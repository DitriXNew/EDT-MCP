/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * A model-free snapshot of one section's command interface - its panel groups in display order,
 * each with its commands in effective order and their visibility - plus the planner that turns a
 * {@code commands} payload into the platform changes that produce the requested END state.
 *
 * <p>The snapshot is taken from EDT's computed command interface (see
 * {@link CommandInterfaceSupport}); the {@code handle} fields carry the model objects the platform
 * tasks need and are opaque here, so this class stays testable without a model.</p>
 */
public final class CommandInterfaceSection
{
    /** The two panels of a section; form panels never appear in a section. */
    public enum Panel
    {
        NAVIGATION("navigation panel"), //$NON-NLS-1$
        ACTIONS("actions panel"); //$NON-NLS-1$

        private final String label;

        Panel(String label)
        {
            this.label = label;
        }

        /** @return the human-readable panel name */
        public String label()
        {
            return label;
        }
    }

    /** A panel group of the section. Identity-compared. */
    public static final class Group
    {
        final String id;
        final String russianId;
        final Panel panel;
        final boolean custom;
        final Object handle;
        final boolean orderCustomized;
        final List<Item> items = new ArrayList<>();
        /** EDT's computed group this was read from, or {@code null}. */
        Object derived;

        /**
         * @param id canonical id: a standard group name ({@code NavigationPanelImportant}) or
         *     {@code CommandGroup.<Name>}
         * @param russianId the Russian identifier of a standard group, or {@code null}
         * @param panel the panel the group belongs to
         * @param custom whether it is a configuration command group
         * @param handle the model command group handed to the platform tasks
         * @param orderCustomized whether the section stores an explicit order for the group
         */
        public Group(String id, String russianId, Panel panel, boolean custom, Object handle,
            boolean orderCustomized)
        {
            this.id = id;
            this.russianId = russianId;
            this.panel = panel;
            this.custom = custom;
            this.handle = handle;
            this.orderCustomized = orderCustomized;
        }

        /** @return the canonical id */
        public String id()
        {
            return id;
        }

        /** @return the model command group */
        public Object handle()
        {
            return handle;
        }

        /** @return the commands in effective order (read-only) */
        public List<Item> items()
        {
            return Collections.unmodifiableList(items);
        }

        String displayName()
        {
            return russianId == null || russianId.isEmpty() ? id : id + " (" + russianId + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** A command of the section. Identity-compared. */
    public static final class Item
    {
        final String fqn;
        final String russianFqn;
        final Object handle;
        final Visibility visibility;
        final boolean visibilityCustomized;
        final boolean groupCustomized;
        final boolean resolved;
        /** EDT's computed command item this was read from, or {@code null}. */
        Object derived;

        /**
         * @param fqn canonical English FQN, e.g. {@code Report.Sales.StandardCommand.Open}
         * @param russianFqn the same address with the standard command's Russian name, or {@code null}
         * @param handle the model command handed to the platform tasks
         * @param visibility the effective visibility
         * @param visibilityCustomized whether the section stores a visibility for it
         * @param groupCustomized whether the section stores a placement for it
         * @param resolved {@code false} for a stored command that no longer resolves
         */
        public Item(String fqn, String russianFqn, Object handle, Visibility visibility, // NOSONAR value holder
            boolean visibilityCustomized, boolean groupCustomized, boolean resolved)
        {
            this.fqn = fqn;
            this.russianFqn = russianFqn;
            this.handle = handle;
            this.visibility = visibility;
            this.visibilityCustomized = visibilityCustomized;
            this.groupCustomized = groupCustomized;
            this.resolved = resolved;
        }

        /** @return the canonical English FQN */
        public String fqn()
        {
            return fqn;
        }

        /** @return the model command */
        public Object handle()
        {
            return handle;
        }
    }

    /** A command visibility: the common value and per-role overrides keyed by {@code Role.<Name>}. */
    public static final class Visibility
    {
        final boolean common;
        final Map<String, Boolean> roles;

        /**
         * @param common the common visibility
         * @param roles per-role overrides in stored order, keyed by {@code Role.<Name>}
         */
        public Visibility(boolean common, Map<String, Boolean> roles)
        {
            this.common = common;
            this.roles = Collections.unmodifiableMap(new LinkedHashMap<>(roles));
        }

        /** @return the common visibility */
        public boolean common()
        {
            return common;
        }

        /** @return the per-role overrides keyed by {@code Role.<Name>} */
        public Map<String, Boolean> roles()
        {
            return roles;
        }

        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof Visibility))
            {
                return false;
            }
            Visibility other = (Visibility)o;
            return common == other.common && roles.equals(other.roles);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(common, roles);
        }
    }

    /** The changes a payload needs, computed from the simulated END state. */
    public static final class Plan
    {
        final Map<Item, Visibility> visibility = new LinkedHashMap<>();
        final Map<Item, Group> placement = new LinkedHashMap<>();
        final Map<Group, List<Item>> order = new LinkedHashMap<>();
        final List<String> unchanged = new ArrayList<>();
        final List<String> touched = new ArrayList<>();
        /** Commands an entry set visible/roles for. */
        final Set<Item> visibilityAsked = new LinkedHashSet<>();
        /** Commands an entry set group/after/before for, with the group they end in. */
        final Map<Item, Group> layoutAsked = new LinkedHashMap<>();
        /** The section this plan was computed from. */
        CommandInterfaceSection section;

        /** @return command -&gt; final visibility, for commands whose visibility changes */
        public Map<Item, Visibility> visibility()
        {
            return Collections.unmodifiableMap(visibility);
        }

        /** @return command -&gt; destination group, for commands that change group */
        public Map<Item, Group> placement()
        {
            return Collections.unmodifiableMap(placement);
        }

        /** @return group -&gt; its complete final order, for groups an entry ordered explicitly */
        public Map<Group, List<Item>> order()
        {
            return Collections.unmodifiableMap(order);
        }

        /** @return the commands of entries that changed nothing */
        public List<String> unchanged()
        {
            return Collections.unmodifiableList(unchanged);
        }

        /** @return every command an entry named, in the order first named */
        public List<String> touched()
        {
            return Collections.unmodifiableList(touched);
        }

        /** @return whether the plan changes nothing */
        public boolean isEmpty()
        {
            return visibility.isEmpty() && placement.isEmpty() && order.isEmpty();
        }
    }

    /** A plan or an actionable error; exactly one is non-null. */
    public static final class PlanResult
    {
        /** The plan, or {@code null} on error. */
        public final Plan plan;
        /** The actionable error, or {@code null} on success. */
        public final String error;

        private PlanResult(Plan plan, String error)
        {
            this.plan = plan;
            this.error = error;
        }
    }

    static final String KEY_COMMAND = "command"; //$NON-NLS-1$
    static final String KEY_VISIBLE = "visible"; //$NON-NLS-1$
    static final String KEY_ROLES = "roles"; //$NON-NLS-1$
    static final String KEY_ROLE = "role"; //$NON-NLS-1$
    static final String KEY_GROUP = "group"; //$NON-NLS-1$
    static final String KEY_AFTER = "after"; //$NON-NLS-1$
    static final String KEY_BEFORE = "before"; //$NON-NLS-1$

    private static final Set<String> ENTRY_KEYS = new LinkedHashSet<>(
        java.util.Arrays.asList(KEY_COMMAND, KEY_VISIBLE, KEY_ROLES, KEY_GROUP, KEY_AFTER, KEY_BEFORE));

    /** How many section commands an unknown-command error lists. */
    private static final int LISTED_COMMANDS = 40;

    private static final String STANDARD_COMMAND_TOKEN = "StandardCommand"; //$NON-NLS-1$

    private static final Set<String> STANDARD_COMMAND_TOKENS = new LinkedHashSet<>(java.util.Arrays.asList(
        "standardcommand", "standardcommands", //$NON-NLS-1$ //$NON-NLS-2$
        MetadataLanguageUtils.cp(0x0441, 0x0442, 0x0430, 0x043d, 0x0434, 0x0430, 0x0440, 0x0442, 0x043d, 0x0430,
            0x044f, 0x043a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x0430),
        MetadataLanguageUtils.cp(0x0441, 0x0442, 0x0430, 0x043d, 0x0434, 0x0430, 0x0440, 0x0442, 0x043d, 0x044b,
            0x0435, 0x043a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x044b)));

    private final String ownerFqn;
    private final String addressFqn;
    private final List<Group> groups;

    /**
     * @param ownerFqn the section owner, e.g. {@code Subsystem.Sales} or {@code Configuration}
     * @param addressFqn the command interface FQN, e.g. {@code Subsystem.Sales.CommandInterface}
     * @param groups the panel groups in display order, each already holding its items
     */
    public CommandInterfaceSection(String ownerFqn, String addressFqn, List<Group> groups)
    {
        this.ownerFqn = ownerFqn;
        this.addressFqn = addressFqn;
        this.groups = new ArrayList<>(groups);
    }

    /** @return the section owner FQN */
    public String ownerFqn()
    {
        return ownerFqn;
    }

    /** @return the groups in display order (read-only) */
    public List<Group> groups()
    {
        return Collections.unmodifiableList(groups);
    }

    // ===== lookup ===================================================================================

    /**
     * Finds a resolved command of the section by FQN. The type, the kind tokens and a standard
     * command's name may be English or Russian; object Names are programmatic Names.
     *
     * @param commandFqn the requested command FQN
     * @return the command, or {@code null} when the section does not show it
     */
    public Item findItem(String commandFqn)
    {
        String wanted = normalizeCommandFqn(commandFqn);
        if (wanted == null)
        {
            return null;
        }
        for (Group group : groups)
        {
            for (Item item : group.items)
            {
                if (item.resolved && (wanted.equalsIgnoreCase(item.fqn)
                    || (item.russianFqn != null && wanted.equalsIgnoreCase(item.russianFqn))))
                {
                    return item;
                }
            }
        }
        return null;
    }

    /**
     * Finds a panel group by a standard group identifier (English or Russian) or by a
     * {@code CommandGroup.<Name>} FQN (the type token may be Russian).
     *
     * @param groupRef the requested group
     * @return the group, or {@code null} when the section has no such group
     */
    public Group findGroup(String groupRef)
    {
        if (groupRef == null || groupRef.trim().isEmpty())
        {
            return null;
        }
        String ref = groupRef.trim();
        String normalized = MetadataTypeUtils.normalizeFqn(ref);
        for (Group group : groups)
        {
            if (group.id.equalsIgnoreCase(normalized)
                || (group.russianId != null && group.russianId.equalsIgnoreCase(ref)))
            {
                return group;
            }
        }
        return null;
    }

    /**
     * Canonicalizes a command FQN: the leading type token to its English singular, a
     * {@code StandardCommand} token and a nested kind token (e.g. {@code Command}) to English. Names
     * are kept verbatim.
     *
     * @param fqn the requested command FQN
     * @return the canonical form, or {@code null} for a blank / single-segment value
     */
    static String normalizeCommandFqn(String fqn)
    {
        if (fqn == null)
        {
            return null;
        }
        String[] parts = fqn.trim().split("\\.", -1); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++)
        {
            String part = parts[i].trim();
            if (i == 0)
            {
                String english = MetadataTypeUtils.toEnglishSingular(part);
                part = english != null ? english : part;
            }
            else if (i % 2 == 0)
            {
                part = canonicalKindToken(part);
            }
            if (i > 0)
            {
                sb.append('.');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static String canonicalKindToken(String token)
    {
        if (STANDARD_COMMAND_TOKENS.contains(token.toLowerCase(Locale.ROOT)))
        {
            return STANDARD_COMMAND_TOKEN;
        }
        MetadataTypeUtils.NestedKindInfo kind = MetadataTypeUtils.resolveNestedKind(token);
        return kind != null ? kind.getEnglish() : token;
    }

    // ===== planning =================================================================================

    /**
     * Validates a {@code commands} payload against this section and computes the changes that
     * produce its END state. Entries apply in order: each one is checked against the state the
     * previous ones left, and a later write of the same command wins.
     *
     * @param entries the payload entries
     * @param roleResolver maps a requested role FQN to its canonical {@code Role.<Name>}, or
     *     returns {@code null} when the configuration has no such role
     * @return the plan, or an actionable error naming the entry and the bad value
     */
    public PlanResult plan(List<JsonObject> entries, UnaryOperator<String> roleResolver)
    {
        if (entries == null || entries.isEmpty())
        {
            return error("'commands' is empty: pass at least one {command, visible?, roles?, group?, " //$NON-NLS-1$
                + "after?, before?} entry."); //$NON-NLS-1$
        }
        Map<Item, Group> groupOf = new IdentityHashMap<>();
        Map<Group, List<Item>> orderOf = new IdentityHashMap<>();
        Map<Item, Visibility> visibilityOf = new IdentityHashMap<>();
        for (Group group : groups)
        {
            orderOf.put(group, new ArrayList<>(group.items));
            for (Item item : group.items)
            {
                groupOf.put(item, group);
                visibilityOf.put(item, item.visibility);
            }
        }
        Set<Group> ordered = new LinkedHashSet<>();
        Set<Item> touched = new LinkedHashSet<>();
        Set<Item> visibilityAsked = new LinkedHashSet<>();
        Set<Item> layoutAsked = new LinkedHashSet<>();

        for (int i = 0; i < entries.size(); i++)
        {
            String err = applyEntry(i, entries.get(i), roleResolver, groupOf, orderOf, visibilityOf, ordered,
                touched, visibilityAsked, layoutAsked);
            if (err != null)
            {
                return error(err);
            }
        }

        Plan plan = new Plan();
        plan.section = this;
        for (Group group : groups)
        {
            for (Item item : group.items)
            {
                if (!touched.contains(item))
                {
                    continue;
                }
                if (!visibilityOf.get(item).equals(item.visibility))
                {
                    plan.visibility.put(item, visibilityOf.get(item));
                }
                if (groupOf.get(item) != group)
                {
                    plan.placement.put(item, groupOf.get(item));
                }
            }
        }
        for (Group group : ordered)
        {
            List<Item> finalOrder = orderOf.get(group);
            if (!finalOrder.equals(group.items) || plan.placement.containsValue(group))
            {
                plan.order.put(group, new ArrayList<>(finalOrder));
            }
        }
        plan.visibilityAsked.addAll(visibilityAsked);
        for (Item item : layoutAsked)
        {
            plan.layoutAsked.put(item, groupOf.get(item));
        }
        for (Item item : touched)
        {
            plan.touched.add(item.fqn);
            if (!plan.visibility.containsKey(item) && !plan.placement.containsKey(item)
                && !orderedGroupMoved(plan, item, groupOf))
            {
                plan.unchanged.add(item.fqn);
            }
        }
        return new PlanResult(plan, null);
    }

    private static boolean orderedGroupMoved(Plan plan, Item item, Map<Item, Group> groupOf)
    {
        List<Item> order = plan.order.get(groupOf.get(item));
        return order != null;
    }

    private String applyEntry(int index, JsonObject entry, UnaryOperator<String> roleResolver, // NOSONAR one simulation step
        Map<Item, Group> groupOf, Map<Group, List<Item>> orderOf, Map<Item, Visibility> visibilityOf,
        Set<Group> ordered, Set<Item> touched, Set<Item> visibilityAsked, Set<Item> layoutAsked)
    {
        String at = "commands[" + index + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        if (entry == null)
        {
            return at + " is not an object: pass {command, visible?, roles?, group?, after?, before?}."; //$NON-NLS-1$
        }
        for (String key : entry.keySet())
        {
            if (!ENTRY_KEYS.contains(key))
            {
                return at + " has an unknown key '" + key + "'. Accepted keys: " //$NON-NLS-1$ //$NON-NLS-2$
                    + String.join(", ", ENTRY_KEYS) + "."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        String commandRef = stringValue(entry, KEY_COMMAND);
        if (commandRef == null || commandRef.isEmpty())
        {
            return at + ".command is required: the command FQN, e.g. 'Report.Sales.StandardCommand.Open' " //$NON-NLS-1$
                + "or 'CommonCommand.Print'."; //$NON-NLS-1$
        }
        Item item = findItem(commandRef);
        if (item == null)
        {
            return unknownCommandError(at + ".command", commandRef); //$NON-NLS-1$
        }
        for (String key : new String[] { KEY_GROUP, KEY_AFTER, KEY_BEFORE })
        {
            JsonElement value = entry.get(key);
            if (value != null && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().trim().isEmpty()))
            {
                return at + "." + key + " must be a non-empty string (" //$NON-NLS-1$ //$NON-NLS-2$
                    + (KEY_GROUP.equals(key) ? "a group id, e.g. 'NavigationPanelOrdinary'" : "a command FQN") //$NON-NLS-1$ //$NON-NLS-2$
                    + "), got " + value + "; omit the key to leave it unchanged."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        boolean hasVisible = entry.has(KEY_VISIBLE);
        boolean hasRoles = entry.has(KEY_ROLES);
        String groupRef = stringValue(entry, KEY_GROUP);
        String after = stringValue(entry, KEY_AFTER);
        String before = stringValue(entry, KEY_BEFORE);
        if (!hasVisible && !hasRoles && groupRef == null && after == null && before == null)
        {
            return at + " ('" + commandRef + "') changes nothing: set 'visible', 'roles', 'group', " //$NON-NLS-1$ //$NON-NLS-2$
                + "'after' or 'before'."; //$NON-NLS-1$
        }
        if (after != null && before != null)
        {
            return at + " sets both 'after' and 'before': pass only one anchor command."; //$NON-NLS-1$
        }
        touched.add(item);
        if (hasVisible || hasRoles)
        {
            visibilityAsked.add(item);
        }
        if (groupRef != null || after != null || before != null)
        {
            layoutAsked.add(item);
        }

        if (hasVisible || hasRoles)
        {
            String err = applyVisibility(at, entry, item, hasVisible, hasRoles, roleResolver, visibilityOf);
            if (err != null)
            {
                return err;
            }
        }
        if (groupRef != null)
        {
            Group target = findGroup(groupRef);
            if (target == null)
            {
                return unknownGroupError(at + ".group", groupRef); //$NON-NLS-1$
            }
            Group current = groupOf.get(item);
            if (current != target)
            {
                orderOf.get(current).remove(item);
                orderOf.get(target).add(item);
                groupOf.put(item, target);
            }
        }
        String anchorRef = after != null ? after : before;
        if (anchorRef != null)
        {
            String key = after != null ? KEY_AFTER : KEY_BEFORE;
            Item anchor = findItem(anchorRef);
            if (anchor == null)
            {
                return unknownCommandError(at + "." + key, anchorRef); //$NON-NLS-1$
            }
            if (anchor == item)
            {
                return at + "." + key + " names the command itself ('" + anchorRef //$NON-NLS-1$ //$NON-NLS-2$
                    + "'): anchor it to another command of the group."; //$NON-NLS-1$
            }
            Group target = groupOf.get(item);
            Group anchorGroup = groupOf.get(anchor);
            if (anchorGroup != target)
            {
                return at + "." + key + " anchor '" + anchor.fqn + "' is in group " + anchorGroup.id //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ", but '" + item.fqn + "' is in group " + target.id + " at that point. Add " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "group:'" + anchorGroup.id + "' to the same entry to move it there, or anchor it to " //$NON-NLS-1$ //$NON-NLS-2$
                    + "a command of " + target.id + "."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            List<Item> order = orderOf.get(target);
            order.remove(item);
            int anchorIndex = order.indexOf(anchor);
            order.add(after != null ? anchorIndex + 1 : anchorIndex, item);
            ordered.add(target);
        }
        return null;
    }

    private static String applyVisibility(String at, JsonObject entry, Item item, boolean hasVisible,
        boolean hasRoles, UnaryOperator<String> roleResolver, Map<Item, Visibility> visibilityOf)
    {
        Visibility current = visibilityOf.get(item);
        boolean common = current.common;
        if (hasVisible)
        {
            Boolean value = booleanValue(entry.get(KEY_VISIBLE));
            if (value == null)
            {
                return at + ".visible must be true or false, got " + entry.get(KEY_VISIBLE) + "."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            common = value;
        }
        Map<String, Boolean> roles = new LinkedHashMap<>(current.roles);
        if (hasRoles)
        {
            JsonElement rolesEl = entry.get(KEY_ROLES);
            if (!rolesEl.isJsonArray())
            {
                return at + ".roles must be an array of {role, visible}, e.g. [{role:'Role.Manager', " //$NON-NLS-1$
                    + "visible:false}]."; //$NON-NLS-1$
            }
            JsonArray array = rolesEl.getAsJsonArray();
            for (int r = 0; r < array.size(); r++)
            {
                String roleAt = at + ".roles[" + r + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                JsonElement roleEl = array.get(r);
                if (!roleEl.isJsonObject())
                {
                    return roleAt + " must be {role, visible}."; //$NON-NLS-1$
                }
                JsonObject roleObj = roleEl.getAsJsonObject();
                String roleRef = stringValue(roleObj, KEY_ROLE);
                if (roleRef == null || roleRef.isEmpty())
                {
                    return roleAt + ".role is required: a role FQN, e.g. 'Role.Manager'."; //$NON-NLS-1$
                }
                String role = roleResolver.apply(roleRef);
                if (role == null)
                {
                    return roleAt + ".role '" + roleRef + "' is not a role of the configuration. Address it " //$NON-NLS-1$ //$NON-NLS-2$
                        + "as 'Role.<Name>' by its programmatic Name; get_metadata_objects with " //$NON-NLS-1$
                        + "metadataType 'Role' lists them."; //$NON-NLS-1$
                }
                // 'default' rather than null: the transport drops null members before they get here.
                JsonElement valueEl = roleObj.get(KEY_VISIBLE);
                if (valueEl == null)
                {
                    return roleAt + ".visible is required: true, false, or 'default' to drop the role's " //$NON-NLS-1$
                        + "override so it follows the common visibility."; //$NON-NLS-1$
                }
                if (valueEl.isJsonNull() || isDefaultToken(valueEl))
                {
                    roles.remove(role);
                    continue;
                }
                Boolean value = booleanValue(valueEl);
                if (value == null)
                {
                    return roleAt + ".visible must be true, false or 'default', got " + valueEl + "."; //$NON-NLS-1$ //$NON-NLS-2$
                }
                roles.put(role, value);
            }
        }
        visibilityOf.put(item, new Visibility(common, roles));
        return null;
    }

    private String unknownCommandError(String at, String commandRef)
    {
        List<String> listed = new ArrayList<>();
        int total = 0;
        for (Group group : groups)
        {
            for (Item item : group.items)
            {
                if (!item.resolved)
                {
                    continue;
                }
                total++;
                if (listed.size() < LISTED_COMMANDS)
                {
                    listed.add(item.fqn);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(at).append(" '").append(commandRef).append("' is not in the command interface of ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(ownerFqn).append(". A section shows the commands of its content objects and the " //$NON-NLS-1$
                + "common commands in its content; address a command as 'Report.<Name>.StandardCommand.Open', " //$NON-NLS-1$
                + "'<Type>.<Name>.Command.<Name>' or 'CommonCommand.<Name>'. If the object is not in the " //$NON-NLS-1$
                + "content yet, add it first with modify_metadata(fqn='").append(ownerFqn) //$NON-NLS-1$
            .append("', content=[{op:'add', metadata:'<Type>.<Name>'}]) - the section is recomputed a " //$NON-NLS-1$
                + "moment later. "); //$NON-NLS-1$
        if (total == 0)
        {
            sb.append("The section has no commands yet."); //$NON-NLS-1$
        }
        else
        {
            sb.append("Commands of the section: ").append(String.join(", ", listed)); //$NON-NLS-1$ //$NON-NLS-2$
            if (total > listed.size())
            {
                sb.append(" ... (").append(total - listed.size()) //$NON-NLS-1$
                    .append(" more; get_metadata_details on '").append(addressFqn).append("' lists all)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append('.');
        }
        return sb.toString();
    }

    private String unknownGroupError(String at, String groupRef)
    {
        List<String> names = new ArrayList<>();
        for (Group group : groups)
        {
            names.add(group.displayName());
        }
        return at + " '" + groupRef + "' is not a panel group of " + ownerFqn + ". Use one of: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + String.join(", ", names) + ". A configuration command group is addressed as " //$NON-NLS-1$ //$NON-NLS-2$
            + "'CommandGroup.<Name>' and must belong to the navigation or actions panel."; //$NON-NLS-1$
    }

    private static PlanResult error(String message)
    {
        return new PlanResult(null, message);
    }

    /**
     * @param message the actionable error
     * @return a failed plan result carrying {@code message}
     */
    static PlanResult failed(String message)
    {
        return error(message);
    }

    private static String stringValue(JsonObject obj, String key)
    {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive())
        {
            return null;
        }
        String value = el.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean isDefaultToken(JsonElement el)
    {
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
            && "default".equalsIgnoreCase(el.getAsString().trim()); //$NON-NLS-1$
    }

    private static Boolean booleanValue(JsonElement el)
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

    // ===== rendering ================================================================================

    /**
     * Renders the section as Markdown: one table per panel group, commands in effective order.
     *
     * @param note an optional line rendered under the heading (e.g. a staleness note), may be {@code null}
     * @return the Markdown block
     */
    public String render(String note)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Command interface: ").append(ownerFqn).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("Commands by panel group in display order. 'Visible' is the common visibility; role " //$NON-NLS-1$
            + "overrides follow it. Edit with modify_metadata(fqn='").append(addressFqn) //$NON-NLS-1$
            .append("', commands=[...]).\n\n"); //$NON-NLS-1$
        if (note != null && !note.isEmpty())
        {
            sb.append("> ").append(note).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<Item> unresolved = new ArrayList<>();
        for (Group group : groups)
        {
            sb.append("### ").append(group.displayName()).append(" - ").append(group.panel.label()); //$NON-NLS-1$ //$NON-NLS-2$
            if (group.custom)
            {
                sb.append(", command group"); //$NON-NLS-1$
            }
            sb.append(", order: ").append(group.orderCustomized ? "custom" : "automatic").append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            List<Item> resolvedItems = new ArrayList<>();
            for (Item item : group.items)
            {
                if (item.resolved)
                {
                    resolvedItems.add(item);
                }
                else
                {
                    unresolved.add(item);
                }
            }
            if (resolvedItems.isEmpty())
            {
                sb.append("_(empty)_\n\n"); //$NON-NLS-1$
                continue;
            }
            sb.append(MarkdownUtils.tableHeader("#", "Command", "Visible", "Role overrides", "Customized")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            int n = 1;
            for (Item item : resolvedItems)
            {
                sb.append(MarkdownUtils.tableRow(String.valueOf(n++), item.fqn,
                    item.visibility.common ? "yes" : "no", roleCell(item.visibility), customizedCell(item))); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append('\n');
        }
        if (!unresolved.isEmpty())
        {
            List<String> names = new ArrayList<>();
            for (Item item : unresolved)
            {
                names.add(item.fqn);
            }
            sb.append("**Unresolved stored commands** (the section stores settings for commands that no " //$NON-NLS-1$
                + "longer resolve): ").append(MarkdownUtils.escapeMarkdown(String.join(", ", names))) //$NON-NLS-1$
                .append("\n\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private static String roleCell(Visibility visibility)
    {
        if (visibility.roles.isEmpty())
        {
            return "-"; //$NON-NLS-1$
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : visibility.roles.entrySet())
        {
            parts.add(e.getKey() + ": " + (Boolean.TRUE.equals(e.getValue()) ? "yes" : "no")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return String.join(", ", parts); //$NON-NLS-1$
    }

    private static String customizedCell(Item item)
    {
        List<String> parts = new ArrayList<>();
        if (item.groupCustomized)
        {
            parts.add("placement"); //$NON-NLS-1$
        }
        if (item.visibilityCustomized)
        {
            parts.add("visibility"); //$NON-NLS-1$
        }
        return parts.isEmpty() ? "-" : String.join(", ", parts); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
