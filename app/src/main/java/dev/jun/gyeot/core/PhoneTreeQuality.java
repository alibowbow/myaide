package dev.jun.gyeot.core;

import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Diagnoses missing accessibility information. It never creates action references. */
public final class PhoneTreeQuality {
    private PhoneTreeQuality() {}
    public static JSONObject assess(JSONObject screen) {
        JSONArray elements=screen.optJSONArray("elements"), blind=screen.optJSONArray("unlabeled_regions");
        int named=0, controls=0, namedControls=0; boolean protectedScreen=false, blindArea=false;
        if(elements!=null)for(int i=0;i<elements.length();i++) {
            JSONObject e=elements.optJSONObject(i); if(e==null)continue;
            String flags=e.optString("flags"), label=(e.optString("text")+" "+e.optString("desc")).strip();
            protectedScreen |= flags.contains("password") || label.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(one.time.code|verification code|인증번호|인증 코드|복구 코드|recovery code).*");
            boolean hasName=!label.isBlank()&&!flags.contains("password");
            if(hasName)named++;
            if(flags.contains("click")||flags.contains("edit")||java.util.Set.of("button","switch","check").contains(e.optString("role"))) {controls++;if(hasName)namedControls++;}
        }
        if(blind!=null)for(int n=0;n<blind.length();n++) {
            JSONArray region=blind.optJSONArray(n);int inside=0;
            if(region==null||region.length()!=4)continue;
            if(elements!=null)for(int i=0;i<elements.length();i++) {
                JSONObject e=elements.optJSONObject(i);if(e==null)continue;JSONArray b=e.optJSONArray("bounds");
                if(b!=null&&b.length()==4&&!(e.optString("text")+e.optString("desc")).isBlank()
                    && b.optInt(0)>=region.optInt(0)&&b.optInt(1)>=region.optInt(1)&&b.optInt(2)<=region.optInt(2)&&b.optInt(3)<=region.optInt(3))inside++;
            }
            if(inside<3)blindArea=true;
        }
        String level=protectedScreen?"protected":named==0?"unusable":screen.optBoolean("truncated")||blindArea
            ||controls>=4&&namedControls*3<=controls||named<6&&controls>namedControls?"partial":"adequate";
        return obj("level",level,"named_nodes",named,"controls",controls,"unnamed_controls",controls-namedControls,
            "blind_region",blindArea,"truncated",screen.optBoolean("truncated"),"visual_recommended",!protectedScreen&&!level.equals("adequate"));
    }
}
