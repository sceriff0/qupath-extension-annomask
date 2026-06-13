package qupath.ext.annomask.core;

import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

/**
 * Reads QuPath objects back from a GeoJSON file via QuPath's native
 * {@link PathIO#readObjectsFromGeoJSON(InputStream)}. The native reader
 * reconstructs {@link qupath.lib.objects.PathCellObject}s — including the
 * nucleus ROI — from a top-level {@code nucleusGeometry} member, so mirage's
 * {@code cells.geojson} round-trips without dropping the nucleus.
 *
 * <p>This is the import counterpart to {@link ObjectWriter#writeGeoJson};
 * together they round-trip cell objects (whole-cell ROI + nucleus ROI +
 * per-compartment measurements) losslessly.</p>
 */
public final class ObjectReader {

    private ObjectReader() {}

    /**
     * Reads all objects from the GeoJSON {@code source}. Cell features carrying
     * a {@code nucleusGeometry} come back as cell objects with the nucleus ROI
     * intact; plain detections come back as detections.
     *
     * @param source a GeoJSON file (FeatureCollection or a single Feature)
     * @return the reconstructed objects, never null
     */
    public static List<PathObject> readGeoJson(File source) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("source file must not be null");
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source.toPath()))) {
            return PathIO.readObjectsFromGeoJSON(in);
        }
    }
}
