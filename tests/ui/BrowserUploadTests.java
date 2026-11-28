package dev.jun.gyeot;

import android.content.Context;
import android.net.Uri;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 준: "파일 첨부는 여전히 안돼": the phone's picker gives a content:// link, which Gecko cannot read; the page gets a copy in this
 * app's cache under the file's own name instead (BrowserActivity.uploadCopy), as Firefox does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
public class BrowserUploadTests {
    @Test public void aPickedFileReachesThePageAsAFile() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Uri picked = Uri.parse("content://com.android.providers.media.documents/document/image%3A1234");
        byte[] bytes = "사진 바이트 · photo bytes".getBytes(StandardCharsets.UTF_8);
        shadowOf(c.getContentResolver()).registerInputStream(picked, new ByteArrayInputStream(bytes));
        Uri file = BrowserActivity.uploadCopy(c, picked);
        assertNotNull("copied", file);
        assertEquals("a real file for Gecko", "file", file.getScheme());
        File f = new File(file.getPath());
        assertTrue("in this app's cache", f.getPath().startsWith(new File(c.getCacheDir(), "uploads").getPath()));
        assertArrayEquals("the same bytes", bytes, Files.readAllBytes(f.toPath()));
        assertSame("a file:// one is passed as it is", file, BrowserActivity.uploadCopy(c, file));
        assertNull("unreadable: nothing", BrowserActivity.uploadCopy(c, Uri.parse("content://nowhere/none")));
    }
}
