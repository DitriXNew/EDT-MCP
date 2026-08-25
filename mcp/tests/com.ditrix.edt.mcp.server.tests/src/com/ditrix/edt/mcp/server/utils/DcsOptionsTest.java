/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionAppearance;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Pins the platform-derived keys, declared types, enum literals, and pagination contract. */
public class DcsOptionsTest
{
    @Test
    public void testOutputOptionsExposeStoredKeyDeclaredTypeAndAcceptedEnumLiterals()
    {
        DcsOptions.Result result;
        DcsOptions.Result russian;
        try (DcsCatalogueTestRuntime.Scope ignored =
            DcsCatalogueTestRuntime.prepareCatalogues(Version.V8_3_27))
        {
            result = DcsOptions.render("Report.Options", TargetKind.REPORT_MAIN_DCS, null, //$NON-NLS-1$
                address("Report.Options"), "outputParameter", "en", Version.V8_3_27, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Integer.valueOf(1000), 0);
            russian = DcsOptions.render("Report.Options", TargetKind.REPORT_MAIN_DCS, null, //$NON-NLS-1$
                address("Report.Options"), "outputParameter", "ru", Version.V8_3_27, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Integer.valueOf(1000), 0);
        }

        assertTrue(result.error(), result.isSuccess());
        String markdown = result.markdown();
        assertTrue(markdown, markdown.contains("VerticalOverallPlacement")); //$NON-NLS-1$
        assertTrue(markdown, markdown.contains("TotalPlacementType")); //$NON-NLS-1$
        assertTrue(markdown, markdown.contains("Auto")); //$NON-NLS-1$
        assertTrue(markdown, markdown.contains("None")); //$NON-NLS-1$
        assertTrue(markdown, markdown.contains("The other English/Russian spelling is also accepted")); //$NON-NLS-1$
        assertTrue(russian.error(), russian.isSuccess());
        assertTrue(russian.markdown(), russian.markdown()
            .contains("ВертикальноеРасположениеОбщихИтогов")); //$NON-NLS-1$
    }

    @Test
    public void testAppearanceAndBodyEnumOptionsUseTheWriterSourcesAndPaginate()
    {
        DcsOptions.Result appearance;
        try (DcsCatalogueTestRuntime.Scope ignored =
            DcsCatalogueTestRuntime.prepareCatalogues(Version.V8_3_27))
        {
            appearance = DcsOptions.render("Report.Options", TargetKind.REPORT_MAIN_DCS, null, //$NON-NLS-1$
                address("Report.Options"), "conditionalAppearance", "en", Version.V8_3_27, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Integer.valueOf(1000), 0);
        }
        assertTrue(appearance.error(), appearance.isSuccess());
        assertTrue(appearance.markdown(), appearance.markdown().contains("TextColor")); //$NON-NLS-1$
        assertTrue(appearance.markdown(), appearance.markdown().contains("Color")); //$NON-NLS-1$

        DcsOptions.Result filter = DcsOptions.render("Report.Options", TargetKind.REPORT_MAIN_DCS, null, //$NON-NLS-1$
            address("Report.Options"), "filter", "en", Version.V8_3_27, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Integer.valueOf(1), 0);
        assertTrue(filter.error(), filter.isSuccess());
        assertTrue(filter.markdown(), filter.markdown().contains("comparisonType") //$NON-NLS-1$
            || filter.markdown().contains("viewMode")); //$NON-NLS-1$
        assertTrue(filter.markdown(), filter.markdown().contains("**Next offset:** `1`")); //$NON-NLS-1$

        DcsOptions.Result allFilter = DcsOptions.render("Report.Options", TargetKind.REPORT_MAIN_DCS, null, //$NON-NLS-1$
            address("Report.Options"), "filter", "en", Version.V8_3_27, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Integer.valueOf(100), 0);
        assertTrue(allFilter.markdown(), allFilter.markdown().contains("comparisonType")); //$NON-NLS-1$
        assertTrue(allFilter.markdown(), allFilter.markdown().contains("Equal")); //$NON-NLS-1$
    }

    @Test
    public void testRealAppearanceCatalogueReportsAndWritesTheSameConcreteValueTypes()
        throws Exception
    {
        DcsOptions.Result options;
        DcsAvailableParameterCollection catalogue;
        try (DcsCatalogueTestRuntime.Scope ignored =
            DcsCatalogueTestRuntime.prepareCatalogues(Version.V8_3_27))
        {
            catalogue = DcsSettingsWriter.appearanceParameters(
                DcsSettingsWriter.AppearanceCatalogue.SCHEMA, Version.V8_3_27, "en"); //$NON-NLS-1$
            options = DcsOptions.render("Report.Options", TargetKind.REPORT_MAIN_DCS, null, //$NON-NLS-1$
                address("Report.Options"), "conditionalAppearance", "en", Version.V8_3_27, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Integer.valueOf(1000), 0);
        }

        assertTrue(options.error(), options.isSuccess());
        String markdown = options.markdown();
        assertRow(markdown, "BackColor", "Color"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRow(markdown, "Font", "Font"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRow(markdown, "Indent", "Number"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRow(markdown, "MarkNegatives", "Boolean"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRow(markdown, "Text", "LocalString"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a multi-entry catalogue description must not leak EMF Type records\n" + markdown, //$NON-NLS-1$
            !markdown.contains("LocalString, Type")); //$NON-NLS-1$
        assertTrue("no declared-type cell may contain the EMF Type class name\n" + markdown, //$NON-NLS-1$
            !markdown.contains(" | Type |")); //$NON-NLS-1$

        DataCompositionAppearance written = write(catalogue,
            "{\"BackColor\":{\"color\":{\"red\":12,\"green\":34,\"blue\":56}}," //$NON-NLS-1$
            + "\"Font\":{\"font\":{\"faceName\":\"Arial\",\"height\":11}}," //$NON-NLS-1$
            + "\"Indent\":{\"kind\":\"number\",\"value\":7}," //$NON-NLS-1$
            + "\"MarkNegatives\":{\"kind\":\"boolean\",\"value\":true}," //$NON-NLS-1$
            + "\"Text\":{\"en\":\"Pinned localized text\"}}"); //$NON-NLS-1$

        assertWrittenType(written, "BackColor", ColorValue.class); //$NON-NLS-1$
        assertWrittenType(written, "Font", FontValue.class); //$NON-NLS-1$
        assertWrittenType(written, "Indent", NumberValue.class); //$NON-NLS-1$
        assertWrittenType(written, "MarkNegatives", BooleanValue.class); //$NON-NLS-1$
        DataCompositionParameterValue text = item(written, "Text"); //$NON-NLS-1$
        assertTrue(text.getValues().get(0) instanceof LocalString);
        assertEquals("Pinned localized text", //$NON-NLS-1$
            ((LocalString)text.getValues().get(0)).getContent().get("en")); //$NON-NLS-1$
    }

    @Test
    public void testPlatformCatalogueDeclaresAutoIndentAsNumber()
        throws Exception
    {
        DcsOptions.Result options;
        try (DcsCatalogueTestRuntime.Scope ignored =
            DcsCatalogueTestRuntime.prepareCatalogues(Version.V8_3_27))
        {
            options = DcsOptions.render("Report.Options", TargetKind.REPORT_MAIN_DCS, null, //$NON-NLS-1$
                address("Report.Options"), "conditionalAppearance", "en", Version.V8_3_27, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Integer.valueOf(1000), 0);
        }
        assertTrue(options.error(), options.isSuccess());
        assertRow(options.markdown(), "AutoIndent", "Number"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static DataCompositionAppearance write(DcsAvailableParameterCollection catalogue,
        String source)
    {
        JsonObject body = JsonParser.parseString(source).getAsJsonObject();
        DcsPresentationParser.LanguageContext english =
            new DcsPresentationParser.LanguageContext(Arrays.asList("en", "ru"), "en"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DcsSettingsWriter.AppearanceResult result = DcsSettingsWriter.buildAppearanceForTest(
            body, null, english, catalogue);
        assertNull(result.error, result.error);
        assertNotNull(result.value);
        return result.value;
    }

    private static void assertWrittenType(DataCompositionAppearance appearance, String name,
        Class<?> type)
    {
        assertTrue(name + " must land as " + type.getSimpleName(), //$NON-NLS-1$
            type.isInstance(item(appearance, name).getValues().get(0)));
    }

    private static DataCompositionParameterValue item(DataCompositionAppearance appearance,
        String name)
    {
        for (DataCompositionParameterValue item : appearance.getItems())
        {
            if (item.getParameter() != null && name.equals(item.getParameter().getValue()))
            {
                return item;
            }
        }
        throw new AssertionError("Missing written appearance parameter " + name); //$NON-NLS-1$
    }

    private static void assertRow(String markdown, String name, String type)
    {
        String row = "| appearance | " + name + " | " + type + " |"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("Missing options row prefix '" + row + "' in:\n" + markdown, //$NON-NLS-1$ //$NON-NLS-2$
            markdown.contains(row));
    }

    private static DcsAddress address(String source)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(source);
        if (!parsed.isSuccess()) throw new AssertionError(parsed.failure().message());
        return parsed.address();
    }
}
