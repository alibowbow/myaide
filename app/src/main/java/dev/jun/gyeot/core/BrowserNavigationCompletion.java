package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.*;
import static dev.jun.gyeot.core.Jsons.*;

/** Exact, read-only navigation postconditions. Never proves a summary, submission or artifact. */
public final class BrowserNavigationCompletion {
    private BrowserNavigationCompletion() {}

    public static JSONObject arrival(JSONObject before, JSONObject action, JSONObject after) {
        if(before==null || action==null || after==null || !"open".equals(action.optString("action"))
                || !action.optBoolean("navigation_only") || !after.optBoolean("navigation_performed")
                || !valid(after,after.optString("document"))
                || before.optString("document").equals(after.optString("document"))
                || !BrowserReadNavigation.allowed(before,action.optString("document"),action.optString("url"))
                || !action.optString("url").equals(after.optString("url"))) return null;
        JSONArray elements=before.optJSONArray("elements");
        for(int i=0;elements!=null && i<elements.length();i++) {
            JSONObject e=elements.optJSONObject(i);
            if(e!=null && action.optString("ref").equals(e.optString("ref")) && !e.optBoolean("disabled")
                    && action.optString("url").equals(e.optString("href")))
                return obj("label",e.optString("label"),"destination",after.optString("url"),"document",after.optString("document"));
        }
        return null;
    }

    /** A routine (tier 1) click on a control, with its observed effect. Never proof by itself:
     * {@link #prove} also requires the whole command to name exactly this control and a current, unblocked page. */
    public static JSONObject clicked(JSONObject before, JSONObject action, JSONObject after) {
        if(before==null || action==null || after==null || !"click".equals(action.optString("action"))
                || !valid(after,after.optString("document")) || Boolean.FALSE.equals(after.opt("action_executed"))) return null;
        JSONObject effect=after.optJSONObject("action_effect");
        String kind=effect==null ? "" : effect.optString("kind");
        if(!Set.of("navigation","content_changed","control_changed").contains(kind))return null;
        JSONArray elements=before.optJSONArray("elements");
        for(int i=0;elements!=null && i<elements.length();i++) {
            JSONObject e=elements.optJSONObject(i);
            if(e!=null && action.optString("ref").equals(e.optString("ref")) && !e.optBoolean("disabled") && !e.optString("label").isBlank())
                return obj("label",e.optString("label"),"document",after.optString("document"),"url",after.optString("url"),
                    "before_url",before.optString("url"),"before_document",before.optString("document"),"effect",kind);
        }
        return null;
    }

    public static JSONObject prove(String request,String goalKind,JSONObject page,String document,JSONObject arrival) {
        return prove(request,goalKind,page,document,arrival,null);
    }
    /** Called with active account/lease guards; goalKind comes from the user's own words.
     * A complete whole-command match prevents navigation from completing a larger task. */
    public static JSONObject prove(String request,String goalKind,JSONObject page,String document,JSONObject arrival,JSONObject click) {
        if(!Set.of("read","state").contains(goalKind) || !valid(page,document))return null;
        if(arrival!=null && document.equals(arrival.optString("document"))
                && page.optString("url").equals(arrival.optString("destination"))
                && onlyOpen(request,arrival.optString("label")))
            return obj("label",arrival.optString("label"),"basis","verified_public_get_arrival");
        JSONArray elements=page.optJSONArray("elements");
        JSONObject matched=null; int matches=0;
        String clickedLabel=click!=null && document.equals(click.optString("document")) && onlyOpen(request,click.optString("label"))
            ? click.optString("label") : "";
        JSONObject hinted=null; int hints=0;
        for(int i=0;i<elements.length();i++) {
            JSONObject e=elements.optJSONObject(i);
            if(e==null || e.optBoolean("disabled") || !onlyOpen(request,e.optString("label")))continue;
            // Labels and CSS 'active' hints alone are not state evidence.
            boolean atDestination=BrowserReadNavigation.safeHref(e.optString("href")) && e.optString("href").equals(page.optString("url"));
            boolean selectedTab="tab".equals(e.optString("role")) && e.optBoolean("selected");
            boolean current=e.optBoolean("current") && Set.of("a","button").contains(e.optString("tag"));
            if(atDestination || selectedTab || current) {
                matched=obj("label",e.optString("label"),"basis",atDestination?"observed_current_destination":"observed_selected_control");
                matches++;
            }
            // After the routine click on exactly this control, its selection hint corroborates the observed effect.
            if(!clickedLabel.isEmpty() && clickedLabel.equals(e.optString("label")) && (e.optBoolean("selection_hint") || e.optBoolean("selected") || e.optBoolean("current"))) {
                hinted=obj("label",clickedLabel,"basis","approved_tab_click_selected");
                hints++;
            }
        }
        if(matches==1)return matched;
        if(matches==0 && hints==1)return hinted;
        // A click on the requested control that loaded a different public page is the navigation itself.
        if(matches==0 && !clickedLabel.isEmpty() && "navigation".equals(click.optString("effect"))
                && !click.optString("before_url").equals(click.optString("url")) && page.optString("url").equals(click.optString("url"))
                && !click.optString("before_document").equals(document))
            return obj("label",clickedLabel,"basis","approved_tab_click_navigation");
        return null;
    }

    public static boolean onlyOpen(String request,String label) {
        if(request==null || label==null || label.isBlank() || label.length()>120
                || !label.equals(DecisionSemanticText.redact(label)))return false;
        String goal=request.strip();
        if(goal.startsWith("{"))try {
            JSONObject envelope=new JSONObject(goal);
            if(!"finalized_voice".equals(envelope.optString("source")))return false;
            goal=envelope.optString("original_user_instruction");
        }catch(Exception invalid){return false;}
        // Multi-turn envelopes and extra constraints stay with semantic completion.
        if(goal.startsWith("[") || goal.length()>300)return false;
        goal=goal.replaceFirst("^(?:자[,，]?\\s+)?(?:여기서\\s+)?","");
        String text=compact(goal).replaceAll("[.!?。]+$","");
        String name=Pattern.quote(compact(label));
        return text.matches(name+"(?:탭|페이지|화면)?(?:으로|로|에|을|를)?(?:들어가(?:줘|주세요|죠|자|요|기)?|열어(?:줘|주세요|봐|요)?|열기|접속(?:해줘|해주세요|하자|해)?|이동(?:해줘|해주세요|하자|해)?|선택(?:해줘|해주세요|해)?|보여(?:줘|주세요))")
            || text.matches("(?:please)?(?:open|show|goto|navigateto)(?:the)?"+name+"(?:tab|page)?(?:please)?");
    }

    private static String compact(String value) {return value.replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
    private static boolean valid(JSONObject page,String document) {
        if(page==null || document==null || document.isBlank() || !document.equals(page.optString("document"))
                || !page.optBoolean("ok") || page.optBoolean("requires_user") || page.optBoolean("action_may_have_run")
                || !page.optString("error").isBlank() || !page.optString("error_code").isBlank()
                || page.optJSONArray("elements")==null || !BrowserReadNavigation.safeHref(page.optString("url")))return false;
        JSONObject activity=page.optJSONObject("activity"),settle=page.optJSONObject("settle");
        return (activity==null || !activity.optBoolean("document_loading"))
            && (settle==null || !"pending".equals(settle.optString("state")));
    }
}
