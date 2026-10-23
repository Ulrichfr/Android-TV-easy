package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Backdrop from an app's artwork: drawn small, saturated, smoothed, then stretched by the
 * ImageView. Bilinear filtering of a 48×27 image is the blur. A real blur would be a shader
 * every frame, which this TV cannot spare.
 */
final class HeroArt {
    static final int W = 48, H = 27;
    private static final float SATURATION = 1.7f;

    private HeroArt() {
    }

    /** Software copy of the artwork at 192×108. Call on the UI thread; the result can leave it. */
    static Bitmap snapshot(Drawable live) {
        if (live == null) return null;
        // Private copy: changing the bounds of the drawable a tile shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        Bitmap big = Bitmap.createBitmap(W * 4, H * 4, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(big);
        c.drawColor(Color.BLACK);
        d.setBounds(0, 0, big.getWidth(), big.getHeight());
        d.draw(c);
        d.setBounds(saved);
        return big;
    }

    /** Saturate and soften {@code big}, then recycle it. About 5 KB. */
    static Bitmap soften(Bitmap big) {
        // Halve twice: each filtered halving averages 2×2 pixels, where one 4× step would skip most of them.
        Bitmap half = Bitmap.createScaledBitmap(big, W * 2, H * 2, true);
        if (half != big) big.recycle();
        Bitmap small = Bitmap.createScaledBitmap(half, W, H, true);
        if (small != half) half.recycle();

        int[] px = new int[W * H];
        small.getPixels(px, 0, W, 0, 0, W, H);
        saturate(px);
        // Three box passes approximate a gaussian, so the stretched image shows no seams between pixels.
        for (int pass = 0; pass < 3; pass++) {
            px = boxBlur(px, W, H, 2, true);
            px = boxBlur(px, W, H, 2, false);
        }
        Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (out != small) small.recycle();
        out.setPixels(px, 0, W, 0, 0, W, H);
        return out;
    }

    private static void saturate(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            px[i] = Color.rgb(clamp(lum + (r - lum) * SATURATION), clamp(lum + (g - lum) * SATURATION),
                    clamp(lum + (b - lum) * SATURATION));
        }
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }

    /** One box blur pass along rows (horizontal) or columns, edges clamped. */
    private static int[] boxBlur(int[] src, int w, int h, int radius, boolean horizontal) {
        int[] dst = new int[src.length];
        int lines = horizontal ? h : w, length = horizontal ? w : h, span = 2 * radius + 1;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int j = Math.max(0, Math.min(length - 1, i + k));
                    int p = horizontal ? src[line * w + j] : src[j * w + line];
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                }
                dst[horizontal ? line * w + i : i * w + line] = Color.rgb(r / span, g / span, b / span);
            }
        }
        return dst;
    }
}
