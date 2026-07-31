package purelandgen.geom;

import org.junit.Test;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class GeomUtilTest {

    private static final double DELTA = 1e-6;

    // ─── helpers ─────────────────────────────────────────────────────────────

    static Face makeSquare(double x, double y, double w, double h) {
        Vertex v0 = new Vertex(x,     y);     v0.index = 0;
        Vertex v1 = new Vertex(x + w, y);     v1.index = 1;
        Vertex v2 = new Vertex(x + w, y + h); v2.index = 2;
        Vertex v3 = new Vertex(x,     y + h); v3.index = 3;
        return new Face(new Vertex[]{v0, v1, v2, v3});
    }

    static Halfedge findHalfedge(Face f, Vec2 start, Vec2 end) {
        Halfedge he = f.halfedge;
        do {
            if (he.vertex.p.epsilonEqual(start) && he.next.vertex.p.epsilonEqual(end))
                return he;
            he = he.next;
        } while (he != f.halfedge);
        return null;
    }

    // 共有辺でペアを張った左右2面を返す（上位レイヤ=left として使う）
    // left: (0,0)-(1,0)-(1,1)-(0,1), right: (1,0)-(2,0)-(2,1)-(1,1)
    static Face[] makePairedSquares() {
        Vertex lv0 = new Vertex(0, 0); lv0.index = 0;
        Vertex lv1 = new Vertex(1, 0); lv1.index = 1;
        Vertex lv2 = new Vertex(1, 1); lv2.index = 2;
        Vertex lv3 = new Vertex(0, 1); lv3.index = 3;
        Face left = new Face(new Vertex[]{lv0, lv1, lv2, lv3});

        Vertex rv0 = new Vertex(1, 0); rv0.index = 1;
        Vertex rv1 = new Vertex(2, 0); rv1.index = 4;
        Vertex rv2 = new Vertex(2, 1); rv2.index = 5;
        Vertex rv3 = new Vertex(1, 1); rv3.index = 2;
        Face right = new Face(new Vertex[]{rv0, rv1, rv2, rv3});

        Halfedge h1 = findHalfedge(left,  new Vec2(1, 0), new Vec2(1, 1));
        Halfedge h2 = findHalfedge(right, new Vec2(1, 1), new Vec2(1, 0));
        assertNotNull(h1); assertNotNull(h2);
        h1.pair = h2;
        h2.pair = h1;

        return new Face[]{left, right};
    }

    // ─── isPointOnSegment ────────────────────────────────────────────────────

    @Test
    public void testIsPointOnSegment_interior() {
        assertTrue(GeomUtil.isPointOnSegment(
                new Vec2(0.5, 0.5), new Vec2(0, 0), new Vec2(1, 1)));
    }

    @Test
    public void testIsPointOnSegment_atStartEndpoint() {
        assertTrue(GeomUtil.isPointOnSegment(
                new Vec2(0, 0), new Vec2(0, 0), new Vec2(1, 0)));
    }

    @Test
    public void testIsPointOnSegment_atEndEndpoint() {
        assertTrue(GeomUtil.isPointOnSegment(
                new Vec2(1, 0), new Vec2(0, 0), new Vec2(1, 0)));
    }

    @Test
    public void testIsPointOnSegment_beyondEnd() {
        assertFalse(GeomUtil.isPointOnSegment(
                new Vec2(2, 2), new Vec2(0, 0), new Vec2(1, 1)));
    }

    @Test
    public void testIsPointOnSegment_offLine() {
        assertFalse(GeomUtil.isPointOnSegment(
                new Vec2(0.5, 0.1), new Vec2(0, 0), new Vec2(1, 0)));
    }

    @Test
    public void testIsPointOnSegment_horizontalEdge() {
        Vec2 sp = new Vec2(0, 0), ep = new Vec2(1, 0);
        assertTrue(GeomUtil.isPointOnSegment(new Vec2(0.3, 0), sp, ep));
        assertFalse(GeomUtil.isPointOnSegment(new Vec2(1.1, 0), sp, ep));
    }

    @Test
    public void testIsPointOnSegment_verticalEdge() {
        Vec2 sp = new Vec2(0, 0), ep = new Vec2(0, 1);
        assertTrue(GeomUtil.isPointOnSegment(new Vec2(0, 0.7), sp, ep));
        assertFalse(GeomUtil.isPointOnSegment(new Vec2(0, -0.1), sp, ep));
    }

    // ─── isPointOnFaceEdge ───────────────────────────────────────────────────

    @Test
    public void testIsPointOnFaceEdge_midEdge() {
        Face f = makeSquare(0, 0, 1, 1);
        assertTrue(GeomUtil.isPointOnFaceEdge(new Vec2(0.5, 0), f, true));
    }

    @Test
    public void testIsPointOnFaceEdge_corner_include() {
        Face f = makeSquare(0, 0, 1, 1);
        assertTrue(GeomUtil.isPointOnFaceEdge(new Vec2(0, 0), f, true));
    }

    @Test
    public void testIsPointOnFaceEdge_corner_exclude() {
        Face f = makeSquare(0, 0, 1, 1);
        assertFalse(GeomUtil.isPointOnFaceEdge(new Vec2(0, 0), f, false));
    }

    @Test
    public void testIsPointOnFaceEdge_interior() {
        Face f = makeSquare(0, 0, 1, 1);
        assertFalse(GeomUtil.isPointOnFaceEdge(new Vec2(0.5, 0.5), f, true));
    }

    @Test
    public void testIsPointOnFaceEdge_outside() {
        Face f = makeSquare(0, 0, 1, 1);
        assertFalse(GeomUtil.isPointOnFaceEdge(new Vec2(2, 0.5), f, true));
    }

    // ─── isPointStrictlyInsideFace ───────────────────────────────────────────

    @Test
    public void testIsPointStrictlyInsideFace_inside() {
        Face f = makeSquare(0, 0, 1, 1);
        assertTrue(GeomUtil.isPointStrictlyInsideFace(new Vec2(0.5, 0.5), f));
    }

    @Test
    public void testIsPointStrictlyInsideFace_onEdge() {
        Face f = makeSquare(0, 0, 1, 1);
        assertFalse(GeomUtil.isPointStrictlyInsideFace(new Vec2(0.5, 0), f));
    }

    @Test
    public void testIsPointStrictlyInsideFace_atCorner() {
        Face f = makeSquare(0, 0, 1, 1);
        assertFalse(GeomUtil.isPointStrictlyInsideFace(new Vec2(0, 0), f));
    }

    @Test
    public void testIsPointStrictlyInsideFace_outside() {
        Face f = makeSquare(0, 0, 1, 1);
        assertFalse(GeomUtil.isPointStrictlyInsideFace(new Vec2(2, 0.5), f));
    }

    // ─── isPointHiddenByFace ─────────────────────────────────────────────────

    @Test
    public void testIsPointHiddenByFace_inside() {
        Face f = makeSquare(0, 0, 1, 1);
        assertTrue(GeomUtil.isPointHiddenByFace(new Vec2(0.5, 0.5), f));
    }

    @Test
    public void testIsPointHiddenByFace_onUnpairedEdge() {
        Face f = makeSquare(0, 0, 1, 1); // no pairs → all boundary
        assertFalse(GeomUtil.isPointHiddenByFace(new Vec2(0.5, 0), f));
    }

    @Test
    public void testIsPointHiddenByFace_onPairedEdge_interior() {
        Face left = makePairedSquares()[0];
        // (1, 0.5) は left の paired辺の内側 → 不可視
        assertTrue(GeomUtil.isPointHiddenByFace(new Vec2(1, 0.5), left));
    }

    @Test
    public void testIsPointHiddenByFace_onPairedEdge_endpoint() {
        Face left = makePairedSquares()[0];
        // 端点は可視
        assertFalse(GeomUtil.isPointHiddenByFace(new Vec2(1, 0), left));
        assertFalse(GeomUtil.isPointHiddenByFace(new Vec2(1, 1), left));
    }

    // ─── isPointVisible ──────────────────────────────────────────────────────

    @Test
    public void testIsPointVisible_noLayers() {
        assertTrue(GeomUtil.isPointVisible(new Vec2(0.5, 0.5), new ArrayList<>()));
    }

    @Test
    public void testIsPointVisible_hiddenByLayer() {
        Face f = makeSquare(0, 0, 1, 1);
        ArrayList<Face> layers = new ArrayList<>();
        layers.add(f);
        assertFalse(GeomUtil.isPointVisible(new Vec2(0.5, 0.5), layers));
    }

    @Test
    public void testIsPointVisible_outsideLayer() {
        Face f = makeSquare(0, 0, 1, 1);
        ArrayList<Face> layers = new ArrayList<>();
        layers.add(f);
        assertTrue(GeomUtil.isPointVisible(new Vec2(2, 0.5), layers));
    }

    // ─── getVisibleSegments / isSegmentPartlyVisible ─────────────────────────

    @Test
    public void testGetVisibleSegments_noLayers() {
        ArrayList<double[]> segs = GeomUtil.getVisibleSegments(
                new Vec2(0, 0), new Vec2(1, 0), new ArrayList<>());
        assertEquals(1, segs.size());
        assertEquals(0.0, segs.get(0)[0], DELTA);
        assertEquals(1.0, segs.get(0)[1], DELTA);
    }

    @Test
    public void testGetVisibleSegments_fullyHidden() {
        Face f = makeSquare(0, 0, 1, 1);
        ArrayList<Face> layers = new ArrayList<>();
        layers.add(f);
        ArrayList<double[]> segs = GeomUtil.getVisibleSegments(
                new Vec2(0.2, 0.5), new Vec2(0.8, 0.5), layers);
        assertTrue(segs.isEmpty());
    }

    @Test
    public void testGetVisibleSegments_halfHidden() {
        // (-0.5, 0.5) → (0.5, 0.5): 右半分が単位正方形の内側
        Face f = makeSquare(0, 0, 1, 1);
        ArrayList<Face> layers = new ArrayList<>();
        layers.add(f);
        ArrayList<double[]> segs = GeomUtil.getVisibleSegments(
                new Vec2(-0.5, 0.5), new Vec2(0.5, 0.5), layers);
        assertEquals(1, segs.size());
        assertEquals(0.0, segs.get(0)[0], DELTA);
        assertEquals(0.5, segs.get(0)[1], 0.01);
    }

    @Test
    public void testGetVisibleSegments_fullyOutside() {
        Face f = makeSquare(0, 0, 1, 1);
        ArrayList<Face> layers = new ArrayList<>();
        layers.add(f);
        ArrayList<double[]> segs = GeomUtil.getVisibleSegments(
                new Vec2(2, 0.5), new Vec2(3, 0.5), layers);
        assertEquals(1, segs.size());
    }

    @Test
    public void testIsSegmentPartlyVisible_fullyHidden() {
        Face f = makeSquare(0, 0, 1, 1);
        ArrayList<Face> layers = new ArrayList<>();
        layers.add(f);
        assertFalse(GeomUtil.isSegmentPartlyVisible(
                new Vec2(0.2, 0.5), new Vec2(0.8, 0.5), layers));
    }

    @Test
    public void testIsSegmentPartlyVisible_partlyVisible() {
        Face f = makeSquare(0, 0, 1, 1);
        ArrayList<Face> layers = new ArrayList<>();
        layers.add(f);
        assertTrue(GeomUtil.isSegmentPartlyVisible(
                new Vec2(-0.5, 0.5), new Vec2(0.5, 0.5), layers));
    }
}
