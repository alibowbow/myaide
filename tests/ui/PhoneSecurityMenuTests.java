package dev.jun.gyeot;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class PhoneSecurityMenuTests {
    private View find(View view,Predicate<View> match) {
        if(match.test(view)) return view;
        if(view instanceof ViewGroup g) for(int i=0;i<g.getChildCount();i++) {
            View child=find(g.getChildAt(i),match); if(child!=null) return child;
        }
        return null;
    }
    private View word(PhoneSecurityActivity a,String label) {
        return find(a.getWindow().getDecorView(),v->v instanceof TextView t && t.getText().toString().equals(label));
    }
    private void tap(PhoneSecurityActivity a,String label) {
        View v=word(a,label); assertNotNull(label,v);
        while(!v.isClickable() && v.getParent() instanceof View parent) v=parent;
        assertTrue(label,v.performClick());
    }
    private void layout(PhoneSecurityActivity a) {
        View decor=a.getWindow().getDecorView();
        var metrics=a.getResources().getDisplayMetrics();
        decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels,View.MeasureSpec.EXACTLY));
        decor.layout(0,0,decor.getMeasuredWidth(),decor.getMeasuredHeight());
    }
    private void checkLink() {
        var a=Robolectric.buildActivity(PhoneSecurityActivity.class).setup().get();
        tap(a,"링크 검사"); layout(a);
        EditText edit=(EditText)find(a.getWindow().getDecorView(),v->v instanceof EditText);
        assertNotNull(edit);
        assertTrue(edit.getWidth()>a.getWindow().getDecorView().getWidth()*0.7);
        edit.setText("https://kr.tradingview.com/");
        tap(a,"검사하기"); assertNotNull(word(a,"공식 도메인 일치")); assertNotNull(word(a,"kr.tradingview.com"));
        edit.setText("https://tradingview.example/"); assertNull(word(a,"공식 도메인 일치"));
        tap(a,"검사하기"); assertNotNull(word(a,"오인 또는 사칭 의심"));
    }
    @Test @Config(qualifiers="w360dp-h780dp") public void coverScreenLink() { checkLink(); }
    @Test @Config(qualifiers="w720dp-h720dp-night") public void unfoldedLink() { checkLink(); }
    @Test public void apkPickerOnlyRequestsSingleApks() {
        Intent pick=PhoneSecurityActivity.apkPicker();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,pick.getAction());
        assertEquals("application/vnd.android.package-archive",pick.getType());
        assertTrue(SecurityDownloads.apkName("setup.APK")); assertFalse(SecurityDownloads.apkName("setup.apk.pdf"));
    }
    @Test public void watchShowsSwitchAndVisibleState() {
        var a=Robolectric.buildActivity(PhoneSecurityActivity.class).setup().get();
        Switch toggle=(Switch)find(a.getWindow().getDecorView(),v->v instanceof Switch);
        assertFalse(toggle.isChecked()); assertEquals("꺼짐",toggle.getText().toString().trim());
        SecurityWatch.prefs(a).edit().putBoolean("watch",true).commit();
        tap(a,"금융 피해 대응 안내"); a.onBackPressed();
        toggle=(Switch)find(a.getWindow().getDecorView(),v->v instanceof Switch);
        assertTrue(toggle.isChecked()); assertEquals("켜짐",toggle.getText().toString().trim());
    }
    @Test @Config(qualifiers="w720dp-h720dp") public void browsingAnAppCanBeReversedWithoutChangingIt() throws Exception {
        var a=Robolectric.buildActivity(PhoneSecurityActivity.class).setup().get();
        PackageInfo pkg=new PackageInfo(); pkg.packageName="example.utility"; pkg.applicationInfo=new ApplicationInfo();
        pkg.applicationInfo.packageName=pkg.packageName; pkg.applicationInfo.nonLocalizedLabel="테스트 유틸리티";
        shadowOf(a.getPackageManager()).installPackage(pkg);
        tap(a,"금융 피해 대응 안내"); tap(a,"앱 점검 결과 보기");
        await(a,"전체 앱"); tap(a,"전체 앱"); layout(a);
        ListView rows=(ListView)find(a.getWindow().getDecorView(),v->v instanceof ListView);
        assertNotNull(rows);
        for(int i=1;i<rows.getCount();i++) {
            View item=rows.getAdapter().getView(i,null,rows);
            if(find(item,v->v instanceof TextView t && t.getText().toString().equals("테스트 유틸리티"))!=null) {
                rows.performItemClick(item,i,rows.getAdapter().getItemId(i)); break;
            }
        }
        assertNotNull(word(a,"현재 차단 안 함")); assertTrue(AppFirewall.blocked().isEmpty());
        a.onBackPressed(); await(a,"전체 앱"); a.onBackPressed(); assertNotNull(word(a,"금융 피해 대응 안내"));
    }
    private void await(PhoneSecurityActivity a,String label) throws Exception {
        for(int i=0;i<150 && word(a,label)==null;i++) {
            shadowOf(Looper.getMainLooper()).idle(); layout(a); Thread.sleep(20);
        }
        assertNotNull(label,word(a,label));
    }
}
