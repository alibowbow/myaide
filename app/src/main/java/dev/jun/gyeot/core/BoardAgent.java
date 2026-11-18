package dev.jun.gyeot.core;

import static dev.jun.gyeot.core.Jsons.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Resolves model-facing names before playback. Only existing widget fields can be changed. */
public final class BoardAgent {
    private BoardAgent() {}

    public static final String GUIDE =
            """

Plan the lesson visually. Choose tools yourself from the question, even without an explicit tool name.
Pair complementary views when they teach distinct things. Use a map + timeline for a journey and chronology,
diagram + graph for forces, chemistry + table for conservation, reading for source evidence and grammar.
Render the requested visual in draw. A verbal promise or a sequence of boxes does not plot a graph.
Native tools (JSON inside draw):
{"op":"map","title":"교류 경로","region":"eastAsia","places":[{"name":"경주","lon":129.22,"lat":35.86}],"route":false}
Physical coastlines, world, korea, eastAsia, europe, mediterranean or bounds [west,south,east,north]. Up to 16 places.
Route connects places in order. Use coordinates inside the extent. Modern coastline is not a historical border.
{"op":"solid","kind":"pyramid","yaw":30,"pitch":24} draws a rotatable prism, pyramid, tetrahedron, cylinder, cone, sphere, cube or box.
A custom mesh accepts vertices [[x,y,z],...] and faces [[0,1,2],...] with zero-based indices. Only cube and box support net.
{"op":"chemistry","formula":"2H2O → 2H2 + O2"} counts atoms, including parentheses and coefficients.
Use neutral formulae or a neutral equation here. Explain ionic charge separately in writing.
{"op":"diagram","preset":"plantCell","as":"cell","title":"식물 세포","focus":"chloroplast"}
Presets: plantCell (wall,membrane,vacuole,nucleus,mitochondrion,chloroplast), animalCell (membrane,nucleus,mitochondrion),
neuron (dendrite,soma,axon,terminal), earthLayers (crust,mantle,outerCore,innerCore).
For any other biological structure, circuit, force diagram, cross section, etc., build your own editable layers:
{"op":"diagram","title":"힘의 방향","height":340,"layers":[{"id":"force","label":"작용하는 힘","at":[610,120],"shapes":[{"kind":"arrow","from":[230,140],"to":[600,140],"color":"blue"}]}]}.
Layers use the figure shape schema, up to 12 layers and 18 shapes each. focus takes a layer id.
{"op":"reading","title":"문장 구조","source":"연습용 창작 예문","lines":[{"text":"The stars faded as the sun rose.","note":"as절은 시간을 나타낸다."}],"reveal":true}
For excerpts preserve the wording and distinguish supplied, paraphrased and invented material; never invent an attribution.
{"op":"graph","points":[{"at":[1,5],"label":"P"}]} draws standalone scatter points; functions are optional.
{"op":"finitefield","p":17,"a":0,"b":7,"points":[{"at":[1,5],"label":"G"}]} plots ALL affine solutions to y² ≡ x³ + ax + b mod p.
Use a prime 5..97 for a labelled teaching example. Clearly distinguish a toy field from the actual large field.
{"op":"relation","mode":"implicit","expr":"y^2=x^3+7","x":[-3,4],"y":[-8,8]} traces a real continuous level set on a numerical grid.
For singularities prefer an explicit graph with split domains. For a parametric curve use mode parametric with xt and yt expressions in t
and a numeric t range; for polar use mode polar with expr in t (angle, radians). Set x and y bounds for visibility.
A real curve y²=f(x) uses two functions sqrt(f(x)) and -sqrt(f(x)) on their real domain. Never mix these with a modular plot.
Any widget may declare a unique "as" alias. In a later step mutate it, or use an existing wid from the board inventory:
{"op":"update","target":"cell","set":{"focus":"nucleus"}} or {"op":"update","target":"graph1","set":{"parameters":{"a":{"value":2}}}}.
Use update for a requested change to an existing object. Keep updates first in that step. Objects merge, arrays replace.
Only use the documented operations and field names. Replaying steps must make sense when read aloud.
""";
    private static final Set<String> BASIC =
            Set.of(
                    "write",
                    "text",
                    "heading",
                    "title",
                    "note",
                    "callout",
                    "box",
                    "divider",
                    "line",
                    "figure",
                    "draw",
                    "mark",
                    "annotate",
                    "space");

    private static String kind(String op) {
        return switch (op) {
            case "plot", "scatter" -> "graph";
            case "number_line" -> "numberline";
            case "steps" -> "flow";
            case "mindmap" -> "tree";
            default -> op;
        };
    }

    public static JSONArray snapshot(JSONArray board) {
        JSONArray widgets = arr();
        for (int i = 0; i < board.length(); i++) {
            JSONObject item = board.optJSONObject(i);
            if (item != null && Board.WIDGET.equals(item.optString("t")))
                widgets.put(BoardWidget.copy(item));
        }
        return widgets;
    }

    public static String inventory(JSONArray board) {
        List<String> lines = new ArrayList<>();
        int available = 20000;
        for (int i = board.length() - 1; i >= 0 && lines.size() < 40; i--) {
            JSONObject item = board.optJSONObject(i);
            if (item == null || !Board.WIDGET.equals(item.optString("t"))) continue;
            String data = item.optString("spec"), summary = BoardWidget.description(item);
            String value =
                    item.optString("wid")
                            + " "
                            + item.optString("kind")
                            + " "
                            + (data.length() <= 3200
                                    ? data
                                    : summary.substring(0, Math.min(1800, summary.length()))
                                            + " (partial)");
            if (value.length() > available) break;
            lines.add(value);
            available -= value.length();
        }
        java.util.Collections.reverse(lines);
        return String.join("\n", lines);
    }

    private record Target(String id, String kind, JSONObject spec) {}

    private static JSONObject merge(JSONObject original, JSONObject changes) {
        JSONObject out = BoardWidget.copy(original);
        for (var it = changes.keys(); it.hasNext(); ) {
            String key = it.next();
            Object value = changes.opt(key);
            if (value == JSONObject.NULL) out.remove(key);
            else if (value instanceof JSONObject nested && out.optJSONObject(key) != null)
                put(out, key, merge(out.optJSONObject(key), nested));
            else put(out, key, value);
        }
        return out;
    }

    private static void validate(String kind, JSONObject spec) {
        if (BoardWidget.GRAPH.equals(kind)) {
            JSONArray curves = spec.optJSONArray("functions");
            for (int j = 0; curves != null && j < curves.length(); j++) {
                JSONObject curve = curves.optJSONObject(j);
                BoardPlot.function(
                        curve == null ? curves.optString(j) : curve.optString("expr"),
                        BoardWidget.values(spec));
            }
            if ((curves == null || curves.length() == 0) && !BoardPlot.hasPoints(spec))
                throw new IllegalArgumentException("함수 그래프에 식이나 좌표가 필요해요.");
        }
        if (BoardWidget.make(kind, spec, 0, .85f, Board.AI, Board.ROUGH) == null)
            throw new IllegalArgumentException(BoardWidget.name(kind) + "의 입력을 확인해 주세요.");
    }

    public static BoardScript.Lesson prepare(BoardScript.Lesson lesson, JSONArray board) {
        Map<String, Target> targets = new LinkedHashMap<>();
        for (int i = 0; i < board.length(); i++) {
            JSONObject item = board.optJSONObject(i);
            if (item != null && Board.WIDGET.equals(item.optString("t"))) {
                String id = item.optString("wid");
                targets.put(id, new Target(id, item.optString("kind"), BoardWidget.spec(item)));
            }
        }
        List<BoardScript.Step> steps = new ArrayList<>();
        String nearby = "";
        for (var step : lesson.steps()) {
            List<JSONObject> ops = new ArrayList<>();
            boolean added = false;
            for (var raw : step.draw()) {
                JSONObject op = BoardWidget.copy(raw);
                op.remove("_wid");
                op.remove("_target");
                op.remove("_spec");
                String name = kind(op.optString("op").strip().toLowerCase(Locale.ROOT));
                put(op, "op", name);
                if (name.equals("update")) {
                    if (added) throw new IllegalArgumentException("자료 수정은 단계의 처음에 넣어 주세요.");
                    Target target = targets.get(op.optString("target"));
                    JSONObject patch = op.optJSONObject("set");
                    if (target == null || patch == null || patch.length() == 0)
                        throw new IllegalArgumentException("수정할 자료의 ID 또는 변경값이 없어요.");
                    for (var it = patch.keys(); it.hasNext(); )
                        if (!BoardWidget.accepts(target.kind, it.next()))
                            throw new IllegalArgumentException("자료에서 지원하지 않는 변경 항목이에요.");
                    JSONObject spec = merge(target.spec, patch);
                    validate(target.kind, spec);
                    Target replacement = new Target(target.id, target.kind, spec);
                    targets.replaceAll((alias, t) -> t.id.equals(target.id) ? replacement : t);
                    put(op, "_target", target.id);
                    put(op, "_spec", spec);
                } else if (BoardWidget.supports(name)) {
                    added = true;
                    if (name.equals("graph")) {
                        JSONObject finite = BoardField.recognize(op.toString());
                        if (finite == null
                                && (op.optJSONArray("functions") == null
                                        || op.optJSONArray("functions").length() == 0))
                            finite = BoardField.recognize(step.say() + " " + nearby);
                        if (finite != null) {
                            name = BoardWidget.FIELD;
                            put(op, "op", name);
                            for (String field : List.of("a", "b", "p"))
                                put(op, field, finite.opt(field));
                        }
                    }
                    validate(name, op);
                    String id = UUID.randomUUID().toString().substring(0, 13);
                    JSONObject widget = BoardWidget.stored(name, op, 0, 1, 1, Board.AI);
                    Target t = new Target(id, name, BoardWidget.spec(widget));
                    targets.put(id, t);
                    String alias = op.optString("as");
                    if (!alias.isBlank()) {
                        if (!alias.matches("[a-zA-Z0-9_-]{1,40}")
                                || targets.putIfAbsent(alias, t) != null)
                            throw new IllegalArgumentException("그림의 참조 이름이 잘못되었거나 중복돼요.");
                    }
                    put(op, "_wid", id);
                } else if (!BASIC.contains(name))
                    throw new IllegalArgumentException("지원하지 않는 판서 도구 " + name);
                else {
                    added = true;
                    nearby =
                            op.optString("text")
                                    + " "
                                    + nearby.substring(0, Math.min(600, nearby.length()));
                }
                ops.add(op);
            }
            steps.add(new BoardScript.Step(step.say(), ops));
        }
        return new BoardScript.Lesson(lesson.title(), steps);
    }

    public static BoardScript.Lesson checked(String answer, JSONArray board, String request) {
        BoardScript.Lesson parsed = BoardScript.parse(answer);
        String user = request.split("<board_so_far>|교과 설정:|<editable_widgets>", 2)[0];
        boolean graphRequested =
                java.util.regex.Pattern.compile(
                                "(그래프|산점도|점도표|plot|graph).{0,18}(그[릴려리]|표시|보여|추가|그림|그려|그리|안"
                                    + " 나|없어|빠졌)|(?:draw|plot|graph)\\s",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(user)
                        .find();
        boolean omitted = user.matches("(?s).*(그리지|빼고|제외|없이|말고|필요 없).*");
        boolean plot = false;
        JSONObject example = BoardField.recognize(user);
        for (var step : parsed.steps()) {
            if (example == null) example = BoardField.recognize(step.say());
            for (JSONObject op : step.draw()) {
                String k = kind(op.optString("op"));
                plot |= Set.of("graph", "relation", "finitefield", "chart", "update").contains(k);
                if (example == null) example = BoardField.recognize(op.optString("text"));
            }
        }
        if (!omitted && !plot && (graphRequested || example != null)) {
            if (example == null)
                throw new IllegalArgumentException("요청한 그래프를 실제 draw 명령으로 추가해 주세요.");
            put(example, "op", BoardWidget.FIELD);
            var steps = new ArrayList<>(parsed.steps());
            steps.add(
                    Math.min(1, steps.size()),
                    new BoardScript.Step("작은 유한체에서 식을 만족하는 좌표를 살펴봅시다.", List.of(example)));
            if (steps.size() > BoardScript.STEPS)
                throw new IllegalArgumentException("그래프를 포함해 단계 수를 줄여 주세요.");
            parsed = new BoardScript.Lesson(parsed.title(), steps);
        }
        return prepare(parsed, board);
    }

    public static JSONArray update(JSONArray board, JSONObject operation, Board.Measure measure) {
        for (int i = 0; i < board.length(); i++) {
            JSONObject item = board.optJSONObject(i);
            if (item == null
                    || !Board.WIDGET.equals(item.optString("t"))
                    || !item.optString("wid").equals(operation.optString("_target"))) continue;
            JSONObject made =
                    BoardWidget.make(
                            item.optString("kind"),
                            operation.optJSONObject("_spec"),
                            (float) item.optDouble("y"),
                            (float) item.optDouble("scale"),
                            item.optString("by"),
                            measure);
            return Board.editedWidget(board, item, made, measure);
        }
        throw new IllegalArgumentException("수정할 판서 자료를 찾지 못했어요.");
    }
}
