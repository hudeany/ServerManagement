package de.soderer.servermanagement.config;

/**
 * Kind of authentication of a login.
 */
public enum LoginType {
	/** Classic login with username and password (HTTP Basic authentication) */
	BasicAuth,

	/** Login via OpenID Connect */
	OIDC;

	/**
	 * Case-insensitive lookup; null or blank values result in CLASSIC, so configurations without login type keep their
	 * former behaviour.
	 */
	public static LoginType getFromString(final String value) {
		if (value == null || value.isBlank()) {
			return BasicAuth;
		}
		for (final LoginType loginType : values()) {
			if (loginType.name().equalsIgnoreCase(value.trim())) {
				return loginType;
			}
		}
		throw new IllegalArgumentException("Unknown login type '" + value + "'");
	}
}
