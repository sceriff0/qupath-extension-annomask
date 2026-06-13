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

/**
 * Mirrors mirage M1's compartment test: a synthetic 2-cell cell-mask, a
 * nucleus-mask, and a fake intensity channel. Asserts cytoplasm = cell −
 * nucleus and that the three exact keys are emitted alongside the bare key.
 */
class CompartmentIntensityTest {

    // 3x3 layout, cell labels:
    //   1 1 0
    //   1 2 2
    //   0 2 2
    // nucleus mask (any positive = nucleus pixel):
    //   1 0 0   -> cell 1 has 1 nucleus pixel (top-left), 2 cytoplasm pixels
    //   0 9 0   -> cell 2 has 1 nucleus pixel (center), 3 cytoplasm pixels
    //   0 0 0
    private static final float[] CELL = {1, 1, 0, 1, 2, 2, 0, 2, 2};
    private static final float[] NUC  = {1, 0, 0, 0, 9, 0, 0, 0, 0};
    // Intensity channel.
    private static final float[] DAPI = {10, 20, 0, 30, 40, 50, 0, 60, 70};

    @Test
    void cytoplasmEqualsCellMinusNucleusAndKeysAreExact() throws Exception {
        BufferedImage img = oneChannelFloatImage(3, 3, DAPI);
        ImageServer<BufferedImage> server = new WrappedBufferedImageServer(
                "test", img, ImageChannel.getChannelList("DAPI"));

        SimpleImage cellBand = SimpleImages.createFloatImage(CELL, 3, 3);
        SimpleImage nucBand = SimpleImages.createFloatImage(NUC, 3, 3);

        PathObject cell1 = objWithName("1");
        PathObject cell2 = objWithName("2");

        IntensityExtractor.extract(server, List.of(cell1, cell2), cellBand, nucBand, 2, null);

        // Cell 1 pixels: 10,20,30. Cell mean = 20. Nucleus pixel = 10 -> 10.
        // Cytoplasm pixels = 20,30 -> 25.
        assertEquals(20.0, cell1.getMeasurements().get("DAPI").doubleValue(), 1e-9, "bare whole-cell mean");
        assertEquals(20.0, cell1.getMeasurements().get("DAPI: Cell: Mean").doubleValue(), 1e-9);
        assertEquals(10.0, cell1.getMeasurements().get("DAPI: Nucleus: Mean").doubleValue(), 1e-9);
        assertEquals(25.0, cell1.getMeasurements().get("DAPI: Cytoplasm: Mean").doubleValue(), 1e-9);

        // Cell 2 pixels: 40,50,60,70. Cell mean = 55. Nucleus pixel = 40 -> 40.
        // Cytoplasm pixels = 50,60,70 -> 60.
        assertEquals(55.0, cell2.getMeasurements().get("DAPI").doubleValue(), 1e-9);
        assertEquals(55.0, cell2.getMeasurements().get("DAPI: Cell: Mean").doubleValue(), 1e-9);
        assertEquals(40.0, cell2.getMeasurements().get("DAPI: Nucleus: Mean").doubleValue(), 1e-9);
        assertEquals(60.0, cell2.getMeasurements().get("DAPI: Cytoplasm: Mean").doubleValue(), 1e-9);

        // Exact key set: bare + three compartments.
        Set<String> keys = cell1.getMeasurements().keySet();
        assertTrue(keys.contains("DAPI"), keys.toString());
        assertTrue(keys.contains("DAPI: Cell: Mean"), keys.toString());
        assertTrue(keys.contains("DAPI: Nucleus: Mean"), keys.toString());
        assertTrue(keys.contains("DAPI: Cytoplasm: Mean"), keys.toString());
        assertEquals(4, keys.size(), "exactly bare + 3 compartment keys per channel");
    }

    @Test
    void cytoplasmCountConsistency() {
        // Cell = nucleus pixels + cytoplasm pixels, so the weighted means must
        // reconstruct the whole-cell mean: cell*n_cell = nuc*n_nuc + cyto*n_cyto.
        SimpleImage cellBand = SimpleImages.createFloatImage(CELL, 3, 3);
        SimpleImage nucBand = SimpleImages.createFloatImage(NUC, 3, 3);
        SimpleImage chan = SimpleImages.createFloatImage(DAPI, 3, 3);

        LabelIntensity.CompartmentMeans cm =
                LabelIntensity.meanPerCompartment(cellBand, nucBand, chan, 2);

        // cell 1: 1 nuc px + 2 cyto px = 3 cell px; 10*1 + 25*2 = 60 = 20*3.
        assertEquals(20.0 * 3, cm.nucleus()[1] * 1 + cm.cytoplasm()[1] * 2, 1e-9);
        // cell 2: 1 nuc px + 3 cyto px = 4 cell px; 40*1 + 60*3 = 220 = 55*4.
        assertEquals(55.0 * 4, cm.nucleus()[2] * 1 + cm.cytoplasm()[2] * 3, 1e-9);
    }

    @Test
    void nullNucleusFallsBackToBareKeyOnly() throws Exception {
        BufferedImage img = oneChannelFloatImage(3, 3, DAPI);
        ImageServer<BufferedImage> server = new WrappedBufferedImageServer(
                "test", img, ImageChannel.getChannelList("DAPI"));
        SimpleImage cellBand = SimpleImages.createFloatImage(CELL, 3, 3);

        PathObject cell1 = objWithName("1");
        IntensityExtractor.extract(server, List.of(cell1), cellBand, null, 2, null);

        Set<String> keys = cell1.getMeasurements().keySet();
        assertEquals(Set.of("DAPI"), keys, "single-mask path stays whole-cell-only");
        assertEquals(20.0, cell1.getMeasurements().get("DAPI").doubleValue(), 1e-9);
    }

    private static PathObject objWithName(String name) {
        PathObject d = PathObjects.createDetectionObject(
                ROIs.createRectangleROI(0, 0, 3, 3, ImagePlane.getDefaultPlane()));
        d.setName(name);
        return d;
    }

    private static BufferedImage oneChannelFloatImage(int w, int h, float[] ch0) {
        int n = w * h;
        float[][] banks = {ch0};
        DataBufferFloat buffer = new DataBufferFloat(banks, n);
        BandedSampleModel sampleModel = new BandedSampleModel(DataBuffer.TYPE_FLOAT, w, h, 1);
        WritableRaster raster = Raster.createWritableRaster(sampleModel, buffer, new Point(0, 0));
        ColorModel cm = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_GRAY),
                new int[]{32}, false, false,
                Transparency.OPAQUE, DataBuffer.TYPE_FLOAT);
        return new BufferedImage(cm, raster, false, null);
    }
}
