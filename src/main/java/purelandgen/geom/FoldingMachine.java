package purelandgen.geom;

import purelandgen.file.FoldFormat;
import purelandgen.file.StreamSQliteDB;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Fold enumerator with per-component reference foldability.
 *
 * Algorithm:
 *  1. Identify BFS components via fold-crease edges that do NOT cross the fold line.
 *  2. Build fold sets: for each component C, add upper-layer components whose
 *     moved-side polygon overlaps C's moved-side polygon (checked on both sides;
 *     applied transitively).
 *  3. For each fold set, re-split from the original figure and determine moved/fixed side.
 *  4. Apply reference foldability check per fold set.
 */
public class FoldingMachine {

    // Inradius threshold equivalent to 1mm for 150mm paper in the internal 200-unit system (A/P ≈ w/2 → 1mm = 4/3 units → threshold = 2/3)
    static final double MINIMUM_FOLDABLE_WIDTH = 2.0 / 3.0;
    static Comparator<Face> depthComparator = (o1, o2) -> Integer.compare(o1.index, o2.index);

    // Mutable (not final) and public so test/analysis harnesses can temporarily lower these to
    // force streaming mode on a small dataset, e.g. to validate the streaming dedup path against
    // a known-good in-memory result. Production default: 200000.
    public static int STREAM_THRESHOLD = 200000;
    public static int DISPLAY_CAP      = 200000;

    private static class FoldSetResult {
        final ArrayList<ArrayList<Integer>> foldSets;    // CCW+CW combined (duplicates removed)
        final ArrayList<ArrayList<Integer>> ccwFoldSets; // from the CCW pool only
        final ArrayList<ArrayList<Integer>> cwFoldSets;  // from the CW pool only
        FoldSetResult(ArrayList<ArrayList<Integer>> foldSets,
                      ArrayList<ArrayList<Integer>> ccwFoldSets,
                      ArrayList<ArrayList<Integer>> cwFoldSets) {
            this.foldSets    = foldSets;
            this.ccwFoldSets = ccwFoldSets;
            this.cwFoldSets  = cwFoldSets;
        }
    }

    private ArrayList<Face> _splitFacesA = new ArrayList<>();
    private ArrayList<Face> _splitFacesB = new ArrayList<>();
    private ArrayList<Face> _uncrossedFaces = new ArrayList<>();
    ArrayList<FoldingLine> _possibleFoldingLines = new ArrayList<>();
    ArrayList<Figure> generated;
    int currentIndex = 0;

    boolean[] constructions;
    boolean[] techs;

    public int lastTotalCount = 0;     // actual unique figures generated (before display cap)
    public long lastFoldableCount = 0; // via SQL COUNT when streamed (exact, not cap-limited);
                                        // via generated.stream() otherwise

    /** When true, both the in-memory dedup path (_addIfDescripterIsDifferent) and the SQLite
     *  streaming path additionally require ExactStateKey equality before merging two figures
     *  that already match on the fast (sd, face count, edge count) bucket key -- the fast key
     *  alone can conflate states that have the same geometry but a different layer order,
     *  discarding a valid, possibly-foldable layer configuration. Default true: this is the
     *  production default. See the README's "Reproducing the paper's reported numbers"
     *  section for how to reproduce a count under the older (fast-key-only) methodology
     *  instead, when that's specifically what's needed. */
    public static boolean REQUIRE_EXACT_KEY = true;

    // Streaming state (non-null when streaming mode is active)
    private int _totalStreamCount = 0;
    private StreamSQliteDB.Writer _dbWriter = null;
    private String _tempDbPath = null;
    // Set once per FoldOnce() call, at the same time storeGeometry is decided (see
    // _switchToStreaming): true for a round whose figures are never reloaded (DISPLAY_CAP<=0),
    // meaning the exact_key column only needs to support equality lookups, not inspection -- so
    // a 128-bit hash (ExactStateKey.hashHex()) is stored/compared instead of the full encoded
    // key text, which can itself be as large as the figure's geometry (see hashHex() javadoc).
    private boolean _compactKeyMode = false;

    public FoldingMachine(boolean[] constructions, boolean[] techs) {
        this.constructions = constructions;
        this.techs = techs;
    }

    public ArrayList<Figure> FoldOnce(ArrayList<Figure> figures) {
        generated = new ArrayList<>();
        _totalStreamCount = 0;
        _dbWriter = null;
        _tempDbPath = null;
        _compactKeyMode = false;
        for (Figure d : figures) {
            _possibleFoldingLines = OrigamiConstructions.getPossibleFoldingLines(d, constructions);
            _makeFoldedFigures(d, _possibleFoldingLines);
            currentIndex++;
            // Progress by INPUT figures consumed (e.g. how many of the prior round's shapes have
            // been expanded so far), not by output rows written -- the input count is known and
            // grows monotonically, unlike the output count whose eventual total is unknown ahead of time.
            if (currentIndex % 10000 == 0) {
                System.out.println("Progress: " + currentIndex + " / " + figures.size() + " input figures processed...");
            }
        }
        if (_dbWriter != null) {
            try { _dbWriter.close(); } catch (Exception e) { e.printStackTrace(); }
            lastTotalCount = _totalStreamCount;
            long[] counts = StreamSQliteDB.countByFoldable(_tempDbPath);
            lastFoldableCount = counts[1] >= 0 ? counts[1] : -1;
            System.out.println("Streaming complete. DB: " + lastTotalCount
                    + " figures at: " + _tempDbPath);
            if (DISPLAY_CAP <= 0) return new ArrayList<>(); // caller only wants the counts above
            ArrayList<Figure> display = StreamSQliteDB.load(_tempDbPath, DISPLAY_CAP);
            return display != null ? display : new ArrayList<>();
        }
        lastTotalCount = generated.size();
        lastFoldableCount = generated.stream().filter(f -> f.isFoldable).count();
        return generated;
    }

    public ArrayList<Figure> FoldTest(ArrayList<Figure> figures,
                                       double x0, double y0, double x1, double y1) {
        generated = new ArrayList<>();
        _dbWriter = null;
        _tempDbPath = null;
        for (Figure d : figures) {
            _possibleFoldingLines = new ArrayList<>();
            _possibleFoldingLines.add(new FoldingLine(x0, y0, x1, y1));
            _makeFoldedFigures(d, _possibleFoldingLines);
            currentIndex++;
        }
        return generated;
    }

    // -------------------------------------------------------------------------
    //  Top-level dispatch
    // -------------------------------------------------------------------------

    private void _makeFoldedFigures(Figure figure, ArrayList<FoldingLine> foldingLines) {
        if (techs[0])
            _makeValleyFoldFigures(figure, foldingLines);
        if (techs[1]) {
            figure.mirroringFlip();
            figure.buildEdges();
            _makeMountainFoldFigures(figure, foldingLines);
            figure.mirroringFlip();
            figure.buildEdges();
        }
        // techs[2] is generated together with valley/mountain inside _applyFoldSets.
        // Use the legacy implementation only when both valley and mountain are disabled.
        if (techs[2] && !techs[0] && !techs[1])
            _makeFoldAndUnfoldFigures(figure, foldingLines);
    }

    private void _makeValleyFoldFigures(Figure original, ArrayList<FoldingLine> lines) {
        for (FoldingLine line : lines) {
            HashMap<Face, Integer> faceToIdx = _buildFaceIndexMap(original);
            _splitFacesA.clear(); _splitFacesB.clear(); _uncrossedFaces.clear();
            Figure workFig = new Figure(original);
            FoldingLine workLine = new FoldingLine(line);
            if (!_splitAllFacesForFoldSets(workFig, workLine)) continue;
            FoldSetResult fsr = _buildFoldSets(workLine);
            _applyFoldSets(original, line, faceToIdx, fsr, false);
        }
    }

    private void _makeMountainFoldFigures(Figure original, ArrayList<FoldingLine> lines) {
        for (FoldingLine line : lines) {
            HashMap<Face, Integer> faceToIdx = _buildFaceIndexMap(original);
            _splitFacesA.clear(); _splitFacesB.clear(); _uncrossedFaces.clear();
            Figure workFig = new Figure(original);
            FoldingLine workLine = new FoldingLine(line);
            if (!_splitAllFacesForFoldSets(workFig, workLine)) continue;
            FoldSetResult fsr = _buildFoldSets(workLine);
            _applyFoldSets(original, line, faceToIdx, fsr, true);
        }
    }

    /**
     * Generates a figure for each FoldSet in FoldSetResult, then emits it after
     * foldability checking and the cross-pool check.
     * When isMountain=true, mirroringFlip() is applied to the emitted figure (for mountain folds).
     *
     * Foldability of an all-faces FoldSet (containing all original faces) is determined in two passes:
     *   Pass 1: generate the figure for every FoldSet and record its individual foldability
     *   Pass 2: cross-pool check for all-faces FoldSets
     *     - all-faces present in both pools -> pass (already merged by Phase C on both sides, no independence)
     *     - CCW only                        -> all FoldSets in CW must be foldable
     *     - CW only                         -> all FoldSets in CCW must be foldable
     */
    private void _applyFoldSets(Figure original, FoldingLine line,
                                 HashMap<Face, Integer> faceToIdx,
                                 FoldSetResult fsr, boolean isMountain) {
        int totalFaces = original.faces.size();

        // Pass 1: generate the figure for every FoldSet and record its foldability
        HashMap<ArrayList<Integer>, Figure>  figureMap     = new HashMap<>();
        HashMap<ArrayList<Integer>, Boolean> foldabilityMap = new HashMap<>();
        for (ArrayList<Integer> foldSetIdx : fsr.foldSets) {
            ArrayList<Face> foldSet = _toFaceList(original, foldSetIdx);
            _splitFacesA.clear(); _splitFacesB.clear(); _uncrossedFaces.clear();
            Figure b = _makeComponentFigure(original, line, foldSet, faceToIdx);
            if (b == null) { foldabilityMap.put(foldSetIdx, false); continue; }
            boolean foldable = _isPhysicallyFoldable(original, b, line);
            foldabilityMap.put(foldSetIdx, foldable);
            figureMap.put(foldSetIdx, b);
        }

        // For techs[2]: check first whether the all-faces FoldSet is foldable (all-faces takes priority, partial is the fallback)
        boolean anyAllFacesFoldable = false;
        if (techs[2]) {
            for (ArrayList<Integer> s : fsr.foldSets) {
                if (s.size() != totalFaces) continue;
                boolean f = foldabilityMap.getOrDefault(s, false);
                boolean inCCW = fsr.ccwFoldSets.contains(s);
                boolean inCW  = fsr.cwFoldSets.contains(s);
                if      (inCCW && !inCW)  f = f && _allFoldSetsPass(fsr.cwFoldSets,  totalFaces, foldabilityMap);
                else if (inCW  && !inCCW) f = f && _allFoldSetsPass(fsr.ccwFoldSets, totalFaces, foldabilityMap);
                if (f) { anyAllFacesFoldable = true; break; }
            }
        }

        // Pass 2: emit after the cross-pool check
        for (ArrayList<Integer> foldSetIdx : fsr.foldSets) {
            Figure b = figureMap.get(foldSetIdx);
            if (b == null) continue;
            boolean foldable = foldabilityMap.get(foldSetIdx);

            if (foldSetIdx.size() == totalFaces) {
                boolean inCCW = fsr.ccwFoldSets.contains(foldSetIdx);
                boolean inCW  = fsr.cwFoldSets.contains(foldSetIdx);
                // all-faces present in only one pool -> all FoldSets in the opposite pool must be foldable
                // all-faces present in both pools -> pass (already merged by Phase C, no independent components)
                if (inCCW && !inCW)
                    foldable = foldable && _allFoldSetsPass(fsr.cwFoldSets,  totalFaces, foldabilityMap);
                else if (inCW && !inCCW)
                    foldable = foldable && _allFoldSetsPass(fsr.ccwFoldSets, totalFaces, foldabilityMap);
            }

            b.isFoldable = foldable;
            b.setShapeDescriptor();
            _addIfDescripterIsDifferent(b, generated);
            if (isMountain) b.mirroringFlip();

            // Generate the crease figure (only when foldable).
            // If the all-faces FoldSet is foldable, generate only that one; otherwise fall back to partial FoldSets.
            // Called from both the valley and mountain passes, but since sd is identical they collapse into one via dedup.
            boolean isAllFaces = (foldSetIdx.size() == totalFaces);
            if (techs[2] && foldable && (!anyAllFacesFoldable || isAllFaces)) {
                ArrayList<Face> foldSet = _toFaceList(original, foldSetIdx);
                Figure crease = _makeCreaseFigure(original, line, foldSet, faceToIdx);
                if (crease != null) {
                    crease.isFoldable = true;
                    crease.setShapeDescriptor();
                    _addIfDescripterIsDifferent(crease, generated);
                    if (isMountain) crease.mirroringFlip();
                }
            }
        }
    }

    private static ArrayList<Face> _toFaceList(Figure original, ArrayList<Integer> indices) {
        ArrayList<Face> list = new ArrayList<>();
        for (int idx : indices) list.add(original.faces.get(idx));
        return list;
    }

    // Splits only the faces within the FoldSet and produces a crease figure without moving them.
    private Figure _makeCreaseFigure(Figure original, FoldingLine line,
                                      ArrayList<Face> foldSet,
                                      HashMap<Face, Integer> origFaceToIdx) {
        Figure b = new Figure(original);
        b.foldingLine = new FoldingLine(line);
        b.foldingLine.type = FoldingLine.MAKECREASE;
        b.foldingLine.p1.set(b.foldingLine.p0);

        ArrayList<Face> foldSetInB = new ArrayList<>();
        for (Face origFace : foldSet) {
            foldSetInB.add(b.faces.get(origFaceToIdx.get(origFace)));
        }
        foldSetInB.sort((a, c) -> Integer.compare(c.index, a.index));
        b.faces.removeAll(foldSetInB);

        ArrayList<Face> resultFaces = new ArrayList<>();
        boolean anySplit = false;

        for (Face face : foldSetInB) {
            ArrayList<Vertex> xs = GeomUtil.getCrossPoints(face, line.p0, line.p1);
            if (xs.size() == 2 && !face.hasEdgeWith(xs.get(0), xs.get(1))) {
                _updateFoldingLine(xs, b.foldingLine);
                for (Vertex v : xs) {
                    if (v.index < 0) {
                        v.halfedge.split(v);
                        v.index = b.vertices.size();
                        b.vertices.add(v);
                    }
                }
                ArrayList<Face> newFaces = face.split(xs.get(0), xs.get(1));
                if (newFaces != null) {
                    resultFaces.addAll(newFaces);
                    anySplit = true;
                    continue;
                }
            }
            resultFaces.add(face);
        }

        if (!anySplit) return null;

        b.faces.addAll(resultFaces);
        b.faces.sort(depthComparator);
        b.buildEdges();
        return b;
    }

    // Fold-and-unfold: no component analysis needed.
    private void _makeFoldAndUnfoldFigures(Figure original, ArrayList<FoldingLine> lines) {
        Figure originalBack = new Figure(original);
        originalBack.mirroringFlip();
        originalBack.buildEdges();
        for (FoldingLine line : lines) {
            boolean foldable = _isPhysicallyFoldableMakeCrease(original, originalBack, line);
            _splitFacesA.clear();
            _splitFacesB.clear();
            _uncrossedFaces.clear();
            Figure b = new Figure(original);
            b.foldingLine = new FoldingLine(line);
            if (_splitAllFaces(b) && _stackSplitFaces(b)) {
                b.buildEdges();
                b.isFoldable = foldable;
                b.setShapeDescriptor();
                _addIfDescripterIsDifferent(b, generated);
            }
        }
    }

    private boolean _isPhysicallyFoldableMakeCrease(Figure fig, Figure figBack, FoldingLine line) {
        switch (line.construction) {
            case OrigamiConstructions.PASSTHROUGH_2P:
                return (_isPointVisibleInFigure(line.p0, fig) || _isPointVisibleInFigure(line.p0, figBack))
                    && (_isPointVisibleInFigure(line.p1, fig) || _isPointVisibleInFigure(line.p1, figBack));
            case OrigamiConstructions.PLACE_PtP:
                return (_isPointVisibleInFigure(line.landmark0, fig) || _isPointVisibleInFigure(line.landmark0, figBack))
                    && (_isPointVisibleInFigure(line.landmark1, fig) || _isPointVisibleInFigure(line.landmark1, figBack));
            case OrigamiConstructions.PLACE_EtE:
                return (_isSegmentPartlyVisibleInFigure(line.landmark0, line.landmark1, fig)
                            || _isSegmentPartlyVisibleInFigure(line.landmark0, line.landmark1, figBack))
                    && (_isSegmentPartlyVisibleInFigure(line.landmark2, line.landmark3, fig)
                            || _isSegmentPartlyVisibleInFigure(line.landmark2, line.landmark3, figBack));
            default:
                return true;
        }
    }

    // -------------------------------------------------------------------------
    //  FoldSet construction: split-first approach (FM3 Step 1 + Step 2)
    // -------------------------------------------------------------------------

    private HashMap<Face, Integer> _buildFaceIndexMap(Figure fig) {
        HashMap<Face, Integer> map = new HashMap<>();
        for (int i = 0; i < fig.faces.size(); i++)
            map.put(fig.faces.get(i), i);
        return map;
    }

    /**
     * Step 1: splits all faces of the working figure by the fold line.
     * New crease halfedges are marked isCrease=true by Face.split().
     * Skips degenerate splits (one half < MINIMUM_FOLDABLE_AREA) without updating fold line.
     */
    private boolean _splitAllFacesForFoldSets(Figure fig, FoldingLine line) {
        int n = fig.faces.size();
        for (int i = 0; i < n; i++) {
            Face face = fig.faces.remove(fig.faces.size() - 1);
            ArrayList<Vertex> xs = GeomUtil.getCrossPoints(face, line.p0, line.p1);
            if (xs.size() == 2) {
                if (face.hasEdgeWith(xs.get(0), xs.get(1))) {
                    _uncrossedFaces.add(face);
                } else if (_polygonInradius(GeomUtil.clipPolygon(face, line.p0, line.p1, true))  < MINIMUM_FOLDABLE_WIDTH
                        || _polygonInradius(GeomUtil.clipPolygon(face, line.p0, line.p1, false)) < MINIMUM_FOLDABLE_WIDTH) {
                    _uncrossedFaces.add(face); // degenerate: skip without corrupting fold line
                } else {
                    _updateFoldingLine(xs, line);
                    for (Vertex v : xs) {
                        if (v.index < 0) {
                            v.halfedge.split(v);
                            v.index = fig.vertices.size();
                            fig.vertices.add(v);
                        }
                    }
                    ArrayList<Face> newFaces = face.split(xs.get(0), xs.get(1));
                    if (newFaces == null) { _uncrossedFaces.add(face); continue; }
                    if (_separateSplitFaceByPosition(newFaces.get(0), line))
                        _splitFacesB.add(newFaces.get(1));
                    else
                        _splitFacesA.add(newFaces.get(1));
                }
            } else {
                _uncrossedFaces.add(face);
            }
        }
        if (_isTooSmallFold()) return false;
        if (n >= _splitFacesA.size() + _splitFacesB.size() + _uncrossedFaces.size()) return false;
        return fig.isLocalFoldable();
    }

    /**
     * Step 2: BFS on split faces using isCrease to separate components.
     * Phase A: BFS within each side's pool (CCW / CW).
     * Phase B: merge interleaved overlapping components (physically inseparable).
     * Phase C: closure — lower component overlapping a higher one must carry it along.
     */
    private FoldSetResult _buildFoldSets(FoldingLine line) {
        ArrayList<Face> ccwPool = new ArrayList<>();
        ArrayList<Face> cwPool  = new ArrayList<>();
        for (Face f : _splitFacesA) if (_faceInradius(f) >= MINIMUM_FOLDABLE_WIDTH) ccwPool.add(f);
        for (Face f : _splitFacesB) if (_faceInradius(f) >= MINIMUM_FOLDABLE_WIDTH) cwPool.add(f);
        for (Face f : _uncrossedFaces) {
            if (GeomUtil.CCWcheckByFaceCenter(line.p0, line.p1, f)) ccwPool.add(f);
            else cwPool.add(f);
        }

        ArrayList<ArrayList<Integer>> foldSets    = new ArrayList<>();
        ArrayList<ArrayList<Integer>> ccwFoldSets = new ArrayList<>();
        ArrayList<ArrayList<Integer>> cwFoldSets  = new ArrayList<>();
        HashSet<ArrayList<Integer>> seen = new HashSet<>();

        ArrayList<ArrayList<Face>> pools = new ArrayList<>();
        pools.add(ccwPool);
        pools.add(cwPool);
        for (int pi = 0; pi < pools.size(); pi++) {
            ArrayList<Face> pool = pools.get(pi);
            boolean isCCW = (pi == 0);
            if (pool.isEmpty()) continue;

            // Phase A: BFS within pool using non-crease pairs
            HashMap<Face, Integer> poolIdx = new HashMap<>();
            for (int i = 0; i < pool.size(); i++) poolIdx.put(pool.get(i), i);
            ArrayList<ArrayList<Face>> components = new ArrayList<>();
            boolean[] visited = new boolean[pool.size()];
            for (int start = 0; start < pool.size(); start++) {
                if (visited[start]) continue;
                ArrayList<Face> comp = new ArrayList<>();
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                queue.add(start); visited[start] = true;
                while (!queue.isEmpty()) {
                    int curr = queue.poll();
                    comp.add(pool.get(curr));
                    Halfedge he = pool.get(curr).halfedge;
                    do {
                        if (he.hasPair() && !he.isCrease) {
                            Integer ni = poolIdx.get(he.pair.face);
                            if (ni != null && !visited[ni]) { visited[ni] = true; queue.add(ni); }
                        }
                        he = he.next;
                    } while (he != pool.get(curr).halfedge);
                }
                components.add(comp);
            }

            // Phase B: merge interleaved overlapping components
            boolean changed = true;
            while (changed) {
                changed = false;
                outer:
                for (int i = 0; i < components.size(); i++) {
                    for (int j = i + 1; j < components.size(); j++) {
                        if (!_fsStrictlyBelow(components.get(i), components.get(j))
                                && !_fsStrictlyBelow(components.get(j), components.get(i))
                                && _fsOverlap(components.get(i), components.get(j))) {
                            components.get(i).addAll(components.get(j));
                            components.remove(j);
                            changed = true;
                            break outer;
                        }
                    }
                }
            }

            // Phase C: closure for ordered overlapping
            components.sort(Comparator.comparingInt(c -> _fsMinIdx(c)));
            int n = components.size();
            @SuppressWarnings("unchecked")
            ArrayList<Face>[] closures = new ArrayList[n];
            for (int i = n - 1; i >= 0; i--) {
                closures[i] = new ArrayList<>(components.get(i));
                for (int j = i + 1; j < n; j++) {
                    if (_fsStrictlyBelow(components.get(i), components.get(j))
                            && _fsOverlap(components.get(i), components.get(j)))
                        closures[i].addAll(closures[j]);
                }
            }

            for (ArrayList<Face> cl : closures) {
                HashSet<Integer> idxSet = new HashSet<>();
                for (Face f : cl) idxSet.add(f.index);
                ArrayList<Integer> sorted = new ArrayList<>(idxSet);
                Collections.sort(sorted);
                if (isCCW) ccwFoldSets.add(sorted);
                else       cwFoldSets.add(sorted);
                if (seen.add(sorted)) foldSets.add(sorted);
            }
        }
        return new FoldSetResult(foldSets, ccwFoldSets, cwFoldSets);
    }

    /**
     * Checks whether every FoldSet in the opposite pool (excluding the all-faces one) is foldable.
     * Returns true if empty or containing only the all-faces FoldSet.
     */
    private static boolean _allFoldSetsPass(ArrayList<ArrayList<Integer>> poolFoldSets,
                                             int totalFaces,
                                             HashMap<ArrayList<Integer>, Boolean> foldabilityMap) {
        for (ArrayList<Integer> s : poolFoldSets) {
            if (s.size() >= totalFaces) continue;
            if (!foldabilityMap.getOrDefault(s, false)) return false;
        }
        return true;
    }

    private boolean _fsOverlap(ArrayList<Face> c1, ArrayList<Face> c2) {
        for (Face f1 : c1) {
            ArrayList<Vec2> p1 = _fsPoly(f1);
            if (GeomUtil.polygonArea(p1) < GeomUtil.EPS) continue;
            for (Face f2 : c2) {
                ArrayList<Vec2> p2 = _fsPoly(f2);
                if (GeomUtil.polygonArea(p2) < GeomUtil.EPS) continue;
                if (GeomUtil.polygonsOverlap(p1, p2)) return true;
            }
        }
        return false;
    }


    private static ArrayList<Vec2> _fsPoly(Face f) {
        ArrayList<Vec2> poly = new ArrayList<>();
        Halfedge he = f.halfedge;
        do { poly.add(he.vertex.p); he = he.next; } while (he != f.halfedge);
        return poly;
    }

    private boolean _fsStrictlyBelow(ArrayList<Face> a, ArrayList<Face> b) {
        return _fsMaxIdx(a) < _fsMinIdx(b);
    }

    private static int _fsMinIdx(ArrayList<Face> comp) {
        int min = Integer.MAX_VALUE;
        for (Face f : comp) min = Math.min(min, f.index);
        return min;
    }

    private static int _fsMaxIdx(ArrayList<Face> comp) {
        int max = Integer.MIN_VALUE;
        for (Face f : comp) max = Math.max(max, f.index);
        return max;
    }

    // -------------------------------------------------------------------------
    //  Per-fold-set figure construction
    // -------------------------------------------------------------------------

    private Figure _makeComponentFigure(Figure original, FoldingLine line,
                                         ArrayList<Face> foldSet,
                                         HashMap<Face, Integer> origFaceToIdx) {
        Figure b = new Figure(original);
        b.foldingLine = new FoldingLine(line);
        // Start degenerate so _updateFoldingLine initializes from the first split intersection,
        // then extends to cover the maximum crease across all folded layers.
        b.foldingLine.p1.set(b.foldingLine.p0);

        // Collect fold-set faces in b (same creation-order positions as original)
        ArrayList<Face> foldSetInB = new ArrayList<>();
        for (Face origFace : foldSet) {
            foldSetInB.add(b.faces.get(origFaceToIdx.get(origFace)));
        }
        // Process top layers first (descending index)
        foldSetInB.sort((a, c) -> Integer.compare(c.index, a.index));

        // b.faces retains only non-fold-set (base) faces
        b.faces.removeAll(foldSetInB);

        // Split fold-set faces by the fold line
        _splitFacesA.clear();
        _splitFacesB.clear();
        _uncrossedFaces.clear();

        for (Face face : foldSetInB) {
            ArrayList<Vertex> intersections = GeomUtil.getCrossPoints(face, line.p0, line.p1);
            if (intersections.size() == 2) {
                if (face.hasEdgeWith(intersections.get(0), intersections.get(1))) {
                    _uncrossedFaces.add(face);
                } else {
                    _updateFoldingLine(intersections, b.foldingLine);
                    for (Vertex v : intersections) {
                        if (v.index < 0) {
                            v.halfedge.split(v);
                            v.index = b.vertices.size();
                            b.vertices.add(v);
                        }
                    }
                    ArrayList<Face> newFaces =
                            face.split(intersections.get(0), intersections.get(1));
                    if (newFaces == null) { _uncrossedFaces.add(face); continue; }
                    if (_separateSplitFaceByPosition(newFaces.get(0), b.foldingLine))
                        _splitFacesB.add(newFaces.get(1));
                    else
                        _splitFacesA.add(newFaces.get(1));
                }
            } else {
                _uncrossedFaces.add(face);
            }
        }

        if (_isTooSmallFold()) return null;
        if (_splitFacesA.isEmpty() && _splitFacesB.isEmpty()) return null;
        if (!_moveAndStackSplitFacesForComponent(b)) return null;
        b.buildEdges();
        if (!b.isLocalFoldable()) return null;
        return b;
    }

    /**
     * Determines which side to move and stacks faces.
     * Base connectivity takes priority; falls back to distance when neither
     * split side is reachable from base faces.
     */
    private boolean _moveAndStackSplitFacesForComponent(Figure figure) {
        if (figure.faces.isEmpty()) {
            for (Face f : _uncrossedFaces)
                _separateSplitFaceByPosition(f, figure.foldingLine);
            _splitFacesA.sort(depthComparator);
            _splitFacesB.sort(depthComparator);
            if (_calculateFixedSideByDistance(figure)) {
                figure.faces.addAll(_splitFacesA);
                for (Face f : _splitFacesB)
                    GeomUtil.moveFaceToOpposite(f, figure.foldingLine.p0, figure.foldingLine.p1);
                Collections.reverse(_splitFacesB);
                figure.faces.addAll(_splitFacesB);
            } else {
                figure.faces.addAll(_splitFacesB);
                for (Face f : _splitFacesA)
                    GeomUtil.moveFaceToOpposite(f, figure.foldingLine.p0, figure.foldingLine.p1);
                Collections.reverse(_splitFacesA);
                figure.faces.addAll(_splitFacesA);
            }
        } else {
            for (Face f : figure.faces)
                _markBaseConnectedFacesAsVisited(f);

            boolean CCW = false, CW = false;
            for (Face f : _splitFacesA) { if (f.visited) { CCW = true; break; } }
            for (Face f : _splitFacesB) { if (f.visited) { CW  = true; break; } }
            if (CCW && CW) return false;

            for (Face f : _uncrossedFaces) {
                if (f.visited)
                    figure.faces.add(f);
                else
                    _separateSplitFaceByPosition(f, figure.foldingLine);
            }
            figure.faces.sort(depthComparator);
            _splitFacesA.sort(depthComparator);
            _splitFacesB.sort(depthComparator);

            boolean fixedSide = (CCW || CW) ? CCW : _calculateFixedSideByDistance(figure);

            if (!fixedSide) {
                figure.faces.addAll(_splitFacesB);
                figure.faces.sort(depthComparator);
                for (Face f : _splitFacesA)
                    GeomUtil.moveFaceToOpposite(f, figure.foldingLine.p0, figure.foldingLine.p1);
                Collections.reverse(_splitFacesA);
                figure.faces.addAll(_splitFacesA);
            } else {
                figure.faces.addAll(_splitFacesA);
                figure.faces.sort(depthComparator);
                for (Face f : _splitFacesB)
                    GeomUtil.moveFaceToOpposite(f, figure.foldingLine.p0, figure.foldingLine.p1);
                Collections.reverse(_splitFacesB);
                figure.faces.addAll(_splitFacesB);
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    //  Polygon clipping (kept for reference foldability checks)
    // -------------------------------------------------------------------------

    private ArrayList<Vec2> _clipPolygon(Face face, FoldingLine line, boolean useCCW) {
        return GeomUtil.clipPolygon(face, line.p0, line.p1, useCCW);
    }

    private boolean _polygonsOverlap(ArrayList<Vec2> poly1, ArrayList<Vec2> poly2) {
        return GeomUtil.polygonsOverlap(poly1, poly2);
    }

    // -------------------------------------------------------------------------
    //  Geometric helpers
    // -------------------------------------------------------------------------

    // True if the edge strictly crosses the fold line (endpoints on opposite sides).
    private boolean _edgeCrossesFoldLine(Halfedge he, FoldingLine line) {
        Vec2 sp = he.vertex.p, ep = he.next.vertex.p;
        if (GeomUtil.distancePointToLine(sp, line.p0, line.p1) < GeomUtil.EPS) return false;
        if (GeomUtil.distancePointToLine(ep, line.p0, line.p1) < GeomUtil.EPS) return false;
        double dx = line.p1.x - line.p0.x, dy = line.p1.y - line.p0.y;
        double cSp = dx * (sp.y - line.p0.y) - dy * (sp.x - line.p0.x);
        double cEp = dx * (ep.y - line.p0.y) - dy * (ep.x - line.p0.x);
        return (cSp > 0) != (cEp > 0);
    }

    // -------------------------------------------------------------------------
    //  Reference foldability checks
    // -------------------------------------------------------------------------

    private boolean _isPhysicallyFoldable(Figure original, Figure result, FoldingLine sourceLine) {
        switch (sourceLine.construction) {
            case OrigamiConstructions.PASSTHROUGH_2P:
                return _isPhysicallyFoldableAxiom1(original, result, sourceLine);
            case OrigamiConstructions.PLACE_PtP:
                return _isPhysicallyFoldableAxiom2(original, result, sourceLine);
            case OrigamiConstructions.PLACE_EtE:
                return _isPhysicallyFoldableAxiom3(original, result, sourceLine);
            default:
                return true;
        }
    }

    private boolean _isPhysicallyFoldableAxiom1(Figure original, Figure result,
                                                  FoldingLine sourceLine) {
        if (!(_isLandmarkVisibleInFigure(sourceLine.p0, sourceLine.p0SourceEdges, original)
                || _isLandmarkVisibleInFigure(sourceLine.p0, sourceLine.p0SourceEdges, result))) return false;
        if (!(_isLandmarkVisibleInFigure(sourceLine.p1, sourceLine.p1SourceEdges, original)
                || _isLandmarkVisibleInFigure(sourceLine.p1, sourceLine.p1SourceEdges, result))) return false;
        FoldingLine crease = result.foldingLine;
        boolean hasEdge = _figureHasEdge(sourceLine.p0, sourceLine.p1, original);
        boolean ret;
        if (hasEdge) {
            // Fold line lies along an existing paper edge: non-trivial overlap is sufficient,
            // but ONLY within a VISIBLE part of that edge -- otherwise the crease could sit
            // entirely in a hidden middle section while skipping over a visible part (or even
            // the one landmark endpoint it never actually reaches), which would not be
            // physically identifiable/foldable even though p0 and p1 themselves are each
            // individually visible (checked above). Mirrors Axiom 3's _checkContact, which
            // already applies this same visible-overlap / endpoints-only distinction.
            ret = _creaseOverlapsVisiblePartOfEdge(sourceLine.p0, sourceLine.p1, crease.p0, crease.p1, original);
        } else {
            // No edge between the two points: crease must pass through both endpoints.
            ret = _isPointOnSegmentLenient(sourceLine.p0, crease.p0, crease.p1)
                && _isPointOnSegmentLenient(sourceLine.p1, crease.p0, crease.p1);
        }
        return ret;
    }

    private static boolean _figureHasEdge(Vec2 p0, Vec2 p1, Figure fig) {
        for (Edge e : fig.edges) {
            if ((e.sv.p.epsilonEqual(p0) && e.ev.p.epsilonEqual(p1))
             || (e.sv.p.epsilonEqual(p1) && e.ev.p.epsilonEqual(p0))) return true;
        }
        return false;
    }

    private static boolean _creaseOverlapsLandmarkSegment(Vec2 p0, Vec2 p1, Vec2 cp0, Vec2 cp1) {
        double dx = p1.x - p0.x, dy = p1.y - p0.y;
        double len2 = dx * dx + dy * dy;
        if (len2 < GeomUtil.EPS * GeomUtil.EPS) return false;
        double tc0 = ((cp0.x - p0.x) * dx + (cp0.y - p0.y) * dy) / len2;
        double tc1 = ((cp1.x - p0.x) * dx + (cp1.y - p0.y) * dy) / len2;
        double cMin = Math.min(tc0, tc1), cMax = Math.max(tc0, tc1);
        double overlapLen = (Math.min(cMax, 1.0) - Math.max(cMin, 0.0)) * Math.sqrt(len2);
        return overlapLen > CREASE_TOL;
    }

    // Same as _creaseOverlapsLandmarkSegment, but additionally requires the overlap to fall
    // within a VISIBLE part of the landmark edge [p0,p1] in `original` -- a raw length-only
    // overlap check would accept a crease that sits entirely under a hidden middle section of
    // the edge (never reaching, or even touching the visible part of, either landmark point),
    // which isn't physically identifiable even though p0/p1 are each individually visible.
    // Mirrors Axiom 3's _checkContact / _isEndpointsOnlyVisible handling of the same situation.
    private boolean _creaseOverlapsVisiblePartOfEdge(Vec2 p0, Vec2 p1, Vec2 cp0, Vec2 cp1, Figure original) {
        ArrayList<Face> upper = _getUpperLayersForEdge(p0, p1, original);
        if (upper == null) return _creaseOverlapsLandmarkSegment(p0, p1, cp0, cp1);
        ArrayList<double[]> visSegs = GeomUtil.getVisibleSegments(p0, p1, upper);
        if (visSegs.isEmpty()) {
            // Only the two endpoints themselves can be visible (if at all) -- require the
            // crease to actually reach both, same as the no-existing-edge case.
            return _isPointOnSegmentLenient(p0, cp0, cp1) && _isPointOnSegmentLenient(p1, cp0, cp1);
        }
        double dx = p1.x - p0.x, dy = p1.y - p0.y;
        double len2 = dx * dx + dy * dy;
        if (len2 < GeomUtil.EPS * GeomUtil.EPS) return false;
        double lenScale = Math.sqrt(len2);
        double tc0 = ((cp0.x - p0.x) * dx + (cp0.y - p0.y) * dy) / len2;
        double tc1 = ((cp1.x - p0.x) * dx + (cp1.y - p0.y) * dy) / len2;
        double cMin = Math.max(0.0, Math.min(tc0, tc1));
        double cMax = Math.min(1.0, Math.max(tc0, tc1));
        for (double[] vis : visSegs) {
            double lo = Math.max(cMin, vis[0]);
            double hi = Math.min(cMax, vis[1]);
            if ((hi - lo) * lenScale > CREASE_TOL) return true;
        }
        return false;
    }

    private static final double CREASE_TOL = 1.0;

    private static boolean _isPointOnSegmentLenient(Vec2 p, Vec2 sp, Vec2 ep) {
        if (GeomUtil.distancePointToLine(p, sp, ep) >= CREASE_TOL) return false;
        double dx = ep.x - sp.x, dy = ep.y - sp.y;
        boolean useX = Math.abs(dx) >= Math.abs(dy);
        double tp  = useX ? p.x  : p.y;
        double tsp = useX ? sp.x : sp.y;
        double tep = useX ? ep.x : ep.y;
        double lo = Math.min(tsp, tep) - CREASE_TOL;
        double hi = Math.max(tsp, tep) + CREASE_TOL;
        return tp >= lo && tp <= hi;
    }


    private boolean _isPhysicallyFoldableAxiom2(Figure original, Figure result,
                                                  FoldingLine sourceLine) {
        Vec2 p0 = sourceLine.landmark0, p1 = sourceLine.landmark1;
        if (!_isLandmarkVisibleInFigure(p0, sourceLine.landmark0SourceEdges, original)) return false;
        if (!_isLandmarkVisibleInFigure(p1, sourceLine.landmark1SourceEdges, original)) return false;
        // Mesh vertex check: did any vertex physically move from p0→p1 or p1→p0?
        // Handles symmetric folds where both points are in the fold set (count stays equal).
        int nv = Math.min(original.vertices.size(), result.vertices.size());
        for (int i = 0; i < nv; i++) {
            Vec2 vo = original.vertices.get(i).p, vr = result.vertices.get(i).p;
            if ((vo.epsilonEqual(p0) && vr.epsilonEqual(p1))
             || (vo.epsilonEqual(p1) && vr.epsilonEqual(p0)))
                return true;
        }
        // Floating vertex check: the edge through p0 was reflected to pass through p1 (or vice versa).
        return _countEdgesThroughPoint(p1, result) > _countEdgesThroughPoint(p1, original)
            || _countEdgesThroughPoint(p0, result) > _countEdgesThroughPoint(p0, original);
    }

    private int _countEdgesThroughPoint(Vec2 p, Figure fig) {
        int count = 0;
        for (Face f : fig.faces) {
            Halfedge he = f.halfedge;
            do {
                if (GeomUtil.isPointOnSegment(p, he.vertex.p, he.next.vertex.p))
                    count++;
                he = he.next;
            } while (he != f.halfedge);
        }
        return count;
    }

    private boolean _isPhysicallyFoldableAxiom3(Figure original, Figure result,
                                                  FoldingLine sourceLine) {
        Vec2 s0 = sourceLine.landmark0, e0 = sourceLine.landmark1;
        Vec2 s1 = sourceLine.landmark2, e1 = sourceLine.landmark3;
        if (!_isSegmentPartlyVisibleInFigure(s0, e0, original)) return false;
        if (!_isSegmentPartlyVisibleInFigure(s1, e1, original)) return false;
        return _checkContact(s0, e0, original, result)
            || _checkContact(s1, e1, original, result);
    }

    private boolean _checkContact(Vec2 sp, Vec2 ep, Figure original, Figure result) {
        // Comparing edge "count" would give a false positive when the fold line splits the
        // landmark edge, so compare total overlap length instead (unaffected by edge splitting).
        if (_totalCollinearOverlapLength(sp, ep, result)
                <= _totalCollinearOverlapLength(sp, ep, original) + GeomUtil.EPS)
            return false;
        if (_isEndpointsOnlyVisible(sp, ep, original)) {
            return _hasNewCollinearEdgeThroughPoint(sp, ep, sp, original, result)
                && _hasNewCollinearEdgeThroughPoint(sp, ep, ep, original, result);
        }
        ArrayList<Face> upper = _getUpperLayersForEdge(sp, ep, original);
        if (upper == null) return false;
        ArrayList<double[]> visSegs = GeomUtil.getVisibleSegments(sp, ep, upper);
        if (visSegs.isEmpty()) return false;
        return _totalCollinearOverlapOnVisiblePart(sp, ep, visSegs, result)
             > _totalCollinearOverlapOnVisiblePart(sp, ep, visSegs, original) + GeomUtil.EPS;
    }

    private double _totalCollinearOverlapLength(Vec2 sp, Vec2 ep, Figure fig) {
        double dx = ep.x - sp.x, dy = ep.y - sp.y;
        double len2 = dx * dx + dy * dy;
        if (len2 < GeomUtil.EPS * GeomUtil.EPS) return 0;
        double total = 0;
        for (Edge e : fig.edges) {
            if (!GeomUtil.isOnSameLine(e.sv.p, e.ev.p, sp, ep)) continue;
            double ts = ((e.sv.p.x - sp.x) * dx + (e.sv.p.y - sp.y) * dy) / len2;
            double te = ((e.ev.p.x - sp.x) * dx + (e.ev.p.y - sp.y) * dy) / len2;
            double lo = Math.max(Math.min(ts, te), 0.0);
            double hi = Math.min(Math.max(ts, te), 1.0);
            if (hi - lo > GeomUtil.EPS) total += hi - lo;
        }
        return total * Math.sqrt(len2);
    }

    private double _totalCollinearOverlapOnVisiblePart(Vec2 sp, Vec2 ep,
                                                        ArrayList<double[]> visSegs, Figure fig) {
        double dx = ep.x - sp.x, dy = ep.y - sp.y;
        double len2 = dx * dx + dy * dy;
        if (len2 < GeomUtil.EPS * GeomUtil.EPS) return 0;
        double total = 0;
        for (Edge e : fig.edges) {
            if (!GeomUtil.isOnSameLine(e.sv.p, e.ev.p, sp, ep)) continue;
            double ts = ((e.sv.p.x - sp.x) * dx + (e.sv.p.y - sp.y) * dy) / len2;
            double te = ((e.ev.p.x - sp.x) * dx + (e.ev.p.y - sp.y) * dy) / len2;
            double tmin = Math.min(ts, te), tmax = Math.max(ts, te);
            for (double[] vis : visSegs) {
                double lo = Math.max(tmin, vis[0]);
                double hi = Math.min(tmax, vis[1]);
                if (hi - lo > GeomUtil.EPS) total += hi - lo;
            }
        }
        return total * Math.sqrt(len2);
    }

    private boolean _hasNewCollinearEdgeThroughPoint(Vec2 sp, Vec2 ep, Vec2 point,
                                                      Figure original, Figure result) {
        return _countCollinearEdgesThroughPoint(sp, ep, point, result)
             > _countCollinearEdgesThroughPoint(sp, ep, point, original);
    }

    private int _countCollinearEdgesThroughPoint(Vec2 sp, Vec2 ep, Vec2 point, Figure fig) {
        int count = 0;
        for (Edge e : fig.edges) {
            if (GeomUtil.isOnSameLine(e.sv.p, e.ev.p, sp, ep)
                    && _collinearSegmentsOverlapNonTrivially(sp, ep, e.sv.p, e.ev.p)
                    && GeomUtil.isPointOnSegment(point, e.sv.p, e.ev.p))
                count++;
        }
        return count;
    }

    private boolean _isSegmentPartlyVisibleInFigure(Vec2 sp, Vec2 ep, Figure fig) {
        ArrayList<Face> upper = _getUpperLayersForEdge(sp, ep, fig);
        if (upper == null) return false;
        if (GeomUtil.isSegmentPartlyVisible(sp, ep, upper)) return true;
        return GeomUtil.isPointVisible(sp, upper) && GeomUtil.isPointVisible(ep, upper);
    }

    private boolean _isEndpointsOnlyVisible(Vec2 sp, Vec2 ep, Figure fig) {
        ArrayList<Face> upper = _getUpperLayersForEdge(sp, ep, fig);
        if (upper == null) return false;
        return !GeomUtil.isSegmentPartlyVisible(sp, ep, upper)
            && GeomUtil.isPointVisible(sp, upper)
            && GeomUtil.isPointVisible(ep, upper);
    }

    private ArrayList<Face> _getUpperLayersForEdge(Vec2 sp, Vec2 ep, Figure fig) {
        int maxEdgeLayer = -1;
        for (Face f : fig.faces) {
            Halfedge he = f.halfedge;
            do {
                if ((he.vertex.p.epsilonEqual(sp) && he.next.vertex.p.epsilonEqual(ep))
                 || (he.vertex.p.epsilonEqual(ep) && he.next.vertex.p.epsilonEqual(sp))) {
                    maxEdgeLayer = Math.max(maxEdgeLayer, f.index);
                    break;
                }
                he = he.next;
            } while (he != f.halfedge);
        }
        if (maxEdgeLayer == -1) return null;
        ArrayList<Face> upper = new ArrayList<>();
        for (Face f : fig.faces)
            if (f.index > maxEdgeLayer) upper.add(f);
        return upper;
    }

    private boolean _collinearSegmentsOverlapNonTrivially(Vec2 a0, Vec2 a1, Vec2 b0, Vec2 b1) {
        double dx = a1.x - a0.x, dy = a1.y - a0.y;
        boolean useX = Math.abs(dx) >= Math.abs(dy);
        double ta0 = useX ? a0.x : a0.y, ta1 = useX ? a1.x : a1.y;
        double tb0 = useX ? b0.x : b0.y, tb1 = useX ? b1.x : b1.y;
        double minA = Math.min(ta0, ta1), maxA = Math.max(ta0, ta1);
        double minB = Math.min(tb0, tb1), maxB = Math.max(tb0, tb1);
        return Math.min(maxA, maxB) - Math.max(minA, minB) > GeomUtil.EPS;
    }

    // p can simultaneously be: a REAL mesh vertex in some faces (an edge was split there,
    // e.g. along L1), AND a merely-on-the-interior point of a DIFFERENT, unrelated edge in
    // other faces (e.g. L2, never split at this location). The two cases need DIFFERENT
    // hiding rules and must BOTH be checked (AND), not treated as mutually-exclusive
    // fallbacks -- treating them as exclusive (the previous version of this method) let
    // whichever case was found FIRST silently skip the other's hiding check entirely.
    //
    //  - Vertex case: p equals he.vertex.p for face F (a real endpoint). Combine ALL such
    //    faces via the MIN layer among them, then check everything strictly above. Safe
    //    without any pair-exclusion gymnastics because isPointHiddenByFace()'s own paired-
    //    edge rule already excludes exact endpoints, so an immediately-adjacent pair face
    //    can never falsely hide an endpoint this way.
    //  - Edge-interior case: p lies strictly inside an edge (sp,ep), matching neither
    //    endpoint. The endpoint-exclusion above does NOT apply here, so each DISTINCT such
    //    edge needs its OWN local-max exclusion (_getUpperLayersForEdge, the same per-edge
    //    convention already used for Axiom 3's segment visibility) checked independently --
    //    a single GLOBAL max across unrelated edges would let one edge's own topmost reach
    //    "clear" the point even when a DIFFERENT edge through the same point is genuinely
    //    hidden by layers between that edge and the (unrelated) global max.
    private boolean _isPointVisibleInFigure(Vec2 p, Figure fig) {
        int minVertexLayer = Integer.MAX_VALUE;
        ArrayList<Vec2[]> edgesThroughP = new ArrayList<>();
        for (Face f : fig.faces) {
            Halfedge he = f.halfedge;
            do {
                Vec2 sp = he.vertex.p, ep = he.next.vertex.p;
                if (sp.epsilonEqual(p)) {
                    minVertexLayer = Math.min(minVertexLayer, f.index);
                } else if (!ep.epsilonEqual(p) && GeomUtil.isPointOnSegment(p, sp, ep)) {
                    boolean already = false;
                    for (Vec2[] e : edgesThroughP) {
                        if ((e[0].epsilonEqual(sp) && e[1].epsilonEqual(ep))
                         || (e[0].epsilonEqual(ep) && e[1].epsilonEqual(sp))) { already = true; break; }
                    }
                    if (!already) edgesThroughP.add(new Vec2[]{sp, ep});
                }
                he = he.next;
            } while (he != f.halfedge);
        }
        if (minVertexLayer == Integer.MAX_VALUE && edgesThroughP.isEmpty()) return false;

        if (minVertexLayer != Integer.MAX_VALUE) {
            ArrayList<Face> upper = new ArrayList<>();
            for (Face f : fig.faces)
                if (f.index > minVertexLayer) upper.add(f);
            if (!GeomUtil.isPointVisible(p, upper)) return false;
        }
        for (Vec2[] e : edgesThroughP) {
            ArrayList<Face> upper = _getUpperLayersForEdge(e[0], e[1], fig);
            if (upper == null || !GeomUtil.isPointVisible(p, upper)) return false;
        }
        return true;
    }

    // Visibility check for a landmark that may be a FLOATING intersection point (Axiom 1's
    // p0/p1, Axiom 2's landmark0/1 -- never Axiom 3's landmark0-3, which are always real edge
    // endpoints). sourceEdges is non-null exactly when the landmark was built from such a point
    // (Vertex.sourceEdges / FoldingLine.p0SourceEdges etc.); in that case, visibility is checked
    // ONLY against those remembered construction-time edges -- not a fresh scan of whichever
    // edges happen to pass through the same coordinate in `fig` (that scan is what
    // _isPointVisibleInFigure's edge-interior branch does, and it can misfire: a coincidentally-
    // overlapping edge belonging to a face that's merely in the OTHER edge's "upper" set can
    // trigger isPointHiddenByFace's own-crease exclusion rule, which is meant for a real vertex
    // pinched in a fold, not for an unrelated point that happens to cross that crease -- see the
    // mountain-fold visibility investigation). For a real-vertex landmark, sourceEdges is null
    // and this falls back to the existing, unchanged _isPointVisibleInFigure.
    private boolean _isLandmarkVisibleInFigure(Vec2 p, Vec2[][] sourceEdges, Figure fig) {
        if (sourceEdges == null) return _isPointVisibleInFigure(p, fig);
        boolean foundAny = false;
        for (Vec2[] edge : sourceEdges) {
            ArrayList<Face> upper = _getUpperLayersForEdge(edge[0], edge[1], fig);
            if (upper == null) continue; // this source edge isn't present in `fig` (e.g. its face moved by this fold) -- not applicable here
            foundAny = true;
            if (!_isPointOnVisiblePartOfEdge(p, edge[0], edge[1], upper)) return false;
        }
        return foundAny;
    }

    // Whether p (assumed to lie on segment [sp,ep]) falls within a VISIBLE portion of it, using
    // the same interval-subtraction machinery as Axiom 1/3's segment-overlap checks
    // (GeomUtil.getVisibleSegments) instead of a direct isPointHiddenByFace(p, f) point check --
    // the interval approach naturally treats a point sitting exactly on the boundary between a
    // hidden and a visible sub-interval as visible (consistent with "touching is OK"), whereas
    // isPointHiddenByFace's own-crease exclusion rule would wrongly flag it hidden if that exact
    // boundary happens to coincide with an upper face's unrelated crease edge.
    private static boolean _isPointOnVisiblePartOfEdge(Vec2 p, Vec2 sp, Vec2 ep, ArrayList<Face> upper) {
        ArrayList<double[]> visSegs = GeomUtil.getVisibleSegments(sp, ep, upper);
        if (visSegs.isEmpty()) return false;
        double dx = ep.x - sp.x, dy = ep.y - sp.y;
        double len2 = dx * dx + dy * dy;
        if (len2 < GeomUtil.EPS * GeomUtil.EPS) return false;
        double t = ((p.x - sp.x) * dx + (p.y - sp.y) * dy) / len2;
        for (double[] vis : visSegs) {
            if (t >= vis[0] - GeomUtil.EPS && t <= vis[1] + GeomUtil.EPS) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    //  Splitting helpers
    // -------------------------------------------------------------------------

    private boolean _splitAllFaces(Figure fig) {
        final int originalFacesLength = fig.faces.size();
        FoldingLine line = fig.foldingLine;
        for (int i = 0; i < originalFacesLength; i++) {
            Face face = fig.faces.remove(fig.faces.size() - 1);
            ArrayList<Vertex> intersections = GeomUtil.getCrossPoints(face, line.p0, line.p1);
            if (intersections.size() == 2) {
                _updateFoldingLine(intersections, line);
                if (face.hasEdgeWith(intersections.get(0), intersections.get(1))) {
                    _uncrossedFaces.add(face);
                } else {
                    for (Vertex v : intersections) {
                        if (v.index < 0) {
                            v.halfedge.split(v);
                            v.index = fig.vertices.size();
                            fig.vertices.add(v);
                        }
                    }
                    ArrayList<Face> newFaces =
                            face.split(intersections.get(0), intersections.get(1));
                    if (newFaces == null) { _uncrossedFaces.add(face); continue; }
                    if (_separateSplitFaceByPosition(newFaces.get(0), line))
                        _splitFacesB.add(newFaces.get(1));
                    else
                        _splitFacesA.add(newFaces.get(1));
                }
            } else {
                _uncrossedFaces.add(face);
            }
        }
        if (_isTooSmallFold()) return false;
        if (originalFacesLength >= _splitFacesA.size() + _splitFacesB.size()
                + _uncrossedFaces.size() + fig.faces.size())
            return false;
        return fig.isLocalFoldable();
    }

    private boolean _stackSplitFaces(Figure figure) {
        figure.faces.addAll(_splitFacesB);
        figure.faces.addAll(_splitFacesA);
        figure.faces.addAll(_uncrossedFaces);
        figure.faces.sort(depthComparator);
        return true;
    }

    private boolean _isTooSmallFold() {
        boolean anyA = false;
        for (Face f : _splitFacesA) if (_faceInradius(f) >= MINIMUM_FOLDABLE_WIDTH) { anyA = true; break; }
        if (!anyA) return true;
        for (Face f : _splitFacesB) if (_faceInradius(f) >= MINIMUM_FOLDABLE_WIDTH) return false;
        return true;
    }

    private boolean _separateSplitFaceByPosition(Face f, FoldingLine line) {
        if (GeomUtil.CCWcheckByFaceCenter(line.p0, line.p1, f)) {
            _splitFacesA.add(f);
            return true;
        } else {
            _splitFacesB.add(f);
            return false;
        }
    }

    private boolean _calculateFixedSideByDistance(Figure figure) {
        Vec2 center0 = GeomUtil.center(_splitFacesA);
        Vec2 center1 = GeomUtil.center(_splitFacesB);
        double d0 = GeomUtil.distancePointToLine(
                center0, figure.foldingLine.p0, figure.foldingLine.p1);
        double d1 = GeomUtil.distancePointToLine(
                center1, figure.foldingLine.p0, figure.foldingLine.p1);
        return d0 > d1;
    }

    private void _markBaseConnectedFacesAsVisited(Face start) {
        HashSet<Face> boundary = new HashSet<>(_splitFacesA);
        boundary.addAll(_splitFacesB);

        ArrayDeque<Face> stack = new ArrayDeque<>();
        start.visited = true;
        stack.push(start);

        while (!stack.isEmpty()) {
            Face f = stack.pop();
            if (boundary.contains(f)) continue;
            Halfedge begin = f.halfedge;
            Halfedge he = begin;
            do {
                if (he.hasPair() && !he.pair.face.visited) {
                    he.pair.face.visited = true;
                    stack.push(he.pair.face);
                }
                he = he.next;
            } while (he != begin);
        }
    }

    private static void _updateFoldingLine(ArrayList<Vertex> xs, FoldingLine line) {
        double dx = line.p1.x - line.p0.x, dy = line.p1.y - line.p0.y;
        double len2 = dx * dx + dy * dy;
        if (len2 < GeomUtil.EPS * GeomUtil.EPS) {
            // Degenerate (first call): initialize from this intersection pair.
            line.p0.set(xs.get(0).p);
            line.p1.set(xs.get(1).p);
            return;
        }
        // Project each intersection point onto the current line direction.
        // Extend p0 (t<0) or p1 (t>1) to cover the maximum crease extent.
        double tMin = 0.0, tMax = 1.0;
        Vec2 pMin = null, pMax = null;
        for (Vertex x : xs) {
            double t = ((x.p.x - line.p0.x) * dx + (x.p.y - line.p0.y) * dy) / len2;
            if (t < tMin) { tMin = t; pMin = x.p; }
            else if (t > tMax) { tMax = t; pMax = x.p; }
        }
        if (pMin != null) line.p0.set(pMin);
        if (pMax != null) line.p1.set(pMax);
    }

    private boolean _addIfDescripterIsDifferent(Figure a, ArrayList<Figure> figures) {
        if (a == null) return false;
        a.prev = currentIndex;

        if (_dbWriter != null) {
            // Streaming mode: duplicate matching via SQLite's sd_index (seenSd/seenSdFoldable are not used)
            boolean aIsMC = a.foldingLine != null && a.foldingLine.type == FoldingLine.MAKECREASE;
            double sdEps = Math.max(Math.abs(a.sd) * 1e-9, Figure.SD_TOLERANCE);
            int faces = a.faces.size(), edges = a.edges.size();
            // Computed unconditionally (needed either way: to query for a match, or to store on
            // insert). REQUIRE_EXACT_KEY=false still narrows by exact_key="" via the query below
            // (all rows share that value), preserving today's sd/faces/edges-only behavior exactly.
            String aKey = !REQUIRE_EXACT_KEY ? "" :
                    _compactKeyMode ? ExactStateKey.compute(a).hashHex() : ExactStateKey.compute(a).toString();
            try {
                int[] existing = _dbWriter.findExisting(a.sd, faces, edges, aKey, sdEps);
                if (existing != null) {
                    boolean existingIsMC = existing[1] == 1;
                    if (!aIsMC && existingIsMC) {
                        // valley/mountain fold (a) replaces MAKECREASE (existing)
                        _dbWriter.replace(a.sd, faces, edges, a, aKey, sdEps);
                    } else if (!aIsMC && !existingIsMC && a.isFoldable && existing[0] == 0) {
                        // same type on both sides: foldable replaces unfoldable
                        _dbWriter.replace(a.sd, faces, edges, a, aKey, sdEps);
                    }
                    // aIsMC && !existingIsMC -> discard MAKECREASE (do nothing)
                    return false;
                }
                // new figure (sd mismatch, or same sd/faces/edges but a new exact_key)
                _totalStreamCount++;
                _dbWriter.writeNow(a, aKey);
            } catch (Exception e) { e.printStackTrace(); }
            return true;
        }

        // In-memory mode
        ExactStateKey aKey = REQUIRE_EXACT_KEY ? ExactStateKey.compute(a) : null;
        for (int i = 0; i < figures.size(); i++) {
            Figure b = figures.get(i);
            if (a.epsilonEqual(b)
                    && a.faces.size() == b.faces.size()
                    && a.edges.size() == b.edges.size()
                    && (!REQUIRE_EXACT_KEY || aKey.equals(ExactStateKey.compute(b)))) {
                boolean aIsMC = a.foldingLine != null && a.foldingLine.type == FoldingLine.MAKECREASE;
                boolean bIsMC = b.foldingLine != null && b.foldingLine.type == FoldingLine.MAKECREASE;
                if (aIsMC && !bIsMC) {
                    // sd collision: the folded figure (b) already exists, discard the MAKECREASE figure (a)
                    if (a.prev != b.prev) b.hasMultiPath = true;
                    return false;
                }
                if (!aIsMC && bIsMC) {
                    // sd collision: the folded figure (a) replaces the MAKECREASE figure (b)
                    if (a.faces.size() != b.faces.size() || a.edges.size() != b.edges.size()) {
                        _writeSdCollisionDebug(a, b);
                    }
                    a.hasMultiPath = b.hasMultiPath || (a.prev != b.prev);
                    figures.set(i, a);
                    return false;
                }
                if (a.prev != b.prev) a.hasMultiPath = b.hasMultiPath = true;
                if (a.isFoldable && !b.isFoldable) {
                    // The foldable figure (a) replaces the existing unfoldable figure (b).
                    // Since the caller's if (isMountain) b.mirroringFlip() will run on a,
                    // the mountain figure is restored to the correct orientation.
                    a.hasMultiPath = b.hasMultiPath;
                    figures.set(i, a);
                }
                return false;
            }
        }
        figures.add(a);

        if (figures.size() >= STREAM_THRESHOLD) {
            _switchToStreaming(figures);
        }
        return true;
    }

    private void _switchToStreaming(ArrayList<Figure> figures) {
        try {
            _tempDbPath = System.getProperty("user.dir")
                    + File.separator + "pureland_" + System.currentTimeMillis() + ".db";
            // DISPLAY_CAP<=0 means the caller (see FoldOnce) never reloads this round's figures --
            // only countByFoldable()'s SQL COUNT is read back -- so (a) skip serializing geometry
            // into the "data" column, and (b) store a 128-bit hash instead of the full encoded
            // exact_key text (see ExactStateKey.hashHex() javadoc) -- both are by far the largest
            // per-row costs for deep/large rounds, and neither is needed once only counts matter.
            _compactKeyMode = DISPLAY_CAP <= 0;
            _dbWriter = new StreamSQliteDB.Writer(_tempDbPath, !_compactKeyMode);
            _totalStreamCount = 0;
            for (Figure f : figures) {
                // No duplicate check needed here, since duplicates were already removed at the in-memory stage
                _totalStreamCount++;
                String key = !REQUIRE_EXACT_KEY ? "" :
                        _compactKeyMode ? ExactStateKey.compute(f).hashHex() : ExactStateKey.compute(f).toString();
                _dbWriter.writeNow(f, key);
            }
            figures.clear();
            System.out.println("Switched to streaming mode. " + _totalStreamCount
                    + " figures written to: " + _tempDbPath);
        } catch (Exception e) {
            System.err.println("Failed to switch to streaming mode: " + e.getMessage());
            if (_dbWriter != null) { try { _dbWriter.close(); } catch (Exception ignore) {} }
            _dbWriter = null;
            _tempDbPath = null;
        }
    }

    /** When a replacement occurs due to an sd collision, writes both figures to {a.prev}-{b.prev}.db.
     *  Uses a dedicated schema without an sd UNIQUE constraint so that both figures are always saved. */
    private void _writeSdCollisionDebug(Figure a, Figure b) {
        String path = System.getProperty("user.dir") + File.separator
                + a.prev + "-" + b.prev + ".db";
        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            conn.setAutoCommit(false);
            Statement s = conn.createStatement();
            s.executeUpdate("CREATE TABLE origami(" +
                    "id INTEGER NOT NULL PRIMARY KEY, data TEXT, " +
                    "sd REAL NOT NULL, isFoldable INTEGER NOT NULL DEFAULT 1)");
            s.executeUpdate("CREATE INDEX sd_index ON origami(sd)");
            conn.commit();
            PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO origami VALUES (?, ?, ?, ?)");
            for (int idx = 0; idx < 2; idx++) {
                Figure f = idx == 0 ? a : b;
                stmt.setLong(1, idx);
                stmt.setString(2, new FoldFormat(f).toJson());
                stmt.setDouble(3, f.sd);
                stmt.setInt(4, f.isFoldable ? 1 : 0);
                stmt.executeUpdate();
            }
            conn.commit();
            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("SD collision debug written: " + path);
    }

    // -------------------------------------------------------------------------
    //  Diagnostic output
    // -------------------------------------------------------------------------

    /**
     * Prints component and fold-set information for the given figure and fold line.
     * Does not modify the figure.
     */
    public void diagnose(Figure fig, FoldingLine line) {
        HashMap<Face, Integer> faceToIdx = _buildFaceIndexMap(fig);

        // --- Layer structure ---
        System.out.println("  Layer structure:");
        for (Face f : fig.faces) {
            StringBuilder sb = new StringBuilder("    Layer " + f.index
                    + " [" + (f.isFront ? "front" : "back") + "]:");
            Halfedge he = f.halfedge;
            do {
                sb.append(String.format(" (%.1f,%.1f)", he.vertex.p.x, he.vertex.p.y));
                he = he.next;
            } while (he != f.halfedge);
            System.out.println(sb);
        }

        // --- Halfedge pairs ---
        System.out.println("  Halfedge pairs:");
        for (Face f : fig.faces) {
            Halfedge he = f.halfedge;
            do {
                if (he.hasPair()) {
                    Face nb = he.pair.face;
                    if (nb != null && f.index < nb.index) {
                        Vec2 sp = he.vertex.p, ep = he.next.vertex.p;
                        boolean crosses = _edgeCrossesFoldLine(he, line);
                        System.out.printf("    Layer%d <-> Layer%d  edge(%.1f,%.1f)-(%.1f,%.1f)  %s%n",
                                f.index, nb.index, sp.x, sp.y, ep.x, ep.y,
                                crosses ? "CROSSES fold line" : "non-crossing");
                    }
                }
                he = he.next;
            } while (he != f.halfedge);
        }

        // --- FoldSets (new split-first approach) ---
        _splitFacesA.clear(); _splitFacesB.clear(); _uncrossedFaces.clear();
        Figure workFig = new Figure(fig);
        FoldingLine workLine = new FoldingLine(line);
        if (!_splitAllFacesForFoldSets(workFig, workLine)) {
            System.out.println("  Step1 FAILED");
            return;
        }
        System.out.printf("  Step1: CCW=%d CW=%d uncrossed=%d%n",
                _splitFacesA.size(), _splitFacesB.size(), _uncrossedFaces.size());

        ArrayList<ArrayList<Integer>> foldSets = _buildFoldSets(workLine).foldSets;
        System.out.println("  Fold Sets (" + foldSets.size() + ")");
        for (int si = 0; si < foldSets.size(); si++) {
            ArrayList<Integer> foldSetIdx = foldSets.get(si);
            ArrayList<Face> foldSet = _toFaceList(fig, foldSetIdx);
            System.out.println("    FoldSet " + si + ": layers=" + foldSetIdx);
            _splitFacesA.clear(); _splitFacesB.clear(); _uncrossedFaces.clear();
            Figure result = _makeComponentFigure(fig, line, foldSet, faceToIdx);
            if (result != null)
                System.out.println("      -> VALID: " + result.faces.size() + " layers");
            else
                System.out.println("      -> no valid fold");
        }
    }

    private static double _polygonArea(ArrayList<Vec2> poly) {
        return GeomUtil.polygonArea(poly);
    }

    private static double _polygonInradius(ArrayList<Vec2> poly) {
        if (poly.size() < 3) return 0;
        double area = GeomUtil.polygonArea(poly);
        double perimeter = 0;
        for (int i = 0; i < poly.size(); i++) {
            Vec2 a = poly.get(i), b = poly.get((i + 1) % poly.size());
            double dx = b.x - a.x, dy = b.y - a.y;
            perimeter += Math.sqrt(dx * dx + dy * dy);
        }
        return perimeter > GeomUtil.EPS ? area / perimeter : 0;
    }

    private static double _faceInradius(Face face) {
        double area = face.area();
        double perimeter = 0;
        Halfedge he = face.halfedge;
        do {
            Vec2 a = he.vertex.p, b = he.next.vertex.p;
            double dx = b.x - a.x, dy = b.y - a.y;
            perimeter += Math.sqrt(dx * dx + dy * dy);
            he = he.next;
        } while (he != face.halfedge);
        return perimeter > GeomUtil.EPS ? area / perimeter : 0;
    }

    private static ArrayList<Face> _diagSorted(ArrayList<Face> faces) {
        ArrayList<Face> sorted = new ArrayList<>(faces);
        sorted.sort((a, b) -> Integer.compare(a.index, b.index));
        return sorted;
    }

    private static String _diagLayerStr(Iterable<Face> faces) {
        ArrayList<Integer> indices = new ArrayList<>();
        for (Face f : faces) indices.add(f.index);
        Collections.sort(indices);
        return indices.toString();
    }

    private static String _diagPolyStr(ArrayList<Vec2> poly) {
        if (poly.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < poly.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("(%.1f,%.1f)", poly.get(i).x, poly.get(i).y));
        }
        return sb.append("]").toString();
    }
}
