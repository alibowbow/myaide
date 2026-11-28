package dev.jun.gyeot;

import dev.jun.gyeot.core.EdgeSpot;
import dev.jun.gyeot.core.EdgeSpot.Handle;
import dev.jun.gyeot.core.EdgeSpot.Win;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 들퍼레's edge handle just below Samsung's Edge panel handle (준: "손잡이 위치를 기존 삼성엣지 아래로 해줘"): found among the
 * windows the accessibility service lists, ours a small gap below it on its side, inside the screen above the navigation bar;
 * none found, the right side at about 60% of the screen's height. Pure Java.
 */
public class EdgeSpotTests {
    // A Galaxy screen: 1080×2340, status bar 80px, navigation bar 126px; our handle's window 228px tall, a 36px gap.
    private static final int W = 1080, H = 2340, TOP = 80, BOTTOM = 126, OURS = 228, GAP = 36;

    @Test public void samsungsHandleIsFoundByItsAppAndShape() {
        List<Win> windows = List.of(
            new Win("com.example.app", null, 0, 0, W, H),
            new Win("com.android.systemui", "NavigationBar0", 0, H - BOTTOM, W, H),
            new Win(EdgeSpot.SAMSUNG, null, W - 40, 700, W, 1100),
            new Win(EdgeSpot.SAMSUNG, null, 0, 0, W, H));
        assertEquals("the narrow strip against the right edge", new Handle(true, 700, 1100), EdgeSpot.find(windows, W));
        assertEquals("on the left too", new Handle(false, 500, 900), EdgeSpot.find(List.of(new Win(EdgeSpot.SAMSUNG, null, 0, 500, 36, 900)), W));
        assertEquals("by its window's title when its app cannot be read", new Handle(true, 600, 960), EdgeSpot.find(List.of(new Win(null, "CocktailBarHandle", W - 30, 600, W, 960)), W));
        assertNull("another app's strip is not it", EdgeSpot.find(List.of(new Win("com.other.edge", null, W - 40, 700, W, 1100)), W));
        assertNull("not against an edge", EdgeSpot.find(List.of(new Win(EdgeSpot.SAMSUNG, null, 400, 700, 440, 1100)), W));
        assertNull("none", EdgeSpot.find(List.of(), W));
    }

    @Test public void oursGoesJustBelowItInsideTheScreen() {
        Handle samsung = new Handle(true, 700, 1100);
        assertEquals("a small gap below Samsung's", 1100 + GAP, EdgeSpot.top(samsung, H, TOP, BOTTOM, OURS, GAP));
        // Samsung's moved down: ours follows it.
        assertEquals(1500 + GAP, EdgeSpot.top(new Handle(true, 1100, 1500), H, TOP, BOTTOM, OURS, GAP));
        // Samsung's so low there is no room below above the navigation bar: ours goes just above it, never over it.
        Handle low = new Handle(true, 1900, 2150);
        int above = EdgeSpot.top(low, H, TOP, BOTTOM, OURS, GAP);
        assertEquals(1900 - GAP - OURS, above);
        assertTrue("never over Samsung's", above + OURS <= 1900);
        // Neither fits (a tiny screen): inside the screen, above the navigation bar.
        int squeezed = EdgeSpot.top(new Handle(true, 100, 600), 700, TOP, BOTTOM, OURS, GAP);
        assertTrue(squeezed >= TOP && squeezed + OURS <= 700 - BOTTOM);
    }

    @Test public void withoutSamsungsItSitsOnTheRightAtAboutSixtyPercent() {
        int top = EdgeSpot.top(null, H, TOP, BOTTOM, OURS, GAP);
        assertEquals(Math.round(H * 0.6), top);
        assertTrue("above the navigation bar", top + OURS <= H - BOTTOM);
        assertEquals("a short screen: clamped above the navigation bar", 700 - BOTTOM - OURS, EdgeSpot.top(null, 700, TOP, BOTTOM, OURS, GAP));
    }
}
