package purelandgen.analysis;

import purelandgen.geom.Figure;
import purelandgen.geom.FoldingMachine;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Re-measures Table 2's (tab:counts) shape counts under the CURRENT (non-exact) duplicate-
 * elimination key -- i.e. the same key/settings Table 2's published numbers (1,579 / 241,303 /
 * 98,804,718 at 3/4/5 folds) were originally computed with, before the exact-key work existed.
 * This re-runs that same methodology (Axioms 1-3, valley+mountain+crease enabled at every
 * round, crease-only operations excluded at the 5th fold only, only isFoldable states carried
 * forward between rounds) with today's codebase, to check whether this session's two
 * FoldingMachine visibility-check fixes (see rebuttal/A2_exact_key_spec.md -- GeomUtil.isRange
 * EPS tolerance, and Axiom 1's crease-vs-visible-edge-overlap check) move Table 2's numbers at
 * all, independently of the separate exact-key question.
 *
 * REQUIRE_EXACT_KEY is explicitly forced false here (the field's current default is true,
 * which is convenient for the exact-key rebuttal scripts but is NOT what Table 2 was computed
 * with) and reset to false again in the finally block regardless.
 *
 * Mirrors ExactKeyEnumeration.java's structure; see that class for the DISPLAY_CAP/streaming
 * notes. depth=4 is already a ~300x larger population than the 3-fold quiz pool (435,467
 * states under the exact key at that depth; the current key is expected to be in the same
 * ballpark), so a single run is expected to take on the order of the paper's reported 16
 * minutes (crease excluded at fold 4) -- consider redirecting output and running detached.
 *
 * Usage:
 *   ./gradlew currentKeyEnumeration4Fold
 *   ./gradlew currentKeyEnumeration4Fold -Pdepth=3    (cheap sanity check, matches 1,579 total)
 */
public class CurrentKeyEnumeration {

    private static final boolean[] CONSTRUCTIONS   = {true, true, true};
    private static final boolean[] TECHS_ALL       = {true, true, true};
    private static final boolean[] TECHS_FOLD_ONLY = {true, true, false}; // crease excluded at fold 5 only

    public static void main(String[] args) throws Exception {
        int depth = 4;
        String outPath = "rebuttal/current_key_enumeration_results.txt";
        for (String a : args) {
            if (a.startsWith("depth=")) depth = Integer.parseInt(a.substring("depth=".length()));
            else if (a.startsWith("out=")) outPath = a.substring("out=".length());
        }

        PrintWriter out = new PrintWriter(new FileWriter(outPath, true));
        _log(out, "========================================================");
        _log(out, "Current-key enumeration to depth " + depth + ", started " + new java.util.Date());
        _log(out, "Crease-only (fold-and-unfold) operations excluded at fold 5 only,"
                + " per Table 2's caption -- included at every earlier round.");

        int savedThreshold = FoldingMachine.STREAM_THRESHOLD;
        int savedCap = FoldingMachine.DISPLAY_CAP;
        FoldingMachine.REQUIRE_EXACT_KEY = false;

        ArrayList<Figure> current = new ArrayList<>();
        current.add(new Figure(200, 200));

        long grandStart = System.currentTimeMillis();
        try {
            for (int d = 1; d <= depth; d++) {
                boolean[] techs = (d == 5) ? TECHS_FOLD_ONLY.clone() : TECHS_ALL.clone();
                // Last round: no further round to carry into -- skip the reload, get counts via
                // SQL COUNT only (see FoldingMachine.FoldOnce). Earlier rounds: never truncate.
                FoldingMachine.DISPLAY_CAP = (d == depth) ? 0 : Integer.MAX_VALUE;

                FoldingMachine fm = new FoldingMachine(CONSTRUCTIONS.clone(), techs);
                long t0 = System.currentTimeMillis();
                ArrayList<Figure> folded = fm.FoldOnce(current);
                long elapsedMs = System.currentTimeMillis() - t0;

                long total = fm.lastTotalCount;
                long foldable = fm.lastFoldableCount;
                long unfoldable = total - foldable;
                double pctElim = total > 0 ? (100.0 * unfoldable / total) : 0.0;
                _log(out, String.format(
                        "depth %d: total=%d foldable=%d unfoldable=%d eliminated=%.1f%% time=%s",
                        d, total, foldable, unfoldable, pctElim, _fmt(elapsedMs)));

                if (d < depth) {
                    ArrayList<Figure> next = new ArrayList<>();
                    for (Figure f : folded) if (f.isFoldable) next.add(f);
                    current = next;
                    if (next.size() != foldable) {
                        _log(out, "  WARNING: carried-forward set (" + next.size()
                                + ") != reported foldable count (" + foldable + ") -- "
                                + "DISPLAY_CAP may not have been high enough after all.");
                    }
                }
            }
        } finally {
            FoldingMachine.REQUIRE_EXACT_KEY = false;
            FoldingMachine.STREAM_THRESHOLD = savedThreshold;
            FoldingMachine.DISPLAY_CAP = savedCap;
        }
        long grandElapsed = System.currentTimeMillis() - grandStart;
        _log(out, "total elapsed: " + _fmt(grandElapsed));
        out.close();
    }

    private static void _log(PrintWriter out, String line) {
        System.out.println(line);
        out.println(line);
        out.flush();
    }

    private static String _fmt(long ms) {
        long s = ms / 1000;
        return String.format("%dh%02dm%02ds (%dms)", s / 3600, (s % 3600) / 60, s % 60, ms);
    }
}
