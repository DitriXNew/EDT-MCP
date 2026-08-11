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
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.CommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorForm;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataTool.FormHolder;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils.MetadataTypeInfo;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;
import com.google.gson.JsonArray;
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

    @Test
    public void testCanonicalMethodReferenceNormalizesGuardedCombos()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        CommonModule module = MdClassFactory.eINSTANCE.createCommonModule();
        module.setName("Calc"); //$NON-NLS-1$
        config.getCommonModules().add(module);
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        assertEquals("a validated methodName must serialize WITHOUT the type prefix", //$NON-NLS-1$
            "Calc.Add", ModifyMetadataTool.canonicalMethodReference(config, job, "methodName", "CommonModule.Calc.Add")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        EventSubscription sub = MdClassFactory.eINSTANCE.createEventSubscription();
        assertEquals("a validated handler must serialize WITH the English CommonModule prefix", //$NON-NLS-1$
            "CommonModule.Calc.Add", ModifyMetadataTool.canonicalMethodReference(config, sub, "handler", "Calc.Add")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Unguarded combo: value passes through unchanged.
        assertEquals("x.y", ModifyMetadataTool.canonicalMethodReference(config, module, "methodName", "x.y")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ===== XDTO package member payload dispatch guards (issue #183 stream 1) =========================
    //
    // An XDTO ObjectType/Property member is edited through the SAME 'properties' surface as an ordinary
    // mdclass member (there is no dedicated 'xdto' payload key, unlike 'dcs'/'template'), so the guard is
    // narrower: refuse a Role / membership content payload (neither applies to an XDTO member), then
    // require a non-empty 'properties'. The pure guard (xdtoMemberPayloadError) and the "member not
    // found" message builders are covered here; the live BM materialize + write + force-export is
    // covered by the E2E suite.

    @Test
    public void testXdtoMemberPayloadRefusesRoleAndContentPayloads()
    {
        String roleMix = ModifyMetadataTool.xdtoMemberPayloadError("XDTOPackage.MyPackage.ObjectType.MyType", //$NON-NLS-1$
            true, false, Collections.singletonList(prop("open", "true"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("an XDTO member FQN carrying a Role payload must be refused", roleMix); //$NON-NLS-1$
        assertTrue("the refusal must be a ToolResult error json", roleMix.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the offending FQN", //$NON-NLS-1$
            roleMix.contains("XDTOPackage.MyPackage.ObjectType.MyType")); //$NON-NLS-1$
        assertTrue("the refusal must point at 'properties'", roleMix.contains("properties")); //$NON-NLS-1$ //$NON-NLS-2$

        String contentMix = ModifyMetadataTool.xdtoMemberPayloadError(
            "XDTOPackage.MyPackage.Property.MyProp", false, true, //$NON-NLS-1$
            Collections.singletonList(prop("type", "string"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("an XDTO member FQN carrying a membership content payload must be refused", //$NON-NLS-1$
            contentMix);
    }

    @Test
    public void testXdtoMemberPayloadRequiresNonEmptyProperties()
    {
        String empty = ModifyMetadataTool.xdtoMemberPayloadError("XDTOPackage.MyPackage.ObjectType.MyType", //$NON-NLS-1$
            false, false, Collections.<JsonObject> emptyList());
        assertNotNull("an XDTO member modify with no 'properties' must be refused", empty); //$NON-NLS-1$
        assertTrue("the refusal must be a ToolResult error json", empty.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must mention the ObjectType flag vocabulary", empty.contains("open")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testXdtoMemberPayloadValidIsNotRefused()
    {
        String ok = ModifyMetadataTool.xdtoMemberPayloadError("XDTOPackage.MyPackage.ObjectType.MyType", //$NON-NLS-1$
            false, false, Collections.singletonList(prop("open", "true"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a lone 'properties' payload on an XDTO member FQN is not refused", ok); //$NON-NLS-1$
    }

    @Test
    public void testXdtoObjectTypeNotFoundErrorNamesTheType()
    {
        com.ditrix.edt.mcp.server.utils.XdtoWriter.MemberRef ref =
            com.ditrix.edt.mcp.server.utils.XdtoWriter.parseMemberRef("XDTOPackage.MyPackage.ObjectType.Missing"); //$NON-NLS-1$
        assertNotNull(ref);
        String err = ModifyMetadataTool.xdtoObjectTypeNotFoundError(ref);
        assertTrue("the refusal must be a ToolResult error json", err.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the missing ObjectType", err.contains("Missing")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must name the owning package", err.contains("XDTOPackage.MyPackage")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testXdtoPropertyNotFoundErrorDistinguishesPackageGlobalAndNested()
    {
        com.ditrix.edt.mcp.server.utils.XdtoWriter.MemberRef packageGlobal =
            com.ditrix.edt.mcp.server.utils.XdtoWriter.parseMemberRef("XDTOPackage.MyPackage.Property.Missing"); //$NON-NLS-1$
        String globalErr = ModifyMetadataTool.xdtoPropertyNotFoundError(packageGlobal);
        assertTrue("a package-global property error must name the package", //$NON-NLS-1$
            globalErr.contains("XDTOPackage.MyPackage"));
        assertFalse("a package-global property error must not mention an ObjectType owner", //$NON-NLS-1$
            globalErr.contains("ObjectType.")); //$NON-NLS-1$

        com.ditrix.edt.mcp.server.utils.XdtoWriter.MemberRef nested = com.ditrix.edt.mcp.server.utils.XdtoWriter
            .parseMemberRef("XDTOPackage.MyPackage.ObjectType.MyType.Property.Missing"); //$NON-NLS-1$
        String nestedErr = ModifyMetadataTool.xdtoPropertyNotFoundError(nested);
        assertTrue("a nested property error must name its owning ObjectType", //$NON-NLS-1$
            nestedErr.contains("ObjectType.MyType")); //$NON-NLS-1$
    }

    @Test
    public void testModifyMemberLookupToleratesYoSpelledObjectTypeName()
    {
        // issue #183 P2 #4: modifyXdtoMemberInTx resolves its target ObjectType/Property via
        // XdtoWriter.findObjectType / findProperty - the SAME shared lookup create_metadata's owner
        // lookup and delete_metadata's locateXdtoMember use. It now falls back to the yo-normalized
        // stored name on an exact miss, so a modify_metadata FQN that still spells an ObjectType's name
        // with the original "yo" (create_metadata normalizes 'yo'->'ye' in a member's own NAME by
        // default) resolves it instead of reporting "not found" for a member that in fact exists.
        com._1c.g5.v8.dt.xdto.model.Package pkg =
            com._1c.g5.v8.dt.xdto.model.XdtoFactory.eINSTANCE.createPackage();
        com._1c.g5.v8.dt.xdto.model.ObjectType type =
            com._1c.g5.v8.dt.xdto.model.XdtoFactory.eINSTANCE.createObjectType();
        // "Zakaz-e" (a Russian word for "order"), yo-normalized - the spelling create_metadata stores.
        type.setName(MetadataLanguageUtils.cp(0x0417, 0x0430, 0x043a, 0x0430, 0x0437, 0x0435));
        pkg.getObjects().add(type);

        // The modify_metadata FQN's target segment, still spelled with the original "yo".
        String yoSpelledName = MetadataLanguageUtils.cp(0x0417, 0x0430, 0x043a, 0x0430, 0x0437, 0x0451);
        assertEquals("modifyXdtoMemberInTx's own target resolution must tolerate a yo-spelled FQN segment", //$NON-NLS-1$
            type, com.ditrix.edt.mcp.server.utils.XdtoWriter.findObjectType(pkg, yoSpelledName));
    }

    // ==================== predefined-item dispatch (issue #293) ====================

    /**
     * The modify dispatch routes a 4-part predefined-item FQN to its dedicated branch via
     * {@link PredefinedWriter#parseRef}, the SAME recognizer create_metadata / delete_metadata use -
     * asserted runtime-free, mirroring the form-member recognizer precedent above.
     */
    @Test
    public void testPredefinedItemFqnRecognizedByModifyDispatch()
    {
        PredefinedWriter.PredefinedRef ref =
            PredefinedWriter.parseRef("ChartOfCharacteristicTypes.Properties.Predefined.Weight"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("ChartOfCharacteristicTypes", ref.ownerType); //$NON-NLS-1$
        assertEquals("Weight", ref.itemName); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsPredefinedItems()
    {
        String desc = new ModifyMetadataTool().getDescription();
        assertTrue("description should mention predefined items", desc.contains("PREDEFINED")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** 'name' is always refused (identity is the FQN leaf); 'parent' (a move) is refused on modify only. */
    @Test
    public void testPredefinedItemPropertiesGuardRules()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();

        JsonObject nameProp = new JsonObject();
        nameProp.addProperty("name", "name"); //$NON-NLS-1$ //$NON-NLS-2$
        nameProp.addProperty("value", "NewName"); //$NON-NLS-1$ //$NON-NLS-2$
        String nameErr =
            PredefinedWriter.parseProperties(java.util.List.of(nameProp), true, out);
        assertNotNull("renaming a predefined item must be refused", nameErr); //$NON-NLS-1$

        JsonObject parentProp = new JsonObject();
        parentProp.addProperty("name", "parent"); //$NON-NLS-1$ //$NON-NLS-2$
        parentProp.addProperty("value", "Folder"); //$NON-NLS-1$ //$NON-NLS-2$
        String parentErr =
            PredefinedWriter.parseProperties(java.util.List.of(parentProp), true, out);
        assertNotNull("moving to a different parent must be refused on modify", parentErr); //$NON-NLS-1$
        assertTrue(parentErr.contains("not yet supported")); //$NON-NLS-1$
    }

    // ==================== The form-retype authorization point (issue #295 review) ==================

    /** A write callback that counts its invocations, so "was it run?" is an assertion, not a hope. */
    private static final class RecordingWrite implements java.util.function.Supplier<String>
    {
        int calls;

        @Override
        public String get()
        {
            calls++;
            return WRITTEN;
        }
    }

    private static final String WRITTEN = "{\"written\":true}"; //$NON-NLS-1$

    /** A consent source that records whether it was ever asked. */
    private static final class RecordingConsent implements ModifyMetadataTool.ConsentRequester
    {
        private final ConsentDecision answer;
        int asked;

        RecordingConsent(ConsentDecision answer)
        {
            this.answer = answer;
        }

        @Override
        public ConsentDecision request(String toolName, ConsentPreview preview)
        {
            asked++;
            return answer;
        }
    }

    private static ConsentPreview retypePreview()
    {
        return new ConsentPreview("Change the data type of X", "subtitle", 1, //$NON-NLS-1$ //$NON-NLS-2$
            Collections.singletonList("valueType")); //$NON-NLS-1$
    }

    @Test
    public void testARetypeThatCannotBeAppliedIsRefusedWithoutEverPrompting()
    {
        // The deterministic refusal (stranded columns, an unresolvable main table) must reach the
        // caller AS IS. Asking first would answer a denial / timeout instead of the actionable error -
        // for a write that could never have been applied.
        RecordingConsent consent = new RecordingConsent(ConsentDecision.REJECT);
        RecordingWrite write = new RecordingWrite();

        String result = new ModifyMetadataTool(consent).gateFormRetype(retypePreview(),
            () -> REFUSAL, write);

        assertEquals("a refused retype must never raise the destructive prompt", 0, consent.asked); //$NON-NLS-1$
        assertEquals("a refused retype must not write", 0, write.calls); //$NON-NLS-1$
        assertEquals("the caller must get the validation error verbatim", REFUSAL, result); //$NON-NLS-1$
    }

    private static final String REFUSAL = "{\"error\":\"delete the columns first\"}"; //$NON-NLS-1$

    @Test
    public void testANonDestructiveChangeIsWrittenWithoutPrompting()
    {
        // "" = nothing destructive happens (not a retype at all, the member is absent, the attribute
        // is already a dynamic list): write, but never ask.
        RecordingConsent consent = new RecordingConsent(ConsentDecision.REJECT);
        RecordingWrite write = new RecordingWrite();

        String result = new ModifyMetadataTool(consent).gateFormRetype(retypePreview(), () -> "", write); //$NON-NLS-1$

        assertEquals("a benign change must not prompt", 0, consent.asked); //$NON-NLS-1$
        assertEquals("a benign change is written exactly once", 1, write.calls); //$NON-NLS-1$
        assertEquals(WRITTEN, result);
    }

    @Test
    public void testARealRetypeIsWrittenOnlyWhenConsentIsGranted()
    {
        for (ConsentDecision refused : new ConsentDecision[] {ConsentDecision.REJECT,
            ConsentDecision.TIMEOUT})
        {
            RecordingConsent consent = new RecordingConsent(refused);
            RecordingWrite write = new RecordingWrite();
            String result =
                new ModifyMetadataTool(consent).gateFormRetype(retypePreview(), () -> null, write);
            assertEquals("a real retype must be authorized (" + refused + ")", 1, consent.asked); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("a refused retype must not write (" + refused + ")", 0, write.calls); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(result.contains("error")); //$NON-NLS-1$
        }

        RecordingConsent allowed = new RecordingConsent(ConsentDecision.ALLOW);
        RecordingWrite write = new RecordingWrite();
        String ok = new ModifyMetadataTool(allowed).gateFormRetype(retypePreview(), () -> null, write);
        assertEquals("an allowed retype is written exactly once", 1, write.calls); //$NON-NLS-1$
        assertEquals(WRITTEN, ok);
    }

    /** The caller's normalization report: the pre-check only copies its SETTING, never its findings. */
    private static MdNameNormalizer.Report report()
    {
        return new MdNameNormalizer.Report(true);
    }

    private static ModifyMetadataTool neverAsking()
    {
        return new ModifyMetadataTool((name, preview) -> {
            throw new AssertionError("the preflight must decide without ever asking the gate"); //$NON-NLS-1$
        });
    }

    @Test
    public void testARetypeAwayFromACollectionIsRefusedWhileATableNeedsItsRows()
    {
        // The early return on "no columns" let a column-less collection be retyped to a scalar while a
        // table was still bound to it - the create path refuses to build that very shape, so the edit
        // path was the looser of the two (issue #295 review).
        //
        // The spec is one the type builder refuses on its own SHAPE, so removing the guard makes
        // this fail on the assertion below rather than crash in the platform type provider: a
        // revert has to red HERE, for this reason, or it proves nothing about this guard.
        String verdict = neverAsking().formRetypeVerdict(null, Version.LATEST,
            attributeWithATableBoundToIt(), Collections.singletonList(malformedTypeProperty()),
            report());

        assertNotNull("a retype that strands a table must be refused", verdict); //$NON-NLS-1$
        assertTrue("the refusal must name the table: " + verdict, verdict.contains("RowsTable")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("...and say how to clear it: " + verdict, //$NON-NLS-1$
            verdict.contains("delete_metadata")); //$NON-NLS-1$
    }

    /**
     * A column-less collection attribute {@code Rows} with a TABLE bound to it - the row consumer a
     * retype to a scalar would strand.
     */
    @SuppressWarnings("unchecked")
    private static EObject attributeWithATableBoundToIt()
    {
        EcoreFactory factory = EcoreFactory.eINSTANCE;
        EPackage pkg = formLikePackage();
        EClass attributeClass = (EClass)pkg.getEClassifier("FormAttribute"); //$NON-NLS-1$

        EClass dataPathClass = factory.createEClass();
        dataPathClass.setName("DataPath"); //$NON-NLS-1$
        EAttribute segments = factory.createEAttribute();
        segments.setName("segments"); //$NON-NLS-1$
        segments.setEType(EcorePackage.Literals.ESTRING);
        segments.setUpperBound(-1);
        dataPathClass.getEStructuralFeatures().add(segments);

        EClass tableClass = factory.createEClass();
        tableClass.setName("Table"); //$NON-NLS-1$
        EAttribute tableName = factory.createEAttribute();
        tableName.setName("name"); //$NON-NLS-1$
        tableName.setEType(EcorePackage.Literals.ESTRING);
        tableClass.getEStructuralFeatures().add(tableName);
        EReference tablePath = factory.createEReference();
        tablePath.setName("dataPath"); //$NON-NLS-1$
        tablePath.setEType(dataPathClass);
        tablePath.setContainment(true);
        tableClass.getEStructuralFeatures().add(tablePath);

        EClass formClass = factory.createEClass();
        formClass.setName("Form"); //$NON-NLS-1$
        EReference attributes = factory.createEReference();
        attributes.setName("attributes"); //$NON-NLS-1$
        attributes.setEType(attributeClass);
        attributes.setContainment(true);
        attributes.setUpperBound(-1);
        formClass.getEStructuralFeatures().add(attributes);
        EReference items = factory.createEReference();
        items.setName("items"); //$NON-NLS-1$
        items.setEType(tableClass);
        items.setContainment(true);
        items.setUpperBound(-1);
        formClass.getEStructuralFeatures().add(items);
        pkg.getEClassifiers().add(dataPathClass);
        pkg.getEClassifiers().add(tableClass);
        pkg.getEClassifiers().add(formClass);

        EObject form = new DynamicEObjectImpl(formClass);
        EObject attribute = new DynamicEObjectImpl(attributeClass);
        attribute.eSet(attributeClass.getEStructuralFeature("name"), "Rows"); //$NON-NLS-1$ //$NON-NLS-2$
        ((java.util.List<EObject>)form.eGet(attributes)).add(attribute);

        EObject table = new DynamicEObjectImpl(tableClass);
        table.eSet(tableName, "RowsTable"); //$NON-NLS-1$
        EObject path = new DynamicEObjectImpl(dataPathClass);
        ((java.util.List<String>)path.eGet(segments)).add("Rows"); //$NON-NLS-1$
        table.eSet(tablePath, path);
        ((java.util.List<EObject>)form.eGet(items)).add(table);
        return attribute;
    }

    @Test
    public void testTheRetypePreflightRefusesARetypeThatStrandsColumns()
    {
        // The DECISION itself, not just the order: a retype of a column-bearing attribute answers the
        // stranded-columns error, so the gate is never reached.
        String verdict = neverAsking().formRetypeVerdict(null, null, collectionAttribute("Price"), //$NON-NLS-1$
            Collections.singletonList(retypeToStringProperty()), report());

        assertNotNull("a retype that strands columns must be refused in the preflight", verdict); //$NON-NLS-1$
        assertTrue("the refusal must name the column so the caller can delete it", //$NON-NLS-1$
            verdict.contains("Price")); //$NON-NLS-1$
    }

    @Test
    public void testAnAbsentMemberIsNotPrompted()
    {
        assertEquals("an absent member must not prompt - the write answers 'not found'", "", //$NON-NLS-1$ //$NON-NLS-2$
            neverAsking().formRetypeVerdict(null, null, null,
                Collections.singletonList(retypeToStringProperty()), report()));
    }

    @Test
    public void testAnUnbuildableTypePayloadOnAColumnIsRefusedBeforeThePrompt()
    {
        // The gate was scoped to include Kind.COLUMN, but the type PAYLOAD was still parsed below it:
        // '...Attribute.Rows.Column.Price' with a type spec that cannot be built raised the
        // destructive prompt and answered a consent denial instead of the type error - for a write no
        // answer could have applied (issue #295 review). The preflight now runs the SAME preparation
        // the write runs, so every payload refusal lands above the gate.
        String verdict = neverAsking().formRetypeVerdict(null, Version.LATEST, plainColumn(),
            Collections.singletonList(malformedTypeProperty()), report());

        assertNotNull("an unbuildable type payload must be refused in the preflight", verdict); //$NON-NLS-1$
        assertTrue("the caller must get the TYPE error, not a consent denial: " + verdict, //$NON-NLS-1$
            verdict.contains("kind")); //$NON-NLS-1$
        assertFalse("a payload refusal must not read as a consent refusal", //$NON-NLS-1$
            verdict.contains("consent")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnbuildableTypePayloadOnACollectionAttributeIsRefusedBeforeThePrompt()
    {
        // The twin branch: the same request on the collection ATTRIBUTE itself.
        String verdict = neverAsking().formRetypeVerdict(null, Version.LATEST, collectionAttribute(),
            Collections.singletonList(malformedTypeProperty()), report());

        assertNotNull("an unbuildable type payload must be refused in the preflight", verdict); //$NON-NLS-1$
        assertTrue("the caller must get the TYPE error, not a consent denial: " + verdict, //$NON-NLS-1$
            verdict.contains("kind")); //$NON-NLS-1$
    }

    @Test
    public void testGivingACollectionTypeToADynamicListIsRefusedBeforeThePrompt()
    {
        // Writing only the valueType would leave the DynamicListExtInfo attached: the attribute would
        // export as a collection AND still count as a dynamic list, able to take columns while a stale
        // query described it. The tool refuses instead of dropping the caller's list configuration -
        // and, being decidable from the model, it refuses ABOVE the gate (issue #295 review).
        String verdict = neverAsking().formRetypeVerdict(null, Version.LATEST, dynamicListAttribute(),
            Collections.singletonList(retypeToCollectionProperty()), report());

        assertNotNull("a collection type on a dynamic list must be refused", verdict); //$NON-NLS-1$
        assertTrue("the refusal must say WHAT blocks it: " + verdict, //$NON-NLS-1$
            verdict.contains("DYNAMIC LIST") && verdict.contains("DynamicListExtInfo")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("...and HOW to clear it: " + verdict, //$NON-NLS-1$
            verdict.contains("delete_metadata")); //$NON-NLS-1$
    }

    @Test
    public void testARetypeThatOrphansExistingItemsIsRefusedBeforeThePrompt()
    {
        // Driven through formRetypeVerdict, the point the property branch's pre-check calls, NOT
        // through the FormElementWriter helper: computing the guard and never consulting it passes a
        // helper-level test, which is exactly the hole a revert exposed here.
        //
        // The link it still cannot pin, said plainly: formRetypePreflight's own call to
        // formRetypeVerdict needs a resolved FormEditContext, so removing THAT line would not turn
        // this red. One line, at the pre-check.
        String verdict = neverAsking().formRetypeVerdict(null, Version.LATEST,
            attributeWithAnItemBoundBelowIt(), Collections.singletonList(retypeToCollectionProperty()),
            report());

        assertNotNull("a retype that strands bound items must be refused", verdict); //$NON-NLS-1$
        assertTrue("the refusal must name the item: " + verdict, verdict.contains("NumberField")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("...and say how to clear it: " + verdict, //$NON-NLS-1$
            verdict.contains("delete_metadata") || verdict.contains("dataPath")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testARetypeToAReferenceKeepsItemsBoundBelowTheAttribute()
    {
        // The orphan scan fired on the mere PRESENCE of a tail, so ANY non-collection retype was
        // refused once something was bound below the attribute - including a retype to a REFERENCE
        // type, whose members live in the metadata and whose dotted paths createField deliberately
        // builds. The tool was refusing to edit a form into a shape it is happy to create (issue #295
        // review). The verdict is now the requested TYPE's.
        String verdict = neverAsking().formRetypeVerdict(null, null, attributeWithAnItemBoundBelowIt(),
            Collections.singletonList(retypeToRefProperty()), report());

        assertFalse("a retype to a reference must not be refused as orphaning: " + verdict, //$NON-NLS-1$
            verdict != null && verdict.contains("NumberField")); //$NON-NLS-1$
    }

    @Test
    public void testARetypeToAMemberlessTypeStillRefusesItemsBoundBelowTheAttribute()
    {
        // The other side of the same gate, so relaxing it cannot quietly disable the guard: a type
        // with NO addressable members really does strand the path. Both the primitive the scan always
        // knew and the platform type it did not (UUID), because the terminality question now comes
        // from MetadataTypeBuilder instead of a list kept here.
        for (JsonObject retype : new JsonObject[] {retypeToStringProperty(), retypeToUuidProperty()})
        {
            String verdict = neverAsking().formRetypeVerdict(null, null,
                attributeWithAnItemBoundBelowIt(), Collections.singletonList(retype), report());

            assertNotNull("a retype to a memberless type must still be refused", verdict); //$NON-NLS-1$
            assertTrue("the refusal must name the stranded item: " + verdict, //$NON-NLS-1$
                verdict.contains("NumberField")); //$NON-NLS-1$
        }
    }

    @Test
    public void testARetypeToACompositeCollectionAndReferenceKeepsItemsBoundBelowIt()
    {
        // The collection guard fired on "a collection is MENTIONED", so a composite
        // {ValueTable, CatalogRef.Products} was refused as soon as anything was bound below - even
        // though the REFERENCE half still owns the tail, which is precisely why createField accepts
        // 'Rows.Product.Description' for such a column. Creation allowed, editing forbade, one level
        // below the terminal-type case this branch already fixed (issue #295 review).
        String verdict = neverAsking().formRetypeVerdict(null, null, attributeWithAnItemBoundBelowIt(),
            Collections.singletonList(retypeToCollectionAndRefProperty()), report());

        assertFalse("a composite carrying a reference must not be refused as orphaning: " + verdict, //$NON-NLS-1$
            verdict != null && verdict.contains("NumberField")); //$NON-NLS-1$
    }

    @Test
    public void testARetypeToAPureCollectionStillRefusesItemsBoundBelowIt()
    {
        // The other side, so relaxing the guard cannot quietly disable it: with NO type that carries
        // members of its own, the bound path really is stranded - under a ValueTable / ValueTree a
        // dotted path addresses a COLUMN, and 'Number' is not one.
        String verdict = neverAsking().formRetypeVerdict(null, null, attributeWithAnItemBoundBelowIt(),
            Collections.singletonList(retypeToCollectionProperty()), report());

        assertNotNull("a pure collection retype must still be refused", verdict); //$NON-NLS-1$
        assertTrue("the refusal must name the stranded item: " + verdict, //$NON-NLS-1$
            verdict.contains("NumberField")); //$NON-NLS-1$
    }

    @Test
    public void testTheCompositeRuleIsTheSameOneTheNestedAddressClassifierApplies()
    {
        // Both sides of "may a tail survive here" must come from ONE rule, or creating and editing
        // drift apart again. Asserted directly on the exported predicate, in both languages.
        assertTrue("a reference carries members of its own", //$NON-NLS-1$
            FormElementWriter.carriesMembersOutsideThisModel(
                Arrays.asList("ValueTable", "CatalogRef.Products"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a pure collection does not", //$NON-NLS-1$
            FormElementWriter.carriesMembersOutsideThisModel(
                Arrays.asList("ValueTable", "ValueTree"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nor does a collection mixed with memberless types", //$NON-NLS-1$
            FormElementWriter.carriesMembersOutsideThisModel(
                Arrays.asList("ValueTable", "String", "UUID"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("the Russian collection name is read the same way", //$NON-NLS-1$
            FormElementWriter.carriesMembersOutsideThisModel(Arrays.asList(
                MetadataLanguageUtils.cp(0x0422, 0x0430, 0x0431, 0x043b, 0x0438, 0x0446, 0x0430, 0x0417,
                    0x043d, 0x0430, 0x0447, 0x0435, 0x043d, 0x0438, 0x0439), // TablicaZnachenij
                "DocumentRef.Invoice"))); //$NON-NLS-1$
    }

    // ===== a contained AdjustableBoolean flag is prepared as a plain boolean (#382) ===============
    //
    // A form attribute's view / edit (and a form item's userVisible, a form command's use) is not a
    // boolean attribute but a CONTAINED AdjustableBoolean whose nested `common` flag the wire boolean
    // addresses. The generic containment-ref filter used to drop it, so the property was not
    // assignable at all; it is now classified as its own kind and validated like a boolean. Driven
    // through formRetypeVerdict - the one headless entry into the SAME preparation the write runs.

    @Test
    public void testANonBooleanAdjustableFlagValueIsRefused()
    {
        String verdict = neverAsking().formRetypeVerdict(null, null, attributeWithAnAdjustableFlag(),
            Collections.singletonList(prop("view", "maybe")), report()); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("a non-boolean value for an AdjustableBoolean flag must be refused", verdict); //$NON-NLS-1$
        assertTrue("the refusal must name the bad value and the property: " + verdict, //$NON-NLS-1$
            verdict.contains("'maybe' is not a valid boolean for 'view'")); //$NON-NLS-1$
        assertTrue("...and say what IS valid: " + verdict, //$NON-NLS-1$
            verdict.contains("Use true or false")); //$NON-NLS-1$
        // The flag is a CONTAINMENT reference: were it still dropped by the generic containment
        // filter the refusal would be the "not assignable" one - a different defect entirely.
        assertFalse("the flag must be assignable, not refused as unknown: " + verdict, //$NON-NLS-1$
            verdict.contains("is not assignable")); //$NON-NLS-1$
    }

    @Test
    public void testBothPolaritiesOfAnAdjustableFlagAreAccepted()
    {
        // modify_metadata must be able to turn the flag OFF as well as on, so neither polarity may be
        // refused by the preparation.
        for (String value : new String[] {"true", "false"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertNull("'" + value + "' must be accepted for an AdjustableBoolean flag", //$NON-NLS-1$ //$NON-NLS-2$
                neverAsking().formRetypeVerdict(null, null, attributeWithAnAdjustableFlag(),
                    Collections.singletonList(prop("view", value)), report())); //$NON-NLS-1$
        }
    }

    /**
     * A form-attribute-like member carrying a {@code view} flag: a SINGLE-VALUED containment reference
     * to the REAL mdclass {@code AdjustableBoolean}, the very EClass the form metamodel's {@code view}
     * / {@code edit} / {@code userVisible} / {@code use} references target. The introspector
     * recognizes the flag BY THAT TYPE, so a synthetic look-alike would diverge from production
     * exactly where the recognition happens (issue #382). The real EClass is only referred to, never
     * added to the synthetic package - that would reparent it out of MdClassPackage for the whole JVM.
     */
    private static EObject attributeWithAnAdjustableFlag()
    {
        EcoreFactory factory = EcoreFactory.eINSTANCE;
        EPackage pkg = factory.createEPackage();
        pkg.setName("formlike"); //$NON-NLS-1$
        pkg.setNsPrefix("formlike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/adjustableflag"); //$NON-NLS-1$

        EClass attributeClass = factory.createEClass();
        attributeClass.setName("FormAttribute"); //$NON-NLS-1$
        EAttribute attributeName = factory.createEAttribute();
        attributeName.setName("name"); //$NON-NLS-1$
        attributeName.setEType(EcorePackage.Literals.ESTRING);
        attributeClass.getEStructuralFeatures().add(attributeName);
        EReference view = factory.createEReference();
        view.setName("view"); //$NON-NLS-1$
        view.setEType(MdClassPackage.Literals.ADJUSTABLE_BOOLEAN);
        view.setContainment(true);
        attributeClass.getEStructuralFeatures().add(view);
        pkg.getEClassifiers().add(attributeClass);

        EObject attribute = new DynamicEObjectImpl(attributeClass);
        attribute.eSet(attributeName, "Flag"); //$NON-NLS-1$
        return attribute;
    }

    /** {name:'type', value:{types:[{kind:'ValueTable'},{kind:'Ref', ref:'Catalog.Products'}]}}. */
    private static JsonObject retypeToCollectionAndRefProperty()
    {
        JsonObject collection = new JsonObject();
        collection.addProperty("kind", "ValueTable"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject reference = new JsonObject();
        reference.addProperty("kind", "Ref"); //$NON-NLS-1$ //$NON-NLS-2$
        reference.addProperty("ref", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray types = new JsonArray();
        types.add(collection);
        types.add(reference);
        JsonObject spec = new JsonObject();
        spec.add("types", types); //$NON-NLS-1$
        JsonObject prop = new JsonObject();
        prop.addProperty("name", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.add("value", spec); //$NON-NLS-1$
        return prop;
    }

    /** {name:'type', value:{types:[{kind:'Ref', ref:'Catalog.Products'}]}} - a type that HAS members. */
    private static JsonObject retypeToRefProperty()
    {
        JsonObject kind = new JsonObject();
        kind.addProperty("kind", "Ref"); //$NON-NLS-1$ //$NON-NLS-2$
        kind.addProperty("ref", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        return typeProperty(kind);
    }

    /** {name:'type', value:{types:[{kind:'UUID'}]}} - a platform type with no members at all. */
    private static JsonObject retypeToUuidProperty()
    {
        JsonObject kind = new JsonObject();
        kind.addProperty("kind", "UUID"); //$NON-NLS-1$ //$NON-NLS-2$
        return typeProperty(kind);
    }

    /** Wraps one type item into a {name:'type', value:{types:[item]}} property. */
    private static JsonObject typeProperty(JsonObject typeItem)
    {
        JsonArray types = new JsonArray();
        types.add(typeItem);
        JsonObject spec = new JsonObject();
        spec.add("types", types); //$NON-NLS-1$
        JsonObject prop = new JsonObject();
        prop.addProperty("name", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.add("value", spec); //$NON-NLS-1$
        return prop;
    }

    /**
     * A form attribute {@code Object} living on a form that also holds a field bound to
     * {@code Object.Number} - the shape a retype to a collection would leave pointing at nothing.
     */
    @SuppressWarnings("unchecked")
    private static EObject attributeWithAnItemBoundBelowIt()
    {
        EcoreFactory factory = EcoreFactory.eINSTANCE;
        EPackage pkg = formLikePackage();
        EClass attributeClass = (EClass)pkg.getEClassifier("FormAttribute"); //$NON-NLS-1$

        EClass dataPathClass = factory.createEClass();
        dataPathClass.setName("DataPath"); //$NON-NLS-1$
        EAttribute segments = factory.createEAttribute();
        segments.setName("segments"); //$NON-NLS-1$
        segments.setEType(EcorePackage.Literals.ESTRING);
        segments.setUpperBound(-1);
        dataPathClass.getEStructuralFeatures().add(segments);

        EClass itemClass = factory.createEClass();
        itemClass.setName("FormField"); //$NON-NLS-1$
        EAttribute itemName = factory.createEAttribute();
        itemName.setName("name"); //$NON-NLS-1$
        itemName.setEType(EcorePackage.Literals.ESTRING);
        itemClass.getEStructuralFeatures().add(itemName);
        EReference itemPath = factory.createEReference();
        itemPath.setName("dataPath"); //$NON-NLS-1$
        itemPath.setEType(dataPathClass);
        itemPath.setContainment(true);
        itemClass.getEStructuralFeatures().add(itemPath);
        EReference nested = factory.createEReference();
        nested.setName("items"); //$NON-NLS-1$
        nested.setEType(itemClass);
        nested.setContainment(true);
        nested.setUpperBound(-1);
        itemClass.getEStructuralFeatures().add(nested);

        EClass formClass = factory.createEClass();
        formClass.setName("Form"); //$NON-NLS-1$
        EReference attributes = factory.createEReference();
        attributes.setName("attributes"); //$NON-NLS-1$
        attributes.setEType(attributeClass);
        attributes.setContainment(true);
        attributes.setUpperBound(-1);
        formClass.getEStructuralFeatures().add(attributes);
        EReference items = factory.createEReference();
        items.setName("items"); //$NON-NLS-1$
        items.setEType(itemClass);
        items.setContainment(true);
        items.setUpperBound(-1);
        formClass.getEStructuralFeatures().add(items);
        pkg.getEClassifiers().add(dataPathClass);
        pkg.getEClassifiers().add(itemClass);
        pkg.getEClassifiers().add(formClass);

        EObject form = new DynamicEObjectImpl(formClass);
        EObject attribute = new DynamicEObjectImpl(attributeClass);
        attribute.eSet(attributeClass.getEStructuralFeature("name"), "Object"); //$NON-NLS-1$ //$NON-NLS-2$
        ((java.util.List<EObject>)form.eGet(attributes)).add(attribute);

        EObject field = new DynamicEObjectImpl(itemClass);
        field.eSet(itemName, "NumberField"); //$NON-NLS-1$
        EObject path = new DynamicEObjectImpl(dataPathClass);
        ((java.util.List<String>)path.eGet(segments)).add("Object"); //$NON-NLS-1$
        ((java.util.List<String>)path.eGet(segments)).add("Number"); //$NON-NLS-1$
        field.eSet(itemPath, path);
        ((java.util.List<EObject>)form.eGet(items)).add(field);
        return attribute;
    }

    @Test
    public void testACollectionTypeOnAPlainAttributeIsNotBlockedByThatGuard()
    {
        // The guard is scoped to the conflict: an attribute that is NOT a dynamic list still reaches
        // the type builder (here: the headless provider limit), never the list refusal.
        String verdict = neverAsking().formRetypeVerdict(null, Version.LATEST, collectionAttribute(),
            Collections.singletonList(retypeToCollectionProperty()), report());

        assertNotNull(verdict);
        assertFalse("a plain attribute must not be refused as a dynamic list: " + verdict, //$NON-NLS-1$
            verdict.contains("DYNAMIC LIST")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnbuildableDynamicListTypeIsRefusedBeforeThePrompt()
    {
        // The conversion sets the ext-info classifier and THEN builds the value type, so a version
        // whose DynamicList type cannot be built refuses after a half-write; the version used to be
        // resolved only inside the write callback (issue #295 review). A null version cannot produce
        // the type, which is exactly the condition being lifted.
        //
        // Scope, stated because the comment here claimed more: this pins the VERDICT. That
        // configureDynamicListQuery hands this verdict to gateFormRetype as its pre-check is one
        // production line that a headless test cannot reach (it needs a resolved FormEditContext).
        String verdict = ModifyMetadataTool.dynamicListRetypeVerdict(null, null, formSupportingLists(),
            collectionAttribute(), null);

        assertNotNull("an unbuildable DynamicList type must be refused before the prompt", verdict); //$NON-NLS-1$
        assertTrue("the caller must get the type error, not a consent denial: " + verdict, //$NON-NLS-1$
            verdict.contains("DynamicList value type")); //$NON-NLS-1$
        assertTrue("...and be told nothing changed: " + verdict, //$NON-NLS-1$
            verdict.contains("Nothing")); //$NON-NLS-1$
    }

    @Test
    public void testTheDynamicListPreflightRefusesAnUnresolvableMainTable()
    {
        // The main table used to be resolved only inside the write callback, so a nonexistent one
        // raised the conversion prompt FIRST and answered the resolution failure only after ALLOW.
        String verdict = ModifyMetadataTool.dynamicListRetypeVerdict(null, Version.LATEST,
            formSupportingLists(), collectionAttribute(), "Catalog.NoSuchObject"); //$NON-NLS-1$

        assertNotNull("an unresolvable main table must be refused before the prompt", verdict); //$NON-NLS-1$
        assertTrue("the refusal must be the main-table one, not a consent denial", //$NON-NLS-1$
            verdict.contains("Cannot resolve the main table")); //$NON-NLS-1$
        assertTrue("it must echo the offending FQN", verdict.contains("Catalog.NoSuchObject")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAFormThatCannotHoldADynamicListIsRefusedBeforeThePrompt()
    {
        // The last deterministic refusal that still lived below the gate: a form metamodel with no
        // DynamicListExtInfo classifier cannot be converted whatever the user answers.
        String verdict = ModifyMetadataTool.dynamicListRetypeVerdict(null, Version.LATEST, formWithoutLists(),
            collectionAttribute(), null);

        assertNotNull("a form that cannot hold a list must be refused before the prompt", verdict); //$NON-NLS-1$
        assertTrue("the refusal must name the missing classifier: " + verdict, //$NON-NLS-1$
            verdict.contains("DynamicListExtInfo")); //$NON-NLS-1$
    }

    @Test
    public void testAnAbsentAttributeIsNotPromptedForAConversion()
    {
        assertEquals("an absent attribute must not prompt", "", //$NON-NLS-1$ //$NON-NLS-2$
            ModifyMetadataTool.dynamicListRetypeVerdict(null, Version.LATEST, formSupportingLists(),
                null, null));
        // The "reach the gate" case cannot be produced headlessly: the LAST pre-check builds the
        // DynamicList value type, and a unit test has no platform type provider (the limit
        // MetadataTypeBuilderTest documents), so a convertible attribute always stops on that check -
        // which is itself asserted by testAnUnbuildableDynamicListTypeIsRefusedBeforeThePrompt.
    }

    /** A form model whose metamodel CAN represent a dynamic list. */
    private static EObject formSupportingLists()
    {
        return formModel(true);
    }

    /** A form model whose metamodel cannot - the conversion is impossible in it. */
    private static EObject formWithoutLists()
    {
        return formModel(false);
    }

    private static EObject formModel(boolean withDynamicListExtInfo)
    {
        EcoreFactory factory = EcoreFactory.eINSTANCE;
        EPackage pkg = factory.createEPackage();
        pkg.setName("formlike"); //$NON-NLS-1$
        pkg.setNsPrefix("formlike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/formmodel"); //$NON-NLS-1$
        EClass formClass = factory.createEClass();
        formClass.setName("Form"); //$NON-NLS-1$
        pkg.getEClassifiers().add(formClass);
        if (withDynamicListExtInfo)
        {
            EClass extInfo = factory.createEClass();
            extInfo.setName("DynamicListExtInfo"); //$NON-NLS-1$
            pkg.getEClassifiers().add(extInfo);
        }
        return new DynamicEObjectImpl(formClass);
    }

    /**
     * {name:'type', value:{types:[{}]}} - a type payload the builder REFUSES on its own shape, so the
     * refusal is decidable without a platform type provider (the headless test has none, exactly as
     * {@code MetadataTypeBuilderTest} documents). In production the same pass answers the reviewer's
     * case, an unknown {@code kind}; both are refusals no consent answer can turn into a write.
     */
    private static JsonObject malformedTypeProperty()
    {
        JsonArray types = new JsonArray();
        types.add(new JsonObject());
        JsonObject spec = new JsonObject();
        spec.add("types", types); //$NON-NLS-1$
        JsonObject prop = new JsonObject();
        prop.addProperty("name", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.add("value", spec); //$NON-NLS-1$
        return prop;
    }

    /** {name:'type', value:{types:[{kind:'String'}]}} - the retype that strands a column. */
    private static JsonObject retypeToStringProperty()
    {
        JsonObject kind = new JsonObject();
        kind.addProperty("kind", "String"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray types = new JsonArray();
        types.add(kind);
        JsonObject spec = new JsonObject();
        spec.add("types", types); //$NON-NLS-1$
        JsonObject prop = new JsonObject();
        prop.addProperty("name", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.add("value", spec); //$NON-NLS-1$
        return prop;
    }

    /**
     * A dynamic-EMF form attribute carrying {@code valueType} + the named {@code columns} - the shape
     * both preflights read reflectively. The {@code valueType} reference targets a classifier NAMED
     * {@code TypeDescription}, because that is exactly how {@code MetadataPropertyIntrospector}
     * recognises a data-type property; without it the preparation would refuse the property as
     * unknown and the test would assert the wrong refusal.
     */
    @SuppressWarnings("unchecked")
    private static EObject collectionAttribute(String... columnNames)
    {
        EClass attributeClass = formLikePackage().getEClassifier("FormAttribute") instanceof EClass //$NON-NLS-1$
            ? (EClass)formLikePackage().getEClassifier("FormAttribute") : null; //$NON-NLS-1$
        EObject attribute = new DynamicEObjectImpl(attributeClass);
        EStructuralFeature columns = attributeClass.getEStructuralFeature("columns"); //$NON-NLS-1$
        EClass columnClass = (EClass)formLikePackage().getEClassifier("FormAttributeColumn"); //$NON-NLS-1$
        for (String name : columnNames)
        {
            EObject column = new DynamicEObjectImpl(columnClass);
            column.eSet(columnClass.getEStructuralFeature("name"), name); //$NON-NLS-1$
            ((java.util.List<EObject>)attribute.eGet(columns)).add(column);
        }
        return attribute;
    }

    /** An attribute already configured as a dynamic list: it carries a {@code DynamicListExtInfo}. */
    private static EObject dynamicListAttribute()
    {
        EPackage pkg = formLikePackage();
        EClass attributeClass = (EClass)pkg.getEClassifier("FormAttribute"); //$NON-NLS-1$
        EObject attribute = new DynamicEObjectImpl(attributeClass);
        attribute.eSet(attributeClass.getEStructuralFeature("name"), "List"); //$NON-NLS-1$ //$NON-NLS-2$
        attribute.eSet(attributeClass.getEStructuralFeature("extInfo"), //$NON-NLS-1$
            new DynamicEObjectImpl((EClass)pkg.getEClassifier("DynamicListExtInfo"))); //$NON-NLS-1$
        return attribute;
    }

    /** {name:'type', value:{types:[{kind:'ValueTable'}]}} - the retype the dynamic-list guard blocks. */
    private static JsonObject retypeToCollectionProperty()
    {
        JsonObject kind = new JsonObject();
        kind.addProperty("kind", "ValueTable"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray types = new JsonArray();
        types.add(kind);
        JsonObject spec = new JsonObject();
        spec.add("types", types); //$NON-NLS-1$
        JsonObject prop = new JsonObject();
        prop.addProperty("name", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.add("value", spec); //$NON-NLS-1$
        return prop;
    }

    /** A COLUMN of a collection attribute: it owns no columns of its own, but is retyped like one. */
    private static EObject plainColumn()
    {
        return new DynamicEObjectImpl(
            (EClass)formLikePackage().getEClassifier("FormAttributeColumn")); //$NON-NLS-1$
    }

    /** The form-like metamodel both fixtures instantiate (attribute + column + its data type). */
    private static EPackage formLikePackage()
    {
        EcoreFactory factory = EcoreFactory.eINSTANCE;
        EPackage pkg = factory.createEPackage();
        pkg.setName("formlike"); //$NON-NLS-1$
        pkg.setNsPrefix("formlike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/formretype"); //$NON-NLS-1$

        EClass typeDescription = factory.createEClass();
        typeDescription.setName("TypeDescription"); //$NON-NLS-1$

        EClass columnClass = factory.createEClass();
        columnClass.setName("FormAttributeColumn"); //$NON-NLS-1$
        EAttribute columnName = factory.createEAttribute();
        columnName.setName("name"); //$NON-NLS-1$
        columnName.setEType(EcorePackage.Literals.ESTRING);
        columnClass.getEStructuralFeatures().add(columnName);
        columnClass.getEStructuralFeatures().add(typeReference(factory, typeDescription));

        EClass listExtInfo = factory.createEClass();
        listExtInfo.setName("DynamicListExtInfo"); //$NON-NLS-1$

        EClass attributeClass = factory.createEClass();
        attributeClass.setName("FormAttribute"); //$NON-NLS-1$
        EAttribute attributeName = factory.createEAttribute();
        attributeName.setName("name"); //$NON-NLS-1$
        attributeName.setEType(EcorePackage.Literals.ESTRING);
        attributeClass.getEStructuralFeatures().add(attributeName);
        EReference columns = factory.createEReference();
        columns.setName("columns"); //$NON-NLS-1$
        columns.setEType(columnClass);
        columns.setContainment(true);
        columns.setUpperBound(-1);
        attributeClass.getEStructuralFeatures().add(columns);
        EReference extInfo = factory.createEReference();
        extInfo.setName("extInfo"); //$NON-NLS-1$
        extInfo.setEType(EcorePackage.Literals.EOBJECT);
        extInfo.setContainment(true);
        attributeClass.getEStructuralFeatures().add(extInfo);
        attributeClass.getEStructuralFeatures().add(typeReference(factory, typeDescription));

        pkg.getEClassifiers().add(typeDescription);
        pkg.getEClassifiers().add(columnClass);
        pkg.getEClassifiers().add(listExtInfo);
        pkg.getEClassifiers().add(attributeClass);
        return pkg;
    }

    /** The {@code valueType} containment reference the type preparation writes into. */
    private static EReference typeReference(EcoreFactory factory, EClass typeDescription)
    {
        EReference valueType = factory.createEReference();
        valueType.setName("valueType"); //$NON-NLS-1$
        valueType.setEType(typeDescription);
        valueType.setContainment(true);
        return valueType;
    }
}
