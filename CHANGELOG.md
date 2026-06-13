# Changelog

## 0.3.3

### Features

- **Dual-segmentation import + per-compartment intensity (M5).** A labeled cell
  mask paired with a labeled nucleus mask produces one `PathCellObject` per cell
  (whole-cell + nucleus ROI), with nuclei assigned to cells by maximum overlap.
  Per-compartment intensities are emitted under mirage-compatible keys
  (`<m>`, `<m>: Cell: Mean`, `<m>: Nucleus: Mean`, `<m>: Cytoplasm: Mean`), where
  cytoplasm = cell − nucleus, keyed by cell label.

### Performance

- **Streamed (tiled) tracing + quantification for very large masks.** When the
  full image (or even a single label band) would exceed a memory budget, the
  mask is traced tile-by-tile and merged per label (`MaskConverter.traceStreamed`
  via a `LabelSource`), and intensities are accumulated reading the label from
  source strip-by-strip (`IntensityExtractor.extractStreamed`) — so the full
  label band is never held in memory. Results are identical to the in-memory
  path (verified by equivalence tests using labels that straddle tile/strip
  seams). Normal-sized images keep the existing in-memory path unchanged.
- **Guard against runaway allocation.** Selecting a raw intensity channel by
  mistake used to risk `OutOfMemoryError` from `new double[maxLabel+1]`; the
  label range is now bounded with an actionable error
  (`MaskConverter.checkLabelRange`).
- `LabelIntensity.maxLabel` iterates the flat pixel array instead of
  `getValue(x,y)` per pixel.

### Performance (intensity extraction)

- **Intensity extraction is now parallelised across channels and reads the
  label band once.** `LabelIntensity` gained flat-`float[]` hot paths
  (`meanPerLabel`/`meanPerCompartment`) that iterate the raw pixel arrays from
  `SimpleImages.getPixels` in row-major order instead of calling
  `SimpleImage.getValue(x,y)` per pixel. `IntensityExtractor` flattens the mask
  band(s) once and runs each channel's bincount on a parallel stream. Results
  are numerically identical to before (existing exact-value tests unchanged);
  this is the main fix for slow multi-channel quantification.
- **Channel mode no longer decodes the image twice.** The host image is already
  read to extract the label channel; `MaskConverter.MaskResult` now carries that
  `BufferedImage` so `IntensityExtractor` reuses it instead of reading the whole
  image again.
- **Large images are quantified in horizontal strips instead of all at once.**
  When the full multi-channel image would exceed a memory budget, channels are
  read in full-width strips top-to-bottom and the bincount is accumulated across
  them (`LabelIntensity.accumulate` / `accumulateCompartments`). Strip order is
  row-major, so results are bit-identical to the single-pass path — verified by
  tests that force 1/2/3-row strips and compare against the whole-image result,
  including labels that straddle strip boundaries. This removes the
  out-of-memory ceiling on the channel read for whole-slide-scale inputs.

  Note: contour tracing still materialises the full label band, so the very
  largest gigapixel masks remain bounded by that step (a separate, larger piece
  of work); strip extraction removes the dominant, channel-count-multiplied
  allocation.

### UI

- Channel list now auto-refreshes when the active image changes while the
  dialog is open (previously it was populated once and went stale).
- Run is disabled until the selection can actually produce output, with an
  inline reason ("Open an image first", "Pick a channel", "Choose a file")
  instead of an error dialog after clicking.
- Added a progress bar (determinate during intensity extraction, by channel)
  and a Cancel button that aborts before the hierarchy is mutated or files
  written.
- Tooltips on the output options; file choosers remember the last directory;
  the chosen mask file shows its full path on hover.
- **Channel-looks-like-labels preview**: selecting a channel runs an async
  sanity check and shows the verdict inline, so a wrong channel is caught before
  a long run.
- **Drag-and-drop**: drop a `.tif`/`.tiff` to set it as the mask source, or a
  `.geojson`/`.json` to import it directly.

### Internal

- Extracted `AnnoMaskPane.runBlockedReason` as a pure, unit-tested helper.
- `MaskValidator` Javadoc corrected to match its actual ≥1-distinct-label
  threshold and to note the centre-tile sampling caveat.
- New tests: streamed trace/quantify equivalence (seam-straddling labels),
  strip-path bit-identity, multi-channel parallel correctness, label-range
  guard.

## 0.2.1

### Fixed

- `MaskConverterTest.disconnectedLabelMergesToSingleDetection` had a bogus
  precondition (`raw.size() == 2`) based on the wrong assumption that QuPath's
  `ContourTracing` returns one detection per connected component. It actually
  groups pixels by label ID into a single multi-polygon detection, which is
  the behaviour AnnoMask wants. Test renamed to
  `disconnectedLabelProducesSingleDetection` with the correct end-state
  assertions. `MaskConverter` Javadoc updated.
- `MaskConverter.mergeByLabel` retained as a safety net for any future
  codepath that builds detections outside `ContourTracing`; no-op on the
  current paths.

### Note

0.2.0 was tagged but its Actions build failed on the above test, so the
release page was created without a JAR attached. 0.2.1 is the first
shipping build of the 0.2.x line.

## 0.2.0

### Breaking

- **Measurement names are now bare channel names.** Previously AnnoMask wrote intensity
  measurements under QuPath's default naming (`"<Channel>: Cell: Mean"`), which neither
  FlowPath-GatingTree nor FlowPath-qUMAP recognise (they look up measurements by bare
  channel name, matching mirage's `export_geojson.py` convention). Re-run AnnoMask on
  existing detections to regenerate the measurement list under the new keys; old GeoJSON
  files written by 0.1.0 keep their old keys and will need to be re-imported to work
  with GatingTree/qUMAP.

### Changed

- Intensity quantification now matches mirage's `bin/quantify.py` arithmetic: a single
  bincount-style pass over the raw label band (`LabelIntensity.meanPerLabel`). Results
  are bit-for-bit identical (up to floating-point ordering) to mirage's per-channel CSV
  output, eliminating the boundary-pixel drift that came from rasterising the traced
  polygon back into a mask.
- Labels with multiple connected components now produce **one** detection per label ID
  (merged via `RoiTools.union` after contour tracing), matching mirage's cell count.
  Previously AnnoMask produced one detection per component.
- `MaskConverter.convert` / `convertChannel` return a `MaskResult` record
  (`detections`, `labelBand`, `maxLabel`) so `IntensityExtractor` can reuse the already-
  traced raster.
- `IntensityExtractor.extract` signature now takes the label band and max label; it no
  longer depends on `ObjectMeasurements.addIntensityMeasurements` or QuPath's
  `Measurements`/`Compartments` enums.

## 0.1.0

Initial release.
