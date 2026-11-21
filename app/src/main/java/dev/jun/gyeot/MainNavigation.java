package dev.jun.gyeot;

import android.app.*;
import android.content.*;
import android.graphics.Insets;
import android.view.*;
import android.widget.*;
import java.util.function.Consumer;
import static dev.jun.gyeot.Ui.*;

/** Visible app-level navigation, independent of chat actions, drafts and running jobs. */
final class MainNavigation extends LinearLayout {
    static final int RAIL_WIDTH_DP=50;
    static final String TARGET="app_menu_destination";
    static final String[] IDS={"voice","chat","work","board","pages","browser","mytube","phone","gallery","settings"};
    static final String[] LABELS={"음성 비서","메인 대화","작업창","판서","페이지","브라우저","마이튜브","내 휴대폰","갤러리","설정"};
    static final int[] ICONS={R.drawable.ic_wave,R.drawable.ic_chat,R.drawable.ic_work,R.drawable.ic_board,R.drawable.ic_pages,R.drawable.ic_browser,R.drawable.ic_mytube,R.drawable.ic_phone,R.drawable.ic_gallery,R.drawable.ic_settings};
    private final Activity activity;private final Ui ui;private final Consumer<String> route;private final boolean primaryScreen;
    final Button menu;final TextView current;final ScrollView rail;final LinearLayout railItems;
    private final LinearLayout top;private String selected;private boolean wide;private Dialog drawer;private Runnable resize=()->{};
    /** The compact title and hairline appear only once a screen's own large title has scrolled away, as in iOS. */
    private final android.graphics.drawable.GradientDrawable topRule=new android.graphics.drawable.GradientDrawable();private boolean titleShown=true;
    MainNavigation(Activity activity,View content,String selected,Consumer<String> route){this(activity,content,selected,route,true);}
    /** Secondary screens keep their existing toolbar/insets; only tablet navigation is added. */
    MainNavigation(Activity activity,View content,String selected,Consumer<String> route,boolean primaryScreen){
        super(activity);this.activity=activity;this.route=route;this.selected=selected;this.primaryScreen=primaryScreen;ui=new Ui(activity);setOrientation(HORIZONTAL);setBackgroundColor(BG);
        top=ui.row();top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(ui.dp(8),ui.dp(4),ui.dp(12),ui.dp(4));
        // The canvas color with a bottom hairline that fades in only while content scrolls beneath the bar.
        topRule.setColor(SEPARATOR);topRule.setAlpha(0);
        android.graphics.drawable.LayerDrawable topSurface=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{new android.graphics.drawable.ColorDrawable(BG),topRule});
        topSurface.setLayerGravity(1,Gravity.BOTTOM|Gravity.FILL_HORIZONTAL);topSurface.setLayerHeight(1,Math.max(1,ui.dp(.5f)));top.setBackground(topSurface);
        // A quiet icon button, not a filled capsule: the menu is always there but never competes with the screen.
        menu=ui.chip("",this::open);menu.setContentDescription("앱 메뉴 열기");menu.setTooltipText("앱 메뉴");menu.setPadding(ui.dp(12),0,ui.dp(12),0);menu.setBackground(ui.shape(android.graphics.Color.TRANSPARENT,PILL));
        android.graphics.drawable.Drawable menuIcon=activity.getDrawable(R.drawable.ic_menu).mutate();menuIcon.setTint(TEXT);menuIcon.setBounds(0,0,ui.dp(24),ui.dp(24));menu.setCompoundDrawablesRelative(menuIcon,null,null,null);
        menu.setMinHeight(ui.dp(48));
        LayoutParams menuSize=new LayoutParams(ui.dp(48),ui.dp(48));
        menuSize.gravity=Gravity.TOP;top.addView(menu,menuSize);
        current=ui.text(label(selected),HEADLINE,700,TEXT);current.setLetterSpacing(-0.015f);current.setSingleLine(true);current.setEllipsize(android.text.TextUtils.TruncateAt.END);current.setPadding(ui.dp(6),0,0,0);top.addView(current,new LayoutParams(0,-2,1));top.setVisibility(primaryScreen?VISIBLE:GONE);
        LinearLayout column=ui.column();
        rail=new ScrollView(activity);rail.setFillViewport(false);rail.setVerticalScrollBarEnabled(false);
        // Same canvas as the content, divided by a hairline; the open destination is a tonal capsule, not a different panel.
        android.graphics.drawable.GradientDrawable railRule=new android.graphics.drawable.GradientDrawable();railRule.setColor(SEPARATOR);
        android.graphics.drawable.LayerDrawable railSurface=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{new android.graphics.drawable.ColorDrawable(BG),railRule});
        railSurface.setLayerGravity(1,Gravity.END|Gravity.FILL_VERTICAL);railSurface.setLayerWidth(1,Math.max(1,ui.dp(.5f)));rail.setBackground(railSurface);
        railItems=ui.column();railItems.setPadding(0,ui.dp(6),0,ui.dp(8));rail.addView(railItems);addView(rail,new LayoutParams(ui.dp(RAIL_WIDTH_DP),-1));rail.setVisibility(GONE);
        addView(column,new LayoutParams(0,-1,1));column.addView(top,new LayoutParams(-1,-2));
        column.addView(content,new LayoutParams(-1,0,1));renderRail();
        setOnApplyWindowInsetsListener((v,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            // Own horizontal safe areas once, outside the 50dp rail and content; keep full touch targets clear of cutouts.
            setPadding(bars.left,0,bars.right,0);rail.setPadding(0,bars.top,0,Math.max(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom));
            top.setPadding(ui.dp(12),ui.dp(4)+(primaryScreen?bars.top:0),ui.dp(12),ui.dp(4));
            Insets system=insets.getInsets(WindowInsets.Type.systemBars()),cutout=insets.getInsets(WindowInsets.Type.displayCutout());
            // Secondary chrome still receives its original vertical insets; only a primary header consumes the top.
            return new WindowInsets.Builder(insets).setInsets(WindowInsets.Type.systemBars(),Insets.of(0,primaryScreen?0:system.top,0,system.bottom)).setInsets(WindowInsets.Type.displayCutout(),Insets.of(0,primaryScreen?0:cutout.top,0,cutout.bottom)).build();});
    }
    /** Android window configuration, not a permanent physical-device identity or a new user setting. */
    static boolean tablet(Context context){return context.getResources().getConfiguration().smallestScreenWidthDp>=600;}
    static String label(String id){for(int i=0;i<IDS.length;i++)if(IDS[i].equals(id))return LABELS[i];return "들퍼레";}
    void selected(String value){if(selected.equals(value))return;selected=value;current.setText(label(value));updateRailSelection();}
    /** Shows the compact bar title (and the hairline) when the screen's large title is out of view; hides it otherwise. */
    void collapsedTitle(boolean shown){
        if(shown==titleShown)return;titleShown=shown;current.animate().cancel();
        if(android.animation.ValueAnimator.areAnimatorsEnabled())current.animate().alpha(shown?1f:0f).setDuration(180).start();else current.setAlpha(shown?1f:0f);
        current.setImportantForAccessibility(shown?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO);topRule.setAlpha(shown?255:0);top.invalidate();
    }
    /**
     * A screen's own bar in the title's place (작업창's conversation: back, its title, its actions), so the header stays one
     * row over the content instead of a second bar floating on it; null puts the screen name back.
     */
    void bar(View custom){
        if(custom==bar)return;if(bar!=null)top.removeView(bar);bar=custom;
        if(custom!=null){if(custom.getParent() instanceof ViewGroup parent)parent.removeView(custom);top.addView(custom,new LayoutParams(0,-2,1));}
        current.setVisibility(custom==null?VISIBLE:GONE);
    }
    private View bar;
    private static int railScrollDp;
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && wide) rail.post(() -> rail.scrollTo(0, ui.dp(railScrollDp)));
    }
    String selected(){return selected;}boolean wide(){return wide;}void onResize(Runnable callback){resize=callback;}
    // Beside the rail, the ≡ menu would only repeat it: it shows where the rail does not (a phone's width).
    @Override protected void onMeasure(int widthSpec,int heightSpec){boolean next=tablet(activity)||MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight()>=ui.dp(680);if(next!=wide){wide=next;rail.setVisibility(wide&&!immersive?VISIBLE:GONE);menu.setVisibility(wide?GONE:VISIBLE);resize.run();}super.onMeasure(widthSpec,heightSpec);}
    private void renderRail(){
        railItems.removeAllViews();
        for(int i=0;i<IDS.length;i++){
            String id=IDS[i];boolean active=selected.equals(id);
            // Full rail width is clickable: side padding must not shrink 50dp below 48dp at fractional densities.
            LinearLayout item=ui.column();item.setGravity(Gravity.CENTER);item.setMinimumHeight(ui.dp(56));
            ImageView icon=new ImageView(activity);icon.setImageResource(ICONS[i]);icon.setImageTintList(android.content.res.ColorStateList.valueOf(active?ACCENT:MUTED));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(icon,new LayoutParams(ui.dp(22),ui.dp(22)));
            item.setSelected(active);item.setFocusable(true);item.setContentDescription(LABELS[i]+(active?" · 현재 화면":" 열기"));item.setTooltipText(LABELS[i]);
            item.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());info.setSelected(host.isSelected());}});
            item.setBackground(active?railLens():null);item.setOnClickListener(v->choose(id));Ui.pressable(item,false);railItems.addView(item,new LayoutParams(-1,ui.dp(56)));
        }
    }
    /** A 40dp tonal capsule centered in the 50×56 rail target. */
    private android.graphics.drawable.Drawable railLens(){return new android.graphics.drawable.InsetDrawable(ui.lens(14),ui.dp(5),ui.dp(8),ui.dp(5),ui.dp(8));}
    private void updateRailSelection(){
        for(int i=0;i<IDS.length;i++){
            ViewGroup item=(ViewGroup)railItems.getChildAt(i);boolean active=selected.equals(IDS[i]);
            ((ImageView)item.getChildAt(0)).setImageTintList(android.content.res.ColorStateList.valueOf(active?ACCENT:MUTED));
            item.setSelected(active);item.setContentDescription(LABELS[i]+(active?" · 현재 화면":" 열기"));item.setBackground(active?railLens():null);
        }
    }
    void open(){if(drawer!=null&&!drawer.isShowing())drawer=null;if(activity.isFinishing()||activity.isDestroyed()||drawer!=null)return;View focus=activity.getCurrentFocus();if(focus!=null){android.view.inputmethod.InputMethodManager keyboard=activity.getSystemService(android.view.inputmethod.InputMethodManager.class);if(keyboard!=null)keyboard.hideSoftInputFromWindow(focus.getWindowToken(),0);}
        Dialog dialog=new Dialog(activity);drawer=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=new LinearLayout(activity){@Override protected void onMeasure(int widthSpec,int heightSpec){
            int cap=drawerHeightLimit();int mode=MeasureSpec.getMode(heightSpec);int available=mode==MeasureSpec.UNSPECIFIED?cap:Math.min(cap,MeasureSpec.getSize(heightSpec));
            super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(available,MeasureSpec.AT_MOST));
        }};panel.setOrientation(VERTICAL);panel.setBackground(ui.glass(R_CARD,false));panel.setElevation(ui.dp(12));panel.setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));
        LinearLayout heading=ui.row();TextView menuTitle=ui.text("메뉴",TITLE2,740,TEXT);menuTitle.setPadding(ui.dp(10),0,0,0);menuTitle.setAccessibilityHeading(true);heading.addView(menuTitle,new LayoutParams(0,-2,1));ImageView close=ui.iconButton(R.drawable.ic_close,FILL,MUTED,"앱 메뉴 닫기",dialog::dismiss);heading.addView(close,new LayoutParams(ui.dp(48),ui.dp(48)));panel.addView(heading,new LayoutParams(-1,-2));
        ScrollView list=new ScrollView(activity);LinearLayout items=ui.column();items.setPadding(0,ui.dp(12),0,0);list.addView(items);panel.addView(list,new LayoutParams(-1,0,1));
        for(int i=0;i<IDS.length;i++){
            String id=IDS[i];boolean active=id.equals(selected);LinearLayout item=ui.row();item.setMinimumHeight(ui.dp(56));item.setPadding(ui.dp(10),ui.dp(10),ui.dp(10),ui.dp(10));
            ImageView glyph=new ImageView(activity);glyph.setImageResource(ICONS[i]);glyph.setImageTintList(android.content.res.ColorStateList.valueOf(active?ACCENT:MUTED));glyph.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(glyph,new LayoutParams(ui.dp(22),ui.dp(22)));
            TextView label=ui.text(LABELS[i],BODY,active?680:500,active?ACCENT:TEXT);label.setPadding(ui.dp(12),0,ui.dp(8),0);label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(label,new LayoutParams(0,-2,1));
            ImageView check=new ImageView(activity);check.setImageResource(R.drawable.ic_check);check.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));check.setVisibility(active?VISIBLE:INVISIBLE);check.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(check,new LayoutParams(ui.dp(20),ui.dp(20)));
            item.setSelected(active);item.setFocusable(true);item.setContentDescription(LABELS[i]+(active?" · 현재 화면":" 열기"));item.setTooltipText(LABELS[i]);if(active)item.setBackground(ui.shape(ACCENT_SOFT,14));
            item.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());info.setSelected(host.isSelected());}});
            item.setOnClickListener(v->choose(id));Ui.pressable(item,false);items.addView(item,new LayoutParams(-1,-2));
        }
        dialog.setContentView(panel);dialog.setCanceledOnTouchOutside(true);dialog.setOnDismissListener(d->{if(drawer==dialog)drawer=null;});dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));window.setGravity(Gravity.START|Gravity.TOP);window.setLayout(drawerWidth(),-2);WindowManager.LayoutParams attributes=window.getAttributes();attributes.y=ui.dp(12);attributes.dimAmount=.22f;window.setAttributes(attributes);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        panel.setOnApplyWindowInsetsListener((v,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());panel.setPadding(ui.dp(12)+bars.left,ui.dp(12)+bars.top,ui.dp(12)+bars.right,ui.dp(12)+bars.bottom);return insets;});panel.requestApplyInsets();close.requestFocus();
    }
    private int drawerWidth(){int width=getWidth()>0?getWidth():getResources().getDisplayMetrics().widthPixels;return Math.max(1,Math.min(ui.dp(320),Math.round(width*.82f)));}
    private int drawerHeightLimit(){int height=getHeight()>0?getHeight():getResources().getDisplayMetrics().heightPixels;return Math.max(1,Math.min(height-ui.dp(24),Math.round(height*.86f)));}
    private void choose(String id){if (wide) railScrollDp=Math.round(rail.getScrollY()/getResources().getDisplayMetrics().density);close();if(!selected.equals(id)||id.equals("voice"))route.accept(id);}
    private boolean immersive;
    /** 전체화면 (마이튜브's video alone): no rail and no top bar while on; off brings them back as the width has them. */
    void immersive(boolean on){if(immersive==on)return;immersive=on;rail.setVisibility(wide&&!on?VISIBLE:GONE);top.setVisibility(!on&&primaryScreen?VISIBLE:GONE);requestLayout();}
    boolean close(){if(drawer==null)return false;Dialog old=drawer;drawer=null;old.dismiss();return true;}
    @Override protected void onDetachedFromWindow(){close();super.onDetachedFromWindow();}
    static void open(Activity activity,String target){
        boolean primary=java.util.List.of("voice","chat","work","gallery","settings").contains(target);
        if(primary&&activity instanceof MainActivity host){host.navigateMenu(target);return;}
        Class<?> screen=target.equals("browser")?BrowserActivity.class:target.equals("phone")?FileExplorerActivity.class:target.equals("pages")?PagesActivity.class:target.equals("board")?BoardActivity.class:target.equals("mytube")?MyTubeActivity.class:MainActivity.class;
        Intent intent=new Intent(activity,screen).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // The menu and rail are drawn in the same place on every screen: switching between them must not slide the whole
        // window, so only the content appears to change. The browser is full screen without the rail and keeps its transition.
        if(!target.equals("browser"))intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if(screen==MainActivity.class)intent.putExtra(TARGET,target).putExtra("tab",target.equals("work")?3:target.equals("gallery")?4:target.equals("settings")?2:0);
        activity.startActivity(intent);
    }
}
