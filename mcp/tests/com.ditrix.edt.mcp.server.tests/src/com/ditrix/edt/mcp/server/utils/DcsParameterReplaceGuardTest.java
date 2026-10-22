/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionAppearance;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.InputParameters;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionDataParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOutputParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.dcs.path.DcsPathException;
import com._1c.g5.v8.dt.mcore.*;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.google.gson.JsonParser;

/** #682: replacement is safe exactly when the containing writer can reproduce each value. */
public class DcsParameterReplaceGuardTest
{
    private static final String ROOT = "Report.Sales";
    private static final DcsFactory SETTINGS = DcsFactory.eINSTANCE;
    private static final McoreFactory VALUES = McoreFactory.eINSTANCE;

    @Test
    public void testMultiValuedDataParameterBlocksReplace()
    {
        DataCompositionSchema schema = schema();
        SettingsParameterValue item = dataItem(schema, VALUES.createStringValue());
        assertNull(refusal(schema));
        item.getValues().add(VALUES.createStringValue());
        String error = refusal(schema);
        contains(error, "SettingsParameterValue at Report.Sales#/defaultSettings/dataParameters/items/0",
            "(2 values; a data parameter body carries one 'value')", "action='update'", "lossless XML channel");
        assertNull(DcsMutationGuard.replaceError(schema, address(ROOT + "#/defaultSettings/selection"),
            DcsReplaceTestCatalogues.schema()));
    }

    @Test
    public void testDataParameterKindsOutsideUntypedValueSpecsBlockReplace()
    {
        for (Value value : List.of(VALUES.createStandardPeriodValue(), VALUES.createStandardBeginningDateValue(),
            VALUES.createValueList(), com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createLocalString(),
            VALUES.createEnumValue(), VALUES.createReferenceValue(), VALUES.createTypeValue()))
        {
            DataCompositionSchema schema = schema();
            SettingsParameterValue item = dataItem(schema, value);
            contains(refusal(schema), value.eClass().getName() + " is not an untyped ValueSpec kind");
            item.getValues().set(0, VALUES.createNumberValue());
            assertNull(value.eClass().getName(), refusal(schema));
        }
    }

    @Test
    public void testInputReferenceAndTypeValuesBlockSchemaReplaceButKeepFullUntypedArray()
    {
        DataCompositionSchema schema = schema();
        var parameter = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaParameter();
        parameter.setName("P");
        InputParameters holder = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createInputParameters();
        DataCompositionParameterValue item = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
            .createDataCompositionParameterValue();
        holder.getItems().add(item);
        parameter.setInputParameters(holder);
        schema.getParameters().add(parameter);
        item.getValues().add(VALUES.createStringValue());
        item.getValues().add(VALUES.createNumberValue());
        assertNull(refusal(schema));
        for (Value value : List.of(VALUES.createReferenceValue(), VALUES.createTypeValue()))
        {
            item.getValues().set(1, value);
            contains(refusal(schema), "#/parameters/P/inputParameters/items/0", "value #1 is "
                + value.eClass().getName(), "input-parameter values are untyped ValueSpecs");
        }
    }

    @Test
    public void testUnknownAndMissingCatalogueNamesBlockReplaceAndBilingualAliasPasses()
    {
        DataCompositionSchema schema = schema();
        SettingsParameterValue item = outputItem(schema, "NotInCatalogue", VALUES.createStringValue());
        DcsAvailableParameterCollection parameters = DcsReplaceTestCatalogues.available("Known",
            "\u0418\u043c\u044f", VALUES.createStringValue());
        DcsWriterCatalogues context = context(AppearanceCatalogue.SCHEMA, (kind, version, language) -> parameters);
        contains(DcsMutationGuard.replaceError(schema, address(ROOT), context),
            "'NotInCatalogue' is not in the platform catalogue for 8.3.27");
        item.setParameter(DcsReplaceTestCatalogues.parameter("\u0418\u043c\u044f"));
        assertNull(DcsMutationGuard.replaceError(schema, address(ROOT), context));
        item.setParameter(null);
        contains(DcsMutationGuard.replaceError(schema, address(ROOT), context),
            "the item has no parameter name; the typed writer needs one");
    }

    @Test
    public void testWrongDeclaredTypeBlocksReplace()
    {
        DataCompositionSchema schema = schema();
        SettingsParameterValue item = outputItem(schema, "Known", com._1c.g5.v8.dt.dcs.model.core
            .DcsFactory.eINSTANCE.createDesignTimeValueValue());
        DcsWriterCatalogues context = context(AppearanceCatalogue.SCHEMA,
            (kind, version, language) -> DcsReplaceTestCatalogues.available("Known", "Alias", VALUES.createStringValue()));
        contains(DcsMutationGuard.replaceError(schema, address(ROOT), context),
            "declared StringValue, stored DesignTimeValueValue");
        item.getValues().set(0, VALUES.createStringValue());
        assertNull(DcsMutationGuard.replaceError(schema, address(ROOT), context));
    }

    @Test
    public void testForeignEnumClassWithTheSameLiteralBlocksReplace()
    {
        EnumValue declared = VALUES.createEnumValue();
        declared.setValue(com._1c.g5.v8.dt.dcs.model.core.DataCompositionPeriodAdditionType.NONE);
        EnumValue stored = VALUES.createEnumValue();
        stored.setValue(com._1c.g5.v8.dt.dcs.model.core.DataCompositionTotalPlacement.NONE);
        DataCompositionSchema schema = schema();
        SettingsParameterValue item = outputItem(schema, "Known", stored);
        DcsWriterCatalogues context = context(AppearanceCatalogue.SCHEMA,
            (kind, version, language) -> DcsReplaceTestCatalogues.available("Known", "Alias", declared));
        contains(DcsMutationGuard.replaceError(schema, address(ROOT), context), "not in the declared platform enum");
        item.getValues().set(0, EcoreUtil.copy(declared));
        assertNull(DcsMutationGuard.replaceError(schema, address(ROOT), context));
    }

    @Test
    public void testFontFormsOutsideBuildFontBlockReplace()
    {
        DataCompositionAppearance holder = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
            .createDataCompositionAppearance();
        SettingsParameterValue item = SETTINGS.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("Font"));
        holder.getItems().add(item);
        FontValue stored = VALUES.createFontValue();
        FontDef font = VALUES.createFontDef();
        font.setHeight(10f);
        stored.setValue(font);
        item.getValues().add(stored);
        DcsWriterCatalogues context = context(AppearanceCatalogue.SCHEMA,
            (kind, version, language) -> DcsReplaceTestCatalogues.available("Font", "Alias", VALUES.createFontValue()));
        assertNull(DcsMutationGuard.replaceError(holder, address(ROOT), context));
        font.setHeight(10.5f);
        contains(DcsMutationGuard.replaceError(holder, address(ROOT), context), "font height 10.5");
        font.setHeight(10f);
        font.setScale(90);
        contains(DcsMutationGuard.replaceError(holder, address(ROOT), context), "font scale 90");
        stored.setValue(VALUES.createFontRef());
        contains(DcsMutationGuard.replaceError(holder, address(ROOT), context), "FontRef");
    }

    @Test
    public void testUnavailableCatalogueFailsClosedForEveryItemAndCachesTheFailure()
    {
        for (boolean checked : new boolean[] {true, false})
        {
            DataCompositionSchema schema = schema();
            outputItem(schema, "First", VALUES.createStringValue());
            SettingsParameterValue second = SETTINGS.createSettingsParameterValue();
            second.setParameter(DcsReplaceTestCatalogues.parameter("Second"));
            schema.getDefaultSettings().getOutputParameters().getItems().add(second);
            AtomicInteger loads = new AtomicInteger();
            DcsWriterCatalogues context = context(AppearanceCatalogue.SCHEMA, (kind, version, language) ->
            {
                loads.incrementAndGet();
                if (checked) throw new DcsPathException("index unavailable");
                throw new IllegalStateException("index unavailable");
            });
            String error = DcsMutationGuard.replaceError(schema, address(ROOT), context);
            contains(error, "items/0 (typed catalogue for platform 8.3.27 unavailable: index unavailable)",
                "items/1 (typed catalogue for platform 8.3.27 unavailable: index unavailable)");
            assertEquals(1, loads.get());
            DcsMutationGuard.replaceError(schema, address(ROOT), context);
            assertEquals(1, loads.get());
            schema.getDefaultSettings().setOutputParameters(null);
            dataItem(schema, VALUES.createNumberValue());
            assertNull(DcsMutationGuard.replaceError(schema, address(ROOT), context));
            assertEquals(1, loads.get());
        }
    }

    @Test
    public void testAppearanceUsesItsOwnCatalogueKindAndLanguageAndLoadsItOnce()
    {
        for (AppearanceCatalogue expected : AppearanceCatalogue.values())
        {
            List<Enum<?>> loaded = new ArrayList<>();
            DcsWriterCatalogues context = new DcsWriterCatalogues(Version.V8_3_27, expected, "ru",
                (kind, version, language) ->
                {
                    loaded.add(kind);
                    assertEquals(Version.V8_3_27, version);
                    assertEquals("ru", language);
                    return DcsReplaceTestCatalogues.available("Known", "Alias", VALUES.createStringValue());
                });
            DataCompositionAppearance holder = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
                .createDataCompositionAppearance();
            DataCompositionParameterValue item = SETTINGS.createSettingsParameterValue();
            item.setParameter(DcsReplaceTestCatalogues.parameter("Known"));
            item.getValues().add(VALUES.createStringValue());
            holder.getItems().add(item);
            assertNull(DcsMutationGuard.replaceError(holder, address(ROOT), context));
            assertNull(DcsMutationGuard.replaceError(holder, address(ROOT), context));
            assertEquals(List.of(expected), loaded);
        }
    }

    @Test
    public void testSchemaAndSettingsGuardsShareTheSameCatalogueSnapshot()
    {
        DataCompositionSchema schema = schema();
        outputItem(schema, "Known", VALUES.createStringValue());
        AtomicInteger loads = new AtomicInteger();
        DcsWriterCatalogues context = context(AppearanceCatalogue.SCHEMA, (kind, version, language) ->
        {
            loads.incrementAndGet();
            return DcsReplaceTestCatalogues.available("Known", "Alias", VALUES.createStringValue());
        });
        assertNull(DcsMutationGuard.replaceError(schema, address(ROOT), context));
        DcsSchemaWriter.PrepareResult prepared = DcsSchemaWriter.prepare("replace", "schema", address(ROOT),
            JsonParser.parseString("{}").getAsJsonObject(), null);
        DcsSchemaWriter.Result applied = DcsSchemaWriter.apply(schema, prepared.request(), null,
            Version.V8_3_27, null, context);
        assertTrue(applied.error(), applied.isSuccess());
        assertEquals(1, loads.get());
    }

    @Test
    public void testUpdatePreservesAllUnnamedDataParameterValues()
    {
        DataCompositionSchema schema = schema();
        SettingsParameterValue item = dataItem(schema, VALUES.createStandardPeriodValue());
        item.getValues().add(VALUES.createNumberValue());
        String before = DcsHash.compute(schema);
        refusal(schema); // Inspecting the guard must preserve the source used by the update control.
        assertEquals(before, DcsHash.compute(schema));
        DcsSettingsWriter.SettingsResult updated = DcsSettingsWriter.planSettings(schema.getDefaultSettings(),
            List.of("dataParameters", "items", "0"), "update", "dataParameter",
            JsonParser.parseString("{\"use\":false}").getAsJsonObject(), null);
        assertTrue(updated.error(), updated.isSuccess());
        var result = updated.settings().getDataParameters().getItems().get(0);
        assertFalse(result.isUse());
        assertEquals(2, result.getValues().size());
        assertTrue(result.getValues().get(0) instanceof StandardPeriodValue);
        assertTrue(result.getValues().get(1) instanceof NumberValue);
    }

    /** #680 pins #673: intentionally green on base, killed by removing the filter-right guard. */
    @Test
    public void testValueListAndStandardBeginningDateInFilterRightBlockReplace()
    {
        DataCompositionSchema schema = schema();
        var group = SETTINGS.createDataCompositionGroup();
        schema.getDefaultSettings().getItems().add(group);
        var filter = SETTINGS.createDataCompositionFilter();
        var item = SETTINGS.createDataCompositionFilterItem();
        group.setFilter(filter);
        filter.getItems().add(item);
        item.getRight().add(VALUES.createNumberValue());
        assertNull(refusal(schema));
        for (Value value : List.of(VALUES.createValueList(), VALUES.createStandardBeginningDateValue()))
        {
            item.getRight().set(0, value);
            contains(refusal(schema), value.eClass().getName()
                + " at Report.Sales#/defaultSettings/items/0/filter/items/0/right");
        }
    }

    private static DataCompositionSchema schema()
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        schema.setDefaultSettings(SETTINGS.createDataCompositionSettings());
        return schema;
    }

    private static SettingsParameterValue dataItem(DataCompositionSchema schema, Value value)
    {
        DataCompositionDataParameterValues holder = SETTINGS.createDataCompositionDataParameterValues();
        SettingsParameterValue item = SETTINGS.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("P"));
        item.getValues().add(value);
        holder.getItems().add(item);
        schema.getDefaultSettings().setDataParameters(holder);
        return item;
    }

    private static SettingsParameterValue outputItem(DataCompositionSchema schema, String name, Value value)
    {
        DataCompositionOutputParameterValues holder = SETTINGS.createDataCompositionOutputParameterValues();
        SettingsParameterValue item = SETTINGS.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter(name));
        item.getValues().add(value);
        holder.getItems().add(item);
        schema.getDefaultSettings().setOutputParameters(holder);
        return item;
    }

    private static DcsWriterCatalogues context(AppearanceCatalogue kind, DcsWriterCatalogues.Source source)
    {
        return new DcsWriterCatalogues(Version.V8_3_27, kind, "en", source);
    }

    private static String refusal(DataCompositionSchema schema)
    {
        return DcsMutationGuard.replaceError(schema, address(ROOT), DcsReplaceTestCatalogues.schema());
    }

    private static DcsAddress address(String raw)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(raw);
        assertTrue(raw, parsed.isSuccess());
        return parsed.address();
    }

    private static void contains(String actual, String... expected)
    {
        assertNotNull("replace should refuse content its writer cannot reproduce", actual);
        for (String text : expected) assertTrue(actual, actual.contains(text));
    }
}
