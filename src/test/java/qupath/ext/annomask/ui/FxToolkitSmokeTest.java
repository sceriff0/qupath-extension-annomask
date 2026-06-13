package qupath.ext.annomask.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TitledPane;

import org.junit.jupiter.api.Test;

/**
 * Headless JavaFX smoke test for the AnnoMask UI stack.
 *
 * <p>{@code AnnoMaskPane} needs a live {@code QuPathGUI} — its constructor
 * {@code AnnoMaskPane(QuPathGUI qupath)} immediately calls {@code refreshChannels()},
 * which dereferences that GUI — so the pane itself cannot be constructed in a unit
 * test. Instead, this test verifies the headless JavaFX toolkit/CSS path that the
 * UI relies on by constructing and exercising the real JavaFX controls the pane
 * uses ({@link ComboBox}, {@link CheckBox}, {@link TitledPane}). Control
 * construction (especially {@code ComboBox}/{@code TitledPane}) touches the CSS
 * subsystem, which is the usual failure point under a headless toolkit.
 */
class FxToolkitSmokeTest {

    @Test
    void comboBoxConstructsAndReadsBackUnderHeadlessToolkit() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        FxTestSupport.onFxRun(() -> {
            ComboBox<String> combo = new ComboBox<>();
            combo.getItems().addAll("a", "b");
            combo.getSelectionModel().select("b");
            assertEquals("b", combo.getValue());
        });
    }

    @Test
    void checkBoxConstructsAndReadsBackUnderHeadlessToolkit() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        FxTestSupport.onFxRun(() -> {
            CheckBox checkBox = new CheckBox("Enabled");
            checkBox.setSelected(true);
            assertTrue(checkBox.isSelected());
        });
    }

    @Test
    void titledPaneConstructsUnderHeadlessToolkit() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        FxTestSupport.onFxRun(() -> {
            ComboBox<String> combo = new ComboBox<>();
            combo.getItems().addAll("a", "b");
            combo.getSelectionModel().select("b");

            CheckBox checkBox = new CheckBox("Enabled");
            checkBox.setSelected(true);

            TitledPane titledPane = new TitledPane("Channels", combo);
            titledPane.setExpanded(true);

            assertEquals("b", combo.getValue());
            assertTrue(checkBox.isSelected());
            assertEquals("Channels", titledPane.getText());
        });
    }
}
