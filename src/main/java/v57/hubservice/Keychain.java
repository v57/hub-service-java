package v57.hubservice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * ed25519 keychain: load-or-generate the {@code auth} key file and sign
 * hub-pro auth tokens.
 *
 * <p>Hub-pro auth parity with the TS reference ({@code hub-service/keychain.ts}):
 *
 * <ul>
 *   <li>The key file ({@code auth} in the working directory, matching the TS
 *       keychain) is the base64 encoding of the 48-byte PKCS8 DER ed25519
 *       private key followed by the 44-byte SPKI DER public key (92 bytes
 *       total, 124 base64 chars). Generated if missing.
 *   <li>A signed token has the shape {@code key.<pub>.<sig>.<expiry>} where:
 *       pub = base64 of the 44-byte SPKI public key (bytes 48..92 of the
 *       file), sig = base64 ed25519 signature over the expiry string, expiry
 *       = DECIMAL epoch-seconds string of now + 10 (TS parity; hub-pro
 *       rejects it once expired or if over 5 minutes ahead).
 * </ul>
 *
 * <p>The hub verifies the signature against the embedded public key and
 * treats the key as the connection identity (owner/host/group permissions).
 *
 * <p>Crypto backend: {@code java.security} Ed25519 (JDK 15+, RFC 8410 DER is
 * exactly the PKCS8/SPKI layout the TS/Python/Ruby ports write).
 */
public final class Keychain {
  /** PKCS8 DER private key length for ed25519 (48 bytes). */
  public static final int PRIVATE_DER_LENGTH = 48;
  /** SPKI DER public key length for ed25519 (44 bytes). */
  public static final int PUBLIC_DER_LENGTH = 44;

  private static final Object LOCK = new Object();
  private static String keyPath;
  private static byte[] keyData;

  private Keychain() {
  }

  /**
   * Overrides the key file location (default: {@code $HUBKEY} or {@code auth}
   * in the cwd). Resets the cached key material, so the next call reloads
   * from the new file.
   */
  public static void setKeyPath(String path) {
    synchronized (LOCK) {
      keyPath = path;
      keyData = null;
    }
  }

  /** The service's public key: base64 of the 44-byte SPKI DER (bytes 48..92). */
  public static String publicKey() {
    var data = loadOrGenerate();
    return Base64.getEncoder().encodeToString(publicDer(data));
  }

  /** A fresh auth token {@code key.<pub>.<sig>.<expiry>} with the default 10s expiry. */
  public static String sign() {
    return sign(10);
  }

  /** A fresh auth token {@code key.<pub>.<sig>.<expiry>} with {@code expires} seconds. */
  public static String sign(int expires) {
    var data = loadOrGenerate();
    var expiry = Long.toString(System.currentTimeMillis() / 1000 + expires);
    var signature = makeSignature(expiry, privateDer(data));
    return "key." + Base64.getEncoder().encodeToString(publicDer(data)) + "."
        + Base64.getEncoder().encodeToString(signature) + "." + expiry;
  }

  private static byte[] loadOrGenerate() {
    synchronized (LOCK) {
      if (keyData != null) {
        return keyData;
      }
      var path = Path.of(keyPath != null ? keyPath : defaultKeyPath());
      if (Files.exists(path)) {
        keyData = load(path);
      } else {
        keyData = generate(path);
      }
      return keyData;
    }
  }

  private static String defaultKeyPath() {
    var env = System.getenv("HUBKEY");
    return env != null && !env.isEmpty() ? env : "auth";
  }

  private static byte[] load(Path path) {
    byte[] data;
    try {
      data = Base64.getDecoder().decode(Files.readString(path).trim());
    } catch (Exception error) {
      throw new IllegalArgumentException("bad auth file " + path + ": " + error.getMessage(), error);
    }
    checkLength(path, data);
    return data;
  }

  private static byte[] generate(Path path) {
    try {
      var generator = KeyPairGenerator.getInstance("Ed25519");
      var pair = generator.generateKeyPair();
      var data = concat(pair.getPrivate().getEncoded(), pair.getPublic().getEncoded());
      Files.writeString(path, Base64.getEncoder().encodeToString(data));
      return data;
    } catch (Exception error) {
      throw new IllegalStateException("failed to generate ed25519 key for " + path, error);
    }
  }

  private static void checkLength(Path path, byte[] data) {
    var expected = PRIVATE_DER_LENGTH + PUBLIC_DER_LENGTH;
    if (data.length != expected) {
      throw new IllegalArgumentException(
          "bad auth file " + path + ": expected " + expected + " bytes, got " + data.length);
    }
  }

  private static byte[] privateDer(byte[] data) {
    var der = new byte[PRIVATE_DER_LENGTH];
    System.arraycopy(data, 0, der, 0, PRIVATE_DER_LENGTH);
    return der;
  }

  private static byte[] publicDer(byte[] data) {
    var der = new byte[PUBLIC_DER_LENGTH];
    System.arraycopy(data, PRIVATE_DER_LENGTH, der, 0, PUBLIC_DER_LENGTH);
    return der;
  }

  private static byte[] makeSignature(String expiry, byte[] privateDer) {
    try {
      var privateKey = KeyFactory.getInstance("Ed25519")
          .generatePrivate(new PKCS8EncodedKeySpec(privateDer));
      var signature = Signature.getInstance("Ed25519");
      signature.initSign(privateKey);
      signature.update(expiry.getBytes(StandardCharsets.UTF_8));
      return signature.sign();
    } catch (Exception error) {
      throw new IllegalStateException("failed to sign ed25519 token", error);
    }
  }

  private static byte[] concat(byte[] a, byte[] b) {
    var out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
