package de.soderer.servermanagement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

import de.soderer.swing.ModalDialog;
import de.soderer.swing.QuestionDialog;
import de.soderer.swing.SwingColor;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Utilities;

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

	private JPanel panel;
	private JButton okButton;

	public UserPublicKeysDialog(final ServerManagementGui serverManagementGui, final String title, final String text, final String adminKeyHash, final Map<String, String> existingKeys, final Map<String, String> configuredKeys, final Map<String, String> knownKeys) throws Exception {
		super(serverManagementGui, title);

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
		panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));

		panel.add(Box.createRigidArea(new Dimension(0, 5)));

		if (StringUtilities.isNotBlank(text)) {
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

		final JPanel tablesPanel = new JPanel();
		tablesPanel.setLayout(new BoxLayout(tablesPanel, BoxLayout.LINE_AXIS));

		final JPanel tables1Panel = new JPanel();
		tables1Panel.setLayout(new BoxLayout(tables1Panel, BoxLayout.PAGE_AXIS));
		tables1Panel.add(new JLabel(LangResources.get("serverPublicKeys")));

		existingUserSshKeysModel = new DefaultTableModel();
		existingUserSshKeysModel.addColumn(LangResources.get("keyName"));
		existingUserSshKeysModel.addColumn(LangResources.get("hash"));
		existingUserSshKeysTable = new JTable(existingUserSshKeysModel);
		existingUserSshKeysTable.setBorder(BorderFactory.createLineBorder(Color.BLACK));
		existingUserSshKeysTable.setDefaultEditor(Object.class, null);
		final TableRowSorter<TableModel> sorter1 = new TableRowSorter<>(existingUserSshKeysTable.getModel());
		final List<RowSorter.SortKey> sortKeys1 = new ArrayList<>();
		sortKeys1.add(new RowSorter.SortKey(1, SortOrder.ASCENDING));
		sortKeys1.add(new RowSorter.SortKey(0, SortOrder.ASCENDING));
		sorter1.setSortKeys(sortKeys1);
		existingUserSshKeysTable.setRowSorter(sorter1);
		final JScrollPane scrollPane1 = new JScrollPane(existingUserSshKeysTable);
		tables1Panel.add(scrollPane1);

		final JButton dropExistingKeysButton = new JButton("X");
		dropExistingKeysButton.setForeground(CustomizedColor.RED);
		dropExistingKeysButton.setSize(500, 20);
		dropExistingKeysButton.setToolTipText(LangResources.get("dropExistingKeys"));
		dropExistingKeysButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				removeSelectedEntries(existingUserSshKeysTable, existingKeys);

				loadTablesData();
			}
		});
		final JPanel removeButton1Panel = new JPanel(new GridLayout(1, 1));
		dropExistingKeysButton.setAlignmentX(Component.CENTER_ALIGNMENT);
		removeButton1Panel.add(dropExistingKeysButton);
		tables1Panel.add(removeButton1Panel);

		final JPanel buttonPanel1 = new JPanel(new GridLayout(4, 1, 5, 5));

		final JButton uploadAllConfiguredUserSshKeysButton = new JButton("<<");
		uploadAllConfiguredUserSshKeysButton.setBackground(CustomizedColor.YELLOW);
		uploadAllConfiguredUserSshKeysButton.setToolTipText(LangResources.get("uploadAllConfiguredUserSshKeys"));
		uploadAllConfiguredUserSshKeysButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				transferAllEntries(configuredSshKeysModel, existingKeys);
			}
		});
		buttonPanel1.add(uploadAllConfiguredUserSshKeysButton);

		final JButton uploadSelectedConfiguredUserSshKeysButton = new JButton("<");
		uploadSelectedConfiguredUserSshKeysButton.setBackground(CustomizedColor.GREEN);
		uploadSelectedConfiguredUserSshKeysButton.setToolTipText(LangResources.get("uploadSelectedConfiguredUserSshKeys"));
		uploadSelectedConfiguredUserSshKeysButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				transferSelectedEntries(configuredSshKeysTable, existingKeys);
			}
		});
		buttonPanel1.add( uploadSelectedConfiguredUserSshKeysButton);

		final JButton downloadSelectedConfiguredUserSshKeysButton = new JButton(">");
		downloadSelectedConfiguredUserSshKeysButton.setBackground(CustomizedColor.GREEN);
		downloadSelectedConfiguredUserSshKeysButton.setToolTipText(LangResources.get("downloadSelectedExistingUserSshKeys"));
		downloadSelectedConfiguredUserSshKeysButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				transferSelectedEntries(existingUserSshKeysTable, knownKeys);
			}
		});
		buttonPanel1.add( downloadSelectedConfiguredUserSshKeysButton);

		final JButton downloadAllExistingUserSshKeysButton = new JButton(">>");
		downloadAllExistingUserSshKeysButton.setBackground(CustomizedColor.YELLOW);
		downloadAllExistingUserSshKeysButton.setToolTipText(LangResources.get("downloadAllExistingUserSshKeys"));
		downloadAllExistingUserSshKeysButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				transferAllEntries(existingUserSshKeysModel, knownKeys);
			}
		});
		buttonPanel1.add(downloadAllExistingUserSshKeysButton);

		final JPanel tables2Panel = new JPanel();
		tables2Panel.setLayout(new BoxLayout(tables2Panel, BoxLayout.PAGE_AXIS));
		tables2Panel.add(new JLabel(LangResources.get("configuredPublicKeys")));
		configuredSshKeysModel = new DefaultTableModel();
		configuredSshKeysModel.addColumn(LangResources.get("keyName"));
		configuredSshKeysModel.addColumn(LangResources.get("hash"));
		configuredSshKeysTable = new JTable(configuredSshKeysModel);
		configuredSshKeysTable.setBorder(BorderFactory.createLineBorder(Color.BLACK));
		configuredSshKeysTable.setDefaultEditor(Object.class, null);

		final TableRowSorter<TableModel> sorter2 = new TableRowSorter<>(configuredSshKeysTable.getModel());
		final List<RowSorter.SortKey> sortKeys2 = new ArrayList<>();
		sortKeys2.add(new RowSorter.SortKey(1, SortOrder.ASCENDING));
		sortKeys2.add(new RowSorter.SortKey(0, SortOrder.ASCENDING));
		sorter2.setSortKeys(sortKeys2);
		configuredSshKeysTable.setRowSorter(sorter2);
		final JScrollPane scrollPane2 = new JScrollPane(configuredSshKeysTable);
		tables2Panel.add(scrollPane2);

		final JButton dropConfiguredKeysButton = new JButton("X");
		dropConfiguredKeysButton.setForeground(CustomizedColor.RED);
		dropConfiguredKeysButton.setToolTipText(LangResources.get("dropConfiguredKeys"));
		dropConfiguredKeysButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				removeSelectedEntries(configuredSshKeysTable, existingKeys, configuredKeys);

				loadTablesData();
			}
		});
		final JPanel removeButton2Panel = new JPanel(new GridLayout(1, 1));
		dropConfiguredKeysButton.setAlignmentX(Component.CENTER_ALIGNMENT);
		removeButton2Panel.add(dropConfiguredKeysButton);
		tables2Panel.add(removeButton2Panel);

		final JPanel buttonPanel2 = new JPanel(new GridLayout(4, 1, 5, 5));

		final JButton configureAllKnownUserSshKeysButton = new JButton("<<");
		configureAllKnownUserSshKeysButton.setBackground(CustomizedColor.YELLOW);
		configureAllKnownUserSshKeysButton.setToolTipText(LangResources.get("configureAllKnownUserSshKeys"));
		configureAllKnownUserSshKeysButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				transferAllEntries(knownSshKeysModel, configuredKeys);
			}
		});
		buttonPanel2.add(configureAllKnownUserSshKeysButton);

		final JButton configureSelectedKnownUserSshKeysButton = new JButton("<");
		configureSelectedKnownUserSshKeysButton.setBackground(CustomizedColor.GREEN);
		configureSelectedKnownUserSshKeysButton.setToolTipText(LangResources.get("configureSelectedKnownUserSshKeys"));
		configureSelectedKnownUserSshKeysButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				transferSelectedEntries(knownSshKeysTable, configuredKeys);
			}
		});
		buttonPanel2.add(configureSelectedKnownUserSshKeysButton);

		final JPanel tables3Panel = new JPanel();
		tables3Panel.setLayout(new BoxLayout(tables3Panel, BoxLayout.PAGE_AXIS));
		tables3Panel.add(new JLabel(LangResources.get("knownPublicKeys")));
		knownSshKeysModel = new DefaultTableModel();
		knownSshKeysModel.addColumn(LangResources.get("keyName"));
		knownSshKeysModel.addColumn(LangResources.get("hash"));
		knownSshKeysTable = new JTable(knownSshKeysModel);
		knownSshKeysTable.setBorder(BorderFactory.createLineBorder(Color.BLACK));
		knownSshKeysTable.setDefaultEditor(Object.class, null);
		final TableRowSorter<TableModel> sorter3 = new TableRowSorter<>(knownSshKeysTable.getModel());
		final List<RowSorter.SortKey> sortKeys3 = new ArrayList<>();
		sortKeys3.add(new RowSorter.SortKey(1, SortOrder.ASCENDING));
		sortKeys3.add(new RowSorter.SortKey(0, SortOrder.ASCENDING));
		sorter3.setSortKeys(sortKeys3);
		knownSshKeysTable.setRowSorter(sorter3);
		final JScrollPane scrollPane3 = new JScrollPane(knownSshKeysTable);
		tables3Panel.add(scrollPane3);

		final JButton dropKnownKeysButton = new JButton("X");
		dropKnownKeysButton.setForeground(CustomizedColor.RED);
		dropKnownKeysButton.setSize(500, 20);
		dropKnownKeysButton.setToolTipText(LangResources.get("dropKnownKeys"));
		dropKnownKeysButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				removeSelectedEntries(knownSshKeysTable, existingKeys, configuredKeys, knownKeys);

				loadTablesData();
			}
		});
		final JPanel removeButton3Panel = new JPanel(new GridLayout(1, 1));
		dropKnownKeysButton.setAlignmentX(Component.CENTER_ALIGNMENT);
		removeButton3Panel.add(dropKnownKeysButton);
		tables3Panel.add(removeButton3Panel);

		tablesPanel.add(tables1Panel);
		tablesPanel.add(buttonPanel1);
		tablesPanel.add(tables2Panel);
		tablesPanel.add(buttonPanel2);
		tablesPanel.add(tables3Panel);

		loadTablesData();

		// Button Panel
		final JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));

		// ok Button
		okButton = new JButton(getI18NString("save"));
		okButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent event) {
				returnValue = true;
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
				returnValue = false;
				dispose();
			}
		});
		buttonPanel.add(closeButton);

		panel.add(tablesPanel);
		panel.add(Box.createRigidArea(new Dimension(5, 5)));
		panel.add(buttonPanel);
		getRootPane().setDefaultButton(okButton);

		panel.add(Box.createRigidArea(new Dimension(0, 5)));

		add(panel);

		pack();

		setLocationRelativeTo(getOwner());

		checkButtonStatus();
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
					|| 0 == new QuestionDialog(this, ServerManagement.APPLICATION_NAME, LangResources.get("adminKeyRemoval"), LangResources.get("yes"), LangResources.get("no")).withBackgroundColor(SwingColor.Yellow).open()) {
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
				default: pattern = "MessageKey unbekannt: " + resourceKey + (arguments != null && arguments.length > 0 ? " Argumente: " + Utilities.join(arguments, ", ") : "");
			}
			return MessageFormat.format(pattern, arguments);
		} else {
			String pattern;
			switch(resourceKey) {
				case "username": pattern = "Username"; break;
				case "password": pattern = "Password"; break;
				case "ok": pattern = "OK"; break;
				case "cancel": pattern = "Cancel"; break;
				default: pattern = "MessageKey unknown: " + resourceKey + (arguments != null && arguments.length > 0 ? " arguments: " + Utilities.join(arguments, ", ") : "");
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
