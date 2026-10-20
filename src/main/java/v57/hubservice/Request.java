package v57.hubservice;

import java.util.Map;

/**
 * A request handed to post and stream handlers registered with a
 * {@code (body, request)} handler.
 *
 * @param path    the API path called
 * @param body    the decoded request body
 * @param caller  who is calling ({@code context.hub.caller}); null when the hub sent none or when its
 *                {@code context.hub.hubKey} isn't the hub key this service pinned
 * @param hubKey  the forwarding hub's SPKI ({@code context.hub.hubKey}); null when the hub sent none
 * @param context the whole request context (selection keys such as {@code service} and {@code device} included)
 */
public record Request(String path, Object body, Caller caller, String hubKey, Map<String, Object> context) {
  /**
   * The request for {@code context}. The caller is trusted only when the
   * context names {@code pinnedHubKey} as its hub (TS parity), so a service
   * without a pinned key sees no caller.
   */
  @SuppressWarnings("unchecked")
  static Request of(String path, Object body, Object context, String pinnedHubKey) {
    var map = context instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();
    var hubKey = Caller.hubKey(map);
    var caller = pinnedHubKey != null && pinnedHubKey.equals(hubKey) ? Caller.fromContext(map) : null;
    return new Request(path, body, caller, hubKey, map);
  }
}
