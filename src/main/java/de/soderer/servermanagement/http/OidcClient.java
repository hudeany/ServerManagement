package de.soderer.servermanagement.http;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import de.soderer.network.OidcLogin;
import de.soderer.servermanagement.config.Login;
import de.soderer.utilities.StringUtilities;

/**
 * OIDC login and requests within a logged in OIDC session (cookies and CSRF token).
 */
public class OidcClient {
	private final HttpAccess httpAccess;

	public OidcClient(final HttpAccess httpAccess) {
		this.httpAccess = httpAccess;
	}

	/**
	 * Executes the OIDC login of the given login configuration.
	 *
	 * @return the logged in session (cookies, CSRF token)
	 * @throws Exception
	 *             if the login failed, with the reason as message
	 */
	public OidcLogin.LoginSession login(final Login login) throws Exception {
		final URI startUri = HttpAccess.parseHttpUri(login.getUrl());
		final char[] passwordArray = login.getPasswordArray();
		final String password = passwordArray == null ? null : new String(passwordArray);
		final String idpSelection = StringUtilities.isBlank(login.getIdpSelection()) ? null : login.getIdpSelection();
		// Proxy of the login; null means system proxy settings
		final String proxyUrl = StringUtilities.isBlank(login.getProxyUrl()) ? null : login.getProxyUrl();

		// Same TLS trust as all other requests: configured keystore or default trust store of the JVM
		final OidcLogin check = new OidcLogin(Duration.ofSeconds(30), idpSelection, proxyUrl, httpAccess.getTrustManager());
		final OidcLogin.LoginSession session = check.login(startUri, login.getUsername(), password);
		if (session == null) {
			throw new Exception("OIDC login of user '" + login.getUsername() + "' at " + startUri + " failed: " + check.getLastFailureMessage());
		}
		return session;
	}

	/**
	 * Sends a request within a logged in session. If the server rejects the CSRF token (HTTP 403), a new token is
	 * requested and the request is retried once.
	 *
	 * @param jsonRequest
	 *            true to send the header "Content-Type: application/json"
	 * @return the response content
	 * @throws Exception
	 *             if the request does not return HTTP 2xx
	 */
	public static String request(final OidcLogin.LoginSession session, final String httpMethod, final URI targetUri, final boolean jsonRequest) throws Exception {
		final String csrfToken = session.getCsrfToken();
		OidcLogin.LoginSession.SessionResponse sessionResponse = executeSessionRequest(session, httpMethod, targetUri, csrfToken, jsonRequest);
		if (sessionResponse.statusCode() == 403) {
			// The token may be outdated or bound to the endpoint: request a new one and retry once
			final String newCsrfToken = session.fetchCsrfToken(targetUri);
			if (newCsrfToken != null && !newCsrfToken.equals(csrfToken)) {
				sessionResponse = executeSessionRequest(session, httpMethod, targetUri, newCsrfToken, jsonRequest);
			}
		}
		if (sessionResponse.statusCode() < 200 || sessionResponse.statusCode() >= 300) {
			throw new Exception("HTTP " + sessionResponse.statusCode() + " for " + httpMethod + " " + targetUri);
		}
		return sessionResponse.body();
	}

	/**
	 * Sends a request with the CSRF token of a logged in OIDC session. The request is sent by the HttpClient of the
	 * session, so TLS trust, proxy and cookies are the same as for the login (the "Cookie" header is set by the session).
	 */
	private static OidcLogin.LoginSession.SessionResponse executeSessionRequest(final OidcLogin.LoginSession session, final String httpMethod, final URI targetUri, final String csrfToken, final boolean jsonRequest) throws Exception {
		final Map<String, String> headers = new LinkedHashMap<>();
		if (jsonRequest) {
			headers.put("Content-Type", "application/json");
		}
		if (csrfToken != null) {
			headers.put("X-CSRF-TOKEN", csrfToken);
		}
		// Same headers as the web application itself sends for its REST calls
		final URI finalPageUri = session.getFinalPageUri();
		headers.put("X-Requested-With", "XMLHttpRequest");
		headers.put("Accept", "application/json, text/plain, */*");
		headers.put("Origin", finalPageUri.getScheme() + "://" + finalPageUri.getRawAuthority());
		headers.put("Referer", finalPageUri.toString());
		return session.send(httpMethod, targetUri, headers, null);
	}
}
