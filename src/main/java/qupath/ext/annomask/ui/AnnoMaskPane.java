package qupath.ext.annomask.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
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
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
import qupath.ext.annomask.core.IntensityExtractor;
import qupath.ext.annomask.core.MaskConverter;
import qupath.ext.annomask.core.MaskValidator;
import qupath.ext.annomask.core.ObjectReader;
import qupath.ext.annomask.core.ObjectWriter;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final Label channelHint = new Label();
    private final Label fileLabel = new Label("(no file selected)");
    private final Button fileButton = new Button("Choose file…");
    private final SimpleObjectProperty<File> selectedFile = new SimpleObjectProperty<>();
    /** Discards stale async channel-preview results when the selection changes fast. */
    private final AtomicInteger previewToken = new AtomicInteger();

    private final CheckBox cbIntensity = new CheckBox("Extract channel intensities (needed for FlowPath gating)");
    private final CheckBox cbLoad = new CheckBox("Load into current image");
    private final CheckBox cbSave = new CheckBox("Also save GeoJSON to disk");

    private final Button runButton = new Button("Run Conversion");
    private final Button cancelButton = new Button("Cancel");
    private final Button importButton = new Button("Import GeoJSON…");
    private final Label statusLabel = new Label("Ready.");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final SimpleStringProperty statusProp = new SimpleStringProperty("Ready.");
    private final SimpleBooleanProperty running = new SimpleBooleanProperty(false);

    /** Remembers the last directory used in a file chooser, for convenience. */
    private File lastDirectory;
    /** The conversion/import task currently in flight, for cancellation. */
    private Task<?> activeTask;

    /**
     * Listener kept as a field so the {@link WeakChangeListener} wrapping it
     * stays alive exactly as long as this pane — when the pane is collected the
     * listener detaches itself from QuPath's image property automatically.
     */
    private final ChangeListener<ImageData<BufferedImage>> imageListener =
            (obs, oldData, newData) -> Platform.runLater(this::refreshChannels);

    public AnnoMaskPane(QuPathGUI qupath) {
        this.qupath = qupath;
        buildUi();
        wireEvents();
        refreshChannels();
        // Keep the channel list in sync when the user switches images while the
        // dialog stays open (the window is reused across invocations).
        qupath.imageDataProperty().addListener(new WeakChangeListener<>(imageListener));
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
        channelHint.setWrapText(true);
        channelHint.setStyle("-fx-font-size: 11px;");
        sourceGrid.add(channelHint, 0, 2, 2, 1);
        sourceGrid.add(rbFile, 0, 3, 2, 1);
        HBox fileRow = new HBox(6, fileButton, fileLabel);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        sourceGrid.add(fileRow, 0, 4, 2, 1);
        TitledPane sourcePane = new TitledPane("Mask source", sourceGrid);
        sourcePane.setCollapsible(false);

        cbIntensity.setSelected(true);
        cbIntensity.setTooltip(new Tooltip(
                "Sample mean intensity per channel for each detection, keyed by bare channel "
                + "name (DAPI, CD45). Required for FlowPath gating and qUMAP."));
        cbLoad.setSelected(true);
        cbLoad.setTooltip(new Tooltip("Add the resulting detections to the current image's object hierarchy."));
        cbSave.setSelected(false);
        cbSave.setTooltip(new Tooltip("Write the detections to a QuPath-native GeoJSON file as well."));
        VBox outputBox = new VBox(6, cbIntensity, cbLoad, cbSave);
        outputBox.setPadding(new Insets(6));
        TitledPane outputPane = new TitledPane("Output", outputBox);
        outputPane.setCollapsible(false);

        runButton.setPrefWidth(180);
        runButton.setDefaultButton(true);
        cancelButton.setTooltip(new Tooltip("Stop the current run (results are discarded)."));
        importButton.setPrefWidth(180);
        importButton.setTooltip(new Tooltip(
                "Read QuPath-native GeoJSON (e.g. mirage's cells.geojson) into the current image, "
                + "preserving nucleus geometry"));
        statusLabel.textProperty().bind(statusProp);
        statusLabel.setWrapText(true);

        // Progress + cancel only take up space while a run is in flight.
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.visibleProperty().bind(running);
        progressBar.managedProperty().bind(running);
        cancelButton.visibleProperty().bind(running);
        cancelButton.managedProperty().bind(running);

        VBox top = new VBox(10, sourcePane, outputPane);
        VBox.setVgrow(sourcePane, Priority.NEVER);

        HBox actionRow = new HBox(8, runButton, cancelButton, importButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        VBox bottom = new VBox(8, new Separator(), actionRow, progressBar, statusLabel);
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
            refreshRunState();
            previewChannel();
        });
        channelCombo.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            refreshRunState();
            previewChannel();
        });
        selectedFile.addListener((o, a, b) -> refreshRunState());
        running.addListener((o, a, b) -> refreshRunState());
        runButton.setOnAction(e -> runConversion());
        cancelButton.setOnAction(e -> {
            if (activeTask != null) {
                activeTask.cancel(true);
                statusProp.set("Cancelling…");
            }
        });
        importButton.disableProperty().bind(running);
        importButton.setOnAction(e -> importGeoJson());
        enableDragAndDrop();
        refreshRunState();
    }

    /**
     * Accepts a dropped file: a {@code .geojson}/{@code .json} is imported into
     * the hierarchy; a {@code .tif}/{@code .tiff} is loaded as the mask source
     * (switching to file mode). Saves a trip through the file chooser.
     */
    private void enableDragAndDrop() {
        setOnDragOver(e -> {
            if (!running.get() && e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean handled = false;
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                File f = db.getFiles().get(0);
                String name = f.getName().toLowerCase();
                if (name.endsWith(".geojson") || name.endsWith(".json")) {
                    handled = true;
                    importGeoJsonFile(f);
                } else if (name.endsWith(".tif") || name.endsWith(".tiff")) {
                    handled = true;
                    rbFile.setSelected(true);
                    selectedFile.set(f);
                    fileLabel.setText(f.getName());
                    fileLabel.setTooltip(new Tooltip(f.getAbsolutePath()));
                    rememberDirectory(f);
                    statusProp.set("Mask file set: " + f.getName());
                }
            }
            e.setDropCompleted(handled);
            e.consume();
        });
    }

    /**
     * Asynchronously checks whether the selected channel looks like an integer
     * label mask and shows the verdict inline, so the user can catch a wrong
     * channel before launching a long run. Runs off the FX thread; stale results
     * are discarded via {@link #previewToken}.
     */
    private void previewChannel() {
        if (!rbChannel.isSelected()) {
            channelHint.setText("");
            return;
        }
        ImageData<BufferedImage> data = qupath.getImageData();
        int idx = channelCombo.getSelectionModel().getSelectedIndex();
        if (data == null || idx < 0) {
            channelHint.setText("");
            return;
        }
        int token = previewToken.incrementAndGet();
        channelHint.setText("Checking channel…");
        Thread t = new Thread(() -> {
            String msg;
            try {
                MaskValidator.Report r = MaskValidator.quickCheck(data.getServer(), idx);
                if (r.looksLikeLabels()) {
                    msg = "✓ Looks like labels (~" + r.getDistinctNonZero() + " distinct in sample)";
                } else if (!r.isIntegerLike()) {
                    msg = "⚠ Values aren't integers — looks like an intensity channel, not labels";
                } else {
                    msg = "⚠ No labels found in the sampled region";
                }
            } catch (Exception ex) {
                msg = "";
            }
            final String result = msg;
            Platform.runLater(() -> {
                if (token == previewToken.get()) {
                    channelHint.setText(result);
                }
            });
        }, "AnnoMask-preview");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Enables Run only when the current selection can actually produce output,
     * and — while idle — shows what is missing instead of letting the user click
     * into an error dialog.
     */
    private void refreshRunState() {
        String blocked = runBlockedReason(
                qupath.getImageData() != null,
                rbChannel.isSelected(),
                channelCombo.getSelectionModel().getSelectedIndex() >= 0,
                selectedFile.get() != null);
        runButton.setDisable(running.get() || blocked != null);
        if (!running.get()) {
            statusProp.set(blocked != null ? blocked : "Ready.");
        }
    }

    /**
     * Pure check of whether a conversion can run. Returns a human-readable reason
     * it is blocked, or {@code null} when the configuration is runnable. Package
     * private so it can be unit-tested without a live {@code QuPathGUI}.
     */
    static String runBlockedReason(boolean hasImage, boolean channelMode,
                                   boolean hasChannelSelected, boolean hasFile) {
        if (!hasImage) {
            return "Open an image in QuPath first.";
        }
        if (channelMode && !hasChannelSelected) {
            return "Pick a channel to use as the label mask.";
        }
        if (!channelMode && !hasFile) {
            return "Choose a labeled TIFF mask file.";
        }
        return null;
    }

    private void chooseFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select a labeled TIFF mask");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("TIFF mask", "*.tif", "*.tiff"));
        applyInitialDirectory(fc);
        File f = fc.showOpenDialog(getScene().getWindow());
        if (f != null) {
            selectedFile.set(f);
            fileLabel.setText(f.getName());
            fileLabel.setTooltip(new Tooltip(f.getAbsolutePath()));
            rememberDirectory(f);
        }
    }

    private void refreshChannels() {
        String previous = channelCombo.getSelectionModel().getSelectedItem();
        channelCombo.getItems().clear();
        ImageData<BufferedImage> data = qupath.getImageData();
        if (data == null) {
            channelCombo.setPlaceholder(new Label("(no image open)"));
            refreshRunState();
            return;
        }
        var channels = data.getServer().getMetadata().getChannels();
        for (var ch : channels) {
            channelCombo.getItems().add(ch.getName());
        }
        if (!channelCombo.getItems().isEmpty()) {
            // Preserve the user's selection across an image swap when possible.
            if (previous != null && channelCombo.getItems().contains(previous)) {
                channelCombo.getSelectionModel().select(previous);
            } else {
                channelCombo.getSelectionModel().select(0);
            }
        }
        refreshRunState();
        previewChannel();
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
            applyInitialDirectory(fc);
            savePath = fc.showSaveDialog(getScene().getWindow());
            if (savePath == null) {
                return;
            }
            rememberDirectory(savePath);
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
                updateProgress(-1, 1);
                IntensityExtractor.ProgressListener intensityProgress = (done, total) -> {
                    updateProgress(done, total);
                    updateStatus("Extracting intensities… " + done + " / " + total);
                };
                List<PathObject> detections = runPipeline(
                        imageData, finalChannelIndex, finalFile, extractIntensity, intensityProgress);
                if (isCancelled()) {
                    // Stop before mutating the hierarchy or writing files.
                    return detections;
                }
                updateProgress(-1, 1);
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
        progressBar.progressProperty().bind(task.progressProperty());
        activeTask = task;
        task.setOnRunning(e -> running.set(true));
        task.setOnSucceeded(e -> {
            finishTask();
            List<PathObject> result = task.getValue();
            int n = result == null ? 0 : result.size();
            statusProp.set(task.isCancelled()
                    ? "Cancelled (" + n + " detection(s) discarded)."
                    : "Done. " + n + " detection(s).");
        });
        task.setOnCancelled(e -> {
            finishTask();
            statusProp.set("Cancelled.");
        });
        task.setOnFailed(e -> {
            finishTask();
            Throwable ex = task.getException();
            String msg = ex == null ? "(unknown)" : ex.getMessage();
            statusProp.set("Error: " + msg);
            Dialogs.showErrorMessage("AnnoMask", "Conversion failed: " + msg);
        });

        startTask(task, "AnnoMask-worker");
    }

    /**
     * Image size (in bytes, label band or full multi-channel read) above which
     * the streamed tile-by-tile path is used instead of decoding the whole image.
     */
    private static final long STREAM_BUDGET_BYTES = 512L * 1024 * 1024;

    /**
     * Traces and (optionally) quantifies, choosing the in-memory path for normal
     * images and the streamed tile-by-tile path for images too large to decode
     * whole. Both paths produce identical detections and measurements.
     */
    private List<PathObject> runPipeline(ImageData<BufferedImage> imageData, Integer channelIndex,
                                         File file, boolean extractIntensity,
                                         IntensityExtractor.ProgressListener intensityProgress) throws Exception {
        ImageServer<BufferedImage> server = imageData.getServer();
        if (channelIndex != null) {
            MaskValidator.Report report = MaskValidator.quickCheck(server, channelIndex);
            if (!report.looksLikeLabels()) {
                updateStatus("Warning: channel values don't look like integer labels. Continuing anyway.");
            }
        }

        if (shouldStream(server)) {
            updateStatus("Large image — streaming in tiles…");
            try (MaskConverter.LabelSource labels = channelIndex != null
                    ? MaskConverter.channelLabelSource(server, channelIndex)
                    : MaskConverter.fileLabelSource(file.toPath())) {
                MaskConverter.StreamedTrace st =
                        MaskConverter.traceStreamed(labels, MaskConverter.DEFAULT_TILE_SIZE);
                List<PathObject> detections = st.detections();
                updateStatus("Traced " + detections.size() + " object(s).");
                if (extractIntensity && !detections.isEmpty()) {
                    IntensityExtractor.extractStreamed(server, detections, labels,
                            st.maxLabel(), 0, intensityProgress);
                }
                return detections;
            }
        }

        MaskConverter.MaskResult result = channelIndex != null
                ? MaskConverter.convertChannel(server, channelIndex)
                : MaskConverter.convert(file.toPath(), server);
        List<PathObject> detections = result.detections();
        updateStatus("Traced " + detections.size() + " object(s).");
        if (extractIntensity && !detections.isEmpty()) {
            IntensityExtractor.extract(server, detections, result.labelBand(), result.maxLabel(),
                    result.sourceImage(), intensityProgress);
        }
        return detections;
    }

    /** True when decoding the whole image (or even one band) would be too large. */
    private static boolean shouldStream(ImageServer<BufferedImage> server) {
        long pixels = (long) server.getWidth() * server.getHeight();
        long bandBytes = pixels * 4; // a single float label band
        long bytesPerSample = Math.max(1, server.getPixelType().getBytesPerPixel());
        long imageBytes = pixels * server.nChannels() * bytesPerSample;
        return bandBytes > STREAM_BUDGET_BYTES || imageBytes > STREAM_BUDGET_BYTES;
    }

    private void importGeoJson() {
        if (qupath.getImageData() == null) {
            Dialogs.showErrorMessage("AnnoMask", "No image is currently open in QuPath.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Import GeoJSON");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("GeoJSON", "*.geojson", "*.json"));
        applyInitialDirectory(fc);
        File source = fc.showOpenDialog(getScene().getWindow());
        if (source == null) {
            return;
        }
        importGeoJsonFile(source);
    }

    /** Reads {@code source} as GeoJSON and loads it into the current hierarchy. */
    private void importGeoJsonFile(File source) {
        ImageData<BufferedImage> data = qupath.getImageData();
        if (data == null) {
            Dialogs.showErrorMessage("AnnoMask", "No image is currently open in QuPath.");
            return;
        }
        if (running.get()) {
            return;
        }
        rememberDirectory(source);

        final File finalSource = source;
        final ImageData<BufferedImage> imageData = data;
        Task<List<PathObject>> task = new Task<>() {
            @Override
            protected List<PathObject> call() throws Exception {
                updateStatus("Reading GeoJSON…");
                updateProgress(-1, 1);
                List<PathObject> objects = ObjectReader.readGeoJson(finalSource);
                if (isCancelled()) {
                    return objects;
                }
                if (!objects.isEmpty()) {
                    updateStatus("Loading " + objects.size() + " object(s) into hierarchy…");
                    ObjectWriter.addToHierarchy(imageData, objects);
                }
                return objects;
            }
        };
        activeTask = task;
        task.setOnRunning(e -> running.set(true));
        task.setOnSucceeded(e -> {
            finishTask();
            List<PathObject> result = task.getValue();
            statusProp.set("Imported " + (result == null ? 0 : result.size()) + " object(s).");
        });
        task.setOnCancelled(e -> {
            finishTask();
            statusProp.set("Cancelled.");
        });
        task.setOnFailed(e -> {
            finishTask();
            Throwable ex = task.getException();
            String msg = ex == null ? "(unknown)" : ex.getMessage();
            statusProp.set("Error: " + msg);
            Dialogs.showErrorMessage("AnnoMask", "Import failed: " + msg);
        });

        startTask(task, "AnnoMask-import");
    }

    private void startTask(Task<?> task, String threadName) {
        Thread t = new Thread(task, threadName);
        t.setDaemon(true);
        t.start();
    }

    private void finishTask() {
        running.set(false);
        progressBar.progressProperty().unbind();
        progressBar.setProgress(0);
        activeTask = null;
    }

    private void applyInitialDirectory(FileChooser fc) {
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            fc.setInitialDirectory(lastDirectory);
        }
    }

    private void rememberDirectory(File file) {
        if (file != null && file.getParentFile() != null) {
            lastDirectory = file.getParentFile();
        }
    }

    private void updateStatus(String msg) {
        Platform.runLater(() -> statusProp.set(msg));
    }
}
