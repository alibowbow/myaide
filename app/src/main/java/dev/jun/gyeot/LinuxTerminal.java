package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static dev.jun.gyeot.Ui.*;

/**
 * A small terminal for an official CLI in the Linux environment (its installer, its sign-in): the command runs under
 * script(1), so it has a real terminal and asks as it would on a computer. The sheet shows what it prints (colours and
 * cursor moves left out), a sign-in link as a button, a line to answer with (a code, a word) and the keys installers ask
 * for (Enter, y, n, arrows, Tab, Esc, Ctrl+C). 끝내기 or closing the sheet stops it. What is typed is never shown again.
 */
final class LinuxTerminal {
    private static final Pattern URL = Pattern.compile("https://[^\\s\"'<>]+");
    private static final Pattern CSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]"), OSC = Pattern.compile("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)"),
        ESC = Pattern.compile("\u001B[@-Z\\\\-_()#][0-9A-Za-z]?");

    /** What a terminal printed as plain lines: escape sequences out, each line as it was last redrawn (after its last \r). */
    static String plain(String raw) {
        String s = OSC.matcher(raw).replaceAll(""); s = CSI.matcher(s).replaceAll(""); s = ESC.matcher(s).replaceAll("");
        StringBuilder out = new StringBuilder();
        for (String line : s.replace("\r\n", "\n").split("\n", -1)) {
            int cr = line.lastIndexOf('\r'); String shown = cr < 0 ? line : line.substring(cr + 1);
            StringBuilder clean = new StringBuilder(); for (char ch : shown.toCharArray()) if (ch == '\t' || ch >= ' ') clean.append(ch);
            out.append(clean).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n");
    }

    /** Opens the terminal running command (bash -lc in Linux) with env; done runs when the sheet is closed. */
    static void show(Activity activity, Ui ui, String title, String about, String command, Map<String, String> env, Runnable done) {
        Handler main = new Handler(Looper.getMainLooper());
        Ui.Sheet sheet = ui.selectionSheet(title, about);
        TextView screen = ui.text("시작하는 중…", 12, 450, TEXT); screen.setTypeface(android.graphics.Typeface.MONOSPACE); screen.setTextIsSelectable(true);
        screen.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        ScrollView scroll = new ScrollView(activity); scroll.setBackground(ui.shape(FILL, 14)); scroll.addView(screen);
        sheet.body.addView(scroll, new LinearLayout.LayoutParams(-1, ui.dp(280)));
        // The sign-in link the CLI printed: opened in the browser, or copied.
        LinearLayout link = ui.row(); link.setVisibility(View.GONE); link.setPadding(0, ui.dp(8), 0, 0);
        String[] url = {""};
        link.addView(ui.pill("로그인 링크 열기", R.drawable.ic_browser, true, () -> { try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url[0]))); } catch (Exception e) { Toast.makeText(activity, "브라우저를 열지 못했어요.", Toast.LENGTH_SHORT).show(); } }));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(8);
        link.addView(ui.pill("링크 복사", R.drawable.ic_copy, false, () -> {
            android.content.ClipboardManager clip = activity.getSystemService(android.content.ClipboardManager.class);
            if (clip != null) clip.setPrimaryClip(android.content.ClipData.newPlainText("link", url[0])); Toast.makeText(activity, "링크를 복사했어요.", Toast.LENGTH_SHORT).show();
        }), gap);
        sheet.body.addView(link);
        // A line to answer with: the code from the browser, or a word an installer asks for (sent with Enter).
        LinearLayout reply = ui.row(); reply.setPadding(0, ui.dp(8), 0, 0);
        EditText field = ui.input("코드나 답 붙여 넣기", 1, 2000); field.setImeOptions(EditorInfo.IME_ACTION_SEND); field.setTextSize(SUBHEAD);
        field.setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9));
        reply.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sendAt = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)); sendAt.leftMargin = ui.dp(6);
        Process[] process = {null};
        java.util.function.Consumer<String> type = text -> {
            Process p = process[0]; if (p == null || !p.isAlive()) return;
            new Thread(() -> { try { OutputStream out = p.getOutputStream(); out.write(text.getBytes(StandardCharsets.UTF_8)); out.flush(); } catch (Exception ignored) {} }, "linux-type").start();
        };
        Runnable sendLine = () -> { String t = field.getText().toString(); field.setText(""); type.accept(t + "\r"); };
        field.setOnEditorActionListener((v, id, e) -> { if (id == EditorInfo.IME_ACTION_SEND) { sendLine.run(); return true; } return false; });
        reply.addView(ui.iconButton(R.drawable.ic_send, ACCENT, ON_ACCENT, "보내기", sendLine), sendAt);
        sheet.body.addView(reply);
        String[][] keys = {{"Enter", "\r"}, {"y", "y\r"}, {"n", "n\r"}, {"↑", "\u001B[A"}, {"↓", "\u001B[B"}, {"Tab", "\t"}, {"Esc", "\u001B"}, {"Ctrl+C", "\u0003"}};
        View[] buttons = new View[keys.length];
        for (int i = 0; i < keys.length; i++) { String[] k = keys[i]; buttons[i] = ui.pill(k[0], 0, false, () -> type.accept(k[1])); }
        sheet.body.addView(ui.pills(buttons));
        TextView note = ui.footer("입력한 코드나 답은 화면에 다시 보여 주지 않아요."); sheet.body.addView(note);

        StringBuilder raw = new StringBuilder(); boolean[] closed = {false}, painting = {false};
        Runnable paint = () -> {
            painting[0] = false; if (closed[0]) return;
            String text; synchronized (raw) { text = plain(raw.toString()); }
            screen.setText(text.strip().isEmpty() ? "…" : text.strip());
            Matcher m = URL.matcher(text); String last = ""; while (m.find()) last = m.group();
            if (!last.isEmpty()) { url[0] = last.replaceAll("[).,]+$", ""); link.setVisibility(View.VISIBLE); }
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        };
        sheet.primary.setText("끝내기");
        sheet.primary.setOnClickListener(v -> { Process p = process[0]; if (p != null && p.isAlive()) p.destroy(); else sheet.dismiss(); });
        sheet.setOnDismissListener(d -> { closed[0] = true; Process p = process[0]; if (p != null && p.isAlive()) p.destroyForcibly(); if (done != null) done.run(); });
        sheet.show();
        new Thread(() -> {
            try {
                // script(1) gives the command a terminal of its own, wide so a long link stays on one line.
                String inner = "stty cols 500 rows 40 2>/dev/null; " + command;
                Process p = LinuxRuntime.start(activity, LinuxRuntime.rootfs(activity), "script -qfec " + quote(inner) + " /dev/null", env);
                process[0] = p;
                try (InputStream in = p.getInputStream()) {
                    byte[] b = new byte[4096]; int n;
                    while ((n = in.read(b)) > 0) {
                        synchronized (raw) { raw.append(new String(b, 0, n, StandardCharsets.UTF_8)); if (raw.length() > 200_000) raw.delete(0, raw.length() - 150_000); }
                        if (!painting[0]) { painting[0] = true; main.postDelayed(paint, 120); }
                    }
                }
                int code = p.waitFor();
                main.post(() -> { if (closed[0]) return; paint.run(); synchronized (raw) { raw.append("\n\n[끝났어요 · 종료 코드 ").append(code).append("]\n"); } paint.run(); sheet.primary.setText("닫기"); });
            } catch (Exception e) {
                String why = e.getMessage() == null ? "실행하지 못했어요." : e.getMessage();
                main.post(() -> { if (closed[0]) return; screen.setText(why); sheet.primary.setText("닫기"); });
            }
        }, "linux-terminal").start();
    }
    /** One shell word: the text in single quotes. */
    static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
}
