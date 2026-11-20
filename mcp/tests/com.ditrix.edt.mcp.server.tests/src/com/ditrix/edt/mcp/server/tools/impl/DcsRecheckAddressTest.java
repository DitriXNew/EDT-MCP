/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * #662 review: the re-check an incomplete validation names is the caller-facing address
 * {@code get_project_errors objectFqns} takes - all-English singular tokens, the way EDT presents the
 * markers - not the internal {@code Type.Object.forms.Name} path, a plural or Russian token the caller
 * typed, or a trailing dot the form parser forgave.
 */
public class DcsRecheckAddressTest
{
    /** Spravochnik.Tovary.Forma.FormaSpiska.Rekvizit.Spisok - a Russian dynamic-list address. */
    private static final String RU_LIST = "Справочник.Товары."
        + "Форма.ФормаСписка."
        + "Реквизит.Список"; //$NON-NLS-1$
    private static final String RU_FORM = "Catalog.Товары.Form."
        + "ФормаСписка"; //$NON-NLS-1$

    /** What DcsTool passes for a dynamic list: the normalized root and the parsed form path. */
    private static String forList(String root)
    {
        String normalized = MetadataTypeUtils.normalizeFqn(root);
        return DcsTool.recheckAddress(normalized, FormElementWriter.parse(normalized).formPath);
    }

    /** What DcsTool passes for a form root. */
    private static String forForm(String root)
    {
        String normalized = MetadataTypeUtils.normalizeFqn(root);
        return DcsTool.recheckAddress(normalized, FormElementWriter.parseFormPath(normalized));
    }

    @Test
    public void testADynamicListIsRecheckedByItsSingularEnglishFormAddress()
    {
        assertEquals("Catalog.Products.Form.ListForm", forList("Catalog.Products.Form.ListForm.Attribute.List")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Products.Form.ListForm", forList("Catalog.Products.Forms.ListForm.Attributes.List")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(RU_FORM, forList(RU_LIST));
        assertEquals("CommonForm.Picker", forList("CommonForm.Picker.Attribute.List")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testATrailingDotTheFormParserAcceptsStillNamesTheForm()
    {
        assertEquals("Catalog.Products.Form.ListForm", forList("Catalog.Products.Form.ListForm.Attribute.List.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Products.Form.ListForm", forForm("Catalog.Products.Form.ListForm.")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAFormIsRecheckedByItsSingularEnglishAddress()
    {
        assertEquals("Catalog.Products.Form.ListForm", forForm("Catalog.Products.Forms.ListForm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Products.Form.ListForm", forForm("Catalog.Products.Form.ListForm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonForm.Picker", forForm("CommonForm.Picker")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testATemplateIsRecheckedByItsSingularEnglishAddress()
    {
        assertEquals("Report.Sales.Template.Main", DcsTool.recheckAddress("Report.Sales.Templates.Main", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Report.Sales", DcsTool.recheckAddress("Report.Sales", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonTemplate.Layout", DcsTool.recheckAddress("CommonTemplate.Layout", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
