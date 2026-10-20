package v57.hubservice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import v57.channel.Channel;
import v57.channel.ChannelServer;
import v57.channel.ListenOptions;
import v57.channel.Sender;
import v57.channel.TypeRef;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End to end against a fake hub: hub/info pinning, the v1 reconnect, the device header and caller identity. */
class FakeHubTest {
  private static final String HUB_KEY = "MCowBQYDK2VwAyEAiojj3XQJ8ZX9UtstPLpdcspnCb8dlBIb83SIAbQPb1w=";

  @TempDir
  Path tmp;

  private ChannelServer<Map<String, String>> server;
  private Thread serverThread;
  private volatile boolean running = true;
  private Service service;

  @AfterEach
  void tearDown() throws Exception {
    if (service != null) {
      service.stop();
    }
    running = false;
    if (serverThread != null) {
      serverThread.join(2000);
    }
    if (server != null) {
      server.close();
    }
    Keychain.setKeyPath(null);
  }

  private static boolean verifies(String publicKey, String data, String signature) throws Exception {
    var key = KeyFactory.getInstance("Ed25519")
        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
    var verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(key);
    verifier.update(data.getBytes(StandardCharsets.UTF_8));
    return verifier.verify(Base64.getDecoder().decode(signature));
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    var deadline = System.currentTimeMillis() + 10_000;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out");
      }
      Thread.sleep(10);
    }
  }

  @Test
  void connectsWhenTheHubStartsLate() throws Exception {
    int port;
    try (var probe = new java.net.ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    service = new Service(new ServiceOptions().address("ws://127.0.0.1:" + port).device(false));
    service.post("echo", body -> body);
    service.start();
    Thread.sleep(500);

    var updates = new CopyOnWriteArrayList<Object>();
    var hub = new Channel<Map<String, String>>()
        .post("hub/service/update", Object.class, Object.class, request -> {
          updates.add(request.body());
          return null;
        });
    server = hub.listen(port, new ListenOptions<>());
    serverThread = new Thread(() -> {
      while (running) {
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
    await(() -> !updates.isEmpty());
  }

  @Test
  void pinsHubKeyReconnectsWithV1AndSeesTheCaller() throws Exception {
    List<Map<String, String>> connects = new CopyOnWriteArrayList<>();
    var latest = new AtomicReference<Sender<Map<String, String>>>();
    var updates = new CopyOnWriteArrayList<Map<String, String>>();
    var hub = new Channel<Map<String, String>>()
        .post("hub/info", Object.class, Object.class,
            request -> Map.of("key", HUB_KEY, "protocol", 1, "pro", true, "mode", "default-allow"))
        .post("hub/service/update", Object.class, Object.class, request -> {
          updates.add(request.state());
          return null;
        });
    server = hub.listen(0, new ListenOptions<Map<String, String>>(
        headers -> {
          var copy = Map.copyOf(headers);
          connects.add(copy);
          return copy;
        },
        (state, sender) -> latest.set(sender),
        null));
    serverThread = new Thread(() -> {
      while (running) {
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

    Keychain.setKeyPath(tmp.resolve("auth").toString());
    var address = "ws://127.0.0.1:" + server.port();
    service = new Service(new ServiceOptions()
        .address(address)
        .name("Echo")
        .deviceName("Mac Studio")
        .deviceKeyPath(tmp.resolve("device.key").toString()));
    service.post("echo/caller", (body, request) -> {
      var caller = request.caller();
      return Map.of("key", caller.key(), "device", caller.deviceName(), "owner", caller.owner(),
          "hubKey", request.hubKey(), "service", request.context().get("service"));
    });
    service.start();

    // First connect v0 → hub/info → pin → reconnect with v1, then the update.
    await(() -> !updates.isEmpty());
    assertTrue(connects.size() >= 2, "expected a v0 connect then a v1 connect, got " + connects);
    assertEquals("0", connects.get(0).get("v"));
    var v1 = updates.get(updates.size() - 1);
    assertEquals("1", v1.get("v"));
    var auth = v1.get("auth").split("\\.");
    assertTrue(verifies(auth[1], "hub1:" + HUB_KEY + ":" + auth[3], auth[2]));
    assertEquals("{\"ws://127.0.0.1:" + server.port() + "\":\"" + HUB_KEY + "\"}",
        Files.readString(tmp.resolve("hubs.json")));
    assertEquals(HUB_KEY, service.hubKey());

    var device = v1.get("device");
    assertNotNull(device);
    var payload = Device.decodeCertificate(device);
    assertEquals(auth[1], payload.get("service"));
    assertEquals("Echo", payload.get("instance"));

    var context = Map.of(
        "service", "s1",
        "hub", Map.of(
            "caller", Map.of(
                "key", auth[1],
                "device", Map.of("id", Device.id((String) payload.get("device")), "name", "Mac Studio"),
                "groups", List.of("owners"),
                "owner", true),
            "hubKey", HUB_KEY));
    var result = latest.get().send(TypeRef.of(Object.class), "echo/caller", null, context).requireValue();
    assertEquals(Map.of("key", auth[1], "device", "Mac Studio", "owner", true, "hubKey", HUB_KEY, "service", "s1"),
        result);
  }
}
