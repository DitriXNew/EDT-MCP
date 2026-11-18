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
import com.ditrix.edt.mcp.server.protocol.ToolArgumentValidator;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * #978: {@code write_module_source} copies named methods from another module of the same project
 * ({@code fromModulePath} + {@code methodNames}) and deletes named methods ({@code mode=deleteMethods}); a move is a
 * copy and a deletion bound by the source's token ({@code fromContentHash} as the deletion's {@code expectedHash}).
 * <p>
 * Runs {@code execute()} against the in-memory resource tree of {@link WriteModuleSourceContentHashTest}, so the
 * bytes on "disk" are exactly the bytes the tool writes and the read tool reads back.
 */
public class WriteModuleSourceMoveMethodsTest
{
    private static final String PROJECT = "MoveFixture"; //$NON-NLS-1$
    private static final String SOURCE = "CommonModules/Calc/Module.bsl"; //$NON-NLS-1$
    private static final String TARGET = "CommonModules/Target/Module.bsl"; //$NON-NLS-1$
    private static final String BOM = "\uFEFF"; //$NON-NLS-1$

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

    // ==================== schema ====================

    /** source is no longer always required: a copy and a deletion replace it; projectName alone is required. */
    @Test
    public void theSchemaDeclaresTheCopyAndDeleteParametersAndRequiresOnlyTheProject()
    {
        JsonObject schema = JsonParser.parseString(new WriteModuleSourceTool().getInputSchema()).getAsJsonObject();
        assertEquals("[\"projectName\"]", schema.get("required").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        assertEquals("string", properties.getAsJsonObject("fromModulePath").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("array", properties.getAsJsonObject("methodNames").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(properties.getAsJsonObject("mode").get("enum").toString().contains("\"deleteMethods\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The tools/call argument check (#976) lets every call shape of a copy and a deletion through - no source,
     * methodNames as a JSON array, as JSON text or as a comma list, mode=deleteMethods - and refuses what the
     * schema rules out: an unknown mode and a non-string name.
     */
    @Test
    public void theArgumentCheckAcceptsTheCopyAndDeleteShapesAndRefusesTheWrongOnes()
    {
        String schema = new WriteModuleSourceTool().getInputSchema();
        assertNull(ToolArgumentValidator.validate(WriteModuleSourceTool.NAME, schema, Map.of("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", TARGET, "fromModulePath", SOURCE, "methodNames", List.of("Add", "Маркер")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertNull(ToolArgumentValidator.validate(WriteModuleSourceTool.NAME, schema, Map.of("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", SOURCE, "mode", "deleteMethods", "methodNames", "[\"Add\"]", "expectedHash", "0123456789abcdef"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        assertNull(ToolArgumentValidator.validate(WriteModuleSourceTool.NAME, schema, Map.of("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", TARGET, "mode", "insertAfter", "methodName", "A", "fromModulePath", SOURCE, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "methodNames", "Add, Test"))); //$NON-NLS-1$ //$NON-NLS-2$
        String mode = ToolArgumentValidator.validate(WriteModuleSourceTool.NAME, schema, Map.of("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", SOURCE, "mode", "deleteMethod", "methodNames", List.of("Add"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNotNull(mode);
        assertTrue(mode, mode.contains("'deleteMethods'")); //$NON-NLS-1$
        String item = ToolArgumentValidator.validate(WriteModuleSourceTool.NAME, schema, Map.of("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", TARGET, "fromModulePath", SOURCE, "methodNames", List.of(Map.of("name", "Add")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertNotNull(item);
        assertTrue(item, item.contains("methodNames[0]")); //$NON-NLS-1$
    }

    // ==================== copy ====================

    /**
     * A copy from a CRLF module with a BOM into an LF module: the method's text arrives line for line, in the
     * TARGET's line delimiter; the source is not touched; the answer carries the source's token (the one
     * read_module_source reports for it) and the target's.
     */
    @Test
    public void aCopyAppendsTheMethodInTheTargetsDelimiterAndAnswersBothTokens() throws Exception
    {
        String sourceText = BOM + "// Adds.\r\nFunction Add(A, B) Export\r\n\tReturn A + B;  \r\nEndFunction\r\n\r\n" //$NON-NLS-1$
            + "Procedure Test() Export\r\n\tResult = Add(1, 2);\r\nEndProcedure\r\n"; //$NON-NLS-1$
        IFile source = tree.module("src/" + SOURCE, sourceText); //$NON-NLS-1$
        IFile target = tree.module("src/" + TARGET, "Procedure Existing()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String sourceHash = readHash(SOURCE);

        String response = write(TARGET, Map.of("fromModulePath", SOURCE, "methodNames", "[\"add\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("Procedure Existing()\nEndProcedure\n\n// Adds.\nFunction Add(A, B) Export\n\tReturn A + B;  \n" //$NON-NLS-1$
            + "EndFunction\n", tree.text(target)); //$NON-NLS-1$
        assertEquals("the source is only read", sourceText, tree.text(source)); //$NON-NLS-1$
        assertTrue(response, response.contains("\nmode: append\n")); //$NON-NLS-1$
        assertTrue(response, response.contains("\ncopiedMethods: [\"Add\"]\n")); //$NON-NLS-1$
        assertTrue(response, response.contains("\nfromModulePath: " + SOURCE + "\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(sourceHash, field(response, "fromContentHash")); //$NON-NLS-1$
        assertEquals(readHash(TARGET), field(response, "contentHash")); //$NON-NLS-1$
        assertTrue(response, response.contains("expectedHash=" + sourceHash)); //$NON-NLS-1$
    }

    /** A copy is existing code moved as it is: the look-alike characters a source write normalizes stay. */
    @Test
    public void aCopyDoesNotNormalizeTheCopiedText() throws Exception
    {
        tree.module("src/" + SOURCE, "Procedure P()\n\tX = \"a–b c\";\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        IFile target = tree.module("src/" + TARGET, "Procedure Existing()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String response = write(TARGET, Map.of("fromModulePath", SOURCE, "methodNames", "[\"P\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(response, response.contains("normalizedCharacters")); //$NON-NLS-1$
        assertTrue(tree.text(target), tree.text(target).contains("\"a–b c\"")); //$NON-NLS-1$
    }

    /** replace creates a module that does not exist yet from the copied methods alone, in request order. */
    @Test
    public void aCopyWithReplaceCreatesANewModule() throws Exception
    {
        tree.module("src/" + SOURCE, "Function Add(A, B) Export\n\tReturn A + B;\nEndFunction\n\n" //$NON-NLS-1$
            + "Procedure Test() Export\nEndProcedure\n"); //$NON-NLS-1$
        tree.folder("src/CommonModules/Target"); //$NON-NLS-1$
        String response = write(TARGET, Map.of("mode", "replace", "fromModulePath", SOURCE, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "methodNames", "[\"Test\", \"Add\"]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(response, response.contains("\nnewFile: true\n")); //$NON-NLS-1$
        String text = tree.text(tree.handle("src/" + TARGET)).replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(BOM + "Procedure Test() Export\nEndProcedure\n\nFunction Add(A, B) Export\n\tReturn A + B;\n" //$NON-NLS-1$
            + "EndFunction\n", text); //$NON-NLS-1$
        assertTrue(response, response.contains("\ncopiedMethods: [\"Test\", \"Add\"]\n")); //$NON-NLS-1$
    }

    /** replace over an EXISTING target would discard it: a copy refuses it. */
    @Test
    public void aCopyWithReplaceOverAnExistingModuleIsRefused() throws Exception
    {
        tree.module("src/" + SOURCE, "Procedure P()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        IFile target = tree.module("src/" + TARGET, "Procedure Existing()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String error = refused(write(TARGET, Map.of("mode", "replace", "overwrite", "true", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "fromModulePath", SOURCE, "methodNames", "[\"P\"]"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(error, error.contains("already exists")); //$NON-NLS-1$
        assertEquals("Procedure Existing()\nEndProcedure\n", tree.text(target)); //$NON-NLS-1$
    }

    /** insertAfter takes the target's anchor and its expectedHash, like any method-targeted insert. */
    @Test
    public void aCopyInsertsAfterAnAnchorOfTheTarget() throws Exception
    {
        tree.module("src/" + SOURCE, "Procedure P()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        IFile target = tree.module("src/" + TARGET, "Procedure A()\nEndProcedure\n\nProcedure Z()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        write(TARGET, Map.of("mode", "insertAfter", "methodName", "A", "expectedHash", readHash(TARGET), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "fromModulePath", SOURCE, "methodNames", "[\"P\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Procedure A()\nEndProcedure\n\nProcedure P()\nEndProcedure\n\nProcedure Z()\nEndProcedure\n", //$NON-NLS-1$
            tree.text(target));
    }

    /** The Russian name is the name: a Russian method is found and copied by it, ignoring case. */
    @Test
    public void aRussianMethodIsCopiedByItsRussianName() throws Exception
    {
        tree.module("src/" + SOURCE, "Функция Маркер() Экспорт\n\tВозврат 1;\nКонецФункции\n"); //$NON-NLS-1$ //$NON-NLS-2$
        IFile target = tree.module("src/" + TARGET, "Процедура Есть()\nКонецПроцедуры\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String response = write(TARGET, Map.of("fromModulePath", SOURCE, "methodNames", "[\"маркер\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(response, response.contains("copiedMethods: [\"Маркер\"]")); //$NON-NLS-1$
        assertTrue(tree.text(target), tree.text(target).endsWith("\n\nФункция Маркер() Экспорт\n\tВозврат 1;\nКонецФункции\n")); //$NON-NLS-1$
    }

    /** A repeated copy is refused - the target already declares the method - and changes nothing. */
    @Test
    public void aRepeatedCopyIsRefused() throws Exception
    {
        tree.module("src/" + SOURCE, "Procedure P()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        IFile target = tree.module("src/" + TARGET, "Procedure Existing()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        write(TARGET, Map.of("fromModulePath", SOURCE, "methodNames", "[\"P\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String once = tree.text(target);
        String error = refused(write(TARGET, Map.of("fromModulePath", SOURCE, "methodNames", "[\"P\"]"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(error, error.contains("already declares it")); //$NON-NLS-1$
        assertEquals(once, tree.text(target));
    }

    /** A missing name refuses the whole copy: nothing is written, the others are not copied either. */
    @Test
    public void aMissingNameRefusesTheWholeCopy() throws Exception
    {
        tree.module("src/" + SOURCE, "Procedure P()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        IFile target = tree.module("src/" + TARGET, "Procedure Existing()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String error = refused(write(TARGET, Map.of("fromModulePath", SOURCE, "methodNames", "[\"P\", \"Nope\"]"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(error, error.contains("'Nope' not found")); //$NON-NLS-1$
        assertEquals("Procedure Existing()\nEndProcedure\n", tree.text(target)); //$NON-NLS-1$
    }

    /** The argument refusals, each before anything is read or written. */
    @Test
    public void theArgumentRulesOfACopyAndADeletion() throws Exception
    {
        tree.module("src/" + SOURCE, "Procedure P()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        IFile target = tree.module("src/" + TARGET, "Procedure Existing()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String names = "[\"P\"]"; //$NON-NLS-1$
        assertRefused("alternatives", Map.of("fromModulePath", SOURCE, "methodNames", names, "source", "x")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertRefused("takes no source", Map.of("mode", "deleteMethods", "methodNames", names, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "expectedHash", readHash(TARGET), "source", "x")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused("got 'replaceMethod'", Map.of("mode", "replaceMethod", "methodName", "Existing", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "expectedHash", readHash(TARGET), "fromModulePath", SOURCE, "methodNames", names)); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("got 'deleteMethods'", Map.of("mode", "deleteMethods", "expectedHash", readHash(TARGET), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "fromModulePath", SOURCE, "methodNames", names)); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("methodNames applies only", Map.of("mode", "append", "source", "// x\n", "methodNames", names)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertRefused("methodNames is required", Map.of("fromModulePath", SOURCE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("twice", Map.of("fromModulePath", SOURCE, "methodNames", "[\"P\", \"p\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertRefused("expectedHash is required for mode=deleteMethods", //$NON-NLS-1$
            Map.of("mode", "deleteMethods", "methodNames", "[\"Existing\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertRefused("must not contain '..'", Map.of("fromModulePath", "CommonModules/../Calc/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "methodNames", names)); //$NON-NLS-1$
        assertRefused(".bsl module file", Map.of("fromModulePath", "CommonModules/Calc/Module.mdo", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "methodNames", names)); //$NON-NLS-1$
        assertRefused("target module itself", Map.of("fromModulePath", TARGET, "methodNames", "[\"Existing\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertRefused("fromModulePath not found", Map.of("fromModulePath", "CommonModules/Missing/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "methodNames", names)); //$NON-NLS-1$
        assertRefused("source is required", Map.of("mode", "append")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("no refusal touched the target", "Procedure Existing()\nEndProcedure\n", tree.text(target)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== delete and the move ====================

    /**
     * The move: copy, then delete from the source with expectedHash=fromContentHash. The deletion removes the
     * method with its preamble, collapses the seam, keeps the source's CRLF and BOM, and warns that the method
     * is still called there.
     */
    @Test
    public void aMoveIsACopyAndADeletionBoundByTheSourceToken() throws Exception
    {
        IFile source = tree.module("src/" + SOURCE, BOM + "// Adds.\r\nFunction Add(A, B) Export\r\n\tReturn A + B;\r\n" //$NON-NLS-1$
            + "EndFunction\r\n\r\nProcedure Test() Export\r\n\tResult = Add(1, 2);\r\nEndProcedure\r\n"); //$NON-NLS-1$
        tree.module("src/" + TARGET, "Procedure Existing()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String copied = write(TARGET, Map.of("fromModulePath", SOURCE, "methodNames", "[\"Add\"]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String token = field(copied, "fromContentHash"); //$NON-NLS-1$

        String deleted = write(SOURCE, Map.of("mode", "deleteMethods", "methodNames", "[\"Add\"]", "expectedHash", token)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals(BOM + "Procedure Test() Export\r\n\tResult = Add(1, 2);\r\nEndProcedure\r\n", tree.text(source)); //$NON-NLS-1$
        assertTrue(deleted, deleted.contains("\ndeletedMethods: [\"Add\"]\n")); //$NON-NLS-1$
        assertTrue(deleted, deleted.contains("still called")); //$NON-NLS-1$
        assertTrue(deleted, deleted.contains("Add (line 2)")); //$NON-NLS-1$
        assertTrue(deleted, deleted.contains("text check")); //$NON-NLS-1$
        assertEquals(readHash(SOURCE), field(deleted, "contentHash")); //$NON-NLS-1$
    }

    /** Once the source changes after the copy, the deletion carrying the copy's token is refused. */
    @Test
    public void theDeletionIsRefusedOnceTheSourceChangedAfterTheCopy() throws Exception
    {
        IFile source = tree.module("src/" + SOURCE, "Procedure P()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        tree.module("src/" + TARGET, "Procedure Existing()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String token = field(write(TARGET, Map.of("fromModulePath", SOURCE, "methodNames", "[\"P\"]")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "fromContentHash"); //$NON-NLS-1$
        String changed = "Procedure P()\n\tX = 1;\nEndProcedure\n"; //$NON-NLS-1$
        tree.contents.get(source).set(changed.getBytes(StandardCharsets.UTF_8));
        String error = refused(write(SOURCE, Map.of("mode", "deleteMethods", "methodNames", "[\"P\"]", "expectedHash", token))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(error, error.contains("expectedHash does not match")); //$NON-NLS-1$
        assertEquals(changed, tree.text(source));
    }

    /** A deleted method no one calls answers without a warning; a missing name deletes nothing. */
    @Test
    public void aDeletionWithoutRemainingCallsHasNoWarningAndAMissingNameDeletesNothing() throws Exception
    {
        IFile module = tree.module("src/" + SOURCE, "Procedure A()\nEndProcedure\n\nProcedure B()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String error = refused(write(SOURCE, Map.of("mode", "deleteMethods", "methodNames", "[\"A\", \"Z\"]", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "expectedHash", readHash(SOURCE)))); //$NON-NLS-1$
        assertTrue(error, error.contains("'Z' not found")); //$NON-NLS-1$
        assertEquals("Procedure A()\nEndProcedure\n\nProcedure B()\nEndProcedure\n", tree.text(module)); //$NON-NLS-1$
        String response = write(SOURCE, Map.of("mode", "deleteMethods", "methodNames", "[\"A\"]", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "expectedHash", readHash(SOURCE))); //$NON-NLS-1$
        assertFalse(response, response.contains("warning")); //$NON-NLS-1$
        assertEquals("Procedure B()\nEndProcedure\n", tree.text(module)); //$NON-NLS-1$
    }

    /** The replaceMethod kind refusal names the mode that really deletes a method. */
    @Test
    public void theKindChangeRefusalNamesDeleteMethods()
    {
        WriteModuleSourceTool.MethodEditResult result = WriteModuleSourceTool.applyMethodTargetedEdit(
            List.of("Function F()", "EndFunction"), "replaceMethod", "F", "Procedure F()\nEndProcedure\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("mode=deleteMethods")); //$NON-NLS-1$
        assertFalse(result.error, result.error.contains("delete plus insert")); //$NON-NLS-1$
    }

    private void assertRefused(String fragment, Map<String, String> extra)
    {
        String error = refused(write(TARGET, extra));
        assertTrue(fragment + " expected in: " + error, error.contains(fragment)); //$NON-NLS-1$
    }

    private static String refused(String response)
    {
        assertTrue("expected a refusal: " + response, response.startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject error = JsonParser.parseString(response).getAsJsonObject();
        assertFalse(response, error.get("success").getAsBoolean()); //$NON-NLS-1$
        return error.get("error").getAsString(); //$NON-NLS-1$
    }

    private String write(String modulePath, Map<String, String> extra)
    {
        Map<String, String> params = new HashMap<>(extra);
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("modulePath", modulePath); //$NON-NLS-1$
        return new WriteModuleSourceTool().execute(params);
    }

    private String readHash(String modulePath)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("modulePath", modulePath); //$NON-NLS-1$
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
