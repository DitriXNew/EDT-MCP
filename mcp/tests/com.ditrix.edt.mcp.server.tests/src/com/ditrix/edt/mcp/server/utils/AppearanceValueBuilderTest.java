/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.ColorRef;
import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.FontRef;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.StyleColor;
import com._1c.g5.v8.dt.mcore.StyleFont;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.PaletteColor;
import com._1c.g5.v8.dt.metadata.mdclass.StyleItem;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com.ditrix.edt.mcp.server.utils.AppearanceValueBuilder.Result;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Headless tests for {@link AppearanceValueBuilder}: the designer grammar of a form item's colour and
 * font (issue #660) - parse, refusal, configuration-vs-platform resolution, re-binding and rendering.
 * The platform catalogue is a stub with the shape {@code AbstractEObjectProvider} has: an exact,
 * case-sensitive index of English names whose descriptions hand back unresolved proxies.
 */
public class AppearanceValueBuilderTest
{
    private static final String COLORS_URI = "v8:/Colors/Style/v8.3.27"; //$NON-NLS-1$

    private static final String FONTS_URI = "v8:/Fonts/Style/v8.3.27"; //$NON-NLS-1$

    // ---- colour: parse ---------------------------------------------------------------------------

    @Test
    public void testRgbBuildsAColorDef()
    {
        Result r = color("{color:{red:255, green:128, blue:0}}", null, null); //$NON-NLS-1$
        assertNull(r.error);
        assertTrue("an RGB value is an absolute ColorDef", r.value instanceof ColorDef); //$NON-NLS-1$
        ColorDef def = (ColorDef)r.value;
        assertEquals(255, def.getRed());
        assertEquals(128, def.getGreen());
        assertEquals(0, def.getBlue());
        assertNull("an absolute colour references no configuration item", r.configurationItem); //$NON-NLS-1$
    }

    @Test
    public void testRgbOutOfRangeIsRefusedWithTheSharedStyleItemMessage()
    {
        Result r = color("{color:{red:256, green:0, blue:0}}", null, null); //$NON-NLS-1$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("red must be in range 0-255, got 256")); //$NON-NLS-1$
        assertNull(r.value);
    }

    @Test
    public void testAnUnknownRgbMemberIsRefusedNotIgnored()
    {
        Result r = color("{color:{red:1, green:2, blue:3, alpha:4}}", null, null); //$NON-NLS-1$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("'alpha'")); //$NON-NLS-1$
    }

    @Test
    public void testAutoClearsTheColor()
    {
        Result r = color("{color:'auto'}", null, null); //$NON-NLS-1$
        assertNull(r.error);
        assertTrue("'auto' clears the property", r.clear); //$NON-NLS-1$
        assertNull(r.value);
    }

    @Test
    public void testAnUnwrappedValueNamesTheWrapper()
    {
        Result r = color("'Web.AliceBlue'", null, catalogue(COLORS_URI, "Web.AliceBlue")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("'color'")); //$NON-NLS-1$
        assertTrue(r.error, r.error.contains("{color:'Web.AliceBlue'}")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownSourcePrefixIsRefused()
    {
        Result r = color("{color:'Foo.Bar'}", null, catalogue(COLORS_URI, "Web.AliceBlue")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("'Foo.Bar' is not a color")); //$NON-NLS-1$
    }

    // ---- colour: platform names ------------------------------------------------------------------

    @Test
    public void testAPlatformWebColorIsTheCataloguesProxy()
    {
        Result r = color("{color:'Web.AliceBlue'}", null, catalogue(COLORS_URI, "Web.AliceBlue")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(r.error);
        ColorRef ref = (ColorRef)r.value;
        EObject target = (EObject)ref.eGet(McorePackage.Literals.COLOR_REF__COLOR, false);
        assertTrue("the stored target is the platform's unresolved proxy", target.eIsProxy()); //$NON-NLS-1$
        assertEquals("Web.AliceBlue", ((InternalEObject)target).eProxyURI().fragment().substring(1)); //$NON-NLS-1$
        assertNull(r.configurationItem);
    }

    @Test
    public void testThePlatformNameMatchesCaseInsensitively()
    {
        Result r = color("{color:'web.aliceblue'}", null, catalogue(COLORS_URI, "Web.AliceBlue")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(r.error);
        assertEquals("Web.AliceBlue", AppearanceValueBuilder.renderColor(r.value)); //$NON-NLS-1$
    }

    @Test
    public void testTheObjectFormAddressesTheSameSource()
    {
        IEObjectProvider colors = catalogue(COLORS_URI, "Windows.ButtonFace", "Style.FormBackColor"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Windows.ButtonFace", AppearanceValueBuilder.renderColor( //$NON-NLS-1$
            color("{color:{windows:'ButtonFace'}}", null, colors).value)); //$NON-NLS-1$
        assertEquals("Style.FormBackColor", AppearanceValueBuilder.renderColor( //$NON-NLS-1$
            color("{color:{style:'FormBackColor'}}", items(), colors).value)); //$NON-NLS-1$
    }

    @Test
    public void testANamedColorWithASecondMemberIsRefused()
    {
        Result r = color("{color:{web:'AliceBlue', red:1}}", null, catalogue(COLORS_URI, "Web.AliceBlue")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("{web:'<Name>'}")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownPlatformColorIsRefusedWithTheBoundedNameList()
    {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 40; i++)
        {
            names.add("Web.Color" + i); //$NON-NLS-1$
        }
        names.add("Web.AliceBlue"); //$NON-NLS-1$
        Result r = color("{color:'Web.Blue'}", null, catalogue(COLORS_URI, names.toArray(new String[0]))); //$NON-NLS-1$
        assertNotNull(r.error);
        assertTrue("the refusal names the bad value", r.error.contains("'Blue'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a name containing the token is listed first", //$NON-NLS-1$
            r.error.contains("Valid names: Web.AliceBlue, Web.Color0")); //$NON-NLS-1$
        assertTrue("the rest is bounded", r.error.contains("(16 more)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(r.value);
    }

    @Test
    public void testWithoutACatalogueAPlatformNameIsRefusedRatherThanGuessed()
    {
        Result r = color("{color:'Web.AliceBlue'}", null, null); //$NON-NLS-1$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("catalogue is unavailable")); //$NON-NLS-1$
    }

    // ---- colour: configuration items -------------------------------------------------------------

    @Test
    public void testAConfigurationStyleItemIsReferencedThroughItsAppearanceItem()
    {
        StyleItem brand = styleItem("BrandColor", McoreFactory.eINSTANCE.createStyleColor()); //$NON-NLS-1$
        Result r = color("{color:{style:'BrandColor'}}", items(brand), catalogue(COLORS_URI)); //$NON-NLS-1$
        assertNull(r.error);
        assertNull("nothing read outside the write transaction is attached to the value", //$NON-NLS-1$
            ((ColorRef)r.value).eGet(McorePackage.Literals.COLOR_REF__COLOR, false));
        assertSame("the item travels with the value for the in-transaction bind", brand, //$NON-NLS-1$
            r.configurationItem);
        AppearanceValueBuilder.bind(r.value, brand);
        assertSame("bound, the ColorRef points at the style item's appearance item", //$NON-NLS-1$
            brand.getAppearanceItem(), ((ColorRef)r.value).getColor());
        assertEquals("Style.BrandColor", AppearanceValueBuilder.renderColor(r.value)); //$NON-NLS-1$
    }

    @Test
    public void testACyrillicStyleItemNameResolvesByItsProgrammaticName()
    {
        String name = "ЦветБренда"; //$NON-NLS-1$
        StyleItem brand = styleItem(name, McoreFactory.eINSTANCE.createStyleColor());
        Result r = color("{color:'Style." + name + "'}", items(brand), catalogue(COLORS_URI)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(r.error);
        assertSame(brand, r.configurationItem);
        AppearanceValueBuilder.bind(r.value, brand);
        assertEquals("Style." + name, AppearanceValueBuilder.renderColor(r.value)); //$NON-NLS-1$
    }

    @Test
    public void testTheConfigurationWinsOverAPlatformColorOfTheSameName()
    {
        StyleItem own = styleItem("FormBackColor", McoreFactory.eINSTANCE.createStyleColor()); //$NON-NLS-1$
        Result r = color("{color:'Style.FormBackColor'}", items(own), //$NON-NLS-1$
            catalogue(COLORS_URI, "Style.FormBackColor")); //$NON-NLS-1$
        assertNull(r.error);
        assertSame(own, r.configurationItem);
        assertNull("the platform proxy is not taken: the item is bound in the write transaction", //$NON-NLS-1$
            ((ColorRef)r.value).eGet(McorePackage.Literals.COLOR_REF__COLOR, false));
    }

    @Test
    public void testAPlatformStyleColorResolvesWhenNoItemHasTheName()
    {
        StyleItem other = styleItem("BrandColor", McoreFactory.eINSTANCE.createStyleColor()); //$NON-NLS-1$
        Result r = color("{color:{style:'FormBackColor'}}", items(other), //$NON-NLS-1$
            catalogue(COLORS_URI, "Style.FormBackColor")); //$NON-NLS-1$
        assertNull(r.error);
        assertNull("a platform colour references no configuration item", r.configurationItem); //$NON-NLS-1$
        assertEquals("Style.FormBackColor", AppearanceValueBuilder.renderColor(r.value)); //$NON-NLS-1$
    }

    @Test
    public void testAStyleItemHoldingAFontIsNotAColor()
    {
        StyleItem font = styleItem("HeaderFont", McoreFactory.eINSTANCE.createStyleFont()); //$NON-NLS-1$
        Result r = color("{color:{style:'HeaderFont'}}", items(font), catalogue(COLORS_URI)); //$NON-NLS-1$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("'HeaderFont' holds no color")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownStyleNameListsConfigurationAndPlatformNames()
    {
        StyleItem brand = styleItem("BrandColor", McoreFactory.eINSTANCE.createStyleColor()); //$NON-NLS-1$
        StyleItem font = styleItem("HeaderFont", McoreFactory.eINSTANCE.createStyleFont()); //$NON-NLS-1$
        Result r = color("{color:{style:'Missing'}}", items(brand, font), //$NON-NLS-1$
            catalogue(COLORS_URI, "Style.FormBackColor", "Web.AliceBlue")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("Unknown Style color 'Missing'")); //$NON-NLS-1$
        assertTrue(r.error, r.error.contains("Style.BrandColor")); //$NON-NLS-1$
        assertTrue(r.error, r.error.contains("Style.FormBackColor")); //$NON-NLS-1$
        assertFalse("a font item is not a colour candidate", r.error.contains("HeaderFont")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("another source's names are not listed", r.error.contains("AliceBlue")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAPlatformNameShadowedByAnItemOfTheOtherKindIsNotSuggested()
    {
        // Resolution takes the configuration item first, so 'Style.FormBackColor' would be refused
        // as "holds no color": the candidate list must not offer it.
        StyleItem shadow = styleItem("FormBackColor", McoreFactory.eINSTANCE.createStyleFont()); //$NON-NLS-1$
        Result r = color("{color:{style:'Missing'}}", items(shadow), //$NON-NLS-1$
            catalogue(COLORS_URI, "Style.FormBackColor", "Style.ButtonBackColor")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("Valid names: Style.ButtonBackColor.")); //$NON-NLS-1$
        assertFalse(r.error, r.error.contains("FormBackColor")); //$NON-NLS-1$
        assertTrue("and the shadowed name itself is refused by the same rule", //$NON-NLS-1$
            color("{color:'Style.FormBackColor'}", items(shadow), //$NON-NLS-1$
                catalogue(COLORS_URI, "Style.FormBackColor")).error.contains("holds no color")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAThrowingCatalogueIsReportedAsUnavailableNotAsNoSuchName()
    {
        IEObjectProvider broken = Mockito.mock(IEObjectProvider.class);
        Mockito.doThrow(new IllegalStateException("index not ready")).when(broken).getProxy(Mockito.anyString()); //$NON-NLS-1$
        StyleItem brand = styleItem("BrandColor", McoreFactory.eINSTANCE.createStyleColor()); //$NON-NLS-1$
        Result r = color("{color:{style:'Missing'}}", items(brand), broken); //$NON-NLS-1$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("catalogue is unavailable")); //$NON-NLS-1$
        assertTrue("the configuration names are still known and listed", //$NON-NLS-1$
            r.error.contains("Valid names: Style.BrandColor.")); //$NON-NLS-1$
    }

    @Test
    public void testWithoutConfigurationItemsOnlyThePlatformAnswers()
    {
        // A style item's / palette color's own colour takes platform values only - the designer's rule.
        Result r = color("{color:{style:'BrandColor'}}", null, catalogue(COLORS_URI, "Style.FormBackColor")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("Unknown Style color 'BrandColor'")); //$NON-NLS-1$
    }

    @Test
    public void testAConfigurationPaletteColorIsReferenced()
    {
        PaletteColor accent = MdClassFactory.eINSTANCE.createPaletteColor();
        accent.setName("Accent"); //$NON-NLS-1$
        accent.setAppearanceItem(McoreFactory.eINSTANCE.createPaletteColor());
        Configuration items = items();
        items.getPaletteColors().add(accent);
        Result r = color("{color:{palette:'Accent'}}", items, catalogue(COLORS_URI)); //$NON-NLS-1$
        assertNull(r.error);
        assertSame(accent, r.configurationItem);
        AppearanceValueBuilder.bind(r.value, accent);
        assertSame(accent.getAppearanceItem(), ((ColorRef)r.value).getColor());
        assertEquals("Palette.Accent", AppearanceValueBuilder.renderColor(r.value)); //$NON-NLS-1$
    }

    // ---- font ------------------------------------------------------------------------------------

    @Test
    public void testAnAbsoluteFontIsAFontDefWithScale()
    {
        Result r = font("{font:{faceName:'Arial', height:12, bold:true, scale:125}}", null, null); //$NON-NLS-1$
        assertNull(r.error);
        FontDef def = (FontDef)r.value;
        assertEquals("Arial", def.getFaceName()); //$NON-NLS-1$
        assertEquals(12f, def.getHeight(), 0f);
        assertTrue(def.isBold());
        assertFalse(def.isItalic());
        assertEquals(125, def.getScale());
        assertEquals("face='Arial', height=12, bold, scale=125", AppearanceValueBuilder.renderFont(def)); //$NON-NLS-1$
    }

    @Test
    public void testAScaleOnlyAbsoluteFontIsAccepted()
    {
        Result r = font("{font:{scale:90}}", null, null); //$NON-NLS-1$
        assertNull(r.error);
        assertEquals(90, ((FontDef)r.value).getScale());
    }

    @Test
    public void testFontMembersAreStrictlyTyped()
    {
        assertTrue(font("{font:{bold:'yes'}}", null, null).error.contains("'bold' must be true or false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(font("{font:{height:0}}", null, null).error.contains("'height' must be a positive integer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(font("{font:{height:10.5}}", null, null).error.contains("'height' must be a positive integer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(font("{font:{faceName:''}}", null, null).error.contains("'faceName' must be a non-empty")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(font("{font:{Bold:true}}", null, null).error.contains("Unknown font member 'Bold'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(font("{font:{}}", null, null).error.contains("at least one member")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAutoClearsTheFont()
    {
        assertTrue(font("{font:'AUTO'}", null, null).clear); //$NON-NLS-1$
    }

    @Test
    public void testAPlatformStyleFontReferenceCarriesNoOverrides()
    {
        Result r = font("{font:'Style.NormalTextFont'}", null, catalogue(FONTS_URI, "Style.NormalTextFont")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(r.error);
        FontRef ref = (FontRef)r.value;
        assertTrue(((EObject)ref.eGet(McorePackage.Literals.FONT_REF__FONT, false)).eIsProxy());
        assertFalse("an override not given stays inherited", ref.isSetBold()); //$NON-NLS-1$
        assertFalse(ref.isSetHeight());
        assertEquals("Style.NormalTextFont", AppearanceValueBuilder.renderFont(ref)); //$NON-NLS-1$
    }

    @Test
    public void testOnlyTheGivenOverridesAreSet()
    {
        Result r = font("{font:{style:'NormalTextFont', bold:true, italic:false, height:10}}", null, //$NON-NLS-1$
            catalogue(FONTS_URI, "Style.NormalTextFont")); //$NON-NLS-1$
        assertNull(r.error);
        FontRef ref = (FontRef)r.value;
        assertTrue(ref.isSetBold() && ref.isBold());
        assertTrue("an explicit false is an override too", ref.isSetItalic() && !ref.isItalic()); //$NON-NLS-1$
        assertTrue(ref.isSetHeight());
        assertEquals(10f, ref.getHeight(), 0f);
        assertFalse(ref.isSetUnderline());
        assertFalse(ref.isSetStrikeout());
        assertFalse(ref.isSetFaceName());
        assertFalse(ref.isSetScale());
        assertEquals("Style.NormalTextFont (height=10, bold, italic=false)", //$NON-NLS-1$
            AppearanceValueBuilder.renderFont(ref));
    }

    @Test
    public void testASystemFontIsReferenced()
    {
        Result r = font("{font:{system:'DefaultGUIFont', bold:true}}", null, //$NON-NLS-1$
            catalogue("v8:/Fonts/System/v8.3.27", "System.DefaultGUIFont")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(r.error);
        assertEquals("System.DefaultGUIFont (bold)", AppearanceValueBuilder.renderFont(r.value)); //$NON-NLS-1$
    }

    @Test
    public void testAConfigurationStyleFontIsReferenced()
    {
        StyleItem header = styleItem("HeaderFont", McoreFactory.eINSTANCE.createStyleFont()); //$NON-NLS-1$
        Result r = font("{font:{style:'HeaderFont', underline:true}}", items(header), //$NON-NLS-1$
            catalogue(FONTS_URI, "Style.NormalTextFont")); //$NON-NLS-1$
        assertNull(r.error);
        assertSame(header, r.configurationItem);
        AppearanceValueBuilder.bind(r.value, header);
        assertSame(header.getAppearanceItem(), ((FontRef)r.value).getFont());
        assertEquals("Style.HeaderFont (underline)", AppearanceValueBuilder.renderFont(r.value)); //$NON-NLS-1$
    }

    @Test
    public void testTwoFontSourcesAreRefused()
    {
        Result r = font("{font:{style:'NormalTextFont', system:'DefaultGUIFont'}}", null, //$NON-NLS-1$
            catalogue(FONTS_URI, "Style.NormalTextFont")); //$NON-NLS-1$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("ONE source")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownFontIsRefusedWithTheValidNames()
    {
        StyleItem brand = styleItem("BrandColor", McoreFactory.eINSTANCE.createStyleColor()); //$NON-NLS-1$
        Result r = font("{font:'Style.Huge'}", items(brand), //$NON-NLS-1$
            catalogue(FONTS_URI, "Style.NormalTextFont", "Style.LargeTextFont")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("Unknown Style font 'Huge'")); //$NON-NLS-1$
        assertTrue(r.error, r.error.contains("Style.NormalTextFont, Style.LargeTextFont")); //$NON-NLS-1$
        assertFalse("a colour item is not a font candidate", r.error.contains("BrandColor")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- re-bind and rendering -------------------------------------------------------------------

    @Test
    public void testBindPointsTheReferenceAtTheInTransactionItem()
    {
        StyleItem outside = styleItem("BrandColor", McoreFactory.eINSTANCE.createStyleColor()); //$NON-NLS-1$
        StyleItem inTx = styleItem("BrandColor", McoreFactory.eINSTANCE.createStyleColor()); //$NON-NLS-1$
        Result r = color("{color:{style:'BrandColor'}}", items(outside), catalogue(COLORS_URI)); //$NON-NLS-1$
        AppearanceValueBuilder.bind(r.value, inTx);
        assertSame(inTx.getAppearanceItem(), ((ColorRef)r.value).getColor());
    }

    @Test
    public void testBindRefusesAnItemOfTheOtherKindAsAMarkedRefusal()
    {
        StyleItem outside = styleItem("BrandColor", McoreFactory.eINSTANCE.createStyleColor()); //$NON-NLS-1$
        StyleItem nowAFont = styleItem("BrandColor", McoreFactory.eINSTANCE.createStyleFont()); //$NON-NLS-1$
        Result r = color("{color:{style:'BrandColor'}}", items(outside), catalogue(COLORS_URI)); //$NON-NLS-1$
        try
        {
            AppearanceValueBuilder.bind(r.value, nowAFont);
            fail("a ColorRef cannot be bound to a font"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("BrandColor")); //$NON-NLS-1$
            assertNotNull("a refusal, logged at INFO - not an ERROR with a stack", //$NON-NLS-1$
                Refusals.messageOf(expected));
        }
    }

    @Test
    public void testAReloadedConfigurationReferenceRendersFromItsBmUri()
    {
        ColorRef ref = McoreFactory.eINSTANCE.createColorRef();
        EObject proxy = EcoreUtil.create(McorePackage.Literals.STYLE_COLOR);
        ((InternalEObject)proxy).eSetProxyURI(
            BmUriUtil.createContainedBmObjectUri("engine", "StyleItem.BrandColor", "/appearanceItem")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ref.setColor((StyleColor)proxy);
        assertEquals("Style.BrandColor", AppearanceValueBuilder.renderColor(ref)); //$NON-NLS-1$
    }

    @Test
    public void testAutoColorAndUnknownValuesRender()
    {
        assertEquals("Auto", AppearanceValueBuilder.renderColor(McoreFactory.eINSTANCE.createAutoColor())); //$NON-NLS-1$
        assertNull(AppearanceValueBuilder.renderColor(null));
        assertNull(AppearanceValueBuilder.renderFont(null));
        assertEquals("Absolute font", AppearanceValueBuilder.renderFont(McoreFactory.eINSTANCE.createFontDef())); //$NON-NLS-1$
        assertNull("a reference with no target is not identified by its overrides", //$NON-NLS-1$
            AppearanceValueBuilder.renderFont(McoreFactory.eINSTANCE.createFontRef()));
        assertNull(AppearanceValueBuilder.renderColor(McoreFactory.eINSTANCE.createColorRef()));
    }

    @Test
    public void testAnAutoFontRendersWithItsOverrides()
    {
        com._1c.g5.v8.dt.mcore.AutoFont auto = McoreFactory.eINSTANCE.createAutoFont();
        auto.setBold(true);
        assertEquals("Auto (bold)", AppearanceValueBuilder.renderFont(auto)); //$NON-NLS-1$
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static Result color(String json, Configuration items, IEObjectProvider catalogue)
    {
        return AppearanceValueBuilder.buildColor(parse(json), items, catalogue);
    }

    private static Result font(String json, Configuration items, IEObjectProvider catalogue)
    {
        return AppearanceValueBuilder.buildFont(parse(json), items, catalogue);
    }

    private static JsonElement parse(String json)
    {
        return JsonParser.parseString(json);
    }

    private static StyleItem styleItem(String name, EObject appearance)
    {
        StyleItem item = MdClassFactory.eINSTANCE.createStyleItem();
        item.setName(name);
        if (appearance instanceof StyleColor)
        {
            item.setAppearanceItem((StyleColor)appearance);
        }
        else
        {
            item.setAppearanceItem((StyleFont)appearance);
        }
        return item;
    }

    private static Configuration items(StyleItem... styleItems)
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        for (StyleItem item : styleItems)
        {
            configuration.getStyleItems().add(item);
        }
        return configuration;
    }

    /**
     * A stub platform catalogue: an exact index of the given names, each description handing back a
     * fresh unresolved proxy whose URI fragment is the name - the shape the platform loaders build.
     */
    static IEObjectProvider catalogue(String resource, String... names)
    {
        boolean fonts = resource.startsWith("v8:/Fonts"); //$NON-NLS-1$
        EClass proxyClass = fonts ? McorePackage.Literals.FONT_DEF : McorePackage.Literals.COLOR_DEF;
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        List<IEObjectDescription> descriptions = new ArrayList<>();
        for (String name : names)
        {
            URI uri = URI.createURI(resource).appendFragment("/" + name); //$NON-NLS-1$
            IEObjectDescription desc = Mockito.mock(IEObjectDescription.class);
            Mockito.doReturn(QualifiedName.create(name.split("\\."))).when(desc).getName(); //$NON-NLS-1$
            Mockito.doReturn(uri).when(desc).getEObjectURI();
            Mockito.doAnswer(invocation -> proxy(proxyClass, uri)).when(desc).getEObjectOrProxy();
            descriptions.add(desc);
            Mockito.doAnswer(invocation -> proxy(proxyClass, uri)).when(provider).getProxy(name);
        }
        Mockito.doReturn(descriptions).when(provider).getEObjectDescriptions(Mockito.any());
        return provider;
    }

    private static EObject proxy(EClass eClass, URI uri)
    {
        EObject proxy = EcoreUtil.create(eClass);
        ((InternalEObject)proxy).eSetProxyURI(uri);
        return proxy;
    }
}
