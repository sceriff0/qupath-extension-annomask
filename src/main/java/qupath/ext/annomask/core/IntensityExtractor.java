package qupath.ext.annomask.core;

import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.images.SimpleImages;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Attaches mean intensity per channel per detection by running a bincount-style
 * pass (see {@link LabelIntensity}) over the raw label band. Intentionally does
 * NOT use QuPath's {@code ObjectMeasurements.addIntensityMeasurements}: that
 * API names measurements {@code "<channel>: Cell: Mean"}, which GatingTree and
 * qUMAP do not recognise (they look up measurements by bare channel name). This
 * path produces the same numbers and names as mirage's
 * {@code bin/quantify.py} / {@code bin/export_geojson.py}.
 *
 * <h2>Memory</h2>
 * The extractor never needs every channel of the whole image in memory at once.
 * It picks one of three strategies, all of which produce bit-identical results:
 * <ul>
 *   <li><b>Reuse</b> — channel mode already decoded the host image to read the
 *       label channel; that {@link BufferedImage} is passed through and reused
 *       instead of decoding the image again.</li>
 *   <li><b>Single read</b> — when the full multi-channel image fits the memory
 *       budget, it is read once (the historical behaviour).</li>
 *   <li><b>Strip</b> — otherwise channels are read in full-width horizontal
 *       strips, top to bottom, and the bincount is accumulated across strips.
 *       Because the strips are visited in row-major order the per-label
 *       summation order is unchanged, so the result is identical to a single
 *       pass while peak channel memory is bounded to one strip.</li>
 * </ul>
 */
public final class IntensityExtractor {

    /**
     * Memory budget for the channel pixels held at once (one strip's raster).
     * If the whole image's channels fit under this, it is read in a single pass;
     * otherwise it is processed in strips sized to stay under it.
     */
    private static final long CHANNEL_MEMORY_BUDGET_BYTES = 256L * 1024 * 1024;

    private IntensityExtractor() {}

    /**
     * Runs intensity extraction for every detection in {@code detections}.
     *
     * @param server       the image server whose channels will be sampled
     * @param detections   the objects to annotate in place (names must be
     *                     positive integer label IDs produced by {@link MaskConverter})
     * @param labelBand    the raster label band that produced {@code detections}
     * @param maxLabel     the largest label ID present in {@code labelBand}
     * @param progress     receives (done, total) work units; may be null
     */
    public static void extract(ImageServer<BufferedImage> server,
                               Collection<? extends PathObject> detections,
                               SimpleImage labelBand,
                               int maxLabel,
                               ProgressListener progress) throws IOException {
        extract(server, detections, labelBand, null, maxLabel, null, 0, progress);
    }

    /**
     * Whole-cell extraction that reuses an already-decoded host image (channel
     * mode), avoiding a second full read of the whole image.
     *
     * @param sourceImage the host image already in memory, or null to read it
     */
    public static void extract(ImageServer<BufferedImage> server,
                               Collection<? extends PathObject> detections,
                               SimpleImage labelBand,
                               int maxLabel,
                               BufferedImage sourceImage,
                               ProgressListener progress) throws IOException {
        extract(server, detections, labelBand, null, maxLabel, sourceImage, 0, progress);
    }

    /**
     * Per-compartment intensity extraction. When {@code nucleusBand} is non-null
     * each object gets, per channel, the bare whole-cell mean ({@code "<m>"}) plus
     * the three compartment keys {@code "<m>: Cell: Mean"},
     * {@code "<m>: Nucleus: Mean"} and {@code "<m>: Cytoplasm: Mean"}. Cytoplasm
     * is cell − nucleus, keyed by the CELL label (see
     * {@link LabelIntensity#meanPerCompartment}). These EXACT keys match mirage's
     * combined cell export so flowpath / qUMAP consume AnnoMask output unchanged.
     *
     * <p>When {@code nucleusBand} is null this is identical to the whole-cell-only
     * path: only the bare {@code "<m>"} key is written.</p>
     *
     * @param nucleusBand nucleus mask aligned to {@code labelBand}, or null for
     *                    the single-mask (whole-cell-only) behaviour
     */
    public static void extract(ImageServer<BufferedImage> server,
                               Collection<? extends PathObject> detections,
                               SimpleImage labelBand,
                               SimpleImage nucleusBand,
                               int maxLabel,
                               ProgressListener progress) throws IOException {
        extract(server, detections, labelBand, nucleusBand, maxLabel, null, 0, progress);
    }

    /**
     * Canonical entry point. Package-private so tests can force the strip path
     * via {@code forcedStripHeight}.
     *
     * @param sourceImage      already-decoded host image to reuse, or null to read
     * @param forcedStripHeight if &gt; 0, process in strips of this many rows
     *                          regardless of the memory budget (test hook); if
     *                          &le; 0 the strip height is chosen automatically
     */
    static void extract(ImageServer<BufferedImage> server,
                        Collection<? extends PathObject> detections,
                        SimpleImage labelBand,
                        SimpleImage nucleusBand,
                        int maxLabel,
                        BufferedImage sourceImage,
                        int forcedStripHeight,
                        ProgressListener progress) throws IOException {
        if (detections == null || detections.isEmpty()) {
            return;
        }
        List<ImageChannel> channels = server.getMetadata().getChannels();
        if (channels == null || channels.isEmpty()) {
            return;
        }
        int total = channels.size();
        boolean compartments = nucleusBand != null;
        String[] names = new String[total];
        for (int c = 0; c < total; c++) {
            names[c] = channels.get(c).getName();
        }

        int width = server.getWidth();
        int height = server.getHeight();
        boolean labelMatchesServer = labelBand.getWidth() == width && labelBand.getHeight() == height;

        Means means;
        if (sourceImage != null) {
            // Channel mode: reuse the raster already decoded for tracing.
            means = extractWholeImage(sourceImage.getRaster(), total, compartments,
                    labelBand, nucleusBand, maxLabel, progress);
        } else {
            int stripHeight = forcedStripHeight > 0
                    ? Math.min(forcedStripHeight, height)
                    : chooseStripHeight(server, width, height, total);
            if (stripHeight >= height || !labelMatchesServer) {
                // Fits in memory (or sizes don't line up for striping): read once.
                BufferedImage img = server.readRegion(RegionRequest.createInstance(server));
                means = extractWholeImage(img.getRaster(), total, compartments,
                        labelBand, nucleusBand, maxLabel, progress);
            } else {
                means = extractInStrips(server, width, height, total, compartments,
                        labelBand, nucleusBand, maxLabel, stripHeight, progress);
            }
        }

        writeMeasurements(detections, names, means, total, compartments, maxLabel);
    }

    /**
     * Streamed whole-cell extraction for masks too large to hold in memory. The
     * label is read strip-by-strip from {@code labelSource} (never the whole band
     * at once) alongside the host image's channel strips, and the bincount is
     * accumulated across strips. Row-major strip order keeps the result
     * bit-identical to the whole-image path. Whole-cell only (no compartments).
     *
     * @param labelSource      tile source for the label band; must match the
     *                         server's pixel dimensions
     * @param forcedStripHeight if &gt; 0, force this strip height (test hook)
     */
    public static void extractStreamed(ImageServer<BufferedImage> server,
                                       Collection<? extends PathObject> detections,
                                       MaskConverter.LabelSource labelSource,
                                       int maxLabel,
                                       int forcedStripHeight,
                                       ProgressListener progress) throws IOException {
        if (detections == null || detections.isEmpty()) {
            return;
        }
        List<ImageChannel> channels = server.getMetadata().getChannels();
        if (channels == null || channels.isEmpty()) {
            return;
        }
        int total = channels.size();
        String[] names = new String[total];
        for (int c = 0; c < total; c++) {
            names[c] = channels.get(c).getName();
        }
        int width = server.getWidth();
        int height = server.getHeight();
        if (labelSource.getWidth() != width || labelSource.getHeight() != height) {
            throw new IllegalArgumentException("label source " + labelSource.getWidth() + "x"
                    + labelSource.getHeight() + " does not match image " + width + "x" + height);
        }
        int stripHeight = forcedStripHeight > 0
                ? Math.min(forcedStripHeight, height)
                : chooseStripHeight(server, width, height, total);

        double[][] sum = new double[total][maxLabel + 1];
        long[][] count = new long[total][maxLabel + 1];
        int stripCount = (height + stripHeight - 1) / stripHeight;
        int stripIndex = 0;
        for (int y = 0; y < height; y += stripHeight) {
            int h = Math.min(stripHeight, height - y);
            float[] labelStrip = SimpleImages.getPixels(labelSource.readTile(0, y, width, h), true);
            Raster stripRaster = server.readRegion(1.0, 0, y, width, h).getRaster();
            IntStream.range(0, total).parallel().forEach(c -> {
                float[] channelStrip = SimpleImages.getPixels(ContourTracing.extractBand(stripRaster, c), true);
                LabelIntensity.accumulate(labelStrip, channelStrip, maxLabel, sum[c], count[c]);
            });
            if (progress != null) {
                progress.update(++stripIndex, stripCount);
            }
        }

        Means means = new Means(total, false);
        for (int c = 0; c < total; c++) {
            means.cell[c] = LabelIntensity.toMeans(sum[c], count[c], maxLabel);
        }
        writeMeasurements(detections, names, means, total, false, maxLabel);
    }

    /**
     * Whole-image path: every channel is read from one raster and reduced in
     * parallel (channels are independent and each writes only its own slot).
     */
    private static Means extractWholeImage(Raster raster, int total, boolean compartments,
                                           SimpleImage labelBand, SimpleImage nucleusBand,
                                           int maxLabel, ProgressListener progress) {
        // Flatten the mask band(s) once and reuse across every channel rather
        // than re-reading them per channel via SimpleImage.getValue(x,y).
        float[] labelFlat = SimpleImages.getPixels(labelBand, true);
        float[] nucleusFlat = compartments ? SimpleImages.getPixels(nucleusBand, true) : null;

        Means means = new Means(total, compartments);
        AtomicInteger done = new AtomicInteger();
        IntStream.range(0, total).parallel().forEach(c -> {
            float[] channelFlat = SimpleImages.getPixels(ContourTracing.extractBand(raster, c), true);
            if (compartments) {
                LabelIntensity.CompartmentMeans cm =
                        LabelIntensity.meanPerCompartment(labelFlat, nucleusFlat, channelFlat, maxLabel);
                means.cell[c] = cm.cell();
                means.nucleus[c] = cm.nucleus();
                means.cytoplasm[c] = cm.cytoplasm();
            } else {
                means.cell[c] = LabelIntensity.meanPerLabel(labelFlat, channelFlat, maxLabel);
            }
            if (progress != null) {
                progress.update(done.incrementAndGet(), total);
            }
        });
        return means;
    }

    /**
     * Strip path: read full-width strips top to bottom, accumulating the
     * bincount across strips. Strips are processed sequentially (so the
     * row-major summation order — and thus the result — matches a single pass),
     * while channels within a strip reduce in parallel into per-channel
     * accumulators.
     */
    private static Means extractInStrips(ImageServer<BufferedImage> server,
                                         int width, int height, int total, boolean compartments,
                                         SimpleImage labelBand, SimpleImage nucleusBand,
                                         int maxLabel, int stripHeight,
                                         ProgressListener progress) throws IOException {
        float[] labelFull = SimpleImages.getPixels(labelBand, true);
        float[] nucleusFull = compartments ? SimpleImages.getPixels(nucleusBand, true) : null;

        // Per-channel running accumulators, persisted across strips.
        double[][] sumCell = new double[total][maxLabel + 1];
        long[][] countCell = new long[total][maxLabel + 1];
        double[][] sumNuc = compartments ? new double[total][maxLabel + 1] : null;
        long[][] countNuc = compartments ? new long[total][maxLabel + 1] : null;
        double[][] sumCyto = compartments ? new double[total][maxLabel + 1] : null;
        long[][] countCyto = compartments ? new long[total][maxLabel + 1] : null;

        int stripCount = (height + stripHeight - 1) / stripHeight;
        int stripIndex = 0;
        for (int y = 0; y < height; y += stripHeight) {
            int h = Math.min(stripHeight, height - y);
            BufferedImage stripImg = server.readRegion(1.0, 0, y, width, h);
            Raster stripRaster = stripImg.getRaster();
            int from = y * width;
            int len = width * h;
            float[] labelStrip = Arrays.copyOfRange(labelFull, from, from + len);
            float[] nucleusStrip = compartments ? Arrays.copyOfRange(nucleusFull, from, from + len) : null;

            IntStream.range(0, total).parallel().forEach(c -> {
                float[] channelStrip = SimpleImages.getPixels(ContourTracing.extractBand(stripRaster, c), true);
                if (compartments) {
                    LabelIntensity.accumulateCompartments(labelStrip, nucleusStrip, channelStrip, maxLabel,
                            sumCell[c], countCell[c], sumNuc[c], countNuc[c], sumCyto[c], countCyto[c]);
                } else {
                    LabelIntensity.accumulate(labelStrip, channelStrip, maxLabel, sumCell[c], countCell[c]);
                }
            });
            if (progress != null) {
                progress.update(++stripIndex, stripCount);
            }
        }

        Means means = new Means(total, compartments);
        for (int c = 0; c < total; c++) {
            means.cell[c] = LabelIntensity.toMeans(sumCell[c], countCell[c], maxLabel);
            if (compartments) {
                means.nucleus[c] = LabelIntensity.toMeans(sumNuc[c], countNuc[c], maxLabel);
                means.cytoplasm[c] = LabelIntensity.toMeans(sumCyto[c], countCyto[c], maxLabel);
            }
        }
        return means;
    }

    /**
     * Largest strip height (in rows) whose channel pixels stay under
     * {@link #CHANNEL_MEMORY_BUDGET_BYTES}. Returns at least 1 and at most the
     * image height (so callers can detect "fits in one read" via {@code >= height}).
     */
    private static int chooseStripHeight(ImageServer<BufferedImage> server, int width, int height, int total) {
        long bytesPerSample = Math.max(1, server.getPixelType().getBytesPerPixel());
        long rowBytes = (long) width * total * bytesPerSample;
        if (rowBytes <= 0) {
            return height;
        }
        long rows = CHANNEL_MEMORY_BUDGET_BYTES / rowBytes;
        if (rows >= height) {
            return height;
        }
        return (int) Math.max(1, rows);
    }

    private static void writeMeasurements(Collection<? extends PathObject> detections,
                                          String[] names, Means means, int total,
                                          boolean compartments, int maxLabel) {
        // Write every channel's value into each object's MeasurementList in a
        // single try-with-resources — close() compacts the list for
        // serialisation and must run after all puts.
        for (PathObject det : detections) {
            int label = parseLabel(det.getName());
            if (label <= 0 || label > maxLabel) continue;
            try (MeasurementList ml = det.getMeasurementList()) {
                for (int c = 0; c < total; c++) {
                    // Bare whole-cell-mean key preserved for backward compatibility.
                    ml.put(names[c], means.cell[c][label]);
                    if (compartments) {
                        ml.put(names[c] + ": Cell: Mean", means.cell[c][label]);
                        ml.put(names[c] + ": Nucleus: Mean", means.nucleus[c][label]);
                        ml.put(names[c] + ": Cytoplasm: Mean", means.cytoplasm[c][label]);
                    }
                }
            }
        }
    }

    private static int parseLabel(String name) {
        if (name == null || name.isEmpty()) return -1;
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }

    /** Per-channel means, indexed {@code [channel][label]}. */
    private static final class Means {
        final double[][] cell;
        final double[][] nucleus;
        final double[][] cytoplasm;

        Means(int total, boolean compartments) {
            this.cell = new double[total][];
            this.nucleus = compartments ? new double[total][] : null;
            this.cytoplasm = compartments ? new double[total][] : null;
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void update(int done, int total);
    }
}
