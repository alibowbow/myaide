package dev.jun.gyeot;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.browser.customtabs.CustomTabsIntent;
import dev.jun.gyeot.core.BrowserPrompts;
import dev.jun.gyeot.core.BrowserTools;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
import org.json.JSONArray;
import org.mozilla.geckoview.*;
import static dev.jun.gyeot.Ui.*;
import static dev.jun.gyeot.core.Jsons.*;

/** Browser chrome stays native; the independent Gecko profile retains site logins across app restarts. */
public final class BrowserActivity extends Activity {
    private Ui ui;
    private boolean navigationResumed;
    private AlertDialog youtubeDialog;
    private BrowserEngine engine;
    private GeckoView web;
    /** 화면 분할 (a foldable or tablet): a second page beside the main one, with its own small header. */
    private GeckoView splitView; private LinearLayout splitPane; private TextView splitTitle;
    /** 끝까지 조사: the next request goes as a deep research task (one request, then off). */
    private boolean deepNext; private TextView deepChip;
    static final String SKILLS = "browserSkills", BOOKMARKS = "browserBookmarks", MEMORY = "browserMemory", PROJECTS = "browserProjects"; static final int REQUEST_BOOKMARKS = 7311, REQUEST_FILES = 7312;
    /** A page's file chooser waiting for the phone's picker (onFilePrompt), and its answer. */
    private GeckoSession.PromptDelegate.FilePrompt filePrompt; private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> fileAnswer;
    private EditText address, request;
    private TextView resultText, tabButton, notice, askBubble, statsText;
    private TextView modelLine;
    private ImageView sendButton, pageBackButton, pageForwardButton, pageRefreshButton, browserMenuButton, paneToggle, siteIcon, clearAddressButton;
    private BrowserPanelHeader panelHeader;
    private ImageView appReturnButton;
    private LinearLayout browserToolbar;
    private static final dev.jun.gyeot.core.BrowserDrafts DRAFTS = new dev.jun.gyeot.core.BrowserDrafts();
    private BrowserEngine.Tab draftTab;
    private String draftConversation = "";
    private Bundle pendingDraftState;
    private String renderedResult;
    private ProgressBar progress;
    private LinearLayout resultBox, pane, conversationBox, resultActions, omniRow, thinkingRow;
    private ShimmerText thinkingText; private ImageView thinkingMark; private android.animation.ObjectAnimator thinkingSpin;
    private HorizontalScrollView omniStrip;
    private BrowserTabStrip tabStrip;
    private ScrollView resultScroll;
    private PageSelection selection;
    private long renderedConversationVersion = -1;
    private String renderedConversationId = "";
    private int historyWindow = 40, renderedHistoryWindow;
    private Button earlierMessages;
    private boolean followNextConversation;
    private TextView historyWarning;
    private static final String RECENTS = "browserRecentRequests";
    private boolean wide, keyboardVisible, paneVisible, wasRunning;
    private Runnable tabOverviewRefresh, zoomMenuRefresh;
    private android.app.Dialog tabOverview;
    private static final String PANE_VISIBLE = "browserPaneVisible";
    /**
     * A page another screen of this app asks to open (open()): its address, and an id so the same request opens one tab
     * even when the screen is made again (rotation, or the process restarting) with the same intent or an older one.
     */
    static final String OPEN = "dev.jun.gyeot.browser.OPEN", OPEN_ID = "dev.jun.gyeot.browser.OPEN_ID";
    /**
     * Opened from the home screen's left page (ClaudePage): its claude.ai tab again if one is open, and ways back to 홈 (준: "뒤로가기로
     * 홈페이지 돌아가는 건 넣지마, 홈버튼이나 우측 스와이프로"): the ⌂ button and a swipe in from the right edge (HomeEdge); back stays
     * the page's history.
     */
    static final String HOME_SIDE = "dev.jun.gyeot.browser.HOME_SIDE";
    private boolean homeSide;
    private HomeEdge homeEdge;
    /** Opened from the "이전 브라우저 작업 확인" notification: the earlier Work step's review comes up at once. */
    static final String REVIEW_WORK = "dev.jun.gyeot.browser.REVIEW_WORK", REVIEW_RECEIPT = "dev.jun.gyeot.browser.REVIEW_RECEIPT";
    private static final String OPENED = "browserOpened";
    /** The ids of the requests already opened, oldest first, the newest OPENED_KEPT of them; kept in the saved state. */
    static final int OPENED_KEPT = 32;
    private final LinkedHashSet<String> opened = new LinkedHashSet<>();
    private ComposerDictation dictation;
    private final android.os.Handler progressUiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean progressTickScheduled;
    private Runnable detailRefresh;
    private String progressAction = "";
    private Ui.Sheet progressDetails;
    private long recoveryViewSequence;
    private final BrowserProgressUi.ObservationClock progressClock = new BrowserProgressUi.ObservationClock();
    private final Runnable progressUiTick = () -> {
        progressTickScheduled = false;
        if (!navigationResumed || engine == null || !engine.running()) return;
        refreshProgressUiTimes();
    };
    private final Runnable storeObserver = this::refresh;
    private final List<android.app.Dialog> dialogs = new ArrayList<>();
    /** 음성 대화 with GPT or Grok in the AI pane (VoiceBar, VoiceTargets.browser): the send button starts it with an empty draft. */
    private VoiceBar voice;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); ui = new Ui(this); engine = BrowserEngine.get(this); pendingDraftState = state;
        if (state != null) restoreOpened(state, opened);
        selection = new PageSelection();
        wide = getResources().getConfiguration().screenWidthDp >= 680;
        paneVisible = state == null ? engine.store.settings.getBoolean(PANE_VISIBLE, false) : state.getBoolean(PANE_VISIBLE, false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = ui.column(); root.setBackground(ui.canvas());
        // AI state lives in the existing toolbar button; never reserve a banner row.
        LinearLayout header = createBrowserHeader();
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT)); root.addView(progress, new LinearLayout.LayoutParams(-1, ui.dp(2)));
        LinearLayout workspace = wide ? ui.row() : ui.column();
        if (wide) workspace.setGravity(Gravity.TOP);
        FrameLayout page = new FrameLayout(this);
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        web = new GeckoView(this) {
            private float downX, downY; private boolean watching;
            private BrowserImeGuard gestureGuard;
            private long gestureStamp;
            private BrowserImeGuard inputGuard() {
                GeckoSession session=getSession();if(session==null)return null;
                return session.getTextInput().getDelegate() instanceof BrowserImeGuard guard ? guard : null;
            }
            private void humanInput(){BrowserImeGuard guard=inputGuard();if(guard!=null)guard.humanInput();else if (engine.current!=null&&getSession()==engine.current.session&&engine.liveSession(getSession())) engine.manual();}
            @Override public boolean dispatchKeyEvent(KeyEvent e){
                if(e.getAction()==KeyEvent.ACTION_DOWN&&(e.isPrintingKey()||e.getKeyCode()==KeyEvent.KEYCODE_ENTER
                        ||e.getKeyCode()==KeyEvent.KEYCODE_TAB||e.getKeyCode()==KeyEvent.KEYCODE_DEL))humanInput();
                return super.dispatchKeyEvent(e);
            }
            @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(){
                BrowserImeGuard expected=inputGuard();
                return BrowserImeGuard.accessibility(super.getAccessibilityNodeProvider(),
                    () -> expected != null && expected == inputGuard() && expected.liveInput(),
                    () -> BrowserImeGuard.humanInputIfCurrent(expected,inputGuard()));
            }
            // A DOWN is not yet a takeover: scroll/pinch remains read-only watching.
            @Override public boolean dispatchTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        downX=e.getX();downY=e.getY();watching=false;gestureGuard=inputGuard();
                        if(gestureGuard!=null){
                            BrowserImeGuard started=gestureGuard;long stamp=gestureStamp=started.beginGesture();
                            postDelayed(()->{if(!watching&&gestureGuard==started&&started.gesture(stamp))started.humanInput();},ViewConfiguration.getLongPressTimeout());
                        }
                    }
                    case MotionEvent.ACTION_POINTER_DOWN -> {watching=true;if(gestureGuard!=null)gestureGuard.cancelGesture();}
                    case MotionEvent.ACTION_MOVE -> {if(Math.hypot(e.getX()-downX,e.getY()-downY)>slop){watching=true;if(gestureGuard!=null)gestureGuard.cancelGesture();}}
                    case MotionEvent.ACTION_UP -> {if(!watching)BrowserImeGuard.finishGesture(gestureGuard,gestureStamp,inputGuard());else if(gestureGuard!=null)gestureGuard.cancelGesture();gestureGuard=null;}
                    case MotionEvent.ACTION_CANCEL -> {if(gestureGuard!=null)gestureGuard.cancelGesture();gestureGuard=null;}
                    default -> {}
                }
                return super.dispatchTouchEvent(e);
            }
        };
        web.setActivityContextDelegate(() -> this); page.addView(web, new FrameLayout.LayoutParams(-1, -1));
        notice = ui.text("", 14, 600, TEXT); notice.setBackground(ui.glass(99, false)); notice.setElevation(ui.dp(6)); notice.setPadding(ui.dp(18), ui.dp(11), ui.dp(18), ui.dp(11));
        notice.setMaxLines(3); notice.setGravity(Gravity.CENTER); notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); np.topMargin = ui.dp(12); np.leftMargin = np.rightMargin = ui.dp(20); page.addView(notice, np);
        // The pages: the main one, and in 화면 분할 a second tab beside it.
        LinearLayout pages = ui.row(); pages.addView(page, new LinearLayout.LayoutParams(0, -1, 1));
        splitPane = ui.column(); splitPane.setVisibility(View.GONE); splitPane.setBackgroundColor(CARD);
        LinearLayout splitBar = ui.row(); splitBar.setGravity(Gravity.CENTER_VERTICAL); splitBar.setPadding(ui.dp(10), 0, ui.dp(2), 0);
        splitTitle = ui.text("", 13, 600, TEXT); splitTitle.setSingleLine(true); splitTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        splitBar.addView(splitTitle, new LinearLayout.LayoutParams(0, -2, 1));
        splitBar.addView(ui.quietIcon(R.drawable.ic_expand, "이쪽을 메인으로", this::swapSplit), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        splitBar.addView(ui.quietIcon(R.drawable.ic_close, "화면 분할 닫기", () -> engine.split(null)), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        splitPane.addView(splitBar, new LinearLayout.LayoutParams(-1, ui.dp(40)));
        splitView = new GeckoView(this); splitView.setActivityContextDelegate(() -> this);
        splitPane.addView(splitView, new LinearLayout.LayoutParams(-1, 0, 1));
        View seam = new View(this); seam.setBackgroundColor(SEPARATOR);
        LinearLayout.LayoutParams seamParams = new LinearLayout.LayoutParams(Math.max(1, ui.dp(1)), -1);
        LinearLayout.LayoutParams sideParams = new LinearLayout.LayoutParams(0, -1, 1);
        splitPane.setTag(seam); pages.addView(seam, seamParams); seam.setVisibility(View.GONE); pages.addView(splitPane, sideParams);
        workspace.addView(pages, wide ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, 0, 1));
        pane = createAssistantPane();
        workspace.addView(pane, wide ? new LinearLayout.LayoutParams(ui.dp(panelWidth()), -1) : new LinearLayout.LayoutParams(-1, -2));
        root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(header, 0, new LinearLayout.LayoutParams(-1, -2));
        // Under the toolbar while typing: the address bar doubles as the AI's command line.
        omniRow = ui.row(); omniStrip = new HorizontalScrollView(this); omniStrip.setHorizontalScrollBarEnabled(false); omniStrip.setClipToPadding(false);
        omniStrip.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(6)); omniStrip.addView(omniRow); omniStrip.setVisibility(View.GONE); root.addView(omniStrip, 1, new LinearLayout.LayoutParams(-1, -2));
        updatePaneVisibility();
        installBrowserContent(root);
        homeEdge = new HomeEdge(this, ui, this::toHome); homeEdge.attach(findViewById(android.R.id.content)); fromHome(getIntent());
        bindDraftScope();
    }
    /** Browser owns the full safe viewport; app navigation remains available through Home. */
    private void installBrowserContent(LinearLayout root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            keyboardVisible = insets.isVisible(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            if (resultBox != null) refresh();
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
    }
    /** Builds the real native panel independently of Gecko so layout tests can exercise every control. */
    private LinearLayout createAssistantPane() {
        pane = new LinearLayout(this) {
            @Override protected void onMeasure(int width, int height) {
                if (!wide) { int available = View.MeasureSpec.getSize(height); int cap = keyboardVisible ? Math.min(available, ui.dp(260)) : Math.min(ui.dp(400), Math.round(available * .6f)); super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(Math.max(0, cap), View.MeasureSpec.AT_MOST)); }
                else super.onMeasure(width, height);
            }
        }; pane.setOrientation(LinearLayout.VERTICAL); pane.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        android.graphics.drawable.GradientDrawable panelSurface = ui.shape(CARD, wide ? 0 : 20);
        panelSurface.setStroke(ui.dp(1), FILL); pane.setBackground(panelSurface);
        panelHeader = new BrowserPanelHeader(this, ui, () -> setPaneVisible(false), this::conversationMenu); pane.addView(panelHeader);
        resultBox = ui.column();
        String storageNotice = "대화 기록 또는 탭의 작업 기억을 저장하거나 불러오지 못했어요. 새 내용은 앱을 다시 열면 복원되지 않을 수 있어요.";
        historyWarning = ui.text("기록 저장 확인 · 자세히", 12, 550, ERROR);
        historyWarning.setMaxLines(1); historyWarning.setEllipsize(android.text.TextUtils.TruncateAt.END); historyWarning.setMinimumHeight(ui.dp(44));
        historyWarning.setGravity(Gravity.CENTER_VERTICAL); historyWarning.setContentDescription(storageNotice + " 눌러서 자세히 보기");
        historyWarning.setOnClickListener(v -> { Ui.Sheet warning = ui.selectionSheet("기록 저장 확인", null); warning.body.addView(ui.label(storageNotice, 15, TEXT)); show(warning); });
        historyWarning.setVisibility(View.GONE); pane.addView(historyWarning); // Fixed above the history scroller, including while reading its bottom.
        earlierMessages = ui.chip("이전 메시지 더 보기", () -> {
            int height = resultBox.getHeight(), y = resultScroll.getScrollY();
            historyWindow = historyWindow > Integer.MAX_VALUE - 40 ? Integer.MAX_VALUE : historyWindow + 40;
            renderConversation(engine.running());
            resultScroll.post(() -> resultScroll.scrollTo(0, y + Math.max(0, resultBox.getHeight() - height)));
        });
        resultBox.addView(earlierMessages);
        // The durable transcript owns every sent user/assistant bubble, including the current request.
        conversationBox = ui.column(); resultBox.addView(conversationBox);
        askBubble = bubble(""); askBubble.setVisibility(View.GONE); resultBox.addView(askBubble, bubbleParams());
        // While the AI works on this tab: one quiet line under the request, the AI mark breathing and words a soft light
        // sweeps across, as chat apps show a model thinking. A tap opens 작업 상세.
        thinkingRow = ui.row(); thinkingRow.setMinimumHeight(ui.dp(40)); thinkingRow.setPadding(0, ui.dp(2), 0, ui.dp(10)); thinkingRow.setVisibility(View.GONE);
        thinkingMark = new ImageView(this); thinkingMark.setImageDrawable(new AiMark(getResources().getDisplayMetrics().density)); thinkingMark.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        thinkingMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)); markParams.rightMargin = ui.dp(9); thinkingRow.addView(thinkingMark, markParams);
        thinkingText = new ShimmerText(this, MUTED, TEXT); thinkingText.setTextSize(14); thinkingText.setTypeface(Ui.weight(500)); thinkingText.setIncludeFontPadding(false);
        thinkingText.setSingleLine(true); thinkingText.setEllipsize(android.text.TextUtils.TruncateAt.END); thinkingText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        thinkingRow.addView(thinkingText, new LinearLayout.LayoutParams(0, -2, 1));
        // 중지, as small as the line: the work stops at once, without opening 작업 상세.
        TextView stopWork = ui.text("중지", 13, 600, ERROR); stopWork.setPadding(ui.dp(12), ui.dp(8), ui.dp(4), ui.dp(8));
        stopWork.setContentDescription("AI 작업 중지"); Ui.pressable(stopWork, true);
        stopWork.setOnClickListener(v -> { BrowserProgressUi.StopTarget target = stopTarget(progressSnapshot()); if (target.canStop()) target.stop(); else detail(); });
        thinkingRow.addView(stopWork);
        thinkingRow.setContentDescription("AI가 작업하는 중 · 작업 상세 열기"); thinkingRow.setOnClickListener(v -> detail()); Ui.pressable(thinkingRow, true);
        resultBox.addView(thinkingRow);
        resultText = ui.text("", 15, 450, TEXT); resultText.setTextIsSelectable(true); resultText.setVisibility(View.GONE);
        resultBox.addView(resultText);
        // Under an answer: copy, share and run again, with how many steps and how long it took.
        resultActions = ui.row(); resultActions.setPadding(0, ui.dp(4), 0, 0); resultActions.setVisibility(View.GONE);
        // Small quiet icons, as under an answer in the main chat and 작업창: copy, share, run again.
        resultActions.addView(ui.quietIcon(R.drawable.ic_copy, "복사", () -> copyText("브라우저 작업 결과", dev.jun.gyeot.core.Markdown.plain(engine.status))), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        resultActions.addView(ui.quietIcon(R.drawable.ic_share, "공유", () -> shareText(engine.status, "브라우저 작업 결과")), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        resultActions.addView(ui.quietIcon(R.drawable.ic_refresh, "다시", this::again), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        statsText = ui.text("", 12, 450, MUTED); statsText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); statsText.setMaxLines(1);
        resultActions.addView(statsText, new LinearLayout.LayoutParams(0, -2, 1));
        resultBox.addView(resultActions);
        if (wide) { ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.addView(resultBox); resultScroll = scroll; pane.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); }
        else { ScrollView results = new ScrollView(this) {
            @Override protected void onMeasure(int width, int height) { super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(Math.min(ui.dp(240), View.MeasureSpec.getSize(height)), View.MeasureSpec.AT_MOST)); }
        }; results.setVerticalScrollBarEnabled(false); results.addView(resultBox); resultScroll = results; pane.addView(results, new LinearLayout.LayoutParams(-1, 0, 1)); }
        // A quiet composer: draft, then send or add an instruction to this tab's task; the model on its line under it (ModelLine,
        // 준: "Ai브라우저 채팅창. 모델선택을 메뉴깊숙히 숨기지말고 이런식으로 입력창아래에").
        LinearLayout compose = ui.row(); compose.setGravity(Gravity.BOTTOM); compose.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        android.graphics.drawable.GradientDrawable composeSurface = ui.shape(FILL, 18); composeSurface.setStroke(Math.max(1, ui.dp(.6f)), DARK ? 0x24ffffff : 0x14000000); compose.setBackground(composeSurface);
        request = ui.input("이 페이지에서 할 일", 2, BrowserPrompts.REQUEST_LIMIT); request.setTextSize(15.5f); request.setMinLines(1); request.setMinimumHeight(0); request.setMinHeight(0); request.setMaxLines(wide ? 6 : 4); request.setBackgroundColor(android.graphics.Color.TRANSPARENT); request.setContentDescription("브라우저 AI에게 지시할 내용");
        request.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); request.setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12)); request.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        compose.addView(request, new LinearLayout.LayoutParams(0, -2, 1));
        // The round send/voice button as in the other chats: a smaller disc inside the same 48dp target.
        sendButton = ui.sendButton("지시 실행", () -> { if (offersVoice()) startVoice(); else ask(); }); compose.addView(sendButton, square());
        sendButton.setOnLongClickListener(v -> { if (!offersVoice()) return false; voice.settings(""); return true; });
        request.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable t) { sendLooks(); }
        });
        // 음성 대화's bar stands in the composer's place while a conversation is on.
        voice = new VoiceBar(this, ((GyeotApp) getApplication()).store(), ui, "browser", () -> VoiceTargets.browser(this)); voice.aside(compose); voice.track(dialogs::add);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, -2); pane.addView(voice.bar(), barParams);
        deepChip = ui.text("끝까지 조사 · 다음 지시에 적용  ✕", 12.5f, 600, ACCENT); deepChip.setBackground(ui.shape(ACCENT_SOFT, 99)); deepChip.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        deepChip.setVisibility(View.GONE); deepChip.setContentDescription("끝까지 조사 켜짐, 눌러서 끄기"); deepChip.setOnClickListener(v -> deep(false));
        LinearLayout.LayoutParams deepParams = new LinearLayout.LayoutParams(-2, -2); deepParams.bottomMargin = ui.dp(6); pane.addView(deepChip, deepParams);
        pane.addView(compose);
        modelLine = ModelLine.make(this, ui, v -> models());
        pane.addView(ModelLine.row(ui, modelLine), new LinearLayout.LayoutParams(-1, -2));
        updateComposerLines(false);
        return pane;
    }
    /** Keep every draft character; only its visible height changes in a constrained viewport. */
    private void updateComposerLines(boolean storageWarning) {
        boolean large = getResources().getConfiguration().fontScale >= 1.5f;
        int lines = keyboardVisible && large && storageWarning ? 1 : keyboardVisible || !wide && large ? 2 : wide ? 6 : 4;
        // TextView also measures its hint; a wrapping hint must not keep a one-line draft tall.
        request.setHint(engine != null && engine.historyRestorePending() ? "불러오는 중" : large ? "할 일 입력" : "이 페이지에서 할 일");
        request.setMaxLines(lines); request.setVerticalScrollBarEnabled(true);
    }
    /** Construct native browser chrome independently of page/session rendering. */
    private LinearLayout createBrowserHeader() {
        // Chrome-style two-row header: directly selectable tabs, then a compact address row.
        // Use the measured, inset-adjusted window width, not the site's desktop user agent.
        // A folded device or narrow multi-window layout keeps only Home without reserved slots.
        browserToolbar = new LinearLayout(this) {
            @Override protected void onMeasure(int width, int height) {
                updateNavigationLayout(View.MeasureSpec.getSize(width));
                super.onMeasure(width, height);
            }
        };
        browserToolbar.setOrientation(LinearLayout.HORIZONTAL); browserToolbar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout toolbar = browserToolbar; toolbar.setPadding(ui.dp(4), 0, ui.dp(4), 0); toolbar.setBackgroundColor(CARD);
        appReturnButton = navigationIcon(R.drawable.ic_home, "앱으로 돌아가기 · 웹페이지 뒤로가기 아님 · 탭과 대화 유지", this::leaveBrowser);
        appReturnButton.setTooltipText("앱으로 돌아가기"); toolbar.addView(appReturnButton, dockSquare());
        pageBackButton = navigationIcon(R.drawable.ic_nav_back, "현재 탭의 이전 웹페이지로 이동", this::goBackInPage);
        toolbar.addView(pageBackButton, dockSquare());
        pageForwardButton = navigationIcon(R.drawable.ic_nav_forward, "현재 탭의 다음 웹페이지로 이동", this::goForwardInPage);
        toolbar.addView(pageForwardButton, dockSquare());
        pageRefreshButton = navigationIcon(R.drawable.ic_refresh, "현재 웹페이지 새로고침", this::reloadPage);
        toolbar.addView(pageRefreshButton, dockSquare());
        updateNavigationLayout(0); updatePageNavigation();
        LinearLayout omnibox = ui.row(); omnibox.setBackground(ui.shape(FILL, 99)); omnibox.setPadding(ui.dp(8), 0, 0, 0);
        siteIcon = new ImageView(this); siteIcon.setImageResource(R.drawable.ic_globe); siteIcon.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED));
        omnibox.addView(siteIcon, new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15)));
        address = ui.input("검색하거나 주소 입력", 1, 4096); address.setTextSize(14); address.setMinimumHeight(0); address.setMinHeight(0); address.setSelectAllOnFocus(true);
        address.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        address.setBackgroundColor(android.graphics.Color.TRANSPARENT); address.setPadding(ui.dp(6), 0, ui.dp(4), 0);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        address.setContentDescription("웹 주소 또는 검색어");
        address.setOnEditorActionListener((v, action, event) -> { if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { go(); return true; } return false; });
        address.setOnFocusChangeListener((v, focused) -> { if (focused && engine.current != null) { address.setText(engine.current.url); address.selectAll(); } refresh(); renderOmni(); });
        address.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { renderOmni(); }
        });
        omnibox.addView(address, new LinearLayout.LayoutParams(0, pillHeight(), 1));
        // Address editing is separate from page navigation: this × only clears typed text.
        clearAddressButton = navigationIcon(R.drawable.ic_close, "주소 입력 지우기", () -> address.setText(""));
        clearAddressButton.setTooltipText("주소 입력 지우기"); clearAddressButton.setVisibility(View.GONE);
        omnibox.addView(clearAddressButton, new LinearLayout.LayoutParams(ui.dp(40), pillHeight()));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, pillHeight(), 1); ap.leftMargin = ap.rightMargin = ui.dp(4); ap.gravity = Gravity.CENTER_VERTICAL; toolbar.addView(omnibox, ap);
        paneToggle = new BrowserProgressUi.Indicator(this); paneToggle.setImageDrawable(new AiMark(getResources().getDisplayMetrics().density)); paneToggle.setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11));
        paneToggle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        paneToggle.setOnClickListener(v -> { haptic(HapticFeedbackConstants.CLOCK_TICK); if (hasAiAttention()) detail(); else setPaneVisible(!paneVisible); });
        paneToggle.setOnLongClickListener(v -> { setPaneVisible(!paneVisible); return true; }); Ui.pressable(paneToggle, false); toolbar.addView(paneToggle, dockSquare());
        tabButton = ui.text("1", 12.5f, 700, TEXT); tabButton.setGravity(Gravity.CENTER); tabButton.setOnClickListener(v -> tabs());
        android.graphics.drawable.GradientDrawable tabFrame = ui.shape(android.graphics.Color.TRANSPARENT, 7); tabFrame.setStroke(ui.dp(1.6f), TEXT); tabButton.setBackground(new android.graphics.drawable.InsetDrawable(tabFrame, ui.dp(8))); Ui.pressable(tabButton, false);
        // Keep the overview available beside the strip, not at the expense of address width.
        tabStrip = new BrowserTabStrip(this, ui, this::newTabFromStrip);
        // The open tabs are counted in the strip itself; the overview is in the menu (열린 탭), not a numbered box beside it.
        browserMenuButton = icon(R.drawable.ic_more, "브라우저 메뉴", this::menu); toolbar.addView(browserMenuButton, dockSquare());
        LinearLayout header = ui.column(); header.addView(tabStrip, new LinearLayout.LayoutParams(-1, -2));
        header.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        return header;
    }
    /** 680dp is the existing native expanded-layout breakpoint; website UA never chooses chrome. */
    private void updateNavigationLayout(int availableWidthPx) {
        int visibility = availableWidthPx >= ui.dp(680) ? View.VISIBLE : View.GONE;
        for (ImageView button : new ImageView[]{pageBackButton, pageForwardButton, pageRefreshButton})
            if (button != null && button.getVisibility() != visibility) button.setVisibility(visibility);
    }
    private ImageView navigationIcon(int resource, String description, Runnable click) {
        ImageView button = icon(resource, description, click);
        button.setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11));
        button.setFocusable(true); button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        android.graphics.drawable.GradientDrawable focused = ui.shape(ACCENT_SOFT, 10);
        focused.setStroke(ui.dp(1.5f), ACCENT);
        android.graphics.drawable.StateListDrawable focusSurface = new android.graphics.drawable.StateListDrawable();
        focusSurface.addState(new int[]{android.R.attr.state_enabled, android.R.attr.state_focused}, focused);
        focusSurface.addState(new int[]{}, ui.shape(android.graphics.Color.TRANSPARENT, 10));
        button.setBackground(focusSurface);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
            }
        });
        return button;
    }
    private int panelWidth() { return Math.min(340, Math.max(300, Math.round(getResources().getConfiguration().screenWidthDp * .32f))); }
    private LinearLayout.LayoutParams square() { return new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); }
    private ImageView icon(int resource, String description, Runnable click) { return ui.iconButton(resource, android.graphics.Color.TRANSPARENT, TEXT, description, click); }
    private int addressRowHeight() { return ui.dp(Math.max(44, 24 * getResources().getConfiguration().fontScale + 8)); }
    /**
     * The address pill, lower than the row's buttons: the row keeps its height and the gray pill stands clear of the gray
     * tab strip above it, with air above and below, as in Chrome.
     */
    private int pillHeight() { return addressRowHeight() - ui.dp(8); }
    private LinearLayout.LayoutParams dockSquare() { return new LinearLayout.LayoutParams(ui.dp(44), addressRowHeight()); }
    private void newTabFromStrip() {
        try {
            BrowserEngine.Tab created = engine.newTab("https://www.google.com/", true);
            address.post(() -> {
                if (engine.current != created || !engine.tabs.contains(created) || isFinishing() || isDestroyed()) return;
                address.setText(created.url); address.requestFocus(); address.selectAll();
                getSystemService(InputMethodManager.class).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
            });
        } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }
    /** A private-mode tab, opened from the menu or the tab screen; told once what it leaves behind (nothing). */
    private void newSecretTab() {
        try {
            BrowserEngine.Tab created = engine.newSecretTab("https://www.google.com/");
            Toast.makeText(this, "시크릿 탭이에요 · 닫으면 방문·로그인·AI 대화가 남지 않아요", Toast.LENGTH_LONG).show();
            address.post(() -> {
                if (engine.current != created || !engine.tabs.contains(created) || isFinishing() || isDestroyed()) return;
                address.setText(created.url); address.requestFocus(); address.selectAll();
                getSystemService(InputMethodManager.class).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
            });
        } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }
    /** The tab's name in the strip and the tab screen; a secret tab leads with 🕶, which also becomes its monogram. */
    private static String tabName(BrowserEngine.Tab tab) {
        String name = tab.title == null || tab.title.isBlank() ? host(tab.url) : tab.title;
        return tab.secret ? "🕶 " + name : name;
    }
    private void refreshTabStrip() {
        List<BrowserTabStrip.Item> items = new ArrayList<>();
        for (BrowserEngine.Tab tab : new ArrayList<>(engine.tabs)) {
            String title = tabName(tab);
            items.add(new BrowserTabStrip.Item(tab.id, title, tab == engine.current, engine.aiWorking(tab),
                () -> { if (engine.tabs.contains(tab)) { hideKeyboard(); engine.select(tab); } },
                () -> { if (engine.tabs.contains(tab)) { hideKeyboard(); engine.close(tab); } }));
        }
        tabStrip.render(items, true);
    }
    private LinearLayout.LayoutParams chipParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, ui.dp(44)); p.rightMargin = ui.dp(8); return p; }
    /** The site shown at rest in the address capsule: the host without "www.", or the address itself when it has none. */
    private static String host(String url) {
        try { String h = android.net.Uri.parse(url).getHost(); return h == null || h.isBlank() ? (url == null ? "" : url) : h.replaceFirst("^www\\.", ""); }
        catch (Exception e) { return url == null ? "" : url; }
    }
    /** After the user took over (or the AI stopped), hands the same goal back to the AI from the page as it is now. */
    private void resume() {
        if (engine.lastRequest.isBlank()) return;
        if (engine.paused) engine.loginMode(false);
        // The app wrote this prompt: it carries only the original task's own words, never authority of its own.
        run("이어서 맡기기", BrowserPrompts.resume(engine.taskWords()));
    }
    /** One round action of the menu's top row, as in the iOS share sheet: a 56dp glass circle with its name underneath. */
    private View quickAction(int icon, String label, boolean enabled, Runnable action, Ui.Sheet sheet) {
        LinearLayout item = ui.column(); item.setGravity(Gravity.CENTER_HORIZONTAL); item.setPadding(0, ui.dp(4), 0, ui.dp(4));
        ImageView circle = ui.iconButton(icon, CARD, enabled ? TEXT : MUTED, label, enabled ? () -> { sheet.dismiss(); action.run(); } : () -> {});
        circle.setBackground(ui.shape(FILL, 10)); circle.setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11)); circle.setEnabled(enabled);
        item.addView(circle, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        TextView name = ui.text(label, 12, 550, enabled ? TEXT : MUTED); name.setGravity(Gravity.CENTER); name.setPadding(0, ui.dp(6), 0, 0); name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(name);
        if (!enabled) { item.setAlpha(0.45f); circle.setContentDescription(label + ", 지금은 사용할 수 없음"); }
        return item;
    }
    private View menuRow(int icon, int tint, String title, boolean enabled, Runnable action, Ui.Sheet sheet) {
        LinearLayout row = ui.listRow(icon, tint, title, null, null, enabled ? () -> { sheet.dismiss(); action.run(); } : null);
        if (!enabled) { row.setAlpha(0.38f); row.setContentDescription(title + ", 지금은 사용할 수 없음"); }
        return row;
    }
    private void copyText(String label, String text) { getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText(label, text)); toast("복사했어요"); }
    private void shareText(String text, String subject) {
        try { startActivity(android.content.Intent.createChooser(new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text).putExtra(android.content.Intent.EXTRA_SUBJECT, subject), "공유")); }
        catch (Exception e) { toast("공유할 앱을 찾지 못했어요."); }
    }
    private void openOutside() {
        try { new CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, android.net.Uri.parse(BrowserTools.url(engine.current.url))); }
        catch (Exception e) { toast("시스템 브라우저를 열지 못했어요."); }
    }
    private void offerYouTube(BrowserEngine.Tab tab, String target) {
        String safe = dev.jun.gyeot.core.BrowserYouTubeLinks.webUrl(target);
        if (safe == null || !safe.equals(target) || !engine.canOpenExternalApp(tab) || isFinishing() || isDestroyed()) return;
        if (youtubeDialog != null && youtubeDialog.isShowing()) return;
        String page = tab.url, document = tab.document;
        java.util.concurrent.atomic.AtomicBoolean consent = new java.util.concurrent.atomic.AtomicBoolean(true);
        AlertDialog[] held = new AlertDialog[1];
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("YouTube 앱에서 열까요?")
            .setMessage("현재 브라우저 탭과 대화는 유지돼요. YouTube 앱이 없으면 이 페이지에 그대로 머물러요.\n\n" + safe)
            .setNegativeButton("취소", null).setPositiveButton("YouTube 앱 열기", (d, which) -> {
                if (!consent.compareAndSet(true, false) || youtubeDialog != held[0] || !held[0].isShowing()) return;
                if (!engine.canOpenExternalApp(tab) || isFinishing() || isDestroyed()
                        || !Objects.equals(page, tab.url) || !Objects.equals(document, tab.document)) return;
                try {
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(safe))
                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE).setPackage("com.google.android.youtube");
                    // No parseUri, component, extras, grants or resolver-query visibility expansion.
                    startActivity(intent);
                } catch (android.content.ActivityNotFoundException e) { toast("YouTube 앱을 찾지 못했어요. 웹페이지에서 계속 볼 수 있어요."); }
                catch (SecurityException e) { toast("YouTube 앱을 열 수 없어요. 웹페이지는 그대로 유지했어요."); }
            }).create();
        held[0] = dialog; youtubeDialog = dialog; dialogs.add(dialog);
        dialog.setOnDismissListener(d -> { consent.set(false); dialogs.remove(dialog); if (youtubeDialog == dialog) youtubeDialog = null; });
        dialog.show();
    }
    /**
     * A link to another app the user tapped on the page (tel:, mailto:, sms:, market:, a KakaoTalk or Naver app sign-in's
     * intent://): handed to Android as Chrome does (BrowserAppIntent). When no app takes it, an intent:// link's own web page
     * (browser_fallback_url) opens in the same tab, else Play opens on the app it names.
     */
    private void openAppLink(BrowserEngine.Tab tab, String raw) {
        if (isFinishing() || isDestroyed()) return;
        android.content.Intent intent = BrowserAppIntent.of(raw, getPackageName());
        String app = dev.jun.gyeot.core.BrowserAppLinks.label(raw);
        if (intent == null) { toast(app + " 링크는 열 수 없어요. 웹페이지는 그대로 유지했어요."); return; }
        try { startActivity(intent); return; }
        catch (android.content.ActivityNotFoundException none) { /* below: its web page, or Play */ }
        catch (SecurityException refused) { toast(app + "을(를) 열 수 없어요. 웹페이지는 그대로 유지했어요."); return; }
        String fallback = dev.jun.gyeot.core.BrowserAppLinks.fallback(raw);
        if (!fallback.isEmpty()) { web.post(() -> engine.openFallback(tab, fallback)); toast(app + "이(가) 없어서 웹페이지로 열었어요."); return; }
        String pkg = BrowserAppIntent.packageOf(raw);
        if (!pkg.isEmpty()) try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + pkg))
                .addCategory(android.content.Intent.CATEGORY_BROWSABLE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            toast(app + "이(가) 설치돼 있지 않아 Play 스토어를 열었어요."); return;
        } catch (RuntimeException noStore) { /* below */ }
        toast(app + " 링크를 열 앱이 없어요. 웹페이지는 그대로 유지했어요.");
    }
    /** Opened from a site's question (BrowserTaskService.siteQuestion): that tab comes up and its question with it. */
    private void consumeQuestion() {
        android.content.Intent intent = getIntent();
        if (!navigationResumed || engine == null || engine.historyRestorePending() || intent == null || !intent.hasExtra(BrowserTaskService.QUESTION_TAB_EXTRA)) return;
        String id = intent.getStringExtra(BrowserTaskService.QUESTION_TAB_EXTRA);
        intent.removeExtra(BrowserTaskService.QUESTION_TAB_EXTRA);
        BrowserEngine.Tab asking = id == null ? null : engine.tab(id);
        if (asking == null) { toast("질문한 탭이 이미 닫혔어요."); return; }
        saveDraft();
        if (dictation != null) dictation.cancel();
        engine.select(asking);
    }
    /**
     * The page as drawn now and where it sits in the window, for 화면 보기 (GeckoView draws on a surface of its own, which a
     * copy of the window leaves out); null when there is none. The answer comes on the main thread.
     */
    void pagePixels(java.util.function.BiConsumer<android.graphics.Bitmap, int[]> done) {
        GeckoView view = web;
        if (view == null || !view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) { done.accept(null, null); return; }
        int[] at = new int[2]; view.getLocationInWindow(at);
        try { view.capturePixels().accept(bitmap -> done.accept(bitmap, at), failure -> done.accept(null, null)); }
        catch (RuntimeException failed) { done.accept(null, null); }
    }
    @Override protected void onResume() { super.onResume(); if (voice != null) voice.attach(); navigationResumed = true; AppNavigator.browserInFront(true); AppNavigator.inFront("browser"); updateAiToolbarState(); engine.attach(this::refresh, prompts); engine.externalAppLink = this::offerYouTube; engine.appLink = this::openAppLink; engine.store.observe(storeObserver); consumeTaskNotification(); consumeOpen(); consumeReview(); consumeQuestion(); acknowledgeNavigation(); }
    @Override protected void onPause() {
        if (voice != null) voice.detach();
        recoveryViewSequence++;
        navigationResumed = false; AppNavigator.browserInFront(false); stopProgressUiClock(); detailRefresh = null; progressDetails = null;
        BrowserImeGuard.cancelGesture(web == null ? null : web.getSession());
        // Invalidate synchronously: Dialog's queued dismiss callback may run only after resume.
        AlertDialog leavingYouTube = youtubeDialog; youtubeDialog = null;
        if (leavingYouTube != null) leavingYouTube.dismiss();
        if (dictation != null) dictation.pause();
        saveDraft();
        engine.store.unobserve(storeObserver);
        for (android.app.Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss(); dialogs.clear();
        // Pages keep no text-selection menu that points at a closed screen.
        GeckoSession shown = web.getSession();
        if (shown != null && shown.getSelectionActionDelegate() == selection) shown.setSelectionActionDelegate(null);
        if (splitView != null && splitView.getSession() != null) splitView.releaseSession();
        if (web.getSession() != null) web.releaseSession(); engine.detach(); super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        saveDraft();
        if (pendingDraftState != null) {
            for (String key : new String[]{"browserDraftTab", "browserDraftConversation", "browserDraft"}) if (pendingDraftState.containsKey(key)) out.putString(key, pendingDraftState.getString(key));
            out.putInt("browserDraftStart", pendingDraftState.getInt("browserDraftStart", 0)); out.putInt("browserDraftEnd", pendingDraftState.getInt("browserDraftEnd", 0));
        } else {
            if (draftTab != null) { out.putString("browserDraftTab", draftTab.id); out.putString("browserDraftConversation", draftConversation); }
            out.putString("browserDraft", request.getText().toString()); out.putInt("browserDraftStart", request.getSelectionStart()); out.putInt("browserDraftEnd", request.getSelectionEnd());
        }
        out.putBoolean(PANE_VISIBLE, paneVisible); keepOpened(out, opened); super.onSaveInstanceState(out);
    }
    /** Every request id already opened, into the saved state. */
    static void keepOpened(Bundle out, Collection<String> ids) { out.putStringArrayList(OPENED, new ArrayList<>(ids)); }
    /** The request ids a saved state holds, into ids. */
    static void restoreOpened(Bundle state, Collection<String> ids) { ArrayList<String> kept = state.getStringArrayList(OPENED); if (kept != null) ids.addAll(kept); }
    private void saveDraft() {
        if (request != null && draftTab != null && !draftTab.secret && (engine == null || !engine.historyRestorePending())) DRAFTS.save(draftTab, draftConversation, request.getText().toString(), request.getSelectionStart(), request.getSelectionEnd());
    }
    /** A transient draft follows this exact tab/conversation, never the last visible global input. */
    private void bindDraftScope() {
        BrowserEngine.Tab tab = engine == null ? null : engine.current; if (tab == null || request == null) return;
        boolean pending = engine.historyRestorePending(); request.setEnabled(!pending);
        request.setContentDescription(pending ? "탭을 불러오는 중 · 지시 입력 대기" : "브라우저 AI에게 지시할 내용");
        if (sendButton != null) sendButton.setEnabled(!pending || engine.running());
        if (pending) { request.setHint("불러오는 중"); return; }
        updateComposerLines(engine.transcriptStorageWarning || engine.taskStorageWarning);
        String conversation = tab.conversationId; if (conversation == null || conversation.isBlank()) return;
        if (tab == draftTab && conversation.equals(draftConversation) && pendingDraftState == null) return;
        saveDraft();
        // A restored Bundle may belong to an open background tab. Hydrate only its exact scope,
        // then consume it so a stale/closed scope cannot override the next active draft on rotation.
        if (pendingDraftState != null) {
            for (BrowserEngine.Tab owner : engine.tabs) {
                if (owner.id.equals(pendingDraftState.getString("browserDraftTab")) && owner.conversationId.equals(pendingDraftState.getString("browserDraftConversation"))) {
                    String saved = pendingDraftState.getString("browserDraft", "");
                    DRAFTS.save(owner, owner.conversationId, saved, pendingDraftState.getInt("browserDraftStart", saved.length()), pendingDraftState.getInt("browserDraftEnd", saved.length())); break;
                }
            }
            pendingDraftState = null;
        }
        draftTab = tab; draftConversation = conversation;
        dev.jun.gyeot.core.BrowserDrafts.Draft draft = DRAFTS.restore(tab, conversation);
        String text = draft == null ? "" : draft.text(); int start = draft == null ? 0 : draft.selectionStart(), end = draft == null ? 0 : draft.selectionEnd();
        request.setText(text); int length = request.length(); request.setSelection(Math.max(0, Math.min(start, length)), Math.max(0, Math.min(end, length)));
    }
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent); setIntent(intent); fromHome(intent);
        web.post(() -> { consumeTaskNotification(); consumeOpen(); consumeReview(); consumeQuestion(); acknowledgeNavigation(); });
    }
    /**
     * Opens url in a new tab of the app's browser, the screen brought to the front: a source the main chat cited, or a
     * Google search suggestion. Only an http or https address (BrowserTools.url); anything else throws and nothing starts.
     */
    static void open(android.content.Context context, String url) {
        String page = BrowserTools.url(url);
        context.startActivity(new android.content.Intent(context, BrowserActivity.class).putExtra(OPEN, page).putExtra(OPEN_ID, UUID.randomUUID().toString())
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }
    /**
     * The AI 브라우저 from the home screen's left page (ClaudePage): fading in where the left page's card stands, its claude.ai tab
     * again if one is open, and 홈 the way back.
     */
    static void openFromHome(android.app.Activity home, String url) {
        String page = BrowserTools.url(url);
        home.startActivity(new android.content.Intent(home, BrowserActivity.class).putExtra(OPEN, page).putExtra(OPEN_ID, UUID.randomUUID().toString()).putExtra(HOME_SIDE, true)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            android.app.ActivityOptions.makeCustomAnimation(home, R.anim.page_fade_in, R.anim.page_hold).toBundle());
    }
    /** Whether this screen came from the home screen's left page: its edge swipe and its ⌂ to 홈. */
    private void fromHome(android.content.Intent intent) {
        homeSide = intent != null && intent.getBooleanExtra(HOME_SIDE, false);
        if (homeEdge != null) homeEdge.on(homeSide);
        if (appReturnButton != null) {
            String what = homeSide ? "홈 화면으로 · 탭과 대화 유지" : "앱으로 돌아가기 · 웹페이지 뒤로가기 아님 · 탭과 대화 유지";
            appReturnButton.setContentDescription(what); appReturnButton.setTooltipText(homeSide ? "홈 화면으로" : "앱으로 돌아가기");
        }
    }
    /** Touches go to the right edge's swipe first, when this screen came from the home screen (HomeEdge). */
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (homeEdge != null && homeEdge.touch(e, super::dispatchTouchEvent)) return true;
        return super.dispatchTouchEvent(e);
    }
    /** To 홈: the home screen turns in from the right as this screen goes left, as from the left page to 홈. */
    private void toHome() {
        saveDraft(); if (dictation != null) dictation.cancel();
        android.content.Intent home = new android.content.Intent(this, HomeActivity.class).putExtra(HomeActivity.PAGE, "홈").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(home, android.app.ActivityOptions.makeCustomAnimation(this, R.anim.page_in_right, R.anim.page_out_left).toBundle()); }
        catch (RuntimeException none) { moveTaskToBack(true); return; }
        finish(); overridePendingTransition(R.anim.page_in_right, R.anim.page_out_left);
    }
    /**
     * The page open() asked for, once its tabs have been restored: a new tab, once per request, checked again; as many tabs as
     * the user likes (no cap), so it stays in the AI 브라우저.
     */
    private void consumeOpen() {
        android.content.Intent intent = getIntent();
        if (!navigationResumed || engine == null || engine.historyRestorePending() || intent == null || !intent.hasExtra(OPEN)) return;
        String url = intent.getStringExtra(OPEN), id = intent.getStringExtra(OPEN_ID);
        intent.removeExtra(OPEN); intent.removeExtra(OPEN_ID);
        if (id == null || id.isBlank() || opened.contains(id)) return;
        opened.add(id); while (opened.size() > OPENED_KEPT) opened.remove(opened.iterator().next());
        String page;
        try { page = BrowserTools.url(url == null ? "" : url); } catch (IllegalArgumentException invalid) { toast(invalid.getMessage()); return; }
        // From the home screen's left page: its claude.ai tab again (the conversation left there), else a new one.
        if (homeSide && ClaudePage.inside(page)) {
            BrowserEngine.Tab again = null;
            for (BrowserEngine.Tab tab : engine.tabs) if (ClaudePage.inside(tab.url)) { again = tab; if (tab == engine.current) break; }
            if (again != null) { if (again != engine.current) { saveDraft(); if (dictation != null) dictation.cancel(); engine.select(again); } return; }
        }
        try { saveDraft(); if (dictation != null) dictation.cancel(); engine.newTab(page, true); }
        catch (Exception refused) {
            // Never another app from the home screen (준: "Ai브라우저의 한 탭으로 열려야지": claude.ai had opened in the Claude app).
            if (homeSide) { toast(refused.getMessage()); return; }
            try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(page)).addCategory(android.content.Intent.CATEGORY_BROWSABLE)); }
            catch (Exception none) { toast(refused.getMessage()); }
        }
    }
    /** Notification identity is a process plus an immutable run lease, never the visible tab. */
    private void consumeReview() {
        android.content.Intent intent = getIntent();
        if (!navigationResumed || engine == null || intent == null || !intent.getBooleanExtra(REVIEW_WORK, false)) return;
        String receipt = intent.getStringExtra(REVIEW_RECEIPT);
        intent.removeExtra(REVIEW_WORK); intent.removeExtra(REVIEW_RECEIPT);
        reviewWorkMutation(receipt == null ? "" : receipt);
    }
    private void consumeTaskNotification() {
        android.content.Intent intent = getIntent();
        if (!navigationResumed || engine == null || engine.historyRestorePending() || intent == null || !intent.hasExtra(BrowserTaskService.TASK_LEASE_EXTRA)) return;
        long lease = intent.getLongExtra(BrowserTaskService.TASK_LEASE_EXTRA, -1);
        boolean sameProcess = BrowserTaskService.currentTaskIntent(intent);
        intent.removeExtra(BrowserTaskService.TASK_LEASE_EXTRA);
        intent.removeExtra(BrowserTaskService.TASK_PROCESS_EXTRA);
        BrowserEngine.Tab target = sameProcess ? engine.taskTab(lease) : null;
        if (target == null || !engine.running(lease) || !engine.tabs.contains(target)) {
            toast("이 알림의 작업은 이미 끝났거나 바뀌었어요. 현재 탭은 유지했어요.");
            return;
        }
        saveDraft();
        if (dictation != null) dictation.cancel();
        engine.select(target);
        if (engine.taskTab(lease) != target || !engine.running(lease)) return;
        setPaneVisible(true);
        toast("알림의 작업 탭 · " + BrowserTools.origin(target.url));
    }
    private void acknowledgeNavigation() { AppNavigator.browserShown(this, navigationResumed); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) acknowledgeNavigation(); }
    @Override public void onBackPressed() {
        if (dictation != null) dictation.cancel();
        // Like a sheet in iOS: back first lowers the AI panel from over the page, then walks the page history.
        if (paneVisible && !wide) { setPaneVisible(false); return; }
        if (engine != null && engine.current != null && engine.current.back) { goBackInPage(); return; }
        // From the home screen: back is the page's alone (준: "뒤로가기로 홈페이지 돌아가는 건 넣지마"); 홈 is ⌂ or the edge's swipe.
        if (homeSide) { toast("홈으로는 ⌂ 버튼이나 오른쪽 끝에서 밀어 주세요"); return; }
        leaveBrowser();
    }
    /** The toolbar arrow is page history only; it must never close the browser screen. */
    private boolean goBackInPage() {
        BrowserEngine.Tab tab = engine == null ? null : engine.current;
        if (tab == null || !tab.back) { toast("이전 웹페이지가 없어요"); return false; }
        if (!pageReady(tab)) { toast("페이지를 준비 중이에요"); return false; }
        engine.manual(); tab.session.goBack(); return true;
    }
    private boolean goForwardInPage() {
        BrowserEngine.Tab tab = engine == null ? null : engine.current;
        if (tab == null || !tab.forward) { toast("다음 웹페이지가 없어요"); return false; }
        if (!pageReady(tab)) { toast("페이지를 준비 중이에요"); return false; }
        engine.manual(); tab.session.goForward(); return true;
    }
    /** The separate refresh control reloads the current page, never clears an address draft. */
    private void reloadPage() {
        BrowserEngine.Tab tab = engine == null ? null : engine.current;
        if (!pageReady(tab)) { toast("페이지를 준비 중이에요"); return; }
        engine.manual(); tab.session.reload(); haptic(HapticFeedbackConstants.CLOCK_TICK);
    }
    /** The menu retains load cancellation without adding hidden mobile toolbar slots. */
    private void stopPageLoad() {
        BrowserEngine.Tab tab = engine == null ? null : engine.current;
        if (!pageReady(tab) || !tab.loading) return;
        engine.manual(); tab.session.stop(); haptic(HapticFeedbackConstants.CLOCK_TICK);
    }
    private boolean pageReady(BrowserEngine.Tab tab) {
        return tab != null && !engine.historyRestorePending() && !tab.needsLoad && tab.session.isOpen();
    }
    private void updatePageNavigation() {
        BrowserEngine.Tab tab = engine == null ? null : engine.current;
        boolean ready = pageReady(tab);
        updateNavigationButton(pageBackButton, ready && tab.back, "현재 탭의 이전 웹페이지로 이동",
            ready ? "현재 탭에 이전 웹페이지 없음" : "이전 웹페이지 · 페이지 준비 중");
        updateNavigationButton(pageForwardButton, ready && tab.forward, "현재 탭의 다음 웹페이지로 이동",
            ready ? "현재 탭에 다음 웹페이지 없음" : "다음 웹페이지 · 페이지 준비 중");
        updateNavigationButton(pageRefreshButton, ready, "현재 웹페이지 새로고침", "새로고침 · 페이지 준비 중");
        if (clearAddressButton != null) clearAddressButton.setVisibility(address != null && address.hasFocus() ? View.VISIBLE : View.GONE);
    }
    private void updateNavigationButton(ImageView button, boolean available, String action, String unavailable) {
        if (button == null) return;
        button.setEnabled(available); button.setAlpha(available ? 1f : .35f);
        button.setContentDescription(available ? action : unavailable); button.setTooltipText(available ? action : unavailable);
    }
    /** One-tap, non-destructive return. A notification-launched task root still returns to the app, not Home. */
    private void leaveBrowser() {
        if (homeSide) { toHome(); return; }
        saveDraft();
        if (isTaskRoot()) startActivity(new android.content.Intent(this, MainActivity.class));
        finish();
    }
    private void refresh() {
        // Resolve notification targets only after asynchronous identity/task restoration has settled.
        consumeTaskNotification();
        consumeOpen();
        bindDraftScope();
        if (dictation != null) dictation.validateScope();
        updatePageNavigation();
        BrowserEngine.Tab tab = engine.current; if (tab == null || web == null) return;
        refreshTabStrip();
        if (tabOverviewRefresh != null) tabOverviewRefresh.run();
        if (zoomMenuRefresh != null) zoomMenuRefresh.run();
        installInputGuards();
        // A tab moving from the side of 화면 분할 to the main page leaves the side view first (one view per page).
        if (splitView != null && splitView.getSession() == tab.session) splitView.releaseSession();
        if (web.getSession() != tab.session && tab.session.isOpen()) { if (web.getSession() != null) { BrowserImeGuard.cancelGesture(web.getSession()); web.releaseSession(); } tab.session.setSelectionActionDelegate(selection); web.setSession(tab.session); }
        refreshSplit();
        // At rest the compact address field shows the site, with a lock on secure pages; while editing it holds the full address.
        boolean editing = address.hasFocus(), secure = tab.url != null && tab.url.startsWith("https://");
        if (!editing) { String shown = host(tab.url); if (!address.getText().toString().equals(shown)) address.setText(shown); }
        address.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        siteIcon.setImageResource(editing ? R.drawable.ic_search : secure ? R.drawable.ic_lock : R.drawable.ic_globe);
        siteIcon.setContentDescription(editing ? "검색" : secure ? "보안 연결" : "보안 연결 아님");
        tabButton.setText(Integer.toString(engine.tabs.size())); tabButton.setContentDescription("열린 탭 " + engine.tabs.size() + "개, 탭 전환");
        boolean pageLoading = tab.loading || tab.needsLoad;
        progress.setIndeterminate(tab.needsLoad);
        if (!tab.needsLoad) progress.setProgress(tab.progress);
        progress.setVisibility(pageLoading ? View.VISIBLE : View.INVISIBLE);
        // At large text sizes or with the keyboard up, keep the two-line draft reachable.
        updateComposerLines(engine.transcriptStorageWarning || engine.taskStorageWarning);
        panelHeader.update(host(tab.url));
        showModel();
        boolean anyRunning = engine.running(), otherTask = anyRunning && !engine.taskOnCurrentTab();
        boolean running = anyRunning && !otherTask, failed = !otherTask && engine.resultState.equals("error"), completed = !otherTask && engine.resultState.equals("done");
        boolean attention = !otherTask && (engine.resultState.equals("paused") || engine.paused);
        // One short tap when the work ends: confirm for a result, reject when it failed or needs the user.
        // With the panel closed, the existing toolbar icon exposes status without moving the page.
        if (wasRunning && !anyRunning) {
            haptic(failed || engine.resultState.equals("paused") ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.CONFIRM);
            if (!paneVisible && completed) toast("작업을 마쳤어요 · ✦를 눌러 결과 보기");
        }
        wasRunning = anyRunning;
        // Loading uses the fixed-height progress bar. Inserting/removing a banner on
        // every navigation resized GeckoView and forced the page to lay out again.
        updateAiToolbarState();
        // Only actual answers belong in the conversation; verbose progress stays in 작업 상세.
        String shownResult = !otherTask && !running && completed ? engine.status : "";
        if (shownResult.equals(engine.conversationLastAnswer())) shownResult = "";
        historyWarning.setVisibility((engine.transcriptStorageWarning || engine.taskStorageWarning) ? View.VISIBLE : View.GONE);
        boolean follow = followNextConversation || !engine.conversationId().equals(renderedConversationId) || renderedConversationVersion < 0 || resultBox.getHeight() <= resultScroll.getHeight() + resultScroll.getScrollY() + ui.dp(48);
        boolean scroll = false;
        resultText.setVisibility(shownResult.isBlank() ? View.GONE : View.VISIBLE);
        if (!shownResult.equals(renderedResult)) { renderedResult = shownResult; resultText.setText(shownResult.isEmpty() ? "" : RichText.render(shownResult)); scroll = true; }
        boolean conversationChanged = renderConversation(running);
        scroll |= conversationChanged;
        if (running != (thinkingRow.getVisibility() == View.VISIBLE)) { thinkingRow.setVisibility(running ? View.VISIBLE : View.GONE); thinkingMotion(running); scroll |= running; }
        if (running) showThinking();
        if (conversationChanged) followNextConversation = false;
        askBubble.setVisibility(View.GONE); // The same request is already in the durable transcript.
        resultActions.setVisibility(!anyRunning && !otherTask && completed && !engine.status.isBlank() ? View.VISIBLE : View.GONE);
        statsText.setText(engine.lastStats);
        if (scroll && follow && resultScroll != null) resultScroll.post(() -> resultScroll.smoothScrollTo(0, resultBox.getHeight()));
        resultBox.setVisibility(engine.hasConversation() || !shownResult.isBlank() ? View.VISIBLE : View.GONE);
        sendLooks();
        if (!offersVoice()) sendButton.setContentDescription(running ? "현재 작업에 지시 바로 반영" : "지시 실행");
        if (voice != null) voice.refresh();
    }
    /** Nothing typed: the send button starts 음성 대화 instead. */
    private boolean offersVoice() { return request != null && request.getText().toString().isBlank() && voice != null; }
    private void sendLooks() {
        if (sendButton == null) return;
        VoiceBar.glyph(sendButton, offersVoice(), engine != null && engine.running() ? "현재 작업에 지시 바로 반영" : "지시 실행");
    }
    private void startVoice() {
        if (dictation != null) dictation.cancel();
        if (engine != null) engine.store.stopReading(null);
        setPaneVisible(true);
        voice.start();
    }
    private void setPaneVisible(boolean visible) {
        if (paneVisible == visible) return;
        if (!visible) hideKeyboard();
        paneVisible = visible;
        engine.store.settings.edit().putBoolean(PANE_VISIBLE, visible).apply();
        updatePaneVisibility();
        if (web != null) refresh();
        animatePane(visible);
    }
    /** The panel rises and settles on the same curve as the app's sheets, and slips away quickly; skipped when animations are off. */
    private void animatePane(boolean visible) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        pane.animate().cancel();
        float offset = ui.dp(wide ? 24 : 28);
        if (visible) {
            if (wide) pane.setTranslationX(offset); else pane.setTranslationY(offset);
            pane.setAlpha(0f);
            pane.animate().translationX(0).translationY(0).alpha(1f).setDuration(340).setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start();
        } else {
            pane.setVisibility(View.VISIBLE);
            pane.animate().alpha(0f).translationX(wide ? offset : 0).translationY(wide ? 0 : offset * .6f).setDuration(180).setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> { pane.setAlpha(1f); pane.setTranslationX(0); pane.setTranslationY(0); if (!paneVisible) pane.setVisibility(View.GONE); }).start();
        }
    }
    private void updatePaneVisibility() {
        pane.setVisibility(paneVisible ? View.VISIBLE : View.GONE);
        updateAiToolbarState();
    }
    private boolean hasAiAttention() {
        return engine != null && (engine.running() || engine.paused || "paused".equals(engine.resultState) || "error".equals(engine.resultState));
    }
    /** No extra view is attached for any AI state; details are a dismissible overlay. */
    private void updateAiToolbarState() {
        if (paneToggle == null) return;
        boolean running = engine != null && engine.running() && engine.taskOnCurrentTab();
        boolean otherTask = engine != null && engine.running() && !engine.taskOnCurrentTab();
        boolean failed = engine != null && !otherTask && "error".equals(engine.resultState);
        boolean attention = engine != null && !otherTask && (engine.paused || "paused".equals(engine.resultState));
        String state = running ? "AI 작업 중" : otherTask ? "다른 탭 AI 작업 중" : failed ? "AI 작업 오류" : attention ? "AI 확인 필요" : "";
        if (running) state += " · " + BrowserProgressUi.stage(engine.status);
        String action = state.isBlank() ? (paneVisible ? "AI 패널 접기" : "AI 패널 열기") : state + (running ? " · 작업 상세와 중지 열기" : " · 확인 내용 열기");
        progressAction = action; paneToggle.setTooltipText(action);
        paneToggle.setStateDescription(state); paneToggle.setSelected(paneVisible || hasAiAttention());
        // The AI mark drawn in code (AiMark), light like the toolbar's other icons; the 확인/오류 mark in its place when needed.
        if (attention || failed) paneToggle.setImageResource(R.drawable.ic_info);
        else if (!(paneToggle.getDrawable() instanceof AiMark)) paneToggle.setImageDrawable(new AiMark(getResources().getDisplayMetrics().density));
        int tint = failed ? ERROR : paneVisible || hasAiAttention() ? ACCENT : TEXT;
        paneToggle.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        // No disc behind it: open is told by its color alone, as the toolbar's other icons.
        paneToggle.setBackground(null);
        if (paneToggle instanceof BrowserProgressUi.Indicator indicator) indicator.running(running);
        refreshProgressUiTimes();
    }
    private void refreshProgressUiTimes() {
        BrowserProgressUi.Snapshot snapshot = progressSnapshot();
        boolean running = snapshot.running();
        long now = android.os.SystemClock.elapsedRealtime();
        String elapsed = BrowserProgressUi.elapsed(snapshot, now);
        String description = progressAction + (elapsed.isBlank() ? "" : " · " + elapsed);
        if (paneToggle != null && !android.text.TextUtils.equals(description, paneToggle.getContentDescription())) paneToggle.setContentDescription(description);
        if (detailRefresh != null) detailRefresh.run();
        showThinking();
        if (running && navigationResumed && paneToggle != null) {
            paneToggle.invalidate();
            if (!progressTickScheduled) { progressTickScheduled = true; progressUiHandler.postDelayed(progressUiTick, 250); }
        } else stopProgressUiClock();
    }
    /** What the thinking line says: the work's current step (생각하는 중 until there is one) and its seconds. */
    private void showThinking() {
        if (thinkingText == null || thinkingRow.getVisibility() != View.VISIBLE) return;
        String status = engine.status == null ? "" : engine.status.strip();
        long started = progressSnapshot().observedStartedElapsed();
        long seconds = started < 0 ? 0 : Math.max(0, android.os.SystemClock.elapsedRealtime() - started) / 1000;
        String line = (status.isEmpty() ? "생각하는 중" : BrowserProgressUi.stage(status)) + (seconds >= 1 ? " · " + seconds + "초" : "");
        if (!line.contentEquals(thinkingText.getText())) thinkingText.setText(line);
    }
    /** The AI mark breathes (a little smaller and fainter, then back) while the line shows; still when animations are off. */
    private void thinkingMotion(boolean on) {
        if (on && thinkingSpin == null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            thinkingSpin = android.animation.ObjectAnimator.ofPropertyValuesHolder(thinkingMark,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.72f), android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.72f),
                android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.55f));
            thinkingSpin.setDuration(800); thinkingSpin.setRepeatCount(android.animation.ValueAnimator.INFINITE); thinkingSpin.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            thinkingSpin.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()); thinkingSpin.start();
        } else if (!on && thinkingSpin != null) { thinkingSpin.cancel(); thinkingSpin = null; thinkingMark.setScaleX(1); thinkingMark.setScaleY(1); thinkingMark.setAlpha(1f); }
    }
    private void stopProgressUiClock() {
        progressUiHandler.removeCallbacks(progressUiTick); progressTickScheduled = false;
    }
    private void hideKeyboard() {
        if (address != null) { getSystemService(InputMethodManager.class).hideSoftInputFromWindow(address.getWindowToken(), 0); address.clearFocus(); }
        if (request != null) request.clearFocus();
        if (web != null) web.requestFocus();
    }
    /** A light system haptic; Android skips it when touch feedback is turned off in the phone's settings. */
    private void haptic(int type) { if (web != null) web.performHapticFeedback(type); }
    private void go() {
        if (dictation != null) dictation.cancel();
        String value = address.getText().toString().strip(); if (value.isBlank()) return;
        if (!value.matches("(?i)^https?://.*")) value = value.contains(".") && !value.contains(" ") ? "https://" + value : "https://www.google.com/search?q=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        try { engine.navigate(value); hideKeyboard(); } catch (Exception e) { toast(e.getMessage()); }
    }
    private void ask() {
        String text = request.getText().toString().strip();
        if (deepNext && !text.isBlank() && !engine.running()) { deep(false); submit(text, dev.jun.gyeot.core.BrowserKit.deep(text), true); return; }
        submit(text, text, true);
    }
    /** 끝까지 조사 on for the next request (the chip over the field says so), or off. */
    /** The 들퍼레 folder is connected (and still allowed): saves need no folder question. */
    private boolean sharedFolderConnected() {
        try { SharedOutputFolder.selectedTree(this); return true; } catch (Exception notConnected) { return false; }
    }
    private void deep(boolean on) {
        deepNext = on; if (deepChip != null) deepChip.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) { setPaneVisible(true); request.requestFocus(); getSystemService(InputMethodManager.class).showSoftInput(request, InputMethodManager.SHOW_IMPLICIT); toast("조사할 내용을 적어 보내면 여러 출처를 끝까지 살펴 보고서로 답해요"); }
    }
    /**
     * visible is what the panel shows and keeps in the conversation; text is the request. Typed requests are remembered for
     * the chips. Same-tab task memory is attached separately by BrowserAgent, never reconstructed from display history.
     */
    private void submit(String visible, String text, boolean remember) {
        if (text.isBlank()) return;
        boolean secret = engine.current != null && engine.current.secret; // a secret tab leaves no recent requests either
        if (remember && !secret && !engine.running() && engine.connected()) saveRecents(BrowserPrompts.remember(stored(), text));
        run(visible, withMemory(text));
    }
    /** 기억: with it on, the notes the app keeps about the user that fit this request go along, marked as for the answer only. */
    private String withMemory(String text) {
        if (!engine.store.settings.getBoolean(MEMORY, false) || !engine.store.memoryOn()) return text;
        String header = "\n\n[참고 · 사용자에 대해 기억한 것. 판단과 답에만 쓰고, 웹페이지에 입력하거나 사이트로 보내지 마세요]\n";
        // The notes fit beside the request within BrowserPrompts.REQUEST_LIMIT; a request that fills it goes alone.
        int room = Math.min(MEMORY_NOTES, BrowserPrompts.REQUEST_LIMIT - text.length() - header.length());
        if (room < 200) return text;
        String notes = engine.store.recallMemory(text, room);
        return notes.isBlank() ? text : text + header + notes;
    }
    /** Characters of remembered notes that go along with a request, and of a note written by hand in 기억. */
    private static final int MEMORY_NOTES = 4000, NOTE_LIMIT = 2000;
    /** A note written by hand, cut into pieces Memory keeps whole (Memory.TEXT_LIMIT each), at a sentence end or a space when it can. */
    private static List<String> notePieces(String words) {
        List<String> pieces = new ArrayList<>(); String rest = words.replaceAll("\\s+", " ").strip();
        int most = dev.jun.gyeot.core.Memory.TEXT_LIMIT;
        while (rest.length() > most) {
            int cut = -1;
            for (String end : new String[]{". ", "다. ", "요. ", "! ", "? ", "\n"}) cut = Math.max(cut, rest.lastIndexOf(end, most - end.length()) + end.length() - 1);
            if (cut < most / 2) cut = rest.lastIndexOf(' ', most);
            if (cut < most / 2) cut = most;
            pieces.add(rest.substring(0, cut).strip()); rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) pieces.add(rest);
        return pieces;
    }
    /** 기억: whether the browser's AI may use what the app remembers about the user, and a note written down by hand. */
    private void memorySheet() {
        Ui.Sheet sheet = ui.selectionSheet("기억", "들퍼레가 기억한 나에 대한 것을 AI 브라우저도 참고해요");
        LinearLayout group = ui.group();
        boolean appMemory = engine.store.memoryOn();
        ui.addRow(group, ui.switchRow("AI 브라우저도 내 기억 참고", appMemory ? "요청과 관련된 기억만 함께 보내요 · 페이지에 입력하지 않아요" : "설정 → 기억에서 장기 기억을 먼저 켜 주세요",
            appMemory && engine.store.settings.getBoolean(MEMORY, false), on -> {
                if (on && !engine.store.memoryOn()) { toast("설정 → 기억에서 장기 기억을 먼저 켜 주세요"); return; }
                engine.store.settings.edit().putBoolean(MEMORY, on).apply();
            }), false);
        int about = 0; org.json.JSONArray notes = engine.store.memories();
        for (int i = 0; i < notes.length(); i++) if (dev.jun.gyeot.core.Memory.ABOUT.equals(notes.optJSONObject(i).optString("kind"))) about++;
        sheet.body.addView(group);
        sheet.body.addView(ui.footer("나에 대해 기억한 것 " + about + "개 · 보기와 지우기는 설정 → 기억 → 기억한 것"));
        EditText note = ui.input("기억해 둘 것 (예: 회의록은 표로 정리해 줘)", 2, NOTE_LIMIT);
        sheet.body.addView(note);
        sheet.primary.setText("기억에 적어 두기"); sheet.primary.setOnClickListener(v -> {
            String words = note.getText().toString().strip(); if (words.isEmpty()) { sheet.dismiss(); return; }
            // A note longer than one memory holds is kept whole, as several notes in a row, never cut short.
            List<String> pieces = notePieces(words); long now = System.currentTimeMillis();
            try {
                engine.store.changeMemory(list -> { for (int i = 0; i < pieces.size(); i++) dev.jun.gyeot.core.Memory.add(list, dev.jun.gyeot.core.Memory.ABOUT, pieces.get(i), "", now + i); return null; });
                sheet.dismiss(); toast(pieces.size() > 1 ? "길어서 기억 " + pieces.size() + "개로 나눠 적어 뒀어요" : "기억해 둘게요");
            }
            catch (Exception e) { toast(e.getMessage() == null ? "기억하지 못했어요." : e.getMessage()); }
        });
        show(sheet);
    }
    /** Starts the agent with exactly this request; a finished answer is added to the conversation with its steps and time. */
    private void run(String visible, String text) {
        if (text.isBlank()) return;
        if (engine.running()) {
            if (engine.steerTask(visible, text)) {
                request.setText(""); hideKeyboard(); haptic(HapticFeedbackConstants.CONFIRM);
                followNextConversation = true;
            } else toast("지시를 반영하지 못했어요. 입력은 유지했으니 작업 상태를 확인해 주세요.");
            return;
        }
        if (!engine.canAcceptTask(text)) {
            toast(text.length() > BrowserPrompts.REQUEST_LIMIT ? String.format(java.util.Locale.ROOT, "요청이 %,d자를 넘어요. 나눠서 보내 주세요. 입력은 그대로 두었어요.", BrowserPrompts.REQUEST_LIMIT)
                : "탭의 작업 기억을 불러오는 중이에요. 잠시 후 다시 보내 주세요. 입력은 그대로 두었어요.");
            return;
        }
        if (!engine.connected()) { engine.status = engine.brainName() + " 연결이 필요해요. 모델 선택에서 연결할 수 있어요."; engine.resultState = "error"; engine.changed(); return; }
        BrowserEngine.Tab target = engine.current;
        String conversation = target.conversationId, before = target.task.snapshot();
        long previousLease = engine.lease();
        boolean failed = false;
        try {
            new BrowserAgent(engine, text, visible, result -> {}).start();
        } catch (RuntimeException failure) {
            // A rejected start must not consume the user's draft or leave a half-started lane alive.
            long attempted = engine.lease();
            if (engine.current == target && attempted != previousLease && engine.running(attempted))
                engine.stopTask(attempted, "작업을 시작하지 못했어요. 입력을 확인해 주세요.");
            failed = true;
        }
        if (engine.current == target && conversation.equals(target.conversationId)
                && !before.equals(target.task.snapshot()) && text.equals(target.task.lastRequest())) {
            request.setText(""); hideKeyboard(); haptic(HapticFeedbackConstants.CONFIRM);
            followNextConversation = true;
            if (failed) toast("작업이 중단됐어요. 요청은 이 탭의 대화에 보관했어요.");
        } else if (failed) toast("작업을 시작하지 못했어요. 입력은 유지했어요.");
    }
    /** Runs the newest request again exactly as it was sent, context included. */
    private void again() { if (engine.lastRequest.isBlank()) return; run(engine.lastAsk.isBlank() ? "같은 지시 다시 실행" : engine.lastAsk, engine.lastRequest); }
    /** A new empty active conversation; retained conversations stay reachable under 기록. */
    private void freshConversation() {
        if (engine.running()) { toast("진행 중인 작업을 멈춘 뒤 새 대화를 시작해 주세요."); return; }
        followNextConversation = true;
        if (!engine.freshConversation()) { toast("기록을 불러온 뒤 다시 시도해 주세요."); return; }
        historyWindow = 40;
        haptic(HapticFeedbackConstants.CLOCK_TICK); engine.changed(); toast("새 대화를 시작했어요");
    }
    /** Puts text in the composer with the cursor at its end and the keyboard up, without sending it. */
    private void draft(String text) {
        if (engine.historyRestorePending()) { toast("탭을 불러온 뒤 입력해 주세요."); return; }
        request.setText(text); request.requestFocus(); request.setSelection(request.length());
        getSystemService(InputMethodManager.class).showSoftInput(request, InputMethodManager.SHOW_IMPLICIT);
    }
    private String stored() { return engine.store.settings.getString(RECENTS, ""); }
    private void saveRecents(String value) { engine.store.settings.edit().putString(RECENTS, value).apply(); refresh(); }
    /** The user's own words, right-aligned in the warm bubble used for the user's messages in the main chat. */
    private TextView bubble(String text) {
        TextView view = ui.text(text, 14.5f, 450, TEXT); view.setBackground(ui.shape(BUBBLE_FILL, 18)); view.setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9));
        view.setMaxLines(6); view.setEllipsize(android.text.TextUtils.TruncateAt.END); return view;
    }
    private LinearLayout.LayoutParams bubbleParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.gravity = Gravity.END; p.leftMargin = ui.dp(40); p.topMargin = ui.dp(6); p.bottomMargin = ui.dp(8); return p; }
    /** Render a bounded window without trimming stored messages or expanding the model's context. */
    private boolean renderConversation(boolean running) {
        String conversationId = engine.conversationId();
        boolean changedConversation = !conversationId.equals(renderedConversationId);
        if (changedConversation) { historyWindow = 40; followNextConversation = true; }
        long version = engine.conversationVersion();
        if (!changedConversation && version == renderedConversationVersion && historyWindow == renderedHistoryWindow) return false;
        renderedConversationId = conversationId; renderedConversationVersion = version; renderedHistoryWindow = historyWindow;
        earlierMessages.setVisibility(engine.conversationSize() > historyWindow ? View.VISIBLE : View.GONE);
        conversationBox.removeAllViews();
        JSONArray rows = engine.conversationWindow(historyWindow);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            String role = row.optString("role"), text = row.optString("text");
            if ("user".equals(role)) {
                TextView question = bubble(text); question.setMaxLines(Integer.MAX_VALUE); question.setEllipsize(null); question.setTextIsSelectable(true);
                conversationBox.addView(question, bubbleParams());
            } else if ("boundary".equals(role)) {
                TextView boundary = ui.text(text, 12, 600, MUTED); boundary.setGravity(Gravity.CENTER); boundary.setPadding(0, ui.dp(16), 0, ui.dp(12)); conversationBox.addView(boundary);
            } else {
                TextView answer = ui.text("", 14.5f, 450, "error".equals(row.optString("state")) ? ERROR : TEXT);
                answer.setText(RichText.render(text)); answer.setTextIsSelectable(true); answer.setPadding(0, 0, 0, ui.dp(12)); conversationBox.addView(answer);
            }
        }
        return true;
    }
    /** Optional requests are kept in a menu, never an always-visible recommendation strip. */
    private void requestLibrary() {
        BrowserEngine.Tab tab = engine.current;
        Ui.Sheet sheet = ui.selectionSheet("요청 모음", null);
        LinearLayout rows = ui.group();
        for (BrowserPrompts.Chip suggestion : BrowserPrompts.chips(tab.url, engine.tabs.size()))
            ui.addRow(rows, menuRow(R.drawable.ic_bolt, ACCENT, suggestion.label(), !engine.running(), () -> submit(suggestion.label(), suggestion.request(), false), sheet), true);
        ui.addRow(rows, menuRow(R.drawable.ic_search, MUTED, "웹 검색 입력", true, () -> draft("웹에서 "), sheet), true);
        for (BrowserPrompts.Recent recent : BrowserPrompts.recents(stored()))
            ui.addRow(rows, menuRow(R.drawable.ic_history, MUTED, (recent.pinned() ? "고정 · " : "최근 · ") + BrowserPrompts.label(recent.text()), true, () -> recentMenu(recent), sheet), true);
        sheet.body.addView(rows); show(sheet);
    }
    private void recentMenu(BrowserPrompts.Recent recent) {
        haptic(HapticFeedbackConstants.LONG_PRESS);
        Ui.Sheet sheet = ui.selectionSheet(recent.pinned() ? "고정한 요청" : "최근 요청", BrowserPrompts.label(recent.text()));
        LinearLayout rows = ui.group();
        ui.addRow(rows, menuRow(R.drawable.ic_bolt, Ui.INDIGO, "지금 실행", !engine.running(), () -> submit(recent.text(), recent.text(), true), sheet), true);
        ui.addRow(rows, menuRow(R.drawable.ic_copy, Ui.GREEN, "입력창에 넣기", true, () -> { setPaneVisible(true); draft(recent.text()); }, sheet), true);
        ui.addRow(rows, menuRow(recent.pinned() ? R.drawable.ic_heart : R.drawable.ic_heart_filled, Ui.ORANGE, recent.pinned() ? "고정 해제" : "고정", true, () -> saveRecents(BrowserPrompts.pin(stored(), recent.text(), !recent.pinned())), sheet), true);
        ui.addRow(rows, menuRow(R.drawable.ic_close, Ui.RED, "목록에서 지우기", true, () -> saveRecents(BrowserPrompts.forget(stored(), recent.text())), sheet), true);
        sheet.body.addView(rows); sheet.setOnDismissListener(d -> dialogs.remove(sheet)); show(sheet);
    }
    /** Under the address bar while typing: hand the words to the AI, search Google (or open the address), or reuse a recent request. */
    private void renderOmni() {
        if (omniStrip == null || omniRow == null) return;
        boolean editing = address.hasFocus();
        String typed = address.getText().toString().strip();
        // Focusing first fills in the current page's address; that is not something to hand to the AI.
        boolean pageAddress = engine.current != null && typed.equals(engine.current.url);
        omniRow.removeAllViews();
        if (editing && !typed.isEmpty() && !pageAddress) {
            Button ai = ui.chip("✦ AI에게 맡기기", () -> askFromAddress(typed)); ai.setTextColor(ACCENT); ai.setBackground(ui.shape(ACCENT_SOFT, 99)); ai.setContentDescription("‘" + typed + "’ AI에게 맡기기");
            omniRow.addView(ai, chipParams());
            omniRow.addView(ui.chip(BrowserPrompts.isAddress(typed) ? "주소로 이동" : "Google 검색", this::go), chipParams());
        } else if (editing) for (BrowserPrompts.Recent recent : BrowserPrompts.recents(stored())) {
            Button chip = ui.chip((recent.pinned() ? "📌 " : "↺ ") + BrowserPrompts.label(recent.text()), () -> askFromAddress(recent.text()));
            chip.setContentDescription("최근 요청 " + clipLine(recent.text()) + ", AI에게 맡기기"); omniRow.addView(chip, chipParams());
        }
        omniStrip.setVisibility(omniRow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }
    private void askFromAddress(String text) { if (text.isBlank()) return; hideKeyboard(); setPaneVisible(true); submit(text, text, true); }
    private static final String ASK_AI = "dev.jun.gyeot.ASK_AI", TRANSLATE_AI = "dev.jun.gyeot.TRANSLATE_AI", EXPLAIN_AI = "dev.jun.gyeot.EXPLAIN_AI";
    /** Text selected on the page: translate and explain run at once with the text quoted; ask puts it in the composer. */
    private void selected(String id, String text) {
        if (text == null || text.isBlank()) return;
        setPaneVisible(true);
        if (ASK_AI.equals(id)) { draft(BrowserPrompts.askDraft(text)); return; }
        boolean translate = TRANSLATE_AI.equals(id);
        submit((translate ? "번역 · “" : "설명 · “") + BrowserPrompts.label(text) + "”", translate ? BrowserPrompts.translate(text) : BrowserPrompts.explain(text), false);
    }
    /** GeckoView's own selection menu with three AI actions after the system ones; the selected text goes only to this app's AI. */
    private final class PageSelection extends BasicSelectionActionDelegate {
        PageSelection() { super(BrowserActivity.this); }
        private boolean ours(String id) { return ASK_AI.equals(id) || TRANSLATE_AI.equals(id) || EXPLAIN_AI.equals(id); }
        @Override protected String[] getAllActions() {
            String[] base = super.getAllActions(), all = Arrays.copyOf(base, base.length + 3);
            all[base.length] = ASK_AI; all[base.length + 1] = TRANSLATE_AI; all[base.length + 2] = EXPLAIN_AI;
            return all;
        }
        @Override protected boolean isActionAvailable(String id) {
            if (ours(id)) return mSelection != null && !mSelection.text.isBlank();
            return super.isActionAvailable(id);
        }
        @Override protected void prepareAction(String id, android.view.MenuItem item) {
            switch (id) {
                case ASK_AI -> item.setTitle("AI에게 묻기");
                case TRANSLATE_AI -> item.setTitle("번역");
                case EXPLAIN_AI -> item.setTitle("설명");
                default -> super.prepareAction(id, item);
            }
        }
        @Override protected boolean performAction(String id, android.view.MenuItem item) {
            if (!ours(id)) return super.performAction(id, item);
            String text = mSelection == null ? "" : mSelection.text;
            if (mActionMode != null) mActionMode.finish();
            selected(id, text);
            return true;
        }
    }
    private String dictationScope() { return engine.current == null ? "browser:none" : "browser:" + engine.current.id + ":" + engine.current.url + ":" + engine.current.document + ":" + engine.current.loading; }
    private void speak() {
        if (engine.historyRestorePending()) { toast("탭을 불러온 뒤 음성을 입력해 주세요."); return; }
        if (engine.store.voiceActive || engine.store.recording || engine.store.voiceChat != null) { toast("음성 대화나 녹음을 종료한 뒤 음성을 입력해 주세요."); return; }
        if (!engine.store.secrets.hasWorkKey()) { toast("Gemini 음성 입력에 사용할 API 키가 없어요. 설정에서 Gemini를 연결해 주세요."); return; }
        engine.store.stopReading(null);
        if (dictation == null) dictation = new ComposerDictation(this, request, this::dictationScope, this::toast);
        dictation.begin(() -> new GeminiComposerSession(engine.store.secrets.workKey()), "Gemini 3.5 Transcribe Live");
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (voice != null && voice.permissionResult(code, results)) return;
        if (dictation != null) dictation.permissionResult(code, results);
    }
    @Override protected void onDestroy() { stopProgressUiClock(); progressClock.clear(); if (thinkingMark != null) thinkingMotion(false); if (dictation != null) dictation.close(); super.onDestroy(); }
    @Override protected void onActivityResult(int code, int result, android.content.Intent data) {
        super.onActivityResult(code, result, data);
        if (code == BrowserMusicDownloads.REQUEST_FOLDER) {
            toast(BrowserMusicDownloads.acceptFolder(this, data, result));
            return;
        }
        if (code == REQUEST_BOOKMARKS && result == RESULT_OK && data != null && data.getData() != null) importBookmarks(data.getData());
        if (code == REQUEST_FILES) {
            GeckoSession.PromptDelegate.FilePrompt p = filePrompt; GeckoResult<GeckoSession.PromptDelegate.PromptResponse> answer = fileAnswer;
            filePrompt = null; fileAnswer = null;
            if (p == null || answer == null) return;
            java.util.List<android.net.Uri> picked = new java.util.ArrayList<>();
            if (result == RESULT_OK && data != null) {
                if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) { android.net.Uri u = data.getClipData().getItemAt(i).getUri(); if (u != null) picked.add(u); }
                else if (data.getData() != null) picked.add(data.getData());
            }
            try { answer.complete(picked.isEmpty() ? p.dismiss() : picked.size() == 1 ? p.confirm(this, picked.get(0)) : p.confirm(this, picked.toArray(new android.net.Uri[0]))); }
            catch (RuntimeException unreadable) { answer.complete(p.dismiss()); toast("고른 파일을 페이지에 넣지 못했어요."); }
        }
    }
    /** The line under the composer: the browser AI's model in bold, its thinking level beside it (ModelLine). */
    private void showModel() {
        if (modelLine == null) return;
        BrowserModels models = new BrowserModels(engine.store); String brain = engine.brain();
        if (brain == null || brain.isBlank() || "none".equals(brain)) { ModelLine.show(modelLine, "AI 연결·선택", "", "AI 브라우저"); return; }
        String model = models.model(brain), effort = models.effort(brain, model);
        String name = models.label(brain, model) + ("gpt".equals(brain) && "fast".equals(models.speed(model)) ? " · Fast" : "");
        String engine = switch (brain) { case "gpt" -> dev.jun.gyeot.core.WorkBook.CHATGPT; case "claude" -> dev.jun.gyeot.core.WorkBook.CLAUDE; case "gemini" -> dev.jun.gyeot.core.WorkBook.GEMINI; case "grok" -> dev.jun.gyeot.core.WorkBook.GROK; default -> dev.jun.gyeot.core.WorkBook.NIM; };
        String level = effort == null || effort.isEmpty() ? "" : MainChatPage.level(engine, effort);
        ModelLine.show(modelLine, name, level, "AI 브라우저");
    }
    private void models() {
        showBrowserPopup(BrowserModelUi.create(this, ui, engine.store, engine.brain(), () -> { engine.changed(); refresh(); },
            () -> startActivity(new android.content.Intent(this, MainActivity.class).putExtra("tab", 2))), modelLine, true);
    }
    private void conversationMenu() {
        BrowserEngine.Tab tab = engine.current; if (tab == null) return;
        String id = tab.conversationId, title = host(tab.url) + " · 이 탭의 대화";
        Ui.Sheet sheet = ui.selectionSheet("대화 메뉴", host(tab.url)); LinearLayout rows = ui.group();
        // The AI working in this tab can be stopped from here too, not only from 작업 상세.
        BrowserProgressUi.StopTarget stopping = stopTarget(progressSnapshot());
        if (stopping.canStop()) ui.addRow(rows, menuRow(R.drawable.ic_close, ERROR, "작업 중지", true, stopping::stop, sheet), true);
        ui.addRow(rows, menuRow(R.drawable.ic_chat, ACCENT, "새 대화", engine.canManageConversations(), this::freshConversation, sheet), true);
        ui.addRow(rows, menuRow(R.drawable.ic_history, MUTED, "보관된 대화 기록", true, this::conversationHistory, sheet), true);
        ui.addRow(rows, menuRow(R.drawable.ic_close, ERROR, "이 탭의 대화 지우기", engine.canManageConversations() && engine.hasConversation(), () -> confirmDeleteConversation(id, title, null), sheet), true);
        ui.addRow(rows, menuRow(R.drawable.ic_bolt, ACCENT, "이어서 맡기기", !engine.running() && !engine.lastRequest.isBlank() && (engine.paused || engine.resultState.equals("error") || engine.resultState.equals("stopped")), this::resume, sheet), true);
        ui.addRow(rows, menuRow(R.drawable.ic_history, MUTED, "작업 상세", true, this::detail, sheet), true);
        ui.addRow(rows, menuRow(R.drawable.ic_search, MUTED, "요청 모음", true, this::requestLibrary, sheet), true);
        ui.addRow(rows, menuRow(R.drawable.ic_mic, MUTED, "음성으로 입력 · 초안만", true, this::speak, sheet), true);
        sheet.body.addView(rows); showBrowserPopup(sheet, panelHeader.menuButton, false);
    }
    private void menu() {
        // Grouped icon rows, as in a system menu: moving around, sharing the page, the AI, then logins.
        BrowserEngine.Tab tab = engine.current;
        Ui.Sheet sheet = ui.selectionSheet("브라우저", host(tab.url));
        // As in the iOS share sheet: the everyday page actions as round buttons across the top, then the grouped rows.
        LinearLayout quick = ui.row(); quick.setGravity(Gravity.TOP);
        quick.addView(quickAction(R.drawable.ic_nav_back, "페이지 뒤로", tab.back && pageReady(tab), this::goBackInPage, sheet), new LinearLayout.LayoutParams(0, -2, 1));
        quick.addView(quickAction(R.drawable.ic_nav_forward, "앞으로", tab.forward && pageReady(tab), this::goForwardInPage, sheet), new LinearLayout.LayoutParams(0, -2, 1));
        quick.addView(quickAction(tab.loading ? R.drawable.ic_close : R.drawable.ic_refresh, tab.loading ? "로드 중지" : "새로고침", pageReady(tab), tab.loading ? this::stopPageLoad : this::reloadPage, sheet), new LinearLayout.LayoutParams(0, -2, 1));
        quick.addView(quickAction(R.drawable.ic_share, "공유", true, () -> shareText(tab.url, tab.title), sheet), new LinearLayout.LayoutParams(0, -2, 1));
        ui.add(sheet.body, quick, 16);
        LinearLayout moves = ui.group();
        ui.addRow(moves, menuRow(R.drawable.ic_home, MUTED, "앱으로 돌아가기", true, this::leaveBrowser, sheet), true);
        ui.addRow(moves, menuRow(R.drawable.ic_layers, MUTED, "열린 탭 " + engine.tabs.size() + "개", true, this::tabs, sheet), true);
        // 시크릿 탭: its own cookies and logins, nothing kept once it closes (no restore, no 대화 기록).
        ui.addRow(moves, menuRow(R.drawable.ic_lock, MUTED, "새 시크릿 탭", true, this::newSecretTab, sheet), true);
        boolean marked = dev.jun.gyeot.core.BrowserKit.bookmarked(engine.store.settings.getString(BOOKMARKS, ""), tab.url);
        ui.addRow(moves, menuRow(marked ? R.drawable.ic_heart_filled : R.drawable.ic_heart, Ui.PINK, marked ? "북마크에서 빼기" : "북마크에 추가", pageReady(tab), () -> toggleBookmark(tab), sheet), true);
        ui.addRow(moves, menuRow(R.drawable.ic_book, Ui.PINK, "북마크", true, this::bookmarks, sheet), true);
        ui.addRow(moves, menuRow(R.drawable.ic_layers, Ui.PINK, "프로젝트 · 탭 묶음", true, this::projects, sheet), true);
        if (splitRoom()) ui.addRow(moves, menuRow(R.drawable.ic_layers, Ui.BLUE, engine.split == null ? "화면 분할 · 두 페이지 나란히" : "화면 분할 바꾸기·끄기", true, this::splitMenu, sheet), true);
        ui.addRow(moves, menuRow(R.drawable.ic_globe, Ui.BLUE, tab.desktop ? "모바일 사이트로 보기" : "데스크톱 사이트로 보기", true, () -> engine.setDesktop(tab, !tab.desktop), sheet), true);
        ui.add(sheet.body, moves, 12);
        // These controls deliberately keep the menu open for repeated adjustments.
        LinearLayout zoom = ui.column(); zoom.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4));
        TextView zoomTitle = ui.text("페이지 배율", 13, 600, MUTED); zoom.addView(zoomTitle);
        LinearLayout zoomButtons = ui.row(); zoom.addView(zoomButtons);
        Button smaller = ui.chip("−", () -> {}), reset = ui.chip("100%", () -> {}), larger = ui.chip("+", () -> {});
        smaller.setPadding(ui.dp(8), 0, ui.dp(8), 0); larger.setPadding(ui.dp(8), 0, ui.dp(8), 0); reset.setPadding(ui.dp(6), 0, ui.dp(6), 0); reset.setSingleLine(true);
        smaller.setContentDescription("페이지 배율 10퍼센트 줄이기"); larger.setContentDescription("페이지 배율 10퍼센트 늘리기");
        reset.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView zoomNotice = ui.text("", 12, 450, MUTED);
        Runnable updateZoom = () -> {
            zoomNotice.setText(tab.zoomFailed ? "이 페이지에서는 배율을 적용하지 못했어요." : tab.appliedZoom != tab.zoomPercent ? tab.zoomPercent + "% 적용 대기 · 페이지 연결 후 적용돼요." : "적용됨 · 배율을 누르면 100%로 돌아가요.");
            reset.setText(tab.zoomPercent + "%"); reset.setContentDescription("페이지 배율 " + tab.zoomPercent + "퍼센트, 눌러서 100퍼센트로 초기화");
            smaller.setEnabled(tab.zoomPercent > BrowserEngine.ZOOM_LEAST); larger.setEnabled(tab.zoomPercent < BrowserEngine.ZOOM_MOST);
            smaller.setAlpha(smaller.isEnabled() ? 1f : .38f); larger.setAlpha(larger.isEnabled() ? 1f : .38f);
        };
        smaller.setOnClickListener(v -> { engine.setZoom(tab, Math.max(BrowserEngine.ZOOM_LEAST, tab.zoomPercent - 10)); updateZoom.run(); });
        reset.setOnClickListener(v -> { engine.setZoom(tab, 100); updateZoom.run(); });
        larger.setOnClickListener(v -> { engine.setZoom(tab, Math.min(BrowserEngine.ZOOM_MOST, tab.zoomPercent + 10)); updateZoom.run(); });
        for (Button button : new Button[]{smaller, reset, larger}) { button.setTextSize(14); button.setBackground(ui.shape(FILL, 10)); zoomButtons.addView(button, new LinearLayout.LayoutParams(0, ui.dp(48), 1)); }
        zoomMenuRefresh = updateZoom;
        sheet.setOnDismissListener(d -> { if (zoomMenuRefresh == updateZoom) zoomMenuRefresh = null; dialogs.remove(sheet); });
        updateZoom.run(); ui.add(sheet.body, zoom, 12); ui.add(sheet.body, zoomNotice, 8);
        LinearLayout sharing = ui.group();
        ui.addRow(sharing, menuRow(R.drawable.ic_copy, Ui.GREEN, "주소 복사", true, () -> copyText("웹 주소", tab.url), sheet), true);
        ui.addRow(sharing, menuRow(R.drawable.ic_globe, Ui.GREEN, "시스템 브라우저에서 열기", true, this::openOutside, sheet), true);
        String youtubePage = tab.url;
        String youtube = dev.jun.gyeot.core.BrowserYouTubeLinks.webUrl(youtubePage);
        if (youtube != null) ui.addRow(sharing, menuRow(R.drawable.ic_video, Ui.RED, "YouTube 앱에서 열기", engine.canOpenExternalApp(tab), () -> { if (Objects.equals(youtubePage, tab.url)) offerYouTube(tab, youtube); }, sheet), true);
        // Music goes to the 들퍼레 folder the app already uses (들퍼레/음악): it is asked for only when none is connected yet.
        boolean musicFolder = sharedFolderConnected();
        ui.addRow(sharing, menuRow(R.drawable.ic_tune, Ui.GREEN, musicFolder ? "음악 저장 위치 · 들퍼레/음악" : "음악 저장 폴더 연결", true, () -> {
            if (!musicFolder) { BrowserMusicDownloads.chooseFolder(this, BrowserMusicDownloads.REQUEST_FOLDER); return; }
            new AlertDialog.Builder(this).setTitle("음악 저장 위치")
                .setMessage("이미 연결된 들퍼레 폴더를 써요. 받은 음악은 내장 저장공간/들퍼레/음악에 저장되고, 기존 파일은 그대로 둬요.")
                .setPositiveButton("확인", null)
                .setNeutralButton("권한 다시 설정", (dialog, which) -> BrowserMusicDownloads.chooseFolder(this, BrowserMusicDownloads.REQUEST_FOLDER)).show();
        }, sheet), true);
        ui.add(sheet.body, sharing, 12);
        String deleteConversationId = tab.conversationId;
        String deleteConversationTitle = host(tab.url) + " · 이 탭의 대화";
        LinearLayout assistant = ui.group();
        ui.addRow(assistant, menuRow(R.drawable.ic_ai_chat, Ui.INDIGO, paneVisible ? "AI 패널 접기" : "AI 패널 열기", true, () -> setPaneVisible(!paneVisible), sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_tune, Ui.INDIGO, "모델·생각 깊이", true, this::models, sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_lock, Ui.INDIGO, "권한 모드 · " + (engine.readOnly() ? "읽기만" : PERMISSION_FULL), true, this::permissionMode, sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_search, Ui.INDIGO, deepNext ? "끝까지 조사 끄기" : "끝까지 조사로 맡기기", !engine.running(), () -> deep(!deepNext), sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_bolt, Ui.INDIGO, "스킬 · 자주 하는 일", true, this::skills, sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_bulb, Ui.INDIGO, "기억 · " + (engine.store.settings.getBoolean(MEMORY, false) && engine.store.memoryOn() ? "참고함" : "참고 안 함"), true, this::memorySheet, sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_history, Ui.INDIGO, "작업 상세", true, this::detail, sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_info, Ui.ORANGE, "확인 필요한 작업", true, this::reviewPendingMutation, sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_bolt, Ui.INDIGO, "같은 지시 다시 실행", !engine.lastRequest.isBlank() && !engine.running(), this::again, sheet), true);
        // 예약 작업: the AI browser does this at set times in a background tab, from the page open now.
        String startPage = tab == null || tab.url == null ? "" : tab.url;
        ui.addRow(assistant, menuRow(R.drawable.ic_history, Ui.INDIGO, "이 페이지에서 할 일 예약", true, () -> scheduleHere(startPage), sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_history, Ui.INDIGO, "예약 작업 목록", true, () -> schedules().show(), sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_chat, Ui.INDIGO, "새 대화", engine.canManageConversations(), this::freshConversation, sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_history, Ui.INDIGO, "보관된 대화 기록", true, this::conversationHistory, sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_close, ERROR, "이 탭의 대화 지우기", engine.canManageConversations() && engine.hasConversation(), () -> confirmDeleteConversation(deleteConversationId, deleteConversationTitle, null), sheet), true);
        ui.addRow(assistant, menuRow(R.drawable.ic_close, Ui.INDIGO, "최근·고정 요청 모두 지우기", !stored().isBlank(), () -> show(ui.confirm("AI 패널의 최근 요청과 고정한 요청을 모두 지울까요?", "모두 지우기", () -> saveRecents(""))), sheet), true);
        ui.add(sheet.body, assistant, 12);
        LinearLayout login = ui.group();
        ui.addRow(login, menuRow(R.drawable.ic_key, Ui.ORANGE, engine.paused ? "AI 다시 사용" : "로그인 관리", true, this::loginMenu, sheet), true);
        ui.addRow(login, menuRow(R.drawable.ic_lock, Ui.ORANGE, "비밀번호", true, () -> passwords().show(), sheet), true);
        sheet.body.addView(login); showBrowserPopup(sheet, browserMenuButton, false);
    }
    /** 예약 작업 (WorkSchedulesUi); a run's results open in 작업창. */
    private WorkSchedulesUi schedules() {
        return new WorkSchedulesUi(this, ui, engine.store, room -> startActivity(new android.content.Intent(this, MainActivity.class).putExtra("tab", 3).putExtra("work", room)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)));
    }
    /** A browser task from this page: what is typed in the AI box (else the last request) as the request to repeat. */
    private void scheduleHere(String startPage) {
        String typed = request == null ? "" : request.getText().toString().strip();
        schedules().edit(null, typed.isEmpty() ? engine.lastRequest : typed, null, startPage);
    }
    /** Review this tab first; a different/background receipt never substitutes for its approval. */
    private void reviewPendingMutation() {
        BrowserEngine.PromptToken token = engine.promptToken(engine.current == null ? null : engine.current.session);
        JSONObject receipt = engine.pendingTabMutation(token);
        if (!receipt.optBoolean("ok") || receipt.optBoolean("pending")) {
            long view = ++recoveryViewSequence;
            showMutationReview(receipt, view, "현재 탭 작업 직접 확인", (id, done) -> engine.acknowledgeTabMutation(token, id, done));
        } else reviewWorkMutation("");
    }
    /** Only direct user inspection can clear an exact durable receipt; this never replays work. Each Work job has its own:
     * the notification's (receiptId), else the newest. */
    private void reviewWorkMutation(String receiptId) {
        long view = ++recoveryViewSequence;
        BrowserEngine.pendingWorkMutation(this, receiptId, receipt -> {
            if (view != recoveryViewSequence || !navigationResumed || isFinishing() || isDestroyed()) return;
            showWorkMutationReview(receipt, view);
        });
    }
    private void showWorkMutationReview(JSONObject receipt, long view) {
        showMutationReview(receipt, view, "백그라운드 작업 직접 확인", (id, done) -> BrowserEngine.acknowledgeWorkMutation(this, id, done));
    }
    private void showMutationReview(JSONObject receipt, long view, String title,
            java.util.function.BiConsumer<String, java.util.function.Consumer<Boolean>> acknowledge) {
        if (!receipt.optBoolean("ok")) { toast("작업 확인 기록을 읽지 못했어요. 기록은 유지돼요."); return; }
        if (!receipt.optBoolean("pending")) { toast("직접 확인이 필요한 백그라운드 작업이 없어요."); return; }
        if (receipt.optBoolean("active")) { toast("해당 동작이 아직 실행 중이에요. 실행이 끝난 뒤 상태를 확인해 주세요."); return; }
        String id = receipt.optString("receipt_id");
        if (id.isBlank()) { toast("작업 확인 기록이 올바르지 않아 유지했어요."); return; }
        Ui.Sheet sheet = ui.new Sheet(title, "실행 결과가 확인되지 않은 작업이에요. 사이트에서 실제 결과를 확인해 주세요.");
        String details = "작업: " + reviewText(receipt.optString("job_id", "현재 탭"), 160)
            + "\n사이트: " + reviewText(receipt.optString("url"), 2048)
            + "\n요청: " + reviewText(receipt.optString("request"), 8000)
            + "\n행동: " + reviewText(receipt.optString("action"), 80)
            + "\n중지 이유: " + reviewText(receipt.optString("reason"), 160)
            + (receipt.optString("stop_reason").isBlank() ? "" : "\n" + reviewText(receipt.optString("stop_reason"), 1600));
        TextView context = ui.label(details, 14, TEXT); context.setTextIsSelectable(true); sheet.body.addView(context);
        sheet.body.addView(ui.label("아래 확인은 앞으로 직접 요청할 작업의 차단만 해제해요. 이전 작업을 다시 실행하거나 성공 처리하지 않아요. 실제 결과를 모르면 닫고 기록을 유지해 주세요.", 14, MUTED));
        CheckBox inspected = new CheckBox(this); inspected.setText("사이트에서 이 작업의 실제 결과를 직접 확인했어요"); sheet.body.addView(inspected);
        sheet.primary.setText("확인 기록하고 차단 해제"); sheet.primary.setEnabled(false);
        inspected.setOnCheckedChangeListener((button, checked) -> sheet.primary.setEnabled(checked));
        sheet.primary.setOnClickListener(button -> {
            if (!sheet.isShowing() || !sheet.primary.isEnabled() || !inspected.isChecked() || view != recoveryViewSequence || !navigationResumed || isFinishing() || isDestroyed()) return;
            sheet.primary.setEnabled(false); inspected.setEnabled(false);
            acknowledge.accept(id, cleared -> {
                if (view != recoveryViewSequence || !navigationResumed || isFinishing() || isDestroyed()) return;
                sheet.dismiss();
                int more = receipt.optInt("more");
                toast(!cleared ? "기록이 바뀌었거나 저장하지 못했어요. 차단은 유지되니 다시 확인해 주세요."
                    : "확인을 기록했어요. 이전 작업은 다시 실행하지 않았어요." + (more > 0 ? " 확인할 다른 작업이 " + more + "개 더 있어요(메뉴 → 확인 필요한 작업)." : ""));
            });
        });
        sheet.setOnDismissListener(dialog -> { dialogs.remove(sheet); if (view == recoveryViewSequence) recoveryViewSequence++; });
        show(sheet);
    }
    private static String reviewText(String value, int limit) {
        String text = value == null ? "" : value;
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
    /** Rows a long list (스킬, 프로젝트, 북마크) draws at first, and again on each "N개 더 보기"; 작업 상세's newest log lines. */
    static final int LIST_PAGE = 50, DETAIL_LINES = 12;
    /** "스킬 N개 더 보기" under a list drawn LIST_PAGE rows at a time (only what is seen is drawn). */
    private TextView moreRow(String what, int hidden, Runnable more) {
        TextView row = ui.text(what + " " + hidden + "개 더 보기", 13, 650, ACCENT); row.setGravity(Gravity.CENTER); row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        row.setOnClickListener(v -> more.run()); Ui.pressable(row, true); return row;
    }
    /** A long text as one row's subtitle: only what a line can show is laid out. */
    private static String clipLine(String text) {
        String line = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return line.length() > 120 ? line.substring(0, 119) + "…" : line;
    }
    // ── 스킬 ──────────────────────────────────────────────────────────────

    /** 스킬: requests kept by name to run again (optionally from a start page); tap runs, swipe deletes, long press edits. */
    private void skills() {
        Ui.Sheet sheet = ui.selectionSheet("스킬", "자주 하는 일을 이름 붙여 두고 한 번에 맡겨요");
        Runnable[] fill = new Runnable[1]; int[] shown = {LIST_PAGE};
        fill[0] = () -> {
            sheet.body.removeAllViews();
            List<dev.jun.gyeot.core.BrowserKit.Skill> list = dev.jun.gyeot.core.BrowserKit.skills(engine.store.settings.getString(SKILLS, ""));
            if (list.isEmpty()) sheet.body.addView(ui.footer("아직 스킬이 없어요. 예: '오늘 IT 뉴스 5개 정리', '이 쇼핑몰 최저가 비교'."));
            else {
                LinearLayout group = ui.group();
                for (dev.jun.gyeot.core.BrowserKit.Skill skill : list.subList(0, Math.min(list.size(), shown[0]))) {
                    LinearLayout row = ui.listRow(R.drawable.ic_bolt, Ui.INDIGO, skill.name(), (skill.url().isBlank() ? "" : host(skill.url()) + " · ") + clipLine(skill.prompt()), ui.chevron(), () -> {
                        if (engine.running()) { toast("진행 중인 작업이 끝난 뒤 실행해 주세요."); return; }
                        sheet.dismiss(); setPaneVisible(true); submit(skill.name(), skill.request(), false);
                    });
                    row.setOnLongClickListener(v -> { sheet.dismiss(); editSkill(skill); return true; });
                    ui.addRow(group, SwipeDelete.wrap(ui, row, "이 스킬", () -> {
                        engine.store.settings.edit().putString(SKILLS, dev.jun.gyeot.core.BrowserKit.deleteSkill(engine.store.settings.getString(SKILLS, ""), skill.id())).apply(); return true;
                    }), true);
                }
                sheet.body.addView(group);
                if (list.size() > shown[0]) sheet.body.addView(moreRow("스킬", list.size() - shown[0], () -> { shown[0] += LIST_PAGE; fill[0].run(); }));
                sheet.body.addView(ui.footer("누르면 실행 · 길게 누르면 고치기 · 왼쪽으로 밀면 지우기"));
            }
            if (!engine.lastRequest.isBlank()) sheet.body.addView(ui.button("방금 요청을 스킬로 저장", false, () -> { sheet.dismiss(); editSkill(new dev.jun.gyeot.core.BrowserKit.Skill("", "", engine.lastRequest, "", 0)); }));
        };
        sheet.primary.setText("새 스킬"); sheet.primary.setOnClickListener(v -> { sheet.dismiss(); editSkill(null); });
        fill[0].run(); show(sheet);
    }
    private void editSkill(dev.jun.gyeot.core.BrowserKit.Skill skill) {
        Ui.Sheet sheet = ui.selectionSheet(skill == null || skill.id().isBlank() ? "새 스킬" : "스킬 고치기", null);
        EditText name = ui.input("이름 (예: 아침 뉴스 정리)", 1, dev.jun.gyeot.core.BrowserKit.SKILL_NAME), words = ui.input("할 일", 3, dev.jun.gyeot.core.BrowserKit.SKILL_PROMPT),
            page = ui.input("시작 페이지 (선택)", 1, dev.jun.gyeot.core.BrowserKit.URL_LIMIT);
        if (skill != null) { name.setText(skill.name()); words.setText(skill.prompt()); page.setText(skill.url()); }
        else if (engine.current != null && engine.current.url != null && engine.current.url.startsWith("http")) page.setHint("시작 페이지 (선택) · 예: " + host(engine.current.url));
        page.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        sheet.body.addView(name); sheet.body.addView(words); sheet.body.addView(page);
        if (engine.current != null && engine.current.url != null && engine.current.url.startsWith("http"))
            sheet.body.addView(ui.button("지금 페이지를 시작 페이지로", false, () -> page.setText(engine.current.url)));
        sheet.primary.setText("저장"); sheet.primary.setOnClickListener(v -> {
            try {
                engine.store.settings.edit().putString(SKILLS, dev.jun.gyeot.core.BrowserKit.saveSkill(engine.store.settings.getString(SKILLS, ""), skill == null ? "" : skill.id(),
                    name.getText().toString(), words.getText().toString(), page.getText().toString(), System.currentTimeMillis())).apply();
                sheet.dismiss(); toast("스킬을 저장했어요"); skills();
            } catch (RuntimeException e) { toast(e.getMessage()); }
        });
        show(sheet);
    }

    // ── 프로젝트 ──────────────────────────────────────────────────────────

    /** 프로젝트: the open tabs kept under a name, opened again as tabs later (those already open are not doubled). */
    private void projects() {
        Ui.Sheet sheet = ui.selectionSheet("프로젝트", "함께 보던 탭들을 이름 붙여 두고 나중에 한 번에 다시 열어요");
        List<dev.jun.gyeot.core.BrowserKit.Project> list = dev.jun.gyeot.core.BrowserKit.projects(engine.store.settings.getString(PROJECTS, ""));
        if (list.isEmpty()) sheet.body.addView(ui.footer("아직 프로젝트가 없어요. 예: 여행 준비, 노트북 고르기 — 관련 탭을 열어 둔 채 아래 버튼을 눌러요."));
        else {
            LinearLayout group = ui.group(); int[] shown = {0};
            Runnable[] more = new Runnable[1]; TextView[] moreView = new TextView[1];
            more[0] = () -> {
                int until = Math.min(list.size(), shown[0] + LIST_PAGE);
                for (dev.jun.gyeot.core.BrowserKit.Project project : list.subList(shown[0], until)) {
                    StringBuilder sites = new StringBuilder(); int named = 0;
                    for (dev.jun.gyeot.core.BrowserKit.Bookmark b : project.pages()) { if (named++ == 4) { sites.append(" 외 ").append(project.pages().size() - 4).append("곳"); break; } sites.append(sites.length() == 0 ? "" : " · ").append(host(b.url())); }
                    View row = ui.listRow(R.drawable.ic_layers, Ui.PINK, project.name(), "탭 " + project.pages().size() + "개 · " + sites, ui.chevron(), () -> { sheet.dismiss(); openProject(project); });
                    ui.addRow(group, SwipeDelete.wrap(ui, row, "이 프로젝트", () -> {
                        engine.store.settings.edit().putString(PROJECTS, dev.jun.gyeot.core.BrowserKit.deleteProject(engine.store.settings.getString(PROJECTS, ""), project.id())).apply(); return true;
                    }), true);
                }
                shown[0] = until;
                if (moreView[0] != null) { sheet.body.removeView(moreView[0]); moreView[0] = null; }
                if (shown[0] < list.size()) { moreView[0] = moreRow("프로젝트", list.size() - shown[0], more[0]); sheet.body.addView(moreView[0], sheet.body.indexOfChild(group) + 1); }
            };
            sheet.body.addView(group); more[0].run();
            sheet.body.addView(ui.footer("누르면 그 탭들을 열어요 · 왼쪽으로 밀면 지우기 · 같은 이름으로 저장하면 바뀐 탭으로 고쳐요"));
        }
        sheet.primary.setText("지금 탭들을 프로젝트로 저장"); sheet.primary.setOnClickListener(v -> { sheet.dismiss(); saveProject(); });
        show(sheet);
    }
    private void saveProject() {
        List<dev.jun.gyeot.core.BrowserKit.Bookmark> pages = new ArrayList<>();
        for (BrowserEngine.Tab tab : new ArrayList<>(engine.tabs)) if (tab.url != null && tab.url.startsWith("http"))
            pages.add(new dev.jun.gyeot.core.BrowserKit.Bookmark(tab.url, tab.title == null || tab.title.isBlank() ? host(tab.url) : tab.title, "", System.currentTimeMillis()));
        if (pages.isEmpty()) { toast("저장할 웹 페이지 탭이 없어요."); return; }
        Ui.Sheet sheet = ui.selectionSheet("프로젝트로 저장", "열린 탭 " + pages.size() + "개");
        EditText name = ui.input("이름 (예: 여행 준비)", 1, dev.jun.gyeot.core.BrowserKit.PROJECT_NAME); sheet.body.addView(name);
        sheet.primary.setText("저장"); sheet.primary.setOnClickListener(v -> {
            try {
                engine.store.settings.edit().putString(PROJECTS, dev.jun.gyeot.core.BrowserKit.saveProject(engine.store.settings.getString(PROJECTS, ""), name.getText().toString(), pages, System.currentTimeMillis())).apply();
                sheet.dismiss(); toast("‘" + name.getText().toString().strip() + "’ 프로젝트로 저장했어요");
            } catch (RuntimeException e) { toast(e.getMessage()); }
        });
        show(sheet);
    }
    /** Opens a project's pages as tabs: one already open is only selected, the rest each in a new tab. */
    private void openProject(dev.jun.gyeot.core.BrowserKit.Project project) {
        int opened = 0, skipped = 0; BrowserEngine.Tab first = null;
        for (dev.jun.gyeot.core.BrowserKit.Bookmark page : project.pages()) {
            BrowserEngine.Tab open = null;
            for (BrowserEngine.Tab tab : engine.tabs) if (dev.jun.gyeot.core.BrowserKit.bookmarked(new org.json.JSONArray().put(page.json()).toString(), tab.url)) { open = tab; break; }
            if (open == null) {
                try { open = engine.newTab(page.url(), false); opened++; } catch (Exception e) { skipped++; continue; }
            }
            if (first == null) first = open;
        }
        if (first != null && engine.tabs.contains(first)) engine.select(first);
        toast("‘" + project.name() + "’ · 새 탭 " + opened + "개" + (skipped > 0 ? " · " + skipped + "개는 못 열었어요" : ""));
    }

    // ── 북마크 ────────────────────────────────────────────────────────────

    private void toggleBookmark(BrowserEngine.Tab tab) {
        if (tab == null || tab.url == null || !tab.url.startsWith("http")) return;
        String saved = engine.store.settings.getString(BOOKMARKS, "");
        if (dev.jun.gyeot.core.BrowserKit.bookmarked(saved, tab.url)) { engine.store.settings.edit().putString(BOOKMARKS, dev.jun.gyeot.core.BrowserKit.deleteBookmark(saved, tab.url)).apply(); toast("북마크에서 뺐어요"); }
        else {
            String title = tab.title == null || tab.title.isBlank() ? host(tab.url) : tab.title;
            dev.jun.gyeot.core.BrowserKit.Added added = dev.jun.gyeot.core.BrowserKit.add(saved, List.of(new dev.jun.gyeot.core.BrowserKit.Bookmark(tab.url, title, "", System.currentTimeMillis())));
            engine.store.settings.edit().putString(BOOKMARKS, added.saved()).apply();
            // Past MAX_BOOKMARKS the oldest goes, and the user hears which one: never let go silently.
            toast(added.note().isEmpty() ? "북마크에 추가했어요" : "북마크에 추가했어요 · " + added.note());
        }
    }
    /** 북마크: kept pages, searched, opened with a tap, deleted with a swipe; other browsers' bookmarks are brought in from their export file. */
    private void bookmarks() {
        Ui.Sheet sheet = ui.selectionSheet("북마크", null);
        EditText search = ui.input("북마크 검색", 1, 100); sheet.controls.addView(search);
        Runnable[] fill = new Runnable[1]; int[] page = {dev.jun.gyeot.core.BrowserKit.MAX_BOOKMARKS};
        fill[0] = () -> {
            sheet.body.removeAllViews();
            String query = search.getText().toString().strip().toLowerCase(java.util.Locale.ROOT);
            List<dev.jun.gyeot.core.BrowserKit.Bookmark> list = dev.jun.gyeot.core.BrowserKit.bookmarks(engine.store.settings.getString(BOOKMARKS, ""));
            // At most MAX_BOOKMARKS are kept, all drawn; a list saved before that cap draws that many at a time.
            LinearLayout group = ui.group(); int shown = 0, more = 0;
            for (dev.jun.gyeot.core.BrowserKit.Bookmark b : list) {
                if (!query.isEmpty() && !(b.title() + " " + b.url() + " " + b.folder()).toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
                if (shown >= page[0]) { more++; continue; }
                View row = ui.listRow(R.drawable.ic_globe, Ui.PINK, b.title(), (b.folder().isBlank() ? "" : b.folder() + " · ") + host(b.url()), null, () -> { sheet.dismiss(); engine.navigate(b.url()); });
                ui.addRow(group, SwipeDelete.wrap(ui, row, "이 북마크", () -> {
                    engine.store.settings.edit().putString(BOOKMARKS, dev.jun.gyeot.core.BrowserKit.deleteBookmark(engine.store.settings.getString(BOOKMARKS, ""), b.url())).apply(); return true;
                }), true);
                shown++;
            }
            if (shown > 0) sheet.body.addView(group);
            if (more > 0) sheet.body.addView(moreRow("북마크", more, () -> { page[0] += dev.jun.gyeot.core.BrowserKit.MAX_BOOKMARKS; fill[0].run(); }));
            if (shown == 0) sheet.body.addView(ui.footer(list.isEmpty() ? "아직 북마크가 없어요. 브라우저 메뉴에서 '북마크에 추가'를 누르거나, 다른 브라우저의 북마크를 가져와요." : "검색과 맞는 북마크가 없어요."));
            sheet.body.addView(ui.footer("크롬은 북마크 관리자 → ⋮ → 북마크 내보내기로 HTML 파일을 만들 수 있어요(엣지·사파리·파이어폭스도 같은 형식)."));
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable t) { fill[0].run(); }
        });
        sheet.primary.setText("다른 브라우저에서 가져오기"); sheet.primary.setOnClickListener(v -> {
            sheet.dismiss();
            android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("text/html")
                .putExtra(android.content.Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "application/xhtml+xml", "application/octet-stream", "text/plain"});
            try { startActivityForResult(pick, REQUEST_BOOKMARKS); } catch (RuntimeException e) { toast("파일을 고를 수 없어요."); }
        });
        fill[0].run(); show(sheet);
    }
    /** An exported bookmarks file read whole: 64 MB at most. */
    private static final int IMPORT_BYTES = 64 * 1024 * 1024;
    /** Reads another browser's exported bookmarks file (at most IMPORT_BYTES) and adds the pages not kept yet. */
    private void importBookmarks(android.net.Uri uri) {
        new Thread(() -> {
            String message;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("파일을 열지 못했어요.");
                byte[] bytes = in.readNBytes(IMPORT_BYTES + 1); if (bytes.length > IMPORT_BYTES) throw new java.io.IOException("파일이 너무 커요 (" + (IMPORT_BYTES >> 20) + "MB까지).");
                List<dev.jun.gyeot.core.BrowserKit.Bookmark> pages = dev.jun.gyeot.core.BrowserKit.parseExport(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), System.currentTimeMillis());
                if (pages.isEmpty()) throw new java.io.IOException("이 파일에서 북마크를 찾지 못했어요. 브라우저의 '북마크 내보내기'로 만든 HTML 파일인지 확인해 주세요.");
                dev.jun.gyeot.core.BrowserKit.Added added = dev.jun.gyeot.core.BrowserKit.add(engine.store.settings.getString(BOOKMARKS, ""), pages);
                engine.store.settings.edit().putString(BOOKMARKS, added.saved()).apply();
                // What the cap let go is said, never dropped silently: the oldest of those kept and of the file's pages.
                message = "북마크 " + added.added() + "개를 가져왔어요 (파일 " + pages.size() + "개 중 새 것만)" + (added.note().isEmpty() ? "" : " · " + added.note());
            } catch (Exception e) { message = e.getMessage() == null ? "북마크를 가져오지 못했어요." : e.getMessage(); }
            String shown = message; runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { toast(shown); bookmarks(); } });
        }, "gyeot-bookmark-import").start();
    }
    /**
     * 권한 모드, said as it is (there is no ask-first flow): 조작 허용 (the default: the AI also types, chooses, presses and
     * attaches on pages) or 읽기만 (BrowserEngine refuses typing, choosing, pressing and attaching). In both, payment, money
     * transfers, deletion and passwords or codes stay with the user (AutomationGate).
     */
    /** The default 권한 모드's name, in the AI menu and its sheet. */
    static final String PERMISSION_FULL = "조작 허용";
    private void permissionMode() {
        Ui.Sheet sheet = ui.selectionSheet("권한 모드", "다음 지시부터 적용돼요");
        boolean readOnly = engine.readOnly(); LinearLayout rows = ui.group();
        android.widget.ImageView on = new android.widget.ImageView(this); on.setImageResource(R.drawable.ic_check); on.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        android.widget.ImageView on2 = new android.widget.ImageView(this); on2.setImageResource(R.drawable.ic_check); on2.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        ui.addRow(rows, ui.listRow(R.drawable.ic_hand, Ui.BLUE, PERMISSION_FULL + " (기본)", "읽기·링크 이동에 더해 글 입력·고르기·버튼 누르기·파일 첨부까지 해요", readOnly ? null : on, () -> {
            engine.store.settings.edit().putBoolean(BrowserEngine.READ_ONLY, false).apply(); sheet.dismiss(); toast("권한 모드: " + PERMISSION_FULL); refresh(); }), true);
        ui.addRow(rows, ui.listRow(R.drawable.ic_eye, Ui.GREEN, "읽기만", "읽기·스크롤·링크 이동만 해요. 글 입력·고르기·버튼 누르기·파일 첨부는 하지 않아요", readOnly ? on2 : null, () -> {
            engine.store.settings.edit().putBoolean(BrowserEngine.READ_ONLY, true).apply(); sheet.dismiss(); toast("권한 모드: 읽기만"); refresh(); }), true);
        sheet.body.addView(rows);
        sheet.body.addView(ui.footer("어느 모드에서든 결제·송금·삭제와 비밀번호·인증번호 입력은 AI가 하지 않고 직접 하시도록 남겨 둬요."));
        show(sheet);
    }
    /** Room for two pages side by side (a foldable opened, a tablet): 600dp and wider. */
    private boolean splitRoom() { return getResources().getConfiguration().screenWidthDp >= 600; }
    /** 화면 분할: the side tab attached to its own view while there is room; closed when the screen gets narrow. */
    private void refreshSplit() {
        if (splitPane == null || splitView == null) return;
        BrowserEngine.Tab side = engine.split;
        if (side != null && !splitRoom()) { engine.split(null); side = null; }
        View seam = (View) splitPane.getTag();
        splitPane.setVisibility(side == null ? View.GONE : View.VISIBLE); if (seam != null) seam.setVisibility(splitPane.getVisibility());
        if (side == null) { if (splitView.getSession() != null) splitView.releaseSession(); return; }
        if (splitView.getSession() != side.session && side.session.isOpen() && web.getSession() != side.session) {
            if (splitView.getSession() != null) splitView.releaseSession();
            splitView.setSession(side.session);
        }
        String title = side.title == null || side.title.isBlank() ? host(side.url) : side.title;
        if (!title.contentEquals(splitTitle.getText())) { splitTitle.setText(title); splitTitle.setContentDescription("옆 화면 · " + title); }
    }
    /** The side page becomes the main one, and the main one moves to the side. */
    private void swapSplit() {
        BrowserEngine.Tab side = engine.split, main = engine.current; if (side == null || main == null) return;
        if (splitView.getSession() != null) splitView.releaseSession();
        engine.select(side); engine.split(main); refresh();
    }
    /** 화면 분할: pick the tab to show beside this one (or a new tab), or turn it off. */
    private void splitMenu() {
        if (!splitRoom()) { toast("화면을 펼치면(600dp 이상) 두 페이지를 나란히 볼 수 있어요."); return; }
        Ui.Sheet sheet = ui.selectionSheet("화면 분할", "옆에 함께 볼 탭을 골라요");
        LinearLayout rows = ui.group();
        for (BrowserEngine.Tab t : new ArrayList<>(engine.tabs)) {
            if (t == engine.current) continue;
            String title = t.title == null || t.title.isBlank() ? host(t.url) : t.title;
            ui.addRow(rows, menuRow(R.drawable.ic_layers, Ui.BLUE, title + (t == engine.split ? " · 지금 옆 화면" : ""), true, () -> engine.split(t), sheet), true);
        }
        ui.addRow(rows, menuRow(R.drawable.ic_add, ACCENT, "새 탭을 옆에 열기", true, () -> {
            BrowserEngine.Tab main = engine.current;
            try { BrowserEngine.Tab fresh = engine.newTab("https://www.google.com/", true); engine.select(main); engine.split(fresh); }
            catch (RuntimeException e) { toast(e.getMessage()); }
        }, sheet), true);
        if (engine.split != null) ui.addRow(rows, menuRow(R.drawable.ic_close, ERROR, "화면 분할 끄기", true, () -> engine.split(null), sheet), true);
        sheet.body.addView(rows); show(sheet);
    }
    /** Reading an archive never makes it active AI context. Deletion always names a captured conversation ID. */
    private void conversationHistory() {
        Ui.Sheet sheet = ui.selectionSheet("대화 기록", "닫은 탭과 새로 시작한 대화의 기록이에요. AI 문맥으로 보내지 않아요.");
        JSONArray archives = engine.archivedConversations();
        if (archives.length() == 0) sheet.body.addView(ui.label("보관된 대화가 없어요.", 15, MUTED));
        for (int i = 0; i < archives.length(); i++) {
            JSONObject value = archives.optJSONObject(i); if (value == null) continue;
            String id = value.optString("id"), title = value.optString("title", "이전 대화");
            int count = value.optInt("count");
            String preview = value.optString("preview");
            String description = (preview.isBlank() ? "" : preview + "\n") + count + "개 메시지 · 보기";
            String targetTitle = title + (preview.isBlank() ? "" : " · " + preview);
            View row = ui.listRow(R.drawable.ic_history, ACCENT, title, description, null, () -> { sheet.dismiss(); archivedConversation(id, targetTitle, 40); });
            ui.addRow(sheet.body, SwipeDelete.wrap(ui, row, "이 대화", () -> deleteConversationNow(id)), true);
        }
        show(sheet);
    }
    /** 대화 기록's X: deletes the conversation at once, as 이 대화 지우기 does after asking. */
    private boolean deleteConversationNow(String id) {
        boolean accepted = engine.deleteConversation(id, saved -> {
            if (isFinishing() || isDestroyed()) return;
            if (!saved) toast("저장하지 못했어요. 앱을 다시 열면 기록이 나타날 수 있어요.");
        });
        if (!accepted) { toast("작업을 멈추고 기록을 불러온 뒤 다시 시도해 주세요."); return false; }
        historyWindow = 40; renderedConversationVersion = -1; refresh();
        return true;
    }
    private void archivedConversation(String id, String title, int limit) {
        Ui.Sheet sheet = ui.selectionSheet(title, "보관된 표시 기록 · 현재 탭의 AI 작업 기억에는 포함되지 않아요");
        JSONArray rows = engine.archivedWindow(id, limit);
        if (rows.length() >= limit) sheet.body.addView(ui.button("이전 메시지 더 보기", false, () -> { sheet.dismiss(); archivedConversation(id, title, limit > Integer.MAX_VALUE - 40 ? Integer.MAX_VALUE : limit + 40); }));
        if (rows.length() == 0) sheet.body.addView(ui.label("표시할 기록이 없어요.", 14, MUTED));
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            String role = row.optString("role"), text = row.optString("text");
            String label = row.optString("label");
            if (!label.isBlank()) { TextView sourceLabel = ui.label(label, 12, MUTED); sourceLabel.setPadding(0, ui.dp(12), 0, ui.dp(4)); sheet.body.addView(sourceLabel); }
            TextView message = ui.label("", 14, "boundary".equals(role) ? MUTED : TEXT);
            message.setText("user".equals(role) ? "나 · " + text : RichText.render(text)); message.setTextIsSelectable(true);
            message.setPadding(0, ui.dp(8), 0, ui.dp(12)); sheet.body.addView(message);
        }
        Button delete = ui.button("이 대화 지우기", false, () -> confirmDeleteConversation(id, title, sheet));
        delete.setTextColor(ERROR); delete.setEnabled(engine.canManageConversations()); sheet.body.addView(delete); show(sheet);
    }
    private void confirmDeleteConversation(String id, String title, android.app.Dialog source) {
        if (!engine.canManageConversations()) { toast("작업을 멈추고 기록을 불러온 뒤 다시 시도해 주세요."); return; }
        boolean active = engine.isActiveConversation(id);
        String scope = active ? "이 대화와 해당 탭의 AI 작업 기억을 지울까요?" : "보관된 표시 기록만 지울까요? 현재 탭의 AI 작업 기억은 그대로예요.";
        show(ui.confirm("‘" + title + "’\n" + scope + "\n다른 탭의 대화는 그대로예요. 이 작업은 되돌릴 수 없어요. 최근·고정 요청은 별도로 남아요.", "이 대화 지우기", () -> {
            boolean accepted = engine.deleteConversation(id, saved -> {
                if (isFinishing() || isDestroyed()) return;
                toast(saved ? "대화를 지웠어요" : "저장하지 못했어요. 앱을 다시 열면 기록이 나타날 수 있어요.");
            });
            if (!accepted) { toast("작업을 멈추고 기록을 불러온 뒤 다시 시도해 주세요."); return; }
            if (source != null) source.dismiss();
            toast("대화 변경을 저장하는 중이에요"); historyWindow = 40; renderedConversationVersion = -1; refresh();
        }));
    }
    /** Session-owned delegates survive Activity recreation without retaining this Activity. */
    private void installInputGuards() {
        BrowserEngine inputEngine = engine;
        for (BrowserEngine.Tab tab : inputEngine.tabs) {
            BrowserImeGuard.install(tab.session,
                () -> inputEngine.visible && inputEngine.current == tab && inputEngine.liveSession(tab.session)
                    && tab.session.getTextInput().getView() != null,
                () -> tab.automationInput,
                () -> {
                    // Clear only genuine user input's session; late AI IME callbacks remain suppressed elsewhere.
                    if (inputEngine.current == tab && inputEngine.liveSession(tab.session)) {
                        tab.automationInput = false;
                        inputEngine.manual();
                    }
                });
        }
    }
    /** Engine projection and lease belong only to the selected tab; other lanes cannot repaint this snapshot. */
    private BrowserProgressUi.Snapshot progressSnapshot() {
        if (engine == null) return progressClock.observe(null, 0, false, "", List.of(), android.os.SystemClock.elapsedRealtime());
        progressClock.retainTabs(engine.tabs);
        return progressClock.observe(engine.current, engine.lease(), engine.running(), engine.status,
            engine.activity, android.os.SystemClock.elapsedRealtime());
    }
    private BrowserProgressUi.StopTarget stopTarget(BrowserProgressUi.Snapshot snapshot) {
        return BrowserProgressUi.stopTarget(snapshot, () -> engine.current, engine::valid,
            () -> snapshot != null && engine.running(snapshot.lease()),
            () -> snapshot != null && engine.taskTab(snapshot.lease()) == engine.current,
            lease -> engine.stopTask(lease, "AI 작업을 중지했어요"));
    }
    private void detail() {
        if (progressDetails != null && progressDetails.isShowing()) return;
        BrowserProgressUi.Snapshot captured = progressSnapshot();
        BrowserEngine.Tab confirmationTab = engine.current;
        BrowserProgressUi.StopTarget stopTarget = stopTarget(captured);
        Ui.Sheet sheet = ui.selectionSheet("브라우저 작업", engine.brainName());
        progressDetails = sheet;
        // Fixed controls remain reachable above a long request/action log, including large text.
        Button stop = ui.button("작업 중지", false, () -> { stopTarget.stop(); sheet.dismiss(); });
        stop.setContentDescription("현재 탭 AI 작업 중지");
        sheet.controls.addView(stop);
        sheet.body.addView(ui.footer("실제 진행 단계 · 요청한 동작 기록"));
        TextView ask = ui.label("", 14, MUTED); sheet.body.addView(ask);
        TextView stage = ui.label("", 16, TEXT); stage.setTextIsSelectable(true);
        stage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); sheet.body.addView(stage);
        TextView elapsed = ui.label("", 13, MUTED), waiting = ui.label("", 13, ACCENT);
        elapsed.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        waiting.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        sheet.body.addView(elapsed); sheet.body.addView(waiting);
        // The action log keeps up to BrowserEngine.ACTIVITY lines; only the newest DETAIL_LINES are drawn until asked for more.
        int[] drawn = {DETAIL_LINES}; Runnable[] again = new Runnable[1];
        TextView earlier = ui.text("", 13, 650, ACCENT); earlier.setGravity(Gravity.CENTER); earlier.setPadding(0, ui.dp(6), 0, ui.dp(6));
        earlier.setOnClickListener(v -> { drawn[0] += DETAIL_LINES; if (again[0] != null) again[0].run(); }); Ui.pressable(earlier, true); sheet.body.addView(earlier);
        TextView actions = ui.label("", 13, MUTED); actions.setTextIsSelectable(true); sheet.body.addView(actions);
        ViewGroup resultCommands = ui.actions(ui.chip("결과 복사", () -> copyText("브라우저 작업 결과", dev.jun.gyeot.core.Markdown.plain(engine.status))), ui.chip("결과 공유", () -> shareText(engine.status, "브라우저 작업 결과"))); sheet.body.addView(resultCommands);
        Button direct = ui.button("페이지에서 직접 확인", false, sheet::dismiss); sheet.body.addView(direct);
        Button resume = ui.button("직접 확인 완료 · AI 다시 사용", false, () -> {
            sheet.dismiss();
            if (engine.current == confirmationTab && engine.paused && engine.valid(captured.lease()) && engine.taskOnCurrentTab()) loginMenu();
        }); sheet.body.addView(resume);
        sheet.body.addView(ui.button("AI 대화 열기", false, () -> { sheet.dismiss(); setPaneVisible(true); }));
        Runnable render = () -> {
            if (engine.current != captured.tab() || !engine.valid(captured.lease())) {
                if (progressDetails == sheet) { progressDetails = null; detailRefresh = null; }
                sheet.dismiss(); return;
            }
            BrowserProgressUi.Snapshot snapshot = progressSnapshot();
            boolean other = engine.running() && !engine.taskOnCurrentTab();
            long now = android.os.SystemClock.elapsedRealtime();
            String asked = other ? "" : engine.lastAsk.isBlank() ? engine.lastRequest : engine.lastAsk;
            setProgressText(ask, asked.length() > 500 ? asked.substring(0, 499) + "…" : asked); // a long request: what fits the sheet
            setProgressText(stage, snapshot.running() || other ? snapshot.stage() : engine.status);
            setProgressText(elapsed, BrowserProgressUi.elapsed(snapshot, now));
            setProgressText(waiting, BrowserProgressUi.waiting(snapshot, now));
            List<String> lines = snapshot.actions(); int hidden = Math.max(0, lines.size() - drawn[0]);
            setProgressText(earlier, hidden > 0 ? "이전 기록 " + hidden + "개 더 보기" : "");
            setProgressText(actions, String.join("\n", lines.subList(hidden, lines.size())));
            resultCommands.setVisibility(!engine.running() && !engine.status.isBlank() ? View.VISIBLE : View.GONE);
            stop.setVisibility(stopTarget.canStop() ? View.VISIBLE : View.GONE);
            direct.setVisibility(!other && (engine.paused || "paused".equals(engine.resultState)) ? View.VISIBLE : View.GONE);
            resume.setVisibility(!other && engine.paused ? View.VISIBLE : View.GONE);
        };
        detailRefresh = render; again[0] = render;
        sheet.setOnDismissListener(d -> { dialogs.remove(sheet); if (progressDetails == sheet) { progressDetails = null; detailRefresh = null; } });
        render.run(); showBrowserPopup(sheet, paneToggle, false);
    }
    private static void setProgressText(TextView view, String text) {
        String value = text == null ? "" : text;
        if (!android.text.TextUtils.equals(view.getText(), value)) view.setText(value);
        int visibility = value.isBlank() ? View.GONE : View.VISIBLE;
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }
    private void tabs() {
        if (tabOverview != null && tabOverview.isShowing()) return;
        hideKeyboard();
        android.app.Dialog overview = new android.app.Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        tabOverview = overview;
        LinearLayout root = ui.column(); root.setBackground(ui.canvas());
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        LinearLayout header = ui.row(); header.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        header.addView(icon(R.drawable.ic_back, "탭 화면 닫기", overview::dismiss), square());
        TextView heading = ui.text("탭", 18, 750, TEXT);
        heading.setGravity(Gravity.CENTER);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button add = ui.chip("+ 새 탭", () -> {
            try { BrowserEngine.Tab created = engine.newTab("https://www.google.com/", true); overview.dismiss();
                address.post(() -> {
                    if (engine.current != created || !engine.tabs.contains(created) || isFinishing() || isDestroyed()) return;
                    address.requestFocus(); address.selectAll(); getSystemService(InputMethodManager.class).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
                }); }
            catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show(); }
        });
        add.setContentDescription("새 탭 열기"); header.addView(add, new LinearLayout.LayoutParams(-2, ui.dp(48)));
        Button secret = ui.smallChip("🕶 시크릿", () -> { overview.dismiss(); newSecretTab(); });
        secret.setContentDescription("새 시크릿 탭 열기");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, ui.dp(48)); sp.setMarginStart(ui.dp(6));
        header.addView(secret, sp); root.addView(header);
        TextView subtitle = ui.text("", 13, 450, MUTED); subtitle.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(12)); root.addView(subtitle);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout cards = ui.column(); cards.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16)); scroll.addView(cards);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = ui.row(); footer.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(12));
        TextView privacy = ui.text("현재 탭으로 돌아가기", 12, 450, MUTED);
        footer.addView(privacy, new LinearLayout.LayoutParams(0, -2, 1));
        Button done = ui.chip("완료", overview::dismiss); done.setContentDescription("현재 탭으로 돌아가기");
        footer.addView(done, new LinearLayout.LayoutParams(-2, ui.dp(48))); root.addView(footer);
        String[] rendered = { "" };
        Runnable render = () -> {
            StringBuilder signature = new StringBuilder();
            for (BrowserEngine.Tab tab : engine.tabs) signature.append(System.identityHashCode(tab)).append(':').append(tab.title).append(':').append(tab.url).append(':').append(tab == engine.current).append(':').append(engine.aiWorking(tab)).append(';');
            if (rendered[0].equals(signature.toString())) return;
            rendered[0] = signature.toString(); cards.removeAllViews();
            int count = engine.tabs.size(); heading.setText("탭 " + count);
            subtitle.setText("카드로 전환 · ×로 닫기");
            android.content.res.Configuration config = getResources().getConfiguration();
            int cardWidth = Math.round(168 * Math.max(1f, config.fontScale));
            int columns = Math.max(1, Math.min(3, (config.screenWidthDp - 24) / cardWidth));
            LinearLayout row = null; int index = 0;
            for (BrowserEngine.Tab tab : new ArrayList<>(engine.tabs)) {
                if (index % columns == 0) { row = ui.row(); row.setGravity(Gravity.TOP); cards.addView(row, new LinearLayout.LayoutParams(-1, -2)); }
                boolean selected = tab == engine.current;
                String name = tabName(tab);
                LinearLayout card = ui.column(); card.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
                android.graphics.drawable.GradientDrawable surface = ui.shape(selected ? ACCENT_SOFT : CARD, 20); surface.setStroke(ui.dp(selected ? 2 : 1), selected ? ACCENT : FILL); card.setBackground(surface);
                card.setMinimumHeight(ui.dp(228)); card.setSelected(selected); card.setContentDescription(name + ", " + host(tab.url) + (selected ? ", 현재 탭" : "") + ", 탭 열기");
                card.setOnClickListener(v -> { if (engine.tabs.contains(tab)) engine.select(tab); overview.dismiss(); }); Ui.pressable(card, false);
                LinearLayout top = ui.row(); top.setPadding(ui.dp(9), 0, 0, 0);
                TextView title = ui.text(name, 13, 650, TEXT); title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                top.addView(title, new LinearLayout.LayoutParams(0, ui.dp(48), 1)); title.setGravity(Gravity.CENTER_VERTICAL);
                top.addView(icon(R.drawable.ic_close, "‘" + name + "’ 탭 닫기", () -> {
                    if (!engine.tabs.contains(tab)) return;
                    engine.close(tab);
                    subtitle.announceForAccessibility(name + " 탭을 닫았어요. 열린 탭 " + engine.tabs.size() + "개");
                    if (tabOverviewRefresh != null) tabOverviewRefresh.run();
                }), square()); card.addView(top);
                // Chrome-like card hierarchy without saving screenshots of private page contents.
                LinearLayout preview = ui.column(); preview.setGravity(Gravity.CENTER); preview.setPadding(ui.dp(12), ui.dp(18), ui.dp(12), ui.dp(16)); preview.setBackground(ui.shape(FILL, 16));
                TextView monogram = ui.text(tab.secret ? "🕶" : host(tab.url).isBlank() ? "W" : host(tab.url).substring(0, 1).toUpperCase(Locale.ROOT), 28, 650, selected ? ACCENT : MUTED);
                monogram.setGravity(Gravity.CENTER); monogram.setBackground(ui.shape(selected ? ACCENT_SOFT : CARD, 99)); monogram.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                preview.addView(monogram, new LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)));
                TextView domain = ui.text(host(tab.url), 12, 450, MUTED); domain.setMaxLines(1); domain.setEllipsize(android.text.TextUtils.TruncateAt.END); domain.setGravity(Gravity.CENTER); domain.setPadding(0, ui.dp(12), 0, 0); preview.addView(domain, new LinearLayout.LayoutParams(-1, -2));
                boolean ai = engine.aiWorking(tab);
                TextView marker = ui.text(ai ? "AI 작업 중 · 열어서 보기·멈추기" : selected ? (tab.secret ? "✓ 현재 탭 · 시크릿" : "✓ 현재 탭") : tab.secret ? "시크릿 · 닫으면 기록 없음" : "탭 열기", 12, 650, selected || ai ? ACCENT : MUTED);
                if (ai) card.setContentDescription(name + ", AI 작업 중" + (selected ? ", 현재 탭" : "") + ", 탭 열기"); marker.setGravity(Gravity.CENTER); marker.setPadding(0, ui.dp(12), 0, 0); preview.addView(marker);
                preview.setMinimumHeight(ui.dp(176)); card.addView(preview, new LinearLayout.LayoutParams(-1, -2));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); cp.setMargins(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); row.addView(card, cp); index++;
            }
            if (row != null) while (index++ % columns != 0) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        };
        tabOverviewRefresh = render;
        overview.setOnDismissListener(d -> { if (tabOverviewRefresh == render) tabOverviewRefresh = null; if (tabOverview == overview) tabOverview = null; dialogs.remove(overview); });
        overview.setContentView(root); render.run(); show(overview);
        Window window = overview.getWindow();
        if (window != null) {
            window.setLayout(-1, -1); window.setStatusBarColor(CARD); window.setNavigationBarColor(CARD);
            window.getDecorView().setSystemUiVisibility(DARK ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        root.requestApplyInsets();
    }
    private void loginMenu() {
        if (engine.paused) { engine.loginMode(false); return; }
        BrowserEngine.PromptToken token = engine.promptToken(engine.current.session);
        Ui.Sheet sheet = ui.new Sheet("로그인", "이 브라우저에서 로그인하면 세션이 유지돼요");
        sheet.body.addView(ui.button("직접 로그인 · AI 잠시 멈추기", true, () -> {
            sheet.dismiss(); if (engine.validPrompt(token)) engine.loginMode(true); else toast("대상 탭이 바뀌었어요. 로그인 메뉴를 다시 열어 주세요.");
        }));
        sheet.body.addView(ui.button("이 사이트의 저장된 로그인", false, () -> {
            sheet.dismiss(); if (engine.validPrompt(token)) vault(); else toast("대상 탭이 바뀌었어요. 로그인 메뉴를 다시 열어 주세요.");
        }));
        sheet.body.addView(ui.button("시스템 브라우저에서 열기", false, () -> {
            sheet.dismiss();
            if (!engine.validPrompt(token)) { toast("대상 탭이 바뀌었어요. 로그인 메뉴를 다시 열어 주세요."); return; }
            String url = token.url; engine.loginMode(true);
            try { new CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, android.net.Uri.parse(BrowserTools.url(url))); }
            catch (Exception e) { toast("시스템 브라우저를 열지 못했어요."); }
        }));
        sheet.body.addView(ui.label("시스템 브라우저의 로그인은 들퍼레에 자동으로 옮겨지지 않아요. 사이트가 내부 로그인을 지원하지 않을 때 이용해 주세요.", 13, MUTED)); show(sheet);
    }
    /** 비밀번호 (PasswordsUi): every saved login, kept by LoginVault. */
    private PasswordsUi passwords() { return new PasswordsUi(this, ui, LoginVault.of(this), this::toast); }
    /**
     * This site's saved logins: one tapped is typed into the page's login form (the AI paused, the page's own fields, never the
     * AI's tools), or a new one is kept and typed. The login button itself is the user's to press.
     */
    private void vault() {
        engine.loginMode(true);
        BrowserEngine.PromptToken token = engine.promptToken(engine.current.session);
        try {
            if (!engine.validPrompt(token)) throw new IllegalStateException("현재 탭을 다시 확인해 주세요.");
            String origin = BrowserTools.origin(engine.current.url);
            if (!origin.startsWith("https://")) throw new IllegalStateException("HTTPS 사이트에서만 로그인 정보를 저장할 수 있어요.");
            LoginVault logins = LoginVault.of(this);
            Ui.Sheet sheet = ui.new Sheet("저장된 로그인", origin);
            java.util.function.BiConsumer<String, String> fill = (user, secret) -> engine.fillVault(token, origin, user, secret, result -> {
                if (engine.current == token.tab && engine.liveSession(token.session))
                    toast(result.optBoolean("ok") ? "입력했어요. 로그인 버튼을 직접 눌러 주세요." : result.optString("error"));
            });
            List<LoginVault.Login> saved = logins.forSite(origin);
            if (!saved.isEmpty()) {
                LinearLayout accounts = ui.group();
                for (LoginVault.Login login : saved) ui.addRow(accounts, ui.listRow(R.drawable.ic_key, Ui.ORANGE, login.username().isBlank() ? "아이디 없는 로그인" : login.username(),
                    "눌러서 입력", ui.chevron(), () -> {
                        if (!engine.validPrompt(token) || !origin.equals(BrowserTools.origin(token.tab.url))) { toast("승인한 탭이 바뀌었어요. 로그인 메뉴를 다시 열어 주세요."); return; }
                        sheet.dismiss(); logins.used(login.guid(), login.origin(), login.username()); fill.accept(login.username(), login.password());
                    }), true);
                sheet.body.addView(accounts); sheet.body.addView(ui.sectionHeader("다른 계정 저장"));
            }
            EditText username = ui.input("아이디 또는 이메일", 1, 512), password = ui.input("비밀번호", 1, 1024);
            username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            sheet.body.addView(username); sheet.body.addView(password);
            sheet.body.addView(ui.label("기기에 암호화해 저장해요. AI에게 전달하지 않으며, 입력 후 로그인 버튼은 직접 눌러 주세요.", 13, MUTED));
            sheet.body.addView(ui.button("모든 비밀번호 보기", false, () -> { sheet.dismiss(); passwords().show(); }));
            sheet.primary.setText("저장하고 입력"); sheet.primary.setOnClickListener(v -> {
                String user = username.getText().toString(), secret = password.getText().toString();
                if (secret.isBlank()) { toast("비밀번호를 입력해 주세요."); return; }
                try {
                    if (!engine.validPrompt(token) || !origin.equals(BrowserTools.origin(token.tab.url))) throw new IllegalStateException("승인한 탭이 바뀌었어요. 로그인 메뉴를 다시 열어 주세요.");
                    LoginVault.Login kept = logins.save(origin, "", "", user, secret);
                    sheet.dismiss(); logins.used(kept.guid(), origin, user); fill.accept(user, secret);
                } catch (Exception e) { toast(e.getMessage()); }
            });
            sheet.setOnDismissListener(d -> { username.setText(""); password.setText(""); dialogs.remove(sheet); });
            show(sheet); sheet.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } catch (Exception e) { toast(e.getMessage()); }
    }
    private void showBrowserPopup(Ui.Sheet sheet, View anchor, boolean applyAction) { dialogs.add(sheet); BrowserPopup.show(ui, sheet, anchor, applyAction); }
    private void show(android.app.Dialog dialog) { dialogs.add(dialog); dialog.show(); }
    /** A short notice in a floating capsule over the page instead of a system toast; it fades away by itself. */
    private final Runnable hideNotice = () -> { if (notice != null) notice.animate().alpha(0f).translationY(-ui.dp(8)).setDuration(220).withEndAction(() -> notice.setVisibility(View.GONE)).start(); };
    private void toast(String text) {
        if (notice == null || text == null || text.isBlank()) return;
        notice.removeCallbacks(hideNotice); notice.animate().cancel(); notice.setText(text); notice.setVisibility(View.VISIBLE);
        notice.setAlpha(0f); notice.setTranslationY(-ui.dp(8)); notice.animate().alpha(1f).translationY(0).setDuration(240).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        notice.postDelayed(hideNotice, Math.min(6000, 2400 + text.length() * 40L));
    }
    private <P extends GeckoSession.PromptDelegate.BasePrompt> GeckoResult<GeckoSession.PromptDelegate.PromptResponse> prompt(GeckoSession session, P prompt, java.util.function.BiConsumer<AlertDialog.Builder, GeckoResult<GeckoSession.PromptDelegate.PromptResponse>> build) {
        if (engine.current == null || engine.current.session != session || !engine.visible || !engine.liveSession(session))
            return GeckoResult.fromValue(prompt.dismiss());
        engine.manual(); // Synchronous, validated source session only; the engine captures its post-handoff token.
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result = new GeckoResult<>();
        String origin = BrowserTools.origin(engine.current.url);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(origin + (prompt.title == null ? "" : " · " + prompt.title));
        build.accept(builder, result); AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> { dialogs.remove(dialog); if (!prompt.isComplete()) result.complete(prompt.dismiss()); }); show(dialog); return result;
    }
    private final GeckoSession.PromptDelegate prompts = new GeckoSession.PromptDelegate() {
        /**
         * A page's "파일 올리기" (준: "AI 브라우저에서 이 동작이 안돼": claude.ai's 파일 또는 사진 추가 did nothing, the chooser had no
         * handler): the phone's own picker, one file or several as the page asks, of the kinds it accepts; what is picked goes to
         * the page (GeckoView copies it in). Cancelled, the page hears no file.
         */
        @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession session, FilePrompt p) {
            if (!engine.liveSession(session)) return GeckoResult.fromValue(p.dismiss());
            if (fileAnswer != null && filePrompt != null) { fileAnswer.complete(filePrompt.dismiss()); fileAnswer = null; filePrompt = null; }
            android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE);
            java.util.List<String> kinds = new java.util.ArrayList<>();
            if (p.mimeTypes != null) for (String m : p.mimeTypes) if (m != null && m.contains("/")) kinds.add(m.strip().toLowerCase(java.util.Locale.ROOT));
            pick.setType(kinds.size() == 1 ? kinds.get(0) : "*/*");
            if (kinds.size() > 1) pick.putExtra(android.content.Intent.EXTRA_MIME_TYPES, kinds.toArray(new String[0]));
            if (p.type == FilePrompt.Type.MULTIPLE) pick.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
            GeckoResult<PromptResponse> answer = new GeckoResult<>();
            try { startActivityForResult(pick, REQUEST_FILES); }
            catch (RuntimeException none) { toast("파일을 고를 앱이 없어요."); return GeckoResult.fromValue(p.dismiss()); }
            filePrompt = p; fileAnswer = answer;
            return answer;
        }
        @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession session, AlertPrompt p) { if (!engine.liveSession(session)) return GeckoResult.fromValue(p.dismiss()); return prompt(session, p, (b, r) -> b.setMessage(p.message).setPositiveButton("확인", (d, w) -> r.complete(p.dismiss()))); }
        @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession session, ButtonPrompt p) { if (!engine.liveSession(session)) return GeckoResult.fromValue(p.dismiss()); return prompt(session, p, (b, r) -> b.setMessage(p.message).setPositiveButton("확인", (d, w) -> r.complete(p.confirm(ButtonPrompt.Type.POSITIVE))).setNegativeButton("취소", (d, w) -> r.complete(p.confirm(ButtonPrompt.Type.NEGATIVE)))); }
        @Override public GeckoResult<PromptResponse> onTextPrompt(GeckoSession session, TextPrompt p) { if (!engine.liveSession(session)) return GeckoResult.fromValue(p.dismiss());
            EditText text = ui.input(p.message, 1, 8000); text.setText(p.defaultValue);
            return prompt(session, p, (b, r) -> b.setView(text).setPositiveButton("확인", (d, w) -> r.complete(p.confirm(text.getText().toString()))).setNegativeButton("취소", (d, w) -> r.complete(p.dismiss())));
        }
        @Override public GeckoResult<PromptResponse> onAuthPrompt(GeckoSession session, AuthPrompt p) { if (!engine.liveSession(session)) return GeckoResult.fromValue(p.dismiss());
            LinearLayout box = ui.column(); EditText user = ui.input("아이디", 1, 512), pass = ui.input("비밀번호", 1, 1024);
            pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); box.addView(user); box.addView(pass);
            return prompt(session, p, (b, r) -> b.setMessage(BrowserEngine.safeUrl(p.authOptions.uri)).setView(box).setPositiveButton("로그인", (d, w) -> r.complete(p.confirm(user.getText().toString(), pass.getText().toString()))).setNegativeButton("취소", (d, w) -> r.complete(p.dismiss())));
        }
        @Override public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession session, PopupPrompt p) { if (!engine.liveSession(session)) return GeckoResult.fromValue(p.dismiss()); return prompt(session, p, (b, r) -> b.setMessage("새 탭을 열까요?\n" + BrowserEngine.safeUrl(p.targetUri)).setPositiveButton("열기", (d, w) -> r.complete(p.confirm(AllowOrDeny.ALLOW))).setNegativeButton("취소", (d, w) -> r.complete(p.confirm(AllowOrDeny.DENY)))); }
        /** The page asks before it is left (a form not saved yet): the user decides, as in any browser. */
        @Override public GeckoResult<PromptResponse> onBeforeUnloadPrompt(GeckoSession session, BeforeUnloadPrompt p) { if (!engine.liveSession(session)) return GeckoResult.fromValue(p.dismiss());
            return prompt(session, p, (b, r) -> b.setMessage("이 페이지를 떠날까요?\n입력한 내용이 저장되지 않았을 수 있어요.").setPositiveButton("떠나기", (d, w) -> r.complete(p.confirm(AllowOrDeny.ALLOW))).setNegativeButton("머무르기", (d, w) -> r.complete(p.confirm(AllowOrDeny.DENY))));
        }
        /** A page sent by a form is loaded again: sending it again could order or pay twice. */
        @Override public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession session, RepostConfirmPrompt p) { if (!engine.liveSession(session)) return GeckoResult.fromValue(p.dismiss());
            return prompt(session, p, (b, r) -> b.setMessage("양식을 다시 보낼까요?\n주문·결제·글 올리기가 한 번 더 될 수 있어요.").setPositiveButton("다시 보내기", (d, w) -> r.complete(p.confirm(AllowOrDeny.ALLOW))).setNegativeButton("취소", (d, w) -> r.complete(p.confirm(AllowOrDeny.DENY))));
        }
        /** After a login with details not kept yet: keep them (encrypted on this device, never sent to an AI)? */
        @Override public GeckoResult<PromptResponse> onLoginSave(GeckoSession session, AutocompleteRequest<Autocomplete.LoginSaveOption> p) {
            if (!engine.liveSession(session) || p.options == null || p.options.length == 0) return GeckoResult.fromValue(p.dismiss());
            LoginVault vault = LoginVault.of(BrowserActivity.this); Autocomplete.LoginEntry entry = p.options[0].value;
            String origin = entry.origin == null ? "" : entry.origin, user = entry.username == null ? "" : entry.username;
            if (!vault.asks() || vault.never(origin)) return GeckoResult.fromValue(p.dismiss());
            boolean update = vault.forSite(origin).stream().anyMatch(l -> l.username().equals(user));
            return prompt(session, p, (b, r) -> b.setMessage((update ? "이 계정의 바뀐 비밀번호를 저장할까요?" : "이 로그인 정보를 저장할까요?") + (user.isBlank() ? "" : "\n아이디 · " + user)
                    + "\n\n기기에 암호화해 두고 다음부터 자동으로 입력해요. AI에게는 보내지 않아요.")
                .setPositiveButton(update ? "바꿔 저장" : "저장", (d, w) -> r.complete(p.confirm(p.options[0])))
                .setNeutralButton("이 사이트는 묻지 않기", (d, w) -> { vault.never(origin, true); r.complete(p.dismiss()); })
                .setNegativeButton("나중에", (d, w) -> r.complete(p.dismiss())));
        }
        /** More than one account kept for this site: which one to fill. */
        @Override public GeckoResult<PromptResponse> onLoginSelect(GeckoSession session, AutocompleteRequest<Autocomplete.LoginSelectOption> p) {
            if (!engine.liveSession(session) || p.options == null || p.options.length == 0) return GeckoResult.fromValue(p.dismiss());
            String[] labels = new String[p.options.length];
            for (int i = 0; i < labels.length; i++) { String u = p.options[i].value == null ? "" : p.options[i].value.username; labels[i] = u == null || u.isBlank() ? "아이디 없는 로그인" : u; }
            return prompt(session, p, (b, r) -> b.setMessage(null).setItems(labels, (d, n) -> r.complete(p.confirm(p.options[n]))).setNegativeButton("취소", (d, w) -> r.complete(p.dismiss())));
        }
        @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession session, ChoicePrompt p) { if (!engine.liveSession(session)) return GeckoResult.fromValue(p.dismiss());
            List<ChoicePrompt.Choice> options = new ArrayList<>(); flatten(p.choices, options); String[] labels = options.stream().map(c -> c.label).toArray(String[]::new);
            return prompt(session, p, (b, r) -> {
                if (p.type == ChoicePrompt.Type.MULTIPLE) {
                    boolean[] checked = new boolean[options.size()]; for (int i = 0; i < checked.length; i++) checked[i] = options.get(i).selected;
                    b.setMultiChoiceItems(labels, checked, (d, n, on) -> { if (!options.get(n).disabled) checked[n] = on; });
                    b.setPositiveButton("완료", (d, w) -> { List<ChoicePrompt.Choice> chosen = new ArrayList<>(); for (int i = 0; i < checked.length; i++) if (checked[i]) chosen.add(options.get(i)); r.complete(p.confirm(chosen.toArray(new ChoicePrompt.Choice[0]))); });
                } else b.setItems(labels, (d, n) -> r.complete(options.get(n).disabled ? p.dismiss() : p.confirm(options.get(n))));
                b.setNegativeButton("취소", (d, w) -> r.complete(p.dismiss()));
            });
        }
    };
    private static void flatten(GeckoSession.PromptDelegate.ChoicePrompt.Choice[] input, List<GeckoSession.PromptDelegate.ChoicePrompt.Choice> output) {
        for (var choice : input) { if (choice.items != null) flatten(choice.items, output); else if (!choice.separator) output.add(choice); }
    }
}



