package dev.jun.gyeot.core;

import static dev.jun.gyeot.core.Jsons.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Named objects and token cells are laid out before connectors, so a moved object keeps its attachments. */
public final class BoardScene {
    private BoardScene() {}

    private static class Examples {
        static final JSONObject ALL = read();

        private static JSONObject read() {
            try (var in = BoardScene.class.getResourceAsStream("board-scenes.json");
                 var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                for (String line; (line = reader.readLine()) != null;) data.append(line);
                return new JSONObject(data.toString());
            } catch (Exception error) {
                throw new IllegalStateException("판서 도해 예시를 읽을 수 없어요.", error);
            }
        }
    }

    private static JSONObject example(String preset) {
        JSONObject found = Examples.ALL.optJSONObject(preset);
        if (found == null) throw new IllegalArgumentException("알 수 없는 도해 예시예요: " + preset);
        return found;
    }

    public static List<String> stages(String preset) {
        if (preset.isBlank()) return List.of();
        JSONArray stages = example(preset).optJSONArray("stages");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < stages.length(); i++) names.add(stages.optJSONObject(i).optString("label"));
        return names;
    }

    /** Preset stages accumulate. A teacher's object and link overrides always apply to the selected stage. */
    public static JSONObject expanded(JSONObject spec) {
        String preset = spec.optString("preset");
        if (preset.isBlank()) return spec;
        JSONObject source = example(preset);
        JSONArray stages = source.optJSONArray("stages");
        int step = integer(spec, "stage", 1, 1, stages.length());
        JSONObject scene = source.optJSONObject("base");
        for (int i = 0; i < step; i++) scene = BoardAgent.merge(scene, stages.optJSONObject(i));
        return BoardAgent.merge(scene, spec);
    }

    static BoardPlot.Made draw(JSONObject input, float scale, Board.Measure measure) {
        JSONObject spec = expanded(input);
        return new Layout(spec, scale, measure == null ? Board.ROUGH : measure).draw();
    }

    public static List<String> ids(JSONObject objects) {
        List<String> ids = new ArrayList<>();
        if (objects != null) objects.keys().forEachRemaining(ids::add);
        ids.sort(String::compareTo);
        return ids;
    }

    static String inventory(JSONObject spec) {
        StringBuilder text = new StringBuilder();
        JSONObject objects = expanded(spec).optJSONObject("objects");
        for (String id : ids(objects)) {
            JSONObject node = objects.optJSONObject(id);
            if (node == null) continue;
            String detail = node.optString("text", node.optString("tokens", node.optString("kind")));
            text.append(id).append('=').append(detail, 0, Math.min(60, detail.length()));
            if (node.has("attach")) text.append('@').append(node.optString("attach"));
            text.append(' ');
        }
        return text.toString().strip();
    }

    /** Coordinates are the widget-local board coordinates used by the preview canvas. */
    public static String hit(JSONObject spec, float scale, Board.Measure measure, float x, float y) {
        Layout layout = new Layout(expanded(spec), scale, measure);
        y -= layout.bodyTop();
        List<String> order = layout.order();
        for (int i = order.size() - 1; i >= 0; i--) {
            Node node = layout.node(order.get(i));
            Box b = node.box;
            if (!node.visible || x < b.left || x > b.left + b.w || y < b.top || y > b.top + b.h) continue;
            if (node.count == 0) return order.get(i);
            for (int c = 1; c <= node.shown; c++) {
                Box cell = node.cell(c);
                if (x >= cell.left && x <= cell.left + cell.w) return order.get(i);
            }
        }
        return "";
    }

    /** Validate the whole group before committing a drag. A rejected move leaves the prior drawing intact. */
    public static boolean move(JSONObject spec, String id, float dx, float dy, float scale, Board.Measure measure) {
        JSONObject effective = expanded(spec);
        JSONObject original = effective.optJSONObject("objects").optJSONObject(id);
        if (original == null) return false;
        Point offset = pair(original, "offset", new Point(0, 0));
        JSONObject next = BoardAgent.merge(spec, obj("objects", obj(id, obj("offset", arr(offset.x + dx, offset.y + dy)))));
        try {
            draw(next, scale, measure);
        } catch (IllegalArgumentException outside) {
            return false;
        }
        put(spec, "objects", next.optJSONObject("objects"));
        return true;
    }

    private record Point(float x, float y) {}
    private record Box(float left, float top, float w, float h) {
        Point anchor(String pin) {
            return switch (pin) {
                case "top" -> new Point(left + w / 2, top);
                case "bottom" -> new Point(left + w / 2, top + h);
                case "left" -> new Point(left, top + h / 2);
                case "right" -> new Point(left + w, top + h / 2);
                case "center" -> new Point(left + w / 2, top + h / 2);
                default -> throw new IllegalArgumentException("알 수 없는 연결 위치예요: " + pin);
            };
        }
    }

    private record Anchor(Point point, boolean visible) {}
    private record Ref(String id, int cell, String pin) {
        static Ref parse(String text) {
            String[] fields = text.split("\\.", -1);
            if (fields.length == 1 && !text.isBlank()) return new Ref(text, 0, "center");
            if (fields.length == 2) return new Ref(fields[0], 0, fields[1]);
            if (fields.length == 3) {
                try {
                    int cell = Integer.parseInt(fields[1]);
                    if (cell > 0) return new Ref(fields[0], cell, fields[2]);
                } catch (NumberFormatException ignored) { /* Report the whole reference below. */ }
            }
            throw new IllegalArgumentException("연결 위치는 물체.방향 또는 물체.칸번호.방향으로 적어 주세요: " + text);
        }
    }

    private static Point pair(JSONObject data, String key, Point fallback) {
        if (!data.has(key)) return fallback;
        JSONArray values = data.optJSONArray(key);
        if (values == null || values.length() != 2 || !Double.isFinite(values.optDouble(0))
                || !Double.isFinite(values.optDouble(1)))
            throw new IllegalArgumentException(key + "에 유한한 숫자 두 개가 필요해요.");
        return new Point((float) values.optDouble(0), (float) values.optDouble(1));
    }

    private static int integer(JSONObject data, String key, int fallback, int min, int max) {
        if (!data.has(key)) return fallback;
        double value = data.optDouble(key);
        if (!Double.isFinite(value) || value != Math.rint(value) || value < min || value > max)
            throw new IllegalArgumentException(key + " 값은 " + min + "부터 " + max + "까지의 정수예요.");
        return (int) value;
    }

    private record Node(JSONObject data, Box box, boolean visible, int count, int shown, int group) {
        Box cell(int number) {
            if (number < 1 || number > count)
                throw new IllegalArgumentException("줄의 칸 번호를 확인해 주세요: " + number);
            // All rows of the same width and length line up, including cells still hidden by reveal.
            float gap = 4, groupGap = 10;
            float width = (box.w - gap * (count - 1) - groupGap * ((count - 1) / group)) / count;
            if (width < 20) throw new IllegalArgumentException("글자 칸이 너무 좁아요. 줄을 넓히거나 칸 수를 줄여 주세요.");
            int i = number - 1;
            return new Box(box.left + i * (width + gap) + groupGap * (i / group), box.top, width, box.h);
        }

        Anchor anchor(int cell, String pin) {
            Box target = cell == 0 ? box : cell(cell);
            return new Anchor(target.anchor(pin), visible && (cell == 0 || cell <= shown));
        }
    }

    private static final class Layout {
        final JSONObject spec, objects;
        final float height, font;
        final Board.Measure measure;
        final Map<String, Node> nodes = new HashMap<>();
        final Set<String> visiting = new HashSet<>();
        final List<JSONObject> ink = new ArrayList<>();

        Layout(JSONObject spec, float scale, Board.Measure measure) {
            this.spec = spec;
            this.measure = measure;
            font = Math.max(24, Board.SMALL * scale);
            height = integer(spec, "height", 360, 80, 1200);
            objects = spec.optJSONObject("objects");
            if (objects == null || objects.length() == 0 || objects.length() > 24)
                throw new IllegalArgumentException("도해에는 이름이 있는 물체를 1~24개 넣어 주세요.");
        }

        Node node(String id) {
            Node cached = nodes.get(id);
            if (cached != null) return cached;
            JSONObject data = objects.optJSONObject(id);
            if (data == null) throw new IllegalArgumentException("연결할 물체가 없어요: " + id);
            if (id.isBlank() || id.contains(".") || id.length() > 40)
                throw new IllegalArgumentException("물체 이름은 점 없이 1~40자로 적어 주세요.");
            if (!visiting.add(id)) throw new IllegalArgumentException("물체 연결이 순환해요: " + id);
            Point size = pair(data, "size", new Point(150, 54));
            if (!(size.x >= 20 && size.x <= Board.WIDTH && size.y >= 12 && size.y <= 1200))
                throw new IllegalArgumentException(id + "의 크기를 확인해 주세요.");
            Anchor target;
            String attachment = data.optString("attach");
            if (attachment.isBlank()) {
                Point at = pair(data, "at", null);
                if (at == null) throw new IllegalArgumentException(id + "에 at 좌표 또는 attach 연결이 필요해요.");
                target = new Anchor(at, true);
            } else {
                target = anchor(Ref.parse(attachment));
            }
            Point offset = pair(data, "offset", new Point(0, 0));
            Point pin = new Box(0, 0, size.x, size.y).anchor(data.optString("pin", "center"));
            Box box = new Box(target.point.x + offset.x - pin.x, target.point.y + offset.y - pin.y, size.x, size.y);
            boolean visible = target.visible && !data.optBoolean("hidden");
            if (visible && !(box.left >= 0 && box.top >= 0 && box.left + box.w <= Board.WIDTH + .01f && box.top + box.h <= height + .01f))
                throw new IllegalArgumentException(id + "가 도해 밖에 있어요. 위치, 크기 또는 height를 조정해 주세요.");
            int count = 0;
            String kind = data.optString("kind");
            switch (kind) {
                case "row" -> {
                    JSONArray tokens = data.optJSONArray("tokens");
                    count = tokens == null ? 0 : tokens.length();
                    if (count < 1 || count > 24) throw new IllegalArgumentException(id + "의 tokens에 1~24개의 글자나 기호가 필요해요.");
                }
                case "text", "box", "oval", "path" -> { }
                default -> throw new IllegalArgumentException("알 수 없는 도해 물체예요: " + kind);
            }
            Node node = new Node(data, box, visible, count, integer(data, "reveal", count, 0, count),
                    integer(data, "group", Math.max(1, count), 1, Math.max(1, count)));
            visiting.remove(id);
            nodes.put(id, node);
            return node;
        }

        Anchor anchor(Ref ref) { return node(ref.id).anchor(ref.cell, ref.pin); }

        List<String> order() {
            List<String> order = ids(objects);
            for (String id : order) node(id);
            // Stable paint order on both Android's and desktop Java's JSON implementations.
            order.sort(Comparator.comparingInt(id -> nodes.get(id).data.optInt("z", 0)));
            return order;
        }

        JSONObject heading() {
            String title = spec.optString("title").strip(), label = spec.optString("label").strip();
            String heading = title.isEmpty() ? label : label.isEmpty() ? title : title + " · " + label;
            return heading.isEmpty() ? null : Board.text(Board.MARGIN, 0, heading, font, Board.INK,
                    true, Board.WIDTH - 2 * Board.MARGIN, Board.AI);
        }

        float bodyTop() {
            JSONObject title = heading();
            return title == null ? 0 : Board.bounds(title, measure)[3] + 12;
        }

        BoardPlot.Made draw() {
            List<String> order = order();
            JSONObject links = spec.optJSONObject("links");
            if (spec.has("links") && (links == null || links.length() > 32))
                throw new IllegalArgumentException("links에는 이름이 있는 연결선을 32개까지 넣을 수 있어요.");
            for (String id : ids(links)) link(id, links.optJSONObject(id));
            for (String id : order) paint(nodes.get(id));
            float top = 0;
            JSONObject text = heading();
            if (text != null) {
                top = Board.bounds(text, measure)[3] + 12;
                for (JSONObject item : ink) Board.shift(item, top);
                ink.add(0, text);
            }
            return new BoardPlot.Made(ink, top + height);
        }

        void link(String id, JSONObject data) {
            if (data == null) throw new IllegalArgumentException("연결선 설정을 확인해 주세요: " + id);
            Ref from = Ref.parse(data.optString("from")), to = Ref.parse(data.optString("to"));
            Node a = node(from.id), b = node(to.id);
            boolean pairs = data.optBoolean("pairwise");
            if (pairs && (a.count == 0 || a.count != b.count || from.cell != 0 || to.cell != 0))
                throw new IllegalArgumentException("칸별 연결에는 같은 칸 수의 두 줄이 필요해요: " + id);
            int count = pairs ? a.count : 1;
            for (int i = 1; i <= count; i++) {
                Anchor start = a.anchor(pairs ? i : from.cell, from.pin), end = b.anchor(pairs ? i : to.cell, to.pin);
                if (!start.visible || !end.visible || data.optBoolean("hidden")) continue;
                JSONObject line = Board.shape(Board.LINE, start.point.x, start.point.y, end.point.x, end.point.y,
                        data.optString("color", "ink"), data.optBoolean("arrow") ? 4 : 3, data.optBoolean("arrow"), Board.AI);
                ink.add(data.optBoolean("dashed") ? Board.dashed(line) : line);
            }
        }

        void paint(Node node) {
            JSONObject data = node.data;
            Box box = node.box;
            String kind = data.optString("kind"), color = data.optString("color", "blue");
            if (kind.equals("row")) {
                JSONArray tokens = data.optJSONArray("tokens");
                int active = integer(data, "active", 0, 0, node.count);
                int span = integer(data, "span", 1, 1, active > 0 ? node.count - active + 1 : node.count);
                for (int i = 1; i <= node.count; i++) {
                    Box cell = node.cell(i);
                    Object token = tokens.opt(i - 1);
                    if (!(token instanceof String word) || word.isBlank())
                        throw new IllegalArgumentException("각 칸에 빈칸이 아닌 글자나 기호를 넣어 주세요.");
                    if (!node.visible || i > node.shown) continue;
                    boolean selected = active > 0 && i >= active && i < active + span;
                    outline(cell, "box", color, selected, selected ? 4 : 1.5f);
                    words(cell, word, color, selected);
                }
            } else if (kind.equals("path")) {
                JSONArray points = data.optJSONArray("points");
                if (points == null || points.length() < 2 || points.length() > 40)
                    throw new IllegalArgumentException("자유 윤곽에는 2~40개의 점이 필요해요.");
                boolean close = data.optBoolean("closed");
                int n = points.length() + (close ? 1 : 0);
                float[] xy = new float[n * 2];
                for (int i = 0; i < n; i++) {
                    JSONArray p = points.optJSONArray(i % points.length());
                    double x = p == null ? Double.NaN : p.optDouble(0), y = p == null ? Double.NaN : p.optDouble(1);
                    if (!(x >= 0 && x <= 100 && y >= 0 && y <= 100) || p.length() != 2)
                        throw new IllegalArgumentException("윤곽 좌표는 물체 내부의 0~100 범위예요.");
                    xy[2 * i] = box.left + box.w * (float) x / 100;
                    xy[2 * i + 1] = box.top + box.h * (float) y / 100;
                }
                if (!node.visible) return;
                JSONObject path = Board.stroke(color, 4, false, xy, Board.AI);
                if (close && data.optBoolean("fill", true)) Board.filled(path);
                if (data.optBoolean("sharp")) put(path, "sharp", true);
                ink.add(path);
            } else if (node.visible && !kind.equals("text")) {
                outline(box, kind, color, data.optBoolean("fill", true), 3);
            }
            if (node.visible && !kind.equals("row") && !data.optString("text").isBlank())
                words(box, data.optString("text"), data.optString("ink", color), false);
        }

        void outline(Box box, String kind, String color, boolean fill, float pen) {
            boolean oval = kind.equals("oval");
            JSONObject shape = Board.shape(oval ? Board.OVAL : Board.RECT, box.left, box.top, box.left + box.w,
                    box.top + box.h, color, pen, false, Board.AI);
            if (!oval) Board.rounded(shape, Math.min(12, box.h / 5));
            ink.add(fill ? Board.filled(shape) : shape);
        }

        void words(Box box, String value, String color, boolean bold) {
            float room = Math.max(Board.MIN_TEXT_WIDTH, box.w - 8), size = font;
            float[] actual = measure.size(value, size, bold, room);
            while (size > 16 && actual[1] > box.h - 2) {
                size = Math.max(16, size - 2);
                actual = measure.size(value, size, bold, room);
            }
            if (actual[1] > box.h + 1) throw new IllegalArgumentException("이름표가 너무 길어요. 칸을 넓혀 주세요: " + value);
            ink.add(Board.text(box.left + (box.w - actual[0]) / 2, box.top + (box.h - actual[1]) / 2,
                    value, size, color, bold, room, Board.AI));
        }
    }
}
