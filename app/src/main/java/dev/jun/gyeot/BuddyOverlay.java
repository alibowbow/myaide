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
 * overlay permission (without it the talk still goes on, heard and said, with its notification). Main thread.
 */
final class BuddyOverlay {
    private final Context context; private final WindowManager manager; private final SharedPreferences prefs; private final Runnable stop;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout card; private ImageView face; private TextView words; private WindowManager.LayoutParams params;
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
        card.addView(words, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
        try { manager.addView(card, params); } catch (RuntimeException refused) { card = null; face = null; words = null; return; }
        state(state, null);
    }
    void hide() {
        main.removeCallbacks(mouth);
        if (card != null) { try { manager.removeView(card); } catch (RuntimeException gone) {} }
        card = null; face = null; words = null;
    }
    /** 퍼레's state as the talk goes: what it hears, that it thinks, what it says (its mouth moving). */
    void state(String state, String text) {
        this.state = state == null ? "" : state;
        if (card == null) return;
        main.removeCallbacks(mouth);
        switch (this.state) {
            case "listening" -> { face.setImageResource(R.drawable.buddy_listen); if (text != null) words.setText(text.isBlank() || "…".equals(text) ? "듣고 있어…" : text); }
            case "thinking" -> { face.setImageResource(R.drawable.buddy_listen); words.setText(text == null || text.isBlank() ? "음…" : "“" + text + "”"); }
            case "speaking" -> { if (text != null) words.setText(text); talkFrame = false; main.post(mouth); }
            default -> { face.setImageResource(R.drawable.buddy_idle); if (text != null && !text.isBlank()) words.setText(text); }
        }
    }
    private static int px(float d, int dp) { return Math.round(dp * d); }
}
