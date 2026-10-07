/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * modify_metadata's per-role values of an AdjustableBoolean flag (#719) and a form attribute's
 * {@code useAlways} checkboxes (#661), driven through the same headless preparation the write runs
 * ({@code formRetypeVerdict}): everything here is decided before anything is written.
 */
public class ModifyMetadataToolAdjustableAndUseAlwaysTest
{
    /** Менеджер - a Russian programmatic role Name. */
    private static final String RU_MANAGER = "\u041c\u0435\u043d\u0435\u0434\u0436\u0435\u0440"; //$NON-NLS-1$
    /** Роль. */
    private static final String RU_ROLE = "\u0420\u043e\u043b\u044c"; //$NON-NLS-1$

    // ---- per-role values (#719) ------------------------------------------------------------------

    @Test
    public void testRoleValuesResolveEveryRoleInBothLanguagesBeforeWriting()
    {
        Configuration config = configWithRoles("Manager", RU_MANAGER, "Clerk"); //$NON-NLS-1$ //$NON-NLS-2$
        List<ModifyMetadataTool.HolderChange> prepared = new ArrayList<>();
        String verdict = verdict(config, formAttribute("Price"), "view", //$NON-NLS-1$ //$NON-NLS-2$
            "{\"common\": false, \"roles\": {\"Role.Manager\": true, \"" + RU_ROLE + "." + RU_MANAGER //$NON-NLS-1$ //$NON-NLS-2$
                + "\": \"false\", \"clerk\": \"default\"}}", prepared); //$NON-NLS-1$

        assertNull(verdict, verdict);
        ModifyMetadataTool.AdjustableBooleanEdit edit =
            (ModifyMetadataTool.AdjustableBooleanEdit)prepared.get(0).change().value();
        assertEquals(Boolean.FALSE, edit.common);
        List<Role> roles = new ArrayList<>(edit.roles.keySet());
        assertSame(config.getRoles().get(0), roles.get(0));
        assertSame("a Russian type token and Name resolve to the role", config.getRoles().get(1), roles.get(1)); //$NON-NLS-1$
        assertSame("a bare Name resolves case-insensitively", config.getRoles().get(2), roles.get(2)); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, edit.roles.get(roles.get(0)));
        assertEquals(Boolean.FALSE, edit.roles.get(roles.get(1)));
        assertNull("'default' drops the role's value", edit.roles.get(roles.get(2))); //$NON-NLS-1$
        assertTrue(edit.roles.containsKey(roles.get(2)));
    }

    @Test
    public void testOnlyRolesKeepsTheStoredCommon()
    {
        List<ModifyMetadataTool.HolderChange> prepared = new ArrayList<>();
        assertNull(verdict(configWithRoles("Manager"), formAttribute("Price"), "edit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"roles\": {\"Manager\": false}}", prepared)); //$NON-NLS-1$
        assertNull("no 'common' means keep the stored one", //$NON-NLS-1$
            ((ModifyMetadataTool.AdjustableBooleanEdit)prepared.get(0).change().value()).common);
    }

    @Test
    public void testThePlainBooleanStillAddressesCommonOnly()
    {
        List<ModifyMetadataTool.HolderChange> prepared = new ArrayList<>();
        JsonObject prop = new JsonObject();
        prop.addProperty("name", "view"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.addProperty("value", false); //$NON-NLS-1$
        assertNull(new ModifyMetadataTool().formRetypeVerdict(MetadataScope.ofConfiguration(configWithRoles()),
            null, formAttribute("Price"), List.of(prop), new MdNameNormalizer.Report(true), prepared)); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, prepared.get(0).change().value());
    }

    @Test
    public void testAnUnknownRoleIsRefusedNamingTheValue()
    {
        String verdict = verdict(configWithRoles("Manager"), formAttribute("Price"), "view", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"roles\": {\"Role.Ghost\": true}}", new ArrayList<>()); //$NON-NLS-1$
        assertNotNull(verdict);
        assertTrue(verdict, verdict.contains("'view'.roles: 'Role.Ghost' is not a role of the configuration")); //$NON-NLS-1$
        assertTrue(verdict, verdict.contains("metadataType 'Role'")); //$NON-NLS-1$
    }

    @Test
    public void testMalformedRoleValuePayloadsAreRefused()
    {
        Configuration config = configWithRoles("Manager"); //$NON-NLS-1$
        assertRefused(config, "{\"roles\": {\"Manager\": true, \"Role.manager\": false}}", //$NON-NLS-1$
            "names the role 'Manager' twice"); //$NON-NLS-1$
        assertRefused(config, "{\"roles\": {\"Manager\": 1}}", "must be true, false or 'default'"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(config, "{\"roles\": [\"Manager\"]}", ".roles must be an object keyed by role"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(config, "{\"common\": \"yes please\"}", ".common must be true or false"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(config, "{\"visible\": true}", "has no member 'visible'"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(config, "{}", "changes nothing"); //$NON-NLS-1$ //$NON-NLS-2$
        // A JSON null role value is dropped by the transport, so what arrives is an empty map.
        assertRefused(config, "{\"roles\": {}}", "use 'default' to drop a role's value"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- useAlways (#661) ------------------------------------------------------------------------

    @Test
    public void testUseAlwaysPreparesOnAConstantsSetAttribute()
    {
        Configuration config = configWithConstants("UseReport"); //$NON-NLS-1$
        FormAttribute set = attributeInForm("ConstantsSet", "ConstantsSet"); //$NON-NLS-1$ //$NON-NLS-2$
        List<ModifyMetadataTool.HolderChange> prepared = new ArrayList<>();

        String verdict = verdict(config, set, "useAlways", "{\"ConstantsSet.UseReport\": true}", prepared); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(verdict, verdict);
        assertEquals("useAlways", prepared.get(0).change().featureName()); //$NON-NLS-1$
        assertEquals(1, ((List<?>)prepared.get(0).change().value()).size());
    }

    @Test
    public void testUseAlwaysRefusesAnUnknownConstantAndAForeignPath()
    {
        Configuration config = configWithConstants("UseReport"); //$NON-NLS-1$
        FormAttribute set = attributeInForm("ConstantsSet", "ConstantsSet"); //$NON-NLS-1$ //$NON-NLS-2$

        String verdict = verdict(config, set, "useAlways", "{\"ConstantsSet.Ghost\": true}", new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(verdict);
        assertTrue(verdict, verdict.contains("'Ghost' is not a constant of the configuration")); //$NON-NLS-1$
        verdict = verdict(config, set, "useAlways", "{\"Object.Code\": true}", new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(verdict, verdict.contains("not with the addressed attribute 'ConstantsSet'")); //$NON-NLS-1$
    }

    @Test
    public void testUseAlwaysCannotRideAlongARetype()
    {
        JsonObject useAlways = new JsonObject();
        useAlways.addProperty("name", "useAlways"); //$NON-NLS-1$ //$NON-NLS-2$
        useAlways.add("value", JsonParser.parseString("{\"Object.Code\": false}")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject retype = new JsonObject();
        retype.addProperty("name", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        retype.add("value", JsonParser.parseString("{\"types\": [{\"kind\": \"String\"}]}")); //$NON-NLS-1$ //$NON-NLS-2$

        String err = ModifyMetadataTool.useAlwaysWithRetypeError(Arrays.asList(useAlways, retype));
        assertNotNull(err);
        assertTrue(err, err.contains("Change the type first")); //$NON-NLS-1$
        assertNull(ModifyMetadataTool.useAlwaysWithRetypeError(List.of(useAlways)));
        assertNull(ModifyMetadataTool.useAlwaysWithRetypeError(List.of(retype)));
    }

    @Test
    public void testUseAlwaysIsNotAPropertyOfAnItem()
    {
        com._1c.g5.v8.dt.form.model.FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName("Code"); //$NON-NLS-1$
        String verdict = verdict(null, field, "useAlways", "{\"Object.Code\": false}", new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(verdict);
        assertTrue(verdict, verdict.contains("Property 'useAlways' is not assignable")); //$NON-NLS-1$
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String verdict(Configuration config, org.eclipse.emf.ecore.EObject member, String name,
        String json, List<ModifyMetadataTool.HolderChange> prepared)
    {
        JsonObject prop = new JsonObject();
        prop.addProperty("name", name); //$NON-NLS-1$
        prop.add("value", JsonParser.parseString(json)); //$NON-NLS-1$
        MetadataScope scope = config == null ? null : MetadataScope.ofConfiguration(config);
        return new ModifyMetadataTool().formRetypeVerdict(scope, null, member, List.of(prop),
            new MdNameNormalizer.Report(true), prepared);
    }

    private static void assertRefused(Configuration config, String json, String expected)
    {
        String verdict = verdict(config, formAttribute("Price"), "view", json, new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("must be refused: " + json, verdict); //$NON-NLS-1$
        assertTrue(verdict, verdict.contains(expected));
    }

    private static FormAttribute formAttribute(String name)
    {
        FormAttribute attribute = FormFactory.eINSTANCE.createFormAttribute();
        attribute.setName(name);
        return attribute;
    }

    private static FormAttribute attributeInForm(String name, String typeName)
    {
        FormAttribute attribute = formAttribute(name);
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(typeName);
        description.getTypes().add(type);
        attribute.setValueType(description);
        Form form = FormFactory.eINSTANCE.createForm();
        form.getAttributes().add(attribute);
        return attribute;
    }

    private static Configuration configWithRoles(String... names)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        for (String name : names)
        {
            Role role = MdClassFactory.eINSTANCE.createRole();
            role.setName(name);
            config.getRoles().add(role);
        }
        return config;
    }

    private static Configuration configWithConstants(String... names)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        for (String name : names)
        {
            Constant constant = MdClassFactory.eINSTANCE.createConstant();
            constant.setName(name);
            config.getConstants().add(constant);
        }
        return config;
    }
}
