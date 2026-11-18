package de.soderer.servermanagement.location;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed version location "ssh-cmd://[user[:password]@][host][:port] &lt;command&gt;". Connection and command are
 * separated by the first whitespace, so the command may contain any characters (":", "/", "@", pipes, quotes).
 *
 * Supported e.g.: "ssh-cmd://andreas@homebase java -version", "ssh-cmd://andreas@homebase:2222 java -version",
 * "ssh-cmd://andreas:password@homebase java -version", "ssh-cmd:// java -version" (host and SSH admin of the server).
 *
 * @param username
 *            null if not given (SSH admin of the server)
 * @param password
 *            null if not given (authentication of the SSH admin)
 * @param host
 *            null if not given (host of the server)
 * @param port
 *            null if not given (port 22, or the port of the server host if no host is given)
 * @param command
 *            the command to execute
 */
public record SshCommandLocation(String username, String password, String host, Integer port, String command) {
	private static final Pattern CONNECTION_AND_COMMAND_PATTERN = Pattern.compile("^(\\S*)\\s+(.+)$", Pattern.DOTALL);

	public static SshCommandLocation parse(final String location) throws Exception {
		if (location == null || !location.trim().toLowerCase().startsWith("ssh-cmd://")) {
			throw new Exception("Invalid SSH command location, expected 'ssh-cmd://[user[:password]@][host][:port] <command>'");
		}
		final Matcher matcher = CONNECTION_AND_COMMAND_PATTERN.matcher(location.trim().substring(10));
		if (!matcher.matches() || matcher.group(2).isBlank()) {
			throw new Exception("Missing command in SSH command location, expected 'ssh-cmd://[user[:password]@][host][:port] <command>'");
		}
		final String connection = matcher.group(1);
		final String command = matcher.group(2).trim();

		// User info: up to the last "@" (the password may contain "@" and ":", but no whitespace)
		final int atIndex = connection.lastIndexOf('@');
		String username = null;
		String password = null;
		if (atIndex >= 0) {
			final String userInfo = connection.substring(0, atIndex);
			final int colonIndex = userInfo.indexOf(':');
			username = colonIndex >= 0 ? userInfo.substring(0, colonIndex) : userInfo;
			password = colonIndex >= 0 ? userInfo.substring(colonIndex + 1) : null;
			if (username.isEmpty()) {
				username = null;
			}
		}
		final String hostAndPort = connection.substring(atIndex + 1);

		String host = hostAndPort;
		String portText = null;
		if (hostAndPort.startsWith("[")) {
			final int closingBracketIndex = hostAndPort.indexOf(']');
			if (closingBracketIndex < 0) {
				throw new Exception("Invalid IPv6 host in SSH command location: " + hostAndPort);
			}
			host = hostAndPort.substring(1, closingBracketIndex);
			final String rest = hostAndPort.substring(closingBracketIndex + 1);
			if (rest.startsWith(":")) {
				portText = rest.substring(1);
			} else if (!rest.isEmpty()) {
				throw new Exception("Invalid host in SSH command location: " + hostAndPort);
			}
		} else if (hostAndPort.indexOf(':') >= 0 && hostAndPort.indexOf(':') == hostAndPort.lastIndexOf(':')) {
			// Exactly one ":" is "host:port", several ":" are a plain IPv6 address
			host = hostAndPort.substring(0, hostAndPort.indexOf(':'));
			portText = hostAndPort.substring(hostAndPort.indexOf(':') + 1);
		}
		if (host.isEmpty()) {
			host = null;
		}

		Integer port = null;
		if (portText != null && !portText.isEmpty()) {
			if (!portText.matches("\\d{1,5}") || Integer.parseInt(portText) < 1 || Integer.parseInt(portText) > 65535) {
				throw new Exception("Invalid port '" + portText + "' in SSH command location");
			}
			port = Integer.parseInt(portText);
		}
		return new SshCommandLocation(username, password, host, port, command);
	}

	@Override
	public String toString() {
		// The password must never appear in log output
		final String hostText = host == null ? "" : host.contains(":") ? "[" + host + "]" : host;
		return "ssh-cmd://" + (username == null ? "" : username + (password == null ? "" : ":***") + "@") + hostText + (port == null ? "" : ":" + port) + " " + command;
	}
}
