package dev.jun.gyeot;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.AvatarCharacter;
import dev.jun.gyeot.core.AvatarSpec;
import dev.jun.gyeot.core.BotAvatar;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static dev.jun.gyeot.Ui.*;

/**
 * 살아있는 아바타: the assistant's profile as an open-bot-avatar character that breathes, looks around, thinks while
 * replies are prepared and is happy when they arrive. Three sources:
 * - a ready shape (the engine's starters and its gallery) in any palette colour;
 * - a new shape an AI designs from the user's words (open-bot-avatar's design prompt, through the main chat's model; only
 *   the words are sent);
 * - the profile picture itself, made with GPT image or uploaded: traced on the phone into a silhouette, with the picture
 *   over the front and either the character's own eyes or the living eyes.
 * Nothing is applied until 적용; 아바타 끄기 returns to the plain picture.
 */
final class AssistantAvatarUi {
    private AssistantAvatarUi() {}
    static void edit(Activity activity, Runnable changed) { if (!activity.isFinishing() && !activity.isDestroyed()) new Editor(activity, changed).show(); }

    static final class Editor {
        final Activity activity; final Runnable changed; final Ui ui; final Ui.Sheet sheet; final Handler main = new Handler(Looper.getMainLooper());
        final ImageView preview; final TextView state; final LinearLayout colors, eyeColors, eyesChoice, shapes;
        final AtomicReference<Runnable> cancel = new AtomicReference<>();
        AvatarSpec draft; AvatarDrawable shown; Bitmap picture; byte[] pictureBytes; String profileName = "들퍼레";
        boolean closed, busy; int ticket; Ui.Sheet maker; String mood = "idle";

        Editor(Activity activity, Runnable changed) {
            this.activity = activity; this.changed = changed; ui = new Ui(activity);
            sheet = ui.selectionSheet("살아있는 아바타", "open-bot-avatar · 이 기기에서만 그리고 움직여요");
            preview = new ImageView(activity); preview.setContentDescription("아바타 미리보기");
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ui.dp(136), ui.dp(136)); pp.gravity = Gravity.CENTER_HORIZONTAL; pp.bottomMargin = ui.dp(10);
            sheet.body.addView(preview, pp);
            sheet.body.addView(ui.pills(dev.jun.gyeot.core.ProfileMotion.labels(), 0, i -> { mood = BotAvatar.MOODS.get(i); if (shown != null) shown.mood(mood); }));
            state = ui.footer("모양을 고르거나, 프로필 사진·AI로 나만의 캐릭터를 만들어요. 표정은 9가지예요. 답을 준비할 땐 생각하고, 답이 오면 기뻐하고, 오래 쉬면 졸고, 답이 실패하면 시무룩해져요.");
            sheet.body.addView(state);

            sheet.body.addView(ui.sectionHeader("나만의 캐릭터"));
            LinearLayout own = ui.group();
            own.addView(ui.listRow(R.drawable.ic_gallery, ACCENT, "프로필 사진으로 만들기", "GPT 이미지로 만든 그림이나 올린 사진을 캐릭터로 · 사진은 보내지 않아요", ui.chevron(), this::fromPicture));
            own.addView(ui.listRow(R.drawable.ic_sparkle, ACCENT, "AI로 새 모양 만들기", "원하는 모습을 적으면 메인 대화의 AI가 모양을 그려요 · 적은 말만 보내요", ui.chevron(), this::maker));
            sheet.body.addView(own);

            eyesChoice = ui.column(); eyesChoice.setVisibility(View.GONE);
            TextView eyesTitle = ui.text("눈", FOOTNOTE, 620, MUTED); eyesTitle.setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(8)); eyesChoice.addView(eyesTitle);
            eyesChoice.addView(ui.pills(new String[]{"캐릭터 눈 그대로", "살아있는 눈 얹기"}, 0, i -> { if (draft != null && AvatarSpec.PICTURE.equals(draft.kind)) show(draft.ownEyes(i == 0)); }));
            sheet.body.addView(eyesChoice);

            sheet.body.addView(ui.sectionHeader("모양"));
            shapes = ui.row(); HorizontalScrollView strip = new HorizontalScrollView(activity); strip.setHorizontalScrollBarEnabled(false); strip.addView(shapes); sheet.body.addView(strip);
            sheet.body.addView(ui.sectionHeader("색"));
            colors = ui.row(); HorizontalScrollView colorStrip = new HorizontalScrollView(activity); colorStrip.setHorizontalScrollBarEnabled(false); colorStrip.addView(colors); sheet.body.addView(colorStrip);
            for (String color : AvatarSpec.COLORS) colors.addView(swatch(color, () -> { if (draft != null && !AvatarSpec.PICTURE.equals(draft.kind)) show(draft.colors(color, null)); }));
            TextView eyeTitle = ui.text("눈 색", FOOTNOTE, 620, MUTED); eyeTitle.setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(8)); sheet.body.addView(eyeTitle);
            eyeColors = ui.row(); sheet.body.addView(eyeColors);
            for (String color : AvatarSpec.EYE_COLORS) eyeColors.addView(swatch(color, () -> { if (draft != null) show(draft.colors(null, color)); }));
            TextView off = ui.ghost("아바타 끄기 · 사진만 보기", this::off); LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, -2); op.topMargin = ui.dp(16); op.gravity = Gravity.CENTER_HORIZONTAL; sheet.body.addView(off, op);
            TextView credit = ui.footer("open-bot-avatar (MIT, Dominik Scholz)의 모양과 엔진을 써요."); sheet.body.addView(credit);
            sheet.primary.setText("적용"); sheet.primary.setOnClickListener(v -> save());
            sheet.setOnDismissListener(d -> { closed = true; ticket++; stopAi(); if (maker != null) maker.dismiss(); if (shown != null) shown.stop(); });
        }
        void show() { sheet.show(); load(); }
        boolean alive() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }

        /** The saved avatar (or the first shape), the profile picture for tracing, and the shape strip. */
        void load() {
            int mine = ++ticket; setBusy(true);
            AssistantProfile.IO.execute(() -> {
                AvatarSpec saved = AssistantAvatar.read(activity); List<AssistantAvatar.Choice> choices = AssistantAvatar.choices(activity);
                Bitmap bitmap = null; byte[] bytes = new byte[0]; String name = "들퍼레";
                try {
                    AssistantProfile.Saved profile = AssistantProfile.read(activity); bytes = profile.bytes(); name = profile.name();
                    if (bytes.length > 0 && AssistantProfile.decode(bytes).first() instanceof BitmapDrawable d) bitmap = d.getBitmap();
                } catch (Exception | OutOfMemoryError ignored) {}
                Bitmap texture = bitmap; byte[] source = bytes; String label = name;
                main.post(() -> {
                    if (!alive() || ticket != mine) return;
                    setBusy(false); picture = texture; pictureBytes = source; profileName = label;
                    for (AssistantAvatar.Choice choice : choices) shapes.addView(tile(choice));
                    AvatarSpec start = saved;
                    if (start != null && AvatarSpec.PICTURE.equals(start.kind) && (source.length == 0 || !AssistantAvatar.checksum(source).equals(start.picture))) {
                        start = null; state.setText("프로필 사진이 바뀌어서 사진 캐릭터를 다시 만들어야 해요. ‘프로필 사진으로 만들기’를 눌러 주세요.");
                    }
                    if (start == null && !choices.isEmpty()) try { start = AvatarSpec.shape(AvatarSpec.GALLERY, choices.get(0).definition(), choices.get(0).color(), null); } catch (RuntimeException ignored) {}
                    if (start != null) show(start);
                });
            });
        }
        /** A ready shape as a small still tile in its own colour. */
        View tile(AssistantAvatar.Choice choice) {
            ImageView tile = new ImageView(activity);
            try {
                AvatarSpec spec = AvatarSpec.shape(AvatarSpec.GALLERY, choice.definition(), choice.color(), null);
                AvatarDrawable still = new AvatarDrawable(spec, null, true, FILL); still.density(activity.getResources().getDisplayMetrics().density);
                tile.setImageDrawable(still); tile.setContentDescription(still.name() + " 모양 고르기");
                tile.setOnClickListener(v -> { AvatarSpec next = draft != null && !AvatarSpec.PICTURE.equals(draft.kind) ? spec.colors(draft.color, draft.eyeColor) : spec; show(next); });
            } catch (RuntimeException invalid) { tile.setVisibility(View.GONE); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)); lp.rightMargin = ui.dp(8); tile.setLayoutParams(lp);
            Ui.pressable(tile, true);
            return tile;
        }
        View swatch(String color, Runnable action) {
            View dot = new View(activity); GradientDrawable fill = new GradientDrawable(); fill.setShape(GradientDrawable.OVAL); fill.setColor(android.graphics.Color.parseColor(color));
            fill.setStroke(ui.dp(1), SEPARATOR); dot.setBackground(fill); dot.setContentDescription("색 " + color); dot.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)); lp.rightMargin = ui.dp(10); dot.setLayoutParams(lp);
            return dot;
        }
        /** Shows a draft live in the preview, with the controls it uses. */
        void show(AvatarSpec spec) {
            AvatarDrawable next;
            try { next = new AvatarDrawable(spec, AvatarSpec.PICTURE.equals(spec.kind) ? picture : null, true, FILL); }
            catch (RuntimeException invalid) { state.setText("이 모양을 그리지 못했어요: " + message(invalid)); return; }
            next.density(activity.getResources().getDisplayMetrics().density); next.mood(mood);
            if (shown != null) shown.stop();
            draft = spec; shown = next; preview.setImageDrawable(next); next.start();
            boolean pictureKind = AvatarSpec.PICTURE.equals(spec.kind);
            eyesChoice.setVisibility(pictureKind ? View.VISIBLE : View.GONE);
            colors.setAlpha(pictureKind ? 0.35f : 1f);
            preview.setContentDescription("아바타 미리보기 · " + next.name());
        }

        /** The profile picture, traced on this phone: nothing is sent. */
        void fromPicture() {
            if (!alive() || busy) return;
            if (picture == null) { state.setText("먼저 프로필 사진을 정해 주세요. 프로필 설정에서 GPT 이미지로 만들거나 들퍼레 폴더의 사진을 고를 수 있어요."); return; }
            int mine = ++ticket; setBusy(true); state.setText("사진에서 캐릭터 모양을 찾는 중…");
            Bitmap source = picture; byte[] bytes = pictureBytes; String name = profileName; String eyes = draft == null ? null : draft.eyeColor;
            AssistantProfile.IO.execute(() -> {
                try {
                    AvatarCharacter.Result traced = AssistantAvatar.trace(source);
                    AvatarSpec spec = AvatarSpec.picture(traced, name, AssistantAvatar.checksum(bytes), true, eyes);
                    main.post(() -> { if (!alive() || ticket != mine) return; setBusy(false); show(spec); state.setText("캐릭터 모양을 찾았어요. 눈을 고르고 ‘적용’을 눌러요. 배경이 단순할수록 깔끔해요."); });
                } catch (Exception | OutOfMemoryError e) { main.post(() -> { if (!alive() || ticket != mine) return; setBusy(false); state.setText(message(e)); }); }
            });
        }

        /** A description, then one JSON shape from the main chat's model through open-bot-avatar's design prompt. */
        void maker() {
            if (!alive() || busy) return;
            AppStore store = activity.getApplication() instanceof GyeotApp app ? app.store() : null;
            ScopedModels models = store == null ? null : ScopedModels.main(store);
            String engine = models == null ? "" : models.readyEngine(), model = engine.isEmpty() ? "" : models.model(engine);
            if (engine.isEmpty() || model.isBlank()) { state.setText("AI로 만들려면 메인 대화에서 쓸 AI와 모델을 먼저 연결해 주세요."); return; }
            if (maker != null) maker.dismiss();
            Ui.Sheet s = ui.selectionSheet("AI로 새 모양 만들기", dev.jun.gyeot.core.WorkBook.author(engine) + " · " + model); maker = s;
            EditText describe = ui.input("어떤 캐릭터로 만들까요? 예: 커피 좋아하는 머그잔, 책 읽는 부엉이", 3, AvatarSpec.DESCRIPTION_LIMIT);
            describe.setContentDescription("만들 아바타 설명"); s.body.addView(describe);
            TextView status = ui.footer("적은 설명과 open-bot-avatar의 디자인 지침만 보내요. 모양은 앱이 규칙에 맞는지 확인한 뒤 미리 보여 줘요. 사용량이 들 수 있어요.");
            s.body.addView(status);
            int[] round = {0};
            s.primary.setText("만들기");
            s.primary.setOnClickListener(v -> {
                String words;
                try { words = AvatarSpec.description(describe.getText().toString()); } catch (IllegalArgumentException e) { status.setText(e.getMessage()); return; }
                int mine = ++round[0]; s.primary.setEnabled(false); s.primary.setText("만드는 중…"); describe.setEnabled(false); status.setText("모양을 그리는 중이에요. 보통 몇십 초 걸려요. 창을 닫으면 멈춰요.");
                String color = draft == null || AvatarSpec.PICTURE.equals(draft.kind) ? AvatarSpec.COLORS[0] : draft.color, eyes = draft == null ? null : draft.eyeColor;
                String effort = models.effort(engine, model);
                Thread worker = new Thread(() -> {
                    try {
                        FileAiOperationsUi.Connection connection = FileAiOperationsUi.Connection.capture(store, engine);
                        String answer = FileAiOperationsUi.complete(store, connection, model, effort, AssistantAvatar.designPrompt(activity), words,
                            () -> { if (!alive() || maker != s || round[0] != mine) throw new java.util.concurrent.CancellationException(); }, cancel);
                        AvatarSpec spec = AvatarSpec.shape(AvatarSpec.MADE, AvatarSpec.fromModel(answer), color, eyes);
                        main.post(() -> { if (!alive() || maker != s || round[0] != mine) return; s.dismiss(); show(spec); state.setText("‘" + spec.name() + "’ 모양을 만들었어요. 색을 고르고 ‘적용’을 눌러요."); });
                    } catch (Exception | OutOfMemoryError e) {
                        main.post(() -> { if (!alive() || maker != s || round[0] != mine) return; s.primary.setEnabled(true); s.primary.setText("다시 만들기"); describe.setEnabled(true); status.setText(message(e)); });
                    }
                }, "avatar-design"); worker.setDaemon(true); worker.start();
            });
            s.setOnDismissListener(d -> { round[0]++; stopAi(); if (maker == s) maker = null; });
            s.show();
        }
        void stopAi() { Runnable stop = cancel.getAndSet(null); if (stop != null) try { stop.run(); } catch (RuntimeException ignored) {} }

        void save() {
            if (!alive() || busy || draft == null) return;
            AvatarSpec spec = draft; setBusy(true); state.setText("이 기기에 저장하는 중…");
            AssistantProfile.IO.execute(() -> {
                try { AssistantAvatar.save(activity, spec); main.post(() -> { if (!alive()) return; sheet.dismiss(); changed.run(); }); }
                catch (Exception e) { main.post(() -> { if (!alive()) return; setBusy(false); state.setText(message(e)); }); }
            });
        }
        void off() {
            if (!alive() || busy) return;
            AssistantProfile.IO.execute(() -> { AssistantAvatar.clear(activity); main.post(() -> { if (!alive()) return; sheet.dismiss(); changed.run(); }); });
        }
        void setBusy(boolean value) { busy = value; sheet.primary.setEnabled(!value && draft != null); }
        static String message(Throwable e) { return e instanceof java.util.concurrent.CancellationException ? "멈췄어요." : e.getMessage() == null ? "아바타를 만들지 못했어요." : e.getMessage(); }
    }
}
