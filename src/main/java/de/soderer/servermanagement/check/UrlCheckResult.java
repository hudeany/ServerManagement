package de.soderer.servermanagement.check;

import java.time.Duration;
import java.time.Instant;

/**
 * Result of an URL check.
 *
 * @param timeInSeconds
 *            response time, negative if the URL is not available
 * @param certificateNotAfter
 *            end of the validity of the server certificate, null for plain http or if no certificate was received
 */
public record UrlCheckResult(String url, int timeInSeconds, Instant certificateNotAfter) {
	/** Response times above this limit are shown as warning */
	public static final int SLOW_RESPONSE_SECONDS = 5;

	/** Certificates valid for less than this number of days are shown as warning */
	public static final int CERTIFICATE_WARNING_DAYS = 30;

	/** Certificates valid for less than this number of days (or already expired) are shown as error */
	public static final int CERTIFICATE_ERROR_DAYS = 14;

	/**
	 * Result without information about the server certificate.
	 */
	public UrlCheckResult(final String url, final int timeInSeconds) {
		this(url, timeInSeconds, null);
	}

	public static UrlCheckResult error(final String url) {
		return new UrlCheckResult(url, -1, null);
	}

	/**
	 * Unavailable URL, whose server certificate may still be known (e.g. an expired certificate).
	 */
	public static UrlCheckResult error(final String url, final Instant certificateNotAfter) {
		return new UrlCheckResult(url, -1, certificateNotAfter);
	}

	public boolean isError() {
		return timeInSeconds < 0;
	}

	/**
	 * Worst level of the response check and the certificate check; this is the level of the URL for the status of the
	 * server item.
	 */
	public StatusLevel getLevel() {
		return getResponseLevel().max(getCertificateLevel());
	}

	/**
	 * Level of availability and response time.
	 */
	public StatusLevel getResponseLevel() {
		if (isError()) {
			return StatusLevel.ERROR;
		} else if (timeInSeconds > SLOW_RESPONSE_SECONDS) {
			return StatusLevel.WARNING;
		} else {
			return StatusLevel.OK;
		}
	}

	/**
	 * Level of the remaining validity of the server certificate; OK if no certificate is known.
	 */
	public StatusLevel getCertificateLevel() {
		final Long daysLeft = getCertificateDaysLeft();
		if (daysLeft == null) {
			return StatusLevel.OK;
		} else if (daysLeft < CERTIFICATE_ERROR_DAYS) {
			return StatusLevel.ERROR;
		} else if (daysLeft < CERTIFICATE_WARNING_DAYS) {
			return StatusLevel.WARNING;
		} else {
			return StatusLevel.OK;
		}
	}

	/**
	 * Remaining validity of the server certificate in whole days (negative if expired).
	 *
	 * @return remaining days, null if no certificate is known
	 */
	public Long getCertificateDaysLeft() {
		if (certificateNotAfter == null) {
			return null;
		}
		final Duration remaining = Duration.between(Instant.now(), certificateNotAfter);
		// Expired since a few hours counts as expired (-1), not as 0 days left
		return remaining.isNegative() ? Math.floorDiv(remaining.toSeconds(), 86_400L) : remaining.toDays();
	}

	public boolean isCertificateExpired() {
		return certificateNotAfter != null && certificateNotAfter.isBefore(Instant.now());
	}

	/**
	 * Text shown in the URLs table.
	 */
	public String getText() {
		return isError() ? "ERROR" : timeInSeconds + " sec";
	}
}
