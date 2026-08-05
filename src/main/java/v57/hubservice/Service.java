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

/**
 * Lets a Java process connect to a hub (hub-lite/hub-pro), host post/stream
 * API endpoints, and call API through the hub.
 *
 * <p>Lite port of hub-service (TS reference): no auth, no keychain, no
 * crypto. The wire contract matches the TS/Go reference — on every
 * (re)connect {@code hub/profile/update} (if a profile is set) and
 * {@code hub/service/update} are re-sent, because the hub drops services on
 * disconnect.
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
    if (opts.address != null) {
      this.address = opts.address;
    } else {
      var env = System.getenv("HUB");
      this.address = (env != null && !env.isEmpty()) ? env : 1997;
    }
    if ((opts.name != null && !opts.name.isEmpty()) || opts.icon != null) {
      this.profile = new Profile(opts.name, opts.icon);
    } else {
      this.profile = null;
    }
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
      current = channel.connect(addressString(), new ConnectOptions<>());
      client = current;
      sender = current.sender();
      wasConnected = false;
    }
    current.poll();
    var connected = current.isConnected();
    if (connected && !wasConnected) {
      wasConnected = true;
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

  private void sendUpdates(Sender<Object> s) {
    if (profile != null) {
      s.send("hub/profile/update", profile.toMap());
    }
    s.send("hub/service/update", update());
  }

  private String addressString() {
    if (address instanceof Integer port) {
      return "ws://127.0.0.1:" + port;
    }
    return String.valueOf(address);
  }
}
