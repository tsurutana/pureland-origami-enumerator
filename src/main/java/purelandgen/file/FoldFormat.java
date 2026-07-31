package purelandgen.file;

import com.google.gson.Gson;
import purelandgen.geom.Face;
import purelandgen.geom.Figure;
import purelandgen.geom.Halfedge;
import purelandgen.geom.Vertex;

import java.util.ArrayList;

public class FoldFormat {

    public String file_spec = "1";
    public String file_creator = "Pureland Origami Generator 1.0";
    public String[] file_classes = {"singleModel"};
    public int previous_index;
    public ArrayList<double[]> vertices_coords;
    public ArrayList<int[]> faces_vertices;

    public FoldFormat(Figure figure) {
        previous_index = figure.prev;
        vertices_coords = new ArrayList<>();
        for (Vertex v : figure.vertices) {
            vertices_coords.add(new double[]{v.p.x, v.p.y});
        }
        faces_vertices = new ArrayList<>();
        for (Face f : figure.faces) {
            int count = 0;
            Halfedge he = f.halfedge;
            ArrayList<Integer> indexes = new ArrayList<>();
            do {
                indexes.add(he.vertex.index);
                he = he.next;
            } while (he != f.halfedge);
            faces_vertices.add(indexes.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    public String toJson() {
        Gson gson = new Gson();
        return gson.toJson(this);
    }
}
