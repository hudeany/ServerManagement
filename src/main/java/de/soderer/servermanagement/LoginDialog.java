package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.Arrays;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import de.soderer.network.HttpUtilities;
import de.soderer.servermanagement.config.Login;
import de.soderer.servermanagement.config.LoginType;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.ButtonStyle;
import de.soderer.swing.modern.IconKind;
import de.soderer.utilities.LangResources;

/**
 * Dialog to add or edit a login: kind of authentication (radio buttons), URL, username, password, optional proxy URL
 * and for OIDC an optional IdP selection.
 */
public class LoginDialog extends JDialog {
	private static final long serialVersionUID = -2716356042391563140L;

	private final JRadioButton classicRadioButton;
	private final JRadioButton oidcRadioButton;
	private final JTextField urlField;
	private final JTextField usernameField;
	private final JPasswordField passwordField;
	private final JTextField idpSelectionField;
	private final JTextField proxyUrlField;
	private final Button okButton;

	private boolean confirmed = false;

	public LoginDialog(final Window parent, final String title) {
		super(parent, title, ModalityType.APPLICATION_MODAL);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		final JPanel formPanel = new JPanel(new GridBagLayout());
		formPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));

		// Kind of authentication
		classicRadioButton = new JRadioButton(LangResources.get("loginType.basicauth"), true);
		oidcRadioButton = new JRadioButton(LangResources.get("loginType.oidc"));
		final ButtonGroup loginTypeGroup = new ButtonGroup();
		loginTypeGroup.add(classicRadioButton);
		loginTypeGroup.add(oidcRadioButton);
		final JPanel loginTypePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		loginTypePanel.add(classicRadioButton);
		loginTypePanel.add(oidcRadioButton);

		urlField = new JTextField(40);
		usernameField = new JTextField(40);
		passwordField = new JPasswordField(40);
		idpSelectionField = new JTextField(40);
		idpSelectionField.setToolTipText(LangResources.get("idpSelection.tooltip"));
		proxyUrlField = new JTextField(40);
		proxyUrlField.setToolTipText(LangResources.get("proxyUrl.tooltip"));

		addRow(formPanel, 0, LangResources.get("loginType"), loginTypePanel);
		addRow(formPanel, 1, LangResources.get("url"), urlField);
		addRow(formPanel, 2, LangResources.get("username"), usernameField);
		addRow(formPanel, 3, LangResources.get("password"), passwordField);
		addRow(formPanel, 4, LangResources.get("proxyUrl"), proxyUrlField);
		addRow(formPanel, 5, LangResources.get("idpSelection"), idpSelectionField);

		// The optional IdP selection is only used for OIDC
		classicRadioButton.addItemListener(e -> updateFieldStatus());
		oidcRadioButton.addItemListener(e -> updateFieldStatus());
		updateFieldStatus();

		// Buttons
		final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttonPanel.setBorder(BorderFactory.createEmptyBorder(8, 16, 16, 16));

		okButton = new Button(LangResources.get("ok"), IconKind.EDIT, ButtonStyle.SECONDARY);
		okButton.addActionListener(e -> {
			confirmed = true;
			dispose();
		});
		buttonPanel.add(okButton);

		// OK only with an URL and a valid (or empty) proxy URL
		final DocumentListener validationListener = new DocumentListener() {
			@Override
			public void insertUpdate(final DocumentEvent e) {
				updateOkButtonStatus();
			}

			@Override
			public void removeUpdate(final DocumentEvent e) {
				updateOkButtonStatus();
			}

			@Override
			public void changedUpdate(final DocumentEvent e) {
				updateOkButtonStatus();
			}
		};
		urlField.getDocument().addDocumentListener(validationListener);
		proxyUrlField.getDocument().addDocumentListener(validationListener);
		updateOkButtonStatus();

		final Button cancelButton = new Button(LangResources.get("cancel"), IconKind.TRASH, ButtonStyle.SECONDARY);
		cancelButton.addActionListener(e -> dispose());
		buttonPanel.add(cancelButton);

		getRootPane().setDefaultButton(okButton);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(formPanel, BorderLayout.CENTER);
		getContentPane().add(buttonPanel, BorderLayout.SOUTH);

		pack();
		setResizable(false);
		setLocationRelativeTo(parent);
	}

	private void updateFieldStatus() {
		idpSelectionField.setEnabled(oidcRadioButton.isSelected());
	}

	/**
	 * Enables OK only with an URL and a valid proxy URL; an invalid proxy URL shows the reason as tooltip.
	 */
	private void updateOkButtonStatus() {
		String proxyError = null;
		try {
			HttpUtilities.validateProxyUrl(proxyUrlField.getText());
		} catch (final IllegalArgumentException e) {
			proxyError = e.getMessage();
		}
		proxyUrlField.setToolTipText(proxyError != null ? proxyError : LangResources.get("proxyUrl.tooltip"));
		okButton.setEnabled(!urlField.getText().isBlank() && proxyError == null);
	}

	private static void addRow(final JPanel panel, final int row, final String labelText, final JComponent component) {
		final GridBagConstraints labelConstraints = new GridBagConstraints();
		labelConstraints.gridx = 0;
		labelConstraints.gridy = row;
		labelConstraints.anchor = GridBagConstraints.WEST;
		labelConstraints.insets = new Insets(4, 0, 4, 12);
		panel.add(new JLabel(labelText), labelConstraints);

		final GridBagConstraints componentConstraints = new GridBagConstraints();
		componentConstraints.gridx = 1;
		componentConstraints.gridy = row;
		componentConstraints.fill = GridBagConstraints.HORIZONTAL;
		componentConstraints.weightx = 1;
		componentConstraints.insets = new Insets(4, 0, 4, 0);
		panel.add(component, componentConstraints);
	}

	/**
	 * Prefills the dialog with an existing login.
	 */
	public LoginDialog withLogin(final Login login) {
		if (login != null) {
			oidcRadioButton.setSelected(login.getLoginType() == LoginType.OIDC);
			classicRadioButton.setSelected(login.getLoginType() != LoginType.OIDC);
			urlField.setText(login.getUrl() == null ? "" : login.getUrl());
			usernameField.setText(login.getUsername() == null ? "" : login.getUsername());
			// The stored password is encrypted, the field shows the decrypted password
			final char[] password = login.getPasswordArray();
			passwordField.setText(password == null ? "" : new String(password));
			if (password != null) {
				Arrays.fill(password, '\0');
			}
			idpSelectionField.setText(login.getIdpSelection() == null ? "" : login.getIdpSelection());
			proxyUrlField.setText(login.getProxyUrl() == null ? "" : login.getProxyUrl());
			updateFieldStatus();
			updateOkButtonStatus();
		}
		return this;
	}

	/**
	 * Shows the dialog modally.
	 *
	 * @return true if confirmed with OK
	 */
	public boolean open() {
		confirmed = false;
		setVisible(true);
		return confirmed;
	}

	/**
	 * Writes the dialog values into the given login.
	 */
	public Login applyTo(final Login login) {
		final char[] password = passwordField.getPassword();
		final boolean isOidc = oidcRadioButton.isSelected();
		final String idpSelection = idpSelectionField.getText().trim();
		final String proxyUrl = proxyUrlField.getText().trim();
		login.setLoginType(isOidc ? LoginType.OIDC : LoginType.BasicAuth)
		.setUrl(urlField.getText().trim())
		.setUsername(usernameField.getText().trim())
		// Encrypted with the master key; an empty password removes it
		.setPasswordArray(password)
		// IdP selection is optional and only stored for OIDC
		.setIdpSelection(isOidc && !idpSelection.isEmpty() ? idpSelection : null)
		// Proxy is optional and used for both login types; empty means system proxy settings
		.setProxyUrl(proxyUrl.isEmpty() ? null : proxyUrl);
		Arrays.fill(password, '\0');
		return login;
	}
}
