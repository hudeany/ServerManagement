package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.BoundedRangeModel;
import javax.swing.Box;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import de.soderer.servermanagement.check.StatusLevel;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.ButtonStyle;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.ModernUi;
import de.soderer.utilities.LangResources;

/**
 * Common building blocks of the sections of a server card: section header, tables with ModernUi look and status colors.
 */
final class ItemTables {
	/** Maximum number of table rows visible without scrolling */
	private static final int MAX_VISIBLE_TABLE_ROWS = 5;

	/**
	 * Action on a table row, e.g. on double click.
	 */
	@FunctionalInterface
	interface RowAction {
		/**
		 * @param modelRow
		 *            row index in the table model
		 */
		void run(int modelRow) throws Exception;
	}

	private ItemTables() {
		// Utility class
	}

	/**
	 * Transparent panel for the buttons of a section header.
	 */
	static JPanel createSectionButtonPanel() {
		final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttonPanel.setOpaque(false);
		return buttonPanel;
	}

	static Button createSectionButton(final String textResourceKey, final IconKind iconKind) {
		return new Button(LangResources.get(textResourceKey), iconKind, ButtonStyle.SECONDARY);
	}

	/**
	 * Adds the header of a section: title on the left, buttons on the right, separated from the previous section by a
	 * thin line.
	 */
	static void addSectionHeader(final JPanel contentPanel, final String title, final JPanel buttonPanel) {
		final boolean firstSection = contentPanel.getComponentCount() == 0;
		if (!firstSection) {
			contentPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		}

		final JPanel sectionHeader = new JPanel(new BorderLayout(12, 0));
		sectionHeader.setOpaque(false);
		if (firstSection) {
			sectionHeader.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		} else {
			sectionHeader.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(1, 0, 0, 0, ModernUi.INNER_BORDER),
					BorderFactory.createEmptyBorder(10, 0, 6, 0)));
		}
		sectionHeader.add(ModernUi.createSectionTitle(title), BorderLayout.WEST);
		sectionHeader.add(buttonPanel, BorderLayout.EAST);
		contentPanel.add(sectionHeader);
	}

	/**
	 * Creates a read-only table: a click toggles the selection of a row, a selection change calls the selection listener,
	 * a double click the double click action (errors are shown in an error dialog).
	 */
	static JTable createTable(final DefaultTableModel model, final int selectionMode, final Runnable selectionListener, final RowAction doubleClickAction, final ServerManagementItem item) {
		final JTable table = new JTable(model) {
			private static final long serialVersionUID = 2804713458829412663L;

			@Override
			public void changeSelection(final int rowIndex, final int columnIndex, final boolean toggle, final boolean extend) {
				// Always toggle on single selection
				super.changeSelection(rowIndex, columnIndex, !extend, extend);
			}
		};
		table.setDefaultEditor(Object.class, null);
		table.setSelectionMode(selectionMode);
		table.getSelectionModel().addListSelectionListener(e -> selectionListener.run());
		if (doubleClickAction != null) {
			table.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(final MouseEvent mouseEvent) {
					if (mouseEvent.getClickCount() == 2) {
						try {
							final int row = table.rowAtPoint(mouseEvent.getPoint());
							if (row >= 0) {
								doubleClickAction.run(table.convertRowIndexToModel(row));
							}
						} catch (final Exception e) {
							item.showError(e);
						}
					}
				}
			});
		}
		return table;
	}

	/**
	 * Sets the preferred width and optionally the horizontal alignment of a column.
	 *
	 * @param horizontalAlignment
	 *            SwingConstants.LEFT/RIGHT, or null for the default renderer
	 */
	static void configureColumn(final JTable table, final int column, final int preferredWidth, final Integer horizontalAlignment) {
		table.getColumnModel().getColumn(column).setPreferredWidth(preferredWidth);
		if (horizontalAlignment != null) {
			final DefaultTableCellRenderer renderer = new PaddedCellRenderer();
			renderer.setHorizontalAlignment(horizontalAlignment);
			table.getColumnModel().getColumn(column).setCellRenderer(renderer);
		}
	}

	/**
	 * Gives a table of the server card the ModernUi look and limits its visible height; the background colors of the
	 * status check stay visible.
	 */
	static void styleTable(final JTable table) {
		ModernUi.styleTable(table);
		table.setDefaultRenderer(Object.class, new PaddedCellRenderer());
		table.setShowVerticalLines(false);
		// Background of not yet checked tables (the status check sets the status colors)
		table.setBackground(Color.WHITE);

		final Dimension preferredSize = table.getPreferredSize();
		table.setPreferredScrollableViewportSize(new Dimension(preferredSize.width, Math.min(preferredSize.height, table.getRowHeight() * MAX_VISIBLE_TABLE_ROWS)));
	}

	/**
	 * Adds a table in a scroll pane to the content panel of the card. Mouse wheel events at the top or bottom end of the
	 * table are passed to the card, so the card scrolls on.
	 */
	static void addTablePanel(final JPanel contentPanel, final JTable table, final Component wheelEventTarget) {
		final JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.addMouseWheelListener(e -> {
			final boolean scrollDown = e.getWheelRotation() > 0;
			final BoundedRangeModel verticalScrollBarModel = scrollPane.getVerticalScrollBar().getModel();
			if ((!scrollDown && verticalScrollBarModel.getValue() <= verticalScrollBarModel.getMinimum())
					|| (scrollDown && verticalScrollBarModel.getValue() + verticalScrollBarModel.getExtent() >= verticalScrollBarModel.getMaximum())) {
				scrollPane.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(scrollPane, e, wheelEventTarget));
			}
		});
		ModernUi.styleScrollPane(scrollPane, Color.WHITE);

		final JPanel tablePanel = new JPanel(new BorderLayout());
		tablePanel.setOpaque(false);
		tablePanel.add(scrollPane);
		contentPanel.add(tablePanel);
	}

	/**
	 * The background of a table shows the worst status of its rows, so a later good row must not hide an earlier error.
	 */
	static void raiseTableColor(final JTable table, final StatusLevel level) {
		final Color color = getStatusColor(level);
		if (getColorSeverity(color) > getColorSeverity(table.getBackground())) {
			table.setBackground(color);
		}
	}

	private static Color getStatusColor(final StatusLevel level) {
		switch (level) {
			case ERROR:
				return CustomizedColor.RED;
			case WARNING:
				return CustomizedColor.YELLOW;
			case OK:
			default:
				return CustomizedColor.GREEN;
		}
	}

	/**
	 * Severity of a table status color: none (white) &lt; green &lt; yellow &lt; red.
	 */
	private static int getColorSeverity(final Color color) {
		if (CustomizedColor.RED.equals(color)) {
			return 3;
		} else if (CustomizedColor.YELLOW.equals(color)) {
			return 2;
		} else if (CustomizedColor.GREEN.equals(color)) {
			return 1;
		} else {
			return 0;
		}
	}

	/**
	 * Cell renderer with padding; uses the background of the table, which shows the status color.
	 */
	static class PaddedCellRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 4019347585127466351L;

		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean isSelected, final boolean hasFocus, final int row, final int column) {
			final Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
			return component;
		}
	}
}
