/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.ColorRef;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.StyleColor;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.StyleItem;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.Merge;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.NamedItemCells;
import com._1c.g5.v8.dt.moxel.Rect;
import com._1c.g5.v8.dt.moxel.RectArea;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.SpreadsheetTemplateWriter.Result;
import com.google.gson.JsonParser;

/**
 * Issue #1001: the counts the {@code template} result reports for the removals, and the named cell
 * colours - a configuration style item bound in the caller's transaction, a platform colour from the
 * project version's catalogue - pooled by the colour they NAME, never by the value they hold.
 */
public class SpreadsheetTemplateColorsAndCountsTest
{
    private static Rect rect(int top, int left, int bottom, int right)
    {
        Rect rect = MoxelFactory.eINSTANCE.createRect();
        rect.setX(left);
        rect.setY(top);
        rect.setWidth(right - left);
        rect.setHeight(bottom - top);
        return rect;
    }

    private static Merge merge(int top, int left, int bottom, int right)
    {
        Merge merge = MoxelFactory.eINSTANCE.createMerge();
        merge.setPosition(rect(top, left, bottom, right));
        return merge;
    }

    private static void addArea(SpreadsheetDocument doc, String name)
    {
        RectArea area = MoxelFactory.eINSTANCE.createRectArea();
        area.setPosition(rect(0, 0, 0, 0));
        NamedItemCells item = MoxelFactory.eINSTANCE.createNamedItemCells();
        item.setArea(area);
        doc.getNamedItems().put(name, item);
    }

    private static StyleItem styleItem(Configuration configuration, String name, String colorName)
    {
        StyleItem item = MdClassFactory.eINSTANCE.createStyleItem();
        item.setName(name);
        StyleColor color = McoreFactory.eINSTANCE.createStyleColor();
        color.setName(colorName);
        item.setAppearanceItem(color);
        configuration.getStyleItems().add(item);
        return item;
    }

    private static Object target(SpreadsheetDocument doc, int colorIndex)
    {
        return ((ColorRef)doc.getColors().get(colorIndex)).eGet(McorePackage.Literals.COLOR_REF__COLOR, false);
    }

    @Test
    public void theResultCountsMergesRemovedAndAreasRemovedOrRenamed()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        doc.getMerges().add(merge(0, 0, 0, 1));
        doc.getMerges().add(merge(2, 0, 2, 1));
        doc.getMerges().add(merge(5, 5, 5, 6));
        addArea(doc, "First");
        addArea(doc, "Second");
        addArea(doc, "Third");
        Result result = SpreadsheetTemplateWriter.apply(doc, JsonParser.parseString(
            "{\"unmerges\":[{\"fromRow\":0,\"fromCol\":0,\"toRow\":2,\"toCol\":9}],\"removeAreas\":[\"First\"],"
                + "\"renameAreas\":[{\"name\":\"Second\",\"newName\":\"Renamed\"}]}").getAsJsonObject());
        assertFalse(result.error, result.hasError());
        assertEquals("two merges touch the range", 2, result.unmerges);
        assertEquals(1, result.removedAreas);
        assertEquals(1, result.renamedAreas);
        assertEquals(0, result.merges);
        assertEquals(1, doc.getMerges().size());
    }

    @Test
    public void aConfigurationStyleColourIsBoundToTheItemTheBuilderFound()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        StyleItem accent = styleItem(configuration, "Accent", "Accent");
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        Result result = SpreadsheetTemplateWriter.apply(doc, JsonParser.parseString(
            "{\"cells\":[{\"row\":0,\"col\":0,\"backColor\":\"Style.Accent\"}]}").getAsJsonObject(), null,
            raw -> AppearanceValueBuilder.buildColor(raw, configuration, null));
        assertFalse(result.error, result.hasError());
        Format format = doc.getFormats().get(doc.getRows().get(Integer.valueOf(0)).getCells().get(Integer.valueOf(0)).getFormatIndex());
        assertSame("the reference targets the style item's own colour", accent.getAppearanceItem(),
            target(doc, format.getBackColor()));
    }

    @Test
    public void twoStyleItemsHoldingEqualColoursStayTwoPoolEntries()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        StyleItem first = styleItem(configuration, "First", "Same");
        StyleItem second = styleItem(configuration, "Second", "Same");
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        Result result = SpreadsheetTemplateWriter.apply(doc, JsonParser.parseString(
            "{\"cells\":[{\"row\":0,\"col\":0,\"backColor\":\"Style.First\",\"textColor\":\"Style.Second\","
                + "\"borderColor\":{\"style\":\"First\"}}]}").getAsJsonObject(), null,
            raw -> AppearanceValueBuilder.buildColor(raw, configuration, null));
        assertFalse(result.error, result.hasError());
        Format format = doc.getFormats().get(doc.getRows().get(Integer.valueOf(0)).getCells().get(Integer.valueOf(0)).getFormatIndex());
        assertEquals("a colour is pooled by what it names, so First twice is one entry", 2, doc.getColors().size());
        assertEquals(format.getBackColor(), format.getBorderColor());
        assertSame(first.getAppearanceItem(), target(doc, format.getBackColor()));
        assertSame("a style item with an equal value is still another colour", second.getAppearanceItem(),
            target(doc, format.getTextColor()));
    }

    @Test
    public void twoProxiesOfTheSameCatalogueUriArePooledOnceAndAnotherUriIsNot()
    {
        // A platform catalogue hands out a FRESH proxy per lookup, so only the proxy URI says two
        // references name the same colour.
        java.util.function.Function<String, ColorRef> proxyRef = fragment -> {
            com._1c.g5.v8.dt.mcore.ColorDef proxy = McoreFactory.eINSTANCE.createColorDef();
            ((InternalEObject)proxy).eSetProxyURI(
                org.eclipse.emf.common.util.URI.createURI("v8:/Colors/Web/v8.3.27").appendFragment(fragment));
            ColorRef ref = McoreFactory.eINSTANCE.createColorRef();
            ref.setColor(proxy);
            return ref;
        };
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        Result result = SpreadsheetTemplateWriter.apply(doc, JsonParser.parseString(
            "{\"cells\":[{\"row\":0,\"col\":0,\"backColor\":\"Web.Red\",\"textColor\":\"Web.Red\","
                + "\"borderColor\":\"Web.Blue\"}]}").getAsJsonObject(), null,
            raw -> AppearanceValueBuilder.Result.ok(proxyRef.apply(
                raw.getAsJsonObject().get(AppearanceValueBuilder.COLOR).getAsString()), null));
        assertFalse(result.error, result.hasError());
        Format format = doc.getFormats().get(doc.getRows().get(Integer.valueOf(0)).getCells().get(Integer.valueOf(0)).getFormatIndex());
        assertEquals("the same proxy URI is one pool entry", format.getBackColor(), format.getTextColor());
        assertEquals("another proxy URI is another entry", 2, doc.getColors().size());
    }

    @Test
    public void aPlatformColourFromTheVersionCatalogueIsPooledOnceByItsName() throws Exception
    {
        try (PlatformColorTestRuntime ignored = new PlatformColorTestRuntime("Web.Red", "Web.Blue"))
        {
            SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
            Result result = SpreadsheetTemplateWriter.apply(doc, JsonParser.parseString(
                "{\"cells\":[{\"row\":0,\"col\":0,\"backColor\":\"Web.Red\",\"textColor\":{\"web\":\"Red\"},"
                    + "\"borderColor\":\"Web.Blue\"}]}").getAsJsonObject(), Version.V8_3_27);
            assertFalse(result.error, result.hasError());
            Format format = doc.getFormats().get(doc.getRows().get(Integer.valueOf(0)).getCells().get(Integer.valueOf(0)).getFormatIndex());
            assertEquals("Web.Red twice is one pool entry", format.getBackColor(), format.getTextColor());
            assertEquals(2, doc.getColors().size());
            EObject red = (EObject)target(doc, format.getBackColor());
            EObject blue = (EObject)target(doc, format.getBorderColor());
            assertNotSame(red, blue);
            assertTrue(red.eIsProxy());
            String fragment = ((InternalEObject)red).eProxyURI().fragment();
            assertTrue(fragment, fragment.endsWith("Web.Red"));
        }
    }

    @Test
    public void aNameThePlatformAlsoHasMeansThePlatformColourAsEdtReadsItBack() throws Exception
    {
        try (PlatformColorTestRuntime ignored = new PlatformColorTestRuntime("Style.FormBackColor"))
        {
            Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
            styleItem(configuration, "FormBackColor", "FormBackColor");
            StyleItem accent = styleItem(configuration, "Accent", "Accent");
            SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
            Result result = SpreadsheetTemplateWriter.apply(doc, JsonParser.parseString(
                "{\"cells\":[{\"row\":0,\"col\":0,\"backColor\":\"Style.FormBackColor\",\"textColor\":\"Style.Accent\"}]}")
                .getAsJsonObject(), Version.V8_3_27,
                SpreadsheetTemplateWriter.projectColors(() -> configuration, Version.V8_3_27));
            assertFalse(result.error, result.hasError());
            Format format = doc.getFormats().get(doc.getRows().get(Integer.valueOf(0)).getCells()
                .get(Integer.valueOf(0)).getFormatIndex());
            EObject back = (EObject)target(doc, format.getBackColor());
            assertTrue("the platform style colour wins, as MoxelUtil.getRegistryColor reads it", back.eIsProxy());
            assertTrue(((InternalEObject)back).eProxyURI().fragment().endsWith("Style.FormBackColor"));
            assertSame("a name only the configuration has is its item", accent.getAppearanceItem(),
                target(doc, format.getTextColor()));
        }
    }

    @Test
    public void anUnknownPlatformColourIsRefusedAndNothingIsWritten() throws Exception
    {
        try (PlatformColorTestRuntime ignored = new PlatformColorTestRuntime("Web.Red"))
        {
            SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
            Result result = SpreadsheetTemplateWriter.apply(doc, JsonParser.parseString(
                "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"A\",\"backColor\":\"Web.Rouge\"}]}").getAsJsonObject(),
                Version.V8_3_27);
            assertTrue(result.hasError());
            assertTrue(result.error, result.error.contains("backColor"));
            assertTrue(result.error, result.error.contains("Web.Red"));
            assertTrue(doc.getRows().isEmpty());
        }
    }
}
