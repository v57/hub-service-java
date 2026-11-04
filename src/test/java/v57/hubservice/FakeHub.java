package v57.hubservice;

import v57.channel.Channel;
import v57.channel.ChannelServer;
import v57.channel.ListenOptions;
import v57.channel.Sender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * A channel server standing in for hub-server: it answers {@code hub/info} as
 * told and records every connection's headers. Its tokens are checked by the tests.
 */
final class FakeHub implements AutoCloseable {
  /** {@code (nonce, challenge) -> hub/info answer} */
  interface Answer extends BiFunction<String, String, Object> {
  }

  final List<Map<String, String>> connects = new CopyOnWriteArrayList<>();
  final List<Map<String, String>> updates = new CopyOnWriteArrayList<>();
  final List<String> nonces = new CopyOnWriteArrayList<>();
  final AtomicReference<Sender<Map<String, String>>> latest = new AtomicReference<>();
  private final AtomicInteger asked = new AtomicInteger();
  private final ChannelServer<Map<String, String>> server;
  private final Thread thread;
  private volatile boolean running = true;

  FakeHub(Answer answer) {
    this(answer, 0, hub -> { });
  }

  FakeHub(Answer answer, int port, Consumer<Channel<Map<String, String>>> setup) {
    var hub = new Channel<Map<String, String>>()
        .post("hub/info", Object.class, Object.class, request -> {
          var nonce = nonce(request.body());
          nonces.add(nonce);
          return answer.apply(nonce, "challenge-" + asked.incrementAndGet());
        })
        .post("hub/service/update", Object.class, Object.class, request -> {
          updates.add(request.state());
          return null;
        });
    setup.accept(hub);
    server = hub.listen(port, new ListenOptions<Map<String, String>>(
        headers -> {
          var copy = Map.copyOf(headers);
          connects.add(copy);
          return copy;
        },
        (state, sender) -> latest.set(sender),
        null));
    thread = pump(server, () -> running);
  }

  static Thread pump(ChannelServer<?> server, java.util.function.BooleanSupplier running) {
    var thread = new Thread(() -> {
      while (running.getAsBoolean()) {
        server.poll();
        try {
          Thread.sleep(1);
        } catch (InterruptedException e) {
          return;
        }
      }
    });
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  int port() {
    return server.port();
  }

  String address() {
    return "ws://127.0.0.1:" + server.port();
  }

  /** The connections that carried credentials. */
  List<Map<String, String>> credentialsSent() {
    var sent = new ArrayList<Map<String, String>>();
    for (var headers : connects) {
      if (headers.containsKey("auth") || headers.containsKey("device")) {
        sent.add(headers);
      }
    }
    return sent;
  }

  /** A hub proving the vectors' key {@code name}. */
  static Answer proving(String name) {
    var key = VectorsTest.key(name);
    return (nonce, challenge) -> proof(key, key.publicKey(), nonce, challenge);
  }

  /** A {@code hub/info} answer claiming {@code claimed}, signed with {@code key}. */
  static Map<String, Object> proof(KeyFile key, String claimed, String nonce, String challenge) {
    var answer = new LinkedHashMap<String, Object>();
    answer.put("key", claimed);
    answer.put("protocol", 2);
    answer.put("challenge", challenge);
    answer.put("proof", key.sign(HubProof.signedData(claimed, nonce)));
    return answer;
  }

  /** The nonce in a {@code hub/info} body. */
  static String nonce(Object body) {
    return body instanceof Map<?, ?> map && map.get("nonce") instanceof String nonce ? nonce : "";
  }

  @Override
  public void close() throws Exception {
    running = false;
    thread.join(2000);
    server.close();
  }
}
