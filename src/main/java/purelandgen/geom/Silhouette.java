package purelandgen.geom;

import purelandgen.matching.MomentOperator;

import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;

public class Silhouette {


    /**
     * 各図形の isUniqueSilhouette / silhouetteGroupSize をセットする。図形リストの
     * 順序・内容は変えない（除去なし）。
     *
     * - isUniqueSilhouette=true  : このシルエット合同クラスに可折かつ非MAKECREASEのメンバーが1件だけ
     * - isUniqueSilhouette=false : 同クラスに複数 or MAKECREASE
     * - silhouetteGroupSize      : 同クラスのメンバー件数
     *
     * グループ分けは {@link ExactSilhouetteGrouping}（matchingValueチェインによる高速な
     * 事前バケット分け → バケット内を厳密ポリゴン合同判定で細分）を使う。単純な
     * matchingValueチェインだけでは、たまたま近い値になった異なるシルエットを誤って
     * 同一視してしまうことが確認されている。
     *
     * hasMultiPath は FoldingMachine が設定するため本メソッドでは変更しない。
     * MAKECREASE は他のクリースバリアントと同一シルエットになり得るが、
     * クリースパターンが異なる独立した図形なので常に isUniqueSilhouette=false。
     *
     * NOTE: ストリーミングモードでの hasMultiPath 完全対応は未実装。
     */
    public static void computeProperties(ArrayList<Figure> figures) {
        if (figures.isEmpty()) return;
        for (Figure f : figures)
            f.matchingValue = MomentOperator.getMatchingValue(getSilhouette(f));

        List<List<Figure>> groups = ExactSilhouetteGrouping.group(figures);
        for (List<Figure> group : groups) {
            int foldableCount = 0;
            for (Figure f : group)
                if (f.isFoldable && !_isMakeCrease(f)) foldableCount++;
            for (Figure f : group) {
                f.silhouetteGroupSize = group.size();
                f.isUniqueSilhouette = f.isFoldable && !_isMakeCrease(f) && (foldableCount == 1);
                f.silhouettePropertiesComputed = true;
            }
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
}
