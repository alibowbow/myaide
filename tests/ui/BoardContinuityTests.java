package dev.jun.gyeot;

import android.app.*;
import android.content.*;
import android.graphics.Insets;
import android.os.*;
import android.view.*;
import android.widget.*;
import dev.jun.gyeot.core.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAppTask;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static dev.jun.gyeot.core.Jsons.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "sw360dp-w400dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BoardContinuityTests {
    @Test public void menuKeepsItsCoordinatesAcrossPhoneWideWindowAndTablet() {
        try (var ctl = Robolectric.buildActivity(Activity.class).setup()) {
            Activity a = ctl.get();
            for (int width : new int[]{400, 720, 980}) {
                MainNavigation home = new MainNavigation(a, new View(a), "chat", t -> {});
                MainNavigation board = new MainNavigation(a, new View(a), "board", t -> {});
                View custom = new View(a); custom.setMinimumHeight(new Ui(a).dp(48)); board.bar(custom);
                int[] first = position(a, home, width), second = position(a, board, width);
                assertArrayEquals(first, second);
                assertEquals(width >= 680, board.wide());
                if (board.wide()) {
                    assertEquals(View.VISIBLE, board.rail.getVisibility());
                    assertTrue(board.railItems.getChildAt(3).isSelected());
                } else assertEquals(View.VISIBLE, board.menu.getVisibility());
            }
        }
    }
    @Test @Config(qualifiers="sw600dp-w600dp-h960dp")
    public void tabletKeepsRailWithDifferentHeaderHeights() {
        try (var ctl=Robolectric.buildActivity(Activity.class).setup()) {
            Activity a=ctl.get();
            MainNavigation home=new MainNavigation(a,new View(a),"chat",t->{});
            MainNavigation board=new MainNavigation(a,new View(a),"board",t->{});
            View header=new View(a);header.setMinimumHeight(new Ui(a).dp(72));board.bar(header);
            assertArrayEquals(position(a,home,600),position(a,board,600));assertTrue(board.wide());
        }
    }
    private int[] position(Activity a, MainNavigation nav, int width) {
        Ui ui = new Ui(a); a.setContentView(nav);
        nav.dispatchApplyWindowInsets(new WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(),
                Insets.of(0,ui.dp(24),0,ui.dp(16))).build());
        int w=ui.dp(width),h=ui.dp(800);
        nav.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));nav.layout(0,0,w,h);
        View control=nav.wide()?nav.railItems.getChildAt(3):nav.menu;
        int[] xy = new int[2]; control.getLocationInWindow(xy); return xy;
    }

    @Test public void launcherResumesBoardTaskAndColdLaunchOpensMain() {
        Application app = RuntimeEnvironment.getApplication();
        ActivityManager manager = app.getSystemService(ActivityManager.class);
        var task = ShadowAppTask.newInstance();
        var info = new ActivityManager.RecentTaskInfo();
        info.topActivity = new ComponentName(app, BoardActivity.class);
        shadowOf(task).setTaskInfo(info);
        shadowOf(manager).setAppTasks(List.of(task));
        try (var ctl = Robolectric.buildActivity(LauncherActivity.class).create()) {
            assertTrue(shadowOf(task).hasMovedToFront());
            assertNull(shadowOf(ctl.get()).getNextStartedActivity());
        }
        shadowOf(manager).setAppTasks(List.of());
        try (var ctl = Robolectric.buildActivity(LauncherActivity.class).create()) {
            Intent main = shadowOf(ctl.get()).getNextStartedActivity();
            assertEquals(new ComponentName(app,MainActivity.class),main.getComponent());
            assertTrue(main.hasCategory(Intent.CATEGORY_LAUNCHER));
        }
    }

    @Test public void viewRestoresTheSamePagePositionAndZoom() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        var items = arr(Board.text(48,3500,"마지막 줄",35,"ink",false,500,Board.ME));
        BoardView view = new BoardView(a); view.layout(0,0,800,1200); view.show(items);
        Bundle saved = new Bundle(); saved.putFloat("boardTop",920); saved.putFloat("boardZoom",2);
        saved.putFloat("boardLeft",123); assertTrue(view.restoreViewport(saved));
        Bundle snapshot=new Bundle();view.saveViewport(snapshot);
        BoardView restored=new BoardView(a);restored.layout(0,0,900,1000);restored.show(items);
        assertTrue(restored.restoreViewport(snapshot));Bundle again=new Bundle();restored.saveViewport(again);
        assertEquals(920,again.getFloat("boardTop"),.01);assertEquals(2,again.getFloat("boardZoom"),.01);
        assertEquals(123,again.getFloat("boardLeft"),.01);
    }

    @Test public void detachedRequestFinishesOnOriginalBoardExactlyOnce() throws Exception {
        Application app=RuntimeEnvironment.getApplication(); BoardJobs jobs=new BoardJobs(app);
        String original=jobs.boards.create("원래 판",1).id(), other=jobs.boards.create("다른 판",2).id();
        CountDownLatch started=new CountDownLatch(1), reply=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger(), oldScreen=new AtomicInteger();
        BoardJobs.Listener listener=r->oldScreen.incrementAndGet();jobs.watch(listener);
        jobs.submit(original,(sys,request,files,cancel)->{
            calls.incrementAndGet();started.countDown();
            if (!reply.await(8,TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
            return "{\"steps\":[{\"say\":\"그래프\",\"draw\":[{\"op\":\"graph\",\"functions\":[\"x^2\"]}]}]}";
        },()->new BoardJobs.Payload("이차함수",List.of()),arr(),Board.ROUGH,.85f,false,false);
        try {
            assertTrue(started.await(8,TimeUnit.SECONDS));
            jobs.unwatch(listener);
            assertTrue(jobs.running(original));
            assertEquals(0,jobs.boards.items(other).length());
        } finally { reply.countDown(); }
        waitFor(()->!jobs.running(original));
        assertEquals(1,calls.get());assertEquals(0,oldScreen.get());
        var result=jobs.boards.load(original);assertEquals(1,result.lessons().length());
        assertEquals("graph",result.items().getJSONObject(0).getString("kind"));
        assertEquals(0,jobs.boards.load(other).lessons().length());
    }
    @Test @Config(application=GyeotApp.class,qualifiers="sw800dp-w900dp-h1000dp")
    public void switchingToWorkMenuDuringModelCallReturnsToSavedResult() throws Exception {
        GyeotApp app=(GyeotApp)RuntimeEnvironment.getApplication();
        BoardStore records=app.boardJobs().boards;
        String id=records.create("삼차방정식",10).id();
        CountDownLatch called=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        BoardActivity.brain=(sys,req,files,cancel)->{
            calls.incrementAndGet();called.countDown();
            if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            return "{\"steps\":[{\"say\":\"교점\",\"draw\":[{\"op\":\"graph\",\"functions\":[\"x^3+3x-2\"]}]}]}";
        };
        try {
            Intent open=new Intent(app,BoardActivity.class).putExtra(BoardActivity.OPEN,id);
            try (var ctl=Robolectric.buildActivity(BoardActivity.class,open).setup()) {
                View root=ctl.get().getWindow().getDecorView();
                ((EditText)find(root,"판서로 물어볼 말")).setText("삼차함수의 그래프를 그려줘");
                find(root,"판서로 설명 듣기").performClick();
                assertTrue(called.await(8,TimeUnit.SECONDS));
                Intent holder=shadowOf(ctl.get()).getNextStartedService();
                assertNotNull(holder);assertEquals(BoardJobService.class.getName(),holder.getComponent().getClassName());
                find(root,"작업창 열기").performClick();
                Intent destination=shadowOf(ctl.get()).getNextStartedActivity();
                assertEquals(MainActivity.class.getName(),destination.getComponent().getClassName());
                assertEquals("work",destination.getStringExtra(MainNavigation.TARGET));
            }
            release.countDown();
            waitFor(()->!app.boardJobs().running(id));
            try (var again=Robolectric.buildActivity(BoardActivity.class).setup()) {
                assertNotNull(find(again.get().getWindow().getDecorView(),"방금 판서를 처음부터 다시 보기"));
                var doc=records.load(id);
                assertEquals(1,doc.lessons().length());assertEquals("graph",doc.items().getJSONObject(0).getString("kind"));
                assertEquals(1,calls.get());
            }
        } finally { release.countDown();BoardActivity.brain=null; }
    }
    private static View find(View root,String description) {
        if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
        if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++) {
            View view=find(group.getChildAt(i),description);if(view!=null)return view;
        }
        return null;
    }
    private static void waitFor(java.util.function.BooleanSupplier done) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while (!done.getAsBoolean() && System.nanoTime()<end) {
            shadowOf(Looper.getMainLooper()).idle();Thread.sleep(15);
        }
        assertTrue(done.getAsBoolean());
    }
    @Test public void cancelledResponseCannotWriteToBoard() throws Exception {
        var jobs=new BoardJobs(RuntimeEnvironment.getApplication());
        String id=jobs.boards.create("취소한 판",1).id();
        CountDownLatch asked=new CountDownLatch(1), returned=new CountDownLatch(1), release=new CountDownLatch(1);
        jobs.submit(id,(sys,req,files,cancel)->{
            asked.countDown();release.await(8,TimeUnit.SECONDS);returned.countDown();
            return "{\"steps\":[{\"draw\":[{\"op\":\"write\",\"text\":\"취소됨\"}]}]}";
        },()->new BoardJobs.Payload("테스트",List.of()),arr(),Board.ROUGH,1,false,false);
        assertTrue(asked.await(8,TimeUnit.SECONDS)); assertTrue(jobs.cancel(id)); release.countDown();
        assertTrue(returned.await(8,TimeUnit.SECONDS)); assertFalse(jobs.running(id));
        assertEquals(0,jobs.boards.load(id).lessons().length());
    }

    @Test public void drawReportedCubicsUsingAndroidCanvas() throws Exception {
        Application app=RuntimeEnvironment.getApplication();BoardView board=new BoardView(app);
        JSONArray items=arr(); float top=10;
        for (boolean steep:List.of(false,true)) {
            String expr=steep?"x^3+3x-2":"(x+1)(x+2)(x+3)";
            var spec=obj("height",280,"functions",arr(obj("expr",expr,"label","y = "+expr)),
                    "x",steep?arr(-1.2,1.6):arr(-3.5,-.5),"y",steep?arr(-7,8):arr(-1.5,1.5),
                    "points",steep?arr(obj("at",arr(.596,0),"label","0.596")):
                            arr(obj("at",arr(-3,0),"label","−3"),obj("at",arr(-2,0),"label","−2"),obj("at",arr(-1,0),"label","−1")));
            var item=BoardWidget.make("graph",spec,top,.85f,Board.AI,board.measure);
            items.put(item);top+=item.getDouble("h")+40;
        }
        board.show(items);
        var image=board.render(1000,0,top);
        try (var output=Files.newOutputStream(Path.of(System.getProperty("ui.output"),"board-cubic-scales.png"))) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));
        } finally { image.recycle(); }
    }
}
