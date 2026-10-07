/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ForRoleType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The shared grammar of an {@link AdjustableBoolean} - a {@code common} flag plus per-role values
 * ({@code for: [{role, value}]}). One home for what the command interface visibility (#666) and
 * {@code modify_metadata}'s form flags ({@code view} / {@code edit} / {@code userVisible} / {@code use},
 * #719) both read and write: role addressing, the tri-state role value, the read-out and the build.
 */
public final class AdjustableBooleanSupport
{
    /** The prefix of a canonical role key, {@code Role.<Name>}. */
    public static final String ROLE_PREFIX = "Role."; //$NON-NLS-1$

    /** The token that drops a role's value so it follows {@code common} again. */
    public static final String DEFAULT_TOKEN = "default"; //$NON-NLS-1$

    private AdjustableBooleanSupport()
    {
        // utility class
    }

    /**
     * The per-role values of {@code value}, keyed {@code Role.<Name>} in stored order. A value whose
     * role does not resolve is left out: it cannot be named, and the writers carry it over untouched.
     *
     * @param value the flag, may be {@code null}
     * @return the role values (never {@code null})
     */
    public static Map<String, Boolean> roleValues(AdjustableBoolean value)
    {
        Map<String, Boolean> roles = new LinkedHashMap<>();
        if (value == null)
        {
            return roles;
        }
        for (ForRoleType forRole : value.getFor())
        {
            if (isResolved(forRole.getRole()))
            {
                roles.put(ROLE_PREFIX + forRole.getRole().getName(), forRole.isValue());
            }
        }
        return roles;
    }

    /**
     * Resolves a role reference against the configuration's roles by programmatic Name
     * (case-insensitive). The type token may be English or Russian ({@code Role.X} / {@code Роль.X}).
     *
     * @param config the configuration, may be {@code null}
     * @param ref the reference
     * @param allowBareName whether a bare {@code X} (no type token) is accepted too
     * @return the role, or {@code null} when the reference names none
     */
    public static Role resolveRole(Configuration config, String ref, boolean allowBareName)
    {
        if (config == null || ref == null || ref.trim().isEmpty())
        {
            return null;
        }
        String trimmed = ref.trim();
        String name;
        if (trimmed.indexOf('.') >= 0)
        {
            String normalized = MetadataTypeUtils.normalizeFqn(trimmed);
            int dot = normalized.indexOf('.');
            if (dot <= 0 || !"Role".equals(normalized.substring(0, dot))) //$NON-NLS-1$
            {
                return null;
            }
            name = normalized.substring(dot + 1).trim();
        }
        else if (allowBareName)
        {
            name = trimmed;
        }
        else
        {
            return null;
        }
        return roleByName(config, name);
    }

    /**
     * The configuration role with programmatic Name {@code name} (case-insensitive), or {@code null}.
     *
     * @param config the configuration
     * @param name the role Name
     * @return the role, or {@code null}
     */
    public static Role roleByName(Configuration config, String name)
    {
        if (config == null || name == null)
        {
            return null;
        }
        for (Role role : config.getRoles())
        {
            if (role != null && name.equalsIgnoreCase(role.getName()))
            {
                return role;
            }
        }
        return null;
    }

    /**
     * A strict boolean: JSON {@code true}/{@code false} or the strings {@code "true"}/{@code "false"}.
     *
     * @param el the element, may be {@code null}
     * @return the value, or {@code null} when {@code el} is not a boolean
     */
    public static Boolean booleanValue(JsonElement el)
    {
        if (el == null || !el.isJsonPrimitive())
        {
            return null;
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isBoolean())
        {
            return p.getAsBoolean();
        }
        if (p.isString())
        {
            String s = p.getAsString().trim();
            if ("true".equalsIgnoreCase(s)) //$NON-NLS-1$
            {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(s)) //$NON-NLS-1$
            {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /**
     * Whether {@code el} asks to drop a role's value: the {@code "default"} token, or a JSON
     * {@code null} where one survives (the MCP transport drops {@code null} object members, so
     * {@code "default"} is the spelling that always arrives).
     *
     * @param el the element, never {@code null}
     * @return whether it is the default token
     */
    public static boolean isDefaultToken(JsonElement el)
    {
        return el.isJsonNull() || el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
            && DEFAULT_TOKEN.equalsIgnoreCase(el.getAsString().trim());
    }

    /**
     * Builds a fresh value: {@code common}, the named role values (keys {@code Role.<Name>}, all
     * resolvable in {@code config}), then every stored value whose role does not resolve - the plan
     * cannot name those, and the editor carries them over.
     *
     * @param config the configuration the role keys resolve in
     * @param common the common flag
     * @param roles the role values keyed {@code Role.<Name>}
     * @param stored the value being replaced, may be {@code null}
     * @return the new value
     * @throws IllegalStateException when a named role no longer resolves
     */
    public static AdjustableBoolean build(Configuration config, boolean common, Map<String, Boolean> roles,
        AdjustableBoolean stored)
    {
        AdjustableBoolean value = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        value.setCommon(common);
        for (Map.Entry<String, Boolean> e : roles.entrySet())
        {
            Role role = roleByName(config, e.getKey().substring(ROLE_PREFIX.length()));
            if (role == null)
            {
                throw new IllegalStateException(e.getKey() + " disappeared before the write"); //$NON-NLS-1$
            }
            value.getFor().add(forRole(role, Boolean.TRUE.equals(e.getValue())));
        }
        if (stored != null)
        {
            for (ForRoleType original : stored.getFor())
            {
                if (!isResolved(original.getRole()))
                {
                    value.getFor().add(forRole(original.getRole(), original.isValue()));
                }
            }
        }
        return value;
    }

    /**
     * Edits the role values of an existing flag in place, in request order: a {@code TRUE}/{@code FALSE}
     * sets the role's value (appending it when the role had none), a {@code null} drops it. Values of
     * roles the edit does not name - including unresolved ones - are left as they are.
     *
     * @param target the flag to edit
     * @param edits role -&gt; new value, {@code null} meaning "drop"
     */
    public static void applyRoleEdits(AdjustableBoolean target, Map<Role, Boolean> edits)
    {
        for (Map.Entry<Role, Boolean> edit : edits.entrySet())
        {
            Role role = edit.getKey();
            boolean found = false;
            for (Iterator<ForRoleType> it = target.getFor().iterator(); it.hasNext();)
            {
                ForRoleType existing = it.next();
                if (!sameRole(existing.getRole(), role))
                {
                    continue;
                }
                if (edit.getValue() == null || found)
                {
                    // A drop, or a second stored entry for the same role: keep at most one.
                    it.remove();
                    continue;
                }
                existing.setValue(edit.getValue().booleanValue());
                found = true;
            }
            if (!found && edit.getValue() != null)
            {
                target.getFor().add(forRole(role, edit.getValue().booleanValue()));
            }
        }
    }

    /**
     * Renders a flag the way {@code modify_metadata} takes it: the bare {@code common} when no role
     * has a value of its own, otherwise {@code {"common":..,"roles":{"Role.X":..}}}.
     *
     * @param value the flag, may be {@code null}
     * @return the rendering, or {@code null} for no flag
     */
    public static String render(AdjustableBoolean value)
    {
        return value == null ? null : toText(value.isCommon(), roleValues(value));
    }

    /**
     * The order-independent identity of a flag: {@link #render} with the roles sorted, plus the values
     * of roles that do not resolve, keyed by their proxy URI - {@link #render} cannot name those, but
     * a comparison must still see them.
     *
     * @param value the flag, may be {@code null}
     * @return the identity, or {@code null} for no flag
     * @throws IllegalStateException when a role value names a role that is neither resolved nor a proxy
     */
    public static String identity(AdjustableBoolean value)
    {
        if (value == null)
        {
            return null;
        }
        String resolved = toText(value.isCommon(), new TreeMap<>(roleValues(value)));
        List<String> unresolved = new ArrayList<>();
        for (ForRoleType forRole : value.getFor())
        {
            Role role = forRole.getRole();
            if (isResolved(role))
            {
                continue;
            }
            URI uri = role instanceof InternalEObject ? ((InternalEObject)role).eProxyURI() : null;
            if (uri == null)
            {
                throw new IllegalStateException("A per-role value names a role that is neither resolved " //$NON-NLS-1$
                    + "nor a reference, so the flag cannot be compared."); //$NON-NLS-1$
            }
            JsonArray entry = new JsonArray();
            entry.add("proxy:" + uri); //$NON-NLS-1$
            entry.add(forRole.isValue());
            unresolved.add(entry.toString());
        }
        if (unresolved.isEmpty())
        {
            return resolved;
        }
        Collections.sort(unresolved);
        return resolved + " unresolved:[" + String.join(",", unresolved) + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String toText(boolean common, Map<String, Boolean> roles)
    {
        if (roles.isEmpty())
        {
            return String.valueOf(common);
        }
        JsonObject rolesJson = new JsonObject();
        for (Map.Entry<String, Boolean> e : roles.entrySet())
        {
            rolesJson.addProperty(e.getKey(), e.getValue());
        }
        JsonObject json = new JsonObject();
        json.addProperty("common", common); //$NON-NLS-1$
        json.add("roles", rolesJson); //$NON-NLS-1$
        return json.toString();
    }

    private static ForRoleType forRole(Role role, boolean value)
    {
        ForRoleType forRole = MdClassFactory.eINSTANCE.createForRoleType();
        forRole.setRole(role);
        forRole.setValue(value);
        return forRole;
    }

    private static boolean isResolved(Role role)
    {
        return role != null && !role.eIsProxy() && role.getName() != null;
    }

    private static boolean sameRole(Role stored, Role wanted)
    {
        return stored == wanted || isResolved(stored) && isResolved(wanted)
            && stored.getName().equalsIgnoreCase(wanted.getName());
    }
}
