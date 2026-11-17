// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;

/** Admission and ownership of database updates, shared with launch preflight. */
public final class ApplicationUpdateJobs
{
    private static ApplicationUpdateJobs shared;
    private final BackgroundJobs jobs;
    private final Map<Target, String> running = new HashMap<>();

    public ApplicationUpdateJobs(BackgroundJobs jobs)
    {
        this.jobs = jobs;
    }

    public static synchronized ApplicationUpdateJobs shared()
    {
        BackgroundJobs registry = BackgroundJobs.shared();
        if (shared == null || shared.jobs != registry)
        {
            shared = new ApplicationUpdateJobs(registry);
        }
        return shared;
    }

    public synchronized String runningJobId(String project, String applicationId)
    {
        Target target = new Target(project, applicationId);
        String id = running.get(target);
        JobSnapshot snapshot = id == null ? null : jobs.get(id);
        if (snapshot != null && (snapshot.getStatus() == BackgroundJobs.Status.RUNNING
            || snapshot.isClaimed()))
        {
            return id;
        }
        running.remove(target);
        return null;
    }

    /** Returns the existing job on refusal, or the newly submitted job on admission. */
    public synchronized Submission start(String project, String applicationId, Supplier<String> work)
    {
        running.entrySet().removeIf(entry -> {
            JobSnapshot job = jobs.get(entry.getValue());
            return job == null || (job.getStatus() != BackgroundJobs.Status.RUNNING && !job.isClaimed());
        });
        String existing = running.get(new Target(project, applicationId));
        JobSnapshot previous = existing == null ? null : jobs.get(existing);
        if (previous != null)
        {
            return new Submission(previous, false);
        }
        JobSnapshot started = jobs.start("update_database", Long.MAX_VALUE,
            "Accepted the database update. Once started, EDT provides no verified clean cancellation; "
                + "the load is left to finish.", progress -> {
                    if (!progress.tryCommit())
                    {
                        return null;
                    }
                    InfobaseAuthDialogSuppressor.markActivityStart();
                    try
                    {
                        return work.get();
                    }
                    finally
                    {
                        InfobaseAuthDialogSuppressor.markActivityEnd();
                    }
                });
        running.put(new Target(project, applicationId), started.getId());
        return new Submission(started, true);
    }

    public record Submission(JobSnapshot job, boolean admitted) { }
    private record Target(String project, String applicationId) { }
}
