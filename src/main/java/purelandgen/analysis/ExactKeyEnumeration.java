package purelandgen.analysis;

import purelandgen.geom.Figure;
import purelandgen.geom.FoldingMachine;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Measures 4- and 5-fold enumeration under the exact key (Table 1 methodology: Axioms 1-3,
 * valley+mountain+crease enabled at every round, only crease-only (fold-and-unfold) operations
 * excluded at the 5th fold specifically -- per the paper's Table 1 caption, NOT "excluded at
 * the target depth" like the 3-fold quiz-pool rule LayerOrderMergeAnalysis uses for Table 3).
 * Only isFoldable states are carried forward between rounds, matching MainFrame.enumerate().
 *
 * Reports aggregate counts only (Total/Foldable/Unfoldable per depth, matching Table 1's
 * columns) and per-round timing -- no per-figure DB export, so this stays cheap regardless of
 * how many states a round produces. See rebuttal/layer_order_merge_results.txt for the 2-3
 * fold cross-check this extends, and for the known DISPLAY_CAP/streaming-path notes below.
 *
 * Usage:
 *   ./gradlew exactKeyEnumeration                  (depth 4)
 *   ./gradlew exactKeyEnumeration -Pdepth=5         (depth 5)
 *   ./gradlew exactKeyEnumeration -Pdepth=5 -Pout=rebuttal/exact_key_5fold.txt
 *
 * Notes on scale (see rebuttal/layer_order_merge_results.txt "Timing" section):
 *   - ExactStateKey.compute() measured ~18.7x slower per candidate than the current key at
 *     3 folds. That cost is paid per candidate regardless of depth, so 4/5-fold runtime is
 *     expected to grow by roughly this factor over the paper's reported 16 min / 13.6 h,
 *     before accounting for the exact key also keeping more states alive (it kept +42% more
 *     at 3 folds), which independently increases the candidate count at the next depth.
 *   - DISPLAY_CAP is raised to Integer.MAX_VALUE for every round except the last, so the
 *     carried-forward set is never silently truncated (the default 200,000 cap would
 *     otherwise truncate a >200,000-state round, e.g. today's 241,303-state 4-fold round,
 *     before it reaches the next depth). For the LAST round only, DISPLAY_CAP is set to 0:
 *     since there is no further round to carry into, the figures themselves are not needed,
 *     only the counts -- FoldingMachine skips the (potentially huge) reload from SQLite in
 *     that case and reports counts via a direct SQL COUNT instead.
 */
public class ExactKeyEnumeration {

    private static final boolean[] CONSTRUCTIONS   = {true, true, true};
    private static final boolean[] TECHS_ALL       = {true, true, true};
    private static final boolean[] TECHS_FOLD_ONLY = {true, true, false}; // crease excluded at fold 5 only

    public static void main(String[] args) throws Exception {
        int depth = 4;
        String outPath = "rebuttal/exact_key_enumeration_results.txt";
        for (String a : args) {
            if (a.startsWith("depth=")) depth = Integer.parseInt(a.substring("depth=".length()));
            else if (a.startsWith("out=")) outPath = a.substring("out=".length());
        }

        PrintWriter out = new PrintWriter(new FileWriter(outPath, true));
        _log(out, "========================================================");
        _log(out, "Exact-key enumeration to depth " + depth + ", started " + new java.util.Date());
        _log(out, "Crease-only (fold-and-unfold) operations excluded at fold 5 only,"
                + " per Table 1's caption -- included at every earlier round.");

        int savedThreshold = FoldingMachine.STREAM_THRESHOLD;
        int savedCap = FoldingMachine.DISPLAY_CAP;
        FoldingMachine.REQUIRE_EXACT_KEY = true;

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
