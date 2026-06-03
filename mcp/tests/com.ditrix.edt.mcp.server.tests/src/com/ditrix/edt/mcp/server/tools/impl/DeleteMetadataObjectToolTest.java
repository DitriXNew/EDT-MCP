/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight tests for {@link DeleteMetadataObjectTool} that exercise tool
 * metadata and JSON schema without needing the Eclipse/EDT runtime. The
 * {@code execute()} path requires a live workbench and refactoring service, so
 * it is covered by the E2E suite instead.
 * <p>
 * {@link #testResponseType()} also guards the refactoring that moved
 * {@code getResponseType()} into {@link com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool}: the tool
 * must still report JSON.
 */
public class DeleteMetadataObjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("delete_metadata_object", new DeleteMetadataObjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DeleteMetadataObjectTool.NAME, new DeleteMetadataObjectTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new DeleteMetadataObjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new DeleteMetadataObjectTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    /**
     * The exhaustive detail (two-phase workflow, child types, bilingual notes)
     * moved out of the always-loaded description/schema into the on-demand
     * {@code getGuide()} channel. Guard that it is non-empty and still carries
     * the migrated specifics rather than having vanished.
     */
    @Test
    public void testGuideHasMigratedDetail()
    {
        String guide = new DeleteMetadataObjectTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must not be empty", guide.isEmpty()); //$NON-NLS-1$
        assertTrue("guide must explain the two-phase workflow", //$NON-NLS-1$
            guide.contains("Two-phase workflow")); //$NON-NLS-1$
        assertTrue("guide must list the supported child types", //$NON-NLS-1$
            guide.contains("TabularSection")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new DeleteMetadataObjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"confirm\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new DeleteMetadataObjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("objectFqn must be required", tail.contains("\"objectFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testConfirmNotRequired()
    {
        String schema = new DeleteMetadataObjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("confirm must not be required", tail.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A valid top-level FQN is {@code Type.Name} (2 parts). */
    @Test
    public void testFqnArityTopLevelAccepted()
    {
        assertTrue("Type.Name (2 parts) must be a valid arity", //$NON-NLS-1$
            DeleteMetadataObjectTool.isValidFqnArity(2));
    }

    /** A nested FQN adds complete pairs: {@code Type.Name.Sub.SubName} (4 parts). */
    @Test
    public void testFqnArityNestedPairAccepted()
    {
        assertTrue("Type.Name.Sub.SubName (4 parts) must be a valid arity", //$NON-NLS-1$
            DeleteMetadataObjectTool.isValidFqnArity(4));
        assertTrue("6 parts (two nested pairs) must be a valid arity", //$NON-NLS-1$
            DeleteMetadataObjectTool.isValidFqnArity(6));
    }

    /**
     * An odd trailing token after {@code Type.Name} is malformed and must be
     * rejected, so a nested delete never silently falls back to the parent.
     */
    @Test
    public void testFqnArityOddNestedRejected()
    {
        assertFalse("3 parts (dangling token) must be rejected", //$NON-NLS-1$
            DeleteMetadataObjectTool.isValidFqnArity(3));
        assertFalse("5 parts (dangling token) must be rejected", //$NON-NLS-1$
            DeleteMetadataObjectTool.isValidFqnArity(5));
    }

    /** Fewer than 2 parts is never a valid FQN. */
    @Test
    public void testFqnArityTooShortRejected()
    {
        assertFalse(DeleteMetadataObjectTool.isValidFqnArity(0));
        assertFalse(DeleteMetadataObjectTool.isValidFqnArity(1));
    }
}
