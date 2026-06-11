/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormMemberRef;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.Kind;

/**
 * Tests the pure, model-independent logic of {@link FormElementWriter}: the bilingual kind-token map,
 * the form-member FQN parser, and the reflective EMF write path (button/command creation, the
 * AutoCommandBar parent, the command Action handler) against a dynamic EMF model shaped like a managed
 * form. The behaviour on the real {@code com._1c.g5.v8.dt.form.model} package is covered by the e2e
 * suite against a live form.
 *
 * <p>Russian tokens are built from code points (independently of the writer's own construction) so
 * the assertion verifies the real Cyrillic mapping, not a round-trip of the same literal.</p>
 */
public class FormElementWriterTest
{
    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    @Test
    public void testKindForEnglishTokens()
    {
        assertEquals(Kind.ATTRIBUTE, FormElementWriter.kindForToken("Attribute")); //$NON-NLS-1$
        assertEquals(Kind.ATTRIBUTE, FormElementWriter.kindForToken("attributes")); //$NON-NLS-1$
        assertEquals(Kind.COMMAND, FormElementWriter.kindForToken("Command")); //$NON-NLS-1$
        assertEquals(Kind.GROUP, FormElementWriter.kindForToken("group")); //$NON-NLS-1$
        assertEquals(Kind.DECORATION, FormElementWriter.kindForToken("Decoration")); //$NON-NLS-1$
        assertEquals(Kind.FIELD, FormElementWriter.kindForToken("Field")); //$NON-NLS-1$
        assertEquals(Kind.BUTTON, FormElementWriter.kindForToken("Button")); //$NON-NLS-1$
    }

    @Test
    public void testKindForRussianTokens()
    {
        // rekvizit -> ATTRIBUTE
        assertEquals(Kind.ATTRIBUTE, FormElementWriter.kindForToken(
            fromCp(0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442)));
        // komanda -> COMMAND
        assertEquals(Kind.COMMAND, FormElementWriter.kindForToken(
            fromCp(0x043a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x0430)));
        // gruppa -> GROUP
        assertEquals(Kind.GROUP, FormElementWriter.kindForToken(
            fromCp(0x0433, 0x0440, 0x0443, 0x043f, 0x043f, 0x0430)));
        // dekoraciya -> DECORATION
        assertEquals(Kind.DECORATION, FormElementWriter.kindForToken(
            fromCp(0x0434, 0x0435, 0x043a, 0x043e, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f)));
        // pole -> FIELD
        assertEquals(Kind.FIELD, FormElementWriter.kindForToken(fromCp(0x043f, 0x043e, 0x043b, 0x0435)));
        // knopka -> BUTTON
        assertEquals(Kind.BUTTON, FormElementWriter.kindForToken(
            fromCp(0x043a, 0x043d, 0x043e, 0x043f, 0x043a, 0x0430)));
    }

    @Test
    public void testKindForUnknownAndNull()
    {
        assertNull(FormElementWriter.kindForToken("Nonsense")); //$NON-NLS-1$
        assertNull(FormElementWriter.kindForToken("Table")); // not a supported form kind yet //$NON-NLS-1$
        assertNull(FormElementWriter.kindForToken(null));
    }

    @Test
    public void testParseManagedFormMember()
    {
        FormMemberRef ref = FormElementWriter.parse("Catalog.Products.Form.ItemForm.Command.Refresh"); //$NON-NLS-1$
        assertNotNull(ref);
        // The form path is normalized to the 'forms' shape resolveMdForm expects.
        assertEquals("Catalog.Products.forms.ItemForm", ref.formPath); //$NON-NLS-1$
        assertEquals("Command", ref.kindToken); //$NON-NLS-1$
        assertEquals("Refresh", ref.name); //$NON-NLS-1$
    }

    @Test
    public void testParseManagedFormMemberRussianToken()
    {
        // "Форма" (forma) as the form token is accepted and normalized to 'forms'.
        String fqn = "Catalog.Products." + fromCp(0x0444, 0x043e, 0x0440, 0x043c, 0x0430) //$NON-NLS-1$
            + ".ItemForm.Attribute.A"; //$NON-NLS-1$
        FormMemberRef ref = FormElementWriter.parse(fqn);
        assertNotNull(ref);
        assertEquals("Catalog.Products.forms.ItemForm", ref.formPath); //$NON-NLS-1$
        assertEquals("Attribute", ref.kindToken); //$NON-NLS-1$
        assertEquals("A", ref.name); //$NON-NLS-1$
    }

    @Test
    public void testIsHandlerToken()
    {
        assertEquals(Boolean.TRUE, Boolean.valueOf(FormElementWriter.isHandlerToken("Handler"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, Boolean.valueOf(FormElementWriter.isHandlerToken("handler"))); //$NON-NLS-1$
        // obrabotchik -> handler
        assertEquals(Boolean.TRUE, Boolean.valueOf(FormElementWriter.isHandlerToken(
            fromCp(0x043e, 0x0431, 0x0440, 0x0430, 0x0431, 0x043e, 0x0442, 0x0447, 0x0438, 0x043a))));
        assertEquals(Boolean.FALSE, Boolean.valueOf(FormElementWriter.isHandlerToken("Command"))); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, Boolean.valueOf(FormElementWriter.isHandlerToken(null)));
        // a Handler token is NOT a member Kind (it routes to the handler path, not createMember)
        assertNull(FormElementWriter.kindForToken("Handler")); //$NON-NLS-1$
    }

    @Test
    public void testParseHandlerFqnRoutesAsHandler()
    {
        // Form-level handler: leaf is the event name; the token routes to the handler path.
        FormMemberRef ref = FormElementWriter.parse("Catalog.Products.Form.ItemForm.Handler.OnOpen"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("Catalog.Products.forms.ItemForm", ref.formPath); //$NON-NLS-1$
        assertEquals("Handler", ref.kindToken); //$NON-NLS-1$
        assertEquals("OnOpen", ref.name); //$NON-NLS-1$
        // A form-level handler is NOT item-level.
        assertNull(ref.itemName);
        assertEquals(Boolean.FALSE, Boolean.valueOf(ref.isItemLevel()));
    }

    @Test
    public void testParseItemLevelHandlerManagedForm()
    {
        // Item-level handler: ItemKind.ItemName.Handler.Event (the leaf is the event, the item carries
        // the owning element name).
        FormMemberRef ref =
            FormElementWriter.parse("Catalog.Products.Form.ItemForm.Field.Price.Handler.OnChange"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("Catalog.Products.forms.ItemForm", ref.formPath); //$NON-NLS-1$
        assertEquals("Handler", ref.kindToken); //$NON-NLS-1$
        assertEquals("OnChange", ref.name); //$NON-NLS-1$
        assertEquals("Field", ref.itemKindToken); //$NON-NLS-1$
        assertEquals("Price", ref.itemName); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, Boolean.valueOf(ref.isItemLevel()));
    }

    @Test
    public void testParseItemLevelHandlerCommonForm()
    {
        FormMemberRef ref =
            FormElementWriter.parse("CommonForm.MyForm.Field.Price.Handler.OnChange"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("CommonForm.MyForm", ref.formPath); //$NON-NLS-1$
        assertEquals("Handler", ref.kindToken); //$NON-NLS-1$
        assertEquals("OnChange", ref.name); //$NON-NLS-1$
        assertEquals("Field", ref.itemKindToken); //$NON-NLS-1$
        assertEquals("Price", ref.itemName); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, Boolean.valueOf(ref.isItemLevel()));
    }

    @Test
    public void testParseItemLevelNonHandlerReturnsNull()
    {
        // A 4-token remainder whose third token is NOT a handler token is not a recognized form member.
        assertNull(FormElementWriter.parse("Catalog.Products.Form.ItemForm.Field.Price.Command.X")); //$NON-NLS-1$
        // A 3-token remainder (odd length) is not a recognized form member either.
        assertNull(FormElementWriter.parse("Catalog.Products.Form.ItemForm.Field.Price.Handler")); //$NON-NLS-1$
    }

    @Test
    public void testParseCommonFormMember()
    {
        FormMemberRef ref = FormElementWriter.parse("CommonForm.MyForm.Attribute.Field1"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("CommonForm.MyForm", ref.formPath); //$NON-NLS-1$
        assertEquals("Attribute", ref.kindToken); //$NON-NLS-1$
        assertEquals("Field1", ref.name); //$NON-NLS-1$
    }

    @Test
    public void testParseFormPathManagedAndCommon()
    {
        // A managed-form FQN (Type.Object.Form.FormName) normalizes to the 'forms' shape resolveMdForm
        // expects; the form token is bilingual.
        assertEquals("Catalog.Products.forms.ItemForm", //$NON-NLS-1$
            FormElementWriter.parseFormPath("Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
        assertEquals("Catalog.Products.forms.ItemForm", //$NON-NLS-1$
            FormElementWriter.parseFormPath("Catalog.Products.Forms.ItemForm")); //$NON-NLS-1$
        // Russian "Форма" (forma) form token.
        String ru = "Catalog.Products." + fromCp(0x0444, 0x043e, 0x0440, 0x043c, 0x0430) + ".ItemForm"; //$NON-NLS-1$
        assertEquals("Catalog.Products.forms.ItemForm", FormElementWriter.parseFormPath(ru)); //$NON-NLS-1$
        // A CommonForm (2 parts) IS a form.
        assertEquals("CommonForm.MyForm", FormElementWriter.parseFormPath("CommonForm.MyForm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testParseFormPathRejectsNonForm()
    {
        // A plain top object is NOT a form FQN (must fall through to the normal object path).
        assertNull(FormElementWriter.parseFormPath("Catalog.Products")); //$NON-NLS-1$
        // A 4-part mdclass member (no form token at position 2) is not a form FQN.
        assertNull(FormElementWriter.parseFormPath("Catalog.Products.Attribute.Weight")); //$NON-NLS-1$
        // A nested member FQN is not a form FQN.
        assertNull(FormElementWriter.parseFormPath("Catalog.Products.TabularSection.Lines.Attribute.Qty")); //$NON-NLS-1$
        assertNull(FormElementWriter.parseFormPath(null));
    }

    @Test
    public void testParseNonFormFqnReturnsNull()
    {
        // A plain mdclass member (no form token at position 2) is NOT a form member.
        assertNull(FormElementWriter.parse("Catalog.Products.Attribute.Weight")); //$NON-NLS-1$
        assertNull(FormElementWriter.parse("Catalog.Products.TabularSection.Lines.Attribute.Qty")); //$NON-NLS-1$
        // A top object / too-short FQN is not a form member.
        assertNull(FormElementWriter.parse("Catalog.Products")); //$NON-NLS-1$
        assertNull(FormElementWriter.parse(null));
    }

    // ==================== reflective write path (dynamic form-like EMF model) ====================

    @Test
    public void testCreateCommandSetsUseAndCurrentRowUse()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        EObject command = FormElementWriter.findFormCommand(form, "Print"); //$NON-NLS-1$
        assertNotNull(command);
        // The platform factory's defaults: use=AdjustableBoolean(common=true), currentRowUse=Auto.
        EObject use = (EObject)command.eGet(feature(command, "use")); //$NON-NLS-1$
        assertNotNull("a created command must carry its 'use' AdjustableBoolean", use); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, use.eGet(feature(use, "common"))); //$NON-NLS-1$
        assertEquals("Auto", literalOf(command, "currentRowUse")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCreateButtonAtRootIsEnabledUsualButton()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        String[] createdKind = new String[1];
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "PrintButton", null, "Print", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, createdKind));
        assertEquals("Button", createdKind[0]); //$NON-NLS-1$
        EObject button = FormElementWriter.findFormItem(form, "PrintButton"); //$NON-NLS-1$
        assertNotNull(button);
        // Issue #138 bug 3: the model default of 'enabled' is FALSE - a created button must be
        // explicitly enabled (and visible), like a designer-created one.
        assertEquals(Boolean.TRUE, button.eGet(feature(button, "enabled"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, button.eGet(feature(button, "visible"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, button.eGet(feature(button, "commandUniqueness"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, button.eGet(feature(button, "autoMaxWidth"))); //$NON-NLS-1$
        assertEquals("UsualButton", literalOf(button, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("UserCmds", literalOf(button, "placementArea")); //$NON-NLS-1$ //$NON-NLS-2$
        EObject userVisible = (EObject)button.eGet(feature(button, "userVisible")); //$NON-NLS-1$
        assertNotNull(userVisible);
        assertEquals(Boolean.TRUE, userVisible.eGet(feature(userVisible, "common"))); //$NON-NLS-1$
    }

    @Test
    public void testCreateButtonInAutoCommandBar()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        // Issue #138 bug 2: 'AutoCommandBar' addresses the form's command bar (a containment OUTSIDE
        // the items tree).
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "PrintButton", //$NON-NLS-1$
            "AutoCommandBar", "Print", null, null, false, null)); //$NON-NLS-1$ //$NON-NLS-2$
        EObject bar = (EObject)form.eGet(feature(form, "autoCommandBar")); //$NON-NLS-1$
        List<?> barItems = (List<?>)bar.eGet(feature(bar, "items")); //$NON-NLS-1$
        assertEquals(1, barItems.size());
        EObject button = (EObject)barItems.get(0);
        assertEquals("PrintButton", button.eGet(feature(button, "name"))); //$NON-NLS-1$ //$NON-NLS-2$
        // Inside a command bar the platform allows only command-bar buttons.
        assertEquals("CommandBarButton", literalOf(button, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, button.eGet(feature(button, "enabled"))); //$NON-NLS-1$
        // The bar's subtree is part of the form-wide item namespace: the button is findable and a
        // duplicate name is rejected.
        assertNotNull(FormElementWriter.findFormItem(form, "PrintButton")); //$NON-NLS-1$
        String dup = FormElementWriter.createMember(form, Kind.BUTTON, "PrintButton", null, "Print", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null);
        assertNotNull(dup);
        assertTrue(dup.contains("already exists")); //$NON-NLS-1$
    }

    @Test
    public void testCreateButtonParentToleratesDottedPathAndChildItems()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        // The reported parent shapes: 'Form.X.AutoCommandBar' and '...AutoCommandBar.ChildItems'.
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "B1", //$NON-NLS-1$
            "Form.MyForm.AutoCommandBar", "Print", null, null, false, null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "B2", //$NON-NLS-1$
            "Form.MyForm.AutoCommandBar.ChildItems", "Print", null, null, false, null)); //$NON-NLS-1$ //$NON-NLS-2$
        EObject bar = (EObject)form.eGet(feature(form, "autoCommandBar")); //$NON-NLS-1$
        assertEquals(2, ((List<?>)bar.eGet(feature(bar, "items"))).size()); //$NON-NLS-1$
    }

    @Test
    public void testFormPathPrefixAlwaysResolvesTheFormBar()
    {
        // 'Form.X.AutoCommandBar' must resolve the FORM's bar even when an ITEM named X exists
        // (here a table with its OWN bar): the segment before the bar in a form path is the form
        // name, which legitimately may coincide with an item name.
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        EObject table = newObject(MODEL.table);
        table.eSet(feature(table, "name"), "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject tableBar = newObject(MODEL.autoCommandBar);
        tableBar.eSet(feature(tableBar, "name"), "MyFormCommandBar"); //$NON-NLS-1$ //$NON-NLS-2$
        table.eSet(feature(table, "autoCommandBar"), tableBar); //$NON-NLS-1$
        addTo(form, "items", table); //$NON-NLS-1$
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "B1", //$NON-NLS-1$
            "Form.MyForm.AutoCommandBar", "Print", null, null, false, null)); //$NON-NLS-1$ //$NON-NLS-2$
        EObject formBar = (EObject)form.eGet(feature(form, "autoCommandBar")); //$NON-NLS-1$
        assertEquals(1, ((List<?>)formBar.eGet(feature(formBar, "items"))).size()); //$NON-NLS-1$
        assertEquals(0, ((List<?>)tableBar.eGet(feature(tableBar, "items"))).size()); //$NON-NLS-1$
        // Without the form token the owner probe targets the named item's OWN bar.
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "B2", //$NON-NLS-1$
            "MyForm.AutoCommandBar", "Print", null, null, false, null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, ((List<?>)tableBar.eGet(feature(tableBar, "items"))).size()); //$NON-NLS-1$
    }

    @Test
    public void testOwnerWithoutBarFallsBackToTheFormBar()
    {
        // 'SomeGroup.AutoCommandBar' where the group has no bar of its own resolves the form's bar
        // rather than failing.
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        EObject group = newObject(MODEL.formGroup);
        group.eSet(feature(group, "name"), "SomeGroup"); //$NON-NLS-1$ //$NON-NLS-2$
        addTo(form, "items", group); //$NON-NLS-1$
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "B1", //$NON-NLS-1$
            "SomeGroup.AutoCommandBar", "Print", null, null, false, null)); //$NON-NLS-1$ //$NON-NLS-2$
        EObject formBar = (EObject)form.eGet(feature(form, "autoCommandBar")); //$NON-NLS-1$
        assertEquals(1, ((List<?>)formBar.eGet(feature(formBar, "items"))).size()); //$NON-NLS-1$
    }

    @Test
    public void testCreateButtonInPopupGroupIsCommandBarButton()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        // A group typed Popup hosts command-bar buttons (the platform's isCommandBarButtonSupport).
        EObject group = newObject(MODEL.formGroup);
        group.eSet(feature(group, "name"), "Menu"); //$NON-NLS-1$ //$NON-NLS-2$
        setLiteral(group, "type", "Popup"); //$NON-NLS-1$ //$NON-NLS-2$
        addTo(form, "items", group); //$NON-NLS-1$
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "MenuButton", "Menu", "Print", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            null, null, false, null));
        EObject button = FormElementWriter.findFormItem(form, "MenuButton"); //$NON-NLS-1$
        assertEquals("CommandBarButton", literalOf(button, "type")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCreateButtonUnknownParentError()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        String err = FormElementWriter.createMember(form, Kind.BUTTON, "B", "NoSuchParent", "Print", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            null, null, false, null);
        assertNotNull(err);
        assertTrue(err.contains("NoSuchParent")); //$NON-NLS-1$
        assertTrue(err.contains("AutoCommandBar")); // the error advertises the bar token //$NON-NLS-1$
    }

    @Test
    public void testCreateCommandActionHandler()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        EObject command = FormElementWriter.findFormCommand(form, "Print"); //$NON-NLS-1$
        // Issue #138 bug 1: ...Command.Print.Handler.Action binds the command's action; the BSL
        // procedure name defaults to the COMMAND name (the EDT UI suggestion).
        String[] createdKind = new String[1];
        assertNull(FormElementWriter.createHandler(command, "Action", null, null, "en", createdKind)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommandHandler", createdKind[0]); //$NON-NLS-1$
        EObject action = (EObject)command.eGet(feature(command, "action")); //$NON-NLS-1$
        assertNotNull(action);
        EObject handler = (EObject)action.eGet(feature(action, "handler")); //$NON-NLS-1$
        assertNotNull(handler);
        assertEquals("Print", handler.eGet(feature(handler, "name"))); //$NON-NLS-1$ //$NON-NLS-2$
        // The bound handler resolves for delete_metadata (the action containment is the target).
        assertEquals(action, FormElementWriter.findFormHandler(command, "Action")); //$NON-NLS-1$
        // A second Action on the same command is rejected.
        String dup = FormElementWriter.createHandler(command, "Action", null, null, "en", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(dup);
        assertTrue(dup.contains("already exists")); //$NON-NLS-1$
    }

    @Test
    public void testCreateCommandActionExplicitProcedureAndRussianToken()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        EObject command = FormElementWriter.findFormCommand(form, "Print"); //$NON-NLS-1$
        // Russian leaf 'Действие' (Dejstvie) + an explicit 'procedure' property value.
        String ruAction = fromCp(0x0414, 0x0435, 0x0439, 0x0441, 0x0442, 0x0432, 0x0438, 0x0435);
        assertNull(FormElementWriter.createHandler(command, ruAction, "PrintHandler", null, "ru", null)); //$NON-NLS-1$ //$NON-NLS-2$
        EObject action = (EObject)command.eGet(feature(command, "action")); //$NON-NLS-1$
        EObject handler = (EObject)action.eGet(feature(action, "handler")); //$NON-NLS-1$
        assertEquals("PrintHandler", handler.eGet(feature(handler, "name"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCreateCommandActionWrongEventListsAction()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        EObject command = FormElementWriter.findFormCommand(form, "Print"); //$NON-NLS-1$
        String err = FormElementWriter.createHandler(command, "OnChange", null, null, "en", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(err);
        // The advisory lists the single available command "event".
        assertTrue(err.contains("Available events: Action")); //$NON-NLS-1$
    }

    @Test
    public void testResolveHandlerContainerByKind()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "PrintButton", null, "Print", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        // ...Command.Print.Handler.Action resolves the COMMAND (not an items-tree lookup).
        FormMemberRef commandRef =
            FormElementWriter.parse("CommonForm.F.Command.Print.Handler.Action"); //$NON-NLS-1$
        assertEquals(FormElementWriter.findFormCommand(form, "Print"), //$NON-NLS-1$
            FormElementWriter.resolveHandlerContainer(form, commandRef));
        // An item kind still resolves through the items tree.
        FormMemberRef itemRef =
            FormElementWriter.parse("CommonForm.F.Button.PrintButton.Handler.Click"); //$NON-NLS-1$
        assertEquals(FormElementWriter.findFormItem(form, "PrintButton"), //$NON-NLS-1$
            FormElementWriter.resolveHandlerContainer(form, itemRef));
        // A form-level ref resolves to the form root itself.
        FormMemberRef formRef = FormElementWriter.parse("CommonForm.F.Handler.OnOpen"); //$NON-NLS-1$
        assertEquals(form, FormElementWriter.resolveHandlerContainer(form, formRef));
        // A missing owner resolves to null (the caller reports not-found).
        FormMemberRef missingRef =
            FormElementWriter.parse("CommonForm.F.Command.NoSuch.Handler.Action"); //$NON-NLS-1$
        assertNull(FormElementWriter.resolveHandlerContainer(form, missingRef));
    }

    @Test
    public void testCreateFieldDesignerDefaults()
    {
        EObject form = newForm();
        EObject attribute = newObject(MODEL.formAttribute);
        attribute.eSet(feature(attribute, "name"), "Price"); //$NON-NLS-1$ //$NON-NLS-2$
        addTo(form, "attributes", attribute); //$NON-NLS-1$
        assertNull(FormElementWriter.createMember(form, Kind.FIELD, "PriceField", null, "Price", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        EObject field = FormElementWriter.findFormItem(form, "PriceField"); //$NON-NLS-1$
        assertNotNull(field);
        // The designer's new-field defaults (false in the model -> visible divergence when missing).
        assertEquals(Boolean.TRUE, field.eGet(feature(field, "enabled"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, field.eGet(feature(field, "showInHeader"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, field.eGet(feature(field, "showInFooter"))); //$NON-NLS-1$
        assertEquals("Enter", literalOf(field, "editMode")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Left", literalOf(field, "headerHorizontalAlign")); //$NON-NLS-1$ //$NON-NLS-2$
        EObject extInfo = (EObject)field.eGet(feature(field, "extInfo")); //$NON-NLS-1$
        assertNotNull(extInfo);
        assertEquals("InputFieldExtInfo", extInfo.eClass().getName()); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, extInfo.eGet(feature(extInfo, "wrap"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, extInfo.eGet(feature(extInfo, "textEdit"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, extInfo.eGet(feature(extInfo, "chooseType"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, extInfo.eGet(feature(extInfo, "typeDomainEnabled"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, extInfo.eGet(feature(extInfo, "autoMaxWidth"))); //$NON-NLS-1$
        // The designer auto-children: a context menu + an extended tooltip with allocated ids.
        EObject menu = (EObject)field.eGet(feature(field, "contextMenu")); //$NON-NLS-1$
        assertNotNull(menu);
        assertEquals("PriceFieldContextMenu", menu.eGet(feature(menu, "name"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, menu.eGet(feature(menu, "autoFill"))); //$NON-NLS-1$
        assertTrue(((Integer)menu.eGet(feature(menu, "id"))).intValue() > 0); //$NON-NLS-1$
        EObject tooltip = (EObject)field.eGet(feature(field, "extendedTooltip")); //$NON-NLS-1$
        assertNotNull(tooltip);
        assertEquals("PriceFieldExtendedTooltip", tooltip.eGet(feature(tooltip, "name"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Label", literalOf(tooltip, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        EObject tooltipExtInfo = (EObject)tooltip.eGet(feature(tooltip, "extInfo")); //$NON-NLS-1$
        assertNotNull(tooltipExtInfo);
        assertEquals("Left", literalOf(tooltipExtInfo, "horizontalAlign")); //$NON-NLS-1$ //$NON-NLS-2$
        // The auto-children ids are distinct from the field's and from each other.
        assertTrue(!menu.eGet(feature(menu, "id")).equals(tooltip.eGet(feature(tooltip, "id")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAutoChildrenRussianSuffixes()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        // russianAutoNames=true -> the suffixes follow the RUSSIAN script variant.
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "Btn", null, "Print", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, true, null));
        EObject button = FormElementWriter.findFormItem(form, "Btn"); //$NON-NLS-1$
        EObject tooltip = (EObject)button.eGet(feature(button, "extendedTooltip")); //$NON-NLS-1$
        assertNotNull(tooltip);
        // RasshirennayaPodskazka (built independently from code points).
        String ruSuffix = fromCp(0x0420, 0x0430, 0x0441, 0x0448, 0x0438, 0x0440, 0x0435, 0x043d,
            0x043d, 0x0430, 0x044f, 0x041f, 0x043e, 0x0434, 0x0441, 0x043a, 0x0430, 0x0437, 0x043a,
            0x0430);
        assertEquals("Btn" + ruSuffix, tooltip.eGet(feature(tooltip, "name"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAutoChildNameCollisionGetsCounter()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        // Occupy the would-be tooltip name with a real item.
        EObject group = newObject(MODEL.formGroup);
        group.eSet(feature(group, "name"), "BtnExtendedTooltip"); //$NON-NLS-1$ //$NON-NLS-2$
        addTo(form, "items", group); //$NON-NLS-1$
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "Btn", null, "Print", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        EObject button = FormElementWriter.findFormItem(form, "Btn"); //$NON-NLS-1$
        EObject tooltip = (EObject)button.eGet(feature(button, "extendedTooltip")); //$NON-NLS-1$
        assertEquals("BtnExtendedTooltip1", tooltip.eGet(feature(tooltip, "name"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testButtonIntoTableIsRejected()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        EObject table = newObject(MODEL.table);
        table.eSet(feature(table, "name"), "List"); //$NON-NLS-1$ //$NON-NLS-2$
        addTo(form, "items", table); //$NON-NLS-1$
        String err = FormElementWriter.createMember(form, Kind.BUTTON, "B", "List", "Print", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            null, null, false, null);
        assertNotNull("the platform forbids buttons directly in tables", err); //$NON-NLS-1$
        assertTrue(err.contains("List")); //$NON-NLS-1$
        assertTrue(err.contains("AutoCommandBar")); // the error advertises the valid alternative //$NON-NLS-1$
        assertEquals(0, ((List<?>)table.eGet(feature(table, "items"))).size()); //$NON-NLS-1$
    }

    @Test
    public void testDecorationIntoCommandBarIsRejected()
    {
        EObject form = newForm();
        String err = FormElementWriter.createMember(form, Kind.DECORATION, "D", "AutoCommandBar", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, null, false, null);
        assertNotNull("the platform forbids decorations in command bars", err); //$NON-NLS-1$
        assertTrue(err.contains("AutoCommandBar")); //$NON-NLS-1$
        EObject bar = (EObject)form.eGet(feature(form, "autoCommandBar")); //$NON-NLS-1$
        assertEquals(0, ((List<?>)bar.eGet(feature(bar, "items"))).size()); //$NON-NLS-1$
    }

    @Test
    public void testGroupInCommandBarBecomesPopup()
    {
        // The platform's getDefaultGroupType: a group inside a command bar is a Popup (a submenu),
        // with the matching ext-info - never a UsualGroup.
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "Menu", "AutoCommandBar", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, null, false, null));
        EObject group = FormElementWriter.findFormItem(form, "Menu"); //$NON-NLS-1$
        assertNotNull(group);
        assertEquals("Popup", literalOf(group, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        EObject extInfo = (EObject)group.eGet(feature(group, "extInfo")); //$NON-NLS-1$
        assertNotNull(extInfo);
        assertEquals("PopupGroupExtInfo", extInfo.eClass().getName()); //$NON-NLS-1$
        // A group at the form root stays a UsualGroup.
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "Main", null, //$NON-NLS-1$
            null, null, null, false, null));
        assertEquals("UsualGroup", //$NON-NLS-1$
            literalOf(FormElementWriter.findFormItem(form, "Main"), "type")); //$NON-NLS-1$ //$NON-NLS-2$
        // And a button INSIDE the popup submenu is a command-bar button.
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "MenuBtn", "Menu", "Print", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            null, null, false, null));
        assertEquals("CommandBarButton", //$NON-NLS-1$
            literalOf(FormElementWriter.findFormItem(form, "MenuBtn"), "type")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCreateGroupWithExplicitType()
    {
        EObject form = newForm();
        // The 'type' property is case-insensitive and maps the matching extInfo class.
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "Tabs", null, "pages", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        EObject group = FormElementWriter.findFormItem(form, "Tabs"); //$NON-NLS-1$
        assertEquals("Pages", literalOf(group, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("PagesGroupExtInfo", //$NON-NLS-1$
            ((EObject)group.eGet(feature(group, "extInfo"))).eClass().getName()); //$NON-NLS-1$
        // A page nested in the Pages group defaults to Page (container-derived).
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "Tab1", "Tabs", null, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        assertEquals("Page", literalOf(FormElementWriter.findFormItem(form, "Tab1"), "type")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testCreateGroupUnknownTypeListsAllowed()
    {
        EObject form = newForm();
        String err = FormElementWriter.createMember(form, Kind.GROUP, "G", null, "Bogus", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null);
        assertNotNull(err);
        assertTrue(err.contains("Bogus")); //$NON-NLS-1$
        assertTrue(err.contains("Allowed group types:")); //$NON-NLS-1$
        assertTrue(err.contains("Popup")); //$NON-NLS-1$
    }

    @Test
    public void testMoveButtonIntoBarRetypesIt()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "Btn", null, "Print", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        EObject button = FormElementWriter.findFormItem(form, "Btn"); //$NON-NLS-1$
        assertEquals("UsualButton", literalOf(button, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        // Root -> bar: the containment moves and the type re-derives to CommandBarButton.
        assertNull(FormElementWriter.moveItem(form, button, "AutoCommandBar")); //$NON-NLS-1$
        EObject bar = (EObject)form.eGet(feature(form, "autoCommandBar")); //$NON-NLS-1$
        assertEquals(1, ((List<?>)bar.eGet(feature(bar, "items"))).size()); //$NON-NLS-1$
        assertEquals(0, ((List<?>)form.eGet(feature(form, "items"))).size()); //$NON-NLS-1$
        assertEquals("CommandBarButton", literalOf(button, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        // Bar -> root (blank parent): back to UsualButton.
        assertNull(FormElementWriter.moveItem(form, button, null));
        assertEquals(0, ((List<?>)bar.eGet(feature(bar, "items"))).size()); //$NON-NLS-1$
        assertEquals("UsualButton", literalOf(button, "type")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMoveRejectsCycleAutoChildAndBadPlacement()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "Outer", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "Inner", "Outer", null, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        EObject outer = FormElementWriter.findFormItem(form, "Outer"); //$NON-NLS-1$
        // A group cannot move into its own contained item.
        String cycle = FormElementWriter.moveItem(form, outer, "Inner"); //$NON-NLS-1$
        assertNotNull(cycle);
        assertTrue(cycle.contains("its own contained item")); //$NON-NLS-1$
        // A designer auto-child (the group's extended tooltip) is not movable.
        EObject tooltip = (EObject)outer.eGet(feature(outer, "extendedTooltip")); //$NON-NLS-1$
        assertNotNull(tooltip);
        String autoChild = FormElementWriter.moveItem(form, tooltip, null);
        assertNotNull(autoChild);
        assertTrue(autoChild.contains("cannot be moved")); //$NON-NLS-1$
        // A decoration cannot move into the command bar (same placement rule as create).
        assertNull(FormElementWriter.createMember(form, Kind.DECORATION, "Deco", null, null, //$NON-NLS-1$
            null, null, false, null));
        EObject deco = FormElementWriter.findFormItem(form, "Deco"); //$NON-NLS-1$
        String placement = FormElementWriter.moveItem(form, deco, "AutoCommandBar"); //$NON-NLS-1$
        assertNotNull(placement);
        assertTrue(placement.contains("cannot hold decorations")); //$NON-NLS-1$
        // A form COMMAND has no visual parent at all.
        EObject command = FormElementWriter.findFormCommand(form, "NoSuch"); //$NON-NLS-1$
        assertNull(command);
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Cmd", null, null, //$NON-NLS-1$
            null, null, false, null));
        String notItem = FormElementWriter.moveItem(form,
            FormElementWriter.findFormCommand(form, "Cmd"), null); //$NON-NLS-1$
        assertNotNull(notItem);
        assertTrue(notItem.contains("Attributes and commands have no visual parent")); //$NON-NLS-1$
    }

    // ==================== dynamic form-like EMF metamodel ====================

    private static final FormLikeModel MODEL = new FormLikeModel();

    private static EObject newForm()
    {
        EObject form = newObject(MODEL.form);
        EObject bar = newObject(MODEL.autoCommandBar);
        bar.eSet(feature(bar, "name"), "FormCommandBar"); //$NON-NLS-1$ //$NON-NLS-2$
        bar.eSet(feature(bar, "id"), Integer.valueOf(-1)); //$NON-NLS-1$
        form.eSet(feature(form, "autoCommandBar"), bar); //$NON-NLS-1$
        return form;
    }

    private static EObject newObject(EClass eClass)
    {
        return new DynamicEObjectImpl(eClass);
    }

    private static EStructuralFeature feature(EObject object, String name)
    {
        return object.eClass().getEStructuralFeature(name);
    }

    /** Reads an EEnum attribute's current literal (dynamic literals implement {@link Enumerator}). */
    private static String literalOf(EObject object, String featureName)
    {
        Object value = object.eGet(feature(object, featureName));
        if (value instanceof EEnumLiteral)
        {
            return ((EEnumLiteral)value).getLiteral();
        }
        return value instanceof Enumerator ? ((Enumerator)value).getLiteral() : null;
    }

    private static void setLiteral(EObject object, String featureName, String literal)
    {
        EAttribute attribute = (EAttribute)feature(object, featureName);
        EEnum eEnum = (EEnum)attribute.getEAttributeType();
        object.eSet(attribute, eEnum.getEEnumLiteralByLiteral(literal));
    }

    @SuppressWarnings("unchecked")
    private static void addTo(EObject owner, String featureName, EObject child)
    {
        ((List<EObject>)owner.eGet(feature(owner, featureName))).add(child);
    }

    /**
     * A dynamic EMF metamodel reproducing the form-model features the writer touches reflectively:
     * the Form (items / attributes / formCommands / autoCommandBar), FormItem subtypes (Button with
     * its type / placement enums, FormGroup with its group type, AutoCommandBar), the FormCommand with
     * its {@code action} containment ({@code FormCommandHandlerContainer} holding a
     * {@code CommandHandler}) and {@code use} AdjustableBoolean. Lets the reflective write logic be
     * tested without the real {@code com._1c.g5.v8.dt.form.model} package.
     */
    private static final class FormLikeModel
    {
        final EClass form;
        final EClass formGroup;
        final EClass autoCommandBar;
        final EClass table;
        final EClass decoration;
        final EClass formAttribute;

        FormLikeModel()
        {
            EcoreFactory f = EcoreFactory.eINSTANCE;
            EPackage pkg = f.createEPackage();
            pkg.setName("formlike"); //$NON-NLS-1$
            pkg.setNsPrefix("formlike"); //$NON-NLS-1$
            pkg.setNsURI("http://ditrix.com/test/formlike-writer"); //$NON-NLS-1$

            EEnum buttonType = newEnum(f, "ManagedFormButtonType", //$NON-NLS-1$
                "CommandBarButton", "UsualButton", "Hyperlink"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EEnum placementArea = newEnum(f, "MenuElementPlacementArea", //$NON-NLS-1$
                "MainCmdsLeft", "AutoCmds", "UserCmds"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EEnum groupType = newEnum(f, "ManagedFormGroupType", //$NON-NLS-1$
                "ButtonGroup", "ColumnGroup", "CommandBar", "UsualGroup", "Popup", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "Page", "Pages"); //$NON-NLS-1$ //$NON-NLS-2$
            EEnum currentRowUse = newEnum(f, "CurrentRowUse", "DontUse", "Use", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            EEnum decorationType = newEnum(f, "ManagedFormDecorationType", "Label", "Picture"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EEnum fieldType = newEnum(f, "ManagedFormFieldType", "InputField", "LabelField"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EEnum horizontalAlign = newEnum(f, "ItemHorizontalAlignment", "Auto", "Left"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EEnum editMode = newEnum(f, "TableFieldEditMode", "Directly", "Enter"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            EClass adjustableBoolean = f.createEClass();
            adjustableBoolean.setName("AdjustableBoolean"); //$NON-NLS-1$
            addBoolean(f, adjustableBoolean, "common"); //$NON-NLS-1$

            // The extInfo family: an abstract base plus the concrete classes the writer resolves by
            // name (group ext-infos, the input-field ext-info and the tooltip's label ext-info).
            EClass extInfoBase = f.createEClass();
            extInfoBase.setName("FormItemExtInfo"); //$NON-NLS-1$
            extInfoBase.setAbstract(true);
            EClass usualGroupExtInfo = subExtInfo(f, extInfoBase, "UsualGroupExtInfo"); //$NON-NLS-1$
            EClass popupGroupExtInfo = subExtInfo(f, extInfoBase, "PopupGroupExtInfo"); //$NON-NLS-1$
            EClass pageGroupExtInfo = subExtInfo(f, extInfoBase, "PageGroupExtInfo"); //$NON-NLS-1$
            EClass pagesGroupExtInfo = subExtInfo(f, extInfoBase, "PagesGroupExtInfo"); //$NON-NLS-1$
            EClass columnGroupExtInfo = subExtInfo(f, extInfoBase, "ColumnGroupExtInfo"); //$NON-NLS-1$
            EClass commandBarExtInfo = subExtInfo(f, extInfoBase, "CommandBarExtInfo"); //$NON-NLS-1$
            EClass buttonGroupExtInfo = subExtInfo(f, extInfoBase, "ButtonGroupExtInfo"); //$NON-NLS-1$
            EClass labelDecorationExtInfo = subExtInfo(f, extInfoBase, "LabelDecorationExtInfo"); //$NON-NLS-1$
            addEnum(f, labelDecorationExtInfo, "horizontalAlign", horizontalAlign); //$NON-NLS-1$
            EClass inputFieldExtInfo = subExtInfo(f, extInfoBase, "InputFieldExtInfo"); //$NON-NLS-1$
            addBoolean(f, inputFieldExtInfo, "autoMaxWidth"); //$NON-NLS-1$
            addBoolean(f, inputFieldExtInfo, "autoMaxHeight"); //$NON-NLS-1$
            addBoolean(f, inputFieldExtInfo, "wrap"); //$NON-NLS-1$
            addBoolean(f, inputFieldExtInfo, "chooseType"); //$NON-NLS-1$
            addBoolean(f, inputFieldExtInfo, "typeDomainEnabled"); //$NON-NLS-1$
            addBoolean(f, inputFieldExtInfo, "textEdit"); //$NON-NLS-1$

            EClass formItem = f.createEClass();
            formItem.setName("FormItem"); //$NON-NLS-1$
            formItem.setAbstract(true);
            addString(f, formItem, "name"); //$NON-NLS-1$
            addInt(f, formItem, "id"); //$NON-NLS-1$

            // The designer's auto-children (both are FormItems: named, id-bearing).
            EClass contextMenu = f.createEClass();
            contextMenu.setName("ContextMenu"); //$NON-NLS-1$
            contextMenu.getESuperTypes().add(formItem);
            addBoolean(f, contextMenu, "autoFill"); //$NON-NLS-1$
            EClass extendedTooltip = f.createEClass();
            extendedTooltip.setName("ExtendedTooltip"); //$NON-NLS-1$
            extendedTooltip.getESuperTypes().add(formItem);
            addEnum(f, extendedTooltip, "type", decorationType); //$NON-NLS-1$
            addBoolean(f, extendedTooltip, "autoMaxWidth"); //$NON-NLS-1$
            addBoolean(f, extendedTooltip, "autoMaxHeight"); //$NON-NLS-1$
            extendedTooltip.getEStructuralFeatures().add(
                containment(f, "extInfo", extInfoBase, false)); //$NON-NLS-1$

            EClass commandHandler = f.createEClass();
            commandHandler.setName("CommandHandler"); //$NON-NLS-1$
            addString(f, commandHandler, "name"); //$NON-NLS-1$

            EClass handlerContainer = f.createEClass();
            handlerContainer.setName("CommandHandlerContainer"); //$NON-NLS-1$
            handlerContainer.setAbstract(true);

            EClass formCommandHandlerContainer = f.createEClass();
            formCommandHandlerContainer.setName("FormCommandHandlerContainer"); //$NON-NLS-1$
            formCommandHandlerContainer.getESuperTypes().add(handlerContainer);
            formCommandHandlerContainer.getEStructuralFeatures().add(
                containment(f, "handler", commandHandler, false)); //$NON-NLS-1$

            EClass formCommand = f.createEClass();
            formCommand.setName("FormCommand"); //$NON-NLS-1$
            addString(f, formCommand, "name"); //$NON-NLS-1$
            formCommand.getEStructuralFeatures().add(
                containment(f, "action", handlerContainer, false)); //$NON-NLS-1$
            formCommand.getEStructuralFeatures().add(
                containment(f, "use", adjustableBoolean, false)); //$NON-NLS-1$
            addEnum(f, formCommand, "currentRowUse", currentRowUse); //$NON-NLS-1$

            EClass button = f.createEClass();
            button.setName("Button"); //$NON-NLS-1$
            button.getESuperTypes().add(formItem);
            addEnum(f, button, "type", buttonType); //$NON-NLS-1$
            addEnum(f, button, "placementArea", placementArea); //$NON-NLS-1$
            addBoolean(f, button, "visible"); //$NON-NLS-1$
            addBoolean(f, button, "enabled"); //$NON-NLS-1$
            addBoolean(f, button, "autoMaxWidth"); //$NON-NLS-1$
            addBoolean(f, button, "autoMaxHeight"); //$NON-NLS-1$
            addBoolean(f, button, "commandUniqueness"); //$NON-NLS-1$
            EReference commandName = f.createEReference();
            commandName.setName("commandName"); //$NON-NLS-1$
            commandName.setEType(formCommand);
            button.getEStructuralFeatures().add(commandName);
            button.getEStructuralFeatures().add(
                containment(f, "userVisible", adjustableBoolean, false)); //$NON-NLS-1$
            button.getEStructuralFeatures().add(
                containment(f, "extendedTooltip", extendedTooltip, false)); //$NON-NLS-1$

            formGroup = f.createEClass();
            formGroup.setName("FormGroup"); //$NON-NLS-1$
            formGroup.getESuperTypes().add(formItem);
            addEnum(f, formGroup, "type", groupType); //$NON-NLS-1$
            formGroup.getEStructuralFeatures().add(containment(f, "items", formItem, true)); //$NON-NLS-1$
            formGroup.getEStructuralFeatures().add(
                containment(f, "extInfo", extInfoBase, false)); //$NON-NLS-1$
            formGroup.getEStructuralFeatures().add(
                containment(f, "extendedTooltip", extendedTooltip, false)); //$NON-NLS-1$

            decoration = f.createEClass();
            decoration.setName("Decoration"); //$NON-NLS-1$
            decoration.getESuperTypes().add(formItem);
            addEnum(f, decoration, "type", decorationType); //$NON-NLS-1$
            addBoolean(f, decoration, "visible"); //$NON-NLS-1$
            addBoolean(f, decoration, "enabled"); //$NON-NLS-1$
            addBoolean(f, decoration, "autoMaxWidth"); //$NON-NLS-1$
            addBoolean(f, decoration, "autoMaxHeight"); //$NON-NLS-1$
            decoration.getEStructuralFeatures().add(
                containment(f, "userVisible", adjustableBoolean, false)); //$NON-NLS-1$
            decoration.getEStructuralFeatures().add(
                containment(f, "extInfo", extInfoBase, false)); //$NON-NLS-1$
            decoration.getEStructuralFeatures().add(
                containment(f, "contextMenu", contextMenu, false)); //$NON-NLS-1$
            decoration.getEStructuralFeatures().add(
                containment(f, "extendedTooltip", extendedTooltip, false)); //$NON-NLS-1$

            EClass dataPath = f.createEClass();
            dataPath.setName("DataPath"); //$NON-NLS-1$
            EAttribute segments = f.createEAttribute();
            segments.setName("segments"); //$NON-NLS-1$
            segments.setEType(EcorePackage.Literals.ESTRING);
            segments.setUpperBound(-1);
            dataPath.getEStructuralFeatures().add(segments);

            EClass formField = f.createEClass();
            formField.setName("FormField"); //$NON-NLS-1$
            formField.getESuperTypes().add(formItem);
            addEnum(f, formField, "type", fieldType); //$NON-NLS-1$
            addBoolean(f, formField, "visible"); //$NON-NLS-1$
            addBoolean(f, formField, "enabled"); //$NON-NLS-1$
            addBoolean(f, formField, "showInHeader"); //$NON-NLS-1$
            addBoolean(f, formField, "showInFooter"); //$NON-NLS-1$
            addEnum(f, formField, "headerHorizontalAlign", horizontalAlign); //$NON-NLS-1$
            addEnum(f, formField, "editMode", editMode); //$NON-NLS-1$
            formField.getEStructuralFeatures().add(
                containment(f, "userVisible", adjustableBoolean, false)); //$NON-NLS-1$
            formField.getEStructuralFeatures().add(containment(f, "dataPath", dataPath, false)); //$NON-NLS-1$
            formField.getEStructuralFeatures().add(containment(f, "extInfo", extInfoBase, false)); //$NON-NLS-1$
            formField.getEStructuralFeatures().add(
                containment(f, "contextMenu", contextMenu, false)); //$NON-NLS-1$
            formField.getEStructuralFeatures().add(
                containment(f, "extendedTooltip", extendedTooltip, false)); //$NON-NLS-1$

            formAttribute = f.createEClass();
            formAttribute.setName("FormAttribute"); //$NON-NLS-1$
            addString(f, formAttribute, "name"); //$NON-NLS-1$

            autoCommandBar = f.createEClass();
            autoCommandBar.setName("AutoCommandBar"); //$NON-NLS-1$
            autoCommandBar.getESuperTypes().add(formItem);
            autoCommandBar.getEStructuralFeatures().add(containment(f, "items", formItem, true)); //$NON-NLS-1$

            table = f.createEClass();
            table.setName("Table"); //$NON-NLS-1$
            table.getESuperTypes().add(formItem);
            table.getEStructuralFeatures().add(containment(f, "items", formItem, true)); //$NON-NLS-1$
            table.getEStructuralFeatures().add(
                containment(f, "autoCommandBar", autoCommandBar, false)); //$NON-NLS-1$

            form = f.createEClass();
            form.setName("Form"); //$NON-NLS-1$
            form.getEStructuralFeatures().add(containment(f, "items", formItem, true)); //$NON-NLS-1$
            form.getEStructuralFeatures().add(containment(f, "formCommands", formCommand, true)); //$NON-NLS-1$
            form.getEStructuralFeatures().add(
                containment(f, "attributes", formAttribute, true)); //$NON-NLS-1$
            form.getEStructuralFeatures().add(
                containment(f, "autoCommandBar", autoCommandBar, false)); //$NON-NLS-1$

            pkg.getEClassifiers().add(form);
            pkg.getEClassifiers().add(table);
            pkg.getEClassifiers().add(buttonType);
            pkg.getEClassifiers().add(placementArea);
            pkg.getEClassifiers().add(groupType);
            pkg.getEClassifiers().add(currentRowUse);
            pkg.getEClassifiers().add(decorationType);
            pkg.getEClassifiers().add(fieldType);
            pkg.getEClassifiers().add(horizontalAlign);
            pkg.getEClassifiers().add(editMode);
            pkg.getEClassifiers().add(adjustableBoolean);
            pkg.getEClassifiers().add(extInfoBase);
            pkg.getEClassifiers().add(usualGroupExtInfo);
            pkg.getEClassifiers().add(popupGroupExtInfo);
            pkg.getEClassifiers().add(pageGroupExtInfo);
            pkg.getEClassifiers().add(pagesGroupExtInfo);
            pkg.getEClassifiers().add(columnGroupExtInfo);
            pkg.getEClassifiers().add(commandBarExtInfo);
            pkg.getEClassifiers().add(buttonGroupExtInfo);
            pkg.getEClassifiers().add(labelDecorationExtInfo);
            pkg.getEClassifiers().add(inputFieldExtInfo);
            pkg.getEClassifiers().add(contextMenu);
            pkg.getEClassifiers().add(extendedTooltip);
            pkg.getEClassifiers().add(formItem);
            pkg.getEClassifiers().add(commandHandler);
            pkg.getEClassifiers().add(handlerContainer);
            pkg.getEClassifiers().add(formCommandHandlerContainer);
            pkg.getEClassifiers().add(formCommand);
            pkg.getEClassifiers().add(button);
            pkg.getEClassifiers().add(formGroup);
            pkg.getEClassifiers().add(decoration);
            pkg.getEClassifiers().add(dataPath);
            pkg.getEClassifiers().add(formField);
            pkg.getEClassifiers().add(formAttribute);
            pkg.getEClassifiers().add(autoCommandBar);
        }

        private static EClass subExtInfo(EcoreFactory f, EClass base, String name)
        {
            EClass extInfo = f.createEClass();
            extInfo.setName(name);
            extInfo.getESuperTypes().add(base);
            return extInfo;
        }

        private static EEnum newEnum(EcoreFactory f, String name, String... literals)
        {
            EEnum eEnum = f.createEEnum();
            eEnum.setName(name);
            int value = 0;
            for (String literal : literals)
            {
                EEnumLiteral eLiteral = f.createEEnumLiteral();
                eLiteral.setName(literal);
                eLiteral.setLiteral(literal);
                eLiteral.setValue(value++);
                eEnum.getELiterals().add(eLiteral);
            }
            return eEnum;
        }

        private static void addString(EcoreFactory f, EClass owner, String name)
        {
            EAttribute attribute = f.createEAttribute();
            attribute.setName(name);
            attribute.setEType(EcorePackage.Literals.ESTRING);
            owner.getEStructuralFeatures().add(attribute);
        }

        private static void addInt(EcoreFactory f, EClass owner, String name)
        {
            EAttribute attribute = f.createEAttribute();
            attribute.setName(name);
            attribute.setEType(EcorePackage.Literals.EINT);
            owner.getEStructuralFeatures().add(attribute);
        }

        private static void addBoolean(EcoreFactory f, EClass owner, String name)
        {
            EAttribute attribute = f.createEAttribute();
            attribute.setName(name);
            attribute.setEType(EcorePackage.Literals.EBOOLEAN);
            owner.getEStructuralFeatures().add(attribute);
        }

        private static void addEnum(EcoreFactory f, EClass owner, String name, EEnum type)
        {
            EAttribute attribute = f.createEAttribute();
            attribute.setName(name);
            attribute.setEType(type);
            owner.getEStructuralFeatures().add(attribute);
        }

        private static EReference containment(EcoreFactory f, String name, EClass type, boolean many)
        {
            EReference reference = f.createEReference();
            reference.setName(name);
            reference.setEType(type);
            reference.setContainment(true);
            reference.setUpperBound(many ? -1 : 1);
            return reference;
        }
    }
}
