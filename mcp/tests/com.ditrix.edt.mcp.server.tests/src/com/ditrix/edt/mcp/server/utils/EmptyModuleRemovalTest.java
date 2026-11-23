// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.junit.Test;

public class EmptyModuleRemovalTest
{
    @Test
    public void acceptsEmptyAndWhitespaceButChecksTheRevision()
    {
        EmptyModuleRemoval.requireEmptyRevision("", ContentHash.of(""));
        EmptyModuleRemoval.requireEmptyRevision(" \t\r\n", ContentHash.of(" \t\n"));
        var e = assertThrows(IllegalStateException.class,
            () -> EmptyModuleRemoval.requireEmptyRevision(" ", ContentHash.of("")));
        assertTrue(e.getMessage().contains("expectedHash does not match"));
    }

    @Test
    public void doesNotConsiderCommentsDirectivesOrCodeEmpty()
    {
        for (String text : new String[] { "// only a comment\n", "#Region Named\n#EndRegion\n",
            "Procedure Keep() Export\nEndProcedure\n" })
        {
            var e = assertThrows(IllegalArgumentException.class,
                () -> EmptyModuleRemoval.requireEmptyRevision(text, ContentHash.of(text)));
            assertTrue(e.getMessage().contains("only removes a blank module"));
        }
    }

    @Test
    public void cleanTypedFileEditorBlocksDeletionWithoutAResourceAdapter() throws Exception
    {
        IFile module = mock(IFile.class);
        IFile owner = mock(IFile.class);
        IFileEditorInput input = mock(IFileEditorInput.class);
        when(input.getFile()).thenReturn(module);
        IEditorReference editor = editor(input);
        assertBlocked(editor, module, owner);
    }

    @Test
    public void cleanOwnerEditorBlocksWhenItAdaptsOnlyToIFile() throws Exception
    {
        IFile module = mock(IFile.class);
        IFile owner = mock(IFile.class);
        IEditorInput input = mock(IEditorInput.class);
        when(input.getAdapter(IFile.class)).thenReturn(owner);
        assertBlocked(editor(input), module, owner);
    }

    @Test
    public void resourceAdapterStillIdentifiesTheOwnerAndUnrelatedFilesDoNotBlock() throws Exception
    {
        IFile module = mock(IFile.class);
        IFile owner = mock(IFile.class);
        IEditorInput input = mock(IEditorInput.class);
        when(input.getAdapter(IResource.class)).thenReturn(owner);
        assertBlocked(editor(input), module, owner);
        IFile unrelated = mock(IFile.class);
        when(input.getAdapter(IResource.class)).thenReturn(unrelated);
        EmptyModuleRemoval.Windowed.requireEditorDoesNotBlock(editor(input), module, owner);
    }

    @Test
    public void unidentifiableDirtyEditorStillBlocksDeletion() throws Exception
    {
        IEditorReference editor = editor(null);
        when(editor.isDirty()).thenReturn(true);
        assertBlocked(editor, mock(IFile.class), mock(IFile.class));
    }

    private static IEditorReference editor(IEditorInput input) throws Exception
    {
        IEditorReference editor = mock(IEditorReference.class);
        when(editor.getEditorInput()).thenReturn(input);
        return editor;
    }

    private static void assertBlocked(IEditorReference editor, IFile module, IFile owner)
    {
        var error = assertThrows(IllegalStateException.class,
            () -> EmptyModuleRemoval.Windowed.requireEditorDoesNotBlock(editor, module, owner));
        assertTrue(error.getMessage().contains("Close the module and its owning object's editors")); //$NON-NLS-1$
    }
}
