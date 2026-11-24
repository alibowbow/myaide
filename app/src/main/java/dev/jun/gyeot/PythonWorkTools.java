package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.PythonTools;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * python_run for a main-chat reply or a 작업창 run (PythonTools): the program runs in the app's Linux in its own folder
 * (LabPython.run), and the charts it saved are kept in 들퍼레/이미지. Offered only once 연구실's 계산 환경 is ready.
 */
final class PythonWorkTools extends WorkTools {
    private static final AtomicInteger runs = new AtomicInteger((int) (System.currentTimeMillis() / 1000 % 100000));
    private final Context context; private final String scope; private final BooleanSupplier stopped;
    private final AtomicReference<Runnable> cancel = new AtomicReference<>();
    private PythonWorkTools(Context context, String scope, BooleanSupplier stopped) { this.context = context.getApplicationContext(); this.scope = scope; this.stopped = stopped; }
    /** The tools when Python is ready on this phone, else null. scope: a short name for its folder (main, work id). */
    static PythonWorkTools offered(Context context, String scope, BooleanSupplier stopped) {
        try { return LabPython.ready(context) ? new PythonWorkTools(context, scope, stopped == null ? () -> false : stopped) : null; } catch (RuntimeException unavailable) { return null; }
    }
    @Override JSONArray declarations() { return PythonTools.declarations(); }
    @Override String instructions() { return PythonTools.INSTRUCTIONS; }
    @Override JSONObject run(String name, Object arguments) {
        if (!PythonTools.is(name)) return dev.jun.gyeot.core.GitHubFunctions.failure("허용하지 않은 도구예요.");
        try {
            JSONObject args = dev.jun.gyeot.core.ToolSchemas.arguments(arguments);
            String code = args.optString("code").strip();
            if (code.isEmpty() || "null".equals(code)) return dev.jun.gyeot.core.GitHubFunctions.failure("code에 실행할 Python 프로그램을 주세요.");
            if (code.length() > 200_000) return dev.jun.gyeot.core.GitHubFunctions.failure("프로그램이 너무 길어요.");
            if (stopped.getAsBoolean()) return dev.jun.gyeot.core.GitHubFunctions.failure("멈췄어요.");
            int n = runs.incrementAndGet();
            File copies = new File(context.getCacheDir(), "python-figures"); copies.mkdirs();
            Thread watch = new Thread(() -> { while (!Thread.currentThread().isInterrupted()) { if (stopped.getAsBoolean()) { Runnable r = cancel.getAndSet(null); if (r != null) r.run(); return; } try { Thread.sleep(500); } catch (InterruptedException done) { return; } } }, "python-stop");
            watch.setDaemon(true); watch.start();
            LabPython.Run run;
            try { run = LabPython.run(context, "python-" + scope.replaceAll("[^A-Za-z0-9_-]", "_"), n, code, copies, cancel); } finally { watch.interrupt(); }
            JSONArray kept = arr();
            for (File f : run.figures()) {
                try {
                    byte[] bytes = Files.readAllBytes(f.toPath());
                    String file = "python-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + "-" + f.getName().replaceFirst("^e[0-9]+-", "");
                    SharedMediaOutput.save(context, "python-" + n + "-" + f.getName(), "이미지", file, "image/png", bytes, () -> {});
                    kept.put("들퍼레/이미지/" + file);
                } catch (Exception unsaved) { kept.put(f.getName() + " (저장하지 못했어요)"); }
                finally { f.delete(); }
            }
            String output = run.output(); if (output.length() > 8000) output = "…" + output.substring(output.length() - 8000);
            return obj("ok", run.exit() == 0, "exit", run.exit(), "seconds", Math.round(run.millis() / 100.0) / 10.0, "output", output, "charts", kept,
                "note", run.exit() == 0 ? "" : "The program failed: read the error above, fix it and run again.");
        } catch (Exception failed) {
            return dev.jun.gyeot.core.GitHubFunctions.failure("Python을 실행하지 못했어요: " + (failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage()));
        }
    }
}
