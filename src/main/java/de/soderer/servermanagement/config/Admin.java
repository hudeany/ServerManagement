package de.soderer.servermanagement.config;

import java.io.File;

import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Utilities;

public class Admin {
	private String username;
	private String privateKeyPath;

	/**
	 * Password or private key passphrase, encrypted with prefix "enc:v1:" (see {@link CredentialCipher}). Older
	 * configurations contain plain text passwords, which are encrypted by {@link #encryptPlainPassword()}.
	 */
	private String password;

	public String getUsername() {
		return username;
	}

	public Admin setUsername(final String username) {
		this.username = username;
		return this;
	}

	public String getPrivateKeyPath() {
		return Utilities.replaceUsersHome(privateKeyPath);
	}

	public File getPrivateKeyFile() {
		return StringUtilities.isBlank(getPrivateKeyPath()) ? null : new File(getPrivateKeyPath());
	}

	public Admin setPrivateKeyPath(final String privateKeyPath) {
		this.privateKeyPath = privateKeyPath;
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
		return password;
	}

	/**
	 * Sets the stored value of the password as it is read from the configuration file (encrypted or, in older
	 * configurations, plain text).
	 *
	 * @deprecated Only for the JSON serialization; use {@link #setPasswordArray(char[])} to set a plain password
	 */
	@Deprecated
	public Admin setPassword(final String password) {
		this.password = password;
		return this;
	}

	/**
	 * Returns the decrypted password, or null if no password is set.
	 */
	public char[] getPasswordArray() {
		return StringUtilities.isBlank(password) ? null : CredentialCipher.getInstance().decrypt(password);
	}

	/**
	 * Encrypts and sets the password; null or an empty password removes it.
	 */
	public Admin setPasswordArray(final char[] password) {
		this.password = password == null || password.length == 0 ? null : CredentialCipher.getInstance().encrypt(password);
		return this;
	}

	/**
	 * Encrypts the password, if it is still stored as plain text.
	 *
	 * @return true, if the password was encrypted
	 */
	public boolean encryptPlainPassword() {
		if (StringUtilities.isNotBlank(password) && !CredentialCipher.isEncrypted(password)) {
			password = CredentialCipher.getInstance().encrypt(password.toCharArray());
			return true;
		} else {
			return false;
		}
	}
}
