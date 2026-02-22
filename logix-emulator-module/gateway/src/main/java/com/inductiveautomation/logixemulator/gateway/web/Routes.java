package com.inductiveautomation.logixemulator.gateway.web;

/** Centralised route path constants — mirrors api.ts on the client side. */
public final class Routes {
    private Routes() {}

    public static final String UPLOAD                  = "/upload";
    public static final String DEVICES                 = "/devices";
    public static final String DEVICE_STATUS           = "/device/:name/status";
    public static final String DEVICE_TAGS             = "/device/:name/tags";
    public static final String DEVICE_TAGS_CHILDREN    = "/device/:name/tags/children";
    public static final String DEVICE_TAGS_LIVE        = "/device/:name/tags/live";
    public static final String DEVICE_TAGS_SIMULATED   = "/device/:name/tags/simulated";
    public static final String DEVICE_TAG_WRITE        = "/device/:name/tag/write";
    public static final String DEVICE_TAG_SIMULATE     = "/device/:name/tag/simulate";
    public static final String DEVICE_SIMULATION_SCOPE = "/device/:name/simulation/scope";
    public static final String DEVICE_SIMULATION_ALL   = "/device/:name/simulation/all";
    public static final String DEVICE_DELETE           = "/device/:name/delete";
    public static final String SYSTEM_STATS            = "/system/stats";
    public static final String SYSTEM_LOGS             = "/system/logs";
    public static final String HEALTH                  = "/health";
    public static final String AUTH_STATUS             = "/auth/status";
    public static final String AUTH_CHECK              = "/auth/check";
}
