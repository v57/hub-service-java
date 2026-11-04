package v57.hubservice;

import v57.channel.Channel;
import v57.channel.ChannelClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Auth v2's client side ({@code Docs/Devices/protocol.md} section 2): before
 * every connect the SDK asks {@code hub/info} on an anonymous connection with a
 * fresh nonce, and sends its token only if the hub proved the key it claims,
 * over that nonce.
 */
public final class HubProof {
  private static final SecureRandom RANDOM = new SecureRandom();

  private HubProof() {
  }

  /** An answer to {@code hub/info} with a nonce. */
  public record Info(String key, String proof, String challenge) {
    /**
     * Whether the answer proves its key over {@code nonce}. A relayed answer
     * passes too, so it never decides which credentials a hub gets.
     */
    public boolean proves(String nonce) {
      if (key == null || proof == null || challenge == null) {
        return false;
      }
      try {
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key))));
        verifier.update(signedData(key, nonce).getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(proof));
      } catch (Exception error) {
        return false;
      }
    }

    static Info of(Object value) {
      if (!(value instanceof Map<?, ?> map)) {
        return new Info(null, null, null);
      }
      return new Info(text(map.get("key")), text(map.get("proof")), text(map.get("challenge")));
    }

    private static String text(Object value) {
      return value instanceof String string ? string : null;
    }
  }

  /** The nonce for one {@code hub/info}: 32 fresh random bytes in base64url. */
  public static String makeClientNonce() {
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The string a {@code hub/info} proof signs. */
  public static String signedData(String hubKey, String nonce) {
    return "hubinfo1:" + hubKey + ":" + nonce;
  }

  /**
   * Asks the hub at {@code address} for its key and a challenge, on a short
   * anonymous connection, and returns the answer only with a valid proof. A
   * client whose hub never answers would wait forever, so the question runs on
   * its own thread and closing the connection ends it.
   */
  static Info ask(String address, long timeoutMs) throws InterruptedException {
    ChannelClient<Object> client = new Channel<Object>().connect(address);
    try {
      var nonce = makeClientNonce();
      var answer = new CompletableFuture<Info>();
      var thread = new Thread(() -> {
        try {
          var result = client.sender().send("hub/info", Map.of("nonce", nonce));
          if (!result.isOk()) {
            answer.completeExceptionally(new IllegalStateException(String.valueOf(result.remoteErr())));
            return;
          }
          answer.complete(Info.of(result.value()));
        } catch (Throwable error) {
          answer.completeExceptionally(error);
        }
      }, "hub-service-info");
      thread.setDaemon(true);
      thread.start();
      var info = answer.get(timeoutMs, TimeUnit.MILLISECONDS);
      if (!info.proves(nonce)) {
        throw new IllegalStateException("the hub didn't prove its key");
      }
      return info;
    } catch (TimeoutException error) {
      throw new IllegalStateException("hub/info timed out");
    } catch (ExecutionException error) {
      throw new IllegalStateException(error.getCause().getMessage(), error.getCause());
    } finally {
      client.stop();
    }
  }
}
