package v57.hubservice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A hub app: a header plus a UI body of elements (TS app.ts parity). Body
 * entries are plain strings (shorthand text) or element maps built with the
 * static helpers below.
 *
 * <p>The AppInterface map streamed at the app path always carries all five
 * keys: {@code header}, {@code body}, {@code top}, {@code bottom},
 * {@code data}.
 */
public final class App {
  private final AppHeader header;
  private final List<Object> body;
  private final Object top;
  private final Object bottom;
  private final Map<String, Object> data;

  public App(AppHeader header, List<Object> body) {
    this(header, body, null, null, Map.of());
  }

  public App(AppHeader header, List<Object> body, Map<String, Object> data) {
    this(header, body, null, null, data);
  }

  public App(AppHeader header, List<Object> body, Object top, Object bottom, Map<String, Object> data) {
    this.header = header;
    this.body = body;
    this.top = top;
    this.bottom = bottom;
    this.data = data == null ? Map.of() : data;
  }

  public AppHeader header() {
    return header;
  }

  public List<Object> body() {
    return body;
  }

  public Object top() {
    return top;
  }

  public Object bottom() {
    return bottom;
  }

  public Map<String, Object> data() {
    return data;
  }

  /** The AppInterface streamed at the app path (all 5 keys always present). */
  public Map<String, Object> interfaceMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("header", header.toMap());
    map.put("body", body);
    map.put("top", top);
    map.put("bottom", bottom);
    map.put("data", data);
    return map;
  }

  /** Action referencing an API call from a UI element: {@code {path, body?, output?}}. */
  public record Action(String path, Object body, Object output) {
    public Action(String path) {
      this(path, null, null);
    }

    public Action(String path, Object body) {
      this(path, body, null);
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("path", path);
      if (body != null) {
        map.put("body", body);
      }
      if (output != null) {
        map.put("output", output);
      }
      return map;
    }
  }

  // ---- element DSL (TS app.ts wire shapes; optional keys omitted) ----

  /** {@code {type: "text", value, secondary?}} */
  public static Map<String, Object> text(String value) {
    return text(value, null);
  }

  /** {@code {type: "text", value, secondary?}} */
  public static Map<String, Object> text(String value, Object secondary) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "text");
    e.put("value", value);
    if (secondary != null) {
      e.put("secondary", secondary);
    }
    return e;
  }

  /** {@code {type: "progress", value, min?, max?}} */
  public static Map<String, Object> progress(Object value) {
    return progress(value, null, null);
  }

  /** {@code {type: "progress", value, min?, max?}} */
  public static Map<String, Object> progress(Object value, Object min, Object max) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "progress");
    e.put("value", value);
    if (min != null) {
      e.put("min", min);
    }
    if (max != null) {
      e.put("max", max);
    }
    return e;
  }

  /** {@code {type: "textField", value, placeholder?, action?}} */
  public static Map<String, Object> textField(String value) {
    return textField(value, null, null);
  }

  /** {@code {type: "textField", value, placeholder?, action?}} */
  public static Map<String, Object> textField(String value, String placeholder, Action action) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "textField");
    e.put("value", value);
    if (placeholder != null && !placeholder.isEmpty()) {
      e.put("placeholder", placeholder);
    }
    if (action != null) {
      e.put("action", action.toMap());
    }
    return e;
  }

  /** {@code {type: "button", title, action}} */
  public static Map<String, Object> button(String title, Action action) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "button");
    e.put("title", title);
    e.put("action", action.toMap());
    return e;
  }

  /** {@code {type: "slider", value, defaultValue?, min?, max?, step?, action?}} */
  public static Map<String, Object> slider(Object value) {
    return slider(value, null, null, null, null, null);
  }

  /** {@code {type: "slider", value, defaultValue?, min?, max?, step?, action?}} */
  public static Map<String, Object> slider(Object value, Object defaultValue, Object min, Object max, Object step, Action action) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "slider");
    e.put("value", value);
    if (defaultValue != null) {
      e.put("defaultValue", defaultValue);
    }
    if (min != null) {
      e.put("min", min);
    }
    if (max != null) {
      e.put("max", max);
    }
    if (step != null) {
      e.put("step", step);
    }
    if (action != null) {
      e.put("action", action.toMap());
    }
    return e;
  }

  /** {@code {type: "list", data, content}} */
  public static Map<String, Object> list(Object data, Object content) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "list");
    e.put("data", data);
    e.put("content", content);
    return e;
  }

  /** {@code {type: "picker", options, selected}} */
  public static Map<String, Object> picker(List<String> options, String selected) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "picker");
    e.put("options", options);
    e.put("selected", selected);
    return e;
  }

  /** {@code {type: "cell", title?, subtitle?}} */
  public static Map<String, Object> cell(Object title) {
    return cell(title, null);
  }

  /** {@code {type: "cell", title?, subtitle?}} */
  public static Map<String, Object> cell(Object title, Object subtitle) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "cell");
    if (title != null) {
      e.put("title", title);
    }
    if (subtitle != null) {
      e.put("subtitle", subtitle);
    }
    return e;
  }

  /** {@code {type: "files", value, title?, action}} */
  public static Map<String, Object> files(String value, Object title, Action action) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "files");
    e.put("value", value);
    if (title != null) {
      e.put("title", title);
    }
    e.put("action", action.toMap());
    return e;
  }

  /** {@code {type: "fileOperation", format?, title?, action}} */
  public static Map<String, Object> fileOperation(String format, Object title, Action action) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "fileOperation");
    if (format != null && !format.isEmpty()) {
      e.put("format", format);
    }
    if (title != null) {
      e.put("title", title);
    }
    e.put("action", action.toMap());
    return e;
  }

  /** {@code {type: "hstack", content, spacing?}} */
  public static Map<String, Object> hstack(List<Object> content) {
    return hstack(content, null);
  }

  /** {@code {type: "hstack", content, spacing?}} */
  public static Map<String, Object> hstack(List<Object> content, Object spacing) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "hstack");
    e.put("content", content);
    if (spacing != null) {
      e.put("spacing", spacing);
    }
    return e;
  }

  /** {@code {type: "vstack", content, spacing?}} */
  public static Map<String, Object> vstack(List<Object> content) {
    return vstack(content, null);
  }

  /** {@code {type: "vstack", content, spacing?}} */
  public static Map<String, Object> vstack(List<Object> content, Object spacing) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "vstack");
    e.put("content", content);
    if (spacing != null) {
      e.put("spacing", spacing);
    }
    return e;
  }

  /** {@code {type: "zstack", content}} */
  public static Map<String, Object> zstack(List<Object> content) {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "zstack");
    e.put("content", content);
    return e;
  }

  /** {@code {type: "spacer"}} */
  public static Map<String, Object> spacer() {
    var e = new LinkedHashMap<String, Object>();
    e.put("type", "spacer");
    return e;
  }
}
