package de.soderer.servermanagement;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.soderer.remote.DirectoryListing;
import de.soderer.remote.FilePermissions;
import de.soderer.remote.FolderInfo;
import de.soderer.remote.SftpConnection;
import de.soderer.sshkeyformats.AuthorizedKey;
import de.soderer.sshkeyformats.SshKey;
import de.soderer.sshkeyformats.SshKeyReader;
import de.soderer.sshkeyformats.SshKeyWriter;
import de.soderer.utilities.Utilities;

public class AuthorizedKeysHelper {
	public static List<String> readUsernames(final String hostname, final String sshAccessUsername, final File sshAccessPrivateKeyFile, final char[] sshAccessPassword) throws Exception, Exception {
		try (SftpConnection sftpConnection = openSftpConnection(hostname, sshAccessUsername, sshAccessPrivateKeyFile, sshAccessPassword)) {
			final List<String> usernames = new ArrayList<>();
			final DirectoryListing directoryInfo = sftpConnection.getDirectory("/home");
			for (final FolderInfo homeFolder : directoryInfo.folders()) {
				usernames.add(homeFolder.getFolderName());
			}
			return usernames;
		}
	}

	public static List<AuthorizedKey> readAuthorizedKeys(final String hostname, final String sshAccessUsername, final File sshAccessPrivateKeyFile, final char[] sshAccessPassword, final String sshUserNameToRead) throws Exception, Exception {
		try (SftpConnection sftpConnection = openSftpConnection(hostname, sshAccessUsername, sshAccessPrivateKeyFile, sshAccessPassword)) {
			if (sftpConnection.getFileInfo("/home/" + sshUserNameToRead + "/.ssh/authorized_keys") == null) {
				if (sftpConnection.getFolderInfo("/home/" + sshUserNameToRead) == null) {
					throw new Exception("No such user '" + sshUserNameToRead + "'");
				} else {
					throw new Exception("No authorized_keys for user '" + sshUserNameToRead + "'");
				}
			} else {
				try (InputStream inputStream = sftpConnection.downloadFile("/home/" + sshUserNameToRead + "/.ssh/authorized_keys")) {
					final List<AuthorizedKey> returnList = new ArrayList<>();
					for (final SshKey key : SshKeyReader.readAllPublicKeys(inputStream)) {
						if (key instanceof AuthorizedKey) {
							returnList.add((AuthorizedKey) key);
						} else {
							throw new Exception("Found invalid authorized_key for user '" + sshUserNameToRead + "'");
						}
					}
					return returnList;
				}
			}
		}
	}

	public static void storeAuthorizedKeys(final String hostname, final String sshAccessUsername, final File sshAccessPrivateKeyFile, final char[] sshAccessPassword, final String sshUserNameToRead, final List<AuthorizedKey> authorizedKeys) throws Exception, Exception {
		final ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
		for (final AuthorizedKey key : authorizedKeys) {
			dataStream.write(key.toString().getBytes(StandardCharsets.UTF_8));
			dataStream.write("\n".getBytes(StandardCharsets.UTF_8));
		}
		try (SftpConnection sftpConnection = openSftpConnection(hostname, sshAccessUsername, sshAccessPrivateKeyFile, sshAccessPassword)) {
			sftpConnection.uploadFileData("/home/" + sshUserNameToRead + "/.ssh", "authorized_keys", dataStream.toByteArray(), new FilePermissions(true, true, false), true);
		}
	}

	/**
	 * Opens a SFTP connection. The hostname may contain a port ("host:port", default port 22).
	 * Also used by ServerManagementItem, so there is only one implementation of the host and port parsing.
	 */
	static SftpConnection openSftpConnection(String hostname, final String sshAccessUsername, final File sshAccessPrivateKeyFile, final char[] sshAccessPassword) throws Exception, IOException {
		if (Utilities.isBlank(hostname)) {
			throw new Exception("No host configured");
		} else if (Utilities.isBlank(sshAccessUsername)) {
			throw new Exception("No SSH admin configured for host '" + hostname + "'");
		}

		int port = 22;
		if (hostname.contains(":")) {
			// Before, lastIndexOf(":" + 1) searched for the text ":1", so every host with a port failed
			final String portText = hostname.substring(hostname.lastIndexOf(":") + 1).trim();
			try {
				port = Integer.parseInt(portText);
			} catch (@SuppressWarnings("unused") final NumberFormatException e) {
				throw new Exception("Invalid port '" + portText + "' for host '" + hostname + "'");
			}
			hostname = hostname.substring(0, hostname.lastIndexOf(":"));
		}

		if (sshAccessPrivateKeyFile != null) {
			byte[] sshKeyData;
			try (InputStream inputStream = new FileInputStream(sshAccessPrivateKeyFile)) {
				SshKey sshKey;
				if (Utilities.isNotBlank(sshAccessPassword)) {
					sshKey = SshKeyReader.readKey(inputStream, sshAccessPassword);
				} else {
					sshKey = SshKeyReader.readKey(inputStream, null);
				}
				final ByteArrayOutputStream keyDataStream = new ByteArrayOutputStream();
				SshKeyWriter.writePuttyVersion2Key(keyDataStream, sshKey, null);
				sshKeyData = keyDataStream.toByteArray();
			}
			return new SftpConnection(hostname, sshAccessUsername, sshKeyData, null, port, null);
		} else if (Utilities.isNotBlank(sshAccessPassword)) {
			return new SftpConnection(hostname, sshAccessUsername, null, sshAccessPassword, port, null);
		} else {
			throw new Exception("No supported authentication method available for host '" + hostname + "'");
		}
	}
}
