package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.AutomationGate;
import dev.jun.gyeot.core.BrowserWorkJobs;
import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.MainBrowser;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The browser and the 들퍼레 folder for one main-chat reply (core/MainBrowser): browser_tasks run in tabs the user does not
 * see, under the user's own words for this question, with the reply's progress shown under it; the files they download are
 * this reply's, kept with folder_save into 들퍼레 or removed when the reply ends. While the browser works the app keeps a
 * notification up (MainBrowserService), so leaving the app does not stop it.
 */
final class MainBrowsing implements AutoCloseable {
    private final Context context; private final AppStore store;
    private final Map<String, JSONObject> downloads = new ConcurrentHashMap<>(); private final List<String> copies = new CopyOnWriteArrayList<>();
    private final BrowserWorkTools browser; private final FolderWorkTools folders; private final String said, recent; private boolean held, closed;

    /** words: the user's words this browsing works under; recent: those with the user's last messages, where a browser AI they named may be. */
    MainBrowsing(Context context, AppStore store, String run, String words, String recent, BooleanSupplier stopped, Consumer<String> status) {
        this.context = context.getApplicationContext(); this.store = store;
        said = words == null ? "" : words.strip();
        this.recent = recent == null ? said : recent.strip();
        String authority = AutomationGate.AUTHORITY_LINE + said.replace("\n", "\n" + AutomationGate.AUTHORITY_LINE) + "\n";
        browser = new BrowserWorkTools(this.context, store, "main", run == null || run.isBlank() ? "main" : run, () -> authority, stopped, () -> false, status, value -> {
            JSONObject artifact = value.optJSONObject("artifact"), attachment = value.optJSONObject("attachment");
            if (artifact != null && dev.jun.gyeot.core.WorkBook.safeId(artifact.optString("id"))) downloads.put(artifact.optString("id"), artifact);
            if (attachment != null && dev.jun.gyeot.core.WorkBook.safeId(attachment.optString("id"))) copies.add(attachment.optString("id"));
        });
        folders = new FolderWorkTools(this.context, () -> said, new FolderWorkTools.Downloads() {
            @Override public JSONObject find(String id) { return id == null ? null : downloads.get(id); }
            @Override public File bytes(String id) { return store.attachmentFile(id); }
        }, stopped);
    }
    /** browser_tasks, browser_ai and the folder functions, as one set; the reply may take MainBrowser.ROUNDS rounds of them. */
    WorkTools tools() {
        WorkTools browsing = new WorkTools() {
            @Override JSONArray declarations() { JSONArray all = browser.declarations(); all.put(AI_DECLARATION); return all; }
            @Override String instructions() { return browser.instructions(); }
            @Override JSONObject run(String name, Object arguments) {
                if (AI.equals(name)) return ai(arguments instanceof JSONObject given ? given : parse(arguments));
                if (BrowserWorkJobs.RUN.equals(name)) hold();
                return browser.run(name, arguments);
            }
            @Override int rounds() { return MainBrowser.ROUNDS; }
        };
        return WorkTools.combine(browsing, folders);
    }
    private static JSONObject parse(Object arguments) { try { return new JSONObject(String.valueOf(arguments)); } catch (Exception unreadable) { return new JSONObject(); } }

    // ── browser_ai: the AI that drives the browser, as 브라우저 설정 sets it ─────────────────────────────

    static final String AI = "browser_ai";
    static final String[] AIS = {"gpt", "claude", "gemini", "grok", "nim"};
    private static final JSONObject AI_DECLARATION = dev.jun.gyeot.core.Jsons.obj("name", AI,
        "description", "Show or set the AI that drives the browser (the same choice as the browser's own settings). Set it only when the user names an AI, a model or a reasoning effort for the browser.",
        "parameters", dev.jun.gyeot.core.Jsons.obj("type", "object", "required", dev.jun.gyeot.core.Jsons.arr("action"), "properties", dev.jun.gyeot.core.Jsons.obj(
            "action", dev.jun.gyeot.core.Jsons.obj("type", "string", "enum", dev.jun.gyeot.core.Jsons.arr("show", "set")),
            "ai", dev.jun.gyeot.core.Jsons.obj("type", "string", "enum", dev.jun.gyeot.core.Jsons.arr((Object[]) AIS), "description", "gpt (ChatGPT), claude, gemini, grok or nim"),
            "model", dev.jun.gyeot.core.Jsons.obj("type", "string", "description", "A model id from show, or words of its name (\"sonnet\")"),
            "effort", dev.jun.gyeot.core.Jsons.obj("type", "string", "description", "A reasoning effort the model offers (low, medium, high …); empty for its default")), "additionalProperties", false));
    private static final java.util.regex.Pattern MODEL_WORDS = java.util.regex.Pattern.compile("(?i)모델|추론|생각\\s*깊이|클로드|소넷|오퍼스|하이쿠|제미나이|그록|\\b(?:gpt|chatgpt|claude|sonnet|opus|haiku|gemini|grok|nim|low|medium|high|effort)\\b");
    private boolean connected(String brain) {
        return switch (brain) {
            case "gpt" -> store.openai.connected(); case "claude" -> store.claude.connected(); case "grok" -> store.grok.connected();
            case "nim" -> store.nim.connected(); case "gemini" -> store.gemini.connected() || store.secrets.hasKey(); default -> false;
        };
    }
    private JSONObject ai(JSONObject args) {
        BrowserModels models = new BrowserModels(store); String current = store.settings.getString("browserBrain", store.brain());
        if ("show".equals(args.optString("action"))) {
            JSONArray choices = new JSONArray();
            for (String brain : AIS) {
                if (!connected(brain)) continue;
                JSONArray list = models.catalog(brain), ids = new JSONArray();
                for (int i = 0; i < list.length() && ids.length() < 12; i++) { JSONObject item = list.optJSONObject(i); if (item != null && !item.optString("id").isBlank()) ids.put(dev.jun.gyeot.core.Jsons.obj("id", item.optString("id"), "name", item.optString("name"))); }
                choices.put(dev.jun.gyeot.core.Jsons.obj("ai", brain, "models", ids));
            }
            String model = models.model(current);
            return dev.jun.gyeot.core.Jsons.obj("ok", true, "ai", current, "model", model, "effort", models.effort(current, model), "connected", choices);
        }
        if (!"set".equals(args.optString("action"))) return GitHubFunctions.failure("action은 show 또는 set이에요.");
        if (!MODEL_WORDS.matcher(said).find() && !MODEL_WORDS.matcher(recent).find()) return GitHubFunctions.failure("Nothing was changed: the user's own recent words did not name an AI or a model for the browser.");
        String brain = args.optString("ai").strip();
        if (!java.util.Arrays.asList(AIS).contains(brain)) return GitHubFunctions.failure("ai는 gpt, claude, gemini, grok, nim 중 하나예요.");
        if (!connected(brain)) return GitHubFunctions.failure(brain + " 연결이 없어요. 설정에서 먼저 연결해 주세요.");
        String wanted = args.optString("model").strip(), model = "";
        JSONArray list = models.catalog(brain);
        for (int i = 0; i < list.length() && model.isEmpty(); i++) { JSONObject item = list.optJSONObject(i); if (item != null && item.optString("id").equalsIgnoreCase(wanted)) model = item.optString("id"); }
        String words = wanted.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9.]+", " ").strip();
        for (int i = 0; i < list.length() && model.isEmpty() && !words.isEmpty(); i++) {
            JSONObject item = list.optJSONObject(i); if (item == null) continue;
            String name = (item.optString("id") + " " + item.optString("name")).toLowerCase(java.util.Locale.ROOT); boolean all = true;
            for (String word : words.split(" ")) all &= name.contains(word);
            if (all) model = item.optString("id");
        }
        if (model.isEmpty() && wanted.isEmpty()) model = models.model(brain);
        if (model.isEmpty()) return GitHubFunctions.failure("그 이름의 모델을 " + brain + " 연결에서 찾지 못했어요. browser_ai show로 고를 수 있는 모델을 확인해 주세요.");
        dev.jun.gyeot.core.Reasoning.Profile profile = models.profile(brain, model); String effort = profile.normalize(args.optString("effort").strip().toLowerCase(java.util.Locale.ROOT));
        if (!args.optString("effort").isBlank() && effort.isEmpty()) return GitHubFunctions.failure("이 모델의 추론 강도는 " + String.join(", ", profile.efforts) + " 중 하나예요.");
        try { models.save(brain, model, effort); } catch (RuntimeException refused) { return GitHubFunctions.failure(refused.getMessage()); }
        return dev.jun.gyeot.core.Jsons.obj("ok", true, "ai", brain, "model", model, "name", models.label(brain, model), "effort", profile.resolve(effort));
    }
    private synchronized void hold() { if (held || closed) return; held = true; MainBrowserService.hold(context); }
    /** The reply ended: its browser work stops, and the files it downloaded but did not keep in 들퍼레 are removed. */
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        try { browser.close(); } finally {
            for (String id : downloads.keySet()) store.attachmentFile(id).delete();
            for (String id : copies) store.attachmentFile(id).delete();
            synchronized (this) { if (held) { held = false; MainBrowserService.release(context); } }
        }
    }
}
