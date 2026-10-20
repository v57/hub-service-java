package v57.hubservice;

import java.util.function.Supplier;

/**
 * Options for {@link Service}.
 *
 * <p>{@code address} is a port (Integer) or a ws:// URL (String). Default:
 * {@code 1997} or the {@code HUB} environment variable. {@code name} is the
 * profile name shown in the hub. {@code icon} is a String or a
 * {@code {"symbol": .., "text": ..}} map. {@code sign} supplies the hub-pro
 * auth token for each connect (default: the keychain — the {@code auth} key
 * file in the cwd, or {@code $HUBKEY} — signing v1 tokens bound to the hub
 * key once it is pinned; a custom supplier always connects with {@code v: 0}).
 * {@code hubKey} supplies the hub's key up front (from an invitation or
 * config) instead of learning it from {@code hub/info}.
 */
public final class ServiceOptions {
  /** Port (Integer) or ws:// URL (String). Null = default ({@code $HUB} or 1997). */
  public Object address;
  /** Profile name shown in the hub. */
  public String name;
  /** Profile icon: a String or a {@code {"symbol": .., "text": ..}} map. */
  public Object icon;
  /** Hub-pro auth token supplier, called once per (re)connect. Null = the keychain (v1 once pinned). */
  public Supplier<String> sign;
  /** The hub's SPKI, if known up front. Null = learn and pin it via {@code hub/info}. */
  public String hubKey;

  public ServiceOptions() {
  }

  public ServiceOptions(Object address, String name, Object icon) {
    this.address = address;
    this.name = name;
    this.icon = icon;
  }

  public ServiceOptions address(Object address) {
    this.address = address;
    return this;
  }

  public ServiceOptions name(String name) {
    this.name = name;
    return this;
  }

  public ServiceOptions icon(Object icon) {
    this.icon = icon;
    return this;
  }

  public ServiceOptions sign(Supplier<String> sign) {
    this.sign = sign;
    return this;
  }

  public ServiceOptions hubKey(String hubKey) {
    this.hubKey = hubKey;
    return this;
  }
}
