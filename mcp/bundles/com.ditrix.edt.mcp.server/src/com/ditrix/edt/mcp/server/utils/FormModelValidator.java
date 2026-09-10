/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;

/**
 * Structural validation of a managed form's model - the connectivity a form needs to be openable
 * and editable, which neither BSL validation nor EDT's own markers answer (issue #473).
 *
 * <p>Read-only by construction: it takes the tx-bound form model and returns findings. Nothing here
 * repairs, exports or touches the model, so the same call is usable from a WRITE transaction before
 * commit as well as from a read-only audit.</p>
 *
 * <p><b>What it walks, and why that matters.</b> Everything structural goes through
 * {@link PersistedContents}, never {@code eAllContents()}: on an EDT 2026.2 form the root alone
 * answers three COMPUTED containments - the whole BSL context, the 22 inferred standard commands
 * and a global-command-source marker - none of them authored by anyone. Judged through
 * {@code eAllContents()} they would each fail an "unnamed" or "no id" check, and the report would be
 * a list of defects the user cannot fix and did not create.</p>
 */
public final class FormModelValidator
{
    /** A form is judged against several NAMESPACES; a name may repeat across them, not inside one. */
    private static final String FEATURE_ATTRIBUTES = "attributes"; //$NON-NLS-1$
    private static final String FEATURE_COLUMNS = "columns"; //$NON-NLS-1$
    private static final String FEATURE_FORM_COMMANDS = "formCommands"; //$NON-NLS-1$
    private static final String FEATURE_PARAMETERS = "parameters"; //$NON-NLS-1$
    private static final String FEATURE_HANDLERS = "handlers"; //$NON-NLS-1$
    private static final String FEATURE_NAME = "name"; //$NON-NLS-1$
    private static final String FEATURE_ID = "id"; //$NON-NLS-1$
    private static final String FEATURE_EVENT = "event"; //$NON-NLS-1$
    private static final String FEATURE_DATA_PATH = "dataPath"; //$NON-NLS-1$
    private static final String FEATURE_SEGMENTS = "segments"; //$NON-NLS-1$
    private static final String FEATURE_COMMAND_NAME = "commandName"; //$NON-NLS-1$
    private static final String FEATURE_AUTO_COMMAND_BAR = "autoCommandBar"; //$NON-NLS-1$
    private static final String FEATURE_MAIN = "main"; //$NON-NLS-1$
    private static final String FEATURE_EXT_INFO = "extInfo"; //$NON-NLS-1$

    private static final String ECLASS_BUTTON = "Button"; //$NON-NLS-1$
    private static final String ECLASS_FORM_FIELD = "FormField"; //$NON-NLS-1$
    private static final String ECLASS_TABLE = "Table"; //$NON-NLS-1$

    /** The id the platform WANTS on a form's root auto command bar; see the check that spares it. */
    private static final int AUTO_COMMAND_BAR_ID_SENTINEL = -1;

    public static final String SEVERITY_ERROR = "error"; //$NON-NLS-1$
    public static final String SEVERITY_WARNING = "warning"; //$NON-NLS-1$

    private FormModelValidator()
    {
    }

    /**
     * One structural defect, addressed the way every other form tool addresses a member so the
     * caller can paste the path straight into {@code modify_metadata} or {@code get_metadata_details}.
     */
    public static final class Finding
    {
        /** {@link #SEVERITY_ERROR} or {@link #SEVERITY_WARNING}. */
        public final String severity;
        /** A stable kebab-case code, safe to branch on. */
        public final String code;
        /** {@code Kind.Name}, {@code Attribute.A.Column.C}, or {@code (form)} for the root. */
        public final String path;
        /** What is wrong, in one sentence, naming what to do about it where that is knowable. */
        public final String message;

        Finding(String severity, String code, String path, String message)
        {
            this.severity = severity;
            this.code = code;
            this.path = path;
            this.message = message;
        }
    }

    /**
     * Validates the form's structure.
     *
     * @param formModel the editable content form, on the tx-bound model (never {@code null})
     * @return the findings, in walk order, empty when the form is structurally sound
     */
    public static List<Finding> validate(EObject formModel)
    {
        List<Finding> findings = new ArrayList<>();
        checkMainAttribute(formModel, findings);
        checkAutoCommandBar(formModel, findings);
        checkNamespaces(formModel, findings);
        checkItems(formModel, findings);
        checkHandlers(formModel, findings);
        return findings;
    }

    // --- the form root -------------------------------------------------------------------------

    /**
     * A form carries ONE main attribute or none - the platform reports two as an error
     * ({@code FormValidator.isMainFormAttributeOneOrNone}, code 5) rather than picking one. A root
     * ext-info left behind by a main attribute that is gone is reported too: the platform tolerates
     * it (nothing clears it on delete), but the form then advertises events it no longer backs.
     */
    private static void checkMainAttribute(EObject formModel, List<Finding> findings)
    {
        List<String> mains = new ArrayList<>();
        for (EObject attr : list(formModel, FEATURE_ATTRIBUTES))
        {
            if (Boolean.TRUE.equals(value(attr, FEATURE_MAIN)))
            {
                mains.add(nameOf(attr));
            }
        }
        if (mains.size() > 1)
        {
            findings.add(new Finding(SEVERITY_ERROR, "multiple-main-attributes", FORM_PATH, //$NON-NLS-1$
                "The form has " + mains.size() + " main attributes (" + String.join(", ", mains) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "); a form carries one or none. Clear 'main' on all but one.")); //$NON-NLS-1$
        }
        if (mains.isEmpty() && single(formModel, FEATURE_EXT_INFO) != null)
        {
            findings.add(new Finding(SEVERITY_WARNING, "orphan-form-ext-info", FORM_PATH, //$NON-NLS-1$
                "The form root carries an ext-info but no main attribute, so it advertises events " //$NON-NLS-1$
                    + "no attribute backs. Flag an attribute as main, or clear the node.")); //$NON-NLS-1$
        }
    }

    /**
     * The root auto command bar must be there. Its {@code id} is deliberately NOT judged: the
     * platform wants the {@code -1} sentinel on it, because a {@code 0} id serializes without an
     * {@code <id>} element and EDT then flags the form itself (issue #189).
     */
    private static void checkAutoCommandBar(EObject formModel, List<Finding> findings)
    {
        EStructuralFeature feature = formModel.eClass().getEStructuralFeature(FEATURE_AUTO_COMMAND_BAR);
        if (feature == null)
        {
            return;
        }
        EObject bar = single(formModel, FEATURE_AUTO_COMMAND_BAR);
        if (bar == null)
        {
            findings.add(new Finding(SEVERITY_ERROR, "missing-auto-command-bar", FORM_PATH, //$NON-NLS-1$
                "The form has no root auto command bar; EDT cannot lay the form out without it.")); //$NON-NLS-1$
            return;
        }
        Object id = value(bar, FEATURE_ID);
        if (id instanceof Integer && ((Integer)id).intValue() != AUTO_COMMAND_BAR_ID_SENTINEL
            && ((Integer)id).intValue() <= 0)
        {
            findings.add(new Finding(SEVERITY_ERROR, "invalid-auto-command-bar-id", FORM_PATH, //$NON-NLS-1$
                "The root auto command bar has id " + id + "; it must be the -1 sentinel or a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "positive id. A 0 id serializes without an <id> element and EDT refuses the form.")); //$NON-NLS-1$
        }
    }

    // --- namespaces ----------------------------------------------------------------------------

    /**
     * Names and ids repeat across namespaces legitimately - a command and an item may share a name -
     * so each list is judged on its own. Getting this wrong in both directions is a defect this
     * repository has already had once, in the rename path.
     */
    private static void checkNamespaces(EObject formModel, List<Finding> findings)
    {
        reportDuplicateNames(list(formModel, FEATURE_ATTRIBUTES), "Attribute", findings); //$NON-NLS-1$
        reportDuplicateNames(list(formModel, FEATURE_FORM_COMMANDS), "Command", findings); //$NON-NLS-1$
        reportDuplicateNames(list(formModel, FEATURE_PARAMETERS), "Parameter", findings); //$NON-NLS-1$
        reportDuplicateIds(list(formModel, FEATURE_ATTRIBUTES), "Attribute", findings); //$NON-NLS-1$
        for (EObject attribute : list(formModel, FEATURE_ATTRIBUTES))
        {
            List<EObject> columns = list(attribute, FEATURE_COLUMNS);
            if (columns.isEmpty())
            {
                continue;
            }
            String owner = "Attribute." + nameOf(attribute) + ".Column"; //$NON-NLS-1$ //$NON-NLS-2$
            reportDuplicateNames(columns, owner, findings);
            reportDuplicateIds(columns, owner, findings);
        }
        List<EObject> items = itemTree(formModel);
        reportDuplicateNames(items, "item", findings); //$NON-NLS-1$
        reportDuplicateIds(items, "item", findings); //$NON-NLS-1$
    }

    private static void reportDuplicateNames(List<EObject> members, String kindLabel,
        List<Finding> findings)
    {
        Map<String, Integer> seen = new HashMap<>();
        Set<String> reported = new LinkedHashSet<>();
        for (EObject member : members)
        {
            String name = nameOf(member);
            if (name.isEmpty())
            {
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            int count = seen.merge(key, Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + 1))
                .intValue();
            if (count > 1 && reported.add(key))
            {
                findings.add(new Finding(SEVERITY_ERROR, "duplicate-name", kindLabel + "." + name, //$NON-NLS-1$ //$NON-NLS-2$
                    "More than one " + kindLabel + " is named '" + name //$NON-NLS-1$ //$NON-NLS-2$
                        + "'; the name no longer addresses one member. Rename all but one.")); //$NON-NLS-1$
            }
        }
    }

    private static void reportDuplicateIds(List<EObject> members, String kindLabel,
        List<Finding> findings)
    {
        Map<Integer, String> seen = new HashMap<>();
        for (EObject member : members)
        {
            Object id = value(member, FEATURE_ID);
            if (!(id instanceof Integer) || ((Integer)id).intValue() <= 0)
            {
                continue;
            }
            String first = seen.putIfAbsent((Integer)id, nameOf(member));
            if (first != null)
            {
                findings.add(new Finding(SEVERITY_ERROR, "duplicate-id", //$NON-NLS-1$
                    kindLabel + "." + nameOf(member), //$NON-NLS-1$
                    "Id " + id + " is used by both '" + first + "' and '" + nameOf(member) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + "'; EDT addresses this " + kindLabel + " by id and refuses the form.")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    // --- items ---------------------------------------------------------------------------------

    /**
     * Per-item checks: the data path a field reads through, the command a button runs, and the
     * type-specific ext-info the element's own kind calls for.
     */
    private static void checkItems(EObject formModel, List<Finding> findings)
    {
        Set<String> dataRoots = dataPathRoots(formModel);
        for (EObject item : itemTree(formModel))
        {
            String path = pathOf(item);
            checkDataPath(item, dataRoots, path, findings);
            checkButtonCommand(item, path, findings);
            checkMemberExtInfo(item, path, findings);
        }
        for (EObject attribute : list(formModel, FEATURE_ATTRIBUTES))
        {
            checkMemberExtInfo(attribute, "Attribute." + nameOf(attribute), findings); //$NON-NLS-1$
        }
    }

    /**
     * A field or table reads its data through a path whose FIRST segment names a form attribute or
     * a form parameter. Later segments walk the attribute's own type, which this validator does not
     * resolve - a wrong answer there would be worse than no answer.
     */
    private static void checkDataPath(EObject item, Set<String> dataRoots, String path,
        List<Finding> findings)
    {
        String eClassName = item.eClass().getName();
        if (!ECLASS_FORM_FIELD.equals(eClassName) && !ECLASS_TABLE.equals(eClassName))
        {
            return;
        }
        EObject dataPath = single(item, FEATURE_DATA_PATH);
        if (dataPath == null)
        {
            findings.add(new Finding(SEVERITY_ERROR, "missing-data-path", path, //$NON-NLS-1$
                "This " + eClassName + " has no data path, so it displays nothing. Set 'dataPath'.")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        List<String> segments = strings(dataPath, FEATURE_SEGMENTS);
        if (segments.isEmpty())
        {
            findings.add(new Finding(SEVERITY_ERROR, "empty-data-path", path, //$NON-NLS-1$
                "This " + eClassName + " has an empty data path.")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        String root = segments.get(0);
        if (!root.isEmpty() && !dataRoots.contains(root.toLowerCase(Locale.ROOT)))
        {
            findings.add(new Finding(SEVERITY_ERROR, "unresolved-data-path", path, //$NON-NLS-1$
                "The data path '" + String.join(".", segments) + "' starts with '" + root //$NON-NLS-1$ //$NON-NLS-2$
                    + "', which is neither a form attribute nor a form parameter.")); //$NON-NLS-1$
        }
    }

    /**
     * A button runs a command, and the reference may point at a form command OR at a standard
     * command the platform infers - both are legitimate, so only a MISSING or unresolvable target
     * is a defect.
     */
    private static void checkButtonCommand(EObject item, String path, List<Finding> findings)
    {
        if (!ECLASS_BUTTON.equals(item.eClass().getName())
            || item.eClass().getEStructuralFeature(FEATURE_COMMAND_NAME) == null)
        {
            return;
        }
        EObject command = single(item, FEATURE_COMMAND_NAME);
        if (command == null)
        {
            findings.add(new Finding(SEVERITY_ERROR, "missing-command-reference", path, //$NON-NLS-1$
                "This button runs no command. Point it at a form command or a standard command.")); //$NON-NLS-1$
            return;
        }
        if (isUnresolvable(command))
        {
            findings.add(new Finding(SEVERITY_ERROR, "unresolved-command-reference", path, //$NON-NLS-1$
                "This button points at a command that is not in the model any more.")); //$NON-NLS-1$
        }
    }

    /**
     * An element's ext-info is decided by its own kind, and the platform creates it together with
     * the element. A missing one means the element was built by something that did not know the
     * rule; a MISMATCHED one means the element's type changed and the node did not follow.
     */
    private static void checkMemberExtInfo(EObject element, String path, List<Finding> findings)
    {
        if (element.eClass().getEStructuralFeature(FEATURE_EXT_INFO) == null)
        {
            return;
        }
        EClass expected = FormElementWriter.resolveExtInfoEClass(element);
        if (expected == null)
        {
            return;
        }
        String expectedName = expected.getName();
        EObject actual = FormElementWriter.extInfoInstance(element);
        if (actual == null)
        {
            findings.add(new Finding(SEVERITY_ERROR, "missing-ext-info", path, //$NON-NLS-1$
                "This element has no '" + expectedName + "', so its type-specific properties and " //$NON-NLS-1$ //$NON-NLS-2$
                    + "events are unavailable.")); //$NON-NLS-1$
            return;
        }
        if (!expectedName.equals(actual.eClass().getName()))
        {
            findings.add(new Finding(SEVERITY_ERROR, "stale-ext-info", path, //$NON-NLS-1$
                "This element carries a '" + actual.eClass().getName() + "' but its kind calls for a '" //$NON-NLS-1$ //$NON-NLS-2$
                    + expectedName + "'; the type changed and the node did not follow.")); //$NON-NLS-1$
        }
    }

    // --- handlers ------------------------------------------------------------------------------

    /**
     * A binding names an event and a BSL procedure. Either half missing leaves a handler that the
     * platform lists but cannot call.
     */
    private static void checkHandlers(EObject formModel, List<Finding> findings)
    {
        checkHandlerList(formModel, FORM_PATH, findings);
        EObject rootExtInfo = single(formModel, FEATURE_EXT_INFO);
        if (rootExtInfo != null)
        {
            checkHandlerList(rootExtInfo, FORM_PATH, findings);
        }
        for (EObject item : itemTree(formModel))
        {
            String path = pathOf(item);
            checkHandlerList(item, path, findings);
            EObject extInfo = single(item, FEATURE_EXT_INFO);
            if (extInfo != null)
            {
                checkHandlerList(extInfo, path, findings);
            }
        }
    }

    private static void checkHandlerList(EObject container, String path, List<Finding> findings)
    {
        for (EObject handler : list(container, FEATURE_HANDLERS))
        {
            String procedure = nameOf(handler);
            EObject event = single(handler, FEATURE_EVENT);
            String eventName = event == null ? "" : nameOf(event); //$NON-NLS-1$
            if (procedure.isEmpty())
            {
                findings.add(new Finding(SEVERITY_ERROR, "empty-handler-name", path, //$NON-NLS-1$
                    "The handler for '" + (eventName.isEmpty() ? "(unknown event)" : eventName) //$NON-NLS-1$ //$NON-NLS-2$
                        + "' names no BSL procedure.")); //$NON-NLS-1$
            }
            if (event == null || isUnresolvable(event))
            {
                findings.add(new Finding(SEVERITY_ERROR, "unresolved-event-reference", path, //$NON-NLS-1$
                    "The handler '" + (procedure.isEmpty() ? "(unnamed)" : procedure) //$NON-NLS-1$ //$NON-NLS-2$
                        + "' is bound to no event this element publishes.")); //$NON-NLS-1$
            }
        }
    }

    // --- shared reading ------------------------------------------------------------------------

    /** The label the handler table uses for a form-level owner; kept identical on purpose. */
    private static final String FORM_PATH = "(form)"; //$NON-NLS-1$

    /**
     * Every PERSISTED item of the form, in walk order. Persisted is the point: the computed
     * containments of a form root are not authored by anyone and must not be judged.
     */
    private static List<EObject> itemTree(EObject formModel)
    {
        List<EObject> items = new ArrayList<>();
        for (EObject descendant : PersistedContents.descendants(formModel))
        {
            FormElementWriter.Kind kind = FormElementWriter.addressableKind(descendant);
            if (kind != null && ITEM_KINDS.contains(kind))
            {
                items.add(descendant);
            }
        }
        return items;
    }

    /**
     * The kinds that live in the ITEM tree. {@code addressableKind} also answers for attributes,
     * columns, commands and parameters - they are members, not items, and each has its own
     * namespace, so folding them in here would report a command and an item that share a name as a
     * duplicate.
     */
    private static final Set<FormElementWriter.Kind> ITEM_KINDS = Set.of(FormElementWriter.Kind.BUTTON,
        FormElementWriter.Kind.FIELD, FormElementWriter.Kind.TABLE, FormElementWriter.Kind.DECORATION,
        FormElementWriter.Kind.GROUP);

    /** The names a data path may START with: the form's own attributes and parameters. */
    private static Set<String> dataPathRoots(EObject formModel)
    {
        Set<String> roots = new LinkedHashSet<>();
        for (EObject attribute : list(formModel, FEATURE_ATTRIBUTES))
        {
            roots.add(nameOf(attribute).toLowerCase(Locale.ROOT));
        }
        for (EObject parameter : list(formModel, FEATURE_PARAMETERS))
        {
            roots.add(nameOf(parameter).toLowerCase(Locale.ROOT));
        }
        return roots;
    }

    /** {@code Kind.Name}, the address every other form tool accepts. */
    private static String pathOf(EObject element)
    {
        FormElementWriter.Kind kind = FormElementWriter.addressableKind(element);
        String name = nameOf(element);
        if (kind == null)
        {
            return name.isEmpty() ? element.eClass().getName() : name;
        }
        List<String> tokens = FormElementWriter.tokensForKind(kind);
        // The tokens are matched case-insensitively and stored lower-case; an ADDRESS is written
        // capitalized, and this path is meant to be pasted into another call as it stands.
        String token = tokens.isEmpty() ? kind.name() : capitalize(tokens.get(0));
        return token + "." + (name.isEmpty() ? "(unnamed)" : name); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String capitalize(String token)
    {
        return token.isEmpty() ? token
            : Character.toUpperCase(token.charAt(0)) + token.substring(1);
    }

    /**
     * Whether a reference target is gone: a proxy the model cannot resolve, or an object that no
     * longer belongs to a resource at all.
     */
    private static boolean isUnresolvable(EObject target)
    {
        if (!(target instanceof InternalEObject))
        {
            return false;
        }
        return target.eIsProxy();
    }

    private static String nameOf(EObject object)
    {
        Object name = value(object, FEATURE_NAME);
        return name instanceof String ? (String)name : ""; //$NON-NLS-1$
    }

    private static Object value(EObject object, String featureName)
    {
        if (object == null)
        {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        return feature == null ? null : object.eGet(feature);
    }

    @SuppressWarnings("unchecked")
    private static List<EObject> list(EObject object, String featureName)
    {
        Object value = value(object, featureName);
        return value instanceof List ? (List<EObject>)value : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(EObject object, String featureName)
    {
        Object value = value(object, featureName);
        return value instanceof List ? (List<String>)value : List.of();
    }

    private static EObject single(EObject object, String featureName)
    {
        Object value = value(object, featureName);
        return value instanceof EObject ? (EObject)value : null;
    }
}
