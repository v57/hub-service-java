package v57.hubservice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceTest {
  @TempDir
  Path tmp;

  @AfterEach
  void resetKeyPath() {
    Keychain.setKeyPath(null);
  }

  private Service service(ServiceOptions options) {
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    return new Service(options.address(4100).deviceKeyPath(tmp.resolve("dev/device.key").toString()));
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
  void createsDeviceKeyWithPrivateModes() throws Exception {
    var path = tmp.resolve("hub/device.key");
    var key = Device.loadOrCreateKey(path);
    assertEquals(124, Files.readString(path).length());
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path.getParent())));
    assertEquals(key.publicKey(), Device.loadOrCreateKey(path).publicKey());
    try (var files = Files.list(path.getParent())) {
      assertEquals(1, files.count(), "no temp file left behind");
    }
  }

  @Test
  void connectHeadersCarryASignedCertificate() throws Exception {
    var service = service(new ServiceOptions().name("Echo").deviceName("Mac Studio").platform("macos"));
    var headers = service.authHeaders();
    var header = headers.get("device");
    var parts = header.split("\\.");
    assertEquals(2, parts.length);

    var payload = Device.decodeCertificate(header);
    var deviceKey = service.deviceKey().publicKey();
    assertTrue(verifies(deviceKey, parts[0], parts[1]), "signature covers the payload string");
    assertEquals(1, ((Number) payload.get("v")).intValue());
    assertEquals(deviceKey, payload.get("device"));
    assertEquals(Keychain.publicKey(), payload.get("service"));
    assertEquals(headers.get("auth").split("\\.")[1], payload.get("service"));
    assertEquals("Echo", payload.get("instance"));
    assertEquals("Mac Studio", payload.get("deviceName"));
    assertEquals("macos", payload.get("platform"));
    assertFalse(payload.containsKey("parent"));
    var iat = ((Number) payload.get("iat")).longValue();
    var exp = ((Number) payload.get("exp")).longValue();
    assertEquals(Device.CERTIFICATE_LIFETIME, exp - iat);
    assertTrue(Math.abs(iat - Keychain.now()) <= 2);
  }

  @Test
  void certificateIsReusedUntilRenewal() {
    var service = service(new ServiceOptions().deviceName("box"));
    var first = service.authHeaders().get("device");
    assertSame(first, service.authHeaders().get("device"));
  }

  @Test
  void instanceDefaults() {
    var named = service(new ServiceOptions().name("Backup").instance("s3-local/Backup Disk"));
    assertEquals("s3-local/Backup Disk", named.instance());
    assertEquals("Backup", service(new ServiceOptions().name("Backup")).instance());
    var byPath = service(new ServiceOptions());
    assertEquals("service", byPath.instance());
    byPath.post("s3/read", body -> body);
    byPath.post("other/x", body -> body);
    assertEquals("s3", byPath.instance());
  }

  @Test
  void parentIsDeclared() {
    var service = service(new ServiceOptions().deviceName("builder").platform("linux").parent("Mac Studio"));
    assertEquals("Mac Studio", Device.decodeCertificate(service.authHeaders().get("device")).get("parent"));
  }

  @Test
  void deviceModeCanBeTurnedOff() {
    var service = service(new ServiceOptions().device(false));
    assertNull(service.authHeaders().get("device"));
    assertFalse(Files.exists(tmp.resolve("dev/device.key")));
  }

  @Test
  void callerProvidedKeyIsUsed() {
    var key = KeyFile.generate();
    var service = service(new ServiceOptions().deviceKey(key).deviceName("app"));
    assertEquals(key.publicKey(), Device.decodeCertificate(service.authHeaders().get("device")).get("device"));
    assertFalse(Files.exists(tmp.resolve("dev/device.key")));
  }

  @Test
  void unreadableDeviceKeyTurnsDeviceModeOff() throws Exception {
    var path = tmp.resolve("bad.key");
    Files.writeString(path, "garbage");
    Keychain.setKeyPath(tmp.resolve("auth").toString());
    var service = new Service(new ServiceOptions().deviceKeyPath(path.toString()));
    var headers = service.authHeaders();
    assertNull(headers.get("device"));
    assertNotEquals(null, headers.get("auth"));
  }
}
