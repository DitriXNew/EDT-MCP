/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateTableCell;
import com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateTableRow;
import com._1c.g5.v8.dt.dcs.model.common.DataCompositionAreaTemplateType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaGroupTemplate;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTemplateDescription;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;

/**
 * #662: typed writes of area templates and grouping bindings through {@link DcsSchemaWriter}
 * (prepare + apply), at the addresses the read prints and in the root body. On master every one of
 * these answered "type='schema' targets the bare root".
 */
public class DcsTemplateWriterTest
{
    private static final String ROOT = "Report.Sales"; //$NON-NLS-1$
    private static final String ROWS = "\"rows\":[{\"cells\":[{\"text\":\"Total\",\"appearance\":{\"HorizontalAlign\":\"Right\"}},"
        + "{\"parameter\":\"Amount\"}]}]"; //$NON-NLS-1$

    @Test
    public void testUpsertAtTheCollectionCreatesATemplateByName()
    {
        DataCompositionSchema schema = schema();
        DcsSchemaWriter.Result result = write(schema, "upsert", ROOT + "#/templates",
            "{\"name\":\"Totals\"," + ROWS + ",\"parameters\":[{\"name\":\"Amount\",\"expression\":\"Amount\"}]}");
        success(result);
        assertEquals(1, schema.getTemplates().size());
        assertEquals("Totals", schema.getTemplates().get(0).getName());
        assertEquals("templates=1, groupTemplates=0, groupHeaderTemplates=0", result.templates().counts());
        assertTrue(result.templates().warnings().toString(), result.templates().warnings().isEmpty());
    }

    @Test
    public void testPartialUpdateChangesOneTemplateAndKeepsTheOthersObjects()
    {
        DataCompositionSchema schema = schemaWith("A", "B");
        DataCompositionSchemaTemplateDescription untouched = schema.getTemplates().get(1);
        DataCompositionSchemaTemplateDescription before = schema.getTemplates().get(0);
        success(write(schema, "update", ROOT + "#/templates/A",
            "{\"parameters\":[{\"name\":\"Amount\",\"expression\":\"Amount * 2\"}]}"));
        assertSame(untouched, schema.getTemplates().get(1));
        assertNotSame(before, schema.getTemplates().get(0));
        assertEquals(2, ((DataCompositionAreaTemplateTableRow)schema.getTemplates().get(0).getTemplate().getItems()
            .get(0)).getCells().size());
        assertEquals("Amount * 2", ((com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionExpressionAreaParameter)
            schema.getTemplates().get(0).getParameters().get(0)).getExpression());
    }

    @Test
    public void testUpdateRenamesAnUnboundTemplateAndRefusesABoundOne()
    {
        DataCompositionSchema schema = schemaWith("A", "B");
        success(write(schema, "update", ROOT + "#/templates/A", "{\"name\":\"Renamed\"}"));
        assertEquals("Renamed", schema.getTemplates().get(0).getName());

        schema.getGroupHeaderTemplates().add(binding("Customer", "B"));
        String refused = failure(write(schema, "update", ROOT + "#/templates/B", "{\"name\":\"B2\"}"));
        assertContains(refused, ROOT + "#/groupHeaderTemplates/0");
        assertEquals("B", schema.getTemplates().get(1).getName());

        String collision = failure(write(schema, "update", ROOT + "#/templates/Renamed", "{\"name\":\"b\"}"));
        assertContains(collision, "collides");
        String numeric = failure(write(schema, "upsert", ROOT + "#/templates", "{\"name\":\"7\"}"));
        assertContains(numeric, "reads as an index");
    }

    @Test
    public void testReplaceRebuildsTheNodeAndRemoveRefusesABoundTemplate()
    {
        DataCompositionSchema schema = schemaWith("A", "B");
        success(write(schema, "replace", ROOT + "#/templates/A", "{\"rows\":[]}"));
        DataCompositionSchemaTemplateDescription replaced = schema.getTemplates().get(0);
        assertEquals("A", replaced.getName());
        assertTrue(replaced.getParameters().isEmpty());
        assertTrue(replaced.getTemplate().getItems().isEmpty());

        schema.getGroupTemplates().add(binding("Customer", "B"));
        String refused = failure(write(schema, "remove", ROOT + "#/templates/B", null));
        assertContains(refused, ROOT + "#/groupTemplates/0");
        assertContains(refused, "still refer to it");
        assertEquals(2, schema.getTemplates().size());

        success(write(schema, "remove", ROOT + "#/groupTemplates/0", null));
        success(write(schema, "remove", ROOT + "#/templates/B", null));
        assertEquals(1, schema.getTemplates().size());
    }

    @Test
    public void testRootBatchWritesATemplateAndItsBindingInOneCall()
    {
        DataCompositionSchema schema = schema();
        DcsSchemaWriter.Result result = write(schema, "upsert", ROOT, "{\"templates\":[{\"name\":\"T\"," + ROWS
            + ",\"parameters\":[{\"name\":\"Amount\",\"expression\":\"Amount\"}]}],"
            + "\"groupTemplates\":[{\"groupName\":\"Customer\",\"templateType\":\"Footer\",\"template\":\"T\"}]}");
        success(result);
        assertEquals(1, schema.getTemplates().size());
        assertEquals(1, schema.getGroupTemplates().size());
        assertEquals(DataCompositionAreaTemplateType.FOOTER, schema.getGroupTemplates().get(0).getTemplateType());
        assertEquals("templates=1, groupTemplates=1, groupHeaderTemplates=0", result.templates().counts());
    }

    @Test
    public void testRootReplaceClearsTemplatesAndBindingsTheBodyOmits()
    {
        DataCompositionSchema schema = schemaWith("A", "B");
        schema.getGroupTemplates().add(binding("Customer", "A"));
        success(write(schema, "replace", ROOT, "{\"templates\":[{\"name\":\"Only\"}]}"));
        assertEquals(1, schema.getTemplates().size());
        assertEquals("Only", schema.getTemplates().get(0).getName());
        assertTrue(schema.getGroupTemplates().isEmpty());
    }

    @Test
    public void testBindingUpsertKeysByGroupingAndTemplateType()
    {
        DataCompositionSchema schema = schemaWith("A", "B");
        schema.getGroupTemplates().add(binding("Customer", "A"));
        success(write(schema, "upsert", ROOT + "#/groupTemplates", "{\"groupName\":\"customer\",\"template\":\"B\"}"));
        assertEquals(1, schema.getGroupTemplates().size());
        assertEquals("B", schema.getGroupTemplates().get(0).getTemplate());

        success(write(schema, "upsert", ROOT + "#/groupTemplates",
            "{\"groupName\":\"Customer\",\"templateType\":\"Footer\",\"template\":\"A\"}"));
        assertEquals(2, schema.getGroupTemplates().size());
    }

    @Test
    public void testGroupNameClearsGroupFieldsAndBothAreRefused()
    {
        DataCompositionSchema schema = schemaWith("A");
        DataCompositionSchemaGroupTemplate byFields = DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate();
        byFields.getGroupFields().add(field("Customer"));
        byFields.setTemplate("A");
        schema.getGroupTemplates().add(byFields);
        success(write(schema, "update", ROOT + "#/groupTemplates/0", "{\"groupName\":\"Customer\"}"));
        assertEquals("Customer", schema.getGroupTemplates().get(0).getGroupName());
        assertTrue(schema.getGroupTemplates().get(0).getGroupFields().isEmpty());

        assertContains(failure(write(schema, "update", ROOT + "#/groupTemplates/0",
            "{\"groupName\":\"X\",\"groupFields\":[\"Y\"]}")), "both groupName and groupFields");
    }

    @Test
    public void testCellsPatchChangesOneCellAndMergesItsAppearance()
    {
        DataCompositionSchema schema = schemaWith("A");
        success(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"Changed\"}]}"));
        DataCompositionAreaTemplateTableCell first = cell(schema, 0, 0);
        assertEquals("Changed", ((LocalString)((com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateField)
            first.getItem().get(0)).getValue()).getContent().get("en"));
        // The content patch keeps the cell's appearance.
        assertEquals(1, first.getAppearance().getItems().size());
        assertEquals("HorizontalAlign", first.getAppearance().getItems().get(0).getParameter().getValue());

        success(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"appearance\":{\"VerticalAlign\":\"Top\"}}]}"));
        first = cell(schema, 0, 0);
        assertEquals(2, first.getAppearance().getItems().size());
        assertEquals("HorizontalAlign", first.getAppearance().getItems().get(0).getParameter().getValue());
        assertEquals("VerticalAlign", first.getAppearance().getItems().get(1).getParameter().getValue());
        assertEquals("Changed", ((LocalString)((com._1c.g5.v8.dt.dcs.model.areaTemplate.DataCompositionAreaTemplateField)
            first.getItem().get(0)).getValue()).getContent().get("en"));

        String outside = failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":5,\"text\":\"x\"}]}"));
        assertContains(outside, "1 row(s) with 2 cell(s)");
    }

    @Test
    public void testACellsPatchKeepsTheClassOfStoredAppearanceItDoesNotWrite()
    {
        DataCompositionSchema schema = schemaWith("A");
        // A stored dotted-key holder of the settings class, with a nested sibling of that class.
        SettingsParameterValue holder = settingsItem("BorderStyle");
        holder.setUse(false);
        holder.getNestedParameterValues().add(settingsItem("BorderStyle.Right"));
        cell(schema, 0, 0).getAppearance().getItems().add(holder);

        success(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"appearance\":{"
                + "\"BorderStyle.Left\":{\"kind\":\"line\",\"value\":{\"width\":2,\"style\":\"Solid\"}}}}]}"));
        DataCompositionAreaTemplateTableCell first = cell(schema, 0, 0);
        assertEquals(2, first.getAppearance().getItems().size());
        assertEquals("HorizontalAlign", first.getAppearance().getItems().get(0).getParameter().getValue());
        DataCompositionParameterValue kept = first.getAppearance().getItems().get(1);
        assertTrue(kept.eClass().getName(), kept instanceof SettingsParameterValue);
        assertFalse(kept.isUse());
        assertEquals(2, kept.getNestedParameterValues().size());
        DataCompositionParameterValue right = kept.getNestedParameterValues().get(0);
        assertEquals("BorderStyle.Right", right.getParameter().getValue());
        assertTrue(right.eClass().getName(), right instanceof SettingsParameterValue);
        // The value this call wrote is the core item EDT's template designer writes.
        DataCompositionParameterValue left = kept.getNestedParameterValues().get(1);
        assertEquals("BorderStyle.Left", left.getParameter().getValue());
        assertFalse(left.eClass().getName(), left instanceof SettingsParameterValue);
    }

    @Test
    public void testACellsPatchKeepsAStoredSiblingThatCarriesSettingsMembers()
    {
        DataCompositionSchema schema = schemaWith("A");
        SettingsParameterValue holder = settingsItem("BorderStyle");
        holder.setUse(false);
        SettingsParameterValue right = settingsItem("BorderStyle.Right");
        right.setUserSettingID("stored-id");
        holder.getNestedParameterValues().add(right);
        cell(schema, 0, 0).getAppearance().getItems().add(holder);

        success(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"appearance\":{"
                + "\"BorderStyle.Left\":{\"kind\":\"line\",\"value\":{\"width\":2,\"style\":\"Solid\"}}}}]}"));
        DataCompositionParameterValue stored = cell(schema, 0, 0).getAppearance().getItems().get(1)
            .getNestedParameterValues().get(0);
        assertTrue(stored.eClass().getName(), stored instanceof SettingsParameterValue);
        assertEquals("stored-id", ((SettingsParameterValue)stored).getUserSettingID());

        // A settings member this call writes is still refused.
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":1,\"appearance\":{\"items\":[{\"parameter\":"
                + "{\"kind\":\"parameter\",\"value\":\"BorderStyle\"},\"nestedParameterValues\":[{\"parameter\":"
                + "{\"kind\":\"parameter\",\"value\":\"BorderStyle.Left\"},"
                + "\"value\":{\"kind\":\"line\",\"value\":{\"width\":2,\"style\":\"Solid\"}},"
                + "\"userSettingID\":\"new-id\"}]}]}}]}")), "carries viewMode, userSettingID");
    }

    @Test
    public void testACellsPatchPairsSameNamedStoredItemsByPosition() throws Exception
    {
        // Two same-named items in one appearance list: EDT's Template.dcs reader keeps both, so a
        // stored cell can hold them (hand-edited XML, or the lossless XML channel).
        DataCompositionSchema seeded = schemaWith("A");
        DataCompositionParameterValue first = coreItem("BorderStyle");
        first.setUse(false);
        first.getNestedParameterValues().add(coreItem("BorderStyle.Left"));
        SettingsParameterValue second = settingsItem("BorderStyle");
        second.setUse(false);
        second.setUserSettingID("second-id");
        second.getNestedParameterValues().add(coreItem("BorderStyle.Right"));
        cell(seeded, 0, 0).getAppearance().getItems().add(first);
        cell(seeded, 0, 0).getAppearance().getItems().add(second);
        DataCompositionSchema schema =
            DcsStoredXmlTestSupport.deserialize(DcsStoredXmlTestSupport.serialize(seeded));
        assertStoredDuplicates(cell(schema, 0, 0).getAppearance().getItems(), "BorderStyle");

        success(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"appearance\":{\"VerticalAlign\":\"Top\"}}]}"));
        List<DataCompositionParameterValue> items = cell(schema, 0, 0).getAppearance().getItems();
        assertEquals(4, items.size());
        assertFalse(items.get(1).eClass().getName(), items.get(1) instanceof SettingsParameterValue);
        assertTrue(items.get(2).eClass().getName(), items.get(2) instanceof SettingsParameterValue);
        assertEquals("second-id", ((SettingsParameterValue)items.get(2)).getUserSettingID());
        assertEquals("BorderStyle.Right", items.get(2).getNestedParameterValues().get(0).getParameter().getValue());
        assertEquals("VerticalAlign", items.get(3).getParameter().getValue());
    }

    @Test
    public void testACellsPatchPairsSameNamedNestedItemsByPosition() throws Exception
    {
        DataCompositionSchema seeded = schemaWith("A");
        SettingsParameterValue holder = settingsItem("BorderStyle");
        holder.setUse(false);
        holder.getNestedParameterValues().add(coreItem("BorderStyle.Left"));
        SettingsParameterValue duplicate = settingsItem("BorderStyle.Left");
        duplicate.setUserSettingID("nested-id");
        holder.getNestedParameterValues().add(duplicate);
        cell(seeded, 0, 0).getAppearance().getItems().add(holder);
        DataCompositionSchema schema =
            DcsStoredXmlTestSupport.deserialize(DcsStoredXmlTestSupport.serialize(seeded));
        assertStoredDuplicates(cell(schema, 0, 0).getAppearance().getItems().get(1).getNestedParameterValues(),
            "BorderStyle.Left");

        success(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"appearance\":{"
                + "\"BorderStyle.Right\":{\"kind\":\"line\",\"value\":{\"width\":2,\"style\":\"Solid\"}}}}]}"));
        DataCompositionParameterValue kept = cell(schema, 0, 0).getAppearance().getItems().get(1);
        assertTrue(kept.eClass().getName(), kept instanceof SettingsParameterValue);
        List<DataCompositionParameterValue> nested = kept.getNestedParameterValues();
        assertEquals(3, nested.size());
        assertFalse(nested.get(0).eClass().getName(), nested.get(0) instanceof SettingsParameterValue);
        assertTrue(nested.get(1).eClass().getName(), nested.get(1) instanceof SettingsParameterValue);
        assertEquals("nested-id", ((SettingsParameterValue)nested.get(1)).getUserSettingID());
        assertEquals("BorderStyle.Right", nested.get(2).getParameter().getValue());
        assertFalse(nested.get(2).eClass().getName(), nested.get(2) instanceof SettingsParameterValue);
    }

    /** The two same-named items survived EDT's serializer and reader, the core one first. */
    private static void assertStoredDuplicates(List<DataCompositionParameterValue> items, String name)
    {
        List<DataCompositionParameterValue> named = new ArrayList<>();
        for (DataCompositionParameterValue item : items)
        {
            if (item.getParameter() != null && name.equals(item.getParameter().getValue())) named.add(item);
        }
        assertEquals(2, named.size());
        assertFalse(named.get(0).eClass().getName(), named.get(0) instanceof SettingsParameterValue);
        assertTrue(named.get(1).eClass().getName(), named.get(1) instanceof SettingsParameterValue);
        assertNotNull(((SettingsParameterValue)named.get(1)).getUserSettingID());
    }

    @Test
    public void testABindingNamesItsTemplateExactlyAsEdtResolvesIt()
    {
        DataCompositionSchema schema = schemaWith("TotalRow");
        String refused = failure(write(schema, "upsert", ROOT + "#/groupTemplates",
            "{\"groupName\":\"Customer\",\"template\":\"totalrow\"}"));
        assertContains(refused, "names template 'totalrow'");
        assertContains(refused, "matched exactly");
        assertContains(refused, "spell it 'TotalRow'");
        assertTrue(schema.getGroupTemplates().isEmpty());

        success(write(schema, "upsert", ROOT + "#/groupTemplates",
            "{\"groupName\":\"Customer\",\"template\":\"TotalRow\"}"));
        assertEquals(1, schema.getGroupTemplates().size());
        // The same identity keeps a case variant from being created beside the bound template.
        assertContains(failure(write(schema, "upsert", ROOT + "#/templates", "{\"name\":\"totalrow\"}")),
            "collides");
    }

    @Test
    public void testARootBodyNamesEachTemplateAndBindsEachGroupingOnce()
    {
        DataCompositionSchema schema = schemaWith("Keep");
        String twice = failure(write(schema, "replace", ROOT, "{\"templates\":[{\"name\":\"T\"," + ROWS
            + "},{\"name\":\"T\",\"parameters\":[]}]}"));
        assertContains(twice, "'body.templates[1]' repeats template name 'T' of 'body.templates[0]'");
        assertEquals(1, schema.getTemplates().size());
        assertEquals("Keep", schema.getTemplates().get(0).getName());

        assertContains(failure(write(schema, "upsert", ROOT, "{\"templates\":[{\"name\":\"T\"},{\"name\":\"t\"}]}")),
            "'body.templates[1]' repeats template name 't' of 'body.templates[0]'");

        String bindings = failure(write(schema, "upsert", ROOT, "{\"templates\":[{\"name\":\"T\"}],"
            + "\"groupTemplates\":[{\"groupName\":\"Customer\",\"template\":\"T\"},"
            + "{\"groupName\":\"customer\",\"template\":\"Keep\"}]}"));
        assertContains(bindings, "'body.groupTemplates[1]' binds the same grouping and template type");
        assertContains(bindings, "'body.groupTemplates[0]'");
        assertEquals(1, schema.getTemplates().size());
        assertTrue(schema.getGroupTemplates().isEmpty());

        // Another template type, and the other collection, are other bindings.
        success(write(schema, "upsert", ROOT, "{\"groupTemplates\":["
            + "{\"groupName\":\"Customer\",\"template\":\"Keep\"},"
            + "{\"groupName\":\"Customer\",\"templateType\":\"Footer\",\"template\":\"Keep\"}],"
            + "\"groupHeaderTemplates\":[{\"groupName\":\"Customer\",\"template\":\"Keep\"}]}"));
        assertEquals(2, schema.getGroupTemplates().size());
        assertEquals(1, schema.getGroupHeaderTemplates().size());
    }

    @Test
    public void testRowsInAnUpdateRefuseToDiscardWhatTheWriterCannotRestate()
    {
        DataCompositionSchema schema = schemaWith("A");
        cell(schema, 0, 1).setAppIndex(4);
        String refused = failure(write(schema, "update", ROOT + "#/templates/A", "{" + ROWS + "}"));
        assertContains(refused, "appearance index 4");
        assertContains(refused, "body.cells");
        assertEquals(4, cell(schema, 0, 1).getAppIndex());

        // A cells patch of another cell still works and keeps the unrestatable one.
        success(write(schema, "update", ROOT + "#/templates/A", "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"y\"}]}"));
        assertEquals(4, cell(schema, 0, 1).getAppIndex());

        String replace = failure(write(schema, "replace", ROOT + "#/templates/A", "{" + ROWS + "}"));
        assertContains(replace, "appearance index 4");
    }

    @Test
    public void testIntegrityAndShapeRefusalsNameTheValueAndWriteNothing()
    {
        DataCompositionSchema schema = schemaWith("A");
        assertContains(failure(write(schema, "upsert", ROOT + "#/groupTemplates",
            "{\"groupName\":\"Customer\",\"template\":\"Missing\"}")), "names template 'Missing'");
        assertTrue(schema.getGroupTemplates().isEmpty());
        assertContains(failure(write(schema, "upsert", ROOT + "#/groupTemplates",
            "{\"groupName\":\"Customer\",\"templateType\":\"Middle\",\"template\":\"A\"}")), "OverallFooter");
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"parameters\":[{\"name\":\"D\",\"kind\":\"details\",\"mainAction\":\"Explode\"}]}")), "ApplyAppearance");
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{" + ROWS + ",\"cells\":[]}")), "both rows and cells");
        // The same catalogue key in both spellings, and a template-only key twice.
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"appearance\":{\"HorizontalAlign\":\"Left\",\""
                + com._1c.g5.v8.dt.dcs.util.DcsTerms.kDCSAppearanceHorizontalAlignTerm[1] + "\":\"Right\"}}]}")),
            "appears twice");
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"appearance\":{\"items\":["
                + "{\"parameter\":{\"kind\":\"parameter\",\"value\":\"HorizontalMerge\"},\"values\":[true]},"
                + "{\"parameter\":{\"kind\":\"parameter\",\"value\":\"HorizontalMerge\"},\"values\":[false]}]}}]}")),
            "appears twice");
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"cells\":[{\"row\":0,\"col\":0,\"appearance\":{\"Details\":true}}]}")), "name of a template parameter");
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A",
            "{\"unknown\":1}")), "Unknown member 'unknown'");

        DataCompositionSchemaTemplateDescription dup = DcsFactory.eINSTANCE.createDataCompositionSchemaTemplateDescription();
        dup.setName("A");
        schema.getTemplates().add(dup);
        assertContains(failure(write(schema, "update", ROOT + "#/templates/A", "{\"name\":\"X\"}")), "ambiguous");
        assertEquals("A", schema.getTemplates().get(0).getName());
    }

    @Test
    public void testWarningsNameAnUnknownGroupingAndAnUndeclaredCellParameter()
    {
        DataCompositionSchema schema = schema();
        DataCompositionGroup group = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createDataCompositionGroup();
        group.setName("Known");
        schema.getDefaultSettings().getItems().add(group);
        DcsSchemaWriter.Result result = write(schema, "upsert", ROOT, "{\"templates\":[{\"name\":\"T\"," + ROWS
            + "}],\"groupTemplates\":[{\"groupName\":\"Elsewhere\",\"template\":\"T\"},{\"groupName\":\"known\",\"template\":\"T\",\"templateType\":\"Footer\"}]}");
        success(result);
        String warnings = String.join("\n", result.templates().warnings());
        assertContains(warnings, "names grouping 'Elsewhere'");
        assertContains(warnings, "named groupings: Known");
        assertFalse(warnings, warnings.contains("'known'"));
        assertContains(warnings, "refers to parameter(s) Amount");
    }

    // ----- helpers -----

    static DcsSchemaWriter.Result write(DataCompositionSchema schema, String action, String address, String body)
    {
        DcsSchemaWriter.PrepareResult prepared = DcsSchemaWriter.prepare(action, "schema",
            DcsAddress.parse(address).address(), body == null ? null : DcsTemplateBodyTest.json(body),
            DcsTemplateBodyTest.EN);
        assertTrue(prepared.error(), prepared.isSuccess());
        return DcsSchemaWriter.apply(schema, prepared.request(), null, DcsTemplateBodyTest.VERSION, null,
            DcsReplaceTestCatalogues.schema());
    }

    static DataCompositionSchema schema()
    {
        return DcsStoredXmlTestSupport.schema();
    }

    static DataCompositionSchema schemaWith(String... names)
    {
        DataCompositionSchema schema = schema();
        for (String name : names)
        {
            DataCompositionSchemaTemplateDescription template = DcsFactory.eINSTANCE.createDataCompositionSchemaTemplateDescription();
            try
            {
                assertNull(DcsTemplateBody.apply(template, DcsTemplateBodyTest.json("{\"name\":\"" + name + "\"," + ROWS
                    + ",\"parameters\":[{\"name\":\"Amount\",\"expression\":\"Amount\"}]}"),
                    DcsTemplateBodyTest.context(DcsTemplateBodyTest.EN), "seed", true, new LinkedHashSet<>()));
            }
            catch (Exception e)
            {
                throw new AssertionError(e);
            }
            schema.getTemplates().add(template);
        }
        return schema;
    }

    static DataCompositionSchemaGroupTemplate binding(String groupName, String template)
    {
        DataCompositionSchemaGroupTemplate binding = DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate();
        binding.setGroupName(groupName);
        binding.setTemplate(template);
        return binding;
    }

    static DataCompositionParameterValue coreItem(String name)
    {
        DataCompositionParameterValue item = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
            .createDataCompositionParameterValue();
        DataCompositionParameter parameter =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionParameter();
        parameter.setValue(name);
        item.setParameter(parameter);
        return item;
    }

    static SettingsParameterValue settingsItem(String name)
    {
        SettingsParameterValue item = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsParameterValue();
        DataCompositionParameter parameter =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionParameter();
        parameter.setValue(name);
        item.setParameter(parameter);
        return item;
    }

    static DataCompositionField field(String value)
    {
        DataCompositionField field = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionField();
        field.setValue(value);
        return field;
    }

    static DataCompositionAreaTemplateTableCell cell(DataCompositionSchema schema, int row, int col)
    {
        return DcsTemplateBodyTest.cell(schema.getTemplates().get(0), row, col);
    }

    static void success(DcsSchemaWriter.Result result)
    {
        assertTrue(result.error(), result.isSuccess());
        assertNotNull(result.templates());
    }

    static String failure(DcsSchemaWriter.Result result)
    {
        assertFalse("expected a refusal", result.isSuccess());
        return result.error();
    }

    static void assertContains(String text, String expected)
    {
        DcsTemplateBodyTest.assertContains(text, expected);
    }
}
