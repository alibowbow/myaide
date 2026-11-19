package dev.jun.gyeot;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import dev.jun.gyeot.core.Board;
import dev.jun.gyeot.core.BoardInk;
import dev.jun.gyeot.core.BoardScript;
import dev.jun.gyeot.core.BoardWidget;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import static dev.jun.gyeot.core.Jsons.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class, qualifiers="w390dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BoardInkTests {
    private static View find(View v, Predicate<View> match) {
        if (match.test(v)) return v;
        if (v instanceof ViewGroup group) {
            for (int i=0; i<group.getChildCount(); i++) {
                View result = find(group.getChildAt(i), match);
                if (result!=null) return result;
            }
        }
        return null;
    }
    private static void click(View root, String name) {
        View control = find(root, v -> name.equals(v.getContentDescription()));
        assertNotNull(name,control); assertTrue(name,control.performClick());
    }
    private static void layout(View view,int width,int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        view.layout(0,0,width,height);
    }
    private static void finger(BoardView board,float x,float y,float endX,float endY) {
        for (int action : new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}) {
            float t = action == MotionEvent.ACTION_DOWN ? 0 : action == MotionEvent.ACTION_MOVE ? .5f : 1;
            MotionEvent event = MotionEvent.obtain(50,50+10*action,action,x+(endX-x)*t,y+(endY-y)*t,0);
            assertTrue(board.onTouchEvent(event)); event.recycle();
        }
    }
    private static void stylus(BoardView board) {
        var pen = new MotionEvent.PointerProperties();
        pen.id=0; pen.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        for (int i=0;i<10;i++) {
            var xy = new MotionEvent.PointerCoords();
            xy.x=40+i*17; xy.y=170; xy.pressure=i<5 ? .2f : 1f; xy.size=.3f;
            int action=i==0 ? MotionEvent.ACTION_DOWN : i==9 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            var event=MotionEvent.obtain(100,100+10*i,action,1,new MotionEvent.PointerProperties[]{pen},
                    new MotionEvent.PointerCoords[]{xy},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
            assertTrue(board.onTouchEvent(event)); event.recycle();
        }
    }
    @Test public void pensMarksHistoryAndPreferencesUseTheRealScreen() throws Exception {
        try (var screen=Robolectric.buildActivity(BoardActivity.class).setup()) {
            BoardActivity activity=screen.get();
            View root=activity.findViewById(android.R.id.content);
            layout(root,390,820);
            BoardView board=(BoardView)find(root,v->v instanceof BoardView);
            click(root,"필기 도구 열기");
            layout(root,390,820);
            BoardPenShelf shelf=(BoardPenShelf)find(root,v->v instanceof BoardPenShelf);
            assertEquals(LinearLayout.VERTICAL,shelf.getOrientation());
            assertEquals(BoardInk.PEN,board.tool());
            click(root,"연필"); click(root,"청록"); finger(board,32,115,110,124);
            assertEquals("pencil",board.items().getJSONObject(0).getString("nib"));
            click(root,"붓펜"); assertEquals("indigo",board.color());
            click(root,"획 굵기"); click(ShadowDialog.getLatestDialog().getWindow().getDecorView(),"굵게");
            stylus(board);
            JSONObject brush=Board.clean(board.items()).getJSONObject(1);
            JSONArray pressures=brush.getJSONArray("pressure");
            assertEquals(10,pressures.length()); assertEquals(20,pressures.getInt(1)); assertEquals(100,pressures.getInt(7));
            assertEquals(22.2,brush.getDouble("w"),.2);
            var plotted=board.render(1000,0,800);
            JSONArray points=brush.getJSONArray("p");
            int y=points.getInt(1), light=points.getInt(6), heavy=points.getInt(12);
            int paper=board.paper(), lightPixels=0, heavyPixels=0;
            for(int n=y-24;n<=y+24;n++) {
                if(plotted.getPixel(light,n)!=paper)lightPixels++;
                if(plotted.getPixel(heavy,n)!=paper)heavyPixels++;
            }
            assertTrue("Rendered brush responds to pressure",heavyPixels>lightPixels+4);
            click(root,"형광펜"); assertEquals("yellow",board.color()); click(root,"분홍"); finger(board,40,222,130,222);
            assertTrue(board.items().getJSONObject(2).getBoolean("hl"));
            assertEquals("pink",board.items().getJSONObject(2).optString("c"));
            click(root,"기호와 도형"); click(ShadowDialog.getLatestDialog().getWindow().getDecorView(),"체크");
            finger(board,54,264,54,264); assertEquals("check",board.items().getJSONObject(3).getString("mark"));
            click(root,"되돌리기"); assertEquals(3,board.items().length());
            click(root,"다시 하기"); assertEquals(4,board.items().length());
            click(root,"지우개"); finger(board,55,268,54,267); assertEquals(3,board.items().length());
            click(root,"되돌리기"); assertEquals(4,board.items().length());
            click(root,"모든 잉크 색"); click(ShadowDialog.getLatestDialog().getWindow().getDecorView(),"보라");
            assertEquals("purple",board.color());
            click(root,"연필"); assertEquals("teal",board.color());
            click(root,"붓펜"); assertEquals(12,board.penWidth(),0);
            click(root,"필기 도구 닫기"); assertEquals(BoardView.HAND,board.tool());
            click(root,"필기 도구 열기"); assertEquals(BoardInk.BRUSH,board.tool());
        }
        try (var again=Robolectric.buildActivity(BoardActivity.class).setup()) {
            BoardView board=(BoardView)find(again.get().findViewById(android.R.id.content),v->v instanceof BoardView);
            assertEquals(BoardInk.BRUSH,board.tool()); assertEquals("indigo",board.color());
            assertEquals(12,board.penWidth(),0); assertEquals(4,board.items().length());
            assertEquals(10,board.items().getJSONObject(1).getJSONArray("pressure").length());
        }
    }
    @Test @Config(qualifiers="sw600dp-w900dp-h1040dp-mdpi")
    public void wideScreenShowsOneShelfAndRendersARealLesson() throws Exception {
        GyeotApp app=(GyeotApp)RuntimeEnvironment.getApplication();
        var store=app.boardJobs().boards;
        String id=store.create("DNA 정보로 단백질 만들기",10).id();
        try (var screen=Robolectric.buildActivity(BoardActivity.class,new Intent(app,BoardActivity.class)
                .putExtra(BoardActivity.OPEN,id)).setup()) {
            View root=screen.get().findViewById(android.R.id.content);
            layout(root,900,1040);
            BoardView board=(BoardView)find(root,v->v instanceof BoardView);
            var flow=new BoardScript.Flow(arr(),board.measure,board.textScale,true);
            var lesson=new BoardScript.Step("",List.of(
                    obj("op","heading","text","유전 정보는 어떻게 전달될까?"),
                    obj("op","flow","direction","right","steps",arr(obj("text","DNA","color","teal"),obj("text","mRNA","color","indigo"),obj("text","단백질","color","rust"))),
                    obj("op","write","text","전사 · DNA의 정보를 RNA로 옮긴다."),
                    obj("op","mark","text","전사","kind","double","color","rust"),
                    obj("op","table","rows",arr(arr("구분","염기 서열"),arr("DNA 주형 가닥","3′–TAC CGA ATT–5′"),arr("만들어진 RNA","5′–AUG GCU UAA–3′"))),
                    obj("op","note","kind","tip","text","두 가닥의 방향을 함께 읽자."),
                    obj("op","heading","text","번역 · 세 염기씩 읽기","accent","plain","ink","indigo"),
                    obj("op","scene","preset","proteinSynthesis","stage",4)));
            assertTrue(board.addAll(flow.place(lesson)));
            // The widest cell and Latin term must survive native pixel rounding, even at fractional font sizes.
            for(float scale : new float[]{board.textScale,.92f}) {
                for(JSONObject op : lesson.draw()) {
                    String kind=op.optString("op");
                    if (!BoardWidget.TABLE.equals(kind) && !BoardWidget.FLOW.equals(kind)) continue;
                    var widget=BoardWidget.make(kind,op,0,scale,Board.AI,board.measure);
                    for(JSONObject item:BoardWidget.render(widget,board.measure)) {
                        if(!Board.TEXT.equals(item.optString("t")))continue;
                        String label=item.optString("text"); float size=(float)item.optDouble("s"); boolean bold=item.optBoolean("b");
                        assertEquals(label,board.measure.size(label,size,bold,900)[1],
                                board.measure.size(label,size,bold,(float)item.optDouble("mw"))[1], .1f);
                    }
                }
            }
            click(root,"필기 도구 열기"); click(root,"붓펜");
            layout(root,900,1040);
            BoardPenShelf shelf=(BoardPenShelf)find(root,v->v instanceof BoardPenShelf);
            assertEquals(LinearLayout.HORIZONTAL,shelf.getOrientation()); assertTrue(shelf.isShown());
            var png=Bitmap.createBitmap(900,1040,Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(png));
            try(var out=Files.newOutputStream(Path.of(System.getProperty("ui.output"),"board-ink-notebook.png"))) {
                assertTrue(png.compress(Bitmap.CompressFormat.PNG,100,out));
            }
        }
    }
}
