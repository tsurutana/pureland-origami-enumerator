package purelandgen.file;

import javax.swing.filechooser.FileFilter;

public class FileFilterEx extends FileFilter {

    private String extensions[];
    private String msg;

    public static FileFilterEx SVG = new FileFilterEx(new String[]{"." + "svg"}, "(*.svg) SVG");
    public static FileFilterEx FOLD = new FileFilterEx(new String[]{"." + "fold"}, "(*.fold) OBJ File");
    public static FileFilterEx DB = new FileFilterEx(new String[]{"." + "db"}, "(*.db) DB");
    public static FileFilterEx PDF = new FileFilterEx(new String[]{"." + "pdf"}, "(*.pdf) PDF");

    public FileFilterEx(String[] extensions , String msg) {
        this.extensions = extensions;
        this.msg = msg;
    }

    public boolean accept(java.io.File f) {
        for(int i = 0; i < extensions.length; i++) {
            if(f.isDirectory()) {
                return true;
            }
            if(f.getName().endsWith(extensions[i])) {
                return true;
            }
        }
        return false;
    }

    public String getDescription() { return msg; }
}
