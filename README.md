<h1>
  <img alt="Hub Service logo" src="./icon.png" width="70" valign="middle">
  &nbsp;hub-service-java
</h1>

Java port of [hub-service](https://github.com/v57/hub-service) — connect to a
[hub](https://github.com/v57/hub-lite) (default `ws://127.0.0.1:1997`), host
your own post/stream API endpoints, register apps, and call API through the hub.

The wire contract matches the TS reference (`hub/profile/update`,
`hub/service/update`, AppInterface + element DSL). hub-server auth is supported:
every (re)connect asks the hub to prove its key, then sends a fresh `auth` token
signed by the ed25519 keychain (`auth` key file in the cwd — or `$HUBKEY`;
generated if missing), exactly like the TS `hub-service/keychain.ts`.

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
- `ServiceOptions.sign((hubKey, challenge) -> token)` — custom token factory given
  the proven hub key and the challenge (turns off device mode);
  `allowPlaintext(true)` dials plain `ws://` beyond loopback, the overlay and the
  LAN (also `HUB_ALLOW_PLAINTEXT=1`)
- `Keychain` — `signHub(hubKey, nonce)` → `key.<pub>.<sig>.<expiry>.<nonce>` token
  for one connection (30 s), `publicKey()`, `setKeyPath(...)`; loads/generates
  the `auth` file (default `auth` in cwd or `$HUBKEY`)
- Hub-bound tokens (`Docs/Devices/protocol.md` section 2): a token signs
  `hub1:<hub key>:<nonce>:<expiry>`, so it works once, on the hub it was made
  for. Before every connect the service asks `hub/info` with a fresh nonce on a
  short anonymous connection. Only an answer with a valid proof of the key it
  claims gets a token, over that answer's challenge. The first proven key is
  pinned in `hubs.json` beside the key file. A hub that doesn't prove its key, or
  answers with another than the pinned one, gets nothing and is reported while the
  service keeps trying, so calls made right after `start()` wait instead of being
  lost. Plain `ws://` beyond loopback, the overlay and the LAN isn't dialed unless
  `allowPlaintext` says so (`HubTransport.of(address)` classifies an address).
  `ServiceOptions.hubKey(...)` supplies the key up front, `forgetHubKey()` clears
  the pin, `lastError()` reports the last failure
- Device mode (on by default): every connect also sends a `device` header, a
  7-day certificate for the service key signed by the device key
  (`~/.hub/device.key`, `$HUB_DEVICE_KEY` or `ServiceOptions.deviceKeyPath`,
  created 0600; `deviceKey(KeyFile)` for a key kept elsewhere), renewed at
  connect with less than a day left. `instance(...)` names the service on its
  device (default: profile name, else the working directory's name, else
  `service`);
  `deviceName`, `platform`, `parent` override the suggestions; `device(false)`
  turns it off
- `post(String path, Function handler, ApiSettings... settings)` — fluent
- `post(String path, BiFunction<Object, Request, Object> handler, ...)` —
  the handler also gets the `Request`: `caller()` (who is calling, from the
  hub's `context.hub.caller`: `key`, `deviceId`, `deviceName`, `groups`,
  `owner`, and `via`/`claimed` through a merged hub; null on hubs that don't
  send it, and unless the request's `context.hub.hubKey` is the pinned hub
  key), `hubKey()` and the whole `context()`
- `stream(String path, ...)` — streaming endpoint (also with a `(body, request)` handler)
- Hub Network shares (TS `publishShare` parity): `share(name, protocol, host,
  port, policy)` declares one before `start()` (republished on every
  connect); `publishShare(...)`, `updateShare(name, policy, port)` and
  `removeShare(name)` change the set at runtime. The local hub-net agent
  attests each share over its Unix socket (`$HUB_NET_AGENT_SOCKET`, else
  `/var/run/hub-net/agent.sock` on macOS, `/run/hub-net/agent.sock` on Linux;
  `network().agentSocket(...)` overrides it), then the set goes to the hub
  with `network/shares/replace`. `network().publications()` lists the results
- `app(App)` — register app header + `AppInterface` stream at `header.path`
- `send(String path, Object body)` — client call through the hub
- `values(String path, Object body)` — stream call through the hub
- `start()` — connect + poll thread; detects `isConnected` false→true per
  connection and re-sends `hub/profile/update` then `hub/service/update`

`ApiSettings` carries `Permissions` (group auto-filled from the first path
segment, TS parity) and `Limit` — wire-parity fields that hub-lite ignores.

Note: channel-java's client has no `onConnect` callback, so `start()` runs a
daemon poll thread (`ChannelClient.poll()`) and sends the update messages on
each `isConnected()` transition. It only dials `ws://`, so a `wss://` address is
reported and retried, never downgraded.

## Test

```bash
./scripts/test            # unit tests (gradle test)
# cross-language check (TS):
bun ../hub-service/tests/verify.ts echo echo/upper '"hello"' '"HELLO"'
```
