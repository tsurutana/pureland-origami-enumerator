package purelandgen.geom;

public class Halfedge {
    public Vertex vertex;
    public Face face;
    public Halfedge next;
    public Halfedge prev;
    public Halfedge pair;
    public Edge edge;
    public boolean isCrease = false; // true for halfedges created by Face.split() along the fold line

    Halfedge(Vertex v, Face f) {
        vertex = v;
        v.halfedge = this;
        face = f;
    }

    Halfedge(Halfedge he) {
        if (he.vertex.copyTo == null)
            throw new IllegalStateException("copyTo is null: vertex must be copied before halfedge");
        vertex = he.vertex.copyTo;
        vertex.halfedge = this;
    }

    // returns true if the paired halfedge was also split
    boolean split(Vertex v) {
        Halfedge he_newA = new Halfedge(v, face);
        he_newA.prev = this;
        he_newA.next = next;
        next.prev = he_newA;
        next = he_newA;

        face.halfedge = he_newA;
        if (hasPair()) {
            Halfedge he_newB = new Halfedge(v, pair.face);
            v.halfedge = he_newA; // restore: he_newB constructor overwrote v.halfedge
            he_newB.prev = pair;
            he_newB.next = pair.next;
            pair.next.prev = he_newB;
            pair.next = he_newB;

            he_newA.pair = pair;
            pair.pair = he_newA;
            he_newB.pair = this;
            pair = he_newB;

            pair.face.halfedge = he_newB;
            return true;
        }
        return false;
    }

    boolean hasPair() {
        return pair != null;
    }
}
