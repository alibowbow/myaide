package dev.jun.gyeot;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 연구실's 계산 환경: Python with numpy, scipy, sympy, mpmath, matplotlib and pandas in the app's Linux (LinuxRuntime), put
 * there once with pacman, and one experiment run in its own folder (/root/lab/<project>/e<n>) with its figures in out/.
 */
final class LabPython {
    private LabPython() {}
    static final String PACKAGES = "python python-numpy python-scipy python-sympy python-mpmath python-matplotlib python-pandas";

    /** Linux is there and Python has numpy and matplotlib. */
    static boolean ready(Context c) {
        if (!LinuxRuntime.installed(c)) return false;
        File root = LinuxRuntime.rootfs(c), lib = new File(root, "usr/lib");
        if (!new File(root, "usr/bin/python3").exists()) return false;
        File[] pythons = lib.listFiles((dir, name) -> name.startsWith("python3."));
        if (pythons == null) return false;
        for (File p : pythons) if (new File(p, "site-packages/numpy").isDirectory() && new File(p, "site-packages/matplotlib").isDirectory()) return true;
        return false;
    }
    /** pacman under PRoot cannot switch to its download user: that line is turned off before it runs. */
    static void prepare(Context c) {
        try {
            File conf = new File(LinuxRuntime.rootfs(c), "etc/pacman.conf");
            if (!conf.isFile()) return;
            String text = new String(Files.readAllBytes(conf.toPath()), StandardCharsets.UTF_8);
            if (text.contains("\nDownloadUser")) Files.write(conf.toPath(), text.replace("\nDownloadUser", "\n#DownloadUser").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }
    /**
     * What 계산 환경 준비 runs in the terminal: the keyring when it has none yet; every mirror of the list turned on (pacman
     * goes on to the next one when one stalls; the nearest-by-location one stays first); a lock left by a stopped run
     * removed (only this terminal runs pacman); then the packages, tried up to three times.
     */
    static String installCommand() {
        return "set -e; if [ ! -s /etc/pacman.d/gnupg/pubring.gpg ] && [ ! -s /etc/pacman.d/gnupg/pubring.kbx ]; then pacman-key --init && pacman-key --populate archlinuxarm; fi; "
            + "if [ -f /etc/pacman.d/mirrorlist ] && ! grep -q '^# gyeot-mirrors' /etc/pacman.d/mirrorlist; then sed -i 's/^#[[:space:]]*Server[[:space:]]*=/Server =/' /etc/pacman.d/mirrorlist && echo '# gyeot-mirrors' >> /etc/pacman.d/mirrorlist; fi; "
            + "rm -f /var/lib/pacman/db.lck; "
            + "ok=; for try in 1 2 3; do if pacman -Sy --noconfirm --needed " + PACKAGES + "; then ok=1; break; fi; "
            + "echo; echo \"서버가 느리거나 끊겨서 다시 시도해요 ($try/3)\"; sleep 5; done; [ -n \"$ok\" ]; "
            + "python3 -c \"import numpy, scipy, sympy, matplotlib, pandas; print('계산 환경 준비 완료 · numpy', numpy.__version__)\"";
    }

    record Run(int exit, String output, long millis, java.util.List<File> figures) {}
    /**
     * Runs one experiment's code in its own folder and returns its exit code, its output (the last 12,000 characters) and
     * the figures it saved in out/, copied to keep (into copies, named e<n>-<name>). At most 150 seconds. Blocking.
     */
    static Run run(Context c, String project, int n, String code, File copies, AtomicReference<Runnable> cancel) throws Exception {
        String folder = "root/lab/" + project.replaceAll("[^A-Za-z0-9_-]", "_") + "/e" + n;
        File dir = new File(LinuxRuntime.rootfs(c), folder), out = new File(dir, "out");
        LinuxRuntime.delete(out); out.mkdirs();
        Files.write(new File(dir, "main.py").toPath(), code.getBytes(StandardCharsets.UTF_8));
        long started = System.currentTimeMillis();
        LinuxRuntime.Result r = LinuxRuntime.run(c, "cd /" + folder + " && timeout 150 python3 -u main.py 2>&1", 200_000,
            Map.of("MPLBACKEND", "Agg", "PYTHONUNBUFFERED", "1", "HOME", "/root", "MPLCONFIGDIR", "/tmp/mpl"), cancel);
        long took = System.currentTimeMillis() - started;
        java.util.List<File> figures = new java.util.ArrayList<>();
        File[] made = out.listFiles((d, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".png"));
        if (made != null) {
            java.util.Arrays.sort(made);
            copies.mkdirs();
            for (File f : made) {
                if (figures.size() >= 6 || f.length() > 8_000_000) continue;
                File kept = new File(copies, "e" + n + "-" + f.getName().replaceAll("[^A-Za-z0-9._-]", "_"));
                Files.copy(f.toPath(), kept.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                figures.add(kept);
            }
        }
        String output = LinuxTerminal.plain(r.output()); int exit = r.code();
        if (output.length() > 12_000) output = "…\n" + output.substring(output.length() - 12_000);
        if (exit == 124) output = (output + "\n(150초가 넘어 멈췄어요)").strip();
        return new Run(exit, output, took, figures);
    }
}
