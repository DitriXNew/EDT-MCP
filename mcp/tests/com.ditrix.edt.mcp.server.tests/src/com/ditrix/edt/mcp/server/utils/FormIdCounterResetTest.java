/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.ToIntFunction;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.form.model.AbstractFormAttribute;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.service.FormIdentifierService;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.Kind;

/**
 * Issue #723: EDT's {@link FormIdentifierService} caches each form's current item / attribute /
 * command id as a BM property and rescans the form only while it is unset. A write that assigns or
 * renumbers ids must drop that cache, or the designer re-issues an id the write already took.
 */
public class FormIdCounterResetTest
{
    // FormIdentifierService's private BM property keys.
    private static final String[] COUNTERS = { "FORM_ITEM_CURRENT_ID", "FORM_ATTR_CURRENT_ID", //$NON-NLS-1$ //$NON-NLS-2$
        "FORM_CMD_CURRENT_ID" }; //$NON-NLS-1$

    /** A real (transient) content form whose three counters EDT has already cached at {@code 1}. */
    private static Form formWithStaleCounters()
    {
        Form form = (Form)FormElementWriter.createContentForm(null, null, Version.V8_5_1, false);
        for (String counter : COUNTERS)
        {
            ((IBmObject)form).bmSetProperty(counter, "1"); //$NON-NLS-1$
        }
        return form;
    }

    private static void assertCountersCleared(Form form)
    {
        for (String counter : COUNTERS)
        {
            assertNull(counter + " must be dropped so EDT rescans the form", //$NON-NLS-1$
                ((IBmObject)form).bmGetProperty(counter));
        }
    }

    private static <T> int maxId(Form form, Class<T> type, ToIntFunction<T> id)
    {
        int max = 0;
        for (TreeIterator<EObject> it = form.eAllContents(); it.hasNext();)
        {
            EObject next = it.next();
            if (type.isInstance(next))
            {
                max = Math.max(max, id.applyAsInt(type.cast(next)));
            }
        }
        return max;
    }

    private static void create(Form form, Kind kind, String name)
    {
        assertNull(FormElementWriter.createMember(form, kind, name, null, null, null, null, false, null));
    }

    @Test
    public void testCreatingAnItemLetsEdtAllocateAFreeItemId()
    {
        Form form = formWithStaleCounters();
        create(form, Kind.GROUP, "Main"); //$NON-NLS-1$
        FormElementWriter.normalizeFormIds(form);

        assertCountersCleared(form);
        int taken = maxId(form, FormItem.class, FormItem::getId);
        assertTrue("the group and its auto-children took ids above the stale counter", taken > 1); //$NON-NLS-1$
        assertTrue(FormIdentifierService.INSTANCE.getNextItemId(form) > taken);
    }

    @Test
    public void testCreatingAnAttributeLetsEdtAllocateAFreeAttributeId()
    {
        Form form = formWithStaleCounters();
        create(form, Kind.ATTRIBUTE, "First"); //$NON-NLS-1$
        create(form, Kind.ATTRIBUTE, "Second"); //$NON-NLS-1$
        FormElementWriter.normalizeFormIds(form);

        assertCountersCleared(form);
        int taken = maxId(form, AbstractFormAttribute.class, AbstractFormAttribute::getId);
        assertTrue(taken > 1);
        assertTrue(FormIdentifierService.INSTANCE.getNextAttributeId(form) > taken);
    }

    @Test
    public void testCreatingACommandLetsEdtAllocateAFreeCommandId()
    {
        Form form = formWithStaleCounters();
        create(form, Kind.COMMAND, "Run"); //$NON-NLS-1$
        create(form, Kind.COMMAND, "Stop"); //$NON-NLS-1$
        FormElementWriter.normalizeFormIds(form);

        assertCountersCleared(form);
        int taken = maxId(form, FormCommand.class, FormCommand::getId);
        assertTrue(taken > 1);
        assertTrue(FormIdentifierService.INSTANCE.getNextCommandId(form) > taken);
    }

    @Test
    public void testRenumberingADuplicateIdLetsEdtAllocateAboveIt()
    {
        Form form = formWithStaleCounters();
        for (String name : new String[] { "A", "B" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            FormGroup group = FormFactory.eINSTANCE.createFormGroup();
            group.setName(name);
            group.setId(1);
            form.getItems().add(group);
        }
        FormElementWriter.normalizeFormIds(form);

        assertCountersCleared(form);
        int taken = maxId(form, FormItem.class, FormItem::getId);
        assertTrue("the duplicate was moved above the stale counter", taken > 1); //$NON-NLS-1$
        assertTrue(FormIdentifierService.INSTANCE.getNextItemId(form) > taken);
    }

    @Test
    public void testAWriteToEitherPeerDropsTheOtherPeersCounters()
    {
        Form base = formWithStaleCounters();
        Form extension = formWithStaleCounters();
        base.setExtensionForm(extension);
        extension.setBaseForm(base);

        FormElementWriter.normalizeFormIds(base);
        assertCountersCleared(base);
        assertCountersCleared(extension);

        for (Form form : new Form[] { base, extension })
        {
            for (String counter : COUNTERS)
            {
                ((IBmObject)form).bmSetProperty(counter, "1"); //$NON-NLS-1$
            }
        }
        FormElementWriter.normalizeFormIds(extension);
        assertCountersCleared(extension);
        assertCountersCleared(base);
    }

    @Test
    public void testTheWriteBoundaryDropsTheCountersOfTheFormItMutated()
    {
        Form form = formWithStaleCounters();
        CatalogForm md = MdClassFactory.eINSTANCE.createCatalogForm();
        md.setForm(form);
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.getObjectById(7L)).thenReturn((IBmObject)md);
        IBmModel model = mock(IBmModel.class);
        when(model.execute(any())).thenAnswer(inv -> ((IBmTask<?>)inv.getArgument(0)).execute(tx, null));
        FormElementWriter.FormEditContext ctx =
            FormEditContextTestAccess.of(mock(IProject.class), model, md, 7L);

        try
        {
            FormElementWriter.writeEditableForm(ctx, "t", //$NON-NLS-1$
                (formModel, t) -> create((Form)formModel, Kind.ATTRIBUTE, "Added")); //$NON-NLS-1$
            fail("a headless form has no FQN to export"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            // Raised by the export-FQN lookup, the operation's last step: the work and the reset ran.
            assertNotNull(expected.getMessage());
            assertTrue(expected.getMessage(), expected.getMessage().contains("attached BM objects only")); //$NON-NLS-1$
        }
        assertNotNull(FormElementWriter.findFormAttribute(form, "Added")); //$NON-NLS-1$
        assertCountersCleared(form);
    }
}
