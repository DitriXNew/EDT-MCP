package com.ditrix.edt.mcp.server.tags.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

public class TagSearchFilterTest {
    @Test
    public void failedSubsystemAccessorIsLoggedOnce() throws Exception {
        EObject subsystem = mock(EObject.class, withSettings().extraInterfaces(SubsystemAccessor.class));
        when(((SubsystemAccessor) subsystem).getSubsystems()).thenThrow(new IllegalStateException("broken accessor"));
        AtomicInteger logged = new AtomicInteger();
        ILogListener listener = (status, plugin) -> {
            if (status.getMessage().contains(subsystem.getClass().getName() + ".getSubsystems")) {
                logged.incrementAndGet();
            }
        };
        Platform.addLogListener(listener);
        try {
            TagSearchFilter filter = new TagSearchFilter();
            Method method = TagSearchFilter.class.getDeclaredMethod("hasMatchingChildSubsystemInProject",
                EObject.class, org.eclipse.core.resources.IProject.class);
            method.setAccessible(true);
            assertEquals(false, method.invoke(filter, subsystem, null));
            assertEquals(false, method.invoke(filter, subsystem, null));
            assertEquals(1, logged.get());
        } finally {
            Platform.removeLogListener(listener);
        }
    }

    @Test
    public void subsystemAccessorErrorsEscapeTheFilter() throws Exception {
        EObject subsystem = mock(EObject.class, withSettings().extraInterfaces(SubsystemAccessor.class));
        AssertionError failure = new AssertionError("subsystem failure");
        when(((SubsystemAccessor) subsystem).getSubsystems()).thenThrow(failure);
        Method method = TagSearchFilter.class.getDeclaredMethod("hasMatchingChildSubsystemInProject",
            EObject.class, org.eclipse.core.resources.IProject.class);
        method.setAccessible(true);
        InvocationTargetException escaped = assertThrows(InvocationTargetException.class,
            () -> method.invoke(new TagSearchFilter(), subsystem, null));
        assertSame(failure, escaped.getCause());
    }

    @Test
    public void nestedFolderAccessorErrorsEscapeTheFilter() throws Exception {
        ErrorFolder folder = new ErrorFolder();
        Method method = TagSearchFilter.class.getDeclaredMethod("checkNestedObjectFolder", Object.class);
        method.setAccessible(true);
        InvocationTargetException escaped = assertThrows(InvocationTargetException.class,
            () -> method.invoke(new TagSearchFilter(), folder));
        assertSame(folder.failure, escaped.getCause());
    }

    @Test
    public void absentOptionalFolderAccessorStillFallsThrough() throws Exception {
        Method method = TagSearchFilter.class.getDeclaredMethod("checkNestedObjectFolder", Object.class);
        method.setAccessible(true);
        assertNull(method.invoke(new TagSearchFilter(), new Object()));
    }

    public interface SubsystemAccessor {
        Object getSubsystems();
    }

    public static class ErrorFolder {
        final AssertionError failure = new AssertionError("folder failure");
        public Object getModel(boolean resolve) {
            throw failure;
        }
    }
}
