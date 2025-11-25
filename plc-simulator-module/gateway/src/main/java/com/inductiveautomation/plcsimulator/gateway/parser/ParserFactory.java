package com.inductiveautomation.plcsimulator.gateway.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for creating appropriate PLC parsers based on file format.
 * Automatically detects format from file extension or content.
 */
public class ParserFactory {

    private static final Logger logger = LoggerFactory.getLogger(ParserFactory.class);
    private static final List<PLCParser> availableParsers = new ArrayList<>();

    static {
        // Register all available parsers
        availableParsers.add(new L5KParser());  // Add L5K parser FIRST for .l5k files
        availableParsers.add(new L5XParser());  // L5X parser for .l5x XML files
        availableParsers.add(new SiemensParser());  // Siemens TIA Portal (S7-1200/1500)
        availableParsers.add(new SchneiderParser());  // Schneider Electric Unity Pro/EcoStruxure
        availableParsers.add(new BeckhoffParser());  // Beckhoff TwinCAT 2/3
        availableParsers.add(new ABBParser());  // ABB Automation Builder / Control Builder Plus
        availableParsers.add(new MitsubishiParser());  // Mitsubishi GX Works 2/3
        availableParsers.add(new OmronParser());  // Omron CX-Programmer / Sysmac Studio
        availableParsers.add(new JsonPLCParser());
        availableParsers.add(new CsvParser());

        logger.info("Registered {} PLC parsers: L5K, L5X, Siemens, Schneider, Beckhoff, ABB, Mitsubishi, Omron, JSON, CSV",
                    availableParsers.size());
    }

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

        for (PLCParser parser : availableParsers) {
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

        for (PLCParser parser : availableParsers) {
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
     * @return Array of supported extensions (e.g., [".l5x", ".json", ".csv"])
     */
    public static String[] getSupportedExtensions() {
        return new String[]{".l5x", ".l5k", ".json", ".csv", ".txt"};
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
        return new ArrayList<>(availableParsers);
    }
}
