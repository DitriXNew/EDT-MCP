/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

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
 *
 * <p><b>What the rules were measured against.</b> The name and id rules exempt {@code Addition}
 * (a table's search string, view status and search control). Across a full real configuration -
 * 323 668 persisted items - those are the ONLY items carrying no name (176) and the only ones whose
 * id is absent (209). Negative ids are normal for the singular nested items (an extended tooltip's
 * id is negative), so only a MISSING id is judged.</p>
 */
public final class FormModelValidator
{
    private static final String FEATURE_ATTRIBUTES = "attributes"; //$NON-NLS-1$
    private static final String FEATURE_COLUMNS = "columns"; //$NON-NLS-1$
    private static final String FEATURE_FORM_COMMANDS = "formCommands"; //$NON-NLS-1$
    private static final String FEATURE_PARAMETERS = "parameters"; //$NON-NLS-1$
    private static final String FEATURE_HANDLERS = "handlers"; //$NON-NLS-1$
    private static final String FEATURE_NAME = "name"; //$NON-NLS-1$
    private static final String FEATURE_NAME_RU = "nameRu"; //$NON-NLS-1$
    private static final String FEATURE_ID = "id"; //$NON-NLS-1$
    private static final String FEATURE_EVENT = "event"; //$NON-NLS-1$
    private static final String FEATURE_CALL_TYPE = "callType"; //$NON-NLS-1$
    private static final String FEATURE_DATA_PATH = "dataPath"; //$NON-NLS-1$
    private static final String FEATURE_SEGMENTS = "segments"; //$NON-NLS-1$
    private static final String FEATURE_COMMAND_NAME = "commandName"; //$NON-NLS-1$
    private static final String FEATURE_AUTO_COMMAND_BAR = "autoCommandBar"; //$NON-NLS-1$
    private static final String FEATURE_MAIN = "main"; //$NON-NLS-1$
    private static final String FEATURE_EXT_INFO = "extInfo"; //$NON-NLS-1$
    private static final String FEATURE_ACTION = "action"; //$NON-NLS-1$
    private static final String FEATURE_HANDLER = "handler"; //$NON-NLS-1$

    private static final String ECLASS_FORM_ITEM = "FormItem"; //$NON-NLS-1$
    private static final String ECLASS_ADDITION = "Addition"; //$NON-NLS-1$
    private static final String ECLASS_EXTENDED_TOOLTIP = "ExtendedTooltip"; //$NON-NLS-1$
    private static final String FEATURE_TYPE = "type"; //$NON-NLS-1$
    private static final String TYPE_LITERAL_LABEL = "Label"; //$NON-NLS-1$
    private static final String CALL_TYPE_CHANGE_AND_VALIDATE = "ChangeAndValidate"; //$NON-NLS-1$
    private static final String ECLASS_BUTTON = "Button"; //$NON-NLS-1$
    private static final String ECLASS_FORM_COMMAND = "FormCommand"; //$NON-NLS-1$
    private static final String ECLASS_FORM_FIELD = "FormField"; //$NON-NLS-1$
    private static final String ECLASS_TABLE = "Table"; //$NON-NLS-1$
    private static final String ECLASS_EVENT_HANDLER_EXTENSION = "EventHandlerExtension"; //$NON-NLS-1$
    private static final String ECLASS_ADDITIONAL_COLUMNS = "FormAttributeAdditionalColumns"; //$NON-NLS-1$

    /** The id the platform WANTS on a form's root auto command bar; see the check that spares it. */
    private static final int AUTO_COMMAND_BAR_ID_SENTINEL = -1;

    /** The label the handler table uses for a form-level owner; kept identical on purpose. */
    private static final String FORM_PATH = "(form)"; //$NON-NLS-1$

    public static final String SEVERITY_ERROR = "error"; //$NON-NLS-1$
    public static final String SEVERITY_WARNING = "warning"; //$NON-NLS-1$

    // The finding codes, declared once - see CODES below for the contract they form.
    public static final String CODE_MULTIPLE_MAIN_ATTRIBUTES = "multiple-main-attributes"; //$NON-NLS-1$
    public static final String CODE_ORPHAN_FORM_EXT_INFO = "orphan-form-ext-info"; //$NON-NLS-1$
    public static final String CODE_MISSING_AUTO_COMMAND_BAR = "missing-auto-command-bar"; //$NON-NLS-1$
    public static final String CODE_INVALID_AUTO_COMMAND_BAR_ID = "invalid-auto-command-bar-id"; //$NON-NLS-1$
    public static final String CODE_UNNAMED_MEMBER = "unnamed-member"; //$NON-NLS-1$
    public static final String CODE_DUPLICATE_NAME = "duplicate-name"; //$NON-NLS-1$
    public static final String CODE_MISSING_ID = "missing-id"; //$NON-NLS-1$
    public static final String CODE_DUPLICATE_ID = "duplicate-id"; //$NON-NLS-1$
    public static final String CODE_MISSING_DATA_PATH = "missing-data-path"; //$NON-NLS-1$
    public static final String CODE_EMPTY_DATA_PATH = "empty-data-path"; //$NON-NLS-1$
    public static final String CODE_UNRESOLVED_DATA_PATH = "unresolved-data-path"; //$NON-NLS-1$
    public static final String CODE_MISSING_COMMAND_REFERENCE = "missing-command-reference"; //$NON-NLS-1$
    public static final String CODE_UNRESOLVED_COMMAND_REFERENCE = "unresolved-command-reference"; //$NON-NLS-1$
    public static final String CODE_INVALID_EXTENDED_TOOLTIP_TYPE = "invalid-extended-tooltip-type"; //$NON-NLS-1$
    public static final String CODE_MISSING_EXT_INFO = "missing-ext-info"; //$NON-NLS-1$
    public static final String CODE_STALE_EXT_INFO = "stale-ext-info"; //$NON-NLS-1$
    public static final String CODE_EMPTY_COMMAND_ACTION = "empty-command-action"; //$NON-NLS-1$
    public static final String CODE_EXTENSION_HANDLER_WITHOUT_CALL_TYPE =
        "extension-handler-without-call-type"; //$NON-NLS-1$
    public static final String CODE_INVALID_EXTENSION_CALL_TYPE = "invalid-extension-call-type"; //$NON-NLS-1$
    public static final String CODE_DUPLICATE_HANDLER_BINDING = "duplicate-handler-binding"; //$NON-NLS-1$
    public static final String CODE_EMPTY_HANDLER_NAME = "empty-handler-name"; //$NON-NLS-1$
    public static final String CODE_UNRESOLVED_EVENT_REFERENCE = "unresolved-event-reference"; //$NON-NLS-1$
    public static final String CODE_FOREIGN_EVENT_REFERENCE = "foreign-event-reference"; //$NON-NLS-1$

    /**
     * Every finding code this engine can emit. A code is documented as STABLE for a caller to
     * branch on, so the set is declared once here and pinned against the tool guide by
     * {@code ValidateFormModelToolTest}: a new code with no guide row now fails the build,
     * which is what three rounds of "the guide does not list it" needed and did not have.
     */
    public static final List<String> CODES = List.of(
        CODE_MULTIPLE_MAIN_ATTRIBUTES, CODE_ORPHAN_FORM_EXT_INFO, CODE_MISSING_AUTO_COMMAND_BAR,
        CODE_INVALID_AUTO_COMMAND_BAR_ID, CODE_UNNAMED_MEMBER, CODE_DUPLICATE_NAME,
        CODE_MISSING_ID, CODE_DUPLICATE_ID, CODE_MISSING_DATA_PATH,
        CODE_EMPTY_DATA_PATH, CODE_UNRESOLVED_DATA_PATH, CODE_MISSING_COMMAND_REFERENCE,
        CODE_UNRESOLVED_COMMAND_REFERENCE, CODE_INVALID_EXTENDED_TOOLTIP_TYPE, CODE_MISSING_EXT_INFO,
        CODE_STALE_EXT_INFO, CODE_EMPTY_COMMAND_ACTION, CODE_EXTENSION_HANDLER_WITHOUT_CALL_TYPE,
        CODE_INVALID_EXTENSION_CALL_TYPE, CODE_DUPLICATE_HANDLER_BINDING, CODE_EMPTY_HANDLER_NAME,
        CODE_UNRESOLVED_EVENT_REFERENCE, CODE_FOREIGN_EVENT_REFERENCE);

    private FormModelValidator()
    {
    }

    @FunctionalInterface
    public interface EventPublication
    {
        /** The names of the events {@code container} publishes; EMPTY means "cannot tell". */
        List<String> publishedBy(EObject container);
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
        /**
         * WHERE the defect is, in the address vocabulary the other form tools use -
         * {@code Field.Description}, {@code Attribute.A.Column.C}, {@code (form)} for the root.
         *
         * <p>Most of these paste straight into {@code get_metadata_details} or
         * {@code modify_metadata}. Three cannot, by construction: an {@code Addition} and an
         * additional column have no address in that vocabulary at all, and a DUPLICATE name is
         * ambiguous - which is the defect being reported. There the path is a location, not an
         * address.</p>
         */
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
        return validate(formModel, container -> List.of());
    }

    /**
     * Validates the form's structure with a source for the events each handler owner publishes.
     *
     * @param formModel the editable content form, on the tx-bound model (never {@code null})
     * @param publication the platform event publication, empty when it cannot be determined
     * @return the findings, in walk order, empty when the form is structurally sound
     */
    public static List<Finding> validate(EObject formModel, EventPublication publication)
    {
        List<Finding> findings = new ArrayList<>();
        List<EObject> items = itemTree(formModel);
        checkMainAttribute(formModel, findings);
        checkAutoCommandBar(formModel, findings);
        checkNamespaces(formModel, items, findings);
        checkAttributeExtInfos(formModel, findings);
        checkItems(formModel, items, findings);
        checkHandlers(formModel, items, publication, findings);
        checkCommandActions(formModel, findings);
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
            findings.add(new Finding(SEVERITY_ERROR, CODE_MULTIPLE_MAIN_ATTRIBUTES, FORM_PATH,
                "The form has " + mains.size() + " main attributes (" + String.join(", ", mains) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "); a form carries one or none. Clear 'main' on all but one.")); //$NON-NLS-1$
        }
        if (mains.isEmpty() && single(formModel, FEATURE_EXT_INFO) != null)
        {
            findings.add(new Finding(SEVERITY_WARNING, CODE_ORPHAN_FORM_EXT_INFO, FORM_PATH,
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
        if (formModel.eClass().getEStructuralFeature(FEATURE_AUTO_COMMAND_BAR) == null)
        {
            return;
        }
        EObject bar = single(formModel, FEATURE_AUTO_COMMAND_BAR);
        if (bar == null)
        {
            findings.add(new Finding(SEVERITY_ERROR, CODE_MISSING_AUTO_COMMAND_BAR, FORM_PATH,
                "The form has no root auto command bar; EDT cannot lay the form out without it.")); //$NON-NLS-1$
            return;
        }
        Object id = value(bar, FEATURE_ID);
        if (id instanceof Integer && ((Integer)id).intValue() != AUTO_COMMAND_BAR_ID_SENTINEL
            && ((Integer)id).intValue() <= 0)
        {
            findings.add(new Finding(SEVERITY_ERROR, CODE_INVALID_AUTO_COMMAND_BAR_ID, FORM_PATH,
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
    private static void checkNamespaces(EObject formModel, List<EObject> items, List<Finding> findings)
    {
        judgeNamespace(list(formModel, FEATURE_ATTRIBUTES), "Attribute", findings); //$NON-NLS-1$
        judgeNamespace(list(formModel, FEATURE_FORM_COMMANDS), "Command", findings); //$NON-NLS-1$
        judgeNamespace(list(formModel, FEATURE_PARAMETERS), "Parameter", findings); //$NON-NLS-1$
        judgeNamespace(items, "item", true, findings); //$NON-NLS-1$
        for (EObject attribute : list(formModel, FEATURE_ATTRIBUTES))
        {
            // Each columns list is its OWN id scope - the platform's normalization repairs
            // duplicates per list - and an attribute's additional-columns groups carry lists too.
            judgeColumns(attribute, "Attribute." + nameOf(attribute) + ".Column", findings); //$NON-NLS-1$ //$NON-NLS-2$
            for (EObject group : additionalColumnGroups(attribute))
            {
                // NOT the Column address: resolveFormMember reaches only the attribute's own
                // columns, so reusing it would point at an unrelated same-named direct column.
                judgeColumns(group, "Attribute." + nameOf(attribute) + ".AdditionalColumn", //$NON-NLS-1$ //$NON-NLS-2$
                    findings);
            }
        }
    }

    private static void judgeColumns(EObject owner, String label, List<Finding> findings)
    {
        List<EObject> columns = list(owner, FEATURE_COLUMNS);
        if (!columns.isEmpty())
        {
            judgeNamespace(columns, label, findings);
        }
    }

    /** Names and ids of ONE list, plus the members that carry neither. */
    private static void judgeNamespace(List<EObject> members, String kindLabel, List<Finding> findings)
    {
        judgeNamespace(members, kindLabel, false, findings);
    }

    /** @param itemPaths whether each finding is addressed by the member's own kind token */
    private static void judgeNamespace(List<EObject> members, String kindLabel, boolean itemPaths,
        List<Finding> findings)
    {
        Map<String, Integer> names = new HashMap<>();
        Set<String> reported = new LinkedHashSet<>();
        Map<Integer, String> ids = new HashMap<>();
        for (EObject member : members)
        {
            String name = nameOf(member);
            // The label names the NAMESPACE; the path has to be an address a caller can paste
            // back, and only the element itself knows its kind token.
            String path = itemPaths ? pathOf(member)
                : kindLabel + "." + (name.isEmpty() ? "(unnamed)" : name); //$NON-NLS-1$ //$NON-NLS-2$
            if (name.isEmpty())
            {
                if (!isAddition(member))
                {
                    findings.add(new Finding(SEVERITY_ERROR, CODE_UNNAMED_MEMBER, path,
                        "This " + member.eClass().getName() + " has no name, so no tool can address " //$NON-NLS-1$ //$NON-NLS-2$
                            + "it and a second unnamed one is indistinguishable from it.")); //$NON-NLS-1$
                }
            }
            else
            {
                String key = name.toLowerCase(Locale.ROOT);
                int count = names.merge(key, Integer.valueOf(1),
                    (a, b) -> Integer.valueOf(a.intValue() + 1)).intValue();
                if (count > 1 && reported.add(key))
                {
                    findings.add(new Finding(SEVERITY_ERROR, CODE_DUPLICATE_NAME, path,
                        "More than one " + kindLabel + " is named '" + name //$NON-NLS-1$ //$NON-NLS-2$
                            + "'; the name no longer addresses one member. Rename all but one.")); //$NON-NLS-1$
                }
            }
            judgeId(member, kindLabel, path, ids, findings);
        }
    }

    /**
     * A member is addressed by id, so it needs one. Only a MISSING id (the {@code 0} the model
     * answers when the {@code <id>} element is absent) is judged: negative ids are what the platform
     * writes for the singular nested items, and an {@code Addition} legitimately carries none.
     */
    private static void judgeId(EObject member, String kindLabel, String path, Map<Integer, String> ids,
        List<Finding> findings)
    {
        Object raw = value(member, FEATURE_ID);
        if (!(raw instanceof Integer))
        {
            return;
        }
        int id = ((Integer)raw).intValue();
        if (id == 0)
        {
            if (!isAddition(member))
            {
                findings.add(new Finding(SEVERITY_ERROR, CODE_MISSING_ID, path,
                    "This " + kindLabel + " has no id; it serializes without an <id> element and EDT " //$NON-NLS-1$ //$NON-NLS-2$
                        + "then refuses the form.")); //$NON-NLS-1$
            }
            return;
        }
        String first = ids.putIfAbsent(Integer.valueOf(id), nameOf(member));
        if (first != null)
        {
            findings.add(new Finding(SEVERITY_ERROR, CODE_DUPLICATE_ID, path,
                "Id " + id + " is used by both '" + first + "' and '" + nameOf(member) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "'; EDT addresses this " + kindLabel + " by id and refuses the form.")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Checks the ext-info whose classifier is decided by each form attribute's value type. */
    private static void checkAttributeExtInfos(EObject formModel, List<Finding> findings)
    {
        for (EObject attribute : list(formModel, FEATURE_ATTRIBUTES))
        {
            String expected = FormElementWriter.attributeExtInfoClassifierNameFor(attribute);
            EObject actual = FormElementWriter.extInfoInstance(attribute);
            String path = pathOf(attribute);
            if (expected == null)
            {
                // A SINGLE type with no pairing forbids an ext-info outright - the writer clears
                // one there. A type that is not single cannot be read, and a finding would guess.
                if (actual != null && FormElementWriter.singleValueTypeCategory(attribute) != null)
                {
                    findings.add(new Finding(SEVERITY_ERROR, CODE_STALE_EXT_INFO, path,
                        "This attribute carries a '" + actual.eClass().getName() //$NON-NLS-1$
                            + "' although its value type calls for none.")); //$NON-NLS-1$
                }
                continue;
            }
            if (actual == null)
            {
                findings.add(new Finding(SEVERITY_ERROR, CODE_MISSING_EXT_INFO, path,
                    "This attribute has no '" + expected //$NON-NLS-1$
                        + "', which its value type requires.")); //$NON-NLS-1$
            }
            else if (!expected.equals(actual.eClass().getName()))
            {
                findings.add(new Finding(SEVERITY_ERROR, CODE_STALE_EXT_INFO, path,
                    "This attribute carries a '" + actual.eClass().getName() //$NON-NLS-1$
                        + "' but its value type calls for a '" + expected //$NON-NLS-1$
                        + "'.")); //$NON-NLS-1$
            }
        }
    }

    // --- items ---------------------------------------------------------------------------------

    /**
     * Per-item checks: the data path a field reads through, the command a button runs, and the
     * type-specific ext-info the element's own kind calls for.
     */
    private static void checkItems(EObject formModel, List<EObject> items, List<Finding> findings)
    {
        Set<String> dataRoots = dataPathRoots(formModel);
        for (EObject item : items)
        {
            String path = pathOf(item);
            checkDataPath(item, dataRoots, path, findings);
            checkButtonCommand(formModel, item, path, findings);
            checkItemExtInfo(item, path, findings);
        }
    }

    /**
     * A field or table reads its data through a path whose FIRST segment names a form ATTRIBUTE.
     * Later segments walk that attribute's own type, which this validator does not resolve - a
     * wrong answer there would be worse than no answer.
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
            findings.add(new Finding(SEVERITY_ERROR, CODE_MISSING_DATA_PATH, path,
                "This " + eClassName + " has no data path, so it displays nothing. Set 'dataPath'.")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        List<String> segments = strings(dataPath, FEATURE_SEGMENTS);
        String root = segments.isEmpty() ? null : segments.get(0);
        if (root == null || root.isEmpty())
        {
            findings.add(new Finding(SEVERITY_ERROR, CODE_EMPTY_DATA_PATH, path,
                "This " + eClassName + " has an empty data path, so it displays nothing.")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (!dataRoots.contains(root.toLowerCase(Locale.ROOT)))
        {
            findings.add(new Finding(SEVERITY_ERROR, CODE_UNRESOLVED_DATA_PATH, path,
                "The data path '" + String.join(".", segments) + "' starts with '" + root //$NON-NLS-1$ //$NON-NLS-2$
                    + "', which is not a form attribute. A path roots at an attribute; nothing " //$NON-NLS-1$
                    + "binds to a form parameter by data path.")); //$NON-NLS-1$
        }
    }

    /**
     * A button runs a command, and the reference may point at a form command OR at a standard
     * command the platform infers - both are legitimate, so only a MISSING or unreachable target
     * is a defect.
     */
    private static void checkButtonCommand(EObject formModel, EObject item, String path,
        List<Finding> findings)
    {
        if (!ECLASS_BUTTON.equals(item.eClass().getName())
            || item.eClass().getEStructuralFeature(FEATURE_COMMAND_NAME) == null)
        {
            return;
        }
        EObject command = single(item, FEATURE_COMMAND_NAME);
        if (command == null)
        {
            findings.add(new Finding(SEVERITY_ERROR, CODE_MISSING_COMMAND_REFERENCE, path,
                "This button runs no command. Point it at a form command or a standard command.")); //$NON-NLS-1$
            return;
        }
        if (isUnreachable(command, formModel))
        {
            findings.add(new Finding(SEVERITY_ERROR, CODE_UNRESOLVED_COMMAND_REFERENCE, path,
                "This button points at a command that is not in the model any more.")); //$NON-NLS-1$
        }
    }

    /**
     * An element's ext-info is decided by its own kind, and the platform creates it together with
     * the element. A missing one means the element was built by something that did not know the
     * rule; a MISMATCHED one means the element's type changed and the node did not follow.
     */
    /**
     * An {@code ExtendedTooltip} is pinned to type {@code Label}, and the platform rejects any other
     * type for the TYPE itself - independently of the node the tooltip carries. Carrying the right
     * ext-info is therefore no evidence here: every one of the 347938 tooltips in a real
     * configuration is a Label, so one typed otherwise is malformed however correct its node.
     */
    private static void checkExtendedTooltipType(EObject element, String path, List<Finding> findings)
    {
        if (!ECLASS_EXTENDED_TOOLTIP.equals(element.eClass().getName()))
        {
            return;
        }
        String type = literalOf(element, FEATURE_TYPE);
        if (type != null && !TYPE_LITERAL_LABEL.equals(type))
        {
            findings.add(new Finding(SEVERITY_ERROR, CODE_INVALID_EXTENDED_TOOLTIP_TYPE, path,
                "This extended tooltip is typed '" + type //$NON-NLS-1$
                    + "'; the platform accepts only 'Label' and rejects the form otherwise.")); //$NON-NLS-1$
        }
    }

    private static void checkItemExtInfo(EObject element, String path, List<Finding> findings)
    {
        checkExtendedTooltipType(element, path, findings);
        if (element.eClass().getEStructuralFeature(FEATURE_EXT_INFO) == null)
        {
            return;
        }
        EObject actual = FormElementWriter.extInfoInstance(element);
        String expected = FormElementWriter.expectedExtInfoClassifier(element);
        if (expected == null)
        {
            // Only the kinds that decide their own node have an opinion here; for the rest a null
            // answer means "no opinion", not "carries none".
            if (actual != null && FormElementWriter.kindDecidesExtInfo(element))
            {
                findings.add(new Finding(SEVERITY_ERROR, CODE_STALE_EXT_INFO, path,
                    "This element carries a '" + actual.eClass().getName() + "' but its current type " //$NON-NLS-1$ //$NON-NLS-2$
                        + "pairs with no ext-info at all; the type changed and the node stayed.")); //$NON-NLS-1$
            }
            return;
        }
        if (actual == null)
        {
            findings.add(new Finding(SEVERITY_ERROR, CODE_MISSING_EXT_INFO, path,
                "This element has no '" + expected + "', so its type-specific properties " //$NON-NLS-1$ //$NON-NLS-2$
                    + "and events are unavailable.")); //$NON-NLS-1$
            return;
        }
        if (!expected.equals(actual.eClass().getName()))
        {
            findings.add(new Finding(SEVERITY_ERROR, CODE_STALE_EXT_INFO, path,
                "This element carries a '" + actual.eClass().getName() + "' but its kind calls for a '" //$NON-NLS-1$ //$NON-NLS-2$
                    + expected + "'; the type changed and the node did not follow.")); //$NON-NLS-1$
        }
    }

    // --- handlers ------------------------------------------------------------------------------

    /**
     * A binding names an event and a BSL procedure. Either half missing leaves a handler the
     * platform lists but cannot call; two bindings on one event leave it unable to say which.
     */
    private static void checkHandlers(EObject formModel, List<EObject> items,
        EventPublication publication, List<Finding> findings)
    {
        checkOwnerHandlers(formModel, FORM_PATH, publication, findings);
        for (EObject item : items)
        {
            checkOwnerHandlers(item, pathOf(item), publication, findings);
        }
    }

    /**
     * One handler OWNER: the list it carries itself, and the one on its ext-info. The published
     * events are asked for the OWNER and never for the ext-info container, because the platform
     * publishes the UNION of an element's base type and its ext type - judging an ext-info's
     * handlers by the ext type alone would call an ordinary binding foreign. Asked once, and only
     * when there is a handler to judge: resolving it walks the platform type payload.
     */
    private static void checkOwnerHandlers(EObject owner, String path, EventPublication publication,
        List<Finding> findings)
    {
        EObject extInfo = single(owner, FEATURE_EXT_INFO);
        boolean extInfoHasHandlers = extInfo != null && !list(extInfo, FEATURE_HANDLERS).isEmpty();
        if (list(owner, FEATURE_HANDLERS).isEmpty() && !extInfoHasHandlers)
        {
            return;
        }
        List<String> published = publication.publishedBy(owner);
        checkHandlerList(owner, path, published, findings);
        if (extInfo != null)
        {
            checkHandlerList(extInfo, path, published, findings);
        }
    }

    private static void checkHandlerList(EObject container, String path, List<String> published,
        List<Finding> findings)
    {
        Set<String> seen = new HashSet<>();
        for (EObject handler : list(container, FEATURE_HANDLERS))
        {
            String procedure = nameOf(handler);
            EObject event = single(handler, FEATURE_EVENT);
            String eventName = event == null ? "" : eventLabel(event); //$NON-NLS-1$
            boolean isExtension = ECLASS_EVENT_HANDLER_EXTENSION.equals(handler.eClass().getName());
            String callType = isExtension ? enumName(value(handler, FEATURE_CALL_TYPE)) : ""; //$NON-NLS-1$
            if (procedure.isEmpty())
            {
                findings.add(new Finding(SEVERITY_ERROR, CODE_EMPTY_HANDLER_NAME, path,
                    "The handler for '" + (eventName.isEmpty() ? "(unknown event)" : eventName) //$NON-NLS-1$ //$NON-NLS-2$
                        + "' names no BSL procedure.")); //$NON-NLS-1$
            }
            if (event == null || event.eIsProxy())
            {
                findings.add(new Finding(SEVERITY_ERROR, CODE_UNRESOLVED_EVENT_REFERENCE, path,
                    "The handler '" + (procedure.isEmpty() ? "(unnamed)" : procedure) //$NON-NLS-1$ //$NON-NLS-2$
                        + "' points at no event: the reference is empty or no longer resolves.")); //$NON-NLS-1$
                continue;
            }
            // The published names are the ENGLISH ones, so an event that answers only in Russian
            // is not judged here: an unmatched name would then mean nothing.
            String englishEvent = nameOf(event);
            if (!published.isEmpty() && !englishEvent.isEmpty()
                && !containsIgnoreCase(published, englishEvent))
            {
                findings.add(new Finding(SEVERITY_ERROR, CODE_FOREIGN_EVENT_REFERENCE, path,
                    "The event '" + eventName //$NON-NLS-1$
                        + "' is published by another type, not by this element.")); //$NON-NLS-1$
            }
            if (isExtension && callType.isEmpty())
            {
                findings.add(new Finding(SEVERITY_ERROR, CODE_EXTENSION_HANDLER_WITHOUT_CALL_TYPE, path,
                    "The extension handler for '" + eventName + "' has no call type, so it does not " //$NON-NLS-1$ //$NON-NLS-2$
                        + "say how it intercepts the base event. Set Before, After or Instead.")); //$NON-NLS-1$
            }
            // A form EVENT takes any call type except the method-only one, so a non-empty value is
            // not the same as a valid one - this is the value the writer itself refuses.
            else if (isExtension && CALL_TYPE_CHANGE_AND_VALIDATE.equalsIgnoreCase(callType))
            {
                findings.add(new Finding(SEVERITY_ERROR, CODE_INVALID_EXTENSION_CALL_TYPE, path,
                    "The extension handler for '" + eventName + "' uses call type '" + callType //$NON-NLS-1$ //$NON-NLS-2$
                        + "', which intercepts a METHOD, not an event. Use Before, After or Instead.")); //$NON-NLS-1$
            }
            // One base handler per event; an extension COEXISTS with it and with other call types,
            // so the key is the event plus the call type - the same rule the writer enforces.
            if (!seen.add(eventName.toLowerCase(Locale.ROOT) + "/" + callType)) //$NON-NLS-1$
            {
                findings.add(new Finding(SEVERITY_ERROR, CODE_DUPLICATE_HANDLER_BINDING, path,
                    "'" + eventName + "' is bound twice" //$NON-NLS-1$ //$NON-NLS-2$
                        + (callType.isEmpty() ? "" : " with call type '" + callType + "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + "; the platform cannot say which binding to call.")); //$NON-NLS-1$
            }
        }
    }

    private static boolean containsIgnoreCase(List<String> values, String wanted)
    {
        for (String value : values)
        {
            if (wanted.equalsIgnoreCase(value))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * A form command runs a BSL procedure through its {@code action} container, in either of two
     * shapes: the base {@code FormCommandHandlerContainer} with a single {@code handler}, or the
     * extension {@code FormExtensionCommandHandlerContainer} with a {@code handlers} list.
     */
    private static void checkCommandActions(EObject formModel, List<Finding> findings)
    {
        for (EObject command : list(formModel, FEATURE_FORM_COMMANDS))
        {
            EObject action = single(command, FEATURE_ACTION);
            if (action == null)
            {
                continue;
            }
            String address = "Command." + nameOf(command); //$NON-NLS-1$
            if (!actionNamesAProcedure(action))
            {
                findings.add(new Finding(SEVERITY_ERROR, CODE_EMPTY_COMMAND_ACTION, address,
                    "This command has an action but names no BSL procedure to run.")); //$NON-NLS-1$
                continue;
            }
            checkActionHandlerNames(action, address, findings);
        }
    }

    /**
     * Every entry of an extension action's {@code handlers} list is a binding of its own, and
     * {@code CommandHandlerExtension} declares {@code name} as {@code String[1]} - required - so a
     * named entry says nothing about its neighbours. Reported per ENTRY, and only for an action
     * that names a procedure somewhere: one that names none is already reported as a whole.
     */
    private static void checkActionHandlerNames(EObject action, String address,
        List<Finding> findings)
    {
        for (EObject handler : list(action, FEATURE_HANDLERS))
        {
            if (nameOf(handler).isEmpty())
            {
                findings.add(new Finding(SEVERITY_ERROR, CODE_EMPTY_HANDLER_NAME, address,
                    "This command's action carries a handler entry that names no BSL procedure.")); //$NON-NLS-1$
            }
        }
    }

    /**
     * Whether a command action names a procedure. An action has TWO shapes: the singular
     * {@code handler} slot, and - for an extension command - a {@code handlers} LIST. Reading only
     * the singular one calls a fully populated extension action empty, so both are read here, the
     * same way {@code FormStructureReader.actionHandlerOf} reads them.
     */
    private static boolean actionNamesAProcedure(EObject action)
    {
        EObject single = single(action, FEATURE_HANDLER);
        if (single != null && !nameOf(single).isEmpty())
        {
            return true;
        }
        for (EObject handler : list(action, FEATURE_HANDLERS))
        {
            if (!nameOf(handler).isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    // --- shared reading ------------------------------------------------------------------------

    /**
     * Every PERSISTED item of the form, in walk order - judged by the metamodel, not by whether the
     * item has a public address token: an {@code Addition} has none and still carries an id and a
     * type-specific ext-info.
     */
    private static List<EObject> itemTree(EObject formModel)
    {
        List<EObject> items = new ArrayList<>();
        for (EObject descendant : PersistedContents.descendants(formModel))
        {
            if (isFormItem(descendant.eClass()))
            {
                items.add(descendant);
            }
        }
        return items;
    }

    private static boolean isFormItem(EClass eClass)
    {
        return FormElementWriter.isOrInherits(eClass, ECLASS_FORM_ITEM);
    }

    private static boolean isAddition(EObject element)
    {
        return ECLASS_ADDITION.equals(element.eClass().getName());
    }

    /** The additional-columns groups of an attribute - each carries a {@code columns} list of its own. */
    private static List<EObject> additionalColumnGroups(EObject attribute)
    {
        List<EObject> groups = new ArrayList<>();
        for (EObject child : PersistedContents.of(attribute))
        {
            if (ECLASS_ADDITIONAL_COLUMNS.equals(child.eClass().getName()))
            {
                groups.add(child);
            }
        }
        return groups;
    }

    /**
     * The names a data path may START with: the form's own ATTRIBUTES, and only those. Nothing binds
     * to a parameter by data path (see {@code FormElementWriter.isFormParameter}, issue #396) - and
     * across a real configuration's 3918 forms that carry parameters, not one data path is rooted at
     * one.
     */
    private static Set<String> dataPathRoots(EObject formModel)
    {
        Set<String> roots = new LinkedHashSet<>();
        for (EObject attribute : list(formModel, FEATURE_ATTRIBUTES))
        {
            roots.add(nameOf(attribute).toLowerCase(Locale.ROOT));
        }
        return roots;
    }

    /** {@code Kind.Name}, the address every other form tool accepts. */
    private static String pathOf(EObject element)
    {
        String name = nameOf(element);
        FormElementWriter.Kind kind = FormElementWriter.addressableKind(element);
        List<String> tokens = kind == null ? List.of() : FormElementWriter.tokensForKind(kind);
        // The tokens are matched case-insensitively and stored lower-case; an ADDRESS is written
        // capitalized. An item with no public token (an Addition) is named by its class instead.
        String token = tokens.isEmpty() ? element.eClass().getName() : capitalize(tokens.get(0));
        return token + "." + (name.isEmpty() ? "(unnamed)" : name); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String capitalize(String token)
    {
        return token.isEmpty() ? token
            : Character.toUpperCase(token.charAt(0)) + token.substring(1);
    }

    /**
     * Whether a BUTTON's command binding cannot resolve in this form. The binding PERSISTS as a name
     * path ({@code Form.Command.X}) that is re-resolved on load, so the question is the one the
     * resolver asks - is a form command of that NAME here - not which object a live session happens
     * to point at. Removing a command does NOT turn the buttons' reference into a proxy, so the
     * proxy test alone misses the in-transaction removal this engine is meant to catch.
     *
     * <p>Deliberately NOT used for an event: events are published by the platform TYPE, not by the
     * form, so "not inside the form" is their ordinary state and would flag every binding.</p>
     */
    private static boolean isUnreachable(EObject target, EObject formModel)
    {
        if (target.eIsProxy())
        {
            return true;
        }
        // A STANDARD command is outside the claim: whether a given one belongs to the set this
        // form (or an item in it) publishes is NOT decidable from containment. An adopted form
        // legitimately keeps one rooted in its linked base form, an inferred one may be rooted
        // nowhere, and a detached ancestor still answers eContainer() - a containment rule reads
        // each of those as the wrong answer, so this says nothing rather than guessing.
        if (!ECLASS_FORM_COMMAND.equals(target.eClass().getName()))
        {
            return false;
        }
        // A form command resolves by NAME against THIS form's list - that is what the resolver looks
        // up first and what the file re-resolves on load, so a surviving command of the same name
        // keeps the binding valid even when the reference itself went stale.
        String wanted = nameOf(target);
        if (wanted.isEmpty())
        {
            return true;
        }
        for (EObject candidate : list(formModel, FEATURE_FORM_COMMANDS))
        {
            if (wanted.equalsIgnoreCase(nameOf(candidate)))
            {
                return false;
            }
        }
        return true;
    }

    /** An event names itself in either language; the first spelling it answers to labels it. */
    private static String eventLabel(EObject event)
    {
        String name = nameOf(event);
        if (!name.isEmpty())
        {
            return name;
        }
        Object ru = value(event, FEATURE_NAME_RU);
        return ru instanceof String ? (String)ru : ""; //$NON-NLS-1$
    }

    private static String enumName(Object literal)
    {
        return literal == null ? "" : String.valueOf(literal); //$NON-NLS-1$
    }

    private static String nameOf(EObject object)
    {
        Object name = value(object, FEATURE_NAME);
        return name instanceof String ? (String)name : ""; //$NON-NLS-1$
    }

    /** The literal of an EEnum-valued feature, or {@code null} when it is absent or not an enum. */
    private static String literalOf(EObject object, String featureName)
    {
        Object raw = value(object, featureName);
        return raw instanceof Enumerator ? ((Enumerator)raw).getLiteral() : null;
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
