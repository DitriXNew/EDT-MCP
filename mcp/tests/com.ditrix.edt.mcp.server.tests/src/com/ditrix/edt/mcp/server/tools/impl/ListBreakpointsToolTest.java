/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link ListBreakpointsTool}.
 * <p>
 * This tool performs no argument validation: {@code projectName} is optional
 * and the first action in {@code execute()} is a live {@code DebugPlugin}
 * lookup. DTO construction is tested through package-visible helpers; manager
 * enumeration and project-filter behaviour are covered by the E2E suite.
 */
public class ListBreakpointsToolTest
{
    public enum TestHitCondition
    {
        EQUALS,
        MULTIPLIER
    }

    public interface ExtendedLineConfiguration
    {
        String getCondition();

        int getHitCount();

        TestHitCondition getHitCondition();
    }

    @Test
    public void testName()
    {
        assertEquals("list_breakpoints", new ListBreakpointsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ListBreakpointsTool.NAME, new ListBreakpointsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new ListBreakpointsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ListBreakpointsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new ListBreakpointsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testConfiguredLineFieldsAppear()
        throws Exception
    {
        ILineBreakpoint breakpoint = configuredBreakpoint("A > 0", 4, TestHitCondition.MULTIPLIER); //$NON-NLS-1$
        IMarker marker = marker();
        when(breakpoint.getMarker()).thenReturn(marker);
        Map<String, Object> dto = ListBreakpointsTool.createLineBreakpointDto(
            breakpoint, marker, project());

        assertEquals("A > 0", dto.get("condition")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(4), dto.get("hitCount")); //$NON-NLS-1$
        assertEquals("MULTIPLIER", dto.get("hitCondition")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnsetLineFieldsAreOmitted()
        throws Exception
    {
        ILineBreakpoint breakpoint = configuredBreakpoint("", -1, TestHitCondition.EQUALS); //$NON-NLS-1$
        IMarker marker = marker();
        when(breakpoint.getMarker()).thenReturn(marker);
        Map<String, Object> dto = ListBreakpointsTool.createLineBreakpointDto(
            breakpoint, marker, project());

        assertFalse(dto.containsKey("condition")); //$NON-NLS-1$
        assertFalse(dto.containsKey("hitCount")); //$NON-NLS-1$
        assertFalse(dto.containsKey("hitCondition")); //$NON-NLS-1$
    }

    @Test
    public void testExceptionBreakpointIsLabelledWorkspaceWide()
        throws Exception
    {
        IBreakpoint breakpoint = mock(IBreakpoint.class);
        when(breakpoint.isEnabled()).thenReturn(true);
        when(breakpoint.getModelIdentifier()).thenReturn("com._1c.g5.v8.dt.debug"); //$NON-NLS-1$
        IMarker marker = marker();
        // The EXACT platform attribute ids, pinned here on purpose: the flag is stored under
        // 'allExceptions' and not under the setter's name, and reading it by suffix would also
        // answer with a foreign 'vendor.saved.exceptionMessage'.
        when(marker.exists()).thenReturn(Boolean.TRUE);
        when(marker.getAttribute("com._1c.g5.v8.dt.debug.core.allExceptions")) //$NON-NLS-1$
            .thenReturn(Boolean.FALSE);
        when(marker.getAttribute("com._1c.g5.v8.dt.debug.core.exceptionMessage")) //$NON-NLS-1$
            .thenReturn("Expected failure"); //$NON-NLS-1$

        Map<String, Object> dto = ListBreakpointsTool.createExceptionBreakpointDto(breakpoint, marker);

        assertEquals("exception", dto.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, dto.get("workspaceWide")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, dto.get("catchAllExceptions")); //$NON-NLS-1$
        assertEquals("Expected failure", dto.get("exceptionMessage")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, dto.get("enabled")); //$NON-NLS-1$
    }

    @Test
    public void testCatchAllExceptionBreakpointOmitsTheMessageInsteadOfPrintingItEmpty()
        throws Exception
    {
        IBreakpoint breakpoint = mock(IBreakpoint.class);
        IMarker marker = marker();
        when(marker.exists()).thenReturn(Boolean.TRUE);
        when(marker.getAttribute("com._1c.g5.v8.dt.debug.core.allExceptions")) //$NON-NLS-1$
            .thenReturn(Boolean.TRUE);

        Map<String, Object> dto = ListBreakpointsTool.createExceptionBreakpointDto(breakpoint, marker);

        assertEquals(Boolean.TRUE, dto.get("catchAllExceptions")); //$NON-NLS-1$
        // Absent, not "": an empty string reads as "there IS a filter and it matches nothing",
        // which is the opposite of catch-all. Unset fields are left out, as for line breakpoints.
        assertFalse(dto.containsKey("exceptionMessage")); //$NON-NLS-1$
    }

    private static ILineBreakpoint configuredBreakpoint(String condition, int hitCount,
        TestHitCondition hitCondition) throws Exception
    {
        ILineBreakpoint breakpoint = mock(ILineBreakpoint.class,
            withSettings().extraInterfaces(ExtendedLineConfiguration.class));
        ExtendedLineConfiguration configuration = (ExtendedLineConfiguration)breakpoint;
        when(configuration.getCondition()).thenReturn(condition);
        when(configuration.getHitCount()).thenReturn(hitCount);
        when(configuration.getHitCondition()).thenReturn(hitCondition);
        when(breakpoint.getLineNumber()).thenReturn(2);
        when(breakpoint.isEnabled()).thenReturn(true);
        when(breakpoint.getModelIdentifier()).thenReturn("com._1c.g5.v8.dt.debug"); //$NON-NLS-1$
        return breakpoint;
    }

    private static IMarker marker()
    {
        IMarker marker = mock(IMarker.class);
        IResource resource = mock(IResource.class);
        when(marker.getId()).thenReturn(42L);
        when(marker.getResource()).thenReturn(resource);
        when(resource.getFullPath()).thenReturn(new Path("/TestConfiguration/src/CommonModules/Calc/Module.bsl")); //$NON-NLS-1$
        return marker;
    }

    private static IProject project()
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("TestConfiguration"); //$NON-NLS-1$
        return project;
    }
}
