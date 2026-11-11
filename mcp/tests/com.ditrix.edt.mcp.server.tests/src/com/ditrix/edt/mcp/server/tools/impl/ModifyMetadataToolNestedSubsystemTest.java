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
import org.eclipse.emf.common.util.EList;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;

/**
 * Issue #897: {@code modify_metadata}'s generic {@code properties} path addresses a NESTED subsystem
 * ({@code Subsystem.<Parent>.Subsystem.<Child>}) the way {@code content}, {@code create_metadata},
 * {@code delete_metadata} and {@code get_metadata_details} already do - through the shared subsystem
 * descent - and resolves it as the separate TOP object it is, never as a member of its parent.
 *
 * <p>The seams are reached by reflection so this suite compiles against the production file as it was
 * before the fix (the same signatures exist there), and every new behaviour has a failing baseline.</p>
 */
public class ModifyMetadataToolNestedSubsystemTest
{
    /** "Подсистема" (Subsystem), from code points so the source stays ASCII. */
    private static final String RU_SUBSYSTEM = "Подсистема";

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

    private MetadataScope scope()
    {
        return MetadataScope.ofConfiguration(configuration);
    }

    // ---- the reflective view of ModifyMetadataTool.resolveModifyTarget ----------------------------

    private Resolved resolve(String fqn)
    {
        return new Resolved(invoke("resolveModifyTarget",
            new Class<?>[] {MetadataScope.class, String.class, String.class},
            scope(), fqn, MetadataTypeUtils.normalizeFqn(fqn)));
    }

    private static Object invoke(String name, Class<?>[] types, Object... arguments)
    {
        try
        {
            Method method = ModifyMetadataTool.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("The modify resolution seam must exist and be callable: " + name, e);
        }
    }

    private static Object field(Object owner, String name)
    {
        try
        {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("The resolution result must expose " + name, e);
        }
    }

    private static final class Resolved
    {
        final String error;
        final MetadataNodeResolver.MetadataNode node;
        final String normFqn;

        Resolved(Object result)
        {
            error = (String)field(result, "error");
            node = (MetadataNodeResolver.MetadataNode)field(result, "node");
            normFqn = (String)field(result, "normFqn");
        }
    }

    private void assertResolvesB(String address)
    {
        Resolved resolved = resolve(address);
        assertNull("the nested chain must resolve, not be refused: " + resolved.error, resolved.error);
        assertSame("the chain names the LEAF, never its parent", b, resolved.node.object);
        assertTrue("a nested subsystem is a separate TOP object (its own .mdo), not a member of the parent",
            resolved.node.topLevel);
        assertSame("its parent's subsystems list registers it", a, resolved.node.owner);
        assertSame(MdClassPackage.Literals.SUBSYSTEM__SUBSYSTEMS, resolved.node.feature);
        assertEquals("the write goes on under the canonical English chain",
            "Subsystem.A.Subsystem.B", resolved.normFqn);
    }

    // ---- the address forms -------------------------------------------------------------------------

    @Test
    public void testEnglishNestedChainResolvesTheLeafForProperties()
    {
        assertResolvesB("Subsystem.A.Subsystem.B");
    }

    @Test
    public void testRussianLeadingAndNonLeadingTokensResolveTheSameLeaf()
    {
        assertResolvesB(RU_SUBSYSTEM + ".A." + RU_SUBSYSTEM + ".B");
        assertResolvesB("Subsystem.A." + RU_SUBSYSTEM + ".B");
    }

    @Test
    public void testNameCaseResolvesTheStoredNames()
    {
        assertResolvesB("Subsystem.a.Subsystem.b");
    }

    @Test
    public void testRussianProgrammaticNamesResolve()
    {
        a.setName("План");
        b.setName("Заказ");
        Resolved resolved = resolve(RU_SUBSYSTEM + "." + a.getName() + "." + RU_SUBSYSTEM + "." + b.getName());
        assertNull(resolved.error);
        assertSame(b, resolved.node.object);
        assertEquals("Subsystem." + a.getName() + ".Subsystem." + b.getName(), resolved.normFqn);
    }

    @Test
    public void testDeepChainResolvesTheThirdLevel()
    {
        Subsystem c = subsystem("C", b);
        Resolved resolved = resolve("Subsystem.A.Subsystem.B.Subsystem.C");
        assertNull(resolved.error);
        assertSame(c, resolved.node.object);
        assertSame(b, resolved.node.owner);
        assertEquals("Subsystem.A.Subsystem.B.Subsystem.C", resolved.normFqn);
    }

    @Test
    public void testYoSpellingFallsBackLikeATopObject()
    {
        // create_metadata stores 'ye' for 'yo' by default; modify's top-object addresses already retry
        // the yo-normalized FQN, and a nested chain must not be the one address that does not.
        b.setName("Елка");
        Resolved resolved = resolve("Subsystem.A.Subsystem.Ёлка");
        assertNull(resolved.error);
        assertSame(b, resolved.node.object);
        assertEquals("Subsystem.A.Subsystem.Елка", resolved.normFqn);
    }

    @Test
    public void testTopLevelSubsystemKeepsTheGenericConfigurationNode()
    {
        Resolved resolved = resolve("Subsystem.A");
        assertNull(resolved.error);
        assertSame(a, resolved.node.object);
        assertSame("a top-level subsystem is still registered by the configuration", configuration,
            resolved.node.owner);
        assertEquals("Subsystem.A", resolved.normFqn);
    }

    // ---- refusals ----------------------------------------------------------------------------------

    @Test
    public void testMissingLeafTeachesTheChainGrammarNotTheParent()
    {
        String address = "Subsystem.A.Subsystem.Missing";
        Resolved resolved = resolve(address);
        assertNull(resolved.node);
        assertNotNull(resolved.error);
        assertTrue(resolved.error, resolved.error.contains("Subsystem not found: " + address));
        assertTrue(resolved.error, resolved.error.contains(SubsystemUtils.addressingHint()));
        assertFalse("the generic 'Node not found' says nothing about chains: " + resolved.error,
            resolved.error.contains("Node not found"));
    }

    @Test
    public void testMissingMiddleLevelDoesNotResolveAnExistingDescendant()
    {
        Resolved resolved = resolve("Subsystem.A.Subsystem.Missing.Subsystem.B");
        assertNull(resolved.node);
        assertTrue(resolved.error, resolved.error.contains(SubsystemUtils.addressingHint()));
    }

    @Test
    public void testSynonymDoesNotAddressAChild()
    {
        b.getSynonym().put("en", "DisplayName");
        Resolved resolved = resolve("Subsystem.A.Subsystem.DisplayName");
        assertNull(resolved.node);
        assertTrue(resolved.error, resolved.error.contains("Subsystem not found"));
    }

    @Test
    public void testTrailingSeparatorIsRefusedNotResolvedAsTheChild()
    {
        String address = "Subsystem.A.Subsystem.B.";
        Resolved resolved = resolve(address);
        assertNull("a stray '.' has no single reading and must not write on B", resolved.node);
        assertTrue(resolved.error, resolved.error.contains("EMPTY segment"));
        assertTrue(resolved.error, resolved.error.contains("Nothing was changed"));
    }

    @Test
    public void testPaddedNameIsRefusedNotResolvedAsTheChild()
    {
        Resolved resolved = resolve("Subsystem.A.Subsystem. B ");
        assertNull(resolved.node);
        assertTrue(resolved.error, resolved.error.contains("padded segment"));
    }

    @Test
    public void testAChainThatDoesNotParseIsRefusedByTheSharedRuleNotNotFound()
    {
        // Review round 1: a chain request that does not parse (an empty mid segment, a foreign kind)
        // used to skip the subsystem refusal and fall into the generic "Node not found".
        Resolved empty = resolve("Subsystem.A..Subsystem.B");
        assertNull(empty.node);
        assertTrue(empty.error, empty.error.contains("EMPTY segment"));
        assertTrue(empty.error, empty.error.contains("Nothing was changed"));
        assertFalse(empty.error, empty.error.contains("Node not found"));
        Resolved foreign = resolve("Subsystem.A.Catalog.B");
        assertNull(foreign.node);
        assertTrue(foreign.error, foreign.error.contains("is not a subsystem address"));
        assertFalse(foreign.error, foreign.error.contains("Node not found"));
    }

    @Test
    public void testTheEntryRefusalCoversEveryPayloadButNotACommandInterface()
    {
        // Asked in executeOnUiThread before the context (and the vendor-support lookup) is resolved, so
        // a content payload on 'Subsystem.A.Subsystem.B.' is refused too, not written on B.
        Class<?>[] types = {String.class};
        String trailing = (String)invoke("malformedChainRefusal", types, "Subsystem.A.Subsystem.B.");
        assertNotNull(trailing);
        assertTrue(trailing, trailing.contains("EMPTY segment"));
        assertNull("a section's command interface is a valid address below a subsystem",
            invoke("malformedChainRefusal", types, "Subsystem.A.CommandInterface"));
        assertNull(invoke("malformedChainRefusal", types, "Subsystem.A.Subsystem.B"));
        assertNull(invoke("malformedChainRefusal", types, "Catalog.Goods.Attribute.Weight"));
    }

    // ---- the write that follows -------------------------------------------------------------------

    @Test
    public void testHeadlessWriteReResolvesTheCanonicalChain()
    {
        // The headless write re-resolves the node from the address resolveModifyTarget handed on - the
        // canonical chain. Through the containment grammar alone it answered "Node no longer exists".
        Object node = invoke("resolveGenericWriteNode", new Class<?>[] {MetadataScope.class, String.class},
            scope(), "Subsystem.A.Subsystem.B");
        assertNotNull("the canonical nested chain must re-resolve for the write", node);
        assertSame(b, ((MetadataNodeResolver.MetadataNode)node).object);
        Object top = invoke("resolveGenericWriteNode", new Class<?>[] {MetadataScope.class, String.class},
            scope(), "Subsystem.A");
        assertSame(a, ((MetadataNodeResolver.MetadataNode)top).object);
    }

    @Test
    public void testTheWriteReFetchesTheNestedSubsystemByItsOwnBmId()
    {
        // The decisive consequence of 'topLevel': the transaction re-fetches the nested subsystem ITSELF
        // by its bm id. As a member it would re-fetch the PARENT and look the leaf up in a collection the
        // parent only refers to.
        Subsystem bmLeaf = bmSubsystem("B", a, 77L);
        a.getSubsystems().clear();
        a.getSubsystems().add(bmLeaf);
        Resolved resolved = resolve("Subsystem.A.Subsystem.B");
        assertSame(bmLeaf, resolved.node.object);
        Object plan = invoke("resolveBmFetchPlan", new Class<?>[] {MetadataScope.class,
            MetadataNodeResolver.MetadataNode.class, MdObject.class, String[].class},
            scope(), resolved.node, resolved.node.object, resolved.normFqn.split("\\."));
        assertNull((String)field(plan, "error"));
        assertEquals(77L, ((Long)field(plan, "topBmId")).longValue());
        assertNull("no member re-navigation inside the transaction", field(plan, "memberFeature"));
    }

    private static Subsystem bmSubsystem(String name, Subsystem parent, long bmId)
    {
        Subsystem object = mock(Subsystem.class, withSettings().extraInterfaces(IBmObject.class));
        EList<Subsystem> children = new BasicEList<>();
        when(object.getName()).thenReturn(name);
        when(object.getParentSubsystem()).thenReturn(parent);
        when(object.getSubsystems()).thenReturn(children);
        when(((IBmObject)object).bmIsTop()).thenReturn(true);
        when(((IBmObject)object).bmGetId()).thenReturn(bmId);
        return object;
    }
}
