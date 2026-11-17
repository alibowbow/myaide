package dev.jun.gyeot;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The voice button: a glowing, slowly turning color orb that breathes. Idle it rests softly; connecting it spins; listening
 * it breathes; speaking it pulses; thinking (the brain is working) its colors swirl round and it breathes slowly; muted it
 * turns gray and still; offline it turns gray and rests softly until the network is back. The animation stops when
 * animations are off.
 */
final class VoiceOrb extends View {
    enum State { IDLE, CONNECTING, LISTENING, SPEAKING, THINKING, MUTED, OFFLINE }
    /** Dusk: coral, amber, peach, rose and blush going round, the warm answer to the old cyan-to-violet. */
    private static final int[] COLORS = {0xffff8a65, 0xffffc062, 0xffff9d7a, 0xffe8627f, 0xfff6a6b8, 0xffff8a65};
    private static final int[] GRAYS = {0xff8f8a82, 0xffaea9a0, 0xffc9c4ba, 0xffaea9a0, 0xff8f8a82};
    private final Paint orb = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix turn = new Matrix();
    private final ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
    private State state = State.IDLE;
    private final long start = System.nanoTime();
    private float time;
    private int built = -1;

    /**
     * The call's state as both voice drawings show it (the voice screen's orb and the floating button): its connection phase
     * (core/VoiceLink), never the status text, so a caption socket's retry does not show as connecting.
     */
    static State of(AppStore store) {
        if (!store.voiceActive) return State.IDLE;
        if (store.voiceNetwork.offline()) return State.OFFLINE;
        if (store.muted) return State.MUTED;
        if (store.voiceLink.connecting()) return State.CONNECTING;
        if (!store.outputDraft.isBlank()) return State.SPEAKING;
        return store.thinking() ? State.THINKING : State.LISTENING;
    }

    VoiceOrb(Context context) {
        super(context);
        clock.setDuration(1000); clock.setRepeatCount(ValueAnimator.INFINITE); clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(animation -> { time = (System.nanoTime() - start) / 1e9f; invalidate(); });
        setClickable(true); setFocusable(true);
    }
    void state(State next) {
        if (next == state) return;
        state = next; built = -1; invalidate();
        if (state == State.MUTED) clock.cancel(); else if (isAttachedToWindow() && isShown() && ValueAnimator.areAnimatorsEnabled() && !clock.isStarted()) clock.start();
    }
    /** Animates only while actually on screen, so a backgrounded app spends nothing on it. */
    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible && state != State.MUTED && ValueAnimator.areAnimatorsEnabled()) { if (!clock.isStarted()) clock.start(); }
        else clock.cancel();
    }
    @Override protected void onDetachedFromWindow() { clock.cancel(); super.onDetachedFromWindow(); }

    private void build(float cx, float cy, float radius) {
        int key = (int) radius * 10 + state.ordinal();
        if (key == built) return;
        built = key;
        boolean gray = state == State.MUTED || state == State.OFFLINE;
        orb.setShader(new SweepGradient(cx, cy, gray ? GRAYS : COLORS, null));
        int halo = gray ? 0x338f8a82 : 0x66ff8a5c;
        glow.setShader(new RadialGradient(cx, cy, radius * 1.45f, new int[]{halo, halo & 0x00ffffff | 0x22000000, 0x00000000}, new float[]{0.45f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        shine.setShader(new RadialGradient(cx - radius * 0.35f, cy - radius * 0.4f, radius * 0.9f, new int[]{0xccffffff, 0x33ffffff, 0x00ffffff}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }
    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, base = Math.min(getWidth(), getHeight()) / 2f / 1.45f;
        if (base < 1) return; // gradients need a positive radius
        build(cx, cy, base);
        // Breathing: amplitude and pace by state.
        float amplitude = switch (state) { case SPEAKING -> 0.07f; case LISTENING -> 0.045f; case THINKING -> 0.05f; case CONNECTING -> 0.02f; case MUTED -> 0f; default -> 0.025f; };
        float pace = switch (state) { case SPEAKING -> 5.5f; case LISTENING -> 3.2f; case THINKING -> 1.6f; case CONNECTING -> 2f; default -> 1.9f; };
        float breath = (float) Math.sin(time * pace) * 0.5f + 0.5f;
        if (state == State.SPEAKING) breath = (float) (0.5 + 0.5 * Math.sin(time * pace) * Math.sin(time * pace * 0.37 + 1));
        float radius = base * (1f + amplitude * breath);
        canvas.drawCircle(cx, cy, base * (1.25f + amplitude * 2 * breath), glow);
        // A slow turn of the colors; fast while connecting, a steady swirl while thinking.
        float degrees = time * (state == State.CONNECTING ? 220f : state == State.THINKING ? 140f : state == State.MUTED ? 0f : 28f);
        turn.setRotate(degrees, cx, cy); orb.getShader().setLocalMatrix(turn);
        canvas.drawCircle(cx, cy, radius, orb);
        canvas.drawCircle(cx, cy, radius, shine);
    }
}
