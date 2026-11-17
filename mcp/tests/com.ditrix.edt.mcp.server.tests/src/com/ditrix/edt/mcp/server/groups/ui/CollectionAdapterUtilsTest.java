// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.groups.ui;

import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Test;

public class CollectionAdapterUtilsTest
{
    @After public void clear() { CollectionAdapterUtils.clearCaches(); }

    public static class FailingAdapter implements org.eclipse.ui.model.IWorkbenchAdapter
    {
        public String getModelObjectName() { throw new IllegalStateException("adapter failed"); }
        @Override public String getLabel(Object object) { return "Catalog Attributes"; }
        @Override public Object[] getChildren(Object object) { return new Object[0]; }
        @Override public org.eclipse.jface.resource.ImageDescriptor getImageDescriptor(Object object) { return null; }
        @Override public Object getParent(Object object) { throw new IllegalStateException("parent failed"); }
    }

    public static class FatalNameAdapter extends FailingAdapter
    {
        @Override public String getModelObjectName() { throw new AssertionError("fatal name"); }
    }

    public static class FatalParentAdapter extends FailingAdapter
    {
        @Override public String getModelObjectName() { return "Catalogs"; }
        @Override public Object getParent(Object object) { throw new AssertionError("fatal parent"); }
    }

    @Test public void invocationFailureUsesFallbackAndLogsOncePerMethod() throws Exception
    {
        var log = org.eclipse.core.runtime.Platform.getLog(
            org.osgi.framework.FrameworkUtil.getBundle(CollectionAdapterUtils.class));
        var messages = new java.util.concurrent.CopyOnWriteArrayList<String>();
        org.eclipse.core.runtime.ILogListener listener = (status, plugin) -> {
            if (status.getMessage().startsWith("Navigator adapter invocation failed:")) {
                messages.add(status.getMessage());
            }
        };
        log.addLogListener(listener);
        try {
            var adapter = new FailingAdapter();
            assertEquals("CatalogAttributes", CollectionAdapterUtils.getCollectionPath(adapter));
            assertEquals("CatalogAttributes", CollectionAdapterUtils.getCollectionPath(adapter));
            assertEquals(2, messages.size());
            assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.endsWith(".getModelObjectName")));
            assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.endsWith(".getParent")));
        } finally { log.removeLogListener(listener); }

    }

    @Test public void fatalErrorsEscapeBothInvocations()
    {
        assertThrows(AssertionError.class, () -> CollectionAdapterUtils.getModelObjectName(new FatalNameAdapter()));
        assertThrows(AssertionError.class, () -> CollectionAdapterUtils.getCollectionPath(new FatalParentAdapter()));
    }

    public static class ParentlessAdapter
    {
        public String getModelObjectName() { return "Catalogs"; }
    }

    @Test public void missingParentMethodRemainsAValidTopLevelCollection()
    {
        assertEquals("Catalogs", CollectionAdapterUtils.getCollectionPath(new ParentlessAdapter()));
    }
}
