package de.soderer.servermanagement.check;

import java.util.ArrayList;
import java.util.List;

import de.soderer.network.HttpMethod;
import de.soderer.network.HttpRequest;
import de.soderer.network.HttpResponse;
import de.soderer.servermanagement.config.Login;
import de.soderer.servermanagement.config.LoginType;
import de.soderer.servermanagement.http.HttpAccess;
import de.soderer.servermanagement.http.JenkinsClient;
import de.soderer.servermanagement.http.OidcClient;

/**
 * Checks logins: BasicAuth logins via HTTP Basic authentication, OIDC logins by a complete OIDC login, Jenkins logins
 * by requesting a CSRF crumb.
 */
public class LoginChecker {
	private final HttpAccess httpAccess;
	private final OidcClient oidcClient;
	private final JenkinsClient jenkinsClient;

	public LoginChecker(final HttpAccess httpAccess, final OidcClient oidcClient, final JenkinsClient jenkinsClient) {
		this.httpAccess = httpAccess;
		this.oidcClient = oidcClient;
		this.jenkinsClient = jenkinsClient;
	}

	/**
	 * Checks each login separately, so a failing login does not abort the check of further logins.
	 *
	 * @return the results in the order of the logins
	 */
	public List<LoginCheckResult> check(final List<Login> logins) {
		final List<LoginCheckResult> results = new ArrayList<>();
		if (logins != null) {
			for (final Login login : logins) {
				results.add(check(login));
			}
		}
		return results;
	}

	public LoginCheckResult check(final Login login) {
		if (login.getLoginType() == LoginType.BasicAuth) {
			try {
				final HttpRequest request = new HttpRequest(HttpMethod.GET, login.getUrl());
				HttpAccess.addBasicAuthorization(request, login);
				final HttpResponse response = httpAccess.execute(request, httpAccess.getProxyForLogin(login, request.getUrl()));
				final int httpCode = response.getHttpCode();
				if (httpCode >= 200 && httpCode < 300) {
					return LoginCheckResult.OK;
				} else if (httpCode == 401 || httpCode == 403) {
					return LoginCheckResult.FAILED;
				} else {
					return LoginCheckResult.ERROR;
				}
			} catch (@SuppressWarnings("unused") final Exception e) {
				return LoginCheckResult.ERROR;
			}
		} else if (login.getLoginType() == LoginType.OIDC) {
			try {
				return oidcClient.login(login) != null ? LoginCheckResult.OK : LoginCheckResult.FAILED;
			} catch (@SuppressWarnings("unused") final Exception e) {
				return LoginCheckResult.FAILED;
			}
		} else if (login.getLoginType() == LoginType.Jenkins) {
			try {
				return jenkinsClient.getAuthCrumb(login) != null ? LoginCheckResult.OK : LoginCheckResult.FAILED;
			} catch (@SuppressWarnings("unused") final Exception e) {
				return LoginCheckResult.ERROR;
			}
		} else {
			return LoginCheckResult.ERROR;
		}
	}
}
