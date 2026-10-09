package de.soderer.servermanagement.config;

import java.util.ArrayList;
import java.util.List;

import de.soderer.utilities.Utilities;

public class ServerManagementConfiguration {
	private String keystore;
	private List<Server> servers = new ArrayList<>();
	private List<KnownPublicSshKey> knownPublicSshKeys = new ArrayList<>();

	public String getKeystore() {
		return keystore;
	}

	public void setKeystore(final String keystore) {
		this.keystore = keystore;
	}

	public List<Server> getServers() {
		return servers;
	}

	public void setServers(final List<Server> servers) {
		this.servers = servers;
	}

	public List<KnownPublicSshKey> getKnownPublicSshKeys() {
		return knownPublicSshKeys;
	}

	public KnownPublicSshKey getKnownPublicSshKeyByKeyId(final String keyId) {
		if (Utilities.isBlank(keyId)) {
			return null;
		} else {
			for (final KnownPublicSshKey knownPublicSshKey : knownPublicSshKeys) {
				if (keyId.equals(knownPublicSshKey.getKeyId())) {
					return knownPublicSshKey;
				}
			}
			return null;
		}
	}

	public void setKnownPublicSshKeys(final List<KnownPublicSshKey> knownPublicSshKeys) {
		this.knownPublicSshKeys = knownPublicSshKeys;
	}
}
