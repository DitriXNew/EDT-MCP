/**
 * MCP Server for EDT
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.InputFieldExtInfo;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.ColorRef;
import com._1c.g5.v8.dt.mcore.FontRef;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.StyleItem;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com.ditrix.edt.mcp.server.utils.AppearanceValueBuilder;
import com.ditrix.edt.mcp.server.utils.AppearanceValueBuilder.ConfigurationItems;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.PropertyInfo;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.ValueKind;
import com.google.gson.JsonParser;

/**
 * A form item's Color / Font properties through {@code modify_metadata} (issue #660): the
 * classification that makes them assignable, the prepared change and what it writes onto a real
 * form-model object, including the in-transaction re-bind of a configuration style item.
 */
public class ModifyMetadataToolAppearanceTest
{
    // ---- classification + discovery --------------------------------------------------------------

    @Test
    public void testAFormFieldsTitleColorAndFontAreAssignable()
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        PropertyInfo color = MetadataPropertyIntrospector.findFeature(field, "titleTextColor"); //$NON-NLS-1$
        PropertyInfo font = MetadataPropertyIntrospector.findFeature(field, "titleFont"); //$NON-NLS-1$
        PropertyInfo back = MetadataPropertyIntrospector.findFeature(field, "titleBackColor"); //$NON-NLS-1$
        assertNotNull("titleTextColor must be assignable", color); //$NON-NLS-1$
        assertNotNull("titleFont must be assignable", font); //$NON-NLS-1$
        assertNotNull("titleBackColor must be assignable", back); //$NON-NLS-1$
        assertEquals(ValueKind.COLOR, color.valueKind);
        assertEquals(ValueKind.FONT, font.valueKind);
        assertEquals(ValueKind.COLOR, back.valueKind);
        assertEquals(List.of(AppearanceValueBuilder.COLOR_SHAPES), color.allowedValues);
        assertEquals(List.of(AppearanceValueBuilder.FONT_SHAPES), font.allowedValues);
    }

    @Test
    public void testAnExtInfoColorIsFoundOnTheExtInfo()
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        InputFieldExtInfo ext = FormFactory.eINSTANCE.createInputFieldExtInfo();
        PropertyInfo text = MetadataPropertyIntrospector.findFeature(field, ext, "textColor"); //$NON-NLS-1$
        assertNotNull(text);
        assertEquals(ValueKind.COLOR, text.valueKind);
        assertTrue("textColor lives on the input field's extInfo", text.onExtInfo); //$NON-NLS-1$
    }

    @Test
    public void testTheCurrentValueIsRenderedForDiscovery()
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        ColorDef red = McoreFactory.eINSTANCE.createColorDef();
        red.setRed(255);
        field.setTitleTextColor(red);
        PropertyInfo color = MetadataPropertyIntrospector.find(field, "titleTextColor"); //$NON-NLS-1$
        assertEquals("RGB(255, 0, 0)", color.currentValue); //$NON-NLS-1$
        assertNull("an unset font renders as empty", //$NON-NLS-1$
            MetadataPropertyIntrospector.find(field, "titleFont").currentValue); //$NON-NLS-1$
    }

    // ---- prepare + apply -------------------------------------------------------------------------

    @Test
    public void testAnRgbColorIsWrittenOntoTheField()
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        ModifyMetadataTool.PreparedChange change = prepare(true, "titleTextColor", //$NON-NLS-1$
            "{color:{red:10, green:20, blue:30}}", null, null, field); //$NON-NLS-1$
        change.applyTo(field, null);
        ColorDef def = (ColorDef)field.getTitleTextColor();
        assertEquals(10, def.getRed());
        assertEquals(20, def.getGreen());
        assertEquals(30, def.getBlue());
    }

    @Test
    public void testAutoClearsAPreviouslySetColor()
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setTitleTextColor(McoreFactory.eINSTANCE.createColorDef());
        EStructuralFeature feature = field.eClass().getEStructuralFeature("titleTextColor"); //$NON-NLS-1$
        prepare(true, "titleTextColor", "{color:'auto'}", null, null, field).applyTo(field, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("'auto' leaves the property unset, as the designer's Clear does", //$NON-NLS-1$
            field.eIsSet(feature));
    }

    @Test
    public void testAPlatformColorOnAnExtInfoStoresTheProxy()
    {
        InputFieldExtInfo ext = FormFactory.eINSTANCE.createInputFieldExtInfo();
        prepare(true, "textColor", "{color:'Web.AliceBlue'}", null, //$NON-NLS-1$ //$NON-NLS-2$
            catalogue("v8:/Colors/Web/v8.3.27", "Web.AliceBlue"), ext).applyTo(ext, null); //$NON-NLS-1$ //$NON-NLS-2$
        ColorRef ref = (ColorRef)ext.getTextColor();
        EObject target = (EObject)ref.eGet(McorePackage.Literals.COLOR_REF__COLOR, false);
        assertTrue(target.eIsProxy());
        assertEquals("Web.AliceBlue", AppearanceValueBuilder.renderColor(ref)); //$NON-NLS-1$
    }

    @Test
    public void testAConfigurationStyleItemIsReboundInsideTheTransaction()
    {
        StyleItem outside = styleItem("BrandColor"); //$NON-NLS-1$
        StyleItem inTx = styleItem("BrandColor"); //$NON-NLS-1$
        IBmTransaction tx = Mockito.mock(IBmTransaction.class);
        Mockito.doReturn(inTx).when(tx).getObjectById(Mockito.anyLong());
        FormField field = FormFactory.eINSTANCE.createFormField();

        prepare(true, "titleTextColor", "{color:{style:'BrandColor'}}", //$NON-NLS-1$ //$NON-NLS-2$
            items(outside), catalogue("v8:/Colors/Style/v8.3.27"), field).applyTo(field, tx); //$NON-NLS-1$

        assertSame("the stored reference is the in-transaction item's appearance, never the one read " //$NON-NLS-1$
            + "outside", inTx.getAppearanceItem(), ((ColorRef)field.getTitleTextColor()).getColor()); //$NON-NLS-1$
        Mockito.verify(tx).getObjectById(((com._1c.g5.v8.bm.core.IBmObject)outside).bmGetId());
    }

    @Test
    public void testAFontReferenceWithOverridesIsWritten()
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        prepare(false, "titleFont", "{font:{style:'NormalTextFont', bold:true}}", null, //$NON-NLS-1$ //$NON-NLS-2$
            catalogue("v8:/Fonts/Style/v8.3.27", "Style.NormalTextFont"), field).applyTo(field, null); //$NON-NLS-1$ //$NON-NLS-2$
        FontRef ref = (FontRef)field.getTitleFont();
        assertTrue(ref.isSetBold() && ref.isBold());
        assertFalse(ref.isSetItalic());
        assertEquals("Style.NormalTextFont (bold)", //$NON-NLS-1$
            MetadataPropertyIntrospector.find(field, "titleFont").currentValue); //$NON-NLS-1$
    }

    @Test
    public void testARefusalNamesThePropertyAndTheValueAndQueuesNothing()
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        List<ModifyMetadataTool.PreparedChange> out = new ArrayList<>();
        String error = ModifyMetadataTool.prepareAppearanceWith(true, "titleTextColor", //$NON-NLS-1$
            JsonParser.parseString("{color:'Web.NoSuchColor'}"), null, //$NON-NLS-1$
            catalogue("v8:/Colors/Web/v8.3.27", "Web.AliceBlue"), //$NON-NLS-1$ //$NON-NLS-2$
            field.eClass().getEStructuralFeature("titleTextColor"), out); //$NON-NLS-1$
        assertNotNull(error);
        assertTrue(error, error.contains("Invalid color for property 'titleTextColor'")); //$NON-NLS-1$
        assertTrue(error, error.contains("'NoSuchColor'")); //$NON-NLS-1$
        assertTrue(error, error.contains("Web.AliceBlue")); //$NON-NLS-1$
        assertTrue("nothing may be queued for a refused value", out.isEmpty()); //$NON-NLS-1$
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static ModifyMetadataTool.PreparedChange prepare(boolean color, String property, String json,
        ConfigurationItems items, IEObjectProvider catalogue, EObject holder)
    {
        List<ModifyMetadataTool.PreparedChange> out = new ArrayList<>();
        String error = ModifyMetadataTool.prepareAppearanceWith(color, property,
            JsonParser.parseString(json), items, catalogue,
            holder.eClass().getEStructuralFeature(property), out);
        assertNull(error, error);
        assertEquals(1, out.size());
        return out.get(0);
    }

    private static StyleItem styleItem(String name)
    {
        StyleItem item = MdClassFactory.eINSTANCE.createStyleItem();
        item.setName(name);
        item.setAppearanceItem(McoreFactory.eINSTANCE.createStyleColor());
        return item;
    }

    private static ConfigurationItems items(StyleItem... styleItems)
    {
        return new ConfigurationItems()
        {
            @Override
            public List<? extends EObject> styleItems()
            {
                return List.of(styleItems);
            }

            @Override
            public List<? extends EObject> paletteColors()
            {
                return Collections.emptyList();
            }
        };
    }

    private static IEObjectProvider catalogue(String resource, String... names)
    {
        boolean fonts = resource.startsWith("v8:/Fonts"); //$NON-NLS-1$
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        List<IEObjectDescription> descriptions = new ArrayList<>();
        for (String name : names)
        {
            URI uri = URI.createURI(resource).appendFragment("/" + name); //$NON-NLS-1$
            IEObjectDescription desc = Mockito.mock(IEObjectDescription.class);
            Mockito.doReturn(QualifiedName.create(name.split("\\."))).when(desc).getName(); //$NON-NLS-1$
            Mockito.doAnswer(invocation -> proxy(fonts, uri)).when(desc).getEObjectOrProxy();
            descriptions.add(desc);
            Mockito.doAnswer(invocation -> proxy(fonts, uri)).when(provider).getProxy(name);
        }
        Mockito.doReturn(descriptions).when(provider).getEObjectDescriptions(Mockito.any());
        return provider;
    }

    private static EObject proxy(boolean font, URI uri)
    {
        EObject proxy = EcoreUtil.create(font ? McorePackage.Literals.FONT_DEF : McorePackage.Literals.COLOR_DEF);
        ((InternalEObject)proxy).eSetProxyURI(uri);
        return proxy;
    }
}
