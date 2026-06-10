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

import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
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
        final EClass formAttribute;
        final EClass formCommand;
        final EClass commandHandler;
        final EClass handlerContainer;
        final EClass autoCommandBar;

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

            // FormItem-like base: name + id. Both groups and fields extend it, so the many-valued
            // 'items' references can be typed to this common supertype.
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

            // FormGroup-like container: a FormItem that also exposes an 'items' list.
            formGroup = factory.createEClass();
            formGroup.setName("FormGroup"); //$NON-NLS-1$
            formGroup.getESuperTypes().add(formItem);
            formGroup.getEStructuralFeatures().add(itemsReference(factory, formItem));

            // FormField-like leaf: a FormItem with no 'items' feature.
            formField = factory.createEClass();
            formField.setName("FormField"); //$NON-NLS-1$
            formField.getESuperTypes().add(formItem);

            // FormAttribute-like: name only.
            formAttribute = factory.createEClass();
            formAttribute.setName("FormAttribute"); //$NON-NLS-1$
            attributeName = factory.createEAttribute();
            attributeName.setName("name"); //$NON-NLS-1$
            attributeName.setEType(EcorePackage.Literals.ESTRING);
            formAttribute.getEStructuralFeatures().add(attributeName);

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

            pkg.getEClassifiers().add(formItem);
            pkg.getEClassifiers().add(form);
            pkg.getEClassifiers().add(formGroup);
            pkg.getEClassifiers().add(formField);
            pkg.getEClassifiers().add(formAttribute);
            pkg.getEClassifiers().add(formCommand);
            pkg.getEClassifiers().add(commandHandler);
            pkg.getEClassifiers().add(handlerContainer);
            pkg.getEClassifiers().add(autoCommandBar);
        }

        private static EReference itemsReference(EcoreFactory factory, EClass itemType)
        {
            return containment(factory, "items", itemType); //$NON-NLS-1$
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
