/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Shared writer for the editable FORM CONTENT model ({@code com._1c.g5.v8.dt.form.model.Form}, a
 * separate top object reached from a {@code BasicForm} mdo via {@code getForm()}). The whole form
 * package is touched REFLECTIVELY (by feature / classifier name) so this bundle needs no compile-time
 * dependency on the form model - the same technique the form-editing tools use.
 *
 * <p>This is the canonical home for the form-write logic that {@code create_metadata} (and, until
 * they are removed, the {@code add_form_*} tools) use to add a form attribute, command or visual
 * item. Mutation MUST run inside a BM write transaction on the re-fetched content form; capturing the
 * content form's own FQN for {@code forceExportToDisk} is the caller's job.</p>
 */
public final class FormElementWriter
{
    // Form-model feature names (reflective).
    private static final String FEATURE_ITEMS = "items"; //$NON-NLS-1$
    private static final String FEATURE_ATTRIBUTES = "attributes"; //$NON-NLS-1$
    private static final String FEATURE_FORM_COMMANDS = "formCommands"; //$NON-NLS-1$
    private static final String FEATURE_TITLE = "title"; //$NON-NLS-1$
    private static final String FEATURE_VALUE_TYPE = "valueType"; //$NON-NLS-1$
    private static final String FEATURE_TYPE = "type"; //$NON-NLS-1$
    private static final String FEATURE_EXT_INFO = "extInfo"; //$NON-NLS-1$
    private static final String FEATURE_ID = "id"; //$NON-NLS-1$
    private static final String FEATURE_NAME = "name"; //$NON-NLS-1$
    private static final String FEATURE_VISIBLE = "visible"; //$NON-NLS-1$

    // Concrete form-model classifier names (resolved on the form EPackage).
    private static final String ECLASS_FORM_GROUP = "FormGroup"; //$NON-NLS-1$
    private static final String ECLASS_DECORATION = "Decoration"; //$NON-NLS-1$
    private static final String ECLASS_FORM_ITEM = "FormItem"; //$NON-NLS-1$
    private static final String ECLASS_USUAL_GROUP_EXT_INFO = "UsualGroupExtInfo"; //$NON-NLS-1$
    private static final String ECLASS_LABEL_DECORATION_EXT_INFO = "LabelDecorationExtInfo"; //$NON-NLS-1$
    private static final String TYPE_LITERAL_USUAL_GROUP = "UsualGroup"; //$NON-NLS-1$
    private static final String TYPE_LITERAL_LABEL = "Label"; //$NON-NLS-1$

    /** A supported form-element kind, resolved from a (bilingual) FQN kind token. */
    public enum Kind { ATTRIBUTE, COMMAND, GROUP, DECORATION, FIELD, BUTTON }

    /** A parsed form-member FQN: the form path (for {@code resolveMdForm}) + the leaf kind/name. */
    public static final class FormMemberRef
    {
        /** The owning form path, normalized to the {@code Type.Object.forms.FormName} /
         * {@code CommonForm.Name} shape that {@code FormStructureReader.resolveMdForm} expects. */
        public final String formPath;
        /** The raw element kind token (English or Russian); resolve via {@link #kindForToken}. */
        public final String kindToken;
        /** The element's programmatic name (for a handler FQN, the EVENT name). */
        public final String name;
        /** For an ITEM-LEVEL handler FQN, the owning item's kind token; {@code null} for a form-level
         * member or handler. */
        public final String itemKindToken;
        /** For an ITEM-LEVEL handler FQN, the owning item's name; {@code null} otherwise. */
        public final String itemName;

        FormMemberRef(String formPath, String kindToken, String name, String itemKindToken,
            String itemName)
        {
            this.formPath = formPath;
            this.kindToken = kindToken;
            this.name = name;
            this.itemKindToken = itemKindToken;
            this.itemName = itemName;
        }

        /** Whether the FQN addresses an event handler on a form ITEM (vs the form root). */
        public boolean isItemLevel()
        {
            return itemName != null;
        }
    }

    private FormElementWriter()
    {
        // utility class
    }

    /**
     * Parses a form-member FQN into its form path + leaf kind/name, or returns {@code null} when the
     * FQN does not address a form member. The recognized shapes are:
     * <ul>
     *   <li>{@code Type.Object.Form.FormName.Kind.Name} (form-level member/handler; the {@code Form}
     *       token may be {@code Form}/{@code Forms}/{@code Форма}/{@code Формы})</li>
     *   <li>{@code CommonForm.FormName.Kind.Name} (a CommonForm IS a form)</li>
     *   <li>{@code Type.Object.Form.FormName.ItemKind.ItemName.Handler.Event} (an event handler on a
     *       form ITEM) and its {@code CommonForm.FormName.ItemKind.ItemName.Handler.Event} variant</li>
     * </ul>
     * The form-element kind tokens are NOT confused with the mdclass member tokens because a mdclass
     * member FQN never carries a form token at position 2 nor starts with {@code CommonForm} followed
     * by a kind pair.
     */
    public static FormMemberRef parse(String normFqn)
    {
        if (normFqn == null)
        {
            return null;
        }
        String[] p = normFqn.split("\\."); //$NON-NLS-1$
        String formPath;
        int rem; // index where the kind/name remainder begins
        if (p.length >= 6 && isFormToken(p[2]))
        {
            formPath = p[0] + "." + p[1] + ".forms." + p[3]; //$NON-NLS-1$ //$NON-NLS-2$
            rem = 4;
        }
        else if (p.length >= 4 && "CommonForm".equalsIgnoreCase(MetadataTypeUtils.toEnglishSingular(p[0]))) //$NON-NLS-1$
        {
            formPath = p[0] + "." + p[1]; //$NON-NLS-1$
            rem = 2;
        }
        else
        {
            return null;
        }
        int tail = p.length - rem;
        if (tail == 2)
        {
            // Form-level member or handler: Kind.Name.
            return new FormMemberRef(formPath, p[rem], p[rem + 1], null, null);
        }
        if (tail == 4 && isHandlerToken(p[rem + 2]))
        {
            // Item-level handler: ItemKind.ItemName.Handler.Event.
            return new FormMemberRef(formPath, p[rem + 2], p[rem + 3], p[rem], p[rem + 1]);
        }
        return null;
    }

    private static boolean isFormToken(String token)
    {
        String s = token.toLowerCase();
        return "form".equals(s) || "forms".equals(s) //$NON-NLS-1$ //$NON-NLS-2$
            || RU_FORM.equals(s) || RU_FORMS.equals(s);
    }

    /**
     * If {@code normFqn} addresses a FORM ITSELF (not a member) - {@code Type.Object.Form(s).FormName}
     * (4 parts, form token at position 2) or {@code CommonForm.FormName} (2 parts) - returns the form
     * path normalized to the {@code Type.Object.forms.FormName} / {@code CommonForm.Name} shape that
     * {@code FormStructureReader.resolveMdForm} expects; otherwise {@code null}. Used to render a
     * form's structure from {@code get_metadata_details}.
     */
    public static String parseFormPath(String normFqn)
    {
        if (normFqn == null)
        {
            return null;
        }
        String[] p = normFqn.split("\\."); //$NON-NLS-1$
        if (p.length == 4 && isFormToken(p[2]))
        {
            return p[0] + "." + p[1] + ".forms." + p[3]; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (p.length == 2 && "CommonForm".equalsIgnoreCase(MetadataTypeUtils.toEnglishSingular(p[0]))) //$NON-NLS-1$
        {
            return p[0] + "." + p[1]; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Resolves a form-member FQN kind token (English or Russian, case-insensitive) to a {@link Kind},
     * or {@code null} if it is not a supported form-element kind.
     */
    // Russian kind / form tokens, built from code points so this source stays pure ASCII (the same
    // non-UTF-8 Tycho-build guard the rest of the project uses; no raw Cyrillic literals).
    private static final String RU_ATTRIBUTE = cp(0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442); // rekvizit
    private static final String RU_COMMAND = cp(0x043a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x0430); // komanda
    private static final String RU_GROUP = cp(0x0433, 0x0440, 0x0443, 0x043f, 0x043f, 0x0430); // gruppa
    private static final String RU_DECORATION = cp(0x0434, 0x0435, 0x043a, 0x043e, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f); // dekoraciya
    private static final String RU_FIELD = cp(0x043f, 0x043e, 0x043b, 0x0435); // pole
    private static final String RU_BUTTON = cp(0x043a, 0x043d, 0x043e, 0x043f, 0x043a, 0x0430); // knopka
    private static final String RU_FORM = cp(0x0444, 0x043e, 0x0440, 0x043c, 0x0430); // forma
    private static final String RU_FORMS = cp(0x0444, 0x043e, 0x0440, 0x043c, 0x044b); // formy
    private static final String RU_HANDLER = cp(0x043e, 0x0431, 0x0440, 0x0430, 0x0431, 0x043e, 0x0442, 0x0447, 0x0438, 0x043a); // obrabotchik

    /** Whether a kind token addresses an event Handler (English or Russian, case-insensitive). */
    public static boolean isHandlerToken(String token)
    {
        if (token == null)
        {
            return false;
        }
        String t = token.trim().toLowerCase();
        return "handler".equals(t) || RU_HANDLER.equals(t); //$NON-NLS-1$
    }

    public static Kind kindForToken(String token)
    {
        if (token == null)
        {
            return null;
        }
        String t = token.trim().toLowerCase();
        if ("attribute".equals(t) || "attributes".equals(t) || RU_ATTRIBUTE.equals(t)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return Kind.ATTRIBUTE;
        }
        if ("command".equals(t) || "commands".equals(t) || RU_COMMAND.equals(t)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return Kind.COMMAND;
        }
        if ("group".equals(t) || RU_GROUP.equals(t)) //$NON-NLS-1$
        {
            return Kind.GROUP;
        }
        if ("decoration".equals(t) || RU_DECORATION.equals(t)) //$NON-NLS-1$
        {
            return Kind.DECORATION;
        }
        if ("field".equals(t) || RU_FIELD.equals(t)) //$NON-NLS-1$
        {
            return Kind.FIELD;
        }
        if ("button".equals(t) || RU_BUTTON.equals(t)) //$NON-NLS-1$
        {
            return Kind.BUTTON;
        }
        return null;
    }

    /** Builds a string from BMP code points (keeps this source pure ASCII). */
    private static String cp(int... codePoints)
    {
        StringBuilder sb = new StringBuilder(codePoints.length);
        for (int c : codePoints)
        {
            sb.append((char)c);
        }
        return sb.toString();
    }

    /**
     * Reads the editable form content model from a {@code BasicForm} mdo via {@code getForm()}
     * (reflective). Returns {@code null} if the form has no managed-form content (empty / legacy /
     * not yet built), recognized by the presence of the {@code items} feature.
     *
     * @param txMdForm the transaction-bound {@code BasicForm} EObject
     * @return the editable form content EObject, or {@code null}
     */
    public static EObject getEditableForm(EObject txMdForm)
    {
        try
        {
            Method getForm = txMdForm.getClass().getMethod("getForm"); //$NON-NLS-1$
            Object form = getForm.invoke(txMdForm);
            if (form instanceof EObject
                && ((EObject)form).eClass().getEStructuralFeature(FEATURE_ITEMS) != null)
            {
                return (EObject)form;
            }
        }
        catch (ReflectiveOperationException e)
        {
            // No getForm() / inaccessible - treated as "no editable model".
        }
        return null;
    }

    /**
     * Creates a form member of {@code kind} named {@code name} on the editable {@code formModel}.
     * For a visual item (group / decoration) the optional {@code parentName} nests it under an
     * existing item (form root when {@code null}); {@code title} (with its language CODE) is applied
     * when given. Runs INSIDE a BM write transaction on the re-fetched content form.
     *
     * @return {@code null} on success, or a human-readable error message (the caller wraps it in
     *     {@code ToolResult.error}); the created element's concrete EClass name is returned via
     *     {@code createdKind} when non-null.
     */
    public static String createMember(EObject formModel, Kind kind, String name, String parentName,
        String bindTarget, String titleLanguage, String title, String[] createdKind)
    {
        switch (kind)
        {
            case ATTRIBUTE:
                return createAttribute(formModel, name, titleLanguage, title, createdKind);
            case COMMAND:
                return createCommand(formModel, name, titleLanguage, title, createdKind);
            case FIELD:
                return createField(formModel, name, parentName, bindTarget, titleLanguage, title, createdKind);
            case BUTTON:
                return createButton(formModel, name, parentName, bindTarget, titleLanguage, title, createdKind);
            case GROUP:
            case DECORATION:
            default:
                return createItem(formModel, kind, name, parentName, titleLanguage, title, createdKind);
        }
    }

    private static String createAttribute(EObject formModel, String name, String titleLanguage,
        String title, String[] createdKind)
    {
        if (findByName(referenceList(formModel, FEATURE_ATTRIBUTES), name) != null)
        {
            return "Form attribute already exists: " + name; //$NON-NLS-1$
        }
        EObject attr = createFromFeatureType(formModel, FEATURE_ATTRIBUTES);
        if (attr == null)
        {
            return "Cannot create a form attribute for this form model."; //$NON-NLS-1$
        }
        setStringFeature(attr, FEATURE_NAME, name);
        setDefaultValueType(attr);
        applyTitle(attr, titleLanguage, title);
        addToList(formModel, FEATURE_ATTRIBUTES, attr);
        recordKind(attr, createdKind);
        return null;
    }

    private static String createCommand(EObject formModel, String name, String titleLanguage,
        String title, String[] createdKind)
    {
        if (findByName(referenceList(formModel, FEATURE_FORM_COMMANDS), name) != null)
        {
            return "Form command already exists: " + name; //$NON-NLS-1$
        }
        EObject cmd = createFromFeatureType(formModel, FEATURE_FORM_COMMANDS);
        if (cmd == null)
        {
            return "Cannot create a form command for this form model."; //$NON-NLS-1$
        }
        setStringFeature(cmd, FEATURE_NAME, name);
        applyTitle(cmd, titleLanguage, title);
        addToList(formModel, FEATURE_FORM_COMMANDS, cmd);
        recordKind(cmd, createdKind);
        return null;
    }

    private static String createItem(EObject formModel, Kind kind, String name, String parentName,
        String titleLanguage, String title, String[] createdKind)
    {
        if (findItem(formModel, name) != null)
        {
            return "Form item already exists: " + name; //$NON-NLS-1$
        }
        EObject container = containerFor(formModel, parentName);
        if (container == null)
        {
            return parentNotFound(parentName);
        }
        String classifier = kind == Kind.GROUP ? ECLASS_FORM_GROUP : ECLASS_DECORATION;
        EObject item = createFromClassifier(formModel, classifier);
        if (item == null)
        {
            return "Cannot create a form " + classifier + " for this form model."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        setStringFeature(item, FEATURE_NAME, name);
        setBooleanFeature(item, FEATURE_VISIBLE, true);
        setIntFeature(item, FEATURE_ID, nextItemId(formModel));
        initManagedItem(formModel, item, kind);
        applyTitle(item, titleLanguage, title);
        addToList(container, FEATURE_ITEMS, item);
        recordKind(item, createdKind);
        return null;
    }

    /** A FormField bound to a form attribute via its dataPath (a generic InputField the user can refine). */
    @SuppressWarnings("unchecked")
    private static String createField(EObject formModel, String name, String parentName,
        String attrName, String titleLanguage, String title, String[] createdKind)
    {
        if (attrName == null || attrName.isEmpty())
        {
            return "A form field needs a 'dataPath' property naming the form attribute it shows " //$NON-NLS-1$
                + "(e.g. {name:'dataPath', value:'Price'})."; //$NON-NLS-1$
        }
        if (findByName(referenceList(formModel, FEATURE_ATTRIBUTES), attrName) == null)
        {
            return "Form attribute '" + attrName + "' not found - create it first, then bind the field " //$NON-NLS-1$ //$NON-NLS-2$
                + "to it (so the data path resolves)."; //$NON-NLS-1$
        }
        if (findItem(formModel, name) != null)
        {
            return "Form item already exists: " + name; //$NON-NLS-1$
        }
        EObject container = containerFor(formModel, parentName);
        if (container == null)
        {
            return parentNotFound(parentName);
        }
        EObject item = createFromClassifier(formModel, "FormField"); //$NON-NLS-1$
        if (item == null)
        {
            return "Cannot create a form field for this form model."; //$NON-NLS-1$
        }
        setStringFeature(item, FEATURE_NAME, name);
        setBooleanFeature(item, FEATURE_VISIBLE, true);
        setIntFeature(item, FEATURE_ID, nextItemId(formModel));
        // dataPath: a contained DataPath with segments=[attrName] (objects is transient - left empty,
        // the form's derived data recomputes it).
        EStructuralFeature dpFeat = item.eClass().getEStructuralFeature("dataPath"); //$NON-NLS-1$
        EObject dataPath = createFromClassifier(formModel, "DataPath"); //$NON-NLS-1$
        if (dpFeat instanceof EReference && dataPath != null)
        {
            EStructuralFeature segFeat = dataPath.eClass().getEStructuralFeature("segments"); //$NON-NLS-1$
            if (segFeat != null && dataPath.eGet(segFeat) instanceof EList<?>)
            {
                ((EList<String>)dataPath.eGet(segFeat)).add(attrName);
            }
            item.eSet(dpFeat, dataPath);
        }
        // Pure-model default field type (InputField + a fresh InputFieldExtInfo), as the platform's
        // own factory does before the value type is known.
        setEnumFeature(item, FEATURE_TYPE, "InputField"); //$NON-NLS-1$
        setExtInfoClassifier(formModel, item, "InputFieldExtInfo"); //$NON-NLS-1$
        applyTitle(item, titleLanguage, title);
        addToList(container, FEATURE_ITEMS, item);
        recordKind(item, createdKind);
        return null;
    }

    /** A Button bound to a form command (FormCommand is-a mcore Command, so the reference is direct). */
    private static String createButton(EObject formModel, String name, String parentName,
        String cmdName, String titleLanguage, String title, String[] createdKind)
    {
        if (cmdName == null || cmdName.isEmpty())
        {
            return "A form button needs a 'command' property naming the form command it runs " //$NON-NLS-1$
                + "(e.g. {name:'command', value:'Refresh'})."; //$NON-NLS-1$
        }
        EObject command = findByName(referenceList(formModel, FEATURE_FORM_COMMANDS), cmdName);
        if (command == null)
        {
            return "Form command '" + cmdName + "' not found - create it first, then bind the button " //$NON-NLS-1$ //$NON-NLS-2$
                + "to it."; //$NON-NLS-1$
        }
        if (findItem(formModel, name) != null)
        {
            return "Form item already exists: " + name; //$NON-NLS-1$
        }
        EObject container = containerFor(formModel, parentName);
        if (container == null)
        {
            return parentNotFound(parentName);
        }
        EObject item = createFromClassifier(formModel, "Button"); //$NON-NLS-1$
        if (item == null)
        {
            return "Cannot create a form button for this form model."; //$NON-NLS-1$
        }
        setStringFeature(item, FEATURE_NAME, name);
        setBooleanFeature(item, FEATURE_VISIBLE, true);
        setIntFeature(item, FEATURE_ID, nextItemId(formModel));
        // A standalone button; buttons have no extInfo (unlike fields/groups/decorations).
        setEnumFeature(item, FEATURE_TYPE, "UsualButton"); //$NON-NLS-1$
        EStructuralFeature cmdFeat = item.eClass().getEStructuralFeature("commandName"); //$NON-NLS-1$
        if (cmdFeat instanceof EReference)
        {
            item.eSet(cmdFeat, command);
        }
        applyTitle(item, titleLanguage, title);
        addToList(container, FEATURE_ITEMS, item);
        recordKind(item, createdKind);
        return null;
    }

    /** The form root for a blank parent, the named item otherwise, or {@code null} if not found. */
    private static EObject containerFor(EObject formModel, String parentName)
    {
        if (parentName == null || parentName.isEmpty())
        {
            return formModel;
        }
        return findItem(formModel, parentName);
    }

    private static String parentNotFound(String parentName)
    {
        return "Parent form item not found: " + parentName //$NON-NLS-1$
            + ". Create the parent group first, or omit 'parent' to add at the form root."; //$NON-NLS-1$
    }

    /** Attaches a fresh extInfo of the named concrete classifier to an item (best-effort). */
    private static void setExtInfoClassifier(EObject formModel, EObject item, String classifier)
    {
        EStructuralFeature feature = item.eClass().getEStructuralFeature(FEATURE_EXT_INFO);
        if (!(feature instanceof EReference))
        {
            return;
        }
        EClass extInfoClass = formEClass(formModel, classifier);
        if (extInfoClass != null && extInfoClass.getEPackage() != null)
        {
            item.eSet(feature, extInfoClass.getEPackage().getEFactoryInstance().create(extInfoClass));
        }
    }

    // ---- event handlers -------------------------------------------------------------------------

    /**
     * Binds an event {@code Handler} to {@code container} (the form itself or a form item): resolves
     * the requested {@code eventName} against the element's AVAILABLE events; on no match returns an
     * error LISTING the available events localized to {@code langCode} (the user-required advisory).
     * The {@code procName} is the BSL handler procedure name (defaults to the event name when blank).
     *
     * @param version the platform version (to resolve the element's platform Type and its events)
     * @return {@code null} on success, or a human-readable error message
     */
    public static String createHandler(EObject container, String eventName, String procName,
        Version version, String langCode, String[] createdKind)
    {
        EStructuralFeature handlersFeat = container.eClass().getEStructuralFeature("handlers"); //$NON-NLS-1$
        if (!(handlersFeat instanceof EReference) || !handlersFeat.isMany())
        {
            return "The form element '" + container.eClass().getName() //$NON-NLS-1$
                + "' cannot hold event handlers."; //$NON-NLS-1$
        }
        List<EObject> events = availableEvents(container, version);
        if (events.isEmpty())
        {
            return "Could not resolve the available events for this form element."; //$NON-NLS-1$
        }
        EObject matched = null;
        for (EObject ev : events)
        {
            if (eventName.equalsIgnoreCase(eventNameOf(ev, false))
                || eventName.equalsIgnoreCase(eventNameOf(ev, true)))
            {
                matched = ev;
                break;
            }
        }
        if (matched == null)
        {
            boolean ru = "ru".equals(langCode); //$NON-NLS-1$
            StringBuilder sb = new StringBuilder();
            for (EObject ev : events)
            {
                String n = eventNameOf(ev, ru);
                if (n == null || n.isEmpty())
                {
                    n = eventNameOf(ev, !ru);
                }
                if (n != null && !n.isEmpty())
                {
                    if (sb.length() > 0)
                    {
                        sb.append(", "); //$NON-NLS-1$
                    }
                    sb.append(n);
                }
            }
            return "Event '" + eventName + "' is not valid for " + container.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ". Available events: " + sb; //$NON-NLS-1$
        }
        EStructuralFeature evFeat = handlerEventFeature(handlersFeat);
        for (EObject existing : referenceList(container, "handlers")) //$NON-NLS-1$
        {
            if (evFeat != null && existing.eGet(evFeat) == matched)
            {
                return "An event handler for '" + eventName + "' already exists on this element."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        EClass ehType = ((EReference)handlersFeat).getEReferenceType();
        if (ehType == null || ehType.getEPackage() == null)
        {
            return "Cannot create an event handler for this form model."; //$NON-NLS-1$
        }
        EObject handler = ehType.getEPackage().getEFactoryInstance().create(ehType);
        setStringFeature(handler, FEATURE_NAME, (procName == null || procName.isEmpty()) ? eventName : procName);
        if (evFeat != null)
        {
            handler.eSet(evFeat, matched);
        }
        addToList(container, "handlers", handler); //$NON-NLS-1$
        recordKind(handler, createdKind);
        return null;
    }

    /** The {@code event} EReference on the EventHandler EClass held by the {@code handlers} feature. */
    private static EStructuralFeature handlerEventFeature(EStructuralFeature handlersFeat)
    {
        EClass ehType = ((EReference)handlersFeat).getEReferenceType();
        return ehType != null ? ehType.getEStructuralFeature("event") : null; //$NON-NLS-1$
    }

    private static String eventNameOf(EObject event, boolean russian)
    {
        return stringFeature(event, russian ? "nameRu" : "name"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The available platform events for a form element (the form root OR a form item), replicating
     * {@code FormItemInformationService.getAllowedEvents}'s pure-model logic (no form-service
     * dependency): the union of the events of the element's platform BASE type and, when present, its
     * {@code extInfo} SUB-type. The base/ext type name comes from {@link #PLATFORM_TYPE_BY_ECLASS}
     * (the same mapping the platform's {@code BASE_TYPES_OF_FORM_ITEMS_AND_EXT} holds); each name is
     * resolved to its {@code Type} via {@link IEObjectProvider} and its {@code events} collected.
     * <p>Unioning the ext-info type matters for items: e.g. an input field's {@code OnChange} lives on
     * {@code FormFieldExtensionForATextBox} (its {@code InputFieldExtInfo}), not on the bare
     * {@code FormField} base type.</p>
     */
    private static List<EObject> availableEvents(EObject element, Version version)
    {
        if (version == null)
        {
            return Collections.emptyList();
        }
        IEObjectProvider provider =
            IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
        if (provider == null)
        {
            return Collections.emptyList();
        }
        List<EObject> events = new ArrayList<>();
        addTypeEvents(provider, element, PLATFORM_TYPE_BY_ECLASS.get(element.eClass().getName()), events);
        EStructuralFeature extInfoFeat = element.eClass().getEStructuralFeature(FEATURE_EXT_INFO);
        if (extInfoFeat instanceof EReference)
        {
            Object ext = element.eGet(extInfoFeat);
            if (ext instanceof EObject)
            {
                addTypeEvents(provider, element,
                    PLATFORM_TYPE_BY_ECLASS.get(((EObject)ext).eClass().getName()), events);
            }
        }
        return events;
    }

    /** Resolves {@code typeName} to a platform {@code Type} and appends its {@code events} to the list. */
    @SuppressWarnings("unchecked")
    private static void addTypeEvents(IEObjectProvider provider, EObject context, String typeName,
        List<EObject> accumulator)
    {
        EObject type = resolveTypeName(provider, context, typeName);
        if (type == null)
        {
            return;
        }
        EStructuralFeature eventsFeat = type.eClass().getEStructuralFeature("events"); //$NON-NLS-1$
        Object value = eventsFeat != null ? type.eGet(eventsFeat) : null;
        if (value instanceof List<?>)
        {
            accumulator.addAll((List<EObject>)value);
        }
    }

    /**
     * Resolves a platform type by name, swapping {@code ManagedForm} &harr; {@code ClientApplication
     * Form} the way the platform does (the managed form's type is {@code ClientApplicationForm} on
     * modern platforms and {@code ManagedForm} on legacy ones).
     */
    private static EObject resolveTypeName(IEObjectProvider provider, EObject context, String typeName)
    {
        if (typeName == null)
        {
            return null;
        }
        EObject type = resolveType(provider, context, typeName);
        if (type == null && "ManagedForm".equals(typeName)) //$NON-NLS-1$
        {
            type = resolveType(provider, context, "ClientApplicationForm"); //$NON-NLS-1$
        }
        else if (type == null && "ClientApplicationForm".equals(typeName)) //$NON-NLS-1$
        {
            type = resolveType(provider, context, "ManagedForm"); //$NON-NLS-1$
        }
        return type;
    }

    private static EObject resolveType(IEObjectProvider provider, EObject context, String typeName)
    {
        try
        {
            // createProxy THROWS for a name the provider does not know (it does not return null), so
            // an unknown legacy/modern type name must not abort the lookup - we try the alternative.
            EObject proxy = provider.createProxy(typeName);
            if (proxy == null)
            {
                return null;
            }
            EObject resolved = EcoreUtil.resolve(proxy, context);
            return (resolved == null || resolved.eIsProxy()) ? null : resolved;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Form-element / ext-info EClass name &rarr; platform base-type name, a faithful copy of
     * {@code FormItemInformationService.BASE_TYPES_OF_FORM_ITEMS_AND_EXT} (keyed by EClass NAME so this
     * bundle needs no compile-time form-model dependency). The events of an element are the union over
     * its base EClass and its current {@code extInfo} EClass.
     */
    private static final Map<String, String> PLATFORM_TYPE_BY_ECLASS = buildPlatformTypeMap();

    private static Map<String, String> buildPlatformTypeMap()
    {
        Map<String, String> m = new HashMap<>();
        // Element base types.
        m.put("Form", "ManagedForm"); // modern: ClientApplicationForm (resolveTypeName swaps) //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Table", "FormTable"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Decoration", "FormDecoration"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FormField", "FormField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Button", "FormButton"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FormGroup", "FormGroup"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Addition", "FormItemAddition"); //$NON-NLS-1$ //$NON-NLS-2$
        // Form ext-infos.
        m.put("CatalogFormExtInfo", "ManagedFormExtensionForCatalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DocumentFormExtInfo", "ManagedFormExtensionForDocuments"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ChartOfCharacteristicTypesFormExtInfo", //$NON-NLS-1$
            "ManagedFormExtensionForChartOfCharacteristicsTypes"); //$NON-NLS-1$
        m.put("ReportFormExtInfo", "ManagedFormExtensionForReports"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ConstantsFormExtInfo", "ManagedFormExtensionForConstants"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("InformationRegisterManagerFormExtInfo", //$NON-NLS-1$
            "ManagedFormExtensionForInformationRegisterRecords"); //$NON-NLS-1$
        m.put("BusinessProcesFormExtInfo", "ManagedFormExtensionForBusinessProcesses"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TaskFormExtInfo", "ManagedFormExtensionForTasks"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SettingsComposerFormExtInfo", "ManagedFormExtensionForSettingsComposer"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("RecordSetFormExtInfo", "ManagedFormExtensionForRecordSet"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ObjectFormExtInfo", "ManagedFormExtensionForObjects"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TableObjectFormExtInfo", "ManagedFormExtensionForExternalDataSourceTableObject"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TableRecordFormExtInfo", "ManagedFormExtensionForExternalDataSourceTableRecord"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CubeRecordFormExtInfo", "ManagedFormExtensionForExternalDataSourceCubeRecord"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CubeRecordSetFormExtInfo", "ManagedFormExtensionForExternalDataSourceCubeRecordSet"); //$NON-NLS-1$ //$NON-NLS-2$
        // Table / decoration ext-infos.
        m.put("DynamicListTableExtInfo", "FormTableExtensionForDynamicList"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("LabelDecorationExtInfo", "FormDecorationExtensionForALabel"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PictureDecorationExtInfo", "FormDecorationExtensionForAPicture"); //$NON-NLS-1$ //$NON-NLS-2$
        // Field ext-infos.
        m.put("LabelFieldExtInfo", "FormFieldExtensionForALabelField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("InputFieldExtInfo", "FormFieldExtensionForATextBox"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CheckBoxFieldExtInfo", "FormFieldExtensionForACheckBoxField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ImageFieldExtInfo", "FormFieldExtensionForAPictureField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("RadioButtonsFieldExtInfo", "FormFieldExtensionForARadioButtonField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SpreadSheetDocFieldExtInfo", "FormFieldExtensionForASpreadsheetDocumentField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TextDocFieldExtInfo", "FormFieldExtensionForATextDocument"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CalendarFieldExtInfo", "FormFieldExtensionForACalendarField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ProgressBarFieldExtInfo", "FormFieldExtensionForAProgressBarField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TrackBarFieldExtInfo", "FormFieldExtensionForATrackBarField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ChartFieldExtInfo", "FormFieldExtensionForAChartField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("GanttChartFieldExtInfo", "FormFieldExtensionForAGanttChartField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DendrogramFieldExtInfo", "FormFieldExtensionForADendrogramField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FlowchartFieldExtInfo", "FormFieldExtensionForAGraphicalSchemaField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("HtmlFieldExtInfo", "FormExtensionForAHTMLDocumentField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("GeographicalMapFieldExtInfo", "FormFieldExtensionForAGeographicalSchemaField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FormattedDocFieldExtInfo", "FormFieldExtensionForAFormattedDocument"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PDFDocumentFieldExtInfo", "FormExtensionForAPDFDocumentField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PlannerFieldExtInfo", "FormFieldExtensionForAPlanner"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PeriodFieldExtInfo", "FormFieldExtensionForAPeriodField"); //$NON-NLS-1$ //$NON-NLS-2$
        // Group ext-infos.
        m.put("ColumnGroupExtInfo", "FormGroupExtensionForAGroupOfColumns"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PagesGroupExtInfo", "FormGroupExtensionForPages"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PageGroupExtInfo", "FormGroupExtensionForAPage"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PopupGroupExtInfo", "FormGroupExtensionForAPopup"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommandBarExtInfo", "FormGroupExtensionForACommandBar"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("UsualGroupExtInfo", "FormGroupExtensionForAUsualGroup"); //$NON-NLS-1$ //$NON-NLS-2$
        // Addition ext-infos.
        m.put("SearchStringAdditionExtInfo", "FormItemAdditionExtensionForSearchString"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ViewStatusAdditionExtInfo", "FormItemAdditionExtensionForViewStatus"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SearchControlAdditionExtInfo", "FormItemAdditionExtensionForSearchControl"); //$NON-NLS-1$ //$NON-NLS-2$
        return Collections.unmodifiableMap(m);
    }

    // ---- element factories (reflective, via the form EPackage) ----------------------------------

    /** Creates an instance of a mono-typed collection's element EType (attributes / formCommands). */
    private static EObject createFromFeatureType(EObject formModel, String featureName)
    {
        EStructuralFeature feature = formModel.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EReference))
        {
            return null;
        }
        EClass type = ((EReference)feature).getEReferenceType();
        if (type == null || type.getEPackage() == null)
        {
            return null;
        }
        return type.getEPackage().getEFactoryInstance().create(type);
    }

    /** Creates an instance of a concrete form classifier (FormGroup / Decoration) by name. */
    private static EObject createFromClassifier(EObject formModel, String classifierName)
    {
        EClass itemClass = formEClass(formModel, classifierName);
        if (itemClass == null || itemClass.getEPackage() == null)
        {
            return null;
        }
        return itemClass.getEPackage().getEFactoryInstance().create(itemClass);
    }

    /** Sets the attribute's valueType to a fresh empty TypeDescription (the form default type). */
    private static void setDefaultValueType(EObject attribute)
    {
        EStructuralFeature feature = attribute.eClass().getEStructuralFeature(FEATURE_VALUE_TYPE);
        if (!(feature instanceof EReference))
        {
            return;
        }
        EClass typeClass = ((EReference)feature).getEReferenceType();
        if (typeClass == null || typeClass.getEPackage() == null)
        {
            return;
        }
        attribute.eSet(feature, typeClass.getEPackage().getEFactoryInstance().create(typeClass));
    }

    /** Sets the managed item's type enum + a default extInfo, the way FormObjectFactory does. */
    private static void initManagedItem(EObject formModel, EObject item, Kind kind)
    {
        String typeLiteral = kind == Kind.GROUP ? TYPE_LITERAL_USUAL_GROUP : TYPE_LITERAL_LABEL;
        String extInfoClassifier =
            kind == Kind.GROUP ? ECLASS_USUAL_GROUP_EXT_INFO : ECLASS_LABEL_DECORATION_EXT_INFO;
        setEnumFeature(item, FEATURE_TYPE, typeLiteral);
        EStructuralFeature feature = item.eClass().getEStructuralFeature(FEATURE_EXT_INFO);
        if (feature instanceof EReference)
        {
            EClass extInfoClass = formEClass(formModel, extInfoClassifier);
            if (extInfoClass != null && extInfoClass.getEPackage() != null)
            {
                item.eSet(feature, extInfoClass.getEPackage().getEFactoryInstance().create(extInfoClass));
            }
        }
    }

    private static EClass formEClass(EObject formModel, String classifierName)
    {
        EPackage pkg = formModel.eClass().getEPackage();
        if (pkg == null)
        {
            return null;
        }
        EClassifier classifier = pkg.getEClassifier(classifierName);
        return (classifier instanceof EClass) ? (EClass)classifier : null;
    }

    // ---- the form-wide id allocation ------------------------------------------------------------

    /** The next free form-item id = max existing {@code FormItem} id across the whole form + 1. */
    private static int nextItemId(EObject formModel)
    {
        EClassifier formItem = formModel.eClass().getEPackage().getEClassifier(ECLASS_FORM_ITEM);
        boolean filter = formItem instanceof EClass;
        int max = 0;
        for (TreeIterator<EObject> it = formModel.eAllContents(); it.hasNext();)
        {
            EObject obj = it.next();
            if (filter && !((EClass)formItem).isInstance(obj))
            {
                continue;
            }
            EStructuralFeature idFeature = obj.eClass().getEStructuralFeature(FEATURE_ID);
            if (idFeature != null && obj.eGet(idFeature) instanceof Integer)
            {
                max = Math.max(max, ((Integer)obj.eGet(idFeature)).intValue());
            }
        }
        return max + 1;
    }

    // ---- reflective helpers ---------------------------------------------------------------------

    /** Writes the title for a language CODE into the object's {@code title} EMap (never the name). */
    private static void applyTitle(EObject object, String languageCode, String title)
    {
        if (languageCode == null || title == null || title.isEmpty())
        {
            return;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(FEATURE_TITLE);
        if (feature == null)
        {
            return;
        }
        Object value = object.eGet(feature);
        if (value instanceof EMap<?, ?>)
        {
            @SuppressWarnings("unchecked")
            EMap<String, String> map = (EMap<String, String>)value;
            map.put(languageCode, title);
        }
    }

    /**
     * Finds a form item by its (form-wide unique) programmatic name anywhere in the {@code items}
     * tree, or {@code null}. Used to resolve the owner of an item-level event handler. Must be called
     * on the transaction-bound form model.
     */
    public static EObject findFormItem(EObject formModel, String name)
    {
        return findItem(formModel, name);
    }

    /** Finds a form ATTRIBUTE by programmatic name, or {@code null}. Call on the tx-bound form model. */
    public static EObject findFormAttribute(EObject formModel, String name)
    {
        return findByName(referenceList(formModel, FEATURE_ATTRIBUTES), name);
    }

    /** Finds a form COMMAND by programmatic name, or {@code null}. Call on the tx-bound form model. */
    public static EObject findFormCommand(EObject formModel, String name)
    {
        return findByName(referenceList(formModel, FEATURE_FORM_COMMANDS), name);
    }

    /**
     * Resolves a form member EObject from a parsed member ref on the tx-bound form model: ATTRIBUTE
     * &rarr; the attributes list, COMMAND &rarr; the formCommands list, anything else (Field / Button /
     * Group / Decoration / Table / ...) &rarr; the items tree by name. Returns {@code null} if no such
     * member exists. A handler ref is NOT a member - resolve it via {@link #findFormHandler} on the
     * appropriate container.
     */
    public static EObject resolveFormMember(EObject formModel, FormMemberRef ref)
    {
        Kind kind = kindForToken(ref.kindToken);
        if (kind == Kind.ATTRIBUTE)
        {
            return findFormAttribute(formModel, ref.name);
        }
        if (kind == Kind.COMMAND)
        {
            return findFormCommand(formModel, ref.name);
        }
        return findFormItem(formModel, ref.name);
    }

    /**
     * Finds the event handler bound to {@code eventName} (English or Russian, case-insensitive) on
     * {@code container} (the form root or a form item), or {@code null}. Used to delete a handler by
     * the event its FQN names. Call on the tx-bound form model.
     */
    public static EObject findFormHandler(EObject container, String eventName)
    {
        EStructuralFeature handlersFeat = container.eClass().getEStructuralFeature("handlers"); //$NON-NLS-1$
        if (!(handlersFeat instanceof EReference) || !handlersFeat.isMany())
        {
            return null;
        }
        EClass ehType = ((EReference)handlersFeat).getEReferenceType();
        EStructuralFeature evFeat = ehType != null ? ehType.getEStructuralFeature("event") : null; //$NON-NLS-1$
        for (EObject handler : referenceList(container, "handlers")) //$NON-NLS-1$
        {
            Object ev = evFeat != null ? handler.eGet(evFeat) : null;
            if (ev instanceof EObject
                && (eventName.equalsIgnoreCase(stringFeature((EObject)ev, "name")) //$NON-NLS-1$
                    || eventName.equalsIgnoreCase(stringFeature((EObject)ev, "nameRu")))) //$NON-NLS-1$
            {
                return handler;
            }
        }
        return null;
    }

    /** Depth-first search of the whole {@code items} tree for an item by programmatic name. */
    private static EObject findItem(EObject container, String name)
    {
        for (EObject item : referenceList(container, FEATURE_ITEMS))
        {
            if (name.equalsIgnoreCase(stringFeature(item, FEATURE_NAME)))
            {
                return item;
            }
            EObject nested = findItem(item, name);
            if (nested != null)
            {
                return nested;
            }
        }
        return null;
    }

    private static EObject findByName(EList<EObject> list, String name)
    {
        for (EObject e : list)
        {
            if (name.equalsIgnoreCase(stringFeature(e, FEATURE_NAME)))
            {
                return e;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static EList<EObject> referenceList(EObject owner, String featureName)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            Object value = owner.eGet(feature);
            if (value instanceof EList<?>)
            {
                return (EList<EObject>)value;
            }
        }
        return org.eclipse.emf.common.util.ECollections.emptyEList();
    }

    @SuppressWarnings("unchecked")
    private static void addToList(EObject container, String featureName, EObject element)
    {
        EStructuralFeature feature = container.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EReference) || !feature.isMany())
        {
            throw new RuntimeException("Form feature '" + featureName + "' is not a list"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ((EList<EObject>)container.eGet(feature)).add(element);
    }

    private static void recordKind(EObject element, String[] createdKind)
    {
        if (createdKind != null && createdKind.length > 0)
        {
            createdKind[0] = element.eClass().getName();
        }
    }

    private static String stringFeature(EObject object, String featureName)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        Object value = feature != null ? object.eGet(feature) : null;
        return value instanceof String ? (String)value : null;
    }

    private static void setStringFeature(EObject object, String featureName, String value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, value);
        }
    }

    private static void setBooleanFeature(EObject object, String featureName, boolean value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, Boolean.valueOf(value));
        }
    }

    private static void setIntFeature(EObject object, String featureName, int value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, Integer.valueOf(value));
        }
    }

    private static void setEnumFeature(EObject object, String featureName, String literal)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EAttribute))
        {
            return;
        }
        EClassifier type = ((EAttribute)feature).getEAttributeType();
        if (!(type instanceof EEnum))
        {
            return;
        }
        EEnumLiteral enumLiteral = ((EEnum)type).getEEnumLiteralByLiteral(literal);
        if (enumLiteral != null)
        {
            object.eSet(feature, enumLiteral.getInstance());
        }
    }
}
