/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EReference;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.google.gson.JsonObject;

/** Creation regressions for #730, isolated from the shared tool test class. */
public class CreateMetadataTextTemplateTest
{
    @Test public void testCreateTextDocumentEnglish() throws Exception
    {
        assertCreatedType("TextDocument", TemplateType.TEXT_DOCUMENT);
    }

    @Test public void testCreateTextDocumentRussian() throws Exception
    {
        assertCreatedType("\u0422\u0435\u043a\u0441\u0442\u043e\u0432\u044b\u0439\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442",
            TemplateType.TEXT_DOCUMENT);
    }

    @Test public void testCreateSpreadsheetDocumentEnglish() throws Exception
    {
        assertCreatedType("SpreadsheetDocument", TemplateType.SPREADSHEET_DOCUMENT);
    }

    @Test public void testCreateSpreadsheetDocumentRussian() throws Exception
    {
        assertCreatedType("\u0422\u0430\u0431\u043b\u0438\u0447\u043d\u044b\u0439\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442",
            TemplateType.SPREADSHEET_DOCUMENT);
    }

    @Test public void testCreateDataCompositionSchemaRefused() throws Exception
    {
        assertRefused("DataCompositionSchema");
    }

    @Test public void testCreateBinaryDataRefused() throws Exception
    {
        assertRefused("BinaryData");
    }

    @Test public void testCreateUnknownTemplateTypeRefused() throws Exception
    {
        assertRefused("NoSuchTemplateType730");
    }

    @Test public void testCreateSchemaExplainsTemplateType()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        assertTrue(schema, schema.contains("templateType"));
        assertTrue(schema, schema.contains("TextDocument"));
    }

    @Test public void testDetailsHashIsAcceptedByWriteModuleSourceGuard() throws Exception
    {
        String text = "same module and template\r\n\u0414\u0430\u043d\u043d\u044b\u0435\n";
        String details = (String)Class.forName("com.ditrix.edt.mcp.server.utils.TemplateContentSupport")
            .getMethod("renderText", String.class, String.class).invoke(null, "CommonTemplate.Contract", text);
        var match = java.util.regex.Pattern.compile("(?m)^contentHash: [\"']?([0-9a-f]{16})[\"']?$")
            .matcher(details);
        assertTrue(details, match.find());
        assertNull(WriteModuleSourceTool.evaluateExpectedHash(text, match.group(1), true));
    }

    @Test public void testModifyTemplateSchemaDescribesTextAndExpectedHash()
    {
        JsonObject schema = com.google.gson.JsonParser.parseString(new ModifyMetadataTool().getInputSchema())
            .getAsJsonObject();
        String description = schema.getAsJsonObject("properties").getAsJsonObject("template")
            .get("description").getAsString();
        assertTrue(description, description.contains("TextDocument"));
        assertTrue(description, description.contains("expectedHash"));
        assertTrue(description, description.contains("get_metadata_details"));
    }

    @Test public void testTextWriteReachesBmBoundaryWithoutFqnGenerator() throws Exception
    {
        assertGeneratorRequirement(TemplateType.TEXT_DOCUMENT, false);
    }

    @Test public void testSpreadsheetWriteStillRequiresFqnGenerator() throws Exception
    {
        assertGeneratorRequirement(TemplateType.SPREADSHEET_DOCUMENT, true);
    }

    private static void assertGeneratorRequirement(TemplateType type, boolean required) throws Exception
    {
        IProject project = mock(IProject.class);
        Activator activator = mock(Activator.class);
        IBmModelManager manager = mock(IBmModelManager.class);
        IBmModel model = mock(IBmModel.class);
        when(activator.getBmModelManager()).thenReturn(manager);
        when(manager.getModel(project)).thenReturn(model);
        BasicTemplate template = mock(BasicTemplate.class, withSettings().extraInterfaces(IBmObject.class));
        when(template.getTemplateType()).thenReturn(type);
        IBmObject bm = (IBmObject)template;
        when(bm.bmIsTop()).thenReturn(true);
        when(bm.bmGetFqn()).thenReturn("CommonTemplate.Contract730");

        // Stop at the real BM boundary: this unit test needs no file service or running EDT.
        String boundary = ToolResult.error("Reached text BM boundary without a generator").toJson();
        var exception = Class.forName(ModifyMetadataTool.class.getName() + "$TemplateWriteException")
            .getDeclaredConstructor(String.class);
        exception.setAccessible(true);
        doThrow((RuntimeException)exception.newInstance(boundary)).when(model).execute(any());

        Class<?> contextType = Class.forName(
            "com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool$ProjectContext");
        var constructor = contextType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object context = constructor.newInstance();
        Field projectField = contextType.getField("project");
        projectField.setAccessible(true);
        projectField.set(context, project);
        Method write = ModifyMetadataTool.class.getDeclaredMethod("modifyTemplateContent", contextType,
            String.class, BasicTemplate.class, List.class, List.class, boolean.class, JsonObject.class);
        write.setAccessible(true);
        JsonObject spec = new JsonObject();
        if (required) { spec.add("cells", new com.google.gson.JsonArray()); }
        else { spec.addProperty("text", "new text"); }
        Field plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        Object previous = plugin.get(null);
        try
        {
            plugin.set(null, activator);
            String result = (String)write.invoke(new ModifyMetadataTool(), context,
                "CommonTemplate.Contract730", template, List.of(), List.of(), false, spec);
            if (required)
            {
                assertTrue(result, result.contains("ITopObjectFqnGenerator"));
                verify(model, never()).execute(any());
            }
            else
            {
                assertEquals("Text writes must reach their BM boundary with a missing generator", boundary, result);
                verify(model).execute(any());
            }
        }
        finally
        {
            plugin.set(null, previous);
        }
    }

    private static void assertCreatedType(String value, TemplateType expected) throws Exception
    {
        CreateMetadataTool.Props props = new CreateMetadataTool.Props();
        assertNull(parse(value, props));
        DataProcessor owner = MdClassFactory.eINSTANCE.createDataProcessor();
        owner.setName("Owner730");
        EReference feature = (EReference)owner.eClass().getEStructuralFeature("templates");
        CreateMetadataTool.MemberChildSpec spec = new CreateMetadataTool.MemberChildSpec(
            mock(IModelObjectFactory.class), feature.getEReferenceType(), owner, Version.LATEST,
            "Contract730", props, null, feature, null);
        BasicTemplate created = (BasicTemplate)CreateMetadataTool.createMemberChild(spec);
        assertSame(expected, created.getTemplateType());
        assertSame(created, owner.getTemplates().get(0));
    }

    private static void assertRefused(String value) throws Exception
    {
        String error = parse(value, new CreateMetadataTool.Props());
        assertNotNull(error);
        assertTrue(error, error.contains(value));
        assertTrue(error, error.contains("TextDocument"));
        assertTrue(error, error.contains("SpreadsheetDocument"));
    }

    private static String parse(String value, CreateMetadataTool.Props props) throws Exception
    {
        JsonObject property = new JsonObject();
        property.addProperty("name", "templateType");
        property.addProperty("value", value);
        Method parse = CreateMetadataTool.class.getDeclaredMethod("parseProperties", List.class,
            CreateMetadataTool.Props.class, MdNameNormalizer.Report.class);
        parse.setAccessible(true);
        return (String)parse.invoke(new CreateMetadataTool(), List.of(property), props,
            new MdNameNormalizer.Report(true));
    }
}
