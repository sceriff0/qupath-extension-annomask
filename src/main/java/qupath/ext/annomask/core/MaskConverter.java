package qupath.ext.annomask.core;

import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Converts a labeled segmentation mask into QuPath detection objects using
 * QuPath's built-in {@link ContourTracing}. Two sources are supported:
 * <ul>
 *   <li>a labeled TIFF file on disk (file mode), and</li>
 *   <li>a labeled channel of the currently open image (channel mode).</li>
 * </ul>
 * Each output detection carries its integer mask label as {@code PathObject.name}.
 * {@link ContourTracing} itself groups pixels by label ID — a disconnected
 * label becomes a single detection with a multi-polygon ROI, matching mirage's
 * one-cell-per-label-ID convention. {@link #mergeByLabel} is kept as a safety
 * net for any future codepath that builds detections outside ContourTracing;
 * on the current paths it is a no-op.
 */
public final class MaskConverter {

    /**
     * Upper bound on a plausible label ID. Intensity sizing allocates arrays of
     * {@code maxLabel + 1} per channel, so a runaway max (typically from picking
     * a raw intensity channel instead of the label channel) would otherwise
     * exhaust memory. Tens of millions of objects is already far beyond any real
     * segmentation; past this we fail with an actionable message instead.
     */
    static final int MAX_REASONABLE_LABEL = 50_000_000;

    private static final BiFunction<ROI, Number, PathObject> DETECTION_FACTORY = (roi, label) -> {
        PathObject det = PathObjects.createDetectionObject(roi);
        det.setName(String.valueOf(label.intValue()));
        return det;
    };

    private MaskConverter() {}

    /**
     * Rejects label ranges so large they would blow up intensity allocation,
     * with a message pointing at the usual cause (a non-label channel selected).
     */
    static int checkLabelRange(int maxLabel) {
        if (maxLabel > MAX_REASONABLE_LABEL) {
            throw new IllegalArgumentException(
                    "Maximum label value " + maxLabel + " is implausibly large for a segmentation "
                    + "mask (limit " + MAX_REASONABLE_LABEL + "). This usually means a raw intensity "
                    + "channel was selected instead of the integer label channel/mask.");
        }
        return maxLabel;
    }

    /**
     * File mode. Reads the labeled TIFF at {@code maskPath} and returns one
     * detection per unique non-zero label, plus the raster label band so
     * intensity quantification can run over the same pixels.
     */
    public static MaskResult convert(Path maskPath, ImageServer<BufferedImage> server) throws IOException {
        RegionRequest region = RegionRequest.createInstance(server);
        List<PathObject> raw = ContourTracing.labelsToObjects(maskPath, region, DETECTION_FACTORY);
        SimpleImage labelBand = readMaskBand(maskPath);
        int maxLabel = checkLabelRange(LabelIntensity.maxLabel(labelBand));
        return new MaskResult(mergeByLabel(raw), labelBand, maxLabel);
    }

    /**
     * Channel mode. Reads the full image at downsample=1, extracts the given
     * band as a single-channel image, and traces contours around each non-zero
     * label. No temporary file is written.
     */
    public static MaskResult convertChannel(ImageServer<BufferedImage> server, int channelIndex) throws IOException {
        RegionRequest region = RegionRequest.createInstance(server);
        BufferedImage img = server.readRegion(region);
        SimpleImage band = ContourTracing.extractBand(img.getRaster(), channelIndex);
        List<PathObject> raw = convertFromSimpleImage(band, region);
        int maxLabel = checkLabelRange(LabelIntensity.maxLabel(band));
        // The host image is already decoded here; hand it to intensity extraction
        // so it does not read the whole image a second time.
        return new MaskResult(mergeByLabel(raw), band, maxLabel, img);
    }

    /**
     * Shared tracing path; package-private for unit tests. Uses the auto-detect
     * sentinel ({@code minLabel=1, maxLabel=-1}) so all positive integer labels
     * in the band are traced — same convention QuPath's own
     * {@link ContourTracing#labelsToObjects(Path, RegionRequest,
     * java.util.function.BiFunction)} uses internally. A null {@code region}
     * is treated as a full-band region at downsample 1.
     *
     * Note: this method returns raw per-component detections (no merging).
     * Callers that want one detection per label ID should pass the result
     * through {@link #mergeByLabel(List)}; the public {@link #convert} and
     * {@link #convertChannel} entry points already do this.
     */
    static List<PathObject> convertFromSimpleImage(SimpleImage band, RegionRequest region) {
        RegionRequest effectiveRegion = region != null
                ? region
                : RegionRequest.createInstance("annomask-mask", 1.0, 0, 0, band.getWidth(), band.getHeight());
        return ContourTracing.createObjects(band, effectiveRegion, 1, -1, DETECTION_FACTORY);
    }

    /**
     * Merges detections that share a label name (i.e. came from the same
     * integer label ID in the mask) into a single detection with a unioned ROI.
     * Preserves original ordering by first occurrence.
     */
    static List<PathObject> mergeByLabel(List<PathObject> detections) {
        Map<String, List<PathObject>> byLabel = new LinkedHashMap<>();
        for (PathObject det : detections) {
            byLabel.computeIfAbsent(det.getName(), k -> new ArrayList<>()).add(det);
        }
        List<PathObject> merged = new ArrayList<>(byLabel.size());
        for (Map.Entry<String, List<PathObject>> e : byLabel.entrySet()) {
            List<PathObject> group = e.getValue();
            if (group.size() == 1) {
                merged.add(group.get(0));
                continue;
            }
            List<ROI> rois = new ArrayList<>(group.size());
            for (PathObject g : group) rois.add(g.getROI());
            ROI union = RoiTools.union(rois);
            PathObject m = PathObjects.createDetectionObject(union);
            m.setName(e.getKey());
            merged.add(m);
        }
        return merged;
    }

    /**
     * Dual-mask mode (file). Reads a labeled cell mask and a labeled nucleus
     * mask from disk and builds one {@link qupath.lib.objects.PathCellObject}
     * per cell label (whole-cell ROI + paired nucleus ROI). The returned
     * {@link MaskResult#labelBand} / {@link MaskResult#maxLabel} are the CELL
     * mask's, so per-compartment intensity extraction keys by cell label.
     */
    public static MaskResult convertCells(Path cellMaskPath, Path nucleusMaskPath) throws IOException {
        SimpleImage cellBand = readMaskBand(cellMaskPath);
        SimpleImage nucleusBand = readMaskBand(nucleusMaskPath);
        return convertCells(cellBand, nucleusBand,
                RegionRequest.createInstance("annomask-cell-mask", 1.0, 0, 0,
                        cellBand.getWidth(), cellBand.getHeight()));
    }

    /**
     * Dual-mask mode (raster). Traces both bands, pairs each nucleus to the cell
     * label it most overlaps (the same rule mirage uses — assignment by maximum
     * overlap area), and emits a cell object per cell label. A cell with no
     * overlapping nucleus becomes a cell object without a nucleus ROI.
     *
     * <p>A null {@code region} is treated as a full-band region at downsample 1.</p>
     *
     * @param cellBand    labeled whole-cell mask (one label ID per cell)
     * @param nucleusBand labeled nucleus mask (label IDs need not match the cells)
     */
    public static MaskResult convertCells(SimpleImage cellBand, SimpleImage nucleusBand, RegionRequest region) {
        RegionRequest effectiveRegion = region != null
                ? region
                : RegionRequest.createInstance("annomask-cell-mask", 1.0, 0, 0,
                        cellBand.getWidth(), cellBand.getHeight());

        // One ROI per cell label and one ROI per nucleus label.
        Map<String, ROI> cellRois = roiByLabel(convertFromSimpleImage(cellBand, effectiveRegion));
        Map<String, ROI> nucleusRois = roiByLabel(convertFromSimpleImage(nucleusBand, effectiveRegion));

        // Pair each nucleus to the cell label it overlaps most (overlap area).
        Map<String, ROI> nucleusForCell = new LinkedHashMap<>();
        for (ROI nucRoi : nucleusRois.values()) {
            String bestCell = null;
            double bestOverlap = 0.0;
            for (Map.Entry<String, ROI> ce : cellRois.entrySet()) {
                double overlap = RoiTools.intersectionArea(ce.getValue(), nucRoi);
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    bestCell = ce.getKey();
                }
            }
            if (bestCell != null) {
                // If two nuclei map to the same cell, keep the larger overlap by
                // only replacing when this nucleus has not yet been beaten; the
                // simplest stable rule is first-wins per cell with max overlap.
                nucleusForCell.merge(bestCell, nucRoi, (existing, candidate) ->
                        candidate.getArea() > existing.getArea() ? candidate : existing);
            }
        }

        List<PathObject> cells = new ArrayList<>(cellRois.size());
        for (Map.Entry<String, ROI> ce : cellRois.entrySet()) {
            ROI nucRoi = nucleusForCell.get(ce.getKey());
            PathObject cell = PathObjects.createCellObject(ce.getValue(), nucRoi);
            cell.setName(ce.getKey());
            cells.add(cell);
        }

        int maxLabel = checkLabelRange(LabelIntensity.maxLabel(cellBand));
        return new MaskResult(cells, cellBand, maxLabel);
    }

    /**
     * Collapses traced per-component detections into one ROI per label ID
     * (via {@link #mergeByLabel}), preserving first-occurrence ordering.
     */
    private static Map<String, ROI> roiByLabel(List<PathObject> traced) {
        Map<String, ROI> byLabel = new LinkedHashMap<>();
        for (PathObject merged : mergeByLabel(traced)) {
            byLabel.put(merged.getName(), merged.getROI());
        }
        return byLabel;
    }

    // ------------------------------------------------------------------
    // Streamed (tiled) path — for images too large to hold a full label band.
    // ------------------------------------------------------------------

    /** Default tile/strip edge for streamed tracing and quantification. */
    public static final int DEFAULT_TILE_SIZE = 4096;

    /**
     * A source of label pixels that can be read one region at a time, so a mask
     * larger than memory is never fully materialised. Backed either by a channel
     * of the host image or by a separate mask file.
     */
    public interface LabelSource extends AutoCloseable {
        int getWidth();
        int getHeight();
        /** Label band for the given region (top-left {@code x,y}, size {@code w×h}). */
        SimpleImage readTile(int x, int y, int w, int h) throws IOException;
        @Override default void close() throws IOException {}
    }

    /** Label source backed by one channel of an already-open server (channel mode). */
    public static LabelSource channelLabelSource(ImageServer<BufferedImage> server, int channelIndex) {
        return new LabelSource() {
            @Override public int getWidth() { return server.getWidth(); }
            @Override public int getHeight() { return server.getHeight(); }
            @Override public SimpleImage readTile(int x, int y, int w, int h) throws IOException {
                return ContourTracing.extractBand(server.readRegion(1.0, x, y, w, h).getRaster(), channelIndex);
            }
        };
    }

    /**
     * Label source backed by a mask file on disk (file mode). Opens its own
     * server, which {@link LabelSource#close()} releases — use try-with-resources.
     */
    public static LabelSource fileLabelSource(Path maskPath) throws IOException {
        final ImageServer<BufferedImage> maskServer;
        try {
            maskServer = ImageServerProvider.buildServer(maskPath.toUri().toString(), BufferedImage.class);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to open mask file " + maskPath, e);
        }
        return new LabelSource() {
            @Override public int getWidth() { return maskServer.getWidth(); }
            @Override public int getHeight() { return maskServer.getHeight(); }
            @Override public SimpleImage readTile(int x, int y, int w, int h) throws IOException {
                return ContourTracing.extractBand(maskServer.readRegion(1.0, x, y, w, h).getRaster(), 0);
            }
            @Override public void close() throws IOException {
                try {
                    maskServer.close();
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("Failed to close mask server", e);
                }
            }
        };
    }

    /** Detections from a streamed trace plus the largest label seen. */
    public record StreamedTrace(List<PathObject> detections, int maxLabel) {}

    /**
     * Traces a label mask tile-by-tile and merges per label across tiles, so the
     * whole band is never held in memory. Each tile is traced in its own
     * coordinate frame (the {@link RegionRequest} offset places the ROIs in
     * global image space); {@link #mergeByLabel} then unions the components of
     * each label ID — including those split by a tile seam — into one detection.
     * Because every label ID is globally unique in a mask, this reproduces the
     * one-detection-per-label result of a whole-image trace.
     *
     * @param tileSize tile edge in pixels; {@link #DEFAULT_TILE_SIZE} is typical
     */
    public static StreamedTrace traceStreamed(LabelSource labels, int tileSize) throws IOException {
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be positive: " + tileSize);
        }
        int width = labels.getWidth();
        int height = labels.getHeight();
        List<PathObject> all = new ArrayList<>();
        int maxLabel = 0;
        for (int y = 0; y < height; y += tileSize) {
            int th = Math.min(tileSize, height - y);
            for (int x = 0; x < width; x += tileSize) {
                int tw = Math.min(tileSize, width - x);
                SimpleImage tile = labels.readTile(x, y, tw, th);
                int m = LabelIntensity.maxLabel(tile);
                if (m > maxLabel) {
                    maxLabel = m;
                }
                RegionRequest region = RegionRequest.createInstance("annomask-stream", 1.0, x, y, tw, th);
                all.addAll(ContourTracing.createObjects(tile, region, 1, -1, DETECTION_FACTORY));
            }
        }
        return new StreamedTrace(mergeByLabel(all), checkLabelRange(maxLabel));
    }

    private static SimpleImage readMaskBand(Path maskPath) throws IOException {
        // Open the mask file as its own server — it's a separate TIFF, not the
        // host image. ContourTracing.labelsToObjects did the same thing
        // internally to produce the contours; we do it here to expose the raw
        // label band to the intensity pass.
        try (ImageServer<BufferedImage> maskServer = ImageServerProvider.buildServer(
                maskPath.toUri().toString(), BufferedImage.class)) {
            BufferedImage img = maskServer.readRegion(RegionRequest.createInstance(maskServer));
            return ContourTracing.extractBand(img.getRaster(), 0);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to read mask band from " + maskPath, e);
        }
    }

    /**
     * Bundle of a traced mask and the raster it was traced from.
     *
     * <p>{@code sourceImage} is the already-decoded host image when the label
     * came from one of its own channels (channel mode), so intensity extraction
     * can reuse it instead of decoding the whole image a second time. It is
     * {@code null} in file/dual-mask modes, where the channels live in a
     * different image than the mask.</p>
     */
    public record MaskResult(List<PathObject> detections, SimpleImage labelBand, int maxLabel,
                             BufferedImage sourceImage) {
        /** Convenience constructor for modes with no reusable source image. */
        public MaskResult(List<PathObject> detections, SimpleImage labelBand, int maxLabel) {
            this(detections, labelBand, maxLabel, null);
        }
    }
}
