/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ForRoleType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

/** The shared AdjustableBoolean grammar used by the command interface and modify_metadata (#666, #719). */
public class AdjustableBooleanSupportTest
{
    /** Менеджер. */
    private static final String RU_MANAGER = "\u041c\u0435\u043d\u0435\u0434\u0436\u0435\u0440"; //$NON-NLS-1$
    /** Роль. */
    private static final String RU_ROLE = "\u0420\u043e\u043b\u044c"; //$NON-NLS-1$

    @Test
    public void testARoleResolvesByEnglishOrRussianTypeTokenAndByBareNameWhenAllowed()
    {
        Configuration config = config("Manager", RU_MANAGER); //$NON-NLS-1$
        Role manager = config.getRoles().get(0);
        Role russian = config.getRoles().get(1);

        assertSame(manager, AdjustableBooleanSupport.resolveRole(config, "Role.Manager", false)); //$NON-NLS-1$
        assertSame("the Name compares case-insensitively", manager, //$NON-NLS-1$
            AdjustableBooleanSupport.resolveRole(config, "role.MANAGER", false)); //$NON-NLS-1$
        assertSame("the Russian type token resolves too", manager, //$NON-NLS-1$
            AdjustableBooleanSupport.resolveRole(config, RU_ROLE + ".Manager", false)); //$NON-NLS-1$
        assertSame("a Russian programmatic Name resolves", russian, //$NON-NLS-1$
            AdjustableBooleanSupport.resolveRole(config, RU_ROLE + "." + RU_MANAGER, false)); //$NON-NLS-1$
        assertSame(russian, AdjustableBooleanSupport.resolveRole(config, RU_MANAGER, true));
        assertSame(manager, AdjustableBooleanSupport.resolveRole(config, "Manager", true)); //$NON-NLS-1$
        assertNull("a bare Name needs allowBareName", //$NON-NLS-1$
            AdjustableBooleanSupport.resolveRole(config, "Manager", false)); //$NON-NLS-1$
        assertNull("another type is not a role", //$NON-NLS-1$
            AdjustableBooleanSupport.resolveRole(config, "Catalog.Manager", true)); //$NON-NLS-1$
        assertNull(AdjustableBooleanSupport.resolveRole(config, "Role.Missing", true)); //$NON-NLS-1$
        assertNull(AdjustableBooleanSupport.resolveRole(null, "Role.Manager", true)); //$NON-NLS-1$
    }

    @Test
    public void testTheRoleValueGrammar()
    {
        assertEquals(Boolean.TRUE, AdjustableBooleanSupport.booleanValue(new JsonPrimitive(true)));
        assertEquals(Boolean.FALSE, AdjustableBooleanSupport.booleanValue(new JsonPrimitive("false"))); //$NON-NLS-1$
        assertNull(AdjustableBooleanSupport.booleanValue(new JsonPrimitive("maybe"))); //$NON-NLS-1$
        assertNull(AdjustableBooleanSupport.booleanValue(new JsonPrimitive(1)));
        assertNull(AdjustableBooleanSupport.booleanValue(JsonNull.INSTANCE));
        assertTrue(AdjustableBooleanSupport.isDefaultToken(new JsonPrimitive(" Default "))); //$NON-NLS-1$
        assertTrue("a surviving JSON null drops the value too", //$NON-NLS-1$
            AdjustableBooleanSupport.isDefaultToken(JsonNull.INSTANCE));
        assertFalse(AdjustableBooleanSupport.isDefaultToken(new JsonPrimitive(false)));
    }

    @Test
    public void testRoleEditsSetAddAndDropOnlyTheNamedRoles()
    {
        Configuration config = config("Manager", "Clerk", "Auditor"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Role manager = config.getRoles().get(0);
        Role clerk = config.getRoles().get(1);
        Role auditor = config.getRoles().get(2);
        Role unresolved = proxyRole("Gone"); //$NON-NLS-1$
        AdjustableBoolean flag = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        flag.setCommon(true);
        flag.getFor().add(forRole(manager, true));
        flag.getFor().add(forRole(unresolved, true));
        flag.getFor().add(forRole(clerk, true));

        Map<Role, Boolean> edits = new LinkedHashMap<>();
        edits.put(manager, Boolean.FALSE);
        edits.put(clerk, null);
        edits.put(auditor, Boolean.TRUE);
        AdjustableBooleanSupport.applyRoleEdits(flag, edits);

        assertTrue("common is not the role edit's business", flag.isCommon()); //$NON-NLS-1$
        assertEquals(3, flag.getFor().size());
        assertSame(manager, flag.getFor().get(0).getRole());
        assertFalse("an existing role value is rewritten in place", flag.getFor().get(0).isValue()); //$NON-NLS-1$
        assertSame("an unresolved role value is kept untouched", unresolved, flag.getFor().get(1).getRole()); //$NON-NLS-1$
        assertSame("a role without a value is appended", auditor, flag.getFor().get(2).getRole()); //$NON-NLS-1$
        assertTrue(flag.getFor().get(2).isValue());
    }

    @Test
    public void testAStoredDuplicateOfAnEditedRoleCollapsesToOne()
    {
        Configuration config = config("Manager"); //$NON-NLS-1$
        Role manager = config.getRoles().get(0);
        AdjustableBoolean flag = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        flag.getFor().add(forRole(manager, true));
        flag.getFor().add(forRole(manager, true));

        AdjustableBooleanSupport.applyRoleEdits(flag, Map.of(manager, Boolean.FALSE));

        assertEquals(1, flag.getFor().size());
        assertFalse(flag.getFor().get(0).isValue());
    }

    @Test
    public void testTheRenderingIsTheBareCommonUntilARoleHasAValue()
    {
        Configuration config = config("Manager", RU_MANAGER); //$NON-NLS-1$
        AdjustableBoolean flag = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        flag.setCommon(true);
        assertEquals("true", AdjustableBooleanSupport.render(flag)); //$NON-NLS-1$
        flag.getFor().add(forRole(proxyRole("Gone"), false)); //$NON-NLS-1$
        assertEquals("an unresolved role cannot be named, so it is not shown", "true", //$NON-NLS-1$ //$NON-NLS-2$
            AdjustableBooleanSupport.render(flag));

        flag.getFor().add(forRole(config.getRoles().get(0), false));
        flag.getFor().add(forRole(config.getRoles().get(1), true));
        assertEquals("{\"common\":true,\"roles\":{\"Role.Manager\":false,\"Role." + RU_MANAGER + "\":true}}", //$NON-NLS-1$ //$NON-NLS-2$
            AdjustableBooleanSupport.render(flag));
        assertNull(AdjustableBooleanSupport.render(null));
    }

    @Test
    public void testBuildKeepsTheUnresolvedStoredValues()
    {
        Configuration config = config("Manager"); //$NON-NLS-1$
        Role unresolved = proxyRole("Gone"); //$NON-NLS-1$
        AdjustableBoolean stored = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        stored.getFor().add(forRole(unresolved, true));

        AdjustableBoolean built = AdjustableBooleanSupport.build(config, false,
            Map.of("Role.Manager", Boolean.TRUE), stored); //$NON-NLS-1$

        assertFalse(built.isCommon());
        assertEquals(2, built.getFor().size());
        assertSame(config.getRoles().get(0), built.getFor().get(0).getRole());
        assertSame(unresolved, built.getFor().get(1).getRole());
        assertEquals(Map.of("Role.Manager", Boolean.TRUE), AdjustableBooleanSupport.roleValues(built)); //$NON-NLS-1$
    }

    private static Configuration config(String... roleNames)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        for (String name : roleNames)
        {
            Role role = MdClassFactory.eINSTANCE.createRole();
            role.setName(name);
            config.getRoles().add(role);
        }
        return config;
    }

    private static Role proxyRole(String name)
    {
        Role role = MdClassFactory.eINSTANCE.createRole();
        ((InternalEObject)role).eSetProxyURI(URI.createURI("bm://TestConfiguration/Role." + name)); //$NON-NLS-1$
        return role;
    }

    private static ForRoleType forRole(Role role, boolean value)
    {
        ForRoleType forRole = MdClassFactory.eINSTANCE.createForRoleType();
        forRole.setRole(role);
        forRole.setValue(value);
        return forRole;
    }
}
