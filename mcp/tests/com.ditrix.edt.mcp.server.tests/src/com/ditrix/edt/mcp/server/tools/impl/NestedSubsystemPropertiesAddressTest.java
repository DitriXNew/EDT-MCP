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
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataScopeTestFixtures;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/**
 * Issue #929: the properties of a NESTED subsystem are reachable the way {@code modify_metadata} writes
 * them. {@code get_metadata_details(assignable:true)} - the view that lists what {@code modify_metadata}
 * sets - resolved only the containment grammar, so a nested chain was "Object not found" there while the
 * write itself took the chain (issue #897). And the short address {@code Subsystem.<Child>} of a subsystem
 * that exists only as a nested one answered the generic "Node not found" / "Object not found", which does
 * not say that the subsystem exists and how it is addressed.
 *
 * <p>Every seam is reached through code that exists before the fix, so each test has a failing
 * baseline rather than a compile error.</p>
 */
public class NestedSubsystemPropertiesAddressTest
{
    /** "Подсистема" (Subsystem). */
    private static final String RU_SUBSYSTEM = "Подсистема";

    private Configuration configuration;
    private Subsystem sales;
    private Subsystem orders;

    @Before
    public void setUp()
    {
        configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setName("Cfg");
        sales = subsystem("Sales", null);
        orders = subsystem("Orders", sales);
        configuration.getSubsystems().add(sales);
        sales.setIncludeInCommandInterface(true);
        orders.setIncludeInCommandInterface(false);
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

    // ---- get_metadata_details(assignable:true) ------------------------------------------------------

    @Test
    public void testAssignableViewRendersTheNestedSubsystemByItsChain()
    {
        Rendered result = render("Subsystem.Sales.Subsystem.Orders", true);
        assertTrue("a nested chain must render, not fail: " + result.failures(), result.failures.isEmpty());
        assertTrue(result.body, result.body.startsWith("## Assignable properties: Subsystem.Sales.Subsystem.Orders\n"));
        assertTrue("the table lists what modify_metadata sets on a subsystem: " + result.body,
            result.body.contains("includeInCommandInterface"));
    }

    @Test
    public void testAssignableViewReadsTheLeafNotItsParent()
    {
        // The parent shows true, the child false: the Current column must be the child's.
        String row = row(render("Subsystem.Sales.Subsystem.Orders", true).body, "includeInCommandInterface");
        String[] cells = row.split("\\|");
        assertEquals("the Current value must be the CHILD's (false), not the parent's: " + row, "false",
            cells[3].trim());
    }

    @Test
    public void testAssignableViewTakesRussianTokensAndNamesTheCanonicalChain()
    {
        Rendered result = render(RU_SUBSYSTEM + ".Sales." + RU_SUBSYSTEM + ".Orders", true);
        assertTrue(result.failures(), result.failures.isEmpty());
        assertTrue(result.body, result.body.startsWith("## Assignable properties: Subsystem.Sales.Subsystem.Orders\n"));
    }

    @Test
    public void testAssignableViewResolvesADeepChain()
    {
        Subsystem backlog = subsystem("Backlog", orders);
        backlog.setIncludeInCommandInterface(true);
        Rendered result = render("Subsystem.Sales.Subsystem.Orders.Subsystem.Backlog", true);
        assertTrue(result.failures(), result.failures.isEmpty());
        assertTrue(result.body, result.body.startsWith(
            "## Assignable properties: Subsystem.Sales.Subsystem.Orders.Subsystem.Backlog\n"));
    }

    @Test
    public void testAssignableViewTakesTheYoSpellingLikeTheWrite()
    {
        // modify_metadata resolves a chain with the yo retry (issue #897); the view that lists what it
        // sets must find the same subsystem.
        orders.setName("Елка");
        Rendered result = render("Subsystem.Sales.Subsystem.Ёлка", true);
        assertTrue(result.failures(), result.failures.isEmpty());
        assertTrue(result.body, result.body.startsWith(
            "## Assignable properties: Subsystem.Sales.Subsystem.Елка\n"));
    }

    @Test
    public void testAssignableViewRefusesAMissingChainWithTheSubsystemText()
    {
        Rendered result = render("Subsystem.Sales.Subsystem.Missing", true);
        assertEquals("", result.body);
        assertEquals(GetMetadataDetailsTool.NESTED_SUBSYSTEM_NOT_FOUND, result.reason());
    }

    @Test
    public void testAssignableViewRefusesAMalformedChainLikeThePlainView()
    {
        Rendered result = render("Subsystem.Sales.Subsystem.Orders.", true);
        assertEquals("a stray '.' has no single reading: never the assignable table of Orders", "", result.body);
        assertTrue(result.reason(), result.reason().contains("EMPTY segment"));
    }

    // ---- the short address of a subsystem that exists only nested -----------------------------------

    @Test
    public void testPlainViewNamesTheChainOfANestedSubsystemAddressedShort()
    {
        Rendered result = render("Subsystem.Orders", false);
        assertEquals("", result.body);
        assertTrue(result.reason(), result.reason().contains("Subsystem.Sales.Subsystem.Orders"));
        assertTrue(result.reason(), result.reason().contains("whole chain"));
    }

    @Test
    public void testAssignableViewNamesTheChainOfANestedSubsystemAddressedShort()
    {
        Rendered result = render(RU_SUBSYSTEM + ".Orders", true);
        assertEquals("", result.body);
        assertTrue(result.reason(), result.reason().contains("Subsystem.Sales.Subsystem.Orders"));
    }

    @Test
    public void testModifyNamesTheChainOfANestedSubsystemAddressedShort()
    {
        String error = modifyRefusal("Subsystem.Orders");
        assertNotNull(error);
        assertTrue(error, error.contains("Subsystem.Sales.Subsystem.Orders"));
        assertTrue(error, error.contains("whole chain"));
    }

    @Test
    public void testEverySameNamedNestedSubsystemIsNamedAndNoneIsGuessed()
    {
        Subsystem purchases = subsystem("Purchases", null);
        configuration.getSubsystems().add(purchases);
        subsystem("orders", purchases);
        String error = modifyRefusal("Subsystem.Orders");
        assertTrue(error, error.contains("Subsystem.Sales.Subsystem.Orders"));
        assertTrue(error, error.contains("Subsystem.Purchases.Subsystem.orders"));
    }

    @Test
    public void testNoChainIsSuggestedWhenNoSubsystemHasTheName()
    {
        String error = modifyRefusal("Subsystem.Nowhere");
        assertNotNull(error);
        assertFalse(error, error.contains("whole chain"));
        Rendered result = render("Subsystem.Nowhere", false);
        assertFalse(result.reason(), result.reason().contains("whole chain"));
    }

    @Test
    public void testAnExistingTopLevelSubsystemIsNeverReportedAsMissing()
    {
        // 'Orders' also exists at the top: the short address names IT, so nothing is refused.
        Subsystem topOrders = subsystem("Orders", null);
        configuration.getSubsystems().add(topOrders);
        assertNull(modifyRefusal("Subsystem.Orders"));
    }

    @Test
    public void testTheSuggestionIsBounded()
    {
        for (int i = 0; i < 30; i++)
        {
            Subsystem parent = subsystem("P" + i, null);
            configuration.getSubsystems().add(parent);
            subsystem("Orders", parent);
        }
        String error = modifyRefusal("Subsystem.Orders");
        assertTrue(error, error.contains("more"));
        assertFalse("the list is cut, not every chain is spelled out: " + error,
            error.contains("Subsystem.P29.Subsystem.Orders"));
    }

    @Test
    public void testTheSuggestionDoesNotLoopOnACyclicModel()
    {
        // A broken model whose child lists its own ancestor: the walk must stop, not recurse forever.
        orders.getSubsystems().add(sales);
        String error = modifyRefusal("Subsystem.Missing");
        assertNotNull(error);
    }

    @Test
    public void testTheHintIsSilentForAnythingButAShortSubsystemAddress()
    {
        assertEquals("", nestedHint("Catalog.Orders"));
        assertEquals("", nestedHint("Subsystem.Sales.Subsystem.Orders"));
        assertEquals("", nestedHint(null));
        assertTrue(nestedHint("Subsystem.Orders").contains("Subsystem.Sales.Subsystem.Orders"));
        assertEquals("an external-objects root holds no subsystems", "",
            nestedHint(MetadataScopeTestFixtures.externalObjectsWithBase(configuration), "Subsystem.Orders"));
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private String modifyRefusal(String fqn)
    {
        Object resolved = invoke(ModifyMetadataTool.class, "resolveModifyTarget",
            new Class<?>[] {MetadataScope.class, String.class, String.class},
            scope(), fqn, MetadataTypeUtils.normalizeFqn(fqn));
        return (String)field(resolved, "error");
    }

    private String nestedHint(String fqn)
    {
        return nestedHint(scope(), fqn);
    }

    private static String nestedHint(MetadataScope scope, String fqn)
    {
        return (String)invoke(SubsystemUtils.class, "nestedNamesakesHint",
            new Class<?>[] {MetadataScope.class, String.class}, scope, fqn);
    }

    private static String row(String body, String property)
    {
        for (String line : body.split("\n"))
        {
            if (line.startsWith("| " + property + " |"))
            {
                return line;
            }
        }
        throw new AssertionError("no row for " + property + " in:\n" + body);
    }

    private Rendered render(String fqn, boolean assignable)
    {
        var context = new GetMetadataDetailsTool.RenderContext(null, scope(), null, "en", false, assignable, false, 0);
        StringBuilder body = new StringBuilder();
        List<String[]> failures = new ArrayList<>();
        VendorSupportGuard.setServiceForTests(() -> null);
        try
        {
            new GetMetadataDetailsTool().processFqn(fqn, body, failures, context);
        }
        finally
        {
            VendorSupportGuard.setServiceForTests(null);
        }
        return new Rendered(body.toString(), failures);
    }

    private static Object invoke(Class<?> owner, String name, Class<?>[] types, Object... arguments)
    {
        try
        {
            Method method = owner.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("The seam must exist and be callable: " + owner.getSimpleName() + "." + name, e);
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
            throw new AssertionError("The result must expose " + name, e);
        }
    }

    private static final class Rendered
    {
        final String body;
        final List<String[]> failures;

        Rendered(String body, List<String[]> failures)
        {
            this.body = body;
            this.failures = failures;
        }

        String reason()
        {
            assertEquals("exactly one failure row expected: " + failures(), 1, failures.size());
            return failures.get(0)[1];
        }

        String failures()
        {
            StringBuilder sb = new StringBuilder();
            for (String[] failure : failures)
            {
                sb.append(failure[0]).append(" -> ").append(failure[1]).append('\n');
            }
            return sb.toString();
        }
    }
}
