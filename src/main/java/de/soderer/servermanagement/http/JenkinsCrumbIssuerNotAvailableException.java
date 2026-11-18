package de.soderer.servermanagement.http;

/**
 * The Jenkins server has no crumb issuer (HTTP 404), because its CSRF protection is disabled.
 */
public class JenkinsCrumbIssuerNotAvailableException extends Exception {
	private static final long serialVersionUID = 6170932861594027851L;

	public JenkinsCrumbIssuerNotAvailableException(final String message) {
		super(message);
	}
}
