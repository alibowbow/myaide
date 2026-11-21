package dev.jun.gyeot.core;

import java.util.List;
import java.util.Set;

public final class SecurityRegression {
    private static void check(boolean yes, String message) {
        if (!yes) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var shared = "트레이딩뷰 할인 https://promo.example/discount";
        var caution = SecurityLink.inspect(SecurityLink.first(shared), shared);
        check(caution.official().equals("tradingview.com") && !caution.block(), "shared brand mismatch");
        check(SecurityLink.inspect("https://tradingview.com.deal.example","").block(), "brand boundary");
        check(SecurityLink.inspect("https://upbit.com@offer.example","").block(), "userinfo");
        check(!SecurityLink.inspect("https://kr.tradingview.com/","").block(), "official subdomain");
        check(SecurityLink.inspect("https://kr.tradingview.com/","").official().equals("tradingview.com"), "official origin");
        check(SecurityLink.inspect("https://upbit.com","TradingView").official().equals("upbit.com"), "origin beats title");
        check(SecurityLink.inspect("https://example.org/app.APK?code=x","").reason().contains("설치 파일"), "APK extension");
        check(SecurityLink.inspect("http://example.net/","").reason().contains("암호화"), "HTTP");
        check(SecurityLink.first("a https://example.net/b). c").equals("https://example.net/b"), "shared punctuation");
        check(SecurityLink.inspect("https://예시.한국/","").host().startsWith("xn--"), "international domain");
        check(!SecurityLink.inspect(null,"").block(), "empty Gecko target");
        var store = new PhoneRisk.App("org.example","앱",false,"com.android.vending",true,false,false,true,Set.of(),false);
        var unknown = new PhoneRisk.App("org.unknown","출처 미확인",false,"",false,false,false,false,Set.of(),false);
        var findings = PhoneRisk.apps(List.of(store,unknown),false);
        check(findings.stream().anyMatch(f->f.level()==PhoneRisk.Level.CAUTION && f.action().equals("app:org.example")), "store app notification access");
        check(findings.stream().noneMatch(f->f.level()==PhoneRisk.Level.DANGER), "unknown installer is not evidence of malware");
        var device = new PhoneRisk.Device(true,"",0,false,false,false,-1,"");
        check(PhoneRisk.device(device).stream().anyMatch(f->f.level()==PhoneRisk.Level.INFO && f.action().contains("DEVELOPMENT_SETTINGS")), "ADB unavailable");
        System.out.println("Phone security regression checks passed");
    }
}
