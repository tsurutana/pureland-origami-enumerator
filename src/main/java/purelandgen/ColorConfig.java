package purelandgen;

import java.awt.Color;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 紙の表裏の色設定を管理するクラス
 * 同じディレクトリのcolor.iniファイルから読み込む
 */
public class ColorConfig {

    private static final String INI_FILENAME = "color.ini";

    // 画面表示用の色
    private static Color displayFront = new Color(100, 100, 200);
    private static Color displayBack = new Color(255, 255, 255);

    // ファイル出力用の色
    private static Color exportFront = new Color(160, 160, 160);
    private static Color exportBack = new Color(255, 255, 255);

    private static boolean loaded = false;

    static {
        load();
    }

    public static void load() {
        Path iniPath = getIniPath();
        if (Files.exists(iniPath)) {
            loadFromFile(iniPath);
        } else {
            saveToFile(iniPath);
        }
        loaded = true;
    }

    private static Path getIniPath() {
        return Paths.get(System.getProperty("user.dir"), INI_FILENAME);
    }

    private static void loadFromFile(Path path) {
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(path)) {
            props.load(is);
            displayFront = parseColor(props.getProperty("display.front"), displayFront);
            displayBack = parseColor(props.getProperty("display.back"), displayBack);
            exportFront = parseColor(props.getProperty("export.front"), exportFront);
            exportBack = parseColor(props.getProperty("export.back"), exportBack);
        } catch (IOException e) {
            System.err.println("Failed to load color config: " + e.getMessage());
        }
    }

    private static void saveToFile(Path path) {
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("# Paper color configuration"); writer.newLine();
            writer.write("# Format: #RRGGBB (hex)"); writer.newLine();
            writer.newLine();
            writer.write("# Display colors (for GUI)"); writer.newLine();
            writer.write("display.front=" + colorToHex(displayFront)); writer.newLine();
            writer.write("display.back=" + colorToHex(displayBack)); writer.newLine();
            writer.newLine();
            writer.write("# Export colors (for PDF/SVG)"); writer.newLine();
            writer.write("export.front=" + colorToHex(exportFront)); writer.newLine();
            writer.write("export.back=" + colorToHex(exportBack)); writer.newLine();
        } catch (IOException e) {
            System.err.println("Failed to save color config: " + e.getMessage());
        }
    }

    private static Color parseColor(String hex, Color defaultColor) {
        if (hex == null || hex.isEmpty()) {
            return defaultColor;
        }
        try {
            hex = hex.trim();
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            int rgb = Integer.parseInt(hex, 16);
            return new Color(rgb);
        } catch (NumberFormatException e) {
            return defaultColor;
        }
    }

    private static String colorToHex(Color color) {
        return String.format("#%02X%02X%02X",
            color.getRed(), color.getGreen(), color.getBlue());
    }

    // Getters
    public static Color getDisplayFront() {
        return displayFront;
    }

    public static Color getDisplayBack() {
        return displayBack;
    }

    public static Color getExportFront() {
        return exportFront;
    }

    public static Color getExportBack() {
        return exportBack;
    }

    // Hex string getters for SVG export
    public static String getExportFrontHex() {
        return colorToHex(exportFront);
    }

    public static String getExportBackHex() {
        return colorToHex(exportBack);
    }
}
