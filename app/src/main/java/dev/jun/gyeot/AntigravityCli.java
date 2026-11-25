package dev.jun.gyeot;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google's official Antigravity CLI (agy) in the app's Linux environment (LinuxRuntime): installed by Google's own
 * installer (it checks the download's SHA-512), signed in by agy itself (its link, the code pasted back), and asked with
 * agy -p, so the user's Antigravity subscription answers. Its home (sign-in, sessions) is /root/antigravity inside Linux;
 * deleting the Linux environment deletes it. Nothing here reads or copies the sign-in.
 */
final class AntigravityCli {
    private AntigravityCli() {}
    static final String HOME = "/root/antigravity", BINARY = HOME + "/.local/bin/agy", INSTALLER = "https://antigravity.google/cli/install.sh";
    private static final Pattern SIGNED_OUT = Pattern.compile("(?i)authentication required|unauthenticated|not (?:logged|signed)[- ]?in|please (?:sign|log)[- ]?in|(?:sign|log)[- ]?in (?:first|required)|login required");
    private static final Pattern NETWORK = Pattern.compile("(?i)could not resolve|network is unreachable|connection (?:refused|reset|timed out)|temporary failure in name resolution|no route to host");

    /** The environment agy runs in: its own home, the manual sign-in (a link to open, a code to paste), no colours. */
    static Map<String, String> env() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", HOME); env.put("PATH", HOME + "/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin");
        env.put("TERM", "xterm-256color"); env.put("NO_COLOR", "1");
        // A remote-style session: agy shows its sign-in link and asks for the code instead of waiting for a browser on this "machine".
        env.put("SSH_CONNECTION", "127.0.0.1 1 127.0.0.1 1"); env.put("BROWSER", "/root/.gyeot/open-browser");
        return env;
    }
    /** Folders and the browser stand-in agy expects (it prints the link, which the app shows as a button). */
    static void prepare(Context c) throws Exception {
        File root = LinuxRuntime.rootfs(c);
        new File(root, "root/antigravity/.local/bin").mkdirs();
        File open = new File(root, "root/.gyeot/open-browser"); open.getParentFile().mkdirs();
        Files.write(open.toPath(), "#!/bin/sh\nprintf '\\n%s\\n' \"$1\"\n".getBytes(StandardCharsets.UTF_8));
        android.system.Os.chmod(open.getPath(), 0755);
        // pacman under PRoot cannot switch to its download user; the rest of its setup stays as it is.
        File conf = new File(root, "etc/pacman.conf");
        if (conf.isFile()) {
            String text = new String(Files.readAllBytes(conf.toPath()), StandardCharsets.UTF_8);
            if (text.contains("\nDownloadUser")) Files.write(conf.toPath(), text.replace("\nDownloadUser", "\n#DownloadUser").getBytes(StandardCharsets.UTF_8));
        }
    }
    /**
     * agy is there: the installer may leave a link to its versioned build (a path inside Linux, which the app cannot follow
     * from outside), so the entry itself is looked for, not what it points to.
     */
    static boolean installed(Context c) {
        return LinuxRuntime.installed(c) && java.nio.file.Files.exists(new File(LinuxRuntime.rootfs(c), BINARY.substring(1)).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }
    /** The install, as the terminal runs it: Google's installer fetched with curl, then run (an update installs afresh). */
    static String installCommand(boolean fresh) {
        return (fresh ? "rm -f " + BINARY + "; " : "") + "mkdir -p " + HOME + "/.local/bin && curl -fsSL " + INSTALLER + " -o /tmp/agy-install.sh && bash /tmp/agy-install.sh";
    }
    /** Signing in: agy itself, which shows Google's link and asks for the code; signed in, it stays open (끝내기 ends it). */
    static String loginCommand() { return BINARY; }

    /** What the settings page shows: installed, its version, signed in (null: could not tell), and the plan's limits. */
    record Status(boolean installed, String version, Boolean signedIn, List<String> usage, String detail, dev.jun.gyeot.core.Limits limits) {
        Status(boolean installed, String version, Boolean signedIn, List<String> usage, String detail) { this(installed, version, signedIn, usage, detail, dev.jun.gyeot.core.Limits.NONE); }
    }
    static Status status(Context c) {
        if (!installed(c)) return new Status(false, "", false, List.of(), "");
        String version = "";
        try { LinuxRuntime.Result v = LinuxRuntime.run(c, BINARY + " --version", 60_000, env()); version = firstLine(v.output()); } catch (Exception ignored) {}
        try {
            LinuxRuntime.Result r = LinuxRuntime.run(c, BINARY + " -p /usage </dev/null", 120_000, env());
            String text = r.output();
            if (r.code() < 0 || NETWORK.matcher(text).find()) return new Status(true, version, null, List.of(), clip(text));
            if (SIGNED_OUT.matcher(text).find()) return new Status(true, version, false, List.of(), "");
            if (r.code() != 0 || text.isBlank()) return new Status(true, version, null, List.of(), clip(text));
            // /usage is a panel of agy's own screen; agy -p may take it as a question and answer in words. Then the panel itself
            // is read through a terminal (panelCommand), the way it shows to a person (core/AgyUsage).
            String seen = text; List<dev.jun.gyeot.core.AgyUsage.Row> found = dev.jun.gyeot.core.AgyUsage.rows(text);
            if (found.isEmpty()) {
                try { LinuxRuntime.Result p = LinuxRuntime.run(c, panelCommand(), 75_000, env()); seen = p.output(); found = dev.jun.gyeot.core.AgyUsage.rows(seen); }
                catch (Exception unread) { /* What -p said is shown instead. */ }
            }
            // As bars by family and window (Claude·GPT 5시간, Gemini 주간), the way the other plans' limits show; lines only for
            // what could not be read as one.
            long now = System.currentTimeMillis();
            List<dev.jun.gyeot.core.Limits.Window> windows = dev.jun.gyeot.core.AgyUsage.windows(found, now);
            List<String> rows = new ArrayList<>();
            if (windows.isEmpty()) for (String line : dev.jun.gyeot.core.AgyUsage.lines(found)) if (rows.size() < 12) rows.add(line);
            String reached = dev.jun.gyeot.core.AgyUsage.reached(seen);
            if (!reached.isEmpty()) rows.add(0, "한도에 닿은 모델이 있어요 · " + dev.jun.gyeot.core.AgyUsage.korean(reached) + " 뒤 초기화");
            return new Status(true, version, true, rows, rows.isEmpty() && windows.isEmpty() ? (seen.contains("NO_SCRIPT") ? "모델별 한도 화면을 열 터미널(script)이 없어요. 설정 → Linux에서 패키지를 다시 설치해 주세요." : "모델별 한도를 읽지 못했어요 · agy가 보여 준 것: " + clip(seen)) : "", windows.isEmpty() ? dev.jun.gyeot.core.Limits.NONE : new dev.jun.gyeot.core.Limits(windows, now));
        } catch (Exception e) { return new Status(true, version, null, List.of(), e.getMessage() == null ? "" : e.getMessage()); }
    }
    /**
     * One answer from Antigravity (agy -p): the instructions and the input in one prompt, passed through a file so no
     * quoting can break it. Blocking: a background thread.
     */
    static String ask(Context c, String instructions, String input, long timeoutMs) throws Exception { return ask(c, "", instructions, input, timeoutMs, null); }
    /** As above on a model of agy's list ("" for agy's default), stoppable through cancel. */
    static String ask(Context c, String model, String instructions, String input, long timeoutMs, java.util.concurrent.atomic.AtomicReference<Runnable> cancel) throws Exception {
        if (!installed(c)) throw new IllegalStateException("Antigravity CLI가 설치되지 않았어요. 설정 → Antigravity에서 설치하고 로그인해 주세요.");
        if (!model.isEmpty() && !model.matches("[a-z0-9][a-z0-9._-]{1,120}")) throw new IllegalArgumentException("Antigravity 모델 이름을 확인해 주세요.");
        // A prompt file of its own per request, so two answers made at once do not share one.
        String name = "gyeot-agy-" + System.nanoTime() + ".txt";
        File prompt = new File(LinuxRuntime.rootfs(c), "tmp/" + name);
        String text = (instructions == null || instructions.isBlank() ? "" : instructions.strip() + "\n\n") + (input == null ? "" : input.strip());
        Files.write(prompt.toPath(), text.getBytes(StandardCharsets.UTF_8));
        try {
            LinuxRuntime.Result r = LinuxRuntime.run(c, BINARY + (model.isEmpty() ? "" : " --model " + model) + " -p \"$(cat /tmp/" + name + ")\" </dev/null", timeoutMs, env(), cancel);
            String out = LinuxTerminal.plain(r.output()).strip();
            if (SIGNED_OUT.matcher(out).find()) throw new IllegalStateException("Antigravity에 로그인해 주세요 (설정 → Antigravity → 로그인).");
            if (r.code() != 0 || out.isEmpty()) throw new IllegalStateException("Antigravity가 답하지 못했어요" + (out.isEmpty() ? "." : ": " + clip(out)));
            return out;
        } finally { prompt.delete(); }
    }
    /**
     * agy opened as a person would, in a terminal (script gives it one), /usage typed, the panel let draw, then Esc and two
     * Ctrl-D to leave. Wide and tall, so every model group is on screen. NO_SCRIPT when the terminal tool is not there.
     */
    static String panelCommand() {
        String keys = "( sleep 8; printf '/usage\\r'; sleep 15; printf '\\033'; sleep 1; printf '\\004'; sleep 1; printf '\\004'; sleep 2 )";
        return "cd " + HOME + " 2>/dev/null; if command -v script >/dev/null 2>&1; then " + keys
            + " | TERM=xterm-256color COLUMNS=150 LINES=160 script -q -c 'stty cols 150 rows 160 2>/dev/null; " + BINARY + "' /dev/null; else echo NO_SCRIPT; fi";
    }
    private static String firstLine(String s) { String t = LinuxTerminal.plain(s == null ? "" : s).strip(); int n = t.indexOf('\n'); return (n < 0 ? t : t.substring(0, n)).strip(); }
    private static String clip(String s) { String t = LinuxTerminal.plain(s == null ? "" : s).strip(); return t.length() > 300 ? t.substring(t.length() - 300) : t; }
}
