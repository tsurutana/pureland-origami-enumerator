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
 * 知覚グラフとの対応:
 *   - Gen-1/Gen-2 は状態の完全同一視（ExactStateKey）のみで重複除去する。山谷折りは
 *     R（重なり順）が異なるため別状態として保持する ── 3手目でどちらが可視/可折になるかが
 *     山谷ごとに変わりうるため、2手目時点では両方を残しておく必要がある。
 *   - 3手目（Tへの最終折り）は、各2手目状態ごとに生成した生候補を crease-pattern-only key
 *     （ExactStateKey.computeCreasePatternKey、Rを含まない＝山谷の向き違いだけを同一視）で
 *     グルーピングし、そのグループ内でいずれか一方でも isFoldable なら「到達可能」とみなす
 *     （∃ 山谷割当規則）。これにより同一クリース線の山谷を別経路として二重計上しない。
 *   - 2手目自体が isFoldable=false の場合、そこから続く3手目候補は foldable プールに
 *     計上しない（その2手目に人間は到達できないため）。unfoldable プール（unfiltered 分類用）
 *     には引き続き計上する。
 *   - 注: 異なる2手目状態（例えば同一2手線の山と谷）が独立に同一Tへ「同一の3手目クリース線」
 *     で到達するケースは、本実装では別経路として数える（山谷ごとに可視性が変わり得るため
 *     3手目側で生成される候補集合自体が異なりうる ── 経路をクリース線列だけで完全に同一視する
 *     には全ステップでの線識別の追跡が要り、今回のスコープ外）。山/谷マージは全体の96.5%を
 *     占めるため、この残差は小さいと想定。
 *
 * T/H シルエット同一性（scalar descriptorによる誤分類を避けるため）:
 *   T・H とも ExactSilhouetteGrouping（厳密多角形合同判定、D4×flip 正規化 + Procrustes
 *   最適回転、閾値1e-4）による分類のみを真実とする。スカラー記述子（matchingValue）の
 *   事前バケット化（旧 addGroup/findGroup、SIL_TOL=1e-6）は一切使わない ── 挿入順依存の
 *   貪欲最近傍マッチは、本当は別の実シルエットなのに数値的に近い既存アンカーへ誤って
 *   合流させうる（確認済み：旧方式とExactSilhouetteGroupingの事前バケット化を混在させた
 *   結果、本来あり得ない「厳密版の分類数が旧版より少ない」という矛盾が生じた）。全ての
 *   シルエット比較箇所（H分類、T分類、_collectByTSil/_collectFoldableGroupedでの再照合）を
 *   ck（crease-pattern key）→厳密T-id、Figure→厳密H-id という単一のマッピングに統一する
 *   ことで、この種の不整合を構造的に排除する。
 *
 * 出力:
 *   path_graph.csv           — T_id, H_id, A, B, D（全体）
 *   path_graph_map.csv       — T/H id と（参考用）代表 matchingValue の対応
 *   path_graph_difficulty.csv — foldable vs unfoldable の難易度クロス表
 */
public class PathGraphAnalyzer {

    private static final boolean[] CONSTRUCTIONS = {true, true, true, false, false, false, false};
    private static final boolean[] TECHS_ALL     = {true, true, true};
    private static final boolean[] TECHS_VM_ONLY = {true, true, false};

    private static final int REMOVED = 0, EASY = 1, MEDIUM = 2, HARD = 3;

    // ---------------------------------------------------------------
    // Gen-2 ノード
    // ---------------------------------------------------------------
    private static class Gen2Node {
        final Figure figure;
        int hi = -1; // 厳密合同判定による H-id（Gen-2 グルーピング直後に確定）
        long pathCount;
        // 厳密T-idでキーした、Tへの経路寄与（最終的にA/D集計で使う唯一の値）。
        final Map<Integer, Long> countInT_foldable   = new HashMap<>();
        final Map<Integer, Long> countInT_unfoldable = new HashMap<>();
        Gen2Node(Figure f) { this.figure = f; }
    }

    // ---------------------------------------------------------------
    // ユーティリティ
    // ---------------------------------------------------------------
    private double matchingValue(Figure f) {
        return MomentOperator.getMatchingValue(Silhouette.getSilhouette(f));
    }

    private ArrayList<Figure> fold(Figure f, boolean[] techs) {
        FoldingMachine fm = new FoldingMachine(CONSTRUCTIONS, techs);
        ArrayList<Figure> input = new ArrayList<>();
        input.add(f);
        return fm.FoldOnce(input);
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

        // Full state identity (crease pattern + overlap order R) for Gen-1/Gen-2 dedup, so
        // that valley/mountain variants of a line (which differ only in R) are kept as
        // separate states here, and states that merely collide in sd/faces/edges by
        // coincidence (not true duplicates) are NOT merged. See the class javadoc.
        FoldingMachine.REQUIRE_EXACT_KEY = true;

        // ===== Gen-1 =====
        ArrayList<Figure> gen1 = fold(initial, TECHS_ALL);
        long gen1RawUnfoldable = gen1.stream().filter(f -> !f.isFoldable).count();
        System.out.println("Gen-1: " + gen1.size() + " figures (" + gen1RawUnfoldable + " unfoldable)");

        // ===== Gen-2 =====
        Map<String, Gen2Node> gen2Map = new LinkedHashMap<>();
        int gen2RawTotal = 0;
        int gen2RawUnfoldable = 0;
        for (Figure f1 : gen1) {
            for (Figure f2 : fold(f1, TECHS_ALL)) {
                gen2RawTotal++;
                if (!f2.isFoldable) gen2RawUnfoldable++;
                gen2Map.computeIfAbsent(ExactStateKey.compute(f2).toString(), k -> new Gen2Node(f2)).pathCount += 1;
            }
        }
        long gen2Unfoldable = gen2Map.values().stream().filter(n -> !n.figure.isFoldable).count();
        System.out.println("Gen-2 raw (pre-dedup): " + gen2RawTotal + " candidates (" + gen2RawUnfoldable + " unfoldable)");
        System.out.println("Gen-2: " + gen2Map.size() + " unique figures (" + gen2Unfoldable + " unfoldable)");

        // ===== H グルーピング (厳密合同判定のみ) =====
        List<Figure> gen2Figures = new ArrayList<>();
        for (Gen2Node n : gen2Map.values()) gen2Figures.add(n.figure);
        List<List<Figure>> hClasses = ExactSilhouetteGrouping.group(gen2Figures);
        Map<Figure, Integer> figureToHi = new IdentityHashMap<>();
        for (int hi = 0; hi < hClasses.size(); hi++)
            for (Figure f : hClasses.get(hi)) figureToHi.put(f, hi);
        for (Gen2Node n : gen2Map.values()) n.hi = figureToHi.get(n.figure);
        int nH = hClasses.size();
        System.out.println("H groups (exact congruence): " + nH);
        {
            int singleRealization = 0;
            for (List<Figure> cls : hClasses) if (cls.size() == 1) singleRealization++;
            System.out.println("  of which single-realization (group size 1): " + singleRealization
                    + "  (Gen-2 states: " + gen2Figures.size() + ")");
        }

        // ===== Gen-3 : foldable / unfoldable を別カウント =====
        // 知覚グラフ：同一2手目状態から生成された3手目の生候補を、山谷の向き違いだけを
        // 同一視する crease-pattern-only key (ck) でグルーピングし（∃ 山谷割当規則）、
        // グループ単位で1回だけ計上する。2手目自体が isFoldable=false なら、
        // そこから続く3手目は foldable プールへは計上しない（unfoldable プールのみ）。
        //
        // 重要: ck は「面の形状の多重集合」だけのキーで、面同士の隣接関係を含まない
        // （ExactStateKey.computeCreasePatternKey() 自身のjavadocが明記: "NOT for
        // shape-identity dedup"）。このため、別々のGen-2親から得られた、本当は無関係な
        // （シルエットも異なる）クリースパターンが、たまたま同じ ck 文字列になる衝突が
        // 実際に発生することを確認済み（706個のck代表を厳密T分類すると412個にしかならず、
        // 1429状態の全数検証（493、見逃しマージ0件）と比べて81個のシルエットが丸ごと
        // 欠落していた。欠落した全ケースで「別Gen-2親のck代表」がrawGapで数万〜無限大
        // という、明らかに別形状の値を示した）。
        //
        // 対策: ck による「親をまたいだ」代表Figureの統合は行わない（ローカルな重複除去
        // ――同一親内でのv/m統合――にのみ ck を使う）。全ての (親, ck) 代表Figureを
        // 別々のまま厳密合同判定のプールに入れ、実際の幾何比較だけで真の同一性を判定する。
        Map<Gen2Node, Map<String, Figure>> ckRepByNode = new LinkedHashMap<>();
        Map<Gen2Node, Map<String, Boolean>> foldableByNode = new LinkedHashMap<>();
        // Separate from ckRepByNode (whose representative, kept by putIfAbsent/first-seen, can be
        // the UNFOLDABLE m/v variant even when the ck group also contains a foldable one) --
        // this instead remembers an actually-foldable f3 instance per (node,ck), purely for later
        // DB export. ckRepByNode / allTCandidates / tClasses / figureToTi (the classification
        // pipeline whose 493/442 counts are already verified) are left untouched by this.
        Map<Gen2Node, Map<String, Figure>> ckFoldableRepByNode = new LinkedHashMap<>();
        List<Figure> allTCandidates = new ArrayList<>();
        int done = 0;
        for (Gen2Node f2node : gen2Map.values()) {
            Map<String, Boolean> groupFoldable = new HashMap<>();
            Map<String, Figure> ckRepLocal = new LinkedHashMap<>();
            Map<String, Figure> ckFoldableRepLocal = new LinkedHashMap<>();
            for (Figure f3 : fold(f2node.figure, TECHS_VM_ONLY)) {
                String ck = ExactStateKey.computeCreasePatternKey(f3);
                groupFoldable.merge(ck, f3.isFoldable, (a, b) -> a || b);
                ckRepLocal.putIfAbsent(ck, f3);
                if (f3.isFoldable) ckFoldableRepLocal.putIfAbsent(ck, f3);
            }
            ckRepByNode.put(f2node, ckRepLocal);
            ckFoldableRepByNode.put(f2node, ckFoldableRepLocal);
            foldableByNode.put(f2node, groupFoldable);
            allTCandidates.addAll(ckRepLocal.values());
            done++;
            if (done % 100 == 0)
                System.out.println("Gen-3 progress: " + done + "/" + gen2Map.size());
        }

        // ===== T グルーピング (厳密合同判定のみ、ck文字列には依存しない) =====
        List<List<Figure>> tClasses = ExactSilhouetteGrouping.group(allTCandidates);

        // Diagnostic export: T classes where 2+ DIFFERENT crease-pattern representatives
        // (different internal fold structure, from possibly different Gen-2 parents) were
        // judged exactly congruent -- i.e. real merges, not the earlier reviewer-conflation
        // splits. Exported for visual spot-checking in the GUI, largest-merge first, since this
        // run's T count (412) differs substantially from the ~492/493 found elsewhere on a
        // nominally-equivalent population and needs confirming before being trusted.
        {
            List<List<Figure>> merged = new ArrayList<>();
            for (List<Figure> cls : tClasses) if (cls.size() > 1) merged.add(cls);
            merged.sort((a, b) -> Integer.compare(b.size(), a.size()));
            String dir = "rebuttal/pathgraph_merge_db";
            new java.io.File(dir).mkdirs();
            int toExport = Math.min(10, merged.size());
            for (int i = 0; i < toExport; i++) {
                List<Figure> cls = merged.get(i);
                StreamSQliteDB.export(new ArrayList<>(cls), dir + "/merged" + (i + 1) + "_n" + cls.size() + ".db");
            }
            System.out.println("DIAGNOSTIC: " + merged.size() + " of " + tClasses.size()
                    + " T classes merged 2+ crease-pattern representatives; exported the "
                    + toExport + " largest to " + dir + "/ for visual spot-check.");
        }

        // Figure (object identity) -> Ti. Safe: tClasses partitions allTCandidates, whose
        // elements are the SAME object instances held in each node's ckRepLocal map (no string
        // re-matching, so the ck-collision risk documented above cannot resurface here).
        Map<Figure, Integer> figureToTi = new IdentityHashMap<>();
        for (int ti = 0; ti < tClasses.size(); ti++)
            for (Figure f : tClasses.get(ti)) figureToTi.put(f, ti);
        int nT = tClasses.size();
        System.out.println("T groups: " + nT + ", H groups: " + nH);

        // 各 (Gen2Node, ck) の foldable フラグを厳密T-idで再キー（countInT_foldable/
        // unfoldable が以降のA/D集計で使う唯一の表現）。ck文字列の再照合は一切行わず、
        // ckRepByNode に保持した「その場で使った具体的なFigureインスタンス」の identity を
        // そのまま figureToTi に引くので、未知キーが出ることは構造的にない。
        // ti -> an actually-foldable Figure instance, for DB export only (see ckFoldableRepByNode
        // above). Guaranteed to gain an entry for every ti with totalT_f[ti]>0 below: that
        // requires some (node,ck) with node.figure.isFoldable && groupFoldable.get(ck), which by
        // construction has a corresponding entry in that node's ckFoldableRepLocal.
        Map<Integer, Figure> foldableRepByTi = new LinkedHashMap<>();
        for (Gen2Node node : gen2Map.values()) {
            Map<String, Figure> ckRepLocal = ckRepByNode.get(node);
            Map<String, Figure> ckFoldableRepLocal = ckFoldableRepByNode.get(node);
            Map<String, Boolean> groupFoldable = foldableByNode.get(node);
            for (String ck : ckRepLocal.keySet()) {
                Integer ti = figureToTi.get(ckRepLocal.get(ck));
                if (ti == null)
                    throw new IllegalStateException("ck " + ck + " representative missing from exact T-grouping result -- coverage bug");
                boolean foldable = node.figure.isFoldable && groupFoldable.get(ck);
                if (foldable) {
                    node.countInT_foldable.merge(ti, 1L, Long::sum);
                    Figure foldableF3 = ckFoldableRepLocal.get(ck);
                    if (foldableF3 != null) foldableRepByTi.putIfAbsent(ti, foldableF3);
                } else {
                    node.countInT_unfoldable.merge(ti, 1L, Long::sum);
                }
            }
        }

        // ===== A/D 集計 =====
        long[] totalT_f = new long[nT];
        long[] totalT_u = new long[nT];
        long[][] A_f     = new long[nT][nH];
        long[][] A_u     = new long[nT][nH];
        long[][] D_f     = new long[nT][nH];
        long[][] D_u     = new long[nT][nH];
        long[][] D_total = new long[nT][nH]; // Tに到達できない経路（foldable+unfoldable両方不到達）

        for (Gen2Node f2 : gen2Map.values()) {
            int hi = f2.hi;

            Set<Integer> reachedF = new HashSet<>(), reachedU = new HashSet<>();

            for (Map.Entry<Integer, Long> e : f2.countInT_foldable.entrySet()) {
                int ti = e.getKey();
                long c = f2.pathCount * e.getValue();
                A_f[ti][hi] += c;
                totalT_f[ti] += c;
                reachedF.add(ti);
            }
            for (Map.Entry<Integer, Long> e : f2.countInT_unfoldable.entrySet()) {
                int ti = e.getKey();
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

        // シルエット対応表（matchingValueは参考情報のみ。グルーピング判定には使っていない）
        String mapPath = csvPath.replace(".csv", "_map.csv");
        try (PrintWriter pw = new PrintWriter(new FileWriter(mapPath))) {
            pw.println("type,id,matchingValue");
            for (int i = 0; i < nT; i++)
                pw.printf("T,%d,%.10f%n", i + 1, matchingValue(tClasses.get(i).get(0)));
            for (int i = 0; i < nH; i++)
                pw.printf("H,%d,%.10f%n", i + 1, matchingValue(hClasses.get(i).get(0)));
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

        // ===== 各列の T-id 集合を構築 (厳密T-id、matchingValueではない) =====
        Set<Integer> easyColTi   = new HashSet<>();
        Set<Integer> mediumColTi = new HashSet<>();
        Set<Integer> hardTi      = new HashSet<>();
        Set<Integer> easyHardTi  = new HashSet<>(); // easy行 ∩ hard列
        for (int ti = 0; ti < nT; ti++) {
            int cc = colClass[ti];
            if (cc == EASY)   easyColTi.add(ti);
            if (cc == MEDIUM) mediumColTi.add(ti);
            if (cc == HARD) {
                hardTi.add(ti);
                if (rowClass[ti] == EASY) easyHardTi.add(ti);
            }
        }

        // ===== 全 DB を出力 =====
        // 再fold()せず、メインパスで既に確定済みの ckRepByNode/figureToTi を再利用する
        // （再照合によるck衝突リスクを避け、計算も1回分で済む）。
        System.out.println("\nCollecting figures for DB export (reusing the main pass's representatives)...");
        List<ArrayList<Figure>> collected = _collectByTi(ckRepByNode, figureToTi,
            new CollectTarget(easyColTi,   true),   // easy列 foldableのみ
            new CollectTarget(mediumColTi, true),   // medium列 foldableのみ
            new CollectTarget(hardTi,      null),   // hard列 全図形
            new CollectTarget(easyHardTi,  null),   // easy行∩hard列 全図形
            new CollectTarget(easyHardTi,  false)   // easy行∩hard列 unfoldableのみ
        );
        ArrayList<Figure> easyFigures        = collected.get(0);
        ArrayList<Figure> mediumFigures      = collected.get(1);
        ArrayList<Figure> hardFigures        = collected.get(2);
        ArrayList<Figure> easyHardFigures    = collected.get(3);
        ArrayList<Figure> easyHardUnfoldFigs = collected.get(4);

        _exportDb(csvPath, "_easy_foldable.db",             easyFigures,        easyColTi.size());
        _exportDb(csvPath, "_medium_foldable.db",           mediumFigures,      mediumColTi.size());
        _exportDb(csvPath, "_hard_foldable.db",             hardFigures,        hardTi.size());
        _exportDb(csvPath, "_easy_total_hard_foldable.db",  easyHardFigures,    easyHardTi.size());
        _exportDb(csvPath, "_easy_total_hard_unfoldable.db",easyHardUnfoldFigs, easyHardTi.size());

        // ===== B_fold 降順ソート DB 出力 =====
        Map<Integer, ArrayList<Figure>> figsByTi = _collectFoldableGrouped(ckRepByNode, figureToTi);

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

        // ===== 442 T group each-one-representative DB 出力 =====
        // 各Tグループから必ず1つ、実際にfoldableなFigureインスタンスを選ぶ
        // （foldableRepByTi -- figsByTiの代表選択バグ（ckRepLocalが偶然unfoldableなm/v変種を
        // 保持していたグループがDB出力から漏れる問題）を回避するため、専用に用意したマップ）。
        ArrayList<Figure> onePerTi = new ArrayList<>();
        for (int ti : tiByB) {
            Figure f = foldableRepByTi.get(ti);
            if (f == null)
                throw new IllegalStateException("ti " + ti + " has totalT_f>0 but no foldable representative -- coverage bug");
            onePerTi.add(f);
        }
        String onePerTiDbPath = csvPath.replace(".csv", "_one_per_target.db");
        StreamSQliteDB.export(onePerTi, onePerTiDbPath);
        System.out.println("Written: " + onePerTiDbPath
            + " (" + onePerTi.size() + " figures, one per T group)");
    }

    private void _exportDb(String csvPath, String suffix, ArrayList<Figure> figures, int silCount) {
        if (figures.isEmpty()) return;
        String dbPath = csvPath.replace(".csv", suffix);
        StreamSQliteDB.export(figures, dbPath);
        System.out.println("Written: " + dbPath + " (" + figures.size() + " figures, "
            + silCount + " T silhouette classes)");
    }

    /**
     * foldable な gen-3 代表Figureを厳密T-id(ti)でグルーピングして返す。B_fold 降順ソート
     * DB 出力用。メインパスで既に確定済みの ckRepByNode（各(Gen2Node,ck)の代表Figure）と
     * figureToTi（そのFigureインスタンスのidentityから厳密T-idを引く）を再利用し、
     * 再fold()・ck文字列の再照合は一切行わない（衝突リスクも再計算コストもない）。
     */
    private Map<Integer, ArrayList<Figure>> _collectFoldableGrouped(
            Map<Gen2Node, Map<String, Figure>> ckRepByNode, Map<Figure, Integer> figureToTi) {
        Map<Integer, Map<String, Figure>> byTi = new LinkedHashMap<>();
        for (Map<String, Figure> ckRepLocal : ckRepByNode.values()) {
            for (Figure f3 : ckRepLocal.values()) {
                if (!f3.isFoldable) continue;
                int ti = figureToTi.get(f3);
                byTi.computeIfAbsent(ti, k -> new LinkedHashMap<>())
                    .putIfAbsent(ExactStateKey.compute(f3).toString(), f3);
            }
        }
        Map<Integer, ArrayList<Figure>> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Figure>> e : byTi.entrySet())
            result.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        return result;
    }

    /** 収集条件: 対象T-id集合 + foldability フィルタ (null=両方, true=foldableのみ, false=unfoldableのみ) */
    private static class CollectTarget {
        final Set<Integer> tis;
        final Boolean foldableFilter; // null=both, true=foldable only, false=unfoldable only
        CollectTarget(Set<Integer> tis, Boolean foldableFilter) {
            this.tis = tis; this.foldableFilter = foldableFilter;
        }
    }

    /**
     * 各 CollectTarget の条件に合う gen-3 代表Figureを重複除去して収集する。複数ターゲットを
     * 1パスで処理する。メインパスの ckRepByNode/figureToTi を再利用し、再fold()・ck文字列の
     * 再照合は行わない（_collectFoldableGroupedと同じ理由）。
     */
    private List<ArrayList<Figure>> _collectByTi(
            Map<Gen2Node, Map<String, Figure>> ckRepByNode, Map<Figure, Integer> figureToTi,
            CollectTarget... targets) {
        @SuppressWarnings("unchecked")
        Map<String, Figure>[] seen = new LinkedHashMap[targets.length];
        for (int i = 0; i < seen.length; i++) seen[i] = new LinkedHashMap<>();

        for (Map<String, Figure> ckRepLocal : ckRepByNode.values()) {
            for (Figure f3 : ckRepLocal.values()) {
                int ti = figureToTi.get(f3);
                String key = ExactStateKey.compute(f3).toString();
                for (int i = 0; i < targets.length; i++) {
                    CollectTarget ct = targets[i];
                    if (ct.foldableFilter != null && ct.foldableFilter != f3.isFoldable) continue;
                    if (ct.tis.contains(ti)) seen[i].putIfAbsent(key, f3);
                }
            }
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
