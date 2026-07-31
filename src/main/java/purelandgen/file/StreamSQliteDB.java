package purelandgen.file;

import com.google.gson.Gson;
import purelandgen.geom.Figure;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;

public class StreamSQliteDB {

    private static final String CREATE_TABLE =
            "CREATE TABLE origami(" +
            "id INTEGER NOT NULL PRIMARY KEY, " +
            "data TEXT, " +
            "sd REAL NOT NULL, " +
            "faces_count INTEGER NOT NULL DEFAULT 0, " +
            "edges_count INTEGER NOT NULL DEFAULT 0, " +
            "isFoldable INTEGER NOT NULL DEFAULT 1, " +
            "isMakeCrease INTEGER NOT NULL DEFAULT 0, " +
            "UNIQUE(sd, faces_count, edges_count))";
    private static final String CREATE_INDEX =
            "CREATE INDEX sd_index ON origami(sd)";
    private static final String INSERT =
            "INSERT OR IGNORE INTO origami VALUES (?, ?, ?, ?, ?, ?, ?)";

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
        private long index = 0;

        public Writer(String filepath) throws Exception {
            Class.forName("org.sqlite.JDBC");
            new File(filepath).delete();
            conn = DriverManager.getConnection("jdbc:sqlite:" + filepath);
            conn.setAutoCommit(false);
            Statement s = conn.createStatement();
            s.executeUpdate(CREATE_TABLE);
            s.executeUpdate(CREATE_INDEX);
            conn.commit();
            stmt        = conn.prepareStatement(INSERT);
            stmtNow     = conn.prepareStatement(INSERT);
            findStmt    = conn.prepareStatement(
                    "SELECT isFoldable, isMakeCrease FROM origami" +
                    " WHERE sd BETWEEN ? AND ? AND faces_count=? AND edges_count=?");
            replaceStmt = conn.prepareStatement(
                    "UPDATE origami SET data=?, isFoldable=?, isMakeCrease=?" +
                    " WHERE sd BETWEEN ? AND ? AND faces_count=? AND edges_count=?");
        }

        public void write(Figure f) throws SQLException {
            stmt.setLong(1, index++);
            stmt.setString(2, new FoldFormat(f).toJson());
            stmt.setDouble(3, f.sd);
            stmt.setInt(4, f.faces.size());
            stmt.setInt(5, f.edges.size());
            stmt.setInt(6, f.isFoldable ? 1 : 0);
            stmt.setInt(7, (f.isMakeCreaseFold()) ? 1 : 0);
            stmt.addBatch();
            if (index % 1000 == 0) {
                stmt.executeBatch();
                conn.commit();
            }
        }

        /** ストリーミング重複除去モード用：即時 INSERT（同一トランザクション内で SELECT に見える）。 */
        public void writeNow(Figure f) throws SQLException {
            stmtNow.setLong(1, index++);
            stmtNow.setString(2, new FoldFormat(f).toJson());
            stmtNow.setDouble(3, f.sd);
            stmtNow.setInt(4, f.faces.size());
            stmtNow.setInt(5, f.edges.size());
            stmtNow.setInt(6, f.isFoldable ? 1 : 0);
            stmtNow.setInt(7, (f.isMakeCreaseFold()) ? 1 : 0);
            stmtNow.executeUpdate();
            if (index % 1000 == 0) conn.commit();
        }

        /**
         * 既存図形を検索する。sd の ε 範囲 + 面数・辺数で照合。
         * 見つかれば int[]{isFoldable, isMakeCrease}、なければ null。
         */
        public int[] findExisting(double sd, int faces, int edges, double sdEps) throws SQLException {
            findStmt.setDouble(1, sd - sdEps);
            findStmt.setDouble(2, sd + sdEps);
            findStmt.setInt(3, faces);
            findStmt.setInt(4, edges);
            ResultSet rs = findStmt.executeQuery();
            return rs.next() ? new int[]{rs.getInt(1), rs.getInt(2)} : null;
        }

        /** 既存図形（sd ε範囲 + 面数・辺数で特定）を f で置き換える。 */
        public void replace(double sd, int faces, int edges, Figure f, double sdEps) throws SQLException {
            boolean isMC = f.isMakeCreaseFold();
            replaceStmt.setString(1, new FoldFormat(f).toJson());
            replaceStmt.setInt(2, f.isFoldable ? 1 : 0);
            replaceStmt.setInt(3, isMC ? 1 : 0);
            replaceStmt.setDouble(4, sd - sdEps);
            replaceStmt.setDouble(5, sd + sdEps);
            replaceStmt.setInt(6, faces);
            replaceStmt.setInt(7, edges);
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

    public static ArrayList<Figure> load(String filepath, int limit) {
        ArrayList<Figure> ret = new ArrayList<>();
        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + filepath);
            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM origami LIMIT ?");
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
