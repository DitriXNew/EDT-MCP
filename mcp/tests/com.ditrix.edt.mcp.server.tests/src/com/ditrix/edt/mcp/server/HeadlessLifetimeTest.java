// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server;

import static org.junit.Assert.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class HeadlessLifetimeTest
{
    private static final Duration IDLE = Duration.ofMinutes(30);
    private static class Clock implements HeadlessLifetime.Timer {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        @Override public AutoCloseable schedule(Runnable task, long delay) {
            tasks.add(task); delays.add(delay); return () -> { };
        }
        void fire() { tasks.get(tasks.size() - 1).run(); }
    }

    @Test public void initialIdleExpiresOnce() {
        Clock clock = new Clock(); AtomicInteger exits = new AtomicInteger();
        try (var lifetime = new HeadlessLifetime(IDLE, clock, exits::incrementAndGet)) {
            assertEquals(IDLE.toNanos(), clock.delays.get(0).longValue());
            clock.fire(); clock.fire(); assertEquals(1, exits.get());
        }
    }

    @Test public void zeroDisablesTheTimer() {
        Clock clock = new Clock();
        try (var lifetime = new HeadlessLifetime(Duration.ZERO, clock, () -> fail("Disabled"))) {
            lifetime.connect().close(); lifetime.tool().close(); assertTrue(clock.tasks.isEmpty());
        }
    }

    @Test public void connectAtExpiryInvalidatesEvenAlreadyQueuedCallback() {
        Clock clock = new Clock();
        try (var lifetime = new HeadlessLifetime(IDLE, clock, () -> fail("Client connected"))) {
            Runnable queued = clock.tasks.get(0);
            var client = lifetime.connect(); assertNotNull(client); queued.run();
            client.close(); queued.run(); assertEquals(2, clock.tasks.size());
        }
    }

    @Test public void expiryWinsAdmissionAndNeverAdmitsMoreWork() {
        Clock clock = new Clock(); AtomicInteger exits = new AtomicInteger();
        try (var lifetime = new HeadlessLifetime(IDLE, clock, exits::incrementAndGet)) {
            clock.fire(); assertNull(lifetime.connect()); assertNull(lifetime.tool()); assertEquals(1, exits.get());
        }
    }

    @Test public void runningToolBlocksExpiryAfterClientDisconnects() {
        Clock clock = new Clock(); AtomicInteger exits = new AtomicInteger();
        try (var lifetime = new HeadlessLifetime(IDLE, clock, exits::incrementAndGet)) {
            Runnable old = clock.tasks.get(0);
            var client = lifetime.connect(); var tool = lifetime.tool(); client.close();
            old.run(); clock.fire(); assertEquals(0, exits.get()); assertEquals(1, clock.tasks.size());
            tool.close(); assertEquals(2, clock.tasks.size());
            assertEquals(IDLE.toNanos(), clock.delays.get(1).longValue());
            old.run(); assertEquals(0, exits.get()); clock.fire(); assertEquals(1, exits.get());
        }
    }

    @Test public void longToolOutlivesMultipleOldTimerDeliveries() {
        Clock clock = new Clock(); AtomicInteger exits = new AtomicInteger();
        try (var lifetime = new HeadlessLifetime(IDLE, clock, exits::incrementAndGet)) {
            var tool = lifetime.tool();
            for (int i = 0; i < 100; i++) { clock.fire(); }
            assertEquals(0, exits.get()); tool.close(); clock.fire(); assertEquals(1, exits.get());
        }
    }

    @Test public void lastClientAndLastToolMustBothFinishAndLeasesAreIdempotent() {
        Clock clock = new Clock();
        try (var lifetime = new HeadlessLifetime(IDLE, clock, () -> fail("Still busy"))) {
            var a = lifetime.connect(); var b = lifetime.connect(); var tool = lifetime.tool(); var secondTool = lifetime.tool();
            a.close(); a.close(); tool.close(); clock.fire(); assertEquals(1, clock.tasks.size());
            b.close(); b.close(); assertEquals(1, clock.tasks.size());
            secondTool.close(); assertEquals(2, clock.tasks.size());
        }
    }

    @Test public void simultaneousExpiryAndConnectHaveOneLinearizedWinner() throws Exception {
        for (int i = 0; i < 100; i++) {
            Clock clock = new Clock(); AtomicInteger exits = new AtomicInteger();
            try (var lifetime = new HeadlessLifetime(IDLE, clock, exits::incrementAndGet)) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Thread timer = new Thread(() -> {
                    try { barrier.await(); clock.fire(); } catch (Exception e) { throw new AssertionError(e); }
                });
                timer.start(); barrier.await(); var client = lifetime.connect(); timer.join(3000);
                assertFalse(timer.isAlive()); assertEquals(client == null ? 1 : 0, exits.get());
                if (client != null) { client.close(); }
            }
        }
    }

    @Test public void closeDisarmsQueuedExpiry() {
        Clock clock = new Clock();
        var lifetime = new HeadlessLifetime(IDLE, clock, () -> fail("Closed"));
        lifetime.close(); clock.fire(); assertNull(lifetime.connect());
    }
}
