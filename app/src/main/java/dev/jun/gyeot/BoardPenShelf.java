package dev.jun.gyeot;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** On a phone the swatches wrap below the pens. Each row still scrolls on a narrow split screen. */
final class BoardPenShelf extends LinearLayout {
    final LinearLayout pens, options;
    View optionalSwatch;

    BoardPenShelf(Context context) {
        super(context);
        setGravity(Gravity.CENTER);
        pens = new LinearLayout(context);
        options = new LinearLayout(context);
        addView(strip(pens), new LayoutParams(-2,-2));
        addView(strip(options), new LayoutParams(-2,-2));
    }
    private HorizontalScrollView strip(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(row);
        return scroll;
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int natural = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        pens.measure(natural, natural);
        options.measure(natural, natural);
        int available = View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        boolean bounded = View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED;
        if (optionalSwatch != null) {
            int full = options.getMeasuredWidth() + (optionalSwatch.getVisibility() == GONE ? optionalSwatch.getLayoutParams().width : 0);
            int visibility = bounded && available < full ? GONE : VISIBLE;
            if (optionalSwatch.getVisibility() != visibility) {
                optionalSwatch.setVisibility(visibility);
                options.measure(natural, natural);
            }
        }
        boolean wrap = bounded && pens.getMeasuredWidth() + options.getMeasuredWidth() > available;
        if (getOrientation() != (wrap ? VERTICAL : HORIZONTAL)) setOrientation(wrap ? VERTICAL : HORIZONTAL);
        super.onMeasure(widthSpec, heightSpec);
    }
}
