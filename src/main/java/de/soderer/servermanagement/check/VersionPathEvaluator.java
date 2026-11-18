package de.soderer.servermanagement.check;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import de.soderer.json.JsonNode;
import de.soderer.json.JsonReader;
import de.soderer.json.JsonValueBoolean;
import de.soderer.json.JsonValueInteger;
import de.soderer.json.JsonValueNumber;
import de.soderer.json.JsonValueString;
import de.soderer.json.path.JsonPath;
import de.soderer.servermanagement.http.HttpAccess;
import de.soderer.utilities.PropertiesReader;
import de.soderer.utilities.Utilities;

/**
 * Extracts a version from the data of a version location by the version path:
 * <ul>
 * <li>"json:&lt;JSON path&gt;", e.g. "json:$.versionData[?(@.name=='nexInsure')].version"</li>
 * <li>"xml:&lt;XPath&gt;"</li>
 * <li>"regex:&lt;regex&gt;" (complete match)</li>
 * <li>"regex(&lt;group&gt;):&lt;regex&gt;", e.g. "regex(1): version: (.*)"</li>
 * <li>"properties:&lt;property name&gt;"</li>
 * </ul>
 */
public final class VersionPathEvaluator {
	private VersionPathEvaluator() {
		// Utility class
	}

	public static boolean isJsonPath(final String path) {
		return path != null && path.toLowerCase().startsWith("json:");
	}

	/**
	 * @return the version text
	 * @throws Exception
	 *             if the path is unsupported or invalid, or nothing matches
	 */
	public static String evaluate(final String data, final String path) throws Exception {
		if (path == null) {
			throw new Exception("Missing version path");
		}
		final String lowerCasePath = path.toLowerCase();
		if (lowerCasePath.startsWith("json:")) {
			// Complete JSON path support (RFC 9535: filters, wildcards, slices, negative indexes, recursive descent,
			// functions) via JsonNode.getDataListByJsonPath on the complete data
			return readFirstValueByJsonPath(data, path.substring(5).trim());
		} else if (lowerCasePath.startsWith("xml:")) {
			final Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(data)));
			return (String) XPathFactory.newInstance().newXPath().evaluate(path.substring(4), document, XPathConstants.STRING);
		} else if (lowerCasePath.startsWith("regex:")) {
			final Matcher matcher = Pattern.compile(path.substring(6)).matcher(data);
			if (matcher.find()) {
				return matcher.group();
			} else {
				throw new Exception("Regex '" + path.substring(6) + "' found no match");
			}
		} else if (lowerCasePath.startsWith("regex(")) {
			// Example: "regex(1): version: (.*)"
			final Pattern regex = Pattern.compile(path.substring(path.indexOf(":") + 1));
			final int group = Integer.parseInt(path.substring(6, path.indexOf(")")));
			final Matcher matcher = regex.matcher(data);
			if (matcher.find()) {
				return matcher.group(group);
			} else {
				throw new Exception("Regex '" + regex.pattern() + "' found no match");
			}
		} else if (lowerCasePath.startsWith("properties:")) {
			final String propertyName = path.substring(11);
			try (PropertiesReader reader = new PropertiesReader(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)))) {
				final Map<String, String> propertiesMap = reader.read();
				String propertyValue = propertiesMap.get(propertyName);
				if (propertyValue == null) {
					throw new Exception("Property '" + propertyName + "' not found");
				}
				if (propertyValue.startsWith("\"") && propertyValue.endsWith("\"")) {
					propertyValue = propertyValue.substring(1, propertyValue.length() - 1);
					propertyValue = Utilities.unescapeJavaString(propertyValue);
				}
				return propertyValue;
			}
		} else {
			throw new Exception("Unsupported version path '" + path + "'");
		}
	}

	/**
	 * Reads the complete JSON data and returns the value of the first node matching the JSON path as text, e.g.
	 * "$.versionData[?(@.name=='nexInsure')].version" or "$.versionData[?(match(@.version, '25\\..*'))].version".
	 * Strings, numbers and booleans are accepted as version values.
	 *
	 * @throws Exception
	 *             if the data or path is invalid, nothing matches or the first match is no simple value
	 */
	public static String readFirstValueByJsonPath(final String data, final String jsonPathString) throws Exception {
		final JsonNode rootNode;
		try (final JsonReader jsonReader = new JsonReader(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)))) {
			// Reads the complete document, already marked as root node (required for paths starting with "$")
			rootNode = jsonReader.read();
		}

		final List<JsonNode> matches = rootNode.getDataListByJsonPath(new JsonPath(jsonPathString));
		if (matches.isEmpty()) {
			String hint = "";
			if (jsonPathString.matches(".*\\[\\?\\(.*\\)\\].*\\.[A-Za-z0-9_$-]+\\[\\d+\\]\\s*$")) {
				hint = " (Hint: an index after the field name, like '.version[0]', is applied to the field value, not to the filter result; remove it, the first match is used anyway)";
			}
			throw new Exception("JSON path '" + jsonPathString + "' found no value" + hint);
		}
		final JsonNode firstMatch = matches.get(0);
		if (firstMatch instanceof JsonValueString) {
			return ((JsonValueString) firstMatch).getValue();
		} else if (firstMatch instanceof JsonValueInteger) {
			return ((JsonValueInteger) firstMatch).getValue().toString();
		} else if (firstMatch instanceof JsonValueNumber) {
			return ((JsonValueNumber) firstMatch).getValue().toString();
		} else if (firstMatch instanceof JsonValueBoolean) {
			return ((JsonValueBoolean) firstMatch).getValue().toString();
		} else {
			throw new Exception("JSON path '" + jsonPathString + "' found no simple value, but " + firstMatch.getJsonDataType() + " "
					+ HttpAccess.shorten(firstMatch.toString(), 300) + (matches.size() > 1 ? " (" + matches.size() + " matches)" : ""));
		}
	}
}
