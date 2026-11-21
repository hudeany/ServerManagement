package de.soderer.servermanagement.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
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
import de.soderer.servermanagement.ServerManagement;
import de.soderer.servermanagement.ServerManagementException;
import de.soderer.servermanagement.config.CredentialCipher;
import de.soderer.servermanagement.config.Server;
import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.servermanagement.worker.CheckAllServerStatusWorker;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.ButtonStyle;
import de.soderer.swing.modern.Card;
import de.soderer.swing.modern.DialogStyle;
import de.soderer.swing.modern.GradientHeader;
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
	private JPanel serverInformationPanel;

	/** Table of contents above the server list with one jump button per server */
	private Card tableOfContentsCard;
	private JPanel tableOfContentsPanel;

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

		final JPanel newServerInformationPanel = createServerItemsPanel();

		final Button configurationButton = createHeaderButton(IconKind.GEAR, LangResources.get("configuration"));
		configurationButton.addActionListener(event -> openConfigurationDialog(serverManagementGui));
		final Button helpButton = createHeaderButton(IconKind.HELP, LangResources.get("help"));
		helpButton.addActionListener(event -> new HelpDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " (" + ServerManagement.VERSION.toString() + ") " + LangResources.get("help"), applicationConfiguration).open());

		final JPanel mainPanel = new JPanel(new BorderLayout(0, 8));
		mainPanel.setOpaque(false);
		mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
		// Fixed above the scroll pane, so it stays reachable while scrolling
		mainPanel.add(createTableOfContents(), BorderLayout.NORTH);
		mainPanel.add(serverInformationScrollPane, BorderLayout.CENTER);
		mainPanel.add(createActionBar(serverManagementGui, newServerInformationPanel), BorderLayout.SOUTH);

		final JPanel contentPane = new JPanel(new BorderLayout());
		contentPane.setBackground(ModernUi.BACKGROUND);
		contentPane.add(createHeader(helpButton, configurationButton), BorderLayout.NORTH);
		contentPane.add(mainPanel, BorderLayout.CENTER);
		setContentPane(contentPane);

		setConfigurationByDefinition(serverManagementConfiguration);

		checkButtonStatus();

		pack();
		setMinimumSize(new Dimension(Math.min(getWidth(), 760), 420));
		fitIntoScreen();

		setLocationRelativeTo(null);
	}

	/**
	 * Limits the window size to the usable screen area (without taskbar), so the action bar with the start button is
	 * always visible, even on small screens or with many server cards.
	 */
	private void fitIntoScreen() {
		final GraphicsConfiguration graphicsConfiguration = getGraphicsConfiguration();
		if (graphicsConfiguration == null) {
			return;
		}
		final Rectangle screenBounds = graphicsConfiguration.getBounds();
		final Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration);
		final int usableWidth = screenBounds.width - screenInsets.left - screenInsets.right;
		final int usableHeight = screenBounds.height - screenInsets.top - screenInsets.bottom;

		// The minimum size must not exceed the screen either, otherwise setSize is overruled
		final Dimension minimumSize = getMinimumSize();
		setMinimumSize(new Dimension(Math.min(minimumSize.width, usableWidth), Math.min(minimumSize.height, usableHeight)));

		setSize(Math.min(getWidth(), usableWidth), Math.min(getHeight(), usableHeight));
	}

	/**
	 * Gradient header with logo, application name, version and the help and configuration buttons.
	 */
	private JPanel createHeader(final Button helpButton, final Button configurationButton) {
		final GradientHeader header = new GradientHeader();
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
		applicationButtonPanel.add(helpButton);
		// The configuration button is the rightmost one, like in the other applications
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
	private static Button createHeaderButton(final IconKind iconKind, final String text) {
		final Button button = new Button(null, iconKind, ButtonStyle.HEADER);
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
		serverInformationPanel = new JPanel();
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
		// Moderate start height; the window is resizable and additionally limited to the screen in fitIntoScreen()
		serverInformationScrollPane.setPreferredSize(new Dimension(820, 420));
		serverInformationScrollPane.getVerticalScrollBar().setUnitIncrement(16);

		return serverInformationPanel;
	}

	/**
	 * Creates the table of contents: one button per server, wrapped into several rows if needed.
	 */
	private Card createTableOfContents() {
		tableOfContentsPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
		tableOfContentsPanel.setOpaque(false);
		// The number of rows depends on the width, so the height must be recalculated after a width change
		tableOfContentsPanel.addComponentListener(new ComponentAdapter() {
			private int lastWidth = -1;

			@Override
			public void componentResized(final ComponentEvent event) {
				final int width = tableOfContentsPanel.getWidth();
				if (width != lastWidth) {
					lastWidth = width;
					tableOfContentsPanel.revalidate();
				}
			}
		});

		tableOfContentsCard = new Card(null, null, 6);
		tableOfContentsCard.setContent(tableOfContentsPanel);

		rebuildTableOfContents();
		return tableOfContentsCard;
	}

	/**
	 * Refills the table of contents from the current server items (after adding or removing servers).
	 */
	private void rebuildTableOfContents() {
		if (tableOfContentsPanel == null) {
			return;
		}

		tableOfContentsPanel.removeAll();
		for (final ServerManagementItem serverItem : serverItems) {
			final String serverName = serverItem.getServerName();
			final Button jumpButton = new Button(StringUtilities.isNotBlank(serverName) ? serverName : "?", null, ButtonStyle.SECONDARY);
			final String host = serverItem.getServer().getHost();
			if (StringUtilities.isNotBlank(host)) {
				jumpButton.setToolTipText(host);
			}
			jumpButton.addActionListener(event -> scrollToServerItem(serverItem));
			tableOfContentsPanel.add(jumpButton);
		}

		// A table of contents is only useful for more than one server
		tableOfContentsCard.setVisible(serverItems.size() > 1);
		tableOfContentsCard.revalidate();
		tableOfContentsCard.repaint();
	}

	/**
	 * Scrolls the server list so that the card of the given server is at the top (as far as possible at the end of
	 * the list).
	 */
	private void scrollToServerItem(final ServerManagementItem serverItem) {
		final JViewport viewport = serverInformationScrollPane.getViewport();
		final int maxY = Math.max(0, serverInformationPanel.getHeight() - viewport.getExtentSize().height);
		viewport.setViewPosition(new Point(0, Math.min(serverItem.getY(), maxY)));
	}

	/**
	 * FlowLayout, which wraps its components into further rows and reports the resulting height as preferred size
	 * (the standard FlowLayout always reports a single row).
	 */
	private static class WrapLayout extends FlowLayout {
		private static final long serialVersionUID = 3870283817163405716L;

		WrapLayout(final int align, final int hgap, final int vgap) {
			super(align, hgap, vgap);
		}

		@Override
		public Dimension preferredLayoutSize(final Container target) {
			return layoutSize(target, true);
		}

		@Override
		public Dimension minimumLayoutSize(final Container target) {
			final Dimension minimum = layoutSize(target, false);
			minimum.width -= getHgap() + 1;
			return minimum;
		}

		private Dimension layoutSize(final Container target, final boolean preferred) {
			synchronized (target.getTreeLock()) {
				// Before the first layout the target has no width yet, then the width of a parent is used
				Container sizedContainer = target;
				while (sizedContainer.getWidth() == 0 && sizedContainer.getParent() != null) {
					sizedContainer = sizedContainer.getParent();
				}
				final int targetWidth = sizedContainer.getWidth() == 0 ? Integer.MAX_VALUE : sizedContainer.getWidth();

				final Insets insets = target.getInsets();
				final int horizontalInsetsAndGap = insets.left + insets.right + getHgap() * 2;
				final int maxRowWidth = targetWidth - horizontalInsetsAndGap;

				final Dimension size = new Dimension(0, 0);
				int rowWidth = 0;
				int rowHeight = 0;
				for (final Component component : target.getComponents()) {
					if (!component.isVisible()) {
						continue;
					}
					final Dimension componentSize = preferred ? component.getPreferredSize() : component.getMinimumSize();
					if (rowWidth > 0 && rowWidth + getHgap() + componentSize.width > maxRowWidth) {
						addRow(size, rowWidth, rowHeight);
						rowWidth = 0;
						rowHeight = 0;
					}
					if (rowWidth > 0) {
						rowWidth += getHgap();
					}
					rowWidth += componentSize.width;
					rowHeight = Math.max(rowHeight, componentSize.height);
				}
				addRow(size, rowWidth, rowHeight);

				size.width += horizontalInsetsAndGap;
				size.height += insets.top + insets.bottom + getVgap() * 2;
				return size;
			}
		}

		private void addRow(final Dimension size, final int rowWidth, final int rowHeight) {
			size.width = Math.max(size.width, rowWidth);
			if (size.height > 0) {
				size.height += getVgap();
			}
			size.height += rowHeight;
		}
	}

	/**
	 * Action bar: server list and key management on the left, save, the status check and close (rightmost) on the right.
	 */
	private JComponent createActionBar(final ServerManagementGui serverManagementGui, final JPanel newServerInformationPanel) {
		// Status check Button
		final Button startButton = new Button(LangResources.get("checkServerStatus"), IconKind.REFRESH, ButtonStyle.PRIMARY);
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

		final Button addServerButton = new Button(LangResources.get("addServer"), IconKind.PLUS, ButtonStyle.SECONDARY);
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
							newServerInformationPanel.add(serverItem);
							newServerInformationPanel.add(Box.createRigidArea(new Dimension(SERVER_ITEM_GAP, SERVER_ITEM_GAP)));
							serverItems.add(serverItem);
							rebuildTableOfContents();
							// Before, a new server was lost without warning when closing the application
							serverManagementGui.signalUnsavedChanges();

							// The window is resizable, so its size is kept and only the list is laid out again
							newServerInformationPanel.revalidate();
							newServerInformationPanel.repaint();

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

		final Button removeServerButton = new Button(LangResources.get("removeServer"), IconKind.TRASH, ButtonStyle.SECONDARY);
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
									final int componentIndex = Arrays.asList(newServerInformationPanel.getComponents()).indexOf(serverItem);
									if (componentIndex >= 0 && componentIndex + 1 < newServerInformationPanel.getComponentCount() && newServerInformationPanel.getComponent(componentIndex + 1) instanceof Box.Filler) {
										newServerInformationPanel.remove(componentIndex + 1);
									}
									newServerInformationPanel.remove(serverItem);
									break;
								}
							}
						}

						rebuildTableOfContents();

						// Without this the removed items stayed visible until the next repaint
						newServerInformationPanel.revalidate();
						newServerInformationPanel.repaint();

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

		final Button managePublicKeysButton = new Button(LangResources.get("managePublicKeys"), IconKind.LOCK, ButtonStyle.SECONDARY);
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
		final Button saveSettingsButton = new Button(LangResources.get("saveServerSettings"), IconKind.CHECK, ButtonStyle.SECONDARY);
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
		final Button closeButton = new Button(LangResources.get("close"), null, ButtonStyle.SECONDARY);
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
		rightActions.add(saveSettingsButton);
		rightActions.add(startButton);
		rightActions.add(closeButton);

		final JPanel actionContent = new JPanel(new BorderLayout(16, 0));
		actionContent.setOpaque(false);
		actionContent.add(leftActions, BorderLayout.WEST);
		actionContent.add(rightActions, BorderLayout.EAST);

		final Card actionCard = new Card(null, null, 6);
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
			final ApplicationConfigurationDialog applicationConfigurationDialog = new ApplicationConfigurationDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, ServerManagement.APPLICATION_STARTUPCLASS_NAME, ServerManagement.VERSION, ServerManagement.VERSION_BUILDTIME, applicationConfiguration, iconData, iconImage, ServerManagement.VERSIONINFO_DOWNLOAD_URL, ServerManagement.TRUSTED_UPDATE_CA_CERTIFICATES, appParameters, false);
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

		writeServersConfiguration(serverManagementConfiguration);
		return true;
	}

	/**
	 * Writes the server configuration to the servers configuration file without asking. Plain text passwords are
	 * encrypted before (e.g. passwords set by a dialog via the serialization setter).
	 *
	 * @throws Exception if the configuration could not be written
	 */
	public static void writeServersConfiguration(final ServerManagementConfiguration serverManagementConfiguration) throws Exception {
		// A plain text password must never be written to the configuration file
		CredentialCipher.encryptPlainPasswords(serverManagementConfiguration);

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
