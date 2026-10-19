/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.symbol.SymbolInfoService;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MethodReferenceValidator;
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.ditrix.edt.mcp.server.utils.RefusalsTestAccess;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** #550 round-two regressions through real tool boundaries and a case-sensitive resource tree. */
public class ModulePathCaseRoundTwoTest
{
    private static final String REQUEST = "CommonModules/CALCULATION/Module.bsl";
    private static final String CANONICAL = "CommonModules/Calculation/Module.bsl";
    private Tree tree;
    private final List<Runnable> restores = new ArrayList<>();
    private final List<IStatus> statuses = new ArrayList<>();

    @Before
    public void setUp() throws Exception
    {
        tree = new Tree();
        // Only the headless test runtime is redirected. Every singleton is restored in @After.
        // This drives execute() and its actual UI callback without starting a workbench.
        IWorkspace workspace = (IWorkspace) mock(Class.forName("org.eclipse.core.internal.resources.Workspace"));
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(workspace.getRoot()).thenReturn(root);
        when(root.getProject(anyString())).thenReturn(tree.project);
        when(root.getProjects()).thenReturn(new IProject[] { tree.project });
        Field customizerField = ResourcesPlugin.class.getDeclaredField("workspaceInitCustomizer");
        customizerField.setAccessible(true);
        Object customizer = customizerField.get(ResourcesPlugin.getPlugin());
        replace(customizer, customizer.getClass().getDeclaredField("workspace"), workspace);

        Class<?> workbenchType = Class.forName("org.eclipse.ui.internal.Workbench");
        // Workbench is final and its constructor starts the UI. Allocate only the holder
        // whose getDisplay() reads the display field; no real SWT display is created.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Object workbench = unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, workbenchType);
        Display display = mock(Display.class);
        replace(workbench, workbenchType.getDeclaredField("display"), display);
        doAnswer(call -> { ((Runnable)call.getArgument(0)).run(); return null; })
            .when(display).syncExec(any(Runnable.class));
        doAnswer(call -> { ((Runnable)call.getArgument(0)).run(); return null; })
            .when(display).asyncExec(any(Runnable.class));
        replace(null, workbenchType.getDeclaredField("instance"), workbench);

        Activator activator = mock(Activator.class);
        IConfigurationProvider provider = mock(IConfigurationProvider.class);
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("CaseFixture");
        when(provider.getConfiguration(tree.project)).thenReturn(config);
        when(activator.getConfigurationProvider()).thenReturn(provider);
        replace(null, Activator.class.getDeclaredField("plugin"), activator);
        IModelEditingSupport support = mock(IModelEditingSupport.class);
        when(support.canEdit(any(), any())).thenReturn(true);
        VendorSupportGuard.setServiceForTests(() -> support);
        RefusalsTestAccess.setSink(statuses::add);
    }

    @After
    public void tearDown()
    {
        RefusalsTestAccess.setSink(null);
        VendorSupportGuard.setServiceForTests(null);
        for (int index = restores.size() - 1; index >= 0; index--)
        {
            restores.get(index).run();
        }
    }

    @Test
    public void setBreakpointRefusesClosedProjectAsJson() throws Exception
    {
        closedTree();
        assertResolutionError(new SetBreakpointTool(), Map.of("lineNumber", "1"));
    }

    @Test
    public void contentAssistRefusesAmbiguousModuleAsJson() throws Exception
    {
        ambiguousTree();
        assertResolutionError(new GetContentAssistTool(), Map.of("line", "1", "column", "1"));
    }

    @Test
    public void symbolInfoRefusesAmbiguousModuleAsJson() throws Exception
    {
        ambiguousTree();
        assertResolutionError(new GetSymbolInfoTool(), Map.of("line", "1", "column", "1"));
    }

    @Test
    public void symbolInfoEmfFallbackPreservesResolutionRefusal() throws Exception
    {
        ambiguousTree();
        Method fallback = SymbolInfoService.class.getDeclaredMethod("getSymbolInfoViaEmf",
            IProject.class, String.class, int.class, int.class);
        fallback.setAccessible(true);
        String result = (String)fallback.invoke(new SymbolInfoService(), tree.project, REQUEST, 1, 1);
        assertNotNull("the fallback must preserve the refusal", result);
        assertTrue(result, result.startsWith("Error: Ambiguous modulePath"));
        assertTrue(result, result.contains(REQUEST));
        assertTrue(result, result.contains("list_modules"));
    }

    @Test
    public void quickFixVendorGuardPreservesResolutionRefusal() throws Exception
    {
        ambiguousTree();
        ApplyQuickFixTool.MarkerMatch marker = new ApplyQuickFixTool.MarkerMatch(null, "case550", REQUEST, 1, "case550");
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        assertResolutionError(ApplyQuickFixTool.vendorSupportRefusal(tree.project,
            MetadataScope.ofConfiguration(config), marker));
    }

    @Test
    public void removeBreakpointLogsResolutionRefusalAtInfo() throws Exception
    {
        ambiguousTree();
        assertResolutionError(new RemoveBreakpointTool(), Map.of("lineNumber", "1"));
        assertInfoRefusal();
    }

    @Test
    public void goToDefinitionLogsResolutionRefusalAtInfo() throws Exception
    {
        ambiguousTree();
        assertResolutionError(new GoToDefinitionTool(), Map.of("symbol", "DoWork"));
        assertInfoRefusal();
    }

    @Test
    public void listModulesSkipsRefusedCandidateAndKeepsOtherModules() throws Exception
    {
        ambiguousTree();
        IFile canonical = tree.module("src/CommonModules/Healthy/Module.bsl", "// healthy\n");
        List<Object> modules = new ArrayList<>();
        Method add = ListModulesTool.class.getDeclaredMethod("addIfExists", IProject.class, List.class,
            String.class, String.class, String.class, String.class, String.class);
        add.setAccessible(true);
        ListModulesTool tool = new ListModulesTool();
        add.invoke(tool, tree.project, modules, REQUEST, "Module", "CommonModule", "CALCULATION", null);
        add.invoke(tool, tree.project, modules, CANONICAL.replace("Calculation", "Healthy"),
            "Module", "CommonModule", "Healthy", null);
        assertTrue(canonical.exists());
        assertEquals("a refused candidate must not discard healthy modules", 1, modules.size());
        assertInfoRefusal();
    }

    @Test
    public void listModulesPropagatesAnUnreadableTreeInsteadOfSkippingIt() throws Exception
    {
        closedTree();
        List<Object> modules = new ArrayList<>();
        Method add = ListModulesTool.class.getDeclaredMethod("addIfExists", IProject.class, List.class,
            String.class, String.class, String.class, String.class, String.class);
        add.setAccessible(true);
        java.lang.reflect.InvocationTargetException thrown = assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> add.invoke(new ListModulesTool(), tree.project, modules, REQUEST, "Module", "CommonModule",
                "CALCULATION", null));
        assertTrue("an unreadable tree must reach the listing's error handler: " + thrown.getCause(),
            thrown.getCause() instanceof IllegalStateException);
        assertTrue(modules.isEmpty());
    }

    @Test
    public void methodReferenceValidationPreservesResolutionRefusal() throws Exception
    {
        ambiguousTree();
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        var module = MdClassFactory.eINSTANCE.createCommonModule();
        module.setName("CALCULATION");
        config.getCommonModules().add(module);
        assertResolutionError(MethodReferenceValidator.validate(tree.project, config, "CALCULATION.DoWork",
            "methodName", "Module.Method", "CALCULATION.DoWork"));
    }

    @Test
    public void outgoingStructuresLogsResolutionRefusalAtInfo() throws Exception
    {
        ambiguousTree();
        assertResolutionError(new GetOutgoingStructuresTool(), Map.of());
        assertInfoRefusal();
    }

    @Test
    public void callHierarchyLogsResolutionRefusalAtInfo() throws Exception
    {
        ambiguousTree();
        assertResolutionError(new GetMethodCallHierarchyTool(), Map.of("methodName", "DoWork"));
        assertInfoRefusal();
    }

    @Test
    public void transitiveCallHierarchyLogsResolutionRefusalAtInfo() throws Exception
    {
        ambiguousTree();
        assertResolutionError(new GetMethodCallHierarchyTool(), Map.of("methodName", "DoWork", "depth", "2"));
        assertInfoRefusal();
    }

    @Test
    public void calleesCallHierarchyLogsResolutionRefusalAtInfo() throws Exception
    {
        ambiguousTree();
        assertResolutionError(new GetMethodCallHierarchyTool(), Map.of("methodName", "DoWork", "direction", "callees"));
        assertInfoRefusal();
    }

    @Test
    public void outgoingCallHierarchyLogsResolutionRefusalAtInfo() throws Exception
    {
        ambiguousTree();
        assertResolutionError(new GetMethodCallHierarchyTool(), Map.of("direction", "outgoing"));
        assertInfoRefusal();
    }

    @Test
    public void objectNameFirstModuleWriteUsesCanonicalParent() throws Exception
    {
        IFolder parent = tree.folder("src/CommonModules/Calculation");
        Map<String, String> arguments = params(Map.of("objectName", "CommonModule.calculation",
            "mode", "replace", "source", "// first module\n", "skipSyntaxCheck", "true"));
        arguments.remove("modulePath");
        String response = new WriteModuleSourceTool().execute(arguments);
        assertFalse(response, response.startsWith("{"));
        IFile target = tree.handle("src/" + CANONICAL);
        assertTrue("objectName must create inside the existing object folder", target.exists());
        assertSame(parent, target.getParent());
        assertEquals("\uFEFF// first module" + System.lineSeparator(), tree.text(target));
        assertTrue(response, response.contains(CANONICAL));
    }

    @Test
    public void missingEnglishLeafKeepsCanonicalParent() throws Exception
    {
        assertMissingLeaf("Calculation", "CALCULATION");
    }

    @Test
    public void missingRussianLeafKeepsCanonicalParent() throws Exception
    {
        assertMissingLeaf("\u0417\u0430\u043a\u0430\u0437", "\u0437\u0430\u043a\u0430\u0437");
    }

    @Test
    public void existingModuleInOtherSourceRootBeatsMissingSrcLeaf() throws Exception
    {
        tree.folder("src/CommonModules/Calculation");
        IFile existing = tree.module("source/CommonModules/Calculation/Module.bsl", "// existing\n");
        assertSame(existing, BslModuleUtils.resolveModuleFile(tree.project, REQUEST));
    }

    @Test
    public void missingLeafInNonstandardSourceRootKeepsCanonicalParent() throws Exception
    {
        IFolder parent = tree.folder("source/CommonModules/Calculation");
        IFile target = BslModuleUtils.resolveModuleFile(tree.project, REQUEST);
        assertSame(parent, target.getParent());
        assertEquals("source/" + CANONICAL, target.getProjectRelativePath().toString());
    }

    @Test
    public void multipleNonstandardCreationParentsAreRefused() throws Exception
    {
        tree.folder("source/CommonModules/Calculation");
        tree.folder("other/CommonModules/Calculation");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> BslModuleUtils.resolveModuleFile(tree.project, REQUEST));
        assertTrue(error.getMessage(), error.getMessage().contains("multiple creation targets"));
        assertTrue(error.getMessage(), error.getMessage().contains("Create the intended module in EDT"));
        assertTrue(error instanceof Refusals.Marker);
    }

    @Test
    public void existingModuleBeatsMultipleMissingNonstandardLeaves() throws Exception
    {
        tree.folder("source/CommonModules/Calculation");
        tree.folder("other/CommonModules/Calculation");
        IFile existing = tree.module("third/CommonModules/Calculation/Module.bsl", "// existing\n");
        assertSame(existing, BslModuleUtils.resolveModuleFile(tree.project, REQUEST));
    }

    @Test
    public void preparationFailureDoesNotClaimAWriteWasAttempted() throws Exception
    {
        IFile file = tree.module("src/" + CANONICAL, "// original\n");
        IConfigurationProvider provider = Activator.getDefault().getConfigurationProvider();
        when(provider.getConfiguration(tree.project)).thenThrow(new IllegalStateException("Metadata root unavailable"));
        String response = new WriteModuleSourceTool().execute(params(Map.of("mode", "append", "source", "// new\n")));
        JsonObject error = JsonParser.parseString(response).getAsJsonObject();
        assertFalse(response, error.get("success").getAsBoolean());
        assertEquals("Failed to prepare module write: Metadata root unavailable", error.get("error").getAsString());
        assertEquals("// original\n", tree.text(file));
    }

    @Test
    public void firstModuleWriteCreatesInsideCanonicalParent() throws Exception
    {
        IFolder parent = tree.folder("src/CommonModules/Calculation");
        String response = new WriteModuleSourceTool().execute(params(Map.of("mode", "replace",
            "source", "// first module\n", "skipSyntaxCheck", "true")));
        assertFalse(response, response.startsWith("{"));
        IFile created = tree.handle("src/" + CANONICAL);
        assertTrue("the canonical target must be created", created.exists());
        assertSame(parent, created.getParent());
        assertEquals("\uFEFF// first module" + System.lineSeparator(), tree.text(created));
    }

    @Test
    public void readResponseEchoesCanonicalModulePath() throws Exception
    {
        tree.module("src/" + CANONICAL, "// canonical\n");
        String response = new ReadModuleSourceTool().execute(params(Map.of()));
        assertTrue(response, response.contains("module: " + CANONICAL + "\n"));
        assertFalse(response, response.contains(REQUEST));

        // Displaying a canonical relative path must not redirect an absolute
        // selector's model lookup to an identically named module in src/.
        IFile alternative = tree.module("source/" + CANONICAL, "// alternate source root\n");
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        when(root.findFilesForLocationURI(any(java.net.URI.class))).thenReturn(new IFile[] { alternative });
        BmAwareResourceSetProvider provider = mock(BmAwareResourceSetProvider.class);
        ResourceSet model = mock(ResourceSet.class);
        Resource resource = mock(Resource.class);
        when(resource.getContents()).thenReturn(new BasicEList<>());
        when(model.getResource(any(URI.class), anyBoolean())).thenReturn(resource);
        when(provider.get(tree.project)).thenReturn(model);
        when(Activator.getDefault().getResourceSetProvider()).thenReturn(provider);
        Map<String, String> absolute = params(Map.of());
        absolute.put("modulePath", "C:/case550/Module.bsl");
        String selected = new ReadModuleSourceTool().execute(absolute);
        assertTrue(selected, selected.contains("// alternate source root"));
        assertTrue(selected, selected.contains("module: " + CANONICAL + "\n"));
        verify(model).getResource(URI.createPlatformResourceURI(
            "/CaseFixture/source/" + CANONICAL, true), true);
    }

    @Test
    public void writeResponseEchoesCanonicalModulePath() throws Exception
    {
        tree.module("src/" + CANONICAL, "// original\n");
        String response = new WriteModuleSourceTool().execute(params(Map.of("mode", "append",
            "source", "// canonical\n", "skipSyntaxCheck", "true")));
        assertTrue(response, response.contains(CANONICAL));
        assertFalse(response, response.contains(REQUEST));
    }

    @Test
    public void readResolutionRefusalHasNoIoPrefix() throws Exception
    {
        ambiguousTree();
        String message = assertResolutionError(new ReadModuleSourceTool(), Map.of());
        assertTrue(message, message.startsWith("Ambiguous modulePath"));
    }

    @Test
    public void writeResolutionRefusalHasNoIoPrefix() throws Exception
    {
        ambiguousTree();
        String message = assertResolutionError(new WriteModuleSourceTool(), Map.of("mode", "replace",
            "source", "// refused\n", "overwrite", "true"));
        assertTrue(message, message.startsWith("Ambiguous modulePath"));
    }

    @Test
    public void structureExecutePreservesRefusalInsteadOfIndexingFallback() throws Exception
    {
        closedTree();
        String message = assertResolutionError(new GetModuleStructureTool(), Map.of());
        assertTrue(message, message.startsWith("Cannot resolve modulePath"));
    }

    @Test
    public void methodReadExecutePreservesRefusalInsteadOfIndexingFallback() throws Exception
    {
        closedTree();
        String message = assertResolutionError(new ReadMethodSourceTool(), Map.of("methodName", "DoWork"));
        assertTrue(message, message.startsWith("Cannot resolve modulePath"));
    }

    private void assertMissingLeaf(String actual, String requested) throws Exception
    {
        IFolder parent = tree.folder("src/Documents/" + actual);
        IFile target = BslModuleUtils.resolveModuleFile(tree.project, "Documents/" + requested + "/ObjectModule.bsl");
        assertSame("a first module belongs to the real object folder", parent, target.getParent());
        assertEquals("src/Documents/" + actual + "/ObjectModule.bsl", target.getProjectRelativePath().toString());
        assertFalse(target.exists());
    }

    private void closedTree() throws Exception
    {
        when(tree.project.isOpen()).thenReturn(false);
        when(tree.project.members()).thenThrow(new CoreException(new Status(IStatus.ERROR, "test", "Project is closed")));
    }

    private void ambiguousTree() throws Exception
    {
        tree.module("src/" + CANONICAL, "// first\n");
        tree.module("src/CommonModules/calculation/Module.bsl", "// second\n");
    }

    private static Map<String, String> params(Map<String, String> extra)
    {
        Map<String, String> params = new HashMap<>(extra);
        params.put("projectName", "CaseFixture");
        params.put("modulePath", REQUEST);
        return params;
    }

    private String assertResolutionError(IMcpTool tool, Map<String, String> extra)
    {
        return assertResolutionError(tool.execute(params(extra)));
    }

    private String assertResolutionError(String response)
    {
        assertNotNull("a refusal must be a tool error, not an absent fallback result", response);
        JsonObject error = JsonParser.parseString(response).getAsJsonObject();
        assertFalse(response, error.get("success").getAsBoolean());
        String message = error.get("error").getAsString();
        assertTrue(message, message.contains(REQUEST));
        assertTrue(message, message.contains("list_modules"));
        assertFalse(message, message.contains("fully indexed"));
        assertFalse(message, message.contains("BSL model is not available"));
        return message;
    }

    private void assertInfoRefusal()
    {
        assertFalse("the tool must log its refusal through Refusals.log", statuses.isEmpty());
        for (IStatus status : statuses)
        {
            assertEquals(status.toString(), IStatus.INFO, status.getSeverity());
            assertNull(status.getException());
            assertTrue(status.getMessage(), status.getMessage().contains(REQUEST));
        }
    }

    private void replace(Object target, Field field, Object value) throws Exception
    {
        field.setAccessible(true);
        Object previous = field.get(target);
        restores.add(() -> {
            try { field.set(target, previous); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
        });
        field.set(target, value);
    }

    /** Resource handles are case sensitive even when the host filesystem is not. */
    private static final class Tree
    {
        final IProject project = mock(IProject.class);
        final Map<String, IFolder> folders = new HashMap<>();
        final Map<String, IFile> files = new HashMap<>();
        final Map<IFile, AtomicReference<byte[]>> contents = new HashMap<>();
        final Map<IContainer, List<IResource>> children = new HashMap<>();

        Tree() throws Exception
        {
            when(project.getName()).thenReturn("CaseFixture");
            when(project.getFullPath()).thenReturn(new Path("/CaseFixture"));
            when(project.exists()).thenReturn(true);
            when(project.isOpen()).thenReturn(true);
            IProjectDescription description = mock(IProjectDescription.class);
            when(description.getNatureIds()).thenReturn(new String[0]);
            when(project.getDescription()).thenReturn(description);
            when(project.getFile(any(IPath.class))).thenAnswer(call -> handle(call.getArgument(0).toString()));
            container(project);
        }

        void container(IContainer container) throws Exception
        {
            children.put(container, new ArrayList<>());
            when(container.members()).thenAnswer(call -> children.get(container).toArray(IResource[]::new));
        }

        IFolder folder(String path) throws Exception
        {
            if (folders.containsKey(path)) { return folders.get(path); }
            IPath relative = new Path(path);
            IContainer parent = relative.segmentCount() == 1 ? project : folder(relative.removeLastSegments(1).toString());
            IFolder folder = mock(IFolder.class);
            when(folder.getName()).thenReturn(relative.lastSegment());
            when(folder.getType()).thenReturn(IResource.FOLDER);
            when(folder.exists()).thenReturn(true);
            when(folder.getParent()).thenReturn(parent);
            when(folder.getFullPath()).thenReturn(new Path("/CaseFixture").append(relative));
            when(folder.getFile(any(IPath.class))).thenAnswer(call -> handle(relative.append((IPath)call.getArgument(0)).toString()));
            children.get(parent).add(folder);
            container(folder);
            folders.put(path, folder);
            return folder;
        }

        IFile handle(String path) throws Exception
        {
            if (files.containsKey(path)) { return files.get(path); }
            IPath relative = new Path(path);
            IFile file = mock(IFile.class);
            AtomicReference<byte[]> bytes = new AtomicReference<>();
            when(file.getName()).thenReturn(relative.lastSegment());
            when(file.getCharset()).thenReturn("UTF-8");
            when(file.getType()).thenReturn(IResource.FILE);
            when(file.getProject()).thenReturn(project);
            when(file.getFullPath()).thenReturn(new Path("/CaseFixture").append(relative));
            when(file.getProjectRelativePath()).thenReturn(relative);
            when(file.getParent()).thenAnswer(call -> folders.get(relative.removeLastSegments(1).toString()));
            when(file.exists()).thenAnswer(call -> bytes.get() != null);
            when(file.getContents()).thenAnswer(call -> new ByteArrayInputStream(bytes.get()));
            doAnswer(call -> { bytes.set(((InputStream)call.getArgument(0)).readAllBytes()); return null; })
                .when(file).setContents(any(InputStream.class), anyInt(), isNull());
            doAnswer(call -> { bytes.set(((InputStream)call.getArgument(0)).readAllBytes()); return null; })
                .when(file).create(any(InputStream.class), anyBoolean(), isNull());
            files.put(path, file);
            contents.put(file, bytes);
            return file;
        }

        IFile module(String path, String text) throws Exception
        {
            IFolder parent = folder(new Path(path).removeLastSegments(1).toString());
            IFile file = handle(path);
            contents.get(file).set(text.getBytes(StandardCharsets.UTF_8));
            children.get(parent).add(file);
            return file;
        }

        String text(IFile file)
        {
            return new String(contents.get(file).get(), StandardCharsets.UTF_8);
        }
    }
}
