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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import de.soderer.sshkeyformats.SshKey;
import de.soderer.sshkeyformats.SshKeyReader;
import de.soderer.sshkeyformats.SshKeyWriter;
import de.soderer.utilities.Utilities;

public class SshLogViewerDialog2 extends JDialog {

	private static final long serialVersionUID = 1L;

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
	private JToggleButton scrollLockButton;

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

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean scrollLocked = new AtomicBoolean(false);
	private Thread tailThread;
	private Session jschSession;

	public SshLogViewerDialog2(final Window parent, final String title, final String host, final int port,
			final String username, final File privateKeyFile, final char[] password, final String logFilePath) {
		super(parent, "SSH Log Viewer (" + title + ")", ModalityType.MODELESS);
		this.host = host;
		this.port = port;
		this.username = username;
		this.privateKeyFile = privateKeyFile;
		this.password = password;
		this.logFilePath = logFilePath;
	}

	public void open() {
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
		final JPanel bar = new JPanel(new GridBagLayout());
		bar.setBackground(C_TOOLBAR_BG);
		bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
				BorderFactory.createEmptyBorder(6, 10, 6, 10)));

		final GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(0, 4, 0, 4);
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.WEST;
		c.gridy = 0;

		final JLabel fileLabel = new JLabel("Log-Datei:");
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
		infoField.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(C_BORDER),
				BorderFactory.createEmptyBorder(2, 6, 2, 6)));
		c.gridx = 1;
		c.weightx = 1;
		bar.add(infoField, c);

		connectButton = new JButton("Trennen");
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

		final JButton clearButton = new JButton("Leeren");
		styleButton(clearButton);
		clearButton.addActionListener(e -> {
			try {
				logDoc.remove(0, logDoc.getLength());
			} catch (@SuppressWarnings("unused") final BadLocationException ex) {
				// do nothing
			}
		});
		c.gridx = 3;
		c.weightx = 0;
		bar.add(clearButton, c);

		scrollLockButton = new JToggleButton("Scroll-Lock") {
			@Override
			protected void paintComponent(final java.awt.Graphics g) {
				if (isSelected()) {
					g.setColor(new Color(255, 200, 200));
					g.fillRect(0, 0, getWidth(), getHeight());
				} else {
					g.setColor(C_TOOLBAR_BG);
					g.fillRect(0, 0, getWidth(), getHeight());
				}
				super.paintComponent(g);
			}
		};
		scrollLockButton.setContentAreaFilled(false);
		scrollLockButton.setOpaque(false);
		scrollLockButton.setForeground(new Color(60, 60, 60));
		scrollLockButton.setFont(uiFont(11, Font.PLAIN));
		scrollLockButton.setFocusPainted(false);
		scrollLockButton.addItemListener(e -> {
			final boolean locked = e.getStateChange() == java.awt.event.ItemEvent.SELECTED;
			scrollLocked.set(locked);
			scrollLockButton.repaint();
			final javax.swing.text.DefaultCaret caret = (javax.swing.text.DefaultCaret) logTextPane.getCaret();
			caret.setUpdatePolicy(
					locked ? javax.swing.text.DefaultCaret.NEVER_UPDATE : javax.swing.text.DefaultCaret.ALWAYS_UPDATE);
		});
		c.gridx = 4;
		c.weightx = 0;
		bar.add(scrollLockButton, c);

		return bar;
	}

	private String buildConnectionDisplay() {
		final String h = (host != null && !host.isEmpty()) ? host : "–";
		final String p = String.valueOf(port);
		final String f = (logFilePath != null && !logFilePath.isEmpty()) ? logFilePath : "–";
		return username + "@" + h + ":" + p + ":" + f;
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
		final JMenuItem copyItem = new JMenuItem("Kopieren  Strg+C");
		copyItem.addActionListener(e -> logTextPane.copy());
		ctx.add(copyItem);
		final JMenuItem selectAll = new JMenuItem("Alles markieren  Strg+A");
		selectAll.addActionListener(e -> logTextPane.selectAll());
		ctx.add(selectAll);
		logTextPane.setComponentPopupMenu(ctx);

		final JScrollPane scroll = new JScrollPane(logTextPane);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		return scroll;
	}

	private JPanel buildStatusBar() {
		final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
		bar.setBackground(C_TOOLBAR_BG);
		bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));

		statusLabel = new JLabel("● Nicht verbunden");
		statusLabel.setForeground(C_STATUS_ERR);
		statusLabel.setFont(uiFont(11, Font.PLAIN));
		bar.add(statusLabel);
		return bar;
	}

	private void startTailing() {
		running.set(true);
		updateConnectButton(true);
		setStatus("Verbinde mit " + host + ":" + port + " …", false);
		SwingUtilities.invokeLater(() -> {
			try {
				logDoc.remove(0, logDoc.getLength());
			} catch (@SuppressWarnings("unused") final BadLocationException ex) {
				// do nothing
			}
		});

		tailThread = new Thread(() -> {
			ChannelExec channel = null;
			try {
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
					jsch.addIdentity(username, keyFileData, null,
							password == null ? new byte[0] : new String(password).getBytes(StandardCharsets.UTF_8));
				} else if (password != null) {
					jschSession.setPassword(new String(password));
				} else {
					throw new Exception("At least one of keyFileData or password is expected");
				}

				jschSession.setConfig("StrictHostKeyChecking", "no");
				jschSession.connect(10_000);

				channel = (ChannelExec) jschSession.openChannel("exec");
				channel.setCommand("tail -n 500 -F " + logFilePath);
				channel.connect();

				setStatus("● Verbunden – lese " + logFilePath, true);

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
						setStatus("✖ Fehler: " + ex.getMessage(), false);
						appendLine("[ERROR] " + ex.getMessage());
					});
				}
			} finally {
				if (channel != null && channel.isConnected())
					channel.disconnect();
				if (jschSession != null && jschSession.isConnected())
					jschSession.disconnect();
				SwingUtilities.invokeLater(() -> {
					running.set(false);
					updateConnectButton(false);
					if (statusLabel.getForeground().equals(C_STATUS_OK)) {
						setStatus("● Verbindung getrennt", false);
					}
				});
			}
		}, "ssh-tail-thread");
		tailThread.setDaemon(true);
		tailThread.start();
	}

	private void stopTailing() {
		running.set(false);
		if (jschSession != null && jschSession.isConnected()) {
			jschSession.disconnect();
		}
		updateConnectButton(false);
		setStatus("● Verbindung getrennt", false);
	}

	private void appendLine(final String line) {
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

	private static Color classifyLine(final String line) {
		final String lower = line.toLowerCase();
		if (lower.contains("error") || lower.contains("fatal") || lower.contains("exception")
				|| lower.contains("critical") || lower.contains("crit")) {
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
		SwingUtilities.invokeLater(() -> connectButton.setText(isConnected ? "Trennen" : "Verbinden"));
	}

	private void centerOnParent() {
		final Window owner = getOwner();
		if (owner != null) {
			final Point ownerLoc = owner.getLocation();
			final Dimension ownerSz = owner.getSize();
			final Dimension mySize = getSize();
			setLocation(ownerLoc.x + (ownerSz.width - mySize.width) / 2,
					ownerLoc.y + (ownerSz.height - mySize.height) / 2);
		} else {
			setLocationRelativeTo(null);
		}
	}

	private void cleanup() {
		running.set(false);
		if (jschSession != null && jschSession.isConnected())
			jschSession.disconnect();
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
		final java.util.Set<String> availSet = new java.util.HashSet<>(
				Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
		for (final String name : candidates) {
			if (availSet.contains(name)) {
				return new Font(name, Font.PLAIN, size);
			}
		}
		return new Font(Font.MONOSPACED, Font.PLAIN, size);
	}

}