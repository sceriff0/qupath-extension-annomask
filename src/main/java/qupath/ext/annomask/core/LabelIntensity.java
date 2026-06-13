package qupath.ext.annomask.core;

import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.images.SimpleImages;

/**
 * Mean intensity per label via a single bincount-style pass over the raster.
 * Deliberately matches mirage's {@code compute_channel_intensity} arithmetic
 * (see mirage/bin/quantify.py) so AnnoMask's measurements are bit-identical
 * (up to floating-point ordering) to mirage's CSV output.
 *
 * Background pixels (label == 0) are accumulated into {@code means[0]} and
 * never read by callers.
 *
 * <p>The hot paths operate on flat {@code float[]} pixel arrays obtained from
 * {@link SimpleImages#getPixels(SimpleImage, boolean)}. A flat array is read in
 * {@code index = y*width + x} order, i.e. the same row-major traversal as the
 * {@code (x,y)} loops it replaces, so per-label summation order — and therefore
 * the floating-point result — is unchanged. This avoids one interface dispatch
 * per pixel and lets callers read the label band once and reuse it across every
 * channel.</p>
 */
public final class LabelIntensity {

    private LabelIntensity() {}

    /**
     * Returns {@code max(0, floor(pixel))} across every pixel in {@code labelBand}.
     * Negative or NaN pixels are treated as background.
     */
    public static int maxLabel(SimpleImage labelBand) {
        float[] pixels = SimpleImages.getPixels(labelBand, true);
        int max = 0;
        for (float v : pixels) {
            if (Float.isNaN(v) || v <= 0f) continue;
            int iv = (int) v;
            if (iv > max) max = iv;
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
        return meanPerLabel(SimpleImages.getPixels(labelBand, true),
                SimpleImages.getPixels(channelBand, true), maxLabel);
    }

    /**
     * Flat-array hot path for {@link #meanPerLabel(SimpleImage, SimpleImage, int)}.
     * {@code labels} and {@code channel} are row-major pixel arrays of equal
     * length (a band's {@code float[]} from
     * {@link SimpleImages#getPixels(SimpleImage, boolean)}). Iterating in index
     * order is the same traversal as the {@code (x,y)} loop, so the result is
     * identical; reading the label band once instead of per channel is the
     * speed-up. Callers that process many channels should obtain {@code labels}
     * once and reuse it.
     *
     * @throws IllegalArgumentException if the two arrays differ in length
     */
    public static double[] meanPerLabel(float[] labels, float[] channel, int maxLabel) {
        double[] sum = new double[maxLabel + 1];
        long[] count = new long[maxLabel + 1];
        accumulate(labels, channel, maxLabel, sum, count);
        return toMeans(sum, count, maxLabel);
    }

    /**
     * Adds one band's contribution into running {@code sum}/{@code count}
     * accumulators (sized {@code maxLabel + 1}) without finalising the means.
     * This is the building block for processing an image in pieces: call it once
     * per piece with that piece's label and channel pixels, then
     * {@link #toMeans(double[], long[], int)} once at the end.
     *
     * <p>Because the per-label running sum is updated in array-index order,
     * feeding contiguous row-major strips top-to-bottom reproduces the exact
     * summation order — and therefore the exact floating-point result — of a
     * single whole-image pass.</p>
     *
     * @throws IllegalArgumentException if the two pixel arrays differ in length
     */
    public static void accumulate(float[] labels, float[] channel, int maxLabel,
                                  double[] sum, long[] count) {
        if (labels.length != channel.length) {
            throw new IllegalArgumentException(
                    "label/channel pixel count mismatch: " + labels.length + " vs " + channel.length);
        }
        for (int i = 0; i < labels.length; i++) {
            float lv = labels[i];
            if (Float.isNaN(lv) || lv <= 0f) continue;
            int label = (int) lv;
            if (label > maxLabel) continue;
            sum[label] += channel[i];
            count[label]++;
        }
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
        return meanPerCompartment(
                SimpleImages.getPixels(cellBand, true),
                SimpleImages.getPixels(nucleusBand, true),
                SimpleImages.getPixels(channelBand, true),
                maxLabel);
    }

    /**
     * Flat-array hot path for
     * {@link #meanPerCompartment(SimpleImage, SimpleImage, SimpleImage, int)}.
     * All three arrays are equal-length row-major pixel bands; iterating in
     * index order matches the {@code (x,y)} traversal exactly. Callers
     * processing many channels should obtain {@code cell} and {@code nucleus}
     * once and reuse them across channels.
     *
     * @throws IllegalArgumentException if the arrays differ in length
     */
    public static CompartmentMeans meanPerCompartment(float[] cell, float[] nucleus,
                                                      float[] channel, int maxLabel) {
        double[] sumCell = new double[maxLabel + 1];
        long[] countCell = new long[maxLabel + 1];
        double[] sumNuc = new double[maxLabel + 1];
        long[] countNuc = new long[maxLabel + 1];
        double[] sumCyto = new double[maxLabel + 1];
        long[] countCyto = new long[maxLabel + 1];
        accumulateCompartments(cell, nucleus, channel, maxLabel,
                sumCell, countCell, sumNuc, countNuc, sumCyto, countCyto);
        return new CompartmentMeans(
                toMeans(sumCell, countCell, maxLabel),
                toMeans(sumNuc, countNuc, maxLabel),
                toMeans(sumCyto, countCyto, maxLabel));
    }

    /**
     * Per-compartment counterpart of {@link #accumulate}: adds one band's
     * contribution into the six running accumulators (cell / nucleus / cytoplasm
     * sums and counts) so a large image can be quantified strip-by-strip.
     * Feeding contiguous row-major strips reproduces the whole-image result
     * exactly. See {@link #meanPerCompartment(float[], float[], float[], int)}
     * for the compartment semantics.
     *
     * @throws IllegalArgumentException if the three pixel arrays differ in length
     */
    public static void accumulateCompartments(float[] cell, float[] nucleus, float[] channel, int maxLabel,
                                              double[] sumCell, long[] countCell,
                                              double[] sumNuc, long[] countNuc,
                                              double[] sumCyto, long[] countCyto) {
        if (cell.length != nucleus.length || cell.length != channel.length) {
            throw new IllegalArgumentException(
                    "compartment band pixel count mismatch: cell=" + cell.length
                    + " nucleus=" + nucleus.length + " channel=" + channel.length);
        }
        for (int i = 0; i < cell.length; i++) {
            float cv = cell[i];
            if (Float.isNaN(cv) || cv <= 0f) continue;
            int label = (int) cv;
            if (label > maxLabel) continue;
            double intensity = channel[i];

            sumCell[label] += intensity;
            countCell[label]++;

            float nv = nucleus[i];
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

    /**
     * Finalises running accumulators into a means array: {@code means[i] =
     * count[i] == 0 ? 0.0 : sum[i] / count[i]} for {@code i} in {@code 1..maxLabel}.
     * Index 0 (background) is left at 0.0.
     */
    public static double[] toMeans(double[] sum, long[] count, int maxLabel) {
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
