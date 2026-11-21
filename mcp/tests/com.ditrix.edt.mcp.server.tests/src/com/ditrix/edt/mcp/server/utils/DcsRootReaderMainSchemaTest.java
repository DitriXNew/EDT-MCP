/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
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

    private static final long STALE_TEMPLATE_ID = 11L;

    private static Target reportTargetWithoutTemplate() throws Exception
    {
        return reportTarget(null);
    }

    /** A report-root descriptor as resolved earlier, optionally carrying the template id it saw then. */
    private static Target reportTarget(Long templateId) throws Exception
    {
        DcsAddress address = DcsAddress.parse("Report.Sales").address(); //$NON-NLS-1$
        Map<BmRole, Long> ids = new EnumMap<>(BmRole.class);
        ids.put(BmRole.ROOT_OWNER, Long.valueOf(REPORT_ID));
        if (templateId != null) ids.put(BmRole.TEMPLATE, templateId);
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
        DataCompositionSchema schema = DcsAttachedSchemas.attached("Report.Sales.Template.Main.Template"); //$NON-NLS-1$
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

    private static Template dcsTemplate(DataCompositionSchema schema)
    {
        Template template = MdClassFactory.eINSTANCE.createTemplate();
        template.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
        template.setTemplate(schema);
        return template;
    }

    @Test
    public void testAReassignedMainSchemaIsReadFromTheReportNotFromTheStaleTemplateId() throws Exception
    {
        // The descriptor saw template A as the main schema; another write then pointed the report at B.
        DataCompositionSchema schemaA = DcsAttachedSchemas.attached("Report.Sales.Template.A.Template"); //$NON-NLS-1$
        DataCompositionSchema schemaB = DcsAttachedSchemas.attached("Report.Sales.Template.B.Template"); //$NON-NLS-1$
        Template templateA = dcsTemplate(schemaA);
        Template templateB = dcsTemplate(schemaB);
        Report report = MdClassFactory.eINSTANCE.createReport();
        report.getTemplates().add(templateA);
        report.getTemplates().add(templateB);
        report.setMainDataCompositionSchema(templateB);
        IBmTransaction transaction = transactionHolding(report);
        when(transaction.getObjectById(STALE_TEMPLATE_ID)).thenReturn((IBmObject)templateA);
        Target target = reportTarget(Long.valueOf(STALE_TEMPLATE_ID));

        DcsRootReader.Result read = DcsRootReader.read(transaction, target);

        assertTrue(read.error(), read.isSuccess());
        assertSame("the hash is checked on the schema the write will change, the report's current main one", //$NON-NLS-1$
            schemaB, read.root());
    }

    @Test
    public void testTheCurrentTemplateOfAReportIsItsMainOneNotTheDescriptorsId() throws Exception
    {
        Template templateA = dcsTemplate(DcsFactory.eINSTANCE.createDataCompositionSchema());
        Template templateB = dcsTemplate(DcsFactory.eINSTANCE.createDataCompositionSchema());
        Report report = MdClassFactory.eINSTANCE.createReport();
        report.getTemplates().add(templateA);
        report.getTemplates().add(templateB);
        report.setMainDataCompositionSchema(templateB);
        IBmTransaction transaction = transactionHolding(report);
        when(transaction.getObjectById(STALE_TEMPLATE_ID)).thenReturn((IBmObject)templateA);

        assertSame("the vendor check judges the template the write touches", templateB, //$NON-NLS-1$
            DcsRootReader.currentTemplate(transaction, reportTarget(Long.valueOf(STALE_TEMPLATE_ID))));
    }

    @Test
    public void testAMainTemplateRetargetedToAnEmptyNonDcsTemplateIsNotAnEmptySchema() throws Exception
    {
        // A hashless write must not pass as "creating the schema" inside a template declared as something else.
        Template printForm = MdClassFactory.eINSTANCE.createTemplate();
        printForm.setTemplateType(TemplateType.SPREADSHEET_DOCUMENT);
        Report report = MdClassFactory.eINSTANCE.createReport();
        report.getTemplates().add(printForm);
        report.setMainDataCompositionSchema(printForm);

        DcsRootReader.Result read = DcsRootReader.read(transactionHolding(report), reportTarget(Long.valueOf(STALE_TEMPLATE_ID)));

        assertFalse("an empty print form is not an empty DCS", read.isSuccess()); //$NON-NLS-1$
        assertTrue(read.error(), read.error().contains("not a data composition schema")); //$NON-NLS-1$
    }

    @Test
    public void testAVanishedReportIsAFailureNotAnAbsentSchema() throws Exception
    {
        // The report was deleted after the descriptor was taken: its stale template id must not be read,
        // and "no schema yet" must not let a hashless write pass as creation.
        IBmTransaction transaction = mock(IBmTransaction.class);
        when(transaction.getObjectById(STALE_TEMPLATE_ID))
            .thenReturn((IBmObject)dcsTemplate(DcsFactory.eINSTANCE.createDataCompositionSchema()));

        DcsRootReader.Result read = DcsRootReader.read(transaction, reportTarget(Long.valueOf(STALE_TEMPLATE_ID)));

        assertFalse("a vanished report reads as a failure", read.isSuccess()); //$NON-NLS-1$
        assertTrue(read.error(), read.error().contains("'Report.Sales' disappeared")); //$NON-NLS-1$
        assertFalse(DcsRootReader.read(transaction, reportTargetWithoutTemplate()).isSuccess());
    }
}
