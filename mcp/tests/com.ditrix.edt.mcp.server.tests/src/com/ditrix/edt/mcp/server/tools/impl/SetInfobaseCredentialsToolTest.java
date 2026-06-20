/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link SetInfobaseCredentialsTool}.
 * <p>
 * Covers tool metadata, schema parity, and the argument-validation guards that execute BEFORE any
 * workspace or platform-services access. The real store path (resolve application -&gt;
 * {@code IInfobaseApplication.getInfobase()} -&gt; {@code IInfobaseAccessManager.updateSettings})
 * needs a live EDT and is covered by the e2e suite.
 */
public class SetInfobaseCredentialsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_infobase_credentials", new SetInfobaseCredentialsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetInfobaseCredentialsTool.NAME, new SetInfobaseCredentialsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new SetInfobaseCredentialsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetInfobaseCredentialsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must mention the credentials purpose", //$NON-NLS-1$
            desc.toLowerCase().contains("credential")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresAllParameters()
    {
        String schema = new SetInfobaseCredentialsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare launchConfigurationName", //$NON-NLS-1$
            schema.contains("\"launchConfigurationName\"")); //$NON-NLS-1$
        assertTrue("schema must declare projectName", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare applicationId", schema.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare user", schema.contains("\"user\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare password", schema.contains("\"password\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare access", schema.contains("\"access\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAccessIsClosedEnum()
    {
        String schema = new SetInfobaseCredentialsTool().getInputSchema();
        assertTrue("access must be a closed enum", schema.contains("\"enum\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("access must advertise INFOBASE", schema.contains("\"INFOBASE\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("access must advertise OS", schema.contains("\"OS\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNoParameterIsRequired()
    {
        // Targeting is launchConfigurationName OR projectName+applicationId, so no single parameter
        // is statically required; user/password are optional too (empty = no-user credentials).
        String schema = new SetInfobaseCredentialsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        if (requiredIdx >= 0)
        {
            int open = schema.indexOf('[', requiredIdx);
            int close = schema.indexOf(']', open);
            if (open >= 0 && close > open)
            {
                String requiredBlock = schema.substring(open, close + 1);
                assertTrue("user must NOT be statically required", //$NON-NLS-1$
                    !requiredBlock.contains("\"user\"")); //$NON-NLS-1$
                assertTrue("projectName must NOT be statically required", //$NON-NLS-1$
                    !requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void testOutputSchemaDeclaresExpectedFields()
    {
        String schema = new SetInfobaseCredentialsTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare applicationId", schema.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare user", schema.contains("\"user\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare access", schema.contains("\"access\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare passwordSet", schema.contains("\"passwordSet\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingTargetIsError()
    {
        // No launchConfigurationName and no projectName -> projectName is named first.
        Map<String, String> params = new HashMap<>();
        params.put("user", "Admin"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetInfobaseCredentialsTool().execute(params);
        assertNotNull(result);
        assertTrue("missing target must name projectName", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingApplicationIdIsError()
    {
        // projectName given but no applicationId and no launchConfigurationName.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("user", "Admin"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetInfobaseCredentialsTool().execute(params);
        assertNotNull(result);
        assertTrue("missing applicationId must produce an error", //$NON-NLS-1$
            result.contains("applicationId is required")); //$NON-NLS-1$
    }
}
