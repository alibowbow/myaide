package dev.jun.gyeot.core;

import java.util.*;
import org.json.*;
import static dev.jun.gyeot.core.Jsons.*;

/** Durable display history, scoped independently from each tab's AI task memory. */
public final class BrowserConversation {
    public static final String LEGACY_SCOPE = "__legacy__";
    private static final String DEFAULT_SCOPE = "__compat__";
    private final LinkedHashMap<String, JSONObject> groups = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> rows = new LinkedHashMap<>();
    private long version, snapshotVersion = -1;
    private String cachedSnapshot;
    private boolean restored;
    public long version() { return version; }
    public int size() { return rows.size(); }
    public boolean isEmpty() { return rows.isEmpty(); }
    public boolean has(String scope) { return scope != null && groups.containsKey(scope); }
    public boolean isArchived(String scope) { JSONObject group = groups.get(scope); return group != null && group.optBoolean("archived"); }
    /** Preserve orphaned display groups in the archive rather than leaving them inaccessible. */
    public void archiveDetached(Set<String> activeScopes) {
        Objects.requireNonNull(activeScopes, "Active conversation scopes required");
        for (JSONObject group : groups.values()) { String scope = group.optString("id"); if (!activeScopes.contains(scope)) archive(scope); }
    }
    public int size(String scope) {
        int count = 0;
        for (JSONObject row : rows.values()) if (row.optString("scope").equals(scope)) count++;
        return count;
    }
    public void ensure(String scope, String title) {
        if (scope == null || scope.isBlank() || LEGACY_SCOPE.equals(scope)) throw new IllegalArgumentException("Invalid conversation scope");
        String label = title == null || title.isBlank() ? "대화" : title;
        JSONObject group = groups.get(scope);
        if (group == null) { groups.put(scope, obj("id", scope, "title", label, "archived", false, "legacy", false)); version++; }
        else if (!group.optString("title").equals(label)) { put(group, "title", label); version++; }
    }
    public String request(String scope, String visible) {
        if (visible == null) throw new IllegalArgumentException("A visible request is required");
        if (!has(scope)) ensure(scope, "대화");
        JSONObject group = groups.get(scope);
        if (group.optBoolean("archived") || group.optBoolean("legacy")) throw new IllegalStateException("Archived conversation cannot accept a new request");
        String id = UUID.randomUUID().toString();
        rows.put(id + ":user", obj("id", id + ":user", "scope", scope, "role", "user", "text", visible)); version++;
        return id;
    }
    /** Compatibility for non-panel callers. The browser UI always supplies an explicit scope. */
    public String request(String visible) { return request(DEFAULT_SCOPE, visible); }
    public boolean answer(String id, String text, String state) {
        JSONObject question = id == null ? null : rows.get(id + ":user");
        if (question == null || !question.optString("role").equals("user") || rows.containsKey(id + ":assistant")) return false;
        String scope = question.optString("scope");
        if (!has(scope)) return false;
        // Completion belongs to the accepted request, even after its tab is closed/archived.
        rows.put(id + ":assistant", obj("id", id + ":assistant", "scope", scope, "role", "assistant", "text", text == null ? "" : text, "state", state == null ? "error" : state));
        version++; return true;
    }
    public void archive(String scope) {
        JSONObject group = groups.get(scope);
        if (group != null && !group.optBoolean("archived")) { put(group, "archived", true); version++; }
    }
    /** Destructive UI actions must wait until the complete saved record was read. */
    public boolean delete(String scope) {
        if (!restored || !has(scope)) return false;
        groups.remove(scope); rows.values().removeIf(row -> row.optString("scope").equals(scope)); version++; return true;
    }
    /** Compatibility boundary only; active panel conversations rotate their explicit scope instead. */
    public void fresh() {
        if (!has(DEFAULT_SCOPE)) ensure(DEFAULT_SCOPE, "대화");
        String id = UUID.randomUUID().toString();
        rows.put(id, obj("id", id, "scope", DEFAULT_SCOPE, "role", "boundary", "text", "새 대화 · 현재 탭의 AI 작업 기억을 새로 시작해요")); version++;
    }
    public JSONArray archives() {
        JSONArray out = new JSONArray(); List<JSONObject> ordered = new ArrayList<>(groups.values());
        Map<String, Integer> counts = new HashMap<>(); Map<String, String> previews = new HashMap<>();
        for (JSONObject row : rows.values()) {
            String scope = row.optString("scope"); counts.put(scope, counts.getOrDefault(scope, 0) + 1);
            if (row.optString("role").equals("user")) previews.putIfAbsent(scope, row.optString("text"));
        }
        Collections.reverse(ordered);
        for (JSONObject group : ordered) {
            String id = group.optString("id"); int count = counts.getOrDefault(id, 0);
            if (group.optBoolean("archived") && count > 0) {
                String preview = previews.getOrDefault(id, "");
                if (preview.length() > 80) {
                    int end = 80;
                    if (Character.isHighSurrogate(preview.charAt(end - 1)) && Character.isLowSurrogate(preview.charAt(end))) end--;
                    preview = preview.substring(0, end);
                }
                out.put(obj("id", id, "title", group.optString("title"), "count", count, "legacy", group.optBoolean("legacy"), "preview", preview));
            }
        }
        return out;
    }
    /** Validate everything before mutation; unidentified legacy rows are always a separate archive. */
    public boolean restore(String saved, String legacyResult) {
        if (restored) return true;
        if (saved == null) return false;
        LinkedHashMap<String, JSONObject> olderGroups = new LinkedHashMap<>(), olderRows = new LinkedHashMap<>();
        try {
            String text = saved.strip(); boolean scoped = text.startsWith("{");
            JSONArray values;
            if (scoped) {
                JSONObject value = new JSONObject(text);
                if (!Integer.valueOf(2).equals(value.opt("version"))) return false;
                JSONArray groupValues = value.getJSONArray("groups"); values = value.getJSONArray("rows");
                for (int i = 0; i < groupValues.length(); i++) {
                    JSONObject group = groupValues.getJSONObject(i);
                    if (!string(group, "id", false) || !string(group, "title", true) || !(group.opt("archived") instanceof Boolean)
                            || !(group.opt("legacy") instanceof Boolean) || olderGroups.containsKey(group.getString("id"))) return false;
                    boolean legacy = group.getBoolean("legacy");
                    if (legacy != group.getString("id").equals(LEGACY_SCOPE) || legacy && !group.getBoolean("archived")) return false;
                    olderGroups.put(group.getString("id"), copy(group));
                }
            } else {
                values = text.isBlank() ? new JSONArray() : new JSONArray(text);
                if (values.length() != 0) olderGroups.put(LEGACY_SCOPE, obj("id", LEGACY_SCOPE, "title", "이전 버전 기록", "archived", true, "legacy", true));
            }
            for (int i = 0; i < values.length(); i++) {
                JSONObject row = values.getJSONObject(i);
                if (!string(row, "id", false) || !string(row, "text", true) || !string(row, "role", false)
                        || !Set.of("user", "assistant", "boundary").contains(row.getString("role")) || olderRows.containsKey(row.getString("id"))
                        || row.has("state") && !(row.opt("state") instanceof String)) return false;
                JSONObject detached = copy(row);
                if (scoped) {
                    if (!string(row, "scope", false) || !olderGroups.containsKey(row.getString("scope"))) return false;
                } else put(detached, "scope", LEGACY_SCOPE);
                olderRows.put(detached.getString("id"), detached);
            }
            if (!scoped && legacyResult != null && !legacyResult.isBlank()) {
                boolean retained = false;
                for (JSONObject row : olderRows.values()) if (row.optString("role").equals("assistant") && row.optString("text").equals(legacyResult)) { retained = true; break; }
                // Older releases wrote transcript and lastResult independently. Preserve a distinct
                // result after a partial write failure without inventing its owner or user request.
                if (!retained) {
                    olderGroups.put(LEGACY_SCOPE, obj("id", LEGACY_SCOPE, "title", "이전 버전 기록", "archived", true, "legacy", true));
                    String id = "legacy:last-result";
                    while (olderRows.containsKey(id)) id = "legacy:last-result:" + UUID.randomUUID();
                    olderRows.put(id, obj("id", id, "scope", LEGACY_SCOPE, "role", "assistant", "text", legacyResult,
                            "label", "소속미확인 마지막 결과", "source", "legacy.lastResult"));
                }
            }
            // A colliding immutable row may be identical (idempotent merge), never silently replaced.
            for (Map.Entry<String, JSONObject> live : rows.entrySet()) {
                JSONObject older = olderRows.get(live.getKey());
                if (older != null && !same(older, live.getValue())) return false;
            }
            olderGroups.putAll(groups); olderRows.putAll(rows);
            for (JSONObject row : olderRows.values()) if (!olderGroups.containsKey(row.getString("scope"))) return false;
        } catch (Exception malformed) { return false; }
        groups.clear(); groups.putAll(olderGroups); rows.clear(); rows.putAll(olderRows);
        restored = true; version++; return true;
    }
    private static boolean string(JSONObject value, String key, boolean blank) {
        return value.opt(key) instanceof String && (blank || !value.optString(key).isBlank());
    }
    private static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); } catch (Exception malformed) { throw new IllegalStateException(malformed); }
    }
    /** Android org.json does not provide JSONObject.similar; compare without depending on key order. */
    private static boolean same(Object a, Object b) {
        if (a == b) return true;
        if (a instanceof JSONObject left && b instanceof JSONObject right) {
            if (left.length() != right.length()) return false;
            JSONArray names = left.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                if (!right.has(key) || !same(left.opt(key), right.opt(key))) return false;
            }
            return true;
        }
        if (a instanceof JSONArray left && b instanceof JSONArray right) {
            if (left.length() != right.length()) return false;
            for (int i = 0; i < left.length(); i++) if (!same(left.opt(i), right.opt(i))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }
    public String snapshot() {
        if (cachedSnapshot == null || snapshotVersion != version) {
            cachedSnapshot = obj("version", 2, "groups", new JSONArray(groups.values()), "rows", new JSONArray(rows.values())).toString();
            snapshotVersion = version;
        }
        return cachedSnapshot;
    }
    /** Bounded detached view; pagination never removes retained source messages. */
    public JSONArray window(String scope, int limit) { return window(scope, limit, false); }
    public JSONArray window(int limit) { return window(null, limit, true); }
    private JSONArray window(String scope, int limit, boolean all) {
        JSONArray out = new JSONArray(); int from = Math.max(0, (all ? rows.size() : size(scope)) - Math.max(1, limit)), index = 0;
        for (JSONObject row : rows.values()) if (all || row.optString("scope").equals(scope)) {
            if (index++ >= from) out.put(copy(row));
        }
        return out;
    }
    public String lastAnswer(String scope) { return lastAnswer(scope, false); }
    public String lastAnswer() { return lastAnswer(null, true); }
    private String lastAnswer(String scope, boolean all) {
        String answer = "";
        for (JSONObject row : rows.values()) if (row.optString("role").equals("assistant") && (all || row.optString("scope").equals(scope))) answer = row.optString("text");
        return answer;
    }
}
