package de.soderer.servermanagement.config;

/**
 * Login to be checked for a server: URL and credentials. The check result (success or not) is not part of the
 * configuration.
 */
public class Login {
	private LoginType loginType = LoginType.BasicAuth;
	private String url;
	private String username;

	/**
	 * Password, encrypted with prefix "enc:v1:" (see {@link CredentialCipher}). Older configurations contain plain text
	 * passwords, which are encrypted by {@link #encryptPlainPassword()}.
	 */
	private String password;

	private String idpSelection;

	/**
	 * Optional proxy for the login check, e.g. "http://proxy.example.com:3128" or "none" for a direct connection; null
	 * means the system proxy settings are used.
	 */
	private String proxyUrl;

	/**
	 * Kind of authentication; never null (configurations without login type are CLASSIC).
	 */
	public LoginType getLoginType() {
		return loginType == null ? LoginType.BasicAuth : loginType;
	}

	public Login setLoginType(final LoginType loginType) {
		this.loginType = loginType == null ? LoginType.BasicAuth : loginType;
		return this;
	}

	public String getUrl() {
		return url;
	}

	public Login setUrl(final String url) {
		this.url = url;
		return this;
	}

	public String getUsername() {
		return username;
	}

	public Login setUsername(final String username) {
		this.username = username;
		return this;
	}

	/**
	 * Stored value of the password as it is written to the configuration file (normally encrypted with prefix
	 * "enc:v1:").
	 *
	 * @deprecated Only for the JSON serialization; use {@link #getPasswordArray()} for the plain password
	 */
	@Deprecated
	public String getPassword() {
		return password == null || password.isEmpty() ? null : new String(CredentialCipher.getInstance().decrypt(password));
	}

	/**
	 * Sets the stored value of the password as it is read from the configuration file (encrypted or, in older
	 * configurations, plain text).
	 *
	 * @deprecated Only for the JSON serialization; use {@link #setPasswordArray(char[])} to set a plain password
	 */
	@Deprecated
	public Login setPassword(final String password) {
		this.password = password;
		return this;
	}

	/**
	 * Returns the decrypted password, or null if no password is set.
	 */
	public char[] getPasswordArray() {
		return password == null || password.isEmpty() ? null : CredentialCipher.getInstance().decrypt(password);
	}

	/**
	 * Checks whether a password is set, without decrypting it.
	 */
	public boolean hasPassword() {
		return password != null && !password.isEmpty();
	}

	/**
	 * Encrypts and sets the password; null or an empty password removes it.
	 */
	public Login setPasswordArray(final char[] password) {
		this.password = password == null || password.length == 0 ? null : CredentialCipher.getInstance().encrypt(password);
		return this;
	}

	/**
	 * Encrypts the password, if it is still stored as plain text.
	 *
	 * @return true, if the password was encrypted
	 */
	public boolean encryptPlainPassword() {
		if (password != null && !password.isEmpty() && !CredentialCipher.isEncrypted(password)) {
			password = CredentialCipher.getInstance().encrypt(password.toCharArray());
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Optional IdP selection for OIDC logins (e.g. if the login page offers several identity providers); null if not used.
	 */
	public String getIdpSelection() {
		return idpSelection;
	}

	public Login setIdpSelection(final String idpSelection) {
		this.idpSelection = idpSelection;
		return this;
	}

	/**
	 * Optional proxy URL for the login check ("http://host:port", "host:port" or "none"); null if the system proxy
	 * settings are used.
	 */
	public String getProxyUrl() {
		return proxyUrl;
	}

	public Login setProxyUrl(final String proxyUrl) {
		this.proxyUrl = proxyUrl;
		return this;
	}

	@Override
	public String toString() {
		// The password must never appear in log output
		return (username == null ? "" : username) + " @ " + (url == null ? "" : url) + " (" + getLoginType() + ")";
	}
}
