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
import java.util.TreeSet;
import java.util.function.Supplier;

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
import com._1c.g5.v8.dt.form.service.attribute.IUseAlwaysAttributeService.UseAlways;
import com._1c.g5.v8.dt.form.service.datasourceinfo.IDataSourceInfoAssociationService;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A form attribute's "Use always" checkboxes, exposed as the {@code useAlways} property:
 * {@code {"<Attr>.<path>": true|false}}, one designer checkbox per path (issue #661).
 *
 * <p>The model keeps the paths whose checkbox differs from its default in
 * {@code FormAttribute.notDefaultUseAlwaysAttributes}; the default depends on the path. EDT's
 * {@link IUseAlwaysAttributeService} is the only authority on it: every path is resolved to the
 * platform's {@link PropertyInfo}, read through {@code getUseAlwaysValue} and written through
 * {@code setUseAlwaysValue}, the designer's own route. A path the platform cannot resolve, or one it
 * gives no checkbox, is refused; without the platform nothing is written.</p>
 */
public final class UseAlwaysSupport
{
    /** The wire property name on a form attribute. */
    public static final String PROPERTY = "useAlways"; //$NON-NLS-1$

    /** The model feature holding the non-default paths. */
    public static final String FEATURE = "notDefaultUseAlwaysAttributes"; //$NON-NLS-1$

    /** Read-back of a stored path the platform's data tree does not resolve. */
    public static final String UNRESOLVED = "unresolved"; //$NON-NLS-1$

    /** Read-back of a stored path the platform gives no checkbox. */
    public static final String NO_CHECKBOX = "noCheckbox"; //$NON-NLS-1$

    private static final String TYPE_DYNAMIC_LIST = "DynamicList"; //$NON-NLS-1$

    /** Where the services come from; a test seam, the OSGi registry in production. */
    static Supplier<Platform> platformSource = () -> new Platform(
        ServiceAccess.get(IDataSourceInfoAssociationService.class), ServiceAccess.get(IUseAlwaysAttributeService.class));

    /** The two EDT form services the checkbox lives in. */
    public static final class Platform
    {
        final IDataSourceInfoAssociationService dataSources;
        final IUseAlwaysAttributeService useAlways;

        /**
         * @param dataSources resolves a data path to the form's data tree
         * @param useAlways the checkbox rule
         */
        public Platform(IDataSourceInfoAssociationService dataSources, IUseAlwaysAttributeService useAlways)
        {
            this.dataSources = dataSources;
            this.useAlways = useAlways;
        }

        /**
         * The registered services. A missing one is a platform failure, not the caller's mistake, so it
         * throws an unmarked exception that the tool logs at ERROR.
         *
         * @return the services
         * @throws IllegalStateException when either is not available
         */
        static Platform current()
        {
            try
            {
                return platformSource.get();
            }
            catch (RuntimeException e)
            {
                throw new IllegalStateException("'" + PROPERTY + "' needs EDT's form data services " //$NON-NLS-1$ //$NON-NLS-2$
                    + "(IDataSourceInfoAssociationService, IUseAlwaysAttributeService), which this EDT session " //$NON-NLS-1$
                    + "does not provide: " + e.getMessage() + ". Nothing was written; the EDT log names the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "bundle that failed to start.", e); //$NON-NLS-1$
            }
        }
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

    /** A request the platform resolved and gave a checkbox. */
    public static final class Plan
    {
        final Request request;

        Plan(Request request)
        {
            this.request = request;
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
     * Resolves every request through the platform before anything is written: the path must be in the
     * form's data tree and have a checkbox, and a dynamic-list field a form item shows cannot be
     * unchecked.
     *
     * @param formModel the form content model
     * @param attribute the addressed attribute
     * @param requests the parsed requests
     * @param out receives one plan per request
     * @return an actionable refusal, or {@code null}
     * @throws IllegalStateException when the platform services are not available, or the attribute
     *     is not in a form
     */
    public static String plan(EObject formModel, FormAttribute attribute, List<Request> requests, List<Plan> out)
    {
        if (!(formModel instanceof Form))
        {
            throw new IllegalStateException("'" + PROPERTY + "': the attribute '" + attribute.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + "' is not inside a form model, so its data tree cannot be read."); //$NON-NLS-1$
        }
        Form form = (Form)formModel;
        Platform platform = Platform.current();
        for (Request request : requests)
        {
            PropertyInfo info = platform.dataSources.findPropertyInfo(form, dataPath(request.segments));
            if (info == null)
            {
                return unresolvedRefusal(platform, form, request);
            }
            UseAlways def = platform.useAlways.getDefaultValue(info, form);
            if (def == null || def == UseAlways.None)
            {
                return "'" + PROPERTY + "' path '" + request.key + "' has no 'Use always' checkbox in the " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "designer (EDT gives none to a field reached through a reference, an aggregate, a " //$NON-NLS-1$
                    + "dynamic-list field folder, a type whose fields are not read separately, or a Gantt " //$NON-NLS-1$
                    + "chart), so there is nothing to set. Address a field that has one, e.g. '" //$NON-NLS-1$
                    + attribute.getName() + ".<field>'."; //$NON-NLS-1$
            }
            if (!request.useAlways && isDynamicList(attribute))
            {
                // The item may spell the field in either language: compare with both of the
                // platform's spellings of the resolved field, every language of a legacy item path.
                List<List<String>> spellings = spellings(info);
                EObject item = FormElementWriter.itemBoundTo(form,
                    bound -> bound instanceof AbstractDataPath && matchesAnySpelling((AbstractDataPath)bound, spellings));
                if (item != null)
                {
                    return "'" + PROPERTY + "' cannot turn 'Use always' off for '" + request.key //$NON-NLS-1$ //$NON-NLS-2$
                        + "': the form item '" + nameOf(item) + "' shows that dynamic-list field, and the " //$NON-NLS-1$ //$NON-NLS-2$
                        + "list reads a shown field only through this flag. Remove or rebind the item first."; //$NON-NLS-1$
                }
            }
            out.add(new Plan(request));
        }
        return null;
    }

    /**
     * Writes the plans through the platform's own setter. A legacy multi-language entry the platform
     * does not recognize by its active spelling is removed too when the path must not be listed.
     *
     * @param formModel the form content model, in the write transaction
     * @param attribute the attribute, in the write transaction
     * @param plans the plans from {@link #plan}
     */
    public static void apply(EObject formModel, FormAttribute attribute, List<Plan> plans)
    {
        Form form = (Form)formModel;
        Platform platform = Platform.current();
        for (Plan plan : plans)
        {
            PropertyInfo info = platform.dataSources.findPropertyInfo(form, dataPath(plan.request.segments));
            if (info == null)
            {
                throw new IllegalStateException("'" + PROPERTY + "' path '" + plan.request.key //$NON-NLS-1$ //$NON-NLS-2$
                    + "' resolved when validated but not when written."); //$NON-NLS-1$
            }
            platform.useAlways.setUseAlwaysValue(info, plan.request.useAlways, form);
            UseAlways def = platform.useAlways.getDefaultValue(info, form);
            boolean listed = def == UseAlways.Checked ? !plan.request.useAlways : plan.request.useAlways;
            if (!listed)
            {
                removeEverySpelling(attribute, spellings(info));
            }
        }
    }

    /**
     * The stored paths with the state EDT reports for each, as {@code {path: true|false|"unresolved"|
     * "noCheckbox"}} in stored order, and an identity of the stored paths alone, sorted.
     *
     * @param attribute the form attribute, in a read transaction
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
        EObject formModel = formAttribute.eContainer();
        if (!(formModel instanceof Form))
        {
            throw new IllegalStateException("'" + PROPERTY + "': the attribute '" + formAttribute.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + "' is not inside a form model."); //$NON-NLS-1$
        }
        Form form = (Form)formModel;
        JsonObject text = new JsonObject();
        TreeSet<String> identity = new TreeSet<>();
        try
        {
            Platform platform = Platform.current();
            for (AbstractDataPath path : formAttribute.getNotDefaultUseAlwaysAttributes())
            {
                List<String> segments = segmentsOf(path);
                String joined = String.join(".", segments); //$NON-NLS-1$
                identity.add(joined);
                PropertyInfo info = segments.isEmpty() ? null
                    : platform.dataSources.findPropertyInfo(form, dataPath(segments));
                UseAlways state = info == null ? null : platform.useAlways.getUseAlwaysValue(info, form);
                if (info == null)
                {
                    text.addProperty(joined, UNRESOLVED);
                }
                else if (state == UseAlways.Checked || state == UseAlways.Unchecked)
                {
                    text.addProperty(joined, state == UseAlways.Checked);
                }
                else
                {
                    text.addProperty(joined, NO_CHECKBOX);
                }
            }
        }
        catch (RuntimeException e)
        {
            Activator.logError("useAlways read-back of form attribute '" + formAttribute.getName() //$NON-NLS-1$
                + "' failed in the platform", e); //$NON-NLS-1$
            throw e;
        }
        return new String[] { text.toString(), String.join("\n", identity) }; //$NON-NLS-1$
    }

    /**
     * The segments of a stored path as the platform reads them: a legacy multi-language path answers
     * with its active language's spelling ({@code MultiLanguageDataPath.getSegments}).
     *
     * @param path the stored path
     * @return the segments, empty when the path has none
     */
    static List<String> segmentsOf(AbstractDataPath path)
    {
        if (path instanceof MultiLanguageDataPath)
        {
            MultiLanguageDataPath multi = (MultiLanguageDataPath)path;
            int active = multi.getActiveLanguage();
            if (active < 0 || active >= multi.getPaths().size() || multi.getPaths().get(active) == null)
            {
                return Collections.emptyList();
            }
        }
        List<String> segments = path == null ? null : path.getSegments();
        return segments == null ? Collections.emptyList() : segments;
    }

    // ---- internals ------------------------------------------------------------------------------

    /** Names the first segment the platform cannot resolve, and the fields it offers there. */
    private static String unresolvedRefusal(Platform platform, Form form, Request request)
    {
        DataPath path = dataPath(request.segments);
        PropertyInfo parent = null;
        int failed = 1;
        for (int i = request.segments.size() - 2; i >= 0; i--)
        {
            parent = platform.dataSources.findPropertyInfo(form, path, i);
            if (parent != null)
            {
                failed = i + 1;
                break;
            }
        }
        String under = String.join(".", request.segments.subList(0, failed)); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        sb.append('\'').append(PROPERTY).append("' path '").append(request.key).append("': '") //$NON-NLS-1$ //$NON-NLS-2$
            .append(request.segments.get(failed)).append("' is not a field of '").append(under) //$NON-NLS-1$
            .append("' in the form's data, so EDT has no checkbox for it."); //$NON-NLS-1$
        List<String> names = new ArrayList<>();
        if (parent != null)
        {
            for (PropertyInfo child : parent.getPropertyInfos())
            {
                if (child != null && child.getName() != null)
                {
                    names.add(child.getName());
                }
            }
        }
        if (!names.isEmpty())
        {
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            int shown = Math.min(names.size(), 40);
            sb.append(" Fields there: ").append(String.join(", ", names.subList(0, shown))) //$NON-NLS-1$ //$NON-NLS-2$
                .append(shown < names.size() ? ", ..." : "").append('.'); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            sb.append(" get_metadata_details on the attribute's type lists its fields."); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /** Both spellings EDT stores a resolved path in - the ones its own setter matches on removal. */
    private static List<List<String>> spellings(PropertyInfo info)
    {
        List<List<String>> spellings = new ArrayList<>();
        for (ScriptVariant variant : new ScriptVariant[] { ScriptVariant.ENGLISH, ScriptVariant.RUSSIAN })
        {
            AbstractDataPath path = info.getDataPath(variant);
            if (path != null && path.getSegments() != null && !path.getSegments().isEmpty())
            {
                spellings.add(new ArrayList<>(path.getSegments()));
            }
        }
        return spellings;
    }

    /** Removes every entry any spelling of which - every language of a legacy entry - is the path. */
    private static void removeEverySpelling(FormAttribute attribute, List<List<String>> spellings)
    {
        for (Iterator<AbstractDataPath> it = attribute.getNotDefaultUseAlwaysAttributes().iterator(); it.hasNext();)
        {
            if (matchesAnySpelling(it.next(), spellings))
            {
                it.remove();
            }
        }
    }

    private static boolean matchesAnySpelling(AbstractDataPath entry, List<List<String>> spellings)
    {
        if (entry instanceof MultiLanguageDataPath)
        {
            for (AbstractDataPath language : ((MultiLanguageDataPath)entry).getPaths())
            {
                if (matchesAnySpelling(language, spellings))
                {
                    return true;
                }
            }
            return false;
        }
        List<String> segments = entry == null ? null : entry.getSegments();
        if (segments == null)
        {
            return false;
        }
        for (List<String> spelling : spellings)
        {
            if (sameSegments(segments, spelling))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isDynamicList(FormAttribute attribute)
    {
        if (FormElementWriter.isDynamicListAttribute(attribute))
        {
            return true;
        }
        if (attribute.getValueType() == null)
        {
            return false;
        }
        for (TypeItem item : attribute.getValueType().getTypes())
        {
            if (item != null && TYPE_DYNAMIC_LIST.equals(McoreUtil.getTypeName(item)))
            {
                return true;
            }
        }
        return false;
    }

    private static DataPath dataPath(List<String> segments)
    {
        DataPath path = FormFactory.eINSTANCE.createDataPath();
        path.getSegments().addAll(segments);
        return path;
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
