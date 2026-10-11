package purelandgen.matching;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

public class HuMoments {


	public double m00, m10, m01, m20, m11, m02, m30, m21, m12, m03;
	public double mu00, mu20, mu11, mu02, mu30, mu21, mu12, mu03;
	public double inv_sqrt_m00;

	public HuMoments() {
		m00 = m10 = m01 = m20 = m11 = m02 = m30 = m21 = m12 = m03 =
			mu20 = mu11 = mu02 = mu30 = mu21 = mu12 = mu03 = 0.;
	}

	/**
	 * calculates Hu moments
	 * @param subpaths contour, as a list of independently-closed loops (multiple loops when
	 *                 the silhouette's Area decomposes into more than one subpath -- the
	 *                 contour integral below is additive across loops, so summing their
	 *                 contributions before normalizing gives the moments of the union)
	 * @return double[] 7 Hu Moments
	 */
	public static double[] getHuMoments( ArrayList<ArrayList<Point2D>> subpaths ) {

		if (subpaths.isEmpty()) {
			return new double[]{0,0,0,0,0,0,0,0};
		}

		HuMoments moments = new HuMoments();

		icvCoutourMoments(subpaths, moments);

		//orientation
		double mu20d = moments.mu20 / moments.mu00;
		double mu02d = moments.mu02 / moments.mu00;
		double mu11d = moments.mu11 / moments.mu00;
		double theta = 0;
		if ( Math.abs(mu20d - mu02d) > 1.0E-13 )
			theta = 0.5 * Math.atan(2.0 * mu11d / (mu20d - mu02d) );

		double m00s = moments.inv_sqrt_m00, m00 = m00s * m00s, s2 = m00 * m00, s3 = s2 * m00s;

		double nu20 = moments.mu20 * 1.0e-9,// * s2,
		nu11 = moments.mu11 * s2,
		nu02 = moments.mu02 * 1.0e-9,// * s2,
		nu30 = moments.mu30 * s3,
		nu21 = moments.mu21 * s3, nu12 = moments.mu12 * s3, nu03 = moments.mu03 * s3;

		double t0 = nu30 + nu12;
		double t1 = nu21 + nu03;

		double q0 = t0 * t0, q1 = t1 * t1;

		double n4 = 4 * nu11;
		double s = nu20 + nu02;
		double d = nu20 - nu02;

		if (Double.isInfinite(s)) {
			System.out.println(s);
		}
		//Rotation Invariant Moments
		double[] Hu = new double[8];

		Hu[1-1] = s;
		Hu[2-1] = d * d + n4 * nu11;
		Hu[4-1] = q0 + q1;
		Hu[6-1]  = d * (q0 - q1) + n4 * t0 * t1;
		Hu[8-1] = nu11 * (q0 - q1) - d * t0 * t1;

		t0 *= q0 - 3 * q1;
		t1 *= 3 * q0 - q1;

		q0 = nu30 - 3 * nu12;
		q1 = 3 * nu21 - nu03;

		Hu[3-1] = q0 * q0 + q1 * q1;
		Hu[5-1] = q0 * t0 + q1 * t1;
		Hu[7-1] = q1 * t0 - q0 * t1;

		return Hu;
	}

	/**
	 * Accumulates the contour-integral moment sums (a00..a03) over every subpath, treating
	 * each as its own independently-closed loop (wrapping its last point back to its first),
	 * then normalizes once from the combined totals. Green's theorem contour integrals are
	 * additive across the loops making up a region, so this correctly computes the moments of
	 * the union regardless of how many subpaths the silhouette's Area happens to be split into
	 * -- summing the flattened, subpath-agnostic point list here previously connected the last
	 * point of one loop to the first point of the next with a spurious edge, corrupting the
	 * result whenever a silhouette's Area had more than one subpath.
	 */
	public static void icvCoutourMoments(ArrayList<ArrayList<Point2D>> subpaths, HuMoments moments) {
		double a00 = 0, a10 = 0, a01 = 0, a20 = 0, a11 = 0, a02 = 0, a30 = 0, a21 = 0, a12 = 0, a03 = 0;
		double xi, yi, xi2, yi2, xi_1, yi_1, xi_12, yi_12, dxy, xii_1, yii_1;

		for (List<Point2D> plist : subpaths) {
			if (plist.isEmpty()) continue;

			xi_1 = plist.get(0).getX();
			yi_1 = plist.get(0).getY();

			xi_12 = xi_1 * xi_1;
			yi_12 = yi_1 * yi_1;

			for (int i = 1, n = plist.size(); i <= n; i++) {
				Point2D p = (i < n) ? plist.get(i) : plist.get(0);
				xi = p.getX();
				yi = p.getY();

				xi2 = xi * xi;
				yi2 = yi * yi;
				dxy = xi_1 * yi - xi * yi_1;
				xii_1 = xi_1 + xi;
				yii_1 = yi_1 + yi;

				a00 += dxy;
				a10 += dxy * xii_1;
				a01 += dxy * yii_1;
				a20 += dxy * (xi_1 * xii_1 + xi2);
				a11 += dxy * (xi_1 * (yii_1 + yi_1) + xi * (yii_1 + yi));
				a02 += dxy * (yi_1 * yii_1 + yi2);
				a30 += dxy * xii_1 * (xi_12 + xi2);
				a03 += dxy * yii_1 * (yi_12 + yi2);
				a21 += dxy * (xi_12 * (3 * yi_1 + yi) + 2 * xi * xi_1 * yii_1 +
						xi2 * (yi_1 + 3 * yi));
				a12 += dxy * (yi_12 * (3 * xi_1 + xi) + 2 * yi * yi_1 * xii_1 +
						yi2 * (xi_1 + 3 * xi));

				xi_1 = xi;
				yi_1 = yi;
				xi_12 = xi2;
				yi_12 = yi2;
			}
		}

		double db1_2, db1_6, db1_12, db1_24, db1_20, db1_60;

		if ( Math.abs(a00) > 1.E-10) {
			if (a00 > 0){
				db1_2 = 0.5;
				db1_6 = 0.166666666666666666666666666666671;
				db1_12 = 0.083333333333333333333333333333333;
				db1_24 = 0.041666666666666666666666666666667;
				db1_20 = 0.05;
				db1_60 = 0.016666666666666666666666666666667;
			}
			else
			{
				db1_2 = -0.5;
				db1_6 = -0.166666666666666666666666666666671;
				db1_12 = -0.083333333333333333333333333333333;
				db1_24 = -0.041666666666666666666666666666667;
				db1_20 = -0.05;
				db1_60 = -0.016666666666666666666666666666667;
			}

			/*  spatial moments    */
			moments.m00 = a00 * db1_2;
			moments.m10 = a10 * db1_6;
			moments.m01 = a01 * db1_6;
			moments.m20 = a20 * db1_12;
			moments.m11 = a11 * db1_24;
			moments.m02 = a02 * db1_12;
			moments.m30 = a30 * db1_20;
			moments.m21 = a21 * db1_60;
			moments.m12 = a12 * db1_60;
			moments.m03 = a03 * db1_20;
		}
		icvCompleteMomentState( moments );

	}

	public static void icvCompleteMomentState( HuMoments moments ) {
		double cx = 0, cy = 0;
		double mu20, mu11, mu02;

		moments.inv_sqrt_m00 = 0;

		if( moments.m00 != 0 )
		{

			double inv_m00 = 1. / moments.m00;

			cx = moments.m10 * inv_m00;
			cy = moments.m01 * inv_m00;
			moments.inv_sqrt_m00 = Math.sqrt( inv_m00 );
		}

		moments.mu00 = moments.m00;

		/* mu20 = m20 - m10*cx */
		mu20 = moments.m20 - moments.m10 * cx;
		/* mu11 = m11 - m10*cy */
		mu11 = moments.m11 - moments.m10 * cy;
		/* mu02 = m02 - m01*cy */
		mu02 = moments.m02 - moments.m01 * cy;

		moments.mu20 = mu20;
		moments.mu11 = mu11;
		moments.mu02 = mu02;

		/* mu30 = m30 - cx*(3*mu20 + cx*m10) */
		moments.mu30 = moments.m30 - cx * (3 * mu20 + cx * moments.m10);
		mu11 += mu11;
		/* mu21 = m21 - cx*(2*mu11 + cx*m01) - cy*mu20 */
		moments.mu21 = moments.m21 - cx * (mu11 + cx * moments.m01) - cy * mu20;
		/* mu12 = m12 - cy*(2*mu11 + cy*m10) - cx*mu02 */
		moments.mu12 = moments.m12 - cy * (mu11 + cy * moments.m10) - cx * mu02;
		/* mu03 = m03 - cy*(3*mu02 + cy*m01) */
		moments.mu03 = moments.m03 - cy * (3 * mu02 + cy * moments.m01);
	}
}
