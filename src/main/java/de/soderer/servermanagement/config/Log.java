package de.soderer.servermanagement.config;

public class Log {
	private String name;
	private String location;
	private String path;

	public String getName() {
		return name;
	}

	public Log setName(final String name) {
		this.name = name;
		return this;
	}

	public String getLocation() {
		return location;
	}

	public Log setLocation(final String location) {
		this.location = location;
		return this;
	}

	public String getPath() {
		return path;
	}

	public Log setPath(final String path) {
		this.path = path;
		return this;
	}
}
