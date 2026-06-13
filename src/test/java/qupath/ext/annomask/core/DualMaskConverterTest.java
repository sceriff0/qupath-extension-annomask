package qupath.ext.annomask.core;

import org.junit.jupiter.api.Test;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.images.SimpleImages;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DualMaskConverterTest {

    @Test
    void buildsCellObjectsPairingNucleusByOverlap() {
        // 12x12 cell mask: two 4x4 cells.
        int w = 12, h = 12;
        float[] cell = new float[w * h];
        fillRect(cell, w, 1, 1, 4, 4, 1);   // cell 1 at x∈[1,4], y∈[1,4]
        fillRect(cell, w, 6, 6, 4, 4, 2);   // cell 2 at x∈[6,9], y∈[6,9]

        // Nucleus mask: a 2x2 nucleus sitting inside each cell.
        float[] nuc = new float[w * h];
        fillRect(nuc, w, 2, 2, 2, 2, 1);    // nucleus inside cell 1
        fillRect(nuc, w, 7, 7, 2, 2, 2);    // nucleus inside cell 2

        SimpleImage cellBand = SimpleImages.createFloatImage(cell, w, h);
        SimpleImage nucBand = SimpleImages.createFloatImage(nuc, w, h);

        MaskConverter.MaskResult result = MaskConverter.convertCells(cellBand, nucBand, null);
        List<PathObject> cells = result.detections();

        assertEquals(2, cells.size(), "one cell object per cell label");
        Map<String, PathCellObject> byName = new HashMap<>();
        for (PathObject o : cells) {
            assertInstanceOf(PathCellObject.class, o, "objects should be cells");
            assertTrue(o.isCell());
            byName.put(o.getName(), (PathCellObject) o);
        }

        PathCellObject c1 = byName.get("1");
        PathCellObject c2 = byName.get("2");
        assertNotNull(c1);
        assertNotNull(c2);

        assertTrue(c1.hasNucleus(), "cell 1 must have its paired nucleus");
        assertTrue(c2.hasNucleus(), "cell 2 must have its paired nucleus");
        assertEquals(16.0, c1.getROI().getArea(), 1e-6, "cell 1 whole-cell area 4x4");
        assertEquals(4.0, c1.getNucleusROI().getArea(), 1e-6, "cell 1 nucleus area 2x2");
        assertEquals(4.0, c2.getNucleusROI().getArea(), 1e-6, "cell 2 nucleus area 2x2");

        // Nucleus centroids must land inside the matching cell (correct pairing).
        assertTrue(c1.getROI().getGeometry().contains(c1.getNucleusROI().getGeometry().getCentroid()),
                "cell 1 nucleus must sit within cell 1");
        assertTrue(c2.getROI().getGeometry().contains(c2.getNucleusROI().getGeometry().getCentroid()),
                "cell 2 nucleus must sit within cell 2");
    }

    @Test
    void cellWithoutNucleusBecomesCellWithoutNucleus() {
        int w = 12, h = 12;
        float[] cell = new float[w * h];
        fillRect(cell, w, 1, 1, 4, 4, 1);
        fillRect(cell, w, 6, 6, 4, 4, 2);

        float[] nuc = new float[w * h];
        fillRect(nuc, w, 2, 2, 2, 2, 1);    // nucleus only in cell 1

        SimpleImage cellBand = SimpleImages.createFloatImage(cell, w, h);
        SimpleImage nucBand = SimpleImages.createFloatImage(nuc, w, h);

        var cells = MaskConverter.convertCells(cellBand, nucBand, null).detections();
        assertEquals(2, cells.size());
        for (PathObject o : cells) {
            PathCellObject c = (PathCellObject) o;
            if (c.getName().equals("1")) {
                assertTrue(c.hasNucleus());
            } else {
                assertFalse(c.hasNucleus(), "cell 2 has no overlapping nucleus");
            }
        }
    }

    @Test
    void nucleusAssignedToMostOverlappingCell() {
        // A nucleus straddling two cells must go to the one it overlaps more.
        int w = 12, h = 12;
        float[] cell = new float[w * h];
        fillRect(cell, w, 0, 0, 6, 12, 1);   // left half = cell 1
        fillRect(cell, w, 6, 0, 6, 12, 2);   // right half = cell 2

        // Nucleus spans x∈[4,8] — 2 cols in cell 1 (x=4,5), 2 cols in cell 2
        // (x=6,7)... make it lopsided: x∈[3,8] => cols 3,4,5 (cell1=3) vs 6,7 (cell2=2).
        float[] nuc = new float[w * h];
        fillRect(nuc, w, 3, 4, 5, 4, 1);     // x∈[3,7], cell1 gets x=3,4,5; cell2 gets x=6,7

        SimpleImage cellBand = SimpleImages.createFloatImage(cell, w, h);
        SimpleImage nucBand = SimpleImages.createFloatImage(nuc, w, h);

        var cells = MaskConverter.convertCells(cellBand, nucBand, null).detections();
        Map<String, PathCellObject> byName = new HashMap<>();
        for (PathObject o : cells) byName.put(o.getName(), (PathCellObject) o);

        assertTrue(byName.get("1").hasNucleus(), "nucleus goes to cell 1 (greater overlap)");
        assertFalse(byName.get("2").hasNucleus(), "cell 2 has lesser overlap, gets none");
    }

    private static void fillRect(float[] data, int w, int x0, int y0, int rw, int rh, int label) {
        for (int yy = y0; yy < y0 + rh; yy++) {
            for (int xx = x0; xx < x0 + rw; xx++) {
                data[yy * w + xx] = label;
            }
        }
    }
}
