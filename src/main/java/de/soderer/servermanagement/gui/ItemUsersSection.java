package de.soderer.servermanagement.gui;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;

import de.soderer.servermanagement.AuthorizedKeysHelper;
import de.soderer.servermanagement.ServerManagement;
import de.soderer.servermanagement.config.Admin;
import de.soderer.servermanagement.config.User;
import de.soderer.servermanagement.sshkeys.UserKeyStatus;
import de.soderer.servermanagement.sshkeys.UserKeysSynchronizer;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.DialogStyle;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.ModernUi;
import de.soderer.swing.modern.QuestionDialog;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Utilities;

/**
 * Section of the managed SSH users: SSH admin, users and the status of their authorized keys.
 */
class ItemUsersSection extends ItemSection {
	private static final int CURRENT_KEYS_COLUMN = 2;
	private static final int SURPLUS_KEYS_COLUMN = 3;

	private Button readAllUsersButton;
	private Button removeUserButton;

	ItemUsersSection(final ServerManagementItem item) {
		super(item);
	}

	@Override
	String getTitle() {
		return LangResources.get("managedUsers");
	}

	@Override
	protected void addButtons(final JPanel buttonPanel) {
		final Button editAdminButton = ItemTables.createSectionButton("editAdmin", IconKind.EDIT);
		editAdminButton.addActionListener(event -> {
			try {
				editAdmin();
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(editAdminButton);

		readAllUsersButton = ItemTables.createSectionButton("readAllUsers", IconKind.SEARCH);
		readAllUsersButton.addActionListener(event -> {
			try {
				readAllUsers();
			} catch (final Exception e) {
				showError(e);
			}
		});
		buttonPanel.add(readAllUsersButton);

		removeUserButton = ItemTables.createSectionButton("removeUser", IconKind.TRASH);
		removeUserButton.addActionListener(event -> {
			try {
				removeSelectedUser();
			} catch (final Exception e) {
				showError(e);
			}
		});
		removeUserButton.setEnabled(false);
		buttonPanel.add(removeUserButton);
	}

	@Override
	protected boolean hasEntries() {
		return Utilities.isNotEmpty(getServer().getManagedUsers());
	}

	@Override
	protected DefaultTableModel createTableModel() {
		final List<String[]> rows = new ArrayList<>();
		for (final User managedUser : getServer().getManagedUsers()) {
			rows.add(new String[] { managedUser.getUsername(), Integer.toString(managedUser.getAuthorized_key_ids().size()), "n/a", "n/a" });
		}
		return createModel(List.of(LangResources.get("username"), LangResources.get("expectedAuthKeys"), LangResources.get("currentAuthKeys"), LangResources.get("surplusAuthKeys")), rows);
	}

	@Override
	protected int getSelectionMode() {
		return ListSelectionModel.SINGLE_SELECTION;
	}

	@Override
	protected void configureColumns(final JTable newTable) {
		ItemTables.configureColumn(newTable, 0, 100, null);
		ItemTables.configureColumn(newTable, 1, 100, SwingConstants.RIGHT);
		ItemTables.configureColumn(newTable, CURRENT_KEYS_COLUMN, 100, SwingConstants.RIGHT);
		ItemTables.configureColumn(newTable, SURPLUS_KEYS_COLUMN, 100, SwingConstants.RIGHT);
	}

	@Override
	protected ItemTables.RowAction getDoubleClickAction() {
		return this::editUser;
	}

	@Override
	protected void clearResults(final DefaultTableModel model) {
		for (int i = 0; i < model.getRowCount(); i++) {
			if (StringUtilities.isNotBlank((String) model.getValueAt(i, 0))) {
				model.setValueAt("n/a", i, CURRENT_KEYS_COLUMN);
				model.setValueAt("n/a", i, SURPLUS_KEYS_COLUMN);
			}
		}
	}

	@Override
	void updateButtonStatus() {
		readAllUsersButton.setEnabled(getServer().getSshAdmin() != null);
		final int selectedRow = getSelectedModelRow();
		removeUserButton.setEnabled(selectedRow >= 0 && StringUtilities.isNotBlank((String) getModel().getValueAt(selectedRow, 0)));
	}

	/**
	 * Shows the key status of the users (in the order of the managed users).
	 */
	void showStatus(final List<UserKeyStatus> userKeyStatusList) {
		for (int i = 0; i < userKeyStatusList.size(); i++) {
			final UserKeyStatus userKeyStatus = userKeyStatusList.get(i);
			showResult(i, CURRENT_KEYS_COLUMN, Integer.toString(userKeyStatus.currentKeys()), userKeyStatus.getLevel());
			showResult(i, SURPLUS_KEYS_COLUMN, Integer.toString(userKeyStatus.surplusKeys()), userKeyStatus.getLevel());
		}
	}

	private void editAdmin() throws Exception {
		final AdminDialog dialog = new AdminDialog(getGui(), ServerManagement.APPLICATION_NAME + " Admin " + getServer().getName(), LangResources.get("enterServerAdmin", getServer().getName()));
		final Admin sshAdmin = getServer().getSshAdmin();
		if (sshAdmin != null) {
			dialog.setUsername(sshAdmin.getUsername());
			dialog.setPrivateKeyFilePath(sshAdmin.getPrivateKeyPath());
			final char[] adminPassword = sshAdmin.getPasswordArray();
			if (adminPassword != null) {
				dialog.setPassword(adminPassword);
			}
		}
		if (dialog.open() == 0) {
			if (getServer().getSshAdmin() == null) {
				getServer().setSshAdmin(new Admin());
			}
			getServer().getSshAdmin().setUsername(dialog.getUsername());
			getServer().getSshAdmin().setPrivateKeyPath(dialog.getPrivateKeyFilePath());
			// Encrypted with the master key; null or empty removes the password
			getServer().getSshAdmin().setPasswordArray(dialog.getPassword());
			// The read users button depends on the SSH admin
			updateButtonStatus();
			item.signalUnsavedChanges();
		}
	}

	/**
	 * Adds all users with a home directory on the server, which are not managed yet.
	 */
	private void readAllUsers() throws Exception {
		final Admin sshAdmin = getServer().getSshAdmin();
		final List<String> usernames = AuthorizedKeysHelper.readUsernames(getServer().getHost(), sshAdmin.getUsername(), sshAdmin.getPrivateKeyFile(), sshAdmin.getPasswordArray());
		boolean changesHappened = false;
		for (final String username : usernames) {
			boolean found = false;
			for (final User user : getServer().getManagedUsers()) {
				if (username.equals(user.getUsername())) {
					found = true;
					break;
				}
			}
			if (!found) {
				final User newUser = new User();
				newUser.setUsername(username);
				getServer().getManagedUsers().add(newUser);
				changesHappened = true;
			}
		}

		if (changesHappened) {
			configurationChanged();
		}
	}

	private void removeSelectedUser() {
		final int userIndex = getSelectedModelRow();
		if (userIndex < 0 || userIndex >= getServer().getManagedUsers().size()) {
			return;
		}
		final String username = getServer().getManagedUsers().get(userIndex).getUsername();
		if (StringUtilities.isNotBlank(username)
				&& confirm(ServerManagement.APPLICATION_NAME + " " + LangResources.get("removeUser"), LangResources.get("removeUserQuestion", username))) {
			getServer().getManagedUsers().remove(userIndex);
			configurationChanged();
		}
	}

	/**
	 * Edits the key assignments of a user and uploads the resulting authorized_keys to the server.
	 */
	private void editUser(final int userIndex) throws Exception {
		final User user = getServer().getManagedUsers().get(userIndex);
		final UserKeysSynchronizer synchronizer = new UserKeysSynchronizer(getServer(), getGui().getServerManagementConfiguration(), user);
		synchronizer.load();

		final UserPublicKeysDialog userPublicKeysDialog = new UserPublicKeysDialog(getGui(), ServerManagement.APPLICATION_NAME, LangResources.get("editUserPublicKeys", user.getUsername(), getServer().getHost()),
				synchronizer.getAdminKeyHash(), synchronizer.getExistingKeys(), synchronizer.getConfiguredKeys(), synchronizer.getKnownKeys(), synchronizer.getKeyNamesByKeyId());
		if (!userPublicKeysDialog.open()) {
			return;
		}

		try {
			final UserKeysSynchronizer.SelectionResult selectionResult = synchronizer.applySelection(
					userPublicKeysDialog.getKnownKeys().keySet(),
					userPublicKeysDialog.getConfiguredKeys().keySet(),
					userPublicKeysDialog.getExistingKeys().keySet());
			for (final String unknownKeyHash : selectionResult.unknownKeyHashes()) {
				new QuestionDialog(getGui(), ServerManagement.APPLICATION_NAME + " ERROR", "Cannot find authorized key for storage. Hash: " + unknownKeyHash).withDialogStyle(DialogStyle.ERROR).open();
			}

			// The configuration was already changed, so it must be marked as unsaved even if the upload fails
			item.signalUnsavedChanges();

			if (synchronizer.isUploadNeeded(selectionResult.newAuthorizedKeys())) {
				try {
					synchronizer.upload(selectionResult.newAuthorizedKeys());
					new QuestionDialog(getGui(), ServerManagement.APPLICATION_NAME, LangResources.get("succesfullyUpdatedSshUserKeys")).withDialogStyle(DialogStyle.INFORMATION).withBackgroundColor(ModernUi.SUCCESS_LIGHT).open();
				} catch (final Exception e) {
					showError(e);
				}
			} else {
				showInformation(ServerManagement.APPLICATION_NAME, LangResources.get("noServerChangeNeeded"));
			}
		} catch (final Exception e) {
			showError(e);
		} finally {
			// The number of expected keys may have changed
			item.rebuild();
		}
	}
}
