package purelandgen.geom;

public class Edge {

    Vertex sv;
    Vertex ev;
    Halfedge left;
    Halfedge right;

    Edge(Vertex v1, Vertex v2) {
        if (v1.index < v2.index) {
            sv = v1;
            ev = v2;
        } else {
            sv = v2;
            ev = v1;
        }
    }
}
