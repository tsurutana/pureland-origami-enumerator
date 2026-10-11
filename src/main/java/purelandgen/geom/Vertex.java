package purelandgen.geom;

public class Vertex {

    public Vec2 p;
    public Halfedge halfedge;
    public int index = 0;
    public boolean isMovable = true;
    public Vertex copyTo;

    /** Position on the original, never-folded flat square (see ExactStateKey / rebuttal/A2_exact_key_spec.md).
     *  Defaults to p at construction time -- correct for the initial square's corners, and a documented
     *  best-effort fallback for vertices whose fold lineage is unknown (FoldFormat-loaded figures,
     *  OrigamiConstructions' scratch floating vertices). Vertices created by splitting an edge during an
     *  actual fold (GeomUtil.getCrossPointByHalfedge) overwrite this via linear interpolation of the two
     *  endpoints' origP, using the same parameter as the folded-space interpolation -- valid because a
     *  face's folded<->original mapping is an isometry, so it preserves the ratio along any straight edge. */
    public Vec2 origP;

    /** Set only on a floating intersection vertex scratch-created by
     *  OrigamiConstructions._addIntersections() (null for every other vertex, including the
     *  figure's real corners/mesh vertices). Holds the (up to) two existing-figure edges whose
     *  crossing produced this point, each as a {start,end} Vec2 pair, so that a landmark built
     *  from this vertex (FoldingLine.p0/p1SourceEdges, landmark0/1SourceEdges) can later check its
     *  own visibility against those SPECIFIC edges instead of re-scanning the whole figure for
     *  whatever edge happens to pass through the same coordinate -- see the mountain-fold
     *  visibility investigation (an unrelated edge coinciding with this point, e.g. a crease of a
     *  totally different fold, must never affect this point's own visibility determination). */
    Vec2[][] sourceEdges;

    Vertex(double x, double y) {
        p = new Vec2(x, y);
        origP = new Vec2(x, y);
    }

    // NOTE: sets v.copyTo = this as a side effect.
    // Figure's copy constructor relies on this to relink halfedges to copied vertices.
    // Caller must clear copyTo (set to null) after all vertices are copied.
    Vertex(Vertex v) {
        p = new Vec2(v.p);
        origP = new Vec2(v.origP);
        index = v.index;
        isMovable = v.isMovable;
        sourceEdges = v.sourceEdges;
        v.copyTo = this;
    }

    // Simple local foldability check: True on edge. True on odd number ridge lines
    boolean isFlatFoldable() {
        Halfedge he = this.halfedge;
        int count = 0;
        do {
            if (!he.hasPair())
                return true; // 紙のフチ
            he = he.pair.next;
            count++;
        } while (he != this.halfedge);
        return (count % 2) == 0;
    }
}
