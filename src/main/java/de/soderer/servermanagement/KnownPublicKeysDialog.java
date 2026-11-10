package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import javax.swing.JFileChooser;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import de.soderer.servermanagement.config.KnownPublicSshKey;
import de.soderer.servermanagement.config.Server;
import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.servermanagement.config.User;
import de.soderer.sshkeyformats.SshKey;
import de.soderer.sshkeyformats.SshKeyReader;
import de.soderer.sshkeyformats.data.Algorithm;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.ButtonStyle;
import de.soderer.swing.modern.DialogStyle;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.ModalDialog;
import de.soderer.swing.modern.QuestionDialog;
import de.soderer.swing.modern.SimpleInputDialog;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;

public class KnownPublicKeysDialog extends ModalDialog<Boolean> {
	private static final long serialVersionUID = -1512808290727321131L;

	private final JTable table;
	private final Button editPublicKeyButton;
	private final Button removePublicKeysButton;

	public KnownPublicKeysDialog(final ServerManagementGui serverManagementGui, final String title, final ServerManagementConfiguration serverManagementConfiguration) {
		super(serverManagementGui, title, IconKind.LOCK);

		setResizable(true);

		final DefaultTableModel model = new DefaultTableModel();
		model.addColumn("Keytype");
		model.addColumn("Keylength");
		model.addColumn("Keyname");
		model.addColumn("ID/Comment");
		model.addColumn("Keyhash");
		model.addColumn("Keydata");

		loadPublicKeysInTable(model, serverManagementConfiguration);

		table = ModernTables.createTable(model);

		table.getColumnModel().getColumn(0).setPreferredWidth(15);
		table.getColumnModel().getColumn(1).setPreferredWidth(15);
		table.getColumnModel().getColumn(2).setPreferredWidth(40);
		table.getColumnModel().getColumn(3).setPreferredWidth(40);
		table.getColumnModel().getColumn(4).setPreferredWidth(140);
		table.getColumnModel().getColumn(5).setPreferredWidth(120);

		table.getSelectionModel().addListSelectionListener(e -> checkButtonStatus());
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent event) {
				if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event) && table.rowAtPoint(event.getPoint()) >= 0 && editPublicKeyButton.isEnabled()) {
					editPublicKeyButton.doClick();
				}
			}
		});

		getContentArea().add(ModernTables.createScrollPane(table, 980, 260), BorderLayout.CENTER);

		addLeftButton(LangResources.get("addPublicKey"), IconKind.PLUS, ButtonStyle.SECONDARY, event -> {
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
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\nKey already exists" + (key.getComment() != null ? ": " + key.getComment() : "")).withDialogStyle(DialogStyle.ERROR).open();
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
					if (StringUtilities.isBlank(keyId)) {
						// The key id is the reference used by the users, so a key without id could never be configured
						continue;
					} else if (serverManagementConfiguration.getKnownPublicSshKeyByKeyId(keyId.trim()) != null) {
						new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\nKey id already exists: " + keyId.trim()).withDialogStyle(DialogStyle.ERROR).open();
						continue;
					}
					newKey.setKeyId(keyId.trim());

					final String keyName = new SimpleInputDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("addPublicKey"), LangResources.get("enterKeyName")).open();
					if (keyName == null) {
						// Canceled
						continue;
					}
					// An empty key name is allowed, then the key id is shown in the user key assignment
					newKey.setName(StringUtilities.isBlank(keyName) ? null : keyName.trim());
					serverManagementConfiguration.getKnownPublicSshKeys().add(newKey);
					keysAdded = true;
				}
			} catch (final Exception e) {
				new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
			}

			if (keysAdded) {
				loadPublicKeysInTable(model, serverManagementConfiguration);
				returnValue = true;
				serverManagementGui.signalUnsavedChanges();
			}
		});

		editPublicKeyButton = addLeftButton(LangResources.get("editPublicKey"), IconKind.EDIT, ButtonStyle.SECONDARY, event -> {
			if (table.getSelectedRows().length != 1) {
				return;
			}

			final int modelRowIndex = table.convertRowIndexToModel(table.getSelectedRow());
			final String oldKeyId = (String) table.getModel().getValueAt(modelRowIndex, 3);
			final KnownPublicSshKey keyToEdit = serverManagementConfiguration.getKnownPublicSshKeyByKeyId(oldKeyId);
			if (keyToEdit == null) {
				return;
			}

			final EditKnownPublicKeyDialog editDialog = new EditKnownPublicKeyDialog(serverManagementGui, ServerManagement.APPLICATION_NAME + " " + LangResources.get("editPublicKey"), serverManagementConfiguration, oldKeyId, keyToEdit.getName(), keyToEdit.getKey());
			if (!Boolean.TRUE.equals(editDialog.open())) {
				// Canceled
				return;
			}
			final String newKeyId = editDialog.getKeyId();
			// An empty key name is allowed, then the key id is shown in the user key assignment
			final String newKeyName = StringUtilities.isBlank(editDialog.getKeyName()) ? null : editDialog.getKeyName().trim();
			final String oldKeyName = StringUtilities.isBlank(keyToEdit.getName()) ? null : keyToEdit.getName();

			if (newKeyId.equals(oldKeyId) && Objects.equals(newKeyName, oldKeyName)) {
				// Nothing changed
				return;
			}

			if (!newKeyId.equals(oldKeyId)) {
				// Users reference keys by their id, so all references must be renamed too
				for (final Server server : serverManagementConfiguration.getServers()) {
					for (final User user : server.getManagedUsers()) {
						if (user.getAuthorized_key_ids().remove(oldKeyId) && !user.getAuthorized_key_ids().contains(newKeyId)) {
							user.getAuthorized_key_ids().add(newKeyId);
						}
					}
				}
				keyToEdit.setKeyId(newKeyId);
			}
			keyToEdit.setName(newKeyName);

			loadPublicKeysInTable(model, serverManagementConfiguration);

			returnValue = true;

			serverManagementGui.signalUnsavedChanges();
		});

		removePublicKeysButton = addLeftButton(LangResources.get("removePublicKeys"), IconKind.TRASH, ButtonStyle.DANGEROUS, event -> {
			if (new QuestionDialog(serverManagementGui, ServerManagement.APPLICATION_NAME, LangResources.get("removePublicKeys") + ": " + table.getSelectedRows().length, LangResources.get("yes"), LangResources.get("no")).withDialogStyle(DialogStyle.WARNING).open() != 0) {
				return;
			}
			for (final int rowIndex : table.getSelectedRows()) {
				final String keyId = (String) table.getModel().getValueAt(table.convertRowIndexToModel(rowIndex), 3);
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
		});

		final Button closeButton = addButton(LangResources.get("close"), null, ButtonStyle.PRIMARY, event -> {
			returnValue = false;
			dispose();
		});

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
							getMd5KeyHash(knownPublicSshKey.getKey()),
							StringUtilities.trimStringToMaximumLength(knownPublicSshKey.getKey(), 30, "...")
			});
		}
	}

	/**
	 * Calculates the MD5 fingerprint of a public key as uppercase hex string without separators (e.g. "3FA27C...")
	 *
	 * @param base64KeyData the base64 encoded key blob, as used in authorized_keys files
	 * @return the fingerprint or an empty string if the key data is not valid base64
	 */
	public static String getMd5KeyHash(final String base64KeyData) {
		final byte[] hash = getKeyHash(base64KeyData, "MD5");
		if (hash == null) {
			return "";
		}
		final StringBuilder fingerprint = new StringBuilder();
		for (final byte hashByte : hash) {
			fingerprint.append(String.format("%02X", hashByte & 0xFF));
		}
		return fingerprint.toString();
	}

	/**
	 * Calculates the SHA256 fingerprint of a public key as unpadded base64 string like "ssh-keygen -lf", but without the "SHA256:" prefix (e.g. "nThbg6kX...").
	 * Base64 is case sensitive, so this value must not be converted to upper or lower case.
	 *
	 * @param base64KeyData the base64 encoded key blob, as used in authorized_keys files
	 * @return the fingerprint or an empty string if the key data is not valid base64
	 */
	public static String getSha256KeyHash(final String base64KeyData) {
		final byte[] hash = getKeyHash(base64KeyData, "SHA-256");
		return hash == null ? "" : Base64.getEncoder().withoutPadding().encodeToString(hash);
	}

	private static byte[] getKeyHash(final String base64KeyData, final String hashAlgorithm) {
		if (StringUtilities.isBlank(base64KeyData)) {
			return null;
		}
		try {
			final byte[] keyBlob = Base64.getDecoder().decode(base64KeyData.trim());
			return MessageDigest.getInstance(hashAlgorithm).digest(keyBlob);
		} catch (@SuppressWarnings("unused") final Exception e) {
			return null;
		}
	}

	private void checkButtonStatus() {
		final int selectedRowCount = table.getSelectedRows().length;
		editPublicKeyButton.setEnabled(selectedRowCount == 1);
		removePublicKeysButton.setEnabled(selectedRowCount > 0);
	}

	private File selectFile(String basePath, final String text) {
		if (StringUtilities.isBlank(basePath)) {
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
