/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationCapability;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CommittedCancellation;

/** Owner-declared destructive cancellation capability for a live YAXUnit client launch. */
public final class YaxunitJobCancellation
{
    /** Consent-preview text exposed through {@code cancel_job}; deliberately explicit. */
    public static final String PREVIEW_WARNING =
        "Terminating this YAXUnit run kills the client process. The infobase keeps whatever " //$NON-NLS-1$
            + "the tests already did and is not rolled back; the JUnit report will be partial " //$NON-NLS-1$
            + "or absent."; //$NON-NLS-1$

    /** Launch and report path have one publication point; neither may be observed without the other. */
    private final AtomicReference<TrackedRun> trackedRun = new AtomicReference<>();
    private final Consumer<ILaunch> afterTermination;
    private final int terminateTimeoutSeconds;

    public YaxunitJobCancellation(Consumer<ILaunch> afterTermination)
    {
        this(afterTermination, LaunchLifecycleUtils.getDefaultTerminateTimeoutSeconds());
    }

    /** Constructor with a bounded test/owner-selected termination wait. */
    public YaxunitJobCancellation(Consumer<ILaunch> afterTermination,
        int terminateTimeoutSeconds)
    {
        this.afterTermination = afterTermination != null ? afterTermination : ignored -> {
            // No owner-side tracking to clear.
        };
        this.terminateTimeoutSeconds = Math.max(1, terminateTimeoutSeconds);
    }

    /** Declares this owner-specific capability to {@link BackgroundJobs#start}. */
    public CancellationCapability capability()
    {
        return CancellationCapability.of(PREVIEW_WARNING, this::cancelCommittedRun);
    }

    /** Publishes the actual client launch and its report directory once spawn succeeds. */
    public void track(ILaunch trackedLaunch, Path trackedReportDir)
    {
        trackedRun.set(new TrackedRun(trackedLaunch, trackedReportDir));
    }

    private CommittedCancellation cancelCommittedRun()
    {
        TrackedRun tracked = trackedRun.get();
        if (tracked == null || tracked.launch == null)
        {
            return CommittedCancellation.notStopped(
                "The YAXUnit job was NOT cancelled: its pre-launch work was already handed to " //$NON-NLS-1$
                    + "EDT, but no live test client is available to terminate yet."); //$NON-NLS-1$
        }
        if (tracked.launch.isTerminated())
        {
            return CommittedCancellation.notStopped(
                "The YAXUnit job was NOT newly cancelled: its client had already terminated and " //$NON-NLS-1$
                    + "the job is collecting the report."); //$NON-NLS-1$
        }
        if (!tracked.launch.canTerminate())
        {
            return CommittedCancellation.notStopped(
                "The YAXUnit job was NOT cancelled because its client launch reports that it " //$NON-NLS-1$
                    + "cannot be terminated. Continue polling the existing job; do not start " //$NON-NLS-1$
                    + "a duplicate run."); //$NON-NLS-1$
        }
        try
        {
            tracked.launch.terminate();
        }
        catch (DebugException e)
        {
            if (tracked.launch.isTerminated())
            {
                return CommittedCancellation.notStopped(
                    "The YAXUnit job was NOT newly cancelled: its client terminated before " //$NON-NLS-1$
                        + "this request could initiate the stop, and the job is collecting the " //$NON-NLS-1$
                        + "report."); //$NON-NLS-1$
            }
            Activator.logError("Failed to initiate YAXUnit launch termination", e); //$NON-NLS-1$
            return CommittedCancellation.notStopped(
                "The YAXUnit job was NOT cancelled because its client process could not be " //$NON-NLS-1$
                    + "terminated: " + failureMessage(e)); //$NON-NLS-1$
        }
        if (!LaunchLifecycleUtils.waitForTerminated(tracked.launch,
            terminateTimeoutSeconds * 1000L))
        {
            String reason = Thread.currentThread().isInterrupted()
                ? "termination verification was interrupted after the request was accepted" //$NON-NLS-1$
                : "the client did not report termination within the allowed verification wait"; //$NON-NLS-1$
            String result = "YAXUnit client termination was requested and cannot be taken back, " //$NON-NLS-1$
                + "but it is not yet confirmed because " + reason + ". The infobase was NOT " //$NON-NLS-1$ //$NON-NLS-2$
                + "rolled back; it keeps whatever changes the tests already made. The " //$NON-NLS-1$
                + "background job is cancellation-pending, and its own status will settle when " //$NON-NLS-1$
                + "the run actually ends. Never treat this run as a clean test outcome.\n\n" //$NON-NLS-1$
                + partialReport(tracked.reportDir);
            return CommittedCancellation.stopInitiated(result, result);
        }

        try
        {
            afterTermination.accept(tracked.launch);
        }
        catch (RuntimeException e) // NOSONAR cleanup failure does not undo the proven stop
        {
            Activator.logError("YAXUnit launch stopped but its owner tracking cleanup failed", e); //$NON-NLS-1$
        }
        String result = "The YAXUnit client process was killed and the run was stopped. The " //$NON-NLS-1$
            + "infobase was NOT rolled back; it keeps whatever changes the tests had already " //$NON-NLS-1$
            + "made.\n\n" + partialReport(tracked.reportDir); //$NON-NLS-1$
        return CommittedCancellation.stopped(result, result);
    }

    private static String partialReport(Path reportDir)
    {
        String partial = YaxunitReportUtils.renderIfUsable(reportDir);
        return partial == null
            ? "No usable partial JUnit report was found; it is absent or incomplete." //$NON-NLS-1$
            : "A JUnit XML report was readable, but it is partial and must not be treated as a " //$NON-NLS-1$
                + "clean test outcome.\n\n" + partial; //$NON-NLS-1$
    }

    /** Immutable state published through {@link #trackedRun} in one atomic write. */
    private static final class TrackedRun
    {
        final ILaunch launch;
        final Path reportDir;

        TrackedRun(ILaunch launch, Path reportDir)
        {
            this.launch = launch;
            this.reportDir = reportDir;
        }
    }

    private static String failureMessage(Throwable failure)
    {
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName() : message;
    }
}
