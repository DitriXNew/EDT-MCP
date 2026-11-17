/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/** Tool contracts and detached configuration/extension rendering, including localized language codes. */
public class GetConfigurationPropertiesToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_configuration_properties", new GetConfigurationPropertiesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetConfigurationPropertiesTool.NAME, new GetConfigurationPropertiesTool().getName());
    }

    @Test
    public void testResponseTypeYaml()
    {
        // The tool emits a human-readable YAML body, so the response type is YAML
        // (the protocol maps it to a text/yaml resource that agrees with the .yaml
        // file name). Errors still travel as structured JSON via the protocol
        // diversion. A MARKDOWN type would mis-advertise the body as text/markdown.
        assertEquals(ResponseType.YAML, new GetConfigurationPropertiesTool().getResponseType());
    }

    @Test
    public void testResultFileNameIsYaml()
    {
        assertEquals("configuration-properties.yaml", //$NON-NLS-1$
            new GetConfigurationPropertiesTool().getResultFileName(new java.util.HashMap<>()));
    }

    @Test
    public void testResponseTypeAndFileNameAgreeOnYaml()
    {
        // The three metadata pieces must agree: a YAML response type, a .yaml
        // resource URI, and (via the protocol) a YAML mimeType.
        GetConfigurationPropertiesTool tool = new GetConfigurationPropertiesTool();
        assertEquals(ResponseType.YAML, tool.getResponseType());
        assertTrue(tool.getResultFileName(new java.util.HashMap<>()).endsWith(".yaml")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetConfigurationPropertiesTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetConfigurationPropertiesTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testProjectNameIsOptional()
    {
        // projectName is optional: absent => first configuration project. The schema
        // must therefore declare NO required array (or one without projectName).
        String schema = new GetConfigurationPropertiesTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        if (requiredIdx >= 0)
        {
            String tail = schema.substring(requiredIdx);
            assertFalse("projectName must NOT be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testResultFileNameIsStableAcrossParams()
    {
        // getResultFileName ignores its params (constant file name), so the same
        // .yaml name comes back regardless of what is passed.
        GetConfigurationPropertiesTool tool = new GetConfigurationPropertiesTool();
        java.util.Map<String, String> withName = new java.util.HashMap<>();
        withName.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("configuration-properties.yaml", tool.getResultFileName(withName)); //$NON-NLS-1$
        assertEquals(tool.getResultFileName(new java.util.HashMap<>()),
            tool.getResultFileName(withName));
    }

    @Test
    public void testGuideNotEmpty()
    {
        String guide = new GetConfigurationPropertiesTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
    }

    @Test
    public void renderedConfigurationUsesLanguageCodesAndPreservesLocalizedSynonyms() throws Exception
    {
        var config = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createConfiguration();
        var english = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createLanguage();
        english.setName("English"); english.setLanguageCode("en");
        var russian = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createLanguage();
        russian.setName("Russian"); russian.setLanguageCode("ru");
        config.getLanguages().add(english); config.getLanguages().add(russian);
        config.setDefaultLanguage(russian);
        config.setName("Sample");
        config.getSynonym().put("en", "Sample configuration");
        config.getSynonym().put("ru", "Тестовая конфигурация");
        var render = GetConfigurationPropertiesTool.class.getDeclaredMethod("appendConfigurationProperties",
            StringBuilder.class, com._1c.g5.v8.dt.metadata.mdclass.Configuration.class);
        render.setAccessible(true);
        StringBuilder yaml = new StringBuilder();
        render.invoke(null, yaml, config);
        String body = yaml.toString();
        assertTrue(body, body.contains("defaultLanguage: ru\n"));
        assertTrue(body, body.contains("defaultLanguageName: Russian\n"));
        assertTrue(body, body.contains("en: Sample configuration"));
        assertTrue(body, body.contains("ru: Тестовая конфигурация"));
        assertFalse(body, body.contains("Russian: Тестовая"));
    }

    @Test
    public void extensionRenderingReportsItsKindAndPrefix() throws Exception
    {
        var config = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createConfiguration();
        config.setNamePrefix("Ext_");
        var render = GetConfigurationPropertiesTool.class.getDeclaredMethod("appendExtensionProperties",
            StringBuilder.class, com._1c.g5.v8.dt.metadata.mdclass.Configuration.class);
        render.setAccessible(true);
        StringBuilder yaml = new StringBuilder();
        render.invoke(null, yaml, config);
        assertTrue(yaml.toString(), yaml.toString().contains("projectKind: Extension\n"));
        assertTrue(yaml.toString(), yaml.toString().contains("namePrefix: Ext_\n"));
    }
}
