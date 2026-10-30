package de.soderer.servermanagement;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

import de.soderer.swing.modern.ButtonStyle;
import de.soderer.swing.modern.DialogStyle;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.ModalDialog;
import de.soderer.swing.modern.ModernUi;
import de.soderer.swing.modern.QuestionDialog;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;

public class UserPublicKeysDialog extends ModalDialog<Boolean> {
	private static final long serialVersionUID = -3359111126746373988L;

	private final String text;

	private final String adminKeyHash;

	private final Map<String, String> existingKeys;
	private final Map<String, String> configuredKeys;
	private final Map<String, String> knownKeys;

	private DefaultTableModel existingUserSshKeysModel;
	private JTable existingUserSshKeysTable;

	private DefaultTableModel configuredSshKeysModel;
	private JTable configuredSshKeysTable;

	private DefaultTableModel knownSshKeysModel;
	private JTable knownSshKeysTable;

	private JButton okButton;

	/** Preferred width of each key table */
	private static final int KEY_TABLE_WIDTH = 260;

	/** Preferred height of each key table */
	private static final int KEY_TABLE_HEIGHT = 240;

	/** Width of the transfer buttons between the tables */
	private static final int TRANSFER_BUTTON_WIDTH = 52;

	public UserPublicKeysDialog(final ServerManagementGui serverManagementGui, final String title, final String text, final String adminKeyHash, final Map<String, String> existingKeys, final Map<String, String> configuredKeys, final Map<String, String> knownKeys) throws Exception {
		super(serverManagementGui, title, IconKind.EDIT);

		this.text = text;

		this.adminKeyHash = adminKeyHash;

		this.existingKeys = new HashMap<>();
		this.existingKeys.putAll(existingKeys);

		this.configuredKeys = new HashMap<>();
		this.configuredKeys.putAll(configuredKeys);

		this.knownKeys = new HashMap<>();
		this.knownKeys.putAll(knownKeys);

		setResizable(false);

		createComponents();

		returnValue = false;
	}

	private void createComponents() throws Exception {
		if (StringUtilities.isNotBlank(text)) {
			final JTextArea textArea = new JTextArea(text);
			textArea.setEditable(false);
			textArea.setFocusable(false);
			textArea.setOpaque(false);
			textArea.setBorder(null);
			textArea.setFont(ModernUi.baseFont());
			textArea.setForeground(ModernUi.TEXT);
			getContentArea().add(textArea, BorderLayout.NORTH);
		}

		// Server keys
		existingUserSshKeysModel = new DefaultTableModel();
		existingUserSshKeysModel.addColumn(LangResources.get("keyName"));
		existingUserSshKeysModel.addColumn(LangResources.get("hash"));
		existingUserSshKeysTable = createKeyTable(existingUserSshKeysModel);

		final JButton dropExistingKeysButton = createDropButton(LangResources.get("dropExistingKeys"));
		dropExistingKeysButton.addActionListener(event -> {
			removeSelectedEntries(existingUserSshKeysTable, existingKeys);
			loadTablesData();
		});

		// Transfer between server keys and configured keys
		final JButton uploadAllConfiguredUserSshKeysButton = createTransferButton("«", LangResources.get("uploadAllConfiguredUserSshKeys"));
		uploadAllConfiguredUserSshKeysButton.addActionListener(event -> transferAllEntries(configuredSshKeysModel, existingKeys));

		final JButton uploadSelectedConfiguredUserSshKeysButton = createTransferButton("‹", LangResources.get("uploadSelectedConfiguredUserSshKeys"));
		uploadSelectedConfiguredUserSshKeysButton.addActionListener(event -> transferSelectedEntries(configuredSshKeysTable, existingKeys));

		final JButton downloadSelectedConfiguredUserSshKeysButton = createTransferButton("›", LangResources.get("downloadSelectedExistingUserSshKeys"));
		downloadSelectedConfiguredUserSshKeysButton.addActionListener(event -> transferSelectedEntries(existingUserSshKeysTable, knownKeys));

		final JButton downloadAllExistingUserSshKeysButton = createTransferButton("»", LangResources.get("downloadAllExistingUserSshKeys"));
		downloadAllExistingUserSshKeysButton.addActionListener(event -> transferAllEntries(existingUserSshKeysModel, knownKeys));

		// Configured keys
		configuredSshKeysModel = new DefaultTableModel();
		configuredSshKeysModel.addColumn(LangResources.get("keyName"));
		configuredSshKeysModel.addColumn(LangResources.get("hash"));
		configuredSshKeysTable = createKeyTable(configuredSshKeysModel);

		final JButton dropConfiguredKeysButton = createDropButton(LangResources.get("dropConfiguredKeys"));
		dropConfiguredKeysButton.addActionListener(event -> {
			removeSelectedEntries(configuredSshKeysTable, existingKeys, configuredKeys);
			loadTablesData();
		});

		// Transfer between configured keys and known keys
		final JButton configureAllKnownUserSshKeysButton = createTransferButton("«", LangResources.get("configureAllKnownUserSshKeys"));
		configureAllKnownUserSshKeysButton.addActionListener(event -> transferAllEntries(knownSshKeysModel, configuredKeys));

		final JButton configureSelectedKnownUserSshKeysButton = createTransferButton("‹", LangResources.get("configureSelectedKnownUserSshKeys"));
		configureSelectedKnownUserSshKeysButton.addActionListener(event -> transferSelectedEntries(knownSshKeysTable, configuredKeys));

		// Known keys
		knownSshKeysModel = new DefaultTableModel();
		knownSshKeysModel.addColumn(LangResources.get("keyName"));
		knownSshKeysModel.addColumn(LangResources.get("hash"));
		knownSshKeysTable = createKeyTable(knownSshKeysModel);

		final JButton dropKnownKeysButton = createDropButton(LangResources.get("dropKnownKeys"));
		dropKnownKeysButton.addActionListener(event -> {
			removeSelectedEntries(knownSshKeysTable, existingKeys, configuredKeys, knownKeys);
			loadTablesData();
		});

		final JPanel tablesPanel = new JPanel(new GridBagLayout());
		tablesPanel.setOpaque(false);
		addKeyColumn(tablesPanel, 0, LangResources.get("serverPublicKeys"), existingUserSshKeysTable, dropExistingKeysButton);
		addTransferColumn(tablesPanel, 1, uploadAllConfiguredUserSshKeysButton, uploadSelectedConfiguredUserSshKeysButton, downloadSelectedConfiguredUserSshKeysButton, downloadAllExistingUserSshKeysButton);
		addKeyColumn(tablesPanel, 2, LangResources.get("configuredPublicKeys"), configuredSshKeysTable, dropConfiguredKeysButton);
		addTransferColumn(tablesPanel, 3, configureAllKnownUserSshKeysButton, configureSelectedKnownUserSshKeysButton);
		addKeyColumn(tablesPanel, 4, LangResources.get("knownPublicKeys"), knownSshKeysTable, dropKnownKeysButton);
		getContentArea().add(tablesPanel, BorderLayout.CENTER);

		loadTablesData();

		okButton = addButton(getI18NString("save"), null, ButtonStyle.PRIMARY, event -> {
			returnValue = true;
			dispose();
		});

		addButton(getI18NString("cancel"), null, ButtonStyle.SECONDARY, event -> {
			returnValue = false;
			dispose();
		});

		getRootPane().setDefaultButton(okButton);

		pack();

		setLocationRelativeTo(getOwner());

		checkButtonStatus();
	}

	/**
	 * Creates a key table, sorted by hash and name.
	 */
	private static JTable createKeyTable(final DefaultTableModel model) {
		final JTable table = ModernTables.createTable(model);
		final TableRowSorter<TableModel> sorter = new TableRowSorter<>(table.getModel());
		final List<RowSorter.SortKey> sortKeys = new ArrayList<>();
		sortKeys.add(new RowSorter.SortKey(1, SortOrder.ASCENDING));
		sortKeys.add(new RowSorter.SortKey(0, SortOrder.ASCENDING));
		sorter.setSortKeys(sortKeys);
		table.setRowSorter(sorter);
		return table;
	}

	/**
	 * Full width button below a key table, removing the selected keys.
	 */
	private static JButton createDropButton(final String toolTipText) {
		final JButton button = new ModernUi.Button(null, IconKind.TRASH, ButtonStyle.DANGEROUS);
		button.setToolTipText(toolTipText);
		button.getAccessibleContext().setAccessibleName(toolTipText);
		return button;
	}

	/**
	 * Button between two key tables, transferring keys in the direction of its arrow.
	 */
	private static JButton createTransferButton(final String arrowText, final String toolTipText) {
		final JButton button = new ModernUi.Button(arrowText, null, ButtonStyle.SECONDARY);
		button.setFont(ModernUi.baseFont().deriveFont(Font.BOLD, ModernUi.baseFont().getSize2D() + 4f));
		button.setToolTipText(toolTipText);
		button.getAccessibleContext().setAccessibleName(toolTipText);
		button.setPreferredSize(new Dimension(TRANSFER_BUTTON_WIDTH, button.getPreferredSize().height));
		return button;
	}

	/**
	 * Adds a column with title, key table and the button removing the selected keys.
	 */
	private static void addKeyColumn(final JPanel tablesPanel, final int column, final String title, final JTable table, final JButton dropButton) {
		final GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = column;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.anchor = GridBagConstraints.LINE_START;

		constraints.gridy = 0;
		constraints.insets = new Insets(0, 0, 6, 0);
		tablesPanel.add(ModernUi.createSectionTitle(title), constraints);

		constraints.gridy = 1;
		constraints.weightx = 1;
		constraints.weighty = 1;
		constraints.fill = GridBagConstraints.BOTH;
		constraints.insets = new Insets(0, 0, 6, 0);
		tablesPanel.add(ModernTables.createScrollPane(table, KEY_TABLE_WIDTH, KEY_TABLE_HEIGHT), constraints);

		constraints.gridy = 2;
		constraints.weighty = 0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.insets = new Insets(0, 0, 0, 0);
		tablesPanel.add(dropButton, constraints);
	}

	/**
	 * Adds a column of transfer buttons between two key tables, vertically centered at the tables.
	 */
	private static void addTransferColumn(final JPanel tablesPanel, final int column, final JButton... buttons) {
		final JPanel buttonPanel = new JPanel(new GridLayout(buttons.length, 1, 0, 6));
		buttonPanel.setOpaque(false);
		for (final JButton button : buttons) {
			buttonPanel.add(button);
		}

		final GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = column;
		constraints.gridy = 1;
		constraints.insets = new Insets(0, 10, 6, 10);
		tablesPanel.add(buttonPanel, constraints);
	}

	private void loadTablesData() {
		for (int i = existingUserSshKeysModel.getRowCount() - 1; i >= 0; i--) {
			existingUserSshKeysModel.removeRow(i);
		}
		for (final Entry<String, String> entry : existingKeys.entrySet()) {
			existingUserSshKeysModel.addRow(new String[] { entry.getValue(), entry.getKey() });
		}

		for (int i = configuredSshKeysModel.getRowCount() - 1; i >= 0; i--) {
			configuredSshKeysModel.removeRow(i);
		}
		for (final Entry<String, String> entry : configuredKeys.entrySet()) {
			configuredSshKeysModel.addRow(new String[] { entry.getValue(), entry.getKey() });
		}

		for (int i = knownSshKeysModel.getRowCount() - 1; i >= 0; i--) {
			knownSshKeysModel.removeRow(i);
		}
		for (final Entry<String, String> entry : knownKeys.entrySet()) {
			knownSshKeysModel.addRow(new String[] { entry.getValue(), entry.getKey() });
		}
	}

	@SafeVarargs
	private final void removeSelectedEntries(final JTable sourceTable, final Map<String, String>... destinationMaps) {
		for (final int rowIndex1 : sourceTable.getSelectedRows()) {
			final String keyHash = (String) sourceTable.getValueAt(rowIndex1, 1);
			if (!keyHash.equalsIgnoreCase(adminKeyHash)
					|| 0 == new QuestionDialog(this, ServerManagement.APPLICATION_NAME, LangResources.get("adminKeyRemoval"), LangResources.get("yes"), LangResources.get("no")).withDialogStyle(DialogStyle.WARNING).open()) {
				for (final Map<String, String> destinationMap : destinationMaps) {
					destinationMap.remove(keyHash);
				}
			}
		}
	}

	private void transferAllEntries(final DefaultTableModel sourceModel, final Map<String, String> destinationMap) {
		for (int rowIndex1 = 0; rowIndex1 < sourceModel.getRowCount(); rowIndex1++) {
			final String keyIdentifier = (String) sourceModel.getValueAt(rowIndex1, 0);
			final String keyHash = (String) sourceModel.getValueAt(rowIndex1, 1);

			boolean found = false;
			for (final Entry<String, String> knownKey : destinationMap.entrySet()) {
				if (knownKey.getKey().equals(keyHash)) {
					found = true;
					break;
				}
			}

			if (!found) {
				destinationMap.put(keyHash, keyIdentifier);
			}
		}

		loadTablesData();
	}

	private void transferSelectedEntries(final JTable sourceTable, final Map<String, String> destinationMap) {
		for (final int rowIndex1 : sourceTable.getSelectedRows()) {
			final String keyIdentifier = (String) sourceTable.getValueAt(rowIndex1, 0);
			final String keyHash = (String) sourceTable.getValueAt(rowIndex1, 1);

			boolean found = false;
			for (final Entry<String, String> knownKey : destinationMap.entrySet()) {
				if (knownKey.getKey().equals(keyHash)) {
					found = true;
					break;
				}
			}

			if (!found) {
				destinationMap.put(keyHash, keyIdentifier);
			}
		}

		loadTablesData();
	}

	private void checkButtonStatus() {
		// nothing to do
	}

	private static String getI18NString(final String resourceKey, final Object... arguments) {
		if (LangResources.existsKey(resourceKey)) {
			return LangResources.get(resourceKey, arguments);
		} else if ("de".equalsIgnoreCase(Locale.getDefault().getLanguage())) {
			String pattern;
			switch(resourceKey) {
				case "username": pattern = "Username"; break;
				case "password": pattern = "Passwort"; break;
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
				case "ok": pattern = "OK"; break;
				case "cancel": pattern = "Cancel"; break;
				default: pattern = "MessageKey unknown: " + resourceKey + (arguments != null && arguments.length > 0 ? " arguments: " + StringUtilities.join(arguments, ", ") : "");
			}
			return MessageFormat.format(pattern, arguments);
		}
	}

	public Map<String, String> getExistingKeys() {
		return existingKeys;
	}

	public Map<String, String> getConfiguredKeys() {
		return configuredKeys;
	}

	public Map<String, String> getKnownKeys() {
		return knownKeys;
	}
}
