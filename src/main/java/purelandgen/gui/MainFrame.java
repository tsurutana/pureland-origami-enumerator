package purelandgen.gui;

import purelandgen.OptionsConfig;
import purelandgen.file.ExporterPDF;
import purelandgen.file.ExporterSVG;
import purelandgen.file.FileFilterEx;
import purelandgen.file.StreamSQliteDB;
import purelandgen.geom.Figure;
import purelandgen.geom.FoldingMachine;
import purelandgen.geom.Silhouette;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainFrame extends JFrame implements ActionListener {

    private static int frameCount = 0;

    private final ArrayList<Figure> allFigures;  // full set; never filtered
    private final ArrayList<Figure> figures;      // currently displayed subset
    private final MenuBar menubar = new MenuBar();
    private final JSlider slider = new JSlider();
    private final FigureScreen canvas;

    public MainFrame(ArrayList<Figure> figures) {
        this(figures, OptionsConfig.getConstructions(),
                OptionsConfig.getTechniques(), OptionsConfig.getSelections(),
                OptionsConfig.getShowFoldable(), OptionsConfig.getShowUnfoldable());
    }

    public MainFrame(ArrayList<Figure> figures,
                     boolean[] constructions, boolean[] techniques, boolean[] selections,
                     boolean showFoldable, boolean showUnfoldable) {
        this.allFigures = new ArrayList<>(figures);
        this.figures = new ArrayList<>();
        this.canvas = new FigureScreen(this.figures);

        menubar.setConstructions(constructions);
        menubar.setTechniques(techniques);
        menubar.setSelections(selections);
        menubar.setShowFoldable(showFoldable);
        menubar.setShowUnfoldable(showUnfoldable);

        slider.setMinimum(0);
        slider.setMaximum(0);
        slider.setValue(0);
        slider.addChangeListener(e -> setCurrentFigure(slider.getValue()));
        getContentPane().add(slider, BorderLayout.NORTH);
        getContentPane().add(canvas, BorderLayout.CENTER);

        menubar.menuItemNew.addActionListener(this);
        menubar.menuItemOpen.addActionListener(this);
        menubar.menuItemSaveDB.addActionListener(this);
        menubar.menuItemSaveClassDBs.addActionListener(this);
        menubar.menuItemSaveSVG.addActionListener(this);
        menubar.menuItemSavePDF.addActionListener(this);
        menubar.menuItemDelete.addActionListener(this);
        menubar.menuItemSilhouette.addActionListener(this);
        menubar.menuItemFlip.addActionListener(this);
        menubar.menuItemSortByDescriptor.addActionListener(this);
        menubar.menuItemGenerate.addActionListener(this);
        menubar.menuItemShowFoldable.addActionListener(this);
        menubar.menuItemShowUnfoldable.addActionListener(this);
        for (javax.swing.JCheckBoxMenuItem item : menubar.selectionItems)
            item.addActionListener(this);

        setJMenuBar(menubar);
        this.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveCurrentOptions();
            }
        });
        this.setPreferredSize(new Dimension(500, 600));
        this.pack();
        this.setLocation(frameCount * 30, frameCount * 30);
        frameCount++;
        applyDisplayFilter();
        this.setVisible(true);
    }

    private void applyDisplayFilter() {
        boolean showF = menubar.getShowFoldable();
        boolean showU = menubar.getShowUnfoldable();
        boolean[] sels = menubar.getSelections(); // [0]=All [1]=Unique [2]=SinglePath [3]=MultiPath [4]=NonUnique
        boolean anySel = sels[1] || sels[2] || sels[3] || sels[4]; // sels[0]=All のときは全件表示
        figures.clear();
        for (Figure f : allFigures) {
            // foldability: どちらもチェックなし → 全件、一方のみ → 該当のみ、両方 → 全件
            if (showF || showU) {
                if (!((showF && f.isFoldable) || (showU && !f.isFoldable))) continue;
            }
            // selection: All またはチェックなし → 全件、それ以外 → 該当のみ
            if (anySel) {
                if (sels[1] && !f.isUniqueSilhouette) continue;
                if (sels[2] &&  f.hasMultiPath)       continue;
                if (sels[3] && !f.hasMultiPath)       continue;
                if (sels[4] &&  f.isUniqueSilhouette) continue;
            }
            figures.add(f);
        }
        slider.setMaximum(Math.max(0, figures.size() - 1));
        slider.setValue(0);
        if (!figures.isEmpty()) setCurrentFigure(0);
    }

    private void setCurrentFigure(int index) {
        if (figures.isEmpty()) return;
        canvas.setTargetFigure(index);
        setWindowTitle(index);
    }

    private void setWindowTitle(int index) {
        if (figures.isEmpty()) { setTitle("0 of 0"); return; }
        if (figures.get(index).foldingLine != null)
            setTitle((index+1) + " of " + figures.size() +". Discoverability: " + figures.get(index).foldingLine.getDiscoverability());
        else
            setTitle((index+1) + " of " + figures.size());
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == menubar.menuItemNew)
            newWindow();
        else if (e.getSource() == menubar.menuItemOpen)
            open();
        else if (e.getSource() == menubar.menuItemSaveDB)
            saveDB();
        else if (e.getSource() == menubar.menuItemSaveClassDBs)
            saveClassBasedDBs();
        else if (e.getSource() == menubar.menuItemSaveSVG)
            saveSVG();
        else if (e.getSource() == menubar.menuItemSavePDF)
            savePDF();
        else if (e.getSource() == menubar.menuItemDelete)
            deleteItem();
        else if (e.getSource() == menubar.menuItemSilhouette)
            canvas.showSilhouette(menubar.menuItemSilhouette.isSelected());
        else if (e.getSource() == menubar.menuItemFlip)
            canvas.flip();
        else if (e.getSource() == menubar.menuItemSortByDescriptor)
            sortByDescriptor();
        else if (e.getSource() == menubar.menuItemGenerate)
            enumerate();
        else if (e.getSource() == menubar.menuItemShowFoldable
              || e.getSource() == menubar.menuItemShowUnfoldable)
            applyDisplayFilter();
        else {
            for (javax.swing.JCheckBoxMenuItem item : menubar.selectionItems) {
                if (e.getSource() == item) { applyDisplayFilter(); break; }
            }
        }
    }

    private boolean[] getCurrentConstructions() {
        return menubar.getConstructions();
    }

    private boolean[] getCurrentTechniques() {
        return menubar.getTechniques();
    }

    private boolean[] getCurrentSelections() {
        return menubar.getSelections();
    }

    private void saveCurrentOptions() {
        OptionsConfig.save(getCurrentConstructions(),
                getCurrentTechniques(), getCurrentSelections(),
                menubar.getShowFoldable(), menubar.getShowUnfoldable());
    }

    private void enumerate() {
        boolean[] constructs = getCurrentConstructions();
        boolean[] techs = getCurrentTechniques();
        boolean[] sels = getCurrentSelections();
        boolean anyConstruction = false;
        for (boolean b : constructs) if (b) { anyConstruction = true; break; }
        boolean anyTechnique = false;
        for (boolean b : techs) if (b) { anyTechnique = true; break; }
        if (!anyConstruction || !anyTechnique) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Choose at least one construction and one folding technique",
                    "Configuration error", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        System.out.println("enumeration start");
        long _startTime = System.currentTimeMillis();

        saveCurrentOptions();

        ArrayList<Figure> folded = new ArrayList<>();
       /*if (techs[0]) {
            ValleyFolder vf = new ValleyFolder(constructs);
            vf.foldAll(figures, folded);
        }
        if (techs[1]) {
            MountainFolder mf = new MountainFolder(constructs);
            mf.foldAll(figures, folded);
        }
        if (techs[2]) {
            CreaseFolder cf = new CreaseFolder(constructs);
            cf.foldAll(figures, folded);
        }*/

        FoldingMachine fm = new FoldingMachine(constructs, techs);
        folded = fm.FoldOnce(figures);

        int totalGenerated = fm.lastTotalCount;
        long foldableCount   = folded.stream().filter(f ->  f.isFoldable).count();
        long unfoldableCount = folded.stream().filter(f -> !f.isFoldable).count();
        long _elapsed = System.currentTimeMillis() - _startTime;
        String cap = (totalGenerated > folded.size()) ? " (showing " + folded.size() + ")" : "";
        System.out.println("completed. " + totalGenerated + " total" + cap
                + "  (foldable=" + foldableCount + "  unfoldable=" + unfoldableCount + ")"
                + "  time=" + _elapsed + "ms");

        Silhouette.computeProperties(folded);
        long uniqueCount    = folded.stream().filter(f ->  f.isUniqueSilhouette).count();
        long nonUniqueCount = folded.stream().filter(f -> !f.isUniqueSilhouette).count();
        long multiPathCount = folded.stream().filter(f ->  f.hasMultiPath).count();
        System.out.println("silhouettes: " + folded.size()
                + "  (unique=" + uniqueCount + "  non-unique=" + nonUniqueCount
                + "  multi-path=" + multiPathCount + ")");

        if (folded.isEmpty()) return;
        new MainFrame(folded, constructs, techs, sels,
                menubar.getShowFoldable(), menubar.getShowUnfoldable());
    }

    private void sortByDescriptor() {
        allFigures.sort((a, b) -> Double.compare(a.sdNormalized, b.sdNormalized));
        applyDisplayFilter();
    }

    private void deleteItem() {
        if (figures.size() <= 1) return;
        int index = slider.getValue();
        Figure toRemove = figures.get(index);
        allFigures.remove(toRemove);
        applyDisplayFilter();
        int newIndex = Math.max(0, Math.min(index, figures.size() - 1));
        slider.setValue(newIndex);
        setCurrentFigure(newIndex);
    }

    private void newWindow() {
        ArrayList<Figure> initial = new ArrayList<>();
        initial.add(new Figure(200, 200));
        new MainFrame(initial);
    }

    private void open() {
        JFileChooser fileChooser = createFileChooser();
        fileChooser.addChoosableFileFilter(FileFilterEx.DB);
        fileChooser.setFileFilter(FileFilterEx.DB);
        if (JFileChooser.APPROVE_OPTION == fileChooser.showOpenDialog(this)) {
            rememberDirectory(fileChooser.getSelectedFile());
            String filePath = fileChooser.getSelectedFile().getPath();
            try {
                ArrayList<Figure> loaded = StreamSQliteDB.load(filePath);
                new MainFrame(loaded, getCurrentConstructions(),
                        getCurrentTechniques(), getCurrentSelections(),
                        menubar.getShowFoldable(), menubar.getShowUnfoldable());
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }
    }

    private void saveDB() {
        JFileChooser fileChooser = createFileChooser();
        fileChooser.addChoosableFileFilter(FileFilterEx.DB);
        fileChooser.setFileFilter(FileFilterEx.DB);
        if (JFileChooser.APPROVE_OPTION == fileChooser.showSaveDialog(this)) {
            rememberDirectory(fileChooser.getSelectedFile());
            String filePath = fileChooser.getSelectedFile().getPath();
            try {
                filePath = addExtensionToFilepath(filePath, "db");
                StreamSQliteDB.export(figures, filePath);
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }
    }

    private void saveClassBasedDBs() {
        JFileChooser fc = createFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        rememberDirectory(fc.getSelectedFile());
        String dir = fc.getSelectedFile().getAbsolutePath();

        LinkedHashMap<String, ArrayList<Figure>> groups = new LinkedHashMap<>();
        groups.put("all", new ArrayList<>(allFigures));
        ArrayList<Figure> foldable = new ArrayList<>(), unfoldable = new ArrayList<>(),
                unique = new ArrayList<>(), nonUnique = new ArrayList<>(),
                simplePath = new ArrayList<>(), multiPath = new ArrayList<>();
        for (Figure f : allFigures) {
            (f.isFoldable        ? foldable   : unfoldable).add(f);
            (f.isUniqueSilhouette ? unique     : nonUnique ).add(f);
            (f.hasMultiPath       ? multiPath  : simplePath).add(f);
        }
        groups.put("foldable",    foldable);
        groups.put("unfoldable",  unfoldable);
        groups.put("unique",      unique);
        groups.put("non-unique",  nonUnique);
        groups.put("simple-path", simplePath);
        groups.put("multi-path",  multiPath);

        for (Map.Entry<String, ArrayList<Figure>> entry : groups.entrySet()) {
            ArrayList<Figure> list = entry.getValue();
            if (list.isEmpty()) continue;
            String path = dir + File.separator + entry.getKey() + "_" + list.size() + ".db";
            StreamSQliteDB.export(list, path);
        }
    }

    private void saveSVG() {
        JFileChooser fileChooser = createFileChooser();
        fileChooser.addChoosableFileFilter(FileFilterEx.SVG);
        fileChooser.setFileFilter(FileFilterEx.SVG);
        if (JFileChooser.APPROVE_OPTION == fileChooser.showSaveDialog(this)) {
            rememberDirectory(fileChooser.getSelectedFile());
            String filePath = fileChooser.getSelectedFile().getPath();
            try {
                filePath = addExtensionToFilepath(filePath, "svg");
                ExporterSVG.export(figures.get(slider.getValue()), filePath);
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }
    }

    private void savePDF() {
        JFileChooser fileChooser = createFileChooser();
        fileChooser.addChoosableFileFilter(FileFilterEx.PDF);
        fileChooser.setFileFilter(FileFilterEx.PDF);
        if (JFileChooser.APPROVE_OPTION == fileChooser.showSaveDialog(this)) {
            rememberDirectory(fileChooser.getSelectedFile());
            String filePath = fileChooser.getSelectedFile().getPath();
            try {
                filePath = addExtensionToFilepath(filePath, "pdf");
                ExporterPDF.export(figures, filePath);
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }
    }

    private JFileChooser createFileChooser() {
        JFileChooser fc = new JFileChooser();
        String last = OptionsConfig.getLastDirectory();
        if (last != null) fc.setCurrentDirectory(new File(last));
        return fc;
    }

    private void rememberDirectory(File selected) {
        if (selected == null) return;
        File dir = selected.isDirectory() ? selected : selected.getParentFile();
        if (dir != null) OptionsConfig.saveLastDirectory(dir.getAbsolutePath());
    }

    private String addExtensionToFilepath(String s, String ext) {
        if (!s.endsWith("." + ext)) {
            s += "." + ext;
        }
        return s;
    }
}
