package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** A saved diagram keeps its input. Its drawing primitives are a view of that input. */
public final class BoardWidget {
    private BoardWidget() {}

    public static final String GRAPH = "graph", TABLE = "table", CHART = "chart", FLOW = "flow",
        TIMELINE = "timeline", TREE = "tree", NUMBER_LINE = "numberline", GEOMETRY = "geometry", SOLID = "solid", MAP = "map", CHEM = "chemistry", DIAGRAM = "diagram", SCENE = "scene", READING = "reading", FIELD = "finitefield", RELATION = "relation";
    public static final float GEOMETRY_LEFT = 120, GEOMETRY_TOP = 42, GEOMETRY_UNIT = 76;
    private static final Map<String, List<String>> FIELDS = Map.ofEntries(
        Map.entry(GRAPH, List.of("height", "x", "y", "grid", "equalUnits", "functions", "points", "vlines", "hlines", "shade", "parameters")),
        Map.entry(TABLE,List.of("rows","header","style")),Map.entry(CHART,List.of("kind","title","labels","values","unit","color")),
        Map.entry(FLOW,List.of("steps","direction","style")),Map.entry(TIMELINE,List.of("events")),Map.entry(TREE,List.of("root","children","style")),
        Map.entry(NUMBER_LINE,List.of("from","to","marks","ranges")),Map.entry(GEOMETRY,List.of("vertices")),Map.entry(SOLID,List.of("kind","yaw","pitch","net","vertices","faces")),
        Map.entry(MAP,List.of("title","region","bounds","route","places")),Map.entry(CHEM,List.of("formula")),
        Map.entry(DIAGRAM,List.of("title","preset","layers","height","focus")),
        Map.entry(SCENE,List.of("title","height","preset","stage","objects","links")),Map.entry(READING,List.of("title","source","lines","reveal")),
        Map.entry(RELATION,List.of("mode","expr","xt","yt","t","x","y","points")),Map.entry(FIELD,List.of("p","a","b","points")));

    public static boolean supports(String kind) { return FIELDS.containsKey(kind); }
    static boolean accepts(String kind,String key) { return supports(kind) && FIELDS.get(kind).contains(key); }

    public static String name(String kind) {
        return switch (kind) {
            case GRAPH -> "함수 그래프"; case TABLE -> "표"; case CHART -> "차트"; case FLOW -> "순서도";
            case TIMELINE -> "타임라인"; case TREE -> "개념 트리"; case NUMBER_LINE -> "수직선";
            case RELATION -> "음함수·매개·극좌표"; case MAP -> "장소와 경로 지도"; case CHEM -> "화학식·반응식"; case DIAGRAM -> "과학 일러스트";
            case SCENE -> "연결 도해"; case READING -> "지문·사료 분석"; case FIELD -> "유한체 점 그래프"; case GEOMETRY -> "평면 도형"; case SOLID -> "입체도형"; default -> "시각 자료";
        };
    }

    public static JSONObject spec(JSONObject item) { return copy(item == null ? null : item.optJSONObject("spec")); }

    public static JSONObject copy(JSONObject from) {
        if (from == null) return obj();
        try { return new JSONObject(from.toString()); } catch (Exception malformed) { return obj(); }
    }

    public static JSONObject make(String kind, JSONObject input, float top, float scale, String by, Board.Measure measure) {
        if (!FIELDS.containsKey(kind)) return null;
        JSONObject item = stored(kind, input, top, scale, 1, by);
        BoardPlot.Made drawn = drawing(item, measure);
        if (drawn.items().isEmpty()) return null;
        float bottom = drawn.height();
        for (JSONObject child : drawn.items()) bottom = Math.max(bottom, Board.bounds(child, measure)[3]);
        put(item, "h", (double) Math.max(48, bottom));
        return item;
    }

    static JSONObject stored(String kind, JSONObject input, float top, float scale, float height, String by) {
        if (!FIELDS.containsKey(kind)) return null;
        JSONObject spec = obj();
        if (input != null) for (String key : FIELDS.get(kind)) {
            Object data = bounded(input.opt(key), 0);
            if (data != null) put(spec, key, data);
        }
        return obj("wid", java.util.UUID.randomUUID().toString().substring(0,13), "t", Board.WIDGET, "kind", kind, "spec", spec, "y", limit(top, 0, Board.DEEPEST),
            "h", limit(height, 1, Board.DEEPEST), "scale", limit(scale, .3, 2.5), "by", Board.AI.equals(by) ? Board.AI : Board.ME);
    }

    private static Object bounded(Object value, int level) {
        if (level > 8 || value == null || value == JSONObject.NULL) return null;
        if (value instanceof String text) return text.length() <= Board.TEXT_LIMIT ? text : text.substring(0, Board.TEXT_LIMIT);
        if (value instanceof Number number) return Double.isFinite(number.doubleValue()) ? value : null;
        if (value instanceof Boolean) return value;
        if (value instanceof JSONArray array) {
            JSONArray kept = arr();
            for (int i = 0; i < Math.min(array.length(), 40); i++) {
                Object part = bounded(array.opt(i), level + 1);
                kept.put(part == null ? JSONObject.NULL : part);
            }
            return kept;
        }
        if (value instanceof JSONObject object) {
            JSONObject kept = obj();
            int count = 0;
            for (Iterator<String> keys = object.keys(); keys.hasNext() && count++ < 32;) {
                String key = keys.next();
                Object part = bounded(object.opt(key), level + 1);
                if (part != null) put(kept, key, part);
            }
            return kept;
        }
        return null;
    }

    private static float graphHeight(JSONObject spec, float scale) {
        boolean equal = spec.optBoolean("equalUnits", true);
        return (float) limit(spec.optDouble("height", 475), equal ? 650 : 280, 900) * BoardPlot.compact(scale)
            + BoardPlot.legendHeight(spec, scale);
    }

    /** Older graphs reserved a shallow box. Move subsequent writing down only if the board has room. */
    public static JSONArray reflowGraphs(JSONArray items, Board.Measure measure) {
        float pageEnd = Board.bottom(items, measure);
        for (int i = 0; i < items.length(); i++) {
            JSONObject old = items.optJSONObject(i);
            if (old == null || !Board.WIDGET.equals(old.optString("t")) || !GRAPH.equals(old.optString("kind"))) continue;
            float height = (float) old.optDouble("h");
            if (height <= 150) continue;
            float wanted = graphHeight(spec(old), (float) old.optDouble("scale", .85));
            if (wanted <= height + 1 || pageEnd + wanted - height > Board.DEEPEST) continue;
            JSONObject next = copy(old);
            put(next, "h", wanted);
            items = Board.editedWidget(items, old, next, measure);
            pageEnd += wanted - height;
        }
        return items;
    }

    static BoardPlot.Made drawing(JSONObject item, Board.Measure measure) {
        String kind = item.optString("kind"); JSONObject spec = item.optJSONObject("spec");
        if (spec == null) return new BoardPlot.Made(List.of(), 0);
        float scale = (float) item.optDouble("scale", .85);
        return switch (kind) {
            case GRAPH -> {
                float desired = graphHeight(spec, scale);
                // Existing boards may already occupy the last available space on the page.
                float saved = (float) item.optDouble("h", 1);
                float height = saved > 150 ? Math.min(desired, saved) : desired;
                List<JSONObject> graph = BoardPlot.graph(spec, 0, height, scale);
                yield new BoardPlot.Made(graph, graph.size() == 1 ? 62 * scale : height);
            }
            case TABLE -> BoardPlot.tableLayout(spec.optJSONArray("rows"), spec.optBoolean("header", true), 0,
                Math.max(24, Board.SMALL * scale), measure, "grid".equals(spec.optString("style")));
            case CHART -> BoardPlot.chart(spec, 0, scale, measure);
            case FLOW -> BoardPlot.flow(spec, 0, scale, measure);
            case TIMELINE -> BoardPlot.timeline(spec, 0, scale, measure);
            case TREE -> BoardPlot.tree(spec, 0, scale, measure);
            case NUMBER_LINE -> new BoardPlot.Made(BoardPlot.numberLine(spec, 0, scale), BoardPlot.LINE_HEIGHT);
            case GEOMETRY -> polygon(spec, scale, measure);
            case SOLID -> solid(spec, scale);
            case RELATION -> { float h=530*BoardPlot.compact(scale); yield new BoardPlot.Made(BoardPlot.graph(spec,0,h,scale,true),h); }
            case MAP -> BoardMap.draw(spec,scale,measure); case FIELD -> BoardField.draw(spec,scale,measure);
            case CHEM -> BoardChemistry.draw(spec,scale,measure); case DIAGRAM -> BoardIllustration.draw(spec,scale,measure);
            case SCENE -> BoardScene.draw(spec,scale,measure);
            case READING -> BoardIllustration.reading(spec,scale,measure);
            default -> new BoardPlot.Made(List.of(), 0);
        };
    }

    public static List<JSONObject> render(JSONObject item, Board.Measure measure) {
        try { return drawing(item, measure).items(); }
        catch(IllegalArgumentException invalid) { return List.of(Board.text(Board.MARGIN,0,invalid.getMessage(),28,"red",false,900,Board.AI)); }
    }

    public record Parameter(String name, double value, double min, double max, double step) {}

    public static List<Parameter> parameters(JSONObject spec) {
        JSONObject raw = spec.optJSONObject("parameters");
        if (raw == null) return List.of();
        List<Parameter> out = new ArrayList<>();
        for (Iterator<String> keys = raw.keys(); keys.hasNext();) {
            String name = keys.next();
            if (!name.matches("[a-df-wyz]") || out.size() >= 12) continue;
            JSONObject p = raw.optJSONObject(name);
            double value = p == null ? raw.optDouble(name, 0) : p.optDouble("value", 0);
            if (!Double.isFinite(value)) value = 0;
            double min = limit(p == null ? Math.min(-10, value) : p.optDouble("min", -10), -1e6, 1e6);
            double max = limit(p == null ? Math.max(10, value) : p.optDouble("max", 10), -1e6, 1e6);
            if (max - min < 1e-9) { min = -10; max = 10; }
            double step = limit(p == null ? .1 : p.optDouble("step", .1), (max - min) / 2000, max - min);
            out.add(new Parameter(name, limit(value, min, max), min, max, step));
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }

    static Map<String, Double> values(JSONObject spec) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Parameter parameter : parameters(spec)) out.put(parameter.name(), parameter.value());
        return out;
    }

    public static String description(JSONObject item) {
        String kind = item.optString("kind"); JSONObject spec = item.optJSONObject("spec");
        if (spec == null) return name(kind);
        String body = switch (kind) {
            case GRAPH -> { JSONArray expressions = spec.optJSONArray("functions"); List<String> words = new ArrayList<>();
                for (int i = 0; expressions != null && i < expressions.length(); i++) {
                    JSONObject expression = expressions.optJSONObject(i);
                    words.add(expression == null ? expressions.optString(i) : expression.optString("expr"));
                }
                String parameters = values(spec).toString();
                yield String.join(", ", words) + (parameters.equals("{}") ? "" : " " + parameters); }
            case MAP -> spec.optString("title")+" "+asText(spec.optJSONArray("places"));
            case RELATION -> spec.optString("mode")+" "+spec.optString("expr")+" "+spec.optString("xt")+" "+spec.optString("yt");
            case CHEM -> spec.optString("formula"); case FIELD -> "p="+spec.optInt("p",17)+", a="+spec.optInt("a",0)+", b="+spec.optInt("b",7);
            case DIAGRAM -> spec.optString("title")+" "+spec.optString("preset")+" "+spec.optString("focus");
            case SCENE -> spec.optString("title") + " " + spec.optString("preset") + " · " + spec.optInt("stage", 1) + " · " + String.join(", ", BoardScene.ids(spec.optJSONObject("objects")));
            case READING -> spec.optString("title")+" "+spec.optString("source")+" "+asText(spec.optJSONArray("lines"));
            case TABLE -> asText(spec.optJSONArray("rows"));
            case CHART -> spec.optString("title") + " " + asText(spec.optJSONArray("labels")) + " " + asText(spec.optJSONArray("values"));
            case FLOW -> asText(spec.optJSONArray("steps"));
            case TIMELINE -> asText(spec.optJSONArray("events"));
            case TREE -> spec.optString("root") + " " + asText(spec.optJSONArray("children"));
            case NUMBER_LINE -> spec.optString("from") + " ~ " + spec.optString("to") + " " + asText(spec.optJSONArray("marks"));
            case SOLID -> BoardSolid.name(spec.optString("kind","cube"));
            default -> asText(spec.optJSONArray("vertices"));
        };
        return body.isBlank() ? name(kind) : name(kind) + " · " + body;
    }

    private static String asText(JSONArray part) { return part == null ? "" : part.toString(); }

    public static JSONArray vertices(JSONObject spec, boolean quadrilateral) {
        JSONArray given = spec.optJSONArray("vertices"), out = arr();
        for (int i = 0; given != null && i < Math.min(given.length(), 6); i++) {
            JSONArray at = given.optJSONArray(i);
            if (at != null && at.length() >= 2) out.put(arr(limit(at.optDouble(0, 0), 0, 10), limit(at.optDouble(1, 0), 0, 5)));
        }
        if (out.length() >= 3) return out;
        return quadrilateral ? arr(arr(2, 1), arr(8, 1), arr(8, 4), arr(2, 4)) : arr(arr(2, 1), arr(8, 1), arr(4.3, 4.4));
    }

    public static float vertexX(double x) { return GEOMETRY_LEFT + (float) x * GEOMETRY_UNIT; }
    public static float vertexY(double y) { return GEOMETRY_TOP + (5 - (float) y) * GEOMETRY_UNIT; }

    private static BoardPlot.Made polygon(JSONObject spec, float scale, Board.Measure measure) {
        JSONArray vertices = vertices(spec, false);
        int count = vertices.length();
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i <= 10; i++) items.add(Board.faint(line(vertexX(i), vertexY(0), vertexX(i), vertexY(5), Board.INK, 1.6f)));
        for (int i = 0; i <= 5; i++) items.add(Board.faint(line(vertexX(0), vertexY(i), vertexX(10), vertexY(i), Board.INK, 1.6f)));
        float[] outline = new float[2 * (count + 1)];
        for (int i = 0; i <= count; i++) {
            JSONArray at = vertices.optJSONArray(i % count);
            outline[2 * i] = vertexX(at.optDouble(0)); outline[2 * i + 1] = vertexY(at.optDouble(1));
        }
        items.add(polygon("blue", outline));
        float size = Math.max(24, Board.SMALL * scale);
        StringBuilder lengths = new StringBuilder(), angles = new StringBuilder();
        double area = 0;
        for (int i = 0; i < count; i++) {
            JSONArray at = vertices.optJSONArray(i), next = vertices.optJSONArray((i + 1) % count);
            area += at.optDouble(0) * next.optDouble(1) - at.optDouble(1) * next.optDouble(0);
        }
        boolean crossed = crosses(outline, count);
        for (int i = 0; i < count; i++) {
            JSONArray before = vertices.optJSONArray((i + count - 1) % count), at = vertices.optJSONArray(i), after = vertices.optJSONArray((i + 1) % count);
            double x = at.optDouble(0), y = at.optDouble(1), u = after.optDouble(0) - x, v = after.optDouble(1) - y;
            double ax = before.optDouble(0) - x, ay = before.optDouble(1) - y, length = Math.hypot(u, v);
            double cosine = (ax * u + ay * v) / (Math.hypot(ax, ay) * length);
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosine))));
            if ((ax * v - ay * u) * area > 0) angle = 360 - angle;
            char letter = (char) ('A' + i), next = (char) ('A' + (i + 1) % count);
            if (i > 0) { lengths.append(" · "); angles.append(" · "); }
            lengths.append(letter).append(next).append(' ').append(compact(length));
            angles.append('∠').append(letter).append(' ');
            if (crossed || Math.abs(area) < 1e-8 || !Double.isFinite(angle)) angles.append('—');
            else angles.append(compact(angle)).append('°');
            float cx = vertexX(x), cy = vertexY(y);
            items.add(Board.stroke("blue", 24, false, new float[]{cx, cy}, Board.AI));
            items.add(Board.text(cx + 14, Math.max(0, cy - size - 8), String.valueOf(letter), size, Board.INK, true, 55, Board.AI));
        }
        float y = vertexY(0) + 28, room = Board.WIDTH - 2 * Board.MARGIN;
        Board.Measure ruler = measure == null ? Board.ROUGH : measure;
        for (String label : List.of(lengths.toString(), angles.toString(), crossed ? "변이 교차해 넓이를 표시할 수 없어요." : "넓이 " + compact(Math.abs(area) / 2))) {
            items.add(Board.text(Board.MARGIN, y, label, size, Board.INK, false, room, Board.AI));
            y += ruler.size(label, size, false, room)[1] + 9;
        }
        return new BoardPlot.Made(items, y);
    }

    private static boolean crosses(float[] outline, int count) {
        for (int i = 0; i < count; i++) for (int j = i + 2; j < count; j++) {
            if (i == 0 && j == count - 1) continue;
            float a = turn(outline, i, j), b = turn(outline, i, j + 1);
            float c = turn(outline, j, i), d = turn(outline, j, i + 1);
            if (a * b < 0 && c * d < 0) return true;
        }
        return false;
    }

    private static float turn(float[] p, int edge, int at) {
        int a = edge * 2, b = at * 2;
        return (p[a + 2] - p[a]) * (p[b + 1] - p[a + 1]) - (p[a + 3] - p[a + 1]) * (p[b] - p[a]);
    }

    private static BoardPlot.Made solid(JSONObject spec, float scale) {
        String kind=spec.optString("kind","cube");
        if(!kind.equals("cube") && !kind.equals("box"))return BoardSolid.draw(spec,scale);
        boolean cube = kind.equals("cube");
        if (spec.optBoolean("net")) return net(cube);
        double yaw = Math.toRadians(limit(spec.optDouble("yaw", 30), -180, 180)), pitch = Math.toRadians(limit(spec.optDouble("pitch", 24), -85, 85));
        double x = cube ? 126 : 182, y = cube ? 126 : 116, z = cube ? 126 : 99;
        double[][] points = {{-x,-y,-z},{x,-y,-z},{x,y,-z},{-x,y,-z},{-x,-y,z},{x,-y,z},{x,y,z},{-x,y,z}};
        float[][] projected = new float[points.length][3];
        for (int i = 0; i < points.length; i++) {
            double[] p = points[i]; double depth = -p[0] * Math.sin(yaw) + p[2] * Math.cos(yaw);
            projected[i][0] = 500 + (float) (p[0] * Math.cos(yaw) + p[2] * Math.sin(yaw));
            projected[i][1] = 260 - (float) (p[1] * Math.cos(pitch) - depth * Math.sin(pitch));
            projected[i][2] = (float) (p[1] * Math.sin(pitch) + depth * Math.cos(pitch));
        }
        int[][] faces = {{0,1,2,3},{5,4,7,6},{4,0,3,7},{1,5,6,2},{3,2,6,7},{4,5,1,0}};
        String[] colors = {"blue", "blue", "purple", "purple", "green", "green"};
        List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5));
        order.sort((a, b) -> Float.compare(depth(faces[a], projected), depth(faces[b], projected)));
        List<JSONObject> items = new ArrayList<>();
        for (int face : order) {
            int[] index = faces[face];
            float[] outline = new float[10];
            for (int i = 0; i <= 4; i++) { outline[2 * i] = projected[index[i % 4]][0]; outline[2 * i + 1] = projected[index[i % 4]][1]; }
            JSONObject shape = polygon(colors[face], outline);
            if (depth(index, projected) < -1) items.add(Board.dashed(Board.stroke("ink", 2.5f, false, outline, Board.AI)));
            else items.add(shape);
        }
        items.add(Board.text(Board.MARGIN, 510, cube ? "정육면체" : "직육면체", Math.max(24, Board.SMALL * scale), Board.INK, true, 380, Board.AI));
        return new BoardPlot.Made(items, 570);
    }

    private static float depth(int[] face, float[][] points) {
        float sum = 0; for (int at : face) sum += points[at][2]; return sum / face.length;
    }

    private static BoardPlot.Made net(boolean cube) {
        List<JSONObject> items = new ArrayList<>();
        float w = cube ? 150 : 198, d = cube ? 150 : 108, h = cube ? 150 : 136, left = (Board.WIDTH - 2 * (w + d)) / 2, y = 215;
        String[] colors = {"purple", "blue", "purple", "blue"};
        for (int i = 0; i < 4; i++) {
            float side = i % 2 == 0 ? d : w;
            items.add(face(left, y, left + side, y + h, colors[i])); left += side;
        }
        left = (Board.WIDTH - 2 * (w + d)) / 2 + d;
        items.add(face(left, y - d, left + w, y, "green"));
        items.add(face(left, y + h, left + w, y + h + d, "green"));
        return new BoardPlot.Made(items, y + h + d + 30);
    }

    private static JSONObject face(float x1, float y1, float x2, float y2, String color) {
        return Board.filled(Board.shape(Board.RECT, x1, y1, x2, y2, color, 4, false, Board.AI));
    }
    private static JSONObject polygon(String color, float[] points) {
        JSONObject shape = Board.filled(Board.stroke(color, 4, false, points, Board.AI)); put(shape, "sharp", true); return shape;
    }
    private static JSONObject line(float x1, float y1, float x2, float y2, String color, float pen) {
        return Board.shape(Board.LINE, x1, y1, x2, y2, color, pen, false, Board.AI);
    }
    private static String compact(double number) {
        if (!Double.isFinite(number)) return "—";
        return BoardPlot.number(Math.round(number * 10) / 10.0);
    }
    private static double limit(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
