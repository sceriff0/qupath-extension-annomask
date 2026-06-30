# FlowPath - AnnoMask

[![QuPath](https://img.shields.io/badge/QuPath-%E2%89%A50.7.0-blue.svg)](https://qupath.github.io/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://jdk.java.net/25/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-flowpath.readthedocs.io-success.svg)](https://flowpath.readthedocs.io/)

A QuPath 0.7+ extension that converts **labeled TIFF segmentation masks into
QuPath detections** (and GeoJSON) without leaving the app — with optional
per-channel intensity sampling. It's the import step for mask-based pipelines
([MIRAGE](https://mirage-pipeline.readthedocs.io/), Cellpose, StarDist, custom) into the
FlowPath toolkit.

Part of the [FlowPath suite](https://flowpath.readthedocs.io/), alongside
[GatingTree](https://github.com/sceriff0/qupath-extension-flowpath-gatingtree) and
[qUMAP](https://github.com/sceriff0/qupath-extension-flowpath-qumap).

## Install

In QuPath, add the FlowPath catalog and install **AnnoMask**:

```
https://raw.githubusercontent.com/sceriff0/flowpath-catalog/main/catalog.json
```

(Extensions → Manage extensions → Manage extension catalogs → Add.) Launch with
**Extensions → FlowPath - AnnoMask** (`Ctrl+Shift+M` / `⌘⇧M`). Full install
options are in the [docs](https://flowpath.readthedocs.io/installation/).

## Build from source

Requires JDK 25 and QuPath 0.7.0 artefacts.

```bash
git clone https://github.com/sceriff0/qupath-extension-annomask.git
cd qupath-extension-annomask
./gradlew build   # JAR lands in build/libs/ → drag onto QuPath
```

## 📖 Documentation

Input modes, intensity sampling, how it fits the FlowPath workflow, and
troubleshooting are all at **<https://flowpath.readthedocs.io/>**.

## License

MIT.
