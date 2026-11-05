// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ThreadLocalScopeTest
{
    @Test public void nestedDefaultsShareOnlyTheActiveScope()
    {
        ThreadLocal<Object> local = new ThreadLocal<>();
        Object state;
        try (var outer = ThreadLocalScope.openIfAbsent(local, Object::new))
        {
            state = local.get();
            assertNotNull(state);
            try (var inner = ThreadLocalScope.openIfAbsent(local, () -> {
                throw new AssertionError("Nested calls must reuse the active parser state");
            })) { assertSame(state, local.get()); }
            assertSame(state, local.get());
        }
        assertNull(local.get());
        try (var next = ThreadLocalScope.openIfAbsent(local, Object::new)) { assertNotSame(state, local.get()); }
        assertNull(local.get());
        local.remove();
    }

    @Test public void nestedValuesAndNullRestoreTheOuterBinding()
    {
        ThreadLocal<String> local = new ThreadLocal<>();
        try (var outer = ThreadLocalScope.open(local, "outer"))
        {
            assertEquals("outer", local.get());
            try (var inner = ThreadLocalScope.open(local, "inner")) { assertEquals("inner", local.get()); }
            assertEquals("outer", local.get());
            try (var inner = ThreadLocalScope.open(local, null)) { assertNull(local.get()); }
            assertEquals("outer", local.get());
        }
        assertNull(local.get());
        local.remove();
    }

    @Test public void exceptionRestoresExistingBindingAndOuterExitRemovesIt()
    {
        ThreadLocal<String> local = new ThreadLocal<>();
        try (var outer = ThreadLocalScope.open(local, "outer"))
        {
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = ThreadLocalScope.open(local, "inner")) { throw new IllegalStateException("failure"); }
            });
            assertEquals("outer", local.get());
        }
        assertNull(local.get());
        local.remove();
    }

    @Test public void bindingsAreIsolatedAcrossThreads() throws Exception
    {
        ThreadLocal<String> local = new ThreadLocal<>();
        try (var outer = ThreadLocalScope.open(local, "caller"))
        {
            CompletableFuture.runAsync(() -> {
                assertNull(local.get());
                try (var worker = ThreadLocalScope.open(local, "worker")) { assertEquals("worker", local.get()); }
                assertNull(local.get());
                local.remove();
            }).get(5, TimeUnit.SECONDS);
            assertEquals("caller", local.get());
        }
        assertNull(local.get());
        local.remove();
    }
}
