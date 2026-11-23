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
    private static final Pattern USAGE = Pattern.compile("^\\s*(\\S.*?)(?:\\t+|\\s{2,})(\\S.*?)(?:\\t+|\\s{2,})(\\d{1,3}(?:\\.\\d+)?)%(?:(?:\\t+|\\s{2,})(\\S+))?\\s*$");

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
    record Status(boolean installed, String version, Boolean signedIn, List<String> usage, String detail) {}
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
            List<String> rows = new ArrayList<>();
            for (String line : text.split("\n")) {
                Matcher m = USAGE.matcher(line);
                if (m.matches() && rows.size() < 8) rows.add(m.group(1).strip() + " · " + m.group(2).strip() + " · " + m.group(3) + "% 남음" + (m.group(4) == null ? "" : " · " + m.group(4) + " 초기화"));
            }
            return new Status(true, version, true, rows, rows.isEmpty() ? clip(text) : "");
        } catch (Exception e) { return new Status(true, version, null, List.of(), e.getMessage() == null ? "" : e.getMessage()); }
    }
    /**
     * One answer from Antigravity (agy -p): the instructions and the input in one prompt, passed through a file so no
     * quoting can break it. Blocking: a background thread.
     */
    static String ask(Context c, String instructions, String input, long timeoutMs) throws Exception {
        if (!installed(c)) throw new IllegalStateException("Antigravity CLI가 설치되지 않았어요. 설정 → Antigravity에서 설치하고 로그인해 주세요.");
        File prompt = new File(LinuxRuntime.rootfs(c), "tmp/gyeot-agy-prompt.txt");
        String text = (instructions == null || instructions.isBlank() ? "" : instructions.strip() + "\n\n") + (input == null ? "" : input.strip());
        Files.write(prompt.toPath(), text.getBytes(StandardCharsets.UTF_8));
        try {
            LinuxRuntime.Result r = LinuxRuntime.run(c, BINARY + " -p \"$(cat /tmp/gyeot-agy-prompt.txt)\" </dev/null", timeoutMs, env());
            String out = LinuxTerminal.plain(r.output()).strip();
            if (SIGNED_OUT.matcher(out).find()) throw new IllegalStateException("Antigravity에 로그인해 주세요 (설정 → Antigravity → 로그인).");
            if (r.code() != 0 || out.isEmpty()) throw new IllegalStateException("Antigravity가 답하지 못했어요" + (out.isEmpty() ? "." : ": " + clip(out)));
            return out;
        } finally { prompt.delete(); }
    }
    private static String firstLine(String s) { String t = LinuxTerminal.plain(s == null ? "" : s).strip(); int n = t.indexOf('\n'); return (n < 0 ? t : t.substring(0, n)).strip(); }
    private static String clip(String s) { String t = LinuxTerminal.plain(s == null ? "" : s).strip(); return t.length() > 300 ? t.substring(t.length() - 300) : t; }
}
