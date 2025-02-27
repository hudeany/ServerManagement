package de.soderer.servermanagement.config;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import de.soderer.sshkeyformats.SshKey;
import de.soderer.sshkeyformats.SshKeyReader;
import de.soderer.sshkeyformats.data.Algorithm;

public class KnownPublicSshKey {
	public String name;
	public String keyId;
	public String keyType;
	public String key;
	
	private transient String hash = null;
	
	public String getName() {
		return name;
	}
	
	public KnownPublicSshKey setName(String name) {
		this.name = name;
		return this;
	}
	
	public String getKeyId() {
		return keyId;
	}
	
	public KnownPublicSshKey setKeyId(String keyId) {
		this.keyId = keyId;
		return this;
	}
	
	public Algorithm getKeyType() throws Exception {
		return Algorithm.getForSshAlgorithmId(keyType);
	}
	
	public KnownPublicSshKey setKeyType(Algorithm keyType) {
		this.keyType = keyType.getSshAlgorithmId();
		return this;
	}
	
	public KnownPublicSshKey setKeyTypeString(String keyType) throws Exception {
		this.keyType = Algorithm.getForSshAlgorithmId(keyType).getSshAlgorithmId();
		return this;
	}
	
	public String getKey() {
		return key;
	}
	
	public KnownPublicSshKey setKey(String key) {
		this.key = key;
		return this;
	}

	public String getHash() throws Exception {
		if (hash == null) {
			try (InputStream inputStream = new ByteArrayInputStream(key.getBytes(StandardCharsets.UTF_8))) {
				List<SshKey> sshKeys = SshKeyReader.readAllPublicKeys(inputStream);
				hash = sshKeys.get(0).getMd5Fingerprint().replace(":", "");
			}
		}
		return hash;
	}
}
