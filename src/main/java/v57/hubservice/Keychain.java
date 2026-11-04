package v57.hubservice;

import java.nio.file.Path;

/**
 * ed25519 keychain: load-or-generate the {@code auth} key file and sign
 * hub-server auth tokens.
 *
 * <p>hub-server auth parity with the TS reference ({@code hub-service/keychain.ts})
 * and {@code Docs/Devices/protocol.md} section 2:
 *
 * <ul>
 *   <li>The key file ({@code auth} in the working directory, or
 *       {@code $HUBKEY}) is the base64 encoding of the 48-byte PKCS8 DER
 *       ed25519 private key followed by the 44-byte SPKI DER public key (92
 *       bytes total, 124 base64 chars). Generated if missing.
 *   <li>A signed token has the shape {@code key.<pub>.<sig>.<expiry>.<nonce>}
 *       where pub = base64 of the 44-byte SPKI public key, expiry = DECIMAL
 *       epoch-seconds string of now + 30, nonce = the challenge the hub answered
 *       in {@code hub/info}, and sig = base64 ed25519 signature over
 *       {@code hub1:<hub SPKI>:<nonce>:<expiry>}, for one connection to one hub.
 *       hub-server allows 30 seconds of clock skew and at most 60 seconds ahead.
 * </ul>
 *
 * <p>The hub verifies the signature against the embedded public key and
 * treats the key as the connection identity (owner/host/group permissions).
 */
public final class Keychain {
  /** PKCS8 DER private key length for ed25519 (48 bytes). */
  public static final int PRIVATE_DER_LENGTH = KeyFile.PRIVATE_DER_LENGTH;
  /** SPKI DER public key length for ed25519 (44 bytes). */
  public static final int PUBLIC_DER_LENGTH = KeyFile.PUBLIC_DER_LENGTH;
  /** The {@code v} header next to {@code auth}. */
  public static final String AUTH_VERSION = "1";
  /** Default token lifetime in seconds (protocol.md: now + 30). */
  public static final int DEFAULT_EXPIRES = 30;

  private static final Object LOCK = new Object();
  private static String keyPath;
  private static KeyFile key;

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
      key = null;
    }
  }

  /** The key file location in use. Hub key pins ({@code hubs.json}) live beside it. */
  public static Path keyPath() {
    synchronized (LOCK) {
      if (keyPath != null) {
        return Path.of(keyPath);
      }
    }
    var env = System.getenv("HUBKEY");
    return Path.of(env != null && !env.isEmpty() ? env : "auth");
  }

  /** The service key, loaded or generated on first use. */
  public static KeyFile key() {
    synchronized (LOCK) {
      if (key == null) {
        key = KeyFile.loadOrCreate(keyPath(), false);
      }
      return key;
    }
  }

  /** The service's public key: base64 of the 44-byte SPKI DER (bytes 48..92). */
  public static String publicKey() {
    return key().publicKey();
  }

  /** A fresh auth token for one connection to the hub {@code hubKey}, over its {@code nonce}. */
  public static String signHub(String hubKey, String nonce) {
    return signHub(hubKey, nonce, DEFAULT_EXPIRES);
  }

  /** A fresh auth token for one connection to the hub {@code hubKey}, with {@code expires} seconds. */
  public static String signHub(String hubKey, String nonce, int expires) {
    return token(key(), hubKey, nonce, now() + expires);
  }

  /** Builds an auth token with {@code key} over {@code hub1:<hubKey>:<nonce>:<expiry>}. */
  public static String token(KeyFile key, String hubKey, String nonce, long expiry) {
    var data = Long.toString(expiry);
    return "key." + key.publicKey() + "." + key.sign("hub1:" + hubKey + ":" + nonce + ":" + data) + "." + data + "." + nonce;
  }

  static long now() {
    return System.currentTimeMillis() / 1000;
  }
}
