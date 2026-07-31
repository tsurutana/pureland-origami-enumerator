package purelandgen.file;

import purelandgen.geom.Face;
import purelandgen.geom.Figure;
import purelandgen.geom.Halfedge;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.text.DecimalFormat;

public class ExporterSVG {

	public static void export(Figure d, String filepath) throws Exception{
		int width = 300, height=300;
		String frontColor = "#b2c6e6";
		String backColor = "#ffffff";
		
		FileWriter fw = new FileWriter(filepath);
		BufferedWriter bw = new BufferedWriter(fw);
		
		DecimalFormat df = new DecimalFormat();
		df.setMaximumFractionDigits(8);
		df.setMinimumFractionDigits(1);
		df.setGroupingUsed(false);

		// centering and scaling
		bw.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>"); bw.newLine();
		bw.write("<svg"); bw.newLine();
		bw.write("   xmlns:svg=\"http://www.w3.org/2000/svg\"");  bw.newLine();
		bw.write("   xmlns=\"http://www.w3.org/2000/svg\"");  bw.newLine();
		bw.write("   version=\"1.0\"");  bw.newLine();
		bw.write("   width=\"" + width + "\"");  bw.newLine();
		bw.write("   height=\""+ height + "\">");  bw.newLine();
		bw.write("  <g>\n");  bw.newLine();
		
		for(Face f : d.faces) {
			//draw faces
			Halfedge he = f.halfedge;
			bw.write("<path d=\"M ");
			bw.write(df.format(he.vertex.p.x+150.0) + " " + df.format(he.vertex.p.y+150.0));
			bw.write(" L");
			he = he.next;
			do {
				bw.write(" " + df.format(he.vertex.p.x+150.0) + " " + df.format(he.vertex.p.y+150.0));
				he = he.next;
			} while (he != f.halfedge);
			bw.write(" Z\" fill=\"");
			if (f.isFront) {
				bw.write(frontColor);
			} else {
				bw.write(backColor);
			}
			bw.write("\" stroke=\"black\" stroke-width=\"1\" stroke-linejoin=\"round\" />");
			bw.newLine();
			
            //    fill="none" stroke="red" stroke-width="3"/>
			
		}
		bw.write(" </g>\n"); // end of group
		bw.write("</svg>\n"); // end of svg
		bw.close();
	}
}
