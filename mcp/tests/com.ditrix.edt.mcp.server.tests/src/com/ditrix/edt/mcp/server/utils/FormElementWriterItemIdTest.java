/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.AutoCommandBar;
import com._1c.g5.v8.dt.form.model.ContextMenu;
import com._1c.g5.v8.dt.form.model.ExtendedTooltip;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormItem;

/** Regression coverage for non-zero negative form item ids (issue #732). */
public class FormElementWriterItemIdTest
{
    @Test
    public void testNegativeFieldAndAutoChildrenKeepTheirIds()
    {
        Form form = form();
        FormField field = field(form, -5);
        ContextMenu menu = FormFactory.eINSTANCE.createContextMenu();
        menu.setId(-6);
        field.setContextMenu(menu);
        ExtendedTooltip tooltip = FormFactory.eINSTANCE.createExtendedTooltip();
        tooltip.setId(-7);
        field.setExtendedTooltip(tooltip);

        for (int pass = 0; pass < 2; pass++)
        {
            FormElementWriter.normalizeFormItemIds(form);

            assertEquals("a unique negative field id is valid in EDT", -5, field.getId());
            assertEquals("the field's persisted context menu must keep its id", -6, menu.getId());
            assertEquals("the field's persisted tooltip must keep its id", -7, tooltip.getId());
            assertEquals(-1, form.getAutoCommandBar().getId());
        }
    }

    @Test
    public void testZeroIdsStillReceiveDistinctPositiveIds()
    {
        Form form = form();
        FormField field = field(form, 0);
        ContextMenu menu = FormFactory.eINSTANCE.createContextMenu();
        field.setContextMenu(menu);
        ExtendedTooltip tooltip = FormFactory.eINSTANCE.createExtendedTooltip();
        field.setExtendedTooltip(tooltip);

        FormElementWriter.normalizeFormItemIds(form);

        Set<Integer> ids = new HashSet<>();
        for (FormItem item : List.of(field, menu, tooltip))
        {
            assertTrue("zero ids must become positive", item.getId() > 0);
            assertTrue("replacement ids must be unique", ids.add(Integer.valueOf(item.getId())));
        }
        assertEquals(-1, form.getAutoCommandBar().getId());
    }

    @Test
    public void testExactlyOneDuplicateNegativeIdIsRenumbered()
    {
        Form form = form();
        FormField first = field(form, -5);
        FormField second = field(form, -5);

        FormElementWriter.normalizeFormItemIds(form);

        int preserved = (first.getId() == -5 ? 1 : 0) + (second.getId() == -5 ? 1 : 0);
        assertEquals("exactly one duplicate must retain the original negative id", 1, preserved);
        assertEquals("the first own item retains the id", -5, first.getId());
        assertEquals("negative ids must not lower the allocation ceiling below zero", 1, second.getId());
    }

    @Test
    public void testNonRootMinusOneCollidesWithRootSentinel()
    {
        Form form = form();
        FormField field = field(form, -1);

        FormElementWriter.normalizeFormItemIds(form);

        assertEquals("the root sentinel is reserved before visiting items", 1, field.getId());
        assertEquals(-1, form.getAutoCommandBar().getId());
    }

    @Test
    public void testRootCommandBarKeepsOrRestoresMinusOne()
    {
        for (int id : new int[] {-1, 0, 17})
        {
            Form form = form();
            form.getAutoCommandBar().setId(id);

            FormElementWriter.normalizeFormItemIds(form);

            assertEquals("the root command bar always uses the platform sentinel", -1,
                form.getAutoCommandBar().getId());
        }
    }

    @Test
    public void testAdoptedNegativeIdIsReservedRegardlessOfOrder()
    {
        for (boolean adoptedFirst : new boolean[] {false, true})
        {
            for (Boolean adopted : List.of(Boolean.TRUE, Boolean.FALSE))
            {
                Form form = form();
                FormField first = field(form, -5);
                FormField second = field(form, -5);
                FormField baseItem = adoptedFirst ? first : second;
                FormField ownItem = adoptedFirst ? second : first;
                baseItem.setAdopted(adopted);

                FormElementWriter.normalizeFormItemIds(form);

                assertEquals("an adopted negative id is immutable", -5, baseItem.getId());
                assertEquals("own items must avoid even a later adopted negative id", 1, ownItem.getId());
                assertEquals(adopted, baseItem.getAdopted());
            }
        }
    }

    @Test
    public void testNegativeIdsDoNotLowerPositiveAllocationCeiling()
    {
        Form form = form();
        FormField negative = field(form, -5);
        FormField positive = field(form, 42);
        FormField missing = field(form, 0);

        FormElementWriter.normalizeFormItemIds(form);

        assertEquals(-5, negative.getId());
        assertEquals(42, positive.getId());
        assertEquals(43, missing.getId());
    }

    @Test
    public void testNegativeAttributeAndCommandIdsKeepExistingRepairBehaviour()
    {
        Form form = form();
        FormAttribute ownAttribute = FormFactory.eINSTANCE.createFormAttribute();
        ownAttribute.setId(-5);
        form.getAttributes().add(ownAttribute);
        FormAttribute baseAttribute = FormFactory.eINSTANCE.createFormAttribute();
        baseAttribute.setId(-5);
        baseAttribute.setAdopted(Boolean.TRUE);
        form.getAttributes().add(baseAttribute);
        FormCommand ownCommand = FormFactory.eINSTANCE.createFormCommand();
        ownCommand.setId(-5);
        form.getFormCommands().add(ownCommand);
        FormCommand baseCommand = FormFactory.eINSTANCE.createFormCommand();
        baseCommand.setId(-5);
        baseCommand.setAdopted(Boolean.TRUE);
        form.getFormCommands().add(baseCommand);

        FormElementWriter.normalizeFormAttributeIds(form);
        FormElementWriter.normalizeFormCommandIds(form);

        assertEquals(1, ownAttribute.getId());
        assertEquals(1, ownCommand.getId());
        assertEquals(-5, baseAttribute.getId());
        assertEquals(-5, baseCommand.getId());
    }

    private static Form form()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        AutoCommandBar bar = FormFactory.eINSTANCE.createAutoCommandBar();
        bar.setId(-1);
        form.setAutoCommandBar(bar);
        return form;
    }

    private static FormField field(Form form, int id)
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setId(id);
        form.getItems().add(field);
        return field;
    }
}
