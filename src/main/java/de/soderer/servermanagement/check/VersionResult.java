package de.soderer.servermanagement.check;

/**
 * Result of a version check.
 *
 * @param name
 *            name of the version entry
 * @param text
 *            the version or "ERROR"
 * @param level
 *            WARNING e.g. for a Jenkins version, whose newer builds failed
 * @param message
 *            reason of an error or warning, null if none
 */
public record VersionResult(String name, String text, StatusLevel level, String message) {
	public VersionResult(final String name, final String text, final StatusLevel level) {
		this(name, text, level, null);
	}

	public static VersionResult error(final String name, final String errorMessage) {
		return new VersionResult(name, "ERROR", StatusLevel.ERROR, errorMessage);
	}

	/**
	 * Version text with the reason of an error or warning, e.g. "ERROR (HTTP 404 for ...)".
	 */
	public String getTextWithMessage() {
		return message == null ? text : text + " (" + message + ")";
	}
}
