package dev.jun.gyeot;

import android.app.*;
import android.content.*;
import android.graphics.Insets;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import dev.jun.gyeot.core.*;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static dev.jun.gyeot.Ui.*;

/** Main messenger content hosted inside the persistent MainActivity window. No model/job starts on show. */
final class MainChatPage implements AutoCloseable {
    private final MainActivity activity;
    private final ArrayList<Dialog> dialogs = new ArrayList<>();
    private AssistantProfileUi.Editor profileEditor;
    private int restoreScrollY = -1, visibilityGeneration;
    private AppStore store;
    private Ui ui;
    private AssistantProfile profile;
    private LinearLayout root, messages, composer;
    /** The files picked for the next message (AppStore.mainFiles) as chips above the composer, each with ✕; hidden while there are none. */
    private LinearLayout filesRow; private HorizontalScrollView filesScroll;
    private ScrollView scroll;
    private ImageView toNewest; static final int AWAY = 240;
    private EditText input;
    private ImageView avatar;
    private TextView name, subtitle;
    private long before;
    private String query = "", rendered = "";
    private boolean stopped = true;
    private Runnable modelPickerRefresh;
    private Dialog modelPicker;
    private ComposerDictation dictation;
    /** 음성 대화 (VoiceBar): the send button is its button while the draft is empty. */
    private VoiceBar voice; private ImageView sendButton;
    /** The composer mic: a tap dictates into the draft, a hold is 누르고 말하기 (ConnectedVoice). */
    private ImageView mic;
    static final String MIC = "음성 입력 · 초안에만 넣기";
    /** 누르고 말하기 waits for the microphone permission: for a hold (it cannot resume, so it says to hold again) or a tap. */
    private static final int HOLD = 1, TAP = 2;
    private int talkAfterPermission;
    /** The calendar card waiting for the calendar permission: its answer and index, added once the user allows it. */
    private long eventAfterPermission = -1; private int eventIndexAfterPermission;
    private boolean talkOnResume, holding, swallow, touching;
    /** 누르고 말하기's state above the composer (the field's hint is hidden behind a draft). */
    private TextView talkStatus;
    /** The 검색 chip over the composer (자동, 항상 or 끔, core/MainSearch) and its label. */
    private LinearLayout searchChip;
    private TextView searchLabel;
    private PushToTalk.State talkSeen = PushToTalk.State.IDLE;
    /** Finished answers this page has already seen, so 답변 자동 읽기 reads each new one once; null until the first latest view. */
    private Set<Long> heard;
    private final Runnable observer = this::refresh;
    /** Drawn answers by message, text and mode, reused across refreshes so visuals are not reloaded; freed when they leave. */
    private HashMap<String, View> answers = new HashMap<>();
    /** Replies still being written, by message, kept across refreshes so only their last block is drawn again. */
    private HashMap<Long, Streaming> streaming = new HashMap<>();
    /** Hears which reply has more to show (MainChatEngine.watch), on the main thread. */
    private final java.util.function.LongConsumer streamer = this::streamed;
    /** Answers whose 생각 과정 the user opened; folded by default. */
    private final Set<Long> unfolded = new HashSet<>();
    /** How many characters of a cut-off answer 이어서 답하기 quotes to name it (carryOn). */
    static final int CARRY_ON_WORDS = 24;
    /** Inline visuals run for the newest answers only; older ones open on tap. */
    static final int LIVE_VISUALS = 6;
    /** Decodes drawn pictures' private copies for the chat, one at a time, off the main thread. */
    private final java.util.concurrent.ExecutorService pictureLoader = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "main-chat-pictures"); t.setDaemon(true); return t; });

    MainChatPage(MainActivity activity, Bundle saved) {
        this.activity = activity;
        store = ((GyeotApp)activity.getApplication()).store();
        ui = new Ui(activity); profile = new AssistantProfile(activity);
        // The messages and the composer keep to the reading column, fitted in the same pass that measures them.
        root = new LinearLayout(activity) {
            @Override protected void onMeasure(int width, int height) { fit(View.MeasureSpec.getSize(width) - getPaddingLeft() - getPaddingRight()); super.onMeasure(width, height); }
        };
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); root.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        // A compact header like the Claude and ChatGPT apps: avatar, name, and the model as a tappable line with a chevron.
        LinearLayout header = ui.row(); header.setMinimumHeight(ui.dp(56)); header.setPadding(ui.dp(2), ui.dp(4), 0, ui.dp(4));
        avatar = new ImageView(activity); avatar.setContentDescription("비서 프로필 설정"); avatar.setOnClickListener(v -> editProfile());
        // 48dp, so the avatar's nine expressions read in the header.
        header.addView(avatar, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))); profile.bind(avatar);
        LinearLayout title = ui.column(); title.setPadding(ui.dp(10), ui.dp(2), ui.dp(4), ui.dp(2)); title.setMinimumHeight(ui.dp(48)); title.setGravity(Gravity.CENTER_VERTICAL);
        name = ui.text(profile.name(), HEADLINE, 700, TEXT); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = ui.text("메인 대화", FOOTNOTE, 560, MUTED); subtitle.setSingleLine(true); subtitle.setEllipsize(TextUtils.TruncateAt.END);
        // The chevron rides on the model line itself, so a long model name ellipsizes without pushing it away.
        android.graphics.drawable.Drawable arrow = activity.getDrawable(R.drawable.ic_chevron);
        if (arrow != null) {
            android.graphics.drawable.RotateDrawable down = new android.graphics.drawable.RotateDrawable(); down.setDrawable(arrow.mutate()); down.setFromDegrees(90); down.setToDegrees(90); down.setLevel(1);
            down.setTint(MUTED); down.setBounds(0, 0, ui.dp(14), ui.dp(14)); subtitle.setCompoundDrawablesRelative(null, null, down, null); subtitle.setCompoundDrawablePadding(ui.dp(3));
        }
        subtitle.setPadding(0, ui.dp(2), 0, 0);
        title.addView(name); title.addView(subtitle, new LinearLayout.LayoutParams(-2, -2)); title.setContentDescription("메인 대화 모델 선택"); title.setOnClickListener(v -> models()); Ui.pressable(title, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(ui.iconButton(R.drawable.ic_more, BG, MUTED, "대화 메뉴", this::menu), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        root.addView(header);
        scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.setPadding(0, ui.dp(8), 0, ui.dp(8)); scroll.setClipToPadding(false);
        messages = ui.column(); messages.setPadding(ui.dp(4), 0, ui.dp(4), 0); scroll.addView(messages);
        // The conversation with one thing floating over its foot: the way back to the newest message, only while it is out of view.
        FrameLayout stream = new FrameLayout(activity); stream.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        toNewest = new ImageView(activity); toNewest.setImageResource(R.drawable.ic_chevron_down); toNewest.setImageTintList(android.content.res.ColorStateList.valueOf(TEXT)); toNewest.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        android.graphics.drawable.GradientDrawable disc = ui.shape(CARD, 99); disc.setStroke(Math.max(1, ui.dp(1)), SEPARATOR);
        toNewest.setBackground(new android.graphics.drawable.InsetDrawable(disc, ui.dp(4))); toNewest.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        toNewest.setContentDescription("최신 메시지로"); toNewest.setVisibility(View.INVISIBLE); Ui.pressable(toNewest, false);
        toNewest.setOnClickListener(v -> { Ui.tick(v); int foot = messages.getHeight(); if (Ui.motion()) scroll.smoothScrollTo(0, foot); else scroll.scrollTo(0, foot); });
        FrameLayout.LayoutParams floating = new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); floating.bottomMargin = ui.dp(6); stream.addView(toNewest, floating);
        root.addView(stream, new LinearLayout.LayoutParams(-1, 0, 1));
        messages.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> { follow(oldB - oldT, b - t); awayFromNewest(); });
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> { nearSuggestions(); awayFromNewest(); });
        talkStatus = ui.text("", FOOTNOTE, 560, MUTED); talkStatus.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), 0); talkStatus.setMaxLines(2); talkStatus.setEllipsize(TextUtils.TruncateAt.END); talkStatus.setVisibility(View.GONE);
        root.addView(talkStatus, new LinearLayout.LayoutParams(-1, -2));
        chipRow = ui.row();
        // 검색: 자동/항상/끔 for the next replies; a tap shows the three with what each does on the engine in use.
        LinearLayout chips = chipRow; chips.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), 0);
        searchChip = ui.row(); searchChip.setGravity(Gravity.CENTER_VERTICAL); searchChip.setBackground(ui.shape(FILL, PILL));
        searchChip.setPadding(ui.dp(11), ui.dp(6), ui.dp(13), ui.dp(6)); searchChip.setMinimumHeight(ui.dp(34));
        ImageView globe = new ImageView(activity); globe.setImageResource(R.drawable.ic_globe); globe.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED)); globe.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        searchChip.addView(globe, new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        searchLabel = ui.text("", FOOTNOTE, 600, MUTED); searchLabel.setPadding(ui.dp(6), 0, 0, 0); searchChip.addView(searchLabel);
        searchChip.setOnClickListener(v -> searchOptions()); Ui.pressable(searchChip, true);
        chips.addView(searchChip, new LinearLayout.LayoutParams(-2, -2)); searchChip();
        root.addView(chips, new LinearLayout.LayoutParams(-1, -2));
        filesScroll = new HorizontalScrollView(activity); filesScroll.setHorizontalScrollBarEnabled(false); filesScroll.setVisibility(View.GONE);
        filesRow = ui.row(); filesRow.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), 0); filesScroll.addView(filesRow);
        root.addView(filesScroll, new LinearLayout.LayoutParams(-1, -2));
        // One rounded composer, as in chat apps and in 작업창: a bare + on the left, the field, the mic, and the round send button,
        // the only accent on the page. A quiet gray ring shows the focus.
        composer = ui.row(); composer.setGravity(Gravity.BOTTOM); composer.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        android.graphics.drawable.GradientDrawable resting = ui.shape(CARD, 28), focused = ui.shape(CARD, 28); focused.setStroke(ui.dp(1.5f), (MUTED & 0x00ffffff) | 0x59000000);
        composer.setBackground(resting);
        composer.addView(ui.bareIcon(R.drawable.ic_add, TEXT, "작업과 도구", this::tools), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        input = new EditText(activity); input.setTextSize(16); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setHint("메시지 보내기");
        input.setBackground(null); input.setPadding(ui.dp(2), ui.dp(13), ui.dp(8), ui.dp(13)); input.setMinLines(1); input.setMaxLines(5); input.setMinimumHeight(ui.dp(48));
        input.setOnFocusChangeListener((v, has) -> composer.setBackground(has ? focused : resting));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12000)});
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        mic = ui.bareIcon(R.drawable.ic_mic, TEXT, MIC, this::dictate); holdToTalk(mic);
        composer.addView(mic, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        sendButton = ui.sendButton("메시지 보내기", () -> { if (offersVoice()) voice.start(); else send(); });
        sendButton.setOnLongClickListener(v -> { if (!offersVoice()) return false; voice.settings(""); return true; });
        composer.addView(sendButton, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        LinearLayout.LayoutParams composerParams = new LinearLayout.LayoutParams(-1, -2); composerParams.topMargin = ui.dp(4); composerParams.bottomMargin = ui.dp(8);
        // 음성 대화's bar takes the composer's place while a conversation is on.
        voice = new VoiceBar(activity, store, ui, "chat", () -> VoiceTargets.chat(store)); voice.aside(composer, chipRow); voice.track(this::track);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, -2); barParams.topMargin = ui.dp(4); barParams.bottomMargin = ui.dp(8);
        root.addView(voice.bar(), barParams);
        root.addView(composer, composerParams);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { sendLooks(); }
        });
        input.setText(saved == null ? store.settings.getString("mainChatDraft", "") : saved.getString("draft", ""));
        if (saved != null) { before = saved.getLong("before"); query = saved.getString("query", ""); }
        else {before=store.settings.getLong("mainChatBefore",0);query=store.settings.getString("mainChatQuery","");}
        restoreScrollY = saved == null ? store.settings.getInt("mainChatScrollY",-1) : saved.getInt("scrollY",-1);
        if(saved!=null){int start=Math.max(0,Math.min(input.length(),saved.getInt("selectionStart",input.length()))),end=Math.max(start,Math.min(input.length(),saved.getInt("selectionEnd",start)));input.setSelection(start,end);}
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            root.setPadding(ui.dp(12) + bars.left, bars.top, ui.dp(12) + bars.right, Math.max(bars.bottom, ime.bottom));
            input.setMaxLines(ime.bottom > 0 ? 3 : 5); return insets;
        });
        root.requestApplyInsets(); filesChanged(); refresh();
    }
    /**
     * The reading column (Ui.READING_WIDTH): in a wider page the messages, the status line, the search chip and the composer
     * are centered on the same two lines; a phone keeps its edges. The header stays at the page's own edge.
     */
    private void fit(int inner) {
        if (inner <= 0 || inner == fitted || composer == null) return; fitted = inner;
        int side = Math.max(0, (inner - ui.dp(READING_WIDTH)) / 2), text = Math.max(ui.dp(4), side);
        messages.setPadding(text, 0, text, 0);
        for (View view : new View[]{talkStatus, chipRow, filesScroll, composer, voice.bar()}) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) view.getLayoutParams();
            if (p == null || p.leftMargin == side && p.rightMargin == side) continue;
            p.leftMargin = side; p.rightMargin = side; view.setLayoutParams(p);
        }
    }
    private int fitted; private LinearLayout chipRow;
    View view(){return root;}
    void show(){
        if(!stopped)return;stopped=false;visibilityGeneration++;store.observe(observer);store.mainAssistant.watch(streamer);store.mainAssistant.reconcile();refresh();
        for(View answer:answers.values())RichAnswer.active(answer,true);for(Streaming reply:streaming.values())RichAnswer.active(reply.wrap,true);
        if(store.talk!=null){store.talk.onChange(this::talkChanged);talkChanged();}voice.attach();
        if(restoreScrollY>=0){int y=restoreScrollY;restoreScrollY=-1;postScroll(()->{if(!stopped)scroll.scrollTo(0,y);});}
        postScroll(this::nearSuggestions);
        // The page in question may have been set or ended on another screen; and one that sent the user here to write gets
        // the newest message and the keyboard.
        filesChanged();
        if(!store.mainDraft.isEmpty()){String draft=store.mainDraft;store.mainDraft="";latest();suggested(draft);}
        if(store.mainWrite){store.mainWrite=false;latest();input.requestFocus();android.view.inputmethod.InputMethodManager keyboard=activity.getSystemService(android.view.inputmethod.InputMethodManager.class);if(keyboard!=null)keyboard.showSoftInput(input,0);}
    }
    private void postScroll(Runnable action){int generation=visibilityGeneration;scroll.post(()->{if(!stopped&&generation==visibilityGeneration)action.run();});}
    void resume(){if(!stopped)profile.resume();if(!stopped&&talkOnResume){talkOnResume=false;talk(false);}}
    void pause(){if(dictation!=null)dictation.pause();stopListening();if(stopped)return;profile.pause();persist();}
    void hide(){
        if(stopped)return;restoreScrollY=scroll.getScrollY();pause();if(dictation!=null)dictation.cancel();stopped=true;visibilityGeneration++;voice.detach();store.unobserve(observer);store.mainAssistant.unwatch(streamer);
        for(View answer:answers.values())RichAnswer.active(answer,false);for(Streaming reply:streaming.values())RichAnswer.active(reply.wrap,false);
        if(profileEditor!=null){profileEditor.sheet.dismiss();profileEditor=null;}
        for(Dialog dialog:new ArrayList<>(dialogs))dialog.dismiss();dialogs.clear();modelPicker=null;modelPickerRefresh=null;
    }
    void save(Bundle out){out.putString("draft",input.getText().toString());out.putLong("before",before);out.putString("query",query);out.putInt("scrollY",stopped&&restoreScrollY>=0?restoreScrollY:scroll.getScrollY());out.putInt("selectionStart",input.getSelectionStart());out.putInt("selectionEnd",input.getSelectionEnd());}
    private void persist(){store.settings.edit().putString("mainChatDraft",input.getText().toString()).putLong("mainChatBefore",before).putString("mainChatQuery",query).putInt("mainChatScrollY",scroll.getScrollY()).apply();}
    boolean back(){if(!input.hasFocus())return false;android.view.inputmethod.InputMethodManager keyboard=activity.getSystemService(android.view.inputmethod.InputMethodManager.class);if(keyboard!=null)keyboard.hideSoftInputFromWindow(input.getWindowToken(),0);input.clearFocus();return true;}
    @Override public void close(){hide();voice.detach();if(dictation!=null)dictation.close();if(store.talk!=null)store.talk.onChange(null);profile.close();forget(answers);answers=new HashMap<>();drop(streaming);streaming=new HashMap<>();pictureLoader.shutdownNow();}
    /** Frees drawn answers that are no longer shown (their pages included). */
    private static void forget(Map<String, View> gone){for(View answer:gone.values()){RichAnswer.destroy(answer);if(answer.getParent() instanceof ViewGroup parent)parent.removeView(answer);}}
    /** Frees replies that were being written and are no longer shown as such. */
    private static void drop(Map<Long, Streaming> gone){for(Streaming reply:gone.values()){RichAnswer.destroy(reply.wrap);if(reply.wrap.getParent() instanceof ViewGroup parent)parent.removeView(reply.wrap);}}
    /** Tracks a dialog or sheet so leaving the page closes it. */
    private <T extends Dialog> T track(T dialog){dialogs.removeIf(d->!d.isShowing());dialogs.add(dialog);return dialog;}
    private AlertDialog.Builder dialog(){return new PageDialog();}
    /** A dismissed dialog can still have an Android Handler callback queued: never execute it in another page epoch. */
    private final class PageDialog extends AlertDialog.Builder {
        private final int generation=visibilityGeneration;
        PageDialog(){super(activity);}
        private DialogInterface.OnClickListener guarded(DialogInterface.OnClickListener listener){return listener==null?null:(dialog,which)->{if(!stopped&&generation==visibilityGeneration&&!activity.isFinishing()&&!activity.isDestroyed())listener.onClick(dialog,which);};}
        @Override public AlertDialog.Builder setPositiveButton(CharSequence text,DialogInterface.OnClickListener listener){return super.setPositiveButton(text,guarded(listener));}
        @Override public AlertDialog.Builder setNegativeButton(CharSequence text,DialogInterface.OnClickListener listener){return super.setNegativeButton(text,guarded(listener));}
        @Override public AlertDialog.Builder setNeutralButton(CharSequence text,DialogInterface.OnClickListener listener){return super.setNeutralButton(text,guarded(listener));}
        @Override public AlertDialog.Builder setItems(CharSequence[] items,DialogInterface.OnClickListener listener){return super.setItems(items,guarded(listener));}
        @Override public AlertDialog.Builder setAdapter(ListAdapter adapter,DialogInterface.OnClickListener listener){return super.setAdapter(adapter,guarded(listener));}
        @Override public AlertDialog create(){AlertDialog result=super.create();dialogs.removeIf(d->!d.isShowing());dialogs.add(result);return result;}
    }
    private boolean historyReady() { if (store.mainChat.available()) return true; error(store.mainChat.failure()); return false; }
    private void editProfile() { profileEditor=new AssistantProfileUi.Editor(activity, () -> { profile.reload(); name.setText(profile.name()); });profileEditor.show(); }
    private void send() {
        if(stopped)return;
        String text = input.getText().toString().strip(); JSONArray files = store.mainFiles; if (text.isEmpty() && files.length() == 0) return;
        // The picked files go with this message and leave the composer; one the engine cannot take keeps them all there.
        try { store.mainAssistant.enqueue(text, files.length() == 0 ? null : files); if (files.length() > 0) { store.mainFiles = new JSONArray(); filesChanged(); } input.setText(""); store.settings.edit().remove("mainChatDraft").apply(); latest(); }
        catch (Exception error) { error(error); }
    }
    /** Draws the chips of the files picked for the next message; the activity calls it when an import finishes. */
    void filesChanged() {
        if (filesRow == null) return;
        filesRow.removeAllViews(); JSONArray files = store.mainFiles;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i); String id = file.optString("id"), name = file.optString("name");
            TextView chip = ui.text(MainAttachments.label(new JSONArray().put(file)).replace(name, name.length() > 22 ? name.substring(0, 21) + "…" : name) + "  ✕", FOOTNOTE, 560, TEXT);
            chip.setBackground(ui.shape(FILL, PILL)); chip.setPadding(ui.dp(12), 0, ui.dp(12), 0); chip.setGravity(Gravity.CENTER); chip.setMinHeight(ui.dp(36));
            chip.setContentDescription(name + " 첨부 빼기");
            chip.setOnClickListener(v -> { if (stopped) return; JSONArray kept = new JSONArray(); for (int j = 0; j < store.mainFiles.length(); j++) if (!id.equals(store.mainFiles.optJSONObject(j).optString("id"))) kept.put(store.mainFiles.optJSONObject(j)); store.mainFiles = kept; store.releaseMainAttachments(List.of(id)); filesChanged(); });
            Ui.pressable(chip, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = ui.dp(6); filesRow.addView(chip, lp);
        }
        // The page this conversation was opened about (a page's 질문하기), first in the row: questions mean it, and it can be
        // fixed or added to by asking. ✕ ends that; the conversation goes on as an ordinary one.
        dev.jun.gyeot.core.PageBook.Page subject = PageStore.subject(activity, "main");
        if (subject != null) {
            String title = subject.title().length() > 18 ? subject.title().substring(0, 17) + "…" : subject.title();
            TextView chip = ui.text("📄 " + title + " · 질문 중  ✕", FOOTNOTE, 600, ACCENT);
            chip.setBackground(ui.shape(ACCENT_SOFT, PILL)); chip.setPadding(ui.dp(12), 0, ui.dp(12), 0); chip.setGravity(Gravity.CENTER);
            // One line by a line limit, not setSingleLine: that sets the height to one line of text and drops the 36dp below.
            chip.setMaxLines(1); chip.setMinHeight(ui.dp(36));
            chip.setContentDescription(subject.title() + " 페이지에 대해 질문 중, 눌러서 끝내기");
            chip.setOnClickListener(v -> { if (stopped) return; PageStore.about(activity, "main", ""); filesChanged(); });
            Ui.pressable(chip, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = ui.dp(6); filesRow.addView(chip, 0, lp);
        }
        filesScroll.setVisibility(files.length() == 0 && subject == null ? View.GONE : View.VISIBLE);
        input.setHint(files.length() > 0 ? "파일에 대해 물어보기" : subject != null ? "이 페이지에 질문하기" : "메시지 보내기");
        sendLooks();
    }
    /** Nothing to send: the send button starts 음성 대화 instead. */
    private boolean offersVoice() { return input != null && input.getText().toString().isBlank() && store.mainFiles.length() == 0; }
    private void sendLooks() { if (voice != null && sendButton != null) voice.looks(sendButton, offersVoice()); }
    /** Shows the way back down once the newest message is more than about a screen's third below the view (AWAY dp). */
    private void awayFromNewest() {
        if (toNewest == null) return;
        boolean away = messages.getHeight() - scroll.getScrollY() - scroll.getHeight() > ui.dp(AWAY);
        // Hidden, not gone: it keeps its place, so showing it never asks for a layout (this runs inside one, from the messages' own).
        if (away != (toNewest.getVisibility() == View.VISIBLE)) toNewest.setVisibility(away ? View.VISIBLE : View.INVISIBLE);
    }
    private void latest() { before = 0; query = ""; rendered = ""; refresh(); postScroll(() -> scroll.scrollTo(0,messages.getHeight())); }
    private MainSearch.Mode searchMode() { return MainSearch.Mode.of(store.settings.getString(MainSearch.SETTING, "")); }
    /** The chip shows the setting the next reply uses. */
    private void searchChip() {
        MainSearch.Mode mode = searchMode(); searchLabel.setText("검색 · " + mode.label);
        searchChip.setContentDescription("웹 검색 " + mode.label + " · 눌러서 바꾸기");
    }
    /**
     * 검색: 자동, 항상 or 끔, each with what it does on the engine in use (항상 forces the search only where the provider can;
     * NVIDIA NIM has no search at all). One global setting, since the app has one main conversation; it applies from the next
     * reply and never stops one being written.
     */
    private void searchOptions() {
        if (stopped) return;
        MainSearch.Mode current = searchMode(); String engine = ScopedModels.main(store).engine();
        Ui.Sheet sheet = menuSheet("웹 검색", "다음 답부터 적용돼요");
        LinearLayout list = ui.group();
        for (MainSearch.Mode mode : MainSearch.Mode.values()) {
            boolean on = mode == current; String help = MainSearch.help(engine, mode);
            LinearLayout option = ui.row(); option.setMinimumHeight(ui.dp(56)); option.setPadding(ui.dp(16), ui.dp(11), ui.dp(14), ui.dp(11));
            LinearLayout words = ui.column(); words.addView(ui.text(mode.label, BODY, on ? 680 : 500, on ? ACCENT : TEXT));
            TextView note = ui.text(help, FOOTNOTE, 450, MUTED); note.setPadding(0, ui.dp(2), 0, 0); words.addView(note);
            option.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView check = new ImageView(activity); check.setImageResource(R.drawable.ic_check); check.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            check.setVisibility(on ? View.VISIBLE : View.INVISIBLE); check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)); cp.leftMargin = ui.dp(10); option.addView(check, cp);
            option.setContentDescription("검색 " + mode.label + (on ? " · 선택됨" : "") + " · " + help);
            option.setOnClickListener(v -> {
                sheet.dismiss(); if (stopped) return; store.settings.edit().putString(MainSearch.SETTING, mode.key).apply();
                // 자동 or 항상 picked again tries the web tools this engine's connections refused (켜고 끄면 다시 시도해요).
                if (mode != MainSearch.Mode.OFF) MainAdapters.forgetRefusals(store, engine);
                searchChip();
            });
            Ui.pressable(option, true); ui.addRow(list, option, false);
        }
        sheet.body.addView(list);
        String kept = keptRefusal(engine);
        if (!kept.isEmpty()) { TextView refused = ui.text(kept, FOOTNOTE, 450, ERROR); refused.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0); sheet.body.addView(refused); }
        sheet.body.addView(ui.footer("찾은 페이지는 답 위에 출처로 보여요. 누르면 앱 브라우저로 열려요. " + MainSearch.FEES));
        sheet.show();
    }
    /** What the sheet says while a refused web tool is kept for the connection and model in use; "" while none is. */
    private String keptRefusal(String engine) {
        String model = ScopedModels.main(store).model(engine), account = store.workConnectionFingerprint(engine); long now = System.currentTimeMillis();
        if (model == null || model.isBlank() || account.isBlank()) return "";
        return MainSearch.remembered(MainSearch.refused(store.settings.getString(MainSearch.refusalKey(engine, model), ""), account, now),
            MainSearch.refused(store.settings.getString(MainSearch.fetchRefusalKey(engine, model), ""), account, now));
    }
    /** A recognizer for the composer mic, its name in the dialog and what the dialog says. */
    record Recognizer(ComposerDictation.Factory factory, String label, ComposerDictation.Words words) {}
    /**
     * The one place that picks the mic's recognizer: Gemini Transcribe Live with the API key, else the phone's own
     * recognizer (the one 누르고 말하기 uses), named for where the audio goes; null when none can run here. Its dialog also
     * says that a hold asks aloud, since a long press shows no tooltip.
     */
    Recognizer recognizer() {
        if (store.secrets.hasWorkKey()) return new Recognizer(() -> new GeminiComposerSession(store.secrets.workKey()), "Gemini 3.5 Transcribe Live", holdHint(ComposerDictation.Words.GEMINI));
        SpeechInput.Ears ears = store.talk().ears();
        if (!ears.available(activity)) return null;
        boolean local = ears.onDevice(activity);
        return new Recognizer(() -> new SpeechInput.Dictation(activity, ears), local ? "기기 음성 인식" : "휴대폰 음성 인식", holdHint(local ? ComposerDictation.Words.DEVICE : ComposerDictation.Words.SERVICE));
    }
    static final String HOLD_HINT = " 마이크를 길게 누른 채 말하면 바로 물어봐요.";
    private static ComposerDictation.Words holdHint(ComposerDictation.Words words) { return new ComposerDictation.Words(words.waiting(), words.ready() + HOLD_HINT, words.failedTail(), words.startFailed()); }
    /** The composer mic: what is said goes into the draft only; sending stays a tap. A tap while 누르고 말하기 is busy stops it. */
    private void dictate() {
        if (stopped) return;
        if (store.talk != null && store.talk.busy()) { store.talk.stop(); return; }
        if (store.voiceActive || store.recording || store.voiceChat != null) { error("음성 대화나 녹음을 종료한 뒤 음성을 입력해 주세요."); return; }
        Recognizer recognizer = recognizer();
        if (recognizer == null) { error("Gemini 음성 입력에 사용할 API 키가 없어요. 이 기기에는 쓸 수 있는 음성 인식도 없어요. 설정에서 Gemini를 연결해 주세요."); return; }
        store.stopReading(null); store.stopReport();
        if (dictation == null) dictation = new ComposerDictation(activity, input, () -> "main:" + visibilityGeneration, message -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
        dictation.begin(recognizer.factory(), recognizer.label(), recognizer.words());
    }
    /**
     * 누르고 말하기 on the same mic: hold to ask aloud, let go and the main chat answers aloud a sentence at a time. A hold is
     * clearer than a fourth composer button and keeps the tap for dictation. Any press while it is busy stops it at once
     * (speech, recognizer and the reply being prepared), and that press never also starts dictation.
     */
    private void holdToTalk(ImageView mic) {
        View.OnTouchListener feedback = Ui.press(false);
        mic.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (store.talk != null && store.talk.busy()) { store.talk.stop(); return true; }
                // A finger on the mic holds it until it lets go; TalkBack's long press has no release, so the recognizer's end of speech ends it.
                boolean held = touching, started = talk(held);
                holding = started && held;
                if (started) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                return true;
            }
            // Only a hold that starts listening buzzes, once.
            @Override public boolean onLongClickUseDefaultHapticFeedback(View v) { return false; }
        });
        mic.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked(); boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
            if (action == MotionEvent.ACTION_DOWN) touching = true; else if (up) touching = false;
            if (action == MotionEvent.ACTION_DOWN && store.talk != null && store.talk.busy()) { swallow = true; store.talk.stop(); }
            if (swallow) { if (up) swallow = false; return true; }
            feedback.onTouch(v, event);
            if (up && holding) { holding = false; if (store.talk != null) store.talk.release(); }
            return false;
        });
        mic.setTooltipText("음성 입력 · 길게 누른 채 말하면 바로 물어봐요");
        mic.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, "말로 묻기"));
            }
        });
    }
    /** 말로 묻기 from the notification: listen once the screen is back, until the recognizer hears the end (a tap cannot be held). */
    void talkWhenResumed() { talkOnResume = true; }
    /** Starts 누르고 말하기 (held, or a tap that the recognizer ends), asking for the microphone first. */
    private boolean talk(boolean held) {
        if (stopped) return false;
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            talkAfterPermission = held ? HOLD : TAP;
            activity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, ConnectedVoice.PERMISSION_REQUEST);
            return false;
        }
        ConnectedVoice voice = store.talk(); voice.onChange(this::talkChanged);
        return voice.listen(held);
    }
    /** The page is leaving or pausing: an open recognizer closes (an answer already asked for goes on). */
    private void stopListening() { if (store.talk != null && store.talk.listening()) store.talk.stop(); holding = false; }
    /** The mic and the line above the composer show 누르고 말하기's state. */
    private void talkChanged() {
        if (mic == null || talkStatus == null) return;
        ConnectedVoice voice = store.talk; PushToTalk.State state = voice == null ? PushToTalk.State.IDLE : voice.state();
        boolean idle = state == PushToTalk.State.IDLE;
        mic.setImageResource(idle || state == PushToTalk.State.LISTENING ? R.drawable.ic_mic : R.drawable.ic_stop);
        // Busy, the mic is an ink disc the size of the send button (the accent stays with send alone).
        mic.setBackground(idle ? null : new android.graphics.drawable.InsetDrawable(ui.shape(TEXT, PILL), ui.dp(5))); mic.setImageTintList(android.content.res.ColorStateList.valueOf(idle ? TEXT : BG));
        mic.setContentDescription(idle ? MIC : state == PushToTalk.State.LISTENING ? "듣는 중 · 누르면 멈춰요" : "말로 묻기 멈추기");
        talkStatus.setText(switch (state) {
            case LISTENING -> !voice.partial().isBlank() ? voice.partial() : voice.paused() ? "듣기를 멈췄어요 · 놓으면 물어봐요" : !voice.ready() ? "듣기 준비 중…" : holding ? "듣고 있어요 · 놓으면 물어봐요" : "듣고 있어요 · 말이 끝나면 물어봐요";
            case THINKING -> "답을 준비하고 있어요 · 마이크를 누르면 멈춰요";
            case SPEAKING -> "답을 읽고 있어요 · 마이크를 누르면 멈춰요";
            default -> "";
        });
        talkStatus.setVisibility(idle ? View.GONE : View.VISIBLE);
        // The question was sent: show the newest messages, where it and its answer appear.
        if (talkSeen == PushToTalk.State.LISTENING && state == PushToTalk.State.THINKING && !stopped) latest();
        talkSeen = state;
    }
    /** 캘린더에 추가 on a card: asks for the calendar permission first when it is missing, then adds. */
    private void addEventAsked(long reply, int index) {
        if (!PhoneCalendar.writable(activity)) {
            eventAfterPermission = reply; eventIndexAfterPermission = index; store.settings.edit().putBoolean(PhoneCalendar.ASKED, true).apply();
            activity.requestPermissions(PhoneCalendar.PERMISSIONS, PhoneCalendar.CARD_REQUEST);
            return;
        }
        try { store.mainAssistant.addEvent(reply, index, this::error); } catch (Exception e) { error(e); }
    }
    boolean permissionResult(int code, int[] results) {
        if (voice != null && voice.permissionResult(code, results)) return true;
        if (code == PhoneCalendar.CARD_REQUEST) {
            long reply = eventAfterPermission; int index = eventIndexAfterPermission; eventAfterPermission = -1;
            if (!PhoneCalendar.writable(activity)) Toast.makeText(activity, "캘린더 권한이 없어서 추가하지 않았어요. 설정 → 캘린더에서 허용할 수 있어요.", Toast.LENGTH_LONG).show();
            else if (reply > 0) addEventAsked(reply, index);
            return true;
        }
        if (code == ConnectedVoice.PERMISSION_REQUEST) {
            int asked = talkAfterPermission; talkAfterPermission = 0;
            boolean granted = results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!granted) Toast.makeText(activity, ConnectedVoice.NO_MIC, Toast.LENGTH_LONG).show();
            else if (asked == TAP) talk(false);
            else if (asked == HOLD) Toast.makeText(activity, "이제 마이크를 길게 누른 채 말해 주세요.", Toast.LENGTH_LONG).show();
            return true;
        }
        return dictation != null && dictation.permissionResult(code, results);
    }
    private static String readKey(JSONObject m) { return "main:" + m.optLong("id"); }
    /** 읽어 주기: the answer as shown, sentence by sentence; a second tap stops it. */
    private void readAloud(JSONObject m) { if (!stopped) store.speech().toggle(readKey(m), m.optString("text")); }
    /** 답변 자동 읽기: a finished answer that appears while the latest messages show is read once; ones already there never are. */
    private void autoRead(JSONArray rows) {
        if (before != 0 || !query.isBlank()) return;
        boolean primed = heard != null; if (!primed) heard = new HashSet<>();
        boolean on = store.settings.getBoolean(Speech.AUTO_READ, false);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject m = rows.optJSONObject(i);
            if (m == null || !m.optString("role").equals("assistant") || !m.optString("state").equals("done")) continue;
            // An answer to 누르고 말하기 was already read aloud as it arrived.
            if (heard.add(m.optLong("id")) && primed && on && !(store.talk != null && store.talk.voiced(m.optLong("id")))) store.speech().enqueue(readKey(m), m.optString("text"));
        }
    }
    /** The newest message is a reply that failed. */
    private boolean lastReplyFailed() {
        if (!store.mainChat.available()) return false;
        org.json.JSONArray last = store.mainChat.page(0, 1); org.json.JSONObject m = last.optJSONObject(last.length() - 1);
        return m != null && "assistant".equals(m.optString("role")) && "error".equals(m.optString("state"));
    }
    private void refresh() {
        if (activity.isFinishing() || activity.isDestroyed() || messages == null || stopped) return;
        try {
            if(modelPickerRefresh!=null)modelPickerRefresh.run();voice.refresh();
            // The living avatar thinks while replies are being prepared, is briefly happy when they have arrived (sad when the last one failed), dozes when quiet.
            try { profile.working(store.mainChat.available() ? store.mainChat.pendingCount() : 0, this::lastReplyFailed); } catch (Exception ignored) {}
            name.setText(profile.name()); ScopedModels models = ScopedModels.main(store); String engine = models.engine(), model = models.model(engine);
            String level = model.isBlank() ? "" : models.effort(engine, model);
            subtitle.setText(models.readyEngine().isBlank() ? "모델 연결·선택하기" : WorkBook.author(engine) + " · " + new TeamRoster(store, models).label(engine, model) + (level.isEmpty() ? "" : " · " + level(engine, level)));
            if (!store.mainChat.available()) {
                if (!rendered.equals("unavailable")) { rendered = "unavailable"; messages.removeAllViews(); forget(answers); answers = new HashMap<>(); drop(streaming); streaming = new HashMap<>(); add(ui.text(store.mainChat.failure(), 16, 400, ERROR)); add(ui.ghost("기존 도구·설정 열기", this::tools)); }
                return;
            }
            JSONArray rows = query.isBlank() ? store.mainChat.page(before, 40) : store.mainChat.search(query, before, 40);
            JSONArray jobs = store.mainChat.jobs(false);
            String signature = query + ":" + before + ":" + rows + ":" + jobs; if (signature.equals(rendered)) return; rendered = signature;
            boolean bottom = atBottom(); int oldY = scroll.getScrollY(); messages.removeAllViews();
            HashMap<String, View> previous = answers; answers = new HashMap<>(); labels.clear(); live = liveAnswers(rows); latestReply = store.mainChat.latestReply(); offering = newestAnswer(rows);
            HashMap<Long, Streaming> writing = streaming; streaming = new HashMap<>();
            if (!query.isBlank()) add(ui.ghost("검색: " + query + " · 최근 대화로", this::latest));
            if (before > 0) add(ui.ghost("최근 대화로", this::latest));
            if (rows.length() == 40) {
                long oldest = rows.optJSONObject(0).optLong("id");
                add(ui.ghost(query.isBlank() ? "이전 메시지 보기" : "이전 검색 결과", () -> { before = oldest; rendered = ""; refresh(); postScroll(() -> scroll.scrollTo(0, 0)); }));
            }
            if (rows.length() == 0) empty();
            for (int i = 0; i < rows.length(); i++) { JSONObject message = rows.optJSONObject(i); if (message != null) bubble(message, previous, writing); }
            forget(previous); drop(writing); autoRead(rows);
            postScroll(() -> { if (bottom && before == 0 && query.isBlank()) scroll.scrollTo(0,messages.getHeight()); else scroll.scrollTo(0, oldY); nearSuggestions(); });
        } catch (Exception problem) {
            rendered = ""; messages.removeAllViews(); forget(answers); answers = new HashMap<>(); drop(streaming); streaming = new HashMap<>(); add(ui.text("대화 기록을 표시하지 못했어요. 원본을 지우거나 새로 만들지 않았어요.", 16, 400, ERROR)); add(ui.ghost("기존 도구·설정 열기", this::tools));
        }
    }
    /** The reader is at (or within 100dp of) the newest message, so new text may pull the list along. */
    private boolean atBottom() { return messages.getHeight() - scroll.getScrollY() - scroll.getHeight() < ui.dp(100); }
    /** A reply still being written has more to show: only its own view changes (its last block); follow() keeps the list with it. */
    private void streamed(long reply) {
        if (stopped) return;
        Streaming view = streaming.get(reply); if (view != null) view.update(store.mainAssistant.live(reply));
    }
    /**
     * Content that grew under a reader who was at the bottom (an answer being written, a picture or a visual settling)
     * keeps them at the bottom once it is laid out; someone reading further up, an older page or a search stays put.
     */
    private void follow(int before, int after) {
        if (stopped || before <= 0 || after <= before || this.before != 0 || !query.isBlank()) return;
        if (before - scroll.getScrollY() - scroll.getHeight() < ui.dp(100)) scroll.scrollTo(0, after);
    }
    /** An empty conversation's starters: the chip's name, and the words it puts in the message box. */
    static final String[][] STARTERS = {{"할 일 정리", "오늘 할 일을 정리해 줘:"}, {"쉽게 설명", "쉽게 설명해 줘:"}, {"표로 비교", "표로 비교해 줘:"}, {"그림 그리기", "그려 줘:"}};
    /** The first screen of an empty conversation (or of a search that found nothing). */
    private void empty() {
        LinearLayout box = ui.column(); box.setGravity(Gravity.CENTER_HORIZONTAL); box.setPadding(ui.dp(16), ui.dp(48), ui.dp(16), ui.dp(24));
        if (!query.isBlank()) { TextView none = ui.text("찾은 메시지가 없어요. 다른 단어로 검색해 보세요.", 16, 400, MUTED); none.setGravity(Gravity.CENTER); box.addView(none); add(box); return; }
        TextView hello = ui.title("무엇을 도와드릴까요?", 22); hello.setGravity(Gravity.CENTER); box.addView(hello);
        TextView hint = ui.text("글, 표, 코드, 그림까지 여기서 바로 답해요.", CALLOUT, 420, MUTED);
        hint.setGravity(Gravity.CENTER); hint.setPadding(0, ui.dp(8), 0, ui.dp(16)); box.addView(hint);
        // Things to try, not things to read: a tap puts the opening words in the message box for the user to finish.
        LinearLayout pair = null;
        for (int i = 0; i < STARTERS.length; i++) {
            // Two to a centered line, so the four read as one small block under the title at any font size.
            if (i % 2 == 0) { pair = ui.row(); pair.setGravity(Gravity.CENTER); box.addView(pair, new LinearLayout.LayoutParams(-1, -2)); }
            String ask = STARTERS[i][1]; android.widget.Button starter = ui.chip(STARTERS[i][0], () -> suggested(ask)); starter.setContentDescription(STARTERS[i][0] + " 예시 넣기");
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2); at.setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)); pair.addView(starter, at);
        }
        // Side by side; one under the other when a large font would break a link into two lines.
        LinearLayout links = activity.getResources().getConfiguration().fontScale > 1.3f ? ui.column() : ui.row(); links.setGravity(Gravity.CENTER); links.setPadding(0, ui.dp(10), 0, 0);
        links.addView(ui.ghost("메인 모델 선택", this::models)); links.addView(ui.ghost("대화·기억이 보관되는 방식", this::privacy)); box.addView(links);
        add(box);
    }
    /**
     * How many visuals of each answer run inline: the newest answers', up to LIVE_VISUALS visual blocks in all, an answer's
     * first ones (as while it was being written) when only some of them fit.
     */
    private Map<Long, Integer> liveAnswers(JSONArray rows) {
        Map<Long, Integer> result = new HashMap<>(); int left = LIVE_VISUALS;
        for (int i = rows.length() - 1; i >= 0 && left > 0; i--) {
            JSONObject m = rows.optJSONObject(i); if (m == null || !m.optString("role").equals("assistant") || !m.optString("state").equals("done")) continue;
            String text = m.optString("text"), lower = text.toLowerCase(Locale.ROOT);
            if (!text.contains("```") && !text.contains("~~~") && !lower.contains("<svg") && !lower.contains("<html") && !lower.contains("<!doctype")) continue;
            int count = RichBlocks.visuals(text); if (count > 0) { result.put(m.optLong("id"), Math.min(count, left)); left -= count; }
        }
        return result;
    }
    private Map<Long, Integer> live = new HashMap<>();
    /**
     * The answer whose suggested requests show: the newest message on the latest page when it is an answer (memory notices
     * aside). Once anything follows it, or an older page or a search is open, no answer offers its suggestions.
     */
    private long newestAnswer(JSONArray rows) {
        if (before != 0 || !query.isBlank()) return -1;
        for (int i = rows.length() - 1; i >= 0; i--) {
            JSONObject m = rows.optJSONObject(i); if (m == null || m.optString("role").equals("memory")) continue;
            return m.optString("role").equals("assistant") ? m.optLong("id") : -1;
        }
        return -1;
    }
    private long offering = -1;
    /** A suggested request the user tapped: into the message box with the keyboard up, for the user to read, change and send. */
    private void suggested(String ask) {
        if (stopped) return;
        input.setText(RichBlocks.drafted(input.getText().toString(), ask)); input.setSelection(input.length()); input.requestFocus();
        android.view.inputmethod.InputMethodManager keyboard = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.showSoftInput(input, 0);
    }
    /** The conversation's newest answer at this refresh: only a cut-off answer that is still newest offers 이어서 답하기. */
    private long latestReply;
    /** Model labels for this refresh, so forty captions do not read the catalog forty times. */
    private final HashMap<String, String> labels = new HashMap<>();
    private String modelLabel(JSONObject m) {
        String engine = m.optString("engine"), model = m.optString("model");
        if (model.isBlank()) return "";
        if (engine.isBlank()) return model;
        return labels.computeIfAbsent(engine + "\n" + model, k -> { try { return new TeamRoster(store, ScopedModels.main(store)).label(engine, model); } catch (Exception e) { return model; } });
    }
    private void add(View view) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(8); messages.addView(view, p); }
    /** What automatic memory kept, as a quiet line in the timeline; tapping it opens memory management. */
    private void notice(JSONObject m) {
        TextView line = ui.text(m.optString("text"), 13, 450, MUTED); line.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4));
        line.setGravity(Gravity.CENTER_HORIZONTAL); line.setContentDescription("자동 기억 알림 · 눌러서 기억 관리");
        line.setOnClickListener(v -> memories()); line.setOnLongClickListener(v -> { messageMenu(m); return true; });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(10); messages.addView(line, p);
    }
    private String stamp(JSONObject m) {
        ZonedDateTime at = Instant.ofEpochMilli(m.optLong("created", m.optLong("at"))).atZone(ZoneId.systemDefault());
        return at.format(DateTimeFormatter.ofPattern(at.toLocalDate().equals(LocalDate.now()) ? "HH:mm" : "M.d HH:mm"));
    }
    /** One message: the user's own on the right in a bubble, answers open on the canvas like Claude/ChatGPT, job records as cards. */
    private void bubble(JSONObject m, Map<String, View> previous, Map<Long, Streaming> writing) {
        String role = m.optString("role");
        if (role.equals("memory")) { notice(m); return; }
        if (role.equals("user")) { mine(m); return; }
        if (role.equals("job")) { job(m); return; }
        answer(m, previous, writing);
    }
    private void mine(JSONObject m) {
        long id = m.optLong("id"); String text = m.optString("text");
        LinearLayout wrap = ui.column(); wrap.setGravity(Gravity.END);
        TextView body = ui.bubble(text);
        body.setOnLongClickListener(v -> { messageMenu(m); return true; }); wrap.setOnLongClickListener(v -> { messageMenu(m); return true; });
        wrap.addView(body, new LinearLayout.LayoutParams(-2, -2));
        // What was attached, under the words: names only, never the files' content.
        String attached = MainAttachments.label(MainAttachments.of(m));
        if (!attached.isEmpty()) { TextView files = ui.text(attached, FOOTNOTE, 520, MUTED); files.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), 0); files.setGravity(Gravity.END); files.setContentDescription("첨부: " + attached); wrap.addView(files, new LinearLayout.LayoutParams(-2, -2)); }
        TextView meta = ui.text(stamp(m), 11.5f, 450, MUTED); meta.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), 0); wrap.addView(meta, new LinearLayout.LayoutParams(-2, -2));
        if (suggestsWork(text)) wrap.addView(ui.ghost("작업으로 보내기", () -> confirmJob(id, text)), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(16); p.leftMargin = ui.dp(48); messages.addView(wrap, p);
    }
    /**
     * An answer: Markdown and visuals while done (생각 과정 folded above it when the provider summarized its thinking), the
     * answer as it is written while pending, what had arrived with 이어서 답하기 when the app closed on it, a soft notice
     * when it failed.
     */
    private void answer(JSONObject m, Map<String, View> previous, Map<Long, Streaming> writing) {
        // written is the answer as saved; text is what is copied and shared, without the requests it suggests (buttons, not words).
        String state = m.optString("state"), written = m.optString("text"), text = RichBlocks.withoutSuggestions(written); long id = m.optLong("id");
        boolean pending = state.equals("running") || state.equals("queued");
        if (pending) {
            Streaming reply = writing.remove(id);
            if (reply == null) reply = new Streaming(m);
            else if (reply.wrap.getParent() instanceof ViewGroup parent) parent.removeView(reply.wrap);
            reply.state(state); reply.content.offer(id == offering); reply.update(store.mainAssistant.live(id)); streaming.put(id, reply);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(20); messages.addView(reply.wrap, p);
            return;
        }
        LinearLayout wrap = ui.column(); wrap.setOnLongClickListener(v -> { messageMenu(m); return true; });
        String partial = m.optString("partial"); boolean cut = state.equals("interrupted") && !partial.isBlank();
        // What came from the web with the answer, kept beside its text (core/MainSearch).
        MainSearch.Web web = state.equals("done") ? MainSearch.web(m.optString("web")) : MainSearch.Web.NONE;
        if (state.equals("done")) {
            if (!m.optString("thinking").isBlank()) { Thought thought = new Thought(id); thought.set(m.optString("thinking")); wrap.addView(thought.view); }
            int liveVisuals = live.getOrDefault(id, 0);
            String key = id + ":" + written.hashCode() + ":" + written.length() + ":" + liveVisuals + ":" + DARK;
            View content = previous.remove(key);
            if (content == null) content = RichAnswer.build(activity, ui, written, liveVisuals, host(m));
            else if (content.getParent() instanceof ViewGroup parent) parent.removeView(content);
            // Only the newest answer offers its suggested requests; a kept view just shows or hides them.
            RichAnswer.offer(content, id == offering);
            answers.put(key, content); wrap.addView(content);
            if (web.sources().length() > 0) wrap.addView(sources(web.sources()), new LinearLayout.LayoutParams(-1, -2));
            // Google's search suggestions under a grounded Gemini answer, as wide as it, as Google sent them, made once the
            // answer comes near the screen.
            if (!web.suggestions().isBlank()) {
                String entry = "suggest:" + id + ":" + web.suggestions().hashCode() + ":" + web.suggestions().length() + ":" + DARK;
                View suggestions = previous.remove(entry);
                if (suggestions == null) suggestions = new Suggestions(web.suggestions());
                else if (suggestions.getParent() instanceof ViewGroup parent) parent.removeView(suggestions);
                answers.put(entry, suggestions);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = ui.dp(6); wrap.addView(suggestions, sp);
            }
        } else if (cut) {
            // The app closed while this answer was being written: what had arrived, and a new request to carry on from it.
            String key = "cut:" + id + ":" + partial.hashCode() + ":" + partial.length() + ":" + DARK;
            View content = previous.remove(key);
            if (content == null) { RichAnswer.Live drawn = new RichAnswer.Live(activity, ui, host(m), false); drawn.update(partial); content = drawn.view(); }
            else if (content.getParent() instanceof ViewGroup parent) parent.removeView(content);
            answers.put(key, content); wrap.addView(content);
            // Only the newest answer is carried on, and only once: the turn 이어서 답하기 starts is newer, so the offer goes.
            LinearLayout stop = ui.row(); stop.setPadding(ui.dp(4), ui.dp(2), 0, 0); boolean newest = id == latestReply;
            stop.addView(ui.text(newest ? "중단됨 ·" : "중단됨", FOOTNOTE, 600, MUTED)); if (newest) stop.addView(ui.ghost("이어서 답하기", () -> carryOn(m)));
            wrap.addView(stop);
        } else {
            // error, interrupted or cancelled: a soft strip with the reason, and resending one tap away.
            TextView reason = ui.banner(text, state.equals("cancelled") ? MUTED : ERROR); reason.setOnLongClickListener(v -> { messageMenu(m); return true; });
            wrap.addView(reason);
        }
        // Under the answer: icons only (copy, read aloud, share, ask again on the newest, ⋯), then its time and model in small gray. What the turn took
        // ("첫 글자 1.1초 · 12.4k 토큰 · 캐시 78%") is in ⋯ as 이 답의 정보, not on the page.
        LinearLayout foot = ui.row(); boolean done = state.equals("done"); int size = ui.dp(48);
        if (done) {
            foot.addView(ui.footIcon(R.drawable.ic_copy, "답변 복사", () -> copy(Markdown.citations(text))), new LinearLayout.LayoutParams(size, size));
            foot.addView(ui.footIcon(R.drawable.ic_sound, "답변 읽어 주기", () -> readAloud(m)), new LinearLayout.LayoutParams(size, size));
            foot.addView(ui.footIcon(R.drawable.ic_share, "답변 공유", () -> share(text)), new LinearLayout.LayoutParams(size, size));
            // The newest answer can be asked for again: the same question goes once more, and the first answer stays above.
            if (id == latestReply && m.optLong("reply_to") > 0) foot.addView(ui.footIcon(R.drawable.ic_refresh, "다시 답하기: 같은 질문을 한 번 더 보내요", () -> { Ui.tick(foot); resend(m.optLong("reply_to")); }), new LinearLayout.LayoutParams(size, size));
            foot.addView(ui.footIcon(R.drawable.ic_more, "답변 더 보기: 페이지에 저장, 이 답의 정보, 삭제", () -> messageMenu(m)), new LinearLayout.LayoutParams(size, size));
        }
        if (!cut && List.of("error", "interrupted", "cancelled").contains(state) && m.optLong("reply_to") > 0)
            foot.addView(ui.ghost("다시 보내기", () -> resend(m.optLong("reply_to"))));
        String caption = stamp(m); String model = modelLabel(m); if (!model.isBlank()) caption += " · " + model;
        if (web.unsearched()) caption += " · " + MainSearch.UNSEARCHED;
        TextView meta = ui.text(caption, 11.5f, 450, MUTED); meta.setSingleLine(true); meta.setEllipsize(TextUtils.TruncateAt.END); meta.setPadding(ui.dp(done ? 6 : 2), 0, 0, 0);
        foot.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        // The first glyph sits on the text's left edge; its target reaches past it.
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2); if (done) { fp.leftMargin = -ui.dp(14); fp.topMargin = -ui.dp(2); } else fp.topMargin = ui.dp(2);
        wrap.addView(foot, fp);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(done ? 10 : 20); messages.addView(wrap, p);
    }
    /**
     * A reply still being prepared: the thinking line until its first piece arrives, then the answer as it is written (only
     * what is still open drawn again, normally the paragraph being written; up to LIVE_VISUALS visuals run, each once its
     * fence has closed), 생각 과정 folded above it, and 이 답변 중지 below.
     */
    private final class Streaming {
        final LinearLayout wrap = ui.column(); final RichAnswer.Live content; final Thought thought; final TextView status;
        private String state = "";
        Streaming(JSONObject m) {
            long id = m.optLong("id");
            wrap.setOnLongClickListener(v -> { messageMenu(m); return true; });
            thought = new Thought(id); thought.view.setVisibility(View.GONE); wrap.addView(thought.view);
            content = new RichAnswer.Live(activity, ui, host(m), true, LIVE_VISUALS); content.view().setVisibility(View.GONE); wrap.addView(content.view(), new LinearLayout.LayoutParams(-1, -2));
            LinearLayout line = ui.row(); line.setMinimumHeight(ui.dp(40));
            View dot = new View(activity); dot.setBackground(ui.shape(MUTED, PILL));
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) { android.view.animation.AlphaAnimation pulse = new android.view.animation.AlphaAnimation(1f, 0.25f); pulse.setDuration(650); pulse.setRepeatMode(android.view.animation.Animation.REVERSE); pulse.setRepeatCount(android.view.animation.Animation.INFINITE); dot.startAnimation(pulse); }
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)); dp.rightMargin = ui.dp(8); line.addView(dot, dp);
            status = ui.text("", 13.5f, 500, MUTED); line.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
            // The same place 작업창 stops a turn: a small ink 중지 at the end of the gray status line.
            TextView stop = ui.ghost("이 답변 중지", () -> { store.mainAssistant.cancel(id); refresh(); }); stop.setTextColor(TEXT); stop.setMinHeight(ui.dp(48)); stop.setMinimumHeight(ui.dp(48));
            line.addView(stop);
            wrap.addView(line);
        }
        void state(String value) { state = value; }
        /** Draws what has arrived so far; an unchanged answer is not drawn again. */
        void update(MainChatEngine.Live live) {
            String text = live == null ? "" : live.text(), thinking = live == null ? "" : live.thinking();
            thought.set(thinking); thought.view.setVisibility(thinking.isBlank() ? View.GONE : View.VISIBLE);
            if (!text.equals(content.text())) content.update(text);
            content.view().setVisibility(text.isBlank() ? View.GONE : View.VISIBLE);
            String doing = live == null ? "" : live.status();
            status.setText(state.equals("queued") ? "답변 차례를 기다리는 중…" : !doing.isBlank() ? doing : !text.isBlank() ? "답을 쓰고 있어요…" : !thinking.isBlank() ? "생각하고 있어요…" : "답변을 생각하고 있어요…");
        }
    }
    /**
     * 생각 과정: the provider's summary of its thinking, folded by default above the answer; never part of the answer's text.
     * It is drawn only while open, so a long summary streaming in, or on a finished answer, costs nothing folded.
     */
    private final class Thought {
        final LinearLayout view = ui.column(); private final TextView body; private final ImageView arrow; private final long id; private String text = ""; private boolean drawn = true;
        Thought(long id) {
            this.id = id;
            LinearLayout head = ui.row(); head.setMinimumHeight(ui.dp(40)); head.setPadding(ui.dp(2), 0, ui.dp(8), 0);
            head.addView(ui.text("생각 과정", FOOTNOTE, 600, MUTED));
            arrow = new ImageView(activity); arrow.setImageResource(R.drawable.ic_chevron); arrow.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED)); arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)); ap.leftMargin = ui.dp(4); head.addView(arrow, ap);
            body = ui.text("", FOOTNOTE, 420, MUTED); body.setLineSpacing(ui.dp(3), 1); body.setPadding(ui.dp(10), ui.dp(2), ui.dp(4), ui.dp(8));
            view.addView(head, new LinearLayout.LayoutParams(-2, -2)); view.addView(body, new LinearLayout.LayoutParams(-1, -2));
            head.setOnClickListener(v -> { if (!unfolded.remove(this.id)) unfolded.add(this.id); shown(); }); Ui.pressable(head, true);
            shown();
        }
        void set(String value) { String next = value == null ? "" : value; if (next.equals(text)) return; text = next; drawn = false; if (unfolded.contains(id)) draw(); }
        private void draw() { if (drawn) return; drawn = true; body.setText(Markdown.looksLike(text) ? RichText.render(text) : text); }
        private void shown() {
            boolean open = unfolded.contains(id); if (open) draw(); body.setVisibility(open ? View.VISIBLE : View.GONE); arrow.setRotation(open ? 90 : 0);
            ((View) arrow.getParent()).setContentDescription("생각 과정 · " + (open ? "접기" : "펼치기"));
        }
    }
    /**
     * 이어서 답하기: a new turn asking to carry on from the cut-off answer m, named by its first words (it goes along as
     * history); nothing is ever sent again on its own. A second tap, or one after another turn, sends nothing.
     */
    private void carryOn(JSONObject m) {
        if (stopped) return;
        try { if (store.mainChat.latestReply() != m.optLong("id")) { rendered = ""; refresh(); return; } } catch (Exception problem) { error(problem); return; }
        String ask = carryOn(m.optString("partial"));
        try { store.mainAssistant.enqueue(ask); latest(); }
        catch (Exception problem) { input.setText(ask); input.setSelection(input.length()); error(problem); }
    }
    /** What 이어서 답하기 sends for a cut-off answer: a request that names it by its first words. */
    static String carryOn(String partial) {
        String start = Markdown.plain(partial == null ? "" : partial).replaceAll("\\s+", " ").strip(), head = MainChatContext.clip(start, CARRY_ON_WORDS);
        return head.isEmpty() ? "끊긴 답을 이어서 답해 줘." : "끊긴 답(“" + head + (head.length() < start.length() ? "…" : "") + "”)을 이어서 답해 줘.";
    }
    /** A delegated job's record and its live state, as a card: it is a receipt, not conversation. */
    private void job(JSONObject m) {
        String state = m.optString("state"); long id = m.optLong("id");
        LinearLayout wrap = ui.column(); wrap.setPadding(ui.dp(15), ui.dp(11), ui.dp(15), ui.dp(10)); wrap.setBackground(ui.shape(CARD, 20));
        String text = state.equals("queued") ? "답변 차례를 기다리는 중…" : state.equals("running") ? "답변을 생각하고 있어요…" : m.optString("text");
        text = "작업 · " + (text.equals("작업창으로 전달할 준비 중이에요.") ? "맡긴 요청 기록이에요. 전달 상태는 아래에서 확인해요." : text);
        TextView body = ui.text(text, 16, 400, TEXT); body.setTextIsSelectable(true); body.setLineSpacing(ui.dp(3), 1); wrap.addView(body);
        body.setOnLongClickListener(v -> { messageMenu(m); return true; }); wrap.setOnLongClickListener(v -> { messageMenu(m); return true; });
        TextView meta = ui.text(stamp(m), 11, 450, MUTED); meta.setPadding(0, ui.dp(6), 0, 0); wrap.addView(meta);
        String jobId = m.optString("job");
        if (!jobId.isBlank()) {
            JSONObject job = store.mainChat.job(jobId);
            if (job != null && job.optInt("hidden") == 0) {
                String room = job.optString("room"), jobState = job.optString("state");
                String status = jobState(jobState);
                wrap.addView(ui.ghost(status + (room.isBlank() ? "" : " · 작업창 열기"), () -> { if (!room.isBlank()) openWork(room); else error("전달 여부를 확인하지 못했어요. 자동 재실행하지 않아요."); }));
                if (jobState.equals("running") && !room.isBlank()) wrap.addView(ui.ghost("이 작업 중지…", () -> dialog().setMessage("이 작업만 중지할까요? 이미 보낸 원격 변경은 취소되지 않을 수 있어요.").setNegativeButton("계속", null).setPositiveButton("중지", (d,w) -> activity.startService(new Intent(activity, AssistantService.class).setAction(AssistantService.WORK_STOP).putExtra("thread", room))).show()));
            }
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(14); p.rightMargin = ui.dp(20); messages.addView(wrap, p);
    }
    private RichAnswer.Host host(JSONObject m) {
        return new RichAnswer.Host() {
            @Override public void copy(String text) { MainChatPage.this.copy(text); }
            @Override public void open(String url) { openLink(url); }
            @Override public void cite(String url) { openSource(url); }
            @Override public void expand(RichBlocks.Block block) {
                if (stopped) return;
                try { track(RichAnswer.viewer(activity, ui, block, this)).show(); } catch (RuntimeException unavailable) { error("이 기기에서 시각화를 열지 못했어요. 코드 복사로 내용을 확인할 수 있어요."); }
            }
            @Override public void menu() { messageMenu(m); }
            @Override public void suggest(String text) { suggested(text); }
            @Override public void picture(String id, ImageView into) { loadPicture(id, into); }
            @Override public void openPicture(String id) { if (!stopped) activity.openGalleryPicture(id); }
            @Override public void stopPicture(int index) { if (!stopped) try { store.mainAssistant.stopPicture(m.optLong("id"), index); } catch (Exception e) { error(e); } }
            @Override public void redraw(int index) { if (!stopped) try { store.mainAssistant.redraw(m.optLong("id"), index); } catch (Exception e) { error(e); } }
            @Override public void addEvent(int index) { if (!stopped) addEventAsked(m.optLong("id"), index); }
            @Override public void undoEvent(int index) { if (!stopped) try { store.mainAssistant.undoEvent(m.optLong("id"), index, MainChatPage.this::error); } catch (Exception e) { error(e); } }
        };
    }
    /** A drawn picture's private copy, decoded to about the screen width; a picture the gallery has let go shows a placeholder. */
    private void loadPicture(String id, ImageView into) {
        into.setTag(id); int want = Math.max(ui.dp(320), activity.getResources().getDisplayMetrics().widthPixels);
        try {
            pictureLoader.execute(() -> {
                java.io.File file = store.attachmentFile(id); android.graphics.Bitmap bitmap = null;
                try {
                    if (file.isFile()) {
                        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                        android.graphics.BitmapFactory.decodeFile(file.getPath(), bounds);
                        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); int sample = 1;
                        while (bounds.outWidth / (sample * 2) >= want) sample *= 2;
                        options.inSampleSize = sample; bitmap = android.graphics.BitmapFactory.decodeFile(file.getPath(), options);
                    }
                } catch (OutOfMemoryError tooLarge) { bitmap = null; }
                android.graphics.Bitmap shown = bitmap;
                into.post(() -> {
                    if (!id.equals(into.getTag())) return;
                    if (shown != null) { into.setImageBitmap(shown); return; }
                    into.setScaleType(ImageView.ScaleType.CENTER_INSIDE); into.setImageResource(R.drawable.ic_gallery);
                    into.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED)); into.setContentDescription("갤러리에서 정리된 그림이에요");
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {}
    }
    private void copy(String text) { ((android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("메시지", text)); Ui.done(activity.getWindow().getDecorView()); }
    /** Shares as plain text: Markdown's marks go, words and line breaks stay. */
    private void share(String text) {
        try { activity.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Markdown.plain(text)), "답변 공유")); } catch (Exception e) { error(e); }
    }
    /**
     * 출처: the pages the answer cited, as one small button under it (the first sites' marks overlapping, and how many
     * pages there are) that lists them in a sheet: each page's title, its number and its site. At most MainSearch.STRIP.
     * The titles are untrusted web text, shown as plain text. A tap on a page opens it in the app's browser.
     */
    private View sources(JSONArray pages) {
        List<JSONObject> list = new ArrayList<>(); for (int i = 0; i < pages.length(); i++) { JSONObject page = pages.optJSONObject(i); if (page != null) list.add(page); }
        LinearLayout button = ui.row(); button.setGravity(Gravity.CENTER_VERTICAL); button.setBackground(ui.shape(FILL, PILL)); button.setMinimumHeight(ui.dp(34));
        button.setPadding(ui.dp(5), ui.dp(5), ui.dp(13), ui.dp(5));
        for (int i = 0; i < Math.min(3, list.size()); i++) {
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)); if (i > 0) mp.leftMargin = -ui.dp(7);
            button.addView(mark(list.get(i).optString("site"), 22, FILL), mp);
        }
        TextView count = ui.text("출처 " + list.size(), FOOTNOTE, 600, MUTED); count.setPadding(ui.dp(7), 0, 0, 0); count.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); button.addView(count);
        button.setContentDescription("출처 " + list.size() + "개 보기"); button.setOnClickListener(v -> sourceList(list)); Ui.pressable(button, true);
        LinearLayout line = ui.row(); line.setPadding(0, ui.dp(8), 0, ui.dp(2)); line.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return line;
    }
    /** The cited pages in a sheet, each opening in the app's browser. */
    private void sourceList(List<JSONObject> pages) {
        if (stopped) return;
        Ui.Sheet sheet = ui.selectionSheet("출처", "답변이 참고한 페이지예요 · 누르면 앱 브라우저로 열려요"); sheet.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group();
        for (int i = 0; i < pages.size(); i++) {
            JSONObject page = pages.get(i); String uri = page.optString("uri"), site = page.optString("site"), title = page.optString("title");
            LinearLayout row = ui.row(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(ui.dp(62)); row.setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(10));
            row.addView(mark(site, 32, 0), new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));
            LinearLayout words = ui.column(); words.setPadding(ui.dp(12), 0, ui.dp(8), 0);
            TextView head = ui.text(title.isBlank() ? MainSearch.shortName(site) : title, CALLOUT, 560, TEXT); head.setMaxLines(2); head.setEllipsize(TextUtils.TruncateAt.END); words.addView(head);
            LinearLayout where = ui.row(); where.setPadding(0, ui.dp(2), 0, 0);
            TextView number = ui.text((i + 1) + " · ", FOOTNOTE, 500, MUTED); where.addView(number);
            // A long host is cut at its start, so its end, the site it really is, always shows ("…verification-center.example").
            TextView name = ui.text(site, FOOTNOTE, 450, MUTED); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.START); where.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            words.addView(where); row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView open = new ImageView(activity); open.setImageResource(R.drawable.ic_chevron); open.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED)); open.setAlpha(.6f);
            open.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(open, new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
            row.setContentDescription("출처 " + (i + 1) + " · " + site + (title.isBlank() ? "" : " · " + title) + " · 앱 브라우저로 열기");
            row.setOnClickListener(v -> { sheet.dismiss(); openSource(uri); }); Ui.pressable(row, false);
            ui.addRow(group, row, true);
        }
        sheet.body.addView(group); sheet.show();
    }
    private static final int[] MARKS = {0xff4f86e8, 0xff2e9e74, 0xffe08a3c, 0xff8e6ad8, 0xffdb5c8a, 0xff2f9fb3, 0xff6f7c8c};
    /** A site's mark: the first letter of its short name on a disc of its own color, ringed in ring when it overlaps another. */
    private TextView mark(String site, int size, int ring) {
        String name = MainSearch.shortName(site); String letter = name.isEmpty() ? "·" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
        TextView mark = ui.text(letter, 12, 700, 0xffffffff); mark.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, size * .45f);
        mark.setGravity(Gravity.CENTER); mark.setIncludeFontPadding(false); mark.setSingleLine(true); mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable(); disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        disc.setColor(MARKS[Math.floorMod(name.hashCode(), MARKS.length)]); if (ring != 0) disc.setStroke(ui.dp(2), ring);
        mark.setBackground(disc);
        return mark;
    }
    /**
     * Where an answer's Google search suggestions go: empty until the answer comes near the screen (nearSuggestions), then
     * the snippet's WebView, or its plain chips on a phone without one. Once made it stays with its answer.
     */
    private final class Suggestions extends FrameLayout {
        private final String html; private boolean asked, made;
        Suggestions(String html) { super(activity); this.html = html; }
        /** Made on the next turn of the main thread, never in the middle of a layout; a slot no longer shown by then is asked again later. */
        void ask() { if (!asked) { asked = true; post(this::make); } }
        void make() {
            if (made) return;
            if (stopped || !isAttachedToWindow()) { asked = false; return; }
            made = true;
            View shown = SearchSuggestions.view(activity, ui, html, MainChatPage.this::openSource);
            if (shown != null) addView(shown, new FrameLayout.LayoutParams(-1, -2));
        }
    }
    /**
     * Makes the suggestions of the answers on the screen or within a screen's height of it. A long Gemini conversation then
     * holds WebViews only for the grounded answers looked at, not one for each grounded answer of the forty on the page.
     */
    private void nearSuggestions() {
        if (stopped || messages == null || scroll.getHeight() == 0) return;
        int top = scroll.getScrollY() - scroll.getHeight(), bottom = scroll.getScrollY() + 2 * scroll.getHeight();
        for (View view : new ArrayList<>(answers.values())) {
            if (!(view instanceof Suggestions slot) || slot.asked || !slot.isLaidOut()) continue;
            int y = 0; View at = slot;
            while (at != null && at != messages) { y += at.getTop(); at = at.getParent() instanceof View parent ? parent : null; }
            if (at == messages && y + slot.getHeight() >= top && y <= bottom) slot.ask();
        }
    }
    /** A cited page or a Google search suggestion opens in the app's browser: an http or https address only (BrowserTools.url). */
    private void openSource(String url) {
        if (stopped) return;
        try { BrowserActivity.open(activity, url); }
        catch (IllegalArgumentException invalid) { error("이 주소는 열 수 없어요."); }
        catch (Exception unavailable) { error("앱 브라우저를 열지 못했어요."); }
    }
    /** A link the user tapped opens outside the app; only web addresses. */
    private void openLink(String url) {
        if (stopped || url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); } catch (Exception e) { error("링크를 열 앱을 찾지 못했어요."); }
    }
    /** Sends the original question again as a new turn; if that cannot start, it waits in the composer instead. */
    private void resend(long original) {
        JSONObject source = store.mainChat.message(original); if (source == null) return;
        String text = source.optString("text");
        try { store.mainAssistant.enqueue(text); latest(); }
        catch (Exception problem) { input.setText(text); input.setSelection(input.length()); error(problem); }
    }
    static boolean suggestsWork(String text) {
        if (text == null) return false; String lower = text.toLowerCase(Locale.ROOT);
        // Pictures are drawn right here in the chat now; only research or file work around them still suggests Work.
        if (lower.matches("(?s).*(그림|이미지|사진|일러스트|포스터|배경화면|로고|그려).*") && !lower.matches("(?s).*(조사|검색|브라우저|파일|코딩|github).*")) return false;
        return lower.matches("(?s).*(조사|검색|브라우저|파일|코딩|구현|개발|github|자료.*모아|비교.*줘|만들어|작업.*해).*");
    }
    /** A message's actions as a short sheet: copy and share first, edits for the user's own, delete set apart in red. */
    private void messageMenu(JSONObject m) {
        if (stopped) return;
        String role = m.optString("role"); boolean mine = role.equals("user"), done = role.equals("assistant") && m.optString("state").equals("done");
        // An answer is copied and shared without the requests it suggests.
        String text = done ? RichBlocks.withoutSuggestions(m.optString("text")) : m.optString("text");
        String preview = text.strip().replace('\n', ' '); if (preview.length() > 90) preview = preview.substring(0, 90) + "…";
        Ui.Sheet sheet = menuSheet(mine ? "내 메시지" : role.equals("memory") ? "기억 알림" : role.equals("job") ? "작업 기록" : "답변", preview);
        if (sheet.subheading != null) { sheet.subheading.setMaxLines(2); sheet.subheading.setEllipsize(TextUtils.TruncateAt.END); }
        LinearLayout group = ui.group();
        item(group, sheet, R.drawable.ic_copy, BLUE, "복사", done ? "마크다운 원문 그대로" : null, false, () -> copy(done ? Markdown.citations(text) : text));
        if (done) item(group, sheet, R.drawable.ic_share, GREEN, "공유", "기호 없이 읽기 좋은 글로", false, () -> share(text));
        // The answer without Markdown's marks, in one block with a copy button: for pasting where Markdown is not drawn.
        if (done) item(group, sheet, R.drawable.ic_file, TEAL, "텍스트로 보기", "기호 없는 글 · 복사해서 메신저에 붙여 넣기 좋아요", false, () -> PlainTextSheet.answer(activity, text));
        // 판서: the same content taught on the board, written step by step and read aloud.
        if (done || mine) item(group, sheet, R.drawable.ic_board, INDIGO, "판서로 설명 듣기", "판에 써 가며 차근차근 설명해요", false, () -> { if (!stopped) BoardActivity.teach(activity, mine ? text : question(m), mine ? "" : text); });
        // 페이지: the answer as a document in a category the user picks.
        if (done) item(group, sheet, R.drawable.ic_pages, BLUE, "페이지에 저장", "카테고리를 골라 문서로 보관해요", false, () -> { if (!stopped) PageSaveSheet.show(activity, ui, PageSaveSheet.document(text), dev.jun.gyeot.core.PageBook.CHAT, modelLabel(m), d -> track(d)); });
        if (done) { boolean reading = store.speech != null && store.speech.reading(readKey(m));
            item(group, sheet, R.drawable.ic_sound, TEAL, reading ? "읽기 멈추기" : "읽어 주기", reading ? "지금 읽는 답변을 멈춰요" : "소리 내어 읽어요 · 다시 누르면 멈춰요", false, () -> readAloud(m)); }
        // The transcript read for the video this message (or the question this answer is for) named, to read by itself.
        String asked = mine ? text : done ? question(m) : "";
        if (YouTubeTranscriptUi.has(activity, asked)) item(group, sheet, R.drawable.ic_file, INDIGO, "유튜브 대본 보기", "가져온 대본을 따로 읽어요", false, () -> YouTubeTranscriptUi.show(activity, asked));
        if (YouTubeTranscriptUi.hasPage(activity, asked)) item(group, sheet, R.drawable.ic_file, INDIGO, "가져온 글 보기", "앱이 읽어 온 글을 따로 읽어요", false, () -> YouTubeTranscriptUi.showPage(activity, asked));
        if (mine) {
            item(group, sheet, R.drawable.ic_tune, ORANGE, "수정", "연결 답변과 요약을 다시 정리해요", false, () -> editMessage(m));
            item(group, sheet, R.drawable.ic_heart, PINK, "기억으로 저장", "이 대화에 고정해 함께 보내요", false, () -> editNote(null, text, m.optLong("id")));
            item(group, sheet, R.drawable.ic_layers, INDIGO, "작업으로 보내기", "확인한 요청만 새 작업창에 전달해요", false, () -> confirmJob(m.optLong("id"), text));
        }
        sheet.body.addView(group);
        LinearLayout danger = ui.group(); danger(item(danger, sheet, R.drawable.ic_close, RED, "삭제", null, false, () -> deleteMessage(m)));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(12); sheet.body.addView(danger, gap);
        if (done) {
            // 이 답의 정보: when and with which model, and what the turn took where the provider counted it.
            String model = modelLabel(m), spent = Usage.foot(m.optString("usage"));
            sheet.body.addView(ui.sectionHeader("이 답의 정보"));
            TextView info = ui.text(stamp(m) + (model.isBlank() ? "" : " · " + model) + (spent.isEmpty() ? "" : "\n" + spent), FOOTNOTE, 450, MUTED);
            info.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(4)); info.setLineSpacing(ui.dp(4), 1); info.setTextIsSelectable(true); sheet.body.addView(info);
        }
        sheet.show();
    }
    /** The question an answer is for, or "". */
    private String question(JSONObject answer) {
        try { JSONObject asked = store.mainChat.message(answer.optLong("reply_to")); return asked == null ? "" : asked.optString("text"); } catch (RuntimeException gone) { return ""; }
    }
    private void deleteMessage(JSONObject m) {
        // A memory notice is not conversation: removing it leaves the summary and running replies alone.
        boolean notice = m.optString("role").equals("memory");
        dialog().setMessage(notice ? "이 알림만 지울까요? 저장된 기억은 그대로이며, 기억은 기억·제약 관리나 설정 → 기억에서 지워요." : "이 메시지·연결 답변·연결 기억을 삭제하고 요약을 비울까요? 진행 중인 답변은 중단돼요. 자동 기억에 들어간 내용과 이미 전달한 작업창 기록·원격 결과는 별개예요.").setNegativeButton("취소", null).setPositiveButton("삭제", (a,b) -> {
            try { List<String> files = ids(MainAttachments.of(m)); store.mainChat.delete(m.optLong("id")); store.releaseMainAttachments(files); if (!notice) store.mainAssistant.contextChanged(); rendered = ""; refresh(); } catch (Exception e) { error(e); }
        }).show();
    }
    private static List<String> ids(JSONArray files) { List<String> ids = new ArrayList<>(); for (int i = 0; i < files.length(); i++) ids.add(files.optJSONObject(i).optString("id")); return ids; }
    private EditText field(String text) { EditText e = new EditText(activity); e.setText(text); e.setTextSize(16); e.setMinLines(2); e.setMaxLines(8); e.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12)); return e; }
    private void editMessage(JSONObject m) {
        EditText edit = field(m.optString("text")); edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12000)});
        dialog().setTitle("메시지 수정").setMessage("연결 기억과 요약을 지우고 이전 답변을 자동 문맥에서 제외해요. 이전 답변 원문과 작업창 지시는 별도로 관리해 주세요.").setView(edit).setNegativeButton("취소", null).setPositiveButton("수정", (d,w) -> {
            try { store.mainChat.edit(m.optLong("id"), edit.getText().toString()); store.mainAssistant.contextChanged(); rendered = ""; refresh(); } catch (Exception e) { error(e); }
        }).show();
    }
    private void confirmJob(long source, String request) {
        if (!historyReady()) return; EditText edit = field(request); edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12000)});
        dialog().setTitle("작업창에 맡기기").setMessage("아래 요청과 메인 모델 선택만 새 작업창에 전달해요. 전체 대화·기억·프로필 사진·기존 첨부는 보내지 않아요. 필요한 조건은 요청에 적어 주세요. 파일 내용 읽기·쓰기는 지정한 들퍼레 안으로 제한돼요. 메인에서 이미지 생성·편집을 맡기는 기능은 아직 지원하지 않아요.").setView(edit).setNegativeButton("취소", null).setPositiveButton("작업 보내기", (d,w) -> {
            try { store.mainAssistant.delegate(source, edit.getText().toString()); latest(); } catch (Exception e) { error(e); }
        }).show();
    }
    // ── Menus: grouped sheets with icons, sections and switches, as in the ChatGPT, Claude, Gemini and Grok apps ──
    /** A tracked sheet without the bottom button: rows act, the header's close button (or a swipe) closes it. */
    private Ui.Sheet menuSheet(String title, String subtitle) { Ui.Sheet sheet = track(ui.selectionSheet(title, subtitle)); sheet.primary.setVisibility(View.GONE); return sheet; }
    /** One row in a group; the sheet closes first so whatever the row opens stands on its own. */
    private LinearLayout item(LinearLayout group, Dialog sheet, int icon, int tint, String title, String subtitle, boolean more, Runnable action) {
        LinearLayout row = ui.listRow(icon, tint, title, subtitle, more ? ui.chevron() : null, () -> { if (sheet != null) sheet.dismiss(); action.run(); });
        TextView name = ((Ui.ListRow) row).parts.title(); name.setMaxLines(3); name.setEllipsize(TextUtils.TruncateAt.END);
        ui.addRow(group, row, icon != 0); return row;
    }
    private static void danger(LinearLayout row) { ((Ui.ListRow) row).parts.title().setTextColor(ERROR); }
    /** A row with a switch: a tap anywhere flips it and the setting is stored at once; a failed save flips it back. */
    private void switchRow(LinearLayout group, int icon, int tint, String title, String subtitle, String key, boolean resets) { switchRow(group, icon, tint, title, subtitle, key, resets, true); }
    private void switchRow(LinearLayout group, int icon, int tint, String title, String subtitle, String key, boolean resets, boolean byDefault) {
        Switch toggle = ui.toggle("", store.settings.getBoolean(key, byDefault), value -> {}); toggle.setText(null); toggle.setPadding(0, 0, 0, 0); toggle.setContentDescription(title);
        boolean[] quiet = {false};
        toggle.setOnCheckedChangeListener((button, value) -> { if (quiet[0]) return; if (!setSwitch(key, value, resets)) { quiet[0] = true; button.setChecked(!value); quiet[0] = false; } });
        LinearLayout row = ui.listRow(icon, tint, title, subtitle, toggle, toggle::toggle);
        TextView detail = ((Ui.ListRow) row).parts.subtitle(); if (detail != null) { detail.setSingleLine(false); detail.setMaxLines(2); }
        ui.addRow(group, row, true);
    }
    /** Memory and summary switches reset derived context as before; the automatic-memory switch only affects later passes. */
    private boolean setSwitch(String key, boolean value, boolean resets) {
        try {
            if (!store.settings.edit().putBoolean(key, value).commit()) throw new IllegalStateException("설정을 저장하지 못했어요.");
            if (resets && store.mainChat.available()) { store.mainChat.invalidateContext(); store.mainAssistant.contextChanged(); }
            return true;
        } catch (Exception e) { error(e); return false; }
    }
    /** "max" sends what the model's window holds; back to the maximum also forgets smaller windows learned from earlier refusals. */
    private void contextSize(boolean saver) {
        android.content.SharedPreferences.Editor edit = store.settings.edit().putString("mainContext", saver ? "saver" : "max");
        Map<String, ?> stored = store.settings.getAll();
        if (!saver) for (String key : stored.keySet()) if (key.startsWith("mainContextLimit.")) edit.remove(key);
        edit.apply();
    }
    private int learnedMemories() { int count = 0; JSONArray all = store.memories(); for (int i = 0; i < all.length(); i++) { JSONObject note = all.optJSONObject(i); if (note != null && dev.jun.gyeot.core.Memory.ABOUT.equals(note.optString("kind"))) count++; } return count; }
    /** The model line used in menus: provider · model · level. */
    private String modelLine() {
        ScopedModels models = ScopedModels.main(store); String engine = models.engine(), model = models.model(engine);
        if (models.readyEngine().isBlank()) return "연결하고 고르기";
        String level = model.isBlank() ? "" : models.effort(engine, model);
        return WorkBook.author(engine) + " · " + new TeamRoster(store, models).label(engine, model) + (level.isEmpty() ? "" : " · " + level(engine, level));
    }
    /**
     * The ⋯ menu: the assistant's profile on top, then 대화 (model and level, search, jobs, tools), 기억·개인화 (switches and
     * saved memory) and 기록·데이터 (how much is sent, how it is kept, clearing), each as a grouped list.
     */
    private void menu() {
        if (stopped) return;
        if (!store.mainChat.available()) { tools(); return; }
        try {
            Ui.Sheet sheet = menuSheet("대화 설정", "메인 대화 · 작업창과 별도");
            LinearLayout card = ui.group(), who = ui.row(); who.setPadding(ui.dp(16), ui.dp(12), ui.dp(14), ui.dp(12)); who.setMinimumHeight(ui.dp(68));
            ImageView face = new ImageView(activity); AssistantProfile faceProfile = new AssistantProfile(activity); faceProfile.bind(face);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)); fp.rightMargin = ui.dp(14); who.addView(face, fp);
            LinearLayout words = ui.column(); words.addView(ui.text(profile.name(), HEADLINE, 700, TEXT));
            TextView about = ui.text("이름·프로필 사진 · GPT 이미지로 만들기", FOOTNOTE, 450, MUTED); about.setPadding(0, ui.dp(3), 0, 0); words.addView(about);
            who.addView(words, new LinearLayout.LayoutParams(0, -2, 1)); who.addView(ui.chevron());
            who.setContentDescription("프로필 설정 · " + profile.name() + " · 이름과 프로필 사진, GPT 이미지로 만들기");
            who.setOnClickListener(v -> { sheet.dismiss(); editProfile(); }); Ui.pressable(who, true); card.addView(who); sheet.body.addView(card);

            sheet.body.addView(ui.sectionHeader("대화"));
            LinearLayout talk = ui.group(); int jobCount = store.mainChat.jobs(false).length();
            item(talk, sheet, R.drawable.ic_sparkle, ACCENT, "모델·추론 레벨", modelLine(), true, this::models);
            item(talk, sheet, R.drawable.ic_search, BLUE, "대화 검색", query.isBlank() ? "지난 원문에서 단어로 찾기" : "검색 중 · " + query, true, this::search);
            item(talk, sheet, R.drawable.ic_search, TEAL, "전체 검색", "작업창·페이지·기억·메모·기록까지 한 번에 찾기", true, () -> SearchActivity.open(activity, query));
            item(talk, sheet, R.drawable.ic_layers, INDIGO, "맡긴 작업", jobCount == 0 ? "아직 없어요" : jobCount + "개 · 상태 보기·작업창 열기", true, this::jobs);
            switchRow(talk, R.drawable.ic_sound, MINT, "답변 자동 읽기", store.geminiSpeech() && store.gemini.hasKey() ? "Gemini 음성 · API 요금이 붙을 수 있어요 · 오프라인이면 기기 음성" : "새 답변이 오면 기기 음성으로 읽어요", Speech.AUTO_READ, false, false);
            sheet.body.addView(talk);

            sheet.body.addView(ui.sectionHeader("이미지"));
            LinearLayout images = ui.group(); boolean connected = !AssistantProfileUi.route(store).isEmpty();
            switchRow(images, R.drawable.ic_gallery, PINK, "이미지 만들기", connected ? "대화에서 GPT 이미지로 그리고 갤러리·들퍼레 폴더에 저장해요" : "연결이 필요해요 · 설정 → ChatGPT에서 이미지용 Codex 계정이나 API 키", MainChatPictures.SETTING, false);
            item(images, sheet, R.drawable.ic_layers, MINT, "갤러리 열기", "만든 그림 모아 보기·공유·편집", true, () -> openMain(4));
            sheet.body.addView(images);

            sheet.body.addView(ui.sectionHeader("기억·개인화"));
            LinearLayout mind = ui.group();
            switchRow(mind, R.drawable.ic_book, PURPLE, "기억 사용", store.memoryOn() ? "지난 대화 검색·요약·기억을 답변에 써요" : "설정 → 기억에서 장기 기억이 꺼져 있어 쓰지 않아요", "mainMemoryOn", true);
            switchRow(mind, R.drawable.ic_history, BLUE, "자동 요약", "오래된 대화를 요약해 이어 가요 · 사용량이 들 수 있어요", "mainSummaryOn", true);
            switchRow(mind, R.drawable.ic_bulb, ORANGE, "자동 기억", store.autoMemory() ? "오래 쓸 만한 사실을 스스로 기억해요" : "설정의 자동 기억이 꺼져 있어 쓰지 않아요", "mainAutoMemory", false);
            item(mind, sheet, R.drawable.ic_heart, PINK, "저장된 기억·제약", "고정 " + store.mainChat.notes().length() + "개 · 자동 기억 " + learnedMemories() + "개", true, this::memories);
            sheet.body.addView(mind);

            sheet.body.addView(ui.sectionHeader("기록·데이터"));
            LinearLayout data = ui.group(), size = ui.column(); size.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
            boolean saver = "saver".equals(store.settings.getString("mainContext", "max"));
            size.addView(ui.text("보내는 대화 분량", BODY, 520, TEXT));
            TextView sizeNote = ui.text("모델 최대: 모델이 받을 수 있는 만큼 원문을 보내요. 절약: 약 6만 자까지만 보내 사용량을 줄여요.", FOOTNOTE, 450, MUTED); sizeNote.setPadding(0, ui.dp(3), 0, ui.dp(10)); size.addView(sizeNote);
            size.addView(ui.segmented(new String[]{"모델 최대", "절약"}, saver ? 1 : 0, index -> contextSize(index == 1)), new LinearLayout.LayoutParams(-1, -2));
            ui.addRow(data, size, false);
            item(data, sheet, R.drawable.ic_lock, GRAY, "대화·기억이 보관되는 방식", "이 기기에만 저장 · 모델로 보내는 범위", true, this::privacy);
            danger(item(data, sheet, R.drawable.ic_close, RED, "대화 기록 삭제", "원문·검색·요약·고정 기억을 지워요", false, this::clearHistory));
            sheet.body.addView(data);
            sheet.body.addView(ui.footer("자동 기억과 ‘나에 대해’는 음성 비서·작업창과 함께 써요. 설정 → 기억에서도 고치거나 지울 수 있어요."));
            sheet.setOnDismissListener(d -> faceProfile.close());
            sheet.show();
        } catch (Exception e) { error(e); }
    }
    /** The + menu: this chat's own tools for the message being written: attach files, draw a picture, send the draft as a job. */
    private void tools() {
        if (stopped) return;
        // What to do with the message being written. Going to another screen is the app's menu (the rail, or ≡ on a phone);
        // which services the model may use is 설정 → 커넥터's, never a switch here.
        Ui.Sheet sheet = menuSheet("대화 도구", "이 대화에서 쓸 기능이에요");
        LinearLayout first = ui.group(); boolean draft = !input.getText().toString().isBlank();
        int picked = store.mainFiles.length();
        item(first, sheet, R.drawable.ic_file, BLUE, "사진·파일 첨부", picked > 0 ? "첨부 " + picked + "개 · 최대 " + MainAttachments.COUNT + "개까지 함께 보내요" : "사진·PDF·글 파일을 골라 이 메시지와 함께 보내요", false, activity::pickMainFiles);
        item(first, sheet, R.drawable.ic_gallery, PINK, "이미지 만들기", "그릴 내용을 적어 보내면 GPT 이미지로 그려요", false, this::startPicture);
        item(first, sheet, R.drawable.ic_layers, ACCENT, "입력한 내용을 작업으로 보내기", draft ? "확인한 요청만 새 작업창에 전달해요" : "입력창에 요청을 적은 뒤 눌러요", false, () -> confirmJob(0, input.getText().toString()));
        sheet.body.addView(first);
        sheet.show();
    }
    /** Starts an image request in the composer: "그려 줘: " before whatever is already typed, keyboard up. */
    private void startPicture() {
        if (stopped) return; String typed = input.getText().toString();
        if (!typed.startsWith("그려 줘: ")) input.setText("그려 줘: " + typed);
        input.setSelection(input.length()); input.requestFocus();
        android.view.inputmethod.InputMethodManager keyboard = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.showSoftInput(input, 0);
    }
    private void openMain(int tab) { activity.openMainContent(tab,null); }
    private void openWork(String room) { activity.openMainContent(3,room); }
    private void search() {
        if (!historyReady()) return; EditText edit = field(query); edit.setHint("기억나는 단어");
        dialog().setTitle("전체 원문 검색").setMessage("단어 앞부분으로 검색해 40개씩 보여줘요. 의미가 비슷한 문장을 찾는 검색은 아니에요.").setView(edit).setNegativeButton("취소", null).setPositiveButton("검색", (d,w) -> { query = edit.getText().toString().strip(); before = 0; rendered = ""; refresh(); postScroll(() -> scroll.scrollTo(0,0)); }).show();
    }
    static String jobState(String state) { return switch (state) { case "queued", "dispatching" -> "전달 중"; case "running" -> "작업창에서 진행 중"; case "done" -> "답변 도착"; case "error", "not_started" -> "오류"; default -> "확인 필요"; }; }
    /** Jobs sent from this chat, newest first, with their state; a tap opens the Work room. */
    private void jobs() {
        if (stopped || !historyReady()) return;
        try {
            JSONArray jobs = store.mainChat.jobs(false);
            Ui.Sheet sheet = menuSheet("맡긴 작업", "메인 대화에서 작업창으로 보낸 요청");
            if (jobs.length() == 0) { sheet.body.addView(ui.footer("아직 맡긴 작업이 없어요. 입력창의 + → ‘입력한 내용을 작업으로 보내기’로 맡길 수 있어요.")); sheet.show(); return; }
            LinearLayout group = ui.group();
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject j = jobs.optJSONObject(i); String request = j.optString("request");
                if (request.isBlank()) { JSONObject source = store.mainChat.message(j.optLong("source")); if (source != null) request = source.optString("text"); }
                String room = j.optString("room");
                item(group, sheet, 0, 0, request.length() > 90 ? request.substring(0, 90) + "…" : request, jobState(j.optString("state")) + (room.isBlank() ? "" : " · 작업창 열기"), !room.isBlank(),
                    () -> { if (!room.isBlank()) openWork(room); else error("전달 여부 확인이 필요해요. 자동 재실행하지 않아요."); });
            }
            sheet.body.addView(group); sheet.show();
        } catch (Exception e) { error(e); }
    }
    /** The picker from the header or menu; as before, only this entry asks for a catalog refresh when one is due. */
    private void models() {
        try { if (store.openai.connected()) store.openai.refreshModelsIfDue(); } catch (Exception e) { error(e); return; }
        showModels("");
    }
    /**
     * One compact sheet, as in the Claude and ChatGPT apps: connected providers as capsules, the provider's models with the
     * current one checked, and a single tap to switch. A confirmation appears only when switching would stop replies that
     * are still being prepared. Refresh, diagnostics and connection settings stay one tap away below the list.
     */
    private void showModels(String engine) {
        if (stopped) return;
        try {
            ScopedModels scoped = ScopedModels.main(store); TeamRoster roster = new TeamRoster(store, scoped);
            ArrayList<String> engines = new ArrayList<>(); for (String e : TeamPlan.ENGINES) if (store.ready(e)) engines.add(e);
            String active = scoped.engine();
            String[] tab = {!engine.isBlank() && engines.contains(engine) ? engine : engines.contains(active) ? active : engines.isEmpty() ? "" : engines.get(0)};
            Ui.Sheet sheet = track(ui.selectionSheet("모델 선택", "메인 대화 · 작업창과 별도"));
            sheet.primary.setVisibility(View.GONE);
            if (engines.isEmpty()) {
                TextView none = ui.text("아직 연결된 AI가 없어요. 연결하면 여기서 바로 고를 수 있어요.", CALLOUT, 450, MUTED); none.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(12)); sheet.body.addView(none);
                sheet.body.addView(ui.button("연결 설정 열기", true, () -> { sheet.dismiss(); openMain(2); }));
                modelPicker = sheet; sheet.setOnDismissListener(d -> { if (modelPicker == sheet) { modelPicker = null; modelPickerRefresh = null; } }); sheet.show(); return;
            }
            final String[] drawn = {""}; final Runnable[] redraw = new Runnable[1];
            if (engines.size() > 1) {
                String[] names = new String[engines.size()]; for (int i = 0; i < names.length; i++) names[i] = WorkBook.author(engines.get(i));
                sheet.controls.addView(ui.pills(names, engines.indexOf(tab[0]), index -> { tab[0] = engines.get(index); redraw[0].run(); }));
            }
            LinearLayout reasoning = ui.column(); sheet.controls.addView(reasoning);
            LinearLayout list = ui.group(); sheet.body.addView(list);
            sheet.body.addView(ui.footer("고른 모델에는 지난 대화 원문·기억·요약을 그 모델이 받을 수 있는 최대 분량까지 보내요. 모델을 바꾸면 준비 중인 답변은 멈춰요. 프로필 사진은 보내지 않아요."));
            LinearLayout actions = ui.row(); actions.setGravity(Gravity.CENTER); sheet.footer.addView(actions, new LinearLayout.LayoutParams(-1, -2));
            redraw[0] = () -> {
                if (stopped || activity.isFinishing() || activity.isDestroyed() || modelPicker != sheet) return;
                String current = tab[0]; JSONArray catalog = roster.catalog(current); String chosen = scoped.model(current); boolean on = current.equals(scoped.engine());
                String status = WorkBook.CHATGPT.equals(current) ? store.openai.modelsStatus() : "";
                String effort = on && !chosen.isBlank() ? scoped.effort(current, chosen) : "";
                String key = current + "\n" + catalog + "\n" + chosen + "\n" + on + "\n" + effort + "\n" + status; if (drawn[0].equals(key)) return; drawn[0] = key;
                reasoning(reasoning, scoped, roster, current, on ? chosen : "", () -> { drawn[0] = ""; redraw[0].run(); });
                if (sheet.subheading != null) sheet.subheading.setText("메인 대화 · 작업창과 별도" + (status.isBlank() ? "" : "\n" + status));
                list.removeAllViews(); ArrayList<String> ids = new ArrayList<>();
                for (int i = 0; i < catalog.length(); i++) {
                    JSONObject item = catalog.optJSONObject(i); if (item == null || item.optString("id").isBlank()) continue;
                    String id = item.optString("id"); ids.add(id);
                    String label = item.optString("name", id), note = item.optString("description").strip();
                    if (note.isBlank() && !label.equals(id)) note = id;
                    String saved = scoped.effort(current, id); if (!saved.isEmpty()) note += (note.isBlank() ? "" : " · ") + "추론 " + level(current, saved);
                    modelRow(list, sheet, scoped, current, id, label, note, on && id.equals(chosen));
                }
                if (!chosen.isBlank() && !ids.contains(chosen)) modelRow(list, sheet, scoped, current, chosen, chosen, "현재 목록에 없음 · 연결 확인 필요", on);
                if (ids.isEmpty() && chosen.isBlank()) { TextView empty = ui.text("모델 목록을 아직 받지 못했어요.", CALLOUT, 450, MUTED); empty.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); list.addView(empty); }
                actions.removeAllViews();
                if (WorkBook.CHATGPT.equals(current)) {
                    actions.addView(action("목록 새로고침", () -> { store.openai.refreshModelsAsync(); drawn[0] = ""; redraw[0].run(); }));
                    actions.addView(action("모델 목록 진단", () -> { sheet.dismiss(); postScroll(this::showModelDiagnostics); }));
                }
                actions.addView(action("연결 설정", () -> { sheet.dismiss(); openMain(2); }));
                actions.addView(action("전송 안내", this::privacy));
            };
            modelPicker = sheet; modelPickerRefresh = redraw[0];
            sheet.setOnDismissListener(d -> { if (modelPicker == sheet) { modelPicker = null; modelPickerRefresh = null; } });
            redraw[0].run(); sheet.show();
        } catch (Exception e) { error(e); }
    }
    private TextView action(String label, Runnable run) { TextView view = ui.ghost(label, run); view.setMinHeight(ui.dp(44)); return view; }
    /** A reasoning level as the Work picker names it: GPT's Light/Medium/…, the others' Korean depth names. */
    static String level(String engine, String value) { return WorkBook.CHATGPT.equals(engine) ? Reasoning.gptShortLabel(value) : MainActivity.effortName(value); }
    /**
     * The reasoning level of the model in use, as capsules under the providers; each model keeps its own level (the same
     * per-model store the Work picker uses). Models without levels say so in one line; another provider's tab shows none.
     */
    private void reasoning(LinearLayout box, ScopedModels scoped, TeamRoster roster, String engine, String model, Runnable redraw) {
        box.removeAllViews(); if (model.isBlank()) return;
        Reasoning.Profile profile = roster.profile(engine, model);
        String name = roster.label(engine, model), title = WorkBook.CHATGPT.equals(engine) ? "추론 레벨" : "생각 깊이";
        TextView heading = ui.text(title + " · " + name, FOOTNOTE, 620, MUTED); heading.setSingleLine(true); heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(6)); heading.setAccessibilityHeading(true); box.addView(heading);
        if (profile.efforts.isEmpty()) { TextView none = ui.text("이 모델은 레벨을 고를 수 없어 기본값으로 답해요.", FOOTNOTE, 450, MUTED); none.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4)); box.addView(none); return; }
        String[] values = profile.values(), names = new String[values.length]; String saved = scoped.effort(engine, model); int chosen = 0;
        for (int i = 0; i < values.length; i++) {
            names[i] = i == 0 ? "기본" + (profile.defaultEffort.isEmpty() ? "" : " · " + level(engine, profile.defaultEffort)) : level(engine, values[i]);
            if (values[i].equals(saved)) chosen = i;
        }
        box.addView(ui.pills(names, chosen, index -> setLevel(scoped, engine, model, values[index], redraw)));
    }
    /** Stores one model's level; like a model change it stops replies bound to the old setting, so it asks first only then. */
    private void setLevel(ScopedModels scoped, String engine, String model, String value, Runnable redraw) {
        if (value.equals(scoped.effort(engine, model))) return;
        int pending = 0; try { if (store.mainChat.available()) pending = store.mainChat.pendingCount(); } catch (Exception ignored) {}
        Runnable apply = () -> { try { scoped.effort(engine, model, value); } catch (Exception e) { error(e); } redraw.run(); refresh(); };
        if (pending == 0) { apply.run(); return; }
        dialog().setTitle("레벨을 바꿀까요?").setMessage("준비 중인 답변 " + pending + "개가 멈춰요. 이미 보낸 요청을 새 설정으로 다시 보내지는 않아요.")
            .setNegativeButton("취소", (d, w) -> redraw.run()).setPositiveButton("바꾸기", (d, w) -> apply.run()).setOnCancelListener(d -> redraw.run()).show();
    }
    /** One model in the picker: its name, a short note, and a check on the one in use; a tap switches to it. */
    private void modelRow(LinearLayout list, Dialog sheet, ScopedModels scoped, String engine, String id, String label, String note, boolean current) {
        ImageView check = new ImageView(activity); check.setImageResource(R.drawable.ic_check); check.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        check.setVisibility(current ? View.VISIBLE : View.INVISIBLE); check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        check.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        LinearLayout row = ui.listRow(0, 0, label, note, check, () -> choose(sheet, scoped, engine, id));
        row.setMinimumHeight(ui.dp(52)); row.setPadding(ui.dp(16), ui.dp(9), ui.dp(14), ui.dp(9));
        if (current) { row.setContentDescription(label + (note.isBlank() ? "" : ", " + note) + ", 사용 중"); TextView title = ((Ui.ListRow) row).parts.title(); title.setTextColor(ACCENT); title.setTypeface(Ui.weight(680)); }
        ui.addRow(list, row, false);
    }
    /** Switches the main model; asks first only when replies being prepared would stop. */
    private void choose(Dialog sheet, ScopedModels scoped, String engine, String model) {
        if (engine.equals(scoped.engine()) && model.equals(scoped.model(engine))) { sheet.dismiss(); return; }
        int pending = 0; try { if (store.mainChat.available()) pending = store.mainChat.pendingCount(); } catch (Exception ignored) {}
        Runnable apply = () -> { try { scoped.model(engine, model); scoped.chooseEngine(engine); sheet.dismiss(); refresh(); } catch (Exception e) { error(e); } };
        if (pending == 0) { apply.run(); return; }
        dialog().setTitle("모델을 바꿀까요?").setMessage("준비 중인 답변 " + pending + "개가 멈춰요. 이미 보낸 요청을 새 모델로 다시 보내지는 않아요.").setNegativeButton("취소", null).setPositiveButton("바꾸기", (d, w) -> apply.run()).show();
    }
    private void showModelDiagnostics(){
        if(stopped||activity.isFinishing()||activity.isDestroyed())return;
        Dialog result=GptCatalogDiagnosticsUi.show(activity,store.openai);
        if(result!=null){dialogs.removeIf(d->!d.isShowing());dialogs.add(result);}
    }
    /** Saved memory as a sheet: notes pinned in this chat, what automatic memory learned (shared with voice), and the switches. */
    private void memories() {
        if (stopped || !historyReady()) return;
        try {
            JSONArray notes = store.mainChat.notes(), all = store.memories();
            Ui.Sheet sheet = menuSheet("기억·제약", "답할 때 함께 보내는 기억이에요 · 눌러서 고치거나 지워요");
            sheet.body.addView(ui.button("기억·제약 추가", false, () -> { sheet.dismiss(); editNote(null, "", 0); }));
            sheet.body.addView(ui.sectionHeader("고정한 기억·제약 · 음성·작업창에도 함께"));
            if (notes.length() == 0) sheet.body.addView(ui.footer("아직 없어요. 메시지를 길게 눌러 ‘기억으로 저장’하거나 위에서 추가해요."));
            else { LinearLayout group = ui.group(); for (int i = 0; i < notes.length(); i++) { JSONObject note = notes.optJSONObject(i); if (note != null) item(group, sheet, 0, 0, note.optString("text"), note.optString("kind"), true, () -> pinned(note)); } sheet.body.addView(group); }
            sheet.body.addView(ui.sectionHeader("자동 기억 · 음성 비서·작업창과 함께"));
            LinearLayout learned = ui.group();
            for (int i = all.length() - 1; i >= 0; i--) {
                JSONObject note = all.optJSONObject(i);
                if (note != null && dev.jun.gyeot.core.Memory.ABOUT.equals(note.optString("kind"))) item(learned, sheet, 0, 0, note.optString("text"), null, true, () -> longTerm(note));
            }
            if (learned.getChildCount() == 0) sheet.body.addView(ui.footer(store.autoMemory() ? "아직 없어요. 대화가 몇 번 오가거나 ‘기억해’라고 하면 여기에 쌓여요." : "설정의 자동 기억이 꺼져 있어요."));
            else sheet.body.addView(learned);
            sheet.body.addView(ui.sectionHeader("설정"));
            LinearLayout group = ui.group();
            switchRow(group, R.drawable.ic_book, PURPLE, "기억 사용", store.memoryOn() ? "지난 대화 검색·요약·기억을 답변에 써요" : "설정 → 기억에서 장기 기억이 꺼져 있어 쓰지 않아요", "mainMemoryOn", true);
            switchRow(group, R.drawable.ic_history, BLUE, "자동 요약", "오래된 대화를 요약해 이어 가요", "mainSummaryOn", true);
            switchRow(group, R.drawable.ic_bulb, ORANGE, "자동 기억", "오래 쓸 만한 사실을 스스로 기억해요", "mainAutoMemory", false);
            sheet.body.addView(group);
            sheet.footer.addView(action("보관·전송 안내", () -> { sheet.dismiss(); privacy(); }), new LinearLayout.LayoutParams(-1, -2));
            sheet.show();
        } catch(Exception e) {error(e);}
    }
    private static String preview(String text) { String t = text == null ? "" : text.strip().replace('\n', ' '); return t.length() > 90 ? t.substring(0, 90) + "…" : t; }
    private void pinned(JSONObject note) {
        Ui.Sheet sheet = menuSheet(note.optString("kind") + " · 고정한 기억", preview(note.optString("text")));
        LinearLayout group = ui.group();
        item(group, sheet, R.drawable.ic_tune, ORANGE, "수정", null, false, () -> editNote(note, note.optString("text"), note.optLong("source")));
        danger(item(group, sheet, R.drawable.ic_close, RED, "삭제", "연결된 원문과 답변은 자동 문맥에서 빠져요", false, () -> { try { store.mainChat.removeNote(note.optLong("id")); store.mainAssistant.contextChanged(); memories(); } catch(Exception e) {error(e);} }));
        sheet.body.addView(group); sheet.show();
    }
    /** One long-term note (shared with the voice assistant): edit or forget it in the same memory file Settings shows. */
    private void longTerm(JSONObject note) {
        String id = note.optString("id"), text = note.optString("text");
        Ui.Sheet sheet = menuSheet("자동 기억", preview(text));
        LinearLayout group = ui.group();
        item(group, sheet, R.drawable.ic_tune, ORANGE, "수정", "음성 비서·작업창과 함께 쓰는 기억이에요", false, () -> {
            EditText edit = field(text); edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(dev.jun.gyeot.core.Memory.TEXT_LIMIT)});
            dialog().setTitle("자동 기억 수정").setMessage("음성 비서, 메인 대화, 작업창이 함께 쓰는 기억이에요. 비밀번호·인증키는 저장하지 않아요.").setView(edit).setNegativeButton("취소", null).setPositiveButton("저장", (d,w) -> {
                try { if (!store.changeMemory(list -> dev.jun.gyeot.core.Memory.edit(list, id, edit.getText().toString(), System.currentTimeMillis()))) throw new IllegalArgumentException("비밀정보 없이 1자 이상 적어 주세요."); memories(); } catch(Exception e) {error(e);}
            }).show();
        });
        danger(item(group, sheet, R.drawable.ic_close, RED, "잊기", "음성 비서에서도 함께 지워져요", false, () -> { try { store.changeMemory(list -> dev.jun.gyeot.core.Memory.forget(list, id)); memories(); } catch(Exception e) {error(e);} }));
        sheet.body.addView(group); sheet.show();
    }
    private void editNote(JSONObject existing, String text, long source) {
        if(!historyReady())return;LinearLayout box=ui.column();String[] kinds={"제약","선호","사실"};Spinner kind=new Spinner(activity);kind.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,kinds));if(existing!=null)kind.setSelection(Math.max(0,Arrays.asList(kinds).indexOf(existing.optString("kind"))));box.addView(kind);EditText edit=field(text);edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(800)});box.addView(edit);
        dialog().setTitle("명시적 기억").setMessage("메인 AI에 별도 문맥으로, 음성 비서와 작업창에도 늘 함께 전달돼요. 비밀번호·인증키는 저장하지 마세요. 연결된 기억을 고치거나 잊으면 그 원문과 답변을 자동 문맥에서 제외하지만, 직접 보는 기록은 따로 삭제해야 해요.").setView(box).setNegativeButton("취소",null).setPositiveButton("저장",(d,w)->{try{if(existing==null)store.mainChat.addNote(kinds[kind.getSelectedItemPosition()],edit.getText().toString(),source);else store.mainChat.updateNote(existing.optLong("id"),kinds[kind.getSelectedItemPosition()],edit.getText().toString());store.mainAssistant.contextChanged();}catch(Exception e){error(e);}}).show();
    }
    private void clearHistory() {
        if(!historyReady())return;dialog().setTitle("메인 기록·기억 삭제").setMessage("메인 원문·검색·요약·명시 기억을 삭제하고 아직 전달하지 않은 작업은 취소해요. 이미 전달한 Work 기록과 진행 중인 작업은 그대로이며, 그 결과는 삭제한 대화에 다시 표시하지 않아요. 음성 비서와 함께 쓰는 자동 기억은 설정 → 기억에서 따로 지워요. 최소 중복 방지 식별자는 남아요.").setNegativeButton("취소",null).setPositiveButton("삭제",(d,w)->{try{Set<String> files=store.mainChat.fileIds();store.mainChat.clear();store.releaseMainAttachments(files);store.mainAssistant.contextChanged();latest();}catch(Exception e){error(e);}}).show();
    }
    private void privacy() {
        dialog().setTitle("한 대화, 이어지는 기억").setMessage("원문은 이 기기의 앱 내부 DB에 개수 제한 없이 남고, 화면에는 40개씩 나눠 보여 줘요. 별도의 DB 암호화나 완전한 기억을 약속하지 않아요.\n\n답할 때마다 선택한 모델이 받을 수 있는 최대 분량까지 지난 대화 원문을 그대로 보내요(절약으로 바꾸면 약 6만 자). 그보다 오래된 대화는 구조화 요약과 키워드로 찾은 과거 발췌로 이어요. 대화가 길수록 요청마다 사용량이 커져요. 모델이 분량이 너무 많다고 거절하면 그 요청은 처리되지 않은 것이라, 분량을 줄여 한 번만 다시 보내요.\n\n자동 기억을 켜 두면 대화가 몇 번 오가거나 ‘기억해’라고 말한 뒤, 선택한 메인 AI가 오래 쓸 만한 사실만 골라 장기 기억에 저장해요. 이 기억과 ‘나에 대해’ 설정은 음성 비서·작업창과 함께 쓰고, 기억·제약 관리나 설정 → 기억에서 고치거나 지울 수 있어요.\n\n답변을 기다리는 동안에도 계속 보낼 수 있어요. 답변은 최대 4개까지 동시에 준비하고 나머지는 차례를 기다려요. 답변은 쓰는 대로 나타나고, 그림은 답이 나온 뒤 그 자리에서 그려요.\n\n자동 요약·자동 기억은 별도 모델 호출이라 사용량이 발생할 수 있어요. 전역 장기 기억 또는 메인 기억 설정을 끄면 기억·검색·요약·자동 기억을 사용하지 않아요.\n\n화면을 나가도 별도 Work를 취소하지 않지만, Android가 프로세스를 종료하면 메인 답변은 그때까지 온 부분과 함께 중단으로 표시하며 자동 재전송하지 않아요. ‘이어서 답하기’를 누르면 새 요청으로 이어 가요. 수정·삭제는 이전 요약과 답변을 무효화하지만 이미 제공자에게 보낸 정보까지 지웠다는 뜻은 아니에요. 프로필 사진은 기기에만 보관하며 업로드하지 않아요.").setPositiveButton("확인",null).show();
    }
    private void error(Exception error) { error(error.getMessage()==null?"처리하지 못했어요. 원본 기록은 유지했어요.":error.getMessage()); }
    private void error(String message) { if(!stopped&&!activity.isFinishing()&&!activity.isDestroyed())dialog().setMessage(message).setPositiveButton("확인",null).show(); }
}
