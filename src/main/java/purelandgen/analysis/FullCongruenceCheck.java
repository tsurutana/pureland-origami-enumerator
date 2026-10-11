package purelandgen.analysis;

import purelandgen.geom.ExactSilhouetteGrouping;
import purelandgen.geom.Figure;
import purelandgen.geom.FoldingMachine;
import purelandgen.geom.Silhouette;

import java.util.ArrayList;
import java.util.List;

/**
 * Follow-up to SilhouetteCongruenceCheck (see rebuttal/silhouette_congruence_check_results.txt):
 * that check only tested for OVER-merging within the EXISTING matchingValue-chain groups (492
 * of them), plus a spot-check of the gap to ADJACENT groups in sorted matchingValue order. It
 * could NOT detect a missed merge between two states whose matchingValue differs enough to land
 * in non-adjacent chain groups -- exactly the failure mode PathGraphAnalyzer's independent
 * exact-congruence run surfaced on a DIFFERENT population (crease-pattern representatives
 * reached via more diverse fold histories): 706 ck-representatives collapsed to 412 true
 * silhouette classes, not ~492 -- a ~16% difference, confirmed by directly, visually inspecting
 * the two largest merges (n=32 and n=27 crease-pattern representatives, all the same shape).
 *
 * EARLIER VERSION OF THIS CHECK (WRONG, kept here as a documented dead end): bucketed by
 * (faces, edges) before running splitByCongruence(), on the theory that it's a discrete,
 * noise-free invariant unlike matchingValue. This is WRONG for silhouette congruence
 * specifically: two states can share the exact same real-world SILHOUETTE while differing in
 * internal crease complexity (e.g. one has extra fold-and-unfold creases from an earlier round
 * that don't touch the outer boundary) -- so they can have DIFFERENT face/edge counts despite
 * being genuinely congruent. Bucketing by (faces,edges) first artificially separates such pairs
 * before they ever get compared, fabricating spurious extra "distinct" classes. Confirmed
 * empirically: that version reported 492 -> 750 with "0 cross-chain merges" -- internally
 * consistent-looking, but a methodology artifact, not a real finding (the fabricated splits are
 * exactly the states whose internal mesh complexity differs despite congruent outlines).
 *
 * This version instead runs splitByCongruence() on the ENTIRE 1429-state population directly,
 * NO pre-filter bucketing at all -- the only way to be certain no genuine cross-group merge is
 * missed by an unsafe invariant choice. O(n^2) ~ 1M pairs; the fast (single-subpath) path's
 * per-pair cost is small, so this is expected to complete in well under a minute.
 *
 * Usage: ./gradlew fullCongruenceCheck
 */
public class FullCongruenceCheck {

    private static final boolean[] CONSTRUCTIONS   = {true, true, true};
    private static final boolean[] TECHS_ALL       = {true, true, true};
    private static final boolean[] TECHS_FOLD_ONLY = {true, true, false};

    public static void main(String[] args) {
        ArrayList<Figure> lastRoundOutput = runToDepth(3, true);
        Silhouette.computeProperties(lastRoundOutput); // sets matchingValue, for the baseline comparison only

        ArrayList<Figure> allStates = new ArrayList<>();
        for (Figure f : lastRoundOutput) if (!f.isMakeCreaseFold()) allStates.add(f);
        System.out.println("All states: " + allStates.size());

        // Baseline: matchingValue chain, exactly Silhouette.computeProperties()'s logic.
        int n = allStates.size();
        ArrayList<Integer> idx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) idx.add(i);
        idx.sort((a, b) -> Double.compare(allStates.get(a).matchingValue, allStates.get(b).matchingValue));
        int baselineDistinct = 0, i2 = 0;
        while (i2 < n) {
            double mv = allStates.get(idx.get(i2)).matchingValue;
            int j = i2 + 1;
            while (j < n && Math.abs(allStates.get(idx.get(j)).matchingValue - mv) < 1e-6) j++;
            baselineDistinct++;
            i2 = j;
        }
        System.out.println("Baseline (matchingValue chain): " + baselineDistinct + " distinct silhouettes"
                + "  (expected 492, the published/validated count)");

        // Full check: NO pre-filter bucketing at all -- run the exact-congruence union-find
        // directly over the entire population. Slower (O(n^2)) but the only way to be sure no
        // genuine merge is missed by an unsafe invariant choice (see class javadoc for why
        // (faces,edges) bucketing, tried first, was wrong).
        long pairsChecked = (long) n * (n - 1) / 2;
        System.out.println("Running brute-force pairwise exact congruence over all " + n
                + " states (" + pairsChecked + " pairs)... this may take a little while.");
        long t0 = System.currentTimeMillis();
        List<List<Figure>> classes = ExactSilhouetteGrouping.splitByCongruence(allStates, ExactSilhouetteGrouping.DEFAULT_EPS);
        long elapsedMs = System.currentTimeMillis() - t0;
        int fullDistinct = classes.size();

        int crossChainMerges = 0;
        for (List<Figure> cls : classes) {
            if (cls.size() < 2) continue;
            double first = cls.get(0).matchingValue;
            boolean spansMultipleChains = false;
            for (Figure f : cls) {
                if (Math.abs(f.matchingValue - first) >= 1e-6) { spansMultipleChains = true; break; }
            }
            if (spansMultipleChains) {
                crossChainMerges++;
                StringBuilder mvs = new StringBuilder();
                for (Figure f : cls) mvs.append(f.matchingValue).append(' ');
                System.out.println("  CROSS-CHAIN MERGE: class size=" + cls.size()
                        + " faces=" + cls.get(0).faces.size() + " edges=" + cls.get(0).edges.size()
                        + " matchingValues=" + mvs);
            }
        }

        System.out.println();
        System.out.println("Elapsed: " + elapsedMs + " ms");
        System.out.println("Full check (brute-force, no pre-filter): " + fullDistinct + " distinct silhouettes");
        System.out.println("Baseline (matchingValue chain):           " + baselineDistinct + " distinct silhouettes");
        System.out.println("Delta: " + (fullDistinct - baselineDistinct)
                + "  (" + crossChainMerges + " classes span multiple matchingValue chains -- true missed merges)");
    }

    private static ArrayList<Figure> runToDepth(int depth, boolean useExactKey) {
        ArrayList<Figure> current = new ArrayList<>();
        current.add(new Figure(200, 200));
        ArrayList<Figure> lastRoundOutput = null;
        for (int d = 1; d <= depth; d++) {
            boolean[] techs = (d == depth) ? TECHS_FOLD_ONLY.clone() : TECHS_ALL.clone();
            FoldingMachine.REQUIRE_EXACT_KEY = useExactKey;
            FoldingMachine fm = new FoldingMachine(CONSTRUCTIONS.clone(), techs);
            ArrayList<Figure> folded = fm.FoldOnce(current);
            if (d == depth) {
                lastRoundOutput = folded;
            } else {
                ArrayList<Figure> next = new ArrayList<>();
                for (Figure f : folded) if (f.isFoldable) next.add(f);
                current = next;
            }
        }
        FoldingMachine.REQUIRE_EXACT_KEY = false;
        return lastRoundOutput;
    }
}
