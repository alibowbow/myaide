package dev.jun.gyeot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.NaverPlaces;
import java.util.List;

/**
 * 설정 → 커넥터 → 네이버 길안내: the NAVER API HUB Client ID and Client Secret that let the voice
 * assistant find a place and start Naver Map's navigation. A pasted key is tried with one 지역 검색 first and saved only when
 * that works; it is kept encrypted (SecretStore.NAVER_PLACES) and never shown again.
 */
final class NaverNavigationUi {
    /** The guide this app follows for the key (NAVER API HUB's own documentation). */
    static final String GUIDE = "https://api.ncloud-docs.com/docs/naver-api-hub-overview";
    /** A place the check looks up: any working key finds it. */
    static final String CHECK = "시청";
    private final Activity activity; private final Ui ui; private final AppStore store; private final Runnable refresh;
    private final Handler main = new Handler(Looper.getMainLooper());
    NaverNavigationUi(Activity activity, Ui ui, AppStore store, Runnable refresh) { this.activity = activity; this.ui = ui; this.store = store; this.refresh = refresh; }

    static String summary(AppStore store) { return NaverPlacesClient.connected(store) ? "연결됨 · 말로 바로 길안내" : "연결 안 됨"; }

    /** The connectors page's row: enter a key, or the sheet that checks, replaces or removes the saved one. */
    View row() {
        return ui.listRow(R.drawable.ic_globe, Ui.GREEN, "네이버 길안내", summary(store), ui.chevron(), () -> { if (NaverPlacesClient.connected(store)) manage(); else enter(); });
    }
    /** The saved key's sheet: what it does, 연결 확인, 키 바꾸기, and 연결 해제 behind a confirm. */
    private void manage() {
        Ui.Sheet sheet = ui.selectionSheet("네이버 길안내", "연결됨 · 음성 비서에서 써요");
        TextView about = ui.label("음성 비서에게 “시청으로 길안내해 줘”, “터미널까지 대중교통으로”처럼 말하면 네이버 지도 길안내가 바로 시작돼요. 같은 이름의 장소가 여럿이면 되물어요.", 13, Ui.TEXT);
        about.setPadding(0, 0, 0, ui.dp(12)); sheet.body.addView(about);
        LinearLayout keys = ui.group();
        ui.addRow(keys, ui.listRow(0, 0, "연결 확인", "‘" + CHECK + "’으로 한 번 찾아봐요", null, () -> { sheet.dismiss(); check(null); }), false);
        ui.addRow(keys, ui.listRow(0, 0, "키 바꾸기", "새 키를 확인한 뒤 바꿔요", null, () -> { sheet.dismiss(); enter(); }), false);
        sheet.body.addView(keys);
        LinearLayout off = ui.group();
        ui.addRow(off, ui.dangerRow("연결 해제", "키가 없으면 지도 앱 검색만 열려요", () -> {
            sheet.dismiss();
            ui.confirm("이 기기에서 네이버 길안내 키를 지울까요? 말로 길안내를 부탁하면 지도 앱 검색만 열려요.", "해제", () -> {
                try { NaverPlacesClient.save(store, null); store.status("네이버 길안내 연결을 해제했어요"); refresh.run(); }
                catch (Exception e) { store.error("키를 지우지 못했어요."); }
            }).show();
        }), false);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(16); sheet.body.addView(off, gap);
        sheet.primary.setText("닫기"); sheet.show();
    }
    /** The two fields, as NAVER API HUB shows them under API 관리 → 인증 정보. */
    void enter() {
        LinearLayout form = ui.column(); form.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0);
        EditText id = secret("Client ID"), secret = secret("Client Secret");
        form.addView(id); form.addView(secret);
        form.addView(ui.label("NAVER API HUB에서 ‘검색 › 지역’을 고른 Application의 [인증 정보] 두 값을 붙여 넣어 주세요. 확인한 뒤 이 기기에 암호화해 보관해요.", 12, Ui.MUTED));
        new AlertDialog.Builder(activity).setTitle("네이버 길안내 키").setView(form).setNegativeButton("취소", null)
            .setNeutralButton("발급 방법", (d, w) -> {
                try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GUIDE))); } catch (RuntimeException none) { store.error("브라우저를 열지 못했어요."); }
            })
            .setPositiveButton("확인하고 저장", (d, w) -> {
                NaverPlacesClient.Key key = new NaverPlacesClient.Key(id.getText().toString().strip(), secret.getText().toString().strip());
                if (!NaverPlacesClient.valid(key.id()) || !NaverPlacesClient.valid(key.secret())) { store.error("Client ID와 Client Secret을 공백 없이 그대로 붙여 넣어 주세요."); return; }
                check(key);
            }).show();
    }
    private EditText secret(String hint) {
        EditText field = ui.input(hint, 1, 200);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setContentDescription(hint);
        return field;
    }
    /** Looks up CHECK with the key: a new key is saved only when that works; null checks the saved one. */
    void check(NaverPlacesClient.Key pasted) {
        store.status("네이버 지역 검색으로 키를 확인하는 중…");
        new Thread(() -> {
            try {
                NaverPlacesClient.Key key = pasted == null ? NaverPlacesClient.key(store) : pasted;
                if (key == null) throw new IllegalStateException("저장된 키를 읽지 못했어요. 키를 다시 넣어 주세요.");
                List<NaverPlaces.Place> found = NaverPlacesClient.search(key, CHECK, null);
                if (found.isEmpty()) throw new IllegalStateException("키는 받아들여졌지만 ‘" + CHECK + "’을 찾지 못했어요. Application에서 ‘검색 › 지역’을 골랐는지 확인해 주세요.");
                if (pasted != null) NaverPlacesClient.save(store, pasted);
                main.post(() -> { store.status("네이버 길안내가 연결됐어요 · " + (store.voiceActive ? "다음 길안내부터 바로 써요" : "“시청으로 길안내해 줘”처럼 말해 보세요")); refresh.run(); });
            } catch (Exception e) {
                String reason = e.getMessage() == null || e.getMessage().isBlank() ? "네이버 지역 검색에 연결하지 못했어요." : e.getMessage();
                main.post(() -> store.error(reason));
            }
        }, "gyeot-naver-check").start();
    }
}
