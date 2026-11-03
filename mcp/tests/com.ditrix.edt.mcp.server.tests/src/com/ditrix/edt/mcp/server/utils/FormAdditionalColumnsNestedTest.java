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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttributeAdditionalColumns;
import com._1c.g5.v8.dt.form.model.FormAttributeColumn;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormMemberRef;

/**
 * Issue #737 review, round 3: a nested table path is addressable with every segment between
 * {@code Column} and the name; a reused case-variant entry keeps its stored path; and a built but
 * empty object tree is a final answer, not a readiness delay.
 */
public class FormAdditionalColumnsNestedTest
{
    private static final String PREFIX = AdditionalColumnFixture.PREFIX;

    private AdditionalColumnFixture f;

    @Before
    public void setUp() throws Exception
    {
        f = new AdditionalColumnFixture();
    }

    @After
    public void tearDown()
    {
        f.close();
    }

    // ---- nested table paths: lookup -----------------------------------------------------------

    @Test
    public void testANestedAddressTakesEverySegmentBetweenColumnAndTheName()
    {
        FormMemberRef ref = AdditionalColumnFixture.ref(PREFIX + "Sub.Deep.Extra");
        assertEquals("Extra", ref.name);
        assertEquals("Object", ref.ownerAttributeName);
        assertEquals(6, ref.tailSegments);
        assertTrue(ref.isAttributeColumn());
        assertTrue(ref.isAdditionalColumn());
        assertFalse(ref.isItemLevel());
        assertNull(FormElementWriter.columnAddressingError(ref));
        assertEquals(7, AdditionalColumnFixture.ref(PREFIX + "Sub.Deep.More.Extra").tailSegments);
        assertEquals("CommonForm.F", AdditionalColumnFixture.ref(
            "CommonForm.F.Attribute.Object.Column.Sub.Deep.Extra").formPath);
    }

    @Test
    public void testTheCollectionColumnAndOneSegmentShapesAreUnchanged()
    {
        FormMemberRef collection = AdditionalColumnFixture.ref("Catalog.Catalog.Form.ItemForm.Attribute.Rows.Column.Price");
        assertEquals(4, collection.tailSegments);
        assertTrue(collection.isAttributeColumn());
        assertFalse(collection.isAdditionalColumn());
        assertEquals("Rows", collection.ownerAttributeName);
        assertEquals("Price", collection.name);
        FormMemberRef single = AdditionalColumnFixture.ref(PREFIX + "Goods.Extra");
        assertEquals(5, single.tailSegments);
        assertTrue(single.isAdditionalColumn());
        assertEquals("Extra", single.name);
    }

    @Test
    public void testANestedAddressFindsTheEntryWithItsWholeTablePath()
    {
        FormAttributeAdditionalColumns deep = f.entry("Object", "Sub", "Deep");
        FormAttributeColumn deepColumn = AdditionalColumnFixture.column("Extra", 11);
        deep.getColumns().add(deepColumn);
        FormAttributeAdditionalColumns shallow = f.entry("Object", "Sub");
        FormAttributeColumn shallowColumn = AdditionalColumnFixture.column("Extra", 12);
        shallow.getColumns().add(shallowColumn);

        assertSame(deepColumn, FormElementWriter.findColumn(f.form,
            AdditionalColumnFixture.ref(PREFIX + "Sub.Deep.Extra"), "Extra"));
        assertSame("table segments match in any case", deepColumn, FormElementWriter.findColumn(f.form, //$NON-NLS-1$
            AdditionalColumnFixture.ref(PREFIX + "sub.DEEP.Extra"), "Extra"));
        assertSame("the one-segment entry is a namespace of its own", shallowColumn, //$NON-NLS-1$
            FormElementWriter.findColumn(f.form, AdditionalColumnFixture.ref(PREFIX + "Sub.Extra"), "Extra"));
        assertNull("a deeper path names an entry that does not exist", FormElementWriter.findColumn(f.form, //$NON-NLS-1$
            AdditionalColumnFixture.ref(PREFIX + "Sub.Deep.More.Extra"), "Extra"));
        assertEquals("Object.Sub.Deep", FormElementWriter.tablePathOfAdditionalColumn(deepColumn));
    }

    @Test
    public void testANestedColumnRenameChecksTheNestedTableThroughEdt()
    {
        PropertyInfo deep = AdditionalColumnFixture.property("Deep", true);
        PropertyInfo quantity = AdditionalColumnFixture.property("Qty", false);
        when(deep.getPropertyInfos()).thenReturn(List.of(quantity));
        PropertyInfo sub = AdditionalColumnFixture.property("Sub", true);
        when(sub.getPropertyInfos()).thenReturn(List.of(deep));
        when(f.root.getPropertyInfos()).thenReturn(List.of(f.table, sub));
        when(f.management.isSupportingColumn(deep)).thenReturn(true);
        doAnswer(call -> {
            List<String> path = ((AbstractDataPath)call.getArgument(1)).getSegments();
            if (path.equals(List.of("Object")))
            {
                return f.root;
            }
            if (path.equals(List.of("Object", "Sub")))
            {
                return sub;
            }
            return path.equals(List.of("Object", "Sub", "Deep")) ? deep : null;
        }).when(f.sources).findPropertyInfo(any(Form.class), any(AbstractDataPath.class));

        String clash = FormElementWriter.additionalColumnNameError(f.form,
            AdditionalColumnFixture.ref(PREFIX + "Sub.Deep.Extra"), "Qty");
        assertNotNull("a property of the NESTED table is a clash", clash); //$NON-NLS-1$
        assertTrue(clash, clash.contains("'Qty' under 'Object.Sub.Deep'")); //$NON-NLS-1$
        assertNull(FormElementWriter.additionalColumnNameError(f.form,
            AdditionalColumnFixture.ref(PREFIX + "Sub.Deep.Extra"), "Fresh"));
        // Every segment matches its table in any case, through the writer's fallback below each level.
        String caseVariant = FormElementWriter.additionalColumnNameError(f.form,
            AdditionalColumnFixture.ref(PREFIX + "SUB.deep.Extra"), "qty");
        assertNotNull(caseVariant);
        assertTrue(caseVariant, caseVariant.contains("under 'Object.Sub.Deep'")); //$NON-NLS-1$
        String missing = FormElementWriter.additionalColumnNameError(f.form,
            AdditionalColumnFixture.ref(PREFIX + "Sub.Missing.Extra"), "Fresh");
        assertNotNull(missing);
        assertTrue(missing, missing.contains("No such table 'Missing' under 'Object.Sub'; available tables: Deep.")); //$NON-NLS-1$
        assertFalse(missing, missing.contains("retry")); //$NON-NLS-1$
    }

    // ---- a reused case-variant entry keeps its stored path ------------------------------------

    @Test
    public void testAReusedCaseVariantEntryKeepsItsTablePathAndItsBoundItems()
    {
        FormAttributeAdditionalColumns entry = f.entry("object", "GOODS");
        entry.getColumns().add(AdditionalColumnFixture.column("Old", 7));
        FormField bound = FormFactory.eINSTANCE.createFormField();
        bound.setName("OldField");
        bound.setDataPath(ComposerDataTreeFake.dataPath("object.GOODS.Old"));
        f.form.getItems().add(bound);

        String[] tablePath = new String[1];
        assertNull(f.create("Second", "gOoDs", new String[1], tablePath));

        assertEquals("one entry, reused", 1, f.owner.getAdditionalColumns().size()); //$NON-NLS-1$
        assertSame(entry, f.owner.getAdditionalColumns().get(0));
        assertEquals("the reused entry's stored path is not rewritten", List.of("object", "GOODS"), //$NON-NLS-1$
            entry.getTablePath().getSegments());
        assertEquals("the response reports the entry's actual path", "object.GOODS", tablePath[0]); //$NON-NLS-1$
        assertEquals("a bound item's dataPath still starts with the entry's exact path", //$NON-NLS-1$
            entry.getTablePath().getSegments(), bound.getDataPath().getSegments().subList(0, 2));
        assertEquals(List.of("Old", "Second"), entry.getColumns().stream().map(FormAttributeColumn::getName).toList());
    }

    @Test
    public void testANewEntryStillTakesTheCanonicalSpelling()
    {
        String[] tablePath = new String[1];
        assertNull(f.create("First", "gOoDs", new String[1], tablePath));
        assertEquals("Object.Goods", tablePath[0]);
        assertEquals(List.of("Object", "Goods"), f.owner.getAdditionalColumns().get(0).getTablePath().getSegments());
    }

    // ---- an empty but built object tree -------------------------------------------------------

    @Test
    public void testABuiltButEmptyObjectTreeIsAFinalRefusal()
    {
        when(f.root.getPropertyInfos()).thenReturn(List.of());
        doAnswer(call -> ((AbstractDataPath)call.getArgument(1)).getSegments().size() == 1 ? f.root : null)
            .when(f.sources).findPropertyInfo(any(Form.class), any(AbstractDataPath.class));
        String error = f.create("Extra", "Goods", new String[1]);
        assertNotNull(error);
        assertTrue(error, error.contains("No such table 'Goods' on form attribute 'Object'; available tables: (none).")); //$NON-NLS-1$
        assertFalse("a built tree is final, so no retry is advised: " + error, error.contains("retry")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(error, error.contains("not ready")); //$NON-NLS-1$
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
    }

    @Test
    public void testOnlyAMissingObjectTreeIsARetry()
    {
        when(f.root.getPropertyInfos()).thenReturn(null);
        String unbuilt = f.create("Extra", "Goods", new String[1]);
        assertTrue(unbuilt, unbuilt.contains("model is not ready; retry")); //$NON-NLS-1$
        doAnswer(call -> null).when(f.sources).findPropertyInfo(any(Form.class), any(AbstractDataPath.class));
        String missing = f.create("Extra", "Goods", new String[1]);
        assertTrue(missing, missing.contains("model is not ready; retry")); //$NON-NLS-1$
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
    }

    @Test
    public void testAnUntypedOwnerKeepsItsOwnRefusalOverAnEmptyTree()
    {
        f.owner.setValueType(McoreFactory.eINSTANCE.createTypeDescription());
        when(f.root.getPropertyInfos()).thenReturn(List.of());
        String error = f.create("Extra", "Goods", new String[1]);
        assertTrue(error, error.contains("'Object' has no value type")); //$NON-NLS-1$
        assertFalse(error, error.contains("available tables")); //$NON-NLS-1$
    }
}
