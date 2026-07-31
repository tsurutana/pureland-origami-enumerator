package purelandgen;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Construction/Technique/Selectionオプションの保存・読み込みを管理するクラス。
 * options.iniファイルから読み込む。
 */
public class OptionsConfig {

    private static final String INI_FILENAME = "options.ini";

    private static boolean[] constructions = {true, true, true, false, false, false, false};
    private static boolean[] techniques = {true, true, true};
    private static boolean[] selections = {true, false, false, false, false}; // [All, Unique, SinglePath, MultiPath, NonUnique]
    private static boolean showFoldable   = false;
    private static boolean showUnfoldable = false;
    private static String lastDirectory = null;

    private static boolean loaded = false;

    static {
        load();
    }

    public static void load() {
        Path iniPath = getIniPath();
        if (Files.exists(iniPath)) {
            loadFromFile(iniPath);
        }
        loaded = true;
    }

    public static void save(boolean[] constructions, boolean[] techniques, boolean[] selections,
                            boolean showFoldable, boolean showUnfoldable) {
        OptionsConfig.constructions = constructions;
        OptionsConfig.techniques = techniques;
        OptionsConfig.selections = selections;
        OptionsConfig.showFoldable   = showFoldable;
        OptionsConfig.showUnfoldable = showUnfoldable;
        saveToFile(getIniPath());
    }

    public static String getLastDirectory() { return lastDirectory; }

    public static void saveLastDirectory(String dir) {
        lastDirectory = dir;
        saveToFile(getIniPath());
    }

    private static Path getIniPath() {
        return Paths.get(System.getProperty("user.dir"), INI_FILENAME);
    }

    private static void loadFromFile(Path path) {
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(path)) {
            props.load(is);
            constructions = parseBooleanArray(props.getProperty("constructions"), constructions);
            techniques = parseBooleanArray(props.getProperty("techniques"), techniques);
            selections = parseBooleanArray(props.getProperty("selections"), selections);
            String sf = props.getProperty("showFoldable");
            if (sf != null) showFoldable = "1".equals(sf.trim());
            String su = props.getProperty("showUnfoldable");
            if (su != null) showUnfoldable = "1".equals(su.trim());
            // backward compat: old physicallyFoldable=1 → showFoldable=true
            String pf = props.getProperty("physicallyFoldable");
            if (pf != null && "1".equals(pf.trim()) && sf == null) { showFoldable = true; showUnfoldable = false; }
            String ld = props.getProperty("lastDirectory");
            if (ld != null && !ld.trim().isEmpty()) lastDirectory = ld.trim();
        } catch (IOException e) {
            System.err.println("Failed to load options config: " + e.getMessage());
        }
    }

    private static void saveToFile(Path path) {
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("# Options configuration"); writer.newLine();
            writer.newLine();
            writer.write("constructions=" + toBooleanString(constructions)); writer.newLine();
            writer.write("techniques=" + toBooleanString(techniques)); writer.newLine();
            writer.write("selections=" + toBooleanString(selections)); writer.newLine();
            writer.write("showFoldable="   + (showFoldable   ? "1" : "0")); writer.newLine();
            writer.write("showUnfoldable=" + (showUnfoldable ? "1" : "0")); writer.newLine();
            if (lastDirectory != null) {
                writer.write("lastDirectory=" + lastDirectory); writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Failed to save options config: " + e.getMessage());
        }
    }

    private static boolean[] parseBooleanArray(String value, boolean[] defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        String[] parts = value.split(",");
        boolean[] result = new boolean[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = "1".equals(parts[i].trim());
        }
        return result;
    }

    private static String toBooleanString(boolean[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(values[i] ? "1" : "0");
        }
        return sb.toString();
    }

    public static boolean[] getConstructions() {
        return constructions.clone();
    }

    public static boolean[] getTechniques() {
        return techniques.clone();
    }

    public static boolean[] getSelections() {
        return selections.clone();
    }

    public static boolean getShowFoldable()   { return showFoldable; }
    public static boolean getShowUnfoldable() { return showUnfoldable; }
}
