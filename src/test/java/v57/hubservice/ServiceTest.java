package v57.hubservice;

import org.junit.jupiter.api.Test;
import v57.channel.ChannelStreams;
import v57.channel.JsonCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTest {
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> services(Map<String, Object> update) {
    return (List<Map<String, Object>>) update.get("services");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> apps(Map<String, Object> update) {
    return (List<Map<String, Object>>) update.get("apps");
  }

  @Test
  void updateListsAllRegisteredPathsSorted() {
    var service = new Service();
    service.post("echo", body -> body);
    service.stream("echo/stream", body -> ChannelStreams.fromIterable(List.of()));
    service.app(new App(new AppHeader("Echo", "echo/ui"), List.of()));

    var update = service.update();
    assertEquals(
        List.of("echo", "echo/stream", "echo/ui"),
        services(update).stream().map(s -> s.get("path")).toList()
    );
    assertEquals(
        List.of(Map.of("type", "app", "name", "Echo", "path", "echo/ui")),
        apps(update)
    );
  }

  @Test
  void settingsGroupAutoFilledFromFirstPathSegment() {
    var service = new Service();
    service.post(
        "echo",
        body -> body,
        new ApiSettings(new ServicePermissions(null, "Echo", true), 5)
    );

    var entry = services(service.update()).get(0);
    @SuppressWarnings("unchecked")
    var permissions = (Map<String, Object>) entry.get("permissions");
    assertEquals("echo", permissions.get("group"));
    assertEquals("Echo", permissions.get("name"));
    assertEquals(Boolean.TRUE, permissions.get("allows"));
    assertEquals(5, entry.get("limit"));
  }

  @Test
  void serviceHeaderOmitsOptionalKeysWhenNoSettings() {
    var service = new Service();
    service.post("echo", body -> body);
    assertEquals(List.of(Map.of("path", "echo")), services(service.update()));
  }

  @Test
  void updatePayloadJsonEqualsExactWireString() {
    var service = new Service();
    service.post(
        "echo",
        body -> body,
        new ApiSettings(new ServicePermissions(null, "Echo", true), 5)
    );

    assertEquals(
        "{\"services\":[{\"path\":\"echo\",\"permissions\":{\"group\":\"echo\",\"name\":\"Echo\",\"allows\":true},\"limit\":5}],\"apps\":[]}",
        JsonCodec.encode(service.update())
    );
  }

  @Test
  void updatePayloadWithAppMatchesWireContractExample() {
    var service = new Service();
    service.post(
        "echo",
        body -> body,
        new ApiSettings(new ServicePermissions(null, "Echo", true), 5)
    );
    service.app(new App(new AppHeader("Echo", "echo/ui"), List.of()));

    // The app path is also a registered stream handler, so it appears in
    // services (TS/Go parity) as well as in apps.
    assertEquals(
        "{\"services\":[{\"path\":\"echo\",\"permissions\":{\"group\":\"echo\",\"name\":\"Echo\",\"allows\":true},\"limit\":5},{\"path\":\"echo/ui\"}],\"apps\":[{\"type\":\"app\",\"name\":\"Echo\",\"path\":\"echo/ui\"}]}",
        JsonCodec.encode(service.update())
    );
  }

  @Test
  void sendAndValuesBeforeStartThrow() {
    var service = new Service();
    assertThrows(IllegalStateException.class, () -> service.send("echo", "x"));
    assertThrows(IllegalStateException.class, () -> service.values("echo", "x"));
  }

  @Test
  void profileOnlyWhenNameOrIconSet() {
    assertNull(new Service().profile());

    var icon = new LinkedHashMap<String, Object>();
    icon.put("symbol", "speaker.wave.2");
    icon.put("text", "E");
    var withIcon = new Service(new ServiceOptions().name("Echo").icon(icon));
    assertNotNull(withIcon.profile());
    assertEquals(
        "{\"name\":\"Echo\",\"icon\":{\"symbol\":\"speaker.wave.2\",\"text\":\"E\"}}",
        JsonCodec.encode(withIcon.profile().toMap())
    );

    var nameOnly = new Service(new ServiceOptions().name("Echo"));
    assertEquals("{\"name\":\"Echo\"}", JsonCodec.encode(nameOnly.profile().toMap()));

    var iconOnly = new Service(new ServiceOptions().icon("star"));
    assertEquals("{\"icon\":\"star\"}", JsonCodec.encode(iconOnly.profile().toMap()));
  }

  @Test
  void appInterfaceHasAllFiveKeys() {
    var app = new App(
        new AppHeader("Echo", "echo/ui"),
        List.of("Hello", App.button("Upper", new App.Action("echo/upper", "value", "value"))),
        Map.of("hello", "world")
    );
    var iface = app.interfaceMap();

    assertEquals(5, iface.size());
    assertTrue(iface.containsKey("header"));
    assertTrue(iface.containsKey("body"));
    assertTrue(iface.containsKey("top"));
    assertTrue(iface.containsKey("bottom"));
    assertTrue(iface.containsKey("data"));
    assertNull(iface.get("top"));
    assertNull(iface.get("bottom"));

    @SuppressWarnings("unchecked")
    var header = (Map<String, Object>) iface.get("header");
    assertEquals("app", header.get("type"));
    assertEquals("Echo", header.get("name"));
    assertEquals("echo/ui", header.get("path"));
    assertEquals(Map.of("hello", "world"), iface.get("data"));

    var json = JsonCodec.encode(iface);
    assertTrue(json.contains("\"header\":"));
    assertTrue(json.contains("\"body\":"));
    assertTrue(json.contains("\"top\":"));
    assertTrue(json.contains("\"bottom\":"));
    assertTrue(json.contains("\"data\":"));
  }

  @Test
  void elementHelpersMatchWireShapes() {
    assertEquals(
        "{\"type\":\"text\",\"value\":\"hi\",\"secondary\":true}",
        JsonCodec.encode(App.text("hi", true))
    );
    assertEquals(
        "{\"type\":\"textField\",\"value\":\"echo\",\"placeholder\":\"Text to echo\",\"action\":{\"path\":\"echo/upper\",\"body\":\"value\",\"output\":\"value\"}}",
        JsonCodec.encode(App.textField("echo", "Text to echo", new App.Action("echo/upper", "value", "value")))
    );
    assertEquals(
        "{\"type\":\"button\",\"title\":\"Upper\",\"action\":{\"path\":\"echo/upper\"}}",
        JsonCodec.encode(App.button("Upper", new App.Action("echo/upper")))
    );
    assertEquals(
        "{\"type\":\"spacer\"}",
        JsonCodec.encode(App.spacer())
    );
  }
}
