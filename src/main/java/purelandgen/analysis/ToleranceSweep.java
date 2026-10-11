package purelandgen.analysis;

import purelandgen.geom.Figure;
import purelandgen.geom.FoldingMachine;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Supports the paper's Sec 4.4 claim: "recomputing the enumeration with the descriptor
 * tolerance varied from 10^-6 to 10^-12 leaves the state counts unchanged at every fold
 * depth". Figure.SD_TOLERANCE (the floor used by Figure.epsilonEqual(), which backs the
 * fast (sd, face count, edge count) bucket pre-filter -- see
 * rebuttal/A2_exact_key_spec.md) is swept over {1e-6, 1e-8, 1e-10, 1e-12} at each fold depth,
 * under REQUIRE_EXACT_KEY=true (the production default -- see FoldingMachine.
 * REQUIRE_EXACT_KEY's javadoc), since the paper's claim is specifically that the bucket
 * pre-filter's tolerance doesn't matter BECAUSE the exact comparison decides every merge;
 * a sweep under the old, non-exact key would not support that claim.
 *
 * Only Figure.SD_TOLERANCE is varied here. GeomUtil.EPS itself is left untouched, so the
 * fold-splitting / visibility geometry that decides *which* figures get generated in the
 * first place is identical across runs -- only the duplicate-elimination bucket width is
 * under test.
 *
 * Usage: ./gradlew toleranceSweep
 *
 * Runtime: depth 4 under the exact key is expensive (~1 hour per enumeration per
 * rebuttal/exact_key_enumeration_results.txt), so a full 4-tolerance x depth-4 sweep is
 * expected to take on the order of several hours; depths 1-3 complete in seconds. Consider
 * running detached (e.g. a background terminal) rather than interactively.
 */
public class ToleranceSweep {

    private static final boolean[] CONSTRUCTIONS   = {true, true, true};
    private static final boolean[] TECHS_ALL       = {true, true, true};
    private static final boolean[] TECHS_FOLD_ONLY = {true, true, false}; // crease excluded at the last fold only

    private static final double[] TOLERANCES = {1e-6, 1e-8, 1e-10, 1e-12};

    public static void main(String[] args) throws Exception {
        int maxDepth = 4;
        String outPath = "rebuttal/tolerance_sweep_results.csv";
        for (String a : args) {
            if (a.startsWith("depth=")) maxDepth = Integer.parseInt(a.substring("depth=".length()));
            else if (a.startsWith("out=")) outPath = a.substring("out=".length());
        }

        double savedTolerance = Figure.SD_TOLERANCE;
        int savedThreshold = FoldingMachine.STREAM_THRESHOLD;
        int savedCap = FoldingMachine.DISPLAY_CAP;

        try (PrintWriter csv = new PrintWriter(new FileWriter(outPath))) {
            csv.println("tolerance,depth,total,foldable,unfoldable");
            System.out.println("tolerance,depth,total,foldable,unfoldable,elapsed_ms");

            for (double tol : TOLERANCES) {
                Figure.SD_TOLERANCE = tol;
                FoldingMachine.REQUIRE_EXACT_KEY = true;

                ArrayList<Figure> current = new ArrayList<>();
                current.add(new Figure(200, 200));

                for (int d = 1; d <= maxDepth; d++) {
                    boolean[] techs = (d == maxDepth) ? TECHS_FOLD_ONLY.clone() : TECHS_ALL.clone();
                    FoldingMachine.DISPLAY_CAP = (d == maxDepth) ? 0 : Integer.MAX_VALUE;

                    FoldingMachine fm = new FoldingMachine(CONSTRUCTIONS.clone(), techs);
                    long t0 = System.currentTimeMillis();
                    ArrayList<Figure> folded = fm.FoldOnce(current);
                    long elapsedMs = System.currentTimeMillis() - t0;

                    long total = fm.lastTotalCount;
                    long foldable = fm.lastFoldableCount;
                    long unfoldable = total - foldable;

                    String row = tol + "," + d + "," + total + "," + foldable + "," + unfoldable;
                    csv.println(row);
                    csv.flush();
                    System.out.println(row + "," + elapsedMs);

                    if (d < maxDepth) {
                        ArrayList<Figure> next = new ArrayList<>();
                        for (Figure f : folded) if (f.isFoldable) next.add(f);
                        current = next;
                    }
                }
            }
        } finally {
            Figure.SD_TOLERANCE = savedTolerance;
            FoldingMachine.REQUIRE_EXACT_KEY = false;
            FoldingMachine.STREAM_THRESHOLD = savedThreshold;
            FoldingMachine.DISPLAY_CAP = savedCap;
        }

        System.out.println();
        System.out.println("Done. If every tolerance row for a given depth shows the same "
                + "total/foldable/unfoldable, the state counts do not depend on the bucket "
                + "pre-filter tolerance at that depth (see rebuttal/A2_exact_key_spec.md).");
    }
}
