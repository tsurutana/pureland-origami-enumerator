package purelandgen.matching;

import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.ArrayList;

public class MomentOperator {

	public static final int CONTOUR = 0;
	public static final int FRONT = 1;

	public static double getMatchingValue(Area area) {
		ArrayList<ArrayList<Point2D>> subpaths = getSubpaths(area.getPathIterator(null));
		return _getMatchingValueCongruence(subpaths);   // 合同判定（デフォルト）
		// return _getMatchingValueSimilarity(subpaths); // 相似判定に切り替える場合
	}

	/**
	 * 合同判定用：平行移動・回転・鏡映不変、スケール非不変。
	 * mu20+mu02（2次中心モーメントのトレース）を面積で正規化しない。
	 * 折りの深さが異なると面積が変わるため、相似な結果でも別形状として区別される。
	 */
	private static double _getMatchingValueCongruence(ArrayList<ArrayList<Point2D>> subpaths) {
		double[] moments = HuMoments.getHuMoments(subpaths);
		convertHuMoment(moments);
		return moments[0];
	}

	/**
	 * 相似判定用：平行移動・回転・鏡映・スケール不変。
	 * (mu20+mu02)/m00^2 で面積正規化することで相似形を同一視する。
	 * 注意：折り深さが異なる相似結果（例：大きな三角形と小さな三角形）も同一とみなされる。
	 */
	private static double _getMatchingValueSimilarity(ArrayList<ArrayList<Point2D>> subpaths) {
		HuMoments m = new HuMoments();
		HuMoments.icvCoutourMoments(subpaths, m);  // icvCompleteMomentState も内部で呼ばれる
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

	/**
	 * Pathを、SEG_MOVETO単位で独立したサブパス（閉ループ）のリストへ変換する。
	 * Areaは同一の輪郭でも面の合成順序によって複数のサブパスに分かれて表現されることがあり
	 * （例：本来1つの領域が2個や5個のループとして返る）、以前はここで全サブパスを1本の
	 * 点列に平坦化していたため、HuMoments側の周回積分が別ループの終点と始点を偽の辺で
	 * つないでしまい、幾何学的に同一の輪郭でもモーメント値が大きくずれる不具合があった。
	 */
	private static ArrayList<ArrayList<Point2D>> getSubpaths(PathIterator pi) {
		ArrayList<ArrayList<Point2D>> subpaths = new ArrayList<>();
		ArrayList<Point2D> current = null;
		Point2D m = null, p, q = new Point2D.Double(Double.MAX_VALUE, Double.MAX_VALUE);

		double[] coords = new double[6];
		while (!pi.isDone()) {
			int type = pi.currentSegment(coords);
			p = new Point2D.Double(coords[0], coords[1]);
			if (type == PathIterator.SEG_MOVETO) {
				current = new ArrayList<>();
				subpaths.add(current);
				m = p;
				current.add(m);
				q = new Point2D.Double(Double.MAX_VALUE, Double.MAX_VALUE);
			} else if (p.distance(q) > 1.E-12) {
				if (type == PathIterator.SEG_LINETO) {
					current.add(p);
					q = p;
				} else if (type == PathIterator.SEG_CLOSE) {
					current.add(new Point2D.Double(m.getX(), m.getY()));
				}
			}
			pi.next();
		}
		return subpaths;
	}
}
