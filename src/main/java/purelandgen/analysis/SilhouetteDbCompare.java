package purelandgen.analysis;

import purelandgen.file.StreamSQliteDB;
import purelandgen.geom.ExactSilhouetteGrouping;
import purelandgen.geom.Figure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares the silhouettes contained in two .db files (A, B) by TRUE exact polygon congruence
 * (ExactSilhouetteGrouping), to answer "is every silhouette in B also present in A?". Each DB
 * row is one candidate silhouette; rows within either DB that are themselves mutually
 * congruent are first merged (so internal duplicates in one DB don't inflate the mismatch
 * count), then A's and B's distinct silhouette sets are compared via a single brute-force
 * congruence pass over their union -- no prefilter bucketing, for the same reason as
 * FullCongruenceCheck.
 *
 * Usage: ./gradlew compareSilhouetteDbs -Pa=<pathA.db> -Pb=<pathB.db> [-Peps=1e-4]
 */
public class SilhouetteDbCompare {

    public static void main(String[] args) {
        Map<String, String> opts = parseArgs(args);
        String pathA = opts.get("a");
        String pathB = opts.get("b");
        if (pathA == null || pathA.isEmpty() || pathB == null || pathB.isEmpty()) {
            System.out.println("usage: a=<pathA.db> b=<pathB.db> [eps=1e-4]");
            return;
        }
        double eps = opts.containsKey("eps") && !opts.get("eps").isEmpty()
                ? Double.parseDouble(opts.get("eps")) : ExactSilhouetteGrouping.DEFAULT_EPS;

        ArrayList<StreamSQliteDB.IdFigure> rowsA = StreamSQliteDB.loadWithIds(pathA);
        ArrayList<StreamSQliteDB.IdFigure> rowsB = StreamSQliteDB.loadWithIds(pathB);
        System.out.println("A (" + pathA + "): " + rowsA.size() + " rows");
        System.out.println("B (" + pathB + "): " + rowsB.size() + " rows");

        Map<Figure, Long> idOf = new IdentityHashMap<>();
        Set<Figure> inA = new HashSet<>();
        List<Figure> union = new ArrayList<>(rowsA.size() + rowsB.size());
        for (StreamSQliteDB.IdFigure r : rowsA) {
            idOf.put(r.figure, r.id);
            inA.add(r.figure);
            union.add(r.figure);
        }
        for (StreamSQliteDB.IdFigure r : rowsB) {
            idOf.put(r.figure, r.id);
            union.add(r.figure);
        }

        List<List<Figure>> classes = ExactSilhouetteGrouping.splitByCongruence(union, eps);

        int common = 0, aOnlyGroups = 0, bOnlyGroups = 0;
        List<Long> bOnlyIds = new ArrayList<>();
        List<Long> aOnlyIds = new ArrayList<>();
        for (List<Figure> cls : classes) {
            boolean hasA = false, hasB = false;
            for (Figure f : cls) {
                if (inA.contains(f)) hasA = true;
                else hasB = true;
            }
            if (hasA && hasB) {
                common++;
            } else if (hasB) {
                bOnlyGroups++;
                for (Figure f : cls) bOnlyIds.add(idOf.get(f));
            } else {
                aOnlyGroups++;
                for (Figure f : cls) aOnlyIds.add(idOf.get(f));
            }
        }
        Collections.sort(bOnlyIds);
        Collections.sort(aOnlyIds);

        System.out.println();
        System.out.println("A distinct silhouettes: " + (common + aOnlyGroups));
        System.out.println("B distinct silhouettes: " + (common + bOnlyGroups));
        System.out.println("共通シルエット数（A と B の両方に存在）: " + common);
        System.out.println("B にあって A に含まれないシルエット数: " + bOnlyGroups
                + "  該当 B 行の id: " + bOnlyIds);
        System.out.println("A にあって B に含まれないシルエット数: " + aOnlyGroups
                + "  該当 A 行の id: " + aOnlyIds);
        System.out.println();
        System.out.println(bOnlyGroups == 0
                ? "=> A は B のすべてのシルエットを含んでいる。"
                : "=> A は B の一部のシルエットを含んでいない（上記 B id を参照）。");
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (eq > 0) m.put(a.substring(0, eq), a.substring(eq + 1));
        }
        return m;
    }
}
