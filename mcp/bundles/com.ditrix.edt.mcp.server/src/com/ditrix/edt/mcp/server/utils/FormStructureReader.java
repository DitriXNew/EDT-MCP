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
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Shared READER for a 1C managed form's structure: it resolves the {@code BasicForm} mdo from a form
 * FQN path and renders the editable form content model ({@code com._1c.g5.v8.dt.form.model.Form}) - the
 * nested items tree, the form attributes and the form commands - to Markdown. The form model is read
 * entirely through EMF reflection ({@code EObject} / {@code eGet}), so this bundle needs no compile-time
 * dependency on the form-model package (mirroring {@link FormElementWriter}, the form WRITER).
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
     */
    private static MdObject findOwnedForm(MdObject owner, String formName)
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
     * Renders the whole form structure (items tree + attributes + commands) to a Markdown document.
     * Pure aside from reading the supplied EObjects, which must still be inside the read transaction
     * when this runs.
     *
     * @param formPath the (normalized) form FQN path, for the heading
     * @param formModel the editable form model EObject
     * @param language the resolved title language CODE (may be {@code null})
     * @return the Markdown document
     */
    public static String render(String formPath, EObject formModel, String language)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# Form Structure: ").append(formPath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        sb.append("## Items\n\n"); //$NON-NLS-1$
        List<EObject> items = getReferenceList(formModel, FEATURE_ITEMS);
        EObject autoCommandBar = getSingleReference(formModel, FEATURE_AUTO_COMMAND_BAR);
        if (items.isEmpty() && autoCommandBar == null)
        {
            sb.append("_(no items)_\n\n"); //$NON-NLS-1$
        }
        else
        {
            // The form's auto command bar is a separate containment, not part of 'items'; render it
            // first (the way the designer shows it). Address it as parent 'AutoCommandBar'.
            if (autoCommandBar != null)
            {
                appendItem(sb, autoCommandBar, 0, language);
            }
            for (EObject item : items)
            {
                appendItem(sb, item, 0, language);
            }
            sb.append('\n');
        }

        sb.append("## Attributes\n\n"); //$NON-NLS-1$
        List<EObject> attributes = getReferenceList(formModel, FEATURE_ATTRIBUTES);
        if (attributes.isEmpty())
        {
            sb.append("_(no attributes)_\n\n"); //$NON-NLS-1$
        }
        else
        {
            sb.append(MarkdownUtils.tableHeader("Name", "Type")); //$NON-NLS-1$ //$NON-NLS-2$
            for (EObject attribute : attributes)
            {
                sb.append(MarkdownUtils.tableRow(nameOf(attribute), valueTypeOf(attribute)));
            }
            sb.append('\n');
        }

        sb.append("## Commands\n\n"); //$NON-NLS-1$
        List<EObject> commands = getReferenceList(formModel, FEATURE_FORM_COMMANDS);
        if (commands.isEmpty())
        {
            sb.append("_(no commands)_\n\n"); //$NON-NLS-1$
        }
        else
        {
            sb.append(MarkdownUtils.tableHeader("Name", "Title", "Action handler")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            for (EObject command : commands)
            {
                sb.append(MarkdownUtils.tableRow(nameOf(command), titleOf(command, language),
                    actionHandlerOf(command)));
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Appends one item (and recursively its child items) as a nested outline line. The item NAME is
     * the stable programmatic id; the integer id and item type are shown alongside, and the title (by
     * language code) is appended when present.
     */
    private static void appendItem(StringBuilder sb, EObject item, int depth, String language)
    {
        for (int i = 0; i < depth; i++)
        {
            sb.append("  "); //$NON-NLS-1$
        }
        String name = nameOf(item);
        sb.append("- ").append(escapeOutline(name)); //$NON-NLS-1$
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
        sb.append(")\n"); //$NON-NLS-1$

        // Recurse into containers (groups / tables expose the same 'items' feature).
        for (EObject child : getReferenceList(item, FEATURE_ITEMS))
        {
            appendItem(sb, child, depth + 1, language);
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
