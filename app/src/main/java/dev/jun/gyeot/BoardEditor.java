package dev.jun.gyeot;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import dev.jun.gyeot.core.Board;
import dev.jun.gyeot.core.BoardPlot;
import dev.jun.gyeot.core.BoardWidget;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.Ui.*;
import static dev.jun.gyeot.core.BoardWidget.*;
import static dev.jun.gyeot.core.Jsons.*;

/** The editors used by tools the user inserts and by the same tools when the tutor inserts them. */
final class BoardEditor {
    interface InkReader { void read(Consumer<String> done); }
    private final Activity activity;
    private final Ui ui;
    private final BoardView board;

    BoardEditor(Activity activity, Ui ui, BoardView board) {
        this.activity = activity; this.ui = ui; this.board = board;
    }

    Ui.Sheet open(String kind, JSONObject original, Consumer<JSONObject> apply, InkReader inkReader) {
        return switch (kind) {
            case GRAPH -> new GraphEdit(original, apply, inkReader).sheet;
            case RELATION, FIELD, MAP, CHEM, DIAGRAM, READING -> subjectTool(kind,original,apply);
            case GEOMETRY, SOLID -> figure(kind, original, apply);
            default -> data(kind, original, apply);
        };
    }

    private final class GraphEdit {
        final Ui.Sheet sheet;
        final JSONObject original;
        final Preview preview;
        final EditText expressions, x1, x2, y1, y2;
        final LinearLayout sliders;
        final TextView status;
        final Consumer<JSONObject> apply;
        JSONObject values;
        boolean filling;
        final Runnable refresh = () -> update(true);
        final JSONObject pointSpec; final List<Runnable> pointEdits=new java.util.ArrayList<>();

        GraphEdit(JSONObject original, Consumer<JSONObject> apply, InkReader inkReader) {
            this.original = original; this.apply = apply; pointSpec=BoardWidget.copy(original);
            values = BoardWidget.copy(original.optJSONObject("parameters"));
            sheet = ui.selectionSheet("함수 그래프", "함수식을 한 줄에 하나씩 입력하세요. a, h, k 같은 글자에 조절 막대가 생겨요.");
            preview = new Preview(GRAPH); sheet.controls.addView(preview, new LinearLayout.LayoutParams(-1, ui.dp(184)));
            sheet.panel.setOnApplyWindowInsetsListener((view, insets) -> {
                preview.setVisibility(insets.isVisible(WindowInsets.Type.ime()) ? View.GONE : View.VISIBLE);
                return insets;
            });
            status = notice(sheet);
            if (inkReader != null) {
                TextView waiting = ui.text("", FOOTNOTE, 500, MUTED);
                waiting.setVisibility(View.GONE);
                var read = ui.button("판의 손글씨에서 식 읽기", false, () -> {});
                read.setOnClickListener(v -> {
                    read.setEnabled(false); waiting.setVisibility(View.VISIBLE); waiting.setText("판 아래쪽에 쓴 식을 읽는 중…");
                    inkReader.read(result -> {
                        if (!sheet.isShowing()) return;
                        read.setEnabled(true);
                        if (result == null || result.isBlank()) { waiting.setText("식을 읽지 못했어요. 크게 다시 쓰거나 직접 입력해 주세요."); return; }
                        expressions().setText(result); waiting.setText("읽은 식을 확인하고 필요하면 고쳐 주세요.");
                    });
                });
                sheet.body.addView(read); sheet.body.addView(waiting);
            }
            JSONArray functions = original.optJSONArray("functions");
            StringBuilder lines = new StringBuilder();
            for (int i = 0; functions != null && i < functions.length(); i++) {
                if (i > 0) lines.append('\n');
                JSONObject item = functions.optJSONObject(i);
                lines.append(item == null ? functions.optString(i) : item.optString("expr"));
            }
            expressions = field(sheet.body, "함수식 · 한 줄에 하나", lines.toString(), 3, BoardPlot.FUNCTIONS * 201);
            if (functions == null || functions.length() == 0) {
                sheet.body.addView(ui.button("이차함수 예시 넣기", false, this::example));
            }
            EditText coordinates=editPoints(sheet,pointSpec,pointEdits,"표시할 점 · x | y | 이름");
            LinearLayout rangeX = ui.row(), rangeY = ui.row(); sheet.body.addView(rangeX); sheet.body.addView(rangeY);
            x1 = axis(rangeX, "x 최소", original.optJSONArray("x"), 0, "");
            x2 = axis(rangeX, "x 최대", original.optJSONArray("x"), 1, "");
            y1 = axis(rangeY, "y 최소 · 자동", original.optJSONArray("y"), 0, "");
            y2 = axis(rangeY, "y 최대 · 자동", original.optJSONArray("y"), 1, "");
            CheckBox equal = new CheckBox(activity);
            equal.setText("X축과 Y축의 1단위를 같은 길이로 표시");
            equal.setChecked(original.optBoolean("equalUnits", true));
            sheet.body.addView(equal);
            sliders = ui.column(); sheet.body.addView(sliders);
            equal.setOnCheckedChangeListener((button, checked) -> {
                put(pointSpec, "equalUnits", checked);
                update(false);
            });
            for (EditText input : List.of(expressions,coordinates,x1,x2,y1,y2)) input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (!filling) { preview.removeCallbacks(refresh); preview.postDelayed(refresh, 150); }
                }
                @Override public void afterTextChanged(Editable change) {}
            });
            sheet.primary.setText("그래프 적용");
            sheet.primary.setOnClickListener(v -> {
                preview.removeCallbacks(refresh);
                JSONObject made = update(true);
                if (made != null) { apply.accept(made); sheet.dismiss(); }
            });
            update(true);
        }

        private EditText expressions() { return expressions; }

        private void example() {
            filling = true;
            expressions.setText("a(x-h)^2+k");
            x1.setText("−5"); x2.setText("5"); y1.setText("−10"); y2.setText("10");
            values = obj("a", obj("value", 1, "min", -3, "max", 3), "h", obj("value", 0, "min", -4, "max", 4),
                "k", obj("value", 0, "min", -5, "max", 5));
            filling = false; update(true);
        }

        private JSONObject update(boolean rebuild) {
            String[] lines = expressions.getText().toString().split("\\R");
            JSONArray curves = arr();
            Set<String> names = new LinkedHashSet<>();
            JSONArray old = original.optJSONArray("functions");
            try {
                for (String line : lines) {
                    String expr = line.strip();
                    if (expr.isEmpty()) continue;
                    if (curves.length() >= BoardPlot.FUNCTIONS) throw new IllegalArgumentException("함수식은 다섯 개까지 넣을 수 있어요.");
                    names.addAll(BoardPlot.parameters(expr));
                    JSONObject curve = BoardWidget.copy(old == null ? null : old.optJSONObject(curves.length()));
                    if (!expr.equals(curve.optString("expr"))) put(curve, "label", "y = " + expr.replaceFirst("(?i)^\\s*(y|[a-z]\\s*\\(\\s*x\\s*\\))\\s*=\\s*", ""));
                    put(curve, "expr", expr); curves.put(curve);
                }
                for(Runnable read:pointEdits)read.run();
                if(curves.length()==0 && (pointSpec.optJSONArray("points")==null || pointSpec.optJSONArray("points").length()==0))throw new IllegalArgumentException("함수식 또는 좌표를 입력하세요.");
                if (names.size() > 12) throw new IllegalArgumentException("조절 변수는 열두 개까지 쓸 수 있어요.");
                JSONObject controls = obj();
                for (String name : names) {
                    Object value = values.opt(name);
                    put(controls, name, value == null ? obj("value", "a".equals(name) ? 1 : 0) : value);
                }
                JSONObject made = BoardWidget.copy(original);
                put(made, "equalUnits", pointSpec.optBoolean("equalUnits", true));
                if(pointSpec.has("points"))put(made,"points",pointSpec.optJSONArray("points"));
                put(made, "functions", curves); put(made, "parameters", controls);
                JSONArray x=axisRange(x1,x2,"x",true);if(x==null)made.remove("x");else put(made,"x",x);
                JSONArray y = axisRange(y1, y2, "y", true);
                if (y == null) made.remove("y"); else put(made, "y", y);
                values = controls;
                status.setVisibility(View.GONE); sheet.primary.setEnabled(true);
                preview.update(made);
                if (rebuild) parameters(made);
                return made;
            } catch (IllegalArgumentException invalid) {
                status.setText(invalid.getMessage()); status.setVisibility(View.VISIBLE);
                if (rebuild) sliders.removeAllViews();
                // Keep the last valid plot while the expression is being typed.
                return null;
            }
        }

        private void parameters(JSONObject made) {
            sliders.removeAllViews();
            for (Parameter p : BoardWidget.parameters(made)) {
                sliders.addView(slider(p.name(), p.min(), p.max(), p.step(), p.value(), value -> {
                    put(values, p.name(), obj("value", value, "min", p.min(), "max", p.max(), "step", p.step()));
                    update(false);
                }));
            }
        }
    }

    private Ui.Sheet figure(String kind, JSONObject original, Consumer<JSONObject> apply) {
        boolean solid = SOLID.equals(kind);
        Ui.Sheet sheet = ui.selectionSheet(name(kind), solid
            ? "미리보기에서 끌어 회전하세요. 전개도도 펼칠 수 있어요."
            : "꼭짓점을 끌면 변의 길이와 각도, 넓이가 바뀌어요.");
        JSONObject spec = BoardWidget.copy(original);
        Preview preview = new Preview(kind);
        preview.spec = spec;
        sheet.body.addView(preview, new LinearLayout.LayoutParams(-1, ui.dp(256)));
        Runnable refresh = () -> preview.update(spec);
        if (solid) {
            if(spec.optString("kind").isBlank())put(spec,"kind","cube");
            CheckBox unfold=check("전개도 펼치기",spec.optBoolean("net"),net->{put(spec,"net",net);refresh.run();});
            var ids=new java.util.ArrayList<>(dev.jun.gyeot.core.BoardSolid.KINDS);
            if(!spec.has("vertices"))ids.remove("mesh");
            String[] names=ids.stream().map(dev.jun.gyeot.core.BoardSolid::name).toArray(String[]::new);
            sheet.body.addView(ui.spinner("입체도형 종류",names,Math.max(0,ids.indexOf(spec.optString("kind"))),i->{
                put(spec,"kind",ids.get(i));boolean supported=i<2;
                if(!supported)unfold.setChecked(false);
                unfold.setVisibility(supported?View.VISIBLE:View.GONE);refresh.run();
            }));
            unfold.setVisibility(ids.indexOf(spec.optString("kind"))<2?View.VISIBLE:View.GONE);
            sheet.body.addView(unfold);
        } else {
            boolean quad = BoardWidget.vertices(spec, false).length() == 4;
            sheet.body.addView(ui.pills(new String[]{"삼각형", "사각형"}, quad ? 1 : 0, index -> {
                spec.remove("vertices"); put(spec, "vertices", BoardWidget.vertices(spec, index == 1)); refresh.run();
            }));
            put(spec, "vertices", BoardWidget.vertices(spec, quad));
        }
        sheet.primary.setText("판에 적용");
        sheet.primary.setOnClickListener(v -> { apply.accept(spec); sheet.dismiss(); });
        refresh.run();
        return sheet;
    }

    private Ui.Sheet subjectTool(String kind, JSONObject original, Consumer<JSONObject> apply) {
        Ui.Sheet s=ui.selectionSheet(name(kind),null);
        JSONObject spec=BoardWidget.copy(original);
        Preview preview=new Preview(kind);s.body.addView(preview,new LinearLayout.LayoutParams(-1,ui.dp(210)));
        java.util.List<Runnable> read=new java.util.ArrayList<>();
        if(MAP.equals(kind) || DIAGRAM.equals(kind) || READING.equals(kind)){
            EditText title=field(s.body,"제목",spec.optString("title",name(kind)),1,160);
            read.add(()->put(spec,"title",title.getText().toString()));
        }
        if(RELATION.equals(kind)){
            List<String> modes=List.of("implicit","parametric","polar");
            s.body.addView(ui.spinner("좌표 표현",new String[]{"음함수 방정식","매개변수 곡선","극좌표 곡선"},Math.max(0,modes.indexOf(spec.optString("mode"))),i->put(spec,"mode",modes.get(i))));
            for(String key:List.of("expr","xt","yt")){
                String fallback=switch(key){case "xt"->"cos(t)";case "yt"->"sin(t)";default->"x^2+y^2=4";};
                EditText value=field(s.body,switch(key){case "xt"->"매개변수 x(t)";case "yt"->"매개변수 y(t)";default->"방정식 또는 반지름 r(t)";},spec.optString(key,fallback),1,200);
                read.add(()->put(spec,key,value.getText().toString().strip()));
            }
            for(String key:List.of("x","y","t")){
                LinearLayout range=ui.row();s.body.addView(range);boolean t=key.equals("t");
                EditText lo=axis(range,key+" 최소",spec.optJSONArray(key),0,t?"0":"-5"),hi=axis(range,key+" 최대",spec.optJSONArray(key),1,t?"6.283185307":"5");
                read.add(()->put(spec,key,axisRange(lo,hi,key,false)));
            }
        }else if(FIELD.equals(kind)){
            for(String key:List.of("p","a","b")){
                String label=key.equals("p")?"소수 p (5~97)":key+" 계수";
                EditText input=field(s.body,label,spec.optString(key,key.equals("p")?"17":key.equals("b")?"7":"0"),1,8);
                read.add(()->put(spec,key,decimal(input.getText().toString().strip())));
            }
            editPoints(s,spec,read,"강조할 점 · x | y | 이름");
        }else if(CHEM.equals(kind)){
            EditText formula=field(s.body,"중성 화학식 또는 반응식",spec.optString("formula","2H2O → 2H2 + O2"),2,240);
            read.add(()->put(spec,"formula",formula.getText().toString()));
        }else if(MAP.equals(kind)){
            List<String> ids=List.of("world","korea","eastAsia","europe","mediterranean");
            s.body.addView(ui.spinner("지도 범위",new String[]{"세계","한반도","동아시아","유럽","지중해"},Math.max(0,ids.indexOf(spec.optString("region"))),i->put(spec,"region",ids.get(i))));
            EditText bounds=field(s.body,"직접 지정할 범위 · 서쪽 경도 | 남쪽 위도 | 동쪽 경도 | 북쪽 위도",joined(spec.optJSONArray("bounds")," | "),1,110);
            read.add(()->{
                String v=bounds.getText().toString().strip();
                if(v.isBlank()){spec.remove("bounds");return;}
                String[] cells=v.split("\\s*\\|\\s*",-1);if(cells.length!=4)throw new IllegalArgumentException("경계 좌표 네 개를 입력하세요.");
                put(spec,"bounds",arr(decimal(cells[0]),decimal(cells[1]),decimal(cells[2]),decimal(cells[3])));
            });
            rowsField(s,spec,read,"places","장소 · 이름 | 경도 | 위도",List.of("name","lon","lat"),16);
            s.body.addView(check("순서대로 경로 연결",spec.optBoolean("route"),on->put(spec,"route",on)));
        }else if(READING.equals(kind)){
            EditText source=field(s.body,"작품·사료 출처",spec.optString("source"),1,400);
            read.add(()->put(spec,"source",source.getText().toString()));
            rowsField(s,spec,read,"lines","한 줄에 본문 | 해설",List.of("text","note"),20);
            s.body.addView(check("해설 펼치기",spec.optBoolean("reveal",true),on->put(spec,"reveal",on)));
        }else{
            List<String> ids=List.of("animalCell","plantCell","neuron","earthLayers","");
            if(!spec.has("preset") && !spec.has("layers"))put(spec,"preset","animalCell");
            LinearLayout focuses=ui.column();
            Runnable focus=()->{
                focuses.removeAllViews();JSONArray parts=dev.jun.gyeot.core.BoardIllustration.parts(spec);
                List<String> keys=new java.util.ArrayList<>(List.of("")),names=new java.util.ArrayList<>(List.of("전체 보기"));
                for(int i=0;i<parts.length();i++){JSONObject part=parts.optJSONObject(i);keys.add(part.optString("id"));names.add(part.optString("label"));}
                focuses.addView(ui.spinner("강조할 부분",names.toArray(new String[0]),Math.max(0,keys.indexOf(spec.optString("focus"))),i->put(spec,"focus",keys.get(i))));
            };
            s.body.addView(ui.spinner("그림 바탕",new String[]{"동물 세포","식물 세포","뉴런","지구 내부","사용자 정의 층"},Math.max(0,ids.indexOf(spec.optString("preset"))),i->{put(spec,"preset",ids.get(i));spec.remove("focus");focus.run();}));
            s.body.addView(focuses);focus.run();
        }
        TextView status=notice(s);
        Consumer<Boolean> refresh=commit->{
            try{
                for(Runnable collect:read)collect.run();
                if(BoardWidget.make(kind,spec,0,.85f,Board.ME,board.measure)==null)throw new IllegalArgumentException("표시할 자료를 입력하세요.");
                preview.update(spec);status.setVisibility(View.GONE);
                if(commit){apply.accept(BoardWidget.copy(spec));s.dismiss();}
            }catch(IllegalArgumentException invalid){status.setText(invalid.getMessage());status.setVisibility(View.VISIBLE);}
        };
        s.body.addView(ui.button("미리보기 갱신",false,()->refresh.accept(false)));
        s.primary.setText("판에 적용");s.primary.setOnClickListener(v->refresh.accept(true));
        if(original.length()>0 || !READING.equals(kind))refresh.accept(false);
        return s;
    }

    private void rowsField(Ui.Sheet s,JSONObject spec,List<Runnable> read,String key,String label,List<String> fields,int limit){
        List<String> lines=new java.util.ArrayList<>();JSONArray rows=spec.optJSONArray(key);
        for(int i=0;rows!=null && i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            List<String> cells=new java.util.ArrayList<>();for(String f:fields)cells.add(row.optString(f));lines.add(String.join(" | ",cells));}
        String initial=String.join("\n",lines);
        EditText input=field(s.body,label,initial,3,Math.max(12000,initial.length()));
        read.add(()->{String now=input.getText().toString();if(now.equals(initial) && rows!=null){put(spec,key,rows);return;}
            JSONArray next=arr();for(String line:now.split("\\R")){if(line.isBlank())continue;
                String[] cells=line.split("\\s*\\|\\s*",fields.size());JSONObject row=obj();
                for(int i=0;i<fields.size();i++){
                    String f=fields.get(i),val=i<cells.length?cells[i].strip():"";
                    if(f.equals("lat") || f.equals("lon"))put(row,f,decimal(val));else put(row,f,val);
                }
                if(next.length()>=limit)throw new IllegalArgumentException(limit+"줄까지 넣을 수 있어요.");
                next.put(row);
            }put(spec,key,next);
        });
    }

    private EditText editPoints(Ui.Sheet s,JSONObject spec,List<Runnable> read,String label) {
        JSONArray points=spec.optJSONArray("points");List<String> strings=new java.util.ArrayList<>();
        for(int i=0;points!=null && i<points.length();i++) {
            JSONObject p=points.optJSONObject(i);JSONArray xy=p==null?points.optJSONArray(i):p.optJSONArray("at");
            if(xy!=null)strings.add(xy.optString(0)+" | "+xy.optString(1)+(p==null||p.optString("label").isBlank()?"":" | "+p.optString("label")));
        }
        String initial=String.join("\n",strings);EditText input=field(s.body,label,initial,2,8000);
        read.add(()->{String now=input.getText().toString();if(now.equals(initial)){if(points!=null)put(spec,"points",points);else spec.remove("points");return;}
            JSONArray changed=arr();for(String line:now.split("\\R")){
                if(line.isBlank())continue;String[] cells=line.split("\\s*\\|\\s*",3);
                if(cells.length<2 || changed.length()>=40)throw new IllegalArgumentException("한 줄에 x | y | 이름 형식으로 최대 40개를 입력하세요.");
                JSONObject p=BoardWidget.copy(points==null?null:points.optJSONObject(changed.length()));
                put(p,"at",arr(decimal(cells[0].strip()),decimal(cells[1].strip())));put(p,"label",cells.length==3?cells[2]:"");changed.put(p);
            }put(spec,"points",changed);
        });
        return input;
    }

    private Ui.Sheet data(String kind, JSONObject original, Consumer<JSONObject> apply) {
        Ui.Sheet sheet = ui.selectionSheet(name(kind), switch (kind) {
            case TABLE -> "한 줄에 한 행을 쓰고 탭 또는 | 로 칸을 나누세요. 스프레드시트의 셀도 붙여넣을 수 있어요.";
            case CHART -> "한 줄에 항목 | 숫자를 입력하세요.";
            case TIMELINE -> "한 줄에 날짜 또는 단계 | 설명을 입력하세요.";
            case TREE -> "한 줄에 가지 하나를 쓰세요. 뒤에 | 로 요점을 덧붙일 수 있어요.";
            case NUMBER_LINE -> "끝점은 숫자 | 라벨 | 열림 형식으로 입력하세요. ‘열림’을 쓰면 빈 점이 돼요.";
            default -> "한 줄에 한 단계를 입력하세요.";
        });
        JSONObject spec = BoardWidget.copy(original);
        EditText name = CHART.equals(kind) || TREE.equals(kind) ? field(sheet.body, CHART.equals(kind) ? "차트 제목" : "중심 주제",
            original.optString(CHART.equals(kind) ? "title" : "root"), 1, 100) : null;
        EditText unit = CHART.equals(kind) ? field(sheet.body, "단위", original.optString("unit"), 1, 20) : null;
        String source = lines(kind, original);
        EditText rows = field(sheet.body, switch (kind) {
            case TABLE -> "표의 행과 셀"; case CHART -> "항목과 값"; case FLOW -> "순서도 단계";
            case TREE -> "가지와 요점"; case NUMBER_LINE -> "표시할 점"; default -> "날짜와 설명";
        }, source, 5, Math.max(12000, source.length() + 2000));
        CheckBox header = TABLE.equals(kind) ? check("첫 줄을 머리글로", original.optBoolean("header", true), checked -> {}) : null;
        if (header != null) sheet.body.addView(header);
        if (header != null && original.optJSONArray("rows") == null) {
            sheet.body.addView(ui.button("빈 3×3 표", false, () -> rows.setText(" | | \n | | \n | | ")));
        }
        if (CHART.equals(kind)) {
            List<String> ids = List.of("bar", "line", "pie");
            sheet.body.addView(ui.pills(new String[]{"막대", "선", "원형"}, Math.max(0, ids.indexOf(original.optString("kind"))), i -> put(spec, "kind", ids.get(i))));
        }
        if (FLOW.equals(kind)) {
            boolean across = "right".equals(original.optString("direction"));
            sheet.body.addView(check("공간이 충분하면 가로로 배치", across, checked -> put(spec, "direction", checked ? "right" : "down")));
        }
        EditText min, max;
        if (NUMBER_LINE.equals(kind)) {
            LinearLayout range = ui.row(); sheet.body.addView(range);
            min = axis(range, "최솟값", null, 0, original.optString("from", "−5"));
            max = axis(range, "최댓값", null, 0, original.optString("to", "5"));
        } else { min = null; max = null; }
        TextView status = notice(sheet);
        sheet.primary.setText("판에 적용");
        sheet.primary.setOnClickListener(v -> {
            try {
                String contents = rows.getText().toString();
                if (original.length() == 0 || !contents.equals(source)) parseRows(kind, spec, contents);
                if (unit != null) put(spec, "unit", unit.getText().toString().strip());
                if (name != null) {
                    String title = name.getText().toString().strip();
                    if (TREE.equals(kind) && title.isEmpty()) throw new IllegalArgumentException("중심 주제를 입력하세요.");
                    put(spec, CHART.equals(kind) ? "title" : "root", title);
                }
                if (header != null) put(spec, "header", header.isChecked());
                if (min != null) {
                    JSONArray range = axisRange(min, max, "수직선", false);
                    put(spec, "from", range.optDouble(0)); put(spec, "to", range.optDouble(1));
                }
                apply.accept(spec); sheet.dismiss();
            } catch (IllegalArgumentException invalid) {
                status.setText(invalid.getMessage()); status.setVisibility(View.VISIBLE);
            }
        });
        return sheet;
    }

    private static String lines(String kind, JSONObject source) {
        List<String> out = new java.util.ArrayList<>();
        String key = switch (kind) {
            case TABLE -> "rows"; case FLOW -> "steps"; case TREE -> "children"; case TIMELINE -> "events";
            case NUMBER_LINE -> "marks"; default -> "values";
        };
        JSONArray rows = source.optJSONArray(key), labels = source.optJSONArray("labels");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject one = rows.optJSONObject(i); JSONArray cells = rows.optJSONArray(i);
            out.add(switch (kind) {
                case TABLE -> joined(cells, " | ");
                case CHART -> (labels == null ? "" : labels.optString(i)) + " | " + rows.optString(i);
                case FLOW -> one == null ? rows.optString(i) : one.optString("text");
                case TIMELINE -> one == null ? "" : one.optString("when") + " | " + one.optString("what");
                case TREE -> one == null ? rows.optString(i) : one.optString("text") + (one.optJSONArray("children") == null ? "" : " | " + joined(one.optJSONArray("children"), " | "));
                case NUMBER_LINE -> one == null ? "" : one.optString("at") + " | " + one.optString("label") + (one.optBoolean("open") ? " | 열림" : "");
                default -> "";
            });
        }
        return String.join("\n", out);
    }

    private static String joined(JSONArray cells, String divider) {
        List<String> texts = new java.util.ArrayList<>();
        for (int i = 0; cells != null && i < cells.length(); i++) texts.add(cells.optString(i).replace('\n', ' '));
        return String.join(divider, texts);
    }

    private static void parseRows(String kind, JSONObject spec, String content) {
        JSONArray rows = arr(), labels = arr();
        int limit = switch (kind) {
            case TABLE -> BoardPlot.ROWS; case CHART -> "pie".equals(spec.optString("kind")) ? BoardPlot.SLICES : BoardPlot.BARS;
            case FLOW -> BoardPlot.BOXES; case TIMELINE -> BoardPlot.EVENTS; case TREE -> BoardPlot.BRANCHES;
            default -> BoardPlot.POINTS;
        };
        for (String line : content.split("\\R", -1)) {
            if (line.isBlank() && (!(TABLE.equals(kind) && line.contains("\t")))) continue;
            if (rows.length() == limit) throw new IllegalArgumentException("이 도구에는 " + limit + "줄까지 넣을 수 있어요.");
            String[] cells = line.split(line.contains("\t") ? "\t" : "\\|", -1);
            String a = cells[0].strip(), b = cells.length > 1 ? cells[1].strip() : "";
            switch (kind) {
                case TABLE -> {
                    if (cells.length > BoardPlot.COLUMNS) throw new IllegalArgumentException("표에는 한 행에 여덟 칸까지 넣을 수 있어요.");
                    JSONArray row = arr(); for (String cell : cells) row.put(cell.strip()); rows.put(row);
                }
                case CHART -> {
                    double value = decimal(b);
                    if ("pie".equals(spec.optString("kind")) && value < 0) throw new IllegalArgumentException("원형 차트에는 음수를 넣을 수 없어요.");
                    if (Math.abs(value) > 1e150) throw new IllegalArgumentException("차트의 값이 너무 커요.");
                    labels.put(a); rows.put((Object) value);
                }
                case TIMELINE -> rows.put(obj("when", a, "what", b));
                case TREE -> {
                    JSONArray leaves = arr();
                    if (cells.length > BoardPlot.LEAVES + 1) throw new IllegalArgumentException("가지마다 요점을 여섯 개까지 넣을 수 있어요.");
                    for (int i = 1; i < cells.length; i++) if (!cells[i].isBlank()) leaves.put(cells[i].strip());
                    rows.put(obj("text", a, "children", leaves));
                }
                case NUMBER_LINE -> rows.put(obj("at", decimal(a), "label", b, "open", cells.length > 2 && cells[2].strip().equals("열림")));
                default -> rows.put(line.strip());
            }
        }
        if (rows.length() == 0 && !(NUMBER_LINE.equals(kind) || TREE.equals(kind))) throw new IllegalArgumentException("먼저 내용을 입력하세요.");
        switch (kind) {
            case TABLE -> put(spec, "rows", rows);
            case CHART -> { put(spec, "labels", labels); put(spec, "values", rows); }
            case FLOW -> put(spec, "steps", rows);
            case TREE -> put(spec, "children", rows);
            case NUMBER_LINE -> put(spec, "marks", rows);
            default -> put(spec, "events", rows);
        }
    }

    private TextView notice(Ui.Sheet sheet) {
        TextView status = ui.text("", FOOTNOTE, 600, RED); status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sheet.body.addView(status); return status;
    }

    private EditText field(LinearLayout in, String label, String value, int lines, int limit) {
        EditText edit = input(label, value, lines, limit); in.addView(edit, fieldBox(-1, 0)); return edit;
    }

    private EditText input(String label, String value, int lines, int limit) {
        EditText edit = ui.input(label, lines, limit);
        edit.setContentDescription(label); edit.setText(value); return edit;
    }

    private LinearLayout.LayoutParams fieldBox(int width, float weight) {
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(width, -2, weight);
        box.topMargin = ui.dp(5); box.bottomMargin = ui.dp(5); return box;
    }

    private EditText axis(LinearLayout in, String label, JSONArray range, int index, String fallback) {
        EditText edit = input(label, range == null ? fallback : range.optString(index, fallback), 1, 24);
        LinearLayout.LayoutParams box = fieldBox(0, 1);
        if (index == 0) box.rightMargin = ui.dp(8);
        in.addView(edit, box); return edit;
    }

    private static JSONArray axisRange(EditText first, EditText second, String axis, boolean auto) {
        String low = first.getText().toString().strip(), high = second.getText().toString().strip();
        if (auto && low.isEmpty() && high.isEmpty()) return null;
        double a = decimal(low), b = decimal(high);
        if (!(a < b) || Math.abs(a) > 1e6 || Math.abs(b) > 1e6)
            throw new IllegalArgumentException(axis + " 범위는 최솟값이 더 작아야 하고, −1,000,000~1,000,000 사이여야 해요.");
        return arr(a, b);
    }

    private static double decimal(String value) {
        try {
            double number = Double.parseDouble(value.replace('−', '-'));
            if (Double.isFinite(number)) return number;
        } catch (NumberFormatException invalid) { /* explained below */ }
        throw new IllegalArgumentException("값은 숫자로 입력하세요.");
    }

    private CheckBox check(String words, boolean checked, Consumer<Boolean> changed) {
        CheckBox view = new CheckBox(activity);
        view.setText(words); view.setTextColor(TEXT); view.setMinimumHeight(ui.dp(44)); view.setChecked(checked);
        view.setButtonTintList(ColorStateList.valueOf(ACCENT)); view.setOnCheckedChangeListener((v, on) -> changed.accept(on));
        return view;
    }

    private LinearLayout slider(String name, double min, double max, double step, double value, Consumer<Double> changed) {
        LinearLayout row = ui.row();
        TextView label = ui.text(name + "  " + BoardPlot.number(value), CALLOUT, 600, TEXT);
        row.addView(label, new LinearLayout.LayoutParams(ui.dp(98), -2));
        SeekBar bar = new SeekBar(activity);
        int ticks = Math.max(1, (int) Math.ceil((max - min) / step));
        bar.setContentDescription(name + " 조절"); bar.setMax(ticks);
        bar.setProgress(Math.min(ticks, (int) Math.round((value - min) / step)));
        row.addView(bar, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seek) { seek.getParent().requestDisallowInterceptTouchEvent(true); }
            @Override public void onStopTrackingTouch(SeekBar seek) { seek.getParent().requestDisallowInterceptTouchEvent(false); }
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                if (!fromUser) return;
                double next = progress == ticks ? max : Math.min(max, min + progress * step);
                label.setText(name + "  " + BoardPlot.number(next)); changed.accept(next);
            }
        });
        return row;
    }

    /** A bounded canvas for previews. A drag here changes data, while dragging the main board still scrolls it. */
    private final class Preview extends View {
        private final String kind;
        private final BoardView renderer;
        private JSONObject spec;
        private float height = 560, x0, y0, scale, lastX, lastY;
        private int vertex = -1;

        Preview(String kind) {
            super(activity); this.kind = kind;
            renderer = new BoardView(activity); renderer.style(board.style());
            setContentDescription(name(kind) + " 미리보기");
            setBackground(ui.shape(renderer.paper(), R_CARD)); setClipToOutline(true);
        }

        void update(JSONObject source) {
            spec = source;
            JSONObject item = BoardWidget.make(kind, source, 0, .85f, Board.ME, renderer.measure);
            if (item != null) {
                renderer.show(arr(item)); height = (float) item.optDouble("h", 560) + 28;
                setContentDescription(BoardWidget.description(item));
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            scale = Math.min(getWidth() / (float) Board.WIDTH, getHeight() / height);
            x0 = (getWidth() - Board.WIDTH * scale) / 2; y0 = Math.max(0, (getHeight() - height * scale) / 2);
            canvas.save(); canvas.translate(x0, y0);
            renderer.renderPage(canvas, scale * Board.WIDTH, 0, height);
            canvas.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (spec == null || scale <= 0 || (!GEOMETRY.equals(kind) && !SOLID.equals(kind))) return false;
            boolean solid = SOLID.equals(kind);
            if (solid && spec.optBoolean("net")) return false;
            float x = (event.getX() - x0) / scale, y = (event.getY() - y0) / scale;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (!solid) {
                        JSONArray vertices = BoardWidget.vertices(spec, false);
                        double distance = ui.dp(25) / scale;
                        for (int i = 0; i < vertices.length(); i++) {
                            JSONArray at = vertices.optJSONArray(i);
                            double d = Math.hypot(x - BoardWidget.vertexX(at.optDouble(0)), y - BoardWidget.vertexY(at.optDouble(1)));
                            if (d < distance) { distance = d; vertex = i; }
                        }
                        if (vertex < 0) return false;
                    }
                    getParent().requestDisallowInterceptTouchEvent(true);
                    lastX = x; lastY = y; return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (solid) {
                        double yaw = spec.optDouble("yaw", 30) + (x - lastX) * .4;
                        double pitch = spec.optDouble("pitch", 24) + (y - lastY) * .4;
                        put(spec, "yaw", Math.IEEEremainder(yaw, 360)); put(spec, "pitch", Math.max(-85, Math.min(85, pitch)));
                    } else if (vertex >= 0) {
                        JSONArray points = BoardWidget.vertices(spec, false), moved = arr();
                        double gx = Math.max(0, Math.min(10, (x - GEOMETRY_LEFT) / GEOMETRY_UNIT));
                        double gy = Math.max(0, Math.min(5, 5 - (y - GEOMETRY_TOP) / GEOMETRY_UNIT));
                        for (int i = 0; i < points.length(); i++) moved.put(i == vertex ? arr(gx, gy) : points.optJSONArray(i));
                        put(spec, "vertices", moved);
                    }
                    lastX = x; lastY = y; update(spec); return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vertex = -1; getParent().requestDisallowInterceptTouchEvent(false); performClick(); return true;
                }
                default -> { return true; }
            }
        }

        @Override public boolean performClick() { return super.performClick(); }
    }
}
