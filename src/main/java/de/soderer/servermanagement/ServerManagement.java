package de.soderer.servermanagement;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import javax.swing.SwingUtilities;

import de.soderer.json.JsonNode;
import de.soderer.json.JsonObject;
import de.soderer.json.JsonReader;
import de.soderer.json.JsonSerializer;
import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.utilities.ConfigurationProperties;
import de.soderer.utilities.DateUtilities;
import de.soderer.utilities.IoUtilities;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.ParameterException;
import de.soderer.utilities.UpdateableConsoleApplication;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.Version;
import de.soderer.utilities.appupdate.ApplicationUpdateUtilities;
import de.soderer.utilities.swing.QuestionDialog;
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
			return APPLICATION_NAME + " (by Andreas Soderer, mail: " + APPLICATION_NAME.toLowerCase() + "@soderer.de)\n"
					+ "VERSION: " + VERSION.toString() + " (" + DateUtilities.formatDate(DateUtilities.YYYY_MM_DD_HHMMSS, VERSION_BUILDTIME) + ")" + "\n\n"
					+ new String(IoUtilities.toByteArray(helpInputStream), StandardCharsets.UTF_8);
		} catch (@SuppressWarnings("unused") final Exception e) {
			return "Help info is missing";
		}
	}

	public static void main(final String[] args) {
		_main(args);
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
			} else {
				for (int i = 0; i < arguments.length; i++) {
					if ("help".equalsIgnoreCase(arguments[i]) || "-help".equalsIgnoreCase(arguments[i]) || "--help".equalsIgnoreCase(arguments[i]) || "-h".equalsIgnoreCase(arguments[i]) || "--h".equalsIgnoreCase(arguments[i])
							|| "-?".equalsIgnoreCase(arguments[i]) || "--?".equalsIgnoreCase(arguments[i])) {
						System.out.println(getUsageMessage());
						return 1;
					} else if ("version".equalsIgnoreCase(arguments[i]) && arguments.length == 1) {
						System.out.println(VERSION);
						return 1;
					} else if ("update".equalsIgnoreCase(arguments[i]) && arguments.length == 1) {
						if (arguments.length > i + 2) {
							final ServerManagement serverManagement = new ServerManagement();
							ApplicationUpdateUtilities.executeUpdate(serverManagement, ServerManagement.VERSIONINFO_DOWNLOAD_URL, applicationConfiguration.getProxyConfiguration(), ServerManagement.APPLICATION_NAME, ServerManagement.VERSION, ServerManagement.TRUSTED_UPDATE_CA_CERTIFICATES, arguments[i + 1], arguments[i + 2].toCharArray(), null, null, false, false);
						} else if (arguments.length > i + 1) {
							final ServerManagement serverManagement = new ServerManagement();
							ApplicationUpdateUtilities.executeUpdate(serverManagement, ServerManagement.VERSIONINFO_DOWNLOAD_URL, applicationConfiguration.getProxyConfiguration(), ServerManagement.APPLICATION_NAME, ServerManagement.VERSION, ServerManagement.TRUSTED_UPDATE_CA_CERTIFICATES, arguments[i + 1], null, null, null, false, false);
						} else {
							final ServerManagement serverManagement = new ServerManagement();
							ApplicationUpdateUtilities.executeUpdate(serverManagement, ServerManagement.VERSIONINFO_DOWNLOAD_URL, applicationConfiguration.getProxyConfiguration(), ServerManagement.APPLICATION_NAME, ServerManagement.VERSION, ServerManagement.TRUSTED_UPDATE_CA_CERTIFICATES, null, null, null, null, false, false);
						}
						return 1;
					}

					if (new File(arguments[i]).exists()) {
						SERVERS_CONFIGURATION_FILE = new File(arguments[i]);
					}
				}
			}

			// open the preconfigured GUI
			de.soderer.utilities.swing.SwingUtilities.setSystemLookAndFeel();

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
				e.printStackTrace();
				new QuestionDialog(null, ServerManagement.APPLICATION_NAME, "Configuration file '" + ServerManagement.SERVERS_CONFIGURATION_FILE + "' is invalid: \n" + e.getMessage(), LangResources.get("ok")).open();
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
			System.err.println(e.getMessage());
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
