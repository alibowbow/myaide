package dev.jun.gyeot;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.Choreographer;
import dev.jun.gyeot.core.AvatarSpec;
import dev.jun.gyeot.core.BotAvatar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The assistant's living avatar (open-bot-avatar) on an Android Canvas: the engine's frame for each vsync while running,
 * painted like the library's own canvas renderer (dom/paint.ts): every cross-section path filled under its matrix and the
 * frame's root motion, back to front, then the eyes as pills, arcs or the extra moods' filled shapes. Stopped, it holds a calm still pose.
 *
 * A picture character (AvatarSpec.PICTURE) paints the volume's sections in the character's darkened edge colour, then
 * the picture itself over the front, mapped by the face plane and clipped to the traced silhouette, so the user's own
 * drawing turns, hops and tilts with the engine's moods. Its own eyes stay unless the living eyes were chosen.
 */
final class AvatarDrawable extends Drawable implements Animatable, Choreographer.FrameCallback {
    /** Small avatars redraw at about 30 fps: the motion is slow and the header is tiny. */
    private static final long FRAME_NS = 33_000_000L;
    final AvatarSpec spec;
    private final BotAvatar.Definition definition;
    private final Bitmap texture;
    private final boolean backdrop;
    private final Map<String, Path> sections = new HashMap<>();
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
        picture = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), back = new Paint(Paint.ANTI_ALIAS_FLAG), tinted = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix(), textureMatrix = new Matrix();
    private final RectF eyeBox = new RectF();
    private final Path clip = new Path();
    private final float[] values = new float[9];
    private final Path silhouette;
    private final BotAvatar.Animator animator = new BotAvatar.Animator();
    private final long born = System.nanoTime();
    private boolean running;
    private long lastFrame;
    private String motion = "idle";
    private float density = 1;

    /** texture: the decoded profile picture for a picture character, else null. backdrop: a soft round tile behind it. */
    AvatarDrawable(AvatarSpec spec, Bitmap texture, boolean backdrop, int backdropColor) {
        this.spec = spec; this.definition = spec.parsed(); this.backdrop = backdrop;
        this.texture = AvatarSpec.PICTURE.equals(spec.kind) ? texture : null;
        body.setStyle(Paint.Style.FILL); body.setColor(this.texture != null ? spec.shade : Color.parseColor(spec.color));
        ink.setStyle(Paint.Style.FILL); ink.setColor(Color.parseColor(spec.eyeColor)); tinted.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND); stroke.setColor(ink.getColor());
        back.setColor(backdropColor);
        silhouette = path(definition.path(), false);
        if (this.texture != null) picture.setShader(new BitmapShader(this.texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }
    void density(float value) { density = value > 0 ? value : 1; }
    String name() { return definition.name(); }

    /** One of BotAvatar.MOODS (idle, thinking, happy, surprised, sad, sleepy, wink, love, angry); the change eases in over the engine's mood time. */
    void mood(String next) {
        String m = BotAvatar.mood(next);
        if (m.equals(motion)) return;
        motion = m; invalidateSelf();
    }
    String mood() { return motion; }

    @Override public void start() {
        if (running) return;
        running = true; lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
        invalidateSelf();
    }
    @Override public void stop() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        invalidateSelf();
    }
    @Override public boolean isRunning() { return running; }
    @Override public void doFrame(long now) {
        if (!running) return;
        if (now - lastFrame >= FRAME_NS) { lastFrame = now; invalidateSelf(); }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds(); if (b.isEmpty()) return;
        float side = Math.min(b.width(), b.height()), left = b.left + (b.width() - side) / 2f, top = b.top + (b.height() - side) / 2f;
        if (backdrop) canvas.drawCircle(left + side / 2f, top + side / 2f, side / 2f, back);
        // The engine's motion reaches a little past the 0..100 box (hop, tilt). The round tile keeps a thin margin so the
        // character, and above all its eyes, stays large at profile sizes; a hop may graze the circle's edge.
        float inset = side * (backdrop ? 0.06f : 0.03f), scale = (side - 2 * inset) / 100f;
        BotAvatar.Options o = options(side / density);
        BotAvatar.Frame frame;
        try { frame = BotAvatar.liveFrame(definition, o); } catch (RuntimeException broken) { return; }
        int save = canvas.save();
        canvas.translate(left + inset, top + inset);
        if (backdrop) { clip.rewind(); clip.addCircle(side / 2f - inset, side / 2f - inset, side / 2f, Path.Direction.CW); canvas.clipPath(clip); }
        for (BotAvatar.FramePath p : frame.paths) if (!sections.containsKey(p.id())) {
            if (sections.size() > 512) sections.clear();
            sections.put(p.id(), path(p.d(), p.evenOdd()));
        }
        double[] root = frame.root;
        if (texture == null) {
            // The library's order exactly: shapes back to front, eyes interleaved by depth, so a part turned in front of an eye covers it.
            for (BotAvatar.Shape shape : frame.shapes) {
                place(scale, root, shape.matrix); canvas.save(); canvas.concat(matrix);
                if ("slice".equals(shape.kind)) { Path section = sections.get(shape.path); if (section != null) canvas.drawPath(section, body); }
                else eye(canvas, shape);
                canvas.restore();
            }
        } else {
            // A picture character: the volume in its shade, the picture over the face plane, then the living eyes if chosen.
            for (BotAvatar.Shape shape : frame.shapes) {
                if (!"slice".equals(shape.kind)) continue;
                Path section = sections.get(shape.path); if (section == null) continue;
                place(scale, root, shape.matrix); canvas.save(); canvas.concat(matrix); canvas.drawPath(section, body); canvas.restore();
            }
            if (frame.facePlane != null) {
                place(scale, root, frame.facePlane); canvas.save(); canvas.concat(matrix);
                canvas.clipPath(silhouette);
                float sx = (float) (spec.textureWidth / texture.getWidth()), sy = (float) (spec.textureHeight / texture.getHeight());
                textureMatrix.setScale(sx, sy); textureMatrix.postTranslate((float) spec.textureX, (float) spec.textureY);
                picture.getShader().setLocalMatrix(textureMatrix);
                canvas.drawPath(silhouette, picture);
                canvas.restore();
            }
            if (!spec.ownEyes) for (BotAvatar.Shape shape : frame.shapes) {
                if (!"eye".equals(shape.kind)) continue;
                place(scale, root, shape.matrix); canvas.save(); canvas.concat(matrix); eye(canvas, shape); canvas.restore();
            }
        }
        canvas.restoreToCount(save);
    }
    /** One eye in its face plane: a rounded pill, the happy arc stroked with round caps (paint.ts's eye()), or an extra mood's filled shape. */
    private void eye(Canvas canvas, BotAvatar.Shape shape) {
        if ("path".equals(shape.mark)) {
            // Tilted and heart eyes move every frame, so their path is built fresh rather than cached.
            Paint fill = ink;
            if (shape.color != null) { try { tinted.setColor(Color.parseColor(shape.color)); tinted.setAlpha(ink.getAlpha()); fill = tinted; } catch (IllegalArgumentException ignored) {} }
            canvas.drawPath(path(shape.arc, false), fill);
        } else if ("arc".equals(shape.mark)) {
            Path arc = sections.get("arc:" + shape.arc);
            if (arc == null) { arc = path(shape.arc, false); sections.put("arc:" + shape.arc, arc); }
            stroke.setStrokeWidth((float) shape.strokeWidth); canvas.drawPath(arc, stroke);
        } else {
            eyeBox.set((float) shape.x, (float) shape.y, (float) (shape.x + shape.width), (float) (shape.y + shape.height));
            canvas.drawRoundRect(eyeBox, (float) shape.radius, (float) shape.radius, ink);
        }
    }
    /** The engine options for this frame: clock, mood easing and the size it is drawn at. */
    private BotAvatar.Options options(float sizeDp) {
        BotAvatar.Options o = new BotAvatar.Options();
        o.color = spec.color; o.eyeColor = spec.eyeColor; o.depth = spec.depth; o.size = Math.max(16, sizeDp);
        o.motion = motion; o.reducedMotion = !running || !android.animation.ValueAnimator.areAnimatorsEnabled();
        o.time = o.reducedMotion ? 0 : (System.nanoTime() - born) / 1e9;
        BotAvatar.Animator.Step step = animator.step(System.nanoTime() / 1e6, motion, null, o.reducedMotion);
        o.gaze = step.gaze(); o.motionBlend = step.motionBlend();
        return o;
    }
    /** matrix = scale × root × m, the canvas form of paint.ts's per-shape transform. */
    private void place(float scale, double[] root, double[] m) {
        double a = root[0] * m[0] + root[2] * m[1], b = root[1] * m[0] + root[3] * m[1], c = root[0] * m[2] + root[2] * m[3], d = root[1] * m[2] + root[3] * m[3];
        double e = root[0] * m[4] + root[2] * m[5] + root[4], f = root[1] * m[4] + root[3] * m[5] + root[5];
        values[0] = (float) (a * scale); values[1] = (float) (c * scale); values[2] = (float) (e * scale);
        values[3] = (float) (b * scale); values[4] = (float) (d * scale); values[5] = (float) (f * scale);
        values[6] = 0; values[7] = 0; values[8] = 1;
        matrix.setValues(values);
    }
    /** SVG path data as an android Path, through the engine's own command normalizer (M L C Z). */
    static Path path(String d, boolean evenOdd) {
        Path out = new Path(); out.setFillType(evenOdd ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
        List<double[]> commands = BotAvatar.commands(d);
        for (double[] c : commands) {
            switch ((char) c[0]) {
                case 'M' -> out.moveTo((float) c[1], (float) c[2]);
                case 'L' -> out.lineTo((float) c[1], (float) c[2]);
                case 'C' -> out.cubicTo((float) c[1], (float) c[2], (float) c[3], (float) c[4], (float) c[5], (float) c[6]);
                case 'Z' -> out.close();
                default -> {}
            }
        }
        return out;
    }

    @Override public void setAlpha(int alpha) { body.setAlpha(alpha); ink.setAlpha(alpha); stroke.setAlpha(alpha); picture.setAlpha(alpha); back.setAlpha(alpha); tinted.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { body.setColorFilter(filter); ink.setColorFilter(filter); picture.setColorFilter(filter); tinted.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
