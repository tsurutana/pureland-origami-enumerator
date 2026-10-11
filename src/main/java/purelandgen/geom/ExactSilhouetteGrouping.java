package purelandgen.geom;

import purelandgen.matching.MomentOperator;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups figures by TRUE silhouette congruence (translation + rotation + reflection, no
 * scaling), instead of a single scalar shape descriptor (matchingValue = log(mu20+mu02))
 * compared via a sorted-scan chain threshold, which can -- and, confirmed empirically in the
 * 3-fold quiz pool, does -- conflate two genuinely different silhouettes that happen to land
 * within the chain's reach of each other. Used internally by
 * {@link Silhouette#computeProperties}.
 *
 * Method: bucket candidates first by the existing scalar descriptor (matchingValue, same 1e-6
 * chain as Silhouette.computeProperties() -- safe as a fast PRE-filter only, since the smallest
 * observed gap between genuinely distinct silhouettes' matchingValue is enormously larger, on
 * the order of 1 absolute unit, than any real same-shape noise), then within each bucket run a
 * union-find over pairwise EXACT polygon congruence:
 *   1. (fast path, single-subpath silhouettes -- the overwhelming majority): both outlines are
 *      normalized to CCW winding and have redundant near-collinear/near-duplicate vertices
 *      merged away (positional tests, not angular -- see simplifyCollinear() javadoc), then for
 *      every cyclic correspondence and both chiralities, the OPTIMAL rigid rotation (closed-
 *      form 2D Procrustes) is computed and the worst-vertex residual after applying it is
 *      recorded; the minimum such residual over every candidate is the reported discrepancy.
 *   2. (fallback, multi-subpath silhouettes -- holes/islands from the Area union, rare at 3
 *      folds): centroid-align by translation, then search rotation angle (coarse + refined)
 *      and both chiralities for the alignment minimizing the Area symmetric-difference relative
 *      to the shapes' area.
 * Two states are congruent iff their discrepancy is at most {@link #DEFAULT_EPS} (1e-4) --
 * confirmed empirically to sit comfortably above this enumeration's actual landmark-coordinate
 * floating-point noise ceiling (~1e-6..1e-7, confirmed via two independent bugs while building
 * this check) and comfortably below the smallest observed gap between genuinely distinct
 * silhouettes (raw ~0.84 in the 3-fold quiz pool).
 */
public final class ExactSilhouetteGrouping {

    /** Validated threshold (sheet-coordinate units / dimensionless relative-area, see class
     *  javadoc) -- comfortably above the confirmed noise ceiling, comfortably below genuine
     *  shape differences. Do not lower without re-checking against a range of tighter/looser
     *  values first (confirm no genuinely-congruent pair falls outside the new threshold, and
     *  no genuinely-distinct pair falls inside it). */
    public static final double DEFAULT_EPS = 1.0e-4;

    private static final double COLLINEAR_POS_TOL = 1.0e-4;
    private static final double DUPLICATE_POINT_TOL = 1.0e-4;

    private ExactSilhouetteGrouping() {}

    /** Groups figures by exact silhouette congruence at {@link #DEFAULT_EPS}. Does not mutate
     *  the input figures (matchingValue is (re)computed internally as a bucketing pre-filter
     *  only -- the returned grouping is authoritative, not the figures' own fields). */
    public static List<List<Figure>> group(List<Figure> figures) {
        return group(figures, DEFAULT_EPS);
    }

    public static List<List<Figure>> group(List<Figure> figures, double eps) {
        int n = figures.size();
        double[] mv = new double[n];
        for (int i = 0; i < n; i++) {
            mv[i] = MomentOperator.getMatchingValue(Silhouette.getSilhouette(figures.get(i)));
        }
        ArrayList<Integer> idx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) idx.add(i);
        idx.sort((a, b) -> Double.compare(mv[a], mv[b]));

        List<List<Figure>> result = new ArrayList<>();
        int i = 0;
        while (i < n) {
            double v = mv[idx.get(i)];
            int j = i + 1;
            while (j < n && Math.abs(mv[idx.get(j)] - v) < 1e-6) j++;
            List<Figure> bucket = new ArrayList<>();
            for (int k = i; k < j; k++) bucket.add(figures.get(idx.get(k)));
            result.addAll(splitByCongruence(bucket, eps));
            i = j;
        }
        return result;
    }

    /**
     * Union-find over pairwise exact congruence within a candidate list the CALLER already
     * knows share one bucket under some OTHER scalar-descriptor grouping (e.g. a caller with
     * its own matchingValue-bucketing scheme, which need not agree in its exact partition with
     * this class's internal one -- {@link #group} does its own scalar pre-bucketing via a
     * sorted-scan 1e-6 chain, same style as {@link Silhouette#computeProperties}; a caller
     * using a DIFFERENT scalar-bucketing algorithm, even one that happens to produce the same
     * group COUNT on some population, is not guaranteed to produce the same PARTITION -- mixing
     * the two (letting {@link #group}'s own pre-filter re-bucket a caller's already-finer
     * groups) can silently MERGE candidates the caller had correctly kept separate, which
     * should be structurally impossible for a congruence refinement and is a sure sign of this
     * mismatch, not a real finding). Only ever SPLITS the input list, never merges across calls.
     */
    public static List<List<Figure>> splitByCongruence(List<Figure> bucket, double eps) {
        if (bucket.size() == 1) {
            List<List<Figure>> single = new ArrayList<>();
            single.add(bucket);
            return single;
        }
        int[] parent = new int[bucket.size()];
        for (int k = 0; k < parent.length; k++) parent[k] = k;
        for (int a = 0; a < bucket.size(); a++) {
            for (int b = a + 1; b < bucket.size(); b++) {
                if (gap(bucket.get(a), bucket.get(b)) <= eps) union(parent, a, b);
            }
        }
        Map<Integer, List<Figure>> classes = new LinkedHashMap<>();
        for (int k = 0; k < bucket.size(); k++) {
            classes.computeIfAbsent(find(parent, k), x -> new ArrayList<>()).add(bucket.get(k));
        }
        return new ArrayList<>(classes.values());
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    // ---- continuous discrepancy between two silhouettes (epsilon-independent) ----

    /** Exposed for diagnostics only -- the raw, continuous, epsilon-independent discrepancy
     *  between two silhouettes. See {@link #splitByCongruence}. */
    public static double rawGap(Figure fa, Figure fb) {
        return gap(fa, fb);
    }

    private static double gap(Figure fa, Figure fb) {
        Area areaA = Silhouette.getSilhouette(fa);
        Area areaB = Silhouette.getSilhouette(fb);
        List<List<Point2D>> subA = subpaths(areaA);
        List<List<Point2D>> subB = subpaths(areaB);

        if (subA.size() == 1 && subB.size() == 1) {
            return fastGap(dropClosingDuplicate(subA.get(0)), dropClosingDuplicate(subB.get(0)));
        }
        return fallbackRelDiff(areaA, areaB);
    }

    // ---- fast path: 2D Procrustes (optimal rotation per candidate correspondence) + max vertex residual ----

    private static double fastGap(List<Point2D> ptsA, List<Point2D> ptsB) {
        List<Point2D> a = simplifyCollinear(ptsA);
        List<Point2D> b = simplifyCollinear(ptsB);
        if (a.size() != b.size() || a.size() < 3) {
            return Double.MAX_VALUE / 4;
        }
        List<Point2D> accw = normalizeCCW(a);
        List<Point2D> bccw = normalizeCCW(b);
        double direct = bestAlignmentResidual(accw, bccw);
        List<Point2D> bMirrorCcw = normalizeCCW(mirror(b));
        double mirrored = bestAlignmentResidual(accw, bMirrorCcw);
        return Math.min(direct, mirrored);
    }

    private static double bestAlignmentResidual(List<Point2D> a, List<Point2D> b) {
        int n = a.size();
        double[] ax = new double[n], ay = new double[n], bx = new double[n], by = new double[n];
        for (int k = 0; k < n; k++) {
            ax[k] = a.get(k).getX(); ay[k] = a.get(k).getY();
            bx[k] = b.get(k).getX(); by[k] = b.get(k).getY();
        }
        double acx = mean(ax), acy = mean(ay), bcx = mean(bx), bcy = mean(by);
        double[] axc = centered(ax, acx), ayc = centered(ay, acy);
        double[] bxc = centered(bx, bcx), byc = centered(by, bcy);

        double best = Double.MAX_VALUE;
        for (int start = 0; start < n; start++) {
            double c = 0, s = 0;
            for (int k = 0; k < n; k++) {
                int ib = (start + k) % n;
                c += axc[k] * bxc[ib] + ayc[k] * byc[ib];
                s += axc[k] * byc[ib] - ayc[k] * bxc[ib];
            }
            double theta = Math.atan2(s, c);
            double cosT = Math.cos(theta), sinT = Math.sin(theta);
            double maxResidual = 0;
            for (int k = 0; k < n; k++) {
                int ib = (start + k) % n;
                double rx = cosT * axc[k] - sinT * ayc[k];
                double ry = sinT * axc[k] + cosT * ayc[k];
                double dist = Math.hypot(rx - bxc[ib], ry - byc[ib]);
                if (dist > maxResidual) maxResidual = dist;
            }
            if (maxResidual < best) best = maxResidual;
        }
        return best;
    }

    private static double mean(double[] xs) {
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.length;
    }

    private static double[] centered(double[] xs, double mean) {
        double[] out = new double[xs.length];
        for (int i = 0; i < xs.length; i++) out[i] = xs[i] - mean;
        return out;
    }

    // java.awt.geom.Area's PathIterator is NOT consistent about how it closes a subpath --
    // only drop the last point when it actually duplicates the first.
    private static List<Point2D> dropClosingDuplicate(List<Point2D> closedLoop) {
        int n = closedLoop.size();
        if (n >= 2 && closedLoop.get(0).distance(closedLoop.get(n - 1)) < 1e-9) {
            return new ArrayList<>(closedLoop.subList(0, n - 1));
        }
        return new ArrayList<>(closedLoop);
    }

    private static List<Point2D> mirror(List<Point2D> pts) {
        List<Point2D> out = new ArrayList<>(pts.size());
        for (Point2D p : pts) out.add(new Point2D.Double(p.getX(), -p.getY()));
        return out;
    }

    private static double signedArea(List<Point2D> pts) {
        double a = 0;
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            Point2D p = pts.get(i), q = pts.get((i + 1) % n);
            a += p.getX() * q.getY() - q.getX() * p.getY();
        }
        return a / 2.0;
    }

    private static List<Point2D> normalizeCCW(List<Point2D> pts) {
        List<Point2D> copy = new ArrayList<>(pts);
        if (signedArea(copy) < 0) Collections.reverse(copy);
        return copy;
    }

    // Removes redundant near-collinear/near-duplicate vertices via POSITIONAL (not angular)
    // tests -- this enumeration's landmark-coordinate precision is ~1e-6..1e-7 (confirmed), so
    // both thresholds are set at 1e-4: comfortably above that noise, comfortably below any real
    // shape feature (see class javadoc).
    private static List<Point2D> simplifyCollinear(List<Point2D> pts) {
        List<Point2D> cur = new ArrayList<>(pts);
        boolean changed = true;
        while (changed && cur.size() > 3) {
            changed = false;
            int n = cur.size();
            for (int i = 0; i < n; i++) {
                Point2D prev = cur.get((i - 1 + n) % n), p = cur.get(i), next = cur.get((i + 1) % n);
                if (p.distance(prev) < DUPLICATE_POINT_TOL || p.distance(next) < DUPLICATE_POINT_TOL) {
                    cur.remove(i);
                    changed = true;
                    break;
                }
                double segX = next.getX() - prev.getX(), segY = next.getY() - prev.getY();
                double segLen = Math.hypot(segX, segY);
                if (segLen < 1e-9) {
                    cur.remove(i);
                    changed = true;
                    break;
                }
                double px = p.getX() - prev.getX(), py = p.getY() - prev.getY();
                double perpDist = Math.abs(segX * py - segY * px) / segLen;
                if (perpDist < COLLINEAR_POS_TOL) {
                    cur.remove(i);
                    changed = true;
                    break;
                }
            }
        }
        return cur;
    }

    // ---- fallback: brute-force rotation/reflection alignment + Area overlap ----

    private static double fallbackRelDiff(Area a, Area b) {
        double areaA = polygonArea(a), areaB = polygonArea(b);
        if (areaA < 1e-9 || areaB < 1e-9) return (areaA < 1e-9 && areaB < 1e-9) ? 0 : Double.MAX_VALUE / 4;
        if (Math.abs(areaA - areaB) / Math.max(areaA, areaB) > 1e-4) return Double.MAX_VALUE / 4;

        double[] ca = centroid(a), cb = centroid(b);
        double bestRel = Double.MAX_VALUE, bestDeg = 0;
        boolean bestMirror = false;
        for (int mirror = 0; mirror < 2; mirror++) {
            for (int deg = 0; deg < 360; deg += 2) {
                double rel = relSymDiff(a, b, ca, cb, Math.toRadians(deg), mirror == 1, areaA, areaB);
                if (rel < bestRel) { bestRel = rel; bestDeg = deg; bestMirror = mirror == 1; }
            }
        }
        double lo = bestDeg - 2, hi = bestDeg + 2, step = 0.5;
        for (int iter = 0; iter < 6; iter++) {
            double bestLocal = bestDeg;
            for (double d = lo; d <= hi; d += step) {
                double rel = relSymDiff(a, b, ca, cb, Math.toRadians(d), bestMirror, areaA, areaB);
                if (rel < bestRel) { bestRel = rel; bestLocal = d; }
            }
            bestDeg = bestLocal;
            lo = bestDeg - step * 2; hi = bestDeg + step * 2; step /= 4;
        }
        return bestRel;
    }

    private static double relSymDiff(Area a, Area b, double[] ca, double[] cb,
                                      double theta, boolean mirrorFlag, double areaA, double areaB) {
        AffineTransform t = new AffineTransform();
        t.translate(cb[0], cb[1]);
        t.rotate(theta);
        if (mirrorFlag) t.scale(-1, 1);
        t.translate(-ca[0], -ca[1]);

        Area aligned = new Area(a);
        aligned.transform(t);
        Area xor = new Area(aligned);
        xor.exclusiveOr(b);
        double diff = polygonArea(xor);
        return diff / Math.max(areaA, areaB);
    }

    private static double[] centroid(Area area) {
        PathIterator pi = area.getPathIterator(null, 1e-6);
        double a00 = 0, a10 = 0, a01 = 0;
        double startX = 0, startY = 0, prevX = 0, prevY = 0;
        double[] c = new double[6];
        while (!pi.isDone()) {
            int type = pi.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                startX = prevX = c[0]; startY = prevY = c[1];
            } else if (type == PathIterator.SEG_LINETO || type == PathIterator.SEG_CLOSE) {
                double x = (type == PathIterator.SEG_CLOSE) ? startX : c[0];
                double y = (type == PathIterator.SEG_CLOSE) ? startY : c[1];
                double cross = prevX * y - x * prevY;
                a00 += cross;
                a10 += cross * (prevX + x);
                a01 += cross * (prevY + y);
                prevX = x; prevY = y;
            }
            pi.next();
        }
        if (Math.abs(a00) < 1e-12) return new double[]{0, 0};
        return new double[]{a10 / (3 * a00), a01 / (3 * a00)};
    }

    private static double polygonArea(Area area) {
        PathIterator pi = area.getPathIterator(null, 1e-6);
        double total = 0;
        double startX = 0, startY = 0, prevX = 0, prevY = 0;
        double acc = 0;
        boolean open = false;
        double[] c = new double[6];
        while (!pi.isDone()) {
            int type = pi.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                if (open) total += Math.abs(acc) / 2.0;
                startX = prevX = c[0]; startY = prevY = c[1];
                acc = 0; open = true;
            } else if (type == PathIterator.SEG_LINETO) {
                acc += prevX * c[1] - c[0] * prevY;
                prevX = c[0]; prevY = c[1];
            } else if (type == PathIterator.SEG_CLOSE) {
                acc += prevX * startY - startX * prevY;
                total += Math.abs(acc) / 2.0;
                open = false;
            }
            pi.next();
        }
        if (open) total += Math.abs(acc) / 2.0;
        return total;
    }

    private static List<List<Point2D>> subpaths(Area area) {
        List<List<Point2D>> subpaths = new ArrayList<>();
        List<Point2D> current = null;
        Point2D m = null, q = new Point2D.Double(Double.MAX_VALUE, Double.MAX_VALUE);
        PathIterator pi = area.getPathIterator(null, 1e-6);
        double[] coords = new double[6];
        while (!pi.isDone()) {
            int type = pi.currentSegment(coords);
            Point2D p = new Point2D.Double(coords[0], coords[1]);
            if (type == PathIterator.SEG_MOVETO) {
                current = new ArrayList<>();
                subpaths.add(current);
                m = p;
                current.add(m);
                q = new Point2D.Double(Double.MAX_VALUE, Double.MAX_VALUE);
            } else if (p.distance(q) > 1e-9) {
                if (type == PathIterator.SEG_LINETO) {
                    current.add(p);
                    q = p;
                } else if (type == PathIterator.SEG_CLOSE) {
                    current.add(new Point2D.Double(m.getX(), m.getY()));
                }
            }
            pi.next();
        }
        return subpaths;
    }
}
