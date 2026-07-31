package purelandgen.geom;

import purelandgen.matching.MomentOperator;

import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ListIterator;

public class Silhouette {


    /**
     * 各図形の isUniqueSilhouette / silhouetteGroupSize をセットする。図形リストの
     * 順序・内容は変えない（除去なし）。
     *
     * - isUniqueSilhouette=true  : 同一 matchingValue がこの群に1件だけ（かつ非MAKECREASE）
     * - isUniqueSilhouette=false : 同一 matchingValue が複数 or MAKECREASE
     * - silhouetteGroupSize      : 同一 matchingValue のグループ件数
     *
     * hasMultiPath は FoldingMachine が設定するため本メソッドでは変更しない。
     * MAKECREASE は他のクリースバリアントと同一 matchingValue になるが、
     * クリースパターンが異なる独立した図形なので常に isUniqueSilhouette=false。
     *
     * NOTE: ストリーミングモードでの hasMultiPath 完全対応は未実装。
     */
    public static void computeProperties(ArrayList<Figure> figures) {
        if (figures.isEmpty()) return;
        for (Figure f : figures)
            f.matchingValue = MomentOperator.getMatchingValue(getSilhouette(f));

        // インデックス配列のみをソートしてグループを検出（figures 本体の順序は保持）
        int n = figures.size();
        ArrayList<Integer> sortedIdx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) sortedIdx.add(i);
        sortedIdx.sort((a, b) -> Double.compare(figures.get(a).matchingValue, figures.get(b).matchingValue));

        int i = 0;
        while (i < n) {
            double mv = figures.get(sortedIdx.get(i)).matchingValue;
            int j = i + 1;
            while (j < n && Math.abs(figures.get(sortedIdx.get(j)).matchingValue - mv) < 1e-6)
                j++;
            int groupSize = j - i;
            int foldableCount = 0;
            for (int k = i; k < j; k++) {
                Figure f = figures.get(sortedIdx.get(k));
                if (f.isFoldable && !_isMakeCrease(f)) foldableCount++;
            }
            for (int k = i; k < j; k++) {
                Figure f = figures.get(sortedIdx.get(k));
                f.silhouetteGroupSize = groupSize;
                f.isUniqueSilhouette = f.isFoldable && !_isMakeCrease(f) && (foldableCount == 1);
                f.silhouettePropertiesComputed = true;
            }
            i = j;
        }
    }

    private static boolean _isMakeCrease(Figure f) {
        return f.foldingLine != null && f.foldingLine.type == FoldingLine.MAKECREASE;
    }

    public static void uniqueSilhouetteOnly(ArrayList<Figure> figures) {
        figures.removeIf(f -> !f.isUniqueSilhouette);
    }

    /** @deprecated computeProperties() を使用すること */
    public static void uniques(ArrayList<Figure> figures) {
        sortByMatchingValue(figures);
        double value = figures.get(0).matchingValue;
        Figure prev = figures.get(0);
        ListIterator<Figure> iter = figures.listIterator(1);
        while (iter.hasNext()) {
            Figure figure = iter.next();
            if (Math.abs(value - figure.matchingValue) < 1e-6) {
                iter.remove();
                figures.get(iter.previousIndex()).hasMultiPath = true;
            }
            value = figure.matchingValue;
        }
        for (Figure f : figures)
            System.out.println(f.matchingValue);
    }

    public static void checkMultiPath(ArrayList<Figure> figures) {
        sortByMatchingValue(figures);
        double value = figures.get(0).matchingValue;
        Figure prev = figures.get(0);
        ListIterator<Figure> iter = figures.listIterator(1);
        while (iter.hasNext()) {
            Figure figure = iter.next();
            if (Math.abs(value - figure.matchingValue) < 1e-7) {
                figure.hasMultiPath = true;
                figures.get(iter.previousIndex()-1).hasMultiPath = true;
            }
            value = figure.matchingValue;
        }
    }

    public static void sortByMatchingValue(ArrayList<Figure> figures) {
        for (Figure figure : figures) {
            Area a = getSilhouette(figure);
            figure.matchingValue = MomentOperator.getMatchingValue(a);

        }
        Collections.sort(figures, matchingValueComparator);
    }

    public static void multiPathOnly(ArrayList<Figure> figures) {
        ListIterator<Figure> iter = figures.listIterator();
        while (iter.hasNext()) {
            Figure figure = iter.next();
            if (!figure.hasMultiPath) {
                iter.remove();
            }
        }
    }

    public static void singlePathOnly(ArrayList<Figure> figures) {
        ListIterator<Figure> iter = figures.listIterator();
        while (iter.hasNext()) {
            Figure figure = iter.next();
            if (figure.hasMultiPath) {
                iter.remove();
            }
        }
    }

    static Comparator<Figure> matchingValueComparator = new Comparator<Figure>() {
        @Override
        public int compare(Figure o1, Figure o2) {
            if (o1.matchingValue == o2.matchingValue)
                return 0;
            else if (o1.matchingValue < o2.matchingValue)
                return -1;
            else
                return 1;
        }
    } ;


    public static Area getSilhouette(Figure figure) {
        GeneralPath p2d = new GeneralPath();
        Area area = new Area();
        for (Face f : figure.faces) {
            Halfedge he = f.halfedge;
            p2d.moveTo(he.vertex.p.x, he.vertex.p.y);
            he = he.next;
            do {
                p2d.lineTo(he.vertex.p.x, he.vertex.p.y);
                he = he.next;
            } while (he != f.halfedge);
            p2d.closePath();
            area.add(new Area(p2d));
        }
        return area;
    }

    // Pathから頂点のリストへ変換
    private static ArrayList<Point2D> _getPoint2DlistfromPathIterator(PathIterator pi) {

        ArrayList<Point2D> ret = new ArrayList<>();
        Point2D m = null, p, q = new Point2D.Double(Double.MAX_VALUE, Double.MAX_VALUE);

        double[] coords = new double[6];
        while (!pi.isDone()) {
            int type = pi.currentSegment(coords);
            p = new Point2D.Double(coords[0], coords[1]);
            if (p.distance(q) > 1.E-12) {
                if (type == PathIterator.SEG_MOVETO) {
                    m = p;
                    ret.add(m);
                } else if (type == PathIterator.SEG_LINETO) {
                    ret.add(p);
                    q = p;
                } else if (type == PathIterator.SEG_CLOSE) {
                    ret.add(new Point2D.Double(m.getX(), m.getY()));
                }
            }
            pi.next();
        }
        return ret;
    }
}
