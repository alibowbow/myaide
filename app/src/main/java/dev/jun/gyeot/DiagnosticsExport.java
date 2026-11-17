package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.DiagnosticLog;
import dev.jun.gyeot.core.DiagnosticReport;
import dev.jun.gyeot.core.ExitReasons;
import dev.jun.gyeot.core.VoiceLatency;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 오류 진단 보고서 내보내기, as one self-contained module: it keeps the error journal (DiagnosticLog) in the app's
 * private files, builds the report (DiagnosticReport) from the app version, device, memory and Android permission states,
 * shows it for review, and shares or copies it only when the user taps. It reads no conversation, key, token, account
 * or saved setting, needs no manifest entry, and every failure inside it is swallowed so diagnostics never break the app.
 *
 * Wired in GyeotApp's main process, AppStore.error and 설정 → 정보.
 *
 * It also keeps the measurements the report shows: voice-call timings and Live token counts (VoiceLatency, numbers and
 * model ids only), Android's record of how earlier processes ended (ExitReasons), the small state summary handed to Android
 * for the next start, and the last 미디어 연결 점검 result.
 */
final class DiagnosticsExport {
    private static final String DIRECTORY = "diagnostics", FILE = "errors.json";
    private static final Object lock = new Object();
    private static DiagnosticLog journal;
    private static final ExecutorService disk = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-diagnostics"); t.setDaemon(true); return t; });
    private static final AtomicInteger pending = new AtomicInteger();
    private static final int MAX_PENDING = 64, MAX_FILE_BYTES = 128_000;
    private static final String VOICE_FILE = "voice.json", PREFS = "diagnostics", EXIT_SEEN = "exitNoticeAt", EXIT_PENDING = "exitNotice";
    // The main thread reads these during a call; they never wait on `lock`, which the diagnostics thread holds across an fsync.
    private static final AtomicReference<VoiceLatency> voice = new AtomicReference<>();
    private static final AtomicReference<byte[]> stateSummary = new AtomicReference<>();
    private static final AtomicBoolean voiceSaving = new AtomicBoolean(), exitPending = new AtomicBoolean();
    private static volatile List<ExitReasons.Exit> exits;
    private static volatile List<DiagnosticReport.Check> mediaChecks = List.of();

    private DiagnosticsExport() {}

    /** Notes an exception under an area of DiagnosticLog.AREAS ("work", "voice", "update", …); saved off the calling thread. */
    static void record(Context context, String area, Throwable error) {
        if (context == null || error == null) return;
        try { enqueue(context.getApplicationContext(), new DiagnosticLog().record(area, error, System.currentTimeMillis())); }
        catch (Throwable ignored) {}
    }
    /** Notes an error known only by its message, such as the text of the error banner. */
    static void record(Context context, String area, String message) {
        if (context == null || message == null || message.isBlank()) return;
        try { enqueue(context.getApplicationContext(), new DiagnosticLog().record(area, message, System.currentTimeMillis())); }
        catch (Throwable ignored) {}
    }
    private static void enqueue(Context app, DiagnosticLog.Entry entry) {
        if (pending.incrementAndGet() > MAX_PENDING) { pending.decrementAndGet(); return; }
        try { disk.execute(() -> {
            try { DiagnosticLog log = load(app); log.record(entry); persist(app, log); }
            catch (Throwable ignored) {} finally { pending.decrementAndGet(); }
        }); } catch (Throwable ignored) { pending.decrementAndGet(); }
    }
    /** Forgets every recorded error. */
    static void clear(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        disk.execute(() -> {
            boolean cleared = false;
            try {
                synchronized (lock) {
                    File target = file(app), temporary = new File(target.getParentFile(), FILE + ".tmp");
                    Files.deleteIfExists(temporary.toPath()); Files.deleteIfExists(target.toPath());
                    load(app).clear(); cleared = true;
                }
            } catch (Throwable ignored) {}
            boolean done = cleared;
            if (context instanceof Activity activity) activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) Toast.makeText(activity, done ? "오류 기록을 지웠어요" : "오류 기록을 지우지 못했어요.", Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * Records an uncaught exception as an "앱 강제 종료" entry before the previous handler (Android's crash dialog) runs.
     * Install once, from the main process only. Calling it again is harmless.
     */
    static void installCrashRecorder(Context context) {
        if (context == null) return;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof CrashRecorder) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashRecorder(context.getApplicationContext(), previous));
    }
    private static final class CrashRecorder implements Thread.UncaughtExceptionHandler {
        private final Context app;
        private final Thread.UncaughtExceptionHandler previous;
        CrashRecorder(Context app, Thread.UncaughtExceptionHandler previous) { this.app = app; this.previous = previous; }
        @Override public void uncaughtException(Thread thread, Throwable error) {
            // Written on this thread: the process ends right after, so a queued write would be lost.
            try { DiagnosticLog log = load(app); log.record("crash", error, System.currentTimeMillis()); persist(app, log); } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
        }
    }

    /** The process's voice timings. The saved history is merged in on the diagnostics thread, ahead of any later save or report. */
    static VoiceLatency voiceLatency(Context context) {
        VoiceLatency current = voice.get();
        if (current != null) return current;
        VoiceLatency created = new VoiceLatency();
        if (!voice.compareAndSet(null, created)) return voice.get();
        Context app = context.getApplicationContext();
        try { disk.execute(() -> {
            try { File file = voiceFile(app); if (file.isFile() && file.length() <= MAX_FILE_BYTES) created.restore(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
            catch (Throwable ignored) {}
        }); } catch (Throwable ignored) {}
        return created;
    }
    /** Saves the voice timings off the calling thread; saves already waiting cover later changes. */
    static void saveVoiceLatency(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        VoiceLatency current = voiceLatency(app);
        if (!voiceSaving.compareAndSet(false, true)) return;
        try { disk.execute(() -> { voiceSaving.set(false); try { write(voiceFile(app), current.save()); } catch (Throwable ignored) {} }); }
        catch (Throwable ignored) { voiceSaving.set(false); }
    }
    private static File voiceFile(Context app) { return new File(new File(app.getFilesDir(), DIRECTORY), VOICE_FILE); }

    /**
     * At process start: reads how Android ended this app's earlier processes and keeps it for the report. Returns the
     * one-time notice when the latest main-process exit was a system kill or ANR while a call or task ran, otherwise null.
     * The notice is saved until exitNoticeShown(): a process started in the background (accessibility rebind, tile, alarm)
     * can die again before any screen opens.
     */
    static String readExits(Context context) {
        if (context == null) return null;
        try {
            Context app = context.getApplicationContext();
            List<ExitReasons.Exit> read = exits(app, true);
            SharedPreferences seen = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long before = seen.getLong(EXIT_SEEN, 0);
            ExitReasons.Exit latest = ExitReasons.latest(read);
            String notice = ExitReasons.notice(read, before);
            if (notice == null) { String saved = seen.getString(EXIT_PENDING, ""); notice = saved == null || saved.isBlank() ? null : saved; }
            SharedPreferences.Editor edit = seen.edit();
            if (latest != null && latest.at() > before) edit.putLong(EXIT_SEEN, latest.at());
            if (notice != null) edit.putString(EXIT_PENDING, notice);
            edit.apply();
            exitPending.set(notice != null);
            return notice;
        } catch (Throwable ignored) { return null; }
    }
    /** The banner is on screen: a later start does not repeat the notice. */
    static void exitNoticeShown(Context context) {
        if (context == null || !exitPending.compareAndSet(true, false)) return;
        try { context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(EXIT_PENDING).apply(); }
        catch (Throwable ignored) {}
    }
    private static List<ExitReasons.Exit> exits(Context app, boolean fresh) {
        List<ExitReasons.Exit> kept = exits;
        if (kept != null && !fresh) return kept;
        List<ExitReasons.Exit> read = new ArrayList<>();
        try {
            ActivityManager manager = app.getSystemService(ActivityManager.class);
            if (manager != null) for (ApplicationExitInfo info : manager.getHistoricalProcessExitReasons(app.getPackageName(), 0, ExitReasons.LIMIT))
                read.add(ExitReasons.exit(info.getReason(), info.getImportance(), info.getTimestamp(), info.getDescription(),
                    info.getProcessStateSummary(), app.getPackageName().equals(info.getProcessName())));
        } catch (Throwable ignored) {}
        exits = List.copyOf(read);
        return exits;
    }
    /** Hands Android a few bytes of what is running (ExitReasons.summary), only when they change. */
    static void processState(Context context, String voiceMode, boolean phone, String work) {
        if (context == null) return;
        byte[] summary = ExitReasons.summary(voiceMode, phone, work);
        if (Arrays.equals(summary, stateSummary.getAndSet(summary))) return;
        try { ActivityManager manager = context.getSystemService(ActivityManager.class); if (manager != null) manager.setProcessStateSummary(summary); }
        catch (Throwable ignored) {}
    }
    /** What the last summary said; for tests. */
    static ExitReasons.State processState() { return ExitReasons.state(stateSummary.get()); }
    /** The latest 미디어 연결 점검 lines (model id, result, ms), shown in the next report. */
    static void mediaChecked(List<DiagnosticReport.Check> lines) { mediaChecks = lines == null ? List.of() : List.copyOf(lines); }

    /** The report text: standard checks first, then `extra` from the calling screen (may be null). */
    static String report(Context context, List<DiagnosticReport.Check> extra) {
        Context app = context.getApplicationContext();
        List<DiagnosticReport.Check> checks = standardChecks(app);
        if (extra != null) checks.addAll(extra);
        List<DiagnosticReport.Section> sections = new ArrayList<>();
        sections.add(new DiagnosticReport.Section("음성 지연·토큰", voiceLatency(app).lines()));
        sections.add(new DiagnosticReport.Section("직전 종료 원인", ExitReasons.lines(exits(app, false), ZoneId.systemDefault())));
        if (!mediaChecks.isEmpty()) sections.add(new DiagnosticReport.Section("미디어 연결 점검", mediaChecks));
        return DiagnosticReport.text(appInfo(app), device(app), memory(app), checks, sections, load(app), System.currentTimeMillis(), ZoneId.systemDefault());
    }
    /** Shows the report for review with 공유 · 복사 · 닫기. Nothing leaves the phone unless the user chooses to share or copy. */
    static void show(Activity activity, List<DiagnosticReport.Check> extra) {
        // Queued after prior error writes, so the preview includes the error the user just saw, without UI-thread disk I/O.
        Context app = activity.getApplicationContext();
        List<DiagnosticReport.Check> checks = extra == null ? List.of() : new ArrayList<>(extra);
        voiceLatency(app);
        disk.execute(() -> {
            String built;
            try { built = report(app, checks); }
            catch (Throwable failure) { built = null; }
            String text = built;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (text == null) Toast.makeText(activity, "진단 보고서를 만들지 못했어요.", Toast.LENGTH_LONG).show();
                else preview(activity, text);
            });
        });
    }
    private static void preview(Activity activity, String text) {
        String subject = DiagnosticReport.subject(appInfo(activity.getApplicationContext()));
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        Context themed = builder.getContext();
        TextView view = new TextView(themed);
        view.setText(text); view.setTextIsSelectable(true); view.setTextSize(13);
        int padding = Math.round(20 * themed.getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding / 2, padding, padding / 2);
        ScrollView scroll = new ScrollView(themed); scroll.addView(view);
        builder.setTitle("오류 진단 보고서").setView(scroll)
            .setPositiveButton("공유", (dialog, which) -> share(activity, subject, text))
            .setNeutralButton("복사", (dialog, which) -> copy(activity, text))
            .setNegativeButton("닫기", null)
            .show();
    }
    private static void share(Activity activity, String subject, String text) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, DiagnosticReport.safe(text));
            activity.startActivity(Intent.createChooser(send, "진단 보고서 보내기"));
        } catch (Exception e) { Toast.makeText(activity, "공유할 앱을 찾지 못했어요. ‘복사’를 이용해 주세요.", Toast.LENGTH_LONG).show(); }
    }
    private static void copy(Context context, String text) {
        try {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText(Brand.NAME + " 진단 보고서", DiagnosticReport.safe(text)));
            Toast.makeText(context, "진단 보고서를 복사했어요", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(context, "복사하지 못했어요.", Toast.LENGTH_SHORT).show(); }
    }

    private static DiagnosticLog load(Context app) {
        synchronized (lock) {
            if (journal == null) {
                try {
                    File file = file(app);
                    journal = DiagnosticLog.restore(file.isFile() && file.length() <= MAX_FILE_BYTES ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "");
                } catch (Exception e) { journal = new DiagnosticLog(); }
            }
            return journal;
        }
    }
    /** Writes to a temporary file and renames it, so a crash mid-write keeps the previous journal. */
    private static void persist(Context app, DiagnosticLog log) { synchronized (lock) { write(file(app), log.save()); } }
    /** The caller keeps writes to one file in order: the journal under `lock`, voice.json on the diagnostics thread alone. */
    private static void write(File target, String text) {
        try {
            File directory = target.getParentFile(), temporary = new File(directory, target.getName() + ".tmp");
            if (directory != null && !directory.isDirectory() && !directory.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            if (!temporary.renameTo(target)) temporary.delete();
        } catch (Exception ignored) {}
    }
    private static File file(Context app) { return new File(new File(app.getFilesDir(), DIRECTORY), FILE); }

    private static DiagnosticReport.App appInfo(Context app) {
        String version = ""; long code = -1;
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            version = info.versionName == null ? "" : info.versionName; code = info.getLongVersionCode();
        } catch (Exception ignored) {}
        return new DiagnosticReport.App(Brand.NAME, version, code, app.getPackageName());
    }
    private static DiagnosticReport.Device device(Context app) {
        String locale = "";
        try { locale = app.getResources().getConfiguration().getLocales().get(0).toLanguageTag(); } catch (Exception ignored) {}
        List<String> abis = Build.SUPPORTED_ABIS == null ? List.of() : Arrays.asList(Build.SUPPORTED_ABIS);
        return new DiagnosticReport.Device(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.VERSION.SECURITY_PATCH, abis, locale);
    }
    private static DiagnosticReport.Memory memory(Context app) {
        Runtime runtime = Runtime.getRuntime();
        long available = -1, total = -1, storage = -1; boolean low = false;
        try {
            ActivityManager manager = app.getSystemService(ActivityManager.class);
            if (manager != null) { ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo(); manager.getMemoryInfo(info); available = info.availMem; total = info.totalMem; low = info.lowMemory; }
        } catch (Exception ignored) {}
        try { storage = app.getFilesDir().getUsableSpace(); } catch (Exception ignored) {}
        return new DiagnosticReport.Memory(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), available, total, low, storage);
    }
    /** Android's own permission and power states that explain most "nothing happens" reports; no app setting is read. */
    private static List<DiagnosticReport.Check> standardChecks(Context app) {
        List<DiagnosticReport.Check> checks = new ArrayList<>();
        checks.add(permission(app, "마이크 권한", Manifest.permission.RECORD_AUDIO));
        checks.add(permission(app, "알림 권한", Manifest.permission.POST_NOTIFICATIONS));
        checks.add(permission(app, "카메라 권한", Manifest.permission.CAMERA));
        try { checks.add(new DiagnosticReport.Check("다른 앱 위에 표시", Settings.canDrawOverlays(app) ? "허용됨" : "허용 안 됨")); } catch (Exception ignored) {}
        try {
            PowerManager power = app.getSystemService(PowerManager.class);
            if (power != null) {
                checks.add(new DiagnosticReport.Check("절전 모드", power.isPowerSaveMode() ? "켜짐" : "꺼짐"));
                checks.add(new DiagnosticReport.Check("배터리 최적화", power.isIgnoringBatteryOptimizations(app.getPackageName()) ? "제외됨" : "적용 중"));
            }
        } catch (Exception ignored) {}
        try { checks.add(new DiagnosticReport.Check("업데이트 설치 허용", app.getPackageManager().canRequestPackageInstalls() ? "허용됨" : "허용 안 됨")); } catch (Exception ignored) {}
        return checks;
    }
    private static DiagnosticReport.Check permission(Context app, String label, String permission) {
        String state;
        try { state = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED ? "허용됨" : "허용 안 됨"; } catch (Exception e) { state = ""; }
        return new DiagnosticReport.Check(label, state);
    }
}
