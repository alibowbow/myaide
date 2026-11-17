package dev.jun.gyeot.core;

import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Minimal, bounded local evidence for the automation gate and completion checks. Never keeps editable values or screenshots. */
public final class PhoneDecisionEvidence {
    private PhoneDecisionEvidence() {}
    /** Longest label kept for the control a tap reaches, with every text it shows; and how many of those texts. */
    public static final int RECEIVER_LABEL = 300, RECEIVER_PARTS = 12;
    public static String text(String value, int limit) {
        if (value == null) return "";
        String clean = DecisionSemanticText.redact(value);
        return clean.substring(0, Math.min(limit, clean.length()));
    }
    public static JSONObject screen(JSONObject source) {
        JSONObject out = obj("observed", source != null && source.optBoolean("ok"));
        if (source == null) return out;
        put(out, "app", text(source.optString("app"), 100));
        put(out, "app_label", text(source.optString("app_label"), 100));
        put(out, "snapshot_id", text(source.optString("snapshot_id"), 40));
        if (source.optJSONArray("screen") != null) put(out, "screen", source.optJSONArray("screen"));
        // A message composer on screen: a reply chip beside it sends with one tap.
        boolean composer = source.optBoolean("composer");
        JSONArray fields = source.optJSONArray("elements");
        for (int i = 0; fields != null && i < fields.length() && !composer; i++) {
            JSONObject e = fields.optJSONObject(i);
            composer = e != null && e.optString("flags").contains("edit") && !e.optString("flags").contains("password") && "chat".equals(elementFieldKind(e));
        }
        if (composer) put(out, "composer", true);
        put(out,"tree_quality",PhoneTreeQuality.assess(source));
        if(source.optJSONObject("action_effect")!=null)put(out,"last_action_effect",ActionEffect.evidence(source.optJSONObject("action_effect")));
        JSONArray items = arr(), all = source.optJSONArray("elements");
        if (all != null) for (int i = 0; i < all.length() && items.length() < 16; i++) {
            JSONObject e = all.optJSONObject(i); if (e == null) continue;
            String flags = e.optString("flags"), role = e.optString("role");
            if (flags.contains("password") || flags.contains("edit") || role.equals("input")) continue;
            // Action context includes controls only; completion can add separately selected relevant excerpts.
            if (!(flags.contains("click") || flags.contains("scroll") || role.equals("button") || role.equals("switch"))) continue;
            items.put(element(e));
        }
        put(out, "controls", items); put(out, "truncated", all != null && all.length() > items.length()); return out;
    }
    // Words in a goal or report that say nothing about what is on screen.
    private static final java.util.Set<String> GENERIC = java.util.Set.of("완료했어요", "확인했어요", "보여줘", "읽어줘", "알려줘", "please", "show", "read", "done", "the", "and",
        "보내", "보내줘", "보내기", "전송", "확인", "눌러", "열어", "해줘", "줘", "주세요", "해", "좀", "지금", "바로", "다시", "했어요", "열었어요", "보냈어요");
    /** Add only non-editable visible excerpts relevant to this goal/report, not unrelated conversations. */
    public static JSONObject completionScreen(JSONObject source, String relevance) {
        JSONObject out = screen(source); JSONArray matches = arr(), all = source == null ? null : source.optJSONArray("elements");
        String meaningful = text(relevance, 8000).toLowerCase(java.util.Locale.ROOT);
        java.util.Set<String> words = new java.util.LinkedHashSet<>();
        for (String word : meaningful.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && !GENERIC.contains(word)) words.add(word);
            // A name or noun with its particle removed ("동료한테" → 동료), when it is still a real word.
            String content = word.replaceFirst("(?:에서|에게|한테|으로|이랑|까지|부터|처럼|을|를|이|가|은|는|에|도|만|의|와|과|랑|로)$", "");
            if (!content.equals(word) && content.length() >= 2 && content.matches("[가-힣]+") && !GENERIC.contains(content)) words.add(content);
        }
        int budget = 1400;
        if (all != null) for (int i = 0; i < all.length() && matches.length() < 6 && budget > 0; i++) {
            JSONObject e = all.optJSONObject(i); if (e == null) continue;
            String flags = e.optString("flags");
            if (flags.contains("password") || flags.contains("edit") || "input".equals(e.optString("role"))) continue;
            String actual = text(e.optString("text", e.optString("desc")), 400);
            String lower = actual.toLowerCase(java.util.Locale.ROOT);
            boolean relevant = actual.length() >= 3 && meaningful.contains(lower);
            for (String word : words) relevant |= lower.contains(word);
            if (!relevant || actual.isBlank()) continue;
            String excerpt = actual.substring(0, Math.min(budget, actual.length())); budget -= excerpt.length(); matches.put(excerpt);
        }
        put(out, "visible_matches", matches); return out;
    }
    public static JSONObject completion(String goal, String answer, JSONObject source, int actions) {
        JSONObject current = completionScreen(source, goal + "\n" + answer);
        JSONObject result = obj("goal", text(goal, 4000), "proposed_answer", text(answer, 4000),
            "accepted_actions", actions, "current", current);
        // Keep the full goal/report, prioritize relevant observed text, and stay within a bounded size.
        for (String key : new String[]{"controls", "visible_matches"}) {
            JSONArray items = current.optJSONArray(key);
            while (items != null && items.length() > 0 && result.toString().length() > 11500) {
                items.remove(items.length() - 1); put(current, "truncated", true);
            }
        }
        return result;
    }
    public static JSONObject element(JSONObject e) {
        String flags = e.optString("flags");
        boolean secret = flags.contains("password"), editable = flags.contains("edit") || "input".equals(e.optString("role"));
        JSONObject out = obj("id", text(e.optString("id"), 16), "role", text(e.optString("role"), 24),
            "flags", text(flags, 100), "password", secret, "editable", editable);
        if (e.optJSONArray("bounds") != null) put(out, "bounds", e.optJSONArray("bounds"));
        if (!secret && !editable) put(out, "label", text(e.optString("desc", e.optString("text")), 60));
        return out;
    }
    private static final String COMMENT_FIELD = "(?s).*(댓글|답글|덧글|코멘트|comment).*";
    private static final String CHAT_FIELD = "(?s).*(메시지|메세지|채팅|대화|답장|문자|카톡|메일|message|chat|reply|compose|\\bdm\\b|\\bsms\\b|\\bmms\\b|send|보내).*";
    private static final String SEARCH_FIELD = "(?s).*(검색|search|찾기|\\bfind\\b|query).*";
    /** What a field is for, from its description and hint (never its typed text): search, chat, comment or other. */
    public static String fieldKind(String description) { return fieldKind(description, "", false); }
    /**
     * What a field is for, from its description and hint, its view id and whether it takes several lines (never its typed
     * text). A search view's own text field (SearchView's search_src_text, a "search" view id) is a search box. Otherwise a
     * comment or message word wins, so a field described both ways ("메시지 입력 검색") is no search box, and a multi-line field
     * is never one.
     */
    public static String fieldKind(String description, String viewId, boolean multiLine) {
        String id = viewId == null ? "" : viewId.strip().toLowerCase(java.util.Locale.ROOT);
        id = id.substring(id.indexOf('/') + 1);
        String d = ((description == null ? "" : description) + " " + id).toLowerCase(java.util.Locale.ROOT);
        if (!multiLine && id.contains("search") && !id.matches(COMMENT_FIELD) && !id.matches(CHAT_FIELD)) return "search";
        if (d.matches(COMMENT_FIELD)) return "comment";
        if (d.matches(CHAT_FIELD)) return "chat";
        if (!multiLine && d.matches(SEARCH_FIELD)) return "search";
        return "other";
    }
    /** The reading's own kind for an editable element (PhoneService adds it from the view id and line count), else from its
     * description and hint. */
    static String elementFieldKind(JSONObject element) {
        if (element == null) return "other";
        if (!element.optString("field_kind").isEmpty()) return element.optString("field_kind");
        return fieldKind(element.optString("desc") + " " + element.optString("hint"), "", element.optString("flags").contains("multiline"));
    }
    /** The control a tap actually reaches, as PhoneService observed it in the accessibility tree; bounded and redacted. */
    public static JSONObject receiverTarget(JSONObject receiver) {
        if (receiver == null) return null;
        if (receiver.optBoolean("missing")) return obj("missing", true);
        boolean password = receiver.optBoolean("password"), editable = receiver.optBoolean("editable");
        JSONObject out = obj("role", text(receiver.optString("role"), 24), "flags", text(receiver.optString("flags"), 100),
            "password", password, "editable", editable, "list_item", receiver.optBoolean("list_item"), "tab", receiver.optBoolean("tab"),
            "checkable", receiver.optBoolean("checkable"), "unlabeled", receiver.optBoolean("unlabeled"), "receiver", true);
        // A chip of a horizontally scrolling row, and whether it shows an image (a story or profile tile rather than a reply chip).
        if (receiver.optBoolean("horizontal")) put(out, "horizontal", true);
        if (receiver.optBoolean("image")) put(out, "image", true);
        // A system UI control: a notification row, a notification's own control or smart reply, a quick-settings tile, or other;
        // and the app that posted the notification, from its header.
        if (!receiver.optString("system_ui").isEmpty()) put(out, "system_ui", text(receiver.optString("system_ui"), 16));
        if (!receiver.optString("notification_app").isEmpty()) put(out, "notification_app", text(receiver.optString("notification_app"), 60));
        if (!receiver.optString("id").isEmpty()) put(out, "id", text(receiver.optString("id"), 16));
        if (receiver.optJSONArray("bounds") != null) put(out, "bounds", receiver.optJSONArray("bounds"));
        if (!password && !editable) {
            put(out, "label", text(receiver.optString("label"), RECEIVER_LABEL));
            JSONArray parts = receiver.optJSONArray("parts"), kept = arr();
            for (int i = 0; parts != null && i < parts.length() && kept.length() < RECEIVER_PARTS; i++) if (!parts.optString(i).isBlank()) kept.put(text(parts.optString(i), 80));
            put(out, "parts", kept);
        }
        if (editable) {
            put(out, "field_label", text(receiver.optString("field_label"), 120));
            put(out, "field_kind", receiver.optString("field_kind", fieldKind(receiver.optString("field_label"))));
        }
        if (receiver.optBoolean("unlabeled") || !receiver.optString("nearby").isEmpty()) put(out, "nearby", text(receiver.optString("nearby"), RECEIVER_LABEL));
        return out;
    }
    private static int[] box(JSONObject element) {
        JSONArray b = element == null ? null : element.optJSONArray("bounds");
        return b == null || b.length() < 4 ? null : new int[]{b.optInt(0), b.optInt(1), b.optInt(2), b.optInt(3)};
    }
    private static boolean inside(int[] inner, int[] outer) { return inner[0] >= outer[0] && inner[1] >= outer[1] && inner[2] <= outer[2] && inner[3] <= outer[3]; }
    private static long area(int[] b) { return (long) Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]); }
    /** The smallest observed element containing a screen point, the element a coordinate tap lands on; null when none. */
    public static JSONObject elementAt(JSONObject screen, double x, double y) {
        JSONArray all = screen == null ? null : screen.optJSONArray("elements");
        JSONObject best = null; long bestArea = Long.MAX_VALUE;
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i); int[] b = box(e);
            if (b == null || x < b[0] || x >= b[2] || y < b[1] || y >= b[3] || area(b) >= bestArea) continue;
            best = e; bestArea = area(b);
        }
        return best;
    }
    /** Visible, non-editable text near a box, for an unlabeled control the user must name. */
    public static String nearby(JSONObject screen, int[] around) {
        JSONArray all = screen == null ? null : screen.optJSONArray("elements");
        if (all == null || around == null) return "";
        int[] zone = {around[0] - 150, around[1] - 150, around[2] + 150, around[3] + 150};
        StringBuilder near = new StringBuilder(); int kept = 0;
        for (int i = 0; i < all.length() && kept < 6; i++) {
            JSONObject e = all.optJSONObject(i); int[] b = box(e);
            String flags = e == null ? "" : e.optString("flags");
            if (b == null || flags.contains("password") || flags.contains("edit") || "input".equals(e.optString("role"))) continue;
            if (b[2] < zone[0] || b[0] > zone[2] || b[3] < zone[1] || b[1] > zone[3]) continue;
            String said = (e.optString("text") + " " + e.optString("desc")).strip();
            if (said.isEmpty()) continue;
            if (near.length() > 0) near.append(" · ");
            near.append(said); kept++;
        }
        return near.toString();
    }
    /** From the flat reading alone (host checks, or no tree): the element a tap reaches and the texts inside it. PhoneService
     * passes the exact clickable ancestor and its descendants instead. */
    private static JSONObject approximate(JSONObject args, JSONObject screen, JSONObject raw) {
        String action = args.optString("action");
        if (!java.util.Set.of("tap", "long_press").contains(action)) return null;
        JSONObject hit = raw;
        if (hit == null && args.has("x") && args.has("y")) {
            hit = elementAt(screen, args.optDouble("x"), args.optDouble("y"));
            if (hit == null) {
                int x = (int) args.optDouble("x"), y = (int) args.optDouble("y");
                return obj("unlabeled", true, "nearby", text(nearby(screen, new int[]{x, y, x + 1, y + 1}), RECEIVER_LABEL), "receiver", true);
            }
        }
        if (hit == null) return null;
        JSONArray all = screen.optJSONArray("elements");
        String wanted = "long_press".equals(action) ? "long" : "click";
        JSONObject reached = hit; int[] hitBox = box(hit);
        if (!hit.optString("flags").contains(wanted) && hitBox != null) {
            long bestArea = Long.MAX_VALUE;
            for (int i = 0; all != null && i < all.length(); i++) {
                JSONObject e = all.optJSONObject(i); int[] b = box(e);
                if (b == null || e == hit || !e.optString("flags").contains(wanted) || !inside(hitBox, b) || area(b) >= bestArea) continue;
                reached = e; bestArea = area(b);
            }
        }
        int[] reachedBox = box(reached);
        JSONArray parts = arr(); StringBuilder label = new StringBuilder();
        boolean toggles = false;
        for (int i = 0; all != null && i < all.length() && parts.length() < RECEIVER_PARTS; i++) {
            JSONObject e = all.optJSONObject(i); int[] b = box(e);
            String flags = e == null ? "" : e.optString("flags");
            if (b != null && reachedBox != null && (e == reached || inside(b, reachedBox)) && java.util.Set.of("switch", "check").contains(e.optString("role"))) toggles = true;
            if (b == null || reachedBox == null || !(e == reached || inside(b, reachedBox)) || flags.contains("password") || flags.contains("edit") || "input".equals(e.optString("role"))) continue;
            String said = e.optString("desc", e.optString("text")).strip();
            if (said.isEmpty() || said.equals("[비밀번호]")) continue;
            parts.put(said); if (label.length() > 0) label.append(" · "); label.append(said);
        }
        String flags = reached.optString("flags");
        boolean editable = flags.contains("edit") || "input".equals(reached.optString("role"));
        JSONObject out = obj("id", reached.optString("id"), "role", reached.optString("role"), "flags", flags, "password", flags.contains("password"),
            "editable", editable, "label", label.toString(), "parts", parts, "unlabeled", label.length() == 0 && !editable, "checkable", toggles);
        if (reached.optJSONArray("bounds") != null) put(out, "bounds", reached.optJSONArray("bounds"));
        if (editable) { put(out, "field_label", reached.optString("desc")); put(out, "field_kind", elementFieldKind(reached)); }
        if (label.length() == 0) put(out, "nearby", nearby(screen, reachedBox));
        return receiverTarget(out);
    }
    public static JSONObject action(String goal, String tool, JSONObject args, JSONObject screen) { return action(goal, tool, args, screen, null); }
    /** receiver: what PhoneService found the step will actually reach (the clickable ancestor of a tapped element or point,
     * the field Enter goes to, the send button), or null to approximate it from the reading. */
    public static JSONObject action(String goal, String tool, JSONObject args, JSONObject screen, JSONObject receiver) {
        JSONObject out = obj("goal", text(goal, 4000), "tool", tool, "action", args.optString("action"), "current", screen(screen));
        String id = args.optString("element"); JSONArray all = screen == null ? null : screen.optJSONArray("elements");
        JSONObject target = null, rawTarget = null;
        if (all != null) for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i); if (e != null && id.equals(e.optString("id"))) { target = element(e); rawTarget = e; break; }
        }
        if (!id.isEmpty()) { put(out, "target", target == null ? obj("missing", true) : target); }
        // The control the step actually reaches, with every text it shows: the gate judges that, never only the tapped child.
        if (!(!id.isEmpty() && target == null) && java.util.Set.of("tap", "long_press", "press_enter", "send").contains(args.optString("action"))) {
            JSONObject reached = receiver != null ? receiverTarget(receiver) : approximate(args, screen, rawTarget);
            if (reached != null) put(out, "target", reached);
        }
        put(out, "requested_app", text(args.optString("app"), 100));
        // Only newly proposed, noncredential input to an observed editable target is routine.
        // Never keep the field's existing value; unknown/coordinate-only input stays withheld.
        if (args.has("text") && ("set_text".equals(args.optString("action")) || !args.optString("text").isEmpty())) {
            String proposed = args.optString("text");
            put(out, "input_characters", proposed.length());
            boolean sensitive = target != null && target.optBoolean("password")
                || rawTarget != null && DecisionSemanticText.sensitiveInput(rawTarget.optString("desc") + " " + rawTarget.optString("hint") + " " + rawTarget.optString("text"), proposed);
            boolean safe = target != null && target.optBoolean("editable") && !sensitive
                && proposed.length() <= 4000 && DecisionSemanticText.redact(proposed).equals(proposed);
            put(out, "input_withheld", !safe); put(out, "input_sensitive", sensitive);
            if (safe) put(out, "proposed_input", text(proposed, 4000));
        }
        // Enter in a search box runs a search; the kind comes from the field's own description, hint, view id and line count.
        if (target != null && target.optBoolean("editable") && !target.optBoolean("password") && rawTarget != null)
            put(out, "search_field", "search".equals(elementFieldKind(rawTarget)));
        if (args.has("url")) {
            put(out, "destination", args.optString("url").strip());
            put(out, "local_public_read_url", PhoneDecisionRisk.publicReadUrl(args.optString("url")));
            try { put(out, "destination_host", new java.net.URI(args.optString("url")).getHost()); }
            catch (Exception ignored) { put(out, "destination_host", "invalid"); }
        }
        for (String k : new String[]{"x", "y", "end_x", "end_y", "seconds"}) if (args.has(k)) put(out, k, args.opt(k));
        put(out, "snapshot_matches", id.isEmpty() || screen != null && args.optString("snapshot_id").equals(screen.optString("snapshot_id")));
        return out;
    }
}
