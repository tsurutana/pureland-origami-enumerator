package purelandgen.gui;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/**
 * 色と線種、矢印の形を定義
 * @author tsuruta
 *
 */

public class Config {
	
	final public static Border border = BorderFactory.createEtchedBorder();

	final public static Color COLOR_OUTLINE = Color.BLACK;
	final public static Color COLOR_MOUNTAIN = Color.RED;
	final public static Color COLOR_VALLEY = Color.GREEN;
	final public static Color COLOR_INSIDEREVERSE = Color.BLUE;
	final public static Color COLOR_OUTSIDEREVERSE = Color.CYAN;
	final public static Color COLOR_OPENSINK = Color.MAGENTA;
	final public static Color COLOR_CLOSEDSINK = Color.ORANGE;
	
	static float thickness = 1.0f;
	static float dash1[] = {5.0f};
	static float dash2[] = {10.0f, 3.0f, 2.0f, 3.0f};
	final public static BasicStroke STROKE_VALLEY = new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER, 3.0f, dash1, 0.0f);
    final public static BasicStroke STROKE_MOUNTAIN = new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER, 3.0f, dash2, 0.0f);
    final public static BasicStroke ROUNDSTROKE = new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    
    final public static int ARROW_HEADSIZE = 15;
    final public static int ARROW_HEADANGLE = 20;
}
