package qupath.ext.annomask.core;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectReaderTest {

    @Test
    void roundTripPreservesNucleus() throws Exception {
        // Build a cell object: whole-cell ROI + nucleus ROI nested inside it.
        var plane = ImagePlane.getDefaultPlane();
        var cellRoi = ROIs.createRectangleROI(0, 0, 20, 20, plane);
        var nucRoi = ROIs.createRectangleROI(5, 5, 10, 10, plane);
        PathObject cell = PathObjects.createCellObject(cellRoi, nucRoi);
        cell.setName("1");
        try (var ml = cell.getMeasurementList()) {
            ml.put("CD45: Nucleus: Mean", 42.0);
        }

        File out = File.createTempFile("annomask-roundtrip-", ".geojson");
        out.deleteOnExit();
        ObjectWriter.writeGeoJson(List.of(cell), out);

        // Sanity: the writer must have emitted a nucleusGeometry for the reader
        // to reconstruct.
        String content = java.nio.file.Files.readString(out.toPath());
        assertTrue(content.contains("nucleusGeometry"),
                "exported GeoJSON must carry a nucleusGeometry member");

        List<PathObject> read = ObjectReader.readGeoJson(out);
        assertEquals(1, read.size(), "exactly one object should round-trip");

        PathObject obj = read.get(0);
        assertInstanceOf(PathCellObject.class, obj, "object should come back as a cell");
        PathCellObject readCell = (PathCellObject) obj;
        assertTrue(readCell.hasNucleus(), "nucleus must survive the round trip");
        assertNotNull(readCell.getNucleusROI(), "nucleus ROI must be reconstructed");
        assertEquals(100.0, readCell.getNucleusROI().getArea(), 1e-6,
                "nucleus ROI area (10x10) must be preserved");
        assertEquals(400.0, readCell.getROI().getArea(), 1e-6,
                "whole-cell ROI area (20x20) must be preserved");
        assertEquals(42.0, readCell.getMeasurements().get("CD45: Nucleus: Mean").doubleValue(), 1e-9,
                "per-compartment measurement must survive the round trip");
    }

    @Test
    void readsPlainDetections() throws Exception {
        PathObject det = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 10, 10, ImagePlane.getDefaultPlane()));
        det.setName("7");
        File out = File.createTempFile("annomask-det-", ".geojson");
        out.deleteOnExit();
        ObjectWriter.writeGeoJson(List.of(det), out);

        List<PathObject> read = ObjectReader.readGeoJson(out);
        assertEquals(1, read.size());
        assertTrue(read.get(0).isDetection(), "plain detection should stay a detection");
        assertEquals("7", read.get(0).getName());
    }
}
