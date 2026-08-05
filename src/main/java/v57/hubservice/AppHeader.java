package v57.hubservice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Identifies an app in the hub apps list ({@code hub/service/update}).
 * Wire shape: {@code {"type": "app", "name": .., "path": ..}}.
 */
public final class AppHeader {
  public final String type;
  public final String name;
  public final String path;

  public AppHeader(String name, String path) {
    this("app", name, path);
  }

  public AppHeader(String type, String name, String path) {
    this.type = type;
    this.name = name;
    this.path = path;
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("type", type);
    map.put("name", name);
    map.put("path", path);
    return map;
  }
}
