package com.sr.nfkd;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImagePlacementTest {
    @Test public void fitAndZoomPreserveAspectRatioAtEveryRotation() {
        for (int rotation : new int[] {0, 90, 180, 270}) {
            boolean sideways = rotation % 180 != 0;
            float width = sideways ? 552 : 768, height = sideways ? 768 : 552;
            float fit = ImagePlacement.scale(1200, 800, rotation, 100);
            assertTrue(1200 * fit <= width + 0.001f);
            assertTrue(800 * fit <= height + 0.001f);
            assertTrue(Math.abs(1200 * fit - width) < 0.001f || Math.abs(800 * fit - height) < 0.001f);
            assertEquals(fit / 4, ImagePlacement.scale(1200, 800, rotation, 25), 0.0001f);
            assertEquals(fit * 3, ImagePlacement.scale(1200, 800, rotation, 300), 0.0001f);
        }
        assertThrows(IllegalArgumentException.class, () -> ImagePlacement.scale(0, 800, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> ImagePlacement.scale(1200, 800, 45, 100));
        assertThrows(IllegalArgumentException.class, () -> ImagePlacement.scale(1200, 800, 0, 301));
    }
}
