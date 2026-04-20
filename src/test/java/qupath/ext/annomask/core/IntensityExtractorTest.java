package qupath.ext.annomask.core;

import org.junit.jupiter.api.Test;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.images.SimpleImages;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.awt.Point;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferFloat;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntensityExtractorTest {

    @Test
    void writesBareChannelNames() throws Exception {
        // 3x3 image with two channels "DAPI" and "CD45".
        float[] dapi = {10, 20, 0, 30, 0, 40, 0, 50, 60};
        float[] cd45 = {1, 2, 0, 3, 0, 4, 0, 5, 6};
        BufferedImage img = twoChannelFloatImage(3, 3, dapi, cd45);

        ImageServer<BufferedImage> server = new WrappedBufferedImageServer(
                "test", img, ImageChannel.getChannelList("DAPI", "CD45"));

        // Label band matching the mirage docstring example.
        SimpleImage labelBand = SimpleImages.createFloatImage(
                new float[]{1, 1, 0, 1, 0, 2, 0, 2, 2}, 3, 3);

        List<PathObject> detections = List.of(
                detectionWithName("1", 0, 0, 3, 3),
                detectionWithName("2", 0, 0, 3, 3));

        IntensityExtractor.extract(server, detections, labelBand, 2, null);

        for (PathObject d : detections) {
            Set<String> keys = d.getMeasurements().keySet();
            assertTrue(keys.contains("DAPI"), "should have bare \"DAPI\" key, got " + keys);
            assertTrue(keys.contains("CD45"), "should have bare \"CD45\" key, got " + keys);
            assertEquals(2, keys.size(), "exactly two measurements per detection");
            for (String k : keys) {
                assertTrue(!k.contains(": Cell: Mean"),
                        "measurement key must not carry QuPath suffix: " + k);
            }
        }
    }

    @Test
    void valuesMatchBincount() throws Exception {
        // Same fixture as LabelIntensityTest.meanPerLabelMatchesMirageDocstring.
        // Expected: label 1 -> DAPI 20.0, CD45 2.0; label 2 -> DAPI 50.0, CD45 5.0.
        float[] dapi = {10, 20, 0, 30, 0, 40, 0, 50, 60};
        float[] cd45 = {1, 2, 0, 3, 0, 4, 0, 5, 6};
        BufferedImage img = twoChannelFloatImage(3, 3, dapi, cd45);
        ImageServer<BufferedImage> server = new WrappedBufferedImageServer(
                "test", img, ImageChannel.getChannelList("DAPI", "CD45"));
        SimpleImage labelBand = SimpleImages.createFloatImage(
                new float[]{1, 1, 0, 1, 0, 2, 0, 2, 2}, 3, 3);

        PathObject det1 = detectionWithName("1", 0, 0, 3, 3);
        PathObject det2 = detectionWithName("2", 0, 0, 3, 3);

        IntensityExtractor.extract(server, List.of(det1, det2), labelBand, 2, null);

        assertEquals(20.0, det1.getMeasurements().get("DAPI").doubleValue(), 1e-9);
        assertEquals(2.0, det1.getMeasurements().get("CD45").doubleValue(), 1e-9);
        assertEquals(50.0, det2.getMeasurements().get("DAPI").doubleValue(), 1e-9);
        assertEquals(5.0, det2.getMeasurements().get("CD45").doubleValue(), 1e-9);
    }

    private static PathObject detectionWithName(String name, int x, int y, int w, int h) {
        PathObject d = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x, y, w, h, ImagePlane.getDefaultPlane()));
        d.setName(name);
        return d;
    }

    /**
     * Builds a 2-band planar float BufferedImage. Uses CS_GRAY + alpha so the
     * ColorModel accepts two components; AnnoMask never reads the ColorModel,
     * only the raster bands via {@code ContourTracing.extractBand}.
     */
    private static BufferedImage twoChannelFloatImage(int w, int h, float[] ch0, float[] ch1) {
        int n = w * h;
        if (ch0.length != n || ch1.length != n) {
            throw new IllegalArgumentException("channel size mismatch");
        }
        float[][] banks = { ch0, ch1 };
        DataBufferFloat buffer = new DataBufferFloat(banks, n);
        BandedSampleModel sampleModel = new BandedSampleModel(DataBuffer.TYPE_FLOAT, w, h, 2);
        WritableRaster raster = Raster.createWritableRaster(sampleModel, buffer, new Point(0, 0));
        ColorModel cm = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_GRAY),
                new int[]{32, 32}, true, false,
                Transparency.TRANSLUCENT, DataBuffer.TYPE_FLOAT);
        return new BufferedImage(cm, raster, false, null);
    }
}
