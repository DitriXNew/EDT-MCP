/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates MCP launcher mutations and native credential writes without owning an SDK monitor.
 * Credential workers acquire their UUID stripe BEFORE LOCK; callers never wait for a worker under
 * LOCK. EDT's GUI does not participate: the public SDK offers no conditional secure-storage write.
 */
final class InfobaseMutationCoordinator
{
    static final Object LOCK = new Object();
    private static final ConcurrentHashMap<UUID, Deletion> DELETIONS = new ConcurrentHashMap<>();

    private InfobaseMutationCoordinator()
    {
    }

    static boolean isDeleting(UUID id)
    {
        return id != null && DELETIONS.containsKey(id);
    }

    static void requireWritable(UUID id)
    {
        if (isDeleting(id))
        {
            throw new IllegalStateException("The registration is being removed or was removed; refresh get_applications before storing credentials."); //$NON-NLS-1$
        }
    }

    static Deletion beginDeletion(UUID id)
    {
        Deletion next = new Deletion(id);
        DELETIONS.compute(id, (key, previous) -> {
            if (previous != null && previous.active.get())
            {
                throw new IllegalStateException("Registration cleanup is already in progress; wait for it to finish before retrying."); //$NON-NLS-1$
            }
            // A retry must not erase the previous tombstone if lookup/cleanup fails.
            next.removalStarted = previous != null && previous.removalStarted;
            return next;
        });
        return next;
    }

    static final class Deletion implements AutoCloseable
    {
        private final UUID id;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile boolean removalStarted;

        private Deletion(UUID id)
        {
            this.id = id;
        }

        void requireActive()
        {
            if (!active.get() || DELETIONS.get(id) != this)
            {
                throw new IllegalStateException("Registration cleanup is no longer active; request a new preview."); //$NON-NLS-1$
            }
        }

        void beforeRemoval()
        {
            requireActive();
            removalStarted = true;
        }

        @Override
        public void close()
        {
            // Do not acquire LOCK here: a timed-out native worker may still hold it. Its stripe
            // ensures a later intentional credential write follows any already-entered clear.
            active.set(false);
            if (!removalStarted)
            {
                DELETIONS.remove(id, this);
            }
            // Once deletion was attempted, retain a process-lifetime tombstone even if read-back
            // failed. Stale resolved applications must never recreate unreachable secure settings.
            // A new explicit cleanup may replace an inactive claim and verify/retry the same UUID.
        }
    }
}
