package purelandgen.file;

import com.google.gson.Gson;
import purelandgen.geom.ExactStateKey;
import purelandgen.geom.Figure;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;

public class StreamSQliteDB {

    // exact_key: ExactStateKey.compute(f).toString(), or "" when the caller does not track it
    // (legacy callers / figures without a verified origP lineage -- see Vertex.origP javadoc).
    // "" is intentionally NOT distinguished from a real key by the UNIQUE constraint below in the
    // rare case two such untracked figures collide on (sd, faces, edges); callers that need exact-key
    // correctness (FoldingMachine's streaming dedup) always pass a real key.
    private static final String CREATE_TABLE =
            "CREATE TABLE origami(" +
            "id INTEGER NOT NULL PRIMARY KEY, " +
            "data TEXT, " +
            "sd REAL NOT NULL, " +
            "faces_count INTEGER NOT NULL DEFAULT 0, " +
            "edges_count INTEGER NOT NULL DEFAULT 0, " +
            "isFoldable INTEGER NOT NULL DEFAULT 1, " +
            "isMakeCrease INTEGER NOT NULL DEFAULT 0, " +
            "exact_key TEXT NOT NULL DEFAULT '', " +
            "UNIQUE(sd, faces_count, edges_count, exact_key))";
    private static final String CREATE_INDEX =
            "CREATE INDEX sd_index ON origami(sd)";
    private static final String CREATE_KEY_INDEX =
            "CREATE INDEX exact_key_index ON origami(faces_count, edges_count, exact_key)";
    private static final String INSERT =
            "INSERT OR IGNORE INTO origami VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Streaming writer: keeps the DB connection open and writes one figure at a time.
     * Commits every 1000 inserts; flushes and closes on close().
     */
    public static class Writer implements AutoCloseable {
        private final Connection conn;
        private final PreparedStatement stmt;     // addBatch 用（write）
        private final PreparedStatement stmtNow;  // 即時実行用（writeNow）
        private final PreparedStatement findStmt;
        private final PreparedStatement replaceStmt;
        private final boolean storeGeometry;
        private long index = 0;

        public Writer(String filepath) throws Exception {
            this(filepath, true);
        }

        /**
         * storeGeometry=false skips serializing each figure's geometry into the "data" column
         * (written as "" instead) -- for a round whose figures are never reloaded (e.g. the final
         * round of ExactKeyEnumeration, where only SUM(isFoldable) is read back via
         * countByFoldable()), this is by far the largest cost per row and is pure waste. Dedup
         * (findExisting/UNIQUE constraint) does not depend on this column, so correctness is
         * unaffected; only load() of such a DB would produce empty-geometry figures.
         */
        public Writer(String filepath, boolean storeGeometry) throws Exception {
            this.storeGeometry = storeGeometry;
            Class.forName("org.sqlite.JDBC");
            new File(filepath).delete();
            conn = DriverManager.getConnection("jdbc:sqlite:" + filepath);
            conn.setAutoCommit(false);
            Statement s = conn.createStatement();
            s.executeUpdate(CREATE_TABLE);
            s.executeUpdate(CREATE_INDEX);
            s.executeUpdate(CREATE_KEY_INDEX);
            conn.commit();
            stmt        = conn.prepareStatement(INSERT);
            stmtNow     = conn.prepareStatement(INSERT);
            findStmt    = conn.prepareStatement(
                    "SELECT isFoldable, isMakeCrease FROM origami" +
                    " WHERE sd BETWEEN ? AND ? AND faces_count=? AND edges_count=? AND exact_key=?");
            replaceStmt = conn.prepareStatement(
                    "UPDATE origami SET data=?, isFoldable=?, isMakeCrease=?" +
                    " WHERE sd BETWEEN ? AND ? AND faces_count=? AND edges_count=? AND exact_key=?");
        }

        /** exactKey: ExactStateKey.compute(f).toString() from the caller, or "" if untracked. */
        public void write(Figure f, String exactKey) throws SQLException {
            stmt.setLong(1, index++);
            stmt.setString(2, storeGeometry ? new FoldFormat(f).toJson() : "");
            stmt.setDouble(3, f.sd);
            stmt.setInt(4, f.faces.size());
            stmt.setInt(5, f.edges.size());
            stmt.setInt(6, f.isFoldable ? 1 : 0);
            stmt.setInt(7, (f.isMakeCreaseFold()) ? 1 : 0);
            stmt.setString(8, exactKey);
            stmt.addBatch();
            if (index % 1000 == 0) {
                stmt.executeBatch();
                conn.commit();
            }
        }

        /** ストリーミング重複除去モード用：即時 INSERT（同一トランザクション内で SELECT に見える）。
         *  exactKey: ExactStateKey.compute(f).toString() from the caller, or "" if untracked. */
        public void writeNow(Figure f, String exactKey) throws SQLException {
            stmtNow.setLong(1, index++);
            stmtNow.setString(2, storeGeometry ? new FoldFormat(f).toJson() : "");
            stmtNow.setDouble(3, f.sd);
            stmtNow.setInt(4, f.faces.size());
            stmtNow.setInt(5, f.edges.size());
            stmtNow.setInt(6, f.isFoldable ? 1 : 0);
            stmtNow.setInt(7, (f.isMakeCreaseFold()) ? 1 : 0);
            stmtNow.setString(8, exactKey);
            stmtNow.executeUpdate();
            if (index % 1000 == 0) conn.commit();
        }

        /**
         * 既存図形を検索する。sd の ε 範囲 + 面数・辺数・exact_key の完全一致で照合。
         * 見つかれば int[]{isFoldable, isMakeCrease}、なければ null。
         */
        public int[] findExisting(double sd, int faces, int edges, String exactKey, double sdEps) throws SQLException {
            findStmt.setDouble(1, sd - sdEps);
            findStmt.setDouble(2, sd + sdEps);
            findStmt.setInt(3, faces);
            findStmt.setInt(4, edges);
            findStmt.setString(5, exactKey);
            ResultSet rs = findStmt.executeQuery();
            return rs.next() ? new int[]{rs.getInt(1), rs.getInt(2)} : null;
        }

        /** 既存図形（sd ε範囲 + 面数・辺数 + exact_key で特定）を f で置き換える。 */
        public void replace(double sd, int faces, int edges, Figure f, String exactKey, double sdEps) throws SQLException {
            boolean isMC = f.isMakeCreaseFold();
            replaceStmt.setString(1, storeGeometry ? new FoldFormat(f).toJson() : "");
            replaceStmt.setInt(2, f.isFoldable ? 1 : 0);
            replaceStmt.setInt(3, isMC ? 1 : 0);
            replaceStmt.setDouble(4, sd - sdEps);
            replaceStmt.setDouble(5, sd + sdEps);
            replaceStmt.setInt(6, faces);
            replaceStmt.setInt(7, edges);
            replaceStmt.setString(8, exactKey);
            replaceStmt.executeUpdate();
            conn.commit();
        }

        @Override
        public void close() throws Exception {
            stmt.executeBatch();
            conn.commit();
            conn.close();
        }
    }

    public static void export(ArrayList<Figure> figures, String filepath) {
        try {
            Class.forName("org.sqlite.JDBC");
            new File(filepath).delete();
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + filepath);
            conn.setAutoCommit(false);
            Statement s = conn.createStatement();
            s.executeUpdate(CREATE_TABLE);
            s.executeUpdate(CREATE_INDEX);
            s.executeUpdate(CREATE_KEY_INDEX);
            conn.commit();
            PreparedStatement stmt = conn.prepareStatement(INSERT);
            long index = 0;
            for (Figure f : figures) {
                stmt.setLong(1, index++);
                stmt.setString(2, new FoldFormat(f).toJson());
                stmt.setDouble(3, f.sd);
                stmt.setInt(4, f.faces.size());
                stmt.setInt(5, f.edges.size());
                stmt.setInt(6, f.isFoldable ? 1 : 0);
                stmt.setInt(7, (f.isMakeCreaseFold()) ? 1 : 0);
                // Best-effort: correct when f's origP lineage traces back to Figure(w,h) without a
                // JSON round-trip in between; otherwise falls back to folded p (see Vertex.origP).
                stmt.setString(8, ExactStateKey.compute(f).toString());
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
            conn.close();
            System.out.println("export complete");
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }

    public static ArrayList<Figure> load(String filepath) {
        return load(filepath, Integer.MAX_VALUE);
    }

    /**
     * Row counts via SQL COUNT(*), without deserializing any figure. Use this instead of
     * load(...).size()/isFoldable-filtering when only the counts are needed (e.g. reporting a
     * huge streamed round's totals) -- avoids reconstructing every Figure just to count them.
     * Returns {total, foldable, unfoldable}, or {-1,-1,-1} on error.
     */
    public static long[] countByFoldable(String filepath) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + filepath);
            Statement s = conn.createStatement();
            ResultSet rs = s.executeQuery(
                    "SELECT COUNT(*), SUM(isFoldable) FROM origami");
            long total = 0, foldable = 0;
            if (rs.next()) {
                total = rs.getLong(1);
                foldable = rs.getLong(2);
            }
            conn.close();
            return new long[]{total, foldable, total - foldable};
        } catch (Exception e) {
            System.err.println(e.getMessage());
            return new long[]{-1, -1, -1};
        }
    }

    /** One loaded row paired with its own "id" column (the DB's own row identifier, as written
     *  by write()/writeNow()/export()), for callers that need to report which specific rows a
     *  later computation singles out (e.g. SilhouetteDbCompare). */
    public static class IdFigure {
        public final long id;
        public final Figure figure;
        public IdFigure(long id, Figure figure) { this.id = id; this.figure = figure; }
    }

    /** Like {@link #load(String)}, but keeps each row's own "id" column alongside its Figure. */
    public static ArrayList<IdFigure> loadWithIds(String filepath) {
        ArrayList<IdFigure> ret = new ArrayList<>();
        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + filepath);
            Statement s = conn.createStatement();
            s.setFetchSize(2000);
            ResultSet rs = s.executeQuery("SELECT * FROM origami ORDER BY id");
            boolean hasFoldableCol = _hasColumn(rs, "isFoldable");
            Gson gson = new Gson();
            while (rs.next()) {
                Figure fig = new Figure(gson.fromJson(rs.getString("data"), FoldFormat.class));
                fig.isFoldable = hasFoldableCol ? rs.getInt("isFoldable") == 1 : true;
                ret.add(new IdFigure(rs.getLong("id"), fig));
            }
            conn.close();
            System.out.println("loadWithIds complete: " + ret.size() + " figures from " + filepath);
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
        return ret;
    }

    public static ArrayList<Figure> load(String filepath, int limit) {
        ArrayList<Figure> ret = new ArrayList<>();
        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + filepath);
            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM origami ORDER BY id LIMIT ?");
            stmt.setInt(1, limit);
            stmt.setQueryTimeout(30);
            stmt.setFetchSize(2000);
            ResultSet rs = stmt.executeQuery();

            boolean hasFoldableCol = _hasColumn(rs, "isFoldable");
            Gson gson = new Gson();
            while (rs.next()) {
                Figure fig = new Figure(gson.fromJson(rs.getString("data"), FoldFormat.class));
                fig.isFoldable = hasFoldableCol ? rs.getInt("isFoldable") == 1 : true;
                ret.add(fig);
            }
            conn.close();
            System.out.println("load complete: " + ret.size() + " figures"
                    + (ret.size() == limit ? " (capped at " + limit + ")" : ""));
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
        return ret;
    }

    private static boolean _hasColumn(ResultSet rs, String name) {
        try {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if (name.equalsIgnoreCase(meta.getColumnName(i))) return true;
            }
        } catch (SQLException ignored) {}
        return false;
    }
}
