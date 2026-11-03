/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.impl.ReadModuleSourceTool;
import com.ditrix.edt.mcp.server.tools.impl.WriteModuleSourceTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Resource-tree regressions for #550, including the canonical target used by file writes. */
public class BslModulePathCaseTest
{
    private static final String RUSSIAN_NAME = "\u0412\u044b\u0447\u0438\u0441\u043b\u0435\u043d\u0438\u0435";
    private static final String RUSSIAN_UPPER = "\u0412\u042b\u0427\u0418\u0421\u041b\u0415\u041d\u0418\u0415";
    private static final String RUSSIAN_LOWER = "\u0432\u044b\u0447\u0438\u0441\u043b\u0435\u043d\u0438\u0435";

    @Test
    public void englishObjectUpperCaseResolvesCanonicalFile() throws Exception
    {
        assertObjectCase("Calculation", "CALCULATION");
    }

    @Test
    public void englishObjectLowerCaseResolvesCanonicalFile() throws Exception
    {
        assertObjectCase("Calculation", "calculation");
    }

    @Test
    public void russianObjectUpperCaseResolvesCanonicalFile() throws Exception
    {
        assertObjectCase(RUSSIAN_NAME, RUSSIAN_UPPER);
    }

    @Test
    public void russianObjectLowerCaseResolvesCanonicalFile() throws Exception
    {
        assertObjectCase(RUSSIAN_NAME, RUSSIAN_LOWER);
    }

    @Test
    public void everyFolderAndFileSegmentCanDifferInCase() throws Exception
    {
        Tree tree = new Tree();
        IFile canonical = tree.module("src", "Documents", "SalesOrder", "Forms", "ItemForm", "Module.bsl");

        assertSame(canonical, tree.resolve("documents/SALESORDER/forms/itemform/MODULE.BSL"));
    }

    @Test
    public void sourceFolderItselfCanDifferInCase() throws Exception
    {
        Tree tree = new Tree();
        IFile canonical = tree.module("SRC", "CommonModules", "Calculation", "Module.bsl");

        assertSame(canonical, tree.resolve("CommonModules/CALCULATION/Module.bsl"));
    }

    @Test
    public void fileNameAloneCanDifferInCase() throws Exception
    {
        Tree tree = new Tree();
        IFile canonical = tree.module("src", "CommonModules", "Calculation", "Module.bsl");

        assertSame(canonical, tree.resolve("CommonModules/Calculation/module.BSL"));
    }

    @Test
    public void caseInsensitiveLookupAlsoWorksInNonstandardSourceFolder() throws Exception
    {
        Tree tree = new Tree();
        IFile canonical = tree.module("source", "CommonModules", RUSSIAN_NAME, "Module.bsl");

        assertSame(canonical, tree.resolve("commonmodules/" + RUSSIAN_UPPER + "/module.bsl"));
    }

    @Test
    public void exactFullPathWinsWithoutEnumeratingResources() throws Exception
    {
        Tree tree = new Tree();
        String path = "CommonModules/Calculation/Module.bsl";
        IFile canonical = tree.module("src", "CommonModules", "Calculation", "Module.bsl");
        when(tree.project.getFile(new Path("src").append(path))).thenReturn(canonical);

        assertSame(canonical, tree.resolve(path));
        verify(tree.project, never()).members();
    }

    @Test
    public void exactFullPathInOtherSourceFolderWinsBeforeCaseInsensitiveSrc() throws Exception
    {
        Tree tree = new Tree();
        tree.module("src", "CommonModules", "Calculation", "Module.bsl");
        IFile exact = tree.module("source", "CommonModules", "CALCULATION", "Module.bsl");
        String path = "CommonModules/CALCULATION/Module.bsl";
        when(tree.folders.get("source").getFile(new Path(path))).thenReturn(exact);

        assertSame(exact, tree.resolve(path));
    }

    @Test
    public void conventionalSourceFolderWinsAmongCaseInsensitivePaths() throws Exception
    {
        Tree tree = new Tree();
        tree.module("source", "CommonModules", "Calculation", "Module.bsl");
        IFile canonical = tree.module("src", "CommonModules", "Calculation", "Module.bsl");

        assertSame(canonical, tree.resolve("CommonModules/CALCULATION/module.bsl"));
    }

    @Test
    public void exactObjectSegmentWinsEvenAfterTwoCaseInsensitiveSiblings() throws Exception
    {
        Tree tree = new Tree();
        tree.module("src", "CommonModules", "calculation", "Module.bsl");
        tree.module("src", "CommonModules", "CALCULATION", "Module.bsl");
        IFile canonical = tree.module("src", "CommonModules", "Calculation", "Module.bsl");

        assertSame(canonical, tree.resolve("commonmodules/Calculation/module.bsl"));
    }

    @Test
    public void exactFileSegmentWinsAmongCaseVariants() throws Exception
    {
        Tree tree = new Tree();
        tree.module("src", "CommonModules", "Calculation", "MODULE.BSL");
        tree.module("src", "CommonModules", "Calculation", "module.bsl");
        IFile canonical = tree.module("src", "CommonModules", "Calculation", "Module.bsl");

        assertSame(canonical, tree.resolve("commonmodules/Calculation/Module.bsl"));
    }

    @Test
    public void ambiguousEnglishObjectIsRefusedWithExactNamesAndNextStep() throws Exception
    {
        assertAmbiguousObject("Calculation", "calculation", "CALCULATION");
    }

    @Test
    public void ambiguousRussianObjectIsRefusedWithExactNamesAndNextStep() throws Exception
    {
        assertAmbiguousObject(RUSSIAN_NAME, RUSSIAN_LOWER, RUSSIAN_UPPER);
    }

    @Test
    public void ambiguousFileNameIsRefused() throws Exception
    {
        Tree tree = new Tree();
        tree.module("src", "CommonModules", "Calculation", "Module.bsl");
        tree.module("src", "CommonModules", "Calculation", "module.bsl");
        String requested = "CommonModules/Calculation/MODULE.BSL";

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> tree.resolve(requested));

        assertRefusal(refusal, requested, "Module.bsl", "module.bsl", "list_modules", "exact resource names");
    }

    @Test
    public void ambiguousParentIsRefusedEvenIfOnlyOneBranchContainsTheModule() throws Exception
    {
        Tree tree = new Tree();
        tree.module("src", "CommonModules", "Calculation", "Module.bsl");
        tree.folder(tree.folders.get("src/CommonModules"), "calculation");
        String requested = "CommonModules/CALCULATION/Module.bsl";

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> tree.resolve(requested));

        assertRefusal(refusal, requested, "Calculation", "calculation", "list_modules");
    }

    @Test
    public void ambiguityAcrossNonstandardSourceFoldersIsRefused() throws Exception
    {
        Tree tree = new Tree();
        tree.module("source", "CommonModules", "Calculation", "Module.bsl");
        tree.module("other", "CommonModules", "Calculation", "Module.bsl");
        String requested = "CommonModules/CALCULATION/Module.bsl";

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> tree.resolve(requested));

        assertRefusal(refusal, requested, "/source/", "/other/", "absolute file path");
    }

    @Test
    public void missingModuleRetainsTheConventionalNonexistentHandle() throws Exception
    {
        Tree tree = new Tree();
        tree.module("src", "CommonModules", "Calculation", "Module.bsl");

        assertSame(tree.missing, tree.resolve("CommonModules/Absent/Module.bsl"));
        assertFalse(tree.missing.exists());
    }

    @Test
    public void fileCannotBeTraversedAsAFolder() throws Exception
    {
        Tree tree = new Tree();
        tree.module("src", "CommonModules", "Calculation", "Module.bsl");

        assertSame(tree.missing, tree.resolve("CommonModules/CALCULATION/Module.bsl/Other.bsl"));
    }

    @Test
    public void folderAtLeafIsNotReturnedAsAModuleFile() throws Exception
    {
        Tree tree = new Tree();
        tree.module("src", "CommonModules", "Calculation", "Other.bsl");
        tree.folder(tree.folders.get("src/CommonModules/Calculation"), "Module.bsl");

        assertSame(tree.missing, tree.resolve("CommonModules/CALCULATION/Module.bsl"));
    }

    @Test
    public void unreadableTreeIsRefusedBeforeItCanBecomeACreateTarget() throws Exception
    {
        Tree tree = new Tree();
        tree.module("src", "CommonModules", "Calculation", "Module.bsl");
        when(tree.folders.get("src/CommonModules").members()).thenThrow(
            new CoreException(new Status(IStatus.ERROR, "test", "Cannot enumerate resources")));
        String requested = "CommonModules/CALCULATION/Module.bsl";

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> tree.resolve(requested));

        assertRefusal(refusal, requested, "Open and refresh", "list_modules", "Cannot enumerate resources");
    }

    @Test
    public void astLoadingAlsoRefusesAmbiguityBeforeAccessingTheModel() throws Exception
    {
        Tree tree = new Tree();
        tree.module("src", "CommonModules", "Calculation", "Module.bsl");
        tree.module("src", "CommonModules", "calculation", "Module.bsl");
        String requested = "CommonModules/CALCULATION/Module.bsl";

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
            () -> BslModuleUtils.loadModule(tree.project, requested));

        assertRefusal(refusal, requested, "list_modules");
    }

    @Test
    public void writeUsesSetContentsOnTheRealExistingFileInsteadOfCreatingACaseVariant() throws Exception
    {
        Tree tree = new Tree();
        IFile canonical = tree.module("src", "CommonModules", RUSSIAN_NAME, "Module.bsl");
        AtomicReference<String> written = new AtomicReference<>();
        doAnswer(invocation -> {
            InputStream contents = invocation.getArgument(0);
            written.set(new String(contents.readAllBytes(), StandardCharsets.UTF_8));
            return null;
        }).when(canonical).setContents(any(InputStream.class), anyInt(), isNull());

        IFile resolved = tree.resolve("CommonModules/" + RUSSIAN_UPPER + "/module.bsl");
        Method writeFile = WriteModuleSourceTool.class.getDeclaredMethod("writeFile",
            IFile.class, List.class, boolean.class, boolean.class, String.class);
        writeFile.setAccessible(true);
        writeFile.invoke(new WriteModuleSourceTool(), resolved, List.of("// case-550"), false, resolved.exists(), "\n");

        assertSame(canonical, resolved);
        assertEquals("// case-550\n", written.get());
        verify(canonical, never()).create(any(InputStream.class), anyBoolean(), isNull());
        verify(tree.missing, never()).create(any(InputStream.class), anyBoolean(), isNull());
    }

    @Test
    public void readToolConvertsResolutionRefusalToAnActionableJsonError() throws Exception
    {
        assertClosedProjectRefusal(new ReadModuleSourceTool(), Map.of());
    }

    @Test
    public void writeToolConvertsResolutionRefusalBeforeReplacingOrCreatingAFile() throws Exception
    {
        assertClosedProjectRefusal(new WriteModuleSourceTool(), Map.of(
            "mode", "replace", "source", "// case-550\n", "overwrite", "true"));
    }

    private static void assertClosedProjectRefusal(IMcpTool tool, Map<String, String> extra) throws Exception
    {
        String name = "Case550_" + UUID.randomUUID().toString().replace("-", "");
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        project.create(null);
        try
        {
            String requested = "CommonModules/CALCULATION/Module.bsl";
            Map<String, String> params = new HashMap<>(extra);
            params.put("projectName", name);
            params.put("modulePath", requested);

            JsonObject error = JsonParser.parseString(tool.execute(params)).getAsJsonObject();

            assertFalse(error.toString(), error.get("success").getAsBoolean());
            String message = error.get("error").getAsString();
            assertTrue(message, message.contains(requested));
            assertTrue(message, message.contains("Open and refresh"));
            assertTrue(message, message.contains("list_modules"));
        }
        finally
        {
            project.delete(true, true, null);
        }
    }

    private static void assertObjectCase(String actual, String requested) throws Exception
    {
        Tree tree = new Tree();
        IFile canonical = tree.module("src", "CommonModules", actual, "Module.bsl");

        assertSame(canonical, tree.resolve("CommonModules/" + requested + "/Module.bsl"));
    }

    private static void assertAmbiguousObject(String first, String second, String requestedName) throws Exception
    {
        Tree tree = new Tree();
        tree.module("src", "CommonModules", first, "Module.bsl");
        tree.module("src", "CommonModules", second, "Module.bsl");
        String requested = "CommonModules/" + requestedName + "/Module.bsl";

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> tree.resolve(requested));

        assertRefusal(refusal, requested, first, second, "list_modules", "exact resource names");
    }

    private static void assertRefusal(RuntimeException refusal, String... fragments)
    {
        assertTrue("resolution refusals must not be logged as platform failures", refusal instanceof Refusals.Marker);
        for (String fragment : fragments)
        {
            assertTrue(refusal.getMessage(), refusal.getMessage().contains(fragment));
        }
    }

    /** An explicitly case-sensitive Eclipse tree, independent of the host filesystem. */
    private static final class Tree
    {
        final IProject project = mock(IProject.class);
        final IFile missing = mock(IFile.class);
        final Map<String, IFolder> folders = new HashMap<>();
        final Map<IContainer, List<IResource>> children = new HashMap<>();

        Tree() throws Exception
        {
            when(project.getName()).thenReturn("CaseFixture");
            when(project.getFullPath()).thenReturn(new Path("/CaseFixture"));
            when(project.getFile(any(IPath.class))).thenReturn(missing);
            container(project);
        }

        void container(IContainer container) throws Exception
        {
            children.put(container, new ArrayList<>());
            when(container.members()).thenAnswer(invocation -> children.get(container).toArray(IResource[]::new));
        }

        IFolder folder(IContainer parent, String name) throws Exception
        {
            IPath fullPath = parent.getFullPath().append(name);
            IFolder folder = mock(IFolder.class);
            when(folder.getName()).thenReturn(name);
            when(folder.getType()).thenReturn(IResource.FOLDER);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullPath()).thenReturn(fullPath);
            when(folder.getFile(any(IPath.class))).thenReturn(missing);
            children.get(parent).add(folder);
            container(folder);
            folders.put(folder.getFullPath().removeFirstSegments(1).toString(), folder);
            return folder;
        }

        IFile module(String... segments) throws Exception
        {
            IContainer parent = project;
            String key = "";
            for (int index = 0; index < segments.length - 1; index++)
            {
                key = key.isEmpty() ? segments[index] : key + "/" + segments[index];
                IFolder folder = folders.get(key);
                parent = folder == null ? folder(parent, segments[index]) : folder;
            }
            String name = segments[segments.length - 1];
            IPath fullPath = parent.getFullPath().append(name);
            IFile file = mock(IFile.class);
            when(file.getName()).thenReturn(name);
            when(file.getType()).thenReturn(IResource.FILE);
            when(file.exists()).thenReturn(true);
            when(file.getFullPath()).thenReturn(fullPath);
            when(file.getProject()).thenReturn(project);
            children.get(parent).add(file);
            return file;
        }

        IFile resolve(String modulePath)
        {
            return BslModuleUtils.resolveModuleFile(project, modulePath);
        }
    }
}
