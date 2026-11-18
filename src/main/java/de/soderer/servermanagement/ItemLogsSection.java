package de.soderer.servermanagement;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;

import de.soderer.servermanagement.config.Admin;
import de.soderer.servermanagement.config.Log;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.MultiInputDialog;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Utilities;

/**
 * Section of the log files of the server; a double click opens the SSH log viewer.
 */
class ItemLogsSection extends ItemSection {
	private Button editLogButton;
	private Button removeLogButton;

	ItemLogsSection(final ServerManagementItem item) {
		super(item);
	}

	@Override
	String getTitle() {
		return LangResources.get("section.logs");
	}

	@Override
	protected void addButtons(final JPanel buttonPanel) {
		final Button addLogButton = ItemTables.createSectionButton("addLog", IconKind.PLUS);
		addLogButton.addActionListener(event -> {
			try {
				final List<String> dialogResult = createLogDialog("addLog").open();
				if (dialogResult != null) {
					getServer().getLogs().add(new Log().setName(dialogResult.get(0)).setLocation(dialogResult.get(1)).setPath(dialogResult.get(2)));
					configurationChanged();
				}
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(addLogButton);

		// A double click on a log opens the log viewer, so editing needs its own button
		editLogButton = ItemTables.createSectionButton("editLog", IconKind.EDIT);
		editLogButton.addActionListener(event -> {
			try {
				editSelectedLog();
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(editLogButton);

		removeLogButton = ItemTables.createSectionButton("removeLog", IconKind.TRASH);
		removeLogButton.addActionListener(event -> {
			try {
				removeSelectedLog();
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(removeLogButton);
	}

	@Override
	protected boolean hasEntries() {
		return Utilities.isNotEmpty(getServer().getLogs());
	}

	@Override
	protected DefaultTableModel createTableModel() {
		final List<String[]> rows = new ArrayList<>();
		for (final Log log : getServer().getLogs()) {
			rows.add(new String[] { log.getName(), log.getLocation(), log.getPath() });
		}
		return createModel(List.of(LangResources.get("logName"), LangResources.get("location"), LangResources.get("path")), rows);
	}

	@Override
	protected void configureColumns(final JTable newTable) {
		ItemTables.configureColumn(newTable, 0, 50, null);
		ItemTables.configureColumn(newTable, 1, 200, SwingConstants.LEFT);
		ItemTables.configureColumn(newTable, 2, 200, SwingConstants.LEFT);
	}

	@Override
	protected ItemTables.RowAction getDoubleClickAction() {
		return this::openLogViewer;
	}

	@Override
	protected void clearResults(final DefaultTableModel model) {
		// Logs have no check results
	}

	@Override
	void updateButtonStatus() {
		removeLogButton.setEnabled(table != null && table.getSelectedRowCount() > 0);
		editLogButton.setEnabled(table != null && table.getSelectedRowCount() == 1);
	}

	private MultiInputDialog createLogDialog(final String titleResourceKey) {
		return new MultiInputDialog(getGui(), ServerManagement.APPLICATION_NAME + " " + LangResources.get(titleResourceKey), LangResources.get(titleResourceKey),
				LangResources.get("logName"),
				LangResources.get("location"),
				LangResources.get("path"));
	}

	/**
	 * Shows the content of a log with location "ssh://host[:port]" in the SSH log viewer.
	 */
	private void openLogViewer(final int logIndex) throws Exception {
		if (logIndex < 0 || logIndex >= getServer().getLogs().size()) {
			return;
		}
		final Log log = getServer().getLogs().get(logIndex);
		final String location = log.getLocation();
		if (location != null && location.toLowerCase().startsWith("ssh://")) {
			String host = location.substring(6);
			int port = 22;
			if (host.contains(":")) {
				port = Integer.parseInt(host.substring(host.indexOf(":") + 1));
				host = host.substring(0, host.indexOf(":"));
			}
			final Admin sshAdmin = item.getSshAdminChecked();
			final SshLogViewerDialog dialog = new SshLogViewerDialog(getGui(), getServer().getName() + " " + log.getName(), host, port, sshAdmin.getUsername(), sshAdmin.getPrivateKeyFile(), sshAdmin.getPasswordArray(), log.getPath());
			dialog.open();
		} else {
			throw new Exception("Unsupported log location '" + location + "', expected 'ssh://host[:port]'");
		}
	}

	/**
	 * Edits name, location and path of the selected log. The log is addressed by index (log names do not have to be
	 * unique).
	 */
	private void editSelectedLog() {
		if (table == null || table.getSelectedRowCount() != 1) {
			return;
		}
		final int logIndex = getSelectedModelRow();
		if (logIndex < 0 || logIndex >= getServer().getLogs().size()) {
			return;
		}
		final Log log = getServer().getLogs().get(logIndex);
		final List<String> dialogResult = createLogDialog("editLog")
				.withDefaultTexts(new String[] {
						log.getName() == null ? "" : log.getName(),
						log.getLocation() == null ? "" : log.getLocation(),
						log.getPath() == null ? "" : log.getPath() })
				.open();
		if (dialogResult != null) {
			log.setName(dialogResult.get(0)).setLocation(dialogResult.get(1)).setPath(dialogResult.get(2));
			configurationChanged();
		}
	}

	private void removeSelectedLog() {
		final int logIndex = getSelectedModelRow();
		if (logIndex < 0 || logIndex >= getServer().getLogs().size()) {
			return;
		}
		final String logName = getServer().getLogs().get(logIndex).getName();
		if (StringUtilities.isNotBlank(logName)
				&& confirm(ServerManagement.APPLICATION_NAME + " " + LangResources.get("removeLog"), LangResources.get("removeLogQuestion", logName))) {
			getServer().getLogs().remove(logIndex);
			configurationChanged();
		}
	}
}
