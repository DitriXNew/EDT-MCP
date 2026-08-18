/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.WorkmateGateway.GatewayException;

/**
 * A name Workmate does not know is REJECTED, not run.
 *
 * <p>Its dispatch loop looks the name up first and puts an unknown one aside in
 * {@code unknownCalls}, completing normally with no messages. Told apart from a tool that ran
 * and answered badly, that is the difference between "fix the name and call again" and "look at
 * the project before repeating" - and the second is a false alarm when nothing was entered.
 */
public class WorkmateUnknownToolTest
{
    @Test
    public void testARejectedNameIsRetryableAndSaysWhatToFix()
    {
        Result result = new Result();
        result.unknownCalls = new ArrayList<>(Collections.singletonList("NoSuchTool")); //$NON-NLS-1$

        try
        {
            WorkmateGateway.rejectUnknownTool(result, "NoSuchTool"); //$NON-NLS-1$
            fail("an unknown tool name must not be reported as a successful call");
        }
        catch (GatewayException e)
        {
            assertTrue("the name is what the caller must fix",
                e.getDetail().contains("NoSuchTool"));
            assertTrue("and it must be clear that nothing ran",
                e.getDetail().contains("without running anything"));
            assertTrue("a name is not a dispatch: this one IS safe to repeat",
                e.getKind() == WorkmateGateway.FailureKind.UNKNOWN_TOOL);
        }
    }

    @Test
    public void testAToolThatRanIsNotTreatedAsUnknown() throws Exception
    {
        Result result = new Result();
        result.unknownCalls = new ArrayList<>();

        WorkmateGateway.rejectUnknownTool(result, "JShellSession"); //$NON-NLS-1$
    }

    @Test
    public void testAWorkmateWithoutThatFieldYieldsNoVerdict() throws Exception
    {
        // Tolerance, not silence-by-accident: an older build simply gives no evidence here, and
        // the result is then read the ordinary way rather than failing on a missing field.
        WorkmateGateway.rejectUnknownTool(new Object(), "JShellSession"); //$NON-NLS-1$
        WorkmateGateway.rejectUnknownTool(null, "JShellSession"); //$NON-NLS-1$
    }

    /** Stands in for Workmate's {@code McpCallToolsResult}: read reflectively, by field name. */
    public static final class Result
    {
        public List<Object> messages = new ArrayList<>();

        public List<Object> unknownCalls = new ArrayList<>();
    }
}
