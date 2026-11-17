package dev.jun.gyeot;

import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import dev.jun.gyeot.core.PhoneRisk;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 휴대폰 검사: gathers what Android will tell this app about the phone's settings, its installed
 * apps and the installer files in the download folder, and hands it to the rules (core/PhoneRisk). Everything is read on
 * the phone and stays there; no file is opened, only names and sizes are listed, and nothing is changed or deleted. It
 * takes a second or two and runs off the main thread.
 */
final class PhoneCheck {
    private PhoneCheck() {}
    /** When it ran, and what it found, most serious first. */
    record Result(long at, List<PhoneRisk.Finding> findings) {}
    private static final String PREFS = "phone-check";
    /** How far into the download folder it looks: enough for a folder of folders, never the whole phone. */
    static final int DEPTH = 3, ENTRIES = 5000;

    static Result run(Context context, BooleanSupplier cancelled) {
        Context app = context.getApplicationContext();
        List<PhoneRisk.Finding> findings = new ArrayList<>(PhoneRisk.device(device(app)));
        if (!cancelled.getAsBoolean()) { boolean all = seesEveryApp(app); findings.addAll(PhoneRisk.apps(apps(app, all), all)); }
        if (!cancelled.getAsBoolean()) {
            // Asking can itself fail on a phone without the usual shared storage: then the folder was not read, and the result says so.
            boolean allowed; try { allowed = Environment.isExternalStorageManager(); } catch (RuntimeException unknown) { allowed = false; }
            findings.addAll(PhoneRisk.installers(allowed ? installers(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), cancelled) : List.of(), allowed));
        }
        Result result = new Result(System.currentTimeMillis(), PhoneRisk.sorted(findings));
        if (!cancelled.getAsBoolean()) remember(app, result);
        return result;
    }

    static PhoneRisk.Device device(Context app) {
        KeyguardManager keyguard = app.getSystemService(KeyguardManager.class);
        boolean adb = Settings.Global.getInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        boolean developer = Settings.Global.getInt(app.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        int encryption = -1;
        try {
            DevicePolicyManager policy = app.getSystemService(DevicePolicyManager.class);
            int status = policy == null ? DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED : policy.getStorageEncryptionStatus();
            encryption = status == DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE ? 0 : status == DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED ? -1 : 1;
        } catch (RuntimeException unknown) { /* -1 */ }
        return new PhoneRisk.Device(keyguard != null && keyguard.isDeviceSecure(), Build.VERSION.SECURITY_PATCH == null ? "" : Build.VERSION.SECURITY_PATCH,
            System.currentTimeMillis(), adb, developer, rooted(), encryption, Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
    }
    /** The usual places of a su binary or a root manager, and a system built with test keys. */
    static boolean rooted() {
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) return true;
        for (String path : new String[] {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sbin/su", "/vendor/bin/su", "/su/bin/su", "/system/app/Superuser.apk", "/data/adb/magisk"})
            try { if (new File(path).exists()) return true; } catch (RuntimeException denied) { /* not there for this app */ }
        return false;
    }
    /**
     * Whether Android shows this app every installed app. It does not: the manifest asks only for apps with a launcher icon
     * (its queries, kept minimal; QUERY_ALL_PACKAGES is not used), so an app with no
     * icon cannot be seen and the result says that instead of calling such apps absent. True only if that permission is
     * ever granted.
     */
    static boolean seesEveryApp(Context app) { return app.checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") == PackageManager.PERMISSION_GRANTED; }

    static List<PhoneRisk.App> apps(Context app, boolean all) {
        PackageManager packages = app.getPackageManager();
        String accessibility = String.valueOf(Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        String listeners = String.valueOf(Settings.Secure.getString(app.getContentResolver(), "enabled_notification_listeners"));
        Set<String> admins = new HashSet<>();
        try {
            DevicePolicyManager policy = app.getSystemService(DevicePolicyManager.class);
            List<ComponentName> active = policy == null ? null : policy.getActiveAdmins();
            if (active != null) for (ComponentName name : active) admins.add(name.getPackageName());
        } catch (RuntimeException unknown) { /* none known */ }
        List<PhoneRisk.App> out = new ArrayList<>();
        List<PackageInfo> installed;
        try { installed = packages.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS)); } catch (RuntimeException unavailable) { return out; }
        for (PackageInfo info : installed) {
            ApplicationInfo about = info.applicationInfo; if (about == null) continue;
            String id = info.packageName, installer = "";
            try { String from = packages.getInstallSourceInfo(id).getInstallingPackageName(); installer = from == null ? "" : from; } catch (PackageManager.NameNotFoundException | RuntimeException unknown) { /* "" */ }
            Set<String> granted = new HashSet<>();
            if (info.requestedPermissions != null) for (int i = 0; i < info.requestedPermissions.length; i++) {
                boolean has = info.requestedPermissionsFlags != null && i < info.requestedPermissionsFlags.length && (info.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                if (has) { String name = info.requestedPermissions[i]; granted.add(name.substring(name.lastIndexOf('.') + 1)); }
            }
            CharSequence label = null; try { label = packages.getApplicationLabel(about); } catch (RuntimeException unnamed) { /* the id */ }
            out.add(new PhoneRisk.App(id, label == null || label.toString().isBlank() ? id : label.toString(), (about.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                installer, packages.getLaunchIntentForPackage(id) != null, enabled(accessibility, id), admins.contains(id), enabled(listeners, id), granted, id.equals(app.getPackageName())));
        }
        return out;
    }
    /** Whether a colon-separated list of components (package/class) names this package. */
    static boolean enabled(String components, String id) {
        for (String component : components.split(":")) if (component.startsWith(id + "/")) return true;
        return false;
    }

    /** Installer files by name in a folder and its folders, to DEPTH levels and ENTRIES entries; nothing is opened. */
    static List<PhoneRisk.Installer> installers(File folder, BooleanSupplier cancelled) {
        List<PhoneRisk.Installer> out = new ArrayList<>();
        ArrayDeque<File> folders = new ArrayDeque<>(); ArrayDeque<Integer> levels = new ArrayDeque<>();
        if (folder != null) { folders.add(folder); levels.add(0); }
        int seen = 0;
        while (!folders.isEmpty() && seen < ENTRIES && !cancelled.getAsBoolean()) {
            File dir = folders.remove(); int level = levels.remove();
            File[] entries; try { entries = dir.listFiles(); } catch (RuntimeException denied) { entries = null; }
            if (entries == null) continue;
            for (File entry : entries) {
                if (++seen > ENTRIES) break;
                if (entry.isDirectory()) { if (level + 1 < DEPTH) { folders.add(entry); levels.add(level + 1); } }
                else if (PhoneRisk.installer(entry.getName())) out.add(new PhoneRisk.Installer(entry.getName(), entry.length(), entry.lastModified()));
            }
        }
        return out;
    }

    /** The last result's time and one-line summary, for the menu; nothing else is kept. */
    private static void remember(Context app, Result result) {
        app.getSharedPreferences(PREFS, 0).edit().putLong("at", result.at()).putString("summary", PhoneRisk.summary(result.findings())).apply();
    }
    static String last(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, 0); long at = prefs.getLong("at", 0);
        if (at <= 0) return "";
        long days = Math.max(0, (System.currentTimeMillis() - at) / 86_400_000L);
        return (days == 0 ? "오늘 검사" : days + "일 전 검사") + " · " + prefs.getString("summary", "");
    }
}
