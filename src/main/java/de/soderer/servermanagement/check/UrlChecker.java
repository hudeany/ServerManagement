package de.soderer.servermanagement.check;

import java.util.ArrayList;
import java.util.List;

import de.soderer.network.HttpMethod;
import de.soderer.network.HttpRequest;
import de.soderer.network.HttpResponse;
import de.soderer.servermanagement.http.HttpAccess;

/**
 * Checks the availability and response time of URLs (GET without login).
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
	 * HTTP 200, 302 and 400 count as available (the server answers), everything else as error.
	 */
	public UrlCheckResult check(final String url) {
		try {
			final HttpRequest request = new HttpRequest(HttpMethod.GET, url);
			final long start = System.nanoTime();
			final HttpResponse response = httpAccess.execute(request, httpAccess.getApplicationProxy(request.getUrl()));
			final int timeInSeconds = (int) ((System.nanoTime() - start) / 1_000_000_000L);
			final int httpCode = response.getHttpCode();
			if (httpCode == 200 || httpCode == 302 || httpCode == 400) {
				return new UrlCheckResult(url, timeInSeconds);
			} else {
				return UrlCheckResult.error(url);
			}
		} catch (@SuppressWarnings("unused") final Exception e) {
			return UrlCheckResult.error(url);
		}
	}
}
