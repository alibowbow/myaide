package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 퍼레 over other apps while it talks (준: "퍼레 대화모드일때는 다른 앱 위에 그려지게 해줘", "퍼레가 말할때 브라우저라도 켜면 바로
 * 꺼져버려"): a small card at the bottom corner, 퍼레's face (listening, talking, thinking) and its words in a bubble, ✕ to end.
 * Dragged by 퍼레, it stays where it was left. Shown only while the home screen is not in front and the talk is on; needs the
 * overlay permission (without it the talk still goes on, heard and said, with its notification). Under the words, the job 퍼레
 * took on (BuddyJobs: "● 배민 담는 중 · 3단계" and 중지, the same 중지 as the home screen's; a tap on the line opens its result).
 * Main thread.
 */
final class BuddyOverlay {
    private final Context context; private final WindowManager manager; private final SharedPreferences prefs; private final Runnable stop;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout card; private ImageView face; private TextView words, jobLine, jobStop; private LinearLayout job; private WindowManager.LayoutParams params;
    /** The jobs as BuddyJobs.json gives them; the newest is shown. */
    private org.json.JSONArray jobs = new org.json.JSONArray();
    private String state = ""; private boolean talkFrame;
    private final Runnable mouth = new Runnable() { @Override public void run() {
        if (face == null || !"speaking".equals(state)) return;
        talkFrame = !talkFrame; face.setImageResource(talkFrame ? R.drawable.buddy_talk : R.drawable.buddy_idle);
        main.postDelayed(this, 170);
    } };

    BuddyOverlay(Context context, Runnable stop) {
        this.context = context.getApplicationContext(); this.stop = stop;
        manager = this.context.getSystemService(WindowManager.class);
        prefs = this.context.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
    }
    static boolean allowed(Context c) { try { return Settings.canDrawOverlays(c); } catch (RuntimeException unknown) { return false; } }
    boolean shown() { return card != null; }

    void show() {
        if (card != null || !allowed(context) || manager == null) return;
        float d = context.getResources().getDisplayMetrics().density;
        card = new LinearLayout(context); card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(px(d, 6), px(d, 6), px(d, 8), px(d, 6));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(px(d, 26)); card.setBackground(bg); card.setElevation(px(d, 8));
        face = new ImageView(context); face.setImageResource(R.drawable.buddy_idle); face.setContentDescription("퍼레 · 끌어서 옮기기");
        card.addView(face, new LinearLayout.LayoutParams(px(d, 44), px(d, 44)));
        words = new TextView(context); words.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); words.setTextColor(0xff22232f); words.setMaxLines(3);
        words.setEllipsize(TextUtils.TruncateAt.END); words.setMaxWidth(px(d, 220)); words.setPadding(px(d, 8), 0, px(d, 6), 0); words.setText("듣고 있어…");
        LinearLayout column = new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL);
        column.addView(words, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // The job's line: as small as a caption, its 중지 a word beside it (준: 화면은 컴팩트하게).
        job = new LinearLayout(context); job.setOrientation(LinearLayout.HORIZONTAL); job.setGravity(Gravity.CENTER_VERTICAL); job.setPadding(px(d, 8), px(d, 2), 0, 0);
        jobLine = new TextView(context); jobLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); jobLine.setTextColor(0xff5b5bd6); jobLine.setSingleLine(true);
        jobLine.setEllipsize(TextUtils.TruncateAt.END); jobLine.setMaxWidth(px(d, 170));
        jobStop = new TextView(context); jobStop.setText("중지"); jobStop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); jobStop.setTextColor(0xffd14343);
        jobStop.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); jobStop.setPadding(px(d, 8), px(d, 2), px(d, 4), px(d, 2)); jobStop.setContentDescription("맡은 일 중지");
        job.addView(jobLine); job.addView(jobStop);
        column.addView(job, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(column, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView close = new TextView(context); close.setText("✕"); close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); close.setTextColor(0xff6a6b7d);
        close.setGravity(Gravity.CENTER); close.setContentDescription("퍼레와 대화 끝내기");
        GradientDrawable cb = new GradientDrawable(); cb.setShape(GradientDrawable.OVAL); cb.setColor(0xfff1f2f6); close.setBackground(cb);
        close.setOnClickListener(v -> stop.run());
        card.addView(close, new LinearLayout.LayoutParams(px(d, 28), px(d, 28)));
        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.END; params.x = prefs.getInt("buddyOverlayX", px(d, 12)); params.y = prefs.getInt("buddyOverlayY", px(d, 110));
        face.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> { sx = e.getRawX(); sy = e.getRawY(); ox = params.x; oy = params.y; return true; }
                    case MotionEvent.ACTION_MOVE -> {
                        params.x = Math.max(0, ox - Math.round(e.getRawX() - sx)); params.y = Math.max(0, oy - Math.round(e.getRawY() - sy));
                        try { manager.updateViewLayout(card, params); } catch (RuntimeException gone) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP -> { prefs.edit().putInt("buddyOverlayX", params.x).putInt("buddyOverlayY", params.y).apply(); return true; }
                    default -> { return false; }
                }
            }
        });
        try { manager.addView(card, params); } catch (RuntimeException refused) { card = null; face = null; words = null; job = null; jobLine = null; jobStop = null; return; }
        state(state, null); drawJob();
    }
    void hide() {
        main.removeCallbacks(mouth);
        if (card != null) { try { manager.removeView(card); } catch (RuntimeException gone) {} }
        card = null; face = null; words = null; job = null; jobLine = null; jobStop = null;
    }
    /** The jobs now (BuddyJobs.json): the newest on the line under the words, with 중지 while it runs; none, no line. */
    void jobs(org.json.JSONArray list) { jobs = list == null ? new org.json.JSONArray() : list; drawJob(); }
    /** The line shown now ("" when there is none), for the screen tests. */
    String jobShown() { return job == null || job.getVisibility() != View.VISIBLE ? "" : jobLine.getText().toString(); }
    private void drawJob() {
        if (job == null) return;
        org.json.JSONObject newest = jobs.length() == 0 ? null : jobs.optJSONObject(jobs.length() - 1);
        if (newest == null) { job.setVisibility(View.GONE); return; }
        String id = newest.optString("id"); boolean running = "running".equals(newest.optString("state"));
        job.setVisibility(View.VISIBLE);
        jobLine.setText((running ? "● " : "") + newest.optString("line"));
        jobLine.setOnClickListener(v -> BuddyHands.open(context, id, true));
        jobStop.setVisibility(newest.optBoolean("stop") ? View.VISIBLE : View.GONE); jobStop.setText("중지"); jobStop.setEnabled(true);
        // The engine's own stop may touch its files (연구실): never on the main thread.
        jobStop.setOnClickListener(v -> { jobStop.setText("멈추는 중"); jobStop.setEnabled(false); Thread t = new Thread(() -> dev.jun.gyeot.core.BuddyJobs.stop(id), "gyeot-buddy-job-stop"); t.setDaemon(true); t.start(); });
    }
    /** 퍼레's state as the talk goes: what it hears, that it thinks, what it says (its mouth moving while it sounds). */
    void state(String state, String text) {
        this.state = state == null ? "" : state;
        if (card == null) return;
        main.removeCallbacks(mouth);
        switch (this.state) {
            case "listening" -> { face.setImageResource(R.drawable.buddy_listen); if (text != null) words.setText(text.isBlank() || "…".equals(text) ? "듣고 있어…" : text); }
            case "thinking" -> { face.setImageResource(R.drawable.buddy_listen); words.setText(text == null || text.isBlank() ? "음…" : "“" + text + "”"); }
            case "speaking" -> { if (text != null) words.setText(text); talkFrame = false; main.post(mouth); }
            // Between two sentences, nothing sounding: as it speaks, the words kept, the mouth still.
            case "gap" -> { face.setImageResource(R.drawable.buddy_idle); if (text != null && !text.isBlank()) words.setText(text); }
            default -> { face.setImageResource(R.drawable.buddy_idle); if (text != null && !text.isBlank()) words.setText(text); }
        }
    }
    private static int px(float d, int dp) { return Math.round(dp * d); }
}
