package de.soderer.servermanagement.http;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import de.soderer.json.JsonArray;
import de.soderer.json.JsonNode;
import de.soderer.json.JsonObject;
import de.soderer.json.JsonReader;
import de.soderer.network.HttpMethod;
import de.soderer.network.HttpRequest;
import de.soderer.network.HttpResponse;
import de.soderer.servermanagement.config.Login;

/**
 * Requests of a Jenkins login: CSRF crumb (also used as login check) and latest successful build of a job. Basic
 * authentication with username and password (or API token) of the login; TLS trust and proxy of the login as for all
 * other login requests.
 */
public class JenkinsClient {
	private final HttpAccess httpAccess;

	public JenkinsClient(final HttpAccess httpAccess) {
		this.httpAccess = httpAccess;
	}

	/**
	 * Requests a CSRF crumb from "&lt;login url&gt;/crumbIssuer/api/json".
	 *
	 * @return the crumb, or null if the credentials were rejected (HTTP 401/403)
	 * @throws JenkinsCrumbIssuerNotAvailableException
	 *             if the server has no crumb issuer (HTTP 404, CSRF protection disabled)
	 * @throws Exception
	 *             if the server is not reachable, returns an unexpected HTTP code or no valid crumb
	 */
	public String getAuthCrumb(final Login login) throws Exception {
		String jenkinsBaseUrl = HttpAccess.parseHttpUri(login.getUrl()).toString();
		while (jenkinsBaseUrl.endsWith("/")) {
			jenkinsBaseUrl = jenkinsBaseUrl.substring(0, jenkinsBaseUrl.length() - 1);
		}

		final HttpRequest request = new HttpRequest(HttpMethod.GET, jenkinsBaseUrl + "/crumbIssuer/api/json");
		HttpAccess.addBasicAuthorization(request, login);

		final HttpResponse response = httpAccess.execute(request, httpAccess.getProxyForLogin(login, request.getUrl()));
		if (response.getHttpCode() == 200) {
			final JsonNode contentJson;
			try {
				contentJson = JsonReader.readJsonItemString(response.getContent());
			} catch (final Exception e) {
				throw new Exception("Invalid JSON data from Jenkins crumb issuer: " + e.getMessage(), e);
			}
			if (!(contentJson instanceof JsonObject)) {
				throw new Exception("Invalid JSON data from Jenkins crumb issuer: JSON object expected");
			}
			final Object crumb = ((JsonObject) contentJson).getSimpleValue("crumb");
			if (!(crumb instanceof String) || ((String) crumb).isBlank()) {
				throw new Exception("No crumb in response of Jenkins crumb issuer");
			}
			return (String) crumb;
		} else if (response.getHttpCode() == 401 || response.getHttpCode() == 403) {
			return null;
		} else if (response.getHttpCode() == 404) {
			// CSRF protection of the Jenkins server is disabled, so there is no crumb issuer
			throw new JenkinsCrumbIssuerNotAvailableException("Jenkins crumb issuer not available (CSRF protection disabled?): HTTP 404 for " + request.getUrl());
		} else {
			// The response content may help to find the cause, but it must not flood the error message
			throw new Exception("Jenkins crumb request failed: HTTP " + response.getHttpCode() + " for " + request.getUrl() + "\nResponse: " + HttpAccess.shorten(response.getContent(), 500));
		}
	}

	/**
	 * URL of a Jenkins job without query and without trailing "/". The job URL may also be given as API URL, e.g.
	 * "https://jenkins/job/x/api/json?tree=builds[number,result,timestamp]", the API part is removed.
	 */
	public static String getJobUrl(final URI jobUri) {
		String path = jobUri.getRawPath() == null ? "" : jobUri.getRawPath();
		while (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		path = path.replaceFirst("(?i)/api/(json|xml|python)$", "");
		while (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return jobUri.getScheme() + "://" + jobUri.getRawAuthority() + path;
	}

	/**
	 * Reads the builds of a Jenkins job ("&lt;job url&gt;/api/json?tree=builds[number,result,timestamp]", newest build
	 * first) and returns the latest successful build.
	 *
	 * @param jobUri
	 *            URL of the job without username, e.g. "https://jenkins/job/component/job/branch/job/build" (also as API
	 *            URL ".../api/json?tree=...")
	 * @param crumb
	 *            sent as header "Jenkins-Crumb"; null if the server has no crumb issuer
	 * @return the latest successful build, or null if the job has no successful build
	 * @throws Exception
	 *             if the job does not exist (HTTP 404), the request fails or the response is invalid
	 */
	public JenkinsBuild getLatestSuccessfulBuild(final Login login, final URI jobUri, final String crumb) throws Exception {
		final String jobUrl = getJobUrl(jobUri);

		final HttpRequest request = new HttpRequest(HttpMethod.GET, jobUrl + "/api/json?tree=builds[number,result,timestamp]");
		HttpAccess.addBasicAuthorization(request, login);
		if (crumb != null) {
			request.addHeader("Jenkins-Crumb", crumb);
		}

		final HttpResponse response = httpAccess.execute(request, httpAccess.getProxyForLogin(login, request.getUrl()));
		if (response.getHttpCode() == 200) {
			final JsonNode contentJson;
			try {
				contentJson = JsonReader.readJsonItemString(response.getContent());
			} catch (final Exception e) {
				throw new Exception("Invalid JSON data from Jenkins job " + jobUrl + ": " + e.getMessage() + "\nResponse: " + HttpAccess.shorten(response.getContent(), 500), e);
			}
			if (!(contentJson instanceof JsonObject) || !(((JsonObject) contentJson).get("builds") instanceof JsonArray)) {
				throw new Exception("Invalid JSON data from Jenkins job " + jobUrl + ": object with 'builds' array expected");
			}

			final JsonArray builds = (JsonArray) ((JsonObject) contentJson).get("builds");
			boolean isMostCurrent = true;
			for (final Object buildObject : builds) {
				if (!(buildObject instanceof JsonObject)) {
					continue;
				}
				final JsonObject build = (JsonObject) buildObject;
				final Object result = build.getSimpleValue("result");
				if ("SUCCESS".equals(result)) {
					// Numbers may be read as Integer or Long, depending on their size
					final Object numberValue = build.getSimpleValue("number");
					final Object timestampValue = build.getSimpleValue("timestamp");
					if (!(numberValue instanceof Number) || !(timestampValue instanceof Number)) {
						throw new Exception("Invalid build data of Jenkins job " + jobUrl + ": 'number' and 'timestamp' expected");
					}
					final long timestamp = ((Number) timestampValue).longValue();
					final LocalDateTime buildTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
					return new JenkinsBuild(((Number) numberValue).intValue(), timestamp, buildTime, isMostCurrent);
				} else if (result != null && !"ABORTED".equals(result)) {
					// A newer failed or unstable build; still running builds (result null) do not count
					isMostCurrent = false;
				}
			}

			return null;
		} else if (response.getHttpCode() == 404) {
			throw new Exception("Jenkins job not found: HTTP 404 for " + request.getUrl());
		} else {
			throw new Exception("Jenkins build request failed: HTTP " + response.getHttpCode() + " for " + request.getUrl() + "\nResponse: " + HttpAccess.shorten(response.getContent(), 500));
		}
	}
}
