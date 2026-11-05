/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/** Tests the pure root dispatch, persistence mapping and actionable resolver errors. */
public class DcsTargetResolverTest
{
    @Test
    public void targetIdentityAndOwnerMustBeCapturedInTheResolutionRead() throws Exception
    {
        for (String route : new String[] {"CommonTemplate", "Report", "CommonForm", "DynamicList"})
        {
            String kind = route.equals("DynamicList") ? "CommonForm" : route;
            for (String name : new String[] {"Target", "Цель"})
            {
            try (var fixture = new HeadlessReadFixture())
            {
                com._1c.g5.v8.dt.metadata.mdclass.MdObject stored;
                Class<? extends com._1c.g5.v8.dt.metadata.mdclass.MdObject> type;
                if (kind.equals("CommonTemplate"))
                {
                    stored = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonTemplate();
                    type = com._1c.g5.v8.dt.metadata.mdclass.CommonTemplate.class;
                }
                else if (kind.equals("Report"))
                {
                    stored = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createReport();
                    type = com._1c.g5.v8.dt.metadata.mdclass.Report.class;
                }
                else
                {
                    stored = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonForm();
                    type = com._1c.g5.v8.dt.metadata.mdclass.CommonForm.class;
                }
                stored.setName(name);
                stored.getSynonym().put("en", "Caption");
                int[] resolvedIn = {-1};
                var object = org.mockito.Mockito.mock(type, org.mockito.Mockito.withSettings()
                    .extraInterfaces(com._1c.g5.v8.bm.core.IBmObject.class, org.eclipse.emf.ecore.InternalEObject.class)
                    .defaultAnswer(call -> {
                        String method = call.getMethod().getName();
                        if (method.equals("getName")) { resolvedIn[0] = fixture.reads; return name; }
                        if (method.startsWith("bm"))
                        {
                            assertEquals("Live " + kind + " escaped the read that resolved it", resolvedIn[0], fixture.reads);
                            assertTrue(fixture.depth > 0);
                            if (method.equals("bmGetId")) return 17L;
                            if (method.equals("bmIsTop")) return true;
                            if (method.equals("bmGetFqn")) return kind + "." + name;
                        }
                        return call.getMethod().invoke(stored, call.getArguments());
                    }));
                if (kind.equals("CommonTemplate")) fixture.config.getCommonTemplates().add((com._1c.g5.v8.dt.metadata.mdclass.CommonTemplate)object);
                else if (kind.equals("Report")) fixture.config.getReports().add((com._1c.g5.v8.dt.metadata.mdclass.Report)object);
                else fixture.config.getCommonForms().add((com._1c.g5.v8.dt.metadata.mdclass.CommonForm)object);
                var tx = org.mockito.Mockito.mock(com._1c.g5.v8.bm.core.IBmTransaction.class);
                org.mockito.Mockito.when(tx.getObjectById(17L)).thenReturn((com._1c.g5.v8.bm.core.IBmObject)stored);
                org.mockito.Mockito.when(fixture.model.executeAndRollback(org.mockito.ArgumentMatchers.any())).thenAnswer(call ->
                    ((com._1c.g5.v8.bm.integration.IBmTask<?>)call.getArgument(0)).execute(tx, null));
                var context = ProjectContext.resolveMetadataRoot(fixture.name());
                String token = name.equals("Target") ? kind : kind.equals("CommonTemplate") ? "ОбщийМакет"
                    : kind.equals("Report") ? "Отчет" : "ОбщаяФорма";
                String fqn = token + "." + name + (route.equals("DynamicList") ? ".Attribute.List" : "");
                var result = DcsTargetResolver.resolve(context, fixture.model, DcsAddress.parse(fqn).address());
                org.junit.Assert.assertNotEquals(result.isSuccess() ? "resolved" : result.failure().message(),
                    DcsTargetResolver.FailureCode.RESOLUTION_FAILED, result.isSuccess() ? null : result.failure().code());
                org.mockito.Mockito.verify(tx).getObjectById(17L);
                var bySynonym = DcsTargetResolver.resolve(context, fixture.model,
                    DcsAddress.parse(kind + ".Caption").address());
                org.junit.Assert.assertEquals(DcsTargetResolver.FailureCode.TARGET_NOT_FOUND, bySynonym.failure().code());
            }
            }
        }
    }

    @Test
    public void testClassifiesReportMainDcs()
    {
        assertKind("Report.Sales", DcsTargetResolver.TargetKind.REPORT_MAIN_DCS); //$NON-NLS-1$
    }

    @Test
    public void testClassifiesExternalReportMainDcs()
    {
        assertKind("ExternalReport.Sales", DcsTargetResolver.TargetKind.REPORT_MAIN_DCS); //$NON-NLS-1$
    }

    @Test
    public void testClassifiesCommonTemplate()
    {
        assertKind("CommonTemplate.Analytics", DcsTargetResolver.TargetKind.COMMON_TEMPLATE); //$NON-NLS-1$
    }

    @Test
    public void testClassifiesOwnedTemplate()
    {
        assertKind("Report.Sales.Template.CustomDcs", DcsTargetResolver.TargetKind.OWNED_TEMPLATE); //$NON-NLS-1$
    }

    @Test
    public void testClassifiesFormAttributeDynamicList()
    {
        DcsTargetResolver.RootClassification classification = classify(
            "Catalog.Products.Form.ListForm.Attribute.List"); //$NON-NLS-1$

        assertTrue(classification.isSuccess());
        assertEquals(DcsTargetResolver.TargetKind.DYNAMIC_LIST, classification.kind);
        assertNotNull(classification.formMemberRef);
        assertEquals("List", classification.formMemberRef.name); //$NON-NLS-1$
    }

    @Test
    public void testClassifiesManagedFormForConditionalAppearance()
    {
        assertKind("Catalog.Products.Form.ListForm", DcsTargetResolver.TargetKind.FORM); //$NON-NLS-1$
        assertKind("CommonForm.Dashboard", DcsTargetResolver.TargetKind.FORM); //$NON-NLS-1$
        assertEquals(Arrays.asList(DcsTargetResolver.ExportRole.FORM_CONTENT),
            DcsTargetResolver.requiredExportRoles(DcsTargetResolver.TargetKind.FORM));
    }

    @Test
    public void testRootKindDispatchIsBilingual()
    {
        assertKind("\u041E\u0442\u0447\u0435\u0442.Sales", //$NON-NLS-1$
            DcsTargetResolver.TargetKind.REPORT_MAIN_DCS);
        assertKind("\u0412\u043D\u0435\u0448\u043D\u0438\u0439\u041E\u0442\u0447\u0435\u0442.Sales", //$NON-NLS-1$
            DcsTargetResolver.TargetKind.REPORT_MAIN_DCS);
        assertKind("Report.Sales.\u041C\u0430\u043A\u0435\u0442.CustomDcs", //$NON-NLS-1$
            DcsTargetResolver.TargetKind.OWNED_TEMPLATE);
        assertKind("Catalog.Products.\u0424\u043E\u0440\u043C\u0430.ListForm." //$NON-NLS-1$
            + "\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442.List", //$NON-NLS-1$
            DcsTargetResolver.TargetKind.DYNAMIC_LIST);
        assertKind("Catalog.Products.\u0424\u043E\u0440\u043C\u0430.ListForm", //$NON-NLS-1$
            DcsTargetResolver.TargetKind.FORM);
    }

    @Test
    public void testUnsupportedRootFailureNamesValueAndAllFixShapes()
    {
        DcsTargetResolver.RootClassification classification = classify("Catalog.Products"); //$NON-NLS-1$

        assertFalse(classification.isSuccess());
        assertEquals(DcsTargetResolver.FailureCode.UNSUPPORTED_ROOT, classification.failure.code());
        assertEquals("Catalog.Products", classification.failure.fqn()); //$NON-NLS-1$
        assertTrue(classification.failure.message(),
            classification.failure.message().contains("Catalog.Products")); //$NON-NLS-1$
        assertTrue(classification.failure.message(), classification.failure.message().contains("Report.<Name>")); //$NON-NLS-1$
        assertTrue(classification.failure.message(),
            classification.failure.message().contains("ExternalReport.<Name>")); //$NON-NLS-1$
        assertTrue(classification.failure.message(),
            classification.failure.message().contains("CommonTemplate.<Name>")); //$NON-NLS-1$
        assertTrue(classification.failure.message(),
            classification.failure.message().contains("Attribute.<Name>")); //$NON-NLS-1$
    }

    @Test
    public void testExternalDataProcessorMainRootRemainsUnsupportedAndNamesOwnedTemplateAlternative()
    {
        DcsTargetResolver.RootClassification classification =
            classify("ExternalDataProcessor.ExtProc"); //$NON-NLS-1$

        assertFalse(classification.isSuccess());
        assertEquals(DcsTargetResolver.FailureCode.UNSUPPORTED_ROOT, classification.failure.code());
        assertTrue(classification.failure.message(),
            classification.failure.message().contains("ExternalDataProcessor.ExtProc")); //$NON-NLS-1$
        assertTrue(classification.failure.message(), classification.failure.message()
            .contains("ExternalDataProcessor.<Name>.Template.<Name>")); //$NON-NLS-1$
        assertTrue(classification.failure.message(),
            classification.failure.message().contains("no main DCS")); //$NON-NLS-1$
    }

    @Test
    public void testDcsRootsRequireOwnerAndContentExports()
    {
        assertEquals(Arrays.asList(DcsTargetResolver.ExportRole.OWNER_TOP_OBJECT,
            DcsTargetResolver.ExportRole.DCS_CONTENT),
            DcsTargetResolver.requiredExportRoles(DcsTargetResolver.TargetKind.REPORT_MAIN_DCS));
        assertEquals(Arrays.asList(DcsTargetResolver.ExportRole.OWNER_TOP_OBJECT,
            DcsTargetResolver.ExportRole.DCS_CONTENT),
            DcsTargetResolver.requiredExportRoles(DcsTargetResolver.TargetKind.COMMON_TEMPLATE));
        assertEquals(Arrays.asList(DcsTargetResolver.ExportRole.OWNER_TOP_OBJECT,
            DcsTargetResolver.ExportRole.DCS_CONTENT),
            DcsTargetResolver.requiredExportRoles(DcsTargetResolver.TargetKind.OWNED_TEMPLATE));
    }

    @Test
    public void testDynamicListRequiresFormAndSeparateSettingsExports()
    {
        assertEquals(Arrays.asList(DcsTargetResolver.ExportRole.FORM_CONTENT,
            DcsTargetResolver.ExportRole.DYNAMIC_LIST_SETTINGS),
            DcsTargetResolver.requiredExportRoles(DcsTargetResolver.TargetKind.DYNAMIC_LIST));
    }

    @Test
    public void testNonDcsTemplateErrorNamesFqnActualTypeAndFix()
    {
        String message = DcsTargetResolver.nonDcsTemplateMessage(
            "Report.Sales.Template.Print", "SpreadsheetDocument"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(message, message.contains("Report.Sales.Template.Print")); //$NON-NLS-1$
        assertTrue(message, message.contains("SpreadsheetDocument")); //$NON-NLS-1$
        assertTrue(message, message.contains("DataCompositionSchema")); //$NON-NLS-1$
        assertTrue(message, message.contains("DATA_COMPOSITION_SCHEMA")); //$NON-NLS-1$
    }

    @Test
    public void testNotDynamicListErrorNamesFqnActualTypeAndFix()
    {
        String message = DcsTargetResolver.notDynamicListMessage(
            "Catalog.Products.Form.ListForm.Attribute.List", "StringFieldExtInfo"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(message, message.contains("Catalog.Products.Form.ListForm.Attribute.List")); //$NON-NLS-1$
        assertTrue(message, message.contains("StringFieldExtInfo")); //$NON-NLS-1$
        assertTrue(message, message.contains("DynamicListExtInfo")); //$NON-NLS-1$
    }

    private static void assertKind(String raw, DcsTargetResolver.TargetKind expected)
    {
        DcsTargetResolver.RootClassification classification = classify(raw);
        assertTrue(classification.failure == null ? "Expected a supported root: " + raw //$NON-NLS-1$
            : classification.failure.message(), classification.isSuccess());
        assertEquals(expected, classification.kind);
    }

    private static DcsTargetResolver.RootClassification classify(String raw)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(raw);
        assertTrue(parsed.failure() == null ? "Expected a valid address: " + raw //$NON-NLS-1$
            : parsed.failure().message(), parsed.isSuccess());
        return DcsTargetResolver.classifyRoot(parsed.address());
    }
}
