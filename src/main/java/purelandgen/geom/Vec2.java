package purelandgen.geom;

public class Vec2 {

    public double x, y;
    static final double EPS = 1.0e-8;

    public Vec2() {
        x = y = 0.0;
    }
    public Vec2(double x, double y){
        this.x = x;
        this.y = y;
    }
    public Vec2(Vec2 v) {
        x = v.x;
        y = v.y;
    }

    public void set(double x, double y) {
        this.x = x;
        this.y = y;
    }
    public void set(Vec2 v) {
        set(v.x, v.y);
    }

    public void add(Vec2 v) {
        x += v.x;
        y += v.y;
    }

    public void sub(Vec2 v) {
        x -= v.x;
        y -= v.y;
    }

    public double dot(Vec2 v) {
        return x * v.x + y * v.y;
    }
    public double dot(Vec2 v0, Vec2 v1) {
        return v0.x * v1.x + v0.y * v1.y;
    }

    public double cross(Vec2 v) {
        return (x * v.y) - (y * v.x);
    }
    public static double cross(Vec2 v0, Vec2 v1) {
        return (v0.x * v1.y) - (v0.y * v1.x);
    }

    public double length() {
        return Math.sqrt(x * x + y * y);
    }
    public double lengthSquared() {
        return x * x + y * y;
    }

    public double distanceTo(Vec2 v) {
        return Math.sqrt((x - v.x) * (x - v.x) + (y - v.y) * (y - v.y));
    }

    public void scale(double scale) {
        x *= scale;
        y *= scale;
    }

    public void normalize() {
        final double len = length();
        if (len < EPS) return;
        final double d = 1.0 / len;
        x *= d;
        y *= d;
    }

    public double angle(Vec2 v) {
        return Math.acos( dot(v) / (length() * v.length()) );
    }

    public void rotate(double angle) {
        double rx = x * Math.cos(angle) - y * Math.sin(angle);
        double ry = x * Math.sin(angle) + y * Math.cos(angle);
        set(rx, ry);
    }

    public boolean epsilonEqual(Vec2 v) {
        return distanceTo(v) < EPS;
    }
}
