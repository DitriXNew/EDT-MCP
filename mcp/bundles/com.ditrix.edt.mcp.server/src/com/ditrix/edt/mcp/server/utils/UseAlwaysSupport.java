/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.MultiLanguageDataPath;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.form.service.attribute.IUseAlwaysAttributeService;
import com._1c.g5.v8.dt.form.service.datasourceinfo.IDataSourceInfoAssociationService;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A form attribute's "Use always" checkboxes (the attribute tree's column in the designer), exposed as
 * the {@code useAlways} property: {@code {"<Attr>.<path>": true|false}}, one designer checkbox per
 * path (issue #661).
 *
 * <p>The model keeps them in {@code FormAttribute.notDefaultUseAlwaysAttributes}, whose meaning flips
 * with the default of the attribute's root type - EDT's {@code UseAlwaysAttributeService}: a
 * ConstantsSet, a RegisterRecordsCollection and a dynamic list's columns default to UNCHECKED, so a
 * listed path is "use always"; any other root defaults to CHECKED, so a listed path is "not use always".
 * The caller sets the checkbox and this class computes the membership. A path the platform's data tree
 * resolves is written through that service itself; one it does not resolve falls back to the same
 * root-type rule, replicated in {@link #rootDefault}.</p>
 */
public final class UseAlwaysSupport
{
    /** The wire property name on a form attribute. */
    public static final String PROPERTY = "useAlways"; //$NON-NLS-1$

    /** The model feature holding the non-default paths. */
    public static final String FEATURE = "notDefaultUseAlwaysAttributes"; //$NON-NLS-1$

    private static final String TYPE_GANTT_CHART = "GanttChart"; //$NON-NLS-1$
    private static final String TYPE_DYNAMIC_LIST = "DynamicList"; //$NON-NLS-1$
    private static final String TYPE_CONSTANTS_SET = "ConstantsSet"; //$NON-NLS-1$
    private static final String TYPE_RECORDS_COLLECTION = "RegisterRecordsCollection"; //$NON-NLS-1$

    /** The designer's default checkbox state of a path; mirrors {@code IUseAlwaysAttributeService.UseAlways}. */
    public enum UseAlwaysDefault
    {
        /** The designer shows no checkbox. */
        NONE,
        /** Checked unless listed. */
        CHECKED,
        /** Unchecked unless listed. */
        UNCHECKED
    }

    /** What the attribute's value type makes of its paths, in the order the platform tests them. */
    enum RootKind
    {
        GANTT_CHART, DYNAMIC_LIST, CONSTANTS_SET, RECORDS_COLLECTION, OTHER
    }

    /** One requested checkbox: the path as given (first segment canonicalized) and its state. */
    public static final class Request
    {
        final String key;
        final List<String> segments;
        final boolean useAlways;

        Request(String key, List<String> segments, boolean useAlways)
        {
            this.key = key;
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
            this.useAlways = useAlways;
        }
    }

    /** A validated request and how it is written: through the platform, or by the replicated rule. */
    public static final class Plan
    {
        final Request request;
        /** The platform's {@code PropertyInfo} for the path, or {@code null}. */
        final PropertyInfo info;
        final UseAlwaysDefault def;

        Plan(Request request, PropertyInfo info, UseAlwaysDefault def)
        {
            this.request = request;
            this.info = info;
            this.def = def;
        }
    }

    private UseAlwaysSupport()
    {
        // utility class
    }

    /**
     * Whether {@code feature} is a form attribute's {@code notDefaultUseAlwaysAttributes}.
     *
     * @param feature the feature
     * @return whether it is the use-always list
     */
    public static boolean isUseAlwaysFeature(EStructuralFeature feature)
    {
        return feature instanceof EReference && FEATURE.equals(feature.getName()) && feature.isMany()
            && ((EReference)feature).isContainment();
    }

    /**
     * Parses {@code {"<Attr>.<path>": true|false}}: at least two segments, the first naming the
     * attribute itself, no path twice.
     *
     * @param attributeName the addressed attribute's name
     * @param value the wire value
     * @param out receives the requests
     * @return an actionable refusal, or {@code null}
     */
    public static String parse(String attributeName, JsonElement value, List<Request> out)
    {
        String example = "e.g. {\"" + attributeName + ".Code\": false}"; //$NON-NLS-1$ //$NON-NLS-2$
        if (value == null || !value.isJsonObject() || value.getAsJsonObject().size() == 0)
        {
            return "'" + PROPERTY + "' takes a non-empty object of '<attribute>.<path>': true|false " //$NON-NLS-1$ //$NON-NLS-2$
                + "(the designer's 'Use always' checkbox per path), " + example + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet())
        {
            String key = entry.getKey();
            List<String> segments = new ArrayList<>();
            for (String part : key.split("\\.", -1)) //$NON-NLS-1$
            {
                segments.add(part.trim());
            }
            if (segments.size() < 2 || segments.contains("")) //$NON-NLS-1$
            {
                return "'" + PROPERTY + "' path '" + key + "' must be '" + attributeName //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ".<field>' - the attribute's own name, then the field path. " + example + "."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!segments.get(0).equalsIgnoreCase(attributeName))
            {
                return "'" + PROPERTY + "' path '" + key + "' starts with '" + segments.get(0) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "', not with the addressed attribute '" + attributeName + "'. Address each path from " //$NON-NLS-1$ //$NON-NLS-2$
                    + "it, " + example + ", or modify the attribute the path belongs to."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            segments.set(0, attributeName);
            Boolean state = AdjustableBooleanSupport.booleanValue(entry.getValue());
            if (state == null)
            {
                return "'" + PROPERTY + "' path '" + key + "' must be true (use always) or false, got " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + entry.getValue() + "."; //$NON-NLS-1$
            }
            for (Request earlier : out)
            {
                if (sameSegments(earlier.segments, segments))
                {
                    return "'" + PROPERTY + "' names the path '" + earlier.key + "' twice (also as '" + key //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + "'). Give each path once."; //$NON-NLS-1$
                }
            }
            out.add(new Request(key, segments, state.booleanValue()));
        }
        return null;
    }

    /**
     * Validates every request against the form and decides how it is written. Runs before anything
     * is written, inside the transaction that will write.
     *
     * @param config the configuration (constants are checked against it), may be {@code null}
     * @param formModel the form content model
     * @param attribute the addressed attribute
     * @param requests the parsed requests
     * @param out receives one plan per request
     * @return an actionable refusal, or {@code null}
     */
    public static String plan(Configuration config, EObject formModel, FormAttribute attribute,
        List<Request> requests, List<Plan> out)
    {
        RootKind kind = rootKind(attribute);
        for (Request request : requests)
        {
            PropertyInfo info = formModel instanceof Form ? platformInfo((Form)formModel, request.segments) : null;
            UseAlwaysDefault def = info != null ? platformDefault(info, (Form)formModel) : null;
            if (def == null)
            {
                String err = staticCheck(config, attribute, kind, request);
                if (err != null)
                {
                    return err;
                }
                info = null;
                def = rootDefault(kind);
            }
            if (def == UseAlwaysDefault.NONE)
            {
                return "'" + PROPERTY + "' path '" + request.key + "' has no 'Use always' checkbox in the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "designer (a field reached through a reference, an aggregate, a field folder, a type " //$NON-NLS-1$
                    + "whose fields are not read separately, or a Gantt chart), so there is nothing to set. " //$NON-NLS-1$
                    + "Address the field itself, e.g. '" + attribute.getName() + ".<field>'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (kind == RootKind.DYNAMIC_LIST && !request.useAlways && formModel != null)
            {
                EObject item = FormElementWriter.itemBoundTo(formModel, request.segments);
                if (item != null)
                {
                    return "'" + PROPERTY + "' cannot turn 'Use always' off for '" + request.key //$NON-NLS-1$ //$NON-NLS-2$
                        + "': the form item '" + nameOf(item) + "' shows that dynamic-list field, and the " //$NON-NLS-1$ //$NON-NLS-2$
                        + "list reads a shown field only through this flag. Remove or rebind the item first."; //$NON-NLS-1$
                }
            }
            out.add(new Plan(request, info, def));
        }
        return null;
    }

    /**
     * Writes the plans onto the attribute, each through the platform's own service when the plan
     * carries its {@code PropertyInfo}, otherwise by the replicated membership rule.
     *
     * @param formModel the form content model, in the write transaction
     * @param attribute the attribute, in the write transaction
     * @param plans the plans from {@link #plan}
     */
    public static void apply(EObject formModel, FormAttribute attribute, List<Plan> plans)
    {
        IUseAlwaysAttributeService service = null;
        for (Plan plan : plans)
        {
            if (plan.info != null && formModel instanceof Form)
            {
                service = service != null ? service : ServiceAccess.get(IUseAlwaysAttributeService.class);
                if (service != null)
                {
                    service.setUseAlwaysValue(plan.info, plan.request.useAlways, (Form)formModel);
                    continue;
                }
            }
            writeMembership(attribute, plan.request.segments, listed(plan.def, plan.request.useAlways));
        }
    }

    /**
     * Whether a path must be in the list for the wanted checkbox state.
     *
     * @param def the path's default
     * @param useAlways the wanted state
     * @return whether the path is listed
     */
    static boolean listed(UseAlwaysDefault def, boolean useAlways)
    {
        return def == UseAlwaysDefault.CHECKED ? !useAlways : useAlways;
    }

    /**
     * The default of a field directly under a root of {@code kind} - the platform's rule for a
     * resolved, non-reference field (EDT {@code UseAlwaysAttributeService.getDefaultValue}).
     *
     * @param kind the root kind
     * @return the default
     */
    static UseAlwaysDefault rootDefault(RootKind kind)
    {
        switch (kind)
        {
            case GANTT_CHART:
                return UseAlwaysDefault.NONE;
            case DYNAMIC_LIST:
            case CONSTANTS_SET:
            case RECORDS_COLLECTION:
                return UseAlwaysDefault.UNCHECKED;
            default:
                return UseAlwaysDefault.CHECKED;
        }
    }

    /**
     * The root kind of an attribute, tested in the platform's order: any GanttChart, then any
     * DynamicList (or a dynamic-list ext-info), then a single ConstantsSet / RegisterRecordsCollection.
     *
     * @param attribute the attribute
     * @return the kind
     */
    static RootKind rootKind(FormAttribute attribute)
    {
        List<String> names = typeNames(attribute.getValueType());
        if (names.contains(TYPE_GANTT_CHART))
        {
            return RootKind.GANTT_CHART;
        }
        if (names.contains(TYPE_DYNAMIC_LIST) || FormElementWriter.isDynamicListAttribute(attribute))
        {
            return RootKind.DYNAMIC_LIST;
        }
        if (names.size() == 1 && TYPE_CONSTANTS_SET.equals(names.get(0)))
        {
            return RootKind.CONSTANTS_SET;
        }
        if (names.size() == 1 && TYPE_RECORDS_COLLECTION.equals(names.get(0)))
        {
            return RootKind.RECORDS_COLLECTION;
        }
        return RootKind.OTHER;
    }

    /**
     * The current checkboxes that differ from the default, as {@code {path: effective state}}: the
     * text in stored order and an order-independent identity.
     *
     * @param attribute the form attribute
     * @return {text, identity}, or {@code null} when every path is at its default
     */
    public static String[] render(EObject attribute)
    {
        if (!(attribute instanceof FormAttribute))
        {
            return null; // NOSONAR null means "nothing to render"
        }
        FormAttribute formAttribute = (FormAttribute)attribute;
        if (formAttribute.getNotDefaultUseAlwaysAttributes().isEmpty())
        {
            return null; // NOSONAR null means "nothing to render"
        }
        boolean listedMeans = rootDefault(rootKind(formAttribute)) == UseAlwaysDefault.UNCHECKED;
        JsonObject text = new JsonObject();
        TreeMap<String, Boolean> sorted = new TreeMap<>();
        for (AbstractDataPath path : formAttribute.getNotDefaultUseAlwaysAttributes())
        {
            String joined = String.join(".", segmentsOf(path)); //$NON-NLS-1$
            text.addProperty(joined, listedMeans);
            sorted.put(joined, listedMeans);
        }
        JsonObject identity = new JsonObject();
        for (Map.Entry<String, Boolean> e : sorted.entrySet())
        {
            identity.addProperty(e.getKey(), e.getValue());
        }
        return new String[] { text.toString(), identity.toString() };
    }

    /** The segments of a stored path; a legacy multi-language path answers with its first spelling. */
    static List<String> segmentsOf(AbstractDataPath path)
    {
        if (path instanceof MultiLanguageDataPath)
        {
            List<AbstractDataPath> paths = ((MultiLanguageDataPath)path).getPaths();
            return paths.isEmpty() ? Collections.emptyList() : segmentsOf(paths.get(0));
        }
        return path == null ? Collections.emptyList() : path.getSegments();
    }

    // ---- internals ------------------------------------------------------------------------------

    /** The checks we can make without the platform's data tree; {@code null} when the path may stand. */
    private static String staticCheck(Configuration config, FormAttribute attribute, RootKind kind,
        Request request)
    {
        String field = request.segments.get(1);
        if (kind == RootKind.CONSTANTS_SET)
        {
            if (request.segments.size() > 2)
            {
                return "'" + PROPERTY + "' path '" + request.key + "' goes below a constant; a constants " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "set has a 'Use always' checkbox per constant only: '" + attribute.getName() //$NON-NLS-1$
                    + ".<Constant>'."; //$NON-NLS-1$
            }
            if (config != null && constantByName(config, field) == null)
            {
                return "'" + PROPERTY + "' path '" + request.key + "': '" + field + "' is not a constant of " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    + "the configuration. Address it by its programmatic Name; get_metadata_objects with " //$NON-NLS-1$
                    + "metadataType 'Constant' lists them."; //$NON-NLS-1$
            }
            return null;
        }
        MdObject owner = kind == RootKind.OTHER ? metadataOwner(attribute.getValueType()) : null;
        if (owner != null)
        {
            Set<String> fields = FormElementWriter.fieldNamesOf(owner);
            if (!fields.isEmpty() && !containsIgnoreCase(fields, field))
            {
                List<String> listed = new ArrayList<>(fields);
                Collections.sort(listed);
                return "'" + PROPERTY + "' path '" + request.key + "': '" + field + "' is not a field of " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    + owner.eClass().getName() + "." + owner.getName() + ". Fields: " //$NON-NLS-1$ //$NON-NLS-2$
                    + String.join(", ", listed) + "."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    /** The platform's data-tree node for the path, or {@code null} when the platform cannot resolve it. */
    private static PropertyInfo platformInfo(Form form, List<String> segments)
    {
        try
        {
            IDataSourceInfoAssociationService service =
                ServiceAccess.get(IDataSourceInfoAssociationService.class);
            if (service == null)
            {
                return null;
            }
            DataPath path = FormFactory.eINSTANCE.createDataPath();
            path.getSegments().addAll(segments);
            return service.findPropertyInfo(form, path);
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }

    /** The platform's default for a resolved path, or {@code null} when the service cannot answer. */
    private static UseAlwaysDefault platformDefault(PropertyInfo info, Form form)
    {
        try
        {
            IUseAlwaysAttributeService service = ServiceAccess.get(IUseAlwaysAttributeService.class);
            IUseAlwaysAttributeService.UseAlways value = service == null ? null : service.getDefaultValue(info, form);
            if (value == null)
            {
                return null;
            }
            switch (value)
            {
                case Checked:
                    return UseAlwaysDefault.CHECKED;
                case Unchecked:
                    return UseAlwaysDefault.UNCHECKED;
                default:
                    return UseAlwaysDefault.NONE;
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }

    /** Adds the path when {@code listed} and it is absent; removes every spelling of it otherwise. */
    private static void writeMembership(FormAttribute attribute, List<String> segments, boolean listed)
    {
        List<AbstractDataPath> list = attribute.getNotDefaultUseAlwaysAttributes();
        boolean present = false;
        for (Iterator<AbstractDataPath> it = list.iterator(); it.hasNext();)
        {
            if (sameSegments(segmentsOf(it.next()), segments))
            {
                if (!listed)
                {
                    it.remove();
                }
                present = true;
            }
        }
        if (listed && !present)
        {
            DataPath path = FormFactory.eINSTANCE.createDataPath();
            path.getSegments().addAll(segments);
            list.add(path);
        }
    }

    private static List<String> typeNames(TypeDescription type)
    {
        List<String> names = new ArrayList<>();
        if (type == null)
        {
            return names;
        }
        for (TypeItem item : type.getTypes())
        {
            String name = item == null ? null : McoreUtil.getTypeName(item);
            if (name != null)
            {
                names.add(name);
            }
        }
        return names;
    }

    /** The metadata object producing a single-typed attribute's type (e.g. a catalog for CatalogObject.X). */
    private static MdObject metadataOwner(TypeDescription type)
    {
        if (type == null || type.getTypes().size() != 1)
        {
            return null;
        }
        EObject current = type.getTypes().get(0);
        while (current != null && !(current instanceof MdObject))
        {
            current = current.eContainer();
        }
        return (MdObject)current;
    }

    private static Constant constantByName(Configuration config, String name)
    {
        for (Constant constant : config.getConstants())
        {
            if (constant != null && name.equalsIgnoreCase(constant.getName()))
            {
                return constant;
            }
        }
        return null;
    }

    private static boolean containsIgnoreCase(Set<String> names, String name)
    {
        for (String candidate : names)
        {
            if (candidate.equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean sameSegments(List<String> a, List<String> b)
    {
        if (a.size() != b.size())
        {
            return false;
        }
        for (int i = 0; i < a.size(); i++)
        {
            if (!a.get(i).equalsIgnoreCase(b.get(i)))
            {
                return false;
            }
        }
        return true;
    }

    private static String nameOf(EObject item)
    {
        EStructuralFeature name = item.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
        Object value = name == null ? null : item.eGet(name);
        return value == null ? item.eClass().getName() : value.toString();
    }
}
