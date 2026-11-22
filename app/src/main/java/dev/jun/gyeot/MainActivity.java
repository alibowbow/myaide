package dev.jun.gyeot;

import android.Manifest;
import dev.jun.gyeot.core.NimProtocol;
import dev.jun.gyeot.core.MainAttachments;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.jun.gyeot.core.Archive;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.Gallery;
import dev.jun.gyeot.core.GeminiProtocol;
import dev.jun.gyeot.core.GeminiSearch;
import dev.jun.gyeot.core.GeminiWork;
import dev.jun.gyeot.core.Markdown;
import dev.jun.gyeot.core.Mcp;
import dev.jun.gyeot.core.Net;
import dev.jun.gyeot.core.OpenAiProtocol;
import dev.jun.gyeot.core.Drawing;
import dev.jun.gyeot.core.Reasoning;
import dev.jun.gyeot.core.RealtimeVoice;
import dev.jun.gyeot.core.Sessions;
import dev.jun.gyeot.core.PhoneTools;
import dev.jun.gyeot.core.TaskBook;
import dev.jun.gyeot.core.WorkBook;
import dev.jun.gyeot.core.VideoJobs;
import dev.jun.gyeot.core.GrokVideo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import static dev.jun.gyeot.Ui.*;
import static dev.jun.gyeot.core.GeminiProtocol.ASSISTANT;
import static dev.jun.gyeot.core.GeminiProtocol.DICTATION;
import static dev.jun.gyeot.core.GeminiProtocol.INTERPRETER;
import static dev.jun.gyeot.core.Jsons.obj;

public final class MainActivity extends Activity {
    /** Set by QuickVoiceActivity when it could not start voice by itself (no key or microphone permission yet). */
    static final String START_VOICE = "startVoice";
    private static final String[] HOME_MODE_NAMES = {"음성 비서 ▾", "통역", "받아쓰기", "녹음"}, MODE_IDS = {ASSISTANT, INTERPRETER, DICTATION};
    /** GeminiProtocol.VOICES as the picker shows them: Google's one-word characteristic of each, in Korean. */
    private static final String[] VOICE_LABELS = {"기본 (모델 선택)", "Aoede · 산뜻한 여성", "Leda · 젊고 밝은 여성", "Zephyr · 밝은 여성", "Kore · 단단한 여성", "Sulafat · 따뜻한 여성", "Achernar · 부드러운 여성", "Vindemiatrix · 다정한 여성", "Despina · 매끄러운 여성", "Callirrhoe · 느긋한 여성", "Autonoe · 밝은 여성", "Erinome · 맑은 여성", "Laomedeia · 경쾌한 여성", "Gacrux · 성숙한 여성", "Pulcherrima · 당찬 여성", "Puck · 경쾌한 남성", "Charon · 또렷한 남성", "Fenrir · 들뜬 남성", "Orus · 단단한 남성", "Enceladus · 숨결 섞인 남성", "Iapetus · 맑은 남성", "Umbriel · 느긋한 남성", "Algieba · 매끄러운 남성", "Algenib · 걸걸한 남성", "Rasalgethi · 또렷한 남성", "Alnilam · 단단한 남성", "Schedar · 차분한 남성", "Achird · 친근한 남성", "Zubenelgenubi · 편한 남성", "Sadachbia · 활기찬 남성", "Sadaltager · 박식한 남성"};
    private static final int[] AUTO_STOP = {0, 5, 10, 20, 30};
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN);
    private AppStore store;
    private LinearLayout grokBox, nimBox;
    private String lastGrok = "", lastNim = "";
    /** 설정 → 사용량 · 남은 한도 while it shows, and what it last drew (UsageUi.fingerprint). */
    private LinearLayout usageBox; private String lastUsage = "";
    private Ui ui;
    private FrameLayout root;
    private MainNavigation navigation;
    private FrameLayout contentSlot;
    private MainChatPage mainChat;
    private Bundle mainChatSaved;
    private boolean showingMainChat, hostStarted, hostResumed, pendingLegacyRecovery;
    private int chatReturnTab;
    private boolean pendingLaunchVoice;
    /** navRow: the back capsule above the large title on a settings page; empty and hidden elsewhere. */
    private LinearLayout navRow;
    private LinearLayout body, header, statusRow, tabBar, workLog, sayRow, recordsList, openAiBox, claudeBox, geminiWorkBox, updateBox;
    /** Settings → Gemini: the Google subscription card while that page is open. */
    private ScrollView scroll;
    private TextView titleView, status, error, applyBanner, exitBanner, taskSummary, updateBanner, edgeState, assistState, archiveState;
    private View statusDot;
    /** The home screen's voice orb and its two lines of text; the recorder tile's state line. */
    private VoiceOrb orb;
    private TextView orbTitle, orbSub, recordButton;
    /** The open settings page ("" is the list), kept across rebuilds of the settings tab. */
    private String settingsPage = "";
    /** 설정 home's search: what is typed (kept while a result is open, not across a restart) and the columns showResults() swaps. */
    private String settingsQuery = "";
    private LinearLayout settingsResults, settingsSections;
    /** Set when the app is left (onStop); onStart then draws settings again, so a grant made in Android's settings shows at once. */
    private boolean settingsLeft;
    /** The edge button setting as 바로 부르기 last drew its switch; the menu on the button itself can turn it off. */
    private boolean edgeDrawn;
    /** Stable ids; gallery has its own tab without changing notification and shortcut targets. */
    private static final int[] TAB_ORDER = {0, 3, 5, 4, 2};
    private final java.util.Map<String, Integer> pageScroll = new java.util.HashMap<>();
    private String shownPage = "";
    /** The open work thread ("" for the list), its live views while a run streams in, and the threads as last drawn. */
    private String workId = "";
    private ComposerDictation composerDictation;
    private TextView workStageView, workTimelineView;
    private LinearLayout workTeamView, workLeadView;
    private String teamDrawn = "", leadDrawn = "";
    /** 작업창's composer, docked over the page; it lives across redraws, so typed text survives a streaming answer. */
    private String composerImageAspect = ""; private boolean composerImageEdit;
    private boolean composerVideo; private String composerVideoSource = "";
    private int galleryMedia; private long workGalleryDrawn = -1;
    private LinearLayout composer; private EditText composerInput; private android.widget.ImageView composerSend; private LinearLayout composerBar; private boolean composerRunning, composerStops, composerOneRow;
    /** 작업창's 음성 대화 (VoiceBar, VoiceTargets.work): the send button starts it with an empty draft; its bar takes the composer's place. */
    private VoiceBar workVoice; private boolean composerOffers;
    private LinearLayout composerRepoRow; private TextView composerRepo; private String lastRepo = "";
    /** When the draft last became empty (elapsedRealtime): the button waits STOP_GUARD_MS before it turns into stop, so the tap that sent a message cannot also stop the turn. */
    static final long STOP_GUARD_MS = 1200; private long composerEmptiedAt = -STOP_GUARD_MS;
    /** The content's measured width (px), beside the rail; 0 before the first measure. */
    private int contentWidth;
    /** The thread whose progress line is unfolded (its step log and saved drafts showing); kept while the thread is redrawn. */
    private String workLogOpen = "";
    /** Over the message box: who answers the next message with its model and reasoning level, and the files waiting to go with it. */
    private LinearLayout composerEngines, composerFiles; private View composerFilesRow; private String lastEngines = "";
    private static final int PICK_FILES = 31, PICK_VIDEO_SOURCE = 32, PICK_MAIN_FILES = 34;
    /** Previews and gallery thumbnails of pictures in 작업창, by private copy id, within a sixth of the app's memory (64MB at most). */
    private final android.util.LruCache<String, android.graphics.Bitmap> previews = new android.util.LruCache<>((int) Math.min(64L << 20, Runtime.getRuntime().maxMemory() / 6)) {
        @Override protected int sizeOf(String key, android.graphics.Bitmap value) { return value.getByteCount(); }
    };
    /** The latest system bar and keyboard insets, for layoutDock(). */
    private int insetLeft, insetTop, insetRight, insetBottom, imeBottom;
    private View dockFade;
    private long workDrawn = -1, workInterruptDrawn = -1;
    /** Conversation list mode and the independently navigated gallery's filters and page. */
    private int sessionMode, galleryView, galleryShown = GALLERY_PAGE;
    private String workQuery = "", galleryQuery = "", searchFocus = "";
    private long galleryDrawn = -1;
    private LinearLayout sessionsBox, galleryBox;
    /** Category sections opened past their first rows. */
    private final java.util.Set<String> openSections = new java.util.HashSet<>();
    private static final int SECTION_ROWS = 4, GALLERY_PAGE = 60;
    /** Decodes gallery thumbnails and pictures off the main thread. */
    private final java.util.concurrent.ExecutorService thumbLoader = java.util.concurrent.Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "gyeot-thumbs"); t.setDaemon(true); return t; });
    /** Whether the phone-control service was connected at the last refresh, to redraw settings when that changes. */
    private Boolean phoneSeen;
    private TextView phoneGptCheckState;
    private String phoneGptCheckResult = "";
    private volatile boolean phoneGptChecking;
    private volatile int phoneGptCheckRun;
    private final java.util.concurrent.atomic.AtomicReference<Runnable> phoneGptCheckRequest = new java.util.concurrent.atomic.AtomicReference<>();
    /** Plays one saved recording at a time from the records screen. */
    private android.media.MediaPlayer player;
    private String playingId = "";
    /** Updates the recording timer once a second while the screen is visible. */
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = () -> { if (store.recording || store.thinking() || workRunning()) refresh(); }; // refresh() schedules the next tick
    private LinearLayout phoneRow;
    private Ui.Dial muteButton, stopButton, endButton;
    /** The home's greeting (hidden during a call) and its conversation card (shown during a call or once something was said). */
    private View homeIntro, liveCard;
    private int orbIdleSize;
    /** The call's compact hero: the orb's size beside its words, and how many turns of the conversation show under it. */
    private static final int CALL_ORB = 52, CALL_TURNS = 12, SAID_HEIGHT = 220;
    private LinearLayout heroStage, heroHead, heroWords; private boolean heroCall;
    /** What is on the home beside the call (the mode pills, the doors, recent work): out of the way while a call is on. */
    private final List<View> callAside = new ArrayList<>();
    private View liveActions; private String shownSaid = "";
    /** Captions shown with their Korean translation (a tap on a foreign one), and why one could not be translated. */
    private final java.util.Set<String> translatedTurns = new java.util.HashSet<>(); private final java.util.Map<String, String> untranslated = new java.util.HashMap<>();
    /** The call's conversation: chat bubbles in a list that scrolls inside the card, and the line for typing instead of speaking. */
    private ScrollView said; private LinearLayout saidList; private EditText sayInput; private Ui.Dial typeButton; private boolean typing;
    /** 카메라로 보며 대화: the running camera (null when off), its preview box in place of the orb, and the button that turns it on. */
    private CameraFeed cameraFeed;
    private FrameLayout cameraBox;
    private Ui.Dial cameraButton;
    private static final int CAMERA_PERMISSION = 22;
    /** 설정 → 마이크 허용하기 (24 is WakeWordUi's). */
    private static final int SETTINGS_MIC = 25;
    private WebView liveGrounding;
    /**
     * 작업창: Google's suggestions under grounded Gemini answers, by thread, message and snippet, kept across the thread's
     * redraws (each made and loaded once, not on every redraw). releaseWorkSuggestions frees those a drawing did not show.
     */
    private final java.util.Map<String, View> workSuggestions = new java.util.HashMap<>();
    private final java.util.Set<String> drawnSuggestions = new java.util.HashSet<>();
    /** 작업창 answers drawn as the main chat draws them, kept across the room's redraws. */
    private WorkAnswers workAnswers;
    private WorkAnswers workAnswers() {
        if (workAnswers == null) workAnswers = new WorkAnswers(this, ui, new WorkAnswers.Screen() {
            @Override public void copy(String text) { MainActivity.this.copy(text); }
            @Override public void open(String url) { MainActivity.this.open(url); }
            // Into the message box after anything already typed, keyboard up; the user reads it and sends it.
            @Override public void suggest(String text) { if (composerInput != null) typeInComposer(dev.jun.gyeot.core.RichBlocks.drafted(composerInput.getText().toString(), text)); }
            @Override public void menu(JSONObject thread, JSONObject message) { answerMenu(thread.optString("title"), dev.jun.gyeot.core.RichBlocks.withoutSuggestions(message.optString("text")), lastRequest(thread, message), engineOf(thread, message), answerInfo(message, engineOf(thread, message), message.optString("model"))); }
            @Override public void error(String message) { store.error(message); }
        });
        return workAnswers;
    }
    private boolean recordingPage;
    private String mode = ASSISTANT, query = "", loadedGrounding = "", lastRecords = "", lastOpenAi = "", lastClaude = "", lastGeminiWork = "", lastUpdate = "";
    private int tab;
    /** Records sub-view: 0 work (tasks, recordings, notes), 1 conversations, 2 interpretation, 3 dictation. */
    private int recordsView;
    private static final String[] RECORD_VIEWS = {"작업·메모", "대화", "통역", "받아쓰기"}, SESSION_MODES = {"", ASSISTANT, INTERPRETER, DICTATION};
    private final List<GeminiSession> probes = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Bumped when a connection check is closed, so its delayed next steps do not start new connections. */
    private int probeRun, mediaRun;
    private final Runnable observer = this::refresh;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store = ((GyeotApp) getApplication()).store(); ui = new Ui(this);
        sessionMode = Math.max(0, Math.min(Sessions.MODES.length - 1, store.settings.getInt("sessionMode", 0)));
        galleryView = Math.max(0, Math.min(Gallery.VIEWS.length - 1, store.settings.getInt("galleryView", 0)));
        if (state != null) { recordingPage = state.getBoolean("recordingPage", false); mode = state.getString("mode", mode); query = state.getString("query", ""); tab = state.getInt("tab"); recordsView = state.getInt("records"); settingsPage = state.getString("page", ""); workId = state.getString("work", ""); composerImageAspect = state.getString("imageAspect", ""); composerImageEdit = state.getBoolean("imageEdit", false); }
        else if (fromSignIn(getIntent())) { tab = 2; settingsPage = signInPage(getIntent()); }
        else if (PhoneCalendar.PAGE.equals(getIntent().getStringExtra(PhoneCalendar.PAGE_EXTRA))) { tab = 2; settingsPage = PhoneCalendar.PAGE; }
        else { tab = getIntent().getIntExtra("tab", 0); workId = getIntent().hasExtra("work") ? getIntent().getStringExtra("work") : ""; }
        if (state != null && state.getInt("workView", 0) == 1 && tab == 3 && workId.isEmpty()) tab = 4;
        if (store.voiceActive) mode = store.voiceMode;
        edgeToEdge();
        // The reading column and the composer follow the width the content really has, in the same pass that measures them.
        root = new FrameLayout(this) {
            @Override protected void onMeasure(int width, int height) {
                int size = View.MeasureSpec.getSize(width);
                if (size > 0 && size != contentWidth) { contentWidth = size; if (dockFade != null && tabBar != null && composer != null) layoutDock(); }
                super.onMeasure(width, height);
            }
        };
        root.setBackground(ui.canvas());
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        body = ui.column(); scroll.addView(body);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        // A fade under the floating tab bar and composer, so text scrolling beneath them does not show through.
        dockFade = new View(this);
        dockFade.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{BG & 0x00ffffff, (BG & 0x00ffffff) | 0xe6000000, BG}));
        root.addView(dockFade, new FrameLayout.LayoutParams(-1, 0, android.view.Gravity.BOTTOM));
        // The large title, the status line and the banners; re-attached at the top of every screen.
        header = ui.column();
        navRow = ui.row(); navRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-2, -2); navParams.bottomMargin = ui.dp(12); header.addView(navRow, navParams);
        titleView = ui.largeTitle(Brand.NAME); header.addView(titleView);

        statusRow = ui.row(); statusRow.setPadding(ui.dp(1), ui.dp(8), 0, 0);
        statusDot = new View(this); statusRow.addView(statusDot, new LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)));
        status = ui.text("", Ui.FOOTNOTE, 520, MUTED); status.setSingleLine(true); status.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, -2, 1); statusParams.leftMargin = ui.dp(7); statusRow.addView(status, statusParams);
        header.addView(statusRow);
        updateBanner = ui.banner("", ACCENT); updateBanner.setVisibility(View.GONE); updateBanner.setOnClickListener(v -> openSettings("update"));
        error = ui.banner("", ERROR); error.setVisibility(View.GONE); error.setOnClickListener(v -> { store.error = ""; refresh(); });
        // A call that started before the voice, style, persona or language changed: one tap reconnects with them.
        applyBanner = ui.banner("바꾼 음성 설정이 지금 대화엔 아직 적용 전이에요 · 눌러서 지금 적용", ACCENT); applyBanner.setVisibility(View.GONE);
        applyBanner.setOnClickListener(v -> {
            try { startService(new Intent(this, AssistantService.class).setAction(AssistantService.APPLY)); } catch (Exception e) { store.error(e.getMessage()); }
        });
        // Android ended the last call or task (GyeotApp read the exit reason): once, with the way to the battery settings.
        exitBanner = ui.banner("", WARNING); exitBanner.setVisibility(View.GONE); exitBanner.setTag("exit-notice");
        exitBanner.setOnClickListener(v -> { store.exitNotice = ""; refresh(); openAppSettings(); });
        LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(-1, -2); bannerParams.topMargin = ui.dp(12);
        header.addView(updateBanner, bannerParams); header.addView(applyBanner, new LinearLayout.LayoutParams(bannerParams)); header.addView(exitBanner, new LinearLayout.LayoutParams(bannerParams)); header.addView(error, new LinearLayout.LayoutParams(bannerParams));
        tabBar = tabBar(); root.addView(tabBar, new FrameLayout.LayoutParams(-1, ui.dp(64), android.view.Gravity.BOTTOM));
        composer = composer(); composer.setVisibility(View.GONE); root.addView(composer, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM));
        workVoice = new VoiceBar(this, store, ui, "work", () -> VoiceTargets.work(this, store, workId)); workVoice.onToggle(this::layoutDock); workVoice.enabled(false);
        root.addView(workVoice.bar(), new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM));
        if (state != null) {
            composerInput.setText(state.getString("composerText", "")); composerVideo = state.getBoolean("videoMode"); composerVideoSource = state.getString("videoSource", ""); galleryMedia = state.getInt("galleryMedia"); refreshFiles();
        }
        contentSlot=new FrameLayout(this);contentSlot.addView(root,new FrameLayout.LayoutParams(-1,-1));
        navigation=new MainNavigation(this,contentSlot,"voice",this::navigateMenu);navigation.onResize(this::layoutDock);setContentView(navigation);
        // The large title collapses into the bar as it scrolls away, as in iOS; the bar title is hidden while it is in view.
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> syncBarTitle());
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()), ime = insets.getInsets(WindowInsets.Type.ime());
            insetLeft = bars.left; insetTop = bars.top; insetRight = bars.right; insetBottom = bars.bottom; imeBottom = ime.bottom;
            layoutDock();
            return insets;
        });
        root.requestApplyInsets();
        showTab(tab);
        if(state!=null){mainChatSaved=state.getBundle("mainChatPage");chatReturnTab=state.getInt("chatReturnTab",tab);}
        if((state!=null&&state.getBoolean("mainChatShown"))||(state==null&&"chat".equals(getIntent().getStringExtra(MainNavigation.TARGET))))showMainChat();
        // Recreated, the saved page wins: a notification tap that once opened this window is not asked again.
        if (state == null || !AppNavigator.tapped(getIntent())) openRequestedScreen(getIntent());
        if (state == null) { startFromShortcut(getIntent()); startOnOpen(getIntent()); talkFromNotification(getIntent()); }
        else pendingLaunchVoice=state.getBoolean("pendingLaunchVoice",false);
        if (state != null && state.getBundle("videoPlayer") != null && videoDialog == null) { Bundle saved = state.getBundle("videoPlayer"); JSONObject video = VideoJobs.find(store.videos(), saved.getString("id", "")); if (video != null) root.post(() -> playVideo(video, saved)); }
    }
    /** Android 15 is always edge to edge; match it on 13–14 so system bar and keyboard insets are handled in one place. */
    @SuppressWarnings("deprecation")
    private void edgeToEdge() { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) getWindow().setDecorFitsSystemWindows(false); }
    /** A clip began or ended: the call's bubbles show which one plays. */
    private final Runnable clipsChanged = () -> { if (!isDestroyed()) { shownSaid = ""; refresh(); } };
    @Override protected void onStart() { super.onStart(); hostStarted=true; store.appVisible = true; VoiceClips.listen(clipsChanged); if (workVoice != null) workVoice.attach(); if(showingMainChat&&mainChat!=null)mainChat.show(); store.observe(observer); refresh(); store.updater.checkIfDue();
        // A call may start from here: the greeting is read into memory and the path to the Live host warmed (Greeter).
        store.greeter().warm();
        // Back from another app (Android's settings): settings is drawn again on its page and scroll, so a grant shows at once.
        boolean back = settingsLeft; settingsLeft = false; if (back && tab == 2 && !showingMainChat && !"key".equals(settingsPage)) showTab(2);
        // 차에서 호출어: the standby can only start while the app is in front (after a reboot it starts here).
        if (store.settings.getBoolean(WakeWordFiles.ENABLED, false) && WakeWordService.current == dev.jun.gyeot.core.WakeStandby.State.OFF) {
            try { WakeWordService.start(this); } catch (RuntimeException refused) { /* the settings page says how to turn it on */ }
        }
        pendingLegacyRecovery=true;if(!showingMainChat)recoverLegacyWork();
    }
    /** Preserve the former MainActivity-onStart recovery boundary when first leaving a cold messenger entry. */
    private void recoverLegacyWork(){
        if(!pendingLegacyRecovery)return;pendingLegacyRecovery=false;
        JSONArray interrupted = store.works();
        for (int i = 0; i < interrupted.length(); i++) if (dev.jun.gyeot.core.WorkCheckpoint.eligible(interrupted.optJSONObject(i), System.currentTimeMillis()) && store.canResumeWork(interrupted.optJSONObject(i))) {
            send(AssistantService.WORK_RECOVER, "", ""); break;
        }
    }
    @Override protected void onPause() { hostResumed=false;if(mainChat!=null)mainChat.pause(); if (composerDictation != null) composerDictation.pause(); if (videoDialog != null) videoDialog.background(); super.onPause(); }
    @Override protected void onResume() { super.onResume();hostResumed=true;AppNavigator.inFront("main");store.refreshModelListsIfDue();SemanticIndex.catchUpIfDue(this, store);Briefing.catchUp(this, store);WorkScheduleAlarm.refresh(this, store);if(showingMainChat&&mainChat!=null)mainChat.resume(); if (videoDialog != null) videoDialog.foreground(); BrowserRuntime.prepareWhenIdle(this);
        if(isLauncher(getIntent())&&StorageSetupActivity.maybeOffer(this))return;
        if(pendingLaunchVoice){pendingLaunchVoice=false;if(store.voiceOnOpen()&&!store.voiceActive){mode=ASSISTANT;recordingPage=false;showTab(0);startVoice();}}
    }
    @Override protected void onStop() { hostStarted=false;settingsLeft=true; VoiceClips.unlisten(clipsChanged); VoiceClips.stop(); if (workVoice != null) workVoice.detach();if(mainChat!=null)mainChat.hide();stopCamera(); store.appVisible = false; store.unobserve(observer); ticker.removeCallbacksAndMessages(null); stopPlayback(); stopReadingWhenLeft(); super.onStop(); }
    /**
     * Reading aloud has no foreground service, so once the app is left Android may freeze it mid-answer with the focus still
     * held: leaving stops it. The screen going off does not (the app stays on top), nor does a rotation.
     */
    private void stopReadingWhenLeft() {
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        if (!isChangingConfigurations() && (power == null || power.isInteractive())) store.stopReading(null);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("pendingLaunchVoice",pendingLaunchVoice);
        state.putBoolean("mainChatShown",showingMainChat);state.putInt("chatReturnTab",chatReturnTab);
        if(mainChat!=null){Bundle chat=new Bundle();mainChat.save(chat);state.putBundle("mainChatPage",chat);}else if(mainChatSaved!=null)state.putBundle("mainChatPage",mainChatSaved);
        state.putBoolean("recordingPage", recordingPage); state.putString("mode", mode); state.putString("query", query); state.putInt("tab", tab); state.putInt("records", recordsView); state.putString("page", settingsPage); state.putString("work", workId); state.putString("imageAspect", composerImageAspect); state.putBoolean("imageEdit", composerImageEdit); state.putString("composerText", composerInput == null ? "" : composerInput.getText().toString());
        state.putBoolean("videoMode", composerVideo); state.putString("videoSource", composerVideoSource); state.putInt("galleryMedia", galleryMedia);
        if (videoDialog != null && videoDialog.isShowing()) state.putBundle("videoPlayer", videoDialog.snapshot());
        super.onSaveInstanceState(state);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (AppNavigator.isRequest(intent)) { openRequestedScreen(intent); return; }
        if (talkFromNotification(intent)) return;
        String menuTarget=intent.getStringExtra(MainNavigation.TARGET);if(java.util.List.of("voice","chat","work","gallery","settings").contains(menuTarget==null?"":menuTarget)){navigateMenu(menuTarget);return;}
        // The calendar's tap-to-open notification (a call without the permission): 설정 → 캘린더, where it is allowed.
        if (PhoneCalendar.PAGE.equals(intent.getStringExtra(PhoneCalendar.PAGE_EXTRA))) { openSettings(PhoneCalendar.PAGE); return; }
        if (intent.hasExtra("work")) { workId = intent.getStringExtra("work"); if (galleryDialog != null) galleryDialog.dismiss(); if (videoDialog != null) videoDialog.dismiss(); }
        // Asked for the settings tab (the browser's 모델 연결 설정): its home with the AI 연결 group, not a page left open earlier.
        if (fromSignIn(intent)) openSettings(signInPage(intent)); else if (intent.hasExtra("tab")) { int asked = intent.getIntExtra("tab", 0); if (asked == 2) { settingsPage = ""; settingsQuery = ""; } showTab(asked); }
        // Some existing home-screen pins still address MainActivity directly.
        if (isLauncher(intent) && "board".equals(AppNavigator.leftAbove())) {
            pendingLaunchVoice = false; reopenLeftScreen(); return;
        }
        startFromShortcut(intent);
        startOnOpen(intent);
        // The app icon (or "들퍼레 열어 줘") brings the app back as the user left it: this window keeps its page (tab, settings
        // page, work room or main chat). singleTask finished the browser or 내 휴대폰 above it, so that screen opens again.
        if(isLauncher(intent)&&!pendingLaunchVoice)reopenLeftScreen();
    }
    private void reopenLeftScreen(){
        String left=AppNavigator.leftAbove();
        if(left.equals("browser"))openBrowser();
        else if(left.equals("pages")){Intent pages=new Intent(this,PagesActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_NO_ANIMATION);
            // The page that was open comes back too (the search text and the category filter do not).
            if(AppNavigator.pageLeft!=null)pages.putExtra(PagesActivity.OPEN,AppNavigator.pageLeft);startActivity(pages);}
        else if(left.equals("board"))startActivity(new Intent(this,BoardActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_NO_ANIMATION));
        else if(left.equals("mytube"))startActivity(new Intent(this,MyTubeActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_NO_ANIMATION));
        else if(!left.isEmpty())startActivity(new Intent(this,FileExplorerActivity.class).putExtra(FileExplorerActivity.EXPLORER,left.equals("files"))
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }
    /** Internal voice navigation can show saved media; it cannot change settings or start a new voice session. */
    private void openRequestedScreen(Intent intent) {
        String screen = AppNavigator.destination(intent);
        if (screen.isEmpty()) return;
        String imageId = AppNavigator.imageId(intent);
        String videoId = AppNavigator.videoId(intent);
        if (galleryDialog != null) galleryDialog.dismiss();
        if (videoDialog != null) videoDialog.dismiss();
        // main is this window as the user left it (a tab or the main chat); chat is the main chat. Both are confirmed when in front.
        if (screen.equals("main") || screen.equals("chat")) {
            if (screen.equals("chat")) showMainChat();
            root.post(() -> { if (getIntent() == intent && hasWindowFocus()) AppNavigator.shown(intent, page()); });
            return;
        }
        if (screen.equals("work")) { workId = ""; store.workOpen = ""; }
        if (screen.equals("settings")) { settingsPage = ""; settingsQuery = ""; }
        if (screen.equals("assistant")) { recordingPage=false; mode=store.voiceActive?store.voiceMode:ASSISTANT; }
        if (screen.equals("records")) recordsView = 0;
        if (!videoId.isEmpty()) galleryMedia = 2;
        showTab(dev.jun.gyeot.core.AppNavigation.tab(screen));
        if (!videoId.isEmpty()) {
            JSONObject video = VideoJobs.find(store.videos(), videoId);
            if (video == null || !"done".equals(video.optString("state")) || video.optString("uri").isBlank()) AppNavigator.videoFailed(intent, "요청한 영상이 아직 재생 가능한 상태가 아니에요.");
            else playVideo(video, null, intent);
            return;
        }
        if (!imageId.isEmpty()) {
            JSONArray entries = store.gallery(); List<JSONObject> pictures = new ArrayList<>(); int selected = -1;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i); if (entry == null) continue;
                if (imageId.equals(entry.optString("id"))) selected = pictures.size();
                pictures.add(entry);
            }
            if (selected < 0) AppNavigator.imageFailed(intent, "요청한 이미지가 갤러리에 없어요.");
            else showPicture(pictures, selected, intent);
            return;
        }
        root.post(() -> { if (getIntent() == intent && hasWindowFocus()) AppNavigator.shown(intent, page()); });
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && root != null) { Intent request=getIntent(); root.post(() -> { if (getIntent()==request&&hasWindowFocus()) AppNavigator.shown(request, page()); }); }
    }
    /** The page in front for a navigation request: the main chat, else the tab. A tab hidden behind the chat is not shown. */
    private int page() { return showingMainChat ? dev.jun.gyeot.core.AppNavigation.CHAT : tab; }
    /**
     * Opened from the app icon or by Bixby ("들퍼레 열어 줘") with 앱을 열면 바로 대화 on: voice starts at once on the 비서 screen.
     * The activity is singleTask, so opening it again from the icon reaches onNewIntent; coming back from recent apps does not.
     * Without that setting, opening it again keeps the page the user left (onNewIntent).
     */
    private static boolean isLauncher(Intent intent){return intent!=null&&Intent.ACTION_MAIN.equals(intent.getAction())&&intent.hasCategory(Intent.CATEGORY_LAUNCHER);}
    private void startOnOpen(Intent intent) {
        pendingLaunchVoice=store.voiceOnOpen()&&!store.voiceActive&&isLauncher(intent)&&(intent.getFlags()&Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)==0;
    }
    void navigateMenu(String target){
        switch(target){case "chat" -> showMainChat();case "voice" -> {recordingPage=false;mode=store.voiceActive?store.voiceMode:ASSISTANT;showTab(0);}case "work" -> showTab(3);case "gallery" -> showTab(4);case "settings" -> {settingsPage="";settingsQuery="";showTab(2);}default -> MainNavigation.open(this,target);}
    }
    /** Only the content slot changes. Window, menu/rail Views and their focus remain owned by this Activity. */
    private void showMainChat(){
        if(showingMainChat)return;
        View menuFocus=navigation.rail.findFocus();
        chatReturnTab=tab;if(!shownPage.isEmpty())pageScroll.put(shownPage,scroll.getScrollY());
        navigation.bar(null);
        stopCamera();stopPlayback();if(composerDictation!=null)composerDictation.cancel();
        View focused=root.findFocus();if(focused!=null){android.view.inputmethod.InputMethodManager keyboard=getSystemService(android.view.inputmethod.InputMethodManager.class);if(keyboard!=null)keyboard.hideSoftInputFromWindow(focused.getWindowToken(),0);focused.clearFocus();}
        showingMainChat=true;ticker.removeCallbacksAndMessages(null);
        if(mainChat==null){mainChat=new MainChatPage(this,mainChatSaved);mainChatSaved=null;contentSlot.addView(mainChat.view(),new FrameLayout.LayoutParams(-1,-1));}
        root.setVisibility(View.GONE);mainChat.view().setVisibility(View.VISIBLE);navigation.selected("chat");navigation.collapsedTitle(true);
        if(hostStarted)mainChat.show();if(hostResumed)mainChat.resume();mainChat.view().requestApplyInsets();
        if(menuFocus!=null)menuFocus.requestFocus();
    }
    private void hideMainChat(){
        if(!showingMainChat)return;showingMainChat=false;
        if(mainChat!=null){mainChat.hide();mainChat.view().setVisibility(View.GONE);}root.setVisibility(View.VISIBLE);root.requestApplyInsets();syncBarTitle();
    }
    /** The bar shows the screen name only when the screen has no large title in view (a thread, the chat, or scrolled past it). */
    private void syncBarTitle() {
        if (navigation == null) return;
        boolean laidOut = titleView.getHeight() > 0;
        boolean titleInView = !showingMainChat && titleView.getVisibility() == View.VISIBLE && header.getParent() == body
            && (laidOut ? scroll.getScrollY() < header.getTop() + titleView.getBottom() - ui.dp(6) : scroll.getScrollY() == 0);
        navigation.collapsedTitle(!titleInView);
    }
    /** A tab opened from the main chat; settings opens on its home (연결 설정), not on a page left open earlier. */
    void openMainContent(int selected,String room){if(room!=null)workId=room;if(selected==0){recordingPage=false;mode=store.voiceActive?store.voiceMode:ASSISTANT;}if(selected==2){settingsPage="";settingsQuery="";}showTab(selected);}
    /**
     * 말로 묻기 in the notification: the main chat opens and listens once, until the recognizer hears the end (a notification
     * action cannot be held). Only this process's own token opens the mic; anything else just opens the chat.
     */
    private boolean talkFromNotification(Intent intent) {
        String token = intent.getStringExtra(ConnectedVoice.EXTRA);
        if (token == null) return false;
        intent.removeExtra(ConnectedVoice.EXTRA);
        showMainChat();
        if (ConnectedVoice.TOKEN.equals(token) && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0 && mainChat != null) mainChat.talkWhenResumed();
        return true;
    }
    /** "들퍼레 바로 대화" handed over because something was missing: the normal start asks for the key or permission. */
    private void startFromShortcut(Intent intent) {
        if (!intent.getBooleanExtra(START_VOICE, false) || (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return;
        intent.removeExtra(START_VOICE);
        if (store.voiceActive) return;
        mode = ASSISTANT; recordingPage = false; showTab(0); startVoice();
    }
    /** The "back to 들퍼레" link on the ChatGPT sign-in page. */
    private static boolean fromSignIn(Intent intent) { Uri data = intent.getData(); return data != null && "gyeot".equals(data.getScheme()) && "openai-done".equals(data.getHost()); }
    /** The settings page the sign-in started from: Gemini's (Google sign-in, ?page=gemini), 커넥터 (MCP sign-in, ?page=connectors) or ChatGPT's. Nothing else is opened. */
    private static String signInPage(Intent intent) {
        Uri data = intent.getData(); String page = data == null ? null : data.getQueryParameter("page");
        return "gemini".equals(page) ? "key" : "connectors".equals(page) ? "connectors" : "chatgpt";
    }
    @Override protected void onDestroy() { if(mainChat!=null)mainChat.close();if (composerDictation != null) composerDictation.close(); if (galleryDialog != null) galleryDialog.dismiss(); if (videoDialog != null) videoDialog.dismiss(); closeProbes(); if (workAnswers != null) workAnswers.close(); destroyWebViews(body); thumbLoader.shutdownNow(); super.onDestroy(); }
    private void openBrowser() {
        View focused = getCurrentFocus();
        if (focused != null) {
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        BrowserEngine.show(this);
    }
    private void showTab(int selected) {
        // A screen that is new (another tab, another settings page, another room) arrives with a short fade; a redraw of the same one does not move.
        String cameFrom = shownPage;
        View menuFocus=navigation==null?null:navigation.rail.findFocus();
        if (composerDictation != null) { if (selected != 3) composerDictation.cancel(); else composerDictation.validateScope(); }
        if (selected == 5) { openBrowser(); return; }
        hideMainChat();
        if(hostStarted)recoverLegacyWork();
        if (!shownPage.isEmpty()) pageScroll.put(shownPage, scroll.getScrollY());
        grokBox = null; nimBox = null; usageBox = null;
        // A search box keeps its focus when the page is drawn again (searchBox looks for this tag).
        View focused = getCurrentFocus();
        searchFocus = focused != null && focused.getTag() instanceof String key && key.startsWith("search:") ? key : "";
        // The camera stays on only on the 비서 screen; redrawn, its preview moves to the new screen.
        if (cameraFeed != null) { if (selected != 0) stopCamera(); else cameraFeed.detach(); }
        phoneGptCheckState = null;
        orb = null; orbTitle = null; orbSub = null; claudeBox = null; geminiWorkBox = null; muteButton = null; stopButton = null; endButton = null; homeIntro = null; liveCard = null; heroStage = null; heroHead = null; heroWords = null; heroCall = false; callAside.clear(); liveActions = null; shownSaid = ""; said = null; saidList = null; sayInput = null; typeButton = null; typing = false; sayRow = null; taskSummary = null; edgeState = null; assistState = null; archiveState = null; phoneRow = null; recordButton = null; recordsList = null; openAiBox = null; updateBox = null; sessionsBox = null; galleryBox = null; cameraBox = null; cameraButton = null; tab = selected; clearBody();
        if (header.getParent() instanceof ViewGroup parent) parent.removeView(header);
        navRow.removeAllViews(); navRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2); headerParams.bottomMargin = ui.dp(20); body.addView(header, headerParams);
        paintTabs();
        if(navigation!=null)navigation.selected(tab==0?"voice":tab==3||tab==1?"work":tab==4?"gallery":"settings");
        workStageView = null; workTimelineView = null; workLog = null; workTeamView = null; workLeadView = null; teamDrawn = ""; leadDrawn = "";
        if (tab != 3 && composerInput != null && composerInput.hasFocus()) composerInput.clearFocus();
        boolean inThread = tab == 3 && !workId.isEmpty() && WorkBook.find(store.works(), workId) != null;
        titleView.setVisibility(inThread ? View.GONE : View.VISIBLE);
        // A conversation's bar sits in the navigation bar, in the screen name's place; every other screen gets the name back.
        if (navigation != null) navigation.bar(null);
        if (tab == 0) home(); else if (tab == 1) records(); else if (tab == 3) works(); else if (tab == 4) gallery(); else settings();
        releaseWorkSuggestions();
        layoutDock(); updateComposer();
        shownPage = tab + ":" + (tab == 3 ? workId : tab == 2 ? settingsPage : "");
        String restoredPage = shownPage; int restoreY = pageScroll.getOrDefault(restoredPage, 0); scroll.post(() -> { if (restoredPage.equals(shownPage)) scroll.scrollTo(0, restoreY); syncBarTitle(); }); syncBarTitle(); refresh();
        if(menuFocus!=null)menuFocus.requestFocus();
        if (!cameFrom.isEmpty() && !shownPage.equals(cameFrom)) ui.arrive(body);
    }
    /** The floating tab bar: a rounded, lightly shadowed capsule over the content. */
    private LinearLayout tabBar() {
        LinearLayout bar = ui.row(); bar.setPadding(ui.dp(6), 0, ui.dp(6), 0);
        android.graphics.drawable.GradientDrawable capsule = ui.glass(32, false);
        bar.setBackground(capsule); ui.lift(bar, 16);
        String[] names = {"비서", "작업", "브라우저", "갤러리", "설정"}; int[] icons = {R.drawable.ic_wave, R.drawable.ic_work, R.drawable.ic_browser, R.drawable.ic_gallery, R.drawable.ic_settings};
        for (int i = 0; i < names.length; i++) {
            int target = TAB_ORDER[i];
            LinearLayout item = ui.column(); item.setGravity(android.view.Gravity.CENTER);
            android.widget.ImageView icon = new android.widget.ImageView(this); icon.setImageResource(icons[i]);
            item.addView(icon, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
            // Wrapped, so the item's centering puts the name under its icon.
            TextView label = ui.text(names[i], 11, 600, MUTED); label.setPadding(0, ui.dp(2), 0, 0); label.setGravity(android.view.Gravity.CENTER);
            // Android requires max > min, including when the system font scale is below 1.
            int maxTabTextDp = Math.max(11, Math.round(11 * getResources().getConfiguration().fontScale));
            label.setMaxLines(1); label.setAutoSizeTextTypeUniformWithConfiguration(10, maxTabTextDp, 1, android.util.TypedValue.COMPLEX_UNIT_DIP);
            item.addView(label, new LinearLayout.LayoutParams(-1, ui.dp(24)));
            item.setContentDescription(names[i] + " 탭"); item.setOnClickListener(v -> {
                // A light tick under the finger when the tab changes, as on iPhone; the phone's haptics setting still applies.
                if (target != tab) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                // Tapping the open tab again goes back to its first screen, as on iPhone; on 설정's home it clears the search and goes to the top.
                boolean settingsHome = target == tab && target == 2 && settingsPage.isEmpty();
                if (target == tab && target == 2 && !settingsPage.isEmpty()) settingsPage = "";
                if (settingsHome) settingsQuery = "";
                if (target == tab && target == 3) { showWorkList(); return; }
                showTab(target);
                // After showTab's own restore of the scroll, which it posts first.
                if (settingsHome) scroll.post(() -> scroll.scrollTo(0, 0));
            });
            Ui.pressable(item, false);
            bar.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        return bar;
    }
    /** The open tab on a tonal accent capsule, icon and name in the accent; the others muted. */
    private void paintTabs() {
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) tabBar.getChildAt(i); boolean open = TAB_ORDER[i] == tab || TAB_ORDER[i] == 3 && tab == 1;
            ((android.widget.ImageView) item.getChildAt(0)).setImageTintList(android.content.res.ColorStateList.valueOf(open ? ACCENT : MUTED));
            TextView name = (TextView) item.getChildAt(1); name.setTextColor(open ? ACCENT : MUTED); name.setTypeface(Ui.weight(open ? 700 : 560)); item.setSelected(open);
            item.setBackground(open ? new android.graphics.drawable.InsetDrawable(ui.lens(20), ui.dp(3), ui.dp(6), ui.dp(3), ui.dp(6)) : null);
            item.setElevation(0);
        }
    }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if(navigation!=null&&navigation.close())return;
        if(showingMainChat){if(mainChat!=null&&mainChat.back())return;showTab(chatReturnTab);return;}
        if (tab == 2 && !settingsPage.isEmpty()) { settingsPage = settingsParent(settingsPage); showTab(2); return; }
        if (tab == 3 && !workId.isEmpty()) { showWorkList(); return; }
        super.onBackPressed();
    }
    private void add(View view, int bottom) { ui.add(body, view, bottom); }
    /** Opens or closes the line for typing in a call; opened, the keyboard comes with it. */
    private void toggleTyping() {
        typing = !typing; refresh();
        android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class); if (sayInput == null || keyboard == null) return;
        if (typing) { sayInput.requestFocus(); keyboard.showSoftInput(sayInput, 0); } else { keyboard.hideSoftInputFromWindow(sayInput.getWindowToken(), 0); sayInput.clearFocus(); }
    }
    /** Keeps the newest words in view: the conversation scrolled to its foot. */
    private void pinSaid() { if (said != null && saidList != null) said.scrollTo(0, Math.max(0, saidList.getHeight() + said.getPaddingTop() + said.getPaddingBottom() - said.getHeight())); }
    /**
     * One turn of the call's conversation as a chat bubble: the user's (나…, and 들린 말, the user's spoken words) on the
     * right, filled with the accent under white words, everyone else's on the left on the card color, with the speaker's
     * name over it when it is not the assistant's. The two fills are far apart on purpose: an earlier pale user bubble
     * (Ui.BUBBLE_FILL) and the white one beside it both read as white on the phone. A notice
     * (안내) is a small centered line, not a bubble. faint: words still being said or thought. Words mostly in another
     * language show their Korean translation under the bubble on a tap (CaptionTranslator), and hide it on the next. A
     * written line (clip, its AppStore.lineKey) has ▶ beside its bubble: 다시 듣기 in the voice it was said in (VoiceClips),
     * or read aloud when its sound was not kept.
     */
    private View turn(String who, CharSequence text, boolean faint, String clip) {
        if ("안내".equals(who)) {
            TextView note = ui.text("", Ui.CAPTION, 450, MUTED); note.setText(text); note.setGravity(android.view.Gravity.CENTER); note.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); return note;
        }
        boolean mine = who.startsWith("나") || AppStore.HEARD.equals(who);
        LinearLayout line = ui.column(); line.setGravity(mine ? android.view.Gravity.END : android.view.Gravity.START); line.setPadding(ui.dp(mine ? 40 : 0), ui.dp(3), ui.dp(mine ? 0 : 40), ui.dp(3));
        if (!mine && !Brand.NAME.equals(who)) { TextView name = ui.text(who, 11, 600, MUTED); name.setPadding(ui.dp(8), 0, 0, ui.dp(2)); line.addView(name, new LinearLayout.LayoutParams(-2, -2)); }
        TextView bubble = ui.text("", Ui.SUBHEAD, mine ? 500 : 400, mine ? ON_ACCENT : TEXT); bubble.setText(text); if (mine) bubble.setLinkTextColor(ON_ACCENT); bubble.setLineSpacing(ui.dp(2), 1); bubble.setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(8));
        // A messenger's bubble: round, with the corner by its speaker's side nearly square.
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable(); shape.setColor(mine ? ACCENT : CARD);
        float round = ui.dp(17), tail = ui.dp(5); shape.setCornerRadii(mine ? new float[]{round, round, round, round, tail, tail, round, round} : new float[]{tail, tail, round, round, round, round, round, round});
        if (!mine) shape.setStroke(Math.max(1, ui.dp(1)), SEPARATOR);
        bubble.setBackground(shape); if (faint) bubble.setAlpha(0.62f);
        String plain = text.toString(); bubble.setContentDescription((mine ? "나" : who) + ": " + plain);
        bubble.setOnLongClickListener(v -> { copy(plain); return true; });
        // ▶ beside the AI's words only: what the user said needs no replay.
        if (clip == null || faint || mine) line.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        else {
            boolean now = VoiceClips.playing(clip), kept = VoiceClips.has(clip);
            android.widget.ImageView play = ui.footIcon(now ? R.drawable.ic_stop : R.drawable.ic_play,
                now ? "다시 듣기 멈추기" : kept ? "다시 듣기 · 말한 목소리 그대로" : "다시 듣기 · 읽어 주기", () -> VoiceClips.toggle(this, store, clip, plain));
            if (now) play.setColorFilter(ACCENT);
            // An 18dp mark in a 36dp target (footIcon's own padding left only a dot of it here).
            play.setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9));
            LinearLayout row = ui.row(); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams playAt = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)), bubbleAt = new LinearLayout.LayoutParams(-2, -2, 1);
            if (mine) { row.addView(play, playAt); row.addView(bubble, bubbleAt); } else { row.addView(bubble, bubbleAt); row.addView(play, playAt); }
            line.addView(row, new LinearLayout.LayoutParams(-2, -2));
        }
        if (!faint && dev.jun.gyeot.core.CaptionLanguage.foreign(plain)) {
            boolean open = translatedTurns.contains(plain);
            bubble.setContentDescription(bubble.getContentDescription() + (open ? ", 눌러서 번역 닫기" : ", 눌러서 한국어로 보기"));
            bubble.setOnClickListener(v -> toggleTranslation(plain));
            if (open) {
                String korean = CaptionTranslator.known(plain), why = untranslated.get(plain);
                TextView under = ui.text(korean != null ? korean : why != null ? why : "한국어로 옮기는 중…", Ui.FOOTNOTE, 450, korean != null ? TEXT : MUTED);
                under.setLineSpacing(ui.dp(2), 1); under.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), 0); under.setTextIsSelectable(korean != null);
                under.setContentDescription("한국어 번역: " + under.getText());
                line.addView(under, new LinearLayout.LayoutParams(-2, -2));
            }
        }
        return line;
    }
    /** A foreign caption's translation: shown (translated once, then kept) or hidden again. */
    private void toggleTranslation(String plain) {
        if (translatedTurns.remove(plain)) { shownSaid = ""; refresh(); return; }
        translatedTurns.add(plain); untranslated.remove(plain); shownSaid = ""; refresh();
        if (CaptionTranslator.known(plain) != null) return;
        CaptionTranslator.translate(store, plain, korean -> { if (!isDestroyed()) { shownSaid = ""; refresh(); } },
            why -> { untranslated.put(plain, why); if (!isDestroyed()) { shownSaid = ""; refresh(); } });
    }

    // ── 비서 ─────────────────────────────────────────────────────────────────

    private void home() {
        titleView.setText(Brand.NAME);
        int hour = java.time.LocalTime.now().getHour();
        // One calm line under the title, at reading weight: the screen's one headline is the orb's own words.
        TextView homeGreeting = ui.text(hour < 12 ? "좋은 아침이에요. 무엇을 도와드릴까요?" : hour < 18 ? "오늘은 어떤 일을 함께할까요?" : "편안한 저녁, 무엇을 도와드릴까요?", Ui.BODY, 450, MUTED);
        homeGreeting.setLineSpacing(ui.dp(3), 1); add(homeGreeting, 16); homeIntro = homeGreeting;
        int selectedMode = recordingPage ? 3 : Math.max(0, java.util.Arrays.asList(MODE_IDS).indexOf(mode));
        View homeModes = ui.pills(HOME_MODE_NAMES, selectedMode, this::selectHomeMode);
        assistantArrow((TextView) ((ViewGroup) ((ViewGroup) homeModes).getChildAt(0)).getChildAt(0));
        // Keep the selected tab reachable on narrow phones, large text and folded screens.
        homeModes.post(() -> {
            View selected = ((ViewGroup) ((ViewGroup) homeModes).getChildAt(0)).getChildAt(selectedMode);
            homeModes.scrollTo(Math.max(0, selected.getLeft() - ui.dp(24)), 0);
        });
        add(homeModes, 14); callAside.clear(); callAside.add(homeModes);
        // Switching home pages never conceals the only way to stop an already-active microphone.
        if (recordingPage && store.voiceActive) add(ui.actions(ui.chip("진행 중인 음성 종료", () -> send(AssistantService.END_LIVE, null, null))), 10);
        if (!recordingPage && store.recording) add(ui.actions(ui.chip("진행 중인 녹음 중지", () -> send(AssistantService.RECORD_STOP, null, null))), 10);
        if (recordingPage) { homeRecorder(); return; }
        if (INTERPRETER.equals(mode)) add(new InterpreterUi(ui, store, this::interpreterChanged).view(), 6);
        // Without search the assistant has to say it cannot look things up, so make the reason visible where it is heard.
        if (ASSISTANT.equals(mode) && !store.searchWanted()) {
            TextView off = ui.banner("검색이 꺼져 있어서 " + Brand.I + " 최신 정보를 찾지 못해요." + (store.voiceActive ? " 켠 뒤 음성을 다시 시작하면 적용돼요." : " 눌러서 켜기"), WARNING);
            off.setOnClickListener(v -> { store.settings.edit().putBoolean("search", true).apply(); store.changed(); showTab(0); }); add(off, 6); callAside.add(off);
        }
        // The voice hero: the orb alone in the middle of the screen, its state in words under it, and during a call the call's
        // own controls. Everything else on the page is one quiet row below.
        boolean largeType = getResources().getConfiguration().fontScale > 1.3f;
        LinearLayout stage = ui.column(); stage.setGravity(android.view.Gravity.CENTER_HORIZONTAL); stage.setBackground(ui.glass(Ui.R_SHEET, true)); stage.setPadding(ui.dp(20), ui.dp(26), ui.dp(20), ui.dp(24));
        cameraBox = new FrameLayout(this); cameraBox.setVisibility(View.GONE); stage.addView(cameraBox, new LinearLayout.LayoutParams(-1, -2));
        orbIdleSize = largeType ? 148 : 192;
        // The orb and its words: stacked and centered while idle, one slim row during a call (refresh), so the words said get the card.
        heroStage = stage; heroCall = false; heroHead = ui.column(); heroHead.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        orb = new VoiceOrb(this); orb.setOnClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); toggleVoice(); }); heroHead.addView(orb, new LinearLayout.LayoutParams(ui.dp(orbIdleSize), ui.dp(orbIdleSize)));
        LinearLayout words = ui.column(); heroWords = words; words.setGravity(android.view.Gravity.CENTER_HORIZONTAL); words.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        orbTitle = ui.text("", Ui.TITLE1, 760, TEXT); orbTitle.setLetterSpacing(-0.03f); orbTitle.setGravity(android.view.Gravity.CENTER); words.addView(orbTitle);
        orbSub = ui.text("", Ui.FOOTNOTE, 450, MUTED); orbSub.setGravity(android.view.Gravity.CENTER); orbSub.setPadding(0, ui.dp(6), 0, 0); words.addView(orbSub);
        heroHead.addView(words, new LinearLayout.LayoutParams(-1, -2));
        stage.addView(heroHead, new LinearLayout.LayoutParams(-1, -2));
        // What is being said, inside the hero under the orb's words, as a chat: bubbles in a list that scrolls inside the card.
        // During a call it is the screen's subject and takes all the room the card has (refresh). On its own (after a call,
        // or when the screen has no room to give) it is at most SAID_HEIGHT dp tall.
        int saidMax = ui.dp(SAID_HEIGHT);
        said = new ScrollView(this) {
            @Override protected void onMeasure(int width, int height) {
                if (MeasureSpec.getMode(height) != MeasureSpec.EXACTLY) height = MeasureSpec.makeMeasureSpec(MeasureSpec.getMode(height) == MeasureSpec.AT_MOST ? Math.min(MeasureSpec.getSize(height), saidMax) : saidMax, MeasureSpec.AT_MOST);
                super.onMeasure(width, height);
            }
        };
        said.setVerticalScrollBarEnabled(false); said.setOverScrollMode(View.OVER_SCROLL_NEVER); said.setNestedScrollingEnabled(true); said.setVerticalFadingEdgeEnabled(true); said.setFadingEdgeLength(ui.dp(18)); said.setVisibility(View.GONE);
        saidList = ui.column(); saidList.setPadding(0, ui.dp(10), 0, ui.dp(2)); said.addView(saidList, new ViewGroup.LayoutParams(-1, -2));
        said.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> { if (b - t != oldB - oldT) pinSaid(); });
        stage.addView(said, new LinearLayout.LayoutParams(-1, -2));
        // Typing instead of speaking: one slim line under the words, opened from the call's own row of controls.
        sayRow = ui.row(); sayRow.setPadding(0, ui.dp(8), 0, 0); sayRow.setVisibility(View.GONE);
        EditText say = ui.input("글로 말하기", 1, 4000); sayInput = say; say.setImeOptions(EditorInfo.IME_ACTION_SEND); say.setBackground(ui.shape(FILL, 99)); say.setTextSize(Ui.SUBHEAD); say.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        say.setContentDescription("글로 말하기 (소리 내기 어려울 때)");
        Runnable sendSay = () -> { String text = say.getText().toString().trim(); if (!text.isEmpty()) { say.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); send(AssistantService.SAY, "text", text); say.setText(""); } };
        say.setOnEditorActionListener((v, actionId, event) -> { if (actionId == EditorInfo.IME_ACTION_SEND) { sendSay.run(); return true; } return false; });
        sayRow.addView(say, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)); sendParams.leftMargin = ui.dp(8);
        sayRow.addView(ui.iconButton(R.drawable.ic_send, ACCENT, ON_ACCENT, "보내기", sendSay), sendParams); stage.addView(sayRow, new LinearLayout.LayoutParams(-1, -2));
        words.setOnClickListener(v -> toggleVoice()); words.setFocusable(true); words.setContentDescription("음성 대화 시작 또는 끝내기"); Ui.pressable(words, true);
        // The call's controls: one slim row of round icons with no captions (each says its name to a screen reader), the
        // call's own on the left and ending it alone on the right. They are the foot of the card, not a second card.
        LinearLayout controls = ui.row(); controls.setGravity(android.view.Gravity.CENTER_VERTICAL); controls.setPadding(0, ui.dp(8), 0, 0);
        muteButton = ui.dial(R.drawable.ic_mic_off, "음소거", false, () -> send(AssistantService.MUTE, null, null));
        cameraButton = ui.dial(R.drawable.ic_video, "카메라", false, this::toggleCamera);
        typeButton = ui.dial(R.drawable.ic_chat, "글로 말하기", false, this::toggleTyping);
        stopButton = ui.dial(R.drawable.ic_stop, "모두 중지", false, () -> send(AssistantService.STOP, null, null));
        endButton = ui.dial(R.drawable.ic_close, "끝내기", true, this::toggleVoice);
        for (Ui.Dial dial : new Ui.Dial[]{muteButton, cameraButton, typeButton, stopButton}) { LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2); at.rightMargin = ui.dp(6); controls.addView(dial, at); }
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1)); controls.addView(endButton, new LinearLayout.LayoutParams(-2, -2));
        stage.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        add(stage, 14);
        // Under the hero: the search sources an answer used (Google asks for them to be shown), and, once the call is over,
        // keeping what was said. Neither is there while nothing calls for it.
        LinearLayout live = ui.card(); liveCard = live;
        liveGrounding = groundingView(); loadedGrounding = ""; LinearLayout.LayoutParams groundingParams = new LinearLayout.LayoutParams(-1, ui.dp(110));
        live.addView(liveGrounding, groundingParams);
        liveActions = ui.actions(ui.chip("전체 보기", this::fullConversation), ui.chip("대화를 메모로 저장", this::saveConversation), ui.chip("기록 보기", () -> showRecords(INTERPRETER.equals(mode) ? 2 : DICTATION.equals(mode) ? 3 : 1)));
        live.addView(liveActions);
        add(live, 14);
        int asideFrom = body.getChildCount();
        // Places to go, in one slim row that scrolls sideways: they are doors, not the screen's subject.
        Runnable[] actions = {this::openBrowser, () -> showTab(4), () -> openSettings("phone"), () -> { showTab(3); typeInComposer(null); },
            () -> startActivity(new android.content.Intent(this, FileExplorerActivity.class)), () -> showRecords(INTERPRETER.equals(mode) ? 2 : DICTATION.equals(mode) ? 3 : 1)};
        int[] icons = {R.drawable.ic_browser, R.drawable.ic_gallery, R.drawable.ic_phone, R.drawable.ic_sparkle, R.drawable.ic_folder, R.drawable.ic_history};
        String[] titles = {"웹에서 하기", "이미지·영상", "폰 조작", "GitHub 작업", "내 휴대폰", "대화 기록"};
        // One tonal family: terracotta, amber, green, blue, teal and gray glyphs, tuned per mode in Ui.
        int[] tints = {ACCENT, Ui.ORANGE, Ui.GREEN, Ui.BLUE, Ui.TEAL, Ui.GRAY};
        View[] doors = new View[titles.length];
        for (int i = 0; i < titles.length; i++) doors[i] = ui.shortcut(icons[i], titles[i], tints[i], actions[i]);
        TextView doorsLabel = ui.text("바로 가기", Ui.FOOTNOTE, 650, MUTED); doorsLabel.setPadding(ui.dp(4), ui.dp(6), 0, ui.dp(8)); doorsLabel.setAccessibilityHeading(true); add(doorsLabel, 0);
        add(ui.strip(8, doors), 14);
        homeRecentWork();
        attachCamera();
        phoneRow = ui.column(); add(phoneRow, 8);
        for (int i = asideFrom; i < body.getChildCount(); i++) callAside.add(body.getChildAt(i));
        // Background work still going: one line, only while there is some.
        taskSummary = ui.banner("", ACCENT); taskSummary.setOnClickListener(v -> showTab(1)); taskSummary.setVisibility(View.GONE); add(taskSummary, 12);
    }
    /** Recording is its own home tab, not a Live API mode. Opening it never starts a microphone. */
    private void homeRecorder() {
        add(ui.sectionHeader("녹음"), 0);
        add(ui.footer("녹음 시작을 누르면 소리를 저장해요. 저장한 녹음과 녹취는 목록에서 다시 볼 수 있어요."), 8);
        LinearLayout recorderGroup = ui.group();
        TextView recordings = ui.text("목록 ›", 14, 600, ACCENT); recordings.setPadding(ui.dp(10), ui.dp(6), 0, ui.dp(6)); recordings.setContentDescription("녹음 목록 보기");
        recordings.setOnClickListener(v -> showRecords(0)); Ui.pressable(recordings, true);
        LinearLayout recorder = ui.listRow(R.drawable.ic_record, Ui.RED, "녹음", "눌러서 녹음 시작", recordings, this::toggleRecording);
        recordButton = (TextView) ((LinearLayout) recorder.getChildAt(1)).getChildAt(1);
        ui.addRow(recorderGroup, recorder, true);
        add(recorderGroup, 8);
        ScopedModels models = ScopedModels.recording(store);
        add(ui.listRow(R.drawable.ic_sparkle, ACCENT, "녹음 요약 모델 · 생각 깊이",
            WorkBook.author(models.engine()) + " · " + modelChip(models, models.engine()), ui.chevron(), this::pickRecordingModel), 8);
    }
    /** Whether the touch on 음성 비서 landed on its ▾ (the voice settings) rather than its name (just the mode). */
    private boolean arrowTapped;
    /**
     * 음성 비서's ▾ as a touch area of its own: the name selects the mode, the larger ▾ a little apart from it opens the voice
     * settings (a long press opens them too).
     */
    private void assistantArrow(TextView pill) {
        android.graphics.drawable.Drawable arrow = getDrawable(R.drawable.ic_chevron_down); if (arrow == null) return;
        int size = ui.dp(20), gap = ui.dp(8);
        arrow = arrow.mutate(); arrow.setBounds(0, 0, size, size);
        pill.setText("음성 비서"); pill.setCompoundDrawablesRelative(null, null, arrow, null); pill.setCompoundDrawablePadding(gap);
        pill.setCompoundDrawableTintList(new android.content.res.ColorStateList(new int[][] {{android.R.attr.state_selected}, {}}, new int[] {Ui.BG, Ui.TEXT}));
        pill.setPaddingRelative(pill.getPaddingStart(), pill.getPaddingTop(), ui.dp(13), pill.getPaddingBottom());
        View.OnTouchListener press = Ui.press(false);
        pill.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) arrowTapped = e.getX() >= v.getWidth() - v.getPaddingEnd() - size - gap;
            return press.onTouch(v, e);
        });
        pill.setOnLongClickListener(v -> { arrowTapped = true; v.performClick(); return true; });
    }
    private void selectHomeMode(int index) {
        boolean settings = arrowTapped; arrowTapped = false;
        recordingPage = index == 3;
        if (recordingPage) { stopCamera(); showTab(0); return; }
        String chosen = MODE_IDS[index];
        boolean unchanged = chosen.equals(mode) && (!store.voiceActive || chosen.equals(store.voiceMode));
        selectMode(chosen);
        if (unchanged) showTab(0);
        if (ASSISTANT.equals(chosen) && settings) voiceModeSheet();
    }
    private void voiceModeSheet() {
        Ui.Sheet sheet = ui.selectionSheet("음성 비서", "음성 엔진 · 모델 · 생각 깊이");
        LinearLayout choices = ui.column(); sheet.body.addView(choices);
        voiceModelChoices(choices, sheet);
        sheet.primary.setText("완료"); sheet.setOnDismissListener(d -> { if (tab == 0) showTab(0); }); sheet.show();
    }
    /** 음성 비서 엔진 (AppStore.assistantRealtime): the stored value and its name, Gemini Live first, as the default. */
    private static final String[] ENGINE_IDS = {AppStore.GEMINI_ENGINE, RealtimeVoice.Kind.GPT.name(), RealtimeVoice.Kind.GROK.name()};
    private String[] engineLabels() {
        String[] labels = new String[ENGINE_IDS.length];
        for (int i = 0; i < labels.length; i++) {
            RealtimeVoice.Kind kind = RealtimeVoice.Kind.of(ENGINE_IDS[i]);
            labels[i] = kind == null ? "Gemini Live" : AppStore.realtimeLabel(kind) + (VoiceCredentials.usable(store, kind) ? "" : " · 로그인 필요");
        }
        return labels;
    }
    /** One choice of the voice mode sheet. */
    private android.widget.RadioButton voiceChoice(String label, boolean selected, Runnable pick) {
        android.widget.RadioButton choice = new android.widget.RadioButton(this);
        choice.setText(label); choice.setTextSize(16); choice.setTextColor(TEXT);
        choice.setChecked(selected); choice.setMinHeight(ui.dp(52));
        choice.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        choice.setContentDescription(label + (selected ? ", 선택됨" : ""));
        choice.setOnClickListener(v -> pick.run());
        return choice;
    }
    /** Keep model choices visible next to depth, and refresh in place without stacking sheets. */
    private void voiceModelChoices(LinearLayout choices, Ui.Sheet sheet) {
        choices.removeAllViews();
        RealtimeVoice.Kind realtime = store.assistantRealtime();
        choices.addView(ui.label(realtime == null ? "선택한 모델 · " + AppStore.modelLabel(store.liveModel()) : "선택한 음성 · " + AppStore.realtimeLabel(realtime) + " · " + RealtimeVoice.shown(realtime, store.pickedVoice(realtime)), 13, MUTED));
        choices.addView(ui.sectionHeader("음성 엔진"));
        String[] engines = engineLabels(); String engine = store.assistantEngine();
        for (int i = 0; i < ENGINE_IDS.length; i++) {
            String id = ENGINE_IDS[i];
            choices.addView(voiceChoice(engines[i], id.equals(engine), () -> {
                if (!id.equals(store.assistantEngine())) { store.settings.edit().putString(AppStore.ASSISTANT_ENGINE, id).apply(); store.changed(); }
                voiceModelChoices(choices, sheet);
            }), new LinearLayout.LayoutParams(-1, -2));
        }
        if (realtime != null) {
            // GPT·Grok: its voices and its sign-in instead of the Live models and depth. GPT's newest (GPT-Live) come first.
            choices.addView(ui.sectionHeader(realtime.label + " 목소리"));
            String chosen = store.pickedVoice(realtime);
            for (String[] option : RealtimeVoice.voiceChoices(realtime)) choices.addView(voiceChoice(option[1], option[0].equals(chosen), () -> {
                if (!option[0].equals(store.pickedVoice(realtime))) { store.settings.edit().putString(AppStore.ASSISTANT_VOICE + realtime.name(), option[0]).apply(); store.changed(); }
                voiceModelChoices(choices, sheet);
            }), new LinearLayout.LayoutParams(-1, -2));
            if (realtime == RealtimeVoice.Kind.GPT) choices.addView(ui.label("‘최신’ 목소리는 GPT-Live로 말해요(Codex 로그인 필요). 열리지 않으면 다른 목소리로 바꾸지 않고 이유를 알려 줘요.", 12, MUTED));
            choices.addView(ui.sectionHeader("연결"));
            LinearLayout sign = ui.group(); realtimeSignIn(sign, realtime, sheet::dismiss); choices.addView(sign);
            choices.addView(ui.label("API 키는 설정 → 목소리와 대화에서 넣을 수 있어요. 대화 중 바꾸면 완료 후 ‘지금 적용’을 눌러 주세요.", 12, MUTED));
            return;
        }
        choices.addView(ui.sectionHeader("음성 비서 모델"));
        for (String[] option : GeminiProtocol.LIVE_MODELS) {
            String id = option[0];
            choices.addView(voiceChoice(option[1], id.equals(store.liveModel()), () -> {
                if (!id.equals(store.liveModel())) {
                    store.settings.edit().putString("liveModel", id).apply(); store.changed();
                }
                voiceModelChoices(choices, sheet);
            }), new LinearLayout.LayoutParams(-1, -2));
        }
        choices.addView(ui.label("대화 중 모델을 바꾸면 완료 후 ‘지금 적용’을 눌러 주세요.", 12, MUTED));
        if (GeminiProtocol.extended(store.liveModel())) {
            choices.addView(ui.sectionHeader("Live 생각 깊이"));
            choices.addView(ui.reasoningChoices(Reasoning.Profile.of(java.util.List.of("low", "medium", "high"), "medium"), store.liveThinkingLevel().toLowerCase(Locale.ROOT), value -> {
                store.settings.edit().putString("liveThinkingLevel", (value.isEmpty() ? "medium" : value).toUpperCase(Locale.ROOT)).apply(); store.changed();
            }));
        }
    }
    private void homeRecentWork() {
        JSONArray list = store.works(); if (list.length() == 0) return;
        LinearLayout heading = ui.row(); heading.addView(ui.text("최근 작업", 16, 650, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(ui.ghost("전체 보기", () -> showTab(3))); add(heading, 8);
        LinearLayout rows = ui.group();
        for (int i = 0; i < Math.min(3, list.length()); i++) { JSONObject item = list.optJSONObject(i); if (item == null) continue;
            String id = item.optString("id"); ui.addRow(rows, ui.listRow(R.drawable.ic_sparkle, ACCENT, item.optString("title", "새 대화"), WorkBook.RUNNING.equals(item.optString("state")) ? "진행 중" : "대화 이어서 보기", ui.chevron(), () -> { workId = id; showTab(3); }), true);
        } add(rows, 18);
    }
    private void interpreterChanged() {
        boolean live = store.voiceActive && INTERPRETER.equals(store.voiceMode);
        if (live) send(AssistantService.APPLY, null, null);
        store.status("통역: " + store.translationDirection() + (live ? " · 바뀐 언어로 연결 중…" : ""));
    }
    private void selectMode(String value) {
        if (value.equals(mode) && !store.voiceActive) return;
        if (store.voiceActive && value.equals(store.voiceMode)) return;
        mode = value;
        // Switching while a call is live restarts it in the new mode instead of silently keeping the old one.
        if (store.voiceActive) startVoice();
        showTab(0);
    }
    /** 카메라: on during a conversation with the assistant (asking for the permission once), off with a second tap. */
    private void toggleCamera() {
        if (cameraFeed != null) { stopCamera(); return; }
        if (!store.voiceActive || !ASSISTANT.equals(store.voiceMode)) { store.error("카메라는 음성 비서와 대화하는 동안 켤 수 있어요."); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION); return; }
        startCamera();
    }
    private void startCamera() {
        if (tab != 0 || recordingPage || !store.voiceActive || !ASSISTANT.equals(store.voiceMode) || cameraFeed != null) return;
        String costNotice = store.voiceNetwork.costWarning(dev.jun.gyeot.core.VoiceNetwork.CAMERA);
        if (costNotice != null) store.transcript("안내", costNotice);
        // Frames go to the conversation through the service; a frame while it is connecting is simply dropped.
        cameraFeed = new CameraFeed(this, jpeg -> { java.util.function.Consumer<String> input = store.cameraInput; if (input != null) input.accept(jpeg); },
            reason -> { store.error(reason); stopCamera(); });
        attachCamera(); refresh();
        store.transcript("안내", "카메라를 켰어요 · " + Brand.I + " 1초에 한 장씩 봐요");
    }
    private void stopCamera() {
        if (cameraFeed == null) return;
        cameraFeed.stop(); cameraFeed = null;
        if (cameraBox != null) { cameraBox.removeAllViews(); cameraBox.setVisibility(View.GONE); }
        if (orb != null) orb.setVisibility(View.VISIBLE);
        if (cameraButton != null) cameraButton.on(R.drawable.ic_video, "카메라", false);
        if (store.voiceActive) store.transcript("안내", "카메라를 껐어요");
    }
    /** The preview in place of the orb: the camera's 3:4 shape (4:3 sideways), as wide as the page allows, with a badge and a switch. */
    private void attachCamera() {
        if (cameraFeed == null || cameraBox == null) return;
        cameraBox.removeAllViews();
        android.util.DisplayMetrics screen = getResources().getDisplayMetrics();
        boolean sideways = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int width = screen.widthPixels - insetLeft - insetRight - ui.dp(32), height = sideways ? width * 3 / 4 : width * 4 / 3;
        int tallest = Math.round(screen.heightPixels * (sideways ? 0.7f : 0.55f));
        if (height > tallest) { height = tallest; width = sideways ? height * 4 / 3 : height * 3 / 4; }
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(width, height); box.bottomMargin = ui.dp(14);
        cameraBox.setLayoutParams(box); cameraBox.setBackground(ui.shape(0xff000000, 24)); cameraBox.setClipToOutline(true);
        android.view.TextureView preview = new android.view.TextureView(this); preview.setContentDescription("카메라 화면");
        cameraBox.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        TextView live = ui.badge("● 보는 중 · 1초에 한 장", Ui.RED);
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.START); top.setMargins(ui.dp(12), ui.dp(12), 0, 0);
        cameraBox.addView(live, top);
        TextView flip = ui.text("앞뒤 바꾸기", 13.5f, 650, 0xffffffff); flip.setBackground(ui.shape(0x80000000, 99)); flip.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        flip.setContentDescription("앞 카메라와 뒤 카메라 바꾸기"); flip.setOnClickListener(v -> { if (cameraFeed != null) cameraFeed.flip(); }); Ui.pressable(flip, false);
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.END); corner.setMargins(0, 0, ui.dp(12), ui.dp(12));
        cameraBox.addView(flip, corner);
        cameraBox.setVisibility(View.VISIBLE);
        if (orb != null) orb.setVisibility(View.GONE);
        cameraFeed.attach(preview);
    }
    private void toggleRecording() {
        if (store.recording) { send(AssistantService.RECORD_STOP, null, null); return; }
        if (store.voiceActive) { store.error("음성 대화 중에는 녹음할 수 없어요. 음성을 끝낸 뒤 녹음해 주세요."); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 21); return; }
        send(AssistantService.RECORD_START, null, null);
    }
    private void toggleVoice() { if (store.voiceActive) send(AssistantService.END_LIVE, null, null); else startVoice(); }
    private void startVoice() {
        if (!voiceUsable()) return;
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO);
        // Notifications are optional; ask once instead of on every start.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !store.settings.getBoolean("askedNotifications", false)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS); store.settings.edit().putBoolean("askedNotifications", true).apply();
        }
        if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), 20); return; }
        // One microphone: the main chat's spoken conversation ends first (what it said stays in the chat).
        VoiceChat talking = store.voiceChat; if (talking != null) talking.end("");
        send(AssistantService.LIVE, "mode", mode);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        // Both composers ask with the same code; the one on screen asked.
        if (showingMainChat && mainChat != null && mainChat.permissionResult(code, results)) return;
        if (workVoice != null && workVoice.permissionResult(code, results)) return;
        if (composerDictation != null && composerDictation.permissionResult(code, results)) return;
        if (code == PhoneCalendar.SETTINGS_REQUEST) { calendar().permissionResult(); return; }
        if (code == WakeWordUi.PERMISSION_REQUEST) { wakeWord().permissionResult(); return; }
        if (code == CAMERA_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { if (store.voiceActive && ASSISTANT.equals(store.voiceMode)) startCamera(); }
            else store.error("카메라로 보여 주려면 카메라 권한이 필요해요. Android 설정 → 앱 → " + Brand.NAME + " → 권한에서 켤 수 있어요.");
            return;
        }
        if (code == 21) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) send(AssistantService.RECORD_START, null, null);
            else store.error("녹음에는 마이크 권한이 필요해요.");
            return;
        }
        // 설정 → 마이크 허용하기: the home drops the row once it is allowed.
        if (code == SETTINGS_MIC) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) store.status("마이크를 허용했어요");
            // Android answered without asking (refused before): say where it is allowed; the next tap on the row opens that screen.
            else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) store.error("마이크가 꺼져 있어요 · ‘마이크 허용하기’를 한 번 더 누르면 Android 설정이 열려요");
            if (tab == 2) showTab(2);
            return;
        }
        if (code != 20) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) store.error("음성 시작에는 마이크 권한이 필요해요.");
            else new AlertDialog.Builder(this).setTitle("마이크 권한이 꺼져 있어요").setMessage("Android 설정 → 권한 → 마이크에서 '앱 사용 중에만 허용'을 선택해 주세요.")
                .setNegativeButton("닫기", null).setPositiveButton("설정 열기", (d, w) -> openAppSettings()).show();
            return;
        }
        send(AssistantService.LIVE, "mode", mode);
    }
    private boolean hasKey() {
        if (store.secrets.hasKey()) return true;
        store.error("설정에서 Gemini API 키를 입력해 주세요."); openSettings("key"); return false;
    }
    /** What this voice call needs: the assistant on GPT or Grok its sign-in or key, anything else (Gemini Live) the Gemini key. */
    private boolean voiceUsable() {
        RealtimeVoice.Kind realtime = ASSISTANT.equals(mode) ? store.assistantRealtime() : null;
        if (realtime == null) return hasKey();
        if (VoiceCredentials.usable(store, realtime)) return true;
        store.error(VoiceCredentials.assistantMissing(realtime)); openSettings("voice"); return false;
    }
    private void send(String action, String name, String value) {
        Intent intent = new Intent(this, AssistantService.class).setAction(action); if (name != null) intent.putExtra(name, value);
        send(intent);
    }
    private void send(Intent intent) {
        try {
            String action = intent.getAction();
            if (AssistantService.SUMMARIZE.equals(action)) intent.putExtra("modelSelection", ScopedModels.recording(store).snapshot().toString());
            if (store.serviceActive) startService(intent);
            else if (AssistantService.LIVE.equals(action) || AssistantService.TASK.equals(action) || AssistantService.RECORD_START.equals(action) || AssistantService.SUMMARIZE.equals(action) || AssistantService.WORK_SEND.equals(action) || AssistantService.WORK_RECOVER.equals(action) || AssistantService.WORK_STOP.equals(action) || AssistantService.WORK_SORT.equals(action) || AssistantService.VIDEO_START.equals(action) || AssistantService.VIDEO_RESUME.equals(action)
                // The waiting instructions' 이어서 실행 · 모두 취소 · 지금 전달: after a stop or an error the service may be gone, and these wake it.
                || AssistantService.WORK_NEXT.equals(action) || AssistantService.WORK_CLEAR.equals(action) || AssistantService.WORK_STEER_NOW.equals(action)) startForegroundService(intent);
        } catch (Exception e) { store.error("실행하지 못했어요: " + e.getClass().getSimpleName()); }
    }
    /** Queues background work on the chosen engine after checking its credentials; false when it could not be sent. */
    private boolean sendTask(String text, String engine) {
        if (TaskBook.CHATGPT.equals(engine)) {
            if (store.analyst().isEmpty()) { store.error("설정에서 ChatGPT, Claude, Gemini, Grok, NVIDIA NIM 중 하나를 먼저 연결해 주세요."); openSettings(store.settings.contains("analyst") ? "ai" : ""); return false; }
        } else if (!hasKey()) return false;
        send(new Intent(this, AssistantService.class).setAction(AssistantService.TASK).putExtra("request", text).putExtra("engine", engine));
        return true;
    }
    /** 전체 보기: every caption kept, on a sheet of its own, while no call is on too (the card shows only its last turns). */
    private void fullConversation() {
        JSONArray lines = store.conversation();
        Ui.Sheet sheet = ui.selectionSheet("대화 전체", lines.length() == 0 ? "아직 대화가 없어요 · 지난 대화는 ‘기록 보기’에 있어요" : "최근 대화 " + lines.length() + "줄");
        LinearLayout all = ui.column();
        for (int i = 0; i < lines.length(); i++) { JSONObject line = lines.optJSONObject(i); if (line != null) all.addView(turn(line.optString("who"), rich(line.optString("text")), false, AppStore.lineKey(line))); }
        sheet.body.addView(all);
        sheet.primary.setText("닫기"); sheet.primary.setOnClickListener(v -> sheet.dismiss());
        sheet.show();
    }
    private void saveConversation() {
        String text = store.conversationText();
        if (text.isEmpty()) { store.error("저장할 대화가 아직 없어요."); return; }
        try { store.saveNote((DICTATION.equals(store.voiceMode) ? "받아쓰기 " : "대화 ") + WHEN.format(Instant.now().atZone(ZoneId.systemDefault())), text); store.status("대화를 메모로 저장했어요"); }
        catch (Exception e) { store.error(e.getMessage() == null ? "메모를 저장하지 못했어요." : e.getMessage()); }
    }
    private void refresh() {
        // Main/Work updates continue in their engines, but a hidden legacy page cannot steal the selected content.
        if(showingMainChat)return;
        if (composerDictation != null) composerDictation.validateScope();
        boolean phoneConnected = PhoneService.connected();
        // Coming back from Android's accessibility settings: show the new state at once.
        if (tab == 2 && phoneSeen != null && phoneSeen != phoneConnected) { phoneSeen = phoneConnected; showTab(2); return; }
        phoneSeen = phoneConnected;
        status.setText(store.voiceActive && store.thinking() ? store.thinkingText() : store.status);
        statusDot.setBackground(ui.shape(store.recording ? Ui.RED : store.voiceActive ? SUCCESS : MUTED, 99));
        // A conversation has no title for the status line to sit under: with nothing to report (empty, or the idle 준비됐어요),
        // it leaves no lone dot above the messages. Settings shows its states in its rows, so there too only news shows.
        boolean nothing = status.getText().length() == 0 || "준비됐어요".contentEquals(status.getText());
        statusRow.setVisibility((tab == 3 || tab == 2 || tab == 1) && nothing && !store.recording && !store.voiceActive ? View.GONE : View.VISIBLE);
        error.setText(store.error); error.setVisibility(store.error.isBlank() ? View.GONE : View.VISIBLE);
        applyBanner.setVisibility(store.settingsBehind() ? View.VISIBLE : View.GONE);
        exitBanner.setText(store.exitNotice + " · 눌러서 배터리 설정 열기"); exitBanner.setVisibility(store.exitNotice.isBlank() ? View.GONE : View.VISIBLE);
        if (!store.exitNotice.isBlank()) DiagnosticsExport.exitNoticeShown(this);
        if (orb != null) {
            boolean offline = store.voiceActive && store.voiceNetwork.offline();
            // The call's own connection phase (core/VoiceLink), never the status text: a back-off or a model switch shows
            // connecting, a caption socket's retry does not.
            boolean connecting = store.voiceActive && !offline && store.voiceLink.connecting();
            boolean thinking = store.voiceActive && store.thinking() && !connecting && !offline && store.outputDraft.isBlank();
            orb.state(VoiceOrb.of(store));
            orbTitle.setText(!store.voiceActive ? "눌러서 말하기" : offline ? "오프라인" : store.muted ? "음소거됨" : connecting ? "연결하는 중…" : !store.outputDraft.isBlank() ? "말하는 중" : thinking ? store.thinkingName() + "가 생각하는 중…" : "듣고 있어요");
            RealtimeVoice.Kind realtime = store.assistantRealtime();
            String model = AppStore.modelLabel(INTERPRETER.equals(mode) ? GeminiProtocol.TRANSLATE : DICTATION.equals(mode) ? GeminiProtocol.TRANSCRIBE
                : store.voiceActive && !store.connectedVoiceModel.isEmpty() ? store.connectedVoiceModel : realtime != null ? realtime.model : store.activeModel() == null ? store.liveModel() : store.activeModel());
            orbSub.setText(offline ? "인터넷이 돌아오면 이어서 연결해요" : thinking ? Math.max(0, (System.currentTimeMillis() - store.thinkingStartedAt()) / 1000) + "초째 · 그동안 말을 걸어도 돼요"
                : store.voiceActive ? "구슬을 누르면 대화를 끝내요" : model + (ASSISTANT.equals(mode) ? " · 검색 " + (store.searchWanted() ? "켜짐" : "꺼짐") : ""));
            orb.setContentDescription(store.voiceActive ? "음성 대화 끝내기" : "음성 대화 시작");
        }
        if (muteButton != null) { muteButton.setVisibility(store.voiceActive ? View.VISIBLE : View.GONE); muteButton.on(store.muted ? R.drawable.ic_mic_off : R.drawable.ic_mic, store.muted ? "다시 듣기" : "음소거", store.muted); }
        // 모두 중지 ends everything this app is doing (a call and background work); 끝내기 ends the call alone.
        if (stopButton != null) stopButton.setVisibility(store.serviceActive ? View.VISIBLE : View.GONE);
        if (endButton != null) endButton.setVisibility(store.voiceActive ? View.VISIBLE : View.GONE);
        // During a call the page is the call: the greeting steps aside.
        if (homeIntro != null) homeIntro.setVisibility(store.voiceActive ? View.GONE : View.VISIBLE);
        // And the orb gives room to what is being said.
        // The page's big title steps aside too (the bar above keeps the screen's name), so the call fits one screen.
        if (orb != null && tab == 0) titleView.setVisibility(store.voiceActive ? View.GONE : View.VISIBLE);
        if (orb != null && heroHead != null && heroCall != store.voiceActive) {
            // A call's head is one slim row (a small orb, its state and one quiet line beside it); idle it is the big centered orb.
            boolean call = heroCall = store.voiceActive; int size = ui.dp(call ? CALL_ORB : orbIdleSize);
            heroHead.setOrientation(call ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL); heroHead.setGravity(call ? android.view.Gravity.CENTER_VERTICAL : android.view.Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) orb.getLayoutParams(); lp.width = size; lp.height = size; orb.setLayoutParams(lp);
            heroWords.setGravity(call ? android.view.Gravity.START : android.view.Gravity.CENTER_HORIZONTAL); heroWords.setPadding(ui.dp(call ? 12 : 8), ui.dp(call ? 0 : 4), ui.dp(8), ui.dp(call ? 0 : 4));
            heroWords.setLayoutParams(call ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2));
            orbTitle.setTextSize(call ? Ui.HEADLINE : Ui.TITLE1); orbTitle.setGravity(call ? android.view.Gravity.START : android.view.Gravity.CENTER);
            orbSub.setTextSize(call ? Ui.CAPTION : Ui.FOOTNOTE); orbSub.setGravity(call ? android.view.Gravity.START : android.view.Gravity.CENTER); orbSub.setPadding(0, ui.dp(call ? 1 : 6), 0, 0);
            heroStage.setPadding(ui.dp(call ? 14 : 20), ui.dp(call ? 14 : 26), ui.dp(call ? 14 : 20), ui.dp(call ? 14 : 24));
            // The call is the page: the card takes the height the screen has left and gives it to the conversation, and what
            // is beside the call (the mode pills, the doors, recent work, the phone row) waits until it ends.
            LinearLayout.LayoutParams stageAt = (LinearLayout.LayoutParams) heroStage.getLayoutParams(); stageAt.height = call ? 0 : -2; stageAt.weight = call ? 1 : 0; heroStage.setLayoutParams(stageAt);
            if (said != null) { LinearLayout.LayoutParams saidAt = (LinearLayout.LayoutParams) said.getLayoutParams(); saidAt.height = call ? 0 : -2; saidAt.weight = call ? 1 : 0; said.setLayoutParams(saidAt); }
            for (View aside : callAside) aside.setVisibility(call ? View.GONE : View.VISIBLE);
        }
        boolean seeing = store.voiceActive && ASSISTANT.equals(store.voiceMode);
        if (cameraFeed != null && !seeing) stopCamera();
        if (cameraButton != null) { cameraButton.setVisibility(seeing ? View.VISIBLE : View.GONE); cameraButton.on(R.drawable.ic_video, cameraFeed != null ? "카메라 끄기" : "카메라", cameraFeed != null); }
        if (sayRow != null) {
            // Typing is the assistant call's: its control shows then, and the line itself only once that control opened it.
            boolean canType = store.voiceActive && ASSISTANT.equals(store.voiceMode); if (!canType) typing = false;
            sayRow.setVisibility(canType && typing ? View.VISIBLE : View.GONE);
            if (typeButton != null) { typeButton.setVisibility(canType ? View.VISIBLE : View.GONE); typeButton.on(R.drawable.ic_chat, typing ? "글로 말하기 닫기" : "글로 말하기", typing); }
        }
        if (liveGrounding != null) {
            liveGrounding.setVisibility(store.searchHtml.isBlank() ? View.GONE : View.VISIBLE);
            if (!loadedGrounding.equals(store.searchHtml)) { loadedGrounding = store.searchHtml; liveGrounding.loadDataWithBaseURL("https://www.google.com", loadedGrounding, "text/html", "UTF-8", null); }
        }
        if (said != null) {
            JSONArray lines = store.conversation(); int first = Math.max(0, lines.length() - CALL_TURNS);
            boolean thinkingNow = store.outputDraft.isBlank() && store.voiceActive && store.thinking();
            // Rebuilt only when the words changed: a redraw for something else leaves the list where the user scrolled it.
            StringBuilder key = new StringBuilder();
            for (int i = first; i < lines.length(); i++) { JSONObject line = lines.optJSONObject(i); key.append(line.optString("who")).append('\u0001').append(line.optString("text")).append('\u0002'); }
            key.append(VoiceClips.playingKey()).append('\u0004');
            key.append(store.interim).append('\u0003').append(store.outputDraft).append('\u0003').append(thinkingNow ? store.thinkingText() : "");
            boolean spoken = lines.length() > first || !store.interim.isBlank() || !store.outputDraft.isBlank() || thinkingNow;
            if (!key.toString().equals(shownSaid)) {
                // Follows the newest words unless the user scrolled up to read.
                boolean atFoot = shownSaid.isEmpty() || saidList.getHeight() - said.getScrollY() - said.getHeight() < ui.dp(48);
                shownSaid = key.toString(); saidList.removeAllViews();
                if (!spoken) {
                    TextView waiting = ui.text("말씀하시면 여기에 보여요.\n다른 앱으로 이동해도 연결은 유지돼요.", Ui.SUBHEAD, 400, MUTED); waiting.setGravity(android.view.Gravity.CENTER); waiting.setPadding(ui.dp(12), ui.dp(18), ui.dp(12), ui.dp(18)); saidList.addView(waiting);
                }
                for (int i = first; i < lines.length(); i++) { JSONObject line = lines.optJSONObject(i); saidList.addView(turn(line.optString("who"), rich(line.optString("text")), false, AppStore.lineKey(line))); }
                if (!store.interim.isBlank()) saidList.addView(turn("나", "… " + store.interim, true, null));
                if (!store.outputDraft.isBlank()) saidList.addView(turn(Brand.NAME, store.outputDraft, false, null));
                else if (thinkingNow) saidList.addView(turn(Brand.NAME, "··· " + store.thinkingText(), true, null));
                if (atFoot) said.post(this::pinSaid);
            }
            // Nothing said and no call: neither is there, so an idle home is the orb and its row of doors.
            said.setVisibility(store.voiceActive || spoken ? View.VISIBLE : View.GONE);
            // During a call only an answer's search sources show under the card; keeping the conversation comes after it.
            if (liveActions != null) liveActions.setVisibility(store.voiceActive ? View.GONE : View.VISIBLE);
            if (liveCard != null) liveCard.setVisibility(store.voiceActive ? (store.searchHtml.isBlank() ? View.GONE : View.VISIBLE) : spoken ? View.VISIBLE : View.GONE);
        }
        if (taskSummary != null) {
            int pending = store.tasks.pendingCount();
            taskSummary.setText(pending > 0 ? "백그라운드 작업 " + pending + "개 진행 중 · 눌러서 기록 보기" : "");
            taskSummary.setVisibility(pending > 0 ? View.VISIBLE : View.GONE);
        }
        if (tab == 3) {
            updateComposer();
            // A thread just sent from the list opens by itself; a finished or new turn redraws; streaming text updates in place.
            if (!store.workOpen.isEmpty()) { workId = store.workOpen; store.workOpen = ""; showTab(3); return; }
            boolean changed = store.worksVersion != workDrawn || store.galleryVersion != workGalleryDrawn || store.workInterruptVersion != workInterruptDrawn;
            if (changed) { int y = scroll.getScrollY(); showTab(3); if (workId.isEmpty()) scroll.post(() -> scroll.scrollTo(0, y)); return; }
            dev.jun.gyeot.core.WorkRoomState.Snapshot roomLive = store.workLive.get(workId);
            if (workStageView != null && !roomLive.present) workStageView.setText(stageLine(store.workStages.getOrDefault(workId, "차례를 기다리는 중")));
            if (workStageView != null && roomLive.present) {
                // Follow the text as it streams in, unless the user scrolled up to read.
                boolean following = scroll.getScrollY() + scroll.getHeight() >= body.getHeight() - ui.dp(160);
                workStageView.setText(stageLine(roomLive.stage.isBlank() ? "생각하는 중" : roomLive.stage));
                workAnswers().write(roomLive.text);
                if (following) scroll.post(() -> scroll.scrollTo(0, body.getHeight()));
            }
        }
        refreshWorkProgress(); refreshTeamProgress();
        if (tab == 4 && store.galleryVersion != galleryDrawn) { int y = scroll.getScrollY(); showTab(4); scroll.post(() -> scroll.scrollTo(0, y)); return; }
        if (recordButton != null) { recordButton.setText(store.recording ? "녹음 중 · " + AssistantService.durationText(System.currentTimeMillis() - store.recordingStartedAt) + " · 눌러서 멈추기" : "눌러서 녹음 시작"); recordButton.setTextColor(store.recording ? Ui.RED : MUTED); }
        if ((store.recording || store.thinking() || workRunning()) && !ticker.hasCallbacks(tick)) ticker.postDelayed(tick, 1000);
        if (phoneGptCheckState != null) phoneGptCheckState.setText(phoneGptCheckResult);
        if (edgeState != null) { if (tab == 2 && edgeDrawn != store.edgeButton()) { showTab(2); return; } edgeState.setText(edgeStatus()); }
        if (assistState != null) assistState.setText(assistStatus());
        if (archiveState != null) archiveState.setText(archiveStatus());
        if (phoneRow != null) {
            phoneRow.removeAllViews();
            if (store.voiceActive && ASSISTANT.equals(store.voiceMode) && store.phoneControl() && !PhoneService.connected()) {
                // Allowed in 들퍼레 but Android has not connected the service (often after an update): say so where it is missed.
                TextView missing = ui.banner("폰 조작이 연결되지 않았어요 · 눌러서 확인", ERROR); missing.setOnClickListener(v -> openSettings("phone"));
                phoneRow.addView(missing);
            }
            if (store.voiceActive && ASSISTANT.equals(store.voiceMode) && store.phoneControl() && PhoneService.connected()) {
                boolean armed = PhoneService.gate.armed();
                phoneRow.addView(ui.label(armed ? "폰 조작 켜짐 · “카톡 열어 줘”처럼 말해 보세요. " + Brand.NAME + " 버튼 위에 뜨는 작은 멈춤 버튼으로 바로 멈출 수 있어요." : "폰 조작이 꺼져 있어요.", 13, armed ? ACCENT : MUTED));
                // By the user's hand, so the voice assistant hears it (PhoneService.stopByUser / armByUser).
                phoneRow.addView(ui.actions(armed ? ui.chip("폰 조작 끄기", PhoneService::stopByUser) : ui.chip("폰 조작 켜기", () -> {
                    try { PhoneService.armByUser(); } catch (Exception e) { store.error(e.getMessage()); }
                })));
            }
        }
        if (tab == 1) refreshRecords(false);
        if (openAiBox != null) refreshOpenAi();
        if (grokBox != null) refreshGrok();
        if (nimBox != null) refreshNim();
        if (claudeBox != null) refreshClaude();
        if (geminiWorkBox != null) refreshGeminiWork();
        if (updateBox != null) refreshUpdate();
        if (usageBox != null) refreshUsage();
        dev.jun.gyeot.core.UpdateCheck.Update update = store.updater.available;
        updateBanner.setText(update == null ? "" : "새 버전 " + update.version + "이 나왔어요 · 눌러서 업데이트 ›");
        updateBanner.setVisibility(update == null || tab == 2 ? View.GONE : View.VISIBLE);
    }

    // ── 기록 ─────────────────────────────────────────────────────────────────

    private void showRecords(int view) { recordsView = view; showTab(1); }
    private void records() {
        add(ui.switcher(new String[]{"대화", "음성·메모 기록"}, 1, index -> { if (index == 0) showTab(3); }), 8);
        titleView.setText("기록");
        add(searchBox("기록 검색", query, value -> { query = value; refreshRecords(false); }), 8);
        add(ui.smallPills(RECORD_VIEWS, recordsView, target -> { if (recordsView != target) showRecords(target); }), 6);
        recordsList = ui.column(); add(recordsList, 0);
        lastRecords = ""; refreshRecords(true);
    }
    /**
     * Rebuilds the list below the search box only when its data changed, keeping the scroll position. In the work view live
     * captions change nothing, so expanded reports and selections stay put during a call.
     */
    private void refreshRecords(boolean force) {
        if (recordsList == null) return;
        String records = query + "\u0000" + recordsView + ":" + (recordsView == 0 ? store.tasks.version() + ":" + store.notesVersion + ":" + store.recordingsVersion + ":" + playingId
            : store.sessionsVersion + ":" + store.conversationVersion);
        if (!force && records.equals(lastRecords)) return;
        int y = scroll.getScrollY();
        lastRecords = records; destroyWebViews(recordsList); recordsList.removeAllViews();
        View everywhere = SearchActivity.link(this, ui, query); if (everywhere != null) ui.add(recordsList, everywhere, 12);
        if (recordsView == 0) buildRecords(recordsList); else buildSessions(recordsList, SESSION_MODES[recordsView]);
        scroll.post(() -> scroll.scrollTo(0, y));
    }
    private boolean matches(String... texts) {
        String joined = String.join("\n", texts).toLowerCase(Locale.ROOT);
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!word.isEmpty() && !joined.contains(word)) return false;
        return true;
    }
    private void buildRecords(LinearLayout list) {
        JSONArray tasks = store.tasks.snapshot(); boolean anyFinished = false, anyShown = false;
        for (int i = 0; i < tasks.length(); i++) if (!tasks.optJSONObject(i).optString("state").matches("queued|running")) anyFinished = true;
        list.addView(sectionHeader("백그라운드 작업", anyFinished ? ui.chip("완료된 작업 지우기", this::clearFinished) : null));
        for (int i = tasks.length() - 1; i >= 0; i--) {
            JSONObject task = tasks.optJSONObject(i);
            if (!matches(task.optString("request"), task.optString("report"), task.optString("error"))) continue;
            anyShown = true; ui.add(list, taskCard(task), 12);
        }
        if (!anyShown) ui.add(list, ui.label(query.isBlank() ? "아직 맡긴 작업이 없어요." : "검색과 맞는 작업이 없어요.", 15, MUTED), 20);
        JSONArray notes = store.notes(); anyShown = false;
        JSONArray recordings = store.recordings(); anyShown = false;
        list.addView(sectionHeader("녹음", null));
        for (int i = recordings.length() - 1; i >= 0; i--) {
            JSONObject recording = recordings.optJSONObject(i);
            if (!matches(recording.optString("title"), recording.optString("transcript"))) continue;
            anyShown = true; ui.add(list, recordingCard(recording), 12);
        }
        if (!anyShown) ui.add(list, ui.label(query.isBlank() ? "비서 화면의 ‘녹음 시작’으로 녹음해 보세요." : "검색과 맞는 녹음이 없어요.", 14, MUTED), 20);
        list.addView(sectionHeader("메모", ui.chip("새 메모", this::newNote)));
        for (int i = notes.length() - 1; i >= 0; i--) {
            JSONObject note = notes.optJSONObject(i);
            if (!matches(note.optString("title"), note.optString("body"))) continue;
            anyShown = true; ui.add(list, noteCard(note), 12);
        }
        if (!anyShown) ui.add(list, ui.label(query.isBlank() ? "음성으로 “이 내용을 메모해 줘”라고 말하거나 새 메모를 만들어 보세요." : "검색과 맞는 메모가 없어요.", 14, MUTED), 20);
    }
    /** Saved voice sessions of one mode, newest first, each with its lines and actions. */
    private void buildSessions(LinearLayout list, String mode) {
        JSONArray sessions = store.sessions(mode); boolean anyShown = false;
        // 자동 정리: how long unpinned records stay (고정 ones always do), one slim chip above the list.
        int keep = store.sessionKeepDays();
        Button cleanup = ui.smallChip("자동 정리 · " + keepLabel(keep), this::chooseKeepDays); cleanup.setContentDescription("기록 자동 정리 기간 바꾸기");
        LinearLayout tools = ui.row(); tools.addView(cleanup); ui.add(list, tools, 8);
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i); String text = AppStore.sessionText(session);
            if (!matches(text)) continue;
            anyShown = true; ui.add(list, sessionCard(session, mode), 12);
        }
        if (!anyShown) ui.add(list, ui.label(!query.isBlank() ? "검색과 맞는 기록이 없어요."
            : INTERPRETER.equals(mode) ? "비서 화면에서 ‘통역’을 시작하면 들린 말과 통역이 여기에 쌓여요."
            : DICTATION.equals(mode) ? "비서 화면에서 ‘받아쓰기’를 시작하면 여기에 쌓여요." : "음성 비서와 대화하면 여기에 쌓여요.", 14, MUTED), 20);
    }
    private View sessionCard(JSONObject session, String mode) {
        String id = session.optString("id"); JSONArray lines = session.optJSONArray("lines");
        long started = session.optLong("startedAt"), length = Math.max(0, session.optLong("endedAt") - started);
        boolean pinned = session.optBoolean("pinned");
        // The AI's title for it once the call is over (else its date), the date then moving to the small line above.
        String named = session.optString("title"), summary = session.optString("summary");
        String date = when(started) + (INTERPRETER.equals(mode) ? " · " + session.optString("direction", "한국어 ↔ " + languageLabel(session.optString("language"))) : "");
        String title = named.isBlank() ? date : named;
        LinearLayout card = ui.card();
        card.addView(meta(INTERPRETER.equals(mode) ? Ui.TEAL : DICTATION.equals(mode) ? Ui.ORANGE : ACCENT, (pinned ? "고정 · " : "") + (named.isBlank() ? "" : date + " · ") + AssistantService.durationText(length) + " · " + lines.length() + "줄"));
        TextView sessionTitle = ui.title(title, 17); sessionTitle.setPadding(0, ui.dp(6), 0, 0); card.addView(sessionTitle);
        if (!summary.isBlank()) { TextView about = ui.label(summary, 14, MUTED); about.setPadding(0, ui.dp(4), 0, 0); card.addView(about); }
        LinearLayout body = ui.column(); body.setPadding(0, ui.dp(8), 0, 0); card.addView(body);
        int preview = Math.min(lines.length(), 6);
        Runnable[] fill = new Runnable[1]; boolean[] expanded = {false};
        TextView toggle = ui.label("", 13, ACCENT); toggle.setPadding(0, ui.dp(6), 0, 0);
        fill[0] = () -> {
            body.removeAllViews();
            int shown = expanded[0] ? Math.min(lines.length(), 300) : preview;
            for (int i = 0; i < shown; i++) {
                JSONObject line = lines.optJSONObject(i); String who = line.optString("who");
                TextView label = ui.label(who, 12, "들린 말".equals(who) ? MUTED : ACCENT); label.setPadding(0, ui.dp(i == 0 ? 0 : 8), 0, 0); body.addView(label);
                TextView text = ui.label("", 15, TEXT); text.setText(rich(line.optString("text"))); text.setTextIsSelectable(true); body.addView(text);
            }
            toggle.setVisibility(lines.length() > preview ? View.VISIBLE : View.GONE);
            toggle.setText(expanded[0] ? "접기" : "전체 보기 (" + lines.length() + "줄)");
        };
        toggle.setOnClickListener(v -> { expanded[0] = !expanded[0]; fill[0].run(); });
        fill[0].run(); card.addView(toggle);
        String text = AppStore.sessionText(session), heading = (INTERPRETER.equals(mode) ? "통역 " : DICTATION.equals(mode) ? "받아쓰기 " : "대화 ") + when(started);
        // 이어서 말하기: a call that picks this conversation up (the voice assistant's records); 고정 keeps it on top and from clean-up.
        LinearLayout lead = ui.row();
        if (ASSISTANT.equals(mode)) { Button resume = withIcon(ui.smallChip("이어서 말하기", () -> continueCall(id)), R.drawable.ic_mic); resume.setTextColor(ACCENT); lead.addView(resume); }
        Button pin = ui.smallChip(pinned ? "고정 해제" : "고정", () -> store.pinSession(id, !pinned)); LinearLayout.LayoutParams pinAt = new LinearLayout.LayoutParams(-2, -2); pinAt.leftMargin = ui.dp(6);
        lead.addView(pin, lead.getChildCount() == 0 ? new LinearLayout.LayoutParams(-2, -2) : pinAt);
        LinearLayout.LayoutParams leadAt = new LinearLayout.LayoutParams(-2, -2); leadAt.topMargin = ui.dp(8); card.addView(lead, leadAt);
        card.addView(ui.actions(ui.chip("문서로 보기", () -> showDocument(heading, sessionMarkdown(lines), null)), ui.chip("복사", () -> copy(text)), ui.chip("공유", () -> share(heading + "\n\n" + text)),
            ui.chip("메모로 저장", () -> saveAsNote(heading, text)), archiveChip(heading, sessionMarkdown(lines), "", Brand.NAME + " · Gemini Live"),
            ui.chip("삭제", () -> confirm("이 기록을 지울까요?", "지우기", () -> { try { store.deleteSession(id); } catch (Exception e) { store.error("기록을 지우지 못했어요."); } }))));
        return SwipeDelete.wrap(ui, card, "이 기록", () -> {
            try { store.deleteSession(id); return true; } catch (Exception e) { store.error("기록을 지우지 못했어요."); return false; }
        });
    }
    /** A chip with a small leading icon in its label's color. */
    private Button withIcon(Button chip, int icon) {
        android.graphics.drawable.Drawable d = getDrawable(icon); if (d == null) return chip;
        d = d.mutate(); d.setTint(ACCENT); d.setBounds(0, 0, ui.dp(16), ui.dp(16)); chip.setCompoundDrawablesRelative(d, null, null, null); chip.setCompoundDrawablePadding(ui.dp(6));
        return chip;
    }
    /** 이어서 말하기: the voice assistant starts a call that picks this saved conversation up. */
    private void continueCall(String id) {
        if (store.voiceActive) { android.widget.Toast.makeText(this, "지금 통화 중이에요 · 끝낸 뒤에 이어서 말해 주세요", android.widget.Toast.LENGTH_SHORT).show(); return; }
        store.continueFrom(id); mode = ASSISTANT; recordingPage = false; showTab(0); startVoice();
    }
    private static String keepLabel(int days) { return days <= 0 ? "계속 보관" : days >= 365 ? "1년" : days + "일"; }
    /** 자동 정리: keep every record (up to 200), or clear unpinned ones older than 30 days, 90 days or a year. */
    private void chooseKeepDays() {
        Ui.Sheet sheet = ui.selectionSheet("기록 자동 정리", "고정한 기록은 지우지 않아요");
        LinearLayout group = ui.group(); int now = store.sessionKeepDays();
        for (int days : AppStore.KEEP_DAYS) ui.addRow(group, ui.listRow(days == now ? R.drawable.ic_check : 0, ACCENT, days <= 0 ? "계속 보관" : keepLabel(days) + " 지나면 지우기",
            days <= 0 ? "최근 200개까지 남겨요" : "고정하지 않은 기록만", null, () -> { store.sessionKeepDays(days); sheet.dismiss(); }), true);
        sheet.body.addView(group); sheet.primary.setText("닫기"); sheet.primary.setOnClickListener(v -> sheet.dismiss()); sheet.show();
    }
    private static String sessionMarkdown(JSONArray lines) {
        StringBuilder markdown = new StringBuilder();
        for (int i = 0; i < lines.length(); i++) { JSONObject line = lines.optJSONObject(i); markdown.append("**").append(line.optString("who")).append("**  \n").append(line.optString("text")).append("\n\n"); }
        return markdown.toString();
    }
    private View sectionHeader(String title, Button action) {
        LinearLayout header = ui.row(); header.setPadding(ui.dp(2), ui.dp(22), 0, ui.dp(10));
        header.addView(ui.title(title, 21), new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) header.addView(action);
        return header;
    }
    /** The small line above a record's title: a colored state dot and details. */
    private View meta(int color, String text) {
        LinearLayout line = ui.row();
        View dot = new View(this); dot.setBackground(ui.shape(color, 99)); line.addView(dot, new LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)));
        TextView words = ui.text(text, 12.5f, 550, MUTED); words.setSingleLine(true); words.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.leftMargin = ui.dp(7); line.addView(words, p);
        return line;
    }
    private View taskCard(JSONObject task) {
        String id = task.optString("id"), state = task.optString("state"), report = task.optString("report"), requestText = task.optString("request");
        LinearLayout item = ui.card();
        long at = task.optLong("finishedAt", task.optLong("createdAt"));
        String engine = TaskBook.CHATGPT.equals(task.optString("engine")) ? TaskBook.CHATGPT : TaskBook.RECORDING.equals(task.optString("engine")) ? TaskBook.RECORDING : TaskBook.GEMINI;
        String engineName = engine.equals(TaskBook.CHATGPT) ? "AI 분석" : engine.equals(TaskBook.RECORDING) ? "녹음 요약" : "Gemini 조사";
        int stateColor = state.matches("failed|interrupted") ? ERROR : state.matches("queued|running") ? WARNING : state.equals("cancelled") ? MUTED : SUCCESS;
        item.addView(meta(stateColor, stateName(state) + " · " + engineName + (at > 0 ? " · " + when(at) : "")));
        TextView prompt = ui.title(requestText, 17); prompt.setTextIsSelectable(true); prompt.setPadding(0, ui.dp(6), 0, 0); item.addView(prompt);
        String content = report.isBlank() ? task.optString("error") : report;
        if (!content.isBlank()) item.addView(report.isBlank() ? expandable(content, MUTED) : expandableMarkdown(report, TEXT));
        List<Button> actions = new ArrayList<>();
        if (state.matches("queued|running")) actions.add(ui.chip("취소", () -> send(AssistantService.CANCEL, "id", id)));
        if (state.matches("failed|interrupted|cancelled")) actions.add(ui.chip("다시 맡기기", () -> {
            if (engine.equals(TaskBook.RECORDING)) summarizeRecording(task.optString("recording")); else sendTask(requestText, engine);
        }));
        if (!report.isBlank()) {
            actions.add(ui.chip("문서로 보기", () -> showDocument(requestText, report, task.optJSONArray("sources"))));
            actions.add(ui.chip("복사", () -> copy(report)));
            actions.add(ui.chip("공유", () -> share(requestText + "\n\n" + report)));
            actions.add(ui.chip("메모로 저장", () -> saveAsNote(requestText, report)));
            Button keep = archiveChip(requestText, report, requestText, Brand.NAME + " · " + (engine.equals(TaskBook.CHATGPT) ? (report.contains("— Claude 분석") ? "Claude" : report.contains("— Gemini 분석") ? "Gemini" : "ChatGPT") : "Gemini"));
            if (keep != null) actions.add(keep);
        }
        if (!state.matches("queued|running")) actions.add(ui.chip("삭제", () -> confirm("이 작업 기록을 지울까요?", "지우기", () -> { try { store.removeTask(id); } catch (Exception e) { store.error(e.getMessage()); } })));
        item.addView(ui.actions(actions.toArray(new Button[0])));
        JSONArray sources = task.optJSONArray("sources");
        if (sources != null && sources.length() > 0) {
            TextView heading = ui.label("출처", 12, ACCENT); heading.setPadding(0, ui.dp(14), 0, ui.dp(2)); item.addView(heading);
            for (int s = 0; s < sources.length(); s++) {
                JSONObject source = sources.optJSONObject(s); if (source == null) continue;
                TextView link = ui.label("↗ " + (source.optString("title").isBlank() ? source.optString("uri") : source.optString("title")), 14, ACCENT);
                link.setPadding(0, ui.dp(6), 0, ui.dp(6)); link.setMaxLines(2); link.setEllipsize(TextUtils.TruncateAt.END); link.setOnClickListener(v -> open(source.optString("uri")));
                item.addView(link);
            }
        }
        String html = task.optString("searchHtml");
        if (!html.isBlank()) {
            WebView grounding = groundingView(); grounding.loadDataWithBaseURL("https://www.google.com", html, "text/html", "UTF-8", null);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, ui.dp(110)); p.topMargin = ui.dp(8); item.addView(grounding, p);
            item.addView(ui.actions(ui.chip("Google 검색 근거 더 보기", () -> showGrounding(html))));
        }
        return item;
    }
    private View noteCard(JSONObject note) {
        String id = note.optString("id"), title = note.optString("title"), body = note.optString("body");
        LinearLayout card = ui.card();
        card.addView(meta(ACCENT, "메모" + (note.optLong("createdAt") > 0 ? " · " + when(note.optLong("createdAt")) : "")));
        TextView heading = ui.title(title, 17); heading.setTextIsSelectable(true); heading.setPadding(0, ui.dp(6), 0, 0); card.addView(heading);
        card.addView(expandableMarkdown(body, MUTED));
        card.addView(ui.actions(ui.chip("문서로 보기", () -> showDocument(title, body, null)), ui.chip("복사", () -> copy(title + "\n\n" + body)), ui.chip("공유", () -> share(title + "\n\n" + body)),
            archiveChip(title, body, "", Brand.NAME),
            ui.chip("삭제", () -> confirm("‘" + title + "’ 메모를 지울까요?", "지우기", () -> { try { store.deleteNote(id); } catch (Exception e) { store.error(e.getMessage()); } }))));
        return card;
    }
    /** Long reports collapse to a preview with a toggle so the list stays scannable. */
    private View expandable(String content, int color) {
        LinearLayout box = ui.column(); box.setPadding(0, ui.dp(10), 0, 0);
        TextView text = ui.label(content, 15, color); text.setTextIsSelectable(true); box.addView(text);
        if (content.length() > 600 || content.split("\n").length > 10) {
            text.setMaxLines(10); text.setEllipsize(TextUtils.TruncateAt.END);
            TextView toggle = ui.label("전체 보기", 13, ACCENT); toggle.setPadding(0, ui.dp(6), 0, 0);
            toggle.setOnClickListener(v -> {
                boolean collapsed = text.getMaxLines() != Integer.MAX_VALUE;
                text.setMaxLines(collapsed ? Integer.MAX_VALUE : 10); text.setEllipsize(collapsed ? null : TextUtils.TruncateAt.END); toggle.setText(collapsed ? "접기" : "전체 보기");
            });
            box.addView(toggle);
        }
        return box;
    }
    /** Like expandable, with Markdown shown as formatting (headings, lists, emphasis, code; tables as rows) instead of symbols. */
    private View expandableMarkdown(String content, int color) {
        if (!Markdown.looksLike(content)) return expandable(content, color);
        LinearLayout box = ui.column(); box.setPadding(0, ui.dp(10), 0, 0);
        TextView text = ui.label("", 15, color); text.setText(RichText.render(content)); text.setLineSpacing(ui.dp(3), 1);
        text.setLinkTextColor(ACCENT); text.setTextIsSelectable(true); box.addView(text);
        if (content.length() > 600 || content.split("\n").length > 10) {
            text.setMaxLines(12); text.setEllipsize(TextUtils.TruncateAt.END);
            TextView toggle = ui.label("전체 보기", 13, ACCENT); toggle.setPadding(0, ui.dp(6), 0, 0);
            toggle.setOnClickListener(v -> {
                boolean collapsed = text.getMaxLines() != Integer.MAX_VALUE;
                text.setMaxLines(collapsed ? Integer.MAX_VALUE : 12); text.setEllipsize(collapsed ? null : TextUtils.TruncateAt.END); toggle.setText(collapsed ? "접기" : "전체 보기");
            });
            box.addView(toggle);
        }
        return box;
    }
    /** Markdown shown as formatting; plain text stays as it is. */
    /** Answers and reports as they read best: Markdown, or plain text with paragraphs, drawn by RichText; one-liners as they are. */
    static CharSequence rich(String text) {
        if (text == null) return "";
        if (!Markdown.looksLike(text) && !text.contains("\n\n")) return text;
        return RichText.render(text);
    }
    private static CharSequence trimTrailing(CharSequence text) {
        int end = text.length(); while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        return text.subSequence(0, end);
    }
    /** The full report as a formatted document, tables included: a script-free WebView whose links open in the browser. */
    private void showDocument(String title, String markdown, JSONArray sources) { showDocument(title, markdown, sources, ""); }
    /** As above; suggestions, Google's search suggestions for a grounded Gemini answer, go under it as Google sent them. */
    private void showDocument(String title, String markdown, JSONArray sources, String suggestions) {
        android.app.Dialog dialog = new android.app.Dialog(this, Ui.DARK ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        int paper = Ui.DARK ? 0xff000000 : 0xffffffff;
        LinearLayout page = ui.column(); page.setBackgroundColor(paper);
        LinearLayout bar = ui.row(); bar.setPadding(ui.dp(16), ui.dp(10), ui.dp(12), ui.dp(6));
        TextView done = ui.text("완료", 17, 650, ACCENT); done.setOnClickListener(v -> dialog.dismiss()); Ui.pressable(done, true);
        bar.addView(done, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(ui.iconButton(R.drawable.ic_send, FILL, ACCENT, "공유", () -> share(title + "\n\n" + markdown)), new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
        page.addView(bar);
        WebView web = groundingView(); web.setBackgroundColor(paper);
        String document = Markdown.document(title, markdown, Markdown.sources(sources)); int end = document.lastIndexOf("</body>");
        if (suggestions != null && !suggestions.isBlank() && suggestions.length() <= dev.jun.gyeot.core.MainSearch.SUGGESTIONS && end >= 0)
            document = document.substring(0, end) + "<div style=\"margin:22px 0 0\">" + suggestions + "</div>" + document.substring(end);
        web.loadDataWithBaseURL("about:blank", document, "text/html", "UTF-8", null);
        page.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        dialog.setContentView(page); dialog.setOnDismissListener(d -> web.destroy()); dialog.show();
    }
    private View recordingCard(JSONObject recording) {
        String id = recording.optString("id"), title = recording.optString("title"), transcript = recording.optString("transcript");
        LinearLayout card = ui.card();
        card.addView(meta(Ui.RED, "녹음 · " + AssistantService.durationText(recording.optLong("durationMs")) + String.format(Locale.ROOT, " · %.1f MB", recording.optLong("size") / 1048576.0) + (transcript.isBlank() ? "" : " · 녹취 있음")));
        TextView recordingTitle = ui.title(title, 17); recordingTitle.setPadding(0, ui.dp(6), 0, 0); card.addView(recordingTitle);
        List<Button> actions = new ArrayList<>();
        actions.add(ui.chip(id.equals(playingId) ? "■ 정지" : "▶ 재생", () -> { if (id.equals(playingId)) stopPlayback(); else play(id); }));
        actions.add(ui.chip(ScopedModels.recording(store).readyEngine().isEmpty() ? "요약하기" : WorkBook.author(ScopedModels.recording(store).engine()) + "로 요약", () -> summarizeRecording(id)));
        if (!transcript.isBlank()) {
            actions.add(ui.chip("녹취 보기", () -> showDocument(title + " · 녹취", transcript, null)));
            actions.add(ui.chip("녹취 공유", () -> share(title + "\n\n" + transcript)));
            Button keep = archiveChip(title + " · 녹취", transcript, "", Brand.NAME + " · Gemini 전사");
            if (keep != null) actions.add(keep);
        }
        actions.add(ui.chip("삭제", () -> confirm("‘" + title + "’ 녹음 파일을 지울까요? 요약 결과는 작업 기록에 남아요.", "지우기", () -> {
            if (id.equals(playingId)) stopPlayback();
            try { store.deleteRecording(id); } catch (Exception e) { store.error("녹음을 지우지 못했어요."); }
        })));
        card.addView(ui.actions(actions.toArray(new Button[0])));
        return card;
    }
    private void summarizeRecording(String id) {
        if (!hasKey()) return;
        send(new Intent(this, AssistantService.class).setAction(AssistantService.SUMMARIZE).putExtra("recording", id));
        store.status("녹음 요약을 맡겼어요 · 끝나면 알려 드릴게요");
    }
    private void play(String id) {
        stopPlayback();
        try {
            android.media.MediaPlayer next = new android.media.MediaPlayer();
            next.setDataSource(store.recordingFile(id).getPath()); next.prepare();
            next.setOnCompletionListener(done -> stopPlayback());
            next.start(); player = next; playingId = id; refreshRecords(false);
        } catch (Exception e) { store.error("녹음을 재생하지 못했어요."); }
    }
    private void stopPlayback() {
        android.media.MediaPlayer current = player; player = null;
        if (current != null) { try { current.stop(); } catch (IllegalStateException ignored) {} current.release(); }
        if (!playingId.isEmpty()) { playingId = ""; refreshRecords(false); }
    }
    private void newNote() {
        Ui.Sheet sheet = ui.selectionSheet("새 메모", "기억해 둘 내용을 적어 주세요");
        EditText title = ui.input("제목", 1, 160), text = ui.input("내용", 5, 80000);
        ui.add(sheet.body, title, 10); sheet.body.addView(text);
        sheet.primary.setText("저장");
        sheet.primary.setOnClickListener(v -> {
            try { store.saveNote(title.getText().toString(), text.getText().toString()); store.status("메모를 저장했어요"); sheet.dismiss(); }
            catch (Exception e) { store.error(e.getMessage() == null ? "메모를 저장하지 못했어요." : e.getMessage()); }
        });
        sheet.show();
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        title.requestFocus();
    }
    private void saveAsNote(String title, String body) {
        try { store.saveNote(title.length() > 160 ? title.substring(0, 157) + "…" : title, body); store.status("작업 결과를 메모로 저장했어요"); }
        catch (Exception e) { store.error(e.getMessage() == null ? "메모를 저장하지 못했어요." : e.getMessage()); }
    }
    private void clearFinished() {
        confirm("완료·실패·취소된 작업 기록을 모두 지울까요? 진행 중인 작업은 남아요.", "모두 지우기", () -> {
            try { store.status("작업 기록 " + store.clearFinishedTasks() + "개를 지웠어요"); } catch (Exception e) { store.error(e.getMessage()); }
        });
    }
    private void confirm(String message, String action, Runnable run) { ui.confirm(message, action, run).show(); }

    // ── 설정 ─────────────────────────────────────────────────────────────────

    private void settings() {
        // A page id this build does not know (a stale saved page, an old link) opens the list, not some other page.
        if (!SETTINGS_PAGES.contains(settingsPage)) settingsPage = "";
        if (settingsPage.isEmpty()) { settingsList(); return; }
        // Back goes one level up: a sub-page to its page (차에서 호출어 → 바로 부르기, GitHub → 커넥터), a page to the list.
        String parent = settingsParent(settingsPage);
        navRow.addView(ui.backButton(parent.isEmpty() ? "설정" : pageTitle(parent), () -> { settingsPage = parent; showTab(2); })); navRow.setVisibility(View.VISIBLE);
        titleView.setText(pageTitle(settingsPage));
        switch (settingsPage) {
            case "key" -> pageKey();
            case "chatgpt" -> pageChatgpt();
            case "grok" -> pageGrok();
            case "nim" -> pageNim();
            case "claude" -> pageClaude();
            case "ai" -> pageAi();
            case "connectors" -> pageConnectors();
            case "voice" -> pageVoice();
            case "quick" -> pageQuick();
            case "phone" -> pagePhone();
            case "look" -> pageLook();
            case "persona" -> pagePersona();
            case "routines" -> add(new RoutinesUi(this, ui, store, () -> showTab(2)).page(), 0);
            case "memory" -> pageMemory();
            case "background" -> pageBackground();
            case "update" -> pageUpdate();
            case "backup" -> pageBackup();
            case "usage" -> pageUsage();
            case "calendar" -> add(calendar().page(), 16);
            case "wake" -> add(wakeWord().page(), 16);
            case "github" -> pageGithub();
            case "drive" -> add(drive().page(), 16);
            case "memories" -> pageMemories();
            default -> pageAbout();
        }
    }
    /** Every settings page id; anything else opens the list. */
    private static final java.util.Set<String> SETTINGS_PAGES = java.util.Set.of("", "key", "chatgpt", "grok", "nim", "claude", "ai", "connectors", "voice",
        "quick", "phone", "look", "persona", "routines", "memory", "background", "update", "usage", "calendar", "wake", "about", "github", "drive", "memories", "backup");
    /** The page a sub-page belongs to, "" for the pages listed on the settings home. */
    static String settingsParent(String page) {
        return switch (page) {
            case "wake" -> "quick";
            case "github", "drive" -> "connectors";
            case "memories" -> "memory";
            default -> "";
        };
    }
    private static String pageTitle(String page) {
        return switch (page) {
            case "grok" -> "Grok";
            case "github" -> "GitHub"; case "drive" -> "Google Drive"; case "memories" -> "기억한 것";
            case "nim" -> "NVIDIA NIM";
            case "key" -> "Gemini"; case "chatgpt" -> "ChatGPT"; case "claude" -> "Claude"; case "ai" -> "AI 역할"; case "connectors" -> "커넥터"; case "voice" -> "목소리와 대화";
            case "quick" -> "바로 부르기"; case "phone" -> "폰 조작"; case "look" -> "화면 보기"; case "persona" -> "나에 대해"; case "routines" -> "한마디 루틴"; case "memory" -> "기억";
            case "background" -> "다른 앱 쓰는 동안"; case "backup" -> "백업 · 복원"; case "update" -> "업데이트"; case "usage" -> "사용량 · 남은 한도"; case "calendar" -> "캘린더"; case "wake" -> "차에서 호출어"; default -> "정보";
        };
    }
    /** Opens settings at one page (or the list for ""). The list opens whole: a search left there would hide the group a caller points at. */
    private void openSettings(String page) { settingsPage = page; if (page.isEmpty()) settingsQuery = ""; showTab(2); }

    // ── 작업창 ──────────────────────────────────────────────────────────────

    /**
     * 작업창 as a chat, like a ChatGPT or Codex conversation: the messages run down the page and the composer stays docked
     * at the bottom (send, or stop while a turn runs). With no thread open it shows the conversations, sorted by themselves,
     * or the gallery of drawings; sending from the list starts a new thread and opens it.
     */
    private void works() {
        workDrawn = store.worksVersion; workInterruptDrawn = store.workInterruptVersion; workGalleryDrawn = store.galleryVersion;
        if (!workId.isEmpty() && WorkBook.find(store.works(), workId) == null) workId = "";
        if (!workId.isEmpty()) { workThread(workId); return; }
        titleView.setText("작업");
        // One slim row over the list: the 대화 / 기록 switch, then 예약 and 에이전트 팀 small at its end.
        LinearLayout tools = ui.row(); tools.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tools.addView(ui.switcher(new String[]{"대화", "음성·메모 기록"}, 0, index -> { if (index == 1) showTab(1); }));
        tools.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        tools.addView(ui.smallChip(schedules().chipLabel(), () -> schedules().show()));
        android.widget.ImageView team = ui.quietIcon(R.drawable.ic_team, "에이전트 팀", () -> teamUi().show()); team.setImageTintList(android.content.res.ColorStateList.valueOf(TEXT));
        tools.addView(team, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        add(tools, 8); sessions();
    }
    /**
     * The conversations, sorted by themselves: by category (the AI that answered files each one after its first answer), by
     * date, or only the pinned ones, with a search over titles, tags and messages. A long press renames, refiles, pins or
     * deletes.
     */
    private void sessions() {
        boolean ready = !workModels().readyEngine().isEmpty() || store.grok.connected();
        if (!ready) { TextView need = ui.banner("사용할 AI를 연결해 주세요 · 눌러서 연결", WARNING); need.setOnClickListener(v -> openSettings("")); add(need, 12); }
        if (store.works().length() == 0) { workHero(ready); return; }
        add(searchBox("대화 검색 · 제목, 내용, 태그", workQuery, value -> { workQuery = value; fillSessions(); }), 8);
        add(ui.smallPills(Sessions.MODE_NAMES, sessionMode, target -> {
            if (sessionMode == target) return;
            sessionMode = target; store.settings.edit().putInt("sessionMode", target).apply(); showTab(3);
        }), 2);
        sessionsBox = ui.column(); add(sessionsBox, 0);
        fillSessions();
        add(ui.footer((store.autoSort() ? "대화는 첫 답이 오면 그 대화를 맡은 AI가 읽고 분류·제목·태그를 붙여요(설정 → AI 역할에서 끌 수 있어요). " : "")
            + "대화를 길게 누르면 이름·분류를 바꾸거나 맨 위에 고정해요. 만든 파일은 다운로드 → " + Brand.NAME + ", 그림은 갤러리와 사진 → " + Brand.NAME + " 폴더에 있어요."), 16);
    }
    /** 작업창 before the first conversation: what it does and a few things to try. */
    private void workHero(boolean ready) {
        LinearLayout hero = ui.card(); hero.setPadding(ui.dp(20), ui.dp(22), ui.dp(20), ui.dp(20));
        hero.addView(ui.badge("함께 일하는 AI", ACCENT), new LinearLayout.LayoutParams(-2, -2));
        hero.addView(ui.title("무엇을 맡길까요?", 22));
        hero.addView(ui.label("조사부터 문서, 이미지, GitHub 작업까지.\n대화하며 맡기고, 여러 AI와 함께 진행해 보세요.", 15, MUTED));
        if (ready) {
            String[] ideas = {"이번 달 지출 내역을 정리해서 엑셀 파일과 그래프로 만들어 줘", "요즘 전기차 보조금 바뀐 점을 찾아서 출처와 함께 정리해 줘", "회의 메모를 보고서 형식 워드 문서로 만들어 줘", "봄 느낌 카페 포스터를 그려 줘"};
            Button[] chips = new Button[ideas.length];
            for (int i = 0; i < ideas.length; i++) { String idea = ideas[i]; chips[i] = ui.chip(idea.length() > 22 ? idea.substring(0, 22) + "…" : idea, () -> typeInComposer(idea)); }
            hero.addView(ui.actions(chips));
        }
        add(hero, 12);
        add(ui.footer("코드는 각 AI의 격리된 공간에서 돌고 휴대폰에서는 실행되지 않아요. 첨부한 사진·PDF는 고른 AI에게, 영상·음성은 Gemini에게 보내요. 맡은 AI의 사용량을 써요."), 16);
    }
    /** Puts text in the composer (null keeps what is typed) and the cursor at the end, with the keyboard up. */
    private void typeInComposer(String text) {
        if (composerInput == null) return;
        if (text != null) composerInput.setText(text);
        composerInput.setSelection(composerInput.length()); composerInput.requestFocus();
        android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) composerInput.post(() -> keyboard.showSoftInput(composerInput, 0));
    }
    /** Rebuilds the sections under the search box (the box keeps its focus while typing). */
    private void fillSessions() {
        if (sessionsBox == null) return;
        sessionsBox.removeAllViews();
        JSONArray threads = store.works(); long now = System.currentTimeMillis(); String mode = Sessions.MODES[sessionMode];
        boolean searching = !workQuery.isBlank();
        List<String> waiting = Sessions.unsorted(threads, now);
        if (!waiting.isEmpty() && !searching && !workModels().readyEngine().isEmpty()) {
            TextView sort = ui.banner("✨ AI가 아직 분류하지 않은 대화 " + waiting.size() + "개 · 눌러서 주제별로 정리", ACCENT);
            sort.setOnClickListener(v -> {
                String[] ids = waiting.subList(0, Math.min(30, waiting.size())).toArray(new String[0]);
                confirm("대화 " + ids.length + "개를 AI가 읽고 주제별로 분류하고, 제목과 태그를 붙여요. 대화마다 가벼운 모델에 한 번씩 물어봐서 사용량이 조금 들어요. 직접 지은 이름과 분류는 그대로 둬요.", "정리하기", () -> sortSessions(ids));
            });
            Ui.pressable(sort, true);
            ui.add(sessionsBox, sort, 0);
        }
        View everywhere = SearchActivity.link(this, ui, workQuery); if (everywhere != null) ui.add(sessionsBox, everywhere, 12);
        JSONArray sections = Sessions.sections(threads, mode, workQuery, now, ZoneId.systemDefault());
        if (sections.length() == 0) {
            TextView none = ui.text(searching ? "‘" + workQuery.strip() + "’에 맞는 대화가 없어요." : "고정한 대화가 없어요. 대화를 길게 눌러 ‘맨 위에 고정’을 골라 보세요.", 14, 450, MUTED);
            none.setGravity(android.view.Gravity.CENTER); none.setPadding(ui.dp(16), ui.dp(28), ui.dp(16), 0);
            ui.add(sessionsBox, none, 0); return;
        }
        // By category the busiest groups stay short until opened; by date and in a search everything shows.
        boolean folding = Sessions.BY_CATEGORY.equals(mode) && !searching;
        for (int s = 0; s < sections.length(); s++) {
            JSONObject section = sections.optJSONObject(s); String title = section.optString("title"); JSONArray items = section.optJSONArray("items");
            boolean foldable = folding && !"고정".equals(title) && items.length() > SECTION_ROWS + 1, open = !foldable || openSections.contains(title);
            ui.add(sessionsBox, sectionHead(section.optString("emoji"), title, items.length(), foldable ? (open ? "접기" : "모두 보기") : null,
                () -> { if (!openSections.remove(title)) openSections.add(title); fillSessions(); }), 0);
            LinearLayout group = ui.group();
            int shown = open ? items.length() : SECTION_ROWS;
            for (int i = 0; i < shown; i++) ui.addRow(group, sessionRow(items.optJSONObject(i), now, !Sessions.BY_CATEGORY.equals(mode) || "고정".equals(title)), true);
            ui.add(sessionsBox, group, 0);
        }
    }
    /** A section's caption over its group: emoji and name, how many, and an optional link on the right. */
    private View sectionHead(String emoji, String title, int count, String link, Runnable action) {
        LinearLayout row = ui.row(); row.setPadding(ui.dp(14), ui.dp(14), ui.dp(8), ui.dp(6));
        row.addView(ui.text((emoji.isEmpty() ? "" : emoji + "  ") + title, 13.5f, 700, TEXT));
        TextView number = ui.text(String.valueOf(count), 12.5f, 600, MUTED); number.setPadding(ui.dp(6), 0, 0, 0);
        row.addView(number, new LinearLayout.LayoutParams(0, -2, 1));
        if (link != null) {
            TextView more = ui.text(link, 13, 600, ACCENT); more.setPadding(ui.dp(8), ui.dp(4), ui.dp(4), ui.dp(4));
            more.setOnClickListener(v -> action.run()); Ui.pressable(more, true); row.addView(more);
        }
        return row;
    }
    /** One conversation in the list: its category's emoji on a tinted tile, the title, then when, who, how long and its tags. */
    private View sessionRow(JSONObject thread, long now, boolean showCategory) {
        String id = thread.optString("id"), category = Sessions.category(thread), title = thread.optString("title");
        boolean running = WorkBook.RUNNING.equals(thread.optString("state"));
        // Compact, so a screen holds many: a small emoji tile, one line of title and one quiet line of details.
        LinearLayout row = ui.row(); row.setMinimumHeight(ui.dp(52)); row.setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7));
        TextView tile = ui.text(Sessions.emoji(category), 15, 400, TEXT); tile.setGravity(android.view.Gravity.CENTER); tile.setBackground(ui.shape(categoryTint(category), 10));
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)); tileParams.rightMargin = ui.dp(12); row.addView(tile, tileParams);
        LinearLayout words = ui.column();
        TextView name = ui.text((thread.optBoolean("pinned") ? "📌 " : "") + title, 15, 560, TEXT); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); words.addView(name);
        JSONArray messages = thread.optJSONArray("messages");
        String engine = thread.has("engine") ? WorkBook.author(WorkBook.engine(thread.optString("engine"))) : "";
        StringBuilder detail = new StringBuilder(Gallery.ago(now, thread.optLong("updatedAt"), ZoneId.systemDefault()));
        if (showCategory) detail.append(" · ").append(category);
        if (!engine.isEmpty()) detail.append(" · ").append(engine);
        detail.append(" · 메시지 ").append(messages == null ? 0 : messages.length());
        JSONArray tags = thread.optJSONArray("tags");
        for (int i = 0; tags != null && i < tags.length(); i++) detail.append(i == 0 ? "   #" : " #").append(tags.optString(i));
        TextView sub = ui.text(detail.toString(), 12.5f, 400, MUTED); sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END); sub.setPadding(0, ui.dp(2), 0, 0); words.addView(sub);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (running) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.leftMargin = ui.dp(8); row.addView(ui.badge("진행 중", ACCENT), p); }
        row.setOnClickListener(v -> { workId = id; showTab(3); });
        row.setOnLongClickListener(v -> { sessionMenu(thread); return true; });
        Ui.pressable(row, true);
        row.setContentDescription(title + ", " + category + ", " + detail + ". 길게 누르면 메뉴");
        return SwipeDelete.wrap(ui, row, "이 대화", () -> removeSession(thread));
    }
    /** A soft tint per category for the emoji tiles. */
    private static int categoryTint(String category) {
        int[] tints = {BLUE, INDIGO, TEAL, PINK, PURPLE, GREEN, ORANGE, MINT, RED, GREEN, GRAY};
        int tint = GRAY;
        for (int i = 0; i < Sessions.CATEGORIES.length; i++) if (Sessions.CATEGORIES[i][0].equals(category)) tint = tints[Math.min(i, tints.length - 1)];
        return (tint & 0x00ffffff) | 0x30000000;
    }
    /** A conversation's menu: rename, refile, pin, ask the AI to file it again, schedule, 기억 사용 on or off, or delete. */
    private void sessionMenu(JSONObject thread) {
        String id = thread.optString("id"), category = Sessions.category(thread); boolean pinned = thread.optBoolean("pinned");
        JSONObject stored = WorkBook.find(store.works(), id); boolean remembers = WorkBook.remembers(stored == null ? thread : stored);
        String[] items = {"이름 바꾸기", "분류 바꾸기 (지금 " + Sessions.emoji(category) + " " + category + ")", pinned ? "고정 풀기" : "맨 위에 고정", "AI로 다시 분류", "이 요청 예약하기",
            remembers ? "기억 사용 끄기 (지금 켜짐)" : "기억 사용 켜기 (지금 꺼짐)", "지우기"};
        new AlertDialog.Builder(this).setTitle(thread.optString("title")).setItems(items, (d, which) -> {
            switch (which) {
                case 0 -> renameSession(thread);
                case 1 -> fileSession(thread);
                case 2 -> changeThread(id, target -> Sessions.pin(target, !pinned));
                case 3 -> { if (changeThread(id, Sessions::unfile)) sortSessions(new String[]{id}); }
                case 4 -> scheduleRequest(thread);
                // What the app keeps about the user goes with this room's requests; from the next one.
                case 5 -> { if (changeThread(id, target -> WorkBook.remember(target, !remembers))) store.status(remembers ? "이 대화에서는 기억을 쓰지 않아요 · 다음 지시부터" : "이 대화에서 기억을 써요 · 다음 지시부터"); }
                default -> deleteSession(thread);
            }
        }).show();
    }
    /** One change to a stored conversation; the list and the open thread redraw from the store. */
    private boolean changeThread(String id, java.util.function.Consumer<JSONObject> change) {
        try { store.changeWorks(list -> { JSONObject target = WorkBook.find(list, id); if (target != null) change.accept(target); return null; }); return true; }
        catch (Exception e) { store.error(e.getMessage() == null ? "바꾸지 못했어요." : e.getMessage()); return false; }
    }
    private void renameSession(JSONObject thread) {
        String id = thread.optString("id");
        LinearLayout form = ui.column(); form.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0);
        EditText name = ui.input("대화 이름", 1, 40); name.setText(thread.optString("title")); name.setSelectAllOnFocus(true); form.addView(name);
        TextView note = ui.label("직접 지은 이름은 AI가 바꾸지 않아요.", 12, MUTED); note.setPadding(0, ui.dp(8), 0, 0); form.addView(note);
        new AlertDialog.Builder(this).setTitle("이름 바꾸기").setView(form).setNegativeButton("취소", null)
            .setPositiveButton("저장", (d, w) -> {
                String title = name.getText().toString();
                if (changeThread(id, target -> Sessions.rename(target, title))) { JSONObject renamed = WorkBook.find(store.works(), id); if (renamed != null) store.retitlePictures(id, renamed.optString("title")); }
            }).show();
    }
    /** Files the conversation by hand, or hands it back to the AI. */
    private void fileSession(JSONObject thread) {
        String id = thread.optString("id"), current = Sessions.category(thread);
        String[] labels = new String[Sessions.CATEGORIES.length]; int chosen = -1;
        for (int i = 0; i < labels.length; i++) { labels[i] = Sessions.CATEGORIES[i][1] + "  " + Sessions.CATEGORIES[i][0]; if (Sessions.CATEGORIES[i][0].equals(current)) chosen = i; }
        new AlertDialog.Builder(this).setTitle("분류 바꾸기")
            .setSingleChoiceItems(labels, chosen, (d, which) -> { changeThread(id, target -> Sessions.file(target, Sessions.CATEGORIES[which][0])); d.dismiss(); })
            .setNeutralButton("AI에게 맡기기", (d, w) -> { if (changeThread(id, Sessions::unfile)) sortSessions(new String[]{id}); })
            .setNegativeButton("취소", null).show();
    }
    /** Asks the AI to file these conversations now: a light model, once each, behind any running turn. */
    private void sortSessions(String[] ids) {
        if (ids.length == 0) return;
        if (workModels().readyEngine().isEmpty()) { store.error("분류하려면 ChatGPT, Claude, Gemini, Grok, NVIDIA NIM 중 하나를 연결해 주세요."); openSettings(""); return; }
        send(new Intent(this, AssistantService.class).setAction(AssistantService.WORK_SORT).putExtra("threads", ids));
        store.status(ids.length == 1 ? "AI가 대화를 읽고 분류하고 있어요" : "AI가 대화 " + ids.length + "개를 읽고 분류하고 있어요");
    }
    /** 예약 작업 (WorkSchedulesUi): the list and editor sheets; a run's room opens here. */
    private WorkSchedulesUi schedules() { return new WorkSchedulesUi(this, ui, store, room -> { workId = room; showTab(3); }); }
    /** A new schedule from this room's first request in the user's own words (for a voice-started room, what the app heard,
     * never the voice model's work_start text), on the AI and model this room uses and with its 기억 사용. */
    private void scheduleRequest(JSONObject thread) {
        String request = WorkBook.firstRequest(thread);
        JSONObject schedule = thread.optJSONObject("schedule");
        if (schedule != null && dev.jun.gyeot.core.WorkSchedules.find(store.schedules(), schedule.optString("id")) != null) { schedules().show(); return; }
        JSONObject stored = WorkBook.find(store.works(), thread.optString("id"));
        schedules().edit(null, request, new ScopedModels(store, thread.optString("id")).snapshot(), WorkBook.remembers(stored == null ? thread : stored));
    }
    private void deleteSession(JSONObject thread) {
        String id = thread.optString("id");
        confirm("‘" + thread.optString("title") + "’ 대화를 지울까요? 받은 파일은 다운로드 폴더에, 그림은 갤러리에 그대로 남아요.", "지우기", () -> { if (removeSession(thread)) showTab(3); });
    }
    /** Deletes the conversation now (the list's X, or 지우기 once asked): a running one is stopped first. */
    private boolean removeSession(JSONObject thread) {
        String id = thread.optString("id");
        JSONObject current = WorkBook.find(store.works(), id);
        JSONObject gone = current == null ? thread : current;
        if (WorkBook.RUNNING.equals(gone.optString("state"))) send(AssistantService.WORK_STOP, "thread", id);
        try {
            store.changeWorks(list -> WorkBook.remove(list, id));
            // Pictures that are in the gallery keep their copies; the gallery shows them.
            for (String fileId : WorkBook.attachmentIds(gone)) store.releaseAttachment(fileId);
            if (id.equals(workId)) workId = "";
            return true;
        } catch (Exception e) { store.error(e.getMessage()); return false; }
    }
    /** A rounded search field with a magnifier and a clear button; onChange gets the text as it is typed. Keeps its focus across redraws. */
    private LinearLayout searchBox(String hint, String value, java.util.function.Consumer<String> onChange) {
        LinearLayout box = ui.row(); box.setBackground(ui.shape(FILL, 14)); box.setPadding(ui.dp(12), 0, ui.dp(2), 0);
        android.widget.ImageView glass = new android.widget.ImageView(this); glass.setImageResource(R.drawable.ic_search); glass.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED));
        box.addView(glass, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        EditText search = ui.input(hint, 1, 200); search.setText(value); search.setBackground(null); search.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); search.setTextSize(15);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH); search.setTag("search:" + hint);
        box.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = ui.text("✕", 15, 500, MUTED); clear.setGravity(android.view.Gravity.CENTER); clear.setMinimumWidth(ui.dp(44)); clear.setMinimumHeight(ui.dp(40)); clear.setContentDescription("검색어 지우기");
        clear.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE); clear.setOnClickListener(v -> search.setText(""));
        box.addView(clear);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { clear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE); onChange.accept(s.toString()); }
        });
        if (("search:" + hint).equals(searchFocus)) search.post(() -> { search.requestFocus(); search.setSelection(search.length()); });
        return box;
    }

    // ── 갤러리 ───────────────────────────────────────────────────────────────

    /**
     * 갤러리: every picture ChatGPT drew, in a grid by date, kind, conversation or album, or only the starred ones, with a
     * search over what was asked for. A picture opens full screen; a long press stars it, files it or edits it further.
     */
    private void gallery() {
        galleryDrawn = store.galleryVersion;
        titleView.setText("갤러리");
        if (store.gallery().length() == 0 && store.videos().length() == 0) {
            LinearLayout empty = ui.card(); empty.setPadding(ui.dp(20), ui.dp(22), ui.dp(20), ui.dp(20));
            empty.addView(ui.feature(R.drawable.ic_gallery, "장면을 모으는 공간", "그림과 영상, 프롬프트까지", Ui.ORANGE, () -> askDrawing(null)));
            empty.addView(ui.title("첫 장면을 만들어 보세요", 21));
            empty.addView(ui.label("작업창에서 ChatGPT에게 “봄 느낌 카페 포스터를 그려 줘”처럼 부탁하면, 그린 그림이 여기에 날짜·종류·대화별로 정리돼요. 사진을 첨부해 “배경을 바다로 바꿔 줘”라고 해도 돼요.", 14, MUTED));
            empty.addView(ui.button("이미지 만들기", true, () -> askDrawing(null)));
            empty.addView(ui.button("Grok 동영상 만들기", false, () -> { showTab(3); workVideoOptions(); }));
            add(empty, 12); return;
        }
        TextView count = ui.text("사진 " + store.gallery().length() + " · 동영상 " + store.videos().length(), 13, 450, MUTED); add(count, 14);
        add(ui.segmented(new String[]{"전체", "사진", "동영상"}, galleryMedia, chosen -> { galleryMedia = chosen; galleryShown = GALLERY_PAGE; showTab(4); }), 12);
        add(searchBox("사진·동영상 프롬프트 검색", galleryQuery, value -> { galleryQuery = value; galleryShown = GALLERY_PAGE; fillGallery(); }), 10);
        if (galleryMedia != 2) add(ui.pills(Gallery.VIEW_NAMES, galleryView, target -> {
            if (galleryView == target) return;
            galleryView = target; galleryShown = GALLERY_PAGE; store.settings.edit().putInt("galleryView", target).apply(); showTab(4);
        }), 2);
        galleryBox = ui.column(); add(galleryBox, 0);
        fillGallery();
        add(ui.footer("원본은 사진 → " + Brand.NAME + " 폴더에 있어요. 그림을 누르면 크게 보고, 길게 누르면 즐겨찾기·앨범·이어서 고치기를 바로 할 수 있어요."), 16);
    }
    /** A new conversation with ChatGPT, ready for a drawing request. */
    private void askDrawing(String text) {
        if (!store.openai.connected()) { store.error("그림은 ChatGPT가 그려요. ChatGPT를 먼저 연결해 주세요."); openSettings("chatgpt"); return; }
        if (!canDraw()) { store.error(drawingUnavailable()); openSettings("chatgpt"); return; }
        if (!store.workImages()) store.error("설정 → AI 역할에서 ‘작업창에서 그림 그리기’를 켜야 그려요.");
        workId = "";
        if (!WorkBook.CHATGPT.equals(workModels().engine())) chooseEngine(WorkBook.CHATGPT);
        showTab(3);
        typeInComposer(text);
    }
    /** Responsive collection: photo squares with prompt captions, grouped and paged without hiding the viewing order. */
    private void fillGallery() {
        if (galleryBox == null) return;
        galleryBox.removeAllViews();
        if (galleryMedia != 1) fillVideoGallery(galleryBox);
        if (galleryMedia == 2) return;
        String view = Gallery.VIEWS[galleryView];
        JSONArray sections = Gallery.sections(store.gallery(), view, galleryQuery, System.currentTimeMillis(), ZoneId.systemDefault());
        if (sections.length() == 0) {
            TextView none = ui.text(!galleryQuery.isBlank() ? "‘" + galleryQuery.strip() + "’에 맞는 그림이 없어요."
                : Gallery.FAVORITE.equals(view) ? "즐겨찾기한 그림이 없어요. 그림을 열어 ☆를 눌러 보세요." : "그림이 없어요.", 14, 450, MUTED);
            none.setGravity(android.view.Gravity.CENTER); none.setPadding(ui.dp(16), ui.dp(28), ui.dp(16), 0);
            ui.add(galleryBox, none, 0); return;
        }
        // The viewer swipes through everything this view and search show, in order.
        List<JSONObject> order = new ArrayList<>();
        for (int s = 0; s < sections.length(); s++) { JSONArray items = sections.optJSONObject(s).optJSONArray("items"); for (int i = 0; i < items.length(); i++) order.add(items.optJSONObject(i)); }
        int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 3;
        int gap = ui.dp(8), cell = (getResources().getDisplayMetrics().widthPixels - insetLeft - insetRight - ui.dp(32) - gap * (columns - 1)) / columns;
        int position = 0, budget = galleryShown;
        for (int s = 0; s < sections.length() && budget > 0; s++) {
            JSONObject section = sections.optJSONObject(s); JSONArray items = section.optJSONArray("items");
            ui.add(galleryBox, sectionHead("", section.optString("title"), items.length(), null, null), 0);
            LinearLayout grid = ui.column(), line = null;
            int shown = Math.min(items.length(), budget);
            for (int i = 0; i < shown; i++) {
                if (i % columns == 0) { line = ui.row(); line.setGravity(android.view.Gravity.TOP); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); if (i > 0) lp.topMargin = ui.dp(14); grid.addView(line, lp); }
                int index = position++;
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); if (i % columns > 0) cp.leftMargin = gap;
                line.addView(galleryCell(items.optJSONObject(i), cell, () -> showPicture(order, index)), cp);
            }
            // Empty cells retain the grid rhythm, including on an unfolded phone.
            for (int k = shown % columns; k > 0 && k < columns; k++) { LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); cp.leftMargin = gap; line.addView(new View(this), cp); }
            ui.add(galleryBox, grid, 0);
            budget -= shown;
        }
        if (galleryShown < order.size()) {
            Button more = ui.chip("더 보기 · " + (order.size() - galleryShown) + "개 더 있어요", () -> { galleryShown += GALLERY_PAGE; fillGallery(); });
            LinearLayout row = ui.row(); row.setGravity(android.view.Gravity.CENTER); row.setPadding(0, ui.dp(14), 0, 0); row.addView(more, new LinearLayout.LayoutParams(-2, ui.dp(36)));
            ui.add(galleryBox, row, 0);
        }
    }
    private View galleryCell(JSONObject entry, int size, Runnable open) {
        LinearLayout tile = ui.column();
        FrameLayout photo = new FrameLayout(this); photo.setBackground(ui.shape(FILL, 12)); photo.setClipToOutline(true);
        android.widget.ImageView image = new android.widget.ImageView(this); image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        photo.addView(image, new FrameLayout.LayoutParams(-1, -1)); loadThumb(image, entry.optString("id"), size);
        if (entry.optBoolean("favorite")) {
            TextView star = ui.text("♥", 15, 650, 0xffffffff); star.setShadowLayer(ui.dp(3), 0, 0, 0xaa000000);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.END); p.bottomMargin = ui.dp(6); p.rightMargin = ui.dp(7); photo.addView(star, p);
        }
        tile.addView(photo, new LinearLayout.LayoutParams(-1, size));
        String prompt = Gallery.prompt(entry);
        TextView caption = ui.text(prompt.isBlank() ? "프롬프트 기록 없음" : prompt.replaceAll("\\s+", " "), 12, 450, TEXT);
        caption.setMaxLines(2); caption.setMinLines(2); caption.setEllipsize(TextUtils.TruncateAt.END); caption.setPadding(ui.dp(1), ui.dp(6), ui.dp(1), 0); tile.addView(caption);
        tile.setContentDescription("이미지: " + (prompt.length() > 100 ? prompt.substring(0, 100) : prompt) + (entry.optBoolean("favorite") ? ", 즐겨찾기" : ""));
        tile.setFocusable(true); tile.setOnClickListener(v -> open.run()); tile.setOnLongClickListener(v -> { pictureMenu(entry, null); return true; }); Ui.pressable(tile, false); return tile;
    }
    /** Fills the view with a square thumbnail, from the cache or decoded off the main thread (the view may be gone by then). */
    private void loadThumb(android.widget.ImageView view, String id, int size) {
        String key = id + "#" + size; view.setTag(key);
        android.graphics.Bitmap cached = previews.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        java.io.File file = store.attachmentFile(id);
        try {
            thumbLoader.execute(() -> {
                android.graphics.Bitmap bitmap = WorkFiles.thumb(file, size);
                main.post(() -> {
                    if (bitmap != null) previews.put(key, bitmap);
                    if (!key.equals(view.getTag())) return;
                    if (bitmap == null) { view.setScaleType(android.widget.ImageView.ScaleType.CENTER); view.setImageResource(R.drawable.ic_sparkle); view.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED)); return; }
                    view.setImageBitmap(bitmap); view.setAlpha(0f); view.animate().alpha(1f).setDuration(140).start();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }
    /** A picture's quick actions from a long press (then done runs, e.g. to redraw the viewer). */
    private void pictureMenu(JSONObject entry, Runnable done) {
        String id = entry.optString("id"); JSONObject current = Gallery.find(store.gallery(), id);
        if (current == null) return;
        boolean favorite = current.optBoolean("favorite");
        String[] items = {favorite ? "☆ 즐겨찾기 빼기" : "★ 즐겨찾기", "앨범에 넣기", "이어서 고치기 (ChatGPT)", "공유", "지우기"};
        new AlertDialog.Builder(this).setItems(items, (d, which) -> {
            switch (which) {
                case 0 -> { changePicture(list -> Gallery.favorite(list, id, !favorite)); if (done != null) done.run(); }
                case 1 -> pickAlbum(current, done);
                case 2 -> editPicture(current, null);
                case 3 -> sharePicture(current);
                default -> deletePicture(current, done);
            }
        }).show();
    }
    private boolean changePicture(java.util.function.Consumer<JSONArray> change) {
        try { store.changeGallery(list -> { change.accept(list); return null; }); return true; }
        catch (Exception e) { store.error(e.getMessage() == null ? "갤러리를 바꾸지 못했어요." : e.getMessage()); return false; }
    }
    /** Puts the picture in an album: one in use, a new one, or none. */
    private void pickAlbum(JSONObject entry, Runnable done) {
        String id = entry.optString("id"), now = entry.optString("album");
        List<String> albums = Gallery.albums(store.gallery());
        List<String> labels = new ArrayList<>();
        for (String album : albums) labels.add((album.equals(now) ? "✓ " : "") + album);
        labels.add("＋ 새 앨범"); if (!now.isBlank()) labels.add("앨범에서 빼기");
        new AlertDialog.Builder(this).setTitle("앨범에 넣기").setItems(labels.toArray(new String[0]), (d, which) -> {
            if (which < albums.size()) { changePicture(list -> Gallery.album(list, id, albums.get(which))); if (done != null) done.run(); return; }
            if (which > albums.size()) { changePicture(list -> Gallery.album(list, id, "")); if (done != null) done.run(); return; }
            LinearLayout form = ui.column(); form.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0);
            EditText name = ui.input("예: 카페 포스터", 1, Gallery.ALBUM_NAME); form.addView(name);
            new AlertDialog.Builder(this).setTitle("새 앨범").setView(form).setNegativeButton("취소", null).setPositiveButton("만들기", (d2, w) -> {
                String album = Gallery.albumName(name.getText().toString());
                if (album.isEmpty()) { store.error("앨범 이름을 적어 주세요."); return; }
                changePicture(list -> Gallery.album(list, id, album)); if (done != null) done.run();
            }).show();
        }).show();
    }
    /** Attaches a copy of the picture to the next message for ChatGPT, in the conversation it came from when that is still there. */
    private void editPicture(JSONObject entry, Runnable done) {
        if (!store.openai.connected()) { store.error("그림은 ChatGPT가 고쳐요. ChatGPT를 먼저 연결해 주세요."); openSettings("chatgpt"); return; }
        if (!canDraw()) { store.error(drawingUnavailable()); openSettings("chatgpt"); return; }
        if (store.workFiles.length() >= WorkBook.ATTACHMENTS) { store.error("한 번에 " + WorkBook.ATTACHMENTS + "개까지 첨부할 수 있어요."); return; }
        String id = entry.optString("id"), name = entry.optString("name", "그림.png"), thread = entry.optString("thread");
        try {
            thumbLoader.execute(() -> {
                try {
                    JSONObject copy = WorkFiles.attachCopy(store, id, name);
                    main.post(() -> {
                        if (store.workFiles.length() >= WorkBook.ATTACHMENTS) { store.attachmentFile(copy.optString("id")).delete(); return; }
                        store.workFiles.put(copy); if (!composerImageAspect.isEmpty()) composerImageEdit = true; refreshFiles();
                        workId = WorkBook.find(store.works(), thread) != null ? thread : "";
                        if (!WorkBook.CHATGPT.equals(workModels().engine())) chooseEngine(WorkBook.CHATGPT);
                        if (done != null) done.run();
                        showTab(3); typeInComposer(null);
                        store.status("그림을 첨부했어요 · 어떻게 고칠지 적어 주세요");
                    });
                } catch (Exception e) { String reason = e.getMessage() == null ? "그림을 첨부하지 못했어요." : e.getMessage(); main.post(() -> store.error(reason)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }
    private void sharePicture(JSONObject entry) {
        String uri = entry.optString("uri");
        if (uri.isBlank()) { store.error("원본 파일이 없어요. 사진 → " + Brand.NAME + " 폴더를 확인해 주세요."); return; }
        shareFile(Uri.parse(uri), entry.optString("name", "그림"));
    }
    /** Takes the picture out of the gallery, and the photo file too when asked. */
    /** Takes the picture out of the gallery, and its original file in the 들퍼레 folder too when asked. */
    private void deletePicture(JSONObject entry, Runnable done) {
        String id = entry.optString("id"), uri = entry.optString("uri");
        Uri original = uri.isBlank() ? null : Uri.parse(uri);
        boolean removable = original != null && (ManagedSharedStorage.AUTHORITY.equals(original.getAuthority()) ? ManagedSharedStorage.removable(original) : SharedOutputFolder.canAccess(this, original));
        String[] choices = original == null ? new String[]{"갤러리에서 지우기"} : removable ? new String[]{"갤러리에서만 지우기 (원본 파일은 보존해요)", "원본 파일까지 지우기 (들퍼레 폴더에서도 삭제)"} : new String[]{"갤러리에서만 지우기 (원본 파일은 보존해요)"};
        new AlertDialog.Builder(this).setTitle("이 그림을 지울까요?").setItems(choices, (d, which) -> {
            if (!changePicture(list -> Gallery.remove(list, id))) return;
            store.releaseAttachment(id);
            if (which == 1) {
                try {
                    if (!ManagedSharedStorage.AUTHORITY.equals(original.getAuthority())) SharedOutputFolder.requireContentAccess(this, original);
                    if (!android.provider.DocumentsContract.deleteDocument(getContentResolver(), original)) throw new java.io.IOException("삭제되지 않았어요.");
                    // The media index drops a file it can no longer find, so other apps' pickers stop listing it.
                    MediaIndex.register(this, original, dev.jun.gyeot.core.MediaPaths.mime(entry.optString("name")));
                    store.status("갤러리와 들퍼레 폴더에서 지웠어요.");
                }
                catch (Exception e) { store.error("갤러리에서는 지웠지만 원본 파일은 지우지 못했어요. 파일 앱의 " + Brand.NAME + " 폴더에서 지워 주세요."); }
            }
            if (done != null) done.run();
        }).setNegativeButton("취소", null).show();
    }
    /**
     * A picture full screen, swiped left and right through the list it was opened from: what was asked for, the image model's
     * own description, when and with which model, its line of edits, and the actions (edit further, share, album, star,
     * open the original, delete).
     */
    private android.app.Dialog galleryDialog;
    /** One gallery picture full screen, opened from elsewhere (a picture drawn in the main chat). */
    void openGalleryPicture(String id) {
        JSONObject entry = Gallery.find(store.gallery(), id);
        if (entry == null) { store.error("갤러리에서 지운 그림이에요. 대화의 미리보기만 남아 있어요."); return; }
        showPicture(new ArrayList<>(List.of(entry)), 0);
    }
    private void showPicture(List<JSONObject> list, int start) {
        showPicture(list, start, null);
    }
    @SuppressWarnings("unchecked")
    private void showPicture(List<JSONObject> list, int start, Intent navigationIntent) {
        if (list.isEmpty()) return;
        if (galleryDialog != null) galleryDialog.dismiss();
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        galleryDialog = dialog;
        // A warm darkroom: near-black paper, warm surfaces, and coral (about 7:1 on the surface) for links.
        int canvas = 0xff0c0b0a, surface = 0xff24221f, secondary = 0xffa8a399, white = 0xfff5f2ea, coral = 0xffeb9b7d;
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(canvas);
        LinearLayout page = ui.column(); root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        List<JSONObject>[] pictures = new List[]{new ArrayList<>(list)}; int[] index = {Math.max(0, Math.min(start, list.size() - 1))};
        Runnable[] render = new Runnable[1]; java.util.function.IntConsumer[] move = new java.util.function.IntConsumer[1];
        LinearLayout bar = ui.row(); bar.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
        bar.addView(ui.iconButton(R.drawable.ic_close, surface, white, "갤러리로 돌아가기", dialog::dismiss), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        LinearLayout titles = ui.column(); titles.setGravity(android.view.Gravity.CENTER);
        TextView counter = ui.text("", 14, 650, white), date = ui.text("", 11.5f, 400, secondary); titles.addView(counter); date.setPadding(0, ui.dp(4), 0, 0); titles.addView(date);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.ImageView star = ui.iconButton(R.drawable.ic_heart, canvas, white, "즐겨찾기", () -> {}); bar.addView(star, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        TextView menu = ui.text("⋯", 26, 650, white); menu.setGravity(android.view.Gravity.CENTER); menu.setContentDescription("이미지 메뉴"); bar.addView(menu, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        page.addView(bar);
        LinearLayout details = ui.column();
        GalleryPhotoView image = new GalleryPhotoView(this, step -> move[0].accept(step), () -> details.setVisibility(details.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE), dialog::dismiss);
        String[] decodedImage = {""};
        Runnable verifyImage = () -> {
            if (dialog.isShowing() && decodedImage[0].equals(image.getTag())) store.visibleGalleryImage = decodedImage[0];
            if (navigationIntent != null && dialog.isShowing() && root.hasWindowFocus() && decodedImage[0].equals(image.getTag()))
                AppNavigator.imageShown(navigationIntent, decodedImage[0]);
        };
        root.getViewTreeObserver().addOnWindowFocusChangeListener(focused -> { if (focused) verifyImage.run(); });
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1, 0, 1); imageParams.leftMargin = imageParams.rightMargin = ui.dp(8); page.addView(image, imageParams);
        LinearLayout navigation = ui.row(); navigation.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        android.widget.ImageView previous = ui.iconButton(R.drawable.ic_back, surface, white, "이전 이미지", () -> move[0].accept(-1));
        android.widget.ImageView next = ui.iconButton(R.drawable.ic_back, surface, white, "다음 이미지", () -> move[0].accept(1)); next.setRotation(180);
        navigation.addView(previous, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        android.widget.HorizontalScrollView film = new android.widget.HorizontalScrollView(this); film.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = ui.row(); strip.setGravity(android.view.Gravity.CENTER); film.addView(strip); LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, ui.dp(52), 1); fp.leftMargin = fp.rightMargin = ui.dp(10); navigation.addView(film, fp);
        navigation.addView(next, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))); details.addView(navigation);
        ScrollView infoScroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                int cap = Math.round(getResources().getDisplayMetrics().heightPixels * 0.26f), available = View.MeasureSpec.getSize(h);
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(available > 0 ? Math.min(cap, available) : cap, View.MeasureSpec.AT_MOST));
            }
        };
        infoScroll.setVerticalScrollBarEnabled(false); infoScroll.setBackground(ui.shape(surface, 20)); infoScroll.setClipToOutline(true);
        LinearLayout info = ui.column(); info.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(14)); infoScroll.addView(info);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2); ip.leftMargin = ip.rightMargin = ui.dp(12); details.addView(infoScroll, ip);
        LinearLayout actions = ui.row(); actions.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); details.addView(actions);
        Button animate = ui.button("Grok으로 영상화", true, () -> {});
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, ui.dp(48)); vp.leftMargin = vp.rightMargin = ui.dp(12); vp.bottomMargin = ui.dp(8); details.addView(animate, vp); page.addView(details);
        move[0] = step -> {
            int target = index[0] + step; if (target < 0 || target >= pictures[0].size()) return;
            index[0] = target; image.reset(); render[0].run();
        };
        render[0] = () -> {
            JSONObject shown = pictures[0].get(index[0]), entry = Gallery.find(store.gallery(), shown.optString("id"));
            if (entry == null) {
                pictures[0].remove(index[0]); if (pictures[0].isEmpty()) { dialog.dismiss(); return; }
                index[0] = Math.min(index[0], pictures[0].size() - 1); render[0].run(); return;
            }
            String id = entry.optString("id");
            animate.setOnClickListener(v -> videoOptions(entry, false));
            counter.setText((index[0] + 1) + " / " + pictures[0].size()); date.setText(entry.optLong("at") > 0 ? WHEN.format(Instant.ofEpochMilli(entry.optLong("at")).atZone(ZoneId.systemDefault())) : "");
            boolean favorite = entry.optBoolean("favorite"); star.setImageResource(favorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            star.setImageTintList(android.content.res.ColorStateList.valueOf(favorite ? 0xffff647c : white)); star.setContentDescription(favorite ? "즐겨찾기 해제" : "즐겨찾기에 추가");
            star.setOnClickListener(v -> { changePicture(all -> Gallery.favorite(all, id, !favorite)); render[0].run(); });
            menu.setOnClickListener(v -> {
                new AlertDialog.Builder(this).setItems(new String[]{"앨범에 넣기", "편집 기록 보기", "원본 열기", "대화로 가기", "이미지 지우기"}, (d, which) -> {
                    if (which == 0) pickAlbum(entry, render[0]);
                    else if (which == 1) {
                        JSONArray versions = Gallery.versions(store.gallery(), id); if (versions.length() < 2) { store.status("아직 편집 기록이 없어요."); return; }
                        List<JSONObject> line = new ArrayList<>(); int current = 0;
                        for (int i = 0; i < versions.length(); i++) { JSONObject item = versions.optJSONObject(i); if (id.equals(item.optString("id"))) current = i; line.add(item); }
                        pictures[0] = line; index[0] = current; image.reset(); render[0].run();
                    } else if (which == 2 && !entry.optString("uri").isBlank()) openFile(Uri.parse(entry.optString("uri")), entry.optString("name"));
                    else if (which == 3) { dialog.dismiss(); workId = WorkBook.find(store.works(), entry.optString("thread")) != null ? entry.optString("thread") : ""; showTab(3); }
                    else if (which == 4) deletePicture(entry, render[0]);
                }).show();
            }); Ui.pressable(menu, true);
            previous.setEnabled(index[0] > 0); previous.setAlpha(index[0] > 0 ? 1 : 0.25f);
            next.setEnabled(index[0] < pictures[0].size() - 1); next.setAlpha(index[0] < pictures[0].size() - 1 ? 1 : 0.25f);
            strip.removeAllViews();
            int first = Math.max(0, index[0] - 3), last = Math.min(pictures[0].size(), first + 7);
            for (int i = first; i < last; i++) {
                JSONObject neighbor = pictures[0].get(i); int at = i; boolean on = at == index[0];
                FrameLayout frame = new FrameLayout(this); frame.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
                android.graphics.drawable.GradientDrawable outline = ui.shape(canvas, 9); if (on) outline.setStroke(ui.dp(2), coral); frame.setBackground(outline);
                android.widget.ImageView thumb = new android.widget.ImageView(this); thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP); thumb.setBackground(ui.shape(surface, 6)); thumb.setClipToOutline(true);
                frame.setMinimumWidth(ui.dp(44)); frame.addView(thumb, new FrameLayout.LayoutParams(ui.dp(on ? 38 : 32), ui.dp(44), android.view.Gravity.CENTER)); loadThumb(thumb, neighbor.optString("id"), ui.dp(60));
                frame.setContentDescription((at + 1) + "번째 이미지" + (on ? ", 현재 이미지" : "")); frame.setOnClickListener(v -> { index[0] = at; image.reset(); render[0].run(); }); frame.setFocusable(true);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, ui.dp(50)); tp.rightMargin = ui.dp(3); strip.addView(frame, tp);
            }
            film.post(() -> film.smoothScrollTo(Math.max(0, ui.dp((index[0] - first) * 47) - film.getWidth() / 2 + ui.dp(22)), 0));
            // Retain the bitmap during favorite/menu changes. New pages clear it before the asynchronous original arrives.
            if (!id.equals(image.getTag())) {
                decodedImage[0] = ""; store.visibleGalleryImage = "";
                image.setImageBitmap(null); image.setTag(id); image.reset();
                android.graphics.Bitmap quick = null;
                for (java.util.Map.Entry<String, android.graphics.Bitmap> cached : previews.snapshot().entrySet()) if (cached.getKey().startsWith(id + "#")) quick = cached.getValue();
                image.setImageBitmap(quick);
                int edge = Math.min(2560, Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels));
                try { thumbLoader.execute(() -> {
                    android.graphics.Bitmap full = WorkFiles.galleryPreview(this, entry.optString("uri"), store.attachmentFile(id), edge);
                    main.post(() -> {
                        if (!id.equals(image.getTag())) return;
                        if (full != null) { image.setImageBitmap(full); decodedImage[0] = id; image.post(verifyImage); }
                        else if (navigationIntent != null) AppNavigator.imageFailed(navigationIntent, "이미지 파일을 읽지 못했어요. 삭제되었거나 접근 권한이 없을 수 있어요.");
                    });
                }); } catch (java.util.concurrent.RejectedExecutionException ignored) {}
            }
            info.removeAllViews(); LinearLayout promptHead = ui.row();
            TextView label = ui.text(Gallery.promptLabel(entry), 12, 600, secondary); promptHead.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            String prompt = Gallery.prompt(entry);
            android.widget.ImageView copy = ui.iconButton(R.drawable.ic_copy, surface, white, "복사: " + Gallery.promptLabel(entry), () -> copy(prompt));
            copy.setEnabled(!prompt.isBlank()); copy.setAlpha(prompt.isBlank() ? 0.4f : 1f);
            promptHead.addView(copy, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(40))); info.addView(promptHead);
            TextView words = ui.text(prompt.isBlank() ? "이 이미지에는 남아 있는 프롬프트가 없어요." : prompt, 14, 450, white);
            words.setMaxLines(3); words.setEllipsize(TextUtils.TruncateAt.END); words.setTextIsSelectable(true); info.addView(words);
            TextView expand = ui.text("전체 보기  ⌄", 12, 600, coral); expand.setPadding(0, ui.dp(8), 0, ui.dp(4)); expand.setMinHeight(ui.dp(32));
            expand.setOnClickListener(v -> { boolean open = words.getMaxLines() == 3; words.setMaxLines(open ? Integer.MAX_VALUE : 3); words.setEllipsize(open ? null : TextUtils.TruncateAt.END); expand.setText(open ? "접기  ⌃" : "전체 보기  ⌄"); }); Ui.pressable(expand, true); info.addView(expand);
            String meta = (entry.optString("model") + (entry.optInt("w") > 0 ? " · " + entry.optInt("w") + " × " + entry.optInt("h") : "") + (entry.optLong("size") > 0 ? " · " + size(entry.optLong("size")) : "")).strip();
            TextView metadata = ui.text(meta, 11, 400, secondary); metadata.setPadding(0, ui.dp(6), 0, 0); info.addView(metadata);
            JSONArray videos = store.videos();
            for (int v = videos.length() - 1; v >= 0; v--) {
                JSONObject video = videos.optJSONObject(v); if (video == null || !id.equals(video.optString("sourceId"))) continue;
                TextView link = ui.text("▶ " + dev.jun.gyeot.core.VideoJobs.label(video) + "  ›", 14, 600, coral);
                link.setMinHeight(ui.dp(44)); link.setGravity(android.view.Gravity.CENTER_VERTICAL); link.setOnClickListener(view -> openVideo(video)); info.addView(link);
                break;
            }
            if (!entry.optString("prompt").isBlank() && !entry.optString("prompt").equals(prompt)) {
                TextView original = ui.text("요청 원문 보기 ›", 12, 550, coral); original.setPadding(0, ui.dp(10), 0, ui.dp(6));
                original.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("요청 원문").setMessage(entry.optString("prompt")).setPositiveButton("닫기", null).setNeutralButton("복사", (d, w) -> copy(entry.optString("prompt"))).show()); info.addView(original);
            }
            actions.removeAllViews(); Button share = ui.button("공유하기", false, () -> sharePicture(entry)), edit = ui.button("이어서 편집", false, () -> editPicture(entry, dialog::dismiss));
            for (Button action : new Button[]{share, edit}) { action.setTextColor(white); action.setBackground(ui.shape(surface, 16)); action.setTextSize(14); }
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, ui.dp(48), 1); actions.addView(share, ap); LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, ui.dp(48), 1); ep.leftMargin = ui.dp(8); actions.addView(edit, ep);
            share.setEnabled(!entry.optString("uri").isBlank()); share.setAlpha(share.isEnabled() ? 1 : 0.4f); infoScroll.scrollTo(0, 0);
        };
        root.setOnApplyWindowInsetsListener((v, insets) -> { Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()); page.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets; });
        dialog.setContentView(root); render[0].run(); dialog.show();
        long[] videoDrawn = {store.galleryVersion};
        Runnable videoWatch = () -> { if (dialog.isShowing() && videoDrawn[0] != store.galleryVersion) { videoDrawn[0] = store.galleryVersion; render[0].run(); } };
        store.observe(videoWatch);
        android.view.Window window = dialog.getWindow(); if (window != null) { window.setLayout(-1, -1); window.setNavigationBarColor(canvas); window.setStatusBarColor(canvas); window.getDecorView().getWindowInsetsController().setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS); }
        dialog.setOnDismissListener(d -> {
            store.unobserve(videoWatch);
            if (navigationIntent != null) AppNavigator.imageFailed(navigationIntent, "이미지를 확인하기 전에 전체 화면을 닫았어요.");
            if (galleryDialog == dialog) { galleryDialog = null; store.visibleGalleryImage = ""; }
            image.setTag(null); image.setImageBitmap(null);
        });
    }
    private VideoPlayerDialog videoDialog;
    private void fillVideoGallery(LinearLayout parent) {
        JSONArray videos = store.videos(); List<JSONObject> found = new ArrayList<>();
        for (int i = videos.length() - 1; i >= 0; i--) {
            JSONObject video = videos.optJSONObject(i); if (video == null) continue;
            if (galleryQuery.isBlank() || (video.optString("sourcePrompt") + " " + video.optString("prompt")).toLowerCase(Locale.ROOT).contains(galleryQuery.strip().toLowerCase(Locale.ROOT))) found.add(video);
        }
        if (found.isEmpty()) {
            if (galleryMedia == 2) { TextView empty = ui.label(galleryQuery.isBlank() ? "작업창의 + → 동영상 생성으로 첫 영상을 만들어 보세요." : "검색한 내용의 영상이 없어요.", 14, MUTED); empty.setPadding(0, ui.dp(24), 0, ui.dp(24)); parent.addView(empty); parent.addView(ui.button("Grok으로 영상 만들기", true, () -> { showTab(3); workVideoOptions(); })); }
            return;
        }
        ui.add(parent, sectionHead("", "동영상", found.size(), null, null), 0);
        int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
        int count = Math.min(found.size(), galleryMedia == 2 ? galleryShown : 4);
        LinearLayout grid = ui.column(), row = null;
        for (int i = 0; i < count; i++) {
            if (i % columns == 0) { row = ui.row(); row.setGravity(android.view.Gravity.TOP); ui.add(grid, row, i == 0 ? 0 : 16); }
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1); if (i % columns != 0) cell.leftMargin = ui.dp(10);
            row.addView(videoTile(found.get(i)), cell);
        }
        for (int k = count % columns; k > 0 && k < columns; k++) { LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1); cell.leftMargin = ui.dp(10); row.addView(new View(this), cell); }
        ui.add(parent, grid, 12);
        if (count < found.size()) ui.add(parent, ui.chip(galleryMedia == 2 ? "동영상 더 보기" : "동영상 전체 보기  ›", () -> { if (galleryMedia == 2) { galleryShown += GALLERY_PAGE; fillGallery(); } else { galleryMedia = 2; showTab(4); } }), 16);
    }
    private View videoTile(JSONObject job) {
        LinearLayout tile = ui.column(); FrameLayout poster = new FrameLayout(this); poster.setBackground(ui.shape(FILL, 18)); poster.setClipToOutline(true);
        android.widget.ImageView image = new android.widget.ImageView(this); image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP); poster.addView(image, new FrameLayout.LayoutParams(-1, -1)); loadVideoThumb(image, job, ui.dp(320));
        View shade = new View(this); shade.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xb0000000})); poster.addView(shade, new FrameLayout.LayoutParams(-1, ui.dp(70), android.view.Gravity.BOTTOM));
        boolean done = "done".equals(job.optString("state"));
        android.widget.ImageView mark = ui.iconButton(done ? R.drawable.ic_play : R.drawable.ic_video, 0x99000000, 0xffffffff, done ? "영상 재생" : "생성 상태 확인", () -> openVideo(job));
        poster.addView(mark, new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), android.view.Gravity.CENTER));
        TextView badge = ui.text(VideoJobs.resolution(job) + "   ·   " + job.optInt("duration") + "초", 12, 600, 0xffffffff); badge.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(10));
        poster.addView(badge, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM));
        int height = Math.max(140, Math.min(240, (getResources().getConfiguration().screenWidthDp - 42) / (getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2)));
        tile.addView(poster, new LinearLayout.LayoutParams(-1, ui.dp(height)));
        TextView caption = ui.text(job.optString("prompt"), 13, 550, TEXT); caption.setMaxLines(2); caption.setMinLines(2); caption.setEllipsize(TextUtils.TruncateAt.END); caption.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), 0); tile.addView(caption);
        TextView status = ui.text(done ? WHEN.format(Instant.ofEpochMilli(job.optLong("createdAt")).atZone(ZoneId.systemDefault())) : VideoJobs.label(job), 11.5f, 450, done ? MUTED : ACCENT); status.setPadding(ui.dp(2), ui.dp(4), 0, 0); tile.addView(status);
        tile.setFocusable(true); tile.setContentDescription("Grok 영상, " + VideoJobs.label(job) + ", " + job.optString("prompt")); tile.setOnClickListener(v -> openVideo(job)); tile.setOnLongClickListener(v -> { videoDetails(job.optString("id")); return true; }); Ui.pressable(tile, false); return tile;
    }
    private void loadVideoThumb(android.widget.ImageView image, JSONObject job, int size) {
        if (!"done".equals(job.optString("state")) || job.optString("uri").isBlank()) {
            if (!job.optString("sourceId").isBlank()) loadThumb(image, job.optString("sourceId"), size);
            else { image.setScaleType(android.widget.ImageView.ScaleType.CENTER); image.setImageResource(R.drawable.ic_video); image.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED)); }
            return;
        }
        String key = job.optString("id") + "#video#" + size; image.setTag(key);
        android.graphics.Bitmap cached = previews.get(key); if (cached != null) { image.setImageBitmap(cached); return; }
        try { thumbLoader.execute(() -> {
            android.graphics.Bitmap frame = null;
            try (android.media.MediaMetadataRetriever reader = new android.media.MediaMetadataRetriever()) { SharedOutputFolder.requireContentAccess(this,Uri.parse(job.optString("uri")));reader.setDataSource(this, Uri.parse(job.optString("uri"))); frame = reader.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size);SharedOutputFolder.requireContentAccess(this,Uri.parse(job.optString("uri"))); } catch (Exception ignored) { if(frame!=null){frame.recycle();frame=null;} }
            if (frame == null && !job.optString("sourceId").isBlank()) frame = WorkFiles.preview(store.attachmentFile(job.optString("sourceId")), size);
            android.graphics.Bitmap bitmap = frame;
            runOnUiThread(() -> { if (isDestroyed()) { if (bitmap != null) bitmap.recycle(); return; } if (bitmap != null) previews.put(key, bitmap); if (!key.equals(image.getTag())) return;
                if (bitmap != null) { image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP); image.setImageTintList(null); image.setImageBitmap(bitmap); }
                else { image.setScaleType(android.widget.ImageView.ScaleType.CENTER); image.setImageResource(R.drawable.ic_video); image.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED)); }
            });
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private void openVideo(JSONObject job) { if ("done".equals(job.optString("state"))) playVideo(job); else videoDetails(job.optString("id")); }
    private void videoOptions(JSONObject source, boolean fresh) {
        if (!store.grok.connected()) { if (galleryDialog != null) galleryDialog.dismiss(); openSettings("grok"); store.status("설정에서 Grok 구독 계정을 한 번 연결해 주세요."); return; }
        if (!fresh) {
            JSONArray jobs = store.videos();
            for (int i = jobs.length() - 1; i >= 0; i--) {
                JSONObject job = jobs.optJSONObject(i);
                if (job != null && source.optString("id").equals(job.optString("sourceId")) && (dev.jun.gyeot.core.VideoJobs.active(job) || dev.jun.gyeot.core.VideoJobs.resumable(job))) { videoDetails(job.optString("id")); return; }
            }
        }
        Ui.Sheet sheet = ui.selectionSheet("Grok으로 영상화", "설정에 연결한 구독 계정으로 만들어요");
        android.widget.ImageView preview = new android.widget.ImageView(this); preview.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER); preview.setBackground(ui.shape(FILL, 16)); preview.setClipToOutline(true);
        sheet.body.addView(preview, new LinearLayout.LayoutParams(-1, ui.dp(180))); loadThumb(preview, source.optString("id"), ui.dp(360));
        TextView sourcePrompt = ui.label(Gallery.prompt(source), 12, MUTED); sourcePrompt.setMaxLines(2); sourcePrompt.setEllipsize(TextUtils.TruncateAt.END); sourcePrompt.setPadding(0, ui.dp(10), 0, ui.dp(12)); sheet.body.addView(sourcePrompt);
        EditText motion = ui.input("어떻게 움직일까요? 예: 카메라가 천천히 다가가고 나뭇잎이 흔들려", 3, 4000); sheet.body.addView(motion);
        sheet.body.addView(ui.label("비워 두면 원본 분위기를 살려 자연스럽게 움직여요.", 12, MUTED));
        int[] duration = {8}, choices = {5, 8, 10, 15};
        TextView length = ui.label("영상 길이", 14, TEXT); length.setPadding(0, ui.dp(16), 0, ui.dp(8)); sheet.body.addView(length);
        sheet.body.addView(ui.pills(new String[]{"5초", "8초", "10초", "15초"}, 1, chosen -> duration[0] = choices[chosen]));
        String[] quality = {store.settings.getString("videoResolution", "1080p")};
        TextView qualityLabel = ui.label("화질", 14, TEXT); qualityLabel.setPadding(0, ui.dp(16), 0, ui.dp(8)); sheet.body.addView(qualityLabel);
        sheet.body.addView(ui.segmented(new String[]{"720p · HD", "1080p · Full HD"}, "1080p".equals(quality[0]) ? 1 : 0, chosen -> quality[0] = chosen == 1 ? "1080p" : "720p"));
        TextView note = ui.label("1080p는 더 선명하고 생성 시간이 더 걸릴 수 있어요. 화면 비율은 원본 이미지를 따라가요.\n원본 이미지는 그대로 보관하고, 완성된 영상은 갤러리에 함께 저장해요.", 12, MUTED); note.setPadding(0, ui.dp(16), 0, 0); sheet.body.addView(note);
        sheet.primary.setText("영상 만들기");
        sheet.primary.setOnClickListener(v -> {
            sheet.primary.setEnabled(false); store.settings.edit().putString("videoResolution", quality[0]).apply();
            send(new Intent(this, AssistantService.class).setAction(AssistantService.VIDEO_START).putExtra("image", source.optString("id")).putExtra("prompt", motion.getText().toString()).putExtra("duration", duration[0]).putExtra("resolution", quality[0]));
            sheet.dismiss(); if (galleryDialog != null) galleryDialog.dismiss(); showTab(4);
        }); sheet.show();
    }
    private void videoDetails(String id) {
        Ui.Sheet sheet = ui.selectionSheet("Grok 영상", "프롬프트와 생성 기록"); String[] fingerprint = {""};
        Runnable render = () -> {
            JSONObject job = dev.jun.gyeot.core.VideoJobs.find(store.videos(), id); if (job == null) { sheet.dismiss(); return; }
            if (job.toString().equals(fingerprint[0])) return; fingerprint[0] = job.toString(); sheet.body.removeAllViews();
            sheet.body.addView(ui.title(dev.jun.gyeot.core.VideoJobs.label(job), 19));
            sheet.body.addView(ui.label(VideoJobs.resolution(job) + " · Grok Imagine · " + job.optInt("duration") + "초", 12, MUTED));
            TextView prompt = ui.label(job.optString("prompt"), 14, TEXT); prompt.setTextIsSelectable(true); prompt.setPadding(0, ui.dp(12), 0, ui.dp(12)); sheet.body.addView(prompt);
            TextView original = ui.label("원본 이미지 · " + job.optString("sourcePrompt"), 12, MUTED); original.setTextIsSelectable(true); if (!job.optString("sourceId").isBlank()) sheet.body.addView(original);
            if (!job.optString("error").isBlank()) sheet.body.addView(ui.label(job.optString("error"), 13, WARNING));
            String state = job.optString("state");
            sheet.primary.setEnabled(true);
            if ("done".equals(state)) {
                sheet.primary.setText("영상 재생"); sheet.primary.setOnClickListener(v -> { sheet.dismiss(); playVideo(job); });
                sheet.body.addView(ui.actions(ui.chip("영상 공유", () -> shareVideo(job)), ui.chip("프롬프트 복사", () -> copy(job.optString("prompt"))), ui.chip("다른 움직임으로 만들기", () -> {
                    JSONObject source = Gallery.find(store.gallery(), job.optString("sourceId"));
                    if (source == null) {
                        if (!job.optString("sourceId").isBlank()) { store.error("원본 이미지가 갤러리에 없어요."); return; }
                        sheet.dismiss(); if (videoDialog != null) videoDialog.dismiss(); showTab(3); composerVideoSource = ""; typeInComposer(job.optString("prompt")); workVideoOptions(); return;
                    }
                    sheet.dismiss(); if (videoDialog != null) videoDialog.dismiss(); videoOptions(source, true);
                })));
                sheet.body.addView(ui.label("새 영상은 들퍼레/영상에 저장해요. 과거 영상은 기존 주소를 유지하며, 지정 폴더 밖의 원본은 열지 않아요.", 12, MUTED));
            } else if (dev.jun.gyeot.core.VideoJobs.resumable(job)) {
                sheet.primary.setText("기존 요청 결과 확인"); sheet.primary.setOnClickListener(v -> {
                    if (!job.optString("accountId").equals(store.grok.accountId())) {
                        new AlertDialog.Builder(this).setTitle("Grok 연결이 바뀌었어요").setMessage("이 요청을 시작한 로그인 연결이 해제되거나 교체됐어요. 다른 계정으로 이전 요청을 이어 확인하지 않았어요.").setPositiveButton("확인", null).show(); return;
                    }
                    sheet.primary.setEnabled(false); send(AssistantService.VIDEO_RESUME, "video", id);
                });
                sheet.body.addView(ui.label("새로 생성하지 않고, 이미 접수된 영상의 결과를 확인해요.", 12, MUTED));
            } else {
                sheet.primary.setText(dev.jun.gyeot.core.VideoJobs.active(job) ? "갤러리에서 기다리기" : "닫기"); sheet.primary.setOnClickListener(v -> sheet.dismiss());
                if (dev.jun.gyeot.core.VideoJobs.active(job)) sheet.body.addView(ui.actions(ui.chip("결과 확인 중지", () -> send(AssistantService.VIDEO_PAUSE, "video", id))));
            }
            if (!dev.jun.gyeot.core.VideoJobs.active(job)) sheet.body.addView(ui.actions(ui.chip("이 영상 기록 지우기", () -> confirm("영상 생성 기록을 지울까요?", "지우기", () -> {
                try { store.changeVideos(all -> { for (int i = all.length() - 1; i >= 0; i--) if (id.equals(all.optJSONObject(i).optString("id"))) all.remove(i); return null; }); sheet.dismiss(); }
                catch (Exception e) { store.error("영상 기록을 지우지 못했어요."); }
            }))));
        };
        render.run(); store.observe(render); sheet.setOnDismissListener(d -> store.unobserve(render)); sheet.show();
    }
    private void shareVideo(JSONObject job) {
        try {
            Uri uri = Uri.parse(job.getString("uri")); SharedOutputFolder.requireContentAccess(this,uri);Intent intent = new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("Grok 영상", uri)); startActivity(Intent.createChooser(intent, "영상 공유"));
        } catch (Exception e) { store.error("지정 들퍼레 폴더 밖의 옛 영상은 공유하지 않아요. 새 위치의 검증된 파일인지 확인해 주세요."); }
    }
    private void playVideo(JSONObject job) { playVideo(job, null); }
    private void playVideo(JSONObject job, Bundle saved) { playVideo(job, saved, null); }
    private void playVideo(JSONObject job, Bundle saved, Intent navigation) {
        if (videoDialog != null) videoDialog.dismiss();
        List<JSONObject> playlist = new ArrayList<>(); int selected = -1; JSONArray videos = store.videos();
        for (int i = videos.length() - 1; i >= 0; i--) { JSONObject item = videos.optJSONObject(i); if (item == null || !"done".equals(item.optString("state")) || item.optString("uri").isBlank()) continue;
            boolean selectedItem = item.optString("id").equals(job.optString("id"));
            if (!selectedItem && tab == 4 && !galleryQuery.isBlank() && !(item.optString("prompt") + " " + item.optString("sourcePrompt")).toLowerCase(Locale.ROOT).contains(galleryQuery.strip().toLowerCase(Locale.ROOT))) continue;
            if (selectedItem) selected = playlist.size(); playlist.add(item); }
        if (playlist.isEmpty() || selected < 0) { store.error("선택한 영상 파일이 없어요."); if (navigation != null) AppNavigator.videoFailed(navigation, "선택한 영상 파일이 없어요."); return; }
        stopPlayback();
        VideoPlayerDialog dialog = new VideoPlayerDialog(this, ui, playlist, selected, this::loadVideoThumb, this::shareVideo, item -> videoDetails(item.optString("id")), saved);
        if (navigation != null) {
            String id = job.optString("id");
            dialog.awaitPlayback(id, () -> id.equals(AppNavigator.videoId(navigation)), error -> {
                if (error.isEmpty()) AppNavigator.videoPlaying(navigation, id); else AppNavigator.videoFailed(navigation, error);
            });
        }
        videoDialog = dialog; dialog.setOnDismissListener(d -> { if (videoDialog == dialog) videoDialog = null; }); dialog.show();
    }
    private void showWorkList() {
        workId = ""; store.workOpen = "";
        if (composerInput != null) {
            composerInput.clearFocus(); android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(composerInput.getWindowToken(), 0);
        }
        showTab(3);
    }
    private void workThread(String id) {
        JSONObject thread = WorkBook.find(store.works(), id);
        titleView.setText("작업창");
        LinearLayout toolbar = ui.conversationBar(thread.optString("title"), this::showWorkList, () -> sessionMenu(thread), () -> {
            workId = ""; showTab(3); typeInComposer(null);
        });
        toolbar.addView(ui.bareIcon(R.drawable.ic_browser, TEXT, "AI 브라우저 열기", this::openBrowser), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        navigation.bar(toolbar);
        // Where the conversation is filed and by whom; tapping it refiles.
        String category = Sessions.category(thread);
        StringBuilder filed = new StringBuilder(Sessions.emoji(category)).append(' ').append(category).append(" · ").append(Sessions.how(thread));
        JSONArray tags = thread.optJSONArray("tags");
        for (int i = 0; tags != null && i < tags.length(); i++) filed.append(i == 0 ? "   #" : " #").append(tags.optString(i));
        TextView chip = ui.text(filed + "  ▾", 12.5f, 600, MUTED); chip.setSingleLine(true); chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setMaxWidth(Math.round(readingWidth() * 0.9f));
        chip.setBackground(ui.shape(FILL, 99)); chip.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)); chip.setContentDescription("분류: " + filed + ", 눌러서 바꾸기");
        chip.setOnClickListener(v -> fileSession(thread)); Ui.pressable(chip, false);
        LinearLayout filedRow = ui.row(); filedRow.setGravity(android.view.Gravity.CENTER); filedRow.addView(chip, new LinearLayout.LayoutParams(-2, -2));
        add(filedRow, 16);
        JSONObject scheduled = thread.optJSONObject("schedule");
        if (scheduled != null) {
            TextView origin = ui.banner("⏰ 예약 작업 · " + scheduled.optString("label") + "에 저절로 시작한 대화예요 · 예약 보기 ›", MUTED);
            origin.setOnClickListener(v -> schedules().show()); Ui.pressable(origin, true); add(origin, 14);
        }
        JSONObject summary = thread.optJSONObject("summary");
        if (summary != null && !summary.optString("text").isBlank()) {
            TextView folded = ui.banner("앞 대화 " + summary.optInt("count") + "개는 요약해서 기억해요 · 요약 보기 ›", MUTED);
            folded.setOnClickListener(v -> showDocument("앞 대화 요약", summary.optString("text"), null)); add(folded, 14);
        }
        JSONArray messages = thread.optJSONArray("messages");
        String lastUser = "";
        if (messages != null) for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i); if (message == null) continue;
            String role = message.optString("role"), text = message.optString("text");
            if (WorkBook.USER.equals(role)) { lastUser = text; add(userBubble(message), 16); }
            else if (WorkBook.ERROR.equals(role)) {
                LinearLayout failed = ui.column();
                failed.addView(speaker(engineOf(thread, message)));
                TextView reason = ui.text(WorkBook.errorText(text), 14, 450, ERROR); reason.setTextIsSelectable(true); failed.addView(reason);
                if (message.has("agents") || message.has("switches")) failed.addView(teamUi().reports(message.optJSONArray("agents"), message.optJSONArray("switches")));
                // Its actions in one line of compact pills: send it again (the one it leads to), the unfinished answer.
                String retry = lastUser, unfinished = message.optString("interruptedDraft");
                TextView again = retry.isBlank() ? null : ui.pill("같은 지시 다시 보내기", R.drawable.ic_refresh, true, () -> { composerInput.setText(retry); composerInput.setSelection(retry.length()); composerInput.requestFocus(); });
                TextView draft = unfinished.isBlank() ? null : ui.pill("미완성 답변 보기", R.drawable.ic_file, false, () -> showDocument("중단 전 미완성 답변", unfinished, null));
                if (again != null || draft != null) failed.addView(ui.pills(again, draft));
                add(failed, 24);
            } else add(workAnswer(thread, message), text.isBlank() ? 24 : 10);
            if (message.optBoolean("recoveredReadOnly")) add(ui.text("중단 후 읽기 전용으로 복구한 답변 · 변경 작업과 대기 지시는 자동 반복하지 않았어요. 이전 변경이 성공했는지 확인되지 않았다면 새 지시로 확인을 요청해 주세요.", 12, 400, MUTED), 8);
            if (!WorkBook.ERROR.equals(role) && !message.optString("interruptedDraft").isBlank())
                add(ui.pills(ui.pill("미완성 답변 보기", R.drawable.ic_file, false, () -> showDocument("중단 전 미완성 답변", message.optString("interruptedDraft"), null))), 4);
        }
        JSONArray savedDrafts = thread.optJSONArray("savedDrafts");
        if (WorkBook.RUNNING.equals(thread.optString("state"))) {
            LinearLayout live = ui.column();
            dev.jun.gyeot.core.WorkRoomState.Snapshot roomLive = store.workLive.get(id);
            boolean mine = roomLive.present;
            String by = mine && !roomLive.author.isBlank() ? roomLive.author : thread.has("imageRequest") ? "이미지 생성" : workEngineName();
            String model = mine ? roomLive.model : "";
            workLeadView = ui.column(); workLeadView.addView(speaker(by + (model.isBlank() ? "" : " · " + modelLabel(engineNamed(by), model)))); live.addView(workLeadView);
            // One gray line says what the turn is doing; a tap unfolds its step log and the saved drafts. 멈춤 stays beside it,
            // so the turn can always be stopped from the same place, whatever the composer's button is showing.
            LinearLayout line = ui.row();
            // The line a soft light sweeps across while the turn works, as chat apps show a model thinking.
            ShimmerText stage = new ShimmerText(this, MUTED, TEXT); stage.setTextSize(13.5f); stage.setTypeface(Ui.weight(500)); stage.setIncludeFontPadding(false); stage.setLineSpacing(ui.dp(3), 1);
            stage.setText(stageLine(mine ? (roomLive.stage.isBlank() ? "생각하는 중" : roomLive.stage) : store.workStages.getOrDefault(id, "차례를 기다리는 중"))); workStageView = stage;
            workStageView.setMaxLines(2); workStageView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout fold = ui.row(); fold.setMinimumHeight(ui.dp(48)); fold.addView(workStageView, new LinearLayout.LayoutParams(-2, -2)); fold.setFocusable(true);
            line.addView(fold, new LinearLayout.LayoutParams(0, -2, 1));
            TextView stop = ui.ghost("멈춤", () -> send(AssistantService.WORK_STOP, "thread", id)); stop.setTextColor(TEXT); stop.setMinHeight(ui.dp(48)); stop.setMinimumHeight(ui.dp(48)); stop.setMinWidth(ui.dp(48));
            android.graphics.drawable.Drawable square = getDrawable(R.drawable.ic_stop).mutate(); square.setTint(TEXT); square.setBounds(0, 0, ui.dp(14), ui.dp(14));
            stop.setCompoundDrawablesRelative(square, null, null, null); stop.setCompoundDrawablePadding(ui.dp(4)); stop.setContentDescription(STOP_WORK); stop.setTooltipText(STOP_WORK); stop.setFocusable(true);
            line.addView(stop, new LinearLayout.LayoutParams(-2, -2));
            live.addView(line);
            workLog = ui.column(); workLog.setPadding(0, 0, 0, ui.dp(6));
            workTimelineView = ui.text("", 12.5f, 400, MUTED); workTimelineView.setLineSpacing(ui.dp(4), 1); workLog.addView(workTimelineView);
            savedDraftRows(workLog, savedDrafts);
            live.addView(workLog);
            fold.setOnClickListener(v -> { workLogOpen = id.equals(workLogOpen) ? "" : id; showWorkLog(fold); refreshWorkProgress(); }); Ui.pressable(fold, true);
            showWorkLog(fold);
            workTeamView = ui.column(); live.addView(workTeamView); refreshTeamProgress();
            // The answer being written, drawn as a finished one is (tables, code, visuals once their block is complete).
            live.addView(workAnswers().draft(id, mine ? roomLive.text : ""));
            add(live, 24);
        } else if (savedDrafts != null && savedDrafts.length() > 0) {
            // No turn is running: the saved drafts keep their own folded line, in the same quiet style.
            LinearLayout kept = ui.column(), rows = ui.column(); savedDraftRows(rows, savedDrafts);
            TextView head = ui.quietRow("저장된 중간 답변 " + savedDrafts.length() + "개", () -> {}); head.setMinHeight(ui.dp(48)); head.setMinimumHeight(ui.dp(48));
            Runnable show = () -> { boolean open = id.equals(workLogOpen); rows.setVisibility(open ? View.VISIBLE : View.GONE); ui.foldArrow(head, open); head.setContentDescription("저장된 중간 답변 " + savedDrafts.length() + "개, " + (open ? "접기" : "펼치기")); };
            head.setOnClickListener(v -> { workLogOpen = id.equals(workLogOpen) ? "" : id; show.run(); }); show.run();
            kept.addView(head, new LinearLayout.LayoutParams(-2, -2)); kept.addView(rows);
            add(kept, 8);
        }
        JSONObject checkpoint = dev.jun.gyeot.core.WorkCheckpoint.get(thread);
        if (checkpoint != null && (dev.jun.gyeot.core.WorkCheckpoint.PAUSED.equals(thread.optString("state")) || checkpoint.optBoolean("readOnly"))) {
            add(ui.banner("중단 기록 복구 · 읽기 전용으로 이어서 확인해요. 변경·코드·이미지·팀 작업은 자동 반복하지 않아요. 대기 지시는 보관해요.", MUTED), 12);
            String partial = checkpoint.optString("originalPartial", checkpoint.optString("partial"));
            boolean paused = dev.jun.gyeot.core.WorkCheckpoint.PAUSED.equals(thread.optString("state"));
            if (paused) add(ui.text("연결이 바뀌었거나 24시간·3회 복구 한도를 넘으면 자동으로 다시 보내지 않아요. 기록을 확인한 뒤 새 지시를 보내 주세요.", 12, 400, MUTED), 8);
            TextView seePartial = partial.isBlank() ? null : ui.pill("중단 전 답변 보기", R.drawable.ic_file, false, () -> showDocument("중단 전 미완성 답변", partial, null));
            TextView cancelRecovery = paused ? ui.pill("복구 취소", R.drawable.ic_close, false, () -> send(AssistantService.WORK_STOP, "thread", id)) : null;
            if (seePartial != null || cancelRecovery != null) add(ui.pills(seePartial, cancelRecovery), 4);
        }

        int pending = dev.jun.gyeot.core.WorkQueue.size(thread);
        if (pending > 0) {
            boolean liveDelivery = dev.jun.gyeot.core.WorkQueue.canSteer(thread, thread.optString("activeEngine"));
            dev.jun.gyeot.core.WorkInterrupt interrupt = store.workInterrupts.get(id);
            boolean immediate = liveDelivery && interrupt != null && interrupt.available();
            boolean interrupting = liveDelivery && interrupt != null && interrupt.requested();
            boolean recovering = checkpoint != null && checkpoint.optBoolean("readOnly");
            add(ui.text("추가 지시 " + pending + "개 · " + (recovering ? "복구 중 보관" : interrupting ? "지금 전달 중" : liveDelivery ? "현재 응답·도구가 끝나면 전달" : "다음 작업으로 실행 대기"), 13, 600, TEXT), 12);
            JSONArray queue = thread.optJSONArray("pendingInstructions");
            for (int q = 0; q < queue.length(); q++) {
                JSONObject item = queue.optJSONObject(q);
                String text = item.optString("text");
                add(ui.text((q + 1) + ". " + WorkBook.author(item.optString("engine")) + " · " + (text.isBlank() ? "첨부 지시" : text.substring(0, Math.min(160, text.length()))), 13, 400, MUTED), 6);
            }
            if (immediate) {
                String expectedRun = checkpoint == null ? "" : checkpoint.optString("run"), expectedTicket = interrupt.token();
                add(ui.pills(ui.pill("지금 전달", R.drawable.ic_send, true, () -> send(new Intent(this, AssistantService.class).setAction(AssistantService.WORK_STEER_NOW)
                    .putExtra("thread", id).putExtra("run", expectedRun).putExtra("ticket", expectedTicket)))), 4);
                add(ui.text("Claude의 현재 응답만 멈추고 추가 지시로 이어가요. 이미 끝난 도구 결과는 보존해요.", 12, 400, MUTED), 8);
            } else if (WorkBook.RUNNING.equals(thread.optString("state"))) {
                add(ui.text(recovering ? "읽기 전용 복구 중에는 추가 지시를 전달하거나 자동 실행하지 않아요." : interrupting ? "현재 응답을 멈추고 추가 지시를 반영하고 있어요." : liveDelivery
                    ? "현재 요청은 바로 끊지 않아요. 다른 AI, 검색·코드 실행을 포함한 Claude 요청, 실행 중인 도구·팀 작업은 응답이나 처리가 끝난 뒤 반영해요."
                    : "현재 실행과 모델·설정이 다르거나 음성·영상 첨부가 있어 다음 작업으로 보관해요.", 12, 400, MUTED), 8);
            }
            // 이어서 실행 (when nothing runs) and 모두 취소 side by side, the note on why it waits just above them.
            boolean idle = !WorkBook.RUNNING.equals(thread.optString("state"));
            if (idle) add(ui.text("중지·오류 뒤에는 저절로 다시 실행하지 않아요. 이어서 실행을 누르거나 새 지시를 보내면 대기 지시부터 순서대로 실행해요.", 12, 400, MUTED), 8);
            TextView runNext = idle ? ui.pill("이어서 실행", R.drawable.ic_play, true, () -> send(AssistantService.WORK_NEXT, "thread", id)) : null;
            if (runNext != null) runNext.setContentDescription("대기 지시 이어서 실행");
            TextView clearQueue = ui.pill("모두 취소", R.drawable.ic_close, false, () -> send(AssistantService.WORK_CLEAR, "thread", id)); clearQueue.setContentDescription("대기 지시 모두 취소");
            add(ui.pills(runNext, clearQueue), 4);
        }
        refreshWorkProgress();
        // Plain scrolling: fullScroll would move focus to the composer and raise the keyboard.
        scroll.post(() -> scroll.scrollTo(0, body.getHeight()));
    }
    /** What the progress line says before the run's own summary arrives ("생각하는 중…"). */
    private static String stageLine(String stage) { return stage + "…"; }
    static final String STOP_WORK = "현재 작업 중지 · 대기 지시는 보관";
    /** The progress line's fold: its arrow, and the step log and saved drafts under it while open. */
    private void showWorkLog(View fold) {
        boolean open = !workId.isEmpty() && workId.equals(workLogOpen);
        if (workLog != null) workLog.setVisibility(open ? View.VISIBLE : View.GONE);
        if (workStageView != null) ui.foldArrow(workStageView, open);
        // A screen reader reads the line itself (it changes as the turn goes) and hears what a tap does.
        fold.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, open ? "진행 기록 접기" : "진행 기록 펼치기"));
            }
        });
    }
    /** "저장된 중간 답변 1 보기 ›" for each partial answer a recovery kept, as quiet rows inside a fold. */
    private void savedDraftRows(LinearLayout into, JSONArray savedDrafts) {
        for (int d = 0; savedDrafts != null && d < savedDrafts.length(); d++) {
            JSONObject draft = savedDrafts.optJSONObject(d); if (draft == null) continue;
            into.addView(ui.quietRow("저장된 중간 답변 " + (d + 1) + " 보기 ›", () -> showDocument("저장된 중간 답변 · 완료 결과가 아니에요", draft.optString("text"), null)), new LinearLayout.LayoutParams(-2, -2));
        }
    }
    /** The width (px) the conversation's column has: the window less the rail, at most Ui.READING_WIDTH. */
    private int readingWidth() {
        int width = contentWidth();
        return Math.max(ui.dp(200), width - 2 * ui.gutter(width, ui.dp(20)));
    }
    /** The content's width (px): as measured, or before that the screen less the rail a wide window shows. */
    private int contentWidth() {
        if (contentWidth > 0) return contentWidth;
        boolean rail = MainNavigation.tablet(this) || getResources().getConfiguration().screenWidthDp >= 680;
        return getResources().getDisplayMetrics().widthPixels - (rail ? ui.dp(MainNavigation.RAIL_WIDTH_DP) : 0);
    }
    /** The user's message on the right: attached pictures as small previews, other files by name (with Gemini's notes on video and audio), then the text. */
    private View userBubble(JSONObject message) {
        String text = message.optString("text");
        LinearLayout column = ui.column(); column.setGravity(android.view.Gravity.END);
        if (message.optBoolean("steering")) column.addView(ui.text("작업 중 추가 지시", 11, 500, MUTED));
        if (WorkBook.SPOKEN.equals(message.optString("kind"))) { LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(-2, -2); right.gravity = android.view.Gravity.END; column.addView(ui.text("음성 대화", 11, 500, MUTED), right); }
        JSONArray attachments = message.optJSONArray("attachments");
        for (int a = 0; attachments != null && a < attachments.length(); a++) {
            JSONObject file = attachments.optJSONObject(a); if (file == null) continue;
            String kind = file.optString("kind"), name = file.optString("name"), notes = file.optString("text");
            View shown = WorkBook.IMAGE.equals(kind) ? picture(file.optString("id"), ui.dp(180), null, name) : null;
            if (shown == null) {
                boolean media = WorkBook.VIDEO.equals(kind) || WorkBook.AUDIO.equals(kind);
                String label = fileIcon(kind) + " " + name + (media && !notes.isBlank() ? " · Gemini가 " + (WorkBook.VIDEO.equals(kind) ? "본" : "들은") + " 내용 ›" : WorkBook.TEXT.equals(kind) ? " · 내용 보기 ›" : "");
                TextView chip = ui.text(label, 13, 560, !file.optString("error").isBlank() ? ERROR : TEXT);
                chip.setBackground(ui.shape(FILL, 14)); chip.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
                chip.setMaxWidth(Math.round(readingWidth() * 0.85f));
                if (!notes.isBlank()) { chip.setOnClickListener(v -> showDocument(name, media ? notes : "```\n" + notes + "\n```", null)); Ui.pressable(chip, true); }
                else if (!file.optString("error").isBlank()) chip.setText(label + "\n" + file.optString("error"));
                shown = chip;
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.gravity = android.view.Gravity.END; p.bottomMargin = ui.dp(6); column.addView(shown, p);
        }
        if (!text.isBlank()) {
            TextView bubble = ui.bubble(text); bubble.setTextIsSelectable(true);
            bubble.setMaxWidth(Math.round(readingWidth() * 0.85f));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.gravity = android.view.Gravity.END; column.addView(bubble, p);
        }
        return column;
    }
    /** The small name over an answer: who wrote it (and with which model). */
    private View speaker(String name) {
        LinearLayout row = ui.row(); row.setGravity(android.view.Gravity.CENTER_VERTICAL); row.setPadding(0, 0, 0, ui.dp(6));
        // Small and faint: the answer is what is read, its author only a caption.
        View dot = new View(this); dot.setBackground(ui.shape(name.startsWith("Claude") ? Ui.ORANGE : name.startsWith("Gemini") ? Ui.BLUE : Ui.MINT, 99)); dot.setAlpha(0.7f); row.addView(dot, new LinearLayout.LayoutParams(ui.dp(6), ui.dp(6)));
        TextView label = ui.text(name, Ui.CAPTION, 500, MUTED); label.setPadding(ui.dp(6), 0, 0, 0); row.addView(label);
        return row;
    }
    private static String engineOf(JSONObject thread, JSONObject message) {
        return WorkBook.author(WorkBook.engine(message.optString("by", thread.optString("engine", WorkBook.CHATGPT))));
    }
    /** The engine behind a name WorkBook.author gave. */
    private static String engineNamed(String name) { return "Claude".equals(name) ? WorkBook.CLAUDE : "Gemini".equals(name) ? WorkBook.GEMINI : "Grok".equals(name) ? WorkBook.GROK : "NVIDIA NIM".equals(name) ? WorkBook.NIM : WorkBook.CHATGPT; }
    /** One answer in the chat: who wrote it, the formatted text, notes, the files it made, cited pages, and a few actions. */
    private LinearLayout workAnswer(JSONObject thread, JSONObject message) {
        // A spoken conversation's answer (VoiceTargets.work): what the voice said, under its name; nothing to act on.
        if (WorkBook.SPOKEN.equals(message.optString("kind"))) {
            LinearLayout answer = ui.column(); answer.addView(speaker(message.optString("voice", "음성")));
            TextView said = ui.text(message.optString("text"), 15, 450, TEXT); said.setTextIsSelectable(true); answer.addView(said);
            return answer;
        }
        if ("video".equals(message.optString("kind"))) {
            LinearLayout answer = ui.column(); answer.addView(speaker("Grok · Imagine")); JSONObject job = VideoJobs.find(store.videos(), message.optString("videoId"));
            if (job == null) answer.addView(ui.label("영상 생성 기록이 지워졌어요.", 14, MUTED));
            else { answer.addView(videoTile(job)); answer.addView(ui.actions(ui.chip("영상 정보", () -> videoDetails(job.optString("id"))))); }
            return answer;
        }
        // text is the answer as it is copied, kept and shown as a document: without the requests it suggests (buttons, not words).
        String text = dev.jun.gyeot.core.RichBlocks.withoutSuggestions(message.optString("text")), by = engineOf(thread, message), model = message.optString("model");
        LinearLayout answer = ui.column();
        answer.addView(speaker(("browser_result".equals(message.optString("kind")) ? "브라우저 · 원본 파일" : by) + (model.isBlank() ? "" : " · " + modelLabel(engineNamed(by), model))));
        if (message.has("agents") || message.has("switches")) answer.addView(teamUi().reports(message.optJSONArray("agents"), message.optJSONArray("switches")));
        // What the AI noted between its tool calls ("저장소를 읽어 볼게요"), folded above the answer.
        String steps = message.optString("steps");
        if (!steps.isBlank()) answer.addView(stepsView(steps));
        if (!text.isBlank()) answer.addView(workAnswers().body(thread, message));
        if (!message.optString("note").isBlank()) { TextView note = ui.label(message.optString("note"), 12, MUTED); note.setPadding(0, ui.dp(8), 0, 0); answer.addView(note); }
        JSONArray files = message.optJSONArray("files");
        if (text.isBlank() && steps.isBlank() && message.optString("note").isBlank() && (files == null || files.length() == 0)) {
            answer.addView(ui.label("이전 요청에서 응답 내용이 오지 않았어요. 같은 요청을 다시 보내 주세요.", 14, ERROR));
        }
        if (files != null) for (int f = 0; f < files.length(); f++) {
            JSONObject file = files.optJSONObject(f); if (file == null) continue;
            String name = file.optString("name");
            LinearLayout attachment = ui.column(); attachment.setBackground(ui.shape(FILL, 14)); attachment.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(6));
            if (!file.optString("error").isBlank()) { attachment.addView(ui.text("📎 " + name, 14, 600, TEXT)); attachment.addView(ui.label(file.optString("error"), 12, ERROR)); }
            else {
                android.net.Uri uri = android.net.Uri.parse(file.optString("uri"));
                // A picture ChatGPT drew shows itself; tapping it opens the full one in the gallery.
                View drawn = WorkBook.safeId(file.optString("picture")) ? picture(file.optString("picture"), ui.dp(280), () -> openFile(uri, name), name) : null;
                if (drawn != null) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.bottomMargin = ui.dp(8); attachment.addView(drawn, p); }
                attachment.addView(ui.text((drawn != null ? "🖼 " : "📎 ") + name + " · " + size(file.optLong("size")), 14, 600, TEXT));
                attachment.addView(ui.actions(ui.chip("열기", () -> openFile(uri, name)), ui.chip("공유", () -> shareFile(uri, name))));
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = ui.dp(10); answer.addView(attachment, p);
        }
        // Google's search suggestions under an answer Gemini grounded with Google Search, as wide as it, as Google sent them.
        String suggestions = message.optString("suggestions");
        View shownSuggestions = suggestions.isBlank() || suggestions.length() > dev.jun.gyeot.core.MainSearch.SUGGESTIONS ? null : workSuggestion(thread, message, suggestions);
        if (shownSuggestions != null) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = ui.dp(10);
            answer.addView(shownSuggestions, p);
        }
        // A quiet row of icons under the answer, as chat apps have it: copy, the full view (with its sources), read aloud, and
        // the rest in ⋯ (what the turn took is there too, as 이 답의 정보).
        JSONArray sources = message.optJSONArray("sources");
        String title = thread.optString("title");
        if (!text.isBlank()) {
            LinearLayout tools = ui.row(); int size = ui.dp(48);
            tools.addView(ui.footIcon(R.drawable.ic_copy, "복사", () -> copy(text)), new LinearLayout.LayoutParams(size, size));
            tools.addView(ui.footIcon(R.drawable.ic_expand, sources != null && sources.length() > 0 ? "크게 보기 · 출처 " + sources.length() : "크게 보기", () -> showDocument(title, text, sources == null ? new JSONArray() : sources, suggestions)), new LinearLayout.LayoutParams(size, size));
            String readKey = "work:" + text.hashCode() + ":" + text.length(), clip = VoiceChat.clipKey("work", message.optLong("at"));
            // A spoken answer plays again in its own voice (VoiceClips); any other is read aloud.
            boolean voiced = VoiceClips.has(clip);
            tools.addView(ui.footIcon(voiced ? R.drawable.ic_play : R.drawable.ic_sound, voiced ? "다시 듣기 · 말한 목소리 그대로 · 다시 누르면 멈춰요" : "읽어 주기 · 다시 누르면 멈춰요",
                () -> { if (VoiceClips.has(clip)) VoiceClips.toggle(this, store, clip, text); else store.speech().toggle(readKey, text); }), new LinearLayout.LayoutParams(size, size));
            String info = answerInfo(message, by, model);
            tools.addView(ui.footIcon(R.drawable.ic_more, "이 답 더 보기: 메모로 저장, 페이지에 저장, 서재에 저장, 공유, 이 답의 정보", () -> answerMenu(title, text, lastRequest(thread, message), by, info)), new LinearLayout.LayoutParams(size, size));
            // The first glyph sits on the text's left edge; its target reaches past it.
            LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-2, -2); row.leftMargin = -ui.dp(14); row.topMargin = -ui.dp(2);
            answer.addView(tools, row);
        }
        return answer;
    }
    /** A grounded answer's suggestions as the last drawing made them, else new (SearchSuggestions.view); null when there is nothing to show. */
    private View workSuggestion(JSONObject thread, JSONObject message, String html) {
        String key = thread.optString("id") + ":" + message.optLong("at") + ":" + html.hashCode() + ":" + html.length() + ":" + Ui.DARK;
        View view = workSuggestions.get(key);
        if (view == null) { view = SearchSuggestions.view(this, ui, html, this::openSuggestion); if (view == null) return null; workSuggestions.put(key, view); }
        else if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
        drawnSuggestions.add(key);
        return view;
    }
    /** A tapped Google search suggestion: its results page in the app's browser, an http or https address only. */
    private void openSuggestion(String url) {
        try { BrowserActivity.open(this, url); } catch (IllegalArgumentException invalid) { store.error("이 주소는 열 수 없어요."); } catch (Exception unavailable) { store.error("앱 브라우저를 열지 못했어요."); }
    }
    /** 이 답의 정보: who answered with which model, when, and what the turn took ("첫 글자 15초 · 647k 토큰 · 캐시 77%"), a line each; only the known parts. */
    private String answerInfo(JSONObject message, String by, String model) {
        StringBuilder info = new StringBuilder(by + (model.isBlank() ? "" : " · " + modelLabel(engineNamed(by), model)));
        if (message.optLong("at") > 0) info.append('\n').append(WHEN.format(Instant.ofEpochMilli(message.optLong("at")).atZone(ZoneId.systemDefault())));
        String spent = dev.jun.gyeot.core.Usage.foot(message.optJSONObject("usage"));
        if (!spent.isBlank()) info.append('\n').append(spent);
        return info.toString();
    }
    /** What else an answer can do: keep it as a note, keep it in 마이아카이브 (when connected), share it, or show what it took (이 답의 정보). Reading aloud is an icon beside ⋯. */
    private void answerMenu(String title, String text, String prompt, String by, String info) {
        List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        labels.add("메모로 저장"); actions.add(() -> { try { store.saveNote(title, text); store.status("메모로 저장했어요"); } catch (Exception e) { store.error(e.getMessage()); } });
        // 페이지: the answer as a document in a category the user picks.
        labels.add("페이지에 저장"); actions.add(() -> PageSaveSheet.show(this, ui, PageSaveSheet.document(text), dev.jun.gyeot.core.PageBook.WORK, title, null));
        if (store.archiveConnected()) { labels.add("서재에 저장 (마이아카이브)"); actions.add(() -> saveToArchive(title, text, prompt, by)); }
        labels.add("공유"); actions.add(() -> share(title + "\n\n" + text));
        // The answer without Markdown's marks, in one block with a copy button: for pasting where Markdown is not drawn.
        labels.add("텍스트로 보기"); actions.add(() -> PlainTextSheet.answer(this, text));
        // The transcript read for the video the request named, to read by itself.
        if (YouTubeTranscriptUi.has(this, prompt)) { labels.add("유튜브 대본 보기"); actions.add(() -> YouTubeTranscriptUi.show(this, prompt)); }
        if (YouTubeTranscriptUi.hasPage(this, prompt)) { labels.add("가져온 글 보기"); actions.add(() -> YouTubeTranscriptUi.showPage(this, prompt)); }
        labels.add("이 답의 정보"); actions.add(() -> new AlertDialog.Builder(this).setTitle("이 답의 정보").setMessage(info).setPositiveButton("닫기", null).show());
        new AlertDialog.Builder(this).setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run()).show();
    }
    /** "작업 과정 3단계 ▾": the notes the AI wrote while it worked, one per line, shown when tapped. */
    private View stepsView(String steps) {
        String[] lines = steps.split("\n");
        LinearLayout box = ui.column(); box.setBackground(ui.shape(FILL, 14)); box.setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9));
        TextView head = ui.text("작업 과정 " + lines.length + "단계  ▾", 13, 600, MUTED);
        StringBuilder list = new StringBuilder();
        for (String line : lines) list.append(list.length() > 0 ? "\n" : "").append("·  ").append(line.strip());
        TextView body = ui.text(list.toString(), 13.5f, 400, MUTED); body.setLineSpacing(ui.dp(4), 1); body.setPadding(0, ui.dp(6), 0, ui.dp(2)); body.setTextIsSelectable(true);
        body.setVisibility(View.GONE);
        box.addView(head); box.addView(body);
        box.setContentDescription("작업 과정 " + lines.length + "단계, 눌러서 펼치기");
        box.setOnClickListener(v -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE); head.setText("작업 과정 " + lines.length + "단계  " + (open ? "▴" : "▾"));
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.bottomMargin = ui.dp(10);
        box.setLayoutParams(p);
        return box;
    }
    /** The user's message this answer replied to, for the archive's prompt field. */
    private static String lastRequest(JSONObject thread, JSONObject answer) {
        JSONArray messages = thread.optJSONArray("messages"); String request = "";
        if (messages != null) for (int i = 0; i < messages.length() && messages.opt(i) != answer; i++)
            if (WorkBook.USER.equals(messages.optJSONObject(i).optString("role"))) request = messages.optJSONObject(i).optString("text");
        return request;
    }
    /**
     * The composer docked at the bottom of 작업창, shaped like Claude's: one rounded field (+, the words, the mic and one round
     * button that sends, or stops the running turn while nothing is typed) with the files waiting to go over the words, and
     * under it one slim line of where and who: the repository and the model (its reasoning level switched with a tap). The
     * field grows with the lines typed.
     */
    private LinearLayout composer() {
        LinearLayout outer = ui.column();
        LinearLayout box = ui.column();
        android.graphics.drawable.GradientDrawable capsule = ui.glass(24, false);
        box.setBackground(capsule); ui.lift(box, 4); box.setPadding(ui.dp(2), 0, ui.dp(2), 0);
        composerEngines = ui.row();
        android.widget.HorizontalScrollView files = new android.widget.HorizontalScrollView(this); files.setHorizontalScrollBarEnabled(false);
        composerFiles = ui.row(); composerFiles.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), 0); files.addView(composerFiles);
        composerFilesRow = files; box.addView(files, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout bar = ui.row(); composerBar = bar; bar.setGravity(android.view.Gravity.BOTTOM);
        bar.addView(ui.bareIcon(R.drawable.ic_add, TEXT, "사진 첨부, 이미지·동영상 생성", this::composerOptions), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        composerInput = new EditText(this); composerInput.setBackground(null); composerInput.setTextColor(TEXT); composerInput.setHintTextColor(MUTED); composerInput.setTextSize(16);
        composerInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composerInput.setMaxLines(6); composerInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(WorkBook.TEXT_LIMIT)});
        composerInput.setPadding(ui.dp(2), ui.dp(12), ui.dp(6), ui.dp(12)); composerInput.setMinimumHeight(ui.dp(48)); composerInput.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        bar.addView(composerInput, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(ui.bareIcon(R.drawable.ic_mic, TEXT, "Gemini 음성 입력 · 초안에만 넣기", this::speakInComposer), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        composerSend = ui.sendButton("보내기", this::composerButton);
        composerSend.setOnLongClickListener(v -> { if (!composerOffersVoice() || workVoice == null) return false; workVoice.settings(""); return true; });
        composerInput.addTextChangedListener(new TextWatcher() {
            private boolean blank = true;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                boolean now = s.toString().isBlank(), flipped = now != blank; if (now && !blank) composerEmptiedAt = android.os.SystemClock.elapsedRealtime(); blank = now;
                // Off a run, the hint, button and chips change only when the field turns empty or filled; while a turn runs a typed
                // drawing request changes them too (composerCreatesMedia). Other keystrokes skip the room's lookups.
                if (flipped || workRunning()) updateComposer();
            }
        });
        bar.addView(composerSend, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        outer.addView(box, new LinearLayout.LayoutParams(-1, -2));
        // Under the field, small and quiet: the repository this 작업창 works in (while GitHub is connected, refreshRepo) and
        // the model that answers the next message.
        composerRepoRow = ui.row(); composerRepoRow.setGravity(android.view.Gravity.CENTER_VERTICAL); composerRepoRow.setPadding(ui.dp(4), ui.dp(2), 0, 0);
        composerRepo = ui.text("", 13, 500, TEXT); composerRepo.setSingleLine(true); composerRepo.setEllipsize(TextUtils.TruncateAt.END);
        composerRepo.setGravity(android.view.Gravity.CENTER_VERTICAL); composerRepo.setPadding(ui.dp(8), 0, ui.dp(8), 0); composerRepo.setMinHeight(ui.dp(36)); composerRepo.setMinWidth(ui.dp(36));
        composerRepo.setOnClickListener(v -> pickRepo()); Ui.pressable(composerRepo, true); lastRepo = ""; composerRepo.setVisibility(View.GONE);
        composerRepoRow.addView(composerRepo, new LinearLayout.LayoutParams(-2, ui.dp(36)));
        composerRepoRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        composerEngines.setPadding(ui.dp(4), 0, ui.dp(4), 0); composerEngines.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        composerRepoRow.addView(composerEngines, new LinearLayout.LayoutParams(-2, ui.dp(36)));
        outer.addView(composerRepoRow, new LinearLayout.LayoutParams(-1, -2));
        composerOneRow = false;
        outer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (b - t != ob - ot) outer.post(this::layoutDock); });
        refreshFiles();
        return outer;
    }
    /** A wide window only lets the repository and model names run longer; the field and the line under it stay as they are. */
    private void arrangeComposer(LinearLayout box, boolean wide) {
        if (wide == composerOneRow) return;
        composerOneRow = wide;
        lastEngines = ""; lastRepo = ""; if (composer != null) { refreshEngines(); refreshRepo(); }
    }
    private void speakInComposer() {
        if (tab != 3) return;
        if (store.voiceActive || store.recording || store.voiceChat != null) { store.error("음성 대화나 녹음을 종료한 뒤 음성을 입력해 주세요."); return; }
        if (!store.secrets.hasWorkKey()) { store.error("Gemini 음성 입력에 사용할 API 키가 없어요. 설정에서 Gemini를 연결해 주세요."); return; }
        store.stopReading(null);
        if (composerDictation == null) composerDictation = new ComposerDictation(this, composerInput,
            () -> "work:" + tab + ":" + workId, message -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show());
        composerDictation.begin(() -> new GeminiComposerSession(store.secrets.workKey()), "Gemini 3.5 Transcribe Live");
    }
    /** The engine row: a capsule each for ChatGPT, Claude and Gemini (the filled one answers the next message) and the model chip. */
    private void refreshEngines() {
        if (composerEngines == null) return;
        String engine = workModels().engine();
        String fingerprint = workModels().teamEnabled() + "|" + workModels().teamFallback() + "|" + workModels().teamAutomatic() + "|" + workModels().teamProfiles() + "|" + composerVideo + "|" + store.grok.connected() + "|" + engine + "\u0000" + store.openai.connected() + store.claude.connected() + store.gemini.connected() + store.nim.connected() + "\u0000" + (engine.isEmpty() ? "" : modelChip(engine));
        if (fingerprint.equals(lastEngines)) return;
        lastEngines = fingerprint; composerEngines.removeAllViews();
        if (engine.isEmpty() || !store.ready(engine)) composerEngines.addView(modelText(new String[]{"AI 연결·선택", ""}, "작업 AI 연결하고 고르기", this::pickWorkProvider));
        if (!engine.isEmpty() && store.ready(engine)) {
            ScopedModels selection = workModels();
            if (selection.teamEnabled() || selection.teamFallback()) {
                TextView team = ui.text(selection.teamEnabled() && selection.teamAutomatic() ? "자동 팀" : selection.teamEnabled() ? "팀 " + dev.jun.gyeot.core.TeamPlan.enabled(selection.teamProfiles()).size() : "한도 전환", 13, 600, TEXT);
                team.setGravity(android.view.Gravity.CENTER); team.setBackground(ui.shape(FILL, 99)); team.setPadding(ui.dp(10), 0, ui.dp(10), 0);
                team.setContentDescription("에이전트 팀과 한도 자동 전환 설정"); team.setOnClickListener(v -> teamUi().show()); Ui.pressable(team, false);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, ui.dp(32)); tp.rightMargin = ui.dp(4); composerEngines.addView(team, tp);
            }
            String[] parts = chipParts(selection, engine);
            composerEngines.addView(modelText(parts, "모델과 생각 깊이 바꾸기, 지금 " + modelChip(engine), () -> modelPopover(composerEngines.getChildAt(composerEngines.getChildCount() - 1))));
        }
    }
    /**
     * The repository chip on the line under the field: "▢ name ⌄", or "▢ ⌄" while none is chosen (its tooltip and spoken name
     * say 저장소 없음). Shown while GitHub is connected and used by 작업창 (설정 → 커넥터 → GitHub); a tap opens the picker.
     */
    private void refreshRepo() {
        if (composerRepoRow == null) return;
        boolean shown = store.githubConnected() && store.settings.getBoolean("githubCoding", true) && !store.scopedModelsUnavailable;
        String repo = "";
        if (shown) { try { repo = workModels().repo(); } catch (Exception e) { shown = false; } }
        String fingerprint = shown + "\u0000" + repo + "\u0000" + workId + "\u0000" + composerOneRow;
        if (fingerprint.equals(lastRepo)) return;
        lastRepo = fingerprint;
        composerRepo.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (!shown) return;
        // Small, on the line under the field: the repository icon alone while none is chosen, else the icon and the
        // repository's name (its owner left out), cut short past a few words.
        composerRepo.setMaxWidth(ui.dp(composerOneRow ? 220 : 150));
        String name = repo.substring(repo.indexOf('/') + 1);
        android.text.SpannableStringBuilder label = new android.text.SpannableStringBuilder(name);
        if (!name.isEmpty()) label.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, label.length(), 0);
        composerRepo.setText(label);
        android.graphics.drawable.Drawable icon = getDrawable(R.drawable.ic_repo).mutate(); icon.setTint(MUTED); icon.setBounds(0, 0, ui.dp(16), ui.dp(16));
        android.graphics.drawable.Drawable chevron = getDrawable(R.drawable.ic_chevron_down).mutate(); chevron.setTint(MUTED); chevron.setBounds(0, 0, ui.dp(14), ui.dp(14));
        composerRepo.setCompoundDrawablesRelative(icon, null, chevron, null); composerRepo.setCompoundDrawablePadding(ui.dp(4));
        composerRepo.setTooltipText(repo.isEmpty() ? "저장소 없음" : repo);
        composerRepo.setContentDescription("GitHub 저장소, 지금 " + (repo.isEmpty() ? "저장소 없음" : repo) + ", 눌러서 바꾸기");
    }
    /**
     * The picker for this 작업창: a room's own repository, or for a 작업창 not sent yet 기본 저장소 (ScopedModels.repo). Before
     * GitHub is connected (or with it turned off) it says so and opens 설정 → 커넥터 → GitHub.
     */
    private void pickRepo() {
        ScopedModels selection = workModels(); String current;
        try { current = selection.repo(); } catch (Exception e) { store.error(e.getMessage()); return; }
        boolean fresh = workId.isEmpty();
        new GitHubRepoPicker(this, ui, store, "GitHub 저장소", fresh ? "새 작업창이 일할 저장소예요 · 기본 저장소는 그대로예요" : "이 작업창은 다음 메시지부터 이 저장소에서 일해요", current, () -> openSettings("github"), repo -> {
            try { selection.repo(repo); } catch (Exception e) { store.error(e.getMessage()); return; }
            lastRepo = ""; updateComposer();
            store.status(repo.isEmpty() ? "저장소 없이 일해요 · 대화에서 저장소를 말해 주세요" : (fresh ? "새 작업창은 " : "이 작업창은 다음 메시지부터 ") + repo + "에서 일해요");
        }).show();
    }
    /** "Sonnet 5.5  보통 ⌄": the model in the text color, the depth quieter, a small chevron; no icon, no box (as in Claude's and ChatGPT's apps). */
    private TextView modelText(String[] parts, String description, Runnable action) {
        android.text.SpannableStringBuilder label = new android.text.SpannableStringBuilder(parts[0]);
        label.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, label.length(), 0);
        if (!parts[1].isEmpty()) { int at = label.length(); label.append("  ").append(parts[1]); label.setSpan(new android.text.style.ForegroundColorSpan(MUTED), at, label.length(), 0); }
        TextView chip = ui.text("", 13, 500, TEXT); chip.setText(label); chip.setSingleLine(true); chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END); chip.setPadding(ui.dp(8), 0, ui.dp(4), 0); chip.setMinHeight(ui.dp(36));
        // On the slim line under the field, beside the repository: a long model name is cut short rather than crowding it.
        chip.setMaxWidth(ui.dp(composerOneRow ? 280 : 210));
        android.graphics.drawable.Drawable chevron = getDrawable(R.drawable.ic_chevron_down).mutate(); chevron.setTint(MUTED); chevron.setBounds(0, 0, ui.dp(15), ui.dp(15));
        chip.setCompoundDrawablesRelative(null, null, chevron, null); chip.setCompoundDrawablePadding(ui.dp(4));
        chip.setContentDescription(description); chip.setOnClickListener(v -> action.run()); Ui.pressable(chip, true);
        return chip;
    }
    /** The chip's two parts: the model (with "Fast" when GPT's fast processing is on) and its thinking level. */
    private String[] chipParts(ScopedModels selection, String engine) {
        String model = selection.model(engine);
        if (model.isBlank()) return new String[]{"모델 고르기", ""};
        Reasoning.Profile profile = engineProfile(engine, model); String effort = selection.effort(engine, model);
        String name = modelLabel(engine, model) + (WorkBook.CHATGPT.equals(engine) && "fast".equals(selection.speed(model)) ? " Fast" : "");
        if (profile.efforts.isEmpty()) return new String[]{name, ""};
        return new String[]{name, effortWord(engine, effort.isEmpty() ? profile.defaultEffort : effort)};
    }
    private static String effortWord(String engine, String effort) { return WorkBook.CHATGPT.equals(engine) ? Reasoning.gptShortLabel(effort) : WorkBook.CLAUDE.equals(engine) ? Reasoning.claudeLabel(effort) : effortName(effort); }
    /** The small menu over the chip (ModelPopover); its 더 많은 모델·설정 opens the full sheet (pickWorkModel). */
    private void modelPopover(View anchor) {
        ScopedModels selection = workModels();
        new ModelPopover(this, ui, store, selection, new ModelPopover.Catalog() {
            @Override public JSONArray models(String engine) { return engineModels(engine); }
            @Override public String label(String engine, String model) { return modelLabel(engine, model); }
            @Override public Reasoning.Profile profile(String engine, String model) { return engineProfile(engine, model); }
            @Override public String effortLabel(String engine, String effort) { return effortWord(engine, effort); }
            @Override public String effortHint(String effort, String fallback) { return MainActivity.effortHint(effort, fallback); }
            @Override public String team() {
                return selection.teamEnabled() ? (selection.teamAutomatic() ? "자동" : dev.jun.gyeot.core.TeamPlan.enabled(selection.teamProfiles()).size() + "명") : selection.teamFallback() ? "한도 전환" : "꺼짐";
            }
        }, engine -> chooseEngine(selection, engine), this::pickWorkModel, () -> teamUi().show(), () -> { lastEngines = ""; updateComposer(); store.changed(); }).show(anchor);
    }
    private void pickWorkProvider() {
        ScopedModels selection = workModels();
        Ui.Sheet sheet = ui.selectionSheet("이 작업창의 AI", "다른 작업창과 음성·녹음 설정은 바뀌지 않아요");
        for (String engine : new String[]{WorkBook.CHATGPT, WorkBook.CLAUDE, WorkBook.GEMINI, WorkBook.GROK, WorkBook.NIM})
            sheet.body.addView(ui.listRow(0, ACCENT, WorkBook.author(engine), store.ready(engine) ? modelChip(engine) : "연결 필요", ui.chevron(), () -> { sheet.dismiss(); chooseEngine(selection, engine); }));
        sheet.show();
    }
    /** Only this workroom owns its next-message selection. */
    private void chooseEngine(String engine) { chooseEngine(workModels(), engine); }
    private void chooseEngine(ScopedModels selection, String engine) {
        String name = WorkBook.author(engine);
        try { selection.chooseEngine(engine); } catch (Exception e) { store.error(e.getMessage()); return; }
        composerVideo = false; composerVideoSource = ""; composerImageAspect = ""; composerImageEdit = false; refreshFiles(); lastEngines = ""; updateComposer();
        if (!store.ready(engine)) {
            boolean gemini = WorkBook.GEMINI.equals(engine);
            store.error(gemini ? "Gemini API 키를 먼저 넣어 주세요." : name + "를 먼저 연결해 주세요.");
            openSettings(WorkBook.CLAUDE.equals(engine) ? "claude" : gemini ? "key" : WorkBook.GROK.equals(engine) ? "grok" : WorkBook.NIM.equals(engine) ? "nim" : "chatgpt"); return;
        }
        store.status("다음 메시지부터 " + name + "가 답해요 · 앞의 대화도 이어서 봐요"); store.changed(); updateComposer();
    }
    // Catalogs are account-wide; selections belong only to this room or recording.
    private JSONArray engineModels(String engine) {
        return WorkBook.CLAUDE.equals(engine) ? store.claude.models() : WorkBook.GEMINI.equals(engine) ? store.gemini.models() : WorkBook.GROK.equals(engine) ? store.grok.models() : WorkBook.NIM.equals(engine) ? store.nim.models() : store.openai.models();
    }
    private ScopedModels workModels() { return new ScopedModels(store, workId); }
    private String workEngineName() { return WorkBook.author(workModels().engine()); }
    private Reasoning.Profile engineProfile(String engine, String model) {
        return WorkBook.CLAUDE.equals(engine) ? store.claude.profile(model) : WorkBook.GEMINI.equals(engine) ? store.gemini.profile(model) : WorkBook.GROK.equals(engine) ? store.grok.profile(model) : WorkBook.NIM.equals(engine) ? store.nim.profile(model) : store.openai.reasoningProfile(model);
    }
    /** "Opus 5.5 · 중간": the engine's model and its reasoning level as saved for that model. */
    private String modelChip(String engine) { return modelChip(workModels(), engine); }
    private String modelChip(ScopedModels selection, String engine) {
        String model = selection.model(engine);
        if (model.isBlank()) return "모델 고르기";
        Reasoning.Profile profile = engineProfile(engine, model);
        String effort = selection.effort(engine, model);
        return modelLabel(engine, model) + (WorkBook.CHATGPT.equals(engine) && "fast".equals(selection.speed(model)) ? " · Fast 요청" : "") + (profile.efforts.isEmpty() ? "" : " · " + (effort.isEmpty() ? "기본" + (profile.defaultEffort.isEmpty() ? "" : "(" + (WorkBook.CHATGPT.equals(engine) ? Reasoning.gptShortLabel(profile.defaultEffort) : effortName(profile.defaultEffort)) + ")") : (WorkBook.CHATGPT.equals(engine) ? Reasoning.gptShortLabel(effort) : effortName(effort))));
    }
    /**
     * A model's name from the engine's catalog, without the "Claude " or "Gemini " the capsule already says. A Gemini model
     * outside the list (the version behind "Flash 최신" that wrote an answer) is named from its id: "3.8 Flash".
     */
    private String modelLabel(String engine, String id) {
        boolean claude = WorkBook.CLAUDE.equals(engine), gemini = WorkBook.GEMINI.equals(engine);
        JSONArray models = engineModels(engine);
        String name = gemini ? GeminiWork.label(id) : id;
        for (int i = 0; i < models.length(); i++) if (models.optJSONObject(i) != null && id.equals(models.optJSONObject(i).optString("id"))) name = models.optJSONObject(i).optString("name", name);
        return claude && name.startsWith("Claude ") ? name.substring(7) : gemini && name.startsWith("Gemini ") ? name.substring(7) : name;
    }
    static String effortName(String effort) {
        return switch (effort) {
            case "none" -> "추론 끔"; case "minimal" -> "최소"; case "low" -> "낮음"; case "medium" -> "보통"; case "high" -> "높음";
            case "xhigh" -> "매우 높음"; case "max" -> "최대"; case "ultra" -> "최고"; default -> "기본";
        };
    }
    /** What a thinking level means for the answer; "" is the model's own choice, usually fallback. */
    static String effortHint(String effort, String fallback) {
        return switch (effort) {
            case "none" -> "생각 없이 바로 답해요. 가장 빨라요.";
            case "minimal" -> "거의 생각하지 않고 바로 답해요. 간단한 일에 빨라요.";
            case "low" -> "가볍게 생각하고 답해요. 빠른 편이에요.";
            case "medium" -> "적당히 생각해요. 대부분의 일에 알맞아요.";
            case "high" -> "깊게 생각해요. 어려운 문제에 좋지만 조금 느려요.";
            case "xhigh" -> "아주 깊게 생각해요. 느리고 사용량이 늘어요.";
            case "max", "ultra" -> "할 수 있는 만큼 오래 생각해요. 가장 느리고 사용량이 많아요.";
            default -> fallback.isEmpty() ? "모델이 알아서 깊이를 정해요." : "기본으로 두면 ‘" + effortName(fallback) + "’ 깊이로 생각해요.";
        };
    }
    /** One line on what a model is good at, read from its id; its id when there is nothing sure to say, "" when the name already is the id. */
    private static String modelHint(String engine, String id, String name) {
        String lower = id.toLowerCase(Locale.ROOT);
        if (WorkBook.GEMINI.equals(engine)) {
            String what = lower.contains("flash-lite") ? "가장 가볍고 빨라요 · 간단한 일에" : lower.contains("flash") ? "빠르고 똑똑해요 · 대부분의 일에" : "깊이 생각해요 · 어려운 일에, 무료 한도가 적거나 없어요";
            return (lower.endsWith("-latest") ? "늘 가장 새 버전 · " : "") + what;
        }
        if (WorkBook.CLAUDE.equals(engine)) {
            if (lower.contains("opus")) return "가장 똑똑해요 · 어려운 일과 긴 작업에";
            if (lower.contains("sonnet")) return "똑똑하고 빨라요 · 대부분의 일에";
            if (lower.contains("haiku")) return "가장 빠르고 가벼워요 · 간단한 일에";
        } else {
            if (lower.contains("-pro")) return "가장 오래 생각해요 · 느리고 사용량이 많아요";
            if (lower.contains("nano")) return "가장 가볍고 빨라요";
            if (lower.contains("mini")) return "빠르고 가벼워요 · 간단한 일에";
            if (lower.contains("codex")) return "코딩에 맞춘 모델이에요";
        }
        return id.equals(name) ? "" : id;
    }
    /**
     * Who answers the next message and how, in a sheet from the bottom: the three AIs on top, the engine's models as a list
     * with the chosen one checked and a line on what each is good at, then its thinking level as capsules with what the
     * chosen level means. Model and depth are isolated from account and voice preferences.
     */
    private void pickWorkModel() { pickScopedModel(workModels(), false); }
    private void pickRecordingModel() { pickScopedModel(ScopedModels.recording(store), true); }
    private void pickScopedModel(ScopedModels selection, boolean recording) {
        store.openai.refreshModelsIfDue();
        Ui.Sheet sheet = ui.selectionSheet(recording ? "녹음 요약 모델" : "작업창 모델 선택",
            recording ? "녹음 요약에만 적용돼요 · 음성 비서와 작업창은 바뀌지 않아요" : "이 작업창의 다음 메시지부터 적용돼요");
        LinearLayout providers = ui.column(); sheet.controls.addView(providers);
        EditText search = ui.input("모델 검색", 1, 80); search.setTextSize(14); search.setContentDescription("모델 이름 검색");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = ui.dp(10); sheet.controls.addView(search, sp);
        Runnable[] fill = new Runnable[1]; String[] shown = {""}; boolean[] asked = {false};
        fill[0] = () -> {
            String engine = selection.engine();
            if (WorkBook.GEMINI.equals(engine) && !asked[0] && !store.gemini.listed()) { asked[0] = true; store.gemini.refreshModelsAsync(); }
            String current = selection.model(engine); JSONArray models = engineModels(engine);
            shown[0] = engine + "\u0000" + models + "\u0000" + current + "\u0000" + selection.effort(engine, current) + "\u0000" + store.openai.modelsStatus();
            providers.removeAllViews(); String[] ids = {WorkBook.CHATGPT, WorkBook.CLAUDE, WorkBook.GEMINI, WorkBook.GROK, WorkBook.NIM};
            providers.addView(ui.pills(new String[]{"ChatGPT", "Claude", "Gemini", "Grok", "NIM"}, java.util.Arrays.asList(ids).indexOf(engine), index -> {
                if (ids[index].equals(selection.engine())) return;
                try { selection.chooseEngine(ids[index]); } catch (Exception e) { store.error(e.getMessage()); return; }
                search.setText(""); fill[0].run();
            }));
            android.widget.HorizontalScrollView providerScroll = (android.widget.HorizontalScrollView) providers.getChildAt(0);
            int selectedProvider = java.util.Arrays.asList(ids).indexOf(engine);
            if (selectedProvider >= 0) providerScroll.post(() -> {
                View selectedProviderView = ((LinearLayout) providerScroll.getChildAt(0)).getChildAt(selectedProvider);
                providerScroll.scrollTo(Math.max(0, selectedProviderView.getLeft() - ui.dp(8)), 0);
            });
            sheet.body.removeAllViews();
            if (engine.isEmpty()) { sheet.body.addView(ui.label("위에서 녹음이나 작업을 맡길 AI를 선택해 주세요.", 14, MUTED)); return; }
            if (!store.ready(engine)) sheet.body.addView(ui.button("선택한 AI 연결하기", false, () -> {
                sheet.dismiss(); openSettings(WorkBook.CLAUDE.equals(engine) ? "claude" : WorkBook.GEMINI.equals(engine) ? "key" : WorkBook.GROK.equals(engine) ? "grok" : WorkBook.NIM.equals(engine) ? "nim" : "chatgpt");
            }));
            LinearLayout selected = ui.row(); selected.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
            android.graphics.drawable.GradientDrawable selectedBg = ui.shape(ACCENT_SOFT, 16); selectedBg.setStroke(Math.max(1, ui.dp(0.5f)), ACCENT); selected.setBackground(selectedBg);
            LinearLayout currentWords = ui.column(); currentWords.addView(ui.text("현재 선택", 11.5f, 600, ACCENT));
            TextView currentName = ui.text(modelLabel(engine, current), 16, 650, TEXT); currentName.setPadding(0, ui.dp(4), 0, 0); currentWords.addView(currentName);
            selected.addView(currentWords, new LinearLayout.LayoutParams(0, -2, 1)); selected.addView(ui.text("✓", 19, 700, ACCENT)); sheet.body.addView(selected);
            Reasoning.Profile profile = engineProfile(engine, current);
            if (!current.isBlank() && !profile.efforts.isEmpty()) {
                TextView heading = ui.text(WorkBook.CHATGPT.equals(engine) ? "추론 레벨" : "생각 깊이", 12, 600, MUTED); heading.setPadding(ui.dp(2), ui.dp(14), 0, ui.dp(8)); sheet.body.addView(heading);
                String[] values = profile.values(), names = new String[values.length]; String saved = selection.effort(engine, current); int chosen = 0;
                for (int i = 0; i < values.length; i++) { names[i] = i == 0 ? (WorkBook.GEMINI.equals(engine) ? "기본 · " + (WorkBook.CHATGPT.equals(engine) ? Reasoning.gptShortLabel(profile.defaultEffort) : effortName(profile.defaultEffort)) : "기본") : (WorkBook.CHATGPT.equals(engine) ? Reasoning.gptShortLabel(values[i]) : effortName(values[i])); if (values[i].equals(saved)) chosen = i; }
                sheet.body.addView(ui.pills(names, chosen, index -> {
                    if (values[index].equals(selection.effort(engine, current))) return;
                    try { selection.effort(engine, current, values[index]); } catch (Exception e) { store.error(e.getMessage()); }
                    fill[0].run();
                }));
            }
            if (WorkBook.CHATGPT.equals(engine) && !current.isBlank()) GptSpeedUi.add(this, ui, sheet.body, store.openai, current, selection.speed(current), value -> {
                try { selection.speed(current, value); } catch (Exception e) { store.error(e.getMessage()); }
                fill[0].run();
            });
            String query = search.getText().toString().strip().toLowerCase(Locale.ROOT);
            TextView listTitle = ui.text(query.isEmpty() ? "모든 모델" : "검색 결과", 12, 600, MUTED); listTitle.setPadding(ui.dp(2), ui.dp(18), 0, ui.dp(8)); sheet.body.addView(listTitle);
            LinearLayout list = ui.group(); int found = 0;
            for (int i = 0; i < models.length(); i++) {
                JSONObject model = models.optJSONObject(i); if (model == null) continue;
                String id = model.optString("id"), name = modelLabel(engine, id), hint = modelHint(engine, id, name); boolean on = id.equals(current);
                if (!(name + " " + id + " " + hint).toLowerCase(Locale.ROOT).contains(query)) continue;
                found++;
                LinearLayout row = ui.row(); row.setMinimumHeight(ui.dp(64)); row.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
                LinearLayout words = ui.column(); words.addView(ui.text(name, 15, on ? 650 : 500, on ? ACCENT : TEXT));
                if (!hint.isEmpty()) { TextView line = ui.text(hint, 12, 400, MUTED); line.setMaxLines(2); line.setEllipsize(TextUtils.TruncateAt.END); line.setPadding(0, ui.dp(4), 0, 0); words.addView(line); }
                row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                TextView check = ui.text(on ? "●" : "○", 20, 500, on ? ACCENT : SEPARATOR); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ui.dp(28), -2); cp.leftMargin = ui.dp(8); row.addView(check, cp);
                row.setSelected(on); row.setContentDescription(name + (hint.isEmpty() ? "" : ", " + hint) + (on ? ", 선택됨" : "")); row.setFocusable(true);
                row.setOnClickListener(v -> { if (!id.equals(selection.model(engine))) { try { selection.model(engine, id); } catch (Exception e) { store.error(e.getMessage()); } fill[0].run(); } }); Ui.pressable(row, true); ui.addRow(list, row, false);
            }
            if (found > 0) sheet.body.addView(list);
            else { TextView empty = ui.label(query.isEmpty() ? "모델 목록을 불러오는 중이거나 연결을 확인해야 해요." : "일치하는 모델이 없어요.", 14, MUTED); empty.setPadding(ui.dp(8), ui.dp(20), ui.dp(8), ui.dp(20)); sheet.body.addView(empty); }
            TextView note = ui.label(WorkBook.NIM.equals(engine) ? "NVIDIA 기본 생각 설정을 사용해요. 텍스트·이미지를 지원하며, 웹 검색·코드 실행·PDF 원본 전송은 제공하지 않아요." : "생각 깊이는 모델마다 기억해요. 깊을수록 느리고 사용량이 늘어요.", 12, MUTED); note.setPadding(ui.dp(2), ui.dp(14), ui.dp(2), 0); sheet.body.addView(note);
            if (WorkBook.CHATGPT.equals(engine)) {
                sheet.body.addView(ui.label(store.openai.modelsStatus(), 12, MUTED));
                sheet.body.addView(ui.actions(ui.chip("목록 새로고침", store.openai::refreshModelsAsync)));
            }
            if (WorkBook.GEMINI.equals(engine)) {
                if (!store.gemini.notice.isBlank()) { TextView notice = ui.label(store.gemini.notice, 12, MUTED); notice.setPadding(0, ui.dp(8), 0, 0); sheet.body.addView(notice); }
                sheet.body.addView(ui.actions(ui.chip("목록 새로고침", store.gemini::refreshModelsAsync), ui.chip("모델 직접 입력", () -> typeGeminiModel(selection))));
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { fill[0].run(); sheet.scroll.scrollTo(0, 0); }
            @Override public void afterTextChanged(Editable e) {}
        });
        Runnable watch = () -> {
            String engine = selection.engine(), current = selection.model(engine);
            if (sheet.isShowing() && !(engine + "\u0000" + engineModels(engine) + "\u0000" + current + "\u0000" + selection.effort(engine, current) + "\u0000" + store.openai.modelsStatus()).equals(shown[0])) fill[0].run();
        };
        fill[0].run(); store.observe(watch);
        sheet.setOnDismissListener(d -> { store.unobserve(watch); updateComposer(); if (recording && tab == 0 && recordingPage) showTab(0); }); sheet.show();
    }
    /** A Gemini model by its name, for one the key's list does not show yet (a new family like gemini-4-argon). */
    private void typeGeminiModel(ScopedModels selection) {
        LinearLayout form = ui.column(); form.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0);
        EditText name = ui.input("gemini-4-argon", 1, 64); name.setText(selection.model(WorkBook.GEMINI)); name.setSelectAllOnFocus(true); form.addView(name);
        TextView note = ui.label("AI Studio나 Google 문서에 나온 모델 이름을 그대로 넣어요. 이 키로 쓸 수 없는 모델이면 보낼 때 알려 줘요.", 12, MUTED); note.setPadding(0, ui.dp(8), 0, 0); form.addView(note);
        new AlertDialog.Builder(this).setTitle("Gemini 모델 이름").setView(form).setNegativeButton("취소", null).setPositiveButton("쓰기", (d, w) -> {
            try { String id = name.getText().toString().strip().toLowerCase(Locale.ROOT); if (!GeminiWork.validModel(id)) throw new IllegalArgumentException("모델 이름은 gemini-로 시작해요."); selection.model(WorkBook.GEMINI, id); store.status("선택한 공간의 모델을 " + GeminiWork.label(id) + "로 바꿨어요"); }
            catch (Exception e) { store.error(e.getMessage()); }
        }).show();
    }
    /**
     * A picked file that could not be attached: the reason goes to the page's error line and, because that line is at the
     * top of a page the user may have scrolled down (the work list, a long conversation), also to a toast over whatever is
     * on screen. Without it a refused pick looks as if nothing had happened.
     */
    private void attachFailed(String reason) { store.error(reason); android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_LONG).show(); }
    /** Opens the system file picker for pictures, PDFs, text files, video and audio. */
    private void pickFiles() {
        if (store.workFiles.length() >= WorkBook.ATTACHMENTS) { store.error("한 번에 " + WorkBook.ATTACHMENTS + "개까지 첨부할 수 있어요."); return; }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf", "text/*", "video/*", "audio/*", "application/json", "application/xml"})
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(pick, PICK_FILES); } catch (Exception e) { store.error("파일을 고를 앱을 열지 못했어요."); }
    }
    /** Copies the picked files into private storage off the main thread; each joins the next message when ready. */
    private void addPicked(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        store.status("첨부하는 중…");
        new Thread(() -> {
            for (Uri uri : uris) {
                try {
                    JSONObject file = WorkFiles.addPicked(this, store, uri);
                    runOnUiThread(() -> {
                        if (store.workFiles.length() < WorkBook.ATTACHMENTS) store.workFiles.put(file);
                        else { store.attachmentFile(file.optString("id")).delete(); store.error("한 번에 " + WorkBook.ATTACHMENTS + "개까지 첨부할 수 있어요."); }
                        refreshFiles();
                    });
                } catch (Exception e) { String reason = e.getMessage() == null ? "파일을 첨부하지 못했어요." : e.getMessage(); runOnUiThread(() -> attachFailed(reason)); }
            }
            runOnUiThread(() -> { if (store.workFiles.length() > 0) store.status("첨부 " + store.workFiles.length() + "개 · 보낼 때 함께 가요"); });
        }, "gyeot-attach").start();
    }
    /**
     * Opens the system file picker for the main chat's next message: photos, PDFs and text files, several at once, read from
     * the 들퍼레 folder as 작업창's are.
     */
    void pickMainFiles() {
        if (store.mainFiles.length() >= MainAttachments.COUNT) { store.error("한 번에 " + MainAttachments.COUNT + "개까지 첨부할 수 있어요."); return; }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf", "text/*", "application/json", "application/xml"})
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(pick, PICK_MAIN_FILES); } catch (Exception e) { store.error("파일을 고를 앱을 열지 못했어요."); }
    }
    /**
     * Imports the picked files off the main thread with 작업창's import (WorkFiles.add: photos made smaller, a PDF as it is, a
     * text file's text read), then keeps a photo's or a PDF's copy apart from 작업창's (AppStore.mainAttachmentFile). Each joins
     * the main chat's next message when ready; one the chat cannot send is refused with the reason.
     */
    private void addMainPicked(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        store.status("첨부하는 중…");
        new Thread(() -> {
            for (Uri uri : uris) {
                try {
                    // Video and audio stay 작업창's: refused before anything is copied.
                    String type = getContentResolver().getType(uri);
                    if (type != null && (type.startsWith("video/") || type.startsWith("audio/"))) throw new IllegalArgumentException(MainAttachments.refusal(null, null));
                    JSONObject file = WorkFiles.addPicked(this, store, uri); String id = file.optString("id");
                    if (!MainAttachments.KINDS.contains(file.optString("kind"))) { store.attachmentFile(id).delete(); throw new IllegalArgumentException(MainAttachments.refusal(null, file)); }
                    if (MainAttachments.binary(file)) java.nio.file.Files.move(store.attachmentFile(id).toPath(), store.mainAttachmentFile(id).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    runOnUiThread(() -> {
                        String refused = MainAttachments.refusal(store.mainFiles, file);
                        if (refused.isEmpty()) store.mainFiles.put(file); else { store.mainAttachmentFile(id).delete(); store.error(refused); }
                        if (mainChat != null) mainChat.filesChanged();
                    });
                } catch (Exception e) { String reason = e.getMessage() == null ? "파일을 첨부하지 못했어요." : e.getMessage(); runOnUiThread(() -> attachFailed(reason)); }
            }
            runOnUiThread(() -> { if (store.mainFiles.length() > 0) store.status("첨부 " + store.mainFiles.length() + "개 · 보낼 때 함께 가요"); });
        }, "gyeot-main-attach").start();
    }
    /** The capsules of files waiting for the next message; tapping one takes it out. */
    private void refreshFiles() {
        if (composerFiles == null) return;
        composerFiles.removeAllViews();
        if (composerVideo) {
            JSONObject source = Gallery.find(store.gallery(), composerVideoSource);
            LinearLayout mode = ui.row(); mode.setBackground(ui.shape(ACCENT_SOFT, 99));
            TextView option = ui.text("동영상 · " + store.settings.getString("videoResolution", "1080p") + " · " + store.settings.getInt("videoDuration", 8) + "초 ▾", 13, 600, ACCENT);
            option.setMinHeight(ui.dp(44)); option.setGravity(android.view.Gravity.CENTER); option.setPadding(ui.dp(12), 0, ui.dp(6), 0);
            option.setContentDescription(source == null ? "설명으로 동영상 생성, 옵션 바꾸기" : "선택한 이미지로 동영상 생성, 옵션 바꾸기"); option.setOnClickListener(v -> workVideoOptions()); mode.addView(option);
            mode.addView(ui.iconButton(R.drawable.ic_close, 0, ACCENT, "동영상 모드 해제", () -> { composerVideo = false; composerVideoSource = ""; lastEngines = ""; refreshFiles(); updateComposer(); }), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(44)));
            composerFiles.addView(mode);
        }
        if (!composerImageAspect.isEmpty()) {
            LinearLayout mode = ui.row(); mode.setBackground(ui.shape(ACCENT_SOFT, 99));
            String ratio = switch (composerImageAspect) { case "square" -> "1:1"; case "portrait" -> "2:3"; case "landscape" -> "3:2"; default -> composerImageAspect; };
            TextView label = ui.text((composerImageEdit ? "이미지 편집 · " : "이미지 · ") + ratio, 13, 600, ACCENT); label.setPadding(ui.dp(12), 0, ui.dp(6), 0);
            label.setGravity(android.view.Gravity.CENTER); label.setMinHeight(ui.dp(40)); label.setOnClickListener(v -> drawInWork()); label.setContentDescription("이미지 옵션 바꾸기, " + ratio); mode.addView(label);
            android.widget.ImageView close = ui.iconButton(R.drawable.ic_close, 0, ACCENT, "이미지 모드 해제", () -> { composerImageAspect = ""; composerImageEdit = false; refreshFiles(); updateComposer(); });
            mode.addView(close, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(40)));
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, ui.dp(40)); mp.rightMargin = ui.dp(6); composerFiles.addView(mode, mp);
        }
        for (int i = 0; i < store.workFiles.length(); i++) {
            JSONObject file = store.workFiles.optJSONObject(i); String id = file.optString("id"), name = file.optString("name");
            TextView chip = ui.text(fileIcon(file.optString("kind")) + " " + (name.length() > 24 ? name.substring(0, 23) + "…" : name) + "  ✕", 13, 560, TEXT);
            chip.setBackground(ui.shape(FILL, 99)); chip.setPadding(ui.dp(12), 0, ui.dp(12), 0); chip.setGravity(android.view.Gravity.CENTER); chip.setContentDescription(name + " 첨부 빼기");
            chip.setOnClickListener(v -> {
                JSONArray kept = new JSONArray();
                for (int k = 0; k < store.workFiles.length(); k++) if (!store.workFiles.optJSONObject(k).optString("id").equals(id)) kept.put(store.workFiles.optJSONObject(k));
                store.workFiles = kept; store.attachmentFile(id).delete(); refreshFiles();
            });
            Ui.pressable(chip, false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, ui.dp(30)); p.rightMargin = ui.dp(6); composerFiles.addView(chip, p);
        }
        if (composerFilesRow != null) composerFilesRow.setVisibility(store.workFiles.length() == 0 && composerImageAspect.isEmpty() && !composerVideo ? View.GONE : View.VISIBLE);
    }
    private static String fileIcon(String kind) {
        return switch (kind) { case WorkBook.IMAGE -> "🖼"; case WorkBook.PDF -> "📄"; case WorkBook.VIDEO -> "🎬"; case WorkBook.AUDIO -> "🎧"; default -> "📝"; };
    }
    /** A picture from private storage (an attached photo or ChatGPT's drawing), about width wide; null when it is gone. */
    private View picture(String id, int width, Runnable open, String name) {
        android.graphics.Bitmap bitmap = previews.get(id);
        if (bitmap == null) { bitmap = WorkFiles.preview(store.attachmentFile(id), width); if (bitmap != null) previews.put(id, bitmap); }
        if (bitmap == null) return null;
        android.widget.ImageView view = new android.widget.ImageView(this); view.setImageBitmap(bitmap); view.setAdjustViewBounds(true);
        view.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER); view.setMaxWidth(width); view.setMaxHeight(width * 4 / 3);
        view.setBackground(ui.shape(FILL, 16)); view.setClipToOutline(true); view.setContentDescription(name);
        if (open != null) { view.setOnClickListener(v -> open.run()); Ui.pressable(view, false); }
        return view;
    }
    /** Read on every redraw and, while a turn runs, every keystroke: only this room's state, never a copy of every room (AppStore.workState). */
    private boolean workRunning() {
        return !workId.isEmpty() && WorkBook.RUNNING.equals(store.workState(workId));
    }
    /**
     * Read on every keystroke while a turn runs: the room's messages are read only for a draft that could ask for a picture,
     * and then only this room's, trimmed to what the check needs (AppStore.workDrawingHistory), never a copy of every room.
     */
    private boolean composerCreatesMedia() {
        if (composerVideo || !composerImageAspect.isEmpty()) return true;
        if (composerInput == null || composerInput.getText().toString().isBlank()) return false;
        String id = workId;
        try { return Drawing.chatArgumentsLazily(composerInput.getText().toString(), () -> store.workDrawingHistory(id), store.workFiles, store.codexImages.connected()) != null; }
        catch (Exception e) { return false; }
    }
    private void composerAction() {
        boolean typed = !composerInput.getText().toString().isBlank();
        sendWorkMessage(workId, composerInput);
        // A firm confirm under the thumb once the message has actually gone (a successful send clears the field).
        if (typed && composerInput.length() == 0) composerSend.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
    }
    /** The running turn would be stopped by the composer's button: nothing typed, nothing attached, and the draft has been empty for a moment. */
    private boolean composerWouldStop() {
        return tab == 3 && workRunning() && composerInput.getText().toString().isBlank() && store.workFiles.length() == 0
            && android.os.SystemClock.elapsedRealtime() - composerEmptiedAt >= STOP_GUARD_MS;
    }
    /** The composer's one button: stop while a turn runs with an empty draft, otherwise send. A typed instruction is always sent, never dropped by a stop. */
    private void composerButton() {
        if (composerWouldStop()) send(AssistantService.WORK_STOP, "thread", workId);
        else if (composerOffersVoice() && workVoice != null) { stopComposerDictation(); workVoice.start(); }
        else composerAction();
    }
    /** Nothing to send and the room is not working: the button starts 음성 대화 with this room. */
    private boolean composerOffersVoice() {
        return tab == 3 && !workRunning() && composerInput != null && composerInput.getText().toString().isBlank() && store.workFiles.length() == 0
            && !composerVideo && composerImageAspect.isEmpty();
    }
    private void stopComposerDictation() { if (composerDictation != null) composerDictation.cancel(); }
    /** The composer's hint and button follow the thread: send, or stop while it works and nothing is typed (멈춤 beside the progress line stops it either way). */
    private void updateComposer() {
        if (composer == null) return;
        boolean working = tab == 3 && workRunning(), running = working && !composerCreatesMedia();
        refreshEngines(); refreshRepo();
        composerInput.setHint(composerVideo && !running ? "원하는 장면이나 움직임을 적고 보내세요" : !composerImageAspect.isEmpty() && !running ? "원하는 그림을 적고 보내세요" : workModels().readyEngine().isEmpty() ? store.codexImages.connected() ? "원하는 그림을 적고 ‘그려줘’라고 보내세요" : "모델을 연결하고 메시지를 입력하세요"
            : running ? "추가 지시 보내기 · 다음 단계에 반영" : workId.isEmpty() ? workEngineName() + "에게 새 일 맡기기" : workEngineName() + "에게 이어서 지시하기");
        composerRunning = running;
        boolean stops = composerWouldStop(), offers = !stops && composerOffersVoice();
        if (stops != composerStops || offers != composerOffers) { composerStops = stops; composerOffers = offers; if (offers) workVoice.looks(composerSend, true); else ui.sendLooks(composerSend, stops); }
        String says = stops ? STOP_WORK : offers ? VoiceBar.OFFER : running ? "작업 중 추가 지시 보내기" : "보내기";
        composerSend.setContentDescription(says); composerSend.setTooltipText(says);
        // The guard after a send or a cleared draft ends by itself: look again then.
        long wait = STOP_GUARD_MS - (android.os.SystemClock.elapsedRealtime() - composerEmptiedAt);
        if (working && !stops && wait > 0 && composerInput.getText().toString().isBlank()) { composerSend.removeCallbacks(composerRecheck); composerSend.postDelayed(composerRecheck, wait + 20); }
    }
    private final Runnable composerRecheck = this::updateComposer;
    private void refreshWorkProgress() {
        if (workStageView == null || workId.isEmpty()) return;
        dev.jun.gyeot.core.WorkRunState.Snapshot progress = store.workProgress.snapshot(workId, android.os.SystemClock.elapsedRealtime());
        if (progress == null) return;
        workStageView.setText(progress.summary);
        if (workTimelineView != null) {
            StringBuilder lines = new StringBuilder("실행 단계 요약만 표시하며 비공개 추론은 표시하지 않아요.\n");
            for (dev.jun.gyeot.core.WorkRunState.Event event : progress.events)
                lines.append(Math.max(0, (event.timestampMs - progress.startedAtMs) / 1000)).append("초 · ").append(event.label).append('\n');
            if (progress.stalled) lines.append("최근 통신이 없어요. 완료 여부는 아직 확인되지 않았으며 자동으로 재시도하지 않아요. 멈춤을 누르면 연결을 종료할 수 있어요.");
            workTimelineView.setText(lines.toString().stripTrailing());
        }
        // Gray while it works; amber only when the connection has gone quiet. Red is for real errors.
        int tone = progress.stalled ? WARNING : MUTED;
        if (workStageView.getCurrentTextColor() != tone) { workStageView.setTextColor(tone); ui.foldArrow(workStageView, workId.equals(workLogOpen)); }
    }

    /** Keep optional image controls with attachments; ordinary drawing requests use the regular send button. */
    private WorkTeamUi teamUi() {
        return new WorkTeamUi(this, ui, store, workModels(), this::engineModels, this::modelLabel, this::engineProfile, () -> { lastEngines = ""; updateComposer(); });
    }
    private void refreshTeamProgress() {
        if (workTeamView == null || workId.isEmpty()) return;
        JSONArray agents = store.workAgents.get(workId), switches = store.workSwitches.get(workId);
        String fingerprint = String.valueOf(agents) + "|" + switches;
        if (!fingerprint.equals(teamDrawn)) {
            teamDrawn = fingerprint; workTeamView.removeAllViews();
            if (agents != null && agents.length() > 0 || switches != null && switches.length() > 0) {
                WorkTeamUi team = teamUi();
                Button capsule = ui.chip(agents == null || agents.length() == 0 ? "모델 전환 기록 보기" : team.summary(agents), () -> {
                    Ui.Sheet sheet = ui.selectionSheet("팀 진행 상황", "각 작업을 누르면 결과와 모델 전환을 볼 수 있어요");
                    String threadId = workId; String[] drawn = {""};
                    Runnable update = () -> {
                        JSONArray rows = store.workAgents.get(threadId), changes = store.workSwitches.get(threadId);
                        if (rows == null && changes == null) {
                            JSONObject thread = WorkBook.find(store.works(), threadId); JSONArray messages = thread == null ? null : thread.optJSONArray("messages");
                            JSONObject last = messages == null || messages.length() == 0 ? null : messages.optJSONObject(messages.length() - 1);
                            if (last != null) { rows = last.optJSONArray("agents"); changes = last.optJSONArray("switches"); }
                        }
                        String value = String.valueOf(rows) + changes;
                        if (value.equals(drawn[0])) return; drawn[0] = value; int y = sheet.scroll.getScrollY();
                        sheet.body.removeAllViews(); sheet.body.addView(team.reports(rows, changes)); sheet.scroll.post(() -> sheet.scroll.scrollTo(0, y));
                    };
                    update.run(); store.observe(update); sheet.setOnDismissListener(d -> store.unobserve(update)); sheet.primary.setText("닫기"); sheet.show();
                });
                workTeamView.addView(capsule, new LinearLayout.LayoutParams(-2, -2));
            }
        }
        dev.jun.gyeot.core.WorkRoomState.Snapshot roomLive = store.workLive.get(workId);
        String lead = roomLive.author + "|" + roomLive.model;
        if (workLeadView != null && roomLive.present && !lead.equals(leadDrawn)) {
            leadDrawn = lead; workLeadView.removeAllViews(); workLeadView.addView(speaker(roomLive.author + " · " + modelLabel(engineNamed(roomLive.author), roomLive.model)));
        }
    }
    private void composerOptions() {
        Ui.Sheet sheet = ui.selectionSheet("만들기와 첨부", "내용은 대화 입력창에 적어 주세요");
        sheet.body.addView(ui.listRow(R.drawable.ic_team, Ui.INDIGO, "에이전트 팀", "병렬 작업과 한도 자동 전환", ui.chevron(), () -> { sheet.dismiss(); teamUi().show(); }));
        // The repository this 작업창 works in; also the way in before GitHub is connected, when the chip over the field is hidden.
        String repo = "";
        try { repo = workModels().repo(); } catch (Exception ignored) {}
        LinearLayout github = ui.listRow(R.drawable.ic_repo, Ui.INDIGO, "GitHub 저장소", !store.githubConnected() ? "연결 안 됨 · 연결하면 저장소를 골라요"
            : !store.settings.getBoolean("githubCoding", true) ? "꺼져 있어요 · 작업창에서 GitHub를 쓰지 않아요" : repo.isEmpty() ? "저장소 없음" : repo, ui.chevron(), () -> { sheet.dismiss(); pickRepo(); });
        TextView state = ((Ui.ListRow) github).parts.subtitle(); state.setSingleLine(false); state.setMaxLines(2);
        sheet.body.addView(github);
        sheet.body.addView(ui.listRow(R.drawable.ic_add, Ui.GRAY, "사진·파일 첨부", "사진과 문서를 대화에 첨부", ui.chevron(), () -> { sheet.dismiss(); pickFiles(); }));
        sheet.body.addView(ui.listRow(R.drawable.ic_gallery, Ui.MINT, "이미지 생성", "ChatGPT · 비율과 편집 옵션", ui.chevron(), () -> { sheet.dismiss(); drawInWork(); }));
        sheet.body.addView(ui.listRow(R.drawable.ic_video, Ui.INDIGO, "동영상 생성", "Grok · 720p / 1080p", ui.chevron(), () -> { sheet.dismiss(); workVideoOptions(); })); sheet.show();
    }
    private void workVideoOptions() {
        if (!store.grok.connected()) { openSettings("grok"); store.status("Grok 구독 계정을 먼저 연결해 주세요."); return; }
        JSONObject source = Gallery.find(store.gallery(), composerVideoSource);
        Ui.Sheet sheet = ui.selectionSheet("Grok 동영상", "원하는 장면이나 움직임은 대화창에 입력해요");
        sheet.body.addView(ui.listRow(R.drawable.ic_gallery, Ui.MINT, source == null ? "설명으로 새 영상" : "선택한 이미지로 영상화", source == null ? "사진 없이도 시작할 수 있어요 · 눌러서 이미지 선택" : Gallery.prompt(source), ui.chevron(), () -> { sheet.dismiss(); pickVideoSource(); }));
        int[] seconds = {store.settings.getInt("videoDuration", 8)}, durations = {5, 8, 10, 15}; String[] resolution = {store.settings.getString("videoResolution", "1080p")}, aspect = {store.settings.getString("videoAspect", "16:9")};
        TextView quality = ui.text("화질", 14, 600, TEXT); quality.setPadding(0, ui.dp(20), 0, ui.dp(10)); sheet.body.addView(quality);
        sheet.body.addView(ui.segmented(new String[]{"720p · HD", "1080p · Full HD"}, "1080p".equals(resolution[0]) ? 1 : 0, i -> resolution[0] = i == 1 ? "1080p" : "720p"));
        TextView length = ui.text("길이", 14, 600, TEXT); length.setPadding(0, ui.dp(20), 0, ui.dp(10)); sheet.body.addView(length);
        int selected = 1; for (int i = 0; i < durations.length; i++) if (durations[i] == seconds[0]) selected = i;
        sheet.body.addView(ui.pills(new String[]{"5초", "8초", "10초", "15초"}, selected, i -> seconds[0] = durations[i]));
        if (source == null) { String[] ratios = {"16:9", "9:16", "1:1", "4:3", "3:4"}; int selectedRatio = Math.max(0, java.util.Arrays.asList(ratios).indexOf(aspect[0])); TextView ratio = ui.text("화면 비율", 14, 600, TEXT); ratio.setPadding(0, ui.dp(20), 0, ui.dp(10)); sheet.body.addView(ratio); sheet.body.addView(ui.pills(ratios, selectedRatio, i -> aspect[0] = ratios[i])); }
        TextView note = ui.label(source == null ? "1080p는 더 선명하고 생성 시간이 더 걸릴 수 있어요." : "영상 비율은 원본 이미지를 따라가요. 원본은 그대로 보관해요.", 12, MUTED); note.setPadding(0, ui.dp(16), 0, 0); sheet.body.addView(note);
        sheet.primary.setText("대화창에서 만들기"); sheet.primary.setOnClickListener(v -> { store.settings.edit().putString("videoResolution", resolution[0]).putInt("videoDuration", seconds[0]).putString("videoAspect", aspect[0]).apply(); composerVideo = true; composerImageAspect = ""; composerImageEdit = false; lastEngines = ""; refreshFiles(); updateComposer(); sheet.dismiss(); typeInComposer(null); }); sheet.show();
    }
    private void pickVideoSource() {
        Ui.Sheet sheet = ui.selectionSheet("영상의 시작 장면", "GPT로 만든 이미지를 그대로 영상으로 이어가세요");
        sheet.body.addView(ui.listRow(R.drawable.ic_sparkle, Ui.INDIGO, "설명으로 새 영상", "이미지 없이 프롬프트로 생성", ui.chevron(), () -> { composerVideoSource = ""; sheet.dismiss(); workVideoOptions(); }));
        sheet.body.addView(ui.listRow(R.drawable.ic_add, Ui.GRAY, "들퍼레 폴더 사진 선택", "지정한 들퍼레 폴더 안의 사진만 사용", ui.chevron(), () -> {
            sheet.dismiss(); try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_VIDEO_SOURCE); } catch (Exception e) { store.error("사진 선택 화면을 열지 못했어요."); }
        }));
        JSONArray pictures = store.gallery();
        for (int i = 0; i < Math.min(60, pictures.length()); i++) {
            JSONObject picture = pictures.optJSONObject(i); if (picture == null) continue;
            LinearLayout row = ui.row(); row.setPadding(0, ui.dp(10), 0, ui.dp(10));
            android.widget.ImageView thumb = new android.widget.ImageView(this); thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP); thumb.setBackground(ui.shape(FILL, 12)); thumb.setClipToOutline(true); row.addView(thumb, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64))); loadThumb(thumb, picture.optString("id"), ui.dp(128));
            TextView label = ui.text(Gallery.prompt(picture), 14, 500, TEXT); label.setMaxLines(3); label.setEllipsize(TextUtils.TruncateAt.END); label.setPadding(ui.dp(12), 0, ui.dp(8), 0); row.addView(label, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(ui.chevron()); row.setFocusable(true); row.setOnClickListener(v -> { composerVideoSource = picture.optString("id"); sheet.dismiss(); workVideoOptions(); }); Ui.pressable(row, false); sheet.body.addView(row);
        }
        if (pictures.length() == 0) sheet.body.addView(ui.label("작업창에서 이미지를 먼저 만들면 여기에 나타나요.", 14, MUTED)); sheet.show();
    }
    /** Choose settings only; the next image's description stays in the ordinary conversation composer. */
    private void drawInWork() {
        String aspect = composerImageAspect.isEmpty() ? Drawing.aspect(composerInput.getText().toString()) : composerImageAspect;
        boolean edit = composerImageEdit;
        if (composerImageAspect.isEmpty()) for (int i = 0; i < store.workFiles.length(); i++)
            if (WorkBook.IMAGE.equals(store.workFiles.optJSONObject(i).optString("kind"))) edit = true;
        ui.imageOptions(aspect, edit, (chosen, editing) -> {
            composerVideo = false; composerVideoSource = ""; lastEngines = ""; composerImageAspect = chosen; composerImageEdit = editing; refreshFiles(); updateComposer(); typeInComposer(null);
        }).show();
    }
    /**
     * Places the floating tab bar, the 작업창 composer above it (above the keyboard while typing), and pads the page so
     * nothing ends up under either.
     */
    private void layoutDock() {
        if (workVoice != null) workVoice.enabled(!showingMainChat && tab == 3);
        if(showingMainChat)return;
        boolean typing = imeBottom > 0, dock = tab == 3, inThread = tab == 3 && !workId.isEmpty();
        boolean railWide=navigation!=null&&navigation.wide();
        tabBar.setVisibility(typing||railWide ? View.GONE : View.VISIBLE);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) tabBar.getLayoutParams();
        int tabInset = ui.dp(getResources().getConfiguration().screenWidthDp < 400 ? 12 : 20);
        p.leftMargin = tabInset + insetLeft; p.rightMargin = tabInset + insetRight; p.bottomMargin = (typing ? imeBottom : insetBottom) + ui.dp(10); tabBar.setLayoutParams(p);
        // Content scrolls under the floating tab bar and stops above it; the keyboard shrinks the scroll area so the
        // focused field scrolls into view.
        FrameLayout.LayoutParams area = (FrameLayout.LayoutParams) scroll.getLayoutParams(); area.bottomMargin = typing ? imeBottom : 0;
        // A conversation's bar is part of the navigation bar above this content, so the messages start right under it.
        area.topMargin = 0;
        scroll.setLayoutParams(area);
        // A conversation reads in a centered column (Ui.READING_WIDTH) with the composer on the same lines; a phone keeps its edges.
        int width = contentWidth() - insetLeft - insetRight;
        int side = inThread ? ui.gutter(width, ui.dp(20)) : ui.dp(20), dockSide = ui.gutter(width, ui.dp(12));
        int dockSpace = 0;
        if (composer != null) {
            boolean talking = workVoice != null && workVoice.showing();
            composer.setVisibility(dock && !talking ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams c = (FrameLayout.LayoutParams) composer.getLayoutParams();
            c.leftMargin = dockSide + insetLeft; c.rightMargin = dockSide + insetRight; c.bottomMargin = typing ? imeBottom + ui.dp(8) : insetBottom + ui.dp(railWide?12:82);
            composer.setLayoutParams(c);
            if (workVoice != null) { FrameLayout.LayoutParams v = (FrameLayout.LayoutParams) workVoice.bar().getLayoutParams(); v.leftMargin = c.leftMargin; v.rightMargin = c.rightMargin; v.bottomMargin = c.bottomMargin; workVoice.bar().setLayoutParams(v); }
            // One low row once the field, the model and the buttons fit side by side (more room at large type).
            arrangeComposer(composer, width - 2 * dockSide >= ui.dp(560 * Math.max(1f, getResources().getConfiguration().fontScale)));
            if (dock) dockSpace = Math.max(talking ? workVoice.bar().getHeight() : composer.getHeight(), ui.dp(50)) + ui.dp(16);
        }
        body.setPadding(side + insetLeft, insetTop + ui.dp(inThread ? 16 : 18), side + insetRight, (typing ? ui.dp(22) : insetBottom + ui.dp(railWide?24:104)) + dockSpace);
        int fade = (typing ? 0 : insetBottom + ui.dp(railWide?0:74)) + dockSpace + ui.dp(22);
        FrameLayout.LayoutParams f = (FrameLayout.LayoutParams) dockFade.getLayoutParams(); f.height = fade; f.bottomMargin = typing ? imeBottom : 0; dockFade.setLayoutParams(f);
        dockFade.setVisibility(fade > 0 ? View.VISIBLE : View.GONE);
    }
    /** Sends the message with its attachments to the engine shown over the box (the attachments wait in the store under a key). */
    private void sendWorkMessage(String threadId, EditText input) {
        if (store.scopedModelsUnavailable) { store.error("모델 설정을 저장하지 못했어요. 저장 공간을 확인한 뒤 앱을 다시 열어 주세요."); return; }
        ScopedModels selection = new ScopedModels(store, threadId);
        String text = input.getText().toString().strip();
        if (composerVideo) {
            if (!store.grok.connected()) { openSettings("grok"); return; }
            if (text.isBlank()) { input.setError("만들 장면이나 움직임을 적어 주세요."); return; }
            if (store.workFiles.length() > 0) { store.error("영상의 시작 이미지는 + → 동영상 생성에서 선택해 주세요. 대화 첨부는 빼고 보내 주세요."); return; }
            try { GrokVideo.prompt(text); if (!composerVideoSource.isBlank() && Gallery.find(store.gallery(), composerVideoSource) == null) throw new IllegalStateException("선택한 이미지가 없어요. + → 동영상 생성에서 다시 선택해 주세요."); }
            catch (Exception e) { input.setError(e.getMessage()); return; }
            send(new Intent(this, AssistantService.class).setAction(AssistantService.VIDEO_START).putExtra("image", composerVideoSource).putExtra("prompt", text).putExtra("duration", store.settings.getInt("videoDuration", 8)).putExtra("resolution", store.settings.getString("videoResolution", "1080p")).putExtra("aspect", store.settings.getString("videoAspect", "16:9")).putExtra("thread", threadId).putExtra("work", true));
            input.setText(""); return;
        }
        if (text.isEmpty() && (store.workFiles.length() == 0 || !composerImageAspect.isEmpty())) { input.setError(composerImageAspect.isEmpty() ? "맡길 일을 적어 주세요." : "그릴 내용을 대화 입력창에 적어 주세요."); return; }
        JSONObject picture = null, thread = WorkBook.find(store.works(), threadId);
        if (!composerImageAspect.isEmpty()) {
            if (!store.codexImages.connected() && (!store.openai.connected() || store.openai.usesPlan())) { store.error("이미지용 Codex 연결이 필요해요. 설정 → ChatGPT에서 연결해 주세요."); openSettings("chatgpt"); return; }
            try { picture = Drawing.composerArguments(text, thread == null ? null : thread.optJSONArray("messages"), composerImageAspect, composerImageEdit, store.codexImages.connected()); }
            catch (Exception e) { input.setError(e.getMessage()); return; }
        }
        if (picture == null && workModels().readyEngine().isEmpty()) {
            try {
                if (!store.codexImages.connected() || Drawing.chatArguments(text, thread == null ? null : thread.optJSONArray("messages"), store.workFiles, true) == null) {
                    store.error("작업창은 ChatGPT, Claude, Gemini, Grok, NVIDIA NIM 중 하나의 연결이 필요해요."); openSettings(""); return;
                }
            } catch (Exception e) { input.setError(e.getMessage()); return; }
        }
        if (!composerCreatesMedia() && dev.jun.gyeot.core.WorkQueue.size(thread) >= dev.jun.gyeot.core.WorkQueue.LIMIT) {
            input.setError("대기 지시가 8개예요. 일부가 끝난 뒤 보내 주세요."); return;
        }
        if (!composerCreatesMedia() && thread != null && (WorkBook.RUNNING.equals(thread.optString("state")) || dev.jun.gyeot.core.WorkQueue.size(thread) > 0)) {
            try {
                // Persist admission before clearing the composer. A full queue or disk error preserves the draft and attachments.
                store.changeWorks(list -> { dev.jun.gyeot.core.WorkQueue.add(WorkBook.find(list, threadId), text, store.workFiles, selection.readyEngine(), selection.model(selection.engine()), selection.effectiveEffort(selection.engine(), selection.model(selection.engine())), selection.snapshot(), System.currentTimeMillis()); return null; });
                input.setText(""); store.workFiles = new JSONArray(); refreshFiles(); updateComposer();
                store.status("추가 지시를 저장했어요. 같은 AI의 작업은 현재 처리 뒤 다음 단계에 반영해요.");
            } catch (Exception e) { input.setError(e.getMessage() == null ? "지시를 보관하지 못했어요. 입력은 남겨 두었어요." : e.getMessage()); }
            return;
        }
        Intent intent = new Intent(this, AssistantService.class).setAction(AssistantService.WORK_SEND).putExtra("thread", threadId).putExtra("text", text).putExtra("engine", selection.readyEngine()).putExtra("modelSelection", selection.snapshot().toString());
        if (picture != null) {
            store.settings.edit().putBoolean("workImages", true).apply(); intent.putExtra("picture", picture.toString()).putExtra("engine", WorkBook.CHATGPT);
        }
        input.setText("");
        if (store.workFiles.length() > 0) { String key = UUID.randomUUID().toString(); store.workOutbox.put(key, store.workFiles); intent.putExtra("files", key); store.workFiles = new JSONArray(); }
        composerImageAspect = ""; composerImageEdit = false; refreshFiles(); updateComposer(); send(intent);
        store.status(picture != null ? "이미지를 요청했어요 · 대화창에서 진행 상태를 확인하세요" : workEngineName() + "에게 맡겼어요 · 다른 앱을 써도 계속 일해요");
    }
    private void requireOpenFile(android.net.Uri uri)throws java.io.IOException {
        // Private browser artifacts are already owned app assets; the provider validates their IDs.
        if(uri!=null&&"content".equals(uri.getScheme())&&BrowserArtifactFiles.AUTHORITY.equals(uri.getAuthority())&&uri.getQuery()==null&&uri.getFragment()==null&&uri.getPathSegments().size()==1&&BrowserArtifactFiles.uri(uri.getLastPathSegment()).equals(uri))return;
        SharedOutputFolder.requireContentAccess(this,uri);
    }
    private void openFile(android.net.Uri uri, String name) {
        try { requireOpenFile(uri);startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, getContentResolver().getType(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch (Exception e) { store.error("‘" + name + "’을(를) 열지 못했어요. 파일 내용 열기는 연결된 들퍼레 폴더 안에서만 허용해요."); }
    }
    private void shareFile(android.net.Uri uri, String name) {
        try { requireOpenFile(uri);startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(getContentResolver().getType(uri)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name)); }
        catch (Exception e) { store.error("공유하지 못했어요."); }
    }
    private static String size(long bytes) { return bytes < 1000 ? bytes + "B" : bytes < 1_000_000 ? (bytes / 1000) + "KB" : String.format(java.util.Locale.ROOT, "%.1fMB", bytes / 1_000_000.0); }
    /**
     * 설정 → 사용량 · 남은 한도 (UsageUi): drawn from what the phone kept. Opening it also asks the Codex sign-in's and
     * Grok's own usage pages (UsageUi.check, at most every five minutes), and its 지금 다시 확인 asks them at once.
     */
    private void pageUsage() {
        usageBox = ui.column(); lastUsage = ""; add(usageBox, 0); refreshUsage();
        UsageUi.check(store, false, store::changed);
        add(ui.footer("질문과 답의 내용은 남기지 않아요. 시각·화면·회사·모델·연결 지문·토큰 수만 이 기기에 90일 동안 보관해요."), 0);
        add(ui.details("어떻게 세나요?", "남은 한도는 실제 답에 붙어 온 응답 헤더로 읽어요. ChatGPT의 5시간·주간 한도는 ‘이미지용 Codex’ 로그인으로, Grok의 주간 한도는 Grok 로그인으로 이 화면을 열 때 물어봐요(5분에 한 번까지). "
            + "두 주소는 Codex와 Grok 프로그램이 쓰는 공개되지 않은 주소라 바뀌면 ‘새로 확인하지 못했어요’로 보여 줘요. Gemini API 키와 NVIDIA NIM은 한도가 오지 않으면 ‘모름’이에요."), 16);
    }
    /**
     * Read off the main thread (the ledger, the connections' identities), then drawn if the page still shows and nothing
     * newer came meanwhile; looked at again each minute while it shows (a window resets, its age and the seven days move on).
     */
    private void refreshUsage() {
        if (usageBox == null) return;
        long now = System.currentTimeMillis();
        if (!ticker.hasCallbacks(usageMinute)) ticker.postDelayed(usageMinute, 60_000 - now % 60_000 + 50);
        UsageUi page = new UsageUi(ui, store); String seen = page.fingerprint(now);
        if (seen.equals(lastUsage)) return; lastUsage = seen;
        LinearLayout box = usageBox;
        UsageUi.read(store, now, java.time.ZoneId.systemDefault(), shown -> box.post(() -> {
            if (usageBox != box || !seen.equals(lastUsage)) return;
            // Each account's row opens the page it is connected on; 지금 다시 확인 asks the usage pages at once.
            box.removeAllViews(); page.fill(box, shown, this::openSettings, () -> UsageUi.check(store, true, store::changed));
        }));
    }
    /** The usage page's minute; refreshUsage posts it again while the page shows. */
    private final Runnable usageMinute = this::refreshUsage;
    /** NVIDIA NIM: one API key, typed only in a sheet, for Nemotron Ultra; shortcuts to where it works and its checks. */
    private void pageNim() {
        nimBox = ui.column(); add(nimBox, 0); lastNim = ""; refreshNim();
        add(ui.footer("메인 대화·작업창·녹음 요약·브라우저·두뇌에서 고를 수 있어요. 텍스트 대화와 GitHub 작업을 하고, 웹 검색·코드 실행·그림·영상 만들기·PDF 원본 보내기는 하지 않아요."), 0);
        add(ui.footer("무료 체험 API라 계정마다 요청 속도와 할당량이 있어요. 생각 깊이는 NVIDIA 기본값을 쓰고, 키는 이 기기에만 암호화해 두고 NVIDIA에만 보내요."), 16);
    }
    private void refreshNim() {
        if (nimBox == null) return;
        boolean connected = store.nim.connected();
        String fingerprint = connected + "|" + store.nim.busy + "|" + store.nim.model() + "|" + store.nim.notice;
        if (fingerprint.equals(lastNim)) return; lastNim = fingerprint; nimBox.removeAllViews();
        String model = NimProtocol.label(store.nim.model());
        nimBox.addView(ui.statusCard(R.drawable.ic_sparkle, Ui.GREEN, connected ? "연결됨" : "연결 안 됨", connected ? model + " · 무료 체험 API" : "NVIDIA API 키로 " + model + "를 써요", null));
        if (!store.nim.notice.isBlank()) { TextView notice = ui.label(store.nim.notice, 13, TEXT); notice.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0); nimBox.addView(notice); }
        // While a key is saved or checked, what would start another waits, dimmed.
        List<View> waits = new ArrayList<>();
        if (!connected) {
            Button put = ui.button("API 키 넣기", true, this::nimKeySheet); waits.add(put);
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2); first.topMargin = ui.dp(16); nimBox.addView(put, first);
            TextView get = ui.quietRow("키가 없나요? NVIDIA에서 무료로 받아요 ›", () -> open("https://build.nvidia.com/settings/api-keys")); get.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), 0);
            nimBox.addView(get);
        } else {
            nimBox.addView(ui.sectionHeader("바로 가기"));
            LinearLayout go = ui.group();
            ui.addRow(go, ui.listRow(0, 0, model + "로 작업창 열기", null, ui.chevron(), () -> { chooseEngine(WorkBook.NIM); showTab(3); }), false);
            if ("nim".equals(store.brain())) {
                LinearLayout brain = ui.listRow(0, 0, "음성 비서의 두뇌", "지금 두뇌로 쓰는 중", null, null); brain.setContentDescription("음성 비서의 두뇌, 지금 두뇌로 쓰는 중"); brain.setScreenReaderFocusable(true);
                ui.addRow(go, brain, false);
            } else {
                String now = "none".equals(store.brain()) ? "없음" : store.brainName() + (store.thinkWanted() ? "" : " · 연결 안 됨");
                ui.addRow(go, ui.listRow(0, 0, "음성 비서의 두뇌로 쓰기", "지금 두뇌: " + now, ui.chevron(), () -> {
                    store.settings.edit().putString("brain", "nim").apply(); store.changed();
                    store.status(model + "를 음성 비서의 두뇌로 골랐어요 · 음성 대화 중이면 ‘지금 적용’을 눌러 주세요"); showTab(2);
                }), false);
            }
            nimBox.addView(go);
            nimBox.addView(ui.sectionHeader("연결 관리"));
            LinearLayout manage = ui.group();
            LinearLayout probe = ui.listRow(0, 0, "연결 확인", model + "에 짧게 물어봐요", null, store.nim::testConnection);
            LinearLayout change = ui.listRow(0, 0, "키 바꾸기", "새 키를 확인한 뒤 바꿔요", ui.chevron(), this::nimKeySheet);
            ui.addRow(manage, probe, false); ui.addRow(manage, change, false); waits.add(probe); waits.add(change);
            nimBox.addView(manage);
            LinearLayout off = ui.group();
            ui.addRow(off, ui.dangerRow("연결 해제", "이 기기의 API 키를 지워요", () -> confirm("이 기기의 NVIDIA API 키를 지울까요?", "해제", store.nim::disconnect)), false);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(16); nimBox.addView(off, gap);
        }
        if (store.nim.busy) for (View view : waits) { view.setEnabled(false); view.setAlpha(0.5f); }
    }
    /** The NVIDIA key, typed only in this sheet: saved off the main thread (NimAccount), its progress in the page's notice. Closing it saves nothing. */
    private void nimKeySheet() {
        Ui.Sheet sheet = ui.secretSheet(store.nim.connected() ? "NVIDIA API 키 바꾸기" : "NVIDIA API 키", "build.nvidia.com에서 무료로 받을 수 있어요", "NVIDIA API 키", 4096, "저장", (s, field) -> {
            if (store.nim.busy) return;
            try { store.nim.saveKey(field.getText().toString()); }
            catch (Exception e) { field.setError(e.getMessage() == null ? "키를 저장하지 못했어요." : e.getMessage()); return; }
            s.dismiss();
        });
        if (store.nim.busy) { sheet.primary.setEnabled(false); sheet.primary.setAlpha(0.5f); }
        sheet.body.addView(ui.actions(ui.chip("NVIDIA에서 키 받기", () -> open("https://build.nvidia.com/settings/api-keys"))));
        sheet.show();
    }
    /** Grok: a subscription sign-in by device code, shortcuts to what it does and its management. */
    private void pageGrok() {
        grokBox = ui.column(); add(grokBox, 0); lastGrok = ""; refreshGrok();
        add(ui.footer("메인 대화·작업창의 Grok 4.7 대화와 + 메뉴·갤러리·음성의 영상 만들기에 써요. GPT로 만든 그림도 영상의 첫 장면이 될 수 있어요."), 0);
        add(ui.footer("되는 기능과 한도는 연결한 Grok 계정에 따라 달라요. 로그인 정보는 이 기기에만 암호화해 둬요."), 16);
    }
    private void refreshGrok() {
        if (grokBox == null) return;
        String fingerprint = store.grok.connected() + "\u0000" + store.grok.signingIn + "\u0000" + store.grok.notice + "\u0000" + store.grok.userCode;
        if (fingerprint.equals(lastGrok)) return; lastGrok = fingerprint; grokBox.removeAllViews();
        boolean connected = store.grok.connected(), signingIn = store.grok.signingIn;
        // A sign-in under way says so, also when it replaces a connected account; the connected rows come back once it ends.
        grokBox.addView(ui.statusCard(R.drawable.ic_sparkle, Ui.GRAY, signingIn ? "로그인하는 중…" : connected ? "연결됨" : "연결 안 됨",
            signingIn ? "아래 코드로 연결을 승인해 주세요" : connected ? "구독 계정 · Grok 4.7" : "Grok 구독 계정으로 로그인해요", null));
        if ((signingIn || !connected) && !store.grok.notice.isBlank()) { TextView notice = ui.label(store.grok.notice, 13, TEXT); notice.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0); grokBox.addView(notice); }
        if (signingIn) {
            if (!store.grok.userCode.isBlank()) {
                TextView code = ui.text(store.grok.userCode, 26, 700, ACCENT); code.setTextIsSelectable(true); code.setGravity(android.view.Gravity.CENTER); code.setPadding(0, ui.dp(18), 0, ui.dp(18)); grokBox.addView(code);
                grokBox.addView(ui.actions(ui.chip("코드 복사", () -> copy(store.grok.userCode)), ui.chip("로그인 페이지 열기", store.grok::openVerification)));
            }
            grokBox.addView(ui.actions(ui.chip("로그인 취소", store.grok::cancelSignIn)));
        } else if (!connected) {
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2); first.topMargin = ui.dp(16);
            grokBox.addView(ui.button("Grok 구독 계정으로 로그인", true, store.grok::signIn), first);
            TextView how = ui.label("누르면 코드가 나와요. 브라우저에서 로그인하고 그 코드로 연결을 승인해 주세요.", 12, MUTED); how.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0); grokBox.addView(how);
        } else {
            grokBox.addView(ui.sectionHeader("바로 가기"));
            LinearLayout go = ui.group();
            ui.addRow(go, ui.listRow(0, 0, "Grok 4.7로 작업창 열기", null, ui.chevron(), () -> { chooseEngine(WorkBook.GROK); showTab(3); }), false);
            ui.addRow(go, ui.listRow(0, 0, "갤러리에서 영상 만들기", "그림을 영상의 첫 장면으로 써요", ui.chevron(), () -> showTab(4)), false);
            ui.addRow(go, ui.listRow(0, 0, "남은 한도 보기", "주간 사용량", ui.chevron(), () -> openSettings("usage")), false);
            grokBox.addView(go);
            grokBox.addView(ui.sectionHeader("연결 관리"));
            LinearLayout manage = ui.group();
            ui.addRow(manage, ui.listRow(0, 0, "Grok 다시 로그인", "계정을 바꾸거나 다시 승인해요", null, store.grok::signIn), false);
            grokBox.addView(manage);
            LinearLayout off = ui.group();
            ui.addRow(off, ui.dangerRow("연결 해제", "이 기기의 Grok 로그인을 지워요", () -> confirm("이 기기의 Grok 연결을 해제할까요?", "해제", store.grok::disconnect)), false);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(16); grokBox.addView(off, gap);
        }
    }
    /**
     * 설정's home: the search on top, a 「확인이 필요해요」 group only while something blocks the voice
     * assistant or 폰 조작, then a group per area whose rows say their state. While something is typed, the matches
     * (core/SettingsIndex) show in the groups' place.
     */
    private void settingsList() {
        titleView.setText("설정");
        add(searchBox("설정 검색 · 예: 목소리, API 키, GitHub", settingsQuery, q -> { settingsQuery = q; showResults(); }), 8);
        settingsResults = ui.column(); add(settingsResults, 0);
        LinearLayout sections = settingsSections = ui.column(); add(sections, 0);
        boolean key = store.secrets.hasKey(), mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean phoneOn = store.phoneControl(), phoneConnected = PhoneService.connected();
        if (!key || !mic || phoneOn && !phoneConnected) {
            ui.add(sections, ui.sectionHeader("확인이 필요해요"), 0);
            LinearLayout fix = ui.group();
            if (!key) ui.addRow(fix, ui.listRow(R.drawable.ic_key, Ui.ORANGE, "Gemini API 키 넣기", "음성 비서를 쓰려면 필요해요", ui.chevron(), () -> openSettings("key")), true);
            if (!mic) ui.addRow(fix, ui.listRow(R.drawable.ic_mic, Ui.ORANGE, "마이크 허용하기", "말로 대화하려면 필요해요", ui.chevron(), this::askMicFromSettings), true);
            if (phoneOn && !phoneConnected) ui.addRow(fix, ui.listRow(R.drawable.ic_phone, Ui.RED, "폰 조작 다시 연결하기", "접근성 연결이 끊겼어요", ui.chevron(), () -> openSettings("phone")), true);
            ui.add(sections, fix, 0);
        }
        ui.add(sections, ui.sectionHeader("AI 연결"), 0);
        LinearLayout connect = ui.group();
        settingsRow(connect, "key", "Gemini", key ? "연결됨 · API 키" : "연결 안 됨 · 음성 비서에 필요해요", null);
        JSONObject gpt = store.openai.describe(); String gptMode = gpt.optString("mode");
        settingsRow(connect, "chatgpt", "ChatGPT", gptMode.equals("key") ? "연결됨 · API 키" : !gptMode.equals("chatgpt") ? "연결 안 됨" : gpt.optBoolean("plan") ? "연결됨 · 구독 로그인" : "확인 필요 · 플랜 사용 허용 안 됨", null);
        settingsRow(connect, "claude", "Claude", store.claude.connected() ? "연결됨 · " + modelName(store.claude.models(), store.claude.model()) : "연결 안 됨", null);
        settingsRow(connect, "grok", "Grok", store.grok.connected() ? "연결됨 · 구독 로그인" : "연결 안 됨", null);
        settingsRow(connect, "nim", "NVIDIA NIM", store.nim.connected() ? "연결됨 · " + NimProtocol.label(store.nim.model()) : "연결 안 됨", null);
        ui.add(sections, connect, 0);
        ui.add(sections, ui.sectionHeader("AI 활용"), 0);
        LinearLayout use = ui.group();
        settingsRow(use, "ai", "AI 역할", "none".equals(store.brain()) ? "두뇌 없음" : "두뇌 " + store.brainName() + (store.thinkWanted() ? "" : " · 연결 안 됨"), null);
        List<String> linked = new ArrayList<>();
        if (store.githubConnected()) linked.add("GitHub");
        if (store.drive.connected()) linked.add("Google Drive");
        if (store.archiveConnected()) linked.add("마이아카이브");
        JSONArray servers = store.connectors();
        for (int i = 0; i < servers.length(); i++) { JSONObject server = servers.optJSONObject(i); if (server != null) linked.add(server.optString("name").isBlank() ? server.optString("host", "MCP") : server.optString("name")); }
        if (NaverPlacesClient.connected(store)) linked.add("네이버 길안내");
        settingsRow(use, "connectors", "커넥터", linked.isEmpty() ? "연결 안 됨 · GitHub·Drive·MCP 등" : linked.size() + "개 연결됨 · " + String.join(", ", linked), null);
        settingsRow(use, "calendar", "캘린더", CalendarSettingsUi.summary(this, store), null);
        LinearLayout usageRow = ui.listRow(R.drawable.ic_history, Ui.TEAL, "사용량 · 남은 한도", UsageUi.ABOUT, ui.chevron(), () -> openSettings("usage"));
        ui.addRow(use, usageRow, true);
        // The last seven days are summed off the main thread, then shown in the row.
        UsageUi.overview(store, System.currentTimeMillis(), line -> usageRow.post(() -> {
            TextView summary = ((Ui.ListRow) usageRow).parts.subtitle();
            if (summary != null) summary.setText(line);
            usageRow.setContentDescription("사용량 · 남은 한도, " + line);
        }));
        ui.add(sections, use, 0);
        ui.add(sections, ui.sectionHeader("음성 비서"), 0);
        LinearLayout voice = ui.group();
        RealtimeVoice.Kind realtime = store.assistantRealtime();
        settingsRow(voice, "voice", "목소리와 대화", (store.voiceActive ? "대화 중 · " : "") + (realtime != null ? AppStore.realtimeLabel(realtime) + " · " + RealtimeVoice.shown(realtime, store.pickedVoice(realtime)) : store.voice().isEmpty() ? "기본 목소리" : store.voice()) + (store.searchWanted() ? " · 검색 켜짐" : " · 검색 꺼짐"), null);
        List<String> ways = new ArrayList<>();
        if (store.voiceOnOpen()) ways.add("앱 열면 바로 대화");
        if (store.edgeButton()) ways.add("가장자리 버튼");
        if (assistantRoleHeld()) ways.add("홈 버튼");
        if (store.settings.getBoolean(WakeWordFiles.ENABLED, false)) ways.add("차에서 호출어");
        settingsRow(voice, "quick", "바로 부르기", ways.isEmpty() ? "앱 아이콘·알림창 타일" : String.join(" · ", ways), null);
        settingsRow(voice, "background", "다른 앱 쓰는 동안", !store.bubble() ? "작은 음성 버튼 꺼짐" : Settings.canDrawOverlays(this) ? "작은 음성 버튼 켜짐" : "작은 음성 버튼 켜짐 · 권한 필요", null);
        ui.add(sections, voice, 0);
        ui.add(sections, ui.sectionHeader("휴대폰"), 0);
        LinearLayout phone = ui.group();
        LinearLayout phoneTrailing = ui.row(); if (phoneOn && !phoneConnected) phoneTrailing.addView(ui.badge("확인 필요", Ui.RED)); phoneTrailing.addView(ui.chevron());
        settingsRow(phone, "phone", "폰 조작", phoneOn ? (phoneConnected ? "준비됨" : "접근성 연결이 끊겼어요") : phoneConnected ? "꺼짐 · 접근성은 연결됨" : "꺼짐", phoneTrailing);
        settingsRow(phone, "look", "화면 보기", !store.screenView() ? "꺼짐" : phoneConnected ? "켜짐 · 원할 때만 한 장씩" : "켜짐 · 접근성이 필요해요", null);
        ui.add(sections, phone, 0);
        ui.add(sections, ui.sectionHeader("나에게 맞추기"), 0);
        LinearLayout personal = ui.group();
        String persona = store.persona().strip().replaceAll("\\s+", " ");
        settingsRow(personal, "persona", "나에 대해", persona.isEmpty() ? "비어 있어요 · 호칭과 답변 스타일" : persona, null);
        String routines = dev.jun.gyeot.core.Routines.summary(store.routines());
        settingsRow(personal, "routines", "한마디 루틴", routines.isEmpty() ? "아직 없어요 · 한마디로 여러 일을 맡겨요" : routines, null);
        int remembered = store.memories().length();
        settingsRow(personal, "memory", "기억", !store.memoryOn() ? "꺼짐" : remembered == 0 ? "켜짐 · 아직 기억한 게 없어요" : remembered + "개 기억 중" + (store.autoMemory() ? " · 자동으로 배우는 중" : ""), null);
        ui.add(sections, personal, 0);
        ui.add(sections, ui.sectionHeader("앱"), 0);
        LinearLayout app = ui.group();
        dev.jun.gyeot.core.UpdateCheck.Update update = store.updater.available;
        LinearLayout updateTrailing = ui.row(); if (update != null) updateTrailing.addView(ui.badge("새 버전", Ui.RED)); updateTrailing.addView(ui.chevron());
        settingsRow(app, "backup", "백업 · 복원", store.drive.connected() ? "Google Drive에 앱 백업 · 기억은 파일로" : "기억은 파일로 · Drive 백업은 연결 후", null);
        settingsRow(app, "update", "업데이트", update == null ? "지금 버전 " + versionName() : "새 버전 " + update.version + " · 지금 " + versionName(), updateTrailing);
        settingsRow(app, "about", "정보", "개인용 시제품 · 진단과 도움말", null);
        ui.add(sections, app, 0);
        ui.add(sections, ui.footer("API 키·로그인·연결 정보는 이 기기 안에만 암호화해 보관해요."), 0);
        showResults();
    }
    /** One row of the home: the page's icon and name, its state under it, and the way in (a chevron unless trailing is given). */
    private void settingsRow(LinearLayout group, String page, String title, String state, View trailing) {
        int[] icon = settingsIcon(page);
        ui.addRow(group, ui.listRow(icon[0], icon[1], title, state, trailing == null ? ui.chevron() : trailing, () -> openSettings(page)), true);
    }
    /** A page's icon and hue as its home row shows them, for the home and the search's results; 정보 and the guides show ic_info. */
    private static int[] settingsIcon(String page) {
        return switch (page) {
            case "key" -> new int[]{R.drawable.ic_sparkle, Ui.BLUE};
            case "chatgpt" -> new int[]{R.drawable.ic_sparkle, Ui.MINT};
            case "claude" -> new int[]{R.drawable.ic_sparkle, Ui.ORANGE};
            case "grok" -> new int[]{R.drawable.ic_sparkle, Ui.GRAY};
            case "nim" -> new int[]{R.drawable.ic_sparkle, Ui.GREEN};
            case "ai" -> new int[]{R.drawable.ic_tune, Ui.INDIGO};
            case "connectors" -> new int[]{R.drawable.ic_plug, Ui.TEAL};
            case "github" -> new int[]{R.drawable.ic_repo, Ui.INDIGO};
            case "drive" -> new int[]{R.drawable.ic_folder, Ui.BLUE};
            case "calendar" -> new int[]{R.drawable.ic_calendar, Ui.BLUE};
            case "usage" -> new int[]{R.drawable.ic_history, Ui.TEAL};
            case "voice" -> new int[]{R.drawable.ic_wave, Ui.PURPLE};
            case "quick" -> new int[]{R.drawable.ic_bolt, Ui.BLUE};
            case "wake" -> new int[]{R.drawable.ic_mic, Ui.ORANGE};
            case "background" -> new int[]{R.drawable.ic_layers, Ui.TEAL};
            case "phone" -> new int[]{R.drawable.ic_phone, Ui.GREEN};
            case "look" -> new int[]{R.drawable.ic_eye, Ui.INDIGO};
            case "persona" -> new int[]{R.drawable.ic_person, Ui.PINK};
            case "routines" -> new int[]{R.drawable.ic_bolt, Ui.MINT};
            case "memory", "memories" -> new int[]{R.drawable.ic_bulb, Ui.ORANGE};
            case "update" -> new int[]{R.drawable.ic_download, Ui.BLUE};
            case "backup" -> new int[]{R.drawable.ic_folder, Ui.GREEN};
            default -> new int[]{R.drawable.ic_info, Ui.GRAY};
        };
    }
    /** What settingsQuery finds, in the groups' place (at most 12, core/SettingsIndex); the groups again once it is blank. */
    private void showResults() {
        if (settingsResults == null || settingsSections == null) return;
        String typed = settingsQuery.strip();
        boolean searching = !dev.jun.gyeot.core.SettingsIndex.normalize(typed).isEmpty();
        settingsResults.removeAllViews(); settingsResults.setVisibility(searching ? View.VISIBLE : View.GONE);
        settingsSections.setVisibility(searching ? View.GONE : View.VISIBLE);
        if (!searching) return;
        ui.add(settingsResults, ui.sectionHeader("검색 결과"), 0);
        List<dev.jun.gyeot.core.SettingsIndex.Result> found = dev.jun.gyeot.core.SettingsIndex.find(typed);
        if (found.isEmpty()) { ui.add(settingsResults, ui.footer("‘" + typed + "’에 맞는 설정이 없어요. 예: 목소리, API 키, GitHub"), 0); return; }
        LinearLayout rows = ui.group();
        for (dev.jun.gyeot.core.SettingsIndex.Result result : found) {
            dev.jun.gyeot.core.SettingsIndex.Entry entry = result.entry(); int[] icon = settingsIcon(entry.page());
            // A guide says where the thing is, outside 설정; it opens nothing.
            boolean guide = entry.kind() == dev.jun.gyeot.core.SettingsIndex.Kind.GUIDE;
            LinearLayout row = ui.listRow(icon[0], icon[1], entry.title(), result.subtitle(), guide ? null : ui.chevron(), guide ? null : () -> openSettings(entry.page()));
            if (guide) { row.setContentDescription(entry.title() + ", " + result.subtitle()); row.setScreenReaderFocusable(true); }
            ui.addRow(rows, row, true);
        }
        ui.add(settingsResults, rows, 0);
    }
    /** 마이크 허용하기: Android's own question, or this app's Android settings once Android no longer asks. */
    private void askMicFromSettings() {
        if (store.settings.getBoolean("settingsMicAsked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) { openAppSettings(); return; }
        store.settings.edit().putBoolean("settingsMicAsked", true).apply();
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, SETTINGS_MIC);
    }
    /** Gemini: the voice assistant's API key, typed only in a sheet, its checks, and an optional second key below. */
    private void pageKey() {
        boolean saved = store.secrets.hasKey();
        add(ui.statusCard(R.drawable.ic_sparkle, Ui.BLUE, saved ? "연결됨" : "연결 안 됨", !saved ? "음성 비서를 쓰려면 API 키가 필요해요"
            : store.gemini.ownKey() ? "API 키 2개 · 음성 비서용과 다른 기능용" : "API 키 · 음성 비서·메인 대화·작업창에서 써요", null), 0);
        add(ui.sectionHeader("Gemini API 키"), 0);
        if (!saved) {
            add(ui.button("API 키 넣기", true, () -> geminiKeySheet(false)), 0);
            TextView studio = ui.quietRow("키가 없나요? AI Studio에서 무료로 받아요 ›", () -> open("https://aistudio.google.com/api-keys")); studio.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), 0);
            add(studio, 0);
        } else {
            // A check's lines and its fix sit right under the group; the probe stays hidden until a check runs.
            TextView probe = ui.label("", 13, TEXT); probe.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0); probe.setVisibility(View.GONE);
            LinearLayout probeFix = ui.column(); probeFix.setPadding(ui.dp(16), 0, ui.dp(16), 0);
            LinearLayout key = ui.group();
            LinearLayout check = ui.listRow(0, 0, "연결 확인", "음성·통역·받아쓰기 모델과 검색을 차례로 확인해요", ui.chevron(), () -> geminiCheck(probe, probeFix));
            LinearLayout voice = ui.listRow(0, 0, "음성 모델", AppStore.modelLabel(store.liveModel()) + " · 목소리와 대화에서 바꿔요", ui.chevron(), () -> openSettings("voice"));
            // Two lines, so the list of checks and a long model name stay whole at 360dp.
            for (LinearLayout row : List.of(check, voice)) { TextView sub = ((Ui.ListRow) row).parts.subtitle(); sub.setSingleLine(false); sub.setMaxLines(2); }
            ui.addRow(key, check, false); ui.addRow(key, voice, false);
            ui.addRow(key, ui.listRow(0, 0, "모델별 한도 보기", "aistudio.google.com", ui.chevron(), () -> open("https://aistudio.google.com/rate-limit")), false);
            ui.addRow(key, ui.listRow(0, 0, "키 바꾸기", "저장하면 다음 음성 대화부터 새 키를 써요", ui.chevron(), () -> geminiKeySheet(true)), false);
            add(key, 0); add(probe, 0); add(probeFix, 16);
            LinearLayout off = ui.group();
            ui.addRow(off, ui.dangerRow("연결 해제", "음성 대화를 끝내고 이 기기의 키를 지워요", () -> confirm("진행 중인 음성 대화를 끝내고 이 기기의 Gemini API 키를 지울까요?", "해제", () -> {
                send(AssistantService.STOP, null, null);
                try { store.secrets.put(""); store.status("Gemini 연결을 해제했어요"); showTab(2); } catch (Exception e) { store.error("키를 지우지 못했어요."); }
            })), false);
            add(off, 0);
        }
        add(ui.footer("음성과 요청은 Google API로 보내요. 키는 이 기기에만 암호화해 두고 앱 백업에서 빼요."), 0);
        add(ui.sectionHeader("작업창·메인 대화용 키 (선택)"), 0);
        geminiWorkBox = ui.column(); add(geminiWorkBox, 0); lastGeminiWork = ""; refreshGeminiWork();
        add(ui.footer("무료 한도는 Google Cloud 프로젝트마다 따로예요. 다른 프로젝트의 키를 넣으면 음성 대화와 한도를 나눠 쓰지 않고, 같은 프로젝트의 키는 한도를 함께 써요."), 0);
        add(ui.details("이 키를 쓰는 곳", "메인 대화·작업창·입력칸 받아쓰기·파일 AI·에이전트 팀·Gemini 분석·브라우저의 Gemini가 이 키를 먼저 써요. 음성 대화·Gemini Flash 두뇌·Gemini 디시전은 위의 키를 써요."), 16);
    }
    /** The voice assistant's key, typed only in this sheet: 넣기 or 바꾸기. Closing it saves nothing. */
    private void geminiKeySheet(boolean change) {
        Ui.Sheet sheet = ui.secretSheet(change ? "Gemini API 키 바꾸기" : "Gemini API 키", change ? "저장하면 다음 음성 대화부터 새 키를 써요" : "Google AI Studio에서 무료로 받을 수 있어요",
            "Gemini API 키 붙여 넣기", 200, change ? "바꾸기" : "저장", (s, field) -> {
                String value = field.getText().toString();
                if (value.isBlank()) { field.setError("새 키를 입력해 주세요"); return; }
                try { store.secrets.put(value.trim()); } catch (Exception e) { field.setError(e.getMessage() == null ? "키 형식을 확인해 주세요." : e.getMessage()); return; }
                s.dismiss(); store.error = ""; store.status("API 키를 저장했어요 · 연결 확인을 눌러 점검해 보세요"); showTab(2);
            });
        sheet.body.addView(ui.label("키는 이 기기에만 암호화해 둬요.", 12, MUTED));
        sheet.body.addView(ui.actions(ui.chip("AI Studio에서 키 받기", () -> open("https://aistudio.google.com/api-keys"))));
        sheet.show();
    }
    /** Rebuilds only the 작업창·메인 대화 key row and its notice when their state changes. */
    private void refreshGeminiWork() {
        if (geminiWorkBox == null) return;
        boolean own = store.gemini.ownKey();
        String fingerprint = own + "\u0000" + store.secrets.hasKey() + "\u0000" + store.gemini.notice;
        if (fingerprint.equals(lastGeminiWork)) return;
        // The status card above says how many keys there are: a saved or removed second key redraws the page.
        if (!lastGeminiWork.isEmpty() && !lastGeminiWork.startsWith(own + "\u0000") && tab == 2 && "key".equals(settingsPage)) { showTab(2); return; }
        lastGeminiWork = fingerprint; geminiWorkBox.removeAllViews();
        LinearLayout group = ui.group();
        ui.addRow(group, ui.listRow(0, 0, "따로 쓰는 키", own ? "저장됨 · " + store.gemini.label() : store.secrets.hasKey() ? "비워 둠 · 위의 키를 같이 써요" : "비워 둠 · 키가 없어요",
            ui.chevron(), this::geminiWorkKeySheet), false);
        geminiWorkBox.addView(group);
        if (!store.gemini.notice.isBlank()) {
            TextView notice = ui.label(store.gemini.notice, 13, store.gemini.notice.endsWith("…") ? TEXT : ERROR); notice.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0);
            geminiWorkBox.addView(notice);
        }
    }
    /** The second key, for 작업창 and 메인 대화: checked by reading its model list, then kept; without it they use the key above. */
    private void geminiWorkKeySheet() {
        boolean own = store.gemini.ownKey();
        Ui.Sheet sheet = ui.secretSheet("작업창·메인 대화용 Gemini 키", "비워 두면 위의 키를 같이 써요", own ? "다른 키로 바꾸기" : "Gemini API 키 붙여 넣기", 200, "저장", (s, field) -> {
            String value = field.getText().toString().strip();
            if (value.isEmpty()) { field.setError("키를 입력해 주세요"); return; }
            try { store.gemini.saveKey(value); } catch (Exception e) { field.setError(e.getMessage() == null ? "키를 저장하지 못했어요." : e.getMessage()); return; }
            s.dismiss();
        });
        if (own) {
            LinearLayout remove = ui.group();
            ui.addRow(remove, ui.dangerRow("따로 쓰는 키 지우기", "지우면 위의 키를 같이 써요", () -> {
                sheet.dismiss(); confirm("따로 쓰는 키를 지울까요? 지우면 위의 키를 같이 써요.", "지우기", () -> store.gemini.saveKey(""));
            }), false);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(6); sheet.body.addView(remove, gap);
        }
        sheet.show();
    }
    /** ChatGPT: a subscription sign-in or an OpenAI API key, the voice assistant's model, and 이미지용 Codex. */
    private void pageChatgpt() {
        openAiBox = ui.column(); add(openAiBox, 0); lastOpenAi = ""; refreshOpenAi();
        add(ui.details("로그인 방식과 개인정보", "‘ChatGPT로 로그인’은 OpenAI가 오픈소스·개인 기기 앱용으로 제공하는 공식 방식이에요. ‘ChatGPT 플랜 사용’을 허용하면 API 키 없이 구독 사용량으로 동작하며, 계정·플랜에 따라 쓸 수 없을 수 있어요. 요청은 OpenAI에 저장하지 않도록(store=false) 보내요."
            + " API 키로 연결하면 사용량은 OpenAI Platform 계정으로 따로 과금돼요."), 16);
    }
    /** Claude: an Anthropic API key typed only in a sheet, the model and its effort, and the connection's checks. */
    private void pageClaude() {
        claudeBox = ui.column(); add(claudeBox, 0); lastClaude = ""; refreshClaude();
        add(ui.footer("키는 이 기기에만 암호화해 두고 api.anthropic.com에만 보내요. 쓴 만큼 Anthropic API 요금이 붙어요(웹 검색·코드 실행 포함)."), 0);
        add(ui.details("자세히", "작업창에서 만든 파일은 휴대폰으로 받은 뒤 Anthropic 저장소에서 지워요. 두뇌의 짧은 답과 입력할 글은 빠르게 낮은 생각 깊이로 보내요."), 16);
    }
    /** Rebuilds only the Claude connection block when its state changes. */
    private void refreshClaude() {
        if (claudeBox == null) return;
        String fingerprint = store.claude.connected() + "\u0000" + store.claude.notice + "\u0000" + store.claude.models() + "\u0000" + store.claude.model() + "\u0000" + store.claude.effort(store.claude.model());
        if (fingerprint.equals(lastClaude)) return;
        lastClaude = fingerprint; claudeBox.removeAllViews();
        boolean connected = store.claude.connected();
        claudeBox.addView(ui.statusCard(R.drawable.ic_sparkle, Ui.ORANGE, connected ? "연결됨" : "연결 안 됨", connected ? "API 키 · " + store.claude.label() : "Anthropic API 키로 연결해요", null));
        // The key's check runs after the sheet closes; its progress or problem shows here.
        String notice = store.claude.notice;
        if (!notice.isBlank()) { TextView line = ui.label(notice, 13, notice.endsWith("…") ? TEXT : ERROR); line.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0); claudeBox.addView(line); }
        if (!connected) {
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2); first.topMargin = ui.dp(16);
            claudeBox.addView(ui.button("API 키 넣기", true, this::claudeKeySheet), first);
            TextView console = ui.quietRow("키가 없나요? Claude Console에서 만들어요 ›", () -> open("https://platform.claude.com/settings/keys")); console.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), 0);
            claudeBox.addView(console);
            claudeBox.addView(ui.footer("메인 대화·작업창(코드 실행·파일 만들기 포함)·두뇌·분석·웹 검색에 쓸 수 있어요. 쓴 만큼 Anthropic API 요금이 붙어요."));
            return;
        }
        JSONArray models = store.claude.models();
        if (models.length() > 0) {
            claudeBox.addView(ui.sectionHeader("음성 비서가 쓸 모델"));
            LinearLayout choice = ui.group();
            String[] labels = new String[models.length()]; int selected = 0;
            for (int i = 0; i < labels.length; i++) { JSONObject model = models.optJSONObject(i); labels[i] = model.optString("name"); if (model.optString("id").equals(store.claude.model())) selected = i; }
            ui.addRow(choice, ui.choiceRow("모델", "Claude 모델", "두뇌·분석·검색에 써요", labels, selected,
                position -> { String id = models.optJSONObject(position).optString("id"); if (!id.equals(store.claude.model())) store.claude.model(id); }), false);
            String model = store.claude.model();
            Reasoning.Profile profile = store.claude.profile(model);
            if (!profile.efforts.isEmpty()) {
                String[] efforts = profile.values(); String effort = store.claude.effort(model); int chosen = 0;
                for (int i = 0; i < efforts.length; i++) if (efforts[i].equals(effort)) chosen = i;
                ui.addRow(choice, ui.choiceRow("생각 깊이", "생각 깊이", "모델마다 따로 기억해요 · 깊을수록 느리고 요금이 늘어요", profile.labels(), chosen,
                    position -> store.claude.effort(model, efforts[position])), false);
            }
            claudeBox.addView(choice);
            claudeBox.addView(ui.footer("두뇌·분석·검색에 써요. 메인 대화와 작업창은 각 화면에서 골라요."));
            if (store.claude.info(model).optBoolean("fallback")) claudeBox.addView(ui.footer("이 모델이 안전 정책으로 답을 거절하면 Anthropic이 권하는 다른 Claude 모델이 이어서 답해요(서버 측 대체)."));
        }
        claudeBox.addView(ui.sectionHeader("연결 관리"));
        LinearLayout manage = ui.group();
        ui.addRow(manage, ui.listRow(0, 0, "연결 확인", "고른 모델에 짧게 물어봐요", null, this::testClaude), false);
        ui.addRow(manage, ui.listRow(0, 0, "모델 새로고침", "최신 모델 목록을 다시 받아요", null, store.claude::refreshModelsAsync), false);
        ui.addRow(manage, ui.listRow(0, 0, "AI 역할 정하기", "두뇌로 Claude를 쓸지 골라요", ui.chevron(), () -> openSettings("ai")), false);
        ui.addRow(manage, ui.listRow(0, 0, "키 바꾸기", "새 키를 확인한 뒤 바꿔요", ui.chevron(), this::claudeKeySheet), false);
        claudeBox.addView(manage);
        LinearLayout off = ui.group();
        ui.addRow(off, ui.dangerRow("연결 해제", "이 기기의 API 키를 지워요", () -> confirm("이 기기의 Claude API 키를 지울까요?", "해제", store.claude::disconnect)), false);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(16); claudeBox.addView(off, gap);
    }
    /** The Anthropic key, typed only in this sheet: ClaudeAccount checks it against the model list before keeping it. Closing it saves nothing. */
    private void claudeKeySheet() {
        boolean connected = store.claude.connected();
        Ui.Sheet sheet = ui.secretSheet(connected ? "Claude API 키 바꾸기" : "Claude API 키", "새 키를 확인한 뒤 저장해요", "Claude API 키 (sk-ant-…)", 300, connected ? "바꾸기" : "키 연결", (s, field) -> {
            try { store.claude.saveKey(field.getText().toString()); }
            catch (Exception e) { field.setError(e.getMessage() == null ? "키를 저장하지 못했어요." : e.getMessage()); return; }
            s.dismiss();
        });
        sheet.body.addView(ui.actions(ui.chip("Claude Console에서 키 만들기", () -> open("https://platform.claude.com/settings/keys"))));
        sheet.show();
    }
    /** One short request with the chosen model, to show that the key, model and network work. */
    private void testClaude() {
        String model = store.claude.model();
        if (model.isBlank()) { store.error("먼저 모델을 골라 주세요."); return; }
        store.status("Claude 연결을 확인하는 중…");
        new Thread(() -> {
            String result;
            try {
                OpenAiClient.Result answer = ClaudeClient.complete(store.claude, model, search -> "Reply in Korean with one short sentence.", "연결 확인이에요. 짧게 인사해 줘.", false, "low", () -> {}, new java.util.concurrent.atomic.AtomicReference<>());
                result = "Claude 연결 성공 ✓ · " + modelName(store.claude.models(), model) + " · " + answer.text.strip();
                String shown = result; runOnUiThread(() -> store.status(shown.length() > 120 ? shown.substring(0, 120) + "…" : shown));
            } catch (Exception e) {
                result = e instanceof java.io.IOException ? dev.jun.gyeot.core.Net.explain(e) : e.getMessage() == null ? "연결을 확인하지 못했어요." : e.getMessage();
                String shown = result; runOnUiThread(() -> store.error(shown));
            }
        }, "gyeot-claude-check").start();
    }
    private static String modelName(JSONArray models, String id) { JSONObject found = dev.jun.gyeot.core.ClaudeProtocol.find(models, id); return found == null ? id : found.optString("name", id); }
    /**
     * 설정 → AI 역할: the voice assistant's brain, the one AI it asks for the harder thinking while
     * Gemini Live stays its voice and hands, then the 작업창's switches. Models and thinking depth live in each 작업창.
     */
    private void pageAi() {
        boolean any = false; for (String engine : new String[]{WorkBook.CHATGPT, WorkBook.CLAUDE, WorkBook.GEMINI, WorkBook.GROK, WorkBook.NIM}) any |= store.ready(engine);
        if (!any) { TextView need = ui.banner("작업창과 두뇌에 쓸 AI가 아직 없어요 · 눌러서 연결 ›", WARNING); need.setOnClickListener(v -> openSettings("")); add(need, 12); }
        add(ui.text("목소리와 폰 조작(손)은 항상 Gemini Live가 해요. 어려운 생각만 두뇌에게 맡겨요.", 15, 400, MUTED), 4);
        add(ui.sectionHeader("음성 비서의 두뇌"), 0);
        String[] brainIds = {"gpt", "claude", "gemini", "nim", "none"};
        String[] brainNames = {"ChatGPT · 똑똑함, 조금 느림" + (store.openai.connected() ? "" : " (연결 필요)"), "Claude · 똑똑함, 조금 느림" + (store.claude.connected() ? "" : " (연결 필요)"),
            "Gemini Flash · 무료 한도 안에서 공짜" + (store.secrets.hasKey() ? "" : " (키 필요)"), "NVIDIA NIM · " + NimProtocol.label(store.nim.model()) + (store.nim.connected() ? "" : " (키 필요)"), "없음 · Gemini Live가 직접 판단"};
        LinearLayout brain = ui.group();
        ui.addRow(brain, ui.choiceRow("두뇌", "두뇌 고르기", "다음 음성 대화부터 적용돼요", brainNames, Math.max(0, java.util.Arrays.asList(brainIds).indexOf(store.brain())),
            position -> { if (brainIds[position].equals(store.brain())) return; store.settings.edit().putString("brain", brainIds[position]).apply(); store.changed(); showTab(2); }), false);
        add(brain, 0);
        // One line under the choice: what the brain does now, or why it does nothing and where to connect it.
        String chosen = store.brain(); boolean ready = store.thinkWanted();
        TextView doing = ui.label(chosen.equals("none") ? "Gemini Live가 혼자 판단해요 · 가장 빨라요"
            : chosen.equals("gemini") ? (ready ? "깊은 생각·입력할 글·여러 단계 폰 작업 순서를 Gemini Flash가 뒤에서 써요 · 무료 한도 안에서는 요금이 없어요" : "Gemini API 키가 없어 지금은 두뇌 없이 동작해요")
            : ready ? "깊은 생각·입력할 글·여러 단계 폰 작업 순서를 " + store.brainName() + "가 뒤에서 써요 · 그동안에도 대화는 이어져요" : store.brainName() + "가 연결돼 있지 않아 지금은 두뇌 없이 동작해요",
            12, chosen.equals("none") || ready ? MUTED : ERROR);
        doing.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0); add(doing, 0);
        if (!chosen.equals("none") && !ready) add(ui.actions(switch (chosen) {
            case "claude" -> ui.chip("Claude 연결하기", () -> openSettings("claude"));
            case "nim" -> ui.chip("NVIDIA NIM 연결하기", () -> openSettings("nim"));
            case "gemini" -> ui.chip("Gemini API 키 넣기", () -> openSettings("key"));
            default -> ui.chip("ChatGPT 연결하기", () -> openSettings("chatgpt"));
        }), 0);
        if (chosen.equals("gemini")) {
            TextView used = ui.label("오늘: " + dev.jun.gyeot.core.GeminiQuota.summary(store.geminiQuota(), dev.jun.gyeot.core.GeminiThink.MODELS, System.currentTimeMillis()), 12, ACCENT); used.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0); add(used, 0);
            add(ui.details("무료 한도와 모델 순서", "Flash 최신 → 2.5 Flash(하루 20번씩) → 3.5 Flash-Lite → 3.1 Flash-Lite(하루 500번씩) → Flash-Lite 최신 순서로 써요. 무료 하루 한도가 차거나 분당 한도에 걸리면 다음 모델로 넘어가고, 한도는 미국 서부 자정(한국 오후 4~5시)에 다시 채워져요. 앱의 검색·영상 요약 같은 다른 Gemini 사용과 한도를 함께 세요(설정 → 사용량)."), 0);
            add(ui.actions(ui.chip("내 키의 무료 한도 보기", () -> open("https://aistudio.google.com/rate-limit"))), 0);
        }
        if (chosen.equals("nim") && ready) { TextView nim = ui.quietRow("NVIDIA NIM 설정 열기 ›", () -> openSettings("nim")); nim.setPadding(ui.dp(16), 0, ui.dp(16), 0); add(nim, 0); }
        add(ui.footer("두뇌에는 요청과 최근 대화 몇 줄(폰 작업이면 배운 폰 사용법도)만 글로 보내고, 화면은 보내지 않아요. 다음 음성 대화부터 적용돼요."), 0);
        if (chosen.equals("none")) add(ui.footer("두뇌가 없어도 작업창과 연결된 그림·영상 만들기는 그대로 돼요. Live 생각 깊이는 목소리와 대화 → 음성 모델에서 골라요."), 0);
        add(ui.sectionHeader("작업 기능"), 0);
        LinearLayout work = ui.group();
        ui.addRow(work, ui.switchRow("작업창에서 코드 실행 (파일 만들기)", "계산하고 엑셀·그래프·문서를 들퍼레 폴더로 보내요", store.workCode(), value -> store.settings.edit().putBoolean("workCode", value).apply()), false);
        ui.addRow(work, ui.switchRow("작업창에서 그림 그리기 (ChatGPT)", "그려 달라고 할 때만 그려요 · 음성 비서의 그림도 따라요", store.workImages(), value -> store.settings.edit().putBoolean("workImages", value).apply()), false);
        ui.addRow(work, ui.switchRow("작업창 대화 자동 분류 (AI)", "첫 답이 오면 분류·제목·태그를 붙여요", store.autoSort(), value -> { store.settings.edit().putBoolean("autoSort", value).apply(); store.changed(); }), false);
        boolean refused = store.chatgptSearchRefused || store.claudeSearchRefused;
        LinearLayout search = ui.switchRow("웹 검색 사용 (최신 정보·출처)", refused ? "이번 실행에서는 연결이 검색을 거부했어요 · 껐다 켜면 다시 시도해요" : "작업창과 분석에서 웹을 찾고 출처를 붙여요",
            store.settings.getBoolean("chatgptSearch", true), value -> {
                store.settings.edit().putBoolean("chatgptSearch", value).apply(); store.chatgptSearchRefused = false; store.claudeSearchRefused = false; store.geminiSearchRefused = false; store.changed();
            });
        if (refused) ((TextView) search.getChildAt(1)).setTextColor(ERROR);
        ui.addRow(work, search, false);
        // An analysis AI picked in an older version that is no longer connected holds analysis back until the pick is cleared.
        if (store.settings.contains("analyst") && !store.ready(store.analystChoice())) {
            String old = store.analystChoice();
            LinearLayout clear = ui.listRow(0, 0, "예전 분석 AI 선택 지우기", WorkBook.author(old) + (WorkBook.GROK.equals(old) || WorkBook.NIM.equals(old) ? "이" : "가") + " 연결돼 있지 않아요 · 지우면 연결된 AI가 맡아요", null, () -> {
                store.settings.edit().remove("analyst").apply(); store.status("분석은 이제 연결된 AI가 맡아요"); showTab(2);
            });
            TextView why = ((Ui.ListRow) clear).parts.subtitle(); if (why != null) { why.setSingleLine(false); why.setMaxLines(2); }
            ui.addRow(work, clear, false);
        }
        add(work, 0);
        add(ui.footer("API 키로 연결했다면 코드 실행·그림·검색마다 요금이 붙을 수 있어요. 자동 분류는 대화마다 짧은 요청이 한두 번 더 가요."), 0);
        add(ui.details("자세히", "코드 · ChatGPT·Claude는 각자의 격리된 공간에서 코드를 돌려 계산하고 엑셀·그래프·문서 파일을 휴대폰 다운로드 → 들퍼레 폴더로 보내요. Gemini·Grok은 코드로 계산·확인만 하고 파일은 보내지 못해요. 휴대폰에서 코드를 실행하지는 않고, 쓸 수 없는 연결이면 코드 없이 답해요.\n"
            + "그림 · “고양이 일러스트 그려 줘”, “이 사진 배경을 바다로 바꿔 줘”처럼 말하면 OpenAI 이미지 생성으로 그려 사진 → 들퍼레 폴더에 저장하고 대화에 보여 줘요. Claude·Gemini·Grok·NIM도 같은 이미지 연결로 그리고 앞에서 만든 그림을 고쳐요. “더 밝게”처럼 이어서 고치거나 Claude로 바꿔 평가받을 수 있고, 연결 방식에 따라 거부될 수 있어요.\n"
            + "분류 · 그 작업창에서 고른 모델과 생각 깊이로 대화를 읽어요. 대화가 길어지면 한 번 더 정리하고, 직접 지은 이름과 분류는 바꾸지 않아요. 끄면 키워드로만 추정해요.\n"
            + "다른 곳 · 메인 대화의 검색은 입력창 위 ‘검색’에서, 그림은 메인 대화의 대화 설정에서, 음성 비서의 검색은 목소리와 대화 → 검색에서 따로 정해요."), 0);
        TextView where = ui.label("모델과 생각 깊이는 각 작업창에서, 녹음 요약 모델은 음성 비서 화면의 ‘녹음’에서 골라요.", 12, MUTED); where.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), 0); add(where, 16);
    }
    /** Where a GitHub token is made: a fine-grained token, given only the repositories it needs. */
    private static final String GITHUB_TOKENS = "https://github.com/settings/personal-access-tokens/new";
    /**
     * 설정 → 커넥터 → GitHub (page "github"): whether a token is connected and how GitHub is used,
     * then 기본 저장소 (picked from the list, pickDefaultRepo) and the token itself, typed only in githubTokenSheet.
     */
    private void pageGithub() {
        if (!store.githubConnected()) {
            add(ui.statusCard(R.drawable.ic_repo, Ui.INDIGO, "연결 안 됨", "토큰으로 내 저장소를 연결해요", null), 0);
            TextView what = ui.text("메인 대화와 작업창의 AI가 내 저장소를 읽고, 허용하면 고쳐요.", 14, 400, MUTED); what.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), 0); add(what, 14);
            add(ui.button("GitHub 토큰으로 연결", true, this::githubTokenSheet), 0);
            TextView make = ui.quietRow("토큰이 없나요? GitHub에서 만들어요 ›", () -> open(GITHUB_TOKENS)); make.setPadding(ui.dp(16), 0, ui.dp(16), 0); add(make, 0);
            add(ui.footer("고치게 하려면 Contents·Pull requests를 Read and write로, 읽기만 하려면 Read-only로 만들어 주세요. 필요한 저장소만 고른 토큰이 좋아요."), 0);
            add(ui.footer("토큰은 이 기기에만 암호화해 두고 GitHub에만 보내요. AI 회사로는 보내지 않아요."), 16);
            return;
        }
        boolean coding = store.settings.getBoolean("githubCoding", true), write = store.githubWrite();
        String user = store.settings.getString("githubUser", ""), who = user.isBlank() ? "GitHub" : user;
        add(coding ? ui.statusCard(R.drawable.ic_repo, Ui.INDIGO, "연결됨", who + " · " + (write ? "고치기 허용" : "읽기 전용"), null)
            : ui.statusCard(R.drawable.ic_repo, Ui.INDIGO, "꺼 둠", who + " · 작업창·메인 대화에서 쓰지 않아요", null), 0);
        add(ui.sectionHeader("사용"), 0);
        LinearLayout use = ui.group();
        ui.addRow(use, ui.switchRow("작업창·메인 대화에서 GitHub 쓰기", "끄면 두 곳 모두 GitHub를 쓰지 않아요", coding, value -> { store.settings.edit().putBoolean("githubCoding", value).apply(); store.changed(); showTab(2); }), false);
        ui.addRow(use, ui.switchRow("저장소 바꾸기 허용 (파일 수정·PR 생성·병합)", write ? "새 브랜치에서 고쳐 PR로 올려요 · 병합은 부탁할 때만 해요" : "지금은 읽기 전용이에요 · 읽고 설명·리뷰·고칠 방법을 알려 줘요",
            write, value -> { store.settings.edit().putBoolean("githubWrite", value).apply(); store.changed(); showTab(2); }), false);
        add(use, 0);
        add(ui.details("바꿀 때 지키는 것", "파일은 새 브랜치에서 고쳐 PR로 올려요. “메인에 머지해 줘”라고 하면 그 PR을 확인하고 병합해요. 이미 부탁한 일은 다시 허락을 묻지 않아요. 저장소·브랜치 삭제와 설정·비밀값 변경은 하지 않아요."), 0);
        add(ui.sectionHeader("저장소"), 0);
        LinearLayout repos = ui.group();
        // Quieter while GitHub is turned off, and it still opens (the picker says it is off).
        LinearLayout usual = ui.valueRow("기본 저장소", store.githubRepo().isEmpty() ? "없음" : store.githubRepo(), this::pickDefaultRepo); if (!coding) usual.setAlpha(0.5f);
        ui.addRow(repos, usual, false);
        add(repos, 0);
        add(ui.footer("새 작업창·메인 대화·예약 작업이 이 저장소로 시작해요. 작업창마다 입력칸 위에서 따로 고를 수 있어요."), 0);
        add(ui.footer("바꾸면 이미 저장한 예약 작업은 한 번 다시 저장해야 실행돼요."), 0);
        add(ui.sectionHeader("연결 관리"), 0);
        LinearLayout manage = ui.group();
        ui.addRow(manage, ui.listRow(0, 0, "토큰 바꾸기", "새 토큰을 GitHub에 확인한 뒤 바꿔요", ui.chevron(), this::githubTokenSheet), false);
        ui.addRow(manage, ui.listRow(0, 0, "GitHub에서 토큰 만들기", "github.com", ui.chevron(), () -> open(GITHUB_TOKENS)), false);
        add(manage, 16);
        LinearLayout leave = ui.group();
        ui.addRow(leave, ui.dangerRow("연결 해제", "이 기기의 토큰을 지워요", () -> confirm("이 기기의 GitHub 토큰을 지울까요? 토큰 자체를 막으려면 GitHub 설정에서 토큰을 삭제해 주세요.", "해제", () -> {
            try { store.secrets.put(SecretStore.GITHUB_CODE, ""); store.settings.edit().remove("githubUser").apply(); GitHubRepoList.forget(store); store.status("GitHub 연결을 해제했어요"); showTab(2); }
            catch (Exception e) { store.error("토큰을 지우지 못했어요."); }
        })), false);
        add(leave, 0);
        add(ui.footer("토큰은 이 기기에만 암호화해 두고 GitHub에만 보내요. AI 회사로는 보내지 않아요."), 0);
        add(ui.details("예시와 동작", "예: “로그인 버그 찾아서 고쳐 줘”, “최근 PR 리뷰해 줘”. GitHub 일은 이 앱이 휴대폰에서 직접 해요. AI는 필요한 줄만 읽고, 바꿀 부분만 고쳐 새 브랜치와 PR로 올려요. 메인 대화·작업창의 모든 AI(ChatGPT·Claude·Gemini·Grok·NIM)가 써요."), 16);
    }
    /** The GitHub token, typed only here (Ui.secretSheet): GitHub checks it before it is kept (connectGithub), then the sheet closes. */
    private void githubTokenSheet() {
        Ui.Sheet sheet = ui.secretSheet(store.githubConnected() ? "GitHub 토큰 바꾸기" : "GitHub 토큰 연결", "GitHub에 확인한 뒤 저장해요", "GitHub 토큰 (github_pat_…)", 300, "확인하고 연결",
            (shown, field) -> connectGithub(field, shown::dismiss));
        TextView scopes = ui.label("고치게 하려면 Contents·Pull requests를 Read and write로, 읽기만 하려면 Read-only로 만들어 주세요.", 12, MUTED); scopes.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        sheet.body.addView(scopes);
        sheet.body.addView(ui.actions(ui.chip("GitHub에서 토큰 만들기", () -> open(GITHUB_TOKENS))));
        sheet.show();
    }
    /**
     * 기본 저장소 from the same list as 작업창's chip (GitHubRepoPicker): where new 작업창, the main chat, schedules and voice
     * work start. A 작업창 not sent yet follows the new default; rooms keep their own. Saved schedules refuse to run until saved again.
     */
    private void pickDefaultRepo() {
        new GitHubRepoPicker(this, ui, store, "기본 저장소", "새 작업창·메인 대화·예약 작업이 이 저장소로 시작해요 · 작업창에서 따로 고른 저장소는 그대로예요", store.githubRepo(), null, repo -> {
            if (!store.settings.edit().putString("githubRepo", repo).commit()) { store.error("기본 저장소를 저장하지 못했어요."); return; }
            try { ScopedModels.draftFollowsDefault(store); } catch (Exception e) { store.error(e.getMessage()); }
            store.status(repo.isEmpty() ? "기본 저장소를 비웠어요 · 작업창에서 저장소를 골라 주세요"
                : "기본 저장소를 " + repo + "(으)로 정했어요" + (store.schedules().length() > 0 ? " · 예약 작업은 한 번 열어 다시 저장해 주세요" : ""));
            store.changed(); if (tab == 2) showTab(2);
        }).show();
    }
    /** Checks the token with GitHub (who it belongs to) and keeps it only when that works; then done runs. A refusal is the field's error. */
    /** One check of a typed GitHub token at a time; more taps on the sheet button while it runs do nothing. */
    private boolean githubChecking;
    private void connectGithub(EditText field, Runnable done) {
        String token = field.getText().toString().strip();
        if (!dev.jun.gyeot.core.GitHubTools.validToken(token)) { field.setError("github_pat_ 또는 ghp_로 시작하는 토큰을 넣어 주세요"); return; }
        if (githubChecking) return; githubChecking = true;
        store.status("GitHub 토큰을 확인하는 중…");
        new Thread(() -> {
            try {
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) java.net.URI.create(dev.jun.gyeot.core.GitHubTools.USER).toURL().openConnection();
                String body; int status;
                try {
                    // No redirects: the token goes to api.github.com and nowhere else.
                    connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(20_000);
                    connection.setRequestProperty("Authorization", "Bearer " + token); connection.setRequestProperty("Accept", "application/vnd.github+json"); connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                    status = connection.getResponseCode();
                    java.io.InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    body = input == null ? "" : OpenAiAccount.Http.read(input, 256 * 1024);
                } finally { connection.disconnect(); }
                if (status != 200) throw new IllegalStateException(status == 401 ? "GitHub가 이 토큰을 받지 않았어요. 만료되거나 잘못 복사됐는지 확인해 주세요." : "GitHub 토큰을 확인하지 못했어요 (" + status + ").");
                String login = new JSONObject(body).optString("login");
                store.secrets.put(SecretStore.GITHUB_CODE, token);
                store.settings.edit().putString("githubUser", login).apply(); GitHubRepoList.forget(store);
                runOnUiThread(() -> { githubChecking = false; field.setText(""); done.run(); store.status("GitHub에 " + login + "(으)로 연결했어요 · 메인 대화와 작업창에서 저장소 일을 맡겨 보세요"); if (tab == 2) showTab(2); });
            } catch (Exception e) {
                String reason = e instanceof java.io.IOException ? Net.explain(e) : e.getMessage() == null ? "GitHub 토큰을 확인하지 못했어요." : e.getMessage();
                runOnUiThread(() -> { githubChecking = false; field.setError(reason); store.error(reason); });
            }
        }, "gyeot-github-check").start();
    }
    /**
     * 커넥터: the services the AIs use for the user, grouped by where they are used. Each row shows
     * its state and opens its page (GitHub, Google Drive) or its sheet; every key, token and address is typed in a sheet.
     */
    private void pageConnectors() {
        add(ui.text("AI가 대신 써 줄 서비스를 연결해요. 주소·토큰은 이 기기에 암호화해 두고 그 서비스로만 보내요.", 14, 400, MUTED), 4);
        // Accounts, hosts and library names vary in length: two lines keep them and the state beside them whole at 360dp.
        java.util.function.UnaryOperator<LinearLayout> twoLines = row -> { TextView sub = ((Ui.ListRow) row).parts.subtitle(); if (sub != null) { sub.setSingleLine(false); sub.setMaxLines(2); } return row; };
        add(ui.sectionHeader("메인 대화·작업창에서 써요"), 0);
        LinearLayout work = ui.group();
        String user = store.settings.getString("githubUser", ""), account = store.drive.email();
        // A long account is shortened in the middle, so the state after it is never the part cut off.
        String email = account.length() > 30 ? account.substring(0, 14) + "…" + account.substring(account.length() - 14) : account;
        String githubState = !store.githubConnected() ? "연결 안 됨" : !store.settings.getBoolean("githubCoding", true) ? "꺼 둠" + (user.isBlank() ? "" : " · " + user)
            : (user.isBlank() ? "" : user + " · ") + (store.githubWrite() ? "고치기 허용" : "읽기 전용");
        ui.addRow(work, twoLines.apply(ui.listRow(R.drawable.ic_repo, Ui.INDIGO, "GitHub", githubState, ui.chevron(), () -> openSettings("github"))), true);
        String driveState = !store.drive.connected() ? "연결 안 됨" : !store.settings.getBoolean(GoogleDriveAccount.WORK, true) ? "꺼 둠" + (email.isBlank() ? "" : " · " + email)
            : (email.isBlank() ? "" : email + " · ") + (store.drive.writable() ? "수정·정리 허용" : "읽기·올리기");
        ui.addRow(work, twoLines.apply(ui.listRow(R.drawable.ic_folder, Ui.BLUE, "Google Drive", driveState, ui.chevron(), () -> openSettings("drive"))), true);
        add(work, 0);
        add(ui.footer("음성 비서는 저장소와 Drive를 쓰지 않아요."), 0);
        add(ui.sectionHeader("음성 비서·메인 대화에서 써요"), 0);
        LinearLayout voice = ui.group();
        ui.addRow(voice, twoLines.apply(ui.listRow(R.drawable.ic_book, Ui.ORANGE, "마이아카이브", archiveStatus(), ui.chevron(), () -> { if (store.archiveConnected()) archiveSheet(); else enterArchiveAddress(); })), true);
        JSONArray added = store.connectors();
        for (int i = 0; i < added.length(); i++) {
            JSONObject connector = added.optJSONObject(i); if (connector == null) continue;
            String connectorId = connector.optString("id"), connectorName = connector.optString("name"), host = connector.optString("host");
            JSONArray tools = connector.optJSONArray("tools"); int count = tools == null ? 0 : tools.length();
            boolean signedIn = "oauth".equals(connector.optString("auth"));
            ui.addRow(voice, twoLines.apply(ui.listRow(R.drawable.ic_plug, Ui.TEAL, connectorName, "도구 " + count + "개 · " + host + (signedIn ? " · 로그인" : ""), ui.chevron(),
                () -> connectorSheet(connectorId, connectorName, count, host, signedIn))), true);
        }
        ui.addRow(voice, ui.listRow(R.drawable.ic_add, ACCENT, "커넥터 추가", "MCP 서버 주소로 연결", ui.chevron(), this::enterConnector), true);
        add(voice, 0);
        add(ui.footer("MCP 커넥터의 데이터를 바꾸는 도구는 쓰기 전에 물어봐요. 음성 비서는 다음 음성 대화부터 바뀐 연결을 써요."), 0);
        add(ui.sectionHeader("음성 비서에서 써요"), 0);
        LinearLayout naver = ui.group(); ui.addRow(naver, naverNavigation().row(), true); add(naver, 0);
        TextView calendar = ui.quietRow("휴대폰 캘린더는 ‘캘린더’에서 켜요 ›", () -> openSettings("calendar")); calendar.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0);
        add(calendar, 16);
    }
    private NaverNavigationUi naverUi;
    private NaverNavigationUi naverNavigation() { if (naverUi == null) naverUi = new NaverNavigationUi(this, ui, store, () -> { if (!isDestroyed() && tab == 2) showTab(2); }); return naverUi; }
    private GoogleDriveUi driveUi;
    private GoogleDriveUi drive() { if (driveUi == null) driveUi = new GoogleDriveUi(this, ui, store, () -> { if (!isDestroyed() && tab == 2) showTab(2); }); return driveUi; }
    private WakeWordUi wakeUi;
    private WakeWordUi wakeWord() { if (wakeUi == null) wakeUi = new WakeWordUi(this, ui, store, () -> { if (!isDestroyed() && tab == 2) showTab(2); }); return wakeUi; }
    private CalendarSettingsUi calendarUi;
    private CalendarSettingsUi calendar() { if (calendarUi == null) calendarUi = new CalendarSettingsUi(this, ui, store, () -> { if (!isDestroyed() && tab == 2) showTab(2); }); return calendarUi; }
    private MemoryBackupUi memoryBackupUi;
    private MemoryBackupUi memoryBackup() { if (memoryBackupUi == null) memoryBackupUi = new MemoryBackupUi(this, ui, store, () -> { if (!isDestroyed() && tab == 2) showTab(2); }); return memoryBackupUi; }
    /**
     * 설정 → 목소리와 대화: the voice and its 말투, the Live model, search, listening and speaking,
     * then the interpreter's languages. Voice, 말투 and languages change during a conversation through the header's 지금 적용;
     * the rest from the next voice conversation.
     */
    private void pageVoice() {
        // 음성 비서 엔진: Gemini Live (its model, voices and depth below), or GPT·Grok on a realtime voice (their voices and sign-in).
        RealtimeVoice.Kind realtime = store.assistantRealtime();
        add(ui.sectionHeader("음성 비서 엔진"), 0);
        LinearLayout engine = ui.group();
        ui.addRow(engine, ui.choiceRow("음성 엔진", "음성 비서 엔진", "통역과 받아쓰기는 늘 Gemini Live로 해요", engineLabels(), Math.max(0, java.util.Arrays.asList(ENGINE_IDS).indexOf(store.assistantEngine())), position -> {
            store.settings.edit().putString(AppStore.ASSISTANT_ENGINE, ENGINE_IDS[position]).apply(); store.changed(); showTab(2);
        }), false);
        add(engine, 0);
        add(ui.footer(realtime == null ? "기본은 Gemini Live예요. GPT·Grok 실시간 음성으로 바꿔도 폰 조작·캘린더·브라우저 같은 기능은 그대로 써요."
            : realtime == RealtimeVoice.Kind.GPT ? "GPT 음성으로 대화해요. 최신 목소리(한울·햇살 등)는 GPT-Live로, 그 밖의 목소리는 GPT 실시간으로 말해요. 최신 목소리가 안 열리면 이유를 알려 줘요. 폰 조작·캘린더·브라우저 같은 기능은 그대로 쓰고, 화면과 카메라도 볼 수 있어요."
            : "Grok 실시간 음성으로 대화해요. 폰 조작·캘린더·브라우저 같은 기능은 그대로 쓰지만, 화면과 카메라는 볼 수 없어요."), 0);
        if (realtime != null) pageRealtimeVoice(realtime); else pageLiveVoice();
        pageVoiceRest(realtime);
    }
    /** GPT·Grok: the voice and its 말투, and what it connects with (a sign-in, or an API key of its own). */
    private void pageRealtimeVoice(RealtimeVoice.Kind kind) {
        add(ui.sectionHeader("목소리"), 0);
        LinearLayout voices = ui.group();
        String[][] options = RealtimeVoice.voiceChoices(kind);
        String[] names = new String[options.length]; int at = 0;
        for (int i = 0; i < options.length; i++) { names[i] = options[i][1]; if (options[i][0].equals(store.pickedVoice(kind))) at = i; }
        String saved = store.voiceStyle(store.styleVoice());
        LinearLayout style = ui.listRow(0, 0, "말투", saved.isBlank() ? "없음" : saved, ui.chevron(), this::voiceStyleSheet);
        ui.addRow(voices, ui.choiceRow("목소리", kind.label + " 목소리", kind == RealtimeVoice.Kind.GPT ? "‘최신’은 GPT-Live로 말해요 · 대화 중이면 ‘지금 적용’으로 바꿔요" : "대화 중이면 ‘지금 적용’으로 바로 바꿔요", names, at, position -> {
            store.settings.edit().putString(AppStore.ASSISTANT_VOICE + kind.name(), options[position][0]).apply(); store.changed();
            String next = store.voiceStyle(store.styleVoice()); TextView shown = ((Ui.ListRow) style).parts.subtitle();
            shown.setText(next.isBlank() ? "없음" : next); style.setContentDescription("말투, " + shown.getText());
        }), false);
        ui.addRow(voices, style, false);
        add(voices, 0);
        add(ui.footer("말투는 목소리마다 따로 저장돼요."), 0);
        add(ui.sectionHeader("연결"), 0);
        LinearLayout sign = ui.group();
        realtimeSignIn(sign, kind, () -> {});
        if (kind == RealtimeVoice.Kind.GPT) realtimeKeyRow(sign, SecretStore.OPENAI_VOICE, "OpenAI API 키 (선택)", "넣으면 로그인 대신 이 키로 연결해요", "sk-로 시작하는 키");
        else realtimeKeyRow(sign, SecretStore.XAI_VOICE, "xAI API 키 (선택)", "넣으면 구독 로그인 대신 이 키로 연결해요", "xai-로 시작하는 키");
        add(sign, 0);
        add(ui.footer("메인 대화의 음성 대화와 같은 로그인과 키를 써요."), 0);
    }
    /** The realtime voice's sign-in as a row: what it connects with now, or a tap to sign in (before runs first, to close a sheet). */
    private void realtimeSignIn(LinearLayout group, RealtimeVoice.Kind kind, Runnable before) {
        String title, subtitle; Runnable signIn = null;
        if (kind == RealtimeVoice.Kind.GPT) {
            if (store.secrets.has(SecretStore.OPENAI_VOICE)) { title = "OpenAI API 키로 연결해요"; subtitle = "넣어 둔 키를 먼저 써요"; }
            else if (store.codexImages.connected()) { title = "Codex 로그인 됨"; subtitle = "ChatGPT 구독으로 GPT 음성(최신·실시간)을 써요"; }
            else if ("key".equals(store.openai.describe().optString("mode"))) { title = "ChatGPT 연결의 API 키로 연결해요"; subtitle = "Codex로 로그인하면 ChatGPT 구독으로 써요"; signIn = store.codexImages::signIn; }
            else { title = "Codex로 로그인"; subtitle = "ChatGPT 구독 계정으로 로그인하면 API 키 없이 써요"; signIn = store.codexImages::signIn; }
        } else {
            if (store.secrets.has(SecretStore.XAI_VOICE)) { title = "xAI API 키로 연결해요"; subtitle = "넣어 둔 키를 먼저 써요"; }
            else if (store.grok.connected()) { title = "Grok 구독 로그인 됨"; subtitle = "구독 로그인으로 연결해요 · 거절되면 xAI API 키를 넣어 주세요"; }
            else { title = "Grok 구독 계정으로 로그인"; subtitle = "로그인하거나 xAI API 키를 넣으면 써요"; signIn = store.grok::signIn; }
        }
        Runnable go = signIn;
        ui.addRow(group, ui.listRow(0, 0, title, subtitle, go == null ? null : ui.chevron(), go == null ? null : () -> { before.run(); go.run(); }), false);
    }
    /** A realtime voice's own API key: typed only in the secret sheet, saved encrypted, cleared by saving it empty. */
    private void realtimeKeyRow(LinearLayout group, String name, String title, String subtitle, String hint) {
        boolean saved = store.secrets.has(name);
        ui.addRow(group, ui.listRow(0, 0, title, saved ? "저장됨 · 눌러서 바꾸거나 지우기" : subtitle, ui.chevron(), () -> {
            Ui.Sheet sheet = ui.secretSheet(title, saved ? "비워 두고 저장하면 지워요" : subtitle, hint, 300, "저장", (s, field) -> {
                String value = field.getText().toString().strip();
                if (!value.isEmpty() && (value.length() < 16 || value.matches("(?s).*\\s.*"))) { field.setError("API 키 형식을 확인해 주세요."); return; }
                try { store.secrets.put(name, value); s.dismiss(); store.status(value.isEmpty() ? "키를 지웠어요" : "키를 저장했어요"); store.changed(); if (tab == 2) showTab(2); }
                catch (Exception e) { field.setError(e.getMessage() == null ? "키를 저장하지 못했어요." : e.getMessage()); }
            });
            sheet.show();
        }), false);
    }
    /** Gemini Live: the voice and its 말투, the Live model and its depth. */
    private void pageLiveVoice() {
        add(ui.sectionHeader("목소리"), 0);
        LinearLayout voices = ui.group();
        int voiceIndex = 0; for (int i = 0; i < GeminiProtocol.VOICES.length; i++) if (GeminiProtocol.VOICES[i].equals(store.voice())) voiceIndex = i;
        // The 말투 row shows the chosen voice's own style; picking another voice swaps it in place.
        String saved = store.voiceStyle(store.voice());
        LinearLayout style = ui.listRow(0, 0, "말투", saved.isBlank() ? "없음" : saved, ui.chevron(), this::voiceStyleSheet);
        ui.addRow(voices, ui.choiceRow("목소리", "목소리 고르기", "이 모델이 받지 않는 목소리면 기본 목소리로 연결해요", VOICE_LABELS, voiceIndex, position -> {
            if (GeminiProtocol.VOICES[position].equals(store.voice())) return;
            store.settings.edit().putString("voice", GeminiProtocol.VOICES[position]).apply(); store.forgetVoiceRefusals(GeminiProtocol.VOICES[position]); store.changed();
            String next = store.voiceStyle(store.voice()); TextView shown = ((Ui.ListRow) style).parts.subtitle();
            shown.setText(next.isBlank() ? "없음" : next); style.setContentDescription("말투, " + shown.getText());
        }), false);
        ui.addRow(voices, style, false);
        add(voices, 0);
        add(ui.footer("말투는 음성 비서에만 써요. 통역은 말투 없이 그대로 번역해요."), 0);

        add(ui.sectionHeader("음성 모델"), 0);
        LinearLayout model = ui.group();
        String[] modelLabels = new String[GeminiProtocol.LIVE_MODELS.length]; int modelIndex = 0;
        for (int i = 0; i < modelLabels.length; i++) { modelLabels[i] = GeminiProtocol.LIVE_MODELS[i][1]; if (GeminiProtocol.LIVE_MODELS[i][0].equals(store.liveModel())) modelIndex = i; }
        ui.addRow(model, ui.choiceRow("음성 모델", "음성 모델", "통역·받아쓰기는 전용 모델을 써요", modelLabels, modelIndex, position -> {
            store.settings.edit().putString("liveModel", GeminiProtocol.LIVE_MODELS[position][0]).apply(); store.changed(); showTab(2);
        }), false);
        if (GeminiProtocol.extended(store.liveModel())) {
            int depth = java.util.Arrays.asList(GeminiProtocol.THINKING_LEVELS).indexOf(store.liveThinkingLevel());
            ui.addRow(model, ui.choiceRow("생각 깊이", "생각 깊이", "기본은 보통이에요", new String[]{"낮음 · 빠르게 답해요", "보통 · 기본", "높음 · 어려운 요청을 더 오래 생각해요"}, depth, index -> {
                store.settings.edit().putString("liveThinkingLevel", GeminiProtocol.THINKING_LEVELS[index]).apply(); store.changed();
            }), false);
        }
        add(model, 0);
        // Out of quota: which model stands in for the chosen one, and the way back to it.
        String active = store.activeModel();
        if (active == null) {
            TextView spent = ui.label("모든 Live 모델의 한도가 다 찼어요. 한 시간쯤 뒤에 다시 시도하거나 aistudio.google.com/rate-limit에서 한도를 확인해 주세요.", 12, ERROR);
            spent.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0); add(spent, 0);
        } else if (!active.equals(store.liveModel())) {
            TextView moved = ui.label(AppStore.modelLabel(store.liveModel()) + " 한도가 다 차서 지금은 " + AppStore.modelLabel(active) + "(으)로 연결해요. 한 시간 뒤 다시 " + AppStore.modelLabel(store.liveModel()) + "을(를) 써요.", 12, ERROR);
            moved.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0); add(moved, 0);
            add(ui.actions(ui.chip(AppStore.modelLabel(store.liveModel()) + " 지금 다시 쓰기", () -> { store.clearExhausted(store.liveModel()); showTab(2); })), 0);
        }
        add(ui.footer("한도가 차면 Extended Thinking → 3.8 Live → 3.1 → 2.5 순서로 쓸 수 있는 모델로 이어 가요. 최근 대화도 함께 넘겨요."), 0);
    }
    /** Search, listening, speaking and the interpreter's languages, whichever engine the assistant runs on (realtime: GPT·Grok, or null). */
    private void pageVoiceRest(RealtimeVoice.Kind realtime) {
        add(ui.sectionHeader("검색"), 0);
        LinearLayout search = ui.group(); boolean searching = store.searchWanted();
        ui.addRow(search, ui.switchRow("검색 사용", "최신 정보를 찾아 출처와 함께 답해요", searching, value -> { store.settings.edit().putBoolean("search", value).apply(); store.changed(); showTab(2); }), false);
        String[] providers = {"Gemini (Google 검색)", "ChatGPT (웹 검색)", "Claude (웹 검색)"}, providerIds = {"gemini", "chatgpt", "claude"};
        String chosenProvider = store.settings.getString("searchProvider", "gemini");
        // Redrawn after a pick, so the line under the group says who searches now.
        if (searching) ui.addRow(search, ui.choiceRow("검색 담당", "검색 담당", "고른 쪽이 실패하면 다른 쪽이 찾아요", providers, Math.max(0, java.util.Arrays.asList(providerIds).indexOf(chosenProvider)),
            position -> { store.settings.edit().putString("searchProvider", providerIds[position]).apply(); store.changed(); showTab(2); }), false);
        add(search, 0);
        if (searching) {
            add(ui.footer(!chosenProvider.equals(store.searchProvider()) ? (chosenProvider.equals("claude") ? "Claude" : "ChatGPT") + "가 연결돼 있지 않아 지금은 Gemini가 검색해요."
                : "ChatGPT·Claude 검색은 그쪽 사용량을 써요."), 0);
            if ("gemini".equals(store.searchProvider()) && store.searchBlocked()) {
                String refusal = store.searchRefusal();
                TextView delegated = ui.label("지금 음성 모델은 이 키에서 Google 검색을 직접 붙일 수 없어요" + (refusal.isBlank() ? "" : " (Google: " + brief(refusal) + ")")
                    + ". 그래서 검색이 필요하면 " + Brand.I + " 따로 Google 검색을 해서 결과를 전해 줘요(검색 대행).", 12, MUTED);
                delegated.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0); add(delegated, 0);
                add(ui.actions(ui.chip("직접 검색 다시 시도", () -> { store.retrySearch(store.activeModel() == null ? store.liveModel() : store.activeModel()); showTab(2); })), 0);
            }
        }

        add(ui.sectionHeader("듣기"), 0);
        LinearLayout listen = ui.group();
        ui.addRow(listen, ui.switchRow("작게 말해도 알아듣기", "작은 목소리도 잘 들어요 · 시끄러우면 꺼 주세요", store.quietSpeech(), value -> store.settings.edit().putBoolean("quietSpeech", value).apply()), false);
        ui.addRow(listen, ui.switchRow("스피커로 들을 때 에코 방지", "스피커 소리에 말이 끊기지 않게 해요", store.echoGuard(), value -> store.settings.edit().putBoolean("echoGuard", value).apply()), false);
        ui.addRow(listen, ui.switchRow("정밀 자막 함께 사용", realtime == null ? "한국어 받아쓰기로 자막과 명령을 더 정확히 들어요" : "Gemini Live에서만 써요 · 실시간 음성은 직접 받아 적어요", store.captions(), value -> store.settings.edit().putBoolean("captions", value).apply()), false);
        String[] stopLabels = new String[AUTO_STOP.length]; int stopIndex = 0;
        for (int i = 0; i < AUTO_STOP.length; i++) { stopLabels[i] = AUTO_STOP[i] == 0 ? "끄기" : AUTO_STOP[i] + "분"; if (AUTO_STOP[i] == store.autoStopMinutes()) stopIndex = i; }
        ui.addRow(listen, ui.choiceRow("말이 없으면 자동 종료", "자동 종료", "잊고 둔 마이크가 사용량을 쓰지 않게 해요", stopLabels, stopIndex,
            position -> store.settings.edit().putInt("autoStop", AUTO_STOP[position]).apply()), false);
        add(listen, 0);
        add(ui.details("자세히", "정밀 자막 · 기본으로 켜져 있어요. 3.5 Transcribe가 한국어로 받아 적은 말로 자막을 보여 주고 명령을 알아들어요. 연결이 하나 더 열려 사용량이 늘고, 바꾸면 다음 음성 대화부터 적용돼요. 끄면 대화 모델 자체 자막을 쓰고, 통역은 항상 Live Translate 자막을 써요.\n"
            + "에코 방지 · 이어폰 없이 들을 때 " + Brand.I + " 말하는 동안 마이크를 잠시 멈춰요. 이어폰·블루투스를 쓰면 말 끼어들기가 그대로 돼요.\n"
            + "작게 말해도 알아듣기 · 마이크를 음성 인식용으로 쓰고, 작은 소리를 키우고, 말 시작을 더 민감하게 알아채요. 다음 음성 대화부터 적용돼요."), 0);

        add(ui.sectionHeader("말하기와 알림"), 0);
        LinearLayout speak = ui.group();
        String[] levels = {"원래대로", "고르게 · 차에서는 더 크게 (기본)", "크게 · 어디서나 크게"};
        ui.addRow(speak, ui.choiceRow("목소리 크기", "목소리 크기", "작게 들리는 목소리를 키워요 · 차·블루투스 스피커에서는 더 크게", levels, store.voiceLevelChoice(),
            position -> store.settings.edit().putInt("voiceLevel", position).apply()), false);
        ui.addRow(speak, ui.switchRow("켤 때 인사하기", "켜자마자 ‘틱’ 소리나 “네, 말씀하세요.”로 알려요", store.greetOnStart(), value -> store.settings.edit().putBoolean("greetOnStart", value).apply()), false);
        ui.addRow(speak, ui.switchRow("완료된 작업을 음성으로 안내", "대화 중 맡긴 일이 끝나면 말로 알려 줘요", store.announce(), value -> store.settings.edit().putBoolean("announce", value).apply()), false);
        ui.addRow(speak, ui.switchRow("음성으로 작업창에 일 시키기", "긴 일을 작업창에 맡겨요 · 기본은 꺼져 있어요.", store.voiceWork(), value -> store.settings.edit().putBoolean("voiceWork", value).apply()), false);
        add(speak, 0);
        add(ui.details("자세히", "목소리 크기 · 고르게는 작게 들리는 목소리(GPT 음성 등)를 다른 목소리만큼 키우고, 차나 블루투스 스피커가 연결돼 있으면 더 크게 해요. 이미 큰 목소리는 줄이지 않고, 키운 소리가 찢어지지 않게 끝만 눌러 줘요. 바꾸면 바로 적용돼요. 화면의 음성 대화에서 GPT 최신 음성은 통화로 나가서 휴대폰·차의 통화 음량을 따라요.\n"
            + "켤 때 인사하기 · 연결을 기다리지 않고 바로 인사해요. 스피커·차·블루투스 스피커에서는 짧고 작은 ‘틱’ 소리만 나요. 마이크 달린 이어폰에서는 그 목소리의 인사 음성이 저장돼 있으면 “네, 말씀하세요.”라고 하고, 없으면 ‘틱’ 소리가 나요. ‘기본’ 목소리는 ‘틱’ 소리로만 인사해요. 인사하는 동안 하시는 말도 막지 않고 모두 전달해요.\n"
            + "음성으로 작업창에 일 시키기 · 저장소 수정 같은 긴 일을 작업창에 맡기고, 진행을 알려 주고, 말로 멈출 수 있어요. 웹 일은 브라우저, 폰 일은 폰 조작이 맡아요. 다음 음성 대화부터 적용돼요."), 0);

        add(ui.sectionHeader("통역 언어"), 0);
        LinearLayout languages = ui.card(); languages.addView(new InterpreterUi(ui, store, this::interpreterChanged).view()); add(languages, 0);
        add(ui.footer("목소리·말투·통역 언어는 대화 중에도 위에 뜨는 ‘지금 적용’으로 바로 바꿀 수 있어요. 나머지는 다음 음성 대화부터 적용돼요."), 16);
    }
    /**
     * The chosen voice's 말투 (목소리와 대화 → 말투): saved as it is typed, for that voice only, and the presets add to it.
     * Closing the sheet redraws the page, so the 말투 row shows what was kept.
     */
    private void voiceStyleSheet() {
        // Whose 말투 (AppStore.styleVoice): the Gemini voice's, or the realtime voice's the assistant runs on.
        String voice = store.styleVoice(); RealtimeVoice.Kind realtime = store.assistantRealtime();
        String shownVoice = realtime != null ? RealtimeVoice.shown(realtime, store.pickedVoice(realtime)) : voice.isEmpty() ? "기본 목소리" : voice;
        Ui.Sheet sheet = ui.selectionSheet(shownVoice + "의 말투", "적으면 바로 저장돼요 · 목소리마다 따로예요");
        EditText input = ui.input("예: 상냥하고 애교 있게, 문장 끝을 부드럽게", 3, GeminiProtocol.VOICE_STYLE_LIMIT);
        // Filled before the watcher, so showing the saved style is not an edit.
        input.setText(store.voiceStyle(voice));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) { store.voiceStyle(voice, text.toString()); store.changed(); }
        });
        sheet.body.addView(input);
        List<Button> presets = new ArrayList<>();
        for (String preset : GeminiProtocol.VOICE_STYLES) presets.add(ui.chip(preset, () -> {
            String current = input.getText().toString().strip();
            input.setText(current.isEmpty() ? preset : current + ", " + preset); input.setSelection(input.length());
        }));
        sheet.body.addView(ui.actions(presets.toArray(new Button[0])));
        sheet.body.addView(ui.actions(ui.chip("말투 지우기", () -> { input.setText(""); store.status("말투를 지웠어요"); })));
        sheet.setOnDismissListener(d -> { if (!isDestroyed() && tab == 2) showTab(2); });
        sheet.show();
    }
    /**
     * 설정 → 바로 부르기: every way to call 들퍼레 (opening the app, the edge button, the home button,
     * the tile, the icon), each a row with its own state (edgeState and assistState follow refresh()), then the car's wake
     * word, a page of its own.
     */
    private void pageQuick() {
        TextView about = ui.text("누르거나 말해서 " + Brand.EUL + " 불러요. 버튼과 타일은 누를 때만 마이크를 켜요.", 13, 400, MUTED); about.setPadding(ui.dp(16), 0, ui.dp(16), 0); add(about, 0);
        add(ui.sectionHeader("앱을 열 때"), 0);
        LinearLayout open = ui.group();
        ui.addRow(open, ui.switchRow("앱을 열면 바로 음성 대화", "아이콘이나 “빅스비, " + Brand.NAME + " 열어 줘”로 열면 바로 시작해요", store.voiceOnOpen(),
            value -> { store.settings.edit().putBoolean("voiceOnOpen", value).apply(); store.changed(); }), false);
        add(open, 0);
        add(ui.footer("최근 앱 목록에서 돌아올 때는 시작하지 않아요."), 0);

        add(ui.sectionHeader("누르는 곳"), 0);
        LinearLayout press = ui.group();
        LinearLayout edge = ui.switchRow("화면 가장자리 버튼", edgeStatus(), store.edgeButton(), value -> { store.settings.edit().putBoolean("edgeButton", value).apply(); store.changed(); showTab(2); });
        edgeState = (TextView) edge.getChildAt(1); edgeDrawn = store.edgeButton(); ui.addRow(press, edge, false);
        // The button shows only with 폰 조작's first step on; while it is off, the way there sits right under the switch.
        if (store.edgeButton() && !PhoneService.connected())
            ui.addRow(press, ui.listRow(0, 0, "폰 조작 1단계 켜러 가기", "접근성에서 ‘" + Brand.NAME + " 폰 조작’을 켜요", ui.chevron(), () -> openSettings("phone")), false);
        // The two live states below take up to two lines, so 「눌러서 바꾸기」 and the car's name are not cut at 360dp.
        LinearLayout home = ui.listRow(R.drawable.ic_home, Ui.BLUE, "홈 버튼 길게 누르기", assistStatus(), ui.chevron(), this::openAssistantSettings);
        assistState = ((Ui.ListRow) home).parts.subtitle(); assistState.setSingleLine(false); assistState.setMaxLines(2); ui.addRow(press, home, true);
        ui.addRow(press, ui.listRow(R.drawable.ic_layers, Ui.TEAL, "알림창 타일 추가", "한 번 누르면 시작, 다시 누르면 종료해요", ui.chevron(), this::addTile), true);
        ui.addRow(press, ui.listRow(R.drawable.ic_bolt, Ui.ORANGE, "아이콘 길게 누르기 → ‘바로 대화’", "홈 화면에 따로 끌어 둘 수도 있어요", null, null), true);
        add(press, 0);
        add(ui.details("자세히", "가장자리 버튼 · 화면 쪽으로 살짝 밀거나 누르면 대화가 시작돼요. 위아래로 끌어 옮기고, 길게 누르면 메뉴(" + Brand.NAME + " 열기·반대쪽으로·잠깐 숨기기·버튼 끄기)가 열려요. 잠깐 숨기면 화면을 껐다 켤 때 다시 나타나요. 대화 중·폰 조작 중·잠금 화면에서는 숨어요. 폰 조작 1단계(접근성 ‘" + Brand.NAME + " 폰 조작’)가 필요하고, 접근성을 끄면 버튼도 바로 사라져요. 버튼은 화면 내용을 읽지 않아요.\n"
            + "홈 버튼 · Android 설정 → 기본 앱 → 디지털 어시스턴트 앱에서 " + Brand.EUL + " 골라요. 제스처를 쓰면 화면 아래 모서리에서 대각선으로 쓸어 올려요. 대신 Google(Gemini) 호출은 이 동작에서 빠지고, 같은 곳에서 Google을 고르면 되돌아가요."), 0);

        add(ui.sectionHeader("차에서"), 0);
        LinearLayout car = ui.group();
        LinearLayout wake = ui.listRow(R.drawable.ic_mic, Ui.ORANGE, "차에서 호출어", WakeWordUi.summary(this, store), ui.chevron(), () -> openSettings("wake"));
        TextView standby = ((Ui.ListRow) wake).parts.subtitle(); standby.setSingleLine(false); standby.setMaxLines(2); ui.addRow(car, wake, true);
        add(car, 0);
        add(ui.footer("호출어는 고른 차의 블루투스가 연결된 동안에만 마이크를 켜요."), 16);
    }
    /**
     * 설정 → 폰 조작 (page "phone"): the state on top, the two steps that turn it on, the rules it
     * keeps, the switches that make it better, and the log behind one row (phoneLogSheet). refresh() redraws the page when
     * Android connects or drops the accessibility service (phoneSeen).
     */
    private void pagePhone() {
        boolean allowed = store.phoneControl(), connected = PhoneService.connected();
        add(allowed && connected ? ui.statusCard(R.drawable.ic_phone, Ui.GREEN, "준비됐어요", "음성 대화 중에만 앱을 열고 눌러요", null)
            : allowed ? ui.statusCard(R.drawable.ic_phone, Ui.RED, "확인이 필요해요", "접근성 연결이 끊겼어요", "확인 필요")
            : ui.statusCard(R.drawable.ic_phone, Ui.GRAY, "꺼져 있어요", connected ? "1단계는 끝났어요 · 아래 2단계를 켜 주세요" : "아래 1·2단계를 켜면 써요", null), 0);
        // Allowed but not connected (often after an update): what happened, and the fix in Android's accessibility settings, one tap away.
        if (allowed && !connected) {
            TextView problem = ui.label(PhoneService.diagnosis(this, allowed) + " ›", 13, ERROR); problem.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0);
            problem.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); Ui.pressable(problem, true); add(problem, 0);
        }
        TextView what = ui.text("음성 비서가 앱을 열고, 화면을 읽고, 누르고, 글을 입력해요. 예: “카톡 열어서 엄마한테 ‘곧 갈게’ 보내 줘”, “크롬에서 네이버 들어가서 내일 날씨 찾아 줘”.", 13, 400, MUTED);
        what.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), 0); add(what, 0);

        add(ui.sectionHeader("켜는 방법"), 0);
        LinearLayout steps = ui.group();
        LinearLayout first = ui.listRow(R.drawable.ic_lock, connected ? Ui.GREEN : Ui.ORANGE, "1단계 · 접근성에서 ‘" + Brand.NAME + " 폰 조작’ 켜기", connected ? "켜져 있어요" : "꺼져 있어요 · 눌러서 접근성 설정 열기",
            ui.chevron(), () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        // Up to two lines, so 「눌러서 접근성 설정 열기」 is not cut with larger text.
        TextView how = ((Ui.ListRow) first).parts.subtitle(); how.setSingleLine(false); how.setMaxLines(2); ui.addRow(steps, first, true);
        // Redrawn after a change, so the status card above says the new state.
        ui.addRow(steps, ui.switchRow("2단계 · 음성 비서의 폰 조작 허용", "음성 대화 중에만 켜져요 · 끄면 다른 앱 화면을 누르지 않아요", allowed, value -> {
            store.settings.edit().putBoolean("phoneControl", value).apply();
            if (!value) PhoneService.stopByUser();
            else if (store.voiceActive && ASSISTANT.equals(store.voiceMode) && PhoneService.connected()) { try { PhoneService.armByUser(); } catch (Exception e) { store.error(e.getMessage()); } }
            store.changed(); showTab(2);
        }), false);
        add(steps, 0);
        if (!connected) {
            add(ui.footer("설치된 앱 → ‘" + Brand.NAME + " 폰 조작’ → ‘사용’을 켜요. 바로가기(접근성 버튼)는 켜지 않아도 돼요."), 0);
            add(ui.footer("‘제한된 설정’ 안내가 뜨면 ‘앱 정보 열기’ → 오른쪽 위 ⋮ → ‘제한된 설정 허용’을 누른 뒤 다시 켜요."), 0);
            add(ui.actions(ui.chip("앱 정보 열기", this::openAppSettings)), 0);
        }
        add(ui.footer("켜져 있는 동안 작은 음성 버튼 위에 멈춤 버튼이 떠요. 누르면 바로 멈추고, 버튼 메뉴의 ‘폰 조작 다시 켜기’로 다시 켜요."), 0);

        add(ui.sectionHeader("안전하게"), 0);
        LinearLayout safe = ui.card();
        safe.addView(ui.label("· 메시지 보내기·결제·삭제·전화 전에는 무엇을 할지 말하고 ‘응’이라는 대답을 기다려요.\n· 잠금 해제, 비밀번호 입력, 보안·권한 설정, 앱 설치 화면은 건드리지 않아요.\n"
            + "· 화면 내용은 Gemini에만 보내요(디시전을 켜면 Gemini 디시전에도). 두뇌(ChatGPT·Claude·Gemini Flash·NIM)는 작업 지시서와 입력할 글만 쓰고 화면은 받지 않아요. 화면 정보는 기기에 저장하지 않아요.", 12, MUTED));
        add(safe, 0);

        add(ui.sectionHeader("더 잘하게"), 0);
        LinearLayout better = ui.group();
        ui.addRow(better, ui.switchRow("끝까지 이어서 하기", "말만 하고 멈추면 이어서 하라고 알려요 · 엉키면 꺼 보세요", store.settings.getBoolean("phoneContinue", true),
            value -> store.settings.edit().putBoolean("phoneContinue", value).apply()), false);
        // Gemini Decision runs voice phone tasks of several steps: the user's choice, off until turned on.
        boolean hasKey = store.secrets.has(SecretStore.GEMINI);
        ui.addRow(better, ui.switchRow("Gemini 디시전으로 빠르게 조작 (실험)", "다음 누를 곳을 0.2초쯤에 골라요 · Gemini API 키로 요청해요", hasKey && store.settings.getBoolean("phoneDecisions", false), value -> {
            // Without a Gemini key it would never run (AppStore.phoneDecisions): say so, and keep it off (redrawn, the switch shows off too).
            if (value && !store.secrets.has(SecretStore.GEMINI)) { store.error("Gemini 디시전은 Gemini API 키로 써요. 설정 → Gemini에서 키를 먼저 넣어 주세요."); store.changed(); showTab(2); return; }
            store.settings.edit().putBoolean("phoneDecisions", value).apply(); store.changed();
        }), false);
        add(better, 0);
        add(ui.footer("디시전 요금은 Gemini API 키의 프로젝트에 나가요(입력 100만 토큰당 약 0.1달러). 다음 음성 대화부터 적용돼요."), 0);
        add(ui.details("자세히", "여러 단계 일도 끝까지 이어서 하고, 로딩·생성은 기다렸다가 다시 확인해요. 폰 조작은 Gemini가 해요. 두뇌를 따로 두면 두뇌가 작업 순서와 입력할 글을 쓰고, Gemini가 그대로 화면을 읽고 조작해요.\n"
            + "디시전 · 켜면 여러 단계 폰 일을 Gemini 디시전에 넘겨 단계마다 생각하는 시간을 줄여요. 확신이 낮거나 맞는 동작이 없으면 음성 비서가 이어받고, 보내기·결제·삭제·전화 같은 규칙은 그대로예요."), 0);

        add(ui.sectionHeader("기록"), 0);
        LinearLayout record = ui.group();
        String log = store.phoneLogText();
        LinearLayout logRow = ui.listRow(R.drawable.ic_history, Ui.GRAY, "최근 폰 조작 기록", log.isBlank() ? "아직 기록이 없어요 · 앱을 끄면 지워져요" : log.split("\n").length + "줄 · 이상하면 복사해서 보내 주세요",
            ui.chevron(), this::phoneLogSheet);
        TextView lines = ((Ui.ListRow) logRow).parts.subtitle(); lines.setSingleLine(false); lines.setMaxLines(2); ui.addRow(record, logRow, true);
        add(record, 16);
    }
    /**
     * 최근 폰 조작 기록 (폰 조작 and 정보): the steps of recent phone tasks, voice and wake-word events, in memory only. Only
     * the log scrolls; its actions stay above 닫기. 새로 보기, 지우기 and 되돌리기 redraw the sheet in place; closing it
     * redraws the settings page under it, so its row shows the new count.
     */
    private void phoneLogSheet() {
        Ui.Sheet sheet = ui.selectionSheet("최근 폰 조작 기록", "음성·호출어 기록도 함께 남아요 · 앱을 끄면 지워져요");
        LinearLayout tools = ui.column(); sheet.footer.addView(tools, 0, new LinearLayout.LayoutParams(-1, -2));
        Runnable[] fill = {null};
        fill[0] = () -> {
            sheet.body.removeAllViews(); tools.removeAllViews();
            String log = store.phoneLogText();
            LinearLayout card = ui.card();
            TextView logText = ui.text(log.isBlank() ? "아직 기록이 없어요. 음성 비서에게 폰 일을 시키면 여기에 단계별로 남아요(앱을 끄면 지워져요)." : log, 12, 400, log.isBlank() ? MUTED : TEXT);
            logText.setTypeface(android.graphics.Typeface.MONOSPACE); logText.setTextIsSelectable(true); card.addView(logText);
            sheet.body.addView(card);
            if (!log.isBlank()) tools.addView(ui.actions(ui.chip("복사", () -> copy(log)), ui.chip("새로 보기", fill[0]), ui.chip("지우기", () -> { store.clearPhoneLog(); fill[0].run(); })));
            if (store.canUndoPhoneLog()) tools.addView(ui.actions(ui.chip("최근 정리 되돌리기 · 5분", () -> { store.undoPhoneLog(); fill[0].run(); })));
            TextView hint = ui.label("폰 조작이 이상하면 이 기록을 복사해서 보내 주세요. 어느 단계에서 멈췄는지 바로 알 수 있어요.", 12, MUTED); hint.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(12));
            tools.addView(hint);
        };
        fill[0].run();
        sheet.primary.setText("닫기");
        sheet.setOnDismissListener(d -> { if (!isDestroyed() && tab == 2) showTab(2); });
        sheet.show();
    }
    /**
     * 설정 → 화면 보기 (page "look"): one switch, what it never sees in two footers, the rest one tap away. Without 폰 조작's
     * first step there is no screen to see, so the way there comes first.
     */
    private void pageLook() {
        if (!PhoneService.connected()) { TextView need = ui.banner("다른 앱 화면까지 보려면 폰 조작 1단계(접근성)를 먼저 켜 주세요 ›", WARNING); need.setOnClickListener(v -> openSettings("phone")); Ui.pressable(need, true); add(need, 12); }
        LinearLayout look = ui.group();
        ui.addRow(look, ui.switchRow("화면 보기 허용", "“화면 봐 줘”라고 하면 지금 화면 한 장을 보고 이야기해요", store.screenView(), value -> {
            store.settings.edit().putBoolean("screenView", value).apply();
            store.status(value ? "화면 보기를 켰어요 · 다음 음성 대화부터 적용돼요" : "화면 보기를 껐어요"); store.changed();
        }), false);
        add(look, 0);
        add(ui.footer("작은 음성 버튼 메뉴의 ‘화면 보기’로도 돼요. 계속 보는 영상 공유는 아니에요."), 0);
        add(ui.footer("메인 대화·작업창·AI 브라우저·판서의 음성 대화(GPT·Grok)에서도 돼요. " + Brand.NAME + " 화면은 접근성 없이도 보고, 다른 앱 화면은 접근성이 켜져 있을 때 봐요. GPT 실시간은 화면을 직접 보고, Grok과 GPT 최신 음성은 ChatGPT 연결이 화면을 글로 풀어 전해 줘요."), 0);
        add(ui.footer("잠금 화면은 보지 않고, 캡처를 막은 화면은 검게 보여요. 화면 이미지는 기기에 저장하지 않아요."), 0);
        add(ui.details("자세히", "음성 대화 중 “화면 보면서 이야기하자”라고 하면 하던 폰 조작을 멈추고 화면 보기로 바꿔요. 그다음 “아래로 내려 줘”처럼 지시하면 다시 조작해요. 폰 조작도 허용했다면 화면의 글자·버튼 정보가 부족할 때만 화면 한 장을 함께 봐요. 비밀번호·인증 화면은 보내지 않고, 이 스위치를 끄면 이것도 꺼져요. 폰 조작 1단계(접근성)가 필요하고, 2단계(허용)는 없어도 돼요.\n"
            + "업데이트 뒤 ‘캡처를 허용하지 않았어요’가 나오면 접근성에서 ‘" + Brand.NAME + " 폰 조작’을 껐다가 다시 켜 주세요."), 16);
    }
    /**
     * 설정 → 기억 (page "memory"): the two switches, what is kept behind one row (기억한 것, page
     * "memories"), the backup, and where memory goes, folded. Turning 장기 기억 off redraws the page, so the automatic
     * switch waits, disabled, and says why.
     */
    /**
     * 설정 → 백업 · 복원: the app's data to Google Drive (DriveBackupUi, or the way to connect Drive first) and the memory
     * alone as files (MemoryBackupUi), each saying where its file goes.
     */
    private void pageBackup() {
        add(ui.sectionHeader("Google Drive 백업 · 폰을 바꿀 때"), 0);
        if (store.drive.connected()) add(new DriveBackupUi(this, ui, store).rows(), 0);
        else {
            LinearLayout connect = ui.group();
            ui.addRow(connect, ui.listRow(R.drawable.ic_folder, Ui.BLUE, "Google Drive 연결하기", "연결하면 여기서 바로 백업하고 가져와요", ui.chevron(), () -> openSettings("drive")), true);
            add(connect, 0);
        }
        add(ui.footer("저장 위치: 내 Google Drive 맨 위(내 드라이브)에 ‘들퍼레-백업-날짜-시간.zip’ 파일로 올라가요. drive.google.com이나 Drive 앱에서 ‘들퍼레-백업’으로 찾을 수 있어요."), 0);
        add(ui.details("Drive 백업에 들어가는 것", DriveBackupUi.about()), 0);
        add(ui.sectionHeader("기억만 파일로"), 0);
        add(memoryBackup().section(), 0);
        add(ui.footer("저장 위치: 휴대폰 내장 저장공간 › 들퍼레 › 백업 폴더에 ‘기억-날짜-시간.json’(가져오기용)과 같은 이름의 .md(읽기용)로 저장돼요. 내 파일 앱에서도 열 수 있어요."), 0);
    }
    /**
     * 설정 → 기억 → 먼저 챙기기 (Briefing): the switch, 나에 대한 이해 (read, changed, made again), when 오늘의 제안 comes, and
     * a try now. Gemma 4 makes both on the Gemini key; the cards wait over 메인 대화's message box.
     */
    private void proactiveSection() {
        boolean on = Briefing.on(store);
        add(ui.sectionHeader("먼저 챙기기"), 0);
        LinearLayout group = ui.group();
        ui.addRow(group, ui.switchRow("나를 이해하고 먼저 챙기기", "AI가 나에 대한 노트를 정리하고, 매일 아침 제안을 드려요", on, value -> { Briefing.turn(this, store, value); showTab(2); }), true);
        String note = Briefing.note(store).strip(); long at = Briefing.noteAt(store);
        String first = note.isEmpty() ? (on ? "곧 만들어요 (하루에 한 번 새로 정리해요)" : "켜면 만들어요") : note.replaceAll("(?m)^#+\\s*", "").replaceAll("\\s+", " ");
        if (first.length() > 60) first = first.substring(0, 60) + "…";
        ui.addRow(group, ui.listRow(R.drawable.ic_person, Ui.ORANGE, "나에 대한 이해", first + (at > 0 ? " · " + PagesActivity.when(at, java.time.ZonedDateTime.now()) : ""), ui.chevron(), this::aboutMeSheet), true);
        int minutes = Briefing.minutes(store);
        ui.addRow(group, ui.listRow(R.drawable.ic_calendar, Ui.BLUE, "오늘의 제안 시각", dev.jun.gyeot.core.Proactive.clock(minutes) + " · 알림과 메인 대화에", ui.chevron(), () ->
            new android.app.TimePickerDialog(this, (picker, h, m) -> { Briefing.minutes(this, store, h * 60 + m); showTab(2); }, minutes / 60, minutes % 60, true).show()), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_bulb, Ui.GREEN, "지금 제안 받아 보기", on ? "오늘의 제안을 새로 만들어 메인 대화에 띄워요" : "먼저 위 스위치를 켜 주세요", ui.chevron(), () -> {
            if (!Briefing.on(store)) { android.widget.Toast.makeText(this, "먼저 ‘나를 이해하고 먼저 챙기기’를 켜 주세요.", android.widget.Toast.LENGTH_SHORT).show(); return; }
            android.widget.Toast.makeText(this, "제안을 만드는 중이에요…", android.widget.Toast.LENGTH_SHORT).show();
            android.content.Context app = getApplicationContext();
            new Thread(() -> {
                String said;
                try { said = "오늘의 제안 " + Briefing.morning(app, store, false).size() + "개를 메인 대화에 띄웠어요"; }
                catch (Exception e) { said = e.getMessage() == null ? "제안을 만들지 못했어요." : e.getMessage(); }
                String text = said; runOnUiThread(() -> { if (!isDestroyed()) { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show(); if (text.startsWith("오늘의")) navigateMenu("chat"); } });
            }, "briefing-now").start();
        }), false);
        add(group, 0);
        add(ui.footer("노트와 제안은 기억·최근 대화·작업창·기록을 바탕으로 Gemini 키의 Gemma 4가 만들어요(무료 한도 안). 노트는 메인 대화·음성 비서·작업창이 참고해요."), 0);
        // 하루 돌아보기: each evening, the day's review as a page (페이지 → 하루 돌아보기), with a notification.
        add(ui.sectionHeader("하루 돌아보기"), 0);
        LinearLayout review = ui.group();
        ui.addRow(review, ui.switchRow("저녁마다 하루 돌아보기", on ? "오늘 한 일·남은 일·내일 챙길 것을 페이지로 정리해 알려 드려요" : "먼저 위의 ‘먼저 챙기기’를 켜 주세요", Briefing.reviewOn(store),
            value -> { if (!Briefing.on(store)) { android.widget.Toast.makeText(this, "먼저 ‘나를 이해하고 먼저 챙기기’를 켜 주세요.", android.widget.Toast.LENGTH_SHORT).show(); showTab(2); return; } Briefing.review(this, store, value); showTab(2); }), true);
        int evening = Briefing.reviewMinutes(store);
        ui.addRow(review, ui.listRow(R.drawable.ic_calendar, Ui.PURPLE, "돌아보기 시각", dev.jun.gyeot.core.Proactive.clock(evening), ui.chevron(), () ->
            new android.app.TimePickerDialog(this, (picker, h, m) -> { Briefing.reviewMinutes(this, store, h * 60 + m); showTab(2); }, evening / 60, evening % 60, true).show()), true);
        ui.addRow(review, ui.listRow(R.drawable.ic_pages, Ui.TEAL, "지금 돌아보기", "오늘 지금까지를 정리해 페이지로 열어요", ui.chevron(), () -> {
            android.widget.Toast.makeText(this, "오늘을 정리하는 중이에요…", android.widget.Toast.LENGTH_SHORT).show();
            android.content.Context app = getApplicationContext();
            new Thread(() -> {
                String id = null, why = "";
                try { id = Briefing.evening(app, store, false); } catch (Exception e) { why = e.getMessage() == null ? "정리하지 못했어요." : e.getMessage(); }
                String page = id, reason = why;
                runOnUiThread(() -> { if (isDestroyed()) return;
                    if (page == null) android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_LONG).show();
                    else startActivity(new Intent(this, PagesActivity.class).putExtra(PagesActivity.OPEN, page).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)); });
            }, "review-now").start();
        }), false);
        add(review, 0);
    }
    /** 기억 정리: the plan made first, shown line by line, applied only on 적용 (the notes before kept for 되돌리기). */
    private void tidyMemory() {
        JSONArray notes = store.memories();
        String listed = dev.jun.gyeot.core.Memory.prompt(notes, false);
        if (listed.isEmpty()) { android.widget.Toast.makeText(this, "정리할 기억이 아직 없어요.", android.widget.Toast.LENGTH_SHORT).show(); return; }
        android.widget.Toast.makeText(this, "기억을 살펴보는 중이에요…", android.widget.Toast.LENGTH_SHORT).show();
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String answer = null, why = "";
            try { String key = store.secrets.workKey(); if (key.isBlank()) throw new IllegalStateException("설정 → Gemini API 키를 먼저 넣어 주세요."); answer = GeminiAudioClient.light(key, dev.jun.gyeot.core.Memory.cleanup(listed), "기억을 정리해 주세요."); }
            catch (Exception e) { why = e.getMessage() == null ? "정리하지 못했어요." : e.getMessage(); }
            String plan = answer == null ? null : dev.jun.gyeot.core.Memory.withoutAdd(answer), reason = why;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (plan == null) { android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_LONG).show(); return; }
                java.util.List<String> changes = dev.jun.gyeot.core.Memory.preview(store.memories(), plan);
                if (changes.isEmpty()) { android.widget.Toast.makeText(this, "정리할 게 없어요. 기억이 깔끔해요 👍", android.widget.Toast.LENGTH_SHORT).show(); return; }
                Ui.Sheet sheet = ui.selectionSheet("기억 정리", changes.size() + "가지를 바꿔요 · 적용 전에 확인해 주세요");
                TextView list = ui.text(String.join("\n\n", changes), 14, 400, TEXT); list.setLineSpacing(ui.dp(3), 1); list.setTextIsSelectable(true); ui.add(sheet.body, list, 8);
                sheet.primary.setText("적용");
                sheet.primary.setOnClickListener(v -> {
                    try {
                        store.settings.edit().putString("memoryUndo", store.memories().toString()).apply();
                        int changed = store.changeMemory(memories -> dev.jun.gyeot.core.Memory.apply(memories, plan, System.currentTimeMillis()));
                        android.widget.Toast.makeText(this, "기억 " + changed + "가지를 정리했어요", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { android.widget.Toast.makeText(this, "정리하지 못했어요.", android.widget.Toast.LENGTH_SHORT).show(); }
                    sheet.dismiss(); showTab(2);
                });
                sheet.show();
            });
        }, "memory-tidy").start();
    }
    /** Puts the notes back as they were before the last 기억 정리. */
    private void undoTidy() {
        String saved = store.settings.getString("memoryUndo", ""); if (saved.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("마지막 정리를 되돌릴까요?").setMessage("정리하기 전 기억으로 돌아가요. 그 뒤에 새로 생긴 기억은 사라져요.")
            .setNegativeButton("취소", null).setPositiveButton("되돌리기", (d, w) -> {
                try {
                    JSONArray before = new JSONArray(saved);
                    store.changeMemory(memories -> { while (memories.length() > 0) memories.remove(0); for (int i = 0; i < before.length(); i++) memories.put(before.opt(i)); return before.length(); });
                    store.settings.edit().remove("memoryUndo").apply();
                    android.widget.Toast.makeText(this, "되돌렸어요", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Exception e) { android.widget.Toast.makeText(this, "되돌리지 못했어요.", android.widget.Toast.LENGTH_SHORT).show(); }
                showTab(2);
            }).show();
    }
    /** 나에 대한 이해: the note to read and change (kept as written), or made again now. */
    private void aboutMeSheet() {
        Ui.Sheet sheet = ui.selectionSheet("나에 대한 이해", "AI가 정리한 노트예요 · 고치면 그대로 지켜요");
        EditText field = ui.input("아직 없어요. ‘지금 다시 정리’를 눌러 보세요.", 8, dev.jun.gyeot.core.Proactive.PROFILE_LIMIT);
        field.setText(Briefing.note(store)); ui.add(sheet.body, field, 8);
        ui.add(sheet.body, ui.button("지금 다시 정리", false, () -> {
            android.widget.Toast.makeText(this, "정리하는 중이에요…", android.widget.Toast.LENGTH_SHORT).show();
            android.content.Context app = getApplicationContext();
            new Thread(() -> {
                String made = null, why = "";
                try { made = Briefing.remake(app, store); } catch (Exception e) { why = e.getMessage() == null ? "정리하지 못했어요." : e.getMessage(); }
                String text = made, reason = why;
                runOnUiThread(() -> { if (isDestroyed()) return; if (text != null) { field.setText(text); android.widget.Toast.makeText(this, "새로 정리했어요", android.widget.Toast.LENGTH_SHORT).show(); } else android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_LONG).show(); });
            }, "about-me").start();
        }), 8);
        sheet.primary.setText("저장");
        sheet.primary.setOnClickListener(v -> { Briefing.edit(store, field.getText().toString()); sheet.dismiss(); showTab(2); });
        sheet.show();
    }
    private void pageMemory() {
        add(ui.text("대화에서 알게 된 것과 배운 폰 사용법을 기억해 두고, 다음 대화에서 써요.", 15, 400, MUTED), 4);
        boolean memoryOn = store.memoryOn();
        add(ui.sectionHeader("기억 쓰기"), 0);
        LinearLayout use = ui.group();
        // Turned on again, every 작업창 room picks its notes anew (AppStore.memoryEpoch).
        ui.addRow(use, ui.switchRow("장기 기억 사용", "음성 비서·메인 대화·작업창이 함께 써요", memoryOn, value -> { android.content.SharedPreferences.Editor edit = store.settings.edit().putBoolean("memory", value); if (value) edit.putLong("memoryOnAt", System.currentTimeMillis()); edit.apply(); store.status(value ? "장기 기억을 켰어요 · 다음 대화부터 써요" : "장기 기억을 껐어요 · 저장된 기억은 남아 있어요"); showTab(2); }), false);
        LinearLayout auto = ui.switchRow("대화에서 자동으로 기억하기", memoryOn ? "대화가 끝나면 오래 쓸 만한 것만 골라 적어요" : "장기 기억이 꺼져 있어 쉬고 있어요", store.settings.getBoolean("autoMemory", true),
            value -> store.settings.edit().putBoolean("autoMemory", value).apply());
        if (!memoryOn) { auto.setEnabled(false); auto.getChildAt(0).setEnabled(false); auto.setAlpha(0.45f); }
        ui.addRow(use, auto, false);
        add(use, 0);
        add(ui.footer("“이거 기억해 줘”, “그건 잊어 줘”라고 말해도 돼요. 비밀번호·인증번호·카드번호는 기억하지 않아요."), 0);
        add(ui.details("자동으로 기억하는 방법", "대화가 끝난 뒤 오래 쓸 만한 내용(호칭, 가족, 취향, 진행 중인 일)만 골라 기억하고, 폰 조작으로 한 일은 실제로 누른 단계를 사용법으로 정리해 둬요. 다음에 같은 앱이나 사이트를 다룰 때 그 순서대로 해요."), 0);
        proactiveSection();
        // Counted as the 기억한 것 page lists them.
        JSONArray notes = store.memories(); int about = 0, phone = 0;
        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i); String kind = note == null ? "" : note.optString("kind");
            if (dev.jun.gyeot.core.Memory.ABOUT.equals(kind)) about++; else if (dev.jun.gyeot.core.Memory.PHONE.equals(kind)) phone++;
        }
        add(ui.sectionHeader("기억한 것"), 0);
        LinearLayout kept = ui.group();
        ui.addRow(kept, ui.listRow(R.drawable.ic_bulb, Ui.ORANGE, "기억한 것 보기", about + phone == 0 ? "아직 없어요" : "나에 대해 " + about + "개 · 폰 사용법 " + phone + "개", ui.chevron(), () -> openSettings("memories")), true);
        // 기억 정리: Gemma 4 tidies the notes about the user; what it would change shows first, and the last tidy can be undone.
        ui.addRow(kept, ui.listRow(R.drawable.ic_refresh, Ui.GREEN, "기억 정리 (AI)", "겹치는 기억은 합치고, 지난 일은 지워요 · 바꾸기 전에 보여 드려요", ui.chevron(), this::tidyMemory), true);
        if (!store.settings.getString("memoryUndo", "").isEmpty())
            ui.addRow(kept, ui.listRow(R.drawable.ic_back, Ui.BLUE, "마지막 정리 되돌리기", "정리하기 전 기억으로 돌아가요", ui.chevron(), this::undoTidy), true);
        add(kept, 0);
        // Two lines at 360dp, so it keeps a footer's distance from the group above.
        TextView pinned = ui.quietRow("메인 대화의 고정 기억은 메인 대화 ⋯ → 기억·제약에서 고쳐요 ›", () -> navigateMenu("chat")); pinned.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(4)); add(pinned, 0);
        add(ui.sectionHeader("백업"), 0); add(memoryBackup().section(), 0);
        add(ui.footer("앱을 지우거나 폰을 바꾸면 기억도 사라져요. 가끔 내보내 두세요."), 0);
        add(ui.details("백업에 들어가는 것", "기억·‘나에 대해’·메인 대화 고정 기억을 JSON과 읽기 쉬운 .md로 들퍼레/백업에 저장해요. 내보낸 파일은 파일 앱에서도 열 수 있어요. 가져올 때는 지금 기억을 지우지 않고 합치고, 비밀번호 같은 정보는 빼요."), 0);
        add(ui.details("기억이 보관되는 방식", "기억은 이 기기에만 저장되고, 음성 대화를 시작할 때 Gemini에, 메인 대화에서는 선택한 메인 AI에, 작업창과 예약 작업에서는 그 대화의 AI에 함께 보내져요. 메인 대화와 작업창에는 요청과 관련된 기억부터 들어가요. 작업창은 대화 메뉴의 ‘기억 사용’으로 대화마다, 예약 작업은 예약 편집의 ‘기억 사용’으로 예약마다 끌 수 있어요. 자동 기억은 음성 대화가 끝난 뒤 그 대화를 Gemini에 한 번 더 보내 정리하고, 메인 대화는 몇 번 오갈 때마다 메인 AI로 정리해요. 메인 대화의 자동 기억은 메인 대화 메뉴 → 기억·제약에서 따로 끌 수 있어요."), 16);
    }
    /**
     * 설정 → 기억 → 기억한 것 (page "memories"): writing a note by hand, then what is remembered about the user and the
     * phone know-how, newest first, each opening editMemory; wiping them all comes last, alone, behind a question.
     */
    private void pageMemories() {
        JSONArray notes = store.memories();
        add(ui.sectionHeader("직접 적기"), 0);
        LinearLayout write = ui.group();
        ui.addRow(write, ui.listRow(R.drawable.ic_add, ACCENT, "직접 추가", "기억해 둘 내용을 적어요", ui.chevron(), () -> editMemory(null, dev.jun.gyeot.core.Memory.ABOUT, "", "")), true);
        LinearLayout recipe = ui.listRow(R.drawable.ic_phone, ACCENT, "폰 사용법 직접 적기", "앱이나 사이트에서 일하는 순서를 가르쳐요", ui.chevron(), () -> editMemory(null, dev.jun.gyeot.core.Memory.PHONE, "", ""));
        // Up to two lines, so it is not cut at 360dp.
        TextView how =((Ui.ListRow) recipe).parts.subtitle(); how.setSingleLine(false); how.setMaxLines(2); ui.addRow(write, recipe, true);
        add(write, 0);
        String[][] kinds = {{dev.jun.gyeot.core.Memory.ABOUT, "나에 대해 기억한 것", "아직 없어요"}, {dev.jun.gyeot.core.Memory.PHONE, "배운 폰 사용법", "아직 없어요 · 폰 조작으로 여러 단계 일을 마치면 배워요"}};
        for (String[] kind : kinds) {
            add(ui.sectionHeader(kind[1]), 0);
            LinearLayout group = ui.group(); int shown = 0;
            for (int i = notes.length() - 1; i >= 0; i--) {
                JSONObject note = notes.optJSONObject(i);
                if (note == null || !kind[0].equals(note.optString("kind"))) continue;
                String noteId = note.optString("id"), text = note.optString("text"), app = note.optString("app");
                ui.addRow(group, ui.listRow(0, 0, (app.isBlank() ? "" : "[" + app + "] ") + text, when(note.optLong("updatedAt")), ui.chevron(), () -> editMemory(noteId, kind[0], app, text)), false);
                shown++;
            }
            if (shown == 0) ui.addRow(group, ui.listRow(0, 0, kind[2], null, null, null), false);
            add(group, kind == kinds[kinds.length - 1] ? 16 : 0);
        }
        if (notes.length() == 0) return;
        LinearLayout clear = ui.group();
        ui.addRow(clear, ui.dangerRow("기억 모두 지우기", notes.length() + "개 · 되돌릴 수 없어요", () -> confirm("기억한 것을 모두 지울까요? 되돌릴 수 없어요.", "모두 지우기", () -> {
            try { store.changeMemory(list -> { while (list.length() > 0) list.remove(0); return 0; }); store.status("기억을 모두 지웠어요"); showTab(2); } catch (Exception e) { store.error(e.getMessage()); }
        })), false);
        add(clear, 16);
    }
    /** Edit or delete one note (a phone recipe with its app or site); a null id adds a new one. */
    private void editMemory(String id, String kind, String app, String text) {
        boolean phone = dev.jun.gyeot.core.Memory.PHONE.equals(kind);
        LinearLayout form = ui.column(); form.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0);
        EditText place = phone ? ui.input("앱이나 사이트 · 예: 네이버 블로그 (blog.naver.com)", 1, 80) : null;
        if (place != null) { place.setText(app); form.addView(place); form.addView(new View(this), new LinearLayout.LayoutParams(1, ui.dp(10))); }
        EditText input = phone ? ui.input("순서 · 예: 글쓰기: blog.naver.com → ‘글쓰기’ 누르기 → …", 4, dev.jun.gyeot.core.Memory.RECIPE_LIMIT)
            : ui.input("예: 아침엔 짧게 대답해 주길 원해요", 2, dev.jun.gyeot.core.Memory.TEXT_LIMIT);
        input.setText(text); form.addView(input);
        String noun = phone ? "폰 사용법" : "기억";
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(noun + (id == null ? " 추가" : " 고치기")).setView(form).setNegativeButton("취소", null)
            .setPositiveButton("저장", (d, w) -> {
                String value = input.getText().toString(), where = place == null ? "" : place.getText().toString(); long now = System.currentTimeMillis();
                try {
                    boolean saved = phone ? store.changeMemory(list -> dev.jun.gyeot.core.Memory.learn(list, where, value, id, now)) != null
                        : id == null ? store.changeMemory(list -> dev.jun.gyeot.core.Memory.add(list, dev.jun.gyeot.core.Memory.ABOUT, value, null, now)) != null
                        : store.changeMemory(list -> dev.jun.gyeot.core.Memory.edit(list, id, value, now));
                    if (!saved) store.error("저장하지 못했어요. 비어 있거나 비밀번호 같은 정보는 기억하지 않아요.");
                    else { store.status("기억을 저장했어요 · 다음 대화부터 써요"); showTab(2); }
                } catch (Exception e) { store.error(e.getMessage()); }
            });
        // Deleting asks first; the editor has closed by then, and 취소 keeps the note.
        if (id != null) dialog.setNeutralButton("지우기", (d, w) -> confirm("이 기억을 지울까요?", "지우기", () -> {
            try { store.changeMemory(list -> dev.jun.gyeot.core.Memory.forget(list, id)); store.status("기억을 지웠어요"); showTab(2); } catch (Exception e) { store.error(e.getMessage()); }
        }));
        dialog.show();
    }
    /**
     * 설정 → 나에 대해 (page "persona"): one field, saved as it is typed, with its length. It goes to 음성 비서, 메인 대화 and
     * 작업창, never to the AI browser; the voice's 말투 and the memory are a tap away.
     */
    private void pagePersona() {
        LinearLayout persona = ui.card();
        persona.addView(ui.label("호칭, 하는 일, 원하는 답변 방식을 적어 두면 음성 비서·메인 대화·작업창이 참고해요.", 13, MUTED));
        EditText personaInput = ui.input("예: 답은 짧게, 존댓말로 해 주세요.", 4, GeminiProtocol.PERSONA_LIMIT); personaInput.setText(store.persona()); persona.addView(personaInput);
        TextView length = ui.label(personaInput.length() + " / " + GeminiProtocol.PERSONA_LIMIT, 12, MUTED); length.setGravity(android.view.Gravity.END); persona.addView(length);
        // Saved as typed, like the voice style; a running call offers 지금 적용 at the top.
        personaInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                store.settings.edit().putString("persona", text.toString().trim()).apply(); store.changed();
                length.setText(text.length() + " / " + GeminiProtocol.PERSONA_LIMIT);
            }
        });
        add(persona, 0);
        add(ui.footer("적으면 바로 저장돼요. 대화 중이면 위에 뜨는 ‘지금 적용’으로 바로 반영해요."), 0);
        add(ui.sectionHeader("함께 보기"), 0);
        LinearLayout related = ui.group();
        ui.addRow(related, ui.listRow(R.drawable.ic_wave, Ui.PURPLE, "목소리 말투", "음성 말투는 목소리마다 따로 정해요", ui.chevron(), () -> openSettings("voice")), true);
        ui.addRow(related, ui.listRow(R.drawable.ic_bulb, Ui.ORANGE, "기억", "대화에서 알게 된 것은 기억에 쌓여요", ui.chevron(), () -> openSettings("memory")), true);
        add(related, 0);
        add(ui.footer("‘나에 대해’는 기억 백업에도 들어가요. AI 브라우저에는 보내지 않아요."), 16);
    }
    /**
     * 설정 → 다른 앱 쓰는 동안: the small voice button over other apps (asking first before it sends the user to Android's
     * overlay permission) and the app's battery settings.
     */
    private void pageBackground() {
        TextView about = ui.text("음성 대화를 켠 채 다른 앱으로 가도 계속 들어요. 알림에서 음소거·종료할 수 있어요.", 13, 400, MUTED); about.setPadding(ui.dp(16), 0, ui.dp(16), 0); add(about, 12);
        LinearLayout bubble = ui.group();
        ui.addRow(bubble, ui.switchRow("작은 음성 버튼 표시", Settings.canDrawOverlays(this) ? "대화 중 다른 앱 위에 떠요" : "대화 중 다른 앱 위에 떠요 · 권한이 필요해요", store.bubble(), value -> {
            store.settings.edit().putBoolean("bubble", value).apply();
            if (value && !Settings.canDrawOverlays(this)) confirm("작은 음성 버튼을 다른 앱 위에 띄우려면 ‘다른 앱 위에 표시’를 허용해 주세요.", "설정 열기",
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        }), false);
        add(bubble, 0);
        add(ui.footer("누르면 메뉴(음소거·화면 보기·음성 종료·" + Brand.NAME + " 열기)가 열리고, 길게 누르면 바로 끝나요. 끌어서 옮긴 위치는 기억하고, “그만”, “이제 끊어”라고 말해도 끝나요."), 16);
        LinearLayout battery = ui.group();
        ui.addRow(battery, ui.listRow(R.drawable.ic_settings, Ui.GRAY, "앱 배터리·권한 설정", "뒤에서 음성이 끊기면 확인해 주세요", ui.chevron(), this::openAppSettings), true);
        add(battery, 4);
        TextView edge = ui.quietRow("화면 가장자리 버튼은 ‘바로 부르기’에 있어요 ›", () -> openSettings("quick")); edge.setPadding(ui.dp(16), 0, ui.dp(16), 0); add(edge, 16);
    }
    /** The 고급 rows of 업데이트 (the update token and 토큰 지우기); refreshUpdate redraws them when the token is saved or cleared. */
    private LinearLayout updateToken;
    /**
     * 설정 → 업데이트 (page "update"): the installed or new version and the one action now (updateBox,
     * refreshUpdate), how a file is checked, what to do when the install is refused, and the read-only token in 고급.
     */
    private void pageUpdate() {
        updateBox = ui.column(); updateToken = ui.column(); lastUpdate = ""; refreshUpdate(); add(updateBox, 0);
        add(ui.footer("받은 파일이 맞는지 확인한 뒤 Android 설치 화면을 열어요. 설치는 직접 눌러서 해요."), 0);
        add(ui.details("어떻게 확인하나요", "GitHub 릴리스에서 새 버전을 찾고, 내려받은 파일의 SHA-256·패키지·버전·서명키를 확인해요."), 0);
        // Samsung's Auto Blocker refuses every install from outside the store, updates included; only the user can switch it.
        add(ui.sectionHeader("설치가 안 될 때"), 0);
        LinearLayout refused = ui.group();
        ui.addRow(refused, ui.listRow(R.drawable.ic_lock, Ui.ORANGE, "삼성 ‘보안 위험 자동 차단’", "켜져 있으면 업데이트도 설치되지 않아요", ui.chevron(), this::samsungSheet), true);
        ui.addRow(refused, ui.listRow(R.drawable.ic_download, Ui.GRAY, "릴리스 페이지에서 받기", "GitHub", ui.chevron(), () -> open(dev.jun.gyeot.core.UpdateCheck.RELEASES_PAGE)), true);
        add(refused, 0);
        add(ui.sectionHeader("고급"), 0);
        add(updateToken, 0);
        add(ui.footer("읽기 전용 토큰이에요. 커넥터의 GitHub 토큰과 따로 저장해요."), 16);
        // Opening this page is asking "is there a new one?": look now rather than show the last check.
        store.updater.checkSoon();
    }
    /** 삼성 ‘보안 위험 자동 차단’ (업데이트 → 설치가 안 될 때): why an update will not install while it is on, and the way through. */
    private void samsungSheet() {
        Ui.Sheet sheet = ui.selectionSheet("보안 위험 자동 차단", "삼성 휴대폰");
        TextView why = ui.label("삼성의 ‘보안 위험 자동 차단’이 켜져 있으면 Play 스토어 밖에서 받은 앱은 업데이트도 설치되지 않아요. " + Brand.I + " 이 차단을 대신 풀 수는 없어요.", 15, MUTED);
        why.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(12)); sheet.body.addView(why);
        TextView how = ui.label("설정 → 보안 및 개인정보 보호 → 보안 위험 자동 차단을 잠시 끄고 업데이트를 설치한 뒤, 바로 다시 켜 주세요. 꺼져 있는 동안에는 다른 출처의 앱도 설치될 수 있어요.", 15, TEXT);
        how.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4)); sheet.body.addView(how);
        sheet.body.addView(ui.actions(ui.chip("보안 설정 열기", this::openSecuritySettings)));
        sheet.primary.setText("닫기");
        sheet.show();
    }
    /** Android's security settings (where Samsung keeps 보안 위험 자동 차단), or the settings home when a phone has none. */
    private void openSecuritySettings() {
        try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); }
        catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { store.error("설정 앱을 열지 못했어요."); } }
    }
    /** 설정 → 정보 (page "about"): the version and models, then 문제 해결 (report, media check, phone log, clearing errors) and 도움말. */
    private void pageAbout() {
        String active = store.activeModel() == null ? store.liveModel() : store.activeModel();
        LinearLayout info = ui.group();
        ui.addRow(info, ui.listRow(0, 0, "버전", null, ui.text(versionName(), 16, 400, MUTED), null), false);
        ui.addRow(info, ui.listRow(0, 0, "음성 모델", AppStore.modelLabel(active), null, null), false);
        ui.addRow(info, ui.listRow(0, 0, "전사 모델", AppStore.modelLabel(GeminiProtocol.TRANSCRIBE), null, null), false);
        add(info, 0);
        add(ui.sectionHeader("문제 해결"), 0);
        LinearLayout fix = ui.group();
        // The report keeps the raw model ids, as the API names them.
        ui.addRow(fix, ui.listRow(R.drawable.ic_info, Ui.GRAY, "오류 진단 보고서", "버전·기기·오류 요약을 보고 공유해요", ui.chevron(),
            () -> DiagnosticsExport.show(this, List.of(
                new dev.jun.gyeot.core.DiagnosticReport.Check("접근성 서비스", PhoneService.connected() ? "연결됨" : "연결 안 됨"),
                new dev.jun.gyeot.core.DiagnosticReport.Check("폰 조작 허용", store.phoneControl() ? "켜짐" : "꺼짐"),
                new dev.jun.gyeot.core.DiagnosticReport.Check("현재 폰 조작", PhoneService.gate.armed() ? "켜짐" : "꺼짐"),
                new dev.jun.gyeot.core.DiagnosticReport.Check("화면 보기 허용", store.screenView() ? "켜짐" : "꺼짐"),
                new dev.jun.gyeot.core.DiagnosticReport.Check("음성 대화", store.voiceActive ? "진행 중" : "종료됨"),
                new dev.jun.gyeot.core.DiagnosticReport.Check("음성 모델", active)))), true);
        // Two lines, so the cost note is not cut at 360dp.
        LinearLayout check = ui.listRow(R.drawable.ic_wave, Ui.PURPLE, "미디어 연결 점검", "음성·전사 모델 연결 시간 · 요금이 들 수 있어요", ui.chevron(), this::confirmMediaCheck);
        TextView cost = ((Ui.ListRow) check).parts.subtitle(); cost.setSingleLine(false); cost.setMaxLines(2); ui.addRow(fix, check, true);
        ui.addRow(fix, ui.listRow(R.drawable.ic_history, Ui.TEAL, "최근 폰 조작 기록", "단계별 기록 · 복사해서 보내기", ui.chevron(), this::phoneLogSheet), true);
        add(fix, 16);
        LinearLayout clear = ui.group();
        ui.addRow(clear, ui.dangerRow("오류 기록 지우기", "진단용 오류 요약만 지워요", () -> confirm("진단용 오류 기록을 모두 지울까요?", "지우기", () -> DiagnosticsExport.clear(this))), false);
        add(clear, 0);
        add(ui.sectionHeader("도움말"), 0);
        LinearLayout links = ui.group();
        ui.addRow(links, ui.listRow(R.drawable.ic_globe, Ui.BLUE, "Gemini 요금·사용 제한", "ai.google.dev", ui.chevron(), () -> open("https://ai.google.dev/gemini-api/docs/pricing")), true);
        ui.addRow(links, ui.listRow(R.drawable.ic_info, Ui.INDIGO, "모델별 한도 보기", "aistudio.google.com", ui.chevron(), () -> open("https://aistudio.google.com/rate-limit")), true);
        ui.addRow(links, ui.listRow(R.drawable.ic_download, Ui.GRAY, "릴리스 페이지", "GitHub", ui.chevron(), () -> open(dev.jun.gyeot.core.UpdateCheck.RELEASES_PAGE)), true);
        add(links, 0);
        add(ui.footer("모델별 무료 티어와 프로젝트 할당량이 적용돼요."), 0);
        add(ui.footer(Brand.NAME + " · 개인용 시제품"), 16);
    }
    private void refreshUpdate() {
        dev.jun.gyeot.core.UpdateCheck.Update update = store.updater.available;
        String fingerprint = store.updater.notice + store.updater.busy + store.updater.hasToken() + (update == null ? "" : update.version + update.size) + (store.updater.ready != null && store.updater.ready.isFile());
        if (fingerprint.equals(lastUpdate)) return;
        lastUpdate = fingerprint; updateBox.removeAllViews();
        // The state on top: the version installed, or the new one and its size, then the updater's notice.
        String size = update == null ? "" : String.format(Locale.ROOT, "%.1f MB", update.size / 1_000_000.0);
        updateBox.addView(update == null ? ui.statusCard(R.drawable.ic_download, Ui.BLUE, "지금 버전 " + versionName(), "새 버전은 GitHub 릴리스에서 받아요", null)
            : ui.statusCard(R.drawable.ic_download, Ui.RED, "새 버전 " + update.version + "이 있어요", "지금 " + versionName() + " · " + size, null));
        if (!store.updater.notice.isBlank()) {
            TextView notice = ui.label(store.updater.notice, 13, store.updater.busy ? TEXT : MUTED); notice.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0); updateBox.addView(notice);
        }
        // One action at a time, none while a check or a download runs.
        java.io.File ready = store.updater.ready;
        Button action = store.updater.busy ? null : ready != null && ready.isFile() ? ui.button("설치 화면 열기", true, () -> {
            if (!getPackageManager().canRequestPackageInstalls()) { installUpdate(); return; }
            openInstaller(ready);
        }) : update != null ? ui.button("내려받고 설치하기", true, this::installUpdate) : ui.button("업데이트 확인", false, () -> store.updater.check(false));
        if (action != null) { LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(14); updateBox.addView(action, gap); }
        // The token is saved and cleared on the updater's thread: the 고급 rows follow once it has landed.
        boolean token = store.updater.hasToken();
        if (updateToken == null || Boolean.valueOf(token).equals(updateToken.getTag())) return;
        updateToken.setTag(token); updateToken.removeAllViews();
        LinearLayout key = ui.group();
        ui.addRow(key, ui.listRow(R.drawable.ic_key, Ui.GRAY, "업데이트 확인용 GitHub 토큰", token ? "저장됨 · api.github.com에만 보내요" : "없음 · 비공개 저장소일 때만 필요해요", ui.chevron(), this::enterGithubToken), true);
        updateToken.addView(key);
        if (!token) return;
        LinearLayout clear = ui.group();
        ui.addRow(clear, ui.dangerRow("토큰 지우기", "업데이트 확인용 토큰만 지워요", () -> confirm("저장된 GitHub 토큰을 지울까요?", "지우기", () -> { store.updater.clearToken(); showTab(2); })), false);
        LinearLayout.LayoutParams apart = new LinearLayout.LayoutParams(-1, -2); apart.topMargin = ui.dp(16); updateToken.addView(clear, apart);
    }
    /** The update token, typed only here (Ui.secretSheet): AppUpdater checks its form and keeps it in SecretStore; a refusal is the field's error. */
    private void enterGithubToken() {
        Ui.Sheet sheet = ui.secretSheet("GitHub 토큰 (업데이트 확인용)", "읽기 전용 토큰이에요 · 커넥터의 GitHub 토큰과 따로 저장해요", "github_pat_로 시작하는 토큰", 300, "저장", (shown, field) -> {
            try { store.updater.saveToken(field.getText().toString()); }
            catch (Exception e) { field.setError(e.getMessage() == null ? "토큰을 저장하지 못했어요." : e.getMessage()); return; }
            shown.dismiss(); showTab(2);
        });
        // Even lines at 360dp: 「GitHub 토큰」 over 「(업데이트 확인용)」, not a break inside the parentheses.
        sheet.heading.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);
        TextView how = ui.label("GitHub → Settings → Developer settings → Fine-grained tokens → Generate new token\n· Repository access: Only select repositories → alibowbow/myaide\n· Permissions: Contents → Read-only\n이 기기에 암호화해 보관해요.", 12, MUTED);
        how.setPadding(ui.dp(4), 0, ui.dp(4), 0); sheet.body.addView(how);
        sheet.body.addView(ui.actions(ui.chip("토큰 만들기", () -> open("https://github.com/settings/personal-access-tokens/new"))));
        sheet.show();
    }
    /** Android asks once per app before it may hand APKs to the installer ("이 출처 허용"). */
    private void installUpdate() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            confirm(Brand.I + " 내려받은 업데이트를 설치 화면으로 넘기려면 ‘이 출처 허용’을 한 번 켜 주세요. 켠 뒤 돌아와서 다시 누르면 돼요.", "설정 열기",
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))));
            return;
        }
        // Installing replaces the app: a running call, phone job or work stops with it.
        boolean busy = store.voiceActive || store.recording || !store.workLive.isEmpty() || store.tasks.pendingCount() > 0;
        Runnable go = () -> store.updater.install(apk -> runOnUiThread(() -> openInstaller(apk)));
        if (!busy) { go.run(); return; }
        confirm("업데이트 설치 중에는 대화·녹음·작업이 멈춰요. 복구 기록이 있는 작업은 앱을 다시 열면 읽기 전용으로 이어서 확인해요. 변경·코드·이미지 작업은 자동 반복하지 않아요. 지금 설치할까요?", "설치", go);
    }
    /**
     * Android's own package installer takes the verified file, the way browsers and file managers hand APKs over (as Mobile
     * Codex does). Samsung's 보안 위험 자동 차단 refused the app's own installer session; this path is the one it lets through.
     */
    @SuppressWarnings("deprecation") // ACTION_INSTALL_PACKAGE: still Android's installer entry for a single APK file.
    private void openInstaller(java.io.File apk) {
        if (!store.appVisible || isFinishing() || isDestroyed()) { store.updater.notice("내려받고 확인했어요 · " + Brand.EURO + " 돌아와 ‘설치 화면 열기’를 눌러 주세요."); return; }
        Uri uri = UpdateFiles.uri(apk);
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true);
        install.setClipData(ClipData.newRawUri(Brand.NAME + " update", uri));
        try { startActivityForResult(install, INSTALL_UPDATE); }
        catch (Exception e) { store.updater.notice("Android 설치 화면을 열지 못했어요. 릴리스 페이지에서 받아 설치해 주세요."); }
    }
    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (drive().onResult(request, result, data)) return;
        if (request == PICK_FILES) { if (result == RESULT_OK && data != null) addPicked(data); return; }
        if (request == PICK_MAIN_FILES) { if (result == RESULT_OK && data != null) addMainPicked(data); return; }
        if (request == PICK_VIDEO_SOURCE) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData(); store.status("영상으로 만들 사진을 준비하고 있어요…");
                new Thread(() -> {
                    try {
                        JSONObject entry = VideoSources.importPhoto(this, store, uri);
                        runOnUiThread(() -> { if (!isDestroyed()) { composerVideoSource = entry.optString("id"); showTab(3); workVideoOptions(); } });
                    } catch (Exception e) { store.error(e.getMessage()); }
                }, "gyeot-video-source").start();
            }
            return;
        }
        if (request != INSTALL_UPDATE) return;
        // A finished install replaces this process before an OK can arrive; a result here means it did not go through.
        if (result == RESULT_OK) { store.updater.ready = null; store.updater.notice("업데이트를 설치했어요."); return; }
        int code = data == null ? 0 : data.getIntExtra("android.intent.extra.INSTALL_RESULT", 0);
        store.updater.notice(result == RESULT_CANCELED && code == 0
            ? "설치를 취소했어요. 다시 하려면 ‘설치 화면 열기’를 누르세요. 삼성 ‘보안 위험 자동 차단’ 안내가 떴다면 아래 ‘설치가 안 될 때’를 봐 주세요."
            : "설치하지 못했어요 (Android 코드 " + code + "). 다시 시도하거나 릴리스 페이지에서 받아 설치해 주세요.");
    }
    /** Rebuilds only the ChatGPT connection block when its state changes, so other settings inputs keep their text. */
    private void refreshOpenAi() {
        JSONObject who = store.openai.describe();
        String fingerprint = who + "\u0000" + store.openai.notice + store.openai.signingIn + store.openai.modelsStatus() + store.openai.models() + store.openai.model() + "\u0000" + store.reasoningEffort() + "\u0000" + store.openai.speed(store.openai.model())
            + store.openai.refused("code_interpreter") + store.openai.refused("image_generation") + store.openai.refused("mcp")
            + store.codexImages.describe() + store.codexImages.notice + store.codexImages.signingIn;
        if (fingerprint.equals(lastOpenAi)) return;
        lastOpenAi = fingerprint; openAiBox.removeAllViews();
        String connectionMode = who.optString("mode"), account = who.optString("label");
        boolean signingIn = store.openai.signingIn, key = connectionMode.equals("key"), plan = connectionMode.equals("chatgpt") && who.optBoolean("plan");
        boolean planMissing = connectionMode.equals("chatgpt") && !plan;
        // Where the connection stands; a sign-in under way says so whatever was connected before.
        String state = signingIn ? "로그인하는 중…" : connectionMode.isEmpty() ? "연결 안 됨" : planMissing ? "확인이 필요해요" : "연결됨";
        String detail = signingIn ? "브라우저에서 로그인을 마쳐 주세요" : connectionMode.isEmpty() ? "구독으로 로그인하거나 API 키로 연결해요" : key ? "OpenAI " + account
            : plan ? "구독 로그인" + (account.isBlank() ? "" : " · " + account) : "‘ChatGPT 플랜 사용’을 허용하지 않았어요";
        openAiBox.addView(ui.statusCard(R.drawable.ic_sparkle, Ui.MINT, state, detail, !signingIn && planMissing ? "확인 필요" : null));
        String notice = who.optString("error", store.openai.notice);
        if (!notice.isBlank()) { TextView line = ui.label(notice, 13, signingIn ? TEXT : ERROR); line.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0); openAiBox.addView(line); }
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2); first.topMargin = ui.dp(16);
        if (signingIn) openAiBox.addView(ui.actions(ui.chip("로그인 취소", () -> { store.openai.cancelSignIn(); store.changed(); })));
        else if (connectionMode.isEmpty()) {
            openAiBox.addView(ui.button("ChatGPT로 로그인", true, store.openai::signIn), first);
            TextView plain = ui.label("구독 사용량으로 써요. 로그인 화면에서 ‘ChatGPT 플랜 사용’을 허용해 주세요.", 12, MUTED); plain.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0); openAiBox.addView(plain);
            TextView byKey = ui.quietRow("API 키로 연결하기 · OpenAI Platform 요금 ›", this::connectOpenAiKey); byKey.setPadding(ui.dp(16), 0, ui.dp(16), 0); openAiBox.addView(byKey);
        } else if (planMissing) openAiBox.addView(ui.button("ChatGPT 다시 로그인", true, store.openai::signIn), first);
        if (!connectionMode.isEmpty()) {
            store.openai.refreshModelsIfDue();
            openAiBox.addView(ui.sectionHeader("음성 비서가 쓸 모델"));
            LinearLayout choice = ui.group();
            JSONArray models = store.openai.models();
            if (models.length() > 0) {
                java.util.List<String> ids = new java.util.ArrayList<>(), names = new java.util.ArrayList<>();
                for (int i = 0; i < models.length(); i++) { JSONObject item = models.optJSONObject(i); ids.add(item.optString("id")); names.add(item.optString("name")); }
                String saved = store.openai.model();
                if (!ids.contains(saved)) { ids.add(0, saved); names.add(0, saved.isBlank() ? "모델을 선택해 주세요" : saved + " · 현재 목록에 없음"); }
                ui.addRow(choice, ui.choiceRow("모델", "ChatGPT 모델", "두뇌·분석·검색에 써요", names.toArray(new String[0]), ids.indexOf(saved),
                    position -> { String id = ids.get(position); if (!id.isBlank() && !id.equals(store.openai.model())) store.openai.model(id); }), false);
            }
            String model = store.openai.model();
            if (!model.isBlank()) {
                Reasoning.Profile profile = store.openai.reasoningProfile(model);
                String[] efforts = profile.values(); int selected = 0;
                String effort = store.openai.reasoningEffort(model);
                for (int i = 0; i < efforts.length; i++) if (efforts[i].equals(effort)) selected = i;
                if (efforts.length > 1) ui.addRow(choice, ui.choiceRow("추론 레벨", "추론 레벨", "모델마다 따로 기억해요 · 높을수록 깊게 생각하지만 느리고 사용량이 늘어요", profile.gptLabels(), selected,
                    position -> store.openai.reasoningEffort(model, efforts[position])), false);
                else {
                    String fixed = profile.known ? "모델 기본값" : "모델 기본값 · 모델 새로고침으로 다시 확인해요";
                    LinearLayout info = ui.listRow(0, 0, "추론 레벨", fixed, null, null); info.setContentDescription("추론 레벨, " + fixed); info.setScreenReaderFocusable(true);
                    ui.addRow(choice, info, false);
                }
                ui.addRow(choice, ui.valueRow("속도", "fast".equals(store.openai.speed(model)) ? "Fast" : "Standard", this::chatgptSpeedSheet), false);
            }
            if (choice.getChildCount() > 0) openAiBox.addView(choice);
            openAiBox.addView(ui.footer("두뇌·분석·검색에 써요. 메인 대화·작업창·녹음 요약은 각 화면에서 따로 골라요."));
            if (models.length() == 0) openAiBox.addView(ui.footer("모델 목록이 아직 없어요. ‘모델 새로고침’을 눌러 주세요."));
            String listed = store.openai.modelsStatus();
            if (!OpenAiAccount.MODELS_FRESH.equals(listed)) openAiBox.addView(ui.footer(listed));
            // What this connection turned down in 작업창, said once here instead of under every answer.
            boolean noCode = store.openai.refused("code_interpreter"), noDrawing = !store.openai.usesPlan() && !canDraw();
            if (noCode || noDrawing) {
                // Only Claude's code sends files to the phone besides ChatGPT's; nobody draws without an image connection.
                List<String> said = new ArrayList<>();
                if (noCode) said.add(store.openai.usesPlan() ? "이 연결로는 작업창 코드 실행(파일 만들기)을 못 해요. 작업창에서 Claude를 고르거나 OpenAI API 키로 연결하면 만들어요."
                    : "이 연결로는 작업창 코드 실행(파일 만들기)을 못 해요. 작업창에서 Claude를 고르면 만들어요.");
                if (noDrawing) said.add("그림은 아래 ‘이미지용 Codex로 로그인’을 하면 그려요.");
                if (store.openai.usesPlan() && store.codexImages.connected()) said.add("그림은 아래 ‘이미지용 Codex’ 연결로 그려요.");
                LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(12);
                openAiBox.addView(ui.banner(String.join(" ", said), WARNING), gap);
            }
        }
        JSONObject imageAccount = store.codexImages.describe();
        boolean imageConnected = imageAccount.optBoolean("connected");
        if (!signingIn || imageConnected || store.codexImages.signingIn) {
            openAiBox.addView(ui.sectionHeader("그림과 사용 한도"));
            if (imageConnected) {
                String label = imageAccount.optString("label");
                LinearLayout codex = ui.group();
                ui.addRow(codex, ui.listRow(0, 0, "이미지용 Codex", "연결됨" + (label.isBlank() ? "" : " · " + label), ui.chevron(), this::codexSheet), false);
                openAiBox.addView(codex);
            }
            if (store.codexImages.signingIn) openAiBox.addView(ui.actions(ui.chip("이미지 로그인 취소", () -> { store.codexImages.cancelSignIn(); store.changed(); })));
            else if (!imageConnected) {
                TextView again = ui.label("ChatGPT를 구독으로 로그인했다면 그림용으로 한 번 더 로그인해 주세요.", 12, MUTED); again.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(10)); openAiBox.addView(again);
                openAiBox.addView(ui.button("이미지용 Codex로 로그인", false, store.codexImages::signIn));
            }
            String imageNotice = imageAccount.optString("error", store.codexImages.notice);
            if (!imageNotice.isBlank()) { TextView line = ui.label(imageNotice, 13, imageConnected ? ACCENT : TEXT); line.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0); openAiBox.addView(line); }
            openAiBox.addView(ui.footer("그림 만들기·고치기와 ‘사용량 · 남은 한도’의 5시간·주간 한도 보기에 써요. 구독 계정의 사용 한도가 적용돼요."));
        }
        if (!connectionMode.isEmpty()) {
            openAiBox.addView(ui.sectionHeader("연결 관리"));
            LinearLayout manage = ui.group();
            ui.addRow(manage, ui.listRow(0, 0, "모델 새로고침", "최신 모델 목록을 다시 받아요", null, store.openai::refreshModelsAsync), false);
            ui.addRow(manage, ui.listRow(0, 0, "AI 역할 정하기", "두뇌로 ChatGPT를 쓸지 골라요", ui.chevron(), () -> openSettings("ai")), false);
            // Not while a sign-in is under way, so it cannot replace a key saved meanwhile. Without the plan the button above is the fix.
            if (!signingIn && key) {
                ui.addRow(manage, ui.listRow(0, 0, "다른 API 키로 연결", "새 키로 바꿔요", ui.chevron(), this::connectOpenAiKey), false);
                ui.addRow(manage, ui.listRow(0, 0, "ChatGPT 로그인으로 바꾸기", "구독 사용량으로 써요", null, store.openai::signIn), false);
            } else if (!signingIn) {
                if (plan) ui.addRow(manage, ui.listRow(0, 0, "ChatGPT 다시 로그인", "계정을 바꾸거나 플랜 사용을 다시 허용해요", null, store.openai::signIn), false);
                ui.addRow(manage, ui.listRow(0, 0, "API 키로 바꾸기", "로그인 대신 OpenAI API 키로 연결해요", ui.chevron(), this::connectOpenAiKey), false);
            }
            openAiBox.addView(manage);
            LinearLayout off = ui.group();
            ui.addRow(off, ui.dangerRow("연결 해제", "진행 중인 분석은 실패로 끝날 수 있어요",
                () -> confirm("이 기기의 ChatGPT 연결을 해제할까요? 진행 중인 분석 작업은 실패로 끝날 수 있어요.", "해제", store.openai::disconnect)), false);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(16); openAiBox.addView(off, gap);
        }
        openAiBox.addView(ui.sectionHeader("고급"));
        openAiBox.addView(ui.actions(ui.chip("모델 목록 진단", () -> GptCatalogDiagnosticsUi.show(this, store.openai))));
        openAiBox.addView(ui.footer("이 기기에 저장된 모델 목록 정보만 보여 줘요. 아무 데도 보내지 않아요."));
    }
    /** 속도 of the voice assistant's GPT model: GptSpeedUi as it is, in its own sheet; a choice closes it. */
    private void chatgptSpeedSheet() {
        String model = store.openai.model(); if (model.isBlank()) return;
        Ui.Sheet sheet = ui.selectionSheet("속도", "두뇌·분석·검색 요청에 써요 · 추론 레벨과 따로 정해요");
        GptSpeedUi.add(this, ui, sheet.body, store.openai, model, store.openai.speed(model), value -> {
            try { store.openai.speed(model, value); } catch (Exception e) { store.error(e.getMessage()); }
            sheet.dismiss();
        });
        sheet.primary.setText("닫기"); sheet.show();
    }
    /** 이미지용 Codex once connected: sign in again, or disconnect behind a confirm. */
    private void codexSheet() {
        String label = store.codexImages.describe().optString("label");
        Ui.Sheet sheet = ui.selectionSheet("이미지용 Codex", "연결됨" + (label.isBlank() ? "" : " · " + label));
        LinearLayout again = ui.group();
        ui.addRow(again, ui.listRow(0, 0, "다시 로그인", "이미지용 Codex 로그인을 새로 해요", null, () -> { sheet.dismiss(); store.codexImages.signIn(); }), false);
        sheet.body.addView(again);
        LinearLayout off = ui.group();
        ui.addRow(off, ui.dangerRow("연결 해제", "이 기기의 이미지용 Codex 연결을 지워요", () -> {
            sheet.dismiss(); confirm("이 기기의 이미지용 Codex 연결을 해제할까요?", "해제", store.codexImages::disconnect);
        }), false);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(16); sheet.body.addView(off, gap);
        sheet.primary.setText("닫기"); sheet.show();
    }
    private String drawingUnavailable() {
        return store.openai.usesPlan() ? "설정 → ChatGPT에서 ‘이미지용 Codex로 로그인’을 눌러 이미지 생성을 연결해 주세요."
            : "이 OpenAI API 키로는 그림을 그릴 수 없어요. 설정 → ChatGPT에서 이미지 생성 권한이 있는 키를 연결해 주세요.";
    }
    /** Plan sign-ins draw with Codex; API keys keep their existing image tools and API. */
    private boolean canDraw() {
        return store.codexImages.connected() || (!store.openai.usesPlan()
            && (!store.openai.refused("image_generation") || !store.openai.refused(dev.jun.gyeot.core.Drawing.OPENAI_REFUSAL)));
    }
    /** An OpenAI API key, typed only in this sheet; saving it replaces a ChatGPT sign-in. Closing the sheet saves nothing. */
    private void connectOpenAiKey() {
        boolean replaces = store.openai.describe().optString("mode").equals("chatgpt");
        Ui.Sheet sheet = ui.secretSheet(replaces ? "OpenAI API 키로 바꾸기" : "OpenAI API 키로 연결", "키는 이 기기에 암호화해 보관해요", "sk-로 시작하는 OpenAI API 키", 300, "연결", (s, field) -> {
            try { store.openai.saveApiKey(field.getText().toString()); }
            catch (Exception e) { field.setError(e.getMessage() == null ? "API 키를 저장하지 못했어요." : e.getMessage()); return; }
            s.dismiss(); store.status("OpenAI API 키를 연결했어요");
        });
        sheet.body.addView(ui.label("API 키 사용량은 OpenAI Platform 계정으로 별도 과금돼요.", 12, MUTED));
        if (replaces) { TextView note = ui.label("저장하면 ChatGPT 로그인 연결은 이 키로 바뀌어요.", 12, MUTED); note.setPadding(0, ui.dp(6), 0, 0); sheet.body.addView(note); }
        sheet.show();
    }
    /** 연결 확인 asks first, because each check opens a real Live connection on the key; never during a voice conversation. */
    private void geminiCheck(TextView probe, LinearLayout probeFix) {
        if (store.voiceActive) { store.error("음성 대화를 끝낸 뒤 확인해 주세요."); return; }
        confirm("Gemini Live에 모델마다 한 번씩 짧게 연결하고, 검색 담당으로 한 번 검색해요. 말이나 글은 보내지 않지만 요청 한도에 잡히고 요금이 들 수 있어요.", "확인 시작", () -> testConnection(probe, probeFix));
    }
    /** Opens each model's connection just until setup completes, so key, quota and model access problems show up before a real call. */
    private void testConnection(TextView result, LinearLayout fix) {
        String key;
        try { key = store.secrets.get(); } catch (Exception e) { show(result, "저장된 키를 읽지 못했어요. 키를 다시 저장해 주세요."); return; }
        if (key.isBlank()) { show(result, "먼저 API 키를 저장해 주세요."); return; }
        closeProbes(); fix.removeAllViews();
        // Every offered Live model, then the transcriber: {mode, model id, label}.
        List<String[]> targets = new ArrayList<>();
        for (String[] model : GeminiProtocol.LIVE_MODELS) targets.add(new String[]{ASSISTANT, model[0], model[1]});
        targets.add(new String[]{INTERPRETER, GeminiProtocol.TRANSLATE, "3.5 Live Translate (통역)"});
        targets.add(new String[]{DICTATION, GeminiProtocol.TRANSCRIBE, "3.5 Transcribe (자막·받아쓰기)"});
        // One line per target, plus the search provider check at the end.
        String[] lines = new String[targets.size() + 1]; boolean[] ok = new boolean[targets.size()];
        for (int i = 0; i < targets.size(); i++) lines[i] = targets.get(i)[2] + ": 대기 중";
        lines[targets.size()] = searchCheckLabel() + ": 대기 중";
        probeNext(key, targets, 0, WITH_SEARCH, null, lines, ok, result, fix);
    }
    /** 미디어 연결 점검: asks first, because each check opens a real Live connection on the user's key. */
    private void confirmMediaCheck() {
        if (store.voiceActive) { android.widget.Toast.makeText(this, "음성 대화를 끝낸 뒤 점검해 주세요.", android.widget.Toast.LENGTH_SHORT).show(); return; }
        if (!store.secrets.hasKey()) { android.widget.Toast.makeText(this, "먼저 설정에서 Gemini API 키를 저장해 주세요.", android.widget.Toast.LENGTH_SHORT).show(); return; }
        confirm("Gemini Live에 음성 모델과 전사 모델로 한 번씩 짧게 연결해서 이용 권한과 연결 시간을 재요. 말이나 글은 보내지 않지만 요청 한도에 잡히고 요금이 들 수 있어요. 자동으로 다시 시도하지 않아요.", "점검", this::mediaCheck);
    }
    /** One Live setup per model, one at a time: model id, result and setup time go to the dialog and the 오류 진단 보고서. */
    private void mediaCheck() {
        String key;
        try { key = store.secrets.get(); } catch (Exception e) { key = ""; }
        if (key.isBlank() || store.voiceActive) return;
        String voiceModel = GeminiProtocol.liveModel(store.activeModel() == null ? store.liveModel() : store.activeModel());
        List<String[]> targets = List.of(new String[]{ASSISTANT, voiceModel, "Gemini Live 음성"}, new String[]{DICTATION, GeminiProtocol.TRANSCRIBE, "Gemini 전사"});
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        TextView view = new TextView(builder.getContext()); view.setText("확인 중…"); view.setTextSize(14); view.setTextIsSelectable(true);
        int padding = ui.dp(20); view.setPadding(padding, padding / 2, padding, 0);
        int run = ++mediaRun; List<GeminiSession> sessions = new ArrayList<>();
        AlertDialog dialog = builder.setTitle("미디어 연결 점검").setView(view).setPositiveButton("닫기", null).create();
        dialog.setOnDismissListener(d -> { if (run == mediaRun) mediaRun++; for (GeminiSession session : sessions) session.close(); });
        dialog.show();
        mediaNext(key, targets, 0, new ArrayList<>(), view, run, sessions);
    }
    private void mediaNext(String key, List<String[]> targets, int index, List<dev.jun.gyeot.core.DiagnosticReport.Check> results, TextView view, int run, List<GeminiSession> sessions) {
        if (isDestroyed() || run != mediaRun) return;
        if (index == targets.size()) {
            results.add(new dev.jun.gyeot.core.DiagnosticReport.Check("점검 시각", java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).format(java.time.ZonedDateTime.now())));
            DiagnosticsExport.mediaChecked(results);
            view.setText(mediaText(results) + "\n\n오류 진단 보고서에도 넣었어요."); return;
        }
        String[] target = targets.get(index);
        GeminiProtocol.Options options = new GeminiProtocol.Options(); options.model = target[1]; options.search = false;
        long started = android.os.SystemClock.elapsedRealtime();
        GeminiSession[] self = new GeminiSession[1];
        java.util.function.Consumer<String> done = result -> runOnUiThread(() -> {
            if (isDestroyed() || run != mediaRun) return;
            results.add(new dev.jun.gyeot.core.DiagnosticReport.Check(target[2], target[1] + " · " + result + " · " + String.format(Locale.ROOT, "%,d", android.os.SystemClock.elapsedRealtime() - started) + "ms"));
            view.setText(mediaText(results) + "\n확인 중…");
            mediaNext(key, targets, index + 1, results, view, run, sessions);
        });
        self[0] = new GeminiSession(key, target[0], options, new GeminiSession.Listener() {
            @Override public void ready() { self[0].close(); done.accept("허용됨"); }
            @Override public void failed(String reason, boolean retryable) { done.accept(GeminiProtocol.failureKind(reason)); }
        });
        sessions.add(self[0]); probes.add(self[0]); self[0].connect();
    }
    private static String mediaText(List<dev.jun.gyeot.core.DiagnosticReport.Check> results) {
        StringBuilder text = new StringBuilder();
        for (dev.jun.gyeot.core.DiagnosticReport.Check line : results) text.append(text.length() == 0 ? "" : "\n").append(line.name()).append(": ").append(line.value());
        return text.toString();
    }
    private static final int WITH_SEARCH = 0, WITHOUT_SEARCH = 1, SEARCH_AGAIN = 2;
    /** startActivityForResult request for Android's package installer. */
    private static final int INSTALL_UPDATE = 41;
    /**
     * One model at a time, so free-tier concurrent-session limits never fail a check that would pass alone. A quota error with
     * search is only blamed on search when the same model then connects without search and fails with search a second time.
     */
    private void probeNext(String key, List<String[]> targets, int index, int step, String refusal, String[] lines, boolean[] ok, TextView result, LinearLayout fix) {
        if (isDestroyed()) return;
        if (index == targets.size()) { probeSearch(key, targets, lines, ok, result, fix); return; }
        String[] target = targets.get(index);
        boolean live = target[0].equals(ASSISTANT), searched = live && store.searchWanted() && step != WITHOUT_SEARCH;
        lines[index] = target[2] + (step == WITHOUT_SEARCH ? ": Google 검색 없이 다시 확인 중…" : step == SEARCH_AGAIN ? ": Google 검색 한 번 더 확인 중…" : ": 확인 중…");
        show(result, String.join("\n", lines));
        GeminiProtocol.Options options = store.options(false, target[1]); options.search = searched;
        int run = probeRun;
        Runnable next = () -> main.postDelayed(() -> probeNext(key, targets, index + 1, WITH_SEARCH, null, lines, ok, result, fix), 1500);
        GeminiSession[] self = new GeminiSession[1];
        self[0] = new GeminiSession(key, target[0], options, new GeminiSession.Listener() {
            @Override public void ready() {
                self[0].close();
                runOnUiThread(() -> {
                    if (isDestroyed() || run != probeRun) return;
                    ok[index] = true;
                    if (live) store.clearExhausted(target[1]);
                    // The model works without search: ask once more with search, after a pause so a per-minute limit can pass.
                    if (step == WITHOUT_SEARCH) { main.postDelayed(() -> probeNext(key, targets, index, SEARCH_AGAIN, refusal, lines, ok, result, fix), 2000); return; }
                    if (searched) store.retrySearch(target[1]);
                    lines[index] = target[2] + (searched ? ": 연결 성공 ✓ (Google 검색 포함)" : ": 연결 성공 ✓");
                    show(result, String.join("\n", lines)); next.run();
                });
            }
            @Override public void failed(String reason, boolean retryable) {
                runOnUiThread(() -> {
                    if (isDestroyed() || run != probeRun) return;
                    boolean quota = GeminiProtocol.quotaError(reason);
                    if (step == WITH_SEARCH && searched && quota) { main.postDelayed(() -> probeNext(key, targets, index, WITHOUT_SEARCH, reason, lines, ok, result, fix), 2000); return; }
                    if (step == SEARCH_AGAIN) {
                        // Without search it connected, with search it failed twice: Google refuses search for this model on this key.
                        if (quota) store.blockSearch(target[1], refusal);
                        lines[index] = target[2] + ": 연결 성공 ✓ — 단, Google 검색은 " + (quota ? "이 키에서 거부돼요" : "확인하지 못했어요") + " (Google: " + brief(quota ? refusal : reason) + ")";
                    } else {
                        // The model fails without search too, so search is not the problem.
                        if (step == WITHOUT_SEARCH) store.retrySearch(target[1]);
                        lines[index] = target[2] + ": " + GeminiProtocol.explain(reason) + (step == WITHOUT_SEARCH ? " — 검색과 상관없이 모델 자체의 한도·권한 문제예요." : "");
                    }
                    show(result, String.join("\n", lines)); next.run();
                });
            }
        });
        probes.add(self[0]); self[0].connect();
    }
    private String searchCheckLabel() { String provider = store.searchProvider(); return "검색 담당 · " + ("chatgpt".equals(provider) ? "ChatGPT 웹 검색" : "claude".equals(provider) ? "Claude 웹 검색" : "Google 검색 대행"); }
    /** One real search through the chosen provider, the same path search_web takes when a Live model cannot ground itself. */
    private void probeSearch(String key, List<String[]> targets, String[] lines, boolean[] ok, TextView result, LinearLayout fix) {
        int run = probeRun, line = lines.length - 1; String provider = store.searchProvider(); boolean gpt = !"gemini".equals(provider); String label = searchCheckLabel();
        if (!store.searchWanted()) { lines[line] = label + ": 검색이 꺼져 있어요"; show(result, String.join("\n", lines)); suggestModel(targets, ok, fix); return; }
        lines[line] = label + ": 확인 중…"; show(result, String.join("\n", lines));
        String clock = GeminiProtocol.clock(java.time.ZonedDateTime.now());
        new Thread(() -> {
            String outcome;
            try {
                if (gpt) {
                    OpenAiClient.Result found = "claude".equals(provider)
                        ? ClaudeClient.complete(store.claude, store.claude.model(), search -> OpenAiProtocol.searchInstructions(clock), "오늘 날씨", true, "low", () -> {}, new java.util.concurrent.atomic.AtomicReference<>())
                        : OpenAiClient.complete(store.openai, search -> OpenAiProtocol.searchInstructions(clock), "오늘 날씨", true, () -> {}, new java.util.concurrent.atomic.AtomicReference<>());
                    outcome = found.searchRefusal != null ? "웹 검색이 거부됐어요 (" + brief(found.searchRefusal) + ")" : "성공 ✓ (출처 " + found.sources.length() + "개)";
                    if (!found.speedNotice.isBlank()) outcome += " · " + found.speedNotice;
                } else {
                    GeminiSearch.Result found = GeminiSearchClient.search(store, key, "오늘 날씨");
                    outcome = (found.searched ? "성공 ✓ (" : "답은 왔지만 검색하지 않았어요 (") + GeminiSearchClient.model(store) + ", 출처 " + found.sources.length() + "개)";
                }
            } catch (Exception error) { outcome = error instanceof java.io.IOException ? Net.explain(error) : String.valueOf(error.getMessage()); }
            String text = outcome;
            runOnUiThread(() -> {
                if (isDestroyed() || run != probeRun) return;
                lines[line] = label + ": " + text; show(result, String.join("\n", lines)); suggestModel(targets, ok, fix);
            });
        }, "gyeot-search-check").start();
    }
    private static String brief(String text) {
        String value = text == null ? "" : text.strip();
        return value.isEmpty() ? "자세한 내용 없음" : value.length() > 120 ? value.substring(0, 120) + "…" : value;
    }
    /** Offers the first working Live model when the selected one failed. */
    private void suggestModel(List<String[]> targets, boolean[] ok, LinearLayout fix) {
        String selected = store.liveModel(); boolean selectedOk = false; String[] working = null;
        for (int i = 0; i < targets.size(); i++) {
            if (!targets.get(i)[0].equals(ASSISTANT)) continue;
            if (targets.get(i)[1].equals(selected)) selectedOk = ok[i];
            else if (ok[i] && working == null) working = targets.get(i);
        }
        if (selectedOk) return;
        if (working == null) { fix.addView(ui.label("이 키로 쓸 수 있는 Live 모델을 찾지 못했어요. ‘모델별 한도 보기’에서 한도를 확인하거나 결제를 연결해 주세요.", 13, ERROR)); return; }
        String[] chosen = working;
        fix.addView(ui.actions(ui.chip("‘" + chosen[2] + "’로 바꾸기", () -> {
            store.settings.edit().putString("liveModel", chosen[1]).apply(); store.error = ""; store.status("음성 모델을 " + chosen[2] + "로 바꿨어요"); showTab(2);
        })));
    }
    private void testPhoneGpt() {
        if (phoneGptChecking) return;
        if (!store.openai.connected() || store.openai.model().isBlank()) {
            phoneGptCheckResult = "설정 → ChatGPT에서 연결한 뒤 GPT 모델을 골라 주세요."; store.error(phoneGptCheckResult); return;
        }
        int run = ++phoneGptCheckRun; phoneGptChecking = true;
        String model = store.openai.model(), effort = store.openai.effectiveReasoningEffort(model), clock = GeminiProtocol.clock(java.time.ZonedDateTime.now());
        JSONObject status = PhoneService.status();
        phoneGptCheckResult = model + " · GPT 도구 연결을 검사하는 중…";
        store.phoneLog("→ GPT 연결 검사 · " + model); store.status(phoneGptCheckResult);
        Runnable guard = () -> {
            if (run != phoneGptCheckRun || isDestroyed()) throw new IllegalStateException("GPT 연결 검사를 취소했어요.");
            if (!model.equals(store.openai.model())) throw new IllegalStateException("GPT 모델이 바뀌었어요. 선택한 모델로 다시 검사해 주세요.");
        };
        new Thread(() -> {
            String result;
            try {
                OpenAiClient.testPhone(store.openai, model, clock, effort, status, () -> {}, phoneGptCheckRequest, guard, store::phoneLog);
                result = model + " · GPT 도구 연결 성공 ✓ · 음성 비서를 시작해 폰 작업을 요청해 주세요.";
            } catch (Exception failure) { result = model + " · 연결 실패: " + (failure instanceof java.io.IOException ? Net.explain(failure) : dev.jun.gyeot.core.Jsons.message(failure, "")); }
            String completed = result;
            main.post(() -> {
                if (run != phoneGptCheckRun || isDestroyed()) return;
                phoneGptChecking = false; phoneGptCheckResult = completed;
                store.phoneLog(completed); store.status(completed);
            });
        }, "gyeot-phone-gpt-check").start();
    }
    private void cancelPhoneGptCheck() {
        phoneGptCheckRun++; phoneGptChecking = false;
        Runnable abort = phoneGptCheckRequest.getAndSet(null);
        if (abort != null) new Thread(abort, "gyeot-phone-check-cancel").start();
    }
    private void closeProbes() { cancelPhoneGptCheck(); probeRun++; main.removeCallbacksAndMessages(null); for (GeminiSession session : probes) session.close(); probes.clear(); }
    private static void show(TextView view, String text) { view.setText(text); view.setVisibility(View.VISIBLE); }
    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0)).versionName; } catch (Exception e) { return ""; }
    }

    // ── 공통 ─────────────────────────────────────────────────────────────────

    private void showGrounding(String html) {
        WebView web = groundingView();
        web.loadDataWithBaseURL("https://www.google.com", html, "text/html", "UTF-8", null);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Google 검색 근거").setView(web).setPositiveButton("닫기", null).create(); dialog.setOnDismissListener(d -> web.destroy()); dialog.show();
    }
    /**
     * A script-free view for given HTML (Google's grounding snippets, 크게 보기 documents with Google's suggestions in them):
     * SearchSuggestions' sandbox, so no network load and no subresource, and only a tapped http or https link leaves,
     * outside the app as before.
     */
    private WebView groundingView() {
        WebView web = new WebView(this); web.setBackgroundColor(CARD);
        SearchSuggestions.sandbox(web, this::open); return web;
    }
    private void clearBody() {
        // 작업창 suggestions wait, unfreed, for the drawing that follows (releaseWorkSuggestions).
        for (View kept : workSuggestions.values()) if (kept.getParent() instanceof ViewGroup parent) parent.removeView(kept);
        drawnSuggestions.clear();
        if (workAnswers != null) workAnswers.detach();
        destroyWebViews(body); liveGrounding = null; body.removeAllViews();
    }
    /** After a drawing: frees the 작업창 suggestions it did not show, so only those on screen are kept (and freed with the screen). */
    private void releaseWorkSuggestions() {
        for (java.util.Iterator<java.util.Map.Entry<String, View>> kept = workSuggestions.entrySet().iterator(); kept.hasNext();) {
            java.util.Map.Entry<String, View> entry = kept.next();
            if (!drawnSuggestions.contains(entry.getKey())) { destroyWebViews(entry.getValue()); kept.remove(); }
        }
        if (workAnswers != null) workAnswers.release();
    }
    private void destroyWebViews(View view) {
        if (view instanceof WebView web) { web.stopLoading(); web.destroy(); }
        else if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) destroyWebViews(group.getChildAt(i));
    }
    private void copy(String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(Brand.NAME, text));
        Ui.done(body);
        store.status("복사했어요");
    }
    /** Shares as plain text: Markdown's marks go, the words, spaces and line breaks stay (Markdown.plain). */
    private void share(String text) { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Markdown.plain(text)), "공유")); }
    /** The 마이아카이브 row's state: its library summary once connected (the address itself stays in SecretStore). */
    private String archiveStatus() {
        if (!store.archiveConnected()) return "연결 안 됨";
        String summary = store.settings.getString("archiveSummary", "");
        return "연결됨" + (summary.isBlank() ? "" : " · " + summary);
    }
    /** One MCP connector's sheet: what it is for, its tools listed again, a new sign-in or token, and 연결 해제 behind a confirm. */
    private void connectorSheet(String id, String name, int tools, String host, boolean signedIn) {
        Ui.Sheet sheet = ui.selectionSheet(name, "도구 " + tools + "개" + (host.isBlank() ? "" : " · " + host));
        TextView about = ui.label("음성 비서와 메인 대화에서 써요. 데이터를 바꾸는 도구는 쓰기 전에 물어봐요.", 13, TEXT); about.setPadding(0, 0, 0, ui.dp(12)); sheet.body.addView(about);
        LinearLayout manage = ui.group();
        ui.addRow(manage, ui.listRow(0, 0, "도구 다시 받기", "서버에서 바뀐 도구를 새로 받아요", null, () -> { sheet.dismiss(); addConnector(id, name, null, null); }), false);
        if (signedIn) ui.addRow(manage, ui.listRow(0, 0, "다시 로그인", "브라우저에서 다시 허용해요", null, () -> { sheet.dismiss(); reconnectConnector(id, name); }), false);
        else ui.addRow(manage, ui.listRow(0, 0, "인증 토큰 바꾸기", "새 토큰으로 확인한 뒤 바꿔요", ui.chevron(), () -> {
            sheet.dismiss();
            // The new token is tried with the saved address first; the old one stays until the tools list with it.
            ui.secretSheet("인증 토큰 바꾸기", name, "인증 토큰", 2000, "확인하고 바꾸기", (s, field) -> {
                String token = field.getText().toString().strip();
                if (token.isEmpty()) { field.setError("새 토큰을 입력해 주세요"); return; }
                String address;
                try { address = store.connectorSecret(id, "url"); } catch (Exception e) { field.setError("커넥터 주소를 읽지 못했어요. 지우고 다시 추가해 주세요."); return; }
                s.dismiss(); addConnector(id, name, address, token);
            }).show();
        }), false);
        sheet.body.addView(manage);
        LinearLayout off = ui.group();
        ui.addRow(off, ui.dangerRow("연결 해제", "주소와 토큰·로그인을 함께 지워요", () -> {
            sheet.dismiss(); confirm("‘" + name + "’ 커넥터를 지울까요? 이 기기에 저장된 주소와 토큰·로그인도 함께 지워요.", "해제", () -> removeConnector(id));
        }), false);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(16); sheet.body.addView(off, gap);
        sheet.primary.setText("닫기"); sheet.show();
    }
    /**
     * 커넥터 추가 in a sheet: Runpod's browser sign-in until a Runpod connector exists, or an https address with an optional
     * name and token. A connector is saved only after its tools are listed; closing the sheet saves nothing.
     */
    private void enterConnector() {
        Ui.Sheet sheet = ui.selectionSheet("커넥터 추가", "MCP 서버를 연결해요");
        boolean runpod = false; JSONArray added = store.connectors();
        for (int i = 0; i < added.length(); i++) runpod |= added.optJSONObject(i) != null && Mcp.host(dev.jun.gyeot.core.McpOAuth.RUNPOD).equalsIgnoreCase(added.optJSONObject(i).optString("host"));
        if (!runpod) {
            // Runpod's hosted MCP server signs in through the browser ("Sign in with Runpod"): no API key is pasted.
            LinearLayout hosted = ui.group(), runpodRow = ui.listRow(R.drawable.ic_plug, Ui.TEAL, "Runpod", "GPU Pod·서버리스 관리 · Runpod 계정으로 로그인", ui.chevron(), () -> {
                sheet.dismiss(); signInConnector(null, dev.jun.gyeot.core.McpOAuth.RUNPOD_NAME, dev.jun.gyeot.core.McpOAuth.RUNPOD, "");
            });
            // Two lines, so what it manages and how it signs in stay whole in the sheet at 360dp.
            TextView about = ((Ui.ListRow) runpodRow).parts.subtitle(); about.setSingleLine(false); about.setMaxLines(2);
            ui.addRow(hosted, runpodRow, true); sheet.body.addView(hosted);
        }
        sheet.body.addView(ui.sectionHeader("직접 입력"));
        EditText address = ui.input("https://… (MCP 서버 주소)", 1, 500), name = ui.input("이름 (선택 · 예: 내 메모)", 1, 30), token = ui.input("인증 토큰 (선택)", 1, 2000);
        for (EditText secret : new EditText[]{address, token}) {
            secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            secret.setContentDescription(secret.getHint());
        }
        for (EditText field : new EditText[]{address, name, token}) ui.add(sheet.body, field, 10);
        sheet.body.addView(ui.label("로그인이 필요한 서버는 토큰을 비워 두면 브라우저 로그인 창이 열려요. 로그인 결과는 이 폰 안(127.0.0.1)으로만 받아요.", 12, MUTED));
        TextView kept = ui.label("주소·토큰·로그인은 이 기기에 암호화해 두고 그 서버로만 보내요. 커넥터마다 도구를 " + Mcp.TOOLS_PER_CONNECTOR + "개까지 써요.", 12, MUTED); kept.setPadding(0, ui.dp(6), 0, 0);
        sheet.body.addView(kept);
        sheet.primary.setText("확인하고 추가");
        sheet.primary.setOnClickListener(v -> {
            String label = name.getText().toString().strip();
            try {
                String url = Mcp.address(address.getText().toString());
                String named = label.isEmpty() ? Mcp.host(url) : label;
                sheet.dismiss(); addConnector(null, named, url, token.getText().toString().strip());
            } catch (Exception e) { store.error(e.getMessage()); }
        });
        sheet.show();
    }
    private void addConnector(String existing, String name, String address, String token) { addConnector(existing, name, address, token, null); }
    /**
     * Lists the server's tools and keeps the connector only when that works. A null address re-lists the tools of the saved
     * connector `existing`; otherwise a new connector is added (or `existing` is replaced) with the pasted token, or with the
     * browser sign-in `session` (ConnectorAuth). A server that asks for a sign-in when no token was pasted opens that sign-in.
     */
    private void addConnector(String existing, String name, String address, String token, JSONObject session) {
        store.status("‘" + name + "’ 커넥터에 연결하는 중…");
        new Thread(() -> {
            try {
                String id = existing != null ? existing : UUID.randomUUID().toString().substring(0, 8);
                String url = address != null ? address : store.connectorSecret(id, "url");
                McpClient.Bearer key = session != null ? McpClient.fixed(session.optString("accessToken")) : address != null ? McpClient.fixed(token) : ConnectorAuth.bearer(store, id);
                if (existing != null) McpClient.forget(id);
                JSONArray listed = McpClient.listTools(id, url, key, name);
                JSONArray list = store.connectors(), kept = new JSONArray();
                java.util.Set<String> slugs = new java.util.HashSet<>(); String previous = "";
                for (int i = 0; i < list.length(); i++) {
                    JSONObject other = list.optJSONObject(i); if (other == null) continue;
                    if (other.optString("id").equals(id)) previous = other.optString("slug"); else { kept.put(other); slugs.add(other.optString("slug")); }
                }
                String slug = previous.isEmpty() ? Mcp.slug(name, "c" + id) : previous;
                for (int n = 2; slugs.contains(slug); n++) slug = Mcp.slug(name, "c" + id) + "_" + n;
                JSONArray tools = Mcp.tools(name, slug, listed, Mcp.TOOLS_PER_CONNECTOR);
                if (tools.length() == 0) throw new IllegalStateException("‘" + name + "’ 서버에 쓸 수 있는 도구가 없어요.");
                if (address != null) {
                    store.connectorSecret(id, "url", url); store.connectorSecret(id, "token", session != null ? "" : token);
                    if (session != null) store.connectorSecret(id, "oauth", session.toString()); else ConnectorAuth.forget(store, id);
                }
                boolean signedIn = ConnectorAuth.signedIn(store, id);
                kept.put(new JSONObject().put("id", id).put("name", name).put("slug", slug).put("host", Mcp.host(url)).put("auth", signedIn ? "oauth" : "key").put("tools", tools).put("checkedAt", System.currentTimeMillis()));
                int count = tools.length(), total = listed.length();
                main.post(() -> { store.saveConnectors(kept); store.status("‘" + name + "’ 커넥터 연결됨 · 도구 " + count + "개" + (total > count ? " (전체 " + total + "개 중)" : "") + " · 메인 대화는 바로, 음성 비서는 다음 음성 대화부터 써요"); if (tab == 2) showTab(2); });
            } catch (McpClient.SignInNeeded e) {
                // No token was pasted and the server wants a sign-in: open its browser sign-in instead of failing.
                if (address != null && session == null && (token == null || token.isBlank())) main.post(() -> signInConnector(existing, name, address, e.challenge));
                else { String reason = e.getMessage(); main.post(() -> store.error(reason)); }
            } catch (Exception e) { String reason = e.getMessage() == null ? "커넥터에 연결하지 못했어요." : e.getMessage(); main.post(() -> store.error(reason)); }
        }, "gyeot-connector").start();
    }
    private void removeConnector(String id) {
        try {
            store.connectorSecret(id, "url", ""); store.connectorSecret(id, "token", ""); ConnectorAuth.forget(store, id); McpClient.forget(id);
            JSONArray list = store.connectors(), kept = new JSONArray();
            for (int i = 0; i < list.length(); i++) if (list.optJSONObject(i) != null && !id.equals(list.optJSONObject(i).optString("id"))) kept.put(list.optJSONObject(i));
            store.saveConnectors(kept); store.status("커넥터 연결을 해제했어요 · 음성 비서는 다음 음성 대화부터 적용돼요"); showTab(2);
        } catch (Exception e) { store.error("커넥터를 지우지 못했어요."); }
    }
    /** Signs a browser-signed-in connector in again (its sign-in ended, or another account is wanted). */
    private void reconnectConnector(String id, String name) {
        try { signInConnector(id, name, store.connectorSecret(id, "url"), ""); }
        catch (Exception e) { store.error("커넥터 주소를 읽지 못했어요. 지우고 다시 추가해 주세요."); }
    }
    /** Browser sign-in (OAuth) to an MCP server such as Runpod's; the connector is kept once its tools list with the new sign-in. */
    private void signInConnector(String existing, String name, String address, String challenge) {
        store.status("브라우저에서 ‘" + name + "’에 로그인하고 허용해 주세요. 끝나면 " + Brand.EURO + " 돌아오세요.");
        ConnectorAuth.signIn(this, name, address, challenge,
            session -> main.post(() -> addConnector(existing, name, address, "", session)),
            reason -> main.post(() -> store.error(reason)));
    }
    /** 마이아카이브 once connected: what it is used for, 연결 확인, 주소 바꾸기, and 연결 해제 behind a confirm. */
    private void archiveSheet() {
        String host = store.settings.getString("archiveHost", "");
        Ui.Sheet sheet = ui.selectionSheet("마이아카이브", "연결됨" + (host.isBlank() ? "" : " · " + host));
        sheet.body.addView(ui.label("음성 비서와 메인 대화에서 찾고 읽고 보관해요. 기록과 작업창 답의 ‘서재에 저장’으로도 넣어요.", 13, TEXT));
        TextView examples = ui.label("예: “서재에서 여행 글 찾아 줘”, “그 글 읽어 줘”, “방금 결과 서재에 저장해 줘”", 12, MUTED); examples.setPadding(0, ui.dp(6), 0, ui.dp(12)); sheet.body.addView(examples);
        LinearLayout manage = ui.group();
        ui.addRow(manage, ui.listRow(0, 0, "연결 확인", "서재에 다시 물어봐요", null, () -> { sheet.dismiss(); checkArchive(null); }), false);
        ui.addRow(manage, ui.listRow(0, 0, "주소 바꾸기", "새 주소를 확인한 뒤 바꿔요", ui.chevron(), () -> { sheet.dismiss(); enterArchiveAddress(); }), false);
        sheet.body.addView(manage);
        LinearLayout off = ui.group();
        ui.addRow(off, ui.dangerRow("연결 해제", "이 기기의 주소만 지워요", () -> {
            sheet.dismiss();
            confirm("이 기기에서 마이아카이브 연결 주소를 지울까요? 키 자체를 막으려면 마이아카이브 앱의 AI 연결에서 ‘연결 끊기’를 눌러 주세요.", "해제", () -> {
                try { store.secrets.put(SecretStore.ARCHIVE, ""); store.settings.edit().remove("archiveSummary").remove("archiveHost").apply(); store.status("마이아카이브 연결을 해제했어요 · 음성 비서는 다음 음성 대화부터 적용돼요"); showTab(2); }
                catch (Exception e) { store.error("연결 주소를 지우지 못했어요."); }
            });
        }), false);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(16); sheet.body.addView(off, gap);
        sheet.primary.setText("닫기"); sheet.show();
    }
    /** Paste the connection address, only in this sheet; it is saved only after the archive answers. Closing it saves nothing. */
    private void enterArchiveAddress() {
        Ui.Sheet sheet = ui.secretSheet("마이아카이브 연결", "마이아카이브 앱 → 설정 → AI 연결 → ‘다른 AI 앱’에서 만든 주소예요", "https://…/mcp/mak_…", 300, "확인하고 연결", (s, field) -> {
            String address;
            try { address = Archive.address(field.getText().toString()); } catch (Exception e) { field.setError(e.getMessage()); return; }
            s.dismiss(); checkArchive(address);
        });
        sheet.body.addView(ui.label("권한은 ‘보관’으로 만들어 주세요. 글을 고치게 하려면 그 연결의 ‘본문 고치기’와 ‘제목·분류·태그 바꾸기’도 켜 주세요.", 12, MUTED));
        TextView kept = ui.label("주소는 연결을 확인한 뒤 이 기기에 암호화해 보관해요.", 12, MUTED); kept.setPadding(0, ui.dp(6), 0, 0); sheet.body.addView(kept);
        sheet.show();
    }
    /** Asks the archive for its categories: a new address is saved only when that works; null re-checks the saved one. */
    private void checkArchive(String pasted) {
        store.status("마이아카이브 연결을 확인하는 중…");
        new Thread(() -> {
            try {
                String address = pasted == null ? store.archiveAddress() : pasted;
                String summary = Archive.summary(ArchiveClient.call(address, Archive.tool("list_categories")));
                if (pasted != null) store.secrets.put(SecretStore.ARCHIVE, pasted);
                store.settings.edit().putString("archiveSummary", summary).putString("archiveHost", Archive.host(address)).apply();
                main.post(() -> { store.status("마이아카이브에 연결됐어요 · " + summary + (store.voiceActive ? " · 음성 비서는 다음 음성 대화부터 써요" : "")); if (tab == 2) showTab(2); });
            } catch (Exception e) { String reason = e.getMessage() == null ? "마이아카이브에 연결하지 못했어요." : e.getMessage(); main.post(() -> store.error(reason)); }
        }, "gyeot-archive-check").start();
    }
    /** "서재에 저장" for a record, or null when 마이아카이브 is not connected. */
    private Button archiveChip(String title, String content, String prompt, String aiModel) {
        if (!store.archiveConnected() || content == null || content.isBlank()) return null;
        return ui.chip("서재에 저장", () -> saveToArchive(title, content, prompt, aiModel));
    }
    private void saveToArchive(String title, String content, String prompt, String aiModel) {
        store.status("마이아카이브에 보관하는 중…");
        new Thread(() -> {
            try {
                String done = ArchiveClient.call(store.archiveAddress(), Archive.request(Archive.SAVE, Archive.saving(title, content, prompt), aiModel));
                main.post(() -> store.status(Archive.summary(done)));
            } catch (Exception e) { String reason = e.getMessage() == null ? "보관하지 못했어요." : e.getMessage(); main.post(() -> store.error(reason)); }
        }, "gyeot-archive-save").start();
    }
    /** The edge button's state, under its switch on 바로 부르기 (refresh() keeps it current). */
    private String edgeStatus() {
        if (!store.edgeButton()) return "꺼져 있어요";
        return PhoneService.connected() ? "켜짐 · 화면 가장자리에 있어요" : "켜짐 · 폰 조작 1단계(접근성)를 켜면 나타나요";
    }
    /** Whether this app is the phone's default digital assistant (the home button's long press). */
    private boolean assistantRoleHeld() {
        RoleManager roles = getSystemService(RoleManager.class);
        return roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT);
    }
    /** The home button row's state on 바로 부르기 (refresh() keeps it current); a tap opens Android's choice. */
    private String assistStatus() {
        return assistantRoleHeld() ? Brand.I + " 기본 어시스턴트예요" : "다른 앱이 기본 어시스턴트예요 · 눌러서 바꾸기";
    }
    private void openAssistantSettings() {
        try { startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); }
        catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); } catch (Exception again) { store.error("설정 → 애플리케이션 → 기본 앱 → 디지털 어시스턴트 앱에서 바꿔 주세요."); } }
    }
    /** Android's own "add tile" prompt; the user confirms it. */
    private void addTile() {
        StatusBarManager bar = getSystemService(StatusBarManager.class);
        String manual = "알림창을 내린 뒤 연필(편집) 버튼에서 ‘" + Brand.NAME + "’ 타일을 끌어다 놓아 주세요.";
        if (bar == null) { store.status(manual); return; }
        bar.requestAddTileService(new ComponentName(this, QuickTile.class), getString(R.string.quick_tile_label), Icon.createWithResource(this, R.drawable.ic_mic), getMainExecutor(), result -> {
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) store.status("알림창에 " + Brand.NAME + " 타일을 추가했어요");
            else if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) store.status(Brand.NAME + " 타일은 이미 알림창에 있어요");
            else if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) store.status(manual);
        });
    }
    private void openAppSettings() { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    private void open(String uri) { try { Uri url = Uri.parse(uri); if (url.getScheme() != null && (url.getScheme().equals("https") || url.getScheme().equals("http"))) startActivity(new Intent(Intent.ACTION_VIEW, url)); } catch (Exception e) { store.error("링크를 열 수 없어요."); } }
    private static String when(long millis) { return millis <= 0 ? "" : WHEN.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())); }
    private static String languageLabel(String language) { for (String[] entry : GeminiProtocol.LANGUAGES) if (entry[0].equals(language)) return entry[1]; return "영어"; }
    private static String stateName(String state) { return switch (state) { case "queued" -> "대기 중"; case "running" -> "조사 중"; case "completed" -> "완료"; case "cancelled" -> "취소됨"; case "interrupted" -> "중단됨"; case "failed" -> "실패"; default -> "확인 필요"; }; }
}





