/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Tests for {@link GetCheckDescriptionTool}.
 * <p>
 * Covers tool metadata, the input schema, and the checkId required-argument
 * validation that returns before the configured-folder / preference-store
 * access. Reading the actual check document needs the configured docs folder and
 * is covered by the E2E suite. (Uses the {@code IMcpTool} default MARKDOWN
 * response type.)
 */
public class GetCheckDescriptionToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_check_description", new GetCheckDescriptionTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetCheckDescriptionTool.NAME, new GetCheckDescriptionTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetCheckDescriptionTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetCheckDescriptionTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetCheckDescriptionTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"checkId\"")); //$NON-NLS-1$
        // Optional projectName, declared so a short UID checkId can be resolved.
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testProjectNameIsOptional()
    {
        // Only checkId is required; projectName is opt-in (UID resolution only).
        String schema = new GetCheckDescriptionTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("checkId must be required", tail.contains("\"checkId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("projectName must NOT be required", //$NON-NLS-1$
            !tail.contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsUidResolution()
    {
        // The description must advertise that a short UID code is accepted (the
        // get_project_errors -> get_check_description chain depends on it).
        String desc = new GetCheckDescriptionTool().getDescription();
        assertTrue("description should mention the UID code", //$NON-NLS-1$
            desc.contains("UID")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no configured folder needed) ====================

    @Test
    public void testMissingCheckId()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetCheckDescriptionTool().execute(params);
        assertTrue(result.contains("checkId is required")); //$NON-NLS-1$
    }

    // ==================== UID -> symbolic resolution (pure, mocked repository) ====================

    @Test
    public void testResolveSymbolicCheckUidMapsUidToSymbolicId()
    {
        // The pure resolver mirrors GetProjectErrorsTool: a short UID is mapped to its
        // symbolic id via ICheckRepository.getUidForShortUid(...).getCheckId().
        IProject project = mock(IProject.class);
        CheckUid uid = mock(CheckUid.class);
        when(uid.getCheckId()).thenReturn("ql-temp-table-index"); //$NON-NLS-1$
        ICheckRepository repo = mock(ICheckRepository.class);
        when(repo.getUidForShortUid("SU23", project)).thenReturn(uid); //$NON-NLS-1$

        assertEquals("ql-temp-table-index", //$NON-NLS-1$
            GetCheckDescriptionTool.resolveSymbolicCheckUid("SU23", project, repo)); //$NON-NLS-1$
    }

    @Test
    public void testResolveSymbolicCheckUidReturnsNullWhenUnresolved()
    {
        IProject project = mock(IProject.class);
        ICheckRepository repo = mock(ICheckRepository.class);
        when(repo.getUidForShortUid("NOPE", project)).thenReturn(null); //$NON-NLS-1$
        assertNull(GetCheckDescriptionTool.resolveSymbolicCheckUid("NOPE", project, repo)); //$NON-NLS-1$
    }

    @Test
    public void testResolveSymbolicCheckUidIsNullSafe()
    {
        IProject project = mock(IProject.class);
        ICheckRepository repo = mock(ICheckRepository.class);
        // Any missing input yields null (never throws), so callers fall back cleanly.
        assertNull(GetCheckDescriptionTool.resolveSymbolicCheckUid(null, project, repo));
        assertNull(GetCheckDescriptionTool.resolveSymbolicCheckUid("", project, repo)); //$NON-NLS-1$
        assertNull(GetCheckDescriptionTool.resolveSymbolicCheckUid("SU23", null, repo)); //$NON-NLS-1$
        assertNull(GetCheckDescriptionTool.resolveSymbolicCheckUid("SU23", project, null)); //$NON-NLS-1$
    }
}
