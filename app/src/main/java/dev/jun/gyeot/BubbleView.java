package dev.jun.gyeot;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 들퍼레's floating button, drawn: a warm dark glass orb with a softly glowing dusk rim and voice bars, and, while phone
 * control is on, a small dark moon with a white stop mark floating clear of it above, toward the middle of the screen. The orb
 * moves while the call listens, speaks, connects or thinks, at most FPS frames a second (CALM_FPS
 * while listening to a quiet room) over a cached still body, and only with the screen on and unlocked; muted, idle or offline
 * it is still, so it costs nothing between state changes.
 */
final class BubbleView {
    /** Orb radius, moon radius, the gap between them, room around each for shadow and glow, and the moon's angle above level. */
    static final float ORB = 24, MOON = 11, GAP = 6, ORB_PAD = 6, MOON_PAD = 4, ANGLE = 52;
    /** Dusk, matching the voice orb: amber, coral, rose, blush and gold. */
    private static final int[] RIM = {0xffffb36b, 0xffff7f5c, 0xffe8607e, 0xfff59ab0, 0xffffc861, 0xffffb36b};
    private static final int[] RIM_MUTED = {0xff8f8a82, 0xffc9c4ba, 0xff8f8a82, 0xffc9c4ba, 0xff8f8a82};
    private BubbleView() {}

    static int px(Context context, float dp) { return Math.round(dp * context.getResources().getDisplayMetrics().density); }
    /** The orb view's side, the moon view's side. */
    static int orbSide(Context context) { return px(context, 2 * (ORB + ORB_PAD)); }
    static int moonSide(Context context) { return px(context, 2 * (MOON + MOON_PAD)); }
    /** The moon's centre from the orb's centre, as {sideways, up}, in dp. */
    private static float[] moonOffset() {
        double distance = ORB + GAP + MOON, angle = Math.toRadians(ANGLE);
        return new float[]{(float) (distance * Math.cos(angle)), (float) (distance * Math.sin(angle))};
    }
    /** The window's {width, height} for the orb alone or with its moon. */
    static int[] window(Context context, boolean withMoon) {
        int orb = orbSide(context);
        if (!withMoon) return new int[]{orb, orb};
        float[] offset = moonOffset(); float half = ORB + ORB_PAD, moonHalf = MOON + MOON_PAD;
        return new int[]{Math.max(orb, px(context, half + offset[0] + moonHalf)), Math.max(orb, px(context, half + offset[1] + moonHalf))};
    }
    /** Lays the orb out in the bottom corner and the moon above it on the side facing the middle of the screen. */
    static void place(Orb orb, Moon moon, boolean moonLeft, boolean withMoon) {
        Context context = orb.getContext(); int side = orbSide(context), moonSide = moonSide(context);
        int corner = Gravity.BOTTOM | (moonLeft ? Gravity.END : Gravity.START);
        FrameLayout.LayoutParams orbParams = new FrameLayout.LayoutParams(side, side, corner);
        orb.setLayoutParams(orbParams);
        float[] offset = moonOffset(); float half = ORB + ORB_PAD, moonHalf = MOON + MOON_PAD;
        FrameLayout.LayoutParams moonParams = new FrameLayout.LayoutParams(moonSide, moonSide, corner);
        int sideways = px(context, half + offset[0] - moonHalf), up = px(context, half + offset[1] - moonHalf);
        if (moonLeft) moonParams.rightMargin = sideways; else moonParams.leftMargin = sideways;
        moonParams.bottomMargin = up;
        moon.setLayoutParams(moonParams); moon.setVisibility(withMoon ? View.VISIBLE : View.GONE);
    }

    /** The bars' heights at rest (0 lowest, 1 tallest), as the button has always drawn them. */
    static final float[] STILL = {0.18f, 0.49f, 0.32f};
    /** Each bar's reach above its lowest, in dp: the middle one is the tallest. */
    private static final float[] REACH = {5, 8, 6};
    /**
     * Frames a second while something visibly moves (speaking, connecting, thinking, a voice heard), and while listening to a
     * quiet room, the call's resting state, where the ripple moves under half a pixel a frame: the button stays on screen for
     * the whole call.
     */
    static final int FPS = 30, CALM_FPS = 12;
    /** The next frame is asked for this much early, so it lands on the vsync at its interval, not the one after (even pacing). */
    static final long EARLY_MS = 4;
    /** A pause in an answer's sound shorter than this (a slow network) still shows speaking while its text is coming. */
    static final long SOUND_GAP_MS = 800;

    /**
     * How loud a microphone chunk is, 0 (room quiet, -52 dBFS or less) to 1 (close speech, -16 dBFS or more). 16-bit
     * little-endian mono PCM; every fourth sample is enough for a level meter.
     */
    static float loudness(byte[] pcm, int length) {
        long sum = 0; int n = 0;
        for (int i = 0; i + 1 < length; i += 8) { int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)); sum += (long) sample * sample; n++; }
        if (n == 0) return 0;
        double rms = Math.sqrt((double) sum / n), db = 20 * Math.log10(Math.max(1, rms) / 32768.0);
        return (float) Math.max(0, Math.min(1, (db + 52) / 36));
    }
    /**
     * The three bars' heights (0 to 1) for a voice state at a time (seconds) and a heard level (0 to 1): listening, they
     * ripple softly and rise with the user's voice; speaking, they bounce; connecting, a low wave runs across them; muted,
     * idle or offline, they rest. Thinking draws dots instead.
     */
    static float[] bars(VoiceOrb.State state, float time, float level) {
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            float ripple = 0.16f + 0.14f * (float) (0.5 + 0.5 * Math.sin(time * 3.2 - i * 0.9));
            out[i] = switch (state) {
                case LISTENING -> Math.min(1f, ripple + level * (0.8f + 0.2f * (float) Math.sin(time * 9 + i * 2.1)));
                case SPEAKING -> 0.3f + 0.7f * (float) Math.abs(Math.sin(time * 6.5 + i * 1.3)) * (0.65f + 0.35f * (float) Math.sin(time * 2.3 + i));
                case CONNECTING -> 0.12f + 0.4f * (float) Math.max(0, Math.sin(time * 5 - i * 1.1));
                default -> STILL[i];
            };
        }
        return out;
    }
    /** The wait before the next frame: quiet listening (no voice heard or still showing) at CALM_FPS, anything else at FPS. */
    static long interval(VoiceOrb.State state, boolean thinking, float heard, float shown) {
        boolean calm = !thinking && state == VoiceOrb.State.LISTENING && heard < 0.15f && shown < 0.08f;
        return 1000L / (calm ? CALM_FPS : FPS);
    }
    /** Eases the shown level toward the heard one over dt seconds: up quickly with the voice, down slowly, at any frame rate. */
    static float ease(float shown, float target, float dt) {
        return shown + (target - shown) * (1 - (float) Math.pow(target > shown ? 0.45 : 0.82, dt * 30));
    }

    /**
     * The orb: dark glass, an iridescent rim with an inner glow, a soft highlight, and three voice bars (slashed when muted).
     * It shows the call's state (VoiceOrb.of, as the voice screen's orb does) so the user can see it is listening: listening,
     * a coral halo breathes and the bars follow the microphone; speaking, the bars bounce and the halo glows; connecting, a low
     * wave runs across the bars; thinking, the rim turns and three dots pulse. Muted, idle or offline it is still and draws
     * nothing per frame. Frames come one at a time from the view's animation clock (postOnAnimationDelayed), only while
     * something moves, the button is shown, the screen is on and unlocked (awake; an overlay is hidden on the lock screen) and
     * animations are on. The glass, rim and highlight are drawn once into a bitmap; frames add only the halo and the bars.
     */
    static final class Orb extends View {
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), shine = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG),
            halo = new Paint(Paint.ANTI_ALIAS_FLAG), copy = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Path clip = new Path();
        private final float density;
        private boolean muted, thinking;
        /** The screen is on and unlocked (FloatingButton follows it); hidden as Android aggregates it (an off window, a GONE parent). */
        private boolean awake = true, hidden;
        private VoiceOrb.State voice = VoiceOrb.State.IDLE;
        /**
         * Whether the call's answer is sounding from its speaker, asked each frame, and when it last did. Speaking is what the
         * user hears: Gemini finishes an answer's text (the call turns back to listening) seconds before its sound has played,
         * and a caption piece arriving after the turn keeps the text without any sound. Null: the call's state as it is.
         */
        private java.util.function.BooleanSupplier playing;
        private boolean sounding; private long soundAt = Long.MIN_VALUE / 2;
        /** The microphone's last level and when it came (from the microphone's thread), and the level shown, eased per frame. */
        private volatile float heard; private volatile long heardAt;
        private float shown;
        /** One frame, then the next after interval(); ticking while frames are scheduled. */
        private final Runnable frame = this::frame;
        private boolean ticking;
        private final long start = android.os.SystemClock.uptimeMillis();
        private long frameAt;
        private float time;
        /** Animations turned off or back on mid-call (accessibility, a battery saver). Held here: Android keeps it weakly. */
        private final android.animation.ValueAnimator.DurationScaleChangeListener scale = value -> post(this::animateNow);
        /** The glass, rim and highlight at rest, drawn once for a size and mute. */
        private android.graphics.Bitmap still; private int stillKey;
        Orb(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            // Blur needs software drawing; the view is small, and its blurred parts are drawn once into a bitmap.
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            shadow.setColor(0x55000000); shadow.setMaskFilter(new BlurMaskFilter(3 * density, BlurMaskFilter.Blur.NORMAL));
            glow.setStyle(Paint.Style.STROKE); glow.setStrokeWidth(5 * density); glow.setMaskFilter(new BlurMaskFilter(3 * density, BlurMaskFilter.Blur.NORMAL));
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(2 * density);
            shine.setColor(0x22ffffff); shine.setMaskFilter(new BlurMaskFilter(2 * density, BlurMaskFilter.Blur.NORMAL));
            mark.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true); setFocusable(true);
        }
        void muted(boolean value) { if (muted != value) { muted = value; invalidate(); animateNow(); } }
        /**
         * The brain is thinking (FloatingButton.thinking). The dots themselves follow the call's THINKING (VoiceOrb.of): this
         * only tells a spoken answer's text without sound apart (thinking, not listening), since VoiceOrb.of puts speaking first.
         */
        void thinking(boolean value) { if (thinking != value) { thinking = value; invalidate(); animateNow(); } }
        /** The call's state (VoiceOrb.of). */
        void voice(VoiceOrb.State value) { if (voice != value) { voice = value; invalidate(); animateNow(); } }
        /** The screen is on and unlocked: only then can anyone see the button move. */
        void awake(boolean value) { if (awake != value) { awake = value; animateNow(); } }
        /** Where to ask whether an answer is sounding (the voice call's speaker). */
        void playing(java.util.function.BooleanSupplier value) { playing = value; }
        /** The microphone's level, 0 to 1 (loudness); safe from the microphone's thread. */
        void level(float value) { heard = value; heardAt = android.os.SystemClock.uptimeMillis(); }
        /** Whether anything moves now: a call that listens, speaks, connects or thinks. */
        boolean moving() { return !muted && (voice == VoiceOrb.State.LISTENING || voice == VoiceOrb.State.SPEAKING || voice == VoiceOrb.State.CONNECTING || voice == VoiceOrb.State.THINKING); }
        /** Whether frames are being drawn. */
        boolean ticking() { return ticking; }
        private void animateNow() {
            boolean run = awake && !hidden && moving() && isAttachedToWindow() && isShown() && android.animation.ValueAnimator.areAnimatorsEnabled();
            if (run == ticking) return;
            ticking = run; removeCallbacks(frame);
            if (run) { frameAt = 0; postOnAnimation(frame); }
            // A still frame is the resting one: never a voice caught mid-word, held until the next change.
            else { shown = 0; sounding = false; invalidate(); }
        }
        private void frame() {
            if (!ticking) return;
            long now = android.os.SystemClock.uptimeMillis();
            float dt = frameAt == 0 ? 1f / FPS : Math.min(0.25f, (now - frameAt) / 1000f);
            frameAt = now; time = (now - start) / 1000f;
            // A level older than 0.4 s is silence (a muted microphone sends nothing).
            float target = now - heardAt < 400 ? heard : 0;
            shown = ease(shown, target, dt);
            sounding = playing != null && playing.getAsBoolean(); if (sounding) soundAt = now;
            invalidate();
            postOnAnimationDelayed(frame, interval(drawn(), dots(), target, shown) - EARLY_MS);
        }
        /**
         * The state drawn: the call's (VoiceOrb.of), as on the voice screen, except that speaking follows the answer's sound:
         * while it sounds (also after its text is done), and while its text is still coming with the sound paused for under
         * SOUND_GAP_MS. Text with no sound (a caption piece after the turn) is not speaking: the call is listening, or thinking
         * (the brain's flag, since VoiceOrb.of puts speaking over thinking).
         */
        VoiceOrb.State drawn() {
            if (muted) return VoiceOrb.State.MUTED;
            boolean talk = voice == VoiceOrb.State.LISTENING || voice == VoiceOrb.State.SPEAKING || voice == VoiceOrb.State.THINKING;
            if (playing == null || !talk) return voice;
            if (sounding || voice == VoiceOrb.State.SPEAKING && android.os.SystemClock.uptimeMillis() - soundAt < SOUND_GAP_MS) return VoiceOrb.State.SPEAKING;
            return voice != VoiceOrb.State.SPEAKING ? voice : thinking ? VoiceOrb.State.THINKING : VoiceOrb.State.LISTENING;
        }
        /** Thinking dots: the brain is thinking and nothing is being said (speaking shows over thinking, as in VoiceOrb.of). */
        boolean dots() { return drawn() == VoiceOrb.State.THINKING; }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            android.animation.ValueAnimator.registerDurationScaleChangeListener(scale);
            animateNow();
        }
        @Override public void onVisibilityAggregated(boolean visible) { super.onVisibilityAggregated(visible); hidden = !visible; animateNow(); }
        /** This view or a parent shown or gone (isShown follows it), dispatched whatever the window's own visibility. */
        @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); animateNow(); }
        @Override protected void onDetachedFromWindow() {
            android.animation.ValueAnimator.unregisterDurationScaleChangeListener(scale);
            ticking = false; removeCallbacks(frame); still = null;
            super.onDetachedFromWindow();
        }

        /** Shadow, glass, the rim's inner glow and ring (turned by degrees), and the highlight. */
        private void body(Canvas canvas, float cx, float cy, float r, float degrees, int glowAlpha) {
            canvas.drawCircle(cx, cy + 2.5f * density, r, shadow);
            base.setShader(new RadialGradient(cx - r * 0.35f, cy - r * 0.45f, r * 1.6f, 0xff3b3631, 0xff0d0b09, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, base);
            SweepGradient sweep = new SweepGradient(cx, cy, muted ? RIM_MUTED : RIM, null);
            Matrix turn = new Matrix(); turn.setRotate(degrees, cx, cy); sweep.setLocalMatrix(turn);
            canvas.save(); clip.reset(); clip.addCircle(cx, cy, r, Path.Direction.CW); canvas.clipPath(clip);
            glow.setShader(sweep); glow.setAlpha(glowAlpha); canvas.drawCircle(cx, cy, r - density, glow);
            canvas.restore();
            ring.setShader(sweep); canvas.drawCircle(cx, cy, r - density, ring);
            canvas.drawOval(cx - r * 0.55f, cy - r * 0.82f, cx + r * 0.35f, cy - r * 0.25f, shine);
        }
        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = ORB * density;
            if (getWidth() <= 0 || getHeight() <= 0) return;
            if (dots()) {
                // Thinking: the colors go round the rim (a turn every 1.6 s) and the inner glow breathes.
                body(canvas, cx, cy, r, -90 + time * 225f, muted ? 70 : Math.round(170 + 60 * (float) Math.sin(time * 4)));
                // Three dots lighting up one after another, as in a chat that is typing.
                mark.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++) {
                    float phase = (float) Math.max(0, Math.sin(time * 6 - i * 0.9));
                    mark.setColor(Color.argb(Math.round(110 + 145 * phase), 255, 255, 255));
                    canvas.drawCircle(cx + (i - 1) * 6.5f * density, cy - phase * 1.6f * density, (2.1f + 0.7f * phase) * density, mark);
                }
                return;
            }
            VoiceOrb.State state = drawn();
            // The halo: a coral glow around the glass that breathes while listening and swells with the voice.
            float glowing = switch (state) {
                case LISTENING -> 0.22f + 0.12f * (float) Math.sin(time * 2.4) + 0.6f * shown;
                case SPEAKING -> 0.45f + 0.3f * (float) Math.abs(Math.sin(time * 3.1));
                case CONNECTING -> 0.15f + 0.15f * (float) (0.5 + 0.5 * Math.sin(time * 3));
                default -> 0f;
            };
            float outer = r + ORB_PAD * density;
            int key = getWidth() * 31 + getHeight() * 7 + (muted ? 1 : 0);
            if (still == null || stillKey != key) {
                still = android.graphics.Bitmap.createBitmap(getWidth(), getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                body(new Canvas(still), cx, cy, r, -90, muted ? 70 : 140); stillKey = key;
                halo.setShader(new RadialGradient(cx, cy, outer, new int[]{0xffff8a5c, 0xffff8a5c, 0x00ff8a5c}, new float[]{0f, (r - density) / outer, 1f}, Shader.TileMode.CLAMP));
            }
            if (glowing > 0) { halo.setAlpha(Math.round(255 * Math.min(1f, glowing))); canvas.drawCircle(cx, cy, outer, halo); }
            canvas.drawBitmap(still, 0, 0, copy);
            // Voice bars; muted, they dim and a slash crosses them.
            mark.setStyle(Paint.Style.STROKE); mark.setStrokeWidth(2.6f * density); mark.setColor(muted ? 0x73ffffff : 0xf0ffffff);
            float[] heights = bars(state, time, shown);
            for (int i = 0; i < 3; i++) { float x = cx + (i - 1) * 5 * density, h = (1.3f + heights[i] * REACH[i]) * density; canvas.drawLine(x, cy - h, x, cy + h, mark); }
            if (muted) { mark.setColor(0xd9ffffff); mark.setStrokeWidth(2 * density); canvas.drawLine(cx - 8 * density, cy - 8 * density, cx + 8 * density, cy + 8 * density, mark); }
        }
    }

    /** The moon: a small dark glass disc with a hairline edge and a white stop mark, or a play mark while control is paused. */
    static final class Moon extends View {
        private boolean paused;
        private final android.graphics.Path play = new android.graphics.Path();
        void paused(boolean value) { if (paused != value) { paused = value; invalidate(); } }
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), stop = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        Moon(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            shadow.setColor(0x50000000); shadow.setMaskFilter(new BlurMaskFilter(2 * density, BlurMaskFilter.Blur.NORMAL));
            edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(density); edge.setColor(0x33ffffff);
            stop.setColor(0xebffffff);
            setClickable(true); setFocusable(true);
        }
        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = MOON * density, q = 3.5f * density;
            canvas.drawCircle(cx, cy + 2 * density, r, shadow);
            base.setShader(new RadialGradient(cx - r * 0.4f, cy - r * 0.5f, r * 1.7f, 0xff403a34, 0xff14110e, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, base);
            canvas.drawCircle(cx, cy, r - density / 2, edge);
            if (!paused) { canvas.drawRoundRect(cx - q, cy - q, cx + q, cy + q, 1.6f * density, 1.6f * density, stop); return; }
            // ▶: phone control is paused; a tap turns it back on.
            float h = 4.2f * density;
            play.reset(); play.moveTo(cx - h * 0.7f, cy - h); play.lineTo(cx + h, cy); play.lineTo(cx - h * 0.7f, cy + h); play.close();
            canvas.drawPath(play, stop);
        }
    }
}
