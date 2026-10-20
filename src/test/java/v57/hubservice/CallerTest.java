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
    var request = Request.of("echo", "hi", null);
    assertNull(request.caller());
    assertNull(request.hubKey());
    assertEquals(Map.of(), request.context());
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
