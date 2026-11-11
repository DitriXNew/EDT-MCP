/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard.Intent;

/**
 * Issue #897, review round 1: the rule a subsystem chain address resolves and is refused by is ONE
 * rule for the vendor-support guard and the write, and for every write that takes a chain.
 *
 * <ul>
 * <li>The guard ({@link MetadataNodeResolver#resolveAddress} with the yo fallback) must judge the very
 * subsystem the {@code modify_metadata} write resolves: a child stored with 'ye' and addressed with 'yo'
 * is found by the write, so the guard must not fall back to its (editable) parent.</li>
 * <li>A request for a subsystem below a top-level one that is not a well-formed chain
 * ({@code Subsystem.A..Subsystem.B}, {@code Subsystem.A.Catalog.B}) is refused by
 * {@link SubsystemUtils#malformedChainError}, independently of whether the chain parses.</li>
 * </ul>
 *
 * <p>New seams are reached by reflection so the suite compiles against the code before this round.</p>
 */
public class NestedSubsystemChainRuleTest
{
    /** "Подсистема" (Subsystem), from code points so the source stays ASCII. */
    private static final String RU_SUBSYSTEM = "Подсистема";
    /** "КомандныйИнтерфейс" (CommandInterface). */
    private static final String RU_COMMAND_INTERFACE = "Командный"
        + "Интерфейс";
    /** The child's stored Name, spelled with 'ye' (as create_metadata stores it by default). */
    private static final String YE_NAME = "Елка";
    /** The same Name spelled with 'yo'. */
    private static final String YO_NAME = "Ёлка";

    private Configuration configuration;
    private Subsystem parent;
    private Subsystem child;
    private MetadataScope scope;
    private IModelEditingSupport support;

    @Before
    public void setUp()
    {
        configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setName("Cfg");
        parent = MdClassFactory.eINSTANCE.createSubsystem();
        parent.setName("A");
        child = MdClassFactory.eINSTANCE.createSubsystem();
        child.setName(YE_NAME);
        parent.getSubsystems().add(child);
        child.setParentSubsystem(parent);
        configuration.getSubsystems().add(parent);
        scope = MetadataScope.ofConfiguration(configuration);
        support = mock(IModelEditingSupport.class);
        when(support.canEdit(any(), any())).thenReturn(true);
        when(support.canDelete(any(), any())).thenReturn(true);
        VendorSupportGuard.setServiceForTests(() -> support);
    }

    @After
    public void tearDown()
    {
        VendorSupportGuard.setServiceForTests(null);
    }

    // ---- finding 1: the guard judges the subsystem the write changes ------------------------------

    @Test
    public void testGuardJudgesTheYoAddressedChildNotItsEditableParent()
    {
        when(support.canEdit(parent, EditingMode.DIRECT)).thenReturn(true);
        when(support.canEdit(child, EditingMode.DIRECT)).thenReturn(false);
        String address = "Subsystem.A.Subsystem." + YO_NAME;

        VendorSupportGuard.Target target = VendorSupportGuard.resolveTarget(scope, address, Intent.MODIFY);

        assertNotNull(target);
        assertSame("the guard must judge the child the write resolves, not fall back to its parent", child,
            target.object);
        assertTrue("the whole address resolved", target.exact);
        assertNotNull("a vendor-locked child must be refused even though its parent is editable",
            VendorSupportGuard.refusalForFqn(scope, address, Intent.MODIFY));
    }

    @Test
    public void testGuardAndWriteResolveTheSameSubsystem()
    {
        String address = "Subsystem.A.Subsystem." + YO_NAME;
        MetadataNodeResolver.ResolvedNode write = MetadataNodeResolver.resolveNestedSubsystem(scope, address, true);
        assertSame(child, write.node.object);
        assertSame("the guard's resolution and the write's are one rule", write.node.object,
            MetadataNodeResolver.resolveAddress(scope, address, true));
    }

    @Test
    public void testResolveAddressHonoursTheYoFlagForAChain()
    {
        String address = "Subsystem.A.Subsystem." + YO_NAME;
        assertSame(child, MetadataNodeResolver.resolveAddress(scope, address, true));
        assertNull("without the fallback (adopt_metadata_object) a chain resolves exactly",
            MetadataNodeResolver.resolveAddress(scope, address, false));
        assertSame(child, MetadataNodeResolver.resolveAddress(scope, "Subsystem.A.Subsystem." + YE_NAME, false));
    }

    @Test
    public void testATopLevelYoSubsystemIsJudgedToo()
    {
        Subsystem top = MdClassFactory.eINSTANCE.createSubsystem();
        top.setName(YE_NAME);
        configuration.getSubsystems().add(top);
        when(support.canEdit(top, EditingMode.DIRECT)).thenReturn(false);
        String address = "Subsystem." + YO_NAME;
        assertSame(top, MetadataNodeResolver.resolveAddress(scope, address, true));
        assertNotNull("the generic modify path finds a top-level subsystem through its yo twin, so the "
            + "guard must judge it", VendorSupportGuard.refusalForFqn(scope, address, Intent.MODIFY));
    }

    // ---- review round 2: the yo reading is decided per level ---------------------------------------

    /** A top-level subsystem with one child, linked both ways. */
    private Subsystem[] chain(String parentName, String childName)
    {
        Subsystem top = MdClassFactory.eINSTANCE.createSubsystem();
        top.setName(parentName);
        Subsystem leaf = MdClassFactory.eINSTANCE.createSubsystem();
        leaf.setName(childName);
        top.getSubsystems().add(leaf);
        leaf.setParentSubsystem(top);
        configuration.getSubsystems().add(top);
        return new Subsystem[] {top, leaf};
    }

    @Test
    public void testAYoParentAboveAYeChildResolvesByTheParentsStoredSpelling()
    {
        // The parent was created with normalizeYo=false (stored with 'yo'), the child with the default
        // ('ye'). Normalizing the WHOLE address would change the parent too and find nothing.
        Subsystem[] stored = chain("Ёж", YE_NAME);
        String address = "Subsystem.Ёж.Subsystem." + YO_NAME;
        assertSame(stored[1], MetadataNodeResolver.resolveAddress(scope, address, true));
        MetadataNodeResolver.ResolvedNode write = MetadataNodeResolver.resolveNestedSubsystem(scope, address, true);
        assertSame("the write resolves the same leaf the guard judges", stored[1], write.node.object);
        assertEquals("Subsystem.Ёж.Subsystem." + YE_NAME, write.fqn);
        assertNull("an exact-only caller still resolves exactly",
            MetadataNodeResolver.resolveAddress(scope, address, false));
    }

    @Test
    public void testAYeParentAboveAYoChildResolvesByTheChildsStoredSpelling()
    {
        Subsystem[] stored = chain("Еж", YO_NAME);
        String address = "Subsystem.Ёж.Subsystem." + YO_NAME;
        assertSame(stored[1], MetadataNodeResolver.resolveAddress(scope, address, true));
        assertSame(stored[1], MetadataNodeResolver.resolveNestedSubsystem(scope, address, true).node.object);
        assertNull(MetadataNodeResolver.resolveAddress(scope, address, false));
    }

    @Test
    public void testTheGuardJudgesTheLeafOfAMixedSpellingChain()
    {
        Subsystem[] stored = chain("Ёж", YE_NAME);
        when(support.canEdit(stored[0], EditingMode.DIRECT)).thenReturn(true);
        when(support.canEdit(stored[1], EditingMode.DIRECT)).thenReturn(false);
        String address = "Subsystem.Ёж.Subsystem." + YO_NAME;
        VendorSupportGuard.Target target = VendorSupportGuard.resolveTarget(scope, address, Intent.MODIFY);
        assertSame("never the editable parent", stored[1], target.object);
        assertTrue(target.exact);
        assertNotNull(VendorSupportGuard.refusalForFqn(scope, address, Intent.MODIFY));
    }

    @Test
    public void testTwoYoReadingsOfOneAddressResolveToNeither()
    {
        // 'yo'-parent with a 'ye'-child AND 'ye'-parent with a 'yo'-child: the address spelled with 'yo'
        // at both levels fits both. A write must not pick one of two different subsystems.
        chain("Ёж", YE_NAME);
        chain("Еж", YO_NAME);
        String address = "Subsystem.Ёж.Subsystem." + YO_NAME;
        assertNull(MetadataNodeResolver.resolveAddress(scope, address, true));
        assertNull(MetadataNodeResolver.resolveNestedSubsystem(scope, address, true).node);
    }

    // ---- finding 2: one malformed-chain rule ------------------------------------------------------

    @Test
    public void testAnEmptyMidSegmentIsAChainRequestRefusedByName()
    {
        String address = "Subsystem.A..Subsystem.B";
        assertNull("the chain does not parse", SubsystemUtils.nestedChain(address));
        String error = malformedChainError(address);
        assertNotNull(error);
        assertTrue(error, error.contains("EMPTY segment"));
        assertTrue(error, error.contains(address));
    }

    @Test
    public void testAForeignKindBelowASubsystemIsRefusedAsNotASubsystemAddress()
    {
        for (String address : new String[] {"Subsystem.A.Catalog.B", RU_SUBSYSTEM + ".A.Catalog.B",
            "Subsystem.A.Subsystem", "Subsystem.A.Subsystem.B.Attribute.C"})
        {
            String error = malformedChainError(address);
            assertNotNull(address, error);
            assertTrue(error, error.contains("is not a subsystem address"));
            assertTrue(error, error.contains(SubsystemUtils.addressingHint()));
        }
    }

    @Test
    public void testAStraySeparatorIsRefused()
    {
        assertTrue(malformedChainError("Subsystem.A.Subsystem.B.").contains("EMPTY segment"));
        assertTrue("a stray '.' after a TOP-level subsystem asks for nothing it can name",
            malformedChainError("Subsystem.A.").contains("EMPTY segment"));
        assertTrue(malformedChainError("Subsystem.A.Subsystem. B ").contains("padded segment"));
    }

    @Test
    public void testWellFormedAddressesAndTheCommandInterfaceAreNotRefused()
    {
        assertNull(malformedChainError("Subsystem.A.Subsystem.B"));
        assertNull(malformedChainError(RU_SUBSYSTEM + ".A." + RU_SUBSYSTEM + ".B"));
        assertNull(malformedChainError("Subsystem.A"));
        assertNull("a section's command interface is a valid address below a subsystem",
            malformedChainError("Subsystem.A.CommandInterface"));
        assertNull(malformedChainError(RU_SUBSYSTEM + ".A." + RU_COMMAND_INTERFACE));
        assertNull(malformedChainError("Subsystem.A.Subsystem.B.CommandInterface"));
        assertNull(malformedChainError("Catalog.Goods.Attribute.Weight"));
        assertNull(malformedChainError("Catalog.Goods.Subsystem"));
        assertNull(malformedChainError(null));
    }

    private static String malformedChainError(String fqn)
    {
        try
        {
            Method method = SubsystemUtils.class.getDeclaredMethod("malformedChainError", String.class);
            return (String)method.invoke(null, fqn);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("The shared malformed-chain rule must exist", e);
        }
    }

    @Test
    public void testTheGetMetadataDetailsClassifierIsTheSharedOne()
    {
        // GetMetadataDetailsTool keeps its own name for the predicate; its verdicts are those of the
        // shared classifier, pinned on the addresses its own test pins.
        assertEquals(Boolean.TRUE, invokeIsChainRequest("Subsystem.Sales.Subsystem"));
        assertEquals(Boolean.FALSE, invokeIsChainRequest("Subsystem.Sales"));
        assertEquals(Boolean.FALSE, invokeIsChainRequest("Subsystem.Sales.CommandInterface"));
        assertFalse(invokeIsChainRequest("Catalog.Goods.Subsystem"));
    }

    private static boolean invokeIsChainRequest(String fqn)
    {
        try
        {
            Method method = SubsystemUtils.class.getDeclaredMethod("isChainRequest", String.class);
            return (Boolean)method.invoke(null, fqn);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("The shared chain classifier must exist", e);
        }
    }
}
