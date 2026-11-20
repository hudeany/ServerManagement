package de.soderer.servermanagement.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Server {
	private String name;
	private String host;
	private Admin sshAdmin;
	private List<User> managedUsers = new ArrayList<>();
	private List<String> urls = new ArrayList<>();
	private List<Version> versions = new ArrayList<>();
	private List<Login> logins = new ArrayList<>();
	private List<Log> logs = new ArrayList<>();
	private Set<String> hiddenSections = new LinkedHashSet<>();

	public String getName() {
		return name;
	}

	public Server setName(final String name) {
		this.name = name;
		return this;
	}

	public String getHost() {
		return host;
	}

	public Server setHost(final String host) {
		this.host = host;
		return this;
	}

	public List<String> getUrls() {
		return urls;
	}

	public Server setUrls(final List<String> urls) {
		this.urls = urls;
		return this;
	}

	public Admin getSshAdmin() {
		return sshAdmin;
	}

	public Server setSshAdmin(final Admin sshAdmin) {
		this.sshAdmin = sshAdmin;
		return this;
	}

	public List<User> getManagedUsers() {
		return managedUsers;
	}

	public Server setManagedUsers(final List<User> managedUsers) {
		this.managedUsers = managedUsers;
		return this;
	}

	public List<Version> getVersions() {
		return versions;
	}

	public Server setVersions(final List<Version> versions) {
		this.versions = versions;
		return this;
	}

	public List<Login> getLogins() {
		return logins;
	}

	public Server setLogins(final List<Login> logins) {
		this.logins = logins;
		return this;
	}

	public List<Log> getLogs() {
		return logs;
	}

	public Server setLogs(final List<Log> logs) {
		this.logs = logs;
		return this;
	}

	/**
	 * Ids of the sections hidden in the card of this server (e.g. "logs", "versions"); empty if all sections are shown.
	 */
	public Set<String> getHiddenSections() {
		return hiddenSections;
	}

	public Server setHiddenSections(final Set<String> hiddenSections) {
		this.hiddenSections = hiddenSections == null ? new LinkedHashSet<>() : new LinkedHashSet<>(hiddenSections);
		return this;
	}
}
