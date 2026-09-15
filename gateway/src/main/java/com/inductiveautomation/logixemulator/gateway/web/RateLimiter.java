package com.inductiveautomation.logixemulator.gateway.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/**
 * Rate limiter to prevent DoS attacks via rapid file uploads.
 *
 * Features:
 * - Per-user rate limiting (100 uploads/hour)
 * - Per-IP rate limiting (1000 uploads/hour)
 * - Automatic cleanup of expired entries
 * - Thread-safe implementation
 */
public class RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    // Rate limit configurations
    private final int maxRequestsPerUser;
    private final int maxRequestsPerIP;
    private final long windowMs;

    // Tracking structures: key -> RequestCounter
    private final Map<String, RequestCounter> userRequests = new ConcurrentHashMap<>();
    private final Map<String, RequestCounter> ipRequests = new ConcurrentHashMap<>();

    // Cleanup task runs periodically to remove expired entries
    private final AtomicLong lastCleanupTime = new AtomicLong(System.currentTimeMillis());
    private static final long CLEANUP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);

    /**
     * Create rate limiter with default limits.
     * - 100 requests per hour per user
     * - 1000 requests per hour per IP
     */
    public RateLimiter() {
        this(100, 1000, TimeUnit.HOURS.toMillis(1));
    }

    /**
     * Create rate limiter with custom limits.
     *
     * @param maxRequestsPerUser Maximum requests allowed per user in time window
     * @param maxRequestsPerIP Maximum requests allowed per IP in time window
     * @param windowMs Time window in milliseconds
     */
    public RateLimiter(int maxRequestsPerUser, int maxRequestsPerIP, long windowMs) {
        this.maxRequestsPerUser = maxRequestsPerUser;
        this.maxRequestsPerIP = maxRequestsPerIP;
        this.windowMs = windowMs;

        logger.info("Rate limiter initialized: {} req/window per user, {} req/window per IP, window={}ms",
                    maxRequestsPerUser, maxRequestsPerIP, windowMs);
    }

    /**
     * Check if a request from this user/IP should be allowed.
     * Returns RateLimitResult with decision and metadata.
     *
     * @param username Username making the request
     * @param ipAddress IP address making the request
     * @return RateLimitResult indicating if request is allowed and rate limit metadata
     */
    public RateLimitResult checkRequest(String username, String ipAddress) {
        periodicCleanup();

        long now = System.currentTimeMillis();

        // Check user rate limit
        RequestCounter userCounter = userRequests.computeIfAbsent(
            username,
            k -> new RequestCounter(now)
        );

        // Check IP rate limit
        RequestCounter ipCounter = ipRequests.computeIfAbsent(
            ipAddress,
            k -> new RequestCounter(now)
        );

        // Check user limit first — atomic check-and-increment
        if (!userCounter.allowAndIncrement(now, windowMs, maxRequestsPerUser)) {
            logger.warn("Rate limit exceeded for user: {} ({}/{} requests in {}ms window)",
                       username, userCounter.getCount(), maxRequestsPerUser, windowMs);

            return new RateLimitResult(
                false,
                "user",
                maxRequestsPerUser,
                0,
                userCounter.getResetTimeMs(now, windowMs)
            );
        }

        // Check IP limit — atomic check-and-increment
        if (!ipCounter.allowAndIncrement(now, windowMs, maxRequestsPerIP)) {
            logger.warn("Rate limit exceeded for IP: {} ({}/{} requests in {}ms window)",
                       ipAddress, ipCounter.getCount(), maxRequestsPerIP, windowMs);

            return new RateLimitResult(
                false,
                "ip",
                maxRequestsPerIP,
                0,
                ipCounter.getResetTimeMs(now, windowMs)
            );
        }

        // Calculate remaining requests (use the more restrictive limit)
        int userRemaining = maxRequestsPerUser - userCounter.getCount();
        int ipRemaining = maxRequestsPerIP - ipCounter.getCount();
        int remaining = Math.min(userRemaining, ipRemaining);

        return new RateLimitResult(
            true,
            null,
            Math.min(maxRequestsPerUser, maxRequestsPerIP),
            remaining,
            0
        );
    }

    /**
     * Periodic cleanup of expired entries to prevent memory leaks.
     */
    private void periodicCleanup() {
        long now = System.currentTimeMillis();

        long lastClean = lastCleanupTime.get();
        if (now - lastClean > CLEANUP_INTERVAL_MS && lastCleanupTime.compareAndSet(lastClean, now)) {
            int usersBefore = userRequests.size();
            int ipsBefore = ipRequests.size();

            // Remove expired user entries
            userRequests.entrySet().removeIf(entry ->
                entry.getValue().isExpired(now, windowMs)
            );

            // Remove expired IP entries
            ipRequests.entrySet().removeIf(entry ->
                entry.getValue().isExpired(now, windowMs)
            );

            int usersRemoved = usersBefore - userRequests.size();
            int ipsRemoved = ipsBefore - ipRequests.size();

            if (usersRemoved > 0 || ipsRemoved > 0) {
                logger.debug("Rate limiter cleanup: removed {} expired user entries, {} expired IP entries",
                            usersRemoved, ipsRemoved);
            }
        }
    }

    /**
     * Get current statistics for a user.
     * Useful for monitoring and debugging.
     */
    public RateLimitStats getUserStats(String username) {
        RequestCounter counter = userRequests.get(username);
        if (counter == null) {
            return new RateLimitStats(0, maxRequestsPerUser, 0);
        }

        long now = System.currentTimeMillis();
        int count = counter.getCount();
        int remaining = Math.max(0, maxRequestsPerUser - count);
        long resetTime = counter.getResetTimeMs(now, windowMs);

        return new RateLimitStats(count, remaining, resetTime);
    }

    /**
     * Get current statistics for an IP address.
     */
    public RateLimitStats getIPStats(String ipAddress) {
        RequestCounter counter = ipRequests.get(ipAddress);
        if (counter == null) {
            return new RateLimitStats(0, maxRequestsPerIP, 0);
        }

        long now = System.currentTimeMillis();
        int count = counter.getCount();
        int remaining = Math.max(0, maxRequestsPerIP - count);
        long resetTime = counter.getResetTimeMs(now, windowMs);

        return new RateLimitStats(count, remaining, resetTime);
    }

    /**
     * Result of a rate limit check.
     */
    public static class RateLimitResult {
        private final boolean allowed;
        private final String limitType; // "user" or "ip", null if allowed
        private final int limit;
        private final int remaining;
        private final long resetTimeMs;

        public RateLimitResult(boolean allowed, String limitType, int limit, int remaining, long resetTimeMs) {
            this.allowed = allowed;
            this.limitType = limitType;
            this.limit = limit;
            this.remaining = remaining;
            this.resetTimeMs = resetTimeMs;
        }

        public boolean isAllowed() { return allowed; }
        public String getLimitType() { return limitType; }
        public int getLimit() { return limit; }
        public int getRemaining() { return remaining; }
        public long getResetTimeMs() { return resetTimeMs; }
    }

    /**
     * Statistics for rate limit monitoring.
     */
    public static class RateLimitStats {
        private final int requestCount;
        private final int remaining;
        private final long resetTimeMs;

        public RateLimitStats(int requestCount, int remaining, long resetTimeMs) {
            this.requestCount = requestCount;
            this.remaining = remaining;
            this.resetTimeMs = resetTimeMs;
        }

        public int getRequestCount() { return requestCount; }
        public int getRemaining() { return remaining; }
        public long getResetTimeMs() { return resetTimeMs; }
    }

    /**
     * Counter for tracking requests in a time window.
     * Resets automatically when the window expires.
     */
    private static class RequestCounter {
        private volatile long startTime;
        private final AtomicInteger count = new AtomicInteger(0);

        public RequestCounter(long startTime) {
            this.startTime = startTime;
        }

        /**
         * Check if a request is allowed and atomically increment if so.
         * Returns true if within limits, false if exceeded.
         * Resets the counter when the window has expired.
         */
        public synchronized boolean allowAndIncrement(long now, long windowMs, int maxRequests) {
            if (isExpired(now, windowMs)) {
                // Reset for new window
                count.set(1);
                startTime = now;
                return true;
            }
            if (count.get() < maxRequests) {
                count.incrementAndGet();
                return true;
            }
            return false;
        }

        /**
         * Check if a request would be allowed (without incrementing).
         */
        public synchronized boolean allowRequest(long now, long windowMs, int maxRequests) {
            if (isExpired(now, windowMs)) {
                return true;
            }
            return count.get() < maxRequests;
        }

        /**
         * Get current count.
         */
        public int getCount() {
            return count.get();
        }

        /**
         * Check if this counter's window has expired.
         */
        public boolean isExpired(long now, long windowMs) {
            return (now - startTime) > windowMs;
        }

        /**
         * Get the time when this counter will reset (in milliseconds since epoch).
         */
        public long getResetTimeMs(long now, long windowMs) {
            return startTime + windowMs;
        }
    }
}
