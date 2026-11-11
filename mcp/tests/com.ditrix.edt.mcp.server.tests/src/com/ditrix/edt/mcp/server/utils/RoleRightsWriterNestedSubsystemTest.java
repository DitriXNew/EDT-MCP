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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.BmBasicTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.event.IEventBroker;
import com._1c.g5.v8.dt.core.model.IModelObjectCollectionRuntimeOrderSorter;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.rights.IRightInfosService;
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com.google.gson.JsonObject;

/**
 * Issue #897: a role's {@code rights[].object} addresses a NESTED subsystem
 * ({@code Subsystem.<Parent>.Subsystem.<Child>}) - EDT stores exactly that chain as the right's
 * object name ({@code <object><name>Subsystem.P.Subsystem.C</name>...}), and the writer must hand
 * EDT's rights task the nested subsystem itself, never its parent.
 *
 * <p>New seams are reached by reflection so the suite compiles against the writer as it was before the
 * fix, and every new behaviour has a failing baseline.</p>
 */
public class RoleRightsWriterNestedSubsystemTest
{
    /** "Подсистема" (Subsystem), from code points so the source stays ASCII. */
    private static final String RU_SUBSYSTEM = "Подсистема";
    private static final long ROLE_BM_ID = 42L;

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

    private static Object invoke(String name, Class<?>[] types, Object... arguments)
    {
        try
        {
            Method method = RoleRightsWriter.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("The rights-object seam must exist and be callable: " + name, e);
        }
    }

    // ---- resolution --------------------------------------------------------------------------------

    @Test
    public void testNestedChainResolvesTheLeafNotTheParent()
    {
        assertSame(b, RoleRightsWriter.resolveObject(configuration, "Subsystem.A.Subsystem.B"));
    }

    @Test
    public void testRussianTokensResolveTheSameLeaf()
    {
        assertSame(b, RoleRightsWriter.resolveObject(configuration, RU_SUBSYSTEM + ".A." + RU_SUBSYSTEM + ".B"));
        assertSame(b, RoleRightsWriter.resolveObject(configuration, "Subsystem.A." + RU_SUBSYSTEM + ".B"));
    }

    @Test
    public void testDeepChainResolvesTheThirdLevel()
    {
        Subsystem c = subsystem("C", b);
        assertSame(c, RoleRightsWriter.resolveObject(configuration, "Subsystem.A.Subsystem.B.Subsystem.C"));
    }

    @Test
    public void testTopLevelSubsystemStillResolves()
    {
        assertSame(a, RoleRightsWriter.resolveObject(configuration, "Subsystem.A"));
    }

    @Test
    public void testMissingLevelResolvesNothing()
    {
        assertNull(RoleRightsWriter.resolveObject(configuration, "Subsystem.A.Subsystem.Missing"));
        assertNull("a missing middle level must not land on a deeper subsystem",
            RoleRightsWriter.resolveObject(configuration, "Subsystem.A.Subsystem.Missing.Subsystem.B"));
    }

    @Test
    public void testNestedChainIsExactLikeEveryOtherRightsObject()
    {
        // The rights object of a top object resolves exactly (no yo retry); a chain must not be the
        // one address that guesses.
        b.setName("Елка");
        assertNull(RoleRightsWriter.resolveObject(configuration, "Subsystem.A.Subsystem.Ёлка"));
        assertSame(b, RoleRightsWriter.resolveObject(configuration, "Subsystem.A.Subsystem.Елка"));
    }

    // ---- refusals ----------------------------------------------------------------------------------

    @Test
    public void testMissingChainRefusalTeachesTheChainNotTheParent()
    {
        String address = "Subsystem.A.Subsystem.Missing";
        String error = (String)invoke("objectNotFound", new Class<?>[] {Configuration.class, String.class},
            configuration, address);
        assertTrue(error, error.contains("Object not found for right entry: '" + address + "'"));
        assertTrue(error, error.contains(SubsystemUtils.addressingHint()));
        assertFalse("'Did you mean' would offer the top-level parent, a different subsystem: " + error,
            error.contains("Did you mean"));
    }

    @Test
    public void testMalformedChainIsRefusedNotTrimmed()
    {
        String trailing = (String)invoke("malformedChain", new Class<?>[] {String.class},
            "Subsystem.A.Subsystem.B.");
        assertNotNull("a stray '.' must not grant the right on B", trailing);
        assertTrue(trailing, trailing.contains("EMPTY segment"));
        String padded = (String)invoke("malformedChain", new Class<?>[] {String.class},
            "Subsystem.A.Subsystem. B ");
        assertNotNull(padded);
        assertTrue(padded, padded.contains("padded segment"));
        assertNull(invoke("malformedChain", new Class<?>[] {String.class}, "Subsystem.A.Subsystem.B"));
        assertNull("a top-level address keeps its own resolution",
            invoke("malformedChain", new Class<?>[] {String.class}, "Subsystem.A"));
        assertNull(invoke("malformedChain", new Class<?>[] {String.class}, "Catalog.Goods.Attribute.Weight"));
    }

    // ---- the write, through the REAL apply ---------------------------------------------------------

    @Test
    public void testTheRightsTaskIsHandedTheNestedSubsystem()
    {
        Right view = RightsFactory.eINSTANCE.createRight();
        view.setName("View");
        IRightInfosService infos = mock(IRightInfosService.class);
        when(infos.getRights(b)).thenReturn(Set.of(view));
        List<IBmTask<?>> tasks = new ArrayList<>();

        RoleRightsWriter.Result result = apply(infos, tasks, rightsEntry("Subsystem.A.Subsystem.B", "View"));

        assertNull("a right on a nested subsystem must apply: " + result.error, result.error);
        assertEquals(1, result.rights);
        verify(infos, never()).getRights(a);
        Map<?, ?> changed = changedObjects(tasks);
        assertEquals(1, changed.size());
        Map<?, ?> byObject = (Map<?, ?>)changed.values().iterator().next();
        assertEquals("EDT's AddRightValuesTask receives exactly the nested subsystem", Set.of(b),
            byObject.keySet());
        assertEquals(Map.of(view, RightValue.SET), byObject.get(b));
    }

    @Test
    public void testAMalformedChainRefusesBeforeAnyRightIsResolved()
    {
        IRightInfosService infos = mock(IRightInfosService.class);
        List<IBmTask<?>> tasks = new ArrayList<>();
        RoleRightsWriter.Result result = apply(infos, tasks, rightsEntry("Subsystem.A.Subsystem.B.", "View"));
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains("EMPTY segment"));
        assertEquals(0, result.rights);
        verify(infos, never()).getRights(any());
        assertTrue("nothing may reach EDT's rights task", changedObjects(tasks).isEmpty());
    }

    @Test
    public void testAMissingChainRefusesWithTheChainGrammar()
    {
        IRightInfosService infos = mock(IRightInfosService.class);
        List<IBmTask<?>> tasks = new ArrayList<>();
        RoleRightsWriter.Result result = apply(infos, tasks,
            rightsEntry("Subsystem.A.Subsystem.Missing", "View"));
        assertTrue(result.hasError());
        assertTrue(result.error, result.error.contains(SubsystemUtils.addressingHint()));
        assertFalse(result.error, result.error.contains("Did you mean"));
        verify(infos, never()).getRights(any());
    }

    private RoleRightsWriter.Result apply(IRightInfosService infos, List<IBmTask<?>> tasks, JsonObject entry)
    {
        Role role = mock(Role.class, withSettings().extraInterfaces(IBmObject.class));
        when(role.getName()).thenReturn("Reader");
        RoleDescription description = mock(RoleDescription.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)description).bmIsTop()).thenReturn(true);
        when(role.getRights()).thenReturn(description);
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.getObjectById(ROLE_BM_ID)).thenReturn((IBmObject)role);
        IBmModel model = mock(IBmModel.class);
        when(model.execute(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            if (task instanceof BmBasicTask)
            {
                tasks.add(task);
                return null;
            }
            return task.execute(tx, new NullProgressMonitor());
        });
        when(model.executeReadonlyTask(any()))
            .thenAnswer(inv -> ((IBmTask<?>)inv.getArgument(0)).execute(tx, new NullProgressMonitor()));
        ITopObjectFqnGenerator generator = mock(ITopObjectFqnGenerator.class);
        when(generator.generateExternalPropertyFqn(any(), any())).thenReturn("Role.Reader.Rights");
        RoleRightsWriter.Context ctx = new RoleRightsWriter.Context(mock(IProject.class), configuration, model,
            role, ROLE_BM_ID, infos, mock(IEventBroker.class), mock(IModelObjectCollectionRuntimeOrderSorter.class));
        return RoleRightsWriter.applyResolved(ctx, generator, List.of(entry), Collections.emptyList(), null);
    }

    /** The {@code changedObjects} map of every AddRightValuesTask the apply handed to the model. */
    private static Map<?, ?> changedObjects(List<IBmTask<?>> tasks)
    {
        Map<Object, Object> merged = new java.util.LinkedHashMap<>();
        for (IBmTask<?> task : tasks)
        {
            try
            {
                Field field = task.getClass().getDeclaredField("changedObjects");
                field.setAccessible(true);
                merged.putAll((Map<?, ?>)field.get(task));
            }
            catch (NoSuchFieldException e)
            {
                // not an AddRightValuesTask
            }
            catch (ReflectiveOperationException e)
            {
                throw new AssertionError(e);
            }
        }
        return merged;
    }

    private static JsonObject rightsEntry(String object, String right)
    {
        JsonObject entry = new JsonObject();
        entry.addProperty("object", object);
        entry.addProperty("right", right);
        entry.addProperty("value", "set");
        return entry;
    }
}
