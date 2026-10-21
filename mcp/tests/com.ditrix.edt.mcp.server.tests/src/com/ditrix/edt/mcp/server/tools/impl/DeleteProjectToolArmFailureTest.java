/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Distinguishes preparation failure from a deletion whose outcome is unknown. */
public class DeleteProjectToolArmFailureTest
{
    @Test
    public void testAFailedArmReportsNoMutationAndDoesNotDelete()
    {
        for (boolean exists : new boolean[] {true, false})
        {
            IProject project = mock(IProject.class);
            when(project.exists()).thenReturn(exists);
            AtomicBoolean deleted = new AtomicBoolean();
            JsonObject result = JsonParser.parseString(DeleteProjectTool.deleteProject(project, "Extension", true,
                () -> { throw new IllegalStateException("listener unavailable"); },
                () -> deleted.set(true))).getAsJsonObject();
            assertFalse(result.get("success").getAsBoolean());
            assertFalse("preparation cannot commit deletion", deleted.get());
            assertFalse("no deletion was attempted", result.has("mutationOutcomeUnknown"));
            assertFalse("no deletion was attempted", result.has("mutationCommitted"));
            String error = result.get("error").getAsString();
            assertTrue(error.contains("Extension"));
            assertTrue(error.contains("listener unavailable"));
            assertTrue(error.contains("Nothing was deleted"));
            assertTrue(error.contains("retry delete_project"));
        }
    }

    @Test
    public void testTheReleaseWarningReportsTheActualReleaseWaitBound()
    {
        JsonObject result = JsonParser.parseString(
            DeleteProjectTool.deleteOutcome("Extension", false, false, true)).getAsJsonObject();
        String duration = TimeUnit.MILLISECONDS.toSeconds(DeleteProjectTool.RELEASE_TIMEOUT_MS) + " s";
        assertTrue("the warning must follow the release bound when that constant changes",
            result.get("message").getAsString().contains("after " + duration + " -"));
    }
}
