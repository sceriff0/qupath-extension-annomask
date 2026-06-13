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

    @Test
    void manyChannelsParallelPathStaysCorrect() throws Exception {
        // Exercises the parallel per-channel reduction with enough channels to
        // span the ForkJoinPool. Channel k has every pixel = (label * 100 + k),
        // so label L's mean on channel k is exactly L*100 + k — independent of
        // pixel count, which makes the expected value trivial to assert and any
        // cross-channel data race obvious.
        int channelCount = 16;
        float[] labelData = {1, 1, 0, 1, 0, 2, 0, 2, 2}; // labels 1 and 2
        float[][] bands = new float[channelCount][labelData.length];
        for (int k = 0; k < channelCount; k++) {
            for (int p = 0; p < labelData.length; p++) {
                int label = (int) labelData[p];
                bands[k][p] = label * 100 + k; // background pixels never read
            }
        }
        BufferedImage img = multiChannelFloatImage(3, 3, bands);
        String[] names = new String[channelCount];
        for (int k = 0; k < channelCount; k++) names[k] = "C" + k;
        ImageServer<BufferedImage> server = new WrappedBufferedImageServer(
                "test", img, ImageChannel.getChannelList(names));
        SimpleImage labelBand = SimpleImages.createFloatImage(labelData, 3, 3);

        PathObject det1 = detectionWithName("1", 0, 0, 3, 3);
        PathObject det2 = detectionWithName("2", 0, 0, 3, 3);
        IntensityExtractor.extract(server, List.of(det1, det2), labelBand, 2, null);

        for (int k = 0; k < channelCount; k++) {
            assertEquals(100 + k, det1.getMeasurements().get("C" + k).doubleValue(), 1e-9,
                    "label 1, channel " + k);
            assertEquals(200 + k, det2.getMeasurements().get("C" + k).doubleValue(), 1e-9,
                    "label 2, channel " + k);
        }
    }

    private static BufferedImage multiChannelFloatImage(int w, int h, float[][] bands) {
        int n = w * h;
        for (float[] b : bands) {
            if (b.length != n) throw new IllegalArgumentException("channel size mismatch");
        }
        int bandCount = bands.length;
        DataBufferFloat buffer = new DataBufferFloat(bands, n);
        BandedSampleModel sampleModel = new BandedSampleModel(DataBuffer.TYPE_FLOAT, w, h, bandCount);
        WritableRaster raster = Raster.createWritableRaster(sampleModel, buffer, new Point(0, 0));
        int[] bits = new int[bandCount];
        java.util.Arrays.fill(bits, 32);
        // BufferedImage validates ColorModel.isCompatibleRaster, which requires
        // numComponents == raster bands. No standard ColorSpace has N components,
        // so use a stub ColorSpace of the right arity. AnnoMask never invokes any
        // colour conversion — it reads raster bands directly.
        ColorSpace cs = new ColorSpace(ColorSpace.TYPE_GRAY, bandCount) {
            @Override public float[] toRGB(float[] c) { return new float[3]; }
            @Override public float[] fromRGB(float[] rgb) { return new float[getNumComponents()]; }
            @Override public float[] toCIEXYZ(float[] c) { return new float[3]; }
            @Override public float[] fromCIEXYZ(float[] c) { return new float[getNumComponents()]; }
        };
        ColorModel cm = new ComponentColorModel(
                cs, bits, false, false, Transparency.OPAQUE, DataBuffer.TYPE_FLOAT);
        return new BufferedImage(cm, raster, false, null);
    }

    // 3-wide × 4-tall label map; labels 2 and 3 straddle strip boundaries so a
    // broken cross-strip accumulation would show up.
    //   1 1 2
    //   1 0 2
    //   3 3 2
    //   3 0 0
    private static final int LW = 3, LH = 4;
    private static final float[] LABELS = {1, 1, 2, 1, 0, 2, 3, 3, 2, 3, 0, 0};
    private static final float[] CH0 = {5, 9, 2, 7, 3, 11, 4, 8, 6, 1, 10, 12};
    private static final float[] CH1 = {50, 90, 20, 70, 30, 110, 40, 80, 60, 10, 100, 120};
    private static final float[] NUC = {1, 0, 0, 0, 0, 9, 3, 0, 0, 0, 0, 0};

    @Test
    void stripPathIsBitIdenticalToWholeImage() throws Exception {
        ImageServer<BufferedImage> server = labelGridServer();
        SimpleImage labelBand = SimpleImages.createFloatImage(LABELS, LW, LH);

        List<PathObject> whole = labels123();
        IntensityExtractor.extract(server, whole, labelBand, 3, null); // whole-image path

        for (int stripHeight : new int[]{1, 2, 3}) {
            List<PathObject> strip = labels123();
            // Package-private hook: force the strip path regardless of image size.
            IntensityExtractor.extract(server, strip, labelBand, null, 3, null, stripHeight, null);
            assertSameMeasurements(whole, strip, List.of("DAPI", "CD45"),
                    "strip height " + stripHeight);
        }
    }

    @Test
    void stripPathCompartmentsAreBitIdenticalToWholeImage() throws Exception {
        ImageServer<BufferedImage> server = labelGridServer();
        SimpleImage labelBand = SimpleImages.createFloatImage(LABELS, LW, LH);
        SimpleImage nucBand = SimpleImages.createFloatImage(NUC, LW, LH);

        List<PathObject> whole = labels123();
        IntensityExtractor.extract(server, whole, labelBand, nucBand, 3, null); // whole-image path

        List<String> keys = List.of(
                "DAPI", "DAPI: Cell: Mean", "DAPI: Nucleus: Mean", "DAPI: Cytoplasm: Mean",
                "CD45", "CD45: Cell: Mean", "CD45: Nucleus: Mean", "CD45: Cytoplasm: Mean");
        for (int stripHeight : new int[]{1, 2, 3}) {
            List<PathObject> strip = labels123();
            IntensityExtractor.extract(server, strip, labelBand, nucBand, 3, null, stripHeight, null);
            assertSameMeasurements(whole, strip, keys, "compartments strip height " + stripHeight);
        }
    }

    private static ImageServer<BufferedImage> labelGridServer() {
        BufferedImage img = twoChannelFloatImage(LW, LH, CH0, CH1);
        return new WrappedBufferedImageServer("test", img, ImageChannel.getChannelList("DAPI", "CD45"));
    }

    private static List<PathObject> labels123() {
        return List.of(
                detectionWithName("1", 0, 0, LW, LH),
                detectionWithName("2", 0, 0, LW, LH),
                detectionWithName("3", 0, 0, LW, LH));
    }

    private static void assertSameMeasurements(List<PathObject> expected, List<PathObject> actual,
                                               List<String> keys, String context) {
        for (int i = 0; i < expected.size(); i++) {
            for (String key : keys) {
                double e = expected.get(i).getMeasurements().get(key).doubleValue();
                double a = actual.get(i).getMeasurements().get(key).doubleValue();
                assertEquals(e, a, 0.0,
                        context + ": label " + expected.get(i).getName() + " key '" + key + "'");
            }
        }
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
