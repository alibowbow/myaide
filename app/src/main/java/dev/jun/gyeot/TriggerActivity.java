package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import dev.jun.gyeot.core.Triggers;
import java.util.List;
import org.json.JSONObject;

/**
 * 조건 알림's one tap, see-through over whatever shows:
 *  - fire: the act a trigger left waiting (a draft the user sends, an app Android would not open from the background), done
 *    now that the user tapped (BuddyActs, the app in front), then gone.
 *  - allow: Android's own permission screens, after one plain line why: 위치 (정확한 위치, then ‘항상 허용’) for a place,
 *    근처 기기 for a device; then what they make possible is listened for at once (TriggerWatch.sync).
 *  - pick: the phone's paired devices, to say once which is the car (kept as the car for 호출어 too) or the device meant.
 */
public final class TriggerActivity extends Activity {
    static final String FIRE = "fire", ALLOW = "allow", PICK = "pick";
    private static final int FINE = 71, BACKGROUND = 72, NEARBY = 73;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String picking = "";

    static Intent fire(Context c, String pending) { return new Intent(c, TriggerActivity.class).putExtra(FIRE, pending).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION); }
    static Intent allow(Context c, String what) { return new Intent(c, TriggerActivity.class).putExtra(ALLOW, what).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION); }
    static Intent pick(Context c, String trigger) { return new Intent(c, TriggerActivity.class).putExtra(PICK, trigger).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent i = getIntent();
        if (i.hasExtra(FIRE)) { fire(i.getStringExtra(FIRE)); return; }
        if ("location".equals(i.getStringExtra(ALLOW))) { location(); return; }
        if ("bluetooth".equals(i.getStringExtra(ALLOW))) { picking = i.getStringExtra(PICK) == null ? "" : i.getStringExtra(PICK); nearby(); return; }
        if (i.hasExtra(PICK)) { picking = i.getStringExtra(PICK); if (TriggerWatch.bluetoothAllowed(this)) pick(); else nearby(); return; }
        finish();
    }
    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }

    private void fire(String id) {
        Context app = getApplicationContext();
        new Thread(() -> {
            JSONObject waiting = TriggerStore.take(app, id);
            if (waiting == null) { main.post(() -> { Toast.makeText(this, "이미 했어요", Toast.LENGTH_SHORT).show(); finish(); }); return; }
            JSONObject act = waiting.optJSONObject("act");
            BuddyVoice.Outcome o = act == null ? null : TriggerFire.doer.act(this, act, waiting.optString("said"));
            main.post(() -> {
                if (o != null && o.missed() && !o.say().isBlank()) Toast.makeText(this, o.say(), Toast.LENGTH_LONG).show();
                finish();
            });
        }, "gyeot-trigger-tap").start();
    }

    // ── 위치: 정확한 위치, then ‘항상 허용’ (Android's own screens) ──
    private void location() {
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) { done("위치는 이미 허용돼 있어요"); return; }
        line(fine ? "도착·출발을 알아채려면 위치를 ‘항상 허용’으로 바꿔 주세요. 다음 화면에서 고를 수 있어요."
                  : "도착·출발을 알아채려면 위치가 필요해요. ‘정확한 위치’로 허용한 뒤, 다음 화면에서 ‘항상 허용’을 골라 주세요.",
            () -> { if (fine) requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, BACKGROUND);
                    else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, FINE); });
    }
    private void nearby() {
        if (TriggerWatch.bluetoothAllowed(this)) { if (picking.isEmpty()) done("근처 기기는 이미 허용돼 있어요"); else pick(); return; }
        line("차에 탄 걸 알아채려면 ‘근처 기기’ 권한이 필요해요. 연결된 블루투스 기기만 봐요.", () -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, NEARBY));
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        // Refused for good before ("다시 묻지 않음"): Android shows no dialog any more, so the app's own permission page instead.
        if (!granted && results.length > 0 && permissions.length > 0 && !shouldShowRequestPermissionRationale(permissions[0])) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (RuntimeException none) { /* said below */ }
            done(code == NEARBY ? "권한 → 근처 기기를 허용해 주세요" : "권한 → 위치에서 ‘항상 허용’을 골라 주세요");
            return;
        }
        switch (code) {
            case FINE -> { if (granted) requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, BACKGROUND); else done("위치를 허용하지 않아서 도착·출발은 알 수 없어요"); }
            case BACKGROUND -> done(granted ? "이제 도착·출발을 알아챌게요" : "‘항상 허용’이 아니면 앱을 닫았을 때 도착·출발을 몰라요");
            case NEARBY -> { if (!granted) done("근처 기기를 허용하지 않아서 차에 탄 걸 알 수 없어요"); else if (!picking.isEmpty()) pick(); else done("이제 블루투스 연결을 알아챌게요"); }
            default -> finish();
        }
    }
    private void line(String why, Runnable then) {
        try {
            new AlertDialog.Builder(this).setMessage(why).setPositiveButton("허용하러 가기", (d, w) -> then.run())
                .setNegativeButton("나중에", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
        } catch (RuntimeException closed) { then.run(); }
    }
    private void done(String said) {
        Toast.makeText(this, said, Toast.LENGTH_SHORT).show();
        TriggerWatch.start(this);
        finish();
    }

    // ── which device is the car (or the one meant) ──
    private void pick() {
        Context app = getApplicationContext();
        new Thread(() -> {
            Triggers.Trigger t = Triggers.find(TriggerStore.list(app), picking);
            List<String[]> devices = TriggerWatch.bonded(app);
            main.post(() -> {
                if (isFinishing()) return;
                if (t == null) { done("그 조건 알림은 이제 없어요"); return; }
                if (devices.isEmpty()) { done("연결해 둔 블루투스 기기가 없어요. 먼저 폰 설정에서 연결해 주세요"); return; }
                String[] names = new String[devices.size()]; int current = -1;
                for (int k = 0; k < devices.size(); k++) { names[k] = devices.get(k)[0]; if (devices.get(k)[1].equalsIgnoreCase(t.when().id())) current = k; }
                Ui ui = new Ui(this);
                Ui.Sheet sheet = ui.choices(t.when().car() ? "어느 게 차인가요?" : "어느 기기인가요?", "폰에 연결해 둔 블루투스 기기예요", names, current, which -> chose(app, t, devices.get(which)));
                sheet.setOnDismissListener(d -> { if (!isFinishing()) finish(); });
                sheet.show();
            });
        }, "gyeot-trigger-pick").start();
    }
    private void chose(Context app, Triggers.Trigger t, String[] device) {
        boolean car = t.when().car();
        new Thread(() -> {
            // The car is one (호출어 uses the same): every car trigger not picked yet gets it.
            TriggerStore.change(app, all -> {
                List<Triggers.Trigger> out = new java.util.ArrayList<>();
                for (Triggers.Trigger x : all) out.add(x.id().equals(t.id()) || car && x.when().car() && x.when().id().isEmpty() ? new Triggers.Trigger(x.id(), x.when().device(device[1]), x.then(), x.mode(), x.on(), x.done(), x.said(), x.made(), x.fired(), x.times(), x.armed(), x.seen()) : x);
                return out;
            });
            if (car) {
                AppStore store = ((GyeotApp) app).store();
                store.settings.edit().putString(WakeWordFiles.CAR, device[1]).putString(WakeWordFiles.CAR_NAME, device[0]).apply();
                if (store.settings.getBoolean(WakeWordFiles.ENABLED, false)) main.post(() -> WakeWordService.refresh(app));
            }
            TriggerWatch.sync(app);
            main.post(() -> { Toast.makeText(this, "‘" + device[0] + "’" + (car ? "를 차로 기억할게요" : "로 할게요"), Toast.LENGTH_SHORT).show(); finish(); });
        }, "gyeot-trigger-chose").start();
    }
}
