package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.File;
import java.text.MessageFormat;
import java.util.Locale;

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.ButtonStyle;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.ModalDialog;
import de.soderer.swing.modern.ModernUi;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Utilities;

public class AdminDialog extends ModalDialog<Integer> {
	private static final long serialVersionUID = 2020023796052296409L;

	/** Preferred width of the input fields */
	private static final int FIELD_WIDTH = 360;

	/** Width and height of the small buttons next to input fields */
	private static final int FIELD_BUTTON_SIZE = 30;

	private final String text;

	private Button okButton;

	private String username = null;
	private String privateKeyFilePath = null;
	private char[] password = null;

	private JTextField userField;
	private JTextField privateKeyFilePathField;
	private JTextField passwordField;

	public AdminDialog(final Window parent, final String title, final String text) {
		super(parent, title, IconKind.LOCK);

		this.text = text;

		setResizable(false);

		createComponents();

		returnValue = 1;
	}

	private void createComponents() {
		if (StringUtilities.isNotBlank(text)) {
			final JTextArea textArea = new JTextArea(text);
			textArea.setEditable(false);
			// Not focusable: it would otherwise get the initial focus and swallow the Enter key
			textArea.setFocusable(false);
			textArea.setOpaque(false);
			textArea.setBorder(null);
			textArea.setFont(ModernUi.baseFont());
			textArea.setForeground(ModernUi.TEXT);
			getContentArea().add(textArea, BorderLayout.NORTH);
		}

		final JPanel formPanel = new JPanel(new GridBagLayout());
		formPanel.setOpaque(false);

		// User
		userField = new JTextField();
		userField.setText("");
		username = "";
		// DocumentListener instead of KeyListener, so pasting with the mouse and setText() are also recognized
		userField.getDocument().addDocumentListener(new TextChangeListener(() -> {
			username = userField.getText();
			checkButtonStatus();
		}));
		addRow(formPanel, 0, getI18NString("username"), userField, null);

		// Private key file path
		privateKeyFilePathField = new JTextField();
		privateKeyFilePathField.setToolTipText(LangResources.get("privateKeyFilePath_help"));
		// Before, every key stroke converted the text into an absolute path, so an emptied field became the current
		// working directory (and the key path could never be removed) and a relative path jumped while typing
		privateKeyFilePathField.getDocument().addDocumentListener(new TextChangeListener(() -> {
			privateKeyFilePath = StringUtilities.isBlank(privateKeyFilePathField.getText()) ? null : privateKeyFilePathField.getText().trim();
			checkButtonStatus();
		}));

		final Button privateKeyFileButton = new Button(null, IconKind.FOLDER, ButtonStyle.SECONDARY);
		privateKeyFileButton.setPreferredSize(new Dimension(FIELD_BUTTON_SIZE, FIELD_BUTTON_SIZE));
		privateKeyFileButton.setToolTipText(getI18NString("privateKeyFilePath"));
		privateKeyFileButton.addActionListener(event -> {
			try {
				final File privateKeyFile = selectFile(Utilities.replaceUsersHome(privateKeyFilePathField.getText()), LangResources.get("privateKeyFilePath"));
				if (privateKeyFile != null) {
					String selectedPath = privateKeyFile.getAbsolutePath();
					if (selectedPath.startsWith(System.getProperty("user.home"))) {
						selectedPath = "$HOME" + selectedPath.substring(System.getProperty("user.home").length());
					}
					// The DocumentListener updates privateKeyFilePath and the button status
					privateKeyFilePathField.setText(selectedPath);
				}
			} catch (final Exception e) {
				ServerManagementGui.showError(this, e);
			}
		});
		addRow(formPanel, 1, getI18NString("privateKeyFilePath"), privateKeyFilePathField, privateKeyFileButton);

		// Password
		passwordField = new JPasswordField();
		passwordField.setText("");
		password = "".toCharArray();
		passwordField.getDocument().addDocumentListener(new TextChangeListener(() -> {
			password = passwordField.getText().toCharArray();
			checkButtonStatus();
		}));
		addRow(formPanel, 2, getI18NString("password"), passwordField, null);

		getContentArea().add(formPanel, BorderLayout.CENTER);

		okButton = addButton(getI18NString("ok"), null, ButtonStyle.PRIMARY, event -> {
			returnValue = 0;
			dispose();
		});

		addButton(getI18NString("cancel"), null, ButtonStyle.SECONDARY, event -> {
			returnValue = 1;
			dispose();
		});

		getRootPane().setDefaultButton(okButton);

		pack();

		setLocationRelativeTo(getOwner());

		if (userField != null) {
			userField.requestFocus();
		} else if (passwordField != null) {
			passwordField.requestFocus();
		}

		checkButtonStatus();
	}

	/**
	 * Adds a form row: muted label, input field and an optional button.
	 */
	private static void addRow(final JPanel formPanel, final int row, final String labelText, final JTextField field, final JComponent trailingComponent) {
		final GridBagConstraints labelConstraints = new GridBagConstraints();
		labelConstraints.gridx = 0;
		labelConstraints.gridy = row;
		labelConstraints.anchor = GridBagConstraints.LINE_START;
		labelConstraints.insets = new Insets(row == 0 ? 0 : 8, 0, 0, 14);
		formPanel.add(ModernUi.createMutedLabel(labelText), labelConstraints);

		ModernUi.styleTextField(field, false);
		field.setPreferredSize(new Dimension(FIELD_WIDTH, field.getPreferredSize().height));
		final GridBagConstraints fieldConstraints = new GridBagConstraints();
		fieldConstraints.gridx = 1;
		fieldConstraints.gridy = row;
		fieldConstraints.gridwidth = trailingComponent == null ? 2 : 1;
		fieldConstraints.weightx = 1;
		fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
		fieldConstraints.insets = new Insets(row == 0 ? 0 : 8, 0, 0, 0);
		formPanel.add(field, fieldConstraints);

		if (trailingComponent != null) {
			// The field shares the row with the button, so the overall width stays the same
			field.setPreferredSize(new Dimension(FIELD_WIDTH - FIELD_BUTTON_SIZE - 6, field.getPreferredSize().height));
			final GridBagConstraints buttonConstraints = new GridBagConstraints();
			buttonConstraints.gridx = 2;
			buttonConstraints.gridy = row;
			buttonConstraints.insets = new Insets(row == 0 ? 0 : 8, 6, 0, 0);
			formPanel.add(trailingComponent, buttonConstraints);
		}
	}

	private void checkButtonStatus() {
		okButton.setEnabled(StringUtilities.isNotEmpty(username) && (StringUtilities.isNotEmpty(privateKeyFilePath) || StringUtilities.isNotEmpty(password)));
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
		// Before, the OK button stayed disabled for an existing admin until a key was typed
		checkButtonStatus();
		return this;
	}

	public AdminDialog setPassword(final char[] password) {
		this.password = password;
		passwordField.setText(password == null ? "" : new String(password));
		checkButtonStatus();
		return this;
	}

	public AdminDialog setPrivateKeyFilePath(final String privateKeyFilePath) {
		this.privateKeyFilePath = privateKeyFilePath;
		privateKeyFilePathField.setText(privateKeyFilePath);
		checkButtonStatus();
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
				default: pattern = "MessageKey unbekannt: " + resourceKey + (arguments != null && arguments.length > 0 ? " Argumente: " + StringUtilities.join(arguments, ", ") : "");
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
				default: pattern = "MessageKey unknown: " + resourceKey + (arguments != null && arguments.length > 0 ? " arguments: " + StringUtilities.join(arguments, ", ") : "");
			}
			return MessageFormat.format(pattern, arguments);
		}
	}

	private File selectFile(String basePath, final String selectFileDialogText) {
		if (StringUtilities.isBlank(basePath)) {
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

	/**
	 * Calls the given action for every change of a text field document.
	 */
	private static class TextChangeListener implements DocumentListener {
		private final Runnable action;

		TextChangeListener(final Runnable action) {
			this.action = action;
		}

		@Override
		public void insertUpdate(final DocumentEvent event) {
			action.run();
		}

		@Override
		public void removeUpdate(final DocumentEvent event) {
			action.run();
		}

		@Override
		public void changedUpdate(final DocumentEvent event) {
			action.run();
		}
	}
}
