package dev.jun.gyeot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** A macro notification's buttons: 중지 of a run, the answer to its ask before a send, and 끝 or 취소 of a recording. */
public final class MacroReceiver extends BroadcastReceiver {
    static final String RUN_STOP = "dev.jun.gyeot.MACRO_STOP", ANSWER = "dev.jun.gyeot.MACRO_ANSWER", RECORD_STOP = "dev.jun.gyeot.MACRO_RECORD_STOP",
        RECORD_CANCEL = "dev.jun.gyeot.MACRO_RECORD_CANCEL", ID = "id", YES = "yes";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction() == null ? "" : intent.getAction();
        Context app = context.getApplicationContext();
        switch (action) {
            case RUN_STOP -> dev.jun.gyeot.core.BuddyJobs.stop(intent.getStringExtra(ID));
            case ANSWER -> MacroRunner.answer(intent.getStringExtra(ID), intent.getBooleanExtra(YES, false));
            // The recording lives on the main thread with 폰 조작's events.
            case RECORD_STOP -> new Handler(Looper.getMainLooper()).post(() -> {
                try { MacroRecorder.stop(app, true); }
                catch (RuntimeException nothing) { Toast.makeText(app, nothing.getMessage(), Toast.LENGTH_LONG).show(); MacroRecorder.cancel(app); }
            });
            case RECORD_CANCEL -> new Handler(Looper.getMainLooper()).post(() -> MacroRecorder.cancel(app));
            default -> { }
        }
    }
}
