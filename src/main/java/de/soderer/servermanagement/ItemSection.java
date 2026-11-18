package de.soderer.servermanagement;

import java.awt.Color;
import java.util.List;

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
	 * Adds header and table of the section to the content panel of the card.
	 */
	final void addTo(final JPanel contentPanel) {
		table = null;

		final JPanel buttonPanel = ItemTables.createSectionButtonPanel();
		addButtons(buttonPanel);
		ItemTables.addSectionHeader(contentPanel, getTitle(), buttonPanel);

		if (hasEntries()) {
			final JTable newTable = ItemTables.createTable(createTableModel(), getSelectionMode(), item::updateButtonStatus, getDoubleClickAction(), item);
			configureColumns(newTable);
			ItemTables.styleTable(newTable);
			ItemTables.addTablePanel(contentPanel, newTable, item);
			table = newTable;
		}
	}

	/**
	 * Clears the results of the last status check.
	 */
	final void resetStatus() {
		if (table != null) {
			clearResults(getModel());
			table.setBackground(Color.WHITE);
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
	 * Shows a result in the table: sets the text of a cell and raises the table color.
	 */
	protected void showResult(final int modelRow, final int column, final String text, final StatusLevel level) {
		if (hasRow(modelRow)) {
			table.clearSelection();
			table.getModel().setValueAt(text, modelRow, column);
			ItemTables.raiseTableColor(table, level);
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
