package de.soderer.servermanagement;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.text.MessageFormat;
import java.util.Locale;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import de.soderer.utilities.LangResources;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.swing.ModalDialog;
import de.soderer.utilities.swing.QuestionDialog;
import de.soderer.utilities.swing.SwingColor;

public class AdminDialog extends ModalDialog<Integer> {
	private static final long serialVersionUID = 2020023796052296409L;

	private final String text;

	private JPanel panel;
	private JButton okButton;

	private String username = null;
	private String privateKeyFilePath = null;
	private char[] password = null;

	private JTextField userField;
	private JTextField privateKeyFilePathField;
	private JTextField passwordField;

	public AdminDialog(final Window parent, final String title, final String text) {
		super(parent, title);

		this.text = text;

		setResizable(false);

		createComponents(this);

		returnValue = 1;
	}

	private void createComponents(@SuppressWarnings("unused") final AdminDialog adminDialog) {
		panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));

		panel.add(Box.createRigidArea(new Dimension(0, 5)));

		if (Utilities.isNotBlank(text)) {
			final JTextArea textField = new JTextArea();
			textField.setText(text);
			textField.setEditable(false);
			textField.setBackground(Color.LIGHT_GRAY);

			final JScrollPane textScrollpane = new JScrollPane(textField);
			textScrollpane.setPreferredSize(new Dimension(textField.getPreferredSize().width + 5, textField.getPreferredSize().height + 5));

			// Text Panel
			final JPanel textPanel = new JPanel();
			textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.LINE_AXIS));

			textPanel.add(Box.createRigidArea(new Dimension(5, 0)));
			textPanel.add(textScrollpane);
			textPanel.add(Box.createRigidArea(new Dimension(5, 0)));
			panel.add(textPanel);
		}

		// User Panel
		final JPanel userPanel = new JPanel();
		userPanel.setLayout(new FlowLayout(FlowLayout.RIGHT));
		final JLabel userLabel = new JLabel(getI18NString("username"));
		userPanel.add(userLabel);
		userField = new JTextField();
		userField.setPreferredSize(new Dimension(400, userField.getPreferredSize().height));
		userField.setText("");
		username = "";
		userField.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(final KeyEvent event) {
				username = userField.getText();
				checkButtonStatus();
			}
		});
		userPanel.add(userField);
		panel.add(userPanel);

		// Private key filepath Panel
		final JPanel privateKeyFilePathPanel = new JPanel();
		privateKeyFilePathPanel.setLayout(new FlowLayout(FlowLayout.RIGHT));
		final JLabel trustStoreFilePathLabel = new JLabel(LangResources.get("privateKeyFilePath"));
		privateKeyFilePathPanel.add(trustStoreFilePathLabel);
		privateKeyFilePathField = new JTextField();
		privateKeyFilePathField.setToolTipText(LangResources.get("trustStoreFile_help"));
		privateKeyFilePathField.setPreferredSize(new Dimension(375, privateKeyFilePathField.getPreferredSize().height));
		privateKeyFilePathField.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(final KeyEvent event) {
				privateKeyFilePath = privateKeyFilePathField.getText();

				privateKeyFilePath = new File(privateKeyFilePath).getAbsolutePath();
				if (privateKeyFilePath.startsWith(System.getProperty("user.home"))) {
					privateKeyFilePath = privateKeyFilePath.replace(System.getProperty("user.home"), "$HOME");
					privateKeyFilePathField.setText(privateKeyFilePath);
				}

				checkButtonStatus();
			}
		});
		privateKeyFilePathPanel.add(privateKeyFilePathField);

		final JButton privateKeyFileButton = new JButton("...");
		privateKeyFileButton.setPreferredSize(new Dimension(20, privateKeyFilePathField.getPreferredSize().height));
		privateKeyFileButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				try {
					final File trustStoreFile = selectFile(privateKeyFilePathField.getText(), LangResources.get("trustStoreFile"));
					if (trustStoreFile != null) {
						privateKeyFilePathField.setText(trustStoreFile.getAbsolutePath());
						privateKeyFilePath = privateKeyFilePathField.getText();

						privateKeyFilePath = new File(privateKeyFilePath).getAbsolutePath();
						if (privateKeyFilePath.startsWith(System.getProperty("user.home"))) {
							privateKeyFilePath = privateKeyFilePath.replace(System.getProperty("user.home"), "$HOME");
							privateKeyFilePathField.setText(privateKeyFilePath);
						}

						checkButtonStatus();
					}
				} catch (final Exception e) {
					new QuestionDialog((ServerManagementGui) getParent(), ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}
			}
		});
		privateKeyFilePathPanel.add(privateKeyFileButton);
		panel.add(privateKeyFilePathPanel);

		// Password Panel
		final JPanel passwordPanel = new JPanel();
		passwordPanel.setLayout(new FlowLayout(FlowLayout.RIGHT));
		final JLabel passwordLabel = new JLabel(getI18NString("password"));
		passwordPanel.add(passwordLabel);
		passwordField = new JPasswordField();
		passwordField.setPreferredSize(new Dimension(400, passwordField.getPreferredSize().height));
		passwordField.setText("");
		password = "".toCharArray();
		passwordField.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(final KeyEvent event) {
				password = passwordField.getText().toCharArray();
				checkButtonStatus();
			}
		});
		passwordPanel.add(passwordField);
		panel.add(passwordPanel);

		// Button Panel
		final JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));

		// ok Button
		okButton = new JButton(getI18NString("ok"));
		okButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				returnValue = 0;
				dispose();
			}
		});
		buttonPanel.add(okButton);

		buttonPanel.add(Box.createRigidArea(new Dimension(5, 0)));

		// Close Button
		final JButton closeButton = new JButton(getI18NString("cancel"));
		closeButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				returnValue = 1;
				dispose();
			}
		});
		buttonPanel.add(closeButton);

		panel.add(Box.createRigidArea(new Dimension(5, 0)));
		panel.add(buttonPanel);
		getRootPane().setDefaultButton(okButton);

		panel.add(Box.createRigidArea(new Dimension(0, 5)));

		add(panel);

		pack();

		setLocationRelativeTo(getOwner());

		if (userField != null) {
			userField.requestFocus();
		} else if (passwordField != null) {
			passwordField.requestFocus();
		}

		checkButtonStatus();
	}

	private void checkButtonStatus() {
		okButton.setEnabled(Utilities.isNotEmpty(username) && (Utilities.isNotEmpty(privateKeyFilePath) || Utilities.isNotEmpty(password)));
	}

	public String getUsername() {
		return username;
	}

	public char[] getPassword() {
		return password;
	}

	public String getPrivateKeyFilePath() {
		return privateKeyFilePath;
	}

	public AdminDialog setUsername(final String username) {
		this.username = username;
		userField.setText(username);
		return this;
	}

	public AdminDialog setPassword(final char[] password) {
		this.password = password;
		passwordField.setText(new String(password));
		return this;
	}

	public AdminDialog setPrivateKeyFilePath(final String privateKeyFilePath) {
		this.privateKeyFilePath = privateKeyFilePath;
		privateKeyFilePathField.setText(privateKeyFilePath);
		return this;
	}

	private static String getI18NString(final String resourceKey, final Object... arguments) {
		if (LangResources.existsKey(resourceKey)) {
			return LangResources.get(resourceKey, arguments);
		} else if ("de".equalsIgnoreCase(Locale.getDefault().getLanguage())) {
			String pattern;
			switch(resourceKey) {
				case "username": pattern = "Username"; break;
				case "password": pattern = "Passwort"; break;
				case "privateKeyFilePath": pattern = "Private KeyFile"; break;
				case "ok": pattern = "OK"; break;
				case "cancel": pattern = "Abbrechen"; break;
				default: pattern = "MessageKey unbekannt: " + resourceKey + (arguments != null && arguments.length > 0 ? " Argumente: " + Utilities.join(arguments, ", ") : "");
			}
			return MessageFormat.format(pattern, arguments);
		} else {
			String pattern;
			switch(resourceKey) {
				case "username": pattern = "Username"; break;
				case "password": pattern = "Password"; break;
				case "privateKeyFilePath": pattern = "Private KeyFile"; break;
				case "ok": pattern = "OK"; break;
				case "cancel": pattern = "Cancel"; break;
				default: pattern = "MessageKey unknown: " + resourceKey + (arguments != null && arguments.length > 0 ? " arguments: " + Utilities.join(arguments, ", ") : "");
			}
			return MessageFormat.format(pattern, arguments);
		}
	}

	private File selectFile(String basePath, final String selectFileDialogText) {
		if (Utilities.isBlank(basePath)) {
			basePath = System.getProperty("user.home");
		} else if (basePath.contains(File.separator)) {
			basePath = basePath.substring(0, basePath.lastIndexOf(File.separator));
		}

		final JFileChooser fileChooser = new JFileChooser(basePath);
		fileChooser.setDialogTitle(ServerManagement.APPLICATION_NAME + " " + selectFileDialogText);
		if (JFileChooser.APPROVE_OPTION == fileChooser.showOpenDialog(this)) {
			return fileChooser.getSelectedFile();
		} else {
			return null;
		}
	}
}
