package qupath.ext.annomask.core;

import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Attaches mean intensity per channel per detection by running a bincount-style
 * pass (see {@link LabelIntensity}) over the raw label band. Intentionally does
 * NOT use QuPath's {@code ObjectMeasurements.addIntensityMeasurements}: that
 * API names measurements {@code "<channel>: Cell: Mean"}, which GatingTree and
 * qUMAP do not recognise (they look up measurements by bare channel name). This
 * path produces the same numbers and names as mirage's
 * {@code bin/quantify.py} / {@code bin/export_geojson.py}.
 */
public final class IntensityExtractor {

    private IntensityExtractor() {}

    /**
     * Runs intensity extraction for every detection in {@code detections}.
     *
     * @param server       the image server whose channels will be sampled
     * @param detections   the objects to annotate in place (names must be
     *                     positive integer label IDs produced by {@link MaskConverter})
     * @param labelBand    the raster label band that produced {@code detections}
     * @param maxLabel     the largest label ID present in {@code labelBand}
     * @param progress     receives (done, total) per channel completed; may be null
     */
    public static void extract(ImageServer<BufferedImage> server,
                               Collection<? extends PathObject> detections,
                               SimpleImage labelBand,
                               int maxLabel,
                               ProgressListener progress) throws IOException {
        if (detections == null || detections.isEmpty()) {
            return;
        }
        List<ImageChannel> channels = server.getMetadata().getChannels();
        if (channels == null || channels.isEmpty()) {
            return;
        }

        // Read the full image once — every channel needed is in this raster.
        BufferedImage img = server.readRegion(RegionRequest.createInstance(server));
        int total = channels.size();

        // One bincount pass per channel; collect all means before touching the
        // MeasurementLists so each detection can be opened once.
        String[] names = new String[total];
        double[][] meansPerChannel = new double[total][];
        for (int c = 0; c < total; c++) {
            SimpleImage channelBand = ContourTracing.extractBand(img.getRaster(), c);
            meansPerChannel[c] = LabelIntensity.meanPerLabel(labelBand, channelBand, maxLabel);
            names[c] = channels.get(c).getName();
            if (progress != null) {
                progress.update(c + 1, total);
            }
        }

        // Write every channel's value into each detection's MeasurementList in
        // a single try-with-resources — close() compacts the list for
        // serialisation and must run after all puts.
        for (PathObject det : detections) {
            int label = parseLabel(det.getName());
            if (label <= 0 || label > maxLabel) continue;
            try (MeasurementList ml = det.getMeasurementList()) {
                for (int c = 0; c < total; c++) {
                    ml.put(names[c], meansPerChannel[c][label]);
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

    @FunctionalInterface
    public interface ProgressListener {
        void update(int done, int total);
    }
}
