package dev.jun.gyeot.core;

import static dev.jun.gyeot.core.Jsons.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Offline physical basemap. Coordinates are longitude, latitude in degrees. */
final class BoardMap {
    private BoardMap() {}

    private record Coast(double[] coords, double west, double south, double east, double north) {}

    private static List<Coast> world, regional;

    private static synchronized List<Coast> coasts(boolean detailed) {
        List<Coast> cached = detailed ? regional : world;
        if (cached != null) return cached;
        List<Coast> lines = new ArrayList<>();
        try (var stream =
                        BoardMap.class.getResourceAsStream(
                                detailed ? "coast50.txt" : "coast110.txt");
                var input =
                        new BufferedReader(
                                new InputStreamReader(
                                        java.util.Objects.requireNonNull(stream),
                                        StandardCharsets.US_ASCII))) {
            String row;
            while ((row = input.readLine()) != null) {
                String[] values = row.split(" ");
                double[] coords = new double[values.length];
                double w = 180, s = 90, e = -180, n = -90;
                for (int i = 0; i < values.length; i += 2) {
                    double lon = coords[i] = Integer.parseInt(values[i]) / 100.0;
                    double lat = coords[i + 1] = Integer.parseInt(values[i + 1]) / 100.0;
                    w = Math.min(w, lon);
                    e = Math.max(e, lon);
                    s = Math.min(s, lat);
                    n = Math.max(n, lat);
                }
                lines.add(new Coast(coords, w, s, e, n));
            }
        } catch (Exception missing) {
            throw new IllegalStateException("해안선 자료를 열 수 없어요.", missing);
        }
        if (detailed) regional = lines;
        else world = lines;
        return lines;
    }

    private record Frame(
            double w, double s, double e, double n, double sx, double sy, float left, float top) {
        float x(double lon) {
            return left + (float) ((lon - w) * sx);
        }

        float y(double lat) {
            return top + (float) ((n - lat) * sy);
        }

        boolean inside(double lon, double lat) {
            return lon >= w && lon <= e && lat >= s && lat <= n;
        }
    }

    private static Frame frame(JSONObject spec) {
        JSONArray b = spec.optJSONArray("bounds");
        if (b == null)
            b =
                    switch (spec.optString("region")) {
                        case "korea" -> arr(123, 32, 132, 44);
                        case "eastAsia" -> arr(99, 18, 147, 56);
                        case "europe" -> arr(-13, 32, 46, 72);
                        case "mediterranean" -> arr(-12, 23, 57, 51);
                        default -> arr(-180, -85, 180, 85);
                    };
        double w = b.optDouble(0), s = b.optDouble(1), e = b.optDouble(2), n = b.optDouble(3);
        if (!(w >= -180 && e <= 180 && s >= -90 && n <= 90 && e - w >= .5 && n - s >= .5))
            throw new IllegalArgumentException("지도 범위는 서경도·남위도·동경도·북위도 순으로 입력하세요.");
        double aspect = Math.max(.15, Math.cos(Math.toRadians((s + n) / 2)));
        double sy = Math.min(890 / ((e - w) * aspect), 450 / (n - s)), sx = sy * aspect;
        return new Frame(
                w,
                s,
                e,
                n,
                sx,
                sy,
                (float) (500 - (e - w) * sx / 2),
                (float) (30 + (450 - (n - s) * sy) / 2));
    }

    /**
     * Clip to the viewport before emitting a stroke, including segments with both ends outside it.
     */
    private static boolean clip(Frame f, double ax, double ay, double bx, double by, float[] out) {
        double dx = bx - ax, dy = by - ay, lo = 0, hi = 1;
        for (int k = 0; k < 4; k++) {
            double p =
                    switch (k) {
                        case 0 -> -dx;
                        case 1 -> dx;
                        case 2 -> -dy;
                        default -> dy;
                    };
            double q =
                    switch (k) {
                        case 0 -> ax - f.w;
                        case 1 -> f.e - ax;
                        case 2 -> ay - f.s;
                        default -> f.n - ay;
                    };
            if (p == 0) {
                if (q < 0) return false;
                continue;
            }
            if (p < 0) lo = Math.max(lo, q / p);
            else hi = Math.min(hi, q / p);
            if (lo > hi) return false;
        }
        out[0] = f.x(ax + lo * dx);
        out[1] = f.y(ay + lo * dy);
        out[2] = f.x(ax + hi * dx);
        out[3] = f.y(ay + hi * dy);
        return true;
    }

    private static void flush(List<JSONObject> items, List<Float> path) {
        if (path.size() >= 4) {
            float[] points = new float[path.size()];
            for (int i = 0; i < points.length; i++) points[i] = path.get(i);
            items.add(Board.stroke("green", 3, false, points, Board.AI));
        }
        path.clear();
    }

    static BoardPlot.Made draw(JSONObject spec, float scale, Board.Measure measure) {
        Frame f = frame(spec);
        List<JSONObject> items = new ArrayList<>();
        List<Float> path = new ArrayList<>();
        float[] segment = new float[4];
        items.add(
                Board.faint(
                        Board.shape(
                                Board.RECT,
                                f.x(f.w),
                                f.y(f.n),
                                f.x(f.e),
                                f.y(f.s),
                                "blue",
                                2,
                                false,
                                Board.AI)));
        for (Coast coast : coasts(f.e - f.w < 80)) {
            if (coast.west > f.e || coast.east < f.w || coast.south > f.n || coast.north < f.s)
                continue;
            double[] p = coast.coords;
            for (int i = 2; i < p.length; i += 2) {
                if (Math.abs(p[i] - p[i - 2]) > 180
                        || !clip(f, p[i - 2], p[i - 1], p[i], p[i + 1], segment)) {
                    flush(items, path);
                    continue;
                }
                int k = path.size();
                if (k > 0 && (path.get(k - 2) != segment[0] || path.get(k - 1) != segment[1]))
                    flush(items, path);
                if (path.isEmpty()) {
                    path.add(segment[0]);
                    path.add(segment[1]);
                }
                path.add(segment[2]);
                path.add(segment[3]);
            }
            flush(items, path);
        }
        float y = 500, size = Math.max(24, Board.SMALL * scale);
        JSONObject title =
                Board.text(
                        Board.MARGIN,
                        y,
                        spec.optString("title", "지도"),
                        size,
                        "ink",
                        true,
                        900,
                        Board.AI);
        items.add(title);
        y = Board.bounds(title, measure)[3] + 12;
        JSONArray markers = spec.optJSONArray("places");
        JSONObject previous = null;
        for (int i = 0; markers != null && i < Math.min(16, markers.length()); i++) {
            JSONObject m = markers.optJSONObject(i);
            if (m == null || !f.inside(m.optDouble("lon"), m.optDouble("lat")))
                throw new IllegalArgumentException("표시할 장소가 지도의 좌표 범위를 벗어났어요.");
            float x = f.x(m.optDouble("lon")), yy = f.y(m.optDouble("lat"));
            if (previous != null
                    && spec.optBoolean("route")
                    && Math.abs(m.optDouble("lon") - previous.optDouble("lon")) <= 180)
                items.add(
                        Board.shape(
                                Board.LINE,
                                f.x(previous.optDouble("lon")),
                                f.y(previous.optDouble("lat")),
                                x,
                                yy,
                                "orange",
                                4,
                                true,
                                Board.AI));
            String idx = String.valueOf(i + 1);
            items.add(Board.stroke("red", 13, false, new float[] {x, yy}, Board.AI));
            items.add(
                    Board.text(
                            Math.min(x + 4, 920),
                            Math.max(0, yy - 32),
                            idx,
                            24,
                            "ink",
                            true,
                            55,
                            Board.AI));
            JSONObject label =
                    Board.text(
                            Board.MARGIN,
                            y,
                            idx + "  " + m.optString("name"),
                            size,
                            "ink",
                            false,
                            900,
                            Board.AI);
            items.add(label);
            y = Board.bounds(label, measure)[3] + 4;
            previous = m;
        }
        return new BoardPlot.Made(items, y);
    }
}
