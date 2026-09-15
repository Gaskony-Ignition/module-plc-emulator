package com.inductiveautomation.logixemulator.gateway.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Factory for creating appropriate PLC parsers based on file format.
 * Automatically detects format from file extension or content.
 */
public class ParserFactory {

    private static final Logger logger = LoggerFactory.getLogger(ParserFactory.class);

    /**
     * Registered parsers.
     *
     * <p>{@code L5XParser} is deliberately NOT registered (maintainer decision 27/07/2026 —
     * L5K is the only supported Rockwell format). Leaving it out means there is no route
     * from a filename to the L5X parser anywhere in the running module, so {@code .l5x}
     * cannot be reached even if a file slips past {@code FileValidator} or
     * {@code FilePreparation}. The class itself is retained for the test contracts
     * described in its javadoc, and those tests instantiate it directly.</p>
     */
    private static final List<PLCParser> AVAILABLE_PARSERS = List.of(
        new L5KParser(),      // L5K parser FIRST for .l5k files
        new JsonPLCParser(),
        new CsvParser()
    );

    /**
     * Get parser for a specific file.
     *
     * @param fileName Name of the file to parse
     * @return PLCParser that can handle this file, or null if no parser found
     */
    public static PLCParser getParser(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            logger.warn("Cannot determine parser - filename is null or empty");
            return null;
        }

        for (PLCParser parser : AVAILABLE_PARSERS) {
            if (parser.canHandle(fileName)) {
                logger.info("Selected parser '{}' for file: {}", parser.getParserType(), fileName);
                return parser;
            }
        }

        logger.warn("No parser found for file: {}", fileName);
        return null;
    }

    /**
     * Get parser by explicit type.
     *
     * @param parserType Parser type ("rockwell", "json", "csv")
     * @return PLCParser of the specified type, or null if not found
     */
    public static PLCParser getParserByType(String parserType) {
        if (parserType == null || parserType.isEmpty()) {
            return null;
        }

        String normalizedType = parserType.toLowerCase().trim();

        for (PLCParser parser : AVAILABLE_PARSERS) {
            if (parser.getParserType().equals(normalizedType)) {
                logger.info("Selected parser by type: {}", parserType);
                return parser;
            }
        }

        logger.warn("No parser found for type: {}", parserType);
        return null;
    }

    /**
     * Get list of all supported file extensions.
     *
     * <p>Mirrors {@code FileValidator.SUPPORTED_EXTENSIONS}, which is the enforcing
     * gate. {@code .l5x} is excluded (unsupported since 11.0.0) and {@code .txt} is
     * excluded because the validator has never accepted it, despite
     * {@code CsvParser.canHandle} matching it.</p>
     *
     * @return Array of supported extensions (e.g., [".l5k", ".json", ".csv"])
     */
    public static String[] getSupportedExtensions() {
        return new String[]{".l5k", ".json", ".csv"};
    }

    /**
     * Check if a file is supported by any parser.
     *
     * @param fileName Name of the file to check
     * @return true if at least one parser can handle this file
     */
    public static boolean isSupported(String fileName) {
        return getParser(fileName) != null;
    }

    /**
     * Get all available parsers.
     *
     * @return List of all registered parsers
     */
    public static List<PLCParser> getAllParsers() {
        return AVAILABLE_PARSERS;
    }
}
