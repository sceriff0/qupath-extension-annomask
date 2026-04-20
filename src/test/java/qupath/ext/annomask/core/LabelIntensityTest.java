package qupath.ext.annomask.core;

import org.junit.jupiter.api.Test;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.images.SimpleImages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LabelIntensityTest {

    @Test
    void meanPerLabelMatchesMirageDocstring() {
        // From mirage/bin/quantify.py:76-82.
        //   mask    = [[1,1,0],[1,0,2],[0,2,2]]
        //   channel = [[10,20,0],[30,0,40],[0,50,60]]
        //   expect  label 1 -> 20.0, label 2 -> 50.0
        SimpleImage mask = SimpleImages.createFloatImage(
                new float[]{1, 1, 0, 1, 0, 2, 0, 2, 2}, 3, 3);
        SimpleImage channel = SimpleImages.createFloatImage(
                new float[]{10, 20, 0, 30, 0, 40, 0, 50, 60}, 3, 3);

        double[] means = LabelIntensity.meanPerLabel(mask, channel, 2);

        assertEquals(3, means.length, "means array sized maxLabel + 1");
        assertEquals(20.0, means[1], 1e-12, "label 1 mean = (10+20+30)/3");
        assertEquals(50.0, means[2], 1e-12, "label 2 mean = (40+50+60)/3");
    }

    @Test
    void meanPerLabelGapsReturnZero() {
        // Labels 1, 2, 7 present; slots 3..6 must read as 0.0.
        SimpleImage mask = SimpleImages.createFloatImage(
                new float[]{1, 2, 0, 7}, 4, 1);
        SimpleImage channel = SimpleImages.createFloatImage(
                new float[]{100, 200, 999, 700}, 4, 1);

        double[] means = LabelIntensity.meanPerLabel(mask, channel, 7);

        assertEquals(8, means.length);
        assertEquals(100.0, means[1], 1e-12);
        assertEquals(200.0, means[2], 1e-12);
        assertEquals(0.0, means[3]);
        assertEquals(0.0, means[6]);
        assertEquals(700.0, means[7], 1e-12);
    }

    @Test
    void meanPerLabelAllZeroBackground() {
        // Pure background: every slot is 0.0, caller never reads [0].
        SimpleImage mask = SimpleImages.createFloatImage(new float[9], 3, 3);
        SimpleImage channel = SimpleImages.createFloatImage(
                new float[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, 3, 3);

        double[] means = LabelIntensity.meanPerLabel(mask, channel, 3);

        assertEquals(4, means.length);
        for (int i = 1; i < means.length; i++) {
            assertEquals(0.0, means[i], "gaps should be zero");
        }
    }

    @Test
    void maxLabelScansMaximumPositiveValue() {
        SimpleImage mask = SimpleImages.createFloatImage(
                new float[]{0, 3, 0, 17, 2, 5}, 6, 1);
        assertEquals(17, LabelIntensity.maxLabel(mask));
    }

    @Test
    void maxLabelIgnoresNegativeAndNan() {
        SimpleImage mask = SimpleImages.createFloatImage(
                new float[]{-1, Float.NaN, 4, 2}, 4, 1);
        assertEquals(4, LabelIntensity.maxLabel(mask));
    }

    @Test
    void meanPerLabelRejectsSizeMismatch() {
        SimpleImage mask = SimpleImages.createFloatImage(new float[]{1, 2}, 2, 1);
        SimpleImage channel = SimpleImages.createFloatImage(new float[]{1, 2, 3, 4}, 2, 2);
        assertThrows(IllegalArgumentException.class,
                () -> LabelIntensity.meanPerLabel(mask, channel, 2));
    }
}
