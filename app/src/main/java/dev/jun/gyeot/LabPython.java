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
        return pacman(PACKAGES) + "python3 -c \"import numpy, scipy, sympy, matplotlib, pandas; print('계산 환경 준비 완료 · numpy', numpy.__version__)\"";
    }
    /**
     * The pacman part every tool set shares (계산 환경, 유튜브 오디오 도구): keyring, mirrors, a stale lock, fresh signing keys,
     * then the whole system with the packages (-Syu) up to three times, each failure named from pacman's error lines.
     */
    static String pacman(String packages) {
        return "set -e; if [ ! -s /etc/pacman.d/gnupg/pubring.gpg ] && [ ! -s /etc/pacman.d/gnupg/pubring.kbx ]; then pacman-key --init && pacman-key --populate archlinuxarm; fi; "
            + "if [ -f /etc/pacman.d/mirrorlist ] && ! grep -q '^# gyeot-mirrors' /etc/pacman.d/mirrorlist; then sed -i 's/^#[[:space:]]*Server[[:space:]]*=/Server =/' /etc/pacman.d/mirrorlist && echo '# gyeot-mirrors' >> /etc/pacman.d/mirrorlist; fi; "
            + "rm -f /var/lib/pacman/db.lck; "
            // The signing keys first: the system is as old as its download, and newer packages are signed by keys it does not know.
            + "echo '서명 키를 새로 받는 중…'; { pacman -Sy --noconfirm --needed archlinuxarm-keyring archlinux-keyring >/dev/null 2>&1 && pacman-key --populate archlinuxarm archlinux >/dev/null 2>&1; } || true; "
            // The whole system with the packages (-Syu): new packages over old libraries (a partial upgrade) is what fails or breaks.
            // A failure says why, from pacman's own error lines, and the next try answers that reason.
            + "ok=; extra=(); for try in 1 2 3; do "
            + "set +e; pacman -Syu --noconfirm --needed \"${extra[@]}\" " + packages + " 2>&1 | tee /tmp/gyeot-pacman.log; rc=${PIPESTATUS[0]}; set -e; "
            + "if [ \"$rc\" = 0 ]; then ok=1; break; fi; echo; "
            + "if grep -qiE 'conflicting files|exists in filesystem' /tmp/gyeot-pacman.log; then echo \"이미 있는 파일과 겹쳐요 · 덮어쓰고 다시 해요 ($try/3)\"; extra=(--overwrite '*'); "
            + "elif grep -qiE 'signature|keyring|PGP|unknown trust' /tmp/gyeot-pacman.log; then echo \"패키지 서명을 확인하지 못했어요 · 서명 키를 새로 받고 다시 해요 ($try/3)\"; { pacman-key --init && pacman-key --populate archlinuxarm archlinux; } >/dev/null 2>&1 || true; "
            + "elif grep -qiE 'free disk space|No space left' /tmp/gyeot-pacman.log; then echo '휴대폰 저장 공간이 부족해요. 공간을 비운 뒤 다시 해 주세요.'; break; "
            + "elif grep -qiE 'failed retrieving|too slow|Could not resolve|timed out|Connection' /tmp/gyeot-pacman.log; then echo \"서버에서 받지 못했어요 · 다른 서버로 다시 해요 ($try/3)\"; "
            + "else echo \"설치하지 못했어요 · 다시 해요 ($try/3)\"; fi; "
            + "why=$(grep -iE '^(error|warning: .*(conflict|signature))' /tmp/gyeot-pacman.log | tail -3); [ -n \"$why\" ] && echo \"pacman이 말한 이유: $why\"; sleep 3; done; [ -n \"$ok\" ]; "
            // The downloaded package files are not needed once installed: they go, so the space they took comes back.
            + "rm -f /var/cache/pacman/pkg/*.pkg.tar.* 2>/dev/null || true; echo \"리눅스가 쓰는 공간: $(du -sh / --exclude=/proc --exclude=/sys --exclude=/dev 2>/dev/null | cut -f1)\"; ";
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
