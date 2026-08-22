/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One holder per file path, so that a read-modify-write of that file cannot interleave with
 * another one in this process.
 *
 * <h2>The hazard it closes</h2>
 * Updating a file in place is three steps - read it, apply the change in memory, write the result
 * back - and only the last of them is atomic on the filesystem. Two calls that update the same
 * file at the same time therefore both read the SAME starting document, and each writes its own
 * change over the other's: one caller's work disappears and both reports say it was recorded.
 * A "the file must not already exist" reservation does not cover this at all - here the file
 * exists legitimately, which is the whole point of an update.
 *
 * <h2>Why a map and not a lock per call</h2>
 * The interval being protected is keyed by the FILE, not by the caller: two calls collide only
 * when they name the same path. A single global lock would serialise unrelated files, and a lock
 * created per call would serialise nothing.
 *
 * <h2>Why the entries are counted</h2>
 * A map that only ever grows is a leak keyed by every path this server has written. Each holder
 * counts the callers that have asked for it and removes itself when the last one leaves, so the
 * map is the size of the work in flight rather than of the work ever done. The count is
 * maintained under the map's own monitor, which is never held while a caller waits for the lock -
 * so a long write blocks the callers of ITS path and nobody else.
 *
 * <h2>What it does NOT do</h2>
 * <ul>
 * <li><b>Nothing across processes.</b> This is a lock in this JVM. Another EDT, or a person with
 * a text editor, can still write the file between a holder's read and its write.</li>
 * <li><b>Nothing across spellings.</b> The key is the path as given - callers pass an absolute,
 * normalised one - so the same file reached through a symbolic link, a junction, or a different
 * case on a case-insensitive filesystem is a DIFFERENT key. Resolving to the file's real identity
 * needs the file to exist, which it does not for a fresh write.</li>
 * <li><b>It is not a file lock,</b> and it does not outlive the call that took it.</li>
 * </ul>
 */
public final class PathMutex
{
    /** The holders currently in use, keyed by the path they guard. Guarded by its own monitor. */
    private static final Map<Path, PathMutex> HELD = new HashMap<>();

    private final Path path;

    private final ReentrantLock lock = new ReentrantLock();

    /** How many callers are holding or waiting for this holder; guarded by {@link #HELD}. */
    private int users;

    private PathMutex(Path path)
    {
        this.path = path;
    }

    /**
     * Takes the holder for a path, waiting for any other caller in this process to finish with it.
     * <p>
     * Always paired with {@link #release()} in a {@code finally}: a holder that is never released
     * would block every later call on that path for as long as the server runs.
     *
     * @param path the file to guard, absolute and normalised by the caller
     * @return the held holder; call {@link #release()} when the sequence is over
     */
    public static PathMutex take(Path path)
    {
        PathMutex mutex;
        synchronized (HELD)
        {
            mutex = HELD.computeIfAbsent(path, PathMutex::new);
            mutex.users++;
        }
        // OUTSIDE the map's monitor on purpose: waiting here with the map held would stop every
        // other path from being taken for the length of somebody else's write.
        mutex.lock.lock();
        return mutex;
    }

    /**
     * Gives the holder back, and forgets it entirely when nobody else wants it.
     */
    public void release()
    {
        lock.unlock();
        synchronized (HELD)
        {
            users--;
            if (users == 0)
            {
                HELD.remove(path, this);
            }
        }
    }

    /**
     * @return how many paths are held or waited for right now; for the tests, which have no other
     *     way to see that a holder does not outlive its callers
     */
    static int heldCount()
    {
        synchronized (HELD)
        {
            return HELD.size();
        }
    }
}
