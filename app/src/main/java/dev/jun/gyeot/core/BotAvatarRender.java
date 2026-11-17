package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * render.ts without the CSS 3D scene: the prepared (validated, volumed) definition cache, flat
 * frames (avatarFrame, liveFrame), the SVG still and avatarExtent.
 */
final class BotAvatarRender {
    private BotAvatarRender() {}

    private static final double MAX_DEPTH = 14;
    /** Eyes sit this far above the surface so they never touch the body planes. */
    static final double EYE_LIFT = 1.5;
    private static final double EYE_FACING = 0.22;
    private static final double POSE_LIFT = 4, POSE_SWAY = 0.2;
    private static final double SLICE_GAP_PX = 0.75;

    static final class Prepared {
        BotAvatar.Definition def;
        BotAvatarVolume.Volume vol;
        double[] range;
        long seed;
        String id;
        final Map<String, String> ids = new HashMap<>();
        final Map<String, boolean[]> detail = new HashMap<>();
    }

    private static final LinkedHashMap<String, Prepared> CACHE = new LinkedHashMap<>();

    static String f(double n) { return BotAvatarJs.fixed(n, 2); }

    private static double clamp(double v, double lo, double hi) { return Math.min(hi, Math.max(lo, v)); }

    static double depthOption(double depth) {
        double d = clamp(Double.isNaN(depth) || Double.isInfinite(depth) ? BotAvatar.DEFAULT_DEPTH : depth, 0, MAX_DEPTH);
        return BotAvatarJs.round(d * 2) / 2;
    }

    /** The last definition (by identity) and depth prepared, so a live avatar's frames skip the cache key. */
    private static volatile Object[] last = {null, Double.NaN, null};

    static Prepared prepare(BotAvatar.Definition definition, double depth) {
        Object[] memo = last;
        if (definition != null && memo[0] == definition && (Double) memo[1] == depth) return (Prepared) memo[2];
        Prepared p = prepareCached(definition, depth);
        last = new Object[] {definition, depth, p};
        return p;
    }

    private static Prepared prepareCached(BotAvatar.Definition definition, double depth) {
        BotAvatar.Eyes e = definition == null ? null : definition.eyes();
        String key = definition == null ? "null" : definition.id() + "\u0000" + definition.name() + "\u0000" + definition.path() + "\u0000"
            + (e == null ? "null" : e.x() + "," + e.y() + "," + e.spacing() + "," + e.scale()) + "\u0000" + depth;
        synchronized (CACHE) {
            Prepared hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        BotAvatarValidate.Result res = BotAvatarValidate.validate(definition == null ? BotAvatarJs.NULL : plain(definition));
        if (res.value == null) throw new IllegalArgumentException("Invalid avatar definition: " + String.join("; ", res.errors));
        Prepared p = new Prepared();
        p.def = res.value;
        BotAvatarValidate.Body body = BotAvatarValidate.analyzeBody(p.def.path());
        p.vol = BotAvatarVolume.build(p.def, depth / BotAvatar.DEFAULT_DEPTH, body);
        p.id = "oba-" + Long.toString(BotAvatarMotion.hashString(p.def.id() + "|" + p.def.path() + "|" + BotAvatarJs.num(depth)), 36);
        for (BotAvatarVolume.Part part : p.vol.parts) {
            for (List<BotAvatarVolume.Slice> stack : List.of(part.zSlices, part.xSlices)) {
                for (BotAvatarVolume.Slice s : stack) if (!p.ids.containsKey(s.d)) p.ids.put(s.d, p.id + "-" + p.ids.size());
            }
        }
        BotAvatar.Eyes eyes = p.def.eyes();
        p.range = BotAvatarValidate.gazeRange(body.polys, new BotAvatar.Eyes(eyes.x(), eyes.y(), eyes.spacing(), p.vol.eyeScale));
        p.seed = BotAvatarMotion.hashString(p.def.id());
        synchronized (CACHE) {
            if (CACHE.size() >= 64) {
                Iterator<String> first = CACHE.keySet().iterator();
                first.next();
                first.remove();
            }
            CACHE.put(key, p);
        }
        return p;
    }

    /** A definition as the plain JSON structure validate reads (field order as written by the TypeScript engine). */
    static Map<String, Object> plain(BotAvatar.Definition d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", 1.0);
        out.put("id", d.id() == null ? BotAvatarJs.NULL : d.id());
        out.put("name", d.name() == null ? BotAvatarJs.NULL : d.name());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", d.path() == null ? BotAvatarJs.NULL : d.path());
        out.put("body", body);
        if (d.eyes() == null) out.put("eyes", BotAvatarJs.NULL);
        else {
            Map<String, Object> eyes = new LinkedHashMap<>();
            eyes.put("x", d.eyes().x()); eyes.put("y", d.eyes().y()); eyes.put("spacing", d.eyes().spacing()); eyes.put("scale", d.eyes().scale());
            out.put("eyes", eyes);
        }
        return out;
    }

    static BotAvatar.Pose poseOf(Prepared p, BotAvatar.Options o) {
        String motion = "thinking".equals(o.motion) || "happy".equals(o.motion) || BotAvatar.EXTRA_MOODS.contains(o.motion) ? o.motion : "idle";
        double time = Double.isNaN(o.time) || Double.isInfinite(o.time) ? 0 : o.time;
        return BotAvatarMotion.computePose(p.seed, time, motion, o.gaze, o.reducedMotion, o.yaw, o.pitch, o.motionBlend);
    }

    private static String esc(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') out.append("&#").append((int) c).append(';');
            else out.append(c);
        }
        return out.toString();
    }

    // -----------------------------------------------------------------------------------------
    // View math (matrices in SVG order a b c d e f)

    static double[] mul(double[] m, double[] n) {
        return new double[] {
            m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1],
            m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
            m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5],
        };
    }

    private static double[] translate(double x, double y) { return new double[] {1, 0, 0, 1, x, y}; }

    private static double[] about(double x, double y, double[] m) { return mul(translate(x, y), mul(m, translate(-x, -y))); }

    private static String mat(double[] m) {
        StringBuilder out = new StringBuilder("matrix(");
        for (int i = 0; i < 6; i++) {
            if (i > 0) out.append(' ');
            out.append(i < 4 ? BotAvatarJs.num(BotAvatarJs.round(m[i] * 10000) / 10000) : f(m[i]));
        }
        return out.append(')').toString();
    }

    /** cY sY cP sP of a view. */
    static final class View {
        final double cY, sY, cP, sP;
        View(double yaw, double pitch) { cY = StrictMath.cos(yaw); sY = StrictMath.sin(yaw); cP = StrictMath.cos(pitch); sP = StrictMath.sin(pitch); }
    }

    private static double facingOf(View v, double[] n) { return v.sP * n[1] + v.cP * (-v.sY * n[0] + v.cY * n[2]); }

    private static boolean eyeVisible(BotAvatarVolume.Volume vol, View v, int i) {
        for (double[] n : vol.eyeNormals[i]) if (!(facingOf(v, n) > EYE_FACING)) return false;
        return true;
    }

    private static char activeStack(View v) { return Math.abs(v.cY) >= Math.abs(v.sY) ? 'z' : 'x'; }

    static double[] planeMatrix(View v, double z, double[] pivot) {
        double cx = pivot[0], cy = pivot[1];
        return new double[] {v.cY, v.sY * v.sP, 0, v.cP, cx - v.cY * cx + v.sY * z, cy - v.cP * cy - v.sY * v.sP * cx - v.cY * v.sP * z};
    }

    private static double[] sidePlaneMatrix(View v, double x0, double[] pivot) {
        double cx = pivot[0], cy = pivot[1];
        return new double[] {-v.sY, v.cY * v.sP, 0, v.cP, cx + v.cY * (x0 - cx), cy - v.cP * cy + v.sY * v.sP * (x0 - cx)};
    }

    /** projectPoint(...)[2]: depth toward the viewer. */
    private static double depthOf(View v, double x, double y, double z, double[] pivot) {
        double X = x - pivot[0], Y = y - pivot[1];
        return v.sP * Y + v.cP * (-v.sY * X + v.cY * z);
    }

    private static double[] rootMatrix(BotAvatar.Pose pose, double[] b) {
        double bx = (b[0] + b[2]) / 2, by = b[3];
        double r = (pose.roll() * Math.PI) / 180;
        double[] rot = {StrictMath.cos(r), StrictMath.sin(r), -StrictMath.sin(r), StrictMath.cos(r), 0, 0};
        return mul(translate(0, pose.lift()), about(bx, by, mul(rot, new double[] {pose.sx(), 0, 0, pose.sy(), 0, 0})));
    }

    private static double[] pivotOf(double[] b) { return new double[] {(b[0] + b[2]) / 2, (b[1] + b[3]) / 2}; }

    static double[] extent(BotAvatar.Definition definition, double depth) {
        Prepared p = prepare(definition, depthOption(depth));
        double[] b = p.vol.bounds;
        double[] c = pivotOf(b);
        double deepest = Double.NEGATIVE_INFINITY;
        for (BotAvatarVolume.Part part : p.vol.parts) deepest = Math.max(deepest, part.depth);
        double radius = BotAvatarJs.hypot(b[2] - b[0], b[3] - b[1], deepest + 2 * EYE_LIFT) / 2;
        double half = radius + POSE_LIFT + POSE_SWAY * (radius + b[3] - c[1]);
        return new double[] {c[0] - half, c[1] - half, 2 * half};
    }

    private static boolean[] sliceDetail(Prepared p, char stack, double size) {
        int px = !Double.isNaN(size) && !Double.isInfinite(size) && size > 0 ? (int) BotAvatarJs.round(size) : 0;
        String key = stack + Integer.toString(px);
        synchronized (p.detail) {
            boolean[] keep = p.detail.get(key);
            if (keep != null) return keep;
        }
        double gap = px != 0 ? (SLICE_GAP_PX * 100) / px : 0;
        List<Boolean> list = new ArrayList<>();
        for (BotAvatarVolume.Part part : p.vol.parts) {
            List<BotAvatarVolume.Slice> slices = stack == 'z' ? part.zSlices : part.xSlices;
            double last = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < slices.size(); j++) {
                BotAvatarVolume.Slice s = slices.get(j);
                boolean core = s.axis == 'z' && s.at == 0 && s.inset == 0;
                boolean kept = core || j == 0 || j == slices.size() - 1 || s.at - last >= gap;
                if (kept) last = s.at;
                list.add(kept);
            }
        }
        boolean[] keep = new boolean[list.size()];
        for (int i = 0; i < keep.length; i++) keep[i] = list.get(i);
        synchronized (p.detail) {
            if (p.detail.size() >= 32) p.detail.clear();
            p.detail.put(key, keep);
        }
        return keep;
    }

    // -----------------------------------------------------------------------------------------
    // Eyes

    /** x y w h dx dy open shift of one eye. */
    private static double[][] eyeStates(Prepared p, BotAvatar.Pose pose) {
        double s = p.vol.eyeScale;
        double w = BotAvatarValidate.EYE_W * s, h = BotAvatarValidate.EYE_H * s;
        double[] r = p.range;
        double dx = pose.gazeX() * (pose.gazeX() >= 0 ? r[1] : r[0]) * BotAvatarValidate.GAZE_X * s;
        double dy = pose.gazeY() * (pose.gazeY() >= 0 ? r[3] : r[2]) * BotAvatarValidate.GAZE_Y * s;
        double[][] centers = BotAvatarValidate.eyeCenters(p.def.eyes());
        double[][] out = new double[2][];
        for (int i = 0; i < 2; i++) {
            double squint = i == 0 ? pose.squintL() : pose.squintR();
            double open = Math.max(h * Math.max(0.09, (1 - pose.blink()) * (1 - squint * 0.4)), w * 0.55);
            out[i] = new double[] {centers[i][0], centers[i][1], w, h, dx, dy, open, (h - open) * 0.18};
        }
        return out;
    }

    private static String happyArc(double[] e) {
        double ex = e[0] + e[4], ey = e[1] + e[5];
        double hw = e[2] * BotAvatarValidate.ARC_HALF_WIDTH, base = ey + e[3] * BotAvatarValidate.ARC_BASE;
        return "M" + f(ex - hw) + " " + f(base) + "Q" + f(ex) + " " + f(ey - e[3] * BotAvatarValidate.ARC_PEAK) + " " + f(ex + hw) + " " + f(base);
    }

    private static void eyeMark(BotAvatar.Shape shape, double[] e, boolean happy) {
        if (happy) {
            shape.mark = "arc";
            shape.arc = happyArc(e);
            shape.strokeWidth = e[2] * BotAvatarValidate.ARC_STROKE;
            return;
        }
        double cx = e[0] + e[4], cy = e[1] + e[5] + e[7];
        shape.mark = "pill";
        shape.x = cx - e[2] / 2;
        shape.y = cy - e[6] / 2;
        shape.width = e[2];
        shape.height = e[6];
        shape.radius = Math.min(e[2], e[6]) / 2;
    }

    /**
     * The extra moods' eyes (BotAvatar.EXTRA_MOODS); idx 0 is the eye on the left of the face. Upstream's pill and happy
     * arc keep going through eyeMark above unchanged.
     */
    private static void extraEyeMark(BotAvatar.Shape shape, double[] e, String eyeShape, int idx, String body) {
        switch (eyeShape) {
            case "round" -> roundEye(shape, e);
            case "wink" -> {
                if (idx != 0) { roundEye(shape, e); return; }
                // The shut eye: a short squeezed curve on the eye's own middle (not the happy arc, which sits high like a brow).
                double ex = e[0] + e[4], ey = e[1] + e[5], hw = e[2] * 0.9, base = ey + e[3] * 0.12, peak = ey - e[3] * 0.14;
                shape.mark = "arc";
                shape.arc = "M" + f(ex - hw) + " " + f(base) + "Q" + f(ex) + " " + f(2 * peak - base) + " " + f(ex + hw) + " " + f(base);
                shape.strokeWidth = e[2] * BotAvatarValidate.ARC_STROKE * 1.25;
            }
            case "sleepy" -> {
                // Closed: a soft downward curve, like a sleeping smile.
                double ex = e[0] + e[4], base = e[1] + e[5] + e[3] * 0.05, hw = e[2] * BotAvatarValidate.ARC_HALF_WIDTH;
                shape.mark = "arc";
                shape.arc = "M" + f(ex - hw) + " " + f(base) + "Q" + f(ex) + " " + f(base + e[3] * 0.4) + " " + f(ex + hw) + " " + f(base);
                shape.strokeWidth = e[2] * BotAvatarValidate.ARC_STROKE * 0.85;
            }
            case "wide" -> {
                // Round, startled eyes.
                double w = e[2] * 1.8, h = Math.max(e[6] * 0.78, w * 0.3), cx = e[0] + e[4], cy = e[1] + e[5] + e[7];
                shape.mark = "pill";
                shape.x = cx - w / 2; shape.y = cy - h / 2; shape.width = w; shape.height = h; shape.radius = Math.min(w, h) / 2;
            }
            case "sad", "angry" -> {
                String d = cutPill(e, idx, eyeShape.equals("sad"));
                if (d.isEmpty()) { eyeMark(shape, e, false); return; }
                shape.mark = "path";
                shape.arc = d;
            }
            case "heart" -> {
                shape.mark = "path";
                shape.arc = heart(e);
                shape.color = heartColor(body);
            }
            default -> eyeMark(shape, e, false);
        }
    }

    /** A round, open eye (the wink's): a little wider than the pill and about as tall as wide, closing as the eye narrows. */
    private static void roundEye(BotAvatar.Shape shape, double[] e) {
        double w = e[2] * 1.55, h = Math.max(w * 1.12 * (e[6] / e[3]), w * 0.28), cx = e[0] + e[4], cy = e[1] + e[5] + e[7];
        shape.mark = "pill";
        shape.x = cx - w / 2; shape.y = cy - h / 2; shape.width = w; shape.height = h; shape.radius = Math.min(w, h) / 2;
    }

    /** The pill cut by a slanted brow line: inner end up for sad (worried), inner end down for angry (a frown). */
    static String cutPill(double[] e, int idx, boolean sad) {
        double cx = e[0] + e[4], cy = e[1] + e[5] + e[7], w = e[2], h = e[6];
        double r = Math.min(w, h) / 2, top = cy - h / 2, bottom = cy + h / 2, left = cx - w / 2, right = cx + w / 2;
        List<double[]> ring = new ArrayList<>();
        double[][] corners = {{left + r, top + r, Math.PI}, {right - r, top + r, Math.PI * 1.5}, {right - r, bottom - r, 0}, {left + r, bottom - r, Math.PI / 2}};
        for (double[] c : corners) for (int i = 0; i <= 6; i++) {
            double a = c[2] + (Math.PI / 2) * i / 6;
            ring.add(new double[] {c[0] + r * StrictMath.cos(a), c[1] + r * StrictMath.sin(a)});
        }
        double inner = idx == 0 ? 1 : -1, k = (sad ? -0.85 : 0.85) * inner, y0 = top + h * (sad ? 0.2 : 0.26);
        List<double[]> kept = new ArrayList<>();
        for (int i = 0; i < ring.size(); i++) {
            double[] p = ring.get(i), q = ring.get((i + 1) % ring.size());
            double fp = p[1] - (y0 + k * (p[0] - cx)), fq = q[1] - (y0 + k * (q[0] - cx));
            if (fp >= 0) kept.add(p);
            if ((fp >= 0) != (fq >= 0)) {
                double u = fp / (fp - fq);
                kept.add(new double[] {p[0] + (q[0] - p[0]) * u, p[1] + (q[1] - p[1]) * u});
            }
        }
        if (kept.size() < 3) return "";
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) d.append(i == 0 ? "M" : "L").append(f(kept.get(i)[0])).append(' ').append(f(kept.get(i)[1]));
        return d.append('Z').toString();
    }

    /** A filled heart a little wider than the happy arc, centred on the eye. */
    static String heart(double[] e) {
        double cx = e[0] + e[4], cy = e[1] + e[5], hw = e[2] * 1.12, hh = e[2] * 1.04;
        double[][] c = {{0, 0.9}, {-0.2, 0.65}, {-1, 0.25}, {-1, -0.25}, {-1, -0.75}, {-0.35, -1}, {0, -0.5}, {0.35, -1}, {1, -0.75}, {1, -0.25}, {1, 0.25}, {0.2, 0.65}, {0, 0.9}};
        StringBuilder d = new StringBuilder("M").append(f(cx + c[0][0] * hw)).append(' ').append(f(cy + c[0][1] * hh));
        for (int i = 1; i < c.length; i += 3) {
            d.append('C');
            for (int j = 0; j < 3; j++) d.append(j == 0 ? "" : " ").append(f(cx + c[i + j][0] * hw)).append(' ').append(f(cy + c[i + j][1] * hh));
        }
        return d.append('Z').toString();
    }

    /** Pink hearts, unless the body is itself close to that pink (then the eye ink keeps them visible). */
    static String heartColor(String body) {
        double[] a = BotAvatarColor.parseHex(body), b = BotAvatarColor.parseHex(BotAvatar.HEART_COLOR);
        if (a == null) return BotAvatar.HEART_COLOR;
        double dr = a[0] - b[0], dg = a[1] - b[1], db = a[2] - b[2];
        return Math.sqrt(dr * dr + dg * dg + db * db) < 110 ? null : BotAvatar.HEART_COLOR;
    }

    private static String color(String value, String fallback) {
        double[] c = BotAvatarColor.parseHex(value);
        return BotAvatarColor.toHex(c != null ? c : BotAvatarColor.parseHex(fallback));
    }

    // -----------------------------------------------------------------------------------------
    // Flat frames

    static BotAvatar.Frame frame(BotAvatar.Definition definition, BotAvatar.Options options, double size) {
        BotAvatar.Options o = options != null ? options : new BotAvatar.Options();
        Prepared p = prepare(definition, depthOption(o.depth));
        BotAvatarVolume.Volume vol = p.vol;
        double[] b = vol.bounds;
        BotAvatar.Pose pose = poseOf(p, o);
        double[] pivot = pivotOf(b);
        View v = new View(pose.yaw(), pose.pitch());
        char stack = activeStack(v);
        double[] eyesMid = {p.def.eyes().x(), p.def.eyes().y()};

        BotAvatar.Frame frame = new BotAvatar.Frame();
        frame.name = p.def.name();
        frame.color = color(o.color, BotAvatar.DEFAULT_COLOR);
        frame.ink = color(o.eyeColor, BotAvatar.DEFAULT_EYE_COLOR);
        Map<String, BotAvatar.FramePath> paths = new LinkedHashMap<>();
        List<double[]> depths = new ArrayList<>();
        List<BotAvatar.Shape> items = new ArrayList<>();

        double sweep = BotAvatarJs.hypot(v.sY, v.cY * v.sP);
        double insetScale = Math.min(Math.abs(v.cY), Math.abs(v.cP));
        boolean[] kept = sliceDetail(p, stack, size);
        int k = 0;
        for (int i = 0; i < vol.parts.size(); i++) {
            BotAvatarVolume.Part part = vol.parts.get(i);
            List<BotAvatarVolume.Slice> slices = stack == 'z' ? part.zSlices : part.xSlices;
            for (int j = 0; j < slices.size(); j++) {
                BotAvatarVolume.Slice s = slices.get(j);
                if (!kept[k++]) continue;
                if (!(s.axis != 'z' || s.inset <= 0 || Math.abs(s.at) * sweep > s.inset * insetScale)) continue;
                double[] m = stack == 'z' ? planeMatrix(v, s.at, pivot) : sidePlaneMatrix(v, s.at, pivot);
                BotAvatar.FramePath path = paths.get(s.d);
                if (path == null) {
                    path = new BotAvatar.FramePath(p.ids.get(s.d), s.d, s.evenOdd, s.box[0], s.box[1], s.box[2], s.box[3]);
                    paths.put(s.d, path);
                }
                double[] ref = i == vol.eyePart ? eyesMid : part.center;
                double depth = stack == 'z' ? depthOf(v, ref[0], ref[1], s.at, pivot) : depthOf(v, s.at, ref[1], 0, pivot);
                BotAvatar.Shape shape = new BotAvatar.Shape();
                shape.kind = "slice";
                shape.key = Integer.toString(i) + stack + j;
                shape.path = path.id();
                shape.matrix = m;
                shape.part = i;
                shape.slice = j;
                shape.stack = stack;
                items.add(shape);
                depths.add(new double[] {depth});
            }
        }
        double[][] eyes = eyeStates(p, pose);
        boolean happy = pose.eyeShape().equals("happy"), upstream = happy || pose.eyeShape().equals("pill");
        boolean anyEye = false;
        for (int idx = 0; idx < 2; idx++) {
            if (!eyeVisible(vol, v, idx)) continue;
            double z = vol.eyeZ[idx] + EYE_LIFT;
            BotAvatar.Shape shape = new BotAvatar.Shape();
            shape.kind = "eye";
            shape.key = idx == 0 ? "eye-0" : "eye-1";
            shape.matrix = planeMatrix(v, z, pivot);
            if (upstream) eyeMark(shape, eyes[idx], happy);
            else extraEyeMark(shape, eyes[idx], pose.eyeShape(), idx, frame.color);
            items.add(shape);
            depths.add(new double[] {depthOf(v, eyesMid[0], eyesMid[1], z, pivot) + 1e-6});
            anyEye = true;
        }
        // The eyes' plane at the pair's mean depth: exactly each eye's matrix when both eyes sit at one depth.
        if (anyEye) frame.facePlane = planeMatrix(v, (vol.eyeZ[0] + vol.eyeZ[1]) / 2 + EYE_LIFT, pivot);

        Integer[] order = new Integer[items.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, c) -> BotAvatarJs.cmp(depths.get(a)[0] - depths.get(c)[0]));
        List<BotAvatar.Shape> shapes = new ArrayList<>(order.length);
        for (Integer i : order) shapes.add(items.get(i));
        frame.paths = new ArrayList<>(paths.values());
        frame.root = rootMatrix(pose, b);
        frame.shapes = shapes;
        return frame;
    }

    // -----------------------------------------------------------------------------------------
    // Standalone SVG still

    private static String eyeSvg(BotAvatar.Shape s, String ink) {
        if (s.mark.equals("arc")) {
            return "<path d=\"" + s.arc + "\" fill=\"none\" stroke=\"" + ink + "\" stroke-width=\"" + f(s.strokeWidth) + "\" stroke-linecap=\"round\"/>";
        }
        if (s.mark.equals("path")) return "<path d=\"" + s.arc + "\" fill=\"" + (s.color != null ? s.color : ink) + "\"/>";
        return "<rect x=\"" + f(s.x) + "\" y=\"" + f(s.y) + "\" width=\"" + f(s.width) + "\" height=\"" + f(s.height) + "\" rx=\"" + f(s.radius) + "\" fill=\"" + ink + "\"/>";
    }

    static String svg(BotAvatar.Definition definition, BotAvatar.Options options) {
        BotAvatar.Options o = options != null ? options : new BotAvatar.Options();
        BotAvatar.Frame frame = frame(definition, o, Double.NaN);
        StringBuilder defs = new StringBuilder();
        for (BotAvatar.FramePath d : frame.paths) {
            defs.append("<path id=\"").append(d.id()).append("\" d=\"").append(d.d()).append('"').append(d.evenOdd() ? " fill-rule=\"evenodd\"" : "").append("/>");
        }
        StringBuilder body = new StringBuilder();
        for (BotAvatar.Shape s : frame.shapes) {
            if (s.kind.equals("slice")) body.append("<use href=\"#").append(s.path).append("\" transform=\"").append(mat(s.matrix)).append("\"/>");
            else body.append("<g transform=\"").append(mat(s.matrix)).append("\">").append(eyeSvg(s, frame.ink)).append("</g>");
        }
        boolean sized = !Double.isNaN(o.size) && !Double.isInfinite(o.size) && o.size > 0;
        String dims = sized ? " width=\"" + f(clamp(o.size, 8, 4096)) + "\" height=\"" + f(clamp(o.size, 8, 4096)) + "\"" : "";
        String label = esc(frame.name);
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\"" + dims + " role=\"img\" aria-label=\"" + label + "\" overflow=\"visible\">"
            + "<title>" + label + "</title>"
            + "<defs>" + defs + "</defs>"
            + "<g transform=\"" + mat(frame.root) + "\" fill=\"" + frame.color + "\">"
            + body
            + "</g></svg>";
    }
}
