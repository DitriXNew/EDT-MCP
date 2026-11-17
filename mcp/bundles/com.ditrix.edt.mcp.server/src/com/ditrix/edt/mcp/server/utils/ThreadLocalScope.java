// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.function.Supplier;

/** Restores a nested binding and removes the outer binding, including on exceptional exits. */
final class ThreadLocalScope<T> implements AutoCloseable
{
    /**
     * A per-thread value that can be bound ONLY by opening a {@link ThreadLocalScope}: it exposes
     * no {@code set}, so no binding can outlive the try-with-resources block that made it.
     *
     * <p>That is the whole point on the threads these planners run on — pooled MCP request
     * threads and Eclipse job workers that live for the session. A binding left behind would
     * stay reachable from the thread for its lifetime; a named-color resolver, for one, captures
     * the live {@code Configuration} and with it the project's model.
     *
     * @param <T> the bound value
     */
    static final class Slot<T>
    {
        private final ThreadLocal<T> local = new ThreadLocal<>();

        /** @return the value bound by the innermost open scope on this thread, or {@code null} */
        T get()
        {
            return local.get();
        }

        private void bind(T value)
        {
            if (value == null)
            {
                local.remove();
            }
            else
            {
                local.set(value);
            }
        }
    }

    private final Slot<T> slot;
    private final T previous;

    private ThreadLocalScope(Slot<T> slot, T value)
    {
        this.slot = slot;
        previous = slot.get();
        slot.bind(value);
    }

    static <T> ThreadLocalScope<T> open(Slot<T> slot, T value)
    {
        return new ThreadLocalScope<>(slot, value);
    }

    static <T> ThreadLocalScope<T> openIfAbsent(Slot<T> slot, Supplier<T> initial)
    {
        T current = slot.get();
        return open(slot, current == null ? initial.get() : current);
    }

    @Override public void close() { slot.bind(previous); }
}
