package v57.hubservice;

import v57.channel.JsonCodec;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Device identity ({@code Docs/Devices/protocol.md} sections 1 and 3).
 *
 * <p>The device key is one ed25519 key per OS instance, in the key file
 * format, at {@code $HUB_DEVICE_KEY}, {@code /var/lib/hub/device.key} for
 * root, or {@code ~/.hub/device.key} (directory 0700, file 0600, created
 * atomically). It signs a short certificate for each service key it runs,
 * sent in the {@code device} connection header next to {@code auth}.
 */
public final class Device {
  /** Default certificate lifetime: 7 days. */
  public static final long CERTIFICATE_LIFETIME = 7 * 24 * 3600;
  /** A certificate with less than this left is renewed at connect: 24 hours. */
  public static final long RENEW_BEFORE = 24 * 3600;

  private Device() {
  }

  /** The device key location: {@code $HUB_DEVICE_KEY}, root's system path, or {@code ~/.hub/device.key}. */
  public static Path defaultKeyPath() {
    var env = System.getenv("HUB_DEVICE_KEY");
    if (env != null && !env.isEmpty()) {
      return Path.of(env);
    }
    if ("root".equals(System.getProperty("user.name")) && !isWindows()) {
      return Path.of("/var/lib/hub/device.key");
    }
    return Path.of(System.getProperty("user.home"), ".hub", "device.key");
  }

  /** Loads the device key at {@code path}, creating it (0600, atomic) if absent. */
  public static KeyFile loadOrCreateKey(Path path) {
    return KeyFile.loadOrCreate(path, true);
  }

  /** The device id: the first 32 lowercase hex characters of SHA-256 of the SPKI DER. */
  public static String id(String publicKey) {
    return HexFormat.of().formatHex(digest(publicKey)).substring(0, 32);
  }

  /** The safety code shown while approving: {@code "ddd ddd"}. */
  public static String safetyCode(String publicKey) {
    var hex = HexFormat.of().formatHex(digest(publicKey)).substring(0, 8);
    var code = String.format("%06d", Long.parseLong(hex, 16) % 1_000_000);
    return code.substring(0, 3) + " " + code.substring(3);
  }

  /** The suggested device name: the computer name on macOS, else the host name without {@code .local}. */
  public static String defaultName() {
    String name = null;
    if ("macos".equals(defaultPlatform())) {
      try {
        var process = new ProcessBuilder("scutil", "--get", "ComputerName").redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() == 0 && !output.isEmpty()) {
          return output;
        }
      } catch (Exception ignored) {
      }
    }
    try {
      name = InetAddress.getLocalHost().getHostName();
    } catch (Exception ignored) {
    }
    if (name == null || name.isEmpty()) {
      name = System.getenv("HOSTNAME");
    }
    if (name == null || name.isEmpty()) {
      return null;
    }
    return name.endsWith(".local") ? name.substring(0, name.length() - ".local".length()) : name;
  }

  /** The certificate {@code platform} for this OS. */
  public static String defaultPlatform() {
    var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("mac")) {
      return "macos";
    }
    if (os.contains("linux")) {
      return System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT).contains("android") ? "android" : "linux";
    }
    if (os.contains("windows")) {
      return "windows";
    }
    return "other";
  }

  /**
   * The certificate payload, fields in protocol order with optional ones
   * omitted when null: {@code v, device, service, instance, deviceName,
   * platform, parent, iat, exp}.
   */
  public static Map<String, Object> certificatePayload(
      String devicePublicKey, String servicePublicKey, String instance,
      String deviceName, String platform, String parent, long iat, long exp) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("v", 1);
    payload.put("device", devicePublicKey);
    payload.put("service", servicePublicKey);
    payload.put("instance", instance);
    if (deviceName != null) {
      payload.put("deviceName", deviceName);
    }
    if (platform != null) {
      payload.put("platform", platform);
    }
    if (parent != null) {
      payload.put("parent", parent);
    }
    payload.put("iat", iat);
    payload.put("exp", exp);
    return payload;
  }

  /**
   * Signs a certificate: {@code <base64url(JSON)>.<base64 signature>}, the
   * signature covering the base64url payload string.
   */
  public static String certificate(KeyFile deviceKey, Map<String, Object> payload) {
    var json = JsonCodec.encode(payload);
    var encoded = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    return encoded + "." + deviceKey.sign(encoded);
  }

  /** Decodes the payload of a {@code device} header (no signature check). */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> decodeCertificate(String header) {
    var dot = header.indexOf('.');
    var encoded = dot < 0 ? header : header.substring(0, dot);
    var json = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    return (Map<String, Object>) JsonCodec.decode(json);
  }

  private static byte[] digest(String publicKey) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(publicKey));
    } catch (Exception error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
  }
}
