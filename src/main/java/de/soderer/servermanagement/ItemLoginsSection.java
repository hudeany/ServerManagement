package de.soderer.servermanagement;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;

import de.soderer.servermanagement.check.LoginCheckResult;
import de.soderer.servermanagement.config.Login;
import de.soderer.servermanagement.config.LoginType;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.IconKind;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Utilities;

/**
 * Section of the logins (BasicAuth, OIDC, Jenkins) and their check results.
 */
class ItemLoginsSection extends ItemSection {
	/** Column of the check result */
	private static final int RESULT_COLUMN = 3;

	private Button removeLoginButton;

	ItemLoginsSection(final ServerManagementItem item) {
		super(item);
	}

	@Override
	String getTitle() {
		return LangResources.get("section.logins");
	}

	@Override
	protected void addButtons(final JPanel buttonPanel) {
		final Button addLoginButton = ItemTables.createSectionButton("addLogin", IconKind.PLUS);
		addLoginButton.addActionListener(event -> {
			try {
				final LoginDialog dialog = new LoginDialog(getGui(), ServerManagement.APPLICATION_NAME + " " + LangResources.get("addLogin"));
				if (dialog.open()) {
					getServer().getLogins().add(dialog.applyTo(new Login()));
					configurationChanged();
				}
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(addLoginButton);

		removeLoginButton = ItemTables.createSectionButton("removeLogin", IconKind.TRASH);
		removeLoginButton.addActionListener(event -> {
			try {
				removeSelectedLogin();
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(removeLoginButton);

		final Button checkLoginsButton = ItemTables.createSectionButton("checkLogins", IconKind.REFRESH);
		checkLoginsButton.addActionListener(event -> {
			try {
				checkLogins();
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(checkLoginsButton);
	}

	@Override
	protected boolean hasEntries() {
		return Utilities.isNotEmpty(getServer().getLogins());
	}

	@Override
	protected DefaultTableModel createTableModel() {
		final List<String[]> rows = new ArrayList<>();
		for (final Login login : getServer().getLogins()) {
			// The password is never shown in the table
			rows.add(new String[] { login.getUrl(), login.getUsername(), getLoginTypeDisplayName(login.getLoginType()), "" });
		}
		return createModel(List.of(LangResources.get("url"), LangResources.get("username"), LangResources.get("loginType"), LangResources.get("loginResult")), rows);
	}

	@Override
	protected int getSelectionMode() {
		return ListSelectionModel.SINGLE_SELECTION;
	}

	@Override
	protected void configureColumns(final JTable newTable) {
		ItemTables.configureColumn(newTable, 0, 400, null);
		ItemTables.configureColumn(newTable, 1, 150, null);
		ItemTables.configureColumn(newTable, 2, 80, null);
		ItemTables.configureColumn(newTable, RESULT_COLUMN, 100, SwingConstants.RIGHT);
	}

	@Override
	protected ItemTables.RowAction getDoubleClickAction() {
		return this::editLogin;
	}

	@Override
	protected void clearResults(final DefaultTableModel model) {
		for (int i = 0; i < model.getRowCount(); i++) {
			model.setValueAt("", i, RESULT_COLUMN);
		}
	}

	@Override
	void updateButtonStatus() {
		removeLoginButton.setEnabled(getSelectedModelRow() >= 0);
	}

	/**
	 * Shows the check results (in the order of the logins). Logins are addressed by index, because the same URL may be
	 * configured with different usernames.
	 */
	void showResults(final List<LoginCheckResult> results) {
		for (int i = 0; i < results.size(); i++) {
			showResult(i, RESULT_COLUMN, results.get(i).getText(), results.get(i).getLevel());
		}
	}

	static String getLoginTypeDisplayName(final LoginType loginType) {
		if (loginType == LoginType.OIDC) {
			return LangResources.get("loginType.oidc");
		} else if (loginType == LoginType.Jenkins) {
			return LangResources.get("loginType.jenkins");
		} else {
			return LangResources.get("loginType.basicauth");
		}
	}

	private void editLogin(final int loginIndex) throws Exception {
		final Login login = getServer().getLogins().get(loginIndex);
		final LoginDialog dialog = new LoginDialog(getGui(), ServerManagement.APPLICATION_NAME + " " + LangResources.get("editLogin")).withLogin(login);
		if (dialog.open()) {
			dialog.applyTo(login);
			configurationChanged();
		}
	}

	private void removeSelectedLogin() {
		final int loginIndex = getSelectedModelRow();
		if (loginIndex < 0 || loginIndex >= getServer().getLogins().size()) {
			return;
		}
		final Login login = getServer().getLogins().get(loginIndex);
		final String loginDisplayName = login.getUsername() + " @ " + login.getUrl();
		if (confirm(ServerManagement.APPLICATION_NAME + " " + LangResources.get("removeLogin"), LangResources.get("removeLoginQuestion", loginDisplayName))) {
			getServer().getLogins().remove(loginIndex);
			configurationChanged();
		}
	}

	/**
	 * Checks all logins of the server and shows the results as dialog.
	 */
	private void checkLogins() {
		resetStatus();
		final List<LoginCheckResult> results = item.runLoginCheck();
		showResults(results);

		final StringBuilder resultText = new StringBuilder();
		final List<Login> logins = getServer().getLogins();
		for (int i = 0; i < results.size() && i < logins.size(); i++) {
			final Login login = logins.get(i);
			item.raiseStatus(results.get(i).getLevel());
			resultText.append(login.getUsername()).append(" @ ").append(login.getUrl())
			.append(" (").append(getLoginTypeDisplayName(login.getLoginType())).append("): ")
			.append(results.get(i).getText()).append("\n");
		}
		showInformation(ServerManagement.APPLICATION_NAME + " " + LangResources.get("checkLogins") + " - " + getServer().getName(), resultText.toString().trim());
	}
}
