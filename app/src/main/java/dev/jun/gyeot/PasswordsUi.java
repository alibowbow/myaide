package dev.jun.gyeot;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.os.PersistableBundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import static dev.jun.gyeot.Ui.*;

/**
 * 비밀번호: the saved logins in one list (site, account, when last used), searched, deleted with a swipe; one opened shows
 * its account, its password behind the phone's own lock (fingerprint, face or PIN), what it was used for, and copies either.
 * The screen is kept out of screenshots and the recent-apps preview (FLAG_SECURE).
 */
final class PasswordsUi {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN);
    private final Activity activity;
    private final Ui ui;
    private final LoginVault vault;
    private final Consumer<String> toast;

    PasswordsUi(Activity activity, Ui ui, LoginVault vault, Consumer<String> toast) { this.activity = activity; this.ui = ui; this.vault = vault; this.toast = toast; }

    static String when(long at) { return at <= 0 ? "" : WHEN.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())); }

    Ui.Sheet show() {
        Ui.Sheet sheet = ui.selectionSheet("비밀번호", "기기에 암호화해 저장해요 · AI에게는 보내지 않아요");
        EditText search = ui.input("사이트·아이디 검색", 1, 100); search.setContentDescription("저장된 비밀번호 검색");
        sheet.controls.addView(search);
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            sheet.body.removeAllViews();
            String query = search.getText().toString().strip().toLowerCase(Locale.ROOT);
            List<LoginVault.Login> logins = vault.all();
            int shown = 0; LinearLayout group = ui.group();
            for (LoginVault.Login login : logins) {
                if (!query.isEmpty() && !(login.host() + " " + login.username()).toLowerCase(Locale.ROOT).contains(query)) continue;
                String detail = (login.username().isBlank() ? "아이디 없음" : login.username()) + (login.used() > 0 ? " · " + when(login.used()) : "");
                View row = ui.listRow(R.drawable.ic_key, Ui.ORANGE, login.host(), detail, ui.chevron(), () -> detail(login));
                ui.addRow(group, SwipeDelete.wrap(ui, row, "이 비밀번호", () -> {
                    try { vault.delete(login.guid()); return true; } catch (Exception e) { toast.accept("지우지 못했어요."); return false; }
                }), true);
                shown++;
            }
            if (shown > 0) sheet.body.addView(group);
            else sheet.body.addView(ui.footer(logins.isEmpty() ? "저장된 비밀번호가 없어요. 이 브라우저에서 사이트에 로그인하면 저장할지 물어봐요." : "검색과 맞는 비밀번호가 없어요."));
            LinearLayout settings = ui.group();
            ui.addRow(settings, ui.toggle("로그인할 때 저장할지 묻기", vault.asks(), vault::asks), false);
            ui.addRow(settings, ui.listRow(R.drawable.ic_history, Ui.INDIGO, "사용 기록", "언제 어느 사이트에 입력했는지 · AI 작업 중이었는지", ui.chevron(), () -> uses(null)), true);
            sheet.body.addView(ui.sectionHeader("설정")); sheet.body.addView(settings);
            sheet.body.addView(ui.footer("비밀번호는 휴대폰 보안 영역의 키로 암호화돼요. 앱 백업에 들어가지 않고, AI는 로그인 칸을 읽거나 채우지 못해요. 사이트를 열면 브라우저가 직접 입력해요."));
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { fill[0].run(); }
        });
        Runnable watch = () -> { if (sheet.isShowing()) fill[0].run(); };
        vault.listen(watch); sheet.setOnDismissListener(d -> vault.unlisten(watch));
        sheet.primary.setText("완료"); sheet.primary.setOnClickListener(v -> sheet.dismiss());
        fill[0].run(); sheet.show(); secure(sheet);
        return sheet;
    }

    /** One login: its account and password (shown or copied after the phone's lock), its uses, and delete. */
    void detail(LoginVault.Login login) {
        Ui.Sheet sheet = ui.selectionSheet(login.host(), login.origin());
        LinearLayout group = ui.group();
        ui.addRow(group, ui.listRow(R.drawable.ic_person, Ui.BLUE, "아이디", login.username().isBlank() ? "아이디 없음" : login.username(),
            login.username().isBlank() ? null : ui.quietIcon(R.drawable.ic_copy, "아이디 복사", () -> copy("아이디", login.username(), false)), null), true);
        TextView secret = ui.text("••••••••", 15, 500, TEXT);
        LinearLayout actions = ui.row();
        actions.addView(ui.quietIcon(R.drawable.ic_eye, "비밀번호 보기", () -> unlock(() -> secret.setText(login.password()))), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        actions.addView(ui.quietIcon(R.drawable.ic_copy, "비밀번호 복사", () -> unlock(() -> copy("비밀번호", login.password(), true))), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        LinearLayout passwordRow = ui.listRow(R.drawable.ic_lock, Ui.ORANGE, "비밀번호", "", actions, null);
        if (passwordRow instanceof Ui.ListRow r && r.parts != null && r.parts.subtitle() == null) { secret.setPadding(0, ui.dp(3), 0, 0); ((LinearLayout) r.parts.title().getParent()).addView(secret); }
        ui.addRow(group, passwordRow, true);
        sheet.body.addView(group);
        sheet.body.addView(ui.footer((login.used() > 0 ? "마지막 사용 " + when(login.used()) + " · " + login.uses() + "번 사용" : "아직 사용하지 않았어요") + " · 저장 " + when(login.created())));
        List<LoginVault.Use> uses = vault.uses(login.origin());
        if (!uses.isEmpty()) {
            sheet.body.addView(ui.sectionHeader("사용 기록"));
            LinearLayout log = ui.group(); int n = 0;
            for (LoginVault.Use use : uses) { if (n++ >= 10) break; ui.addRow(log, ui.listRow(0, 0, when(use.at()), (use.username().isBlank() ? "" : use.username() + " · ") + (use.ai() ? "AI 작업 중" : "직접"), null, null), false); }
            sheet.body.addView(log);
        }
        TextView remove = ui.ghost("이 비밀번호 지우기", () -> new android.app.AlertDialog.Builder(activity).setTitle(login.host())
            .setMessage("이 비밀번호를 지울까요? 사이트의 계정은 그대로예요.").setNegativeButton("취소", null)
            .setPositiveButton("지우기", (d, w) -> { try { vault.delete(login.guid()); sheet.dismiss(); } catch (Exception e) { toast.accept("지우지 못했어요."); } }).show());
        remove.setTextColor(ERROR); sheet.body.addView(remove);
        sheet.primary.setText("닫기"); sheet.primary.setOnClickListener(v -> sheet.dismiss());
        sheet.setOnDismissListener(d -> secret.setText("••••••••"));
        sheet.show(); secure(sheet);
    }

    /** Every use (or one site's), newest first: when, which site and account, typed by the user or while an AI task ran. */
    void uses(String origin) {
        Ui.Sheet sheet = ui.selectionSheet("비밀번호 사용 기록", "비밀번호 자체는 기록하지 않아요");
        List<LoginVault.Use> uses = vault.uses(origin);
        if (uses.isEmpty()) sheet.body.addView(ui.footer("아직 사용 기록이 없어요."));
        else {
            LinearLayout log = ui.group();
            for (LoginVault.Use use : uses) ui.addRow(log, ui.listRow(0, 0, LoginVault.host(use.origin()) + " · " + when(use.at()), (use.username().isBlank() ? "" : use.username() + " · ") + (use.ai() ? "AI 작업 중" : "직접"), null, null), false);
            sheet.body.addView(log);
        }
        sheet.primary.setText("닫기"); sheet.primary.setOnClickListener(v -> sheet.dismiss());
        sheet.show(); secure(sheet);
    }

    private static void secure(Ui.Sheet sheet) { if (sheet.getWindow() != null) sheet.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); }

    private void copy(String label, String value, boolean sensitive) {
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class); if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText(label, value);
        if (sensitive) { PersistableBundle extras = new PersistableBundle(); extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true); clip.getDescription().setExtras(extras); }
        clipboard.setPrimaryClip(clip); toast.accept(label + "를 복사했어요");
    }

    /** The phone's own lock first (fingerprint, face, PIN or pattern); a phone with no lock set shows it at once. */
    private void unlock(Runnable then) {
        KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
        if (keyguard == null || !keyguard.isDeviceSecure()) { then.run(); return; }
        try {
            BiometricPrompt prompt = new BiometricPrompt.Builder(activity).setTitle("비밀번호 확인").setSubtitle("휴대폰 잠금으로 본인을 확인해요")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL).build();
            prompt.authenticate(new CancellationSignal(), activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { then.run(); }
                @Override public void onAuthenticationError(int code, CharSequence message) { if (code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) toast.accept("확인하지 못했어요 · " + message); }
            });
        } catch (RuntimeException unavailable) { toast.accept("휴대폰 잠금 확인을 열지 못했어요."); }
    }
}
