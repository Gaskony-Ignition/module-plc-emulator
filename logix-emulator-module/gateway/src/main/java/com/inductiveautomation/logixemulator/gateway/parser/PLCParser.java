package com.inductiveautomation.logixemulator.gateway.parser;

import com.google.gson.JsonObject;

import java.io.File;

/**
 * Base interface for PLC file parsers.
 * Each parser implementation handles a specific PLC vendor format.
 */
public interface PLCParser {

    /**
     * Parse a PLC file and return structured tag data.
     *
     * @param filePath Path to the PLC file
     * @return JsonObject containing parsed tag structure, or null if parsing failed
     */
    JsonObject parse(String filePath);

    /**
     * Parse PLC file content directly (without file system).
     *
     * @param fileContent String content of the PLC file
     * @param fileName Original filename (for format detection if needed)
     * @return JsonObject containing parsed tag structure, or null if parsing failed
     */
    JsonObject parseContent(String fileContent, String fileName);

    /**
     * Check if this parser can handle the given file.
     *
     * @param fileName Name of the file (used for extension checking)
     * @return true if this parser supports the file format
     */
    boolean canHandle(String fileName);

    /**
     * Get the parser name/type.
     *
     * @return Parser identifier (e.g., "rockwell", "json", "csv")
     */
    String getParserType();
}
