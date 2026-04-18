# FlowPath - AnnoMask

A QuPath 0.7+ extension that converts labeled TIFF segmentation masks into
QuPath detection objects (and GeoJSON) without leaving the app.

Useful after an external segmentation run (Cellpose, StarDist, mirage,
custom pipelines) when all you have on disk is a labeled mask and you want
detections that FlowPath-GatingTree can gate on.

## What it does

Given a labeled mask — a single-band integer raster where `0` is background
and each positive value is a unique cell/region ID — AnnoMask traces the
contour of every label and produces one QuPath `PathDetectionObject` per
label, using QuPath's built-in `ContourTracing`.

It can also:
- Sample mean intensity per channel per detection (so downstream tools like
  FlowPath-GatingTree see populated measurements and can gate immediately)
- Add the detections straight into the current image's hierarchy
- Save the result as QuPath-native GeoJSON (`FeatureCollection`)

## Two input modes

1. **Channel in current image** — pick a channel whose pixel values are
   integer labels. Useful when segmentation output is merged into the
   OME-TIFF as an extra channel.
2. **Load from file** — pick a labeled `.tif` / `.tiff` on disk. Assumed to
   align to the current image's pixel grid.

## Install

Drop the release JAR into QuPath's extensions directory, or use the
[FlowPath catalog](https://github.com/sceriff0/flowpath-catalog) via
QuPath's extension manager and install "FlowPath - AnnoMask".

Open an image, then `Extensions → FlowPath - AnnoMask`
(shortcut: `Ctrl+Shift+M` / `⌘⇧M`).

## Build

Requires JDK 25 and QuPath 0.7.0 artefacts. This project uses the standard
`qupath-extension-settings` Gradle plugin, same as the other FlowPath
extensions.

```bash
./gradlew build
```

JAR lands in `build/libs/`.

## How it relates to the rest of the FlowPath toolkit

- **FlowPath - GatingTree**: interactive phenotype gating. AnnoMask
  produces the detections that GatingTree operates on.
- **FlowPath - qUMAP**: UMAP over detection measurements. AnnoMask +
  intensity extraction populates measurements for qUMAP to embed.
- **mirage** (external Python pipeline): produces the labeled masks that
  AnnoMask consumes. AnnoMask is the "import step" when you want mirage's
  masks inside QuPath without running mirage's `export_geojson.py`.

## License

MIT.
