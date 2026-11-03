/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.FormEditContextTestAccess;
import com.ditrix.edt.mcp.server.utils.MetadataDeleteBatch;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Exercises real form resolvers/removal through a copy-on-commit stand-in BM boundary. */
public class DeleteMetadataBatchTest
{
    private static final String PREFIX = "Catalog.Catalog.Form.ItemForm.";

    private static Map<String, String> params(String... members)
    {
        List<Map<String, String>> operations = new ArrayList<>();
        for (String member : members)
        {
            operations.add(Map.of("action", "delete", "fqn", PREFIX + member));
        }
        return Map.of("projectName", "TestConfiguration", "operations", GsonProvider.toJson(operations));
    }

    private static JsonObject json(String result)
    {
        return JsonParser.parseString(result).getAsJsonObject();
    }

    private static Form form()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        for (String name : List.of("A", "B", "C"))
        {
            FormCommand command = FormFactory.eINSTANCE.createFormCommand();
            command.setName(name);
            form.getFormCommands().add(command);
        }
        return form;
    }

    @Test
    public void unknownFieldsAreAggregatedBeforeUiOrProjectAccess()
    {
        Map<String, String> args = Map.of("projectName", "NoProject", "typo", "true", "operations",
            "[{\"action\":\"delete\",\"fqn\":\"" + PREFIX + "Command.A\",\"oops\":1},"
            + "{\"action\":\"modify\",\"fqn\":\"" + PREFIX + "Command.B\"}]");
        JsonObject result = json(new DeleteMetadataTool().execute(args));
        assertFalse(result.get("success").getAsBoolean());
        assertEquals(3, result.getAsJsonArray("errors").size());
        assertTrue(result.toString().contains("typo"));
        assertTrue(result.toString().contains("oops"));
        assertEquals(0, result.getAsJsonArray("applied").size());
        assertFalse(result.get("committed").getAsBoolean());
        assertFalse(result.has("mutationCommitted"));
    }

    @Test
    public void malformedItemsAreNeverSilentlyDropped()
    {
        for (String input : List.of("null", "{}", "[]", "[null, 5]", "broken"))
        {
            JsonObject result = json(new DeleteMetadataTool().execute(Map.of("projectName", "P", "operations", input)));
            assertFalse(input, result.get("success").getAsBoolean());
            assertEquals("unchanged", result.get("persistenceState").getAsString());
        }
    }

    @Test
    public void unsupportedAtomicityAndCrossFormTargetsAreRefused()
    {
        for (Map<String, String> extra : List.of(Map.of("atomic", "false"), Map.of("force", "true"),
            Map.of("timeout", "600"), Map.of("fqn", "Catalog.Catalog"), Map.of("confirm", "perhaps")))
        {
            Map<String, String> args = new java.util.LinkedHashMap<>(params("Command.A"));
            args.putAll(extra);
            assertFalse(json(new DeleteMetadataTool().execute(args)).get("success").getAsBoolean());
        }
        MetadataDeleteBatch batch = MetadataDeleteBatch.parse(Map.of("projectName", "P", "operations",
            "[{\"action\":\"delete\",\"fqn\":\"CommonForm.One.Command.A\"},"
            + "{\"action\":\"delete\",\"fqn\":\"CommonForm.Two.Command.B\"}]"));
        assertEquals("unsupportedBatch", batch.errors.get(0).get("reason"));
        assertFalse(MetadataDeleteBatch.parse(params("Attribute.A", "Attribute.A.Column.B")).errors.isEmpty());
    }

    @Test
    public void twoIndependentMissingMembersAreReportedTogetherWithoutMutation()
    {
        Form form = form();
        MetadataDeleteBatch batch = MetadataDeleteBatch.parse(params("Command.TypoOne", "Command.TypoTwo"));
        DeleteMetadataTool.formBatchPreflight(form, batch, null);
        assertEquals(2, batch.errors.size());
        assertTrue(batch.errors.get(0).get("error").toString().contains("TypoOne"));
        assertTrue(batch.errors.get(1).get("error").toString().contains("TypoTwo"));
        assertEquals(3, form.getFormCommands().size());
    }

    @Test
    public void bilingualTokensResolveTheSameProgrammaticNameAndDuplicatesAreRejected()
    {
        Form form = form();
        form.getFormCommands().get(0).setName("Команда");
        Map<String, String> args = Map.of("projectName", "P", "operations", GsonProvider.toJson(List.of(
            Map.of("action", "delete", "fqn", "Catalog.Catalog.Form.ItemForm.Command.Команда"),
            Map.of("action", "delete", "fqn", "Справочник.Catalog.Форма.ItemForm.Команда.Команда"))));
        MetadataDeleteBatch batch = MetadataDeleteBatch.parse(args);
        assertTrue(batch.errors.toString(), batch.errors.isEmpty());
        List<Map<String, Object>> preview = DeleteMetadataTool.formBatchPreflight(form, batch, null);
        assertEquals(2, preview.size());
        assertEquals("dependsOnPriorOperation", batch.errors.get(0).get("reason"));
        assertEquals(3, form.getFormCommands().size());
    }

    @Test
    public void containingTargetsAndTheirDescendantsCannotBeDeletedTwice()
    {
        Form form = form();
        FormGroup outer = FormFactory.eINSTANCE.createFormGroup();
        outer.setName("Outer");
        FormGroup inner = FormFactory.eINSTANCE.createFormGroup();
        inner.setName("Inner");
        outer.getItems().add(inner);
        form.getItems().add(outer);
        MetadataDeleteBatch batch = MetadataDeleteBatch.parse(params("Group.Outer", "Group.Inner"));
        DeleteMetadataTool.formBatchPreflight(form, batch, null);
        assertEquals("dependsOnPriorOperation", batch.errors.get(0).get("reason"));
        assertSame(outer, inner.eContainer());
    }

    private static final class Boundary
    {
        final AtomicReference<Form> committed = new AtomicReference<>(form());
        final AtomicReference<Form> working = new AtomicReference<>();
        final IProject project = mock(IProject.class);
        final IBmModel model = mock(IBmModel.class);

        Boundary(boolean failThird)
        {
            when(project.getName()).thenReturn("TestConfiguration");
            when(model.execute(any())).thenAnswer(inv -> {
                Form copy = EcoreUtil.copy(committed.get());
                Form view = mock(Form.class, withSettings().extraInterfaces(IBmObject.class)
                    .defaultAnswer(call -> call.getMethod().invoke(copy, call.getArguments())));
                doReturn("Catalog.Catalog.Form.ItemForm.Form").when((IBmObject)view).bmGetFqn();
                working.set(copy);
                if (failThird)
                {
                    copy.eAdapters().add(new AdapterImpl() {
                        @Override
                        public void notifyChanged(Notification event)
                        {
                            if (event.getEventType() == Notification.REMOVE && event.getOldValue() instanceof FormCommand
                                && "C".equals(((FormCommand)event.getOldValue()).getName()))
                            {
                                throw new IllegalStateException("controlled third-operation failure");
                            }
                        }
                    });
                }
                CatalogForm md = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
                when(md.getForm()).thenReturn(view);
                IBmTransaction tx = mock(IBmTransaction.class);
                when(tx.getObjectById(7L)).thenReturn((IBmObject)md);
                Object result = ((IBmTask<?>)inv.getArgument(0)).execute(tx, null);
                committed.set(copy);
                return result;
            });
        }

        String run(MetadataDeleteBatch batch, WriteScope scope)
        {
            List<Map<String, Object>> preview = DeleteMetadataTool.formBatchPreflight(committed.get(), batch, null);
            String[] result = new String[1];
            WriteScope.runWithScope(scope, () -> result[0] = new DeleteMetadataTool().performFormBatch(
                FormEditContextTestAccess.of(project, model, MdClassFactory.eINSTANCE.createCatalogForm(), 7L),
                batch, null, preview));
            return result[0];
        }
    }

    @Test
    public void thirdOperationFailureEscapesOneWriteBoundaryAndCommitsNothing()
    {
        Boundary boundary = new Boundary(true);
        Form before = boundary.committed.get();
        WriteScope scope = new WriteScope();
        JsonObject result = json(boundary.run(MetadataDeleteBatch.parse(params("Command.A", "Command.B", "Command.C")), scope));
        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.toString(), result.has("failedOperationIndex"));
        assertEquals(2, result.get("failedOperationIndex").getAsInt());
        assertTrue(result.get("rollbackPerformed").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("controlled third-operation failure"));
        assertEquals(0, result.getAsJsonArray("applied").size());
        assertEquals(0, boundary.working.get().getFormCommands().size());
        assertSame(before, boundary.committed.get());
        assertEquals(3, before.getFormCommands().size());
        assertFalse(scope.hasRecordedWrite());
        assertTrue(scope.exportedTopObjects("TestConfiguration").isEmpty());
        verify(boundary.model, times(1)).execute(any());
    }

    @Test
    public void successfulBatchUsesOneWriteAndOneFormExport()
    {
        Boundary boundary = new Boundary(false);
        WriteScope scope = new WriteScope();
        JsonObject result = json(boundary.run(MetadataDeleteBatch.parse(params("Command.A", "Command.B")), scope));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("[0,1]", result.get("applied").toString());
        assertTrue(result.get("committed").getAsBoolean());
        assertEquals("C", boundary.committed.get().getFormCommands().get(0).getName());
        assertEquals(1, boundary.committed.get().getFormCommands().size());
        assertTrue(scope.hasRecordedWrite());
        assertEquals(List.of("Catalog.Catalog.Form.ItemForm.Form"), scope.exportedTopObjects("TestConfiguration"));
        verify(boundary.model, times(1)).execute(any());
    }

    @Test
    public void consentRejectionCannotRunBatchMutation()
    {
        boolean[] wrote = {false};
        DeleteMetadataTool tool = new DeleteMetadataTool((name, preview) -> DestructiveConsentGate.ConsentDecision.REJECT);
        String result = tool.deleteWithConsent(new com.ditrix.edt.mcp.server.utils.ConsentPreview(
            "Delete form members", "batch", 2, List.of(PREFIX + "Command.A", PREFIX + "Command.B")), () -> {
                wrote[0] = true;
                return ToolResult.success().toJson();
            });
        assertFalse(wrote[0]);
        assertFalse(json(result).get("success").getAsBoolean());
    }

    @Test
    public void changedScopeAfterConsentCommitsNothing()
    {
        Boundary boundary = new Boundary(false);
        MetadataDeleteBatch batch = MetadataDeleteBatch.parse(params("Command.A", "Command.B"));
        List<Map<String, Object>> authorized = DeleteMetadataTool.formBatchPreflight(boundary.committed.get(), batch, null);
        boundary.committed.get().getFormCommands().get(1).setName("Renamed");
        Form before = boundary.committed.get();
        WriteScope scope = new WriteScope();
        String[] result = new String[1];
        WriteScope.runWithScope(scope, () -> result[0] = new DeleteMetadataTool().performFormBatch(
            FormEditContextTestAccess.of(boundary.project, boundary.model, MdClassFactory.eINSTANCE.createCatalogForm(), 7L),
            batch, null, authorized));
        assertFalse(json(result[0]).get("success").getAsBoolean());
        assertEquals(0, json(result[0]).getAsJsonArray("applied").size());
        assertSame(before, boundary.committed.get());
        assertEquals(3, before.getFormCommands().size());
        assertFalse(scope.hasRecordedWrite());
    }

    private static final class PendingExportTool extends DeleteMetadataTool
    {
        @Override
        protected IExportEnvironment exportEnvironment()
        {
            return (projectName, timeoutMs) -> com.ditrix.edt.mcp.server.utils.BuildUtils.DiskExportState.PENDING;
        }

        String await(Map<String, String> params, String result, WriteScope scope)
        {
            return awaitDiskExport(params, result, scope);
        }
    }

    @Test
    public void exportRefusalPreservesCommittedIndexesAndRetryAdvice()
    {
        Boundary boundary = new Boundary(false);
        WriteScope scope = new WriteScope();
        Map<String, String> args = params("Command.A", "Command.B");
        String result = boundary.run(MetadataDeleteBatch.parse(args), scope);
        JsonObject refused = json(new PendingExportTool().await(args, result, scope));
        assertFalse(refused.get("success").getAsBoolean());
        assertTrue(refused.toString(), refused.has("mutationCommitted"));
        assertTrue(refused.get("mutationCommitted").getAsBoolean());
        assertTrue(refused.get("committed").getAsBoolean());
        assertEquals("[0,1]", refused.get("applied").toString());
        assertFalse(refused.get("rollbackPerformed").getAsBoolean());
        assertTrue(refused.get("safeRetry").getAsString().contains("Do not repeat"));
        assertEquals("unconfirmed", refused.get("persistenceState").getAsString());
    }

    @Test
    public void batchOptsIntoSharedMarkersWithoutChangingSingleItemOptIn()
    {
        DeleteMetadataTool tool = new DeleteMetadataTool();
        assertTrue(tool.reportsWrittenObjectMarkers(params("Command.A")));
        assertFalse(tool.reportsWrittenObjectMarkers(Map.of("projectName", "P", "fqn", PREFIX + "Command.A")));
        assertTrue(json(tool.getOutputSchema()).getAsJsonObject("properties").has("markersIncomplete"));
    }
}
