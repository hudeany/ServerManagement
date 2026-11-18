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
 * @param errorMessage
 *            reason of an error, null if unknown or no error
 */
public record VersionResult(String name, String text, StatusLevel level, String errorMessage) {
	public VersionResult(final String name, final String text, final StatusLevel level) {
		this(name, text, level, null);
	}

	public static VersionResult error(final String name, final String errorMessage) {
		return new VersionResult(name, "ERROR", StatusLevel.ERROR, errorMessage);
	}

	/**
	 * Version text with the error reason, e.g. "ERROR (HTTP 404 for ...)".
	 */
	public String getTextWithErrorMessage() {
		return errorMessage == null ? text : text + " (" + errorMessage + ")";
	}
}
