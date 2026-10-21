/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com._1c.g5.v8.bm.core.IBmObject;

/** Metadata addresses strip content segments while retaining real object and member names. */
@RunWith(Parameterized.class)
public class BmObjectMetadataAddressTest
{
    private final String contentFqn;
    private final String metadataAddress;

    public BmObjectMetadataAddressTest(String contentFqn, String metadataAddress)
    {
        this.contentFqn = contentFqn;
        this.metadataAddress = metadataAddress;
    }

    @Parameters(name = "{index}: {0}") //$NON-NLS-1$
    public static Collection<Object[]> addresses()
    {
        return Arrays.asList(new Object[][] {
            {"Catalog.Goods.Form.ListForm.Form", "Catalog.Goods.Form.ListForm"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Document.D.Forms.F.Form", "Document.D.Forms.F"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Report.R.Template.T.Template", "Report.R.Template.T"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Report.R.Templates.T.Template", "Report.R.Templates.T"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"CommonForm.F.Form", "CommonForm.F"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"CommonTemplate.T.Template", "CommonTemplate.T"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"commonforms.F.fOrM", "commonforms.F"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"commontemplates.T.tEmPlAtE", "commontemplates.T"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.Goods.\u0424\u043e\u0440\u043c\u0430.F.\u0424\u043e\u0440\u043c\u0430", "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.Goods.\u0424\u043e\u0440\u043c\u0430.F"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442.D.\u0424\u043e\u0440\u043c\u044b.F.Form", "\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442.D.\u0424\u043e\u0440\u043c\u044b.F"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"CommonForm.F.\u0424\u043e\u0440\u043c\u0430", "CommonForm.F"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"\u041e\u0431\u0449\u0430\u044f\u0424\u043e\u0440\u043c\u0430.F.\u0424\u043e\u0440\u043c\u0430", "\u041e\u0431\u0449\u0430\u044f\u0424\u043e\u0440\u043c\u0430.F"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"\u041e\u0442\u0447\u0435\u0442.R.\u041c\u0430\u043a\u0435\u0442.T.\u041c\u0430\u043a\u0435\u0442", "\u041e\u0442\u0447\u0435\u0442.R.\u041c\u0430\u043a\u0435\u0442.T"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Report.R.\u041c\u0430\u043a\u0435\u0442\u044b.T.Template", "Report.R.\u041c\u0430\u043a\u0435\u0442\u044b.T"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"CommonTemplate.T.\u041c\u0430\u043a\u0435\u0442", "CommonTemplate.T"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"\u041e\u0431\u0449\u0438\u0439\u041c\u0430\u043a\u0435\u0442.T.\u041c\u0430\u043a\u0435\u0442", "\u041e\u0431\u0449\u0438\u0439\u041c\u0430\u043a\u0435\u0442.T"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Role.R.Rights", "Role.R"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Role.R.rIgHtS", "Role.R"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"roles.R.Rights", "Role.R"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"\u0420\u043e\u043b\u044c.R.Rights", "Role.R"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"\u0420\u043e\u043b\u0438.R.rIgHtS", "Role.R"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Catalog.R.Rights", "Catalog.R.Rights"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Role.R", "Role.R"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Role.R.Right", "Role.R.Right"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Role.R.Rights.Child", "Role.R.Rights.Child"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Role.R.Attribute.X.Rights", "Role.R.Attribute.X.Rights"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Catalog.Goods", "Catalog.Goods"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Catalog.Goods.Form.Form", "Catalog.Goods.Form.Form"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Report.R.Template.Template", "Report.R.Template.Template"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"CommonForm.Form", "CommonForm.Form"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"CommonTemplate.Template", "CommonTemplate.Template"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Catalog.Goods.Attribute.X.Form", "Catalog.Goods.Attribute.X.Form"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Report.R.Attribute.X.Template", "Report.R.Attribute.X.Template"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Catalog.Goods.Form.F.Forms", "Catalog.Goods.Form.F.Forms"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Report.R.Template.T.Templates", "Report.R.Template.T.Templates"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Catalog.Goods.Form", "Catalog.Goods.Form"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Catalog.Goods.Template", "Catalog.Goods.Template"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"CommonForm.F.Template", "CommonForm.F.Template"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"CommonTemplate.T.Form", "CommonTemplate.T.Form"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Catalog.Goods.Form.F.Template", "Catalog.Goods.Form.F.Template"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Report.R.Template.T.Form", "Report.R.Template.T.Form"} //$NON-NLS-1$ //$NON-NLS-2$
        });
    }

    @Test
    public void testTopAndContainedObjectsExposeTheirMetadataAddress()
    {
        IBmObject top = mock(IBmObject.class);
        when(top.bmIsTop()).thenReturn(true);
        when(top.bmGetFqn()).thenReturn(contentFqn);
        IBmObject child = mock(IBmObject.class);
        when(child.bmGetTopObject()).thenReturn(top);
        assertEquals(metadataAddress, BmObjectAddress.metadataAddressOrNull(top));
        assertEquals(metadataAddress, BmObjectAddress.metadataAddressOrNull(child));
        assertEquals(contentFqn, BmObjectAddress.topFqnOrNull(child));
        verify(child, never()).bmGetFqn();
    }
}
