package qupath.ext.annomask.core;

import org.junit.jupiter.api.Test;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.images.SimpleImages;
import qupath.lib.objects.PathObject;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskConverterTest {

    @Test
    void twoLabelsProduceTwoDetectionsWithCorrectNames() {
        // 12x12 image with two labeled 4x4 regions.
        int w = 12;
        int h = 12;
        float[] data = new float[w * h];
        fillRect(data, w, 1, 1, 4, 4, 1);   // label 1 at x∈[1,4], y∈[1,4]
        fillRect(data, w, 6, 6, 4, 4, 2);   // label 2 at x∈[6,9], y∈[6,9]

        SimpleImage band = SimpleImages.createFloatImage(data, w, h);
        List<PathObject> raw = MaskConverter.convertFromSimpleImage(band, null);
        List<PathObject> detections = MaskConverter.mergeByLabel(raw);

        assertEquals(2, detections.size(), "expected one detection per label");
        var names = detections.stream().map(PathObject::getName).collect(Collectors.toSet());
        assertTrue(names.contains("1"), "label 1 should be named \"1\", got " + names);
        assertTrue(names.contains("2"), "label 2 should be named \"2\", got " + names);
        for (PathObject d : detections) {
            assertNotNull(d.getROI(), "every detection must have a ROI");
            assertTrue(d.isDetection(), "every object should be a detection");
        }
    }

    @Test
    void emptyMaskProducesNoDetections() {
        SimpleImage band = SimpleImages.createFloatImage(new float[16], 4, 4);
        List<PathObject> detections = MaskConverter.mergeByLabel(
                MaskConverter.convertFromSimpleImage(band, null));
        assertEquals(0, detections.size());
    }

    @Test
    void disconnectedLabelProducesSingleDetection() {
        // 12x12 band with two 3x3 squares both labelled 5 (no touching).
        int w = 12;
        int h = 12;
        float[] data = new float[w * h];
        fillRect(data, w, 1, 1, 3, 3, 5);
        fillRect(data, w, 7, 7, 3, 3, 5);

        SimpleImage band = SimpleImages.createFloatImage(data, w, h);
        List<PathObject> detections = MaskConverter.mergeByLabel(
                MaskConverter.convertFromSimpleImage(band, null));

        // ContourTracing groups pixels by label ID even when the label is
        // spatially disconnected, producing one multi-polygon detection.
        // mergeByLabel is a safety net for any future codepath that doesn't
        // go through ContourTracing — on this path it is a no-op.
        assertEquals(1, detections.size(), "one detection per label ID");
        PathObject det = detections.get(0);
        assertEquals("5", det.getName());
        assertEquals(18.0, det.getROI().getArea(), 1e-6, "combined area = 2 * 3 * 3");
    }

    private static void fillRect(float[] data, int w, int x0, int y0, int rw, int rh, int label) {
        for (int yy = y0; yy < y0 + rh; yy++) {
            for (int xx = x0; xx < x0 + rw; xx++) {
                data[yy * w + xx] = label;
            }
        }
    }
}
