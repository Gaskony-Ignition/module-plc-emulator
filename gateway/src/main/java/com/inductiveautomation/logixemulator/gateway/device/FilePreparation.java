package com.inductiveautomation.logixemulator.gateway.device;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.inductiveautomation.logixemulator.gateway.FileVersionManager;
import com.inductiveautomation.logixemulator.gateway.parser.PLCParser;
import com.inductiveautomation.logixemulator.gateway.parser.ParserFactory;
import com.inductiveautomation.logixemulator.gateway.web.DeviceFileManager;
import com.inductiveautomation.logixemulator.gateway.web.PathSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Owns file-system preparation and parser dispatch for a Logix Emulator
 * device.
 *
 * <p>Extracted from the 1040-line {@code LogixEmulatorDevice} as part of the
 * Sprint 3 P6 refactor. Responsibilities:</p>
 * <ul>
 *   <li>Sanitise user-supplied filenames via {@link PathSecurity}.</li>
 *   <li>Locate the most recently uploaded PLC file on disk for the device,
 *       deterministically by last-modified time, pruning stale ones beyond
 *       the version-manager retention limit (defect B5, fixed 10/07/2026 —
 *       previously an unordered {@code listFiles()} scan could pick an
 *       arbitrary/stale file on Gateway restart).</li>
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
     * Legacy device/filename separator (pre-FIX-3, 11/07/2026). Device names may themselves
     * contain {@code "_"} (see {@code DeviceConfigService}'s {@code ^[a-zA-Z0-9_-]+$} name
     * regex), so {@code startsWith(deviceName + "_")} is ambiguous: device {@code "plc"} also
     * matches device {@code "plc_test"}'s files. Kept only as a non-authoritative fallback so
     * files uploaded before this fix aren't orphaned on Gateway restart — see the safety note on
     * {@link #findExistingFileForDevice}.
     */
    private static final String LEGACY_DEVICE_FILE_SEPARATOR = "_";

    /**
     * Find the most recently uploaded PLC file in the storage directory for
     * THIS device. Only considers files with the device-specific prefix to
     * prevent cross-device file sharing.
     *
     * <p>Defect B5 (10/07/2026): this previously returned the first match
     * from {@link File#listFiles()}, whose iteration order is unspecified —
     * on Gateway restart the device could silently load a stale or arbitrary
     * file instead of the one most recently uploaded via REST
     * ({@code plc-dod/item7-versioning-FAIL.txt}). Candidates are now sorted
     * by last-modified time (newest first) and any beyond the version
     * manager's retention limit ({@link FileVersionManager#getMaxVersions()})
     * are pruned, so a device that has been re-uploaded to under many
     * different filenames does not accumulate files here unboundedly.</p>
     *
     * <p><b>Defect FIX-3 (11/07/2026, data-safety):</b> matching is now split into two tiers
     * mirroring {@link com.inductiveautomation.logixemulator.gateway.web.DeviceFileManager}'s
     * naming scheme precisely:
     * <ul>
     *   <li><b>Safe</b> — {@code deviceName + DeviceFileManager.DEVICE_FILE_SEPARATOR} ({@code
     *       "."}, illegal in any device name, so this prefix can never match a different device's
     *       file no matter what other devices are registered). This is the only tier that
     *       participates in retention pruning/deletion.</li>
     *   <li><b>Legacy</b> — {@code deviceName + "_"}, kept only so files uploaded before this fix
     *       still get picked up on Gateway restart. Because {@code "_"} IS a legal device-name
     *       character this tier remains ambiguous between prefix-overlapping device names (e.g.
     *       {@code "plc"} vs {@code "plc_test"}) — so legacy matches are used for pickup only and
     *       are <b>never deleted</b> by the pruning below, eliminating the irreversible half of
     *       the original defect ({@code plc-dod2/item5-write.txt} lineage) even for old files.</li>
     * </ul>
     *
     * @param storageDir directory to search
     * @return the most recently modified matching file, or {@code null} if none
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
        String safePrefix = deviceName + DeviceFileManager.DEVICE_FILE_SEPARATOR;
        String legacyPrefix = deviceName + LEGACY_DEVICE_FILE_SEPARATOR;

        List<File> safeCandidates = new ArrayList<>();
        List<File> legacyCandidates = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(".") || name.endsWith(".bak") || name.contains("~")) {
                continue;
            }
            if (!matchesExtension(name)) {
                continue;
            }
            if (name.startsWith(safePrefix)) {
                safeCandidates.add(file);
            } else if (name.startsWith(legacyPrefix)) {
                legacyCandidates.add(file);
            }
        }

        // Retention pruning only ever touches the unambiguous (safe-separator) tier - a legacy
        // match can never be deleted here, even if it turns out to belong to a different device.
        pruneStaleCandidates(safeCandidates);

        List<File> allCandidates = new ArrayList<>(safeCandidates);
        allCandidates.addAll(legacyCandidates);
        if (allCandidates.isEmpty()) {
            return null;
        }

        allCandidates.sort(Comparator.comparingLong(File::lastModified).reversed());

        File mostRecent = allCandidates.get(0);
        logger.info("Found most recently uploaded device-specific file: {}", mostRecent.getName());
        return mostRecent;
    }

    /** @return {@code true} if {@code name} ends with one of {@link #PLC_EXTENSIONS}. */
    private static boolean matchesExtension(String name) {
        for (String ext : PLC_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prunes (deletes) candidates beyond the version manager's retention limit, newest first.
     * Mutates {@code candidates} by sorting it in place (newest-first) so the caller's later
     * "most recent" selection sees a consistent order; callers must pass only file lists that are
     * safe to delete from (see the FIX-3 safety note on {@link #findExistingFileForDevice}).
     */
    private void pruneStaleCandidates(List<File> candidates) {
        if (candidates.isEmpty()) {
            return;
        }

        candidates.sort(Comparator.comparingLong(File::lastModified).reversed());

        int maxRetained = FileVersionManager.getMaxVersions();
        if (candidates.size() > maxRetained) {
            for (File stale : candidates.subList(maxRetained, candidates.size())) {
                if (stale.delete()) {
                    logger.info("Pruned stale uploaded file beyond retention ({}): {}", maxRetained, stale.getName());
                } else {
                    logger.warn("Failed to prune stale uploaded file: {}", stale.getAbsolutePath());
                }
            }
        }
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
                // DoD FIX-1 (11/07/2026): a genuine parse failure (garbage/malformed content, an
                // XXE rejection, etc.) on a file that DOES exist must propagate as null so the
                // caller (LogixEmulatorDevice.onStartup / HotReloadCoordinator.handleFileChange)
                // sets an Error-prefixed status and the REST upload gate (DeviceController's B4
                // check) reports 422 success:false. Previously this substituted a demo tag
                // structure, so the device silently reached "Running" on a corrupt upload and the
                // endpoint reported HTTP 200 success:true (plc-dod2/item6-verify.txt).
                logger.error("Parser returned null for existing file {} - file may be invalid or "
                    + "corrupted, rejecting rather than substituting demo tags", filePath);
                return null;
            }

        } catch (Exception e) {
            // Same honesty requirement as the null-result branch above: an exception while
            // parsing an existing file is a genuine failure, not a "no file yet" state.
            logger.error("Error in built-in parser for file {}", filePath, e);
            return null;
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
