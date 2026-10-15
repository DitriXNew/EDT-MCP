/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.PropertyInfo;

/**
 * Tests {@link MetadataPropertyIntrospector#introspectOnly}: the filtered introspection the
 * configuration root's assignable view is rendered with (issue #741). A root holds every object of the
 * configuration in its collections - thousands on an ERP-size configuration - so the filter must act
 * BEFORE a property's current value is read, not on the finished list.
 */
public class MetadataPropertyIntrospectorFilterTest
{
    @Test
    public void testOnlyTheAdmittedPropertiesComeBackInModelOrder()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        catalog.setComment("A comment"); //$NON-NLS-1$

        List<PropertyInfo> props = MetadataPropertyIntrospector.introspectOnly(catalog,
            name -> "comment".equals(name) || "synonym".equals(name)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Arrays.asList("synonym", "comment"), names(props)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("A comment", props.get(1).currentValue); //$NON-NLS-1$
    }

    @Test
    public void testARejectedPropertyIsNeverRead()
    {
        CountingObject obj = countingObject();

        List<PropertyInfo> props = MetadataPropertyIntrospector.introspectOnly(obj, "name"::equalsIgnoreCase); //$NON-NLS-1$

        assertEquals(Arrays.asList("name"), names(props)); //$NON-NLS-1$
        assertEquals("a property the filter rejects must not be read at all", 0, obj.readsOf("comment")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, obj.readsOf("name")); //$NON-NLS-1$
    }

    /** The control for the test above: the unfiltered introspection does read every property. */
    @Test
    public void testTheUnfilteredIntrospectionReadsEveryProperty()
    {
        CountingObject obj = countingObject();

        List<PropertyInfo> props = MetadataPropertyIntrospector.introspect(obj);

        assertEquals(Arrays.asList("name", "comment"), names(props)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, obj.readsOf("comment")); //$NON-NLS-1$
    }

    @Test
    public void testAdmittingEverythingIsTheUnfilteredIntrospection()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        catalog.setComment("A comment"); //$NON-NLS-1$
        catalog.setCodeLength(9);

        List<PropertyInfo> all = MetadataPropertyIntrospector.introspect(catalog);
        List<PropertyInfo> admitted = MetadataPropertyIntrospector.introspectOnly(catalog, name -> true);

        assertEquals(names(all), names(admitted));
        assertTrue("a catalog has plenty of assignable properties", all.size() > 10); //$NON-NLS-1$
        for (int i = 0; i < all.size(); i++)
        {
            assertEquals(all.get(i).name, all.get(i).currentValue, admitted.get(i).currentValue);
            assertEquals(all.get(i).name, all.get(i).valueKind, admitted.get(i).valueKind);
        }
    }

    private static List<String> names(List<PropertyInfo> props)
    {
        List<String> names = new ArrayList<>();
        for (PropertyInfo p : props)
        {
            names.add(p.name);
        }
        return names;
    }

    /** An object with two plain string properties, "name" and "comment", that counts its reads. */
    private static CountingObject countingObject()
    {
        EcoreFactory f = EcoreFactory.eINSTANCE;
        EPackage pkg = f.createEPackage();
        pkg.setName("countinglike"); //$NON-NLS-1$
        pkg.setNsPrefix("countinglike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/countinglike"); //$NON-NLS-1$
        EClass holder = f.createEClass();
        holder.setName("CountingHolder"); //$NON-NLS-1$
        for (String name : new String[] { "name", "comment" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            EAttribute attribute = f.createEAttribute();
            attribute.setName(name);
            attribute.setEType(EcorePackage.Literals.ESTRING);
            holder.getEStructuralFeatures().add(attribute);
        }
        pkg.getEClassifiers().add(holder);
        CountingObject obj = new CountingObject(holder);
        obj.eSet(holder.getEStructuralFeature("name"), "N"); //$NON-NLS-1$ //$NON-NLS-2$
        obj.eSet(holder.getEStructuralFeature("comment"), "C"); //$NON-NLS-1$ //$NON-NLS-2$
        return obj;
    }

    /** A dynamic EObject that counts how often each feature is read. */
    private static final class CountingObject extends DynamicEObjectImpl
    {
        private final List<String> reads = new ArrayList<>();

        CountingObject(EClass eClass)
        {
            super(eClass);
        }

        @Override
        public Object eGet(EStructuralFeature feature)
        {
            reads.add(feature.getName());
            return super.eGet(feature);
        }

        int readsOf(String name)
        {
            int count = 0;
            for (String read : reads)
            {
                if (read.equals(name))
                {
                    count++;
                }
            }
            return count;
        }
    }
}
