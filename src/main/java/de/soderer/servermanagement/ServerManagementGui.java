package de.soderer.servermanagement;

import java.awt.Dimension;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.WindowConstants;

import de.soderer.json.JsonNode;
import de.soderer.json.JsonObject;
import de.soderer.json.JsonSerializer;
import de.soderer.json.JsonWriter;
import de.soderer.network.NetworkUtilities;
import de.soderer.pac.utilities.ProxyConfiguration;
import de.soderer.pac.utilities.ProxyConfiguration.ProxyConfigurationType;
import de.soderer.servermanagement.config.Server;
import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.servermanagement.worker.CheckAllServerStatusWorker;
import de.soderer.utilities.ConfigurationProperties;
import de.soderer.utilities.DateUtilities;
import de.soderer.utilities.ExceptionUtilities;
import de.soderer.utilities.IoUtilities;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Result;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.VersionInfo;
import de.soderer.utilities.appupdate.ApplicationUpdateUtilities;
import de.soderer.utilities.swing.ApplicationConfigurationDialog;
import de.soderer.utilities.swing.ProgressDialog;
import de.soderer.utilities.swing.QuestionDialog;
import de.soderer.utilities.swing.SelectableTableDialog;
import de.soderer.utilities.swing.SimpleInputDialog;
import de.soderer.utilities.swing.SwingColor;
import de.soderer.utilities.swing.UpdateableGuiApplication;

/**
 * TODO:
	Convenience Button: Add KnownKey to all selected users
	Convenience Button: Remove KnownKey from all servers
	Interval Testing button: every 5 sec
 */

/**
 * The GUI for ServerManagement.
 */
public class ServerManagementGui extends UpdateableGuiApplication {
	/** The Constant serialVersionUID. */
	private static final long serialVersionUID = 5969613637206441880L;

	public static final File KEYSTORE_FILE = new File(System.getProperty("user.home") + File.separator + "." + ServerManagement.APPLICATION_NAME + File.separator + "." + ServerManagement.APPLICATION_NAME + ".keystore");
	public static final String CONFIG_DAILY_UPDATE_CHECK = "DailyUpdateCheck";
	public static final String CONFIG_NEXT_DAILY_UPDATE_CHECK = "NextDailyUpdateCheck";
	public static final ProxyConfiguration PROXYCONFIGURATION = new ProxyConfiguration(ProxyConfigurationType.WPAD, null);

	private final ConfigurationProperties applicationConfiguration;
	private final ServerManagementConfiguration serverManagementConfiguration;
	private boolean hasUnsavedChanges = false;

	private final List<ServerManagementItem> serverItems = new ArrayList<>();
	private JScrollPane serverInformationScrollPane;

	/**
	 * Instantiates a new ServerManagement gui.
	 *
	 * @param serverManagementConfiguration
	 *            the ServerManagementConfiguration
	 * @throws Exception
	 *             the exception
	 */
	public ServerManagementGui(final ServerManagementConfiguration serverManagementConfiguration) throws Exception {
		super(ServerManagement.APPLICATION_NAME, ServerManagement.VERSION, KEYSTORE_FILE);

		this.serverManagementConfiguration = serverManagementConfiguration;

		setTitle(ServerManagement.APPLICATION_NAME + " (Version " + ServerManagement.VERSION.toString() + ")");

		applicationConfiguration = new ConfigurationProperties(ServerManagement.APPLICATION_NAME, true);
		ServerManagementGui.setupDefaultConfig(applicationConfiguration);
		if ("de".equalsIgnoreCase(applicationConfiguration.get(ApplicationConfigurationDialog.CONFIG_LANGUAGE))) {
			Locale.setDefault(Locale.GERMAN);
		} else {
			Locale.setDefault(Locale.ENGLISH);
		}

		final ProxyConfigurationType proxyConfigurationType = ProxyConfigurationType.getFromString(applicationConfiguration.get(ApplicationConfigurationDialog.CONFIG_PROXY_CONFIGURATION_TYPE));
		final String proxyUrl = applicationConfiguration.get(ApplicationConfigurationDialog.CONFIG_PROXY_URL);
		final ProxyConfiguration proxyConfiguration = new ProxyConfiguration(proxyConfigurationType, proxyUrl);

		if (dailyUpdateCheckIsPending()) {
			setDailyUpdateCheckStatus(true);
			try {
				if (ApplicationUpdateUtilities.checkForNewVersionAvailable(ServerManagement.VERSIONINFO_DOWNLOAD_URL, proxyConfiguration, ServerManagement.APPLICATION_NAME, VersionInfo.getApplicationVersion()) != null) {
					ApplicationUpdateUtilities.executeUpdate(this, ServerManagement.VERSIONINFO_DOWNLOAD_URL, proxyConfiguration, ServerManagement.APPLICATION_NAME, ServerManagement.VERSION, ServerManagement.TRUSTED_UPDATE_CA_CERTIFICATES, null, null, "gui", true, false);
				}
			} catch (final Exception e) {
				new QuestionDialog(this, ServerManagement.APPLICATION_NAME + " " + LangResources.get("updateCheck") + " ERROR", LangResources.get("error.cannotCheckForUpdate") + "\n" + "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
			}
		}

		try (InputStream imageIconStream = this.getClass().getClassLoader().getResourceAsStream("ServerManagement_Icon.png")) {
			final BufferedImage imageIcon = ImageIO.read(imageIconStream);
			setIconImage(imageIcon);
		}

		setLocationRelativeTo(null);

		final ServerManagementGui serverManagementGui = this;

		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				if (hasUnsavedChanges
						&& new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("ingoreUnsavedSettings"), LangResources.get("yes"), LangResources.get("no")).open() != 0) {
					return;
				}

				dispose();
				System.exit(0);
			}
		});

		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

		setLayout(new BoxLayout(getContentPane(), BoxLayout.PAGE_AXIS));
		((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

		final JPanel serverInformationPanel = addServerItemsPanel();

		add(Box.createRigidArea(new Dimension(0, 5)));

		addButtons1(serverManagementGui, serverInformationPanel);
		addButtons2(serverManagementGui);

		setConfigurationByDefinition(serverManagementConfiguration);

		checkButtonStatus();

		add(Box.createRigidArea(new Dimension(0, 5)));

		pack();

		setLocationRelativeTo(null);
		setResizable(false);
	}

	private JPanel addServerItemsPanel() throws Exception {
		// Server information Panel
		final JPanel serverInformationPanel = new JPanel();
		serverInformationPanel.setLayout(new BoxLayout(serverInformationPanel, BoxLayout.PAGE_AXIS));

		for (final Server server : serverManagementConfiguration.getServers()) {
			final ServerManagementItem serverItem = new ServerManagementItem(this, server, PROXYCONFIGURATION);
			serverInformationPanel.add(serverItem);
			serverInformationPanel.add(Box.createRigidArea(new Dimension(10, 10)));
			serverItems.add(serverItem);
		}

		serverInformationScrollPane = new JScrollPane(serverInformationPanel);
		serverInformationScrollPane.setBorder(null);
		serverInformationScrollPane.setViewportBorder(null);
		serverInformationScrollPane.setPreferredSize(new Dimension(750, 600));
		serverInformationScrollPane.getVerticalScrollBar().setUnitIncrement(8);

		final JPanel mainPanel = new JPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.LINE_AXIS));
		mainPanel.add(serverInformationScrollPane);

		add(mainPanel);
		return serverInformationPanel;
	}

	private void addButtons1(final ServerManagementGui serverManagementGui, final JPanel serverInformationPanel) {
		final JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));

		// Start Button
		final JButton startButton = new JButton(LangResources.get("checkServerStatus"));
		startButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					checkServerStatus(serverManagementGui);
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(startButton);

		buttonPanel.add(Box.createRigidArea(new Dimension(5, 0)));

		final JButton addServerButton = new JButton(LangResources.get("addServer"));
		addServerButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final String servername = new SimpleInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("addServer"), LangResources.get("enterServerName")).open();
					if (servername != null) {
						final String host = new SimpleInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("addServer"), LangResources.get("enterHost")).open();
						if (host != null) {
							final Server newServer = new Server().setName(servername).setHost(host);
							serverManagementConfiguration.getServers().add(newServer);
							final ServerManagementItem serverItem = new ServerManagementItem(serverManagementGui, newServer, PROXYCONFIGURATION);
							serverInformationPanel.add(serverItem);
							serverInformationPanel.add(Box.createRigidArea(new Dimension(10, 10)));
							serverItems.add(serverItem);

							serverManagementGui.pack();

							// Scroll to end
							final JScrollBar verticalScrollBar = serverInformationScrollPane.getVerticalScrollBar();
							verticalScrollBar.setValue(verticalScrollBar.getMaximum());
						}
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(addServerButton);

		buttonPanel.add(Box.createRigidArea(new Dimension(5, 0)));

		final JButton removeServerButton = new JButton(LangResources.get("removeServer"));
		removeServerButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final List<List<String>> servernamesList = new ArrayList<>();
					for (final Server server : serverManagementConfiguration.getServers()) {
						final List<String> serverList = new ArrayList<>();
						serverList.add(server.getName());
						servernamesList.add(serverList);
					}
					final SelectableTableDialog tableDialog = new SelectableTableDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("removeServer"), LangResources.get("selectServersToRemove"),
							Collections.singletonList("Servername"),
							servernamesList,
							false,
							LangResources.get("selectAll"),
							LangResources.get("selectNone"),
							LangResources.get("removeServer"),
							LangResources.get("cancel")
							);
					if (tableDialog.open()) {
						for (final List<String> row : tableDialog.getSelectedItems()) {
							final String servername = row.get(0);
							for (final ServerManagementItem serverItem : serverItems) {
								if (serverItem.getServerName().equals(servername)) {
									serverItems.remove(serverItem);
									serverInformationPanel.remove(serverItem);
									break;
								}
							}
						}

						// Scroll to start
						final JScrollBar verticalScrollBar = serverInformationScrollPane.getVerticalScrollBar();
						verticalScrollBar.setValue(verticalScrollBar.getMinimum());

						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(removeServerButton);

		buttonPanel.add(Box.createRigidArea(new Dimension(5, 0)));

		final JButton managePublicKeysButton = new JButton(LangResources.get("managePublicKeys"));
		managePublicKeysButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final KnownPublicKeysDialog publicKeysDialog = new KnownPublicKeysDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("managePublicKeys"), serverManagementConfiguration);
					if (publicKeysDialog.open()) {
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(managePublicKeysButton);

		serverManagementGui.add(buttonPanel);
	}

	private void addButtons2(final ServerManagementGui serverManagementGui) {
		final JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));

		// Save Button
		final JButton saveSettingsButton = new JButton(LangResources.get("saveServerSettings"));
		saveSettingsButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					saveSettings(serverManagementGui);
					hasUnsavedChanges = false;
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(saveSettingsButton);

		buttonPanel.add(Box.createRigidArea(new Dimension(5, 0)));

		// Configuration Button
		final JButton configurationButton = new JButton(LangResources.get("configuration"));
		configurationButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					byte[] iconData;
					try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("ServerManagement.ico")) {
						iconData = IoUtilities.toByteArray(inputStream);
					}

					Image iconImage;
					try (InputStream imageIconStream = getClass().getClassLoader().getResourceAsStream("ServerManagement_Icon.png")) {
						iconImage = ImageIO.read(getClass().getClassLoader().getResource("ServerManagement_Icon.png"));
					}

					final ApplicationConfigurationDialog applicationConfigurationDialog = new ApplicationConfigurationDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, ServerManagement.APPLICATION_STARTUPCLASS_NAME, ServerManagement.VERSION, ServerManagement.VERSION_BUILDTIME, applicationConfiguration, iconData, iconImage, ServerManagement.VERSIONINFO_DOWNLOAD_URL, ServerManagement.TRUSTED_UPDATE_CA_CERTIFICATES, ServerManagement.SERVERS_CONFIGURATION_FILE.getAbsolutePath());
					final Result result = applicationConfigurationDialog.open();
					if (result != null && result == Result.OK) {
						applicationConfiguration.save();
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		buttonPanel.add(configurationButton);

		buttonPanel.add(Box.createRigidArea(new Dimension(5, 0)));

		// Close Button
		final JButton closeButton = new JButton(LangResources.get("close"));
		closeButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				if (hasUnsavedChanges
						&& new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("ingoreUnsavedSettings"), LangResources.get("yes"), LangResources.get("no")).open() != 0) {
					return;
				}

				dispose();
				System.exit(0);
			}
		});
		buttonPanel.add(closeButton);

		serverManagementGui.add(buttonPanel);
	}

	private void saveSettings(final ServerManagementGui serverManagementGui) {
		if (ServerManagement.SERVERS_CONFIGURATION_FILE.exists()
				&& new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("overwriteServerSettings"), LangResources.get("yes"), LangResources.get("no")).open() != 0) {
			return;
		}

		try {
			final JsonNode rootNode = JsonSerializer.serialize(serverManagementConfiguration);
			try (JsonWriter writer = new JsonWriter(new FileOutputStream(ServerManagement.SERVERS_CONFIGURATION_FILE))) {
				writer.add((JsonObject) rootNode);
			}
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Sets the configuration by definition.
	 *
	 * @param serverManagementConfiguration
	 *            the new configuration by definition
	 * @throws Exception
	 *             the exception
	 */
	private void setConfigurationByDefinition(final ServerManagementConfiguration serverManagementConfiguration) throws Exception {
		checkButtonStatus();
	}

	/**
	 * Check button status.
	 */
	private void checkButtonStatus() {
		// nothing to do
	}

	private void checkServerStatus(final ServerManagementGui serverManagementGui) throws Exception {
		try {
			// The worker parent is set later by the opened DualProgressDialog
			final CheckAllServerStatusWorker worker = new CheckAllServerStatusWorker(null, serverItems);

			final Result result;
			final ProgressDialog<CheckAllServerStatusWorker> progressDialog = new ProgressDialog<>(serverManagementGui, ServerManagement.APPLICATION_NAME, null, worker);
			result = progressDialog.open();

			if (result == Result.CANCELED) {
				new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("error.canceledbyuser")).setBackgroundColor(SwingColor.Yellow).open();
			} else if (result == Result.ERROR) {
				final Exception e = worker.getError();
				if (e instanceof ServerManagementException) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				} else {
					final String stacktrace = ExceptionUtilities.getStackTrace(e);
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getClass().getSimpleName() + ":\n" + e.getMessage() + "\n\n" + stacktrace).setBackgroundColor(SwingColor.LightRed).open();
				}
			} else {
				final LocalDateTime start = worker.getStartTime();
				final LocalDateTime end = worker.getEndTime();

				@SuppressWarnings("unused")
				String resultText = LangResources.get("start") + ": " + DateUtilities.formatDate(DateUtilities.YYYY_MM_DD_HHMMSS, start) + "\n" + LangResources.get("end") + ": " + DateUtilities.formatDate(DateUtilities.YYYY_MM_DD_HHMMSS, end) + "\n" + LangResources.get("timeelapsed") + ": "
						+ DateUtilities.getHumanReadableTimespan(Duration.between(start, end), true);

				resultText += "\n" + LangResources.get("servers found") + ": " + Utilities.getHumanReadableInteger(worker.getItemsDone(), null, Locale.getDefault());

				final boolean showResult = false;
				if (showResult) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, resultText).open();
				}

				final List<String> errorneousServers = worker.get();
				if (errorneousServers.size() > 0) {
					String errorneousServerText = "Errors:\n";
					for (final String errorneousServer : errorneousServers) {
						errorneousServerText += errorneousServer + "\n";
					}
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, errorneousServerText).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		} catch (final Exception e) {
			new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
		}
	}

	public static void setupDefaultConfig(final ConfigurationProperties applicationConfiguration) {
		if (Utilities.isBlank(applicationConfiguration.get(ApplicationConfigurationDialog.CONFIG_LANGUAGE))) {
			applicationConfiguration.set(ApplicationConfigurationDialog.CONFIG_LANGUAGE, Locale.getDefault().getLanguage());
		}
	}

	@Override
	protected void setDailyUpdateCheckStatus(final boolean checkboxStatus) {
		applicationConfiguration.set(CONFIG_DAILY_UPDATE_CHECK, checkboxStatus);
		applicationConfiguration.set(CONFIG_NEXT_DAILY_UPDATE_CHECK, LocalDateTime.now().plusDays(1));
		applicationConfiguration.save();
	}

	@Override
	protected Boolean isDailyUpdateCheckActivated() {
		return applicationConfiguration.getBoolean(CONFIG_DAILY_UPDATE_CHECK);
	}

	protected boolean dailyUpdateCheckIsPending() {
		return applicationConfiguration.getBoolean(CONFIG_DAILY_UPDATE_CHECK)
				&& (applicationConfiguration.getDate(CONFIG_NEXT_DAILY_UPDATE_CHECK) == null || applicationConfiguration.getDate(CONFIG_NEXT_DAILY_UPDATE_CHECK).isBefore(LocalDateTime.now()))
				&& NetworkUtilities.checkForNetworkConnection();
	}

	public void signalUnsavedChanges() {
		hasUnsavedChanges = true;
	}

	public List<ServerManagementItem> getServerItems() {
		return serverItems;
	}

	public ServerManagementConfiguration getServerManagementConfiguration() {
		return serverManagementConfiguration;
	}
}
