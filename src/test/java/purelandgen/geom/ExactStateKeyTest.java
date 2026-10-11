package purelandgen.geom;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

/**
 * Validation test for ExactStateKey.
 *
 * Folds a square in half 3 times to an 1/8-area, 2:1-footprint stack, along 4 sequences
 * that are all reachable from the same first fold (fixed arbitrarily as "H", a horizontal
 * fold line -- the first fold is symmetric, so this loses no generality):
 *
 *   H-H-V (mountain)   same crease pattern as H-H-V (valley), differs only in layer order
 *   H-H-V (valley)
 *   H-V-H             different crease pattern (connectivity) from the H-H-V pair
 *   H-V-V             different crease pattern (connectivity) from H-H-V and from H-V-H
 *
 * All 4 end states share the same footprint (a 2:1 rectangle) and the same (sd, face count,
 * edge count), so the current bucket key cannot distinguish them; the exact key must produce
 * exactly 4 distinct keys.
 */
public class ExactStateKeyTest {

    private static final boolean[] CONSTRUCTIONS = {true, true, true};
    private static final boolean[] TECHS_FOLD_ONLY = {true, true, false}; // valley + mountain, no crease

    @After
    public void resetFlag() {
        FoldingMachine.REQUIRE_EXACT_KEY = false;
    }

    private static ArrayList<Figure> singleton(Figure f) {
        ArrayList<Figure> l = new ArrayList<>();
        l.add(f);
        return l;
    }

    // {minX, maxX, minY, maxY} of the current folded footprint.
    private static double[] bbox(Figure fig) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Face f : fig.faces) {
            Halfedge he = f.halfedge;
            do {
                minX = Math.min(minX, he.vertex.p.x); maxX = Math.max(maxX, he.vertex.p.x);
                minY = Math.min(minY, he.vertex.p.y); maxY = Math.max(maxY, he.vertex.p.y);
                he = he.next;
            } while (he != f.halfedge);
        }
        return new double[]{minX, maxX, minY, maxY};
    }

    // Folds fig in half along the current horizontal midline (a "H" fold). May legitimately
    // collapse to one result (e.g. the very first fold, which is symmetric) or split into
    // two (mountain/valley), depending on prior asymmetry; the caller picks by index.
    private static ArrayList<Figure> foldH(Figure fig) {
        double[] bb = bbox(fig);
        double midY = (bb[2] + bb[3]) * 0.5;
        FoldingMachine fm = new FoldingMachine(CONSTRUCTIONS.clone(), TECHS_FOLD_ONLY.clone());
        return fm.FoldTest(singleton(fig), bb[0] - 10, midY, bb[1] + 10, midY);
    }

    private static ArrayList<Figure> foldV(Figure fig) {
        double[] bb = bbox(fig);
        double midX = (bb[0] + bb[1]) * 0.5;
        FoldingMachine fm = new FoldingMachine(CONSTRUCTIONS.clone(), TECHS_FOLD_ONLY.clone());
        return fm.FoldTest(singleton(fig), midX, bb[2] - 10, midX, bb[3] + 10);
    }

    @Test
    public void eighthRectangle_fourFoldOrdersAreAllDistinct() {
        FoldingMachine.REQUIRE_EXACT_KEY = true; // don't let the buggy bucket-only merge hide results

        Figure square = new Figure(200, 200);

        ArrayList<Figure> afterH = foldH(square);
        assertFalse("first H fold should produce at least one result", afterH.isEmpty());
        Figure h = afterH.get(0);

        ArrayList<Figure> afterHH = foldH(h);
        assertFalse(afterHH.isEmpty());
        Figure hh = afterHH.get(0);

        ArrayList<Figure> afterHV = foldV(h);
        assertFalse(afterHV.isEmpty());
        Figure hv = afterHV.get(0);

        // H-H-V: fold the H-H stack vertically. Same crease pattern regardless of which of
        // the (up to 2) layer-order outcomes we take -- both must appear since they share
        // sd/face/edge counts and only the exact key keeps them apart.
        ArrayList<Figure> afterHHV = foldV(hh);
        assertEquals("H-H-V's final fold should yield 2 distinct layer orderings under the exact key",
                2, afterHHV.size());
        Figure hhvA = afterHHV.get(0);
        Figure hhvB = afterHHV.get(1);

        // H-V-H and H-V-V: take one representative each (which of any tied layer-order
        // outcomes we pick doesn't matter -- both branches share the same H-V prefix figure).
        ArrayList<Figure> afterHVH = foldH(hv);
        assertFalse(afterHVH.isEmpty());
        Figure hvh = afterHVH.get(0);

        ArrayList<Figure> afterHVV = foldV(hv);
        assertFalse(afterHVV.isEmpty());
        Figure hvv = afterHVV.get(0);

        // Sanity: all 4 candidates really do share the same fast bucket key, otherwise this
        // test would not be exercising the merge the exact key is meant to fix.
        Figure[] all = {hhvA, hhvB, hvh, hvv};
        for (Figure f : all) {
            assertTrue("expected an 1/8-area, 8-layer result", f.faces.size() == 8);
            assertTrue(hhvA.epsilonEqual(f));
            assertEquals(hhvA.faces.size(), f.faces.size());
            assertEquals(hhvA.edges.size(), f.edges.size());
        }

        // The actual validation: the exact key must tell all 4 apart.
        ExactStateKey[] keys = new ExactStateKey[all.length];
        for (int i = 0; i < all.length; i++) keys[i] = ExactStateKey.compute(all[i]);
        for (int i = 0; i < keys.length; i++) {
            for (int j = i + 1; j < keys.length; j++) {
                assertNotEquals("states " + i + " and " + j + " should be distinct", keys[i], keys[j]);
            }
        }
    }

    @Test
    public void currentBucketKey_mergesTheMountainValleyPair() {
        FoldingMachine.REQUIRE_EXACT_KEY = false;

        Figure square = new Figure(200, 200);
        Figure h  = foldH(square).get(0);
        Figure hh = foldH(h).get(0);

        // With the flag off, the current (sd, faces, edges) key alone collapses the two
        // layer-order outcomes of the final fold into one -- this is the bug A2 addresses.
        ArrayList<Figure> afterHHV = foldV(hh);
        assertEquals("current key should merge the mountain/valley pair into one entry",
                1, afterHHV.size());
    }
}
