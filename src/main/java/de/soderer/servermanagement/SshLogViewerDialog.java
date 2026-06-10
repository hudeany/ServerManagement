package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import de.soderer.sshkeyformats.SshKey;
import de.soderer.sshkeyformats.SshKeyReader;
import de.soderer.sshkeyformats.SshKeyWriter;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Utilities;

public class SshLogViewerDialog extends JFrame {
	private static final long serialVersionUID = 6355639543655800660L;

	private final String host;
	private final int port;
	private final String username;
	private final File privateKeyFile;
	private final char[] password;
	private final String logFilePath;

	private JTextPane logTextPane;
	private StyledDocument logDoc;
	private JLabel statusLabel;
	private JButton connectButton;
	private JCheckBox scrollLockCheckBox;
	private JCheckBox lineWrapCheckBox;
	private JScrollPane logScrollPane;

	private static final Color C_BACKGROUND = Color.WHITE;
	private static final Color C_TEXT = Color.BLACK;
	private static final Color C_ERROR = new Color(180, 20, 20);
	private static final Color C_WARN = new Color(160, 100, 0);
	private static final Color C_INFO = new Color(0, 120, 60);
	private static final Color C_TIMESTAMP = new Color(100, 100, 130);
	private static final Color C_STATUS_OK = new Color(0, 140, 60);
	private static final Color C_STATUS_ERR = new Color(180, 20, 20);
	private static final Color C_TOOLBAR_BG = new Color(240, 240, 240);
	private static final Color C_BORDER = new Color(200, 200, 200);
	private static final Color C_FIELD_BG = new Color(248, 248, 248);
	private static final Color C_SEARCH_HIGHLIGHT = new Color(255, 220, 80);
	private static final Color C_SEARCH_HIGHLIGHT_ACTIVE = new Color(255, 140, 0);

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean scrollLocked = new AtomicBoolean(false);
	private final AtomicBoolean lineWrap = new AtomicBoolean(true);
	private Thread tailThread;
	private Session jschSession;

	private JTextField searchField;
	private JCheckBox caseSensitiveCheckBox;
	private JCheckBox regexCheckBox;
	private JCheckBox filterModeCheckBox;
	private JLabel searchResultLabel;
	private final List<int[]> searchMatches = new ArrayList<>();
	private int searchMatchIndex = -1;
	private String lastSearchTerm = "";
	private boolean lastCaseSensitive = false;
	private boolean lastRegex = false;
	private int lastDocLength = -1;
	private final List<String> rawLines = new ArrayList<>();
	private final AtomicBoolean filterMode = new AtomicBoolean(false);

	/**
	 * @param parent
	 */
	public SshLogViewerDialog(final Window parent, final String title, final String host, final int port, final String username, final File privateKeyFile, final char[] password, final String logFilePath) {
		super(LangResources.get("sshLogViewer.title") + " (" + title + ")");

		this.host = host;
		this.port = port;
		this.username = username;
		this.privateKeyFile = privateKeyFile;
		this.password = password;
		this.logFilePath = logFilePath;
	}

	public void open() throws Exception {
		if (host == null || host.isEmpty()) {
			throw new Exception("No host configured");
		}
		if (logFilePath == null || logFilePath.isEmpty()) {
			throw new Exception("No log file path configured");
		}

		checkRemoteFileExists();

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setSize(1000, 700);
		setResizable(true);

		buildUI();
		centerOnParent();

		setVisible(true);

		if (host != null && !host.isEmpty() && logFilePath != null && !logFilePath.isEmpty()) {
			startTailing();
		}

		addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosed(final java.awt.event.WindowEvent e) {
				cleanup();
			}
		});
	}

	private void buildUI() {
		getContentPane().setBackground(C_BACKGROUND);
		getContentPane().setLayout(new BorderLayout(0, 0));

		getContentPane().add(buildToolbar(), BorderLayout.NORTH);
		getContentPane().add(buildLogArea(), BorderLayout.CENTER);
		getContentPane().add(buildStatusBar(), BorderLayout.SOUTH);
	}

	private JPanel buildToolbar() {
		final JPanel outer = new JPanel(new BorderLayout(0, 0));
		outer.setBackground(C_TOOLBAR_BG);
		outer.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));
		outer.add(buildControlRow(), BorderLayout.NORTH);
		outer.add(buildSearchRow(), BorderLayout.SOUTH);
		return outer;
	}

	private JPanel buildControlRow() {
		final JPanel bar = new JPanel(new GridBagLayout());
		bar.setBackground(C_TOOLBAR_BG);
		bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER), BorderFactory.createEmptyBorder(6, 10, 6, 10)));

		final GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(0, 4, 0, 4);
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.WEST;
		c.gridy = 0;

		final JLabel fileLabel = new JLabel(LangResources.get("sshLogViewer.logFile"));
		fileLabel.setForeground(new Color(80, 80, 80));
		fileLabel.setFont(uiFont(11, Font.PLAIN));
		c.gridx = 0;
		c.weightx = 0;
		bar.add(fileLabel, c);

		final String displayText = buildConnectionDisplay();
		final JTextField infoField = new JTextField(displayText);
		infoField.setEditable(false);
		infoField.setBackground(C_FIELD_BG);
		infoField.setForeground(C_TEXT);
		infoField.setFont(uiFont(11, Font.PLAIN));
		infoField.setCaretPosition(0);
		infoField.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(C_BORDER), BorderFactory.createEmptyBorder(2, 6, 2, 6)));
		c.gridx = 1;
		c.weightx = 1;
		bar.add(infoField, c);

		connectButton = new JButton(LangResources.get("sshLogViewer.disconnect"));
		styleButton(connectButton);
		connectButton.setPreferredSize(new Dimension(90, connectButton.getPreferredSize().height));
		connectButton.addActionListener(e -> {
			if (running.get()) {
				stopTailing();
			} else {
				startTailing();
			}
		});
		c.gridx = 2;
		c.weightx = 0;
		bar.add(connectButton, c);

		final JButton clearButton = new JButton(LangResources.get("sshLogViewer.clear"));
		styleButton(clearButton);
		clearButton.addActionListener(e -> {
			rawLines.clear();
			try {
				logDoc.remove(0, logDoc.getLength());
			} catch (@SuppressWarnings("unused") final BadLocationException ex) {
				// do nothing
			}
		});
		c.gridx = 3;
		c.weightx = 0;
		bar.add(clearButton, c);

		scrollLockCheckBox = new JCheckBox(LangResources.get("sshLogViewer.scrollLock"));
		scrollLockCheckBox.setBackground(C_TOOLBAR_BG);
		scrollLockCheckBox.setForeground(new Color(60, 60, 60));
		scrollLockCheckBox.setFont(uiFont(11, Font.PLAIN));
		scrollLockCheckBox.setFocusPainted(false);
		scrollLockCheckBox.addItemListener(e -> {
			final boolean locked = e.getStateChange() == java.awt.event.ItemEvent.SELECTED;
			scrollLocked.set(locked);
			final javax.swing.text.DefaultCaret caret = (javax.swing.text.DefaultCaret) logTextPane.getCaret();
			caret.setUpdatePolicy(locked ? javax.swing.text.DefaultCaret.NEVER_UPDATE : javax.swing.text.DefaultCaret.ALWAYS_UPDATE);
		});
		c.gridx = 4;
		c.weightx = 0;
		bar.add(scrollLockCheckBox, c);

		lineWrapCheckBox = new JCheckBox(LangResources.get("sshLogViewer.lineWrap"));
		lineWrapCheckBox.setSelected(true);
		lineWrapCheckBox.setBackground(C_TOOLBAR_BG);
		lineWrapCheckBox.setForeground(new Color(60, 60, 60));
		lineWrapCheckBox.setFont(uiFont(11, Font.PLAIN));
		lineWrapCheckBox.setFocusPainted(false);
		lineWrapCheckBox.addItemListener(e -> {
			lineWrap.set(e.getStateChange() == java.awt.event.ItemEvent.SELECTED);
			applyLineWrap();
		});
		c.gridx = 5;
		c.weightx = 0;
		bar.add(lineWrapCheckBox, c);

		return bar;
	}

	private JPanel buildSearchRow() {
		final JPanel bar = new JPanel(new GridBagLayout());
		bar.setBackground(C_TOOLBAR_BG);
		bar.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

		final GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(0, 4, 0, 4);
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.WEST;
		c.gridy = 0;

		final JLabel searchLabel = new JLabel(LangResources.get("sshLogViewer.search"));
		searchLabel.setForeground(new Color(80, 80, 80));
		searchLabel.setFont(uiFont(11, Font.PLAIN));
		c.gridx = 0;
		c.weightx = 0;
		bar.add(searchLabel, c);

		searchField = new JTextField();
		searchField.setBackground(C_FIELD_BG);
		searchField.setForeground(C_TEXT);
		searchField.setFont(uiFont(11, Font.PLAIN));
		searchField.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(C_BORDER), BorderFactory.createEmptyBorder(2, 6, 2, 6)));
		searchField.addActionListener(e -> searchNext());
		c.gridx = 1;
		c.weightx = 1;
		bar.add(searchField, c);

		caseSensitiveCheckBox = new JCheckBox(LangResources.get("sshLogViewer.search.caseSensitive"));
		caseSensitiveCheckBox.setBackground(C_TOOLBAR_BG);
		caseSensitiveCheckBox.setForeground(new Color(60, 60, 60));
		caseSensitiveCheckBox.setFont(uiFont(11, Font.PLAIN));
		caseSensitiveCheckBox.setFocusPainted(false);
		c.gridx = 2;
		c.weightx = 0;
		bar.add(caseSensitiveCheckBox, c);

		regexCheckBox = new JCheckBox(LangResources.get("sshLogViewer.search.regex"));
		regexCheckBox.setBackground(C_TOOLBAR_BG);
		regexCheckBox.setForeground(new Color(60, 60, 60));
		regexCheckBox.setFont(uiFont(11, Font.PLAIN));
		regexCheckBox.setFocusPainted(false);
		c.gridx = 3;
		c.weightx = 0;
		bar.add(regexCheckBox, c);

		filterModeCheckBox = new JCheckBox(LangResources.get("sshLogViewer.search.filterMode"));
		filterModeCheckBox.setBackground(C_TOOLBAR_BG);
		filterModeCheckBox.setForeground(new Color(60, 60, 60));
		filterModeCheckBox.setFont(uiFont(11, Font.PLAIN));
		filterModeCheckBox.setFocusPainted(false);
		filterModeCheckBox.addItemListener(e -> {
			filterMode.set(e.getStateChange() == java.awt.event.ItemEvent.SELECTED);
			rebuildDocumentFromRawLines();
			invalidateSearch();
		});
		c.gridx = 4;
		c.weightx = 0;
		bar.add(filterModeCheckBox, c);

		final JButton searchUpButton = new JButton(LangResources.get("sshLogViewer.search.up"));
		styleButton(searchUpButton);
		searchUpButton.addActionListener(e -> searchPrev());
		c.gridx = 5;
		c.weightx = 0;
		bar.add(searchUpButton, c);

		final JButton searchDownButton = new JButton(LangResources.get("sshLogViewer.search.down"));
		styleButton(searchDownButton);
		searchDownButton.addActionListener(e -> searchNext());
		c.gridx = 6;
		c.weightx = 0;
		bar.add(searchDownButton, c);

		searchResultLabel = new JLabel();
		searchResultLabel.setFont(uiFont(11, Font.PLAIN));
		searchResultLabel.setForeground(new Color(100, 100, 100));
		c.gridx = 7;
		c.weightx = 0;
		bar.add(searchResultLabel, c);

		return bar;
	}

	private String buildConnectionDisplay() {
		final String h = (host != null && !host.isEmpty()) ? host : "–";
		final String p = String.valueOf(port);
		final String f = (logFilePath != null && !logFilePath.isEmpty()) ? logFilePath : "–";
		return "ssh://" + username + "@" + h + ":" + p + ":" + f;
	}

	private JScrollPane buildLogArea() {
		logTextPane = new JTextPane();
		logTextPane.setBackground(C_BACKGROUND);
		logTextPane.setForeground(C_TEXT);
		logTextPane.setEditable(false);
		logTextPane.setFont(monospaceFont(12));
		logTextPane.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		logDoc = logTextPane.getStyledDocument();

		final JPopupMenu ctx = new JPopupMenu();
		final JMenuItem copyItem = new JMenuItem(LangResources.get("sshLogViewer.copy"));
		copyItem.addActionListener(e -> logTextPane.copy());
		ctx.add(copyItem);
		final JMenuItem selectAll = new JMenuItem(LangResources.get("sshLogViewer.selectAll"));
		selectAll.addActionListener(e -> logTextPane.selectAll());
		ctx.add(selectAll);
		logTextPane.setComponentPopupMenu(ctx);

		logScrollPane = new JScrollPane(logTextPane);
		logScrollPane.setBorder(BorderFactory.createEmptyBorder());
		return logScrollPane;
	}

	private JPanel buildStatusBar() {
		final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
		bar.setBackground(C_TOOLBAR_BG);
		bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));

		statusLabel = new JLabel(LangResources.get("sshLogViewer.status.disconnected"));
		statusLabel.setForeground(C_STATUS_ERR);
		statusLabel.setFont(uiFont(11, Font.PLAIN));
		bar.add(statusLabel);
		return bar;
	}

	private void startTailing() {
		running.set(true);
		updateConnectButton(true);
		setStatus(LangResources.get("sshLogViewer.status.connecting") + " " + host + ":" + port + " …", false);
		SwingUtilities.invokeLater(() -> {
			rawLines.clear();
			try {
				logDoc.remove(0, logDoc.getLength());
			} catch (@SuppressWarnings("unused") final BadLocationException ex) {
				// do nothing
			}
		});

		tailThread = new Thread(() -> {
			ChannelExec channel = null;
			try {
				if (jschSession == null || !jschSession.isConnected()) {
					createJschSession();
				}

				channel = (ChannelExec) jschSession.openChannel("exec");
				channel.setCommand("tail -n 500 -F " + logFilePath);
				channel.connect();

				setStatus(LangResources.get("sshLogViewer.status.connected") + " " + logFilePath, true);

				try (InputStream is = channel.getInputStream();
						BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

					String line;
					while (running.get() && (line = reader.readLine()) != null) {
						final String finalLine = line;
						SwingUtilities.invokeLater(() -> appendLine(finalLine));
					}
				}
			} catch (final Exception ex) {
				if (running.get()) {
					SwingUtilities.invokeLater(() -> {
						setStatus(LangResources.get("sshLogViewer.status.error") + " " + ex.getMessage(), false);
						appendLine("[ERROR] " + ex.getMessage());
					});
				}
			} finally {
				if (channel != null && channel.isConnected()) {
					channel.disconnect();
				}
				SwingUtilities.invokeLater(() -> {
					running.set(false);
					updateConnectButton(false);
					if (statusLabel.getForeground().equals(C_STATUS_OK)) {
						setStatus(LangResources.get("sshLogViewer.status.disconnected"), false);
					}
				});
			}
		}, "ssh-tail-thread");
		tailThread.setDaemon(true);
		tailThread.start();
	}

	private void createJschSession() throws JSchException, Exception, IOException, FileNotFoundException {
		final JSch jsch = new JSch();
		jschSession = jsch.getSession(username, host, port);

		byte[] keyFileData = null;
		if (privateKeyFile != null) {
			try (InputStream inputStream = new FileInputStream(privateKeyFile)) {
				final SshKey sshKey;
				if (Utilities.isNotBlank(password)) {
					sshKey = SshKeyReader.readKey(inputStream, password);
				} else {
					sshKey = SshKeyReader.readKey(inputStream, null);
				}
				final ByteArrayOutputStream keyDataStream = new ByteArrayOutputStream();
				SshKeyWriter.writePuttyVersion2Key(keyDataStream, sshKey, null);
				keyFileData = keyDataStream.toByteArray();
			}
		}

		if (keyFileData != null) {
			jsch.addIdentity(username, keyFileData, null, password == null ? new byte[0] : new String(password).getBytes(StandardCharsets.UTF_8));
		} else if (password != null) {
			jschSession.setPassword(new String(password));
		} else {
			throw new Exception("At least one of keyFileData or password is expected");
		}

		jschSession.setConfig("StrictHostKeyChecking", "no");
		jschSession.connect(10_000);
	}

	private void stopTailing() {
		running.set(false);
		if (jschSession != null && jschSession.isConnected()) {
			jschSession.disconnect();
		}
		updateConnectButton(false);
		setStatus(LangResources.get("sshLogViewer.status.disconnected"), false);
	}

	private void appendLine(final String line) {
		rawLines.add(line);
		if (filterMode.get() && !lineMatchesFilter(line)) {
			return;
		}
		appendLineToDoc(line);
	}

	private boolean lineMatchesFilter(final String line) {
		final String term = lastSearchTerm;
		if (term.isEmpty()) {
			return true;
		}
		if (lastRegex) {
			try {
				final int flags = lastCaseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
				return Pattern.compile(term, flags).matcher(line).find();
			} catch (@SuppressWarnings("unused") final PatternSyntaxException ex) {
				return true;
			}
		}
		return lastCaseSensitive ? line.contains(term) : line.toLowerCase().contains(term.toLowerCase());
	}

	private void appendLineToDoc(final String line) {
		try {
			final int lineCount = logDoc.getDefaultRootElement().getElementCount();
			if (lineCount > 5000) {
				final int cutEnd = logDoc.getDefaultRootElement().getElement(lineCount - 4000).getStartOffset();
				logDoc.remove(0, cutEnd);
			}

			final String timestamp = "[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] ";

			final SimpleAttributeSet tsAttr = new SimpleAttributeSet();
			StyleConstants.setForeground(tsAttr, C_TIMESTAMP);
			StyleConstants.setBold(tsAttr, false);
			logDoc.insertString(logDoc.getLength(), timestamp, tsAttr);

			final Color lineColor = classifyLine(line);
			final SimpleAttributeSet lineAttr = new SimpleAttributeSet();
			StyleConstants.setForeground(lineAttr, lineColor);
			StyleConstants.setBold(lineAttr, lineColor.equals(C_ERROR));
			logDoc.insertString(logDoc.getLength(), line + "\n", lineAttr);

			if (!scrollLocked.get()) {
				logTextPane.setCaretPosition(logDoc.getLength());
			}
		} catch (@SuppressWarnings("unused") final BadLocationException e) {
			// do nothing
		}
	}

	private void rebuildDocumentFromRawLines() {
		try {
			logDoc.remove(0, logDoc.getLength());
		} catch (@SuppressWarnings("unused") final BadLocationException ex) {
			// do nothing
		}
		for (final String line : rawLines) {
			if (!filterMode.get() || lineMatchesFilter(line)) {
				appendLineToDoc(line);
			}
		}
	}

	private void invalidateSearch() {
		lastDocLength = -1;
	}

	private static Color classifyLine(final String line) {
		final String lower = line.toLowerCase();
		if (lower.contains("error") || lower.contains("fatal") || lower.contains("exception") || lower.contains("critical") || lower.contains("crit")) {
			return C_ERROR;
		}
		if (lower.contains("warn") || lower.contains("warning")) {
			return C_WARN;
		}
		if (lower.contains("info") || lower.contains("notice")) {
			return C_INFO;
		}
		return C_TEXT;
	}

	private void setStatus(final String msg, final boolean ok) {
		SwingUtilities.invokeLater(() -> {
			statusLabel.setText(msg);
			statusLabel.setForeground(ok ? C_STATUS_OK : C_STATUS_ERR);
		});
	}

	private void updateConnectButton(final boolean isConnected) {
		SwingUtilities.invokeLater(() -> connectButton.setText(isConnected ? LangResources.get("sshLogViewer.disconnect") : LangResources.get("sshLogViewer.connect")));
	}

	private void checkRemoteFileExists() throws Exception {
		if (jschSession == null || !jschSession.isConnected()) {
			createJschSession();
		}

		final ChannelExec channel = (ChannelExec) jschSession.openChannel("exec");
		channel.setCommand("test -e " + logFilePath + " && echo exists || echo missing");
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		channel.setOutputStream(out);
		channel.connect();
		while (!channel.isClosed()) {
			Thread.sleep(100);
		}
		channel.disconnect();
		if (!out.toString(StandardCharsets.UTF_8).trim().equals("exists")) {
			throw new Exception("Remote log file not found: " + buildConnectionDisplay());
		}
	}

	private void centerOnParent() {
		final Window owner = getOwner();
		if (owner != null) {
			final Point ownerLoc = owner.getLocation();
			final Dimension ownerSz = owner.getSize();
			final Dimension mySize = getSize();
			setLocation(ownerLoc.x + (ownerSz.width - mySize.width) / 2, ownerLoc.y + (ownerSz.height - mySize.height) / 2);
		} else {
			setLocationRelativeTo(null);
		}
	}

	private void runSearch() {
		final String term = searchField.getText();
		final boolean caseSensitive = caseSensitiveCheckBox.isSelected();
		final boolean regex = regexCheckBox.isSelected();

		final int currentDocLength = logDoc.getLength();
		final boolean paramsChanged = !term.equals(lastSearchTerm) || caseSensitive != lastCaseSensitive || regex != lastRegex || currentDocLength != lastDocLength;
		if (!paramsChanged && !searchMatches.isEmpty()) {
			return;
		}

		lastSearchTerm = term;
		lastCaseSensitive = caseSensitive;
		lastRegex = regex;
		lastDocLength = currentDocLength;

		final int previousMatchOffset = (searchMatchIndex >= 0 && searchMatchIndex < searchMatches.size())
				? searchMatches.get(searchMatchIndex)[0]
				: -1;

		final javax.swing.text.Highlighter highlighter = logTextPane.getHighlighter();
		highlighter.removeAllHighlights();
		searchMatches.clear();
		searchMatchIndex = -1;

		if (term.isEmpty()) {
			searchResultLabel.setText("");
			searchField.setBackground(C_FIELD_BG);
			return;
		}

		try {
			final String docText = logDoc.getText(0, logDoc.getLength());
			final int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
			final Pattern pattern = regex ? Pattern.compile(term, flags) : Pattern.compile(Pattern.quote(term), flags);
			final Matcher matcher = pattern.matcher(docText);

			final javax.swing.text.Highlighter.HighlightPainter normalPainter = new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(C_SEARCH_HIGHLIGHT);
			while (matcher.find()) {
				searchMatches.add(new int[] { matcher.start(), matcher.end() });
				highlighter.addHighlight(matcher.start(), matcher.end(), normalPainter);
			}

			if (!searchMatches.isEmpty() && previousMatchOffset >= 0) {
				int nearest = 0;
				for (int i = 1; i < searchMatches.size(); i++) {
					if (Math.abs(searchMatches.get(i)[0] - previousMatchOffset) < Math.abs(searchMatches.get(nearest)[0] - previousMatchOffset)) {
						nearest = i;
					}
				}
				searchMatchIndex = nearest;
			}

			searchField.setBackground(searchMatches.isEmpty() ? new Color(255, 210, 210) : C_FIELD_BG);
			updateSearchResultLabel();
		} catch (@SuppressWarnings("unused") final PatternSyntaxException ex) {
			searchField.setBackground(new Color(255, 210, 210));
			searchResultLabel.setText(LangResources.get("sshLogViewer.search.invalidRegex"));
		} catch (@SuppressWarnings("unused") final BadLocationException ex) {
			// do nothing
		}
	}

	private void searchNext() {
		runSearch();
		if (searchMatches.isEmpty()) {
			return;
		}
		searchMatchIndex = (searchMatchIndex + 1) % searchMatches.size();
		jumpToMatch(searchMatchIndex);
	}

	private void searchPrev() {
		runSearch();
		if (searchMatches.isEmpty()) {
			return;
		}
		searchMatchIndex = (searchMatchIndex <= 0 ? searchMatches.size() : searchMatchIndex) - 1;
		jumpToMatch(searchMatchIndex);
	}

	private void jumpToMatch(final int index) {
		final int[] match = searchMatches.get(index);
		logTextPane.setCaretPosition(match[0]);
		logTextPane.moveCaretPosition(match[1]);

		try {
			final javax.swing.text.Highlighter highlighter = logTextPane.getHighlighter();
			highlighter.removeAllHighlights();
			final javax.swing.text.Highlighter.HighlightPainter normalPainter = new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(C_SEARCH_HIGHLIGHT);
			final javax.swing.text.Highlighter.HighlightPainter activePainter = new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(C_SEARCH_HIGHLIGHT_ACTIVE);
			for (int i = 0; i < searchMatches.size(); i++) {
				final int[] m = searchMatches.get(i);
				highlighter.addHighlight(m[0], m[1], i == index ? activePainter : normalPainter);
			}
		} catch (@SuppressWarnings("unused") final BadLocationException ex) {
			// do nothing
		}

		SwingUtilities.invokeLater(() -> {
			try {
				final java.awt.Rectangle rect = logTextPane.modelToView2D(match[0]).getBounds();
				logTextPane.scrollRectToVisible(rect);
			} catch (@SuppressWarnings("unused") final BadLocationException ex) {
				// do nothing
			}
		});

		updateSearchResultLabel();
	}

	private void updateSearchResultLabel() {
		if (searchMatches.isEmpty()) {
			searchResultLabel.setText(LangResources.get("sshLogViewer.search.noResults"));
			searchResultLabel.setForeground(C_STATUS_ERR);
		} else {
			searchResultLabel.setText((searchMatchIndex + 1) + " / " + searchMatches.size());
			searchResultLabel.setForeground(new Color(100, 100, 100));
		}
	}

	private void applyLineWrap() {
		if (lineWrap.get()) {
			logScrollPane.setViewportView(logTextPane);
		} else {
			final JPanel noWrapPanel = new JPanel(new BorderLayout());
			noWrapPanel.setBackground(C_BACKGROUND);
			noWrapPanel.add(logTextPane, BorderLayout.CENTER);
			logScrollPane.setViewportView(noWrapPanel);
		}
	}

	private void cleanup() {
		running.set(false);
		if (jschSession != null && jschSession.isConnected()) {
			jschSession.disconnect();
		}
	}

	private static void styleButton(final JButton b) {
		b.setFont(uiFont(11, Font.PLAIN));
		b.setFocusPainted(false);
	}

	private static Font uiFont(final int size, final int style) {
		return new Font("Segoe UI", style, size);
	}

	private static Font monospaceFont(final int size) {
		final String[] candidates = { "Cascadia Code", "JetBrains Mono", "Consolas", "DejaVu Sans Mono", "Monospaced" };
		final java.util.Set<String> availSet = new java.util.HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
		for (final String name : candidates) {
			if (availSet.contains(name)) {
				return new Font(name, Font.PLAIN, size);
			}
		}
		return new Font(Font.MONOSPACED, Font.PLAIN, size);
	}
}