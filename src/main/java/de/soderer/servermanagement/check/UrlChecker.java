package de.soderer.servermanagement.check;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import de.soderer.network.HttpMethod;
import de.soderer.network.HttpRequest;
import de.soderer.network.HttpResponse;
import de.soderer.servermanagement.http.CertificateCapturingTrustManager;
import de.soderer.servermanagement.http.HttpAccess;

/**
 * Checks the availability and response time of URLs (GET without login) and the remaining validity of the server
 * certificate of https URLs.
 */
public class UrlChecker {
	private final HttpAccess httpAccess;

	public UrlChecker(final HttpAccess httpAccess) {
		this.httpAccess = httpAccess;
	}

	/**
	 * @return the results in the order of the URLs
	 */
	public List<UrlCheckResult> check(final List<String> urls) {
		final List<UrlCheckResult> results = new ArrayList<>();
		if (urls != null) {
			for (final String url : urls) {
				results.add(check(url));
			}
		}
		return results;
	}

	/**
	 * HTTP 200, 302 and 400 count as available (the server answers), everything else as error. The server certificate
	 * is also reported for unavailable URLs, if the TLS handshake took place (e.g. an expired certificate).
	 */
	public UrlCheckResult check(final String url) {
		// One instance per request, so the certificate belongs to this URL
		final CertificateCapturingTrustManager capturingTrustManager = httpAccess.createCertificateCapturingTrustManager();
		try {
			final HttpRequest request = new HttpRequest(HttpMethod.GET, url);
			final long start = System.nanoTime();
			final HttpResponse response = httpAccess.execute(request, httpAccess.getApplicationProxy(request.getUrl()), capturingTrustManager);
			final int timeInSeconds = (int) ((System.nanoTime() - start) / 1_000_000_000L);
			final int httpCode = response.getHttpCode();
			if (httpCode == 200 || httpCode == 302 || httpCode == 400) {
				return new UrlCheckResult(url, timeInSeconds, getCertificateNotAfter(capturingTrustManager));
			} else {
				return UrlCheckResult.error(url, getCertificateNotAfter(capturingTrustManager));
			}
		} catch (@SuppressWarnings("unused") final Exception e) {
			return UrlCheckResult.error(url, getCertificateNotAfter(capturingTrustManager));
		}
	}

	/**
	 * End of the validity of the server certificate, null if no certificate was received.
	 */
	private static Instant getCertificateNotAfter(final CertificateCapturingTrustManager capturingTrustManager) {
		if (capturingTrustManager == null) {
			return null;
		}
		final X509Certificate serverCertificate = capturingTrustManager.getServerCertificate();
		return serverCertificate == null ? null : serverCertificate.getNotAfter().toInstant();
	}
}
