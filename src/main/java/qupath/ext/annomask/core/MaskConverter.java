package qupath.ext.annomask.core;

import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Converts a labeled segmentation mask into QuPath detection objects using
 * QuPath's built-in {@link ContourTracing}. Two sources are supported:
 * <ul>
 *   <li>a labeled TIFF file on disk (file mode), and</li>
 *   <li>a labeled channel of the currently open image (channel mode).</li>
 * </ul>
 * Each output detection carries its integer mask label as {@code PathObject.name}.
 */
public final class MaskConverter {

    private MaskConverter() {}

    /**
     * File mode. Reads the labeled TIFF at {@code maskPath} and returns one
     * detection per non-zero label. The {@code server} is used purely to
     * construct a region request matching the full image plane.
     */
    public static List<PathObject> convert(Path maskPath, ImageServer<BufferedImage> server) throws IOException {
        RegionRequest region = RegionRequest.createInstance(server);
        BiFunction<ROI, Number, PathObject> factory = (roi, label) -> {
            PathObject det = PathObjects.createDetectionObject(roi);
            det.setName(String.valueOf(label.intValue()));
            return det;
        };
        return ContourTracing.labelsToObjects(maskPath, region, factory);
    }

    /**
     * Channel mode. Reads the full image at downsample=1, extracts the given
     * band as a single-channel image, and traces contours around each non-zero
     * label. No temporary file is written.
     */
    public static List<PathObject> convertChannel(ImageServer<BufferedImage> server, int channelIndex) throws IOException {
        BufferedImage img = server.readRegion(RegionRequest.createInstance(server));
        SimpleImage band = ContourTracing.extractBand(img.getRaster(), channelIndex);
        return convertFromSimpleImage(band, RegionRequest.createInstance(server));
    }

    /**
     * Shared tracing path; exposed package-private for unit tests. Uses the
     * auto-detect sentinel ({@code minLabel=1, maxLabel=-1}) so all positive
     * integer labels in the band are traced — same convention QuPath's own
     * {@link ContourTracing#labelsToObjects(Path, RegionRequest,
     * java.util.function.BiFunction)} uses internally. A null {@code region}
     * is treated as a full-band region at downsample 1.
     */
    static List<PathObject> convertFromSimpleImage(SimpleImage band, RegionRequest region) {
        RegionRequest effectiveRegion = region != null
                ? region
                : RegionRequest.createInstance("annomask-mask", 1.0, 0, 0, band.getWidth(), band.getHeight());
        BiFunction<ROI, Number, PathObject> factory = (roi, label) -> {
            PathObject det = PathObjects.createDetectionObject(roi);
            det.setName(String.valueOf(label.intValue()));
            return det;
        };
        return ContourTracing.createObjects(band, effectiveRegion, 1, -1, factory);
    }

}
