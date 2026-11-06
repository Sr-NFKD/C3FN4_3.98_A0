package com.sr.nfkd;

final class ImagePlacement {
    static float scale(int width, int height, int rotation, int percent) {
        if (width <= 0 || height <= 0 || rotation % 90 != 0 || percent < 25 || percent > 300)
            throw new IllegalArgumentException("图片缩放参数错误");
        boolean sideways = rotation % 180 != 0;
        float boxWidth = sideways ? EpaperProtocol.HEIGHT : EpaperProtocol.WIDTH;
        float boxHeight = sideways ? EpaperProtocol.WIDTH : EpaperProtocol.HEIGHT;
        return Math.min(boxWidth / width, boxHeight / height) * percent / 100f;
    }
}
