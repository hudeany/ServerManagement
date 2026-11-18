package de.soderer.servermanagement.sshkeys;

import de.soderer.servermanagement.check.StatusLevel;

/**
 * Status of the authorized keys of a managed SSH user.
 *
 * @param expectedKeys
 *            number of configured keys, -1 if the keys could not be read
 * @param currentKeys
 *            number of configured keys found in the authorized_keys of the user
 * @param surplusKeys
 *            number of keys in the authorized_keys of the user, which are not configured
 */
public record UserKeyStatus(String username, int expectedKeys, int currentKeys, int surplusKeys) {
	/**
	 * Status of a user, whose keys could not be read.
	 */
	public static UserKeyStatus failed(final String username) {
		return new UserKeyStatus(username, -1, 0, 0);
	}

	/**
	 * ERROR if configured keys are missing, WARNING for surplus keys.
	 */
	public StatusLevel getLevel() {
		if (currentKeys != expectedKeys) {
			return StatusLevel.ERROR;
		} else if (surplusKeys > 0) {
			return StatusLevel.WARNING;
		} else {
			return StatusLevel.OK;
		}
	}
}
