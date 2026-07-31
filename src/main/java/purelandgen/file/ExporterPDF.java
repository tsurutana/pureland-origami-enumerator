package purelandgen.file;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import purelandgen.geom.Face;
import purelandgen.geom.Figure;
import purelandgen.geom.Halfedge;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

public class ExporterPDF {

    public static void export(ArrayList<Figure> figures, String filepath) {
        if (figures.isEmpty()) return;

        final int NUM_HORIZONTAL = 20;
        final int NUM_VERTICAL   = 25;
        final int PER_PAGE       = NUM_HORIZONTAL * NUM_VERTICAL;
        final int total          = figures.size();
        final int totalPages     = (total + PER_PAGE - 1) / PER_PAGE;

        try (PDDocument doc = new PDDocument()) {
            int figIdx = 0;
            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                int pageFirst = figIdx + 1;

                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                final float W  = page.getCropBox().getUpperRightX();
                final float H  = page.getCropBox().getUpperRightY();
                final float margin = 20f;
                final float h  = (W - margin * 2) / (NUM_HORIZONTAL - 1);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    for (int row = 0; row < NUM_VERTICAL && figIdx < total; row++) {
                        for (int col = 0; col < NUM_HORIZONTAL && figIdx < total; col++) {
                            float dx = margin + col * h;
                            float dy = H - margin - row * h;
                            _drawFigure(figures.get(figIdx), dx, dy, cs);
                            figIdx++;
                        }
                    }
                    int pageLast = figIdx;
                    _drawFooter(pageNum, totalPages, pageFirst, pageLast, total, cs);
                }
            }
            doc.save(new File(filepath));
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    private static void _drawFigure(Figure figure, float dx, float dy,
                                     PDPageContentStream cs) throws IOException {
        final float s = 0.08f;
        cs.setLineJoinStyle(1);
        cs.setLineWidth(0.1f);
        final Color FRONT = new Color(160, 160, 160);
        final Color BACK  = new Color(255, 255, 255);
        for (Face f : figure.faces) {
            Halfedge he = f.halfedge;
            cs.moveTo((float) he.vertex.p.x * s + dx, (float) he.vertex.p.y * -s + dy);
            he = he.next;
            do {
                cs.lineTo((float) he.vertex.p.x * s + dx, (float) he.vertex.p.y * -s + dy);
                he = he.next;
            } while (he != f.halfedge);
            cs.closePath();
            cs.setNonStrokingColor(f.isFront ? FRONT : BACK);
            cs.fillAndStroke();
        }
    }

    private static void _drawFooter(int pageNum, int totalPages,
                                     int pageFirst, int pageLast, int total,
                                     PDPageContentStream cs) throws IOException {
        PDFont font = PDType1Font.TIMES_ROMAN;
        String text = String.format("Page %d / %d  (%d – %d of %d)   Pureland Origami Generator 1.4",
                pageNum, totalPages, pageFirst, pageLast, total);
        cs.setNonStrokingColor(0);
        cs.beginText();
        cs.setFont(font, 8);
        cs.newLineAtOffset(10, 10);
        cs.showText(text);
        cs.endText();
    }

    private static Color generateColor() {
        Random random = new Random();
        final float hue        = random.nextFloat();
        final float saturation = (random.nextInt(2000) + 1000) / 10000f;
        final float luminance  = 0.9f;
        return Color.getHSBColor(hue, saturation, luminance);
    }
}
