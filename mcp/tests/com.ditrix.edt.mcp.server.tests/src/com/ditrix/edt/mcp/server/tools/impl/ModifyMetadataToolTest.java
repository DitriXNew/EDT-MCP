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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils.MetadataTypeInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Lightweight contract tests for {@link ModifyMetadataTool}: tool metadata and JSON schema, without
 * the Eclipse/EDT runtime. The execute() path (validation + BM write) needs a live workbench and BM
 * model, so the validation / apply behaviour is covered by the E2E suite.
 */
public class ModifyMetadataToolTest
{
    @Test
    public void testNameConstant()
    {
        assertEquals("modify_metadata", new ModifyMetadataTool().getName()); //$NON-NLS-1$
        assertEquals(ModifyMetadataTool.NAME, new ModifyMetadataTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new ModifyMetadataTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new ModifyMetadataTool().getDescription();
        assertNotNull(desc);
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('modify_metadata')")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionAdvertisesFormHandlerAndCommandRebind()
    {
        // The form event-handler procedure rebind + the button command rebind are part of the tool
        // surface, so the description must advertise the 'procedure' and 'command' rebind properties.
        String desc = new ModifyMetadataTool().getDescription();
        assertTrue("description should advertise the handler 'procedure' rebind", //$NON-NLS-1$
            desc.contains("procedure")); //$NON-NLS-1$
        assertTrue("description should advertise the button 'command' rebind", //$NON-NLS-1$
            desc.contains("command")); //$NON-NLS-1$
    }

    @Test
    public void testGuideExplainsHandlerAndButtonCommandRebind()
    {
        // The rebind contract is documented: REBIND an existing handler's procedure / re-point a button.
        String guide = new ModifyMetadataTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide should explain the handler procedure rebind", //$NON-NLS-1$
            guide.contains("procedure")); //$NON-NLS-1$
        assertTrue("guide should explain re-pointing a button at a form command", //$NON-NLS-1$
            guide.contains("command")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionAndGuideAdvertiseStyleItemValue()
    {
        // Setting a StyleItem's Color / Font value is part of the tool surface, so the description
        // and the guide must advertise the 'value' property with its color / font shape.
        String desc = new ModifyMetadataTool().getDescription();
        assertTrue("description should advertise the StyleItem value", //$NON-NLS-1$
            desc.contains("StyleItem")); //$NON-NLS-1$
        assertTrue("description should mention the color shape", desc.contains("color")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention the font shape", desc.contains("font")); //$NON-NLS-1$ //$NON-NLS-2$

        String guide = new ModifyMetadataTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide should explain setting a StyleItem value", //$NON-NLS-1$
            guide.contains("StyleItem")); //$NON-NLS-1$
        assertTrue("guide should show the color value shape", guide.contains("color")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should show the font value shape", guide.contains("font")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescriptionAndGuideAdvertiseDynamicListQuery()
    {
        // Setting a dynamic-list custom query is part of the tool surface, so the description and the
        // guide must advertise the queryText / customQuery properties on a list-form attribute.
        String desc = new ModifyMetadataTool().getDescription();
        assertTrue("description should advertise queryText", desc.contains("queryText")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention the dynamic list", //$NON-NLS-1$
            desc.contains("DynamicList") || desc.contains("dynamic list")); //$NON-NLS-1$ //$NON-NLS-2$

        String guide = new ModifyMetadataTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide should show the queryText property", guide.contains("queryText")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should show the customQuery property", guide.contains("customQuery")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should show the mainTable property", guide.contains("mainTable")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDynamicListQueryPropertyRecognitionIsBilingual()
    {
        // English names, any case.
        assertTrue(ModifyMetadataTool.isQueryTextProp("queryText")); //$NON-NLS-1$
        assertTrue(ModifyMetadataTool.isQueryTextProp("QUERYTEXT")); //$NON-NLS-1$
        assertTrue(ModifyMetadataTool.isCustomQueryProp("customQuery")); //$NON-NLS-1$
        assertFalse(ModifyMetadataTool.isQueryTextProp("title")); //$NON-NLS-1$
        assertFalse(ModifyMetadataTool.isCustomQueryProp("queryText")); //$NON-NLS-1$
        // Russian names via codepoints (independent of the production constants): TekstZaprosa /
        // ProizvolnyjZapros - proving the tool recognizes both script variants.
        String ruQueryText = MetadataLanguageUtils.cp(0x0422, 0x0435, 0x043a, 0x0441, 0x0442, 0x0417,
            0x0430, 0x043f, 0x0440, 0x043e, 0x0441, 0x0430);
        String ruCustomQuery = MetadataLanguageUtils.cp(0x041f, 0x0440, 0x043e, 0x0438, 0x0437, 0x0432,
            0x043e, 0x043b, 0x044c, 0x043d, 0x044b, 0x0439, 0x0417, 0x0430, 0x043f, 0x0440, 0x043e, 0x0441);
        assertTrue("Russian queryText name must be recognized", //$NON-NLS-1$
            ModifyMetadataTool.isQueryTextProp(ruQueryText));
        assertTrue("Russian customQuery name must be recognized", //$NON-NLS-1$
            ModifyMetadataTool.isCustomQueryProp(ruCustomQuery));

        // mainTable - English + Russian OsnovnayaTablica (via codepoints, no raw Cyrillic).
        assertTrue(ModifyMetadataTool.isMainTableProp("mainTable")); //$NON-NLS-1$
        assertFalse(ModifyMetadataTool.isMainTableProp("queryText")); //$NON-NLS-1$
        String ruMainTable = MetadataLanguageUtils.cp(0x041e, 0x0441, 0x043d, 0x043e, 0x0432, 0x043d,
            0x0430, 0x044f, 0x0422, 0x0430, 0x0431, 0x043b, 0x0438, 0x0446, 0x0430);
        assertTrue("Russian mainTable name must be recognized", //$NON-NLS-1$
            ModifyMetadataTool.isMainTableProp(ruMainTable));
    }

    @Test
    public void testParseBooleanFlag()
    {
        // A JSON boolean or the strings "true"/"false" (any case) parse; anything else is not a flag.
        assertEquals(Boolean.TRUE, ModifyMetadataTool.parseBooleanFlag(new JsonPrimitive(true)));
        assertEquals(Boolean.FALSE, ModifyMetadataTool.parseBooleanFlag(new JsonPrimitive(false)));
        assertEquals(Boolean.TRUE, ModifyMetadataTool.parseBooleanFlag(new JsonPrimitive("true"))); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, ModifyMetadataTool.parseBooleanFlag(new JsonPrimitive("FALSE"))); //$NON-NLS-1$
        assertNull("a non-boolean string is not a flag", //$NON-NLS-1$
            ModifyMetadataTool.parseBooleanFlag(new JsonPrimitive("maybe"))); //$NON-NLS-1$
        assertNull("null is not a flag", ModifyMetadataTool.parseBooleanFlag(null)); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new ModifyMetadataTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"properties\"")); //$NON-NLS-1$
        // The ё->е normalization toggle must be declared (execute() reads it; schema parity).
        assertTrue("schema must declare the normalizeYo toggle", //$NON-NLS-1$
            schema.contains("\"normalizeYo\"")); //$NON-NLS-1$
        // The CommonAttribute content payload must be declared (execute() reads it; schema parity).
        assertTrue("schema must declare the content payload", //$NON-NLS-1$
            schema.contains("\"content\"")); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeYoIsOptional()
    {
        String schema = new ModifyMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("normalizeYo must not be required (defaults true)", //$NON-NLS-1$
            tail.contains("\"normalizeYo\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new ModifyMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fqn must be required", tail.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // 'properties' is no longer unconditionally required: a Role FQN is modified through the
        // role payload ('rights' / 'templates' / 'roleProperties') instead, so 'properties' is
        // conditionally required (enforced in execute(), not the schema's required array).
        assertFalse("properties must not be schema-required (role payload is the alternative)", //$NON-NLS-1$
            tail.contains("\"properties\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideCarriesValidationDetail()
    {
        String guide = new ModifyMetadataTool().getGuide();
        assertNotNull(guide);
        // the actionable-validation contract is documented
        assertTrue("guide should explain the allowed-values validation", //$NON-NLS-1$
            guide.contains("allowed")); //$NON-NLS-1$
        assertTrue("guide should steer discovery to get_metadata_details(assignable:true)", //$NON-NLS-1$
            guide.contains("assignable:true")); //$NON-NLS-1$
        // renaming is refused with a pointer to rename_metadata_object
        assertTrue("guide should point a rename at rename_metadata_object", //$NON-NLS-1$
            guide.contains("rename_metadata_object")); //$NON-NLS-1$
    }

    // ---- a handler rebind must not be mixed with other property changes ---------------------------

    private static JsonObject prop(String name, String value)
    {
        JsonObject o = new JsonObject();
        o.addProperty("name", name); //$NON-NLS-1$
        o.addProperty("value", value); //$NON-NLS-1$
        return o;
    }

    /**
     * The mix detector behind the handler-rebind rejection: a call that carries ONLY the rebind
     * property ({@code procedure} / {@code handler} alias, any case) is clean; any other property in
     * the same call is reported by name so the rebind path REJECTS instead of silently dropping it -
     * the same no-mixing policy the move ('parent'/'position') and button-command ('command')
     * branches enforce.
     */
    @Test
    public void testHandlerRebindMixDetection()
    {
        assertNull(ModifyMetadataTool.firstNonHandlerRebindProperty(
            Collections.singletonList(prop("procedure", "MyProc")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ModifyMetadataTool.firstNonHandlerRebindProperty(
            Collections.singletonList(prop("Handler", "MyProc")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ModifyMetadataTool.firstNonHandlerRebindProperty(
            Arrays.asList(prop("PROCEDURE", "A"), prop("handler", "B")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // The first foreign property is reported by name, wherever it sits in the list.
        assertEquals("title", ModifyMetadataTool.firstNonHandlerRebindProperty( //$NON-NLS-1$
            Arrays.asList(prop("procedure", "MyProc"), prop("title", "T")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("visible", ModifyMetadataTool.firstNonHandlerRebindProperty( //$NON-NLS-1$
            Arrays.asList(prop("visible", "false"), prop("handler", "MyProc")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    // ===== normalizeStringPropertyValue (scoped yo->ye normalization for free STRINGs) =====

    @Test
    public void testNormalizeStringPropertyValueLeavesNamespaceVerbatim()
    {
        // An identifier-like free STRING property (XDTOPackage.namespace is a URI) must keep
        // the caller's text VERBATIM even when it contains a yo (U+0451): a silent yo->ye
        // rewrite would corrupt the identifier, and 'namespace' is not presentation text
        // checked by the std474 validator (names / synonyms / comments are).
        MdNameNormalizer.Report report = new MdNameNormalizer.Report(true);
        String uri = "http://v8.1c.ru/packages/pak\u0451t"; //$NON-NLS-1$
        assertSame("namespace-like value must be returned verbatim", uri, //$NON-NLS-1$ //$NON-NLS-2$
            ModifyMetadataTool.normalizeStringPropertyValue("namespace", uri, report)); //$NON-NLS-1$
        assertFalse("a verbatim value must not be reported as normalized", report.hasChanges()); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeStringPropertyValueNormalizesComment()
    {
        // 'comment' IS presentation text checked by std474: its yo (U+0451) is normalized
        // to ye (U+0435) and the rewrite is reported under the property name.
        MdNameNormalizer.Report report = new MdNameNormalizer.Report(true);
        String result = ModifyMetadataTool.normalizeStringPropertyValue("comment", //$NON-NLS-1$
            "ozhidani\u0451", report); //$NON-NLS-1$
        assertEquals("ozhidani\u0435", result); //$NON-NLS-1$
        assertTrue("the comment normalization must be reported", report.hasChanges()); //$NON-NLS-1$
        assertEquals("comment", report.normalizedFields().get(0)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNormalizeStringPropertyValueHonorsDisabledToggle()
    {
        // normalizeYo=false: even the comment keeps the caller's text verbatim.
        MdNameNormalizer.Report report = new MdNameNormalizer.Report(false);
        String raw = "comment with \u0451"; //$NON-NLS-1$
        assertSame(raw, ModifyMetadataTool.normalizeStringPropertyValue("comment", raw, report)); //$NON-NLS-1$
        assertFalse(report.hasChanges());
    }

    // ===== CommonAttribute content payload (content[]) =====================================

    @Test
    public void testDescriptionAndGuideAdvertiseCommonAttributeContent()
    {
        // Attaching / detaching an owner in a CommonAttribute's content list is part of the tool
        // surface, so the description and the guide must advertise the 'content' payload with its
        // add/remove op and the 'use' values.
        String desc = new ModifyMetadataTool().getDescription();
        assertTrue("description should advertise the CommonAttribute content payload", //$NON-NLS-1$
            desc.contains("CommonAttribute") && desc.contains("content")); //$NON-NLS-1$ //$NON-NLS-2$

        String guide = new ModifyMetadataTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide should explain the common attribute content payload", //$NON-NLS-1$
            guide.contains("common attribute") && guide.contains("content")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should show the add/remove op", //$NON-NLS-1$
            guide.contains("remove")); //$NON-NLS-1$
        assertTrue("guide should show the use values", guide.contains("DontUse")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresContentCounts()
    {
        // The success shape for a content change carries a 'content' counts object {added, updated,
        // removed}; the output schema must declare it.
        String outputSchema = new ModifyMetadataTool().getOutputSchema();
        assertNotNull(outputSchema);
        assertTrue("output schema must declare the content counts key", //$NON-NLS-1$
            outputSchema.contains("\"content\"")); //$NON-NLS-1$
    }

    @Test
    public void testContentPayloadNotSchemaRequired()
    {
        // Like the role payload, 'content' is a conditional alternative to 'properties' (enforced in
        // execute(), not the schema's required array), so it must NOT be schema-required.
        String schema = new ModifyMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("content must not be schema-required (properties is the alternative)", //$NON-NLS-1$
            tail.contains("\"content\"")); //$NON-NLS-1$
    }

    @Test
    public void testArgumentGuardHelpNamesContentAlternative()
    {
        // The Display-free argument guard (empty 'properties' AND no sibling payload) now names
        // 'content' as the CommonAttribute alternative in its help text, so a caller who forgot the
        // content payload is steered to it. The guard message is built inside executeOnUiThread (on
        // a live workbench), so the wording is asserted via the guide + description surface here; the
        // FQN-typed rejects - content on a non-CommonAttribute FQN, content mixed with properties, an
        // empty content list on a CommonAttribute, and an unknown 'use' token - run inside
        // executeOnUiThread and are covered by the writer unit tests and the E2E suite.
        String guide = new ModifyMetadataTool().getGuide();
        assertTrue("the CommonAttribute FQN alternative must be documented in the guide", //$NON-NLS-1$
            guide.contains("CommonAttribute") && guide.contains("content")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCommonAttributeAndOwnerTypeTokensResolveBilingually()
    {
        // The bilingual ratchet: the content branch addresses a CommonAttribute FQN and resolves each
        // owner FQN through the shared MetadataTypeUtils + MetadataNodeResolver pair, so both the
        // English "CommonAttribute" token and the Russian "\u041e\u0431\u0449\u0438\u0439\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442" token must normalize to the
        // SAME MetadataTypeInfo, and likewise the owner "Catalog" / "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a" tokens. This
        // documents that modify_metadata accepts a "CommonAttribute.<Name>" FQN with a Russian owner
        // FQN in 'content', without needing a live model. The resolve-by-Name path (programmatic Name
        // inside a BM transaction) is exercised by the E2E suite.
        // Escaped so the RU tokens survive a non-UTF-8 Tycho build (see CLAUDE.md hard don't #7).
        String ruCommonAttribute =
            "\u041e\u0431\u0449\u0438\u0439\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442"; // \u041e\u0431\u0449\u0438\u0439\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442 //$NON-NLS-1$
        MetadataTypeInfo enCommonAttr = MetadataTypeUtils.resolve("CommonAttribute"); //$NON-NLS-1$
        MetadataTypeInfo ruCommonAttr = MetadataTypeUtils.resolve(ruCommonAttribute);
        assertNotNull("English CommonAttribute token must resolve", enCommonAttr); //$NON-NLS-1$
        assertEquals("EN and RU CommonAttribute tokens must resolve to the same type", //$NON-NLS-1$
            enCommonAttr, ruCommonAttr);
        assertEquals(MetadataTypeInfo.COMMON_ATTRIBUTE, enCommonAttr);

        String ruCatalog = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a"; // \u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a //$NON-NLS-1$
        MetadataTypeInfo enCatalog = MetadataTypeUtils.resolve("Catalog"); //$NON-NLS-1$
        MetadataTypeInfo ruCatalogInfo = MetadataTypeUtils.resolve(ruCatalog);
        assertNotNull("English Catalog owner token must resolve", enCatalog); //$NON-NLS-1$
        assertEquals("EN and RU Catalog owner tokens must resolve to the same type", //$NON-NLS-1$
            enCatalog, ruCatalogInfo);
    }
}
