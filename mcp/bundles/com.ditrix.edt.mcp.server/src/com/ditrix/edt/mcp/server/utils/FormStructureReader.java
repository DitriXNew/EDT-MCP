/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Shared READER for a 1C managed form's structure: it resolves the {@code BasicForm} mdo from a form
 * FQN path and renders the editable form content model ({@code com._1c.g5.v8.dt.form.model.Form}) to a
 * full enriched Markdown document - the nested items tree (with per-element synonym/visibility/dataPath
 * and per-kind extras), an Attributes table (Name/Synonym/Type/Main/SavedData), a Commands table,
 * a Parameters table (only when the form declares parameters) and an
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
    /** The columns of a collection-typed form attribute (issue #295). */
    private static final String FEATURE_COLUMNS = "columns"; //$NON-NLS-1$
    /** EReference name holding the {@code FormCommand}s on a {@code Form}. */
    private static final String FEATURE_FORM_COMMANDS = "formCommands"; //$NON-NLS-1$

    /** The form's {@code parameters} containment - FormParameter, issue #396. */
    private static final String FEATURE_PARAMETERS = "parameters"; //$NON-NLS-1$

    /** A form parameter's "key parameter" flag. */
    private static final String FEATURE_KEY_PARAMETER = "keyParameter"; //$NON-NLS-1$

    /** A form parameter's free-text comment - it has no title. */
    private static final String FEATURE_COMMENT = "comment"; //$NON-NLS-1$
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

    /**
     * Upper bound on total visited item nodes for {@link #render}, guarding a pathological form.
     * Shared with the other whole-form walks (the delete prompt's content count) so one form-wide
     * traversal budget is stated once.
     *
     * <p><b>It bounds VISITS, not DEPTH, and the difference is not academic.</b> When the elements
     * are nested in a chain the two are the same number, so a walk that re-enters itself once per
     * element can stand this many frames deep before the budget declines anything - and the failure
     * that follows is not a truncated table: {@code StackOverflowError} is an {@link Error}, it
     * escapes every {@code catch (Exception)} and every {@code catch (RuntimeException)} on the way
     * out, and the caller gets no result at all rather than a short one.</p>
     *
     * <p><b>Both walks that descend a form therefore use an explicit stack</b> and take this only
     * for the node cap it is: {@link #collectHandlers} for the Event-handlers section and
     * {@link #appendItem} for the item outline. Neither leans on the caller's {@code rowLimit} to
     * keep it shallow, and that was never a bound to lean on: {@code get_metadata_details} renders
     * a form through the three-argument {@link #render}, which hands down exactly this ceiling, so
     * the shallow limit the comparison report happens to pass is one caller's choice and not a
     * property of this reader. What is left is a cap on how many elements are LOOKED AT, honoured
     * identically by both walks, on a form of any depth.</p>
     */
    public static final int MAX_NODES = 5000;

    /** The root-owner label used in the Event handlers table for a form-level handler. */
    private static final String FORM_OWNER_LABEL = "(form)"; //$NON-NLS-1$

    /**
     * What the Event-handlers section says when its own walk stopped at {@link #MAX_NODES} visited
     * elements.
     *
     * <p>This bound is INDEPENDENT of the caller's row limit and has to be reported independently.
     * The row cap bites on rows the walk DECLINED TO KEEP, and it is visible in the numbers;
     * this one bites on elements that were never LOOKED AT, so it can leave the section holding
     * fewer handlers than the caller asked for - or none at all - and every surface that would have
     * betrayed it stays silent: no row was declined, so no cap note is due, and a shorter table is
     * indistinguishable from a complete one. The worst case was the empty one, where the section
     * went on to state {@code _(no event handlers)_} about a form whose handler-bearing elements the
     * walk had never reached.</p>
     */
    private static final String HANDLER_WALK_CUT_SHORT =
        "the handler walk stopped after visiting " + MAX_NODES //$NON-NLS-1$
            + " elements, so elements past that point were never looked at"; //$NON-NLS-1$

    /**
     * The Event-handlers section's line when the walk was cut short having found no handler.
     * <p>
     * It deliberately does not carry the words the complete walk uses: "no event handlers" is a
     * statement about the FORM, and nothing here observed the form - only the part of it the walk
     * got through.
     */
    private static final String HANDLERS_NONE_FOUND_WALK_CUT_SHORT =
        "_(no handler was found, but " + HANDLER_WALK_CUT_SHORT //$NON-NLS-1$
            + " - whether this form has event handlers is not established)_"; //$NON-NLS-1$

    /** The line printed under a handler table whose walk was cut short. */
    private static final String HANDLERS_WALK_CUT_SHORT_NOTE =
        "_(" + HANDLER_WALK_CUT_SHORT + " and their handlers are not in this table)_"; //$NON-NLS-1$ //$NON-NLS-2$

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
        return resolveMdForm(MetadataScope.ofConfiguration(config), formPath);
    }

    /**
     * Resolves the metadata form object ({@code BasicForm}) from a form FQN path, against whichever
     * ROOT the project has - the {@link Configuration} of a configuration / extension project, or
     * the standalone root objects of an external-objects project, whose external data processors
     * and reports own forms exactly like a configuration object does (issue #309).
     *
     * @param scope the resolution root (may be {@code null})
     * @param formPath the form FQN path
     * @return the {@code BasicForm} {@link MdObject}, or {@code null} if not found
     */
    public static MdObject resolveMdForm(MetadataScope scope, String formPath)
    {
        if (scope == null || formPath == null)
        {
            return null;
        }
        String[] parts = formPath.split("\\."); //$NON-NLS-1$

        // CommonForm.FormName — the CommonForm IS a BasicForm.
        if (parts.length == 2)
        {
            if (!"CommonForm".equalsIgnoreCase(MetadataTypeUtils.toEnglishSingular(parts[0]))) //$NON-NLS-1$
            {
                return null;
            }
            return scope.findObject(parts[0], parts[1]);
        }

        // MetadataType.ObjectName.Forms.FormName — find the owner object, then its form.
        if (parts.length == 4)
        {
            if (!"forms".equalsIgnoreCase(parts[2])) //$NON-NLS-1$
            {
                return null;
            }
            MdObject owner = scope.findObject(parts[0], parts[1]);
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
        return render(formPath, formModel, language, MAX_NODES);
    }

    /**
     * The same document, with every table and the item outline capped at {@code rowLimit} rows and
     * every cap SAID where it bites.
     *
     * <p>This overload exists because a caller can own a row budget of its own. The comparison
     * report does: {@code get_comparison_node} promises "maximum rows per table" and prints a form
     * node's structure inside its own document, so without a cap here that promise held for the
     * report's own tables and quietly failed for the six this reader writes - a {@code limit} of 1
     * would still have produced every attribute, command, parameter and handler the form has, plus
     * an item outline of up to {@link #MAX_NODES} lines.</p>
     *
     * <p>{@link #MAX_NODES} stays the ceiling: it is the guard against a pathological form, not a
     * caller preference, so a larger {@code rowLimit} does not raise it. The three-argument entry
     * point passes exactly that ceiling, which is what the reader already applied, so the document
     * it produces is unchanged.</p>
     *
     * @param formPath the (normalized) form FQN path, for the heading
     * @param formModel the editable form model EObject (must still be inside the read transaction)
     * @param language the resolved title/event language CODE (may be {@code null})
     * @param rowLimit the maximum number of rows per table and lines in the item outline; values
     *            below 1 are read as 1, values above {@link #MAX_NODES} as {@link #MAX_NODES}
     * @return the enriched Markdown document
     */
    public static String render(String formPath, EObject formModel, String language, int rowLimit)
    {
        int limit = Math.min(Math.max(1, rowLimit), MAX_NODES);
        StringBuilder sb = new StringBuilder();
        sb.append("# Form Structure: ").append(formPath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        renderItems(sb, formModel, language, limit);
        renderAttributes(sb, formModel, language, limit);
        renderAttributeColumns(sb, formModel, language, limit);
        renderCommands(sb, formModel, language, limit);
        renderParameters(sb, formModel, limit);
        renderEventHandlers(sb, formModel, language, limit);
        return sb.toString();
    }

    /**
     * The ONE line this document prints when a cap dropped rows, so a short table is never read as
     * a complete one.
     *
     * <p>It states the cap and nothing else. It deliberately does NOT tell the reader to raise a
     * {@code limit}: this reader is called both from a tool that has such a parameter and from one
     * whose only cap is the internal {@link #MAX_NODES} guard, and a remedy that exists in only one
     * of the two callers would be wrong wherever it is not offered.</p>
     *
     * @param sb the output buffer
     * @param what what was capped, as the sentence's subject
     * @param limit the cap that was reached
     */
    private static void appendCapNote(StringBuilder sb, String what, int limit)
    {
        sb.append("\n_(").append(what).append(" truncated: only the first ").append(limit) //$NON-NLS-1$ //$NON-NLS-2$
            .append(" are shown)_\n"); //$NON-NLS-1$
    }

    /**
     * Renders the {@code ## Items} section: the nested item outline (auto command bar first, then the
     * form root's items), capped at {@link #MAX_NODES} nodes with a truncation note. Falls back to
     * {@code _(no items)_} when the form carries neither items nor an auto command bar.
     */
    private static void renderItems(StringBuilder sb, EObject formModel, String language, int limit)
    {
        sb.append("## Items\n\n"); //$NON-NLS-1$
        List<EObject> items = getReferenceList(formModel, FEATURE_ITEMS);
        EObject autoCommandBar = getSingleReference(formModel, FEATURE_AUTO_COMMAND_BAR);
        if (items.isEmpty() && autoCommandBar == null)
        {
            sb.append("_(no items)_\n\n"); //$NON-NLS-1$
            return;
        }
        int[] budget = {limit};
        boolean[] truncated = {false};
        if (autoCommandBar != null)
        {
            appendItem(sb, autoCommandBar, 0, language, budget, truncated);
        }
        for (EObject item : items)
        {
            if (budget[0] <= 0)
            {
                // The item in hand is itself the proof that something was dropped, which is all
                // the flag claims - and with nothing left to spend, reading the rest of the list
                // could add neither a line to the outline nor a fact to the report. Each of these
                // items used to be pushed and popped to raise the same flag again.
                truncated[0] = true;
                break;
            }
            // A null element is an absent child, not a child that renders as nothing. See
            // getReferenceList for when the model's list can hold one at all.
            if (item != null)
            {
                appendItem(sb, item, 0, language, budget, truncated);
            }
        }
        // Gate the note on the explicit flag (set only when a node was actually dropped), NOT on an
        // exhausted budget: a form with EXACTLY MAX_NODES nodes drains the budget to 0 yet renders
        // every node, so inferring truncation from budget[0] <= 0 would falsely flag it.
        if (truncated[0])
        {
            sb.append("- _(item outline truncated: more than ") //$NON-NLS-1$
                .append(limit).append(" nodes)_\n"); //$NON-NLS-1$
        }
        sb.append('\n');
    }

    /**
     * Renders the {@code ## Attributes} table (Name / Synonym / Type / Main / SavedData), or
     * {@code _(no attributes)_} when the form has no attributes.
     */
    private static void renderAttributes(StringBuilder sb, EObject formModel, String language,
        int limit)
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
        int shown = Math.min(limit, attributes.size());
        for (int i = 0; i < shown; i++)
        {
            EObject attribute = attributes.get(i);
            if (attribute == null)
            {
                continue;
            }
            sb.append(MarkdownUtils.tableRow(nameOf(attribute), titleOf(attribute, language),
                valueTypeOf(attribute), Boolean.toString(booleanFeature(attribute, FEATURE_MAIN)),
                Boolean.toString(booleanFeature(attribute, FEATURE_SAVED_DATA))));
        }
        if (shown < attributes.size())
        {
            appendCapNote(sb, "attributes", limit); //$NON-NLS-1$
        }
        sb.append('\n');
    }

    /**
     * Renders the {@code ## Parameters} table (Name / Type / Key / Comment) - the form's
     * {@code FormParameter} members - and NOTHING when the form declares none.
     *
     * <p>Conditional like {@code ## Attribute columns} rather than always-present like
     * {@code ## Attributes}: most forms declare no parameters, and an empty section would be
     * paid for on every form read. No language argument - a parameter has no title, only a
     * comment (issue #396).</p>
     */
    private static void renderParameters(StringBuilder sb, EObject formModel, int limit)
    {
        List<EObject> parameters = getReferenceList(formModel, FEATURE_PARAMETERS);
        if (parameters.isEmpty())
        {
            return;
        }
        sb.append("## Parameters\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("Name", "Type", "Key", "Comment")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        int shown = Math.min(limit, parameters.size());
        for (int i = 0; i < shown; i++)
        {
            EObject parameter = parameters.get(i);
            if (parameter == null)
            {
                continue;
            }
            Object comment = getValue(parameter, FEATURE_COMMENT);
            sb.append(MarkdownUtils.tableRow(nameOf(parameter), valueTypeOf(parameter),
                Boolean.toString(booleanFeature(parameter, FEATURE_KEY_PARAMETER)),
                comment instanceof String ? (String)comment : "")); //$NON-NLS-1$
        }
        if (shown < parameters.size())
        {
            appendCapNote(sb, "parameters", limit); //$NON-NLS-1$
        }
        sb.append('\n');
    }

    /**
     * Renders the {@code ## Attribute columns} table for every COLLECTION attribute (ValueTable /
     * ValueTree) that has columns - the only place a column is visible, since the attributes table shows
     * just the owner's own type. The section is OMITTED entirely when no attribute has columns, so a form
     * without collections renders exactly as before. Issue #295.
     *
     * @param sb the output buffer
     * @param formModel the form content model
     * @param language the language code for the title column
     */
    private static void renderAttributeColumns(StringBuilder sb, EObject formModel, String language,
        int limit)
    {
        // ONE pass, into a buffer of its own. Whether the section exists at all is a claim about
        // every attribute, so nothing can shorten the walk in the case where no attribute carries
        // a column; what used to be paid for regardless was a LIST of the ones that do, built in
        // full before the row cap could admit its first row. The cap now ends the pass, and the
        // only rows held are the ones it admitted.
        StringBuilder rows = new StringBuilder();
        int shown = 0;
        boolean capped = false;
        for (EObject attribute : getReferenceList(formModel, FEATURE_ATTRIBUTES))
        {
            if (attribute == null)
            {
                continue;
            }
            for (EObject column : getReferenceList(attribute, FEATURE_COLUMNS))
            {
                if (column == null)
                {
                    continue;
                }
                if (shown >= limit)
                {
                    capped = true;
                    break;
                }
                rows.append(MarkdownUtils.tableRow(nameOf(attribute), nameOf(column),
                    titleOf(column, language), valueTypeOf(column)));
                shown++;
            }
            if (capped)
            {
                break;
            }
        }
        if (shown == 0)
        {
            // No attribute carries a column, and that is the only thing this can mean: render
            // clamps the limit to at least 1, so a form that has a column always produces a row
            // before the cap can bite.
            return;
        }
        sb.append("## Attribute columns\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("Attribute", "Name", "Synonym", "Type")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        sb.append(rows);
        if (capped)
        {
            appendCapNote(sb, "attribute columns", limit); //$NON-NLS-1$
        }
        sb.append('\n');
    }

    /**
     * Renders the {@code ## Commands} table (Name / Title / Action handler), or {@code _(no commands)_}
     * when the form has no commands.
     */
    private static void renderCommands(StringBuilder sb, EObject formModel, String language,
        int limit)
    {
        sb.append("## Commands\n\n"); //$NON-NLS-1$
        List<EObject> commands = getReferenceList(formModel, FEATURE_FORM_COMMANDS);
        if (commands.isEmpty())
        {
            sb.append("_(no commands)_\n\n"); //$NON-NLS-1$
            return;
        }
        sb.append(MarkdownUtils.tableHeader("Name", "Title", "Action handler")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int shown = Math.min(limit, commands.size());
        for (int i = 0; i < shown; i++)
        {
            EObject command = commands.get(i);
            if (command == null)
            {
                continue;
            }
            sb.append(MarkdownUtils.tableRow(nameOf(command), titleOf(command, language),
                actionHandlerOf(command)));
        }
        if (shown < commands.size())
        {
            appendCapNote(sb, "commands", limit); //$NON-NLS-1$
        }
        sb.append('\n');
    }

    /**
     * Renders the {@code ## Event handlers} table (Element / Event / Handler) for the BSL handler bound
     * to each event of the form root and every element, or {@code _(no event handlers)_} when none.
     *
     * <p>TWO independent bounds can narrow this section, and each is reported on its own. The
     * caller's {@code limit} declines rows and is announced by {@link #appendCapNote}; the
     * walk's {@link #MAX_NODES} ceiling stops the traversal and is announced by
     * {@link #HANDLERS_WALK_CUT_SHORT_NOTE}. Neither implies the other: a walk cut short usually
     * collects FEWER rows than the limit, so gating the report on the row cap - which is what this
     * section used to do - hid the walk's bound exactly in the cases where it mattered. The absence
     * sentence is therefore reserved for a walk that COMPLETED; a cut-short walk that found nothing
     * says {@link #HANDLERS_NONE_FOUND_WALK_CUT_SHORT} instead, which claims nothing about the
     * form.</p>
     *
     * <p><b>The row cap is applied by the WALK, not here.</b> It used to be applied here, over a
     * list the walk had filled without one, and {@link #MAX_NODES} did not stand in for it: that
     * budget counts ELEMENTS, and one element carries as many handler rows as it has bound events,
     * so a form with hundreds of thousands of them - on one element or spread over the 5000 the
     * budget allows - was materialised in full and then had all but {@code limit} of its rows
     * thrown away. {@link HandlerRows} declines them as they are found instead, which is the same
     * bound the walk already puts on what it QUEUES, applied to what it KEEPS.</p>
     */
    private static void renderEventHandlers(StringBuilder sb, EObject formModel, String language,
        int limit)
    {
        sb.append("## Event handlers\n\n"); //$NON-NLS-1$
        HandlerRows handlers = new HandlerRows(limit);
        boolean[] walkCutShort = {false};
        // collectHandlers walks the form root's 'items' AND its singular containments (the form-wide
        // auto command bar, context menus, tooltips), so the whole element tree is covered from here. It
        // shares the same MAX_NODES bound as the item-outline pass (its own fresh budget, since it is an
        // independent walk) so the Event-handlers section honours the same cap on a pathological form.
        collectHandlers(formModel, FORM_OWNER_LABEL, language, handlers, new int[] {MAX_NODES},
            walkCutShort);
        if (handlers.kept().isEmpty())
        {
            sb.append(walkCutShort[0] ? HANDLERS_NONE_FOUND_WALK_CUT_SHORT
                : "_(no event handlers)_").append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        sb.append(MarkdownUtils.tableHeader("Element", "Event", "Handler")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String[] row : handlers.kept())
        {
            sb.append(MarkdownUtils.tableRow(row[0], row[1], row[2]));
        }
        if (handlers.declined())
        {
            appendCapNote(sb, "event handlers", limit); //$NON-NLS-1$
        }
        // NOT an 'else': the two bounds are independent and can both have bitten on one form.
        if (walkCutShort[0])
        {
            sb.append('\n').append(HANDLERS_WALK_CUT_SHORT_NOTE).append('\n');
        }
        sb.append('\n');
    }

    /**
     * Appends {@code item} and its whole subtree as nested outline lines: beyond name / type / id /
     * title each line carries visibility (only when {@code false}), the bound dataPath, and per-kind
     * extras (group layout, field type+editMode, button command). The item NAME is the stable
     * programmatic id; the integer id and item type are shown alongside, and the title (by language
     * code) is appended when present. A shared {@code budget} caps the total node count for a
     * pathological form; when it is hit the shared {@code truncated} flag is raised so the caller can
     * record that nodes were actually dropped.
     *
     * <h2>The descent uses an explicit stack, for the reason {@link #collectHandlers} does</h2>
     * This walk used to re-enter itself once per element, and {@code budget} does not bound the DEPTH
     * it can reach - it bounds the number of elements VISITED. The bound that was offered instead was
     * the caller's {@code rowLimit}, which the comparison report keeps small; but the ordinary read
     * does not go through the comparison report. {@code get_metadata_details} calls the
     * three-argument {@link #render}, which passes {@link #MAX_NODES}, so a form nested that deep ran
     * the rendering thread out of stack - and a {@code StackOverflowError} is not a truncated
     * outline: it is an {@link Error}, it escapes the {@code catch (Exception)} between here and the
     * caller, and the request came back with no result at all. Widening a catch to {@code Error} is
     * not the answer; not needing one is.
     * <p>
     * <b>The ORDER and the INDENTATION are the recursion's own, and they are what the outline
     * prints.</b> An element's line comes first, then the whole subtree under each {@code items}
     * child in list order, then the subtree under each singular containment in the order
     * {@link #SINGULAR_ITEM_CONTAINMENTS} declares - plain depth-first pre-order, each child one
     * level deeper than its parent. A stack that pushed siblings forward would reverse every one of
     * those orderings, and a stack that recomputed depth from anything but its own entry would flatten
     * the outline; {@link #pushOutlineChildren} therefore pushes children in REVERSE and each entry
     * carries the depth its own line is indented to.
     * <p>
     * <b>{@code budget} and {@code truncated} keep their exact meaning</b>, including WHICH element
     * trips the cut: an element is charged when it is VISITED, and the stack visits elements in the
     * very order the recursion did. An element the budget cannot reach raises {@code truncated}
     * whether it is declined at the pop or left off the stack by {@link #pushOutlineChildren} -
     * see there for why those are the same elements.
     * <p>
     * <b>The budget bounds the PENDING work too, and that is the whole reason it is stated.</b> The
     * first de-recursion pushed every child before the outer loop could look at the budget again,
     * so a single element with far more children than {@link #MAX_NODES} allocated a
     * {@link PendingItem} for each of them even at {@code limit=1} - the recursion had returned at
     * the budget instead, so the conversion traded stack depth for heap. The stack now holds at
     * most {@code budget} entries (or the single root entry when the budget is already gone), which
     * is the bound the guard advertises.
     *
     * @param sb the output buffer
     * @param item the item to render, together with everything below it
     * @param depth the indentation level of {@code item}'s own line
     * @param language the title language CODE
     * @param budget the shared per-visited-element node budget
     * @param truncated raised when the budget actually DECLINED an element
     */
    private static void appendItem(StringBuilder sb, EObject item, int depth, String language,
        int[] budget, boolean[] truncated)
    {
        appendItem(sb, item, depth, language, budget, truncated, new ArrayDeque<>());
    }

    /**
     * The outline walk itself, with its stack handed in.
     * <p>
     * The stack is a parameter for ONE reason: the bound above is a statement about a structure
     * that is otherwise a local, and a test that could only read the rendered outline would be
     * pinning the symptom (an allocation) instead of the bound. Production creates its own on every
     * call - {@link #appendItem(StringBuilder, EObject, int, String, int[], boolean[])} - and
     * nothing outside this class can reach this overload.
     *
     * @param sb the output buffer
     * @param item the item to render, together with everything below it
     * @param depth the indentation level of {@code item}'s own line
     * @param language the title language CODE
     * @param budget the shared per-visited-element node budget
     * @param truncated raised when the budget actually DECLINED an element
     * @param pending the walk's stack, empty on entry and drained on exit
     */
    static void appendItem(StringBuilder sb, EObject item, int depth, String language,
        int[] budget, boolean[] truncated, Deque<PendingItem> pending)
    {
        pending.push(new PendingItem(item, depth));
        while (!pending.isEmpty())
        {
            PendingItem current = pending.pop();
            if (budget[0] <= 0)
            {
                truncated[0] = true;
                continue;
            }
            budget[0]--;
            appendItemLine(sb, current.item, current.depth, language);
            pushOutlineChildren(current.item, current.depth, language, pending, budget, truncated);
        }
    }

    /**
     * Writes the ONE outline line an element gets, indented to its own depth.
     *
     * @param sb the output buffer
     * @param item the item whose line this is
     * @param depth the indentation level
     * @param language the title language CODE
     */
    private static void appendItemLine(StringBuilder sb, EObject item, int depth, String language)
    {
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
    }

    /**
     * Puts as many of one element's children on the outline walk's stack as the budget can still
     * reach, in the order that reproduces the descent the recursion made: REVERSED, and the
     * singular containments BEFORE the {@code items} children, so that the first {@code items}
     * child is the next one popped and the singular ones come out last. Every child is pushed one
     * level deeper than its parent, which is the depth its own line will be indented to.
     *
     * <p>The singular item-bearing containments live OUTSIDE {@code items} (a table's command bar, an
     * item's context menu / extended tooltip). Their names occupy the form-wide namespace, so they
     * must be discoverable - but a designer-default child (no nested items, no title) is noise and is
     * skipped to keep the outline lean. That test is applied HERE, at push time, exactly where the
     * recursion applied it before calling itself: a child that fails it is never visited, so it is
     * never charged to the budget either - and it is not counted against the room the budget has,
     * for the same reason.</p>
     *
     * @param element the element whose children are to be walked
     * @param depth the indentation level of {@code element}'s own line
     * @param language the title language CODE, for the designer-default test
     * @param pending the walk's stack
     * @param budget the shared per-visited-element node budget, already charged for {@code element}
     * @param truncated raised when a child this element has is left off the stack
     */
    private static void pushOutlineChildren(EObject element, int depth, String language,
        Deque<PendingItem> pending, int[] budget, boolean[] truncated)
    {
        List<EObject> items = getReferenceList(element, FEATURE_ITEMS);
        List<EObject> singular = outlineSingularChildren(element, language);
        int reachable =
            makeRoomForReachableChildren(items.size() + singular.size(), pending, budget, truncated);
        int keptItems = Math.min(items.size(), reachable);
        int childDepth = depth + 1;
        for (int i = reachable - keptItems - 1; i >= 0; i--)
        {
            pending.push(new PendingItem(singular.get(i), childDepth));
        }
        for (int i = keptItems - 1; i >= 0; i--)
        {
            // Read one at a time, and only the ones there is room for: the list is the model's
            // own, so this is where an element far past the budget stops being paid for at all.
            EObject child = items.get(i);
            if (child != null)
            {
                pending.push(new PendingItem(child, childDepth));
            }
        }
    }

    /**
     * The singular item-bearing containments of one element that the outline shows, in the order
     * {@link #SINGULAR_ITEM_CONTAINMENTS} declares - which is the order they are VISITED in, after
     * the {@code items} children.
     *
     * @param element the element whose singular containments these are
     * @param language the title language CODE, for the designer-default test
     * @return the eligible children, at most {@link #SINGULAR_ITEM_CONTAINMENTS}{@code .length} of
     *         them
     */
    private static List<EObject> outlineSingularChildren(EObject element, String language)
    {
        List<EObject> eligible = new ArrayList<>(SINGULAR_ITEM_CONTAINMENTS.length);
        for (String containment : SINGULAR_ITEM_CONTAINMENTS)
        {
            EObject child = getSingleReference(element, containment);
            if (child != null && (!getReferenceList(child, FEATURE_ITEMS).isEmpty()
                || !titleOf(child, language).isEmpty()))
            {
                eligible.add(child);
            }
        }
        return eligible;
    }

    /**
     * How many of one element's children the budget can still reach, with the stack trimmed to
     * leave room for exactly those.
     *
     * <h2>Why this drops nothing the walk would have shown</h2>
     * Children are popped in visit order, so reaching the child at index {@code i} means popping
     * children {@code 0..i-1} first; each of those pops either spends a unit of budget or finds the
     * budget already gone, in which case every pop after it does too. A child at index {@code i}
     * can therefore be VISITED only when {@code i} is below the remaining budget, and the ones past
     * that point are exactly the ones the old walk pushed, popped, and declined. Leaving them off
     * the stack changes nothing but the heap - which is why {@code truncated} is raised here
     * instead: the pop that used to raise it no longer happens.
     * <p>
     * The same argument runs down the stack. Entries already on it are popped only AFTER the new
     * children and everything below them, so an entry lying more than {@code budget} pops from the
     * top cannot be visited either, and the surplus is removed from the BOTTOM. That is what keeps
     * the bound a bound: capping each push alone would still let one push per level accumulate.
     *
     * @param childCount how many children the element has, singular containments included
     * @param pending the walk's stack, holding the entries pushed by ancestors
     * @param budget the shared per-visited-element node budget, already charged for the element
     *            whose children these are
     * @param dropped raised when anything was left off the stack or removed from it
     * @return how many of the children to push, counted from the first in visit order
     */
    private static int makeRoomForReachableChildren(int childCount, Deque<?> pending, int[] budget,
        boolean[] dropped)
    {
        int reachable = Math.min(childCount, budget[0]);
        if (reachable < childCount)
        {
            dropped[0] = true;
        }
        int room = budget[0] - reachable;
        while (pending.size() > room)
        {
            pending.removeLast();
            dropped[0] = true;
        }
        return reachable;
    }

    /**
     * One item waiting on the outline walk's stack, with the indentation level its own line is
     * written at.
     *
     * <p>The depth is carried rather than derived at pop time because a stack holds elements from
     * several levels at once: the next pop is not one level below the previous one, it is one level
     * below whichever element pushed it.</p>
     *
     * <p>Package-private so a test can hand the walk a stack it can measure; see
     * {@link FormStructureReader#appendItem(StringBuilder, EObject, int, String, int[], boolean[], Deque)}.</p>
     */
    static final class PendingItem
    {
        final EObject item;

        final int depth;

        PendingItem(EObject item, int depth)
        {
            this.item = item;
            this.depth = depth;
        }
    }

    // ==================== EMF reflection helpers ====================

    /**
     * Reads a many-valued reference feature by name as a READ-ONLY VIEW of the model's own list -
     * nothing is copied and no element is touched until a caller asks for it. Returns an empty
     * list when the feature is absent or is not a many-valued reference, so callers never have to
     * null-check the list itself.
     *
     * <h2>Why a view and not the copy this used to return</h2>
     * Every caller here is capped - by a row limit, by the walk's node budget, or by nothing more
     * than {@code isEmpty()} - and the copy was taken BEFORE the cap could look at it. A form
     * whose element carries a hundred thousand handlers therefore cost a hundred thousand reads
     * and a second list of that size to answer {@code limit=1}: a cap that is paid for in full
     * bounds the OUTPUT and not the work, which is the one thing it is there for. The cost is
     * gone at the accessor rather than at one call site, because all of them have that shape.
     * <p>
     * What makes the view exact rather than hopeful is that neither list this reader is ever handed
     * has to be materialised to answer {@code size()} or {@code get(i)}, and the two are different
     * classes - which is why the property is stated and not the hierarchy. A form object in EDT is
     * a {@code BmObject}, and its many-valued features answer with {@code BmBasicEStoreEList}
     * (EMF's {@code EStoreEObjectImpl.BasicEStoreEList}), which delegates both the size and each
     * index straight to the BM store - so the copy was N store reads to answer a question about
     * one. The dynamic EMF objects the unit tests build answer with {@code BasicEList}, which is
     * array-backed. A RESOLVING list, either way, resolves the elements that are read and no
     * others, which the copy denied it.
     *
     * <h2>What the elements are, measured against the platform</h2>
     * The gate is {@link EReference} rather than "is many", and that is what makes the elements
     * {@link EObject}s without a filter. A stored reference list refuses both a wrong type and a
     * null as they go IN, in both of the families above - read out of the 2026.1 bytecode rather
     * than assumed:
     * <ul>
     * <li>{@code EcoreEList.validate} throws {@code ArrayStoreException} for an element outside
     * the reference's instance class, and {@code EObjectEList.canContainNull()} answers
     * {@code false}, which makes {@code AbstractEList.validate} refuse a null with "The 'no null'
     * constraint is violated";</li>
     * <li>{@code DelegatingEcoreEList.validate} throws the same {@code ArrayStoreException}, and
     * its {@code canContainNull()} answers {@code false} for every feature whose type is an
     * EClass - which a reference's always is.</li>
     * </ul>
     * So on the features this class reads, all of them stored containments, the filter the copy
     * performed could never drop anything, and dropping the filter drops no behaviour.
     * <p>
     * The guards at the call sites are for the case that reasoning does not reach: a DERIVED or
     * volatile feature answering with a list built by neither path -
     * {@code EcoreEList.UnmodifiableEList} wraps a raw {@code Object[]} and validates nothing, and
     * EMF's own {@code addUnique} bypasses {@code validate} - and this reader is reflective, so it
     * is handed whatever the feature answers. On such a list the guards keep a null out of the
     * accessors that would dereference it, and that is ALL they restore: its place is still
     * counted by {@code size()}, so a cap or a budget can be spent on it and a truncation reported
     * for a member that was never a child, and an element of the wrong type would surface as a
     * {@code ClassCastException} instead of being skipped. Both are strictly beyond what any
     * feature here can produce, and buying the guarantee back would mean walking every list on
     * every read - the cost this method exists to remove. A many-valued EAttribute (a feature map
     * included) answers empty, which is what the filter did for it.
     *
     * <h2>It is LIVE, and it is read-only</h2>
     * The list is the model's, so it must be read inside the same transaction as the object it
     * came from - the class's own rule already - and it must not be retained past it. Writing
     * through it would be writing to the model behind {@link FormElementWriter}'s back, so the
     * view refuses it.
     *
     * @param object the object to read, or {@code null}
     * @param featureName the feature's name
     * @return the view, never {@code null}, and never modifiable
     */
    @SuppressWarnings("unchecked")
    public static List<EObject> getReferenceList(EObject object, String featureName)
    {
        if (object == null)
        {
            return List.of();
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EReference) || !feature.isMany())
        {
            return List.of();
        }
        Object value = object.eGet(feature);
        if (!(value instanceof List<?>))
        {
            return List.of();
        }
        return Collections.unmodifiableList((List<EObject>)value);
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
            if (type != null)
            {
                names.add(typeItemName(type));
            }
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    /**
     * The displayable platform name of one {@code TypeItem}. A type just assigned through
     * {@code modify_metadata} is a PROXY created by {@code IEObjectProvider} whose raw EMF
     * {@code name} feature can still be empty; reading it directly would render a perfectly good
     * {@code Number} column as the bare EClass name {@code TypeItem} - defeating the point of showing
     * the type at all. {@code McoreUtil} is the proxy-aware accessor the rest of the code uses for
     * this, with the EClass name kept only as the last resort (issue #295 review).
     *
     * @param type one entry of a {@code TypeDescription}'s {@code types} list
     * @return the platform type name, never {@code null}
     */
    private static String typeItemName(EObject type)
    {
        if (type instanceof TypeItem)
        {
            String resolved = McoreUtil.getTypeName((TypeItem)type);
            if (resolved == null || resolved.isEmpty())
            {
                resolved = McoreUtil.getTypeNameRu((TypeItem)type);
            }
            if (resolved != null && !resolved.isEmpty())
            {
                return resolved;
            }
        }
        String name = stringValue(getValue(type, FEATURE_NAME));
        return name.isEmpty() ? type.eClass().getName() : name;
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

    /**
     * The value of a single-valued reference feature, or {@code null} when absent/unset. Public
     * alongside {@link #getReferenceList}: a caller walking a form has to reach the SINGULAR
     * containments too (the auto command bar, a context menu, an extended tooltip), and reading them
     * through a second hand-rolled accessor is how the two walks drift apart.
     *
     * @param object the owner to read from, may be {@code null}
     * @param featureName the single-valued reference name
     * @return the referenced object, or {@code null}
     */
    public static EObject getSingleReference(EObject object, String featureName)
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
     * Appends one {@code [owner, event, handler]} row per bound event handler of {@code root} to
     * {@code rows}, then covers the whole subtree below it - the child items and the singular item
     * containments alike. Each handler exposes its own {@code name} (the BSL procedure) and a
     * single {@code event} reference whose {@code name} (en) / {@code nameRu} (ru) is the event
     * name.
     *
     * <h2>The descent uses an explicit stack, and that is not a matter of taste</h2>
     * This walk used to re-enter itself once per element, and {@code budget} does not bound the
     * DEPTH it can reach - it bounds the number of elements VISITED. A form whose elements are
     * nested deeply enough therefore ran the walking thread out of stack before the budget could
     * decline anything, and a {@code StackOverflowError} is not a truncated table: it is an
     * {@code Error}, {@code GetComparisonNodeTool} catches {@code RuntimeException}, and the MCP
     * request ended with no result at all. Widening that catch is not the answer either - an
     * {@code Error} leaves the JVM in a state this bundle cannot reason about, and the comparison
     * feature deliberately rethrows one rather than turning it into a JSON error. Heap is a bound
     * the workbench can be given more of; the walking thread's stack is not. This is the same
     * conversion, for the same reason, as {@code CompareConfigurationsTool.collectTopNodes}.
     * <p>
     * <b>The ORDER is the recursion's own, and it is what the rendered table prints.</b> An
     * element's own handler rows come first, then the whole subtree under each {@code items} child
     * in list order, then the subtree under each singular containment in the order
     * {@link #SINGULAR_ITEM_CONTAINMENTS} declares - plain depth-first pre-order.
     * {@link #pushHandlerChildren} reproduces it by pushing children in REVERSE, so the first child
     * is the next one popped.
     * <p>
     * <b>{@code budget} and {@code cutShort} keep their exact meaning</b>, including WHICH element
     * trips the cut: an element is charged when it is VISITED, and the stack visits elements in the
     * very order the recursion did. An element the budget cannot reach raises {@code cutShort}
     * whether it is declined at the pop or left off the stack by {@link #pushHandlerChildren} -
     * see {@link #makeRoomForReachableChildren} for why those are the same elements.
     * <p>
     * <b>The budget bounds the PENDING work too.</b> The first de-recursion pushed every child
     * before the outer loop could look at the budget again, so one element with far more children
     * than {@link #MAX_NODES} allocated a {@link PendingElement} for each of them - the recursion
     * had returned at the budget instead, so the conversion traded stack depth for heap. The stack
     * now holds at most {@code budget} entries (or the single root entry when the budget is already
     * gone), which is the bound the guard advertises.
     *
     * <p>
     * <b>And the ROWS are bounded too, by {@link HandlerRows} rather than by this budget.</b> The
     * budget counts elements; the rows are what the elements carry, and one element carries a row
     * per bound event, so neither number bounds the other. The walk keeps going after the rows are
     * full - it has to, because whether the ELEMENT budget ran out is a different statement from
     * "there were more rows than shown", and only a walk that reaches the end of the budget can
     * make the first one honestly.
     *
     * @param root the form root, whose {@code handlers} list is read first
     * @param rootOwnerLabel the Element-column label for handlers directly on {@code root}
     * @param language the event-name language CODE
     * @param rows the accumulator receiving {@code {owner, event, handler}} rows, which keeps at
     *            most the caller's row cap of them
     * @param budget the shared per-visited-element node budget, capping the walk on a pathological form
     * @param cutShort raised when the budget actually DECLINED an element, so the caller can report
     *            a walk that stopped early instead of publishing a short list as a complete one.
     *            Set only on the budget branch: a {@code null} element drops nothing, and a form with
     *            exactly {@link #MAX_NODES} elements drains the budget to zero while every element
     *            is still visited - the same off-by-one the item outline is gated against
     */
    private static void collectHandlers(EObject root, String rootOwnerLabel, String language,
        HandlerRows rows, int[] budget, boolean[] cutShort)
    {
        collectHandlers(root, rootOwnerLabel, language, rows, budget, cutShort, new ArrayDeque<>());
    }

    /**
     * The handler walk itself, with its stack handed in.
     * <p>
     * The stack is a parameter for the reason it is one on the outline walk: the bound above is a
     * statement about a structure that is otherwise a local, and only a test that can measure that
     * structure pins the bound rather than a symptom of it. Production creates its own on every
     * call and nothing outside this class can reach this overload.
     *
     * @param root the form root, whose {@code handlers} list is read first
     * @param rootOwnerLabel the Element-column label for handlers directly on {@code root}
     * @param language the event-name language CODE
     * @param rows the accumulator receiving {@code {owner, event, handler}} rows, bounded by its own
     *            row cap
     * @param budget the shared per-visited-element node budget
     * @param cutShort raised when the budget actually DECLINED an element
     * @param pending the walk's stack, empty on entry and drained on exit
     */
    static void collectHandlers(EObject root, String rootOwnerLabel, String language,
        HandlerRows rows, int[] budget, boolean[] cutShort, Deque<PendingElement> pending)
    {
        if (root == null)
        {
            return;
        }
        pending.push(new PendingElement(root, rootOwnerLabel));
        while (!pending.isEmpty())
        {
            PendingElement current = pending.pop();
            if (budget[0] <= 0)
            {
                cutShort[0] = true;
                continue;
            }
            budget[0]--;
            for (EObject handler : getReferenceList(current.element, FEATURE_HANDLERS))
            {
                if (handler == null)
                {
                    // Never a row, and never counted as one: the cap decides between handlers
                    // this element HAS, and the model's list may legally hold a null.
                    continue;
                }
                // Asked BEFORE the row is built: past the cap the strings would be read off the
                // model only to be dropped, and the whole point of the cap is that nothing past it
                // is retained. Leaving the loop still RECORDS the decline - there is a handler in
                // hand, so "there were more rows than are shown" is established - and it leaves
                // only this element's remaining handlers; the WALK carries on, because the element
                // budget is a separate statement (see this method's own doc).
                if (rows.full())
                {
                    rows.decline();
                    break;
                }
                String procName = stringValue(getValue(handler, FEATURE_NAME));
                String eventName = eventNameOf(getSingleReference(handler, FEATURE_EVENT), language);
                rows.add(current.ownerLabel, eventName, procName);
            }
            pushHandlerChildren(current.element, pending, budget, cutShort);
        }
    }

    /**
     * The Event-handlers table's rows, capped at the caller's row limit as they are collected.
     *
     * <h2>Why the cap lives here and not at the render</h2>
     * The walk's {@link FormStructureReader#MAX_NODES} budget counts ELEMENTS. A row is a bound
     * event, and one element carries as many of them as it has events bound, so a form can hold
     * hundreds of thousands of rows within a budget of 5000 elements - on a single element or
     * spread across them. Collecting them all and then rendering the first {@code limit} kept every
     * one of those rows alive for the length of the walk, which is precisely the accumulation the
     * budget was put on the pending stack to prevent. So this keeps {@code cap} rows and counts
     * nothing else.
     *
     * <h2>{@link #declined()} is a different statement from the walk's cut-short flag</h2>
     * This one says "there were more rows than are shown"; the walk's says "the element budget ran
     * out, so elements were never looked at". A form can produce either, both or neither, and the
     * section prints them independently - which is why the walk carries on collecting elements
     * after this accumulator is full rather than stopping: stopping would leave the element budget
     * unspent and the second statement unanswerable.
     */
    static final class HandlerRows
    {
        private final List<String[]> rows = new ArrayList<>();

        private final int cap;

        private boolean declined;

        /**
         * @param cap the most rows to keep; read as at least 1, because an accumulator that keeps
         *            nothing would make "no handler was found" indistinguishable from "no handler
         *            was kept", and the section says something quite different about each
         */
        HandlerRows(int cap)
        {
            this.cap = Math.max(1, cap);
        }

        /** @return whether the cap is reached, so nothing more will be kept */
        boolean full()
        {
            return rows.size() >= cap;
        }

        /**
         * Keeps one row, or declines it and records that it did.
         *
         * @param owner the Element-column label
         * @param event the event name
         * @param handler the BSL procedure name
         */
        void add(String owner, String event, String handler)
        {
            if (full())
            {
                decline();
                return;
            }
            rows.add(new String[] {owner, event, handler});
        }

        /**
         * Records that a row was found and not kept, without building it.
         * <p>
         * The caller that leaves its loop at {@link #full()} still holds the handler that did not
         * fit, so the fact this flag reports - that there are more rows than are shown - is
         * established there; building the row it is about only to drop it is not.
         */
        void decline()
        {
            declined = true;
        }

        /** @return the rows kept, in collection order; never more than the cap */
        List<String[]> kept()
        {
            return rows;
        }

        /** @return whether a row was found and NOT kept, which is what the cap note reports */
        boolean declined()
        {
            return declined;
        }
    }

    /**
     * Puts as many of one element's children on the handler walk's stack as the budget can still
     * reach, in the order that reproduces the descent the recursion made: REVERSED, and the
     * singular containments BEFORE the {@code items} children, so that the first {@code items}
     * child is the next one popped and the singular ones come out last - which is the order the
     * recursion visited them in.
     *
     * @param element the element whose children are to be walked
     * @param pending the walk's stack
     * @param budget the shared per-visited-element node budget, already charged for {@code element}
     * @param cutShort raised when a child this element has is left off the stack
     */
    private static void pushHandlerChildren(EObject element, Deque<PendingElement> pending,
        int[] budget, boolean[] cutShort)
    {
        List<EObject> items = getReferenceList(element, FEATURE_ITEMS);
        List<EObject> singular = handlerSingularChildren(element);
        int reachable =
            makeRoomForReachableChildren(items.size() + singular.size(), pending, budget, cutShort);
        int keptItems = Math.min(items.size(), reachable);
        for (int i = reachable - keptItems - 1; i >= 0; i--)
        {
            EObject child = singular.get(i);
            pending.push(new PendingElement(child, nameOf(child)));
        }
        for (int i = keptItems - 1; i >= 0; i--)
        {
            EObject child = items.get(i);
            if (child != null)
            {
                pending.push(new PendingElement(child, nameOf(child)));
            }
        }
    }

    /**
     * The singular item-bearing containments of one element, in the order
     * {@link #SINGULAR_ITEM_CONTAINMENTS} declares - which is the order they are VISITED in, after
     * the {@code items} children. Unlike the outline walk, the handler walk keeps every one of
     * them: a designer-default command bar shows nothing in an outline but may still carry a bound
     * handler.
     *
     * @param element the element whose singular containments these are
     * @return the children present, at most {@link #SINGULAR_ITEM_CONTAINMENTS}{@code .length} of
     *         them
     */
    private static List<EObject> handlerSingularChildren(EObject element)
    {
        List<EObject> present = new ArrayList<>(SINGULAR_ITEM_CONTAINMENTS.length);
        for (String containment : SINGULAR_ITEM_CONTAINMENTS)
        {
            EObject child = getSingleReference(element, containment);
            if (child != null)
            {
                present.add(child);
            }
        }
        return present;
    }

    /**
     * One element waiting on the handler walk's stack, with the Element-column label its own
     * handler rows are written under.
     *
     * <p>The label is carried rather than recomputed at pop time because the form ROOT's label is
     * not its name - it is {@link #FORM_OWNER_LABEL} - and a walk that re-derived the label would
     * have to recognise the root by identity to keep that one row right.</p>
     *
     * <p>Package-private so a test can hand the walk a stack it can measure; see
     * {@link FormStructureReader#collectHandlers(EObject, String, String, List, int[], boolean[], Deque)}.</p>
     */
    static final class PendingElement
    {
        final EObject element;

        final String ownerLabel;

        PendingElement(EObject element, String ownerLabel)
        {
            this.element = element;
            this.ownerLabel = ownerLabel;
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
