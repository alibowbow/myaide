package dev.jun.gyeot;

import dev.jun.gyeot.core.*;
import org.json.*;
import static dev.jun.gyeot.core.Jsons.*;

/** Durable selections owned by a Work room or the recorder, never live account defaults. */
final class ScopedModels {
    static final String KEY = "modelSelection", DEFAULTS = "isolatedModelDefaults", DRAFT = "workDraftModels", RECORDING = "recordingModels";
    private final AppStore store;
    private final String room, setting;
    private JSONObject frozen;
    static ScopedModels frozen(AppStore store, JSONObject selection) { ScopedModels result = new ScopedModels(store, ""); result.frozen = copy(selection); return result; }
    ScopedModels(AppStore store, String room) { this(store, room == null ? "" : room, DRAFT); }
    private ScopedModels(AppStore store, String room, String setting) { this.store = store; this.room = room; this.setting = setting; }
    static ScopedModels main(AppStore store) {
        synchronized(store) {
            if(!store.settings.contains("mainChatModels")) {
                JSONObject value=copy(saved(store,DEFAULTS));
                put(value,"team",obj("enabled",false,"fallback",false,"automatic",false,"parallel",1,"profiles",arr(),"excluded",arr()));
                put(value,"speeds",obj());
                if(!store.settings.edit().putString("mainChatModels",value.toString()).commit())throw new IllegalStateException("메인 모델 설정을 저장하지 못했어요.");
            }
        }
        return new ScopedModels(store,"","mainChatModels");
    }
    /** Explorer-only choices, initially copied from account defaults, then independent. */
    static ScopedModels explorer(AppStore store) {
        synchronized(store) {
            if(!store.settings.contains("fileExplorerModels")) {
                JSONObject value=initial(store);
                put(value,"engine",store.settings.getString("fileAiProvider",store.analystChoice()));
                put(value,"speeds",obj());
                if(!store.settings.edit().putString("fileExplorerModels",value.toString()).commit()) throw new IllegalStateException("탐색 모델 설정을 저장하지 못했어요.");
            }
        }
        return new ScopedModels(store,"","fileExplorerModels");
    }
    void chooseProfile(String engine,String model,String effort) {
        if(!TeamPlan.ENGINES.contains(engine))throw new IllegalArgumentException("AI를 선택해 주세요.");
        if(model==null||model.isBlank())throw new IllegalArgumentException("모델을 선택해 주세요.");
        new Reasoning.Selections("{}").set(model,effort,new TeamRoster(store).profile(engine,model));
        change(s->{put(s,"engine",engine);put(s.optJSONObject("models"),engine,model);put(s.optJSONObject("efforts"),engine+":"+model,effort);});
    }
    static ScopedModels recording(AppStore store) { return new ScopedModels(store, "", RECORDING); }
    /** 판서's own choice of the model that prepares its lessons: copied from the main chat's the first time, independent after. */
    static ScopedModels board(AppStore store) {
        synchronized (store) {
            if (!store.settings.contains("boardModels")) {
                JSONObject value = main(store).snapshot();
                if (!store.settings.edit().putString("boardModels", value.toString()).commit()) throw new IllegalStateException("판서 모델 설정을 저장하지 못했어요.");
            }
        }
        return new ScopedModels(store, "", "boardModels");
    }
    /** 마이튜브 AI's own choice: copied from the main chat's the first time, independent after. */
    static ScopedModels mytube(AppStore store) {
        synchronized (store) {
            if (!store.settings.contains("mytubeModels")) {
                JSONObject value = main(store).snapshot();
                if (!store.settings.edit().putString("mytubeModels", value.toString()).commit()) throw new IllegalStateException("마이튜브 모델 설정을 저장하지 못했어요.");
            }
        }
        return new ScopedModels(store, "", "mytubeModels");
    }
    /** 이 영상에 대해 물어보세요's own choice: copied from 마이튜브 AI's the first time, independent after. */
    static ScopedModels videoChat(AppStore store) {
        synchronized (store) {
            if (!store.settings.contains("videoChatModels")) {
                JSONObject value = mytube(store).snapshot();
                if (!store.settings.edit().putString("videoChatModels", value.toString()).commit()) throw new IllegalStateException("영상 대화 모델 설정을 저장하지 못했어요.");
            }
        }
        return new ScopedModels(store, "", "videoChatModels");
    }
    /** 나의학교: one teacher's own choice of the app's AIs, copied from the main chat's the first time, independent after. */
    static ScopedModels tutor(AppStore store, String teacher) {
        String key = "tutorModels:" + teacher;
        synchronized (store) {
            if (!store.settings.contains(key)) {
                JSONObject value = main(store).snapshot();
                if (!store.settings.edit().putString(key, value.toString()).commit()) throw new IllegalStateException("선생님 모델 설정을 저장하지 못했어요.");
            }
        }
        return new ScopedModels(store, "", key);
    }
    /**
     * A 연구실 researcher's own model ("labModels:<project>:<role>"). A new one starts from the main chat's choice with the
     * engine its role suits best among those connected, so the team mixes companies (같은 회사 모델은 같은 실수를 하기 쉬워서).
     */
    static ScopedModels lab(AppStore store, String project, String role) {
        String key = "labModels:" + project + ":" + role;
        synchronized (store) {
            if (!store.settings.contains(key)) {
                JSONObject value = main(store).snapshot();
                for (String engine : labPreference(role)) if (store.ready(engine)) { try { value.put("engine", engine); } catch (Exception ignored) {} break; }
                value.remove("team");
                if (!store.settings.edit().putString(key, value.toString()).commit()) throw new IllegalStateException("연구원 모델 설정을 저장하지 못했어요.");
            }
        }
        return new ScopedModels(store, "", key);
    }
    static java.util.List<String> labPreference(String role) {
        return switch (role == null ? "" : role) {
            case "pi" -> java.util.List.of(WorkBook.CLAUDE, WorkBook.CHATGPT, WorkBook.GEMINI, WorkBook.ANTIGRAVITY, WorkBook.GROK, WorkBook.NIM, WorkBook.GROQ);
            case "scout" -> java.util.List.of(WorkBook.GEMINI, WorkBook.GROK, WorkBook.CHATGPT, WorkBook.ANTIGRAVITY, WorkBook.CLAUDE, WorkBook.GROQ, WorkBook.NIM);
            case "theorist" -> java.util.List.of(WorkBook.CHATGPT, WorkBook.ANTIGRAVITY, WorkBook.CLAUDE, WorkBook.GEMINI, WorkBook.GROK, WorkBook.NIM, WorkBook.GROQ);
            case "experimenter" -> java.util.List.of(WorkBook.CLAUDE, WorkBook.CHATGPT, WorkBook.ANTIGRAVITY, WorkBook.GEMINI, WorkBook.GROK, WorkBook.NIM, WorkBook.GROQ);
            case "critic" -> java.util.List.of(WorkBook.GROK, WorkBook.GEMINI, WorkBook.CHATGPT, WorkBook.CLAUDE, WorkBook.ANTIGRAVITY, WorkBook.NIM, WorkBook.GROQ);
            default -> java.util.List.of(WorkBook.GROQ, WorkBook.GEMINI, WorkBook.NIM, WorkBook.CHATGPT, WorkBook.CLAUDE, WorkBook.GROK, WorkBook.ANTIGRAVITY);
        };
    }
    /** A project's researchers' models are forgotten with it. */
    static void forgetLab(AppStore store, String project) {
        android.content.SharedPreferences.Editor edit = store.settings.edit();
        for (dev.jun.gyeot.core.LabBook.Role r : dev.jun.gyeot.core.LabBook.ROLES) edit.remove("labModels:" + project + ":" + r.id());
        edit.apply();
    }
    static JSONObject copy(JSONObject value) { try { return new JSONObject(value.toString()); } catch (Exception e) { throw new IllegalStateException(e); } }
    static JSONObject saved(AppStore store, String key) {
        try { return new JSONObject(store.settings.getString(key, "{}")); } catch (Exception e) { throw new IllegalStateException("모델 설정을 읽지 못했어요. 기존 설정은 보존했어요.", e); }
    }
    static JSONObject initial(AppStore store) {
        JSONObject result = obj("engine", store.analystChoice(), "models", obj(), "efforts", obj(), "team", obj("enabled", store.teamEnabled(), "fallback", store.teamFallback(), "automatic", store.teamAutomatic(), "parallel", store.teamParallel(), "profiles", store.teamProfiles(), "excluded", new JSONArray(new TeamRoster(store).excluded())));
        TeamRoster roster = new TeamRoster(store);
        for (String engine : TeamPlan.ENGINES) {
            String model = roster.selected(engine); put(result.optJSONObject("models"), engine, model);
            JSONArray catalog = roster.catalog(engine);
            for (int i = 0; i <= catalog.length(); i++) {
                String id = i == catalog.length() ? model : catalog.optJSONObject(i).optString("id");
                if (!id.isBlank()) put(result.optJSONObject("efforts"), engine + ":" + id, rawEffort(store, engine, id));
            }
        }
        // Preserve remembered depths even for custom models not currently in the server catalog.
        for (String[] source : new String[][]{{WorkBook.CHATGPT, "openaiReasoning"}, {WorkBook.CLAUDE, "claudeEffort"}, {WorkBook.GEMINI, "geminiWorkEffort"}}) {
            JSONObject saved = saved(store, source[1]); java.util.Iterator<String> keys = saved.keys();
            while (keys.hasNext()) { String model = keys.next(); put(result.optJSONObject("efforts"), source[0] + ":" + model, saved.optString(model)); }
        }
        return result;
    }
    private static String rawEffort(AppStore s, String e, String m) {
        return switch (e) { case WorkBook.CLAUDE -> s.claude.effort(m); case WorkBook.GEMINI -> s.gemini.effort(m);
            case WorkBook.GROK -> s.grok.effort(m); case WorkBook.NIM -> s.nim.effort(m); case WorkBook.ANTIGRAVITY -> s.antigravity.effort(m); case WorkBook.GROQ -> s.groqModels.effort(m); default -> s.openai.reasoningEffort(m); };
    }
    /** One-time snapshot, not a reconstruction of historical per-room choices which were never stored. */
    static void initialize(AppStore store) {
        if (!store.settings.contains(DEFAULTS)) {
            String defaults = initial(store).toString();
            boolean hadDraft = store.settings.contains(DRAFT), hadRecording = store.settings.contains(RECORDING);
            android.content.SharedPreferences.Editor edit = store.settings.edit().putString(DEFAULTS, defaults);
            if (!hadDraft) edit.putString(DRAFT, defaults);
            if (!hadRecording) edit.putString(RECORDING, defaults);
            if (!edit.commit()) {
                // SharedPreferences updates memory even when disk commit fails. Do not leave a false migration marker.
                android.content.SharedPreferences.Editor rollback = store.settings.edit().remove(DEFAULTS);
                if (!hadDraft) rollback.remove(DRAFT);
                if (!hadRecording) rollback.remove(RECORDING);
                rollback.commit();
                throw new IllegalStateException("모델 설정을 저장하지 못했어요.");
            }
        }
    }
    static boolean migrate(JSONArray threads, JSONObject defaults) {
        boolean changed = false;
        for (int i = 0; i < threads.length(); i++) {
            JSONObject thread = threads.optJSONObject(i); if (thread == null) continue;
            if (!thread.has(KEY)) { put(thread, KEY, copy(defaults)); changed = true; }
            // Legacy queued messages have an engine but no model; freeze the migration-time selection once.
            JSONArray queue = thread.optJSONArray("pendingInstructions");
            for (int q = 0; queue != null && q < queue.length(); q++) {
                JSONObject item = queue.optJSONObject(q); if (item == null) continue;
                if (!item.has(KEY)) { put(item, KEY, copy(thread.optJSONObject(KEY))); changed = true; }
                if (item.has("model")) continue;
                String engine = item.optString("engine"), model = thread.optJSONObject(KEY).optJSONObject("models").optString(engine);
                put(item, "model", model); put(item, "effort", thread.optJSONObject(KEY).optJSONObject("efforts").optString(engine + ":" + model)); changed = true;
            }
        }
        return changed;
    }
    JSONObject snapshot() {
        if (store.scopedModelsUnavailable) throw new IllegalStateException("모델 설정을 저장하지 못했어요. 저장 공간을 확인한 뒤 앱을 다시 열어 주세요.");
        synchronized (store) {
            if (frozen != null) return copy(frozen);
            if (room.isEmpty()) {
                JSONObject selection = saved(store, setting);
                // A new 작업창 starts in 기본 저장소 as it is when the first message goes: the room keeps it from then on.
                if (DRAFT.equals(setting) && !selection.has(REPO)) put(selection, REPO, store.githubRepo());
                return selection;
            }
            // Only this room's selection is copied (AppStore.workSelection): the composer reads it on every redraw.
            JSONObject selection = store.workSelection(room);
            if (selection == null) throw new IllegalStateException("이 작업의 모델 설정을 찾지 못했어요.");
            return selection;
        }
    }
    private void change(java.util.function.Consumer<JSONObject> update) {
        if (frozen != null) throw new IllegalStateException("전송한 모델 설정은 바꿀 수 없어요.");
        synchronized (store) {
            if (room.isEmpty()) {
                // The stored selection, not snapshot(): the draft's repository is 기본 저장소 as it is, never written into the draft.
                if (store.scopedModelsUnavailable) throw new IllegalStateException("모델 설정을 저장하지 못했어요. 저장 공간을 확인한 뒤 앱을 다시 열어 주세요.");
                JSONObject selected = saved(store, setting); update.accept(selected);
                if (!store.settings.edit().putString(setting, selected.toString()).commit()) throw new IllegalStateException("모델 설정을 저장하지 못했어요.");
                store.changed();
            } else try {
                store.changeWorks(list -> { JSONObject thread = WorkBook.find(list, room); if (thread == null) throw new IllegalStateException("이 작업을 찾지 못했어요.");
                    JSONObject selected = thread.optJSONObject(KEY); if (selected == null) throw new IllegalStateException("모델 설정을 찾지 못했어요."); update.accept(selected); return null; });
            } catch (Exception e) { throw new IllegalStateException("모델 설정을 저장하지 못했어요.", e); }
        }
    }
    /** The GitHub repository a 작업창 room works in ("owner/name", "" for none), beside its model; see AppStore.githubRepo(room). */
    static final String REPO = "repo";
    String repo() { if (store.scopedModelsUnavailable) return store.githubRepo(); JSONObject s = snapshot(); return s.has(REPO) ? s.optString(REPO) : store.githubRepo(); }
    /**
     * Chooses the repository: a room's own from its next message on, or for a 작업창 not sent yet (the draft) the one its first
     * message takes into the new room. Never 기본 저장소 itself: that is part of the work connection's identity
     * (AppStore.workConnectionFingerprint), which a tap on the composer must not change.
     */
    void repo(String value) {
        String repo = GitHubTools.repo(value);
        if (value != null && !value.isBlank() && repo.isEmpty()) throw new IllegalArgumentException("owner/저장소 형식으로 골라 주세요.");
        change(s -> put(s, REPO, repo));
    }
    /** 기본 저장소 changed in 설정: a 작업창 not sent yet follows it again instead of an earlier pick. */
    static void draftFollowsDefault(AppStore store) {
        synchronized (store) {
            JSONObject draft = saved(store, DRAFT);
            if (draft.remove(REPO) != null && !store.settings.edit().putString(DRAFT, draft.toString()).commit()) throw new IllegalStateException("모델 설정을 저장하지 못했어요.");
        }
    }
    String engine() { if (store.scopedModelsUnavailable) return ""; return snapshot().optString("engine"); }
    String readyEngine() { String e = engine(); return store.ready(e) ? e : ""; }
    /** This scope's saved model for engine, a replaced one read as its successor (core/ModelIds). */
    /** The model chosen here for engine; one this selection has none for (an AI added after it was made) is the AI's own default. */
    String model(String engine) {
        if (store.scopedModelsUnavailable) return "";
        JSONObject m = snapshot().optJSONObject("models"); String saved = m == null ? "" : ModelIds.current(engine, m.optString(engine));
        if (!saved.isBlank() || engine == null || engine.isBlank()) return saved;
        try { return ModelIds.current(engine, new TeamRoster(store).selected(engine)); } catch (Exception unavailable) { return ""; }
    }
    String effort(String engine, String model) { if (store.scopedModelsUnavailable) return ""; JSONObject e = snapshot().optJSONObject("efforts"); return new TeamRoster(store).profile(engine, model).normalize(e == null ? "" : e.optString(engine + ":" + model)); }
    String speed(String model) {
        JSONObject saved = snapshot(); JSONObject speeds = saved.optJSONObject("speeds");
        String value = GptSpeed.normalize(speeds == null ? "standard" : speeds.optString(model, "standard"));
        if ("fast".equals(value) && !store.openai.speedEpoch().equals(saved.optString("speedConsentEpoch"))) {
            if (frozen != null) throw new IllegalStateException("GPT 연결이 바뀌어 저장된 Fast 요청을 멈췄어요. 속도를 다시 선택해 주세요.");
            return "standard";
        }
        return value;
    }
    void speed(String model, String value) {
        String speed = GptSpeed.normalize(value);
        if (model == null || model.isBlank() || ("fast".equals(speed) && !GptSpeed.supported(store.openai.describe().optString("mode"), model, store.openai.models())))
            throw new IllegalArgumentException("이 연결과 모델에서 Fast 지원을 확인하지 못했어요.");
        change(s -> { JSONObject speeds = s.optJSONObject("speeds");
            if (speeds == null || !store.openai.speedEpoch().equals(s.optString("speedConsentEpoch"))) { speeds = obj(); put(s, "speeds", speeds); }
            put(s, "speedConsentEpoch", store.openai.speedEpoch()); put(speeds, model, speed); });
    }
    String effectiveEffort(String engine, String model) { return new TeamRoster(store).profile(engine, model).resolve(effort(engine, model)); }
    void chooseEngine(String engine) { if (!TeamPlan.ENGINES.contains(engine)) throw new IllegalArgumentException("AI를 선택해 주세요."); change(s -> put(s, "engine", engine)); }
    void model(String engine, String model) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("모델을 선택해 주세요.");
        change(s -> put(s.optJSONObject("models"), engine, model));
    }
    void effort(String engine, String model, String effort) {
        Reasoning.Profile profile = new TeamRoster(store).profile(engine, model);
        Reasoning.Selections validation = new Reasoning.Selections("{}"); validation.set(model, effort, profile);
        change(s -> put(s.optJSONObject("efforts"), engine + ":" + model, effort));
    }
    private JSONObject team() { if (store.scopedModelsUnavailable) return obj(); JSONObject value = snapshot().optJSONObject("team"); return value == null ? obj() : value; }
    boolean teamEnabled() { return team().optBoolean("enabled"); }
    boolean teamFallback() { return team().optBoolean("fallback"); }
    boolean teamAutomatic() { return team().optBoolean("automatic"); }
    int teamParallel() { return TeamPlan.parallel(team().optInt("parallel", 4)); }
    JSONArray teamProfiles() { return TeamPlan.profiles(team().optJSONArray("profiles")); }
    java.util.Set<String> teamExcluded() {
        java.util.Set<String> result = new java.util.LinkedHashSet<>(); JSONArray values = team().optJSONArray("excluded");
        for (int i = 0; values != null && i < values.length(); i++) if (TeamPlan.ENGINES.contains(values.optString(i))) result.add(values.optString(i));
        return result;
    }
    void saveTeam(JSONArray profiles, boolean automatic, boolean enabled, boolean fallback, int parallel, java.util.Set<String> excluded) {
        JSONObject value = obj("profiles", TeamPlan.profiles(profiles), "automatic", automatic, "enabled", enabled, "fallback", fallback,
            "parallel", TeamPlan.parallel(parallel), "excluded", new JSONArray(excluded));
        change(s -> put(s, "team", value));
    }

}
