package purelandgen.gui;
/**
 * 画面中央の描画パネル
 * @author tsuruta
 *
 */
import purelandgen.ColorConfig;
import purelandgen.geom.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

public class FigureScreen extends JPanel implements MouseWheelListener, ComponentListener{

    private Image bufferImage;       //オフスクリーン描画イメージ-
    private Graphics2D bufferg;
    private AffineTransform affineTransform = new AffineTransform();
    private Dimension preSize;
    private double scale = 2.0;
    private double transX = 0.0;
    private double transY = 0.0;
    private double angle = 0.0;
    protected Figure figure;
    protected boolean showSilhouette = false;
    private ArrayList<Figure> figures;

    private FoldingLine flBisector;  //折り線

    public FigureScreen(ArrayList<Figure> figures) {
        this.figures = figures;
        addMouseWheelListener(this);
        addComponentListener(this);
        setTargetFigure(0);
        setFocusable(true);
        requestFocus();
    }

    public void paintComponent (Graphics g) {
        super.paintComponent(g);

        if (bufferImage == null) {
            bufferImage = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
            bufferg = (Graphics2D) bufferImage.getGraphics();
            updateAffineTransform();
            preSize = getSize();
        }

        // buffergのAffineTransformを初期化
        bufferg.setTransform(new AffineTransform());

        // バッファのイメージをクリア
        Composite orig = bufferg.getComposite();
        bufferg.setComposite(AlphaComposite.Clear);
        bufferg.fillRect(0, 0, getWidth(), getHeight());
        bufferg.setComposite(orig);

        // バッファのAffineTransformを設定する
        bufferg.setTransform(affineTransform);

        Graphics2D g2d = bufferg;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setStroke(Config.ROUNDSTROKE);

        if (figure == null) {
            g.drawImage(bufferImage, 0, 0, this);
            return;
        }
        if (showSilhouette) {
            g2d.fill(Silhouette.getSilhouette(figure));
        } else {
            for (Face f : figure.faces) {
                GeneralPath plg = new GeneralPath();
                Halfedge he = f.halfedge;
                plg.moveTo(he.vertex.p.x, he.vertex.p.y);
                he = he.next;
                do {
                    Vertex v = he.vertex;
                    plg.lineTo(v.p.x, v.p.y);     //polygonに頂点を追加
                    he = he.next;
                } while (he != f.halfedge);
                plg.closePath();
                if (f.isFront)
                    g2d.setColor(ColorConfig.getDisplayFront());
                else
                    g2d.setColor(ColorConfig.getDisplayBack());
                g2d.fill(plg);
                g2d.setColor(Config.COLOR_OUTLINE);
                g2d.draw(plg);
            }
        }

        g.drawImage(bufferImage, 0, 0, this);

    }

    //描画対象の図を変更する
    public void setTargetFigure(int index) {
        if (index >= 0 && index < figures.size()) {
            figure = figures.get(index);
        }
        repaint();
    }

    // 現在のAffineTransformを更新する
    private void updateAffineTransform() {
        affineTransform.setToIdentity(); // 初期化
        affineTransform.translate(getWidth() * 0.5, getHeight() * 0.5); // 画面中央を基準
        affineTransform.scale(scale, scale);     // スケール変換
        affineTransform.translate(transX, transY); // 平行移動
        affineTransform.rotate(angle); //回転
    }

    //拡大縮小
    public void mouseWheelMoved(MouseWheelEvent e) {
        double scale_ = (100.0 - e.getWheelRotation() * 5) / 100.0;
        scale *= scale_;
        updateAffineTransform();
        repaint();
    }

    public void componentHidden(ComponentEvent arg0) {
        // TODO 自動生成されたメソッド・スタブ

    }

    public void componentMoved(ComponentEvent arg0) {
        // TODO 自動生成されたメソッド・スタブ

    }

    public void componentResized(ComponentEvent e) {
        if(getWidth() <= 0 || getHeight() <= 0) return;
        preSize = getSize();

        // 画面の中央の論理座標の更新
        transX = transX - preSize.width * 0.5 + getWidth() * 0.5;
        transY = transY - preSize.height * 0.5 + getHeight() * 0.5;

        // バッファイメージの更新
        bufferImage = new BufferedImage(getWidth() , getHeight(), BufferedImage.TYPE_INT_ARGB);
        bufferg = (Graphics2D)bufferImage.getGraphics();

        updateAffineTransform();

        // 再描画
        repaint();
    }

    public void componentShown(ComponentEvent arg0) {
        // TODO 自動生成されたメソッド・スタブ
    }

    public void flip() {
        figure.flip();
        updateAffineTransform();
        repaint();
    }

    public void showSilhouette(boolean bool) {
        showSilhouette = bool;
        repaint();
    }

    public Image getBufferedImage(){
        return bufferImage;
    }
}
