/*
 * Ported from open-bot-avatar 0.2.0 (https://github.com/DominikScholz/open-bot-avatar), MIT License,
 * Copyright (c) 2026 Dominik Scholz. The port is this file and the BotAvatar*.java files beside it.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.json.JSONObject;

/**
 * Open Bot Avatar engine in pure Java: validates avatar definitions (one SVG path in a 0 0 100 100
 * box plus where the eyes sit), infers their volume as stacked cross-sections, and computes each
 * animation frame as plain data (paths, 2D matrices, eye marks) for any vector painter, or as a
 * standalone SVG. Numerically identical to the TypeScript original (golden tests in
 * tests/BotAvatarTests.java). Everything is deterministic: a frame is a pure function of the
 * definition and {@link Options}. The first frame of a definition at a depth builds its volume
 * (tens of milliseconds; warm it off the UI thread with {@link #frame} if that matters); it is
 * cached (64 definition/depth pairs) and the API is safe to call from several threads.
 *
 * <p>Painting a {@link Frame}: inside a 0 0 100 100 viewBox, concatenate {@link Frame#root} with
 * each shape's {@link Shape#matrix} and paint the shapes in list order (back to front): slices
 * fill their {@link FramePath} in {@link Frame#color} (even-odd when {@link FramePath#evenOdd}),
 * eyes in {@link Frame#ink} (a rounded rect for "pill", a round-capped stroke of {@link Shape#arc}
 * for "arc"). {@link #commands} turns any path string into M/L/C/Z for android.graphics.Path.
 */
public final class BotAvatar {
    private BotAvatar() {}

    public static final String DEFAULT_COLOR = "#ff6a1a", DEFAULT_EYE_COLOR = "#151515";
    /** The depth option value that maps to the natural inferred volume (0 = flat, 14 = deepest). */
    public static final double DEFAULT_DEPTH = 6;
    /** Resting view, radians: turned a little and seen a little from above. */
    public static final double BASE_YAW = -0.3, BASE_PITCH = 0.2;
    /**
     * The app's own moods beyond open-bot-avatar's idle, thinking and happy: each a body motion and an eye shape
     * ("wide", "sad", "sleepy", "wink", "heart", "angry"). The library's three render exactly as upstream.
     */
    public static final java.util.List<String> EXTRA_MOODS = java.util.List.of("surprised", "sad", "sleepy", "wink", "love", "angry");
    /** Every mood the engine draws, in the editor's order. */
    public static final java.util.List<String> MOODS = java.util.List.of("idle", "thinking", "happy", "surprised", "sad", "sleepy", "wink", "love", "angry");
    /** A mood name the engine draws, else "idle". */
    public static String mood(String value) { return value != null && MOODS.contains(value) ? value : "idle"; }
    /** The heart eyes' colour, unless it would vanish into the body (then the eye ink). */
    public static final String HEART_COLOR = "#ff3d7f";

    /** Eye pair: center x/y, center-to-center spacing, scale (1 = each neutral eye about 4 x 11 units). */
    public record Eyes(double x, double y, double spacing, double scale) {}

    /** A definition (contract v1): lowercase slug id, display name, one body path, the eyes. */
    public record Definition(String id, String name, String path, Eyes eyes) {
        /** {"version":1,"id","name","body":{"path"},"eyes":{"x","y","spacing","scale"}}. */
        public JSONObject json() {
            return Jsons.obj("version", 1, "id", id, "name", name, "body", Jsons.obj("path", path),
                "eyes", eyes == null ? JSONObject.NULL : Jsons.obj("x", eyes.x(), "y", eyes.y(), "spacing", eyes.spacing(), "scale", eyes.scale()));
        }
    }

    /**
     * validateAvatar: the errors, empty when valid. Accepts a JSONObject (or any org.json value), a
     * String of JSON text (unparseable text gives one "definition is not valid JSON" error), a
     * java.util.Map/List structure or a {@link Definition}. Several unknown fields are reported in
     * JavaScript key order; JSON text always gives that order, a JSONObject gives its own key
     * order (insertion order on Android, hash order in the desktop org.json jar).
     */
    public static List<String> validate(Object json) { return Collections.unmodifiableList(check(json).errors); }

    /** Parses and validates; the normalized definition (path re-serialized, name trimmed). Throws IllegalArgumentException with the errors joined by "; ". */
    public static Definition parse(Object json) {
        BotAvatarValidate.Result r = check(json);
        if (r.value == null) throw new IllegalArgumentException(String.join("; ", r.errors));
        return r.value;
    }

    private static BotAvatarValidate.Result check(Object json) {
        Object input;
        if (json instanceof Definition) input = BotAvatarRender.plain((Definition) json);
        else if (json instanceof String) {
            try { input = BotAvatarJs.parseJson((String) json); }
            catch (IllegalArgumentException bad) { return new BotAvatarValidate.Result(new ArrayList<>(List.of("definition is not valid JSON (" + bad.getMessage() + ")")), null); }
        } else input = BotAvatarJs.plain(json);
        return BotAvatarValidate.validate(input);
    }

    /** The built-in starter avatars (examples.ts), in order. */
    public static List<Definition> examples() { return BotAvatarExamples.ALL; }

    /** getExample: the built-in avatar with this id, or null. */
    public static Definition example(String id) {
        for (Definition d : BotAvatarExamples.ALL) if (d.id().equals(id)) return d;
        return null;
    }

    /** RenderOptions. Mutable; every field has the TypeScript default. */
    public static final class Options {
        /** Body and eye color, #rgb or #rrggbb; anything else falls back to the default. */
        public String color = DEFAULT_COLOR, eyeColor = DEFAULT_EYE_COLOR;
        /** depth: volume 0..14 (rounded to halves); time: animation clock, seconds; yaw/pitch: extra view rotation, radians (RenderOptions.view). */
        public double depth = DEFAULT_DEPTH, time = 0, yaw = 0, pitch = 0;
        /** Rendered size, CSS px / dp; 0 = not given. liveFrame drops slices closer than 0.75 px apart; svg writes width/height. */
        public double size = 0;
        /** idle | thinking | happy (anything else is idle). */
        public String motion = "idle";
        /** Look target; null = idle eye wander. */
        public Gaze gaze;
        /** Easing into motion from the mood shown before; null once done. */
        public Blend motionBlend;
        /** Freeze body motion and blinking at a calm pose; gaze still applies. */
        public boolean reducedMotion;
    }

    /** A look target, each axis -1..1 (x right, y down); weight 0..1 blends it over the idle pose (non-finite = 1). */
    public record Gaze(double x, double y, double weight) {
        public Gaze(double x, double y) { this(x, y, 1); }
    }

    /**
     * AvatarMotionBlend: the current motion easing in (t 0..1) over what was shown before: a plain
     * motion ({@code fromBlend == null}) or a motion that was itself still easing in (its own blend
     * frozen where it was).
     */
    public record Blend(String fromMotion, Blend fromBlend, double t) {
        public Blend(String fromMotion, double t) { this(fromMotion, null, t); }
    }

    /** avatarFrame: one frame as plain data. Throws IllegalArgumentException if the definition is invalid. */
    public static Frame frame(Definition d, Options o) { return BotAvatarRender.frame(d, o, Double.NaN); }

    /** liveFrame: avatarFrame for a live avatar {@code o.size} px wide (when > 0): slices closer than 0.75 px are skipped. */
    public static Frame liveFrame(Definition d, Options o) { return BotAvatarRender.frame(d, o, o != null ? o.size : Double.NaN); }

    /** renderAvatarSvg: a standalone SVG still of {@link #frame}. */
    public static String svg(Definition d, Options o) { return BotAvatarRender.svg(d, o); }

    /** avatarExtent: {x, y, size}, a square of viewBox units that holds the avatar at every view and pose (non-finite depth = default). */
    public static double[] extent(Definition d, double depth) { return BotAvatarRender.extent(d, depth); }

    /**
     * One frame flattened onto the screen. Paint {@link #shapes} in order inside {@link #root} and a
     * 0 0 100 100 viewBox: slices in {@link #color}, eyes in {@link #ink}.
     */
    public static final class Frame {
        /** The definition's (trimmed) name, and the colors as #rrggbb. */
        public String name, color, ink;
        /** The distinct cross-section paths this frame draws; ids are stable for equal geometry and depth. */
        public List<FramePath> paths;
        /** 2D motion (hop, roll, squash) about the base of the body, a b c d e f; applies to every shape. */
        public double[] root;
        /** Back to front. */
        public List<Shape> shapes;
        /**
         * Not in the TypeScript API: the matrix (a b c d e f, before {@link #root}, viewBox units) of
         * the face plane the eyes are painted on: the eye-plane projection at the eye pair's mean
         * depth plus the eye lift, which is exactly each eye shape's matrix whenever both eyes sit at
         * one depth (true for every built-in and gallery avatar). Eye marks and any face decoration in
         * definition coordinates map through it. Null when no eye is visible (seen from behind).
         */
        public double[] facePlane;

        public FramePath path(String id) {
            for (FramePath p : paths) if (p.id().equals(id)) return p;
            return null;
        }
    }

    /** One cross-section outline shared by every shape that draws it; x y w h bound d in its own coordinates. */
    public record FramePath(String id, String d, boolean evenOdd, double x, double y, double w, double h) {}

    /**
     * One painted shape. kind "slice": key ("{part}{stack}{slice}", stable across frames), path (a
     * {@link FramePath} id), matrix, and part/slice/stack parsed from the key. kind "eye": key
     * "eye-0" (left) or "eye-1", matrix, and a mark: "pill" (rounded rect x y width height radius)
     * or "arc" (stroke {@link #arc} {@link #strokeWidth} wide with round caps), in face-plane units.
     */
    public static final class Shape {
        public String kind, key, path;
        /** a b c d e f, before the frame's root. */
        public double[] matrix;
        /** Eyes only: "pill", "arc" (stroked), or "path" (filled {@link #arc} path data, the extra moods' tilted and heart eyes). */
        public String mark;
        /** Eyes only: a fill colour of its own (the heart eyes), else null for the frame's ink. */
        public String color;
        public double x, y, width, height, radius;
        public String arc;
        public double strokeWidth;
        /** Slices: part index, slice index within the stack, and 'z' or 'x' (the stack); -1, -1, '\0' for eyes. */
        public int part = -1, slice = -1;
        public char stack;
    }

    /**
     * Normalized absolute commands for path data the engine produces or accepts (M L H V C S Q T A
     * Z, absolute or relative, no size limits): entries {op, args...} with op 'M' (x y), 'L' (x y),
     * 'C' (x1 y1 x2 y2 x y) or 'Z' as a char code. Quadratics are raised to cubics exactly, arcs
     * become cubics of at most 90 degrees, H V S T are expanded, and open subpaths stay open (the
     * happy-eye arc is stroked). Throws IllegalArgumentException for malformed data.
     */
    public static List<double[]> commands(String d) { return BotAvatarPath.commands(d); }

    // ---------------------------------------------------------------------------------------------
    // Motion (motion.ts)

    /** A pose: view yaw/pitch (radians), roll (degrees), lift (viewBox units, negative = up), squash sx/sy, gaze -1..1, blink and squints 0..1, eye shape "pill" or "happy" (or an extra mood's shape). */
    public record Pose(double yaw, double pitch, double roll, double lift, double sx, double sy, double gazeX, double gazeY,
                       double blink, double squintL, double squintR, String eyeShape) {}

    /** computePose: seed is hashString(definition id); view yaw/pitch are added to the resting view. */
    public static Pose computePose(long seed, double time, String motion, Gaze gaze, boolean reducedMotion, double viewYaw, double viewPitch, Blend blend) {
        return BotAvatarMotion.computePose(seed, time, motion, gaze, reducedMotion, viewYaw, viewPitch, blend);
    }

    /** The pose {@link #frame} uses for these options (seeded by the definition's id). */
    public static Pose pose(Definition d, Options o) {
        Options options = o != null ? o : new Options();
        return BotAvatarRender.poseOf(BotAvatarRender.prepare(d, BotAvatarRender.depthOption(options.depth)), options);
    }

    /** blendPoses: part way from a to b; a change of eye shape swaps halfway inside a blink. */
    public static Pose blendPoses(Pose a, Pose b, double t) { return BotAvatarMotion.blendPoses(a, b, t); }

    /** FNV-1a of the UTF-16 code units, unsigned 32-bit. */
    public static long hashString(String s) { return BotAvatarMotion.hashString(s); }

    // ---------------------------------------------------------------------------------------------
    // Moods (mood.ts)

    /** A mood on screen, possibly still easing in over what came before ({@code blend} null when settled). */
    public record MoodState(String motion, Blend blend) {}

    /** mood.ts: mood changes ease over {@link #MS} with a smoothstep. */
    public static final class Mood {
        private Mood() {}
        /** MOOD_MS: length of a mood change, ms. */
        public static final double MS = 420;

        /** moodProgress: eased progress of a change from elapsed ms (smoothstep, clamped 0..1). */
        public static double progress(double elapsedMs) {
            double x = Math.min(1, Math.max(0, elapsedMs / MS));
            return x * x * (3 - 2 * x);
        }

        /** settleMood: what a new change eases in from; faded or deep (3 levels) history is dropped. */
        public static MoodState settle(MoodState state) { return settle(state, 1, 0); }

        public static MoodState settle(MoodState state, double share, int depth) {
            Blend b = state.blend();
            if (b == null) return state;
            double rest = share * (1 - b.t());
            if (rest < 0.02 || depth >= 3) return new MoodState(state.motion(), null);
            if (b.fromBlend() == null) return new MoodState(state.motion(), new Blend(b.fromMotion(), null, b.t()));
            MoodState from = settle(new MoodState(b.fromMotion(), b.fromBlend()), rest, depth + 1);
            return new MoodState(state.motion(), new Blend(from.motion(), from.blend(), b.t()));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Following a look target (follow.ts)

    /** The eased look: x/y -1..1 and attention 0 (at rest) .. 1 (fully following). */
    public record FollowState(double x, double y, double attention) {}

    /** follow.ts: pointer to look direction, and the per-frame easing toward it. */
    public static final class Follow {
        private Follow() {}
        public static final FollowState REST = new FollowState(0, 0, 0);
        private static final double TAU = 0.12, ATTEND_TAU = 0.3, REACH = 1, MIN_SIZE = 160;

        /** followStep result: the next state and whether it still needs frames. */
        public record Step(FollowState state, boolean moving) {}

        /**
         * pointerGaze: look direction (-1..1, x right, y down; weight 1) toward a pointer at (px, py)
         * from the avatar's box (same units, e.g. px), starting at origin (fractions of the box; the
         * eyes, see {@link #eyesOrigin}).
         */
        public static Gaze pointerGaze(double px, double py, double left, double top, double width, double height, double originX, double originY) {
            double reach = Math.max(Math.max(width, height), MIN_SIZE) * REACH;
            double dx = (px - (left + width * originX)) / reach;
            double dy = (py - (top + height * originY)) / reach;
            double m = BotAvatarJs.hypot(dx, dy);
            if (Double.isNaN(m) || Double.isInfinite(m) || m < 1e-9) return new Gaze(0, 0, 1);
            double k = StrictMath.tanh(m) / m;
            return new Gaze(dx * k, dy * k, 1);
        }

        /** pointerGaze from the box center. */
        public static Gaze pointerGaze(double px, double py, double left, double top, double width, double height) {
            return pointerGaze(px, py, left, top, width, height, 0.5, 0.5);
        }

        /** Where the look starts, as fractions of the avatar's box: the eye pair's center (as the web mount uses it). */
        public static double[] eyesOrigin(Definition d) {
            Eyes e = d == null ? null : d.eyes();
            return e == null ? new double[] {0.5, 0.5} : new double[] {at(e.x()), at(e.y())};
        }

        private static double at(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? 0.5 : Math.min(1, Math.max(0, v / 100)); }

        /** followStep: one frame-rate independent easing step toward target (null = back to rest; weight ignored); reduced snaps. dt in seconds. */
        public static Step step(FollowState state, Gaze target, double dt, boolean reduced) {
            FollowState goal = target != null ? new FollowState(target.x(), target.y(), 1) : REST;
            double step = Math.max(0, Math.min(dt, 0.1));
            double k = ease(target != null ? TAU : ATTEND_TAU, step, reduced);
            FollowState next = new FollowState(state.x() + (goal.x() - state.x()) * k, state.y() + (goal.y() - state.y()) * k,
                state.attention() + (goal.attention() - state.attention()) * ease(ATTEND_TAU, step, reduced));
            boolean moving = Math.abs(goal.x() - next.x()) > 0.002 || Math.abs(goal.y() - next.y()) > 0.002 || Math.abs(goal.attention() - next.attention()) > 0.002;
            return new Step(moving ? next : goal, moving);
        }

        private static double ease(double tau, double step, boolean reduced) { return reduced ? 1 : 1 - StrictMath.exp(-step / tau); }

        /** followGaze: the render gaze for a state (weight = smoothstep of attention), null at rest. */
        public static Gaze gaze(FollowState state) {
            double a = Math.min(1, Math.max(0, state.attention()));
            return a > 0 ? new Gaze(state.x(), state.y(), a * a * (3 - 2 * a)) : null;
        }
    }

    /**
     * createAnimator: the per-avatar state a live renderer keeps between frames (the eased look and
     * the eased mood change). Per frame: {@code Step s = animator.step(nowMs, mood, target, hold,
     * reduced)} with nowMs a wall clock (e.g. SystemClock.uptimeMillis()), target a look direction
     * (e.g. {@link Follow#pointerGaze}) or null; then set {@code options.gaze = s.gaze()},
     * {@code options.motionBlend = s.motionBlend()}, {@code options.motion = mood},
     * {@code options.time} (animation clock, seconds), {@code options.size} and paint
     * {@link #liveFrame}. Keep requesting frames while {@code !reduced || s.moving()}. Not thread-safe.
     */
    public static final class Animator {
        private FollowState follow = Follow.REST;
        private double last;
        private boolean started, hasMood;
        private String moodMotion;
        /** The settled mood the current change eases in from; null when it is not easing. */
        private MoodState moodFrom;
        private double since;

        public Animator() {}

        /** One frame's gaze and motionBlend render options (null exactly where the TypeScript returns undefined) and whether it is still easing. */
        public record Step(Gaze gaze, Blend motionBlend, boolean moving) {}

        /** One frame: nowMs wall clock in ms; motion idle|thinking|happy; target the look target (-1..1, null eases back to rest; weight ignored); hold keeps the current look. */
        public Step step(double nowMs, String motion, Gaze target, boolean hold, boolean reducedMotion) {
            double dt = started ? (nowMs - last) / 1000 : 0;
            last = nowMs;
            started = true;
            boolean moving = false;
            if (!hold) {
                Follow.Step s = Follow.step(follow, target, dt, reducedMotion);
                follow = s.state();
                moving = s.moving();
            }
            if (hasMood && !Objects.equals(motion, moodMotion)) {
                MoodState shown = new MoodState(moodMotion, moodFrom == null ? null : new Blend(moodFrom.motion(), moodFrom.blend(), Mood.progress(nowMs - since)));
                moodFrom = reducedMotion ? null : Mood.settle(shown);
                moodMotion = motion;
                since = nowMs;
            }
            if (!hasMood) {
                hasMood = true;
                moodMotion = motion;
                moodFrom = null;
                since = nowMs;
            }
            double t = reducedMotion ? 1 : Mood.progress(nowMs - since);
            Blend motionBlend = null;
            if (moodFrom != null && t < 1) motionBlend = new Blend(moodFrom.motion(), moodFrom.blend(), t);
            else moodFrom = null;
            return new Step(Follow.gaze(follow), motionBlend, moving || motionBlend != null);
        }

        /** Same as step(nowMs, motion, target, false, reducedMotion). */
        public Step step(double nowMs, String motion, Gaze target, boolean reducedMotion) { return step(nowMs, motion, target, false, reducedMotion); }

        /** Back to rest with no mood history, e.g. when the avatar is remounted. */
        public void reset() {
            follow = Follow.REST;
            started = false;
            hasMood = false;
            moodMotion = null;
            moodFrom = null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Strict design checks (avatar-validate --strict)

    /** A small accidental step in the outer silhouette: side left|right|top|bottom, where along it (y or x), its height (± units). */
    public record Seam(String side, double at, double size) {}

    /** silhouetteSeams of a valid definition (throws IllegalArgumentException if invalid). */
    public static List<Seam> seams(Definition d) {
        double[][][] polys = BotAvatarValidate.analyzeBody(parse(d).path()).polys;
        String[] sides = {"left", "right", "top", "bottom"};
        List<Seam> out = new ArrayList<>();
        for (double[] s : BotAvatarSeams.silhouetteSeams(polys)) out.add(new Seam(sides[(int) s[0]], s[1], s[2]));
        return out;
    }

    /** faceRoom: drawn eye boost (1..1.45), clearance at rest, gaze travel each way (0..1), and whether the face meets the strict target. */
    public record FaceRoom(double boost, double clearance, double gazeLeft, double gazeRight, double gazeUp, double gazeDown, boolean ok) {}

    public static FaceRoom faceRoom(Definition d) {
        Definition def = parse(d);
        double[] r = BotAvatarVolume.faceRoom(def, BotAvatarValidate.analyzeBody(def.path()));
        return new FaceRoom(r[0], r[1], r[2], r[3], r[4], r[5], r[6] != 0);
    }
}
