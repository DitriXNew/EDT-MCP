/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.BmRole;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.Target;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;

/**
 * #662 review: a report's main-schema descriptor taken before the report had a main DCS template must
 * not keep answering "absent" once a write created one - the transaction asks the report itself, or a
 * hashless write into that now existing schema would pass as its creation.
 */
public class DcsRootReaderMainSchemaTest
{
    private static final long REPORT_ID = 7L;

    private static Target reportTargetWithoutTemplate() throws Exception
    {
        DcsAddress address = DcsAddress.parse("Report.Sales").address(); //$NON-NLS-1$
        Map<BmRole, Long> ids = new EnumMap<>(BmRole.class);
        ids.put(BmRole.ROOT_OWNER, Long.valueOf(REPORT_ID));
        Constructor<Target> constructor = Target.class.getDeclaredConstructor(DcsAddress.class, String.class,
            TargetKind.class, Map.class, List.class, FormElementWriter.FormMemberRef.class);
        constructor.setAccessible(true);
        return constructor.newInstance(address, "Report.Sales", TargetKind.REPORT_MAIN_DCS, ids, List.of(), null); //$NON-NLS-1$
    }

    private static IBmTransaction transactionHolding(EObject report)
    {
        IBmTransaction transaction = mock(IBmTransaction.class);
        when(transaction.getObjectById(REPORT_ID)).thenReturn((IBmObject)report);
        return transaction;
    }

    @Test
    public void testAMainSchemaCreatedAfterTheDescriptorIsReadFromTheReport() throws Exception
    {
        Report report = MdClassFactory.eINSTANCE.createReport();
        Template template = MdClassFactory.eINSTANCE.createTemplate();
        template.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        template.setTemplate(schema);
        report.getTemplates().add(template);
        report.setMainDataCompositionSchema(template);

        DcsRootReader.Result read = DcsRootReader.read(transactionHolding(report), reportTargetWithoutTemplate());

        assertTrue(read.error(), read.isSuccess());
        assertSame("the report's current main schema, not the stale 'absent' of the descriptor", //$NON-NLS-1$
            schema, read.root());
    }

    @Test
    public void testAReportStillWithoutAMainSchemaReadsAsAbsent() throws Exception
    {
        DcsRootReader.Result read = DcsRootReader.read(
            transactionHolding(MdClassFactory.eINSTANCE.createReport()), reportTargetWithoutTemplate());
        assertTrue(read.error(), read.isSuccess());
        assertNull("creating the main schema still needs no hash", read.root()); //$NON-NLS-1$
    }
}
