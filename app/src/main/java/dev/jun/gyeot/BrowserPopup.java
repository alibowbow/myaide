package dev.jun.gyeot;

import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;
import static dev.jun.gyeot.Ui.*;

/** Browser-only appearance and placement. Existing sheet actions, consent, and persistence are untouched. */
final class BrowserPopup {
    private BrowserPopup() {}
    static void show(Ui ui, Ui.Sheet sheet, View anchor, boolean applyAction) {
        prepare(ui, sheet, applyAction);
        sheet.show();
        sheet.panel.animate().cancel(); sheet.panel.setAlpha(1); sheet.panel.setTranslationY(0);
        Window window=sheet.getWindow(); if(window==null)return;
        window.setBackgroundBlurRadius(0);
        boolean[] positioning={false};
        Runnable place=()->{if(positioning[0])return;positioning[0]=true;try{position(ui,sheet,anchor,applyAction);}finally{positioning[0]=false;}};
        ViewTreeObserver.OnGlobalLayoutListener layout=place::run;
        sheet.panel.getViewTreeObserver().addOnGlobalLayoutListener(layout);
        sheet.panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {}
            public void onViewDetachedFromWindow(View v) { if(v.getViewTreeObserver().isAlive())v.getViewTreeObserver().removeOnGlobalLayoutListener(layout); }
        });
        place.run();
    }
    static void prepare(Ui ui,Ui.Sheet sheet,boolean applyAction) {
        sheet.dragHandle.setVisibility(View.GONE);
        GradientDrawable surface=ui.shape(CARD,18);surface.setStroke(Math.max(1,ui.dp(.6f)),SEPARATOR);sheet.panel.setBackground(surface);
        sheet.header.setPadding(ui.dp(14),ui.dp(4),ui.dp(4),ui.dp(2));sheet.header.setOnTouchListener(null);
        sheet.heading.setTextSize(16);sheet.heading.setTypeface(weight(650));sheet.heading.setMaxLines(2);sheet.heading.setEllipsize(TextUtils.TruncateAt.END);
        sheet.heading.setContentDescription(sheet.heading.getText());
        if(sheet.subheading!=null) {sheet.subheading.setTextSize(11.5f);sheet.subheading.setMaxLines(2);sheet.subheading.setEllipsize(TextUtils.TruncateAt.END);}
        sheet.closeButton.setBackgroundColor(Color.TRANSPARENT);sheet.closeButton.setImageTintList(ColorStateList.valueOf(TEXT));
        sheet.closeButton.setPadding(ui.dp(10),ui.dp(10),ui.dp(10),ui.dp(10));sheet.closeButton.setContentDescription(sheet.heading.getText()+" 닫기");
        sheet.controls.setPadding(ui.dp(10),0,ui.dp(10),ui.dp(6));sheet.body.setPadding(ui.dp(6),0,ui.dp(6),ui.dp(6));
        sheet.footer.setPadding(ui.dp(10),ui.dp(6),ui.dp(10),ui.dp(10));
        sheet.footer.setVisibility(applyAction?View.VISIBLE:View.GONE);
        sheet.primary.setTextSize(14.5f);sheet.primary.setMinHeight(ui.dp(42));sheet.primary.setMinimumHeight(ui.dp(42));sheet.primary.setBackground(ui.shape(ACCENT,12));
        if(applyAction)sheet.scrollControlsWithBody();
        styleControls(ui,sheet.controls);styleTree(ui,sheet.body);
        sheet.body.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            public void onChildViewAdded(View parent,View child) {styleControls(ui,sheet.controls);styleTree(ui,child);}
            public void onChildViewRemoved(View parent,View child) {}
        });
    }
    private static void styleTree(Ui ui,View view) {
        Ui.RowParts parts=view instanceof Ui.ListRow row?row.parts:null;
        if(parts!=null) {
            view.setMinimumHeight(ui.dp(42));view.setPadding(ui.dp(12),ui.dp(7),ui.dp(10),ui.dp(7));
            view.setBackground(ui.shape(view.isSelected()?ACCENT_SOFT:Color.TRANSPARENT,10));
            parts.title().setTextSize(14.5f);parts.title().setTypeface(weight(view.isSelected()?600:450));
            if(parts.subtitle()!=null){parts.subtitle().setTextSize(11.5f);parts.subtitle().setSingleLine(false);parts.subtitle().setMaxLines(2);parts.subtitle().setEllipsize(TextUtils.TruncateAt.END);}
            if(parts.tile()!=null) {
                parts.tile().setBackgroundColor(Color.TRANSPARENT);
                LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)parts.tile().getLayoutParams();lp.width=lp.height=ui.dp(20);lp.rightMargin=ui.dp(12);parts.tile().setLayoutParams(lp);
                parts.icon().setImageTintList(ColorStateList.valueOf(parts.tint()==ERROR?ERROR:view.isSelected()?ACCENT:MUTED));
            }
            if(parts.trailing() instanceof TextView label&&!label.getText().toString().isBlank()) {label.setTextColor(ACCENT);label.setBackgroundColor(Color.TRANSPARENT);label.setTextSize(12);}
            return;
        }
        if(ui.listGroups.contains(view))view.setBackgroundColor(Color.TRANSPARENT);
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++) {
            View child=group.getChildAt(i);
            if(ui.listGroups.contains(group)&&!(child instanceof ViewGroup)&&!(child instanceof TextView)&&child.getLayoutParams() instanceof LinearLayout.LayoutParams line) {line.leftMargin=ui.dp(44);line.rightMargin=ui.dp(12);child.setLayoutParams(line);}
            styleTree(ui,child);
        }
    }
    private static void styleControls(Ui ui,View view) {
        if(view instanceof EditText search) {search.setTextSize(14);search.setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10));search.setBackground(ui.shape(FILL,10));search.setMinimumHeight(ui.dp(44));}
        else if(view instanceof Button button) {
            button.setTextSize(14);button.setMinHeight(ui.dp(44));button.setMinimumHeight(ui.dp(44));button.setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8));
            StateListDrawable background=new StateListDrawable();background.addState(new int[]{android.R.attr.state_selected},ui.shape(ACCENT_SOFT,10));background.addState(new int[]{},ui.shape(Color.TRANSPARENT,10));button.setBackground(background);
            button.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_selected},new int[]{}},new int[]{ACCENT,MUTED}));
        }
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)styleControls(ui,group.getChildAt(i));
    }
    /** Pure bounds policy, also checked against narrow, IME and enlarged-text fixtures. */
    static Rect placement(Rect available,Rect anchor,int desiredWidth,int measuredHeight,boolean anchored,int gap) {
        int width=Math.max(1,Math.min(desiredWidth,available.width()-gap*2));
        int height=Math.max(1,Math.min(measuredHeight,available.height()-gap*2));
        int x=anchored?Math.max(available.left+gap,Math.min(anchor.right-width,available.right-gap-width)):available.left+(available.width()-width)/2;
        int y=available.bottom-gap-height;
        if(anchored) {
            int below=anchor.bottom+gap,above=anchor.top-gap-height;
            y=below+height<=available.bottom-gap?below:above>=available.top+gap?above:available.top+gap;
        }
        return new Rect(x,y,x+width,y+height);
    }
    static Rect measureBounds(Ui.Sheet sheet,Rect available,Rect anchor,int desiredWidth,boolean anchored,int gap) {
        return measureBounds(sheet,available,anchor,desiredWidth,anchored,gap,available.height()-gap*2);
    }
    static Rect measureBounds(Ui.Sheet sheet,Rect available,Rect anchor,int desiredWidth,boolean anchored,int gap,int heightLimit) {
        int width=Math.max(1,Math.min(desiredWidth,available.width()-gap*2));
        int cap=Math.max(1,Math.min(heightLimit,available.height()-gap*2));sheet.viewportHeightLimit=cap;
        // TextView.setMaxLines requests layout even for an unchanged value. Do not
        // reset compact chrome on every global-layout callback (or oscillate 1/2 lines).
        String policy=width+":"+available.height()+":"+heightLimit+":"+sheet.heading.getResources().getConfiguration().fontScale+":"+sheet.heading.getResources().getDisplayMetrics().density+":"+sheet.heading.getText();
        if(!policy.equals(sheet.heading.getTag())) {
            sheet.heading.setTag(policy);
            if(sheet.heading.getMaxLines()!=2)sheet.heading.setMaxLines(2);
            if(sheet.subheading!=null)sheet.subheading.setVisibility(View.VISIBLE);
        }
        sheet.panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(cap,View.MeasureSpec.AT_MOST));
        // At very short IME heights/large font, reserve one reachable content target
        // after fixed chrome. Prefer the compact cap, but never hide all scroll content.
        int minimumContent=Math.round(48*sheet.scroll.getResources().getDisplayMetrics().density);
        int missing=Math.max(0,minimumContent-sheet.scroll.getMeasuredHeight());
        int usableCap=Math.min(available.height()-gap*2,cap+missing);
        if(usableCap>cap) {
            cap=usableCap;sheet.viewportHeightLimit=cap;
            sheet.panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(cap,View.MeasureSpec.AT_MOST));
        }
        if(sheet.scroll.getMeasuredHeight()<minimumContent) {
            // Very short IME + large text: keep the close/action buttons and one
            // content target rather than spending all space on supplementary text.
            // The complete title remains available to accessibility and tooltip.
            if(sheet.heading.getMaxLines()!=1)sheet.heading.setMaxLines(1);sheet.heading.setTooltipText(sheet.heading.getText());
            if(sheet.subheading!=null)sheet.subheading.setVisibility(View.GONE);
            sheet.panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(cap,View.MeasureSpec.AT_MOST));
        }
        return placement(available,anchor,width,sheet.panel.getMeasuredHeight(),anchored,gap);
    }
    private static void position(Ui ui,Ui.Sheet sheet,View anchor,boolean applyAction) {
        Window window=sheet.getWindow();if(window==null||!sheet.isShowing())return;
        WindowMetrics metrics=window.getWindowManager().getCurrentWindowMetrics();Rect available=new Rect(metrics.getBounds());
        Insets bars=metrics.getWindowInsets().getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
        available.inset(bars.left,bars.top,bars.right,bars.bottom);
        boolean keyboard=metrics.getWindowInsets().isVisible(WindowInsets.Type.ime());
        Rect target=new Rect();boolean attached=anchor!=null&&anchor.isAttachedToWindow()&&anchor.getGlobalVisibleRect(target);
        // At its button on a phone too, as Chrome's menu drops from its ⋯ (준: "팝업메뉴는 쓸데없이 큼직하고 컴팩트하지도 않아");
        // across the bottom only while typing (the keyboard) or with very large text.
        boolean anchored=attached&&!keyboard&&anchor.getResources().getConfiguration().fontScale<1.6f;
        boolean mobile=available.width()<ui.dp(680);
        int gap=ui.dp(mobile?8:8);
        // Only the body scrolls; the close/action controls remain reachable, including enlarged text.
        int width=mobile?(anchored?Math.min(ui.dp(applyAction?340:296),Math.round(available.width()*.94f)):Math.min(ui.dp(360),Math.round(available.width()*.92f)))
                :Math.min(ui.dp(anchored?(applyAction?400:320):480),available.width()-gap*2);
        int heightLimit=mobile?Math.min(ui.dp(600),Math.round(available.height()*(anchored?.80f:.70f))):available.height()-gap*2;
        Rect bounds=measureBounds(sheet,available,target,width,anchored,gap,heightLimit);
        WindowManager.LayoutParams p=window.getAttributes();
        int gravity=Gravity.TOP|Gravity.LEFT;
        float dim=anchored?.12f:.18f;
        if(p.width!=bounds.width()||p.gravity!=gravity||p.x!=bounds.left||p.y!=bounds.top||p.dimAmount!=dim) {p.width=bounds.width();p.height=WindowManager.LayoutParams.WRAP_CONTENT;p.gravity=gravity;p.x=bounds.left;p.y=bounds.top;p.dimAmount=dim;window.setAttributes(p);}
    }
}
