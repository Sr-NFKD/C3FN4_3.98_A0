package com.sr.nfkd;

final class FrameCodec {
    // Transport/preview colours; ReinkRaster uses its own screen palette for matching.
    static final int[] COLORS = {0xff000000, 0xffffffff, 0xffffff00, 0xffff0000};
    static byte[] encode(int[] pixels, int width, int height, boolean dither, boolean invert) {
        return encode(pixels, width, height, dither, invert, 100, 100, 100);
    }
    static byte[] encode(int[] pixels, int width, int height, boolean dither, boolean invert,
                         int redDepth, int blackDepth, int yellowDepth) {
        return encode(pixels, width, height, dither ? ReinkRaster.MODE_GOLD : ReinkRaster.MODE_LEVEL,
                invert, redDepth, blackDepth, yellowDepth);
    }
    static byte[] encode(int[] pixels, int width, int height, int mode, boolean invert,
                         int redDepth, int blackDepth, int yellowDepth) {
        if (pixels == null || width <= 0 || height <= 0 || (long)width * height != pixels.length
                || (pixels.length & 3) != 0) throw new IllegalArgumentException("图片尺寸错误");
        if (mode < 0 || mode >= ReinkRaster.MODES.length)
            throw new IllegalArgumentException("图片风格错误");
        if (redDepth < 0 || redDepth > 200 || blackDepth < 0 || blackDepth > 200
                || yellowDepth < 0 || yellowDepth > 200)
            throw new IllegalArgumentException("颜色深浅参数错误");
        // Defaults bypass preprocessing to preserve the upstream floating-point algorithm.
        int[] source = pixels;
        if (invert || redDepth != 100 || blackDepth != 100 || yellowDepth != 100) {
            source = pixels.clone();
            for (int i = 0; i < source.length; i++) {
                int p = source[i];
                double alpha = (p >>> 24) / 255.0;
                double r = ((p >>> 16) & 255) * alpha + 255 * (1 - alpha);
                double g = ((p >>> 8) & 255) * alpha + 255 * (1 - alpha);
                double b = (p & 255) * alpha + 255 * (1 - alpha);
                if (invert) { r = 255 - r; g = 255 - g; b = 255 - b; }
                double black = (255 - Math.max(r, Math.max(g, b))) * (blackDepth / 100.0 - 1);
                double red = Math.max(0, r - g) * (redDepth / 100.0 - 1);
                double yellow = Math.max(0, Math.min(r, g) - b) * (yellowDepth / 100.0 - 1);
                source[i] = 0xff000000 | (channel(r - black) << 16)
                        | (channel(g - black - red) << 8) | channel(b - black - red - yellow);
            }
        }
        byte[] codes = ReinkRaster.quantize(source, width, height, mode);
        byte[] packed = new byte[pixels.length / 4];
        for (int i = 0; i < pixels.length; i++) {
            int code = codes[i] & 3;
            pixels[i] = COLORS[code];
            packed[i / 4] |= (byte)(code << (6 - (i % 4) * 2));
        }
        return packed;
    }
    private static int channel(double value) {
        return (int)Math.round(Math.max(0, Math.min(255, value)));
    }
}
