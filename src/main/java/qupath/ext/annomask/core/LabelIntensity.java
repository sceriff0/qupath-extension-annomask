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

    /**
     * Per-compartment mean intensity, keyed by CELL label. A single pass splits
     * each cell's pixels into nucleus vs cytoplasm using the nucleus mask:
     * <ul>
     *   <li><b>Cell</b> — every pixel belonging to the cell label (whole cell).</li>
     *   <li><b>Nucleus</b> — pixels in the cell label that ALSO sit on a positive
     *       nucleus-mask pixel.</li>
     *   <li><b>Cytoplasm</b> — pixels in the cell label that sit on NO nucleus
     *       pixel (i.e. cell − nucleus).</li>
     * </ul>
     * This mirrors mirage M1's {@code bincount(where(nuc, cell, 0))} approach:
     * the cell-mask labeling drives keying, so no nucleus→cell label pairing is
     * needed for intensities. A nucleus pixel that falls outside any cell label
     * contributes to nothing. Slots for absent labels (and slots with zero
     * pixels in a compartment) read as 0.0.
     *
     * @param cellBand    labeled whole-cell mask (label 0 = background)
     * @param nucleusBand nucleus mask; any positive (non-NaN) value marks a
     *                    nucleus pixel (its label value is irrelevant here)
     * @param channelBand the intensity channel to average
     * @param maxLabel    the largest cell label to size the result arrays
     * @return three parallel arrays keyed by cell label
     * @throws IllegalArgumentException if any band disagrees on size
     */
    public static CompartmentMeans meanPerCompartment(SimpleImage cellBand,
                                                      SimpleImage nucleusBand,
                                                      SimpleImage channelBand,
                                                      int maxLabel) {
        int w = cellBand.getWidth();
        int h = cellBand.getHeight();
        requireSameSize(nucleusBand, w, h, "nucleus");
        requireSameSize(channelBand, w, h, "channel");

        double[] sumCell = new double[maxLabel + 1];
        long[] countCell = new long[maxLabel + 1];
        double[] sumNuc = new double[maxLabel + 1];
        long[] countNuc = new long[maxLabel + 1];
        double[] sumCyto = new double[maxLabel + 1];
        long[] countCyto = new long[maxLabel + 1];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float cv = cellBand.getValue(x, y);
                if (Float.isNaN(cv) || cv <= 0f) continue;
                int label = (int) cv;
                if (label > maxLabel) continue;
                double intensity = channelBand.getValue(x, y);

                sumCell[label] += intensity;
                countCell[label]++;

                float nv = nucleusBand.getValue(x, y);
                boolean inNucleus = !Float.isNaN(nv) && nv > 0f;
                if (inNucleus) {
                    sumNuc[label] += intensity;
                    countNuc[label]++;
                } else {
                    sumCyto[label] += intensity;
                    countCyto[label]++;
                }
            }
        }

        return new CompartmentMeans(
                toMeans(sumCell, countCell, maxLabel),
                toMeans(sumNuc, countNuc, maxLabel),
                toMeans(sumCyto, countCyto, maxLabel));
    }

    private static double[] toMeans(double[] sum, long[] count, int maxLabel) {
        double[] means = new double[maxLabel + 1];
        for (int i = 1; i <= maxLabel; i++) {
            means[i] = count[i] == 0 ? 0.0 : sum[i] / count[i];
        }
        return means;
    }

    private static void requireSameSize(SimpleImage band, int w, int h, String which) {
        if (band.getWidth() != w || band.getHeight() != h) {
            throw new IllegalArgumentException(
                    which + " band size mismatch: expected " + w + "x" + h + " but got "
                    + band.getWidth() + "x" + band.getHeight());
        }
    }

    /**
     * Per-compartment means keyed by cell label, parallel arrays sized
     * {@code maxLabel + 1}. Index 0 (background) is unused.
     */
    public record CompartmentMeans(double[] cell, double[] nucleus, double[] cytoplasm) {}
}
