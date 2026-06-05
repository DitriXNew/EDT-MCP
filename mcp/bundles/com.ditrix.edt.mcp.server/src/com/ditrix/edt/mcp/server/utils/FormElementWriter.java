/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;

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
    public enum Kind { ATTRIBUTE, COMMAND, GROUP, DECORATION }

    /** A parsed form-member FQN: the form path (for {@code resolveMdForm}) + the leaf kind/name. */
    public static final class FormMemberRef
    {
        /** The owning form path, normalized to the {@code Type.Object.forms.FormName} /
         * {@code CommonForm.Name} shape that {@code GetFormStructureTool.resolveMdForm} expects. */
        public final String formPath;
        /** The raw element kind token (English or Russian); resolve via {@link #kindForToken}. */
        public final String kindToken;
        /** The element's programmatic name. */
        public final String name;

        FormMemberRef(String formPath, String kindToken, String name)
        {
            this.formPath = formPath;
            this.kindToken = kindToken;
            this.name = name;
        }
    }

    private FormElementWriter()
    {
        // utility class
    }

    /**
     * Parses a form-member FQN into its form path + leaf kind/name, or returns {@code null} when the
     * FQN does not address a form member. Two shapes are recognized:
     * <ul>
     *   <li>{@code Type.Object.Form.FormName.Kind.Name} (6 parts; the {@code Form} token may be
     *       {@code Form}/{@code Forms}/{@code Форма}/{@code Формы})</li>
     *   <li>{@code CommonForm.FormName.Kind.Name} (4 parts; a CommonForm IS a form)</li>
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
        if (p.length == 6 && isFormToken(p[2]))
        {
            return new FormMemberRef(p[0] + "." + p[1] + ".forms." + p[3], p[4], p[5]); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (p.length == 4 && "CommonForm".equalsIgnoreCase(MetadataTypeUtils.toEnglishSingular(p[0]))) //$NON-NLS-1$
        {
            return new FormMemberRef(p[0] + "." + p[1], p[2], p[3]); //$NON-NLS-1$
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
     * Resolves a form-member FQN kind token (English or Russian, case-insensitive) to a {@link Kind},
     * or {@code null} if it is not a supported form-element kind.
     */
    // Russian kind / form tokens, built from code points so this source stays pure ASCII (the same
    // non-UTF-8 Tycho-build guard the rest of the project uses; no raw Cyrillic literals).
    private static final String RU_ATTRIBUTE = cp(0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442); // rekvizit
    private static final String RU_COMMAND = cp(0x043a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x0430); // komanda
    private static final String RU_GROUP = cp(0x0433, 0x0440, 0x0443, 0x043f, 0x043f, 0x0430); // gruppa
    private static final String RU_DECORATION = cp(0x0434, 0x0435, 0x043a, 0x043e, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f); // dekoraciya
    private static final String RU_FORM = cp(0x0444, 0x043e, 0x0440, 0x043c, 0x0430); // forma
    private static final String RU_FORMS = cp(0x0444, 0x043e, 0x0440, 0x043c, 0x044b); // formy

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
        String titleLanguage, String title, String[] createdKind)
    {
        switch (kind)
        {
            case ATTRIBUTE:
                return createAttribute(formModel, name, titleLanguage, title, createdKind);
            case COMMAND:
                return createCommand(formModel, name, titleLanguage, title, createdKind);
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
        EObject container = formModel;
        if (parentName != null && !parentName.isEmpty())
        {
            container = findItem(formModel, parentName);
            if (container == null)
            {
                return "Parent form item not found: " + parentName //$NON-NLS-1$
                    + ". Create the parent group first, or omit 'parent' to add at the form root."; //$NON-NLS-1$
            }
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
