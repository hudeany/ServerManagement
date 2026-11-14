package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.net.ssl.TrustManager;
import javax.swing.BorderFactory;
import javax.swing.BoundedRangeModel;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import de.soderer.applicationbase.ApplicationConfigurationProperties;
import de.soderer.json.JsonReader;
import de.soderer.json.JsonValueString;
import de.soderer.network.HttpMethod;
import de.soderer.network.HttpRequest;
import de.soderer.network.HttpResponse;
import de.soderer.network.HttpUtilities;
import de.soderer.network.trustmanager.TrustManagerUtilities;
import de.soderer.remote.SftpConnection;
import de.soderer.servermanagement.config.Admin;
import de.soderer.servermanagement.config.KnownPublicSshKey;
import de.soderer.servermanagement.config.Log;
import de.soderer.servermanagement.config.Login;
import de.soderer.servermanagement.config.LoginType;
import de.soderer.servermanagement.config.Server;
import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.servermanagement.config.User;
import de.soderer.servermanagement.config.Version;
import de.soderer.servermanagement.worker.RefreshServerItemStatusWorker;
import de.soderer.sshkeyformats.AuthorizedKey;
import de.soderer.sshkeyformats.SshKey;
import de.soderer.sshkeyformats.SshKeyReader;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.ButtonStyle;
import de.soderer.swing.modern.Card;
import de.soderer.swing.modern.DialogStyle;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.ModernUi;
import de.soderer.swing.modern.MultiInputDialog;
import de.soderer.swing.modern.QuestionDialog;
import de.soderer.swing.modern.SimpleInputDialog;
import de.soderer.utilities.IoUtilities;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.PropertiesReader;
import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Utilities;

public class ServerManagementItem extends JPanel {
	private static final long serialVersionUID = -8920980761735439684L;

	private final ApplicationConfigurationProperties applicationConfiguration;

	private final ServerManagementGui serverManagementGui;
	private final ServerManagementConfiguration serverManagementConfiguration;
	private final Server server;
	private final TrustManager trustManager;
	private final boolean deactivateHostnameVerification;

	private Button readAllUsersButton;
	private JTable managedUserTable;
	private JTable urlsTable;
	private JTable versionsTable;
	private JTable loginsTable;
	private JTable logsTable;
	private String status = "OK";

	private Button removeUserButton;
	private Button removeUrlButton;
	private Button removeVersionButton;
	private Button removeLoginButton;
	private Button removeLogButton;

	/** Result texts of a login check */
	private static final String LOGIN_RESULT_OK = "OK";
	private static final String LOGIN_RESULT_FAILED = "FAILED";
	private static final String LOGIN_RESULT_ERROR = "ERROR";
	private static final String LOGIN_RESULT_NOT_SUPPORTED = "n/a";

	/** Column of the check result in the logins table */
	private static final int LOGINS_RESULT_COLUMN = 3;

	private RefreshServerItemStatusWorker worker;

	/** Maximum number of table rows visible without scrolling */
	private static final int MAX_VISIBLE_TABLE_ROWS = 5;

	/** Content of the server card, refilled by createComponents() */
	private JPanel contentPanel;

	public ServerManagementItem(final ServerManagementGui serverManagementGui, final Server server, final ApplicationConfigurationProperties applicationConfiguration) throws Exception {
		super(new BorderLayout());
		setOpaque(false);

		this.applicationConfiguration = applicationConfiguration;
		this.serverManagementGui = serverManagementGui;
		serverManagementConfiguration = serverManagementGui.getServerManagementConfiguration();
		this.server = server;

		if (StringUtilities.isNotBlank(serverManagementConfiguration.getKeystore())) {
			trustManager = TrustManagerUtilities.createTrustManagerForKeyStore(new File(serverManagementConfiguration.getKeystore()));
			deactivateHostnameVerification = false;
		} else {
			trustManager = TrustManagerUtilities.createTrustAllTrustManager();
			deactivateHostnameVerification = true;
		}

		createComponents();
	}

	private void createComponents() {
		removeAll();

		// Tables of a former build must not be updated by a running status check anymore
		managedUserTable = null;
		urlsTable = null;
		versionsTable = null;
		loginsTable = null;
		logsTable = null;

		contentPanel = new JPanel();
		contentPanel.setOpaque(false);
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.PAGE_AXIS));

		// Each section: title with its buttons, below it the table (only if there are entries)
		addManagedUsersButtons();
		if (Utilities.isNotEmpty(server.getManagedUsers())) {
			addManagedUsersPanel(server.getManagedUsers());
		}

		addUrlsButtons();
		if (Utilities.isNotEmpty(server.getUrls())) {
			addUrlsPanel(server.getUrls());
		}

		addVersionsButtons();
		if (Utilities.isNotEmpty(server.getVersions())) {
			addVersionsPanel(server.getVersions());
		}

		addLoginsButtons();
		if (Utilities.isNotEmpty(server.getLogins())) {
			addLoginsPanel(server.getLogins());
		}

		addLogsButtons();
		if (Utilities.isNotEmpty(server.getLogs())) {
			addLogsPanel(server.getLogs());
		}

		final Card card = new Card(server.getName(), IconKind.SERVER, 10);
		if (StringUtilities.isNotBlank(server.getHost())) {
			final JLabel hostLabel = ModernUi.createMutedLabel(server.getHost());
			hostLabel.setToolTipText(LangResources.get("host"));
			card.addHeaderComponent(hostLabel);
		}
		card.setContent(contentPanel);
		add(card, BorderLayout.CENTER);

		validate();
		repaint();

		checkButtonStatus();
	}

	/**
	 * Adds the header of a section: title on the left, buttons on the right, separated from the previous section by a
	 * thin line.
	 */
	private void addSectionHeader(final String title, final JPanel buttonPanel) {
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
	 * Transparent panel for the buttons of a section header.
	 */
	private static JPanel createSectionButtonPanel() {
		final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttonPanel.setOpaque(false);
		return buttonPanel;
	}

	private static Button createSectionButton(final String textResourceKey, final IconKind iconKind) {
		return new Button(LangResources.get(textResourceKey), iconKind, ButtonStyle.SECONDARY);
	}

	/**
	 * Gives a table of the server card the ModernUi look; the background colors of the status check stay visible.
	 */
	private static void styleItemTable(final JTable table) {
		ModernUi.styleTable(table);
		table.setDefaultRenderer(Object.class, new PaddedCellRenderer());
		table.setShowVerticalLines(false);
		// Background of not yet checked tables (the status check sets the status colors)
		table.setBackground(Color.WHITE);
	}

	/**
	 * Limits the visible height of a table, more rows can be scrolled.
	 */
	private static void limitVisibleRows(final JTable table) {
		final Dimension preferredSize = table.getPreferredSize();
		table.setPreferredScrollableViewportSize(new Dimension(preferredSize.width, Math.min(preferredSize.height, table.getRowHeight() * MAX_VISIBLE_TABLE_ROWS)));
	}

	/**
	 * Wraps the scroll pane of a table into a transparent panel for the card.
	 */
	private void addTablePanel(final JScrollPane scrollPane) {
		ModernUi.styleScrollPane(scrollPane, Color.WHITE);
		final JPanel tablePanel = new JPanel(new BorderLayout());
		tablePanel.setOpaque(false);
		tablePanel.add(scrollPane);
		contentPanel.add(tablePanel);
	}

	/**
	 * Cell renderer with padding; uses the background of the table, which shows the status color.
	 */
	private static class PaddedCellRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 4019347585127466351L;

		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean isSelected, final boolean hasFocus, final int row, final int column) {
			final Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
			return component;
		}
	}

	public String getServerName() {
		return server.getName();
	}

	public Server getServer() {
		return server;
	}

	public void resetTablesStatus() {
		if (managedUserTable != null) {
			for (int i = 0; i < managedUserTable.getModel().getRowCount(); i++) {
				if (StringUtilities.isNotBlank((String) managedUserTable.getModel().getValueAt(i, 0))) {
					managedUserTable.getModel().setValueAt("n/a", i, 2);
					managedUserTable.getModel().setValueAt("n/a", i, 3);
				}
			}
			managedUserTable.setBackground(Color.WHITE);
		}

		if (urlsTable != null) {
			for (int i = 0; i < urlsTable.getModel().getRowCount(); i++) {
				if (StringUtilities.isNotBlank((String) urlsTable.getModel().getValueAt(i, 0))) {
					urlsTable.getModel().setValueAt("", i, 1);
				}
			}
			urlsTable.setBackground(Color.WHITE);
		}

		if (versionsTable != null) {
			for (int i = 0; i < versionsTable.getModel().getRowCount(); i++) {
				if (StringUtilities.isNotBlank((String) versionsTable.getModel().getValueAt(i, 0))) {
					versionsTable.getModel().setValueAt("", i, 3);
				}
			}
			versionsTable.setBackground(Color.WHITE);
		}

		resetLoginsTableStatus();

		status = "OK";
	}

	private void resetLoginsTableStatus() {
		if (loginsTable != null) {
			for (int i = 0; i < loginsTable.getModel().getRowCount(); i++) {
				loginsTable.getModel().setValueAt("", i, LOGINS_RESULT_COLUMN);
			}
			loginsTable.setBackground(Color.WHITE);
		}
	}

	/**
	 * Shows the result of a login check. Logins are addressed by index, because the same URL may be configured with
	 * different usernames.
	 */
	public void setLoginResult(final int loginIndex, final String resultText) {
		if (loginsTable == null || loginIndex < 0 || loginIndex >= loginsTable.getModel().getRowCount()) {
			return;
		}

		loginsTable.clearSelection();
		loginsTable.getModel().setValueAt(resultText, loginIndex, LOGINS_RESULT_COLUMN);
		if (LOGIN_RESULT_OK.equals(resultText)) {
			raiseTableColor(loginsTable, CustomizedColor.GREEN);
		} else if (LOGIN_RESULT_NOT_SUPPORTED.equals(resultText)) {
			raiseTableColor(loginsTable, CustomizedColor.YELLOW);
			raiseStatus("WARNING");
		} else {
			raiseTableColor(loginsTable, CustomizedColor.RED);
			raiseStatus("ERROR");
		}
	}

	public void setManagedUserCurrentAuthKeys(final String username, final int expectedAuthKeys, final int currentAuthKeys, final int surplusAuthKeys) {
		if (managedUserTable == null) {
			createComponents();
		}

		managedUserTable.clearSelection();
		for (int i = 0; i < managedUserTable.getModel().getRowCount(); i++) {
			if (managedUserTable.getModel().getValueAt(i, 0).equals(username)) {
				managedUserTable.getModel().setValueAt(Integer.toString(currentAuthKeys), i, 2);
				managedUserTable.getModel().setValueAt(Integer.toString(surplusAuthKeys), i, 3);

				// Only raise the color and status, so a later OK user does not hide an error of an earlier user
				if (currentAuthKeys != expectedAuthKeys) {
					raiseTableColor(managedUserTable, CustomizedColor.RED);
					raiseStatus("ERROR");
				} else if (surplusAuthKeys > 0) {
					raiseTableColor(managedUserTable, CustomizedColor.YELLOW);
					raiseStatus("WARNING");
				} else {
					raiseTableColor(managedUserTable, CustomizedColor.GREEN);
				}

				break;
			}
		}
	}

	public void setUrlTime(final String url, final int timeInSeconds) {
		urlsTable.clearSelection();
		for (int i = 0; i < urlsTable.getModel().getRowCount(); i++) {
			if (urlsTable.getModel().getValueAt(i, 0).equals(url)) {
				// Only raise the color and status (before, a slow URL turned an ERROR status into WARNING)
				if (timeInSeconds < 0) {
					urlsTable.getModel().setValueAt("ERROR", i, 1);
					raiseTableColor(urlsTable, CustomizedColor.RED);
					raiseStatus("ERROR");
				} else if (timeInSeconds > 5) {
					urlsTable.getModel().setValueAt(Integer.toString(timeInSeconds) + " sec", i, 1);
					raiseTableColor(urlsTable, CustomizedColor.YELLOW);
					raiseStatus("WARNING");
				} else {
					urlsTable.getModel().setValueAt(Integer.toString(timeInSeconds) + " sec", i, 1);
					raiseTableColor(urlsTable, CustomizedColor.GREEN);
				}

				break;
			}
		}
	}

	public void setVersion(final String name, final String versionText) {
		versionsTable.clearSelection();
		for (int i = 0; i < versionsTable.getModel().getRowCount(); i++) {
			if (versionsTable.getModel().getValueAt(i, 0).equals(name)) {
				versionsTable.getModel().setValueAt(versionText, i, 3);
				// Before, the first version always turned the table green, even for "ERROR"
				if ("ERROR".equals(versionText)) {
					raiseTableColor(versionsTable, CustomizedColor.RED);
					raiseStatus("ERROR");
				} else {
					raiseTableColor(versionsTable, CustomizedColor.GREEN);
				}
				break;
			}
		}
	}

	private void addManagedUsersPanel(final List<User> managedUsers) {
		final DefaultTableModel model = new DefaultTableModel();
		model.addColumn(LangResources.get("username"));
		model.addColumn(LangResources.get("expectedAuthKeys"));
		model.addColumn(LangResources.get("currentAuthKeys"));
		model.addColumn(LangResources.get("surplusAuthKeys"));

		loadUsersData(model, managedUsers);

		managedUserTable = new JTable(model) {
			private static final long serialVersionUID = 4698367052129394845L;
			@Override
			public void changeSelection(final int rowIndex, final int columnIndex, final boolean toggle, final boolean extend) {
				// Always toggle on single selection
				super.changeSelection(rowIndex, columnIndex, !extend, extend);
			}
		};

		managedUserTable.setDefaultEditor(Object.class, null);
		managedUserTable.getColumnModel().getColumn(0).setPreferredWidth(100);

		managedUserTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		final ListSelectionModel selectionModel = managedUserTable.getSelectionModel();
		selectionModel.addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(final ListSelectionEvent e) {
				checkButtonStatus();
			}
		});

		managedUserTable.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent mouseEvent) {
				if (mouseEvent.getClickCount() == 2) {
					// Open Edit user on doubleclick
					try {
						final JTable table = (JTable) mouseEvent.getSource();
						final int row = table.rowAtPoint(mouseEvent.getPoint());
						if (row >= 0) {
							editUser(table.convertRowIndexToModel(row));
						}
					} catch (final Exception e) {
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
					}
				}
			}
		});

		managedUserTable.getColumnModel().getColumn(1).setPreferredWidth(100);
		final DefaultTableCellRenderer rightRenderer1 = new PaddedCellRenderer();
		rightRenderer1.setHorizontalAlignment(SwingConstants.RIGHT);
		managedUserTable.getColumnModel().getColumn(1).setCellRenderer(rightRenderer1);

		managedUserTable.getColumnModel().getColumn(2).setPreferredWidth(100);
		final DefaultTableCellRenderer rightRenderer2 = new PaddedCellRenderer();
		rightRenderer2.setHorizontalAlignment(SwingConstants.RIGHT);
		managedUserTable.getColumnModel().getColumn(2).setCellRenderer(rightRenderer2);

		managedUserTable.getColumnModel().getColumn(3).setPreferredWidth(100);
		final DefaultTableCellRenderer rightRenderer3 = new PaddedCellRenderer();
		rightRenderer3.setHorizontalAlignment(SwingConstants.RIGHT);
		managedUserTable.getColumnModel().getColumn(3).setCellRenderer(rightRenderer3);

		styleItemTable(managedUserTable);


		limitVisibleRows(managedUserTable);

		final ServerManagementItem item = this;

		final JScrollPane scrollPane = new JScrollPane(managedUserTable);
		scrollPane.addMouseWheelListener(
				new MouseWheelListener() {
					@Override
					public void mouseWheelMoved(final MouseWheelEvent e) {
						final boolean scrollDown = e.getWheelRotation() > 0;
						final BoundedRangeModel verticalScrollBarModel = scrollPane.getVerticalScrollBar().getModel();
						if (!scrollDown && verticalScrollBarModel.getValue() <= verticalScrollBarModel.getMinimum()) {
							scrollPane.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(scrollPane, e, item));
						} else if (scrollDown && verticalScrollBarModel.getValue() + verticalScrollBarModel.getExtent() >= verticalScrollBarModel.getMaximum()) {
							scrollPane.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(scrollPane, e, item));
						}
					}
				}
				);

		addTablePanel(scrollPane);
	}

	private void addUrlsPanel(final List<String> urls) {
		final DefaultTableModel model = new DefaultTableModel();
		model.addColumn(LangResources.get("url"));
		model.addColumn(LangResources.get("time"));

		loadUrlsData(model, urls);

		urlsTable = new JTable(model) {
			private static final long serialVersionUID = -5447166999136247378L;
			@Override
			public void changeSelection(final int rowIndex, final int columnIndex, final boolean toggle, final boolean extend) {
				// Always toggle on single selection
				super.changeSelection(rowIndex, columnIndex, !extend, extend);
			}
		};

		urlsTable.setDefaultEditor(Object.class, null);
		urlsTable.getColumnModel().getColumn(0).setPreferredWidth(400);

		final ListSelectionModel selectionModel = urlsTable.getSelectionModel();
		selectionModel.addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(final ListSelectionEvent e) {
				checkButtonStatus();
			}
		});

		urlsTable.getColumnModel().getColumn(1).setPreferredWidth(100);
		final DefaultTableCellRenderer rightRenderer1 = new PaddedCellRenderer();
		rightRenderer1.setHorizontalAlignment(SwingConstants.RIGHT);
		urlsTable.getColumnModel().getColumn(1).setCellRenderer(rightRenderer1);

		styleItemTable(urlsTable);

		limitVisibleRows(urlsTable);

		final ServerManagementItem item = this;

		final JScrollPane urlsScrollPane = new JScrollPane(urlsTable);
		urlsScrollPane.addMouseWheelListener(
				new MouseWheelListener() {
					@Override
					public void mouseWheelMoved(final MouseWheelEvent e) {
						final boolean scrollDown = e.getWheelRotation() > 0;
						final BoundedRangeModel verticalScrollBarModel = urlsScrollPane.getVerticalScrollBar().getModel();
						if (!scrollDown && verticalScrollBarModel.getValue() <= verticalScrollBarModel.getMinimum()) {
							urlsScrollPane.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(urlsScrollPane, e, item));
						} else if (scrollDown && verticalScrollBarModel.getValue() + verticalScrollBarModel.getExtent() >= verticalScrollBarModel.getMaximum()) {
							urlsScrollPane.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(urlsScrollPane, e, item));
						}
					}
				}
				);

		addTablePanel(urlsScrollPane);
	}

	private void addVersionsPanel(final List<Version> versions) {
		final DefaultTableModel model = new DefaultTableModel();
		model.addColumn(LangResources.get("versionName"));
		model.addColumn(LangResources.get("location"));
		model.addColumn(LangResources.get("path"));
		model.addColumn(LangResources.get("version"));

		loadVersionsData(model, versions);

		versionsTable = new JTable(model) {
			private static final long serialVersionUID = -5447166999136247378L;
			@Override
			public void changeSelection(final int rowIndex, final int columnIndex, final boolean toggle, final boolean extend) {
				// Always toggle on single selection
				super.changeSelection(rowIndex, columnIndex, !extend, extend);
			}
		};

		versionsTable.setDefaultEditor(Object.class, null);
		versionsTable.getColumnModel().getColumn(0).setPreferredWidth(50);

		versionsTable.getColumnModel().getColumn(1).setPreferredWidth(200);
		final DefaultTableCellRenderer rightRenderer1 = new PaddedCellRenderer();
		rightRenderer1.setHorizontalAlignment(SwingConstants.LEFT);
		versionsTable.getColumnModel().getColumn(1).setCellRenderer(rightRenderer1);

		versionsTable.getColumnModel().getColumn(2).setPreferredWidth(200);
		final DefaultTableCellRenderer rightRenderer2 = new PaddedCellRenderer();
		rightRenderer2.setHorizontalAlignment(SwingConstants.LEFT);
		versionsTable.getColumnModel().getColumn(2).setCellRenderer(rightRenderer2);

		versionsTable.getColumnModel().getColumn(3).setPreferredWidth(50);
		final DefaultTableCellRenderer rightRenderer3 = new PaddedCellRenderer();
		rightRenderer3.setHorizontalAlignment(SwingConstants.LEFT);
		versionsTable.getColumnModel().getColumn(3).setCellRenderer(rightRenderer3);

		final ListSelectionModel selectionModel = versionsTable.getSelectionModel();
		selectionModel.addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(final ListSelectionEvent e) {
				checkButtonStatus();
			}
		});

		versionsTable.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent mouseEvent) {
				if (mouseEvent.getClickCount() == 2) {
					// Open Edit version on doubleclick
					try {
						final JTable table = (JTable) mouseEvent.getSource();
						final int row = table.rowAtPoint(mouseEvent.getPoint());
						if (row < 0) {
							return;
						}
						final int rowIndex = table.convertRowIndexToModel(row);
						final String versionName = (String) table.getModel().getValueAt(rowIndex, 0);
						if (versionName == null || versionName.isBlank()) {
							return;
						}

						final List<String> dialogResult = new MultiInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("editVersion"), LangResources.get("editVersion"),
								LangResources.get("versionName"),
								LangResources.get("location"),
								LangResources.get("path"))
								.withDefaultTexts(new String[] {
										versionName,
										(String) table.getModel().getValueAt(rowIndex, 1),
										(String) table.getModel().getValueAt(rowIndex, 2)})
								.open();
						if (dialogResult != null) {
							final Version version = server.getVersions().get(rowIndex);
							version.setName(dialogResult.get(0));
							version.setLocation(dialogResult.get(1));
							version.setPath(dialogResult.get(2));
							createComponents();
							checkButtonStatus();
							serverManagementGui.signalUnsavedChanges();
						}
					} catch (final Exception e) {
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
					}
				}
			}
		});

		styleItemTable(versionsTable);

		limitVisibleRows(versionsTable);

		final ServerManagementItem item = this;

		final JScrollPane versionsScrollPane = new JScrollPane(versionsTable);
		versionsScrollPane.addMouseWheelListener(
				new MouseWheelListener() {
					@Override
					public void mouseWheelMoved(final MouseWheelEvent e) {
						final boolean scrollDown = e.getWheelRotation() > 0;
						final BoundedRangeModel verticalScrollBarModel = versionsScrollPane.getVerticalScrollBar().getModel();
						if (!scrollDown && verticalScrollBarModel.getValue() <= verticalScrollBarModel.getMinimum()) {
							versionsScrollPane.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(versionsScrollPane, e, item));
						} else if (scrollDown && verticalScrollBarModel.getValue() + verticalScrollBarModel.getExtent() >= verticalScrollBarModel.getMaximum()) {
							versionsScrollPane.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(versionsScrollPane, e, item));
						}
					}
				}
				);

		addTablePanel(versionsScrollPane);
	}

	private void addLoginsPanel(final List<Login> logins) {
		final DefaultTableModel model = new DefaultTableModel();
		model.addColumn(LangResources.get("url"));
		model.addColumn(LangResources.get("username"));
		model.addColumn(LangResources.get("loginType"));
		model.addColumn(LangResources.get("loginResult"));

		loadLoginsData(model, logins);

		loginsTable = new JTable(model) {
			private static final long serialVersionUID = 2804713458829412663L;
			@Override
			public void changeSelection(final int rowIndex, final int columnIndex, final boolean toggle, final boolean extend) {
				// Always toggle on single selection
				super.changeSelection(rowIndex, columnIndex, !extend, extend);
			}
		};

		loginsTable.setDefaultEditor(Object.class, null);
		loginsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		loginsTable.getColumnModel().getColumn(0).setPreferredWidth(400);
		loginsTable.getColumnModel().getColumn(1).setPreferredWidth(150);

		loginsTable.getColumnModel().getColumn(2).setPreferredWidth(80);

		loginsTable.getColumnModel().getColumn(LOGINS_RESULT_COLUMN).setPreferredWidth(100);
		final DefaultTableCellRenderer rightRenderer = new PaddedCellRenderer();
		rightRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
		loginsTable.getColumnModel().getColumn(LOGINS_RESULT_COLUMN).setCellRenderer(rightRenderer);

		final ListSelectionModel selectionModel = loginsTable.getSelectionModel();
		selectionModel.addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(final ListSelectionEvent e) {
				checkButtonStatus();
			}
		});

		loginsTable.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent mouseEvent) {
				if (mouseEvent.getClickCount() == 2) {
					// Open Edit login on doubleclick
					try {
						final JTable table = (JTable) mouseEvent.getSource();
						final int row = table.rowAtPoint(mouseEvent.getPoint());
						if (row >= 0) {
							editLogin(table.convertRowIndexToModel(row));
						}
					} catch (final Exception e) {
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
					}
				}
			}
		});

		styleItemTable(loginsTable);

		limitVisibleRows(loginsTable);

		final ServerManagementItem item = this;

		final JScrollPane loginsScrollPane = new JScrollPane(loginsTable);
		loginsScrollPane.addMouseWheelListener(
				new MouseWheelListener() {
					@Override
					public void mouseWheelMoved(final MouseWheelEvent e) {
						final boolean scrollDown = e.getWheelRotation() > 0;
						final BoundedRangeModel verticalScrollBarModel = loginsScrollPane.getVerticalScrollBar().getModel();
						if (!scrollDown && verticalScrollBarModel.getValue() <= verticalScrollBarModel.getMinimum()) {
							loginsScrollPane.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(loginsScrollPane, e, item));
						} else if (scrollDown && verticalScrollBarModel.getValue() + verticalScrollBarModel.getExtent() >= verticalScrollBarModel.getMaximum()) {
							loginsScrollPane.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(loginsScrollPane, e, item));
						}
					}
				}
				);

		addTablePanel(loginsScrollPane);
	}

	private void editLogin(final int loginIndex) throws Exception {
		final Login login = server.getLogins().get(loginIndex);
		final LoginDialog dialog = new LoginDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("editLogin")).withLogin(login);
		if (dialog.open()) {
			dialog.applyTo(login);
			createComponents();
			checkButtonStatus();
			serverManagementGui.signalUnsavedChanges();
		}
	}

	private void addLogsPanel(final List<Log> logs) {
		final DefaultTableModel model = new DefaultTableModel();
		model.addColumn(LangResources.get("logName"));
		model.addColumn(LangResources.get("location"));
		model.addColumn(LangResources.get("path"));

		loadLogsData(model, logs);

		logsTable = new JTable(model) {
			@Override
			public void changeSelection(final int rowIndex, final int columnIndex, final boolean toggle, final boolean extend) {
				// Always toggle on single selection
				super.changeSelection(rowIndex, columnIndex, !extend, extend);
			}
		};

		logsTable.setDefaultEditor(Object.class, null);
		logsTable.getColumnModel().getColumn(0).setPreferredWidth(50);

		logsTable.getColumnModel().getColumn(1).setPreferredWidth(200);
		final DefaultTableCellRenderer rightRenderer1 = new PaddedCellRenderer();
		rightRenderer1.setHorizontalAlignment(SwingConstants.LEFT);
		logsTable.getColumnModel().getColumn(1).setCellRenderer(rightRenderer1);

		logsTable.getColumnModel().getColumn(2).setPreferredWidth(200);
		final DefaultTableCellRenderer rightRenderer2 = new PaddedCellRenderer();
		rightRenderer2.setHorizontalAlignment(SwingConstants.LEFT);
		logsTable.getColumnModel().getColumn(2).setCellRenderer(rightRenderer2);

		final ListSelectionModel selectionModel = logsTable.getSelectionModel();
		selectionModel.addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(final ListSelectionEvent e) {
				checkButtonStatus();
			}
		});

		logsTable.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent mouseEvent) {
				if (mouseEvent.getClickCount() == 2) {
					// Show log content on doubleclick
					try {
						final JTable table = (JTable) mouseEvent.getSource();
						final int row = table.rowAtPoint(mouseEvent.getPoint());
						if (row < 0) {
							return;
						}
						final int rowIndex = table.convertRowIndexToModel(row);

						final String logName = (String) table.getModel().getValueAt(rowIndex, 0);
						final String location = (String) table.getModel().getValueAt(rowIndex, 1);
						final String path = (String) table.getModel().getValueAt(rowIndex, 2);

						if (location != null && location.toLowerCase().startsWith("ssh://")) {
							String host = location.substring(6);
							int port = 22;
							if (host.contains(":")) {
								port = Integer.parseInt(host.substring(host.indexOf(":") + 1));
								host = host.substring(0, host.indexOf(":"));
							}
							final Admin sshAdmin = getSshAdminChecked();
							final SshLogViewerDialog dialog = new SshLogViewerDialog(serverManagementGui, server.getName() + " " + logName, host, port, sshAdmin.getUsername(), sshAdmin.getPrivateKeyFile(), sshAdmin.getPasswordArray(), path);
							dialog.open();
						} else {
							// Before, nothing happened for other locations
							throw new Exception("Unsupported log location '" + location + "', expected 'ssh://host[:port]'");
						}
					} catch (final Exception e) {
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
					}
				}
			}
		});

		styleItemTable(logsTable);

		limitVisibleRows(logsTable);

		final ServerManagementItem item = this;

		final JScrollPane versionsScrollPane = new JScrollPane(logsTable);
		versionsScrollPane.addMouseWheelListener(
			new MouseWheelListener() {
				@Override
				public void mouseWheelMoved(final MouseWheelEvent e) {
					final boolean scrollDown = e.getWheelRotation() > 0;
					final BoundedRangeModel verticalScrollBarModel = versionsScrollPane.getVerticalScrollBar().getModel();
					if (!scrollDown && verticalScrollBarModel.getValue() <= verticalScrollBarModel.getMinimum()) {
						versionsScrollPane.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(versionsScrollPane, e, item));
					} else if (scrollDown && verticalScrollBarModel.getValue() + verticalScrollBarModel.getExtent() >= verticalScrollBarModel.getMaximum()) {
						versionsScrollPane.getParent().dispatchEvent(SwingUtilities.convertMouseEvent(versionsScrollPane, e, item));
					}
				}
			}
		);

		addTablePanel(versionsScrollPane);
	}

	private void addUrlsButtons() {
		final JPanel buttonPanel = createSectionButtonPanel();

		final Button addUrlButton = createSectionButton("addUrl", IconKind.PLUS);
		addUrlButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final String url = new SimpleInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("addUrl"), LangResources.get("addUrl")).open();
					if (StringUtilities.isNotBlank(url)) {
						server.getUrls().add(url);

						createComponents();

						checkButtonStatus();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		buttonPanel.add(addUrlButton);

		removeUrlButton = createSectionButton("removeUrl", IconKind.TRASH);
		removeUrlButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					if (urlsTable.getSelectedColumnCount() > 0
							&& new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("removeUrl"), LangResources.get("removeUrl"), LangResources.get("yes"), LangResources.get("cancel")).open() == 0) {

						final int[] selectedRows = urlsTable.getSelectedRows();
						for (int i = selectedRows.length - 1; i >= 0; i--) {
							final String urlToRemove = (String) ((DefaultTableModel) urlsTable.getModel()).getValueAt(selectedRows[i], 0);
							server.getUrls().remove(urlToRemove);
						}

						createComponents();

						checkButtonStatus();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		buttonPanel.add(removeUrlButton);

		addSectionHeader(LangResources.get("section.urls"), buttonPanel);
	}

	private void addVersionsButtons() {
		final JPanel buttonPanel = createSectionButtonPanel();

		final Button addVersionButton = createSectionButton("addVersion", IconKind.PLUS);
		addVersionButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final List<String> dialogResult = new MultiInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("addVersion"), LangResources.get("addVersion"),
							LangResources.get("versionName"),
							LangResources.get("location"),
							LangResources.get("path")).open();
					if (dialogResult != null) {
						final Version newVersion = new Version().setName(dialogResult.get(0)).setLocation(dialogResult.get(1)).setPath(dialogResult.get(2));
						server.getVersions().add(newVersion);

						createComponents();

						checkButtonStatus();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		buttonPanel.add(addVersionButton);

		removeVersionButton = createSectionButton("removeVersion", IconKind.TRASH);
		removeVersionButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final int selectedRow = versionsTable.getSelectedRow();
					if (selectedRow < 0) {
						return;
					}
					final int rowIndex = versionsTable.convertRowIndexToModel(selectedRow);
					final String versionName = (String) versionsTable.getModel().getValueAt(rowIndex, 0);
					if (StringUtilities.isNotBlank(versionName) && new QuestionDialog(serverManagementGui,
							ServerManagement.APPLICATION_NAME + " " + LangResources.get("removeVersion"),
							LangResources.get("removeVersionQuestion", versionName), LangResources.get("ok"),
							LangResources.get("cancel")).open() == 0) {
						server.getVersions().remove(rowIndex);
						((DefaultTableModel) versionsTable.getModel()).removeRow(rowIndex);

						checkButtonStatus();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR",
							"ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		buttonPanel.add(removeVersionButton);

		final Button readVersionsButton = createSectionButton("readVersions", IconKind.REFRESH);
		readVersionsButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					refreshVersions();
					String result = "";
					for (int i = 0; i < versionsTable.getModel().getRowCount(); i++) {
						result += versionsTable.getModel().getValueAt(i, 0) + ": " + versionsTable.getModel().getValueAt(i, 3) + "\n";
					}
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("readVersions"), result.trim()).withDialogStyle(DialogStyle.INFORMATION).open();
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		buttonPanel.add(readVersionsButton);

		addSectionHeader(LangResources.get("section.versions"), buttonPanel);
	}

	private void addLoginsButtons() {
		final JPanel buttonPanel = createSectionButtonPanel();

		final Button addLoginButton = createSectionButton("addLogin", IconKind.PLUS);
		addLoginButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final LoginDialog dialog = new LoginDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("addLogin"));
					if (dialog.open()) {
						server.getLogins().add(dialog.applyTo(new Login()));

						createComponents();

						checkButtonStatus();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		buttonPanel.add(addLoginButton);

		removeLoginButton = createSectionButton("removeLogin", IconKind.TRASH);
		removeLoginButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final int selectedRow = loginsTable.getSelectedRow();
					if (selectedRow < 0) {
						return;
					}
					final int rowIndex = loginsTable.convertRowIndexToModel(selectedRow);
					final Login login = server.getLogins().get(rowIndex);
					final String loginDisplayName = login.getUsername() + " @ " + login.getUrl();
					if (new QuestionDialog(serverManagementGui,
							ServerManagement.APPLICATION_NAME + " " + LangResources.get("removeLogin"),
							LangResources.get("removeLoginQuestion", loginDisplayName), LangResources.get("ok"),
							LangResources.get("cancel")).open() == 0) {
						server.getLogins().remove(rowIndex);
						((DefaultTableModel) loginsTable.getModel()).removeRow(rowIndex);

						checkButtonStatus();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		buttonPanel.add(removeLoginButton);

		final Button checkLoginsButton = createSectionButton("checkLogins", IconKind.REFRESH);
		checkLoginsButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					resetLoginsTableStatus();
					refreshLogins();
					String result = "";
					if (loginsTable != null) {
						for (int i = 0; i < loginsTable.getModel().getRowCount(); i++) {
							result += loginsTable.getModel().getValueAt(i, 1) + " @ " + loginsTable.getModel().getValueAt(i, 0) + " (" + loginsTable.getModel().getValueAt(i, 2) + "): " + loginsTable.getModel().getValueAt(i, LOGINS_RESULT_COLUMN) + "\n";
						}
					}
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("checkLogins"), result.trim()).withDialogStyle(DialogStyle.INFORMATION).open();
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		buttonPanel.add(checkLoginsButton);

		addSectionHeader(LangResources.get("section.logins"), buttonPanel);
	}

	private void addLogsButtons() {
		final JPanel buttonPanel = createSectionButtonPanel();

		final Button addLogButton = createSectionButton("addLog", IconKind.PLUS);
		addLogButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final List<String> dialogResult = new MultiInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("addLog"), LangResources.get("addLog"),
							LangResources.get("logName"),
							LangResources.get("location"),
							LangResources.get("path")).open();
					if (dialogResult != null) {
						final Log newLog = new Log().setName(dialogResult.get(0)).setLocation(dialogResult.get(1)).setPath(dialogResult.get(2));
						server.getLogs().add(newLog);

						createComponents();

						checkButtonStatus();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		buttonPanel.add(addLogButton);

		removeLogButton = createSectionButton("removeLog", IconKind.TRASH);
		removeLogButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final String versionName = (String) logsTable.getModel().getValueAt(logsTable.getSelectedRow(), 0);
					if (StringUtilities.isNotBlank(versionName)
							&& new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("removeLog"), LangResources.get("removeLogQuestion", versionName), LangResources.get("ok"), LangResources.get("cancel")).open() == 0) {
						for (int i = 0; i < server.getLogs().size(); i++) {
							if (server.getLogs().get(i).getName().equals(versionName)) {
								server.getLogs().remove(i);
								break;
							}
						}

						((DefaultTableModel) logsTable.getModel()).removeRow(logsTable.getSelectedRow());

						checkButtonStatus();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		buttonPanel.add(removeLogButton);

		addSectionHeader(LangResources.get("section.logs"), buttonPanel);
	}

	private static void loadUrlsData(final DefaultTableModel model, final List<String> urls) {
		for (int i = model.getRowCount() - 1; i >= 0; i--) {
			model.removeRow(i);
		}
		for (final String url : urls) {
			model.addRow(new String[] { url, "" });
		}
	}

	private static void loadUsersData(final DefaultTableModel model, final List<User> managedUsers) {
		for (int i = model.getRowCount() - 1; i >= 0; i--) {
			model.removeRow(i);
		}
		for (final User managedUser : managedUsers) {
			model.addRow(new String[] { managedUser.getUsername(), Integer.toString(managedUser.getAuthorized_key_ids().size()), "n/a", "n/a" });
		}
	}

	private static void loadVersionsData(final DefaultTableModel model, final List<Version> versions) {
		for (int i = model.getRowCount() - 1; i >= 0; i--) {
			model.removeRow(i);
		}
		for (final Version version : versions) {
			model.addRow(new String[] { version.getName(), version.getLocation(), version.getPath() });
		}
	}

	private static void loadLoginsData(final DefaultTableModel model, final List<Login> logins) {
		for (int i = model.getRowCount() - 1; i >= 0; i--) {
			model.removeRow(i);
		}
		for (final Login login : logins) {
			// The password is never shown in the table
			model.addRow(new String[] { login.getUrl(), login.getUsername(), getLoginTypeDisplayName(login.getLoginType()), "" });
		}
	}

	private static String getLoginTypeDisplayName(final LoginType loginType) {
		return loginType == LoginType.OIDC ? LangResources.get("loginType.oidc") : LangResources.get("loginType.basicauth");
	}

	private static void loadLogsData(final DefaultTableModel model, final List<Log> logs) {
		for (int i = model.getRowCount() - 1; i >= 0; i--) {
			model.removeRow(i);
		}
		for (final Log log : logs) {
			model.addRow(new String[] { log.getName(), log.getLocation(), log.getPath() });
		}
	}

	private void addManagedUsersButtons() {
		final JPanel buttonPanel = createSectionButtonPanel();

		final Button editAdminButton = createSectionButton("editAdmin", IconKind.EDIT);
		editAdminButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final AdminDialog dialog = new AdminDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " Admin " + server.getName(), LangResources.get("enterServerAdmin", server.getName()));
					if (server.getSshAdmin() != null) {
						dialog.setUsername(server.getSshAdmin().getUsername());
						dialog.setPrivateKeyFilePath(server.getSshAdmin().getPrivateKeyPath());
						final char[] adminPassword = server.getSshAdmin().getPasswordArray();
						if (adminPassword != null) {
							dialog.setPassword(adminPassword);
						}
					}
					if (dialog.open() == 0) {
						if (server.getSshAdmin() == null) {
							server.setSshAdmin(new Admin());
						}
						server.getSshAdmin().setUsername(dialog.getUsername());
						server.getSshAdmin().setPrivateKeyPath(dialog.getPrivateKeyFilePath());
						// Encrypted with the master key; null or empty removes the password
						server.getSshAdmin().setPasswordArray(dialog.getPassword());
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		buttonPanel.add(editAdminButton);

		readAllUsersButton = createSectionButton("readAllUsers", IconKind.SEARCH);
		readAllUsersButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final List<String> usernames = AuthorizedKeysHelper.readUsernames(server.getHost(), server.getSshAdmin().getUsername(), server.getSshAdmin().getPrivateKeyFile(), server.getSshAdmin().getPasswordArray());
					boolean changesHappened = false;
					for (final String username : usernames) {
						boolean found = false;
						for (final User user : server.getManagedUsers()) {
							if (username.equals(user.getUsername())) {
								found = true;
								break;
							}
						}
						if (!found) {
							final User newUser = new User();
							newUser.setUsername(username);
							server.getManagedUsers().add(newUser);
							changesHappened = true;
						}
					}

					if (changesHappened) {
						createComponents();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		buttonPanel.add(readAllUsersButton);

		removeUserButton = createSectionButton("removeUser", IconKind.TRASH);
		removeUserButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final String username = (String) managedUserTable.getModel().getValueAt(managedUserTable.getSelectedRow(), 0);
					if (StringUtilities.isNotBlank(username)
							&& new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("removeUser"), LangResources.get("removeUserQuestion", username), LangResources.get("ok"), LangResources.get("cancel")).open() == 0) {
						for (int i = 0; i < server.getManagedUsers().size(); i++) {
							if (server.getManagedUsers().get(i).getUsername().equals(username)) {
								server.getManagedUsers().remove(i);
								break;
							}
						}

						((DefaultTableModel) managedUserTable.getModel()).removeRow(managedUserTable.getSelectedRow());

						checkButtonStatus();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		});
		removeUserButton.setEnabled(false);
		buttonPanel.add(removeUserButton);

		addSectionHeader(LangResources.get("managedUsers"), buttonPanel);
	}

	private static KnownPublicSshKey getKeyFromKnownKeysByKeyId(final String keyId, final List<KnownPublicSshKey> knownPublicSshKeys) {
		for (final KnownPublicSshKey knownPublicSshKey : knownPublicSshKeys) {
			if (keyId != null && keyId.equals(knownPublicSshKey.getKeyId())) {
				return knownPublicSshKey;
			}
		}
		return null;
	}

	private void editUser(final int clickedRow) throws Exception {
		final User user = server.getManagedUsers().get(clickedRow);
		final String username = user.getUsername();
		// Before, a missing admin caused a NullPointerException with the message "null"
		getSshAdminChecked();
		final String adminKeyHash = getAdminKeyHash(server.getSshAdmin().getPrivateKeyFile(), server.getSshAdmin().getPasswordArray());

		List<AuthorizedKey> existingAuthorizedKeys;
		try {
			existingAuthorizedKeys = AuthorizedKeysHelper.readAuthorizedKeys(server.getHost(), server.getSshAdmin().getUsername(), server.getSshAdmin().getPrivateKeyFile(), server.getSshAdmin().getPasswordArray(), user.getUsername());
		} catch (final Exception e) {
			new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
			return;
		}
		final Map<String, String> existingKeys = new HashMap<>();
		for (final AuthorizedKey key : existingAuthorizedKeys) {
			existingKeys.put(key.getHash(), key.getComment());
		}

		final Map<String, String> configuredKeys = new HashMap<>();
		for (final String keyId : user.getAuthorized_key_ids()) {
			final KnownPublicSshKey key = getKeyFromKnownKeysByKeyId(keyId, serverManagementGui.getServerManagementConfiguration().getKnownPublicSshKeys());
			// A configured key id without known key (e.g. after editing the configuration file) caused a NullPointerException
			if (key != null) {
				configuredKeys.put(key.getHash(), keyId);
			}
		}

		final Map<String, String> knownKeys = new HashMap<>();
		for (final KnownPublicSshKey key : serverManagementGui.getServerManagementConfiguration().getKnownPublicSshKeys()) {
			knownKeys.put(key.getHash(), key.getKeyId());
		}

		final Map<String, String> keyNamesByKeyId = new HashMap<>();
		for (final KnownPublicSshKey knownPublicSshKey : serverManagementConfiguration.getKnownPublicSshKeys()) {
			keyNamesByKeyId.put(knownPublicSshKey.getKeyId(), knownPublicSshKey.getName());
		}

		final UserPublicKeysDialog userPublicKeysDialog = new UserPublicKeysDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("editUserPublicKeys", username, server.getHost()), adminKeyHash, existingKeys, configuredKeys, knownKeys, keyNamesByKeyId);
		if (userPublicKeysDialog.open()) {
			try {
				// Known public keys
				for (final String keyHash : userPublicKeysDialog.getKnownKeys().keySet()) {
					boolean found = false;
					for (final KnownPublicSshKey key : serverManagementGui.getServerManagementConfiguration().getKnownPublicSshKeys()) {
						if (keyHash.equals(key.getHash())) {
							found = true;
							break;
						}
					}
					if (!found) {
						for (final AuthorizedKey authorizedKeys : existingAuthorizedKeys) {
							if (keyHash.equals(authorizedKeys.getHash())) {
								final KnownPublicSshKey newKnownPublicKey = new KnownPublicSshKey();
								newKnownPublicKey.setKey(authorizedKeys.getKeyString());
								newKnownPublicKey.setKeyId(authorizedKeys.getComment());
								newKnownPublicKey.setKeyType(authorizedKeys.getKeyType());
								serverManagementGui.getServerManagementConfiguration().getKnownPublicSshKeys().add(newKnownPublicKey);
								break;
							}
						}
					}
				}
				serverManagementGui.getServerManagementConfiguration().setKnownPublicSshKeys(
						serverManagementGui.getServerManagementConfiguration().getKnownPublicSshKeys().stream()
						.filter(x -> {
							try {
								return userPublicKeysDialog.getKnownKeys().keySet().contains(x.getHash());
							} catch (final Exception e) {
								e.printStackTrace();
								return false;
							}
						})
						.collect(Collectors.toList())
						);

				// Configured keys
				final Map<String, String> hashByKeyId = new HashMap<>();
				final Map<String, String> keyIdByHash = new HashMap<>();
				for (final KnownPublicSshKey key : serverManagementGui.getServerManagementConfiguration().getKnownPublicSshKeys()) {
					hashByKeyId.put(key.getKeyId(), key.getHash());
					keyIdByHash.put(key.getHash(), key.getKeyId());
				}
				for (final String keyHash : userPublicKeysDialog.getConfiguredKeys().keySet()) {
					final String keyId = keyIdByHash.get(keyHash);
					// Only known keys can be configured, otherwise a null key id was stored
					if (keyId != null && !user.getAuthorized_key_ids().contains(keyId)) {
						user.getAuthorized_key_ids().add(keyId);
					}
				}
				user.setAuthorized_key_ids(
						user.getAuthorized_key_ids().stream()
						.filter(x -> {
							try {
								return userPublicKeysDialog.getConfiguredKeys().keySet().contains(hashByKeyId.get(x));
							} catch (final Exception e) {
								e.printStackTrace();
								return false;
							}
						})
						.collect(Collectors.toList())
						);

				// Existing user authorized keys on host
				final ArrayList<AuthorizedKey> newAuthorizedKeys = new ArrayList<>();
				for (final String hash : userPublicKeysDialog.getExistingKeys().keySet()) {
					final AuthorizedKey foundInExistingAuthorizedKeys = existingAuthorizedKeys.stream().filter(x -> {
						try {
							return x.getHash().equals(hash);
						} catch (final Exception e) {
							e.printStackTrace();
							return false;
						}
					}).findFirst().orElse(null);
					if (foundInExistingAuthorizedKeys != null) {
						newAuthorizedKeys.add(foundInExistingAuthorizedKeys);
					} else {
						final KnownPublicSshKey foundInKnownKeys = serverManagementGui.getServerManagementConfiguration().getKnownPublicSshKeys().stream().filter(x -> {
							try {
								return x.getHash().equals(hash);
							} catch (final Exception e) {
								e.printStackTrace();
								return false;
							}
						}).findFirst().orElse(null);
						if (foundInKnownKeys != null) {
							final AuthorizedKey newKey = new AuthorizedKey(foundInKnownKeys.getKeyType(), foundInKnownKeys.getKey());
							newKey.setComment(foundInKnownKeys.getKeyId());
							newAuthorizedKeys.add(newKey);
						} else {
							new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "Cannot find authorized key for storage. Hash: " + hash).withDialogStyle(DialogStyle.ERROR).open();
						}
					}
				}

				// The configuration was already changed above, so it must be marked as unsaved even if the upload fails
				serverManagementGui.signalUnsavedChanges();

				if (!compareAuthorizedKeysLists(existingAuthorizedKeys, newAuthorizedKeys)) {
					try {
						AuthorizedKeysHelper.storeAuthorizedKeys(server.getHost(), server.getSshAdmin().getUsername(), server.getSshAdmin().getPrivateKeyFile(), server.getSshAdmin().getPasswordArray(), user.getUsername(), newAuthorizedKeys);
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("succesfullyUpdatedSshUserKeys")).withDialogStyle(DialogStyle.INFORMATION).withBackgroundColor(ModernUi.SUCCESS_LIGHT).open();
					} catch (final Exception e) {
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
					}
				} else {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("noServerChangeNeeded")).withDialogStyle(DialogStyle.INFORMATION).open();
				}
				loadUsersData((DefaultTableModel) managedUserTable.getModel(), server.getManagedUsers());
			} catch (final Exception e) {
				new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
			}
		}
	}

	private static String getAdminKeyHash(final File sshAccessPrivateKeyFile, final char[] sshAccessPassword) throws Exception {
		if (sshAccessPrivateKeyFile != null) {
			try (InputStream inputStream = new FileInputStream(sshAccessPrivateKeyFile)) {
				SshKey sshKey;
				if (StringUtilities.isNotBlank(sshAccessPassword)) {
					sshKey = SshKeyReader.readKey(inputStream, sshAccessPassword);
				} else {
					sshKey = SshKeyReader.readKey(inputStream, null);
				}
				return sshKey.getMd5Fingerprint().replace(":", "");
			}
		} else {
			return null;
		}
	}

	private static boolean compareAuthorizedKeysLists(final List<AuthorizedKey> existingAuthorizedKeys, final ArrayList<AuthorizedKey> newAuthorizedKeys) throws Exception {
		if (existingAuthorizedKeys.size() != newAuthorizedKeys.size()) {
			return false;
		} else {
			for (final AuthorizedKey key : existingAuthorizedKeys) {
				boolean found = false;
				for (final AuthorizedKey checkKey : newAuthorizedKeys) {
					if (checkKey.getHash().equals(key.getHash())) {
						found = true;
						break;
					}
				}
				if (!found) {
					return false;
				}
			}

			for (final AuthorizedKey key : newAuthorizedKeys) {
				boolean found = false;
				for (final AuthorizedKey checkKey : existingAuthorizedKeys) {
					if (checkKey.getHash().equals(key.getHash())) {
						found = true;
						break;
					}
				}
				if (!found) {
					return false;
				}
			}

			return true;
		}
	}

	private void checkButtonStatus() {
		readAllUsersButton.setEnabled(server.getSshAdmin() != null);

		if (managedUserTable != null) {
			if (managedUserTable.getSelectedRowCount() > 0) {
				final String username = (String) managedUserTable.getModel().getValueAt(managedUserTable.getSelectedRow(), 0);
				removeUserButton.setEnabled(StringUtilities.isNotBlank(username));
			} else {
				removeUserButton.setEnabled(false);
			}
		} else {
			removeUserButton.setEnabled(false);
		}

		if (urlsTable != null) {
			if (urlsTable.getSelectedRowCount() > 0) {
				removeUrlButton.setEnabled(true);
			} else {
				removeUrlButton.setEnabled(false);
			}
		} else {
			removeUrlButton.setEnabled(false);
		}

		if (versionsTable != null) {
			if (versionsTable.getSelectedRowCount() > 0) {
				removeVersionButton.setEnabled(true);
			} else {
				removeVersionButton.setEnabled(false);
			}
		} else {
			removeVersionButton.setEnabled(false);
		}

		if (loginsTable != null) {
			removeLoginButton.setEnabled(loginsTable.getSelectedRowCount() > 0);
		} else {
			removeLoginButton.setEnabled(false);
		}

		if (logsTable != null) {
			if (logsTable.getSelectedRowCount() > 0) {
				removeLogButton.setEnabled(true);
			} else {
				removeLogButton.setEnabled(false);
			}
		} else {
			removeLogButton.setEnabled(false);
		}
	}

	public void refreshStatusAsync() {
		synchronized (this) {
			// A finished worker, which was not yet collected by refreshStatusIsDone() (e.g. after a canceled check), must
			// not block a new refresh, otherwise the old status was reported again
			if (worker == null || worker.isDone()) {
				worker = new RefreshServerItemStatusWorker(null, this);
				new Thread(worker).start();
			}
		}
	}

	public synchronized boolean refreshStatusIsDone() {
		if (worker != null) {
			if (!worker.isDone()) {
				return false;
			} else {
				worker = null;
				return true;
			}
		} else {
			return true;
		}
	}

	public void resetStatus() {
		if (managedUserTable != null) {
			for (int i = 0; i < managedUserTable.getModel().getRowCount(); i++) {
				if (StringUtilities.isNotBlank((String) managedUserTable.getModel().getValueAt(i, 0))) {
					managedUserTable.getModel().setValueAt("n/a", i, 2);
					managedUserTable.getModel().setValueAt("n/a", i, 3);
				}
			}
			managedUserTable.setBackground(Color.WHITE);
		}

		if (urlsTable != null) {
			for (int i = 0; i < urlsTable.getModel().getRowCount(); i++) {
				if (StringUtilities.isNotBlank((String) urlsTable.getModel().getValueAt(i, 0))) {
					urlsTable.getModel().setValueAt("", i, 1);
				}
			}
			urlsTable.setBackground(Color.WHITE);
		}

		if (versionsTable != null) {
			for (int i = 0; i < versionsTable.getModel().getRowCount(); i++) {
				if (StringUtilities.isNotBlank((String) versionsTable.getModel().getValueAt(i, 0))) {
					versionsTable.getModel().setValueAt("", i, 3);
				}
			}
			versionsTable.setBackground(Color.WHITE);
		}

		resetLoginsTableStatus();

		status = "OK";
	}

	public void refreshStatus() {
		try {
			resetStatus();

			// Refresh ssh users status
			final String hostname = server.getHost();
			final Admin sshAdmin = server.getSshAdmin();

			final Map<String, String> knownPublicSshKeysMap = new HashMap<>();
			final Map<String, String> knownPublicSshKeysNamesMap = new HashMap<>();
			final List<KnownPublicSshKey> knownPublicSshKeys = serverManagementConfiguration.getKnownPublicSshKeys();
			if (knownPublicSshKeys != null) {
				for (final KnownPublicSshKey knownPublicSshKey : knownPublicSshKeys) {
					knownPublicSshKeysMap.put(knownPublicSshKey.getKeyId(), knownPublicSshKey.getKey());
					knownPublicSshKeysNamesMap.put(knownPublicSshKey.getKeyId(), knownPublicSshKey.getName());
				}
			}

			final List<User> managedUsers = server.getManagedUsers();
			for (final User managedUser : managedUsers) {
				final String username = managedUser.getUsername();
				try {
					final List<AuthorizedKey> authorizedKeys = AuthorizedKeysHelper.readAuthorizedKeys(hostname, sshAdmin.getUsername(), sshAdmin.getPrivateKeyFile(), sshAdmin.getPasswordArray(), username);

					final List<String> configuredAuthorizedKeyIds = managedUser.getAuthorized_key_ids();

					final List<String> expectedKeyNames = new ArrayList<>();
					final List<String> expectedKeyIds = new ArrayList<>();
					final List<String> missingKeyNames = new ArrayList<>();
					for (final Object authorizedKeyIdObject : configuredAuthorizedKeyIds) {
						final String authorizedKeyId = (String) authorizedKeyIdObject;
						final String authorizedKey = knownPublicSshKeysMap.get(authorizedKeyId);
						expectedKeyNames.add(knownPublicSshKeysNamesMap.get(authorizedKeyId));
						expectedKeyIds.add(authorizedKeyId);
						boolean found = false;
						for (final AuthorizedKey authorizedKeyItem : authorizedKeys) {
							if (authorizedKeyItem.getKeyString().equals(authorizedKey)) {
								found = true;
								break;
							}
						}
						if (!found) {
							missingKeyNames.add(knownPublicSshKeysNamesMap.get(authorizedKeyId));
						}
					}

					final List<String> surplusKeys = new ArrayList<>();
					for (final AuthorizedKey authorizedKeyItem : authorizedKeys) {
						boolean isExpected = false;
						for (final Object authorizedKeyIdObject : configuredAuthorizedKeyIds) {
							final String expectedAuthorizedKeyId = (String) authorizedKeyIdObject;
							final String expectedAuthorizedKey = knownPublicSshKeysMap.get(expectedAuthorizedKeyId);
							if (expectedAuthorizedKey != null && expectedAuthorizedKey.equals(authorizedKeyItem.getKeyString())) {
								isExpected = true;
								break;
							}
						}

						if (!isExpected) {
							boolean found = false;
							for (final Entry<String, String> entry : knownPublicSshKeysMap.entrySet()) {
								if (entry.getValue().equals(authorizedKeyItem.getKeyString())) {
									surplusKeys.add(knownPublicSshKeysNamesMap.get(entry.getKey()));
									found = true;
									break;
								}
							}
							if (!found) {
								surplusKeys.add("< " + authorizedKeyItem.toString() + " >");
							}
						}
					}

					setManagedUserCurrentAuthKeys(username, expectedKeyNames.size(), expectedKeyNames.size() - missingKeyNames.size(), surplusKeys.size());
				} catch (@SuppressWarnings("unused") final Exception e) {
					setManagedUserCurrentAuthKeys(username, -1, 0, 0);
					raiseStatus("ERROR");
				}
			}

			// Refresh urls status
			for (final String url : server.getUrls()) {
				try {
					final HttpRequest request = new HttpRequest(HttpMethod.GET, url);
					final LocalDateTime start = LocalDateTime.now();
					final HttpResponse response = HttpUtilities.executeHttpRequest(request, applicationConfiguration.getProxyConfiguration().getProxy(request.getUrl()), trustManager, deactivateHostnameVerification);
					final LocalDateTime end = LocalDateTime.now();
					if (response.getHttpCode() == 200) {
						setUrlTime(url, (int) ChronoUnit.SECONDS.between(start, end));
					} else if (response.getHttpCode() == 302) {
						setUrlTime(url, (int) ChronoUnit.SECONDS.between(start, end));
					} else if (response.getHttpCode() == 400) {
						setUrlTime(url, (int) ChronoUnit.SECONDS.between(start, end));
					} else {
						setUrlTime(url, -1);
					}
				} catch (@SuppressWarnings("unused") final Exception e) {
					setUrlTime(url, -1);
				}

			}

			// Refresh versions status
			refreshVersions();

			// Refresh logins status
			refreshLogins();
		} catch (final Exception e) {
			e.printStackTrace();
			status = "ERROR";
		}
	}

	private void refreshVersions() throws Exception {
		SftpConnection sftpConnection = null;
		String cachedSftpConnectionString = "";

		try {
			for (final Version version : server.getVersions()) {
				// Each version separately: before, a failing SSH connection, an invalid regex or a missing property aborted
				// the check of all further versions of this server
				try {
					String data = null;
					if (version.getLocation().toLowerCase().startsWith("http://")
							|| version.getLocation().toLowerCase().startsWith("https://")) {
						try {
							final HttpRequest request = new HttpRequest(HttpMethod.GET, version.getLocation());
							final HttpResponse response = HttpUtilities.executeHttpRequest(request, applicationConfiguration.getProxyConfiguration().getProxy(request.getUrl()), trustManager, deactivateHostnameVerification);
							if (response.getHttpCode() == 200) {
								data = response.getContent();
							} else {
								setVersion(version.getName(), "ERROR");
								status = "ERROR";
							}
						} catch (@SuppressWarnings("unused") final Exception e) {
							setVersion(version.getName(), "ERROR");
							status = "ERROR";
						}
					} else if (version.getLocation().toLowerCase().startsWith("ssh://")) {
						String hostnameForSsh = server.getHost();
						String usernameForSsh = server.getSshAdmin().getUsername();
						File privateKeyFileForSsh = server.getSshAdmin().getPrivateKeyFile();
						char[] passwordForSsh = server.getSshAdmin().getPasswordArray();

						String filePath = version.getLocation().substring(6);

						if (filePath.contains(":")) {
							hostnameForSsh = filePath.substring(0, filePath.lastIndexOf(":"));
							filePath = filePath.substring(filePath.lastIndexOf(":") + 1);
						}

						if (hostnameForSsh.contains("@")) {
							usernameForSsh = hostnameForSsh.substring(0, hostnameForSsh.lastIndexOf("@"));
							hostnameForSsh = hostnameForSsh.substring(hostnameForSsh.lastIndexOf("@") + 1);
						}

						if (usernameForSsh.contains(":")) {
							privateKeyFileForSsh = null;
							passwordForSsh = usernameForSsh.substring(usernameForSsh.lastIndexOf(":") + 1).toCharArray();
							usernameForSsh = usernameForSsh.substring(0, usernameForSsh.lastIndexOf(":"));
						}

						final String sftpConnectionString = usernameForSsh + "@" + hostnameForSsh;

						if (sftpConnection == null || !cachedSftpConnectionString.equals(sftpConnectionString)) {
							if (sftpConnection != null) {
								sftpConnection.close();
								// Must not be used again, if the new connection fails
								sftpConnection = null;
								cachedSftpConnectionString = "";
							}
							sftpConnection = openSftpConnection(hostnameForSsh, usernameForSsh, privateKeyFileForSsh, passwordForSsh);
							cachedSftpConnectionString = sftpConnectionString;
						}

						if (filePath.endsWith("/")) {
							try {
								data = StringUtilities.join(sftpConnection.listFiles(filePath), "\n");
							} catch (@SuppressWarnings("unused") final Exception e) {
								setVersion(version.getName(), "ERROR");
								status = "ERROR";
							}
						} else {
							try (InputStream inputStream = sftpConnection.downloadFile(filePath)) {
								data = new String(IoUtilities.toByteArray(inputStream), StandardCharsets.UTF_8);
							} catch (@SuppressWarnings("unused") final Exception e) {
								setVersion(version.getName(), "ERROR");
								status = "ERROR";
							}
						}
					} else if (version.getLocation().toLowerCase().startsWith("ssh-cmd:")) {
						String hostnameForSsh = server.getHost();
						String usernameForSsh = server.getSshAdmin().getUsername();
						File privateKeyFileForSsh = server.getSshAdmin().getPrivateKeyFile();
						char[] passwordForSsh = server.getSshAdmin().getPasswordArray();

						String command = version.getLocation().substring(8);

						if (command.contains(":")) {
							hostnameForSsh = command.substring(0, command.lastIndexOf(":"));
							command = command.substring(command.lastIndexOf(":") + 1);
						}

						if (hostnameForSsh.contains("@")) {
							usernameForSsh = hostnameForSsh.substring(0, hostnameForSsh.lastIndexOf("@"));
							hostnameForSsh = hostnameForSsh.substring(hostnameForSsh.lastIndexOf("@") + 1);
						}

						if (usernameForSsh.contains(":")) {
							privateKeyFileForSsh = null;
							passwordForSsh = usernameForSsh.substring(usernameForSsh.lastIndexOf(":") + 1).toCharArray();
							usernameForSsh = usernameForSsh.substring(0, usernameForSsh.lastIndexOf(":"));
						}

						final String sftpConnectionString = usernameForSsh + "@" + hostnameForSsh;

						if (sftpConnection == null || !cachedSftpConnectionString.equals(sftpConnectionString)) {
							if (sftpConnection != null) {
								sftpConnection.close();
								// Must not be used again, if the new connection fails
								sftpConnection = null;
								cachedSftpConnectionString = "";
							}
							sftpConnection = openSftpConnection(hostnameForSsh, usernameForSsh, privateKeyFileForSsh, passwordForSsh);
							cachedSftpConnectionString = sftpConnectionString;
						}

						try {
							data = sftpConnection.executeCommand(command);
						} catch (@SuppressWarnings("unused") final Exception e) {
							setVersion(version.getName(), "ERROR");
							status = "ERROR";
						}
					} else {
						setVersion(version.getName(), "ERROR");
						status = "ERROR";
					}

					if (data != null) {
						if (version.getPath().toLowerCase().startsWith("json:")) {
							try (final JsonReader jsonReader = new JsonReader(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)))) {
								jsonReader.readUpToJsonPath(version.getPath().substring(5));
								jsonReader.readNextJsonNode();
								final String versionText = ((JsonValueString) jsonReader.getCurrentObject()).getValue();
								setVersion(version.getName(), versionText);
							} catch (@SuppressWarnings("unused") final Exception e) {
								setVersion(version.getName(), "ERROR");
								status = "ERROR";
							}
						} else if (version.getPath().toLowerCase().startsWith("xml:")) {
							try {
								final Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(data)));
								final String versionText = (String) XPathFactory.newInstance().newXPath().evaluate(version.getPath().substring(4), document, XPathConstants.STRING);
								setVersion(version.getName(), versionText);
							} catch (@SuppressWarnings("unused") final Exception e) {
								setVersion(version.getName(), "ERROR");
								status = "ERROR";
							}
						} else if (version.getPath().toLowerCase().startsWith("regex:")) {
							final Pattern regex = Pattern.compile(version.getPath().substring(6));
							final Matcher matcher = regex.matcher(data);
							if (matcher.find()) {
								setVersion(version.getName(), matcher.group());
							} else {
								setVersion(version.getName(), "ERROR");
								status = "ERROR";
							}
						} else if (version.getPath().toLowerCase().startsWith("regex(")) {
							//Example: "regex(1): version: (.*)"
							final Pattern regex = Pattern.compile(version.getPath().substring(version.getPath().indexOf(":") + 1));
							final int group = Integer.parseInt(version.getPath().substring(6, version.getPath().indexOf(")")));
							final Matcher matcher = regex.matcher(data);
							if (matcher.find()) {
								setVersion(version.getName(), matcher.group(group));
							} else {
								setVersion(version.getName(), "ERROR");
								status = "ERROR";
							}
						} else if (version.getPath().toLowerCase().startsWith("properties:")) {
							final String propertyName = version.getPath().substring(11);
							try (PropertiesReader reader = new PropertiesReader(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)))) {
								final Map<String, String> propertiesMap = reader.read();
								String propertyValue = propertiesMap.get(propertyName);
								if (propertyValue == null) {
									throw new Exception("Property '" + propertyName + "' not found");
								}
								if (propertyValue.startsWith("\"") && propertyValue.endsWith("\"")) {
									propertyValue = propertyValue.substring(1, propertyValue.length() - 1);
									propertyValue = Utilities.unescapeJavaString(propertyValue);
								}
								setVersion(version.getName(), propertyValue);
							}
						} else {
							setVersion(version.getName(), "ERROR");
							status = "ERROR";
						}
					} else {
						setVersion(version.getName(), "ERROR");
						status = "ERROR";
					}
				} catch (@SuppressWarnings("unused") final Exception e) {
					setVersion(version.getName(), "ERROR");
					status = "ERROR";
				}
			}
		} finally {
			if (sftpConnection != null) {
				sftpConnection.close();
			}
		}
	}

	private void refreshLogins() {
		final List<Login> logins = server.getLogins();
		if (logins == null) {
			return;
		}
		for (int i = 0; i < logins.size(); i++) {
			// Each login separately, so a failing login does not abort the check of further logins
			setLoginResult(i, checkLogin(logins.get(i)));
		}
	}

	/**
	 * Checks a login. CLASSIC logins are checked via HTTP Basic authentication, OIDC logins are not checked yet.
	 *
	 * @return n/a for OIDC, OK for HTTP 2xx, FAILED for HTTP 401/403 (credentials rejected), ERROR for anything else (e.g. server
	 *         not reachable, unexpected HTTP code)
	 */
	private String checkLogin(final Login login) {
		if (login.getLoginType() == LoginType.BasicAuth) {
			try {
				final HttpRequest request = new HttpRequest(HttpMethod.GET, login.getUrl());
				final char[] password = login.getPasswordArray();
				final String credentials = (login.getUsername() == null ? "" : login.getUsername()) + ":" + (password == null ? "" : new String(password));
				request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
				final HttpResponse response = HttpUtilities.executeHttpRequest(request, applicationConfiguration.getProxyConfiguration().getProxy(request.getUrl()), trustManager, deactivateHostnameVerification);
				final int httpCode = response.getHttpCode();
				if (httpCode >= 200 && httpCode < 300) {
					return LOGIN_RESULT_OK;
				} else if (httpCode == 401 || httpCode == 403) {
					return LOGIN_RESULT_FAILED;
				} else {
					return LOGIN_RESULT_ERROR;
				}
			} catch (@SuppressWarnings("unused") final Exception e) {
				return LOGIN_RESULT_ERROR;
			}
		} else if (login.getLoginType() == LoginType.OIDC) {
			try {
				final String url = login.getUrl();
				final String user = login.getUsername();
				final char[] passwordArray = login.getPasswordArray();
				final String password = passwordArray == null ? null : new String(passwordArray);
				final String expectText = null;
				final String usernameField = null;
				final String passwordField = null;
				final String dumpFinalHtmlPath = null;
				final String tokenType = null;
				final String idpSelection = StringUtilities.isBlank(login.getIdpSelection()) ? null : login.getIdpSelection();
				final boolean insecure = false;
				final boolean verbose = false;
				final boolean quiet = false;
				final int timeoutSeconds = 30;

				final URI startUri;
				try {
					startUri = URI.create(url);
					if (startUri.getScheme() == null || startUri.getHost() == null) {
						throw new IllegalArgumentException();
					}
				} catch (@SuppressWarnings("unused") final IllegalArgumentException e) {
					throw new Exception("Invalid URL: " + url);
				}

				final OidcLoginCheck check = new OidcLoginCheck(insecure, verbose, quiet, Duration.ofSeconds(timeoutSeconds), usernameField, passwordField, dumpFinalHtmlPath, tokenType, idpSelection);
				return check.check(startUri, user, password, expectText) ? LOGIN_RESULT_OK : LOGIN_RESULT_FAILED;
			} catch (@SuppressWarnings("unused") final Exception e) {
				return LOGIN_RESULT_FAILED;
			}
		} else {
			return LOGIN_RESULT_ERROR;
		}
	}

	public String getStatus() {
		return status;
	}

	private static SftpConnection openSftpConnection(final String hostname, final String sshAccessUsername, final File sshAccessPrivateKeyFile, final char[] sshAccessPassword) throws Exception, IOException {
		// Same implementation as for the authorized keys (this copy had the same port parsing bug)
		return AuthorizedKeysHelper.openSftpConnection(hostname, sshAccessUsername, sshAccessPrivateKeyFile, sshAccessPassword);
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
	 * The background of a table shows the worst status of its rows, so a later good row must not hide an earlier error.
	 */
	private static void raiseTableColor(final JTable table, final Color color) {
		if (getColorSeverity(color) > getColorSeverity(table.getBackground())) {
			table.setBackground(color);
		}
	}

	/**
	 * The status of the server item is the worst status of all checks (OK &lt; WARNING &lt; ERROR).
	 */
	private void raiseStatus(final String newStatus) {
		if ("ERROR".equals(newStatus) || ("WARNING".equals(newStatus) && "OK".equals(status))) {
			status = newStatus;
		}
	}

	private Admin getSshAdminChecked() throws Exception {
		if (server.getSshAdmin() == null || StringUtilities.isBlank(server.getSshAdmin().getUsername())) {
			throw new Exception("No SSH admin configured for server '" + server.getName() + "'");
		}
		return server.getSshAdmin();
	}
}
