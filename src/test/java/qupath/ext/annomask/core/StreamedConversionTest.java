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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the streamed (tiled) trace + quantify path against the whole-image
 * path on small fixtures, including labels that straddle tile/strip seams — the
 * case a broken cross-tile merge or a missing coordinate offset would corrupt.
 */
class StreamedConversionTest {

    // 4×4 label map. With tile size 2 the tiles are the four 2×2 quadrants, and
    // every label crosses at least one seam:
    //   1 1 1 2
    //   1 1 1 2
    //   3 1 1 2
    //   3 3 3 2
    private static final int W = 4, H = 4;
    private static final float[] LABELS = {
            1, 1, 1, 2,
            1, 1, 1, 2,
            3, 1, 1, 2,
            3, 3, 3, 2};
    private static final float[] CH0 = {
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25};
    private static final float[] CH1 = {
            5, 9, 2, 7, 3, 11, 4, 8, 6, 1, 13, 12, 14, 19, 17, 16};

    @Test
    void traceStreamedMatchesWholeImageTrace() throws Exception {
        SimpleImage band = SimpleImages.createFloatImage(LABELS, W, H);
        List<PathObject> whole = MaskConverter.mergeByLabel(
                MaskConverter.convertFromSimpleImage(band, null));

        ImageServer<BufferedImage> labelServer = oneChannelServer(LABELS);
        MaskConverter.StreamedTrace st =
                MaskConverter.traceStreamed(MaskConverter.channelLabelSource(labelServer, 0), 2);

        assertEquals(3, st.maxLabel(), "max label across tiles");

        Map<String, Double> wholeAreas = areaByLabel(whole);
        Map<String, Double> streamAreas = areaByLabel(st.detections());
        assertEquals(wholeAreas.keySet(), streamAreas.keySet(), "same set of label IDs");
        for (String label : wholeAreas.keySet()) {
            // Union of seam-split components must reconstruct the whole-trace area.
            assertEquals(wholeAreas.get(label), streamAreas.get(label), 1e-9,
                    "ROI area for label " + label);
        }
    }

    @Test
    void extractStreamedMatchesWholeImage() throws Exception {
        ImageServer<BufferedImage> host = twoChannelServer(CH0, CH1);
        SimpleImage band = SimpleImages.createFloatImage(LABELS, W, H);

        List<PathObject> whole = labels1to3();
        IntensityExtractor.extract(host, whole, band, 3, null);

        ImageServer<BufferedImage> labelServer = oneChannelServer(LABELS);
        for (int stripHeight : new int[]{1, 2, 3}) {
            List<PathObject> streamed = labels1to3();
            IntensityExtractor.extractStreamed(host, streamed,
                    MaskConverter.channelLabelSource(labelServer, 0), 3, stripHeight, null);
            for (int i = 0; i < whole.size(); i++) {
                for (String key : List.of("DAPI", "CD45")) {
                    double e = whole.get(i).getMeasurements().get(key).doubleValue();
                    double a = streamed.get(i).getMeasurements().get(key).doubleValue();
                    assertEquals(e, a, 0.0, "strip " + stripHeight + " label "
                            + whole.get(i).getName() + " " + key);
                }
            }
        }
    }

    private static Map<String, Double> areaByLabel(List<PathObject> detections) {
        Map<String, Double> areas = new LinkedHashMap<>();
        for (PathObject d : detections) {
            areas.put(d.getName(), d.getROI().getArea());
        }
        return areas;
    }

    private static List<PathObject> labels1to3() {
        return List.of(objNamed("1"), objNamed("2"), objNamed("3"));
    }

    private static PathObject objNamed(String name) {
        PathObject d = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, W, H, ImagePlane.getDefaultPlane()));
        d.setName(name);
        return d;
    }

    private static ImageServer<BufferedImage> oneChannelServer(float[] band) {
        return new WrappedBufferedImageServer("labels",
                floatImage(W, H, new float[][]{band}), ImageChannel.getChannelList("L"));
    }

    private static ImageServer<BufferedImage> twoChannelServer(float[] ch0, float[] ch1) {
        return new WrappedBufferedImageServer("host",
                floatImage(W, H, new float[][]{ch0, ch1}), ImageChannel.getChannelList("DAPI", "CD45"));
    }

    private static BufferedImage floatImage(int w, int h, float[][] bands) {
        int n = w * h;
        int bandCount = bands.length;
        DataBufferFloat buffer = new DataBufferFloat(bands, n);
        BandedSampleModel sampleModel = new BandedSampleModel(DataBuffer.TYPE_FLOAT, w, h, bandCount);
        WritableRaster raster = Raster.createWritableRaster(sampleModel, buffer, new Point(0, 0));
        int[] bits = new int[bandCount];
        java.util.Arrays.fill(bits, 32);
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
}
