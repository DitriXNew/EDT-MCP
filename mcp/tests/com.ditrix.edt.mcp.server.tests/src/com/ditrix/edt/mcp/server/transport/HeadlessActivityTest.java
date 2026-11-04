// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;
import com.ditrix.edt.mcp.server.*;
import com.ditrix.edt.mcp.server.ipc.*;
import com.ditrix.edt.mcp.server.tools.*;

public class HeadlessActivityTest extends StdioTestSupport
{
    @Test public void socketEofCannotEndTheLifetimeOfTheStillExecutingTool() throws Exception {
        BlockingQueue<Runnable> timers = new LinkedBlockingQueue<>();
        AtomicInteger exits = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var tools = McpToolRegistry.getInstance(); var savedTools = tools.getAllTools();
        Field plugin = Activator.class.getDeclaredField("plugin"); plugin.setAccessible(true); Object savedPlugin = plugin.get(null);
        String previousApplication = System.getProperty("eclipse.application");
        try (var lifetime = new HeadlessLifetime(Duration.ofMinutes(30), (task, delay) -> { timers.add(task); return () -> { }; }, exits::incrementAndGet)) {
            Runnable originalDeadline = timers.take();
            Activator activator = mock(Activator.class);
            when(activator.getHeadlessLifetime()).thenReturn(lifetime);
            when(activator.getMcpServer()).thenReturn(new McpServer());
            when(activator.getPreferenceStore()).thenReturn(new PreferenceStore());
            plugin.set(null, activator); System.setProperty("eclipse.application", HostKind.APPLICATION_ID);
            tools.replaceAll(List.of(new IMcpTool() {
                public String getName() { return "list_projects"; }
                public String getDescription() { return "Blocked read"; }
                public String getInputSchema() { return "{\"type\":\"object\"}"; }
                public String execute(Map<String, String> params) {
                    entered.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError("EDT operation must survive peer EOF", e); }
                    return "completed";
                }
            }));
            var registry = registry(); var catalog = catalog();
            catalog.getAsJsonObject("tools").getAsJsonArray("tools").get(0).getAsJsonObject().addProperty("name", "list_projects");
            try (var listener = new IpcListener(registry, "test");
                 var bootstrap = new StdioBootstrap(registry, () -> { fail("Existing host must be reused"); return null; }, new ByteArrayOutputStream(), catalog)) {
                bootstrap.accept(init(1, false)); bootstrap.accept(initialized()); bootstrap.accept(call(2, "list_projects"));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                bootstrap.close();
                for (int i = 0; i < 100; i++) { originalDeadline.run(); }
                assertEquals(0, exits.get());
                assertNull("No new idle interval while the detached tool still runs", timers.poll(200, TimeUnit.MILLISECONDS));
                release.countDown();
                Runnable afterTool = timers.poll(5, TimeUnit.SECONDS); assertNotNull("Completion must rearm idle expiry", afterTool);
                originalDeadline.run(); assertEquals(0, exits.get());
                afterTool.run(); assertEquals(1, exits.get());
            }
        }
        finally {
            release.countDown(); plugin.set(null, savedPlugin); tools.replaceAll(savedTools);
            if (previousApplication == null) { System.clearProperty("eclipse.application"); } else { System.setProperty("eclipse.application", previousApplication); }
        }
    }

    @Test public void acceptedSocketKeepsTheHeadlessHostAliveUntilEof() throws Exception {
        BlockingQueue<Runnable> timers = new LinkedBlockingQueue<>();
        AtomicInteger exits = new AtomicInteger();
        Field plugin = Activator.class.getDeclaredField("plugin"); plugin.setAccessible(true); Object saved = plugin.get(null);
        try (var lifetime = new HeadlessLifetime(Duration.ofMinutes(30), (task, delay) -> { timers.add(task); return () -> { }; }, exits::incrementAndGet)) {
            Runnable original = timers.take();
            Activator activator = mock(Activator.class); when(activator.getHeadlessLifetime()).thenReturn(lifetime);
            when(activator.getMcpServer()).thenReturn(new McpServer()); plugin.set(null, activator);
            var registry = registry();
            try (var listener = new IpcListener(registry, "test")) {
                var endpoint = registry.read();
                try (var socket = new JsonFrames.Socket(java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX))) {
                    socket.connect(java.net.UnixDomainSocketAddress.of(endpoint.socket())); IpcHandshake.client(socket, endpoint);
                    original.run(); assertEquals(0, exits.get()); assertNull(timers.poll(50, TimeUnit.MILLISECONDS));
                }
                Runnable afterEof = timers.poll(5, TimeUnit.SECONDS); assertNotNull(afterEof);
                afterEof.run(); assertEquals(1, exits.get());
            }
        }
        finally { plugin.set(null, saved); }
    }
}
