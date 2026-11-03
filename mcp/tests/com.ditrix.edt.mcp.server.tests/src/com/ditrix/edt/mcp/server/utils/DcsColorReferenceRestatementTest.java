/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionAppearance;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceItem;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.dcs.util.DcsV8Serializer;
import com._1c.g5.v8.dt.mcore.Color;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.ColorRef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.PaletteColor;
import com._1c.g5.v8.dt.mcore.StyleColor;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.google.gson.JsonParser;

/**
 * #682 round 5: a stored color reference is restated by the name EDT's Template.dcs writer stores
 * for it - {@code web:Red} by {@code {color:{web:'Red'}}}, a platform or configuration style color by
 * {@code {color:{style:...}}} - so the replace guard no longer lets an unrestatable reference through
 * on the mere presence of a referent, and no longer refuses a restatable one.
 */
public class DcsColorReferenceRestatementTest
{
    private static final Version VERSION = Version.V8_3_27;
    private static final McoreFactory VALUES = McoreFactory.eINSTANCE;
    private static final com._1c.g5.v8.dt.dcs.model.settings.DcsFactory SETTINGS =
        com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;
    private static final String COLORS = "v8:/Colors/Web/v8.3.27"; //$NON-NLS-1$

    /**
     * EDT's reader turns {@code web:Red}, {@code win:WindowText} and {@code style:FieldBackColor} into
     * references the guard names and the writer rebuilds through the platform catalogue; without a
     * catalogue the writer cannot name them, and the guard refuses instead of passing on the referent.
     */
    @Test
    public void testEdtsPlatformColorReferencesAreRestatedByTheirNames() throws Exception
    {
        StyleValueBuilder.NamedColorResolver platform = StyleValueBuilder.forConfiguration(null,
            catalogue("Web.Red", "Windows.WindowText", "Style.FieldBackColor"));
        String[][] cases = {{"web:Red", "Web.Red", "Unknown Web color 'Red'"},
            {"win:WindowText", "Windows.WindowText", "Unknown Windows color 'WindowText'"},
            {"style:FieldBackColor", "Style.FieldBackColor", "Unknown Style color 'FieldBackColor'"}};
        for (String[] item : cases)
        {
            DataCompositionSchema read = readColor(item[0]);
            ColorRef stored = (ColorRef)((ColorValue)appearanceValue(read)).getValue();
            assertEquals(item[0], item[1], AppearanceValueBuilder.colorReferenceName(stored));
            assertNull(item[0], guard(read, platform));
            String refusal = guard(read, null);
            assertNotNull(item[0] + " has no restatement without the platform catalogue", refusal);
            assertTrue(refusal, refusal.contains("the writer refuses its body: Typed parameter value at 'value' is "
                + "invalid: " + item[2]));
        }
        assertTrue(guard(readColor("web:Blue"), platform),
            guard(readColor("web:Blue"), platform).contains("Unknown Web color 'Blue'"));
    }

    /** A configuration style color and palette color are restated through the project's resolver. */
    @Test
    public void testConfigurationColorReferencesAreRestatedThroughTheResolver()
    {
        StyleColor style = VALUES.createStyleColor();
        style.setName("Accent");
        PaletteColor palette = VALUES.createPaletteColor();
        palette.setName("Brand");
        var configuration = MdClassFactory.eINSTANCE.createConfiguration();
        var styleItem = MdClassFactory.eINSTANCE.createStyleItem();
        styleItem.setName("Accent");
        styleItem.setAppearanceItem(style);
        configuration.getStyleItems().add(styleItem);
        var paletteItem = MdClassFactory.eINSTANCE.createPaletteColor();
        paletteItem.setName("Brand");
        paletteItem.setAppearanceItem(palette);
        configuration.getPaletteColors().add(paletteItem);
        var project = StyleValueBuilder.forConfiguration(configuration);
        assertNull(guard(appearanceSchema(reference(style)), project));
        assertNull(guard(appearanceSchema(reference(palette)), project));
        StyleColor gone = VALUES.createStyleColor();
        gone.setName("Removed");
        String refusal = guard(appearanceSchema(reference(gone)), project);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("Unknown Style color 'Removed'"));
    }

    @Test
    public void testAReferenceWithoutARestatableNameRefuses()
    {
        ColorRef empty = VALUES.createColorRef();
        ColorValue value = VALUES.createColorValue();
        value.setValue(empty);
        String refusal = guard(appearanceSchema(value), StyleValueBuilder.forConfiguration(null, catalogue("Web.Red")));
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("the color reference names no target"));

        ColorDef foreign = VALUES.createColorDef();
        ((InternalEObject)foreign).eSetProxyURI(URI.createURI("v8:/Colors/Other#/Other.Thing"));
        String other = guard(appearanceSchema(reference(foreign)), StyleValueBuilder.forConfiguration(null,
            catalogue("Web.Red")));
        assertNotNull(other);
        assertTrue(other, other.contains("names 'Other.Thing', and no color body names a Other color"));
    }

    /** The writer builds web, Windows and platform style references, and refuses what it cannot name. */
    @Test
    public void testTheColorWriterBuildsPlatformColorReferences()
    {
        StyleValueBuilder.NamedColorResolver platform = StyleValueBuilder.forConfiguration(null,
            catalogue("Web.Red", "Windows.WindowText", "Style.FieldBackColor"));
        String[][] cases = {{"{\"color\":{\"web\":\"Red\"}}", "Web.Red"},
            {"{\"color\":{\"windows\":\"WindowText\"}}", "Windows.WindowText"},
            {"{\"color\":{\"style\":\"FieldBackColor\"}}", "Style.FieldBackColor"},
            {"{\"color\":{\"web\":\"red\"}}", "Web.Red"}};
        for (String[] item : cases)
        {
            StyleValueBuilder.Result built = StyleValueBuilder.build(JsonParser.parseString(item[0]), platform);
            assertNull(item[0], built.error);
            Color color = ((ColorValue)built.value).getValue();
            assertTrue(item[0], color instanceof ColorRef);
            assertEquals(item[0], item[1], AppearanceValueBuilder.colorReferenceName((ColorRef)color));
        }
        StyleValueBuilder.Result noCatalogue = StyleValueBuilder.build(
            JsonParser.parseString("{\"color\":{\"web\":\"Red\"}}"), StyleValueBuilder.forConfiguration(null));
        assertNotNull(noCatalogue.error);
        assertTrue(noCatalogue.error, noCatalogue.error.contains("platform catalogue is unavailable for this project's platform version"));
        StyleValueBuilder.Result mixed = StyleValueBuilder.build(
            JsonParser.parseString("{\"color\":{\"web\":\"Red\",\"red\":1}}"), platform);
        assertNotNull(mixed.error);
        assertTrue(mixed.error, mixed.error.contains("must be exactly one of {style:'<name>'}, {palette:'<name>'}, "
            + "{web:'<name>'} or {windows:'<name>'}"));
        StyleValueBuilder.Result palette = StyleValueBuilder.build(
            JsonParser.parseString("{\"color\":{\"palette\":\"Red\"}}"), platform);
        assertNotNull("a palette name absent from both sources is refused", palette.error);
    }

    private static IEObjectProvider catalogue(String... names)
    {
        return AppearanceValueBuilderTest.catalogue(COLORS, names);
    }

    private static ColorValue reference(Color target)
    {
        ColorRef ref = VALUES.createColorRef();
        ref.setColor(target);
        ColorValue value = VALUES.createColorValue();
        value.setValue(ref);
        return value;
    }

    /** Serializes an RGB TextColor and swaps EDT's color text for {@code text} before reading back. */
    private static DataCompositionSchema readColor(String text) throws Exception
    {
        ColorDef definition = VALUES.createColorDef();
        definition.setRed(1);
        definition.setGreen(2);
        definition.setBlue(3);
        ColorValue value = VALUES.createColorValue();
        value.setValue(definition);
        String xml = serialize(appearanceSchema(value));
        assertTrue(xml, xml.contains(">#010203<"));
        return deserialize(xml.replace(">#010203<", ">" + text + "<"));
    }

    private static DataCompositionSchema appearanceSchema(Value value)
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        schema.setDefaultSettings(SETTINGS.createDataCompositionSettings());
        DataCompositionConditionalAppearance appearance = SETTINGS.createDataCompositionConditionalAppearance();
        DataCompositionConditionalAppearanceItem rule = SETTINGS.createDataCompositionConditionalAppearanceItem();
        DataCompositionAppearance holder =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionAppearance();
        SettingsParameterValue item = SETTINGS.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("TextColor"));
        item.getValues().add(value);
        holder.getItems().add(item);
        rule.setAppearance(holder);
        appearance.getItems().add(rule);
        schema.getDefaultSettings().setConditionalAppearance(appearance);
        return schema;
    }

    private static Value appearanceValue(DataCompositionSchema schema)
    {
        return schema.getDefaultSettings().getConditionalAppearance().getItems().get(0).getAppearance().getItems()
            .get(0).getValues().get(0);
    }

    private static String guard(DataCompositionSchema schema, StyleValueBuilder.NamedColorResolver namedColors)
    {
        DcsWriterCatalogues catalogues = new DcsWriterCatalogues(VERSION, AppearanceCatalogue.SCHEMA, "en", null,
            namedColors, DcsReplaceTestCatalogues::load);
        return DcsMutationGuard.replaceError(schema, DcsAddress.parse("Report.Sales").address(), catalogues);
    }

    private static String serialize(DataCompositionSchema schema) throws Exception
    {
        DcsV8Serializer serializer = new DcsV8Serializer((IDtProject)null, VERSION, (IResourceLookup)null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        serializer.serializeXML(schema, output, serializer.createExportContext((IProject)null, VERSION),
            new HashMap<String, UUID>(), "\n");
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static DataCompositionSchema deserialize(String xml) throws Exception
    {
        DcsV8Serializer reader = new DcsV8Serializer((IDtProject)null, VERSION, (IResourceLookup)null);
        return reader.deserializeXML(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
