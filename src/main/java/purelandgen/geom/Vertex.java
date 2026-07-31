package purelandgen.geom;

public class Vertex {

    public Vec2 p;
    public Halfedge halfedge;
    public int index = 0;
    public boolean isMovable = true;
    public Vertex copyTo;

    Vertex(double x, double y) {
        p = new Vec2(x, y);
    }

    // NOTE: sets v.copyTo = this as a side effect.
    // Figure's copy constructor relies on this to relink halfedges to copied vertices.
    // Caller must clear copyTo (set to null) after all vertices are copied.
    Vertex(Vertex v) {
        p = new Vec2(v.p);
        index = v.index;
        isMovable = v.isMovable;
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
