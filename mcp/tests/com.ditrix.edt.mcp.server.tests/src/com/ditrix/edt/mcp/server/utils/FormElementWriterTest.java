/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormMemberRef;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.Kind;

/**
 * Tests the pure, model-independent logic of {@link FormElementWriter}: the bilingual kind-token map
 * and the form-member FQN parser. The model-dependent write path (the cross-model hop + the EMF
 * mutation) is covered by the e2e suite against a live form.
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
    public void testParseNonFormFqnReturnsNull()
    {
        // A plain mdclass member (no form token at position 2) is NOT a form member.
        assertNull(FormElementWriter.parse("Catalog.Products.Attribute.Weight")); //$NON-NLS-1$
        assertNull(FormElementWriter.parse("Catalog.Products.TabularSection.Lines.Attribute.Qty")); //$NON-NLS-1$
        // A top object / too-short FQN is not a form member.
        assertNull(FormElementWriter.parse("Catalog.Products")); //$NON-NLS-1$
        assertNull(FormElementWriter.parse(null));
    }
}
