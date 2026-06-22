/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.junit.Test;

/**
 * Tests the pure form-read logic of {@link FormStructureReader}: the FQN-parsing resolver
 * ({@link FormStructureReader#resolveMdForm}), the EMF-reflection accessors
 * ({@code nameOf} / {@code titleOf} / {@code getReferenceList}) and the Markdown renderer
 * ({@link FormStructureReader#render}), exercised against a dynamic EMF model shaped like a managed
 * form (items / attributes / formCommands / name / id / title). The deep read of a real form model is
 * covered by the e2e suite (get_metadata_details on a form FQN) against a live EDT.
 *
 * <p>This logic was extracted into the shared {@link FormStructureReader} (from the former
 * form-read tool) so {@code get_metadata_details} / {@code delete_metadata} reuse it.</p>
 */
public class FormStructureReaderTest
{
    // ==================== resolveMdForm: pure FQN parsing (null config tolerated) ====================

    @Test
    public void testResolveMdFormRejectsTooFewParts()
    {
        assertNull(FormStructureReader.resolveMdForm(null, "CommonForm")); //$NON-NLS-1$
    }

    @Test
    public void testResolveMdFormRejectsThreeParts()
    {
        assertNull(FormStructureReader.resolveMdForm(null, "Catalog.Products.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testResolveMdFormRejectsFiveParts()
    {
        assertNull(FormStructureReader.resolveMdForm(null, "Catalog.Products.Forms.ItemForm.Extra")); //$NON-NLS-1$
    }

    @Test
    public void testResolveMdFormRejectsNonCommonFormTwoParts()
    {
        // Two-part path whose type is not a CommonForm is not a valid form path.
        assertNull(FormStructureReader.resolveMdForm(null, "Catalog.Products")); //$NON-NLS-1$
    }

    @Test
    public void testResolveMdFormRejectsWrongFormsKeyword()
    {
        assertNull(FormStructureReader.resolveMdForm(null, "Catalog.Products.NotForms.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testResolveMdFormValidShapesTolerateNullConfig()
    {
        // Well-formed paths return null (not throw) when the config is null: the shared resolver
        // short-circuits on a null configuration.
        assertNull(FormStructureReader.resolveMdForm(null, "CommonForm.MyForm")); //$NON-NLS-1$
        assertNull(FormStructureReader.resolveMdForm(null, "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
        // Russian metadata TYPE token is accepted (Справочник).
        assertNull(FormStructureReader.resolveMdForm(null,
            "Справочник.Products.Forms.ItemForm")); //$NON-NLS-1$
    }

    // ==================== nameOf / titleOf helpers ====================

    @Test
    public void testNameOfUnnamedFallback()
    {
        EObject item = newItem(MODEL.formGroup, null, 0);
        assertEquals("(unnamed)", FormStructureReader.nameOf(item)); //$NON-NLS-1$
    }

    @Test
    public void testNameOfReturnsProgrammaticName()
    {
        EObject item = newItem(MODEL.formGroup, "GroupMain", 7); //$NON-NLS-1$
        assertEquals("GroupMain", FormStructureReader.nameOf(item)); //$NON-NLS-1$
    }

    @Test
    public void testTitleOfByLanguageCode()
    {
        EObject command = newCommand("Post", "Provesti", "Post document"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // The title is keyed by language CODE — selecting "en" returns the English title, never the
        // language NAME.
        assertEquals("Post document", FormStructureReader.titleOf(command, "en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Provesti", FormStructureReader.titleOf(command, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTitleOfMissingFeatureIsEmpty()
    {
        // A bare named element with no 'title' feature yields an empty title, never null.
        EObject item = newItem(MODEL.formGroup, "G", 1); //$NON-NLS-1$
        assertEquals("", FormStructureReader.titleOf(item, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== getReferenceList helper ====================

    @Test
    public void testGetReferenceListEmptyForAbsentFeature()
    {
        EObject item = newItem(MODEL.formGroup, "G", 1); //$NON-NLS-1$
        List<EObject> attrs = FormStructureReader.getReferenceList(item, "attributes"); //$NON-NLS-1$
        assertNotNull(attrs);
        assertTrue(attrs.isEmpty());
    }

    @Test
    public void testGetReferenceListNullObject()
    {
        assertTrue(FormStructureReader.getReferenceList(null, "items").isEmpty()); //$NON-NLS-1$
    }

    // ==================== render: full structure outline + escaped tables ====================

    @Test
    public void testRenderNestedTree()
    {
        EObject form = newForm();
        EObject group = newItem(MODEL.formGroup, "MainGroup", 1); //$NON-NLS-1$
        EObject field = newItem(MODEL.formField, "Description", 2); //$NON-NLS-1$
        addItem(group, field);
        addItem(form, group);

        String md = FormStructureReader.render("Catalog.Products.Forms.ItemForm", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(md.startsWith("# Form Structure: Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
        assertTrue(md.contains("## Items")); //$NON-NLS-1$
        assertTrue(md.contains("- MainGroup (type: FormGroup, id: 1)")); //$NON-NLS-1$
        // The child field is indented one level under its container.
        assertTrue(md.contains("  - Description (type: FormField, id: 2)")); //$NON-NLS-1$
        assertTrue(md.contains("## Attributes")); //$NON-NLS-1$
        assertTrue(md.contains("## Commands")); //$NON-NLS-1$
    }

    @Test
    public void testRenderEmptyFormSections()
    {
        String md = FormStructureReader.render("CommonForm.Empty", newForm(), "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("_(no items)_")); //$NON-NLS-1$
        assertTrue(md.contains("_(no attributes)_")); //$NON-NLS-1$
        assertTrue(md.contains("_(no commands)_")); //$NON-NLS-1$
    }

    @Test
    public void testRenderAttributesAndCommandsTables()
    {
        EObject form = newForm();
        addAttribute(form, newAttribute("Object")); //$NON-NLS-1$
        addCommand(form, newCommand("Recalculate", null, "Recalculate totals")); //$NON-NLS-1$ //$NON-NLS-2$

        String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        // Attribute name appears as a table cell.
        assertTrue(md.contains("| Object |")); //$NON-NLS-1$
        // Command name + title appear as a table row.
        assertTrue(md.contains("| Recalculate | Recalculate totals |")); //$NON-NLS-1$
    }

    @Test
    public void testRenderAutoCommandBarSubtree()
    {
        // The form's auto command bar is a containment OUTSIDE 'items' - the renderer must surface it
        // (with its child buttons) or buttons created there would be invisible to clients.
        EObject form = newForm();
        EObject bar = newItem(MODEL.autoCommandBar, "FormCommandBar", -1); //$NON-NLS-1$
        EObject button = newItem(MODEL.formField, "PrintButton", 3); //$NON-NLS-1$
        addItem(bar, button);
        form.eSet(form.eClass().getEStructuralFeature("autoCommandBar"), bar); //$NON-NLS-1$

        String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(md.contains("_(no items)_")); //$NON-NLS-1$
        assertTrue(md.contains("- FormCommandBar (type: AutoCommandBar, id: -1)")); //$NON-NLS-1$
        assertTrue(md.contains("  - PrintButton (type: FormField, id: 3)")); //$NON-NLS-1$
    }

    @Test
    public void testRenderTableBarNestedAndEmptyBarSkipped()
    {
        // A table's OWN command bar (a containment outside 'items') renders nested under the table
        // when it has content; a designer-default EMPTY bar is skipped to keep the outline lean.
        EObject form = newForm();
        EObject withContent = newItem(MODEL.table, "List", 5); //$NON-NLS-1$
        EObject bar = newItem(MODEL.autoCommandBar, "ListCommandBar", 6); //$NON-NLS-1$
        addItem(bar, newItem(MODEL.formField, "ListButton", 7)); //$NON-NLS-1$
        withContent.eSet(withContent.eClass().getEStructuralFeature("autoCommandBar"), bar); //$NON-NLS-1$
        addItem(form, withContent);
        EObject withEmptyBar = newItem(MODEL.table, "Other", 8); //$NON-NLS-1$
        withEmptyBar.eSet(withEmptyBar.eClass().getEStructuralFeature("autoCommandBar"), //$NON-NLS-1$
            newItem(MODEL.autoCommandBar, "OtherCommandBar", 9)); //$NON-NLS-1$
        addItem(form, withEmptyBar);

        String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("  - ListCommandBar (type: AutoCommandBar, id: 6)")); //$NON-NLS-1$
        assertTrue(md.contains("    - ListButton (type: FormField, id: 7)")); //$NON-NLS-1$
        assertFalse(md.contains("OtherCommandBar")); //$NON-NLS-1$
    }

    @Test
    public void testRenderCommandActionHandlerColumn()
    {
        EObject form = newForm();
        EObject command = newCommand("Print", null, "Print form"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject container = new DynamicEObjectImpl(MODEL.handlerContainer);
        EObject handler = new DynamicEObjectImpl(MODEL.commandHandler);
        handler.eSet(MODEL.commandHandler.getEStructuralFeature("name"), "PrintHandler"); //$NON-NLS-1$ //$NON-NLS-2$
        container.eSet(MODEL.handlerContainer.getEStructuralFeature("handler"), handler); //$NON-NLS-1$
        command.eSet(MODEL.formCommand.getEStructuralFeature("action"), container); //$NON-NLS-1$
        addCommand(form, command);
        addCommand(form, newCommand("Unbound", null, null)); //$NON-NLS-1$

        String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        // The bound BSL procedure shows in the commands table; an unbound command shows empty.
        assertTrue(md.contains("| Print | Print form | PrintHandler |")); //$NON-NLS-1$
        assertTrue(md.contains("| Unbound |  |  |")); //$NON-NLS-1$
    }

    @Test
    public void testRenderEscapesPipeInTableCell()
    {
        EObject form = newForm();
        addCommand(form, newCommand("Cmd|Name", null, "Title|with|pipes")); //$NON-NLS-1$ //$NON-NLS-2$

        String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        // A raw '|' in a cell would break the table; the shared builder escapes it.
        assertTrue(md.contains("Cmd\\|Name")); //$NON-NLS-1$
        assertFalse(md.contains("| Cmd|Name |")); //$NON-NLS-1$
    }

    // ==================== render: enriched outline + tables + event handlers =====================

    /**
     * The detailed render of a representative form: a group (extInfo + group + child field), a field
     * (type + editMode + dataPath + hidden), a button (commandName), an attribute (main + savedData +
     * synonym) and a form-root event handler. Asserts every enrichment the slice adds.
     */
    @Test
    public void testRenderDetailedEnrichments()
    {
        EObject form = buildRichForm();

        String md = FormStructureReader.render(
            "Catalog.Products.Forms.ItemForm", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$

        // Items: per-kind extras + visibility + dataPath on the field. NON-default enum literals are
        // used (Horizontal/LabelField/Directly) so the values are genuinely authored — only explicitly
        // set enums are reported (an unset enum reads back as the metamodel default, which is noise).
        assertTrue(md.contains("- MainGroup (type: FormGroup, id: 1, " //$NON-NLS-1$
            + "group: UsualGroupExtInfo Horizontal Collapsible)")); //$NON-NLS-1$
        assertTrue(md.contains("field: type=LabelField editMode=Directly")); //$NON-NLS-1$
        assertTrue(md.contains("visible: false")); //$NON-NLS-1$
        assertTrue(md.contains("dataPath: Object.Description")); //$NON-NLS-1$
        assertTrue(md.contains("command: Post")); //$NON-NLS-1$

        // Attributes: the new Synonym / Main / SavedData columns.
        assertTrue(md.contains("| Name | Synonym | Type | Main | SavedData |")); //$NON-NLS-1$
        assertTrue(md.contains("| Goods | Goods item | ")); //$NON-NLS-1$
        assertTrue(md.contains("| true | true |")); //$NON-NLS-1$

        // Event handlers: a NEW section with the form-root handler row.
        assertTrue(md.contains("## Event handlers")); //$NON-NLS-1$
        assertTrue(md.contains("| Element | Event | Handler |")); //$NON-NLS-1$
        assertTrue(md.contains("| (form) | OnOpen | FormOnOpen |")); //$NON-NLS-1$
    }

    @Test
    public void testRenderDetailedVisibleTrueOmitted()
    {
        // A visible (default) field must NOT carry the 'visible: false' note.
        EObject form = newForm();
        EObject field = newItem(MODEL.formField, "Price", 2); //$NON-NLS-1$
        setBoolean(field, "visible", true); //$NON-NLS-1$
        addItem(form, field);

        String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(md.contains("visible: false")); //$NON-NLS-1$
    }

    @Test
    public void testRenderDetailedElementHandlerOwner()
    {
        // A handler on an ELEMENT (not the form root) is attributed to that element's name.
        EObject form = newForm();
        EObject field = newItem(MODEL.formField, "Quantity", 3); //$NON-NLS-1$
        addHandler(field, "OnChange", null, "QuantityOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        addItem(form, field);

        String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("| Quantity | OnChange | QuantityOnChange |")); //$NON-NLS-1$
    }

    @Test
    public void testRenderDetailedNoHandlers()
    {
        // With no handlers anywhere the section shows the empty placeholder.
        EObject form = newForm();
        addItem(form, newItem(MODEL.formField, "Plain", 1)); //$NON-NLS-1$

        String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("## Event handlers")); //$NON-NLS-1$
        assertTrue(md.contains("_(no event handlers)_")); //$NON-NLS-1$
    }

    /** Mirrors {@code FormStructureReader.MAX_NODES} (private): the detailed-render item-outline cap. */
    private static final int MAX_NODES = 5000;

    @Test
    public void testRenderDetailedExactlyMaxNodesNotTruncated()
    {
        // BOUNDARY (off-by-one guard): a form with EXACTLY MAX_NODES item nodes drains the budget to 0
        // while every node is still rendered, so the truncation note must NOT appear. The note is gated
        // on an explicit 'a node was dropped' flag, not on the exhausted budget.
        EObject form = newForm();
        for (int i = 0; i < MAX_NODES; i++)
        {
            addItem(form, newItem(MODEL.formField, "F" + i, i)); //$NON-NLS-1$
        }

        String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(md.contains("item outline truncated")); //$NON-NLS-1$
        // The last node IS present in the outline (nothing was dropped).
        assertTrue(md.contains("- F" + (MAX_NODES - 1) + " (")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderDetailedBeyondMaxNodesTruncated()
    {
        // A form with MAX_NODES + 1 item nodes genuinely exceeds the cap: the outline is capped and the
        // truncation note is emitted, naming the cap.
        EObject form = newForm();
        for (int i = 0; i <= MAX_NODES; i++)
        {
            addItem(form, newItem(MODEL.formField, "F" + i, i)); //$NON-NLS-1$
        }

        String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains(
            "- _(item outline truncated: more than " + MAX_NODES + " nodes)_")); //$NON-NLS-1$ //$NON-NLS-2$
        // The node past the cap is dropped from the outline.
        assertFalse(md.contains("- F" + MAX_NODES + " (")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderDetailedEnumReadsLiteralNotName()
    {
        // Pin the accessor: enumLiteralOf reads Enumerator.getName(), NOT getLiteral(). The shared
        // enums set name==literal (true of the real 1C metamodel) so they can't tell the two apart;
        // here the field's 'type' literal carries a DISTINCT name ('Vertical') vs literal ('vertical'),
        // so a future swap of getName() for getLiteral() flips the rendered token and fails this test.
        // The distinct literal is added to the SHARED 'type' enum only for this test, then removed in a
        // finally so the singleton MODEL is not mutated for any other test.
        EEnum fieldTypeEnum = (EEnum)((EAttribute)MODEL.formField.getEStructuralFeature("type")) //$NON-NLS-1$
            .getEAttributeType();
        EEnumLiteral distinct = EcoreFactory.eINSTANCE.createEEnumLiteral();
        distinct.setName("Vertical"); //$NON-NLS-1$
        distinct.setLiteral("vertical"); //$NON-NLS-1$
        distinct.setValue(fieldTypeEnum.getELiterals().size());
        fieldTypeEnum.getELiterals().add(distinct);
        try
        {
            EObject form = newForm();
            EObject field = newItem(MODEL.formField, "Mode", 1); //$NON-NLS-1$
            field.eSet(field.eClass().getEStructuralFeature("type"), distinct.getInstance()); //$NON-NLS-1$
            addItem(form, field);

            String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
            // The rendered token is the literal's NAME, never its (lower-case) literal.
            assertTrue(md.contains("field: type=Vertical")); //$NON-NLS-1$
            assertFalse(md.contains("vertical")); //$NON-NLS-1$
        }
        finally
        {
            fieldTypeEnum.getELiterals().remove(distinct);
        }
    }

    @Test
    public void testRenderDetailedEventNameByLanguageRu()
    {
        // The event name is selected by language CODE: 'ru' picks nameRu, never the English name.
        EObject form = newForm();
        addHandler(form, "OnOpen", "ПриОткрытии", "ФормаПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String mdRu = FormStructureReader.render("CommonForm.F", form, "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(mdRu.contains("ПриОткрытии")); //$NON-NLS-1$
        String mdEn = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(mdEn.contains("| (form) | OnOpen | ")); //$NON-NLS-1$
    }

    /**
     * Builds a representative form exercising every enrichment: a {@code MainGroup}
     * (extInfo Horizontal/Collapsible) containing a hidden {@code Description} field (LabelField /
     * Directly, dataPath {@code Object.Description}); a {@code Post} button bound to a metadata command; a
     * {@code Goods} attribute (main + savedData, synonym "Goods item"); and a form-root {@code OnOpen}
     * handler ({@code FormOnOpen}).
     */
    @SuppressWarnings("unchecked")
    private static EObject buildRichForm()
    {
        EObject form = newForm();

        EObject group = newItem(MODEL.formGroup, "MainGroup", 1); //$NON-NLS-1$
        setGroupExtInfo(group, "Horizontal", "Collapsible"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject field = newItem(MODEL.formField, "Description", 2); //$NON-NLS-1$
        setEnum(field, "type", "LabelField"); //$NON-NLS-1$ //$NON-NLS-2$
        setEnum(field, "editMode", "Directly"); //$NON-NLS-1$ //$NON-NLS-2$
        setBoolean(field, "visible", false); //$NON-NLS-1$
        setDataPath(field, "Object", "Description"); //$NON-NLS-1$ //$NON-NLS-2$
        addItem(group, field);
        addItem(form, group);

        EObject button = newItem(MODEL.formButton, "PostButton", 4); //$NON-NLS-1$
        button.eSet(button.eClass().getEStructuralFeature("commandName"), "Post"); //$NON-NLS-1$ //$NON-NLS-2$
        addItem(form, button);

        EObject attribute = newAttribute("Goods"); //$NON-NLS-1$
        setBoolean(attribute, "main", true); //$NON-NLS-1$
        setBoolean(attribute, "savedData", true); //$NON-NLS-1$
        EMap<String, String> title =
            (EMap<String, String>)attribute.eGet(attribute.eClass().getEStructuralFeature("title")); //$NON-NLS-1$
        title.put("en", "Goods item"); //$NON-NLS-1$ //$NON-NLS-2$
        addAttribute(form, attribute);

        addHandler(form, "OnOpen", "ПриОткрытии", //$NON-NLS-1$
            "FormOnOpen"); //$NON-NLS-1$

        return form;
    }

    // ==================== Dynamic EMF model shaped like a managed form ====================

    private static final FormLikeModel MODEL = new FormLikeModel();

    private static EObject newForm()
    {
        return new DynamicEObjectImpl(MODEL.form);
    }

    private static EObject newItem(EClass eClass, String name, int id)
    {
        EObject item = new DynamicEObjectImpl(eClass);
        if (name != null)
        {
            item.eSet(MODEL.itemName, name);
        }
        item.eSet(MODEL.itemId, Integer.valueOf(id));
        return item;
    }

    private static EObject newAttribute(String name)
    {
        EObject attribute = new DynamicEObjectImpl(MODEL.formAttribute);
        attribute.eSet(MODEL.attributeName, name);
        return attribute;
    }

    @SuppressWarnings("unchecked")
    private static EObject newCommand(String name, String titleRu, String titleEn)
    {
        EObject command = new DynamicEObjectImpl(MODEL.formCommand);
        command.eSet(MODEL.commandName, name);
        EMap<String, String> title = (EMap<String, String>)command.eGet(MODEL.commandTitle);
        if (titleRu != null)
        {
            title.put("ru", titleRu); //$NON-NLS-1$
        }
        if (titleEn != null)
        {
            title.put("en", titleEn); //$NON-NLS-1$
        }
        return command;
    }

    private static void addItem(EObject container, EObject child)
    {
        addTo(container, "items", child); //$NON-NLS-1$
    }

    private static void addAttribute(EObject form, EObject attribute)
    {
        addTo(form, "attributes", attribute); //$NON-NLS-1$
    }

    private static void addCommand(EObject form, EObject command)
    {
        addTo(form, "formCommands", command); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private static void addTo(EObject owner, String featureName, EObject child)
    {
        ((List<EObject>)owner.eGet(owner.eClass().getEStructuralFeature(featureName))).add(child);
    }

    // ---- detailed-render test scaffolding ------------------------------------------------------

    /** Sets a Boolean feature by name on an item (e.g. {@code visible}, {@code main}). */
    private static void setBoolean(EObject object, String featureName, boolean value)
    {
        object.eSet(object.eClass().getEStructuralFeature(featureName), Boolean.valueOf(value));
    }

    /** Sets an EEnum feature to a named literal, read back by the reader as that literal. */
    private static void setEnum(EObject object, String featureName, String literal)
    {
        EAttribute feature = (EAttribute)object.eClass().getEStructuralFeature(featureName);
        EEnumLiteral lit = ((EEnum)feature.getEAttributeType()).getEEnumLiteral(literal);
        object.eSet(feature, lit.getInstance());
    }

    /** Attaches a contained {@code DataPath} whose {@code segments} are the given parts. */
    @SuppressWarnings("unchecked")
    private static void setDataPath(EObject item, String... parts)
    {
        EObject dataPath = new DynamicEObjectImpl(MODEL.dataPath);
        EList<String> segments =
            (EList<String>)dataPath.eGet(MODEL.dataPath.getEStructuralFeature("segments")); //$NON-NLS-1$
        for (String part : parts)
        {
            segments.add(part);
        }
        item.eSet(item.eClass().getEStructuralFeature("dataPath"), dataPath); //$NON-NLS-1$
    }

    /** Attaches a contained {@code UsualGroupExtInfo} carrying the layout {@code group} + {@code behavior}. */
    private static void setGroupExtInfo(EObject group, String groupMode, String behavior)
    {
        EObject extInfo = new DynamicEObjectImpl(MODEL.usualGroupExtInfo);
        setEnum(extInfo, "group", groupMode); //$NON-NLS-1$
        if (behavior != null)
        {
            setEnum(extInfo, "behavior", behavior); //$NON-NLS-1$
        }
        group.eSet(group.eClass().getEStructuralFeature("extInfo"), extInfo); //$NON-NLS-1$
    }

    /** Appends an {@code EventHandler} (its BSL proc name + a contained {@code Event}) to the element. */
    private static void addHandler(EObject element, String eventName, String eventNameRu, String procName)
    {
        EObject handler = new DynamicEObjectImpl(MODEL.eventHandler);
        handler.eSet(MODEL.eventHandler.getEStructuralFeature("name"), procName); //$NON-NLS-1$
        EObject event = new DynamicEObjectImpl(MODEL.event);
        if (eventName != null)
        {
            event.eSet(MODEL.event.getEStructuralFeature("name"), eventName); //$NON-NLS-1$
        }
        if (eventNameRu != null)
        {
            event.eSet(MODEL.event.getEStructuralFeature("nameRu"), eventNameRu); //$NON-NLS-1$
        }
        handler.eSet(MODEL.eventHandler.getEStructuralFeature("event"), event); //$NON-NLS-1$
        addTo(element, "handlers", handler); //$NON-NLS-1$
    }

    /**
     * A tiny dynamic EMF metamodel reproducing the feature names the reader reads via reflection:
     * {@code items} / {@code attributes} / {@code formCommands} on the form, {@code name} / {@code id}
     * / {@code title} on items, commands and attributes. This lets the rendering and reflection helpers
     * be tested without the real {@code com._1c.g5.v8.dt.form.model} package.
     */
    private static final class FormLikeModel
    {
        final EClass formItem;
        final EClass form;
        final EClass formGroup;
        final EClass formField;
        final EClass formButton;
        final EClass formAttribute;
        final EClass formCommand;
        final EClass commandHandler;
        final EClass handlerContainer;
        final EClass autoCommandBar;
        final EClass table;
        final EClass usualGroupExtInfo;
        final EClass dataPath;
        final EClass eventHandler;
        final EClass event;

        final EAttribute itemName;
        final EAttribute itemId;
        final EAttribute attributeName;
        final EAttribute commandName;
        final EReference commandTitle;

        FormLikeModel()
        {
            EcoreFactory factory = EcoreFactory.eINSTANCE;
            EPackage pkg = factory.createEPackage();
            pkg.setName("formlike"); //$NON-NLS-1$
            pkg.setNsPrefix("formlike"); //$NON-NLS-1$
            pkg.setNsURI("http://ditrix.com/test/formlike"); //$NON-NLS-1$

            // ---- enums the detailed render reads as their literal (via Enumerator) -------------------
            EEnum groupTypeEnum = enumOf(factory, "FormGroupExtInfoType", "Vertical", "Horizontal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EEnum behaviorEnum = enumOf(factory, "UsualGroupBehavior", "Usual", "Collapsible"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EEnum fieldTypeEnum = enumOf(factory, "FormFieldType", "InputField", "LabelField"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EEnum editModeEnum = enumOf(factory, "FormFieldEditMode", "Enter", "Directly"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            // ---- supporting contained objects --------------------------------------------------------
            // DataPath-like: a 'segments' string list joined by '.' to form an item's bound path.
            dataPath = factory.createEClass();
            dataPath.setName("DataPath"); //$NON-NLS-1$
            EAttribute segments = factory.createEAttribute();
            segments.setName("segments"); //$NON-NLS-1$
            segments.setEType(EcorePackage.Literals.ESTRING);
            segments.setUpperBound(-1);
            dataPath.getEStructuralFeatures().add(segments);

            // UsualGroupExtInfo-like: a group's extInfo carrying the layout 'group' + 'behavior' enums.
            usualGroupExtInfo = factory.createEClass();
            usualGroupExtInfo.setName("UsualGroupExtInfo"); //$NON-NLS-1$
            EAttribute groupMode = factory.createEAttribute();
            groupMode.setName("group"); //$NON-NLS-1$
            groupMode.setEType(groupTypeEnum);
            usualGroupExtInfo.getEStructuralFeatures().add(groupMode);
            EAttribute behavior = factory.createEAttribute();
            behavior.setName("behavior"); //$NON-NLS-1$
            behavior.setEType(behaviorEnum);
            usualGroupExtInfo.getEStructuralFeatures().add(behavior);

            // Event-like + EventHandler-like: a handler's own 'name' (BSL proc) + single 'event' ref
            // whose 'name' (en) / 'nameRu' (ru) is the platform event name.
            event = factory.createEClass();
            event.setName("Event"); //$NON-NLS-1$
            EAttribute eventName = factory.createEAttribute();
            eventName.setName("name"); //$NON-NLS-1$
            eventName.setEType(EcorePackage.Literals.ESTRING);
            event.getEStructuralFeatures().add(eventName);
            EAttribute eventNameRu = factory.createEAttribute();
            eventNameRu.setName("nameRu"); //$NON-NLS-1$
            eventNameRu.setEType(EcorePackage.Literals.ESTRING);
            event.getEStructuralFeatures().add(eventNameRu);
            eventHandler = factory.createEClass();
            eventHandler.setName("EventHandler"); //$NON-NLS-1$
            EAttribute ehName = factory.createEAttribute();
            ehName.setName("name"); //$NON-NLS-1$
            ehName.setEType(EcorePackage.Literals.ESTRING);
            eventHandler.getEStructuralFeatures().add(ehName);
            EReference ehEvent = factory.createEReference();
            ehEvent.setName("event"); //$NON-NLS-1$
            ehEvent.setEType(event);
            ehEvent.setContainment(true);
            eventHandler.getEStructuralFeatures().add(ehEvent);

            // FormItem-like base: name + id + visible + dataPath + extInfo + handlers. Groups, fields and
            // buttons extend it, so the many-valued 'items' references can be typed to this supertype and
            // every item carries the detailed-render features (read reflectively, only when present).
            formItem = factory.createEClass();
            formItem.setName("FormItem"); //$NON-NLS-1$
            formItem.setAbstract(true);
            itemName = factory.createEAttribute();
            itemName.setName("name"); //$NON-NLS-1$
            itemName.setEType(EcorePackage.Literals.ESTRING);
            formItem.getEStructuralFeatures().add(itemName);
            itemId = factory.createEAttribute();
            itemId.setName("id"); //$NON-NLS-1$
            itemId.setEType(EcorePackage.Literals.EINT);
            formItem.getEStructuralFeatures().add(itemId);
            EAttribute itemVisible = factory.createEAttribute();
            itemVisible.setName("visible"); //$NON-NLS-1$
            itemVisible.setEType(EcorePackage.Literals.EBOOLEAN);
            itemVisible.setDefaultValueLiteral("true"); //$NON-NLS-1$
            formItem.getEStructuralFeatures().add(itemVisible);
            EReference itemDataPath = factory.createEReference();
            itemDataPath.setName("dataPath"); //$NON-NLS-1$
            itemDataPath.setEType(dataPath);
            itemDataPath.setContainment(true);
            formItem.getEStructuralFeatures().add(itemDataPath);
            EReference itemExtInfo = factory.createEReference();
            itemExtInfo.setName("extInfo"); //$NON-NLS-1$
            itemExtInfo.setEType(usualGroupExtInfo);
            itemExtInfo.setContainment(true);
            formItem.getEStructuralFeatures().add(itemExtInfo);
            formItem.getEStructuralFeatures().add(handlersReference(factory, eventHandler));

            // FormGroup-like container: a FormItem that also exposes an 'items' list.
            formGroup = factory.createEClass();
            formGroup.setName("FormGroup"); //$NON-NLS-1$
            formGroup.getESuperTypes().add(formItem);
            formGroup.getEStructuralFeatures().add(itemsReference(factory, formItem));

            // FormField-like leaf: a FormItem with 'type' + 'editMode' enums, no 'items' feature.
            formField = factory.createEClass();
            formField.setName("FormField"); //$NON-NLS-1$
            formField.getESuperTypes().add(formItem);
            EAttribute fieldType = factory.createEAttribute();
            fieldType.setName("type"); //$NON-NLS-1$
            fieldType.setEType(fieldTypeEnum);
            formField.getEStructuralFeatures().add(fieldType);
            EAttribute fieldEditMode = factory.createEAttribute();
            fieldEditMode.setName("editMode"); //$NON-NLS-1$
            fieldEditMode.setEType(editModeEnum);
            formField.getEStructuralFeatures().add(fieldEditMode);

            // Button-like leaf: a FormItem carrying the bound metadata 'commandName'. The concrete
            // form-model button EClass is named "Button" (NOT "FormButton", its platform-type name), so
            // the dynamic EClass must use that name for kindExtrasOf's eClass()-name match to fire.
            formButton = factory.createEClass();
            formButton.setName("Button"); //$NON-NLS-1$
            formButton.getESuperTypes().add(formItem);
            EAttribute buttonCommand = factory.createEAttribute();
            buttonCommand.setName("commandName"); //$NON-NLS-1$
            buttonCommand.setEType(EcorePackage.Literals.ESTRING);
            formButton.getEStructuralFeatures().add(buttonCommand);

            // FormAttribute-like: name + title (EMap by language code) + main + savedData flags.
            formAttribute = factory.createEClass();
            formAttribute.setName("FormAttribute"); //$NON-NLS-1$
            attributeName = factory.createEAttribute();
            attributeName.setName("name"); //$NON-NLS-1$
            attributeName.setEType(EcorePackage.Literals.ESTRING);
            formAttribute.getEStructuralFeatures().add(attributeName);
            EReference attributeTitle = factory.createEReference();
            attributeTitle.setName("title"); //$NON-NLS-1$
            attributeTitle.setEType(EcorePackage.Literals.ESTRING_TO_STRING_MAP_ENTRY);
            attributeTitle.setContainment(true);
            attributeTitle.setUpperBound(-1);
            formAttribute.getEStructuralFeatures().add(attributeTitle);
            EAttribute attributeMain = factory.createEAttribute();
            attributeMain.setName("main"); //$NON-NLS-1$
            attributeMain.setEType(EcorePackage.Literals.EBOOLEAN);
            formAttribute.getEStructuralFeatures().add(attributeMain);
            EAttribute attributeSavedData = factory.createEAttribute();
            attributeSavedData.setName("savedData"); //$NON-NLS-1$
            attributeSavedData.setEType(EcorePackage.Literals.EBOOLEAN);
            formAttribute.getEStructuralFeatures().add(attributeSavedData);

            // CommandHandler-like pair: the command's contained action holding the handler name.
            commandHandler = factory.createEClass();
            commandHandler.setName("CommandHandler"); //$NON-NLS-1$
            EAttribute handlerName = factory.createEAttribute();
            handlerName.setName("name"); //$NON-NLS-1$
            handlerName.setEType(EcorePackage.Literals.ESTRING);
            commandHandler.getEStructuralFeatures().add(handlerName);
            handlerContainer = factory.createEClass();
            handlerContainer.setName("FormCommandHandlerContainer"); //$NON-NLS-1$
            EReference handlerRef = factory.createEReference();
            handlerRef.setName("handler"); //$NON-NLS-1$
            handlerRef.setEType(commandHandler);
            handlerRef.setContainment(true);
            handlerContainer.getEStructuralFeatures().add(handlerRef);

            // FormCommand-like: name + title (EMap by language code) + the action containment.
            formCommand = factory.createEClass();
            formCommand.setName("FormCommand"); //$NON-NLS-1$
            commandName = factory.createEAttribute();
            commandName.setName("name"); //$NON-NLS-1$
            commandName.setEType(EcorePackage.Literals.ESTRING);
            formCommand.getEStructuralFeatures().add(commandName);
            commandTitle = factory.createEReference();
            commandTitle.setName("title"); //$NON-NLS-1$
            commandTitle.setEType(EcorePackage.Literals.ESTRING_TO_STRING_MAP_ENTRY);
            commandTitle.setContainment(true);
            commandTitle.setUpperBound(-1);
            formCommand.getEStructuralFeatures().add(commandTitle);
            EReference action = factory.createEReference();
            action.setName("action"); //$NON-NLS-1$
            action.setEType(handlerContainer);
            action.setContainment(true);
            formCommand.getEStructuralFeatures().add(action);

            // AutoCommandBar-like: a FormItem container OUTSIDE the items tree.
            autoCommandBar = factory.createEClass();
            autoCommandBar.setName("AutoCommandBar"); //$NON-NLS-1$
            autoCommandBar.getESuperTypes().add(formItem);
            autoCommandBar.getEStructuralFeatures().add(itemsReference(factory, formItem));

            // Table-like: a FormItem container with its OWN auto command bar containment.
            table = factory.createEClass();
            table.setName("Table"); //$NON-NLS-1$
            table.getESuperTypes().add(formItem);
            table.getEStructuralFeatures().add(itemsReference(factory, formItem));
            EReference tableBar = factory.createEReference();
            tableBar.setName("autoCommandBar"); //$NON-NLS-1$
            tableBar.setEType(autoCommandBar);
            tableBar.setContainment(true);
            table.getEStructuralFeatures().add(tableBar);

            // Form: items + attributes + formCommands + autoCommandBar.
            form = factory.createEClass();
            form.setName("Form"); //$NON-NLS-1$
            form.getEStructuralFeatures().add(itemsReference(factory, formItem));
            form.getEStructuralFeatures().add(
                containment(factory, "attributes", formAttribute)); //$NON-NLS-1$
            form.getEStructuralFeatures().add(
                containment(factory, "formCommands", formCommand)); //$NON-NLS-1$
            EReference barRef = factory.createEReference();
            barRef.setName("autoCommandBar"); //$NON-NLS-1$
            barRef.setEType(autoCommandBar);
            barRef.setContainment(true);
            form.getEStructuralFeatures().add(barRef);
            // The form ROOT carries its own event handlers (e.g. OnOpen / BeforeClose).
            form.getEStructuralFeatures().add(handlersReference(factory, eventHandler));

            pkg.getEClassifiers().add(groupTypeEnum);
            pkg.getEClassifiers().add(behaviorEnum);
            pkg.getEClassifiers().add(fieldTypeEnum);
            pkg.getEClassifiers().add(editModeEnum);
            pkg.getEClassifiers().add(dataPath);
            pkg.getEClassifiers().add(usualGroupExtInfo);
            pkg.getEClassifiers().add(event);
            pkg.getEClassifiers().add(eventHandler);
            pkg.getEClassifiers().add(formItem);
            pkg.getEClassifiers().add(form);
            pkg.getEClassifiers().add(formGroup);
            pkg.getEClassifiers().add(formField);
            pkg.getEClassifiers().add(formButton);
            pkg.getEClassifiers().add(formAttribute);
            pkg.getEClassifiers().add(formCommand);
            pkg.getEClassifiers().add(commandHandler);
            pkg.getEClassifiers().add(handlerContainer);
            pkg.getEClassifiers().add(autoCommandBar);
            pkg.getEClassifiers().add(table);
        }

        private static EReference itemsReference(EcoreFactory factory, EClass itemType)
        {
            return containment(factory, "items", itemType); //$NON-NLS-1$
        }

        private static EReference handlersReference(EcoreFactory factory, EClass handlerType)
        {
            return containment(factory, "handlers", handlerType); //$NON-NLS-1$
        }

        private static EEnum enumOf(EcoreFactory factory, String name, String... literals)
        {
            EEnum eEnum = factory.createEEnum();
            eEnum.setName(name);
            int value = 0;
            for (String literal : literals)
            {
                EEnumLiteral lit = factory.createEEnumLiteral();
                lit.setName(literal);
                lit.setLiteral(literal);
                lit.setValue(value++);
                eEnum.getELiterals().add(lit);
            }
            return eEnum;
        }

        private static EReference containment(EcoreFactory factory, String name, EClass type)
        {
            EReference reference = factory.createEReference();
            reference.setName(name);
            reference.setEType(type);
            reference.setContainment(true);
            reference.setUpperBound(-1);
            return reference;
        }
    }
}
