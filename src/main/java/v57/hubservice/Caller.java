package v57.hubservice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Who is calling, as the hub reports it in {@code context.hub.caller}
 * ({@code Docs/Devices/protocol.md} section 4). The hub replaces any
 * {@code context.hub} a client sent, and services accept requests only from
 * the hub connection, so handlers can trust it.
 *
 * @param key        the caller's service SPKI; null for anonymous callers
 * @param deviceId   the caller's device id; null unless its connection is device-attached
 * @param deviceName the caller's device name; null without a device
 * @param groups     every group the key or its device belongs to
 * @param owner      whether the caller is in the owners group
 * @param via        through a merged hub: the merged hub's SPKI, else null
 * @param claimed    through a merged hub: the merged hub's own (unverified) caller, else null
 * @param raw        the {@code caller} object as received
 */
public record Caller(
    String key,
    String deviceId,
    String deviceName,
    List<String> groups,
    boolean owner,
    String via,
    Caller claimed,
    Map<String, Object> raw
) {
  /** The caller in a request {@code context}, or null when the hub sent none (hub-lite, older hub-pro). */
  public static Caller fromContext(Object context) {
    if (!(context instanceof Map<?, ?> map) || !(map.get("hub") instanceof Map<?, ?> hub)) {
      return null;
    }
    return from(hub.get("caller"));
  }

  /** Parses a {@code caller} object, or null if it isn't one. */
  @SuppressWarnings("unchecked")
  public static Caller from(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return null;
    }
    String deviceId = null;
    String deviceName = null;
    if (map.get("device") instanceof Map<?, ?> device) {
      deviceId = string(device.get("id"));
      deviceName = string(device.get("name"));
    }
    var groups = new ArrayList<String>();
    if (map.get("groups") instanceof List<?> list) {
      for (var group : list) {
        if (group instanceof String name) {
          groups.add(name);
        }
      }
    }
    return new Caller(
        string(map.get("key")),
        deviceId,
        deviceName,
        List.copyOf(groups),
        Boolean.TRUE.equals(map.get("owner")),
        string(map.get("via")),
        from(map.get("claimed")),
        (Map<String, Object>) map
    );
  }

  /** The hub's SPKI from a request {@code context} ({@code context.hub.hubKey}), or null. */
  public static String hubKey(Object context) {
    if (context instanceof Map<?, ?> map && map.get("hub") instanceof Map<?, ?> hub) {
      return string(hub.get("hubKey"));
    }
    return null;
  }

  /** Whether the caller's connection is attached to a device. */
  public boolean hasDevice() {
    return deviceId != null;
  }

  /** Whether the request came through a merged hub (the caller is then the merging hub's connection). */
  public boolean viaMergedHub() {
    return via != null;
  }

  private static String string(Object value) {
    return value instanceof String text ? text : null;
  }
}
