package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.regex.Pattern;
import org.json.*;
import static dev.jun.gyeot.core.Jsons.*;

/** The local phone open-app route: a single "open X" command and its one exact installed app. No page text or credentials are inputs. */
public final class DecisionRouting {
    private DecisionRouting() {}
    private static final Pattern UNSAFE = Pattern.compile("삭제|지워|결제|구매|송금|보내|전송|게시|업로드|로그인|인증|비밀번호|권한|설치|다운로드|설정|취소|멈춰|중지|하지마|말고|않|안\\s|뒤로|새로고침|https?[:/]|www\\.|[.@:/?=&]|\\b(delete|pay|buy|send|post|login|password|permission|install|download|cancel|stop|back|not|don't|refresh)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPEN = Pattern.compile("^(?:저기\\s*|좀\\s*|제발\\s*|please\\s+)?([가-힣a-zA-Z0-9 _-]{1,45}?)(?:\\s*앱)?(?:을|를|에)?\\s*(?:열어\\s*줘|열어주세요|켜\\s*줘|켜주세요|실행해\\s*줘|실행해주세요|들어가\\s*줘)[.!?。]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENGLISH_OPEN = Pattern.compile("^(?:please\\s+)?(?:open|launch)\\s+([a-zA-Z0-9 _-]{1,45})(?:\\s+app)?[.!?]*$", Pattern.CASE_INSENSITIVE);
    public static String appQuery(String request) {
        if (request == null || request.length()>100 || UNSAFE.matcher(request).find()) return "";
        if (AppNavigation.browserScreenRequest(request) || !AppNavigation.destination(request).isEmpty()
                || AppNavigation.galleryScope(request)!=AppNavigation.GalleryScope.NONE) return "";
        var match=OPEN.matcher(request.trim()); if(!match.matches()){match=ENGLISH_OPEN.matcher(request.trim());if(!match.matches())return "";}
        String name=match.group(1).trim();
        if(name.matches(".*(?:하고|해서|그리고|다음|then|and|after).*"))return "";
        return name;
    }
    private static String appName(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replace("유튜브", "youtube").replace("카카오톡", "kakaotalk")
            .replace("인스타그램", "instagram").replace("크롬", "chrome").replace("네이버", "naver").replace("스포티파이", "spotify");
    }
    /** Deterministic proposal only for one exact normalized installed label. Duplicate names remain ambiguous. */
    public static JSONObject uniqueExactApp(JSONArray candidates,String query) {
        String wanted=appName(query);JSONObject found=null;
        if(wanted.length()<2)return null;
        for(int i=0;i<candidates.length();i++){
            JSONObject candidate=candidates.optJSONObject(i);
            if(candidate==null||!appName(candidate.optString("label")).equals(wanted))continue;
            if(found!=null)return null;
            found=candidate;
        }
        return found;
    }
    /** Installed launcher apps only; no custom intents, package strings from model, own app or system settings. */
    public static JSONArray appCandidates(JSONObject listing, String ownPackage) {
        JSONArray result=arr(); if(listing==null||!listing.optBoolean("ok"))return result;
        JSONArray apps=listing.optJSONArray("apps");if(apps==null)return result;
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<apps.length()&&i<60;i++){
            JSONObject app=apps.optJSONObject(i);if(app==null)continue;
            String pkg=app.optString("package"),label=app.optString("label");
            if(pkg.equals(ownPackage)||app.has("navigation_tool")||!pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")
                    ||pkg.toLowerCase(Locale.ROOT).matches(".*(?:settings|installer|permissioncontroller|systemui|keychain).*"))continue;
            if(label.isBlank()||label.length()>60||label.contains("\n")||label.contains("\r")||!seen.add(pkg))continue;
            result.put(obj("id","app_"+result.length(),"label",label,"package",pkg));
        }return result;
    }
}
