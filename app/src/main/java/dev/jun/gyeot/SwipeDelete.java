package dev.jun.gyeot;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;

/**
 * A history row that slides: pushed left it shows a round X at its end, and a tap on the X deletes it at once (no question
 * asked; when it cannot be deleted now the deletion says why and the row slides back). Pushed back right, a tap on the
 * row while it is open, or opening another row closes it. A tap on the row works as before, and so does a long press: a
 * drag that starts after the long press, or one that is mostly up and down (the list scrolling), never slides. Screen
 * readers get a 지우기 action on the row instead.
 */
final class SwipeDelete extends FrameLayout {
    private static WeakReference<SwipeDelete> open = new WeakReference<>(null);
    private final View front;
    private final ImageView x;
    private final BooleanSupplier delete;
    private final int reveal, slop, longPress, fling;
    private float downX, downY, startAt;
    private long downTime;
    private boolean dragging, closeOnUp, gone;
    private VelocityTracker velocity;

    /**
     * The row inside a slider; what is the thing deleted ("이 대화"), for the X's spoken name. delete runs on the X's tap
     * and says whether it deleted.
     */
    static View wrap(Ui ui, View row, String what, BooleanSupplier delete) { return new SwipeDelete(ui, row, what, delete); }

    private SwipeDelete(Ui ui, View row, String what, BooleanSupplier delete) {
        super(row.getContext());
        this.front = row; this.delete = delete;
        ViewConfiguration config = ViewConfiguration.get(getContext());
        slop = config.getScaledTouchSlop(); longPress = ViewConfiguration.getLongPressTimeout(); fling = config.getScaledMinimumFlingVelocity() * 4;
        reveal = ui.dp(68);
        x = new ImageView(getContext()); x.setImageResource(R.drawable.ic_close); x.setImageTintList(ColorStateList.valueOf(0xffffffff));
        x.setBackground(ui.shape(Ui.ERROR, 99)); int pad = ui.dp(10); x.setPadding(pad, pad, pad, pad); x.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        x.setContentDescription((what == null || what.isBlank() ? "" : what + " ") + "지우기"); x.setAlpha(0f); x.setVisibility(INVISIBLE);
        x.setOnClickListener(v -> remove());
        LayoutParams spot = new LayoutParams(ui.dp(42), ui.dp(42), Gravity.END | Gravity.CENTER_VERTICAL); spot.setMarginEnd(ui.dp(14));
        addView(x, spot);
        ViewGroup.LayoutParams given = row.getLayoutParams();
        addView(row, new LayoutParams(-1, given != null && given.height > 0 ? given.height : -2));
        row.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.swipe_delete, x.getContentDescription()));
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == R.id.swipe_delete) { remove(); return true; }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    private boolean isOpen() { return front.getTranslationX() < 0; }

    /** Slides back (another row opened, or the list is redrawn). */
    void close() { settle(0); }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (gone) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = e.getX(); downY = e.getY(); downTime = e.getEventTime(); startAt = front.getTranslationX(); dragging = false; closeOnUp = false;
                front.animate().cancel();
                SwipeDelete other = open.get(); if (other != null && other != this) other.close();
                track(e);
                // An open row closes on a tap rather than doing what the row does; the X itself still takes its tap.
                if (isOpen() && e.getX() < getWidth() + startAt) { closeOnUp = true; return true; }
                return false;
            }
            case MotionEvent.ACTION_MOVE -> { track(e); if (startDrag(e)) return true; return false; }
            default -> { return false; }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (gone) return true;
        track(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> { return true; }
            case MotionEvent.ACTION_MOVE -> {
                if (!dragging) startDrag(e);
                if (dragging) show(Math.max(-reveal * 1.12f, Math.min(0f, startAt + e.getX() - downX)));
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity.computeCurrentVelocity(1000); float speed = velocity.getXVelocity();
                    float at = front.getTranslationX();
                    settle(speed < -fling ? -reveal : speed > fling ? 0 : at < -reveal / 2f ? -reveal : 0);
                } else if (closeOnUp) close();
                done(); return true;
            }
            case MotionEvent.ACTION_CANCEL -> { if (dragging || closeOnUp) settle(front.getTranslationX() < -reveal / 2f ? -reveal : 0); done(); return true; }
            default -> { return true; }
        }
    }

    /** A drag that is sideways, before any long press, and leftward unless the row is already open. */
    private boolean startDrag(MotionEvent e) {
        if (dragging) return true;
        float dx = e.getX() - downX, dy = e.getY() - downY;
        if (Math.abs(dx) < slop || Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
        if (e.getEventTime() - downTime > longPress && !isOpen()) return false;
        if (dx > 0 && !isOpen()) return false;
        dragging = true; downX = e.getX() - (front.getTranslationX() - startAt); closeOnUp = false;
        ViewGroup parent = (ViewGroup) getParent(); if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private void track(MotionEvent e) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        MotionEvent copy = MotionEvent.obtain(e); copy.offsetLocation(getLeft(), getTop()); velocity.addMovement(copy); copy.recycle();
    }
    private void done() { if (velocity != null) { velocity.recycle(); velocity = null; } dragging = false; closeOnUp = false; }

    private void show(float at) {
        front.setTranslationX(at);
        float shown = Math.min(1f, -at / reveal);
        x.setVisibility(shown > 0 ? VISIBLE : INVISIBLE); x.setAlpha(shown); x.setScaleX(0.6f + 0.4f * shown); x.setScaleY(0.6f + 0.4f * shown);
    }
    private void settle(float to) {
        if (to < 0) { open = new WeakReference<>(this); Ui.tick(this); }
        else if (open.get() == this) open = new WeakReference<>(null);
        if (!ValueAnimator.areAnimatorsEnabled()) { show(to); return; }
        front.animate().translationX(to).setDuration(180).setInterpolator(new DecelerateInterpolator())
            .setUpdateListener(a -> show(front.getTranslationX())).setListener(null).start();
    }

    /** Deletes the row, then it slides away and the space closes; a row that could not be deleted slides back. */
    private void remove() {
        if (gone) return;
        if (!delete.getAsBoolean()) { close(); return; }
        gone = true; Ui.done(this);
        if (open.get() == this) open = new WeakReference<>(null);
        x.setEnabled(false);
        if (!ValueAnimator.areAnimatorsEnabled() || getHeight() == 0 || !isAttachedToWindow()) { collapse(); return; }
        front.animate().translationX(-getWidth()).alpha(0f).setDuration(160).setUpdateListener(null).setListener(null).start();
        x.animate().alpha(0f).setDuration(160).start();
        ValueAnimator shrink = ValueAnimator.ofInt(getHeight(), 0).setDuration(200); shrink.setStartDelay(120);
        shrink.addUpdateListener(a -> { ViewGroup.LayoutParams p = getLayoutParams(); if (p != null) { p.height = (int) a.getAnimatedValue(); setLayoutParams(p); } });
        shrink.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator a) { collapse(); } });
        shrink.start();
    }

    /** Takes the row out of its list, with the hairline that went with it. */
    private void collapse() {
        if (!(getParent() instanceof ViewGroup list)) return;
        int at = list.indexOfChild(this);
        View before = at > 0 ? list.getChildAt(at - 1) : null, after = at + 1 < list.getChildCount() ? list.getChildAt(at + 1) : null;
        if (hairline(before)) before.setVisibility(GONE); else if (hairline(after)) after.setVisibility(GONE);
        setVisibility(GONE);
    }
    private static boolean hairline(View view) { return view != null && view.getClass() == View.class && view.getVisibility() == VISIBLE; }

    @Override protected void onDetachedFromWindow() { if (open.get() == this) open = new WeakReference<>(null); super.onDetachedFromWindow(); }
}
