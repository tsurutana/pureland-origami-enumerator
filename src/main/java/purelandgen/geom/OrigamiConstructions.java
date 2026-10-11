package purelandgen.geom;

import java.util.ArrayList;
import java.util.Random;

public class OrigamiConstructions {
    // # of Origami geometric constructions
    static final String PASSTHROUGH_2P = "PT_2P";
    static final String PLACE_PtP = "P_PtP";
    static final String PLACE_EtE = "P_EtE";
    static final String PASSTHROUGH_P = "PT_P";
    static final String PLACE_PtE = "P_PtE";
    static final String PERPENDICULAR_E = "Prp_E";
    static final String RANDOM3DOF = "Random";

    static Random _rnd = new Random();
    private static final int MAX_RANDOM_TRIES = 100;

    // a line passing through v1 and v2
    static void setFLbyPassingThrough2Points(Figure fig, FoldingLine fl) {
        Vec2 v1, v2;
        final int index_v1 = _rnd.nextInt(fig.vertices.size());
        v1 = fig.vertices.get(index_v1).p;
        v2 = v1;
        for (int t = 0; t < MAX_RANDOM_TRIES; t++) {
            v2 = fig.vertices.get(_rnd.nextInt(fig.vertices.size())).p;
            if (v1.distanceTo(v2) >= GeomUtil.EPS) break;
        }
        fl.setValuesFromVectors(v1, v2);
    }

    static FoldingLine getFLbyPassingThrough2Points(Vec2 v1, Vec2 v2) {
         return new FoldingLine(v1, v2);
    }

    static void setFLbyPlacingPointOntoPoint(Figure fig, FoldingLine fl) {
        // Randomly select v1, v2
        Vec2 v1, v2;
        final int index_v1 = _rnd.nextInt(fig.vertices.size());
        v1 = fig.vertices.get(index_v1).p;
        v2 = v1;
        for (int t = 0; t < MAX_RANDOM_TRIES; t++) {
            v2 = fig.vertices.get(_rnd.nextInt(fig.vertices.size())).p;
            if (v1.distanceTo(v2) >= GeomUtil.EPS) break;
        }

        // a line placing v1 onto v2
        Vec2 _p1 = new Vec2(), _p2 = new Vec2();
        GeomUtil.setPerpendicularBisector(v1, v2, _p1, _p2);
        fl.setValuesFromVectors(_p1, _p2);
    }

    static void setFLbyPlacingEdgeOntoEdge(Figure fig, FoldingLine fl) {
        Vec2 _p1 = new Vec2(), _p2 = new Vec2(), _cross = new Vec2();
        // Randomly select e1,e2
        Vec2 l1s, l1e, l2s, l2e;
        final int index_e1 = _rnd.nextInt(fig.edges.size());
        l1s = fig.edges.get(index_e1).sv.p;
        l1e = fig.edges.get(index_e1).ev.p;
        l2s = l1s; l2e = l1e;
        for (int t = 0; t < MAX_RANDOM_TRIES; t++) {
            final int index_e2 = _rnd.nextInt(fig.edges.size());
            l2s = fig.edges.get(index_e2).sv.p;
            l2e = fig.edges.get(index_e2).ev.p;
            if (!GeomUtil.isOnSameLine(l1s, l1e, l2s, l2e)) break;
        }

        // a line placing e1 onto e2
        // obtain cross-point
        GeomUtil.setCrossPointByLines(l1s, l1e, l2s, l2e, _cross);
        if (Double.isNaN(_cross.x))  {
            // lines are parallel
            GeomUtil.setMidPoint(l1s, l2s, _p1);
            GeomUtil.setMidPoint(l1e, l2s, _p2);
        } else {
            // non-parallel
            _p1.set(_cross);
            final boolean direction = _rnd.nextBoolean();  // choose 1 from 2 possibilities
            if (direction) {
                GeomUtil.setBisectorVec(l1s, _p1, l2s, _p2);
            } else {
                GeomUtil.setBisectorVec(l1s, _p1, l2e, _p2);
            }
  /* memo
        // when cross-point is NOT on either edges,
        // the answer will be the same. (We can not obtain second answer)
        */
        }
        fl.setValuesFromVectors(_p1, _p2);
    }

    // 1-DOF fold
    static void setFLbyPassingThroughPoint(Figure fig, FoldingLine fl) {
        // Randomly select v1
        Vec2 v1;
        final int index_v1 = _rnd.nextInt(fig.vertices.size());
        v1 = fig.vertices.get(index_v1).p;

        // a line passing through v1
        Vec2 _p1 = new Vec2(), _p2 = new Vec2();
        _p1.set(v1);
        final double radian = _rnd.nextDouble() * 1.570796;
        _p2.set(1.0, 0.0);
        _p2.rotate(radian);
        _p2.add(_p1);
        fl.setValuesFromVectors(_p1, _p2);
    }

    // 1-DOF fold
    static void setFLbyPlacingPointOntoEdge(Figure fig, FoldingLine fl) {
        Vec2 _p1 = new Vec2(), _p2 = new Vec2(), _cross = new Vec2();
        // Randomly select v1, e1
        Vec2 v1, l1s, l1e;
        final int index_e1 = _rnd.nextInt(fig.edges.size());
        l1s = fig.edges.get(index_e1).sv.p;
        l1e = fig.edges.get(index_e1).ev.p;
        v1 = fig.vertices.get(0).p;
        for (int t = 0; t < MAX_RANDOM_TRIES; t++) {
            v1 = fig.vertices.get(_rnd.nextInt(fig.vertices.size())).p;
            if (GeomUtil.distancePointToLine(v1, l1s, l1e) >= GeomUtil.EPS) break;
        }

        // a line placing v1 onto e1
        final double t = _rnd.nextDouble();
        _cross.set((l1e.x - l1s.x) * t + l1s.x, (l1e.y - l1s.y) * t + l1s.y);
        GeomUtil.setPerpendicularBisector(v1, _cross, _p1, _p2);
        fl.setValuesFromVectors(_p1, _p2);
    }

    // 1-DOF fold
    static void setFLbyPerpendicularToEdge(Figure fig, FoldingLine fl) {
        Vec2 _p1 = new Vec2(), _p2 = new Vec2(), _cross = new Vec2();
        // Randomly select e1
        Vec2 l1s, l1e;
        final int index_e1 = _rnd.nextInt(fig.edges.size());
        l1s = fig.edges.get(index_e1).sv.p;
        l1e = fig.edges.get(index_e1).ev.p;
        // a line perpendicular to edge
        final double t = _rnd.nextDouble() * 0.6 + 0.2;  // prevent small fold
        _cross.set((l1e.x - l1s.x) * t + l1s.x, (l1e.y - l1s.y) * t + l1s.y);
        GeomUtil.setPerpendicularBisector(l1s, _cross, _p1, _p2);
        fl.setValuesFromVectors(_p1, _p2);
    }

    // 3-DOF fold (completely random)
    static void setFLByRandom(Figure fig, FoldingLine fl) {
        Vec2 _p1 = new Vec2(), _p2 = new Vec2();
        // a line passing through a point
        final double x = _rnd.nextDouble() * 100 - 50;
        final double y = _rnd.nextDouble() * 100 - 50;
        _p1.set(x, y);
        final double rad = _rnd.nextDouble() * 1.570796;
        _p2.set(10.0, 0.0);
        _p2.rotate(rad);
        _p2.add(_p1);
        fl.setValuesFromVectors(_p1, _p2);
    }


    public static ArrayList<FoldingLine> getPossibleFoldingLines(Figure fig, boolean[] constructions){
        //int[] sum = new int[7];
        int extra = _addIntersections(fig);
        ArrayList<FoldingLine> possibleFoldingLines = new ArrayList<>();
        if (constructions[0])
            _setFLByAxiom1(fig, possibleFoldingLines);
        if (constructions[1])
            _setFLByAxiom2(fig, possibleFoldingLines);
        if (constructions[2])
            _setFLByAxiom3(fig, possibleFoldingLines);
        //for (FoldingLine fl : possibleFoldingLines)
        //    System.out.println("("+fl.p0.x +","+fl.p0.y +"),("+fl.p1.x+","+fl.p1.y);
        for (int i=0; i<extra; i++)
            fig.vertices.remove(fig.vertices.size()-1);
        return possibleFoldingLines;
    }

    /*
     * 2点p0,p1が与えられたとき、2点を通るただ一つの折り方がある
     */
    private static void _setFLByAxiom1(Figure d, ArrayList<FoldingLine> lines){
        Vertex v0, v1;
        for (int i=0,n=d.vertices.size(); i<n; i++){
            v0 = d.vertices.get(i);
            for (int j=i+1; j<n; j++){
                v1 = d.vertices.get(j);
                //頂点が同じ位置にあれば除外
                if (v0.p.epsilonEqual(v1.p)) continue;
                FoldingLine line = new FoldingLine(v0.p, v1.p);
                line.construction = OrigamiConstructions.PASSTHROUGH_2P;
                line.p0SourceEdges = v0.sourceEdges;
                line.p1SourceEdges = v1.sourceEdges;
                lines.add(line);
            }
        }
    }

    /*
     * 2点p0,p1が与えられたとき、p0をp1に重ねるただ一つの折り方がある
     */
    private static void _setFLByAxiom2(Figure d, ArrayList<FoldingLine> lines){
        Vertex v0, v1;
        for (int i=0,n=d.vertices.size(); i<n; i++){
            v0 = d.vertices.get(i);
            for (int j=i+1; j<n; j++){
                v1 = d.vertices.get(j);
                //頂点が同じ位置にあれば除外
                if (v0.p.epsilonEqual(v1.p)) continue;
                FoldingLine line = new FoldingLine();
                GeomUtil.setPerpendicularBisector(
                        v0.p, v1.p, line.p0, line.p1);
                line.construction = OrigamiConstructions.PLACE_PtP;
                line.landmark0 = new Vec2(v0.p);
                line.landmark1 = new Vec2(v1.p);
                line.landmark0SourceEdges = v0.sourceEdges;
                line.landmark1SourceEdges = v1.sourceEdges;
                lines.add(line);
            }
        }
    }
    /*
     * 2辺e0,e1が与えられたとき、e0をe1に重ねる折り方が(複数)ある
     */
    private static void _setFLByAxiom3(Figure d, ArrayList<FoldingLine> lines){

        // ここから角の二等分線を求める
        for (int i=0,n=d.edges.size(); i<n; i++){
            Vec2 s0 = new Vec2(d.edges.get(i).sv.p);
            Vec2 e0 = new Vec2(d.edges.get(i).ev.p);
            for (int j=i+1; j<n; j++){
                Vec2 s1 = new Vec2(d.edges.get(j).sv.p);
                Vec2 e1 = new Vec2(d.edges.get(j).ev.p);
                //エッジが重なっている場合を除外する
                if (GeomUtil.isOnSameLine(s0, e0, s1, e1))
                    continue;

                //交点を求める
                Vec2 p0 = new Vec2();
                Vec2 p1 = new Vec2();
                Vec2 cross = new Vec2();
                GeomUtil.setCrossPointByLines(s0, e0, s1, e1, cross);
                if (Double.isNaN(cross.x) ){
                    //並行な場合
                    GeomUtil.setMidPoint(s0, s1, p0);
                    GeomUtil.setMidPoint(e0, s1, p1);
                } else {
                    p0 = cross;
                    if (cross.epsilonEqual(s0)) {
                        if (cross.epsilonEqual(s1))
                            GeomUtil.setBisectorVec(e0, cross, e1, p1);
                        else
                            GeomUtil.setBisectorVec(e0, cross, s1, p1);
                    } else {
                        if (cross.epsilonEqual(s1))
                            GeomUtil.setBisectorVec(s0, cross, e1, p1);
                        else
                            GeomUtil.setBisectorVec(s0, cross, s1, p1);
                    }
                    FoldingLine fl = new FoldingLine(p0, p1);
                    fl.construction = OrigamiConstructions.PLACE_EtE;
                    fl.landmark0 = new Vec2(s0); fl.landmark1 = new Vec2(e0);
                    fl.landmark2 = new Vec2(s1); fl.landmark3 = new Vec2(e1);
                    lines.add(fl);
                    p1.sub(p0);
                    p1.rotate(0.5 * Math.PI);
                    p1.add(p0);
                }
                FoldingLine fl = new FoldingLine(p0, p1);
                if (_isMirroredEdgeOverlapsEdge(s0, e0, p0, p1, s1, e1)) {
                    fl.construction = OrigamiConstructions.PLACE_EtE;
                    fl.landmark0 = new Vec2(s0); fl.landmark1 = new Vec2(e0);
                    fl.landmark2 = new Vec2(s1); fl.landmark3 = new Vec2(e1);
                    lines.add(fl);
                }
            }
        }
    }

    //折り返した2点のどちらかが辺の範囲内にあるか(辺が重なっているか)
    private static boolean _isMirroredEdgeOverlapsEdge(Vec2 s0, Vec2 e0, Vec2 f0, Vec2 f1, Vec2 s1, Vec2 e1){
        Vec2 s0_dash = new Vec2(s0);
        GeomUtil.moveVectorToOpposite(s0_dash, f0, f1);
        Vec2 e0_dash = new Vec2(e0);
        GeomUtil.moveVectorToOpposite(e0_dash, f0, f1);
        if (GeomUtil.isRangeStrict(s0_dash, s1, e1))
            return true;
        else if (GeomUtil.isRangeStrict(e0_dash, s1, e1))
            return true;
        else if (GeomUtil.isRangeStrict(s1, s0_dash, e0_dash))
            return true;
        else if (GeomUtil.isRangeStrict(e1, s0_dash, e0_dash))
            return true;
        return false;
    }

    private static int _addIntersections(Figure figure) {
        ArrayList<Vec2> newPoints = new ArrayList<>();
        // Parallel to newPoints: the (exactly 2) existing-figure edges whose crossing produced
        // that point, recorded inline (not re-derived afterward) so each new floating Vertex can
        // remember exactly which edges it came from -- see Vertex.sourceEdges' javadoc.
        ArrayList<Vec2[][]> newPointSourceEdges = new ArrayList<>();
        for (int i = 0, n = figure.edges.size(); i < n; i++) {
            Vec2 s0 = figure.edges.get(i).sv.p;
            Vec2 e0 = figure.edges.get(i).ev.p;
            for (int j = i+1; j < n; j++) {
                Vec2 s1 = figure.edges.get(j).sv.p;
                Vec2 e1 = figure.edges.get(j).ev.p;
                Vec2 cross = GeomUtil.getCrossPointBySegments(s0, e0, s1, e1);
                if (cross == null) continue;
                boolean newlocation = true;
                for (Vertex v : figure.vertices) {
                    if (cross.epsilonEqual(v.p)) { newlocation = false; break; }
                }
                if (newlocation) {
                    for (Vec2 p : newPoints) {
                        if (cross.epsilonEqual(p)) { newlocation = false; break; }
                    }
                }
                if (newlocation) {
                    newPoints.add(cross);
                    newPointSourceEdges.add(new Vec2[][]{
                            {new Vec2(s0), new Vec2(e0)},
                            {new Vec2(s1), new Vec2(e1)}});
                }
            }
        }
        for (int k = 0; k < newPoints.size(); k++) {
            Vec2 p = newPoints.get(k);
            Vertex v = new Vertex(p.x, p.y);
            v.sourceEdges = newPointSourceEdges.get(k);
            figure.vertices.add(v);
        }
        return newPoints.size();
    }
}
