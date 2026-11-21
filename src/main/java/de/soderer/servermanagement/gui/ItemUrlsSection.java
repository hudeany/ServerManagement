package de.soderer.servermanagement.gui;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;

import de.soderer.servermanagement.ServerManagement;
import de.soderer.servermanagement.check.UrlCheckResult;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.QuestionDialog;
import de.soderer.swing.modern.SimpleInputDialog;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Utilities;

/**
 * Section of the checked URLs and their response times.
 */
class ItemUrlsSection extends ItemSection {
	/** Column of the response time */
	private static final int TIME_COLUMN = 1;

	private Button removeUrlButton;

	ItemUrlsSection(final ServerManagementItem item) {
		super(item);
	}

	@Override
	String getTitle() {
		return LangResources.get("section.urls");
	}

	@Override
	protected void addButtons(final JPanel buttonPanel) {
		final Button addUrlButton = ItemTables.createSectionButton("addUrl", IconKind.PLUS);
		addUrlButton.addActionListener(event -> {
			try {
				final String url = new SimpleInputDialog(getGui(), ServerManagement.APPLICATION_NAME + " " + LangResources.get("addUrl"), LangResources.get("addUrl")).open();
				if (StringUtilities.isNotBlank(url)) {
					getServer().getUrls().add(url.trim());
					configurationChanged();
				}
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(addUrlButton);

		removeUrlButton = ItemTables.createSectionButton("removeUrl", IconKind.TRASH);
		removeUrlButton.addActionListener(event -> {
			try {
				removeSelectedUrls();
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(removeUrlButton);
	}

	@Override
	protected boolean hasEntries() {
		return Utilities.isNotEmpty(getServer().getUrls());
	}

	@Override
	protected DefaultTableModel createTableModel() {
		final List<String[]> rows = new ArrayList<>();
		for (final String url : getServer().getUrls()) {
			rows.add(new String[] { url, "" });
		}
		return createModel(List.of(LangResources.get("url"), LangResources.get("time")), rows);
	}

	@Override
	protected void configureColumns(final JTable newTable) {
		ItemTables.configureColumn(newTable, 0, 400, null);
		ItemTables.configureColumn(newTable, TIME_COLUMN, 100, SwingConstants.RIGHT);
	}

	@Override
	protected ItemTables.RowAction getDoubleClickAction() {
		return this::editUrl;
	}

	@Override
	protected void clearResults(final DefaultTableModel model) {
		for (int i = 0; i < model.getRowCount(); i++) {
			if (StringUtilities.isNotBlank((String) model.getValueAt(i, 0))) {
				model.setValueAt("", i, TIME_COLUMN);
			}
		}
	}

	@Override
	void updateButtonStatus() {
		removeUrlButton.setEnabled(table != null && table.getSelectedRowCount() > 0);
	}

	/**
	 * Shows the check results (in the order of the URLs).
	 */
	void showResults(final List<UrlCheckResult> results) {
		for (int i = 0; i < results.size(); i++) {
			showResult(i, TIME_COLUMN, results.get(i).getText(), results.get(i).getLevel());
		}
	}

	/**
	 * Edits an URL. The URL is addressed by index, the same URL may be configured twice.
	 */
	private void editUrl(final int urlIndex) {
		if (urlIndex < 0 || urlIndex >= getServer().getUrls().size()) {
			return;
		}
		final SimpleInputDialog urlInputDialog = new SimpleInputDialog(getGui(), ServerManagement.APPLICATION_NAME + " " + LangResources.get("editUrl"), LangResources.get("editUrl"));
		urlInputDialog.setDefaultText(getServer().getUrls().get(urlIndex));
		final String url = urlInputDialog.open();
		// Canceled or emptied: the URL stays unchanged (removing is done by the remove button)
		if (StringUtilities.isNotBlank(url) && !url.trim().equals(getServer().getUrls().get(urlIndex))) {
			getServer().getUrls().set(urlIndex, url.trim());
			configurationChanged();
		}
	}

	/**
	 * Removes all selected URLs, addressed by index (the same URL may be configured twice).
	 */
	private void removeSelectedUrls() {
		if (table == null || table.getSelectedRowCount() == 0) {
			return;
		}
		if (new QuestionDialog(getGui(), ServerManagement.APPLICATION_NAME + " " + LangResources.get("removeUrl"), LangResources.get("removeUrl"), LangResources.get("yes"), LangResources.get("cancel")).open() == 0) {
			final List<Integer> urlIndexes = new ArrayList<>();
			for (final int selectedRow : table.getSelectedRows()) {
				urlIndexes.add(table.convertRowIndexToModel(selectedRow));
			}
			// Remove from the end, so the other indexes stay valid
			urlIndexes.sort((a, b) -> Integer.compare(b, a));
			for (final int urlIndex : urlIndexes) {
				if (urlIndex < getServer().getUrls().size()) {
					getServer().getUrls().remove(urlIndex);
				}
			}
			configurationChanged();
		}
	}
}
