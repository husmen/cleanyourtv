package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The dock band's corners are concentric with its tiles' corners, and its edge takes the glow's hue. */
public class DockCornersTest {
    private static final int PLAIN_EDGE = 0x1FFFFFFF;

    /** A full-strength red glow lights the edge pale red at 55% opacity: red on top, green and blue level. */
    @Test public void aColouredGlowLightsTheEdgeInItsOwnHue() {
        int edge = DockDecoration.litEdge(PLAIN_EDGE, 0xFFCC0000);
        assertEquals(0x8C, edge >>> 24);
        assertEquals(222, (edge >> 16) & 0xFF);
        assertEquals(144, (edge >> 8) & 0xFF);
        assertEquals(144, edge & 0xFF);
    }

    /** A grey glow has no hue, and a glow that is not showing has no light: the edge stays plain for both. */
    @Test public void aGreyOrAbsentGlowLeavesTheEdgePlain() {
        assertEquals(PLAIN_EDGE, DockDecoration.litEdge(PLAIN_EDGE, 0xFF444444));
        assertEquals(PLAIN_EDGE, DockDecoration.litEdge(PLAIN_EDGE, 0x00CC0000));
    }

    /** While the glow fades in, the edge is part-way between plain and lit, so it follows the fade. */
    @Test public void theEdgeFollowsTheGlowsStrength() {
        int half = DockDecoration.litEdge(PLAIN_EDGE, 0x80CC0000);
        assertTrue((half >>> 24) > 0x1F && (half >>> 24) < 0x8C);
        assertTrue((half & 0xFF) < 0xFF && (half & 0xFF) > 144);
    }

    private static final float DENSITY = 2f;   // 1080p TV, 320 dpi
    /** activity_main.xml: 56dp root padding and 10dp grid padding on each side of a 1920 px screen. */
    private static final int INNER = 1920 - 2 * 56 * 2 - 2 * 10 * 2;

    @Test public void bandRadiusIsTileRadiusPlusPadding() {
        assertEquals(77f, DockDecoration.radiusPx(25f, 52f), 0f);
    }

    @Test public void evenPaddingIsTheGapBetweenTilesUnlessTheEdgeIsTighter() {
        assertEquals(50f, DockDecoration.evenPaddingPx(1000, 4, 200, 80f), 0.001f);
        assertEquals(30f, DockDecoration.evenPaddingPx(1000, 4, 200, 30f), 0.001f);
        assertEquals(0f, DockDecoration.evenPaddingPx(1000, 0, 200, 30f), 0.001f);
    }

    @Test public void evenPaddingStaysPositiveAndBelowTheCellFromFourToSevenColumns() {
        for (int columns = 4; columns <= 7; columns++) {
            int tile = AppAdapter.tileWidthPx(INNER, columns, DENSITY);
            float gap = INNER / (float) columns - tile;
            float pad = DockDecoration.evenPaddingPx(INNER, columns, tile, Float.MAX_VALUE);
            assertEquals("columns " + columns, gap, pad, 0.001f);
            float radius = DockDecoration.radiusPx(AppAdapter.cornerRadius(tile, DENSITY), pad);
            assertEquals("columns " + columns, pad, radius - AppAdapter.cornerRadius(tile, DENSITY), 0.001f);
        }
    }
}