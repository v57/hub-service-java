package v57.hubservice;

import java.util.Map;

/**
 * A request handed to post and stream handlers registered with a
 * {@code (body, request)} handler.
 *
 * @param path    the API path called
 * @param body    the decoded request body
 * @param caller  who is calling ({@code context.hub.caller}); null when the hub sent none
 * @param hubKey  the forwarding hub's SPKI ({@code context.hub.hubKey}); null when the hub sent none
 * @param context the whole request context (selection keys such as {@code service} and {@code device} included)
 */
public record Request(String path, Object body, Caller caller, String hubKey, Map<String, Object> context) {
  @SuppressWarnings("unchecked")
  static Request of(String path, Object body, Object context) {
    var map = context instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();
    return new Request(path, body, Caller.fromContext(map), Caller.hubKey(map), map);
  }
}
