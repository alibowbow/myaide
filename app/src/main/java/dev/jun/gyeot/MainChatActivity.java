package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Compatibility entry only. The messenger itself belongs to MainActivity's existing shell. */
public final class MainChatActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);openHost();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);openHost();}
    private void openHost(){
        // Do not forward arbitrary extras, launcher actions, permissions or voice-start flags.
        startActivity(new Intent(this,MainActivity.class).putExtra(MainNavigation.TARGET,"chat")
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
    static boolean suggestsWork(String text){return MainChatPage.suggestsWork(text);}
}
