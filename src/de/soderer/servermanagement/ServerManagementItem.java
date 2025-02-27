package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import de.soderer.servermanagement.config.Admin;
import de.soderer.servermanagement.config.KnownPublicSshKey;
import de.soderer.servermanagement.config.Server;
import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.servermanagement.config.User;
import de.soderer.servermanagement.worker.RefreshServerItemStatusWorker;
import de.soderer.sshkeyformats.AuthorizedKey;
import de.soderer.sshkeyformats.SshKey;
import de.soderer.sshkeyformats.SshKeyReader;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.http.HttpRequest;
import de.soderer.utilities.http.HttpRequest.HttpMethod;
import de.soderer.utilities.http.HttpResponse;
import de.soderer.utilities.http.HttpUtilities;
import de.soderer.utilities.http.ProxyConfiguration;
import de.soderer.utilities.swing.QuestionDialog;
import de.soderer.utilities.swing.SimpleInputDialog;
import de.soderer.utilities.swing.SwingColor;

public class ServerManagementItem extends JPanel {
	private static final long serialVersionUID = -8920980761735439684L;

	private final ServerManagementGui serverManagementGui;
	private final ServerManagementConfiguration serverManagementConfiguration;
	private final Server server;
	private final ProxyConfiguration proxyConfiguration;

	private JTable managedUserTable;
	private JTable urlsTable;
	private String status = "OK";

	private JButton editUserButton;
	private JButton removeUserButton;

	private RefreshServerItemStatusWorker worker;

	public ServerManagementItem(final ServerManagementGui serverManagementGui, final Server server, final ProxyConfiguration proxyConfiguration) {
		super();
		setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
		setBorder(BorderFactory.createLineBorder(Color.BLACK));

		this.serverManagementGui = serverManagementGui;
		serverManagementConfiguration = serverManagementGui.getServerManagementConfiguration();
		this.server = server;
		this.proxyConfiguration = proxyConfiguration;

		createComponents(serverManagementGui, server);
	}

	private void createComponents(final ServerManagementGui serverManagementGui, final Server server) {
		removeAll();

		addServerNamePanel(server.getName());
		if (Utilities.isNotBlank(server.getHost())) {
			addHostPanel(server.getHost());
		}

		if (Utilities.isNotEmpty(server.getManagedUsers())) {
			addManagedUsersPanel(serverManagementGui, server.getManagedUsers());
		}
		addManagedUsersButtons(serverManagementGui);

		if (Utilities.isNotEmpty(server.getUrls())) {
			addUrlsPanel(server.getUrls());
		}
		addUrlsButtons(serverManagementGui);

		validate();
		updateUI();
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

		status = "OK";
	}

	public void setManagedUserCurrentAuthKeys(final String username, final int expectedAuthKeys, final int currentAuthKeys, final int surplusAuthKeys) {
		if (managedUserTable == null) {
			createComponents(serverManagementGui, server);
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

	private void addManagedUsersPanel(final ServerManagementGui serverManagementGui, final List<User> managedUsers) {
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

		loadUserData(model, managedUsers);

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
						editUser(serverManagementGui, table.convertRowIndexToModel(table.rowAtPoint(mouseEvent.getPoint())));
					} catch (final Exception e) {
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
					}
				}
			}
		});

		managedUserTable.getColumnModel().getColumn(1).setPreferredWidth(100);
		final DefaultTableCellRenderer rightRenderer1 = new DefaultTableCellRenderer();
		rightRenderer1.setHorizontalAlignment(JLabel.RIGHT);
		managedUserTable.getColumnModel().getColumn(1).setCellRenderer(rightRenderer1);

		managedUserTable.getColumnModel().getColumn(2).setPreferredWidth(100);
		final DefaultTableCellRenderer rightRenderer2 = new DefaultTableCellRenderer();
		rightRenderer2.setHorizontalAlignment(JLabel.RIGHT);
		managedUserTable.getColumnModel().getColumn(2).setCellRenderer(rightRenderer2);

		managedUserTable.getColumnModel().getColumn(3).setPreferredWidth(100);
		final DefaultTableCellRenderer rightRenderer3 = new DefaultTableCellRenderer();
		rightRenderer3.setHorizontalAlignment(JLabel.RIGHT);
		managedUserTable.getColumnModel().getColumn(3).setCellRenderer(rightRenderer3);

		managedUserTable.setBorder(BorderFactory.createLineBorder(Color.BLACK));
		managedUserTable.setRowHeight(20);


		if (managedUserTable.getPreferredSize().getHeight() < 65) {
			managedUserTable.setPreferredScrollableViewportSize(managedUserTable.getPreferredSize());
		} else {
			managedUserTable.setPreferredScrollableViewportSize(new Dimension((int) managedUserTable.getPreferredSize().getWidth(), 65));
		}

		final JScrollPane pane = new JScrollPane(managedUserTable);
		pane.setBorder(BorderFactory.createEmptyBorder());

		final JPanel managedUsersTablePanel = new JPanel();
		managedUsersTablePanel.setLayout(new BorderLayout());
		managedUsersTablePanel.add(pane);

		add(managedUsersTablePanel);

		add(Box.createRigidArea(new Dimension(5, 5)));
	}

	private void addUrlsPanel(final List<String> urls) {
		final DefaultTableModel model = new DefaultTableModel();
		model.addColumn(LangResources.get("url"));
		model.addColumn(LangResources.get("time"));

		loadUrlData(model, urls);

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
			public void valueChanged(final ListSelectionEvent e) {
				checkButtonStatus();
			}
		});

		urlsTable.getColumnModel().getColumn(1).setPreferredWidth(100);
		final DefaultTableCellRenderer rightRenderer1 = new DefaultTableCellRenderer();
		rightRenderer1.setHorizontalAlignment(JLabel.RIGHT);
		urlsTable.getColumnModel().getColumn(1).setCellRenderer(rightRenderer1);

		urlsTable.setBorder(BorderFactory.createLineBorder(Color.BLACK));
		urlsTable.setRowHeight(20);

		if (urlsTable.getPreferredSize().getHeight() < 65) {
			urlsTable.setPreferredScrollableViewportSize(urlsTable.getPreferredSize());
		} else {
			urlsTable.setPreferredScrollableViewportSize(new Dimension((int) urlsTable.getPreferredSize().getWidth(), 65));
		}

		final JScrollPane urlsScrollPane = new JScrollPane(urlsTable);
		urlsScrollPane.setBorder(BorderFactory.createEmptyBorder());

		final JPanel urlsTablePanel = new JPanel();
		urlsTablePanel.setLayout(new BorderLayout());
		urlsTablePanel.add(urlsScrollPane);

		add(urlsTablePanel);

		add(Box.createRigidArea(new Dimension(5, 5)));
	}

	private void addUrlsButtons(final ServerManagementGui serverManagementGui) {
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

						createComponents(serverManagementGui, server);

						checkButtonStatus();
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(addUrlButton);

		final JButton removeUrlButton = new JButton(LangResources.get("removeUrl"));
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

						createComponents(serverManagementGui, server);

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

	private void loadUrlData(final DefaultTableModel model, final List<String> urls) {
		for (int i = model.getRowCount() - 1; i >= 0; i--) {
			model.removeRow(i);
		}
		for (final String url : urls) {
			model.addRow(new String[] { url, "" });
		}
	}

	private void loadUserData(final DefaultTableModel model, final List<User> managedUsers) {
		for (int i = model.getRowCount() - 1; i >= 0; i--) {
			model.removeRow(i);
		}
		for (final User managedUser : managedUsers) {
			model.addRow(new String[] { managedUser.getUsername(), Integer.toString(managedUser.getAuthorized_key_ids().size()), "n/a", "n/a" });
		}
	}

	private void addManagedUsersButtons(final ServerManagementGui serverManagementGui) {
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

		final JButton readAllUsersButton = new JButton(LangResources.get("readAllUsers"));
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
						createComponents(serverManagementGui, server);
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(readAllUsersButton);

		editUserButton = new JButton(LangResources.get("editUser"));
		editUserButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					editUser(serverManagementGui, managedUserTable.getSelectedRows()[0]);
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		editUserButton.setEnabled(false);
		buttonPanel.add(editUserButton);

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

	private KnownPublicSshKey getKeyFromKnownKeysByKeyId(final String keyId, final List<KnownPublicSshKey> knownPublicSshKeys) {
		for (final KnownPublicSshKey knownPublicSshKey : knownPublicSshKeys) {
			if (knownPublicSshKey.getKeyId().equals(keyId)) {
				return knownPublicSshKey;
			}
		}
		return null;
	}

	private void editUser(final ServerManagementGui serverManagementGui, final int clickedRow) throws Exception {
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
				loadUserData((DefaultTableModel) managedUserTable.getModel(), server.getManagedUsers());
			} catch (final Exception e) {
				new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
			}
		}
	}

	private String getAdminKeyHash(final File sshAccessPrivateKeyFile, final char[] sshAccessPassword) throws Exception {
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

	private boolean compareAuthorizedKeysLists(final List<AuthorizedKey> existingAuthorizedKeys, final ArrayList<AuthorizedKey> newAuthorizedKeys) throws Exception {
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
		if (managedUserTable != null) {
			if (managedUserTable.getSelectedRowCount() == 1) {
				final String username = (String) managedUserTable.getModel().getValueAt(managedUserTable.getSelectedRow(), 0);
				editUserButton.setEnabled(Utilities.isNotBlank(username));
				removeUserButton.setEnabled(Utilities.isNotBlank(username));
			} else {
				editUserButton.setEnabled(false);
				removeUserButton.setEnabled(false);
			}
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
					request.setCheckSslCertificates(false);
					final LocalDateTime start = LocalDateTime.now();
					final HttpResponse response = HttpUtilities.executeHttpRequest(request, proxyConfiguration.getProxy(request.getUrl()));
					final LocalDateTime end = LocalDateTime.now();
					if (response.getHttpCode() == 200) {
						setUrlTime(url, (int) ChronoUnit.SECONDS.between(start, end));
					} else {
						setUrlTime(url, -1);
					}
				} catch (@SuppressWarnings("unused") final Exception e) {
					setUrlTime(url, -1);
					status = "ERROR";
				}

			}
		} catch (final Exception e) {
			e.printStackTrace();
			status = "ERROR";
		}
	}

	public String getStatus() {
		return status;
	}
}
