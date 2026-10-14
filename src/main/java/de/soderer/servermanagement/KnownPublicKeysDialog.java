package de.soderer.servermanagement;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableModel;

import de.soderer.servermanagement.config.KnownPublicSshKey;
import de.soderer.servermanagement.config.Server;
import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.servermanagement.config.User;
import de.soderer.sshkeyformats.SshKey;
import de.soderer.sshkeyformats.SshKeyReader;
import de.soderer.sshkeyformats.data.Algorithm;
import de.soderer.swing.ModalDialog;
import de.soderer.swing.QuestionDialog;
import de.soderer.swing.SimpleInputDialog;
import de.soderer.swing.SwingColor;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Utilities;

public class KnownPublicKeysDialog extends ModalDialog<Boolean> {
	private static final long serialVersionUID = -1512808290727321131L;

	private JTable table;
	private final JButton removePublicKeysButton;

	public KnownPublicKeysDialog(final ServerManagementGui serverManagementGui, final String title, final ServerManagementConfiguration serverManagementConfiguration) {
		super(serverManagementGui, title);

		final KnownPublicKeysDialog dialog = this;

		setResizable(false);

		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));

		final DefaultTableModel model = new DefaultTableModel();
		model.addColumn("Keytype");
		model.addColumn("Keylength");
		model.addColumn("Keyname");
		model.addColumn("ID/Comment");
		model.addColumn("Keydata");

		loadPublicKeysInTable(model, serverManagementConfiguration);

		table = new JTable(model);

		table.getColumnModel().getColumn(0).setPreferredWidth(15);
		table.getColumnModel().getColumn(1).setPreferredWidth(15);
		table.getColumnModel().getColumn(2).setPreferredWidth(40);
		table.getColumnModel().getColumn(3).setPreferredWidth(40);
		table.getColumnModel().getColumn(4).setPreferredWidth(120);

		table.setBackground(Color.LIGHT_GRAY);

		final JScrollPane tableScrollpane = new JScrollPane(table);
		tableScrollpane.setPreferredSize(new Dimension(700, 250));

		table.setDefaultEditor(Object.class, null);

		table.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(final ListSelectionEvent e) {
				checkButtonStatus();
			}
		});

		final JPanel tablePanel = new JPanel();
		tablePanel.setLayout(new BoxLayout(tablePanel, BoxLayout.LINE_AXIS));

		tablePanel.add(Box.createRigidArea(new Dimension(5, 0)));
		tablePanel.add(tableScrollpane);
		tablePanel.add(Box.createRigidArea(new Dimension(5, 0)));

		// Button Panel 2
		final JPanel buttonPanel2 = new JPanel();
		buttonPanel2.setLayout(new BoxLayout(buttonPanel2, BoxLayout.LINE_AXIS));

		buttonPanel2.add(Box.createRigidArea(new Dimension(5, 0)));

		final JButton addPublicKeyButton = new JButton(LangResources.get("addPublicKey"));
		addPublicKeyButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				final String homePath = System.getProperty("user.home") + File.separator + ".ssh" + File.separator;
				final File newPublicKeyFile = selectFile(homePath, LangResources.get("addPublicKey"));
				if (newPublicKeyFile == null) {
					// Before, canceling the file selection also marked the configuration as changed
					return;
				}

				boolean keysAdded = false;
				try (FileInputStream fileInputStream = new FileInputStream(newPublicKeyFile)) {
					final List<SshKey> keys = SshKeyReader.readAllPublicKeys(fileInputStream);
					for (final SshKey key : keys) {
						final String authKeyString = key.encodePublicKeyForAuthorizedKeys();
						final String[] authKeyStringParts = authKeyString.split(" ");

						boolean alreadyExists = false;
						for (final KnownPublicSshKey existingKey : serverManagementConfiguration.getKnownPublicSshKeys()) {
							if (authKeyStringParts[1].equals(existingKey.getKey())) {
								alreadyExists = true;
								break;
							}
						}
						if (alreadyExists) {
							// Before, this returned immediately, so the further keys of the file were skipped and the
							// keys added so far were neither shown nor marked as unsaved changes
							new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\nKey already exists" + (key.getComment() != null ? ": " + key.getComment() : "")).setBackgroundColor(SwingColor.LightRed).open();
							continue;
						}

						final KnownPublicSshKey newKey = new KnownPublicSshKey();
						newKey.setKeyTypeString(authKeyStringParts[0]);
						newKey.setKey(authKeyStringParts[1]);

						final SimpleInputDialog keyInputDialog = new SimpleInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("addPublicKey"), LangResources.get("enterKeyId"));
						if (key.getComment() != null) {
							keyInputDialog.setDefaultText(key.getComment());
						}
						final String keyId = keyInputDialog.open();
						if (Utilities.isBlank(keyId)) {
							// The key id is the reference used by the users, so a key without id could never be configured
							continue;
						} else if (serverManagementConfiguration.getKnownPublicSshKeyByKeyId(keyId.trim()) != null) {
							new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\nKey id already exists: " + keyId.trim()).setBackgroundColor(SwingColor.LightRed).open();
							continue;
						}
						newKey.setKeyId(keyId.trim());

						final String keyName = new SimpleInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("addPublicKey"), LangResources.get("enterKeyName")).open();
						if (Utilities.isNotBlank(keyName)) {
							newKey.setName(keyName);
							serverManagementConfiguration.getKnownPublicSshKeys().add(newKey);
							keysAdded = true;
						}
					}
				} catch (final Exception e) {
					new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).setBackgroundColor(SwingColor.LightRed).open();
				}

				if (keysAdded) {
					loadPublicKeysInTable(model, serverManagementConfiguration);
					returnValue = true;
					serverManagementGui.signalUnsavedChanges();
				}
			}
		});
		buttonPanel2.add(addPublicKeyButton);

		buttonPanel2.add(Box.createRigidArea(new Dimension(5, 0)));

		removePublicKeysButton = new JButton(LangResources.get("removePublicKeys"));
		removePublicKeysButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				if (new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("removePublicKeys") + ": " + table.getSelectedRows().length, LangResources.get("yes"), LangResources.get("no")).open() != 0) {
					return;
				}
				for (final int rowIndex : table.getSelectedRows()) {
					final String keyId = (String) table.getModel().getValueAt(rowIndex, 3);
					serverManagementConfiguration.getKnownPublicSshKeys().remove(serverManagementConfiguration.getKnownPublicSshKeyByKeyId(keyId));

					for (final Server server : serverManagementConfiguration.getServers()) {
						for (final User user : server.getManagedUsers()) {
							user.getAuthorized_key_ids().remove(keyId);
						}
					}
				}

				loadPublicKeysInTable(model, serverManagementConfiguration);

				returnValue = true;

				serverManagementGui.signalUnsavedChanges();
			}
		});
		buttonPanel2.add(removePublicKeysButton);

		buttonPanel2.add(Box.createRigidArea(new Dimension(5, 0)));

		final JButton closeButton = new JButton(LangResources.get("close"));
		closeButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				returnValue = false;
				dialog.dispose();
			}
		});
		buttonPanel2.add(closeButton);

		buttonPanel2.add(Box.createRigidArea(new Dimension(5, 0)));

		panel.add(Box.createRigidArea(new Dimension(0, 5)));

		panel.add(tablePanel);

		panel.add(buttonPanel2);

		add(panel);

		pack();

		setLocationRelativeTo(serverManagementGui);

		getRootPane().setDefaultButton(closeButton);

		checkButtonStatus();
	}

	private static void loadPublicKeysInTable(final DefaultTableModel model, final ServerManagementConfiguration serverManagementConfiguration) {
		// Remove all existing rows from table
		for (int i = model.getRowCount() - 1; i >= 0; i--) {
			model.removeRow(i);
		}

		// Fill table with current data
		for (final KnownPublicSshKey knownPublicSshKey : serverManagementConfiguration.getKnownPublicSshKeys()) {
			String keyType;
			int keyLength;
			try {
				// Using fix KeyType.RSA. Real keytype is detected afterwards.
				final List<SshKey> keys = SshKeyReader.readAllPublicKeys(new ByteArrayInputStream(knownPublicSshKey.getKey().getBytes(StandardCharsets.UTF_8)));
				final SshKey key = keys.get(0);
				final Algorithm algorithm = key.getAlgorithm();
				keyType = algorithm.getSshAlgorithmId();
				if (Algorithm.RSA == algorithm) {
					keyLength = key.getKeyStrength();
				} else {
					keyLength = 0;
				}
			} catch (@SuppressWarnings("unused") final Exception e) {
				keyType = "Unknown key type";
				keyLength = 0;
			}

			model.addRow(new Object[]{
					keyType,
					keyLength > 0 ? Integer.toString(keyLength) : "",
							knownPublicSshKey.getName(),
							knownPublicSshKey.getKeyId(),
							Utilities.shortenStringToMaxLengthCutMiddle(knownPublicSshKey.getKey(), 30)
			});
		}
	}

	private void checkButtonStatus() {
		if (table.getSelectedRows().length > 0) {
			removePublicKeysButton.setEnabled(true);
		} else {
			removePublicKeysButton.setEnabled(false);
		}
	}

	private File selectFile(String basePath, final String text) {
		if (Utilities.isBlank(basePath)) {
			basePath = System.getProperty("user.home");
		} else if (basePath.contains(File.separator)) {
			basePath = basePath.substring(0, basePath.lastIndexOf(File.separator));
		}

		final JFileChooser fileChooser = new JFileChooser(basePath);
		fileChooser.setDialogTitle(ServerManagement.APPLICATION_NAME + " " + text);
		if (JFileChooser.APPROVE_OPTION == fileChooser.showOpenDialog(this)) {
			return fileChooser.getSelectedFile();
		} else {
			return null;
		}
	}
}
