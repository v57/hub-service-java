package v57.hubservice;

import org.junit.jupiter.api.Test;
import v57.channel.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The device layer test vectors ({@code Docs/Devices/vectors.json}, copied to
 * {@code src/test/resources/device-vectors.json}). Ed25519 is deterministic,
 * so the exact strings must match.
 */
class VectorsTest {
  @SuppressWarnings("unchecked")
  private static final Map<String, Object> VECTORS = load();

  @SuppressWarnings("unchecked")
  private static Map<String, Object> load() {
    try (var stream = VectorsTest.class.getResourceAsStream("/device-vectors.json")) {
      return (Map<String, Object>) JsonCodec.decode(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> at(String... path) {
    Object value = VECTORS;
    for (var key : path) {
      value = ((Map<String, Object>) value).get(key);
    }
    return (Map<String, Object>) value;
  }

  static KeyFile key(String name) {
    return KeyFile.parse((String) at("keys", name).get("keyFile"));
  }

  private static long number(Map<String, Object> map, String key) {
    return ((Number) map.get(key)).longValue();
  }

  @Test
  void keyFilesGiveThePublicKeys() {
    for (var name : List.of("hub", "service", "device")) {
      assertEquals(at("keys", name).get("publicKey"), key(name).publicKey(), name);
    }
  }

  @Test
  void deviceIdAndSafetyCode() {
    var device = at("keys", "device");
    assertEquals(device.get("deviceId"), Device.id((String) device.get("publicKey")));
    @SuppressWarnings("unchecked")
    var cases = (java.util.List<java.util.Map<String, Object>>) at("safetyCode").get("cases");
    for (var code : cases) {
      assertEquals(code.get("code"), Device.safetyCode((String) code.get("hubKey"), (String) code.get("deviceKey"), (String) code.get("codeValue")));
    }
  }

  @Test
  void authTokenMatches() {
    var auth = at("auth");
    var expiry = number(auth, "expiry");
    var nonce = (String) auth.get("nonce");
    var service = key("service");
    var hubKey = (String) at("keys", "hub").get("publicKey");

    var v1 = at("auth", "v1");
    assertEquals("hub1:" + hubKey + ":" + nonce + ":" + expiry, v1.get("signedData"));
    assertEquals(v1.get("signature"), service.sign((String) v1.get("signedData")));
    assertEquals(v1.get("header"), Keychain.token(service, hubKey, nonce, expiry));
  }

  @Test
  void hubInfoProof() {
    var vector = at("hubInfo");
    var hubKey = (String) at("keys", "hub").get("publicKey");
    var nonce = (String) vector.get("nonce");
    var proof = (String) vector.get("proof");
    assertEquals(vector.get("signedData"), HubProof.signedData(hubKey, nonce));
    assertTrue(new HubProof.Info(hubKey, proof, "c").proves(nonce));
    assertFalse(new HubProof.Info(hubKey, proof, "c").proves("another-nonce-another-nonce"));
    assertFalse(new HubProof.Info((String) at("keys", "service").get("publicKey"), proof, "c").proves(nonce));
    assertFalse(new HubProof.Info(hubKey, null, "c").proves(nonce));
    assertFalse(new HubProof.Info(hubKey, proof, null).proves(nonce));
    assertFalse(new HubProof.Info(hubKey, "not base64!", "c").proves(nonce));
    assertFalse(new HubProof.Info("not a key", proof, "c").proves(nonce));
  }

  @Test
  @SuppressWarnings("unchecked")
  void transports() {
    var cases = (List<Map<String, Object>>) VECTORS.get("transport");
    assertFalse(cases.isEmpty());
    for (var entry : cases) {
      assertEquals(entry.get("transport"), HubTransport.of(entry.get("url")).label(), (String) entry.get("url"));
    }
    assertEquals(HubTransport.LOOPBACK, HubTransport.of(1997));
    assertEquals(HubTransport.LOOPBACK, HubTransport.of("1997"));
  }

  @Test
  void certificate() {
    checkCertificate(at("certificate"), "s3-local/Backup Disk", "Mac Studio", "macos", null);
  }

  @Test
  void childCertificateWithParent() {
    checkCertificate(at("childCertificate"), "builder", "builder", "linux", "Mac Studio");
  }

  private static void checkCertificate(
      Map<String, Object> vector, String instance, String deviceName, String platform, String parent) {
    var device = key("device");
    var payload = Device.certificatePayload(
        device.publicKey(), key("service").publicKey(), instance, deviceName, platform, parent,
        1792500000L, 1793104800L);
    assertEquals(vector.get("json"), JsonCodec.encode(payload));
    var header = Device.certificate(device, payload);
    assertEquals(vector.get("header"), header);
    assertEquals(vector.get("payload") + "." + vector.get("signature"), header);
    assertEquals(device.sign((String) vector.get("payload")), vector.get("signature"));
    assertEquals(JsonCodec.decode((String) vector.get("json")), Device.decodeCertificate(header));
  }

  @Test
  void callerContext() {
    var context = at("caller", "context");
    var caller = Caller.fromContext(context);
    var device = at("keys", "device");
    assertEquals(at("keys", "service").get("publicKey"), caller.key());
    assertEquals(device.get("deviceId"), caller.deviceId());
    assertEquals("Mac Studio", caller.deviceName());
    assertEquals(List.of("owners"), caller.groups());
    assertTrue(caller.owner());
    assertEquals(at("keys", "hub").get("publicKey"), Caller.hubKey(context));
  }
}
