package de.soderer.servermanagement.config;

import java.util.ArrayList;
import java.util.List;

public class User {
	private String username;
	private List<String> authorizedKeyIds = new ArrayList<>();
	
	public String getUsername() {
		return username;
	}
	
	public User setUsername(String username) {
		this.username = username;
		return this;
	}
	
	public List<String> getAuthorized_key_ids() {
		return authorizedKeyIds;
	}
	
	public User setAuthorized_key_ids(List<String> authorized_key_ids) {
		this.authorizedKeyIds = authorized_key_ids;
		return this;
	}
}
