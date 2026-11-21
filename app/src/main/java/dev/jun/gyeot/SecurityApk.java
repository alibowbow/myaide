package dev.jun.gyeot;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import java.io.*;
import java.security.*;
import java.util.*;

/** Inspect only the document the user chose. No code from the archive is executed. */
final class SecurityApk {
    record Report(String id,String detail) {}
    private static final long LIMIT=512L * 1024*1024;
    static Report read(Context c,Uri uri) throws Exception {
        File temp=File.createTempFile("selected-", ".apk", c.getCacheDir());
        try {
            try (InputStream in=c.getContentResolver().openInputStream(uri);
                 OutputStream out=new FileOutputStream(temp)) {
                if(in==null) throw new IOException("파일을 열 수 없어요.");
                byte[] buf=new byte[65536]; long total=0; int count;
                while ((count=in.read(buf))!=-1) {
                    if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    total+=count;
                    if(total>LIMIT) throw new IOException("선택 검사는 512MB 이하의 단일 APK를 지원해요.");
                    out.write(buf,0,count);
                }
            }
            PackageManager pm=c.getPackageManager();
            var flags=PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo archive=pm.getPackageArchiveInfo(temp.getAbsolutePath(),flags);
            if(archive==null || archive.signingInfo==null) throw new IOException("서명을 읽을 수 있는 단일 APK를 선택해 주세요.");
            Set<String> certs=signers(archive);
            String installed="동일 패키지의 설치본을 확인하지 못했어요.";
            try {
                PackageInfo pkg=pm.getPackageInfo(archive.packageName,flags);
                installed="설치 버전 "+pkg.versionName+" · "+(!certs.isEmpty() && certs.equals(signers(pkg)) ? "현재 서명 일치" : "현재 서명 다름 또는 미확인");
            } catch (PackageManager.NameNotFoundException invisible) {}
            return new Report(archive.packageName,"버전 "+archive.versionName+"\n"+installed+"\n인증서 SHA-256\n"+String.join("\n",certs)+"\n서명 일치는 같은 서명자에 대한 정보예요.");
        } finally { if (!temp.delete()) temp.deleteOnExit(); }
    }
    private static Set<String> signers(PackageInfo p) throws GeneralSecurityException {
        Set<String> out=new TreeSet<>();
        if(p.signingInfo!=null) for(android.content.pm.Signature s:p.signingInfo.getApkContentsSigners()) {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
            StringBuilder value=new StringBuilder(); for(byte b:bytes) value.append(String.format(Locale.ROOT,"%02x",b & 255)); out.add(value.toString());
        }
        return out;
    }
}
