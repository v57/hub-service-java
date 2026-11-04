package v57.hubservice;

import v57.channel.CallResult;
import v57.channel.Channel;
import v57.channel.ChannelClient;
import v57.channel.ChannelStream;
import v57.channel.ChannelStreams;
import v57.channel.ConnectOptions;
import v57.channel.Sender;
import v57.channel.TypeRef;
import v57.channel.Values;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Lets a Java process connect to a hub (hub-server), host post/stream
 * API endpoints, and call API through the hub.
 *
 * <p>Port of hub-service (TS reference). On every (re)connect the connection
 * carries hub-server auth headers {@code auth} (a fresh token per connect) and
 * {@code v}, then {@code hub/profile/update} (if a profile is set) and
 * {@code hub/service/update} are re-sent, because the hub drops services on
 * disconnect.
 *
 * <p>Auth v2 ({@code Docs/Devices/protocol.md} section 2): before every connect
 * the service asks {@code hub/info} on a short anonymous connection with a fresh
 * nonce. Only an answer with a valid proof of the key it claims gets a token, over
 * that answer's challenge; the first proven key is pinned in {@code hubs.json}
 * beside the key file. A hub that doesn't prove its key, or answers with another
 * than the pinned one, gets nothing and is reported ({@link #lastError()}) while
 * the service keeps trying with a growing delay, and calls wait. Call
 * {@link #forgetHubKey()} when the hub key really changed. Plain {@code ws://}
 * beyond loopback, the overlay and the LAN isn't dialed unless
 * {@code allowPlaintext} says so.
 *
 * <p>Device mode (protocol.md section 3, on by default): every connect also
 * carries a {@code device} header, a certificate for the service key signed
 * by the device key. It is created at connect when missing or with less than
 * 24 hours left (7-day lifetime). A device key that can't be read or created
 * turns device mode off for this service.
 *
 * <p>The channel-java client has no onConnect callback and does not reconnect
 * by itself, so {@link #start()} runs a daemon poll thread that detects the
 * {@code isConnected} false→true transition per connection (and re-establishes
 * the connection when it drops), sending the updates at each transition.
 */
public final class Service {
  private static final long POLL_INTERVAL_MS = 10;
  /**
   * A dial that hasn't connected by then is dropped and redialed: the
   * channel-java client fails a refused upgrade (hub-server answers a bad token
   * with HTTP 401) or a refused TCP connect without telling its owner.
   */
  private static final long CONNECT_TIMEOUT_MS = 3000;
  /** Bounds the {@code hub/info} question before a connect. */
  private static final long HUB_INFO_TIMEOUT_MS = 5000;
  /** Retry delays for a hub that doesn't prove its key, or can't be reached. */
  private static final long HANDSHAKE_RETRY_MS = 1000;
  private static final long HANDSHAKE_MAX_RETRY_MS = 30_000;
  private static final TypeRef<Object> ANY = TypeRef.of(Object.class);

  private final Channel<Object> channel = new Channel<>();
  private final Object address;
  private final Profile profile;
  private final BiFunction<String, String, String> sign;
  private final HubPins pins;
  private final boolean allowPlaintext;
  private volatile String hubKey;
  private volatile String lastError;
  /** Why {@link #start()} dialed nothing. */
  private volatile String refusal;
  private final ServiceOptions options;
  private volatile boolean deviceMode;
  private KeyFile deviceKey;
  private String certificate;
  private long certificateExpiry;
  private String deviceName;
  private final NetworkPublisher network = new NetworkPublisher();
  private final List<AppHeader> apps = new ArrayList<>();
  /** Every registered handler path (post, stream, app), registration order. */
  private final LinkedHashSet<String> paths = new LinkedHashSet<>();
  private final Map<String, ApiSettings> settings = new LinkedHashMap<>();
  private final Object lock = new Object();

  private volatile ChannelClient<Object> client;
  private volatile Sender<Object> sender;
  private volatile boolean started;
  private volatile boolean stopped;
  private boolean wasConnected;
  private long dialedAt;
  private Thread pollThread;

  /** Creates a Service. Options are optional; the first one wins. */
  public Service(ServiceOptions... options) {
    var opts = options.length > 0 ? options[0] : new ServiceOptions();
    this.options = opts;
    // A custom token supplier hides the service key the certificate must name.
    this.deviceMode = opts.device && opts.sign == null;
    if (opts.address != null) {
      this.address = opts.address;
    } else {
      var env = System.getenv("HUB");
      this.address = (env != null && !env.isEmpty())
          ? (env.matches("\\d+") ? Integer.parseInt(env) : env)
          : 1997;
    }
    if ((opts.name != null && !opts.name.isEmpty()) || opts.icon != null) {
      this.profile = new Profile(opts.name, opts.icon);
    } else {
      this.profile = null;
    }
    this.sign = opts.sign;
    this.allowPlaintext = opts.allowPlaintext || "1".equals(System.getenv("HUB_ALLOW_PLAINTEXT"));
    this.pins = HubPins.beside(Keychain.keyPath());
    this.hubKey = opts.hubKey != null ? opts.hubKey : pins.get(addressKey());
  }

  /** The hub key this address is pinned to, or null until a hub proved one. */
  public String hubKey() {
    return hubKey;
  }

  /**
   * Clears the pinned hub key for this address, so the next connect pins what
   * the hub proves. Use it when a hub's key changed on purpose.
   */
  public void forgetHubKey() {
    hubKey = null;
    pins.remove(addressKey());
  }

  /**
   * The instance name the certificate carries: the option, else the profile
   * name, else the working directory's name, else {@code service}.
   */
  public String instance() {
    if (options.instance != null && !options.instance.isEmpty()) {
      return options.instance;
    }
    if (profile != null && profile.name() != null && !profile.name().isEmpty()) {
      return profile.name();
    }
    var directory = java.nio.file.Path.of("").toAbsolutePath().getFileName();
    return directory != null && !directory.toString().isEmpty() ? directory.toString() : "service";
  }

  /**
   * The {@code device} header for the next connect, or null with device mode
   * off. Reuses the current certificate while it has more than 24 hours left.
   */
  synchronized String deviceHeader() {
    if (!deviceMode) {
      return null;
    }
    try {
      if (deviceKey == null) {
        deviceKey = options.deviceKey != null
            ? options.deviceKey
            : Device.loadOrCreateKey(options.deviceKeyPath != null
                ? java.nio.file.Path.of(options.deviceKeyPath) : Device.defaultKeyPath());
      }
      var now = Keychain.now();
      if (certificate == null || certificateExpiry - now < Device.RENEW_BEFORE) {
        if (deviceName == null) {
          deviceName = options.deviceName != null ? options.deviceName : Device.defaultName();
        }
        var platform = options.platform != null ? options.platform : Device.defaultPlatform();
        var exp = now + Device.CERTIFICATE_LIFETIME;
        certificate = Device.certificate(deviceKey, Device.certificatePayload(
            deviceKey.publicKey(), Keychain.publicKey(), instance(), deviceName, platform, options.parent, now, exp));
        certificateExpiry = exp;
      }
      return certificate;
    } catch (RuntimeException error) {
      System.err.println("hub-service: device mode off: " + error.getMessage());
      deviceMode = false;
      return null;
    }
  }

  /** The device key in use, or null before the first connect or with device mode off. */
  public synchronized KeyFile deviceKey() {
    return deviceKey;
  }

  /** The last connect error (a hub that didn't prove its key, a connect it refused with HTTP 401), or null. */
  public String lastError() {
    return lastError;
  }

  /** The address as {@code hubs.json} keys it ({@link #normalizeAddress(Object)}). */
  public String addressKey() {
    return normalizeAddress(address);
  }

  /**
   * Normalizes a hub address like channel TS does (protocol.md section 2): a
   * bare port becomes {@code ws://localhost:<port>}; a URL keeps its form with
   * the scheme and host lowercased and no trailing slash.
   */
  public static String normalizeAddress(Object address) {
    if (address instanceof Integer port) {
      return "ws://localhost:" + port;
    }
    var text = String.valueOf(address).trim();
    if (text.matches("\\d+")) {
      return "ws://localhost:" + text;
    }
    var schemeEnd = text.indexOf("://");
    if (schemeEnd >= 0) {
      var authorityEnd = text.indexOf('/', schemeEnd + 3);
      if (authorityEnd < 0) {
        authorityEnd = text.length();
      }
      text = text.substring(0, authorityEnd).toLowerCase(java.util.Locale.ROOT) + text.substring(authorityEnd);
    }
    while (text.endsWith("/")) {
      text = text.substring(0, text.length() - 1);
    }
    return text;
  }

  /** The configured profile (null when neither name nor icon was set). */
  public Profile profile() {
    return profile;
  }

  /**
   * Registers a request/response API endpoint. The handler receives the
   * decoded body and returns the response body; throw a
   * {@link v57.channel.ChannelException} to signal an error to the caller.
   */
  public Service post(String path, Function<Object, Object> handler, ApiSettings... settings) {
    addPath(path, settings);
    channel.post(path, Object.class, Object.class, request -> handler.apply(request.body()));
    return this;
  }

  /**
   * Registers a request/response API endpoint whose handler also receives the
   * {@link Request}: the caller identity the hub forwards
   * ({@link Request#caller()}, only when the request's hub key is the pinned
   * one) and the whole request context.
   */
  public Service post(String path, BiFunction<Object, Request, Object> handler, ApiSettings... settings) {
    addPath(path, settings);
    channel.post(path, ANY, ANY, ANY, request ->
        handler.apply(request.body(), Request.of(request.path(), request.body(), request.context(), hubKey)));
    return this;
  }

  /**
   * Registers a streaming API endpoint. The handler receives the decoded body
   * and returns a {@link ChannelStream} of values to stream back.
   */
  public Service stream(String path, Function<Object, ChannelStream<Object>> handler, ApiSettings... settings) {
    addPath(path, settings);
    channel.stream(path, Object.class, Object.class, request -> handler.apply(request.body()));
    return this;
  }

  /**
   * Registers a streaming API endpoint whose handler also receives the
   * {@link Request} with the caller identity.
   */
  public Service stream(
      String path, BiFunction<Object, Request, ChannelStream<Object>> handler, ApiSettings... settings) {
    addPath(path, settings);
    channel.stream(path, ANY, ANY, ANY, request ->
        handler.apply(request.body(), Request.of(request.path(), request.body(), request.context(), hubKey)));
    return this;
  }

  /** Registers an app: header goes to the hub apps list, its path serves the AppInterface as a stream. */
  public Service app(App app) {
    synchronized (lock) {
      apps.add(app.header());
    }
    addPath(app.header().path, null);
    var iface = app.interfaceMap();
    channel.stream(app.header().path, Object.class, Object.class, request -> ChannelStreams.fromIterable(List.of(iface)));
    return this;
  }

  /** Hub Network share publication: agent socket settings and the current publications. */
  public NetworkPublisher network() {
    return network;
  }

  /**
   * Declares a Hub Network share before {@link #start()}: the local
   * {@code host:port} is published under {@code name} on connect and after
   * every reconnect. {@code policy} is {@code {"mode": "hub" | "private" |
   * "disabled"}}, {@code {"mode": "group", "group": ..}} or
   * {@code {"mode": "keys", "keys": [..]}}; null means private.
   */
  public Service share(String name, String protocol, String host, int port, Map<String, Object> policy) {
    network.declare(new NetworkPublisher.Share(name, protocol, host, port, policyOrPrivate(policy)));
    return this;
  }

  /**
   * Publishes a Hub Network share now (TS {@code publishShare}): the local
   * hub-net agent attests the port, then the declared set goes to the hub.
   * Returns every current publication. Requires {@link #start()}.
   */
  public List<Map<String, Object>> publishShare(
      String name, String protocol, String host, int port, Map<String, Object> policy) {
    return network.publish(new NetworkPublisher.Share(name, protocol, host, port, policyOrPrivate(policy)),
        shareKey(), this::replaceShares);
  }

  /** Changes a published share's policy and/or port (null keeps the current one) and republishes. */
  public List<Map<String, Object>> updateShare(String name, Map<String, Object> policy, Integer port) {
    var current = network.declaredShares().stream().filter(share -> share.name().equals(name)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Share '" + name + "' not found"));
    return network.publish(new NetworkPublisher.Share(name, current.protocol(), current.host(),
        port != null ? port : current.port(), policy != null ? policy : current.policy()), shareKey(), this::replaceShares);
  }

  /** Removes a share and republishes the rest. */
  public List<Map<String, Object>> removeShare(String name) {
    return network.remove(name, shareKey(), this::replaceShares);
  }

  private static Map<String, Object> policyOrPrivate(Map<String, Object> policy) {
    return policy != null ? policy : Map.of("mode", "private");
  }

  private KeyFile shareKey() {
    if (sign != null) {
      throw new IllegalStateException("Share publication needs the keychain service key, not a custom sign");
    }
    return Keychain.key();
  }

  private Object replaceShares(Object body) {
    return send("network/shares/replace", body);
  }

  /** Calls an API through the hub (client side). Requires {@link #start()}. */
  public Object send(String path) {
    return send(path, null);
  }

  /** Calls an API through the hub (client side). Requires {@link #start()}. */
  public Object send(String path, Object body) {
    var s = senderOrError();
    CallResult<Object> result = s.send(path, body);
    return result.requireValue();
  }

  /** Opens a stream call through the hub (client side). Requires {@link #start()}. */
  public Values<Object> values(String path) {
    return values(path, null);
  }

  /** Opens a stream call through the hub (client side). Requires {@link #start()}. */
  public Values<Object> values(String path, Object body) {
    var s = senderOrError();
    return s.values(Object.class, path, body);
  }

  /**
   * Connects to the hub and starts the daemon poll thread. On every
   * (re)connect the profile update (if set) and the service update are sent.
   */
  public Service start() {
    refusal = null;
    if (!allowPlaintext && HubTransport.of(address) == HubTransport.REFUSED) {
      refusal = addressKey() + " would carry this service's key unencrypted. Use wss://, or set allowPlaintext"
          + " (HUB_ALLOW_PLAINTEXT=1) on a private network";
      System.err.println("hub-service: " + refusal);
      return this;
    }
    synchronized (lock) {
      if (started) {
        return this;
      }
      started = true;
    }
    pollThread = new Thread(this::pollLoop, "hub-service");
    pollThread.setDaemon(true);
    pollThread.start();
    return this;
  }

  /** Stops the poll thread and closes the connection. A handshake waiting to try again ends with it. */
  public void stop() {
    stopped = true;
    disposeClient();
    var thread = pollThread;
    if (thread != null && thread != Thread.currentThread()) {
      thread.interrupt();
    }
  }

  /**
   * Builds the current {@code hub/service/update} payload: services sorted by
   * path (one entry per registered handler) plus the apps list.
   */
  public Map<String, Object> update() {
    var payload = new LinkedHashMap<String, Object>();
    var services = new ArrayList<Object>();
    List<String> sortedPaths;
    List<AppHeader> appSnapshot;
    synchronized (lock) {
      sortedPaths = new ArrayList<>(paths);
      appSnapshot = new ArrayList<>(apps);
    }
    Collections.sort(sortedPaths);
    for (var path : sortedPaths) {
      services.add(serviceHeader(path, settings.get(path)));
    }
    payload.put("services", services);
    var appsList = new ArrayList<Object>();
    for (var app : appSnapshot) {
      appsList.add(app.toMap());
    }
    payload.put("apps", appsList);
    return payload;
  }

  private static Map<String, Object> serviceHeader(String path, ApiSettings settings) {
    var entry = new LinkedHashMap<String, Object>();
    entry.put("path", path);
    if (settings != null) {
      if (settings.permissions != null) {
        var permissions = new LinkedHashMap<String, Object>();
        if (settings.permissions.group != null && !settings.permissions.group.isEmpty()) {
          permissions.put("group", settings.permissions.group);
        }
        permissions.put("name", settings.permissions.name);
        if (settings.permissions.allows != null) {
          permissions.put("allows", settings.permissions.allows);
        }
        entry.put("permissions", permissions);
      }
      if (settings.limit != null) {
        entry.put("limit", settings.limit);
      }
    }
    return entry;
  }

  private void addPath(String path, ApiSettings[] settings) {
    synchronized (lock) {
      paths.add(path);
      if (settings != null && settings.length > 0 && settings[0] != null) {
        var st = settings[0];
        if (st.permissions != null && (st.permissions.group == null || st.permissions.group.isEmpty())) {
          st.permissions.group = path.split("/")[0];
        }
        this.settings.put(path, st);
      }
    }
  }

  /** The connection's sender. While the service is started and has none yet, the call waits for one. */
  private Sender<Object> senderOrError() {
    while (true) {
      var reason = refusal;
      if (reason != null) {
        throw new IllegalStateException(reason);
      }
      var s = sender;
      if (s != null) {
        return s;
      }
      if (!started || stopped) {
        throw new IllegalStateException("Service not started");
      }
      try {
        Thread.sleep(POLL_INTERVAL_MS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for the hub", interrupted);
      }
    }
  }

  private void pollLoop() {
    while (!stopped) {
      try {
        step();
      } catch (Throwable error) {
        disposeClient();
      }
      try {
        Thread.sleep(POLL_INTERVAL_MS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void step() {
    var current = client;
    if (current == null) {
      var headers = authHeaders();
      if (headers == null) {
        return; // Stopped while waiting.
      }
      try {
        current = channel.connect(addressString(), new ConnectOptions<>(null, headers));
      } catch (RuntimeException error) {
        reportConnectError(error);
        throw error;
      }
      client = current;
      sender = current.sender();
      wasConnected = false;
      dialedAt = System.currentTimeMillis();
      if (stopped) {
        disposeClient();
        return;
      }
    }
    current.poll();
    var connected = current.isConnected();
    if (connected && !wasConnected) {
      wasConnected = true;
      lastError = null;
      sendUpdates(current.sender());
    } else if (!connected && !wasConnected && System.currentTimeMillis() - dialedAt > CONNECT_TIMEOUT_MS) {
      reportConnectError(new IllegalStateException("not connected after " + CONNECT_TIMEOUT_MS
          + " ms (the hub is down or refused the connect)"));
      disposeClient();
    } else if (!connected && wasConnected) {
      // Connection dropped (hub removes our services on disconnect): the
      // channel-java client does not reconnect, so dispose it and reconnect.
      wasConnected = false;
      disposeClient();
    }
  }

  private void disposeClient() {
    var current = client;
    client = null;
    sender = null;
    wasConnected = false;
    if (current != null) {
      try {
        current.stop();
      } catch (Throwable ignored) {
      }
    }
  }

  private void reportConnectError(RuntimeException error) {
    var message = String.valueOf(error.getMessage());
    for (var cause = error.getCause(); cause != null; cause = cause.getCause()) {
      message += ": " + cause.getMessage();
    }
    // hub-server refuses a bad token with HTTP 401, which the client doesn't surface, and its reason
    // (invalid signature, key revoked) stays on the hub; so any failed dial is reported once.
    report("connect to " + addressKey() + " (hub key " + hubKey + ") failed: " + message
        + ". If the hub key changed or a different server answers, call forgetHubKey().");
  }

  /** Records a failure, and reports it once per distinct message. */
  private void report(String message) {
    if (!message.equals(lastError)) {
      System.err.println("hub-service: " + message);
    }
    lastError = message;
  }

  private void sendUpdates(Sender<Object> s) {
    if (profile != null) {
      s.send("hub/profile/update", profile.toMap());
    }
    s.send("hub/service/update", update());
    if (network.hasShares() && sign == null) {
      try {
        network.republishAll(Keychain.key(), body -> s.send("network/shares/replace", body).requireValue());
      } catch (RuntimeException error) {
        System.err.println("hub-service: failed to republish network shares: " + error.getMessage());
      }
    }
  }

  /**
   * Runs before every connection attempt (protocol.md section 2). Comes back
   * with a token over a fresh challenge from a hub that proved its key; until
   * one has, it holds the attempt back, so nothing goes out and calls keep
   * waiting. Null once the service is stopped.
   */
  Map<String, String> authHeaders() {
    var delay = HANDSHAKE_RETRY_MS;
    while (!stopped) {
      try {
        return handshake();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return null;
      } catch (RuntimeException error) {
        report(addressKey() + ": " + error.getMessage() + ", sending it nothing");
      }
      try {
        Thread.sleep(delay);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return null;
      }
      delay = Math.min(delay * 2, HANDSHAKE_MAX_RETRY_MS);
    }
    return null;
  }

  /** Asks the hub for its proof, checks it against the pin and signs for that answer alone. */
  private Map<String, String> handshake() throws InterruptedException {
    var info = HubProof.ask(addressString(), HUB_INFO_TIMEOUT_MS);
    var pinned = hubKey != null ? hubKey : pins.get(addressKey());
    if (pinned != null && !pinned.equals(info.key())) {
      throw new IllegalStateException("it answers with key " + info.key() + ", not the pinned " + pinned
          + ". Call forgetHubKey() if its key really changed");
    }
    hubKey = info.key();
    if (pinned == null) {
      try {
        pins.put(addressKey(), info.key());
      } catch (RuntimeException error) {
        // Unwritable pin file: keep the key for this process only.
        System.err.println("hub-service: pin hub key: " + error.getMessage());
      }
    }
    return signedHeaders(info.key(), info.challenge());
  }

  /** The headers for one connect: a token over {@code challenge} and the device certificate. */
  Map<String, String> signedHeaders(String key, String challenge) {
    var headers = new LinkedHashMap<String, String>();
    var token = sign != null ? sign.apply(key, challenge) : Keychain.signHub(key, challenge);
    if (token == null) {
      throw new IllegalStateException("the custom sign returned no token");
    }
    headers.put("auth", token);
    headers.put("v", Keychain.AUTH_VERSION);
    var device = deviceHeader();
    if (device != null) {
      headers.put("device", device);
    }
    return headers;
  }

  private String addressString() {
    if (address instanceof Integer port) {
      return "ws://127.0.0.1:" + port;
    }
    var text = String.valueOf(address).trim();
    return text.matches("\\d+") ? "ws://127.0.0.1:" + text : text;
  }
}
