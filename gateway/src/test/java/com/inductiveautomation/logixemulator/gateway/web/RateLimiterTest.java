package com.inductiveautomation.logixemulator.gateway.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class RateLimiterTest {

    @Test
    @DisplayName("Should allow requests within user limit")
    void testAllowWithinUserLimit() {
        RateLimiter limiter = new RateLimiter(5, 100, TimeUnit.HOURS.toMillis(1));

        for (int i = 0; i < 5; i++) {
            RateLimiter.RateLimitResult result = limiter.checkRequest("user1", "1.2.3.4");
            assertThat(result.isAllowed()).isTrue();
        }
    }

    @Test
    @DisplayName("Should block requests exceeding user limit")
    void testBlockExceedingUserLimit() {
        RateLimiter limiter = new RateLimiter(3, 100, TimeUnit.HOURS.toMillis(1));

        for (int i = 0; i < 3; i++) {
            RateLimiter.RateLimitResult result = limiter.checkRequest("user1", "1.2.3.4");
            assertThat(result.isAllowed()).isTrue();
        }

        RateLimiter.RateLimitResult blocked = limiter.checkRequest("user1", "1.2.3.4");
        assertThat(blocked.isAllowed()).isFalse();
        assertThat(blocked.getLimitType()).isEqualTo("user");
        assertThat(blocked.getLimit()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should block requests exceeding IP limit")
    void testBlockExceedingIpLimit() {
        RateLimiter limiter = new RateLimiter(100, 3, TimeUnit.HOURS.toMillis(1));

        // Three different users from same IP
        limiter.checkRequest("user1", "1.2.3.4");
        limiter.checkRequest("user2", "1.2.3.4");
        limiter.checkRequest("user3", "1.2.3.4");

        RateLimiter.RateLimitResult blocked = limiter.checkRequest("user4", "1.2.3.4");
        assertThat(blocked.isAllowed()).isFalse();
        assertThat(blocked.getLimitType()).isEqualTo("ip");
    }

    @Test
    @DisplayName("Should track remaining requests")
    void testRemainingCount() {
        RateLimiter limiter = new RateLimiter(5, 100, TimeUnit.HOURS.toMillis(1));

        RateLimiter.RateLimitResult first = limiter.checkRequest("user1", "1.2.3.4");
        assertThat(first.isAllowed()).isTrue();
        assertThat(first.getRemaining()).isEqualTo(4);

        RateLimiter.RateLimitResult second = limiter.checkRequest("user1", "1.2.3.4");
        assertThat(second.isAllowed()).isTrue();
        assertThat(second.getRemaining()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should track users independently")
    void testIndependentUsers() {
        RateLimiter limiter = new RateLimiter(2, 100, TimeUnit.HOURS.toMillis(1));

        // User1 uses all their requests
        limiter.checkRequest("user1", "1.2.3.4");
        limiter.checkRequest("user1", "1.2.3.4");
        RateLimiter.RateLimitResult user1Blocked = limiter.checkRequest("user1", "1.2.3.4");
        assertThat(user1Blocked.isAllowed()).isFalse();

        // User2 should still have requests available
        RateLimiter.RateLimitResult user2Ok = limiter.checkRequest("user2", "5.6.7.8");
        assertThat(user2Ok.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Should track IPs independently")
    void testIndependentIPs() {
        RateLimiter limiter = new RateLimiter(100, 2, TimeUnit.HOURS.toMillis(1));

        // IP 1.2.3.4 uses all requests
        limiter.checkRequest("user1", "1.2.3.4");
        limiter.checkRequest("user2", "1.2.3.4");
        RateLimiter.RateLimitResult ip1Blocked = limiter.checkRequest("user3", "1.2.3.4");
        assertThat(ip1Blocked.isAllowed()).isFalse();

        // Different IP should still work
        RateLimiter.RateLimitResult ip2Ok = limiter.checkRequest("user1", "5.6.7.8");
        assertThat(ip2Ok.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Should return user stats")
    void testGetUserStats() {
        RateLimiter limiter = new RateLimiter(10, 100, TimeUnit.HOURS.toMillis(1));

        // Unknown user should have zero count
        RateLimiter.RateLimitStats stats = limiter.getUserStats("unknown");
        assertThat(stats.getRequestCount()).isEqualTo(0);
        assertThat(stats.getRemaining()).isEqualTo(10);

        // After some requests
        limiter.checkRequest("user1", "1.2.3.4");
        limiter.checkRequest("user1", "1.2.3.4");

        stats = limiter.getUserStats("user1");
        assertThat(stats.getRequestCount()).isEqualTo(2);
        assertThat(stats.getRemaining()).isEqualTo(8);
    }

    @Test
    @DisplayName("Should return IP stats")
    void testGetIPStats() {
        RateLimiter limiter = new RateLimiter(100, 10, TimeUnit.HOURS.toMillis(1));

        RateLimiter.RateLimitStats stats = limiter.getIPStats("1.2.3.4");
        assertThat(stats.getRequestCount()).isEqualTo(0);

        limiter.checkRequest("user1", "1.2.3.4");

        stats = limiter.getIPStats("1.2.3.4");
        assertThat(stats.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should provide reset time when blocked")
    void testResetTime() {
        RateLimiter limiter = new RateLimiter(1, 100, TimeUnit.HOURS.toMillis(1));

        limiter.checkRequest("user1", "1.2.3.4");
        RateLimiter.RateLimitResult blocked = limiter.checkRequest("user1", "1.2.3.4");

        assertThat(blocked.isAllowed()).isFalse();
        assertThat(blocked.getResetTimeMs()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    @DisplayName("Default constructor should create valid limiter")
    void testDefaultConstructor() {
        RateLimiter limiter = new RateLimiter();

        // Should allow requests with default settings
        RateLimiter.RateLimitResult result = limiter.checkRequest("user1", "1.2.3.4");
        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Should reset counter after window expires")
    void testWindowReset() {
        // Use a very short window (50ms)
        RateLimiter limiter = new RateLimiter(2, 100, 50);

        limiter.checkRequest("user1", "1.2.3.4");
        limiter.checkRequest("user1", "1.2.3.4");

        // Should be blocked
        RateLimiter.RateLimitResult blocked = limiter.checkRequest("user1", "1.2.3.4");
        assertThat(blocked.isAllowed()).isFalse();

        // Wait for window to expire, then verify requests are allowed again
        await().atMost(1, TimeUnit.SECONDS).until(() ->
            limiter.checkRequest("user1", "1.2.3.4").isAllowed()
        );
    }
}
