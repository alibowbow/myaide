package dev.jun.gyeot;

import android.content.res.ColorStateList;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/** Compact actions in phone security details and the scan report. */
final class SecurityControls {
    private SecurityControls() {}

    static Button button(Ui ui, String label, boolean primary, Runnable action) {
        Button button = ui.chip(label, action);
        style(ui, button, primary);
        return button;
    }

    static void style(Ui ui, Button button, boolean primary) {
        button.setTextSize(14);
        button.setTypeface(Ui.weight(600));
        button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(false);
        button.setEllipsize(null);
        button.setMinWidth(ui.dp(96));
        button.setMinimumWidth(ui.dp(96));
        button.setMinHeight(ui.dp(48));
        button.setMinimumHeight(ui.dp(48));
        int[] disabled = {-android.R.attr.state_enabled};
        StateListDrawable fill = new StateListDrawable();
        fill.addState(disabled, ui.shape(Ui.FILL, 9));
        fill.addState(new int[] {android.R.attr.state_focused}, ui.shape(primary ? Ui.ACCENT : Ui.ACCENT_SOFT, 9));
        fill.addState(new int[0], ui.shape(primary ? Ui.ACCENT : Ui.RAISED, 9));
        button.setBackground(new InsetDrawable(fill, 0, ui.dp(7), 0, ui.dp(7)));
        button.setTextColor(new ColorStateList(new int[][] {disabled, {}},
                new int[] {Ui.MUTED, primary ? Ui.ON_ACCENT : Ui.TEXT}));
        button.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
    }

    /** Equal columns use the widest label, up to the space offered by this window. */
    static LinearLayout bar(Ui ui, Button... buttons) {
        LinearLayout bar = new LinearLayout(buttons[0].getContext()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int cell = ui.dp(96), visible = 0;
                for (Button button : buttons) {
                    if (button.getVisibility() == View.GONE) continue;
                    visible++;
                    int label = (int) Math.ceil(button.getPaint().measureText(button.getText().toString()));
                    cell = Math.max(cell, label + button.getPaddingLeft() + button.getPaddingRight());
                }
                int gaps = ui.dp(6) * Math.max(0, visible - 1);
                int preferred = cell * visible + gaps;
                int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? preferred
                        : Math.min(preferred, MeasureSpec.getSize(widthSpec));
                // A horizontal weighted row inside a ScrollView has no intrinsic height when all
                // children fill the parent. Measure the labels at their final width before equalizing.
                int column = Math.max(0, width - gaps) / Math.max(1, visible);
                int height = ui.dp(48);
                for (Button button : buttons) {
                    if (button.getVisibility() == View.GONE) continue;
                    button.measure(MeasureSpec.makeMeasureSpec(column, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    height = Math.max(height, button.getMeasuredHeight());
                }
                int measuredHeight = View.resolveSize(height, heightSpec);
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY));
            }
        };
        bar.setBaselineAligned(false);
        bar.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        for (Button button : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            if (bar.getChildCount() > 0) lp.setMarginStart(ui.dp(6));
            bar.addView(button, lp);
        }
        return bar;
    }
}
