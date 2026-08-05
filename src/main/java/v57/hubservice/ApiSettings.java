package v57.hubservice;

/**
 * Settings for a registered API path (pro-only fields, wire parity; hub-lite
 * ignores them).
 */
public final class ApiSettings {
  public ServicePermissions permissions;
  public Integer limit;

  public ApiSettings() {
  }

  public ApiSettings(ServicePermissions permissions, Integer limit) {
    this.permissions = permissions;
    this.limit = limit;
  }
}
