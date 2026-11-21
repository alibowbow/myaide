package dev.jun.gyeot;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.VpnService;
import android.os.*;
import android.widget.Toast;
import java.io.IOException;
import java.util.*;

/** A local sink for selected UIDs. No external gateway, traffic inspection or forwarding loop. */
public final class AppFirewall extends VpnService {
    static final String ADD="security.block", REMOVE="security.unblock", STOP="security.stop";
    static final String CHANGED="dev.jun.gyeot.FIREWALL_CHANGED";
    private static final String CHANNEL="phone-firewall";
    private static final int NOTIFICATION=34713;
    private static volatile Set<String> active=Set.of();
    private ParcelFileDescriptor tunnel;
    private boolean registered;
    private final BroadcastReceiver removed=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) {
            if (i.getData()!=null && !i.getBooleanExtra(Intent.EXTRA_REPLACING,false))
                update(REMOVE,i.getData().getSchemeSpecificPart());
        }
    };
    static Set<String> blocked() { return active; }
    static boolean eligible(Context c,String id) {
        if (id==null || id.equals(c.getPackageName())) return false;
        try {
            PackageManager pm=c.getPackageManager();
            ApplicationInfo info=pm.getApplicationInfo(id,0);
            @SuppressWarnings("deprecation") String shared=pm.getPackageInfo(id,0).sharedUserId;
            return shared==null && (info.flags & (ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) { return false; }
    }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"앱 인터넷 차단",NotificationManager.IMPORTANCE_LOW));
        IntentFilter filter=new IntentFilter(Intent.ACTION_PACKAGE_REMOVED); filter.addDataScheme("package");
        registerReceiver(removed,filter,Context.RECEIVER_NOT_EXPORTED); registered=true;
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        startForeground(NOTIFICATION,notification(active.size()));
        String action=intent==null ? STOP : intent.getAction();
        if (STOP.equals(action) || intent==null) finishTunnel();
        else update(action,intent.getStringExtra("package"));
        return START_NOT_STICKY;
    }
    private void update(String action,String id) {
        Set<String> next=new TreeSet<>(active);
        next.removeIf(p -> !eligible(this,p));
        if (ADD.equals(action) && eligible(this,id)) next.add(id);
        else if (REMOVE.equals(action) && id!=null) next.remove(id);
        if (next.isEmpty()) { finishTunnel(); return; }
        if (next.equals(active) && tunnel!=null) return;
        ParcelFileDescriptor replacement=null;
        try {
            // An empty allowed-app list would route every app. Establish only after ALL selected packages resolve.
            Builder builder=new Builder().setSession("들퍼레 · 선택한 앱 차단").setMtu(1500)
                    .addAddress("192.0.2.1",32).addRoute("0.0.0.0",0)
                    .addAddress("2001:db8::1",128).addRoute("::",0)
                    .addDnsServer("192.0.2.2");
            for (String pkg:next) builder.addAllowedApplication(pkg);
            replacement=builder.establish();
            if (replacement==null) throw new IllegalStateException("VPN 승인이 해제됐어요");
            ParcelFileDescriptor previous=tunnel; tunnel=replacement; replacement=null;
            close(previous);
            active=Set.copyOf(next);
            getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(active.size()));
            changed();
        } catch (Exception error) {
            close(replacement);
            Toast.makeText(this,"앱 인터넷 차단을 적용하지 못했어요. VPN 승인과 설치 상태를 확인해 주세요.",Toast.LENGTH_LONG).show();
            if (tunnel==null || VpnService.prepare(this)!=null) finishTunnel(); else changed();
        }
    }
    private Notification notification(int count) {
        PendingIntent open=PendingIntent.getActivity(this,NOTIFICATION,new Intent(this,PhoneSecurityActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,NOTIFICATION,new Intent(this,AppFirewall.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_lock).setContentTitle("들퍼레 앱 인터넷 차단")
                .setContentText(count==0?"적용하는 중…":"선택한 앱 "+count+"개 · Wi-Fi 및 모바일 데이터")
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"차단 해제",stop).build()).build();
    }
    private static void close(ParcelFileDescriptor fd) { if (fd!=null) try { fd.close(); } catch(IOException ignored) {} }
    private void changed() { sendBroadcast(new Intent(CHANGED).setPackage(getPackageName())); }
    private void clear() { close(tunnel); tunnel=null; active=Set.of(); changed(); }
    private void finishTunnel() { clear(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    @Override public void onRevoke() { new Handler(Looper.getMainLooper()).post(this::finishTunnel); }
    @Override public void onDestroy() { if (registered) unregisterReceiver(removed); clear(); super.onDestroy(); }
}
