package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntFunction;

import javax.swing.BorderFactory;
import javax.swing.BoundedRangeModel;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

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
	 * a double click the double click action (errors are shown in an error dialog). Each row is colored by its status of
	 * the last check; the reason of an error or warning is shown as tooltip of the row.
	 *
	 * @param rowLevelProvider
	 *            status of a row by model row index, null for a row without check result (white)
	 * @param rowMessageProvider
	 *            reason of an error or warning by model row index, null for none
	 */
	static JTable createTable(final DefaultTableModel model, final int selectionMode, final Runnable selectionListener, final RowAction doubleClickAction, final ServerManagementItem item,
			final IntFunction<StatusLevel> rowLevelProvider, final IntFunction<String> rowMessageProvider) {
		final JTable table = new JTable(model) {
			private static final long serialVersionUID = 2804713458829412663L;

			@Override
			public void changeSelection(final int rowIndex, final int columnIndex, final boolean toggle, final boolean extend) {
				// Always toggle on single selection
				super.changeSelection(rowIndex, columnIndex, !extend, extend);
			}

			@Override
			public Component prepareRenderer(final TableCellRenderer renderer, final int row, final int column) {
				final Component component = super.prepareRenderer(renderer, row, column);
				final int modelRow = convertRowIndexToModel(row);
				if (!isRowSelected(row)) {
					// Always set, because DefaultTableCellRenderer keeps the last background for the following cells
					final StatusLevel rowLevel = rowLevelProvider.apply(modelRow);
					component.setBackground(rowLevel == null ? Color.WHITE : getStatusColor(rowLevel));
				}
				if (component instanceof JComponent) {
					((JComponent) component).setToolTipText(rowMessageProvider.apply(modelRow));
				}
				return component;
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
	 * Gives a table of the server card the ModernUi look and limits its visible height.
	 */
	static void styleTable(final JTable table) {
		ModernUi.styleTable(table);
		table.setDefaultRenderer(Object.class, new PaddedCellRenderer());
		table.setShowVerticalLines(false);
		// Background of rows without check result and of the empty area below the rows
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
	 * Background color of a row with the given status.
	 */
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
	 * Cell renderer with padding; the background of a row is set by the table according to the status of the row.
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
