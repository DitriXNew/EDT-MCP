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
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('set_infobase_credentials')")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidAccessIsError()
    {
        // An out-of-enum access value is rejected before any service lookup (headless-safe),
        // naming the bad value and the allowed kinds — the schema enum is advisory for clients.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", "someApp"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("access", "OOPS"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetInfobaseCredentialsTool().execute(params);
        assertNotNull(result);
        assertTrue("invalid access must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the bad value", result.contains("OOPS")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must list allowed kinds", //$NON-NLS-1$
            result.contains("INFOBASE") && result.contains("OS")); //$NON-NLS-1$ //$NON-NLS-2$
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

    // ==================== Pure storeOutcome seam (no live EDT, no jobs framework) ====================

    /** A representative SUCCESS JSON the bounded Job records the instant updateSettings commits. */
    private static final String SUCCESS_JSON =
        "{\"success\":true,\"project\":\"TestProject\",\"applicationId\":\"app1\"," //$NON-NLS-1$
            + "\"applicationName\":\"My Infobase\",\"user\":\"Admin\",\"access\":\"INFOBASE\"," //$NON-NLS-1$
            + "\"passwordSet\":true,\"message\":\"Stored ...\"}"; //$NON-NLS-1$

    @Test
    public void testStoreOutcomeFinishedReturnsRecordedSuccess()
    {
        // A clean finish returns whatever the Job recorded, verbatim.
        String result = SetInfobaseCredentialsTool.storeOutcome(true, SUCCESS_JSON, "TestProject", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(SUCCESS_JSON, result);
    }

    @Test
    public void testStoreOutcomeTimeoutWithRecordedSuccessReturnsSuccess()
    {
        // Persist-first guarantee: a timeout AFTER updateSettings committed still reports success.
        String result = SetInfobaseCredentialsTool.storeOutcome(false, SUCCESS_JSON, "TestProject", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a persisted success must survive a post-commit timeout", SUCCESS_JSON, result); //$NON-NLS-1$
    }

    @Test
    public void testStoreOutcomeTimeoutWithNoResultIsGracefulError()
    {
        // A timeout BEFORE the credentials committed yields a graceful, actionable error.
        String result = SetInfobaseCredentialsTool.storeOutcome(false, null, "TestProject", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(result);
        assertTrue("timeout-with-no-result must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must say it timed out", result.contains("timed out")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the application", result.contains("app1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the project", result.contains("TestProject")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStoreOutcomeFinishedWithNoResultIsGracefulError()
    {
        // A clean finish that recorded nothing must not hang or NPE — graceful "no result" error.
        String result = SetInfobaseCredentialsTool.storeOutcome(true, null, "TestProject", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(result);
        assertTrue("no-result finish must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must explain no result was produced", result.contains("no result")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStoreOutcomeKeepsLowerCamelCaseOutputKeys()
    {
        // The success branch (recorded JSON returned verbatim) preserves the lowerCamelCase wire keys.
        String result = SetInfobaseCredentialsTool.storeOutcome(true, SUCCESS_JSON, "TestProject", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must keep applicationId", result.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must keep applicationName", result.contains("\"applicationName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must keep passwordSet", result.contains("\"passwordSet\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
