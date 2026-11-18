/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceRuleFactory;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.AdoptedModuleState;
import com.ditrix.edt.mcp.server.utils.AdoptedModuleStateTestFixtures;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * #978: every successful {@code write_module_source} answers with the {@code contentHash} of the text it wrote -
 * the very token {@code read_module_source} reports for the module right after - so the next guarded edit can
 * carry it as {@code expectedHash} without reading the module again.
 * <p>
 * The tool runs through {@code execute()} against an in-memory resource tree (the harness of
 * {@link ModulePathCaseRoundTwoTest}): the bytes the write hands to {@code IFile.setContents/create} are what the
 * read tool reads back, so each test compares the write's answer with the read tool's answer over the same bytes.
 */
public class WriteModuleSourceContentHashTest
{
    private static final String PROJECT = "HashFixture"; //$NON-NLS-1$
    private static final String MODULE = "CommonModules/Calc/Module.bsl"; //$NON-NLS-1$
    private static final Pattern HASH_LINE = Pattern.compile("\ncontentHash: ([0-9a-f]{16})\n"); //$NON-NLS-1$
    private static final String BOM = "﻿"; //$NON-NLS-1$

    private Tree tree;
    private com._1c.g5.v8.dt.core.platform.IBmModelManager models;
    private final AtomicBoolean modelGone = new AtomicBoolean();
    private final List<Runnable> restores = new ArrayList<>();

    @Before
    public void setUp() throws Exception
    {
        IWorkspace workspace = (IWorkspace) mock(Class.forName("org.eclipse.core.internal.resources.Workspace")); //$NON-NLS-1$
        tree = new Tree(workspace);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(workspace.getRoot()).thenReturn(root);
        when(root.getProject(anyString())).thenReturn(tree.project);
        when(root.getProjects()).thenReturn(new IProject[] { tree.project });
        // The method-targeted modes compare and write inside ONE workspace operation: run it in place.
        IResourceRuleFactory rules = mock(IResourceRuleFactory.class);
        when(rules.modifyRule(any(IResource.class))).thenReturn(mock(ISchedulingRule.class));
        when(workspace.getRuleFactory()).thenReturn(rules);
        doAnswer(call -> {
            ((ICoreRunnable) call.getArgument(0)).run(null);
            return null;
        }).when(workspace).run(any(ICoreRunnable.class), any(ISchedulingRule.class), anyInt(),
            (IProgressMonitor) isNull());
        Field customizerField = ResourcesPlugin.class.getDeclaredField("workspaceInitCustomizer"); //$NON-NLS-1$
        customizerField.setAccessible(true);
        Object customizer = customizerField.get(ResourcesPlugin.getPlugin());
        replace(customizer, customizer.getClass().getDeclaredField("workspace"), workspace); //$NON-NLS-1$

        Class<?> workbenchType = Class.forName("org.eclipse.ui.internal.Workbench"); //$NON-NLS-1$
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); //$NON-NLS-1$
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Object workbench = unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, workbenchType); //$NON-NLS-1$
        Display display = mock(Display.class);
        replace(workbench, workbenchType.getDeclaredField("display"), display); //$NON-NLS-1$
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
            .when(display).syncExec(any(Runnable.class));
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
            .when(display).asyncExec(any(Runnable.class));
        replace(null, workbenchType.getDeclaredField("instance"), workbench); //$NON-NLS-1$

        Activator activator = mock(Activator.class);
        IConfigurationProvider provider = mock(IConfigurationProvider.class);
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName(PROJECT);
        when(provider.getConfiguration(tree.project)).thenReturn(config);
        when(activator.getConfigurationProvider()).thenReturn(provider);
        models = mock(com._1c.g5.v8.dt.core.platform.IBmModelManager.class);
        var model = mock(com._1c.g5.v8.bm.integration.IBmModel.class);
        when(activator.getBmModelManager()).thenReturn(models);
        // A test may let EDT's model of the project go away right after the write (modelGone).
        when(models.getModel(tree.project)).thenAnswer(call -> modelGone.get() ? null : model);
        when(model.executeReadonlyTask(any())).thenAnswer(call ->
            ((com._1c.g5.v8.bm.integration.IBmTask<?>) call.getArgument(0)).execute(null, null));
        replace(null, Activator.class.getDeclaredField("plugin"), activator); //$NON-NLS-1$
        IModelEditingSupport support = mock(IModelEditingSupport.class);
        when(support.canEdit(any(), any())).thenReturn(true);
        VendorSupportGuard.setServiceForTests(() -> support);
    }

    @After
    public void tearDown()
    {
        VendorSupportGuard.setServiceForTests(null);
        for (int index = restores.size() - 1; index >= 0; index--)
        {
            restores.get(index).run();
        }
    }

    /** A whole-module replace of an LF module without a BOM: the answer is the read tool's token. */
    @Test
    public void aReplaceAnswersTheHashTheReadToolReportsAfterTheWrite() throws Exception
    {
        IFile file = tree.module("src/" + MODULE, "// before\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String before = readHash();
        String response = write(Map.of("mode", "replace", "overwrite", "true", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "source", "// after\nValue = 1;\n")); //$NON-NLS-1$ //$NON-NLS-2$
        String written = hashOf(response);
        assertEquals("// after\nValue = 1;\n", tree.text(file)); //$NON-NLS-1$
        assertEquals(readHash(), written);
        assertEquals(ContentHash.of(tree.text(file)), written);
        assertNotEquals("the token names the written text, not the text before the write", before, written); //$NON-NLS-1$
    }

    /**
     * A CRLF module with a BOM edited by searchReplace: the BOM is not part of the token (the read tool strips it),
     * the module's CRLF is kept, and the token is neither the old text's nor the {@code source} argument's.
     */
    @Test
    public void aSearchReplaceOfACrlfModuleWithABomAnswersTheReadToolsHash() throws Exception
    {
        IFile file = tree.module("src/" + MODULE, //$NON-NLS-1$
            BOM + "Procedure A()\r\n\tValue = 1;\r\nEndProcedure\r\n"); //$NON-NLS-1$
        String before = readHash();
        String response = write(Map.of("oldSource", "Value = 1;", "source", "Value = 2;")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String written = hashOf(response);
        assertEquals(BOM + "Procedure A()\r\n\tValue = 2;\r\nEndProcedure\r\n", tree.text(file)); //$NON-NLS-1$
        assertEquals(readHash(), written);
        assertNotEquals(before, written);
        assertNotEquals("the token is the module's, not the fragment sent", ContentHash.of("Value = 2;"), written); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotEquals("the BOM is not hashed - read_module_source strips it", //$NON-NLS-1$
            ContentHash.of(tree.text(file)), written);
    }

    /** A module created by the write (BOM added, project line separator): the token is the read tool's. */
    @Test
    public void aNewModuleAnswersTheReadToolsHash() throws Exception
    {
        tree.folder("src/CommonModules/Calc"); //$NON-NLS-1$
        String response = write(Map.of("mode", "replace", "source", "// first\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(response, response.contains("\nnewFile: true\n")); //$NON-NLS-1$
        IFile created = tree.handle("src/" + MODULE); //$NON-NLS-1$
        assertTrue(tree.text(created).startsWith(BOM));
        assertEquals(readHash(), hashOf(response));
    }

    /** Every mode answers the token: append as well. */
    @Test
    public void anAppendAnswersTheReadToolsHash() throws Exception
    {
        tree.module("src/" + MODULE, "Procedure A()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String response = write(Map.of("mode", "append", "source", "Procedure B()\nEndProcedure\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(readHash(), hashOf(response));
    }

    /**
     * The point of the token: a chain of method-targeted edits (each requires {@code expectedHash}) runs on the
     * returned tokens alone, without a read between the writes - on a CRLF module with a BOM.
     */
    @Test
    public void methodEditsChainOnTheReturnedHashesWithoutARead() throws Exception
    {
        IFile file = tree.module("src/" + MODULE, //$NON-NLS-1$
            BOM + "Procedure Target()\r\n\tValue = 1;\r\nEndProcedure\r\n"); //$NON-NLS-1$
        String first = write(Map.of("mode", "replaceMethod", "methodName", "Target", "expectedHash", readHash(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "source", "Procedure Target()\n\tValue = 2;\nEndProcedure\n")); //$NON-NLS-1$ //$NON-NLS-2$
        String firstHash = hashOf(first);
        String second = write(Map.of("mode", "insertAfter", "methodName", "Target", "expectedHash", firstHash, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "source", "Procedure Added()\nEndProcedure\n")); //$NON-NLS-1$ //$NON-NLS-2$
        String secondHash = hashOf(second);
        assertTrue(tree.text(file), tree.text(file).contains("Procedure Added()\r\nEndProcedure")); //$NON-NLS-1$
        assertEquals(readHash(), secondHash);
        assertNotEquals(firstHash, secondHash);
    }

    /**
     * The token vouches for the text THIS call wrote, not for whatever the file holds later: after another writer
     * changes the module, a guarded write carrying the returned token is refused.
     */
    @Test
    public void aLaterForeignChangeMakesTheReturnedHashStale() throws Exception
    {
        IFile file = tree.module("src/" + MODULE, "Procedure Target()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String hash = hashOf(write(Map.of("mode", "append", "source", "// mine\n"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        tree.contents.get(file).set("Procedure Target()\nEndProcedure\n// theirs\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        String refused = write(Map.of("mode", "replaceMethod", "methodName", "Target", "expectedHash", hash, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "source", "Procedure Target()\n\tValue = 2;\nEndProcedure\n")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject error = JsonParser.parseString(refused).getAsJsonObject();
        assertFalse(refused, error.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(tree.text(file), tree.text(file).endsWith("// theirs\n")); //$NON-NLS-1$
    }

    /**
     * The token is computed from the text this call wrote, never by reading the file again: when another writer
     * changes the module right after our write, the token still names OUR text - so a guarded edit carrying it is
     * refused instead of blessing content the caller has not seen.
     */
    @Test
    public void aForeignWriteRightAfterOursIsNotVouchedFor() throws Exception
    {
        IFile file = tree.module("src/" + MODULE, "Procedure Target()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String[] ours = new String[1];
        tree.afterWrite.put(file, () -> {
            ours[0] = tree.text(file);
            tree.contents.get(file).set("Procedure Target()\nEndProcedure\n// theirs\n" //$NON-NLS-1$
                .getBytes(StandardCharsets.UTF_8));
        });
        String hash = hashOf(write(Map.of("mode", "append", "source", "// mine\n"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNotNull("the foreign writer ran after our write", ours[0]); //$NON-NLS-1$
        assertTrue(ours[0], ours[0].endsWith("// mine\n")); //$NON-NLS-1$
        assertEquals("the token names the text this call wrote", ContentHash.of(ours[0]), hash); //$NON-NLS-1$
        assertNotEquals("the token does not vouch for the later foreign text", readHash(), hash); //$NON-NLS-1$
        String refused = write(Map.of("mode", "replaceMethod", "methodName", "Target", "expectedHash", hash, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "source", "Procedure Target()\n\tValue = 2;\nEndProcedure\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(refused, JsonParser.parseString(refused).getAsJsonObject().get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(tree.text(file), tree.text(file).endsWith("// theirs\n")); //$NON-NLS-1$
    }

    /**
     * Text the UTF-8 encoder cannot represent: an unpaired surrogate in {@code source} lands on disk as '?', and
     * the token still equals the read tool's - both sides hash the text as UTF-8 encodes it.
     */
    @Test
    public void anUnencodableCharacterStillAnswersTheReadToolsHash() throws Exception
    {
        IFile file = tree.module("src/" + MODULE, "// before\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String response = write(Map.of("mode", "replace", "overwrite", "true", "skipSyntaxCheck", "true", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "source", "// lone \uD800 surrogate\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("// lone ? surrogate\n", tree.text(file)); //$NON-NLS-1$
        assertEquals(readHash(), hashOf(response));
    }

    /**
     * The text is written but the adopted-module step failed (#711): the error says the mutation committed and
     * still carries the token of the written text - that IS the truth about the file.
     */
    @Test
    public void anErrorAfterMutationCarriesTheWrittenHash()
    {
        AdoptedModuleState.Result failed = AdoptedModuleStateTestFixtures.failedForWantOfAModel();
        JsonObject error = JsonParser.parseString(
            WriteModuleSourceTool.finishWrite("0123456789abcdef", () -> failed, state -> "success")) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject();
        assertTrue(error.get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
        assertNotNull(error.get("contentHash")); //$NON-NLS-1$
        assertEquals("0123456789abcdef", error.get("contentHash").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The same through {@code execute()}: in an extension whose BM model EDT unregisters right after the write
     * (a project restart), the adopted-module step (#711) cannot run after the text is written - the write
     * answers the error with {@code mutationCommitted:true}, and that error carries the token of the text now
     * on disk.
     */
    @Test
    public void aWriteWhoseAdoptedModuleStepFailsAnswersTheWrittenHashInItsError() throws Exception
    {
        when(tree.description.getNatureIds()).thenReturn(new String[] { "com._1c.g5.v8.dt.core.V8ExtensionNature" }); //$NON-NLS-1$
        IFile file = tree.module("src/" + MODULE, "Procedure A()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        tree.afterWrite.put(file, () -> modelGone.set(true));
        String response = write(Map.of("mode", "append", "source", "// mine\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        JsonObject error = JsonParser.parseString(response).getAsJsonObject();
        assertFalse(response, error.get("success").getAsBoolean()); //$NON-NLS-1$
        assertNotNull(response, error.get("mutationCommitted")); //$NON-NLS-1$
        assertTrue(response, error.get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
        assertTrue(tree.text(file), tree.text(file).endsWith("// mine\n")); //$NON-NLS-1$
        assertNotNull(response, error.get("contentHash")); //$NON-NLS-1$
        assertEquals(ContentHash.of(tree.text(file)), error.get("contentHash").getAsString()); //$NON-NLS-1$
    }

    /** The success frontmatter carries the token it is given, in the frontmatter. */
    @Test
    public void theSuccessFrontmatterCarriesTheHash()
    {
        String response = WriteModuleSourceTool.buildSuccessResponse(PROJECT, MODULE, "append", false, //$NON-NLS-1$
            List.of("A", "B"), true, 1, "0123456789abcdef", null, AdoptedModuleState.Result.notApplicable()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(response, response.contains("\ncontentHash: 0123456789abcdef\n")); //$NON-NLS-1$
        assertTrue("the key belongs to the frontmatter", //$NON-NLS-1$
            response.indexOf("contentHash") < response.indexOf("\n---\n", 4)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String write(Map<String, String> extra)
    {
        Map<String, String> params = new HashMap<>(extra);
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("modulePath", MODULE); //$NON-NLS-1$
        return new WriteModuleSourceTool().execute(params);
    }

    /**
     * A BOM-less module whose declared charset is not UTF-8: the writer emits UTF-8 bytes, the read tool decodes them
     * with the declared charset, and the token is the one that read reports - so the next guarded edit is accepted.
     */
    @Test
    public void aBomlessModuleInANonUtf8CharsetAnswersTheTokenItsReadReports() throws Exception
    {
        IFile file = tree.module("src/" + MODULE, ""); //$NON-NLS-1$ //$NON-NLS-2$
        tree.contents.get(file).set("// до\n".getBytes("windows-1251")); //$NON-NLS-1$ //$NON-NLS-2$
        when(file.getCharset()).thenReturn("windows-1251"); //$NON-NLS-1$
        String response = write(Map.of("mode", "replace", "overwrite", "true", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "source", "// после\n")); //$NON-NLS-1$ //$NON-NLS-2$
        String written = hashOf(response);
        assertEquals(readHash(), written);
        // The next guarded edit chains on the returned token alone and is accepted.
        hashOf(write(Map.of("oldSource", "//", "source", "///", "expectedHash", written))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    /**
     * A BOM-less module rewritten with text that itself begins with U+FEFF: the bytes start with EF BB BF, which the
     * read tool takes for a BOM and strips, so the token must be of the text without it.
     */
    @Test
    public void aWrittenTextStartingWithAByteOrderMarkAnswersTheTokenItsReadReports() throws Exception
    {
        tree.module("src/" + MODULE, "// before\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String response = write(Map.of("mode", "replace", "overwrite", "true", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "normalizeInvalidCharacters", "false", "source", "﻿// after\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(readHash(), hashOf(response));
    }

    private String readHash()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("modulePath", MODULE); //$NON-NLS-1$
        return hashOf(new ReadModuleSourceTool().execute(params));
    }

    private static String hashOf(String response)
    {
        assertFalse("expected a success response: " + response, response.startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        Matcher matcher = HASH_LINE.matcher(response);
        assertTrue("the response must carry contentHash: " + response, matcher.find()); //$NON-NLS-1$
        return matcher.group(1);
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

    /** An in-memory resource tree (from {@link ModulePathCaseRoundTwoTest}): files keep the exact bytes written. */
    private static final class Tree
    {
        final IProject project = mock(IProject.class);
        final IWorkspace workspace;
        final IProjectDescription description = mock(IProjectDescription.class);
        final Map<String, IFolder> folders = new HashMap<>();
        final Map<String, IFile> files = new HashMap<>();
        final Map<IFile, AtomicReference<byte[]>> contents = new HashMap<>();
        final Map<IContainer, List<IResource>> children = new HashMap<>();
        /** Another writer that changes a file right after the next write of it, once. */
        final Map<IFile, Runnable> afterWrite = new HashMap<>();

        Tree(IWorkspace workspace) throws Exception
        {
            this.workspace = workspace;
            when(project.getName()).thenReturn(PROJECT);
            when(project.getFullPath()).thenReturn(new Path("/" + PROJECT)); //$NON-NLS-1$
            when(project.exists()).thenReturn(true);
            when(project.isOpen()).thenReturn(true);
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
            when(folder.getFullPath()).thenReturn(new Path("/" + PROJECT).append(relative)); //$NON-NLS-1$
            when(folder.getFile(any(IPath.class))).thenAnswer(call -> handle(relative.append((IPath) call.getArgument(0)).toString()));
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
            when(file.getCharset()).thenReturn("UTF-8"); //$NON-NLS-1$
            when(file.getType()).thenReturn(IResource.FILE);
            when(file.getProject()).thenReturn(project);
            when(file.getWorkspace()).thenReturn(workspace);
            when(file.getFullPath()).thenReturn(new Path("/" + PROJECT).append(relative)); //$NON-NLS-1$
            when(file.getProjectRelativePath()).thenReturn(relative);
            when(file.getParent()).thenAnswer(call -> folders.get(relative.removeLastSegments(1).toString()));
            when(file.exists()).thenAnswer(call -> bytes.get() != null);
            when(file.getContents()).thenAnswer(call -> new ByteArrayInputStream(bytes.get()));
            doAnswer(call -> {
                bytes.set(((InputStream) call.getArgument(0)).readAllBytes());
                Runnable foreign = afterWrite.remove(file);
                if (foreign != null)
                {
                    foreign.run();
                }
                return null;
            }).when(file).setContents(any(InputStream.class), anyInt(), isNull());
            doAnswer(call -> { bytes.set(((InputStream) call.getArgument(0)).readAllBytes()); return null; })
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
