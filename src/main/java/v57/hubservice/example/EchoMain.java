package v57.hubservice.example;

import v57.hubservice.App;
import v57.hubservice.AppHeader;
import v57.hubservice.Service;
import v57.hubservice.ServiceOptions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Echo example service: registers {@code echo} and {@code echo/upper} posts
 * plus an app at {@code echo/ui}, connects to the live hub (default
 * 127.0.0.1:1997 or $HUB) and keeps the JVM alive. The Service's daemon poll
 * thread drives the connection, detects the isConnected false→true transition
 * per (re)connect and sends the profile + service updates.
 */
public final class EchoMain {
  public static void main(String[] args) throws InterruptedException {
    var icon = new LinkedHashMap<String, Object>();
    icon.put("symbol", "speaker.wave.2");
    icon.put("text", "E");

    var service = new Service(new ServiceOptions().name("Echo").icon(icon));
    service.post("echo", body -> body);
    service.post("echo/upper", body -> String.valueOf(body).toUpperCase(Locale.ROOT));
    service.app(new App(
        new AppHeader("Echo", "echo/ui"),
        List.of(
            "Hello from Java",
            App.textField("echo", "Text to echo", new App.Action("echo/upper", "value", "value")),
            App.cell("$Echo", "value"),
            App.button("Upper", new App.Action("echo/upper", "value", "value"))
        ),
        Map.of("hello", "world")
    ));
    service.start();
    var hub = System.getenv("HUB");
    System.out.println("echo service started (" + (hub != null && !hub.isEmpty() ? "HUB=" + hub : "hub-lite :1997") + ")");
    while (true) {
      Thread.sleep(1000);
    }
  }
}
