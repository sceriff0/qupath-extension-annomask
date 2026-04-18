package qupath.ext.annomask.core;

import qupath.lib.analysis.features.ObjectMeasurements;
import qupath.lib.analysis.features.ObjectMeasurements.Compartments;
import qupath.lib.analysis.features.ObjectMeasurements.Measurements;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;

import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Attaches mean intensity per channel per detection using QuPath's
 * {@link ObjectMeasurements} API. Parallelised over detections so throughput
 * scales with cores. Measurements failures are logged but do not abort the run.
 */
public final class IntensityExtractor {

    private IntensityExtractor() {}

    /**
     * Runs intensity extraction for every detection in {@code detections}.
     *
     * @param server the image server whose channels will be sampled
     * @param detections the objects to annotate in place
     * @param progress receives (done, total) on roughly every 500 completed
     *                 detections so UI code can update a status label
     */
    public static void extract(ImageServer<BufferedImage> server,
                               Collection<? extends PathObject> detections,
                               ProgressListener progress) {
        if (detections == null || detections.isEmpty()) {
            return;
        }
        int total = detections.size();
        AtomicInteger done = new AtomicInteger();
        int reportEvery = Math.max(1, Math.min(500, total / 20));
        var measurements = EnumSet.of(Measurements.MEAN);
        var compartments = EnumSet.of(Compartments.CELL);

        detections.parallelStream().forEach(det -> {
            try {
                ObjectMeasurements.addIntensityMeasurements(server, det, 1.0, measurements, compartments);
            } catch (Exception ex) {
                // Swallow per-object errors; measurements simply stay empty on that object.
            }
            int d = done.incrementAndGet();
            if (progress != null && (d == total || d % reportEvery == 0)) {
                progress.update(d, total);
            }
        });
    }

    @FunctionalInterface
    public interface ProgressListener {
        void update(int done, int total);
    }
}
