import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * The mod icon, in the server's (theblockyzombies.net) theme: two glowing magenta eyes on deep-purple "static".
 * Same shapes as the website's icon.svg (a 64 grid, drawn at 128). Usage: java tools/icon/MakeIcon.java <out.png>
 */
public class MakeIcon {
	static final int S = 128, K = S / 64;

	public static void main(String[] args) throws Exception {
		BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Backdrop: dark edge to lighter centre (#1a0826 -> #2a0e3e -> #4a2b6b), like the site's panels
		g.setPaint(new RadialGradientPaint(S / 2f, S / 2f, S * 0.75f, new float[] {0f, 0.55f, 1f},
			new Color[] {new Color(0x4a2b6b), new Color(0x2a0e3e), new Color(0x1a0826)}));
		g.fillRect(0, 0, S, S);
		// Static: fixed seed, so the icon is the same every time it is made
		Random r = new Random(24);
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				int a = r.nextInt(38);
				g.setColor(r.nextBoolean() ? new Color(255, 255, 255, a / 3) : new Color(0, 0, 0, a));
				g.fillRect(x, y, 1, 1);
			}
		}
		// Scanlines
		g.setColor(new Color(0, 0, 0, 40));
		for (int y = 0; y < S; y += 3) {
			g.fillRect(0, y, S, 1);
		}

		// Glow: the eye shapes in magenta on their own layer, blurred twice, drawn twice for strength
		BufferedImage glow = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
		Graphics2D gg = glow.createGraphics();
		gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		gg.setColor(new Color(0xc95ce8));
		eye(gg, 7, 25, 22, 15, 5);
		eye(gg, 35, 25, 22, 15, 5);
		gg.dispose();
		BufferedImage blurred = blur(blur(glow, 9), 9);
		g.drawImage(blurred, 0, 0, null);
		g.drawImage(blurred, 0, 0, null);

		// Bright cores, with a white-hot centre line
		g.setColor(new Color(0xe9a6f7));
		eye(g, 10, 28, 16, 10, 4);
		eye(g, 38, 28, 16, 10, 4);
		g.setColor(new Color(255, 240, 255, 200));
		eye(g, 13, 31, 10, 4, 2);
		eye(g, 41, 31, 10, 4, 2);
		g.dispose();

		ImageIO.write(img, "png", new File(args[0]));
	}

	static void eye(Graphics2D g, double x, double y, double w, double h, double rx) {
		g.fill(new RoundRectangle2D.Double(x * K, y * K, w * K, h * K, rx * 2 * K, rx * 2 * K));
	}

	static BufferedImage blur(BufferedImage src, int radius) {
		int n = radius * 2 + 1;
		float[] k = new float[n];
		float sum = 0;
		for (int i = 0; i < n; i++) {
			double x = i - radius;
			k[i] = (float) Math.exp(-x * x / (2.0 * (radius / 2.0) * (radius / 2.0)));
			sum += k[i];
		}
		for (int i = 0; i < n; i++) {
			k[i] /= sum;
		}
		BufferedImage padded = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		BufferedImage h = new ConvolveOp(new Kernel(n, 1, k), ConvolveOp.EDGE_NO_OP, null).filter(src, padded);
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		return new ConvolveOp(new Kernel(1, n, k), ConvolveOp.EDGE_NO_OP, null).filter(h, out);
	}
}
