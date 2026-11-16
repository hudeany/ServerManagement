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
import de.soderer.utilities.StringUtilities;

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

	public static final int DEFAULT_SSH_PORT = 22;

	/**
	 * Host and SSH port, e.g. of a server host configuration "host:port".
	 */
	public record HostAndPort(String host, int port) {
	}

	/**
	 * Splits a host configuration into host and port: "host", "host:port", "[ipv6]", "[ipv6]:port" or a plain IPv6
	 * address (without port). Without port the default port 22 is used.
	 */
	public static HostAndPort parseHostAndPort(final String hostname) throws Exception {
		if (StringUtilities.isBlank(hostname)) {
			throw new Exception("No host configured");
		}
		final String value = hostname.trim();
		String host = value;
		String portText = null;
		if (value.startsWith("[")) {
			final int closingBracketIndex = value.indexOf(']');
			if (closingBracketIndex < 0) {
				throw new Exception("Invalid IPv6 host '" + hostname + "'");
			}
			host = value.substring(1, closingBracketIndex);
			final String rest = value.substring(closingBracketIndex + 1);
			if (rest.startsWith(":")) {
				portText = rest.substring(1).trim();
			} else if (!rest.isEmpty()) {
				throw new Exception("Invalid host '" + hostname + "'");
			}
		} else if (value.indexOf(':') >= 0 && value.indexOf(':') == value.lastIndexOf(':')) {
			// Exactly one ":" is "host:port", several ":" are a plain IPv6 address
			host = value.substring(0, value.indexOf(':')).trim();
			portText = value.substring(value.indexOf(':') + 1).trim();
		}

		int port = DEFAULT_SSH_PORT;
		if (portText != null) {
			try {
				port = Integer.parseInt(portText);
			} catch (@SuppressWarnings("unused") final NumberFormatException e) {
				throw new Exception("Invalid port '" + portText + "' for host '" + hostname + "'");
			}
		}
		if (port < 1 || port > 65535) {
			throw new Exception("Invalid port '" + port + "' for host '" + hostname + "'");
		}
		if (host.isEmpty()) {
			throw new Exception("No host configured");
		}
		return new HostAndPort(host, port);
	}

	/**
	 * Opens a SFTP connection. The hostname may contain a port ("host:port", default port 22).
	 * Also used by ServerManagementItem, so there is only one implementation of the host and port parsing.
	 */
	static SftpConnection openSftpConnection(final String hostname, final String sshAccessUsername, final File sshAccessPrivateKeyFile, final char[] sshAccessPassword) throws Exception, IOException {
		final HostAndPort hostAndPort = parseHostAndPort(hostname);
		return openSftpConnection(hostAndPort.host(), hostAndPort.port(), sshAccessUsername, sshAccessPrivateKeyFile, sshAccessPassword);
	}

	/**
	 * Opens a SFTP connection on an explicit port (the hostname must not contain a port).
	 */
	static SftpConnection openSftpConnection(final String hostname, final int port, final String sshAccessUsername, final File sshAccessPrivateKeyFile, final char[] sshAccessPassword) throws Exception, IOException {
		if (StringUtilities.isBlank(hostname)) {
			throw new Exception("No host configured");
		} else if (StringUtilities.isBlank(sshAccessUsername)) {
			throw new Exception("No SSH admin configured for host '" + hostname + "'");
		} else if (port < 1 || port > 65535) {
			throw new Exception("Invalid port '" + port + "' for host '" + hostname + "'");
		}

		if (sshAccessPrivateKeyFile != null) {
			byte[] sshKeyData;
			try (InputStream inputStream = new FileInputStream(sshAccessPrivateKeyFile)) {
				SshKey sshKey;
				if (StringUtilities.isNotBlank(sshAccessPassword)) {
					sshKey = SshKeyReader.readKey(inputStream, sshAccessPassword);
				} else {
					sshKey = SshKeyReader.readKey(inputStream, null);
				}
				final ByteArrayOutputStream keyDataStream = new ByteArrayOutputStream();
				SshKeyWriter.writePuttyVersion2Key(keyDataStream, sshKey, null);
				sshKeyData = keyDataStream.toByteArray();
			}
			return new SftpConnection(hostname, sshAccessUsername, sshKeyData, null, port, null);
		} else if (StringUtilities.isNotBlank(sshAccessPassword)) {
			return new SftpConnection(hostname, sshAccessUsername, null, sshAccessPassword, port, null);
		} else {
			throw new Exception("No supported authentication method available for host '" + hostname + "'");
		}
	}
}
