# Pureland Origami Enumerator

A research tool for enumerating reference foldable origami shapes reachable
from a square sheet of paper using Pureland-style folding operations
(one-fold axioms). 

Given a starting figure, the enumerator generates all possible next folding
lines (Axiom 1: point-to-point line, Axiom 2: point-to-point match,
Axiom 3: edge-to-edge match), applies each fold, and filters out judgment
folds — i.e. folds that would require passing the paper through a landmark
hidden by other layers, leaving only the reference-foldable results.

## Requirements

- JDK 8 or later
- Gradle (a wrapper is included, so a separate Gradle install is not required)

## Build & run

```bash
./gradlew build
./gradlew run
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

The GUI lets you step through generated figures, choose which construction
axioms/techniques to enumerate, and export results (SVG, PDF, or a SQLite
database of figures).

To resume from a previously saved database:

```bash
./gradlew run --args="path/to/file.db"
```

### Running tests

```bash
./gradlew test
```

### Other Gradle tasks

- `./gradlew pathAnalysis` — runs `purelandgen.analysis.PathGraphAnalyzer`,
  which analyzes the fold-path graph of a generated database and writes a
  CSV report (`-Pout=<file>` to choose the output path).

## Project structure

```
src/main/java/purelandgen/
├── PurelandOrigamiEnumerator.java  Entry point
├── OptionsConfig.java              Persists enumeration options (options.ini)
├── ColorConfig.java                Paper display/export colors
├── geom/                           Geometry classes and the folding engine
├── gui/                            Swing GUI
├── file/                           Import/export (SQLite, SVG, PDF, JSON)
├── matching/                       Shape-matching (Hu moments)
└── analysis/                       Post-hoc analysis of generated fold graphs
```

## Example output

Sample PDF exports (one shape per page, generated via "Save All as PDF...")
are in [`examples/`](examples/):

- [`1fold_all_4.pdf`](examples/1fold_all_4.pdf) — all shapes after 1 fold
- [`2fold_all_37.pdf`](examples/2fold_all_37.pdf) — all shapes after 2 folds
- [`3fold_all_1579.pdf`](examples/3fold_all_1579.pdf) — all shapes after 3 folds
- [`3fold_foldable_1478.pdf`](examples/3fold_foldable_1478.pdf) — reference
  foldable shapes after 3 folds

## Configuration

`options.ini` (last-used enumeration settings) and `color.ini` (paper colors)
live in the working directory. `options.ini` is machine-specific and is not
tracked in git — copy `options.ini.sample` to `options.ini` to get started.

## License

MIT — see [LICENSE](LICENSE).
