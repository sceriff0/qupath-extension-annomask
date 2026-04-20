# FlowPath - AnnoMask

A QuPath 0.7+ extension that converts labeled TIFF segmentation masks into
QuPath detection objects (and GeoJSON) without leaving the app. The import
step for mask-based pipelines (Cellpose, StarDist, mirage, etc.) into the
FlowPath toolkit.

## Stack
- Language: Java (JDK 25)
- Framework: QuPath extension (shadow JAR via `com.gradleup.shadow` + `qupath-conventions`)
- Build tool: Gradle (Kotlin DSL, `build.gradle.kts` + `settings.gradle.kts`)
- Test framework: JUnit 5 (`useJUnitPlatform()`)
- UI: JavaFX (via `qupath.fxtras`) — JavaFX 25.0.2 test deps
- Logging: SLF4J (via `libs.bundles.logging`)

## Build & Test
- Full build: `./gradlew build` (JAR lands in `build/libs/`)
- Run tests only: `./gradlew test`
- Compile (typecheck): `./gradlew compileJava`

## Directory Structure
- `src/main/java/qupath/ext/annomask/` — extension entry point (`AnnoMaskExtension`)
  - `core/` — mask → detections pipeline (`MaskConverter`, `MaskValidator`, `IntensityExtractor`, `ObjectWriter`)
  - `ui/` — JavaFX dialog (`AnnoMaskPane`)
- `src/main/resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension` — SPI registration
- `src/test/java/` — JUnit 5 tests mirroring `core/` layout

## Conventions

- **Measurement keys are bare channel names** (e.g. `DAPI`, `CD45`) — never QuPath's
  default `"<Channel>: Cell: Mean"` format. GatingTree's
  `CellIndex.findMarkerValue` and qUMAP's equivalent look up measurements by bare
  channel name; the QuPath suffix silently breaks them. Do not re-introduce
  `ObjectMeasurements.addIntensityMeasurements`.
- **Intensity arithmetic is a single bincount pass over the raw label band**
  (`LabelIntensity.meanPerLabel`). This matches mirage's `bin/quantify.py` exactly.
  Do not measure from the polygon ROI via rasterisation — it drifts on boundary
  pixels and on disconnected labels.
- **One detection per unique integer label**. Disconnected components of the same
  label are merged via `MaskConverter.mergeByLabel` → `RoiTools.union`.
  `PathObject.name` stores the label ID as a string and is the join key between
  the traced detections and the `means[]` array.

## Architecture
- Entry point: `AnnoMaskExtension` is registered via Java SPI (see `META-INF/services`)
- Two input modes (per README): channel-in-current-image, or load-from-file
- Output: `PathDetectionObject` per unique label ID, with bare-channel-name mean
  intensity measurements when extraction is enabled
- Contour tracing: `ContourTracing.createObjects` / `labelsToObjects` produces
  per-component detections, then `MaskConverter.mergeByLabel` collapses components
  sharing a label ID into one detection
- Quantification: `MaskConverter` returns a `MaskResult(detections, labelBand, maxLabel)`
  so `IntensityExtractor` reuses the already-read raster instead of
  polygon-rasterising each detection

## FlowPath Ecosystem
AnnoMask is part of a toolkit — detection format is a cross-repo contract:

- **GatingTree** (`qupath-extension-flowpath`) — interactive phenotype gating.
  Reads `PathObject.getMeasurements()` and looks up measurement values by bare
  channel name (`CellIndex.findMarkerValue`).
- **qUMAP** (`qupath-extension-qumap`) — UMAP over detection measurements.
  Same bare-channel-name lookup (`qumap` / `CellIndex.findMarkerValue`).
- **mirage** (external Python pipeline, `~/Downloads/mirage`) — produces the
  labeled masks AnnoMask consumes, and emits bare-channel-name measurements in
  its own `bin/export_geojson.py`. AnnoMask's measurements are bit-identical to
  mirage's up to FP ordering, so the two paths are interchangeable.
- **flowpath-catalog** — QuPath extension manager catalog entry.

If you change measurement naming, label-merging, or the `PathObject.name`
convention, update GatingTree and qUMAP's `findMarkerValue` in lockstep.

## Citadel Harness

This project uses the [Citadel](https://github.com/SethGammon/Citadel) agent
orchestration harness. Configuration is in `.claude/harness.json`. Hook
definitions are resolved into `.claude/settings.json` by
`node /Users/valer/Citadel/scripts/install-hooks.js` (re-run after Citadel updates).

Useful commands:
- `/do [anything]` — route any task to the right skill/orchestrator
- `/review` — 5-pass structured code review
- `/refactor` — safe multi-file refactoring with rollback
- `/do --list` — show all registered skills
