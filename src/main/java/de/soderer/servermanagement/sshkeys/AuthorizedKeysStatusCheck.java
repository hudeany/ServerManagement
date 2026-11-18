package de.soderer.servermanagement.sshkeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.soderer.servermanagement.AuthorizedKeysHelper;
import de.soderer.servermanagement.config.Admin;
import de.soderer.servermanagement.config.KnownPublicSshKey;
import de.soderer.servermanagement.config.Server;
import de.soderer.servermanagement.config.User;
import de.soderer.sshkeyformats.AuthorizedKey;

/**
 * Compares the configured keys of the managed SSH users of a server with their authorized_keys on the server.
 */
public class AuthorizedKeysStatusCheck {
	private final Server server;
	/** Public key text by key id */
	private final Map<String, String> knownKeysByKeyId = new HashMap<>();

	public AuthorizedKeysStatusCheck(final Server server, final List<KnownPublicSshKey> knownPublicSshKeys) {
		this.server = server;
		if (knownPublicSshKeys != null) {
			for (final KnownPublicSshKey knownPublicSshKey : knownPublicSshKeys) {
				knownKeysByKeyId.put(knownPublicSshKey.getKeyId(), knownPublicSshKey.getKey());
			}
		}
	}

	/**
	 * Checks each user separately, so a failing user does not abort the check of further users.
	 *
	 * @return the status in the order of the managed users
	 */
	public List<UserKeyStatus> check() {
		final List<UserKeyStatus> results = new ArrayList<>();
		if (server.getManagedUsers() != null) {
			for (final User managedUser : server.getManagedUsers()) {
				results.add(check(managedUser));
			}
		}
		return results;
	}

	public UserKeyStatus check(final User managedUser) {
		final String username = managedUser.getUsername();
		try {
			final Admin sshAdmin = server.getSshAdmin();
			if (sshAdmin == null) {
				throw new Exception("No SSH admin configured for server '" + server.getName() + "'");
			}
			final List<AuthorizedKey> authorizedKeys = AuthorizedKeysHelper.readAuthorizedKeys(server.getHost(), sshAdmin.getUsername(), sshAdmin.getPrivateKeyFile(), sshAdmin.getPasswordArray(), username);
			final Set<String> authorizedKeyStrings = new HashSet<>();
			for (final AuthorizedKey authorizedKey : authorizedKeys) {
				authorizedKeyStrings.add(authorizedKey.getKeyString());
			}

			// A configured key id without known key counts as missing key
			final List<String> configuredKeyIds = managedUser.getAuthorized_key_ids();
			final Set<String> expectedKeyStrings = new HashSet<>();
			int currentKeys = 0;
			for (final String keyId : configuredKeyIds) {
				final String expectedKey = knownKeysByKeyId.get(keyId);
				if (expectedKey != null) {
					expectedKeyStrings.add(expectedKey);
					if (authorizedKeyStrings.contains(expectedKey)) {
						currentKeys++;
					}
				}
			}

			int surplusKeys = 0;
			for (final AuthorizedKey authorizedKey : authorizedKeys) {
				if (!expectedKeyStrings.contains(authorizedKey.getKeyString())) {
					surplusKeys++;
				}
			}

			return new UserKeyStatus(username, configuredKeyIds.size(), currentKeys, surplusKeys);
		} catch (@SuppressWarnings("unused") final Exception e) {
			return UserKeyStatus.failed(username);
		}
	}
}
