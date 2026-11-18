package dev.jun.gyeot;

import static dev.jun.gyeot.core.Jsons.*;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import dev.jun.gyeot.core.*;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.FileOutputStream;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BoardSchoolTests {
    private static View find(View v, java.util.function.Predicate<View> f) {
        if (f.test(v)) return v;
        if (v instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) {
                View one = find(group.getChildAt(i), f);
                if (one != null) return one;
            }
        return null;
    }

    @Test
    public void fieldUsesRealAndroidRendererAndExports() throws Exception {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity a = controller.get();
            BoardView board = new BoardView(a);
            board.style("chalk");
            var spec =
                    obj(
                            "p",
                            17,
                            "a",
                            0,
                            "b",
                            7,
                            "points",
                            arr(
                                    obj("at", arr(1, 5), "label", "G (1, 5)"),
                                    obj("at", arr(1, 12), "label", "−G (1, 12)"),
                                    obj("at", arr(2, 10), "label", "2G (2, 10)")));
            var prep =
                    new BoardEditor(a, new Ui(a), board).open("finitefield", spec, v -> {}, null);
            prep.show();
            assertTrue(prep.primary.isEnabled());
            prep.dismiss();
            var widget = BoardWidget.make("finitefield", spec, 28, .85f, Board.AI, board.measure);
            board.show(arr(widget));
            android.graphics.Bitmap bm = board.render(1150, 0, (float) widget.optDouble("h") + 65);
            int background = bm.getPixel(5, 5), ink = 0;
            for (int y = 20; y < bm.getHeight() - 20; y += 3)
                for (int x = 40; x < bm.getWidth() - 20; x += 3)
                    if (background != bm.getPixel(x, y)) ink++;
            assertTrue(ink > 500);
            var path = Path.of(System.getProperty("ui.output"), "myaide-f17.png");
            try (FileOutputStream out = new FileOutputStream(path.toFile())) {
                assertTrue(bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
            }
        }
    }

    @Test
    public void classPickerSearchesBothEditionsAndRemembersBoard() throws Exception {
        try (var ctl = Robolectric.buildActivity(Activity.class).setup()) {
            Activity a = ctl.get();
            BoardClassroom catalog = new BoardClassroom(a, new Ui(a));
            for (String year : List.of("2022", "2015")) {
                var cfg =
                        obj(
                                "grade",
                                2,
                                "subject",
                                6,
                                "auto",
                                false,
                                "edition",
                                Integer.parseInt(year));
                AtomicReference<JSONObject> chosen = new AtomicReference<>();
                var sheet = catalog.open(cfg, chosen::set);
                sheet.show();
                EditText input = (EditText) find(sheet.body, v -> v instanceof EditText);
                input.setText("9수");
                View hit =
                        find(
                                sheet.body,
                                v ->
                                        v instanceof TextView t
                                                && t.getText().toString().startsWith("[9수"));
                assertNotNull(year, hit);
                View row = hit;
                while (row != null && !row.isClickable()) row = (View) row.getParent();
                assertNotNull(row);
                row.performClick();
                sheet.primary.performClick();
                assertTrue(chosen.get().has("code"));
                assertEquals(year, chosen.get().getString("edition"));
                var store = new BoardStore(a);
                var entry = store.create("", 1L);
                store.save(entry.id(), arr(), arr(), chosen.get(), "수학", 2L);
                assertEquals(
                        chosen.get().getString("key"),
                        store.load(entry.id()).classroom().getString("key"));
            }
        }
    }

    @Test
    public void teacherRepairsAndEditsSameGraph() throws Exception {
        try (var ctl = Robolectric.buildActivity(Activity.class).setup()) {
            Activity a = ctl.get();
            BoardView board = new BoardView(a);
            AppStore store = new AppStore(a);
            BoardVoice voice = new BoardVoice(a, store, null);
            String[] error = {""};
            AtomicInteger calls = new AtomicInteger();
            var teacher =
                    new BoardTeacher(
                            board,
                            voice,
                            (sys, input, files, cancel) -> {
                                int n = calls.incrementAndGet();
                                if (n == 1) return "{\"steps\":[{\"say\":\"그래프 설명\"}]}";
                                if (n == 2) {
                                    assertTrue(input.contains("검증 결과"));
                                    return obj(
                                                    "steps",
                                                    arr(
                                                            obj(
                                                                    "draw",
                                                                    arr(
                                                                            obj(
                                                                                    "op",
                                                                                    "finitefield",
                                                                                    "p",
                                                                                    17,
                                                                                    "a",
                                                                                    0,
                                                                                    "b",
                                                                                    7)))))
                                            .toString();
                                }
                                String id = board.items().getJSONObject(0).getString("wid");
                                assertTrue(input.contains(id));
                                return obj(
                                                "steps",
                                                arr(
                                                        obj(
                                                                "draw",
                                                                arr(
                                                                        obj(
                                                                                "op",
                                                                                "update",
                                                                                "target",
                                                                                id,
                                                                                "set",
                                                                                obj("b", 3))))))
                                        .toString();
                            },
                            new BoardTeacher.Watcher() {
                                public void state(String now, int step, int n, String words) {}

                                public void notice(String why) {
                                    error[0] = why;
                                }

                                public void wrote(String title) {}
                            });
            try {
                BoardTeacher.work = Runnable::run;
                teacher.mute(true);
                teacher.ask("그래프를 그려줘", List.of(), "Korean");
                assertEquals(2, calls.get());
                assertEquals("finitefield", board.items().getJSONObject(0).getString("kind"));
                teacher.stop();
                String id = board.items().getJSONObject(0).getString("wid");
                teacher.ask("기존 그림의 b를 3으로 바꿔 줘", List.of(), "Korean");
                teacher.stop();
                assertEquals("", error[0]);
                assertEquals(1, board.items().length());
                assertEquals(id, board.items().getJSONObject(0).getString("wid"));
                assertEquals(3, BoardWidget.spec(board.items().getJSONObject(0)).getInt("b"));
                String updatedLesson = teacher.latest();
                assertFalse(updatedLesson.isBlank());
                var kept = BoardStore.lessons(teacher.lessons());
                assertEquals(2, kept.getJSONObject(1).getJSONArray("frames").length());
                teacher.lessons(kept);
                teacher.replay(updatedLesson, 0);
                assertEquals(BoardTeacher.PLAYING, teacher.state());
                teacher.stop();
                assertEquals(3, BoardWidget.spec(board.items().getJSONObject(0)).getInt("b"));
                board.undo();
                assertEquals(7, BoardWidget.spec(board.items().getJSONObject(0)).getInt("b"));
            } finally {
                teacher.close();
                BoardTeacher.work = null;
            }
        }
    }

    @Test
    public void mapAndCustomDiagramUsePackagedResources() {
        try (var ctl = Robolectric.buildActivity(Activity.class).setup()) {
            Activity a = ctl.get();
            BoardView board = new BoardView(a);
            for (String kind :
                    List.of("map", "diagram", "reading", "chemistry", "relation", "solid")) {
                JSONObject spec =
                        switch (kind) {
                            case "map" ->
                                    obj(
                                            "region",
                                            "korea",
                                            "places",
                                            arr(obj("lon", 127, "lat", 37, "name", "A")));
                            case "diagram" -> obj("preset", "neuron", "focus", "axon");
                            case "reading" -> obj("lines", arr(obj("text", "A", "note", "B")));
                            case "chemistry" -> obj("formula", "Ca(OH)2");
                            case "relation" -> obj("mode", "implicit", "expr", "x^2+y^2=4");
                            default -> obj("kind", "cone");
                        };
                AtomicReference<JSONObject> saved = new AtomicReference<>();
                var editor =
                        new BoardEditor(a, new Ui(a), board).open(kind, spec, saved::set, null);
                editor.show();
                editor.primary.performClick();
                assertNotNull(kind, saved.get());
                var widget = BoardWidget.make(kind, saved.get(), 0, .85f, Board.ME, board.measure);
                assertNotNull(widget);
                board.show(arr(widget));
                assertNotNull(board.render(320, 0, (float) widget.optDouble("h")));
            }
        }
    }
}
