package de.soderer.servermanagement;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import javax.swing.SwingUtilities;

import de.soderer.servermanagement.config.ServerManagementConfiguration;
import de.soderer.utilities.ConfigurationProperties;
import de.soderer.utilities.DateUtilities;
import de.soderer.utilities.IoUtilities;
import de.soderer.utilities.ParameterException;
import de.soderer.utilities.UpdateableConsoleApplication;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.Version;
import de.soderer.utilities.appupdate.ApplicationUpdateUtilities;
import de.soderer.utilities.http.ProxyConfiguration;
import de.soderer.utilities.http.ProxyConfiguration.ProxyConfigurationType;
import de.soderer.utilities.json.JsonNode;
import de.soderer.utilities.json.JsonObject;
import de.soderer.utilities.json.JsonReader;
import de.soderer.utilities.json.JsonSerializer;
import de.soderer.utilities.swing.ApplicationConfigurationDialog;
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
	public static final File CONFIGURATION_FILE = new File(System.getProperty("user.home") + File.separator + "." + APPLICATION_NAME + ".config");
	
	public static final File SERVERS_CONFIGURATION_FILE = new File(System.getProperty("user.home") + File.separator + "." + APPLICATION_NAME + ".servers.config");

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
	
	public static void main(String[] args) {
		_main(args);
	}

	/** Trusted CA certificate for updates **/
	public static String TRUSTED_UPDATE_CA_CERTIFICATES = null;
	protected static int _main(final String[] args) {
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

		final ProxyConfigurationType proxyConfigurationType = ProxyConfigurationType.getFromString(applicationConfiguration.get(ApplicationConfigurationDialog.CONFIG_PROXY_CONFIGURATION_TYPE));
		final String proxyUrl = applicationConfiguration.get(ApplicationConfigurationDialog.CONFIG_PROXY_URL);
		final ProxyConfiguration proxyConfiguration = new ProxyConfiguration(proxyConfigurationType, proxyUrl);

		try {
			String[] arguments = args;

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
							ApplicationUpdateUtilities.executeUpdate(serverManagement, ServerManagement.VERSIONINFO_DOWNLOAD_URL, proxyConfiguration, ServerManagement.APPLICATION_NAME, ServerManagement.VERSION, ServerManagement.TRUSTED_UPDATE_CA_CERTIFICATES, arguments[i + 1], arguments[i + 2].toCharArray(), null, false);
						} else if (arguments.length > i + 1) {
							final ServerManagement serverManagement = new ServerManagement();
							ApplicationUpdateUtilities.executeUpdate(serverManagement, ServerManagement.VERSIONINFO_DOWNLOAD_URL, proxyConfiguration, ServerManagement.APPLICATION_NAME, ServerManagement.VERSION, ServerManagement.TRUSTED_UPDATE_CA_CERTIFICATES, arguments[i + 1], null, null, false);
						} else {
							final ServerManagement serverManagement = new ServerManagement();
							ApplicationUpdateUtilities.executeUpdate(serverManagement, ServerManagement.VERSIONINFO_DOWNLOAD_URL, proxyConfiguration, ServerManagement.APPLICATION_NAME, ServerManagement.VERSION, ServerManagement.TRUSTED_UPDATE_CA_CERTIFICATES, null, null, null, false);
						}
						return 1;
					}
				}
			}

			ServerManagementConfiguration serverManagementConfiguration = new ServerManagementConfiguration();
			
			if (ServerManagement.SERVERS_CONFIGURATION_FILE.exists()) {
				JsonNode rootNode = null;
				try (JsonReader jsonReader = new JsonReader(new FileInputStream(ServerManagement.SERVERS_CONFIGURATION_FILE))) {
					rootNode = jsonReader.read();
				}
				
				serverManagementConfiguration = (ServerManagementConfiguration) JsonSerializer.deserialize(ServerManagementConfiguration.class, (JsonObject) rootNode.getValue());
			}

			// open the preconfigured GUI
			de.soderer.utilities.swing.SwingUtilities.setSystemLookAndFeel();
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
	public void receiveDoneSignal(LocalDateTime arg0, LocalDateTime arg1, long arg2, String arg3, String arg4) {
		// do nothing

	}

	@Override
	public void receiveProgressSignal(LocalDateTime arg0, long arg1, long arg2, String arg3) {
		// do nothing

	}

	@Override
	public void receiveUnlimitedProgressSignal() {
		// do nothing

	}

	@Override
	public void receiveItemDoneSignal(LocalDateTime arg0, LocalDateTime arg1, long arg2, String arg3, String arg4) {
		// do nothing

	}

	@Override
	public void receiveItemProgressSignal(LocalDateTime arg0, long arg1, long arg2, String arg3) {
		// do nothing
	}

	@Override
	public void receiveItemStartSignal(String arg0, String arg1) {
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
