/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.AbstractFormAttribute;
import com._1c.g5.v8.dt.form.model.DataItem;
import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.Decoration;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormAttributeColumn;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.form.service.attribute.FormAttributeManagementService;
import com._1c.g5.v8.dt.form.service.datasourceinfo.IDataSourceInfoAssociationService;
import com._1c.g5.v8.dt.form.service.extension.IFormExtensionService;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.EdtServices;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.FormAttributeDeletion;
import com.ditrix.edt.mcp.server.utils.FormEditContextTestAccess;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormValidationException;

/**
 * Deleting a FORM ATTRIBUTE goes through EDT's form-attribute service (the designer's delete), which
 * also removes the items bound to it. The prediction runs EDT's OWN item collector (the private
 * {@code DeleteDataItemByPathDathPrefixCommand}, reached the way production reaches it) over a real
 * form model, with the path resolution stubbed. These tests pin what the preview predicts, what the
 * confirmed delete reports from OBSERVATION, that a failed delete commits nothing through the write
 * boundary, the refusal when the service is unavailable, and that a non-attribute member never
 * consults the service.
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

    /** A collection attribute 'Rows' shown by a table that also holds an unbound decoration and a foreign field. */
    private static Form tableForm()
    {
        Form form = F.createForm();
        FormAttribute rows = attribute("Rows", false); //$NON-NLS-1$
        FormAttributeColumn price = F.createFormAttributeColumn();
        price.setName("Price"); //$NON-NLS-1$
        rows.getColumns().add(price);
        form.getAttributes().add(rows);
        form.getAttributes().add(attribute("Other", false)); //$NON-NLS-1$
        Table table = F.createTable();
        table.setName("RowsTable"); //$NON-NLS-1$
        table.setDataPath(path("Rows")); //$NON-NLS-1$
        table.getItems().add(field("RowsPrice", "Rows", "Price")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Decoration hint = F.createDecoration();
        hint.setName("RowsHint"); //$NON-NLS-1$
        table.getItems().add(hint);
        table.getItems().add(field("OtherInTable", "Other")); //$NON-NLS-1$ //$NON-NLS-2$
        form.getItems().add(table);
        form.getItems().add(field("OtherField", "Other")); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static FormItem item(FormItemContainer container, String name)
    {
        for (FormItem item : container.getItems())
        {
            if (name.equals(item.getName()))
            {
                return item;
            }
        }
        return null;
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

    private static List<Object> namesFlagged(List<Map<String, Object>> entries, String flag)
    {
        List<Object> names = new ArrayList<>();
        for (Map<String, Object> entry : entries)
        {
            if (Boolean.TRUE.equals(entry.get(flag)))
            {
                names.add(entry.get("name")); //$NON-NLS-1$
            }
        }
        return names;
    }

    // ---- EDT's prediction, with the path resolution stubbed -------------------------------------

    /** The path an attribute or column resolves to: {@code [Name]} or {@code [Owner, Name]}. */
    private static AbstractDataPath attributePath(AbstractFormAttribute attribute)
    {
        EObject owner = attribute.eContainer();
        return owner instanceof FormAttribute
            ? path(((FormAttribute)owner).getName(), attribute.getName()) : path(attribute.getName());
    }

    private static PropertyInfo info(AbstractDataPath dataPath)
    {
        PropertyInfo info = mock(PropertyInfo.class);
        when(info.getDataPath(any())).thenReturn(dataPath);
        return info;
    }

    /** EDT's own deletion over stubbed services; paths in {@code unresolved} do not resolve. */
    private static FormAttributeDeletion edt(Set<String> unresolved, IFormExtensionService extensions)
    {
        IDataSourceInfoAssociationService associations = mock(IDataSourceInfoAssociationService.class);
        when(associations.findPropertyInfo(any(Form.class), any(AbstractFormAttribute.class)))
            .thenAnswer(inv -> info(attributePath(inv.getArgument(1))));
        when(associations.findPropertyInfo(any(Form.class), any(AbstractDataPath.class))).thenAnswer(inv ->
        {
            AbstractDataPath path = inv.getArgument(1);
            return unresolved.contains(String.join(".", path.getSegments())) ? null : info(path); //$NON-NLS-1$
        });
        try
        {
            return new FormAttributeDeletion(mock(FormAttributeManagementService.class), associations,
                extensions, EdtServices.deleteItemCollectorConstructor());
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("EDT's item collector must be reachable", e); //$NON-NLS-1$
        }
    }

    private static FormAttributeDeletion edt()
    {
        return edt(Set.of(), mock(IFormExtensionService.class));
    }

    /** A deleter whose prediction is EDT's and whose delete is the given emulation. */
    private static DeleteMetadataTool.FormAttributeDeleter deleter(FormAttributeDeletion edt,
        BiConsumer<IBmTransaction, EObject> delete)
    {
        return new DeleteMetadataTool.FormAttributeDeleter()
        {
            @Override
            public FormAttributeDeletion.Plan plan(EObject formModel, EObject attribute)
            {
                return edt.plan(formModel, attribute);
            }

            @Override
            public void delete(IBmTransaction tx, EObject attribute)
            {
                delete.accept(tx, attribute);
            }
        };
    }

    private static DeleteMetadataTool.FormDeletePreview preview(Form form, EObject attribute,
        FormAttributeDeletion edt)
    {
        DeleteMetadataTool.FormDeletePreview data = new DeleteMetadataTool.FormDeletePreview();
        data.found = true;
        data.type = attribute.eClass().getName();
        DeleteMetadataTool.readAttributeDeletePreview(form, attribute, deleter(edt, (tx, a) -> { }), data);
        return data;
    }

    private static DeleteMetadataTool.FormDeletePreview preview(Form form, EObject attribute)
    {
        return preview(form, attribute, edt());
    }

    // ---- the preview ---------------------------------------------------------------------------

    @Test
    public void testPreviewListsTheItemsEdtRemovesWithTheMainAttribute()
    {
        Form form = itemForm("Object"); //$NON-NLS-1$
        DeleteMetadataTool.FormDeletePreview data = preview(form, named(form, "Object")); //$NON-NLS-1$

        assertTrue(data.attribute);
        assertTrue("the fixture attribute is the form's main one", data.main); //$NON-NLS-1$
        assertEquals("CatalogFormExtInfo", data.rootExtInfo); //$NON-NLS-1$
        assertEquals("exactly the three Object.* fields, not OtherField, Lookalike or the decoration", //$NON-NLS-1$
            List.of("Code", "Description", "Attribute"), names(data.boundItems)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(3, data.boundItemCount);
        assertEquals(0, data.containedCount);
        assertEquals(0, data.clearedCount);
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
        DeleteMetadataTool.FormDeletePreview data = preview(form, named(form, "Объект")); //$NON-NLS-1$
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
        Form form = tableForm();
        FormAttributeColumn price = named(form, "Rows").getColumns().get(0); //$NON-NLS-1$
        DeleteMetadataTool.FormDeletePreview data = preview(form, price);
        assertEquals("a column takes the field bound to IT, not the table or its siblings", //$NON-NLS-1$
            List.of("RowsPrice"), names(data.boundItems)); //$NON-NLS-1$
    }

    @Test
    public void testPreviewDisclosesEverythingInsideARemovedTable()
    {
        // EDT removes a bound table WITH its whole containment subtree: an unbound decoration and a
        // field bound to another attribute go too, and the consent must say so (review P1).
        Form form = tableForm();
        DeleteMetadataTool.FormDeletePreview data = preview(form, named(form, "Rows")); //$NON-NLS-1$
        assertEquals(List.of("RowsTable", "RowsPrice", "RowsHint", "OtherInTable"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            names(data.boundItems));
        assertEquals("the table and its bound column field are EDT's own picks", 2, data.boundItemCount); //$NON-NLS-1$
        assertEquals(List.of("RowsHint", "OtherInTable"), namesFlagged(data.boundItems, "contained")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(2, data.containedCount);
        assertEquals("Other", data.boundItems.get(3).get("dataPath")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(data.boundItemsSentence(),
            data.boundItemsSentence().contains("the 2 member(s) inside them (RowsHint, OtherInTable)")); //$NON-NLS-1$
        assertFalse("a field outside the table stays", names(data.boundItems).contains("OtherField")); //$NON-NLS-1$ //$NON-NLS-2$

        List<ConsentPreview> asked = new ArrayList<>();
        new DeleteMetadataTool((name, preview) ->
        {
            asked.add(preview);
            return DestructiveConsentGate.ConsentDecision.REJECT;
        }).gateFormMemberDelete("Catalog.Catalog.Form.ItemForm.Attribute.Rows", //$NON-NLS-1$
            FormElementWriter.parse("Catalog.Catalog.Form.ItemForm.Attribute.Rows"), false, data, () -> "{}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the attribute + its own members + 2 picked + 2 contained", //$NON-NLS-1$
            1 + data.descendants.size() + 4, asked.get(0).getTotalCount());
        assertTrue(asked.get(0).getSubtitle(), asked.get(0).getSubtitle().contains("2 member(s) inside them")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnresolvedPathIsReportedAsClearedNotRemoved()
    {
        // EDT's collector resolves each path first; one that does not resolve is not removed but has its
        // data path cleared - a different outcome the preview must not count as a removal (review P2).
        Form form = itemForm("Object"); //$NON-NLS-1$
        form.getItems().add(field("Stale", "Object", "Missing")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DeleteMetadataTool.FormDeletePreview data = preview(form, named(form, "Object"), //$NON-NLS-1$
            edt(Set.of("Object.Missing"), mock(IFormExtensionService.class))); //$NON-NLS-1$
        assertEquals(List.of("Code", "Description", "Attribute"), names(data.boundItems)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(3, data.removedAlongCount());
        assertEquals(List.of("Stale"), names(data.clearedItems)); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, data.clearedItems.get(0).get("unbound")); //$NON-NLS-1$
        assertEquals("Object.Missing", data.clearedItems.get(0).get("dataPath")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(data.boundItemsSentence(), data.boundItemsSentence().contains(
            "1 item(s) whose path starts with it but does not resolve stay with the data path cleared (Stale)")); //$NON-NLS-1$
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
        // EDT removes the bound items in an extension form only for the extension's OWN attribute.
        IFormExtensionService extensions = mock(IFormExtensionService.class);
        when(extensions.isExtensionAdopted(any())).thenReturn(true);
        when(extensions.isPureExtensionObject(any(), any())).thenReturn(false);
        Form form = itemForm("Object"); //$NON-NLS-1$
        DeleteMetadataTool.FormDeletePreview data = preview(form, named(form, "Object"), //$NON-NLS-1$
            edt(Set.of(), extensions));
        assertTrue(data.itemsKept);
        assertTrue("no bound item is promised for removal", data.boundItems.isEmpty()); //$NON-NLS-1$
        assertTrue(data.boundItemsSentence(), data.boundItemsSentence().contains("adopted from the base form")); //$NON-NLS-1$

        when(extensions.isPureExtensionObject(any(), any())).thenReturn(true);
        DeleteMetadataTool.FormDeletePreview own = preview(form, named(form, "Other"), edt(Set.of(), extensions)); //$NON-NLS-1$
        assertFalse("the extension's own attribute takes its items", own.itemsKept); //$NON-NLS-1$
        assertEquals(List.of("OtherField"), names(own.boundItems)); //$NON-NLS-1$
    }

    // ---- the confirmed delete: the service route, observed ---------------------------------------

    @Test
    public void testConfirmedDeleteReportsWhatItObserved()
    {
        Form form = tableForm();
        FormAttribute rows = named(form, "Rows"); //$NON-NLS-1$
        FormItem table = item(form, "RowsTable"); //$NON-NLS-1$
        FormField stale = field("Stale", "Rows", "Gone"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        form.getItems().add(stale);
        List<EObject> calls = new ArrayList<>();
        // Emulates EDT: the table goes with its subtree, the stale field is unbound, the attribute goes.
        DeleteMetadataTool.FormAttributeDeleter platform = deleter(edt(), (tx, attribute) ->
        {
            calls.add(attribute);
            EcoreUtil.remove(table);
            ((DataItem)stale).setDataPath(null);
            EcoreUtil.remove(attribute);
        });

        DeleteMetadataTool.AttributeDeleteOutcome outcome =
            DeleteMetadataTool.deleteAttributeInTx(form, rows, null, platform, FQN);

        assertEquals("the service is called exactly once, with the attribute", List.of(rows), calls); //$NON-NLS-1$
        assertEquals(List.of("Price"), names(outcome.columns)); //$NON-NLS-1$
        assertEquals(List.of("RowsTable", "RowsPrice", "RowsHint", "OtherInTable"), names(outcome.removed)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("everything inside the removed table is flagged contained", //$NON-NLS-1$
            List.of("RowsPrice", "RowsHint", "OtherInTable"), namesFlagged(outcome.removed, "contained")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(List.of("Stale"), names(outcome.unbound)); //$NON-NLS-1$
        assertEquals("Rows.Gone", outcome.unbound.get(0).get("dataPath")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an item outside the table survives unreported", //$NON-NLS-1$
            names(outcome.entries()).contains("OtherField")); //$NON-NLS-1$
        String message = outcome.describe();
        assertTrue(message, message.contains("1 item(s) removed (RowsTable) with the 3 member(s) inside them")); //$NON-NLS-1$
        assertTrue(message, message.contains("1 item(s) kept with their data path cleared (Stale)")); //$NON-NLS-1$
    }

    @Test
    public void testConfirmedMainAttributeDeleteKeepsTheRootExtInfo()
    {
        Form form = itemForm("Object"); //$NON-NLS-1$
        DeleteMetadataTool.AttributeDeleteOutcome outcome = DeleteMetadataTool.deleteAttributeInTx(form,
            named(form, "Object"), null, deleter(edt(), (tx, a) -> //$NON-NLS-1$
            {
                EcoreUtil.remove(item(form, "Code")); //$NON-NLS-1$
                EcoreUtil.remove(a);
            }), FQN);
        assertNotNull("the root extInfo is untouched, as in the designer", form.getExtInfo()); //$NON-NLS-1$
        String message = outcome.describe();
        assertTrue(message, message.contains("was the form's MAIN attribute")); //$NON-NLS-1$
        assertTrue(message, message.contains("orphan-form-ext-info")); //$NON-NLS-1$
    }

    // ---- the write boundary: a failed delete commits nothing (review P2) -----------------------

    /** An IBmModel whose write task runs on a working COPY and commits it only when the task returns. */
    private static IBmModel transactional(AtomicReference<Form> committed, AtomicReference<Form> working,
        long mdFormId)
    {
        IBmModel model = mock(IBmModel.class);
        when(model.execute(any())).thenAnswer(inv ->
        {
            IBmTask<?> task = inv.getArgument(0);
            Form copy = EcoreUtil.copy(committed.get());
            working.set(copy);
            CatalogForm md = MdClassFactory.eINSTANCE.createCatalogForm();
            md.setForm(copy);
            IBmTransaction tx = mock(IBmTransaction.class);
            when(tx.getObjectById(mdFormId)).thenReturn((com._1c.g5.v8.bm.core.IBmObject)md);
            Object result = task.execute(tx, null);
            committed.set(copy);
            return result;
        });
        return model;
    }

    private static void assertNothingCommitted(BiConsumer<IBmTransaction, EObject> delete)
    {
        Form live = itemForm("Object"); //$NON-NLS-1$
        AtomicReference<Form> committed = new AtomicReference<>(live);
        AtomicReference<Form> working = new AtomicReference<>();
        IProject project = mock(IProject.class);
        FormElementWriter.FormEditContext fctx = FormEditContextTestAccess.of(project,
            transactional(committed, working, 7L), MdClassFactory.eINSTANCE.createCatalogForm(), 7L);
        try
        {
            new DeleteMetadataTool((name, preview) -> DestructiveConsentGate.ConsentDecision.ALLOW)
                .performFormDelete(fctx, FQN, FormElementWriter.parse(FQN), false, null, deleter(edt(), delete));
            fail("a failed attribute delete must not report success"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            // the refusal / the service's own failure, escaping the write boundary
        }
        assertNotSame("the delete ran on the transaction's working copy", live, working.get()); //$NON-NLS-1$
        assertNull("... and really removed the field there", item(working.get(), "Code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame("the failed write must not have committed its working copy", live, committed.get()); //$NON-NLS-1$
        assertNotNull("the removed field survives", item(live, "Code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the attribute survives", named(live, "Object")); //$NON-NLS-1$
        verifyNoMoreInteractions(project); // no write scope recorded, no export submitted
    }

    @Test
    public void testADeleteThatLeavesTheAttributeCommitsNothing()
    {
        assertNothingCommitted((tx, attribute) ->
            EcoreUtil.remove(item((Form)attribute.eContainer(), "Code"))); //$NON-NLS-1$
    }

    @Test
    public void testADeleteThatThrowsCommitsNothing()
    {
        assertNothingCommitted((tx, attribute) ->
        {
            EcoreUtil.remove(item((Form)attribute.eContainer(), "Code")); //$NON-NLS-1$
            throw new IllegalStateException("EDT failed half-way"); //$NON-NLS-1$
        });
    }

    @Test
    public void testTheEmulatedBoundaryCommitsWhatReturns()
    {
        // The control for the two above: the same boundary DOES commit work that returns, so their
        // "nothing committed" is the rollback of a throwing task, not an emulation that never commits.
        Form live = itemForm("Object"); //$NON-NLS-1$
        AtomicReference<Form> committed = new AtomicReference<>(live);
        AtomicReference<Form> working = new AtomicReference<>();
        IBmModel model = transactional(committed, working, 7L);
        BmTransactions.write(model, "t", (tx, pm) -> //$NON-NLS-1$
        {
            CatalogForm md = (CatalogForm)tx.getObjectById(7L);
            EcoreUtil.remove(item((Form)md.getForm(), "Code")); //$NON-NLS-1$
            return null;
        });
        assertSame(working.get(), committed.get());
        assertNull(item(committed.get(), "Code")); //$NON-NLS-1$
    }

    // ---- the service seam ----------------------------------------------------------------------

    @Test
    public void testAnUnavailableServiceRefusesInsteadOfFallingBack()
    {
        DeleteMetadataTool tool = new DeleteMetadataTool((name, preview) -> DestructiveConsentGate.ConsentDecision.ALLOW)
            .withFormAttributeDeleters(() -> null);
        try
        {
            tool.requireAttributeDeleter(FQN);
            fail("an unavailable service must refuse, never fall back to an own predictor or EcoreUtil.remove"); //$NON-NLS-1$
        }
        catch (FormValidationException e)
        {
            assertTrue(e.json(), e.json().contains("\"success\":false")); //$NON-NLS-1$
            assertTrue(e.json(), e.json().contains("is unavailable, so nothing was changed")); //$NON-NLS-1$
            assertTrue(e.json(), e.json().contains(FQN));
        }
        DeleteMetadataTool.FormDeletePreview attribute = new DeleteMetadataTool.FormDeletePreview();
        attribute.attribute = true;
        try
        {
            tool.attributeDeleterFor(attribute, FQN);
            fail("the confirm path refuses the same way"); //$NON-NLS-1$
        }
        catch (FormValidationException e)
        {
            assertTrue(e.json(), e.json().contains("is unavailable")); //$NON-NLS-1$
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
}
