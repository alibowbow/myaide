package dev.jun.gyeot;

import android.app.Application;
import android.content.SharedPreferences;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Antigravity's models in the pickers: Argon (being retired) left out, Gemini then Claude then GPT then agy's own. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class AgyModelListTests {
    @Test public void retiredOutFamiliesTogether() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = app.getSharedPreferences("agy-test", 0);
        String[][] listed = {{"gemini-4-neon-high", "Gemini 4 Neon (High)"}, {"gemini-4-neon-low", "Gemini 4 Neon (Low)"}, {"gemini-4-carbon-high", "Gemini 4 Carbon (High)"},
            {"gemini-4-argon-high", "Gemini 4 Argon (High)"}, {"gemini-4-hydrogen", "Gemini 4 Hydrogen"}, {"orchestrator-neon", "Orchestrator (Neon)"},
            {"orchestrator-argon", "Orchestrator (Argon)"}, {"gemini-3.8-flash", "Gemini 3.8 Flash"}, {"claude-opus-5-5", "Claude Opus 5.5"},
            {"claude-sonnet-5-5", "Claude Sonnet 5.5"}, {"gpt-oss-120b", "GPT-OSS 120B"}};
        JSONArray saved = new JSONArray(); for (String[] m : listed) saved.put(new JSONObject().put("id", m[0]).put("name", m[1]));
        prefs.edit().putString("agyModels", saved.toString()).commit();
        AntigravityAccount agy = new AntigravityAccount(app, prefs, () -> {});
        List<String> names = new java.util.ArrayList<>(); for (AntigravityAccount.Group g : agy.groups()) names.add(g.name());
        assertEquals(List.of("Gemini 4 Neon", "Gemini 4 Carbon", "Gemini 4 Hydrogen", "Gemini 3.8 Flash", "Claude Opus 5.5", "Claude Sonnet 5.5", "GPT-OSS 120B", "Orchestrator (Neon)"), names);
        assertEquals(8, agy.models().length());
    }
}
