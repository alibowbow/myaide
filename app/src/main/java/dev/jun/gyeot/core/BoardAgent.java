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
import java.util.regex.Pattern;

/** Resolves model-facing names before playback. Only existing widget fields can be changed. */
public final class BoardAgent {
    private BoardAgent() {}

    public static final String GUIDE =
            """

Plan the lesson visually. Choose tools yourself from the question, even without an explicit tool name.
Pair complementary views when they teach distinct things. Use a map + timeline for a journey and chronology,
diagram + graph for forces, chemistry + table for conservation, reading for source evidence and grammar.
Render the requested visual in draw. A verbal promise or a sequence of boxes does not plot a graph.
Cartesian functions use equal-length x and y units. For independently scaled data, e.g. time and temperature, set equalUnits to false.
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
        boolean graphRequested = REQUEST_PLOT.matcher(user).find();
        boolean omitted = SKIP_PLOT.matcher(user).find();
        JSONObject example = BoardField.recognize(user);
        StringBuilder explanation = new StringBuilder();
        for (var step : parsed.steps()) {
            explanation.append(step.say()).append('\n');
            for (JSONObject op : step.draw()) explanation.append(op.optString("text")).append('\n');
        }
        if (example == null) example = BoardField.recognize(explanation.toString());
        boolean namedCurve = BITCOIN_CURVE.matcher(user).find() && !REAL_ONLY.matcher(user).find();
        BoardScript.Lesson prepared = prepare(parsed, board);
        if (omitted || (!namedCurve && !graphRequested && example == null)) return prepared;

        Map<String, String> kinds = new LinkedHashMap<>();
        for (int i = 0; i < board.length(); i++) {
            JSONObject item = board.optJSONObject(i);
            if (item != null) kinds.put(item.optString("wid"), item.optString("kind"));
        }
        boolean plotted = false, field = false;
        for (var step : prepared.steps()) {
            for (JSONObject op : step.draw()) {
                String kind = op.optString("op");
                if (kind.equals("update")) kind = kinds.getOrDefault(op.optString("_target"), "");
                else if (op.has("_wid")) kinds.put(op.optString("_wid"), kind);
                field |= kind.equals(BoardWidget.FIELD);
                plotted |= Set.of("graph", "relation", "finitefield", "chart").contains(kind);
            }
        }
        if (field || (plotted && example == null && !namedCurve)) return prepared;

        String caption = "작은 유한체에서 식을 만족하는 좌표를 살펴봅시다.";
        if (example == null && namedCurve) {
            var requested = NUMERIC_MODULUS.matcher(user);
            if (requested.find()) {
                example = bitcoinExample(requested.group(1));
                if (example == null)
                    throw new IllegalArgumentException(
                            "지정된 소수의 범위를 설명하고 작은 연습용 유한체를 명확히 구분해 그려 주세요.");
            } else {
                var mentioned = NUMERIC_MODULUS.matcher(explanation);
                while (example == null && mentioned.find())
                    example = bitcoinExample(mentioned.group(1));
                if (example == null) example = bitcoinExample("17");
            }
            if (example.optInt("p") == 17)
                put(
                        example,
                        "points",
                        arr(
                                obj("at", arr(1, 5), "label", "P (1, 5)"),
                                obj("at", arr(1, 12), "label", "−P (1, 12)"),
                                obj("at", arr(2, 10), "label", "2P (2, 10)")));
        }
        if (example == null) throw new IllegalArgumentException("요청한 그래프를 실제 draw 명령으로 추가해 주세요.");
        if (namedCurve)
            caption =
                    "연습용 유한체 (p = "
                            + example.optInt("p")
                            + ")에서 얻은 점입니다. 실제 secp256k1은 훨씬 큰 소수를 사용합니다.";
        BoardField.labelMentionedPoints(example, user + "\n" + explanation);
        put(example, "op", BoardWidget.FIELD);
        var fallback =
                new BoardScript.Step(
                        caption, List.of(obj("op", "write", "text", caption), example));
        var steps = new ArrayList<>(prepared.steps());
        if (steps.size() >= BoardScript.STEPS)
            throw new IllegalArgumentException("그래프를 포함해 단계 수를 줄여 주세요.");
        steps.add(
                Math.min(1, steps.size()),
                prepare(new BoardScript.Lesson("", List.of(fallback)), board).steps().get(0));
        return new BoardScript.Lesson(prepared.title(), steps);
    }

    private static JSONObject bitcoinExample(String prime) {
        try {
            JSONObject spec = obj("p", Integer.parseInt(prime), "a", 0, "b", 7);
            BoardField.validate(spec);
            return spec;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static final Pattern REQUEST_PLOT =
            Pattern.compile(
                    "(?i)(?:그래프|산점도|점도표).{0,18}(?:그려|그리|그릴|표시|보여|추가|안"
                        + " 나|없어|빠졌)|\\b(?:plot|graph)\\s+(?:y\\b|x\\b|the function|the curve|the"
                        + " points)|"
                        + "\\b(?:draw|show|add|make|plot|create)\\s+(?:(?:me|a|an|the|this|line|scatter|function)\\s+)*(?:graph|plot|chart)\\b");
    private static final Pattern BITCOIN_CURVE =
            Pattern.compile(
                    "(?i)secp256k1|(?:비트코인|bitcoin|\\bBTC\\b).{0,36}(?:타원\\s*곡선|elliptic\\s+curve)|"
                            + "(?:타원\\s*곡선|elliptic\\s+curve).{0,36}(?:비트코인|bitcoin|\\bBTC\\b)");
    private static final Pattern SKIP_PLOT =
            Pattern.compile(
                    "(?i)(?:그래프|그림|시각화|도표|산점도)(?:를|을|는|은)?\\s*(?:그리지|빼|제외|없이|말고|생략|필요\\s*없)|(?:글|말|텍스트)(?:로)?만|(?:no|without)"
                        + " (?:a |any )?(?:graph|plot|diagram)|text[- ]only");
    private static final Pattern REAL_ONLY =
            Pattern.compile(
                    "(?i)실수\\s*(?:곡선|그래프|에서|위|평면)?(?:로|에서|에서의)?\\s*만|real.{0,20}only|"
                            + "(?:예제|예시|예시값|예시용|예제용|장난감|연습용)(?:를|는|은)?\\s*(?:없이|빼|제외|말고)|"
                            + "(?:실제|원래).{0,14}(?:값|소수|파라미터|곡선)만|no toy|without (?:a )?toy");
    private static final Pattern NUMERIC_MODULUS =
            Pattern.compile(
                    "(?i)(?:\\bp\\s*=\\s*|\\bmod\\s*)([0-9]+)(?![0-9]|\\s*(?:[\\^×*⁰¹²³⁴⁵⁶⁷⁸⁹]|\\*\\*))");

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
