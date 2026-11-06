package com.sr.nfkd;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ReinkRasterTest {
    @Test public void allStylesMatchUpstreamGoldenImages() throws Exception {
        // Generated from upstream blob 0566be301c9b14121c192c59627379c556c0f737.
        String[] hashes = {
            "6bfd1d7f48c47143f7f7bd93a6a908495f54cb06ec5add74cfd92910a69d167d",
            "381f626a323ae9fe83d613e1761c2dd26a17b3b1a4025d35894ed6873f001fab",
            "bd2abe9b77a0c1298a7ee5823bd9d4803bbb307e283d04f135700f386e549c89",
            "baa55ea2df850de6a3022ce2b3585d86c387c141c4e38b4f44ef999127ecb4fa"};
        int[] source = new int[32 * 16];
        for (int i = 0; i < source.length; i++) source[i] = i * 0x1234567 + 0x23456789;
        for (int mode = 0; mode < 4; mode++) {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(ReinkRaster.quantize(source, 32, 16, mode));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            assertEquals(hashes[mode], hex.toString());
        }
    }
    @Test public void allStylesPreservePackedPixelCodesAndDefaultAlgorithm() {
        int[] source = new int[32 * 16];
        for (int i = 0; i < source.length; i++) source[i] = i * 0x1234567 + 0x23456789;
        for (int mode = 0; mode < 4; mode++) {
            byte[] expected = ReinkRaster.quantize(source, 32, 16, mode);
            int[] preview = source.clone();
            byte[] frame = FrameCodec.encode(preview, 32, 16, mode, false, 100, 100, 100);
            for (int i = 0; i < source.length; i++) {
                int code = (frame[i / 4] >>> (6 - 2 * (i % 4))) & 3;
                assertEquals(expected[i], code);
                assertEquals(FrameCodec.COLORS[code], preview[i]);
            }
        }
    }

    @Test public void transparentPixelsMatchWhiteAndAllModesHandleFullSize() {
        int[] transparent = new int[552 * 768];
        Arrays.fill(transparent, 0x00123456);
        for (int mode = 0; mode < 4; mode++) {
            byte[] frame = FrameCodec.encode(transparent.clone(), 552, 768, mode, false, 100, 100, 100);
            assertEquals(105984, frame.length);
            for (byte value : frame) assertEquals(0x55, value & 255);
        }
    }

    @Test public void invalidModesAndDimensionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.encode(new int[4], 4, 1, 4, false, 100, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.encode(new int[4], Integer.MAX_VALUE, 4, 0, false, 100, 100, 100));
    }
}
