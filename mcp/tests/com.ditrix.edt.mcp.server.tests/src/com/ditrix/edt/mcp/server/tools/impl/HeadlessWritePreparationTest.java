// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.HeadlessReadFixture;

public class HeadlessWritePreparationTest
{
    @Test public void unavailableHeadlessModelIsLoggedAsAnExpectedRefusal() throws Exception
    {
        var records = new java.util.ArrayList<org.eclipse.core.runtime.IStatus>();
        org.eclipse.core.runtime.ILogListener listener = (status, plugin) -> {
            if (status.getMessage().startsWith("Error in modify_metadata")) records.add(status);
        };
        org.eclipse.core.runtime.Platform.addLogListener(listener);
        try (var fixture = new HeadlessReadFixture())
        {
            org.mockito.Mockito.when(com.ditrix.edt.mcp.server.Activator.getDefault().getBmModelManager())
                .thenReturn(null);
            String result = fixture.call(new ModifyMetadataTool(), Map.of("fqn", "CommonModule.EnglishName",
                "properties", "[{\"name\":\"comment\",\"value\":\"changed\"}]"));
            assertTrue(result, result.contains("BM model is unavailable"));
            assertEquals(1, records.size());
            assertEquals(org.eclipse.core.runtime.IStatus.INFO, records.get(0).getSeverity());
            assertNull(records.get(0).getException());
        }
        finally { org.eclipse.core.runtime.Platform.removeLogListener(listener); }
    }

    @Test public void createContinuationStartsOnlyAfterPreparationReadEnds() throws Exception
    {
        class Probe extends CreateMetadataTool
        {
            String run(HeadlessReadFixture fixture)
            {
                return prepareWrite(fixture.project(), () -> {
                    assertTrue("Preparation was not in a read", fixture.depth > 0);
                    String name = fixture.config.getCommonModules().get(0).getName();
                    return () -> {
                        assertEquals("Writer entered while the read was active", 0, fixture.depth);
                        return name;
                    };
                });
            }
        }
        try (var fixture = new HeadlessReadFixture())
        {
            assertEquals("EnglishName", new Probe().run(fixture));
        }
    }

    @Test public void adoptionVerifiesEverySubmittedTopRegistration() throws Exception
    {
        class AdoptProbe extends AdoptMetadataObjectTool
        {
            @Override protected IExportEnvironment exportEnvironment()
            {
                return (project, timeout) -> com.ditrix.edt.mcp.server.utils.BuildUtils.DiskExportState.DRAINED;
            }
            String finish(String result, com.ditrix.edt.mcp.server.tools.base.WriteScope scope)
            {
                return awaitDiskExport(Map.of(), result, scope);
            }
        }
        try (var fixture = new HeadlessReadFixture())
        {
            var path = fixture.project().getLocation().toFile().toPath()
                .resolve("src/Configuration/Configuration.mdo");
            java.nio.file.Files.createDirectories(path.getParent());
            String xml = "<Configuration><commonModules>CommonModule.EnglishName</commonModules>";
            java.nio.file.Files.writeString(path, xml + "</Configuration>");
            var scope = new com.ditrix.edt.mcp.server.tools.base.WriteScope();
            com.ditrix.edt.mcp.server.tools.base.WriteScope.runWithScope(scope, () ->
                com.ditrix.edt.mcp.server.tools.base.WriteScope.recordExportSubmission(fixture.project(),
                    java.util.List.of("Configuration", "CommonModule.EnglishName", "CommonModule.Код")));
            String success = com.ditrix.edt.mcp.server.protocol.ToolResult.success().put("action", "adopted")
                .put("fqn", "CommonModule.EnglishName").put("extensionProject", fixture.name()).toJson();
            String result = new AdoptProbe().finish(success, scope);
            assertTrue(result, com.ditrix.edt.mcp.server.protocol.ToolResult.isErrorPayload(result));
            assertTrue(result, result.contains("mutationCommitted"));
            assertTrue(result, result.contains("CommonModule.Код"));
            java.nio.file.Files.writeString(path, xml
                + "<commonModules>CommonModule.Код</commonModules></Configuration>");
            assertFalse(new AdoptProbe().finish(success, scope), com.ditrix.edt.mcp.server.protocol.ToolResult
                .isErrorPayload(new AdoptProbe().finish(success, scope)));
        }
    }

    @Test public void modifyCapturesIdentityInReadAndReResolvesAfterReadEnds() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            int[] writes = {0};
            var preflightConfig = org.mockito.Mockito.mock(com._1c.g5.v8.dt.metadata.mdclass.Configuration.class,
                org.mockito.Mockito.withSettings().defaultAnswer(call -> {
                    if (call.getMethod().getName().equals("getSynonym"))
                        assertEquals("Preflight configuration escaped into the write", 0, writes[0]);
                    return call.getMethod().invoke(fixture.config, call.getArguments());
                }));
            var configurations = com.ditrix.edt.mcp.server.Activator.getDefault().getConfigurationProvider();
            org.mockito.Mockito.doAnswer(call -> {
                    assertTrue(fixture.depth > 0);
                    return preflightConfig;
                }).when(configurations).getConfiguration(fixture.project());
            var stored = fixture.config.getCommonModules().get(0);
            var module = org.mockito.Mockito.mock(com._1c.g5.v8.dt.metadata.mdclass.CommonModule.class,
                org.mockito.Mockito.withSettings().extraInterfaces(com._1c.g5.v8.bm.core.IBmObject.class,
                    org.eclipse.emf.ecore.InternalEObject.class)
                    .defaultAnswer(call -> {
                        if (call.getMethod().getName().equals("bmGetId"))
                        {
                            assertTrue("Identity escaped the read boundary", fixture.depth > 0); return 17L;
                        }
                        if (call.getMethod().getName().equals("bmIsTop")) return true;
                        if (call.getMethod().getName().equals("bmGetFqn")) return "CommonModule.EnglishName";
                        return call.getMethod().invoke(stored, call.getArguments());
                    }));
            fixture.config.getCommonModules().set(0, module);
            var bm = (com._1c.g5.v8.bm.core.IBmObject)module;
            var tx = org.mockito.Mockito.mock(com._1c.g5.v8.bm.core.IBmTransaction.class);
            org.mockito.Mockito.when(tx.getObjectById(17L)).thenReturn(bm);
            org.mockito.Mockito.when(tx.getTopObjectByFqn("Configuration"))
                .thenReturn((com._1c.g5.v8.bm.core.IBmObject)fixture.config);
            org.mockito.Mockito.when(fixture.model.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                assertEquals("Write was nested in a read transaction", 0, fixture.depth);
                writes[0]++; fixture.depth++;
                try { return ((com._1c.g5.v8.bm.integration.IBmTask<?>)call.getArgument(0)).execute(tx, null); }
                finally { fixture.depth--; }
            });
            String result = invoke(new ModifyMetadataTool(), Map.of("projectName", fixture.name(),
                "fqn", "CommonModule.EnglishName", "properties", "[{\"name\":\"comment\",\"value\":\"changed\"}]"));
            assertTrue(result, result.contains("\"action\":\"modified\""));
            assertEquals("changed", module.getComment());
            assertEquals(1, writes[0]);
            org.mockito.Mockito.verify(tx).getObjectById(17L);
            org.mockito.Mockito.verify(tx).getTopObjectByFqn("Configuration");
        }
    }

    @Test public void createMustVerifyTopRegistrationAfterDrain() throws Exception
    {
        class CreateProbe extends CreateMetadataTool
        {
            String finish(Map<String, String> params, String result, boolean drained)
            {
                return refreshAfterExportAwait(params, result, drained);
            }
        }
        try (var fixture = new HeadlessReadFixture())
        {
            var path = fixture.project().getLocation().toFile().toPath()
                .resolve("src/Configuration/Configuration.mdo");
            java.nio.file.Files.createDirectories(path.getParent());
            for (String name : new String[] { "EnglishName", "Код" })
            {
                java.nio.file.Files.writeString(path, "<Configuration/>");
                String success = com.ditrix.edt.mcp.server.protocol.ToolResult.success()
                    .put("action", "created").put("fqn", "CommonModule." + name).toJson();
                String result = new CreateProbe().finish(Map.of("projectName", fixture.name()), success, true);
                assertTrue(result, com.ditrix.edt.mcp.server.protocol.ToolResult.isErrorPayload(result));
                assertTrue(result, result.contains("mutationCommitted"));
                java.nio.file.Files.writeString(path, "<Configuration><commonModules>CommonModule."
                    + name + "</commonModules></Configuration>");
                assertEquals(success, new CreateProbe().finish(Map.of("projectName", fixture.name()), success, true));
            }
        }
    }

    @Test public void createPreflightChecksDuplicateNamesAndLanguageInsideBm() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            for (String fqn : new String[] { "CommonModule.EnglishName", "ОбщийМодуль.Код" })
            {
                String result = invoke(new CreateMetadataTool(), Map.of("projectName", fixture.name(),
                    "fqn", fqn, "properties", "[{\"name\":\"synonym\",\"value\":\"Translated\"}]"));
                assertTrue(result, result.contains("already exists"));
            }
            assertTrue(fixture.reads > 0);
        }
    }

    @Test public void deletePreflightResolvesNamesInsideBm() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            org.mockito.Mockito.when(com.ditrix.edt.mcp.server.Activator.getDefault().getMdRefactoringService())
                .thenReturn(org.mockito.Mockito.mock(com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService.class));
            for (String fqn : new String[] { "CommonModule.Missing", "ОбщийМодуль.Нет" })
            {
                String result = invoke(new DeleteMetadataTool(), Map.of("projectName", fixture.name(), "fqn", fqn));
                assertTrue(result, result.contains("Node not found"));
            }
        }
    }

    @Test public void modifyPreflightReadsNamesInsideBm() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            for (String fqn : new String[] { "CommonModule.Missing", "ОбщийМодуль.Нет" })
            {
                String result = invoke(new ModifyMetadataTool(), Map.of("projectName", fixture.name(),
                    "fqn", fqn, "properties", "[{\"name\":\"comment\",\"value\":\"test\"}]"));
                assertTrue(result, result.contains("Node not found"));
            }
            assertTrue(fixture.reads > 0);
        }
    }

    static String invoke(AbstractMetadataWriteTool tool, Map<String, String> params) throws Exception
    {
        var method = tool.getClass().getDeclaredMethod("executeOnUiThread", Map.class);
        method.setAccessible(true);
        try { return (String)method.invoke(tool, params); }
        catch (java.lang.reflect.InvocationTargetException e)
        {
            if (e.getCause() instanceof Error error) throw error;
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }
}
