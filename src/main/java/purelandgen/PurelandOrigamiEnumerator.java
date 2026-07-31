package purelandgen;


import purelandgen.file.StreamSQliteDB;
import purelandgen.geom.Figure;
import purelandgen.gui.MainFrame;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;

public class PurelandOrigamiEnumerator {

    private static String dbFilePath = null;

    public static void main(String[] args) {
        if (args.length > 0) {
            dbFilePath = args[0];
        }
        System.out.println(dbFilePath);
        EventQueue.invokeLater(PurelandOrigamiEnumerator::run);
    }

    private static void run() {
        try {
            UIManager.setLookAndFeel(UIManager
                    .getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        ArrayList<Figure> initialFigures;

        if (dbFilePath != null && new File(dbFilePath).exists()) {
            try {
                initialFigures = StreamSQliteDB.load(dbFilePath);
                System.out.println("Loaded " + initialFigures.size() + " figures from: " + dbFilePath);
            } catch (Exception e) {
                System.err.println("Failed to load DB file: " + dbFilePath);
                e.printStackTrace();
                initialFigures = new ArrayList<>();
                initialFigures.add(new Figure(200, 200));
            }
        } else {
            initialFigures = new ArrayList<>();
            initialFigures.add(new Figure(200, 200));
        }

        new MainFrame(initialFigures);
    }
}
