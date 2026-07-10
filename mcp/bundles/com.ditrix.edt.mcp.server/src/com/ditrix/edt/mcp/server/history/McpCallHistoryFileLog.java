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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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

    /** Set once when the first overflow drop happens, to log the warning only once. */
    private final AtomicBoolean overflowWarned = new AtomicBoolean(false);

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

    /**
     * The lazily-opened writer. Confined to the background thread while running;
     * read/closed by {@link #close()} only after the executor has terminated, so a
     * happens-before edge covers the cross-thread hand-off. {@code volatile} for
     * belt-and-braces visibility.
     */
    private volatile BufferedWriter out;

    /**
     * Creates a sink writing to {@code logFile}. Package-private: production code
     * goes through {@link #reconfigure()} / {@link #createIfEnabled(boolean, String)};
     * tests construct directly against a temporary path.
     *
     * @param logFile the target log file (its parent directory is created on demand)
     */
    McpCallHistoryFileLog(Path logFile)
    {
        this.logFile = logFile;
        this.queue = new ArrayBlockingQueue<>(DEFAULT_CAPACITY);
        this.writer = Executors.newSingleThreadExecutor(McpCallHistoryFileLog::newWriterThread);
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
     * lines. Because the targets differ, the old sink's flush and the new sink's writes go
     * to different files and cannot interleave. The lock is held only for the field swap;
     * the (potentially blocking) {@code close()} runs outside it.
     *
     * @param next the new sink to install, or {@code null} to leave the file log off
     */
    private static void swapActive(McpCallHistoryFileLog next)
    {
        McpCallHistory history = McpCallHistory.getInstance();
        McpCallHistoryFileLog previous;
        synchronized (ACTIVE_LOCK)
        {
            Path currentFile = active == null ? null : active.logFile;
            Path nextFile = next == null ? null : next.logFile;
            if (Objects.equals(currentFile, nextFile))
            {
                // Unchanged target: keep the running sink; drop the unused duplicate (its
                // executor thread is released — the file was never opened for it).
                if (next != null)
                {
                    next.close();
                }
                return;
            }
            previous = active;
            active = next;
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

    /**
     * Schedules a single drain task if one is not already pending, so a burst of records
     * coalesces into one drain instead of one task per record (see {@link #drainScheduled}).
     * A rejected submission (executor shutting down) resets the flag so a later
     * {@link #close()} residual drain still flushes the line.
     */
    private void scheduleDrain()
    {
        if (drainScheduled.compareAndSet(false, true))
        {
            try
            {
                writer.execute(this::drain);
            }
            catch (RejectedExecutionException e)
            {
                // The executor is shutting down. Clear the flag we just set; the line stays
                // queued and is flushed by close()'s residual drain. Never propagate.
                drainScheduled.set(false);
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

    /**
     * Stops the sink and flushes any pending records. Shuts the background writer
     * down, waits briefly for it to drain, then closes the file. Idempotent and
     * safe to call from any thread.
     */
    @Override
    public void close()
    {
        closed = true;
        writer.shutdown();
        try
        {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS))
            {
                writer.shutdownNow();
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        // The writer thread has now terminated (or been cancelled), so no drain runs
        // concurrently. Flush any line left in the queue by a race between enqueue and
        // shutdown (offer succeeded, then execute was rejected) — otherwise close()'s
        // documented "flush pending lines" promise would silently drop it.
        drainResidual();
        closeWriterQuietly();
    }

    /**
     * Final synchronous flush of any lines still queued after the executor has
     * terminated. Runs on the closing thread with no concurrent writer, so it may
     * touch {@link #out} directly. Best-effort: a failure here is logged, not thrown
     * (the sink is closing anyway).
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

    private static Thread newWriterThread(Runnable r)
    {
        Thread t = new Thread(r, "mcp-call-history-file-log"); //$NON-NLS-1$
        t.setDaemon(true);
        return t;
    }
}
