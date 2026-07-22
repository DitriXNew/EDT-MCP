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
import java.util.HashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.CommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorForm;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataTool.FormHolder;
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

    // ===== Membership content payload v2 (ExchangePlan / Catalog / Document) ================

    @Test
    public void testDescriptionAdvertisesAllFourMembershipKinds()
    {
        // The v2 content[] dispatch adds ExchangePlan content objects, Catalog owners and Document
        // register records to the v1 CommonAttribute owners - all four kinds must be advertised in the
        // description so a schema-driven client sees which FQNs accept a 'content' payload.
        String desc = new ModifyMetadataTool().getDescription();
        assertTrue("description should still advertise CommonAttribute content", //$NON-NLS-1$
            desc.contains("CommonAttribute") && desc.contains("content")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should advertise ExchangePlan content", //$NON-NLS-1$
            desc.contains("ExchangePlan")); //$NON-NLS-1$
        assertTrue("description should advertise Catalog owners", desc.contains("Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should advertise Document register records", //$NON-NLS-1$
            desc.contains("Document")); //$NON-NLS-1$
        // The ExchangePlan per-entry flag is autoRecord (mapped Allow / Deny).
        assertTrue("description should advertise the ExchangePlan autoRecord flag", //$NON-NLS-1$
            desc.contains("autoRecord")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContentParamDocumentsAutoRecordAndKinds()
    {
        // The content param doc must declare the ExchangePlan 'autoRecord' field and name all four
        // membership kinds (execute() dispatches on them; schema parity keeps the wire surface honest).
        String schema = new ModifyMetadataTool().getInputSchema();
        assertTrue("content param doc must mention autoRecord", schema.contains("autoRecord")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("content param doc must name ExchangePlan", schema.contains("ExchangePlan")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("content param doc must name Catalog", schema.contains("Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("content param doc must name Document", schema.contains("Document")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideExplainsAllFourMembershipKinds()
    {
        // The guide's membership section must explain all four kinds and their per-entry flags, so a
        // caller learns which FQN takes 'use' (CommonAttribute), which takes 'autoRecord'
        // (ExchangePlan) and which are plain references (Catalog owners / Document register records).
        String guide = new ModifyMetadataTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide should explain ExchangePlan content", guide.contains("ExchangePlan")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should explain Catalog owners", //$NON-NLS-1$
            guide.contains("Catalog") && guide.contains("owner")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should explain Document register records", //$NON-NLS-1$
            guide.contains("Document") && guide.contains("register record")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should show the ExchangePlan autoRecord flag", //$NON-NLS-1$
            guide.contains("autoRecord")); //$NON-NLS-1$
        // The Allow / Deny tokens for autoRecord are documented.
        assertTrue("guide should show the autoRecord Allow token", guide.contains("Allow")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should show the autoRecord Deny token", guide.contains("Deny")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideDocumentsPlainRefCountsShape()
    {
        // A plain-reference membership change (Catalog owners / Document register records) has no
        // per-entry flag, so its counts shape is {added, removed} (no 'updated') - the guide's Result
        // section must document that distinction from the wrapper kinds.
        String guide = new ModifyMetadataTool().getGuide();
        assertTrue("guide should document the {added, removed} counts for plain-reference lists", //$NON-NLS-1$
            guide.contains("added, removed")); //$NON-NLS-1$
    }

    @Test
    public void testExchangePlanTypeTokenResolvesBilingually()
    {
        // The v2 content branch addresses an ExchangePlan FQN; both the English "ExchangePlan" token
        // and the Russian "\u041f\u043b\u0430\u043d\u041e\u0431\u043c\u0435\u043d\u0430" token must normalize to the SAME MetadataTypeInfo through the shared
        // MetadataTypeUtils resolver (the resolve-by-Name path is exercised by the E2E suite).
        // Escaped so the RU token survives a non-UTF-8 Tycho build (see CLAUDE.md hard don't #7).
        String ruExchangePlan =
            "\u041f\u043b\u0430\u043d\u041e\u0431\u043c\u0435\u043d\u0430"; // \u041f\u043b\u0430\u043d\u041e\u0431\u043c\u0435\u043d\u0430 //$NON-NLS-1$
        MetadataTypeInfo enExchangePlan = MetadataTypeUtils.resolve("ExchangePlan"); //$NON-NLS-1$
        MetadataTypeInfo ruExchangePlanInfo = MetadataTypeUtils.resolve(ruExchangePlan);
        assertNotNull("English ExchangePlan token must resolve", enExchangePlan); //$NON-NLS-1$
        assertEquals("EN and RU ExchangePlan tokens must resolve to the same type", //$NON-NLS-1$
            enExchangePlan, ruExchangePlanInfo);
        assertEquals(MetadataTypeInfo.EXCHANGE_PLAN, enExchangePlan);
    }

    @Test
    public void testDocumentTypeTokenResolvesBilingually()
    {
        // The v2 content branch addresses a Document FQN (its register records); both the English
        // "Document" token and the Russian "\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442" token must resolve to the SAME MetadataTypeInfo.
        String ruDocument =
            "\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442"; // \u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442 //$NON-NLS-1$
        MetadataTypeInfo enDocument = MetadataTypeUtils.resolve("Document"); //$NON-NLS-1$
        MetadataTypeInfo ruDocumentInfo = MetadataTypeUtils.resolve(ruDocument);
        assertNotNull("English Document token must resolve", enDocument); //$NON-NLS-1$
        assertEquals("EN and RU Document tokens must resolve to the same type", //$NON-NLS-1$
            enDocument, ruDocumentInfo);
        assertEquals(MetadataTypeInfo.DOCUMENT, enDocument);
    }

    @Test
    public void testCatalogTypeTokenResolvesBilinguallyForOwners()
    {
        // The v2 content branch also addresses a Catalog FQN for its OWNERS list (distinct from a
        // Catalog used as a CommonAttribute owner); the same bilingual token pair must resolve to the
        // same type, and it must be the CATALOG literal.
        String ruCatalog =
            "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a"; // \u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a //$NON-NLS-1$
        MetadataTypeInfo enCatalog = MetadataTypeUtils.resolve("Catalog"); //$NON-NLS-1$
        MetadataTypeInfo ruCatalogInfo = MetadataTypeUtils.resolve(ruCatalog);
        assertNotNull("English Catalog token must resolve", enCatalog); //$NON-NLS-1$
        assertEquals("EN and RU Catalog tokens must resolve to the same type", //$NON-NLS-1$
            enCatalog, ruCatalogInfo);
        assertEquals(MetadataTypeInfo.CATALOG, enCatalog);
    }

    @Test
    public void testContentPayloadDispatchSurfaceIsDocumentedForNegativeCases()
    {
        // The FQN-typed rejects for the v2 content dispatch - a content payload on an UNSUPPORTED kind
        // (rejected listing CommonAttribute / ExchangePlan / Catalog / Document), a wrong REF kind for
        // the target list (a non-CatalogOwner / non-BasicRegister), and content MIXED with a generic
        // 'properties' change - all run inside executeOnUiThread on a live workbench (like the v1
        // CommonAttribute rejects), so they are covered by the writer unit tests and the E2E suite. The
        // surface here asserts the four supported kinds are documented so a rejected caller is steered
        // to the right FQN kind.
        String desc = new ModifyMetadataTool().getDescription();
        assertTrue("all four content kinds must be named on the surface", //$NON-NLS-1$
            desc.contains("CommonAttribute") && desc.contains("ExchangePlan") //$NON-NLS-1$ //$NON-NLS-2$
                && desc.contains("Catalog") && desc.contains("Document")); //$NON-NLS-1$ //$NON-NLS-2$
        // The no-mixing policy (a content payload CANNOT be combined with a generic properties change)
        // is documented in the guide's shared membership section for every kind.
        String guide = new ModifyMetadataTool().getGuide();
        assertTrue("the no-mixing-with-properties policy must be documented", //$NON-NLS-1$
            guide.contains("CANNOT be combined with a generic")); //$NON-NLS-1$
    }

    // ===== resolveReferenceTarget: FORM member resolution for a REFERENCE property (issue #262) =====
    //
    // A REFERENCE property whose target is a FORM (e.g. a DataProcessor's `defaultForm`) could not be
    // set at all: the generic mdclass node resolver only walks the child-token containment tree
    // (attributes / tabular sections / commands / ...), which has no "Form" token - forms live in the
    // owner's OWN getForms() collection. resolveReferenceTarget now falls back to the SAME
    // FormElementWriter.parseFormPath + FormStructureReader.resolveMdForm pair get_metadata_details
    // already uses to read an existing form, plus a short-Name shorthand against a supplied owner.
    // Exercised headlessly: pure EMF containment reads, no BM/live project needed.

    private static DataProcessorForm addOwnedForm(DataProcessor owner, String formName)
    {
        DataProcessorForm form = MdClassFactory.eINSTANCE.createDataProcessorForm();
        form.setName(formName);
        owner.getForms().add(form);
        return form;
    }

    @Test
    public void testResolveReferenceTargetResolvesFullFormPath()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        DataProcessor dp = MdClassFactory.eINSTANCE.createDataProcessor();
        dp.setName("MyProcessor"); //$NON-NLS-1$
        config.getDataProcessors().add(dp);
        DataProcessorForm form = addOwnedForm(dp, "MyForm"); //$NON-NLS-1$

        MdObject resolved = ModifyMetadataTool.resolveReferenceTarget(config, dp,
            "DataProcessor.MyProcessor.Form.MyForm"); //$NON-NLS-1$
        assertSame("the full Type.Name.Form.FormName path must resolve the owned form", form, resolved); //$NON-NLS-1$
    }

    @Test
    public void testResolveReferenceTargetResolvesShortFormNameAgainstOwner()
    {
        // A bare short Name ('MyForm', no dots) resolves as shorthand for a form owned by the SAME
        // object being modified (e.g. defaultForm:'MyForm' instead of the full FQN).
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        DataProcessor dp = MdClassFactory.eINSTANCE.createDataProcessor();
        dp.setName("MyProcessor"); //$NON-NLS-1$
        config.getDataProcessors().add(dp);
        DataProcessorForm form = addOwnedForm(dp, "MyForm"); //$NON-NLS-1$

        MdObject resolved = ModifyMetadataTool.resolveReferenceTarget(config, dp, "MyForm"); //$NON-NLS-1$
        assertSame("a short form Name must resolve against the supplied owner", form, resolved); //$NON-NLS-1$
    }

    @Test
    public void testResolveReferenceTargetShortNameWithoutOwnerDoesNotResolve()
    {
        // Without an owner (e.g. the MANY_REFERENCE call site), a bare short Name must NOT silently
        // pick some unrelated object - it simply does not resolve.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        DataProcessor dp = MdClassFactory.eINSTANCE.createDataProcessor();
        dp.setName("MyProcessor"); //$NON-NLS-1$
        config.getDataProcessors().add(dp);
        addOwnedForm(dp, "MyForm"); //$NON-NLS-1$

        assertNull(ModifyMetadataTool.resolveReferenceTarget(config, null, "MyForm")); //$NON-NLS-1$
    }

    @Test
    public void testResolveReferenceTargetUnknownFormReturnsNull()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        DataProcessor dp = MdClassFactory.eINSTANCE.createDataProcessor();
        dp.setName("MyProcessor"); //$NON-NLS-1$
        config.getDataProcessors().add(dp);

        assertNull(ModifyMetadataTool.resolveReferenceTarget(config, dp,
            "DataProcessor.MyProcessor.Form.NoSuchForm")); //$NON-NLS-1$
        assertNull(ModifyMetadataTool.resolveReferenceTarget(config, dp, "NoSuchForm")); //$NON-NLS-1$
    }

    @Test
    public void testResolveReferenceTargetStillResolvesTopObjects()
    {
        // Non-form top-object references (e.g. a Command's group -> 'CommandGroup.<Name>') keep
        // working through the generic mdclass resolver, unaffected by the new form fallback.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        CommandGroup group = MdClassFactory.eINSTANCE.createCommandGroup();
        group.setName("MyGroup"); //$NON-NLS-1$
        config.getCommandGroups().add(group);

        MdObject resolved = ModifyMetadataTool.resolveReferenceTarget(config, null, "CommandGroup.MyGroup"); //$NON-NLS-1$
        assertSame(group, resolved);
    }

    // ===== validateReferenceTarget: not-found hint (issue #262 P3, "do not fake support") ==========
    //
    // target==null never touches IBmObject (bmGetId/bmIsTop), so this branch is testable headlessly.

    @Test
    public void testValidateReferenceTargetNotFoundHintIsCommandGroupSpecific()
    {
        // A command's 'group' feature (declared against the mcore CommandGroup interface) gets a hint
        // naming the supported 'CommandGroup.<Name>' shape AND explicitly calling out that the
        // platform's STANDARD command groups are a different, unsupported value space.
        EStructuralFeature groupFeature = MdClassFactory.eINSTANCE.createDataProcessorCommand()
            .eClass().getEStructuralFeature("group"); //$NON-NLS-1$
        assertNotNull("precondition: DataProcessorCommand must declare 'group'", groupFeature); //$NON-NLS-1$
        String err = ModifyMetadataTool.validateReferenceTarget("group", groupFeature, null, //$NON-NLS-1$
            "CommandGroup.Bogus"); //$NON-NLS-1$
        assertNotNull(err);
        assertTrue("the hint must name the CommandGroup.<Name> shape", //$NON-NLS-1$
            err.contains("CommandGroup.<Name>")); //$NON-NLS-1$
        assertTrue("the hint must call out standard groups as unsupported", //$NON-NLS-1$
            err.contains("STANDARD command groups")); //$NON-NLS-1$
    }

    @Test
    public void testValidateReferenceTargetNotFoundHintIsGenericForOtherReferences()
    {
        EStructuralFeature parentFeature = MdClassFactory.eINSTANCE.createSubsystem()
            .eClass().getEStructuralFeature("parentSubsystem"); //$NON-NLS-1$
        assertNotNull("precondition: Subsystem must declare 'parentSubsystem'", parentFeature); //$NON-NLS-1$
        String err = ModifyMetadataTool.validateReferenceTarget("parentSubsystem", parentFeature, null, //$NON-NLS-1$
            "Subsystem.Bogus"); //$NON-NLS-1$
        assertNotNull(err);
        assertFalse("a non-group reference must NOT get the CommandGroup-specific hint", //$NON-NLS-1$
            err.contains("CommandGroup.<Name>")); //$NON-NLS-1$
        assertTrue("a non-group reference gets the generic FQN hint", //$NON-NLS-1$
            err.contains("get_metadata_objects")); //$NON-NLS-1$
    }

    // ===== form-member extInfo routing (#235: a UsualGroup's layout props live under <extInfo>) =====
    //
    // A form group's grouping (`group`) + united / layout flags do NOT live on the group element but on
    // its nested extInfo. resolveFormHolder is the ONE general reflective decision that routes each
    // property to the correct receiver: a DIRECT feature stays on the member, an extInfo feature is
    // flagged onExtInfo so the write goes to the extInfo holder. Tested headlessly against a synthetic
    // form-like EMF model (no live workbench / BM needed for the classification decision).

    /**
     * A synthetic EMF package shaped like a form group: a {@code FormGroup} EClass with a DIRECT
     * {@code visible} boolean and a containment {@code extInfo} reference to a {@code UsualGroupExtInfo}
     * EClass that carries the layout props ({@code group} enum + {@code united} boolean). Mirrors the
     * real 1C form metamodel closely enough to exercise the reflective extInfo routing without importing
     * (the forbidden) {@code com._1c.g5.v8.dt.form.model}.
     */
    private static EPackage buildFormLikePackage()
    {
        EcoreFactory f = EcoreFactory.eINSTANCE;
        EPackage pkg = f.createEPackage();
        pkg.setName("formlike"); //$NON-NLS-1$
        pkg.setNsPrefix("formlike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.test/formlike/235"); //$NON-NLS-1$

        EEnum grouping = f.createEEnum();
        grouping.setName("Grouping"); //$NON-NLS-1$
        EEnumLiteral vertical = f.createEEnumLiteral();
        vertical.setName("Vertical"); //$NON-NLS-1$
        vertical.setValue(0);
        EEnumLiteral horizontal = f.createEEnumLiteral();
        horizontal.setName("Horizontal"); //$NON-NLS-1$
        horizontal.setValue(1);
        grouping.getELiterals().add(vertical);
        grouping.getELiterals().add(horizontal);
        pkg.getEClassifiers().add(grouping);

        EClass extInfo = f.createEClass();
        extInfo.setName("UsualGroupExtInfo"); //$NON-NLS-1$
        EAttribute group = f.createEAttribute();
        group.setName("group"); //$NON-NLS-1$
        group.setEType(grouping);
        EAttribute united = f.createEAttribute();
        united.setName("united"); //$NON-NLS-1$
        united.setEType(EcorePackage.Literals.EBOOLEAN);
        extInfo.getEStructuralFeatures().add(group);
        extInfo.getEStructuralFeatures().add(united);
        pkg.getEClassifiers().add(extInfo);

        EClass formGroup = f.createEClass();
        formGroup.setName("FormGroup"); //$NON-NLS-1$
        EAttribute visible = f.createEAttribute();
        visible.setName("visible"); //$NON-NLS-1$
        visible.setEType(EcorePackage.Literals.EBOOLEAN);
        EReference extInfoRef = f.createEReference();
        extInfoRef.setName("extInfo"); //$NON-NLS-1$
        extInfoRef.setEType(extInfo);
        extInfoRef.setContainment(true);
        formGroup.getEStructuralFeatures().add(visible);
        formGroup.getEStructuralFeatures().add(extInfoRef);
        pkg.getEClassifiers().add(formGroup);

        return pkg;
    }

    /** A synthetic FormGroup instance with its extInfo instance already attached (the common case). */
    private static EObject newGroupWithExtInfo(EPackage pkg, EObject[] outExtInfo)
    {
        EClass formGroupClass = (EClass)pkg.getEClassifier("FormGroup"); //$NON-NLS-1$
        EClass extInfoClass = (EClass)pkg.getEClassifier("UsualGroupExtInfo"); //$NON-NLS-1$
        EObject group = pkg.getEFactoryInstance().create(formGroupClass);
        EObject extInfo = pkg.getEFactoryInstance().create(extInfoClass);
        group.eSet(formGroupClass.getEStructuralFeature("extInfo"), extInfo); //$NON-NLS-1$
        outExtInfo[0] = extInfo;
        return group;
    }

    @Test
    public void testResolveFormHolderRoutesExtInfoLayoutPropsToExtInfo()
    {
        // `group` + `united` live on the UsualGroupExtInfo, so they must route to the extInfo holder
        // (onExtInfo == true) and be classified against the extInfo instance.
        EPackage pkg = buildFormLikePackage();
        EObject[] extInfoOut = new EObject[1];
        EObject group = newGroupWithExtInfo(pkg, extInfoOut);
        EObject extInfo = extInfoOut[0];

        FormHolder g = ModifyMetadataTool.resolveFormHolder(group, "group"); //$NON-NLS-1$
        assertTrue("the grouping enum lives under <extInfo> -> onExtInfo", g.onExtInfo); //$NON-NLS-1$
        assertSame("the group prop must be classified against the extInfo instance", //$NON-NLS-1$
            extInfo, g.classifyExtInfo);

        FormHolder u = ModifyMetadataTool.resolveFormHolder(group, "united"); //$NON-NLS-1$
        assertTrue("the united flag lives under <extInfo> -> onExtInfo", u.onExtInfo); //$NON-NLS-1$

        // Case-insensitive, mirroring findFeature.
        assertTrue("routing must be case-insensitive", //$NON-NLS-1$
            ModifyMetadataTool.resolveFormHolder(group, "GROUP").onExtInfo); //$NON-NLS-1$
    }

    @Test
    public void testResolveFormHolderKeepsDirectFeatureOnMember()
    {
        // `visible` is a DIRECT feature of the group element, so it stays on the member (onExtInfo ==
        // false) even though the element also carries an extInfo - direct-precedence.
        EPackage pkg = buildFormLikePackage();
        EObject[] extInfoOut = new EObject[1];
        EObject group = newGroupWithExtInfo(pkg, extInfoOut);

        FormHolder v = ModifyMetadataTool.resolveFormHolder(group, "visible"); //$NON-NLS-1$
        assertFalse("a direct feature must stay on the member (not the extInfo)", v.onExtInfo); //$NON-NLS-1$
        assertSame("the extInfo is still threaded for classification", //$NON-NLS-1$
            extInfoOut[0], v.classifyExtInfo);
    }

    @Test
    public void testResolveFormHolderUnknownPropertyStaysOnMember()
    {
        // A property that is on NEITHER the member nor its extInfo is not routed to the extInfo (the
        // holder defaults to the member; prepare() then rejects it with the extended assignable set).
        EPackage pkg = buildFormLikePackage();
        EObject[] extInfoOut = new EObject[1];
        EObject group = newGroupWithExtInfo(pkg, extInfoOut);

        FormHolder n = ModifyMetadataTool.resolveFormHolder(group, "noSuchProp_zz235"); //$NON-NLS-1$
        assertFalse("an unknown property must not be routed to the extInfo", n.onExtInfo); //$NON-NLS-1$
    }

    @Test
    public void testResolveFormHolderNoExtInfoFeatureIsDirectNoOp()
    {
        // An element with NO extInfo feature (the mdclass-like no-op case) always routes a direct feature
        // to the element itself, and threads a null classification extInfo.
        EPackage pkg = buildFormLikePackage();
        EClass extInfoClass = (EClass)pkg.getEClassifier("UsualGroupExtInfo"); //$NON-NLS-1$
        // A UsualGroupExtInfo has a direct `united` but NO nested `extInfo` feature of its own.
        EObject plain = pkg.getEFactoryInstance().create(extInfoClass);

        FormHolder h = ModifyMetadataTool.resolveFormHolder(plain, "united"); //$NON-NLS-1$
        assertFalse("a member with no extInfo feature routes directly", h.onExtInfo); //$NON-NLS-1$
        assertNull("no extInfo instance is threaded when the element has no extInfo feature", //$NON-NLS-1$
            h.classifyExtInfo);
    }

    // ===== reject a classifier `type` change batched with an extInfo layout prop (#235 review) =====
    //
    // A group's `type` decides which concrete <extInfo> EClass applies; the extInfo props are classified
    // against the PRE-change type's EClass, so combining a direct `type` change with any onExtInfo prop in
    // ONE call is order-dependent and unsafe. formTypeExtInfoComboError rejects that combination up front
    // (a mixed direct + extInfo batch that does NOT change `type` is still allowed). Reuses the shared
    // prop(name, value) helper above.

    @Test
    public void testComboRejectsTypeChangeWithExtInfoLayoutProp()
    {
        // `type` (the classifier) + `group` (lives on <extInfo>) in one call must be refused with an
        // actionable "change the type in a separate call" error.
        EPackage pkg = buildFormLikePackage();
        EObject group = newGroupWithExtInfo(pkg, new EObject[1]);

        String err = ModifyMetadataTool.formTypeExtInfoComboError(group,
            Arrays.asList(prop("type", "Pages"), prop("group", "Horizontal"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNotNull("combining a group type change with an extInfo prop must be rejected", err); //$NON-NLS-1$
        assertTrue("the error must be a ToolResult error json", err.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must point at making the type change separately", //$NON-NLS-1$
            err.contains("separate call")); //$NON-NLS-1$
        // Order-independent: the reverse batch (extInfo prop first) is refused just the same.
        assertNotNull("the reverse order must be rejected identically", //$NON-NLS-1$
            ModifyMetadataTool.formTypeExtInfoComboError(group,
                Arrays.asList(prop("group", "Horizontal"), prop("type", "Pages")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testComboAllowsExtInfoPropAlone()
    {
        // An extInfo layout prop on its own (no `type` change) is safe - the extInfo is resolved against
        // the element's current, unchanged type.
        EPackage pkg = buildFormLikePackage();
        EObject group = newGroupWithExtInfo(pkg, new EObject[1]);

        assertNull("an extInfo prop with no type change must be allowed", //$NON-NLS-1$
            ModifyMetadataTool.formTypeExtInfoComboError(group,
                Collections.singletonList(prop("group", "Horizontal")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testComboAllowsTypeChangeAlone()
    {
        // A `type` change with no extInfo prop is safe (the extInfo is re-resolved against the new type
        // on the next call).
        EPackage pkg = buildFormLikePackage();
        EObject group = newGroupWithExtInfo(pkg, new EObject[1]);

        assertNull("a type change with no extInfo prop must be allowed", //$NON-NLS-1$
            ModifyMetadataTool.formTypeExtInfoComboError(group,
                Collections.singletonList(prop("type", "Pages")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testComboAllowsDirectAndExtInfoWithoutTypeChange()
    {
        // A mixed batch of a DIRECT feature (`visible`) + an extInfo prop (`group`) that does NOT touch
        // `type` stays allowed - the classifier is unchanged, so both route to their correct holder.
        EPackage pkg = buildFormLikePackage();
        EObject group = newGroupWithExtInfo(pkg, new EObject[1]);

        assertNull("a direct + extInfo batch without a type change must still be allowed", //$NON-NLS-1$
            ModifyMetadataTool.formTypeExtInfoComboError(group,
                Arrays.asList(prop("visible", "true"), prop("group", "Horizontal")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    // ===== template spreadsheet-content payload dispatch guards (#245) =====================
    //
    // A `template` payload (SpreadsheetDocument cells / merges / areas) is only valid on a
    // SpreadsheetDocument template FQN, is authored through its own surface, and must not be mixed with a
    // generic properties / membership content / Role payload. The two tool-level guards behind that
    // (templateOnlyForTemplateFqnError on a non-template FQN; templateMixError inside modifyTemplateContent)
    // plus the parseTemplateArg reader are pure and covered here, mirroring the Role / content guard tests
    // (firstNonHandlerRebindProperty). The live BM write + force-export is covered by the E2E suite.

    @Test
    public void testTemplatePayloadRefusedOnNonTemplateFqn()
    {
        // A `template` payload addressed to a NON-template FQN must be refused (not silently dropped while
        // a generic / role / content branch reports success): the error names the offending FQN, the
        // 'template' payload, what the FQN actually is, and points at the valid template FQN shapes.
        String err = ModifyMetadataTool.templateOnlyForTemplateFqnError(
            "Catalog.Goods", "is a Catalog"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a template payload on a non-template FQN must be refused", err); //$NON-NLS-1$
        assertTrue("the refusal must be a ToolResult error json", err.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the offending FQN", err.contains("Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the 'template' payload", err.contains("template")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must echo what the FQN actually is", err.contains("is a Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must point at the valid template FQN shape", //$NON-NLS-1$
            err.contains("CommonTemplate")); //$NON-NLS-1$
    }

    @Test
    public void testTemplatePayloadMixRefused()
    {
        // A `template` payload combined with a generic 'properties' change is refused, naming both the
        // template payload and the conflicting properties change.
        String propsMix = ModifyMetadataTool.templateMixError(
            Collections.singletonList(prop("comment", "Goods")), //$NON-NLS-1$ //$NON-NLS-2$
            Collections.<JsonObject> emptyList(), false);
        assertNotNull("template + a generic properties change must be refused", propsMix); //$NON-NLS-1$
        assertTrue("the refusal must be a ToolResult error json", propsMix.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the template payload", propsMix.contains("template")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the conflicting properties change", //$NON-NLS-1$
            propsMix.contains("properties")); //$NON-NLS-1$

        // A `template` payload combined with a membership 'content' payload is refused, naming 'content'.
        String contentMix = ModifyMetadataTool.templateMixError(
            Collections.<JsonObject> emptyList(),
            Collections.singletonList(prop("owner", "Catalog.Goods")), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("template + a membership content payload must be refused", contentMix); //$NON-NLS-1$
        assertTrue("the refusal must name the conflicting content payload", //$NON-NLS-1$
            contentMix.contains("content")); //$NON-NLS-1$

        // A `template` payload combined with a Role payload is refused, naming the Role rights payload.
        String roleMix = ModifyMetadataTool.templateMixError(
            Collections.<JsonObject> emptyList(), Collections.<JsonObject> emptyList(), true);
        assertNotNull("template + a Role payload must be refused", roleMix); //$NON-NLS-1$
        assertTrue("the refusal must name the Role rights payload", //$NON-NLS-1$
            roleMix.contains("Role") && roleMix.contains("rights")); //$NON-NLS-1$ //$NON-NLS-2$

        // A `template` payload standing alone is NOT a mix -> null, so the write proceeds.
        assertNull("a lone template payload is not a mix", ModifyMetadataTool.templateMixError( //$NON-NLS-1$
            Collections.<JsonObject> emptyList(), Collections.<JsonObject> emptyList(), false));
    }

    @Test
    public void testParseTemplateArgHandlesAbsentBlankAndMalformed()
    {
        Map<String, String> params = new HashMap<>();
        // Absent -> no payload, no error (the caller falls through to the other branches).
        ModifyMetadataTool.TemplateArg absent = ModifyMetadataTool.parseTemplateArg(params);
        assertNull("an absent 'template' arg carries no spec", absent.spec); //$NON-NLS-1$
        assertNull("an absent 'template' arg carries no error", absent.error); //$NON-NLS-1$

        // Blank / whitespace-only -> absent.
        params.put("template", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        ModifyMetadataTool.TemplateArg blank = ModifyMetadataTool.parseTemplateArg(params);
        assertNull("a blank 'template' arg carries no spec", blank.spec); //$NON-NLS-1$
        assertNull("a blank 'template' arg carries no error", blank.error); //$NON-NLS-1$

        // Malformed JSON -> an actionable error, NOT a silent drop: 'template' is the sole surface for the
        // feature, so a present-but-malformed value must be surfaced rather than dropped (which would let a
        // stray 'properties' apply, or misreport 'properties is required').
        params.put("template", "{not json"); //$NON-NLS-1$ //$NON-NLS-2$
        ModifyMetadataTool.TemplateArg malformed = ModifyMetadataTool.parseTemplateArg(params);
        assertNull("a malformed 'template' arg yields no spec", malformed.spec); //$NON-NLS-1$
        assertNotNull("a malformed 'template' arg must be an error, not a silent drop", //$NON-NLS-1$
            malformed.error);
        assertTrue("the refusal must be a ToolResult error json", //$NON-NLS-1$
            malformed.error.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("the refusal must name the 'template' arg", malformed.error.contains("template")); //$NON-NLS-1$ //$NON-NLS-2$

        // A non-object JSON (an array) -> the same actionable error: the arg is a single spec object.
        params.put("template", "[1,2,3]"); //$NON-NLS-1$ //$NON-NLS-2$
        ModifyMetadataTool.TemplateArg nonObject = ModifyMetadataTool.parseTemplateArg(params);
        assertNull("a non-object 'template' arg yields no spec", nonObject.spec); //$NON-NLS-1$
        assertNotNull("a non-object 'template' arg must be an error", nonObject.error); //$NON-NLS-1$

        // A well-formed JSON object parses through (its members preserved, whitespace-trimmed).
        params.put("template", "  {\"cells\":[]}  "); //$NON-NLS-1$ //$NON-NLS-2$
        ModifyMetadataTool.TemplateArg valid = ModifyMetadataTool.parseTemplateArg(params);
        assertNull("a well-formed 'template' arg carries no error", valid.error); //$NON-NLS-1$
        assertNotNull("a well-formed 'template' object must parse", valid.spec); //$NON-NLS-1$
        assertTrue("the parsed object must carry its members", valid.spec.has("cells")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ===== template payload on a real BasicTemplate of the WRONG type is refused (#245) ============
    //
    // Only a SpreadsheetDocument template hosts cells; a text / binary-data / DCS / graphical template is
    // refused UP FRONT (before any BM write) by nonSpreadsheetTemplateError, naming the template's ACTUAL
    // type. This is the refusal the existing non-template-FQN / mix guards do NOT cover: the FQN resolves
    // to a real BasicTemplate, but its type is wrong. Built headlessly from an in-memory MdClassFactory
    // template (the resolve-by-FQN + BM write + assert_no_diff is the E2E suite's job).

    private static BasicTemplate templateOfType(TemplateType type)
    {
        BasicTemplate template = MdClassFactory.eINSTANCE.createCommonTemplate();
        template.setTemplateType(type);
        return template;
    }

    @Test
    public void testNonSpreadsheetTemplateRefusalNamesActualType()
    {
        // A Text template is the wrong kind for a `template` payload: refused, naming both the FQN and the
        // template's ACTUAL type so the caller learns why the cells cannot be authored.
        String fqn = "CommonTemplate.TextNote"; //$NON-NLS-1$
        String err = ModifyMetadataTool.nonSpreadsheetTemplateError(
            templateOfType(TemplateType.TEXT_DOCUMENT), fqn);
        assertNotNull("a non-SpreadsheetDocument template must be refused", err); //$NON-NLS-1$
        assertTrue("the refusal must be a ToolResult error json", err.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the offending template FQN", err.contains(fqn)); //$NON-NLS-1$
        assertTrue("the refusal must state it is not a SpreadsheetDocument template", //$NON-NLS-1$
            err.contains("not a SpreadsheetDocument template")); //$NON-NLS-1$
        // The actual type is named verbatim (its EMF literal name) - a regression that dropped the type
        // from the message (or NPE'd resolving it) fails here.
        assertTrue("the refusal must name the actual template type", //$NON-NLS-1$
            err.contains(TemplateType.TEXT_DOCUMENT.getName()));
    }

    @Test
    public void testNonSpreadsheetTemplateRefusalCoversEveryTypeAndAcceptsSpreadsheet()
    {
        // The ratchet across the whole TemplateType enum: ONLY a SpreadsheetDocument template is accepted
        // (null -> the write proceeds); every other kind is refused naming its actual type. A guard that
        // special-cased one type - or silently accepted a non-spreadsheet template - fails here.
        for (TemplateType type : TemplateType.values())
        {
            String err = ModifyMetadataTool.nonSpreadsheetTemplateError(
                templateOfType(type), "CommonTemplate.T"); //$NON-NLS-1$
            if (type == TemplateType.SPREADSHEET_DOCUMENT)
            {
                assertNull("a SpreadsheetDocument template must be accepted (write may proceed)", err); //$NON-NLS-1$
            }
            else
            {
                assertNotNull("a " + type.getName() + " template must be refused", err); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue("the refusal for " + type.getName() + " must name its actual type", //$NON-NLS-1$ //$NON-NLS-2$
                    err.contains(type.getName()));
            }
        }
    }

    // ===== DCS (Report Data Composition Schema) payload dispatch guards (#241) =======================
    //
    // A `dcs` payload authors a report's Data Composition Schema; it is only valid on a Report FQN, is
    // authored through its own surface, and must not be mixed with a generic properties / membership
    // content / Role / template payload. The two tool-level guards behind that (dcsOnlyForReportFqnError
    // on a non-Report FQN; dcsMixError at the dispatch site) plus the parseDcsArg reader are pure and
    // covered here, mirroring the #245 template guard tests. The live BM write + force-export (the report
    // -> DCS-template resolution + the .dcs drain) is covered by the E2E suite.

    @Test
    public void testDcsPayloadRefusedOnNonReportFqn()
    {
        // A `dcs` payload addressed to a NON-Report FQN must be refused (not silently dropped while a
        // generic / role / content / template branch reports success): the error names the offending FQN,
        // the 'dcs' payload, what the FQN actually is, and points at the valid Report FQN shape.
        String err = ModifyMetadataTool.dcsOnlyForReportFqnError(
            "Catalog.Goods", "is a Catalog"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a dcs payload on a non-Report FQN must be refused", err); //$NON-NLS-1$
        assertTrue("the refusal must be a ToolResult error json", err.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the offending FQN", err.contains("Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the 'dcs' payload", err.contains("dcs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must echo what the FQN actually is", err.contains("is a Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must point at the valid Report FQN shape", //$NON-NLS-1$
            err.contains("Report.<Name>")); //$NON-NLS-1$
    }

    @Test
    public void testDcsPayloadMixRefused()
    {
        // A `dcs` payload combined with a generic 'properties' change is refused, naming both payloads.
        String propsMix = ModifyMetadataTool.dcsMixError(
            Collections.singletonList(prop("comment", "Sales")), //$NON-NLS-1$ //$NON-NLS-2$
            Collections.<JsonObject> emptyList(), false, false);
        assertNotNull("dcs + a generic properties change must be refused", propsMix); //$NON-NLS-1$
        assertTrue("the refusal must be a ToolResult error json", propsMix.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the dcs payload", propsMix.contains("dcs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the conflicting properties change", //$NON-NLS-1$
            propsMix.contains("properties")); //$NON-NLS-1$

        // A `dcs` payload combined with a membership 'content' payload is refused, naming 'content'.
        String contentMix = ModifyMetadataTool.dcsMixError(
            Collections.<JsonObject> emptyList(),
            Collections.singletonList(prop("metadata", "Catalog.Goods")), false, false); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("dcs + a membership content payload must be refused", contentMix); //$NON-NLS-1$
        assertTrue("the refusal must name the conflicting content payload", //$NON-NLS-1$
            contentMix.contains("content")); //$NON-NLS-1$

        // A `dcs` payload combined with a Role payload is refused, naming the Role rights payload.
        String roleMix = ModifyMetadataTool.dcsMixError(
            Collections.<JsonObject> emptyList(), Collections.<JsonObject> emptyList(), true, false);
        assertNotNull("dcs + a Role payload must be refused", roleMix); //$NON-NLS-1$
        assertTrue("the refusal must name the Role rights payload", //$NON-NLS-1$
            roleMix.contains("Role") && roleMix.contains("rights")); //$NON-NLS-1$ //$NON-NLS-2$

        // A `dcs` payload combined with a `template` payload is refused, naming the template payload.
        String templateMix = ModifyMetadataTool.dcsMixError(
            Collections.<JsonObject> emptyList(), Collections.<JsonObject> emptyList(), false, true);
        assertNotNull("dcs + a template payload must be refused", templateMix); //$NON-NLS-1$
        assertTrue("the refusal must name the conflicting template payload", //$NON-NLS-1$
            templateMix.contains("template")); //$NON-NLS-1$

        // A `dcs` payload standing alone is NOT a mix -> null, so the write proceeds.
        assertNull("a lone dcs payload is not a mix", ModifyMetadataTool.dcsMixError( //$NON-NLS-1$
            Collections.<JsonObject> emptyList(), Collections.<JsonObject> emptyList(), false, false));
    }

    @Test
    public void testParseDcsArgHandlesAbsentBlankAndMalformed()
    {
        Map<String, String> params = new HashMap<>();
        // Absent -> no payload, no error (the caller falls through to the other branches).
        ModifyMetadataTool.DcsArg absent = ModifyMetadataTool.parseDcsArg(params);
        assertNull("an absent 'dcs' arg carries no spec", absent.spec); //$NON-NLS-1$
        assertNull("an absent 'dcs' arg carries no error", absent.error); //$NON-NLS-1$

        // Blank / whitespace-only -> absent.
        params.put("dcs", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        ModifyMetadataTool.DcsArg blank = ModifyMetadataTool.parseDcsArg(params);
        assertNull("a blank 'dcs' arg carries no spec", blank.spec); //$NON-NLS-1$
        assertNull("a blank 'dcs' arg carries no error", blank.error); //$NON-NLS-1$

        // Malformed JSON -> an actionable error, NOT a silent drop: 'dcs' is the sole surface for the
        // feature, so a present-but-malformed value must be surfaced rather than dropped (which would let a
        // stray 'properties' apply, or misreport 'properties is required').
        params.put("dcs", "{not json"); //$NON-NLS-1$ //$NON-NLS-2$
        ModifyMetadataTool.DcsArg malformed = ModifyMetadataTool.parseDcsArg(params);
        assertNull("a malformed 'dcs' arg yields no spec", malformed.spec); //$NON-NLS-1$
        assertNotNull("a malformed 'dcs' arg must be an error, not a silent drop", //$NON-NLS-1$
            malformed.error);
        assertTrue("the refusal must be a ToolResult error json", //$NON-NLS-1$
            malformed.error.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("the refusal must name the 'dcs' arg", malformed.error.contains("dcs")); //$NON-NLS-1$ //$NON-NLS-2$

        // A non-object JSON (an array) -> the same actionable error: the arg is a single spec object.
        params.put("dcs", "[1,2,3]"); //$NON-NLS-1$ //$NON-NLS-2$
        ModifyMetadataTool.DcsArg nonObject = ModifyMetadataTool.parseDcsArg(params);
        assertNull("a non-object 'dcs' arg yields no spec", nonObject.spec); //$NON-NLS-1$
        assertNotNull("a non-object 'dcs' arg must be an error", nonObject.error); //$NON-NLS-1$

        // A well-formed JSON object parses through (its members preserved, whitespace-trimmed).
        params.put("dcs", "  {\"dataSets\":[]}  "); //$NON-NLS-1$ //$NON-NLS-2$
        ModifyMetadataTool.DcsArg valid = ModifyMetadataTool.parseDcsArg(params);
        assertNull("a well-formed 'dcs' arg carries no error", valid.error); //$NON-NLS-1$
        assertNotNull("a well-formed 'dcs' object must parse", valid.spec); //$NON-NLS-1$
        assertTrue("the parsed object must carry its members", valid.spec.has("dataSets")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ===== method-reference guard dispatch (a job/subscription must be bound to an EXISTING, Exported,
    // Server method) =================================================================================
    //
    // validateMethodReference is scoped to exactly two type+property combos (ScheduledJob.methodName /
    // EventSubscription.handler); the actual parse/resolve/decide logic lives in MethodReferenceValidator
    // (covered by MethodReferenceValidatorTest) - these tests only prove the DISPATCH: which target/property
    // combo triggers the guard, which is a no-op, and that an empty value (clearing) is never validated.

    @Test
    public void testMethodReferenceGuardTriggersOnScheduledJobMethodName()
    {
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        String err = ModifyMetadataTool.validateMethodReference(null, null, job, "methodName", "NoDotHere"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a methodName value with no dot must be rejected", err); //$NON-NLS-1$
        assertTrue("the refusal must be a ToolResult error json", err.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the bad value", err.contains("NoDotHere")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMethodReferenceGuardTriggersOnEventSubscriptionHandler()
    {
        EventSubscription sub = MdClassFactory.eINSTANCE.createEventSubscription();
        String err = ModifyMetadataTool.validateMethodReference(null, null, sub, "handler", "NoDotHere"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a handler value with no dot must be rejected", err); //$NON-NLS-1$
        assertTrue("the refusal must name the bad value", err.contains("NoDotHere")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMethodReferenceGuardIsNoOpForOtherTargetTypes()
    {
        // Even a garbage (no-dot) value on a 'methodName'-NAMED property must be IGNORED on a non
        // -ScheduledJob target: the guard is scoped by TYPE first.
        DataProcessor dp = MdClassFactory.eINSTANCE.createDataProcessor();
        assertNull("the guard must not fire on a non-ScheduledJob/EventSubscription target", //$NON-NLS-1$
            ModifyMetadataTool.validateMethodReference(null, null, dp, "methodName", "NoDotHere")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMethodReferenceGuardIsNoOpForOtherPropertyNames()
    {
        // A ScheduledJob's 'comment' (or any property other than methodName) is not the guarded one.
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        assertNull("the guard must only fire on 'methodName', not any other property", //$NON-NLS-1$
            ModifyMetadataTool.validateMethodReference(null, null, job, "comment", "NoDotHere")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMethodReferenceGuardSkipsEmptyOrNullValue()
    {
        // An empty/null value is NEVER validated by this guard - it falls through to the pre-existing
        // generic-STRING policy (which itself rejects an empty value rather than "clearing" the
        // property; modify_metadata never clears a property on an empty value).
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        assertNull("an empty methodName value must not be validated by this guard", //$NON-NLS-1$
            ModifyMetadataTool.validateMethodReference(null, null, job, "methodName", "")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a null methodName value must not be validated by this guard", //$NON-NLS-1$
            ModifyMetadataTool.validateMethodReference(null, null, job, "methodName", null)); //$NON-NLS-1$
    }

    @Test
    public void testMethodReferenceGuardReportsMissingModule()
    {
        // A well-formed reference ("Module.Method") whose module does not exist in the (empty)
        // configuration must be rejected naming the module - end-to-end through
        // MethodReferenceValidator.validate, proving the dispatch threads project/config correctly.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        String err =
            ModifyMetadataTool.validateMethodReference(null, config, job, "methodName", "NoSuchModule.Foo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a reference to a non-existent module must be rejected", err); //$NON-NLS-1$
        assertTrue("the refusal must name the missing module", err.contains("NoSuchModule")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
