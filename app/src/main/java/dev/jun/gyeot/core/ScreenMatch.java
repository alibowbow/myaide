package dev.jun.gyeot.core;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Carries an element over from a reading that went stale to a fresh one. Pages redraw a little all the time (a web page
 * finishing a load, the text just typed into a field), and refusing every action after that cost a round trip each time.
 * An element is carried over only when it is plainly the same one: the page is still mostly the same, and exactly one
 * element of the same kind, with the same label, sits about where it was. Anything less certain is refused as before.
 */
public final class ScreenMatch {
    /** Share of the earlier labels still on screen for it to count as the same page. */
    private static final double SAME_PAGE = 0.5;
    /** How far an element may have moved, as a share of the screen's longer side. */
    private static final double NEAR = 0.15;
    private ScreenMatch() {}

    /** The id in after of the element id in before, or null when it is not plainly the same element on the same page. */
    public static String find(JSONArray before, String id, JSONArray after, int width, int height) {
        JSONObject wanted = element(before, id);
        if (wanted == null || after == null) return null;
        if (!samePage(before, after)) return null;
        boolean input = "input".equals(wanted.optString("role"));
        String text = wanted.optString("text"), desc = wanted.optString("desc");
        // An unlabeled control cannot be told apart from its neighbours; a field may have new text, so it goes by place.
        if (!input && text.isEmpty() && desc.isEmpty()) return null;
        int[] box = bounds(wanted); if (box == null) return null;
        double reach = Math.max(width, height) * NEAR;
        String found = null; int matches = 0;
        for (int i = 0; i < after.length(); i++) {
            JSONObject candidate = after.optJSONObject(i); if (candidate == null) continue;
            if (!wanted.optString("role").equals(candidate.optString("role"))) continue;
            int[] other = bounds(candidate); if (other == null) continue;
            boolean same = input ? desc.equals(candidate.optString("desc")) && overlap(box, other) >= 0.5
                : text.equals(candidate.optString("text")) && desc.equals(candidate.optString("desc")) && distance(box, other) <= reach;
            if (!same) continue;
            matches++; found = candidate.optString("id");
        }
        return matches == 1 ? found : null;
    }
    static JSONObject element(JSONArray elements, String id) {
        for (int i = 0; elements != null && i < elements.length(); i++) { JSONObject element = elements.optJSONObject(i); if (element != null && id.equals(element.optString("id"))) return element; }
        return null;
    }
    /** At least half of the earlier labels are still there. */
    static boolean samePage(JSONArray before, JSONArray after) {
        Set<String> was = labels(before), now = labels(after);
        if (was.isEmpty()) return false;
        int kept = 0; for (String label : was) if (now.contains(label)) kept++;
        return kept >= was.size() * SAME_PAGE;
    }
    private static Set<String> labels(JSONArray elements) {
        Set<String> labels = new HashSet<>();
        for (int i = 0; elements != null && i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i); if (element == null) continue;
            String label = element.optString("text") + "\u0000" + element.optString("desc");
            if (label.length() > 1) labels.add(label);
        }
        return labels;
    }
    private static int[] bounds(JSONObject element) {
        JSONArray box = element.optJSONArray("bounds");
        if (box == null || box.length() != 4) return null;
        return new int[]{box.optInt(0), box.optInt(1), box.optInt(2), box.optInt(3)};
    }
    private static double distance(int[] a, int[] b) { return Math.hypot((a[0] + a[2]) / 2.0 - (b[0] + b[2]) / 2.0, (a[1] + a[3]) / 2.0 - (b[1] + b[3]) / 2.0); }
    /** Intersection over union of two boxes. */
    private static double overlap(int[] a, int[] b) {
        long w = Math.max(0, Math.min(a[2], b[2]) - Math.max(a[0], b[0])), h = Math.max(0, Math.min(a[3], b[3]) - Math.max(a[1], b[1]));
        long inter = w * h, union = (long) (a[2] - a[0]) * (a[3] - a[1]) + (long) (b[2] - b[0]) * (b[3] - b[1]) - inter;
        return union <= 0 ? 0 : (double) inter / union;
    }
}
