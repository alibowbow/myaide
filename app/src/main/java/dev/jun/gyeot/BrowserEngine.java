package dev.jun.gyeot;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.BrowserTools;
import dev.jun.gyeot.core.BrowserPersistence;
import dev.jun.gyeot.core.BrowserConversation;
import dev.jun.gyeot.core.BrowserPrompts;
import dev.jun.gyeot.core.BrowserTaskContext;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import org.mozilla.geckoview.*;
import static dev.jun.gyeot.core.Jsons.*;

/** One persistent Gecko profile. Only our bundled extension can talk to native browser tools. */
final class BrowserEngine {
    static final String EXTENSION = "browser@gyeot.local", NATIVE = "gyeot_browser";
    private static BrowserEngine instance;
    // Every Work engine shares this queue: guard read/check/write and receipt settlement are atomic
    // with respect to other jobs. It also avoids one permanently idle storage thread per Work job.
    private static final java.util.concurrent.ExecutorService WORK_STORAGE_IO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "browser-work-storage"); thread.setDaemon(true); return thread;
    });
    private static final String WORK_PENDING_KEY = "browser.work.pending.v1";
    private static final List<java.lang.ref.WeakReference<BrowserEngine>> workEngines = new ArrayList<>();
    private static final Set<String> activeWorkReceipts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static void stopExisting(String message) { if (instance != null) instance.stopAll(message); }
    static BrowserEngine get(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Browser must run on the main thread");
        if (instance == null) instance = new BrowserEngine(context.getApplicationContext());
        return instance;
    }
    static BrowserEngine forWork(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Browser must run on the main thread");
        return forWork(context, "https://www.google.com/");
    }
    static BrowserEngine forWork(Context context, String url) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Browser must run on the main thread");
        String initialUrl = BrowserTools.url(url);
        BrowserEngine engine = new BrowserEngine(context.getApplicationContext(), true);
        engine.current.url = initialUrl;
        return engine;
    }
    /** Stable existing Work job ID; a newly generated run/lease is not recovery identity. */
    static BrowserEngine forWork(Context context, String url, String workScope) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Browser must run on the main thread");
        if (workScope == null || !workScope.matches("[A-Za-z0-9_.:-]{1,160}")) throw new IllegalArgumentException("Invalid Work recovery scope");
        BrowserEngine engine = new BrowserEngine(context.getApplicationContext(), true, workScope);
        engine.current.url = BrowserTools.url(url); return engine;
    }
    /** Native recovery UI only; page/model claims never call this API. */
    static void pendingWorkMutation(Context context, Consumer<JSONObject> callback) {
        AppStore store = ((GyeotApp) context.getApplicationContext()).store();
        Handler main = new Handler(Looper.getMainLooper());
        WORK_STORAGE_IO.execute(() -> {
            JSONObject result;
            try {
                JSONObject pending = readWorkPending(store);
                result = pending == null ? obj("ok", true, "pending", false) : pending;
                put(result, "ok", true); put(result, "pending", pending != null);
                if (pending != null) put(result, "active", activeWorkReceipts.contains(pending.optString("receipt_id")));
            } catch (Exception failure) { result = obj("ok", false, "pending", true, "error_code", "work_recovery_unreadable"); }
            final JSONObject value = result; main.post(() -> callback.accept(value));
        });
    }
    /** Explicit user inspection releases exactly one receipt; this is not a success/replay claim. */
    static void acknowledgeWorkMutation(Context context, String receiptId, Consumer<Boolean> callback) {
        AppStore store = ((GyeotApp) context.getApplicationContext()).store();
        Handler main = new Handler(Looper.getMainLooper());
        WORK_STORAGE_IO.execute(() -> {
            boolean cleared = false; String scope = "";
            try {
                JSONObject pending = readWorkPending(store);
                if (pending != null && pending.optString("receipt_id").equals(receiptId) && !activeWorkReceipts.contains(receiptId)) {
                    scope = pending.getString("job_id");
                    BrowserTaskContext task = readWorkTask(store, scope);
                    if (task.confirmMutationInspected(receiptId)
                            || task.lastMutationId().equals(receiptId) && !task.mutationUnresolved()) {
                        store.secrets.put(workTaskKey(scope), task.snapshot());
                        store.secrets.put(WORK_PENDING_KEY, ""); cleared = true;
                    }
                }
            } catch (Exception failure) { /* Preserve the guard unless every required write succeeded. */ }
            final boolean success = cleared; final String resolvedScope = scope;
            main.post(() -> {
                if (success) {
                    workEngines.removeIf(ref -> ref.get() == null);
                    for (java.lang.ref.WeakReference<BrowserEngine> ref : workEngines) {
                        BrowserEngine engine = ref.get();
                        if (engine != null && engine.store == store && engine.workScope.equals(resolvedScope) && engine.restoreTab != null) {
                            engine.restoreTab.task.confirmMutationInspected(receiptId); engine.changed();
                        }
                    }
                }
                callback.accept(success);
            });
        });
    }
    private static JSONObject readWorkPending(AppStore store) throws Exception {
        String saved = store.secrets.get(WORK_PENDING_KEY);
        if (saved == null) throw new IllegalStateException("Unreadable Work recovery record");
        if (saved.isBlank()) return null;
        JSONObject row = new JSONObject(saved);
        if (row.optInt("version") != 1 || !validIdentity(row.optString("receipt_id"))
                || !row.optString("job_id").matches("[A-Za-z0-9_.:-]{1,160}")
                || !(row.opt("request") instanceof String) || row.getString("request").length() > BrowserPrompts.REQUEST_LIMIT
                || !(row.opt("url") instanceof String) || !safeWorkUrl(row.getString("url")).equals(row.getString("url"))
                || !row.optString("reason").matches("[a-z][a-z0-9_]{0,79}")
                || !Set.of("click", "type", "select", "press", "vault_fill").contains(row.optString("action"))
                || !row.optString("state").equals("awaiting_acknowledgement")) throw new IllegalArgumentException("Invalid Work recovery record");
        return row;
    }
    private static BrowserTaskContext readWorkTask(AppStore store, String scope) throws Exception {
        String saved = store.secrets.get(workTaskKey(scope)); BrowserTaskContext task = new BrowserTaskContext();
        if (saved == null || !task.restore(saved)) throw new IllegalStateException("Unreadable Work task");
        return task;
    }
    private static String safeWorkOrigin(String url) {
        try {
            URI uri = new URI(BrowserTools.url(url));
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (Exception invalid) { return "https://www.google.com"; }
    }
    private static String safeWorkUrl(String url) {
        try {
            URI uri = new URI(BrowserTools.url(url));
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (BrowserTools.googleLogin(url) || path.toLowerCase(Locale.ROOT).matches(".*(?:login|signin|oauth|auth|token|password).*"))
                return safeWorkOrigin(url);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), path, null, null).toString();
        } catch (Exception invalid) { return "https://www.google.com"; }
    }
    static void show(Context context) {
        context.startActivity(new Intent(context, BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }
    static final class Tab {
        String id = UUID.randomUUID().toString().substring(0, 8);
        volatile String conversationId = UUID.randomUUID().toString();
        long navigationEpoch;
        volatile boolean retained = true;
        final GeckoSession session = new GeckoSession();
        final BrowserTaskContext task = new BrowserTaskContext();
        String unreadTask = "";
        JSONObject lastReadObservation; // ephemeral, same-document proof for public GET recovery only
        boolean taskChanged;
        String recoveringMutationId = "";
        String url = "https://www.google.com/", title = "새 탭", document = "";
        boolean back, forward, loading, needsLoad, loadFailed;
        String loadingUrl = "";
        final Set<String> acceptedRedirectUrls = new HashSet<>();
        int progress;
        int zoomPercent = 100, appliedZoom = -1;
        boolean zoomFailed;
        boolean desktop, settingsChanged;
        // Retained after an AI task ends: late native focus must not summon the IME.
        // Cleared only by actual input handoff on this live tab; never a global keyboard setting.
        boolean automationInput;
        WebExtension.Port port;
        GeckoDisplay display;
        android.media.ImageReader surface;
    }
    final Handler main = new Handler(Looper.getMainLooper());
    final List<Tab> tabs = new ArrayList<>();
    final dev.jun.gyeot.core.BrowserJobs jobs = new dev.jun.gyeot.core.BrowserJobs();
    final GeckoRuntime runtime;
    final AppStore store;
    final Context context;
    final boolean workEngine;
    private volatile boolean disposed;
    private boolean disposing;
    java.util.function.BiConsumer<Tab, WebResponse> workDownload;
    private final BrowserPersistence persistence;
    private final java.util.concurrent.Executor storageIo;
    private final String workScope;
    private boolean workTaskWritable;
    private boolean restoring = true, restorePending = true;
    private boolean tabsWritable;
    private Tab restoreTab;
    private boolean restoreTabReplaced;
    Tab current;
    boolean visible, paused;
    String status = "브라우저 준비 중";
    final List<String> activity = new ArrayList<>();
    String lastRequest = "", resultState = "idle";
    // These fields project only the visible tab's lane for existing UI callers.
    // Task callbacks use lease-scoped APIs and never change visible selection.
    String lastAsk = "", lastStats = "";
    final BrowserConversation transcript = new BrowserConversation();
    private boolean transcriptWritable;
    boolean transcriptStorageWarning, taskStorageWarning;
    long progressStartedElapsed, progressUpdatedElapsed;
    boolean historyRestorePending() { return restorePending; }
    String brain() { return store.settings.getString("browserBrain", store.brain()); }
    String brainName() { return switch (brain()) { case "gpt" -> "GPT"; case "claude" -> "Claude"; case "gemini" -> "Gemini"; case "grok" -> "Grok"; case "nim" -> "NVIDIA NIM"; default -> "Live 직접 판단"; }; }
    boolean connected() { return switch (brain()) { case "gpt" -> store.openai.connected(); case "claude" -> store.claude.connected(); case "grok" -> store.grok.connected(); case "nim" -> store.nim.connected(); case "gemini" -> store.gemini.connected() || store.secrets.hasKey(); default -> store.secrets.hasKey(); }; }
    Runnable observer;

    /** A lease owns one tab and one conversation for its entire lifetime. */
    private static final class Lane {
        final long id;
        final Tab owner;
        final String conversation;
        volatile Runnable onCancel;
        boolean paused;
        volatile boolean completed;
        Pending pending;
        String status = "준비됐어요", lastAsk = "", lastRequest = "", lastStats = "", resultState = "idle", transcriptRequest = "";
        /** The user's own words for this lane's task (AutomationGate.userWords of its request and corrections), for 이어서 맡기기. */
        String taskWords = "";
        long requestStarted, progressStartedElapsed, progressUpdatedElapsed;
        final List<String> activity = new ArrayList<>();
        java.util.function.BiFunction<String, String, Boolean> steering;
        Lane(long id, Tab owner) { this.id = id; this.owner = owner; conversation = owner.conversationId; }
    }
    private final java.util.concurrent.ConcurrentHashMap<Long, Lane> lanes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Tab, Lane> tabLanes = new IdentityHashMap<>();
    private long nextLease, viewEpoch;
    private final Set<Tab> stopping = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Lane lane(Tab tab) {
        if (!live(tab)) return null;
        Lane existing = tabLanes.get(tab);
        if (existing != null && !existing.conversation.equals(tab.conversationId)) {
            lanes.remove(existing.id); tabLanes.remove(tab);
        }
        return tabLanes.computeIfAbsent(tab, t -> { Lane l = new Lane(++nextLease, t); lanes.put(l.id, l); return l; });
    }
    private Lane lane(long lease) { return lanes.get(lease); }
    private Lane selected() { return lane(current); }
    private Pending pending(Tab tab) { Lane l = tabLanes.get(tab); return l == null ? null : l.pending; }
    private boolean working(Tab tab) { Lane l = tabLanes.get(tab); return l != null && (l.onCancel != null || l.pending != null); }
    long activeTaskLease() {
        Lane selected = tabLanes.get(current);
        if (selected != null && taskRunning(selected)) return selected.id;
        for (Lane l : lanes.values()) if (taskRunning(l)) return l.id;
        return -1;
    }
    /**
     * A lane runs a task while it holds the task's cancel, on a tab the browser still has. A lane
     * whose tab was discarded can never be stopped or finished through its lease, so it is not running: it must not hold
     * the job's notification or report a job as running forever.
     */
    private static boolean taskRunning(Lane l) { return l.onCancel != null && l.owner.retained; }
    private boolean rendering(Tab tab) { Lane l = tabLanes.get(tab); return l != null && l.onCancel != null; }
    boolean anyRunning() { return lanes.values().stream().anyMatch(BrowserEngine::taskRunning); }
    boolean running() { Lane l = selected(); return l != null && l.onCancel != null; }
    boolean running(long lease) { Lane l = lane(lease); return valid(lease) && l.onCancel != null; }
    Tab tabForLease(long lease) { Lane l = lane(lease); return valid(lease) ? l.owner : null; }
    Tab taskTab(long lease) { return tabForLease(lease); }
    void installSteering(long lease, java.util.function.BiFunction<String, String, Boolean> callback) {
        Lane l = lane(lease); if (valid(lease) && l.onCancel != null) l.steering = callback;
    }
    boolean steerTask(String visible, String actual) {
        Lane l = selected();
        return l != null && l.onCancel != null && l.steering != null && canAcceptTask(actual) && l.steering.apply(visible, actual);
    }
    boolean acceptSteering(long lease, String visible, String actual) {
        Lane l = lane(lease);
        if (!valid(lease) || l.onCancel == null || l.owner != current || !canAcceptTask(actual)) return false;
        l.owner.task.accept(actual); l.owner.taskChanged = true; l.lastAsk = visible; l.lastRequest = actual;
        l.taskWords = (l.taskWords + "\n" + dev.jun.gyeot.core.AutomationGate.userWords(actual)).strip();
        l.transcriptRequest = transcript.request(l.conversation, visible);
        persistTranscript(); changed(); return true;
    }
    /** A running task's status line (setTaskStatus), "" when that lease runs no task. */
    String taskStatus(long lease) { Lane l = lane(lease); return l != null && valid(lease) && taskRunning(l) && l.status != null ? l.status : ""; }
    void setTaskStatus(long lease, String text) {
        Lane l = lane(lease); if (!valid(lease) || l.completed) return;
        if (!Objects.equals(l.status, text)) l.progressUpdatedElapsed = android.os.SystemClock.elapsedRealtime();
        l.status = text; changed();
    }
    void acceptBackgroundRequest(long lease, String text) {
        Lane l = lane(lease); if (!valid(lease) || l.completed) return;
        l.lastRequest = text; l.taskWords = dev.jun.gyeot.core.AutomationGate.userWords(text); changed();
    }
    void record(String text) { Lane l = selected(); if (l != null) record(l.id, text); }
    void record(long lease, String text) {
        Lane l = lane(lease); if (!valid(lease) || l.completed) return;
        l.progressUpdatedElapsed = android.os.SystemClock.elapsedRealtime(); l.activity.add(text);
        if (l.activity.size() > 30) l.activity.remove(0); changed();
    }
    // Existing UI/voice callers still assign these fields. Capture such changes only
    // against the lane that was projected, never against a newly selected tab.
    private Lane projected;
    private String projectedStatus = status, projectedAsk = lastAsk, projectedRequest = lastRequest, projectedStats = lastStats, projectedResult = resultState;
    private boolean projectedPaused;
    private void absorbProjection() {
        if (projected == null || lane(projected.id) != projected) return;
        if (!Objects.equals(status, projectedStatus)) projected.status = status;
        if (!Objects.equals(lastAsk, projectedAsk)) projected.lastAsk = lastAsk;
        if (!Objects.equals(lastRequest, projectedRequest)) projected.lastRequest = lastRequest;
        if (!Objects.equals(lastStats, projectedStats)) projected.lastStats = lastStats;
        if (!Objects.equals(resultState, projectedResult)) projected.resultState = resultState;
        if (paused != projectedPaused) projected.paused = paused;
    }
    private void project() {
        absorbProjection();
        Lane l = selected(); if (l == null) return;
        projected = l;
        projectedPaused = paused = l.paused; projectedStatus = status = l.status;
        projectedAsk = lastAsk = l.lastAsk; projectedRequest = lastRequest = l.lastRequest;
        projectedStats = lastStats = l.lastStats; projectedResult = resultState = l.resultState;
        activity.clear(); activity.addAll(l.activity);
        progressStartedElapsed = l.progressStartedElapsed; progressUpdatedElapsed = l.progressUpdatedElapsed;
    }
    static final class PromptToken {
        final Tab tab; final GeckoSession session; final String conversation, url, document;
        final long lease, viewEpoch, navigationEpoch;
        PromptToken(Tab tab, long lease, long viewEpoch) {
            this.tab = tab; session = tab.session; conversation = tab.conversationId;
            this.lease = lease; this.viewEpoch = viewEpoch; navigationEpoch = tab.navigationEpoch;
            url = tab.url; document = tab.document;
        }
    }
    PromptToken promptToken(GeckoSession session) {
        if (!visible || current == null || current.session != session || !liveSession(session)) return null;
        return new PromptToken(current, lease(), viewEpoch);
    }
    boolean validPrompt(PromptToken token) {
        return token != null && visible && current == token.tab && current.session == token.session
            && token.viewEpoch == viewEpoch && token.navigationEpoch == current.navigationEpoch
            && token.url.equals(current.url) && token.document.equals(current.document)
            && token.conversation.equals(current.conversationId) && valid(token.lease);
    }
    JSONObject pendingTabMutation(PromptToken token) {
        if (workEngine || !validPrompt(token) || restorePending || !token.tab.unreadTask.isEmpty())
            return obj("ok", false, "pending", true);
        Tab tab = token.tab; JSONObject receipt = tab.task.pendingMutation();
        if (receipt.optString("id").isEmpty()) return obj("ok", true, "pending", false);
        try {
            JSONObject saved = new JSONObject(tab.task.snapshot()), recovery = saved.optJSONObject("recovery_context");
            JSONObject context = recovery == null ? saved : recovery;
            JSONArray requests = context.optJSONArray("requests");
            String request = requests == null || requests.length() == 0 ? "" : requests.optString(0);
            JSONObject live = context.optJSONObject("liveness");
            return obj("ok", true, "pending", true, "receipt_id", receipt.optString("id"), "action", receipt.optString("action"),
                "request", receipt.optString("request", request), "url", receipt.optString("url", recovery == null ? safeWorkUrl(tab.url) : recovery.optString("origin", safeWorkUrl(tab.url))),
                "reason", live == null ? "awaiting_acknowledgement" : live.optString("reason", "awaiting_acknowledgement"),
                "stop_reason", context.optString("result"), "active", working(tab) || !tab.recoveringMutationId.isEmpty());
        } catch (Exception unreadable) { return obj("ok", false, "pending", true); }
    }
    /** Confirmation commits to this exact original tab; later selection cannot retarget it. */
    void acknowledgeTabMutation(PromptToken token, String receiptId, Consumer<Boolean> callback) {
        if (workEngine || !validPrompt(token) || restorePending || !tabsWritable || !transcriptWritable
                || working(token.tab) || !token.tab.unreadTask.isEmpty() || !token.tab.recoveringMutationId.isEmpty()
                || !token.tab.task.pendingMutation().optString("id").equals(receiptId) || receiptId.isEmpty()) {
            callback.accept(false); return;
        }
        Tab tab = token.tab; tab.recoveringMutationId = receiptId;
        persist(saved -> {
            if (saved) tab.task.confirmMutationInspected(receiptId);
            if (tab.recoveringMutationId.equals(receiptId)) tab.recoveringMutationId = "";
            callback.accept(saved); changed();
        });
    }
    private String taskSnapshot(Tab tab) {
        if (tab.recoveringMutationId.isEmpty()) return tab.task.snapshot();
        BrowserTaskContext resolved = new BrowserTaskContext();
        if (!resolved.restore(tab.task.snapshot()) || !resolved.confirmMutationInspected(tab.recoveringMutationId))
            throw new IllegalStateException("Recovery receipt changed");
        return resolved.snapshot();
    }
    private boolean refreshPosted;
    private WebExtension extension;
    private String extensionError = "";
    private long sequence;
    private GeckoSession.PromptDelegate prompts;
    java.util.function.BiConsumer<Tab,String> externalAppLink;
    boolean canOpenExternalApp(Tab tab) {
        return live(tab) && !workEngine && visible && current == tab && !running() && pending(current) == null
            && dev.jun.gyeot.core.BrowserYouTubeLinks.origin(tab.url)
            && !tab.needsLoad && !tab.loading && !tab.loadFailed && tab.session.isOpen();
    }
    private static final class Pending {
        long generation; String id; Tab tab; WebExtension.Port port, dispatchPort; Consumer<JSONObject> callback;
        boolean sent, mutation, dispatched, observingNavigation, rereadRejected, preparingMutation, mutationSaved, settlingMutation;
        String dispatchId = "", readReceiptId = "", dispatchDocument = "";
        boolean actionAcknowledged;
        boolean navigationProposalAccepted, recoveryChecked;
        String action = "", navigationAction = "", previousDocument = "", previousUrl = "", navigationTarget = "", committedUrl = "";
        int sends;
        long deadline;
    }
    private BrowserEngine(Context context) { this(context, false); }
    private BrowserEngine(Context context, boolean workEngine) { this(context, workEngine, ""); }
    private BrowserEngine(Context context, boolean workEngine, String workScope) {
        this.context = context; this.workEngine = workEngine; this.workScope = workScope;
        store = ((GyeotApp) context).store();
        storageIo = workEngine ? WORK_STORAGE_IO : java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "browser-storage"); thread.setDaemon(true); return thread;
        });
        persistence = new BrowserPersistence(new BrowserPersistence.Disk() {
            public String get(String key) throws Exception { return store.secrets.get(key); }
            public void put(String key, String value) throws Exception { store.secrets.put(key, value); }
        }, storageIo);
        BrowserRuntime shared = BrowserRuntime.get(context);
        runtime = shared.runtime;
        shared.bridge().accept(value -> {
            if (disposed) return;
            extension = value;
            // Bind before the first load. Reloading every restored page used to duplicate
            // network work and could discard freshly entered page content.
            for (Tab tab : tabs) bind(tab);
            if (current != null) activate(current);
            if (status.equals("브라우저 준비 중")) status = "준비됐어요"; changed();
        }, error -> { if (disposed) return; extensionError = "브라우저 AI 연결을 준비하지 못했어요. 앱을 다시 열어 주세요."; status = extensionError; if (current != null) activate(current); changed(); });
        // Show browser chrome immediately. Keystore reads must not block the first frame.
        restoreTab = addTab("https://www.google.com/", false); restoreTab.needsLoad = true;
        if (workEngine) {
            workEngines.add(new java.lang.ref.WeakReference<>(this));
            restoring = false; restorePending = !workScope.isEmpty(); status = "준비됐어요";
            if (restorePending) storageIo.execute(() -> {
                String saved;
                try { saved = store.secrets.get(workTaskKey()); } catch (Exception failure) { saved = null; }
                final String loaded = saved;
                main.post(() -> {
                    if (disposed) return;
                    workTaskWritable = loaded != null && restoreTab.task.restore(loaded);
                    taskStorageWarning = !workTaskWritable;
                    restorePending = false;
                    if (!workTaskWritable) restoreTab.unreadTask = loaded == null ? "unreadable_work_task" : loaded;
                    changed();
                });
            });
        } else persistence.load(saved -> main.post(() -> restore(saved)));
        if (!workEngine && store.settings.getBoolean("browserRunning", false)) {
            status = "이전 브라우저 작업이 앱 종료로 중단됐어요. 다시 실행하려면 지시해 주세요.";
            store.settings.edit().putBoolean("browserRunning", false).apply();
        }
    }
    private void restore(BrowserPersistence.Saved saved) {
        // Untouched: nothing was opened, typed or begun on the placeholder tab before the saved tabs came back.
        boolean untouched = restoring && tabs.stream().noneMatch(this::working);
        restoring = false; restorePending = false;
        transcriptWritable = transcript.restore(saved.conversation(), saved.result());
        transcriptStorageWarning = !transcriptWritable;
        List<String> urls = new ArrayList<>();
        List<Integer> zooms = new ArrayList<>();
        List<Boolean> desktops = new ArrayList<>();
        List<String> tasks = new ArrayList<>(), ids = new ArrayList<>(), conversations = new ArrayList<>();
        Set<String> restoredIds = new HashSet<>(), restoredConversations = new HashSet<>();
        int savedSelected = -1;
        tabsWritable = saved.tabs() != null;
        try { JSONArray values = new JSONArray(saved.tabs() == null || saved.tabs().isBlank() ? "[]" : saved.tabs()); if (values.length() > 6) tabsWritable = false; for (int i = 0; i < Math.min(6, values.length()); i++) {
            try {
                JSONObject entry = values.optJSONObject(i);
                if (entry != null && entry.has("task") && !(entry.opt("task") instanceof String)) throw new IllegalArgumentException("Invalid tab task memory");
                if (entry != null && ((entry.has("id") && !(entry.opt("id") instanceof String)) || (entry.has("conversation") && !(entry.opt("conversation") instanceof String)))) throw new IllegalArgumentException("Invalid tab identity");
                String id = entry == null ? "" : entry.optString("id", ""), conversation = entry == null ? "" : entry.optString("conversation", "");
                if ((!id.isEmpty() && (!validIdentity(id) || !restoredIds.add(id)))
                        || (!conversation.isEmpty() && (!validIdentity(conversation) || conversation.startsWith("__") || !restoredConversations.add(conversation))))
                    throw new IllegalArgumentException("Duplicate or invalid tab identity");
                urls.add(BrowserTools.url(entry == null ? values.getString(i) : entry.getString("url")));
                ids.add(id); conversations.add(conversation);
                zooms.add(entry == null ? 100 : clampZoom(entry.optInt("zoom", 100)));
                desktops.add(entry != null && entry.optBoolean("desktop"));
                tasks.add(entry == null ? "" : entry.optString("task", ""));
                if (entry != null && entry.optBoolean("selected")) savedSelected = urls.size() - 1;
            } catch (Exception ignored) { tabsWritable = false; }
        } } catch (Exception ignored) { tabsWritable = false; }
        taskStorageWarning = !tabsWritable;
        if (savedSelected < 0) savedSelected = urls.size() - 1;
        if (!urls.isEmpty() && untouched) {
            for (Tab tab : tabs) tab.retained = false;
            tabs.clear(); // The placeholder has never opened a GeckoSession.
            for (int i = 0; i < urls.size(); i++) { String url = urls.get(i); Tab tab = addTab(url, false); tab.zoomPercent = zooms.get(i); tab.desktop = desktops.get(i); restoreIdentity(tab, ids.get(i), conversations.get(i)); restoreTask(tab, tasks.get(i), urls.get(i)); tab.title = BrowserTools.origin(url); tab.needsLoad = true; }
            current = tabs.get(savedSelected);
        } else if (!urls.isEmpty()) {
            // A new address replaces only the previously active saved tab. Preserve
            // the other tabs without switching away from or reloading the user's page.
            Tab selected = current; List<Tab> liveTabs = new ArrayList<>(tabs);
            if (!liveTabs.contains(restoreTab)) transcript.archive(conversations.get(savedSelected));
            if (!restoreTabReplaced && liveTabs.contains(restoreTab)) {
                restoreTab.url = urls.get(savedSelected); if (!restoreTab.settingsChanged) { restoreTab.zoomPercent = zooms.get(savedSelected); restoreTab.desktop = desktops.get(savedSelected); } restoreTab.title = BrowserTools.origin(restoreTab.url);
            }
            if (liveTabs.contains(restoreTab)) { restoreIdentity(restoreTab, ids.get(savedSelected), conversations.get(savedSelected)); restoreTask(restoreTab, tasks.get(savedSelected), urls.get(savedSelected)); }
            tabs.clear();
            for (int i = 0; i < urls.size(); i++) {
                if (i == savedSelected) continue;
                // The tab cap is not permission to discard a saved task during a slow restore.
                if (tabs.size() + liveTabs.size() >= 6) { tabsWritable = false; continue; }
                Tab tab = addTab(urls.get(i), false); tab.zoomPercent = zooms.get(i); tab.desktop = desktops.get(i); restoreIdentity(tab, ids.get(i), conversations.get(i)); restoreTask(tab, tasks.get(i), urls.get(i)); tab.title = BrowserTools.origin(tab.url); tab.needsLoad = true;
            }
            tabs.addAll(liveTabs); current = selected;
            persist();
        }
        taskStorageWarning = !tabsWritable || tabs.stream().anyMatch(tab -> !tab.unreadTask.isEmpty());
        Set<String> activeScopes = new HashSet<>();
        for (Tab tab : tabs) activeScopes.add(tab.conversationId);
        transcript.archiveDetached(activeScopes);
        // Upgrade once to a single committed snapshot; the legacy result is archive-only.
        persist(); showTaskState(); activate(current); changed();
    }
    private static boolean validIdentity(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,80}"); }
    private void restoreIdentity(Tab tab, String id, String conversation) {
        if (tab.taskChanged) { // Never reassign a scope that already owns a live accepted request.
            if (!conversation.isEmpty() && !conversation.equals(tab.conversationId)) transcript.archive(conversation);
            return;
        }
        if (!id.isEmpty()) tab.id = id;
        if (!conversation.isEmpty()) tab.conversationId = conversation;
    }
    private void restoreTask(Tab tab, String saved, String sourceUrl) {
        boolean restored = tab.taskChanged ? tab.task.restoreMutationFence(saved, safeWorkUrl(sourceUrl)) : tab.task.restore(saved);
        if (!restored) { tab.unreadTask = saved; taskStorageWarning = true; }
    }
    private void showTaskState() {
        Lane l = selected(); if (l == null || l.onCancel != null) return;
        l.lastAsk = current.task.lastRequest(); l.lastRequest = l.lastAsk; l.lastStats = "";
        l.taskWords = dev.jun.gyeot.core.AutomationGate.userWords(l.lastRequest);
        l.resultState = current.task.state();
        l.status = current.task.lastResult().isBlank() ? "준비됐어요" : current.task.lastResult();
        project();
    }
    boolean canAcceptTask(String request) {
        return !restorePending && tabsWritable && transcriptWritable && current != null
            && !transcript.isArchived(current.conversationId) && current.task.canAccept(request);
    }
    String taskContext(long lease) {
        Lane l = lane(lease);
        return !valid(lease) || l.transcriptRequest.isEmpty() ? "" : l.owner.task.context();
    }
    boolean mutationUnresolved(long lease) {
        Lane l = lane(lease); return valid(lease) && l.owner.task.mutationUnresolved();
    }
    JSONObject pendingMutation(long lease) {
        Lane l = lane(lease); return valid(lease) ? l.owner.task.pendingMutation() : obj();
    }
    void recordTaskLiveness(long lease, String phase, String reason) {
        Lane l = lane(lease); if (!valid(lease)) return;
        l.owner.task.liveness(phase, reason); l.owner.taskChanged = true; persistTask(l.owner, null);
    }
    /** The user's own words of the visible tab's last task, carried by 이어서 맡기기 (BrowserPrompts.resume). */
    String taskWords() { Lane l = selected(); return l == null ? "" : l.taskWords; }
    String taskContext() { return taskContext(lease()); }
    long begin(Runnable cancel) {
        Lane old = selected();
        if (stopping.contains(current)) throw new IllegalStateException("작업을 중지하는 중이에요.");
        if (old == null) throw new IllegalStateException("탭이 닫혔어요.");
        if (old.onCancel != null || old.pending != null)
            throw new IllegalStateException("이 탭의 작업이 진행 중이에요. 현재 작업에 지시하거나 중지해 주세요.");
        // A task begun before the saved tabs are back (a voice browser_task right after the app starts) owns this tab:
        // restore keeps it (and its conversation), as it keeps a tab the user typed an address into, instead of
        // discarding it as an untouched placeholder and leaving the task on no tab. restoring itself stays as it is: it
        // also holds back loads and saves until restore lands.
        if (restoring) current.taskChanged = true;
        lanes.remove(old.id);
        Lane l = new Lane(++nextLease, current); tabLanes.put(current, l); lanes.put(l.id, l);
        l.onCancel = Objects.requireNonNull(cancel);
        l.progressStartedElapsed = l.progressUpdatedElapsed = android.os.SystemClock.elapsedRealtime();
        l.resultState = "running";
        activate(current); changed(); return l.id;
    }
    private void persistTranscript() { persist(); }
    String conversationId() { return current == null ? "" : current.conversationId; }
    long conversationVersion() { return transcript.version(); }
    int conversationSize() { return transcript.size(conversationId()); }
    boolean hasConversation() { return conversationSize() > 0 || current != null && (!current.task.lastRequest().isBlank() || !current.task.lastResult().isBlank() || !current.unreadTask.isBlank()); }
    JSONArray conversationWindow(int limit) { return transcript.window(conversationId(), limit); }
    String conversationLastAnswer() { return transcript.lastAnswer(conversationId()); }
    boolean isActiveConversation(String id) { return id != null && tabs.stream().anyMatch(tab -> id.equals(tab.conversationId)); }
    JSONArray archivedConversations() { return transcript.archives(); }
    JSONArray archivedWindow(String id, int limit) { return transcript.window(id, limit); }
    boolean canManageConversations() { return !running() && pending(current) == null && !restorePending && tabsWritable && transcriptWritable; }
    boolean taskOnCurrentTab() { return true; }
    private void archiveConversation(Tab tab) {
        if (tab != null) transcript.archive(tab.conversationId);
    }
    boolean deleteConversation(String id) { return deleteConversation(id, saved -> {}); }
    boolean deleteConversation(String id, Consumer<Boolean> callback) {
        if (!canManageConversations() || id == null || id.isBlank()) return false;
        for (Tab tab : tabs) if (id.equals(tab.conversationId) && working(tab)) return false;
        boolean found = transcript.has(id);
        for (Tab tab : tabs) if (id.equals(tab.conversationId)) found = true;
        if (!found) return false;
        stop(""); // Retire callbacks belonging to the deleted/previous idle conversation.
        for (Tab tab : tabs) if (id.equals(tab.conversationId) && tab != current) stop(tab, "");
        viewEpoch++;
        transcript.delete(id);
        for (Tab tab : tabs) if (id.equals(tab.conversationId)) {
            tab.task.fresh(); tab.taskChanged = true; tab.unreadTask = "";
            tab.conversationId = UUID.randomUUID().toString();
        }
        Lane l=selected();l.transcriptRequest="";l.activity.clear();
        showTaskState(); persist(callback); changed(); return true;
    }
    void acceptRequest(long lease, String visible, String actual) {
        Lane l = lane(lease); if (!valid(lease) || l.completed || !l.transcriptRequest.isEmpty()) return;
        l.owner.task.accept(actual); l.owner.taskChanged = true;
        l.lastAsk = visible; l.lastRequest = actual; l.lastStats = ""; l.requestStarted = System.currentTimeMillis();
        l.taskWords = dev.jun.gyeot.core.AutomationGate.userWords(actual);
        transcript.ensure(l.conversation, BrowserTools.origin(l.owner.url));
        l.transcriptRequest = transcript.request(l.conversation, visible);
        persistTranscript(); changed();
    }
    boolean freshConversation() {
        if (!canManageConversations() || current == null) return false;
        stop(""); // Late completion or acceptance must not enter the new conversation.
        viewEpoch++;
        archiveConversation(current);
        current.task.fresh(); current.taskChanged = true; current.unreadTask = "";
        current.conversationId = UUID.randomUUID().toString();
        persistTranscript();
        Lane l=selected();l.lastAsk="";l.lastStats="";l.lastRequest="";l.transcriptRequest="";
        l.activity.clear();l.resultState="idle";l.status="준비됐어요";changed();return true;
    }
    // Work jobs already live inside AssistantService foreground ownership. Starting another
    // service after a model reply while the Activity is hidden may be prohibited by Android.
    void holdJob(long lease) {
        if (workEngine || !running(lease)) return;
        BrowserTaskService.hold(context, lease); store.settings.edit().putBoolean("browserRunning", true).apply();
    }
    void finishJob(long lease, JSONObject value) {
        Lane l = lane(lease); if (!valid(lease) || l.completed) return;
        absorbProjection();
        Pending unfinished = l.pending; settleUnsent(unfinished); l.pending = null;
        l.onCancel = null; l.steering = null; l.completed = true;
        if (unfinished != null && unfinished.dispatched) {
            put(value, "ok", false); put(value, "action_may_have_run", true);
            put(value, "error", "실행한 동작의 결과를 확인하지 못했어요. 반복 실행하지 말고 해당 탭을 확인해 주세요.");
            l.status = value.optString("error");
        }
        l.resultState = BrowserTools.resultState(value);
        saveAnswer(l);
        l.lastStats = BrowserPrompts.stats(value.optInt("steps"), System.currentTimeMillis() - l.requestStarted);
        releaseJob(l, value.optBoolean("requires_user"));
        settleDisplays(); changed();
        if (unfinished != null) unfinished.callback.accept(obj("ok", false, "cancelled", true,
            "action_may_have_run", unfinished.dispatched, "error", "작업이 끝나 이전 동작의 응답은 더 이상 사용하지 않아요."));
    }
    private void saveAnswer(Lane l) {
        if (transcript.answer(l.transcriptRequest, l.status, l.resultState)) {
            l.lastStats = BrowserPrompts.stats(0, System.currentTimeMillis() - l.requestStarted);
        }
        l.owner.task.finish(l.status, l.resultState); l.owner.taskChanged = true; persistTask(l.owner, null);
    }
    private void releaseJob(Lane l, boolean requiresUser) {
        if (workEngine) return;
        store.settings.edit().putBoolean("browserRunning", anyRunning()).apply();
        BrowserTaskService.release(context, l.id, l.status, requiresUser, anyRunning());
    }
    private void settleDisplays() {
        for (Tab t : tabs) {
            if (visible && t == current || rendering(t)) activate(t);
            else if (t.session.isOpen()) { t.session.setActive(false); releaseDisplay(t); }
        }
    }
    long lease() {
        Lane l = selected();
        if (l != null && l.completed) {
            lanes.remove(l.id);
            Lane idle = new Lane(++nextLease, l.owner);
            idle.paused = l.paused; idle.status = l.status; idle.lastAsk = l.lastAsk;
            idle.lastRequest = l.lastRequest; idle.lastStats = l.lastStats; idle.resultState = l.resultState; idle.taskWords = l.taskWords;
            idle.activity.addAll(l.activity); tabLanes.put(l.owner, idle); lanes.put(idle.id, idle); l = idle;
            project();
        }
        return l == null ? -1 : l.id;
    }
    boolean valid(long lease) { Lane l = lane(lease); return !disposed && l != null && !l.completed && !stopping.contains(l.owner) && l.owner.retained && l.conversation.equals(l.owner.conversationId); }
    /** A Work job owns these sessions exclusively; never closes the browser UI sessions. */
    void disposeWork() {
        if (!workEngine || disposed || disposing) return;
        disposing = true;
        try { stopAll("작업 브라우저를 닫았어요"); }
        catch (RuntimeException ignored) { /* Consumer failures must not retain native sessions. */ }
        finally {
            disposed = true; workDownload = null; observer = null;
            List<Tab> closing = new ArrayList<>(tabs);
            for (Tab tab : closing) tab.retained = false;
            tabs.clear(); current = null;
            for (Tab tab : closing) {
                if (tab.display != null) {
                    try { tab.display.surfaceDestroyed(); } catch (RuntimeException ignored) { }
                    try { tab.session.releaseDisplay(tab.display); } catch (RuntimeException ignored) { }
                    tab.display = null;
                }
                if (tab.surface != null) {
                    try { tab.surface.close(); } catch (RuntimeException ignored) { }
                    tab.surface = null;
                }
                try { if (tab.session.isOpen()) tab.session.close(); } catch (RuntimeException ignored) { }
            }
            disposing = false;
        }
    }
    void stop(String message){stop(current,message);}
    void stop(Tab tab,String message){Lane l=lane(tab);if(l!=null)stopTask(l.id,message);}
    void stopTask(long lease, String message) {
        Lane l = lane(lease); if (!valid(lease)) return;
        absorbProjection();
        Runnable cancel = l.onCancel; Pending pending = l.pending;
        settleUnsent(pending);
        boolean active = cancel != null || pending != null;
        l.onCancel = null; l.steering = null; l.pending = null; l.completed = true;
        if (!message.isBlank()) l.status = message;
        else if (active) l.status = "브라우저 작업을 중지했어요";
        if (active) {
            l.resultState = "stopped";
            l.owner.task.liveness("interrupted", "engine_stopped");
            saveAnswer(l);
        }
        // Retire before arbitrary callbacks: neither a cancelled model nor a
        // reentrant approval can dispatch under the old lease.
        lanes.remove(l.id); stopping.add(l.owner);
        Lane replacement = new Lane(++nextLease, l.owner);
        replacement.paused = l.paused; replacement.status = l.status;
        replacement.lastAsk = l.lastAsk; replacement.lastRequest = l.lastRequest; replacement.taskWords = l.taskWords;
        replacement.lastStats = l.lastStats; replacement.resultState = l.resultState;
        replacement.activity.addAll(l.activity);
        replacement.progressStartedElapsed = l.progressStartedElapsed;
        replacement.progressUpdatedElapsed = l.progressUpdatedElapsed;
        tabLanes.put(l.owner, replacement); lanes.put(replacement.id, replacement);
        project(); releaseJob(l, l.paused && !active);
        try { if (cancel != null) cancel.run(); }
        finally {
            try {
                if (pending != null) pending.callback.accept(obj("ok", false, "cancelled", true,
                    "action_may_have_run", pending.dispatched,
                    "error", "브라우저 작업을 중지했어요. 이전 동작을 다시 실행하지 마세요."));
            } finally { stopping.remove(l.owner); projected = null; project(); settleDisplays(); changed(); }
        }
    }
    /** Steering withdraws unexecuted plans. Dispatched effects retain one observation. */
    void invalidatePlan(long lease) {
        Lane l = lane(lease); if (!valid(lease) || l.pending == null) return;
        Pending p = l.pending;
        if (p.dispatched) return;
        settleUnsent(p);
        l.pending = null;
        p.callback.accept(obj("ok", false, "stale_plan", true, "error_code", "stale_plan", "action_executed", false,
            "error", "새 지시가 도착해 이전 읽기·미실행 계획을 취소했어요."));
        settleDisplays();
    }
    void stopAll(String message) {
        for (Lane l : new ArrayList<>(lanes.values())) if (l.onCancel != null || l.pending != null) {
            try { stopTask(l.id, message); } catch (RuntimeException ignored) { /* Stop the remaining owners as well. */ }
        }
    }
    void manual(){Lane l=selected();if(l!=null&&(l.onCancel!=null||l.pending!=null)){l.paused=true;stopTask(l.id,"직접 조작 중 · AI가 멈췄어요");}}
    void loginMode(boolean enable){Lane l=selected();if(l!=null)l.paused=enable;stop(enable?"로그인은 직접 진행해 주세요":"AI를 다시 사용할 수 있어요");}
    void attach(Runnable observer, GeckoSession.PromptDelegate delegate) {
        this.observer = observer; this.prompts = delegate; visible = true; viewEpoch++;
        for (Tab tab : tabs) releaseDisplay(tab);
        for (Tab tab : tabs) tab.session.setPromptDelegate(gatedPrompts);
        activate(current);
        for(Lane l:lanes.values())if(l.onCancel!=null&&live(l.owner))activate(l.owner);
        changed();
    }
    void detach() {
        visible = false; viewEpoch++; observer = null; prompts = null; externalAppLink = null;
        for (Tab tab : tabs) tab.session.setPromptDelegate(backgroundPrompts);
        // GeckoView releases its display; the session and extension keep working in the foreground service.
        main.post(() -> {
            if (visible) return;
            for (Tab tab : tabs) if (tab.session.isOpen()) {
                boolean active = rendering(tab) || tab==current&&store.voiceActive;
                tab.session.setActive(active);
                if (active) backgroundDisplay(tab); else releaseDisplay(tab);
            }
        });
    }
    void changed() {
        project();
        // Gecko emits location/title/progress/port callbacks in bursts. Render once per
        // frame instead of rebuilding the native chrome for each callback.
        if (observer == null || refreshPosted) return;
        refreshPosted = true;
        main.postDelayed(() -> { refreshPosted = false; if (observer != null) { observer.run(); settleDisplays(); } }, 16);
    }
    Tab newTab(String url, boolean open) {
        BrowserTools.url(url);
        restoring = false;
        Tab tab = addTab(url, open); persist(); return tab;
    }
    private boolean live(Tab tab) { return !disposed && tab != null && tabs.contains(tab); }
    boolean liveSession(GeckoSession session) { return !disposed && tabs.stream().anyMatch(tab -> tab.session == session); }
    private Tab addTab(String url, boolean open) {
        if (disposed) throw new IllegalStateException("작업 브라우저가 종료됐어요.");
        if (workEngine && tabs.size() >= 2) throw new IllegalStateException("작업별 탭은 2개까지 열 수 있어요. 별도 병렬 작업을 사용해 주세요.");
        if (tabs.size() >= 6) throw new IllegalStateException("탭은 6개까지 열 수 있어요. 사용하지 않는 탭을 닫아 주세요.");
        Tab tab = new Tab(); tab.url = BrowserTools.url(url); tabs.add(tab); current = tab; viewEpoch++;
        tab.session.setPromptDelegate(gatedPrompts);
        tab.session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
                if (!live(tab)) return GeckoResult.fromValue(AllowOrDeny.DENY);
                try {
                    BrowserTools.url(request.uri);
                    // Redirects can fail before location commit, without a second PageStart.
                    // Record accepted targets without replacing a newer explicit loading URL.
                    if (request.isRedirect && tab.loading) tab.acceptedRedirectUrls.add(request.uri);
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }
                catch (Exception e) {
                    String target = dev.jun.gyeot.core.BrowserYouTubeLinks.appLink(request.uri, tab.url, request.triggerUri,
                        request.hasUserGesture, request.isRedirect, request.isDirectNavigation);
                    if (target != null && canOpenExternalApp(tab) && externalAppLink != null) externalAppLink.accept(tab, target);
                    else if (visible && current == tab) { status = "이 앱 링크는 열 수 없어요. 웹페이지는 그대로 유지했어요."; changed(); }
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
            }
            @Override public void onLocationChange(GeckoSession session, String url, List<GeckoSession.PermissionDelegate.ContentPermission> permissions, Boolean gesture) {
                if (!live(tab)) return;
                tab.url = url;
                navigationCommitted(tab, url);
                if (tab == current && BrowserTools.googleLogin(url)) status = "Google 로그인 · 직접 진행해 주세요";
                persist(); changed();
            }
            @Override public void onCanGoBack(GeckoSession session, boolean value) { if (!live(tab)) return; tab.back = value; changed(); }
            @Override public void onCanGoForward(GeckoSession session, boolean value) { if (!live(tab)) return; tab.forward = value; changed(); }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
                if (!live(tab)) return GeckoResult.fromValue(null);
                if (working(tab)) {
                    stopAffected(tab, "새 탭은 직접 열어 주세요. 작업 대상 탭은 바뀌지 않았어요.");
                    return GeckoResult.fromValue(null);
                }
                try {
                    Tab selected = current;
                    restoring = false;
                    Tab popup = addTab(uri, false);
                    if (!workEngine && !visible) {
                        current = selected; // No screen/tab-close action was requested by the user.

                        showTaskState();
                    }
                    popup.desktop = tab.desktop; popup.zoomPercent = tab.zoomPercent; desktopSettings(popup); persist();
                    main.post(() -> { if (popup.session.isOpen() && live(popup)) { if (current == popup || working(popup)) activate(popup); else popup.session.setActive(false); changed(); } });
                    return GeckoResult.fromValue(popup.session);
                }
                catch (Exception e) { status = e.getMessage(); changed(); return GeckoResult.fromValue(null); }
            }
            @Override public GeckoResult<String> onLoadError(GeckoSession session, String uri, WebRequestError error) {
                if (!live(tab)) return null;
                // Ignore a late failure from a document superseded by another navigation.
                if (!Objects.equals(uri, tab.loadingUrl) && !tab.acceptedRedirectUrls.contains(uri)) return null;
                tab.loading = false; tab.loadFailed = true; tab.port = null; tab.document = "";
                String message = "페이지를 열지 못했어요. 주소와 연결을 확인해 주세요.";
                Pending p = pending(tab);
                if (p != null && p.tab == tab && valid(p.generation)) finish(p,readinessFailure(p, "page_load_failed", message));
                else if (tab == current && !running() && resultState.equals("idle")) status = message;
                changed(); return null;
            }
        });
        tab.session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession session, String url) { if (!live(tab)) return; tab.navigationEpoch++; tab.loading = true; tab.loadFailed = false; tab.loadingUrl = url; tab.acceptedRedirectUrls.clear(); tab.progress = 0; tab.appliedZoom = -1; tab.zoomFailed = false; tab.port = null; tab.document = "";
                Pending p = pending(tab); if (p != null && p.tab == tab && !p.navigationAction.isEmpty()) p.committedUrl = "";
                changed(); }
            @Override public void onProgressChange(GeckoSession session, int progress) { if (!live(tab)) return; tab.progress = Math.max(0, Math.min(100, progress)); changed(); }
            @Override public void onPageStop(GeckoSession session, boolean success) { if (!live(tab)) return; tab.loading = false; changed(); }
        });
        tab.session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onExternalResponse(GeckoSession session, WebResponse response) {
                if (!live(tab)) { try { if (response.body != null) response.body.close(); } catch (java.io.IOException ignored) { } return; }
                if (workEngine) {
                    if (!disposed && workDownload != null) workDownload.accept(tab, response);
                    else { try { if (response.body != null) response.body.close(); } catch (java.io.IOException ignored) { } }
                } else { final long downloadLease = lane(tab).id;
                    BrowserMusicDownloads.save(context, response, result -> {
                        if (!valid(downloadLease)) return;
                        setTaskStatus(downloadLease, result); record(downloadLease, result);
                    });
                }
            }
            @Override public void onTitleChange(GeckoSession session, String title) { if (!live(tab)) return; tab.title = title; changed(); }
            @Override public void onCloseRequest(GeckoSession session) { close(tab); }
            @Override public void onCrash(GeckoSession session) { if (!live(tab)) return; stopAffected(tab,"페이지가 중단됐어요. 탭을 닫고 다시 열어 주세요."); tab.port = null; tab.document = ""; }
        });
        bind(tab);
        if (open) { tab.needsLoad = true; activate(tab); }
        if (!running()) showTaskState();
        changed(); return tab;
    }
    private void stopAffected(Tab tab, String message) {
        Lane l = tabLanes.get(tab); if (l != null) stopTask(l.id, message);
    }
    void close(Tab tab) {
        if (disposed || tab == null || !tabs.contains(tab)) return;
        if (tab == restoreTab) restoreTabReplaced = true;
        restoring = false; viewEpoch++;
        // Closing an unrelated background tab must not stop another tab's AI job.
        stopAffected(tab,"");
        removeTab(tab);
    }
    private void removeTab(Tab tab) {
        int closedIndex = tabs.indexOf(tab);
        if (closedIndex < 0) return;
        archiveConversation(tab);
        tab.retained = false; tabs.remove(closedIndex); Lane removed=tabLanes.remove(tab);if(removed!=null)lanes.remove(removed.id); tab.port = null; tab.document = ""; releaseDisplay(tab); if (tab.session.isOpen()) tab.session.close();
        if (tabs.isEmpty()) newTab("https://www.google.com/", true);
        else if (current == tab) current = tabs.get(Math.min(closedIndex, tabs.size() - 1));
        showTaskState(); activate(current); persist(); changed();
    }
    void navigate(String value) {
        String url = BrowserTools.url(value); if (current == restoreTab) restoreTabReplaced = true;
        restoring = false; manual(); current.url = url;
        current.needsLoad = true; activate(current); changed();
    }
    void select(Tab tab) {
        if (!live(tab)) return;
        if (current != tab) viewEpoch++; absorbProjection(); current = tab; showTaskState(); activate(tab); persist(); changed();
    }
    static int clampZoom(int percent) { return Math.max(50, Math.min(200, percent)); }
    /** Page layout zoom, separate from system font scale and pinch zoom. Never reloads forms. */
    void setZoom(Tab tab, int percent) {
        if (tab == null || !tabs.contains(tab)) return;
        stop(tab,"직접 조작 중 · AI가 멈췄어요"); tab.settingsChanged = true; tab.appliedZoom = -1; tab.zoomFailed = false; tab.zoomPercent = clampZoom(percent); applyZoom(tab); persist(); changed();
    }
    private void applyZoom(Tab tab) {
        if (tab.port == null) return;
        try { tab.port.postMessage(obj("page_zoom", tab.zoomPercent)); }
        catch (Exception e) { if (tab == current) status = "페이지 연결 뒤 확대·축소를 다시 적용할게요."; }
    }

    void setDesktop(Tab tab, boolean desktop) {
        if (tab == null || !tabs.contains(tab)) return;
        stop(tab,"직접 조작 중 · AI가 멈췄어요"); changeDesktop(tab, desktop); changed();
    }
    private void desktopSettings(Tab tab) {
        tab.session.getSettings().setUserAgentMode(tab.desktop ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
        tab.session.getSettings().setViewportMode(tab.desktop ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
    }
    private void changeDesktop(Tab tab, boolean desktop) {
        tab.settingsChanged = true;
        if (tab.desktop == desktop) return;
        tab.desktop = desktop; desktopSettings(tab); tab.port = null; tab.document = "";
        if (tab.session.isOpen() && !tab.needsLoad) { tab.loadFailed = false; tab.session.reload(); }
        else { tab.needsLoad = true; activate(tab); }
        persist();
    }
    private void activate(Tab tab) {
        if (disposed || (workEngine && !anyRunning()) || restoring || tab == null || (restorePending && tab == restoreTab && !restoreTabReplaced)) return;
        // Do not close inactive sessions: login, history, forms and page work stay alive.
        for (Tab other : tabs) if (other != tab && other.session.isOpen()) {
            // Keep the visible page attached while a tool operates an offscreen tab.
            boolean retained = visible && other == current || rendering(other);
            if (!retained) { other.session.setActive(false); releaseDisplay(other); }
        }
        desktopSettings(tab);
        if (!tab.session.isOpen()) {
            // A popup is opened by Gecko after onNewSession returns, not by us.
            if (!tab.needsLoad) return;
            tab.session.open(runtime);
        }
        tab.session.setActive(visible&&tab==current || rendering(tab) || store.voiceActive); backgroundDisplay(tab);
        if (tab.needsLoad && (extension != null || !extensionError.isBlank())) {
            tab.needsLoad = false; tab.loading = true; tab.loadFailed = false; tab.loadingUrl = tab.url; tab.acceptedRedirectUrls.clear(); tab.progress = 0;
            tab.port = null; tab.document = ""; tab.session.loadUri(tab.url);
        }
    }
    private static String workTaskKey(String scope) { return "browser.work.task.v1." + scope; }
    private String workTaskKey() { return workTaskKey(workScope); }
    /** Runs only on WORK_STORAGE_IO. A stale queued snapshot cannot undo manual inspection. */
    private void saveWorkTask(String snapshot) throws Exception {
        BrowserTaskContext next = new BrowserTaskContext();
        if (!next.restore(snapshot)) throw new IllegalArgumentException("Invalid Work snapshot");
        BrowserTaskContext old = readWorkTask(store, workScope);
        String pendingId = next.pendingMutation().optString("id");
        if (!pendingId.isEmpty() && old.lastMutationId().equals(pendingId) && old.lastMutationState().equals("user_inspected"))
            next.confirmMutationInspected(pendingId);
        store.secrets.put(workTaskKey(), next.snapshot());
        JSONObject guard = readWorkPending(store);
        if (guard != null && guard.optString("job_id").equals(workScope)) {
            String receipt = guard.optString("receipt_id");
            if (next.lastMutationId().equals(receipt) && !next.mutationUnresolved()) {
                store.secrets.put(WORK_PENDING_KEY, "");
            } else if (next.pendingMutation().optString("id").equals(receipt)) {
                JSONObject state = new JSONObject(next.snapshot());
                JSONObject live = state.optJSONObject("liveness");
                if (live != null) put(guard, "reason", live.optString("reason", "awaiting_acknowledgement"));
                String stopped = next.lastResult();
                if (!stopped.isBlank()) put(guard, "stop_reason", stopped.substring(0, Math.min(stopped.length(), BrowserPrompts.REQUEST_LIMIT)));
                store.secrets.put(WORK_PENDING_KEY, guard.toString());
            }
        }
    }
    private void persistTask(Tab tab, Consumer<Boolean> completion) {
        if (!workEngine) { persist(completion); return; }
        if (workScope.isEmpty() || !workTaskWritable || restorePending || tab != restoreTab) {
            if (completion != null) completion.accept(false); return;
        }
        String snapshot = tab.task.snapshot();
        storageIo.execute(() -> {
            boolean saved;
            try { saveWorkTask(snapshot); saved = true; } catch (Exception failure) { saved = false; }
            final boolean durable = saved;
            main.post(() -> { taskStorageWarning = !durable; if (completion != null) completion.accept(durable); changed(); });
        });
    }
    private void settleUnsent(Pending p) {
        if (p == null) return;
        if (p != null && !p.dispatched && !p.dispatchId.isEmpty()
                && p.tab.task.settleMutation(p.dispatchId, false)) {
            p.settlingMutation = workEngine;
            persistTask(p.tab, saved -> { if (workEngine) { p.settlingMutation = false; activeWorkReceipts.remove(p.dispatchId); } });
        } else if (workEngine && !p.settlingMutation) activeWorkReceipts.remove(p.dispatchId);
    }
    private void acknowledgeMutation(Pending p, JSONObject result) {
        if (!p.mutation || !p.dispatched || p.dispatchId.isEmpty()) return;
        // Only this matching bundled-extension reply is an acknowledgement. A later read is not.
        boolean acknowledged = Boolean.TRUE.equals(result.opt("action_accepted"))
            || p.action.equals("vault_fill") && Boolean.TRUE.equals(result.opt("filled")) && Boolean.TRUE.equals(result.opt("ok"));
        boolean rejectedBeforeEffect = Boolean.TRUE.equals(result.opt("stale_observation"));
        if ((acknowledged || rejectedBeforeEffect) && p.tab.task.settleMutation(p.dispatchId, acknowledged)) {
            p.settlingMutation = workEngine;
            persistTask(p.tab, saved -> { if (workEngine) { p.settlingMutation = false; activeWorkReceipts.remove(p.dispatchId); } });
        }
    }
    private static String navigationHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(); for (byte b : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hash.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Trusted extension proposes before dispatch. Native GET replaces, never replays, DOM events. */
    private void acceptReadNavigation(Pending p, JSONObject reply) {
        JSONObject proof = reply.optJSONObject("navigation_proposal");
        String destination = proof == null ? "" : proof.optString("url");
        if (p.navigationProposalAccepted || !p.mutation || !Set.of("click", "press").contains(p.action)
                || !Boolean.FALSE.equals(reply.opt("action_executed")) || proof == null
                || !"GET".equals(proof.optString("method"))
                || p.tab.document.isBlank() || !p.tab.document.equals(proof.optString("document"))
                || p.port != p.tab.port || !dev.jun.gyeot.core.BrowserReadNavigation.safeHref(destination)
                || !p.tab.task.pendingMutation().optString("id").equals(p.dispatchId)) {
            finish(p, error("읽기 이동의 실제 대상을 확인하지 못했어요. 동작을 반복하지 않았어요.")); return;
        }
        p.navigationProposalAccepted = true; p.dispatched = false; p.sends++;
        p.previousDocument = p.tab.document; p.previousUrl = p.tab.url;
        p.navigationTarget = destination; p.navigationAction = "read_get"; p.readReceiptId = p.dispatchId;
        if (!p.tab.task.prepareReadNavigation(p.dispatchId, navigationHash(destination), navigationHash(p.previousDocument))) {
            finish(p, error("읽기 이동 기록이 바뀌어 실행하지 않았어요.")); return;
        }
        persistTask(p.tab, saved -> {
            if (!isPending(p) || !valid(p.generation)) return;
            if (!saved) { finish(p, error("읽기 이동 복구 기록을 저장하지 못해 실행하지 않았어요.")); return; }
            p.dispatched = true; p.sent = false; p.port = null; p.tab.port = null;
            p.tab.url = destination; p.tab.needsLoad = true; p.tab.loadFailed = false;
            record(p.generation, "확인된 공개 링크로 이동하는 중"); activate(p.tab);
            if (isPending(p) && valid(p.generation)) sendWhenReady(p, obj("action", "read"), p.deadline);
        });
    }
    /** Exact receipt, fresh document and native URL only; arbitrary reads cannot settle effects. */
    private boolean recoverReadNavigation(Pending p, JSONObject result) {
        if (p.recoveryChecked || p.readReceiptId.isBlank() || !valid(p.generation)
                || !result.optBoolean("ok") || result.optBoolean("requires_user") || p.port == null || p.port != p.tab.port
                || result.optString("document").isBlank() || !result.optString("document").equals(p.tab.document)
                || result.optJSONArray("elements") == null || !sameNavigationUrl(p.tab.url, p.port.sender.url)
                || !safeUrl(p.tab.url).equals(result.optString("url"))) return false;
        String before = p.tab.task.snapshot();
        if (!p.tab.task.settleReadNavigation(p.readReceiptId, navigationHash(p.tab.url), navigationHash(p.tab.document))) return false;
        p.recoveryChecked = true; p.settlingMutation = workEngine;
        persistTask(p.tab, saved -> {
            p.settlingMutation = false;
            if (!saved) p.tab.task.restoreMutationFence(before);
            else if (workEngine) activeWorkReceipts.remove(p.readReceiptId);
            if (!isPending(p) || !valid(p.generation)) return;
            if (!saved) { finish(p, error("공개 페이지는 열렸지만 복구 기록 저장을 확인하지 못했어요.")); return; }
            put(result, "public_get_navigation_verified", true);
            put(result, "navigation_performed", true); finish(p, result);
        });
        return true;
    }
    private JSONObject mutationBlocked() {
        return obj("ok", false, "requires_user", true, "error_code", "mutation_unresolved", "action_executed", false,
            "action_may_have_run", false, "previous_action_may_have_run", true, "retryable", false,
            "error", workEngine
                ? "이전 Work 작업의 변경 결과가 불확실해 현재 변경은 실행하지 않았어요. 읽기로 상태를 확인한 뒤 브라우저 메뉴의 Work 실행 확인에서 해당 기록을 직접 확인해 주세요."
                : "이 탭의 이전 변경 결과가 불확실해 현재 변경은 실행하지 않았어요. 읽기로 상태를 확인한 뒤 브라우저 메뉴에서 해당 실행 기록을 직접 확인해 주세요.");
    }
    private void prepareDispatch(Pending p, Runnable dispatch) {
        if (!isPending(p) || !valid(p.generation)) return;
        if (p.mutationSaved) { dispatch.run(); return; }
        if (p.preparingMutation) return;
        if (p.tab.task.mutationUnresolved()) { finish(p, mutationBlocked()); return; }
        p.dispatchId = UUID.randomUUID().toString();
        if (!p.tab.task.prepareMutation(p.dispatchId, p.action)) { finish(p, mutationBlocked()); return; }
        Lane owner = lane(p.generation);
        String request = p.action.equals("vault_fill") ? "사용자가 저장된 로그인 정보 입력을 승인함" : owner == null ? "" : owner.lastRequest;
        try { p.tab.task.mutationContext(p.dispatchId, request, safeWorkUrl(p.tab.url)); }
        catch (IllegalArgumentException invalidContext) { settleUnsent(p); finish(p, obj("ok", false, "action_executed", false, "error", "변경 요청의 복구 문맥을 저장할 수 없어 실행하지 않았어요.")); return; }
        if (workEngine) activeWorkReceipts.add(p.dispatchId);
        p.tab.taskChanged = true; p.preparingMutation = true;
        Consumer<Boolean> ready = saved -> {
            p.preparingMutation = false;
            if (!isPending(p) || !valid(p.generation)) return;
            if (!saved) {
                settleUnsent(p);
                finish(p, obj("ok", false, "error_code", "mutation_persistence_failed", "action_executed", false,
                    "retryable", false, "error", "변경 동작의 복구 기록을 저장하지 못해 실행하지 않았어요. 저장 상태를 확인해 주세요.")); return;
            }
            if (!p.tab.task.pendingMutation().optString("id").equals(p.dispatchId)) {
                finish(p, mutationBlocked()); return;
            }
            p.mutationSaved = true; dispatch.run();
        };
        if (!workEngine) { persistTask(p.tab, ready); return; }
        if (workScope.isEmpty() || !workTaskWritable || restorePending || p.tab != restoreTab) { ready.accept(false); return; }
        // Reserve globally, then dispatch only after both encrypted records commit. Serial Work IO
        // means concurrent/new UUID jobs cannot observe an empty guard and both submit effects.
        String snapshot = p.tab.task.snapshot();
        JSONObject record = obj("version", 1, "receipt_id", p.dispatchId, "job_id", workScope,
            "request", request, "url", safeWorkUrl(p.tab.url), "reason", "awaiting_acknowledgement",
            "action", p.action, "state", "awaiting_acknowledgement");
        reserveWorkDispatch(p, snapshot, record, ready);
    }
    private void reserveWorkDispatch(Pending p, String snapshot, JSONObject record, Consumer<Boolean> ready) {
        if (!isPending(p) || !valid(p.generation)) return;
        storageIo.execute(() -> {
            boolean saved = false, blocked = false, waiting = false;
            try {
                JSONObject existing = readWorkPending(store);
                if (existing != null) {
                    waiting = activeWorkReceipts.contains(existing.optString("receipt_id")); blocked = !waiting;
                }
                else {
                    store.secrets.put(workTaskKey(), snapshot);
                    store.secrets.put(WORK_PENDING_KEY, record.toString()); saved = true;
                }
            } catch (Exception failure) { /* No durable reservation, no dispatch. */ }
            final boolean durable = saved, conflict = blocked, awaitActive = waiting;
            main.post(() -> {
                if (!isPending(p) || !valid(p.generation)) return;
                if (awaitActive) {
                    setTaskStatus(p.generation, "다른 Work 동작의 실행 확인을 기다리는 중이에요");
                    main.postDelayed(() -> reserveWorkDispatch(p, snapshot, record, ready), 150);
                } else if (conflict) {
                    p.preparingMutation = false; settleUnsent(p);
                    if (isPending(p) && valid(p.generation)) finish(p, mutationBlocked());
                } else ready.accept(durable);
            });
        });
    }
    private void persist() { persist(null); }
    private void persist(Consumer<Boolean> completion) {
        if (workEngine || restoring || restorePending || !tabsWritable || !transcriptWritable) {
            if (completion != null) completion.accept(false);
            return;
        }
        JSONArray urls = arr();
        for (Tab tab : tabs) try {
            URI u = URI.create(BrowserTools.url(tab.url));
            boolean login = BrowserTools.googleLogin(tab.url);
            // Keep interrupted task memory, never the authentication URL, query or redirect token.
            if (login && tab.task.lastRequest().isBlank() && tab.unreadTask.isBlank() && !tab.task.mutationUnresolved()) continue;
            String restoreUrl = login ? "https://www.google.com/" : BrowserTools.origin(tab.url) + (u.getRawPath() == null ? "/" : u.getRawPath());
            urls.put(obj("id", tab.id, "conversation", tab.conversationId, "url", restoreUrl, "zoom", tab.zoomPercent, "desktop", tab.desktop, "selected", tab == current,
                "task", tab.unreadTask.isEmpty() ? taskSnapshot(tab) : tab.unreadTask));
        } catch (Exception ignored) { taskStorageWarning = true; if (completion != null) completion.accept(false); return; }
        persistence.state(urls.toString(), transcript.snapshot(), saved -> main.post(() -> {
            taskStorageWarning = !saved || tabs.stream().anyMatch(tab -> !tab.unreadTask.isEmpty());
            transcriptStorageWarning = !saved;
            if (completion != null) completion.accept(saved);
            changed();
        }));
    }
    private void bind(Tab tab) {
        if (extension == null) return;
        tab.session.getWebExtensionController().setMessageDelegate(extension, new WebExtension.MessageDelegate() {
            @Override public void onConnect(WebExtension.Port port) {
                if (!live(tab)) { port.disconnect(); return; }
                if (port.sender.session != tab.session || !port.sender.isTopLevel() || !EXTENSION.equals(port.sender.webExtension.id)) { port.disconnect(); return; }
                try { BrowserTools.url(port.sender.url); } catch (Exception e) { port.disconnect(); return; }
                tab.port = port;
                port.setDelegate(new WebExtension.PortDelegate() {
                    @Override public void onPortMessage(Object message, WebExtension.Port source) {
                        if (!live(tab) || !(message instanceof JSONObject json)) return;
                        JSONObject receipt = json.optJSONObject("action_receipt");
                        if (receipt != null) {
                            Pending operation = pending(tab);
                            // A queued receipt from the original port remains valid after navigation.
                            // A new page, retired lease or other operation cannot acknowledge this click.
                            if (operation != null && valid(operation.generation) && operation.dispatched
                                    && operation.dispatchPort == source && operation.id.equals(receipt.optString("id"))
                                    && !operation.dispatchDocument.isBlank() && operation.dispatchDocument.equals(receipt.optString("document"))
                                    && Set.of("click", "press").contains(operation.action) && operation.action.equals(receipt.optString("action"))
                                    && !operation.navigationProposalAccepted && Boolean.TRUE.equals(receipt.opt("action_accepted"))) {
                                acknowledgeMutation(operation, receipt);
                                operation.actionAcknowledged = true;
                            }
                            return; // Dispatch acknowledgement is never a result/page observation.
                        }
                        if (source != tab.port) return;
                        if (json.optBoolean("ready")) { tab.document = json.optString("document"); applyZoom(tab); changed(); return; }
                        if (json.optBoolean("page_zoom")) {
                            tab.zoomFailed = !json.optBoolean("ok");
                            if (json.optBoolean("ok")) tab.appliedZoom = json.optInt("percent", -1);
                            changed();
                            if (!json.optBoolean("ok") && tab == current) { status = "페이지 확대·축소를 적용하지 못했어요."; changed(); }
                            return;
                        }
                        Pending p = pending(tab);
                        if (p != null && p.port == source && p.id.equals(json.optString("id")) && valid(p.generation)) {
                            JSONObject value = json.optJSONObject("result");
                            if (value == null) value = error("페이지 응답을 읽지 못했어요.");
                            if (value.optJSONObject("navigation_proposal") != null) {
                                acceptReadNavigation(p, value); return;
                            }
                            acknowledgeMutation(p, value);
                            if (value.optBoolean("stale_observation") && !p.rereadRejected) {
                                p.rereadRejected = true; p.sends++;
                                record(p.generation,"바뀐 페이지의 요소를 다시 확인하는 중");
                                sendWhenReady(p, obj("action", "read"), p.deadline); return;
                            }
                            if (p.rereadRejected) put(value, "previous_action_rejected", true);
                            if (value.optBoolean("requires_user")) setTaskStatus(p.generation,BrowserTools.observationError(value));
                            put(value, "tab", tab.id); put(value, "desktop", tab.desktop); put(value, "zoom", tab.zoomPercent); finish(p,value); changed();
                        }
                    }
                    @Override public void onDisconnect(WebExtension.Port source) {
                        if (!live(tab)) return;
                        if (tab.port == source) { tab.port = null; tab.document = ""; }
                        Pending p = pending(tab);
                        if (p != null && p.port == source && p.sent && valid(p.generation)) observeNavigation(p);
                    }
                });
            }
        }, NATIVE);
    }
    private void backgroundDisplay(Tab tab) {
        if (visible && tab == current || (!rendering(tab) && !store.voiceActive) || tab.display != null || !tab.session.isOpen()) return;
        // A real offscreen surface keeps layout and viewport-based controls available without an Activity.
        int width = Math.min(1080, context.getResources().getDisplayMetrics().widthPixels);
        int height = Math.min(1920, context.getResources().getDisplayMetrics().heightPixels);
        tab.surface = android.media.ImageReader.newInstance(Math.max(360, width), Math.max(640, height), android.graphics.PixelFormat.RGBA_8888, 2);
        tab.surface.setOnImageAvailableListener(reader -> { try (android.media.Image image = reader.acquireLatestImage()) { } catch (Exception ignored) { } }, main);
        try { tab.display = tab.session.acquireDisplay(); }
        catch (IllegalStateException stillAttached) {
            // GeckoView may still own the old visible tab until its next frame.
            // Leave pending tools intact and retry after the observer releases it.
            tab.surface.close(); tab.surface = null;
            main.postDelayed(() -> { if (live(tab) && working(tab)) backgroundDisplay(tab); }, 50);
            return;
        }
        tab.display.surfaceChanged(new GeckoDisplay.SurfaceInfo.Builder(tab.surface.getSurface()).size(Math.max(360, width), Math.max(640, height)).build());
    }
    private void releaseDisplay(Tab tab) {
        if (tab.display != null) { tab.display.surfaceDestroyed(); tab.session.releaseDisplay(tab.display); tab.display = null; }
        if (tab.surface != null) { tab.surface.close(); tab.surface = null; }
    }
    JSONObject tabList() {
        JSONArray list = arr();
        for (Tab t : tabs) list.put(obj("tab", t.id, "title", BrowserTools.googleLogin(t.url) ? "Google 로그인" : t.title, "url", safeUrl(t.url), "selected", t == current, "desktop", t.desktop, "zoom", t.zoomPercent));
        return obj("ok", true, "tabs", list, "working_tab", current == null ? "" : current.id);
    }
    static String safeUrl(String url) { try { URI u = URI.create(BrowserTools.url(url)); return BrowserTools.origin(url) + (BrowserTools.googleLogin(url) ? "" : u.getRawPath()); } catch (Exception e) { return ""; } }
    static JSONObject error(String message) { return obj("ok", false, "error", message); }
    private static boolean sameNavigationUrl(String left, String right) {
        try { return URI.create(BrowserTools.url(left)).normalize().equals(URI.create(BrowserTools.url(right)).normalize()); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private void navigationCommitted(Tab tab, String url) {
        Pending p = pending(tab);
        if (p == null || p.tab != tab || p.navigationAction.isEmpty() || !valid(p.generation)) return;
        boolean expected = p.navigationAction.equals("read_get") ? sameNavigationUrl(url, p.navigationTarget) : p.navigationAction.equals("open")
            ? sameNavigationUrl(url, p.navigationTarget) || tab.acceptedRedirectUrls.contains(url)
            : !sameNavigationUrl(url, p.previousUrl);
        p.committedUrl = expected ? url : "";
    }
    void execute(JSONObject input, long lease, Consumer<JSONObject> callback) {
        if (!valid(lease)) { callback.accept(error("작업이 중지됐어요.")); return; }
        Lane l=lane(lease);
        if (restorePending) { main.postDelayed(() -> execute(input, lease, callback), 50); return; }
        JSONObject args;
        try { args = BrowserTools.validate(input); } catch (Exception e) { callback.accept(error(e.getMessage())); return; }
        String action = args.optString("action");
        if (action.equals("stop")) { l.paused = true; stopTask(lease,"AI 작업을 중지했어요"); callback.accept(obj("ok", true, "stopped", true)); return; }
        if (l.paused) { callback.accept(obj("ok", false, "requires_user", true, "error", "직접 조작 모드예요. 브라우저에서 AI 계속을 누르거나 새 지시를 입력해 주세요.")); return; }
        if (l.pending != null) { callback.accept(error("이전 브라우저 동작이 끝난 뒤 다시 요청해 주세요.")); return; }
        if (action.equals("tabs")) { JSONObject value = tabList(); put(value, "working_tab", l.owner.id); callback.accept(value); return; }
        Tab tab = l.owner;
        if (!args.optString("tab").isBlank()) { tab = null; for (Tab t : tabs) if (t.id.equals(args.optString("tab"))) tab = t; }
        if (tab == null) { callback.accept(error("탭이 닫혔어요. 탭 목록을 다시 읽어 주세요.")); return; }
        if (tab != l.owner) { callback.accept(obj("ok", false, "error_code", "task_target_mismatch", "action_executed", false, "error", "작업 대상 탭은 바꿀 수 없어요. 해당 탭에서 별도로 지시해 주세요.")); return; }

        boolean observedReadNavigation = action.equals("open") && dev.jun.gyeot.core.BrowserReadNavigation.allowed(tab.lastReadObservation, tab.document, args.optString("url"));
        JSONObject userNavigation = BrowserTools.explicitNavigation(l.lastAsk.isBlank() ? l.lastRequest : l.lastAsk);
        boolean explicitBack = action.equals("back") && args.optBoolean("navigation_only") && tab.back
            && userNavigation != null && "back".equals(userNavigation.optString("action"));
        if (tab.task.mutationUnresolved() && !Set.of("read", "scroll", "switch").contains(action) && !observedReadNavigation && !explicitBack) {
            callback.accept(mutationBlocked()); return;
        }
        // Every task, including an isolated Work browser, retains its original tab.
        // Only explicit native UI selection changes the visible conversation.
        if (action.equals("close")) { close(tab); callback.accept(tabList()); return; }
        if (!extensionError.isBlank()) { callback.accept(obj("ok", false, "error_code", "bridge_install_failed", "error", extensionError)); return; }
        if (action.equals("back") && !tab.back) { callback.accept(obj("ok", false, "error_code", "no_history", "action_executed", false, "retryable", false, "error", "돌아갈 이전 페이지가 없어요. 이동할 공개 HTTPS 홈페이지 주소를 알려 주세요.")); return; }
        Pending p = new Pending(); p.id = Long.toString(++sequence); p.tab = tab; p.generation = lease; p.callback = callback; p.action = action; p.readReceiptId = tab.task.pendingMutation().optString("id");
        if (args.optBoolean("navigation_only") && Set.of("open", "back").contains(action)) {
            p.navigationAction = action; p.previousDocument = tab.document; p.previousUrl = tab.url; p.navigationTarget = args.optString("url");
        }
        p.mutation = Set.of("click", "type", "select", "press").contains(action);
        p.deadline = android.os.SystemClock.uptimeMillis() + 20000;
        tab.automationInput = true;
        l.pending = p; record(lease,BrowserTools.actionLabel(action));
        if (Set.of("open", "back", "forward", "reload", "set_mode").contains(action)) p.dispatched = true;
        if (action.equals("open")) { tab.url = args.optString("url"); tab.needsLoad = true; }
        if (action.equals("set_mode")) changeDesktop(tab, args.optString("mode").equals("desktop"));
        activate(tab);
        if (l.pending != p || !valid(lease)) return; // Native loading can fail synchronously.
        switch (action) {
            case "open" -> { tab.port = null; args = obj("action", "read"); }
            case "back" -> { if (tab.back) { tab.loadFailed = false; tab.port = null; tab.session.goBack(); } args = obj("action", "read"); }
            case "forward" -> { if (tab.forward) { tab.loadFailed = false; tab.port = null; tab.session.goForward(); } args = obj("action", "read"); }
            case "reload" -> { tab.loadFailed = false; tab.port = null; tab.session.reload(); args = obj("action", "read"); }
            case "switch", "set_mode" -> args = obj("action", "read");
        }
        changed(); sendWhenReady(p, args, p.deadline);
    }
    private boolean isPending(Pending p){Lane l=lane(p.generation);return l!=null&&l.pending==p;}
    private void sendWhenReady(Pending p, JSONObject args, long deadline) {
        if (!isPending(p) || !valid(p.generation)) return;
        if (lane(p.generation).paused) { finish(p,error("브라우저 작업이 중지됐어요.")); return; }
        if (!extensionError.isBlank()) { finish(p,obj("ok", false, "error_code", "bridge_install_failed", "error", extensionError)); return; }
        if (p.tab.loadFailed) { finish(p,readinessFailure(p, "page_load_failed", "페이지를 열지 못했어요. 주소와 연결을 확인해 주세요.")); return; }
        // The DOM bridge is ready at document_end. Images/ads may still be loading;
        // they must not hold up reading or interacting with an already available page.
        boolean navigationReady = p.navigationAction.isEmpty() || !p.committedUrl.isBlank() && p.tab.port != null
            && sameNavigationUrl(p.tab.url, p.committedUrl) && sameNavigationUrl(p.tab.port.sender.url, p.committedUrl);
        if (navigationReady && p.tab.port != null && !p.tab.document.isBlank() && !p.tab.needsLoad) {
            if (p.mutation && !args.optString("action").equals("read") && !p.mutationSaved) {
                prepareDispatch(p, () -> sendWhenReady(p, args, deadline)); return;
            }
            p.port = p.tab.port; p.sent = true;
            if (p.mutation && !args.optString("action").equals("read")) {
                p.dispatched = true; p.dispatchPort = p.port; p.dispatchDocument = p.tab.document;
            }
            p.observingNavigation = false;
            int attempt = ++p.sends;
            try { p.port.postMessage(obj("id", p.id, "args", args)); }
            catch (Exception e) { finish(p, obj("ok", false, "action_may_have_run", p.dispatched, "error", "페이지 연결이 바뀌었어요. 동작을 반복하지 말고 먼저 페이지를 확인해 주세요.")); return; }
            main.postDelayed(() -> {
                if (isPending(p) && p.sends == attempt) {
                    if (p.port != p.tab.port && android.os.SystemClock.uptimeMillis() < p.deadline) observeNavigation(p);
                    else finish(p,obj("ok", false, "action_may_have_run", p.dispatched, "error", "페이지 응답을 확인하지 못했어요. 동작을 반복하지 말고 먼저 페이지를 다시 읽어 주세요."));
                }
            }, 6000);
        } else if (android.os.SystemClock.uptimeMillis() >= deadline) finish(p,readinessFailure(p, extension == null ? "bridge_not_ready" : "page_not_ready", "아직 페이지를 읽을 수 없어요. 로딩이 끝난 뒤 다시 요청해 주세요."));
        else main.postDelayed(() -> sendWhenReady(p, args, deadline), 150);
    }
    // Readiness recovery is never permission to replay a dispatched action or a mutation.
    private static JSONObject readinessFailure(Pending p, String code, String message) {
        // Native URL knowledge survives a missing bridge. Never turn a known login
        // screen into permission to navigate away just because its DOM is unavailable.
        if (BrowserTools.googleLogin(p.tab.url) || BrowserTools.googleLogin(p.tab.loadingUrl)
                || p.tab.acceptedRedirectUrls.stream().anyMatch(BrowserTools::googleLogin))
            return obj("ok", false, "requires_user", true, "error_code", "authentication_required",
                "guard_reason", "auth_host", "action_may_have_run", p.dispatched, "error", "인증은 직접 진행해 주세요. 이 화면에서 나가려면 뒤로 가기 또는 공개 HTTPS 홈페이지로 이동을 새로 지시해 주세요.");
        boolean untouchedRead = !p.mutation && p.sends == 0;
        return obj("ok", false, "error_code", untouchedRead ? code : "page_observation_failed",
            "action_may_have_run", p.dispatched, "error", message);
    }
    /** Navigation destroys the old content port. Only read the new document; never replay its click or submission. */
    private void observeNavigation(Pending p) {
        if (!isPending(p) || p.observingNavigation || !valid(p.generation)) return;
        p.observingNavigation = true; p.sent = false; p.sends++; p.port = null;
        record(p.generation,"새 페이지를 확인하는 중");
        sendWhenReady(p, obj("action", "read"), p.deadline);
    }
    // The Activity must capture a token when opening the saved-login chooser.
    // Origin-only approvals cannot distinguish two tabs on the same site.
    void fillVault(String origin, String username, String password, Consumer<JSONObject> result) {
        result.accept(error("저장된 로그인 입력은 대상 탭 확인이 필요해요. 이 버전에서는 직접 입력해 주세요."));
    }
    void fillVault(PromptToken token, String origin, String username, String password, Consumer<JSONObject> result) {
        if (!validPrompt(token) || token.tab.port == null || !BrowserTools.origin(token.tab.url).equals(origin) || !origin.startsWith("https://")) {
            result.accept(error("승인한 HTTPS 탭이 바뀌었어요. 해당 탭에서 다시 확인해 주세요.")); return;
        }
        Tab target = token.tab;
        loginMode(true);
        Pending p = new Pending(); p.tab = target; p.port = target.port;
        p.id = Long.toString(++sequence); p.generation = lease(); p.callback = result;
        p.action = "vault_fill"; p.mutation = true; selected().pending = p;
        PromptToken approved = promptToken(target.session);
        prepareDispatch(p, () -> {
            if (!validPrompt(approved) || target.port != p.port) {
                settleUnsent(p); finish(p, obj("ok", false, "action_executed", false, "error", "승인한 로그인 대상이 바뀌어 입력하지 않았어요.")); return;
            }
            p.dispatched = p.sent = true;
            try { p.port.postMessage(obj("id", p.id, "args", obj("action", "vault_fill", "origin", origin, "username", username, "password", password))); }
            catch (RuntimeException error) { finish(p, obj("ok", false, "action_may_have_run", true, "error", "입력 결과를 확인하지 못했어요. 화면을 확인해 주세요.")); return; }
            main.postDelayed(() -> { if (isPending(p)) finish(p, obj("ok", false, "action_may_have_run", true, "error", "입력 결과를 확인하지 못했어요. 화면을 확인해 주세요.")); }, 5000);
        });
    }
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> needUser(GeckoSession session, GeckoSession.PromptDelegate.BasePrompt prompt) {
        Tab target = null; for (Tab t : tabs) if (t.session == session) target = t;
        Lane captured = target == null ? null : lane(target);
        final long lease = captured == null ? -1 : captured.id;
        main.post(() -> {
            Lane l = lane(lease);
            if (!valid(lease) || l.owner.session != session) return;
            l.paused = true;
            stopTask(lease, "해당 탭에서 웹사이트의 요청을 직접 확인해 주세요");
        });
        return GeckoResult.fromValue(prompt.dismiss());
    }
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> routePrompt(GeckoSession session,
            GeckoSession.PromptDelegate.BasePrompt prompt,
            java.util.function.Supplier<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>> show) {
        if (!liveSession(session) || !visible || current == null || current.session != session || prompts == null)
            return needUser(session, prompt);
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> source = show.get();
        if (source == null) return null;
        // The UI helper may synchronously hand control to the user (retiring the
        // automation lease). Bind the resulting manual session after that handoff.
        PromptToken token = promptToken(session);
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> guarded = new GeckoResult<>();
        source.accept(response -> {
            if (validPrompt(token)) guarded.complete(response);
            else guarded.completeExceptionally(new IllegalStateException("Stale browser prompt target"));
        }, guarded::completeExceptionally);
        return guarded;
    }
    private final GeckoSession.PromptDelegate gatedPrompts = new GeckoSession.PromptDelegate() {
        @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt p) { return routePrompt(s, p, () -> prompts.onAlertPrompt(s, p)); }
        @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt p) { return routePrompt(s, p, () -> prompts.onButtonPrompt(s, p)); }
        @Override public GeckoResult<PromptResponse> onTextPrompt(GeckoSession s, TextPrompt p) { return routePrompt(s, p, () -> prompts.onTextPrompt(s, p)); }
        @Override public GeckoResult<PromptResponse> onAuthPrompt(GeckoSession s, AuthPrompt p) { return routePrompt(s, p, () -> prompts.onAuthPrompt(s, p)); }
        @Override public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession s, PopupPrompt p) { return routePrompt(s, p, () -> prompts.onPopupPrompt(s, p)); }
        @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt p) { return routePrompt(s, p, () -> prompts.onFilePrompt(s, p)); }
        @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession s, ChoicePrompt p) { return routePrompt(s, p, () -> prompts.onChoicePrompt(s, p)); }
    };
    private final GeckoSession.PromptDelegate backgroundPrompts = new GeckoSession.PromptDelegate() {
        @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt p) { return needUser(s, p); }
        @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt p) { return needUser(s, p); }
        @Override public GeckoResult<PromptResponse> onTextPrompt(GeckoSession s, TextPrompt p) { return needUser(s, p); }
        @Override public GeckoResult<PromptResponse> onAuthPrompt(GeckoSession s, AuthPrompt p) { return needUser(s, p); }
        @Override public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession s, PopupPrompt p) { return needUser(s, p); }
        @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt p) { return needUser(s, p); }
        @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession s, ChoicePrompt p) { return needUser(s, p); }
    };
    private void finish(Pending p,JSONObject result) {
        if(p==null||!isPending(p))return;
        if (recoverReadNavigation(p, result)) return;
        settleUnsent(p);lane(p.generation).pending=null;
        if (result.optBoolean("ok") && !result.optBoolean("requires_user") && !result.optString("document").isBlank()
                && result.optString("document").equals(p.tab.document) && result.optJSONArray("elements") != null)
            p.tab.lastReadObservation = copy(result);
        if (p.tab.task.mutationUnresolved() && !p.mutation) {
            put(result, "previous_mutation_unresolved", true);
            put(result, "recovery_rule", "Earlier mutation remains unresolved. Safe reading and observed public GET links may continue; never repeat a submission or infer its success.");
        }
        if (p.mutation && !p.dispatched) put(result, "action_executed", false);
        if (p.actionAcknowledged) put(result, "action_accepted", true);
        if (p.mutation && p.tab.task.mutationUnresolved()) put(result, "action_may_have_run", p.dispatched);
        if (!p.navigationAction.isEmpty()) {
            put(result, "navigation_attempted", true);
            boolean fresh = !p.committedUrl.isBlank() && p.port != null && sameNavigationUrl(p.tab.url, p.committedUrl) && sameNavigationUrl(p.port.sender.url, p.committedUrl)
                && !result.optString("document").isBlank() && !result.optString("document").equals(p.previousDocument)
                && (!p.previousDocument.isBlank() || !sameNavigationUrl(p.previousUrl, p.committedUrl))
                && (result.optBoolean("requires_user") ? BrowserTools.origin(p.committedUrl).equals(result.optString("url")) : safeUrl(p.committedUrl).equals(result.optString("url")))
                && result.optJSONArray("elements") != null;
            put(result, "navigation_performed", fresh);
        }
        p.callback.accept(result);
        settleDisplays();
    }
}



