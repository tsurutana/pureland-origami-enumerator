package purelandgen.analysis;

import purelandgen.file.StreamSQliteDB;
import purelandgen.geom.*;
import purelandgen.matching.MomentOperator;

import java.io.*;
import java.util.*;

/**
 * 3回折りの全経路グラフを分析し、各(T,H)ペアに対してA/B/Dを計算してCSVに出力する。
 *
 * T : 3手目のシルエットグループ（山谷折りのみ、折り目付け除外）
 * H : 2手目のシルエットグループ（山谷＋折り目付け）
 *
 * A(T,H) : 2手目シルエットが H であるTへの3手経路数
 * B(T,H) : Tへの全経路数 − A(T,H)
 * D(T,H) : Hに至る経路のうちTに到達できない経路数
 *
 * 難易度分類（各T/Hペア・Tの foldable/unfoldable 別）:
 *   easy    : A ≥ 2
 *   medium  : A = 1 ∧ D = 0
 *   hard    : A = 1 ∧ D ≥ 1
 *   removed : A = 0 (有効なヒントが存在しない)
 *
 * 出力:
 *   path_graph.csv           — T_id, H_id, A, B, D（全体）
 *   path_graph_map.csv       — T/H id と matchingValue の対応
 *   path_graph_difficulty.csv — foldable vs unfoldable の難易度クロス表
 */
public class PathGraphAnalyzer {

    private static final boolean[] CONSTRUCTIONS = {true, true, true, false, false, false, false};
    private static final boolean[] TECHS_ALL     = {true, true, true};
    private static final boolean[] TECHS_VM_ONLY = {true, true, false};

    private static final double SIL_TOL = 1e-6;

    private static final int REMOVED = 0, EASY = 1, MEDIUM = 2, HARD = 3;

    // ---------------------------------------------------------------
    // Gen-2 ノード
    // ---------------------------------------------------------------
    private static class Gen2Node {
        final Figure figure;
        double hSil;
        long pathCount;
        final Map<Double, Long> countInT_foldable   = new HashMap<>();
        final Map<Double, Long> countInT_unfoldable = new HashMap<>();
        Gen2Node(Figure f) { this.figure = f; }
    }

    // ---------------------------------------------------------------
    // ユーティリティ
    // ---------------------------------------------------------------
    private double matchingValue(Figure f) {
        return MomentOperator.getMatchingValue(Silhouette.getSilhouette(f));
    }

    private String figureKey(Figure f) {
        return f.sd + "|" + f.faces.size() + "|" + f.edges.size();
    }

    private ArrayList<Figure> fold(Figure f, boolean[] techs) {
        FoldingMachine fm = new FoldingMachine(CONSTRUCTIONS, techs);
        ArrayList<Figure> input = new ArrayList<>();
        input.add(f);
        return fm.FoldOnce(input);
    }

    private int findGroup(List<Double> sortedGroups, double val) {
        int lo = 0, hi = sortedGroups.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            double diff = sortedGroups.get(mid) - val;
            if (Math.abs(diff) < SIL_TOL) return mid;
            if (diff < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        if (lo > 0 && Math.abs(sortedGroups.get(lo - 1) - val) < SIL_TOL) return lo - 1;
        if (lo < sortedGroups.size() && Math.abs(sortedGroups.get(lo) - val) < SIL_TOL) return lo;
        return -1;
    }

    private int addGroup(List<Double> sortedGroups, double val) {
        int idx = findGroup(sortedGroups, val);
        if (idx >= 0) return idx;
        int pos = 0;
        while (pos < sortedGroups.size() && sortedGroups.get(pos) < val) pos++;
        sortedGroups.add(pos, val);
        return pos;
    }

    /**
     * T グループの難易度を決定する（最良の H を選んだときの難易度）。
     * A[hi], D[hi] は hi=0..nH-1 に対応。
     */
    private int classify(long[] A_row, long[] D_row, int nH) {
        boolean hasMedium = false, hasHard = false;
        for (int hi = 0; hi < nH; hi++) {
            if (A_row[hi] >= 2) return EASY;
            if (A_row[hi] == 1) {
                if (D_row[hi] == 0) hasMedium = true;
                else hasHard = true;
            }
        }
        if (hasMedium) return MEDIUM;
        if (hasHard) return HARD;
        return REMOVED;
    }

    // ---------------------------------------------------------------
    // メイン分析
    // ---------------------------------------------------------------
    public void analyze(Figure initial, String csvPath) throws IOException {

        // ===== Gen-1 =====
        ArrayList<Figure> gen1 = fold(initial, TECHS_ALL);
        System.out.println("Gen-1: " + gen1.size() + " figures");

        // ===== Gen-2 =====
        Map<String, Gen2Node> gen2Map = new LinkedHashMap<>();
        for (Figure f1 : gen1) {
            for (Figure f2 : fold(f1, TECHS_ALL)) {
                gen2Map.computeIfAbsent(figureKey(f2), k -> new Gen2Node(f2)).pathCount += 1;
            }
        }
        long gen2Unfoldable = gen2Map.values().stream().filter(n -> !n.figure.isFoldable).count();
        System.out.println("Gen-2: " + gen2Map.size() + " unique figures (" + gen2Unfoldable + " unfoldable)");

        for (Gen2Node node : gen2Map.values())
            node.hSil = matchingValue(node.figure);

        // ===== Gen-3 : foldable / unfoldable を別カウント =====
        int done = 0;
        for (Gen2Node f2node : gen2Map.values()) {
            for (Figure f3 : fold(f2node.figure, TECHS_VM_ONLY)) {
                double tSil = matchingValue(f3);
                if (f3.isFoldable)
                    f2node.countInT_foldable.merge(tSil, 1L, Long::sum);
                else
                    f2node.countInT_unfoldable.merge(tSil, 1L, Long::sum);
            }
            done++;
            if (done % 100 == 0)
                System.out.println("Gen-3 progress: " + done + "/" + gen2Map.size());
        }

        // ===== シルエットグループ化 =====
        List<Double> tGroups = new ArrayList<>();
        List<Double> hGroups = new ArrayList<>();
        for (Gen2Node node : gen2Map.values()) {
            addGroup(hGroups, node.hSil);
            for (double t : node.countInT_foldable.keySet())   addGroup(tGroups, t);
            for (double t : node.countInT_unfoldable.keySet()) addGroup(tGroups, t);
        }
        int nT = tGroups.size(), nH = hGroups.size();
        System.out.println("T groups: " + nT + ", H groups: " + nH);

        // ===== A/D 集計 =====
        long[] totalT_f = new long[nT];
        long[] totalT_u = new long[nT];
        long[][] A_f     = new long[nT][nH];
        long[][] A_u     = new long[nT][nH];
        long[][] D_f     = new long[nT][nH];
        long[][] D_u     = new long[nT][nH];
        long[][] D_total = new long[nT][nH]; // Tに到達できない経路（foldable+unfoldable両方不到達）

        for (Gen2Node f2 : gen2Map.values()) {
            int hi = findGroup(hGroups, f2.hSil);
            if (hi < 0) continue;

            Set<Integer> reachedF = new HashSet<>(), reachedU = new HashSet<>();

            for (Map.Entry<Double, Long> e : f2.countInT_foldable.entrySet()) {
                int ti = findGroup(tGroups, e.getKey());
                if (ti < 0) continue;
                long c = f2.pathCount * e.getValue();
                A_f[ti][hi] += c;
                totalT_f[ti] += c;
                reachedF.add(ti);
            }
            for (Map.Entry<Double, Long> e : f2.countInT_unfoldable.entrySet()) {
                int ti = findGroup(tGroups, e.getKey());
                if (ti < 0) continue;
                long c = f2.pathCount * e.getValue();
                A_u[ti][hi] += c;
                totalT_u[ti] += c;
                reachedU.add(ti);
            }
            for (int ti = 0; ti < nT; ti++) {
                if (!reachedF.contains(ti)) D_f[ti][hi] += f2.pathCount;
                if (!reachedU.contains(ti)) D_u[ti][hi] += f2.pathCount;
                if (!reachedF.contains(ti) && !reachedU.contains(ti))
                    D_total[ti][hi] += f2.pathCount;
            }
        }

        // ===== Per-T foldable: best-H selection and B_fold =====
        long[] bestA_fold  = new long[nT];
        long[] bestD_fold  = new long[nT];
        int[]  bestHi_fold = new int[nT];
        long[] B_fold      = new long[nT];
        Arrays.fill(bestHi_fold, -1);
        Arrays.fill(bestD_fold, Long.MAX_VALUE);
        for (int ti = 0; ti < nT; ti++) {
            for (int hi = 0; hi < nH; hi++) {
                long a = A_f[ti][hi], d = D_f[ti][hi];
                if (a > bestA_fold[ti] || (a == bestA_fold[ti] && d < bestD_fold[ti])) {
                    bestA_fold[ti]  = a;
                    bestD_fold[ti]  = d;
                    bestHi_fold[ti] = hi;
                }
            }
            B_fold[ti] = totalT_f[ti] - bestA_fold[ti];
        }

        // ===== CSV 出力 (全(T,H)ペアを出力) =====
        try (PrintWriter pw = new PrintWriter(new FileWriter(csvPath))) {
            pw.println("T_id,H_id,A,A_fold,A_unfold,B,D,D_fold,D_unfold");
            for (int ti = 0; ti < nT; ti++) {
                long totalT = totalT_f[ti] + totalT_u[ti];
                for (int hi = 0; hi < nH; hi++) {
                    long A = A_f[ti][hi] + A_u[ti][hi];
                    pw.printf("%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                        ti + 1, hi + 1,
                        A, A_f[ti][hi], A_u[ti][hi],
                        totalT - A,
                        D_total[ti][hi], D_f[ti][hi], D_u[ti][hi]);
                }
            }
        }

        // シルエット対応表
        String mapPath = csvPath.replace(".csv", "_map.csv");
        try (PrintWriter pw = new PrintWriter(new FileWriter(mapPath))) {
            pw.println("type,id,matchingValue");
            for (int i = 0; i < nT; i++)
                pw.printf("T,%d,%.10f%n", i + 1, tGroups.get(i));
            for (int i = 0; i < nH; i++)
                pw.printf("H,%d,%.10f%n", i + 1, hGroups.get(i));
        }

        // ===== 難易度分類 =====
        // rowClass[ti]: total (foldable+unfoldable 合算) の難易度
        // colClass[ti]: foldable のみの難易度 (-1 = foldable 図形なし)
        int[] rowClass = new int[nT]; // 行: total
        int[] colClass = new int[nT]; // 列: foldable
        Arrays.fill(colClass, -1);

        for (int ti = 0; ti < nT; ti++) {
            // total 用: A_total[hi] = A_f[ti][hi] + A_u[ti][hi]
            long[] A_total_row = new long[nH];
            for (int hi = 0; hi < nH; hi++)
                A_total_row[hi] = A_f[ti][hi] + A_u[ti][hi];
            rowClass[ti] = classify(A_total_row, D_total[ti], nH);
            if (totalT_f[ti] > 0)
                colClass[ti] = classify(A_f[ti], D_f[ti], nH);
        }

        // クロス表: cross[rowClass][colClass]  0=removed,1=easy,2=medium,3=hard
        // colClass=-1 (foldable なし) は noFoldableRow に別集計
        int[][] cross = new int[4][4];
        int[] noFoldableRow = new int[4]; // foldable 図形なし → rowClass のみ

        for (int ti = 0; ti < nT; ti++) {
            int rc = rowClass[ti], cc = colClass[ti];
            if (cc >= 0) cross[rc][cc]++;
            else         noFoldableRow[rc]++;
        }

        // 難易度 CSV
        String[] classLabels = {"removed", "easy", "medium", "hard"};
        String diffPath = csvPath.replace(".csv", "_difficulty.csv");
        try (PrintWriter pw = new PrintWriter(new FileWriter(diffPath))) {
            pw.println("total\\foldable,easy,medium,hard,removed,(no_foldable)");
            for (int rc = 0; rc < 4; rc++) {
                pw.printf("%s,%d,%d,%d,%d,%d%n", classLabels[rc],
                    cross[rc][1], cross[rc][2], cross[rc][3], cross[rc][0], noFoldableRow[rc]);
            }
        }

        // per-T foldable summary — B_fold reported separately per paper description
        String perTPath = csvPath.replace(".csv", "_per_target_foldable.csv");
        try (PrintWriter pw = new PrintWriter(new FileWriter(perTPath))) {
            pw.println("T_id,H_best_id,A_fold,B_fold,D_fold,difficulty_fold");
            for (int ti = 0; ti < nT; ti++) {
                if (totalT_f[ti] == 0) continue;
                String diff = colClass[ti] >= 0 ? classLabels[colClass[ti]] : "-";
                pw.printf("%d,%d,%d,%d,%d,%s%n",
                    ti + 1, bestHi_fold[ti] >= 0 ? bestHi_fold[ti] + 1 : -1,
                    bestA_fold[ti], B_fold[ti], bestD_fold[ti], diff);
            }
        }

        // コンソール出力
        System.out.println();
        System.out.println("Written: " + csvPath);
        System.out.println("Written: " + mapPath);
        System.out.println("Written: " + diffPath);
        System.out.println();
        System.out.println("=== T難易度クロス表 ===");
        System.out.println("行=total(fold+unfold)の難易度、列=foldableのみの難易度");
        System.out.printf("%-10s | %7s %7s %7s %7s %12s%n",
            "tot\\fold", "easy", "medium", "hard", "removed", "(no_foldable)");
        System.out.println("---------------------------------------------------------------");
        for (int rc = 0; rc < 4; rc++) {
            System.out.printf("%-10s | %7d %7d %7d %7d %12d%n", classLabels[rc],
                cross[rc][1], cross[rc][2], cross[rc][3], cross[rc][0], noFoldableRow[rc]);
        }
        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("T silhouette classes: " + nT);
        System.out.println("H silhouette classes: " + nH);
        System.out.println("Written: " + perTPath);
        long sumBfold = 0, maxBfold = 0, cntBgt0 = 0;
        for (int ti = 0; ti < nT; ti++) {
            if (totalT_f[ti] == 0) continue;
            sumBfold += B_fold[ti];
            if (B_fold[ti] > maxBfold) maxBfold = B_fold[ti];
            if (B_fold[ti] > 0) cntBgt0++;
        }
        System.out.printf("Hint-bypassing B_fold: total=%d, max=%d, targets with B>0: %d%n",
            sumBfold, maxBfold, cntBgt0);

        // ===== 各列の T シルエット集合を構築 =====
        Set<Double> easyColTSils   = new HashSet<>();
        Set<Double> mediumColTSils = new HashSet<>();
        Set<Double> hardTSils      = new HashSet<>();
        Set<Double> easyHardTSils  = new HashSet<>(); // easy行 ∩ hard列
        for (int ti = 0; ti < nT; ti++) {
            int cc = colClass[ti];
            if (cc == EASY)   easyColTSils.add(tGroups.get(ti));
            if (cc == MEDIUM) mediumColTSils.add(tGroups.get(ti));
            if (cc == HARD) {
                hardTSils.add(tGroups.get(ti));
                if (rowClass[ti] == EASY) easyHardTSils.add(tGroups.get(ti));
            }
        }

        // ===== 1パスで全 DB を出力 =====
        System.out.println("\nCollecting figures (second gen-3 pass)...");
        List<ArrayList<Figure>> collected = _collectByTSil(gen2Map,
            new CollectTarget(easyColTSils,   true),   // easy列 foldableのみ
            new CollectTarget(mediumColTSils, true),   // medium列 foldableのみ
            new CollectTarget(hardTSils,      null),   // hard列 全図形
            new CollectTarget(easyHardTSils,  null),   // easy行∩hard列 全図形
            new CollectTarget(easyHardTSils,  false)   // easy行∩hard列 unfoldableのみ
        );
        ArrayList<Figure> easyFigures        = collected.get(0);
        ArrayList<Figure> mediumFigures      = collected.get(1);
        ArrayList<Figure> hardFigures        = collected.get(2);
        ArrayList<Figure> easyHardFigures    = collected.get(3);
        ArrayList<Figure> easyHardUnfoldFigs = collected.get(4);

        _exportDb(csvPath, "_easy_foldable.db",             easyFigures,        easyColTSils.size());
        _exportDb(csvPath, "_medium_foldable.db",           mediumFigures,      mediumColTSils.size());
        _exportDb(csvPath, "_hard_foldable.db",             hardFigures,        hardTSils.size());
        _exportDb(csvPath, "_easy_total_hard_foldable.db",  easyHardFigures,    easyHardTSils.size());
        _exportDb(csvPath, "_easy_total_hard_unfoldable.db",easyHardUnfoldFigs, easyHardTSils.size());

        // ===== B_fold 降順ソート DB 出力 =====
        System.out.println("\nCollecting foldable T figures (third gen-3 pass, grouped by B_fold)...");
        Map<Integer, ArrayList<Figure>> figsByTi = _collectFoldableGrouped(gen2Map, tGroups);

        // ti を B_fold 降順にソート（foldable 図形あり T のみ）
        List<Integer> tiByB = new ArrayList<>();
        for (int ti = 0; ti < nT; ti++) if (totalT_f[ti] > 0) tiByB.add(ti);
        tiByB.sort((a, b) -> Long.compare(B_fold[b], B_fold[a]));

        ArrayList<Figure> sortedFigs = new ArrayList<>();
        for (int ti : tiByB) {
            ArrayList<Figure> figs = figsByTi.getOrDefault(ti, new ArrayList<>());
            sortedFigs.addAll(figs);
        }
        String sortedDbPath = csvPath.replace(".csv", "_sorted_by_b_foldable.db");
        StreamSQliteDB.export(sortedFigs, sortedDbPath);
        System.out.println("Written: " + sortedDbPath
            + " (" + sortedFigs.size() + " figures, " + tiByB.size() + " T groups)");
    }

    private void _exportDb(String csvPath, String suffix, ArrayList<Figure> figures, int silCount) {
        if (figures.isEmpty()) return;
        String dbPath = csvPath.replace(".csv", suffix);
        StreamSQliteDB.export(figures, dbPath);
        System.out.println("Written: " + dbPath + " (" + figures.size() + " figures, "
            + silCount + " T silhouette classes)");
    }

    /**
     * gen-2 図形を再折りして、foldable な gen-3 図形を T グループインデックス(ti) でグルーピングして返す。
     * B_fold 降順ソート DB 出力用。
     */
    private Map<Integer, ArrayList<Figure>> _collectFoldableGrouped(
            Map<String, Gen2Node> gen2Map, List<Double> tGroups) {
        Map<Integer, Map<String, Figure>> byTi = new LinkedHashMap<>();
        int done = 0;
        for (Gen2Node f2node : gen2Map.values()) {
            for (Figure f3 : fold(f2node.figure, TECHS_VM_ONLY)) {
                if (!f3.isFoldable) continue;
                int ti = findGroup(tGroups, matchingValue(f3));
                if (ti < 0) continue;
                byTi.computeIfAbsent(ti, k -> new LinkedHashMap<>())
                    .putIfAbsent(figureKey(f3), f3);
            }
            done++;
            if (done % 10 == 0)
                System.out.println("  collecting (grouped): " + done + "/" + gen2Map.size());
        }
        Map<Integer, ArrayList<Figure>> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Figure>> e : byTi.entrySet())
            result.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        return result;
    }

    /** 収集条件: 対象シルエット集合 + foldability フィルタ (null=両方, true=foldableのみ, false=unfoldableのみ) */
    private static class CollectTarget {
        final Set<Double> sils;
        final Boolean foldableFilter; // null=both, true=foldable only, false=unfoldable only
        CollectTarget(Set<Double> sils, Boolean foldableFilter) {
            this.sils = sils; this.foldableFilter = foldableFilter;
        }
    }

    /**
     * gen-2 図形を再折りして、各 CollectTarget の条件に合う gen-3 図形を重複除去して収集する。
     * 複数ターゲットを1パスで処理する。
     */
    private List<ArrayList<Figure>> _collectByTSil(
            Map<String, Gen2Node> gen2Map, CollectTarget... targets) {
        @SuppressWarnings("unchecked")
        Map<String, Figure>[] seen = new LinkedHashMap[targets.length];
        for (int i = 0; i < seen.length; i++) seen[i] = new LinkedHashMap<>();

        int done = 0;
        for (Gen2Node f2node : gen2Map.values()) {
            for (Figure f3 : fold(f2node.figure, TECHS_VM_ONLY)) {
                double tSil = matchingValue(f3);
                String key = figureKey(f3);
                for (int i = 0; i < targets.length; i++) {
                    CollectTarget ct = targets[i];
                    if (ct.foldableFilter != null && ct.foldableFilter != f3.isFoldable) continue;
                    for (double t : ct.sils) {
                        if (Math.abs(tSil - t) < SIL_TOL) {
                            seen[i].putIfAbsent(key, f3);
                            break;
                        }
                    }
                }
            }
            done++;
            if (done % 10 == 0)
                System.out.println("  collecting: " + done + "/" + gen2Map.size());
        }
        List<ArrayList<Figure>> result = new ArrayList<>();
        for (Map<String, Figure> m : seen) result.add(new ArrayList<>(m.values()));
        return result;
    }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "path_graph.csv";
        new PathGraphAnalyzer().analyze(new Figure(200, 200), out);
    }
}
