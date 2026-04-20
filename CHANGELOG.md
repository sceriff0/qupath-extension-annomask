# Changelog

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
