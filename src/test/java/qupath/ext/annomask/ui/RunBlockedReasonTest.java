package qupath.ext.annomask.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link AnnoMaskPane#runBlockedReason}. This is the pure
 * decision behind enabling/disabling the Run button, extracted so it can be
 * verified without constructing the JavaFX pane (which needs a live QuPathGUI).
 */
class RunBlockedReasonTest {

    @Test
    void noImageBlocksRegardlessOfMode() {
        assertNotNull(AnnoMaskPane.runBlockedReason(false, true, true, true));
        assertNotNull(AnnoMaskPane.runBlockedReason(false, false, true, true));
    }

    @Test
    void channelModeNeedsAChannel() {
        assertNotNull(AnnoMaskPane.runBlockedReason(true, true, false, false),
                "channel mode with no channel selected must block");
        assertNull(AnnoMaskPane.runBlockedReason(true, true, true, false),
                "channel mode with a channel selected is runnable");
    }

    @Test
    void fileModeNeedsAFile() {
        assertNotNull(AnnoMaskPane.runBlockedReason(true, false, false, false),
                "file mode with no file must block");
        assertNull(AnnoMaskPane.runBlockedReason(true, false, false, true),
                "file mode with a file chosen is runnable");
    }

    @Test
    void blockingReasonsAreHumanReadable() {
        assertEquals("Open an image in QuPath first.",
                AnnoMaskPane.runBlockedReason(false, true, true, true));
        assertEquals("Pick a channel to use as the label mask.",
                AnnoMaskPane.runBlockedReason(true, true, false, false));
        assertEquals("Choose a labeled TIFF mask file.",
                AnnoMaskPane.runBlockedReason(true, false, false, false));
    }
}
