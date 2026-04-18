package qupath.ext.annomask.core;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Quick sanity check on a channel before treating it as a labeled mask.
 * Samples a small centre tile and reports whether the values look like
 * integer labels (no fractional parts, at least two distinct non-zero labels).
 * Reports are advisory — the UI warns but does not block conversion.
 */
public final class MaskValidator {

    private MaskValidator() {}

    public static Report quickCheck(ImageServer<BufferedImage> server, int channelIndex) throws IOException {
        int w = server.getWidth();
        int h = server.getHeight();
        int size = 256;
        int x = Math.max(0, (w - size) / 2);
        int y = Math.max(0, (h - size) / 2);
        int sw = Math.min(size, w - x);
        int sh = Math.min(size, h - y);
        if (sw <= 0 || sh <= 0) {
            return new Report(false, false, 0);
        }
        RegionRequest region = RegionRequest.createInstance(server.getPath(), 1.0, x, y, sw, sh);
        BufferedImage img = server.readRegion(region);
        Raster raster = img.getRaster();
        int samples = 0;
        int integerSamples = 0;
        Set<Integer> distinct = new HashSet<>();
        int maxSamples = 10_000;
        outer:
        for (int yy = 0; yy < sh; yy++) {
            for (int xx = 0; xx < sw; xx++) {
                float v = raster.getSampleFloat(xx, yy, Math.min(channelIndex, raster.getNumBands() - 1));
                samples++;
                if (v == Math.floor(v) && !Float.isInfinite(v)) {
                    integerSamples++;
                }
                if (v != 0f) {
                    distinct.add((int) v);
                }
                if (samples >= maxSamples) {
                    break outer;
                }
            }
        }
        boolean integerLike = samples > 0 && (integerSamples / (double) samples) >= 0.99;
        boolean hasEnoughLabels = distinct.size() >= 1;
        return new Report(integerLike, hasEnoughLabels, distinct.size());
    }

    public static final class Report {
        private final boolean integerLike;
        private final boolean hasLabels;
        private final int distinctNonZero;

        Report(boolean integerLike, boolean hasLabels, int distinctNonZero) {
            this.integerLike = integerLike;
            this.hasLabels = hasLabels;
            this.distinctNonZero = distinctNonZero;
        }

        public boolean looksLikeLabels() {
            return integerLike && hasLabels;
        }

        public boolean isIntegerLike() {
            return integerLike;
        }

        public int getDistinctNonZero() {
            return distinctNonZero;
        }
    }
}
