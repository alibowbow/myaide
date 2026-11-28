package dev.jun.gyeot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.Drawing;
import static dev.jun.gyeot.Ui.*;

/**
 * A picture's shape as a list (준: "이미지크기를 이런식으로 선택할 수 있게해줘", "gpt에서 제공하는 비율에 맞게"): 가로 · 세로 at the
 * end of the heading's line, then each shape GPT Image draws (core/Drawing.WIDE, TALL) as one row: its outline drawn to scale,
 * its name and ratio ("와이드스크린 (16:9)"), a check by the one chosen. A tap picks it; given picked, that is told at once.
 */
final class RatioPicker extends LinearLayout {
    private final Ui ui; private final LinearLayout rows; private final java.util.function.Consumer<String> picked;
    private String chosen; private boolean tall;
    RatioPicker(Context context, Ui ui, String aspect, java.util.function.Consumer<String> picked) {
        super(context); this.ui = ui; this.picked = picked; setOrientation(VERTICAL);
        String[] now = Drawing.shape(aspect); chosen = now == null ? "square" : now[0]; tall = Drawing.ratio(chosen) < 1;
        LinearLayout head = ui.row(); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(ui.dp(2), 0, 0, ui.dp(6));
        TextView title = ui.text("화면 비율", 14, 650, TEXT); head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(ui.switcher(new String[]{"가로", "세로"}, tall ? 1 : 0, i -> { tall = i == 1; fill(); }));
        addView(head, new LinearLayout.LayoutParams(-1, -2));
        rows = ui.column(); addView(rows, new LinearLayout.LayoutParams(-1, -2));
        fill();
    }
    String aspect() { return chosen; }
    private void fill() {
        rows.removeAllViews();
        for (String[] one : tall ? Drawing.TALL : Drawing.WIDE) rows.addView(row(one), new LinearLayout.LayoutParams(-1, ui.dp(44)));
    }
    private View row(String[] one) {
        boolean on = one[0].equals(chosen); float ratio = (float) Drawing.ratio(one[0]);
        LinearLayout row = ui.row(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(ui.dp(6), 0, ui.dp(6), 0);
        Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG); pen.setStyle(Paint.Style.STROKE); pen.setStrokeWidth(ui.dp(1.8f)); pen.setColor(on ? ACCENT : TEXT);
        View outline = new View(getContext()) {
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                // The long side 24dp (wide) or 22dp (tall), the short side to scale, never thinner than 7dp.
                float w = ratio >= 1 ? ui.dp(24) : Math.max(ui.dp(7), ui.dp(22) * ratio), h = ratio >= 1 ? Math.max(ui.dp(7), ui.dp(24) / ratio) : ui.dp(22);
                float x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
                canvas.drawRoundRect(new RectF(x, y, x + w, y + h), ui.dp(3), ui.dp(3), pen);
            }
        };
        outline.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(outline, new LinearLayout.LayoutParams(ui.dp(32), ui.dp(28)));
        TextView name = ui.text(one[2] + " (" + one[1] + ")", 15, on ? 650 : 450, on ? ACCENT : TEXT); name.setSingleLine(true); name.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (on) {
            ImageView check = new ImageView(getContext()); check.setImageResource(R.drawable.ic_check); check.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            row.addView(check, new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
        }
        row.setContentDescription(one[2] + " " + one[1].replace(":", " 대 ") + (on ? ", 선택됨" : "")); row.setSelected(on); row.setFocusable(true);
        row.setOnClickListener(v -> { Ui.tick(v); chosen = one[0]; fill(); if (picked != null) picked.accept(chosen); });
        Ui.pressable(row, true);
        return row;
    }
}
