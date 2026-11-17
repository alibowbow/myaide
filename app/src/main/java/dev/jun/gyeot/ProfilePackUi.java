package dev.jun.gyeot;

import android.app.Activity;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.BotAvatar;
import dev.jun.gyeot.core.ProfileMotion;
import dev.jun.gyeot.core.ProfileShelf;
import java.util.ArrayList;
import java.util.List;
import static dev.jun.gyeot.Ui.*;

/**
 * 표정 GIF 자동 만들기: for one library picture, GPT Image 2.6 draws an animated GIF for each of the avatar's nine moods
 * (평소, 생각 중, 기뻐요, 놀람, 슬퍼요, 졸려요, 윙크, 좋아요, 화나요). When the picture is the profile, the profile then
 * shows the GIF of its mood: thinking while a reply is prepared, happy when it arrives, sad when it failed, sleepy when quiet.
 */
final class ProfilePackUi {
    private ProfilePackUi() {}

    static void show(Activity activity, AppStore store, ProfileShelf.Entry entry, Runnable changed) {
        if (!activity.isFinishing() && !activity.isDestroyed()) new Sheet(activity, store, entry, changed).sheet.show();
    }

    static final class Sheet {
        final Activity activity; final AppStore store; final Runnable changed; final Ui ui; final Ui.Sheet sheet;
        final Handler main = new Handler(Looper.getMainLooper());
        final ImageView preview; final TextView made, status; final EditText describe; final Button clear, steady;
        /** 흔들림 줄이기 is running; what its last run said. */
        boolean steadying; String steadied = "";
        final Runnable refresh = this::refresh;
        ProfileShelf.Entry entry; int shownMood, reference, which, ticket; boolean closed; Drawable playing;
        /** The picture's stored description as last shown in the box; a new one (made from the picture) fills an empty box once. */
        String shownPrompt;

        Sheet(Activity activity, AppStore store, ProfileShelf.Entry entry, Runnable changed) {
            this.activity = activity; this.store = store; this.entry = entry; this.changed = changed; ui = new Ui(activity);
            sheet = ui.selectionSheet("표정 GIF 자동 만들기", "GPT 이미지 2.6 · 표정마다 움직이는 GIF를 만들어요");
            preview = new ImageView(activity); preview.setContentDescription("표정 GIF 미리보기");
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ui.dp(124), ui.dp(124)); pp.gravity = Gravity.CENTER_HORIZONTAL; pp.bottomMargin = ui.dp(10);
            sheet.body.addView(preview, pp);
            sheet.body.addView(ui.pills(ProfileMotion.labels(), 0, i -> { shownMood = i; showMood(); }));
            made = ui.footer(""); sheet.body.addView(made);

            describe = ui.input("캐릭터 설명 · 예: 동그란 안경을 쓴 주황 여우", 2, ProfileShelf.PROMPT_LIMIT);
            describe.setText(entry.prompt); shownPrompt = entry.prompt; describe.setContentDescription("표정 GIF로 만들 캐릭터 설명"); sheet.body.addView(describe);
            sheet.body.addView(title("참고 그림"));
            reference = entry.madeByGpt() ? 0 : 1;
            sheet.body.addView(ui.pills(new String[]{"이 그림을 참고로 보내기", "설명만 보내기"}, reference, i -> { reference = i; refresh(); }));
            sheet.body.addView(title("만들 표정"));
            sheet.body.addView(ui.pills(new String[]{"아직 없는 표정만", "9개 모두 새로"}, 0, i -> { which = i; refresh(); }));
            sheet.body.addView(title("프로필에 쓰기"));
            sheet.body.addView(ui.pills(new String[]{"표정 GIF로 보여 주기", "쓰지 않기"}, entry.useMoods || entry.moods.isEmpty() ? 0 : 1, i -> use(i == 0)));
            status = ui.footer(""); sheet.body.addView(status);
            // For GIFs already made: holds the character still without drawing them again (no usage, nothing sent).
            steady = ui.button("흔들림 줄이기", false, this::steady); steady.setContentDescription("흔들림 줄이기: 만든 표정 GIF에서 캐릭터가 좌우로 흔들리는 것을 줄여요. 다시 만들지 않아요.");
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = ui.dp(8); sheet.body.addView(steady, sp);
            clear = ui.button("만든 표정 GIF 지우기", false, this::clear);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.topMargin = ui.dp(8); sheet.body.addView(clear, cp);
            sheet.primary.setOnClickListener(v -> primary());
            ProfilePack.listen(refresh);
            sheet.setOnDismissListener(d -> { closed = true; ProfilePack.unlisten(refresh); stopPlaying(); });
            refresh(); showMood();
        }
        TextView title(String text) { TextView t = ui.text(text, FOOTNOTE, 620, MUTED); t.setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(8)); return t; }

        List<String> wanted() { return which == 1 ? new ArrayList<>(BotAvatar.MOODS) : entry.missingMoods(); }
        boolean mine() { ProfilePack.Job j = ProfilePack.current(); return j != null && j.entryId.equals(entry.id); }

        void refresh() {
            if (closed) return;
            reloadEntry();
            if (!entry.prompt.equals(shownPrompt)) {
                if (describe.getText().toString().isBlank()) describe.setText(entry.prompt);
                shownPrompt = entry.prompt;
            }
            StringBuilder list = new StringBuilder();
            for (ProfileMotion.Mood m : ProfileMotion.MOODS) if (entry.moods.contains(m.id())) list.append(list.length() == 0 ? "" : " · ").append(m.label());
            made.setText(entry.moods.isEmpty() ? "아직 만든 표정 GIF가 없어요." : "만든 표정 " + entry.moods.size() + "/9 · " + list);
            clear.setVisibility(entry.moods.isEmpty() || ProfilePack.busy() ? View.GONE : View.VISIBLE);
            steady.setVisibility(entry.moods.isEmpty() || ProfilePack.busy() ? View.GONE : View.VISIBLE); steady.setEnabled(!steadying);
            steady.setText(steadying ? "흔들림을 줄이는 중…" : "흔들림 줄이기");
            ProfilePack.Job job = ProfilePack.current();
            String route = AssistantProfileUi.route(store);
            if (ProfilePack.busy()) {
                sheet.primary.setEnabled(mine()); sheet.primary.setText(mine() ? "멈추기" : "다른 그림을 만드는 중…");
                status.setText(mine() ? job.status() + "\n창을 닫아도 앱이 켜져 있는 동안 계속 만들고, 다 된 표정은 바로 보관돼요." : "다른 그림의 표정을 만드는 중이에요: " + job.status());
                return;
            }
            int count = wanted().size();
            sheet.primary.setEnabled(count > 0 && !route.isEmpty());
            sheet.primary.setText(count == 0 ? "표정을 다 만들었어요" : "표정 " + count + "개 만들기");
            String why = route.isEmpty() ? "GPT 이미지를 쓰려면 설정 → ChatGPT에서 이미지용 Codex 계정이나 API 키를 연결해 주세요."
                : (reference == 0 ? "이 그림을 OpenAI로 보내 같은 캐릭터로 움직이게 해요. " : "적은 설명만 보내요. 캐릭터가 그림과 조금 다를 수 있어요. ")
                + "GIF 한 개에 보통 30초~1분, 사용량이 들어요.";
            status.setText((steadied.isEmpty() ? "" : steadied + "\n") + (job != null && job.entryId.equals(entry.id) ? job.status() + "\n" : "") + why);
        }
        void reloadEntry() {
            // The runner keeps GIFs as they finish; the index is small, so re-read it for the newest state.
            try { ProfileShelf.Entry fresh = ProfileLibrary.read(activity).find(entry.id); if (fresh != null) entry = fresh; } catch (RuntimeException ignored) {}
        }

        void primary() {
            if (closed) return;
            if (ProfilePack.busy()) { if (mine()) ProfilePack.stop(); return; }
            String route = AssistantProfileUi.route(store);
            if (route.isEmpty()) { refresh(); return; }
            try { ProfilePack.start(activity, store, entry, wanted(), describe.getText().toString(), reference == 0, route.equals("codex")); }
            catch (IllegalStateException e) { status.setText(e.getMessage()); return; }
            refresh();
        }
        void use(boolean on) {
            String id = entry.id;
            AssistantProfile.IO.execute(() -> {
                try { ProfileLibrary.useMoods(activity, id, on); AssistantProfile.refreshAll(); main.post(() -> { if (!closed) refresh(); changed.run(); }); }
                catch (Exception e) { main.post(() -> { if (!closed) status.setText(e.getMessage()); }); }
            });
        }
        /** 흔들림 줄이기: on the phone, from the GIFs kept; says how many changed, or that they were still already. */
        void steady() {
            if (closed || steadying || ProfilePack.busy()) return;
            steadying = true; steadied = ""; refresh();
            String id = entry.id;
            AssistantProfile.IO.execute(() -> {
                String told;
                try {
                    int[] result = ProfilePack.steady(activity, id);
                    told = result[0] > 0 ? "표정 GIF " + result[0] + "개의 흔들림을 줄였어요. 다시 만들지 않았고, 그림 가장자리가 조금 잘렸을 수 있어요."
                        : "이미 흔들림이 거의 없어요. 그래도 흔들려 보이면, 표정을 다시 만들면 새 방식으로 더 가만히 그려요.";
                } catch (Exception | OutOfMemoryError e) { told = e.getMessage() == null ? "흔들림을 줄이지 못했어요." : e.getMessage(); }
                String message = told;
                main.post(() -> { steadying = false; steadied = message; if (!closed) { refresh(); showMood(); } changed.run(); });
            });
        }
        void clear() {
            if (closed || ProfilePack.busy()) return;
            ui.confirm("이 그림의 표정 GIF " + entry.moods.size() + "개를 지울까요? 그림 자체와 지금 프로필 사진은 그대로예요.", "표정 GIF 지우기", () -> {
                String id = entry.id;
                AssistantProfile.IO.execute(() -> {
                    try { ProfileLibrary.clearMoods(activity, id); AssistantProfile.refreshAll(); main.post(() -> { if (!closed) { refresh(); showMood(); } changed.run(); }); }
                    catch (Exception e) { main.post(() -> { if (!closed) status.setText(e.getMessage()); }); }
                });
            }).show();
        }
        /** The chosen mood's GIF, playing, or the picture itself while that mood has none yet. */
        void showMood() {
            String mood = BotAvatar.MOODS.get(shownMood); ProfileShelf.Entry e = entry; int mine = ++ticket;
            AssistantProfile.IO.execute(() -> {
                try {
                    byte[] bytes = e.moods.contains(mood) ? ProfileLibrary.mood(activity, e, mood) : ProfileLibrary.image(activity, e);
                    AssistantProfile.Decoded d = AssistantProfile.decode(bytes);
                    main.post(() -> {
                        if (closed || mine != ticket) { if (d.animation() instanceof Animatable a) a.stop(); return; }
                        stopPlaying(); playing = d.animation() != null ? d.animation() : d.first();
                        preview.setImageDrawable(new AssistantProfile.Circle(playing));
                        if (playing instanceof Animatable a && android.animation.ValueAnimator.areAnimatorsEnabled()) a.start();
                    });
                } catch (Exception | OutOfMemoryError ignored) {}
            });
        }
        void stopPlaying() { if (playing instanceof Animatable a) a.stop(); playing = null; }
    }
}
