/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.BmRole;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.Target;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;

/**
 * #662 review: the reader that computes a write's hash and the resolver that hands the write its schema must
 * agree on WHICH object is the schema. An attached schema is both hashed and mutated; a schema that is not
 * attached is replaced by the resolver with a fresh one, so the reader must hash it as no schema yet - else a
 * hash of the detached object would let a write pass and then change a different, empty schema.
 */
public class DcsSchemaReadWriteAgreementTest
{
    private static final long TEMPLATE_ID = 21L;
    private static final String CONTENT_FQN = "CommonTemplate.Sales.Template"; //$NON-NLS-1$

    private static Target commonTemplateTarget() throws Exception
    {
        DcsAddress address = DcsAddress.parse("CommonTemplate.Sales").address(); //$NON-NLS-1$
        Map<BmRole, Long> ids = new EnumMap<>(BmRole.class);
        ids.put(BmRole.TEMPLATE, Long.valueOf(TEMPLATE_ID));
        Constructor<Target> constructor = Target.class.getDeclaredConstructor(DcsAddress.class, String.class,
            TargetKind.class, Map.class, List.class, FormElementWriter.FormMemberRef.class);
        constructor.setAccessible(true);
        return constructor.newInstance(address, "CommonTemplate.Sales", TargetKind.COMMON_TEMPLATE, ids, //$NON-NLS-1$
            List.of(), null);
    }

    private static Template dcsTemplate(DataCompositionSchema content)
    {
        Template template = MdClassFactory.eINSTANCE.createTemplate();
        template.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
        template.setTemplate(content);
        return template;
    }

    private static IBmTransaction transactionHolding(Template template)
    {
        IBmTransaction transaction = mock(IBmTransaction.class);
        when(transaction.getObjectById(TEMPLATE_ID)).thenReturn((IBmObject)template);
        return transaction;
    }

    private static ITopObjectFqnGenerator names()
    {
        ITopObjectFqnGenerator names = mock(ITopObjectFqnGenerator.class);
        when(names.generateExternalPropertyFqn(any(), any())).thenReturn(CONTENT_FQN);
        return names;
    }

    @Test
    public void testAnAttachedSchemaIsHashedAndWrittenAsTheSameObject() throws Exception
    {
        DataCompositionSchema schema = DcsAttachedSchemas.attached(CONTENT_FQN);
        Template template = dcsTemplate(schema);
        IBmTransaction transaction = transactionHolding(template);

        DcsRootReader.Result read = DcsRootReader.read(transaction, commonTemplateTarget());
        DcsSchemaContent.ResolveResult written =
            DcsSchemaContent.resolveTemplateContent(transaction, template, names(), "CommonTemplate.Sales"); //$NON-NLS-1$

        assertTrue(read.error(), read.isSuccess());
        assertSame(schema, read.root());
        assertTrue(written.isSuccess());
        assertSame("the write mutates the very schema whose hash was checked", schema, written.schema()); //$NON-NLS-1$
    }

    @Test
    public void testAnEmptySchemaNotAttachedIsHashedAsNoSchemaBecauseTheWriteReplacesIt() throws Exception
    {
        DataCompositionSchema detached = DcsFactory.eINSTANCE.createDataCompositionSchema();
        Template template = dcsTemplate(detached);
        IBmTransaction transaction = transactionHolding(template);

        DcsRootReader.Result read = DcsRootReader.read(transaction, commonTemplateTarget());
        DcsSchemaContent.ResolveResult written =
            DcsSchemaContent.resolveTemplateContent(transaction, template, names(), "CommonTemplate.Sales"); //$NON-NLS-1$

        assertTrue(read.error(), read.isSuccess());
        assertNull("the hash is the hash of no schema, as the write creates a fresh one", read.root()); //$NON-NLS-1$
        assertTrue(written.isSuccess());
        assertNotSame("the write does not touch the detached object", detached, written.schema()); //$NON-NLS-1$
    }

    @Test
    public void testADetachedSchemaHoldingContentIsRefusedByBothAndNotReplaced() throws Exception
    {
        DataCompositionSchema detached = DcsFactory.eINSTANCE.createDataCompositionSchema();
        detached.getDataSources().add(DcsFactory.eINSTANCE.createDataCompositionSchemaDataSource());
        Template template = dcsTemplate(detached);
        IBmTransaction transaction = transactionHolding(template);

        DcsRootReader.Result read = DcsRootReader.read(transaction, commonTemplateTarget());
        DcsSchemaContent.ResolveResult written =
            DcsSchemaContent.resolveTemplateContent(transaction, template, names(), "CommonTemplate.Sales"); //$NON-NLS-1$

        assertFalse("no hash is offered for content the write cannot change", read.isSuccess()); //$NON-NLS-1$
        assertTrue(read.error(), read.error().contains("not attached to the EDT model")); //$NON-NLS-1$
        assertFalse("the write refuses instead of replacing the content with an empty schema", written.isSuccess()); //$NON-NLS-1$
        assertSame("the template still holds what it held", detached, template.getTemplate()); //$NON-NLS-1$
    }

    @Test
    public void testAnUnresolvedSchemaReferenceIsRefusedByBothNotReadAsEmpty() throws Exception
    {
        // A typed proxy shows no content of its own but stands for a stored schema.
        DataCompositionSchema proxy = DcsFactory.eINSTANCE.createDataCompositionSchema();
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI("bm://TestConfiguration/" + CONTENT_FQN)); //$NON-NLS-1$
        Template template = dcsTemplate(proxy);
        IBmTransaction transaction = transactionHolding(template);

        DcsRootReader.Result read = DcsRootReader.read(transaction, commonTemplateTarget());
        DcsSchemaContent.ResolveResult written =
            DcsSchemaContent.resolveTemplateContent(transaction, template, names(), "CommonTemplate.Sales"); //$NON-NLS-1$

        assertFalse("an unresolved reference is not an empty schema", read.isSuccess()); //$NON-NLS-1$
        assertTrue(read.error(), read.error().contains("unresolved reference")); //$NON-NLS-1$
        assertTrue(read.error(), read.error().contains("Save any unsaved edits")); //$NON-NLS-1$
        assertFalse("the write does not replace the reference", written.isSuccess()); //$NON-NLS-1$
    }

    @Test
    public void testTheSharedTestNamesOnlyAnAttachedSchema()
    {
        assertTrue(DcsSchemaContent.isWrittenSchema(DcsAttachedSchemas.attached(CONTENT_FQN)));
        assertTrue(!DcsSchemaContent.isWrittenSchema(DcsFactory.eINSTANCE.createDataCompositionSchema()));
        assertTrue(!DcsSchemaContent.isWrittenSchema(null));
    }
}
