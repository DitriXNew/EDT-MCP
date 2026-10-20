/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.eclipse.emf.common.util.BasicEList;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;

/**
 * Nested-subsystem deletion decisions without a workbench. Reflection lets this same suite compile
 * against the base production file, so every new behavior has its own failing baseline assertion.
 */
public class DeleteMetadataToolNestedSubsystemTest
{
    private static final String RU_SUBSYSTEM = "\u041f\u043e\u0434\u0441\u0438\u0441\u0442\u0435\u043c\u0430";
    private Configuration configuration;
    private Subsystem a;
    private Subsystem b;

    @Before
    public void setUp()
    {
        configuration = MdClassFactory.eINSTANCE.createConfiguration();
        a = subsystem("A", null);
        b = subsystem("B", a);
        configuration.getSubsystems().add(a);
    }

    private static Subsystem subsystem(String name, Subsystem parent)
    {
        Subsystem child = MdClassFactory.eINSTANCE.createSubsystem();
        child.setName(name);
        if (parent != null)
        {
            parent.getSubsystems().add(child);
            child.setParentSubsystem(parent);
        }
        return child;
    }

    private Target target(String fqn)
    {
        Object result = invoke("resolveNestedSubsystemTarget",
            new Class<?>[] {MetadataScope.class, String.class},
            MetadataScope.ofConfiguration(configuration), MetadataTypeUtils.normalizeFqn(fqn));
        return result == null ? null : new Target(result);
    }

    private static Object invoke(String name, Class<?>[] types, Object... arguments)
    {
        try
        {
            Method method = DeleteMetadataTool.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("The delete decision seam must exist and be callable: " + name, e);
        }
    }

    private static final class Target
    {
        final Subsystem object;
        final String fqn;
        final String containerFqn;
        final String error;

        Target(Object result)
        {
            object = (Subsystem)field(result, "object");
            fqn = (String)field(result, "fqn");
            containerFqn = (String)field(result, "containerFqn");
            error = (String)field(result, "error");
        }

        private static Object field(Object result, String name)
        {
            try
            {
                Field field = result.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(result);
            }
            catch (ReflectiveOperationException e)
            {
                throw new AssertionError("The resolved delete must expose " + name, e);
            }
        }
    }

    private void assertLeaf(String address)
    {
        useBmParent();
        Target target = target(address);
        assertNotNull(target);
        assertNull(target.error);
        assertSame(b, target.object);
        assertEquals("Subsystem.A.Subsystem.B", target.fqn);
        assertEquals("Subsystem.A", target.containerFqn);
        assertSame(a, b.getParentSubsystem());
        assertTrue(a.getSubsystems().contains(b));
    }

    /** Factory objects are unattached BM objects; only an attached parent can name its export. */
    private void useBmParent()
    {
        Subsystem parent = bmSubsystem(a.getName(), null, "Subsystem." + a.getName());
        parent.getSubsystems().add(b);
        b.setParentSubsystem(parent);
        configuration.getSubsystems().clear();
        configuration.getSubsystems().add(parent);
        a = parent;
    }

    private static void assertRefused(Target target, String address, String reason)
    {
        assertNotNull(target);
        assertNull("a refusal must expose no deletable object", target.object);
        assertNull(target.fqn);
        assertNull(target.containerFqn);
        assertTrue(target.error, target.error.contains(address));
        assertTrue(target.error, target.error.contains(reason));
    }

    @Test
    public void testEnglishNestedChainResolvesOnlyTheLeaf()
    {
        assertLeaf("Subsystem.A.Subsystem.B");
    }

    @Test
    public void testRussianNonLeadingTokenResolvesTheSameLeaf()
    {
        assertLeaf("Subsystem.A." + RU_SUBSYSTEM + ".B");
    }

    @Test
    public void testRussianLeadingTokenAndCaseResolveStoredNames()
    {
        assertLeaf(RU_SUBSYSTEM + ".a.Subsystem.b");
    }

    @Test
    public void testRussianProgrammaticNamesAreResolved()
    {
        a.setName("\u041f\u043b\u0430\u043d");
        b.setName("\u0417\u0430\u043a\u0430\u0437");
        useBmParent();
        String address = "Subsystem." + a.getName() + "." + RU_SUBSYSTEM + "." + b.getName();
        Target target = target(address);
        assertSame(b, target.object);
        assertNull(target.error);
        assertEquals("Subsystem." + a.getName() + ".Subsystem." + b.getName(), target.fqn);
    }

    @Test
    public void testTrailingSeparatorIsRefusedNotResolvedAsTheChild()
    {
        String address = "Subsystem.A.Subsystem.B.";
        assertRefused(target(address), address, "EMPTY segment");
    }

    @Test
    public void testPaddedNameIsRefusedNotResolvedAsTheChild()
    {
        String address = "Subsystem.A.Subsystem. B ";
        assertRefused(target(address), address, "padded segment");
    }

    @Test
    public void testSynonymDoesNotAddressAChild()
    {
        b.getSynonym().put("en", "DisplayName");
        String address = "Subsystem.A.Subsystem.DisplayName";
        assertRefused(target(address), address, SubsystemUtils.addressingHint());
    }

    @Test
    public void testRegisteringContainerUsesTheParentsBmIdentity()
    {
        Subsystem parent = bmSubsystem("A", null, "Subsystem.A");
        configuration.getSubsystems().clear();
        configuration.getSubsystems().add(parent);
        Subsystem child = subsystem("B", parent);
        Target target = target("Subsystem.a.Subsystem.b");
        assertNull(target.error);
        assertSame(child, target.object);
        assertEquals("Subsystem.A.Subsystem.B", target.fqn);
        assertEquals("Subsystem.A", target.containerFqn);
    }

    @Test
    public void testDeepChainExportsTheImmediateParent()
    {
        Subsystem middle = bmSubsystem("B", a, "Subsystem.A.Subsystem.B");
        a.getSubsystems().clear();
        a.getSubsystems().add(middle);
        Subsystem leaf = subsystem("C", middle);
        Target target = target("Subsystem.A.Subsystem.B.Subsystem.C");
        assertNull(target.error);
        assertSame(leaf, target.object);
        assertEquals("Subsystem.A.Subsystem.B.Subsystem.C", target.fqn);
        assertEquals("Subsystem.A.Subsystem.B", target.containerFqn);
    }

    private static Subsystem bmSubsystem(String name, Subsystem parent, String fqn)
    {
        Subsystem object = mock(Subsystem.class, withSettings().extraInterfaces(IBmObject.class));
        when(object.getName()).thenReturn(name);
        when(object.getParentSubsystem()).thenReturn(parent);
        when(object.getSubsystems()).thenReturn(new BasicEList<>());
        when(((IBmObject)object).bmIsTop()).thenReturn(true);
        when(((IBmObject)object).bmGetFqn()).thenReturn(fqn);
        return object;
    }

    @Test
    public void testMismatchedLineageRefusesAnyTarget()
    {
        Subsystem other = subsystem("C", null);
        configuration.getSubsystems().add(other);
        b.setParentSubsystem(other);
        assertRefused(target("Subsystem.A.Subsystem.B"), "Subsystem.A.Subsystem.B",
            "the stored lineage of this subsystem does not spell the address");
        assertTrue(a.getSubsystems().contains(b));
        assertSame(other, b.getParentSubsystem());
    }

    @Test
    public void testMissingParentLinkRefusesAnyTarget()
    {
        b.setParentSubsystem(null);
        assertRefused(target("Subsystem.A.Subsystem.B"), "Subsystem.A.Subsystem.B", "stored lineage");
        assertTrue(a.getSubsystems().contains(b));
    }

    @Test
    public void testCyclicLineageRefusesAnyTarget()
    {
        a.setParentSubsystem(b);
        assertRefused(target("Subsystem.A.Subsystem.B"), "Subsystem.A.Subsystem.B", "stored lineage");
    }

    @Test
    public void testTopLevelKeepsTheGenericConfigurationContainer()
    {
        assertNull(target("Subsystem.A"));
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(
            MetadataScope.ofConfiguration(configuration), "Subsystem.A");
        assertSame(a, node.object);
        assertSame(configuration, node.owner);
    }

    @Test
    public void testMissingLeafTeachesTheChainGrammar()
    {
        String address = "Subsystem.A.Subsystem.Missing";
        Target target = target(address);
        assertRefused(target, address, SubsystemUtils.addressingHint());
        assertFalse(target.error.contains("Any node create_metadata can address"));
    }

    @Test
    public void testMissingParentDoesNotResolveAnExistingAncestor()
    {
        String address = "Subsystem.A.Subsystem.Missing.Subsystem.B";
        assertRefused(target(address), address, SubsystemUtils.addressingHint());
        assertTrue(a.getSubsystems().contains(b));
    }

    @Test
    public void testMalformedSubsystemLookupTeachesTheSharedGrammar()
    {
        String address = RU_SUBSYSTEM + ".A.Subsystem";
        String message = (String)invoke("nodeNotFound", new Class<?>[] {String.class}, address);
        assertTrue(message, message.contains(address));
        assertTrue(message, message.contains(SubsystemUtils.addressingHint()));
        assertFalse(message.contains("Any node create_metadata can address"));
    }

    @Test
    public void testNestedChainDoesNotRetryYoNames()
    {
        b.setName("\u0415\u043b\u043a\u0430");
        String address = "Subsystem.A.Subsystem.\u0401\u043b\u043a\u0430";
        assertRefused(target(address), address, SubsystemUtils.addressingHint());
    }


}
