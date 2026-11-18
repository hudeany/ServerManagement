package de.soderer.servermanagement.location;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.soderer.servermanagement.http.HttpAccess;

/**
 * Parsed version location with login: "[&lt;GET|POST&gt;:]&lt;http(s)://username@host/path&gt;", e.g.
 * "POST:https://monitor@host/cid/ui/gwt/rest/versions/getVersionData" or "https://monitor@host/version.json" (GET
 * without method prefix). The username identifies the login of the server; special characters in the username must be
 * URL encoded (e.g. "@" as "%40"). URLs without username are requested without login.
 *
 * @param httpMethod
 *            "GET" or "POST" (GET without method prefix)
 * @param username
 *            the decoded username of the login, e.g. "max@firma.de" for "max%40firma.de"
 * @param url
 *            the URL without username (the username is only for the login lookup and is not sent to the server)
 * @param targetUri
 *            the URL without username as URI
 */
public record HttpLoginLocation(String httpMethod, String username, String url, URI targetUri) {
	private static final Pattern LOGIN_LOCATION_PATTERN = Pattern.compile("^\\s*(?:(GET|POST)\\s*:\\s*)?(https?://[^/\\s?#]*@\\S+)\\s*$", Pattern.CASE_INSENSITIVE);

	/**
	 * True if the location is a URL with username (and optional method prefix).
	 */
	public static boolean matches(final String location) {
		return location != null && LOGIN_LOCATION_PATTERN.matcher(location).matches();
	}

	/**
	 * @throws Exception
	 *             if the location has no username, contains a password or is no valid http(s) URL
	 */
	public static HttpLoginLocation parse(final String location) throws Exception {
		final Matcher matcher = location == null ? null : LOGIN_LOCATION_PATTERN.matcher(location);
		if (matcher == null || !matcher.matches()) {
			throw new Exception("Invalid version location with login, expected '[<GET|POST>:]<http(s)://username@host/path>'");
		}
		// Without method prefix a GET request is used
		final String httpMethod = matcher.group(1) != null ? matcher.group(1).toUpperCase() : "GET";
		final String urlWithUsername = matcher.group(2);

		final URI uriWithUsername = HttpAccess.parseHttpUri(urlWithUsername);
		final String rawUserInfo = uriWithUsername.getRawUserInfo();
		if (rawUserInfo == null || rawUserInfo.isEmpty()) {
			throw new Exception("Missing username in version location URL (e.g. https://monitor@host/path)");
		}
		if (rawUserInfo.contains(":")) {
			// A password must never be stored as plain text in the version location
			throw new Exception("Password in version location URL is not supported, the password is taken from the login");
		}
		// Decodes URL encoded characters, e.g. "max%40firma.de" to "max@firma.de"
		final String username = uriWithUsername.getUserInfo();
		final String url = urlWithUsername.trim().replaceFirst("://" + Pattern.quote(rawUserInfo) + "@", "://");
		return new HttpLoginLocation(httpMethod, username, url, HttpAccess.parseHttpUri(url));
	}

	public boolean isPost() {
		return "POST".equals(httpMethod);
	}
}
