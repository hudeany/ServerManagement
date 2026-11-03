package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.ButtonStyle;
import de.soderer.swing.modern.DialogStyle;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.ModalDialog;
import de.soderer.swing.modern.QuestionDialog;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;

/**
 * Dialog for editing key id and key name of a known public ssh key in one step.
 * Input is validated before the dialog closes, so the user can correct mistakes without retyping everything.
 */
public class EditKnownPublicKeyDialog extends ModalDialog<Boolean> {
	private static final long serialVersionUID = 4127659831054623987L;

	private final JTextField keyIdField;
	private final JTextField keyNameField;

	private String keyId;
	private String keyName;

	public EditKnownPublicKeyDialog(final ServerManagementGui serverManagementGui, final String title, final ServerManagementConfiguration serverManagementConfiguration, final String oldKeyId, final String oldKeyName) {
		super(serverManagementGui, title, IconKind.LOCK);

		setResizable(false);

		keyIdField = new JTextField(oldKeyId == null ? "" : oldKeyId, 30);
		keyNameField = new JTextField(oldKeyName == null ? "" : oldKeyName, 30);

		final JPanel formPanel = new JPanel(new GridBagLayout());
		formPanel.setOpaque(false);
		formPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		final GridBagConstraints constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 4, 4, 4);
		constraints.anchor = GridBagConstraints.WEST;

		constraints.gridx = 0;
		constraints.gridy = 0;
		constraints.fill = GridBagConstraints.NONE;
		constraints.weightx = 0;
		formPanel.add(new JLabel(LangResources.get("enterKeyId")), constraints);

		constraints.gridx = 1;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.weightx = 1;
		formPanel.add(keyIdField, constraints);

		constraints.gridx = 0;
		constraints.gridy = 1;
		constraints.fill = GridBagConstraints.NONE;
		constraints.weightx = 0;
		formPanel.add(new JLabel(LangResources.get("enterKeyName")), constraints);

		constraints.gridx = 1;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.weightx = 1;
		formPanel.add(keyNameField, constraints);

		getContentArea().add(formPanel, BorderLayout.CENTER);

		final Button okButton = addButton(LangResources.get("ok"), null, ButtonStyle.PRIMARY, event -> {
			final String newKeyId = keyIdField.getText().trim();
			final String newKeyName = keyNameField.getText().trim();

			final String errorMessage;
			if (StringUtilities.isBlank(newKeyId)) {
				// The key id is the reference used by the users, so it must not be empty
				errorMessage = "Key id must not be empty";
			} else if (!newKeyId.equals(oldKeyId) && serverManagementConfiguration.getKnownPublicSshKeyByKeyId(newKeyId) != null) {
				errorMessage = "Key id already exists: " + newKeyId;
			} else {
				errorMessage = null;
			}

			if (errorMessage != null) {
				// Keep the dialog open, so the user can correct the input
				new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + errorMessage).withDialogStyle(DialogStyle.ERROR).open();
				return;
			}

			keyId = newKeyId;
			// An empty key name is allowed, then the key id is shown in the user key assignment
			keyName = StringUtilities.isBlank(newKeyName) ? null : newKeyName;
			returnValue = true;
			dispose();
		});

		addButton(LangResources.get("cancel"), null, ButtonStyle.SECONDARY, event -> {
			returnValue = false;
			dispose();
		});

		pack();

		setLocationRelativeTo(serverManagementGui);

		getRootPane().setDefaultButton(okButton);
	}

	public String getKeyId() {
		return keyId;
	}

	/**
	 * @return The entered key name, or null if it was left empty
	 */
	public String getKeyName() {
		return keyName;
	}
}
