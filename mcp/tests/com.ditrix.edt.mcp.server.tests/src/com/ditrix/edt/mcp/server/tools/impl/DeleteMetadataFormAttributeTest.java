/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.DataItem;
import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.Decoration;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormAttributeColumn;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.Table;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormValidationException;

/**
 * Deleting a FORM ATTRIBUTE goes through EDT's form-attribute service (the designer's delete), which
 * also removes the items bound to it. These tests pin, on a real form model and with the service
 * faked: what the preview predicts, what the confirmed delete reports from OBSERVATION, the refusal
 * when the service is unavailable, the extension-adopted branch, and that a non-attribute member
 * never consults the service.
 */
public class DeleteMetadataFormAttributeTest
{
    private static final String FQN = "Catalog.Catalog.Form.ItemForm.Attribute.Object"; //$NON-NLS-1$

    private static final FormFactory F = FormFactory.eINSTANCE;

    // ---- fixture: the shape of TestConfiguration's Catalog.Catalog.Form.ItemForm ------------------

    private static Form itemForm(String main)
    {
        Form form = F.createForm();
        form.getAttributes().add(attribute(main, true));
        form.getAttributes().add(attribute("Other", false)); //$NON-NLS-1$
        Decoration decoration = F.createDecoration();
        decoration.setName("Decoration1"); //$NON-NLS-1$
        form.getItems().add(decoration);
        form.getItems().add(field("Code", main, "Code")); //$NON-NLS-1$ //$NON-NLS-2$
        form.getItems().add(field("Description", main, "Description")); //$NON-NLS-1$ //$NON-NLS-2$
        form.getItems().add(field("Attribute", main, "Attribute")); //$NON-NLS-1$ //$NON-NLS-2$
        form.getItems().add(field("OtherField", "Other")); //$NON-NLS-1$ //$NON-NLS-2$
        // A sibling whose FIRST segment merely starts with the attribute's name: segment-wise, not textual.
        form.getItems().add(field("Lookalike", main + "X")); //$NON-NLS-1$ //$NON-NLS-2$
        form.setExtInfo(F.createCatalogFormExtInfo());
        return form;
    }

    private static FormAttribute attribute(String name, boolean main)
    {
        FormAttribute attribute = F.createFormAttribute();
        attribute.setName(name);
        attribute.setMain(main);
        return attribute;
    }

    private static FormField field(String name, String... path)
    {
        FormField field = F.createFormField();
        field.setName(name);
        field.setDataPath(path(path));
        return field;
    }

    private static DataPath path(String... segments)
    {
        DataPath path = F.createDataPath();
        for (String segment : segments)
        {
            path.getSegments().add(segment);
        }
        return path;
    }

    private static FormAttribute named(Form form, String name)
    {
        for (FormAttribute attribute : form.getAttributes())
        {
            if (name.equals(attribute.getName()))
            {
                return attribute;
            }
        }
        throw new AssertionError("no attribute " + name); //$NON-NLS-1$
    }

    private static EObject item(Form form, String name)
    {
        for (EObject item : form.getItems())
        {
            if (item instanceof FormField && name.equals(((FormField)item).getName()))
            {
                return item;
            }
        }
        throw new AssertionError("no item " + name); //$NON-NLS-1$
    }

    private static List<Object> names(List<Map<String, Object>> entries)
    {
        List<Object> names = new ArrayList<>();
        for (Map<String, Object> entry : entries)
        {
            names.add(entry.get("name")); //$NON-NLS-1$
        }
        return names;
    }

    private static DeleteMetadataTool.FormDeletePreview preview(Form form, EObject attribute)
    {
        DeleteMetadataTool.FormDeletePreview data = new DeleteMetadataTool.FormDeletePreview();
        data.found = true;
        data.type = attribute.eClass().getName();
        DeleteMetadataTool.readAttributeDeletePreview(form, attribute, data);
        return data;
    }

    // ---- the preview ---------------------------------------------------------------------------

    @Test
    public void testPreviewListsTheItemsBoundToTheMainAttribute()
    {
        Form form = itemForm("Object"); //$NON-NLS-1$
        DeleteMetadataTool.FormDeletePreview data = preview(form, named(form, "Object")); //$NON-NLS-1$

        assertTrue(data.attribute);
        assertTrue("the fixture attribute is the form's main one", data.main); //$NON-NLS-1$
        assertEquals("CatalogFormExtInfo", data.rootExtInfo); //$NON-NLS-1$
        assertFalse(data.itemsKept);
        assertEquals("exactly the three Object.* fields, not OtherField, Lookalike or the decoration", //$NON-NLS-1$
            List.of("Code", "Description", "Attribute"), names(data.boundItems)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(3, data.boundItemCount);
        assertEquals("Object.Code", data.boundItems.get(0).get("dataPath")); //$NON-NLS-1$ //$NON-NLS-2$
        String sentence = data.boundItemsSentence();
        assertTrue(sentence, sentence.contains("3 item(s) bound to it (Code, Description, Attribute)")); //$NON-NLS-1$
        String note = data.mainAttributeNote(false);
        assertTrue(note, note.contains("MAIN attribute")); //$NON-NLS-1$
        assertTrue(note, note.contains("CatalogFormExtInfo")); //$NON-NLS-1$
        assertTrue(note, note.contains("orphan-form-ext-info")); //$NON-NLS-1$
    }

    @Test
    public void testPreviewResolvesARussianAttributeByItsOwnName()
    {
        // The data path's head is the attribute's programmatic Name, which is never translated: a
        // Russian-named main attribute binds its fields as '<Name>.<Field>' exactly as an English one.
        Form form = itemForm("Объект"); //$NON-NLS-1$
        DeleteMetadataTool.FormDeletePreview data =
            preview(form, named(form, "Объект")); //$NON-NLS-1$
        assertEquals(List.of("Code", "Description", "Attribute"), names(data.boundItems)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testPreviewOfANonMainAttributeCarriesNoMainNote()
    {
        Form form = itemForm("Object"); //$NON-NLS-1$
        DeleteMetadataTool.FormDeletePreview data = preview(form, named(form, "Other")); //$NON-NLS-1$
        assertFalse(data.main);
        assertEquals(List.of("OtherField"), names(data.boundItems)); //$NON-NLS-1$
        assertEquals("", data.mainAttributeNote(false)); //$NON-NLS-1$
    }

    @Test
    public void testPreviewOfAColumnListsOnlyItsOwnBindings()
    {
        Form form = F.createForm();
        FormAttribute rows = attribute("Rows", false); //$NON-NLS-1$
        FormAttributeColumn price = F.createFormAttributeColumn();
        price.setName("Price"); //$NON-NLS-1$
        rows.getColumns().add(price);
        form.getAttributes().add(rows);
        Table table = F.createTable();
        table.setName("RowsTable"); //$NON-NLS-1$
        table.setDataPath(path("Rows")); //$NON-NLS-1$
        table.getItems().add(field("RowsPrice", "Rows", "Price")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        table.getItems().add(field("RowsQty", "Rows", "Qty")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        form.getItems().add(table);

        DeleteMetadataTool.FormDeletePreview data = preview(form, price);
        assertEquals("a column takes the fields bound to IT, not the table or its siblings", //$NON-NLS-1$
            List.of("RowsPrice"), names(data.boundItems)); //$NON-NLS-1$

        DeleteMetadataTool.FormDeletePreview whole = preview(form, rows);
        assertEquals("the whole attribute takes the table and every column field below it", //$NON-NLS-1$
            List.of("RowsTable", "RowsPrice", "RowsQty"), names(whole.boundItems)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testPreviewCountIsExactWhenTheListIsCut()
    {
        Form form = itemForm("Object"); //$NON-NLS-1$
        int extra = DeleteMetadataTool.MAX_LISTED_BOUND_ITEMS + 5;
        for (int i = 0; i < extra; i++)
        {
            form.getItems().add(field("F" + i, "Object", "A" + i)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        DeleteMetadataTool.FormDeletePreview data = preview(form, named(form, "Object")); //$NON-NLS-1$
        assertEquals(DeleteMetadataTool.MAX_LISTED_BOUND_ITEMS, data.boundItems.size());
        assertEquals(3 + extra, data.boundItemCount);
        assertTrue(data.boundItemsSentence(), data.boundItemsSentence()
            .contains("the first " + DeleteMetadataTool.MAX_LISTED_BOUND_ITEMS + " listed")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExtensionAdoptedAttributeKeepsItsBoundItems()
    {
        // EDT removes the bound items in an extension form only for the extension's OWN attribute; an
        // attribute adopted from the base form keeps them (FormExtensionService.isPureExtensionObject).
        Form adopted = itemForm("Object"); //$NON-NLS-1$
        adopted.setBaseForm(F.createForm());
        named(adopted, "Object").setAdopted(Boolean.TRUE); //$NON-NLS-1$
        DeleteMetadataTool.FormDeletePreview data = preview(adopted, named(adopted, "Object")); //$NON-NLS-1$
        assertTrue(data.itemsKept);
        assertTrue("no bound item is promised for removal", data.boundItems.isEmpty()); //$NON-NLS-1$
        assertTrue(data.boundItemsSentence(), data.boundItemsSentence().contains("adopted from the base form")); //$NON-NLS-1$

        Form own = itemForm("Object"); //$NON-NLS-1$
        own.setBaseForm(F.createForm());
        DeleteMetadataTool.FormDeletePreview pure = preview(own, named(own, "Other")); //$NON-NLS-1$
        assertFalse("the extension's own attribute takes its items", pure.itemsKept); //$NON-NLS-1$
        assertEquals(List.of("OtherField"), names(pure.boundItems)); //$NON-NLS-1$
    }

    @Test
    public void testHelperIgnoresTheBaseFormCopy()
    {
        // An extension form CONTAINS its base form; the base copy's items are not this form's items.
        Form form = itemForm("Object"); //$NON-NLS-1$
        Form base = itemForm("Object"); //$NON-NLS-1$
        form.setBaseForm(base);
        List<EObject> bound = FormElementWriter.findItemsBoundAtOrBelowAttribute(named(form, "Object")); //$NON-NLS-1$
        assertEquals(3, bound.size());
        for (EObject item : bound)
        {
            assertSame(form, item.eContainer());
        }
    }

    // ---- the confirmed delete: the service route, observed ---------------------------------------

    /** Emulates EDT's delete: removes two fields, unbinds a third, removes the attribute. */
    private static DeleteMetadataTool.FormAttributeDeleter fakePlatform(Form form, List<EObject> calls)
    {
        return (tx, attribute) ->
        {
            calls.add(attribute);
            EcoreUtil.remove(item(form, "Code")); //$NON-NLS-1$
            EcoreUtil.remove(item(form, "Description")); //$NON-NLS-1$
            ((DataItem)item(form, "Attribute")).setDataPath(null); //$NON-NLS-1$
            EcoreUtil.remove(attribute);
        };
    }

    @Test
    public void testConfirmedDeleteGoesThroughTheServiceAndReportsWhatItObserved()
    {
        Form form = itemForm("Object"); //$NON-NLS-1$
        FormAttribute object = named(form, "Object"); //$NON-NLS-1$
        List<EObject> calls = new ArrayList<>();

        DeleteMetadataTool.AttributeDeleteOutcome outcome =
            DeleteMetadataTool.deleteAttributeInTx(form, object, null, fakePlatform(form, calls), FQN);

        assertEquals("the service is called exactly once, with the attribute", List.of(object), calls); //$NON-NLS-1$
        assertEquals(List.of("Code", "Description"), names(outcome.removed)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Object.Code", outcome.removed.get(0).get("dataPath")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Attribute"), names(outcome.unbound)); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, outcome.unbound.get(0).get("unbound")); //$NON-NLS-1$
        assertNull("a removed item is not flagged unbound", outcome.removed.get(0).get("unbound")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the root extInfo is untouched, as in the designer", form.getExtInfo()); //$NON-NLS-1$
        String message = outcome.describe();
        assertTrue(message, message.contains("2 bound item(s) removed (Code, Description)")); //$NON-NLS-1$
        assertTrue(message, message.contains("1 item(s) kept with their data path cleared (Attribute)")); //$NON-NLS-1$
        assertTrue(message, message.contains("was the form's MAIN attribute")); //$NON-NLS-1$
        assertTrue(message, message.contains("orphan-form-ext-info")); //$NON-NLS-1$
        assertEquals("unrelated items are neither removed nor reported", //$NON-NLS-1$
            List.of("Code", "Description", "Attribute"), names(outcome.entries())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testAServiceThatLeavesTheAttributeIsRefused()
    {
        Form form = itemForm("Object"); //$NON-NLS-1$
        try
        {
            DeleteMetadataTool.deleteAttributeInTx(form, named(form, "Object"), null, (tx, a) -> { }, FQN); //$NON-NLS-1$
            fail("a delete that left the attribute must throw and roll the transaction back"); //$NON-NLS-1$
        }
        catch (FormValidationException e)
        {
            assertTrue(e.json(), e.json().contains("left '" + FQN + "' in the form")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ---- the service seam ----------------------------------------------------------------------

    @Test
    public void testAnUnavailableServiceRefusesInsteadOfFallingBack()
    {
        DeleteMetadataTool tool = new DeleteMetadataTool((name, preview) -> DestructiveConsentGate.ConsentDecision.ALLOW)
            .withFormAttributeDeleters(() -> null);
        DeleteMetadataTool.FormDeletePreview data = preview(itemForm("Object"), F.createFormAttribute()); //$NON-NLS-1$
        try
        {
            tool.attributeDeleterFor(data, FQN);
            fail("an unavailable service must refuse, never fall back to EcoreUtil.remove"); //$NON-NLS-1$
        }
        catch (FormValidationException e)
        {
            assertTrue(e.json(), e.json().contains("\"success\":false")); //$NON-NLS-1$
            assertTrue(e.json(), e.json().contains("is unavailable, so nothing was changed")); //$NON-NLS-1$
            assertTrue(e.json(), e.json().contains(FQN));
        }
    }

    @Test
    public void testANonAttributeMemberNeverConsultsTheService()
    {
        AtomicInteger asked = new AtomicInteger();
        DeleteMetadataTool tool = new DeleteMetadataTool((name, preview) -> DestructiveConsentGate.ConsentDecision.ALLOW)
            .withFormAttributeDeleters(() ->
            {
                asked.incrementAndGet();
                return null;
            });
        DeleteMetadataTool.FormDeletePreview field = new DeleteMetadataTool.FormDeletePreview();
        field.found = true;
        field.type = "FormField"; //$NON-NLS-1$
        assertNull(tool.attributeDeleterFor(field, "Catalog.Catalog.Form.ItemForm.Field.Code")); //$NON-NLS-1$
        assertEquals("an item / command / handler delete keeps its own path", 0, asked.get()); //$NON-NLS-1$
        assertEquals("", field.boundItemsSentence()); //$NON-NLS-1$
    }

    @Test
    public void testTheConsentPromptCountsTheBoundItems()
    {
        List<ConsentPreview> asked = new ArrayList<>();
        DeleteMetadataTool tool = new DeleteMetadataTool((name, preview) ->
        {
            asked.add(preview);
            return DestructiveConsentGate.ConsentDecision.REJECT;
        });
        Form form = itemForm("Object"); //$NON-NLS-1$
        DeleteMetadataTool.FormDeletePreview data = preview(form, named(form, "Object")); //$NON-NLS-1$
        tool.gateFormMemberDelete(FQN, FormElementWriter.parse(FQN), false, data, () -> "{}"); //$NON-NLS-1$
        assertEquals(1, asked.size());
        assertEquals("the attribute and its three bound fields", 4, asked.get(0).getTotalCount()); //$NON-NLS-1$
        assertTrue(asked.get(0).getSubtitle(), asked.get(0).getSubtitle().contains("3 item(s) bound to it")); //$NON-NLS-1$
    }
}
