package v57.hubservice;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How a connection to an address travels ({@code Docs/Devices/protocol.md}
 * section 2). {@link #REFUSED} is plain {@code ws://} beyond loopback, the
 * overlay and the LAN: it isn't dialed unless {@code allowPlaintext} says so.
 */
public enum HubTransport {
  ENCRYPTED("encrypted"),
  LOOPBACK("loopback"),
  OVERLAY("overlay"),
  LOCAL_NETWORK("localNetwork"),
  REFUSED("refused");

  private static final Pattern ADDRESS =
      Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*)://(?:[^/?#@]*@)?(\\[[^\\]]*\\]|[^/?#:]*)");
  private static final Pattern BYTE = Pattern.compile("\\d{1,3}");

  private final String label;

  HubTransport(String label) {
    this.label = label;
  }

  /** The name the protocol's vectors use. */
  public String label() {
    return label;
  }

  /** Classifies an address (a port or a URL) before anything is dialed. */
  public static HubTransport of(Object address) {
    if (address instanceof Integer) {
      return LOOPBACK;
    }
    var text = String.valueOf(address).trim();
    if (text.matches("\\d+")) {
      return LOOPBACK;
    }
    var match = ADDRESS.matcher(text);
    if (!match.find()) {
      return REFUSED;
    }
    var scheme = match.group(1).toLowerCase(Locale.ROOT);
    var host = match.group(2).toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    if (scheme.equals("wss") || scheme.equals("https") || scheme.equals("quic")) {
      return ENCRYPTED;
    }
    if (!scheme.equals("ws") && !scheme.equals("http")) {
      return REFUSED;
    }
    if (host.startsWith("[")) {
      var v6 = host.substring(1, host.length() - 1);
      if (v6.equals("::1")) {
        return LOOPBACK;
      }
      return v6.matches("^(f[cd]|fe[89ab]).*") ? LOCAL_NETWORK : REFUSED;
    }
    var v4 = ipv4(host);
    if (v4 != null) {
      int a = v4[0];
      int b = v4[1];
      if (a == 127) {
        return LOOPBACK;
      }
      if (a == 100 && b >= 64 && b <= 127) {
        return OVERLAY;
      }
      if (a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254)) {
        return LOCAL_NETWORK;
      }
      return REFUSED;
    }
    if (host.equals("localhost") || host.endsWith(".localhost")) {
      return LOOPBACK;
    }
    if (host.endsWith(".hub.invalid")) {
      return OVERLAY;
    }
    return host.endsWith(".local") ? LOCAL_NETWORK : REFUSED;
  }

  private static int[] ipv4(String host) {
    var parts = host.split("\\.", -1);
    if (parts.length != 4) {
      return null;
    }
    var bytes = new int[4];
    for (var i = 0; i < 4; i += 1) {
      if (!BYTE.matcher(parts[i]).matches()) {
        return null;
      }
      bytes[i] = Integer.parseInt(parts[i]);
      if (bytes[i] > 255) {
        return null;
      }
    }
    return bytes;
  }
}
