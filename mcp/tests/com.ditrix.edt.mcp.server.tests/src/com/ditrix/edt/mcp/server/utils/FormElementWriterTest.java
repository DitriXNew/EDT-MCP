/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.common.util.TreeIterator;
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

import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormMemberRef;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormObjectRef;
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
        assertEquals(Kind.TABLE, FormElementWriter.kindForToken("Table")); //$NON-NLS-1$
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
        // tablica -> TABLE
        assertEquals(Kind.TABLE, FormElementWriter.kindForToken(
            fromCp(0x0442, 0x0430, 0x0431, 0x043b, 0x0438, 0x0446, 0x0430)));
    }

    @Test
    public void testCreateTableWithColumns()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createTable(form, "Goods", null, "Object.Goods", //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Arrays.asList("Product", "Quantity"), null, null, false, new String[1])); //$NON-NLS-1$ //$NON-NLS-2$
        EObject table = FormElementWriter.findFormItem(form, "Goods"); //$NON-NLS-1$
        assertNotNull(table);
        assertEquals("Table", table.eClass().getName()); //$NON-NLS-1$
        // The table carries its OWN command bar (a normal item, not the form-root -1 sentinel).
        EObject bar = (EObject)table.eGet(feature(table, "autoCommandBar")); //$NON-NLS-1$
        assertNotNull(bar);
        assertEquals("GoodsCommandBar", bar.eGet(feature(bar, "name"))); //$NON-NLS-1$ //$NON-NLS-2$
        // Columns: the standard LineNumber label column FIRST, then one input column per attribute.
        List<?> columns = (List<?>)table.eGet(feature(table, "items")); //$NON-NLS-1$
        assertEquals(3, columns.size());
        EObject lineNo = (EObject)columns.get(0);
        assertEquals("GoodsLineNumber", lineNo.eGet(feature(lineNo, "name"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("InputField", literalOf(lineNo, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        EObject productCol = FormElementWriter.findFormItem(form, "GoodsProduct"); //$NON-NLS-1$
        assertNotNull(productCol);
        assertEquals("InputField", literalOf(productCol, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(FormElementWriter.findFormItem(form, "GoodsQuantity")); //$NON-NLS-1$
        // Each column carries the designer auto-children with an allocated (nonzero) id.
        EObject menu = (EObject)productCol.eGet(feature(productCol, "contextMenu")); //$NON-NLS-1$
        assertNotNull(menu);
        assertTrue(((Integer)menu.eGet(feature(menu, "id"))).intValue() > 0); //$NON-NLS-1$
    }

    @Test
    public void testCreateTableAdditionsAreEnabled()
    {
        // The table's search-string / view-status / search-control additions are Visible items whose
        // 'enabled' model default is FALSE (see testCreateButtonAtRootIsEnabledUsualButton). If they are
        // left un-enabled the open form editor renders them grey/read-only - the exact symptom that a
        // designer-created table never shows. They must be created enabled, like the table and its columns.
        EObject form = newForm();
        assertNull(FormElementWriter.createTable(form, "Goods", null, "Object.Goods", //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Arrays.asList("Product", "Quantity"), null, null, false, new String[1])); //$NON-NLS-1$ //$NON-NLS-2$
        EObject table = FormElementWriter.findFormItem(form, "Goods"); //$NON-NLS-1$
        assertNotNull(table);
        for (String additionFeature : new String[] {"searchStringAddition", "viewStatusAddition", //$NON-NLS-1$ //$NON-NLS-2$
            "searchControlAddition"}) //$NON-NLS-1$
        {
            EObject addition = (EObject)table.eGet(feature(table, additionFeature));
            assertNotNull(additionFeature + " was not created", addition); //$NON-NLS-1$
            // The fix: additions must be created enabled, else the open editor renders them grey.
            assertEquals(additionFeature + " must be enabled", //$NON-NLS-1$
                Boolean.TRUE, addition.eGet(feature(addition, "enabled"))); //$NON-NLS-1$
            // ...but ONLY enabled - the designer keeps additions at visible=false; setting it (e.g. via
            // applyVisibleDefaults) would diverge from a designer-built table.
            assertEquals(additionFeature + " must stay visible=false", //$NON-NLS-1$
                Boolean.FALSE, addition.eGet(feature(addition, "visible"))); //$NON-NLS-1$
        }
    }

    @Test
    public void testCreateTableAdditionsRussianAutoNames()
    {
        // In a Russian script variant the three table additions must get LOCALIZED name suffixes, just
        // like the command bar (КоманднаяПанель) and LineNumber (НомерСтроки). Verified against EDT's
        // ru report_variant.form template: СтрокаПоиска / СостояниеПросмотра / УправлениеПоиском. An
        // English suffix here (e.g. "TSearchString") would break byte-identity with the designer.
        EObject form = newForm();
        assertNull(FormElementWriter.createTable(form, "T", null, "Object.Goods", //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Collections.emptyList(), null, null, true, new String[1]));
        EObject table = FormElementWriter.findFormItem(form, "T"); //$NON-NLS-1$
        assertNotNull(table);
        // СтрокаПоиска / СостояниеПросмотра / УправлениеПоиском, built independently from code points.
        String searchString = fromCp(0x0421, 0x0442, 0x0440, 0x043e, 0x043a, 0x0430, 0x041f, 0x043e,
            0x0438, 0x0441, 0x043a, 0x0430);
        String viewStatus = fromCp(0x0421, 0x043e, 0x0441, 0x0442, 0x043e, 0x044f, 0x043d, 0x0438, 0x0435,
            0x041f, 0x0440, 0x043e, 0x0441, 0x043c, 0x043e, 0x0442, 0x0440, 0x0430);
        String searchControl = fromCp(0x0423, 0x043f, 0x0440, 0x0430, 0x0432, 0x043b, 0x0435, 0x043d,
            0x0438, 0x0435, 0x041f, 0x043e, 0x0438, 0x0441, 0x043a, 0x043e, 0x043c);
        assertAdditionName(table, "searchStringAddition", "T" + searchString); //$NON-NLS-1$ //$NON-NLS-2$
        assertAdditionName(table, "viewStatusAddition", "T" + viewStatus); //$NON-NLS-1$ //$NON-NLS-2$
        assertAdditionName(table, "searchControlAddition", "T" + searchControl); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertAdditionName(EObject table, String additionFeature, String expectedName)
    {
        EObject addition = (EObject)table.eGet(feature(table, additionFeature));
        assertNotNull(additionFeature + " was not created", addition); //$NON-NLS-1$
        assertEquals(additionFeature + " must use the localized suffix", //$NON-NLS-1$
            expectedName, addition.eGet(feature(addition, "name"))); //$NON-NLS-1$
    }

    @Test
    public void testCreateTableRequiresDataPath()
    {
        EObject form = newForm();
        String err = FormElementWriter.createTable(form, "T", null, null, //$NON-NLS-1$
            java.util.Collections.emptyList(), null, null, false, new String[1]);
        assertNotNull(err);
        assertTrue(err.contains("dataPath")); //$NON-NLS-1$
    }

    @Test
    public void testCreateTableViaCreateMemberIsColumnLess()
    {
        // Through createMember (no metadata) a table gets only the standard LineNumber column.
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.TABLE, "Lines", null, //$NON-NLS-1$
            "Object.Lines", null, null, false, new String[1])); //$NON-NLS-1$
        EObject table = FormElementWriter.findFormItem(form, "Lines"); //$NON-NLS-1$
        assertNotNull(table);
        assertEquals(1, ((List<?>)table.eGet(feature(table, "items"))).size()); //$NON-NLS-1$
    }

    @Test
    public void testKindForUnknownAndNull()
    {
        assertNull(FormElementWriter.kindForToken("Nonsense")); //$NON-NLS-1$
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

    // ---- form-OBJECT create FQN parse ------------------------------------------------------------

    @Test
    public void testParseFormObjectCreateManaged()
    {
        // A 4-part form FQN addresses the FORM OBJECT to create (owner type/name + form name).
        FormObjectRef ref = FormElementWriter.parseFormObjectCreate("Catalog.Products.Form.ItemForm"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("Catalog", ref.ownerType); //$NON-NLS-1$
        assertEquals("Products", ref.ownerName); //$NON-NLS-1$
        assertEquals("ItemForm", ref.formName); //$NON-NLS-1$
        assertEquals("Catalog.Products", ref.ownerFqn()); //$NON-NLS-1$
    }

    @Test
    public void testParseFormObjectCreateBilingualFormToken()
    {
        // The form token is bilingual: "Форма" (forma) and "Forms" are both accepted.
        String ru = "Catalog.Products." + fromCp(0x0444, 0x043e, 0x0440, 0x043c, 0x0430) + ".F"; //$NON-NLS-1$
        FormObjectRef ref = FormElementWriter.parseFormObjectCreate(ru);
        assertNotNull(ref);
        assertEquals("F", ref.formName); //$NON-NLS-1$
        assertNotNull(FormElementWriter.parseFormObjectCreate("Document.Inv.Forms.MainForm")); //$NON-NLS-1$
    }

    @Test
    public void testParseFormObjectCreateRejectsNonFormObject()
    {
        // A form MEMBER FQN (6 parts) is NOT a form-object create (it routes to parse()).
        assertNull(FormElementWriter.parseFormObjectCreate("Catalog.Products.Form.ItemForm.Attribute.A")); //$NON-NLS-1$
        // A 4-part mdclass member (no form token at position 2) is not a form-object create.
        assertNull(FormElementWriter.parseFormObjectCreate("Catalog.Products.Attribute.Weight")); //$NON-NLS-1$
        // A CommonForm (2 parts) IS a top object - created via the normal top-level path, not here.
        assertNull(FormElementWriter.parseFormObjectCreate("CommonForm.MyForm")); //$NON-NLS-1$
        // A plain top object / null is not a form-object create.
        assertNull(FormElementWriter.parseFormObjectCreate("Catalog.Products")); //$NON-NLS-1$
        assertNull(FormElementWriter.parseFormObjectCreate(null));
    }

    // ---- form-token predicate (shared with MetadataPathResolver) ---------------------------------

    @Test
    public void testIsFormTokenAcceptsEnglishAndRussianSingularPlural()
    {
        assertTrue(FormElementWriter.isFormToken("Form")); //$NON-NLS-1$
        assertTrue(FormElementWriter.isFormToken("forms")); //$NON-NLS-1$
        assertTrue(FormElementWriter.isFormToken("FORMS")); //$NON-NLS-1$
        // Forma (capital F-cyrillic, the predicate lowercases) -> accepted.
        assertTrue(FormElementWriter.isFormToken(fromCp(0x0424, 0x043e, 0x0440, 0x043c, 0x0430)));
        // Formy (plural) -> accepted.
        assertTrue(FormElementWriter.isFormToken(fromCp(0x0424, 0x043e, 0x0440, 0x043c, 0x044b)));
    }

    @Test
    public void testIsFormTokenRejectsOthers()
    {
        assertFalse(FormElementWriter.isFormToken("Template")); //$NON-NLS-1$
        assertFalse(FormElementWriter.isFormToken("CommonForm")); //$NON-NLS-1$
        assertFalse(FormElementWriter.isFormToken("")); //$NON-NLS-1$
        assertFalse(FormElementWriter.isFormToken(null));
    }

    // ---- move / reorder position resolution ------------------------------------------------------

    private static final List<String> SIBLINGS = Arrays.asList("A", "B", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    @Test
    public void testPositionLastAndDefault()
    {
        // null / blank / "last" -> the end (the dest list already EXCLUDES the moved item).
        assertEquals(3, FormElementWriter.resolveMovePosition(null, SIBLINGS, "X")); //$NON-NLS-1$
        assertEquals(3, FormElementWriter.resolveMovePosition("", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, FormElementWriter.resolveMovePosition("last", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, FormElementWriter.resolveMovePosition("LAST", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPositionFirst()
    {
        assertEquals(0, FormElementWriter.resolveMovePosition("first", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, FormElementWriter.resolveMovePosition("First", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPositionBeforeAndAfter()
    {
        // before:<name> = the sibling's own index; after:<name> = its index + 1 (case-insensitive).
        assertEquals(0, FormElementWriter.resolveMovePosition("before:A", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, FormElementWriter.resolveMovePosition("before:B", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, FormElementWriter.resolveMovePosition("after:A", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, FormElementWriter.resolveMovePosition("after:C", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, FormElementWriter.resolveMovePosition("BEFORE:c", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPositionInteger()
    {
        // A plain integer is the desired FINAL 0-based index as-is (no off-by-one compensation).
        assertEquals(0, FormElementWriter.resolveMovePosition("0", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, FormElementWriter.resolveMovePosition(" 2 ", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        // An index beyond the list end is returned verbatim; moveItem() then clamps it to the end.
        assertEquals(9, FormElementWriter.resolveMovePosition("9", SIBLINGS, "X")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPositionMalformedRejected()
    {
        assertMoveError("nonsense", SIBLINGS, "X", "Invalid position"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertMoveError("-1", SIBLINGS, "X", "zero or positive"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testPositionUnknownSiblingRejected()
    {
        assertMoveError("before:Z", SIBLINGS, "X", "not found"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertMoveError("after:", SIBLINGS, "X", "missing a sibling name"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testPositionCannotReferenceMovedItem()
    {
        // A before:/after: must not name the moved item itself (it is absent from the dest list anyway).
        assertMoveError("before:B", SIBLINGS, "B", "the moved item itself"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertMoveError("after:b", SIBLINGS, "B", "the moved item itself"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testPositionFirstLastOnEmptyDest()
    {
        // Into an empty group both first and last resolve to index 0.
        List<String> empty = Collections.emptyList();
        assertEquals(0, FormElementWriter.resolveMovePosition("first", empty, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, FormElementWriter.resolveMovePosition("last", empty, "X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, FormElementWriter.resolveMovePosition(null, empty, "X")); //$NON-NLS-1$
    }

    private static void assertMoveError(String position, List<String> dest, String moved, String fragment)
    {
        try
        {
            FormElementWriter.resolveMovePosition(position, dest, moved);
            fail("expected a RuntimeException for position '" + position + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (RuntimeException e)
        {
            assertNotNull(e.getMessage());
            assertTrue("message should mention '" + fragment + "' but was: " + e.getMessage(), //$NON-NLS-1$ //$NON-NLS-2$
                e.getMessage().contains(fragment));
        }
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
    public void testEnforceAutoCommandBarIdSentinelRestoresMinusOne()
    {
        EObject form = newForm();
        EObject bar = (EObject)form.eGet(feature(form, "autoCommandBar")); //$NON-NLS-1$
        // Simulate the BM integration (attachTopObject + fillDefaultReferences) resetting the
        // predefined bar's id back to the model default (0) - the regression behind issue #189.
        bar.eSet(feature(bar, "id"), Integer.valueOf(0)); //$NON-NLS-1$
        FormElementWriter.enforceAutoCommandBarIdSentinel(form);
        // The bar carries the -1 sentinel again, matching a designer-built form (<id>-1</id>), which
        // serializes as <id>-1</id> instead of being dropped (a dropped id resolves to 0 -> invalid).
        assertEquals(Integer.valueOf(-1), bar.eGet(feature(bar, "id"))); //$NON-NLS-1$
    }

    @Test
    public void testEnforceAutoCommandBarIdSentinelToleratesMissingBar()
    {
        // A form with no command bar (an ordinary/legacy form) must not fail.
        EObject form = newObject(MODEL.form);
        FormElementWriter.enforceAutoCommandBarIdSentinel(form);
        assertNull(form.eGet(feature(form, "autoCommandBar"))); //$NON-NLS-1$
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
    public void testCallTypeRejectedOnCommandAction()
    {
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        EObject command = FormElementWriter.findFormCommand(form, "Print"); //$NON-NLS-1$
        // callType is form-EVENT interception only (a form:EventHandlerExtension on a form item); a
        // form command action has no call type, so the new 7-arg overload rejects it.
        String err = FormElementWriter.createHandler(command, "Action", null, null, "en", "After", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new String[1]);
        assertNotNull(err);
        assertTrue(err.contains("command action")); //$NON-NLS-1$
    }

    // ---- general extInfo access (ensureExtInfo / resolveExtInfoEClass, #235) ----------------------

    @Test
    public void testEnsureExtInfoCreatesUsualGroupExtInfoForEmptyGroup()
    {
        // A UsualGroup with an empty <extInfo> slot gets its concrete UsualGroupExtInfo created and
        // linked - the derive-from-type path (generalized groupExtInfoClassifierFor).
        EObject group = newObject(MODEL.formGroup);
        setLiteral(group, "type", "UsualGroup"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject extInfo = FormElementWriter.ensureExtInfo(newForm(), group);
        assertNotNull(extInfo);
        assertEquals("UsualGroupExtInfo", extInfo.eClass().getName()); //$NON-NLS-1$
        // It is actually attached to the group's extInfo reference.
        assertSame(extInfo, group.eGet(feature(group, "extInfo"))); //$NON-NLS-1$
    }

    @Test
    public void testEnsureExtInfoIsIdempotentAndKeepsSetProperties()
    {
        // A 2nd ensureExtInfo returns the SAME instance and does NOT reset properties set on it.
        EObject form = newForm();
        EObject group = newObject(MODEL.formGroup);
        setLiteral(group, "type", "UsualGroup"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject first = FormElementWriter.ensureExtInfo(form, group);
        setLiteral(first, "group", "AlwaysHorizontal"); //$NON-NLS-1$ //$NON-NLS-2$
        first.eSet(feature(first, "united"), Boolean.TRUE); //$NON-NLS-1$
        EObject second = FormElementWriter.ensureExtInfo(form, group);
        assertSame(first, second);
        assertEquals("AlwaysHorizontal", literalOf(second, "group")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, second.eGet(feature(second, "united"))); //$NON-NLS-1$
    }

    @Test
    public void testEnsureExtInfoReusesPresetExtInfoWithoutClobber()
    {
        // A designer-created (already present) extInfo carrying a set 'group' is reused verbatim - never
        // re-created via setExtInfoClassifier (which would clobber the set layout property).
        EObject group = newObject(MODEL.formGroup);
        setLiteral(group, "type", "UsualGroup"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject preset = newObject(MODEL.usualGroupExtInfo);
        setLiteral(preset, "group", "Horizontal"); //$NON-NLS-1$ //$NON-NLS-2$
        group.eSet(feature(group, "extInfo"), preset); //$NON-NLS-1$
        EObject ensured = FormElementWriter.ensureExtInfo(newForm(), group);
        assertSame(preset, ensured);
        assertEquals("Horizontal", literalOf(ensured, "group")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEnsureExtInfoReplacesStaleExtInfoAfterTypeChange()
    {
        // #235 review: a form group whose `type` was changed via modify_metadata carries a STALE extInfo of
        // the OLD type. The `type` is authoritative: resolveExtInfoEClass must report the NEW type's class,
        // and ensureExtInfo must RECREATE the extInfo for the new type (not reuse the stale one) - so a
        // later layout write lands on the correct holder instead of the wrong-type extInfo.
        EObject form = newForm();
        EObject group = newObject(MODEL.formGroup);
        setLiteral(group, "type", "UsualGroup"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject usual = FormElementWriter.ensureExtInfo(form, group);
        assertEquals("UsualGroupExtInfo", usual.eClass().getName()); //$NON-NLS-1$
        // Change the classifier: the UsualGroupExtInfo is now stale for a Pages group.
        setLiteral(group, "type", "Pages"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the type is authoritative - resolveExtInfoEClass reports the NEW type's class", //$NON-NLS-1$
            "PagesGroupExtInfo", FormElementWriter.resolveExtInfoEClass(group).getName()); //$NON-NLS-1$
        EObject replaced = FormElementWriter.ensureExtInfo(form, group);
        assertEquals("ensureExtInfo recreates the extInfo for the new type", //$NON-NLS-1$
            "PagesGroupExtInfo", replaced.eClass().getName()); //$NON-NLS-1$
        assertNotSame("the stale UsualGroupExtInfo must be replaced, not reused", usual, replaced); //$NON-NLS-1$
        assertSame(replaced, group.eGet(feature(group, "extInfo"))); //$NON-NLS-1$
    }

    @Test
    public void testEnsureExtInfoNoOpWhenElementHasNoExtInfoSlot()
    {
        // A form root (mdclass-like: no extInfo feature) is a no-op - null, nothing set.
        EObject form = newForm();
        assertNull(FormElementWriter.ensureExtInfo(form, form));
        assertNull(FormElementWriter.resolveExtInfoEClass(form));
    }

    @Test
    public void testResolveExtInfoEClassForEmptyGroupDoesNotInstantiate()
    {
        // Read-only listing: the concrete class is derived from the group type WITHOUT creating an
        // instance (the extInfo slot stays empty).
        EObject group = newObject(MODEL.formGroup);
        setLiteral(group, "type", "UsualGroup"); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(MODEL.usualGroupExtInfo, FormElementWriter.resolveExtInfoEClass(group));
        assertNull(group.eGet(feature(group, "extInfo"))); //$NON-NLS-1$
        // A different group type resolves its own concrete extInfo (generalized mapping).
        EObject popup = newObject(MODEL.formGroup);
        setLiteral(popup, "type", "Popup"); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(modelClass("PopupGroupExtInfo"), FormElementWriter.resolveExtInfoEClass(popup)); //$NON-NLS-1$
    }

    @Test
    public void testResolveExtInfoEClassReusesExistingInstanceForAnyKind()
    {
        // The reuse path is element-agnostic: a field carrying an InputFieldExtInfo reports that concrete
        // class, so the general extInfo path is not group-only.
        EClass inputExtInfo = modelClass("InputFieldExtInfo"); //$NON-NLS-1$
        EObject field = newObject(modelClass("FormField")); //$NON-NLS-1$
        field.eSet(feature(field, "extInfo"), newObject(inputExtInfo)); //$NON-NLS-1$
        assertSame(inputExtInfo, FormElementWriter.resolveExtInfoEClass(field));
    }

    @Test
    public void testUsualGroupExtInfoCarriesLayoutFeatures()
    {
        // The synthetic UsualGroupExtInfo exposes the #235 layout features (so the A/C/D unit tests that
        // read/write them run headlessly).
        for (String featureName : new String[] {"group", "united", "showLeftMargin", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "throughAlign", "currentRowUse", "representation"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertNotNull(featureName + " must exist on UsualGroupExtInfo", //$NON-NLS-1$
                MODEL.usualGroupExtInfo.getEStructuralFeature(featureName));
        }
    }

    /**
     * Builds a synthetic {@code EventHandlerExtension} EClass carrying a {@code callType} EEnum shaped
     * like the form metamodel's {@code ExtendedMethodCallType} (literal == name), so the pure
     * call-type resolver can be exercised headlessly without the real form package.
     */
    private static EClass syntheticEventHandlerExtensionType()
    {
        EEnum callTypeEnum = EcoreFactory.eINSTANCE.createEEnum();
        callTypeEnum.setName("ExtendedMethodCallType"); //$NON-NLS-1$
        addEnumLiteral(callTypeEnum, "Before", 0); //$NON-NLS-1$
        addEnumLiteral(callTypeEnum, "After", 1); //$NON-NLS-1$
        addEnumLiteral(callTypeEnum, "ChangeAndValidate", 2); //$NON-NLS-1$
        addEnumLiteral(callTypeEnum, "Override", 3); //$NON-NLS-1$
        EAttribute callType = EcoreFactory.eINSTANCE.createEAttribute();
        callType.setName("callType"); //$NON-NLS-1$
        callType.setEType(callTypeEnum);
        EClass ehExt = EcoreFactory.eINSTANCE.createEClass();
        ehExt.setName("EventHandlerExtension"); //$NON-NLS-1$
        ehExt.getEStructuralFeatures().add(callType);
        EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        pkg.setName("form"); //$NON-NLS-1$
        pkg.setNsURI("http://g5.1c.ru/v8/dt/form/test"); //$NON-NLS-1$
        pkg.setNsPrefix("form"); //$NON-NLS-1$
        pkg.getEClassifiers().add(callTypeEnum);
        pkg.getEClassifiers().add(ehExt);
        return ehExt;
    }

    private static void addEnumLiteral(EEnum target, String name, int value)
    {
        EEnumLiteral lit = EcoreFactory.eINSTANCE.createEEnumLiteral();
        lit.setName(name);
        lit.setLiteral(name);
        lit.setValue(value);
        // bindEventHandler stores lit.getInstance(); a dynamic literal already returns itself (the impl
        // IS an Enumerator), matching how a generated form-model literal returns its enum constant.
        target.getELiterals().add(lit);
    }

    @Test
    public void testResolveEventCallTypeMapsTokensToLiterals()
    {
        EClass ehExt = syntheticEventHandlerExtensionType();
        // Before / After resolve to their own literals (case-insensitively, tolerating whitespace).
        assertEquals("Before", FormElementWriter.resolveEventCallType(ehExt, "Before").getName()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("After", FormElementWriter.resolveEventCallType(ehExt, "after").getName()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("After", FormElementWriter.resolveEventCallType(ehExt, "  After  ").getName()); //$NON-NLS-1$ //$NON-NLS-2$
        // The 1C UI label "Instead" (Вместо) maps to the EMF enum literal "Override".
        assertEquals("Override", FormElementWriter.resolveEventCallType(ehExt, "Instead").getName()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Override", FormElementWriter.resolveEventCallType(ehExt, "instead").getName()); //$NON-NLS-1$ //$NON-NLS-2$
        // The raw literal "Override" is also accepted.
        assertEquals("Override", FormElementWriter.resolveEventCallType(ehExt, "Override").getName()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testResolveEventCallTypeRejectsMethodOnlyAndUnknown()
    {
        EClass ehExt = syntheticEventHandlerExtensionType();
        // ChangeAndValidate is a METHOD call type, never valid for a form event (both spellings).
        assertNull(FormElementWriter.resolveEventCallType(ehExt, "ChangeAndValidate")); //$NON-NLS-1$
        assertNull(FormElementWriter.resolveEventCallType(ehExt, "CHANGE_AND_VALIDATE")); //$NON-NLS-1$
        // Unknown / empty tokens resolve to null (the caller then errors loudly).
        assertNull(FormElementWriter.resolveEventCallType(ehExt, "Nonsense")); //$NON-NLS-1$
        assertNull(FormElementWriter.resolveEventCallType(ehExt, "")); //$NON-NLS-1$
    }

    /**
     * A self-contained dynamic EMF model shaped like the form metamodel's handler containment: a
     * {@code FormField} container with a {@code handlers} containment list typed to base
     * {@code EventHandler} (which has {@code event} + {@code name}), and (optionally) the
     * {@code EventHandlerExtension} subtype with a {@code callType} EEnum. Lets {@link
     * FormElementWriter#bindEventHandler} be exercised headlessly, without the real form package.
     */
    private static final class HandlerModel
    {
        EObject container;
        EStructuralFeature handlersFeat;
        EObject event;
    }

    private static HandlerModel newHandlerModel(boolean withExtensionType)
    {
        EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        pkg.setName("form"); //$NON-NLS-1$
        pkg.setNsURI("http://g5.1c.ru/v8/dt/form/handlertest"); //$NON-NLS-1$
        pkg.setNsPrefix("form"); //$NON-NLS-1$

        EClass eventType = EcoreFactory.eINSTANCE.createEClass();
        eventType.setName("Event"); //$NON-NLS-1$
        pkg.getEClassifiers().add(eventType);

        EClass eventHandler = EcoreFactory.eINSTANCE.createEClass();
        eventHandler.setName("EventHandler"); //$NON-NLS-1$
        EReference eventRef = EcoreFactory.eINSTANCE.createEReference();
        eventRef.setName("event"); //$NON-NLS-1$
        eventRef.setEType(eventType);
        EAttribute nameAttr = EcoreFactory.eINSTANCE.createEAttribute();
        nameAttr.setName("name"); //$NON-NLS-1$
        nameAttr.setEType(EcorePackage.Literals.ESTRING);
        eventHandler.getEStructuralFeatures().add(eventRef);
        eventHandler.getEStructuralFeatures().add(nameAttr);
        pkg.getEClassifiers().add(eventHandler);

        if (withExtensionType)
        {
            EEnum callTypeEnum = EcoreFactory.eINSTANCE.createEEnum();
            callTypeEnum.setName("ExtendedMethodCallType"); //$NON-NLS-1$
            addEnumLiteral(callTypeEnum, "Before", 0); //$NON-NLS-1$
            addEnumLiteral(callTypeEnum, "After", 1); //$NON-NLS-1$
            addEnumLiteral(callTypeEnum, "ChangeAndValidate", 2); //$NON-NLS-1$
            addEnumLiteral(callTypeEnum, "Override", 3); //$NON-NLS-1$
            pkg.getEClassifiers().add(callTypeEnum);
            EClass ehExt = EcoreFactory.eINSTANCE.createEClass();
            ehExt.setName("EventHandlerExtension"); //$NON-NLS-1$
            ehExt.getESuperTypes().add(eventHandler);
            EAttribute callType = EcoreFactory.eINSTANCE.createEAttribute();
            callType.setName("callType"); //$NON-NLS-1$
            callType.setEType(callTypeEnum);
            ehExt.getEStructuralFeatures().add(callType);
            pkg.getEClassifiers().add(ehExt);
        }

        EClass field = EcoreFactory.eINSTANCE.createEClass();
        field.setName("FormField"); //$NON-NLS-1$
        EReference handlers = EcoreFactory.eINSTANCE.createEReference();
        handlers.setName("handlers"); //$NON-NLS-1$
        handlers.setEType(eventHandler);
        handlers.setContainment(true);
        handlers.setUpperBound(-1);
        field.getEStructuralFeatures().add(handlers);
        pkg.getEClassifiers().add(field);

        HandlerModel m = new HandlerModel();
        m.container = pkg.getEFactoryInstance().create(field);
        m.handlersFeat = field.getEStructuralFeature("handlers"); //$NON-NLS-1$
        m.event = pkg.getEFactoryInstance().create(eventType);
        return m;
    }

    private static String handlerCallTypeName(EObject handler)
    {
        EStructuralFeature ct = handler.eClass().getEStructuralFeature("callType"); //$NON-NLS-1$
        Object v = ct != null ? handler.eGet(ct) : null;
        return v instanceof Enumerator ? ((Enumerator)v).getName() : null;
    }

    @Test
    public void testBindEventHandlerBaseAndExtensionCoexist()
    {
        HandlerModel m = newHandlerModel(true);
        String[] baseKind = new String[1];
        // Base handler (no callType).
        assertNull(FormElementWriter.bindEventHandler(m.container, m.handlersFeat, m.event,
            "OnChange", "OnChange", null, baseKind)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("EventHandler", baseKind[0]); //$NON-NLS-1$
        // Extension After handler on the SAME event coexists with the base handler.
        String[] extKind = new String[1];
        assertNull(FormElementWriter.bindEventHandler(m.container, m.handlersFeat, m.event,
            "OnChange", "ext_OnChangeAfter", "After", extKind)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("EventHandlerExtension", extKind[0]); //$NON-NLS-1$
        List<?> handlers = (List<?>)m.container.eGet(m.handlersFeat);
        assertEquals("base + extension handler must coexist", 2, handlers.size()); //$NON-NLS-1$
        assertEquals("After", handlerCallTypeName((EObject)handlers.get(1))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBindEventHandlerDuplicateCallTypeRejectedOtherwiseCoexists()
    {
        HandlerModel m = newHandlerModel(true);
        assertNull(FormElementWriter.bindEventHandler(m.container, m.handlersFeat, m.event,
            "OnChange", "a", "After", new String[1])); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // A second After extension handler on the same event is a duplicate.
        String dup = FormElementWriter.bindEventHandler(m.container, m.handlersFeat, m.event,
            "OnChange", "b", "After", new String[1]); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(dup);
        assertTrue(dup.contains("already exists")); //$NON-NLS-1$
        // A DIFFERENT call type (Before) on the same event is allowed (coexists).
        assertNull(FormElementWriter.bindEventHandler(m.container, m.handlersFeat, m.event,
            "OnChange", "c", "Before", new String[1])); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(2, ((List<?>)m.container.eGet(m.handlersFeat)).size());
    }

    @Test
    public void testBindEventHandlerInsteadMapsToOverrideLiteral()
    {
        HandlerModel m = newHandlerModel(true);
        assertNull(FormElementWriter.bindEventHandler(m.container, m.handlersFeat, m.event,
            "OnChange", "ext_OnChangeInstead", "Instead", new String[1])); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<?> handlers = (List<?>)m.container.eGet(m.handlersFeat);
        assertEquals(1, handlers.size());
        // The 1C UI "Instead" is written as the EMF enum literal "Override".
        assertEquals("Override", handlerCallTypeName((EObject)handlers.get(0))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBindEventHandlerWithoutExtensionTypeErrors()
    {
        // A form model lacking the EventHandlerExtension type cannot host extension interception.
        HandlerModel m = newHandlerModel(false);
        String err = FormElementWriter.bindEventHandler(m.container, m.handlersFeat, m.event,
            "OnChange", "x", "After", new String[1]); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(err);
        assertTrue(err.contains("EventHandlerExtension")); //$NON-NLS-1$
        // The base path still works on the same model.
        assertNull(FormElementWriter.bindEventHandler(m.container, m.handlersFeat, m.event,
            "OnChange", "x", null, new String[1])); //$NON-NLS-1$ //$NON-NLS-2$
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
    public void testCreateFieldBindsToMainObjectSubAttribute()
    {
        // Issue #208 round 2 (Part 2): a Field may bind to a sub-attribute of the form's MAIN object
        // attribute via a dotted dataPath (e.g. 'Object.Number'). The head segment names the main
        // attribute (main=true), the tail is the object's sub-attribute. The build must succeed and
        // produce a 2-segment DataPath (Object / Number).
        EObject form = newForm();
        EObject objectAttr = newObject(MODEL.formAttribute);
        objectAttr.eSet(feature(objectAttr, "name"), "Object"); //$NON-NLS-1$ //$NON-NLS-2$
        objectAttr.eSet(feature(objectAttr, "main"), Boolean.TRUE); //$NON-NLS-1$
        addTo(form, "attributes", objectAttr); //$NON-NLS-1$

        assertNull(FormElementWriter.createMember(form, Kind.FIELD, "NumberField", null, //$NON-NLS-1$
            "Object.Number", null, null, false, null)); //$NON-NLS-1$
        EObject field = FormElementWriter.findFormItem(form, "NumberField"); //$NON-NLS-1$
        assertNotNull(field);
        // The dotted path resolved to a 2-segment DataPath (the validator walks Object -> Number),
        // byte-identical to the designer's bound object field.
        EObject dataPath = (EObject)field.eGet(feature(field, "dataPath")); //$NON-NLS-1$
        assertNotNull("the field must carry a contained DataPath", dataPath); //$NON-NLS-1$
        assertEquals("Object.Number must split into 2 segments", //$NON-NLS-1$
            Arrays.asList("Object", "Number"), //$NON-NLS-1$ //$NON-NLS-2$
            dataPath.eGet(feature(dataPath, "segments"))); //$NON-NLS-1$
        // The field still carries the designer InputField defaults (same path as a plain field).
        assertEquals("InputField", literalOf(field, "type")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCreateFieldRejectsDottedPathOnNonMainNonListAttribute()
    {
        // A dotted dataPath whose head attribute is neither the main object attribute nor a dynamic
        // list is still rejected (the only two valid dotted heads). The error names the head and the
        // two legitimate uses so the caller can self-correct.
        EObject form = newForm();
        EObject plainAttr = newObject(MODEL.formAttribute);
        plainAttr.eSet(feature(plainAttr, "name"), "Plain"); //$NON-NLS-1$ //$NON-NLS-2$
        addTo(form, "attributes", plainAttr); //$NON-NLS-1$

        String err = FormElementWriter.createMember(form, Kind.FIELD, "PlainSubField", null, //$NON-NLS-1$
            "Plain.Sub", null, null, false, null); //$NON-NLS-1$
        assertNotNull("a dotted path on a plain attribute must be rejected", err); //$NON-NLS-1$
        assertTrue("the error must name the offending head attribute", err.contains("Plain")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must mention the main object attribute as a valid dotted head", //$NON-NLS-1$
            err.contains("main object attribute")); //$NON-NLS-1$
        // Nothing was created.
        assertNull(FormElementWriter.findFormItem(form, "PlainSubField")); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeFormItemIdsRepairsAutoChildrenAndRootBar()
    {
        EObject form = newForm();
        EObject bar = (EObject)form.eGet(feature(form, "autoCommandBar")); //$NON-NLS-1$
        bar.eSet(feature(bar, "id"), Integer.valueOf(0)); //$NON-NLS-1$

        for (int i = 0; i < 7; i++)
        {
            String attrName = "Attr" + i; //$NON-NLS-1$
            EObject attribute = newObject(MODEL.formAttribute);
            attribute.eSet(feature(attribute, "name"), attrName); //$NON-NLS-1$
            addTo(form, "attributes", attribute); //$NON-NLS-1$

            String fieldName = "Field" + i; //$NON-NLS-1$
            assertNull(FormElementWriter.createMember(form, Kind.FIELD, fieldName, null, attrName,
                null, null, false, null));
            EObject field = FormElementWriter.findFormItem(form, fieldName);
            EObject menu = (EObject)field.eGet(feature(field, "contextMenu")); //$NON-NLS-1$
            EObject tooltip = (EObject)field.eGet(feature(field, "extendedTooltip")); //$NON-NLS-1$
            menu.eSet(feature(menu, "id"), Integer.valueOf(0)); //$NON-NLS-1$
            tooltip.eSet(feature(tooltip, "id"), Integer.valueOf(0)); //$NON-NLS-1$
        }

        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "PrintButton", //$NON-NLS-1$
            "AutoCommandBar", "Print", null, null, false, null)); //$NON-NLS-1$ //$NON-NLS-2$
        EObject button = FormElementWriter.findFormItem(form, "PrintButton"); //$NON-NLS-1$
        EObject buttonTooltip = (EObject)button.eGet(feature(button, "extendedTooltip")); //$NON-NLS-1$
        buttonTooltip.eSet(feature(buttonTooltip, "id"), Integer.valueOf(0)); //$NON-NLS-1$

        FormElementWriter.normalizeFormItemIds(form);

        assertEquals(Integer.valueOf(-1), bar.eGet(feature(bar, "id"))); //$NON-NLS-1$
        assertUniqueNonZeroFormItemIds(form);
    }

    @Test
    public void testCreateAttributeAssignsUniqueIdsInAttributeNamespace()
    {
        EObject form = newForm();

        assertNull(FormElementWriter.createMember(form, Kind.ATTRIBUTE, "Customer", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.ATTRIBUTE, "Total", null, null, //$NON-NLS-1$
            null, null, false, null));

        EObject customer = FormElementWriter.findFormAttribute(form, "Customer"); //$NON-NLS-1$
        EObject total = FormElementWriter.findFormAttribute(form, "Total"); //$NON-NLS-1$
        assertNotNull(customer);
        assertNotNull(total);
        assertEquals(Integer.valueOf(1), customer.eGet(feature(customer, "id"))); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), total.eGet(feature(total, "id"))); //$NON-NLS-1$

        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "Main", null, null, //$NON-NLS-1$
            null, null, false, null));
        EObject group = FormElementWriter.findFormItem(form, "Main"); //$NON-NLS-1$
        assertNotNull(group);
        assertEquals("attribute ids must not advance the form-item namespace", Integer.valueOf(1), //$NON-NLS-1$
            group.eGet(feature(group, "id"))); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeFormAttributeIdsRepairsDuplicatesWithoutChangingItemIds()
    {
        EObject form = newForm();
        EObject first = newObject(MODEL.formAttribute);
        first.eSet(feature(first, "name"), "First"); //$NON-NLS-1$ //$NON-NLS-2$
        first.eSet(feature(first, "id"), Integer.valueOf(0)); //$NON-NLS-1$
        addTo(form, "attributes", first); //$NON-NLS-1$
        EObject second = newObject(MODEL.formAttribute);
        second.eSet(feature(second, "name"), "Second"); //$NON-NLS-1$ //$NON-NLS-2$
        second.eSet(feature(second, "id"), Integer.valueOf(0)); //$NON-NLS-1$
        addTo(form, "attributes", second); //$NON-NLS-1$

        EObject group = newObject(MODEL.formGroup);
        group.eSet(feature(group, "name"), "Main"); //$NON-NLS-1$ //$NON-NLS-2$
        group.eSet(feature(group, "id"), Integer.valueOf(9)); //$NON-NLS-1$
        addTo(form, "items", group); //$NON-NLS-1$

        FormElementWriter.normalizeFormAttributeIds(form);

        Set<Integer> ids = new HashSet<>();
        assertTrue(((Integer)first.eGet(feature(first, "id"))).intValue() > 0); //$NON-NLS-1$
        assertTrue(ids.add((Integer)first.eGet(feature(first, "id")))); //$NON-NLS-1$
        assertTrue(((Integer)second.eGet(feature(second, "id"))).intValue() > 0); //$NON-NLS-1$
        assertTrue(ids.add((Integer)second.eGet(feature(second, "id")))); //$NON-NLS-1$
        assertEquals(Integer.valueOf(9), group.eGet(feature(group, "id"))); //$NON-NLS-1$
    }

    @Test
    public void testCreateCommandAssignsUniqueIdsInCommandNamespace()
    {
        EObject form = newForm();

        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Run", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Stop", null, null, //$NON-NLS-1$
            null, null, false, null));

        EObject run = FormElementWriter.findFormCommand(form, "Run"); //$NON-NLS-1$
        EObject stop = FormElementWriter.findFormCommand(form, "Stop"); //$NON-NLS-1$
        assertNotNull(run);
        assertNotNull(stop);
        assertEquals(Integer.valueOf(1), run.eGet(feature(run, "id"))); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), stop.eGet(feature(stop, "id"))); //$NON-NLS-1$

        assertNull(FormElementWriter.createMember(form, Kind.ATTRIBUTE, "Customer", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "Main", null, null, //$NON-NLS-1$
            null, null, false, null));
        EObject customer = FormElementWriter.findFormAttribute(form, "Customer"); //$NON-NLS-1$
        EObject group = FormElementWriter.findFormItem(form, "Main"); //$NON-NLS-1$
        assertNotNull(customer);
        assertNotNull(group);
        assertEquals("command ids must not advance the form-attribute namespace", Integer.valueOf(1), //$NON-NLS-1$
            customer.eGet(feature(customer, "id"))); //$NON-NLS-1$
        assertEquals("command ids must not advance the form-item namespace", Integer.valueOf(1), //$NON-NLS-1$
            group.eGet(feature(group, "id"))); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeFormCommandIdsRepairsDuplicatesWithoutChangingOtherIds()
    {
        EObject form = newForm();
        EObject first = newObject(MODEL.formCommand);
        first.eSet(feature(first, "name"), "First"); //$NON-NLS-1$ //$NON-NLS-2$
        first.eSet(feature(first, "id"), Integer.valueOf(0)); //$NON-NLS-1$
        addTo(form, "formCommands", first); //$NON-NLS-1$
        EObject second = newObject(MODEL.formCommand);
        second.eSet(feature(second, "name"), "Second"); //$NON-NLS-1$ //$NON-NLS-2$
        second.eSet(feature(second, "id"), Integer.valueOf(0)); //$NON-NLS-1$
        addTo(form, "formCommands", second); //$NON-NLS-1$

        EObject attribute = newObject(MODEL.formAttribute);
        attribute.eSet(feature(attribute, "name"), "Customer"); //$NON-NLS-1$ //$NON-NLS-2$
        attribute.eSet(feature(attribute, "id"), Integer.valueOf(7)); //$NON-NLS-1$
        addTo(form, "attributes", attribute); //$NON-NLS-1$

        EObject group = newObject(MODEL.formGroup);
        group.eSet(feature(group, "name"), "Main"); //$NON-NLS-1$ //$NON-NLS-2$
        group.eSet(feature(group, "id"), Integer.valueOf(9)); //$NON-NLS-1$
        addTo(form, "items", group); //$NON-NLS-1$

        FormElementWriter.normalizeFormCommandIds(form);

        Set<Integer> ids = new HashSet<>();
        assertTrue(((Integer)first.eGet(feature(first, "id"))).intValue() > 0); //$NON-NLS-1$
        assertTrue(ids.add((Integer)first.eGet(feature(first, "id")))); //$NON-NLS-1$
        assertTrue(((Integer)second.eGet(feature(second, "id"))).intValue() > 0); //$NON-NLS-1$
        assertTrue(ids.add((Integer)second.eGet(feature(second, "id")))); //$NON-NLS-1$
        assertEquals(Integer.valueOf(7), attribute.eGet(feature(attribute, "id"))); //$NON-NLS-1$
        assertEquals(Integer.valueOf(9), group.eGet(feature(group, "id"))); //$NON-NLS-1$
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

    // ---- moveItem destination contract (blank / form-name parent -> the form root; null
    // parent -> reorder in place; named-resolution ambiguity guard) - on the form-like model -------

    @Test
    public void testMoveItemBlankParentMovesToFormRoot()
    {
        // The 'parent' contract: a BLANK targetParent means the FORM ROOT - it must re-parent, not
        // fall into the reorder-in-place branch (which would silently leave the item in its group).
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "G", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.DECORATION, "D", "G", null, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        String dest = FormElementWriter.moveItem(form, "D", "", null, "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(dest, dest.contains("the form root")); //$NON-NLS-1$
        EObject deco = FormElementWriter.findFormItem(form, "D"); //$NON-NLS-1$
        assertSame(form, deco.eContainer());
        EObject group = FormElementWriter.findFormItem(form, "G"); //$NON-NLS-1$
        assertEquals(0, ((List<?>)group.eGet(feature(group, "items"))).size()); //$NON-NLS-1$
    }

    @Test
    public void testMoveItemFormNameParentMovesToFormRoot()
    {
        // The form name (case-insensitive) as targetParent is the other spelling of "the form root".
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "G", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.DECORATION, "D", "G", null, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        String dest = FormElementWriter.moveItem(form, "D", "myform", null, "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(dest, dest.contains("the form root")); //$NON-NLS-1$
        assertSame(form, FormElementWriter.findFormItem(form, "D").eContainer()); //$NON-NLS-1$
    }

    @Test
    public void testMoveItemNullParentReordersInCurrentContainer()
    {
        // null targetParent keeps the current container (reorder in place) - never re-parents.
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "G", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.DECORATION, "A", "G", null, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.DECORATION, "B", "G", null, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        String dest = FormElementWriter.moveItem(form, "A", null, "last", "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(dest, dest.contains("group 'G'")); //$NON-NLS-1$
        EObject group = FormElementWriter.findFormItem(form, "G"); //$NON-NLS-1$
        List<?> items = (List<?>)group.eGet(feature(group, "items")); //$NON-NLS-1$
        assertEquals(2, items.size());
        assertSame(FormElementWriter.findFormItem(form, "B"), items.get(0)); //$NON-NLS-1$
        assertSame(FormElementWriter.findFormItem(form, "A"), items.get(1)); //$NON-NLS-1$
    }

    @Test
    public void testMoveItemNamedGroupParentStillReparents()
    {
        // Regression guard: a real group name still re-parents into that group, at the requested
        // position ('first' -> index 0 in the destination payload).
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "G", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.DECORATION, "InG", "G", null, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.DECORATION, "D", null, null, //$NON-NLS-1$
            null, null, false, null));
        String dest = FormElementWriter.moveItem(form, "D", "G", "first", "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(dest, dest.contains("group 'G'")); //$NON-NLS-1$
        assertTrue(dest, dest.contains("at index 0")); //$NON-NLS-1$
        EObject group = FormElementWriter.findFormItem(form, "G"); //$NON-NLS-1$
        List<?> items = (List<?>)group.eGet(feature(group, "items")); //$NON-NLS-1$
        assertSame(FormElementWriter.findFormItem(form, "D"), items.get(0)); //$NON-NLS-1$
    }

    @Test
    public void testMoveItemAmbiguousNameRejected()
    {
        // The name-resolving overload REJECTS an ambiguous item name instead of silently moving the
        // first match (the EObject-based move never sees the ambiguity - its caller resolved already).
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "G", null, null, //$NON-NLS-1$
            null, null, false, null));
        EObject d1 = newObject(MODEL.decoration);
        d1.eSet(feature(d1, "name"), "Dup"); //$NON-NLS-1$ //$NON-NLS-2$
        addTo(form, "items", d1); //$NON-NLS-1$
        EObject d2 = newObject(MODEL.decoration);
        d2.eSet(feature(d2, "name"), "Dup"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject group = FormElementWriter.findFormItem(form, "G"); //$NON-NLS-1$
        addTo(group, "items", d2); //$NON-NLS-1$
        try
        {
            FormElementWriter.moveItem(form, "Dup", null, "first", "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("an ambiguous item name must be rejected"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            assertNotNull(e.getMessage());
            assertTrue(e.getMessage(), e.getMessage().contains("ambiguous")); //$NON-NLS-1$
        }
    }

    @Test
    public void testMoveItemMissingNameRejected()
    {
        EObject form = newForm();
        try
        {
            FormElementWriter.moveItem(form, "NoSuch", null, null, "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a missing item name must be rejected"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            assertNotNull(e.getMessage());
            assertTrue(e.getMessage(), e.getMessage().contains("not found")); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("get_metadata_details")); //$NON-NLS-1$
        }
    }

    @Test
    public void testMoveItemCycleRejectedViaNameOverload()
    {
        // The cycle guard surfaces through the name overload as a thrown, user-facing error that
        // names BOTH spellings ("itself" / "descendant" for the e2e contract, "its own contained
        // item" for the designer-parity wording).
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "Outer", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.GROUP, "Inner", "Outer", null, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        try
        {
            FormElementWriter.moveItem(form, "Outer", "Inner", null, "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a containment cycle must be rejected"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            assertNotNull(e.getMessage());
            assertTrue(e.getMessage(), e.getMessage().contains("itself")); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("descendant")); //$NON-NLS-1$
        }
    }

    @Test
    public void testMoveItemIntoBarByNameRetypesButton()
    {
        // The name overload resolves 'AutoCommandBar' like a create parent and re-derives the
        // button type on the bar boundary - the same designer parity the EObject move has.
        EObject form = newForm();
        assertNull(FormElementWriter.createMember(form, Kind.COMMAND, "Print", null, null, //$NON-NLS-1$
            null, null, false, null));
        assertNull(FormElementWriter.createMember(form, Kind.BUTTON, "Btn", null, "Print", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null));
        String dest = FormElementWriter.moveItem(form, "Btn", "AutoCommandBar", "first", "MyForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(dest, dest.contains("at index 0")); //$NON-NLS-1$
        EObject button = FormElementWriter.findFormItem(form, "Btn"); //$NON-NLS-1$
        EObject bar = (EObject)form.eGet(feature(form, "autoCommandBar")); //$NON-NLS-1$
        assertSame(bar, button.eContainer());
        assertEquals("CommandBarButton", literalOf(button, "type")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== whole-form creation (reflective, the REAL EDT packages) =================
    // The form EPackage is resolved from the global EMF package registry by nsURI - the design rule:
    // NO compile-time dependency on com._1c.g5.v8.dt.form.model anywhere in the server bundle (the
    // mdclass metamodel cannot lead there: BasicForm.form is typed by the mdclass-own AbstractForm
    // base). The form model bundle is in the OSGi test runtime transitively, so the registry chain
    // and the reflective whole-form defaults ARE headless-testable here.

    @Test
    public void testContentFormEClassReachableWithoutFormModelImport()
    {
        EClass formEClass = FormElementWriter.contentFormEClass();
        assertNotNull(formEClass);
        // The CONCRETE Form (the reference EType is the AbstractForm base on current EDT).
        assertEquals("Form", formEClass.getName()); //$NON-NLS-1$
        assertFalse(formEClass.isAbstract());
        EPackage formPkg = formEClass.getEPackage();
        assertNotNull(formPkg);
        // The sibling classifiers the whole-form build resolves by name on that package.
        assertTrue(formPkg.getEClassifier("AutoCommandBar") instanceof EClass); //$NON-NLS-1$
        assertTrue(formPkg.getEClassifier("FormCommandInterface") instanceof EClass); //$NON-NLS-1$
        assertTrue(formPkg.getEClassifier("FormCommandInterfaceItems") instanceof EClass); //$NON-NLS-1$
    }

    @Test
    public void testCreateContentFormDefaultsOnRealFormPackage()
    {
        // No FORM factory (null, like a missing injector) and no version (null = the legacy shape,
        // preserving the writer's previous behavior): the reflective fallback must still build a
        // renderable content form with the designer defaults the typed build used to set.
        EObject content = FormElementWriter.createContentForm(null, null, null, true);
        assertNotNull(content);
        assertEquals("Form", content.eClass().getName()); //$NON-NLS-1$
        // The eight form flags.
        for (String flag : new String[] { "saveWindowSettings", "autoTitle", "autoUrl", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "autoFillCheck", "allowFormCustomize", "enabled", "showTitle", "showCloseButton" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertEquals(flag, Boolean.TRUE, content.eGet(feature(content, flag)));
        }
        // The children grouping FormChildrenGroup.VERTICAL.
        assertEquals("Vertical", literalOf(content, "group")); //$NON-NLS-1$ //$NON-NLS-2$
        // The render-critical predefined auto command bar: autoFill, LEFT, the -1 id sentinel and
        // the canonical Russian predefined-command-bar name (russianAutoNames=true;
        // FormaKomandnayaPanel, from code points).
        EObject bar = (EObject)content.eGet(feature(content, "autoCommandBar")); //$NON-NLS-1$
        assertNotNull("the WYSIWYG generator requires the predefined autoCommandBar", bar); //$NON-NLS-1$
        assertEquals("AutoCommandBar", bar.eClass().getName()); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, bar.eGet(feature(bar, "autoFill"))); //$NON-NLS-1$
        assertEquals("Left", literalOf(bar, "horizontalAlign")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(-1), bar.eGet(feature(bar, "id"))); //$NON-NLS-1$
        String ruBarName = fromCp(0x0424, 0x043e, 0x0440, 0x043c, 0x0430, 0x041a, 0x043e, 0x043c,
            0x0430, 0x043d, 0x0434, 0x043d, 0x0430, 0x044f, 0x041f, 0x0430, 0x043d, 0x0435, 0x043b,
            0x044c);
        assertEquals(ruBarName, bar.eGet(feature(bar, "name"))); //$NON-NLS-1$
        // The (empty) command interface holding an empty navigation panel and command bar.
        EObject commandInterface = (EObject)content.eGet(feature(content, "commandInterface")); //$NON-NLS-1$
        assertNotNull(commandInterface);
        assertNotNull(commandInterface.eGet(feature(commandInterface, "navigationPanel"))); //$NON-NLS-1$
        assertNotNull(commandInterface.eGet(feature(commandInterface, "commandBar"))); //$NON-NLS-1$
    }

    @Test
    public void testCreateContentFormEnglishBarName()
    {
        // russianAutoNames=false (English script variant): the fallback predefined command bar gets
        // the canonical English name, like the designer's default-name provider builds it
        // (getFormDefaultName 'Form' + the COMMAND_BAR item name 'CommandBar').
        EObject content = FormElementWriter.createContentForm(null, null, null, false);
        EObject bar = (EObject)content.eGet(feature(content, "autoCommandBar")); //$NON-NLS-1$
        assertNotNull(bar);
        assertEquals("FormCommandBar", bar.eGet(feature(bar, "name"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCreateContentFormPre851VersionBranch()
    {
        // version < 8.5.1 (and <= 8.3.22): the designer wizard (FormObjectFactory.newForm) uses the
        // legacy children grouping VERTICAL, the legacy boolean showTitle=true, and does NOT set
        // saveWindowSettings (only versions > 8.3.22 get it).
        EObject content = FormElementWriter.createContentForm(null, null, Version.V8_3_20, true);
        assertEquals("Vertical", literalOf(content, "group")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, content.eGet(feature(content, "showTitle"))); //$NON-NLS-1$
        assertEquals("saveWindowSettings is only set for versions > 8.3.22", //$NON-NLS-1$
            Boolean.FALSE, content.eGet(feature(content, "saveWindowSettings"))); //$NON-NLS-1$
        // The version-independent flags stay set.
        assertEquals(Boolean.TRUE, content.eGet(feature(content, "autoTitle"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, content.eGet(feature(content, "showCloseButton"))); //$NON-NLS-1$
    }

    @Test
    public void testCreateContentFormModern851VersionBranch()
    {
        // version >= 8.5.1: the wizard uses group=AUTO and showTitle851=AUTO (NOT the legacy boolean
        // showTitle), and saveWindowSettings=true (8.5.1 > 8.3.22). The ShowTitle851 enum's literal
        // string is "auto" while its name is "Auto" - the writer must resolve either.
        EObject content = FormElementWriter.createContentForm(null, null, Version.V8_5_1, true);
        assertEquals("Auto", literalOf(content, "group")); //$NON-NLS-1$ //$NON-NLS-2$
        String showTitle851 = literalOf(content, "showTitle851"); //$NON-NLS-1$
        assertNotNull("showTitle851 must be set on the 8.5.1+ branch", showTitle851); //$NON-NLS-1$
        assertTrue("showTitle851 must be Auto but was: " + showTitle851, //$NON-NLS-1$
            "Auto".equalsIgnoreCase(showTitle851)); //$NON-NLS-1$
        assertEquals("the legacy showTitle boolean is not set on the 8.5.1+ branch", //$NON-NLS-1$
            Boolean.FALSE, content.eGet(feature(content, "showTitle"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, content.eGet(feature(content, "saveWindowSettings"))); //$NON-NLS-1$
    }

    @Test
    public void testCreateContentFormGenerateContentSeedsMainObjectAttribute()
    {
        // generateContent=true (issue #208): the content form is seeded with the main 'Object'
        // attribute like the designer's object-form wizard - name Object, main=true, savedData=true. The
        // value type (<Type>Object.<Name>) is read from the owner's OWN produced object type, which needs
        // a model-resolved owner; headless (owner == null) it is left unset, so the name/main/savedData
        // flags are the headless must-haves and the value type is proven by the e2e/live byte-diff.
        EObject content = FormElementWriter.createContentForm(null, null, Version.V8_5_1, false,
            true, "Document"); //$NON-NLS-1$
        assertNotNull(content);
        List<?> attributes = (List<?>)content.eGet(feature(content, "attributes")); //$NON-NLS-1$
        assertEquals("generateContent must seed exactly one (main Object) attribute", //$NON-NLS-1$
            1, attributes.size());
        EObject mainAttr = (EObject)attributes.get(0);
        assertEquals("the seeded attribute is named Object", //$NON-NLS-1$
            "Object", mainAttr.eGet(feature(mainAttr, "name"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the seeded Object attribute must be the form's main attribute", //$NON-NLS-1$
            Boolean.TRUE, mainAttr.eGet(feature(mainAttr, "main"))); //$NON-NLS-1$
        assertEquals("the seeded Object attribute must carry savedData", //$NON-NLS-1$
            Boolean.TRUE, mainAttr.eGet(feature(mainAttr, "savedData"))); //$NON-NLS-1$
        // The designer's predefined Object attribute also carries view/edit = common("use"); the seed
        // must match it byte-for-byte (issue #208 review). The real form metamodel types view/edit by
        // AdjustableBoolean - assert each was created with common=true.
        EObject view = (EObject)mainAttr.eGet(feature(mainAttr, "view")); //$NON-NLS-1$
        assertNotNull("the seeded Object attribute must carry a view AdjustableBoolean", view); //$NON-NLS-1$
        assertEquals("the seeded Object attribute's view must be common ('use')", //$NON-NLS-1$
            Boolean.TRUE, view.eGet(feature(view, "common"))); //$NON-NLS-1$
        EObject edit = (EObject)mainAttr.eGet(feature(mainAttr, "edit")); //$NON-NLS-1$
        assertNotNull("the seeded Object attribute must carry an edit AdjustableBoolean", edit); //$NON-NLS-1$
        assertEquals("the seeded Object attribute's edit must be common ('use')", //$NON-NLS-1$
            Boolean.TRUE, edit.eGet(feature(edit, "common"))); //$NON-NLS-1$
        // The attribute gets a positive id in the form-attribute id space (survives normalize).
        assertTrue("the seeded attribute must get a positive form-attribute id", //$NON-NLS-1$
            ((Integer)mainAttr.eGet(feature(mainAttr, "id"))).intValue() > 0); //$NON-NLS-1$
        // The render-critical command bar still carries the -1 sentinel (issue #189 untouched).
        EObject bar = (EObject)content.eGet(feature(content, "autoCommandBar")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(-1), bar.eGet(feature(bar, "id"))); //$NON-NLS-1$
    }

    @Test
    public void testCreateContentFormGenerateContentSkippedForNonObjectOwner()
    {
        // generateContent must NOT seed an Object attribute for a record-based owner (registers) or any
        // non-object-form type: those forms' main data source is not a <Type>Object value type, so a
        // seeded 'Object' attribute would be semantically wrong (issue #208 review). Even with
        // generateContent=true the form stays EMPTY for an InformationRegister / Constant owner.
        EObject registerForm = FormElementWriter.createContentForm(null, null, Version.V8_5_1, false,
            true, "InformationRegister"); //$NON-NLS-1$
        assertEquals("a register owner must not get a seeded Object attribute", 0, //$NON-NLS-1$
            ((List<?>)registerForm.eGet(feature(registerForm, "attributes"))).size()); //$NON-NLS-1$
        EObject constantForm = FormElementWriter.createContentForm(null, null, Version.V8_5_1, false,
            true, "Constant"); //$NON-NLS-1$
        assertEquals("a constant owner must not get a seeded Object attribute", 0, //$NON-NLS-1$
            ((List<?>)constantForm.eGet(feature(constantForm, "attributes"))).size()); //$NON-NLS-1$
        // An unknown / null owner type is likewise not seeded (defensive).
        EObject unknownForm = FormElementWriter.createContentForm(null, null, Version.V8_5_1, false,
            true, null);
        assertEquals("an unknown owner type must not get a seeded Object attribute", 0, //$NON-NLS-1$
            ((List<?>)unknownForm.eGet(feature(unknownForm, "attributes"))).size()); //$NON-NLS-1$
    }

    @Test
    public void testCreateContentFormGenerateContentRussianAttributeName()
    {
        // In a Russian script variant the main attribute Name is the localized 'Объект' (== Object),
        // built independently from code points (not a round-trip of the writer's own literal).
        EObject content = FormElementWriter.createContentForm(null, null, Version.V8_5_1, true,
            true, "Catalog"); //$NON-NLS-1$
        List<?> attributes = (List<?>)content.eGet(feature(content, "attributes")); //$NON-NLS-1$
        assertEquals(1, attributes.size());
        EObject mainAttr = (EObject)attributes.get(0);
        // Объект (Obyekt).
        String ruObject = fromCp(0x041e, 0x0431, 0x044a, 0x0435, 0x043a, 0x0442);
        assertEquals("the Russian script variant names the main attribute Объект", //$NON-NLS-1$
            ruObject, mainAttr.eGet(feature(mainAttr, "name"))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, mainAttr.eGet(feature(mainAttr, "main"))); //$NON-NLS-1$
    }

    @Test
    public void testCreateContentFormWithoutGenerateContentSeedsNoAttributes()
    {
        // Default (generateContent omitted / false): the form stays EMPTY - byte-stable existing
        // behaviour. Both the legacy 4-arg overload and the explicit false must seed zero attributes.
        EObject viaOverload = FormElementWriter.createContentForm(null, null, Version.V8_5_1, false);
        assertEquals("the empty-form overload must seed no attributes", //$NON-NLS-1$
            0, ((List<?>)viaOverload.eGet(feature(viaOverload, "attributes"))).size()); //$NON-NLS-1$
        EObject explicitFalse = FormElementWriter.createContentForm(null, null, Version.V8_5_1, false,
            false, "Document"); //$NON-NLS-1$
        assertEquals("generateContent=false must seed no attributes", //$NON-NLS-1$
            0, ((List<?>)explicitFalse.eGet(feature(explicitFalse, "attributes"))).size()); //$NON-NLS-1$
    }

    @Test
    public void testResolveObjectFieldsPerKindDefaultsAndExplicitList()
    {
        // Issue #208 round 2 (Part 1): the object-field resolution mirrors the designer's checkbox list.
        // OMITTED (null) -> the per-kind defaults. English script variant.
        assertEquals("a document with no objectFields defaults to Number/Date", //$NON-NLS-1$
            Arrays.asList("Number", "Date"), //$NON-NLS-1$ //$NON-NLS-2$
            FormElementWriter.resolveObjectFields("Document", null, false)); //$NON-NLS-1$
        assertEquals("a catalog with no objectFields defaults to Code/Description", //$NON-NLS-1$
            Arrays.asList("Code", "Description"), //$NON-NLS-1$ //$NON-NLS-2$
            FormElementWriter.resolveObjectFields("Catalog", null, false)); //$NON-NLS-1$
        // Other object kinds default to NO fields (only the main Object attribute).
        assertTrue("a report defaults to no object fields", //$NON-NLS-1$
            FormElementWriter.resolveObjectFields("Report", null, false).isEmpty()); //$NON-NLS-1$
        // An EXPLICIT list is taken verbatim regardless of the kind.
        assertEquals("an explicit list is taken verbatim", //$NON-NLS-1$
            Arrays.asList("Number", "Posted", "Comment"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            FormElementWriter.resolveObjectFields("Document", //$NON-NLS-1$
                Arrays.asList("Number", "Posted", "Comment"), false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // An EXPLICIT EMPTY list -> no fields (overrides the per-kind default).
        assertTrue("an explicit empty list yields no fields", //$NON-NLS-1$
            FormElementWriter.resolveObjectFields("Document", Collections.emptyList(), false).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testResolveObjectFieldsRussianDefaults()
    {
        // In a Russian script variant the standard-attribute programmatic names are Russian (a dataPath
        // segment IS the programmatic name), so the per-kind defaults are localized.
        assertEquals("a Russian document defaults to Номер/Дата", //$NON-NLS-1$
            Arrays.asList(fromCp(0x041d, 0x043e, 0x043c, 0x0435, 0x0440), // Nomer
                fromCp(0x0414, 0x0430, 0x0442, 0x0430)), // Data
            FormElementWriter.resolveObjectFields("Document", null, true)); //$NON-NLS-1$
        assertEquals("a Russian catalog defaults to Код/Наименование", //$NON-NLS-1$
            Arrays.asList(fromCp(0x041a, 0x043e, 0x0434), // Kod
                fromCp(0x041d, 0x0430, 0x0438, 0x043c, 0x0435, 0x043d, 0x043e, 0x0432, 0x0430, 0x043d,
                    0x0438, 0x0435)), // Naimenovanie
            FormElementWriter.resolveObjectFields("Catalog", null, true)); //$NON-NLS-1$
    }

    @Test
    public void testCreateContentFormGenerateContentSeedsBoundObjectFields()
    {
        // Issue #208 round 2 (Part 1): with an explicit object-field list, the seeded object form carries
        // a bound InputField per name (dataPath Object.<name>) under the form root, after the main
        // attribute - mirroring the designer's checked-attribute output. The list resolution itself is
        // covered by testResolveObjectFields*; createForm threads the resolved list to this overload.
        EObject content = FormElementWriter.createContentForm(null, null, Version.V8_5_1, false, true,
            "Document", Arrays.asList("Number", "Date")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(content);
        // The main Object attribute is still seeded (one attribute).
        assertEquals("only the main Object attribute lives in attributes (fields are items)", //$NON-NLS-1$
            1, ((List<?>)content.eGet(feature(content, "attributes"))).size()); //$NON-NLS-1$
        // The two bound fields are form ITEMS (under the root), each an InputField bound to Object.<name>.
        EObject numberField = FormElementWriter.findFormItem(content, "Number"); //$NON-NLS-1$
        EObject dateField = FormElementWriter.findFormItem(content, "Date"); //$NON-NLS-1$
        assertNotNull("the Number field must be seeded", numberField); //$NON-NLS-1$
        assertNotNull("the Date field must be seeded", dateField); //$NON-NLS-1$
        assertEquals("InputField", literalOf(numberField, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertObjectSubPath(numberField, "Number"); //$NON-NLS-1$
        assertObjectSubPath(dateField, "Date"); //$NON-NLS-1$
        // Issue #208: the designer's object-form wizard creates these bound object fields with editMode
        // "EnterOnInput" (not createField's standalone "Enter" default), so seedObjectFields re-sets them.
        assertEquals("a seeded object field must be EnterOnInput like the designer's", //$NON-NLS-1$
            "EnterOnInput", literalOf(numberField, "editMode")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a seeded object field must be EnterOnInput like the designer's", //$NON-NLS-1$
            "EnterOnInput", literalOf(dateField, "editMode")); //$NON-NLS-1$ //$NON-NLS-2$
        // Each seeded field reuses createField, so it carries the designer auto-children (a context menu
        // + an extended tooltip) - byte-diff parity with a manually-created field.
        assertNotNull("the seeded field must carry the auto context menu", //$NON-NLS-1$
            numberField.eGet(feature(numberField, "contextMenu"))); //$NON-NLS-1$
        assertNotNull("the seeded field must carry the auto extended tooltip", //$NON-NLS-1$
            numberField.eGet(feature(numberField, "extendedTooltip"))); //$NON-NLS-1$
    }

    @Test
    public void testCreateContentFormGenerateContentEmptyObjectFieldsSeedsOnlyMainAttribute()
    {
        // An explicit EMPTY object-field list -> only the main Object attribute, no bound fields (the
        // 'main attribute only' choice from the designer's list). The form items stay empty.
        EObject content = FormElementWriter.createContentForm(null, null, Version.V8_5_1, false, true,
            "Document", Collections.emptyList()); //$NON-NLS-1$
        assertEquals("the main Object attribute is still seeded", //$NON-NLS-1$
            1, ((List<?>)content.eGet(feature(content, "attributes"))).size()); //$NON-NLS-1$
        assertEquals("an empty object-field list seeds no bound fields", //$NON-NLS-1$
            0, ((List<?>)content.eGet(feature(content, "items"))).size()); //$NON-NLS-1$
    }

    @Test
    public void testValidateObjectFieldsRejectsUnknownNameAndListsAvailable()
    {
        // Issue #208 round 2 (review): an EXPLICIT objectFields name that is not a bindable sub-attribute
        // of the owner's Object is an actionable error - it must NAME the bad value and LIST the available
        // names so the caller can self-correct (the error-shape sentinel the ratchet enforces).
        EObject owner = newOwnerWithAttributes("Posted", "Comment"); //$NON-NLS-1$ //$NON-NLS-2$
        String err = FormElementWriter.validateObjectFields(owner,
            Arrays.asList("BogusAttr_zz")); //$NON-NLS-1$
        assertNotNull("an unknown objectFields name must be rejected", err); //$NON-NLS-1$
        assertTrue("the error must name the offending value", err.contains("BogusAttr_zz")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must offer the available names", err.contains("Available:")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must list a real bindable attribute", err.contains("Posted")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the available list must be the owner's own attributes", err.contains("Comment")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testValidateObjectFieldsAcceptsKnownNameAndNullEmpty()
    {
        // A name that IS a bindable sub-attribute (case-insensitively), and the null / empty / owner-less
        // inputs, all pass (return null - no rejection). A valid name must not be turned into an error.
        EObject owner = newOwnerWithAttributes("Posted", "Comment"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a known attribute name (exact) must be accepted", //$NON-NLS-1$
            FormElementWriter.validateObjectFields(owner, Arrays.asList("Posted"))); //$NON-NLS-1$
        assertNull("a known attribute name (case-insensitive) must be accepted", //$NON-NLS-1$
            FormElementWriter.validateObjectFields(owner, Arrays.asList("comment"))); //$NON-NLS-1$
        assertNull("a null list is not validated", //$NON-NLS-1$
            FormElementWriter.validateObjectFields(owner, null));
        assertNull("an empty list is not validated", //$NON-NLS-1$
            FormElementWriter.validateObjectFields(owner, Collections.<String>emptyList()));
        assertNull("a null owner is not validated", //$NON-NLS-1$
            FormElementWriter.validateObjectFields(null, Arrays.asList("Posted"))); //$NON-NLS-1$
    }

    @Test
    public void testValidateObjectFieldsSkipsWhenBindableSetEmpty()
    {
        // Unattended-safe contract: when the owner's bindable set cannot be determined (no custom
        // attributes and no getStandardAttributes() to read), the check is SKIPPED (returns null) rather
        // than rejecting a possibly-valid name - the seed then proceeds best-effort. An owner with an
        // empty 'attributes' list and no standard-attribute getter has an empty bindable set.
        EObject owner = newOwnerWithAttributes();
        assertNull("an owner with no determinable bindable attributes is not validated", //$NON-NLS-1$
            FormElementWriter.validateObjectFields(owner, Arrays.asList("AnyName_zz"))); //$NON-NLS-1$
    }

    /**
     * Builds a minimal owner EObject exposing the given custom attribute names via the {@code attributes}
     * containment feature {@link FormElementWriter#validateObjectFields} reads (a no-{@code
     * getStandardAttributes()} owner exercises the custom-attribute branch alone, which is enough to
     * cover the rejection + available-list shape). Issue #208 (round 2 review).
     */
    @SuppressWarnings("unchecked")
    private static EObject newOwnerWithAttributes(String... attributeNames)
    {
        EcoreFactory f = EcoreFactory.eINSTANCE;
        EPackage pkg = f.createEPackage();
        pkg.setName("ownerlike"); //$NON-NLS-1$
        pkg.setNsPrefix("ownerlike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/ownerlike-writer"); //$NON-NLS-1$

        EClass attribute = f.createEClass();
        attribute.setName("Attribute"); //$NON-NLS-1$
        EAttribute attrName = f.createEAttribute();
        attrName.setName("name"); //$NON-NLS-1$
        attrName.setEType(EcorePackage.Literals.ESTRING);
        attribute.getEStructuralFeatures().add(attrName);

        EClass ownerClass = f.createEClass();
        ownerClass.setName("Owner"); //$NON-NLS-1$
        EReference attributes = f.createEReference();
        attributes.setName("attributes"); //$NON-NLS-1$
        attributes.setEType(attribute);
        attributes.setContainment(true);
        attributes.setUpperBound(-1);
        ownerClass.getEStructuralFeatures().add(attributes);

        pkg.getEClassifiers().add(attribute);
        pkg.getEClassifiers().add(ownerClass);

        EObject owner = new DynamicEObjectImpl(ownerClass);
        for (String name : attributeNames)
        {
            EObject attr = new DynamicEObjectImpl(attribute);
            attr.eSet(attrName, name);
            ((List<EObject>)owner.eGet(attributes)).add(attr);
        }
        return owner;
    }

    /** Asserts a seeded field's dataPath is the 2-segment Object.<sub> path. */
    private static void assertObjectSubPath(EObject field, String sub)
    {
        EObject dataPath = (EObject)field.eGet(feature(field, "dataPath")); //$NON-NLS-1$
        assertNotNull("the seeded field must carry a contained DataPath", dataPath); //$NON-NLS-1$
        assertEquals("the field must bind to Object." + sub, Arrays.asList("Object", sub), //$NON-NLS-1$ //$NON-NLS-2$
            dataPath.eGet(feature(dataPath, "segments"))); //$NON-NLS-1$
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

    /** Looks up a classifier of the synthetic form-like package by name (via any exposed EClass). */
    private static EClass modelClass(String name)
    {
        return (EClass)MODEL.formGroup.getEPackage().getEClassifier(name);
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

    private static void assertUniqueNonZeroFormItemIds(EObject form)
    {
        Set<Integer> ids = new HashSet<>();
        int count = 0;
        for (TreeIterator<EObject> it = form.eAllContents(); it.hasNext();)
        {
            EObject object = it.next();
            if (!MODEL.formItem.isInstance(object))
            {
                continue;
            }
            EStructuralFeature idFeature = object.eClass().getEStructuralFeature("id"); //$NON-NLS-1$
            if (idFeature == null)
            {
                continue;
            }
            int id = ((Integer)object.eGet(idFeature)).intValue();
            assertTrue("form-item id must not be 0 on " + object.eClass().getName(), id != 0); //$NON-NLS-1$
            assertTrue("duplicate form-item id " + id, ids.add(Integer.valueOf(id))); //$NON-NLS-1$
            count++;
        }
        assertEquals("root bar + 7 fields + 14 field auto-children + button + button tooltip", //$NON-NLS-1$
            24, count);
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
        final EClass formItem;
        final EClass formGroup;
        final EClass usualGroupExtInfo;
        final EClass autoCommandBar;
        final EClass table;
        final EClass decoration;
        final EClass formAttribute;
        final EClass formCommand;

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
            // The UsualGroupExtInfo layout enums (#235): the children grouping and the through-align /
            // representation tri-states nested under a group's <extInfo>.
            EEnum formChildrenGroup = newEnum(f, "FormChildrenGroup", //$NON-NLS-1$
                "Vertical", "Horizontal", "AlwaysHorizontal", "HorizontalIfPossible"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            EEnum throughAlign = newEnum(f, "FormElementsThroughAlign", "Auto", "Use", "DontUse"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            EEnum groupRepresentation = newEnum(f, "UsualGroupRepresentation", //$NON-NLS-1$
                "None", "WeakSeparation", "NormalSeparation", "StrongSeparation"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            EEnum decorationType = newEnum(f, "ManagedFormDecorationType", "Label", "Picture"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EEnum fieldType = newEnum(f, "ManagedFormFieldType", "InputField", "LabelField"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EEnum horizontalAlign = newEnum(f, "ItemHorizontalAlignment", "Auto", "Left"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EEnum editMode =
                newEnum(f, "TableFieldEditMode", "Directly", "Enter", "EnterOnInput"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

            EClass adjustableBoolean = f.createEClass();
            adjustableBoolean.setName("AdjustableBoolean"); //$NON-NLS-1$
            addBoolean(f, adjustableBoolean, "common"); //$NON-NLS-1$

            // The extInfo family: an abstract base plus the concrete classes the writer resolves by
            // name (group ext-infos, the input-field ext-info and the tooltip's label ext-info).
            EClass extInfoBase = f.createEClass();
            extInfoBase.setName("FormItemExtInfo"); //$NON-NLS-1$
            extInfoBase.setAbstract(true);
            usualGroupExtInfo = subExtInfo(f, extInfoBase, "UsualGroupExtInfo"); //$NON-NLS-1$
            // The UsualGroup layout properties that live under <extInfo> (#235): the children grouping
            // plus united / showLeftMargin / throughAlign / currentRowUse / representation.
            addEnum(f, usualGroupExtInfo, "group", formChildrenGroup); //$NON-NLS-1$
            addBoolean(f, usualGroupExtInfo, "united"); //$NON-NLS-1$
            addBoolean(f, usualGroupExtInfo, "showLeftMargin"); //$NON-NLS-1$
            addEnum(f, usualGroupExtInfo, "throughAlign", throughAlign); //$NON-NLS-1$
            addEnum(f, usualGroupExtInfo, "currentRowUse", currentRowUse); //$NON-NLS-1$
            addEnum(f, usualGroupExtInfo, "representation", groupRepresentation); //$NON-NLS-1$
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

            formItem = f.createEClass();
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

            formCommand = f.createEClass();
            formCommand.setName("FormCommand"); //$NON-NLS-1$
            addString(f, formCommand, "name"); //$NON-NLS-1$
            addInt(f, formCommand, "id"); //$NON-NLS-1$
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

            EClass abstractFormAttribute = f.createEClass();
            abstractFormAttribute.setName("AbstractFormAttribute"); //$NON-NLS-1$
            abstractFormAttribute.setAbstract(true);
            addInt(f, abstractFormAttribute, "id"); //$NON-NLS-1$
            addString(f, abstractFormAttribute, "name"); //$NON-NLS-1$

            formAttribute = f.createEClass();
            formAttribute.setName("FormAttribute"); //$NON-NLS-1$
            formAttribute.getESuperTypes().add(abstractFormAttribute);
            // The seed (issue #208) sets these on the main Object attribute: main/savedData booleans and
            // the presentation flags view/edit (each an AdjustableBoolean - "use"). Declare them so the
            // headless write logic can be exercised and the test can read them back (an absent feature
            // would make the reflective writer a no-op and the eGet(null) read throw).
            addBoolean(f, formAttribute, "main"); //$NON-NLS-1$
            addBoolean(f, formAttribute, "savedData"); //$NON-NLS-1$
            formAttribute.getEStructuralFeatures().add(
                containment(f, "view", adjustableBoolean, false)); //$NON-NLS-1$
            formAttribute.getEStructuralFeatures().add(
                containment(f, "edit", adjustableBoolean, false)); //$NON-NLS-1$

            autoCommandBar = f.createEClass();
            autoCommandBar.setName("AutoCommandBar"); //$NON-NLS-1$
            autoCommandBar.getESuperTypes().add(formItem);
            autoCommandBar.getEStructuralFeatures().add(containment(f, "items", formItem, true)); //$NON-NLS-1$

            // The table additions (search string / view status / search control) are one concrete
            // Addition EClass (a Visible FormItem: name/id/enabled/visible), differentiated at runtime by
            // 'type'. Modeling it here lets the grey-fix invariant (additions must be created enabled) be
            // asserted headlessly instead of skipped.
            EClass addition = f.createEClass();
            addition.setName("Addition"); //$NON-NLS-1$
            addition.getESuperTypes().add(formItem);
            addBoolean(f, addition, "enabled"); //$NON-NLS-1$
            addBoolean(f, addition, "visible"); //$NON-NLS-1$

            table = f.createEClass();
            table.setName("Table"); //$NON-NLS-1$
            table.getESuperTypes().add(formItem);
            table.getEStructuralFeatures().add(containment(f, "items", formItem, true)); //$NON-NLS-1$
            table.getEStructuralFeatures().add(
                containment(f, "autoCommandBar", autoCommandBar, false)); //$NON-NLS-1$
            table.getEStructuralFeatures().add(
                containment(f, "searchStringAddition", addition, false)); //$NON-NLS-1$
            table.getEStructuralFeatures().add(
                containment(f, "viewStatusAddition", addition, false)); //$NON-NLS-1$
            table.getEStructuralFeatures().add(
                containment(f, "searchControlAddition", addition, false)); //$NON-NLS-1$
            pkg.getEClassifiers().add(addition);

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
            pkg.getEClassifiers().add(formChildrenGroup);
            pkg.getEClassifiers().add(throughAlign);
            pkg.getEClassifiers().add(groupRepresentation);
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
            pkg.getEClassifiers().add(abstractFormAttribute);
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
