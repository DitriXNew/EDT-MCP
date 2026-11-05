// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertTrue;

import java.util.IdentityHashMap;
import java.util.Map;

import com._1c.g5.v8.bm.core.IBmObject;

/** Checks values, records, containers and detached EMF references; not closures or native commands. */
public final class ReadBoundaryAssertions
{
    private ReadBoundaryAssertions() { }

    public static void assertNoLiveBmValue(Object value)
    {
        check(value, new IdentityHashMap<>());
    }

    public static void assertPreparationResult(String taskName, Object value)
    {
        // Native form rename builds the workspace outside BM; its target rebinds on access.
        if ("Prepare form element rename".equals(taskName))
        {
            assertTrue("Only the direct form rename target may cross this read",
                value instanceof com._1c.g5.v8.dt.mcore.NamedElement);
            return;
        }
        assertNoLiveBmValue(value);
    }

    private static void check(Object value, Map<Object, Boolean> visited)
    {
        if (value == null || visited.put(value, true) != null) return;
        if (value instanceof IBmObject object)
        {
            assertTrue("A live IBmObject escaped a preparation read: " + value.getClass().getName(),
                object.bmIsTransient());
            if (!object.eIsProxy())
            {
                for (var reference : object.eClass().getEAllReferences())
                    if (!reference.isDerived()) check(object.eGet(reference, false), visited);
            }
        }
        else if (value instanceof Map<?, ?> map)
            map.forEach((key, item) -> { check(key, visited); check(item, visited); });
        else if (value instanceof Iterable<?> items)
            items.forEach(item -> check(item, visited));
        else if (value instanceof Object[] items)
            java.util.Arrays.stream(items).forEach(item -> check(item, visited));
        else if (value.getClass().isRecord())
        {
            for (var component : value.getClass().getRecordComponents())
            {
                try
                {
                    var accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    check(accessor.invoke(value), visited);
                }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }
        }
    }
}
