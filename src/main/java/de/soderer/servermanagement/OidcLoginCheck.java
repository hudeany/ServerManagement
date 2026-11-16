package de.soderer.servermanagement;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import de.soderer.network.HttpMethod;
import de.soderer.network.HttpRequest;
import de.soderer.network.HttpResponse;
import de.soderer.network.HttpUtilities;
import de.soderer.network.HttpVersion;

/**
 * Smoke test for web applications protected by an OIDC login (authorization code flow).
 *
 * Behaves like a simple browser without JavaScript: follows redirects, keeps cookies,
 * fills and submits the login form of the identity provider (username/password, also
 * two-step forms), submits auto-post forms (response_mode=form_post) and finally checks
 * whether the main page of the web application is delivered successfully.
 *
 * A successful {@link #check(URI, String, String, String)} returns a {@link LoginSession} for further requests:
 * {@link LoginSession#getCookieHeader(URI)} delivers the "Cookie" header value and
 * {@link LoginSession#getCsrfToken()} the CSRF token (header "X-CSRF-TOKEN") of the logged in session.
 *
 * The requests are sent with the network library (de.soderer.network), Java 17+.
 */
public class OidcLoginCheck {
	private static final int MAX_REDIRECTS = 25;
	private static final int MAX_FORM_SUBMITS = 10;
	private static final String USER_AGENT = "Mozilla/5.0 (OidcLoginCheck) Gecko/20100101 Firefox/130.0";
	private static final Pattern USERNAME_FIELD_PATTERN = Pattern.compile("(?i).*(user|login|email|mail|identifier|account|uid).*");
	private static final Pattern META_REFRESH_URL_PATTERN = Pattern.compile("(?is)<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*content\\s*=\\s*[\"'][^\"']*url\\s*=\\s*([^\"'>;\\s]+)[\"']");
	private static final Pattern JS_LOCATION_PATTERN = Pattern.compile("(?is)(?:window\\.)?location(?:\\.href)?\\s*=\\s*['\"]([^'\"]+)['\"]");
	private static final Pattern LOGIN_ACTION_PATTERN = Pattern.compile("(?is)\"loginAction\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern PROVIDER_PAIR_PATTERN = Pattern.compile("(?is)\"displayName\"\\s*:\\s*\"([^\"]+)\".{0,1500}?\"loginUrl\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern ANCHOR_PATTERN = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a>");
	private static final Pattern HREF_ATTR_PATTERN = Pattern.compile("(?is)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
	private static final Pattern SCRIPT_SRC_PATTERN = Pattern.compile("(?is)<script\\b[^>]*\\bsrc\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
	private static final Pattern CSRF_META_PATTERN = Pattern.compile("(?is)<meta\\b[^>]*name\\s*=\\s*[\"'](?:_csrf_token|_csrf|csrf|xsrf-token|xsrf_token|x-csrf-token|x_csrf_token)[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']");
	private static final Pattern CSRF_JS_PATTERN = Pattern.compile("(?is)(?:\"(?:csrfToken|_csrf_token|_csrf|csrf|X-CSRF-TOKEN|X_CSRF_TOKEN)\"\\s*:\\s*\"([^\"]+)\"|(?:csrfToken|_csrf_token|_csrf|csrf|X-CSRF-TOKEN|X_CSRF_TOKEN)\\s*=\\s*[\"']([^\"']+)[\"'])");

	/** Typical CSRF endpoints, probed relative to the final page if the token is not contained in the page itself */
	private static final String[] CSRF_PROBE_PATHS = {
			"/cid/ui/csrf",
			"/cid/ui/rest/csrf",
			"/cid/ui/gwt/rest/csrf",
			"/cid/ui/api/csrf",
			"/cid/ui/security/csrf"
	};
	private static final int MAX_CSRF_PROBES = 10;

	private final boolean verbose;
	private final boolean quiet;
	private final String usernameFieldOverride;
	private final String passwordFieldOverride;
	private final Duration requestTimeout;
	private final String finalHtmlDumpPath;
	private final String tokenTypeFilter;
	private final String idpSelectionText;

	/** Request settings; each check gets its own {@link HttpTransport} with its own cookies */
	private final String proxyUrl;
	/** Trust manager for the TLS server certificates, null for the trust store of the JVM (cacerts) */
	private final TrustManager trustManager;
	private final boolean deactivateHostnameVerification;

	/** Transport (cookies, proxy, TLS trust) of the currently running check */
	private HttpTransport transport;
	private String securityTokenType;
	private String securityToken;
	/** Reason of the failure of the last check, null if it succeeded */
	private String lastFailureMessage;

	/**
	 * Creates a check that uses the system proxy settings.
	 */
	public OidcLoginCheck(final boolean insecure, final boolean verbose, final boolean quiet, final Duration timeout,
			final String usernameFieldOverride, final String passwordFieldOverride, final String finalHtmlDumpPath,
			final String tokenTypeFilter, final String idpSelectionText) throws Exception {
		this(insecure, verbose, quiet, timeout, usernameFieldOverride, passwordFieldOverride, finalHtmlDumpPath, tokenTypeFilter, idpSelectionText, null);
	}

	/**
	 * Creates a check with an explicit proxy.
	 *
	 * @param proxyUrl
	 *            "http://host:port" or "host:port" for an HTTP proxy, "none"/"direct" for a direct connection, null or
	 *            blank for the system proxy settings
	 * @throws IllegalArgumentException
	 *             if the proxy URL is invalid
	 */
	public OidcLoginCheck(final boolean insecure, final boolean verbose, final boolean quiet, final Duration timeout,
			final String usernameFieldOverride, final String passwordFieldOverride, final String finalHtmlDumpPath,
			final String tokenTypeFilter, final String idpSelectionText, final String proxyUrl) throws Exception {
		this(insecure, verbose, quiet, timeout, usernameFieldOverride, passwordFieldOverride, finalHtmlDumpPath, tokenTypeFilter, idpSelectionText, proxyUrl, null);
	}

	/**
	 * Creates a check with an explicit proxy and an explicit TLS trust manager.
	 *
	 * @param proxyUrl
	 *            "http://host:port" or "host:port" for an HTTP proxy, "none"/"direct" for a direct connection, null or
	 *            blank for the system proxy settings
	 * @param trustManager
	 *            trust manager for the TLS server certificates (e.g. of a configured keystore); null for the default
	 *            trust store of the JVM (cacerts). Ignored if insecure is set. The hostname verification stays active.
	 * @throws IllegalArgumentException
	 *             if the proxy URL is invalid
	 */
	public OidcLoginCheck(final boolean insecure, final boolean verbose, final boolean quiet, final Duration timeout,
			final String usernameFieldOverride, final String passwordFieldOverride, final String finalHtmlDumpPath,
			final String tokenTypeFilter, final String idpSelectionText, final String proxyUrl, final TrustManager trustManager) throws Exception {
		this.verbose = verbose;
		this.quiet = quiet;
		this.usernameFieldOverride = usernameFieldOverride;
		this.passwordFieldOverride = passwordFieldOverride;
		this.finalHtmlDumpPath = finalHtmlDumpPath;
		this.tokenTypeFilter = tokenTypeFilter;
		this.idpSelectionText = idpSelectionText;

		// Explicit proxy, or the system default (honors -Dhttps.proxyHost etc. and -Djava.net.useSystemProxies=true);
		// validated here, so an invalid proxy URL fails before the first request
		validateProxyUrl(proxyUrl);
		this.proxyUrl = proxyUrl;
		log("Proxy: " + describeProxy(proxyUrl));

		if (insecure) {
			// Only for this check (no global system property): any certificate and hostname is accepted
			this.trustManager = new X509TrustManager() {
				@Override
				public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
					// Trust all
				}

				@Override
				public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
					// Trust all
				}

				@Override
				public X509Certificate[] getAcceptedIssuers() {
					return new X509Certificate[0];
				}
			};
			deactivateHostnameVerification = true;
		} else {
			// Given trust manager (e.g. of a configured keystore) or null for the trust store of the JVM (cacerts)
			this.trustManager = trustManager;
			deactivateHostnameVerification = false;
		}

		requestTimeout = timeout;
	}

	/**
	 * Executes the complete login flow and checks the resulting page.
	 *
	 * @return the logged in session (CSRF token, cookie header) if the web application main page was delivered
	 *         successfully after login, otherwise null
	 */
	public LoginSession check(final URI startUri, final String user, final String password, final String expectText) throws Exception {
		final long startTime = System.currentTimeMillis();
		if (!quiet) {
			System.out.println("Checking " + startUri);
		}
		securityTokenType = null;
		securityToken = null;
		lastFailureMessage = null;
		// Each check gets its own cookie store, a new check must not reuse the session of a previous check
		transport = new HttpTransport(proxyUrl, trustManager, deactivateHostnameVerification, requestTimeout);

		Page page = send("GET", startUri, null, null);
		boolean loginPageSeen = false;
		boolean usernameSubmitted = false;
		boolean passwordSubmitted = false;
		boolean directCredentialPostAttempted = false;
		boolean idpSelectionDone = false;

		for (int submitCount = 0; submitCount < MAX_FORM_SUBMITS; submitCount++) {
			if (page.status >= 400) {
				break;
			}

			final List<HtmlForm> forms = HtmlForm.parseAll(page.body, page.uri);

			if (!idpSelectionDone && idpSelectionText != null && !idpSelectionText.isBlank()) {
				final URI providerTarget = findIdpSelectionTarget(page, idpSelectionText);
				if (providerTarget != null && !providerTarget.equals(page.uri)) {
					log("Selecting IdP provider \"" + idpSelectionText + "\" -> " + providerTarget);
					page = send("GET", providerTarget, null, page.uri);
					loginPageSeen = true;
					idpSelectionDone = true;
					continue;
				}
			}

			final HtmlForm passwordForm = findPasswordForm(forms);
			if (passwordForm != null) {
				loginPageSeen = true;
				if (passwordSubmitted) {
					final String errorText = extractErrorText(page.body);
					return fail("Login form was shown again after submitting the credentials (wrong username/password?)"
							+ (errorText != null ? ": " + errorText : ""), page, startTime);
				}
				final String passwordFieldName = passwordFieldOverride != null ? passwordFieldOverride : passwordForm.getFirstFieldNameOfType("password");
				final String usernameFieldName = findUsernameFieldName(passwordForm);
				final Map<String, String> values = passwordForm.getDefaultValues();
				if (usernameFieldName != null) {
					values.put(usernameFieldName, user);
				}
				values.put(passwordFieldName, password);
				log("Submitting login form (user field: " + usernameFieldName + ", password field: " + passwordFieldName + ") to " + passwordForm.action);
				page = submit(passwordForm, values, page.uri);
				passwordSubmitted = true;
				continue;
			}

			final HtmlForm autoPostForm = findAutoPostForm(forms, page.body);
			if (autoPostForm != null) {
				log("Submitting auto-post form to " + autoPostForm.action);
				page = submit(autoPostForm, autoPostForm.getDefaultValues(), page.uri);
				continue;
			}

			final URI clientSideRedirect = findClientSideRedirect(page);
			if (clientSideRedirect != null && !clientSideRedirect.equals(page.uri)) {
				log("Following client-side redirect to " + clientSideRedirect);
				page = send("GET", clientSideRedirect, null, page.uri);
				continue;
			}

			if (!usernameSubmitted && !passwordSubmitted && !isSameOrigin(page.uri, startUri)) {
				final HtmlForm usernameForm = findUsernameOnlyForm(forms);
				if (usernameForm != null) {
					// First step of a two-step login (username first, password on the next page)
					loginPageSeen = true;
					final Map<String, String> values = usernameForm.getDefaultValues();
					values.put(findUsernameFieldName(usernameForm), user);
					log("Submitting username form to " + usernameForm.action);
					page = submit(usernameForm, values, page.uri);
					usernameSubmitted = true;
					continue;
				}
			}

			if (!directCredentialPostAttempted && !isSameOrigin(page.uri, startUri) && !hasAnyFormField(forms)) {
				final URI scriptedLoginAction = findScriptedLoginAction(page);
				if (scriptedLoginAction != null) {
					final Map<String, String> values = new LinkedHashMap<>();
					values.put("username", user);
					values.put("password", password);
					values.put("credentialId", "");
					log("No input fields found; submitting credentials to scripted loginAction " + scriptedLoginAction);
					page = send("POST", scriptedLoginAction, encodeForm(values), page.uri);
					loginPageSeen = true;
					passwordSubmitted = true;
					directCredentialPostAttempted = true;
					continue;
				}

				if (isLikelyDirectCredentialPostTarget(page.uri, page.body)) {
					final Map<String, String> values = new LinkedHashMap<>();
					values.put("username", user);
					values.put("password", password);
					values.put("credentialId", "");
					log("No input fields found; submitting direct credential POST to " + page.uri);
					page = send("POST", page.uri, encodeForm(values), page.uri);
					loginPageSeen = true;
					passwordSubmitted = true;
					directCredentialPostAttempted = true;
					continue;
				}
				log("No input fields found and URL/body does not look like a login processing endpoint; skipping direct credential POST");
			}

			break;
		}

		// Evaluate the final page
		if (page.status < 200 || page.status >= 300) {
			return fail("Final page returned HTTP " + page.status, page, startTime);
		}
		if (!isSameOrigin(page.uri, startUri)) {
			return fail("Final page is not on the web application host (still at identity provider?). "
					+ (loginPageSeen ? "Login form could not be completed." : "No login form found (JavaScript-based login is not supported)."),
					page, startTime);
		}
		if (findPasswordForm(HtmlForm.parseAll(page.body, page.uri)) != null) {
			return fail("Final page still contains a password form", page, startTime);
		}
		if (expectText != null && !page.body.contains(expectText)) {
			return fail("Expected text not found in final page: \"" + expectText + "\"", page, startTime);
		}
		if (!loginPageSeen) {
			if (!quiet) {
				System.out.println("Note: No login form was shown (existing session or page not protected)");
			}
		}

		final LoginSession session = new LoginSession(transport, page.uri, page.body, securityTokenType, securityToken, verbose && !quiet);

		if (!quiet) {
			printPageInfo(page, startTime);
		}
		if (verbose && !quiet) {
			final String cookieHeader = session.getCookieHeader();
			System.out.println("Session cookies: " + (cookieHeader == null ? "<none>" : maskCookieHeader(cookieHeader)));
			System.out.println("CSRF token in page: " + (session.csrfToken == null ? "<not found, probed on demand>" : maskSensitiveValue(session.csrfToken)));
		}
		if (securityToken != null) {
			System.out.println("Security token (" + securityTokenType + "): " + securityToken);
		} else {
			System.out.println("Security token: <not found>");
		}
		System.out.println("RESULT: OK");
		return session;
	}

	/**
	 * Reason why the last {@link #check(URI, String, String, String)} returned null, e.g. "Login form was shown again
	 * after submitting the credentials (wrong username/password?)".
	 *
	 * @return null if the last check succeeded or threw an exception
	 */
	public String getLastFailureMessage() {
		return lastFailureMessage;
	}

	/**
	 * Result of a successful login: the logged in session of the web application with its cookies and CSRF token, to
	 * be used for further requests (headers "Cookie" and "X-CSRF-TOKEN").
	 *
	 * Each session has its own transport and cookie store, so it stays valid after further checks.
	 */
	public static final class LoginSession {
		private final HttpTransport transport;
		private final CookieManager cookieManager;
		private final URI finalPageUri;
		private final String finalPageBody;
		private final String securityTokenType;
		private final String securityToken;
		private final boolean logEnabled;

		private String csrfToken;
		/** The probing for the CSRF token is expensive (several requests), so it is done once and only on demand */
		private boolean csrfTokenDetermined;

		private LoginSession(final HttpTransport transport, final URI finalPageUri, final String finalPageBody,
				final String securityTokenType, final String securityToken, final boolean logEnabled) {
			this.transport = transport;
			cookieManager = transport.getCookieManager();
			this.finalPageUri = finalPageUri;
			this.finalPageBody = finalPageBody;
			this.securityTokenType = securityTokenType;
			this.securityToken = securityToken;
			this.logEnabled = logEnabled;

			// Cheap detection without further requests: page content and cookies
			csrfToken = findCsrfToken(finalPageBody);
			csrfTokenDetermined = csrfToken != null;
		}

		/**
		 * URI of the final page after the login (the main page of the web application).
		 */
		public URI getFinalPageUri() {
			return finalPageUri;
		}

		/**
		 * Security token found in the redirects of the login (id_token, access_token or code), may be null.
		 */
		public String getSecurityToken() {
			return securityToken;
		}

		/**
		 * Type of {@link #getSecurityToken()}: "id_token", "access_token" or "code", may be null.
		 */
		public String getSecurityTokenType() {
			return securityTokenType;
		}

		/**
		 * Value for the "Cookie" header of further requests to the final page.
		 *
		 * @return null if there are no cookies
		 */
		public String getCookieHeader() {
			return getCookieHeader(finalPageUri);
		}

		/**
		 * Value for the "Cookie" header of further requests to the given URI of the logged in application (only the
		 * cookies matching domain and path of this URI).
		 *
		 * @return null if there are no matching cookies
		 */
		public String getCookieHeader(final URI targetUri) {
			if (targetUri == null) {
				return null;
			}
			final StringBuilder header = new StringBuilder();
			for (final HttpCookie cookie : cookieManager.getCookieStore().get(targetUri)) {
				if (header.length() > 0) {
					header.append("; ");
				}
				header.append(cookie.getName()).append('=').append(cookie.getValue());
			}
			return header.length() == 0 ? null : header.toString();
		}

		/**
		 * All cookies of the session (copy).
		 */
		public List<HttpCookie> getCookies() {
			return List.copyOf(cookieManager.getCookieStore().getCookies());
		}

		/**
		 * Response of a request sent with {@link #send(String, URI, Map, String)}.
		 */
		public record SessionResponse(int statusCode, String body, Map<String, List<String>> headers) {
		}

		/**
		 * Sends a request with this session: same transport as the login, so the same TLS trust (configured trust
		 * manager or JVM trust store, or trust all for "insecure"), the same proxy and the session cookies (the "Cookie"
		 * header is added automatically and cookies set by the response are kept in the session). Redirects are not
		 * followed.
		 *
		 * @param method
		 *            HTTP method, e.g. "GET" or "POST"
		 * @param headers
		 *            additional headers, e.g. "X-CSRF-TOKEN" and "Content-Type"; may be null
		 * @param body
		 *            request body, may be null (empty body)
		 */
		public SessionResponse send(final String method, final URI uri, final Map<String, String> headers, final String body) throws Exception {
			final Map<String, String> requestHeaders = new LinkedHashMap<>();
			requestHeaders.put("User-Agent", USER_AGENT);
			if (headers != null) {
				requestHeaders.putAll(headers);
			}
			log(method.toUpperCase(Locale.ROOT) + " " + uri + " with session");
			final TransportResponse response = transport.execute(method, uri, requestHeaders, body);
			log("-> " + response.status());
			return new SessionResponse(response.status(), response.body(), response.getHeaderLists());
		}

		/**
		 * CSRF token of the session (to be sent as header "X-CSRF-TOKEN").
		 *
		 * The token is searched in the final page and the cookies first. If it is not found there, typical CSRF
		 * endpoints and the scripts of the final page are probed with the session (several requests, done only once).
		 *
		 * @return null if no token was found
		 */
		public synchronized String getCsrfToken() {
			if (!csrfTokenDetermined) {
				csrfTokenDetermined = true;
				csrfToken = autoDetectCsrfToken();
				if (csrfToken == null) {
					// SAP/Spring style: the token is delivered on request with "X-CSRF-TOKEN: Fetch"
					csrfToken = fetchCsrfToken(finalPageUri);
				}
			}
			return csrfToken;
		}

		/**
		 * Requests a (new) CSRF token for the given endpoint with this session by sending "X-CSRF-TOKEN: Fetch". Useful
		 * if a request with {@link #getCsrfToken()} was rejected. A delivered token replaces the token of this session.
		 *
		 * @return the token or null if none was delivered
		 */
		public synchronized String fetchCsrfToken(final URI endpoint) {
			if (endpoint == null) {
				return null;
			}
			try {
				log("CSRF probe: GET " + endpoint + " with X-CSRF-TOKEN: Fetch");
				final Map<String, String> headers = new LinkedHashMap<>();
				headers.put("User-Agent", USER_AGENT);
				headers.put("Accept", "*/*");
				headers.put("X-Requested-With", "XMLHttpRequest");
				headers.put("X-CSRF-TOKEN", "Fetch");
				headers.put("Referer", finalPageUri.toString());
				headers.put("Origin", finalPageUri.getScheme() + "://" + finalPageUri.getRawAuthority());
				final TransportResponse response = transport.execute("GET", endpoint, headers, null);
				final String headerToken = extractCsrfTokenFromHeaders(response.headers());
				final String token = headerToken != null ? headerToken : findCsrfToken(response.body());
				if (token != null) {
					csrfToken = token;
					csrfTokenDetermined = true;
				}
				return token;
			} catch (final Exception e) {
				log("CSRF fetch probe failed (" + e.getMessage() + ")");
			}
			return null;
		}

		/**
		 * Probes the final page, typical CSRF endpoints and the scripts of the final page for a CSRF token.
		 */
		private String autoDetectCsrfToken() {
			final List<URI> probeUris = new ArrayList<>();
			probeUris.add(finalPageUri);
			for (final String path : CSRF_PROBE_PATHS) {
				probeUris.add(resolveHttpUri(finalPageUri, path));
			}
			probeUris.addAll(extractScriptUris(finalPageUri, finalPageBody));

			int probeCount = 0;
			for (final URI probeUri : probeUris) {
				if (probeUri == null) {
					continue;
				}
				if (probeCount >= MAX_CSRF_PROBES) {
					break;
				}
				probeCount++;
				try {
					log("CSRF probe: GET " + probeUri);
					final Map<String, String> headers = new LinkedHashMap<>();
					headers.put("User-Agent", USER_AGENT);
					headers.put("Accept", "*/*");
					headers.put("Referer", finalPageUri.toString());
					final TransportResponse response = transport.execute("GET", probeUri, headers, null);
					final String headerToken = extractCsrfTokenFromHeaders(response.headers());
					if (headerToken != null) {
						log("CSRF token found in response headers from " + probeUri);
						return headerToken;
					}
					final String bodyToken = findCsrfToken(response.body());
					if (bodyToken != null) {
						log("CSRF token found in response body/cookies from " + probeUri);
						return bodyToken;
					}
				} catch (final Exception e) {
					log("CSRF probe failed at " + probeUri + " (" + e.getMessage() + ")");
				}
			}
			return null;
		}

		/**
		 * Searches a CSRF token in HTML (meta tag or JavaScript variable) and then in the cookies of the session.
		 */
		private String findCsrfToken(final String html) {
			if (html != null) {
				final Matcher metaMatcher = CSRF_META_PATTERN.matcher(html);
				if (metaMatcher.find()) {
					return decodeEntities(metaMatcher.group(1));
				}
				final Matcher jsMatcher = CSRF_JS_PATTERN.matcher(html);
				if (jsMatcher.find()) {
					return unescapeJsString(jsMatcher.group(1) != null ? jsMatcher.group(1) : jsMatcher.group(2));
				}
			}

			for (final HttpCookie cookie : cookieManager.getCookieStore().getCookies()) {
				final String name = cookie.getName().toLowerCase(Locale.ROOT);
				if ((name.contains("csrf") || name.contains("xsrf")) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
					return cookie.getValue();
				}
			}
			return null;
		}

		private void log(final String message) {
			if (logEnabled) {
				System.out.println("  " + message);
			}
		}

		@Override
		public String toString() {
			// Cookies and tokens must never appear in log output
			return "LoginSession @ " + finalPageUri;
		}
	}

	private static String extractCsrfTokenFromHeaders(final Map<String, String> headers) {
		for (final Map.Entry<String, String> header : headers.entrySet()) {
			final String name = header.getKey().toLowerCase(Locale.ROOT);
			if (!(name.contains("csrf") || name.contains("xsrf")) || header.getValue() == null) {
				continue;
			}
			// Several header lines of the same name are joined with ", " by the network library
			for (final String value : header.getValue().split(",\\s*")) {
				// "Required"/"Fetch" are protocol values of servers that demand or offer a token, no token itself
				if (value != null && !value.isBlank() && !"Required".equalsIgnoreCase(value.trim()) && !"Fetch".equalsIgnoreCase(value.trim())) {
					return value.trim();
				}
			}
		}
		return null;
	}

	private static List<URI> extractScriptUris(final URI baseUri, final String html) {
		final List<URI> uris = new ArrayList<>();
		if (html == null || html.isBlank()) {
			return uris;
		}
		final Matcher matcher = SCRIPT_SRC_PATTERN.matcher(html);
		while (matcher.find()) {
			final String src = matcher.group(1) != null ? matcher.group(1) : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
			final URI resolved = resolveHttpUri(baseUri, src);
			if (resolved != null) {
				uris.add(resolved);
			}
		}
		return uris;
	}

	/**
	 * Shortens secret values for log output.
	 */
	private static String maskSensitiveValue(final String value) {
		if (value == null || value.isEmpty()) {
			return "<empty>";
		} else if (value.length() <= 8) {
			return "<len=" + value.length() + ">";
		} else {
			return value.substring(0, 4) + "..." + value.substring(value.length() - 4) + " (len=" + value.length() + ")";
		}
	}

	private static String maskCookieHeader(final String cookieHeader) {
		final StringBuilder masked = new StringBuilder();
		for (final String cookie : cookieHeader.split("; ")) {
			if (masked.length() > 0) {
				masked.append("; ");
			}
			final int equalsIndex = cookie.indexOf('=');
			masked.append(equalsIndex < 0 ? cookie : cookie.substring(0, equalsIndex + 1) + maskSensitiveValue(cookie.substring(equalsIndex + 1)));
		}
		return masked.toString();
	}

	/**
	 * Prints the failure.
	 *
	 * @return always null (no session)
	 */
	private LoginSession fail(final String message, final Page page, final long startTime) {
		lastFailureMessage = message + " (final URL: " + page.uri + ", HTTP " + page.status + ")";
		if (!quiet) {
			printPageInfo(page, startTime);
		}
		if (securityToken != null) {
			System.out.println("Security token (" + securityTokenType + "): " + securityToken);
		}
		dumpFinalHtmlIfRequested(page);
		System.out.println("RESULT: FAILED - " + message);
		return null;
	}

	private void dumpFinalHtmlIfRequested(final Page page) {
		if (finalHtmlDumpPath == null || finalHtmlDumpPath.isBlank()) {
			return;
		}
		try {
			Files.writeString(Path.of(finalHtmlDumpPath), page.body, StandardCharsets.UTF_8);
			if (!quiet) {
				System.out.println("Final HTML written to: " + finalHtmlDumpPath);
			}
		} catch (final Exception e) {
			if (!quiet) {
				System.out.println("Note: Could not write final HTML dump to " + finalHtmlDumpPath + " (" + e.getMessage() + ")");
			}
		}
	}

	private static void printPageInfo(final Page page, final long startTime) {
		// Kept for non-quiet mode; caller controls whether this is invoked
		System.out.println("Final URL:   " + page.uri);
		System.out.println("HTTP status: " + page.status);
		final String title = extractTitle(page.body);
		if (title != null) {
			System.out.println("Page title:  " + title);
		}
		System.out.println("Body size:   " + page.body.length() + " chars");
		System.out.println("Duration:    " + (System.currentTimeMillis() - startTime) + " ms");
	}

	private HtmlForm findPasswordForm(final List<HtmlForm> forms) {
		for (final HtmlForm form : forms) {
			if (passwordFieldOverride != null ? form.hasField(passwordFieldOverride) : form.getFirstFieldNameOfType("password") != null) {
				return form;
			}
		}
		return null;
	}

	/**
	 * Detects forms that are submitted by JavaScript on page load (e.g. OIDC response_mode=form_post)
	 */
	private static HtmlForm findAutoPostForm(final List<HtmlForm> forms, final String body) {
		final boolean hasSubmitScript = Pattern.compile("(?i)\\.submit\\s*\\(").matcher(body).find();
		for (final HtmlForm form : forms) {
			if (form.fields.isEmpty() || !form.fields.stream().allMatch(field -> "hidden".equals(field.type) || "submit".equals(field.type))) {
				continue;
			}
			final boolean hasOidcFields = form.hasField("code") || form.hasField("id_token") || form.hasField("state") || form.hasField("SAMLResponse");
			if (hasSubmitScript || hasOidcFields) {
				return form;
			}
		}
		return null;
	}

	private HtmlForm findUsernameOnlyForm(final List<HtmlForm> forms) {
		for (final HtmlForm form : forms) {
			if (findUsernameFieldName(form) != null) {
				return form;
			}
		}
		return null;
	}

	private String findUsernameFieldName(final HtmlForm form) {
		if (usernameFieldOverride != null) {
			return form.hasField(usernameFieldOverride) ? usernameFieldOverride : null;
		}
		String firstTextField = null;
		for (final HtmlField field : form.fields) {
			if ("text".equals(field.type) || "email".equals(field.type)) {
				if (USERNAME_FIELD_PATTERN.matcher(field.name).matches()) {
					return field.name;
				} else if (firstTextField == null) {
					firstTextField = field.name;
				}
			}
		}
		// Fallback for login forms with unusual field names: the only visible text field next to a password field
		return form.getFirstFieldNameOfType("password") != null ? firstTextField : null;
	}

	private Page submit(final HtmlForm form, final Map<String, String> values, final URI referer) throws Exception {
		final String encoded = encodeForm(values);
		if ("POST".equals(form.method)) {
			return send("POST", form.action, encoded, referer);
		} else {
			final String actionString = form.action.toString();
			final int fragmentIndex = actionString.indexOf('#');
			final String base = fragmentIndex >= 0 ? actionString.substring(0, fragmentIndex) : actionString;
			final String withoutQuery = base.contains("?") ? base.substring(0, base.indexOf('?')) : base;
			return send("GET", URI.create(withoutQuery + (encoded.isEmpty() ? "" : "?" + encoded)), null, referer);
		}
	}

	/**
	 * Sends a request and follows all redirects
	 */
	private Page send(final String initialMethod, final URI initialUri, final String initialFormBody, final URI referer) throws Exception {
		String method = initialMethod;
		URI uri = initialUri;
		String formBody = initialFormBody;
		URI currentReferer = referer;

		for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
			final Map<String, String> headers = new LinkedHashMap<>();
			headers.put("User-Agent", USER_AGENT);
			headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
			headers.put("Accept-Language", "de,en;q=0.8");
			if (currentReferer != null) {
				headers.put("Referer", currentReferer.toString());
			}
			final String body;
			if ("POST".equals(method)) {
				headers.put("Content-Type", "application/x-www-form-urlencoded");
				if (currentReferer != null) {
					headers.put("Origin", currentReferer.getScheme() + "://" + currentReferer.getRawAuthority());
				}
				body = formBody == null ? "" : formBody;
			} else {
				body = null;
			}

			final TransportResponse response = transport.execute(method, uri, headers, body);
			captureSecurityTokenFromUri(uri);
			final int status = response.status();
			log(method + " " + uri + " -> " + status);

			final String location = response.getHeader("Location");
			if (status >= 300 && status < 400 && location != null) {
				final URI nextUri = uri.resolve(location.trim().replace(" ", "%20"));
				captureSecurityTokenFromUri(nextUri);
				if (!"http".equalsIgnoreCase(nextUri.getScheme()) && !"https".equalsIgnoreCase(nextUri.getScheme())) {
					throw new IllegalStateException("Redirect to unsupported URL scheme: " + nextUri);
				}
				if (status != 307 && status != 308) {
					method = "GET";
					formBody = null;
				}
				currentReferer = uri;
				uri = nextUri;
				continue;
			}

			return new Page(uri, status, response.body());
		}
		throw new IllegalStateException("Too many redirects (more than " + MAX_REDIRECTS + ")");
	}

	private void captureSecurityTokenFromUri(final URI uri) {
		captureSecurityTokenFromEncodedParams(uri.getRawQuery());
		captureSecurityTokenFromEncodedParams(uri.getRawFragment());
	}

	private void captureSecurityTokenFromEncodedParams(final String rawParams) {
		if (rawParams == null || rawParams.isBlank()) {
			return;
		}
		for (final String pair : rawParams.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			final int equalsIndex = pair.indexOf('=');
			final String rawName = equalsIndex >= 0 ? pair.substring(0, equalsIndex) : pair;
			final String rawValue = equalsIndex >= 0 ? pair.substring(equalsIndex + 1) : "";
			final String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
			final String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
			if (value.isEmpty()) {
				continue;
			}
			if (!("id_token".equals(name) || "access_token".equals(name) || "code".equals(name))) {
				continue;
			}
			if (tokenTypeFilter != null && !tokenTypeFilter.equals(name)) {
				continue;
			}

			if (securityTokenType == null || tokenPriority(name) > tokenPriority(securityTokenType)) {
				securityTokenType = name;
				securityToken = value;
			}
		}
	}

	private static int tokenPriority(final String tokenType) {
		return switch (tokenType) {
			case "id_token" -> 3;
			case "access_token" -> 2;
			case "code" -> 1;
			default -> 0;
		};
	}

	private static boolean isSameOrigin(final URI uri1, final URI uri2) {
		return uri1.getScheme().equalsIgnoreCase(uri2.getScheme())
				&& uri1.getHost().equalsIgnoreCase(uri2.getHost())
				&& effectivePort(uri1) == effectivePort(uri2);
	}

	private static boolean hasAnyFormField(final List<HtmlForm> forms) {
		for (final HtmlForm form : forms) {
			if (!form.fields.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static boolean isLikelyDirectCredentialPostTarget(final URI uri, final String body) {
		if (isAuthorizationEndpoint(uri)) {
			return false;
		}

		final String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
		if (path.contains("/login-actions/authenticate") || path.contains("/login") || path.contains("/authenticate")) {
			return true;
		}
		final String lowerBody = body == null ? "" : body.toLowerCase(Locale.ROOT);
		return lowerBody.contains("session_code")
				|| lowerBody.contains("execution")
				|| lowerBody.contains("tab_id")
				|| lowerBody.contains("kc-form-login");
	}

	private static boolean isAuthorizationEndpoint(final URI uri) {
		final String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
		return path.contains("/protocol/openid-connect/auth")
				|| path.contains("/oauth2/authorize")
				|| path.endsWith("/authorize");
	}

	private static URI findClientSideRedirect(final Page page) {
		final Matcher metaRefreshMatcher = META_REFRESH_URL_PATTERN.matcher(page.body);
		if (metaRefreshMatcher.find()) {
			final URI resolved = resolveHttpUri(page.uri, metaRefreshMatcher.group(1));
			if (resolved != null) {
				return resolved;
			}
		}

		final Matcher jsLocationMatcher = JS_LOCATION_PATTERN.matcher(page.body);
		if (jsLocationMatcher.find()) {
			return resolveHttpUri(page.uri, jsLocationMatcher.group(1));
		}
		return null;
	}

	private static URI findScriptedLoginAction(final Page page) {
		final Matcher matcher = LOGIN_ACTION_PATTERN.matcher(page.body);
		if (!matcher.find()) {
			return null;
		}
		final String unescaped = unescapeJsString(matcher.group(1));
		return resolveHttpUri(page.uri, unescaped);
	}

	private static URI findIdpSelectionTarget(final Page page, final String targetLabel) {
		final String expected = targetLabel.trim().toLowerCase(Locale.ROOT);

		final Matcher providerMatcher = PROVIDER_PAIR_PATTERN.matcher(page.body);
		while (providerMatcher.find()) {
			final String displayName = unescapeJsString(providerMatcher.group(1));
			if (!displayName.trim().toLowerCase(Locale.ROOT).equals(expected)) {
				continue;
			}
			final URI resolved = resolveHttpUri(page.uri, unescapeJsString(providerMatcher.group(2)));
			if (resolved != null) {
				return resolved;
			}
		}

		final Matcher anchorMatcher = ANCHOR_PATTERN.matcher(page.body);
		while (anchorMatcher.find()) {
			final String label = normalizeText(anchorMatcher.group(2));
			if (!label.toLowerCase(Locale.ROOT).equals(expected)) {
				continue;
			}
			final Matcher hrefMatcher = HREF_ATTR_PATTERN.matcher(anchorMatcher.group(1));
			if (!hrefMatcher.find()) {
				continue;
			}
			final String href = hrefMatcher.group(1) != null ? hrefMatcher.group(1)
					: hrefMatcher.group(2) != null ? hrefMatcher.group(2) : hrefMatcher.group(3);
			final URI resolved = resolveHttpUri(page.uri, href);
			if (resolved != null) {
				return resolved;
			}
		}

		return null;
	}

	private static String unescapeJsString(final String value) {
		if (value == null) {
			return null;
		}
		return value.replace("\\/", "/").replace("\\u0026", "&").replace("\\\\", "\\");
	}

	private static URI resolveHttpUri(final URI baseUri, final String candidate) {
		if (candidate == null || candidate.isBlank()) {
			return null;
		}
		try {
			final URI resolved = baseUri.resolve(candidate.trim().replace(" ", "%20"));
			if ("http".equalsIgnoreCase(resolved.getScheme()) || "https".equalsIgnoreCase(resolved.getScheme())) {
				return resolved;
			}
		} catch (@SuppressWarnings("unused") final IllegalArgumentException e) {
			return null;
		}
		return null;
	}

	/**
	 * Checks whether the value means "no proxy at all" (direct connection, ignoring the system proxy settings).
	 */
	private static boolean isDirectProxyValue(final String proxyUrl) {
		final String trimmed = proxyUrl.trim();
		return "none".equalsIgnoreCase(trimmed) || "direct".equalsIgnoreCase(trimmed);
	}

	/**
	 * Validates a proxy URL as accepted by {@link #createProxySelector(String)} without any DNS lookup.
	 *
	 * @throws IllegalArgumentException
	 *             with a readable message if the value is invalid
	 */
	public static void validateProxyUrl(final String proxyUrl) {
		if (proxyUrl != null && !proxyUrl.isBlank() && !isDirectProxyValue(proxyUrl)) {
			parseProxyUri(proxyUrl);
		}
	}

	/**
	 * Creates the proxy selector for the HttpClient.
	 *
	 * @param proxyUrl
	 *            null or blank: system proxy settings; "none"/"direct": no proxy; otherwise "http://host:port" or
	 *            "host:port"
	 * @throws IllegalArgumentException
	 *             if the proxy URL is invalid
	 */
	public static ProxySelector createProxySelector(final String proxyUrl) {
		if (proxyUrl == null || proxyUrl.isBlank()) {
			final ProxySelector systemDefault = ProxySelector.getDefault();
			return systemDefault != null ? systemDefault : HttpClient.Builder.NO_PROXY;
		} else if (isDirectProxyValue(proxyUrl)) {
			return HttpClient.Builder.NO_PROXY;
		} else {
			final URI proxyUri = parseProxyUri(proxyUrl);
			// Resolved here (not createUnresolved), so the HttpClient gets a connectable address
			return ProxySelector.of(new InetSocketAddress(proxyUri.getHost(), proxyUri.getPort()));
		}
	}

	/**
	 * Creates a java.net.Proxy for other HTTP clients (e.g. the BasicAuth login check) with the same syntax as
	 * {@link #createProxySelector(String)}.
	 *
	 * @return null if no proxy URL is given (caller decides about its default), {@link Proxy#NO_PROXY} for
	 *         "none"/"direct", otherwise an HTTP proxy
	 * @throws IllegalArgumentException
	 *             if the proxy URL is invalid
	 */
	public static Proxy createProxy(final String proxyUrl) {
		if (proxyUrl == null || proxyUrl.isBlank()) {
			return null;
		} else if (isDirectProxyValue(proxyUrl)) {
			return Proxy.NO_PROXY;
		} else {
			final URI proxyUri = parseProxyUri(proxyUrl);
			return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyUri.getHost(), proxyUri.getPort()));
		}
	}

	/**
	 * Determines the proxy for a request to the given target with the same rules as the login itself: null or blank
	 * means the system proxy settings (JVM properties, -Djava.net.useSystemProxies=true), "none"/"direct" a direct
	 * connection, otherwise the given HTTP proxy.
	 *
	 * @return the proxy to use, never null ({@link Proxy#NO_PROXY} for a direct connection)
	 * @throws IllegalArgumentException
	 *             if the proxy URL is invalid
	 */
	public static Proxy resolveProxy(final String proxyUrl, final URI targetUri) {
		final List<Proxy> proxies = createProxySelector(proxyUrl).select(targetUri);
		return proxies == null || proxies.isEmpty() ? Proxy.NO_PROXY : proxies.get(0);
	}

	private static String describeProxy(final String proxyUrl) {
		if (proxyUrl == null || proxyUrl.isBlank()) {
			return "system settings";
		} else if (isDirectProxyValue(proxyUrl)) {
			return "none (direct connection)";
		} else {
			final URI proxyUri = parseProxyUri(proxyUrl);
			return proxyUri.getHost() + ":" + proxyUri.getPort();
		}
	}

	/**
	 * Parses and checks an HTTP proxy URL. Only plain HTTP proxies without credentials are supported, because the
	 * java.net.http.HttpClient does not support SOCKS proxies.
	 */
	private static URI parseProxyUri(final String proxyUrl) {
		String value = proxyUrl.trim();
		if (!value.contains("://")) {
			value = "http://" + value;
		}
		final URI proxyUri;
		try {
			proxyUri = new URI(value);
		} catch (@SuppressWarnings("unused") final URISyntaxException e) {
			throw new IllegalArgumentException("Invalid proxy URL: " + proxyUrl);
		}
		if (!"http".equalsIgnoreCase(proxyUri.getScheme())) {
			throw new IllegalArgumentException("Unsupported proxy scheme \"" + proxyUri.getScheme() + "\" (only http proxies are supported): " + proxyUrl);
		}
		if (proxyUri.getRawUserInfo() != null) {
			throw new IllegalArgumentException("Credentials in the proxy URL are not supported: " + proxyUri.getHost());
		}
		if (proxyUri.getHost() == null || proxyUri.getHost().isBlank()) {
			throw new IllegalArgumentException("Missing host in proxy URL: " + proxyUrl);
		}
		if (proxyUri.getPort() < 1 || proxyUri.getPort() > 65535) {
			throw new IllegalArgumentException("Missing or invalid port in proxy URL (e.g. http://proxy:3128): " + proxyUrl);
		}
		final String path = proxyUri.getRawPath();
		if ((path != null && !path.isEmpty() && !"/".equals(path)) || proxyUri.getRawQuery() != null || proxyUri.getRawFragment() != null) {
			throw new IllegalArgumentException("Proxy URL must not contain a path, query or fragment: " + proxyUrl);
		}
		return proxyUri;
	}

	private static int effectivePort(final URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private static String encodeForm(final Map<String, String> values) {
		final StringBuilder builder = new StringBuilder();
		for (final Map.Entry<String, String> entry : values.entrySet()) {
			if (builder.length() > 0) {
				builder.append('&');
			}
			builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
					.append('=')
					.append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), StandardCharsets.UTF_8));
		}
		return builder.toString();
	}

	private static String extractTitle(final String html) {
		final Matcher matcher = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
		return matcher.find() ? normalizeText(matcher.group(1)) : null;
	}

	/**
	 * Tries to find a login error message on the identity provider page (e.g. Keycloak "Invalid username or password.")
	 */
	private static String extractErrorText(final String html) {
		final Matcher matcher = Pattern.compile("(?is)<(span|div|p)\\b[^>]*(?:id|class)\\s*=\\s*[\"'][^\"']*(?:error|alert|feedback)[^\"']*[\"'][^>]*>(.*?)</\\1>").matcher(html);
		while (matcher.find()) {
			final String text = normalizeText(matcher.group(2));
			if (!text.isEmpty()) {
				return text.length() > 200 ? text.substring(0, 200) + "..." : text;
			}
		}
		return null;
	}

	private static String normalizeText(final String html) {
		return decodeEntities(html.replaceAll("(?s)<[^>]*>", " ")).replaceAll("\\s+", " ").trim();
	}

	static String decodeEntities(final String text) {
		if (text == null || text.indexOf('&') < 0) {
			return text;
		}
		final Matcher matcher = Pattern.compile("&(#[xX][0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);").matcher(text);
		final StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			final String entity = matcher.group(1);
			final String replacement;
			if (entity.startsWith("#x") || entity.startsWith("#X")) {
				replacement = new String(Character.toChars(Integer.parseInt(entity.substring(2), 16)));
			} else if (entity.startsWith("#")) {
				replacement = new String(Character.toChars(Integer.parseInt(entity.substring(1))));
			} else {
				replacement = switch (entity) {
					case "amp" -> "&";
					case "lt" -> "<";
					case "gt" -> ">";
					case "quot" -> "\"";
					case "apos" -> "'";
					default -> " ";
				};
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private void log(final String message) {
		if (verbose && !quiet) {
			System.out.println("  " + message);
		}
	}

	private record Page(URI uri, int status, String body) {
	}

	/**
	 * Sends the requests of a check and its login session with the network library (de.soderer.network): own cookie
	 * store per login session, redirects are not followed (the login flow follows them itself to see every hop), proxy
	 * and TLS trust as configured.
	 * <p>
	 * HTTP/2 is requested (with transparent fallback to HTTP/1.1), so the network library sends the requests with
	 * java.net.http.HttpClient. Its HTTP/1.1 transport HttpURLConnection would silently drop the "Origin" header, which
	 * is checked by the CSRF protection of many identity providers and web applications.
	 * </p>
	 */
	private static final class HttpTransport {
		private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		private final String proxyUrl;
		private final TrustManager trustManager;
		private final boolean deactivateHostnameVerification;
		private final int timeoutMillis;

		HttpTransport(final String proxyUrl, final TrustManager trustManager, final boolean deactivateHostnameVerification, final Duration timeout) {
			this.proxyUrl = proxyUrl;
			this.trustManager = trustManager;
			this.deactivateHostnameVerification = deactivateHostnameVerification;
			timeoutMillis = (int) Math.min(Integer.MAX_VALUE, Math.max(0, timeout.toMillis()));
		}

		CookieManager getCookieManager() {
			return cookieManager;
		}

		/**
		 * Sends a request without following redirects.
		 *
		 * @param method
		 *            HTTP method, e.g. "GET" or "POST"
		 * @param headers
		 *            request headers, may be null; null values are skipped
		 * @param body
		 *            request body, null for none
		 */
		TransportResponse execute(final String method, final URI uri, final Map<String, String> headers, final String body) throws Exception {
			final HttpRequest request = new HttpRequest(HttpMethod.getHttpMethodByName(method), uri.toString());
			request.setHttpVersion(HttpVersion.HTTP_2);
			request.setMaxRedirects(0);
			request.setConnectionTimeoutMillis(timeoutMillis);
			request.setReadTimeoutMillis(timeoutMillis);
			if (headers != null) {
				for (final Map.Entry<String, String> header : headers.entrySet()) {
					if (header.getValue() != null) {
						request.addHeader(header.getKey(), header.getValue());
					}
				}
			}

			final List<String> cookieHeaderValues = cookieManager.get(uri, Collections.emptyMap()).get("Cookie");
			if (cookieHeaderValues != null && !cookieHeaderValues.isEmpty()) {
				// Set as plain header: HttpRequest.addCookieData would encode "=" within the cookie values (e.g. Base64)
				request.addHeader("Cookie", String.join("; ", cookieHeaderValues));
			}

			if (body != null) {
				request.setRequestBody(body);
			}

			final HttpResponse response = HttpUtilities.executeHttpRequest(request, resolveProxy(proxyUrl, uri), trustManager, deactivateHostnameVerification);
			final TransportResponse transportResponse = new TransportResponse(response.getHttpCode(), response.getContent() == null ? "" : response.getContent(),
					response.getHeaders() == null ? Collections.emptyMap() : response.getHeaders());

			// The network library joins several "Set-Cookie" header lines with a line break
			final String setCookieData = transportResponse.getHeader("Set-Cookie");
			if (setCookieData != null) {
				final Map<String, List<String>> setCookieHeaders = new LinkedHashMap<>();
				setCookieHeaders.put("Set-Cookie", Arrays.stream(setCookieData.split("\n")).filter(line -> !line.isBlank()).toList());
				cookieManager.put(uri, setCookieHeaders);
			}
			return transportResponse;
		}
	}

	/**
	 * Response of {@link HttpTransport#execute(String, URI, Map, String)}.
	 *
	 * @param headers
	 *            response headers; several lines of the same name are joined (", ", for "Set-Cookie" with a line break)
	 */
	private record TransportResponse(int status, String body, Map<String, String> headers) {
		/**
		 * Header value by case insensitive name, null if missing.
		 */
		String getHeader(final String name) {
			for (final Map.Entry<String, String> header : headers.entrySet()) {
				if (header.getKey() != null && header.getKey().equalsIgnoreCase(name)) {
					return header.getValue();
				}
			}
			return null;
		}

		/**
		 * Headers with one list entry per header line ("Set-Cookie") or per header (all others).
		 */
		Map<String, List<String>> getHeaderLists() {
			final Map<String, List<String>> headerLists = new LinkedHashMap<>();
			for (final Map.Entry<String, String> header : headers.entrySet()) {
				if (header.getKey() == null || header.getValue() == null) {
					continue;
				}
				if ("Set-Cookie".equalsIgnoreCase(header.getKey())) {
					headerLists.put(header.getKey(), List.of(header.getValue().split("\n")));
				} else {
					headerLists.put(header.getKey(), List.of(header.getValue()));
				}
			}
			return Collections.unmodifiableMap(headerLists);
		}
	}

	private record HtmlField(String name, String type, String value, boolean checked) {
	}

	/**
	 * Minimal regex based HTML form parser, sufficient for typical identity provider login pages
	 */
	private static class HtmlForm {
		private static final Pattern FORM_PATTERN = Pattern.compile("(?is)<form\\b([^>]*)>(.*?)</form\\s*>");
		private static final Pattern FIELD_PATTERN = Pattern.compile("(?is)<(input|button|select|textarea)\\b([^>]*)>");
		private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*(?:=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");

		private final URI action;
		private final String method;
		private final List<HtmlField> fields = new ArrayList<>();

		private HtmlForm(final URI action, final String method) {
			this.action = action;
			this.method = method;
		}

		static List<HtmlForm> parseAll(final String html, final URI pageUri) {
			final List<HtmlForm> forms = new ArrayList<>();
			final Matcher formMatcher = FORM_PATTERN.matcher(html);
			while (formMatcher.find()) {
				final Map<String, String> formAttributes = parseAttributes(formMatcher.group(1));
				final String actionValue = formAttributes.getOrDefault("action", "").trim();
				URI action;
				try {
					action = actionValue.isEmpty() ? pageUri : pageUri.resolve(actionValue.replace(" ", "%20"));
				} catch (@SuppressWarnings("unused") final IllegalArgumentException e) {
					continue;
				}
				final String method = formAttributes.getOrDefault("method", "get").trim().toUpperCase(Locale.ROOT);
				final HtmlForm form = new HtmlForm(action, "POST".equals(method) ? "POST" : "GET");

				final Matcher fieldMatcher = FIELD_PATTERN.matcher(formMatcher.group(2));
				while (fieldMatcher.find()) {
					final String tag = fieldMatcher.group(1).toLowerCase(Locale.ROOT);
					final Map<String, String> attributes = parseAttributes(fieldMatcher.group(2));
					final String name = attributes.get("name");
					if (name == null || name.isEmpty() || attributes.containsKey("disabled")) {
						continue;
					}
					final String type = switch (tag) {
						case "button" -> attributes.getOrDefault("type", "submit").toLowerCase(Locale.ROOT);
						case "input" -> attributes.getOrDefault("type", "text").toLowerCase(Locale.ROOT);
						default -> tag;
					};
					form.fields.add(new HtmlField(name, type, attributes.getOrDefault("value", ""), attributes.containsKey("checked")));
				}
				forms.add(form);
			}
			return forms;
		}

		private static Map<String, String> parseAttributes(final String attributeString) {
			final Map<String, String> attributes = new LinkedHashMap<>();
			final Matcher matcher = ATTRIBUTE_PATTERN.matcher(attributeString);
			while (matcher.find()) {
				String value = matcher.group(2);
				if (value == null) {
					value = matcher.group(3);
				}
				if (value == null) {
					value = matcher.group(4);
				}
				attributes.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), value == null ? "" : decodeEntities(value));
			}
			return attributes;
		}

		boolean hasField(final String name) {
			return fields.stream().anyMatch(field -> field.name.equals(name));
		}

		String getFirstFieldNameOfType(final String type) {
			return fields.stream().filter(field -> type.equals(field.type)).map(HtmlField::name).findFirst().orElse(null);
		}

		/**
		 * Values a browser would send without user interaction (only the first submit button is "clicked")
		 */
		Map<String, String> getDefaultValues() {
			final Map<String, String> values = new LinkedHashMap<>();
			boolean submitButtonUsed = false;
			for (final HtmlField field : fields) {
				switch (field.type) {
					case "submit", "image" -> {
						if (!submitButtonUsed) {
							values.put(field.name, field.value);
							submitButtonUsed = true;
						}
					}
					case "checkbox", "radio" -> {
						if (field.checked) {
							values.put(field.name, field.value.isEmpty() ? "on" : field.value);
						}
					}
					case "reset", "button", "file", "select" -> {
						// Not sent by this simple client
					}
					default -> values.put(field.name, field.value);
				}
			}
			return values;
		}
	}
}
