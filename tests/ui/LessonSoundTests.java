package dev.jun.gyeot;

import android.media.AudioAttributes;
import android.media.AudioTrack;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * 판서's voice is media (준: "판서강의는 왜 녹화할 때 미디어 소리가 녹음이 안되지?"): a screen recording takes in media, game and
 * unknown sound only, never an assistant's or a call's, so the lesson's speaker and the device voice it uses play as media,
 * while 퍼레's and the assistant's voices stay as they were.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
public class LessonSoundTests {
    private static int usage(Speaker speaker) throws Exception {
        java.lang.reflect.Field f = Speaker.class.getDeclaredField("track"); f.setAccessible(true);
        return ((AudioTrack) f.get(speaker)).getAudioAttributes().getUsage();
    }
    @Test public void theLessonPlaysAsMedia() throws Exception {
        android.content.Context app = RuntimeEnvironment.getApplication();
        Speaker lesson = Speaker.media(app), assistant = new Speaker(app), call = new Speaker(app, true);
        try {
            assertEquals("판서: media, so a screen recording hears it", AudioAttributes.USAGE_MEDIA, usage(lesson));
            assertEquals("the assistant's voice as before", AudioAttributes.USAGE_ASSISTANT, usage(assistant));
            assertEquals("a call's voice as before", AudioAttributes.USAGE_VOICE_COMMUNICATION, usage(call));
        } finally { lesson.close(); assistant.close(); call.close(); }
        assertEquals(AudioAttributes.USAGE_MEDIA, Speech.LESSON.getUsage());
        assertEquals(AudioAttributes.USAGE_ASSISTANT, Speech.ATTRIBUTES.getUsage());
    }
}
