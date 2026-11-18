package de.soderer.servermanagement.http;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Latest successful build of a Jenkins job.
 *
 * @param number
 *            build number
 * @param timestamp
 *            start time of the build in epoch milliseconds
 * @param buildTime
 *            start time of the build in the local time zone
 * @param isMostCurrent
 *            false if a newer build failed (aborted and still running builds are ignored)
 */
public record JenkinsBuild(int number, long timestamp, LocalDateTime buildTime, boolean isMostCurrent) {
	private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	/**
	 * Text shown as version for an empty version path, e.g. "#123 (2026-11-18 10:40)".
	 */
	public String toDisplayText() {
		return "#" + number + " (" + buildTime.format(DISPLAY_FORMAT) + ")";
	}

	/**
	 * JSON data of the build for version paths "json:...", e.g. "json:$.number".
	 */
	public String toJson() {
		return "{\"number\": " + number
				+ ", \"timestamp\": " + timestamp
				+ ", \"buildTime\": \"" + buildTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\""
				+ ", \"mostCurrent\": " + isMostCurrent + "}";
	}
}
