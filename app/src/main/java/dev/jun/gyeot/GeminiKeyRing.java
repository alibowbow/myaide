package dev.jun.gyeot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The user's Gemini API keys, as many as they keep, each with a name, its tier (무료 · 유료, chosen by the user and marked
 * 무료 by itself when Google answers a request with its free-tier limit) and the roles it fills: 음성 비서 (SecretStore.GEMINI)
 * and 작업창·메인 대화 (SecretStore.GEMINI_WORK; empty means the voice key). The ring is kept encrypted with the keys; the roles
 * stay in their own slots, so everything that reads a key reads it as before.
 */
final class GeminiKeyRing {
    static final String RING = "geminiRing";
    static final String FREE = "free", PAID = "paid";
    record Key(String id, String name, String value, String tier, long added, String note) {
        JSONObject json() throws Exception { return new JSONObject().put("id", id).put("name", name).put("value", value).put("tier", tier).put("added", added).put("note", note); }
        static Key of(JSONObject o) { return new Key(o.optString("id"), o.optString("name"), o.optString("value"), o.optString("tier"), o.optLong("added"), o.optString("note")); }
        Key named(String n) { return new Key(id, n, value, tier, added, note); }
        Key tiered(String t, String why) { return new Key(id, name, value, t, added, why); }
    }
    private final SecretStore secrets;
    GeminiKeyRing(SecretStore secrets) { this.secrets = secrets; }

    /** The keys, oldest first; the keys already in the role slots join the ring the first time it is read. */
    synchronized List<Key> keys() throws Exception {
        List<Key> out = new ArrayList<>();
        String saved = secrets.get(RING);
        if (!saved.isBlank()) { JSONArray list = new JSONArray(saved); for (int i = 0; i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null && !o.optString("value").isBlank()) out.add(Key.of(o)); } }
        boolean changed = false;
        String voice = secrets.get(SecretStore.GEMINI), work = secrets.get(SecretStore.GEMINI_WORK);
        if (!voice.isBlank() && find(out, voice) == null) { out.add(new Key(id(), out.isEmpty() ? "기본 키" : "음성 비서 키", voice, "", System.currentTimeMillis(), "")); changed = true; }
        if (!work.isBlank() && find(out, work) == null) { out.add(new Key(id(), "작업창 키", work, "", System.currentTimeMillis(), "")); changed = true; }
        if (changed) keep(out);
        return out;
    }
    private static String id() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }
    static Key find(List<Key> keys, String value) { for (Key k : keys) if (k.value().equals(value)) return k; return null; }
    private void keep(List<Key> keys) throws Exception { JSONArray list = new JSONArray(); for (Key k : keys) list.put(k.json()); secrets.put(RING, keys.isEmpty() ? "" : list.toString()); }

    /** A new key with its name and tier; the first one also becomes 음성 비서's. Returns it. */
    synchronized Key add(String value, String name, String tier) throws Exception {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.contains(" ") || v.contains("\n") || v.length() < 16) throw new IllegalArgumentException("API 키 형식을 확인해 주세요.");
        List<Key> all = keys();
        if (find(all, v) != null) throw new IllegalArgumentException("이미 등록한 키예요.");
        Key made = new Key(id(), name == null || name.isBlank() ? "키 " + (all.size() + 1) : name.strip(), v, PAID.equals(tier) ? PAID : FREE.equals(tier) ? FREE : "", System.currentTimeMillis(), "");
        all.add(made); keep(all);
        if (!secrets.hasKey()) secrets.put(v);
        return made;
    }
    synchronized void change(Key key) throws Exception {
        List<Key> all = keys();
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(key.id())) all.set(i, key);
        keep(all);
    }
    /** Forgets a key; a role it filled is emptied (작업창 then uses the voice key; without a voice key the voice assistant is off). */
    synchronized void remove(Key key) throws Exception {
        List<Key> all = keys(); all.removeIf(k -> k.id().equals(key.id())); keep(all);
        if (key.value().equals(secrets.get(SecretStore.GEMINI_WORK))) secrets.putWork("");
        if (key.value().equals(secrets.get(SecretStore.GEMINI))) secrets.put("");
    }
    /** What a key does now: "음성 비서", "작업창·메인 대화", both, or "" (kept, not in use). */
    String roles(Key key) {
        try {
            String voice = secrets.get(SecretStore.GEMINI), work = secrets.get(SecretStore.GEMINI_WORK);
            boolean v = key.value().equals(voice), w = key.value().equals(work) || work.isBlank() && v;
            return v && w ? "음성 비서 · 작업창·메인 대화" : v ? "음성 비서" : w ? "작업창·메인 대화" : "";
        } catch (Exception unreadable) { return ""; }
    }
    /** A request answered with the free tier's limit: the key it went with is free, whoever marked it otherwise. */
    void sawFreeTier(String value) {
        if (value == null || value.isBlank()) return;
        try {
            List<Key> all = keys(); Key k = find(all, value);
            if (k != null && !FREE.equals(k.tier())) change(k.tiered(FREE, "무료 한도 응답을 받아 무료로 표시했어요"));
        } catch (Exception ignored) { /* Only a label. */ }
    }

    /** The key with only its start and end showing: "AIzaSy…X7kQ". */
    static String masked(String value) {
        String v = value == null ? "" : value.strip();
        return v.length() <= 12 ? "••••" : v.substring(0, 6) + "…" + v.substring(v.length() - 4);
    }
    static String tierLabel(String tier) { return FREE.equals(tier) ? "무료" : PAID.equals(tier) ? "유료" : "등급 미정"; }
    /** Google's answer names the free tier's quota when a free key runs out ("…generate_content_free_tier_requests"). */
    static boolean freeTierAnswer(int status, String body) { return status == 429 && body != null && body.contains("free_tier"); }
}
