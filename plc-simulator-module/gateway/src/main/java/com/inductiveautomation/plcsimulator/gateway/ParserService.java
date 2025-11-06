package com.inductiveautomation.plcsimulator.gateway;

import org.slf4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * Manages the Python parser service subprocess.
 * Extracts the embedded Python executable, starts it as a subprocess,
 * and provides methods to call its REST API.
 */
public class ParserService {

    private static final int DEFAULT_PORT = 5000;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int STARTUP_WAIT_MS = 3000;

    private final Logger logger;
    private Process parserProcess;
    private Path executablePath;
    private final int port;
    private final String host;

    public ParserService(Logger logger) {
        this(logger, DEFAULT_HOST, DEFAULT_PORT);
    }

    public ParserService(Logger logger, String host, int port) {
        this.logger = logger;
        this.host = host;
        this.port = port;
    }

    /**
     * Start the parser service.
     * Extracts the embedded executable and starts it as a subprocess.
     *
     * @throws IOException if extraction or process start fails
     */
    public void start() throws IOException {
        logger.info("Starting PLC Parser Service on {}:{}", host, port);

        // Extract executable from JAR
        extractExecutable();

        // Start the process
        ProcessBuilder pb = new ProcessBuilder(
            executablePath.toString(),
            "--host", host,
            "--port", String.valueOf(port)
        );
        pb.redirectErrorStream(true);

        parserProcess = pb.start();

        // Start thread to log output
        startOutputLogger();

        // Wait for service to start
        try {
            Thread.sleep(STARTUP_WAIT_MS);
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for parser service to start", e);
        }

        // Verify service is running
        if (!isRunning()) {
            throw new IOException("Parser service failed to start");
        }

        logger.info("Parser service started successfully");
    }

    /**
     * Stop the parser service.
     */
    public void stop() {
        logger.info("Stopping PLC Parser Service");

        if (parserProcess != null && parserProcess.isAlive()) {
            parserProcess.destroy();

            try {
                boolean exited = parserProcess.waitFor(5, TimeUnit.SECONDS);
                if (!exited) {
                    logger.warn("Parser service did not stop gracefully, forcing...");
                    parserProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for parser service to stop", e);
                parserProcess.destroyForcibly();
            }
        }

        // Clean up temp executable
        if (executablePath != null) {
            try {
                Files.deleteIfExists(executablePath);
                logger.debug("Temp executable deleted");
            } catch (IOException e) {
                logger.warn("Failed to delete temp executable", e);
            }
        }

        logger.info("Parser service stopped");
    }

    /**
     * Check if the parser service is running.
     *
     * @return true if running and responding to health checks
     */
    public boolean isRunning() {
        if (parserProcess == null || !parserProcess.isAlive()) {
            return false;
        }

        try {
            URL url = new URL(String.format("http://%s:%d/health", host, port));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);

            int responseCode = conn.getResponseCode();
            conn.disconnect();

            return responseCode == 200;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parse an L5K file.
     *
     * @param l5kContent The L5K file content as a string
     * @return JSON response as a string
     * @throws IOException if parsing fails
     */
    public String parseL5K(String l5kContent) throws IOException {
        String endpoint = String.format("http://%s:%d/parse/l5k", host, port);
        return sendPostRequest(endpoint, l5kContent);
    }

    /**
     * Parse a JSON PLC configuration file.
     *
     * @param jsonContent The JSON file content as a string
     * @return JSON response as a string
     * @throws IOException if parsing fails
     */
    public String parseJSON(String jsonContent) throws IOException {
        String endpoint = String.format("http://%s:%d/parse/json", host, port);
        return sendPostRequest(endpoint, jsonContent);
    }

    /**
     * Extract the embedded Python executable to a temp directory.
     */
    private void extractExecutable() throws IOException {
        logger.debug("Extracting parser executable");

        // Get executable from resources
        InputStream is = getClass().getResourceAsStream("/bin/plc-parser-service");
        if (is == null) {
            throw new IOException("Parser executable not found in module resources");
        }

        // Create temp file
        Path tempDir = Files.createTempDirectory("plc-parser");
        executablePath = tempDir.resolve("plc-parser-service");

        // Copy to temp file
        Files.copy(is, executablePath, StandardCopyOption.REPLACE_EXISTING);
        is.close();

        // Make executable (Unix only)
        executablePath.toFile().setExecutable(true);

        logger.debug("Executable extracted to: {}", executablePath);
    }

    /**
     * Start a thread to log output from the parser process.
     */
    private void startOutputLogger() {
        Thread loggerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(parserProcess.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[Parser] {}", line);
                }

            } catch (IOException e) {
                if (parserProcess.isAlive()) {
                    logger.error("Error reading parser output", e);
                }
            }
        });

        loggerThread.setDaemon(true);
        loggerThread.setName("Parser-Output-Logger");
        loggerThread.start();
    }

    /**
     * Send a POST request to an endpoint.
     *
     * @param endpoint The full URL endpoint
     * @param body     The request body
     * @return Response body as string
     * @throws IOException if request fails
     */
    private String sendPostRequest(String endpoint, String body) throws IOException {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/plain");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);

            // Send request body
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            // Read response
            int responseCode = conn.getResponseCode();

            if (responseCode == 200) {
                return readResponse(conn.getInputStream());
            } else {
                String error = readResponse(conn.getErrorStream());
                throw new IOException("Parser returned error " + responseCode + ": " + error);
            }

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Read response from input stream.
     */
    private String readResponse(InputStream is) throws IOException {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
        }
        return response.toString();
    }
}
