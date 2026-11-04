// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class McpCoreStartupTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void sharedStartupKeepsImportHistoryIpcOrder() {
        List<String> steps = new ArrayList<>();
        new McpCoreStartup(name -> null) {
            @Override void importConfiguredProjects() { steps.add("import"); }
            @Override void initializeHistory() { steps.add("history"); }
            @Override void startIpc() { steps.add("ipc"); }
        }.start();
        assertEquals(List.of("import", "history", "ipc"), steps);
    }

    @Test public void importCreatesExternalProjectAndOpensIt() throws Exception {
        Path directory = temporary.newFolder().toPath(); Files.writeString(directory.resolve(".project"), "descriptor");
        IWorkspace workspace = mock(IWorkspace.class); IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class); IProjectDescription description = mock(IProjectDescription.class);
        when(workspace.getRoot()).thenReturn(root); when(description.getName()).thenReturn("Fixture");
        when(workspace.loadProjectDescription(any(IPath.class))).thenReturn(description); when(root.getProject("Fixture")).thenReturn(project);
        new McpCoreStartup(name -> null).importProject(workspace, directory.toString(), null);
        var order = inOrder(description, project);
        order.verify(description).setLocation(IPath.fromOSString(directory.toString()));
        order.verify(project).create(description, null); order.verify(project).open(null);
    }

    @Test public void existingProjectIsReopenedWithoutReimportOrLocationChange() throws Exception {
        Path directory = temporary.newFolder().toPath(); Files.writeString(directory.resolve(".project"), "descriptor");
        IWorkspace workspace = mock(IWorkspace.class); IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        IProject project = mock(IProject.class); IProjectDescription description = mock(IProjectDescription.class);
        when(workspace.getRoot()).thenReturn(root); when(description.getName()).thenReturn("Fixture");
        when(workspace.loadProjectDescription(any(IPath.class))).thenReturn(description); when(root.getProject("Fixture")).thenReturn(project);
        when(project.exists()).thenReturn(true);
        new McpCoreStartup(name -> null).importProject(workspace, directory.toString(), null);
        verify(project).open(null); verify(project, never()).create(any(IProjectDescription.class), any());
        verify(description, never()).setLocation(any());
    }

    @Test public void unsetImportNeverResolvesWorkspace() {
        new McpCoreStartup(name -> null) {
            @Override IWorkspace workspace() { fail("No opt-in import"); return null; }
        }.importConfiguredProjects();
    }

    @Test public void failedProjectImportDoesNotSkipTheNextProject() throws Exception {
        Path first = temporary.newFolder().toPath(), second = temporary.newFolder().toPath();
        Files.writeString(first.resolve(".project"), "first"); Files.writeString(second.resolve(".project"), "second");
        IWorkspace workspace = mock(IWorkspace.class); IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(workspace.getRoot()).thenReturn(root);
        doAnswer(call -> { ((IWorkspaceRunnable) call.getArgument(0)).run(null); return null; })
            .when(workspace).run(any(IWorkspaceRunnable.class), eq(root), eq(IWorkspace.AVOID_UPDATE), isNull());
        when(workspace.loadProjectDescription(any(IPath.class))).thenThrow(new CoreException(Status.error("bad descriptor")));
        new McpCoreStartup(name -> first + java.io.File.pathSeparator + second) {
            @Override IWorkspace workspace() { return workspace; }
        }.importConfiguredProjects();
        verify(workspace).loadProjectDescription(IPath.fromOSString(first.toString()).append(".project"));
        verify(workspace).loadProjectDescription(IPath.fromOSString(second.toString()).append(".project"));
    }

    @Test public void stdioOnlyOverridesPreferenceAndEnvironmentHttpAutostart() {
        org.eclipse.jface.preference.IPreferenceStore store = org.mockito.Mockito.mock(org.eclipse.jface.preference.IPreferenceStore.class);
        org.mockito.Mockito.when(store.getBoolean(com.ditrix.edt.mcp.server.preferences.PreferenceConstants.PREF_HTTP_ENABLED)).thenReturn(true);
        org.mockito.Mockito.when(store.getBoolean(com.ditrix.edt.mcp.server.preferences.PreferenceConstants.PREF_AUTO_START)).thenReturn(true);
        assertFalse(McpServerStartup.shouldStartHttp(store, "true", true));
        assertTrue(McpServerStartup.shouldStartHttp(store, null, false));
    }

    @Test public void platformFailureLogsTheOriginalStackWithoutWorkbench() throws Exception {
        Throwable expected = new IllegalStateException("headless-log-sentinel");
        AtomicReference<IStatus> logged = new AtomicReference<>();
        ILogListener listener = (status, plugin) -> { if (status.getException() == expected) { logged.set(status); } };
        Platform.addLogListener(listener);
        try { new StdioHostPlatform().failure("headless failure", expected); }
        finally { Platform.removeLogListener(listener); }
        assertNotNull(logged.get()); assertSame(expected, logged.get().getException());
        assertEquals(IStatus.ERROR, logged.get().getSeverity());
        logged.set(null);
        Platform.addLogListener(listener);
        try { com.ditrix.edt.mcp.server.utils.Log.warning("headless warning", expected); }
        finally { Platform.removeLogListener(listener); }
        assertNotNull(logged.get()); assertSame(expected, logged.get().getException());
        assertEquals(IStatus.WARNING, logged.get().getSeverity());
    }

    @Test public void applicationIsRegisteredAndCoreClassesReferenceNoWorkbenchOrSwt() throws Exception {
        var extension = Platform.getExtensionRegistry().getExtension("org.eclipse.core.runtime.applications", HostKind.APPLICATION_ID);
        assertNotNull(extension);
        var application = extension.getConfigurationElements()[0];
        assertEquals("false", application.getAttribute("visible"));
        assertEquals(StdioHostApplication.class.getName(), application.getChildren("run")[0].getAttribute("class"));
        for (Class<?> type : List.of(McpCoreStartup.class, StdioHostApplication.class, StdioHostPlatform.class, HeadlessLifetime.class)) {
            try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                assertNotNull(input); String constants = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(type.getName(), constants.contains("org/eclipse/ui"));
                assertFalse(type.getName(), constants.contains("org/eclipse/swt"));
                assertFalse(type.getName(), constants.contains("IPlatformUI"));
            }
        }
    }
}
