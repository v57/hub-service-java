<h1>
  <img alt="Hub Service logo" src="./icon.png" width="70" valign="middle">
  &nbsp;hub-service-java
</h1>

Java port of [hub-service](https://github.com/v57/hub-service) — connect to a
[hub](https://github.com/v57/hub-lite) (default `ws://127.0.0.1:1997`), host
your own post/stream API endpoints, register apps, and call API through the hub.

Lite port: no auth, no keychain, no crypto (hub-pro territory). The wire
contract matches the TS reference (`hub/profile/update`, `hub/service/update`,
AppInterface + element DSL).

## Install

Composite build against [channel-java](https://github.com/v57/channel-java)
(`includeBuild` in `settings.gradle.kts`). Java 21 toolchain required.

```bash
./gradlew build
```

## Usage

Start a hub first (`new Hub()` from hub-lite, listens on 1997), then:

```java
import v57.hubservice.Service;
import v57.hubservice.ServiceOptions;
import v57.hubservice.App;
import v57.hubservice.AppHeader;
import static v57.hubservice.App.*;

var service = new Service(new ServiceOptions("Echo",
    Map.of("symbol", "speaker.wave.2", "text", "E")));
service.post("echo", body -> body);
service.post("echo/upper", body -> String.valueOf(body).toUpperCase());
service.app(new App(
    new AppHeader("app", "Echo", "echo/ui"),
    java.util.List.of(
        "Hello from Java",
        textField("echo", "Text to echo", new Action("echo/upper", "value", "value")),
        cell("$Echo", "value"),
        button("Upper", new Action("echo/upper", "value", "value"))
    ),
    Map.of("hello", "world")
));
service.start(); // spawns a poll thread; re-announces on every reconnect
// keep the JVM alive (e.g. Thread.sleep / await)
```

Call it through the hub (any language):

```java
Object response = service.send("echo/upper", "hello"); // "HELLO"
```

## API

- `Service(ServiceOptions)` — `ServiceOptions(String name, Map<String,Object> icon)`,
  default address 1997 or `HUB` env
- `post(String path, Function handler, ApiSettings... settings)` — fluent
- `stream(String path, ...)` — streaming endpoint
- `app(App)` — register app header + `AppInterface` stream at `header.path`
- `send(String path, Object body)` — client call through the hub
- `values(String path, Object body)` — stream call through the hub
- `start()` — connect + poll thread; detects `isConnected` false→true per
  connection and re-sends `hub/profile/update` then `hub/service/update`

`ApiSettings` carries `Permissions` (group auto-filled from the first path
segment, TS parity) and `Limit` — wire-parity fields that hub-lite ignores.

Note: channel-java's client has no `onConnect` callback, so `start()` runs a
daemon poll thread (`ChannelClient.poll()`) and sends the update messages on
each `isConnected()` transition.

## Test

```bash
./scripts/test            # unit tests (gradle test)
# cross-language check (TS):
bun ../hub-service/tests/verify.ts echo echo/upper '"hello"' '"HELLO"'
```
