/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormPackage;

/** Tests BM external-property resolution of form-owned DCS content. */
public class DcsRootReaderTest
{
    @Test
    public void testUnattachedFormConditionalAppearanceProxyReadsAsEmpty()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.setConditionalAppearance(proxy());
        IBmTransaction transaction = mock(IBmTransaction.class);
        ITopObjectFqnGenerator generator = mock(ITopObjectFqnGenerator.class);
        String fqn = "Catalog.Products.Form.ItemForm.Form.ConditionalAppearance"; //$NON-NLS-1$
        when(generator.generateExternalPropertyFqn(form,
            FormPackage.Literals.FORM__CONDITIONAL_APPEARANCE)).thenReturn(fqn);
        when(transaction.getTopObjectByFqn(fqn)).thenReturn(null);

        DcsFormAppearanceContent.Result resolved =
            DcsFormAppearanceContent.resolve(transaction, form, generator);

        assertTrue(resolved.error(), resolved.isSuccess());
        assertNull("a normal proxy with no registered external top object is an empty appearance", //$NON-NLS-1$
            resolved.appearance());
        assertEquals(fqn, resolved.fqn());
    }

    @Test
    public void testUnattachedFormConditionalAppearanceProxyIsAttachedRefetchedAndFilled()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.setConditionalAppearance(proxy());
        DataCompositionConditionalAppearance planned =
            DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
        planned.getItems().add(DcsFactory.eINSTANCE
            .createDataCompositionConditionalAppearanceItem());
        DataCompositionConditionalAppearance attached =
            DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
        assertTrue(attached instanceof IBmObject);

        IBmTransaction transaction = mock(IBmTransaction.class);
        ITopObjectFqnGenerator generator = mock(ITopObjectFqnGenerator.class);
        String fqn = "Catalog.Products.Form.ItemForm.Form.ConditionalAppearance"; //$NON-NLS-1$
        when(generator.generateExternalPropertyFqn(form,
            FormPackage.Literals.FORM__CONDITIONAL_APPEARANCE)).thenReturn(fqn);
        when(transaction.getTopObjectByFqn(fqn))
            .thenReturn(null, (IBmObject)attached);
        doAnswer(invocation ->
        {
            DataCompositionConditionalAppearance empty = invocation.getArgument(0);
            assertTrue("attachment must receive a fresh non-proxy carrier", !empty.eIsProxy()); //$NON-NLS-1$
            assertTrue("planned items must be copied only after attachment", empty.getItems().isEmpty()); //$NON-NLS-1$
            // Reproduce BM replacing the assigned carrier during attachment. The commit must fill
            // the object re-fetched under the external-property FQN, not this pre-attach instance.
            form.setConditionalAppearance(attached);
            return null;
        }).when(transaction).attachTopObject(any(IBmObject.class), eq(fqn));

        DcsFormAppearanceContent.Result committed =
            DcsFormAppearanceContent.commit(transaction, form, planned, generator);

        assertTrue(committed.error(), committed.isSuccess());
        verify(transaction).attachTopObject(any(IBmObject.class), eq(fqn));
        assertSame("the committed carrier must be the BM re-fetch", attached, //$NON-NLS-1$
            committed.appearance());
        assertSame(attached, form.getConditionalAppearance());
        assertEquals(1, attached.getItems().size());
        assertNull(DcsModelComparison.firstDifference(planned, attached));
    }

    private static DataCompositionConditionalAppearance proxy()
    {
        DataCompositionConditionalAppearance proxy =
            DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
        ((InternalEObject)proxy).eSetProxyURI(
            URI.createURI("bm://project/Catalog.Products.Form.ItemForm.Form.ConditionalAppearance")); //$NON-NLS-1$
        return proxy;
    }
}
