package com.inductiveautomation.plcsimulator.gateway.parser;

/**
 * Exception thrown when parsing PLC files fails.
 * Provides detailed context about where and why parsing failed.
 */
public class ParserException extends Exception {

    private final String parserType;
    private final String fileName;
    private final int lineNumber;
    private final String lineContent;

    /**
     * Create a parser exception with full context.
     *
     * @param parserType  Type of parser (e.g., "L5K", "Siemens", "JSON")
     * @param fileName    Name of the file being parsed
     * @param lineNumber  Line number where error occurred (-1 if unknown)
     * @param lineContent Content of the problematic line (null if not applicable)
     * @param message     Description of what went wrong
     * @param cause       Underlying exception (optional)
     */
    public ParserException(String parserType, String fileName, int lineNumber,
                           String lineContent, String message, Throwable cause) {
        super(formatMessage(parserType, fileName, lineNumber, lineContent, message), cause);
        this.parserType = parserType;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.lineContent = lineContent;
    }

    public ParserException(String parserType, String fileName, String message, Throwable cause) {
        this(parserType, fileName, -1, null, message, cause);
    }

    public ParserException(String parserType, String fileName, String message) {
        this(parserType, fileName, -1, null, message, null);
    }

    private static String formatMessage(String parserType, String fileName, int lineNumber,
                                         String lineContent, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(parserType).append(" Parser] ");

        if (fileName != null && !fileName.isEmpty()) {
            sb.append("File: ").append(fileName);
        }

        if (lineNumber > 0) {
            sb.append(" (line ").append(lineNumber).append(")");
        }

        sb.append(" - ").append(message);

        if (lineContent != null && !lineContent.isEmpty()) {
            // Truncate long lines for readability
            String truncated = lineContent.length() > 100
                ? lineContent.substring(0, 100) + "..."
                : lineContent;
            sb.append("\n  Content: ").append(truncated.trim());
        }

        return sb.toString();
    }

    /**
     * Get a user-friendly error message suitable for display in device status.
     */
    public String getUserFriendlyMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("Failed to parse ").append(parserType).append(" file");

        if (lineNumber > 0) {
            sb.append(" at line ").append(lineNumber);
        }

        // Extract the core message without technical details
        String coreMessage = getMessage();
        int dashIndex = coreMessage.indexOf(" - ");
        if (dashIndex > 0 && dashIndex + 3 < coreMessage.length()) {
            String afterDash = coreMessage.substring(dashIndex + 3);
            // Remove line content portion if present
            int newlineIndex = afterDash.indexOf('\n');
            if (newlineIndex > 0) {
                afterDash = afterDash.substring(0, newlineIndex);
            }
            sb.append(": ").append(afterDash);
        }

        return sb.toString();
    }

    public String getParserType() {
        return parserType;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getLineContent() {
        return lineContent;
    }
}
