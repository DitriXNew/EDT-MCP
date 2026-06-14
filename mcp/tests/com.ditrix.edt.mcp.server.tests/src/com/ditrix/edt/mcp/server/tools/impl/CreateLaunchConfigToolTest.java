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
 * Tests for {@link CreateLaunchConfigTool}.
 *
 * <p>Covers tool metadata, input/output schema declarations, enum validation
 * (clientType), the required-argument guard (projectName), and the guide.
 * The actual {@code ILaunchConfigurationType.newInstance} + {@code doSave} path requires
 * a live {@link org.eclipse.debug.core.DebugPlugin} and is therefore e2e-only
 * (see {@code tests/e2e/tools/test_create_launch_config.py}).
 */
public class CreateLaunchConfigToolTest
{
    @Test
    public void testName()
    {
        assertEquals("create_launch_config", new CreateLaunchConfigTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateLaunchConfigTool.NAME, new CreateLaunchConfigTool().getName());
    }

    @Test
    public void testResponseTypeIsJson()
    {
        assertEquals(ResponseType.JSON, new CreateLaunchConfigTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new CreateLaunchConfigTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_launch_config')")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsRunAndDebug()
    {
        String desc = new CreateLaunchConfigTool().getDescription();
        // The key fact that run and debug share the same config type must be surfaced.
        assertTrue("description must mention both run and debug", //$NON-NLS-1$
            desc.toLowerCase().contains("run") && desc.toLowerCase().contains("debug")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaDeclaresAllParameters()
    {
        String schema = new CreateLaunchConfigTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare projectName", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare clientType", schema.contains("\"clientType\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare name", schema.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare applicationId", schema.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaRequiredContainsProjectNameOnly()
    {
        String schema = new CreateLaunchConfigTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // Optional parameters must NOT be in the required array.
        assertTrue("clientType must NOT be required", !tail.contains("\"clientType\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("name must NOT be required", !tail.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("applicationId must NOT be required", !tail.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaClientTypeEnumValues()
    {
        String schema = new CreateLaunchConfigTool().getInputSchema();
        assertTrue("schema must list 'thin' in enum", schema.contains("\"thin\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must list 'thick' in enum", schema.contains("\"thick\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must list 'web' in enum", schema.contains("\"web\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresResultFields()
    {
        String schema = new CreateLaunchConfigTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare name", schema.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare project", schema.contains("\"project\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare clientType", schema.contains("\"clientType\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare applicationId", schema.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare type", schema.contains("\"type\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare message", schema.contains("\"message\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideIsNonEmpty()
    {
        String guide = new CreateLaunchConfigTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must be non-empty", guide.length() > 0); //$NON-NLS-1$
        assertTrue("guide must mention applicationId", guide.contains("applicationId")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must mention clientType", guide.contains("clientType")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Argument validation (returns before any workspace access)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    public void testMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        String result = new CreateLaunchConfigTool().execute(params);
        assertTrue("missing projectName must error", result.contains("projectName is required")); //$NON-NLS-1$ //$NON-NLS-2$
        // The shared required-arg guard steers to list_projects.
        assertTrue("error must suggest list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidClientTypeIsError()
    {
        // clientType is validated at the top of execute() (before any project/manager lookup),
        // so an invalid value is rejected headlessly with an actionable error naming the
        // allowed kinds. This genuinely exercises the clientType validation branch.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("clientType", "mobile"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateLaunchConfigTool().execute(params);
        assertNotNull("execute must return a non-null result", result); //$NON-NLS-1$
        assertTrue("invalid clientType must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the bad value", result.contains("mobile")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must list the allowed kinds", //$NON-NLS-1$
            result.contains("thin") && result.contains("thick") && result.contains("web")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal constant sanity (verified against R-B javap table)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    public void testComponentTypeConstants()
    {
        assertEquals("com._1c.g5.v8.dt.platform.services.core.componentTypes.ThinClient", //$NON-NLS-1$
            CreateLaunchConfigTool.COMPONENT_TYPE_THIN);
        assertEquals("com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient", //$NON-NLS-1$
            CreateLaunchConfigTool.COMPONENT_TYPE_THICK);
        assertEquals("com._1c.g5.v8.dt.platform.services.core.componentTypes.WebClient", //$NON-NLS-1$
            CreateLaunchConfigTool.COMPONENT_TYPE_WEB);
    }

    @Test
    public void testAttrClientTypeConstant()
    {
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_CLIENT_TYPE", //$NON-NLS-1$
            CreateLaunchConfigTool.ATTR_CLIENT_TYPE);
    }

    @Test
    public void testAttrClientAutoSelectConstant()
    {
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_CLIENT_AUTO_SELECT", //$NON-NLS-1$
            CreateLaunchConfigTool.ATTR_CLIENT_AUTO_SELECT);
    }

    @Test
    public void testProcessFactoryConstants()
    {
        assertEquals("process_factory_id", CreateLaunchConfigTool.ATTR_PROCESS_FACTORY_ID); //$NON-NLS-1$
        assertEquals("com._1c.g5.v8.dt.debug.core.RuntimeProcessFactory", //$NON-NLS-1$
            CreateLaunchConfigTool.VALUE_PROCESS_FACTORY);
    }
}
