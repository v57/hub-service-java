package v57.hubservice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import v57.channel.Channel;
import v57.channel.ChannelServer;
import v57.channel.JsonCodec;
import v57.channel.ListenOptions;

import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Share publication against a fake hub-net agent (Unix socket) and a fake hub. */
class NetworkTest {
  private Path tmp;
  private ServerSocketChannel agent;
  private Thread agentThread;
  private final List<Map<String, Object>> agentRequests = new CopyOnWriteArrayList<>();
  /** Lets a test tamper with the attestation the fake agent returns. */
  private volatile Function<Map<String, Object>, Map<String, Object>> attestationEdit = a -> a;

  @BeforeEach
  void startAgent() throws Exception {
    // Unix socket paths are short (104 bytes on macOS), so stay out of the deep per-user temp dir.
    tmp = Files.createTempDirectory(Path.of("/tmp"), "hsj");
    agent = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    agent.bind(UnixDomainSocketAddress.of(tmp.resolve("agent.sock")));
    agentThread = new Thread(this::serveAgent);
    agentThread.setDaemon(true);
    agentThread.start();
    Keychain.setKeyPath(tmp.resolve("auth").toString());
  }

  @AfterEach
  void stopAgent() throws Exception {
    agent.close();
    Keychain.setKeyPath(null);
    try (var files = Files.walk(tmp)) {
      files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
  }

  @SuppressWarnings("unchecked")
  private void serveAgent() {
    while (agent.isOpen()) {
      try (SocketChannel connection = agent.accept()) {
        var out = new ByteArrayOutputStream();
        var buffer = ByteBuffer.allocate(8192);
        String text;
        while (true) {
          connection.read(buffer);
          out.write(buffer.array(), 0, buffer.position());
          buffer.clear();
          text = out.toString(StandardCharsets.UTF_8);
          var end = text.indexOf("\r\n\r\n");
          if (end >= 0) {
            var length = Integer.parseInt(text.substring(0, end).replaceAll("(?is).*content-length: *(\\d+).*", "$1"));
            if (text.length() - end - 4 >= length) {
              break;
            }
          }
        }
        var path = text.split(" ")[1];
        var request = (Map<String, Object>) JsonCodec.decode(text.substring(text.indexOf("\r\n\r\n") + 4));
        request.put("_path", path);
        agentRequests.add(request);
        String body;
        if (path.equals("/challenge")) {
          body = "{\"type\":\"attest_response\",\"nonce\":\"nonce-1\"}";
        } else {
          var attestation = new LinkedHashMap<String, Object>();
          attestation.put("version", 1);
          attestation.put("attestationId", "att-1");
          attestation.put("serviceKey", request.get("serviceKey"));
          attestation.put("key", "agent-key");
          attestation.put("shareName", request.get("shareName"));
          attestation.put("protocol", request.get("protocol"));
          attestation.put("target", request.get("target"));
          attestation.put("issuedAt", Instant.now().toString());
          attestation.put("expiresAt", Instant.now().plusSeconds(300).toString());
          attestation.put("signature", "agent-signature");
          body = JsonCodec.encode(attestationEdit.apply(attestation));
        }
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.write(ByteBuffer.wrap(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
            + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)));
        connection.write(ByteBuffer.wrap(bytes));
      } catch (Exception error) {
        // Closed by the test.
      }
    }
  }

  private NetworkPublisher publisher() {
    return new NetworkPublisher().agentSocket(tmp.resolve("agent.sock").toString());
  }

  private static Map<String, Object> publications(Object body) {
    @SuppressWarnings("unchecked")
    var shares = (List<Map<String, Object>>) ((Map<String, Object>) body).get("shares");
    var out = new java.util.ArrayList<Object>();
    for (int i = 0; i < shares.size(); i++) {
      out.add(Map.of("version", 1, "resourceId", "hnr1_" + shares.get(i).get("name"), "authority", "a" + i,
          "providerId", "p", "protocol", shares.get(i).get("protocol"), "overlayAddress", "100.96.0.2", "port", 40000 + i));
    }
    return Map.of("shares", out);
  }

  private static boolean verifies(String publicKey, String data, String signature) throws Exception {
    var key = KeyFactory.getInstance("Ed25519")
        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
    var verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(key);
    verifier.update(data.getBytes(StandardCharsets.UTF_8));
    return verifier.verify(Base64.getDecoder().decode(signature));
  }

  @Test
  @SuppressWarnings("unchecked")
  void publishesAttestedSharesInNameOrder() throws Exception {
    var key = Keychain.key();
    var sent = new CopyOnWriteArrayList<Object>();
    var network = publisher();
    network.publish(new NetworkPublisher.Share("web", "tcp", "127.0.0.1", 8080, Map.of("mode", "hub")), key,
        body -> {
          sent.add(body);
          return publications(body);
        });
    var result = network.publish(new NetworkPublisher.Share("db", "tcp", "localhost", 5432, null), key, body -> {
      sent.add(body);
      return publications(body);
    });

    // The agent's JSON decoder refuses unknown fields: exactly these go out.
    assertEquals(Set.of("_path", "serviceKey", "shareName", "protocol", "target"), agentRequests.get(0).keySet());
    var attest = agentRequests.get(1);
    assertEquals(Set.of("_path", "serviceKey", "shareName", "protocol", "target", "signature"), attest.keySet());
    assertEquals(key.publicKey(), attest.get("serviceKey"));
    assertTrue(verifies(key.publicKey(), "nonce-1", (String) attest.get("signature")));

    var wire = (List<Map<String, Object>>) ((Map<String, Object>) sent.get(1)).get("shares");
    assertEquals(List.of("db", "web"), wire.stream().map(share -> share.get("name")).toList());
    assertEquals(1, wire.get(0).get("version"));
    assertEquals("att-1", ((Map<String, Object>) wire.get(0).get("attestation")).get("attestationId"));
    assertEquals(Map.of("host", "localhost", "port", 5432), wire.get(0).get("target"));
    assertEquals(2, result.size());
    assertEquals("hnr1_web", network.publication("web").get("resourceId"));
  }

  @Test
  void mismatchedAttestationIsRefusedAndRolledBack() {
    attestationEdit = attestation -> {
      attestation.put("shareName", "other");
      return attestation;
    };
    var network = publisher();
    var error = assertThrows(NetworkPublisher.AgentException.class, () -> network.publish(
        new NetworkPublisher.Share("web", "tcp", "127.0.0.1", 8080, null), Keychain.key(), NetworkTest::publications));
    assertEquals("attestation-mismatch", error.code());
    assertEquals(List.of(), network.declaredShares());
  }

  @Test
  void expiredAttestationIsRefused() {
    attestationEdit = attestation -> {
      attestation.put("expiresAt", Instant.now().minusSeconds(1).toString());
      return attestation;
    };
    var error = assertThrows(NetworkPublisher.AgentException.class, () -> publisher().publish(
        new NetworkPublisher.Share("web", "tcp", "127.0.0.1", 8080, null), Keychain.key(), NetworkTest::publications));
    assertEquals("attestation-expired", error.code());
  }

  @Test
  void missingAgentIsReported() {
    var network = new NetworkPublisher().agentSocket(tmp.resolve("none.sock").toString());
    var error = assertThrows(NetworkPublisher.AgentException.class, () -> network.publish(
        new NetworkPublisher.Share("web", "tcp", "127.0.0.1", 8080, null), Keychain.key(), NetworkTest::publications));
    assertEquals("agent-unavailable", error.code());
  }

  @Test
  void invalidSharesAndPublicationsAreRefused() {
    assertThrows(IllegalArgumentException.class,
        () -> NetworkPublisher.validate(new NetworkPublisher.Share("", "tcp", "h", 1, null)));
    assertThrows(IllegalArgumentException.class,
        () -> NetworkPublisher.validate(new NetworkPublisher.Share("a", "sctp", "h", 1, null)));
    assertThrows(IllegalArgumentException.class,
        () -> NetworkPublisher.validate(new NetworkPublisher.Share("a", "tcp", "h", 70000, null)));
    assertThrows(IllegalArgumentException.class,
        () -> NetworkPublisher.validate(new NetworkPublisher.Share("a", "tcp", "h", 1, Map.of("mode", "keys"))));
    var error = assertThrows(IllegalStateException.class, () -> publisher().publish(
        new NetworkPublisher.Share("web", "tcp", "127.0.0.1", 8080, null), Keychain.key(),
        body -> Map.of("shares", List.of())));
    assertTrue(error.getMessage().contains("0 publications for 1 shares"));
  }

  @Test
  void declaredSharesArePublishedOnConnect() throws Exception {
    var replaced = new CopyOnWriteArrayList<Object>();
    var hub = new Channel<Object>()
        .post("hub/service/update", Object.class, Object.class, request -> null)
        .post("network/shares/replace", Object.class, Object.class, request -> {
          replaced.add(request.body());
          return publications(request.body());
        });
    ChannelServer<Object> server = hub.listen(0, new ListenOptions<>());
    var running = new java.util.concurrent.atomic.AtomicBoolean(true);
    var serverThread = new Thread(() -> {
      while (running.get()) {
        server.poll();
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          return;
        }
      }
    });
    serverThread.setDaemon(true);
    serverThread.start();
    var service = new Service(new ServiceOptions().address("ws://127.0.0.1:" + server.port()).device(false));
    service.network().agentSocket(tmp.resolve("agent.sock").toString());
    service.share("web", "tcp", "127.0.0.1", 8080, null);
    try {
      service.start();
      var deadline = System.currentTimeMillis() + 10_000;
      while (replaced.isEmpty() && System.currentTimeMillis() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(1, replaced.size());
      assertEquals("hnr1_web", service.network().publication("web").get("resourceId"));

      var updated = service.updateShare("web", Map.of("mode", "hub"), 9090);
      assertEquals(1, updated.size());
      assertEquals(9090, service.network().declaredShares().get(0).port());
      assertEquals(List.of(), service.removeShare("web"));
    } finally {
      service.stop();
      running.set(false);
      server.close();
    }
  }
}
