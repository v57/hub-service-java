package v57.hubservice;

import java.util.function.Supplier;

/**
 * Options for {@link Service}.
 *
 * <p>{@code address} is a port (Integer) or a ws:// URL (String). Default:
 * {@code 1997} or the {@code HUB} environment variable. {@code name} is the
 * profile name shown in the hub. {@code icon} is a String or a
 * {@code {"symbol": .., "text": ..}} map. {@code sign} supplies the hub-server
 * auth token for each connect (default: the keychain — the {@code auth} key
 * file in the cwd, or {@code $HUBKEY} — signing v1 tokens bound to the hub
 * key once it is pinned; a custom supplier always connects with {@code v: 0}).
 * {@code hubKey} supplies the hub's key up front (from an invitation or
 * config) instead of learning it from {@code hub/info}.
 *
 * <p>Device mode ({@code Docs/Devices/protocol.md} section 3) is on by
 * default: the device key ({@code deviceKey}, else the file at
 * {@code deviceKeyPath}, {@code $HUB_DEVICE_KEY} or {@code ~/.hub/device.key})
 * signs a certificate for the service key, sent in the {@code device} header.
 * {@code instance} names this service on its device (default: the profile
 * name, else the working directory's name, else
 * {@code service}); {@code deviceName}, {@code platform} and {@code parent}
 * override what the certificate suggests. {@code device(false)} turns it off.
 */
public final class ServiceOptions {
  /** Port (Integer) or ws:// URL (String). Null = default ({@code $HUB} or 1997). */
  public Object address;
  /** Profile name shown in the hub. */
  public String name;
  /** Profile icon: a String or a {@code {"symbol": .., "text": ..}} map. */
  public Object icon;
  /** hub-server auth token supplier, called once per (re)connect. Null = the keychain (v1 once pinned). */
  public Supplier<String> sign;
  /** The hub's SPKI, if known up front. Null = learn and pin it via {@code hub/info}. */
  public String hubKey;
  /** Device mode: send a device certificate with every connect (default true). */
  public boolean device = true;
  /** A caller-provided device key (e.g. kept in an app's own keychain). Null = the device key file. */
  public KeyFile deviceKey;
  /** The device key file. Null = {@link Device#defaultKeyPath()}. */
  public String deviceKeyPath;
  /** The instance name on this device, e.g. {@code s3-local/Backup Disk}. */
  public String instance;
  /** The suggested device name. Null = the computer name. */
  public String deviceName;
  /** The certificate platform. Null = this OS. */
  public String platform;
  /** The declared parent device name (containers and VMs). */
  public String parent;

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

  public ServiceOptions device(boolean device) {
    this.device = device;
    return this;
  }

  public ServiceOptions deviceKey(KeyFile deviceKey) {
    this.deviceKey = deviceKey;
    return this;
  }

  public ServiceOptions deviceKeyPath(String deviceKeyPath) {
    this.deviceKeyPath = deviceKeyPath;
    return this;
  }

  public ServiceOptions instance(String instance) {
    this.instance = instance;
    return this;
  }

  public ServiceOptions deviceName(String deviceName) {
    this.deviceName = deviceName;
    return this;
  }

  public ServiceOptions platform(String platform) {
    this.platform = platform;
    return this;
  }

  public ServiceOptions parent(String parent) {
    this.parent = parent;
    return this;
  }
}
