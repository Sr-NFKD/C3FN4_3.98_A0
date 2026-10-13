package com.sr.nfkd;

final class FrameCodec {
    // Panel codes: 0 black, 1 white, 2 yellow, 3 red; first pixel in the top two bits.
    static final int[] COLORS = {0xff000000, 0xffffffff, 0xffffff00, 0xffff0000};
    static byte[] encode(int[] pixels, int width, int height, boolean dither, boolean invert) {
        if (width <= 0 || height <= 0 || (width * height & 3) != 0
                || pixels.length != width * height) throw new IllegalArgumentException("图片尺寸错误");
        byte[] packed = new byte[pixels.length / 4];
        float[] current = new float[(width + 2) * 3];
        float[] next = new float[current.length];
        for (int y = 0; y < height; y++) {
            int direction = (y & 1) == 0 ? 1 : -1;
            for (int step = 0; step < width; step++) {
                int x = direction == 1 ? step : width - 1 - step;
                int index = y * width + x, pixel = pixels[index], slot = (x + 1) * 3;
                float r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
                if (invert) { r = 255 - r; g = 255 - g; b = 255 - b; }
                r = clamp(r + current[slot]);
                g = clamp(g + current[slot + 1]);
                b = clamp(b + current[slot + 2]);
                int code = 0;
                float best = Float.MAX_VALUE;
                for (int c = 0; c < COLORS.length; c++) {
                    float dr = r - ((COLORS[c] >> 16) & 255);
                    float dg = g - ((COLORS[c] >> 8) & 255);
                    float db = b - (COLORS[c] & 255);
                    float distance = dr * dr * 0.30f + dg * dg * 0.59f + db * db * 0.11f;
                    if (distance < best) { best = distance; code = c; }
                }
                pixels[index] = COLORS[code];
                packed[index / 4] |= (byte) (code << (6 - (index % 4) * 2));
                if (dither) {
                    float[] residual = {r - ((COLORS[code] >> 16) & 255),
                            g - ((COLORS[code] >> 8) & 255), b - (COLORS[code] & 255)};
                    for (int channel = 0; channel < 3; channel++) {
                        float e = residual[channel] / 16;
                        current[slot + direction * 3 + channel] += e * 7;
                        next[slot - direction * 3 + channel] += e * 3;
                        next[slot + channel] += e * 5;
                        next[slot + direction * 3 + channel] += e;
                    }
                }
            }
            float[] swap = current; current = next; next = swap;
            java.util.Arrays.fill(next, 0);
        }
        return packed;
    }
    private static float clamp(float value) { return Math.max(0, Math.min(255, value)); }
}
