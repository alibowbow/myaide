package dev.jun.gyeot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import static dev.jun.gyeot.Ui.*;

/** Real ratio silhouettes, with a check as well as a tint; the entire tile is a touch target. */
final class RatioPicker extends LinearLayout {
    static final String[] ASPECTS = {"square", "portrait", "landscape", "9:16", "16:9", "4:3", "3:4"};
    private static final String[] LABELS = {"1:1", "2:3", "3:2", "9:16", "16:9", "4:3", "3:4"},
        NOTES = {"정사각형", "세로", "가로", "스토리", "와이드", "가로", "세로"};
    private static final float[] RATIOS = {1, 2f/3, 3f/2, 9f/16, 16f/9, 4f/3, 3f/4};
    private final Ui ui; private int chosen; private final Runnable[] paint = new Runnable[7];
    RatioPicker(Context context, Ui ui, String aspect) {
        super(context); this.ui = ui; setOrientation(VERTICAL);
        for (int i = 0; i < ASPECTS.length; i++) if (ASPECTS[i].equals(aspect)) chosen = i;
        int available = context.getResources().getConfiguration().screenWidthDp;
        int columns = available >= 560 ? 4 : available < 300 ? 2 : 3;
        for (int start = 0; start < ASPECTS.length; start += columns) {
            LinearLayout row = ui.row();
            for (int col = 0; col < columns; col++) {
                int index = start + col; LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); if (col > 0) p.leftMargin = ui.dp(8);
                row.addView(index < ASPECTS.length ? tile(index) : new View(context), p);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); if (start > 0) rp.topMargin = ui.dp(8); addView(row, rp);
        }
        refresh();
    }
    String aspect() { return ASPECTS[chosen]; }
    private View tile(int index) {
        LinearLayout tile = ui.column(); tile.setGravity(Gravity.CENTER); tile.setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(9)); tile.setMinimumHeight(ui.dp(100));
        TextView check = ui.text("", 11, 700, ACCENT); check.setGravity(Gravity.END); tile.addView(check, new LinearLayout.LayoutParams(-1, ui.dp(12)));
        Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG); pen.setStyle(Paint.Style.STROKE); pen.setStrokeWidth(ui.dp(1.5f));
        View silhouette = new View(getContext()) {
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas); float w = ui.dp(38), h = w / RATIOS[index]; if (h > ui.dp(34)) { h = ui.dp(34); w = h * RATIOS[index]; }
                pen.setColor(chosen == index ? ACCENT : MUTED); float x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
                canvas.drawRoundRect(new RectF(x, y, x + w, y + h), ui.dp(3), ui.dp(3), pen);
            }
        };
        tile.addView(silhouette, new LinearLayout.LayoutParams(-1, ui.dp(40)));
        TextView label = ui.text(LABELS[index], 14, 650, TEXT), note = ui.text(NOTES[index], 11.5f, 420, MUTED); label.setGravity(Gravity.CENTER); note.setGravity(Gravity.CENTER);
        tile.addView(label); note.setPadding(0, ui.dp(3), 0, 0); tile.addView(note);
        tile.setFocusable(true); tile.setOnClickListener(v -> { chosen = index; refresh(); }); Ui.pressable(tile, true);
        paint[index] = () -> {
            boolean on = chosen == index; GradientDrawable background = ui.shape(on ? ACCENT_SOFT : CARD, 16); background.setStroke(Math.max(1, ui.dp(on ? 1.5f : 0.5f)), on ? ACCENT : SEPARATOR); tile.setBackground(background);
            check.setText(on ? "✓" : ""); label.setTextColor(on ? ACCENT : TEXT); silhouette.invalidate(); tile.setSelected(on); tile.setStateDescription(on ? "선택됨" : "선택 안 됨");
            tile.setContentDescription(LABELS[index] + " " + NOTES[index]);
        };
        return tile;
    }
    private void refresh() { for (Runnable update : paint) if (update != null) update.run(); }
}
