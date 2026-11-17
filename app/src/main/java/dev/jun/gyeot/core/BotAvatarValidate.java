package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** validate.ts: validateAvatar, body analysis (subpaths, holes, probes) and the eye fit tests. */
final class BotAvatarValidate {
    private BotAvatarValidate() {}

    /** Neutral eye size at scale 1, viewBox units. */
    static final double EYE_W = 4, EYE_H = 11;
    static final double BODY_MIN = 6, BODY_MAX = 94;
    static final double EYE_MARGIN = 1.2;
    /** Max gaze travel of the eyes inside the face at scale 1. */
    static final double GAZE_X = 1.5, GAZE_Y = 1.1;
    /** Happy-eye arc as fractions of the drawn eye size. */
    static final double ARC_HALF_WIDTH = 0.72, ARC_BASE = 0.1, ARC_PEAK = 0.42, ARC_STROKE = 0.6;
    /** Gap the renderer keeps between every drawn eye shape and the outline while the eyes look around. */
    static final double EYE_CLEARANCE = 1.5;
    static final double RECESS_MAX_AREA = 110;

    private static final String[] TOP_KEYS = {"version", "id", "name", "body", "eyes"};
    private static final String[] EYE_KEYS = {"x", "y", "spacing", "scale"};
    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");
    private static final Pattern BAD_NAME = Pattern.compile("[\\u0000-\\u001f<>]");

    /** One subpath: its serialization, own polygons, whether it cuts a hole, and a point inside its own fill (or cut-out). */
    static final class Subpath {
        final String d; final double[][][] polys; final boolean hole; final double[] probe;
        Subpath(String d, double[][][] polys, boolean hole, double[] probe) { this.d = d; this.polys = polys; this.hole = hole; this.probe = probe; }
    }

    static final class Body {
        final double[][][] polys; final double[] bounds; final int area; final List<String> holeD = new ArrayList<>(); final List<Integer> holeArea = new ArrayList<>();
        final List<Subpath> subpaths = new ArrayList<>();
        Body(double[][][] polys, double[] bounds, int area) { this.polys = polys; this.bounds = bounds; this.area = area; }
    }

    /** {left, right, up, down}: how far (0..1 of GAZE_X/GAZE_Y) the drawn eyes travel each way. */
    static double[] gazeRange(double[][][] polys, BotAvatar.Eyes eyes) {
        return new double[] {reach(polys, eyes, -1, 0), reach(polys, eyes, 1, 0), reach(polys, eyes, 0, -1), reach(polys, eyes, 0, 1)};
    }

    private static double reach(double[][][] polys, BotAvatar.Eyes eyes, double ux, double uy) {
        for (double k : new double[] {1, 0.8, 0.6, 0.4, 0.2}) {
            if (drawnEyesFit(polys, eyes, ux * k * GAZE_X * eyes.scale(), uy * k * GAZE_Y * eyes.scale(), EYE_CLEARANCE) == null) return k;
        }
        return 0;
    }

    private static List<double[]> eyeOutline(double cx, double cy, double scale, double margin) {
        double hw = (EYE_W * scale) / 2 + margin;
        double hh = (EYE_H * scale) / 2 + margin;
        List<double[]> pts = new ArrayList<>(24);
        double straight = hh - hw;
        for (int k = 0; k < 16; k++) {
            double a = ((double) k / 16) * Math.PI * 2;
            double x = StrictMath.cos(a) * hw;
            double y = StrictMath.sin(a) * hw + Math.signum(StrictMath.sin(a)) * Math.max(0, straight);
            pts.add(new double[] {cx + x, cy + y});
        }
        for (double f : new double[] {-0.5, 0, 0.5}) {
            pts.add(new double[] {cx - hw, cy + f * straight});
            pts.add(new double[] {cx + hw, cy + f * straight});
        }
        pts.add(new double[] {cx, cy});
        return pts;
    }

    private static List<double[]> drawnEyeOutline(double cx, double cy, double scale, double margin) {
        List<double[]> pts = eyeOutline(cx, cy, scale, margin);
        double w = EYE_W * scale, h = EYE_H * scale;
        double x0 = cx - ARC_HALF_WIDTH * w, x1 = cx + ARC_HALF_WIDTH * w;
        double yb = cy + ARC_BASE * h, yc = cy - ARC_PEAK * h;
        double r = (ARC_STROKE * w) / 2 + margin;
        for (int i = 0; i <= 8; i++) {
            double t = (double) i / 8, m = 1 - t;
            double x = m * m * x0 + 2 * m * t * cx + t * t * x1;
            double y = (m * m + t * t) * yb + 2 * m * t * yc;
            for (int k = 0; k < 8; k++) {
                double a = ((double) k / 8) * Math.PI * 2;
                pts.add(new double[] {x + StrictMath.cos(a) * r, y + StrictMath.sin(a) * r});
            }
        }
        return pts;
    }

    static double[][] eyeCenters(BotAvatar.Eyes eyes) {
        return new double[][] {{eyes.x() - eyes.spacing() / 2, eyes.y()}, {eyes.x() + eyes.spacing() / 2, eyes.y()}};
    }

    static double[] eyesFit(double[][][] polys, BotAvatar.Eyes eyes, double dx, double dy, double margin) {
        for (double[] c : eyeCenters(eyes)) {
            for (double[] p : eyeOutline(c[0] + dx, c[1] + dy, eyes.scale(), margin)) if (!BotAvatarPath.isInside(polys, p[0], p[1])) return p;
        }
        return null;
    }

    static double[] drawnEyesFit(double[][][] polys, BotAvatar.Eyes eyes, double dx, double dy, double margin) {
        for (double[] c : eyeCenters(eyes)) {
            for (double[] p : drawnEyeOutline(c[0] + dx, c[1] + dy, eyes.scale(), margin)) if (!BotAvatarPath.isInside(polys, p[0], p[1])) return p;
        }
        return null;
    }

    /** Largest gap (to 0.1) between the drawn eyes at rest and the outline; 0 when they touch it. */
    static double eyeClearance(double[][][] polys, BotAvatar.Eyes eyes) {
        if (drawnEyesFit(polys, eyes, 0, 0, 0) != null) return 0;
        double lo = 0, hi = 20;
        while (hi - lo > 0.05) {
            double mid = (lo + hi) / 2;
            if (drawnEyesFit(polys, eyes, 0, 0, mid) != null) hi = mid;
            else lo = mid;
        }
        return Math.floor(lo * 10) / 10;
    }

    static Body analyzeBody(String path) {
        List<BotAvatarPath.Seg> segs = BotAvatarPath.parse(path);
        double[][][] polys = BotAvatarPath.flatten(segs);
        double[] b = BotAvatarPath.bounds(polys);
        Body body = new Body(polys, b, BotAvatarPath.filledArea(polys, b));
        for (List<BotAvatarPath.Seg> sub : BotAvatarPath.splitSubpaths(segs)) {
            double[][][] own = BotAvatarPath.flatten(sub);
            if (own.length == 0) continue;
            double[] ob = BotAvatarPath.bounds(own);
            double inside = 0, cut = 0;
            double[] probe = null, cutProbe = null;
            double step = (ob[2] - ob[0]) * (ob[3] - ob[1]) < 900 ? 0.5 : 1;
            boolean alone = own.length == polys.length;
            List<Double> list = new ArrayList<>();
            for (double x = Math.floor(ob[0]) + step / 2; x < ob[2]; x += step) list.add(x);
            double[] xs = new double[list.size()];
            for (int i = 0; i < xs.length; i++) xs[i] = list.get(i);
            boolean[] mine = new boolean[xs.length], all = new boolean[xs.length];
            for (double y = Math.floor(ob[1]) + step / 2; y < ob[3]; y += step) {
                BotAvatarPath.insideRow(own, y, xs, mine);
                if (!alone) BotAvatarPath.insideRow(polys, y, xs, all);
                for (int k = 0; k < xs.length; k++) {
                    if (!mine[k]) continue;
                    inside++;
                    if (probe == null) probe = new double[] {xs[k], y};
                    if (!alone && !all[k]) { cut++; if (cutProbe == null) cutProbe = new double[] {xs[k], y}; }
                }
            }
            cut *= step * step * 4;
            inside *= step * step * 4;
            String d = BotAvatarPath.serialize(sub);
            boolean hole = inside > 0 && cut / inside > 0.6;
            if (hole) { body.holeD.add(d); body.holeArea.add((int) BotAvatarJs.round(cut / 4)); }
            body.subpaths.add(new Subpath(d, own, hole, hole ? cutProbe : probe));
        }
        return body;
    }

    private static String r1(double n) { return BotAvatarJs.num(BotAvatarJs.round(n * 10) / 10); }

    private static boolean isNum(Object v) { return v instanceof Double && !((Double) v).isNaN() && !((Double) v).isInfinite(); }

    /** Validation errors (empty when valid) and, when valid, the normalized definition. */
    static final class Result {
        final List<String> errors; final BotAvatar.Definition value;
        Result(List<String> errors, BotAvatar.Definition value) { this.errors = errors; this.value = value; }
    }

    /** validateAvatar over the plain JSON structure of {@link BotAvatarJs#parseJson}. */
    @SuppressWarnings("unchecked")
    static Result validate(Object input) {
        List<String> errors = new ArrayList<>();
        if (!(input instanceof Map)) { errors.add("definition must be a JSON object"); return new Result(errors, null); }
        Map<String, Object> o = (Map<String, Object>) input;
        for (String k : BotAvatarJs.keys(o)) if (!List.of(TOP_KEYS).contains(k)) errors.add("unknown field \"" + k + "\" (allowed: " + String.join(", ", TOP_KEYS) + ")");
        Object version = o.get("version");
        if (!(version instanceof Double && (Double) version == 1)) errors.add("version must be 1");

        Object id = o.get("id");
        if (!(id instanceof String) || !SLUG.matcher((String) id).matches()) errors.add("id must be a lowercase slug of 1-40 chars [a-z0-9-]");
        String name = o.get("name") instanceof String ? BotAvatarJs.trim((String) o.get("name")) : "";
        if (name.isEmpty() || name.length() > 40 || BAD_NAME.matcher(name).find()) errors.add("name must be 1-40 printable characters without < or >");

        String path = null;
        Object bodyValue = o.get("body");
        if (!(bodyValue instanceof Map)) errors.add("body must be an object { \"path\": \"...\" }");
        else {
            Map<String, Object> body = (Map<String, Object>) bodyValue;
            for (String k : BotAvatarJs.keys(body)) if (!k.equals("path")) errors.add("unknown field \"body." + k + "\" (only \"path\" is allowed)");
            if (!(body.get("path") instanceof String)) errors.add("body.path must be a string");
            else path = (String) body.get("path");
        }

        BotAvatar.Eyes eyes = null;
        Object eyesValue = o.get("eyes");
        if (!(eyesValue instanceof Map)) errors.add("eyes must be an object { x, y, spacing, scale }");
        else {
            Map<String, Object> e = (Map<String, Object>) eyesValue;
            for (String k : BotAvatarJs.keys(e)) if (!List.of(EYE_KEYS).contains(k)) errors.add("unknown field \"eyes." + k + "\" (allowed: " + String.join(", ", EYE_KEYS) + ")");
            List<String> bad = new ArrayList<>();
            for (String k : EYE_KEYS) if (!isNum(e.get(k))) bad.add(k);
            if (!bad.isEmpty()) errors.add("eyes." + String.join(", eyes.", bad) + " must be finite numbers");
            else {
                eyes = new BotAvatar.Eyes((Double) e.get("x"), (Double) e.get("y"), (Double) e.get("spacing"), (Double) e.get("scale"));
                if (eyes.scale() < 0.6 || eyes.scale() > 1.8) errors.add("eyes.scale " + BotAvatarJs.num(eyes.scale()) + " out of range 0.6..1.8");
                double minSpacing = EYE_W * eyes.scale() * 1.75;
                if (eyes.spacing() < minSpacing || eyes.spacing() > 40) {
                    errors.add("eyes.spacing " + BotAvatarJs.num(eyes.spacing()) + " out of range " + r1(minSpacing) + "..40 for scale " + BotAvatarJs.num(eyes.scale()));
                }
            }
        }

        String normalized = "";
        Body body = null;
        if (path != null) {
            try {
                normalized = BotAvatarPath.serialize(BotAvatarPath.parse(path));
                body = analyzeBody(normalized);
            } catch (BotAvatarPath.PathError err) {
                errors.add("body.path: " + err.getMessage());
            }
        }

        if (body != null) {
            double[] b = body.bounds;
            List<String> out = new ArrayList<>();
            if (b[0] < BODY_MIN) out.add("left edge x=" + r1(b[0]));
            if (b[1] < BODY_MIN) out.add("top edge y=" + r1(b[1]));
            if (b[2] > BODY_MAX) out.add("right edge x=" + r1(b[2]));
            if (b[3] > BODY_MAX) out.add("bottom edge y=" + r1(b[3]));
            if (!out.isEmpty()) errors.add("body.path leaves the safe area (" + String.join(", ", out) + "); keep geometry within 8..92");
            double w = b[2] - b[0], h = b[3] - b[1];
            if (Math.max(w, h) < 40 || Math.min(w, h) < 14) {
                errors.add("body is too small (" + r1(w) + "x" + r1(h) + "); use at least 40 units on the long side and 14 on the short side");
            }
            if (body.area < 600) errors.add("body fill area " + body.area + " is too thin to carry eyes; must be at least 600 units²");
            if (eyes != null) {
                double[] miss = eyesFit(body.polys, eyes, 0, 0, EYE_MARGIN);
                if (miss != null) {
                    errors.add("eyes must sit inside the body with " + BotAvatarJs.num(EYE_MARGIN) + " units margin; point (" + r1(miss[0]) + ", " + r1(miss[1]) + ") is outside or in a hole. "
                        + "Move eyes.x/y toward the widest solid area, reduce spacing or scale.");
                }
            }
        }
        if (!errors.isEmpty() || eyes == null) return new Result(errors, null);
        return new Result(errors, new BotAvatar.Definition((String) id, name, normalized, eyes));
    }
}
