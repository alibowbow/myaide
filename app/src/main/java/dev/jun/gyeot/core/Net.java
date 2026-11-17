package dev.jun.gyeot.core;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Korean, actionable text for failed HTTPS calls (OpenAI sign-in and requests, update checks). */
public final class Net {
    private Net() {}
    public static String explain(Throwable error) {
        if (error instanceof UnknownHostException) {
            if (String.valueOf(error.getMessage()).contains(DirectHttps.PUBLIC_DNS_FAILED))
                return "‘" + host(error.getMessage()) + "’ 주소를 휴대폰 DNS와 공개 DNS(1.1.1.1·8.8.8.8) 모두에서 찾지 못했어요. 인터넷 연결을 확인하고, 광고 차단 앱·VPN·방화벽이 이 주소나 공개 DNS를 막고 있지 않은지 확인해 주세요.";
            // The rest of the internet usually still works here: a DNS filter, VPN or network policy is hiding this one host.
            return "‘" + host(error.getMessage()) + "’ 주소를 찾지 못했어요. 인터넷 연결을 확인하고, 비공개 DNS·광고 차단 앱·VPN이나 회사·학교 와이파이가 이 주소를 막고 있지 않은지 확인해 주세요.";
        }
        if (error instanceof SocketTimeoutException) return "응답이 너무 늦어요. 네트워크를 확인하고 잠시 후 다시 시도해 주세요.";
        if (error instanceof SSLException) return "보안 연결을 만들지 못했어요. 기기 날짜·시간과 VPN·프록시 설정을 확인해 주세요.";
        if (error instanceof ConnectException) return "서버에 연결하지 못했어요. 네트워크를 확인해 주세요.";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
    /** Host from Android's "Unable to resolve host \"x\": ..." or the JVM's "x: Name or service not known". */
    static String host(String message) {
        if (message == null || message.isBlank()) return "서버";
        int open = message.indexOf('"'), close = open < 0 ? -1 : message.indexOf('"', open + 1);
        if (open >= 0 && close > open) return message.substring(open + 1, close);
        int colon = message.indexOf(':');
        return (colon > 0 ? message.substring(0, colon) : message).strip();
    }
}
