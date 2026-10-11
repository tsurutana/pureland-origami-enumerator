package purelandgen.analysis;

import purelandgen.geom.ExactSilhouetteGrouping;
import purelandgen.geom.Figure;
import purelandgen.geom.FoldingMachine;
import purelandgen.geom.Silhouette;

import java.util.ArrayList;
import java.util.List;

/**
 * Reproduces Table 3's (tab:silhouettes) three-fold "quiz pool" silhouette statistics, under
 * the exact, layer-order-aware state key (purelandgen.geom.ExactStateKey -- the production
 * default since FoldingMachine.REQUIRE_EXACT_KEY=true; see rebuttal/A2_exact_key_spec.md and
 * the README's "Reproducing the paper's reported numbers" section for why this no longer
 * matches the published 1,004/903/312/35/492/442 exactly). Also run at 2 folds as a sanity
 * check (no state should be eliminated there, matching Table 2/tab:counts).
 *
 * Distinct-silhouette counts here come straight from the same true exact-polygon congruence
 * grouping Silhouette.computeProperties() now uses internally (purelandgen.geom.
 * ExactSilhouetteGrouping -- see rebuttal/A4_visibility_identity_congruence_summary.md Sec 3),
 * not a scalar shape descriptor. For an independent, no-shortcuts verification that this fast
 * (bucket-then-refine) grouping never misses a cross-bucket merge, see fullCongruenceCheck.
 *
 * Usage: ./gradlew table3SilhouetteStats
 */
public class Table3SilhouetteStats {

    private static final boolean[] CONSTRUCTIONS   = {true, true, true};
    private static final boolean[] TECHS_ALL       = {true, true, true};  // rounds 1..depth-1
    private static final boolean[] TECHS_FOLD_ONLY = {true, true, false}; // last round: no make-crease
    private static final int[] DEPTHS = {2, 3};

    public static void main(String[] args) {
        for (int depth : DEPTHS) {
            System.out.println("########## depth " + depth + " ##########");
            ArrayList<Figure> list = runToDepth(depth);
            Stats.compute(list).print();
            System.out.println();
        }
    }

    private static ArrayList<Figure> runToDepth(int depth) {
        ArrayList<Figure> current = new ArrayList<>();
        current.add(new Figure(200, 200));

        ArrayList<Figure> lastRoundOutput = null;
        for (int d = 1; d <= depth; d++) {
            boolean[] techs = (d == depth) ? TECHS_FOLD_ONLY.clone() : TECHS_ALL.clone();
            FoldingMachine.REQUIRE_EXACT_KEY = true;
            FoldingMachine fm = new FoldingMachine(CONSTRUCTIONS.clone(), techs);
            ArrayList<Figure> folded = fm.FoldOnce(current);
            System.out.println("  depth " + d + ": " + fm.lastTotalCount + " generated, "
                    + folded.size() + " kept");
            if (d == depth) {
                lastRoundOutput = folded;
            } else {
                ArrayList<Figure> next = new ArrayList<>();
                for (Figure f : folded) if (f.isFoldable) next.add(f);
                current = next;
            }
        }
        return lastRoundOutput;
    }

    private static boolean isMakeCrease(Figure f) {
        return f.isMakeCreaseFold();
    }

    private static class Stats {
        int allStates;
        int foldableStates;
        int uniqueSilhouetteFoldable;
        int multiPathFoldable;
        int distinctSilhouettes;
        int distinctSilhouettesWithFoldable;

        static Stats compute(ArrayList<Figure> lastRoundOutput) {
            // Run on the full round output (crease-type folds included), matching
            // MainFrame.enumerate()'s actual call -- Silhouette.computeProperties()'s
            // uniqueness test already excludes MAKECREASE figures from foldableCount
            // internally, so including them here vs. filtering first gives identical
            // isUniqueSilhouette values for the real fold figures, and is the faithful
            // reproduction of how the paper's numbers were actually produced.
            Silhouette.computeProperties(lastRoundOutput);

            ArrayList<Figure> allStates = new ArrayList<>();
            for (Figure f : lastRoundOutput) if (!isMakeCrease(f)) allStates.add(f);

            Stats s = new Stats();
            s.allStates = allStates.size();
            for (Figure f : allStates) {
                if (f.isFoldable) {
                    s.foldableStates++;
                    if (f.isUniqueSilhouette) s.uniqueSilhouetteFoldable++;
                    if (f.hasMultiPath) s.multiPathFoldable++;
                }
            }

            // Distinct-silhouette counts: computeProperties() above already grouped allStates
            // internally by exact polygon congruence, but doesn't keep a per-figure group-id
            // field -- only size/uniqueness flags -- so the cheapest way to recover a class
            // COUNT is to group once more here. Same method, same input, same result; this is
            // not a second, independent computation to cross-check against.
            List<List<Figure>> classes = ExactSilhouetteGrouping.group(allStates);
            s.distinctSilhouettes = classes.size();
            for (List<Figure> cls : classes) {
                boolean hasFoldable = false;
                for (Figure f : cls) if (f.isFoldable) hasFoldable = true;
                if (hasFoldable) s.distinctSilhouettesWithFoldable++;
            }
            return s;
        }

        void print() {
            System.out.println("All states: " + allStates);
            System.out.println("Foldable states: " + foldableStates);
            System.out.println("  with a unique silhouette: " + uniqueSilhouetteFoldable);
            System.out.println("  with multiple fold paths: " + multiPathFoldable);
            System.out.println("Distinct silhouettes: " + distinctSilhouettes);
            System.out.println("  with a foldable realization: " + distinctSilhouettesWithFoldable);
        }
    }
}
