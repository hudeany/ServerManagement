package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import de.soderer.json.JsonReader;
import de.soderer.json.JsonValueString;
import de.soderer.network.HttpMethod;
import de.soderer.network.HttpRequest;
import de.soderer.network.HttpResponse;
import de.soderer.network.HttpUtilities;
import de.soderer.network.trustmanager.TrustManagerUtilities;
import de.soderer.pac.utilities.ProxyConfiguration;
import de.soderer.servermanagement.config.Admin;
import de.soderer.servermanagement.config.KnownPublicSshKey;
import de.soderer.servermanagement.config.Log;
import de.soderer.servermanagement.config.Server;
import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.servermanagement.config.User;
import de.soderer.servermanagement.config.Version;
import de.soderer.servermanagement.worker.RefreshServerItemStatusWorker;
import de.soderer.sshkeyformats.AuthorizedKey;
import de.soderer.sshkeyformats.SshKey;
import de.soderer.sshkeyformats.SshKeyReader;
import de.soderer.sshkeyformats.SshKeyWriter;
import de.soderer.utilities.IoUtilities;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.remote.SftpConnection;
import de.soderer.utilities.swing.MultiInputDialog;
import de.soderer.utilities.swing.QuestionDialog;
import de.soderer.utilities.swing.SimpleInputDialog;
import de.soderer.utilities.swing.SwingColor;

public class ServerManagementItem extends JPanel {
	private static final long serialVersionUID = -8920980761735439684L;

	private final ServerManagementGui serverManagementGui;
	private final ServerManagementConfiguration serverManagementConfiguration;
	private final Server server;
	private final ProxyConfiguration proxyConfiguration;
	private final TrustManager trustManager;
	private final boolean deactivateHostnameVerification;

	private JButton readAllUsersButton;
	private JTable managedUserTable;
	private JTable urlsTable;
	private JTable versionsTable;
	private JTable logsTable;
	private String status = "OK";

	private JButton removeUserButton;
	private JButton removeUrlButton;
	private JButton removeVersionButton;
	private JButton removeLogButton;

	private RefreshServerItemStatusWorker worker;

	public ServerManagementItem(final ServerManagementGui serverManagementGui, final Server server, final ProxyConfiguration proxyConfiguration) throws Exception {
		super();
		setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
		setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.BLACK),
			BorderFactory.createEmptyBorder(5, 5, 5, 5)));

		this.serverManagementGui = serverManagementGui;
		serverManagementConfiguration = serverManagementGui.getServerManagementConfiguration();
		this.server = server;
		this.proxyConfiguration = proxyConfiguration;

		if (Utilities.isNotBlank(serverManagementConfiguration.getKeystore())) {
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

		addServerNamePanel(server.getName());
		if (Utilities.isNotBlank(server.getHost())) {
			addHostPanel(server.getHost());
		}

		if (Utilities.isNotEmpty(server.getManagedUsers())) {
			addManagedUsersPanel(server.getManagedUsers());
		}
		addManagedUsersButtons();

		if (Utilities.isNotEmpty(server.getUrls())) {
			addUrlsPanel(server.getUrls());
		}
		addUrlsButtons();

		if (Utilities.isNotEmpty(server.getVersions())) {
			addVersionsPanel(server.getVersions());
		}
		addVersionsButtons();

		if (Utilities.isNotEmpty(server.getLogs())) {
			addLogsPanel(server.getLogs());
		}
		addLogsButtons();

		validate();
		updateUI();

		checkButtonStatus();
	}

	public String getServerName() {
		return server.getName();
	}

	public Server getServer() {
		return server;
	}

	private void addServerNamePanel(final String serverName) {
		final JPanel serverNamePanel = new JPanel();
		serverNamePanel.setLayout(new FlowLayout(FlowLayout.LEFT));

		final JLabel serverNameLabel = new JLabel(LangResources.get("serverName") + ": ");
		serverNamePanel.add(serverNameLabel);

		final JTextField serverNameField = new JTextField(serverName);
		serverNameField.setBackground(Color.LIGHT_GRAY);
		serverNameField.setEditable(false);
		serverNamePanel.add(serverNameField);

		add(serverNamePanel);
	}

	private void addHostPanel(final String host) {
		final JPanel hostPanel = new JPanel();
		hostPanel.setLayout(new FlowLayout(FlowLayout.LEFT));

		final JLabel hostLabel = new JLabel(LangResources.get("host") + ": ");
		hostPanel.add(hostLabel);

		final JTextField hostField = new JTextField(host);
		hostField.setEditable(false);
		hostPanel.add(hostField);

		add(hostPanel);
	}

	public void resetTablesStatus() {
		if (managedUserTable != null) {
			for (int i = 0; i < managedUserTable.getModel().getRowCount(); i++) {
				if (Utilities.isNotBlank((String) managedUserTable.getModel().getValueAt(i, 0))) {
					managedUserTable.getModel().setValueAt("n/a", i, 2);
					managedUserTable.getModel().setValueAt("n/a", i, 3);
				}
			}
			managedUserTable.setBackground(Color.WHITE);
		}

		if (urlsTable != null) {
			for (int i = 0; i < urlsTable.getModel().getRowCount(); i++) {
				if (Utilities.isNotBlank((String) urlsTable.getModel().getValueAt(i, 0))) {
					urlsTable.getModel().setValueAt("", i, 1);
				}
			}
			urlsTable.setBackground(Color.WHITE);
		}

		if (versionsTable != null) {
			for (int i = 0; i < versionsTable.getModel().getRowCount(); i++) {
				if (Utilities.isNotBlank((String) versionsTable.getModel().getValueAt(i, 0))) {
					versionsTable.getModel().setValueAt("", i, 3);
				}
			}
			versionsTable.setBackground(Color.WHITE);
		}

		status = "OK";
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

				if (currentAuthKeys != expectedAuthKeys) {
					managedUserTable.setBackground(CustomizedColor.RED);
					status = "ERROR";
				} else if (surplusAuthKeys > 0 && "OK".equals(status)) {
					managedUserTable.setBackground(CustomizedColor.YELLOW);
					status = "WARNING";
				} else {
					managedUserTable.setBackground(CustomizedColor.GREEN);
				}

				break;
			}
		}
	}

	public void setUrlTime(final String url, final int timeInSeconds) {
		urlsTable.clearSelection();
		for (int i = 0; i < urlsTable.getModel().getRowCount(); i++) {
			if (urlsTable.getModel().getValueAt(i, 0).equals(url)) {
				if (timeInSeconds < 0) {
					urlsTable.getModel().setValueAt("ERROR", i, 1);
					urlsTable.setBackground(CustomizedColor.RED);
					status = "ERROR";
				} else if (timeInSeconds > 5) {
					urlsTable.getModel().setValueAt(Integer.toString(timeInSeconds) + " sec", i, 1);
					urlsTable.setBackground(CustomizedColor.YELLOW);
					status = "WARNING";
				} else {
					urlsTable.getModel().setValueAt(Integer.toString(timeInSeconds) + " sec", i, 1);
					urlsTable.setBackground(CustomizedColor.GREEN);
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
				if (versionsTable.getBackground().equals(Color.WHITE)) {
					versionsTable.setBackground(CustomizedColor.GREEN);
				} else if ("ERROR".equals(versionText)) {
					versionsTable.setBackground(CustomizedColor.RED);
				}
				break;
			}
		}
	}

	private void addManagedUsersPanel(final List<User> managedUsers) {
		final JPanel labelPanel = new JPanel();
		labelPanel.setLayout(new FlowLayout(FlowLayout.LEFT));

		final JLabel hostLabel = new JLabel(LangResources.get("managedUsers"));
		labelPanel.add(hostLabel);
		add(labelPanel);

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
						editUser(table.convertRowIndexToModel(table.rowAtPoint(mouseEvent.getPoint())));
					} catch (final Exception e) {
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
					}
				}
			}
		});

		managedUserTable.getColumnModel().getColumn(1).setPreferredWidth(100);
		final DefaultTableCellRenderer rightRenderer1 = new DefaultTableCellRenderer();
		rightRenderer1.setHorizontalAlignment(SwingConstants.RIGHT);
		managedUserTable.getColumnModel().getColumn(1).setCellRenderer(rightRenderer1);

		managedUserTable.getColumnModel().getColumn(2).setPreferredWidth(100);
		final DefaultTableCellRenderer rightRenderer2 = new DefaultTableCellRenderer();
		rightRenderer2.setHorizontalAlignment(SwingConstants.RIGHT);
		managedUserTable.getColumnModel().getColumn(2).setCellRenderer(rightRenderer2);

		managedUserTable.getColumnModel().getColumn(3).setPreferredWidth(100);
		final DefaultTableCellRenderer rightRenderer3 = new DefaultTableCellRenderer();
		rightRenderer3.setHorizontalAlignment(SwingConstants.RIGHT);
		managedUserTable.getColumnModel().getColumn(3).setCellRenderer(rightRenderer3);

		managedUserTable.setBorder(BorderFactory.createLineBorder(Color.BLACK));
		managedUserTable.setRowHeight(20);


		if (managedUserTable.getPreferredSize().getHeight() < 65) {
			managedUserTable.setPreferredScrollableViewportSize(managedUserTable.getPreferredSize());
		} else {
			managedUserTable.setPreferredScrollableViewportSize(new Dimension((int) managedUserTable.getPreferredSize().getWidth(), 65));
		}

		final ServerManagementItem item = this;

		final JScrollPane scrollPane = new JScrollPane(managedUserTable);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
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

		final JPanel managedUsersTablePanel = new JPanel();
		managedUsersTablePanel.setLayout(new BorderLayout());

		managedUsersTablePanel.add(scrollPane);

		add(managedUsersTablePanel);

		add(Box.createRigidArea(new Dimension(5, 5)));
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
		final DefaultTableCellRenderer rightRenderer1 = new DefaultTableCellRenderer();
		rightRenderer1.setHorizontalAlignment(SwingConstants.RIGHT);
		urlsTable.getColumnModel().getColumn(1).setCellRenderer(rightRenderer1);

		urlsTable.setBorder(BorderFactory.createLineBorder(Color.BLACK));
		urlsTable.setRowHeight(20);

		if (urlsTable.getPreferredSize().getHeight() < 65) {
			urlsTable.setPreferredScrollableViewportSize(urlsTable.getPreferredSize());
		} else {
			urlsTable.setPreferredScrollableViewportSize(new Dimension((int) urlsTable.getPreferredSize().getWidth(), 65));
		}

		final ServerManagementItem item = this;

		final JScrollPane urlsScrollPane = new JScrollPane(urlsTable);
		urlsScrollPane.setBorder(BorderFactory.createEmptyBorder());
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

		final JPanel urlsTablePanel = new JPanel();
		urlsTablePanel.setLayout(new BorderLayout());
		urlsTablePanel.add(urlsScrollPane);

		add(urlsTablePanel);

		add(Box.createRigidArea(new Dimension(5, 5)));
	}

	private void addVersionsPanel(final List<Version> urls) {
		final DefaultTableModel model = new DefaultTableModel();
		model.addColumn(LangResources.get("versionName"));
		model.addColumn(LangResources.get("location"));
		model.addColumn(LangResources.get("path"));
		model.addColumn(LangResources.get("version"));

		loadVersionsData(model, urls);

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
		final DefaultTableCellRenderer rightRenderer1 = new DefaultTableCellRenderer();
		rightRenderer1.setHorizontalAlignment(SwingConstants.LEFT);
		versionsTable.getColumnModel().getColumn(1).setCellRenderer(rightRenderer1);

		versionsTable.getColumnModel().getColumn(2).setPreferredWidth(200);
		final DefaultTableCellRenderer rightRenderer2 = new DefaultTableCellRenderer();
		rightRenderer2.setHorizontalAlignment(SwingConstants.LEFT);
		versionsTable.getColumnModel().getColumn(2).setCellRenderer(rightRenderer2);

		versionsTable.getColumnModel().getColumn(3).setPreferredWidth(50);
		final DefaultTableCellRenderer rightRenderer3 = new DefaultTableCellRenderer();
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
						final int rowIndex = (table.convertRowIndexToModel(table.rowAtPoint(mouseEvent.getPoint())));

						final String versionName = (String) table.getModel().getValueAt(rowIndex, 0);

						final List<String> dialogResult = new MultiInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("editVersion"), LangResources.get("editVersion"),
								LangResources.get("versionName"),
								LangResources.get("location"),
								LangResources.get("path"))
								.setDefaultTexts(new String[] {
										versionName,
										(String) table.getModel().getValueAt(rowIndex, 1),
										(String) table.getModel().getValueAt(rowIndex, 2)})
								.open();
						if (dialogResult != null) {
							for (final Version version : server.getVersions()) {
								if (versionName.equals(version.getName())) {
									version.setName(dialogResult.get(0));
									version.setLocation(dialogResult.get(1));
									version.setPath(dialogResult.get(2));
									break;
								}
							}

							createComponents();

							checkButtonStatus();
							serverManagementGui.signalUnsavedChanges();
						}
					} catch (final Exception e) {
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
					}
				}
			}
		});

		versionsTable.setBorder(BorderFactory.createLineBorder(Color.BLACK));
		versionsTable.setRowHeight(20);

		if (versionsTable.getPreferredSize().getHeight() < 65) {
			versionsTable.setPreferredScrollableViewportSize(versionsTable.getPreferredSize());
		} else {
			versionsTable.setPreferredScrollableViewportSize(new Dimension((int) versionsTable.getPreferredSize().getWidth(), 65));
		}

		final ServerManagementItem item = this;

		final JScrollPane versionsScrollPane = new JScrollPane(versionsTable);
		versionsScrollPane.setBorder(BorderFactory.createEmptyBorder());
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

		final JPanel versionsTablePanel = new JPanel();
		versionsTablePanel.setLayout(new BorderLayout());
		versionsTablePanel.add(versionsScrollPane);

		add(versionsTablePanel);

		add(Box.createRigidArea(new Dimension(5, 5)));
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
		final DefaultTableCellRenderer rightRenderer1 = new DefaultTableCellRenderer();
		rightRenderer1.setHorizontalAlignment(SwingConstants.LEFT);
		logsTable.getColumnModel().getColumn(1).setCellRenderer(rightRenderer1);

		logsTable.getColumnModel().getColumn(2).setPreferredWidth(200);
		final DefaultTableCellRenderer rightRenderer2 = new DefaultTableCellRenderer();
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
						final int rowIndex = (table.convertRowIndexToModel(table.rowAtPoint(mouseEvent.getPoint())));

						final String logName = (String) table.getModel().getValueAt(rowIndex, 0);
						final String location = (String) table.getModel().getValueAt(rowIndex, 1);
						final String path = (String) table.getModel().getValueAt(rowIndex, 2);

						if (location.toLowerCase().startsWith("ssh://")) {
							String host = location.substring(6);
							int port = 22;
							if (host.contains(":")) {
								port = Integer.parseInt(host.substring(host.indexOf(":") + 1));
								host = host.substring(0, host.indexOf(":"));
							}
							final SshLogViewerDialog dialog = new SshLogViewerDialog(serverManagementGui, server.getName() + " " + logName, host, port, server.getSshAdmin().getUsername(), server.getSshAdmin().getPrivateKeyFile(), server.getSshAdmin().getPasswordArray(), path);
							dialog.open();
						}
					} catch (final Exception e) {
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
					}
				}
			}
		});

		logsTable.setBorder(BorderFactory.createLineBorder(Color.BLACK));
		logsTable.setRowHeight(20);

		if (logsTable.getPreferredSize().getHeight() < 65) {
			logsTable.setPreferredScrollableViewportSize(logsTable.getPreferredSize());
		} else {
			logsTable.setPreferredScrollableViewportSize(new Dimension((int) logsTable.getPreferredSize().getWidth(), 65));
		}

		final ServerManagementItem item = this;

		final JScrollPane versionsScrollPane = new JScrollPane(logsTable);
		versionsScrollPane.setBorder(BorderFactory.createEmptyBorder());
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

		final JPanel logsTablePanel = new JPanel();
		logsTablePanel.setLayout(new BorderLayout());
		logsTablePanel.add(versionsScrollPane);

		add(logsTablePanel);

		add(Box.createRigidArea(new Dimension(5, 5)));
	}

	private void addUrlsButtons() {
		final JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new FlowLayout(FlowLayout.LEFT));

		final JButton addUrlButton = new JButton(LangResources.get("addUrl"));
		addUrlButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final String url = new SimpleInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("addUrl"), LangResources.get("addUrl")).open();
					if (Utilities.isNotBlank(url)) {
						server.getUrls().add(url);

						createComponents();

						checkButtonStatus();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(addUrlButton);

		removeUrlButton = new JButton(LangResources.get("removeUrl"));
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
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(removeUrlButton);

		add(buttonPanel);
	}

	private void addVersionsButtons() {
		final JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new FlowLayout(FlowLayout.LEFT));

		final JButton addVersionButton = new JButton(LangResources.get("addVersion"));
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
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(addVersionButton);

		removeVersionButton = new JButton(LangResources.get("removeVersion"));
		removeVersionButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final String versionName = (String) logsTable.getModel().getValueAt(logsTable.getSelectedRow(), 0);
					if (Utilities.isNotBlank(versionName)
							&& new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("removeVersion"), LangResources.get("removeVersionQuestion", versionName), LangResources.get("ok"), LangResources.get("cancel")).open() == 0) {
						for (int i = 0; i < server.getVersions().size(); i++) {
							if (server.getVersions().get(i).getName().equals(versionName)) {
								server.getVersions().remove(i);
								break;
							}
						}

						((DefaultTableModel) logsTable.getModel()).removeRow(logsTable.getSelectedRow());

						checkButtonStatus();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(removeVersionButton);

		final JButton readVersionsButton = new JButton(LangResources.get("readVersions"));
		readVersionsButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					refreshVersions();
					String result = "";
					for (int i = 0; i < logsTable.getModel().getRowCount(); i++) {
						result += logsTable.getModel().getValueAt(i, 0) + ": " + logsTable.getModel().getValueAt(i, 3) + "\n";
					}
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " readVersions", result.trim()).open();
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(readVersionsButton);

		add(buttonPanel);
	}

	private void addLogsButtons() {
		final JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new FlowLayout(FlowLayout.LEFT));

		final JButton addLogButton = new JButton(LangResources.get("addLog"));
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
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(addLogButton);

		removeLogButton = new JButton(LangResources.get("removeLog"));
		removeLogButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final String versionName = (String) logsTable.getModel().getValueAt(logsTable.getSelectedRow(), 0);
					if (Utilities.isNotBlank(versionName)
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
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(removeLogButton);

		add(buttonPanel);
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

	private static void loadLogsData(final DefaultTableModel model, final List<Log> logs) {
		for (int i = model.getRowCount() - 1; i >= 0; i--) {
			model.removeRow(i);
		}
		for (final Log log : logs) {
			model.addRow(new String[] { log.getName(), log.getLocation(), log.getPath() });
		}
	}

	private void addManagedUsersButtons() {
		final JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new FlowLayout(FlowLayout.LEFT));

		final JButton editAdminButton = new JButton(LangResources.get("editAdmin"));
		editAdminButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final AdminDialog dialog = new AdminDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " Admin " + server.getName(), LangResources.get("enterServerAdmin", server.getName()));
					if (server.getSshAdmin() != null) {
						dialog.setUsername(server.getSshAdmin().getUsername());
						dialog.setPrivateKeyFilePath(server.getSshAdmin().getPrivateKeyPath());
						if (server.getSshAdmin().getPassword() != null) {
							dialog.setPassword(server.getSshAdmin().getPassword().toCharArray());
						}
					}
					if (dialog.open() == 0) {
						if (server.getSshAdmin() == null) {
							server.setSshAdmin(new Admin());
						}
						server.getSshAdmin().setUsername(dialog.getUsername());
						server.getSshAdmin().setPrivateKeyPath(dialog.getPrivateKeyFilePath());
						server.getSshAdmin().setPassword(new String(dialog.getPassword()));
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(editAdminButton);

		readAllUsersButton = new JButton(LangResources.get("readAllUsers"));
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
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(readAllUsersButton);

		removeUserButton = new JButton(LangResources.get("removeUser"));
		removeUserButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final String username = (String) managedUserTable.getModel().getValueAt(managedUserTable.getSelectedRow(), 0);
					if (Utilities.isNotBlank(username)
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
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		removeUserButton.setEnabled(false);
		buttonPanel.add(removeUserButton);

		add(buttonPanel);
	}

	private static KnownPublicSshKey getKeyFromKnownKeysByKeyId(final String keyId, final List<KnownPublicSshKey> knownPublicSshKeys) {
		for (final KnownPublicSshKey knownPublicSshKey : knownPublicSshKeys) {
			if (knownPublicSshKey.getKeyId().equals(keyId)) {
				return knownPublicSshKey;
			}
		}
		return null;
	}

	private void editUser(final int clickedRow) throws Exception {
		final User user = server.getManagedUsers().get(clickedRow);
		final String username = user.getUsername();

		final String adminKeyHash = getAdminKeyHash(server.getSshAdmin().getPrivateKeyFile(), server.getSshAdmin().getPasswordArray());

		List<AuthorizedKey> existingAuthorizedKeys;
		try {
			existingAuthorizedKeys = AuthorizedKeysHelper.readAuthorizedKeys(server.getHost(), server.getSshAdmin().getUsername(), server.getSshAdmin().getPrivateKeyFile(), server.getSshAdmin().getPasswordArray(), user.getUsername());
		} catch (final Exception e) {
			new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
			return;
		}
		final Map<String, String> existingKeys = new HashMap<>();
		for (final AuthorizedKey key : existingAuthorizedKeys) {
			existingKeys.put(key.getHash(), key.getComment());
		}

		final Map<String, String> configuredKeys = new HashMap<>();
		for (final String keyId : user.getAuthorized_key_ids()) {
			final KnownPublicSshKey key = getKeyFromKnownKeysByKeyId(keyId, serverManagementGui.getServerManagementConfiguration().getKnownPublicSshKeys());
			configuredKeys.put(key.getHash(), keyId);
		}

		final Map<String, String> knownKeys = new HashMap<>();
		for (final KnownPublicSshKey key : serverManagementGui.getServerManagementConfiguration().getKnownPublicSshKeys()) {
			knownKeys.put(key.getHash(), key.getKeyId());
		}

		final UserPublicKeysDialog userPublicKeysDialog = new UserPublicKeysDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("editUserPublicKeys", username, server.getHost()), adminKeyHash, existingKeys, configuredKeys, knownKeys);
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
					if (!user.getAuthorized_key_ids().contains(keyId)) {
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
							new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "Cannot find authorized key for storage. Hash: " + hash).setBackgroundColor(SwingColor.LightRed).open();
						}
					}
				}

				if (!compareAuthorizedKeysLists(existingAuthorizedKeys, newAuthorizedKeys)) {
					try {
						AuthorizedKeysHelper.storeAuthorizedKeys(server.getHost(), server.getSshAdmin().getUsername(), server.getSshAdmin().getPrivateKeyFile(), server.getSshAdmin().getPasswordArray(), user.getUsername(), newAuthorizedKeys);
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("succesfullyUpdatedSshUserKeys")).open();
					} catch (final Exception e) {
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
						return;
					}
				} else {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("noServerChangeNeeded")).open();
				}

				serverManagementGui.signalUnsavedChanges();
				loadUsersData((DefaultTableModel) managedUserTable.getModel(), server.getManagedUsers());
			} catch (final Exception e) {
				new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
			}
		}
	}

	private static String getAdminKeyHash(final File sshAccessPrivateKeyFile, final char[] sshAccessPassword) throws Exception {
		if (sshAccessPrivateKeyFile != null) {
			try (InputStream inputStream = new FileInputStream(sshAccessPrivateKeyFile)) {
				SshKey sshKey;
				if (Utilities.isNotBlank(sshAccessPassword)) {
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
				removeUserButton.setEnabled(Utilities.isNotBlank(username));
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
			if (worker == null) {
				worker = new RefreshServerItemStatusWorker(null, this);
				new Thread(worker).start();
			}
		}
	}

	public boolean refreshStatusIsDone() {
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
				if (Utilities.isNotBlank((String) managedUserTable.getModel().getValueAt(i, 0))) {
					managedUserTable.getModel().setValueAt("n/a", i, 2);
					managedUserTable.getModel().setValueAt("n/a", i, 3);
				}
			}
			managedUserTable.setBackground(Color.WHITE);
		}

		if (urlsTable != null) {
			for (int i = 0; i < urlsTable.getModel().getRowCount(); i++) {
				if (Utilities.isNotBlank((String) urlsTable.getModel().getValueAt(i, 0))) {
					urlsTable.getModel().setValueAt("", i, 1);
				}
			}
			urlsTable.setBackground(Color.WHITE);
		}

		if (versionsTable != null) {
			for (int i = 0; i < versionsTable.getModel().getRowCount(); i++) {
				if (Utilities.isNotBlank((String) versionsTable.getModel().getValueAt(i, 0))) {
					versionsTable.getModel().setValueAt("", i, 3);
				}
			}
			versionsTable.setBackground(Color.WHITE);
		}

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
					status = "ERROR";
				}
			}

			// Refresh urls status
			for (final String url : server.getUrls()) {
				try {
					final HttpRequest request = new HttpRequest(HttpMethod.GET, url);
					final LocalDateTime start = LocalDateTime.now();
					final HttpResponse response = HttpUtilities.executeHttpRequest(request, proxyConfiguration.getProxy(request.getUrl()), trustManager, deactivateHostnameVerification);
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
					status = "ERROR";
				}

			}

			// Refresh versions status
			refreshVersions();
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
				String data = null;
				if (version.getLocation().toLowerCase().startsWith("http://")
						|| version.getLocation().toLowerCase().startsWith("https://")) {
					try {
						final HttpRequest request = new HttpRequest(HttpMethod.GET, version.getLocation());
						final HttpResponse response = HttpUtilities.executeHttpRequest(request, proxyConfiguration.getProxy(request.getUrl()), trustManager, deactivateHostnameVerification);
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
						}
						try {
							sftpConnection = openSftpConnection(hostnameForSsh, usernameForSsh, privateKeyFileForSsh, passwordForSsh);
						} catch (final Exception e) {
							throw e;
						}
						cachedSftpConnectionString = sftpConnectionString;
					}

					if (filePath.endsWith("/")) {
						try {
							data = Utilities.join(sftpConnection.listFiles(filePath), "\n");
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
						}
						try {
							sftpConnection = openSftpConnection(hostnameForSsh, usernameForSsh, privateKeyFileForSsh, passwordForSsh);
						} catch (final Exception e) {
							throw e;
						}
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
					} else {
						setVersion(version.getName(), "ERROR");
						status = "ERROR";
					}
				} else {
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

	public String getStatus() {
		return status;
	}

	private static SftpConnection openSftpConnection(String hostname, final String sshAccessUsername, final File sshAccessPrivateKeyFile, final char[] sshAccessPassword) throws Exception, IOException {
		int port = 22;
		if (hostname.contains(":")) {
			port = Integer.parseInt(hostname.substring(hostname.lastIndexOf(":" + 1)));
			hostname = hostname.substring(0, hostname.lastIndexOf(":"));
		}

		if (sshAccessPrivateKeyFile != null) {
			byte[] sshKeyData;
			try (InputStream inputStream = new FileInputStream(sshAccessPrivateKeyFile)) {
				SshKey sshKey;
				if (Utilities.isNotBlank(sshAccessPassword)) {
					sshKey = SshKeyReader.readKey(inputStream, sshAccessPassword);
				} else {
					sshKey = SshKeyReader.readKey(inputStream, null);
				}
				final ByteArrayOutputStream keyDataStream = new ByteArrayOutputStream();
				SshKeyWriter.writePuttyVersion2Key(keyDataStream, sshKey, null);
				sshKeyData = keyDataStream.toByteArray();
			}
			return new SftpConnection(hostname, sshAccessUsername, sshKeyData, null, port, null);
		} else if (Utilities.isNotBlank(sshAccessPassword)) {
			return new SftpConnection(hostname, sshAccessUsername, null, sshAccessPassword, port, null);
		} else {
			throw new Exception("No supported authentification method available for host '" + hostname + "'");
		}
	}
}
