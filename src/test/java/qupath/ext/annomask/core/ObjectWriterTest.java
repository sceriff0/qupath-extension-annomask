package qupath.ext.annomask.core;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectWriterTest {

    @Test
    void writeGeoJsonProducesFeatureCollection() throws Exception {
        PathObject d1 = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 10, 10, ImagePlane.getDefaultPlane()));
        d1.setName("1");
        PathObject d2 = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(20, 20, 10, 10, ImagePlane.getDefaultPlane()));
        d2.setName("2");

        File out = File.createTempFile("annomask-test-", ".geojson");
        out.deleteOnExit();
        ObjectWriter.writeGeoJson(List.of(d1, d2), out);

        String content = Files.readString(out.toPath());
        assertTrue(content.contains("FeatureCollection"), "output should be a FeatureCollection");
        assertTrue(content.contains("\"name\": \"1\"") || content.contains("\"name\":\"1\""),
                "label name should be preserved");
        assertTrue(content.contains("\"objectType\": \"detection\"") || content.contains("\"objectType\":\"detection\""),
                "output should mark objects as detections");
    }
}
