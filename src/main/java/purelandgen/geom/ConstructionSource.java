package purelandgen.geom;

import java.util.Arrays;

/**
 * Records how a folding line was discovered.
 * Used to calculate "discoverability" - how easy it is for beginners to find this fold.
 */
public class ConstructionSource {

    /** The axiom used (e.g., "AXIOM1_THROUGH_2POINTS") */
    public String axiom;

    /** Landmark identifiers used (e.g., ["v0", "v2"] or ["e1", "e3"]) */
    public String[] landmarks;

    /** Positions of landmarks for overlap detection (transient, not serialized) */
    transient double[][] positions;

    public ConstructionSource() {
    }

    /**
     * Constructor for deserialization (positions not needed).
     */
    public ConstructionSource(String axiom, String[] landmarks) {
        this.axiom = axiom;
        this.landmarks = landmarks;
        this.positions = null;
    }

    /**
     * Constructor with position information for overlap detection.
     */
    public ConstructionSource(String axiom, String[] landmarks, double[][] positions) {
        this.axiom = axiom;
        this.landmarks = landmarks;
        this.positions = positions;
    }

    /**
     * Checks if this source has the same positions as another source.
     * Used to filter out duplicate sources from overlapping landmarks.
     */
    public boolean hasSamePositions(ConstructionSource other) {
        if (!this.axiom.equals(other.axiom)) {
            return false;
        }
        if (this.positions == null || other.positions == null) {
            return false;
        }
        if (this.positions.length != other.positions.length) {
            return false;
        }
        // Sort positions for comparison (order-independent)
        double[][] sorted1 = sortPositions(this.positions);
        double[][] sorted2 = sortPositions(other.positions);
        for (int i = 0; i < sorted1.length; i++) {
            if (!epsilonEqual(sorted1[i], sorted2[i])) {
                return false;
            }
        }
        return true;
    }

    private static double[][] sortPositions(double[][] positions) {
        double[][] copy = Arrays.copyOf(positions, positions.length);
        Arrays.sort(copy, (a, b) -> {
            int cmp = Double.compare(a[0], b[0]);
            return cmp != 0 ? cmp : Double.compare(a[1], b[1]);
        });
        return copy;
    }

    private static boolean epsilonEqual(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < GeomUtil.EPS
                && Math.abs(a[1] - b[1]) < GeomUtil.EPS;
    }

    @Override
    public String toString() {
        return axiom + ": " + String.join(", ", landmarks);
    }
}
