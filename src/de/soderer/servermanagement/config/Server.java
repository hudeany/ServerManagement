package de.soderer.servermanagement.config;

import java.util.ArrayList;
import java.util.List;

public class Server {
	private String name;
	private String host;
	private Admin sshAdmin;
	private List<User> managedUsers = new ArrayList<>();
	private List<String> urls = new ArrayList<>();
	private List<String> versionFiles = new ArrayList<>();
	
	public String getName() {
		return name;
	}
	
	public Server setName(String name) {
		this.name = name;
		return this;
	}
	
	public String getHost() {
		return host;
	}
	
	public Server setHost(String host) {
		this.host = host;
		return this;
	}
	
	public List<String> getUrls() {
		return urls;
	}
	
	public Server setUrls(List<String> urls) {
		this.urls = urls;
		return this;
	}
	
	public Admin getSshAdmin() {
		return sshAdmin;
	}
	
	public Server setSshAdmin(Admin sshAdmin) {
		this.sshAdmin = sshAdmin;
		return this;
	}
	
	public List<User> getManagedUsers() {
		return managedUsers;
	}
	
	public Server setManagedUsers(List<User> managedUsers) {
		this.managedUsers = managedUsers;
		return this;
	}
	
	public List<String> getversionFiles() {
		return versionFiles;
	}
	
	public Server setversionFiles(List<String> versionFiles) {
		this.versionFiles = versionFiles;
		return this;
	}
}
