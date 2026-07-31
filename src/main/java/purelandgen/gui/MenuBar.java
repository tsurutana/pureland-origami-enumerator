package purelandgen.gui;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

public class MenuBar extends JMenuBar {

    public JMenu menuFile = new JMenu("File");
    public JMenuItem menuItemNew = new JMenuItem("New");
    public JMenuItem menuItemOpen = new JMenuItem("Open...");
    public JMenuItem menuItemSaveDB = new JMenuItem("Save as DB...");
    public JMenuItem menuItemSaveClassDBs = new JMenuItem("Save class-based DBs...");
    public JMenuItem menuItemSaveSVG = new JMenuItem("Save as SVG...");
    public JMenuItem menuItemSavePDF = new JMenuItem("Export All Figures as PDF...");
    public JMenu menuEdit = new JMenu("Edit");
    public JMenuItem menuItemDelete = new JMenuItem("Delete");
    public JMenu menuView = new JMenu("View");
    public JMenuItem menuItemSilhouette = new JCheckBoxMenuItem("Show silhouette");
    public JMenuItem menuItemFlip = new JMenuItem("Flip");
    public JMenuItem menuItemSortByDescriptor = new JMenuItem("Sort by Descriptor");

    // Options menu
    public JMenu menuOptions = new JMenu("Options");
    public JMenu menuConstruction = new JMenu("Construction");
    public JCheckBoxMenuItem[] constructionItems = new JCheckBoxMenuItem[3];
    public JMenu menuTechnique = new JMenu("Technique");
    public JCheckBoxMenuItem[] techniqueItems = new JCheckBoxMenuItem[3];
    public JMenu menuSelection = new JMenu("Selection");
    public JCheckBoxMenuItem[] selectionItems = new JCheckBoxMenuItem[5];
    public JCheckBoxMenuItem menuItemShowFoldable   = new JCheckBoxMenuItem("Reference Foldable");
    public JCheckBoxMenuItem menuItemShowUnfoldable = new JCheckBoxMenuItem("Reference Unfoldable");

    // Generate menu
    public JMenu menuGenerate = new JMenu("Generate");
    public JMenuItem menuItemGenerate = new JMenuItem("Generate");

    public void setConstructions(boolean[] states) {
        for (int i = 0; i < constructionItems.length && i < states.length; i++) {
            constructionItems[i].setSelected(states[i]);
        }
    }

    public boolean[] getConstructions() {
        boolean[] states = new boolean[constructionItems.length];
        for (int i = 0; i < constructionItems.length; i++) {
            states[i] = constructionItems[i].isSelected();
        }
        return states;
    }

    public void setTechniques(boolean[] states) {
        for (int i = 0; i < techniqueItems.length && i < states.length; i++) {
            techniqueItems[i].setSelected(states[i]);
        }
    }

    public boolean[] getTechniques() {
        boolean[] states = new boolean[techniqueItems.length];
        for (int i = 0; i < techniqueItems.length; i++) {
            states[i] = techniqueItems[i].isSelected();
        }
        return states;
    }

    public void setSelections(boolean[] states) {
        for (int i = 0; i < selectionItems.length && i < states.length; i++) {
            selectionItems[i].setSelected(states[i]);
        }
    }

    public boolean[] getSelections() {
        boolean[] states = new boolean[selectionItems.length];
        for (int i = 0; i < selectionItems.length; i++) {
            states[i] = selectionItems[i].isSelected();
        }
        return states;
    }

    public boolean getShowFoldable()   { return menuItemShowFoldable.isSelected(); }
    public boolean getShowUnfoldable() { return menuItemShowUnfoldable.isSelected(); }

    public void setShowFoldable(boolean state)   { menuItemShowFoldable.setSelected(state); }
    public void setShowUnfoldable(boolean state) { menuItemShowUnfoldable.setSelected(state); }

    public MenuBar() {
        add(menuFile);
        menuFile.setMnemonic('f');
        menuFile.add(menuItemNew);
        menuItemNew.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, ActionEvent.CTRL_MASK));
        menuFile.add(menuItemOpen);
        menuItemOpen.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, ActionEvent.CTRL_MASK));
        menuFile.add(menuItemSaveDB);
        menuFile.add(menuItemSaveClassDBs);
        menuFile.add(menuItemSaveSVG);
        menuItemSaveSVG.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, ActionEvent.CTRL_MASK));
        menuFile.add(menuItemSavePDF);

        add(menuEdit);
        menuEdit.add(menuItemDelete);
        menuItemDelete.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));

        add(menuView);
        menuView.add(menuItemSilhouette);
        menuItemSilhouette.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0));
        menuView.add(menuItemFlip);
        menuItemFlip.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, 0));
        menuView.addSeparator();
        menuView.add(menuItemSortByDescriptor);

        // Options menu - Construction
        add(menuOptions);
        menuOptions.setMnemonic('o');
        menuOptions.add(menuConstruction);
        constructionItems[0] = new JCheckBoxMenuItem("Pass through P1 and P2", true);
        constructionItems[1] = new JCheckBoxMenuItem("P1 onto P2", true);
        constructionItems[2] = new JCheckBoxMenuItem("L1 onto L2", true);
        //constructionItems[3] = new JCheckBoxMenuItem("Pass through P1 perpendicular to L1");
        //constructionItems[4] = new JCheckBoxMenuItem("P1 onto L1 passing through P2");
        //constructionItems[5] = new JCheckBoxMenuItem("P1 onto L1 and P2 onto L2");
        //constructionItems[6] = new JCheckBoxMenuItem("P1 onto L1 perpendicular to L2");
        for (JCheckBoxMenuItem item : constructionItems) {
            menuConstruction.add(item);
        }

        // Options menu - Technique
        menuOptions.add(menuTechnique);
        techniqueItems[0] = new JCheckBoxMenuItem("Valley", true);
        techniqueItems[1] = new JCheckBoxMenuItem("Mountain", true);
        techniqueItems[2] = new JCheckBoxMenuItem("Making Crease", true);
        for (JCheckBoxMenuItem item : techniqueItems) {
            menuTechnique.add(item);
        }

        // Options menu - Selection (mutually exclusive)
        menuOptions.add(menuSelection);
        selectionItems[0] = new JCheckBoxMenuItem("All");
        selectionItems[1] = new JCheckBoxMenuItem("Unique Silhouettes");
        selectionItems[2] = new JCheckBoxMenuItem("Single-path Only");
        selectionItems[3] = new JCheckBoxMenuItem("Multi-path Only");
        selectionItems[4] = new JCheckBoxMenuItem("Non-Unique Silhouettes");
        for (JCheckBoxMenuItem item : selectionItems) {
            menuSelection.add(item);
        }

        // Options menu - Reference foldability filter
        menuOptions.addSeparator();
        menuOptions.add(menuItemShowFoldable);
        menuOptions.add(menuItemShowUnfoldable);

        // Generate menu
        add(menuGenerate);
        menuGenerate.setMnemonic('g');
        menuGenerate.add(menuItemGenerate);
        menuItemGenerate.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, ActionEvent.CTRL_MASK));
    }
}
