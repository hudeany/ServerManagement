package de.soderer.servermanagement.http;

import java.io.File;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import de.soderer.applicationbase.ApplicationConfigurationProperties;
import de.soderer.network.HttpRequest;
import de.soderer.network.HttpResponse;
import de.soderer.network.HttpUtilities;
import de.soderer.network.trustmanager.TrustManagerUtilities;
import de.soderer.servermanagement.config.Login;
import de.soderer.utilities.StringUtilities;

/**
 * HTTP access of the server checks: TLS trust, proxies and Basic authentication of logins. All requests use the same
 * TLS trust: the keystore configured in the servers configuration, otherwise the default trust store of the JVM
 * (cacerts); the hostname is always verified.
 */
public class HttpAccess {
	/** The hostname is always verified */
	private static final boolean DEACTIVATE_HOSTNAME_VERIFICATION = false;

	private final ApplicationConfigurationProperties applicationConfiguration;
	private final TrustManager trustManager;

	/**
	 * @param keystorePath
	 *            keystore with the trusted certificates; blank for the default trust store of the JVM
	 */
	public HttpAccess(final ApplicationConfigurationProperties applicationConfiguration, final String keystorePath) throws Exception {
		this.applicationConfiguration = applicationConfiguration;
		if (StringUtilities.isNotBlank(keystorePath)) {
			trustManager = TrustManagerUtilities.createTrustManagerForKeyStore(new File(keystorePath));
		} else {
			trustManager = createJvmDefaultTrustManager();
		}
	}

	public TrustManager getTrustManager() {
		return trustManager;
	}

	/**
	 * Executes a request with the TLS trust of the server checks.
	 */
	public HttpResponse execute(final HttpRequest request, final Proxy proxy) throws Exception {
		return HttpUtilities.executeHttpRequest(request, proxy, trustManager, DEACTIVATE_HOSTNAME_VERIFICATION);
	}

	/**
	 * Proxy of the application configuration for requests without login.
	 */
	public Proxy getApplicationProxy(final String url) throws Exception {
		return applicationConfiguration.getProxyConfiguration().getProxy(url);
	}

	/**
	 * Proxy for login requests: the proxy of the login, otherwise the proxy of the application configuration.
	 */
	public Proxy getProxyForLogin(final Login login, final String url) throws Exception {
		final Proxy loginProxy = HttpUtilities.createProxy(login.getProxyUrl());
		return loginProxy != null ? loginProxy : getApplicationProxy(url);
	}

	/**
	 * Proxy for the requests of a version location with login: the same proxy as for the OIDC login, so the proxy of the
	 * login, or the system proxy settings if the login has no proxy.
	 */
	public static Proxy getSessionProxy(final Login login, final URI targetUri) {
		return HttpUtilities.resolveProxy(login.getProxyUrl(), targetUri);
	}

	/**
	 * Adds the header for HTTP Basic authentication with username and password of the login.
	 */
	public static void addBasicAuthorization(final HttpRequest request, final Login login) {
		final char[] password = login.getPasswordArray();
		final String credentials = (login.getUsername() == null ? "" : login.getUsername()) + ":" + (password == null ? "" : new String(password));
		if (password != null) {
			// The decrypted password is not needed anymore
			Arrays.fill(password, '\0');
		}
		request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * Parses an absolute http(s) URL.
	 *
	 * @throws Exception
	 *             if the URL is invalid or no http(s) URL
	 */
	public static URI parseHttpUri(final String url) throws Exception {
		try {
			final URI uri = URI.create(url == null ? "" : url.trim());
			if (uri.getScheme() == null || uri.getHost() == null
					|| !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
				throw new IllegalArgumentException();
			}
			return uri;
		} catch (@SuppressWarnings("unused") final IllegalArgumentException e) {
			throw new Exception("Invalid URL: " + url);
		}
	}

	/**
	 * Shortens a text for error messages.
	 */
	public static String shorten(final String text, final int maxLength) {
		if (text == null) {
			return "<null>";
		} else if (text.length() <= maxLength) {
			return text;
		} else {
			return text.substring(0, maxLength) + "... (" + text.length() + " chars)";
		}
	}

	/**
	 * Trust manager of the default trust store of the JVM (cacerts, or the trust store set by
	 * -Djavax.net.ssl.trustStore).
	 */
	private static TrustManager createJvmDefaultTrustManager() throws Exception {
		final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		// null: default trust store of the JVM
		trustManagerFactory.init((KeyStore) null);
		for (final TrustManager defaultTrustManager : trustManagerFactory.getTrustManagers()) {
			if (defaultTrustManager instanceof X509TrustManager) {
				return defaultTrustManager;
			}
		}
		throw new Exception("No default X509 trust manager of the JVM available");
	}
}
