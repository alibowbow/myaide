package dev.jun.gyeot;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 조건 알림's broadcast doors, each switched on by TriggerWatch.sync only while it is needed, each handing its work to
 * TriggerWatch's own thread (goAsync: the app may have been started just for it).
 *  - Boot: after the phone restarts or the app is updated, the places are watched again and the receivers set as needed
 *    (Android drops proximity alerts on a restart; the jobs are persisted). On while any trigger is on.
 *  - Bluetooth: a paired device connecting or going (ACL, a protected broadcast only Android sends). On while a device
 *    trigger is on or 하루 기록 is (the car's lines, DayLogs.bluetooth), and 근처 기기 is allowed.
 *  - Place: Android's proximity alert for one place trigger. Not exported and with no filter: only this app's own
 *    PendingIntent (from LocationManager) reaches it.
 */
final class TriggerReceiver {
    private TriggerReceiver() {}
    private static void later(BroadcastReceiver r, Runnable work) {
        BroadcastReceiver.PendingResult pending = null;
        try { pending = r.goAsync(); } catch (RuntimeException notFromAndroid) { /* called directly (tests) */ }
        BroadcastReceiver.PendingResult done = pending;
        try { TriggerWatch.IO.execute(() -> { try { work.run(); } catch (RuntimeException failed) { /* one event lost, said in the log */ } finally { if (done != null) done.finish(); } }); }
        catch (RuntimeException stopped) { if (done != null) done.finish(); }
    }
    public static final class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String a = i == null ? "" : String.valueOf(i.getAction());
            if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)) return;
            if (SecurityWatch.paused(c)) return;
            later(this, () -> TriggerWatch.sync(c.getApplicationContext()));
        }
    }
    public static final class Bluetooth extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String a = i == null ? "" : String.valueOf(i.getAction());
            if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(a) && !BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) return;
            if (SecurityWatch.paused(c)) return;
            later(this, () -> TriggerWatch.bluetooth(c.getApplicationContext(), i));
        }
    }
    public static final class Place extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if (i == null || !TriggerWatch.PLACE_ACTION.equals(i.getAction()) || SecurityWatch.paused(c)) return;
            later(this, () -> TriggerWatch.place(c.getApplicationContext(), i));
        }
    }
}
