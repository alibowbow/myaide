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
        notebookInk();
        schoolTools();
        agentTools();
        connectedScenes();
        graph();
        cartesianScales();
        tableAndFigure();
        roundTrip();
        pagesAndGeometry();
        System.out.println("Board regression checks passed: " + checked);
    }

    private static void notebookInk() throws Exception {
        var flow = new BoardScript.Flow(arr(), Board.ROUGH);
        var notes = flow.place(new BoardScript.Step("", List.of(
                obj("op","heading","text","정보의 흐름"),
                obj("op","note","kind","tip","text","세 개씩 읽기"),
                obj("op","write","text","전사와 번역","underline","dotted"),
                obj("op","mark","text","번역","kind","double","color","rust"))));
        check(notes.stream().noneMatch(o -> Board.RECT.equals(o.optString("t"))), "notes, headings and key phrases stay open");
        check(notes.stream().anyMatch(o -> "bracket".equals(o.optString("mark"))) &&
              notes.stream().anyMatch(o -> "dotted".equals(o.optString("rule"))), "margin and inline annotations use their own geometry");
        var dual = notes.stream().filter(o -> "double".equals(o.optString("rule"))).findFirst().orElseThrow();
        float x = (float)dual.optDouble("x1"), y = (float)dual.optDouble("y1");
        float offset = BoardInk.doubleOffset(x,y,(float)dual.optDouble("x2"),y,(float)dual.optDouble("w"))[1];
        check(Board.bounds(dual,Board.ROUGH)[3] >= y+offset && Board.distance(dual,x+3,y+offset,Board.ROUGH) < 1,
                "the companion underline is measured and erasable");
        var block = flow.place(new BoardScript.Step("",List.of(obj("op","box","text","빈칸"))));
        check(block.stream().anyMatch(o -> Board.RECT.equals(o.optString("t"))), "an explicit box remains available");
        for (String kind : List.of("flow","tree","table")) {
            var spec = switch(kind) {
                case "flow" -> obj("steps",arr("DNA","RNA","단백질"),"direction","right");
                case "tree" -> obj("root","핵산","children",arr("DNA","RNA"));
                default -> obj("rows",arr(arr("구분","내용"),arr("전사","RNA")));
            };
            for(boolean borders : List.of(false,true)) {
                if(borders) put(spec,"style","table".equals(kind)?"grid":"boxed");
                var item=BoardWidget.make(kind,spec,24,.85f,Board.ME,Board.ROUGH);
                var restored=Board.clean(arr(item)).getJSONObject(0);
                check(BoardWidget.render(restored,Board.ROUGH).stream().anyMatch(o->Board.RECT.equals(o.optString("t")))==borders,
                        kind+" keeps the selected border style after saving");
            }
        }
        var row=obj("kind","row","at",arr(400,100),"size",arr(500,60),"tokens",arr("A","U","G","G","C","U"),
                "group",3,"active",2,"span",3,"reveal",3);
        var scene=obj("height",200,"objects",obj("bases",row));
        var children=BoardWidget.render(BoardWidget.make("scene",scene,0,1,Board.AI,Board.ROUGH),Board.ROUGH);
        check(children.stream().filter(o->o.optBoolean("hl")).count()==1 &&
                children.stream().noneMatch(o->Board.RECT.equals(o.optString("t"))),"one marker sweep covers revealed part of an active group");
        put(row,"style","tiles");
        children=BoardWidget.render(BoardWidget.make("scene",scene,0,1,Board.AI,Board.ROUGH),Board.ROUGH);
        check(children.stream().filter(o->Board.RECT.equals(o.optString("t"))).count()==3,"tile exercises keep cells and respect reveal");
        var paths=flow.place(new BoardScript.Step("",List.of(obj("op","figure","shapes",arr(
                obj("kind","path","nib","brush","color","indigo","width",9,"points",arr(arr(20,60),arr(60,30),arr(80,35))),
                obj("kind","path","nib","marker","color","ochre","points",arr(arr(20,80),arr(100,80))))))));
        var brush=Board.clean(arr(paths.get(0),paths.get(1))).getJSONObject(0);
        check("brush".equals(brush.optString("nib")) && Board.AI.equals(brush.optString("by")) && "indigo".equals(brush.optString("c")),
                "tutor can use the same nib and ink color");
        check(paths.get(1).optBoolean("hl"),"tutor's marker uses the underlay");
        var legacy=Board.stroke("blue",7,false,new float[]{10,10,30,20},Board.ME);
        var kept=Board.clean(arr(legacy)).getJSONObject(0);
        check(!kept.has("nib") && kept.getDouble("w")==7 && kept.getString("c").equals("blue"), "existing ink keeps its original width and color");
    }

    private static void cartesianScales() {
        for (float scale : new float[] {.5f, .85f}) {
            for (boolean steep : List.of(true, false)) {
                double x = steep ? 0 : -3;
                JSONObject spec = obj("height", 280, "x", steep ? arr(-1.2,1.6) : arr(-3.5,-.5),
                    "y", steep ? arr(-7,8) : arr(-1.5,1.5), "functions", arr(steep ? "x^3+3x-2" : "(x+1)(x+2)(x+3)"),
                    "points", arr(arr(x,0),arr(x+1,0),arr(x,1)));
                JSONObject graph = BoardWidget.make("graph",spec,15,scale,Board.AI,Board.ROUGH);
                var points = BoardWidget.render(graph,Board.ROUGH).stream().map(o->o.optJSONArray("p"))
                    .filter(p->p!=null && p.length()==2).toList();
                double dx=points.get(1).optDouble(0)-points.get(0).optDouble(0);
                double dy=points.get(0).optDouble(1)-points.get(2).optDouble(1);
                check(dx>1 && Math.abs(dx-dy)<.05,"same Cartesian units for both reported cubics");
                put(spec,"equalUnits",false);
                check(!BoardWidget.spec(BoardWidget.make("graph",spec,0,scale,Board.AI,Board.ROUGH))
                    .optBoolean("equalUnits",true),"independent scale survives storage");
                var old=BoardWidget.copy(graph);put(old,"h",220);var note=Board.text(50,260,"다음 단계",28,"ink",false,600,Board.AI);
                var moved=BoardWidget.reflowGraphs(arr(old,note),Board.ROUGH);
                check(moved.optJSONObject(1).optDouble("y")>moved.optJSONObject(0).optDouble("h")+15
                    && BoardWidget.reflowGraphs(moved,Board.ROUGH)==moved,"existing lesson reflows once without overlap");
            }
        }
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
    private static List<JSONObject> sceneInk(JSONObject spec) {
        JSONObject scene = BoardWidget.make(BoardWidget.SCENE, spec, 0, .85f, Board.AI, Board.ROUGH);
        return BoardWidget.render(scene, Board.ROUGH);
    }

    private static JSONObject textItem(List<JSONObject> items, String word) {
        return items.stream().filter(o -> word.equals(o.optString("text"))).findFirst().orElseThrow();
    }

    private static void connectedScenes() throws Exception {
        var create = obj("op", "scene", "as", "track", "height", 300, "objects", obj(
                "label", obj("kind", "text", "text", "표식", "attach", "row.2.top", "pin", "bottom", "size", arr(100, 48)),
                "row", obj("kind", "row", "at", arr(420, 180), "size", arr(450, 56), "tokens", arr("가","나","다"), "reveal", 1),
                "lower", obj("kind", "row", "attach", "row.bottom", "pin", "top", "offset", arr(0, 32),
                        "size", arr(450, 56), "tokens", arr("1","2","3"))),
                "links", obj("pairs", obj("from", "row.bottom", "to", "lower.top", "pairwise", true, "dashed", true)));
        var update = obj("op", "update", "target", "track", "set", obj("objects",
                obj("row", obj("reveal", 3, "at", arr(500, 150)), "label", obj("attach", "row.3.top"))));
        var lesson = BoardAgent.checked(obj("steps",arr(obj("draw",arr(create)),obj("draw",arr(update)))).toString(), arr(), "기호 대응");
        var flow = new BoardScript.Flow(arr(), Board.ROUGH, .85f);
        var first = flow.place(lesson.steps().get(0)).get(0);
        var before = BoardWidget.render(first, Board.ROUGH);
        check(before.stream().noneMatch(o -> "표식".equals(o.optString("text"))), "unrevealed cells also hide docked labels");
        check(before.stream().filter(o -> o.optBoolean("d")).count() == 1, "only revealed corresponding cells connect");
        var saved = Board.clean(arr(first));
        var edited = BoardAgent.update(saved, lesson.steps().get(1).draw().get(0), Board.ROUGH).getJSONObject(0);
        check(edited.getString("wid").equals(first.getString("wid")), "a stage updates the same saved scene");
        var after = BoardWidget.render(edited, Board.ROUGH);
        check(after.stream().filter(o -> o.optBoolean("d")).count() == 3, "revealing the full row reveals all three connectors");
        for (JSONObject line : after) if (line.optBoolean("d"))
            check(line.getDouble("x1") == line.getDouble("x2") && line.getDouble("y2") - line.getDouble("y1") == 32, "paired row endpoints follow both edges");
        check(Math.abs(textItem(after,"가").getDouble("x") - textItem(before,"가").getDouble("x") - 80) < .2,
                "row moves without redistributing revealed cells");
        float[] cell = Board.bounds(textItem(after,"다"), Board.ROUGH), label = Board.bounds(textItem(after,"표식"), Board.ROUGH);
        check(Math.abs(cell[0]+cell[2]-label[0]-label[2]) < .4 && label[3] < cell[1], "label is docked to the third cell");
        JSONObject record = obj();
        BoardReplay.remember(record, first, 0);
        BoardReplay.remember(record, edited, 1);
        JSONArray frames = BoardReplay.clean(record, 2);
        check(sceneInk(frames.getJSONObject(0).getJSONObject("spec")).stream().filter(o->o.optBoolean("d")).count()==1
                && sceneInk(frames.getJSONObject(1).getJSONObject("spec")).stream().filter(o->o.optBoolean("d")).count()==3,
                "replay restores two distinct poses after saving");

        var spec = BoardWidget.spec(edited);
        check("row".equals(BoardScene.hit(spec, .85f, Board.ROUGH, (cell[0]+cell[2])/2, (cell[1]+cell[3])/2)), "editor picks the visible token row");
        check(BoardScene.move(spec, "row", 30, 12, .85f, Board.ROUGH), "editor moves a connected group");
        check(Math.abs(textItem(sceneInk(spec),"표식").getDouble("x") - textItem(after,"표식").getDouble("x") - 30)<.2, "child follows editor drag");
        String previous = spec.toString();
        check(!BoardScene.move(spec, "row", 1000, 0, .85f, Board.ROUGH) && previous.equals(spec.toString()), "invalid drag is atomic");
        for (String bad : List.of("missing.top", "row.4.top", "row.0.top", "row.corner", "label.center")) {
            JSONObject badSpec = BoardWidget.copy(spec);
            put(badSpec.getJSONObject("objects").getJSONObject("label"), "attach", bad);
            reject(() -> sceneInk(badSpec), "invalid or cyclic attachment rejected: " + bad);
        }
        JSONObject badPair = BoardWidget.copy(spec);
        put(badPair.getJSONObject("objects").getJSONObject("lower"), "tokens", arr("1", "2"));
        reject(() -> sceneInk(badPair), "unequal row lengths rejected for pairwise links");

        for (String preset : List.of("proteinSynthesis", "force")) {
            var stages = BoardScene.stages(preset);
            for (int i=1; i<=stages.size(); i++) {
                JSONObject input = obj("preset", preset, "stage", i);
                var items = sceneInk(input);
                check(!items.isEmpty() && items.get(0).optString("text").endsWith(stages.get(i-1)), "preset has visible native stage heading");
                if (preset.equals("force")) {
                    var arrow = items.stream().filter(o -> o.optBoolean("arrow")).findFirst().orElseThrow();
                    check(arrow.optDouble("x1") == (i==1 ? 393 : 573) && arrow.optDouble("x2")-arrow.optDouble("x1")==205, "force arrow follows moving block");
                } else if (i<=2) {
                    check(items.stream().filter(o->o.optBoolean("d")).count()==(i==1?1:9), "DNA pairs appear with actual mRNA bases");
                } else {
                    boolean carrier = items.stream().anyMatch(o -> "운반 RNA".equals(o.optString("text")));
                    check(carrier == (i < 5), "no tRNA or amino acid gets assigned to the stop codon");
                    check(items.stream().filter(o -> "green".equals(o.optString("c")) && o.optBoolean("b")).count()==3, "one triplet is highlighted");
                }
            }
        }
        reject(() -> sceneInk(obj("preset","proteinSynthesis","stage",6)), "unknown preset stage rejected");
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
