package de.soderer.servermanagement;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.swing.Timer;

import de.soderer.servermanagement.config.Login;

/**
 * Opens the URL of a {@link Login} in the system default browser and provides the password via the clipboard for a
 * limited time.
 *
 * <p>
 * The login fields of a page shown in an external browser cannot be filled in from outside, and passing credentials
 * inside the URL ("https://user:password@host/") would expose the password in the process list, in the browser
 * history and in proxy logs. Therefore the password is copied to the clipboard and removed again after
 * {@link #CLIPBOARD_CLEAR_DELAY_MILLIS}, unless the clipboard content was changed in the meantime.
 * </p>
 *
 * <p>
 * All clipboard methods must be called on the Event Dispatch Thread.
 * </p>
 */
public final class LoginBrowserLauncher {
	/** Time after which the copied password is removed from the clipboard. */
	public static final int CLIPBOARD_CLEAR_DELAY_MILLIS = 30_000;

	/** Pending clipboard cleanup (EDT only). */
	private static Timer clipboardClearTimer;

	/** Password currently placed in the clipboard by this class (EDT only). */
	private static String clipboardPassword;

	private LoginBrowserLauncher() {
		// Utility class
	}

	/**
	 * Checks whether the login has a URL that may be opened in a browser.
	 */
	public static boolean isLaunchable(final Login login) {
		return login != null && toBrowserUri(login.getUrl()) != null;
	}

	/**
	 * Converts the configured login URL into a URI for the browser. Only absolute "http" and "https" URLs with a host
	 * are accepted, so that a manipulated configuration cannot be used to open local files or other protocol
	 * handlers.
	 *
	 * @return the URI or null, if the URL is missing or not acceptable
	 */
	public static URI toBrowserUri(final String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		try {
			final URI uri = new URI(url.trim());
			final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!"http".equals(scheme) && !"https".equals(scheme)) {
				return null;
			} else if (uri.getHost() == null || uri.getHost().isEmpty()) {
				return null;
			} else if (uri.getRawUserInfo() != null) {
				// Credentials embedded in the URL are not passed to the browser
				return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
			} else {
				return uri;
			}
		} catch (@SuppressWarnings("unused") final URISyntaxException e) {
			return null;
		}
	}

	/**
	 * Opens the URI in the system default browser. Falls back to the platform command ("rundll32", "open" or
	 * "xdg-open"), if {@link Desktop#browse(URI)} is not supported or fails (e.g. on some Linux desktops).
	 *
	 * <p>
	 * This call may block for a while on some platforms, so it should not be executed on the Event Dispatch Thread.
	 * </p>
	 */
	public static void browse(final URI uri) throws IOException {
		if (uri == null) {
			throw new IllegalArgumentException("URI for browser is missing");
		}

		if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
			try {
				Desktop.getDesktop().browse(uri);
				return;
			} catch (@SuppressWarnings("unused") final IOException | UnsupportedOperationException e) {
				// Fall back to the platform command
			}
		}

		final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		final String uriString = uri.toASCIIString();
		final List<String> command;
		if (osName.contains("win")) {
			command = List.of("rundll32", "url.dll,FileProtocolHandler", uriString);
		} else if (osName.contains("mac")) {
			command = List.of("open", uriString);
		} else {
			command = List.of("xdg-open", uriString);
		}
		new ProcessBuilder(command)
		.redirectErrorStream(true)
		.redirectOutput(ProcessBuilder.Redirect.DISCARD)
		.start();
	}

	/**
	 * Copies the decrypted password of the login to the clipboard and schedules its removal.
	 *
	 * @return true, if a password was copied; false, if the login has no password
	 */
	public static boolean copyPasswordToClipboard(final Login login) {
		final char[] password = login == null || !login.hasPassword() ? null : login.getPasswordArray();
		if (password == null || password.length == 0) {
			return false;
		}
		try {
			// The clipboard API only accepts Strings, so a String copy of the password cannot be avoided here
			final String passwordString = new String(password);
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(passwordString), null);
			clipboardPassword = passwordString;

			if (clipboardClearTimer != null) {
				clipboardClearTimer.stop();
			}
			clipboardClearTimer = new Timer(CLIPBOARD_CLEAR_DELAY_MILLIS, event -> clearPasswordFromClipboard());
			clipboardClearTimer.setRepeats(false);
			clipboardClearTimer.start();
			return true;
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	/**
	 * Removes a password copied by this class from the clipboard, if the clipboard still contains it. Should also be
	 * called when the application is closed.
	 */
	public static void clearPasswordFromClipboard() {
		if (clipboardClearTimer != null) {
			clipboardClearTimer.stop();
			clipboardClearTimer = null;
		}
		final String expectedContent = clipboardPassword;
		clipboardPassword = null;
		if (expectedContent == null) {
			return;
		}

		try {
			final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor) && expectedContent.equals(clipboard.getData(DataFlavor.stringFlavor))) {
				clipboard.setContents(new StringSelection(""), null);
			}
		} catch (@SuppressWarnings("unused") final IllegalStateException | UnsupportedFlavorException | IOException e) {
			// Clipboard is busy or its content changed: leave it untouched
		}
	}
}
