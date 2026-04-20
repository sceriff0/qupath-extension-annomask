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

    private static final BiFunction<ROI, Number, PathObject> DETECTION_FACTORY = (roi, label) -> {
        PathObject det = PathObjects.createDetectionObject(roi);
        det.setName(String.valueOf(label.intValue()));
        return det;
    };

    private MaskConverter() {}

    /**
     * File mode. Reads the labeled TIFF at {@code maskPath} and returns one
     * detection per unique non-zero label, plus the raster label band so
     * intensity quantification can run over the same pixels.
     */
    public static MaskResult convert(Path maskPath, ImageServer<BufferedImage> server) throws IOException {
        RegionRequest region = RegionRequest.createInstance(server);
        List<PathObject> raw = ContourTracing.labelsToObjects(maskPath, region, DETECTION_FACTORY);
        SimpleImage labelBand = readMaskBand(maskPath);
        int maxLabel = LabelIntensity.maxLabel(labelBand);
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
        int maxLabel = LabelIntensity.maxLabel(band);
        return new MaskResult(mergeByLabel(raw), band, maxLabel);
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

    /** Bundle of a traced mask and the raster it was traced from. */
    public record MaskResult(List<PathObject> detections, SimpleImage labelBand, int maxLabel) {}
}
