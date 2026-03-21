package de.soderer.servermanagement.config;

public class Version {
	private String name;
	private String location;
	private String path;

	public String getName() {
		return name;
	}

	public Version setName(final String name) {
		this.name = name;
		return this;
	}

	public String getLocation() {
		return location;
	}

	public Version setLocation(final String location) {
		this.location = location;
		return this;
	}

	public String getPath() {
		return path;
	}

	public Version setPath(final String path) {
		this.path = path;
		return this;
	}
}
