package de.soderer.servermanagement;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.swing.SwingUtilities;

import de.soderer.applicationbase.ApplicationUpdateUtilities;
import de.soderer.applicationbase.UpdateableConsoleApplication;
import de.soderer.json.JsonNode;
import de.soderer.json.JsonObject;
import de.soderer.json.JsonReader;
import de.soderer.json.JsonSerializer;
import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.swing.QuestionDialog;
import de.soderer.swing.SwingColor;
import de.soderer.utilities.ConfigurationProperties;
import de.soderer.utilities.DateUtilities;
import de.soderer.utilities.IoUtilities;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.ParameterException;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.Version;
import de.soderer.utilities.worker.WorkerParentDual;

public class ServerManagement extends UpdateableConsoleApplication implements WorkerParentDual {
	/** The Constant APPLICATION_NAME. */
	public static final String APPLICATION_NAME = "ServerManagement";
	public static final String APPLICATION_STARTUPCLASS_NAME = "de-soderer-ServerManagement";

	/**
	 * The Constant VERSION_RESOURCE_FILE, which contains version number and
	 * versioninfo download url.
	 */
	public static final String VERSION_RESOURCE_FILE = "/version.txt";

	public static final String HELP_RESOURCE_FILE = "/help.txt";

	/** The Constant CONFIGURATION_FILE. */
	public static final File CONFIGURATION_FILE = new File(System.getProperty("user.home") + File.separator + "." + APPLICATION_NAME + File.separator + "." + APPLICATION_NAME + ".config");

	public static File SERVERS_CONFIGURATION_FILE = new File(System.getProperty("user.home") + File.separator + "." + APPLICATION_NAME + File.separator + "." + APPLICATION_NAME + ".servers.config");

	/** The version is filled in at application start from the version.txt file. */
	public static Version VERSION = null;

	/**
	 * The version build time is filled in at application start from the version.txt
	 * file
	 */
	public static LocalDateTime VERSION_BUILDTIME = null;

	/**
	 * The versioninfo download url is filled in at application start from the
	 * version.txt file.
	 */
	public static String VERSIONINFO_DOWNLOAD_URL = null;

	/** The usage message. **/
	private static String getUsageMessage() {
		try (InputStream helpInputStream = ServerManagement.class.getResourceAsStream(HELP_RESOURCE_FILE)) {
			return APPLICATION_NAME + " (by Andreas Soderer, mail: " + APPLICATION_NAME.toLowerCase(Locale.ROOT) + "@soderer.de)\n"
					+ "VERSION: " + VERSION.toString() + getBuildTimeText() + "\n\n"
					+ new String(IoUtilities.toByteArray(helpInputStream), StandardCharsets.UTF_8);
		} catch (@SuppressWarnings("unused") final Exception e) {
			return "Help info is missing";
		}
	}

	/**
	 * Returns the build time for the version output, e.g. " (2026-10-09 21:13:22)".
	 * The build time is optional in version.txt, so it must not break the help output (NullPointerException before).
	 */
	public static String getBuildTimeText() {
		return VERSION_BUILDTIME == null ? "" : " (" + DateUtilities.formatDate(DateUtilities.YYYY_MM_DD_HHMMSS, VERSION_BUILDTIME) + ")";
	}

	/** Lower case keywords for the help text, only recognized as single argument */
	private static final Set<String> HELP_KEYWORDS = Set.of("help", "-help", "--help", "-h", "--h", "-?", "--?");

	public static void main(final String[] args) {
		// The return code was ignored, so the exit code was always 0 (also for errors).
		// A negative return code means that the GUI was started and keeps running.
		final int returnCode = _main(args);
		if (returnCode >= 0) {
			System.exit(returnCode);
		}
	}

	/** Trusted CA certificate for updates **/
	public static String TRUSTED_UPDATE_CA_CERTIFICATES = null;
	protected static int _main(final String[] args) {
		ApplicationUpdateUtilities.removeUpdateLeftovers();

		try (InputStream resourceStream = ServerManagement.class.getResourceAsStream(VERSION_RESOURCE_FILE)) {
			// Try to fill the version and versioninfo download url
			final List<String> versionInfoLines = Utilities.readLines(resourceStream, StandardCharsets.UTF_8);
			VERSION = new Version(versionInfoLines.get(0));
			if (versionInfoLines.size() >= 2) {
				VERSION_BUILDTIME = DateUtilities.parseLocalDateTime(DateUtilities.YYYY_MM_DD_HHMMSS, versionInfoLines.get(1));
			}
			if (versionInfoLines.size() >= 3) {
				VERSIONINFO_DOWNLOAD_URL = versionInfoLines.get(2);
			}
			if (versionInfoLines.size() >= 4) {
				TRUSTED_UPDATE_CA_CERTIFICATES = versionInfoLines.get(3);
			}
		} catch (@SuppressWarnings("unused") final Exception e) {
			// Without the version.txt file we may not go on
			System.err.println("Invalid version.txt");
			return 1;
		}

		ConfigurationProperties applicationConfiguration;
		try {
			applicationConfiguration = new ConfigurationProperties(ServerManagement.APPLICATION_NAME, true);
			ServerManagementGui.setupDefaultConfig(applicationConfiguration);
		} catch (@SuppressWarnings("unused") final Exception e) {
			System.err.println("Invalid application configuration");
			return 1;
		}

		try {
			final String[] arguments = args;

			if (arguments.length == 0) {
				// If started without any parameter we check for headless mode and show the console menu or the GUI
				if (GraphicsEnvironment.isHeadless()) {
					throw new ServerManagementException("Headless mode not supported");
				}
			} else if (arguments.length == 1 && HELP_KEYWORDS.contains(arguments[0].toLowerCase(Locale.ROOT))) {
				// Only as single argument (before, a help keyword was recognized at any position)
				System.out.println(getUsageMessage());
				return 1;
			} else if (arguments.length == 1 && "version".equalsIgnoreCase(arguments[0])) {
				System.out.println(VERSION);
				return 1;
			} else if ("update".equalsIgnoreCase(arguments[0]) && arguments.length <= 3) {
				// Only as first argument with optional username and password. Before, "update" was only accepted as
				// single argument, so the username and password branches were unreachable and "update user password"
				// silently opened the GUI.
				final ServerManagement serverManagement = new ServerManagement();
				final String username = arguments.length > 1 ? arguments[1] : null;
				final char[] updatePassword = arguments.length > 2 ? arguments[2].toCharArray() : null;
				ApplicationUpdateUtilities.executeUpdate(serverManagement, ServerManagement.VERSIONINFO_DOWNLOAD_URL, applicationConfiguration.getProxyConfiguration(), ServerManagement.APPLICATION_NAME, ServerManagement.VERSION, ServerManagement.TRUSTED_UPDATE_CA_CERTIFICATES, username, updatePassword, null, false, false);
				return 1;
			} else if (arguments.length == 1) {
				// The only other parameter is the servers configuration file. Before, a not existing file (e.g. a typo)
				// was silently ignored and the default configuration file was opened instead.
				final File serversConfigurationFile = new File(Utilities.replaceUsersHome(arguments[0]));
				if (!serversConfigurationFile.exists()) {
					throw new ParameterException(arguments[0], "Servers configuration file does not exist: " + arguments[0]);
				} else if (serversConfigurationFile.isDirectory()) {
					throw new ParameterException(arguments[0], "Servers configuration file is a directory: " + arguments[0]);
				}
				SERVERS_CONFIGURATION_FILE = serversConfigurationFile;
			} else {
				// Before, any further parameters were silently ignored
				throw new ParameterException(Utilities.join(arguments, " "), "Invalid parameters");
			}

			// open the preconfigured GUI
			de.soderer.swing.SwingUtilities.setSystemLookAndFeel();

			ServerManagementConfiguration serverManagementConfiguration = new ServerManagementConfiguration();

			try {
				if (ServerManagement.SERVERS_CONFIGURATION_FILE.exists()) {
					JsonNode rootNode = null;
					try (JsonReader jsonReader = new JsonReader(new FileInputStream(ServerManagement.SERVERS_CONFIGURATION_FILE))) {
						rootNode = jsonReader.read();
					}

					serverManagementConfiguration = (ServerManagementConfiguration) JsonSerializer.deserialize(ServerManagementConfiguration.class, (JsonObject) rootNode);
				}
			} catch (final Exception e) {
				// Before, the GUI was opened with an empty configuration, so saving overwrote the existing (only
				// slightly broken) configuration file with an empty one
				e.printStackTrace();
				new QuestionDialog(null, ServerManagement.APPLICATION_NAME, "Configuration file '" + ServerManagement.SERVERS_CONFIGURATION_FILE + "' is invalid: \n" + (e.getMessage() != null ? e.getMessage() : e.toString()), LangResources.get("ok")).setBackgroundColor(SwingColor.LightRed).open();
				return 1;
			}
			try {
				final ServerManagementGui serverManagementGui = new ServerManagementGui(serverManagementConfiguration);
				SwingUtilities.invokeLater(new Runnable() {
					@Override
					public void run() {
						serverManagementGui.setVisible(true);
					}
				});
				return -1;
			} catch (final Exception e) {
				e.printStackTrace();
				return 1;
			}
		} catch (final ParameterException e) {
			System.err.println(e.getMessage());
			System.err.println();
			System.err.println(getUsageMessage());
			return 1;
		} catch (final Exception e) {
			System.err.println(e.getMessage() != null ? e.getMessage() : e.toString());
			return 1;
		}
	}

	public ServerManagement() throws Exception {
		super(APPLICATION_NAME, VERSION);
	}

	@Override
	public void receiveDoneSignal(final LocalDateTime arg0, final LocalDateTime arg1, final long arg2, final String arg3, final String arg4) {
		// do nothing

	}

	@Override
	public void receiveProgressSignal(final LocalDateTime arg0, final long arg1, final long arg2, final String arg3) {
		// do nothing

	}

	@Override
	public void receiveUnlimitedProgressSignal() {
		// do nothing

	}

	@Override
	public void receiveItemDoneSignal(final LocalDateTime arg0, final LocalDateTime arg1, final long arg2, final String arg3, final String arg4) {
		// do nothing

	}

	@Override
	public void receiveItemProgressSignal(final LocalDateTime arg0, final long arg1, final long arg2, final String arg3) {
		// do nothing
	}

	@Override
	public void receiveItemStartSignal(final String arg0, final String arg1) {
		// do nothing

	}

	@Override
	public void receiveUnlimitedSubProgressSignal() {
		// do nothing
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see de.soderer.utilities.WorkerParentSimple#cancel()
	 */
	@Override
	public boolean cancel() {
		System.out.println("Canceled");
		return true;
	}

	@Override
	public void changeTitle(final String text) {
		// Do nothing
	}
}
