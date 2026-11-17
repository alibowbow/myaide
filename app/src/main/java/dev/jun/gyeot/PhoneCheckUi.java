package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.PhoneRisk;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.jun.gyeot.Ui.*;

/**
 * 휴대폰 검사's sheet: it checks as it opens, then lists what it found most serious first, each with
 * the one place to act on it (the setting's page, the app's info page where it can be removed, the file explorer). What
 * passed is a short list at the foot, so the user sees what was looked at. It says every time that it is a check of risk
 * signs and not a virus scan. Nothing on the phone is changed from here.
 */
final class PhoneCheckUi {
    private PhoneCheckUi() {}
    /** Runs the check off the main thread; tests run it in place. */
    static Executor runner = task -> { Thread thread = new Thread(task, "phone-check"); thread.setDaemon(true); thread.start(); };
    /** What gathers the findings; tests give their own. */
    interface Checker { PhoneCheck.Result run(Activity activity, AtomicBoolean cancelled); }
    static Checker checker = (activity, cancelled) -> PhoneCheck.run(activity, cancelled::get);

    /** files: opens the file explorer (for installer files); done: the sheet closed, with a result or without. */
    static Ui.Sheet show(Activity activity, Runnable files, Runnable done) {
        Ui ui = new Ui(activity); Handler main = new Handler(Looper.getMainLooper());
        Ui.Sheet sheet = ui.selectionSheet("휴대폰 검사", "설정 · 설치된 앱 · 설치 파일의 위험 신호");
        AtomicBoolean cancelled = new AtomicBoolean(); boolean[] busy = {false};
        Runnable[] check = new Runnable[1];
        check[0] = () -> {
            if (busy[0]) return; busy[0] = true;
            sheet.body.removeAllViews(); sheet.primary.setEnabled(false); sheet.primary.setText("검사하는 중…");
            TextView working = ui.text("휴대폰을 살펴보고 있어요…", CALLOUT, 450, MUTED); working.setGravity(Gravity.CENTER); working.setPadding(0, ui.dp(28), 0, ui.dp(28));
            working.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); sheet.body.addView(working);
            runner.execute(() -> {
                PhoneCheck.Result result; String failed = null;
                try { result = checker.run(activity, cancelled); } catch (RuntimeException e) { result = null; failed = e.getMessage(); }
                PhoneCheck.Result found = result; String why = failed;
                main.post(() -> {
                    busy[0] = false; if (cancelled.get() || !sheet.isShowing()) return;
                    sheet.primary.setEnabled(true); sheet.primary.setText("다시 검사");
                    sheet.body.removeAllViews();
                    if (found == null) { sheet.body.addView(ui.banner("검사하지 못했어요." + (why == null ? "" : " " + why), ERROR)); return; }
                    fill(activity, ui, sheet, found.findings(), files);
                });
            });
        };
        sheet.primary.setOnClickListener(v -> check[0].run());
        sheet.setOnDismissListener(d -> { cancelled.set(true); if (done != null) done.run(); });
        sheet.show(); check[0].run();
        return sheet;
    }

    private static void fill(Activity activity, Ui ui, Ui.Sheet sheet, List<PhoneRisk.Finding> findings, Runnable files) {
        int[] counts = PhoneRisk.counts(findings);
        TextView verdict = ui.text(PhoneRisk.summary(findings), TITLE2, 720, counts[0] > 0 ? ERROR : counts[1] > 0 ? WARNING : SUCCESS);
        verdict.setAccessibilityHeading(true); verdict.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4)); sheet.body.addView(verdict);
        TextView tally = ui.text("위험 " + counts[0] + " · 주의 " + counts[1] + " · 통과 " + counts[2], FOOTNOTE, 500, MUTED); tally.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(12)); sheet.body.addView(tally);
        LinearLayout passed = null; int passes = 0;
        for (PhoneRisk.Finding finding : findings) {
            if (finding.level() == PhoneRisk.Level.OK) {
                if (passed == null) passed = ui.column();
                TextView line = ui.text("✓  " + finding.title() + (finding.detail().isEmpty() ? "" : " · " + finding.detail()), FOOTNOTE, 450, MUTED); line.setPadding(ui.dp(4), ui.dp(3), ui.dp(4), ui.dp(3));
                passed.addView(line); passes++; continue;
            }
            sheet.body.addView(item(activity, ui, sheet, finding, files));
        }
        if (passed != null) {
            TextView heading = ui.text("통과한 항목 " + passes + "개", FOOTNOTE, 650, MUTED); heading.setAccessibilityHeading(true); heading.setPadding(ui.dp(4), ui.dp(14), ui.dp(4), ui.dp(4));
            sheet.body.addView(heading); sheet.body.addView(passed);
        }
        TextView scope = ui.footer(PhoneRisk.SCOPE); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = ui.dp(12); sheet.body.addView(scope, sp);
        if (counts[0] + counts[1] > 0) {
            // The findings in words, for the user's own AI to look over: put in the main chat's message box, sent only if the user sends.
            Button ask = ui.button("AI에게 물어보기", false, () -> { sheet.dismiss(); ((GyeotApp) activity.getApplication()).store().mainDraft = report(findings); MainNavigation.open(activity, "chat"); });
            ask.setContentDescription("AI에게 물어보기: 검사 결과를 메인 대화 입력칸에 넣어요. 보내기 전에는 아무것도 전송되지 않아요.");
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2); ap.topMargin = ui.dp(10); sheet.body.addView(ask, ap);
        }
    }
    private static View item(Activity activity, Ui ui, Ui.Sheet sheet, PhoneRisk.Finding finding, Runnable files) {
        boolean danger = finding.level() == PhoneRisk.Level.DANGER, caution = finding.level() == PhoneRisk.Level.CAUTION;
        int tint = danger ? ERROR : caution ? WARNING : MUTED; String tag = danger ? "위험" : caution ? "주의" : "참고";
        LinearLayout card = ui.column(); card.setBackground(ui.shape(FILL, R_BUTTON)); card.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        TextView label = ui.text(tag, CAPTION, 720, tint); card.addView(label);
        TextView title = ui.text(finding.title(), CALLOUT, 650, TEXT); title.setPadding(0, ui.dp(3), 0, 0); card.addView(title);
        if (!finding.detail().isEmpty()) { TextView detail = ui.text(finding.detail(), FOOTNOTE, 450, MUTED); detail.setPadding(0, ui.dp(4), 0, 0); card.addView(detail); }
        card.setContentDescription(tag + ": " + finding.title() + (finding.detail().isEmpty() ? "" : ". " + finding.detail()));
        String action = finding.action();
        if (!action.isEmpty()) {
            String name = action.startsWith("app:") ? "앱 정보 열기" : action.equals("files") ? "파일 탐색기 열기" : "설정 열기";
            Button open = ui.chip(name, () -> act(activity, sheet, action, files)); open.setContentDescription(name + ": " + finding.title());
            open.setBackground(ui.capsule(CARD)); // on the card's own fill a chip's usual fill would not show
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.topMargin = ui.dp(8); card.addView(open, bp);
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.bottomMargin = ui.dp(8); card.setLayoutParams(cp);
        return card;
    }
    /** Opens the place to act: a settings page (all settings when that page is not on this phone), an app's info page, the explorer. */
    static void act(Activity activity, Ui.Sheet sheet, String action, Runnable files) {
        if (action.equals("files")) { sheet.dismiss(); if (files != null) files.run(); return; }
        Intent intent = action.startsWith("app:") ? new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", action.substring(4), null))
            : new Intent(action.substring("settings:".length()));
        try { activity.startActivity(intent); }
        catch (RuntimeException missing) { try { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (RuntimeException none) { android.widget.Toast.makeText(activity, "설정 화면을 열지 못했어요.", android.widget.Toast.LENGTH_SHORT).show(); } }
    }
    /** The risks and cautions as a message the user can send to their AI: what was found, in the check's own words. */
    static String report(List<PhoneRisk.Finding> findings) {
        StringBuilder text = new StringBuilder("휴대폰 검사 결과예요. 실제로 위험한지 봐 주고, 무엇을 하면 좋을지 알려 줘.\n");
        for (PhoneRisk.Finding finding : findings) {
            if (finding.level() != PhoneRisk.Level.DANGER && finding.level() != PhoneRisk.Level.CAUTION) continue;
            text.append("- [").append(finding.level() == PhoneRisk.Level.DANGER ? "위험" : "주의").append("] ").append(finding.title());
            if (!finding.detail().isEmpty()) text.append(" · ").append(finding.detail());
            text.append('\n');
        }
        return text.toString().strip();
    }
}
