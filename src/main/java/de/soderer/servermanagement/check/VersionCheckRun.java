package de.soderer.servermanagement.check;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import de.soderer.network.HttpMethod;
import de.soderer.network.HttpRequest;
import de.soderer.network.HttpResponse;
import de.soderer.network.OidcLogin;
import de.soderer.remote.SftpConnection;
import de.soderer.servermanagement.AuthorizedKeysHelper;
import de.soderer.servermanagement.config.Admin;
import de.soderer.servermanagement.config.Login;
import de.soderer.servermanagement.config.LoginType;
import de.soderer.servermanagement.config.Server;
import de.soderer.servermanagement.config.Version;
import de.soderer.servermanagement.http.HttpAccess;
import de.soderer.servermanagement.http.JenkinsBuild;
import de.soderer.servermanagement.http.JenkinsClient;
import de.soderer.servermanagement.http.JenkinsCrumbIssuerNotAvailableException;
import de.soderer.servermanagement.http.OidcClient;
import de.soderer.servermanagement.location.HttpLoginLocation;
import de.soderer.servermanagement.location.SshCommandLocation;
import de.soderer.servermanagement.location.SshLocation;
import de.soderer.utilities.IoUtilities;
import de.soderer.utilities.StringUtilities;

/**
 * One version check of a server. Several versions are often read from the same location (e.g. one JSON response or one
 * file with several versions), so within one check each location is read only once, each login is executed only once
 * and SSH connections are reused; also failures are cached, so they are not repeated for each version. Close the check
 * to close its SSH connections.
 *
 * Supported version locations:
 * <ul>
 * <li>"http(s)://host/path": GET without login</li>
 * <li>"[&lt;GET|POST&gt;:]http(s)://username@host/path": request with the login of the username (BasicAuth or
 * OIDC)</li>
 * <li>"[GET:]http(s)://username@host/job/&lt;component&gt;/job/&lt;branch&gt;/job/&lt;build job&gt;" with a Jenkins
 * login: latest successful build of the Jenkins job</li>
 * <li>"ssh://[user[:password]@][host][:port]/path": file content, or file list for a path ending with "/"</li>
 * <li>"ssh-cmd://[user[:password]@][host][:port] &lt;command&gt;": command output</li>
 * <li>"ssh-cmd:[user[:password]@host:]&lt;command&gt;": old syntax of the command output</li>
 * </ul>
 */
public class VersionCheckRun implements AutoCloseable {
	private final Server server;
	private final HttpAccess httpAccess;
	private final OidcClient oidcClient;
	private final JenkinsClient jenkinsClient;

	/** Data per location; a failed location is cached as null */
	private final Map<String, String> locationDataCache = new HashMap<>();
	/** OIDC session or error per login */
	private final Map<Login, OidcLoginAttempt> oidcLoginCache = new HashMap<>();
	/** Open SSH connections per user, host and port */
	private final Map<String, SftpConnection> sftpConnections = new HashMap<>();
	/** SSH connections that failed to open, they are not tried again */
	private final Map<String, Exception> failedSftpConnections = new HashMap<>();
	/** Jenkins crumb or error per login */
	private final Map<Login, JenkinsCrumbAttempt> jenkinsCrumbCache = new HashMap<>();
	/** Jenkins build or error per login and job */
	private final Map<String, JenkinsBuildAttempt> jenkinsBuildCache = new HashMap<>();

	/** Result of an OIDC login: the session or the error */
	private record OidcLoginAttempt(OidcLogin.LoginSession session, Exception error) {
	}

	/** Result of a Jenkins crumb request: the crumb (null if the server has no crumb issuer) or the error */
	private record JenkinsCrumbAttempt(String crumb, Exception error) {
	}

	/** Result of a Jenkins build request: the build (null if none) or the error */
	private record JenkinsBuildAttempt(JenkinsBuild build, Exception error) {
	}

	/** Version location of a Jenkins job with its Jenkins login */
	private record JenkinsJob(HttpLoginLocation location, Login login) {
	}

	/**
	 * Effective data of an SSH connection for a version location.
	 */
	private record SshConnectionData(String host, int port, String username, File privateKeyFile, char[] password) {
		/**
		 * Key for reusing the open connection; the credentials are not part of it, so the same user, host and port share
		 * one connection.
		 */
		String cacheKey() {
			return username + "@" + host + ":" + port;
		}

		SftpConnection open() throws Exception {
			return AuthorizedKeysHelper.openSftpConnection(host, port, username, privateKeyFile, password);
		}

		@Override
		public String toString() {
			// The password must never appear in log output
			return cacheKey();
		}
	}

	public VersionCheckRun(final Server server, final HttpAccess httpAccess, final OidcClient oidcClient, final JenkinsClient jenkinsClient) {
		this.server = server;
		this.httpAccess = httpAccess;
		this.oidcClient = oidcClient;
		this.jenkinsClient = jenkinsClient;
	}

	/**
	 * Checks all versions of the server. Each version separately, so a failing SSH connection, an invalid regex or a
	 * missing property does not abort the check of further versions.
	 *
	 * @return the results in the order of the versions
	 */
	public List<VersionResult> run() {
		final List<VersionResult> results = new ArrayList<>();
		if (server.getVersions() != null) {
			for (final Version version : server.getVersions()) {
				results.add(check(version));
			}
		}
		return results;
	}

	/**
	 * Checks one version; any problem results in "ERROR".
	 */
	public VersionResult check(final Version version) {
		try {
			// Locations referencing a Jenkins login show the latest successful build of the Jenkins job
			final JenkinsJob jenkinsJob = getJenkinsJob(version);
			if (jenkinsJob != null) {
				return checkJenkinsVersion(version, jenkinsJob);
			}

			final String locationKey = getLocationCacheKey(version);
			final String data;
			if (locationDataCache.containsKey(locationKey)) {
				data = locationDataCache.get(locationKey);
			} else {
				String readData;
				try {
					readData = readLocationData(version);
				} catch (@SuppressWarnings("unused") final Exception e) {
					// Invalid location: shown as ERROR
					readData = null;
				}
				locationDataCache.put(locationKey, readData);
				data = readData;
			}

			if (data == null) {
				return VersionResult.error(version.getName(), "No data read from location");
			}
			return new VersionResult(version.getName(), VersionPathEvaluator.evaluate(data, version.getPath()), StatusLevel.OK);
		} catch (final Exception e) {
			return VersionResult.error(version.getName(), e.getMessage());
		}
	}

	/**
	 * Closes the SSH connections of this check.
	 */
	@Override
	public void close() {
		for (final SftpConnection sftpConnection : sftpConnections.values()) {
			try {
				sftpConnection.close();
			} catch (@SuppressWarnings("unused") final Exception e) {
				// Closing errors are irrelevant for the result
			}
		}
		sftpConnections.clear();
	}

	/**
	 * Key of the data of a version location for the location cache. Login locations with a JSON path are requested with
	 * "Content-Type: application/json", so the content type is part of their key.
	 */
	private static String getLocationCacheKey(final Version version) {
		final String location = version.getLocation().trim();
		if (HttpLoginLocation.matches(location) && VersionPathEvaluator.isJsonPath(version.getPath())) {
			return location + " [json]";
		} else {
			return location;
		}
	}

	/**
	 * Reads the data of a version location (HTTP with or without login, SSH file or directory, SSH command).
	 *
	 * @return the data or null, if reading failed
	 * @throws Exception
	 *             if the location itself is invalid
	 */
	private String readLocationData(final Version version) throws Exception {
		final String location = version.getLocation().trim();
		if (HttpLoginLocation.matches(location)) {
			try {
				return readWithLogin(HttpLoginLocation.parse(location), VersionPathEvaluator.isJsonPath(version.getPath()));
			} catch (@SuppressWarnings("unused") final Exception e) {
				return null;
			}
		} else if (location.toLowerCase().startsWith("http://") || location.toLowerCase().startsWith("https://")) {
			// URL without username: GET request without login
			try {
				final HttpRequest request = new HttpRequest(HttpMethod.GET, location);
				final HttpResponse response = httpAccess.execute(request, httpAccess.getApplicationProxy(request.getUrl()));
				if (response.getHttpCode() == 200) {
					return response.getContent();
				} else {
					return null;
				}
			} catch (@SuppressWarnings("unused") final Exception e) {
				return null;
			}
		} else if (location.toLowerCase().startsWith("ssh://")) {
			final SshLocation sshLocation = SshLocation.parse(location);
			final SshConnectionData connectionData = resolveSshConnectionData(sshLocation.username(), sshLocation.password(), sshLocation.host(), sshLocation.port());
			try {
				final SftpConnection sftpConnection = getSftpConnection(connectionData.cacheKey(), connectionData::open);
				if (sshLocation.isDirectory()) {
					return StringUtilities.join(sftpConnection.listFiles(sshLocation.path()), "\n");
				} else {
					try (InputStream inputStream = sftpConnection.downloadFile(sshLocation.path())) {
						return new String(IoUtilities.toByteArray(inputStream), StandardCharsets.UTF_8);
					}
				}
			} catch (@SuppressWarnings("unused") final Exception e) {
				return null;
			}
		} else if (location.toLowerCase().startsWith("ssh-cmd://")) {
			final SshCommandLocation sshCommandLocation = SshCommandLocation.parse(location);
			final SshConnectionData connectionData = resolveSshConnectionData(sshCommandLocation.username(), sshCommandLocation.password(), sshCommandLocation.host(), sshCommandLocation.port());
			try {
				final SftpConnection sftpConnection = getSftpConnection(connectionData.cacheKey(), connectionData::open);
				return sftpConnection.executeCommand(sshCommandLocation.command());
			} catch (@SuppressWarnings("unused") final Exception e) {
				return null;
			}
		} else if (location.toLowerCase().startsWith("ssh-cmd:")) {
			return readOldSshCommandLocation(location);
		} else {
			return null;
		}
	}

	/**
	 * Old syntax "ssh-cmd:[user[:password]@host:]&lt;command&gt;" (the command must not contain ":").
	 */
	private String readOldSshCommandLocation(final String location) throws Exception {
		String hostnameForSsh = server.getHost();
		String usernameForSsh = server.getSshAdmin().getUsername();
		File privateKeyFileForSsh = server.getSshAdmin().getPrivateKeyFile();
		char[] passwordForSsh = server.getSshAdmin().getPasswordArray();

		String command = location.substring(8);

		if (command.contains(":")) {
			hostnameForSsh = command.substring(0, command.lastIndexOf(":"));
			command = command.substring(command.lastIndexOf(":") + 1);
		}

		if (hostnameForSsh.contains("@")) {
			usernameForSsh = hostnameForSsh.substring(0, hostnameForSsh.lastIndexOf("@"));
			hostnameForSsh = hostnameForSsh.substring(hostnameForSsh.lastIndexOf("@") + 1);
		}

		if (usernameForSsh.contains(":")) {
			privateKeyFileForSsh = null;
			passwordForSsh = usernameForSsh.substring(usernameForSsh.lastIndexOf(":") + 1).toCharArray();
			usernameForSsh = usernameForSsh.substring(0, usernameForSsh.lastIndexOf(":"));
		}

		final String sftpConnectionString = usernameForSsh + "@" + hostnameForSsh;
		final String effectiveHostname = hostnameForSsh;
		final String effectiveUsername = usernameForSsh;
		final File effectivePrivateKeyFile = privateKeyFileForSsh;
		final char[] effectivePassword = passwordForSsh;
		try {
			// Own key prefix: the old syntax opens the connection differently (port within the host)
			final SftpConnection sftpConnection = getSftpConnection("old-syntax:" + sftpConnectionString,
					() -> AuthorizedKeysHelper.openSftpConnection(effectiveHostname, effectiveUsername, effectivePrivateKeyFile, effectivePassword));
			return sftpConnection.executeCommand(command);
		} catch (@SuppressWarnings("unused") final Exception e) {
			return null;
		}
	}

	/**
	 * Requests a version location with login: logs in with the login of the username and requests the URL (without the
	 * username) with the session (cookies and CSRF token for OIDC, Basic authentication for BasicAuth logins).
	 *
	 * @param jsonRequest
	 *            true to send the header "Content-Type: application/json" (for version paths "json:...")
	 * @return the response content
	 * @throws Exception
	 *             if the login fails or the request does not return HTTP 2xx
	 */
	private String readWithLogin(final HttpLoginLocation loginLocation, final boolean jsonRequest) throws Exception {
		final Login login = findLoginByUsername(loginLocation.username(), loginLocation.targetUri());

		if (login.getLoginType() == LoginType.OIDC) {
			OidcLoginAttempt loginAttempt = oidcLoginCache.get(login);
			if (loginAttempt == null) {
				try {
					loginAttempt = new OidcLoginAttempt(oidcClient.login(login), null);
				} catch (final Exception e) {
					loginAttempt = new OidcLoginAttempt(null, e);
				}
				// Cached even if the login failed, so it is not repeated for each version
				oidcLoginCache.put(login, loginAttempt);
			}
			if (loginAttempt.error() != null) {
				throw loginAttempt.error();
			}
			return OidcClient.request(loginAttempt.session(), loginLocation.httpMethod(), loginLocation.targetUri(), jsonRequest);
		} else {
			// BasicAuth (Jenkins logins are handled by checkJenkinsVersion before)
			final HttpRequest request = new HttpRequest(loginLocation.isPost() ? HttpMethod.POST : HttpMethod.GET, loginLocation.url());
			if (jsonRequest) {
				request.addHeader("Content-Type", "application/json");
			}
			HttpAccess.addBasicAuthorization(request, login);
			final HttpResponse response = httpAccess.execute(request, HttpAccess.getSessionProxy(login, loginLocation.targetUri()));
			if (response.getHttpCode() < 200 || response.getHttpCode() >= 300) {
				throw new Exception("HTTP " + response.getHttpCode() + " for " + loginLocation.httpMethod() + " " + loginLocation.url() + "\nResponse: " + HttpAccess.shorten(response.getContent(), 500));
			}
			return response.getContent();
		}
	}

	/**
	 * Finds the login of the server by its username. With several logins of the same username the login on the host of
	 * the target URL is preferred.
	 */
	private Login findLoginByUsername(final String username, final URI targetUri) throws Exception {
		Login firstMatchingLogin = null;
		if (server.getLogins() != null) {
			for (final Login login : server.getLogins()) {
				if (username.equals(login.getUsername())) {
					if (firstMatchingLogin == null) {
						firstMatchingLogin = login;
					}
					try {
						final URI loginUri = URI.create(login.getUrl());
						if (loginUri.getHost() != null && loginUri.getHost().equalsIgnoreCase(targetUri.getHost())) {
							return login;
						}
					} catch (@SuppressWarnings("unused") final Exception e) {
						// Invalid login URL, the login may still be used as fallback
					}
				}
			}
		}
		if (firstMatchingLogin == null) {
			throw new Exception("No login with username '" + username + "' configured for server '" + server.getName() + "'");
		}
		return firstMatchingLogin;
	}

	/**
	 * Returns the Jenkins job of a version location "[GET:]https://&lt;jenkins login username&gt;@&lt;host&gt;/job/..."
	 * (the job URL as shown in the browser, with the username of a Jenkins login of the server).
	 *
	 * @return the job, or null if the location does not reference a Jenkins login (then the version is read as usual)
	 */
	private JenkinsJob getJenkinsJob(final Version version) {
		if (version.getLocation() == null || !HttpLoginLocation.matches(version.getLocation().trim())) {
			return null;
		}
		try {
			final HttpLoginLocation loginLocation = HttpLoginLocation.parse(version.getLocation().trim());
			final Login login = findLoginByUsername(loginLocation.username(), loginLocation.targetUri());
			return login.getLoginType() == LoginType.Jenkins ? new JenkinsJob(loginLocation, login) : null;
		} catch (@SuppressWarnings("unused") final Exception e) {
			// Invalid location or unknown login: shown as ERROR by the normal version check
			return null;
		}
	}

	/**
	 * Version of a Jenkins job: with an empty version path the build number and build time of the latest successful
	 * build (e.g. "#123 (2026-11-18 10:40)"), a path "json:..." is applied to the build data {"number", "timestamp",
	 * "buildTime", "mostCurrent"} (e.g. "json:$.number"). If a newer build failed, the version is a warning.
	 */
	private VersionResult checkJenkinsVersion(final Version version, final JenkinsJob jenkinsJob) throws Exception {
		if (jenkinsJob.location().isPost()) {
			throw new Exception("POST is not supported for Jenkins version locations");
		}

		final Login login = jenkinsJob.login();
		final String buildCacheKey = login.getUsername() + "@" + jenkinsJob.location().url();
		JenkinsBuildAttempt buildAttempt = jenkinsBuildCache.get(buildCacheKey);
		if (buildAttempt == null) {
			try {
				final String crumb = getJenkinsCrumb(login);
				buildAttempt = new JenkinsBuildAttempt(jenkinsClient.getLatestSuccessfulBuild(login, jenkinsJob.location().targetUri(), crumb), null);
			} catch (final Exception e) {
				buildAttempt = new JenkinsBuildAttempt(null, e);
			}
			jenkinsBuildCache.put(buildCacheKey, buildAttempt);
		}
		if (buildAttempt.error() != null) {
			throw buildAttempt.error();
		}

		final JenkinsBuild build = buildAttempt.build();
		if (build == null) {
			return VersionResult.error(version.getName(), "No successful build of Jenkins job " + JenkinsClient.getJobUrl(jenkinsJob.location().targetUri()));
		}

		final String versionText;
		if (StringUtilities.isBlank(version.getPath())) {
			versionText = build.toDisplayText();
		} else if (VersionPathEvaluator.isJsonPath(version.getPath())) {
			versionText = VersionPathEvaluator.evaluate(build.toJson(), version.getPath());
		} else {
			throw new Exception("Unsupported version path for Jenkins location, expected empty path or 'json:...'");
		}
		return new VersionResult(version.getName(), versionText, build.isMostCurrent() ? StatusLevel.OK : StatusLevel.WARNING);
	}

	/**
	 * Crumb of a Jenkins login, requested once per check.
	 *
	 * @return the crumb, or null if the server has no crumb issuer (GET requests do not need one)
	 * @throws Exception
	 *             if the login was rejected or the crumb request failed
	 */
	private String getJenkinsCrumb(final Login login) throws Exception {
		JenkinsCrumbAttempt crumbAttempt = jenkinsCrumbCache.get(login);
		if (crumbAttempt == null) {
			try {
				final String crumb = jenkinsClient.getAuthCrumb(login);
				crumbAttempt = crumb != null ? new JenkinsCrumbAttempt(crumb, null)
						: new JenkinsCrumbAttempt(null, new Exception("Jenkins login of user '" + login.getUsername() + "' was rejected"));
			} catch (@SuppressWarnings("unused") final JenkinsCrumbIssuerNotAvailableException e) {
				crumbAttempt = new JenkinsCrumbAttempt(null, null);
			} catch (final Exception e) {
				crumbAttempt = new JenkinsCrumbAttempt(null, e);
			}
			jenkinsCrumbCache.put(login, crumbAttempt);
		}
		if (crumbAttempt.error() != null) {
			throw crumbAttempt.error();
		}
		return crumbAttempt.crumb();
	}

	/**
	 * Returns an open SSH connection of this check or opens it. A connection that failed to open is not tried again.
	 */
	private SftpConnection getSftpConnection(final String key, final Callable<SftpConnection> opener) throws Exception {
		final SftpConnection openConnection = sftpConnections.get(key);
		if (openConnection != null) {
			return openConnection;
		}
		final Exception formerError = failedSftpConnections.get(key);
		if (formerError != null) {
			throw new Exception("SSH connection " + key + " already failed before: " + formerError.getMessage(), formerError);
		}
		final SftpConnection newConnection;
		try {
			newConnection = opener.call();
		} catch (final Exception e) {
			failedSftpConnections.put(key, e);
			throw e;
		}
		sftpConnections.put(key, newConnection);
		return newConnection;
	}

	/**
	 * Determines the SSH connection data of a version location. Missing parts are taken from the server and its SSH
	 * admin: without host the host (and port) of the server, without username the SSH admin, without password the
	 * authentication of the SSH admin. Without port, port 22 is used for an explicit host.
	 */
	private SshConnectionData resolveSshConnectionData(final String username, final String password, final String host, final Integer port) throws Exception {
		final String effectiveHost;
		final int effectivePort;
		if (host != null) {
			effectiveHost = host;
			effectivePort = port != null ? port : AuthorizedKeysHelper.DEFAULT_SSH_PORT;
		} else {
			// The host of the server may contain a port itself ("host:port")
			final AuthorizedKeysHelper.HostAndPort serverHostAndPort = AuthorizedKeysHelper.parseHostAndPort(server.getHost());
			effectiveHost = serverHostAndPort.host();
			effectivePort = port != null ? port : serverHostAndPort.port();
		}

		final Admin sshAdmin = server.getSshAdmin();
		final String effectiveUsername = username != null ? username : sshAdmin == null ? null : sshAdmin.getUsername();
		if (password != null) {
			// An explicit password replaces the key authentication of the SSH admin
			return new SshConnectionData(effectiveHost, effectivePort, effectiveUsername, null, password.toCharArray());
		} else if (sshAdmin == null) {
			throw new Exception("No SSH admin configured for server '" + server.getName() + "'");
		} else {
			return new SshConnectionData(effectiveHost, effectivePort, effectiveUsername, sshAdmin.getPrivateKeyFile(), sshAdmin.getPasswordArray());
		}
	}
}
