package v57.hubservice;

import v57.channel.JsonCodec;

import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Hub Network share publication, ported from the TS SDK
 * ({@code hub-service/network.ts}). A share makes a local TCP/UDP port
 * reachable through Hub Network: for each share the local hub-net agent
 * attests that this service owns the port (POST {@code /challenge}, sign the
 * nonce with the service key, POST {@code /attest} over the agent's Unix
 * socket), then the complete set goes to the hub with
 * {@code network/shares/replace}, which answers one publication per share in
 * request order. The declared set is republished after every reconnect.
 */
public final class NetworkPublisher {
  /** Hub Network wire version. */
  public static final int WIRE_VERSION = 1;
  private static final int MAX_SHARE_NAME_LENGTH = 255;

  /** A share: a local target published under a stable name with an access policy. */
  public record Share(String name, String protocol, String host, int port, Map<String, Object> policy) {
    /** The share as the hub expects it (without attestation). */
    public Map<String, Object> toMap() {
      var target = new LinkedHashMap<String, Object>();
      target.put("host", host);
      target.put("port", port);
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("protocol", protocol);
      map.put("target", target);
      if (policy != null) {
        map.put("policy", policy);
      }
      return map;
    }
  }

  /** A local-agent failure (unavailable, timeout, rejected, invalid response, mismatched or expired attestation). */
  public static final class AgentException extends RuntimeException {
    private final String code;

    public AgentException(String code, String message) {
      super(message);
      this.code = code;
    }

    /** {@code agent-unavailable}, {@code agent-timeout}, {@code agent-rejected}, {@code invalid-agent-response}, {@code attestation-mismatch} or {@code attestation-expired}. */
    public String code() {
      return code;
    }
  }

  private final TreeMap<String, Share> shares = new TreeMap<>();
  private volatile List<Map<String, Object>> publications = List.of();
  private final Map<String, Map<String, Object>> publicationsByShare = new LinkedHashMap<>();
  private volatile String agentSocket;
  private volatile long agentTimeoutMs = 5000;

  /** A publisher talking to the agent at {@link #defaultAgentSocket()}. */
  public NetworkPublisher() {
    this.agentSocket = defaultAgentSocket();
  }

  /** {@code $HUB_NET_AGENT_SOCKET}, else the platform's hub-net agent socket. */
  public static String defaultAgentSocket() {
    var env = System.getenv("HUB_NET_AGENT_SOCKET");
    if (env != null && !env.isEmpty()) {
      return env;
    }
    var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return os.contains("linux") ? "/run/hub-net/agent.sock" : "/var/run/hub-net/agent.sock";
  }

  /** Uses the agent socket at {@code path}. */
  public NetworkPublisher agentSocket(String path) {
    this.agentSocket = path;
    return this;
  }

  /** Times agent requests out after {@code ms}. */
  public NetworkPublisher agentTimeout(long ms) {
    this.agentTimeoutMs = ms;
    return this;
  }

  /** Whether any share is declared. */
  public synchronized boolean hasShares() {
    return !shares.isEmpty();
  }

  /** The declared shares, sorted by name. */
  public synchronized List<Share> declaredShares() {
    return new ArrayList<>(shares.values());
  }

  /** Declares a share, published on the next connect and after every reconnect. */
  public synchronized void declare(Share share) {
    validate(share);
    shares.put(share.name(), share);
  }

  /** The current publications, in share-name order. */
  public List<Map<String, Object>> publications() {
    return publications;
  }

  /** The publication of {@code shareName}, or null. */
  public synchronized Map<String, Object> publication(String shareName) {
    return publicationsByShare.get(shareName);
  }

  /** Publishes {@code share} with the declared set; rolls the declaration back on failure. */
  public synchronized List<Map<String, Object>> publish(
      Share share, KeyFile serviceKey, Function<Object, Object> send) {
    validate(share);
    var previous = shares.put(share.name(), share);
    try {
      return synchronize(serviceKey, send);
    } catch (RuntimeException error) {
      if (previous != null) {
        shares.put(share.name(), previous);
      } else {
        shares.remove(share.name());
      }
      throw error;
    }
  }

  /** Removes {@code shareName} and republishes the rest; restores it on failure. */
  public synchronized List<Map<String, Object>> remove(
      String shareName, KeyFile serviceKey, Function<Object, Object> send) {
    var removed = shares.remove(shareName);
    if (removed == null) {
      throw new IllegalArgumentException("Share '" + shareName + "' not found");
    }
    try {
      return synchronize(serviceKey, send);
    } catch (RuntimeException error) {
      shares.put(shareName, removed);
      throw error;
    }
  }

  /** Publishes the whole declared set again (after a reconnect). */
  public synchronized List<Map<String, Object>> republishAll(KeyFile serviceKey, Function<Object, Object> send) {
    return synchronize(serviceKey, send);
  }

  /**
   * Asks the local agent to attest {@code share} for {@code serviceKey}:
   * {@code /challenge} returns a nonce, which the service key signs for
   * {@code /attest}.
   */
  public Map<String, Object> requestAttestation(Share share, KeyFile serviceKey) {
    validate(share);
    var body = new LinkedHashMap<String, Object>();
    body.put("serviceKey", serviceKey.publicKey());
    body.put("shareName", share.name());
    body.put("protocol", share.protocol());
    body.put("target", share.toMap().get("target"));
    var challenge = agentPost("/challenge", body, "challenge");
    if (!(challenge.get("nonce") instanceof String nonce) || nonce.isEmpty()) {
      throw new AgentException("invalid-agent-response", "Hub Network agent returned an invalid challenge");
    }
    body.put("signature", serviceKey.sign(nonce));
    var attestation = agentPost("/attest", body, "attestation");
    validateAttestation(attestation, share, serviceKey.publicKey());
    return attestation;
  }

  /**
   * Attests every declared share and sends the set with
   * {@code network/shares/replace}; {@code send} posts that call's body to
   * the hub and returns its result.
   */
  private List<Map<String, Object>> synchronize(KeyFile serviceKey, Function<Object, Object> send) {
    var declared = new ArrayList<>(shares.values());
    var wire = new ArrayList<Object>();
    for (var share : declared) {
      var attestation = requestAttestation(share, serviceKey);
      var entry = share.toMap();
      entry.put("version", WIRE_VERSION);
      entry.put("attestation", attestation);
      wire.add(entry);
    }
    var response = send.apply(Map.of("shares", wire));
    if (!(response instanceof Map<?, ?> map) || !(map.get("shares") instanceof List<?> list)) {
      throw new IllegalStateException("Hub returned an invalid shares/replace response");
    }
    if (list.size() != declared.size()) {
      throw new IllegalStateException(
          "Hub returned " + list.size() + " publications for " + declared.size() + " shares");
    }
    // Protocol v1 publications carry no share name: the hub keeps request order.
    var correlated = new LinkedHashMap<String, Map<String, Object>>();
    var resourceIds = new java.util.HashSet<String>();
    for (int index = 0; index < declared.size(); index++) {
      var publication = validatePublication(list.get(index), declared.get(index), index);
      if (!resourceIds.add((String) publication.get("resourceId"))) {
        throw new IllegalStateException("Hub returned duplicate resourceId at share index " + index);
      }
      correlated.put(declared.get(index).name(), publication);
    }
    publicationsByShare.clear();
    publicationsByShare.putAll(correlated);
    publications = List.copyOf(correlated.values());
    return publications;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> validatePublication(Object value, Share share, int index) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalStateException("Invalid Hub publication at share index " + index);
    }
    if (!(map.get("version") instanceof Number version) || version.intValue() != WIRE_VERSION) {
      throw new IllegalStateException("Invalid Hub publication version at share index " + index);
    }
    if (!(map.get("resourceId") instanceof String id) || !id.startsWith("hnr1_") || id.length() == 5) {
      throw new IllegalStateException("Invalid Hub publication resourceId at share index " + index);
    }
    for (var field : List.of("authority", "providerId", "overlayAddress")) {
      if (!(map.get(field) instanceof String text) || text.isBlank()) {
        throw new IllegalStateException("Invalid Hub publication " + field + " at share index " + index);
      }
    }
    if (!share.protocol().equals(map.get("protocol"))) {
      throw new IllegalStateException("Hub publication protocol mismatch at share index " + index);
    }
    if (!(map.get("port") instanceof Number port) || !validPort(port)) {
      throw new IllegalStateException("Invalid Hub publication port at share index " + index);
    }
    return (Map<String, Object>) map;
  }

  private static void validateAttestation(Map<String, Object> attestation, Share share, String serviceKey) {
    var target = attestation.get("target") instanceof Map<?, ?> map ? map : Map.of();
    if (!(attestation.get("version") instanceof Number version) || version.intValue() != WIRE_VERSION
        || !serviceKey.equals(attestation.get("serviceKey"))
        || !share.name().equals(attestation.get("shareName"))
        || !share.protocol().equals(attestation.get("protocol"))
        || !share.host().equals(target.get("host"))
        || !(target.get("port") instanceof Number port) || port.intValue() != share.port()) {
      throw new AgentException("attestation-mismatch", "Local-agent attestation does not match the share");
    }
    Instant issuedAt;
    Instant expiresAt;
    try {
      issuedAt = Instant.parse(String.valueOf(attestation.get("issuedAt")));
      expiresAt = Instant.parse(String.valueOf(attestation.get("expiresAt")));
    } catch (Exception error) {
      throw new AgentException("attestation-expired", "Local-agent attestation is expired");
    }
    var now = Instant.now();
    if (!expiresAt.isAfter(issuedAt) || issuedAt.isAfter(now.plusSeconds(30)) || !expiresAt.isAfter(now)) {
      throw new AgentException("attestation-expired", "Local-agent attestation is expired");
    }
  }

  /** Validates a share like the TS SDK: name, protocol, target and policy. */
  public static void validate(Share share) {
    requireText(share.name(), "share name", MAX_SHARE_NAME_LENGTH);
    if (!"tcp".equals(share.protocol()) && !"udp".equals(share.protocol())) {
      throw new IllegalArgumentException("Invalid Hub Network protocol");
    }
    requireText(share.host(), "target host", Integer.MAX_VALUE);
    if (share.port() < 1 || share.port() > 65_535) {
      throw new IllegalArgumentException("Invalid Hub Network target port");
    }
    var policy = share.policy();
    if (policy == null) {
      return;
    }
    switch (String.valueOf(policy.get("mode"))) {
      case "hub", "private", "disabled" -> {
      }
      case "group" -> requireText(policy.get("group"), "policy group", Integer.MAX_VALUE);
      case "keys" -> {
        if (!(policy.get("keys") instanceof List<?> keys) || keys.isEmpty()) {
          throw new IllegalArgumentException("Invalid Hub Network policy keys");
        }
        for (var key : keys) {
          requireText(key, "policy key", Integer.MAX_VALUE);
        }
      }
      default -> throw new IllegalArgumentException("Invalid Hub Network policy mode");
    }
  }

  private static void requireText(Object value, String field, int maxLength) {
    if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength
        || text.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      throw new IllegalArgumentException("Invalid Hub Network " + field);
    }
  }

  private static boolean validPort(Number port) {
    return port.doubleValue() == port.intValue() && port.intValue() >= 1 && port.intValue() <= 65_535;
  }

  /** POSTs JSON to the agent over its Unix socket and decodes the JSON answer. */
  @SuppressWarnings("unchecked")
  private Map<String, Object> agentPost(String path, Object body, String operation) {
    var socket = agentSocket;
    var payload = JsonCodec.encode(body).getBytes(StandardCharsets.UTF_8);
    var request = ("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
        + "Content-Length: " + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    var timedOut = new AtomicBoolean();
    byte[] raw;
    try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      var watchdog = Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(agentTimeoutMs);
          timedOut.set(true);
          channel.close();
        } catch (Exception ignored) {
        }
      });
      try {
        channel.connect(UnixDomainSocketAddress.of(socket));
        channel.write(ByteBuffer.wrap(request));
        channel.write(ByteBuffer.wrap(payload));
        var out = new ByteArrayOutputStream();
        var buffer = ByteBuffer.allocate(8192);
        while (channel.read(buffer) >= 0) {
          out.write(buffer.array(), 0, buffer.position());
          buffer.clear();
        }
        raw = out.toByteArray();
      } finally {
        watchdog.interrupt();
      }
    } catch (Exception error) {
      if (timedOut.get()) {
        throw new AgentException("agent-timeout", "Hub Network agent timed out after " + agentTimeoutMs + " ms");
      }
      throw new AgentException("agent-unavailable", "Hub Network agent is unavailable at " + socket);
    }
    var text = new String(raw, StandardCharsets.UTF_8);
    var headerEnd = text.indexOf("\r\n\r\n");
    if (headerEnd < 0) {
      throw new AgentException("invalid-agent-response", "Hub Network agent returned an invalid response");
    }
    var head = text.substring(0, headerEnd).split("\r\n");
    var status = head[0].split(" ");
    var code = status.length > 1 ? Integer.parseInt(status[1]) : 0;
    var responseBody = text.substring(headerEnd + 4);
    for (var line : head) {
      if (line.toLowerCase(Locale.ROOT).startsWith("transfer-encoding:") && line.toLowerCase(Locale.ROOT).contains("chunked")) {
        responseBody = dechunk(responseBody);
      }
    }
    if (code < 200 || code > 299) {
      var detail = responseBody.trim();
      throw new AgentException("agent-rejected", "Hub Network agent rejected " + operation + " (" + code + ")"
          + (detail.isEmpty() ? "" : ": " + detail));
    }
    try {
      return (Map<String, Object>) JsonCodec.decode(responseBody);
    } catch (Exception error) {
      throw new AgentException("invalid-agent-response", "Hub Network agent returned invalid JSON");
    }
  }

  private static String dechunk(String body) {
    var out = new StringBuilder();
    var index = 0;
    while (index < body.length()) {
      var lineEnd = body.indexOf("\r\n", index);
      if (lineEnd < 0) {
        break;
      }
      var size = Integer.parseInt(body.substring(index, lineEnd).split(";")[0].trim(), 16);
      if (size == 0) {
        break;
      }
      out.append(body, lineEnd + 2, lineEnd + 2 + size);
      index = lineEnd + 2 + size + 2;
    }
    return out.toString();
  }
}
