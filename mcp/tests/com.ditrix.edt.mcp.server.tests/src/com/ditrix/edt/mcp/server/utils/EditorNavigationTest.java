/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.Document;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.FormType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

public class EditorNavigationTest
{
    private static final String MODULE = "CommonModules/Calc/Module.bsl";

    private static IFile diskFile(String content) throws Exception
    {
        IFile file = mock(IFile.class);
        when(file.getContents()).thenAnswer(invocation ->
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        when(file.getCharset()).thenReturn("UTF-8");
        return file;
    }

    private static ITextEditor editor(Document document)
    {
        ITextEditor editor = mock(ITextEditor.class);
        IEditorInput input = mock(IEditorInput.class);
        IDocumentProvider provider = mock(IDocumentProvider.class);
        when(editor.getEditorInput()).thenReturn(input);
        when(editor.getDocumentProvider()).thenReturn(provider);
        when(provider.getDocument(input)).thenReturn(document);
        return editor;
    }

    @Test
    public void unsavedEnglishMethodIsNavigable() throws Exception
    {
        assertUnsavedMethod("Procedure NewMethod()\nEndProcedure", "newmethod");
    }

    @Test
    public void unsavedRussianMethodIsNavigable() throws Exception
    {
        assertUnsavedMethod("\u041f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u0430 \u0420\u0430\u0441\u0447\u0435\u0442()\n"
            + "\u041a\u043e\u043d\u0435\u0446\u041f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u044b", "\u0440\u0430\u0441\u0447\u0435\u0442");
    }

    private static void assertUnsavedMethod(String method, String name) throws Exception
    {
        String saved = "// saved";
        Document document = new Document(saved);
        document.replace(document.getLength(), 0, "\n" + method);
        ITextEditor editor = editor(document);
        IFile file = diskFile(saved);
        EditorNavigation.openModule(new EditorNavigation.Request("Project", null, MODULE, null, name),
            file, openedFile -> { assertSame(file, openedFile); return editor; });
        verify(editor).selectAndReveal(saved.length() + 1, 0);
        verify(file, never()).getContents();
    }

    @Test
    public void unsavedAdditionalLineIsNavigable() throws Exception
    {
        Document document = new Document("// saved");
        document.replace(document.getLength(), 0, "\n// unsaved");
        ITextEditor editor = editor(document);
        EditorNavigation.openModule(new EditorNavigation.Request("Project", null, MODULE, 2, null),
            diskFile("// saved"), openedFile -> editor);
        verify(editor).selectAndReveal(9, 0);
    }

    @Test
    public void savedTrailingEmptyLineIsNavigable() throws Exception
    {
        for (String delimiter : List.of("\n", "\r\n", "\r"))
        {
            String saved = "// saved" + delimiter;
            Document document = new Document(saved);
            assertEquals(2, document.getNumberOfLines());
            assertEquals(saved.length(), document.getLineOffset(1));
            IFile file = diskFile(saved);
            assertEquals("Disk line reader omits the final empty document line", 1,
                BslModuleUtils.readFileLines(file).size());
            ITextEditor editor = editor(document);
            EditorNavigation.openModule(new EditorNavigation.Request("Project", null, MODULE, 2, null),
                file, openedFile -> editor);
            verify(editor).selectAndReveal(saved.length(), 0);
        }
    }

    @Test
    public void savedLineStillNavigates() throws Exception
    {
        ITextEditor editor = editor(new Document("// saved"));
        EditorNavigation.openModule(new EditorNavigation.Request("Project", null, MODULE, 1, null),
            diskFile("// saved"), openedFile -> editor);
        verify(editor).selectAndReveal(0, 0);
    }

    @Test
    public void methodMovedUpInShorterDocumentIsNavigable() throws Exception
    {
        String method = "Procedure Existing()\nEndProcedure";
        Document document = new Document(method);
        ITextEditor editor = editor(document);
        EditorNavigation.openModule(new EditorNavigation.Request("Project", null, MODULE, null, "Existing"),
            diskFile("// removed\n// removed\n" + method), openedFile -> editor);
        verify(editor).selectAndReveal(0, 0);
    }

    @Test
    public void lineOnlyOnDiskIsRejected() throws Exception
    {
        ITextEditor editor = editor(new Document("// live"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> EditorNavigation.openModule(new EditorNavigation.Request("Project", null, MODULE, 2, null),
                diskFile("// saved\n// removed"), openedFile -> editor));
        assertTrue(error.getMessage().contains("Invalid line '2'"));
        verify(editor, never()).selectAndReveal(anyInt(), anyInt());
    }

    @Test
    public void methodOnlyOnDiskIsRejected() throws Exception
    {
        ITextEditor editor = editor(new Document("// removed"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> EditorNavigation.openModule(new EditorNavigation.Request("Project", null, MODULE, null, "Old"),
                diskFile("Procedure Old()\nEndProcedure"), openedFile -> editor));
        assertTrue(error.getMessage().contains("Method 'Old' not found"));
        verify(editor, never()).selectAndReveal(anyInt(), anyInt());
    }

    @Test
    public void emptyDocumentDefaultsToFirstLine() throws Exception
    {
        ITextEditor editor = editor(new Document(""));
        EditorNavigation.openModule(new EditorNavigation.Request("Project", null, MODULE, null, null),
            diskFile(""), openedFile -> editor);
        verify(editor).selectAndReveal(0, 0);
    }

    @Test
    public void unavailableDocumentHasActionableError() throws Exception
    {
        ITextEditor editor = editor(null);
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> EditorNavigation.openModule(new EditorNavigation.Request("Project", null, MODULE, 1, null),
                diskFile("// saved"), openedFile -> editor));
        assertTrue(error.getMessage().contains(MODULE));
        assertTrue(error.getMessage().contains("Reopen its BSL editor"));
        verify(editor, never()).selectAndReveal(anyInt(), anyInt());
    }

    private static Catalog catalog(Configuration configuration, String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        catalog.getSynonym().put("en", "Product list");
        configuration.getCatalogs().add(catalog);
        return catalog;
    }

    @Test
    public void englishAndRussianTypesNamesAndMembersReachUi()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog english = catalog(configuration, "Products");
        Catalog russian = catalog(configuration, "\u0422\u043e\u0432\u0430\u0440\u044b");
        CatalogAttribute price = MdClassFactory.eINSTANCE.createCatalogAttribute();
        price.setName("\u0426\u0435\u043d\u0430");
        russian.getAttributes().add(price);
        AtomicReference<MdObject> opened = new AtomicReference<>();
        EditorNavigation.Editors editors = object -> { opened.set(object); return true; };
        MetadataScope scope = MetadataScope.ofConfiguration(configuration);

        EditorNavigation.openMetadata(scope, "Catalog.Products", editors);
        assertSame(english, opened.get());
        EditorNavigation.openMetadata(scope, "Catalog.\u0422\u043e\u0432\u0430\u0440\u044b", editors);
        assertSame(russian, opened.get());
        EditorNavigation.openMetadata(scope,
            "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.\u0422\u043e\u0432\u0430\u0440\u044b."
                + "\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442.\u0426\u0435\u043d\u0430", editors);
        assertSame("Pass the member to EDT so its native parent editor selects it", price, opened.get());
        assertThrows(IllegalArgumentException.class,
            () -> EditorNavigation.openMetadata(scope, "Catalog.Product list", object -> {
                fail("A synonym must not resolve"); return false;
            }));
    }

    @Test
    public void managedFormReachesNativeEditor()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog owner = catalog(configuration, "Products");
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("Item");
        form.setFormType(FormType.MANAGED);
        owner.getForms().add(form);
        AtomicReference<MdObject> opened = new AtomicReference<>();
        EditorNavigation.openMetadata(MetadataScope.ofConfiguration(configuration), "Catalog.Products.Form.Item",
            object -> { opened.set(object); return true; });
        assertSame(form, opened.get());
    }

    @Test
    public void unsupportedFormNeverReachesUi()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog owner = catalog(configuration, "Products");
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("Item");
        form.setFormType(FormType.ORDINARY);
        owner.getForms().add(form);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> EditorNavigation.openMetadata(MetadataScope.ofConfiguration(configuration),
                "Catalog.Products.Form.Item", object -> { fail("Unsupported form"); return false; }));
        assertTrue(error.getMessage().contains("Catalog.Products.Form.Item"));
        assertTrue(error.getMessage().contains("Select a managed form"));
    }

    @Test
    public void missingTargetNeverReachesUi()
    {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> EditorNavigation.openMetadata(
                MetadataScope.ofConfiguration(MdClassFactory.eINSTANCE.createConfiguration()),
                "Catalog.Missing", object -> { fail("Missing target"); return false; }));
        assertTrue(error.getMessage().contains("Catalog.Missing"));
        assertTrue(error.getMessage().contains("get_metadata_objects"));
    }

    @Test
    public void nativeFailureIsNotSuccess()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        catalog(configuration, "Products");
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> EditorNavigation.openMetadata(MetadataScope.ofConfiguration(configuration),
                "Catalog.Products", object -> false));
        assertTrue(error.getMessage().contains("EDT could not open"));
    }

    @Test
    public void lineDefaultsAndBoundaries()
    {
        List<String> lines = List.of("// first", "// second");
        assertEquals(1, EditorNavigation.resolveLine(lines, null, null));
        assertEquals(2, EditorNavigation.resolveLine(lines, 2, null));
        assertEquals(1, EditorNavigation.resolveLine(List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> EditorNavigation.resolveLine(lines, 0, null));
        assertThrows(IllegalArgumentException.class, () -> EditorNavigation.resolveLine(lines, 3, null));
    }

    @Test
    public void englishAndRussianMethodsSelectDeclarationInsteadOfComments()
    {
        List<String> lines = List.of("// Procedure Wrong()", "// documentation", "Procedure Calculate()",
            "EndProcedure", "\u041f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u0430 \u0420\u0430\u0441\u0447\u0435\u0442()",
            "\u041a\u043e\u043d\u0435\u0446\u041f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u044b");
        assertEquals(3, EditorNavigation.resolveLine(lines, null, "calculate"));
        assertEquals(5, EditorNavigation.resolveLine(lines, null, "\u0420\u0430\u0441\u0447\u0435\u0442"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> EditorNavigation.resolveLine(lines, null, "Wrong"));
        assertTrue(error.getMessage().contains("Wrong"));
        assertTrue(error.getMessage().contains("get_module_structure"));
    }
}
