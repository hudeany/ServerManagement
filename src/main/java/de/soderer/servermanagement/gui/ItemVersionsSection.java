package de.soderer.servermanagement.gui;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;

import de.soderer.servermanagement.ServerManagement;
import de.soderer.servermanagement.check.VersionResult;
import de.soderer.servermanagement.config.Version;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.MultiInputDialog;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Utilities;

/**
 * Section of the version entries and their read versions. The table only shows the name and the read version; location
 * and path are part of the configuration and are edited in the version dialog.
 */
class ItemVersionsSection extends ItemSection {
	/** Column of the read version */
	private static final int VERSION_COLUMN = 1;

	private Button removeVersionButton;

	ItemVersionsSection(final ServerManagementItem item) {
		super(item);
	}

	@Override
	String getTitle() {
		return LangResources.get("section.versions");
	}

	@Override
	protected void addButtons(final JPanel buttonPanel) {
		final Button addVersionButton = ItemTables.createSectionButton("addVersion", IconKind.PLUS);
		addVersionButton.addActionListener(event -> {
			try {
				final List<String> dialogResult = createVersionDialog("addVersion").open();
				if (dialogResult != null) {
					getServer().getVersions().add(new Version().setName(dialogResult.get(0)).setLocation(dialogResult.get(1)).setPath(dialogResult.get(2)));
					configurationChanged();
				}
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(addVersionButton);

		removeVersionButton = ItemTables.createSectionButton("removeVersion", IconKind.TRASH);
		removeVersionButton.addActionListener(event -> {
			try {
				removeSelectedVersion();
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(removeVersionButton);

		final Button readVersionsButton = ItemTables.createSectionButton("readVersions", IconKind.REFRESH);
		readVersionsButton.addActionListener(event -> {
			try {
				readVersions();
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(readVersionsButton);
	}

	@Override
	protected boolean hasEntries() {
		return Utilities.isNotEmpty(getServer().getVersions());
	}

	@Override
	protected DefaultTableModel createTableModel() {
		final List<String[]> rows = new ArrayList<>();
		for (final Version version : getServer().getVersions()) {
			rows.add(new String[] { version.getName(), "" });
		}
		return createModel(List.of(LangResources.get("versionName"), LangResources.get("version")), rows);
	}

	@Override
	protected void configureColumns(final JTable newTable) {
		ItemTables.configureColumn(newTable, 0, 200, null);
		ItemTables.configureColumn(newTable, VERSION_COLUMN, 200, SwingConstants.LEFT);
	}

	@Override
	protected ItemTables.RowAction getDoubleClickAction() {
		return this::editVersion;
	}

	@Override
	protected void clearResults(final DefaultTableModel model) {
		for (int i = 0; i < model.getRowCount(); i++) {
			if (StringUtilities.isNotBlank((String) model.getValueAt(i, 0))) {
				model.setValueAt("", i, VERSION_COLUMN);
			}
		}
	}

	@Override
	void updateButtonStatus() {
		removeVersionButton.setEnabled(table != null && table.getSelectedRowCount() > 0);
	}

	/**
	 * Shows the read versions (in the order of the version entries).
	 */
	void showResults(final List<VersionResult> results) {
		for (int i = 0; i < results.size(); i++) {
			showResult(i, VERSION_COLUMN, results.get(i).text(), results.get(i).level(), results.get(i).message());
		}
	}

	private MultiInputDialog createVersionDialog(final String titleResourceKey) {
		return new MultiInputDialog(getGui(), ServerManagement.APPLICATION_NAME + " " + LangResources.get(titleResourceKey), LangResources.get(titleResourceKey),
				LangResources.get("versionName"),
				LangResources.get("location"),
				LangResources.get("path"));
	}

	private void editVersion(final int versionIndex) {
		if (versionIndex < 0 || versionIndex >= getServer().getVersions().size()) {
			return;
		}
		final Version version = getServer().getVersions().get(versionIndex);
		if (StringUtilities.isBlank(version.getName())) {
			return;
		}
		final List<String> dialogResult = createVersionDialog("editVersion")
				.withDefaultTexts(new String[] {
						version.getName(),
						version.getLocation() == null ? "" : version.getLocation(),
						version.getPath() == null ? "" : version.getPath() })
				.open();
		if (dialogResult != null) {
			version.setName(dialogResult.get(0)).setLocation(dialogResult.get(1)).setPath(dialogResult.get(2));
			configurationChanged();
		}
	}

	private void removeSelectedVersion() {
		final int versionIndex = getSelectedModelRow();
		if (versionIndex < 0 || versionIndex >= getServer().getVersions().size()) {
			return;
		}
		final String versionName = getServer().getVersions().get(versionIndex).getName();
		if (StringUtilities.isNotBlank(versionName)
				&& confirm(ServerManagement.APPLICATION_NAME + " " + LangResources.get("removeVersion"), LangResources.get("removeVersionQuestion", versionName))) {
			getServer().getVersions().remove(versionIndex);
			configurationChanged();
		}
	}

	/**
	 * Reads all versions of the server and shows them as dialog.
	 */
	private void readVersions() {
		resetStatus();
		final List<VersionResult> results = item.runVersionCheck();
		showResults(results);

		final StringBuilder resultText = new StringBuilder();
		for (final VersionResult result : results) {
			item.raiseStatus(result.level());
			// The dialog also shows the reason of an error
			resultText.append(result.name()).append(": ").append(result.getTextWithMessage()).append("\n");
		}
		showInformation(ServerManagement.APPLICATION_NAME + " " + LangResources.get("readVersions"), getServer().getName() + ":\n\n" + resultText.toString().trim());
	}
}
