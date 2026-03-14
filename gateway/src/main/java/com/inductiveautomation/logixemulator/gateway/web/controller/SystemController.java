package com.inductiveautomation.logixemulator.gateway.web.controller;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.logixemulator.gateway.DeviceRegistry;
import com.inductiveautomation.logixemulator.gateway.web.GatewayAuthHelper;
import com.inductiveautomation.logixemulator.gateway.web.RateLimiter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles system-level endpoints: health check, system stats, gateway logs, and auth status.
 */
public class SystemController {

    private static final Logger logger = LoggerFactory.getLogger(SystemController.class);

    private static final DateTimeFormatter LOG_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final GatewayContext context;
    private final DeviceRegistry registry;
    private final RateLimiter readRateLimiter;

    public SystemController(GatewayContext context, DeviceRegistry registry, RateLimiter readRateLimiter) {
        this.context = context;
        this.registry = registry;
        this.readRateLimiter = readRateLimiter;
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    public JSONObject handleHealthCheck(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        return new JSONObject().put("status", "ok").put("service", "logix-file-upload");
    }

    /**
     * System stats — CPU usage, RAM usage, device count, module version.
     */
    public JSONObject handleSystemStats(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        try { if (!GatewayAuthHelper.requireAuthentication(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); return null; }

        JSONObject result = new JSONObject();

        var osBean = ManagementFactory.getOperatingSystemMXBean();
        double cpuPercent = 0;

        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                cpuPercent = sunBean.getCpuLoad() * 100;
                if (cpuPercent < 0) cpuPercent = 0;

                long ramTotal = sunBean.getTotalMemorySize();
                long ramFree = sunBean.getFreeMemorySize();
                result.put("ramUsage", ramTotal - ramFree);
                result.put("ramTotal", ramTotal);
            } else {
                MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                result.put("ramUsage", heap.getUsed());
                result.put("ramTotal", heap.getMax());

                double loadAvg = osBean.getSystemLoadAverage();
                if (loadAvg >= 0) {
                    cpuPercent = Math.min((loadAvg / osBean.getAvailableProcessors()) * 100, 100);
                }
            }
        } catch (Exception e) {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            result.put("ramUsage", heap.getUsed());
            result.put("ramTotal", heap.getMax());
        }

        return result.put("success", true)
            .put("cpuUsage", Math.round(cpuPercent * 10) / 10.0)
            .put("moduleVersion", "9.2.4")
            .put("deviceCount", registry.getRegisteredDevices().size());
    }

    private static final String MODULE_LOGGER_PREFIX = "com.inductiveautomation.logixemulator";

    /**
     * Gateway logs — reads entries from Ignition's SQLite system_logs.idb database.
     * Mirrors the Camera Driver's GatewayLogHandler pattern.
     *
     * Query parameters:
     *   - limit:      max entries to return (default 100, max 500)
     *   - level:      comma-separated log levels to include (ERROR, WARN, INFO, DEBUG)
     *   - after:      only return entries after this event ID (for incremental polling)
     *   - filter:     keyword filter (case-insensitive, message or logger)
     *   - moduleOnly: "false" to show all loggers; default true (filters to module prefix)
     */
    public JSONObject handleSystemLogs(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        try { if (!GatewayAuthHelper.requireAuthentication(ctx, resp)) return null; }
        catch (Exception e) { resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); return null; }

        JSONObject result = new JSONObject();

        RateLimiter.RateLimitResult readRate = readRateLimiter.checkRequest(
            GatewayAuthHelper.getUsername(ctx), GatewayAuthHelper.getClientIP(ctx));
        if (!readRate.isAllowed()) { resp.setStatus(429); return result.put("success", false).put("error", "Rate limit exceeded"); }

        int limit = Math.min(parseIntParam(ctx, "limit", 100), 500);
        String levelParam = ctx.getRequest().getParameter("level");
        String afterParam = ctx.getRequest().getParameter("after");
        String filterParam = ctx.getRequest().getParameter("filter");
        String moduleOnlyParam = ctx.getRequest().getParameter("moduleOnly");
        boolean moduleOnly = moduleOnlyParam == null || !"false".equalsIgnoreCase(moduleOnlyParam);

        Set<String> levelFilter = new HashSet<>();
        if (levelParam != null && !levelParam.isEmpty()) {
            Arrays.stream(levelParam.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(s -> !s.isEmpty())
                .forEach(levelFilter::add);
        }

        long afterEventId = 0;
        if (afterParam != null && !afterParam.isEmpty()) {
            try {
                afterEventId = Long.parseLong(afterParam);
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        File logDb = findSystemLogsDb();
        if (logDb == null) {
            logger.warn("system_logs.idb not found");
            return result.put("success", false).put("error", "system_logs.idb not found");
        }

        try {
            List<JSONObject> entries = readLogEntriesFromDb(logDb, limit, filterParam, levelFilter, afterEventId, moduleOnly);
            JSONArray entriesArray = new JSONArray();
            long lastEventId = 0;
            for (JSONObject entry : entries) {
                entriesArray.put(entry);
                // Track the last event_id for client-side incremental polling
                try {
                    long id = Long.parseLong(entry.getString("id"));
                    if (id > lastEventId) lastEventId = id;
                } catch (Exception ignored) { /* ignore */ }
            }

            return result.put("success", true)
                .put("entries", entriesArray)
                .put("count", entries.size())
                .put("lastEventId", lastEventId)
                .put("hasMore", entries.size() >= limit);

        } catch (Exception e) {
            logger.error("Error reading gateway logs from SQLite", e);
            return result.put("success", false).put("error", "Failed to read gateway logs");
        }
    }

    public JSONObject handleAuthStatus(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        boolean isAuth = GatewayAuthHelper.isGatewayAuthenticated(ctx);
        return new JSONObject()
            .put("authenticated", isAuth)
            .put("loginUrl", "/web/login");
    }

    /**
     * Auth check endpoint — returns 200 if authenticated, 401 if not.
     */
    public JSONObject handleAuthCheck(RequestContext ctx, HttpServletResponse resp) throws JSONException {
        if (!GatewayAuthHelper.isGatewayAuthenticated(ctx)) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return new JSONObject().put("authenticated", false);
        }
        return new JSONObject().put("authenticated", true);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private int parseIntParam(RequestContext ctx, String name, int defaultValue) {
        String value = ctx.getRequest().getParameter(name);
        if (value != null && !value.isEmpty()) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return defaultValue;
    }

    private List<JSONObject> readLogEntriesFromDb(File logDb, int maxLines, String filter,
                                                   Set<String> levelFilter, long afterEventId,
                                                   boolean moduleOnly)
            throws JSONException {
        List<JSONObject> entries = new ArrayList<>();
        String url = "jdbc:sqlite:" + logDb.getAbsolutePath();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT event_id, timestmp, formatted_message, logger_name, level_string ");
        sql.append("FROM logging_event WHERE 1=1 ");

        List<Object> params = new ArrayList<>();

        if (afterEventId > 0) {
            sql.append("AND event_id > ? ");
            params.add(afterEventId);
        }

        if (!levelFilter.isEmpty()) {
            sql.append("AND level_string IN (");
            sql.append(String.join(",", Collections.nCopies(levelFilter.size(), "?")));
            sql.append(") ");
            params.addAll(levelFilter);
        }

        // Filter to module loggers only (mirrors Camera Driver moduleOnly=true behaviour)
        if (moduleOnly) {
            sql.append("AND logger_name LIKE ? ");
            params.add(MODULE_LOGGER_PREFIX + "%");
        }

        if (filter != null && !filter.isEmpty()) {
            String safeFilter = filter.length() > 200 ? filter.substring(0, 200) : filter;
            String escapedFilter = safeFilter.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            sql.append("AND (formatted_message LIKE ? ESCAPE '\\' OR logger_name LIKE ? ESCAPE '\\') ");
            params.add("%" + escapedFilter + "%");
            params.add("%" + escapedFilter + "%");
        }

        sql.append("ORDER BY event_id DESC LIMIT ?");
        params.add(maxLines);

        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof Long) {
                    stmt.setLong(i + 1, (Long) param);
                } else if (param instanceof Integer) {
                    stmt.setInt(i + 1, (Integer) param);
                } else {
                    stmt.setString(i + 1, param.toString());
                }
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long eventId = rs.getLong("event_id");
                    long timestmp = rs.getLong("timestmp");
                    String message = rs.getString("formatted_message");
                    String loggerName = rs.getString("logger_name");
                    String level = rs.getString("level_string");

                    String formattedTime = LOG_DATE_FORMAT.format(Instant.ofEpochMilli(timestmp));

                    String shortSource = loggerName;
                    if (loggerName != null && loggerName.contains(".")) {
                        shortSource = loggerName.substring(loggerName.lastIndexOf('.') + 1);
                    }

                    entries.add(new JSONObject()
                        .put("id", String.valueOf(eventId))
                        .put("timestamp", formattedTime)
                        .put("epochMs", timestmp)
                        .put("level", level)
                        .put("source", shortSource)
                        .put("logger", loggerName)
                        .put("message", message != null ? message : ""));
                }
            }
        } catch (SQLException e) {
            logger.error("Error reading from SQLite database: {}", e.getMessage(), e);
        }

        Collections.reverse(entries);
        return entries;
    }

    private File findSystemLogsDb() {
        List<File> candidates = new ArrayList<>();

        try {
            File logsDir = context.getSystemManager().getLogsDir();
            if (logsDir != null) {
                candidates.add(new File(logsDir, "system_logs.idb"));
            }
        } catch (Exception e) {
            logger.trace("Could not resolve Ignition logs directory via SystemManager", e);
        }

        candidates.add(new File("/usr/local/bin/ignition/logs/system_logs.idb"));
        candidates.add(new File("/var/lib/ignition/logs/system_logs.idb"));
        candidates.add(new File("C:/Program Files/Inductive Automation/Ignition/logs/system_logs.idb"));

        for (File candidate : candidates) {
            if (candidate.exists() && candidate.canRead()) {
                logger.debug("Found system_logs.idb at: {}", candidate.getAbsolutePath());
                return candidate;
            }
        }
        return null;
    }
}
