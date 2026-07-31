package purelandgen.geom;

import java.util.ArrayList;
import java.util.Collections;

public class GeomUtil {

    static final double EPS = 1.0e-8;

    // Is p between s and e;
    static boolean isRange(double p, double s, double e) {
        return Math.min(s, e) <= p && p <= Math.max(s, e);
    }
    /** Bounding-box check only. Does NOT verify collinearity. */
    public static boolean isRange( Vec2 q, Vec2 p0, Vec2 p1 ) {
        return isRange(q.x, p0.x,p1.x) && isRange(q.y, p0.y,p1.y);
    }
    public static boolean isRangeStrict(Vec2 q, Vec2 p0, Vec2 p1) {
        double minX = Math.min(p0.x, p1.x), maxX = Math.max(p0.x, p1.x);
        double minY = Math.min(p0.y, p1.y), maxY = Math.max(p0.y, p1.y);
        boolean inX = (maxX - minX < EPS) ? Math.abs(q.x - minX) < EPS : minX < q.x && q.x < maxX;
        boolean inY = (maxY - minY < EPS) ? Math.abs(q.y - minY) < EPS : minY < q.y && q.y < maxY;
        return inX && inY;
    }

    // Centroid
    static Vec2 center(ArrayList<Face> faces) {
        if (faces.isEmpty()) {
            System.err.println("GeomUtil.center: faces is empty");
            return new Vec2();
        }
        Vec2 center = new Vec2();
        int total = 0;
        for (Face f : faces) {
            Halfedge begin = f.halfedge;
            Halfedge he = begin;
            int count = 0;
            do {
                center.add(he.vertex.p);
                he = he.next;
                count++;
            } while (he != begin);
            total += count;
        }
        center.scale(1.0 / total);
        return center;
    }

    static void setMidPoint(Vec2 p0, Vec2 p1, Vec2 mid) {
        mid.set((p0.x + p1.x) * 0.5, (p0.y + p1.y) * 0.5);
    }

    static void setBisectorVec(Vec2 p0, Vec2 p1, Vec2 p2, Vec2 ret) {
        Vec2 tmp0 = new Vec2(p0.x - p1.x, p0.y - p1.y);
        tmp0.normalize();
        Vec2 tmp1 = new Vec2(p2.x - p1.x, p2.y - p1.y);
        tmp1.normalize();
        ret.set(tmp0.x + tmp1.x, tmp0.y + tmp1.y);
        ret.add(p1);
    }

    // Whether the face is counter-clockwise relative to the line
    static boolean CCWcheck(Vec2 p0, Vec2 p1, Vec2 q) {
        double dx1, dx2, dy1, dy2;
        dx1 = p1.x - p0.x;
        dy1 = p1.y - p0.y;
        dx2 = q.x - p0.x;
        dy2 = q.y - p0.y;
        return dx1 * dy2 > dy1 * dx2;
    }

    // Whether the face is counter-clockwise relative to the line
    static boolean CCWcheckByFaceCenter(Vec2 p0, Vec2 p1, Face f) {
        Vec2 tmp0 = new Vec2();
        Halfedge he = f.halfedge;
        int count = 0;
        do {
            count++;
            tmp0.add(he.vertex.p);
            he = he.next;
        } while (he != f.halfedge);
        tmp0.scale(1.0 / count);

        return CCWcheck(p0, p1, tmp0);
    }

    static boolean isOnSameLine(Vec2 p0, Vec2 p1, Vec2 q0, Vec2 q1) {
        if (distancePointToLine(p0, q0, q1) < EPS &&
                distancePointToLine(p1, q0, q1) < EPS) {
            return true;
        }
        return false;
    }

    // Intersection point of two segments (excluding endpoints)
    static Vec2 getCrossPointBySegments(
            Vec2 p0, Vec2 p1, Vec2 q0, Vec2 q1) {
        Vec2 tmp0 = new Vec2(p1.x - p0.x, p1.y - p0.y);
        Vec2 tmp1 = new Vec2(q1.x - q0.x, q1.y - q0.y);
        Vec2 tmp2 = new Vec2(q0.x - p0.x, q0.y - p0.y);
        final double det = tmp1.cross(tmp0);

        if (det * det > EPS * tmp0.lengthSquared() * tmp1.lengthSquared()) {
            final double invDet = 1.0 / det;
            final double s = tmp1.cross(tmp2) * invDet;
            final double t = tmp0.cross(tmp2) * invDet;

            if (t < 0.0 + EPS || t > 1.0 - EPS) {
                return null;
            } else if (s < 0.0 + EPS || s > 1.0 - EPS) {
                return null;
            } else {
                Vec2 cp = new Vec2(
                        (1.0 - t) * q0.x + t * q1.x, (1.0 - t) * q0.y + t * q1.y);
                return cp;
            }
        }
        return null;
    }

    // Intersection test between a segment and a line (returns only the point on the halfedge's start side)
    static Vertex getCrossPointByHalfedge(Halfedge he, Vec2 q0, Vec2 q1) {
        Vec2 sp = he.vertex.p;
        Vec2 ep = he.next.vertex.p;
        Vec2 tmp0 = new Vec2(ep.x - sp.x, ep.y - sp.y);
        Vec2 tmp1 = new Vec2(q1.x - q0.x, q1.y - q0.y);
        Vec2 tmp2 = new Vec2(q0.x - sp.x, q0.y - sp.y);

        final double det = tmp1.cross(tmp0);
        if (det * det > EPS * tmp0.lengthSquared() * tmp1.lengthSquared()) {
            final double invDet = 1.0 / det;
            final double s = tmp1.cross(tmp2) * invDet;
            final double t = tmp0.cross(tmp2) * invDet;
            if (s < 0.0 - EPS || s > 1.0 + EPS) {
                return null;
            } else {
                if (s < EPS) {
                    he.vertex.halfedge = he;
                    return he.vertex;
                }
                if (s > 1.0 - EPS) {
                    return null; // null at the end point; should be caught by halfedge.next
                }
                final double x = (1.0 - t) * q0.x + t * q1.x;
                final double y = (1.0 - t) * q0.y + t * q1.y;
                Vertex ret = new Vertex(x, y);
                ret.index = -1; // Mark that this is a new point
                ret.halfedge = he;
                return ret;
            }
        }
        return null;
    }

    // Intersection points of a face and a line
    static ArrayList<Vertex> getCrossPoints(Face f, Vec2 p0, Vec2 p1) {
        ArrayList<Vertex> ret = new ArrayList<>();
        Halfedge begin = f.halfedge;
        Halfedge he = begin;
        do {
            Vertex cross = getCrossPointByHalfedge(he, p0, p1);
            if (cross != null) {
                ret.add(cross);
            }
            he = he.next;
        } while (he != begin);
        return ret;
    }

    static void setCrossPointByLines(
            Vec2 p0, Vec2 p1, Vec2 q0, Vec2 q1, Vec2 cross) {
        Vec2 tmp0 = new Vec2(p1.x - p0.x, p1.y - p0.y);
        Vec2 tmp1 = new Vec2(q1.x - q0.x, q1.y - q0.y);
        Vec2 tmp2 = new Vec2(q0.x - p0.x, q0.y - p0.y);
        final double det = tmp1.cross(tmp0);

        if (det * det > EPS * tmp0.lengthSquared() * tmp1.lengthSquared()) {
            final double invDet = 1.0 / det;
            final double t = tmp0.cross(tmp2) * invDet;

            cross.x = (1.0 - t) * q0.x + t * q1.x;
            cross.y = (1.0 - t) * q0.y + t * q1.y;
        } else {
            // set NaN when lines are parallel
            cross.x = cross.y = Double.NaN;
        }
    }

    static void setPerpendicularBisector(
            Vec2 p, Vec2 q, Vec2 ret1, Vec2 ret2) {
        final double px = p.x;
        final double py = p.y;
        final double qx = q.x;
        final double qy = q.y;

        final double midx = (px + qx) * 0.5;
        final double midy = (py + qy) * 0.5;
        ret1.set(midx + (qy - midy), midy - (qx - midx));
        ret2.set(midx - (qy - midy), midy + (qx - midx));
    }

    // Find the point on the line closest to the given point
    static Vec2 getNearestPointToLine(Vec2 p, Vec2 sp, Vec2 ep) {
        final double x0 = sp.x;
        final double y0 = sp.y;
        final double x1 = ep.x;
        final double y1 = ep.y;
        final double px = p.x;
        final double py = p.y;

        Vec2 tmp1 = new Vec2(px - x0, py - y0);
        Vec2 tmp2 = new Vec2(x1 - x0, y1 - y0);

        final double denom = (tmp2.x * tmp2.x) + (tmp2.y * tmp2.y);
        if (denom < EPS * EPS) return new Vec2(sp);
        final double r = ((tmp2.x * tmp1.x) + (tmp2.y * tmp1.y)) / denom;

        return new Vec2(x0 + r * tmp2.x, y0 + r * tmp2.y);
    }

    static double distancePointToLine(Vec2 p, Vec2 sp, Vec2 ep) {
        Vec2 np = getNearestPointToLine(p, sp, ep);
        return p.distanceTo(np);
    }

    static boolean isPointOnSegment(Vec2 p, Vec2 sp, Vec2 ep) {
        return distancePointToLine(p, sp, ep) < EPS && isRange(p, sp, ep);
    }

    // Whether the point is hidden by the upper layer
    // Hidden if: inside the face, OR inside a paired edge (excluding endpoints)
    static boolean isPointHiddenByFace(Vec2 p, Face upperFace) {
        if (isPointStrictlyInsideFace(p, upperFace)) return true;
        Halfedge he = upperFace.halfedge;
        do {
            if (he.hasPair()) {
                Vec2 sp = he.vertex.p;
                Vec2 ep = he.next.vertex.p;
                if (isPointOnSegment(p, sp, ep) && !p.epsilonEqual(sp) && !p.epsilonEqual(ep))
                    return true;
            }
            he = he.next;
        } while (he != upperFace.halfedge);
        return false;
    }

    // Returns the list of visible intervals of the segment (as t in [0,1] intervals)
    static ArrayList<double[]> getVisibleSegments(Vec2 sp, Vec2 ep, ArrayList<Face> upperLayers) {
        ArrayList<double[]> visible = new ArrayList<>();
        visible.add(new double[]{0.0, 1.0});
        for (Face f : upperLayers) {
            visible = _subtractHiddenByFace(sp, ep, f, visible);
            if (visible.isEmpty()) break;
        }
        return visible;
    }

    static boolean isSegmentPartlyVisible(Vec2 sp, Vec2 ep, ArrayList<Face> upperLayers) {
        return !getVisibleSegments(sp, ep, upperLayers).isEmpty();
    }

    private static ArrayList<double[]> _subtractHiddenByFace(
            Vec2 sp, Vec2 ep, Face f, ArrayList<double[]> intervals) {
        // Collect the t parameters of intersections between the segment and f's edges
        ArrayList<Double> cuts = new ArrayList<>();
        final double dx = ep.x - sp.x, dy = ep.y - sp.y;
        Halfedge he = f.halfedge;
        do {
            Vec2 fsp = he.vertex.p;
            Vec2 fep = he.next.vertex.p;
            double ex = fep.x - fsp.x, ey = fep.y - fsp.y;
            double det = ex * dy - ey * dx;
            if (det * det > EPS * (dx*dx+dy*dy) * (ex*ex+ey*ey)) {
                double fx = fsp.x - sp.x, fy = fsp.y - sp.y;
                double t = (ex * fy - ey * fx) / det;
                double s = (dx * fy - dy * fx) / det;
                if (t > EPS && t < 1.0 - EPS && s >= -EPS && s <= 1.0 + EPS)
                    cuts.add(Math.max(0.0, Math.min(1.0, t)));
            }
            he = he.next;
        } while (he != f.halfedge);
        Collections.sort(cuts);

        ArrayList<double[]> result = new ArrayList<>();
        for (double[] iv : intervals) {
            double prev = iv[0];
            for (double t : cuts) {
                if (t <= iv[0] + EPS) continue;
                if (t >= iv[1] - EPS) break;
                _addIfVisible(sp, ep, prev, t, f, result);
                prev = t;
            }
            _addIfVisible(sp, ep, prev, iv[1], f, result);
        }
        return result;
    }

    private static void _addIfVisible(
            Vec2 sp, Vec2 ep, double t0, double t1, Face f, ArrayList<double[]> result) {
        if (t1 - t0 < EPS) return;
        double mt = (t0 + t1) * 0.5;
        Vec2 mid = new Vec2(sp.x + mt * (ep.x - sp.x), sp.y + mt * (ep.y - sp.y));
        if (!isPointHiddenByFace(mid, f)) result.add(new double[]{t0, t1});
    }

    public static boolean isPointVisible(Vec2 p, ArrayList<Face> upperLayers) {
        for (Face f : upperLayers) {
            if (isPointHiddenByFace(p, f)) return false;
        }
        return true;
    }

    // Whether the point is strictly inside the polygon (false if on an edge or vertex)
    static boolean isPointStrictlyInsideFace(Vec2 p, Face f) {
        if (isPointOnFaceEdge(p, f, true)) return false;
        int crossings = 0;
        Halfedge he = f.halfedge;
        do {
            Vec2 sp = he.vertex.p;
            Vec2 ep = he.next.vertex.p;
            if ((sp.y <= p.y && ep.y > p.y) || (ep.y <= p.y && sp.y > p.y)) {
                double t = (p.y - sp.y) / (ep.y - sp.y);
                if (p.x < sp.x + t * (ep.x - sp.x)) {
                    crossings++;
                }
            }
            he = he.next;
        } while (he != f.halfedge);
        return (crossings % 2) == 1;
    }

    static boolean isPointOnFaceEdge(Vec2 p, Face f, boolean includeEndpoints) {
        Halfedge he = f.halfedge;
        do {
            Vec2 sp = he.vertex.p;
            Vec2 ep = he.next.vertex.p;
            if (isPointOnSegment(p, sp, ep)) {
                if (includeEndpoints) return true;
                if (!p.epsilonEqual(sp) && !p.epsilonEqual(ep)) return true;
            }
            he = he.next;
        } while (he != f.halfedge);
        return false;
    }

    // Move the point to its mirrored position across the line
    static void moveVectorToOpposite(Vec2 p, Vec2 sp, Vec2 ep) {
        Vec2 dir = getNearestPointToLine(p, sp, ep);
        dir.sub(p);
        dir.scale(2.0);
        p.add(dir);
    }

    static void moveVertexToOpposite(Vertex v, Vec2 sp, Vec2 ep) {
        if (!v.isMovable) return;
        moveVectorToOpposite(v.p, sp, ep);
        v.isMovable = false;
    }

    static void moveFaceToOpposite(Face f, Vec2 sp, Vec2 ep) {
        Halfedge he = f.halfedge;
        do {
            moveVertexToOpposite(he.vertex, sp, ep);
            he = he.next;
        } while (he != f.halfedge);
        f.isFront = !f.isFront;
    }

    // ─── Half-plane test ──────────────────────────────────────────────────────

    /** Whether p lies in the CCW-side half-plane of the crease line (lineP0->lineP1) (useCCW=true for CCW, false for CW).
     *  Points on the boundary (cross ~ 0) are included on both sides. */
    public static boolean isOnHalfPlane(Vec2 p, Vec2 lineP0, Vec2 lineP1, boolean useCCW) {
        double dx = lineP1.x - lineP0.x, dy = lineP1.y - lineP0.y;
        double cross = dx * (p.y - lineP0.y) - dy * (p.x - lineP0.x);
        return useCCW ? cross >= -EPS : cross <= EPS;
    }

    // ─── Polygon clipping (Sutherland-Hodgman) ────────────────────────────────

    /** Clips the face by the crease line's half-plane and returns the resulting vertex list. */
    public static ArrayList<Vec2> clipPolygon(Face face, Vec2 lineP0, Vec2 lineP1, boolean useCCW) {
        ArrayList<Vec2> input = new ArrayList<>();
        Halfedge he = face.halfedge;
        do { input.add(he.vertex.p); he = he.next; } while (he != face.halfedge);
        ArrayList<Vec2> output = new ArrayList<>();
        int n = input.size();
        for (int i = 0; i < n; i++) {
            Vec2 curr = input.get(i), next = input.get((i + 1) % n);
            boolean currIn = isOnHalfPlane(curr, lineP0, lineP1, useCCW);
            boolean nextIn = isOnHalfPlane(next, lineP0, lineP1, useCCW);
            if (currIn) {
                output.add(curr);
                if (!nextIn) { Vec2 ip = _segLineIntersect(curr, next, lineP0, lineP1); if (ip != null) output.add(ip); }
            } else if (nextIn) {
                Vec2 ip = _segLineIntersect(curr, next, lineP0, lineP1); if (ip != null) output.add(ip);
            }
        }
        return output;
    }

    /** Intersection of segment p0-p1 with line lp0-lp1 (the line is treated as infinite). Returns null if parallel. */
    private static Vec2 _segLineIntersect(Vec2 p0, Vec2 p1, Vec2 lp0, Vec2 lp1) {
        double dx1 = p1.x - p0.x, dy1 = p1.y - p0.y;
        double dx2 = lp1.x - lp0.x, dy2 = lp1.y - lp0.y;
        double denom = dx1 * dy2 - dy1 * dx2;
        if (Math.abs(denom) < EPS) return null;
        double t = ((lp0.x - p0.x) * dy2 - (lp0.y - p0.y) * dx2) / denom;
        return new Vec2(p0.x + t * dx1, p0.y + t * dy1);
    }

    // ─── Polygon area / centroid / overlap ───────────────────────────────────

    /** Polygon area (shoelace formula). Returns 0 when the vertex count is less than 3. */
    public static double polygonArea(ArrayList<Vec2> poly) {
        if (poly.size() < 3) return 0;
        double area = 0;
        for (int i = 0, n = poly.size(); i < n; i++) {
            Vec2 a = poly.get(i), b = poly.get((i + 1) % n);
            area += a.x * b.y - b.x * a.y;
        }
        return Math.abs(area) * 0.5;
    }

    /** Polygon centroid (simple average of the vertices). */
    public static Vec2 centroid(ArrayList<Vec2> poly) {
        double sx = 0, sy = 0;
        for (Vec2 v : poly) { sx += v.x; sy += v.y; }
        return new Vec2(sx / poly.size(), sy / poly.size());
    }

    /** Whether point p is inside the polygon or on its edge (ray casting + on-edge check). */
    public static boolean isPointInOrOnPolygon(Vec2 p, ArrayList<Vec2> polygon) {
        int n = polygon.size();
        for (int i = 0; i < n; i++)
            if (isPointOnSegment(p, polygon.get(i), polygon.get((i + 1) % n))) return true;
        int crossings = 0;
        for (int i = 0; i < n; i++) {
            Vec2 sp = polygon.get(i), ep = polygon.get((i + 1) % n);
            if ((sp.y <= p.y && ep.y > p.y) || (ep.y <= p.y && sp.y > p.y)) {
                double t = (p.y - sp.y) / (ep.y - sp.y);
                if (p.x < sp.x + t * (ep.x - sp.x)) crossings++;
            }
        }
        return (crossings & 1) == 1;
    }

    // Inradius threshold corresponding to 1mm on a 150mm square paper: 1mm = 4/3 internal units, A/P ~ w/2 -> threshold = (4/3)/2 = 2/3
    private static final double OVERLAP_INRADIUS_THRESHOLD = 2.0 / 3.0;

    /** Whether two polygons overlap with nonzero area (determined via the inradius approximation of the
     *  Sutherland-Hodgman intersection polygon).
     *  Correctly returns false for cases of endpoint-only contact, such as shared vertices or shared edges.
     *  The threshold uses the inradius approximation (A/P) > 2/3, equivalent to 1mm (150mm paper, internal 200-unit system). */
    public static boolean polygonsOverlap(ArrayList<Vec2> poly1, ArrayList<Vec2> poly2) {
        if (poly1.isEmpty() || poly2.isEmpty()) return false;
        ArrayList<Vec2> inter = _intersectionPolygon(poly1, poly2);
        if (inter.size() < 3) return false;
        double area = polygonArea(inter);
        double perimeter = 0;
        for (int i = 0; i < inter.size(); i++) {
            Vec2 a = inter.get(i), b = inter.get((i + 1) % inter.size());
            double dx = b.x - a.x, dy = b.y - a.y;
            perimeter += Math.sqrt(dx * dx + dy * dy);
        }
        return perimeter > EPS && area / perimeter > OVERLAP_INRADIUS_THRESHOLD;
    }

    /** Returns the intersection polygon obtained by clipping poly1 against all of poly2's half-planes using
     *  Sutherland-Hodgman. Automatically detects poly2's orientation (CCW/CW) to select which half-plane to use. */
    private static ArrayList<Vec2> _intersectionPolygon(ArrayList<Vec2> poly1, ArrayList<Vec2> poly2) {
        int n2 = poly2.size();
        if (n2 < 3) return new ArrayList<>();
        double signed2 = 0;
        for (int i = 0; i < n2; i++) {
            Vec2 a = poly2.get(i), b = poly2.get((i + 1) % n2);
            signed2 += a.x * b.y - b.x * a.y;
        }
        boolean ccw2 = signed2 >= 0;
        ArrayList<Vec2> result = new ArrayList<>(poly1);
        for (int i = 0; i < n2; i++) {
            Vec2 p0 = poly2.get(i), p1 = poly2.get((i + 1) % n2);
            result = ccw2 ? _clipPolyByHalfPlane(result, p0, p1)
                          : _clipPolyByHalfPlane(result, p1, p0);
            if (result.isEmpty()) return result;
        }
        return result;
    }

    /** Clips poly against the CCW-side (left-side) half-plane of the directed edge p0->p1 and returns the result. */
    private static ArrayList<Vec2> _clipPolyByHalfPlane(ArrayList<Vec2> input, Vec2 p0, Vec2 p1) {
        ArrayList<Vec2> output = new ArrayList<>();
        int n = input.size();
        if (n == 0) return output;
        for (int i = 0; i < n; i++) {
            Vec2 curr = input.get(i), next = input.get((i + 1) % n);
            boolean currIn = isOnHalfPlane(curr, p0, p1, true);
            boolean nextIn = isOnHalfPlane(next, p0, p1, true);
            if (currIn) {
                output.add(curr);
                if (!nextIn) { Vec2 ip = _segLineIntersect(curr, next, p0, p1); if (ip != null) output.add(ip); }
            } else if (nextIn) {
                Vec2 ip = _segLineIntersect(curr, next, p0, p1); if (ip != null) output.add(ip);
            }
        }
        return output;
    }
}
