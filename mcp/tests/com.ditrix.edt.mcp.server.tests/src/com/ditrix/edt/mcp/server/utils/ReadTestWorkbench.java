// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import java.lang.reflect.Field;
import org.eclipse.swt.widgets.Display;

/** Reuses the canonical-path regression harness's workbench holder, without creating SWT windows. */
final class ReadTestWorkbench implements AutoCloseable
{
    private final Field instance;
    private final Object previous;
    final Display display;

    ReadTestWorkbench() throws Exception
    {
        Class<?> type = Class.forName("org.eclipse.ui.internal.Workbench");
        instance = type.getDeclaredField("instance"); instance.setAccessible(true);
        previous = instance.get(null);
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
        Object holder = unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
        display = mock(Display.class);
        Field displayField = type.getDeclaredField("display"); displayField.setAccessible(true);
        displayField.set(holder, display);
        doAnswer(call -> { ((Runnable)call.getArgument(0)).run(); return null; }).when(display).syncExec(any());
        doAnswer(call -> { ((Runnable)call.getArgument(0)).run(); return null; }).when(display).asyncExec(any());
        instance.set(null, holder);
    }

    @Override public void close() throws Exception { instance.set(null, previous); }
}
