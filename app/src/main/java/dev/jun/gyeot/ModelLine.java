package dev.jun.gyeot;

import android.app.Activity;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The model line under every chat's input (준: "그동안 모든 입력창은 바로 모델선택가능하게 했는데 자꾸 통일이 안되고 있어", "새로
 * 대화창 만들거나 입력창 만들면 같은문제가 반복돼"): the model that answers the next message, its name in bold and its thinking
 * level quiet beside it, a small chevron, at the end of the line under the field; a tap opens that chat's model menu. One part
 * for all of them, so a new chat has it as the others do: tools/build.py fails a screen with a send button (Ui.sendButton) that
 * does not use it (check_composers), and launcher.html's 퍼레와 대화 has its own #chatModel line in the same look.
 */
final class ModelLine {
    private ModelLine() {}
    /** Each line is tagged so the screen tests find it. */
    static final String TAG = "modelLine";

    /** The line: single, ending in "…" when long, a chevron after it, at least 36dp tall; tap: the chat's model menu. */
    static TextView make(Activity activity, Ui ui, View.OnClickListener tap) {
        TextView line = ui.text("", 13, 500, Ui.TEXT); line.setTag(TAG);
        line.setSingleLine(true); line.setEllipsize(TextUtils.TruncateAt.END);
        line.setGravity(Gravity.CENTER_VERTICAL | Gravity.END); line.setPadding(ui.dp(8), 0, ui.dp(4), 0); line.setMinHeight(ui.dp(36));
        android.graphics.drawable.Drawable chevron = activity.getDrawable(R.drawable.ic_chevron_down);
        if (chevron != null) { chevron = chevron.mutate(); chevron.setTint(Ui.MUTED); chevron.setBounds(0, 0, ui.dp(15), ui.dp(15)); line.setCompoundDrawablesRelative(null, null, chevron, null); line.setCompoundDrawablePadding(ui.dp(4)); }
        if (tap != null) { line.setOnClickListener(tap); Ui.pressable(line, true); }
        return line;
    }
    /** The line in its own row under the field, at the row's end (as 메인 대화 has it). */
    static LinearLayout row(Ui ui, TextView line) {
        LinearLayout row = ui.row(); row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); row.setPadding(ui.dp(4), ui.dp(2), 0, 0);
        row.addView(line, new LinearLayout.LayoutParams(-2, ui.dp(36)));
        return row;
    }
    /** "GPT-6 Luna  Light": the model in bold, its level quiet beside it (none when it has no levels). */
    static CharSequence label(String model, String level) {
        SpannableStringBuilder label = new SpannableStringBuilder(model == null ? "" : model);
        label.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, label.length(), 0);
        if (level != null && !level.isEmpty()) { int at = label.length(); label.append("  ").append(level); label.setSpan(new ForegroundColorSpan(Ui.MUTED), at, label.length(), 0); }
        return label;
    }
    /** Shows {model, level} on the line; what: the chat it belongs to, for its spoken name ("메인 대화", "마이튜브 AI" …). */
    static void show(TextView line, String model, String level, String what) {
        line.setText(label(model, level));
        line.setContentDescription((what == null || what.isBlank() ? "" : what + " ") + "모델과 생각 깊이 바꾸기, 지금 " + model + (level == null || level.isEmpty() ? "" : " " + level));
    }
    static void show(TextView line, String[] parts, String what) { show(line, parts[0], parts.length > 1 ? parts[1] : "", what); }
}
