package purelandgen.analysis;

import purelandgen.geom.Figure;
import purelandgen.geom.FoldingMachine;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Measures shape counts under the OLDER, non-exact duplicate-elimination key (bucketing by
 * (sd, face count, edge count) alone, with no further layer-order check) -- the methodology
 * an earlier version of this enumeration used, before ExactStateKey existed. Useful for
 * comparing against today's default (FoldingMachine.REQUIRE_EXACT_KEY=true, ExactKeyEnumeration)
 * to see how much the layer-order-aware key changes the per-fold counts.
 *
 * REQUIRE_EXACT_KEY is explicitly forced false here (overriding the field's true default) and
 * reset to false again in the finally block regardless.
 *
 * Mirrors ExactKeyEnumeration.java's structure; see that class for the DISPLAY_CAP/streaming
 * notes.
 *
 * Usage: ./gradlew currentKeyEnumeration4Fold (the registered Gradle task hardcodes depth=4;
 * the class itself also accepts plain "depth=N"/"out=path" arguments when run directly).
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
