package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.EventQueue;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import de.soderer.applicationbase.ApplicationConfigurationProperties;
import de.soderer.servermanagement.check.LoginCheckResult;
import de.soderer.servermanagement.check.LoginChecker;
import de.soderer.servermanagement.check.StatusLevel;
import de.soderer.servermanagement.check.UrlCheckResult;
import de.soderer.servermanagement.check.UrlChecker;
import de.soderer.servermanagement.check.VersionCheckRun;
import de.soderer.servermanagement.check.VersionResult;
import de.soderer.servermanagement.config.Admin;
import de.soderer.servermanagement.config.Server;
import de.soderer.servermanagement.http.HttpAccess;
import de.soderer.servermanagement.http.JenkinsClient;
import de.soderer.servermanagement.http.OidcClient;
import de.soderer.servermanagement.sshkeys.AuthorizedKeysStatusCheck;
import de.soderer.servermanagement.sshkeys.UserKeyStatus;
import de.soderer.servermanagement.worker.RefreshServerItemStatusWorker;
import de.soderer.swing.modern.Card;
import de.soderer.swing.modern.DialogStyle;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.ModernUi;
import de.soderer.swing.modern.QuestionDialog;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;

/**
 * Card of a server: sections for managed SSH users, logins, URLs, versions and logs, and the status check of the
 * server.
 *
 * The status check runs in a worker thread: the checks only return results and determine the status, the tables are
 * updated in the event dispatch thread.
 */
public class ServerManagementItem extends JPanel {
	private static final long serialVersionUID = -8920980761735439684L;

	private final ServerManagementGui serverManagementGui;
	private final Server server;

	private final HttpAccess httpAccess;
	private final OidcClient oidcClient;
	private final JenkinsClient jenkinsClient;
	private final LoginChecker loginChecker;

	private final ItemUsersSection usersSection;
	private final ItemLoginsSection loginsSection;
	private final ItemUrlsSection urlsSection;
	private final ItemVersionsSection versionsSection;
	private final ItemLogsSection logsSection;
	private final List<ItemSection> sections;

	/** Language resource key of the display name of each section, in display order */
	private final Map<ItemSection, String> sectionNameKeys = new LinkedHashMap<>();

	/** Sections hidden by the user; hidden sections are still checked, they are only not shown */
	private final Set<ItemSection> hiddenSections = new HashSet<>();

	private RefreshServerItemStatusWorker worker;

	/** Worst status of all checks since the last reset; written by the worker thread and the event dispatch thread */
	private volatile StatusLevel status = StatusLevel.OK;

	public ServerManagementItem(final ServerManagementGui serverManagementGui, final Server server, final ApplicationConfigurationProperties applicationConfiguration) throws Exception {
		super(new BorderLayout());
		setOpaque(false);

		this.serverManagementGui = serverManagementGui;
		this.server = server;

		httpAccess = new HttpAccess(applicationConfiguration, serverManagementGui.getServerManagementConfiguration().getKeystore());
		oidcClient = new OidcClient(httpAccess);
		jenkinsClient = new JenkinsClient(httpAccess);
		loginChecker = new LoginChecker(httpAccess, oidcClient, jenkinsClient);

		usersSection = new ItemUsersSection(this);
		loginsSection = new ItemLoginsSection(this);
		urlsSection = new ItemUrlsSection(this);
		versionsSection = new ItemVersionsSection(this);
		logsSection = new ItemLogsSection(this);
		sections = List.of(usersSection, loginsSection, urlsSection, versionsSection, logsSection);

		sectionNameKeys.put(usersSection, "managedUsers");
		sectionNameKeys.put(loginsSection, "section.logins");
		sectionNameKeys.put(urlsSection, "section.urls");
		sectionNameKeys.put(versionsSection, "section.versions");
		sectionNameKeys.put(logsSection, "section.logs");

		rebuild();
	}

	public String getServerName() {
		return server.getName();
	}

	public Server getServer() {
		return server;
	}

	/**
	 * Status of the last check: "OK", "WARNING" or "ERROR".
	 */
	public String getStatus() {
		return status.name();
	}

	/**
	 * Clears the results of the last status check.
	 */
	public void resetStatus() {
		runInEventDispatchThread(() -> {
			for (final ItemSection section : sections) {
				section.resetStatus();
			}
		});
		status = StatusLevel.OK;
	}

	/**
	 * @deprecated same as {@link #resetStatus()}
	 */
	@Deprecated
	public void resetTablesStatus() {
		resetStatus();
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

	/**
	 * Checks SSH users, URLs, versions and logins of the server. Called by the worker thread; the network checks run in
	 * the calling thread, the results are shown in the event dispatch thread.
	 */
	public void refreshStatus() {
		try {
			resetStatus();

			final List<UserKeyStatus> userKeyStatusList = new AuthorizedKeysStatusCheck(server, serverManagementGui.getServerManagementConfiguration().getKnownPublicSshKeys()).check();
			final List<UrlCheckResult> urlResults = new UrlChecker(httpAccess).check(server.getUrls());
			final List<VersionResult> versionResults = runVersionCheck();
			final List<LoginCheckResult> loginResults = runLoginCheck();

			for (final UserKeyStatus userKeyStatus : userKeyStatusList) {
				raiseStatus(userKeyStatus.getLevel());
			}
			for (final UrlCheckResult urlResult : urlResults) {
				raiseStatus(urlResult.getLevel());
			}
			for (final VersionResult versionResult : versionResults) {
				raiseStatus(versionResult.level());
			}
			for (final LoginCheckResult loginResult : loginResults) {
				raiseStatus(loginResult.getLevel());
			}

			runInEventDispatchThread(() -> {
				usersSection.showStatus(userKeyStatusList);
				urlsSection.showResults(urlResults);
				versionsSection.showResults(versionResults);
				loginsSection.showResults(loginResults);
			});
		} catch (final Exception e) {
			e.printStackTrace();
			raiseStatus(StatusLevel.ERROR);
		}
	}

	/**
	 * Reads all versions of the server (in the calling thread).
	 */
	List<VersionResult> runVersionCheck() {
		try (VersionCheckRun versionCheckRun = new VersionCheckRun(server, httpAccess, oidcClient, jenkinsClient)) {
			return versionCheckRun.run();
		}
	}

	/**
	 * Checks all logins of the server (in the calling thread).
	 */
	List<LoginCheckResult> runLoginCheck() {
		return loginChecker.check(server.getLogins());
	}

	/**
	 * The status of the server item is the worst status of all checks (OK &lt; WARNING &lt; ERROR).
	 */
	synchronized void raiseStatus(final StatusLevel level) {
		status = status.max(level);
	}

	ServerManagementGui getGui() {
		return serverManagementGui;
	}

	/**
	 * Rebuilds the card from the server configuration, e.g. after a change.
	 */
	void rebuild() {
		removeAll();

		final JPanel contentPanel = new JPanel();
		contentPanel.setOpaque(false);
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.PAGE_AXIS));
		for (final ItemSection section : sections) {
			if (!hiddenSections.contains(section)) {
				section.addTo(contentPanel);
			}
		}

		final Card card = new Card(server.getName(), IconKind.SERVER, 10);
		final JComponent titleLabel = card.getTitleComponent();
		titleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		titleLabel.setToolTipText(LangResources.get("selectVisibleSections"));
		titleLabel.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent event) {
				if (SwingUtilities.isLeftMouseButton(event)) {
					showSectionVisibilityDialog();
				}
			}
		});
		if (StringUtilities.isNotBlank(server.getHost())) {
			final JLabel hostLabel = ModernUi.createMutedLabel(server.getHost());
			hostLabel.setToolTipText(LangResources.get("host"));
			card.addHeaderComponent(hostLabel);
		}
		card.setContent(contentPanel);
		add(card, BorderLayout.CENTER);

		validate();
		repaint();

		updateButtonStatus();
	}

	/**
	 * Shows a dialog with one checkbox per section to select which sections of this card are visible. The card is
	 * rebuilt only if the selection was confirmed and changed.
	 */
	void showSectionVisibilityDialog() {
		final JPanel checkBoxPanel = new JPanel();
		checkBoxPanel.setLayout(new BoxLayout(checkBoxPanel, BoxLayout.PAGE_AXIS));
		final Map<ItemSection, JCheckBox> checkBoxes = new LinkedHashMap<>();
		for (final Map.Entry<ItemSection, String> entry : sectionNameKeys.entrySet()) {
			final JCheckBox checkBox = new JCheckBox(LangResources.get(entry.getValue()), !hiddenSections.contains(entry.getKey()));
			checkBoxes.put(entry.getKey(), checkBox);
			checkBoxPanel.add(checkBox);
		}

		final int result = JOptionPane.showConfirmDialog(serverManagementGui, checkBoxPanel, server.getName() + ": " + LangResources.get("selectVisibleSections"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (result != JOptionPane.OK_OPTION) {
			return;
		}

		final Set<ItemSection> newHiddenSections = new HashSet<>();
		for (final Map.Entry<ItemSection, JCheckBox> entry : checkBoxes.entrySet()) {
			if (!entry.getValue().isSelected()) {
				newHiddenSections.add(entry.getKey());
			}
		}
		if (!newHiddenSections.equals(hiddenSections)) {
			hiddenSections.clear();
			hiddenSections.addAll(newHiddenSections);
			rebuild();
		}
	}

	/**
	 * Visibility of a section in this card; hidden sections are still included in the status check.
	 */
	boolean isSectionVisible(final ItemSection section) {
		return !hiddenSections.contains(section);
	}

	void updateButtonStatus() {
		for (final ItemSection section : sections) {
			section.updateButtonStatus();
		}
	}

	void signalUnsavedChanges() {
		serverManagementGui.signalUnsavedChanges();
	}

	void showError(final Exception e) {
		new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
	}

	Admin getSshAdminChecked() throws Exception {
		if (server.getSshAdmin() == null || StringUtilities.isBlank(server.getSshAdmin().getUsername())) {
			throw new Exception("No SSH admin configured for server '" + server.getName() + "'");
		}
		return server.getSshAdmin();
	}

	/**
	 * Runs a table update in the event dispatch thread. From another thread the update is queued and not waited for, so
	 * a status check can never block on an event dispatch thread waiting for the check; the updates are executed in the
	 * order they were queued, and the status itself is determined by the checking thread.
	 */
	private static void runInEventDispatchThread(final Runnable runnable) {
		if (EventQueue.isDispatchThread()) {
			runnable.run();
		} else {
			EventQueue.invokeLater(runnable);
		}
	}
}
