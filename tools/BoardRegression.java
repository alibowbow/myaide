package dev.jun.gyeot.core;

import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

final class BoardRegression {
    private static int checked;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }

    public static void main(String[] args) throws Exception {
        graph();
        tableAndFigure();
        roundTrip();
        pagesAndGeometry();
        System.out.println("Board regression checks passed: " + checked);
    }

    private static void graph() {
        check(BoardPlot.parameters("a(x−h)^2+k").equals(List.of("a", "h", "k")), "coefficient discovery");
        var first = BoardPlot.function("a(x-h)^2+k", Map.of("a", 2.0, "h", 3.0, "k", 4.0));
        check(first.at(3) == 4 && first.at(4) == 6, "parameterized graph vertex");
        var tiny = BoardPlot.graph(obj("x", arr(0, 1e-12), "y", arr(-2, 2), "functions", arr("x")), 0, 460, .85f);
        check(tiny.size() < 150, "tick count must scale with range");
        check(!BoardPlot.number(.0001).equals(BoardPlot.number(.0002)), "small decimals remain distinguishable");
        var invalid = BoardPlot.graph(obj("functions", arr("bad")), 0, 460, .85f);
        check(invalid.size() == 1 && invalid.get(0).optString("text").contains("함수식"), "invalid expression is visible");

        var clipped = BoardPlot.graph(obj("x", arr(-5, 5), "functions", arr(obj("expr", "x", "from", -10, "color", "pink"))), 0, 460, .85f);
        check(clipped.stream().noneMatch(i -> "pink".equals(i.optString("c")) && i.optJSONArray("p") != null && i.optJSONArray("p").length() == 2), "clipped domain cannot invent an endpoint");
        var asymptote = BoardPlot.graph(obj("x", arr(-5, 5), "y", arr(-10000, 10000), "functions", arr(obj("expr", "1/(x-0.01)", "color", "pink"))), 0, 460, .85f);
        List<JSONObject> curve = asymptote.stream().filter(i -> "pink".equals(i.optString("c")) && Board.STROKE.equals(i.optString("t"))).toList();
        float pole = (float) (90 + 850 * (5.01 / 10));
        check(curve.size() >= 2, "an off-sample pole splits the visible curve");
        check(curve.stream().noneMatch(i -> { float[] b = Board.bounds(i, Board.ROUGH); return b[0] + 3 < pole && b[2] - 3 > pole; }), "no drawn stroke crosses the pole");
    }

    private static void tableAndFigure() {
        var table = BoardPlot.tableLayout(arr(arr("열", "헤더"), arr("긴한국어셀".repeat(35), "끝"), arr("다음 행", "값")), true, 0, 30.6f, Board.ROUGH);
        JSONObject longCell = table.items().stream().filter(i -> i.optString("text").length() > 100).findFirst().orElseThrow();
        JSONObject next = table.items().stream().filter(i -> "다음 행".equals(i.optString("text"))).findFirst().orElseThrow();
        check(Board.bounds(longCell, Board.ROUGH)[3] < next.optDouble("y"), "a multiline cell fits its own row");
        check(table.height() > 250, "table spacing uses measured text rather than a fixed height");

        var flow = new BoardScript.Flow(arr(), Board.ROUGH, .85f);
        var figure = flow.place(new BoardScript.Step("", List.of(obj("op", "figure", "height", 1200, "shapes", arr(obj("kind", "point", "at", arr(250, 20)))))));
        var below = flow.place(new BoardScript.Step("", List.of(obj("op", "write", "text", "다음 문장")))).get(0);
        check(Board.bounds(below, Board.ROUGH)[1] - Board.bounds(figure.get(0), Board.ROUGH)[3] < 100, "sparse figure uses only its bounding height");
        var mark = flow.place(new BoardScript.Step("", List.of(obj("op", "write", "text", "핵심은 가운데 있다"), obj("op", "mark", "text", "가운데", "kind", "underline"))));
        check(mark.get(1).optDouble("x1") > mark.get(0).optDouble("x"), "underline starts at the matching phrase");
        var unknown = flow.place(BoardScript.parse("{\"steps\":[{\"draw\":[{\"op\":\"no_such_tool\"}]}]}").steps().get(0));
        check(unknown.size() == 1 && unknown.get(0).optString("text").contains("도구"), "unsupported tools are explained");
    }

    private static void roundTrip() throws Exception {
        var flow = new BoardScript.Flow(arr(), Board.ROUGH);
        JSONObject spec = obj("op", "graph", "functions", arr(obj("expr", "a*x^2", "label", "포물선")),
            "parameters", obj("a", obj("value", 1, "min", -3, "max", 3)));
        JSONObject graph = flow.place(new BoardScript.Step("", List.of(spec))).get(0);
        Board.inLesson(graph, "lesson", 2);
        JSONArray original = arr(graph, Board.text(48, (float) (graph.optDouble("y") + graph.optDouble("h") + 20), "다음", 36, "ink", false, 900, Board.ME));
        JSONArray stored = Board.clean(new JSONArray(original.toString()));
        check(stored.length() == 2 && Board.WIDGET.equals(stored.getJSONObject(0).optString("t")), "the tutor graph is one stored object alongside legacy text");
        check(BoardWidget.spec(stored.getJSONObject(0)).optJSONArray("functions").getJSONObject(0).getString("expr").equals("a*x^2"), "original expression survives save");
        check(Board.words(stored, 6000).contains("a*x^2"), "retained algebra is available to follow-up questions");

        JSONObject grown = BoardWidget.make(BoardWidget.GRAPH, obj("height", 700, "functions", arr("x")), (float) graph.optDouble("y"), 1, Board.AI, Board.ROUGH);
        JSONArray edited = Board.editedWidget(original, graph, grown, Board.ROUGH);
        check(edited.getJSONObject(0).optString("ls").equals("lesson") && edited.getJSONObject(0).optInt("st") == 2, "edit retains lesson and step identity");
        check(edited.getJSONObject(1).optDouble("y") > original.getJSONObject(1).optDouble("y"), "increasing the widget height reflows following items");

        JSONObject picture = Board.image("abcdef-1.png", 48, 100, 600, 800, Board.ME);
        var marks = new BoardScript.Flow(arr(picture), Board.ROUGH).place(new BoardScript.Step("", List.of(
            obj("op", "annotate", "image", 1, "shapes", arr(obj("kind", "circle", "box", arr(55, 50, 180, 190)))))));
        check(Board.clean(arr(picture, marks.get(0))).getJSONObject(1).optString("on").equals("abcdef-1.png"), "image ownership survives saving");
    }

    private static void pagesAndGeometry() {
        JSONArray longBoard = arr(Board.text(48, 600, "첫 페이지", 36, "ink", false, 400, Board.ME),
            Board.text(48, 26000, "마지막 페이지", 36, "ink", false, 400, Board.ME));
        List<Float> pages = Board.pageEnds(longBoard, Board.ROUGH, 1327);
        check(pages.size() > 19 && pages.get(pages.size() - 1) == Board.bottom(longBoard, Board.ROUGH) + Board.MARGIN, "export covers all content beyond the old cutoff");
        JSONObject triangle = BoardWidget.make(BoardWidget.GEOMETRY, obj("vertices", arr(arr(1, 1), arr(5, 1), arr(1, 4))), 0, .85f, Board.ME, Board.ROUGH);
        var triangleItems = BoardWidget.render(triangle, Board.ROUGH);
        check(triangleItems.stream().anyMatch(i -> i.optString("text").contains("∠A 90°")), "triangle angles use the vertex coordinates");
        check(triangleItems.stream().anyMatch(i -> i.optString("text").equals("넓이 6")), "triangle area matches the geometry");
        JSONObject crossed = BoardWidget.make(BoardWidget.GEOMETRY, obj("vertices", arr(arr(1, 1), arr(5, 4), arr(1, 4), arr(5, 1))), 0, .85f, Board.ME, Board.ROUGH);
        check(BoardWidget.render(crossed, Board.ROUGH).stream().anyMatch(i -> i.optString("text").contains("변이 교차해")), "crossed edges cannot show a misleading area");
        JSONObject solid = BoardWidget.make(BoardWidget.SOLID, obj("kind", "cube", "net", true), 0, .85f, Board.ME, Board.ROUGH);
        check(BoardWidget.render(solid, Board.ROUGH).stream().filter(i -> Board.RECT.equals(i.optString("t"))).count() == 6, "cube net is six faces");
    }
}
