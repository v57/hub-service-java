package v57.hubservice;

import v57.channel.CallResult;
import v57.channel.Channel;
import v57.channel.ChannelClient;
import v57.channel.ChannelStream;
import v57.channel.ChannelStreams;
import v57.channel.ConnectOptions;
import v57.channel.Sender;
import v57.channel.Values;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lets a Java process connect to a hub (hub-lite/hub-pro), host post/stream
 * API endpoints, and call API through the hub.
 *
 * <p>Port of hub-service (TS reference). On every (re)connect the connection
 * carries hub-pro auth headers {@code auth} (a fresh token per connect) and
 * {@code v}, then {@code hub/profile/update} (if a profile is set) and
 * {@code hub/service/update} are re-sent, because the hub drops services on
 * disconnect. hub-lite ignores the headers.
 *
 * <p>Hub-bound auth ({@code Docs/Devices/protocol.md} section 2): with no
 * pinned key for the address, the service connects with {@code v: 0}, calls
 * {@code hub/info}, pins the returned key in {@code hubs.json} beside the key
 * file and reconnects at once with {@code v: 1} tokens signed over
 * {@code hub1:<hub key>:<expiry>}. A hub that doesn't answer
 * {@code hub/info} (hub-lite) keeps getting v0. A refused v1 connect is
 * reported ({@link #lastError()}), never silently downgraded; call
 * {@link #forgetHubKey()} when the hub key really changed.
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

  private final Channel<Object> channel = new Channel<>();
  private final Object address;
  private final Profile profile;
  private final Supplier<String> sign;
  private final HubPins pins;
  private volatile String hubKey;
  private volatile String lastError;
  private final ServiceOptions options;
  private volatile boolean deviceMode;
  private KeyFile deviceKey;
  private String certificate;
  private long certificateExpiry;
  private String deviceName;
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
    this.pins = opts.sign == null ? HubPins.beside(Keychain.keyPath()) : null;
    this.hubKey = opts.hubKey != null ? opts.hubKey : (pins != null ? pins.get(addressKey()) : null);
  }

  /** The hub key v1 tokens are bound to, or null while connecting with v0. */
  public String hubKey() {
    return hubKey;
  }

  /**
   * Clears the pinned hub key for this address, so the next connect uses v0
   * and pins whatever key {@code hub/info} returns. Use it when a hub's key
   * changed on purpose.
   */
  public void forgetHubKey() {
    hubKey = null;
    if (pins != null) {
      pins.remove(addressKey());
    }
  }

  /**
   * The instance name the certificate carries: the option, else the profile
   * name, else the first segment of the first registered path, else
   * {@code service}.
   */
  public String instance() {
    if (options.instance != null && !options.instance.isEmpty()) {
      return options.instance;
    }
    if (profile != null && profile.name() != null && !profile.name().isEmpty()) {
      return profile.name();
    }
    synchronized (lock) {
      if (!paths.isEmpty()) {
        return paths.iterator().next().split("/")[0];
      }
    }
    return "service";
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

  /** The last connect error (e.g. a v1 connect refused with {@code invalid signature}), or null. */
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
   * Registers a streaming API endpoint. The handler receives the decoded body
   * and returns a {@link ChannelStream} of values to stream back.
   */
  public Service stream(String path, Function<Object, ChannelStream<Object>> handler, ApiSettings... settings) {
    addPath(path, settings);
    channel.stream(path, Object.class, Object.class, request -> handler.apply(request.body()));
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

  /** Stops the poll thread and closes the connection. */
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

  private Sender<Object> senderOrError() {
    var s = sender;
    if (s == null) {
      throw new IllegalStateException("Service not started");
    }
    return s;
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
      try {
        current = channel.connect(addressString(), new ConnectOptions<>(null, headers));
      } catch (RuntimeException error) {
        reportConnectError(headers, error);
        throw error;
      }
      client = current;
      sender = current.sender();
      wasConnected = false;
    }
    current.poll();
    var connected = current.isConnected();
    if (connected && !wasConnected) {
      wasConnected = true;
      lastError = null;
      if (learnHubKey(current.sender())) {
        // Pinned the hub key: reconnect at once with a v1 token.
        disposeClient();
        return;
      }
      sendUpdates(current.sender());
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

  /**
   * On a v0 connection with no pinned key, asks {@code hub/info} for the hub
   * key and pins it. Returns true when a key was newly pinned.
   */
  private boolean learnHubKey(Sender<Object> s) {
    if (pins == null || hubKey != null) {
      return false;
    }
    CallResult<Object> result;
    try {
      result = s.send("hub/info");
    } catch (RuntimeException error) {
      return false;
    }
    if (!result.isOk() || !(result.value() instanceof Map<?, ?> info) || !(info.get("key") instanceof String key)) {
      return false; // hub-lite or an older hub-pro: stay on v0.
    }
    hubKey = key;
    try {
      pins.put(addressKey(), key);
    } catch (RuntimeException error) {
      // Unwritable pin file: keep the key for this process only.
    }
    return true;
  }

  private void reportConnectError(Map<String, String> headers, RuntimeException error) {
    var message = String.valueOf(error.getMessage());
    for (var cause = error.getCause(); cause != null; cause = cause.getCause()) {
      message += ": " + cause.getMessage();
    }
    if ("1".equals(headers.get("v")) && message.contains("invalid signature") && !message.equals(lastError)) {
      System.err.println("hub-service: the hub at " + addressKey() + " refused the v1 token for hub key "
          + hubKey + " (invalid signature). The hub key changed or a different server answers;"
          + " call forgetHubKey() if the change is expected.");
    }
    lastError = message;
  }

  private void sendUpdates(Sender<Object> s) {
    if (profile != null) {
      s.send("hub/profile/update", profile.toMap());
    }
    s.send("hub/service/update", update());
  }

  /**
   * Fresh hub-pro auth headers for a connect: {@code auth} is a fresh signed
   * token ({@code key.<pub>.<sig>.<expiry>}) and {@code v} is the protocol
   * version: {@code 1} with a token bound to the pinned hub key, else
   * {@code 0}. Built once per connect, so every (re)connect gets a fresh
   * token. hub-lite ignores the headers.
   */
  Map<String, String> authHeaders() {
    var headers = new LinkedHashMap<String, String>();
    var key = hubKey;
    if (sign != null) {
      headers.put("auth", sign.get());
      headers.put("v", "0");
    } else if (key != null) {
      headers.put("auth", Keychain.signHub(key));
      headers.put("v", "1");
    } else {
      headers.put("auth", Keychain.sign());
      headers.put("v", "0");
    }
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
    return String.valueOf(address);
  }
}
