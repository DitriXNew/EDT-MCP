// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.form.refactoring.IFormRefactoringService;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.tools.impl.DeleteMetadataTool;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.HeadlessReadFixture;
import com.ditrix.edt.mcp.server.utils.ReadBoundaryAssertions;

/** Native refactoring initialization may build the workspace and must enter with no BM task. */
@RunWith(Parameterized.class)
public class RefactoringEntryBoundaryTest
{
    @Parameterized.Parameters(name = "{0}")
    public static Object[] hosts() { return HostKind.values(); }

    @Parameterized.Parameter public HostKind host;

    private void selectHost()
    {
        System.setProperty("eclipse.application", host == HostKind.HEADLESS
            ? HostKind.APPLICATION_ID : "org.eclipse.ui.ide.workbench");
    }

    @Test public void metadataRenameEntersOutsideReadAndContainsRemovedTarget() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            selectHost();
            var service = mock(IMdRefactoringService.class);
            int[] entries = {0};
            when(service.createMdObjectRenameRefactoring(any(), anyString())).thenAnswer(call -> {
                assertEquals("Metadata rename entered with an open BM transaction", 0, fixture.depth);
                entries[0]++;
                throw new IllegalStateException("Target removed after preparation");
            });
            String result = new MetadataRenameService().prepareMdClassRename(fixture.project(),
                "CommonModule.EnglishName", "Renamed", fixture.config.getCommonModules().get(0),
                false, null, null, 0, null, service, BmModelResolver.resolve(fixture.project()));
            assertEquals(1, entries[0]);
            assertTrue(result, result.contains("Nothing was renamed; no cascade started"));
            assertTrue(result, result.contains("CommonModule.EnglishName"));
            assertTrue(result, result.contains("get_metadata_details"));
            verify(fixture.model, never()).execute(any());
        }
    }

    @Test public void deleteEntersOutsideReadAndContainsRemovedTarget() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            selectHost();
            var service = mock(IMdRefactoringService.class);
            int[] entries = {0};
            when(service.createMdObjectDeleteRefactoring(any())).thenAnswer(call -> {
                assertEquals("Delete entered with an open BM transaction", 0, fixture.depth);
                entries[0]++;
                throw new IllegalStateException("Target removed after preparation");
            });
            Method prepare = DeleteMetadataTool.class.getDeclaredMethod("prepareMdClassDelete",
                org.eclipse.core.resources.IProject.class, String.class,
                com._1c.g5.v8.dt.metadata.mdclass.MdObject.class, String.class, boolean.class,
                boolean.class, IMdRefactoringService.class, BmModelResolver.Resolution.class);
            String result = (String)invoke(prepare, new DeleteMetadataTool(), fixture.project(),
                "CommonModule.EnglishName", fixture.config.getCommonModules().get(0), "Configuration",
                false, false, service, BmModelResolver.resolve(fixture.project()));
            assertEquals(1, entries[0]);
            assertTrue(result, result.contains("Nothing was deleted"));
            assertTrue(result, result.contains("CommonModule.EnglishName"));
            assertTrue(result, result.contains("get_metadata_details"));
            verify(fixture.model, never()).execute(any());
        }
    }

    @Test public void formRenameEntersOutsideReadAndContainsRemovedTarget() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            selectHost();
            var form = FormFactory.eINSTANCE.createForm();
            var group = FormFactory.eINSTANCE.createFormGroup();
            group.setName("Before");
            form.getItems().add(group);
            var mdForm = MdClassFactory.eINSTANCE.createCommonForm();
            mdForm.setName("BoundaryForm");
            mdForm.setForm(form);
            fixture.config.getCommonForms().add(mdForm);
            var transaction = mock(IBmTransaction.class);
            when(transaction.getObjectById(anyLong())).thenReturn((IBmObject)mdForm);
            doAnswer(call -> {
                IBmTask<?> task = call.getArgument(0);
                fixture.depth++;
                Object value;
                try
                {
                    value = task.execute(transaction, null);
                    ReadBoundaryAssertions.assertPreparationResult(task.getName(), value);
                }
                finally { fixture.depth--; }
                if ("Prepare form element rename".equals(task.getName())) form.getItems().remove(group);
                return value;
            }).when(fixture.model).executeReadonlyTask(any());
            var service = mock(IFormRefactoringService.class);
            when(Activator.getDefault().getFormRefactoringService()).thenReturn(service);
            int[] entries = {0};
            when(service.createFormRenameRefactoring(group, "After")).thenAnswer(call -> {
                assertEquals("Form rename entered with an open BM transaction", 0, fixture.depth);
                entries[0]++;
                assertNull("The target was removed after its preparation read", group.eContainer());
                throw new IllegalStateException("Target removed after preparation");
            });
            Method rename = MetadataRenameService.class.getDeclaredMethod("renameFormMember",
                org.eclipse.core.resources.IProject.class,
                com._1c.g5.v8.dt.metadata.mdclass.Configuration.class, String.class, String.class,
                boolean.class, DisableRequest.class, String.class, int.class,
                RenameProgress.class, FormElementWriter.FormMemberRef.class);
            String fqn = "CommonForm.BoundaryForm.Group.Before";
            String result = (String)invoke(rename, new MetadataRenameService(), fixture.project(),
                fixture.config, fqn, "After", false, null, null, 0, null, FormElementWriter.parse(fqn));
            assertEquals(1, entries[0]);
            assertTrue(result, result.contains(fqn));
            assertTrue(result, result.contains("Nothing was renamed"));
            assertTrue(result, result.contains("get_metadata_details"));
            assertEquals("Before", group.getName());
            verify(fixture.model, never()).execute(any());
        }
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception
    {
        method.setAccessible(true);
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure)
        {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }
}
