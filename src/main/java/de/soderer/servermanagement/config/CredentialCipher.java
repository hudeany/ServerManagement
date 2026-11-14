package de.soderer.servermanagement.config;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption of the passwords in the servers configuration file.
 *
 * The passwords are encrypted with AES-256-GCM. The key (master key) is kept in a separate file next to the servers
 * configuration file ("&lt;configuration file name&gt;.masterkey"). It is generated randomly on first use and is only
 * readable by the current user (POSIX permissions rw------- on Linux, an ACL with only the owner on Windows).
 *
 * Encrypted passwords are stored as "enc:v1:" + Base64(nonce | ciphertext | tag). Values without this prefix are
 * plain text passwords of older configurations and are encrypted by {@link #encryptPlainPasswords(ServerManagementConfiguration)}.
 */
public final class CredentialCipher {
	/** Prefix of encrypted passwords in the configuration file */
	public static final String ENCRYPTED_PREFIX = "enc:v1:";

	/** Extension appended to the name of the servers configuration file for the master key file */
	public static final String MASTER_KEY_FILE_EXTENSION = ".masterkey";

	private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int KEY_LENGTH_BYTES = 32;
	private static final int NONCE_LENGTH_BYTES = 12;
	private static final int TAG_LENGTH_BITS = 128;

	/** Additional authenticated data, binds the ciphertexts to this application and format version */
	private static final byte[] ASSOCIATED_DATA = "de.soderer.servermanagement:v1".getBytes(StandardCharsets.UTF_8);

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private static CredentialCipher instance;

	private final File masterKeyFile;
	private SecretKeySpec masterKey;

	/**
	 * Initializes the cipher for the given servers configuration file. Must be called before any password is read or
	 * written. The master key file itself is only read or created when it is needed.
	 */
	public static synchronized void init(final File serversConfigurationFile) {
		instance = new CredentialCipher(getMasterKeyFile(serversConfigurationFile));
	}

	public static synchronized CredentialCipher getInstance() {
		if (instance == null) {
			throw new IllegalStateException("CredentialCipher was not initialized");
		}
		return instance;
	}

	/**
	 * The master key file lies next to the servers configuration file, e.g. ".ServerManagement.servers.config.masterkey".
	 */
	public static File getMasterKeyFile(final File serversConfigurationFile) {
		final File absoluteFile = serversConfigurationFile.getAbsoluteFile();
		return new File(absoluteFile.getParentFile(), absoluteFile.getName() + MASTER_KEY_FILE_EXTENSION);
	}

	public static boolean isEncrypted(final String value) {
		return value != null && value.startsWith(ENCRYPTED_PREFIX);
	}

	/**
	 * Encrypts all plain text passwords of the configuration (passwords without the prefix "enc:v1:").
	 *
	 * @return true, if at least one password was encrypted, so the configuration file must be written again
	 */
	public static boolean encryptPlainPasswords(final ServerManagementConfiguration serverManagementConfiguration) {
		boolean changed = false;
		if (serverManagementConfiguration != null && serverManagementConfiguration.getServers() != null) {
			for (final Server server : serverManagementConfiguration.getServers()) {
				if (server.getSshAdmin() != null) {
					changed |= server.getSshAdmin().encryptPlainPassword();
				}
				if (server.getLogins() != null) {
					for (final Login login : server.getLogins()) {
						changed |= login.encryptPlainPassword();
					}
				}
			}
		}
		return changed;
	}

	private CredentialCipher(final File masterKeyFile) {
		this.masterKeyFile = masterKeyFile;
	}

	public File getMasterKeyFile() {
		return masterKeyFile;
	}

	/**
	 * Encrypts a password. The master key file is created, if it does not exist yet.
	 *
	 * @return the encrypted password with prefix "enc:v1:", or null for null
	 */
	public String encrypt(final char[] password) {
		if (password == null) {
			return null;
		}

		byte[] plainBytes = null;
		try {
			final byte[] nonce = new byte[NONCE_LENGTH_BYTES];
			SECURE_RANDOM.nextBytes(nonce);

			final Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, getMasterKey(true), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
			cipher.updateAAD(ASSOCIATED_DATA);
			plainBytes = toUtf8Bytes(password);
			final byte[] cipherText = cipher.doFinal(plainBytes);

			final byte[] data = ByteBuffer.allocate(nonce.length + cipherText.length).put(nonce).put(cipherText).array();
			return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(data);
		} catch (final IllegalStateException e) {
			throw e;
		} catch (final Exception e) {
			throw new IllegalStateException("Cannot encrypt password: " + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
		} finally {
			if (plainBytes != null) {
				Arrays.fill(plainBytes, (byte) 0);
			}
		}
	}

	/**
	 * Decrypts a password. Values without prefix "enc:v1:" are plain text passwords of older configurations and are
	 * returned as they are.
	 *
	 * @return the plain password, or null for null
	 */
	public char[] decrypt(final String value) {
		if (value == null) {
			return null;
		} else if (!isEncrypted(value)) {
			return value.toCharArray();
		}

		byte[] plainBytes = null;
		try {
			final byte[] data = Base64.getDecoder().decode(value.substring(ENCRYPTED_PREFIX.length()));
			if (data.length < NONCE_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
				throw new IllegalArgumentException("Encrypted password is too short");
			}

			final Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, getMasterKey(false), new GCMParameterSpec(TAG_LENGTH_BITS, data, 0, NONCE_LENGTH_BYTES));
			cipher.updateAAD(ASSOCIATED_DATA);
			plainBytes = cipher.doFinal(data, NONCE_LENGTH_BYTES, data.length - NONCE_LENGTH_BYTES);
			return fromUtf8Bytes(plainBytes);
		} catch (final IllegalStateException e) {
			throw e;
		} catch (final javax.crypto.AEADBadTagException e) {
			throw new IllegalStateException("Cannot decrypt password: The master key file '" + masterKeyFile.getAbsolutePath() + "' does not match the configuration file", e);
		} catch (final Exception e) {
			throw new IllegalStateException("Cannot decrypt password: " + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
		} finally {
			if (plainBytes != null) {
				Arrays.fill(plainBytes, (byte) 0);
			}
		}
	}

	/**
	 * Returns the master key, reads it from the master key file or creates a new random master key file.
	 *
	 * @param createIfMissing
	 *            true for encryption; for decryption a missing master key file is an error, because a new key could
	 *            never decrypt the existing passwords
	 */
	private synchronized SecretKeySpec getMasterKey(final boolean createIfMissing) throws Exception {
		if (masterKey == null) {
			if (masterKeyFile.exists()) {
				masterKey = readMasterKeyFile();
			} else if (createIfMissing) {
				masterKey = createMasterKeyFile();
			} else {
				throw new IllegalStateException("Master key file '" + masterKeyFile.getAbsolutePath() + "' is missing, the encrypted passwords cannot be decrypted");
			}
		}
		return masterKey;
	}

	private SecretKeySpec readMasterKeyFile() throws Exception {
		final byte[] fileContent = Files.readAllBytes(masterKeyFile.toPath());
		byte[] keyBytes = null;
		try {
			keyBytes = Base64.getDecoder().decode(new String(fileContent, StandardCharsets.US_ASCII).trim());
			if (keyBytes.length != KEY_LENGTH_BYTES) {
				throw new IllegalStateException("Master key file '" + masterKeyFile.getAbsolutePath() + "' is invalid (expected " + KEY_LENGTH_BYTES + " bytes, found " + keyBytes.length + ")");
			}
			return new SecretKeySpec(keyBytes, "AES");
		} catch (final IllegalArgumentException e) {
			throw new IllegalStateException("Master key file '" + masterKeyFile.getAbsolutePath() + "' is invalid: " + e.getMessage(), e);
		} finally {
			Arrays.fill(fileContent, (byte) 0);
			if (keyBytes != null) {
				Arrays.fill(keyBytes, (byte) 0);
			}
		}
	}

	private SecretKeySpec createMasterKeyFile() throws Exception {
		final Path keyPath = masterKeyFile.getAbsoluteFile().toPath();
		if (keyPath.getParent() != null) {
			Files.createDirectories(keyPath.getParent());
		}

		try {
			createFileForOwnerOnly(keyPath);
		} catch (@SuppressWarnings("unused") final FileAlreadyExistsException e) {
			// Created in the meantime by another instance of the application
			return readMasterKeyFile();
		}

		final byte[] keyBytes = new byte[KEY_LENGTH_BYTES];
		byte[] fileContent = null;
		try {
			SECURE_RANDOM.nextBytes(keyBytes);
			fileContent = Base64.getEncoder().encode(keyBytes);
			try (OutputStream outputStream = Files.newOutputStream(keyPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				outputStream.write(fileContent);
			}
			return new SecretKeySpec(keyBytes, "AES");
		} catch (final Exception e) {
			// An empty or partially written key file must not remain, it would make all later encryptions fail
			Files.deleteIfExists(keyPath);
			throw new Exception("Cannot create master key file '" + keyPath + "': " + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
		} finally {
			Arrays.fill(keyBytes, (byte) 0);
			if (fileContent != null) {
				Arrays.fill(fileContent, (byte) 0);
			}
		}
	}

	/**
	 * Creates a new empty file, which only the current user may read and write. The permissions are set before any
	 * content is written.
	 *
	 * @throws FileAlreadyExistsException
	 *             if the file already exists
	 */
	private static void createFileForOwnerOnly(final Path path) throws IOException {
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			// Linux, macOS: rw------- already at creation, so there is no moment with wider permissions
			Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		} else {
			// Windows: replace the inherited ACL by a single entry for the owner
			Files.createFile(path);
			final AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
			if (aclView != null) {
				try {
					final UserPrincipal owner = Files.getOwner(path);
					final AclEntry ownerEntry = AclEntry.newBuilder()
							.setType(AclEntryType.ALLOW)
							.setPrincipal(owner)
							.setPermissions(EnumSet.allOf(AclEntryPermission.class))
							.build();
					aclView.setAcl(List.of(ownerEntry));
				} catch (final IOException e) {
					Files.deleteIfExists(path);
					throw e;
				}
			}
		}
	}

	private static byte[] toUtf8Bytes(final char[] chars) {
		final ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
		final byte[] bytes = new byte[byteBuffer.remaining()];
		byteBuffer.get(bytes);
		// Wipe the internal buffer of the encoder
		if (byteBuffer.hasArray()) {
			Arrays.fill(byteBuffer.array(), (byte) 0);
		}
		return bytes;
	}

	private static char[] fromUtf8Bytes(final byte[] bytes) {
		final CharBuffer charBuffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
		final char[] chars = new char[charBuffer.remaining()];
		charBuffer.get(chars);
		// Wipe the internal buffer of the decoder
		if (charBuffer.hasArray()) {
			Arrays.fill(charBuffer.array(), '\0');
		}
		return chars;
	}
}
