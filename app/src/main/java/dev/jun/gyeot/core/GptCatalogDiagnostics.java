package dev.jun.gyeot.core;

import org.json.*;
import static dev.jun.gyeot.core.Jsons.*;

/** Allowlisted local summary. Never copies labels, error bodies, headers, tokens or arbitrary model metadata. */
public final class GptCatalogDiagnostics {
    private static final String TERRA="gpt-6.1-terra";
    private GptCatalogDiagnostics() {}
    public static JSONObject report(String mode,long at,int http,String outcome,JSONObject body,JSONArray shown) {
        JSONArray plan=body==null?null:body.optJSONArray("models"),data=body==null?null:body.optJSONArray("data");
        boolean found=false,listed=false,wrongField=false,hidden=false,otherArray=false;
        for(String field:new String[]{"models","data"}) {
            JSONArray values=body==null?null:body.optJSONArray(field);
            if(values==null)continue;
            for(int i=0;i<values.length();i++) {
                JSONObject m=values.optJSONObject(i);if(m==null)continue;
                boolean exact=false;for(String key:new String[]{"slug","id","model","model_id"})exact|=TERRA.equals(m.optString(key));
                if(!exact)continue;found=true;
                String expected=field.equals("models")?m.optString("slug"):m.optString("model",m.optString("id"));
                wrongField|=!TERRA.equals(expected);
                hidden|=field.equals("models")?!"list".equals(m.optString("visibility")):m.optBoolean("hidden");
                otherArray|=field.equals("data")&&plan!=null;
            }
        }
        for(int i=0;shown!=null&&i<shown.length();i++){JSONObject m=shown.optJSONObject(i);if(m!=null&&TERRA.equals(m.optString("id")))listed=true;}
        String result=listed?"displayed":!found?"not_returned":otherArray?"other_array_ignored":wrongField?"unrecognized_id_field":hidden?"not_list_visible":"filtered";
        String safeOutcome=switch(outcome){case "ok","http_error","invalid_shape","connection_error"->outcome;default->"unknown";};
        return obj("source","https://api.openai.com/v1/models","mode",mode.equals("key")?"api_key":mode.equals("chatgpt")?"chatgpt_login":"unknown",
            "checked_at",Math.max(0,at),"http_status",Math.max(0,Math.min(599,http)),"outcome",safeOutcome,
            "shape",plan!=null?(data!=null?"models+data":"models"):data!=null?"data":"unrecognized",
            "models_count",plan==null?0:plan.length(),"data_count",data==null?0:data.length(),"displayed_count",shown==null?0:shown.length(),
            "terra",safeOutcome.equals("ok")?result:"not_determined");
    }
}
