package purelandgen.geom;

import java.util.ArrayList;
import java.util.Random;

public class FoldingLine {

    // Folding techniques
    static final int VALLEY = 0;
    static final int MOUNTAIN = 1;
    static final int MAKECREASE = 2;

    Vec2 p0;
    Vec2 p1;
    int type = VALLEY;
    int layer = -1;
    String construction = "";
    Vec2 landmark0 = null; // construction-specific landmark point
    Vec2 landmark1 = null;
    Vec2 landmark2 = null; // second landmark segment endpoint (Axiom 3)
    Vec2 landmark3 = null;

    /** Sources that can discover this folding line */
    public ArrayList<ConstructionSource> sources = new ArrayList<>();


    FoldingLine() {
        p0 = new Vec2(0, 0);
        p1 = new Vec2(0, 1);
    }

    FoldingLine(double x0, double y0, double x1, double y1) {
        p0 = new Vec2(x0, y0);
        p1 = new Vec2(x1, y1);
    }

    FoldingLine(Vec2 v0, Vec2 v1) {
        p0 = new Vec2(v0);
        p1 = new Vec2(v1);
    }

    FoldingLine(FoldingLine fl) {
        p0 = new Vec2(fl.p0);
        p1 = new Vec2(fl.p1);
        type = fl.type;
        layer = fl.layer;
        construction = fl.construction;
        sources = new ArrayList<>(fl.sources);
        if (fl.landmark0 != null) landmark0 = new Vec2(fl.landmark0);
        if (fl.landmark1 != null) landmark1 = new Vec2(fl.landmark1);
        if (fl.landmark2 != null) landmark2 = new Vec2(fl.landmark2);
        if (fl.landmark3 != null) landmark3 = new Vec2(fl.landmark3);
    }

    /**
     * Returns the discoverability score (number of ways to find this fold).
     */
    public int getDiscoverability() {
        return sources.size();
    }

    void setValuesFromVectors(Vec2 v0, Vec2 v1) {
        p0.set(v0);
        p1.set(v1);
    }

    // move vertices in random angle
    void slide(int amount) {
        Vec2 unit = new Vec2(1.0, 0.0);
        Random rnd = new Random();
        double rad = rnd.nextDouble() * Math.PI * 2;
        unit.rotate(rad);
        unit.scale(amount);
        p0.add(unit);
        unit.set(1.0, 0.0);
        rad = rnd.nextDouble() * Math.PI * 2;
        unit.rotate(rad);
        unit.scale(amount);
        p1.add(unit);
    }

    void flip() {
        p0.x *= -1;
        p1.x *= -1;
    }
}
