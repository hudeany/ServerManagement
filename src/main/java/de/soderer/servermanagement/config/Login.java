package de.soderer.servermanagement.config;

/**
 * Login to be checked for a server: URL and credentials. The check result (success or not) is not part of the
 * configuration.
 */
public class Login {
	private LoginType loginType = LoginType.BasicAuth;
	private String url;
	private String username;
	private String password;
	private String idpSelection;

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

	public String getPassword() {
		return password;
	}

	public Login setPassword(final String password) {
		this.password = password;
		return this;
	}

	public char[] getPasswordArray() {
		return password == null ? null : password.toCharArray();
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

	@Override
	public String toString() {
		// The password must never appear in log output
		return (username == null ? "" : username) + " @ " + (url == null ? "" : url) + " (" + getLoginType() + ")";
	}
}
