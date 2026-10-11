package purelandgen.geom;

import purelandgen.file.FoldFormat;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

public class Figure {

    public ArrayList<Face> faces = new ArrayList<>();
    public ArrayList<Edge> edges = new ArrayList<>();
    public ArrayList<Vertex> vertices = new ArrayList<>();
    public FoldingLine foldingLine;
    public int prev = 0;
    public double sd = 0; // shape descriptor
    public double sdNormalized = 0; // normalized shape descriptor (layer-weighted, flip-invariant)
    public double matchingValue = 0;  // log (3rd moment)
    public boolean hasMultiPath = false;
    public boolean isUniqueSilhouette = false;
    public int silhouetteGroupSize = 1;   // number of identical silhouettes before matchingValue dedup
    public boolean silhouettePropertiesComputed = false; // whether computeProperties() has been run
    public boolean isFoldable = true;

    /** Floor tolerance for sd-based duplicate elimination (see epsilonEqual()).
     *  Defaults to GeomUtil.EPS to preserve existing behavior. Overridable (e.g. via
     *  a system property read at harness startup) so its effect on enumeration counts
     *  can be stress-tested independently of the shared geometric epsilon used
     *  elsewhere (point/line comparisons, degenerate-area checks, etc.). */
    public static double SD_TOLERANCE = GeomUtil.EPS;

    public Figure(double w, double h) {
        final double hw = w / 2;
        final double hh = h / 2;
        Vertex v0 = new Vertex( hw,  hh);
        Vertex v1 = new Vertex( hw, -hh);
        Vertex v2 = new Vertex(-hw, -hh);
        Vertex v3 = new Vertex(-hw,  hh);
        vertices.add(v0);
        vertices.add(v1);
        vertices.add(v2);
        vertices.add(v3);
        Vertex[] vlist = new Vertex[]{v0, v1, v2, v3};
        Face f = new Face(vlist);
        faces.add(f);
        buildEdges();
    }

    Figure(Figure fig) {
        // copy vertices
        for (int i = 0, n = fig.vertices.size(); i < n; i++) {
            fig.vertices.get(i).index = i;
            vertices.add(new Vertex(fig.vertices.get(i)));
        }
        // copy faces
        for (Face f : fig.faces) {
            faces.add(new Face(f));
        }
        for (Vertex v : fig.vertices) {
            v.copyTo = null;
        }
        // copy folding line and creases
        if (fig.foldingLine != null) {
            foldingLine = new FoldingLine(fig.foldingLine);
        }
        prev = fig.prev;
        // reconstruct edges
        buildEdges();
    }

    public Figure(FoldFormat file) {
        boolean hasOrigP = file.vertices_origP != null
                && file.vertices_origP.size() == file.vertices_coords.size();
        for (int i = 0; i < file.vertices_coords.size(); i++) {
            double[] v = file.vertices_coords.get(i);
            Vertex vertex = new Vertex(v[0], v[1]);
            if (hasOrigP) {
                double[] o = file.vertices_origP.get(i);
                vertex.origP = new Vec2(o[0], o[1]);
            } // else: leave origP at its fallback (== folded p), per Vertex.origP javadoc
            vertices.add(vertex);
        }
        for (int[] indexes : file.faces_vertices) {
            Vertex[] vlist = new Vertex[indexes.length];
            for (int i=0; i<indexes.length; i++) {
                if (indexes[i] < 0 || indexes[i] >= vertices.size())
                    throw new IllegalArgumentException(
                            "Face vertex index out of range: " + indexes[i]);
                vlist[i] = vertices.get(indexes[i]);
            }
            faces.add(new Face(vlist));
        }
        for (Face f : faces) {
            f.setSide();
        }
        prev = file.previous_index;
        // reconstruct edges
        buildEdges();
    }

    public boolean isMakeCreaseFold() {
        return foldingLine != null && foldingLine.type == FoldingLine.MAKECREASE;
    }

    public void printInfo(int index, int total) {
        System.out.printf("=== Figure %d / %d  layers=%d  vertices=%d  prev=%d ===%n",
                index + 1, total, faces.size(), vertices.size(), prev);
        if (foldingLine != null) {
            String type = foldingLine.type == FoldingLine.VALLEY ? "valley"
                        : foldingLine.type == FoldingLine.MOUNTAIN ? "mountain" : "crease";
            System.out.printf("  FoldLine: (%.4f,%.4f)-(%.4f,%.4f)  type=%s  layer=%d  construction=%s%n",
                    foldingLine.p0.x, foldingLine.p0.y, foldingLine.p1.x, foldingLine.p1.y,
                    type, foldingLine.layer, foldingLine.construction);
        } else {
            System.out.println("  FoldLine: none");
        }
        for (Face f : faces) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  Layer %d [%s]:", f.index, f.isFront ? "front" : "back"));
            Halfedge he = f.halfedge;
            do {
                sb.append(String.format(" (%.4f,%.4f)", he.vertex.p.x, he.vertex.p.y));
                he = he.next;
            } while (he != f.halfedge);
            System.out.println(sb);
        }
    }

    // only checks # of lines
    public boolean isLocalFoldable() {
        for (Vertex v : vertices) {
            if (!v.isFlatFoldable())
                return false;
        }
        return true;
    }

    public void buildEdges() {
        edges.clear();
        _renumberingVertices();
        _renumberingFaces();
        HashMap<IntBuffer, Edge> edgeHashMap = new HashMap<>();
        for (Face f : faces) {
            Halfedge he = f.halfedge;
            do {
                Vertex a, b;
                if (he.vertex.index < he.next.vertex.index) {
                    a = he.vertex;
                    b = he.next.vertex;
                } else {
                    a = he.next.vertex;
                    b = he.vertex;
                }
                IntBuffer buf = IntBuffer.wrap(new int[]{a.index, b.index});
                if (!edgeHashMap.containsKey(buf)) {
                    Edge new_e = new Edge(a, b);
                    he.edge = new_e;
                    new_e.left = he;
                    edges.add(new_e);
                    edgeHashMap.put(buf, new_e);
                } else {
                    Edge e = edgeHashMap.get(buf);
                    he.edge = e;
                    e.right = he;
                    he.pair = e.left;
                    e.left.pair = he;
                }
                he = he.next;
            } while (he != f.halfedge);
        }
    }

    private void _renumberingFaces() {
        int i = 0;
        for (Face f : faces) {
            f.index = i++;
            f.visited = false;
        }
    }

    private void _renumberingVertices() {
        int i = 0;
        for (Vertex v : vertices) {
            v.index = i++;
            v.isMovable = true;
        }
    }

    public void flip() {
        for (Vertex v : vertices) {
            v.p.x *= -1;
        }
        for (Face f : faces) {
            f.isFront = !f.isFront;
        }
        Collections.reverse(faces);
    }

    public void mirroringFlip() {
        for (Face f : faces) {
            f.isFront = !f.isFront;
        }
        Collections.reverse(faces);
    }

    // True if the common barycenters are equal
    boolean epsilonEqual(Figure fig) {
        final double dif = Math.abs(sd - fig.sd);
        final double scale = Math.max(sd, fig.sd);
        return dif < Math.max(scale * 1e-9, SD_TOLERANCE);
    }

    // Sets shape to the sum of distances from the barycenter to each vertex
    public void setShapeDescriptor(){
        sd = 0;
        Vec2 barycenter = new Vec2();  // common barycenter
        for (Face f : faces) {
            Vec2 center = new Vec2();
            int i = 0;
            Halfedge he = f.halfedge;
            do {
                center.add(he.vertex.p);
                he = he.next;
                i++;
            } while (he != f.halfedge);
            center.scale(1.0/i);
            barycenter.add(center);
        }
        barycenter.scale(1.0/faces.size());
        for (Vertex v : vertices)
            sd += barycenter.distanceTo(v.p);
    }

    /**
     * Computes a normalized shape descriptor.
     * Normalizes independently of face list order, so geometrically symmetric shapes
     * are treated as identical. Shapes that differ only by front/back
     * (mountain fold vs valley fold) are also treated as identical.
     *
     * Computation method:
     * 1. Compute (area, distance from barycenter) for each face
     * 2. Sort by descending area, then descending distance to determine canonical order
     * 3. Sum with depth-based weights following the canonical order (no front/back sign)
     */
    public void setNormalizedShapeDescriptor() {
        if (faces.isEmpty()) {
            sdNormalized = 0;
            return;
        }

        // Compute the common barycenter
        Vec2 barycenter = new Vec2();
        for (Face f : faces) {
            Vec2 center = getFaceCentroid(f);
            barycenter.add(center);
        }
        barycenter.scale(1.0 / faces.size());

        // Collect geometric info for each face
        int n = faces.size();
        double[] areas = new double[n];
        double[] dists = new double[n];

        for (int i = 0; i < n; i++) {
            Face f = faces.get(i);
            Vec2 center = getFaceCentroid(f);
            areas[i] = f.area();
            dists[i] = barycenter.distanceTo(center);
        }

        // Index array for sorting by descending area, then descending distance
        // Faces with the same (area, distance) yield the same sum regardless of depth order
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            int cmp = Double.compare(areas[b], areas[a]);
            if (cmp != 0) return cmp;
            return Double.compare(dists[b], dists[a]);
        });

        // Compute the depth-weighted descriptor in canonical order (no front/back sign)
        sdNormalized = 0;
        for (int depth = 0; depth < n; depth++) {
            int idx = order[depth];
            sdNormalized += (dists[idx] * areas[idx]) * (depth + 1);
        }

        // Also factor in the basic geometric descriptor (sd) to improve precision
        if (sd == 0) {
            for (Vertex v : vertices) {
                sd += barycenter.distanceTo(v.p);
            }
        }

        sdNormalized += sd * 1e-6;
    }

    /**
     * Computes the centroid of a face
     */
    private Vec2 getFaceCentroid(Face f) {
        Vec2 center = new Vec2();
        int count = 0;
        Halfedge he = f.halfedge;
        do {
            center.add(he.vertex.p);
            he = he.next;
            count++;
        } while (he != f.halfedge);
        center.scale(1.0 / count);
        return center;
    }

    /**
     * Compares using the normalized shape descriptor.
     * Treats mountain fold / valley fold differences as identical.
     */
    public boolean epsilonEqualNormalized(Figure fig) {
        return Math.abs(sdNormalized - fig.sdNormalized) < GeomUtil.EPS;
    }

}
