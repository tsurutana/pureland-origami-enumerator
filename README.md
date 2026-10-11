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

### Reproducing the paper's reported numbers

The shape counts per fold depth, the 3-fold "quiz pool" silhouette statistics, and the
easy/medium/hard difficulty classification reported in the paper are produced by the
`purelandgen.analysis` tasks below. They default to writing under `rebuttal/`, which is
gitignored (that folder also held the paper draft and reviewer correspondence while the
submission was in progress, neither of which belongs in this repo) — create it once
before running them:

```bash
mkdir -p rebuttal
```

- `./gradlew pathAnalysis` — the fold-path graph and the easy/medium/hard difficulty
  classification (`-Pout=<file>` for the CSV path, default `path_graph.csv`).
- `./gradlew layerOrderMergeAnalysis` — the 3-fold "quiz pool" silhouette statistics
  (distinct silhouettes, states with a unique silhouette, foldable realizations), computed
  both under the original duplicate-elimination key and the exact, layer-order-aware key
  (`purelandgen.geom.ExactStateKey`), plus an in-memory-vs-streaming cross-check.
- `./gradlew fullCongruenceCheck` — brute-force, no-shortcuts verification that the
  silhouette counts above are not an artifact of the scalar shape descriptor used to
  group states: every pair among the 1,429 three-fold states is checked for true rigid
  (translation + rotation + reflection) polygon congruence
  (`purelandgen.geom.ExactSilhouetteGrouping`).
- `./gradlew silhouetteCongruenceCheck` — narrower companion check (same congruence
  method, scoped to within each existing scalar-descriptor group) used to validate the
  method's residual tolerance.
- `./gradlew exactKeyEnumeration4Fold` / `./gradlew exactKeyEnumeration5Fold` — 4- and
  5-fold total/foldable/unfoldable counts under the exact key, appended to
  `rebuttal/exact_key_enumeration_results.txt`. The 5-fold run is expected to take on the
  order of a day; consider running it detached.
- `./gradlew currentKeyEnumeration4Fold` — the same 4-fold count under the original
  (non-exact) key, for direct comparison against the previous task.
- `./gradlew exactKeyShapeDbExport` — exports the actual 1/2/3-fold shapes (under the
  exact key) as `.db` files for visual inspection in the GUI
  (`./gradlew run --args="path/to/file.db"`).
- `./gradlew toleranceSweep` — re-runs 3-/4-fold enumeration at several duplicate-
  elimination tolerances, to show the counts are stable across a wide range.
- `./gradlew compareSilhouetteDbs -Pa=<A.db> -Pb=<B.db>` — general-purpose check of
  whether every silhouette in database B also appears in database A (exact polygon
  congruence), for comparing two independently generated/exported shape sets.

Running time ranges from seconds to (for the 5-fold run) over a day; each task prints
progress and a final summary to stdout as it goes. `./gradlew test` also includes
`ExactStateKeyTest`, which validates the layer-order-aware key against a hand-constructed
case (three ways of folding a square into a 1/8 rectangle that a naive key would conflate).

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
