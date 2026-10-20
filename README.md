<h1>
  <img alt="Hub Service logo" src="./icon.png" width="70" valign="middle">
  &nbsp;hub-service-java
</h1>

Java port of [hub-service](https://github.com/v57/hub-service) — connect to a
[hub](https://github.com/v57/hub-lite) (default `ws://127.0.0.1:1997`), host
your own post/stream API endpoints, register apps, and call API through the hub.

The wire contract matches the TS reference (`hub/profile/update`,
`hub/service/update`, AppInterface + element DSL). Hub-pro auth is supported:
every (re)connect sends `auth` + `v` headers signed by the ed25519 keychain
(`auth` key file in the cwd — or `$HUBKEY`; generated if missing), exactly
like the TS `hub-service/keychain.ts`. hub-lite ignores the headers.

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
- `Keychain` — `sign()` → `key.<pub>.<sig>.<expiry>` token, `signHub(hubKey)`
  (v1, bound to one hub), `publicKey()`, `setKeyPath(...)`; loads/generates
  the `auth` file (default `auth` in cwd or `$HUBKEY`); attached as the
  `auth` header on every (re)connect
- Hub-bound auth: the first connect uses `v: 0`, asks `hub/info` for the hub
  key, pins it in `hubs.json` beside the key file and reconnects with `v: 1`
  (`ServiceOptions.hubKey(...)` supplies it up front, `forgetHubKey()` clears
  the pin, `lastError()` reports a refused connect)
- Device mode (on by default): every connect also sends a `device` header, a
  7-day certificate for the service key signed by the device key
  (`~/.hub/device.key`, `$HUB_DEVICE_KEY` or `ServiceOptions.deviceKeyPath`,
  created 0600; `deviceKey(KeyFile)` for a key kept elsewhere), renewed at
  connect with less than a day left. `instance(...)` names the service on its
  device (default: profile name, else first path segment, else `service`);
  `deviceName`, `platform`, `parent` override the suggestions; `device(false)`
  turns it off
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
