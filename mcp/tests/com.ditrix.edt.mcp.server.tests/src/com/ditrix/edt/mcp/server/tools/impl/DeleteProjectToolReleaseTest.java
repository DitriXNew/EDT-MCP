/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.DeleteProjectTool.IReleaseWait;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Pins the whole deletion sequence rather than only a formatted result. */
public class DeleteProjectToolReleaseTest
{
    /** A recording fake keeps this package-private seam compatible with the OSGi test loader. */
    private static final class Wait implements IReleaseWait
    {
        boolean released;
        RuntimeException failure;
        int awaits;
        int cleanups;
        long timeout;

        @Override public boolean await(long timeoutMs)
        {
            awaits++;
            timeout = timeoutMs;
            if (failure != null)
            {
                throw failure;
            }
            return released;
        }

        @Override public void cleanup()
        {
            cleanups++;
        }
    }

    private static JsonObject parse(String result)
    {
        return JsonParser.parseString(result).getAsJsonObject();
    }

    @Test
    public void testDeletedIsReportedOnlyWhenEdtReleasedTheProject()
    {
        Wait waiter = new Wait();
        waiter.released = true;
        JsonObject result = parse(DeleteProjectTool.deleteProject(mock(IProject.class), "Extension", true,
            () -> waiter, () -> {}));
        assertTrue(result.get("success").getAsBoolean());
        assertEquals("deleted", result.get("action").getAsString());
        assertEquals("Extension", result.get("project").getAsString());
        assertTrue(result.get("deleteContent").getAsBoolean());
        assertEquals("released", result.get("state").getAsString());
        assertTrue(result.get("message").getAsString().contains("removed from the workspace"));
        assertEquals(1, waiter.awaits);
        assertEquals(30_000L, waiter.timeout);
        assertEquals(1, waiter.cleanups);
    }

    @Test
    public void testAProjectEdtStillHoldsIsReportedAsReleasingNotRemoved()
    {
        Wait waiter = new Wait();
        JsonObject result = parse(DeleteProjectTool.deleteProject(mock(IProject.class), "Extension", false,
            () -> waiter, () -> {}));
        assertTrue(result.get("success").getAsBoolean());
        assertEquals("deleted", result.get("action").getAsString());
        assertEquals("releasing", result.get("state").getAsString());
        String message = result.get("message").getAsString();
        assertTrue(message.contains("Extension"));
        assertTrue(message.contains("files were kept"));
        assertTrue(message.contains("still releasing its project context after 30 s"));
        assertTrue(message.contains("clean_project"));
        assertTrue(message.contains("create_project"));
        assertFalse(message.contains("removed from the workspace"));
        assertTrue(message.contains("absence there does not prove context release"));
        assertTrue(message.contains("restart EDT before retrying"));
        assertEquals(1, waiter.awaits);
        assertEquals(30_000L, waiter.timeout);
    }

    @Test
    public void testTheReleaseWaitIsArmedBeforeTheDeleteAndCleanedUpAfter()
    {
        List<String> order = new ArrayList<>();
        IReleaseWait waiter = new IReleaseWait()
        {
            public boolean await(long timeout)
            {
                order.add("await");
                assertEquals(30_000, timeout);
                return true;
            }
            public void cleanup()
            {
                order.add("cleanup");
            }
        };
        Supplier<IReleaseWait> prepare = () -> {
            order.add("prepare");
            return waiter;
        };
        DeleteProjectTool.deleteProject(mock(IProject.class), "Extension", true,
            prepare, () -> order.add("delete"));
        assertEquals(List.of("prepare", "delete", "await", "cleanup"), order);
    }

    @Test
    public void testUnavailableLifecycleServicesAreReportedAsUnverifiedNotReleased()
    {
        JsonObject result = parse(DeleteProjectTool.deleteProject(mock(IProject.class), "Extension", true,
            () -> null, () -> {}));
        assertTrue(result.get("success").getAsBoolean());
        assertEquals("unverified", result.get("state").getAsString());
        assertTrue(result.get("message").getAsString().contains("lifecycle services are unavailable"));
        assertTrue(result.get("message").getAsString().contains("absence there does not prove context release"));
        assertFalse(result.get("message").getAsString().contains("removed from the workspace"));
    }

    @Test
    public void testADeleteFailureStillCleansUpAndPreservesTheKnownMutationOutcome()
    {
        for (boolean exists : new boolean[] {false, true})
        {
            IProject project = mock(IProject.class);
            when(project.exists()).thenReturn(exists);
            Wait waiter = new Wait();
            JsonObject result = parse(DeleteProjectTool.deleteProject(project, "Extension", true,
                () -> waiter, () -> { throw new IllegalStateException("delete failed"); }));
            assertFalse(result.get("success").getAsBoolean());
            assertTrue(result.get(exists ? "mutationOutcomeUnknown" : "mutationCommitted").getAsBoolean());
            assertTrue(result.get("error").getAsString().contains("Extension"));
            assertEquals(0, waiter.awaits);
            assertEquals(1, waiter.cleanups);
        }
    }

    @Test
    public void testAWaitFailureStillCleansUpAfterTheResourceWasDeleted()
    {
        Wait waiter = new Wait();
        waiter.failure = new IllegalStateException("wait failed");
        JsonObject result = parse(DeleteProjectTool.deleteProject(mock(IProject.class), "Extension", false,
            () -> waiter, () -> {}));
        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("mutationCommitted").getAsBoolean());
        assertEquals(1, waiter.cleanups);
    }

    @Test
    public void testOutputSchemaDeclaresStateAndGuideExplainsWhatDeletedMeans()
    {
        DeleteProjectTool tool = new DeleteProjectTool();
        assertTrue(parse(tool.getOutputSchema()).getAsJsonObject("properties").has("state"));
        assertFalse(parse(tool.getInputSchema()).getAsJsonObject("properties").has("timeout"));
        assertTrue(tool.getGuide().contains("releasing"));
        assertTrue(tool.getGuide().contains("unverified"));
        assertTrue(tool.getGuide().contains("30 s"));
        assertTrue(tool.getGuide().contains("clean_project"));
        assertTrue(tool.getGuide().contains("absence does not prove context release"));
    }
}
