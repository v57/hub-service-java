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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubAuthTest {
  private static final String HUB_KEY = "MCowBQYDK2VwAyEAiojj3XQJ8ZX9UtstPLpdcspnCb8dlBIb83SIAbQPb1w=";

  @TempDir
  Path tmp;

  @AfterEach
  void resetKeyPath() {
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

  @Test
  void signHubSignsHubBoundString() throws Exception {
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    var parts = Keychain.signHub(HUB_KEY).split("\\.");
    assertEquals(4, parts.length);
    assertTrue(verifies(parts[1], "hub1:" + HUB_KEY + ":" + parts[3], parts[2]));
    assertFalse(verifies(parts[1], parts[3], parts[2]), "a v1 token must not verify as v0");
  }

  @Test
  void connectsWithV0UntilAHubKeyIsKnown() {
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    var service = new Service(new ServiceOptions().address(4100));
    assertNull(service.hubKey());
    var headers = service.authHeaders();
    assertEquals("0", headers.get("v"));
  }

  @Test
  void hubKeyOptionSignsV1() throws Exception {
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    var service = new Service(new ServiceOptions().address(4100).hubKey(HUB_KEY));
    var headers = service.authHeaders();
    assertEquals("1", headers.get("v"));
    var parts = headers.get("auth").split("\\.");
    assertTrue(verifies(parts[1], "hub1:" + HUB_KEY + ":" + parts[3], parts[2]));
  }

  @Test
  void pinnedKeyBesideKeyFileIsUsedAndForgotten() throws Exception {
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    new HubPins(tmp.resolve("hubs.json")).put("ws://localhost:4100", HUB_KEY);
    assertEquals("{\"ws://localhost:4100\":\"" + HUB_KEY + "\"}", Files.readString(tmp.resolve("hubs.json")));

    var service = new Service(new ServiceOptions().address(4100));
    assertEquals(HUB_KEY, service.hubKey());
    assertEquals("1", service.authHeaders().get("v"));

    // Another address has no pin.
    assertNull(new Service(new ServiceOptions().address("ws://other:1997")).hubKey());

    service.forgetHubKey();
    assertNull(service.hubKey());
    assertEquals("0", service.authHeaders().get("v"));
    assertEquals("{}", Files.readString(tmp.resolve("hubs.json")));
  }

  @Test
  void addressesNormalizeLikeChannelTs() {
    assertEquals("ws://localhost:1997", Service.normalizeAddress(1997));
    assertEquals("ws://localhost:1997", Service.normalizeAddress("1997"));
    assertEquals("wss://hub.example.com/path", Service.normalizeAddress("WSS://Hub.Example.COM/path/"));
    assertEquals("ws://127.0.0.1:1997", Service.normalizeAddress("ws://127.0.0.1:1997/"));
  }

  @Test
  void customSignStaysV0() {
    var service = new Service(new ServiceOptions().hubKey(HUB_KEY).sign(() -> "key.custom"));
    var headers = service.authHeaders();
    assertEquals("key.custom", headers.get("auth"));
    assertEquals("0", headers.get("v"));
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
