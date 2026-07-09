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
     * Atomically replaces the {@link #active} sink with {@code next}, then registers
     * the new sink and unregisters + {@link #close() closes} the old one. The lock is
     * held only for the field swap; the (potentially blocking) {@code close()} runs
     * outside it so it never serializes with a concurrent swap.
     *
     * @param next the new sink to install, or {@code null} to leave the file log off
     */
    private static void swapActive(McpCallHistoryFileLog next)
    {
        McpCallHistory history = McpCallHistory.getInstance();
        McpCallHistoryFileLog previous;
        synchronized (ACTIVE_LOCK)
        {
            previous = active;
            active = next;
        }
        if (next != null)
        {
            history.addListener(next);
        }
        if (previous != null)
        {
            history.removeListener(previous);
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
        try
        {
            writer.execute(this::drain);
        }
        catch (RejectedExecutionException e)
        {
            // The executor is shutting down. The line stays queued and is written by a
            // drain task already in flight, or discarded on shutdown. Never propagate.
        }
    }

    /**
     * Drains all currently queued lines to disk on the background thread. Any I/O
     * failure disables the sink (swallowed, logged once): the queue is cleared and
     * no further writes are attempted.
     */
    private void drain()
    {
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
        closeWriterQuietly();
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
