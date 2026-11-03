/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Response-only bilingual coverage; the programmatic names deliberately resemble kind tokens. */
public class MetadataWriteSuccessFqnTest
{
    private static final String MEMBER = "Catalog.Справочник.Attribute.Форма";
    private static final String FORM_MEMBER = "Catalog.Справочник.Form.Макет.Attribute.Форма";

    private static List<String> memberAddresses()
    {
        return List.of(MEMBER, "Справочник.Справочник.Реквизит.Форма",
            "Catalog.Справочник.Реквизит.Форма", "Справочник.Справочник.Attribute.Форма");
    }

    @Test
    public void testGenericCreateEchoesCanonicalTokensAndPreservesNames() throws Exception
    {
        Class<?> infoType = Class.forName(CreateMetadataTool.class.getName() + "$SuccessInfo");
        Constructor<?> constructor = infoType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Method success = CreateMetadataTool.class.getDeclaredMethod("success", infoType);
        success.setAccessible(true);
        for (String address : memberAddresses())
        {
            Object info = constructor.newInstance(address, MdClassPackage.Literals.CATALOG_ATTRIBUTE,
                "Форма", true, new CreateMetadataTool.Props(), null, null, false, null,
                new MdNameNormalizer.Report(false));
            JsonObject result = parse((String)success.invoke(new CreateMetadataTool(), info));
            assertEquals(MEMBER, result.get("fqn").getAsString());
            assertEquals("Created " + MEMBER, result.get("message").getAsString());
            assertEquals("Форма", result.get("name").getAsString());
        }
    }

    @Test
    public void testNestedFormCreateEchoesCanonicalTokens()
    {
        for (String address : List.of(FORM_MEMBER,
            "Справочник.Справочник.Форма.Макет.Реквизит.Форма",
            "Catalog.Справочник.Форма.Макет.Attribute.Форма"))
        {
            JsonObject result = parse(CreateMetadataTool.formMemberResult(address,
                "FormAttribute", "Форма", true, null).toJson());
            assertEquals(FORM_MEMBER, result.get("fqn").getAsString());
            assertEquals("Created " + FORM_MEMBER, CreateMetadataTool.formMemberCreatedMessage(
                FormElementWriter.Kind.ATTRIBUTE, address, "Форма", null));
        }
    }

    @Test
    public void testAdditionalColumnNamesRemainVerbatimAfterTheStructuralPrefix()
    {
        String canonical = "Catalog.Справочник.Form.Макет.Attribute.Object.Column.Реквизит.Форма";
        for (String address : List.of(canonical,
            "Справочник.Справочник.Форма.Макет.Реквизит.Object.Колонка.Реквизит.Форма"))
        {
            JsonObject result = parse(CreateMetadataTool.formMemberResult(address,
                "FormAttributeColumn", "Форма", true, "Object.Реквизит").toJson());
            assertEquals(canonical, result.get("fqn").getAsString());
            assertEquals("Object.Реквизит", result.get("tablePath").getAsString());
        }
    }

    @Test
    public void testCommandSuccessUsesTheSameCanonicalAddressInItsNextCall()
    {
        String canonical = "Catalog.Справочник.Form.Макет.Command.Команда";
        for (String address : List.of(canonical,
            "Справочник.Справочник.Форма.Макет.Команда.Команда"))
        {
            String message = CreateMetadataTool.formMemberCreatedMessage(
                FormElementWriter.Kind.COMMAND, address, "Команда", null);
            assertTrue(message, message.startsWith("Created " + canonical + "."));
            assertTrue(message, message.contains("'" + canonical + ".Handler.Action'"));
        }
    }

    @Test
    public void testModifyEchoesCanonicalTokensInStructuredAndHumanResults() throws Exception
    {
        Method build = ModifyMetadataTool.class.getDeclaredMethod("buildModifiedResult",
            String.class, List.class, boolean.class, MdNameNormalizer.Report.class);
        build.setAccessible(true);
        for (String address : memberAddresses())
        {
            JsonObject result = parse((String)build.invoke(null, address, List.of("comment"), true,
                new MdNameNormalizer.Report(false)));
            assertEquals(MEMBER, result.get("fqn").getAsString());
            assertEquals("Modified " + MEMBER + " (comment)", result.get("message").getAsString());
        }
    }

    @Test
    public void testDeletePreviewEchoesCanonicalTokens() throws Exception
    {
        Method build = DeleteMetadataTool.class.getDeclaredMethod("buildPreview",
            String.class, IRefactoring.class, List.class);
        build.setAccessible(true);
        for (String address : memberAddresses())
        {
            JsonObject result = parse((String)build.invoke(new DeleteMetadataTool(), address,
                mock(IRefactoring.class), List.of()));
            assertEquals(MEMBER, result.get("fqn").getAsString());
            assertEquals("preview", result.get("action").getAsString());
            assertTrue(result.get("success").getAsBoolean());
        }
    }

    @Test
    public void testRussianDeletePreviewScopeCanBeConfirmedWithItsCanonicalAddress()
    {
        DeleteMetadataTool.FormDeletePreview data = new DeleteMetadataTool.FormDeletePreview();
        data.scope = List.of("Attribute:Форма");
        String hash = ContentHash.of(data.scopeSignature(FORM_MEMBER));
        assertTrue(ContentHash.matches(data.scopeSignature(
            "Справочник.Справочник.Форма.Макет.Реквизит.Форма"), hash));
        data.scope = List.of("Attribute:Форма", "Field:BoundField");
        assertTrue("the guard must still detect scope changes",
            !ContentHash.matches(data.scopeSignature(FORM_MEMBER), hash));
    }

    @Test
    public void testConfirmedFormDeleteCanonicalizesTheResultAndItsOwnerPath()
    {
        for (String address : List.of(FORM_MEMBER,
            "Справочник.Справочник.Форма.Макет.Реквизит.Форма"))
        {
            FormElementWriter.FormMemberRef ref = FormElementWriter.parse(address);
            JsonObject result = parse(DeleteMetadataTool.formDeleteResult(address, ref, false,
                "FormAttribute", null, true));
            assertEquals(FORM_MEMBER, result.get("fqn").getAsString());
            assertTrue(result.toString(), result.get("message").getAsString()
                .contains("from Catalog.Справочник.Form.Макет"));
            assertEquals("executed", result.get("action").getAsString());
        }
    }

    private static JsonObject parse(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
