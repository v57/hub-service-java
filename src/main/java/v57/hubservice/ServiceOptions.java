package v57.hubservice;

import java.util.function.Supplier;

/**
 * Options for {@link Service}.
 *
 * <p>{@code address} is a port (Integer) or a ws:// URL (String). Default:
 * {@code 1997} or the {@code HUB} environment variable. {@code name} is the
 * profile name shown in the hub. {@code icon} is a String or a
 * {@code {"symbol": .., "text": ..}} map. {@code sign} supplies the hub-pro
 * auth token for each connect (default: {@link Keychain#sign()} — the
 * {@code auth} key file in the cwd, or {@code $HUBKEY}).
 */
public final class ServiceOptions {
  /** Port (Integer) or ws:// URL (String). Null = default ({@code $HUB} or 1997). */
  public Object address;
  /** Profile name shown in the hub. */
  public String name;
  /** Profile icon: a String or a {@code {"symbol": .., "text": ..}} map. */
  public Object icon;
  /** Hub-pro auth token supplier, called once per (re)connect. Null = {@link Keychain#sign()}. */
  public Supplier<String> sign;

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
}
