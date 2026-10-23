package de.soderer.servermanagement.config;

import java.io.File;

import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Utilities;

public class Admin {
	private String username;
	private String privateKeyPath;
	private String password;
	
	public String getUsername() {
		return username;
	}
	
	public Admin setUsername(String username) {
		this.username = username;
		return this;
	}
	
	public String getPrivateKeyPath() {
		return Utilities.replaceUsersHome(privateKeyPath);
	}

	public File getPrivateKeyFile() {
		return StringUtilities.isBlank(getPrivateKeyPath()) ? null : new File(getPrivateKeyPath());
	}
	
	public Admin setPrivateKeyPath(String privateKeyPath) {
		this.privateKeyPath = privateKeyPath;
		return this;
	}
	
	public String getPassword() {
		return password;
	}

	public char[] getPasswordArray() {
		return StringUtilities.isBlank(getPassword()) ? null : getPassword().toCharArray();
	}
	
	public Admin setPassword(String password) {
		this.password = password;
		return this;
	}
}
