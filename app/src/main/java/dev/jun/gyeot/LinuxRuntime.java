package dev.jun.gyeot;

import android.content.Context;
import android.system.Os;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The Linux environment the user installs when they want it (설정 → Antigravity): Arch Linux ARM (aarch64) run by PRoot
 * inside the app's own storage, no root and no kernel of its own. The APK carries only PRoot (libproot.so and its loader
 * and libraries, tools/prepare_proot.py); the system itself is downloaded only when 설치 is pressed, checked against its
 * pinned size and SHA-256, unpacked into a staging folder (no path may leave it), run once (uname -m) and only then put in
 * place. Updating the app never downloads or turns it on; 삭제 removes it. Official CLIs (Antigravity's agy) run in it.
 */
final class LinuxRuntime {
    private LinuxRuntime() {}
    static final String DISTRIBUTION = "Arch Linux ARM (aarch64)";
    static final String URL_ROOTFS = "https://github.com/termux/proot-distro/releases/download/v4.29.0/archlinux-aarch64-pd-v4.29.0.tar.xz";
    static final String SHA256 = "08d74365213e647c558e561b0a2a7afb6fa3dfe345a1994c62ccac5af1a1cdc6";
    static final long ARCHIVE_BYTES = 151_744_988L, MOST_UNPACKED = 1_200_000_000L, MARGIN = 268_435_456L;
    static final String TOP = "archlinux-aarch64/";
    private static final String READY = ".gyeot-ready";

    /** What 설치 is doing, for the settings page (read on the main thread). */
    static volatile String state = ""; static volatile long done, total; static volatile boolean busy;
    private static volatile boolean cancelled;

    static File home(Context c) { return new File(c.getFilesDir(), "linux"); }
    static File rootfs(Context c) { return new File(home(c), "rootfs"); }
    private static File nativeDir(Context c) { return new File(c.getApplicationInfo().nativeLibraryDir); }
    /** This phone can run it: an arm64 phone with PRoot installed from the APK. */
    static boolean supported(Context c) { if (c.getApplicationInfo().nativeLibraryDir == null) return false; return new File(nativeDir(c), "libproot.so").isFile() && new File(nativeDir(c), "libproot_loader.so").isFile(); }
    static boolean installed(Context c) { return new File(rootfs(c), READY).isFile(); }
    /** Room the install needs: the archive, the unpacked system and a working margin. */
    static long needed() { return ARCHIVE_BYTES + MOST_UNPACKED + MARGIN; }
    static long free(Context c) { File h = home(c); h.mkdirs(); return h.getUsableSpace(); }
    /** The space the installed system takes (walked on a background thread). */
    static long used(Context c) { return size(rootfs(c)); }
    private static long size(File f) {
        if (Files.isSymbolicLink(f.toPath())) return 0;
        if (f.isFile()) return f.length();
        long sum = 0; File[] list = f.listFiles(); if (list != null) for (File each : list) sum += size(each);
        return sum;
    }

    static void cancel() { cancelled = true; }

    /** Downloads, checks, unpacks, tries and puts the system in place. Blocking: a background thread. Says why when it cannot. */
    static void install(Context c, Runnable changed) throws Exception {
        if (busy) throw new IllegalStateException("이미 설치하는 중이에요.");
        if (!supported(c)) throw new IllegalStateException("이 휴대폰(ARM64가 아닌 기기)에서는 Linux 환경을 쓸 수 없어요.");
        busy = true; cancelled = false; done = 0; total = ARCHIVE_BYTES;
        File home = home(c), archive = new File(home, "rootfs.tar.xz"), staging = new File(home, "staging");
        try {
            home.mkdirs();
            if (free(c) < needed() - (archive.isFile() ? archive.length() : 0)) throw new IOException("저장 공간이 모자라요. " + mb(needed()) + "가 필요해요(지금 " + mb(free(c)) + " 남음).");
            step("내려받는 중", changed);
            if (!archive.isFile() || archive.length() != ARCHIVE_BYTES || !SHA256.equals(sha256(archive))) download(archive, changed);
            step("압축을 푸는 중", changed); done = 0; total = MOST_UNPACKED;
            delete(staging); staging.mkdirs();
            unpack(archive, staging, changed);
            prepare(staging);
            step("실행해 보는 중", changed);
            Result check = run(c, staging, "uname -m && head -n 1 /etc/os-release", 60_000, null, null);
            if (check.code != 0 || !check.output.contains("aarch64")) throw new IOException("Linux를 실행해 보지 못했어요: " + clip(check.output));
            Files.write(new File(staging, READY).toPath(), (DISTRIBUTION + "\n" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            File root = rootfs(c); delete(root);
            if (!staging.renameTo(root)) throw new IOException("설치한 환경을 옮기지 못했어요.");
            archive.delete();
            step("", changed);
        } catch (Exception e) {
            delete(staging); step("", changed);
            if (cancelled) throw new IOException("설치를 취소했어요.");
            throw e;
        } finally { busy = false; cancelled = false; changed.run(); }
    }
    /** Removes the system (packages, its home, the CLIs' sign-ins); the app's own data stays. */
    static void remove(Context c) { delete(rootfs(c)); delete(new File(home(c), "staging")); new File(home(c), "rootfs.tar.xz").delete(); }

    private static void step(String what, Runnable changed) { state = what; changed.run(); }

    private static void download(File archive, Runnable changed) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(URL_ROOTFS).openConnection();
        http.setInstanceFollowRedirects(true); http.setConnectTimeout(20_000); http.setReadTimeout(60_000);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        File part = new File(archive.getPath() + ".part");
        try (InputStream in = new BufferedInputStream(http.getInputStream()); OutputStream out = new FileOutputStream(part)) {
            if (http.getResponseCode() != 200) throw new IOException("내려받지 못했어요 (HTTP " + http.getResponseCode() + ").");
            byte[] buffer = new byte[64 * 1024]; long got = 0, shown = 0; int n;
            while ((n = in.read(buffer)) > 0) {
                if (cancelled) throw new IOException("취소");
                got += n; if (got > ARCHIVE_BYTES) throw new IOException("내려받은 파일이 예상보다 커요.");
                out.write(buffer, 0, n); digest.update(buffer, 0, n); done = got;
                if (got - shown > 2_000_000) { shown = got; changed.run(); }
            }
        } finally { http.disconnect(); }
        if (part.length() != ARCHIVE_BYTES || !SHA256.equals(hex(digest.digest()))) { part.delete(); throw new IOException("내려받은 파일이 고정된 크기·SHA-256과 달라요. 다시 시도해 주세요."); }
        if (!part.renameTo(archive)) throw new IOException("내려받은 파일을 저장하지 못했어요.");
    }

    /**
     * The archive's files into dir, its top folder left out. Only regular files, folders, symbolic and hard links (hard
     * links as copies: Android does not let an app make them); every path must stay inside dir, and nothing is written
     * through a link. Sizes and the number of entries are capped.
     */
    private static void unpack(File archive, File dir, Runnable changed) throws Exception {
        String base = dir.getCanonicalPath() + File.separator; long written = 0, shown = 0; int entries = 0;
        List<String[]> hardLinks = new ArrayList<>();
        try (InputStream raw = new BufferedInputStream(new org.tukaani.xz.XZInputStream(new BufferedInputStream(Files.newInputStream(archive.toPath()), 1 << 16)), 1 << 16)) {
            Tar tar = new Tar(raw);
            for (Tar.Entry e; (e = tar.next()) != null; ) {
                if (cancelled) throw new IOException("취소");
                if (++entries > 100_000) throw new IOException("압축 파일의 항목이 너무 많아요.");
                String name = e.name.startsWith("./") ? e.name.substring(2) : e.name;
                if (!name.startsWith(TOP)) continue;
                name = name.substring(TOP.length()); while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                if (name.isEmpty()) continue;
                if (name.startsWith("/") || ("/" + name + "/").contains("/../")) throw new IOException("압축 파일에 잘못된 경로가 있어요: " + name);
                File target = new File(dir, name);
                File parent = target.getParentFile(); parent.mkdirs();
                // Never through a link: the folder an entry goes in must be inside dir once links are followed.
                if (!(parent.getCanonicalPath() + File.separator).startsWith(base)) throw new IOException("압축 파일이 링크를 거쳐 밖에 쓰려고 해요: " + name);
                switch (e.type) {
                    case '5' -> { target.mkdirs(); chmod(target, e.mode | 0700); }
                    case '2' -> { Files.deleteIfExists(target.toPath()); Os.symlink(e.link, target.getPath()); }
                    case '1' -> hardLinks.add(new String[]{name, e.link.startsWith("./") ? e.link.substring(2) : e.link});
                    case '0', 0 -> {
                        written += e.size; if (written > MOST_UNPACKED) throw new IOException("압축을 푼 크기가 상한을 넘어요.");
                        Files.deleteIfExists(target.toPath());
                        try (OutputStream out = new FileOutputStream(target)) { tar.copy(out); }
                        chmod(target, e.mode | 0600);
                        done = written; if (written - shown > 20_000_000) { shown = written; changed.run(); }
                    }
                    default -> { /* devices, FIFOs: the real /dev is bound in */ }
                }
            }
        }
        for (String[] link : hardLinks) {
            String from = link[1].startsWith(TOP) ? link[1].substring(TOP.length()) : link[1];
            File source = new File(dir, from), target = new File(dir, link[0]);
            if (!(source.getCanonicalPath()).startsWith(base) || !source.isFile()) continue;
            Files.deleteIfExists(target.toPath()); Files.copy(source.toPath(), target.toPath());
            chmod(target, 0755);
        }
    }
    private static void chmod(File f, int mode) { try { Os.chmod(f.getPath(), mode & 07777); } catch (Exception ignored) {} }

    /** What PRoot on Android needs in the system: a resolver, hosts, /tmp, and stand-ins for /proc files apps may not read. */
    private static void prepare(File root) throws IOException {
        write(new File(root, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        write(new File(root, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");
        File tmp = new File(root, "tmp"); tmp.mkdirs(); chmod(tmp, 01777);
        File fakes = new File(root, ".gyeot/proc"); fakes.mkdirs();
        write(new File(fakes, "loadavg"), "0.12 0.07 0.02 2/165 765\n");
        write(new File(fakes, "stat"), "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\nctxt 140223\nbtime 1680020856\nprocesses 772\nprocs_running 2\nprocs_blocked 0\n");
        write(new File(fakes, "uptime"), "124.08 932.80\n");
        write(new File(fakes, "version"), "Linux version 6.1.0 (gyeot@localhost) #1 SMP PREEMPT\n");
        write(new File(fakes, "vmstat"), "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n");
    }
    private static void write(File f, String text) throws IOException { f.getParentFile().mkdirs(); Files.deleteIfExists(f.toPath()); Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8)); }

    /** One command's outcome: its exit code and what it printed (both streams, cut to a megabyte). */
    record Result(int code, String output) {}
    /** Runs a shell command in the installed system (bash -lc), as Linux's root, in /root. Blocking. */
    static Result run(Context c, String command, long timeoutMs) throws Exception { return run(c, command, timeoutMs, null); }
    /** As above, with environment variables of its own (an installed CLI's home and settings). */
    static Result run(Context c, String command, long timeoutMs, java.util.Map<String, String> env) throws Exception { return run(c, command, timeoutMs, env, null); }
    /** As above; cancel is handed a stop for the running command (the chat's 멈추기). */
    static Result run(Context c, String command, long timeoutMs, java.util.Map<String, String> env, java.util.concurrent.atomic.AtomicReference<Runnable> cancel) throws Exception {
        if (!installed(c)) throw new IllegalStateException("Linux 환경이 설치되지 않았어요.");
        return run(c, rootfs(c), command, timeoutMs, env, cancel);
    }
    static Process start(Context c, File root, String command, java.util.Map<String, String> extraEnv) throws IOException {
        File lib = nativeDir(c), tmp = new File(home(c), "tmp"); tmp.mkdirs();
        List<String> args = new ArrayList<>(List.of(new File(lib, "libproot.so").getPath(), "--kill-on-exit", "--link2symlink", "-0", "-r", root.getPath(),
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/dev/urandom:/dev/random", "-b", new File(root, "tmp").getPath() + ":/dev/shm"));
        // /proc files Android keeps from apps: the system reads stand-ins instead.
        for (String name : new String[]{"loadavg", "stat", "uptime", "version", "vmstat"})
            if (!new File("/proc/" + name).canRead()) { args.add("-b"); args.add(new File(root, ".gyeot/proc/" + name).getPath() + ":/proc/" + name); }
        args.addAll(List.of("-w", "/root", "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LANG=C.UTF-8", "TERM=dumb", "TMPDIR=/tmp",
            "PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin"));
        if (extraEnv != null) for (var e : extraEnv.entrySet()) args.add(e.getKey() + "=" + e.getValue());
        args.addAll(List.of("/bin/bash", "-lc", command));
        ProcessBuilder builder = new ProcessBuilder(args).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PROOT_LOADER", new File(lib, "libproot_loader.so").getPath());
        builder.environment().put("PROOT_TMP_DIR", tmp.getPath());
        builder.environment().put("LD_LIBRARY_PATH", lib.getPath());
        builder.environment().put("HOME", home(c).getPath());
        return builder.start();
    }
    private static Result run(Context c, File root, String command, long timeoutMs, java.util.Map<String, String> env, java.util.concurrent.atomic.AtomicReference<Runnable> cancel) throws Exception {
        Process p = start(c, root, command, env);
        if (cancel != null) cancel.set(p::destroyForcibly);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            byte[] b = new byte[8192]; int n;
            try (InputStream in = p.getInputStream()) { while ((n = in.read(b)) > 0) synchronized (out) { if (out.size() < 1 << 20) out.write(b, 0, n); } } catch (IOException ignored) {}
        }, "linux-output");
        reader.start();
        boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) { p.destroyForcibly(); reader.join(2000); return new Result(-1, out.toString(StandardCharsets.UTF_8.name()) + "\n(시간 초과로 멈췄어요)"); }
        reader.join(5000);
        synchronized (out) { return new Result(p.exitValue(), out.toString(StandardCharsets.UTF_8.name()).strip()); }
    }

    static void delete(File f) {
        if (f == null || !f.exists() && !Files.isSymbolicLink(f.toPath())) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) { File[] list = f.listFiles(); if (list != null) for (File each : list) delete(each); }
        f.delete();
    }
    private static String sha256(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(f.toPath()))) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) d.update(b, 0, n); }
        return hex(d.digest());
    }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x)); return s.toString(); }
    static String mb(long bytes) { return String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0); }
    private static String clip(String s) { String t = s == null ? "" : s.strip(); return t.length() > 400 ? t.substring(t.length() - 400) : t; }

    /** A tar reader: ustar, GNU long names (L, K) and PAX headers (x: path, linkpath, size). */
    static final class Tar {
        record Entry(String name, int type, int mode, long size, String link) {}
        private final InputStream in; private long left, padding;
        Tar(InputStream in) { this.in = in; }
        Entry next() throws IOException {
            skip(left + padding); left = 0; padding = 0;
            String longName = null, longLink = null; Long paxSize = null;
            while (true) {
                byte[] h = new byte[512]; if (!fill(h)) return null;
                boolean empty = true; for (byte b : h) if (b != 0) { empty = false; break; }
                if (empty) return null;
                int type = h[156];
                long size = number(h, 124, 12);
                if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                    byte[] body = new byte[(int) Math.min(size, 1 << 20)]; fill(body); skip(size - body.length + pad(size));
                    if (type == 'L') longName = cstring(body);
                    else if (type == 'K') longLink = cstring(body);
                    else if (type == 'x') {
                        for (String line : new String(body, StandardCharsets.UTF_8).split("\n")) {
                            int sp = line.indexOf(' '), eq = line.indexOf('='); if (sp < 0 || eq < sp) continue;
                            String key = line.substring(sp + 1, eq), value = line.substring(eq + 1);
                            if (key.equals("path")) longName = value; else if (key.equals("linkpath")) longLink = value; else if (key.equals("size")) paxSize = Long.parseLong(value.strip());
                        }
                    }
                    continue;
                }
                String name = longName != null ? longName : join(cstring(h, 345, 155), cstring(h, 0, 100));
                String link = longLink != null ? longLink : cstring(h, 157, 100);
                if (paxSize != null) size = paxSize;
                int mode = (int) number(h, 100, 8);
                left = (type == '0' || type == 0 || type == '7') ? size : (type == '5' || type == '2' || type == '1' ? 0 : size);
                padding = pad(left);
                return new Entry(name, type == '7' ? '0' : type, mode, size, link);
            }
        }
        void copy(OutputStream out) throws IOException {
            byte[] b = new byte[1 << 16];
            while (left > 0) { int n = in.read(b, 0, (int) Math.min(b.length, left)); if (n < 0) throw new IOException("압축 파일이 중간에 끊겼어요."); out.write(b, 0, n); left -= n; }
        }
        private static long pad(long size) { return (512 - size % 512) % 512; }
        private boolean fill(byte[] b) throws IOException { int at = 0; while (at < b.length) { int n = in.read(b, at, b.length - at); if (n < 0) return false; at += n; } return true; }
        private void skip(long n) throws IOException { byte[] b = new byte[8192]; while (n > 0) { int r = in.read(b, 0, (int) Math.min(b.length, n)); if (r < 0) return; n -= r; } }
        private static String join(String prefix, String name) { return prefix.isEmpty() ? name : prefix + "/" + name; }
        private static String cstring(byte[] b) { return cstring(b, 0, b.length); }
        private static String cstring(byte[] b, int at, int length) { int end = at; while (end < at + length && b[end] != 0) end++; return new String(b, at, end - at, StandardCharsets.UTF_8); }
        private static long number(byte[] b, int at, int length) {
            if ((b[at] & 0x80) != 0) { long v = b[at] & 0x7f; for (int i = 1; i < length; i++) v = (v << 8) | (b[at + i] & 0xff); return v; } // base-256
            String s = cstring(b, at, length).strip(); return s.isEmpty() ? 0 : Long.parseLong(s, 8);
        }
    }
}
