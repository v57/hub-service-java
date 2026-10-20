package v57.hubservice;

import v57.channel.JsonCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pinned hub keys ({@code Docs/Devices/protocol.md} section 2): the file
 * {@code hubs.json} beside the service key file, a JSON object
 * {@code {"<address>": "<hub SPKI>"}}, keyed by the normalized address
 * ({@link Service#normalizeAddress(Object)}). A service learns a hub's key from
 * {@code hub/info} on its first (v0) connection and signs v1 tokens bound to
 * it from then on.
 */
public final class HubPins {
  private final Path path;

  /** Pins stored in {@code path}. */
  public HubPins(Path path) {
    this.path = path;
  }

  /** Pins stored in {@code hubs.json} beside {@code keyPath}. */
  public static HubPins beside(Path keyPath) {
    var parent = keyPath.toAbsolutePath().getParent();
    return new HubPins(parent == null ? Path.of("hubs.json") : parent.resolve("hubs.json"));
  }

  /** The file holding the pins. */
  public Path path() {
    return path;
  }

  /** The pinned key for {@code address}, or null. */
  public synchronized String get(String address) {
    var value = read().get(address);
    return value instanceof String key ? key : null;
  }

  /** Pins {@code hubKey} for {@code address}. */
  public synchronized void put(String address, String hubKey) {
    var pins = read();
    pins.put(address, hubKey);
    write(pins);
  }

  /** Forgets the pinned key for {@code address}. */
  public synchronized void remove(String address) {
    var pins = read();
    if (pins.remove(address) != null) {
      write(pins);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> read() {
    try {
      if (!Files.exists(path)) {
        return new LinkedHashMap<>();
      }
      var value = JsonCodec.decode(Files.readString(path));
      if (value instanceof Map<?, ?> map) {
        return new LinkedHashMap<>((Map<String, Object>) map);
      }
    } catch (Exception ignored) {
      // A corrupt pin file counts as empty: the next hub/info pins again.
    }
    return new LinkedHashMap<>();
  }

  private void write(Map<String, Object> pins) {
    try {
      KeyFile.writeAtomic(path, JsonCodec.encode(pins), false);
    } catch (Exception error) {
      throw new IllegalStateException("failed to write " + path, error);
    }
  }
}
