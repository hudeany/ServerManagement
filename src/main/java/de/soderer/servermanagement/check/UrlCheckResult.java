package de.soderer.servermanagement.check;

/**
 * Result of an URL check.
 *
 * @param timeInSeconds
 *            response time, negative if the URL is not available
 */
public record UrlCheckResult(String url, int timeInSeconds) {
	/** Response times above this limit are shown as warning */
	public static final int SLOW_RESPONSE_SECONDS = 5;

	public static UrlCheckResult error(final String url) {
		return new UrlCheckResult(url, -1);
	}

	public boolean isError() {
		return timeInSeconds < 0;
	}

	public StatusLevel getLevel() {
		if (isError()) {
			return StatusLevel.ERROR;
		} else if (timeInSeconds > SLOW_RESPONSE_SECONDS) {
			return StatusLevel.WARNING;
		} else {
			return StatusLevel.OK;
		}
	}

	/**
	 * Text shown in the URLs table.
	 */
	public String getText() {
		return isError() ? "ERROR" : timeInSeconds + " sec";
	}
}
