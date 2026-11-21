package dev.jun.gyeot.core;

import java.net.IDN;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local URL checks. Never resolves or visits the destination. */
public final class SecurityLink {
    private SecurityLink() {}
    public record Result(String host, String reason, String official, boolean block) {}
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s<>\"\\p{Cntrl}]+");
    private static final List<String> BRANDS = List.of("tradingview.com", "upbit.com", "binance.com");

    public static String first(String input) {
        Matcher match = URL.matcher(input == null ? "" : input);
        return match.find() ? match.group().replaceAll("[)\\]}>.,]+$", "") : "";
    }

    public static Result inspect(String raw, String context) {
        try {
            URI uri = new URI(raw);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            if ((!https && !"http".equalsIgnoreCase(uri.getScheme())) || uri.getRawAuthority() == null)
                return new Result("", "HTTP 또는 HTTPS 주소를 확인해 주세요.", "", false);
            String authority = uri.getRawAuthority();
            if (authority.contains("@") || authority.contains("%"))
                return new Result("", "실제 목적지를 오인할 수 있는 주소 표기예요.", "", true);
            String host = uri.getHost();
            if (host == null) {
                String ascii = IDN.toASCII(authority.replaceFirst(":[0-9]+$", ""), IDN.USE_STD3_ASCII_RULES);
                host = new URI("https://" + ascii).getHost();
            }
            if (host == null) throw new IllegalArgumentException();
            host = host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
            for (String official : BRANDS) {
                if (host.equals(official) || host.endsWith("." + official)) {
                    String reason = https ? "공식 도메인과 일치해요. 계정 정보나 결제 요청은 따로 확인하세요." : "암호화되지 않은 연결이에요.";
                    return new Result(host, reason, official, false);
                }
            }
            for (String official : BRANDS) {
                String brand = official.substring(0, official.indexOf('.'));
                if (host.contains(brand))
                    return new Result(host, "금융 서비스 이름을 사용하지만 공식 도메인과 달라요.", official, true);
            }
            if (PhoneRisk.installer(uri.getPath()))
                return new Result(host, "외부 앱 설치 파일 링크예요. 배포자와 서명을 확인하세요.", "", false);
            String title = context == null ? "" : URL.matcher(context).replaceAll(" ").toLowerCase(Locale.ROOT);
            for (String official : BRANDS) {
                String brand = official.substring(0, official.indexOf('.'));
                if (title.contains(brand) || ("tradingview".equals(brand) && title.contains("트레이딩뷰")))
                    return new Result(host, "공유 문구의 금융 서비스와 도메인이 달라요. 공식 사이트에서 혜택을 확인하세요.", official, false);
            }
            return new Result(host, https ? "사칭 규칙과 일치하지 않아요. 단축 주소의 최종 목적지와 페이지 내용은 미확인입니다." : "암호화되지 않은 연결이에요.", "", false);
        } catch (RuntimeException | java.net.URISyntaxException invalid) {
            return new Result("", "주소 형식을 확인할 수 없어요.", "", false);
        }
    }
}
