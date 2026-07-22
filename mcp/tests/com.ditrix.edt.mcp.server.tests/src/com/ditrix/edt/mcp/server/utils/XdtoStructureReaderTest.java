/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.QName;
import com._1c.g5.v8.dt.xdto.model.Enumeration;
import com._1c.g5.v8.dt.xdto.model.Import;
import com._1c.g5.v8.dt.xdto.model.ObjectType;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com._1c.g5.v8.dt.xdto.model.ValueType;
import com._1c.g5.v8.dt.xdto.model.XdtoFactory;

/**
 * Tests {@link XdtoStructureReader}: the pure Markdown renderer for an XDTO {@link Package} content. The
 * xdto.model package is NOT Tycho access-restricted (all public API, unlike the DCS "default settings"
 * subtree), so every fixture here is a REAL in-memory model built with the typed {@code XdtoFactory}
 * singleton - the same pattern {@link XdtoWriterTest} uses, mirroring {@code DcsStructureReaderTest}'s
 * typed (non-reflective) sections.
 */
public class XdtoStructureReaderTest
{
    private static Package newPackage()
    {
        return XdtoFactory.eINSTANCE.createPackage();
    }

    private static QName qname(String nsUri, String name)
    {
        QName q = McoreFactory.eINSTANCE.createQName();
        q.setNsUri(nsUri);
        q.setName(name);
        return q;
    }

    @Test
    public void testNullContentRendersNote()
    {
        String md = XdtoStructureReader.render("XDTOPackage.MyPackage", null); //$NON-NLS-1$
        assertTrue(md.contains("no package content")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyPackageRendersOnlyHeaderAndNamespace()
    {
        Package pkg = newPackage();
        pkg.setNsUri("http://example.org/MyPackage"); //$NON-NLS-1$
        String md = XdtoStructureReader.render("XDTOPackage.MyPackage", pkg); //$NON-NLS-1$
        assertTrue(md.contains("XDTOPackage.MyPackage")); //$NON-NLS-1$
        assertTrue(md.contains("http://example.org/MyPackage")); //$NON-NLS-1$
        assertFalse("an empty package must not render an Object types section", //$NON-NLS-1$
            md.contains("Object types")); //$NON-NLS-1$
        assertFalse("an empty package must not render a Dependencies section", //$NON-NLS-1$
            md.contains("Dependencies")); //$NON-NLS-1$
    }

    @Test
    public void testObjectTypeWithPropertiesAndFlags()
    {
        Package pkg = newPackage();
        pkg.setNsUri("http://example.org/MyPackage"); //$NON-NLS-1$
        ObjectType type = XdtoFactory.eINSTANCE.createObjectType();
        type.setName("Address"); //$NON-NLS-1$
        type.setOpen(true);
        // 'abstract' is deliberately left UNTOUCHED (never eSet) so the render's isSet-only
        // summarization can be asserted below - calling setAbstract(false) would itself mark it SET.
        pkg.getObjects().add(type);

        Property street = XdtoFactory.eINSTANCE.createProperty();
        street.setName("Street"); //$NON-NLS-1$
        street.setType(qname("http://www.w3.org/2001/XMLSchema", "string")); //$NON-NLS-1$ //$NON-NLS-2$
        street.setLowerBound(1);
        street.setUpperBound(1);
        street.setNillable(false);
        type.getProperties().add(street);

        String md = XdtoStructureReader.render("XDTOPackage.MyPackage", pkg); //$NON-NLS-1$
        assertTrue(md.contains("Object types")); //$NON-NLS-1$
        assertTrue(md.contains("Address")); //$NON-NLS-1$
        assertTrue("SET flags must be summarized (open=true)", md.contains("open=true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an UNSET flag (abstract was never set) must not appear", //$NON-NLS-1$
            md.contains("abstract=")); //$NON-NLS-1$
        assertTrue(md.contains("Street")); //$NON-NLS-1$
        assertTrue("the QName must render its localName", md.contains("string")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("SET bounds must render as [lower..upper]", md.contains("[1..1]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnboundedUpperRendersStar()
    {
        Package pkg = newPackage();
        ObjectType type = XdtoFactory.eINSTANCE.createObjectType();
        type.setName("Address"); //$NON-NLS-1$
        pkg.getObjects().add(type);
        Property items = XdtoFactory.eINSTANCE.createProperty();
        items.setName("Items"); //$NON-NLS-1$
        items.setLowerBound(0);
        items.setUpperBound(-1);
        type.getProperties().add(items);

        String md = XdtoStructureReader.render("XDTOPackage.MyPackage", pkg); //$NON-NLS-1$
        assertTrue(md.contains("[0..*]")); //$NON-NLS-1$
    }

    // ==================== fixed / default read-back (issue #183 P2 #2) ====================
    //
    // fixed/default are WRITABLE (XdtoWriter#applyPropertyProperties) but the structure render used to
    // drop them entirely from the property row - a caller reading the structure back after a write could
    // never see what it had just set.

    @Test
    public void testPropertyFixedAndDefaultRenderWhenSet()
    {
        Package pkg = newPackage();
        ObjectType type = XdtoFactory.eINSTANCE.createObjectType();
        type.setName("Address"); //$NON-NLS-1$
        pkg.getObjects().add(type);
        Property country = XdtoFactory.eINSTANCE.createProperty();
        country.setName("Country"); //$NON-NLS-1$
        country.setFixed(true);
        country.setDefault("N/A"); //$NON-NLS-1$
        type.getProperties().add(country);

        String md = XdtoStructureReader.render("XDTOPackage.MyPackage", pkg); //$NON-NLS-1$
        assertTrue("the property table header must advertise the Fixed column", md.contains("Fixed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the property table header must advertise the Default column", md.contains("Default")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a SET fixed=true must render", md.contains("true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a SET default must render its value", md.contains("N/A")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPropertyFixedAndDefaultOmittedWhenUnset()
    {
        Package pkg = newPackage();
        ObjectType type = XdtoFactory.eINSTANCE.createObjectType();
        type.setName("Address"); //$NON-NLS-1$
        pkg.getObjects().add(type);
        Property street = XdtoFactory.eINSTANCE.createProperty();
        street.setName("Street"); //$NON-NLS-1$
        // 'fixed' and 'default' are deliberately left UNTOUCHED - neither setFixed(...) nor setDefault(...)
        // is called, mirroring how testObjectTypeWithPropertiesAndFlags asserts an unset flag is absent.
        type.getProperties().add(street);

        String md = XdtoStructureReader.render("XDTOPackage.MyPackage", pkg); //$NON-NLS-1$
        assertFalse("an UNSET fixed must not render a boolean value", md.contains("| true |") //$NON-NLS-1$ //$NON-NLS-2$
            || md.contains("| false |")); //$NON-NLS-1$
    }

    @Test
    public void testPackageGlobalPropertiesSection()
    {
        Package pkg = newPackage();
        Property global = XdtoFactory.eINSTANCE.createProperty();
        global.setName("Version"); //$NON-NLS-1$
        global.setType(qname("http://www.w3.org/2001/XMLSchema", "string")); //$NON-NLS-1$ //$NON-NLS-2$
        pkg.getProperties().add(global);

        String md = XdtoStructureReader.render("XDTOPackage.MyPackage", pkg); //$NON-NLS-1$
        assertTrue(md.contains("Package-global properties")); //$NON-NLS-1$
        assertTrue(md.contains("Version")); //$NON-NLS-1$
    }

    @Test
    public void testValueTypeWithEnumerations()
    {
        Package pkg = newPackage();
        ValueType valueType = XdtoFactory.eINSTANCE.createValueType();
        valueType.setName("Status"); //$NON-NLS-1$
        Enumeration active = XdtoFactory.eINSTANCE.createEnumeration();
        active.setContent("Active"); //$NON-NLS-1$
        Enumeration inactive = XdtoFactory.eINSTANCE.createEnumeration();
        inactive.setContent("Inactive"); //$NON-NLS-1$
        valueType.getEnumerations().add(active);
        valueType.getEnumerations().add(inactive);
        pkg.getTypes().add(valueType);

        String md = XdtoStructureReader.render("XDTOPackage.MyPackage", pkg); //$NON-NLS-1$
        assertTrue(md.contains("Value types")); //$NON-NLS-1$
        assertTrue(md.contains("Status")); //$NON-NLS-1$
        assertTrue(md.contains("Active")); //$NON-NLS-1$
        assertTrue(md.contains("Inactive")); //$NON-NLS-1$
    }

    @Test
    public void testDependenciesSection()
    {
        Package pkg = newPackage();
        Import dependency = XdtoFactory.eINSTANCE.createImport();
        dependency.setNamespace("http://example.org/other"); //$NON-NLS-1$
        dependency.setLocation("other.xsd"); //$NON-NLS-1$
        pkg.getDependencies().add(dependency);

        String md = XdtoStructureReader.render("XDTOPackage.MyPackage", pkg); //$NON-NLS-1$
        assertTrue(md.contains("Dependencies")); //$NON-NLS-1$
        assertTrue(md.contains("http://example.org/other")); //$NON-NLS-1$
        assertTrue(md.contains("other.xsd")); //$NON-NLS-1$
    }
}
