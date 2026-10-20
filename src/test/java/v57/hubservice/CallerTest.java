package v57.hubservice;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallerTest {
  @Test
  void noHubContextMeansNoCaller() {
    assertNull(Caller.fromContext(null));
    assertNull(Caller.fromContext(Map.of("service", "x")));
    var request = Request.of("echo", "hi", null, "hub");
    assertNull(request.caller());
    assertNull(request.hubKey());
    assertEquals(Map.of(), request.context());
  }

  @Test
  void callerOnlyFromThePinnedHub() {
    var context = Map.of("hub", Map.of("caller", Map.of("key", "k", "owner", true), "hubKey", "hub-a"));
    assertEquals("k", Request.of("p", null, context, "hub-a").caller().key());
    assertNull(Request.of("p", null, context, "hub-b").caller());
    assertNull(Request.of("p", null, context, null).caller());
    assertEquals("hub-a", Request.of("p", null, context, null).hubKey());
  }

  @Test
  void anonymousCallerHasNoKeyOrDevice() {
    var caller = Caller.fromContext(Map.of("hub", Map.of("caller", Map.of("groups", List.of(), "owner", false))));
    assertNull(caller.key());
    assertFalse(caller.hasDevice());
    assertFalse(caller.owner());
    assertEquals(List.of(), caller.groups());
  }

  @Test
  void mergedHubCallerKeepsTheUnverifiedClaim() {
    var caller = Caller.fromContext(Map.of("hub", Map.of("caller", Map.of(
        "key", "merging",
        "groups", List.of("g1", 5),
        "owner", false,
        "via", "merged-hub",
        "claimed", Map.of("key", "remote", "device", Map.of("id", "d1", "name", "Phone"), "owner", true)))));
    assertEquals("merging", caller.key());
    assertEquals(List.of("g1"), caller.groups());
    assertTrue(caller.viaMergedHub());
    assertEquals("merged-hub", caller.via());
    assertEquals("remote", caller.claimed().key());
    assertEquals("Phone", caller.claimed().deviceName());
    assertTrue(caller.claimed().owner());
  }
}
