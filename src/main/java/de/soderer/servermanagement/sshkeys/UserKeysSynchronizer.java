package de.soderer.servermanagement.sshkeys;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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
import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.servermanagement.config.User;
import de.soderer.sshkeyformats.AuthorizedKey;
import de.soderer.sshkeyformats.SshKey;
import de.soderer.sshkeyformats.SshKeyReader;
import de.soderer.utilities.StringUtilities;

/**
 * Synchronizes the keys of a managed SSH user between the known keys of the configuration, the configured keys of the
 * user and the authorized_keys of the user on the server. Keys are identified by their hash.
 *
 * Usage: {@link #load()}, show the key maps to the user, {@link #applySelection(Set, Set, Set)} with the selection of
 * the user, then {@link #isUploadNeeded(List)} and {@link #upload(List)}.
 */
public class UserKeysSynchronizer {
	private final Server server;
	private final ServerManagementConfiguration configuration;
	private final User user;

	private String adminKeyHash;
	private List<AuthorizedKey> existingAuthorizedKeys;

	/**
	 * Result of the selection of the user.
	 *
	 * @param newAuthorizedKeys
	 *            the new authorized_keys of the user
	 * @param unknownKeyHashes
	 *            selected keys, which are neither on the server nor known, so they cannot be stored
	 */
	public record SelectionResult(List<AuthorizedKey> newAuthorizedKeys, List<String> unknownKeyHashes) {
	}

	public UserKeysSynchronizer(final Server server, final ServerManagementConfiguration configuration, final User user) {
		this.server = server;
		this.configuration = configuration;
		this.user = user;
	}

	/**
	 * Reads the authorized_keys of the user from the server.
	 *
	 * @throws Exception
	 *             if no SSH admin is configured or the keys cannot be read
	 */
	public void load() throws Exception {
		final Admin sshAdmin = getSshAdminChecked();
		adminKeyHash = readAdminKeyHash(sshAdmin.getPrivateKeyFile(), sshAdmin.getPasswordArray());
		existingAuthorizedKeys = AuthorizedKeysHelper.readAuthorizedKeys(server.getHost(), sshAdmin.getUsername(), sshAdmin.getPrivateKeyFile(), sshAdmin.getPasswordArray(), user.getUsername());
	}

	/**
	 * Hash of the key of the SSH admin, which must not be removed by mistake; null if the admin uses a password.
	 */
	public String getAdminKeyHash() {
		return adminKeyHash;
	}

	/**
	 * Keys in the authorized_keys of the user: comment by hash.
	 */
	public Map<String, String> getExistingKeys() throws Exception {
		checkLoaded();
		final Map<String, String> existingKeys = new HashMap<>();
		for (final AuthorizedKey key : existingAuthorizedKeys) {
			existingKeys.put(key.getHash(), key.getComment());
		}
		return existingKeys;
	}

	/**
	 * Configured keys of the user: key id by hash. Configured key ids without known key are ignored.
	 */
	public Map<String, String> getConfiguredKeys() throws Exception {
		final Map<String, String> configuredKeys = new HashMap<>();
		for (final String keyId : user.getAuthorized_key_ids()) {
			final KnownPublicSshKey key = findKnownKeyByKeyId(keyId);
			if (key != null) {
				configuredKeys.put(key.getHash(), keyId);
			}
		}
		return configuredKeys;
	}

	/**
	 * Known keys of the configuration: key id by hash.
	 */
	public Map<String, String> getKnownKeys() throws Exception {
		final Map<String, String> knownKeys = new HashMap<>();
		for (final KnownPublicSshKey key : configuration.getKnownPublicSshKeys()) {
			knownKeys.put(key.getHash(), key.getKeyId());
		}
		return knownKeys;
	}

	/**
	 * Names of the known keys by key id.
	 */
	public Map<String, String> getKeyNamesByKeyId() {
		final Map<String, String> keyNamesByKeyId = new HashMap<>();
		for (final KnownPublicSshKey knownPublicSshKey : configuration.getKnownPublicSshKeys()) {
			keyNamesByKeyId.put(knownPublicSshKey.getKeyId(), knownPublicSshKey.getName());
		}
		return keyNamesByKeyId;
	}

	/**
	 * Applies the selection of the user to the configuration (known keys, configured keys of the user) and determines the
	 * new authorized_keys of the user. The configuration is changed, the server not yet.
	 *
	 * @param knownKeyHashes
	 *            keys, which stay or become known; keys from the server are added to the known keys
	 * @param configuredKeyHashes
	 *            keys configured for the user (only known keys can be configured)
	 * @param existingKeyHashes
	 *            keys for the authorized_keys of the user
	 */
	public SelectionResult applySelection(final Set<String> knownKeyHashes, final Set<String> configuredKeyHashes, final Set<String> existingKeyHashes) throws Exception {
		checkLoaded();
		applyKnownKeys(knownKeyHashes);
		applyConfiguredKeys(configuredKeyHashes);
		return createAuthorizedKeys(existingKeyHashes);
	}

	/**
	 * True if the new authorized_keys differ from the authorized_keys on the server.
	 */
	public boolean isUploadNeeded(final List<AuthorizedKey> newAuthorizedKeys) throws Exception {
		checkLoaded();
		if (existingAuthorizedKeys.size() != newAuthorizedKeys.size()) {
			return true;
		}
		return !getHashes(existingAuthorizedKeys).equals(getHashes(newAuthorizedKeys));
	}

	/**
	 * Stores the new authorized_keys of the user on the server.
	 */
	public void upload(final List<AuthorizedKey> newAuthorizedKeys) throws Exception {
		final Admin sshAdmin = getSshAdminChecked();
		AuthorizedKeysHelper.storeAuthorizedKeys(server.getHost(), sshAdmin.getUsername(), sshAdmin.getPrivateKeyFile(), sshAdmin.getPasswordArray(), user.getUsername(), newAuthorizedKeys);
		existingAuthorizedKeys = new ArrayList<>(newAuthorizedKeys);
	}

	/**
	 * Adds selected keys of the server to the known keys and removes not selected keys from the known keys.
	 */
	private void applyKnownKeys(final Set<String> knownKeyHashes) throws Exception {
		for (final String keyHash : knownKeyHashes) {
			boolean found = false;
			for (final KnownPublicSshKey key : configuration.getKnownPublicSshKeys()) {
				if (keyHash.equals(key.getHash())) {
					found = true;
					break;
				}
			}
			if (!found) {
				for (final AuthorizedKey authorizedKey : existingAuthorizedKeys) {
					if (keyHash.equals(authorizedKey.getHash())) {
						final KnownPublicSshKey newKnownPublicKey = new KnownPublicSshKey();
						newKnownPublicKey.setKey(authorizedKey.getKeyString());
						newKnownPublicKey.setKeyId(authorizedKey.getComment());
						newKnownPublicKey.setKeyType(authorizedKey.getKeyType());
						configuration.getKnownPublicSshKeys().add(newKnownPublicKey);
						break;
					}
				}
			}
		}

		final List<KnownPublicSshKey> remainingKnownKeys = new ArrayList<>();
		for (final KnownPublicSshKey key : configuration.getKnownPublicSshKeys()) {
			if (knownKeyHashes.contains(getHashOrNull(key))) {
				remainingKnownKeys.add(key);
			}
		}
		configuration.setKnownPublicSshKeys(remainingKnownKeys);
	}

	/**
	 * Adds the selected known keys to the configured keys of the user and removes the not selected ones.
	 */
	private void applyConfiguredKeys(final Set<String> configuredKeyHashes) throws Exception {
		final Map<String, String> hashByKeyId = new HashMap<>();
		final Map<String, String> keyIdByHash = new HashMap<>();
		for (final KnownPublicSshKey key : configuration.getKnownPublicSshKeys()) {
			hashByKeyId.put(key.getKeyId(), key.getHash());
			keyIdByHash.put(key.getHash(), key.getKeyId());
		}

		for (final String keyHash : configuredKeyHashes) {
			final String keyId = keyIdByHash.get(keyHash);
			// Only known keys can be configured, otherwise a null key id was stored
			if (keyId != null && !user.getAuthorized_key_ids().contains(keyId)) {
				user.getAuthorized_key_ids().add(keyId);
			}
		}

		final List<String> remainingKeyIds = new ArrayList<>();
		for (final String keyId : user.getAuthorized_key_ids()) {
			if (configuredKeyHashes.contains(hashByKeyId.get(keyId))) {
				remainingKeyIds.add(keyId);
			}
		}
		user.setAuthorized_key_ids(remainingKeyIds);
	}

	/**
	 * New authorized_keys: selected keys from the server stay unchanged, other selected keys are taken from the known
	 * keys.
	 */
	private SelectionResult createAuthorizedKeys(final Set<String> existingKeyHashes) throws Exception {
		final List<AuthorizedKey> newAuthorizedKeys = new ArrayList<>();
		final List<String> unknownKeyHashes = new ArrayList<>();
		for (final String hash : existingKeyHashes) {
			final AuthorizedKey existingKey = findExistingKeyByHash(hash);
			if (existingKey != null) {
				newAuthorizedKeys.add(existingKey);
			} else {
				final KnownPublicSshKey knownKey = findKnownKeyByHash(hash);
				if (knownKey != null) {
					final AuthorizedKey newKey = new AuthorizedKey(knownKey.getKeyType(), knownKey.getKey());
					newKey.setComment(knownKey.getKeyId());
					newAuthorizedKeys.add(newKey);
				} else {
					unknownKeyHashes.add(hash);
				}
			}
		}
		return new SelectionResult(newAuthorizedKeys, unknownKeyHashes);
	}

	private AuthorizedKey findExistingKeyByHash(final String hash) {
		for (final AuthorizedKey key : existingAuthorizedKeys) {
			try {
				if (key.getHash().equals(hash)) {
					return key;
				}
			} catch (final Exception e) {
				e.printStackTrace();
			}
		}
		return null;
	}

	private KnownPublicSshKey findKnownKeyByHash(final String hash) {
		for (final KnownPublicSshKey key : configuration.getKnownPublicSshKeys()) {
			if (hash.equals(getHashOrNull(key))) {
				return key;
			}
		}
		return null;
	}

	private KnownPublicSshKey findKnownKeyByKeyId(final String keyId) {
		if (keyId != null) {
			for (final KnownPublicSshKey knownPublicSshKey : configuration.getKnownPublicSshKeys()) {
				if (keyId.equals(knownPublicSshKey.getKeyId())) {
					return knownPublicSshKey;
				}
			}
		}
		return null;
	}

	/**
	 * Hash of a known key; null if the key is invalid (such a key is never selected).
	 */
	private static String getHashOrNull(final KnownPublicSshKey key) {
		try {
			return key.getHash();
		} catch (final Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	private static Set<String> getHashes(final List<AuthorizedKey> keys) throws Exception {
		final Set<String> hashes = new HashSet<>();
		for (final AuthorizedKey key : keys) {
			hashes.add(key.getHash());
		}
		return hashes;
	}

	private void checkLoaded() throws Exception {
		if (existingAuthorizedKeys == null) {
			throw new Exception("Authorized keys of user '" + user.getUsername() + "' not loaded");
		}
	}

	private Admin getSshAdminChecked() throws Exception {
		if (server.getSshAdmin() == null || StringUtilities.isBlank(server.getSshAdmin().getUsername())) {
			throw new Exception("No SSH admin configured for server '" + server.getName() + "'");
		}
		return server.getSshAdmin();
	}

	private static String readAdminKeyHash(final File sshAccessPrivateKeyFile, final char[] sshAccessPassword) throws Exception {
		if (sshAccessPrivateKeyFile != null) {
			try (InputStream inputStream = new FileInputStream(sshAccessPrivateKeyFile)) {
				final SshKey sshKey;
				if (StringUtilities.isNotBlank(sshAccessPassword)) {
					sshKey = SshKeyReader.readKey(inputStream, sshAccessPassword);
				} else {
					sshKey = SshKeyReader.readKey(inputStream, null);
				}
				return sshKey.getMd5Fingerprint().replace(":", "");
			}
		} else {
			return null;
		}
	}
}
