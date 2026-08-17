/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.base;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IProject;

/**
 * What a single write call says about WHERE it wrote - stated by the call itself, while it runs.
 * <p>
 * The export barrier used to ask this question AFTERWARDS, of the tool's arguments and of the JSON
 * it had already produced, and every tool answered it with a different indirect signal: the
 * extension name read back out of its own result, the position of the marker it fixed, its own
 * report counters, the {@code confirm} argument. None of those is a statement about a write; each
 * new writing tool was a fresh chance to guess wrong in either direction - waiting for a project
 * nothing was queued in refuses a healthy call, and not waiting for the one that was answers "done"
 * over an unfinished disk. Issue #408.
 * <p>
 * <b>The statement is a by-product of doing the write, not a second thing to remember.</b> Every
 * export this plugin submits goes through one call -
 * {@code BmTransactions.forceExportToDisk} - and that call records the project here. A tool that
 * submits an export therefore declares by submitting. The explicit methods exist only for what that
 * choke point cannot see: a branch that wrote but had no FQN to submit, the platform's own cascade,
 * a success that queued nothing, and a write whose reach the platform will not report.
 * <p>
 * <b>What the choke point cannot see</b>, stated so the guarantee is not read wider than it is: the
 * exports the PLATFORM schedules for itself - the BM reactor after a write transaction commits,
 * EDT's md-refactoring inside a delete or a rename, the adoption service's own BM task - never pass
 * through our helper. They are unobservable from here, which is why {@link #cascadedInto(IProject)}
 * exists and why it is graded differently from a write we performed ourselves.
 * <p>
 * <b>Two grades, and the grade decides whether a stalled queue may REFUSE the call.</b>
 * <ul>
 * <li>a project this call WROTE in (a submitted export, or {@link #wrote(IProject)} when the write
 * queued no export of its own) is waited for and a queue that will not drain refuses - the same
 * verdict the barrier has always reached, now for the right project;</li>
 * <li>a project the platform's cascade MAY have written in ({@link #cascadedInto(IProject)}) is
 * waited for but can never refuse: we never went near that project's export path, so a stall there
 * is somebody else's news. This is what lets a delete cascade be awaited at all without
 * reintroducing the false refusal that awaiting the scanned-but-untouched set would cause.</li>
 * </ul>
 * <p>
 * <b>"Nothing" and "unknown" are different, at every layer.</b> {@link #queuedNothing()} is a
 * finding - this call put nothing in the queue. Saying nothing at all is an absence of knowledge, and
 * {@link #undeterminable(String, Collection)} is a third thing again: a decision that the platform
 * does not report what was touched. They must never collapse into one value, because the barrier owes
 * a different answer to each and because publishing "I wrote nowhere" for a call that simply could
 * not tell is the over-claim this whole issue is about.
 * <p>
 * Order of statements does not matter, and the precedence is
 * record &gt; {@link #undeterminable(String, Collection)} &gt; {@link #queuedNothing()} &gt; silence.
 * A record wins because it is the hardest fact available; between the other two the safe reading is
 * the one that waits more and claims less, so a call that contradicted itself by stating both is
 * read as "I cannot tell". That is what makes a tool safe to write in
 * the obvious way - {@code resync_to_disk} may report "nothing to export" and only then have its
 * dangling-reference cleanup export {@code Configuration.mdo}; the later record simply wins.
 * <p>
 * Instances are confined to one call. The base class binds one to the thread that runs
 * {@code executeOnUiThread} and restores the previous binding afterwards, so a nested tool call -
 * reachable, because the consent dialog and {@code resync_to_disk} both pump nested SWT event loops
 * in which another request's UI runnable can run - records into its own scope and gives the outer one
 * back.
 */
public final class WriteScope
{
    /**
     * The scope of the call currently running on this thread, if any.
     * <p>
     * Thread-bound rather than passed as a parameter because the recording point is
     * {@code BmTransactions.forceExportToDisk}, which is reached from ~20 call sites across three
     * tools AND from the shared writers ({@code FormElementWriter}, {@code RoleRightsWriter}). A
     * parameter would have to be threaded through all of them and could still be dropped on one
     * branch; the choke point cannot be bypassed while still queuing an export.
     */
    private static final ThreadLocal<WriteScope> BOUND = new ThreadLocal<>();

    /** The result member the barrier publishes this scope into. */
    public static final String RESULT_MEMBER = "writtenProjects"; //$NON-NLS-1$

    /**
     * The one wording for the published member, so five output schemas cannot describe the same
     * field differently. The wire strips output-schema descriptions, so its length is paid by
     * maintainers reading the source, not by every session's {@code tools/list}.
     */
    public static final String OUTPUT_SCHEMA_DESCRIPTION =
        "The projects this call wrote in. Their export queue is what the tool waits for before " //$NON-NLS-1$
            + "answering - a wait it cannot always observe (no derived-data service, or the shared " //$NON-NLS-1$
            + "budget already spent), so this names what was waited FOR, not a guarantee that the " //$NON-NLS-1$
            + "bytes have landed. An empty array means the call wrote in no project of its own: a " //$NON-NLS-1$
            + "finding, not a silence. The member is ABSENT when the tool could not determine where " //$NON-NLS-1$
            + "it wrote. A " //$NON-NLS-1$
            + "project the platform's own cascade may have touched is awaited but not listed here, " //$NON-NLS-1$
            + "because 'may have' must not be published as 'wrote'."; //$NON-NLS-1$

    /** Projects this call wrote in. Insertion-ordered so the wait order is reproducible. */
    private final Set<String> written = new LinkedHashSet<>();

    /** Projects the platform's cascade may have written in. */
    private final Set<String> cascadedInto = new LinkedHashSet<>();

    private boolean queuedNothing;

    private String undeterminableReason;

    private List<String> undeterminableFallback;

    /**
     * Runs {@code work} with {@code scope} bound to the current thread, and gives the previous
     * binding back afterwards.
     * <p>
     * The only way to bind one, so the restore cannot be forgotten and cannot be skipped by an
     * {@link Error}: leaving the UI thread bound to a finished call would charge every later export
     * to it. Saving and restoring rather than clearing, because a NESTED call is reachable - the
     * consent dialog and {@code resync_to_disk} both pump nested SWT event loops, in which another
     * request's UI runnable can run - and the inner call must not swallow the outer one's scope.
     * <p>
     * Public so a tool's own test can observe what its declarations do; production code reaches it
     * only from {@code AbstractMetadataWriteTool.execute}.
     *
     * @param scope the scope to bind; must not be {@code null}
     * @param work the work to run under it
     */
    public static void runWithScope(WriteScope scope, Runnable work)
    {
        WriteScope previous = BOUND.get();
        BOUND.set(scope);
        try
        {
            work.run();
        }
        finally
        {
            if (previous == null)
            {
                BOUND.remove();
            }
            else
            {
                BOUND.set(previous);
            }
        }
    }

    /**
     * Records that an export was submitted for {@code project}. Called from the single place this
     * plugin hands save tasks to the platform, so that submitting IS declaring.
     * <p>
     * Called whatever the platform answered. A refused submission is not evidence that this call did
     * not write - the model change stands, and for a list submission that threw part way through, it
     * is not even evidence that nothing was queued. The project identity is knowledge we have either
     * way, and dropping it is how the barrier used to end up waiting for the wrong project.
     *
     * @param project the project whose export was submitted; ignored when {@code null}
     */
    public static void recordExportSubmission(IProject project)
    {
        WriteScope scope = BOUND.get();
        if (scope != null)
        {
            scope.wrote(project);
        }
    }

    /**
     * Static face of {@link #wrote(IProject)}, for a tool that wrote without submitting an export.
     * <p>
     * The explicit methods are reached statically for the same reason the choke point is: the
     * statement belongs at the line that knows it, and that line is often deep inside a private
     * helper that has no business taking a scope parameter. A no-op outside a write tool's call, so
     * a helper shared with a read tool stays correct.
     *
     * @param project the project written in; ignored when {@code null}
     */
    public static void recordWrite(IProject project)
    {
        WriteScope scope = BOUND.get();
        if (scope != null)
        {
            scope.wrote(project);
        }
    }

    /**
     * Static face of {@link #wrote(String)}.
     *
     * @param projectName the project written in; ignored when {@code null} or empty
     */
    public static void recordWrite(String projectName)
    {
        WriteScope scope = BOUND.get();
        if (scope != null)
        {
            scope.wrote(projectName);
        }
    }

    /**
     * Static face of {@link #cascadedInto(IProject)}.
     *
     * @param project the cascade participant; ignored when {@code null}
     */
    public static void recordCascade(IProject project)
    {
        WriteScope scope = BOUND.get();
        if (scope != null)
        {
            scope.cascadedInto(project);
        }
    }

    /**
     * Static face of {@link #cascadedInto(String)}.
     *
     * @param projectName the cascade participant; ignored when {@code null} or empty
     */
    public static void recordCascade(String projectName)
    {
        WriteScope scope = BOUND.get();
        if (scope != null)
        {
            scope.cascadedInto(projectName);
        }
    }

    /** Static face of {@link #queuedNothing()}. */
    public static void recordNothingQueued()
    {
        WriteScope scope = BOUND.get();
        if (scope != null)
        {
            scope.queuedNothing();
        }
    }

    /**
     * Static face of {@link #undeterminable(String, Collection)}.
     *
     * @param reason why the write scope cannot be determined
     * @param fallbackProjects the projects to wait for instead
     */
    public static void recordUndeterminable(String reason, Collection<String> fallbackProjects)
    {
        WriteScope scope = BOUND.get();
        if (scope != null)
        {
            scope.undeterminable(reason, fallbackProjects);
        }
    }

    /**
     * States that this call wrote in {@code project}.
     * <p>
     * Needed only where a write queued no export of its own - the platform had already scheduled the
     * save (a refactoring), or there was no top-object FQN to name. Where the tool submits an export,
     * the choke point has already recorded it and calling this again is harmless.
     *
     * @param project the project written in; ignored when {@code null}
     */
    public void wrote(IProject project)
    {
        if (project != null)
        {
            wrote(project.getName());
        }
    }

    /**
     * Name-taking form of {@link #wrote(IProject)}, so the decision can be exercised without a live
     * workspace - the barrier waits by name anyway.
     *
     * @param projectName the project written in; ignored when {@code null} or empty
     */
    public void wrote(String projectName)
    {
        if (projectName != null && !projectName.isEmpty())
        {
            written.add(projectName);
        }
    }

    /**
     * States that the PLATFORM's cascade may have written in {@code project} - this call authorized
     * the work but did not perform it and cannot see what it touched.
     * <p>
     * Waited for, never able to refuse, and deliberately NOT published as a project this call wrote
     * in: the honest set is "every open extension of the target", which is what EDT SCANS, not what it
     * writes.
     *
     * @param project the cascade participant; ignored when {@code null}
     */
    public void cascadedInto(IProject project)
    {
        if (project != null)
        {
            cascadedInto(project.getName());
        }
    }

    /**
     * Name-taking form of {@link #cascadedInto(IProject)}.
     *
     * @param projectName the cascade participant; ignored when {@code null} or empty
     */
    public void cascadedInto(String projectName)
    {
        if (projectName != null && !projectName.isEmpty())
        {
            cascadedInto.add(projectName);
        }
    }

    /**
     * States that this call put nothing in any export queue - a finding, not a silence. Overridden by
     * any later record, so it is safe to state as soon as it is known.
     */
    public void queuedNothing()
    {
        queuedNothing = true;
    }

    /**
     * States that what was written cannot be determined here, and names what to wait for instead.
     * <p>
     * For the one case the platform keeps to itself: {@code apply_quick_fix} hands the work to EDT's
     * fix extension point, which reports nothing about what it touched. The classification stays -
     * it is the best signal available - but it is now a decision recorded at the call whose opacity
     * is the reason, rather than a guess re-derived from the response afterwards, and it publishes
     * nothing, because "I could not tell" must not be shown to a caller as "I wrote nowhere".
     *
     * @param reason why the write scope cannot be determined; kept for diagnostics
     * @param fallbackProjects the projects to wait for instead; may be empty, never {@code null}
     */
    public void undeterminable(String reason, Collection<String> fallbackProjects)
    {
        this.undeterminableReason = reason;
        this.undeterminableFallback = new ArrayList<>(fallbackProjects);
    }

    /**
     * @return the projects declared as written in, in the order they were declared - the same set
     *     the barrier waits for strictly and publishes to the caller
     */
    public List<String> writtenProjects()
    {
        return new ArrayList<>(written);
    }

    /**
     * @return the projects declared as ones the platform's cascade may have written in - waited for,
     *     never able to refuse, never published
     */
    public List<String> cascadeProjects()
    {
        return new ArrayList<>(cascadedInto);
    }

    /** @return whether the call stated that it queued nothing */
    public boolean statedNothingQueued()
    {
        return queuedNothing;
    }

    /** @return why the scope could not be determined, or {@code null} */
    String undeterminableReason()
    {
        return undeterminableReason;
    }

    /**
     * What the barrier does with this call.
     *
     * @param defaultProjects what to wait for when the call said nothing at all - today's default,
     *            kept so a tool that declares nothing behaves exactly as it does now
     * @return the verdict; never {@code null}
     */
    Verdict verdict(Collection<String> defaultProjects)
    {
        if (!written.isEmpty() || !cascadedInto.isEmpty())
        {
            // A record beats every statement, whatever order they arrived in: it is the hardest
            // fact available - something really was written or authorized.
            return new Verdict(new ArrayList<>(written), removeAll(cascadedInto, written), true);
        }
        if (undeterminableFallback != null)
        {
            // Ahead of queuedNothing() on purpose. A call that states BOTH has contradicted itself,
            // and of the two the safe reading is the one that waits more and claims less: publishing
            // "[] - I queued nothing" while a fallback was named would drop that wait AND assert a
            // finding the same call has just said it cannot make. No tool states both today.
            return new Verdict(new ArrayList<>(undeterminableFallback), Collections.emptyList(), false);
        }
        if (queuedNothing)
        {
            return new Verdict(new ArrayList<>(), Collections.emptyList(), true);
        }
        return new Verdict(new ArrayList<>(defaultProjects), Collections.emptyList(), false);
    }

    /**
     * A project written in AND named as a cascade participant is one project, and the stronger grade
     * wins: it must be able to refuse.
     */
    private static List<String> removeAll(Set<String> from, Set<String> remove)
    {
        List<String> result = new ArrayList<>(from);
        result.removeAll(remove);
        return result;
    }

    /** The barrier's reading of one call: what to wait for, how hard, and what to publish. */
    static final class Verdict
    {
        /** Projects this call wrote in: waited for, and a stall refuses. */
        private final List<String> written;

        /** Projects the platform's cascade may have touched: waited for, a stall never refuses. */
        private final List<String> cascaded;

        /** Whether the call actually stated its scope, i.e. whether it may be published. */
        private final boolean declared;

        Verdict(List<String> written, List<String> cascaded, boolean declared)
        {
            this.written = written;
            this.cascaded = cascaded;
            this.declared = declared;
        }

        List<String> written()
        {
            return written;
        }

        List<String> cascaded()
        {
            return cascaded;
        }

        boolean declared()
        {
            return declared;
        }

        /**
         * @return the projects to publish to the caller, sorted for a stable response; {@code null}
         *     when the call did not state its scope - and {@code null} means the member is left out
         *     entirely, which is how "unknown" stays distinguishable from the empty list that means
         *     "this call queued nothing"
         */
        Collection<String> publishable()
        {
            return declared ? new TreeSet<>(written) : null;
        }
    }
}
