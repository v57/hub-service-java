package v57.hubservice;

import java.nio.file.Path;

/**
 * ed25519 keychain: load-or-generate the {@code auth} key file and sign
 * hub-pro auth tokens.
 *
 * <p>Hub-pro auth parity with the TS reference ({@code hub-service/keychain.ts})
 * and {@code Docs/Devices/protocol.md} section 2:
 *
 * <ul>
 *   <li>The key file ({@code auth} in the working directory, or
 *       {@code $HUBKEY}) is the base64 encoding of the 48-byte PKCS8 DER
 *       ed25519 private key followed by the 44-byte SPKI DER public key (92
 *       bytes total, 124 base64 chars). Generated if missing.
 *   <li>A signed token has the shape {@code key.<pub>.<sig>.<expiry>} where
 *       pub = base64 of the 44-byte SPKI public key, expiry = DECIMAL
 *       epoch-seconds string of now + 60, and sig = base64 ed25519 signature
 *       over {@code <expiry>} (header {@code v: 0}) or over
 *       {@code hub1:<hub SPKI>:<expiry>} (header {@code v: 1}, bound to one
 *       hub). hub-pro allows 30 seconds of clock skew and at most 300 seconds
 *       ahead.
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
  /** Default token lifetime in seconds (protocol.md: now + 60). */
  public static final int DEFAULT_EXPIRES = 60;

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

  /** A fresh v0 auth token {@code key.<pub>.<sig>.<expiry>} with the default 60s expiry. */
  public static String sign() {
    return sign(DEFAULT_EXPIRES);
  }

  /** A fresh v0 auth token {@code key.<pub>.<sig>.<expiry>} with {@code expires} seconds. */
  public static String sign(int expires) {
    return token(key(), null, now() + expires);
  }

  /** A fresh v1 auth token bound to {@code hubKey} (the hub's SPKI), default 60s expiry. */
  public static String signHub(String hubKey) {
    return signHub(hubKey, DEFAULT_EXPIRES);
  }

  /** A fresh v1 auth token bound to {@code hubKey} with {@code expires} seconds. */
  public static String signHub(String hubKey, int expires) {
    return token(key(), hubKey, now() + expires);
  }

  /**
   * Builds an auth token with {@code key} expiring at {@code expiry}: a v1
   * token over {@code hub1:<hubKey>:<expiry>} when {@code hubKey} is set,
   * else a v0 token over {@code <expiry>}.
   */
  public static String token(KeyFile key, String hubKey, long expiry) {
    var data = Long.toString(expiry);
    var signed = hubKey != null ? "hub1:" + hubKey + ":" + data : data;
    return "key." + key.publicKey() + "." + key.sign(signed) + "." + data;
  }

  static long now() {
    return System.currentTimeMillis() / 1000;
  }
}
