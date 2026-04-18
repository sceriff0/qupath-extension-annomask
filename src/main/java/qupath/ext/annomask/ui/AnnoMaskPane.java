package qupath.ext.annomask.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import qupath.ext.annomask.core.IntensityExtractor;
import qupath.ext.annomask.core.MaskConverter;
import qupath.ext.annomask.core.MaskValidator;
import qupath.ext.annomask.core.ObjectWriter;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * Main AnnoMask UI. Lets the user pick a mask source (channel in the current
 * image, or a TIFF on disk), optionally extract channel intensities, and
 * optionally load the resulting detections into the current image and/or save
 * them as GeoJSON.
 */
public class AnnoMaskPane extends BorderPane {

    private final QuPathGUI qupath;

    private final ToggleGroup sourceGroup = new ToggleGroup();
    private final RadioButton rbChannel = new RadioButton("Channel in current image");
    private final RadioButton rbFile = new RadioButton("Load from file");
    private final ComboBox<String> channelCombo = new ComboBox<>();
    private final Label fileLabel = new Label("(no file selected)");
    private final Button fileButton = new Button("Choose file…");
    private final SimpleObjectProperty<File> selectedFile = new SimpleObjectProperty<>();

    private final CheckBox cbIntensity = new CheckBox("Extract channel intensities (needed for FlowPath gating)");
    private final CheckBox cbLoad = new CheckBox("Load into current image");
    private final CheckBox cbSave = new CheckBox("Also save GeoJSON to disk");

    private final Button runButton = new Button("Run Conversion");
    private final Label statusLabel = new Label("Ready.");
    private final SimpleStringProperty statusProp = new SimpleStringProperty("Ready.");
    private final SimpleBooleanProperty running = new SimpleBooleanProperty(false);

    public AnnoMaskPane(QuPathGUI qupath) {
        this.qupath = qupath;
        buildUi();
        wireEvents();
        refreshChannels();
    }

    private void buildUi() {
        setPadding(new Insets(12));

        rbChannel.setToggleGroup(sourceGroup);
        rbFile.setToggleGroup(sourceGroup);
        rbChannel.setSelected(true);
        channelCombo.setPrefWidth(240);
        fileButton.setTooltip(new Tooltip("Choose a labeled TIFF mask on disk"));

        GridPane sourceGrid = new GridPane();
        sourceGrid.setHgap(8);
        sourceGrid.setVgap(6);
        sourceGrid.setPadding(new Insets(6, 6, 6, 6));
        sourceGrid.add(rbChannel, 0, 0, 2, 1);
        sourceGrid.add(new Label("Channel:"), 0, 1);
        sourceGrid.add(channelCombo, 1, 1);
        sourceGrid.add(rbFile, 0, 2, 2, 1);
        HBox fileRow = new HBox(6, fileButton, fileLabel);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        sourceGrid.add(fileRow, 0, 3, 2, 1);
        TitledPane sourcePane = new TitledPane("Mask source", sourceGrid);
        sourcePane.setCollapsible(false);

        cbIntensity.setSelected(true);
        cbLoad.setSelected(true);
        cbSave.setSelected(false);
        VBox outputBox = new VBox(6, cbIntensity, cbLoad, cbSave);
        outputBox.setPadding(new Insets(6));
        TitledPane outputPane = new TitledPane("Output", outputBox);
        outputPane.setCollapsible(false);

        runButton.setPrefWidth(180);
        runButton.setDefaultButton(true);
        statusLabel.textProperty().bind(statusProp);
        statusLabel.setWrapText(true);

        VBox top = new VBox(10, sourcePane, outputPane);
        VBox.setVgrow(sourcePane, Priority.NEVER);

        VBox bottom = new VBox(8, new Separator(), runButton, statusLabel);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(6, 0, 0, 0));

        setTop(top);
        setBottom(bottom);
    }

    private void wireEvents() {
        fileButton.setOnAction(e -> chooseFile());
        sourceGroup.selectedToggleProperty().addListener((obs, oldV, newV) -> {
            boolean channelMode = newV == rbChannel;
            channelCombo.setDisable(!channelMode);
            fileButton.setDisable(channelMode);
        });
        runButton.disableProperty().bind(running);
        runButton.setOnAction(e -> runConversion());
    }

    private void chooseFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select a labeled TIFF mask");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("TIFF mask", "*.tif", "*.tiff"));
        File f = fc.showOpenDialog(getScene().getWindow());
        if (f != null) {
            selectedFile.set(f);
            fileLabel.setText(f.getName());
        }
    }

    private void refreshChannels() {
        channelCombo.getItems().clear();
        ImageData<BufferedImage> data = qupath.getImageData();
        if (data == null) {
            channelCombo.setPlaceholder(new Label("(no image open)"));
            return;
        }
        var channels = data.getServer().getMetadata().getChannels();
        for (var ch : channels) {
            channelCombo.getItems().add(ch.getName());
        }
        if (!channelCombo.getItems().isEmpty()) {
            channelCombo.getSelectionModel().select(0);
        }
    }

    private void runConversion() {
        ImageData<BufferedImage> data = qupath.getImageData();
        if (data == null) {
            Dialogs.showErrorMessage("AnnoMask", "No image is currently open in QuPath.");
            return;
        }
        boolean channelMode = rbChannel.isSelected();
        Integer channelIndex = null;
        File file = null;
        if (channelMode) {
            int idx = channelCombo.getSelectionModel().getSelectedIndex();
            if (idx < 0) {
                Dialogs.showErrorMessage("AnnoMask", "Pick a channel first.");
                return;
            }
            channelIndex = idx;
        } else {
            file = selectedFile.get();
            if (file == null) {
                Dialogs.showErrorMessage("AnnoMask", "Choose a mask file first.");
                return;
            }
        }
        File savePath = null;
        if (cbSave.isSelected()) {
            FileChooser fc = new FileChooser();
            fc.setTitle("Save GeoJSON");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("GeoJSON", "*.geojson", "*.json"));
            fc.setInitialFileName("annomask.geojson");
            savePath = fc.showSaveDialog(getScene().getWindow());
            if (savePath == null) {
                return;
            }
        }

        final Integer finalChannelIndex = channelIndex;
        final File finalFile = file;
        final File finalSavePath = savePath;
        final boolean extractIntensity = cbIntensity.isSelected();
        final boolean loadIntoImage = cbLoad.isSelected();
        final ImageData<BufferedImage> imageData = data;

        Task<List<PathObject>> task = new Task<>() {
            @Override
            protected List<PathObject> call() throws Exception {
                updateStatus("Tracing contours…");
                List<PathObject> detections;
                if (finalChannelIndex != null) {
                    MaskValidator.Report report = MaskValidator.quickCheck(imageData.getServer(), finalChannelIndex);
                    if (!report.looksLikeLabels()) {
                        updateStatus("Warning: channel values don't look like integer labels. Continuing anyway.");
                    }
                    detections = MaskConverter.convertChannel(imageData.getServer(), finalChannelIndex);
                } else {
                    detections = MaskConverter.convert(finalFile.toPath(), imageData.getServer());
                }
                updateStatus("Traced " + detections.size() + " object(s).");
                if (extractIntensity && !detections.isEmpty()) {
                    IntensityExtractor.extract(imageData.getServer(), detections,
                            (done, total) -> updateStatus("Extracting intensities… " + done + " / " + total));
                }
                if (loadIntoImage && !detections.isEmpty()) {
                    updateStatus("Loading " + detections.size() + " detections into hierarchy…");
                    ObjectWriter.addToHierarchy(imageData, detections);
                }
                if (finalSavePath != null) {
                    updateStatus("Writing GeoJSON…");
                    ObjectWriter.writeGeoJson(detections, finalSavePath);
                }
                return detections;
            }
        };
        task.setOnRunning(e -> running.set(true));
        task.setOnSucceeded(e -> {
            running.set(false);
            List<PathObject> result = task.getValue();
            statusProp.set("Done. " + (result == null ? 0 : result.size()) + " detection(s).");
        });
        task.setOnFailed(e -> {
            running.set(false);
            Throwable ex = task.getException();
            String msg = ex == null ? "(unknown)" : ex.getMessage();
            statusProp.set("Error: " + msg);
            Dialogs.showErrorMessage("AnnoMask", "Conversion failed: " + msg);
        });

        Thread t = new Thread(task, "AnnoMask-worker");
        t.setDaemon(true);
        t.start();
    }

    private void updateStatus(String msg) {
        Platform.runLater(() -> statusProp.set(msg));
    }
}
