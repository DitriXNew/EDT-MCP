/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;

/**
 * Every tools/call that reaches its tool is listed in {@link InFlightToolCalls} while the tool runs
 * and removed when it returns or throws; a call refused before the tool runs is never listed (#993).
 */
public class McpProtocolHandlerInFlightTest
{
    private static final long WAIT_SECONDS = 10L;

    private final McpToolRegistry registry = McpToolRegistry.getInstance();
    private McpProtocolHandler handler;

    @Before
    public void setUp()
    {
        registry.clear();
        handler = new McpProtocolHandler();
    }

    @After
    public void tearDown()
    {
        registry.clear();
    }

    @Test
    public void testARunningCallIsListedWithItsProjectAndGoneAfterItReturns() throws Exception
    {
        BlockingTool tool = new BlockingTool("inflight_blocking_tool", false); //$NON-NLS-1$
        registry.register(tool);

        Thread caller = dispatch(toolCall(tool.getName(), "{\"projectName\":\"InFlightProject\",\"secret\":\"s3cr3t\"}")); //$NON-NLS-1$
        try
        {
            assertTrue("the tool must start", tool.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
            List<InFlightToolCalls.Snapshot> listed = listed(tool.getName());
            assertEquals("the running call is listed exactly once", 1, listed.size());
            assertEquals("InFlightProject", listed.get(0).projectName()); //$NON-NLS-1$
            assertFalse("only projectName of the arguments is kept",
                listed.get(0).toString().contains("s3cr3t")); //$NON-NLS-1$
        }
        finally
        {
            tool.release.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
        assertFalse(caller.isAlive());
        assertTrue("a returned call is no longer listed", listed(tool.getName()).isEmpty());
    }

    @Test
    public void testANonStringProjectNameIsNotReported() throws Exception
    {
        // The tool does not declare projectName, so the validator lets any value through; the
        // flattened params would carry the object re-serialized as text.
        BlockingTool tool = new BlockingTool("inflight_object_project_tool", false); //$NON-NLS-1$
        registry.register(tool);

        Thread caller = dispatch(toolCall(tool.getName(), "{\"projectName\":{\"nested\":\"s3cr3t\"}}")); //$NON-NLS-1$
        try
        {
            assertTrue(tool.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("an object reached the tool, so the test sees the flattened value", "{\"nested\":\"s3cr3t\"}", //$NON-NLS-1$
                tool.projectName);
            List<InFlightToolCalls.Snapshot> listed = listed(tool.getName());
            assertEquals(1, listed.size());
            assertNull("only a JSON string is a project name", listed.get(0).projectName());
        }
        finally
        {
            tool.release.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
    }

    @Test
    public void testACallWhoseToolThrowsLeavesNoEntry() throws Exception
    {
        BlockingTool tool = new BlockingTool("inflight_throwing_tool", true); //$NON-NLS-1$
        registry.register(tool);

        Thread caller = dispatch(toolCall(tool.getName(), "{}")); //$NON-NLS-1$
        try
        {
            assertTrue(tool.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, listed(tool.getName()).size());
        }
        finally
        {
            tool.release.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
        assertFalse(caller.isAlive());
        assertTrue("the finally must remove the call even when the tool throws",
            listed(tool.getName()).isEmpty());
    }

    @Test
    public void testTheToolSeesItsOwnCallAsTheCurrentOne() throws Exception
    {
        AtomicReference<List<InFlightToolCalls.Snapshot>> seen = new AtomicReference<>();
        AtomicReference<InFlightToolCalls.Handle> current = new AtomicReference<>();
        registry.register(new StubTool("inflight_self_tool") //$NON-NLS-1$
        {
            @Override
            public String execute(Map<String, String> params)
            {
                current.set(InFlightToolCalls.current());
                seen.set(InFlightToolCalls.report(InFlightToolCalls.current()).calls());
                return "{}"; //$NON-NLS-1$
            }
        });

        handler.processRequest(toolCall("inflight_self_tool", "{}")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the dispatch registered the call before execute", current.get() != null);
        assertTrue("excluding the current registration leaves the tool's own call out",
            seen.get().stream().noneMatch(c -> "inflight_self_tool".equals(c.tool()))); //$NON-NLS-1$
        assertNull("the dispatch thread is left with nothing bound", InFlightToolCalls.current());
    }

    @Test
    public void testADisabledToolIsNeverListed()
    {
        // "git" ships DISABLED, so the refusal branch runs before the tool would.
        AtomicReference<List<InFlightToolCalls.Snapshot>> seen = new AtomicReference<>();
        registry.register(new StubTool("git") //$NON-NLS-1$
        {
            @Override
            public String execute(Map<String, String> params)
            {
                seen.set(InFlightToolCalls.report(null).calls());
                return "{}"; //$NON-NLS-1$
            }
        });
        assertFalse("git must ship disabled, or this asserts nothing", registry.isToolEnabled("git")); //$NON-NLS-1$

        RecordingRegistrations recorder = new RecordingRegistrations("git"); //$NON-NLS-1$
        handler.processRequest(toolCall("git", "{}")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a refused tool must not have run", seen.get());
        assertFalse("a refused call is never registered", recorder.sawIt());
        assertTrue("a refused call leaves no entry behind", listed(recorder.tool).isEmpty());
    }

    @Test
    public void testACallWithInvalidArgumentsIsNeverListed()
    {
        AtomicReference<String> ran = new AtomicReference<>();
        registry.register(new StubTool("inflight_typed_tool") //$NON-NLS-1$
        {
            @Override
            public String getInputSchema()
            {
                return JsonSchemaBuilder.object().integerProperty("limit", "count").build(); //$NON-NLS-1$ //$NON-NLS-2$
            }

            @Override
            public String execute(Map<String, String> params)
            {
                ran.set("ran"); //$NON-NLS-1$
                return "{}"; //$NON-NLS-1$
            }
        });

        RecordingRegistrations recorder = new RecordingRegistrations("inflight_typed_tool"); //$NON-NLS-1$
        String response = handler.processRequest(toolCall("inflight_typed_tool", "{\"limit\":\"many\"}")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(response, response.contains("isError")); //$NON-NLS-1$
        assertNull("an invalid call must not have run", ran.get());
        assertFalse("a refused call is never registered", recorder.sawIt());
        assertTrue("a refused call leaves no entry behind", listed(recorder.tool).isEmpty());
    }

    private Thread dispatch(String request)
    {
        Thread caller = new Thread(() -> {
            try
            {
                handler.processRequest(request);
            }
            catch (RuntimeException e)
            {
                // the throwing tool's exception surfaces here; the entry is what is asserted
            }
        }, "inflight-test-caller"); //$NON-NLS-1$
        caller.setDaemon(true);
        caller.start();
        return caller;
    }

    private static List<InFlightToolCalls.Snapshot> listed(String tool)
    {
        return InFlightToolCalls.report(null).calls().stream().filter(c -> tool.equals(c.tool())).toList();
    }

    private static String toolCall(String name, String argumentsJson)
    {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" //$NON-NLS-1$
            + name + "\",\"arguments\":" + argumentsJson + "}}"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Watches the registry from a background thread while one request runs, so a registration
     * that is removed again before processRequest returns is still caught.
     */
    private static final class RecordingRegistrations
    {
        private final String tool;
        private final java.util.concurrent.atomic.AtomicBoolean seen = new java.util.concurrent.atomic.AtomicBoolean();
        private final Thread watcher;
        private volatile boolean stop;

        RecordingRegistrations(String tool)
        {
            this.tool = tool;
            this.watcher = new Thread(() -> {
                while (!stop)
                {
                    if (!listed(this.tool).isEmpty())
                    {
                        seen.set(true);
                    }
                    Thread.onSpinWait();
                }
            }, "inflight-test-watcher"); //$NON-NLS-1$
            watcher.setDaemon(true);
            watcher.start();
        }

        boolean sawIt()
        {
            stop = true;
            try
            {
                watcher.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return seen.get();
        }
    }

    private static class StubTool implements IMcpTool
    {
        private final String name;

        StubTool(String name)
        {
            this.name = name;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String getDescription()
        {
            return "In-flight test tool"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "{}"; //$NON-NLS-1$
        }
    }

    private static final class BlockingTool extends StubTool
    {
        private final boolean throwOnRelease;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        BlockingTool(String name, boolean throwOnRelease)
        {
            super(name);
            this.throwOnRelease = throwOnRelease;
        }

        volatile String projectName;

        @Override
        public String execute(Map<String, String> params)
        {
            projectName = params.get("projectName"); //$NON-NLS-1$
            started.countDown();
            try
            {
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            if (throwOnRelease)
            {
                throw new IllegalStateException("tool failure"); //$NON-NLS-1$
            }
            return "{}"; //$NON-NLS-1$
        }
    }
}
