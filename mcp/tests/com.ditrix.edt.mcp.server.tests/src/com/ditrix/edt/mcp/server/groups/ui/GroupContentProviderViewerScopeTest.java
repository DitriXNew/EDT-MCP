/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.navigator.CommonViewer;
import org.junit.Test;

/**
 * Pins WHICH viewer gets virtual group nodes (issue #587).
 *
 * <p>The viewer binding in {@code plugin.xml} names the Navigator's viewer id, but EDT builds
 * metadata-editor pages and picker dialogs from a content service created for that SAME id, driving
 * it with a UI-less viewer instead of the view. A group node delivered into one of those trees
 * reaches platform tree filters that cast every child to a metadata type - the register editor's
 * Recorders tab casts to {@code Document} and threw {@code ClassCastException} in the SWT event
 * loop. The viewer id therefore cannot tell the two apart; the viewer can.</p>
 */
public class GroupContentProviderViewerScopeTest
{
    /** A viewer that is not the Navigator's - the shape EDT's UI-less stand-in has. */
    private static final class ForeignViewer
        extends Viewer
    {
        @Override
        public Control getControl()
        {
            return null;
        }

        @Override
        public Object getInput()
        {
            return null;
        }

        @Override
        public ISelection getSelection()
        {
            return StructuredSelection.EMPTY;
        }

        @Override
        public void refresh()
        {
            // nothing to show
        }

        @Override
        public void setInput(Object input)
        {
            // nothing to show
        }

        @Override
        public void setSelection(ISelection selection, boolean reveal)
        {
            // nothing to select
        }
    }

    @Test
    public void testNavigatorViewerIsServed()
    {
        assertTrue("The Navigator view's own viewer is the one place group nodes belong", //$NON-NLS-1$
            GroupContentProvider.isNavigatorViewer(CommonViewer.class));
    }

    @Test
    public void testOtherViewersAreNotServed()
    {
        assertFalse("A plain TreeViewer is a dialog or an editor page, not the Navigator view", //$NON-NLS-1$
            GroupContentProvider.isNavigatorViewer(TreeViewer.class));
        assertFalse("EDT drives its editor-page trees with an AbstractTreeViewer stand-in", //$NON-NLS-1$
            GroupContentProvider.isNavigatorViewer(AbstractTreeViewer.class));
        assertFalse("A StructuredViewer alone says nothing about being the Navigator", //$NON-NLS-1$
            GroupContentProvider.isNavigatorViewer(StructuredViewer.class));
        assertFalse("No viewer at all is not the Navigator view either", //$NON-NLS-1$
            GroupContentProvider.servesNavigatorView(null));
    }

    /**
     * The gate is closed until {@code inputChanged} names the viewer, so a provider asked for
     * children before it knows where it is contributes nothing.
     */
    @Test
    public void testProviderYieldsUntilItsViewerIsKnown()
    {
        GroupContentProvider provider = new GroupContentProvider();

        assertEquals("No children before the viewer is known", //$NON-NLS-1$
            0, provider.getChildren(new Object()).length);
        assertFalse("No children before the viewer is known", //$NON-NLS-1$
            provider.hasChildren(new Object()));
    }

    @Test
    public void testForeignViewerClosesTheGate()
    {
        GroupContentProvider provider = new GroupContentProvider();
        provider.inputChanged(new ForeignViewer(), null, new Object());

        assertEquals("An editor-page tree must not receive group nodes", //$NON-NLS-1$
            0, provider.getChildren(new Object()).length);
        assertFalse("An editor-page tree must not receive group nodes", //$NON-NLS-1$
            provider.hasChildren(new Object()));
    }
}
