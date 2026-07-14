/**
 * MCP Server for EDT - Tests
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

import java.util.Collections;
import java.util.List;

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

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.PropertyInfo;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.ValueKind;

/**
 * Tests {@link MetadataPropertyIntrospector} against real mdclass objects created via the EMF
 * factory (no live project needed - this is pure metamodel reflection).
 */
public class MetadataPropertyIntrospectorTest
{
    private static CatalogAttribute newAttribute()
    {
        return MdClassFactory.eINSTANCE.createCatalogAttribute();
    }

    private static Catalog newCatalog()
    {
        return MdClassFactory.eINSTANCE.createCatalog();
    }

    @Test
    public void testNameAndCommentAreAssignableStrings()
    {
        CatalogAttribute attr = newAttribute();
        PropertyInfo name = MetadataPropertyIntrospector.find(attr, "name"); //$NON-NLS-1$
        assertNotNull("name must be assignable", name); //$NON-NLS-1$
        assertTrue(name.valueKind == ValueKind.STRING);

        PropertyInfo comment = MetadataPropertyIntrospector.find(attr, "comment"); //$NON-NLS-1$
        assertNotNull("comment must be assignable", comment); //$NON-NLS-1$
        assertTrue(comment.valueKind == ValueKind.STRING);
    }

    @Test
    public void testSynonymIsLocalizedString()
    {
        PropertyInfo synonym = MetadataPropertyIntrospector.find(newAttribute(), "synonym"); //$NON-NLS-1$
        assertNotNull("synonym must be assignable", synonym); //$NON-NLS-1$
        assertTrue("synonym must be the localized-string kind", //$NON-NLS-1$
            synonym.valueKind == ValueKind.LOCALIZED_STRING);
    }

    @Test
    public void testSynonymCurrentValueRendersPerLanguageEntry()
    {
        CatalogAttribute attr = newAttribute();
        attr.getSynonym().put("en", "Weight"); //$NON-NLS-1$ //$NON-NLS-2$
        PropertyInfo synonym = MetadataPropertyIntrospector.find(attr, "synonym"); //$NON-NLS-1$
        assertNotNull(synonym);
        assertTrue("synonym current must render the per-language entry, got: " + synonym.currentValue, //$NON-NLS-1$
            "en=Weight".equals(synonym.currentValue)); //$NON-NLS-1$
    }

    @Test
    public void testEnumCurrentValueSharesAllowedVocabulary()
    {
        // After setting an enum to one of its literals, the rendered current value must be one of
        // the allowedValues (same vocabulary), so a client can compare Current vs Allowed.
        CatalogAttribute attr = newAttribute();
        PropertyInfo anyEnum = null;
        for (PropertyInfo info : MetadataPropertyIntrospector.introspect(attr))
        {
            if (info.valueKind == ValueKind.ENUM && !info.allowedValues.isEmpty())
            {
                anyEnum = info;
                break;
            }
        }
        assertNotNull(anyEnum);
        org.eclipse.emf.ecore.EEnumLiteral lit =
            MetadataPropertyIntrospector.resolveEnumLiteral(anyEnum.feature, anyEnum.allowedValues.get(0));
        assertNotNull(lit);
        attr.eSet(anyEnum.feature, lit.getInstance());
        PropertyInfo reread = MetadataPropertyIntrospector.find(attr, anyEnum.name);
        assertNotNull(reread.currentValue);
        assertTrue("enum current must be one of the allowed literal names", //$NON-NLS-1$
            reread.allowedValues.contains(reread.currentValue));
    }

    @Test
    public void testFindFeatureMatchesFindWithoutRenderingCurrentValue()
    {
        // findFeature is the per-property validation lookup: same feature / kind / allowedValues as
        // find(), but the current value is never rendered (validation never reads it, and find()'s
        // full introspect() renders the current value of EVERY assignable feature per lookup).
        CatalogAttribute attr = newAttribute();
        attr.getSynonym().put("en", "Weight"); //$NON-NLS-1$ //$NON-NLS-2$
        List<PropertyInfo> all = MetadataPropertyIntrospector.introspect(attr);
        assertFalse(all.isEmpty());
        for (PropertyInfo full : all)
        {
            PropertyInfo light = MetadataPropertyIntrospector.findFeature(attr, full.name);
            assertNotNull("findFeature must locate " + full.name, light); //$NON-NLS-1$
            assertTrue("feature must match for " + full.name, light.feature == full.feature); //$NON-NLS-1$
            assertTrue("kind must match for " + full.name, light.valueKind == full.valueKind); //$NON-NLS-1$
            assertTrue("allowedValues must match for " + full.name, //$NON-NLS-1$
                light.allowedValues.equals(full.allowedValues));
            assertNull("findFeature must not render a current value", light.currentValue); //$NON-NLS-1$
        }
        assertNull(MetadataPropertyIntrospector.findFeature(attr, "noSuchProperty")); //$NON-NLS-1$
        assertNull(MetadataPropertyIntrospector.findFeature(null, "name")); //$NON-NLS-1$
        assertNull(MetadataPropertyIntrospector.findFeature(attr, null));
    }

    @Test
    public void testAssignableNamesAgreeWithIntrospect()
    {
        // assignableNames uses a names-only iteration (no value rendering); it must list exactly
        // the names the full introspect() yields, in the same model feature order.
        CatalogAttribute attr = newAttribute();
        List<String> names = MetadataPropertyIntrospector.assignableNames(attr);
        List<PropertyInfo> all = MetadataPropertyIntrospector.introspect(attr);
        assertTrue("name count must agree", names.size() == all.size()); //$NON-NLS-1$
        for (int i = 0; i < all.size(); i++)
        {
            assertTrue("name #" + i + " must agree", names.get(i).equals(all.get(i).name)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertTrue(MetadataPropertyIntrospector.assignableNames(null).isEmpty());
    }

    @Test
    public void testAttributeTypeIsTypeDescription()
    {
        PropertyInfo type = MetadataPropertyIntrospector.find(newAttribute(), "type"); //$NON-NLS-1$
        assertNotNull("an attribute's type must be assignable", type); //$NON-NLS-1$
        assertTrue("type must be the TypeDescription kind", //$NON-NLS-1$
            type.valueKind == ValueKind.TYPE_DESCRIPTION);
    }

    @Test
    public void testAttributeHasAnEnumPropertyWithAllowedValues()
    {
        // A db-object attribute carries enum flags (indexing / fillChecking / ...). Don't hardcode
        // the exact name; assert that at least one ENUM property is present and exposes its literals.
        boolean foundEnumWithValues = false;
        for (PropertyInfo info : MetadataPropertyIntrospector.introspect(newAttribute()))
        {
            if (info.valueKind == ValueKind.ENUM && !info.allowedValues.isEmpty())
            {
                foundEnumWithValues = true;
                break;
            }
        }
        assertTrue("an attribute must expose at least one enum property with allowed values", //$NON-NLS-1$
            foundEnumWithValues);
    }

    @Test
    public void testResolveEnumLiteralIsCaseInsensitiveAndRejectsUnknown()
    {
        CatalogAttribute attr = newAttribute();
        PropertyInfo anyEnum = null;
        for (PropertyInfo info : MetadataPropertyIntrospector.introspect(attr))
        {
            if (info.valueKind == ValueKind.ENUM && !info.allowedValues.isEmpty())
            {
                anyEnum = info;
                break;
            }
        }
        assertNotNull("precondition: an enum property exists", anyEnum); //$NON-NLS-1$
        String literal = anyEnum.allowedValues.get(0);
        // exact + lower-case both resolve
        assertNotNull(MetadataPropertyIntrospector.resolveEnumLiteral(anyEnum.feature, literal));
        assertNotNull(MetadataPropertyIntrospector.resolveEnumLiteral(anyEnum.feature,
            literal.toLowerCase()));
        // a bogus value does not resolve
        assertNull(MetadataPropertyIntrospector.resolveEnumLiteral(anyEnum.feature, "NotARealLiteral_zzz")); //$NON-NLS-1$
    }

    @Test
    public void testContainmentChildrenAreNotAssignable()
    {
        // A Catalog's attributes / tabularSections / forms / commands are child collections created
        // via create_metadata, NOT assignable scalar properties.
        List<String> names = MetadataPropertyIntrospector.assignableNames(newCatalog());
        assertFalse("attributes (containment) must NOT be assignable", names.contains("attributes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("forms (containment) must NOT be assignable", names.contains("forms")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("tabularSections (containment) must NOT be assignable", //$NON-NLS-1$
            names.contains("tabularSections")); //$NON-NLS-1$
        // but the catalog's own scalar/flag properties ARE assignable
        assertTrue("comment must be assignable on a Catalog", names.contains("comment")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNullObjectYieldsEmpty()
    {
        assertTrue(MetadataPropertyIntrospector.introspect(null).isEmpty());
        assertNull(MetadataPropertyIntrospector.find(null, "name")); //$NON-NLS-1$
    }

    @Test
    public void testSubsystemContentIsManyReferenceWithTargetType()
    {
        // A Subsystem's `content` is a non-containment list of MdObject references -> MANY_REFERENCE,
        // reporting its (base) target type as the allowed value.
        PropertyInfo content = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createSubsystem(), "content"); //$NON-NLS-1$
        assertNotNull("a subsystem's content must be assignable", content); //$NON-NLS-1$
        assertTrue("content must be a MANY_REFERENCE", //$NON-NLS-1$
            content.valueKind == ValueKind.MANY_REFERENCE);
        assertFalse("a reference must report its allowed target type", //$NON-NLS-1$
            content.allowedValues.isEmpty());
    }

    @Test
    public void testSubsystemParentIsSingleReference()
    {
        PropertyInfo parent = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createSubsystem(), "parentSubsystem"); //$NON-NLS-1$
        assertNotNull("a subsystem's parentSubsystem must be assignable", parent); //$NON-NLS-1$
        assertTrue("parentSubsystem must be a single REFERENCE", //$NON-NLS-1$
            parent.valueKind == ValueKind.REFERENCE);
        assertTrue("parentSubsystem must report its Subsystem target type", //$NON-NLS-1$
            parent.allowedValues.contains("Subsystem")); //$NON-NLS-1$
    }

    @Test
    public void testStyleItemValueIsStyleValueKind()
    {
        // A StyleItem's `value` is a single-valued containment ref to an mcore Value (Color / Font).
        // It is assignable as the dedicated STYLE_VALUE kind (the generic containment-ref filter would
        // otherwise drop it), so modify_metadata can set the color / font.
        PropertyInfo value = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createStyleItem(), "value"); //$NON-NLS-1$
        assertNotNull("a StyleItem's value must be assignable", value); //$NON-NLS-1$
        assertTrue("value must be the STYLE_VALUE kind", value.valueKind == ValueKind.STYLE_VALUE); //$NON-NLS-1$
    }

    @Test
    public void testStyleItemColorValueRendersCurrent()
    {
        // After setting a ColorValue, the assignable "Current" must render the color (RGB / Auto),
        // proving the STYLE_VALUE current-render path is wired.
        com._1c.g5.v8.dt.metadata.mdclass.StyleItem item = MdClassFactory.eINSTANCE.createStyleItem();
        com._1c.g5.v8.dt.mcore.ColorValue cv = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createColorValue();
        com._1c.g5.v8.dt.mcore.ColorDef def = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createColorDef();
        def.setRed(255);
        def.setGreen(0);
        def.setBlue(0);
        cv.setValue(def);
        item.setValue(cv);
        PropertyInfo value = MetadataPropertyIntrospector.find(item, "value"); //$NON-NLS-1$
        assertNotNull(value);
        assertNotNull("the current color must render", value.currentValue); //$NON-NLS-1$
        assertTrue("the current must show the RGB color, got: " + value.currentValue, //$NON-NLS-1$
            value.currentValue.contains("RGB(255, 0, 0)")); //$NON-NLS-1$
    }

    @Test
    public void testAccountingRegisterChartOfAccountsIsSingleReference()
    {
        PropertyInfo coa = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createAccountingRegister(), "chartOfAccounts"); //$NON-NLS-1$
        assertNotNull("an AccountingRegister.chartOfAccounts must be assignable", coa); //$NON-NLS-1$
        assertTrue("chartOfAccounts must be a single REFERENCE", coa.valueKind == ValueKind.REFERENCE); //$NON-NLS-1$
        assertTrue("chartOfAccounts must report its ChartOfAccounts target type", //$NON-NLS-1$
            coa.allowedValues.contains("ChartOfAccounts")); //$NON-NLS-1$
    }

    // ---- BasicCommand.group (issue #262) --------------------------------------------------------
    //
    // BasicCommand.getGroup() is declared against com._1c.g5.v8.dt.mcore.CommandGroup - the base
    // interface both the metadata com._1c.g5.v8.dt.metadata.mdclass.CommandGroup (a real top MdObject,
    // FQN-addressable) and the platform's StandardCommandGroup (a built-in group, addressed by an enum
    // category - out of scope here) implement. The generic MdObject-subtype check alone would miss it
    // (the DECLARED target type is the mcore interface, not the mdclass one), so 'group' was previously
    // unclassified (excluded from the assignable set) on every BasicCommand subtype.

    @Test
    public void testDataProcessorCommandGroupIsSingleReference()
    {
        PropertyInfo group = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createDataProcessorCommand(), "group"); //$NON-NLS-1$
        assertNotNull("a DataProcessorCommand's group must be assignable", group); //$NON-NLS-1$
        assertTrue("group must be a single REFERENCE", group.valueKind == ValueKind.REFERENCE); //$NON-NLS-1$
        assertTrue("group must report its CommandGroup target type", //$NON-NLS-1$
            group.allowedValues.contains("CommandGroup")); //$NON-NLS-1$
    }

    @Test
    public void testCommonCommandGroupIsSingleReferenceToo()
    {
        // The fix is on the generic mcore-interface target type, not a one-off for a single
        // BasicCommand subtype - CommonCommand (a different EClass entirely) must classify the same.
        PropertyInfo group = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createCommonCommand(), "group"); //$NON-NLS-1$
        assertNotNull("a CommonCommand's group must be assignable", group); //$NON-NLS-1$
        assertTrue("group must be a single REFERENCE", group.valueKind == ValueKind.REFERENCE); //$NON-NLS-1$
    }

    @Test
    public void testCommandGroupOwnSuppressObjectStaysExcluded()
    {
        // A CommandGroup's own suppressObject is a plain EObject-typed reference (not an MdObject / mcore
        // CommandGroup target) - it must stay excluded, proving the new mcore-interface admission is
        // scoped to the CommandGroup target type and does not open the filter generally.
        List<String> names = MetadataPropertyIntrospector.assignableNames(
            MdClassFactory.eINSTANCE.createCommandGroup());
        assertFalse("suppressObject must stay excluded (not MdObject / CommandGroup typed)", //$NON-NLS-1$
            names.contains("suppressObject")); //$NON-NLS-1$
    }

    // ---- extInfo-aware overloads (issue #235) ---------------------------------------------------
    //
    // A form element carries its kind-specific / layout properties on a nested <extInfo> EObject (e.g.
    // a group's UsualGroupExtInfo group/united/showLeftMargin/...). These are exercised headlessly on a
    // synthetic EPackage (newGroupFixture) shaped like the form metamodel - NO form-model dependency,
    // the introspector only sees EObjects/EClasses.

    @Test
    public void testFindFeatureResolvesExtInfoLayoutEnum()
    {
        GroupFixture fx = newGroupFixture();
        PropertyInfo group = MetadataPropertyIntrospector.findFeature(fx.group, fx.extInfo, "group"); //$NON-NLS-1$
        assertNotNull("the group layout enum lives on the extInfo and must resolve", group); //$NON-NLS-1$
        assertTrue("group must be an ENUM", group.valueKind == ValueKind.ENUM); //$NON-NLS-1$
        assertTrue("group must be reported as living on the extInfo", group.onExtInfo); //$NON-NLS-1$
        assertSame("the resolved feature must be the extInfo's own feature", //$NON-NLS-1$
            fx.extInfoEClass.getEStructuralFeature("group"), group.feature); //$NON-NLS-1$
        assertTrue("group must list its grouping literals", //$NON-NLS-1$
            group.allowedValues.contains("Horizontal") && group.allowedValues.contains("Vertical")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFindFeatureResolvesExtInfoBoolean()
    {
        GroupFixture fx = newGroupFixture();
        PropertyInfo united = MetadataPropertyIntrospector.findFeature(fx.group, fx.extInfo, "united"); //$NON-NLS-1$
        assertNotNull("the 'united' layout flag lives on the extInfo and must resolve", united); //$NON-NLS-1$
        assertTrue("united must be a BOOLEAN", united.valueKind == ValueKind.BOOLEAN); //$NON-NLS-1$
        assertTrue("united must be reported as living on the extInfo", united.onExtInfo); //$NON-NLS-1$
    }

    @Test
    public void testFindFeatureDirectWinsOnNameCollision()
    {
        // The synthetic extInfo declares a boolean 'name' that COLLIDES with the element's direct String
        // 'name'. The DIRECT feature must win: the resolved property is the element's String, onExtInfo
        // false, so a write targets the element - never the extInfo.
        GroupFixture fx = newGroupFixture();
        PropertyInfo name = MetadataPropertyIntrospector.findFeature(fx.group, fx.extInfo, "name"); //$NON-NLS-1$
        assertNotNull(name);
        assertFalse("a direct feature must win the name collision (not the extInfo one)", name.onExtInfo); //$NON-NLS-1$
        assertTrue("the winning direct 'name' is the element's String", name.valueKind == ValueKind.STRING); //$NON-NLS-1$
        assertSame("the resolved feature must be the element's own feature", //$NON-NLS-1$
            fx.group.eClass().getEStructuralFeature("name"), name.feature); //$NON-NLS-1$
    }

    @Test
    public void testFindFeatureDirectFeatureHasOnExtInfoFalse()
    {
        GroupFixture fx = newGroupFixture();
        PropertyInfo id = MetadataPropertyIntrospector.findFeature(fx.group, fx.extInfo, "id"); //$NON-NLS-1$
        assertNotNull(id);
        assertTrue("id is a direct INTEGER", id.valueKind == ValueKind.INTEGER); //$NON-NLS-1$
        assertFalse("a direct feature is never onExtInfo", id.onExtInfo); //$NON-NLS-1$
    }

    @Test
    public void testFindFeatureWithNullExtInfoResolvesOnlyDirect()
    {
        // A null extInfo (the element has no extInfo instance yet) still resolves the DIRECT features but
        // cannot reach an extInfo-only property.
        GroupFixture fx = newGroupFixture();
        PropertyInfo name = MetadataPropertyIntrospector.findFeature(fx.group, null, "name"); //$NON-NLS-1$
        assertNotNull("a direct feature must resolve even without an extInfo", name); //$NON-NLS-1$
        assertFalse(name.onExtInfo);
        assertNull("an extInfo-only property is unreachable without the extInfo instance", //$NON-NLS-1$
            MetadataPropertyIntrospector.findFeature(fx.group, null, "group")); //$NON-NLS-1$
    }

    @Test
    public void testFindFeatureUnknownReturnsNull()
    {
        GroupFixture fx = newGroupFixture();
        assertNull(MetadataPropertyIntrospector.findFeature(fx.group, fx.extInfo, "noSuchProperty")); //$NON-NLS-1$
        // The raw containment 'extInfo' reference is not itself an assignable property.
        assertNull(MetadataPropertyIntrospector.findFeature(fx.group, fx.extInfo, "extInfo")); //$NON-NLS-1$
    }

    @Test
    public void testAssignableNamesUnionDirectThenExtInfo()
    {
        GroupFixture fx = newGroupFixture();
        List<String> union = MetadataPropertyIntrospector.assignableNames(fx.group, fx.extInfoEClass);
        for (String direct : new String[] {"name", "id", "type"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("union must keep the direct property " + direct, union.contains(direct)); //$NON-NLS-1$
        }
        for (String ext : new String[] {"group", "united", "showLeftMargin", "throughAlign", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "currentRowUse", "representation"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue("union must add the extInfo property " + ext, union.contains(ext)); //$NON-NLS-1$
        }
        // Direct-precedence: the colliding 'name' appears exactly once, and the direct names precede the
        // extInfo ones.
        assertEquals("the colliding 'name' must appear exactly once", //$NON-NLS-1$
            1, Collections.frequency(union, "name")); //$NON-NLS-1$
        assertTrue("direct names must precede extInfo names", //$NON-NLS-1$
            union.indexOf("type") < union.indexOf("group")); //$NON-NLS-1$ //$NON-NLS-2$
        // The raw containment extInfo reference is not listed.
        assertFalse("the raw extInfo containment ref must not be listed", union.contains("extInfo")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAssignableNamesNullExtInfoEqualsDirect()
    {
        GroupFixture fx = newGroupFixture();
        assertEquals("a null extInfo EClass must reduce to the direct-only listing", //$NON-NLS-1$
            MetadataPropertyIntrospector.assignableNames(fx.group),
            MetadataPropertyIntrospector.assignableNames(fx.group, (EClass)null));
    }

    @Test
    public void testIntrospectExtInfoEClassListsKindAndAllowedWithoutCurrent()
    {
        // The EClass overload lists the extInfo features (kind + allowed enum values) but, having no
        // instance, renders no current value.
        GroupFixture fx = newGroupFixture();
        PropertyInfo group = pick(
            MetadataPropertyIntrospector.introspect(fx.group, fx.extInfoEClass), "group"); //$NON-NLS-1$
        assertNotNull(group);
        assertTrue("group must be an ENUM", group.valueKind == ValueKind.ENUM); //$NON-NLS-1$
        assertTrue("group must be onExtInfo", group.onExtInfo); //$NON-NLS-1$
        assertTrue("group must list its allowed literals", //$NON-NLS-1$
            group.allowedValues.contains("Horizontal")); //$NON-NLS-1$
        assertNull("the EClass listing renders no current value", group.currentValue); //$NON-NLS-1$
    }

    @Test
    public void testIntrospectExtInfoInstanceRendersCurrent()
    {
        // The instance overload reads the current values off the live extInfo instance.
        GroupFixture fx = newGroupFixture();
        EStructuralFeature groupFeature = fx.extInfoEClass.getEStructuralFeature("group"); //$NON-NLS-1$
        fx.extInfo.eSet(groupFeature, fx.grouping.getEEnumLiteralByLiteral("Horizontal")); //$NON-NLS-1$
        fx.extInfo.eSet(fx.extInfoEClass.getEStructuralFeature("united"), Boolean.TRUE); //$NON-NLS-1$

        List<PropertyInfo> props = MetadataPropertyIntrospector.introspect(fx.group, fx.extInfo);
        PropertyInfo group = pick(props, "group"); //$NON-NLS-1$
        assertNotNull(group);
        assertTrue("group must be onExtInfo", group.onExtInfo); //$NON-NLS-1$
        assertEquals("the current group must render as the set literal name", "Horizontal", //$NON-NLS-1$ //$NON-NLS-2$
            group.currentValue);
        assertTrue("the current value must share the allowed vocabulary", //$NON-NLS-1$
            group.allowedValues.contains(group.currentValue));
        assertEquals("the current 'united' flag must render", "true", //$NON-NLS-1$ //$NON-NLS-2$
            pick(props, "united").currentValue); //$NON-NLS-1$
    }

    @Test
    public void testIntrospectDirectPropsPrecedeAndKeepOnExtInfoFalse()
    {
        GroupFixture fx = newGroupFixture();
        List<PropertyInfo> direct = MetadataPropertyIntrospector.introspect(fx.group);
        List<PropertyInfo> union = MetadataPropertyIntrospector.introspect(fx.group, fx.extInfoEClass);
        // The union starts with exactly the direct properties (same order), each still onExtInfo false.
        assertTrue("the union must be longer than the direct-only listing", union.size() > direct.size()); //$NON-NLS-1$
        for (int i = 0; i < direct.size(); i++)
        {
            assertEquals(direct.get(i).name, union.get(i).name);
            assertSame(direct.get(i).feature, union.get(i).feature);
            assertFalse("a direct property must stay onExtInfo false", union.get(i).onExtInfo); //$NON-NLS-1$
        }
    }

    @Test
    public void testResolveEnumLiteralOnExtInfoFeatureIsCaseInsensitive()
    {
        // The shared resolveEnumLiteral works for an extInfo feature exactly as for a direct one.
        GroupFixture fx = newGroupFixture();
        EStructuralFeature groupFeature = fx.extInfoEClass.getEStructuralFeature("group"); //$NON-NLS-1$
        EEnumLiteral lit = MetadataPropertyIntrospector.resolveEnumLiteral(groupFeature, "horizontal"); //$NON-NLS-1$
        assertNotNull("an extInfo enum must resolve case-insensitively", lit); //$NON-NLS-1$
        assertEquals("Horizontal", lit.getName()); //$NON-NLS-1$
        assertNull(MetadataPropertyIntrospector.resolveEnumLiteral(groupFeature, "NotAGrouping_zzz")); //$NON-NLS-1$
    }

    @Test
    public void testSingleArgIntrospectUnaffectedByExtInfoOverloads()
    {
        // The mdclass path is unchanged: a real object (no extInfo) introspected with a null extInfo
        // EClass yields exactly the single-arg listing.
        CatalogAttribute attr = newAttribute();
        List<String> viaNull = MetadataPropertyIntrospector.assignableNames(attr, (EClass)null);
        assertEquals(MetadataPropertyIntrospector.assignableNames(attr), viaNull);
        // And the direct listing never carries an onExtInfo property.
        for (PropertyInfo p : MetadataPropertyIntrospector.introspect(attr))
        {
            assertFalse("mdclass properties are never onExtInfo", p.onExtInfo); //$NON-NLS-1$
        }
    }

    /** Picks the property named {@code name} from a listing, or {@code null}. */
    private static PropertyInfo pick(List<PropertyInfo> props, String name)
    {
        for (PropertyInfo p : props)
        {
            if (p.name.equals(name))
            {
                return p;
            }
        }
        return null;
    }

    /**
     * A synthetic form-group-like element with its nested extInfo, built on a dynamic EPackage so the
     * extInfo-aware overloads can be exercised headlessly (no form-model dependency). The extInfo
     * declares the #235 layout features plus a boolean {@code name} that deliberately collides with the
     * element's direct {@code name} (to probe DIRECT-precedence).
     */
    private static final class GroupFixture
    {
        final EObject group;
        final EObject extInfo;
        final EClass extInfoEClass;
        final EEnum grouping;

        GroupFixture(EObject group, EObject extInfo, EClass extInfoEClass, EEnum grouping)
        {
            this.group = group;
            this.extInfo = extInfo;
            this.extInfoEClass = extInfoEClass;
            this.grouping = grouping;
        }
    }

    private static GroupFixture newGroupFixture()
    {
        EcoreFactory f = EcoreFactory.eINSTANCE;
        EPackage pkg = f.createEPackage();
        pkg.setName("introspectlike"); //$NON-NLS-1$
        pkg.setNsPrefix("introspectlike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/introspectlike"); //$NON-NLS-1$

        EEnum grouping = newEnum(f, "FormChildrenGroup", //$NON-NLS-1$
            "Auto", "Vertical", "Horizontal", "HorizontalIfPossible"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        EEnum throughAlign = newEnum(f, "UsualGroupThroughAlign", "Auto", "Use", "DontUse"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        EEnum currentRowUse = newEnum(f, "CurrentRowUse", "DontUse", "Use", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        EEnum representation = newEnum(f, "UsualGroupRepresentation", //$NON-NLS-1$
            "None", "StrongSeparation", "WeakSeparation"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        EEnum groupType = newEnum(f, "ManagedFormGroupType", "UsualGroup", "Pages", "Page"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        EClass extInfoBase = f.createEClass();
        extInfoBase.setName("FormItemExtInfo"); //$NON-NLS-1$
        extInfoBase.setAbstract(true);

        EClass usualGroupExtInfo = f.createEClass();
        usualGroupExtInfo.setName("UsualGroupExtInfo"); //$NON-NLS-1$
        usualGroupExtInfo.getESuperTypes().add(extInfoBase);
        addEnum(f, usualGroupExtInfo, "group", grouping); //$NON-NLS-1$
        addBoolean(f, usualGroupExtInfo, "united"); //$NON-NLS-1$
        addBoolean(f, usualGroupExtInfo, "showLeftMargin"); //$NON-NLS-1$
        addEnum(f, usualGroupExtInfo, "throughAlign", throughAlign); //$NON-NLS-1$
        addEnum(f, usualGroupExtInfo, "currentRowUse", currentRowUse); //$NON-NLS-1$
        addEnum(f, usualGroupExtInfo, "representation", representation); //$NON-NLS-1$
        // A boolean 'name' that COLLIDES with the element's direct String 'name' (precedence probe).
        addBoolean(f, usualGroupExtInfo, "name"); //$NON-NLS-1$

        EClass formGroup = f.createEClass();
        formGroup.setName("FormGroup"); //$NON-NLS-1$
        addString(f, formGroup, "name"); //$NON-NLS-1$
        addInt(f, formGroup, "id"); //$NON-NLS-1$
        addEnum(f, formGroup, "type", groupType); //$NON-NLS-1$
        EReference extInfoRef = f.createEReference();
        extInfoRef.setName("extInfo"); //$NON-NLS-1$
        extInfoRef.setEType(extInfoBase);
        extInfoRef.setContainment(true);
        extInfoRef.setUpperBound(1);
        formGroup.getEStructuralFeatures().add(extInfoRef);

        pkg.getEClassifiers().add(grouping);
        pkg.getEClassifiers().add(throughAlign);
        pkg.getEClassifiers().add(currentRowUse);
        pkg.getEClassifiers().add(representation);
        pkg.getEClassifiers().add(groupType);
        pkg.getEClassifiers().add(extInfoBase);
        pkg.getEClassifiers().add(usualGroupExtInfo);
        pkg.getEClassifiers().add(formGroup);

        EObject group = pkg.getEFactoryInstance().create(formGroup);
        EObject extInfo = pkg.getEFactoryInstance().create(usualGroupExtInfo);
        group.eSet(extInfoRef, extInfo);
        return new GroupFixture(group, extInfo, usualGroupExtInfo, grouping);
    }

    private static EEnum newEnum(EcoreFactory f, String name, String... literals)
    {
        EEnum eEnum = f.createEEnum();
        eEnum.setName(name);
        int value = 0;
        for (String literal : literals)
        {
            EEnumLiteral eLiteral = f.createEEnumLiteral();
            eLiteral.setName(literal);
            eLiteral.setLiteral(literal);
            eLiteral.setValue(value++);
            eEnum.getELiterals().add(eLiteral);
        }
        return eEnum;
    }

    private static void addString(EcoreFactory f, EClass owner, String name)
    {
        EAttribute attribute = f.createEAttribute();
        attribute.setName(name);
        attribute.setEType(EcorePackage.Literals.ESTRING);
        owner.getEStructuralFeatures().add(attribute);
    }

    private static void addInt(EcoreFactory f, EClass owner, String name)
    {
        EAttribute attribute = f.createEAttribute();
        attribute.setName(name);
        attribute.setEType(EcorePackage.Literals.EINT);
        owner.getEStructuralFeatures().add(attribute);
    }

    private static void addBoolean(EcoreFactory f, EClass owner, String name)
    {
        EAttribute attribute = f.createEAttribute();
        attribute.setName(name);
        attribute.setEType(EcorePackage.Literals.EBOOLEAN);
        owner.getEStructuralFeatures().add(attribute);
    }

    private static void addEnum(EcoreFactory f, EClass owner, String name, EEnum type)
    {
        EAttribute attribute = f.createEAttribute();
        attribute.setName(name);
        attribute.setEType(type);
        owner.getEStructuralFeatures().add(attribute);
    }
}
