/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.history;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;

/**
 * Optional JSONL file-log sink for the in-EDT MCP call history.
 * <p>
 * A {@link McpCallHistory.HistoryListener} that appends one JSON line per recorded
 * request/response to a file on disk. It is <b>OFF by default</b>: nothing is
 * written and no listener is registered unless the file log is explicitly enabled
 * in {@link HistoryConfig}. When enabled it never blocks the wire or UI thread —
 * every record is handed to a background single-thread executor, buffered in a
 * bounded queue, and dropped on overflow rather than back-pressured. All disk I/O
 * is guarded; the first I/O failure disables the sink for the rest of the session
 * and is swallowed (logged, never propagated to the caller).
 * <p>
 * The file is <a href="https://jsonlines.org/">JSON Lines</a>: every line is a
 * standalone JSON object. The very first line of a fresh file is a warning header
 * noting that the records below are <b>raw, pre-redaction</b> payloads that may
 * contain sensitive infobase data.
 */
public final class McpCallHistoryFileLog implements McpCallHistory.HistoryListener, AutoCloseable
{
    /**
     * Maximum number of pending lines buffered before the async writer catches up.
     * Beyond this the sink drops records (see {@link #enqueue(String)}) so a slow or
     * stuck disk can never block or grow memory without bound.
     */
    static final int DEFAULT_CAPACITY = 4096;

    /**
     * Fixed name of the JSONL log file created inside the configured log <b>folder</b>.
     * The History preferences collect a directory (a {@code DirectoryDialog}), stored
     * verbatim in {@link HistoryConfig#KEY_FILE_PATH}; the sink appends its records to
     * this file within that folder rather than treating the folder itself as the file.
     */
    static final String LOG_FILE_NAME = "mcp-call-history.jsonl"; //$NON-NLS-1$

    /**
     * Human-readable warning emitted as the first line of a fresh log file. The
     * records that follow are the raw, pre-redaction request/response payloads, so
     * they may carry sensitive infobase data (variable values, query results,
     * credentials). Kept ASCII/English-only.
     */
    static final String WARNING_HEADER_TEXT =
        "This log contains RAW, PRE-REDACTION MCP request/response records that may include " //$NON-NLS-1$
            + "sensitive infobase data (variable values, query results, credentials). " //$NON-NLS-1$
            + "Handle this file as confidential."; //$NON-NLS-1$

    private final Path logFile;

    /** Bounded buffer of pre-formatted JSON lines awaiting the writer thread. */
    private final BlockingQueue<String> queue;

    /** Single background thread that owns all disk I/O for this sink. */
    private final ExecutorService writer;

    /** Completed by the executor's own {@code terminated()} hook, whichever thread terminates it. */
    private final CompletableFuture<Void> writerTerminated = new CompletableFuture<>();

    /** Orders admission and drain scheduling before the final writer cleanup task. */
    private final Object lifecycleLock = new Object();

    /** Set once when the first overflow drop happens, to log the warning only once. */
    private final AtomicBoolean overflowWarned = new AtomicBoolean(false);

    /** Repeated close calls must not repeat the stalled-disk warning. */
    private final AtomicBoolean closeTimeoutWarned = new AtomicBoolean(false);

    /**
     * Coalesces drain scheduling: at most ONE drain task is queued on the executor at a
     * time. Without this, {@link #enqueue} would submit one drain task per accepted record
     * and, since a single drain empties the whole queue, thousands of redundant no-op tasks
     * would pile up on the executor's unbounded task queue under sustained traffic (the
     * bounded-memory guarantee would move from the line queue to the task queue). Set true
     * when a drain is scheduled, cleared at the start of {@link #drain}.
     */
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);

    /** Count of records dropped on overflow (diagnostics / tests). */
    private final AtomicLong dropped = new AtomicLong(0);

    /** Set after an I/O failure: the sink is disabled for the rest of the session. */
    private volatile boolean failed;

    /** Set by {@link #close()}: no further records are accepted. */
    private volatile boolean closed;

    /** The lazily-opened writer, accessed and closed only by the background executor. */
    private BufferedWriter out;

    /** Assigned before listener publication under ACTIVE_LOCK, then owned by the writer thread. */
    private McpCallHistoryFileLog predecessor;

    /**
     * Creates a sink writing to {@code logFile}. Package-private: production code
     * goes through {@link #reconfigure()} / {@link #createIfEnabled(boolean, String)};
     * tests construct directly against a temporary path.
     *
     * @param logFile the target log file (its parent directory is created on demand)
     */
    McpCallHistoryFileLog(Path logFile)
    {
        this.logFile = logFile.toAbsolutePath().normalize();
        this.queue = new ArrayBlockingQueue<>(DEFAULT_CAPACITY);
        // A single-thread executor whose termination hook completes writerTerminated: the hook runs in
        // whichever thread moves the pool to TERMINATED (the worker or the thread calling shutdown()),
        // before the state is published, so a successor that saw it not terminated is always released.
        this.writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            this::newWriterThread)
        {
            @Override
            protected void terminated()
            {
                writerTerminated.complete(null);
            }
        };
    }

    /**
     * Guards {@link #active} so {@link #reconfigure()} and {@link #shutdown()} swap the
     * current sink atomically with respect to each other.
     */
    private static final Object ACTIVE_LOCK = new Object();

    /**
     * The currently registered sink, or {@code null} when the file log is off. Retained
     * so a preference change can unregister and {@link #close()} it, and so plugin
     * shutdown can flush and release it (background writer thread + open file handle).
     * Guarded by {@link #ACTIVE_LOCK}.
     */
    private static McpCallHistoryFileLog active;

    /**
     * Latest retired sink for each normalized file, retained until its executor terminates.
     * A successor keeps the predecessor link even if it closes without writing any records.
     * Guarded by {@link #ACTIVE_LOCK}; no termination wait or disk I/O holds this lock.
     */
    private static final Map<Path, McpCallHistoryFileLog> CLOSING = new LinkedHashMap<>();

    /**
     * (Re)builds the file-log sink from the current {@link HistoryConfig} preferences
     * and installs it on the shared {@link McpCallHistory}. Any previously active sink
     * is unregistered and {@link #close() closed} (flushing its pending lines); then,
     * when the file log is enabled with a valid path, a fresh sink is created,
     * registered and retained. When the file log is disabled (the default) or
     * misconfigured this leaves no sink registered and writes nothing.
     * <p>
     * Called once at server startup and again whenever the History preferences are
     * applied, so enabling/disabling the log or changing its folder takes effect
     * immediately without an EDT restart. Idempotent and safe to call repeatedly.
     * Never throws: any failure is logged and swallowed so it cannot abort startup or
     * a preference save.
     */
    public static void reconfigure()
    {
        try
        {
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            swapActive(createIfEnabled(HistoryConfig.isFileLogEnabled(store), HistoryConfig.getFilePath(store)));
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to (re)configure the MCP call history file log", e); //$NON-NLS-1$
        }
    }

    /**
     * Unregisters and {@link #close() closes} the active sink (if any), flushing any
     * pending JSONL lines and releasing the background writer thread and file handle.
     * Called from the plugin's {@code stop}. Idempotent; never throws.
     */
    public static void shutdown()
    {
        try
        {
            swapActive(null);
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to shut down the MCP call history file log", e); //$NON-NLS-1$
        }
    }

    /**
     * Installs {@code next} as the {@link #active} sink, unless it targets the SAME log
     * file the active sink already writes (an unchanged configuration) — in which case the
     * running sink is kept and the freshly-built {@code next} duplicate is discarded, so
     * there is never a second writer on the same file.
     * <p>
     * When the target does change, the old sink is unregistered <b>before</b> the new one
     * is registered (a single-registration handoff, so no record is ever delivered to two
     * sinks at once), then the old sink is {@link #close() closed} to flush its pending
     * lines. A return to a file whose earlier sink is still closing links the new sink
     * to that predecessor under the same lock. Its writer thread waits for predecessor
     * termination before opening the file; the preference thread does not wait for that
     * handoff. The bounded {@code close()} runs outside the lock.
     *
     * @param next the new sink to install, or {@code null} to leave the file log off
     */
    private static void swapActive(McpCallHistoryFileLog next)
    {
        McpCallHistory history = McpCallHistory.getInstance();
        McpCallHistoryFileLog previous;
        boolean unchanged;
        synchronized (ACTIVE_LOCK)
        {
            Path currentFile = active == null ? null : active.logFile;
            Path nextFile = next == null ? null : next.logFile;
            unchanged = Objects.equals(currentFile, nextFile);
            previous = active;
            if (!unchanged)
            {
                if (previous != null)
                {
                    CLOSING.put(previous.logFile, previous);
                }
                if (next != null)
                {
                    next.predecessor = CLOSING.get(next.logFile);
                }
                active = next;
            }
        }
        if (unchanged)
        {
            // The duplicate never opens the file. Close outside ACTIVE_LOCK as its
            // writer thread also uses that lock to remove a terminated registry entry.
            if (next != null)
            {
                next.close();
            }
            return;
        }
        if (previous != null)
        {
            history.removeListener(previous);
        }
        if (next != null)
        {
            history.addListener(next);
        }
        if (previous != null)
        {
            previous.close();
        }
    }

    /**
     * Returns a sink for the given enabled flag and folder, or {@code null} when the
     * file log should not run. This is the pure enable/disable + null-guard gating,
     * isolated from {@link HistoryConfig} so it is unit-testable.
     * <p>
     * {@code folder} is the <b>directory</b> the preferences UI collects (a
     * {@code DirectoryDialog}); the sink writes to {@link #LOG_FILE_NAME} inside it,
     * so opening an existing folder no longer fails as if it were the log file. The
     * folder itself is created on demand when the first record is written.
     *
     * @param enabled whether the file log is turned on
     * @param folder the target log folder (may be {@code null}/blank)
     * @return a new sink, or {@code null} when disabled or the folder is null/blank
     */
    static McpCallHistoryFileLog createIfEnabled(boolean enabled, String folder)
    {
        if (!enabled)
        {
            return null;
        }
        if (folder == null || folder.trim().isEmpty())
        {
            return null;
        }
        try
        {
            Path dir = Paths.get(folder.trim());
            return new McpCallHistoryFileLog(dir.resolve(LOG_FILE_NAME));
        }
        catch (RuntimeException e)
        {
            Activator.logError("Invalid MCP call history log folder: " + folder, e); //$NON-NLS-1$
            return null;
        }
    }

    @Override
    public void onRecord(McpCallRecord record)
    {
        enqueue(toJsonLine(record));
    }

    /**
     * Serializes one record to a single compact JSON line (no trailing newline).
     * Uses the shared, compact {@link GsonProvider}, so string values keep their
     * embedded newlines escaped as {@code \\n} and the whole record stays on one
     * line — the JSONL invariant. Returns {@code null} (skip) for a {@code null}
     * record or a serialization failure.
     *
     * @param record the record to serialize
     * @return the JSON line, or {@code null} to skip it
     */
    static String toJsonLine(Object record)
    {
        if (record == null)
        {
            return null;
        }
        try
        {
            return GsonProvider.toJson(record);
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to serialize an MCP call history record for the file log", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Returns the warning header line written first to a fresh log file: a single
     * JSON object noting that the records that follow are raw, pre-redaction data.
     *
     * @return the header line as compact JSON (no trailing newline)
     */
    static String warningHeaderLine()
    {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("_warning", WARNING_HEADER_TEXT); //$NON-NLS-1$
        header.put("_format", "jsonl"); //$NON-NLS-1$ //$NON-NLS-2$
        header.put("_producer", "com.ditrix.edt.mcp.server call history file log"); //$NON-NLS-1$ //$NON-NLS-2$
        return GsonProvider.toJson(header);
    }

    /**
     * Number of records dropped on overflow so far (diagnostics / tests).
     *
     * @return the dropped-record count
     */
    long droppedCount()
    {
        return dropped.get();
    }

    /**
     * Offers one pre-formatted line to the background writer. Never blocks: if the
     * bounded queue is full the line is dropped (and the first drop is logged once).
     * A {@code null} line, a failed sink, or a closed sink is a no-op.
     *
     * @param line the JSON line to append, or {@code null} to skip
     */
    void enqueue(String line)
    {
        synchronized (lifecycleLock)
        {
            if (line == null || failed || closed)
            {
                return;
            }
            if (!queue.offer(line))
            {
                dropped.incrementAndGet();
                if (overflowWarned.compareAndSet(false, true))
                {
                    Activator.logWarning(
                        "MCP call history file log queue is full; dropping records (disk writer cannot keep up)"); //$NON-NLS-1$
                }
                return;
            }
            scheduleDrain();
        }
    }

    /**
     * Schedules a single drain task if one is not already pending, so a burst of records
     * coalesces into one drain instead of one task per record (see {@link #drainScheduled}).
     * A rejected submission (executor shutting down) resets the flag so a later
     * {@link #close()} residual drain still flushes the line.
     */
    private void scheduleDrain()
    {
        synchronized (lifecycleLock)
        {
            if (!closed && drainScheduled.compareAndSet(false, true))
            {
                try
                {
                    writer.execute(this::drain);
                }
                catch (RejectedExecutionException e)
                {
                    // Leave the line queued for the final cleanup task. Never propagate.
                    drainScheduled.set(false);
                }
            }
        }
    }

    /**
     * Drains all currently queued lines to disk on the background thread. Any I/O
     * failure disables the sink (swallowed, logged once): the queue is cleared and
     * no further writes are attempted. Clears {@link #drainScheduled} up front (so
     * records offered during this drain re-schedule a fresh task) and, as a safety net,
     * reschedules if lines remain when it finishes.
     */
    private void drain()
    {
        drainScheduled.set(false);
        if (failed)
        {
            queue.clear();
            return;
        }
        try
        {
            ensureOpen();
            String line;
            while ((line = queue.poll()) != null)
            {
                out.write(line);
                out.write('\n');
            }
            out.flush();
        }
        catch (IOException | RuntimeException e)
        {
            failed = true;
            queue.clear();
            closeWriterQuietly();
            Activator.logError("MCP call history file log disabled after an I/O failure writing " + logFile, e); //$NON-NLS-1$
            return;
        }
        // A line offered between the last successful poll() and the flag reset above would
        // otherwise wait for the next record; reschedule so it is not stranded.
        if (!queue.isEmpty())
        {
            scheduleDrain();
        }
    }

    /**
     * Opens the writer on first use, creating the parent directory as needed and
     * emitting the {@link #warningHeaderLine() warning header} when the file is new
     * or empty (so the header is always the very first line and is never duplicated
     * when appending to an existing log).
     *
     * @throws IOException if the directory or file cannot be created/opened
     */
    private void ensureOpen() throws IOException
    {
        if (out != null)
        {
            return;
        }
        awaitPredecessor();
        Path parent = logFile.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        boolean fresh = !Files.exists(logFile) || Files.size(logFile) == 0L;
        out = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (fresh)
        {
            out.write(warningHeaderLine());
            out.write('\n');
        }
    }

    /** Waits only on this sink's writer thread, before any access to a predecessor's file. */
    private void awaitPredecessor()
    {
        McpCallHistoryFileLog previous = predecessor;
        if (previous == null)
        {
            return;
        }
        if (!previous.writer.isTerminated())
        {
            // join waits through interruption and restores the flag on return. File ownership
            // transfers only after the predecessor worker has finished all cleanup.
            previous.writerTerminated.join();
            // terminated() runs in TIDYING; TERMINATED is published by the same thread right after
            // it returns. Wait out that short step so the handoff keeps awaitTermination's guarantee.
            while (!previous.writer.isTerminated())
            {
                Thread.yield();
            }
        }
        predecessor = null;
    }

    /**
     * Stops admission and queues the final flush and close after all accepted drains.
     * Waits at most five seconds, or less if interrupted. Cleanup then stays with the
     * daemon writer thread: pending lines and its buffered output may be lost if EDT
     * exits while disk I/O is still stalled. A timeout is logged once with the file path.
     * Idempotent and safe to call from any thread.
     */
    @Override
    public void close()
    {
        synchronized (lifecycleLock)
        {
            if (!closed)
            {
                closed = true;
                writer.execute(() -> {
                    try
                    {
                        // Even an empty successor must retain the predecessor's place
                        // in the chain until that executor has terminated.
                        awaitPredecessor();
                        drainResidual();
                    }
                    finally
                    {
                        closeWriterQuietly();
                    }
                });
                writer.shutdown();
            }
        }
        try
        {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS))
            {
                if (closeTimeoutWarned.compareAndSet(false, true))
                {
                    Activator.logWarning("Timed out after 5 seconds closing the MCP call history file log: " //$NON-NLS-1$
                        + logFile + ". Pending call-history lines may be lost if EDT exits while the disk write " //$NON-NLS-1$
                        + "is still stalled; check the log file and disk."); //$NON-NLS-1$
                }
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Final flush of any lines still queued, on the writer executor after its drains.
     * Admission has stopped, so no later record can be stranded behind this task.
     * Best-effort: a failure here is logged, not thrown (the sink is closing anyway).
     */
    private void drainResidual()
    {
        if (failed || queue.isEmpty())
        {
            return;
        }
        try
        {
            ensureOpen();
            String line;
            while ((line = queue.poll()) != null)
            {
                out.write(line);
                out.write('\n');
            }
            out.flush();
        }
        catch (IOException | RuntimeException e)
        {
            Activator.logError("MCP call history file log failed to flush pending lines on close: " + logFile, e); //$NON-NLS-1$
        }
    }

    private void closeWriterQuietly()
    {
        BufferedWriter w = out;
        out = null;
        if (w != null)
        {
            try
            {
                w.close();
            }
            catch (IOException e)
            {
                // Best-effort close of a log file; nothing actionable remains.
            }
        }
    }

    private Thread newWriterThread(Runnable r)
    {
        Thread t = new Thread(() -> {
            try
            {
                r.run();
            }
            finally
            {
                synchronized (ACTIVE_LOCK)
                {
                    // The final disk cleanup task runs before executor termination.
                    // Remove the entry only after the executor's worker has returned.
                    if (writer.isTerminated())
                    {
                        CLOSING.remove(logFile, this);
                    }
                }
            }
        }, "mcp-call-history-file-log"); //$NON-NLS-1$
        t.setDaemon(true);
        return t;
    }
}
