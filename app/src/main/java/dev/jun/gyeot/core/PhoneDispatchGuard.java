package dev.jun.gyeot.core;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Revalidate the actual action target just before dispatch, not unrelated screen text. */
public final class PhoneDispatchGuard {
    private PhoneDispatchGuard() {}
    public static boolean independent(String action) {
        return Set.of("open_app", "open_url", "home", "recents", "notifications", "wait").contains(action);
    }
    public static JSONObject rebind(JSONObject args, JSONObject before, JSONObject after,
            boolean sameWindow, boolean unchanged) {
        JSONObject bound = copy(args);
        String action = args.optString("action"), id = args.optString("element");
        if (independent(action)) return bound;
        if (!sameWindow || before == null || after == null)
            throw new IllegalStateException("조작할 앱이나 창이 바뀌었어요. 현재 화면을 다시 확인해 주세요.");
        if (Set.of("tap", "long_press", "press_enter", "send").contains(action)
                && !editableState(before.optJSONArray("elements")).equals(editableState(after.optJSONArray("elements"))))
            throw new IllegalStateException("입력 내용이나 입력칸이 바뀌었어요. 제출 전에 현재 화면을 다시 확인해 주세요.");
        if (!id.isEmpty() && (PhoneTools.TARGETED.contains(action) || "press_enter".equals(action))) {
            JSONArray size = after.optJSONArray("screen");
            JSONArray previous = before.optJSONArray("elements"), current = after.optJSONArray("elements");
            // Exact unchanged observations also support unlabeled accessibility controls.
            boolean identical = previous != null && current != null && previous.toString().equals(current.toString());
            String next = identical ? id : size == null ? null : ScreenMatch.find(previous, id,
                after.optJSONArray("elements"), size.optInt(0), size.optInt(1));
            JSONObject target = next == null ? null : ScreenMatch.element(after.optJSONArray("elements"), next);
            if (target == null || target.optString("flags").contains("password") || target.optString("flags").contains("disabled"))
                throw new IllegalStateException("판단한 대상과 현재 요소가 달라요. 화면을 다시 읽어 주세요.");
            put(bound, "element", next); put(bound, "snapshot_id", after.optString("snapshot_id"));
        } else if (!"back".equals(action) && !unchanged) {
            // Coordinates, implicit focused inputs and Send have no independently bound element.
            throw new IllegalStateException("좌표 또는 입력 대상이 바뀌었어요. 현재 화면을 다시 읽어 주세요.");
        }
        return bound;
    }
    /** Local-only comparison: a stable submit button must not submit a field changed since it was read. */
    private static String editableState(JSONArray elements) {
        JSONArray state = arr();
        for (int i = 0; elements != null && i < elements.length(); i++) {
            JSONObject item = elements.optJSONObject(i);
            if (item == null || !("input".equals(item.optString("role")) || item.optString("flags").contains("edit"))) continue;
            state.put(obj("role", item.optString("role"), "desc", item.optString("desc"), "text", item.optString("text"),
                "bounds", item.optJSONArray("bounds"), "password", item.optString("flags").contains("password")));
        }
        return state.toString();
    }
}
