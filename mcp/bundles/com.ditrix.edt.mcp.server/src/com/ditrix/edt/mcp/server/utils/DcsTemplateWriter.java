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
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.dcs.model.areaTemplate.AreaTemplateParameter;
import com._1c.g5.v8.dt.dcs.model.common.DataCompositionAreaTemplateType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaGroupTemplate;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTemplateDescription;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionChartGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionTableGroup;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Typed writes of a schema's area templates ({@code templates}) and grouping bindings
 * ({@code groupTemplates}, {@code groupHeaderTemplates}) under {@code type='schema'} (#662), at the
 * addresses the read prints and in the root body.
 *
 * <p>Everything is validated against the attached schema first; {@link Plan#commit} then performs
 * only the list operations the request needs, so templates and bindings the call does not touch
 * keep their objects. Integrity is judged on the resulting state: a binding this call writes must
 * name an existing template, and a template that bindings still name is not removed or renamed.
 */
public final class DcsTemplateWriter
{
    static final String TEMPLATES = DcsTemplateProjection.TEMPLATES;
    static final String GROUP_TEMPLATES = DcsTemplateProjection.GROUP_TEMPLATES;
    static final String GROUP_HEADER_TEMPLATES = DcsTemplateProjection.GROUP_HEADER_TEMPLATES;
    static final String FIELD_TEMPLATES = "fieldTemplates"; //$NON-NLS-1$
    static final String TOTAL_FIELDS_TEMPLATES = "totalFieldsTemplates"; //$NON-NLS-1$

    private static final String ACTION_UPSERT = "upsert"; //$NON-NLS-1$
    private static final String ACTION_UPDATE = "update"; //$NON-NLS-1$
    private static final String ACTION_REPLACE = "replace"; //$NON-NLS-1$
    private static final String ACTION_REMOVE = "remove"; //$NON-NLS-1$
    private static final String TYPE_SCHEMA = "schema"; //$NON-NLS-1$

    private DcsTemplateWriter()
    {
        // utility class
    }

    /** @return whether a {@code type='schema'} write addresses a template collection or one of its nodes */
    public static boolean handles(String type, List<String> segments)
    {
        return TYPE_SCHEMA.equals(type) && segments != null && !segments.isEmpty()
            && DcsTemplateProjection.isCollection(segments.get(0));
    }

    /** @return whether the address is one template or binding node */
    public static boolean isNodePath(String type, List<String> segments)
    {
        return handles(type, segments) && segments.size() == 2;
    }

    /**
     * The lexical refusals for a write at a template address, judged before any model work.
     *
     * @param action the requested write action
     * @param type the requested type token
     * @param address the parsed target
     * @param body the request body, or {@code null}
     * @return the refusal text, or {@code null}
     */
    public static String shapeRefusal(String action, String type, DcsAddress address, JsonObject body)
    {
        if (address == null || !address.hasPointer()) return null;
        String collection = address.segments().get(0);
        boolean authorable = DcsTemplateProjection.isCollection(collection);
        boolean xmlOnly = FIELD_TEMPLATES.equals(collection) || TOTAL_FIELDS_TEMPLATES.equals(collection);
        if (!authorable && !xmlOnly || !DcsTemplateProjection.isSchemaRoot(address)) return null;
        if (xmlOnly) return DcsUnsupportedAuthoring.templateCollectionRefusal(collection, address.toString());
        if (!TYPE_SCHEMA.equals(type))
        {
            return "Template address '" + address + "' is written with type='schema', not type='" //$NON-NLS-1$ //$NON-NLS-2$
                + type + "'; action='" + action + "' was not run. Resend it with type='schema'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (address.segments().size() > 2)
        {
            return "'" + address + "' points inside a template node; action='" + action //$NON-NLS-1$ //$NON-NLS-2$
                + "' was not run. Address the node itself ('" //$NON-NLS-1$
                + DcsAddress.render(address.rootFqn(), address.segments().subList(0, 2))
                + "'): patch single cells with body.cells, or send rows/parameters."; //$NON-NLS-1$
        }
        if (address.segments().size() == 1 && !ACTION_UPSERT.equals(action))
        {
            return "action='" + action + "' at collection '" + address + "' was not run: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (ACTION_REPLACE.equals(action)
                    ? "replace one node, or the bare root with " + collection + ":[...]." //$NON-NLS-1$ //$NON-NLS-2$
                    : "address one node copied from get, or use action='upsert' at the collection."); //$NON-NLS-1$
        }
        if (body != null && body.has("insertPosition")) //$NON-NLS-1$
        {
            return "body.insertPosition is not accepted at template address '" + address //$NON-NLS-1$
                + "': templates are keyed by name and bindings are appended. Remove it."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * A body that patches stored cells depends on the observed grid, so it needs the hash of the
     * read it was made from, as {@code insertPosition} does.
     */
    public static boolean requiresHash(DcsAddress address, JsonObject body)
    {
        if (address == null || body == null) return false;
        if (address.hasPointer())
        {
            return TEMPLATES.equals(address.segments().get(0)) && body.has(DcsTemplateBody.KEY_CELLS);
        }
        JsonElement templates = body.get(TEMPLATES);
        if (templates == null || !templates.isJsonArray()) return false;
        for (JsonElement template : templates.getAsJsonArray())
        {
            if (template.isJsonObject() && template.getAsJsonObject().has(DcsTemplateBody.KEY_CELLS)) return true;
        }
        return false;
    }

    /** Removes the template members from a root body and returns them. */
    public static JsonObject takeRootMembers(JsonObject body)
    {
        JsonObject members = new JsonObject();
        for (String member : List.of(TEMPLATES, GROUP_TEMPLATES, GROUP_HEADER_TEMPLATES))
        {
            if (body != null && body.has(member)) members.add(member, body.remove(member));
        }
        return members;
    }

    // ----- planning ------------------------------------------------------------------------------

    /**
     * Plans a write at a template collection or node.
     *
     * @param schema the attached schema; not changed
     * @param action upsert, update, replace or remove
     * @param address the template address
     * @param body the request body, {@code null} for remove
     * @param context the build context
     * @return the plan or the refusal
     */
    public static PlanResult plan(DataCompositionSchema schema, String action, DcsAddress address,
        JsonObject body, DcsTemplateBody.Context context)
    {
        State state = new State(schema, address.rootFqn(), false);
        List<String> segments = address.segments();
        String collection = segments.get(0);
        String error;
        if (segments.size() == 1)
        {
            if (body == null) return PlanResult.failure("A body is required at '" + address + "'."); //$NON-NLS-1$ //$NON-NLS-2$
            error = TEMPLATES.equals(collection)
                ? upsertTemplate(state, body, context, address.toString())
                : upsertBinding(state, collection, body, address.toString());
        }
        else
        {
            error = TEMPLATES.equals(collection)
                ? templateNode(state, action, segments.get(1), body, context, address)
                : bindingNode(state, action, collection, segments.get(1), body, address);
        }
        if (error == null) error = state.integrityError();
        return error == null ? PlanResult.success(state.plan()) : PlanResult.failure(error);
    }

    /**
     * Plans the template members of a bare-root body. {@code replace} rebuilds the three
     * collections from the body alone; {@code upsert} keys templates by name and bindings by
     * grouping and template type.
     */
    public static PlanResult planRoot(DataCompositionSchema schema, String action, DcsAddress address,
        JsonObject members, DcsTemplateBody.Context context)
    {
        boolean replace = ACTION_REPLACE.equals(action);
        State state = new State(schema, address.rootFqn(), replace);
        JsonArray templates = null;
        for (String member : List.of(TEMPLATES, GROUP_TEMPLATES, GROUP_HEADER_TEMPLATES))
        {
            JsonElement value = members.get(member);
            if (value != null && !value.isJsonArray())
            {
                return PlanResult.failure("body." + member + " must be an array of " //$NON-NLS-1$ //$NON-NLS-2$
                    + (TEMPLATES.equals(member) ? "template objects." : "binding objects.")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (TEMPLATES.equals(member)) templates = value == null ? null : value.getAsJsonArray();
        }
        if (templates != null)
        {
            for (int i = 0; i < templates.size(); i++)
            {
                if (!templates.get(i).isJsonObject())
                {
                    return PlanResult.failure("body.templates[" + i + "] must be a template object."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                String error = upsertTemplate(state, templates.get(i).getAsJsonObject(), context,
                    "body.templates[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                if (error != null) return PlanResult.failure(error);
            }
        }
        for (String member : List.of(GROUP_TEMPLATES, GROUP_HEADER_TEMPLATES))
        {
            JsonElement value = members.get(member);
            if (value == null) continue;
            JsonArray bindings = value.getAsJsonArray();
            for (int i = 0; i < bindings.size(); i++)
            {
                String path = "body." + member + "[" + i + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (!bindings.get(i).isJsonObject()) return PlanResult.failure("'" + path + "' must be a binding object."); //$NON-NLS-1$ //$NON-NLS-2$
                String error = upsertBinding(state, member, bindings.get(i).getAsJsonObject(), path);
                if (error != null) return PlanResult.failure(error);
            }
        }
        String error = state.integrityError();
        return error == null ? PlanResult.success(state.plan()) : PlanResult.failure(error);
    }

    private static String upsertTemplate(State state, JsonObject body, DcsTemplateBody.Context context,
        String path)
    {
        String name = stringMember(body, DcsTemplateBody.KEY_NAME);
        if (name == null || name.isEmpty())
        {
            return "'" + path + ".name' is required: a template is created or found by its name."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<Integer> exact = state.templatesNamed(name, false);
        if (exact.size() > 1)
        {
            return "Template name '" + name + "' at '" + path + "' is ambiguous: " + exact.size() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " templates carry it. Address one of them by index (" + state.selectors() + ")."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (exact.size() == 1)
        {
            return changeTemplate(state, exact.get(0).intValue(), body, context, path, false);
        }
        String nameError = newNameError(state, name, -1, path);
        if (nameError != null) return nameError;
        DataCompositionSchemaTemplateDescription created =
            DcsFactory.eINSTANCE.createDataCompositionSchemaTemplateDescription();
        Set<String> references = new LinkedHashSet<>();
        String error = DcsTemplateBody.apply(created, body, context, path, false, references);
        if (error != null) return error;
        state.templates.add(created);
        state.wroteTemplate(created, references);
        if (created.getTemplate() == null)
        {
            state.warnings.add("Template '" + name + "' was created without rows; send rows to give it a body."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private static String templateNode(State state, String action, String selector, JsonObject body,
        DcsTemplateBody.Context context, DcsAddress address)
    {
        int index = state.templateIndex(selector);
        if (index < 0)
        {
            List<Integer> namesakes = state.templatesNamed(selector, false);
            return namesakes.size() > 1
                ? "Template name '" + selector + "' at '" + address + "' is ambiguous: " + namesakes.size() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " templates carry it, so the read addresses them by index. Address one of: " //$NON-NLS-1$
                    + state.selectors() + "." //$NON-NLS-1$
                : "No template '" + selector + "' exists at '" + address + "'. Existing: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + state.selectors() + ". Re-run get and copy an address."; //$NON-NLS-1$
        }
        DataCompositionSchemaTemplateDescription stored = state.templates.get(index);
        String path = address.toString();
        if (ACTION_REMOVE.equals(action))
        {
            String referenced = state.templateReferenceError(stored.getName(), index, address);
            if (referenced != null) return referenced;
            state.templates.remove(index);
            state.templateCount++;
            return null;
        }
        if (ACTION_REPLACE.equals(action))
        {
            String name = stringMember(body, DcsTemplateBody.KEY_NAME);
            if (body.has(DcsTemplateBody.KEY_NAME) && !java.util.Objects.equals(name, stored.getName()))
            {
                return "action='replace' at '" + address + "' keeps the template's name '" + stored.getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + "'; body.name is '" + name + "'. Omit name, or rename with action='update'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            DataCompositionSchemaTemplateDescription replacement =
                DcsFactory.eINSTANCE.createDataCompositionSchemaTemplateDescription();
            replacement.setName(stored.getName());
            Set<String> references = new LinkedHashSet<>();
            String error = DcsTemplateBody.apply(replacement, body, context, path, true, references);
            if (error != null) return error;
            state.templates.set(index, replacement);
            state.wroteTemplate(replacement, references);
            return null;
        }
        if (ACTION_UPSERT.equals(action) && body.has(DcsTemplateBody.KEY_NAME)
            && !java.util.Objects.equals(stringMember(body, DcsTemplateBody.KEY_NAME), stored.getName()))
        {
            return "action='upsert' at node '" + address + "' keeps the template's name '" + stored.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Omit body.name, or rename with action='update'."; //$NON-NLS-1$
        }
        return changeTemplate(state, index, body, context, path, true);
    }

    /** A partial change of a stored template: rename, rows, a cells patch, parameters. */
    private static String changeTemplate(State state, int index, JsonObject body,
        DcsTemplateBody.Context context, String path, boolean renameAllowed)
    {
        DataCompositionSchemaTemplateDescription stored = state.templates.get(index);
        boolean rows = body.has(DcsTemplateBody.KEY_ROWS);
        String discarded = DcsTemplateBody.partRefusal(stored, context, rows, false);
        String part = DcsTemplateBody.KEY_ROWS;
        if (discarded == null && body.has(DcsTemplateBody.KEY_PARAMETERS))
        {
            discarded = DcsTemplateBody.partRefusal(stored, context, false, true);
            part = DcsTemplateBody.KEY_PARAMETERS;
        }
        if (discarded != null)
        {
            return "'" + path + "' would discard stored " + part //$NON-NLS-1$ //$NON-NLS-2$
                + " of template '" + stored.getName() + "' that this writer cannot restate: " + discarded //$NON-NLS-1$ //$NON-NLS-2$
                + ". Nothing was written. Patch single cells with body.cells, or carry the schema " //$NON-NLS-1$
                + "through the lossless XML channel (action='replace', type='schema', body={xml:...})."; //$NON-NLS-1$
        }
        String newName = body.has(DcsTemplateBody.KEY_NAME) ? stringMember(body, DcsTemplateBody.KEY_NAME) : null;
        boolean rename = newName != null && !newName.equals(stored.getName());
        if (rename)
        {
            if (!renameAllowed)
            {
                return "'" + path + "' cannot rename template '" + stored.getName() + "'."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            String nameError = newNameError(state, newName, index, path);
            if (nameError != null) return nameError;
            String referenced = state.templateReferenceError(stored.getName(), index,
                state.templateAddress(index));
            if (referenced != null) return referenced;
        }
        DataCompositionSchemaTemplateDescription changed = EcoreUtil.copy(stored);
        Set<String> references = new LinkedHashSet<>();
        String error = DcsTemplateBody.apply(changed, body, context, path, false, references);
        if (error != null) return error;
        state.templates.set(index, changed);
        state.wroteTemplate(changed, references);
        return null;
    }

    private static String newNameError(State state, String name, int self, String path)
    {
        if (name == null || name.isEmpty())
        {
            return "'" + path + ".name' must be a non-empty template name."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (DcsAddress.isZeroBasedIndex(name))
        {
            return "Template name '" + name + "' at '" + path + "' reads as an index, so the read could " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "not address the template by it. Give it a name that is not a number."; //$NON-NLS-1$
        }
        for (int i : state.templatesNamed(name, true))
        {
            if (i != self)
            {
                return "Template name '" + name + "' at '" + path + "' collides with the existing template '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + state.templates.get(i).getName() + "' (names are compared case-insensitively). " //$NON-NLS-1$
                    + "Choose another name, or address that template to change it."; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static String upsertBinding(State state, String collection, JsonObject body, String path)
    {
        List<DataCompositionSchemaGroupTemplate> bindings = state.bindings(collection);
        DataCompositionSchemaGroupTemplate probe = DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate();
        String error = DcsTemplateBody.applyBinding(probe, body, path, true);
        if (error != null) return error;
        List<Integer> same = new ArrayList<>();
        for (int i = 0; i < bindings.size(); i++)
        {
            if (sameKey(bindings.get(i), probe)) same.add(Integer.valueOf(i));
        }
        if (same.size() > 1)
        {
            return "Two stored bindings in '" + collection + "' bind the same grouping and template type (" //$NON-NLS-1$ //$NON-NLS-2$
                + bindingKey(probe) + "): indices " + same + ". Update one of them by its address instead " //$NON-NLS-1$ //$NON-NLS-2$
                + "of upserting."; //$NON-NLS-1$
        }
        if (same.size() == 1)
        {
            int index = same.get(0).intValue();
            DataCompositionSchemaGroupTemplate changed = EcoreUtil.copy(bindings.get(index));
            changed.setTemplate(probe.getTemplate());
            bindings.set(index, changed);
            state.wroteBinding(collection, changed);
            return null;
        }
        bindings.add(probe);
        state.wroteBinding(collection, probe);
        return null;
    }

    private static String bindingNode(State state, String action, String collection, String selector,
        JsonObject body, DcsAddress address)
    {
        List<DataCompositionSchemaGroupTemplate> bindings = state.bindings(collection);
        if (!DcsAddress.isZeroBasedIndex(selector) || Integer.parseInt(selector) >= bindings.size())
        {
            return "No binding '" + selector + "' exists at '" + address + "': " + collection + " holds " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + bindings.size() + " binding(s), addressed by zero-based index. Re-run get."; //$NON-NLS-1$
        }
        int index = Integer.parseInt(selector);
        if (ACTION_REMOVE.equals(action))
        {
            bindings.remove(index);
            state.countBinding(collection);
            return null;
        }
        boolean authoritative = ACTION_REPLACE.equals(action);
        DataCompositionSchemaGroupTemplate changed = authoritative
            ? DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate() : EcoreUtil.copy(bindings.get(index));
        String error = DcsTemplateBody.applyBinding(changed, body, address.toString(), authoritative);
        if (error != null) return error;
        bindings.set(index, changed);
        state.wroteBinding(collection, changed);
        return null;
    }

    private static boolean sameKey(DataCompositionSchemaGroupTemplate first, DataCompositionSchemaGroupTemplate second)
    {
        return bindingKey(first).equals(bindingKey(second));
    }

    private static String bindingKey(DataCompositionSchemaGroupTemplate binding)
    {
        String grouping;
        if (binding.getGroupName() != null && !binding.getGroupName().isEmpty())
        {
            grouping = "groupName " + binding.getGroupName().toLowerCase(Locale.ROOT); //$NON-NLS-1$
        }
        else
        {
            List<String> fields = new ArrayList<>();
            for (DataCompositionField field : binding.getGroupFields())
            {
                fields.add(field == null || field.getValue() == null ? "" //$NON-NLS-1$
                    : field.getValue().toLowerCase(Locale.ROOT));
            }
            grouping = "groupFields " + String.join(", ", fields); //$NON-NLS-1$ //$NON-NLS-2$
        }
        DataCompositionAreaTemplateType type = binding.getTemplateType();
        return grouping + ", templateType " + (type == null ? "" : type.getLiteral()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String stringMember(JsonObject body, String member)
    {
        JsonElement value = body == null ? null : body.get(member);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : null;
    }

    // ----- state, plan and result ----------------------------------------------------------------

    /** The planned contents of the three collections, holding the attached objects that stay. */
    private static final class State
    {
        final DataCompositionSchema schema;
        final String rootFqn;
        final List<DataCompositionSchemaTemplateDescription> templates;
        final List<DataCompositionSchemaGroupTemplate> groupTemplates;
        final List<DataCompositionSchemaGroupTemplate> groupHeaderTemplates;
        final Map<DataCompositionSchemaTemplateDescription, Set<String>> writtenTemplates = new IdentityHashMap<>();
        final Map<DataCompositionSchemaGroupTemplate, String> writtenBindings = new IdentityHashMap<>();
        final List<String> warnings = new ArrayList<>();
        int templateCount;
        int groupTemplateCount;
        int groupHeaderTemplateCount;

        State(DataCompositionSchema schema, String rootFqn, boolean cleared)
        {
            this.schema = schema;
            this.rootFqn = rootFqn;
            this.templates = cleared ? new ArrayList<>() : new ArrayList<>(schema.getTemplates());
            this.groupTemplates = cleared ? new ArrayList<>() : new ArrayList<>(schema.getGroupTemplates());
            this.groupHeaderTemplates = cleared ? new ArrayList<>()
                : new ArrayList<>(schema.getGroupHeaderTemplates());
            if (cleared)
            {
                templateCount = schema.getTemplates().size();
                groupTemplateCount = schema.getGroupTemplates().size();
                groupHeaderTemplateCount = schema.getGroupHeaderTemplates().size();
            }
        }

        List<DataCompositionSchemaGroupTemplate> bindings(String collection)
        {
            return GROUP_TEMPLATES.equals(collection) ? groupTemplates : groupHeaderTemplates;
        }

        void wroteTemplate(DataCompositionSchemaTemplateDescription template, Set<String> references)
        {
            writtenTemplates.put(template, references);
            templateCount++;
        }

        void wroteBinding(String collection, DataCompositionSchemaGroupTemplate binding)
        {
            writtenBindings.put(binding, collection);
            countBinding(collection);
        }

        void countBinding(String collection)
        {
            if (GROUP_TEMPLATES.equals(collection)) groupTemplateCount++;
            else groupHeaderTemplateCount++;
        }

        List<Integer> templatesNamed(String name, boolean ignoreCase)
        {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < templates.size(); i++)
            {
                String candidate = templates.get(i).getName();
                if (candidate != null && (ignoreCase ? candidate.equalsIgnoreCase(name) : candidate.equals(name)))
                {
                    result.add(Integer.valueOf(i));
                }
            }
            return result;
        }

        /** The index the read's selector names, by the read's own rule; -1 when none. */
        int templateIndex(String selector)
        {
            for (int i = 0; i < templates.size(); i++)
            {
                if (selector.equals(selector(i))) return i;
            }
            return -1;
        }

        private String selector(int index)
        {
            DataCompositionSchemaTemplateDescription template = templates.get(index);
            String name = template.getName();
            return name == null || name.isEmpty() || DcsAddress.isZeroBasedIndex(name)
                || templatesNamed(name, false).size() != 1 ? Integer.toString(index) : name;
        }

        String selectors()
        {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < templates.size(); i++) result.add(selector(i));
            return result.isEmpty() ? "(none)" : String.join(", ", result); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** The read's address of a planned template that is still the attached one. */
        DcsAddress templateAddress(int index)
        {
            return DcsAddress.parse(DcsAddress.render(rootFqn, List.of(TEMPLATES, selector(index))))
                .address();
        }

        /**
         * Refuses removing or renaming a template that a binding, field template or resource
         * template of the schema still names - unless another template keeps that exact name.
         */
        String templateReferenceError(String name, int index, DcsAddress address)
        {
            if (name == null || name.isEmpty()) return null;
            for (int i : templatesNamed(name, false))
            {
                if (i != index) return null;
            }
            return DcsMutationGuard.referenceError(schema, address, "template", name); //$NON-NLS-1$
        }

        /** Bindings this call writes must name a template of the resulting schema. */
        String integrityError()
        {
            Set<String> names = new LinkedHashSet<>();
            for (DataCompositionSchemaTemplateDescription template : templates)
            {
                if (template.getName() != null) names.add(template.getName());
            }
            for (Map.Entry<DataCompositionSchemaGroupTemplate, String> entry : writtenBindings.entrySet())
            {
                String template = entry.getKey().getTemplate();
                if (!names.contains(template))
                {
                    return "The binding written to '" + bindingAddress(entry.getValue(), entry.getKey()) //$NON-NLS-1$
                        + "' names template '" + template + "', which this schema does not have. Existing " //$NON-NLS-1$ //$NON-NLS-2$
                        + "templates: " + (names.isEmpty() ? "(none)" : String.join(", ", names)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + ". Create the template first (or in the same root call), or name an existing one; " //$NON-NLS-1$
                        + "nothing was written."; //$NON-NLS-1$
                }
            }
            return null;
        }

        String bindingAddress(String collection, DataCompositionSchemaGroupTemplate binding)
        {
            int index = indexOf(bindings(collection), binding);
            return DcsAddress.render(rootFqn, List.of(collection, Integer.toString(index)));
        }

        Plan plan()
        {
            List<String> all = new ArrayList<>(warnings);
            Set<String> groupings = groupingNames(schema);
            for (Map.Entry<DataCompositionSchemaGroupTemplate, String> entry : writtenBindings.entrySet())
            {
                String groupName = entry.getKey().getGroupName();
                if (groupName == null || groupName.isEmpty() || containsIgnoreCase(groupings, groupName)) continue;
                all.add("Binding '" + bindingAddress(entry.getValue(), entry.getKey()) + "' names grouping '" //$NON-NLS-1$ //$NON-NLS-2$
                    + groupName + "', which no grouping of the default settings or of a variant carries" //$NON-NLS-1$
                    + " (named groupings: " + (groupings.isEmpty() ? "none" : String.join(", ", groupings)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "). EDT does not check this; the binding takes effect once a grouping has that name."); //$NON-NLS-1$
            }
            for (Map.Entry<DataCompositionSchemaTemplateDescription, Set<String>> entry : writtenTemplates.entrySet())
            {
                if (!templates.contains(entry.getKey())) continue;
                Set<String> declared = new LinkedHashSet<>();
                for (AreaTemplateParameter parameter : entry.getKey().getParameters())
                {
                    if (parameter.getName() != null) declared.add(parameter.getName().toLowerCase(Locale.ROOT));
                }
                List<String> undeclared = new ArrayList<>();
                for (String reference : entry.getValue())
                {
                    if (!declared.contains(reference.toLowerCase(Locale.ROOT))) undeclared.add(reference);
                }
                if (!undeclared.isEmpty())
                {
                    all.add("Template '" + entry.getKey().getName() + "' refers to parameter(s) " //$NON-NLS-1$ //$NON-NLS-2$
                        + String.join(", ", undeclared) + " that it does not declare in parameters. EDT " //$NON-NLS-1$ //$NON-NLS-2$
                        + "does not check this; declare them so the cells get a value."); //$NON-NLS-1$
                }
            }
            return new Plan(templates, groupTemplates, groupHeaderTemplates,
                new Summary(templateCount, groupTemplateCount, groupHeaderTemplateCount, all));
        }
    }

    private static boolean containsIgnoreCase(Set<String> values, String value)
    {
        for (String candidate : values)
        {
            if (candidate.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    /** Names of the groupings (group, table group, chart group) of the default settings and variants. */
    static Set<String> groupingNames(DataCompositionSchema schema)
    {
        Set<String> result = new LinkedHashSet<>();
        List<DataCompositionSettings> settings = new ArrayList<>();
        if (schema.getDefaultSettings() != null) settings.add(schema.getDefaultSettings());
        for (SettingsVariant variant : schema.getSettingsVariants())
        {
            if (variant.getSettings() != null) settings.add(variant.getSettings());
        }
        for (DataCompositionSettings item : settings)
        {
            var contents = item.eAllContents();
            while (contents.hasNext())
            {
                EObject object = contents.next();
                String name = object instanceof DataCompositionGroup ? ((DataCompositionGroup)object).getName()
                    : object instanceof DataCompositionTableGroup ? ((DataCompositionTableGroup)object).getName()
                    : object instanceof DataCompositionChartGroup ? ((DataCompositionChartGroup)object).getName()
                    : null;
                if (name != null && !name.isEmpty()) result.add(name);
            }
        }
        return result;
    }

    private static <T> int indexOf(List<T> list, T item)
    {
        for (int i = 0; i < list.size(); i++)
        {
            if (list.get(i) == item) return i;
        }
        return -1;
    }

    /** A validated template write; {@link #commit} is its only model change. */
    public static final class Plan
    {
        private final List<DataCompositionSchemaTemplateDescription> templates;
        private final List<DataCompositionSchemaGroupTemplate> groupTemplates;
        private final List<DataCompositionSchemaGroupTemplate> groupHeaderTemplates;
        private final Summary summary;

        Plan(List<DataCompositionSchemaTemplateDescription> templates,
            List<DataCompositionSchemaGroupTemplate> groupTemplates,
            List<DataCompositionSchemaGroupTemplate> groupHeaderTemplates, Summary summary)
        {
            this.templates = templates;
            this.groupTemplates = groupTemplates;
            this.groupHeaderTemplates = groupHeaderTemplates;
            this.summary = summary;
        }

        /** Brings the attached collections to the planned contents with minimal list operations. */
        public void commit(DataCompositionSchema schema)
        {
            reconcile(schema.getTemplates(), templates);
            reconcile(schema.getGroupTemplates(), groupTemplates);
            reconcile(schema.getGroupHeaderTemplates(), groupHeaderTemplates);
        }

        public Summary summary()
        {
            return summary;
        }

        private static <T> void reconcile(EList<T> actual, List<T> wanted)
        {
            Set<T> keep = Collections.newSetFromMap(new IdentityHashMap<>());
            keep.addAll(wanted);
            for (int i = actual.size() - 1; i >= 0; i--)
            {
                if (!keep.contains(actual.get(i))) actual.remove(i);
            }
            for (int i = 0; i < wanted.size(); i++)
            {
                T item = wanted.get(i);
                if (i < actual.size() && actual.get(i) == item) continue;
                int at = indexOf(actual, item);
                if (at >= 0) actual.move(i, at);
                else actual.add(i, item);
            }
        }
    }

    /** What a template write did, for the tool's result. */
    public static final class Summary
    {
        private final int templates;
        private final int groupTemplates;
        private final int groupHeaderTemplates;
        private final List<String> warnings;

        Summary(int templates, int groupTemplates, int groupHeaderTemplates, List<String> warnings)
        {
            this.templates = templates;
            this.groupTemplates = groupTemplates;
            this.groupHeaderTemplates = groupHeaderTemplates;
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public int templates()
        {
            return templates;
        }

        public int groupTemplates()
        {
            return groupTemplates;
        }

        public int groupHeaderTemplates()
        {
            return groupHeaderTemplates;
        }

        public List<String> warnings()
        {
            return warnings;
        }

        /** {@code templates=N, groupTemplates=N, groupHeaderTemplates=N}. */
        public String counts()
        {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put(TEMPLATES, Integer.valueOf(templates));
            counts.put(GROUP_TEMPLATES, Integer.valueOf(groupTemplates));
            counts.put(GROUP_HEADER_TEMPLATES, Integer.valueOf(groupHeaderTemplates));
            List<String> parts = new ArrayList<>();
            counts.forEach((key, value) -> parts.add(key + "=" + value)); //$NON-NLS-1$
            return String.join(", ", parts); //$NON-NLS-1$
        }
    }

    /** A plan or the refusal that stopped it. */
    public static final class PlanResult
    {
        private final Plan plan;
        private final String error;

        private PlanResult(Plan plan, String error)
        {
            this.plan = plan;
            this.error = error;
        }

        static PlanResult success(Plan plan)
        {
            return new PlanResult(plan, null);
        }

        static PlanResult failure(String error)
        {
            return new PlanResult(null, error);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public Plan plan()
        {
            return plan;
        }

        public String error()
        {
            return error;
        }
    }
}
