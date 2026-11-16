package de.soderer.servermanagement;

import java.io.Console;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Smoke test for web applications protected by an OIDC login (authorization code flow).
 *
 * Behaves like a simple browser without JavaScript: follows redirects, keeps cookies,
 * fills and submits the login form of the identity provider (username/password, also
 * two-step forms), submits auto-post forms (response_mode=form_post) and finally checks
 * whether the main page of the web application is delivered successfully.
 *
 * No external dependencies, Java 17+.
 *
 * Exit codes: 0 = OK, 1 = check failed, 2 = usage error
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

	private final boolean verbose;
	private final boolean quiet;
	private final HttpClient httpClient;
	private final String usernameFieldOverride;
	private final String passwordFieldOverride;
	private final Duration requestTimeout;
	private final String finalHtmlDumpPath;
	private final String tokenTypeFilter;
	private final String idpSelectionText;
	private String securityTokenType;
	private String securityToken;

	public static void main(final String[] args) {
		try {
			System.exit(run(args));
		} catch (final UsageException e) {
			System.err.println("Error: " + e.getMessage());
			System.err.println();
			printUsage();
			System.exit(2);
		}
	}

	private static int run(final String[] args) throws UsageException {
		String url = null;
		String user = null;
		String password = null;
		String expectText = null;
		String usernameField = null;
		String passwordField = null;
		String dumpFinalHtmlPath = null;
		String tokenType = null;
		String idpSelection = null;
		String proxyUrl = null;
		boolean insecure = false;
		boolean verbose = false;
		boolean quiet = false;
		int timeoutSeconds = 30;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "-u", "-user" -> user = nextArg(args, ++i, args[i - 1]);
				case "-p", "-password" -> password = nextArg(args, ++i, args[i - 1]);
				case "-expect" -> expectText = nextArg(args, ++i, args[i - 1]);
				case "-userfield" -> usernameField = nextArg(args, ++i, args[i - 1]);
				case "-passfield" -> passwordField = nextArg(args, ++i, args[i - 1]);
				case "-dump-final-html" -> dumpFinalHtmlPath = nextArg(args, ++i, args[i - 1]);
				case "-token-type" -> tokenType = nextArg(args, ++i, args[i - 1]);
				case "-idp", "-idp-button", "-idp-provider" -> idpSelection = nextArg(args, ++i, args[i - 1]);
				case "-proxy" -> proxyUrl = nextArg(args, ++i, args[i - 1]);
				case "-timeout" -> {
					try {
						timeoutSeconds = Integer.parseInt(nextArg(args, ++i, args[i - 1]));
					} catch (@SuppressWarnings("unused") final NumberFormatException e) {
						throw new UsageException("Invalid timeout value");
					}
				}
				case "-insecure" -> insecure = true;
				case "-v", "-verbose" -> verbose = true;
				case "-q", "-quiet" -> quiet = true;
				case "-h", "-help", "--help", "-?" -> {
					printUsage();
					return 0;
				}
				default -> {
					if (args[i].startsWith("-")) {
						throw new UsageException("Unknown option: " + args[i]);
					} else if (url != null) {
						throw new UsageException("More than one URL given");
					}
					url = args[i];
				}
			}
		}

		if (url == null) {
			throw new UsageException("Missing URL");
		}
		if (user == null) {
			user = System.getenv("OIDC_USER");
		}
		if (password == null) {
			password = System.getenv("OIDC_PASSWORD");
		}
		if (user == null) {
			throw new UsageException("Missing username (-u or environment variable OIDC_USER)");
		}
		if (password == null) {
			final Console console = System.console();
			if (console == null) {
				throw new UsageException("Missing password (-p, environment variable OIDC_PASSWORD or interactive console)");
			}
			final char[] passwordChars = console.readPassword("Password for %s: ", user);
			if (passwordChars == null) {
				throw new UsageException("No password entered");
			}
			password = new String(passwordChars);
		}

		final URI startUri;
		try {
			startUri = URI.create(url);
			if (startUri.getScheme() == null || startUri.getHost() == null) {
				throw new IllegalArgumentException();
			}
		} catch (@SuppressWarnings("unused") final IllegalArgumentException e) {
			throw new UsageException("Invalid URL: " + url);
		}

		if (tokenType != null && !("id_token".equals(tokenType) || "access_token".equals(tokenType) || "code".equals(tokenType))) {
			throw new UsageException("Invalid token type for -token-type (allowed: id_token, access_token, code)");
		}

		try {
			validateProxyUrl(proxyUrl);
		} catch (final IllegalArgumentException e) {
			throw new UsageException(e.getMessage());
		}

		try {
			final OidcLoginCheck check = new OidcLoginCheck(insecure, verbose, quiet, Duration.ofSeconds(timeoutSeconds), usernameField, passwordField,
					dumpFinalHtmlPath, tokenType, idpSelection, proxyUrl);
			return check.check(startUri, user, password, expectText) ? 0 : 1;
		} catch (final Exception e) {
			System.out.println("RESULT: FAILED - " + e.getClass().getSimpleName() + ": " + e.getMessage());
			if (verbose) {
				e.printStackTrace(System.out);
			}
			return 1;
		}
	}

	private static String nextArg(final String[] args, final int index, final String option) throws UsageException {
		if (index >= args.length) {
			throw new UsageException("Missing value for option " + option);
		}
		return args[index];
	}

	private static void printUsage() {
		System.out.println("""
				OidcLoginCheck - checks if a web application with OIDC login is deployed and running

				Usage: java -jar OidcLoginCheck.jar <url> -u <user> [-p <password>] [options]

				Options:
				  -u, -user <name>        Username for the identity provider (or env OIDC_USER)
				  -p, -password <pw>      Password (or env OIDC_PASSWORD, otherwise interactive prompt)
				  -expect <text>          Text that must be contained in the final page
				  -userfield <name>       Name of the username input field (auto-detected by default)
				  -passfield <name>       Name of the password input field (auto-detected by default)
				  -dump-final-html <file> Write final HTML response body to this file path
				  -token-type <name>      Token output type: id_token, access_token or code
				  -idp <text>             Optional IdP button/provider text to click before login (federated flow)
				  -proxy <url>            HTTP proxy ("http://host:port" or "host:port"), "none" for a direct
				                          connection (default: system proxy settings / JVM proxy properties)
				  -timeout <seconds>      Timeout per request (default 30)
				  -insecure               Accept any TLS certificate and hostname (test environments only)
				  -v, -verbose            Log every request and redirect
				  -q, -quiet              Only print token and final result

				Exit codes: 0 = OK, 1 = check failed, 2 = usage error""");
	}

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
		this.verbose = verbose;
		this.quiet = quiet;
		this.usernameFieldOverride = usernameFieldOverride;
		this.passwordFieldOverride = passwordFieldOverride;
		this.finalHtmlDumpPath = finalHtmlDumpPath;
		this.tokenTypeFilter = tokenTypeFilter;
		this.idpSelectionText = idpSelectionText;

		final HttpClient.Builder builder = HttpClient.newBuilder()
				.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
				// Redirects are followed manually to keep track of the hops
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(timeout)
				// Explicit proxy, or the system default (honors -Dhttps.proxyHost etc. and -Djava.net.useSystemProxies=true)
				.proxy(createProxySelector(proxyUrl));
		log("Proxy: " + describeProxy(proxyUrl));

		if (insecure) {
			// Must be set before the first HttpClient is created
			System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
			final SSLContext sslContext = SSLContext.getInstance("TLS");
			sslContext.init(null, new TrustManager[] { new X509TrustManager() {
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
			} }, new SecureRandom());
			builder.sslContext(sslContext);
		}

		httpClient = builder.build();
		requestTimeout = timeout;
	}

	/**
	 * Executes the complete login flow and checks the resulting page.
	 *
	 * @return true if the web application main page was delivered successfully after login
	 */
	public boolean check(final URI startUri, final String user, final String password, final String expectText) throws Exception {
		final long startTime = System.currentTimeMillis();
		if (!quiet) {
			System.out.println("Checking " + startUri);
		}
		securityTokenType = null;
		securityToken = null;

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

		if (!quiet) {
			printPageInfo(page, startTime);
		}
		if (securityToken != null) {
			System.out.println("Security token (" + securityTokenType + "): " + securityToken);
		} else {
			System.out.println("Security token: <not found>");
		}
		System.out.println("RESULT: OK");
		return true;
	}

	private boolean fail(final String message, final Page page, final long startTime) {
		if (!quiet) {
			printPageInfo(page, startTime);
		}
		if (securityToken != null) {
			System.out.println("Security token (" + securityTokenType + "): " + securityToken);
		}
		dumpFinalHtmlIfRequested(page);
		System.out.println("RESULT: FAILED - " + message);
		return false;
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
			final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
					.timeout(requestTimeout)
					.header("User-Agent", USER_AGENT)
					.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
					.header("Accept-Language", "de,en;q=0.8");
			if (currentReferer != null) {
				requestBuilder.header("Referer", currentReferer.toString());
			}
			if ("POST".equals(method)) {
				requestBuilder.header("Content-Type", "application/x-www-form-urlencoded");
				if (currentReferer != null) {
					requestBuilder.header("Origin", currentReferer.getScheme() + "://" + currentReferer.getRawAuthority());
				}
				requestBuilder.POST(HttpRequest.BodyPublishers.ofString(formBody == null ? "" : formBody, StandardCharsets.UTF_8));
			} else {
				requestBuilder.GET();
			}

			final HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
			captureSecurityTokenFromUri(uri);
			final int status = response.statusCode();
			log(method + " " + uri + " -> " + status);

			final String location = response.headers().firstValue("Location").orElse(null);
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

			return new Page(uri, status, response.body() == null ? "" : response.body());
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

	private static class UsageException extends Exception {
		private static final long serialVersionUID = 1L;

		UsageException(final String message) {
			super(message);
		}
	}
}
