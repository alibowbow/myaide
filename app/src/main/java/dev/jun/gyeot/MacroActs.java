package dev.jun.gyeot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.Macros;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 매크로 by words: 퍼레's macro tool ({"tool":"macro","command":record|stop|run|list|delete|save,"name":…}, from its brain or
 * from the words alone, local), and the voice assistant's run_macro. Off the main thread; a recording starts and ends there.
 */
final class MacroActs {
    private MacroActs() {}
    /** What came of an act: done or not, the words to say, the tap that clears the way, and whether it leaves for another app. */
    record Said(boolean ok, String say, String fix, boolean leaves) {}

    /** 퍼레's words about macros the phone can do with no brain ("매크로 녹화 시작", "녹화 끝", "출근 체크 해 줘"), or null. */
    static JSONObject local(Context c, String said) {
        try { return Macros.local(MacroBook.list(c), said, MacroRecorder.recording(), MacroBook.offered(System.currentTimeMillis()) != null); }
        catch (RuntimeException unreadable) { return null; }
    }

    static Said run(Context c, JSONObject act, String heard) {
        Context app = c.getApplicationContext();
        String command = act.optString("command", act.optString("action", "run")).strip().toLowerCase(java.util.Locale.ROOT);
        String name = act.optString("name", act.optString("macro")).strip();
        try {
            switch (command) {
                case "record", "start" -> {
                    if (MacroRecorder.recording()) return new Said(false, "이미 녹화 중이야. 다 하면 위에 뜬 ‘끝’을 눌러 줘.", "", false);
                    onMain(() -> { MacroRecorder.start(app, name, true); return null; });
                    return new Said(true, "녹화 시작할게! 할 일을 직접 한 다음 위에 뜬 ‘끝’을 눌러 줘.", "", true);
                }
                case "stop", "end", "finish" -> {
                    MacroRecorder.Saved s = onMain(() -> MacroRecorder.stop(app, false));
                    Macros.Macro m = s.macro();
                    return new Said(true, "‘" + m.name() + "’ 매크로로 저장했어 · " + m.steps().size() + "단계." + (s.note().isEmpty() ? "" : " " + s.note() + ".")
                        + " ‘" + m.name() + " 해 줘’라고 하면 그대로 할게.", "", false);
                }
                case "list" -> {
                    List<Macros.Macro> all = MacroBook.list(app);
                    return new Said(true, all.isEmpty() ? "아직 매크로가 없어. ‘매크로 녹화 시작’이라고 하면 같이 만들 수 있어." : "매크로는 " + Macros.names(all) + " 있어.", "", false);
                }
                case "delete", "remove" -> {
                    Macros.Macro m = MacroBook.named(app, name.isEmpty() ? heard : name);
                    if (m == null) return new Said(false, "그런 매크로가 없어.", "", false);
                    MacroBook.change(app, all -> Macros.remove(all, m.id()));
                    return new Said(true, "‘" + m.name() + "’ 매크로 지웠어.", "", false);
                }
                case "save" -> {
                    Macros.Macro m = MacroBook.saveOffer(app, name, System.currentTimeMillis());
                    return new Said(true, "‘" + m.name() + "’ 매크로로 저장했어. 다음엔 AI 없이 그대로 할게.", "", false);
                }
                default -> {
                    Macros.Macro m = MacroBook.named(app, name.isEmpty() ? heard : name);
                    if (m == null && !name.isEmpty()) m = MacroBook.named(app, name + " 매크로");
                    if (m == null) {
                        List<Macros.Macro> all = MacroBook.list(app);
                        return new Said(false, all.isEmpty() ? "아직 매크로가 없어. ‘매크로 녹화 시작’이라고 하면 같이 만들 수 있어." : "그런 매크로는 없어. 있는 건 " + Macros.names(all) + "이야.", "", false);
                    }
                    BuddyHands.Started s = MacroRunner.start(app, m, false, heard, null);
                    return new Said(s.ok(), s.say(), s.fix(), s.ok());
                }
            }
        } catch (Exception failed) {
            return new Said(false, failed.getMessage() == null ? "매크로를 다루지 못했어." : failed.getMessage(), "", false);
        }
    }

    /** The voice assistant's run_macro: one by its name (started, not waited for), or the names when none is given. */
    static JSONObject voice(Context c, JSONObject args) {
        Context app = c.getApplicationContext();
        String name = args == null ? "" : args.optString("name").strip();
        List<Macros.Macro> all = MacroBook.list(app);
        if (name.isEmpty()) {
            JSONArray names = arr(); for (Macros.Macro m : all) names.put(m.name());
            return obj("ok", true, "macros", names, "note", all.isEmpty() ? "No macros yet: the user records one in 설정 → 한마디 루틴 → 매크로 녹화, or by telling 퍼레 매크로 녹화 시작." : "");
        }
        Macros.Macro m = MacroBook.named(app, name);
        if (m == null) m = MacroBook.named(app, name + " 매크로");
        if (m == null) { JSONArray names = arr(); for (Macros.Macro x : all) names.put(x.name()); return obj("ok", false, "error", "그런 매크로가 없어요.", "macros", names); }
        BuddyHands.Started s = MacroRunner.start(app, m, false, name, null);
        return s.ok() ? obj("ok", true, "started", true, "macro", m.name(), "steps", m.steps().size(), "say", s.say(), "next", "It runs by itself now; do not operate the phone meanwhile. Its result shows as a notification if it stops.")
            : obj("ok", false, "error", s.say());
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static <T> T onMain(java.util.concurrent.Callable<T> what) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return what.call();
        CompletableFuture<T> done = new CompletableFuture<>();
        MAIN.post(() -> { try { done.complete(what.call()); } catch (Exception e) { done.completeExceptionally(e); } });
        try { return done.get(10, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException e) { throw e.getCause() instanceof Exception x ? x : new IllegalStateException(e.getCause()); }
    }
}
