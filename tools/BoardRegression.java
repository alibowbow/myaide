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
        schoolTools();
        agentTools();
        graph();
        tableAndFigure();
        roundTrip();
        pagesAndGeometry();
        System.out.println("Board regression checks passed: " + checked);
    }

    private static void schoolTools() throws Exception {
        var mod=obj("p",17,"a",0,"b",7,"points",arr(obj("at",arr(1,5),"label","G"),obj("at",arr(1,12),"label","−G"),obj("at",arr(2,10),"label","2G")));
        JSONArray dots=BoardField.points(mod);
        check(dots.length()==17,"affine point count over F17");
        var coords=new java.util.HashSet<String>();
        for(int i=0;i<dots.length();i++)coords.add(dots.getJSONObject(i).getJSONArray("at").toString());
        check(coords.containsAll(List.of("[1,5]","[1,12]","[2,10]")),"user's exact elliptic curve points");
        var plotted=BoardField.draw(mod,.85f,Board.ROUGH).items();
        check(plotted.stream().filter(p->p.optJSONArray("p")!=null && p.optJSONArray("p").length()==2).count()==17,"all affine points drawn");
        check(plotted.stream().anyMatch(p->"2G".equals(p.optString("text"))),"selected point labelled");
        check(BoardField.recognize("연습용: p = 17, y² ≡ x³ + 7 (mod 17)").optInt("p")==17,"legacy equation detected");
        check(BoardField.recognize("y² ≡ x³ + 7 (mod p)")==null,"no invented small prime");
        for(int bad:List.of(1,9,101))reject(()->BoardField.points(obj("p",bad)),"invalid modulus");
        var pointsOnly=BoardPlot.graph(obj("points",arr(arr(37,112),obj("at",arr(80,130)))),0,480,.85f);
        check(pointsOnly.stream().filter(i->i.optJSONArray("p")!=null && i.optJSONArray("p").length()==2).count()==2,"scatter works without functions or explicit bounds");
        for(JSONObject input:List.of(obj("mode","implicit","expr","y^2=x^3+7"),obj("mode","parametric","xt","cos(t)","yt","sin(t)"),obj("mode","polar","expr","2*cos(3*t)"))) {
            var relation=BoardWidget.make(BoardWidget.RELATION,input,0,.85f,Board.ME,Board.ROUGH);
            check(BoardWidget.render(relation,Board.ROUGH).stream().anyMatch(i->"blue".equals(i.optString("c"))),"non-cartesian geometry is drawn");
        }
        reject(()->BoardWidget.make("relation",obj("mode","implicit","expr","1/(x-0.023)=0"),0,1,Board.ME,Board.ROUGH),"a sign change at a pole is not an implicit zero");
        var legacy=BoardWidget.stored("graph",obj(),120,.85f,58,Board.AI);
        JSONObject clue=Board.inLesson(Board.text(48,40,"연습용: y² ≡ x³ + 7 (mod 17)",30,"ink",false,900,Board.AI),"lesson",1);
        Board.inLesson(legacy,"lesson",1);
        var restored=BoardField.restore(arr(clue,legacy,Board.text(48,200,"다음 줄",30,"ink",false,900,Board.AI)),Board.ROUGH);
        check(restored.getJSONObject(1).getString("kind").equals("finitefield") && restored.getJSONObject(2).getDouble("y")>600,"a broken kept graph is restored and following writing moves");
        check(BoardField.restore(restored,Board.ROUGH)==restored,"migration is idempotent");

        var eq=BoardChemistry.draw(obj("formula","Ca(OH)2 + 2HCl → CaCl2 + 2H2O"),.85f,Board.ROUGH);
        check(eq.items().stream().anyMatch(i->"양쪽 원자 수가 같아요".equals(i.optString("text"))),"groups and coefficients conserve atoms");
        check(BoardChemistry.draw(obj("formula","H2 + O2 → H2O"),1,Board.ROUGH).items().stream().anyMatch(i->"양쪽 원자 수가 달라요".equals(i.optString("text"))),"unbalanced equation reported");
        reject(()->BoardChemistry.draw(obj("formula","Mg(OH"),1,Board.ROUGH),"invalid chemistry syntax");
        var map=BoardWidget.make("map",obj("region","korea","places",arr(obj("name","서울","lon",126.98,"lat",37.57),obj("name","경주","lon",129.22,"lat",35.86)),"route",true),0,.85f,Board.AI,Board.ROUGH);
        var land=BoardWidget.render(map,Board.ROUGH);
        check(land.stream().filter(i->"green".equals(i.optString("c"))).count()>10,"actual local coastline");
        check(land.stream().anyMatch(i->i.optBoolean("arrow")),"map route drawn");
        reject(()->BoardMap.draw(obj("region","korea","places",arr(obj("lon",1,"lat",1))),1,Board.ROUGH),"invalid map extent");
        for(String preset:List.of("plantCell","animalCell","neuron","earthLayers")){
            check(BoardIllustration.parts(obj("preset",preset)).length()>=3,"preset has meaningful editable layers");
        }
        var layers=arr(obj("id","force","at",arr(20,30),"label","힘","shapes",arr(obj("kind","path","points",arr(arr(80,30),arr(300,160))))));
        var layered=BoardWidget.make("diagram",obj("layers",layers,"focus","force"),30,1,Board.AI,Board.ROUGH);
        check(BoardWidget.render(Board.clean(arr(layered)).getJSONObject(0),Board.ROUGH).stream().anyMatch(i->i.optJSONArray("p")!=null && i.optJSONArray("p").optInt(2)==300),"custom illustration survives nested point arrays");
        for(String kind:List.of("tetrahedron","pyramid","prism","cylinder","cone","sphere")){
            var solid=BoardWidget.make("solid",obj("kind",kind,"yaw",35,"pitch",22),0,1,Board.ME,Board.ROUGH);
            check(BoardWidget.render(solid,Board.ROUGH).stream().anyMatch(i->BoardSolid.name(kind).equals(i.optString("text"))),"correct solid drawn: "+kind);
        }
        var mesh=obj("kind","mesh","vertices",arr(arr(0,0,0),arr(1,0,0),arr(0,1,0),arr(0,0,1)),"faces",arr(arr(0,1,2),arr(0,2,3),arr(0,1,3),arr(1,2,3)));
        check(BoardSolid.draw(mesh,1).items().size()==5,"agent-defined tetrahedral mesh has four faces");
        var reading=obj("lines",arr(obj("text","봄이 왔다.","note","계절의 변화")),"reveal",false);
        check(BoardIllustration.reading(reading,1,Board.ROUGH).items().stream().noneMatch(i->"계절의 변화".equals(i.optString("text"))),"reading notes can be hidden");
        put(reading,"reveal",true);check(BoardIllustration.reading(reading,1,Board.ROUGH).items().stream().anyMatch(i->"계절의 변화".equals(i.optString("text"))),"reading notes revealed");
    }
    private static void reject(Runnable action,String why) {
        try{action.run();}catch(IllegalArgumentException expected){check(true,why);return;}
        throw new AssertionError(why);
    }
    private static void agentTools() throws Exception {
        JSONObject graph=obj("op","graph","as","slope","functions",arr("a*x"),"parameters",obj("a",obj("value",1,"min",-3,"max",3)));
        JSONObject update=obj("op","update","target","slope","set",obj("parameters",obj("a",obj("value",2))));
        var create=new BoardScript.Step("그래프",List.of(graph));
        var change=new BoardScript.Step("기울기를 바꿔요",List.of(update));
        var plan=BoardAgent.prepare(new BoardScript.Lesson("직선",List.of(create,change)),arr());
        var flow=new BoardScript.Flow(arr(),Board.ROUGH);
        JSONObject placed=flow.place(plan.steps().get(0)).get(0);
        var stored=Board.clean(new JSONArray(arr(placed).toString()));String id=placed.getString("wid");
        check(BoardAgent.inventory(stored).contains(id),"agent can identify stored visual");
        var result=BoardAgent.update(stored,plan.steps().get(1).draw().get(0),Board.ROUGH);
        check(result.length()==1 && id.equals(result.getJSONObject(0).getString("wid")),"edit replaces original and preserves id");
        var p=BoardWidget.parameters(BoardWidget.spec(result.getJSONObject(0))).get(0);
        check(p.value()==2 && p.min()==-3 && p.max()==3,"nested patch preserves other parameters");
        check(BoardWidget.parameters(BoardWidget.spec(stored.getJSONObject(0))).get(0).value()==1,"undo retains old data");
        var invalid=obj("op","update","target","unknown","set",obj("a",2));
        reject(()->BoardAgent.prepare(new BoardScript.Lesson("",List.of(new BoardScript.Step("",List.of(invalid)))),stored),"bad reference diagnosed");
        reject(()->BoardAgent.checked("{\"steps\":[{\"say\":\"함수 설명\"}]}",arr(),"그래프를 그려줘"),"missing requested graph diagnosed");
        var repair=BoardAgent.checked("{\"steps\":[{\"draw\":[{\"op\":\"write\",\"text\":\"연습: y² ≡ x³ + 7 (mod 17)\"}]}]}",arr(),"비트코인 타원곡선 설명");
        check(repair.steps().stream().anyMatch(step->step.draw().stream().anyMatch(o->"finitefield".equals(o.optString("op")))),"toy field lesson gets a real plot even if the model omits it");
        String textOnly = "{\"steps\":[{\"say\":\"비트코인의 타원곡선 원리를 봅니다.\"}]}";
        var automatic = BoardAgent.checked(textOnly, arr(), "비트코인 타원곡선을 설명해 줘");
        var teaching = automatic.steps().get(1);
        check(teaching.draw().get(1).optInt("p")==17 && teaching.draw().get(1).getJSONArray("points").length()==3,
                "a topic alone leads to a labelled finite-field plot");
        check(teaching.draw().get(0).getString("text").contains("연습용"), "toy field is identified on the board");
        var chosen = BoardAgent.checked(textOnly, arr(), "secp256k1을 p=19로 설명해 줘");
        check(chosen.steps().get(1).draw().get(1).optInt("p")==19, "explicit prime respected");
        check(BoardAgent.checked(textOnly, arr(), "비트코인의 타원곡선을 그림 없이 글로만 설명해 줘").steps().size()==1,
                "explicit text-only request kept");
        check(BoardAgent.checked(textOnly, arr(), "secp256k1 실제 소수만 알려줘").steps().size()==1,
                "an exact parameter request does not add a toy example");
        var symbolic = BoardAgent.checked("{\"steps\":[{\"say\":\"p = 2^256 - 2^32 - 977\"}]}", arr(), "secp256k1 설명");
        check(symbolic.steps().get(1).draw().get(1).optInt("p")==17, "large prime formula does not become modulus two");
        check(BoardAgent.checked(textOnly, arr(), "비트코인 가격의 추세를 설명해 줘").steps().size()==1,
                "unrelated Bitcoin topic has no cryptographic diagram");
        var mention = BoardAgent.checked("{\"steps\":[{\"say\":\"y² ≡ x³ + 7 (mod 17). (1,5), (1,12), (1,5), (0,1)\"}]}", arr(), "점 확인");
        check(mention.steps().get(1).draw().get(1).getJSONArray("points").length()==2,
                "valid finite-field coordinates in text are highlighted just once");
        var mapOnly = BoardAgent.checked("{\"steps\":[{\"draw\":[{\"op\":\"map\",\"region\":\"korea\"}]}]}",
                arr(), "Draw a map of Korea");
        check(mapOnly.steps().size()==1, "English map instruction does not require a graph");
        check(BoardAgent.checked("{\"steps\":[{\"draw\":[{\"op\":\"finitefield\",\"a\":0,\"b\":7,\"p\":19}]}]}",
                arr(), "비트코인 타원곡선 설명").steps().size()==1, "existing finite-field lesson is not duplicated");

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
