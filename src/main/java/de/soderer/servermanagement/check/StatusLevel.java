package de.soderer.servermanagement.check;

/**
 * Result level of a check. The status of a server item is the worst level of all its checks (OK &lt; WARNING &lt;
 * ERROR); the names are the status texts of the server item.
 */
public enum StatusLevel {
	OK,
	WARNING,
	ERROR;

	/**
	 * The worse of this and the other level.
	 */
	public StatusLevel max(final StatusLevel other) {
		return other != null && other.ordinal() > ordinal() ? other : this;
	}
}
