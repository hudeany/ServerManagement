package de.soderer.servermanagement;

/**
 * The Class ServerManagementException.
 */
public class ServerManagementException extends Exception {
	/** The Constant serialVersionUID. */
	private static final long serialVersionUID = 6039775378389122712L;

	public ServerManagementException(final String errorMessage) {
		super(errorMessage);
	}

	public ServerManagementException(final String errorMessage, final Exception e) {
		super(errorMessage, e);
	}
}
