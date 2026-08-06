package v57.hubservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeychainTest {
  /** TS-generated test key from the hub-pro auth contract (owner key for live verification). */
  private static final String TS_TEST_AUTH =
      "MC4CAQAwBQYDK2VwBCIEINguo0cc0FACJ0oRV3V/+ZqIyj04ON5wdMJGH4a/do8CMCowBQYDK2VwAyEAHZSDpmbLRjLrOyNMugsStFqK5EYcNluUekE4di+FalQ=";
  private static final String TS_TEST_PUB =
      "MCowBQYDK2VwAyEAHZSDpmbLRjLrOyNMugsStFqK5EYcNluUekE4di+FalQ=";

  private static final Pattern TOKEN = Pattern.compile(
      "^key\\.[A-Za-z0-9+/]+={0,2}\\.[A-Za-z0-9+/]+={0,2}\\.\\d+$");

  @TempDir
  Path tmp;

  @Test
  void signReturnsKeyTokenShape() {
    Keychain.setKeyPath(tmp.resolve("shape").toString());
    var token = Keychain.sign();
    assertTrue(TOKEN.matcher(token).matches(), "unexpected token: " + token);
    assertEquals(4, token.split("\\.").length);
    // pub part is the 44-byte SPKI DER public key, base64 (60 chars).
    assertEquals(60, token.split("\\.")[1].length());
  }

  @Test
  void signatureVerifiesAgainstPublicKey() throws Exception {
    Keychain.setKeyPath(tmp.resolve("verify").toString());
    var parts = Keychain.sign().split("\\.");

    var publicKey = KeyFactory.getInstance("Ed25519")
        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(parts[1])));
    var verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(publicKey);
    verifier.update(parts[3].getBytes(StandardCharsets.UTF_8));
    assertTrue(verifier.verify(Base64.getDecoder().decode(parts[2])),
        "signature must verify against the token's public key");

    // expiry is the decimal epoch-seconds string of now + 10 (TS parity).
    var expiry = Long.parseLong(parts[3]);
    var now = System.currentTimeMillis() / 1000;
    assertTrue(expiry >= now + 9 && expiry <= now + 11, "unexpected expiry: " + expiry);
  }

  @Test
  void authFileRoundTrip() throws Exception {
    var path = tmp.resolve("roundtrip");
    Keychain.setKeyPath(path.toString());
    var pub1 = Keychain.publicKey(); // generates + writes the file

    var text = Files.readString(path).trim();
    assertEquals(124, text.length(), "auth file must be 124 base64 chars (92 bytes)");
    var data = Base64.getDecoder().decode(text);
    assertEquals(Keychain.PRIVATE_DER_LENGTH + Keychain.PUBLIC_DER_LENGTH, data.length);

    // Reload from the file: same public key, tokens still verify.
    Keychain.setKeyPath(path.toString());
    assertEquals(pub1, Keychain.publicKey());
    var token = Keychain.sign();
    assertTrue(TOKEN.matcher(token).matches(), "unexpected token: " + token);
    assertEquals(pub1, token.split("\\.")[1]);
  }

  @Test
  void loadsTsGeneratedKeyAndReproducesPublicKey() throws Exception {
    var path = tmp.resolve("ts-auth");
    Files.writeString(path, TS_TEST_AUTH);
    Keychain.setKeyPath(path.toString());

    assertEquals(TS_TEST_PUB, Keychain.publicKey());

    var parts = Keychain.sign().split("\\.");
    assertEquals(TS_TEST_PUB, parts[1]);

    // The TS key's signature must verify against the TS public key.
    var publicKey = KeyFactory.getInstance("Ed25519")
        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(parts[1])));
    var verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(publicKey);
    verifier.update(parts[3].getBytes(StandardCharsets.UTF_8));
    assertTrue(verifier.verify(Base64.getDecoder().decode(parts[2])));
  }

  @Test
  void badAuthFileThrows() throws Exception {
    var wrongLength = tmp.resolve("short");
    Files.writeString(wrongLength, Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
    Keychain.setKeyPath(wrongLength.toString());
    assertThrows(IllegalArgumentException.class, Keychain::publicKey);

    var notBase64 = tmp.resolve("garbage");
    Files.writeString(notBase64, "not base64 at all!!");
    Keychain.setKeyPath(notBase64.toString());
    assertThrows(IllegalArgumentException.class, Keychain::sign);
  }
}
