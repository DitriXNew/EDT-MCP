/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.mcore.ColorRef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;

/** Exercises the project branch of EDT's reader with registry and BM proxy targets. */
public class DcsColorProjectReaderTest
{
    @Test
    public void testNamesOfColorReferencesActuallyReadWithAProject() throws Exception
    {
        var colors = McoreFactory.eINSTANCE;
        var rgb = colors.createColorDef();
        rgb.setRed(1);
        rgb.setGreen(2);
        rgb.setBlue(3);
        ColorValue value = colors.createColorValue();
        value.setValue(rgb);
        var schema = DcsStoredXmlTestSupport.schema();
        var appearance = DcsStoredXmlTestSupport.SETTINGS.createDataCompositionConditionalAppearance();
        var rule = DcsStoredXmlTestSupport.SETTINGS.createDataCompositionConditionalAppearanceItem();
        var holder = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionAppearance();
        var item = DcsStoredXmlTestSupport.SETTINGS.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("TextColor"));
        item.getValues().add(value);
        holder.getItems().add(item);
        rule.setAppearance(holder);
        appearance.getItems().add(rule);
        schema.getDefaultSettings().setConditionalAppearance(appearance);
        String xml = DcsStoredXmlTestSupport.serialize(schema);
        assertTrue(xml.contains(">#010203<"));

        IDtProject project = Mockito.mock(IDtProject.class);
        Mockito.doReturn("DcsProjectReaderTest").when(project).getName();
        try (PlatformColorTestRuntime ignored = new PlatformColorTestRuntime(
            "Web.Red", "Windows.WindowText", "Style.FieldBackColor"))
        {
            var configuration = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createConfiguration();
            var accent = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createStyleItem();
            accent.setName("ConfigAccent");
            var accentColor = colors.createStyleColor();
            accentColor.setName("ConfigAccent");
            accent.setAppearanceItem(accentColor);
            configuration.getStyleItems().add(accent);
            var resolver = StyleValueBuilder.forConfiguration(configuration,
                AppearanceValueBuilder.colorCatalogue(DcsStoredXmlTestSupport.VERSION));
            var catalogues = new DcsWriterCatalogues(DcsStoredXmlTestSupport.VERSION,
                DcsSettingsWriter.AppearanceCatalogue.SCHEMA, "en", null, resolver, DcsReplaceTestCatalogues::load);
            String[][] cases = {{"web:Red", "Web.Red"}, {"win:WindowText", "Windows.WindowText"},
                {"style:FieldBackColor", "Style.FieldBackColor"}, {"style:ConfigAccent", "Style.ConfigAccent"}};
            for (String[] sample : cases)
            {
                var read = DcsStoredXmlTestSupport.deserialize(xml.replace(">#010203<", ">" + sample[0] + "<"), project);
                var stored = (ColorValue)read.getDefaultSettings().getConditionalAppearance().getItems().get(0)
                    .getAppearance().getItems().get(0).getValues().get(0);
                ColorRef ref = (ColorRef)stored.getValue();
                EObject target = (EObject)ref.eGet(McorePackage.Literals.COLOR_REF__COLOR, false);
                assertTrue(sample[0], target.eIsProxy());
                var uri = ((InternalEObject)target).eProxyURI();
                if (sample[0].equals("style:ConfigAccent"))
                {
                    assertTrue(uri.toString(), BmUriUtil.isBmUri(uri));
                    assertEquals("StyleItem.ConfigAccent", BmUriUtil.extractTopObjectFqn(uri));
                    assertTrue(uri.toString(), uri.toString().endsWith("/appearanceItem"));
                }
                else
                {
                    assertEquals(sample[0], "v8", uri.scheme());
                }
                assertEquals(uri.toString(), sample[1], AppearanceValueBuilder.colorReferenceName(ref));
                assertNull(sample[0], DcsMutationGuard.replaceError(read,
                    DcsAddress.parse("Report.Sales").address(), catalogues));
                // Registry proxies serialize by their qualified fragment. A BM proxy needs a live
                // project resource set to resolve its appearance item; the conductor covers that.
                if (!BmUriUtil.isBmUri(uri))
                {
                    assertTrue(sample[0], DcsStoredXmlTestSupport.serialize(read).contains(">" + sample[0] + "<"));
                }
            }
        }
    }
}
