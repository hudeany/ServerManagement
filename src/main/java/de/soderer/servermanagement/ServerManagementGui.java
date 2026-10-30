package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import de.soderer.applicationbase.ApplicationConfigurationProperties;
import de.soderer.applicationbase.ApplicationUpdateUtilities;
import de.soderer.applicationbase.modern.ApplicationConfigurationDialog;
import de.soderer.applicationbase.modern.UpdateableGuiApplication;
import de.soderer.json.JsonNode;
import de.soderer.json.JsonObject;
import de.soderer.json.JsonSerializer;
import de.soderer.json.JsonWriter;
import de.soderer.network.NetworkUtilities;
import de.soderer.servermanagement.config.Server;
import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.servermanagement.worker.CheckAllServerStatusWorker;
import de.soderer.swing.modern.ButtonStyle;
import de.soderer.swing.modern.DialogStyle;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.ModernUi;
import de.soderer.swing.modern.ProgressDialog;
import de.soderer.swing.modern.QuestionDialog;
import de.soderer.swing.modern.SelectableTableDialog;
import de.soderer.swing.modern.SimpleInputDialog;
import de.soderer.utilities.ConfigurationProperties;
import de.soderer.utilities.DateUtilities;
import de.soderer.utilities.ExceptionUtilities;
import de.soderer.utilities.IoUtilities;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Result;
import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.VersionInfo;

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

	/** Vertical gap between the server cards */
	private static final int SERVER_ITEM_GAP = 8;

	public static final File KEYSTORE_FILE = new File(System.getProperty("user.home") + File.separator + "." + ServerManagement.APPLICATION_NAME + File.separator + "." + ServerManagement.APPLICATION_NAME + ".keystore");

	private final ApplicationConfigurationProperties applicationConfiguration;
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

		applicationConfiguration = new ApplicationConfigurationProperties(ServerManagement.APPLICATION_NAME, true);
		ServerManagementGui.setupDefaultConfig(applicationConfiguration);
		if ("de".equalsIgnoreCase(applicationConfiguration.get(ConfigurationProperties.CONFIG_KEY_LANGUAGE))) {
			Locale.setDefault(Locale.GERMAN);
		} else {
			Locale.setDefault(Locale.ENGLISH);
		}

		if (dailyUpdateCheckIsPending()) {
			setDailyUpdateCheckStatus(true);
			try {
				if (ApplicationUpdateUtilities.checkForNewVersionAvailable(ServerManagement.VERSIONINFO_DOWNLOAD_URL, applicationConfiguration.getProxyConfiguration(), ServerManagement.APPLICATION_NAME, VersionInfo.getApplicationVersion()) != null) {
					final List<String> appParameters = new ArrayList<>();
					appParameters.add(ServerManagement.SERVERS_CONFIGURATION_FILE.getAbsolutePath());
					ApplicationUpdateUtilities.executeUpdate(this, ServerManagement.VERSIONINFO_DOWNLOAD_URL, applicationConfiguration.getProxyConfiguration(), ServerManagement.APPLICATION_NAME, ServerManagement.VERSION, ServerManagement.TRUSTED_UPDATE_CA_CERTIFICATES, null, null, appParameters, true, false);
				}
			} catch (final Exception e) {
				new QuestionDialog(this, ServerManagement.APPLICATION_NAME + " " + LangResources.get("updateCheck") + " ERROR", LangResources.get("error.cannotCheckForUpdate") + "\n" + "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
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
						&& new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("ingoreUnsavedSettings"), LangResources.get("yes"), LangResources.get("no")).withDialogStyle(DialogStyle.WARNING).open() != 0) {
					return;
				}

				dispose();
				System.exit(0);
			}
		});

		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

		final JPanel serverInformationPanel = createServerItemsPanel();

		final JButton configurationButton = createHeaderButton(IconKind.GEAR, LangResources.get("configuration"));
		configurationButton.addActionListener(event -> openConfigurationDialog(serverManagementGui));

		final JPanel mainPanel = new JPanel(new BorderLayout(0, 8));
		mainPanel.setOpaque(false);
		mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
		mainPanel.add(serverInformationScrollPane, BorderLayout.CENTER);
		mainPanel.add(createActionBar(serverManagementGui, serverInformationPanel), BorderLayout.SOUTH);

		final JPanel contentPane = new JPanel(new BorderLayout());
		contentPane.setBackground(ModernUi.BACKGROUND);
		contentPane.add(createHeader(configurationButton), BorderLayout.NORTH);
		contentPane.add(mainPanel, BorderLayout.CENTER);
		setContentPane(contentPane);

		setConfigurationByDefinition(serverManagementConfiguration);

		checkButtonStatus();

		pack();
		setMinimumSize(new Dimension(Math.min(getWidth(), 760), 420));

		setLocationRelativeTo(null);
	}

	/**
	 * Gradient header with logo, application name, version and the configuration button.
	 */
	private JPanel createHeader(final JButton configurationButton) {
		final ModernUi.GradientHeader header = new ModernUi.GradientHeader();
		final java.net.URL iconUrl = getClass().getClassLoader().getResource("ServerManagement_Icon.png");
		header.add(ModernUi.createLogo(iconUrl == null ? null : new ImageIcon(iconUrl)), BorderLayout.WEST);

		final JLabel applicationLabel = new JLabel(ServerManagement.APPLICATION_NAME);
		applicationLabel.setForeground(Color.WHITE);
		applicationLabel.setFont(ModernUi.baseFont().deriveFont(Font.BOLD, 21f));
		final JLabel versionLabel = new JLabel("Version " + ServerManagement.VERSION);
		versionLabel.setForeground(new Color(255, 255, 255, 190));
		final JPanel titlePanel = new JPanel(new GridLayout(2, 1, 0, 0));
		titlePanel.setOpaque(false);
		titlePanel.add(applicationLabel);
		titlePanel.add(versionLabel);
		header.add(titlePanel, BorderLayout.CENTER);

		final JPanel applicationButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		applicationButtonPanel.setOpaque(false);
		applicationButtonPanel.add(configurationButton);
		// GridBagLayout centers the buttons vertically in the header
		final JPanel eastPanel = new JPanel(new GridBagLayout());
		eastPanel.setOpaque(false);
		eastPanel.add(applicationButtonPanel);
		header.add(eastPanel, BorderLayout.EAST);
		return header;
	}

	/**
	 * Transparent icon button for the gradient header; the text is shown as tooltip.
	 */
	private static JButton createHeaderButton(final IconKind iconKind, final String text) {
		final JButton button = new ModernUi.Button(null, iconKind, ButtonStyle.HEADER);
		button.setToolTipText(text);
		button.getAccessibleContext().setAccessibleName(text);
		return button;
	}

	/**
	 * Creates the scrollable list of the server cards.
	 *
	 * @return the panel holding the server cards
	 */
	private JPanel createServerItemsPanel() throws Exception {
		final JPanel serverInformationPanel = new JPanel();
		serverInformationPanel.setOpaque(false);
		serverInformationPanel.setLayout(new BoxLayout(serverInformationPanel, BoxLayout.PAGE_AXIS));

		for (final Server server : serverManagementConfiguration.getServers()) {
			final ServerManagementItem serverItem = new ServerManagementItem(this, server, applicationConfiguration);
			serverInformationPanel.add(serverItem);
			serverInformationPanel.add(Box.createRigidArea(new Dimension(SERVER_ITEM_GAP, SERVER_ITEM_GAP)));
			serverItems.add(serverItem);
		}

		serverInformationScrollPane = new JScrollPane(serverInformationPanel);
		serverInformationScrollPane.setBorder(BorderFactory.createEmptyBorder());
		serverInformationScrollPane.setViewportBorder(null);
		serverInformationScrollPane.setOpaque(false);
		serverInformationScrollPane.getViewport().setBackground(ModernUi.BACKGROUND);
		ModernUi.styleScrollBars(serverInformationScrollPane, ModernUi.BACKGROUND);
		serverInformationScrollPane.setPreferredSize(new Dimension(820, 600));
		serverInformationScrollPane.getVerticalScrollBar().setUnitIncrement(16);

		return serverInformationPanel;
	}

	/**
	 * Action bar: server list and key management on the left, close, save and the status check on the right.
	 */
	private JComponent createActionBar(final ServerManagementGui serverManagementGui, final JPanel serverInformationPanel) {
		// Status check Button
		final JButton startButton = new ModernUi.Button(LangResources.get("checkServerStatus"), IconKind.REFRESH, ButtonStyle.PRIMARY);
		startButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					checkServerStatus(serverManagementGui);
				} catch (final Exception e) {
					showError(serverManagementGui, e);
				}
			}
		});

		final JButton addServerButton = new ModernUi.Button(LangResources.get("addServer"), IconKind.PLUS, ButtonStyle.SECONDARY);
		addServerButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final String servername = new SimpleInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("addServer"), LangResources.get("enterServerName")).open();
					if (StringUtilities.isNotBlank(servername)) {
						final String host = new SimpleInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("addServer"), LangResources.get("enterHost")).open();
						if (StringUtilities.isNotBlank(host)) {
							final Server newServer = new Server().setName(servername.trim()).setHost(host.trim());
							serverManagementConfiguration.getServers().add(newServer);
							final ServerManagementItem serverItem = new ServerManagementItem(serverManagementGui, newServer, applicationConfiguration);
							serverInformationPanel.add(serverItem);
							serverInformationPanel.add(Box.createRigidArea(new Dimension(SERVER_ITEM_GAP, SERVER_ITEM_GAP)));
							serverItems.add(serverItem);
							// Before, a new server was lost without warning when closing the application
							serverManagementGui.signalUnsavedChanges();

							// The window is resizable, so its size is kept and only the list is laid out again
							serverInformationPanel.revalidate();
							serverInformationPanel.repaint();

							// Scroll to end, once the new card was laid out
							SwingUtilities.invokeLater(() -> {
								final JScrollBar verticalScrollBar = serverInformationScrollPane.getVerticalScrollBar();
								verticalScrollBar.setValue(verticalScrollBar.getMaximum());
							});
						}
					}
				} catch (final Exception e) {
					showError(serverManagementGui, e);
				}
			}
		});

		final JButton removeServerButton = new ModernUi.Button(LangResources.get("removeServer"), IconKind.TRASH, ButtonStyle.SECONDARY);
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
					// null when closed via the window decoration
					if (Boolean.TRUE.equals(tableDialog.open())) {
						for (final List<String> row : tableDialog.getSelectedItems()) {
							final String servername = row.get(0);
							for (final ServerManagementItem serverItem : serverItems) {
								if (serverItem.getServerName().equals(servername)) {
									serverItems.remove(serverItem);
									// Before, the server stayed in the configuration, so it was saved again and came back on the next start
									serverManagementConfiguration.getServers().remove(serverItem.getServer());
									// Also remove the spacing area after the item
									final int componentIndex = Arrays.asList(serverInformationPanel.getComponents()).indexOf(serverItem);
									if (componentIndex >= 0 && componentIndex + 1 < serverInformationPanel.getComponentCount() && serverInformationPanel.getComponent(componentIndex + 1) instanceof Box.Filler) {
										serverInformationPanel.remove(componentIndex + 1);
									}
									serverInformationPanel.remove(serverItem);
									break;
								}
							}
						}

						// Without this the removed items stayed visible until the next repaint
						serverInformationPanel.revalidate();
						serverInformationPanel.repaint();

						// Scroll to start
						final JScrollBar verticalScrollBar = serverInformationScrollPane.getVerticalScrollBar();
						verticalScrollBar.setValue(verticalScrollBar.getMinimum());

						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					showError(serverManagementGui, e);
				}
			}
		});

		final JButton managePublicKeysButton = new ModernUi.Button(LangResources.get("managePublicKeys"), IconKind.LOCK, ButtonStyle.SECONDARY);
		managePublicKeysButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final KnownPublicKeysDialog publicKeysDialog = new KnownPublicKeysDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("managePublicKeys"), serverManagementConfiguration);
					if (Boolean.TRUE.equals(publicKeysDialog.open())) {
						serverManagementGui.signalUnsavedChanges();
					}
				} catch (final Exception e) {
					showError(serverManagementGui, e);
				}
			}
		});

		// Save Button
		final JButton saveSettingsButton = new ModernUi.Button(LangResources.get("saveServerSettings"), IconKind.CHECK, ButtonStyle.SECONDARY);
		saveSettingsButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					// Only reset the flag if the settings were really saved (not on "no" or an error)
					if (saveSettings(serverManagementGui)) {
						hasUnsavedChanges = false;
					}
				} catch (final Exception e) {
					showError(serverManagementGui, e);
				}
			}
		});

		// Close Button
		final JButton closeButton = new ModernUi.Button(LangResources.get("close"), null, ButtonStyle.SECONDARY);
		closeButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				if (hasUnsavedChanges
						&& new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("ingoreUnsavedSettings"), LangResources.get("yes"), LangResources.get("no")).withDialogStyle(DialogStyle.WARNING).open() != 0) {
					return;
				}

				dispose();
				System.exit(0);
			}
		});

		final JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		leftActions.setOpaque(false);
		leftActions.add(addServerButton);
		leftActions.add(removeServerButton);
		leftActions.add(managePublicKeysButton);

		final JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		rightActions.setOpaque(false);
		rightActions.add(closeButton);
		rightActions.add(saveSettingsButton);
		rightActions.add(startButton);

		final JPanel actionContent = new JPanel(new BorderLayout(16, 0));
		actionContent.setOpaque(false);
		actionContent.add(leftActions, BorderLayout.WEST);
		actionContent.add(rightActions, BorderLayout.EAST);

		final ModernUi.Card actionCard = new ModernUi.Card(null, null, 6);
		actionCard.setContent(actionContent);
		return actionCard;
	}

	/**
	 * Opens the application configuration (language, proxy, daily update check, desktop link, ...).
	 */
	private void openConfigurationDialog(final ServerManagementGui serverManagementGui) {
		try {
			byte[] iconData;
			try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("ServerManagement.ico")) {
				iconData = IoUtilities.toByteArray(inputStream);
			}

			Image iconImage;
			try (InputStream imageIconStream = getClass().getClassLoader().getResourceAsStream("ServerManagement_Icon.png")) {
				iconImage = ImageIO.read(imageIconStream);
			}
			final List<String> appParameters = new ArrayList<>();
			appParameters.add(ServerManagement.SERVERS_CONFIGURATION_FILE.getAbsolutePath());
			final ApplicationConfigurationDialog applicationConfigurationDialog = new ApplicationConfigurationDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, ServerManagement.APPLICATION_STARTUPCLASS_NAME, ServerManagement.VERSION, ServerManagement.VERSION_BUILDTIME, applicationConfiguration, iconData, iconImage, ServerManagement.VERSIONINFO_DOWNLOAD_URL, ServerManagement.TRUSTED_UPDATE_CA_CERTIFICATES, appParameters);
			final Result result = applicationConfigurationDialog.open();
			if (result != null && result == Result.OK) {
				applicationConfiguration.save();
			}
		} catch (final Exception e) {
			showError(serverManagementGui, e);
		}
	}

	/**
	 * Shows an error message.
	 */
	static void showError(final Window parent, final Exception e) {
		new QuestionDialog(parent, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
	}

	/**
	 * Saves the server configuration.
	 *
	 * @return true, if the configuration was saved, false, if the user declined to overwrite the existing file
	 * @throws Exception if the configuration could not be written (before, this was only printed to the console)
	 */
	private boolean saveSettings(final ServerManagementGui serverManagementGui) throws Exception {
		if (ServerManagement.SERVERS_CONFIGURATION_FILE.exists()
				&& new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("overwriteServerSettings"), LangResources.get("yes"), LangResources.get("no")).open() != 0) {
			return false;
		}

		final File configurationFile = ServerManagement.SERVERS_CONFIGURATION_FILE.getAbsoluteFile();
		// The directory is missing on the first start
		if (configurationFile.getParentFile() != null && !configurationFile.getParentFile().exists()) {
			Files.createDirectories(configurationFile.getParentFile().toPath());
		}

		// Write into a temporary file first, so a failing write does not destroy the existing configuration
		final File temporaryFile = new File(configurationFile.getAbsolutePath() + ".tmp");
		try {
			final JsonNode rootNode = JsonSerializer.serialize(serverManagementConfiguration);
			try (JsonWriter writer = new JsonWriter(new FileOutputStream(temporaryFile))) {
				writer.add((JsonObject) rootNode);
			}
			Files.move(temporaryFile.toPath(), configurationFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (final Exception e) {
			Files.deleteIfExists(temporaryFile.toPath());
			throw new Exception("Cannot save server settings to '" + configurationFile.getAbsolutePath() + "': " + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
		}
		return true;
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
				new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("error.canceledbyuser")).withDialogStyle(DialogStyle.WARNING).open();
			} else if (result == Result.ERROR) {
				final Exception e = worker.getError();
				if (e instanceof ServerManagementException) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
				} else {
					final String stacktrace = ExceptionUtilities.getStackTrace(e);
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getClass().getSimpleName() + ":\n" + e.getMessage() + "\n\n" + stacktrace).withDialogStyle(DialogStyle.ERROR).open();
				}
			} else {
				final LocalDateTime start = worker.getStartTime();
				final LocalDateTime end = worker.getEndTime();

				@SuppressWarnings("unused")
				String resultText = LangResources.get("start") + ": " + DateUtilities.formatDate(DateUtilities.YYYY_MM_DD_HHMMSS, start) + "\n" + LangResources.get("end") + ": " + DateUtilities.formatDate(DateUtilities.YYYY_MM_DD_HHMMSS, end) + "\n" + LangResources.get("timeelapsed") + ": "
						+ DateUtilities.getHumanReadableTimespan(Duration.between(start, end), true);

				resultText += "\n" + LangResources.get("serversChecked") + ": " + Utilities.getHumanReadableInteger(worker.getItemsDone(), null, Locale.getDefault());

				final boolean showResult = false;
				if (showResult) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, resultText).withDialogStyle(DialogStyle.INFORMATION).open();
				}

				final List<String> errorneousServers = worker.get();
				if (errorneousServers.size() > 0) {
					String errorneousServerText = "Errors:\n";
					for (final String errorneousServer : errorneousServers) {
						errorneousServerText += errorneousServer + "\n";
					}
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, errorneousServerText).withDialogStyle(DialogStyle.ERROR).open();
				}
			}
		} catch (final Exception e) {
			new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
		}
	}

	public static void setupDefaultConfig(final ConfigurationProperties applicationConfiguration) {
		applicationConfiguration.setupDefaultConfig();
	}

	@Override
	protected void setDailyUpdateCheckStatus(final boolean checkboxStatus) {
		applicationConfiguration.set(ApplicationConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK, checkboxStatus);
		applicationConfiguration.set(ApplicationConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK, LocalDateTime.now().plusDays(1));
		applicationConfiguration.save();
	}

	@Override
	protected Boolean isDailyUpdateCheckActivated() {
		return applicationConfiguration.getBoolean(ApplicationConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK);
	}

	protected boolean dailyUpdateCheckIsPending() {
		return applicationConfiguration.getBoolean(ApplicationConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK)
				&& (applicationConfiguration.getDate(ApplicationConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK) == null || applicationConfiguration.getDate(ApplicationConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK).isBefore(LocalDateTime.now()))
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
