/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.After;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.FormAttributeAdditionalColumns;
import com._1c.g5.v8.dt.form.model.FormAttributeColumn;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldTableDef;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormMemberRef;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.Kind;

/** Issue #737: addressing, derived-data eligibility and writes through the real model's containments. */
public class FormAdditionalColumnsTest
{
    private final AdditionalColumnFixture f;

    public FormAdditionalColumnsTest() throws Exception
    {
        f = new AdditionalColumnFixture();
    }

    @After
    public void restore()
    {
        f.close();
    }

    @Test
    public void testParseAdditionalColumnAddress() throws Exception
    {
        FormMemberRef ref = ref("Goods.Extra");
        assertEquals("Catalog.Catalog.forms.ItemForm", ref.formPath);
        assertEquals("Column", ref.kindToken);
        assertEquals("Extra", ref.name);
        assertEquals("Object", ref.ownerAttributeName);
        assertEquals(List.of("Goods"), ref.tablePathSegments);
        assertEquals(List.of("Object", "Goods"), ref.additionalTablePath());
        FormMemberRef nested = ref("Sub.Deep.Extra");
        assertEquals(List.of("Sub", "Deep"), nested.tablePathSegments);
        assertEquals(List.of("Object", "Sub", "Deep"), nested.additionalTablePath());
        assertNull(AdditionalColumnFixture.ref("Catalog.Catalog.Form.ItemForm.Attribute.Rows.Column.Price")
            .additionalTablePath());
        assertEquals(5, ref.tailSegments);
        assertTrue(ref.isAttributeColumn());
        assertTrue(ref.isAdditionalColumn());
        assertFalse(ref.isItemLevel());
        assertNull(FormElementWriter.columnAddressingError(ref));
    }

    @Test
    public void testCommonFormAndRussianTokens()
    {
        FormMemberRef common = AdditionalColumnFixture.ref("CommonForm.F.\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442."
            + "\u041e\u0431\u044a\u0435\u043a\u0442.\u041a\u043e\u043b\u043e\u043d\u043a\u0430.\u0422\u043e\u0432\u0430\u0440\u044b.\u0414\u043e\u043f");
        assertEquals("CommonForm.F", common.formPath);
        assertEquals(5, common.tailSegments);
        FormMemberRef owned = AdditionalColumnFixture.ref("\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.X."
            + "\u0424\u043e\u0440\u043c\u0430.F.Attribute.Object.Columns.Goods.Extra");
        assertEquals("Extra", owned.name);
        assertEquals(5, owned.tailSegments);
    }

    @Test
    public void testRussianIdentifiersResolveByNameAndNeverByTitle()
    {
        String ownerName = "\u041e\u0431\u044a\u0435\u043a\u0442";
        String tableName = "\u0422\u043e\u0432\u0430\u0440\u044b";
        String columnName = "\u0414\u043e\u043f";
        f.owner.setName(ownerName);
        FormAttributeColumn column = AdditionalColumnFixture.column(columnName, 4);
        column.getTitle().put("en", "Displayed column");
        f.entry(ownerName, tableName).getColumns().add(column);
        String prefix = "CommonForm.F.Attribute." + ownerName + ".Column." + tableName + '.';
        assertSame(column, FormElementWriter.resolveFormMember(f.form, AdditionalColumnFixture.ref(prefix + columnName)));
        assertNull(FormElementWriter.resolveFormMember(f.form, AdditionalColumnFixture.ref(prefix + "Displayed column")));
    }

    @Test
    public void testResolveFindsOnlyTheAddressedEntry()
    {
        FormAttributeColumn extra = AdditionalColumnFixture.column("Extra", 9);
        f.entry("Object", "Goods").getColumns().add(extra);
        assertSame(extra, FormElementWriter.resolveFormMember(f.form, ref("Goods.Extra")));
        assertNull(FormElementWriter.resolveFormMember(f.form, ref("Other.Extra")));
        assertNull(FormElementWriter.resolveFormMember(f.form, ref("Goods.Nope")));
    }

    @Test
    public void testResolveMatchesTablePathCaseInsensitivelyAndSegmentBySegment()
    {
        FormAttributeColumn extra = AdditionalColumnFixture.column("Extra", 9);
        f.entry("object", "goods").getColumns().add(extra);
        assertSame(extra, FormElementWriter.resolveFormMember(f.form, ref("Goods.extra")));
        assertNull(FormElementWriter.resolveFormMember(f.form, ref("Good.Extra")));
        f.owner.getAdditionalColumns().clear();
        f.entry("Object", "Goods", "Nested").getColumns().add(extra);
        assertNull(FormElementWriter.resolveFormMember(f.form, ref("Goods.Extra")));
    }

    @Test
    public void testDirectAndAdditionalColumnsNeverMix()
    {
        FormAttributeColumn direct = AdditionalColumnFixture.column("Extra", 7);
        f.owner.getColumns().add(direct);
        assertNull(FormElementWriter.resolveFormMember(f.form, ref("Goods.Extra")));
        FormMemberRef directRef = AdditionalColumnFixture.ref(
            "Catalog.Catalog.Form.ItemForm.Attribute.Object.Column.Extra");
        assertSame(direct, FormElementWriter.resolveFormMember(f.form, directRef));
        FormAttributeColumn additional = AdditionalColumnFixture.column("Extra", 9);
        f.entry("Object", "Goods").getColumns().add(additional);
        assertSame(additional, FormElementWriter.resolveFormMember(f.form, ref("Goods.Extra")));
    }

    @Test
    public void testCreateWritesCanonicalPathIdDefaultsAndTitle() throws Exception
    {
        String[] kind = new String[1];
        assertNull(f.create("Extra", "goods", kind));
        FormAttributeAdditionalColumns entry = f.owner.getAdditionalColumns().get(0);
        assertEquals(List.of("Object", "Goods"), entry.getTablePath().getSegments());
        FormAttributeColumn column = entry.getColumns().get(0);
        assertEquals("Extra", column.getName());
        assertEquals(4, column.getId());
        assertNotNull(column.getValueType());
        assertTrue(column.getValueType().getTypes().isEmpty());
        assertTrue(column.getView().isCommon());
        assertTrue(column.getEdit().isCommon());
        assertEquals("Extra title", column.getTitle().get("en"));
        assertEquals("FormAttributeColumn", kind[0]);
        verify(f.management).isSupportingColumn(f.table);
    }

    @Test
    public void testTableTokenDoesNotAliasTheBindingArgument() throws Exception
    {
        String error = FormElementWriter.createMember(f.form, Kind.COLUMN, "Direct", "Object", "Goods",
            null, null, false, new String[1]);
        assertNotNull("legacy bindTarget must not be interpreted as a table token", error);
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
        assertNull(f.create("Extra", "Goods", new String[1]));
        assertEquals(1, f.owner.getAdditionalColumns().size());
        assertTrue(f.owner.getColumns().isEmpty());
    }

    @Test
    public void testCreateReusesTheExistingEntryIgnoringCase() throws Exception
    {
        FormAttributeAdditionalColumns entry = f.entry("object", "goods");
        entry.getColumns().add(AdditionalColumnFixture.column("First", 4));
        assertNull(f.create("Second", "Goods", new String[1]));
        assertEquals(1, f.owner.getAdditionalColumns().size());
        assertSame(entry, f.owner.getAdditionalColumns().get(0));
        // The reused entry keeps its stored spelling: its bound items' dataPaths depend on it.
        assertEquals(List.of("object", "goods"), entry.getTablePath().getSegments());
        assertEquals("object.goods", FormElementWriter.tablePathOfAdditionalColumn(entry.getColumns().get(1)));
        assertEquals(2, entry.getColumns().size());
        assertEquals("Second", entry.getColumns().get(1).getName());
    }

    @Test
    public void testIdAllocationCoversAllAttributeScopes() throws Exception
    {
        f.owner.getColumns().add(AdditionalColumnFixture.column("Direct", 7));
        f.entry("Object", "Items").getColumns().add(AdditionalColumnFixture.column("Other", 9));
        assertNull(f.create("Extra", "Goods", new String[1]));
        assertEquals(10, ((FormAttributeColumn)FormElementWriter.resolveFormMember(f.form, ref("Goods.Extra"))).getId());
    }

    @Test
    public void testDuplicateEntryColumnIsRefusedBeforeWriting() throws Exception
    {
        FormAttributeAdditionalColumns entry = f.entry("Object", "Goods");
        entry.getColumns().add(AdditionalColumnFixture.column("Extra", 4));
        String error = f.create("extra", "Goods", new String[1]);
        assertRefusal(error, "extra", "already exists");
        assertEquals(1, entry.getColumns().size());
    }

    @Test
    public void testTableChildrenAndRussianAliasesAreRefusedBeforeWriting() throws Exception
    {
        for (String name : List.of("product", "LineNumber", "\u0442\u043e\u0432\u0430\u0440", "\u043d\u043e\u043c\u0435\u0440\u0441\u0442\u0440\u043e\u043a\u0438"))
        {
            assertRefusal(f.create(name, "Goods", new String[1]), name, "table property");
            assertTrue(f.owner.getAdditionalColumns().isEmpty());
        }
    }

    @Test
    public void testCollectionOwnerNamesTheDirectColumnAddress() throws Exception
    {
        f.owner.setValueType(ComposerDataTreeFake.form("Rows", "ValueTable").getAttributes().get(0).getValueType());
        assertRefusal(f.create("Extra", "Goods", new String[1]), "ValueTable", "...Attribute.Object.Column.<Name>");
        assertTrue(f.owner.getColumns().isEmpty());
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
    }

    @Test
    public void testDynamicListOwnerNamesItsQuery() throws Exception
    {
        f.owner.setValueType(ComposerDataTreeFake.form("Rows", "DynamicList").getAttributes().get(0).getValueType());
        f.owner.setExtInfo(com._1c.g5.v8.dt.form.model.FormFactory.eINSTANCE.createDynamicListExtInfo());
        assertRefusal(f.create("Extra", "Goods", new String[1]), "dynamic list", "query");
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
    }

    @Test
    public void testUnknownTableListsAvailableTablesWithoutWriting() throws Exception
    {
        assertRefusal(f.create("Extra", "Missing", new String[1]), "No such table 'Missing'", "Goods");
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
        f.owner.setValueType(ComposerDataTreeFake.form("Scalar", "Number").getAttributes().get(0).getValueType());
        when(f.root.getPropertyInfos()).thenReturn(List.of());
        String scalarError = f.create("Extra", "Missing", new String[1]);
        assertRefusal(scalarError, "No such table 'Missing'", "(none)");
        assertFalse(scalarError, scalarError.contains("model is not ready"));
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
    }

    @Test
    public void testUnreadyModelIsDistinctFromAnUnknownTable() throws Exception
    {
        f.unavailable(true);
        String error = f.create("Extra", "Goods", new String[1]);
        assertRefusal(error, "model is not ready", "retry");
        assertFalse(error, error.contains("No such table"));
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
    }

    @Test
    public void testTableSupportPredicateIsMandatory() throws Exception
    {
        when(f.management.isSupportingColumn(f.table)).thenReturn(false);
        assertRefusal(f.create("Extra", "Goods", new String[1]), "Goods", "does not support additional columns");
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
    }

    @Test
    public void testArbitraryTableSourceIsRefusedEvenWhenTableAndSupported() throws Exception
    {
        when(f.table.getSource()).thenReturn(MdClassFactory.eINSTANCE.createCatalogAttribute());
        assertRefusal(f.create("Extra", "Goods", new String[1]), "Goods", "does not support additional columns");
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
    }

    @Test
    public void testEveryEdtAdditionalContainerSourceIsAccepted() throws Exception
    {
        DbViewFieldTableDef calculationTable = mock(DbViewFieldTableDef.class);
        when(calculationTable.getMdObject()).thenReturn(MdClassFactory.eINSTANCE.createChartOfCalculationTypes());
        List<Object> sources = Arrays.asList(null, AdditionalColumnFixture.column("Source", 1),
            mock(BasicTabularSection.class), MdClassFactory.eINSTANCE.createDataProcessorAttribute(),
            MdClassFactory.eINSTANCE.createAccumulationRegister(), MdClassFactory.eINSTANCE.createAccountingRegister(),
            MdClassFactory.eINSTANCE.createCalculationRegister(), calculationTable);
        for (Object source : sources)
        {
            when(f.table.getSource()).thenReturn(source);
            assertNull("allowed EDT source " + source, f.create("Extra", "Goods", new String[1]));
            assertEquals(1, f.owner.getAdditionalColumns().size());
            f.owner.getAdditionalColumns().clear();
        }
    }

    @Test
    public void testColumnSiblingsAreOnlyTheAddressedEntry() throws Exception
    {
        FormAttributeAdditionalColumns entry = f.entry("Object", "Goods");
        entry.getColumns().add(AdditionalColumnFixture.column("A", 4));
        f.entry("Object", "Other").getColumns().add(AdditionalColumnFixture.column("B", 5));
        f.owner.getColumns().add(AdditionalColumnFixture.column("C", 6));
        assertEquals(entry.getColumns(), FormElementWriter.columnSiblingsOf(f.form, ref("Goods.A")));
        assertTrue(FormElementWriter.columnSiblingsOf(f.form, ref("Absent.A")).isEmpty());
    }

    @Test
    public void testAdviceListsPathsAndTheCollectionAlternative() throws Exception
    {
        f.entry("Object", "Goods");
        f.entry("Object", "Items");
        String advice = FormElementWriter.additionalColumnAdvice(f.form, ref("Missing.Extra"));
        assertRefusal(advice, "Object.Missing", "Object.Goods, Object.Items");
        String routed = FormElementWriter.kindMismatchAdvice(f.form, ref("Missing.Extra"),
            AdditionalColumnFixture.PREFIX + "Missing.Extra");
        assertEquals(advice, routed);
        f.owner.setValueType(ComposerDataTreeFake.form("Rows", "ValueTree").getAttributes().get(0).getValueType());
        assertRefusal(FormElementWriter.additionalColumnAdvice(f.form, ref("Goods.Extra")), "ValueTree", "Column.<Name>");
    }

    @Test
    public void testBuildDataPathUsesTheSharedBuilder() throws Exception
    {
        // An item's binding and an entry's tablePath are the same DataPath for the same segments,
        // including the empty-segment filtering of a dotted path.
        for (String dotted : List.of("Object.Goods.Product", "Object..Goods.", "List"))
        {
            FormField item = FormFactory.eINSTANCE.createFormField();
            Method build = FormElementWriter.class.getDeclaredMethod("buildDataPath", EObject.class,
                EObject.class, String.class);
            build.setAccessible(true);
            build.invoke(null, f.form, item, dotted);
            EObject shared = FormElementWriter.newDataPath(f.form, Arrays.asList(dotted.split("\\.")));
            assertNotNull(dotted, item.getDataPath());
            assertTrue(dotted + ": " + item.getDataPath().getSegments() + " vs "
                + ((AbstractDataPath)shared).getSegments(), EcoreUtil.equals(item.getDataPath(), shared));
            assertFalse(dotted, item.getDataPath().getSegments().contains(""));
        }
        assertEquals(List.of("Object", "Goods", "Product"), ((AbstractDataPath)FormElementWriter.newDataPath(f.form,
            List.of("Object", "Goods", "Product"))).getSegments());
    }

    private FormMemberRef ref(String tail)
    {
        return AdditionalColumnFixture.ref(AdditionalColumnFixture.PREFIX + tail);
    }

    private static void assertRefusal(String error, String subject, String advice)
    {
        assertNotNull(error);
        assertTrue(error, error.contains(subject));
        assertTrue(error, error.contains(advice));
    }
}
