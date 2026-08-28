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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

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
        assertNull(FormStructureReader.resolveMdForm(MetadataScope.ofConfiguration(null), "CommonForm")); //$NON-NLS-1$
    }

    @Test
    public void testResolveMdFormRejectsThreeParts()
    {
        assertNull(FormStructureReader.resolveMdForm(MetadataScope.ofConfiguration(null), "Catalog.Products.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testResolveMdFormRejectsFiveParts()
    {
        assertNull(FormStructureReader.resolveMdForm(MetadataScope.ofConfiguration(null), "Catalog.Products.Forms.ItemForm.Extra")); //$NON-NLS-1$
    }

    @Test
    public void testResolveMdFormRejectsNonCommonFormTwoParts()
    {
        // Two-part path whose type is not a CommonForm is not a valid form path.
        assertNull(FormStructureReader.resolveMdForm(MetadataScope.ofConfiguration(null), "Catalog.Products")); //$NON-NLS-1$
    }

    @Test
    public void testResolveMdFormRejectsWrongFormsKeyword()
    {
        assertNull(FormStructureReader.resolveMdForm(MetadataScope.ofConfiguration(null), "Catalog.Products.NotForms.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testResolveMdFormValidShapesTolerateNullConfig()
    {
        // Well-formed paths return null (not throw) when the config is null: the shared resolver
        // short-circuits on a null configuration.
        assertNull(FormStructureReader.resolveMdForm(MetadataScope.ofConfiguration(null), "CommonForm.MyForm")); //$NON-NLS-1$
        assertNull(FormStructureReader.resolveMdForm(MetadataScope.ofConfiguration(null), "Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
        // Russian metadata TYPE token is accepted (Справочник).
        assertNull(FormStructureReader.resolveMdForm(MetadataScope.ofConfiguration(null),
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

    // ============ the handler walk's OWN bound is reported, and reported separately ============
    //
    // Two independent bounds narrow the Event-handlers section: the caller's row limit, which
    // declines COLLECTED rows, and the walk's MAX_NODES ceiling, which stops the traversal. Only
    // the first was ever announced, and it is the one that does not bite here: a cut-short walk
    // collects FEWER handlers than the limit - often none - so no row is declined, no cap note is
    // due, and a short table is indistinguishable from a complete one. In the empty case the
    // section went on to state outright that the form has no event handlers, about a form whose
    // handler-bearing elements the walk had never reached.

    /**
     * The form that reaches the ceiling: the walk visits the root and then MAX_NODES-1 children, so
     * the LAST child - the only one carrying a handler - is declined and never looked at.
     *
     * @return the form
     */
    private static EObject formWhoseLastElementIsPastTheHandlerWalkBound()
    {
        EObject form = newForm();
        for (int i = 0; i < MAX_NODES; i++)
        {
            EObject field = newItem(MODEL.formField, "F" + i, i); //$NON-NLS-1$
            if (i == MAX_NODES - 1)
            {
                addHandler(field, "OnChange", null, "LastOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            addItem(form, field);
        }
        return form;
    }

    @Test
    public void testHandlerWalkCutShortDoesNotClaimTheFormHasNoHandlers()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWhoseLastElementIsPastTheHandlerWalkBound(), "en"); //$NON-NLS-1$

        // The form HAS a handler; the walk simply never reached it. Stating its absence is the one
        // thing this section may not do without a complete walk.
        assertFalse("a cut-short walk may not state the form has no event handlers", //$NON-NLS-1$
            md.contains("_(no event handlers)_")); //$NON-NLS-1$
    }

    @Test
    public void testHandlerWalkCutShortSaysTheWalkStopped()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWhoseLastElementIsPastTheHandlerWalkBound(), "en"); //$NON-NLS-1$

        assertTrue("the bound that bit must be named: " + md, md.contains( //$NON-NLS-1$
            "the handler walk stopped after visiting " + MAX_NODES //$NON-NLS-1$
                + " elements, so elements past that point were never looked at")); //$NON-NLS-1$
    }

    @Test
    public void testHandlerWalkCutShortSaysWhatIsNotEstablished()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWhoseLastElementIsPastTheHandlerWalkBound(), "en"); //$NON-NLS-1$

        assertTrue("and it must say the question is left open, not answered: " + md, //$NON-NLS-1$
            md.contains("whether this form has event handlers is not established")); //$NON-NLS-1$
    }

    /**
     * The same bound with the table NON-empty: the root's handler was collected before the walk ran
     * out, so the table has a row, the row limit declined nothing, and the walk's own bound is the
     * only thing that narrowed the answer.
     *
     * @return the form
     */
    private static EObject formWithARootHandlerAndElementsPastTheBound()
    {
        EObject form = newForm();
        addHandler(form, "OnOpen", null, "FormOnOpen"); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < MAX_NODES; i++)
        {
            addItem(form, newItem(MODEL.formField, "F" + i, i)); //$NON-NLS-1$
        }
        return form;
    }

    @Test
    public void testHandlerWalkCutShortIsReportedUnderANonEmptyTable()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWithARootHandlerAndElementsPastTheBound(), "en"); //$NON-NLS-1$

        assertTrue("the table is short because the walk stopped, and it must say so: " + md, //$NON-NLS-1$
            md.contains("the handler walk stopped after visiting " + MAX_NODES //$NON-NLS-1$
                + " elements, so elements past that point were never looked at and their handlers " //$NON-NLS-1$
                + "are not in this table")); //$NON-NLS-1$
    }

    @Test
    public void testHandlerWalkCutShortIsReportedThoughNoRowWasDeclined()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWithARootHandlerAndElementsPastTheBound(), "en"); //$NON-NLS-1$

        // The point of the previous test: the ROW cap did not fire, so gating the walk's report on
        // it - which is what this section used to do - reports nothing exactly here.
        assertFalse("no row was declined, so the row-cap note may not appear: " + md, //$NON-NLS-1$
            md.contains("event handlers truncated: only the first")); //$NON-NLS-1$
    }

    @Test
    public void testHandlerWalkCutShortKeepsTheRowsItDidCollect()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWithARootHandlerAndElementsPastTheBound(), "en"); //$NON-NLS-1$

        assertTrue("what the walk DID reach is still reported: " + md, //$NON-NLS-1$
            md.contains("| (form) | OnOpen | FormOnOpen |")); //$NON-NLS-1$
    }

    /**
     * BOUNDARY, mirroring the item outline's: a form the walk visits ENTIRELY - the root plus
     * MAX_NODES-1 children - drains the budget to zero without declining anything, so no cut is
     * reported. Without this the report could be produced by an implementation that flags every
     * exhausted budget, which would cry truncation on a form it read completely.
     */
    @Test
    public void testExactlyTheHandlerWalkBoundIsNotReportedAsCutShort()
    {
        EObject form = newForm();
        for (int i = 0; i < MAX_NODES - 1; i++)
        {
            addItem(form, newItem(MODEL.formField, "F" + i, i)); //$NON-NLS-1$
        }

        String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nothing was declined, so no walk-cut note is due: " + md, //$NON-NLS-1$
            md.contains("the handler walk stopped")); //$NON-NLS-1$
    }

    /**
     * The positive control for the pair above: on a COMPLETE walk the plain absence sentence is
     * still what the section prints, so a reader that had lost the phrase entirely would not pass
     * {@link #testHandlerWalkCutShortDoesNotClaimTheFormHasNoHandlers}.
     */
    @Test
    public void testACompleteWalkStillStatesTheAbsencePlainly()
    {
        EObject form = newForm();
        for (int i = 0; i < MAX_NODES - 1; i++)
        {
            addItem(form, newItem(MODEL.formField, "F" + i, i)); //$NON-NLS-1$
        }

        String md = FormStructureReader.render("CommonForm.F", form, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a walk that saw the whole form may state the absence: " + md, //$NON-NLS-1$
            md.contains("_(no event handlers)_")); //$NON-NLS-1$
    }

    // ============ the ROWS the handler walk keeps are bounded too, and separately ============
    //
    // MAX_NODES counts ELEMENTS. A row is a bound event, and one element carries as many of them
    // as it has events bound, so a form can hold hundreds of thousands of rows inside a budget of
    // 5000 elements - on one element or spread over them. The walk used to append every one of
    // them to a list and the caller's limit was applied afterwards, at the render, so the rows
    // past it were built, held for the whole walk and then thrown away. That is the same
    // accumulation the budget already bounds on the pending stack: bounded there for what the walk
    // QUEUES, unbounded here for what it KEEPS.
    //
    // The two bounds stay two statements. "There were more rows than are shown" is the row cap;
    // "the element budget ran out" is the walk's. Neither may be inferred from the other, which is
    // why the walk carries on visiting elements after the rows are full.

    /**
     * @param handlers how many bound events to give the one element
     * @return an element carrying that many handlers, named {@code H0..H(n-1)}
     */
    private static EObject elementWithHandlers(int handlers)
    {
        EObject group = newItem(MODEL.formGroup, "G", 1); //$NON-NLS-1$
        for (int i = 0; i < handlers; i++)
        {
            addHandler(group, "OnChange", null, "H" + i); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return group;
    }

    /**
     * @param rows the accumulator to read
     * @return the handler procedure names it kept, in order
     */
    private static List<String> keptHandlerNames(FormStructureReader.HandlerRows rows)
    {
        List<String> names = new ArrayList<>();
        for (String[] row : rows.kept())
        {
            names.add(row[2]);
        }
        return names;
    }

    @Test
    public void testTheHandlerWalkKeepsNoMoreRowsThanTheCapAllows()
    {
        FormStructureReader.HandlerRows rows = new FormStructureReader.HandlerRows(3);

        FormStructureReader.collectHandlers(elementWithHandlers(10), "G", "en", rows, //$NON-NLS-1$ //$NON-NLS-2$
            new int[] {MAX_NODES}, new boolean[] {false}, new ArrayDeque<>());

        assertEquals("one element's handlers are not bounded by the element budget, so the cap " //$NON-NLS-1$
            + "has to bite as they are found", //$NON-NLS-1$
            List.of("H0", "H1", "H2"), keptHandlerNames(rows)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * And the flag, in its own method: rows that are never kept leave no trace in the numbers, so a
     * walk that simply stopped keeping them would render a cut table that reads as a whole one.
     * JUnit stops a method at its first failed assertion, which is why this is not a line above.
     */
    @Test
    public void testTheHandlerWalkReportsThatItDeclinedRows()
    {
        FormStructureReader.HandlerRows rows = new FormStructureReader.HandlerRows(3);

        FormStructureReader.collectHandlers(elementWithHandlers(10), "G", "en", rows, //$NON-NLS-1$ //$NON-NLS-2$
            new int[] {MAX_NODES}, new boolean[] {false}, new ArrayDeque<>());

        assertTrue("rows were found and not kept, and that must be reported", rows.declined()); //$NON-NLS-1$
    }

    /**
     * The positive control for the flag: a form whose handlers all fit declines nothing. Without
     * this an accumulator that raised the flag unconditionally would satisfy the pin above, and
     * every complete table would be announced as truncated.
     */
    @Test
    public void testTheHandlerWalkDeclinesNothingWhenEveryRowFits()
    {
        FormStructureReader.HandlerRows rows = new FormStructureReader.HandlerRows(3);

        FormStructureReader.collectHandlers(elementWithHandlers(3), "G", "en", rows, //$NON-NLS-1$ //$NON-NLS-2$
            new int[] {MAX_NODES}, new boolean[] {false}, new ArrayDeque<>());

        assertFalse("exactly as many rows as the cap allows is a complete table: " //$NON-NLS-1$
            + keptHandlerNames(rows), rows.declined());
    }

    /**
     * The half that a cap alone would break: the walk must keep VISITING elements after its rows
     * are full, because whether the element budget ran out is a different question and only a walk
     * that spends the budget can answer it.
     * <p>
     * A CHAIN rather than a flat form, and that is what makes this a pin: on a flat form the very
     * first push already finds more children than the budget can reach and raises the flag there,
     * so a walk that stopped after the root would still look cut short. Here the budget only runs
     * out three elements down, which a walk that stopped at a full accumulator never reaches.
     */
    @Test
    public void testTheHandlerWalkFinishesItsElementBudgetAfterTheRowsAreFull()
    {
        EObject root = newItem(MODEL.formGroup, "R", 1); //$NON-NLS-1$
        addHandler(root, "OnChange", null, "R1"); //$NON-NLS-1$ //$NON-NLS-2$
        addHandler(root, "OnOpen", null, "R2"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject parent = root;
        for (int i = 0; i < 4; i++)
        {
            EObject group = newItem(MODEL.formGroup, "G" + i, 10 + i); //$NON-NLS-1$
            addItem(parent, group);
            parent = group;
        }
        boolean[] cutShort = {false};

        FormStructureReader.collectHandlers(root, "R", "en", //$NON-NLS-1$ //$NON-NLS-2$
            new FormStructureReader.HandlerRows(1), new int[] {3}, cutShort, new ArrayDeque<>());

        assertTrue("the rows fill on the root, and the element budget only runs out three " //$NON-NLS-1$
            + "elements below it - a walk that stopped at the full accumulator would report a " //$NON-NLS-1$
            + "form it never finished looking at as complete", cutShort[0]); //$NON-NLS-1$
    }

    /**
     * A form whose handlers exceed a row cap of 1 AND whose elements exceed the walk's budget, as a
     * chain so the walk only meets its budget at the bottom.
     *
     * @return the form
     */
    private static EObject formPastBothTheRowCapAndTheWalkBound()
    {
        EObject form = newForm();
        addHandler(form, "OnOpen", null, "FormOnOpen"); //$NON-NLS-1$ //$NON-NLS-2$
        addHandler(form, "BeforeClose", null, "FormBeforeClose"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject parent = form;
        for (int i = 0; i < MAX_NODES; i++)
        {
            EObject group = newItem(MODEL.formGroup, "G" + i, i); //$NON-NLS-1$
            addItem(parent, group);
            parent = group;
        }
        return form;
    }

    @Test
    public void testARowCapAndACutShortWalkAreBothReported()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formPastBothTheRowCapAndTheWalkBound(), "en", 1); //$NON-NLS-1$

        assertTrue("a row was declined, so the cap note is due: " + md, //$NON-NLS-1$
            md.contains("event handlers truncated: only the first 1")); //$NON-NLS-1$
    }

    @Test
    public void testACutShortWalkIsStillReportedWhenTheRowCapAlsoBit()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formPastBothTheRowCapAndTheWalkBound(), "en", 1); //$NON-NLS-1$

        assertTrue("and the walk's own bound is a separate statement, printed too: " + md, //$NON-NLS-1$
            md.contains("the handler walk stopped after visiting " + MAX_NODES)); //$NON-NLS-1$
    }

    /**
     * The row the cap DID keep is still rendered, so a cap that dropped everything - or one applied
     * before the first row - would not pass here.
     */
    @Test
    public void testTheRowsWithinTheCapAreStillRendered()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formPastBothTheRowCapAndTheWalkBound(), "en", 1); //$NON-NLS-1$

        assertTrue("the first row is what the cap kept: " + md, //$NON-NLS-1$
            md.contains("| (form) | OnOpen | FormOnOpen |")); //$NON-NLS-1$
    }


    // ============= both form walks are bounded by VISITS, so neither may recurse =============
    //
    // MAX_NODES caps how many elements a walk LOOKS AT, and that is not a cap on how DEEP it goes:
    // on a chain of nested elements the two are the same number. A walk that re-entered itself once
    // per element therefore stood thousands of frames deep before the budget could decline anything,
    // and the failure was not a truncated table - StackOverflowError is an Error, GetComparisonNodeTool
    // catches RuntimeException, and the MCP request came back with no result at all.
    //
    // There are TWO such walks over a form, and the item outline is the one whose depth looked
    // bounded from the outside: the caller's rowLimit caps it, and the comparison report hands down
    // a small one. get_metadata_details does not go through the comparison report - it calls the
    // three-argument render, which passes MAX_NODES - so the outline had the same 5000-frame reach
    // the handler walk had, on a tool that does not catch Error either.

    /**
     * One less than the walk's own budget, so the traversal reaches the BOTTOM of the chain instead
     * of stopping on the cap - the point being the depth it survives, not the cap it honours.
     */
    private static final int CHAIN_DEPTH = MAX_NODES - 1;

    /**
     * The stack the deep walk is given. Small on purpose: the depth the walk can reach is itself
     * bounded by MAX_NODES, so a recursive walk cannot be made to overflow a default stack by
     * feeding it a bigger form - the only dial left is the stack. A traversal whose stack use does
     * not grow with the form fits in this whatever the form looks like.
     */
    private static final int WALK_STACK_BYTES = 256 * 1024;

    /**
     * Renders through the THREE-argument entry point - the one {@code get_metadata_details} calls,
     * which hands down MAX_NODES as the row limit - on a thread with a deliberately small stack.
     *
     * @param form the form model
     * @return the rendered document
     * @throws Throwable whatever the render threw
     */
    private static String renderOnASmallStack(EObject form) throws Throwable
    {
        return onASmallStack(() -> FormStructureReader.render("CommonForm.F", form, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Renders with an explicit row limit on a thread with a deliberately small stack.
     *
     * @param form the form model
     * @param rowLimit the caller's row limit
     * @return the rendered document
     * @throws Throwable whatever the render threw
     */
    private static String renderOnASmallStack(EObject form, int rowLimit) throws Throwable
    {
        return onASmallStack(
            () -> FormStructureReader.render("CommonForm.F", form, "en", rowLimit)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Runs one render on a thread with a deliberately small stack, and reports what the walk did
     * with it.
     *
     * @param render the render to run
     * @return the rendered document
     * @throws Throwable whatever the render threw - a StackOverflowError included, which is the
     *             whole point: it is an Error, so a test that let it be swallowed would pass over
     *             the defect
     */
    private static String onASmallStack(Supplier<String> render) throws Throwable
    {
        Object[] outcome = new Object[2];
        Runnable walk = () -> {
            try
            {
                outcome[0] = render.get();
            }
            catch (Throwable t) // NOSONAR an Error is exactly what this test is about
            {
                outcome[1] = t;
            }
        };
        Thread walker = new Thread(null, walk, "form-structure-deep-walk", WALK_STACK_BYTES); //$NON-NLS-1$
        walker.start();
        walker.join();
        if (outcome[1] != null)
        {
            throw (Throwable)outcome[1];
        }
        return (String)outcome[0];
    }

    /**
     * Nests {@link #CHAIN_DEPTH} groups {@code G0..G(CHAIN_DEPTH-1)} one inside the next under a new
     * form, each one level deeper than the last.
     *
     * @param form the form to nest the chain under
     * @return the DEEPEST group, so a caller can hang something at the bottom of the chain
     */
    private static EObject deepChainUnder(EObject form)
    {
        EObject parent = form;
        for (int i = 0; i < CHAIN_DEPTH; i++)
        {
            EObject group = newItem(MODEL.formGroup, "G" + i, i); //$NON-NLS-1$
            addItem(parent, group);
            parent = group;
        }
        return parent;
    }

    /**
     * A chain of nested groups as deep as the walk's budget allows it to go, with the ONE handler in
     * the form at the very bottom - so a walk that came back without that row did not reach it.
     *
     * @return the form
     */
    private static EObject formWhoseOnlyHandlerIsAtTheBottomOfADeepChain()
    {
        EObject form = newForm();
        addHandler(deepChainUnder(form), "OnChange", null, "DeepestOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        return form;
    }

    /**
     * The same chain with no handler on it: what this one is read for is the OUTLINE it renders,
     * whose deepest line is one the walk only reaches by getting all the way down.
     *
     * @return the form
     */
    private static EObject formNestedAsDeepAsTheBudgetAllows()
    {
        EObject form = newForm();
        deepChainUnder(form);
        return form;
    }

    /**
     * The handler walk's depth pin. The row limit of 1 no longer has the meaning it was given when
     * it was written - the item outline does not recurse any more, so it no longer has to be held
     * out of the way - but it is kept, for the reason it is now: a limit of 1 stops the outline walk
     * after a single node, so what this measures is the handler walk and nothing else, and a failure
     * here names ONE of the two walks. The outline's own depth is pinned separately, at the budget
     * that the tool actually hands it.
     *
     * @throws Throwable when the walk ran the thread out of stack
     */
    @Test
    public void testTheHandlerWalkCrossesAChainDeeperThanItsThreadStack() throws Throwable
    {
        String md = renderOnASmallStack(formWhoseOnlyHandlerIsAtTheBottomOfADeepChain(), 1);

        assertTrue("the walk must reach the bottom of the chain, not the bottom of the stack: " + md, //$NON-NLS-1$
            md.contains("| G" + (CHAIN_DEPTH - 1) + " | OnChange | DeepestOnChange |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The same walk, this time asked whether it thinks it was cut short. It was not: the chain is one
     * element shorter than the budget, so a report of truncation here would mean the budget branch
     * fired on a form the walk had read completely.
     *
     * @throws Throwable when the walk ran the thread out of stack
     */
    @Test
    public void testTheDeepWalkDoesNotReportItselfCutShort() throws Throwable
    {
        String md = renderOnASmallStack(formWhoseOnlyHandlerIsAtTheBottomOfADeepChain(), 1);

        assertFalse("nothing was declined, so no walk-cut note is due: " + md, //$NON-NLS-1$
            md.contains("the handler walk stopped")); //$NON-NLS-1$
    }

    // ==================== the ORDER of the walk is what the table prints ====================
    //
    // Depth-first pre-order: an element's own handler rows, then the whole subtree under each 'items'
    // child in list order, then the subtree under each singular containment in the order
    // SINGULAR_ITEM_CONTAINMENTS declares. A stack that pushed siblings in forward order would
    // reverse every one of those three, and nothing else in this suite would notice.

    /**
     * A form built so that each of the three orderings is separately observable: siblings A/T/B at
     * the root, a subtree under A, and a Table carrying an items child, an auto command bar and a
     * context menu at once.
     *
     * @return the form
     */
    private static EObject formWhoseHandlersSpellOutTheWalkOrder()
    {
        EObject form = newForm();
        addHandler(form, "OnOpen", null, "FormOnOpen"); //$NON-NLS-1$ //$NON-NLS-2$

        EObject groupA = newItem(MODEL.formGroup, "A", 1); //$NON-NLS-1$
        addHandler(groupA, "OnChange", null, "AOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject a1 = newItem(MODEL.formField, "A1", 2); //$NON-NLS-1$
        addHandler(a1, "OnChange", null, "A1OnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        addItem(groupA, a1);
        EObject a2 = newItem(MODEL.formField, "A2", 3); //$NON-NLS-1$
        addHandler(a2, "OnChange", null, "A2OnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        addItem(groupA, a2);
        addItem(form, groupA);

        EObject table = newItem(MODEL.table, "T", 4); //$NON-NLS-1$
        addHandler(table, "OnChange", null, "TOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject t1 = newItem(MODEL.formField, "T1", 5); //$NON-NLS-1$
        addHandler(t1, "OnChange", null, "T1OnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        addItem(table, t1);
        EObject tableBar = newItem(MODEL.autoCommandBar, "TBar", 6); //$NON-NLS-1$
        addHandler(tableBar, "OnChange", null, "TBarOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        table.eSet(table.eClass().getEStructuralFeature("autoCommandBar"), tableBar); //$NON-NLS-1$
        EObject tableMenu = newItem(MODEL.formGroup, "TMenu", 7); //$NON-NLS-1$
        addHandler(tableMenu, "OnChange", null, "TMenuOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        table.eSet(table.eClass().getEStructuralFeature("contextMenu"), tableMenu); //$NON-NLS-1$
        addItem(form, table);

        EObject fieldB = newItem(MODEL.formField, "B", 8); //$NON-NLS-1$
        addHandler(fieldB, "OnChange", null, "BOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        addItem(form, fieldB);

        EObject formBar = newItem(MODEL.autoCommandBar, "FBar", 9); //$NON-NLS-1$
        addHandler(formBar, "OnChange", null, "FBarOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        form.eSet(form.eClass().getEStructuralFeature("autoCommandBar"), formBar); //$NON-NLS-1$

        return form;
    }

    /**
     * The whole table, as one literal block. A pin on membership would survive every reordering; the
     * order IS the observable, so the order is what is written down.
     */
    @Test
    public void testTheHandlerTableIsInTheWalksDepthFirstPreOrder()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWhoseHandlersSpellOutTheWalkOrder(), "en"); //$NON-NLS-1$

        assertTrue("the rows must come out in the order the walk visits the elements: " + md, //$NON-NLS-1$
            md.contains("| (form) | OnOpen | FormOnOpen |\n" //$NON-NLS-1$
                + "| A | OnChange | AOnChange |\n" //$NON-NLS-1$
                + "| A1 | OnChange | A1OnChange |\n" //$NON-NLS-1$
                + "| A2 | OnChange | A2OnChange |\n" //$NON-NLS-1$
                + "| T | OnChange | TOnChange |\n" //$NON-NLS-1$
                + "| T1 | OnChange | T1OnChange |\n" //$NON-NLS-1$
                + "| TBar | OnChange | TBarOnChange |\n" //$NON-NLS-1$
                + "| TMenu | OnChange | TMenuOnChange |\n" //$NON-NLS-1$
                + "| B | OnChange | BOnChange |\n" //$NON-NLS-1$
                + "| FBar | OnChange | FBarOnChange |\n")); //$NON-NLS-1$
    }

    /**
     * Its own literal for the one ordering a chain cannot show: two SINGULAR containments on the same
     * element must come out in the order {@code SINGULAR_ITEM_CONTAINMENTS} declares them, and a
     * table with only an auto command bar would pass whichever way they were pushed.
     */
    @Test
    public void testTwoSingularContainmentsOnOneElementKeepTheirDeclarationOrder()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWhoseHandlersSpellOutTheWalkOrder(), "en"); //$NON-NLS-1$

        assertTrue("autoCommandBar is declared before contextMenu, so it is walked first: " + md, //$NON-NLS-1$
            md.indexOf("| TBar | OnChange | TBarOnChange |") //$NON-NLS-1$
                < md.indexOf("| TMenu | OnChange | TMenuOnChange |")); //$NON-NLS-1$
    }

    /**
     * And its own literal for the ordering a single-level form cannot show: a child's whole SUBTREE
     * is walked before the next sibling is reached, so A2 - two levels down under the first sibling -
     * precedes B, which is the second sibling.
     */
    @Test
    public void testASubtreeIsFinishedBeforeTheNextSiblingIsReached()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWhoseHandlersSpellOutTheWalkOrder(), "en"); //$NON-NLS-1$

        assertTrue("depth-first: everything under A comes before B: " + md, //$NON-NLS-1$
            md.indexOf("| A2 | OnChange | A2OnChange |") //$NON-NLS-1$
                < md.indexOf("| B | OnChange | BOnChange |")); //$NON-NLS-1$
    }

    // ==================== the ITEM outline, at the depth the tool actually allows ====================

    /**
     * The deepest line the chain produces, indented to its own level - {@code G0} is a top-level item
     * at depth 0, so {@code G(n)} sits at depth {@code n}.
     *
     * @return the expected outline line for the deepest group
     */
    private static String deepestOutlineLine()
    {
        return "  ".repeat(CHAIN_DEPTH - 1) + "- G" + (CHAIN_DEPTH - 1) //$NON-NLS-1$ //$NON-NLS-2$
            + " (type: FormGroup, id: " + (CHAIN_DEPTH - 1) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The outline's depth pin, taken on the path that actually allows the depth: the three-argument
     * render, which is what {@code get_metadata_details} calls and which hands the outline the whole
     * MAX_NODES budget. A walk that re-entered itself once per element runs the thread out of stack
     * here and never returns a document at all.
     *
     * @throws Throwable when the walk ran the thread out of stack
     */
    @Test
    public void testTheItemOutlineCrossesAChainDeeperThanItsThreadStack() throws Throwable
    {
        String md = renderOnASmallStack(formNestedAsDeepAsTheBudgetAllows());

        // The document is megabytes wide (the indentation alone is quadratic in the depth), so the
        // failure message carries its size rather than the document.
        assertTrue("the outline must reach the bottom of the chain, not the bottom of the stack" //$NON-NLS-1$
            + " (rendered " + md.length() + " chars)", md.contains(deepestOutlineLine())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The same render, asked whether it thinks it was truncated. It was not: the chain is one element
     * shorter than the budget, so a truncation note here would mean the budget branch fired on a form
     * the outline had rendered completely.
     *
     * @throws Throwable when the walk ran the thread out of stack
     */
    @Test
    public void testTheDeepItemOutlineDoesNotReportItselfTruncated() throws Throwable
    {
        String md = renderOnASmallStack(formNestedAsDeepAsTheBudgetAllows());

        assertFalse("nothing was dropped, so no truncation note is due (rendered " //$NON-NLS-1$
            + md.length() + " chars)", md.contains("item outline truncated")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ============ the ORDER and the INDENTATION of the outline walk are what it prints ============
    //
    // Depth-first pre-order: an element's own line, then the whole subtree under each 'items' child in
    // list order, then the subtree under each singular containment in the order
    // SINGULAR_ITEM_CONTAINMENTS declares - each child one level deeper than its parent. A stack that
    // pushed siblings forward would reverse all three of those orderings; a stack that derived depth
    // from anything but its own entry would mis-indent every line after a descent.

    /**
     * A form built so that each ordering is separately observable in the outline: the form's own
     * command bar, three root siblings G/T/B, a subtree under G, and a Table carrying an items child,
     * an auto command bar and a context menu at once. Every singular containment is given a child of
     * its own, because a childless, titleless one is a designer default the outline skips.
     *
     * @return the form
     */
    private static EObject formWhoseOutlineSpellsOutTheWalkOrder()
    {
        EObject form = newForm();

        EObject group = newItem(MODEL.formGroup, "G", 10); //$NON-NLS-1$
        addItem(group, newItem(MODEL.formField, "G1", 11)); //$NON-NLS-1$
        addItem(group, newItem(MODEL.formField, "G2", 12)); //$NON-NLS-1$
        addItem(form, group);

        EObject table = newItem(MODEL.table, "T", 20); //$NON-NLS-1$
        addItem(table, newItem(MODEL.formField, "T1", 21)); //$NON-NLS-1$
        EObject tableBar = newItem(MODEL.autoCommandBar, "TBar", 22); //$NON-NLS-1$
        addItem(tableBar, newItem(MODEL.formField, "TBarButton", 23)); //$NON-NLS-1$
        table.eSet(table.eClass().getEStructuralFeature("autoCommandBar"), tableBar); //$NON-NLS-1$
        EObject tableMenu = newItem(MODEL.formGroup, "TMenu", 24); //$NON-NLS-1$
        addItem(tableMenu, newItem(MODEL.formField, "TMenuItem", 25)); //$NON-NLS-1$
        table.eSet(table.eClass().getEStructuralFeature("contextMenu"), tableMenu); //$NON-NLS-1$
        addItem(form, table);

        addItem(form, newItem(MODEL.formField, "B", 30)); //$NON-NLS-1$

        EObject formBar = newItem(MODEL.autoCommandBar, "FBar", 90); //$NON-NLS-1$
        addItem(formBar, newItem(MODEL.formField, "FBarButton", 91)); //$NON-NLS-1$
        form.eSet(form.eClass().getEStructuralFeature("autoCommandBar"), formBar); //$NON-NLS-1$

        return form;
    }

    /**
     * The whole outline, as one literal block. A pin on membership would survive every reordering and
     * every indentation slip; the order and the indentation ARE the observable, so both are written
     * down.
     */
    @Test
    public void testTheItemOutlineIsInTheWalksDepthFirstPreOrder()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWhoseOutlineSpellsOutTheWalkOrder(), "en"); //$NON-NLS-1$

        assertTrue("the lines must come out in the order the walk visits the elements: " + md, //$NON-NLS-1$
            md.contains("- FBar (type: AutoCommandBar, id: 90)\n" //$NON-NLS-1$
                + "  - FBarButton (type: FormField, id: 91)\n" //$NON-NLS-1$
                + "- G (type: FormGroup, id: 10)\n" //$NON-NLS-1$
                + "  - G1 (type: FormField, id: 11)\n" //$NON-NLS-1$
                + "  - G2 (type: FormField, id: 12)\n" //$NON-NLS-1$
                + "- T (type: Table, id: 20)\n" //$NON-NLS-1$
                + "  - T1 (type: FormField, id: 21)\n" //$NON-NLS-1$
                + "  - TBar (type: AutoCommandBar, id: 22)\n" //$NON-NLS-1$
                + "    - TBarButton (type: FormField, id: 23)\n" //$NON-NLS-1$
                + "  - TMenu (type: FormGroup, id: 24)\n" //$NON-NLS-1$
                + "    - TMenuItem (type: FormField, id: 25)\n" //$NON-NLS-1$
                + "- B (type: FormField, id: 30)\n")); //$NON-NLS-1$
    }

    /**
     * Its own literal for the thing a forward-pushing stack breaks and a correct one does not: two
     * SIBLINGS under one parent, in the order the list holds them. Written separately because JUnit
     * stops a method at its first failed assertion, so a pin sharing a method with another is only
     * ever exercised while that other one passes.
     */
    @Test
    public void testOutlineSiblingsKeepTheirListOrder()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWhoseOutlineSpellsOutTheWalkOrder(), "en"); //$NON-NLS-1$

        assertTrue("G1 is added before G2, so it is printed first: " + md, //$NON-NLS-1$
            md.indexOf("  - G1 (type: FormField, id: 11)") //$NON-NLS-1$
                < md.indexOf("  - G2 (type: FormField, id: 12)")); //$NON-NLS-1$
    }

    /**
     * And its own literal for the ordering a single-level form cannot show: a child's whole SUBTREE is
     * printed before the next sibling is reached, so TBarButton - two levels under the table, at the
     * bottom of the FIRST of its two singular containments - precedes TMenu, the second one.
     *
     * <p>The pair is deliberately a NESTED one. The form root's own top-level items are walked by
     * {@code renderItems}, which calls the walk once per item, so their relative order is decided
     * outside the walk and no defect in it could disturb them; a pin taken across two root siblings
     * would therefore hold whatever the walk did.</p>
     */
    @Test
    public void testAnOutlineSubtreeIsFinishedBeforeTheNextSiblingIsReached()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWhoseOutlineSpellsOutTheWalkOrder(), "en"); //$NON-NLS-1$

        assertTrue("depth-first: everything under TBar comes before TMenu: " + md, //$NON-NLS-1$
            md.indexOf("    - TBarButton (type: FormField, id: 23)") //$NON-NLS-1$
                < md.indexOf("  - TMenu (type: FormGroup, id: 24)")); //$NON-NLS-1$
    }

    /**
     * The singular containments come AFTER the items children of the same element, and among
     * themselves in the order {@code SINGULAR_ITEM_CONTAINMENTS} declares - a table with only an auto
     * command bar would pass whichever way the two were pushed.
     */
    @Test
    public void testOutlineSingularContainmentsFollowTheItemsAndKeepTheirDeclarationOrder()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWhoseOutlineSpellsOutTheWalkOrder(), "en"); //$NON-NLS-1$

        assertTrue("items first, then autoCommandBar, then contextMenu: " + md, //$NON-NLS-1$
            md.indexOf("  - T1 (type: FormField, id: 21)") //$NON-NLS-1$
                < md.indexOf("  - TBar (type: AutoCommandBar, id: 22)") //$NON-NLS-1$
                && md.indexOf("  - TBar (type: AutoCommandBar, id: 22)") //$NON-NLS-1$
                    < md.indexOf("  - TMenu (type: FormGroup, id: 24)")); //$NON-NLS-1$
    }

    /**
     * The indentation pin proper. Every line before this one descends or stays level, so an
     * implementation that carried a single running depth would still get them right; what it cannot
     * get right is the RETURN - {@code B} is a root sibling printed immediately after a line two
     * levels deep, and its indentation comes from the entry that pushed it, not from the line above.
     */
    @Test
    public void testOutlineDepthIsCarriedPerNodeNotDerivedFromThePreviousLine()
    {
        String md = FormStructureReader.render("CommonForm.F", //$NON-NLS-1$
            formWhoseOutlineSpellsOutTheWalkOrder(), "en"); //$NON-NLS-1$

        assertTrue("B returns to column 0 straight after a depth-2 line: " + md, //$NON-NLS-1$
            md.contains("    - TMenuItem (type: FormField, id: 25)\n" //$NON-NLS-1$
                + "- B (type: FormField, id: 30)\n")); //$NON-NLS-1$
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

    // ============ the advertised budget bounds the PENDING work, not only the visited work ============
    //
    // The finding: the de-recursion pushed EVERY child before the outer loop could look at the budget
    // again, so one element with far more direct children than the budget allocated a PendingItem (or,
    // in the handler walk, a PendingElement) for each of them even at limit=1. The recursion had
    // RETURNED at the budget instead, so the conversion had traded stack depth for heap. Every pin
    // below asserts the BOUND on the structure, never a duration - and the walks are driven through
    // their package-private overloads because the bound is a statement about a structure that is
    // otherwise a local, and a test reading only the rendered outline would be pinning an allocation
    // it cannot see.

    /** Direct children of one element, chosen far past any budget these tests hand the walks. */
    private static final int CHILDREN_FAR_PAST_THE_BUDGET = 5000;

    /** The budget these tests run the walks under: small, so "bounded by it" is a real assertion. */
    private static final int SMALL_BUDGET = 4;

    /**
     * An {@link ArrayDeque} that remembers the largest size it ever held.
     *
     * <p>{@code addFirst} is the one override needed: {@code ArrayDeque.push} delegates to it, so a
     * walk that pushes and a walk that adds are both measured.</p>
     *
     * @param <E> the element type
     */
    private static final class MeasuringDeque<E>
        extends ArrayDeque<E>
    {
        private static final long serialVersionUID = 1L;

        /** The largest size this deque ever held. */
        int peak;

        @Override
        public void addFirst(E element)
        {
            super.addFirst(element);
            peak = Math.max(peak, size());
        }
    }

    /**
     * @param children how many direct children to give the element
     * @return an element carrying that many childless items
     */
    private static EObject elementWithChildren(int children)
    {
        EObject group = newItem(MODEL.formGroup, "G", 1); //$NON-NLS-1$
        for (int i = 0; i < children; i++)
        {
            addItem(group, newItem(MODEL.formField, "C" + i, 100 + i)); //$NON-NLS-1$
        }
        return group;
    }

    @Test
    public void testTheOutlineWalksPendingStackIsBoundedByItsBudget()
    {
        MeasuringDeque<FormStructureReader.PendingItem> pending = new MeasuringDeque<>();

        FormStructureReader.appendItem(new StringBuilder(),
            elementWithChildren(CHILDREN_FAR_PAST_THE_BUDGET), 0, "en", //$NON-NLS-1$
            new int[] {SMALL_BUDGET}, new boolean[] {false}, pending);

        assertTrue("the walk may not hold more pending items than its budget can ever visit - " //$NON-NLS-1$
            + "peaked at " + pending.peak + " on an element with " + CHILDREN_FAR_PAST_THE_BUDGET //$NON-NLS-1$ //$NON-NLS-2$
            + " children under a budget of " + SMALL_BUDGET, pending.peak <= SMALL_BUDGET); //$NON-NLS-1$
    }

    @Test
    public void testTheHandlerWalksPendingStackIsBoundedByItsBudget()
    {
        MeasuringDeque<FormStructureReader.PendingElement> pending = new MeasuringDeque<>();

        FormStructureReader.collectHandlers(elementWithChildren(CHILDREN_FAR_PAST_THE_BUDGET),
            "Form", "en", new FormStructureReader.HandlerRows(FormStructureReader.MAX_NODES), //$NON-NLS-1$ //$NON-NLS-2$
            new int[] {SMALL_BUDGET}, new boolean[] {false}, pending);

        assertTrue("the walk may not hold more pending elements than its budget can ever visit - " //$NON-NLS-1$
            + "peaked at " + pending.peak + " on an element with " + CHILDREN_FAR_PAST_THE_BUDGET //$NON-NLS-1$ //$NON-NLS-2$
            + " children under a budget of " + SMALL_BUDGET, pending.peak <= SMALL_BUDGET); //$NON-NLS-1$
    }

    /**
     * The half a bound alone would let through: WHICH elements come out, and which one trips the cut.
     * Children the budget cannot reach are now left off the stack instead of being pushed, popped and
     * declined - so the emitted lines, their order and the truncation flag must be exactly what the
     * pushing-everything walk produced.
     */
    @Test
    public void testTheOutlineEmitsExactlyTheElementsTheBudgetCanReach()
    {
        StringBuilder sb = new StringBuilder();
        boolean[] truncated = {false};

        FormStructureReader.appendItem(sb, elementWithChildren(10), 0, "en", //$NON-NLS-1$
            new int[] {SMALL_BUDGET}, truncated, new ArrayDeque<>());

        assertEquals("the budget is spent on the element and the first three of its children, " //$NON-NLS-1$
            + "in list order", //$NON-NLS-1$
            "- G (type: FormGroup, id: 1)\n" //$NON-NLS-1$
                + "  - C0 (type: FormField, id: 100)\n" //$NON-NLS-1$
                + "  - C1 (type: FormField, id: 101)\n" //$NON-NLS-1$
                + "  - C2 (type: FormField, id: 102)\n", //$NON-NLS-1$
            sb.toString());
    }

    /**
     * And the flag itself, in its own method: the elements that used to raise it at the pop are no
     * longer popped, so a walk that simply stopped pushing them would report a form it had cut as
     * complete. JUnit stops a method at its first failed assertion, which is why this is not an
     * extra line on the pin above.
     */
    @Test
    public void testTheOutlineStillReportsTheChildrenItLeftOffTheStack()
    {
        boolean[] truncated = {false};

        FormStructureReader.appendItem(new StringBuilder(), elementWithChildren(10), 0, "en", //$NON-NLS-1$
            new int[] {SMALL_BUDGET}, truncated, new ArrayDeque<>());

        assertTrue("children the budget could not reach were dropped, and that must be reported", //$NON-NLS-1$
            truncated[0]);
    }

    /**
     * The same for the handler walk: the rows are the ones the elements the budget reaches carry, in
     * the walk's own order.
     */
    @Test
    public void testTheHandlerWalkCollectsExactlyTheRowsTheBudgetCanReach()
    {
        EObject group = newItem(MODEL.formGroup, "G", 1); //$NON-NLS-1$
        for (int i = 0; i < 10; i++)
        {
            EObject child = newItem(MODEL.formField, "C" + i, 100 + i); //$NON-NLS-1$
            addHandler(child, "OnChange", null, "C" + i + "OnChange"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            addItem(group, child);
        }
        FormStructureReader.HandlerRows rows =
            new FormStructureReader.HandlerRows(FormStructureReader.MAX_NODES);

        FormStructureReader.collectHandlers(group, "G", "en", rows, //$NON-NLS-1$ //$NON-NLS-2$
            new int[] {SMALL_BUDGET}, new boolean[] {false}, new ArrayDeque<>());

        List<String> handlers = new ArrayList<>();
        for (String[] row : rows.kept())
        {
            handlers.add(row[2]);
        }
        assertEquals("the budget reaches the group and its first three children", //$NON-NLS-1$
            List.of("C0OnChange", "C1OnChange", "C2OnChange"), handlers); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A singular containment is a child like any other as far as the room goes: it is VISITED after
     * the {@code items} children, so it is the first thing dropped when there is room for only some
     * of them. A walk that counted only {@code items} would push it past the budget's reach.
     */
    @Test
    public void testTheOutlineCountsSingularContainmentsAgainstTheSameRoom()
    {
        EObject table = newItem(MODEL.table, "T", 1); //$NON-NLS-1$
        addItem(table, newItem(MODEL.formField, "T1", 2)); //$NON-NLS-1$
        addItem(table, newItem(MODEL.formField, "T2", 3)); //$NON-NLS-1$
        EObject bar = newItem(MODEL.autoCommandBar, "TBar", 4); //$NON-NLS-1$
        addItem(bar, newItem(MODEL.formField, "TBarButton", 5)); //$NON-NLS-1$
        table.eSet(table.eClass().getEStructuralFeature("autoCommandBar"), bar); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();

        FormStructureReader.appendItem(sb, table, 0, "en", new int[] {3}, //$NON-NLS-1$
            new boolean[] {false}, new ArrayDeque<>());

        assertEquals("room for two children goes to the two items; the command bar is visited " //$NON-NLS-1$
            + "after them and is therefore the one dropped", //$NON-NLS-1$
            "- T (type: Table, id: 1)\n" //$NON-NLS-1$
                + "  - T1 (type: FormField, id: 2)\n" //$NON-NLS-1$
                + "  - T2 (type: FormField, id: 3)\n", //$NON-NLS-1$
            sb.toString());
    }

    /**
     * The other direction, and the one an over-eager trim breaks: an entry an ANCESTOR left on the
     * stack is popped after the current element's whole subtree, but the budget may still reach it,
     * and dropping it would lose an element the walk had every right to show.
     * <p>
     * {@code B} is the pin. It is pushed by the root, sits UNDER {@code A} on the stack for the whole
     * of {@code A}'s subtree, and is still within the budget when its turn comes.
     */
    @Test
    public void testTheOutlineKeepsAncestorSiblingsTheBudgetCanStillReach()
    {
        EObject root = newItem(MODEL.formGroup, "R", 1); //$NON-NLS-1$
        EObject a = newItem(MODEL.formGroup, "A", 2); //$NON-NLS-1$
        addItem(a, newItem(MODEL.formField, "A0", 3)); //$NON-NLS-1$
        addItem(root, a);
        addItem(root, newItem(MODEL.formField, "B", 4)); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        boolean[] truncated = {false};

        FormStructureReader.appendItem(sb, root, 0, "en", new int[] {SMALL_BUDGET}, truncated, //$NON-NLS-1$
            new ArrayDeque<>());

        assertEquals("the budget covers every element, so every element is shown", //$NON-NLS-1$
            "- R (type: FormGroup, id: 1)\n" //$NON-NLS-1$
                + "  - A (type: FormGroup, id: 2)\n" //$NON-NLS-1$
                + "    - A0 (type: FormField, id: 3)\n" //$NON-NLS-1$
                + "  - B (type: FormField, id: 4)\n", //$NON-NLS-1$
            sb.toString());
    }

    /**
     * And the flag on that same walk, in its own method: nothing was dropped, so a walk that trimmed
     * an ancestor's sibling and then rendered it anyway - or one that raised the flag while dropping
     * nothing - is caught here rather than by the text above.
     */
    @Test
    public void testTheOutlineReportsNoTruncationWhenTheBudgetCoversEverything()
    {
        EObject root = newItem(MODEL.formGroup, "R", 1); //$NON-NLS-1$
        EObject a = newItem(MODEL.formGroup, "A", 2); //$NON-NLS-1$
        addItem(a, newItem(MODEL.formField, "A0", 3)); //$NON-NLS-1$
        addItem(root, a);
        addItem(root, newItem(MODEL.formField, "B", 4)); //$NON-NLS-1$
        boolean[] truncated = {false};

        FormStructureReader.appendItem(new StringBuilder(), root, 0, "en", //$NON-NLS-1$
            new int[] {SMALL_BUDGET}, truncated, new ArrayDeque<>());

        assertFalse("no element was declined, so nothing may be reported as truncated", //$NON-NLS-1$
            truncated[0]);
    }

    /**
     * The trim proper, which is the half that makes the bound a bound: capping each push alone still
     * lets one push per level accumulate on the stack, so the entries an ancestor left behind that
     * the budget can no longer reach are removed from the BOTTOM.
     * <p>
     * {@code A}'s ten children take the whole of the remaining budget, so {@code B}, {@code C} and
     * {@code D} - pushed by the root, lying under them - can never be visited. The old walk pushed,
     * popped and declined them; this one drops them, and the OUTPUT must be the same either way.
     */
    @Test
    public void testTheOutlineTrimsPendingSiblingsTheBudgetCanNoLongerReach()
    {
        EObject root = newItem(MODEL.formGroup, "R", 1); //$NON-NLS-1$
        EObject a = newItem(MODEL.formGroup, "A", 2); //$NON-NLS-1$
        for (int i = 0; i < 10; i++)
        {
            addItem(a, newItem(MODEL.formField, "A" + i, 100 + i)); //$NON-NLS-1$
        }
        addItem(root, a);
        addItem(root, newItem(MODEL.formField, "B", 3)); //$NON-NLS-1$
        addItem(root, newItem(MODEL.formField, "C", 4)); //$NON-NLS-1$
        addItem(root, newItem(MODEL.formField, "D", 5)); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();

        FormStructureReader.appendItem(sb, root, 0, "en", new int[] {5}, new boolean[] {false}, //$NON-NLS-1$
            new ArrayDeque<>());

        assertEquals("A's subtree spends the budget before B, C and D can be reached", //$NON-NLS-1$
            "- R (type: FormGroup, id: 1)\n" //$NON-NLS-1$
                + "  - A (type: FormGroup, id: 2)\n" //$NON-NLS-1$
                + "    - A0 (type: FormField, id: 100)\n" //$NON-NLS-1$
                + "    - A1 (type: FormField, id: 101)\n" //$NON-NLS-1$
                + "    - A2 (type: FormField, id: 102)\n", //$NON-NLS-1$
            sb.toString());
    }

    /**
     * The handler walk's own no-over-trim pin: {@code B} carries a handler, sits under {@code A} on
     * the stack for the whole of {@code A}'s subtree, and is still within the budget.
     */
    @Test
    public void testTheHandlerWalkKeepsAncestorSiblingsTheBudgetCanStillReach()
    {
        EObject root = newItem(MODEL.formGroup, "R", 1); //$NON-NLS-1$
        EObject a = newItem(MODEL.formGroup, "A", 2); //$NON-NLS-1$
        EObject a0 = newItem(MODEL.formField, "A0", 3); //$NON-NLS-1$
        addHandler(a0, "OnChange", null, "A0OnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        addItem(a, a0);
        addItem(root, a);
        EObject b = newItem(MODEL.formField, "B", 4); //$NON-NLS-1$
        addHandler(b, "OnChange", null, "BOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        addItem(root, b);
        FormStructureReader.HandlerRows rows =
            new FormStructureReader.HandlerRows(FormStructureReader.MAX_NODES);

        FormStructureReader.collectHandlers(root, "R", "en", rows, new int[] {SMALL_BUDGET}, //$NON-NLS-1$ //$NON-NLS-2$
            new boolean[] {false}, new ArrayDeque<>());

        List<String> handlers = new ArrayList<>();
        for (String[] row : rows.kept())
        {
            handlers.add(row[2]);
        }
        assertEquals("the budget covers every element, so every handler is collected", //$NON-NLS-1$
            List.of("A0OnChange", "BOnChange"), handlers); //$NON-NLS-1$ //$NON-NLS-2$
    }

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
            // A SECOND singular item containment on the same element, so that the order the walk
            // takes them in is observable at all: with one of them present every push order looks
            // alike. Left unset by every other fixture here, so nothing else changes.
            EReference tableMenu = factory.createEReference();
            tableMenu.setName("contextMenu"); //$NON-NLS-1$
            tableMenu.setEType(formGroup);
            tableMenu.setContainment(true);
            table.getEStructuralFeatures().add(tableMenu);

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
