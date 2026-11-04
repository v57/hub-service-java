package v57.hubservice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubAuthTest {
  private static final String HUB_KEY = "MCowBQYDK2VwAyEAiojj3XQJ8ZX9UtstPLpdcspnCb8dlBIb83SIAbQPb1w=";

  @TempDir
  Path tmp;

  private Service service;
  private FakeHub hub;

  @AfterEach
  void tearDown() throws Exception {
    if (service != null) {
      service.stop();
    }
    if (hub != null) {
      hub.close();
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

  private Service serviceOn(FakeHub hub) {
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    return new Service(new ServiceOptions().address(hub.address()).device(false));
  }

  @Test
  void signHubSignsItsChallenge() throws Exception {
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    var parts = Keychain.signHub(HUB_KEY, "nonce-1").split("\\.");
    assertEquals(5, parts.length);
    assertEquals("nonce-1", parts[4]);
    assertTrue(verifies(parts[1], "hub1:" + HUB_KEY + ":nonce-1:" + parts[3], parts[2]));
  }

  @Test
  void sendsATokenOnlyAfterProofAndPinsTheKey() throws Exception {
    hub = new FakeHub(FakeHub.proving("hub"));
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    service = new Service(new ServiceOptions().address(hub.address()).name("Echo")
        .deviceName("Mac Studio").deviceKeyPath(tmp.resolve("device.key").toString()));
    assertNull(service.hubKey());
    service.start();
    await(() -> !hub.updates.isEmpty());

    // hub/info went on an anonymous connection before the service's own.
    assertEquals(2, hub.connects.size());
    assertFalse(hub.connects.get(0).containsKey("auth"));
    assertFalse(hub.connects.get(0).containsKey("device"));
    assertFalse(hub.connects.get(0).containsKey("v"));
    var headers = hub.connects.get(1);
    assertEquals("1", headers.get("v"));
    assertTrue(headers.containsKey("device"));
    var auth = headers.get("auth").split("\\.");
    assertEquals("challenge-1", auth[4]);
    assertTrue(verifies(auth[1], "hub1:" + HUB_KEY + ":challenge-1:" + auth[3], auth[2]));

    assertEquals(HUB_KEY, service.hubKey());
    assertEquals("{\"" + hub.address() + "\":\"" + HUB_KEY + "\"}", Files.readString(tmp.resolve("hubs.json")));
    assertTrue(hub.nonces.get(0).matches("[A-Za-z0-9_-]{43}"), hub.nonces.get(0));
  }

  @Test
  void callsMadeRightAfterStartWaitForTheConnection() throws Exception {
    hub = new FakeHub(FakeHub.proving("hub"), 0, channel -> channel.post("ping", Object.class, Object.class, request -> "pong"));
    service = serviceOn(hub).start();
    assertEquals("pong", service.send("ping"));
  }

  @Test
  void everyConnectGetsItsOwnNonceAndChallenge() throws Exception {
    hub = new FakeHub(FakeHub.proving("hub"));
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    var first = new Service(new ServiceOptions().address(hub.address()).device(false)).start();
    service = new Service(new ServiceOptions().address(hub.address()).device(false));
    try {
      await(() -> hub.updates.size() == 1);
      service.start();
      await(() -> hub.updates.size() == 2);
    } finally {
      first.stop();
    }
    assertEquals(2, hub.nonces.size());
    assertNotEquals(hub.nonces.get(0), hub.nonces.get(1));
    var one = hub.updates.get(0).get("auth").split("\\.");
    var two = hub.updates.get(1).get("auth").split("\\.");
    assertEquals(5, one.length);
    assertNotEquals(one[4], two[4]);
    assertEquals(HUB_KEY, new HubPins(tmp.resolve("hubs.json")).get(hub.address()));
  }

  @Test
  void reconnectsAskAgain() throws Exception {
    hub = new FakeHub(FakeHub.proving("hub"));
    service = serviceOn(hub).start();
    await(() -> hub.updates.size() == 1);
    hub.latest.get().stop();
    await(() -> hub.updates.size() == 2);
    assertEquals(2, hub.nonces.size());
    assertNotEquals(hub.updates.get(0).get("auth"), hub.updates.get(1).get("auth"));
  }

  private void getsNothing(FakeHub.Answer answer) throws Exception {
    hub = new FakeHub(answer);
    service = serviceOn(hub).start();
    await(() -> !hub.nonces.isEmpty());
    Thread.sleep(1500);
    assertEquals(List.of(), hub.credentialsSent());
    assertEquals(List.of(), hub.updates);
    assertNull(service.hubKey());
    assertFalse(Files.exists(tmp.resolve("hubs.json")));
    assertTrue(service.lastError().contains("didn't prove"), service.lastError());
  }

  @Test
  void aHubWithoutProofGetsNothing() throws Exception {
    getsNothing((nonce, challenge) -> Map.of("key", HUB_KEY, "protocol", 2, "challenge", challenge));
  }

  @Test
  void aHubClaimingTheRealKeyButSigningWithItsOwnGetsNothing() throws Exception {
    var own = VectorsTest.key("service");
    getsNothing((nonce, challenge) -> FakeHub.proof(own, HUB_KEY, nonce, challenge));
  }

  @Test
  void aHubProvingAnotherKeyThanThePinGetsNothing() throws Exception {
    hub = new FakeHub(FakeHub.proving("service"));
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    new HubPins(tmp.resolve("hubs.json")).put(hub.address(), HUB_KEY);
    service = new Service(new ServiceOptions().address(hub.address()).device(false)).start();
    await(() -> !hub.nonces.isEmpty());
    Thread.sleep(1500);
    assertEquals(List.of(), hub.credentialsSent());
    assertEquals(HUB_KEY, service.hubKey());
    assertEquals(HUB_KEY, new HubPins(tmp.resolve("hubs.json")).get(hub.address()));
    assertTrue(service.lastError().contains("not the pinned"), service.lastError());
  }

  @Test
  void stopEndsAWaitingHandshakeAndItsCalls() throws Exception {
    hub = new FakeHub((nonce, challenge) -> Map.of("key", HUB_KEY));
    service = serviceOn(hub).start();
    var call = CompletableFuture.supplyAsync(() -> {
      try {
        return service.send("ping");
      } catch (IllegalStateException error) {
        return error.getMessage();
      }
    });
    await(() -> !hub.nonces.isEmpty());
    var stoppedAt = System.currentTimeMillis();
    service.stop();
    assertEquals("Service not started", call.get(1, TimeUnit.SECONDS));
    assertTrue(System.currentTimeMillis() - stoppedAt < 1000);
  }

  @Test
  void aPublicPlainAddressIsNotDialed() {
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    service = new Service(new ServiceOptions().address("ws://hub.example.com:1997").device(false)).start();
    var error = assertThrows(IllegalStateException.class, () -> service.send("x"));
    assertTrue(error.getMessage().contains("unencrypted"), error.getMessage());
    var values = assertThrows(IllegalStateException.class, () -> service.values("x"));
    assertTrue(values.getMessage().contains("unencrypted"), values.getMessage());
  }

  @Test
  void allowPlaintextDialsARefusedAddress() throws Exception {
    // The option lets a refused address through, to a name that goes nowhere.
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    service = new Service(new ServiceOptions().address("ws://hub.invalid:1").device(false).allowPlaintext(true)).start();
    await(() -> service.lastError() != null);
    assertFalse(service.lastError().contains("unencrypted"), service.lastError());
  }

  @Test
  void pinnedKeyBesideKeyFileIsUsedAndForgotten() throws Exception {
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    new HubPins(tmp.resolve("hubs.json")).put("ws://localhost:4100", HUB_KEY);
    assertEquals("{\"ws://localhost:4100\":\"" + HUB_KEY + "\"}", Files.readString(tmp.resolve("hubs.json")));

    var service = new Service(new ServiceOptions().address(4100).device(false));
    assertEquals(HUB_KEY, service.hubKey());

    // Another address has no pin.
    assertNull(new Service(new ServiceOptions().address("ws://other:1997")).hubKey());

    service.forgetHubKey();
    assertNull(service.hubKey());
    assertEquals("{}", Files.readString(tmp.resolve("hubs.json")));
  }

  @Test
  void hubKeyOptionIsThePin() {
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    var service = new Service(new ServiceOptions().address(4100).device(false).hubKey(HUB_KEY));
    assertEquals(HUB_KEY, service.hubKey());
    service.forgetHubKey();
    assertNull(service.hubKey());
  }

  @Test
  void addressesNormalizeLikeChannelTs() {
    assertEquals("ws://localhost:1997", Service.normalizeAddress(1997));
    assertEquals("ws://localhost:1997", Service.normalizeAddress("1997"));
    assertEquals("wss://hub.example.com/path", Service.normalizeAddress("WSS://Hub.Example.COM/path/"));
    assertEquals("ws://127.0.0.1:1997", Service.normalizeAddress("ws://127.0.0.1:1997/"));
  }

  @Test
  void customSignGetsTheProvenKeyAndTheChallenge() throws Exception {
    hub = new FakeHub(FakeHub.proving("hub"));
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    service = new Service(new ServiceOptions().address(hub.address())
        .sign((key, challenge) -> "key.custom." + key + "." + challenge)).start();
    await(() -> !hub.updates.isEmpty());
    var headers = hub.updates.get(0);
    assertEquals("key.custom." + HUB_KEY + ".challenge-1", headers.get("auth"));
    assertEquals("1", headers.get("v"));
    assertNull(headers.get("device"));
  }

  @Test
  void customSignDoesNotRunWithoutProof() throws Exception {
    var signed = new java.util.concurrent.atomic.AtomicInteger();
    hub = new FakeHub((nonce, challenge) -> Map.of("key", HUB_KEY, "challenge", challenge));
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    service = new Service(new ServiceOptions().address(hub.address()).sign((key, challenge) -> {
      signed.incrementAndGet();
      return "key.custom";
    })).start();
    await(() -> !hub.nonces.isEmpty());
    Thread.sleep(300);
    assertEquals(0, signed.get());
    assertEquals(List.of(), hub.credentialsSent());
  }

  @Test
  void corruptPinFileCountsAsEmpty() throws Exception {
    Files.writeString(tmp.resolve("hubs.json"), "not json");
    var pins = new HubPins(tmp.resolve("hubs.json"));
    assertNull(pins.get("1997"));
    pins.put("1997", HUB_KEY);
    assertEquals(HUB_KEY, pins.get("1997"));
  }
}
