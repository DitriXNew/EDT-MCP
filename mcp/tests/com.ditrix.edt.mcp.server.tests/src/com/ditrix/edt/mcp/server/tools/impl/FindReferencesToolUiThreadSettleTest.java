/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;
import org.junit.Test;

/**
 * The in-process bridge runs a tool on its caller's thread, which can be the SWT UI thread: the
 * search-scope settle must never sleep there (it would freeze EDT and the derived-data work it waits
 * for), while every other thread - the HTTP tool executor among them - keeps the bounded wait.
 */
public class FindReferencesToolUiThreadSettleTest
{
    private static final Map<String, String> ARGS = Map.of("projectName", "P", "objectFqn", "Catalog.X");

    @Test
    public void onTheUiThreadTheSettlerIsNeverCalledAndTheScanStillRuns()
    {
        List<String> events = new ArrayList<>();
        FindReferencesTool tool = new FindReferencesTool((name, budget) -> {
            fail("the settle must not sleep on the UI thread");
            return null;
        }, runnable -> events.add("ui"), () -> true);

        tool.execute(ARGS);

        assertEquals(List.of("ui"), events);
    }

    @Test
    public void offTheUiThreadTheSettlerRunsBeforeTheScan()
    {
        List<String> events = new ArrayList<>();
        FindReferencesTool tool = new FindReferencesTool((name, budget) -> {
            events.add("settle:" + name + ":" + budget);
            return null;
        }, runnable -> events.add("ui"), () -> false);

        tool.execute(ARGS);

        assertEquals(List.of("settle:P:10000", "ui"), events);
    }

    /**
     * The public constructor must wire the real UI-thread check: on a fresh thread (which owns no
     * display) it answers false, and on any thread it agrees with {@link Display#getCurrent()}.
     */
    @Test
    public void thePublicConstructorAsksSwtWhetherTheCallerIsTheUiThread() throws Exception
    {
        FindReferencesTool tool = new FindReferencesTool();
        AtomicReference<Boolean> onFreshThread = new AtomicReference<>();
        AtomicReference<Boolean> expectedOnFreshThread = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            expectedOnFreshThread.set(currentDisplayOrNull() != null);
            onFreshThread.set(tool.onUiThread().getAsBoolean());
        }, "find-references-ui-thread-probe"); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();
        worker.join(10_000);

        assertFalse("the probe must not block", worker.isAlive());
        assertEquals(Boolean.FALSE, expectedOnFreshThread.get());
        assertEquals("a thread that owns no display is not the UI thread", Boolean.FALSE, onFreshThread.get());
        assertEquals(currentDisplayOrNull() != null, tool.onUiThread().getAsBoolean());
    }

    private static Display currentDisplayOrNull()
    {
        try
        {
            return Display.getCurrent();
        }
        catch (LinkageError e)
        {
            // SWT natives are not loadable here - then no thread can be the UI thread.
            return null;
        }
    }
}
