// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.function.Supplier;

/** Restores a nested binding and removes the outer binding, including on exceptional exits. */
final class ThreadLocalScope<T> implements AutoCloseable
{
    private final ThreadLocal<T> local;
    private final T previous;

    private ThreadLocalScope(ThreadLocal<T> local, T value)
    {
        this.local = local;
        previous = local.get();
        set(value);
    }

    static <T> ThreadLocalScope<T> open(ThreadLocal<T> local, T value)
    {
        return new ThreadLocalScope<>(local, value);
    }

    static <T> ThreadLocalScope<T> openIfAbsent(ThreadLocal<T> local, Supplier<T> initial)
    {
        T current = local.get();
        return open(local, current == null ? initial.get() : current);
    }

    private void set(T value)
    {
        if (value == null) local.remove();
        else local.set(value);
    }

    @Override public void close() { set(previous); }
}
