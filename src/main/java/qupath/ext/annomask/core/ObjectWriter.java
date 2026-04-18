package qupath.ext.annomask.core;

import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collection;

/**
 * Loads detections into the current image hierarchy and/or writes them out
 * as QuPath-native GeoJSON via {@link PathIO#exportObjectsAsGeoJSON}.
 */
public final class ObjectWriter {

    private ObjectWriter() {}

    public static void addToHierarchy(ImageData<BufferedImage> imageData, Collection<? extends PathObject> detections) {
        if (imageData == null || detections == null || detections.isEmpty()) {
            return;
        }
        var hierarchy = imageData.getHierarchy();
        hierarchy.addObjects(detections);
        hierarchy.fireHierarchyChangedEvent(ObjectWriter.class);
    }

    public static void writeGeoJson(Collection<? extends PathObject> detections, File target) throws IOException {
        if (target == null) {
            return;
        }
        PathIO.exportObjectsAsGeoJSON(target, detections,
                PathIO.GeoJsonExportOptions.FEATURE_COLLECTION,
                PathIO.GeoJsonExportOptions.PRETTY_JSON);
    }
}
