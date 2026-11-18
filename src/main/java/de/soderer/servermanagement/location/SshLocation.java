package de.soderer.servermanagement.location;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed version location "ssh://[user[:password]@][host][:port]/path" or "ssh://[user[:password]@][host]:path"
 * (scp style, the path may be relative to the home directory of the user).
 *
 * Supported e.g.: "ssh://andreas@homebase/etc/os-release", "ssh://andreas@homebase:/etc/os-release",
 * "ssh://andreas@homebase:22/etc/os-release", "ssh://andreas:password@homebase/etc/os-release",
 * "ssh://andreas@homebase:version.txt", "ssh:///etc/os-release" (host and user of the server).
 *
 * @param username
 *            null if not given (SSH admin of the server)
 * @param password
 *            null if not given (authentication of the SSH admin)
 * @param host
 *            null if not given (host of the server)
 * @param port
 *            null if not given (port 22, or the port of the server host if no host is given)
 * @param path
 *            path of the file, a path ending with "/" is a directory
 */
public record SshLocation(String username, String password, String host, Integer port, String path) {
	private static final Pattern PORT_AND_PATH_PATTERN = Pattern.compile("^(\\d{1,5})(/.*)$");

	public static SshLocation parse(final String location) throws Exception {
		if (location == null || !location.toLowerCase().startsWith("ssh://")) {
			throw new Exception("Invalid SSH location, expected 'ssh://[user[:password]@][host][:port]/path'");
		}
		final String rest = location.trim().substring(6);

		// User info: up to the last "@" before the path (the password may contain "@", but not "/")
		final int firstSlashIndex = rest.indexOf('/');
		final String authorityArea = firstSlashIndex >= 0 ? rest.substring(0, firstSlashIndex) : rest;
		final int atIndex = authorityArea.lastIndexOf('@');
		String username = null;
		String password = null;
		if (atIndex >= 0) {
			final String userInfo = rest.substring(0, atIndex);
			// The username cannot contain ":", so the password may contain ":"
			final int colonIndex = userInfo.indexOf(':');
			username = colonIndex >= 0 ? userInfo.substring(0, colonIndex) : userInfo;
			password = colonIndex >= 0 ? userInfo.substring(colonIndex + 1) : null;
			if (username.isEmpty()) {
				username = null;
			}
		}
		final String hostPortPath = rest.substring(atIndex + 1);

		// Host, also as IPv6 address in brackets
		final int hostEndIndex;
		if (hostPortPath.startsWith("[")) {
			final int closingBracketIndex = hostPortPath.indexOf(']');
			if (closingBracketIndex < 0) {
				throw new Exception("Invalid IPv6 host in SSH location: " + location);
			}
			hostEndIndex = closingBracketIndex + 1;
		} else {
			int index = 0;
			while (index < hostPortPath.length() && hostPortPath.charAt(index) != ':' && hostPortPath.charAt(index) != '/') {
				index++;
			}
			hostEndIndex = index;
		}
		String host = hostPortPath.substring(0, hostEndIndex);
		if (host.startsWith("[") && host.endsWith("]")) {
			host = host.substring(1, host.length() - 1);
		}
		if (host.isEmpty()) {
			host = null;
		}

		// Port and path
		Integer port = null;
		String path;
		final String afterHost = hostPortPath.substring(hostEndIndex);
		if (afterHost.startsWith(":")) {
			final String afterColon = afterHost.substring(1);
			final Matcher portMatcher = PORT_AND_PATH_PATTERN.matcher(afterColon);
			if (portMatcher.matches()) {
				// "host:22/path"
				port = Integer.parseInt(portMatcher.group(1));
				path = portMatcher.group(2);
			} else if (afterColon.matches("\\d{1,5}")) {
				throw new Exception("Missing path after port in SSH location: " + location);
			} else {
				// "host:/path" or scp style "host:relative/path"
				path = afterColon;
			}
		} else {
			// "host/path" or "/path"
			path = afterHost;
		}

		if (path.isEmpty()) {
			throw new Exception("Missing path in SSH location: " + location);
		}
		if (port != null && (port < 1 || port > 65535)) {
			throw new Exception("Invalid port " + port + " in SSH location: " + location);
		}
		return new SshLocation(username, password, host, port, path);
	}

	/**
	 * True if the path is a directory (ends with "/"), whose file list is read instead of a file content.
	 */
	public boolean isDirectory() {
		return path.endsWith("/");
	}

	@Override
	public String toString() {
		// The password must never appear in log output
		final String hostText = host == null ? "" : host.contains(":") ? "[" + host + "]" : host;
		// Relative paths are relative to the home directory of the user
		final String pathText = path.startsWith("/") ? path : "/~/" + path;
		return "ssh://" + (username == null ? "" : username + (password == null ? "" : ":***") + "@") + hostText + (port == null ? "" : ":" + port) + pathText;
	}
}
