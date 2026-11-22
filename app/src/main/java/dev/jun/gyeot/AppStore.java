package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import dev.jun.gyeot.core.GeminiProtocol;
import dev.jun.gyeot.core.InterpreterConfig;
import dev.jun.gyeot.core.Mcp;
import dev.jun.gyeot.core.PhoneTools;
import dev.jun.gyeot.core.TaskBook;
import dev.jun.gyeot.core.WorkBook;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import static dev.jun.gyeot.core.Jsons.*;

final class AppStore {
    final TaskBook tasks = new TaskBook();
    /** What each text request used and what each account has left of its limits, metadata only. */
    final UsageLedger usage;
    final MainChatStore mainChat;
    final MainChatEngine mainAssistant;
    long workInterruptVersion; // Main-thread UI invalidation only, never persisted.
    final java.util.Map<String, dev.jun.gyeot.core.WorkInterrupt> workInterrupts = new java.util.concurrent.ConcurrentHashMap<>();
    final dev.jun.gyeot.core.WorkRunState workProgress = new dev.jun.gyeot.core.WorkRunState();
    final java.util.Map<String, String> workStages = new java.util.concurrent.ConcurrentHashMap<>();
    final SecretStore secrets;
    final SharedPreferences settings;
    /** The analysis LLM connection (ChatGPT sign-in or OpenAI API key). */
    final OpenAiAccount openai;
    /** Codex's own OAuth client for image generation with a ChatGPT plan. */
    final CodexImageAccount codexImages;
    final GrokAccount grok;
    /** The user's own Google Drive for Work tools, through Google's sign-in on the phone. */
    final GoogleDriveAccount drive;
    final NimAccount nim;
    private JSONArray videos = new JSONArray();
    private boolean videosUnreadable;
    /** The other analysis LLM: Claude with an Anthropic API key. */
    final ClaudeAccount claude;
    /** Gemini in 작업창, on its own Gemini key or the voice's. */
    final GeminiWorkAccount gemini;
    /** Self-update from GitHub releases. */
    final AppUpdater updater;
    private static final long SEARCH_BLOCK_MS = 6 * 60 * 60 * 1000L, EXHAUSTED_MS = 60 * 60 * 1000L;
    private final File root;
    private final Context diagnosticsContext;
    /** Exact aliases from previously verified, committed migrations; never a path/text replacement rule. */
    private final java.util.Map<String,String> migrationAliases = new java.util.LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();
    private final JSONArray conversation = new JSONArray();
    private JSONArray notes = new JSONArray();
    /** Long-term memory (core/Memory): notes about the user and learned phone know-how, kept only on this phone. */
    private JSONArray memories = new JSONArray();
    private boolean memoriesUnreadable;
    /** 작업창 threads (WorkBook), newest first, and the live text of the run in progress (not saved until it finishes). */
    private JSONArray works = new JSONArray();
    private boolean worksUnreadable;
    /** 예약 작업 (core/WorkSchedules): Work requests that run by themselves at set times. The alarm follows every saved change. */
    private JSONArray schedules = new JSONArray();
    private boolean schedulesUnreadable;
    volatile long schedulesVersion;
    /** 갤러리: the pictures ChatGPT drew (core/Gallery); their small private copies stay while they are listed here. */
    private JSONArray gallery = new JSONArray();
    private boolean galleryUnreadable;
    volatile long galleryVersion;
    final dev.jun.gyeot.core.WorkRoomState workLive = new dev.jun.gyeot.core.WorkRoomState();
    /** 작업창 attachments picked for the next message (main thread only), and messages' attachments handed to the service by key: too big for an Intent. */
    JSONArray workFiles = new JSONArray();
    /** Files picked for the next main-chat message (main thread only): MainAttachments' records, their bytes in mainAttachmentFile. */
    JSONArray mainFiles = new JSONArray();
    /** Another screen sent the user to the main chat to write (a page's 질문하기): the message box takes the keyboard once it shows. */
    boolean mainWrite;
    /** Words another screen put together for the user to send (휴대폰 검사's AI에게 물어보기): into the message box once it shows, unsent. */
    String mainDraft = "";
    /** Where camera frames go while a voice conversation can take them (set by the service; null when it is not running). */
    volatile java.util.function.Consumer<String> cameraInput;
    /** Silences a task result the service is reading aloud on its own speaker (set by the service; null when it is not running). Main thread. */
    volatile Runnable reportStop;
    final java.util.Map<String, JSONArray> workOutbox = new java.util.concurrent.ConcurrentHashMap<>();
    /** The brain is working on think_deeply requests: how many, who answers and since when, for every place that shows it. */
    volatile int thinking; volatile String thinkingBy = ""; volatile long thinkingSince;
    volatile boolean liveReasoning; volatile long liveReasoningSince;
    volatile String connectedVoiceModel = "";
    boolean thinking() { return thinking > 0 || liveReasoning; }
    String thinkingName() { return thinking > 0 ? thinkingBy : "Gemini"; }
    long thinkingStartedAt() { return thinking > 0 ? thinkingSince : liveReasoningSince; }
    /** "Claude가 생각하는 중 · 12초". */
    String thinkingText() { return thinkingName() + "가 생각하는 중 · " + Math.max(0, (System.currentTimeMillis() - thinkingStartedAt()) / 1000) + "초"; }
    /** The thread the 작업창 should show (just sent, or opened from a notification); MainActivity is on screen. */
    volatile String workOpen = "";
    volatile String visibleGalleryImage = "";
    /** Bumped on every saved change to the threads, so screens rebuild only when something changed. */
    volatile long worksVersion;
    volatile boolean appVisible;
    volatile long memoriesVersion;
    /**
     * Saved voice sessions, newest last: {id, mode (assistant/interpreter/dictation), language, startedAt, endedAt, lines:[{who,text,at}]}.
     * The open session is the one with id == openSession; its lines are saved as they arrive.
     */
    private JSONArray sessions = new JSONArray();
    private String openSession = "";
    volatile long sessionsVersion;
    private static final int SESSION_LIMIT = 200, SESSION_LINES = 2000;
    /** Local recordings: {id, title, createdAt, durationMs, size, transcript?}; the audio stays in files/recordings. */
    private JSONArray recordings = new JSONArray();
    /** The recorder is running in AssistantService. */
    volatile boolean recording;
    volatile long recordingStartedAt, recordingsVersion;
    volatile String status = "준비됐어요", error = "", interim = "", outputDraft = "", voiceMode = "assistant", searchHtml = "";
    volatile boolean voiceActive, muted, serviceActive;
    /** Where the voice call's connection is (core/VoiceLink); the orb reads it, never the status text. */
    volatile dev.jun.gyeot.core.VoiceLink voiceLink = dev.jun.gyeot.core.VoiceLink.ENDED;
    /** The voice session's network: offline pause, reconnect budget and cost notices. Main thread only. */
    final dev.jun.gyeot.core.VoiceNetwork voiceNetwork = new dev.jun.gyeot.core.VoiceNetwork();
    /** Read-aloud output, made on first use (no engine starts before something is read). Main thread only. */
    Speech speech;
    /** 누르고 말하기 (ConnectedVoice), made on first use (no recognizer before a press). Main thread only. */
    ConnectedVoice talk;
    /** The main chat's spoken conversation with GPT or Grok while one is on (VoiceChat); main thread. */
    volatile VoiceChat voiceChat;
    /** The call greeting's clips and the warm-up before a call (Greeter), made on first use. Main thread only. */
    private Greeter greeter;
    /** Wall-clock start of the current voice session, for the elapsed-time display. */
    volatile long voiceStartedAt;
    /** One-time notice that Android ended the previous process during a call or task (DiagnosticsExport.readExits); "" when none. */
    volatile String exitNotice = "";
    /** Change counters for the records screen, cheaper than comparing serialized notes or captions. */
    volatile long notesVersion, conversationVersion;
    private boolean scheduled;
    private boolean notesUnreadable, tasksUnreadable;
    boolean scopedModelsUnavailable;
    /** The Decisions API is gone: delete its saved key and switch once, without decrypting the key. */
    private void retireDecisions() {
        try { if (secrets.has(SecretStore.RETIRED_DECISIONS)) secrets.put(SecretStore.RETIRED_DECISIONS, ""); }
        catch (Exception kept) { /* Retried at the next start; the key is never read or used. */ }
        if (settings.contains("decisionsEnabled")) settings.edit().remove("decisionsEnabled").apply();
    }
    /** The Gemini subscription sign-in is gone: delete a saved session and its settings, without decrypting it. */
    private void retireGeminiPlan() {
        try { if (secrets.has(SecretStore.GEMINI_PLAN)) secrets.put(SecretStore.GEMINI_PLAN, ""); }
        catch (Exception kept) { /* Retried at the next start; the session is never read or used. */ }
        android.content.SharedPreferences.Editor edit = settings.edit(); boolean changed = false;
        for (String name : new String[]{"geminiWorkAuth", "geminiPlanModel", "geminiPlanCustom", "geminiPlanModels", "geminiPlanModelsAt", "geminiPlanModelsAccount"})
            if (settings.contains(name)) { edit.remove(name); changed = true; }
        if (changed) edit.apply();
    }
    AppStore(Context context) {
        diagnosticsContext = context.getApplicationContext();
        secrets = new SecretStore(context); settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE); root = context.getFilesDir();
        // An older version turned search off for good on any quota error, even when the model itself was over its limit; start fresh.
        if (settings.contains("searchBlocked")) settings.edit().remove("searchBlocked").apply();
        // Initialize the new default once without replacing an explicitly saved Live model.
        if (!settings.getBoolean("extendedDefault51", false)) {
            SharedPreferences.Editor edit = settings.edit().putBoolean("extendedDefault51", true);
            if (!settings.contains("liveModel")) edit.putString("liveModel", GeminiProtocol.DEFAULT_LIVE);
            edit.apply();
        }
        openai = new OpenAiAccount(context, secrets, settings, this::changed);
        codexImages = new CodexImageAccount(context, secrets, this::changed);
        grok = new GrokAccount(context, secrets, this::changed);
        drive = new GoogleDriveAccount(context, settings, this::changed);
        nim = new NimAccount(secrets, settings, this::changed);
        retireDecisions();
        retireGeminiPlan();
        try {
            File file = new File(root, "videos.json");
            if (file.exists()) { videos = new JSONArray(read(file)); dev.jun.gyeot.core.VideoJobs.recover(videos); write(file, videos.toString()); }
        } catch (Exception e) { videosUnreadable = true; error = "영상 기록을 읽지 못했어요. 원본 기록은 보존했어요."; }
        claude = new ClaudeAccount(secrets, settings, this::changed);
        gemini = new GeminiWorkAccount(secrets, settings, this::changed);
        updater = new AppUpdater(context, secrets, settings, this::changed);
        try { ScopedModels.initialize(this); }
        catch (Exception e) { scopedModelsUnavailable = true; error = "모델 설정을 저장하지 못했어요. 저장 공간을 확인한 뒤 앱을 다시 열어 주세요."; }
        try { File file = new File(root, "tasks.json"); if (file.exists()) tasks.restore(read(file)); persistTasks(); }
        catch (Exception e) { tasksUnreadable = true; error = "작업 기록을 읽지 못했어요. 원본 파일은 보존했어요."; }
        try { File file = new File(root, "notes.json"); if (file.exists()) notes = new JSONArray(read(file)); }
        catch (Exception e) { notesUnreadable = true; error = "메모 기록을 읽지 못했어요. 원본 파일은 보존했어요."; }
        try { File file = new File(root, "memories.json"); if (file.exists()) memories = new JSONArray(read(file)); }
        catch (Exception e) { memoriesUnreadable = true; }
        try {
            File file = new File(root, "works.json");
            if (file.exists()) {
                works = new JSONArray(read(file));
                // Nothing runs across a restart: a run that was cut off is marked so instead of spinning forever.
                boolean migrated = !scopedModelsUnavailable && ScopedModels.migrate(works, ScopedModels.saved(this, ScopedModels.DEFAULTS));
                if (dev.jun.gyeot.core.WorkBook.recover(works, System.currentTimeMillis()) > 0 || migrated) write(file, works.toString());
            }
        } catch (Exception e) { worksUnreadable = true; }
        try {
            File file = new File(root, "schedules.json");
            if (file.exists()) {
                schedules = new JSONArray(read(file));
                // A new process runs nothing yet: a run still marked running was cut off and is never repeated.
                if (dev.jun.gyeot.core.WorkSchedules.interrupted(schedules, s -> false, System.currentTimeMillis()) > 0) write(file, schedules.toString());
            }
        } catch (Exception e) { schedulesUnreadable = true; }
        try {
            File file = new File(root, "gallery.json");
            if (file.exists()) gallery = new JSONArray(read(file));
            else if (!worksUnreadable) {
                // First run with the gallery: list the pictures already drawn in 작업창.
                JSONArray earlier = dev.jun.gyeot.core.Gallery.backfill(works);
                for (int i = 0; i < earlier.length(); i++) dev.jun.gyeot.core.Gallery.add(gallery, earlier.optJSONObject(i));
                write(file, gallery.toString());
            }
        } catch (Exception e) { galleryUnreadable = true; }
        if (!worksUnreadable && !galleryUnreadable) try { cleanAttachments(); } catch (Exception ignored) {}
        try { File file = new File(root, "sessions.json"); if (file.exists()) sessions = new JSONArray(read(file)); }
        catch (Exception ignored) { sessions = new JSONArray(); }
        try {
            File file = new File(root, "recordings.json");
            if (file.exists()) {
                JSONArray stored = new JSONArray(read(file));
                // A recording whose audio file is gone (deleted elsewhere, or an interrupted save) is dropped from the list.
                for (int i = 0; i < stored.length(); i++) if (recordingFile(stored.optJSONObject(i).optString("id")).isFile()) recordings.put(stored.optJSONObject(i));
            }
        } catch (Exception ignored) { recordings = new JSONArray(); }
        try { migrationAliases.putAll(MigrationReferences.loadCommittedAliases(context)); }
        catch (Exception unavailable) { error = "이전 파일 주소 연결 기록을 확인하지 못했어요. 기존 참조는 그대로 두었어요."; }
        usage = new UsageLedger(context);
        mainChat = new MainChatStore(context);
        mainAssistant = new MainChatEngine(context, this, mainChat);
        try { cleanMainAttachments(); } catch (Exception ignored) {}
    }
    String language() { return settings.getString("language", "English"); }
    /**
     * Precision captions: a second, separately billed Transcribe Live connection per assistant call, whose words are the heard
     * words; on by default. A stored choice is kept.
     */
    boolean captions() { return settings.getBoolean("captions", true); }
    boolean announce() { return settings.getBoolean("announce", true); }
    boolean bubble() { return settings.getBoolean("bubble", false); }
    boolean edgeButton() { return settings.getBoolean("edgeButton", false); }
    boolean screenView() { return settings.getBoolean("screenView", false); }
    /**
     * Connectors the user added besides 마이아카이브 (MCP servers): only what is shown and the tool declarations live here;
     * each address and token is in SecretStore as connector.<id>.url / .token.
     */
    JSONArray connectors() { try { return new JSONArray(settings.getString("connectors", "[]")); } catch (Exception e) { return new JSONArray(); } }
    void saveConnectors(JSONArray list) { settings.edit().putString("connectors", list.toString()).apply(); changed(); }
    String connectorSecret(String id, String part) throws Exception { return secrets.get("connector." + id + "." + part); }
    void connectorSecret(String id, String part, String value) throws Exception { secrets.put("connector." + id + "." + part, value == null ? "" : value); }
    /** The connector and tool behind a voice function name, or null. */
    JSONObject connectorTarget(String function) {
        JSONArray list = connectors();
        for (int i = 0; i < list.length(); i++) {
            JSONObject connector = list.optJSONObject(i); JSONArray tools = connector == null ? null : connector.optJSONArray("tools");
            if (tools != null) for (int t = 0; t < tools.length(); t++) {
                JSONObject tool = tools.optJSONObject(t);
                if (tool != null && function.equals(tool.optString("function"))) return obj("id", connector.optString("id"), "name", connector.optString("name"), "tool", tool.optString("tool"));
            }
        }
        return null;
    }
    /** Declarations of all connectors' tools for the voice assistant, up to Mcp.TOOLS_TOTAL. */
    JSONArray connectorDeclarations() {
        JSONArray result = new JSONArray(), list = connectors();
        for (int i = 0; i < list.length(); i++) {
            JSONArray tools = list.optJSONObject(i) == null ? null : list.optJSONObject(i).optJSONArray("tools");
            if (tools != null) for (int t = 0; t < tools.length() && result.length() < Mcp.TOOLS_TOTAL; t++) if (tools.optJSONObject(t) != null) result.put(tools.optJSONObject(t).optJSONObject("declaration"));
        }
        return result;
    }
    /** 마이아카이브 is connected when its address is saved; the address itself stays in SecretStore. */
    boolean archiveConnected() { return secrets.has(SecretStore.ARCHIVE); }
    String archiveAddress() throws Exception {
        String address = secrets.get(SecretStore.ARCHIVE);
        if (address.isBlank()) throw new IllegalStateException("설정 → 커넥터 → 마이아카이브에서 연결 주소를 먼저 넣어 주세요.");
        return address;
    }
    String voice() { return settings.getString("voice", ""); }
    /**
     * 음성 비서 엔진 (설정 → 목소리와 대화): the realtime voice the assistant's calls run on, GPT (OpenAI Realtime) or Grok
     * (xAI Voice Agent), or null for Gemini Live, the default. Interpretation and dictation always run on Gemini.
     */
    dev.jun.gyeot.core.RealtimeVoice.Kind assistantRealtime() { return dev.jun.gyeot.core.RealtimeVoice.Kind.of(settings.getString(ASSISTANT_ENGINE, GEMINI_ENGINE)); }
    /** The stored engine: "gemini", "GPT" or "GROK". */
    String assistantEngine() { dev.jun.gyeot.core.RealtimeVoice.Kind kind = assistantRealtime(); return kind == null ? GEMINI_ENGINE : kind.name(); }
    static final String ASSISTANT_ENGINE = "assistantEngine", GEMINI_ENGINE = "gemini", ASSISTANT_VOICE = "assistantVoice.";
    /** The voice the assistant speaks with on that realtime engine; its own default until one is chosen. */
    String assistantVoice(dev.jun.gyeot.core.RealtimeVoice.Kind kind) { return kind.voice(settings.getString(ASSISTANT_VOICE + kind.name(), kind.voice)); }
    /**
     * The voice chosen for that engine as it was chosen: for GPT one of GPT-Live's newest (한울 until another is chosen) or a
     * Realtime voice. assistantVoice() is the Realtime voice that stands in when GPT-Live does not open (and that 판서's
     * Realtime reading uses).
     */
    String pickedVoice(dev.jun.gyeot.core.RealtimeVoice.Kind kind) {
        String saved = settings.getString(ASSISTANT_VOICE + kind.name(), kind == dev.jun.gyeot.core.RealtimeVoice.Kind.GPT ? "hanul" : kind.voice);
        return dev.jun.gyeot.core.RealtimeVoice.chosen(kind, saved);
    }
    /**
     * Whose 말투 the assistant speaks with: the Gemini voice's (voice()), or on a realtime engine its voice's, kept apart as
     * "GPT.marin" so that each voice keeps its own.
     */
    String styleVoice() {
        dev.jun.gyeot.core.RealtimeVoice.Kind kind = assistantRealtime();
        return kind == null ? voice() : kind.name() + "." + pickedVoice(kind);
    }
    /** Speaking style saved for one voice; "" is the model's default voice. */
    String voiceStyle(String voice) { return settings.getString("voiceStyle:" + (voice == null || voice.isEmpty() ? "default" : voice), ""); }
    void voiceStyle(String voice, String style) {
        String value = style == null ? "" : style.strip();
        if (value.length() > GeminiProtocol.VOICE_STYLE_LIMIT) value = value.substring(0, GeminiProtocol.VOICE_STYLE_LIMIT);
        settings.edit().putString("voiceStyle:" + (voice == null || voice.isEmpty() ? "default" : voice), value).apply();
    }
    /** Live model for voice and research; falls back to the default if the stored id is no longer offered. */
    String liveModel() { return GeminiProtocol.liveModel(settings.getString("liveModel", GeminiProtocol.DEFAULT_LIVE)); }
    /**
     * Each connected AI's model list read again once it is old (a day; GPT and Claude every 6 hours, as before), so newer
     * models show up in every picker without an app update. Called when the app comes to the front; every account checks
     * its own date first, so this is a few settings reads, and at most one small request per AI a day on its own thread.
     */
    void refreshModelListsIfDue() {
        try { if (openai.connected()) openai.refreshModelsIfDue(); } catch (RuntimeException ignored) {}
        try { if (claude.connected()) claude.models(); } catch (RuntimeException ignored) {}
        try { gemini.refreshModelsIfDue(); } catch (RuntimeException ignored) {}
        try { grok.refreshModelsIfDue(); } catch (RuntimeException ignored) {}
        try { nim.refreshModelsIfDue(); } catch (RuntimeException ignored) {}
    }
    String liveThinkingLevel() { return GeminiProtocol.thinkingLevel(settings.getString("liveThinkingLevel", "MEDIUM")); }
    /** 뜻으로 찾기 (SemanticIndex): off until turned on, since it sends what the app keeps to Gemini's embedding model. */
    boolean semanticSearch() { return settings.getBoolean("semanticSearch", false); }
    void semanticSearch(boolean on) { settings.edit().putBoolean("semanticSearch", on).apply(); changed(); }
        boolean translateToKorean() { return settings.getBoolean("translateToKorean", true); }
    InterpreterConfig interpreterConfig() {
        return new InterpreterConfig(settings.getString("translateLanguageA", "ko"),
            settings.getString("translateLanguageB", InterpreterConfig.code(language(), "en")), settings.getString("translateMode", InterpreterConfig.AUTO));
    }
    void interpreterConfig(InterpreterConfig config) {
        settings.edit().putString("translateLanguageA", config.a).putString("translateLanguageB", config.b)
            .putString("translateMode", config.mode).putString("language", InterpreterConfig.name(config.b)).apply();
    }
    String translationDirection() { return interpreterConfig().description(); }
    /**
     * The model sessions actually use: the chosen one, or when it ran out of quota within the last hour, the next offered
     * model that has not (3.8 → 3.1 → 2.5). Null when every model is out of quota.
     */
    String activeModel() {
        for (String model : modelOrder()) if (!exhausted(model)) return model;
        return null;
    }
    List<String> modelOrder() {
        List<String> order = new ArrayList<>(); order.add(liveModel());
        for (String[] model : GeminiProtocol.LIVE_MODELS) if (!order.contains(model[0])) order.add(model[0]);
        return order;
    }
    boolean exhausted(String model) { return System.currentTimeMillis() - settings.getLong("exhaustedAt:" + model, 0) < EXHAUSTED_MS; }
    void markExhausted(String model) { settings.edit().putLong("exhaustedAt:" + model, System.currentTimeMillis()).apply(); changed(); }
    void clearExhausted(String model) { if (settings.contains("exhaustedAt:" + model)) { settings.edit().remove("exhaustedAt:" + model).apply(); changed(); } }
    /** "GPT 음성" (its newest voices on GPT-Live, the others realtime), "Grok 실시간 음성": the voice assistant on that engine. */
    static String realtimeLabel(dev.jun.gyeot.core.RealtimeVoice.Kind kind) { return kind == dev.jun.gyeot.core.RealtimeVoice.Kind.GPT ? "GPT 음성" : kind.label + " 실시간 음성"; }
    static String modelLabel(String model) {
        if (GeminiProtocol.TRANSLATE.equals(model)) return "Gemini 3.5 Live Translate";
        if (GeminiProtocol.TRANSCRIBE.equals(model)) return "Gemini 3.5 Transcribe Live";
        if (dev.jun.gyeot.core.RealtimeVoice.LIVE_MODEL.equals(model)) return "GPT 최신 음성 (GPT-Live)";
        for (String[] offered : GeminiProtocol.LIVE_MODELS) if (offered[0].equals(model)) return offered[1].replaceAll("\\s*\\(.*\\)$", "");
        // A realtime voice assistant's model (never a Live model id: GeminiProtocol.liveModel would take it for the default).
        for (dev.jun.gyeot.core.RealtimeVoice.Kind kind : dev.jun.gyeot.core.RealtimeVoice.Kind.values()) if (kind.model.equals(model)) return realtimeLabel(kind);
        return model;
    }
    /** Google Search grounding: on unless the user turned it off. */
    boolean searchWanted() { return settings.getBoolean("search", true); }
    /**
     * Whether Google refused search for the current Live model on this key: a session with search failed on quota while the
     * same model without search connected. Kept per model and for a few hours only, so a changed limit gets tried again.
     */
    boolean searchBlocked() { String model = activeModel(); return searchBlocked(model == null ? liveModel() : model); }
    boolean searchBlocked(String model) { return System.currentTimeMillis() - settings.getLong("searchBlockedAt:" + model, 0) < SEARCH_BLOCK_MS; }
    /** Google's own words when it refused search for the current model. */
    String searchRefusal() { String model = activeModel(); return settings.getString("searchRefusal:" + (model == null ? liveModel() : model), ""); }
    void blockSearch(String model, String reason) {
        settings.edit().putLong("searchBlockedAt:" + model, System.currentTimeMillis()).putString("searchRefusal:" + model, reason == null ? "" : reason).apply(); changed();
    }
    void retrySearch(String model) { settings.edit().remove("searchBlockedAt:" + model).remove("searchRefusal:" + model).apply(); changed(); }
    /** Who answers searches: "gemini" (Google Search), "chatgpt" or "claude" (their web search). Another one stands in when it fails. */
    String searchProvider() {
        String chosen = settings.getString("searchProvider", "gemini");
        return "chatgpt".equals(chosen) && openai.connected() ? "chatgpt" : "claude".equals(chosen) && claude.connected() ? "claude" : "gemini";
    }
    /** Responses API reasoning effort for ChatGPT analysis; empty keeps the model's default. */
    String reasoningEffort() { return openai.reasoningEffort(openai.model()); }
    /** ChatGPT analysis with the hosted web search tool: on unless turned off, or refused by this connection during this run. */
    /**
     * Who does the thinking while Gemini Live is the voice and the hands: "gpt" (ChatGPT), "claude" (Claude), "gemini"
     * (Gemini Flash on the Gemini key, free within its quota) or "none" (the Live model alone, fastest).
     */
    String brain() {
        String chosen = settings.getString("brain", settings.getBoolean("think", true) ? "gpt" : "gemini");
        return switch (chosen) { case "claude", "gemini", "nim", "none" -> chosen; default -> "gpt"; };
    }
    /** The brain answers: it is chosen and connected. */
    boolean thinkWanted() {
        return switch (brain()) { case "gpt" -> openai.connected(); case "claude" -> claude.connected(); case "gemini" -> secrets.hasKey(); case "nim" -> nim.connected(); default -> false; };
    }
    String brainName() { return switch (brain()) { case "claude" -> "Claude"; case "gemini" -> "Gemini Flash"; case "none" -> "없음"; case "nim" -> dev.jun.gyeot.core.NimProtocol.label(nim.model()); default -> "ChatGPT"; }; }
    /**
     * Gemini 무료 한도: what the app's own Gemini calls used of each model's free tier today (core/GeminiQuota), shared by
     * search, titles, attachments, recordings, video summaries and the Gemini brain (GeminiQuotas).
     */
    synchronized org.json.JSONObject geminiQuota() { try { return new org.json.JSONObject(settings.getString("geminiQuota", "{}")); } catch (Exception e) { return new org.json.JSONObject(); } }
    /** Changes it in one step; listeners hear of it only when told (a count on every answer would redraw every screen). */
    synchronized <T> T changeGeminiQuota(java.util.function.Function<org.json.JSONObject, T> change, boolean tell) {
        org.json.JSONObject state = geminiQuota(); T result = change.apply(state); settings.edit().putString("geminiQuota", state.toString()).apply();
        if (tell) changed();
        return result;
    }
    /**
     * Who takes the longer work (작업창, analysis tasks, recording summaries): "chatgpt", "claude" or "gemini", as chosen when
     * it is connected, else ChatGPT, Claude or Gemini, whichever is; "" when none is.
     */
    String analyst() {
        String chosen = settings.getString("analyst", "claude".equals(brain()) ? "claude" : "chatgpt");
        if (ready(chosen)) return WorkBook.engine(chosen);
        if (settings.contains("analyst")) return ""; // An explicit selection never silently changes provider.
        for (String engine : new String[]{WorkBook.CHATGPT, WorkBook.CLAUDE, WorkBook.GEMINI, WorkBook.GROK, WorkBook.NIM}) if (ready(engine)) return engine;
        return "";
    }
    String analystName() { return WorkBook.author(analyst()); }
    String analystChoice() { return settings.contains("analyst") ? WorkBook.engine(settings.getString("analyst", "chatgpt")) : analyst(); }
    /** That engine is connected for 작업창 and analysis. */
    boolean ready(String engine) {
        return WorkBook.CLAUDE.equals(engine) ? claude.connected() : WorkBook.GEMINI.equals(engine) ? gemini.connected() : WorkBook.GROK.equals(engine) ? grok.connected() : WorkBook.NIM.equals(engine) ? nim.connected() : WorkBook.CHATGPT.equals(engine) && openai.connected();
    }
    /** Web search for that engine: on unless turned off, or refused by its connection during this run. */
    boolean webSearch(String engine) {
        if (WorkBook.NIM.equals(engine)) return false;
        boolean refused = WorkBook.CLAUDE.equals(engine) ? claudeSearchRefused : WorkBook.GEMINI.equals(engine) ? geminiSearchRefused : WorkBook.GROK.equals(engine) ? grokSearchRefused : chatgptSearchRefused;
        return settings.getBoolean("chatgptSearch", true) && !refused;
    }
    volatile boolean claudeSearchRefused, geminiSearchRefused, grokSearchRefused;
    /** 작업창 runs code in ChatGPT's, Claude's or Gemini's sandbox; ChatGPT's and Claude's files are saved to Downloads. */
    boolean workCode() { return settings.getBoolean("workCode", true); }
    final java.util.Map<String, JSONArray> workAgents = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.Map<String, JSONArray> workSwitches = new java.util.concurrent.ConcurrentHashMap<>();
    boolean teamEnabled() { return settings.getBoolean("workTeamEnabled", false); }
    boolean teamFallback() { return settings.getBoolean("workTeamFallback", false); }
    boolean teamAutomatic() { return settings.getBoolean("workTeamAutomatic", false); }
    int teamParallel() { return dev.jun.gyeot.core.TeamPlan.parallel(settings.getInt("workTeamParallel", 4)); }
    JSONArray teamProfiles() {
        try { return dev.jun.gyeot.core.TeamPlan.profiles(new JSONArray(settings.getString("workTeamProfiles", "[]"))); }
        catch (Exception e) { return new JSONArray(); }
    }
    void teamProfiles(JSONArray profiles) {
        settings.edit().putString("workTeamProfiles", dev.jun.gyeot.core.TeamPlan.profiles(profiles).toString()).apply(); changed();
    }

    /** 작업창 lets ChatGPT draw and edit pictures with OpenAI's image generation tool (only when asked). */
    boolean workImages() { return settings.getBoolean("workImages", true); }
    /** 작업창 conversations are filed (category, title, tags) by the AI that answered, after its answer. */
    boolean autoSort() { return settings.getBoolean("autoSort", true); }
    /** 작업창 can work on GitHub: a token is saved; changes need githubWrite on top. */
    boolean githubConnected() { return secrets.has(SecretStore.GITHUB_CODE); }
    boolean githubWrite() { return settings.getBoolean("githubWrite", false); }
    String githubRepo() { return settings.getString("githubRepo", ""); }
    /**
     * A 작업창 room's repository, kept with its model selection (ScopedModels.REPO) and chosen with the picker on its composer
     * (GitHubRepoPicker): "owner/name", or "" for none. A room made before rooms could choose follows 기본 저장소.
     */
    String githubRepo(String room) {
        JSONObject thread = room == null || room.isEmpty() ? null : WorkBook.find(works(), room);
        JSONObject selection = thread == null ? null : thread.optJSONObject(ScopedModels.KEY);
        return selection == null || !selection.has(ScopedModels.REPO) ? githubRepo() : selection.optString(ScopedModels.REPO);
    }
    /** GitHub's tools for the next 작업창 turn, or null when not connected or turned off. Reads the token: off the main thread when possible. */
    dev.jun.gyeot.core.GitHubTools githubTools() {
        if (!githubConnected() || !settings.getBoolean("githubCoding", true)) return null;
        try { String token = secrets.get(SecretStore.GITHUB_CODE); return token.isBlank() ? null : new dev.jun.gyeot.core.GitHubTools(token, githubWrite(), githubRepo()); }
        catch (Exception e) { return null; }
    }
    /**
     * The same for one 작업창 room's turn: the repository its message was sent with (the run's checkpoint keeps the selection
     * it was sent with, as for the model), else the room's own, else 기본 저장소. So a pick made after sending, the lead and
     * its team, and a recovery of the run all agree on one repository.
     */
    dev.jun.gyeot.core.GitHubTools githubTools(String room) {
        dev.jun.gyeot.core.GitHubTools tools = githubTools();
        if (tools == null) return null;
        JSONObject thread = room == null || room.isEmpty() ? null : WorkBook.find(works(), room);
        JSONObject checkpoint = thread == null ? null : dev.jun.gyeot.core.WorkCheckpoint.get(thread);
        JSONObject sent = checkpoint == null ? null : checkpoint.optJSONObject(ScopedModels.KEY);
        String repo = sent != null && sent.has(ScopedModels.REPO) ? sent.optString(ScopedModels.REPO) : githubRepo(room);
        return repo.equals(tools.repo) ? tools : new dev.jun.gyeot.core.GitHubTools(tools.token, tools.write, repo);
    }
    /** The Live model refused a non-blocking think_deeply at setup in this run; later sessions declare it blocking. */
    volatile boolean thinkAsyncRefused;
    /** Gemini 2.5 Flash refused thinkingBudget 0 at setup in this run; later calls leave its thinking as Google sets it. */
    volatile boolean quickAnswerRefused;
    /**
     * A setup setting this Live model refused in the last week (core/LiveRefusals): the chosen voice, background function
     * calls or 2.5 Flash's thinking switched off. Later calls start without it and connect once.
     */
    boolean liveRefused(String kind, String model, String voice) {
        return dev.jun.gyeot.core.LiveRefusals.refused(settings.getString("liveRefusals", ""), dev.jun.gyeot.core.LiveRefusals.key(kind, model, voice), System.currentTimeMillis());
    }
    /** Keeps a refusal once a connection on its model completed its setup without that setting. */
    synchronized void rememberLiveRefusal(String key) {
        settings.edit().putString("liveRefusals", dev.jun.gyeot.core.LiveRefusals.remember(settings.getString("liveRefusals", ""), key, System.currentTimeMillis())).apply();
    }
    /** The user chose this voice: the next call asks every model for it once more, even one that refused it this week. */
    synchronized void forgetVoiceRefusals(String voice) {
        String saved = settings.getString("liveRefusals", "");
        if (saved.isEmpty()) return;
        settings.edit().putString("liveRefusals", dev.jun.gyeot.core.LiveRefusals.forgetVoice(saved, voice)).apply();
    }
    boolean chatgptSearch() { return settings.getBoolean("chatgptSearch", true) && !chatgptSearchRefused; }
    volatile boolean chatgptSearchRefused;
    /** The user allowed the voice assistant to operate the phone (armed per assistant call, see PhoneService). */
    boolean phoneControl() { return settings.getBoolean("phoneControl", false); }
    /** Gemini always operates the phone (the voice and the hands); GPT, when it is the brain, only plans and writes. */
    String phoneProvider() { return PhoneTools.GEMINI; }
    /**
     * Gemini Decision runs voice phone tasks of several steps: off until the user turns it on in
     * 설정 → 폰 조작, and only with a Gemini API key, which it uses.
     */
    boolean phoneDecisions() { return settings.getBoolean("phoneDecisions", false) && secrets.has(SecretStore.GEMINI); }
    /**
     * Gemini Decision picks the AI browser's clicks, scrolls and backs before its selected AI:
     * off until the user turns it on in the browser's model sheet, and only with a Gemini API key, which it uses.
     */
    /** 유튜브 대본을 Gemini 홈페이지에서 받아 오기: on unless the user turned it off (YouTubeGeminiWeb). */
    boolean youtubeGeminiWeb() { return settings.getBoolean("youtubeGeminiWeb", true); }
    boolean browserDecisions() { return settings.getBoolean("browserDecisions", false) && secrets.has(SecretStore.GEMINI); }
    String persona() { return settings.getString("persona", ""); }
    /** 한마디 루틴 (설정 → 한마디 루틴), in the order they were made. */
    JSONArray routines() { return dev.jun.gyeot.core.Routines.parse(settings.getString("routines", "")); }
    void saveRoutines(JSONArray list) { settings.edit().putString("routines", list == null ? "" : list.toString()).apply(); changed(); }
    /** The assistant's name as the user set it in its profile (AssistantProfile), "들퍼레" when unset; for the voice, main-chat and 작업창 instructions. */
    String assistantName() { return AssistantProfile.readName(diagnosticsContext); }
    /** The settings a live call is shaped by (voice, its style, persona, interpreter language), to tell when a call is behind them. */
    String sessionSettings() {
        if (GeminiProtocol.INTERPRETER.equals(voiceMode)) return interpreterConfig().fingerprint();
        dev.jun.gyeot.core.RealtimeVoice.Kind realtime = assistantRealtime();
        return brain() + "\u0000" + liveModel() + "\u0000" + liveThinkingLevel() + "\u0000" + voice() + "\u0000" + voiceStyle(styleVoice()) + "\u0000" + persona() + "\u0000" + settings.getString("routines", "") + "\u0000" + phoneDecisions()
            + "\u0000" + assistantEngine() + "\u0000" + (realtime == null ? "" : pickedVoice(realtime));
    }
    /** sessionSettings() as the current call was connected with; set by the voice service. */
    volatile String appliedSettings = "";
    /** A call is on and was connected before the latest change to its voice, style, persona or language. */
    boolean settingsBehind() { return voiceActive && !GeminiProtocol.DICTATION.equals(voiceMode) && !sessionSettings().equals(appliedSettings); }
    /** Opening the app from its icon or by Bixby ("들퍼레 열어 줘") starts the voice assistant at once; off by default. */
    boolean voiceOnOpen() { return settings.getBoolean("voiceOnOpen", false); }
    /** Make quiet speech easier to catch (sensitive microphone and voice detection); on by default. */
    boolean quietSpeech() { return settings.getBoolean("quietSpeech", true); }
    /** Pause the microphone while 들퍼레 speaks through the phone speaker so it does not hear and interrupt itself. */
    boolean echoGuard() { return settings.getBoolean("echoGuard", true); }
    /** 켤 때 인사하기: the voice assistant greets the moment a call starts, before it is connected (Greeter); on by default. */
    boolean greetOnStart() { return settings.getBoolean("greetOnStart", true); }
    /**
     * 목소리 크기 (core/VoiceLevel), the choice's position: 0 원래대로, 1 고르게 (the default: quiet voices raised, more into a
     * car or a Bluetooth speaker), 2 크게 (always the louder level).
     */
    int voiceLevelChoice() { return Math.max(0, Math.min(2, settings.getInt("voiceLevel", 1))); }
    dev.jun.gyeot.core.VoiceLevel.Mode voiceLevel() {
        return switch (voiceLevelChoice()) { case 0 -> dev.jun.gyeot.core.VoiceLevel.Mode.OFF; case 2 -> dev.jun.gyeot.core.VoiceLevel.Mode.LOUD; default -> dev.jun.gyeot.core.VoiceLevel.Mode.EVEN; };
    }
    /**
     * Gemini text-to-speech (gemini-*-tts): off by default. Its free tier allows a few requests a minute
     * and ten a day, which reading aloud and the start greeting used up. Off, reading aloud uses the phone's own voice, the
     * greeting plays a clip saved earlier or its short tick, and 작업창 offers no voice-file tool. Turning it back on is this
     * setting alone.
     */
    boolean geminiSpeech() { return settings.getBoolean("geminiSpeech", false); }
    /** 음성으로 작업창에 일 시키기: the voice assistant may start, follow and stop 작업창 jobs; off by default. */
    boolean voiceWork() { return settings.getBoolean("voiceWork", false); }
    /** Minutes without speech before the voice connection ends on its own; 0 keeps it open. */
    int autoStopMinutes() { return settings.getInt("autoStop", 10); }
    GeminiProtocol.Options options(boolean inputTranscription) { return options(inputTranscription, activeModel()); }
    /**
     * The options for a session on this model (null: the chosen one). A caller that connects another model than activeModel()
     * asks for it here, so what this model refused before is left out, not what another model refused.
     */
    GeminiProtocol.Options options(boolean inputTranscription, String model) {
        GeminiProtocol.Options options = new GeminiProtocol.Options();
        options.language = language(); options.voice = voice(); options.voiceStyle = voiceStyle(styleVoice()); options.persona = persona(); options.routines = dev.jun.gyeot.core.Routines.instructions(routines()); options.quietSpeech = quietSpeech(); options.model = model == null ? liveModel() : model;
        options.thinkingLevel = liveThinkingLevel(); options.translateToKorean = translateToKorean();
        options.interpreter = interpreterConfig();
        // Built-in Google Search only when Gemini answers searches and this model may ground on this key; otherwise search_web.
        options.search = searchWanted() && "gemini".equals(searchProvider()) && !searchBlocked(options.model);
        options.clock = GeminiProtocol.clock(ZonedDateTime.now()); options.inputTranscription = inputTranscription; options.chatgpt = !analyst().isEmpty(); options.chatgptSearch = webSearch(analyst()); options.analyst = analystName(); options.think = thinkWanted(); options.brain = brainName(); options.thinkAsync = !thinkAsyncRefused; options.quickAnswer = !quickAnswerRefused;
        options.searchDelegate = searchWanted() && !options.search;
        options.browserDelegate = !"none".equals(settings.getString("browserBrain", brain()));
        options.phoneProvider = phoneProvider(); options.phoneDecisions = phoneDecisions();
        options.screenView = screenView();
        options.archive = archiveConnected(); options.connectorTools = connectorDeclarations();
        options.memory = memoryOn(); options.memoryPhone = phoneControl(); options.memoryNotes = memoryOn() ? dev.jun.gyeot.core.Memory.prompt(memories(), phoneControl()) : "";
        options.pinnedNotes = pinnedNotes(); options.assistantName = assistantName(); options.voiceWork = voiceWork();
        // 캘린더: declared only once the calendar can be read, so nothing changes for anyone who never allowed it.
        options.calendar = PhoneCalendar.on(this) && PhoneCalendar.readable(diagnosticsContext);
        // What this model refused at setup before is left out, so the call does not wait for the refusal first.
        if (liveRefused(dev.jun.gyeot.core.LiveRefusals.ASYNC, options.model, "")) options.thinkAsync = false;
        if (liveRefused(dev.jun.gyeot.core.LiveRefusals.QUICK, options.model, "")) options.quickAnswer = false;
        if (!options.voice.isBlank() && liveRefused(dev.jun.gyeot.core.LiveRefusals.VOICE, options.model, options.voice)) options.voice = "";
        return options;
    }
    void observe(Runnable observer) { observers.add(observer); }
    void unobserve(Runnable observer) { observers.remove(observer); }
    void changed() {
        main.post(() -> {
            if (scheduled) return; scheduled = true;
            main.postDelayed(() -> { scheduled = false; for (Runnable observer : observers) observer.run(); }, 100);
        });
    }
    void status(String value) { status = value; changed(); }
    void error(String value) { DiagnosticsExport.record(diagnosticsContext, "app", value); error = value; changed(); }
    Speech speech() { if (speech == null) speech = new Speech(diagnosticsContext, this); return speech; }
    ConnectedVoice talk() { if (talk == null) talk = new ConnectedVoice(diagnosticsContext, this, mainAssistant, SpeechInput.DEVICE); return talk; }
    Greeter greeter() { if (greeter == null) greeter = new Greeter(diagnosticsContext, this); return greeter; }
    /** A voice call, recording or dictation is starting: anything being read aloud stops now, saying why when given. */
    void stopReading(String why) { Speech current = speech; if (current == null) return; if (why == null) current.stop(); else current.interrupt(why); }
    /** A mic in the app is about to listen: a task result being read aloud stops too, so the recognizer does not hear it. */
    void stopReport() { Runnable stop = reportStop; if (stop != null) stop.run(); }
    /** A call line's key for its 다시 듣기 (VoiceClips): when it was written, who said it and what. */
    static String lineKey(JSONObject line) { return line == null ? "" : "call:" + line.optLong("at") + ":" + line.optString("who").hashCode() + ":" + line.optString("text").hashCode(); }
    /** Writes a call line; returns its key (lineKey), or null when there was nothing to write. */
    synchronized String transcript(String who, String text) {
        if (text == null || text.isBlank()) return null;
        JSONObject line = obj("who", who, "text", text.strip(), "at", System.currentTimeMillis());
        conversation.put(line);
        while (conversation.length() > 70) conversation.remove(0);
        conversationVersion++;
        JSONObject open = session(openSession);
        if (open != null && open.optJSONArray("lines").length() < SESSION_LINES) {
            try { open.optJSONArray("lines").put(copy(line)); open.put("endedAt", line.optLong("at")); saveSessionsSoon(2000); } catch (Exception ignored) {}
        }
        changed();
        return lineKey(line);
    }
    /** How long after a call its words still carry into the next one (calling again, or with another voice engine). */
    static final long CARRY_MS = 60 * 60_000L;
    /**
     * The words (no notices) of the voice assistant's calls in the last CARRY_MS before the one now open, oldest first, at most
     * max: a new call picks the conversation up from them.
     */
    synchronized java.util.List<JSONObject> carriedLines(int max) {
        long since = System.currentTimeMillis() - CARRY_MS; java.util.List<JSONObject> out = new java.util.ArrayList<>();
        for (int s = sessions.length() - 1; s >= 0 && out.size() < max; s--) {
            JSONObject session = sessions.optJSONObject(s);
            if (session == null || session.optString("id").equals(openSession) || !GeminiProtocol.ASSISTANT.equals(session.optString("mode"))) continue;
            if (session.optLong("endedAt") < since) break;
            JSONArray lines = session.optJSONArray("lines");
            for (int i = lines == null ? -1 : lines.length() - 1; i >= 0 && out.size() < max; i--) {
                JSONObject line = lines.optJSONObject(i);
                if (line != null && !"안내".equals(line.optString("who")) && line.optLong("at") >= since) out.add(line);
            }
        }
        java.util.Collections.reverse(out); return out;
    }
    /** Starts saving a voice session's lines under its mode; an empty previous session is dropped. */
    synchronized void startSession(String mode, String language) {
        endSession();
        long now = System.currentTimeMillis();
        JSONObject session = obj("id", UUID.randomUUID().toString(), "mode", mode, "language", language, "startedAt", now, "endedAt", now, "lines", new JSONArray());
        if (GeminiProtocol.INTERPRETER.equals(mode)) {
            InterpreterConfig pair = interpreterConfig();
            put(session, "direction", pair.description()); put(session, "languageA", pair.a); put(session, "languageB", pair.b); put(session, "translationMode", pair.mode);
        }
        sessions.put(session); openSession = session.optString("id");
        // Over the limit, the oldest go first; a pinned one stays.
        for (int i = 0; sessions.length() > SESSION_LIMIT && i < sessions.length(); ) { if (sessions.optJSONObject(i).optBoolean("pinned")) i++; else sessions.remove(i); }
        sessionsVersion++;
    }
    /** Whether anything was said in the voice session that ended last. */
    volatile boolean lastCallSpoken;
    /**
     * Whether the user asked for anything in the voice session that ended last (a line of theirs that is more than a word
     * to stop it), and whether they turned it off themselves: the wake word rests longer after one without (core/WakeRest).
     */
    volatile boolean lastCallAsked, lastCallStopped;
    /** Who the user's own words are from in a session's lines. */
    static final String HEARD = "들린 말";
    static boolean asked(JSONObject session) {
        JSONArray lines = session == null ? null : session.optJSONArray("lines");
        for (int i = 0; lines != null && i < lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line != null && HEARD.equals(line.optString("who")) && dev.jun.gyeot.core.WakeRest.asked(line.optString("text"))) return true;
        }
        return false;
    }
    /** Who a line of the app's own is from (a notice about the connection, a tool, the network): not something anyone said. */
    static final String NOTICE = "안내";
    /**
     * Whether anyone said anything in a session: a line that is not the app's own notice. A call that only connected (a
     * wake word that fired by itself, a tap by mistake) holds one notice about the network and nothing else; kept, such calls
     * filled the records with rows of 「0초 · 1줄」.
     */
    static boolean spoken(JSONObject session) {
        JSONArray lines = session == null ? null : session.optJSONArray("lines");
        for (int i = 0; lines != null && i < lines.length(); i++) { JSONObject line = lines.optJSONObject(i); if (line != null && !NOTICE.equals(line.optString("who"))) return true; }
        return false;
    }
    /**
     * Closes the open session; returns a copy of it when something was said in it, for learning from it. A session in which
     * nothing was said is not kept, and earlier ones like it (kept before this rule) go with it.
     */
    synchronized JSONObject endSession() {
        JSONObject open = session(openSession); openSession = "";
        if (open == null) return null;
        boolean said = spoken(open); lastCallSpoken = said; lastCallAsked = asked(open);
        for (int i = sessions.length() - 1; i >= 0; i--) if (!spoken(sessions.optJSONObject(i))) sessions.remove(i);
        pruneSessions();
        saveSessionsSoon(0); sessionsVersion++; changed();
        return said ? copy(open) : null;
    }
    /** Every saved session that has words, oldest first, as copies: for a backup. */
    synchronized JSONArray allSessions() {
        JSONArray result = new JSONArray();
        for (int i = 0; i < sessions.length(); i++) { JSONObject session = sessions.optJSONObject(i); if (session != null && !session.optString("id").equals(openSession) && spoken(session)) result.put(copy(session)); }
        return result;
    }
    /** Adds a backup's sessions this phone does not have (by id), keeps the list oldest first and saves it; returns how many came in. */
    synchronized int mergeSessions(JSONArray incoming) {
        JSONArray next = new JSONArray(); for (int i = 0; i < sessions.length(); i++) next.put(sessions.opt(i));
        int added = dev.jun.gyeot.core.AppBackup.addUnseen(next, incoming, -1, null);
        if (added == 0) return 0;
        sessions = dev.jun.gyeot.core.AppBackup.sorted(next, "startedAt", false);
        saveSessionsSoon(0); sessionsVersion++; changed();
        return added;
    }
    /** Saved sessions of one mode, newest first, as copies. */
    /** One mode's saved sessions with words: the pinned first, then the newest. */
    synchronized JSONArray sessions(String mode) {
        JSONArray result = new JSONArray();
        for (boolean pinned : new boolean[] {true, false})
            for (int i = sessions.length() - 1; i >= 0; i--) { JSONObject session = sessions.optJSONObject(i); if (session.optString("mode").equals(mode) && spoken(session) && session.optBoolean("pinned") == pinned) result.put(copy(session)); }
        return result;
    }
    /** 고정: a pinned session stays at the top and is never cleared by age or the limit. */
    synchronized void pinSession(String id, boolean on) {
        JSONObject session = session(id); if (session == null) return;
        put(session, "pinned", on); saveSessionsSoon(0); sessionsVersion++; changed();
    }
    /** A finished call's title and one-line summary (written by the AI after it ends). */
    synchronized void nameSession(String id, String title, String summary) {
        JSONObject session = session(id); if (session == null || title == null || title.isBlank()) return;
        put(session, "title", title.strip()); put(session, "summary", summary == null ? "" : summary.strip()); saveSessionsSoon(0); sessionsVersion++; changed();
    }
    /** 자동 정리: unpinned sessions older than this many days are cleared (0 keeps them all, up to the limit). */
    static final int[] KEEP_DAYS = {0, 30, 90, 365};
    int sessionKeepDays() { return settings.getInt("sessionKeepDays", 0); }
    synchronized void sessionKeepDays(int days) { settings.edit().putInt("sessionKeepDays", days).apply(); pruneSessions(); saveSessionsSoon(0); sessionsVersion++; changed(); }
    private void pruneSessions() {
        int days = sessionKeepDays(); if (days <= 0) return;
        long before = System.currentTimeMillis() - days * 86_400_000L;
        for (int i = sessions.length() - 1; i >= 0; i--) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null && !session.optString("id").equals(openSession) && !session.optBoolean("pinned") && session.optLong("endedAt") < before) sessions.remove(i);
        }
    }
    /** 이어서 말하기: the session the next call picks up (taken once, by the call that starts). */
    private String resumeSession = "";
    synchronized void continueFrom(String id) { resumeSession = id == null ? "" : id; }
    /** The words (no notices) the next call picks up from the session chosen with continueFrom, at most max of its last; null when none was chosen. */
    synchronized java.util.List<JSONObject> takeResume(int max) {
        String id = resumeSession; resumeSession = "";
        JSONObject session = session(id); if (session == null) return null;
        java.util.List<JSONObject> out = new java.util.ArrayList<>(); JSONArray lines = session.optJSONArray("lines");
        for (int i = lines == null ? -1 : lines.length() - 1; i >= 0 && out.size() < max; i--) { JSONObject line = lines.optJSONObject(i); if (line != null && !"안내".equals(line.optString("who"))) out.add(line); }
        java.util.Collections.reverse(out); return out;
    }
    synchronized boolean deleteSession(String id) throws Exception {
        for (int i = 0; i < sessions.length(); i++) if (sessions.optJSONObject(i).optString("id").equals(id)) {
            if (id.equals(openSession)) openSession = "";
            sessions.remove(i); saveSessionsSoon(0); sessionsVersion++; changed(); return true;
        }
        return false;
    }
    static String sessionText(JSONObject session) {
        StringBuilder text = new StringBuilder(); JSONArray lines = session.optJSONArray("lines");
        if (lines != null) for (int i = 0; i < lines.length(); i++) { JSONObject line = lines.optJSONObject(i); text.append(line.optString("who")).append(": ").append(line.optString("text")).append("\n\n"); }
        return text.toString().strip();
    }
    private JSONObject session(String id) {
        if (id == null || id.isEmpty()) return null;
        for (int i = sessions.length() - 1; i >= 0; i--) if (sessions.optJSONObject(i).optString("id").equals(id)) return sessions.optJSONObject(i);
        return null;
    }
    /** Session lines arrive one by one; the file is written off the main thread, at most every couple of seconds. */
    private void saveSessionsSoon(long delayMs) {
        if (sessionWriteQueued && delayMs > 0) return;
        sessionWriteQueued = true;
        sessionWriter.schedule(() -> {
            String json;
            synchronized (this) {
                sessionWriteQueued = false; json = sessions.toString();
                // Oldest sessions go first when the history outgrows its budget.
                while (json.getBytes(StandardCharsets.UTF_8).length > 3 * 1024 * 1024 && sessions.length() > 1) { sessions.remove(0); json = sessions.toString(); }
            }
            try { write(new File(root, "sessions.json"), json); } catch (Exception e) { error("대화 기록을 저장하지 못했어요."); }
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    private boolean sessionWriteQueued;
    private final java.util.concurrent.ScheduledExecutorService sessionWriter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "gyeot-sessions"); t.setDaemon(true); return t; });
    synchronized JSONArray conversation() {
        try { return new JSONArray(conversation.toString()); } catch (Exception e) { return new JSONArray(); }
    }
    synchronized void clearConversation() { while (conversation.length() > 0) conversation.remove(0); conversationVersion++; changed(); }
    synchronized String conversationText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < conversation.length(); i++) { JSONObject line = conversation.optJSONObject(i); text.append(line.optString("who")).append(": ").append(line.optString("text")).append("\n\n"); }
        return text.toString().strip();
    }
    /** The last phone-control steps (calls, results, nudges), in memory only, so a failure can be read in settings. */
    final dev.jun.gyeot.core.PhoneLogBook phoneLogs = new dev.jun.gyeot.core.PhoneLogBook();
    synchronized void phoneLog(String line) {
        phoneLogs.add(java.time.LocalTime.now().withNano(0) + " " + (line.length() > 160 ? line.substring(0, 160) + "…" : line));
    }
    synchronized String phoneLogText() { return phoneLogs.text(); }
    synchronized void clearPhoneLog() {
        phoneLogs.clear("manual:" + java.util.UUID.randomUUID(),phoneLogs.snapshot(),android.os.SystemClock.elapsedRealtime());
    }
    synchronized boolean canUndoPhoneLog() { return phoneLogs.canUndo(android.os.SystemClock.elapsedRealtime()); }
    synchronized int undoPhoneLog() { return phoneLogs.undo(android.os.SystemClock.elapsedRealtime()); }

    /** Long-term memory on: the assistant reads and keeps notes; auto: it also learns from finished conversations. */
    boolean memoryOn() { return settings.getBoolean("memory", true); }
    boolean autoMemory() { return memoryOn() && settings.getBoolean("autoMemory", true); }
    synchronized JSONArray works() { try { return new JSONArray(works.toString()); } catch (Exception e) { return new JSONArray(); } }
    /**
     * One 작업창 room's model selection (ScopedModels.KEY), copied alone; null when the room or its selection is missing.
     * The composer reads it on every redraw, so it never copies the other rooms and their messages as works() does.
     */
    synchronized JSONObject workSelection(String id) {
        JSONObject thread = id == null || id.isEmpty() ? null : WorkBook.find(works, id);
        JSONObject selection = thread == null ? null : thread.optJSONObject(ScopedModels.KEY);
        return selection == null ? null : ScopedModels.copy(selection);
    }
    /** One 작업창 room's state (WorkBook.RUNNING while it works), "" when it is missing; read without copying the work threads. */
    synchronized String workState(String id) {
        JSONObject thread = id == null || id.isEmpty() ? null : WorkBook.find(works, id);
        return thread == null ? "" : thread.optString("state");
    }
    /**
     * One 작업창 room's messages cut down to what Drawing.chatArguments reads (role, text, drawn pictures and attachment
     * kinds), for the composer's drawing check while a turn runs. Texts are shared, not copied, and the other rooms and the
     * rest of each message (tool logs, file details) are never touched, unlike works().
     */
    synchronized JSONArray workDrawingHistory(String id) {
        JSONObject thread = id == null || id.isEmpty() ? null : WorkBook.find(works, id);
        JSONArray messages = thread == null ? null : thread.optJSONArray("messages"), slim = new JSONArray();
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i); if (message == null) continue;
            try {
                JSONObject copy = new JSONObject().put("role", message.optString("role")).put("text", message.optString("text"));
                JSONArray files = message.optJSONArray("files"), pictures = new JSONArray();
                for (int f = 0; files != null && f < files.length(); f++) {
                    JSONObject file = files.optJSONObject(f);
                    if (file != null && !file.optString("picture").isEmpty()) pictures.put(new JSONObject().put("picture", file.optString("picture")).put("pictureAspect", file.optString("pictureAspect")));
                }
                if (pictures.length() > 0) copy.put("files", pictures);
                JSONArray attachments = message.optJSONArray("attachments"), kinds = new JSONArray();
                for (int a = 0; attachments != null && a < attachments.length(); a++) {
                    JSONObject attachment = attachments.optJSONObject(a);
                    if (attachment != null) kinds.put(new JSONObject().put("kind", attachment.optString("kind")));
                }
                if (kinds.length() > 0) copy.put("attachments", kinds);
                slim.put(copy);
            } catch (Exception e) { /* a message that cannot be copied is left out, as a missing one would be */ }
        }
        return slim;
    }
    synchronized boolean hasWorkSteering(String id, String engine) {
        return dev.jun.gyeot.core.WorkQueue.canSteer(WorkBook.find(works, id), engine);
    }
    /** 작업창 attachments: private copies by opaque id (images made smaller, PDFs and media as they were); never shared with other apps. */
    File attachmentsDir() { File dir = new File(root, "attachments"); dir.mkdirs(); return dir; }
    File attachmentFile(String id) { return new File(attachmentsDir(), WorkBook.safeId(id) ? id : "_"); }
    /**
     * Main-chat attachments: private copies by opaque id, kept for as long as a message keeps
     * them. Apart from 작업창's, whose cleaner never sees them.
     */
    File mainAttachmentsDir() { File dir = new File(root, "main-attachments"); dir.mkdirs(); return dir; }
    File mainAttachmentFile(String id) { return new File(mainAttachmentsDir(), WorkBook.safeId(id) ? id : "_"); }
    /** Deletes copies no main-chat message keeps and no pick waits with; one less than a day old may still be on its way into a message. */
    void cleanMainAttachments() {
        if (mainChat == null || !mainChat.available()) return;
        java.util.Set<String> used = mainChat.fileIds(); File[] files = mainAttachmentsDir().listFiles(); long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        if (files != null) for (File file : files) if (!used.contains(file.getName()) && file.lastModified() < cutoff) file.delete();
    }
    /** Deletes these copies at once (a message or the conversation was deleted, a pick was taken out) unless a message or a pick still has one. Main thread. */
    void releaseMainAttachments(java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        java.util.Set<String> keep = mainChat != null && mainChat.available() ? mainChat.fileIds() : null;
        if (keep == null) return;
        for (int i = 0; i < mainFiles.length(); i++) keep.add(mainFiles.optJSONObject(i).optString("id"));
        for (String id : ids) if (!keep.contains(id)) mainAttachmentFile(id).delete();
    }
    /** Deletes copies no thread or gallery entry refers to any more, sparing a day's worth still waiting to be sent. */
    void cleanAttachments() {
        java.util.Set<String> used = dev.jun.gyeot.core.Gallery.ids(gallery()); JSONArray threads = works();
        for (int i = 0; i < threads.length(); i++) used.addAll(WorkBook.attachmentIds(threads.optJSONObject(i)));
        JSONArray videoJobs = videos(); for (int i = 0; i < videoJobs.length(); i++) { JSONObject job = videoJobs.optJSONObject(i); if (job != null && WorkBook.safeId(job.optString("sourceId"))) used.add(job.optString("sourceId")); }
        File[] files = attachmentsDir().listFiles(); long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        if (files != null) for (File file : files) if (!used.contains(file.getName()) && file.lastModified() < cutoff) file.delete();
    }
    /** Deletes a thread's private copy unless the gallery still shows it, or a gallery picture's copy unless a thread still shows it. */
    void releaseAttachment(String id) {
        if (dev.jun.gyeot.core.Gallery.find(gallery(), id) != null) return;
        JSONArray videoJobs = videos(); for (int i = 0; i < videoJobs.length(); i++) { JSONObject job = videoJobs.optJSONObject(i); if (job != null && id.equals(job.optString("sourceId"))) return; }
        JSONArray threads = works();
        for (int i = 0; i < threads.length(); i++) if (WorkBook.attachmentIds(threads.optJSONObject(i)).contains(id)) return;
        attachmentFile(id).delete();
    }
    synchronized JSONArray gallery() { try { return new JSONArray(gallery.toString()); } catch (Exception e) { return new JSONArray(); } }
    synchronized JSONArray videos() { try { return new JSONArray(videos.toString()); } catch (Exception e) { return new JSONArray(); } }
    synchronized <T> T changeVideos(java.util.function.Function<JSONArray, T> change) throws Exception {
        if (videosUnreadable) throw new IllegalStateException("영상 기록을 읽지 못해 바꾸지 않았어요. 원본 기록은 보존했어요.");
        JSONArray next = new JSONArray(videos.toString()); T result = change.apply(next);
        MigrationReferences.rewrite("videos", next, availableMigrationAliases("videos", next));
        write(new File(root, "videos.json"), next.toString()); videos = next; galleryVersion++; changed(); return result;
    }
    /** Runs one change on a copy of the gallery, then saves it; the change's result is returned. */
    /** The conversation's pictures follow its new name; nothing is written when none changes. */
    void retitlePictures(String thread, String title) {
        try { if (dev.jun.gyeot.core.Gallery.retitle(gallery(), thread, title) > 0) changeGallery(list -> dev.jun.gyeot.core.Gallery.retitle(list, thread, title)); }
        catch (Exception ignored) {}
    }
    synchronized <T> T changeGallery(java.util.function.Function<JSONArray, T> change) throws Exception {
        if (galleryUnreadable) throw new IllegalStateException("갤러리 파일을 읽지 못해 바꾸지 않았어요. 원본 파일은 보존했어요.");
        JSONArray next = new JSONArray(gallery.toString());
        T result = change.apply(next);
        MigrationReferences.rewrite("gallery", next, availableMigrationAliases("gallery", next));
        write(new File(root, "gallery.json"), next.toString()); gallery = next; galleryVersion++; changed();
        return result;
    }
    /** Runs one change on a copy of the work threads, then saves them; the change's result is returned. */
    synchronized <T> T changeWorks(java.util.function.Function<JSONArray, T> change) throws Exception {
        if (scopedModelsUnavailable) throw new java.io.IOException("모델 설정 저장을 복구한 뒤 다시 시도해 주세요.");
        if (worksUnreadable) throw new IllegalStateException("작업 기록 파일을 읽지 못해 바꾸지 않았어요. 원본 파일은 보존했어요.");
        JSONArray next = new JSONArray(works.toString());
        T result = change.apply(next);
        ScopedModels.migrate(next, ScopedModels.saved(this, ScopedModels.DRAFT));
        MigrationReferences.rewrite("works", next, availableMigrationAliases("works", next));
        write(new File(root, "works.json"), next.toString()); works = next; worksVersion++; changed();
        return result;
    }
    synchronized JSONArray schedules() { try { return new JSONArray(schedules.toString()); } catch (Exception e) { return new JSONArray(); } }
    boolean schedulesReadable() { return !schedulesUnreadable; }
    /** Runs one change on a copy of the schedules, saves them, and sets the alarm for the earliest next run. */
    synchronized <T> T changeSchedules(java.util.function.Function<JSONArray, T> change) throws Exception {
        if (schedulesUnreadable) throw new IllegalStateException("예약 기록을 읽지 못해 바꾸지 않았어요. 원본 기록은 보존했어요.");
        JSONArray next = new JSONArray(schedules.toString());
        T result = change.apply(next);
        write(new File(root, "schedules.json"), next.toString()); schedules = next; schedulesVersion++;
        WorkScheduleAlarm.arm(diagnosticsContext, dev.jun.gyeot.core.WorkSchedules.soonest(next));
        changed();
        return result;
    }
    /** All migration changes share the normal store monitor, so concurrent updates are never replaced by stale snapshots. */
    synchronized java.util.Set<String> migrationFileReferences() throws java.io.IOException {
        if (worksUnreadable || galleryUnreadable || videosUnreadable) throw new java.io.IOException("파일 참조 기록을 읽지 못했어요. 원본 기록은 보존해요.");
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        result.addAll(MigrationReferences.references("works", works)); result.addAll(MigrationReferences.references("gallery", gallery)); result.addAll(MigrationReferences.references("videos", videos));
        return java.util.Set.copyOf(result);
    }
    synchronized java.util.Set<String> installMigrationAliases(java.util.Map<String,String> aliases) throws java.io.IOException {
        for (var entry : aliases.entrySet()) if (migrationAliases.containsKey(entry.getKey()) && !migrationAliases.get(entry.getKey()).equals(entry.getValue()))
            throw new java.io.IOException("이전 파일 주소의 새 위치가 서로 달라 참조 변경을 보류했어요.");
        java.util.Set<String> added = new java.util.HashSet<>();
        for (var entry : aliases.entrySet()) if (!migrationAliases.containsKey(entry.getKey())) added.add(entry.getKey());
        migrationAliases.putAll(aliases); return added;
    }
    synchronized void suspendMigrationAliases(java.util.Map<String,String> aliases, java.util.Set<String> added) { for (String key : added) if (java.util.Objects.equals(migrationAliases.get(key),aliases.get(key))) migrationAliases.remove(key); }
    private java.util.Map<String,String> availableMigrationAliases(String collection, JSONArray values) {
        java.util.Map<String,String> result = new java.util.LinkedHashMap<>();
        java.util.Set<String> used = MigrationReferences.references(collection, values);
        java.util.Map<String,Boolean> checked = new java.util.HashMap<>();
        for (var entry : migrationAliases.entrySet()) {
            if (!used.contains(entry.getKey())) continue; // No provider work for already-normalized or unrelated writes.
            boolean available = checked.computeIfAbsent(entry.getValue(), target -> MigrationReferences.targetAvailable(diagnosticsContext, target));
            if (available) result.put(entry.getKey(), entry.getValue());
            else error = "새 위치의 파일 식별을 확인하지 못해 자동 주소 연결을 보류했어요. 기존 참조는 되돌리지 않았어요.";
        }
        return result;
    }
    synchronized int applyMigrationReferences(String collection, java.util.Map<String,String> aliases, java.util.function.BooleanSupplier cancelled) throws Exception {
        JSONArray current;
        switch (collection) {
            case "works" -> { if (worksUnreadable) throw new java.io.IOException("작업 파일 기록을 읽지 못했어요."); current = works; }
            case "gallery" -> { if (galleryUnreadable) throw new java.io.IOException("갤러리 기록을 읽지 못했어요."); current = gallery; }
            case "videos" -> { if (videosUnreadable) throw new java.io.IOException("영상 기록을 읽지 못했어요."); current = videos; }
            default -> throw new java.io.IOException("허용되지 않은 참조 저장소예요.");
        }
        // Receipt-bound metadata only: no ledger lock or file-body hashing while holding the store monitor.
        for (String target : new java.util.HashSet<>(aliases.values())) if (!MigrationReferences.targetAvailable(diagnosticsContext, target))
            throw new java.io.IOException("새 위치의 파일 식별 또는 권한이 바뀌어 참조 변경을 보류했어요.");
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("참조 연결을 취소했어요. 원본은 보존해요.");
        JSONArray next = new JSONArray(current.toString()); int changed = MigrationReferences.rewrite(collection, next, aliases);
        if (changed == 0) return 0;
        File file = new File(root, collection + ".json"); String content = next.toString();
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("참조 연결을 취소했어요. 원본은 보존해요.");
        write(file, content);
        MigrationLedger.syncDirectory(root);
        if (!content.equals(read(file))) throw new java.io.IOException("파일 참조 저장 결과를 확인하지 못했어요. 원본은 보존해요.");
        switch (collection) { case "works" -> { works = next; worksVersion++; } case "gallery" -> { gallery = next; galleryVersion++; } case "videos" -> { videos = next; galleryVersion++; } }
        changed(); return changed;
    }
    /** The stored credential behind engine (a key, or a sign-in with its tokens): only ever hashed, never sent or shown. */
    private String connectionCredential(String engine) throws Exception {
        return WorkBook.CLAUDE.equals(engine) ? secrets.get(SecretStore.CLAUDE)
            : WorkBook.GEMINI.equals(engine) ? gemini.credential()
            : WorkBook.NIM.equals(engine) ? secrets.get(SecretStore.NIM)
            : WorkBook.GROK.equals(engine) ? secrets.get(SecretStore.GROK) : secrets.get(SecretStore.OPENAI);
    }
    /**
     * The connection behind engine for the usage ledger: 16 hex characters of a SHA-256 over the account or key without its
     * rotating tokens (ConnectionIdentity), with a prefix of its own so it matches no other hash. Unlike
     * workConnectionFingerprint it does not change with GitHub settings. "" when not connected. Kept per engine until what
     * is stored for its credential changes (SecretStore.stamp), so a redraw decrypts nothing.
     */
    String usageIdentity(String engine) {
        String stamp = credentialStamp(engine); String[] known = usageIdentities.get(engine);
        if (known != null && known[0].equals(stamp)) return known[1];
        try {
            String credential = connectionCredential(engine), identity = "";
            if (credential != null && !credential.isBlank()) {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(("gyeot-usage\n" + engine + "\n" + dev.jun.gyeot.core.ConnectionIdentity.stable(credential)).getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(); for (int i = 0; i < 8; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
                identity = hex.toString();
            }
            usageIdentities.put(engine, new String[]{stamp, identity});
            return identity;
        } catch (Exception e) { return ""; }
    }
    /** usageIdentity per engine, with the stamp of the stored credential it was worked out from. */
    private final java.util.Map<String, String[]> usageIdentities = new java.util.concurrent.ConcurrentHashMap<>();
    /** What is stored for engine's credential, still encrypted (SecretStore.stamp); Gemini's depends on which of its three it uses. */
    private String credentialStamp(String engine) {
        return WorkBook.CLAUDE.equals(engine) ? secrets.stamp(SecretStore.CLAUDE)
            : WorkBook.GEMINI.equals(engine) ? secrets.stamp(SecretStore.GEMINI_WORK) + "|" + secrets.stamp(SecretStore.GEMINI)
            : WorkBook.NIM.equals(engine) ? secrets.stamp(SecretStore.NIM)
            : WorkBook.GROK.equals(engine) ? secrets.stamp(SecretStore.GROK) : secrets.stamp(SecretStore.OPENAI);
    }
    /** How engine's requests are paid: a subscription sign-in (ChatGPT plan, Grok) or an API key. */
    String usageRoute(String engine) {
        return switch (WorkBook.engine(engine)) {
            case WorkBook.GROK -> UsageLedger.PLAN;
            case WorkBook.GEMINI, WorkBook.CLAUDE, WorkBook.NIM -> UsageLedger.KEY;
            default -> openai.usesPlan() ? UsageLedger.PLAN : UsageLedger.KEY;
        };
    }
    /** One-way connection binding, never the credential itself. Rotation conservatively requires a new instruction. */
    String workConnectionFingerprint(String engine) {
        try {
            String credential = connectionCredential(engine);
            if (credential.isBlank()) return "";
            // The account or key, not the sign-in's rotating tokens: a refresh mid-reply is still the same connection.
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest((engine + "\n" + dev.jun.gyeot.core.ConnectionIdentity.stable(credential) + "\n" + secrets.get(SecretStore.GITHUB_CODE) + "\n" + githubRepo() + "\n" + settings.getBoolean("githubCoding", true)).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (Exception e) { return ""; }
    }
    /** A separate revocable resume ticket: removing it does not need free space for rewriting works.json. */
    synchronized void allowWorkResume(String run) throws Exception {
        if (!run.matches("[A-Za-z0-9-]{1,80}")) throw new IllegalArgumentException("Invalid run identity");
        write(new File(root, "work-resume-" + run), "1");
    }
    synchronized boolean canResumeWork(JSONObject thread) {
        JSONObject checkpoint = dev.jun.gyeot.core.WorkCheckpoint.get(thread);
        String run = checkpoint == null ? "" : checkpoint.optString("run");
        return !settings.getBoolean("workResumeBlocked", false) && run.matches("[A-Za-z0-9-]{1,80}")
            && new File(root, "work-resume-" + run).isFile();
    }
    synchronized void revokeWorkResume(JSONObject thread) throws Exception {
        JSONObject checkpoint = dev.jun.gyeot.core.WorkCheckpoint.get(thread);
        if (checkpoint == null) return;
        String run = checkpoint.optString("run");
        if (!run.matches("[A-Za-z0-9-]{1,80}")) return;
        File ticket = new File(root, "work-resume-" + run);
        if (ticket.exists() && !ticket.delete()) {
            boolean blocked = settings.edit().putBoolean("workResumeBlocked", true).commit();
            throw new java.io.IOException(blocked ? "자동 복구를 전체 차단했어요. 저장 공간을 확인해 주세요." : "지금은 멈췄지만 재시작 후 자동 복구 차단을 저장하지 못했어요. 저장 공간을 확인해 주세요.");
        }
    }
    synchronized void workCheckpoint(String id, String run, String partial, String tool, String target, String outcome) throws Exception {
        if (worksUnreadable) throw new java.io.IOException("작업 기록을 읽지 못했어요.");
        JSONArray next = new JSONArray(works.toString()); JSONObject thread = WorkBook.find(next, id);
        if (partial != null) dev.jun.gyeot.core.WorkCheckpoint.partial(thread, run, partial, System.currentTimeMillis());
        if (tool != null) dev.jun.gyeot.core.WorkCheckpoint.receipt(thread, run, tool, target, outcome, System.currentTimeMillis());
        write(new File(root, "works.json"), next.toString()); works = next;
    }
    /** Persist team checkpoints without rebuilding the conversation or losing its scroll position. */
    synchronized void teamCheckpoint(String id, JSONArray agents, JSONArray switches) throws Exception {
        if (worksUnreadable) throw new IllegalStateException("작업 기록을 읽지 못했어요.");
        JSONArray next = new JSONArray(works.toString()); JSONObject thread = WorkBook.find(next, id);
        if (thread == null || !WorkBook.RUNNING.equals(thread.optString("state"))) return;
        if (agents != null) put(thread, "activeAgents", agents);
        if (switches != null) put(thread, "activeSwitches", switches);
        write(new File(root, "works.json"), next.toString()); works = next;
        if (agents != null) workAgents.put(id, agents);
        if (switches != null) workSwitches.put(id, switches);
        changed();
    }
    synchronized JSONArray memories() { try { return new JSONArray(memories.toString()); } catch (Exception e) { return new JSONArray(); } }
    /** Runs one change on a copy of the memory, then saves it; the change's result is returned. */
    synchronized <T> T changeMemory(java.util.function.Function<JSONArray, T> change) throws Exception {
        if (memoriesUnreadable) throw new IllegalStateException("기억 파일을 읽지 못해 바꾸지 않았어요. 원본 파일은 보존했어요.");
        JSONArray next = new JSONArray(memories.toString());
        T result = change.apply(next);
        write(new File(root, "memories.json"), next.toString()); memories = next; memoriesVersion++; changed();
        return result;
    }
    /**
     * Notes about the user for one main-chat question (core/Memory.pick), within budget characters; "" with long-term
     * memory off. Scored on a copy, outside the store's lock; the notes that matched are then marked used (markUsed).
     */
    String recallMemory(String query, int budget) {
        if (!memoryOn()) return "";
        dev.jun.gyeot.core.Memory.Recall recall = dev.jun.gyeot.core.Memory.pick(memories(), query, budget);
        markUsed(recall.matched());
        return recall.text();
    }
    /** Marks these notes used (Memory.used) in one short locked step, saved without redrawing anything (no screen shows the time). */
    synchronized void markUsed(java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty() || memoriesUnreadable) return;
        // In place: a failed write loses only usage times, and every reader gets a copy.
        if (dev.jun.gyeot.core.Memory.used(memories, ids, System.currentTimeMillis()) == 0) return;
        try { write(new File(root, "memories.json"), memories.toString()); }
        catch (Exception unsaved) { /* The notes still go along; only their usage times are not kept. */ }
    }
    /**
     * The main chat's pinned notes ("kind: text" lines) that also go to the voice assistant and 작업창; "" with long-term
     * memory off or the chat store closed. MainChatStore keeps the text current, so this never waits on its database.
     */
    String pinnedNotes() { return memoryOn() && mainChat != null ? mainChat.pinned() : ""; }
    /** When long-term memory was last turned on (설정 → 기억): every room picks its notes again after that (WorkBook.memory). */
    long memoryEpoch() { return settings.getLong("memoryOnAt", 0); }
    /**
     * What a 작업창 request carries about the user after its fixed instructions (WorkBook.background): "나에 대해" without
     * its secret-looking lines, and with long-term memory on the pinned notes and the automatic memories the room picked
     * (WorkBook.memory), within 2% of the model's window. "" when the room's 기억 사용 is off. Scheduled runs are rooms
     * too. thread is the caller's snapshot; it gets the room's pick as well.
     */
    String workBackground(JSONObject thread, String engine, String model) {
        if (!WorkBook.remembers(thread)) return "";
        String notes = "";
        if (memoryOn()) {
            String before = String.valueOf(thread.opt(WorkBook.PICKED));
            // Scored on a copy, outside the store's lock; a new pick is kept on the room so the next turn sends the same notes.
            dev.jun.gyeot.core.Memory.Recall recall = WorkBook.memory(thread, memories(), dev.jun.gyeot.core.Memory.budget(dev.jun.gyeot.core.ContextWindow.chars(engine, model)), memoryEpoch());
            if (!before.equals(String.valueOf(thread.opt(WorkBook.PICKED)))) keepMemoryPick(thread.optString("id"), thread.optJSONObject(WorkBook.PICKED));
            markUsed(recall.matched());
            notes = recall.text();
        }
        return WorkBook.background(persona(), pinnedNotes(), notes);
    }
    /** Keeps the notes a room picked (WorkBook.memory) without redrawing anything: no screen shows them. */
    synchronized void keepMemoryPick(String id, JSONObject pick) {
        if (worksUnreadable || pick == null) return;
        try {
            JSONArray next = new JSONArray(works.toString()); JSONObject thread = WorkBook.find(next, id);
            // 기억 사용 turned off meanwhile: the room keeps no pick.
            if (thread == null || !WorkBook.remembers(thread)) return;
            put(thread, WorkBook.PICKED, copy(pick)); write(new File(root, "works.json"), next.toString()); works = next;
        } catch (Exception unsaved) { /* The next request picks again. */ }
    }
    synchronized JSONObject saveNote(String title, String body) throws Exception {
        if (notesUnreadable) throw new IllegalStateException("기존 메모 기록을 읽지 못해 덮어쓰지 않았어요.");
        title = title == null ? "" : title.strip(); body = body == null ? "" : body.strip();
        if (body.isEmpty() || body.length() > 80000 || title.length() > 160) throw new IllegalArgumentException("메모는 제목 160자·본문 80,000자 이내로 작성해 주세요.");
        JSONArray next = new JSONArray(notes.toString());
        JSONObject note = obj("id", UUID.randomUUID().toString(), "title", title.isEmpty() ? "음성 메모" : title, "body", body, "createdAt", System.currentTimeMillis());
        next.put(note);
        if (next.toString().getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024) throw new IllegalStateException("메모 보관 용량을 초과했어요. 기존 메모를 먼저 별도로 보관해 주세요.");
        write(new File(root, "notes.json"), next.toString()); notes = next; notesVersion++; changed(); return copy(note);
    }
    /** Adds a backup's notes this phone does not have (by id), within the notes' size limit, and saves; returns how many came in. */
    synchronized int mergeNotes(JSONArray incoming) throws Exception {
        if (notesUnreadable) throw new IllegalStateException("기존 메모 기록을 읽지 못해 변경하지 않았어요.");
        JSONArray next = new JSONArray(notes.toString());
        int added = dev.jun.gyeot.core.AppBackup.addUnseen(next, incoming, -1, null);
        if (added == 0) return 0;
        if (next.toString().getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024) throw new IllegalStateException("메모 보관 용량을 넘어서 메모는 가져오지 않았어요.");
        write(new File(root, "notes.json"), next.toString()); notes = next; notesVersion++; changed();
        return added;
    }
    synchronized boolean deleteNote(String id) throws Exception {
        if (notesUnreadable) throw new IllegalStateException("기존 메모 기록을 읽지 못해 변경하지 않았어요.");
        JSONArray next = new JSONArray();
        for (int i = 0; i < notes.length(); i++) if (!notes.optJSONObject(i).optString("id").equals(id)) next.put(notes.optJSONObject(i));
        if (next.length() == notes.length()) return false;
        write(new File(root, "notes.json"), next.toString()); notes = next; notesVersion++; changed(); return true;
    }
    synchronized JSONArray notes() {
        try { return new JSONArray(notes.toString()); } catch (Exception e) { return new JSONArray(); }
    }
    /** Notes whose title or body contains every word of the query, oldest first. */
    synchronized JSONArray notes(String query) {
        String[] words = query == null ? new String[0] : query.toLowerCase(Locale.ROOT).strip().split("\\s+");
        JSONArray result = new JSONArray();
        outer:
        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            String text = (note.optString("title") + "\n" + note.optString("body")).toLowerCase(Locale.ROOT);
            for (String word : words) if (!word.isEmpty() && !text.contains(word)) continue outer;
            result.put(copy(note));
        }
        return result;
    }
    File recordingsDir() { File dir = new File(root, "recordings"); dir.mkdirs(); return dir; }
    File recordingFile(String id) { return new File(recordingsDir(), (id == null ? "" : id.replaceAll("[^A-Za-z0-9-]", "")) + ".m4a"); }
    synchronized JSONObject addRecording(String id, long createdAt, long durationMs) throws Exception {
        File audio = recordingFile(id);
        String title = "녹음 " + java.time.format.DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN).format(java.time.Instant.ofEpochMilli(createdAt).atZone(java.time.ZoneId.systemDefault()));
        JSONObject entry = obj("id", id, "title", title, "createdAt", createdAt, "durationMs", durationMs, "size", audio.length());
        JSONArray next = new JSONArray(recordings.toString()); next.put(entry);
        write(new File(root, "recordings.json"), next.toString()); recordings = next; recordingsVersion++; changed(); return copy(entry);
    }
    synchronized JSONArray recordings() { try { return new JSONArray(recordings.toString()); } catch (Exception e) { return new JSONArray(); } }
    synchronized JSONObject recording(String id) {
        for (int i = 0; i < recordings.length(); i++) if (recordings.optJSONObject(i).optString("id").equals(id)) return copy(recordings.optJSONObject(i));
        return null;
    }
    synchronized void updateRecording(String id, String key, Object value) throws Exception {
        JSONArray next = new JSONArray(recordings.toString()); boolean found = false;
        for (int i = 0; i < next.length(); i++) if (next.optJSONObject(i).optString("id").equals(id)) { next.optJSONObject(i).put(key, value); found = true; }
        if (!found) return;
        write(new File(root, "recordings.json"), next.toString()); recordings = next; recordingsVersion++; changed();
    }
    synchronized boolean deleteRecording(String id) throws Exception {
        JSONArray next = new JSONArray();
        for (int i = 0; i < recordings.length(); i++) if (!recordings.optJSONObject(i).optString("id").equals(id)) next.put(recordings.optJSONObject(i));
        if (next.length() == recordings.length()) return false;
        write(new File(root, "recordings.json"), next.toString()); recordings = next; recordingFile(id).delete(); recordingsVersion++; changed(); return true;
    }
    void requireTaskStorage() { if (tasksUnreadable) throw new IllegalStateException("기존 작업 기록을 읽지 못해 새 작업으로 덮어쓰지 않았어요."); }
    void persistTasks() throws Exception { requireTaskStorage(); write(new File(root, "tasks.json"), tasks.serialize()); }
    boolean removeTask(String id) throws Exception { requireTaskStorage(); boolean removed = tasks.remove(id); if (removed) { persistTasks(); changed(); } return removed; }
    int clearFinishedTasks() throws Exception { requireTaskStorage(); int removed = tasks.clearFinished(); if (removed > 0) { persistTasks(); changed(); } return removed; }
    private static String read(File file) throws Exception {
        if (file.length() > 8 * 1024 * 1024) throw new IllegalStateException("기록 크기 제한을 초과했어요.");
        return new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8);
    }
    private static synchronized void write(File file, String content) throws Exception {
        AtomicFile atomic = new AtomicFile(file); FileOutputStream output = null;
        try { output = atomic.startWrite(); output.write(content.getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(output); }
        catch (Exception error) { if (output != null) atomic.failWrite(output); throw error; }
    }
}




