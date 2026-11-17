package dev.jun.gyeot.core;

import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Observed UI differences, never proof of task completion or permission to replay. */
public final class ActionEffect {
    private ActionEffect() {}
    public static JSONObject browser(JSONObject action, JSONObject before, JSONObject after) {
        if (after == null) return obj("kind", "unobserved", "task_complete", false);
        if (after.optJSONObject("action_effect") != null) return after.optJSONObject("action_effect");
        boolean moved = before != null && !before.optString("url").equals(after.optString("url"));
        boolean changed = before != null && !before.optString("text").equals(after.optString("text"));
        JSONObject result = base(action.optString("action"), before, after, moved, changed);
        put(result, "location_changed", moved); put(result, "text_changed", changed);
        put(result, "dispatched", !Boolean.FALSE.equals(after.opt("action_executed")) && after.optBoolean("ok")
            && !java.util.Set.of("read", "tabs").contains(action.optString("action")));
        return result;
    }
    public static JSONObject phone(JSONObject action, JSONObject before, JSONObject after) {
        boolean moved = before != null && after != null && !before.optString("app").equals(after.optString("app"));
        boolean changed = before != null && after != null && !semantic(before).equals(semantic(after));
        JSONObject result = base(action.optString("action"), before, after, moved, changed);
        put(result, "app_changed", moved); put(result, "ui_changed", changed); put(result, "dispatched", true);
        if ("set_text".equals(action.optString("action")) && before != null && after != null) {
            JSONObject target = target(before, action.optString("element"));
            boolean retained = false;
            JSONArray elements = after.optJSONArray("elements");
            if (target != null && elements != null) for (int i=0;i<elements.length();i++) {
                JSONObject e=elements.optJSONObject(i);
                if (e!=null && e.optString("flags").contains("edit") && !e.optString("flags").contains("password")
                        && e.optString("bounds").equals(target.optString("bounds")) && e.optString("text").equals(action.optString("text"))) retained=true;
            }
            put(result,"input_verified",retained);
            if (retained) put(result,"kind","input_retained");
        }
        return result;
    }
    private static JSONObject base(String action, JSONObject before, JSONObject after, boolean moved, boolean changed) {
        String kind = after==null || !after.optBoolean("ok") || before==null ? "unobserved"
            : after.optBoolean("requires_user") ? "protected"
            : moved ? "navigation" : changed ? "content_changed" : "no_observable_change";
        return obj("action",action,"kind",kind,"task_complete",false,
            "retry_policy","Observe or replan; no visible change does not mean an accepted action failed.");
    }
    public static String semantic(JSONObject screen) {
        JSONArray elements=screen.optJSONArray("elements"), rows=arr();
        if (elements!=null) for(int i=0;i<elements.length();i++) {
            JSONObject e=elements.optJSONObject(i); if(e==null)continue;
            rows.put(obj("role",e.optString("role"),"text",e.optString("text"),"desc",e.optString("desc"),"flags",e.optString("flags"),"bounds",e.optJSONArray("bounds")));
        }
        return obj("app",screen.optString("app"),"screen",screen.optJSONArray("screen"),"elements",rows).toString();
    }
    private static JSONObject target(JSONObject screen,String id) {
        JSONArray elements=screen.optJSONArray("elements");
        if(elements!=null)for(int i=0;i<elements.length();i++){JSONObject e=elements.optJSONObject(i);if(e!=null&&id.equals(e.optString("id")))return e;}
        return null;
    }
    /** Small allowlist for semantic checks: never copy arbitrary page fields or text. */
    public static JSONObject evidence(JSONObject source) {
        JSONObject out=obj(); if(source==null)return out;
        for(String key:new String[]{"action","kind","dispatched","task_complete","location_changed","text_changed","control_changed","ui_changed","app_changed","input_verified"})
            if(source.has(key))put(out,key,source.opt(key) instanceof Boolean?source.opt(key):PhoneDecisionEvidence.text(source.optString(key),40));
        return out;
    }
}
