package de.soderer.servermanagement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import de.soderer.servermanagement.check.StatusLevel;
import de.soderer.servermanagement.config.Server;
import de.soderer.swing.modern.DialogStyle;
import de.soderer.swing.modern.QuestionDialog;
import de.soderer.utilities.LangResources;

/**
 * Section of a server card: title with buttons, below it a table (only if there are entries). The table is recreated
 * on each rebuild of the card. All methods must be called in the event dispatch thread.
 */
abstract class ItemSection {
	protected final ServerManagementItem item;

	/** Table of the current card build, null if the section has no entries */
	protected JTable table;

	/** Status of the rows of the last check by model row index; rows without check result have no entry */
	private final Map<Integer, StatusLevel> rowLevels = new HashMap<>();

	/** Reason of an error or warning by model row index, shown as tooltip */
	private final Map<Integer, String> rowMessages = new HashMap<>();

	ItemSection(final ServerManagementItem item) {
		this.item = item;
	}

	abstract String getTitle();

	/**
	 * Creates the buttons of the section header.
	 */
	protected abstract void addButtons(JPanel buttonPanel);

	protected abstract boolean hasEntries();

	protected abstract DefaultTableModel createTableModel();

	/**
	 * Column widths and renderers.
	 */
	protected abstract void configureColumns(JTable newTable);

	/**
	 * Clears the result columns of the table model (status check not yet done).
	 */
	protected abstract void clearResults(DefaultTableModel model);

	/**
	 * Enables the buttons according to the selection.
	 */
	abstract void updateButtonStatus();

	@SuppressWarnings("static-method")
	protected int getSelectionMode() {
		return ListSelectionModel.MULTIPLE_INTERVAL_SELECTION;
	}

	/**
	 * Action on double click on a row; null for none.
	 */
	@SuppressWarnings("static-method")
	protected ItemTables.RowAction getDoubleClickAction() {
		return null;
	}

	/**
	 * Called after the table was created, configured and styled, before it is added to the card. Sections can install
	 * additional renderers or listeners here, which must not be replaced by the styling.
	 */
	@SuppressWarnings("unused")
	protected void tableCreated(final JTable newTable) {
		// Nothing to do by default
	}

	/**
	 * Adds header and table of the section to the content panel of the card.
	 */
	final void addTo(final JPanel contentPanel) {
		table = null;
		rowLevels.clear();
		rowMessages.clear();

		final JPanel buttonPanel = ItemTables.createSectionButtonPanel();
		addButtons(buttonPanel);
		ItemTables.addSectionHeader(contentPanel, getTitle(), buttonPanel);

		if (hasEntries()) {
			final JTable newTable = ItemTables.createTable(createTableModel(), getSelectionMode(), item::updateButtonStatus, getDoubleClickAction(), item, rowLevels::get, rowMessages::get);
			configureColumns(newTable);
			ItemTables.styleTable(newTable);
			tableCreated(newTable);
			ItemTables.addTablePanel(contentPanel, newTable, item);
			table = newTable;
		}
	}

	/**
	 * Clears the results of the last status check.
	 */
	final void resetStatus() {
		rowLevels.clear();
		rowMessages.clear();
		if (table != null) {
			clearResults(getModel());
			table.repaint();
		}
	}

	protected Server getServer() {
		return item.getServer();
	}

	protected ServerManagementGui getGui() {
		return item.getGui();
	}

	protected DefaultTableModel getModel() {
		return (DefaultTableModel) table.getModel();
	}

	/**
	 * True if the table exists and has the row (results of a check may arrive after the card was rebuilt).
	 */
	protected boolean hasRow(final int modelRow) {
		return table != null && modelRow >= 0 && modelRow < table.getModel().getRowCount();
	}

	/**
	 * Model index of the selected row, -1 if no row is selected.
	 */
	protected int getSelectedModelRow() {
		if (table == null || table.getSelectedRow() < 0) {
			return -1;
		}
		return table.convertRowIndexToModel(table.getSelectedRow());
	}

	/**
	 * Shows a result in the table: sets the text of a cell and colors the row by its status. Several results of the same
	 * row (e.g. two columns) only raise the status of the row.
	 */
	protected void showResult(final int modelRow, final int column, final String text, final StatusLevel level) {
		showResult(modelRow, column, text, level, null);
	}

	/**
	 * Shows a result in the table like {@link #showResult(int, int, String, StatusLevel)}, with the reason of an error or
	 * warning as tooltip of the row.
	 *
	 * @param message
	 *            reason of an error or warning; null for none
	 */
	protected void showResult(final int modelRow, final int column, final String text, final StatusLevel level, final String message) {
		if (hasRow(modelRow)) {
			table.clearSelection();
			table.getModel().setValueAt(text, modelRow, column);
			rowLevels.merge(modelRow, level, StatusLevel::max);
			if (message != null) {
				rowMessages.put(modelRow, message);
			}
			table.repaint();
		}
	}

	/**
	 * Creates a table model with the given columns and rows.
	 */
	protected static DefaultTableModel createModel(final List<String> columnNames, final List<String[]> rows) {
		final DefaultTableModel model = new DefaultTableModel();
		for (final String columnName : columnNames) {
			model.addColumn(columnName);
		}
		for (final String[] row : rows) {
			model.addRow(row);
		}
		return model;
	}

	/**
	 * The configuration of the server was changed: rebuild the card and mark the configuration as unsaved.
	 */
	protected void configurationChanged() {
		item.rebuild();
		item.signalUnsavedChanges();
	}

	protected void showError(final Exception e) {
		item.showError(e);
	}

	protected void showInformation(final String title, final String text) {
		new QuestionDialog(getGui(), title, text).withDialogStyle(DialogStyle.INFORMATION).open();
	}

	/**
	 * Asks for confirmation with OK and Cancel.
	 */
	protected boolean confirm(final String title, final String question) {
		return new QuestionDialog(getGui(), title, question, LangResources.get("ok"), LangResources.get("cancel")).open() == 0;
	}
}
