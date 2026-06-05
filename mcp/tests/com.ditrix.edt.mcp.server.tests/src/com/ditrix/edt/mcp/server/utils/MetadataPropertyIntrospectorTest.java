/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

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
}
