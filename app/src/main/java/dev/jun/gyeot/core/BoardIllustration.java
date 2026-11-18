package dev.jun.gyeot.core;

import static dev.jun.gyeot.core.Jsons.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Editable labelled layers share the vector vocabulary of the free drawing tool. */
public final class BoardIllustration {
    private BoardIllustration() {}

    private static JSONObject oval(int x, int y, int w, int h, String color) {
        return obj(
                "kind",
                "oval",
                "from",
                arr(x, y),
                "to",
                arr(x + w, y + h),
                "fill",
                true,
                "color",
                color);
    }

    private static JSONObject path(String color, int... xy) {
        JSONArray points = arr();
        for (int i = 0; i + 1 < xy.length; i += 2) points.put(arr(xy[i], xy[i + 1]));
        return obj("kind", "path", "points", points, "color", color);
    }

    private static JSONObject part(String key, String name, int x, int y, JSONObject... shapes) {
        return obj(
                "id",
                key,
                "label",
                name,
                "at",
                arr(x, y),
                "shapes",
                new JSONArray(java.util.Arrays.asList(shapes)));
    }

    public static JSONArray parts(JSONObject spec) {
        JSONArray parts = arr();
        String preset = spec.optString("preset");
        if (preset.equals("plantCell") || preset.equals("animalCell")) {
            boolean plant = preset.equals("plantCell");
            if (plant)
                parts.put(
                        part(
                                "wall",
                                "세포벽",
                                170,
                                218,
                                obj(
                                        "kind",
                                        "rect",
                                        "from",
                                        arr(165, 30),
                                        "to",
                                        arr(830, 445),
                                        "color",
                                        "green")));
            parts.put(
                    part(
                            "membrane",
                            "세포막",
                            plant ? 185 : 165,
                            225,
                            plant
                                    ? obj(
                                            "kind",
                                            "rect",
                                            "from",
                                            arr(182, 48),
                                            "to",
                                            arr(813, 427),
                                            "color",
                                            "blue")
                                    : oval(150, 35, 690, 400, "blue")));
            if (plant)
                parts.put(part("vacuole", "중앙 액포", 545, 210, oval(375, 88, 365, 244, "blue")));
            parts.put(
                    part(
                            "nucleus",
                            "핵",
                            285,
                            220,
                            oval(215, 147, 150, 140, "purple"),
                            oval(273, 198, 36, 33, "purple")));
            parts.put(
                    part(
                            "mitochondrion",
                            "미토콘드리아",
                            480,
                            355,
                            oval(420, 321, 131, 62, "orange"),
                            path(
                                    "orange", 430, 350, 451, 339, 465, 369, 487, 339, 506, 367, 536,
                                    350)));
            if (plant)
                parts.put(
                        part(
                                "chloroplast",
                                "엽록체",
                                658,
                                360,
                                oval(605, 332, 122, 52, "green"),
                                path("green", 620, 355, 705, 355)));
        } else if (preset.equals("earthLayers")) {
            parts.put(part("crust", "지각", 500, 48, oval(290, 38, 420, 420, "green")));
            parts.put(part("mantle", "맨틀", 500, 115, oval(299, 47, 402, 402, "orange")));
            parts.put(part("outerCore", "외핵", 500, 176, oval(375, 123, 250, 250, "pink")));
            parts.put(part("innerCore", "내핵", 500, 244, oval(441, 189, 118, 118, "purple")));
        } else if (preset.equals("neuron")) {
            parts.put(
                    part(
                            "dendrite",
                            "가지돌기",
                            172,
                            176,
                            path("green", 315, 245, 212, 183, 120, 192),
                            path("green", 214, 184, 168, 112),
                            path("green", 295, 242, 194, 277, 100, 257),
                            path("green", 190, 275, 135, 324)));
            parts.put(
                    part(
                            "soma",
                            "세포체",
                            315,
                            228,
                            oval(255, 180, 130, 112, "blue"),
                            oval(296, 214, 42, 40, "purple")));
            parts.put(part("axon", "축삭", 552, 240, path("orange", 380, 239, 770, 239)));
            parts.put(
                    part(
                            "terminal",
                            "축삭 말단",
                            795,
                            245,
                            path("pink", 755, 239, 816, 185, 874, 168),
                            path("pink", 756, 239, 827, 247, 883, 222),
                            path("pink", 756, 239, 811, 297, 874, 305)));
        }
        JSONArray custom = spec.optJSONArray("layers");
        for (int i = 0; custom != null && i < Math.min(12, custom.length()); i++)
            if (custom.optJSONObject(i) != null) parts.put(custom.optJSONObject(i));
        return parts;
    }

    static BoardPlot.Made draw(JSONObject spec, float scale, Board.Measure measure) {
        var flow = new BoardScript.Flow(arr(), measure, scale);
        JSONArray parts = parts(spec);
        if (parts.length() == 0) throw new IllegalArgumentException("도해에 그림 층을 하나 이상 넣어 주세요.");
        List<JSONObject> items = new ArrayList<>(), pins = new ArrayList<>();
        String focus = spec.optString("focus");
        boolean found = focus.isBlank();
        float h = (float) Math.max(180, Math.min(1000, spec.optDouble("height", 480))),
                size = Math.max(24, Board.SMALL * scale);
        JSONObject title =
                Board.text(
                        Board.MARGIN,
                        0,
                        spec.optString("title", "구조 모식도"),
                        size,
                        "ink",
                        true,
                        900,
                        Board.AI);
        items.add(title);
        float top = Board.bounds(title, measure)[3] + 12;
        JSONArray legend = arr();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject layer = parts.optJSONObject(i);
            boolean selected = focus.equals(layer.optString("id"));
            found |= selected;
            if (layer.optBoolean("hidden")) continue;
            String label = layer.optString("label", layer.optString("id"));
            JSONArray shapes = layer.optJSONArray("shapes");
            int drawn = 0;
            for (int j = 0; shapes != null && j < Math.min(18, shapes.length()); j++) {
                JSONObject item = flow.shape(shapes.optJSONObject(j), top, h);
                if (item != null) {
                    items.add(!focus.isBlank() && !selected ? Board.faint(item) : item);
                    drawn++;
                }
            }
            if (drawn == 0) throw new IllegalArgumentException("도해의 각 층에 표시할 도형을 넣어 주세요.");
            JSONArray anchor = layer.optJSONArray("at");
            String number = String.valueOf(i + 1);
            if (anchor != null)
                pins.add(
                        Board.text(
                                (float) anchor.optDouble(0, 48),
                                (float) anchor.optDouble(1, 0) + top,
                                number,
                                size,
                                selected ? "red" : "ink",
                                true,
                                70,
                                Board.AI));
            legend.put((selected ? "▶ " : "") + number + "  " + label);
        }
        if (!found) throw new IllegalArgumentException("강조할 도해 층을 찾지 못했어요.");
        items.addAll(pins);
        float y = top + h + 12;
        for (int i = 0; i < legend.length(); i++) {
            String label = legend.optString(i);
            JSONObject line =
                    Board.text(
                            Board.MARGIN,
                            y,
                            label,
                            size,
                            label.startsWith("▶") ? "red" : "ink",
                            label.startsWith("▶"),
                            900,
                            Board.AI);
            items.add(line);
            y = Board.bounds(line, measure)[3] + 3;
        }
        return new BoardPlot.Made(items, y);
    }

    static BoardPlot.Made reading(JSONObject spec, float scale, Board.Measure measure) {
        List<JSONObject> items = new ArrayList<>();
        float y = 0, size = Math.max(24, Board.SMALL * scale);
        for (String field : List.of("title", "source"))
            if (!spec.optString(field).isBlank()) {
                JSONObject item =
                        Board.text(
                                Board.MARGIN,
                                y,
                                spec.optString(field),
                                size,
                                field.equals("title") ? "ink" : "blue",
                                field.equals("title"),
                                900,
                                Board.AI);
                items.add(item);
                y = Board.bounds(item, measure)[3] + 9;
            }
        JSONArray rows = spec.optJSONArray("lines");
        if (rows == null || rows.length() == 0)
            throw new IllegalArgumentException("분석할 지문이나 사료를 입력하세요.");
        for (int i = 0; i < Math.min(rows.length(), 20); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optString("text").isBlank())
                throw new IllegalArgumentException("본문이 빈 줄이 있어요.");
            JSONObject quote =
                    Board.text(
                            Board.MARGIN + 16,
                            y,
                            row.optString("text"),
                            size,
                            "ink",
                            false,
                            875,
                            Board.AI);
            items.add(quote);
            float end = Board.bounds(quote, measure)[3];
            items.add(
                    Board.shape(
                            Board.LINE,
                            Board.MARGIN,
                            y,
                            Board.MARGIN,
                            end,
                            "blue",
                            4,
                            false,
                            Board.AI));
            y = end + 8;
            if (spec.optBoolean("reveal", true) && !row.optString("note").isBlank()) {
                JSONObject note =
                        Board.text(
                                Board.MARGIN + 29,
                                y,
                                row.optString("note"),
                                size * .94f,
                                "blue",
                                false,
                                860,
                                Board.AI);
                items.add(note);
                y = Board.bounds(note, measure)[3] + 8;
            }
            y += 12;
        }
        return new BoardPlot.Made(items, y);
    }
}
