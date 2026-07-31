package purelandgen.geom;

import java.util.ArrayList;
import java.util.Arrays;

public class Face {

    public Halfedge halfedge;
    public boolean isFront = false;
    public int index = 0;
    boolean visited = false;

    Face() {
    }

    Face(Halfedge baseHe) {
        halfedge = baseHe;
        Halfedge he = baseHe;
        do {
            he.face = this;
            he = he.next;
        } while (he != baseHe);
    }

    Face(Face f) {
        ArrayList<Halfedge> helist = new ArrayList<>();
        Halfedge begin = f.halfedge;
        Halfedge he = begin;
        do {
            helist.add(new Halfedge(he));
            he = he.next;
        } while (he != begin);
        for (int i=0, n=helist.size(); i<n; i++) {
            helist.get(i).prev = helist.get((i-1)<0? n-1: i-1);
            helist.get(i).face = this;
            helist.get(i).next = helist.get((i+1)%n);
        }
        halfedge = helist.get(0);
        isFront = f.isFront;
    }

    Face(Vertex[] vlist) {
        Halfedge begin = new Halfedge(vlist[0], this);
        halfedge = begin;
        for (int i = 1; i < vlist.length; i++) {
            Halfedge he = new Halfedge(vlist[i], this);
            halfedge.next = he;
            he.prev = halfedge;
            halfedge = he;
        }
        halfedge.next = begin;
        begin.prev = halfedge;
    }

    boolean hasEdgeWith(Vertex v0, Vertex v1) {
        Halfedge he = halfedge;
        do {
            if (he.vertex == v0) {
                if (he.next.vertex == v1) {
                    return true;
                } else if (he.prev.vertex == v1) {
                    return true;
                }
            }
            he = he.next;
        } while (he != halfedge);
        return false;
    }

    public double area() {
        double sum = 0;
        Halfedge he = halfedge;
        do {
            sum += he.vertex.p.x * (he.next.vertex.p.y - he.prev.vertex.p.y);
            he = he.next;
        } while (he != halfedge);
        return 0.5 * Math.abs(sum);
    }

    public void markVerticesFixed() {
        Halfedge he = halfedge;
        do {
            he.vertex.isMovable = false;
            he = he.next;
        } while (he != halfedge);
    }

    ArrayList<Face> split(Vertex v0, Vertex v1) {
        if (v0 == v1) return null;
        // search halfedges which start at v0 and v1
        Halfedge he0 = v0.halfedge;
        Halfedge he1 = v1.halfedge;

        // create new halfedges
        Halfedge e_he0 = new Halfedge(v1, null);
        Halfedge e_he1 = new Halfedge(v0, null);
        e_he0.next = he0;
        e_he1.next = he1;
        e_he0.prev = he1.prev;
        e_he1.prev = he0.prev;
        he1.prev.next = e_he0;
        he0.prev.next = e_he1;
        he0.prev = e_he0;
        he1.prev = e_he1;
        e_he0.pair = e_he1;
        e_he1.pair = e_he0;
        e_he0.isCrease = true;
        e_he1.isCrease = true;

        // create new faces
        Face f0 = new Face(e_he0);
        Face f1 = new Face(e_he1);
        f0.isFront = f1.isFront = isFront;
        f0.index = f1.index = index;
        return new ArrayList<Face>(Arrays.asList(f0, f1));
    }

    public void setSide(){
        isFront = GeomUtil.CCWcheck(halfedge.prev.vertex.p, halfedge.vertex.p, halfedge.next.vertex.p);
    }

    public void diagnose() {
        System.out.print("f" + this.index + ": ");
        Halfedge he = halfedge;
        do {
            System.out.print(" (" + he.vertex.p.x + "," + he.vertex.p.y + ")");
            he = he.next;
        } while (he != halfedge);
        System.out.println();
    }
}
