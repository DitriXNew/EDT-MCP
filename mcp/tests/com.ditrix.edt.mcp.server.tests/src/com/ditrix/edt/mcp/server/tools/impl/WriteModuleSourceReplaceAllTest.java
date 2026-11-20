/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * #996: {@code write_module_source mode=replaceAll} replaces every whole-word occurrence of {@code oldSource}, reports
 * how many and where, names the other-case spellings it left, and inherits every guard of the text modes - the
 * {@code expectedHash} check, the syntax check, the repeated-method-name check (#987) and the re-plan under the file's
 * modify rule.
 * <p>
 * Runs {@code execute()} against the in-memory resource tree of {@link WriteModuleSourceDuplicateMethodsTest}, so the
 * bytes on "disk" are exactly the bytes the tool writes.
 */
public class WriteModuleSourceReplaceAllTest
{
    private static final String PROJECT = "ReplaceAllFixture"; //$NON-NLS-1$
    private static final String MODULE = "CommonModules/Calc/Module.bsl"; //$NON-NLS-1$

    private Tree tree;
    private com._1c.g5.v8.dt.core.platform.IBmModelManager models;
    private final AtomicBoolean modelGone = new AtomicBoolean();
    private final List<Runnable> restores = new ArrayList<>();
    /** Another writer that changes a file after the tool read it and before its locked write runs, once. */
    private Runnable beforeLockedWrite;

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
            Runnable foreign = beforeLockedWrite;
            beforeLockedWrite = null;
            if (foreign != null)
            {
                foreign.run();
            }
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

    // ==================== replaceAll rewrites every whole-word occurrence ====================

    /** The module of the #987 tests: a function and a procedure, Russian keywords. */
    private static final String BASE = "Функция ВыборкаДанных(Параметр) Экспорт\n\tВозврат Параметр;\nКонецФункции\n\n" //$NON-NLS-1$
        + "Процедура Другая()\n\tЗначение = 1;\nКонецПроцедуры\n"; //$NON-NLS-1$

    private static final String ENGLISH = "Procedure Run() Export\n\tOldName = 1; // OldName here\n" //$NON-NLS-1$
        + "\tOldNameX = OldName + XOldName;\n\tText = \"OldName\";\nEndProcedure\n"; //$NON-NLS-1$

    @Test
    public void everyWholeWordOccurrenceIsReplacedAndReported() throws Exception
    {
        IFile module = tree.module("src/" + MODULE, ENGLISH); //$NON-NLS-1$
        String response = write(Map.of("mode", "replaceAll", "oldSource", "OldName", "source", "NewName")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertFalse(response, response.startsWith("{")); //$NON-NLS-1$
        assertTrue(response, response.contains("\nmode: replaceAll\n")); //$NON-NLS-1$
        assertTrue(response, response.contains("\nreplacements: 4\n")); //$NON-NLS-1$
        assertTrue(response, response.contains("\nreplacedLines: 2-4\n")); //$NON-NLS-1$
        assertFalse(response, response.contains("warning:")); //$NON-NLS-1$
        assertEquals("Procedure Run() Export\n\tNewName = 1; // NewName here\n" //$NON-NLS-1$
            + "\tOldNameX = NewName + XOldName;\n\tText = \"NewName\";\nEndProcedure\n", tree.text(module)); //$NON-NLS-1$
    }

    /** A Russian module and a Russian name: the boundary holds for Cyrillic letters, keywords included. */
    @Test
    public void aRussianNameIsReplacedAsAWholeWord() throws Exception
    {
        IFile module = tree.module("src/" + MODULE, BASE); //$NON-NLS-1$
        String response = write(Map.of("mode", "replaceAll", "oldSource", "Значение", "source", "Итог")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertFalse(response, response.startsWith("{")); //$NON-NLS-1$
        assertTrue(response, response.contains("\nreplacements: 1\n")); //$NON-NLS-1$
        assertTrue(response, response.contains("\nreplacedLines: \"6\"\n")); //$NON-NLS-1$
        assertEquals(BASE.replace("\tЗначение = 1;", "\tИтог = 1;"), tree.text(module)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** BSL ignores case: another spelling of the name is left, and the warning names it with its line. */
    @Test
    public void anotherCaseIsLeftAndNamedInTheWarning() throws Exception
    {
        String text = "Procedure Run()\n\tOldName = 1;\n\toldname = 2;\nEndProcedure\n"; //$NON-NLS-1$
        IFile module = tree.module("src/" + MODULE, text); //$NON-NLS-1$
        String response = write(Map.of("mode", "replaceAll", "oldSource", "OldName", "source", "NewName")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(response, response.contains("\nreplacements: 1\n")); //$NON-NLS-1$
        assertTrue(response, response.contains("1 whole-word occurrence(s) of the old text in another letter case " //$NON-NLS-1$
            + "were left (BSL ignores case): 'oldname' at line 3.")); //$NON-NLS-1$
        assertTrue(tree.text(module), tree.text(module).contains("\toldname = 2;")); //$NON-NLS-1$
    }

    @Test
    public void anEmptySourceDeletesTheOccurrences() throws Exception
    {
        IFile module = tree.module("src/" + MODULE, "Procedure Run()\n\tCall(1, Extra);\n\tCall(2, Extra);\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String response = write(Map.of("mode", "replaceAll", "oldSource", ", Extra", "source", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(response, response.contains("\nreplacements: 2\n")); //$NON-NLS-1$
        assertEquals("Procedure Run()\n\tCall(1);\n\tCall(2);\nEndProcedure\n", tree.text(module)); //$NON-NLS-1$
    }

    // ==================== nothing to replace ====================

    @Test
    public void noWholeWordOccurrenceIsRefusedAndNamesTheLongerIdentifiers() throws Exception
    {
        String text = "Procedure Run()\n\tOldNameX = 1;\nEndProcedure\n"; //$NON-NLS-1$
        IFile module = tree.module("src/" + MODULE, text); //$NON-NLS-1$
        String error = refused(write(Map.of("mode", "replaceAll", "oldSource", "OldName", "source", "NewName"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(error, error.startsWith("oldSource has no whole-word occurrence in the module. It occurs 1 " //$NON-NLS-1$
            + "time(s) inside longer identifiers (line 2)")); //$NON-NLS-1$
        assertTrue(error, error.endsWith("Nothing was written.")); //$NON-NLS-1$
        assertEquals(text, tree.text(module));
    }

    // ==================== the inherited guards ====================

    /** #987: a replaceAll that turns one declaration into the name of another creates a duplicate - refused. */
    @Test
    public void aReplaceAllThatCreatesADuplicateMethodIsRefused() throws Exception
    {
        IFile module = tree.module("src/" + MODULE, BASE); //$NON-NLS-1$
        String error = refused(write(Map.of("mode", "replaceAll", "oldSource", "Другая", "source", "ВыборкаДанных"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(error, error.startsWith("BSL duplicate method check failed. Write blocked: ")); //$NON-NLS-1$
        assertTrue(error, error.contains("'ВыборкаДанных' is declared 2 times")); //$NON-NLS-1$
        assertEquals(BASE, tree.text(module));
    }

    @Test
    public void aStaleExpectedHashIsRefused() throws Exception
    {
        IFile module = tree.module("src/" + MODULE, ENGLISH); //$NON-NLS-1$
        String error = refused(write(Map.of("mode", "replaceAll", "oldSource", "OldName", "source", "NewName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "expectedHash", "0000000000000000"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.startsWith("expectedHash does not match the current module content.")); //$NON-NLS-1$
        assertEquals(ENGLISH, tree.text(module));
    }

    @Test
    public void aMatchingExpectedHashIsAccepted() throws Exception
    {
        IFile module = tree.module("src/" + MODULE, ENGLISH); //$NON-NLS-1$
        String response = write(Map.of("mode", "replaceAll", "oldSource", "OldName", "source", "NewName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "expectedHash", readHash())); //$NON-NLS-1$
        assertTrue(response, response.contains("\nreplacements: 4\n")); //$NON-NLS-1$
        assertTrue(tree.text(module), tree.text(module).contains("\tNewName = 1;")); //$NON-NLS-1$
    }

    /** Replacing a block keyword away breaks the balance: the syntax check refuses, nothing written. */
    @Test
    public void aReplaceAllThatBreaksTheBlockBalanceIsRefused() throws Exception
    {
        IFile module = tree.module("src/" + MODULE, ENGLISH); //$NON-NLS-1$
        String error = refused(write(Map.of("mode", "replaceAll", "oldSource", "EndProcedure", "source", "End"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(error, error.startsWith("BSL syntax check failed. Write blocked.")); //$NON-NLS-1$
        assertEquals(ENGLISH, tree.text(module));
    }

    @Test
    public void aMissingModuleIsRefused() throws Exception
    {
        tree.folder("src/CommonModules/Calc"); //$NON-NLS-1$
        String error = refused(write(Map.of("mode", "replaceAll", "oldSource", "OldName", "source", "NewName"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(error, error.contains("File not found: src/" + MODULE)); //$NON-NLS-1$
        assertFalse("nothing was created", tree.handle("src/" + MODULE).exists()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Another writer adds an occurrence after the first read and before the locked write: the plan is computed again
     * under the modify rule, so all three are replaced and the count reported is the count of the text rewritten.
     */
    @Test
    public void theCountIsTheCountOfTheTextRewrittenUnderTheLock() throws Exception
    {
        String ours = "Procedure Run()\n\tOldName = 1;\n\tOldName = 2;\nEndProcedure\n"; //$NON-NLS-1$
        IFile module = tree.module("src/" + MODULE, ours); //$NON-NLS-1$
        String theirs = ours + "\nProcedure Theirs()\n\tOldName = 3;\nEndProcedure\n"; //$NON-NLS-1$
        beforeLockedWrite = () -> tree.contents.get(module).set(theirs.getBytes(StandardCharsets.UTF_8));
        String response = write(Map.of("mode", "replaceAll", "oldSource", "OldName", "source", "NewName")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(response, response.contains("\nreplacements: 3\n")); //$NON-NLS-1$
        assertTrue(response, response.contains("\nreplacedLines: \"2-3, 7\"\n")); //$NON-NLS-1$
        assertEquals(theirs.replace("OldName", "NewName"), tree.text(module)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== argument rules ====================

    @Test
    public void oldSourceIsRequired() throws Exception
    {
        tree.module("src/" + MODULE, ENGLISH); //$NON-NLS-1$
        String error = refused(write(Map.of("mode", "replaceAll", "source", "NewName"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(error, error.startsWith("oldSource is required for replaceAll mode")); //$NON-NLS-1$
    }

    @Test
    public void sourceIsRequired() throws Exception
    {
        tree.module("src/" + MODULE, ENGLISH); //$NON-NLS-1$
        String error = refused(write(Map.of("mode", "replaceAll", "oldSource", "OldName"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(error, error.startsWith("source is required")); //$NON-NLS-1$
    }

    @Test
    public void theSameTextIsNothingToReplace() throws Exception
    {
        IFile module = tree.module("src/" + MODULE, ENGLISH); //$NON-NLS-1$
        String error = refused(write(Map.of("mode", "replaceAll", "oldSource", "OldName", "source", "OldName"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertEquals("oldSource and source are the same text: nothing to replace. Nothing was written.", error); //$NON-NLS-1$
        assertEquals(ENGLISH, tree.text(module));
    }

    @Test
    public void methodNameIsRefused() throws Exception
    {
        tree.module("src/" + MODULE, ENGLISH); //$NON-NLS-1$
        String error = refused(write(Map.of("mode", "replaceAll", "oldSource", "OldName", "source", "NewName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "methodName", "Run"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.startsWith("methodName does not apply to mode=replaceAll")); //$NON-NLS-1$
    }

    @Test
    public void expectedSourceAndOverwriteAreRefused() throws Exception
    {
        tree.module("src/" + MODULE, ENGLISH); //$NON-NLS-1$
        String withExpected = refused(write(Map.of("mode", "replaceAll", "oldSource", "OldName", "source", "NewName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "expectedSource", ENGLISH))); //$NON-NLS-1$
        assertTrue(withExpected, withExpected.startsWith("expectedSource and overwrite apply only to mode=replace")); //$NON-NLS-1$
        String withOverwrite = refused(write(Map.of("mode", "replaceAll", "oldSource", "OldName", "source", "NewName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "overwrite", "true"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(withOverwrite, withOverwrite.startsWith("expectedSource and overwrite apply only to mode=replace")); //$NON-NLS-1$
    }

    @Test
    public void methodNamesAndFromModulePathAreRefused() throws Exception
    {
        tree.module("src/" + MODULE, ENGLISH); //$NON-NLS-1$
        Map<String, String> withNames = new HashMap<>(Map.of("mode", "replaceAll", "oldSource", "OldName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "source", "NewName", "methodNames", "[\"Run\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(refused(write(withNames)), refused(write(withNames)).startsWith("methodNames applies only")); //$NON-NLS-1$
        String fromModule = refused(write(Map.of("mode", "replaceAll", "fromModulePath", "CommonModules/Other/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "methodNames", "[\"Run\"]"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(fromModule, fromModule.contains("fromModulePath supports mode append")); //$NON-NLS-1$
    }

    // ==================== the wire ====================

    @Test
    public void theSchemaListsTheModeAndTheOldSourceProseNamesIt()
    {
        JsonObject properties = JsonParser.parseString(new WriteModuleSourceTool().getInputSchema()).getAsJsonObject()
            .getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue(properties.getAsJsonObject("mode").getAsJsonArray("enum").toString(), //$NON-NLS-1$ //$NON-NLS-2$
            properties.getAsJsonObject("mode").getAsJsonArray("enum").contains(new JsonPrimitive("replaceAll"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Fragment to find and replace; required for searchReplace (must match exactly once) and " //$NON-NLS-1$
            + "replaceAll (every whole-word occurrence).", //$NON-NLS-1$
            properties.getAsJsonObject("oldSource").get("description").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anUnknownModeNamesReplaceAllAmongTheAllowed() throws Exception
    {
        tree.module("src/" + MODULE, ENGLISH); //$NON-NLS-1$
        String error = refused(write(Map.of("mode", "replaceEverything", "oldSource", "OldName", "source", "NewName"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(error, error.endsWith("insertAfter, deleteMethods, replaceAll")); //$NON-NLS-1$
    }
    private static String refused(String response)
    {
        assertTrue("expected a refusal: " + response, response.startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject error = JsonParser.parseString(response).getAsJsonObject();
        assertFalse(response, error.get("success").getAsBoolean()); //$NON-NLS-1$
        return error.get("error").getAsString(); //$NON-NLS-1$
    }

    private String write(Map<String, String> extra)
    {
        Map<String, String> params = new HashMap<>(extra);
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("modulePath", MODULE); //$NON-NLS-1$
        return new WriteModuleSourceTool().execute(params);
    }

    private String readHash()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("modulePath", MODULE); //$NON-NLS-1$
        return field(new ReadModuleSourceTool().execute(params), "contentHash"); //$NON-NLS-1$
    }

    private static String field(String response, String key)
    {
        assertFalse("expected a success response: " + response, response.startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        Matcher matcher = Pattern.compile("\n" + key + ": \"?([0-9a-f]{16})\"?\n").matcher(response); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the response must carry " + key + ": " + response, matcher.find()); //$NON-NLS-1$ //$NON-NLS-2$
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
