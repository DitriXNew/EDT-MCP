/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IProject;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/** A global-context copy must never save an unrelated user's dirty editor implicitly. */
public final class MetadataCloneEditors
{
    private MetadataCloneEditors()
    {
    }

    public static void requireClean(IProject project)
    {
        if (!PlatformUI.isWorkbenchRunning())
        {
            return;
        }
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference ref : page.getEditorReferences())
                {
                    IEditorPart editor = ref.getEditor(false);
                    if (editor != null && editor.isDirty())
                    {
                        IResource resource = editor.getEditorInput().getAdapter(IResource.class);
                        if (resource == null || project.equals(resource.getProject()))
                        {
                            throw new IllegalArgumentException("Unsaved editor '" + editor.getTitle()
                                + "' may affect cloning in project '" + project.getName()
                                + "'. Save or close it, wait for export, then preview again.");
                        }
                    }
                }
            }
        }
    }
}
