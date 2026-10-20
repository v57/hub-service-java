package v57.hubservice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * An ed25519 key in the hub key file format (protocol.md section 1): base64
 * of the 48-byte PKCS8 DER private key followed by the 44-byte SPKI DER
 * public key (92 bytes). Service keys ({@code auth}) and the device key
 * ({@code ~/.hub/device.key}) both use it.
 */
public final class KeyFile {
  /** PKCS8 DER private key length for ed25519 (48 bytes). */
  public static final int PRIVATE_DER_LENGTH = 48;
  /** SPKI DER public key length for ed25519 (44 bytes). */
  public static final int PUBLIC_DER_LENGTH = 44;

  private final byte[] data;
  private final PrivateKey privateKey;

  private KeyFile(byte[] data) {
    if (data.length != PRIVATE_DER_LENGTH + PUBLIC_DER_LENGTH) {
      throw new IllegalArgumentException(
          "expected " + (PRIVATE_DER_LENGTH + PUBLIC_DER_LENGTH) + " bytes, got " + data.length);
    }
    this.data = data.clone();
    try {
      this.privateKey = KeyFactory.getInstance("Ed25519")
          .generatePrivate(new PKCS8EncodedKeySpec(slice(data, 0, PRIVATE_DER_LENGTH)));
    } catch (Exception error) {
      throw new IllegalArgumentException("bad ed25519 private key: " + error.getMessage(), error);
    }
  }

  /** Parses the key file text (base64, surrounding whitespace ignored). */
  public static KeyFile parse(String text) {
    return new KeyFile(Base64.getDecoder().decode(text.trim()));
  }

  /** A fresh random key. */
  public static KeyFile generate() {
    try {
      var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      return new KeyFile(concat(pair.getPrivate().getEncoded(), pair.getPublic().getEncoded()));
    } catch (Exception error) {
      throw new IllegalStateException("failed to generate ed25519 key", error);
    }
  }

  /** Reads the key file at {@code path}. */
  public static KeyFile load(Path path) {
    try {
      return parse(Files.readString(path));
    } catch (Exception error) {
      throw new IllegalArgumentException("bad key file " + path + ": " + error.getMessage(), error);
    }
  }

  /**
   * Reads the key file at {@code path}, creating it if absent. With
   * {@code secure}, the parent directory is created with mode 0700, the file
   * is written to a temp file with mode 0600 and renamed into place (device
   * key rules, protocol.md section 3).
   */
  public static KeyFile loadOrCreate(Path path, boolean secure) {
    if (Files.exists(path)) {
      return load(path);
    }
    var key = generate();
    try {
      if (secure) {
        writeAtomic(path, key.text(), true);
      } else {
        Files.writeString(path, key.text());
      }
    } catch (Exception error) {
      throw new IllegalStateException("failed to write key file " + path, error);
    }
    return key;
  }

  /** The key file text: base64 of the 92 bytes. */
  public String text() {
    return Base64.getEncoder().encodeToString(data);
  }

  /** The public key: base64 of the 44-byte SPKI DER. */
  public String publicKey() {
    return Base64.getEncoder().encodeToString(publicDer());
  }

  /** The 44-byte SPKI DER public key. */
  public byte[] publicDer() {
    return slice(data, PRIVATE_DER_LENGTH, PUBLIC_DER_LENGTH);
  }

  /** Base64 ed25519 signature over the UTF-8 bytes of {@code text}. */
  public String sign(String text) {
    try {
      var signature = Signature.getInstance("Ed25519");
      signature.initSign(privateKey);
      signature.update(text.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(signature.sign());
    } catch (Exception error) {
      throw new IllegalStateException("failed to sign with ed25519", error);
    }
  }

  /** Writes {@code text} to a temp file beside {@code path} and renames it into place. */
  static void writeAtomic(Path path, String text, boolean secure) throws Exception {
    var parent = path.toAbsolutePath().getParent();
    if (parent != null && !Files.exists(parent)) {
      Files.createDirectories(parent);
      if (secure) {
        setPermissions(parent, "rwx------");
      }
    }
    var temp = Files.createTempFile(parent, "." + path.getFileName(), ".tmp");
    try {
      if (secure) {
        setPermissions(temp, "rw-------");
      }
      Files.writeString(temp, text);
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static void setPermissions(Path path, String permissions) {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    } catch (UnsupportedOperationException | java.io.IOException ignored) {
      // Not a POSIX file system (Windows): rely on the default ACLs.
    }
  }

  private static byte[] slice(byte[] data, int offset, int length) {
    var out = new byte[length];
    System.arraycopy(data, offset, out, 0, length);
    return out;
  }

  private static byte[] concat(byte[] a, byte[] b) {
    var out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
