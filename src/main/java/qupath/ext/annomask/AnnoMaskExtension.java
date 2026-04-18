package qupath.ext.annomask;

import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import qupath.ext.annomask.ui.AnnoMaskPane;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuPath extension entry point. Registers an "AnnoMask" menu item under the
 * Extensions menu that opens a floating window for converting TIFF segmentation
 * masks into QuPath detection objects.
 */
public class AnnoMaskExtension implements QuPathExtension {

    private static final String NAME = "FlowPath - AnnoMask";
    private static final String DESCRIPTION = "Convert TIFF segmentation masks to QuPath detections via GeoJSON.";

    private Stage stage;
    private AnnoMaskPane pane;

    @Override
    public void installExtension(QuPathGUI qupath) {
        var menuItem = new MenuItem(NAME);
        menuItem.setOnAction(e -> showWindow(qupath));
        menuItem.setAccelerator(new KeyCodeCombination(KeyCode.M, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        qupath.getMenu("Extensions", true).getItems().add(menuItem);
    }

    private void showWindow(QuPathGUI qupath) {
        if (stage != null && stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }
        pane = new AnnoMaskPane(qupath);
        stage = new Stage();
        stage.setTitle(NAME);
        stage.initOwner(qupath.getStage());
        stage.setScene(new Scene(pane, 460, 380));
        stage.setMinWidth(420);
        stage.setMinHeight(340);
        stage.show();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }
}
