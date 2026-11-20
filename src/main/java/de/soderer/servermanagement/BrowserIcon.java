package de.soderer.servermanagement;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;

import javax.swing.Icon;

/**
 * Dependency-free, painted "web browser" icon (a globe with meridian and latitude lines). It scales to any size
 * without image resources and can be painted in any color.
 */
public class BrowserIcon implements Icon {
	private final int size;
	private final Color color;

	/**
	 * @param size
	 *            width and height of the icon in pixels
	 * @param color
	 *            line color of the globe
	 */
	public BrowserIcon(final int size, final Color color) {
		this.size = size;
		this.color = color;
	}

	@Override
	public int getIconWidth() {
		return size;
	}

	@Override
	public int getIconHeight() {
		return size;
	}

	@Override
	public void paintIcon(final Component component, final Graphics graphics, final int x, final int y) {
		final Graphics2D g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.translate(x, y);
			g.setColor(color);

			final float strokeWidth = Math.max(1f, size / 14f);
			g.setStroke(new BasicStroke(strokeWidth));

			final double inset = strokeWidth / 2.0 + 0.5;
			final double diameter = size - 2 * inset;
			final double radius = diameter / 2.0;
			final double center = size / 2.0;

			// Outline of the globe
			g.draw(new Ellipse2D.Double(inset, inset, diameter, diameter));

			// Meridians: central vertical line and an inner ellipse
			g.draw(new Line2D.Double(center, inset, center, inset + diameter));
			final double meridianWidth = diameter * 0.46;
			g.draw(new Ellipse2D.Double(center - meridianWidth / 2.0, inset, meridianWidth, diameter));

			// Equator
			g.draw(new Line2D.Double(inset, center, inset + diameter, center));

			// Two latitude lines, ending exactly on the outline
			final double latitudeOffset = radius * 0.55;
			final double latitudeHalfWidth = Math.sqrt(radius * radius - latitudeOffset * latitudeOffset);
			g.draw(new Line2D.Double(center - latitudeHalfWidth, center - latitudeOffset, center + latitudeHalfWidth, center - latitudeOffset));
			g.draw(new Line2D.Double(center - latitudeHalfWidth, center + latitudeOffset, center + latitudeHalfWidth, center + latitudeOffset));
		} finally {
			g.dispose();
		}
	}
}
