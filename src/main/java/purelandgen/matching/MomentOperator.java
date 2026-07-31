package purelandgen.matching;

import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.ArrayList;

public class MomentOperator {

	public static final int CONTOUR = 0;
	public static final int FRONT = 1;

	public static double getMatchingValue(Area area) {
		ArrayList<Point2D> plist = getPoint2DlistfromPathIterator(area.getPathIterator(null));
		Point2D center = HuMoments.getCentroid(plist);
		for (Point2D p : plist)
			p.setLocation(p.getX() + center.getX(), p.getY() + center.getY());
		return _getMatchingValueCongruence(plist);   // 合同判定（デフォルト）
		// return _getMatchingValueSimilarity(plist); // 相似判定に切り替える場合
	}

	/**
	 * 合同判定用：平行移動・回転・鏡映不変、スケール非不変。
	 * mu20+mu02（2次中心モーメントのトレース）を面積で正規化しない。
	 * 折りの深さが異なると面積が変わるため、相似な結果でも別形状として区別される。
	 */
	private static double _getMatchingValueCongruence(ArrayList<Point2D> plist) {
		double[] moments = HuMoments.getHuMoments(plist);
		convertHuMoment(moments);
		return moments[0];
	}

	/**
	 * 相似判定用：平行移動・回転・鏡映・スケール不変。
	 * (mu20+mu02)/m00^2 で面積正規化することで相似形を同一視する。
	 * 注意：折り深さが異なる相似結果（例：大きな三角形と小さな三角形）も同一とみなされる。
	 */
	private static double _getMatchingValueSimilarity(ArrayList<Point2D> plist) {
		HuMoments m = new HuMoments();
		HuMoments.icvCoutourMoments(plist, m);  // icvCompleteMomentState も内部で呼ばれる
		if (m.mu00 == 0) return 0;
		double m00sq = m.mu00 * m.mu00;
		double s = (m.mu20 + m.mu02) / m00sq;
		double tmp = Math.log(Math.abs(s));
		if (Double.isInfinite(tmp)) return 0;
		return Math.signum(s) * tmp;
	}

	// sign(x)*log(x)を計算し比較用の値にする
	private static void convertHuMoment(double[] moments) {
		for (int i=0; i<8; i++) {
			final double sign = Math.signum(moments[i]);
			double tmp = Math.log(Math.abs(moments[i]));
			if (Double.isInfinite(tmp))
				moments[i] = 0;
			else
				moments[i] = sign * tmp;
		}
	}

	// Pathから頂点のリストへ変換
	private static ArrayList<Point2D> getPoint2DlistfromPathIterator(PathIterator pi) {

		ArrayList<Point2D> ret = new ArrayList<Point2D>();
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
