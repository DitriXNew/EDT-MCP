/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Shared READER for a 1C managed form's structure: it resolves the {@code BasicForm} mdo from a form
 * FQN path and renders the editable form content model ({@code com._1c.g5.v8.dt.form.model.Form}) to a
 * full enriched Markdown document - the nested items tree (with per-element synonym/visibility/dataPath
 * and per-kind extras), an Attributes table (Name/Synonym/Type/Main/SavedData), a Commands table and an
 * Event handlers section. The form model is read entirely through EMF reflection ({@code EObject} /
 * {@code eGet}), so this bundle needs no compile-time dependency on the form-model package (mirroring
 * {@link FormElementWriter}, the form WRITER).
 *
 * <p>This is the single home for the form-read logic that {@code get_metadata_details} (a form FQN
 * renders its structure) and {@code delete_metadata} (the form-member delete preview lists item
 * descendants) share. The supplied EObjects must still be inside their read transaction when
 * {@link #render} / {@link #getReferenceList} / {@link #nameOf} run.</p>
 */
public final class FormStructureReader
{
    /** EReference name holding child {@code FormItem}s on a {@code FormItemContainer}. */
    private static final String FEATURE_ITEMS = "items"; //$NON-NLS-1$
    /** EReference name holding the {@code FormAttribute}s on a {@code Form}. */
    private static final String FEATURE_ATTRIBUTES = "attributes"; //$NON-NLS-1$
    /** EReference name holding the {@code FormCommand}s on a {@code Form}. */
    private static final String FEATURE_FORM_COMMANDS = "formCommands"; //$NON-NLS-1$
    /** EAttribute name carrying the programmatic name on a {@code NamedElement}. */
    private static final String FEATURE_NAME = "name"; //$NON-NLS-1$
    /** EAttribute name carrying the per-item integer id on a {@code FormItem}. */
    private static final String FEATURE_ID = "id"; //$NON-NLS-1$
    /** EReference name (EMap by language code) carrying the title on a {@code Titled}. */
    private static final String FEATURE_TITLE = "title"; //$NON-NLS-1$
    /** EReference name carrying the value type on a {@code FormAttribute}. */
    private static final String FEATURE_VALUE_TYPE = "valueType"; //$NON-NLS-1$
    /** EReference name holding the form's {@code AutoCommandBar} (a containment OUTSIDE {@code items}). */
    private static final String FEATURE_AUTO_COMMAND_BAR = "autoCommandBar"; //$NON-NLS-1$
    /** EReference name holding a {@code FormCommand}'s contained handler container. */
    private static final String FEATURE_ACTION = "action"; //$NON-NLS-1$
    /** EReference name of the single {@code CommandHandler} inside a {@code FormCommandHandlerContainer}. */
    private static final String FEATURE_HANDLER = "handler"; //$NON-NLS-1$
    /** EReference name of the {@code CommandHandlerExtension} list inside an extension container. */
    private static final String FEATURE_HANDLERS = "handlers"; //$NON-NLS-1$
    /** Singular item-bearing containments outside {@code items} (bar / context menu / tooltip). */
    private static final String[] SINGULAR_ITEM_CONTAINMENTS =
        {FEATURE_AUTO_COMMAND_BAR, "contextMenu", "extendedTooltip"}; //$NON-NLS-1$ //$NON-NLS-2$

    /** EAttribute name (Boolean) carrying an item's visibility on a {@code FormVisualEntity}. */
    private static final String FEATURE_VISIBLE = "visible"; //$NON-NLS-1$
    /** EReference name holding the contained {@code DataPath} on a bound {@code FormItem}. */
    private static final String FEATURE_DATA_PATH = "dataPath"; //$NON-NLS-1$
    /** EAttribute name (EList of String) carrying the dot-joined path parts on a {@code DataPath}. */
    private static final String FEATURE_SEGMENTS = "segments"; //$NON-NLS-1$
    /** EReference name holding a visual item's type-specific {@code extInfo} sub-object. */
    private static final String FEATURE_EXT_INFO = "extInfo"; //$NON-NLS-1$
    /** EAttribute name (EEnum) carrying the layout {@code group} mode on a group's extInfo. */
    private static final String FEATURE_GROUP = "group"; //$NON-NLS-1$
    /** EAttribute name (EEnum) carrying the optional {@code behavior} on a group's extInfo. */
    private static final String FEATURE_BEHAVIOR = "behavior"; //$NON-NLS-1$
    /** EAttribute name (EEnum) carrying the platform {@code type} on a {@code FormField}/group. */
    private static final String FEATURE_TYPE = "type"; //$NON-NLS-1$
    /** EAttribute name (EEnum) carrying the {@code editMode} on a {@code FormField}. */
    private static final String FEATURE_EDIT_MODE = "editMode"; //$NON-NLS-1$
    /** EAttribute name carrying the bound metadata-command name on a {@code FormButton}. */
    private static final String FEATURE_COMMAND_NAME = "commandName"; //$NON-NLS-1$
    /** EAttribute name (Boolean) flagging the MAIN form attribute on a {@code FormAttribute}. */
    private static final String FEATURE_MAIN = "main"; //$NON-NLS-1$
    /** EAttribute name (Boolean) flagging the saved-data form attribute on a {@code FormAttribute}. */
    private static final String FEATURE_SAVED_DATA = "savedData"; //$NON-NLS-1$
    /** EReference name carrying the single {@code event} on an {@code EventHandler}. */
    private static final String FEATURE_EVENT = "event"; //$NON-NLS-1$
    /** EAttribute name carrying the Russian event name on an {@code Event}. */
    private static final String FEATURE_NAME_RU = "nameRu"; //$NON-NLS-1$

    /** EClass simple-name token identifying a group item. */
    private static final String ECLASS_FORM_GROUP = "FormGroup"; //$NON-NLS-1$
    /** EClass simple-name token identifying a field item. */
    private static final String ECLASS_FORM_FIELD = "FormField"; //$NON-NLS-1$
    /**
     * EClass simple-name token identifying a button item. The concrete form-model button EClass is
     * {@code Button} (NOT {@code FormButton}, which is its platform-type presentation name); this mirrors
     * {@code FormElementWriter.ELEM_BUTTON} and is matched against {@code item.eClass().getName()}.
     */
    private static final String ECLASS_BUTTON = "Button"; //$NON-NLS-1$

    /** The Russian language CODE; selects the {@code nameRu} event name over the English {@code name}. */
    private static final String LANG_RU = "ru"; //$NON-NLS-1$

    /** Upper bound on total visited item nodes for {@link #render}, guarding a pathological form. */
    private static final int MAX_NODES = 5000;

    /** The root-owner label used in the Event handlers table for a form-level handler. */
    private static final String FORM_OWNER_LABEL = "(form)"; //$NON-NLS-1$

    private FormStructureReader()
    {
        // utility class
    }

    /**
     * Resolves the metadata form object ({@code BasicForm}) from a form FQN path. Supports
     * {@code CommonForm.Name} (2 parts) and {@code MetadataType.ObjectName.Forms.FormName} (4 parts).
     * Names match the programmatic {@code Name}, case-insensitively.
     *
     * @param config the configuration
     * @param formPath the form FQN path
     * @return the {@code BasicForm} {@link MdObject}, or {@code null} if not found
     */
    public static MdObject resolveMdForm(Configuration config, String formPath)
    {
        String[] parts = formPath.split("\\."); //$NON-NLS-1$

        // CommonForm.FormName — the CommonForm IS a BasicForm.
        if (parts.length == 2)
        {
            if (!"CommonForm".equalsIgnoreCase(MetadataTypeUtils.toEnglishSingular(parts[0]))) //$NON-NLS-1$
            {
                return null;
            }
            return MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        }

        // MetadataType.ObjectName.Forms.FormName — find the owner object, then its form.
        if (parts.length == 4)
        {
            if (!"forms".equalsIgnoreCase(parts[2])) //$NON-NLS-1$
            {
                return null;
            }
            MdObject owner = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
            if (owner == null)
            {
                return null;
            }
            return findOwnedForm(owner, parts[3]);
        }

        return null;
    }

    /**
     * Finds a form by name in an owner object's {@code getForms()} list, accessed reflectively (the
     * return type is a per-owner subtype of {@code BasicForm}, so the call site cannot bind to a single
     * interface). Name match is case-insensitive against the programmatic {@code Name}.
     *
     * <p>Public so a caller that already holds the OWNER object (e.g. {@code modify_metadata} resolving
     * a bare short form name like {@code 'Форма'} against the object being modified) can look up an
     * owned form directly, without building a full {@code Type.Name.Form.FormName} FQN first - issue
     * #262.</p>
     *
     * @param owner the owner metadata object
     * @param formName the form's programmatic Name
     * @return the matching form, or {@code null} when the owner has no such form (or no forms at all)
     */
    public static MdObject findOwnedForm(MdObject owner, String formName)
    {
        try
        {
            Method getForms = owner.getClass().getMethod("getForms"); //$NON-NLS-1$
            Object result = getForms.invoke(owner);
            if (result instanceof EList<?>)
            {
                for (Object form : (EList<?>)result)
                {
                    if (form instanceof MdObject
                        && formName.equalsIgnoreCase(((MdObject)form).getName()))
                    {
                        return (MdObject)form;
                    }
                }
            }
        }
        catch (ReflectiveOperationException e)
        {
            // Owner type has no getForms() — not a form-bearing object.
        }
        return null;
    }

    // ==================== Rendering (pure, transaction-bound EObjects only) ====================

    /**
     * Renders the FULL enriched form structure to a Markdown document: the nested items tree (each line
     * carries name / type / id / title plus per-item visibility and dataPath and kind-specific extras -
     * group layout / field type+editMode / button command), an Attributes table (Name / Synonym / Type /
     * Main / SavedData) and a Commands table, followed by an Event handlers section listing the BSL
     * handler bound to each event of the form root and every element. Read entirely through EMF
     * reflection - any absent feature simply degrades to nothing, so this never throws on a model that
     * lacks a feature. Pure aside from reading the supplied EObjects, which must still be inside the read
     * transaction when this runs.
     *
     * @param formPath the (normalized) form FQN path, for the heading
     * @param formModel the editable form model EObject (must still be inside the read transaction)
     * @param language the resolved title/event language CODE (may be {@code null})
     * @return the enriched Markdown document
     */
    public static String render(String formPath, EObject formModel, String language)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# Form Structure: ").append(formPath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        renderItems(sb, formModel, language);
        renderAttributes(sb, formModel, language);
        renderCommands(sb, formModel, language);
        renderEventHandlers(sb, formModel, language);
        return sb.toString();
    }

    /**
     * Renders the {@code ## Items} section: the nested item outline (auto command bar first, then the
     * form root's items), capped at {@link #MAX_NODES} nodes with a truncation note. Falls back to
     * {@code _(no items)_} when the form carries neither items nor an auto command bar.
     */
    private static void renderItems(StringBuilder sb, EObject formModel, String language)
    {
        sb.append("## Items\n\n"); //$NON-NLS-1$
        List<EObject> items = getReferenceList(formModel, FEATURE_ITEMS);
        EObject autoCommandBar = getSingleReference(formModel, FEATURE_AUTO_COMMAND_BAR);
        if (items.isEmpty() && autoCommandBar == null)
        {
            sb.append("_(no items)_\n\n"); //$NON-NLS-1$
            return;
        }
        int[] budget = {MAX_NODES};
        boolean[] truncated = {false};
        if (autoCommandBar != null)
        {
            appendItem(sb, autoCommandBar, 0, language, budget, truncated);
        }
        for (EObject item : items)
        {
            appendItem(sb, item, 0, language, budget, truncated);
        }
        // Gate the note on the explicit flag (set only when a node was actually dropped), NOT on an
        // exhausted budget: a form with EXACTLY MAX_NODES nodes drains the budget to 0 yet renders
        // every node, so inferring truncation from budget[0] <= 0 would falsely flag it.
        if (truncated[0])
        {
            sb.append("- _(item outline truncated: more than ") //$NON-NLS-1$
                .append(MAX_NODES).append(" nodes)_\n"); //$NON-NLS-1$
        }
        sb.append('\n');
    }

    /**
     * Renders the {@code ## Attributes} table (Name / Synonym / Type / Main / SavedData), or
     * {@code _(no attributes)_} when the form has no attributes.
     */
    private static void renderAttributes(StringBuilder sb, EObject formModel, String language)
    {
        sb.append("## Attributes\n\n"); //$NON-NLS-1$
        List<EObject> attributes = getReferenceList(formModel, FEATURE_ATTRIBUTES);
        if (attributes.isEmpty())
        {
            sb.append("_(no attributes)_\n\n"); //$NON-NLS-1$
            return;
        }
        sb.append(MarkdownUtils.tableHeader(
            "Name", "Synonym", "Type", "Main", "SavedData")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        for (EObject attribute : attributes)
        {
            sb.append(MarkdownUtils.tableRow(nameOf(attribute), titleOf(attribute, language),
                valueTypeOf(attribute), Boolean.toString(booleanFeature(attribute, FEATURE_MAIN)),
                Boolean.toString(booleanFeature(attribute, FEATURE_SAVED_DATA))));
        }
        sb.append('\n');
    }

    /**
     * Renders the {@code ## Commands} table (Name / Title / Action handler), or {@code _(no commands)_}
     * when the form has no commands.
     */
    private static void renderCommands(StringBuilder sb, EObject formModel, String language)
    {
        sb.append("## Commands\n\n"); //$NON-NLS-1$
        List<EObject> commands = getReferenceList(formModel, FEATURE_FORM_COMMANDS);
        if (commands.isEmpty())
        {
            sb.append("_(no commands)_\n\n"); //$NON-NLS-1$
            return;
        }
        sb.append(MarkdownUtils.tableHeader("Name", "Title", "Action handler")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (EObject command : commands)
        {
            sb.append(MarkdownUtils.tableRow(nameOf(command), titleOf(command, language),
                actionHandlerOf(command)));
        }
        sb.append('\n');
    }

    /**
     * Renders the {@code ## Event handlers} table (Element / Event / Handler) for the BSL handler bound
     * to each event of the form root and every element, or {@code _(no event handlers)_} when none.
     */
    private static void renderEventHandlers(StringBuilder sb, EObject formModel, String language)
    {
        sb.append("## Event handlers\n\n"); //$NON-NLS-1$
        List<String[]> handlers = new ArrayList<>();
        // collectHandlers recurses the form root's 'items' AND its singular containments (the form-wide
        // auto command bar, context menus, tooltips), so the whole element tree is covered from here. It
        // shares the same MAX_NODES bound as the item-outline pass (its own fresh budget, since it is an
        // independent walk) so the Event-handlers section honours the same cap on a pathological form.
        collectHandlers(formModel, FORM_OWNER_LABEL, language, handlers, new int[] {MAX_NODES});
        if (handlers.isEmpty())
        {
            sb.append("_(no event handlers)_\n\n"); //$NON-NLS-1$
            return;
        }
        sb.append(MarkdownUtils.tableHeader("Element", "Event", "Handler")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String[] row : handlers)
        {
            sb.append(MarkdownUtils.tableRow(row[0], row[1], row[2]));
        }
        sb.append('\n');
    }

    /**
     * Appends one item (and recursively its child items) as a nested outline line: beyond name / type /
     * id / title it appends visibility (only when {@code false}), the bound dataPath, and per-kind
     * extras (group layout, field type+editMode, button command). The item NAME is the stable
     * programmatic id; the integer id and item type are shown alongside, and the title (by language
     * code) is appended when present. A shared {@code budget} caps the total node count for a
     * pathological form; when it is hit the shared {@code truncated} flag is raised so the caller can
     * record that nodes were actually dropped.
     */
    private static void appendItem(StringBuilder sb, EObject item, int depth, String language,
        int[] budget, boolean[] truncated)
    {
        if (budget[0] <= 0)
        {
            truncated[0] = true;
            return;
        }
        budget[0]--;
        for (int i = 0; i < depth; i++)
        {
            sb.append("  "); //$NON-NLS-1$
        }
        sb.append("- ").append(escapeOutline(nameOf(item))); //$NON-NLS-1$
        sb.append(" (type: ").append(escapeOutline(typeOf(item))); //$NON-NLS-1$
        Integer id = idOf(item);
        if (id != null)
        {
            sb.append(", id: ").append(id); //$NON-NLS-1$
        }
        String title = titleOf(item, language);
        if (!title.isEmpty())
        {
            sb.append(", title: ").append(escapeOutline(title)); //$NON-NLS-1$
        }
        if (!visibilityOf(item))
        {
            sb.append(", visible: false"); //$NON-NLS-1$
        }
        String dataPath = dataPathOf(item);
        if (!dataPath.isEmpty())
        {
            sb.append(", dataPath: ").append(escapeOutline(dataPath)); //$NON-NLS-1$
        }
        String extras = kindExtrasOf(item);
        if (!extras.isEmpty())
        {
            sb.append(", ").append(escapeOutline(extras)); //$NON-NLS-1$
        }
        sb.append(")\n"); //$NON-NLS-1$

        // Recurse into containers (groups / tables expose the same 'items' feature).
        for (EObject child : getReferenceList(item, FEATURE_ITEMS))
        {
            appendItem(sb, child, depth + 1, language, budget, truncated);
        }
        // Singular item-bearing containments OUTSIDE 'items' (a table's command bar, an item's
        // context menu / extended tooltip). Their names occupy the form-wide namespace, so they must
        // be discoverable - but a designer-default child (no nested items, no title) is noise and is
        // skipped to keep the outline lean.
        for (String featureName : SINGULAR_ITEM_CONTAINMENTS)
        {
            EObject child = getSingleReference(item, featureName);
            if (child != null
                && (!getReferenceList(child, FEATURE_ITEMS).isEmpty()
                    || !titleOf(child, language).isEmpty()))
            {
                appendItem(sb, child, depth + 1, language, budget, truncated);
            }
        }
    }

    // ==================== EMF reflection helpers ====================

    /**
     * Reads a containment/reference list feature by name, returning the contained {@link EObject}s.
     * Returns an empty list when the feature is absent or not a many-valued reference, so callers never
     * have to null-check.
     */
    @SuppressWarnings("unchecked")
    public static List<EObject> getReferenceList(EObject object, String featureName)
    {
        List<EObject> result = new ArrayList<>();
        if (object == null)
        {
            return result;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null || !feature.isMany())
        {
            return result;
        }
        Object value = object.eGet(feature);
        if (value instanceof List<?>)
        {
            for (Object element : (List<?>)value)
            {
                if (element instanceof EObject)
                {
                    result.add((EObject)element);
                }
            }
        }
        return result;
    }

    /**
     * @return the programmatic name, or {@code "(unnamed)"} when the {@code name} feature is absent or
     *         blank (the name is the addressing id, so a blank is surfaced rather than silently dropped)
     */
    public static String nameOf(EObject object)
    {
        Object value = getValue(object, FEATURE_NAME);
        if (value instanceof String && !((String)value).isEmpty())
        {
            return (String)value;
        }
        return "(unnamed)"; //$NON-NLS-1$
    }

    /** @return the EClass simple name of the item (e.g. "FormGroup", "FormField", "Table"). */
    private static String typeOf(EObject object)
    {
        return object != null ? object.eClass().getName() : ""; //$NON-NLS-1$
    }

    /** @return the integer item id, or {@code null} when the {@code id} feature is absent. */
    private static Integer idOf(EObject object)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(FEATURE_ID);
        if (feature == null)
        {
            return null;
        }
        Object value = object.eGet(feature);
        return value instanceof Integer ? (Integer)value : null;
    }

    /**
     * Reads the title for the given language CODE from the title EMap. The title map is keyed by
     * language code (e.g. "en"/"ru"), never by the language name (CLAUDE.md don't #2). Returns
     * {@code ""} when there is no title.
     */
    @SuppressWarnings("unchecked")
    static String titleOf(EObject object, String language)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(FEATURE_TITLE);
        if (feature == null)
        {
            return ""; //$NON-NLS-1$
        }
        Object value = object.eGet(feature);
        if (value instanceof EMap<?, ?>)
        {
            return MetadataLanguageUtils.getSynonymForLanguage(((EMap<String, String>)value).map(), language);
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * @return a short description of a form attribute's value type, or {@code ""} when no type is set.
     *         The type description is rendered by its EClass name plus any contained type names, read
     *         reflectively.
     */
    private static String valueTypeOf(EObject attribute)
    {
        EStructuralFeature feature = attribute.eClass().getEStructuralFeature(FEATURE_VALUE_TYPE);
        if (feature == null)
        {
            return ""; //$NON-NLS-1$
        }
        Object value = attribute.eGet(feature);
        if (!(value instanceof EObject))
        {
            return ""; //$NON-NLS-1$
        }
        return describeTypeDescription((EObject)value);
    }

    /**
     * Renders a 1C {@code TypeDescription} to a readable, language-neutral string by reading its
     * contained {@code types} list (each a {@code TypeItem}/{@code Type} with a name), via EMF
     * reflection. Falls back to the EClass name.
     */
    private static String describeTypeDescription(EObject typeDescription)
    {
        List<EObject> types = getReferenceList(typeDescription, "types"); //$NON-NLS-1$
        if (types.isEmpty())
        {
            return typeDescription.eClass().getName();
        }
        List<String> names = new ArrayList<>();
        for (EObject type : types)
        {
            String name = stringValue(getValue(type, FEATURE_NAME));
            names.add(name.isEmpty() ? type.eClass().getName() : name);
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    /**
     * @return the BSL procedure name(s) bound to a form command's Action - the single
     *         {@code CommandHandler} of a {@code FormCommandHandlerContainer} or the
     *         {@code CommandHandlerExtension}s of an extension container - or {@code ""} when the
     *         command has no action handler. Addressed as {@code ...Command.X.Handler.Action}.
     */
    private static String actionHandlerOf(EObject command)
    {
        EObject action = getSingleReference(command, FEATURE_ACTION);
        if (action == null)
        {
            return ""; //$NON-NLS-1$
        }
        EObject single = getSingleReference(action, FEATURE_HANDLER);
        if (single != null)
        {
            return stringValue(getValue(single, FEATURE_NAME));
        }
        List<String> names = new ArrayList<>();
        for (EObject handler : getReferenceList(action, FEATURE_HANDLERS))
        {
            String name = stringValue(getValue(handler, FEATURE_NAME));
            if (!name.isEmpty())
            {
                names.add(name);
            }
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    /** The value of a single-valued reference feature, or {@code null} when absent/unset. */
    private static EObject getSingleReference(EObject object, String featureName)
    {
        if (object == null)
        {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null || feature.isMany())
        {
            return null;
        }
        Object value = object.eGet(feature);
        return value instanceof EObject ? (EObject)value : null;
    }

    private static Object getValue(EObject object, String featureName)
    {
        if (object == null)
        {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        return feature != null ? object.eGet(feature) : null;
    }

    private static String stringValue(Object value)
    {
        return value instanceof String ? (String)value : ""; //$NON-NLS-1$
    }

    // ==================== Enrichment reflection helpers ====================

    /**
     * @return the item's {@code visible} flag; an absent feature or a non-Boolean value is treated as
     *         visible ({@code true}), so the render only ever calls out a HIDDEN item
     */
    private static boolean visibilityOf(EObject item)
    {
        Object value = getValue(item, FEATURE_VISIBLE);
        return !(value instanceof Boolean) || ((Boolean)value).booleanValue();
    }

    /**
     * @return the item's bound data path, the contained {@code DataPath}'s {@code segments} joined by
     *         {@code "."} (e.g. {@code Object.Description}), or {@code ""} when the item carries no
     *         data path or it has no segments
     */
    @SuppressWarnings("unchecked")
    private static String dataPathOf(EObject item)
    {
        EObject dataPath = getSingleReference(item, FEATURE_DATA_PATH);
        if (dataPath == null)
        {
            return ""; //$NON-NLS-1$
        }
        EStructuralFeature segments = dataPath.eClass().getEStructuralFeature(FEATURE_SEGMENTS);
        if (segments == null || !segments.isMany())
        {
            return ""; //$NON-NLS-1$
        }
        Object value = dataPath.eGet(segments);
        if (!(value instanceof List<?>))
        {
            return ""; //$NON-NLS-1$
        }
        List<String> parts = new ArrayList<>();
        for (Object part : (List<Object>)value)
        {
            if (part != null)
            {
                parts.add(part.toString());
            }
        }
        return String.join(".", parts); //$NON-NLS-1$
    }

    /**
     * @return kind-specific extras for the outline line, or {@code ""} for a kind with none:
     *         <ul>
     *         <li>group ({@code FormGroup}): {@code "group: <extInfoSimpleName> <group> [behavior]"};</li>
     *         <li>field ({@code FormField}): {@code "field: type=<type> editMode=<editMode>"};</li>
     *         <li>button ({@code Button}): {@code "command: <commandName>"}.</li>
     *         </ul>
     *         Every enum is read as its literal via {@link Enumerator}; an absent feature is omitted.
     */
    private static String kindExtrasOf(EObject item)
    {
        if (item == null)
        {
            return ""; //$NON-NLS-1$
        }
        String eClassName = item.eClass().getName();
        if (ECLASS_FORM_GROUP.equals(eClassName))
        {
            return groupExtras(item);
        }
        if (ECLASS_FORM_FIELD.equals(eClassName))
        {
            return fieldExtras(item);
        }
        if (ECLASS_BUTTON.equals(eClassName))
        {
            String command = stringValue(getValue(item, FEATURE_COMMAND_NAME));
            return command.isEmpty() ? "" : "command: " + command; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ""; //$NON-NLS-1$
    }

    /** Builds the {@code group: ...} extras from a group's {@code extInfo} (EClass + group + behavior). */
    private static String groupExtras(EObject group)
    {
        EObject extInfo = getSingleReference(group, FEATURE_EXT_INFO);
        if (extInfo == null)
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder("group: ").append(extInfo.eClass().getName()); //$NON-NLS-1$
        String groupMode = enumLiteralOf(extInfo, FEATURE_GROUP);
        if (!groupMode.isEmpty())
        {
            sb.append(' ').append(groupMode);
        }
        String behavior = enumLiteralOf(extInfo, FEATURE_BEHAVIOR);
        if (!behavior.isEmpty())
        {
            sb.append(' ').append(behavior);
        }
        return sb.toString();
    }

    /** Builds the {@code field: type=... editMode=...} extras from a field's own enum features. */
    private static String fieldExtras(EObject field)
    {
        String type = enumLiteralOf(field, FEATURE_TYPE);
        String editMode = enumLiteralOf(field, FEATURE_EDIT_MODE);
        if (type.isEmpty() && editMode.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder("field:"); //$NON-NLS-1$
        if (!type.isEmpty())
        {
            sb.append(" type=").append(type); //$NON-NLS-1$
        }
        if (!editMode.isEmpty())
        {
            sb.append(" editMode=").append(editMode); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Reads an EEnum feature as its literal, NEVER importing the enum type: the value is cast to
     * {@link Enumerator} and its {@code getName()} returned (falling back to {@code toString()}).
     *
     * @return the enum literal, or {@code ""} when the feature is absent / unset / not an enum value
     */
    private static String enumLiteralOf(EObject object, String featureName)
    {
        if (object == null)
        {
            return ""; //$NON-NLS-1$
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        // Only an EXPLICITLY-SET enum is reported: an unset enum feature reads back as the metamodel's
        // default literal (e.g. a field's default view type), which is noise rather than authored
        // structure, so an absent/unset feature contributes no extra.
        if (feature == null || !object.eIsSet(feature))
        {
            return ""; //$NON-NLS-1$
        }
        Object value = object.eGet(feature);
        if (value instanceof Enumerator)
        {
            String name = ((Enumerator)value).getName();
            return name != null ? name : value.toString();
        }
        return value != null ? value.toString() : ""; //$NON-NLS-1$
    }

    /**
     * @return the value of a Boolean feature; an absent feature or a non-Boolean value yields
     *         {@code false}, so a missing flag never throws and never reports as set
     */
    private static boolean booleanFeature(EObject object, String featureName)
    {
        Object value = getValue(object, featureName);
        return value instanceof Boolean && ((Boolean)value).booleanValue();
    }

    /**
     * Appends one {@code [owner, event, handler]} row per bound event handler of {@code element} to
     * {@code rows}, then recurses into the element's child items (and singular item containments) so the
     * whole subtree is covered. Each handler exposes its own {@code name} (the BSL procedure) and a
     * single {@code event} reference whose {@code name} (en) / {@code nameRu} (ru) is the event name.
     *
     * @param element the form root or a form item whose {@code handlers} list is read
     * @param ownerLabel the Element-column label for handlers directly on {@code element}
     * @param language the event-name language CODE
     * @param rows the accumulator receiving {@code {owner, event, handler}} rows
     * @param budget the shared per-visited-element node budget, capping the walk on a pathological form
     */
    private static void collectHandlers(EObject element, String ownerLabel, String language,
        List<String[]> rows, int[] budget)
    {
        if (element == null || budget[0] <= 0)
        {
            return;
        }
        budget[0]--;
        for (EObject handler : getReferenceList(element, FEATURE_HANDLERS))
        {
            String procName = stringValue(getValue(handler, FEATURE_NAME));
            String eventName = eventNameOf(getSingleReference(handler, FEATURE_EVENT), language);
            rows.add(new String[] {ownerLabel, eventName, procName});
        }
        for (EObject child : getReferenceList(element, FEATURE_ITEMS))
        {
            collectHandlers(child, nameOf(child), language, rows, budget);
        }
        for (String featureName : SINGULAR_ITEM_CONTAINMENTS)
        {
            EObject child = getSingleReference(element, featureName);
            if (child != null)
            {
                collectHandlers(child, nameOf(child), language, rows, budget);
            }
        }
    }

    /**
     * @return the event's name for the given language CODE - {@code nameRu} for {@code "ru"}, otherwise
     *         the English {@code name}, falling back to the other when the preferred one is blank
     */
    private static String eventNameOf(EObject event, String language)
    {
        if (event == null)
        {
            return ""; //$NON-NLS-1$
        }
        boolean ru = LANG_RU.equalsIgnoreCase(language);
        String preferred = stringValue(getValue(event, ru ? FEATURE_NAME_RU : FEATURE_NAME));
        if (!preferred.isEmpty())
        {
            return preferred;
        }
        return stringValue(getValue(event, ru ? FEATURE_NAME : FEATURE_NAME_RU));
    }

    /**
     * Escapes a value for use inside a parenthesised outline line so a stray newline, '(' or ')' cannot
     * corrupt the nesting. The Markdown table cells go through {@link MarkdownUtils} separately.
     */
    private static String escapeOutline(String text)
    {
        if (text == null)
        {
            return ""; //$NON-NLS-1$
        }
        return text.replace("\r", "") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\n", " ") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("(", "\\(") //$NON-NLS-1$ //$NON-NLS-2$
            .replace(")", "\\)"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
