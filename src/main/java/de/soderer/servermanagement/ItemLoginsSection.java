package de.soderer.servermanagement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

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
	/** Column without title with the browser icon, which opens the login page in the default browser */
	private static final int BROWSER_COLUMN = 0;

	/** Column of the check result */
	private static final int RESULT_COLUMN = 4;

	private static final int BROWSER_COLUMN_WIDTH = 28;
	private static final int BROWSER_ICON_SIZE = 16;
	private static final Color BROWSER_ICON_COLOR = new Color(0x2F6FDB);

	private Button removeLoginButton;

	/**
	 * True, if the last mouse press on the table hit a browser icon. A double click on the icon then only opens the
	 * browser and not additionally the edit dialog.
	 */
	private boolean lastPressOnBrowserIcon = false;

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
			// The password is never shown in the table; the browser column is painted by its own renderer
			rows.add(new String[] { "", login.getUrl(), login.getUsername(), getLoginTypeDisplayName(login.getLoginType()), "" });
		}
		return createModel(List.of("", LangResources.get("url"), LangResources.get("username"), LangResources.get("loginType"), LangResources.get("loginResult")), rows);
	}

	@Override
	protected int getSelectionMode() {
		return ListSelectionModel.SINGLE_SELECTION;
	}

	@Override
	protected void configureColumns(final JTable newTable) {
		ItemTables.configureColumn(newTable, BROWSER_COLUMN, BROWSER_COLUMN_WIDTH, SwingConstants.CENTER);
		ItemTables.configureColumn(newTable, 1, 400, null);
		ItemTables.configureColumn(newTable, 2, 150, null);
		ItemTables.configureColumn(newTable, 3, 80, null);
		ItemTables.configureColumn(newTable, RESULT_COLUMN, 100, SwingConstants.RIGHT);
	}

	/**
	 * Installs the browser icon renderer and the click handling of the browser column. This is done after the styling
	 * of the table, so the renderer cannot be replaced by it.
	 */
	@Override
	protected void tableCreated(final JTable newTable) {
		final TableColumn browserColumn = newTable.getColumnModel().getColumn(newTable.convertColumnIndexToView(BROWSER_COLUMN));
		browserColumn.setMinWidth(BROWSER_COLUMN_WIDTH);
		browserColumn.setMaxWidth(BROWSER_COLUMN_WIDTH);
		browserColumn.setPreferredWidth(BROWSER_COLUMN_WIDTH);
		browserColumn.setResizable(false);
		browserColumn.setCellRenderer(new BrowserCellRenderer(browserColumn.getCellRenderer(), this::getLogin));

		final MouseAdapter browserMouseHandler = new MouseAdapter() {
			@Override
			public void mousePressed(final MouseEvent event) {
				lastPressOnBrowserIcon = getLaunchableLoginIndexAt(newTable, event.getPoint()) >= 0;
			}

			@Override
			public void mouseClicked(final MouseEvent event) {
				if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 1) {
					final int loginIndex = getLaunchableLoginIndexAt(newTable, event.getPoint());
					if (loginIndex >= 0) {
						openLoginInBrowser(getServer().getLogins().get(loginIndex));
					}
				}
			}

			@Override
			public void mouseMoved(final MouseEvent event) {
				final boolean overBrowserIcon = getLaunchableLoginIndexAt(newTable, event.getPoint()) >= 0;
				newTable.setCursor(overBrowserIcon ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}

			@Override
			public void mouseExited(final MouseEvent event) {
				newTable.setCursor(Cursor.getDefaultCursor());
			}
		};
		newTable.addMouseListener(browserMouseHandler);
		newTable.addMouseMotionListener(browserMouseHandler);
	}

	@Override
	protected ItemTables.RowAction getDoubleClickAction() {
		return loginIndex -> {
			// A double click on the browser icon must not open the edit dialog
			if (!lastPressOnBrowserIcon) {
				editLogin(loginIndex);
			}
		};
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

	/**
	 * Login of a model row; null if the row does not exist (anymore).
	 */
	private Login getLogin(final int modelRow) {
		final List<Login> logins = getServer().getLogins();
		return modelRow >= 0 && modelRow < logins.size() ? logins.get(modelRow) : null;
	}

	/**
	 * Index of the login, if the point is in the browser column of a row with a URL that can be opened; otherwise -1.
	 */
	private int getLaunchableLoginIndexAt(final JTable sourceTable, final Point point) {
		final int viewRow = sourceTable.rowAtPoint(point);
		final int viewColumn = sourceTable.columnAtPoint(point);
		if (viewRow < 0 || viewColumn < 0 || sourceTable.convertColumnIndexToModel(viewColumn) != BROWSER_COLUMN) {
			return -1;
		}
		final int loginIndex = sourceTable.convertRowIndexToModel(viewRow);
		return LoginBrowserLauncher.isLaunchable(getLogin(loginIndex)) ? loginIndex : -1;
	}

	/**
	 * Opens the URL of the login in the default browser and copies the password to the clipboard for a limited time.
	 * The login fields of a page in an external browser cannot be filled in from outside.
	 */
	private void openLoginInBrowser(final Login login) {
		final URI uri = LoginBrowserLauncher.toBrowserUri(login.getUrl());
		if (uri == null) {
			return;
		}
		final boolean passwordCopied = LoginBrowserLauncher.copyPasswordToClipboard(login);

		// Desktop.browse may block on some platforms, so it runs outside of the event dispatch thread
		final Thread browserThread = new Thread(() -> {
			try {
				LoginBrowserLauncher.browse(uri);
			} catch (final Exception e) {
				SwingUtilities.invokeLater(() -> {
					if (passwordCopied) {
						LoginBrowserLauncher.clearPasswordFromClipboard();
					}
					showError(new Exception(LangResources.get("error.cannotOpenBrowser", uri.toString(), e.getMessage() != null ? e.getMessage() : e.toString()), e));
				});
			}
		}, "OpenLoginInBrowser");
		browserThread.setDaemon(true);
		browserThread.start();
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

	/**
	 * Shows the browser icon centered in the cell. Background, foreground and border are taken from the regular
	 * renderer of the column, so the row colors of the status check and the selection stay the same.
	 */
	private static class BrowserCellRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		/** Regular renderer of the column; null means the default renderer of the table */
		private final transient TableCellRenderer baseRenderer;

		private final transient IntFunction<Login> loginByModelRow;

		private final transient Icon icon = new BrowserIcon(BROWSER_ICON_SIZE, BROWSER_ICON_COLOR);

		BrowserCellRenderer(final TableCellRenderer baseRenderer, final IntFunction<Login> loginByModelRow) {
			this.baseRenderer = baseRenderer;
			this.loginByModelRow = loginByModelRow;
			setHorizontalAlignment(SwingConstants.CENTER);
		}

		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean isSelected, final boolean hasFocus, final int row, final int column) {
			super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);

			final TableCellRenderer renderer = baseRenderer != null ? baseRenderer : table.getDefaultRenderer(Object.class);
			final Component baseComponent = renderer.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
			setBackground(baseComponent.getBackground());
			setForeground(baseComponent.getForeground());
			setOpaque(baseComponent.isOpaque());
			if (baseComponent instanceof final JComponent baseJComponent) {
				setBorder(baseJComponent.getBorder());
			}
			setHorizontalAlignment(SwingConstants.CENTER);

			final Login login = loginByModelRow.apply(table.convertRowIndexToModel(row));
			if (LoginBrowserLauncher.isLaunchable(login)) {
				setIcon(icon);
				setToolTipText(login.hasPassword()
						? LangResources.get("openLoginInBrowserWithPassword", String.valueOf(LoginBrowserLauncher.CLIPBOARD_CLEAR_DELAY_MILLIS / 1000))
								: LangResources.get("openLoginInBrowser"));
			} else {
				setIcon(null);
				setToolTipText(null);
			}
			return this;
		}
	}
}
