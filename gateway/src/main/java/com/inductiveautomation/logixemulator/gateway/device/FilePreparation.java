package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.FileVersionManager;
import com.inductiveautomation.logixemulator.gateway.parser.PLCParser;
import com.inductiveautomation.logixemulator.gateway.parser.ParserFactory;
import com.inductiveautomation.logixemulator.gateway.web.PathSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;

/**
 * Owns file-system preparation and parser dispatch for a Logix Emulator
 * device.
 *
 * <p>Extracted from the 1040-line {@code LogixEmulatorDevice} as part of the
 * Sprint 3 P6 refactor. Responsibilities:</p>
 * <ul>
 *   <li>Sanitise user-supplied filenames via {@link PathSecurity}.</li>
 *   <li>Locate an existing PLC file on disk for the device (the heuristic
 *       the review flagged as questionable; preserved as-is — drop is a
 *       behavioural decision out of scope).</li>
 *   <li>Save uploaded file content to the data dir and version older copies.</li>
 *   <li>Pick the right parser (L5K vs L5X for {@code rockwell} type, falling
 *       back to extension detection).</li>
 *   <li>Provide a small demo {@link JsonObject} when no real file is
 *       available — so the device still appears in the OPC-UA browser.</li>
 * </ul>
 *
 * <p>Public API mirrors the original private methods on the device exactly so
 * behaviour is preserved.</p>
 */
public final class FilePreparation {

    private static final Logger logger = LoggerFactory.getLogger(FilePreparation.class);
    private static final Gson GSON = new Gson();

    /** Common Rockwell / generic PLC file extensions. */
    private static final String[] PLC_EXTENSIONS =
        { ".L5K", ".l5k", ".L5X", ".l5x", ".json", ".JSON", ".csv", ".CSV" };

    private final DeviceContext context;
    private final LogixEmulatorConfig config;

    /** Initialised on first call to {@link #prepareFile()}. */
    private volatile FileVersionManager versionManager;

    public FilePreparation(DeviceContext context, LogixEmulatorConfig config) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.context = context;
        this.config = config;
    }

    /**
     * @return the version manager initialised by {@link #prepareFile()}, or
     *     {@code null} if {@link #prepareFile()} has not yet run.
     */
    public FileVersionManager getVersionManager() {
        return versionManager;
    }

    /**
     * Sanitises a filename to prevent path traversal attacks. Falls back to
     * a device-specific default if the input is null/empty or rejected by
     * {@link PathSecurity#sanitizeFileName(String)}.
     */
    public String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "uploaded-" + context.getName() + ".L5K";
        }
        try {
            return PathSecurity.sanitizeFileName(fileName);
        } catch (SecurityException | IllegalArgumentException e) {
            logger.warn("Filename rejected by PathSecurity: {} — using default", fileName);
            return "uploaded-" + context.getName() + ".L5K";
        }
    }

    /**
     * Find an existing PLC file in the storage directory previously uploaded
     * for THIS device. Only returns files with the device-specific prefix
     * to prevent cross-device file sharing.
     *
     * @param storageDir directory to search
     * @return the matched file, or {@code null} if none
     */
    public File findExistingFileForDevice(File storageDir) {
        if (storageDir == null || !storageDir.exists()) {
            return null;
        }

        File[] files = storageDir.listFiles();
        if (files == null) {
            return null;
        }

        String deviceName = context.getName();
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(".") || name.endsWith(".bak") || name.contains("~")) {
                continue;
            }
            if (name.startsWith(deviceName + "_")) {
                for (String ext : PLC_EXTENSIONS) {
                    if (name.endsWith(ext)) {
                        logger.info("Found device-specific file: {}", file.getName());
                        return file;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Prepares the PLC file for parsing. If file content was uploaded, saves
     * it to the Gateway filesystem (versioning any prior copy first).
     * Otherwise resolves the existing configured filename or falls back to
     * {@link #findExistingFileForDevice(File)}.
     *
     * @return absolute path to the prepared file, or {@code null} if no file
     *     is available yet (device should remain in waiting state)
     */
    public String prepareFile() {
        try {
            String fileContent = config.parser().fileContent();
            String fileName = config.parser().fileName();

            File dataDir = context.getGatewayContext().getSystemManager().getDataDir();
            File storageDir = new File(dataDir, "logix-emulator");

            if (!storageDir.exists()) {
                if (!storageDir.mkdirs()) {
                    logger.warn("Failed to create PLC file storage directory: {}", storageDir.getAbsolutePath());
                } else {
                    logger.info("Created PLC file storage directory: {}", storageDir.getAbsolutePath());
                }
            }

            versionManager = new FileVersionManager(storageDir, context.getName());

            if (fileContent != null && !fileContent.trim().isEmpty()) {
                String sanitizedFileName = sanitizeFileName(fileName);
                File targetFile = new File(storageDir, sanitizedFileName);

                if (targetFile.exists()) {
                    versionManager.saveVersion(targetFile, sanitizedFileName);
                }

                Files.writeString(targetFile.toPath(), fileContent);
                logger.info("Saved uploaded file to: {}", targetFile.getAbsolutePath());
                return targetFile.getAbsolutePath();
            }

            if (fileName != null && !fileName.trim().isEmpty()) {
                String sanitizedFileName = sanitizeFileName(fileName);
                File targetFile = new File(storageDir, sanitizedFileName);
                if (targetFile.exists()) {
                    logger.info("Using existing file: {}", targetFile.getAbsolutePath());
                    return targetFile.getAbsolutePath();
                } else {
                    logger.error("File not found: {}", targetFile.getAbsolutePath());
                    return null;
                }
            }

            File deviceFile = findExistingFileForDevice(storageDir);
            if (deviceFile != null && deviceFile.exists()) {
                logger.info("Found existing uploaded file for device: {}", deviceFile.getAbsolutePath());
                logger.info("To make this permanent, re-save device configuration with this filename");
                return deviceFile.getAbsolutePath();
            }

            logger.info("No file content or file name provided - device will wait for upload");
            return null;

        } catch (Exception e) {
            logger.error("Error preparing file", e);
            return null;
        }
    }

    /**
     * Parses the PLC file using the configured parser. Returns {@code null}
     * on an outright failure; callers may also receive a {@link #createDemoStructure()}
     * payload from the inner built-in parser when the file is absent or the
     * specific parser refuses it.
     */
    public JsonObject parseFile(String filePath) {
        try {
            LogixEmulatorConfig.ParserType parserType = config.parser().parserType();
            logger.info("Parsing file: {} with parser: {}", filePath, parserType.getDisplayName());
            return parseFileBuiltIn(filePath, parserType);
        } catch (Exception e) {
            logger.error("Error parsing file", e);
            return null;
        }
    }

    /**
     * Built-in Java parser using {@link ParserFactory}. Selects the
     * appropriate parser based on file type and parses the PLC file. Falls
     * back to {@link #createDemoStructure()} when the file is missing or the
     * parser returns null.
     */
    JsonObject parseFileBuiltIn(String filePath, LogixEmulatorConfig.ParserType parserType) {
        logger.info("Using built-in Java parser for: {}", filePath);

        File file = new File(filePath);
        if (!file.exists()) {
            logger.error("File does not exist: {}", filePath);
            return createDemoStructure();
        }

        try {
            PLCParser parser;

            if ("rockwell".equals(parserType.getKey())) {
                String fileName = file.getName().toLowerCase();
                if (fileName.endsWith(".l5k")) {
                    parser = ParserFactory.getParserByType("l5k");
                    logger.info("Selected L5K parser for Rockwell .l5k file");
                } else if (fileName.endsWith(".l5x")) {
                    parser = ParserFactory.getParserByType("l5x");
                    logger.info("Selected L5X parser for Rockwell .l5x file");
                } else {
                    parser = ParserFactory.getParser(file.getName());
                }
            } else {
                parser = ParserFactory.getParserByType(parserType.getKey());
            }

            if (parser == null) {
                logger.warn("No parser available for type: {}, trying file extension detection", parserType);
                parser = ParserFactory.getParser(file.getName());
            }

            if (parser == null) {
                logger.error("No parser found for file: {}", file.getName());
                return createDemoStructure();
            }

            JsonObject result = parser.parse(filePath);
            if (result != null) {
                logger.info("Successfully parsed file using {} parser", parser.getParserType());
                return result;
            } else {
                logger.error("Parser returned null - file may be invalid or corrupted");
                return createDemoStructure();
            }

        } catch (Exception e) {
            logger.error("Error in built-in parser", e);
            return createDemoStructure();
        }
    }

    /**
     * Creates a demo tag structure for testing — used when no real PLC file
     * is available. Three tags so the device still shows up in the OPC-UA
     * browser with something selectable.
     */
    public JsonObject createDemoStructure() {
        String json = """
            {
                "global_tags": [
                    {
                        "name": "DemoTag1",
                        "dataType": "DINT",
                        "value": 0,
                        "description": "Demo tag — no PLC file loaded"
                    },
                    {
                        "name": "DemoTag2",
                        "dataType": "REAL",
                        "value": 0.0,
                        "description": "Demo tag — no PLC file loaded"
                    },
                    {
                        "name": "DemoStatus",
                        "dataType": "STRING",
                        "value": "Parser service unavailable - showing demo tags",
                        "description": "Status indicator"
                    }
                ]
            }
            """;
        return GSON.fromJson(json, JsonObject.class);
    }
}
