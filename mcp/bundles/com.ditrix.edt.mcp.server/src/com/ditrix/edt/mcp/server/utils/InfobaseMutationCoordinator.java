/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Coordinates MCP launcher mutations and native credential writes without owning an SDK monitor.
 * Credential workers acquire their UUID stripe BEFORE LOCK; callers never wait for a worker under
 * LOCK. EDT's GUI does not participate: the public SDK offers no conditional secure-storage write.
 */
final class InfobaseMutationCoordinator
{
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final long WAIT_TIMEOUT_MS = 5_000L;
    private static final ConcurrentHashMap<UUID, Deletion> DELETIONS = new ConcurrentHashMap<>();

    private InfobaseMutationCoordinator()
    {
    }

    /** A native operation may outlive its caller; other requests must fail instead of queue forever. */
    static Lease acquire()
    {
        return acquire(WAIT_TIMEOUT_MS);
    }

    static Lease acquire(long timeoutMs)
    {
        try
        {
            if (!LOCK.tryLock(timeoutMs, TimeUnit.MILLISECONDS))
            {
                throw new Unavailable("EDT launcher/credential operations are busy; retry after the active SDK operation finishes."); //$NON-NLS-1$
            }
            return new Lease();
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            throw new Unavailable("Waiting for EDT launcher/credential operations was cancelled; retry with a fresh application lookup."); //$NON-NLS-1$
        }
    }

    static final class Lease implements AutoCloseable
    {
        @Override
        public void close()
        {
            LOCK.unlock();
        }
    }

    static final class Unavailable extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;

        private Unavailable(String message)
        {
            super(message);
        }
    }

    static boolean isDeleting(UUID id)
    {
        Deletion state = id == null ? null : DELETIONS.get(id);
        return state != null && (state.active.get() || state.removalStarted);
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
            next.recoverySnapshot = previous == null ? null : previous.recoverySnapshot;
            return next;
        });
        return next;
    }

    /** Only snapshots verified by an earlier cleanup may recover an absent UUID. */
    static InfobaseReference recoverySnapshot(UUID id)
    {
        Deletion state = DELETIONS.get(id);
        var snapshot = state == null ? null : state.recoverySnapshot;
        return snapshot == null ? null : EcoreUtil.copy(snapshot);
    }

    static final class Deletion implements AutoCloseable
    {
        private final UUID id;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile boolean removalStarted;
        private volatile InfobaseReference recoverySnapshot;
        private boolean cleanupCompleted;

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

        /** Never expose the retained prototype: native settings writes may mutate their reference. */
        void remember(InfobaseReference reference)
        {
            var snapshot = EcoreUtil.copy(reference);
            DELETIONS.compute(id, (key, current) -> {
                Deletion target = current == null ? this : current;
                if (!target.cleanupCompleted && (target == this || target.recoverySnapshot == null))
                {
                    target.recoverySnapshot = snapshot;
                }
                return target;
            });
        }

        void completed()
        {
            DELETIONS.computeIfPresent(id, (key, current) -> {
                if (current == this)
                {
                    cleanupCompleted = true;
                    recoverySnapshot = null;
                }
                return current;
            });
        }

        /** Successful late reloads must preserve absence even after the bounded caller closed. */
        void observedAbsence()
        {
            DELETIONS.compute(id, (key, current) -> {
                removalStarted = true;
                if (current != null)
                {
                    current.removalStarted = true;
                }
                return current == null ? this : current;
            });
        }

        @Override
        public void close()
        {
            // Do not acquire LOCK here: a timed-out native worker may still hold it. Its stripe
            // ensures a later intentional credential write follows any already-entered clear.
            active.set(false);
            // Check and removal share the same map operation as observedAbsence: a late reload
            // either seals this claim before close, or reinstates an inactive tombstone afterward.
            DELETIONS.computeIfPresent(id, (key, current) ->
                current == this && !removalStarted && recoverySnapshot == null ? null : current);
            // Once deletion was attempted, retain a process-lifetime tombstone even if read-back
            // failed. Stale resolved applications must never recreate unreachable secure settings.
            // A new explicit cleanup may replace an inactive claim and verify/retry the same UUID.
        }
    }
}
