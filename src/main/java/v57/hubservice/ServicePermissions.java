package v57.hubservice;

/**
 * Pro-only wire-parity field; hub-lite ignores it. Wire shape:
 * {@code {"group": .., "name": .., "allows": ..}}.
 */
public final class ServicePermissions {
  /** Permission group; auto-filled with the first path segment when missing. */
  public String group;
  /** Permission name. */
  public String name;
  /** Allows permission by default. */
  public Boolean allows;

  public ServicePermissions() {
  }

  public ServicePermissions(String group, String name, Boolean allows) {
    this.group = group;
    this.name = name;
    this.allows = allows;
  }
}
