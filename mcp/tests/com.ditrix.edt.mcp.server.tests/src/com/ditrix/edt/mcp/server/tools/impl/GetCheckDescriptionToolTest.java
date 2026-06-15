/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * Covers tool metadata, the input schema, the {@code getResultFileName} naming
 * helper, the {@code checkId} required-argument guard, the pure UID -> symbolic
 * resolver, and the {@code hasCheckDocumentation} lookup. The headless boundary
 * is {@code Activator.getDefault().getPreferenceStore()} (the configured
 * check-descriptions folder): in the unit-test runtime the Activator is not
 * started, so any document-reading path degrades to an error payload /
 * {@code false} rather than returning content. {@code findCheckDocumentationFile}
 * swallows that and yields {@code null}, so {@code hasCheckDocumentation} is
 * headless-safe. Reading an actual check document needs the configured docs
 * folder and is covered by the E2E suite. (Uses the {@code IMcpTool} default
 * MARKDOWN response type.)
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

    // ==================== getResultFileName (pure, no folder/Activator) ====================

    @Test
    public void testResultFileNameUsesCheckId()
    {
        // A supplied checkId names the result file <checkId>.md.
        Map<String, String> params = new HashMap<>();
        params.put("checkId", "begin-transaction"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("begin-transaction.md", //$NON-NLS-1$
            new GetCheckDescriptionTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameFallsBackToToolName()
    {
        // No checkId -> the file name falls back to "<toolName>.md".
        assertEquals("get_check_description.md", //$NON-NLS-1$
            new GetCheckDescriptionTool().getResultFileName(new HashMap<>()));
    }

    @Test
    public void testResultFileNameEmptyCheckIdFallsBackToToolName()
    {
        // A blank checkId is treated as absent -> fall back to the tool name.
        Map<String, String> params = new HashMap<>();
        params.put("checkId", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("get_check_description.md", //$NON-NLS-1$
            new GetCheckDescriptionTool().getResultFileName(params));
    }

    // ==================== checkId validation (no configured folder needed) ====================

    @Test
    public void testMissingCheckId()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetCheckDescriptionTool().execute(params);
        assertTrue(result.contains("checkId is required")); //$NON-NLS-1$
    }

    @Test
    public void testStaticGetCheckDescriptionNullCheckIdIsError()
    {
        // The static entry point shares the same required-checkId guard, which
        // returns BEFORE any preference-store / folder access.
        String result = GetCheckDescriptionTool.getCheckDescription(null);
        assertTrue("null checkId must produce the checkId-required error", //$NON-NLS-1$
            result.contains("checkId is required")); //$NON-NLS-1$
    }

    @Test
    public void testStaticGetCheckDescriptionEmptyCheckIdIsError()
    {
        String result = GetCheckDescriptionTool.getCheckDescription(""); //$NON-NLS-1$
        assertTrue("empty checkId must produce the checkId-required error", //$NON-NLS-1$
            result.contains("checkId is required")); //$NON-NLS-1$
    }

    @Test
    public void testStaticGetCheckDescriptionTwoArgEmptyCheckIdIsError()
    {
        // The projectName-aware overload applies the same guard first.
        String result = GetCheckDescriptionTool.getCheckDescription("", "SomeProject"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("empty checkId must produce the checkId-required error", //$NON-NLS-1$
            result.contains("checkId is required")); //$NON-NLS-1$
    }

    @Test
    public void testGetCheckDescriptionForUnknownCheckIsError()
    {
        // A non-empty but unknown checkId can never resolve to a document
        // (no configured folder in the unit-test runtime, and even if one were
        // configured this id has no .md file): the tool yields an error payload,
        // never content. A unique synthetic id keeps this independent of any
        // packaged or configured check docs.
        String result = GetCheckDescriptionTool.getCheckDescription("no-such-check-xyz-unit"); //$NON-NLS-1$
        assertNotNull(result);
        assertTrue("an unresolvable checkId must yield a structured error payload", //$NON-NLS-1$
            result.contains("\"success\":false") || result.contains("\"success\": false")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== hasCheckDocumentation (headless-safe lookup) ====================

    @Test
    public void testHasCheckDocumentationNullCheckIdIsFalse()
    {
        // A null/empty checkId short-circuits to false before any folder access.
        assertFalse(GetCheckDescriptionTool.hasCheckDocumentation(null));
        assertFalse(GetCheckDescriptionTool.hasCheckDocumentation("")); //$NON-NLS-1$
    }

    @Test
    public void testHasCheckDocumentationForUnknownCheckIsFalse()
    {
        // findCheckDocumentationFile swallows the missing-folder / absent-Activator
        // condition and returns null, so the public probe is a safe false. A unique
        // synthetic id also guarantees no packaged/configured doc can match.
        assertFalse(GetCheckDescriptionTool.hasCheckDocumentation("no-such-check-xyz-unit")); //$NON-NLS-1$
    }

    @Test
    public void testHasCheckDocumentationPathTraversalIsFalse()
    {
        // A checkId carrying path-traversal characters must never resolve a file;
        // the lookup is null-safe and returns false (no exception).
        assertFalse(GetCheckDescriptionTool.hasCheckDocumentation("../../etc/passwd")); //$NON-NLS-1$
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
