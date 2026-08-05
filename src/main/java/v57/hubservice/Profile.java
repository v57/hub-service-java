package v57.hubservice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Profile sent to the hub as {@code hub/profile/update} on connect. Only
 * created when a name and/or icon is configured (TS/Go parity: the profile
 * update is skipped entirely when neither is set).
 */
public final class Profile {
  private final String name;
  private final Object icon;

  Profile(String name, Object icon) {
    this.name = name;
    this.icon = icon;
  }

  public String name() {
    return name;
  }

  public Object icon() {
    return icon;
  }

  /** Wire map: {@code {"name": .., "icon": ..}} with only the set keys. */
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    if (name != null && !name.isEmpty()) {
      map.put("name", name);
    }
    if (icon != null) {
      map.put("icon", icon);
    }
    return map;
  }
}
