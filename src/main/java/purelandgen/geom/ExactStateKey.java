package purelandgen.geom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Exact, layer-order-and-symmetry-aware duplicate-elimination key for a folded Figure.
 * See rebuttal/A2_exact_key_spec.md for the design rationale.
 *
 * A folding state is fully determined by:
 *  - the crease pattern: the complete planar subdivision of the original flat square,
 *    each face tagged with its original (unfolded) position (Vertex.origP);
 *  - the overlap order R: the "below/above" relation restricted to folded-space
 *    face pairs that actually overlap with nonzero area (independent faces are left
 *    unordered).
 *
 * The key is canonicalized under G = D4 x {flip} (16 elements: the 8 symmetries of the
 * original square, combined with turning the whole folded packet over, which reverses R),
 * by taking the lexicographically smallest encoding over all 16 group elements.
 *
 * Requires Vertex.origP to be populated along the figure's full fold lineage, starting
 * from Figure(w,h) (see Vertex.origP and GeomUtil.getCrossPointByHalfedge).
 */
public final class ExactStateKey {

    // Coordinate snapping applied before building the canonical string, so that float
    // noise from repeated reflections/interpolation cannot create spurious distinctions.
    // 1e5 -> 5 decimal digits, far coarser than a handful of folds' compounded error
    // and far finer than any real crease-pattern feature on a 200-unit paper.
    private static final double SNAP = 1.0e5;

    private final String encoded;

    private ExactStateKey(String encoded) {
        this.encoded = encoded;
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof ExactStateKey) && encoded.equals(((ExactStateKey) o).encoded);
    }

    @Override
    public int hashCode() {
        return encoded.hashCode();
    }

    @Override
    public String toString() {
        return encoded;
    }

    /**
     * A fixed-length (128-bit, 32 hex chars) SHA-256 digest of the full encoded key, for use in
     * place of {@link #toString()} when the full key text itself would dominate storage: R
     * repeats each overlapping face's complete vertex list once per overlap it participates in,
     * so for figures with many overlapping face pairs the encoded key can be as large as -- or
     * larger than -- the figure's own geometry (confirmed: at 4 folds, dropping the geometry
     * ("data") column alone only took the round-4 DB from 7.4GB to 6.9GB -- the encoded exact_key
     * column, not geometry, is the dominant cost).
     *
     * Dedup only needs equality comparison, never reconstruction of the original key, so a
     * 128-bit digest's collision probability is astronomically small at any realistic
     * enumeration scale (birthday bound ~ n^2/2^128).
     */
    public String hashHex() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(encoded.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e); // SHA-256 is guaranteed present on every standard JCE provider
        }
    }

    public static ExactStateKey compute(Figure fig) {
        final int n = fig.faces.size();

        // Each face's polygon in original-square space (for the crease pattern) and in
        // current folded space (for the overlap test), both in halfedge-ring order.
        List<List<Vec2>> origPolys = new ArrayList<>(n);
        List<ArrayList<Vec2>> foldedPolys = new ArrayList<>(n);
        for (Face f : fig.faces) {
            List<Vec2> op = new ArrayList<>();
            ArrayList<Vec2> fp = new ArrayList<>();
            Halfedge he = f.halfedge;
            do {
                if (he.vertex.origP == null) {
                    throw new IllegalStateException(
                            "Vertex.origP not tracked for this figure; ExactStateKey requires " +
                            "full fold lineage starting from Figure(w,h)");
                }
                op.add(he.vertex.origP);
                fp.add(he.vertex.p);
                he = he.next;
            } while (he != f.halfedge);
            origPolys.add(op);
            foldedPolys.add(fp);
        }

        // Overlap order R, recorded once as (lowerPos, upperPos) pairs of positions into
        // fig.faces -- fig.faces is a valid linear extension of the true stacking order
        // (Figure.buildEdges()'s invariant f.index == position in fig.faces), so index
        // order among an overlapping pair is exactly "below, above".
        List<int[]> overlapPairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (GeomUtil.polygonsOverlap(foldedPolys.get(i), foldedPolys.get(j))) {
                    overlapPairs.add(new int[]{i, j});
                }
            }
        }

        Vec2 center = _origSquareCenter(fig);

        String best = null;
        for (int g = 0; g < 8; g++) {
            List<String> polySig = new ArrayList<>(n);
            for (List<Vec2> op : origPolys) {
                polySig.add(_canonicalPolygonString(_applyD4(op, center, g)));
            }

            List<String> sortedShapes = new ArrayList<>(polySig);
            Collections.sort(sortedShapes);

            for (int flip = 0; flip < 2; flip++) {
                List<String> rStrings = new ArrayList<>(overlapPairs.size());
                for (int[] pr : overlapPairs) {
                    String below = polySig.get(pr[0]);
                    String above = polySig.get(pr[1]);
                    if (flip == 1) { String t = below; below = above; above = t; }
                    rStrings.add(below + ">" + above);
                }
                Collections.sort(rStrings);

                StringBuilder sb = new StringBuilder();
                sb.append(sortedShapes.size()).append('#');
                for (String s : sortedShapes) sb.append(s).append(';');
                sb.append('|').append(rStrings.size()).append('#');
                for (String s : rStrings) sb.append(s).append(';');
                String candidate = sb.toString();

                if (best == null || candidate.compareTo(best) < 0) best = candidate;
            }
        }
        return new ExactStateKey(best);
    }

    /**
     * Crease-pattern-only key: same as {@link #compute}'s D4-canonicalized face-shape
     * signature, but WITHOUT the overlap order R (no O(n^2) polygonsOverlap pass) and
     * without the flip component (flip only ever reorders R, never the crease pattern).
     *
     * Two states with the same crease pattern differ, if at all, only in which faces are
     * folded valley vs mountain -- so this key is exactly "same fold line(s), direction(s)
     * aside" and is intended for perceptual-graph path counting (rebuttal/recompute_plan.md
     * S2.1: a silhouette-preserving valley/mountain choice does not create a new discoverable
     * quiz path), NOT for shape-identity dedup (use {@link #compute} there).
     */
    public static String computeCreasePatternKey(Figure fig) {
        final int n = fig.faces.size();
        List<List<Vec2>> origPolys = new ArrayList<>(n);
        for (Face f : fig.faces) {
            List<Vec2> op = new ArrayList<>();
            Halfedge he = f.halfedge;
            do {
                if (he.vertex.origP == null) {
                    throw new IllegalStateException(
                            "Vertex.origP not tracked for this figure; computeCreasePatternKey " +
                            "requires full fold lineage starting from Figure(w,h)");
                }
                op.add(he.vertex.origP);
                he = he.next;
            } while (he != f.halfedge);
            origPolys.add(op);
        }

        Vec2 center = _origSquareCenter(fig);

        String best = null;
        for (int g = 0; g < 8; g++) {
            List<String> polySig = new ArrayList<>(n);
            for (List<Vec2> op : origPolys) {
                polySig.add(_canonicalPolygonString(_applyD4(op, center, g)));
            }
            Collections.sort(polySig);

            StringBuilder sb = new StringBuilder();
            sb.append(polySig.size()).append('#');
            for (String s : polySig) sb.append(s).append(';');
            String candidate = sb.toString();

            if (best == null || candidate.compareTo(best) < 0) best = candidate;
        }
        return best;
    }

    // Center of the original flat square: the original corners are always the extremal
    // origP points (creases only ever subdivide the square, never extend past its edges),
    // so min/max over every tracked vertex's origP finds them regardless of face structure.
    private static Vec2 _origSquareCenter(Figure fig) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Vertex v : fig.vertices) {
            if (v.origP == null) continue;
            minX = Math.min(minX, v.origP.x); maxX = Math.max(maxX, v.origP.x);
            minY = Math.min(minY, v.origP.y); maxY = Math.max(maxY, v.origP.y);
        }
        return new Vec2((minX + maxX) * 0.5, (minY + maxY) * 0.5);
    }

    // The 8 symmetries of a square about `center` (4 rotations, 4 reflections).
    private static List<Vec2> _applyD4(List<Vec2> pts, Vec2 center, int g) {
        List<Vec2> out = new ArrayList<>(pts.size());
        for (Vec2 p : pts) {
            double x = p.x - center.x, y = p.y - center.y;
            double nx, ny;
            switch (g) {
                case 0: nx =  x; ny =  y; break; // identity
                case 1: nx = -y; ny =  x; break; // rotate 90
                case 2: nx = -x; ny = -y; break; // rotate 180
                case 3: nx =  y; ny = -x; break; // rotate 270
                case 4: nx =  x; ny = -y; break; // reflect across x-axis
                case 5: nx = -x; ny =  y; break; // reflect across y-axis
                case 6: nx =  y; ny =  x; break; // reflect across y=x
                default: nx = -y; ny = -x; break; // reflect across y=-x
            }
            out.add(new Vec2(nx + center.x, ny + center.y));
        }
        return out;
    }

    // Canonical string for one face's polygon: snap coordinates, then pick the
    // lexicographically smallest listing over all starting vertices and both winding
    // directions, so the result depends only on the point set, not on halfedge traversal
    // order or isFront-derived winding.
    private static String _canonicalPolygonString(List<Vec2> poly) {
        final int n = poly.size();
        long[][] snapped = new long[n][2];
        for (int i = 0; i < n; i++) {
            snapped[i][0] = Math.round(poly.get(i).x * SNAP);
            snapped[i][1] = Math.round(poly.get(i).y * SNAP);
        }
        String best = null;
        for (int dir = 0; dir < 2; dir++) {
            for (int start = 0; start < n; start++) {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < n; k++) {
                    int idx = (dir == 0) ? (start + k) % n : ((start - k) % n + n) % n;
                    sb.append(snapped[idx][0]).append(',').append(snapped[idx][1]).append(';');
                }
                String s = sb.toString();
                if (best == null || s.compareTo(best) < 0) best = s;
            }
        }
        return best;
    }
}
