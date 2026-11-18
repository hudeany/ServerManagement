package de.soderer.servermanagement.check;

/**
 * Result of a login check.
 */
public enum LoginCheckResult {
	/** Login successful (HTTP 2xx, OIDC session, Jenkins crumb) */
	OK("OK", StatusLevel.OK),

	/** Credentials rejected (HTTP 401/403, failed OIDC login) */
	FAILED("FAILED", StatusLevel.ERROR),

	/** Anything else, e.g. server not reachable or unexpected HTTP code */
	ERROR("ERROR", StatusLevel.ERROR),

	/** The login type cannot be checked */
	NOT_SUPPORTED("n/a", StatusLevel.WARNING);

	private final String text;
	private final StatusLevel level;

	LoginCheckResult(final String text, final StatusLevel level) {
		this.text = text;
		this.level = level;
	}

	/**
	 * Text shown in the logins table.
	 */
	public String getText() {
		return text;
	}

	public StatusLevel getLevel() {
		return level;
	}
}
