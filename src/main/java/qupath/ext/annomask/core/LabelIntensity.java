package qupath.ext.annomask.core;

import qupath.lib.analysis.images.SimpleImage;

/**
 * Mean intensity per label via a single bincount-style pass over the raster.
 * Deliberately matches mirage's {@code compute_channel_intensity} arithmetic
 * (see mirage/bin/quantify.py) so AnnoMask's measurements are bit-identical
 * (up to floating-point ordering) to mirage's CSV output.
 *
 * Background pixels (label == 0) are accumulated into {@code means[0]} and
 * never read by callers.
 */
public final class LabelIntensity {

    private LabelIntensity() {}

    /**
     * Returns {@code max(0, floor(pixel))} across every pixel in {@code labelBand}.
     * Negative or NaN pixels are treated as background.
     */
    public static int maxLabel(SimpleImage labelBand) {
        int w = labelBand.getWidth();
        int h = labelBand.getHeight();
        int max = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = labelBand.getValue(x, y);
                if (Float.isNaN(v) || v <= 0f) continue;
                int iv = (int) v;
                if (iv > max) max = iv;
            }
        }
        return max;
    }

    /**
     * One pass over the two bands. Returns {@code means[0..maxLabel]}; label 0
     * and labels with zero pixels both map to 0.0.
     *
     * @throws IllegalArgumentException if the bands disagree on size
     */
    public static double[] meanPerLabel(SimpleImage labelBand, SimpleImage channelBand, int maxLabel) {
        int w = labelBand.getWidth();
        int h = labelBand.getHeight();
        if (channelBand.getWidth() != w || channelBand.getHeight() != h) {
            throw new IllegalArgumentException(
                    "label/channel band size mismatch: " + w + "x" + h + " vs "
                    + channelBand.getWidth() + "x" + channelBand.getHeight());
        }
        double[] sum = new double[maxLabel + 1];
        long[] count = new long[maxLabel + 1];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float lv = labelBand.getValue(x, y);
                if (Float.isNaN(lv) || lv <= 0f) continue;
                int label = (int) lv;
                if (label > maxLabel) continue;
                sum[label] += channelBand.getValue(x, y);
                count[label]++;
            }
        }
        double[] means = new double[maxLabel + 1];
        for (int i = 1; i <= maxLabel; i++) {
            means[i] = count[i] == 0 ? 0.0 : sum[i] / count[i];
        }
        return means;
    }
}
