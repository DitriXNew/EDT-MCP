/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.BmBatchSessionException;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.md.extension.IMdAdoptedPropertyAccess;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Gives the module of an object ADOPTED into a configuration extension the state EDT's UI gives it when
 * code is added to it, {@code Extended}, and records it in the owner's {@code .mdo} at once (issue #711).
 *
 * <h2>The state, and where EDT records it</h2>
 * An adopted object's {@code .mdo} carries one state per module in its extension block -
 * {@code <module>Extended</module>} for a common module, {@code <objectModule>}, {@code <managerModule>},
 * {@code <commandModule>}, {@code <sessionModule>} and so on for the others. When code is added to an
 * adopted module in EDT's UI, EDT records {@code Extended} there (its module adopter sets the state with
 * the platform call this step makes). EDT also switches the state from {@code None} to
 * {@code Extended} from a resource-synchronization listener ({@code BslModuleExtensionBuildDispatcherRule})
 * that runs a few seconds after a {@code .bsl} of an adopted object is synchronized - but only in the
 * MODEL: the listener commits through {@code IBmModel#execute}, which never saves, so the {@code .mdo}
 * keeps no line. Until the listener has run, the model is {@code None} as well.
 * <p>
 * Nothing here claims what the missing line costs the extension EDT exports or loads into an infobase:
 * on EDT 2026.2.0 the XML dump carried the module of an adopted object also when its state was
 * {@code Checked} and when the object had no extension block, and the infobase load was not tested. The
 * step brings the {@code .mdo} in line with what EDT's UI records, and its texts say no more than that.
 *
 * <h2>What this does</h2>
 * After a module file is written, {@link #ensureExtended} repeats the listener's sequence with the
 * listener's own calls: the owner is found from the file's path, the feature from the file's stem, and
 * {@link IMdAdoptedPropertyAccess#hasPropertyState} with {@code NONE} gates
 * {@link IMdAdoptedPropertyAccess#setPropertyState} with {@code EXTENDED}, made on the transaction's copy
 * of the owner inside a write task. Then the owner's TOP object is force-exported - also when the state
 * was already {@code Extended}, which is how the listener leaves it, so the {@code .mdo} is brought in
 * line with the model - and the export is confirmed by the FILE: after the export queue's wait, the
 * {@code .mdo} is read back from disk for the module's own state line until it carries it, within the
 * metadata writes' one budget - counted from BEFORE the export request, so the request, the queue's wait
 * and the reads share it. The request itself is counted, not bounded: it is the shared export call,
 * {@link BmTransactions#forceExportToDisk}, which create_metadata, modify_metadata and delete_metadata make too,
 * and EDT can keep the caller's thread waiting in it (see {@link #EXPORT_DEADLINE_MS}) - the budget bounds what
 * follows the request. No read starts once the budget is spent, and each read is bounded by what is left: it
 * runs in a {@link BoundedJob}, so a file system that does not answer (a network disk, a {@code .mdo} too large
 * for what is left) cannot hold the call past the budget - but for BoundedJob's short grace (0.5s) for a read
 * whose job had not started by the deadline - and a read not answered in time confirms nothing. Such a read
 * keeps its job until the file system answers, so while an earlier read of the project's files is out - its job
 * is reading, or still waiting to run - no other one starts - the file counts as not read back - until that job
 * ends, also without having run the read, or its claim lapses, three times the budget after it was taken: a file
 * system that never answers costs a project at most one hung read per lapse, not one per write (see
 * {@link #readBounded}). The queue cannot confirm the export: EDT's export
 * task processor catches a failed file write (a locked or read-only file, a full disk), so the queue DRAINS
 * all the same, and an UNOBSERVABLE queue says nothing either way (see {@link BuildUtils#waitForDiskExport}).
 * An export the platform does not accept is never waited for - nothing was queued - but the file still
 * decides it: the {@code .mdo} is read once, at once - bounded by what the request left of the budget, and
 * not at all when it left nothing or an earlier read of the project's files still holds its claim - so one
 * that already carries the line (recorded in EDT's UI, or by an earlier write) confirms it all the same.
 * <p>
 * An export the file does not confirm fails the write - the text written, nothing rolled back - whether the
 * step switched the state or found it already {@code Extended}: the listener flips the model in reaction to
 * the same write, so which of the two the step finds is a race, and the file, the one evidence, gets one
 * verdict. The ordinary-application module is the exception (below).
 * <p>
 * The step runs in a configuration extension only. EDT's project registry is asked first, but it also
 * says "no" for a project it has not registered - as while it disposes and restarts a project's context,
 * which can fall between the write and this step - so its "no" is checked against the project's
 * persistent nature: only a project that verifiably lacks the extension's nature is no extension.
 * <p>
 * It never switches a state back (EDT keeps {@code Extended} when a module is emptied), never touches
 * {@code Checked} or {@code Notify}, and never creates a missing extension block - the platform's set
 * would create one, and throws for an abstract block class; the {@code NONE} gate keeps the listener
 * away from that too, because the platform reports a missing block as "not None". Leaving
 * {@code Checked} and {@code Notify} as they are, the step follows EDT's listener, which asks the same
 * {@code NONE} question first - not EDT's module adopter ({@code BslModuleAdopterParticipant}, which
 * EDT's UI runs when it adds a method to an extension): that sets {@code Extended} without asking
 * whenever it adopts a module other than a form's (2026.2.0 bytecode). A {@code Checked} or {@code Notify} state and a
 * missing block are reported with the written text; they do not fail the write.
 * <p>
 * EDT does not list the root's ordinary-application module among the properties an extension can
 * extend: none of its property-type delegates, 8.3.8 to 8.5.1, names it, while those of 8.3.9 to 8.5.1
 * list the root's session, managed-application and external-connection modules (8.3.8's lists no module
 * at all). It is switched like the others, but a step that cannot finish on it does not fail the write.
 *
 * <h2>Boundaries</h2>
 * The owner is read off the STRICT reading of the module path
 * ({@link VendorSupportGuard#moduleOwnerFqn(String, boolean)}): {@code <Dir>/<Name>/<Stem>.bsl},
 * {@code <Dir>/<Name>/Commands/<Command>/CommandModule.bsl} and {@code Configuration/<Stem>.bsl}. So it
 * does not cover the modules of an external data source's tables, cubes and dimension tables (deeper
 * paths), nor {@code Bot}, {@code IntegrationService} and {@code WebSocketClient} modules (no source
 * directory is mapped for those types): their state has to be set in EDT. A recalculation's module has
 * no extension state at all, and a form's module needs none: the form's own state
 * ({@code <form>Extended</form>}) is set when the form is adopted.
 * <p>
 * Until EDT has registered the project's BM model, an adopted object cannot be told from one of the
 * extension's own: a write into any module of the strict reading then fails as a transient error. An
 * extension project EDT has not (re)registered yet is still told an extension by its persistent nature, so
 * it gets the same answer, never a silent skip.
 * <p>
 * A dry-run mode of {@code write_module_source} (issue #465) must skip this step: it changes the model
 * and exports the owner's {@code .mdo}.
 */
public final class AdoptedModuleState
{
    /** The frontmatter key a write reports this step's outcome under. */
    public static final String FRONT_MATTER_KEY = "moduleExtensionState"; //$NON-NLS-1$

    /**
     * The one budget for the owner's {@code .mdo} export - the export request, the export queue's wait and the
     * reads of the file back together, counted from BEFORE the request: the budget the metadata write tools give
     * their own export (AbstractMetadataWriteTool's EXPORT_DEADLINE_MS, whose javadoc carries the measurement -
     * 0.02-0.15s on a healthy workspace).
     * <p>
     * The request is counted, NOT bounded: it is the shared export call, {@link BmTransactions#forceExportToDisk},
     * which create_metadata, modify_metadata and delete_metadata make too, run on the caller's thread, and EDT can
     * keep it there (2026.1 and 2026.2 bytecode of {@code IBmModelManager.forceExport}). It begins a BM read-only
     * transaction to build the save task, and beginning one waits with no timeout, ignoring interrupts, while a BM
     * service operation runs or is waiting to run; then, holding the project's synchronization lock - which it may
     * first have to wait for - it hands the task to EDT's derived-data pipeline and waits for the pipeline to take it
     * in, a wait EDT caps at 100s but checks only between waits that have no timeout of their own. A request EDT
     * keeps past the budget holds the call until it returns, and nothing follows it then - no queue wait, no read:
     * the budget bounds what follows the request. Bounding the request is the canon's to do, for all its callers at
     * once.
     */
    static final long EXPORT_DEADLINE_MS = 60_000L;

    /**
     * The smallest remainder of the budget still worth a wait for the export queue - the rule
     * AbstractMetadataWriteTool's and WrittenObjectMarkers' MIN_USEFUL_WAIT_MS apply to their own waits. Below a
     * second a wait cannot plausibly see the queue drain, while it still starts a platform wait that cannot be
     * cancelled and takes the project's wait slot, whose claim lapses only after three times its own timeout. A
     * smaller remainder is not waited with: the queue counts as not seen draining, and the reads get the rest.
     */
    private static final long MIN_USEFUL_WAIT_MS = 1_000L;

    /**
     * The first pause between two reads of the owner's {@code .mdo} while its state line is not there; each
     * pause doubles, up to {@link #MDO_POLL_MAX_MS}: a prompt second look, then no busier than once a second.
     */
    static final long MDO_POLL_FIRST_MS = 100L;

    /** The longest pause between two reads of the owner's {@code .mdo}. */
    static final long MDO_POLL_MAX_MS = 1_000L;

    /**
     * The projects whose current read claim has refused a read and said so in the log (see {@link #readBounded}):
     * one line per claim, not one per refusal - while a claim holds, the poll is refused about once a second. A
     * project leaves the set when one of its reads takes a claim. Best effort: a refusal that races a new claim can
     * drop or repeat a line; the log only says why a read was not started.
     */
    private static final Set<String> REFUSAL_LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * The persistent nature of a configuration-extension project - {@code ICoreConstants.V8_EXTENSION_NATURE}
     * of EDT 2026.1 and 2026.2 alike. Named here, as ProjectStateChecker names it: the constant's package is
     * not imported.
     */
    private static final List<String> EXTENSION_NATURE =
        Collections.singletonList("com._1c.g5.v8.dt.core.V8ExtensionNature"); //$NON-NLS-1$

    /** The {@code .mdo} element of an object's extension block. */
    private static final String EXTENSION_ELEMENT = "extension"; //$NON-NLS-1$

    /** The {@code .mdo} element holding a contained object's Name. */
    private static final String NAME_ELEMENT = "name"; //$NON-NLS-1$

    private static final String CONFIGURATION = "Configuration"; //$NON-NLS-1$
    private static final String BSL = ".bsl"; //$NON-NLS-1$

    /** The root's {@code .mdo}: the one top object outside a type directory, as DeleteMetadataTool names it. */
    private static final String CONFIGURATION_MDO = "src/Configuration/Configuration.mdo"; //$NON-NLS-1$

    /**
     * What EDT itself does with the state - the verified facts the step rests on, and no more: its UI
     * records the state in the {@code .mdo}, its listener only in the model.
     */
    private static final String PARITY = "EDT's UI records this state in the .mdo when code is added to an " //$NON-NLS-1$
        + "adopted module; EDT's own listener sets it only in the model, a few seconds after the file is " //$NON-NLS-1$
        + "written, and never saves the .mdo."; //$NON-NLS-1$

    /**
     * The call that re-runs this step alone. An empty append rewrites the module's current text: its line
     * breaks are normalized (one trailing break, the module's own separator), and the whole module goes
     * through the syntax check again - so a module written with the check skipped needs it skipped again.
     */
    private static final String RERUN_CALL = "write_module_source on the same module with mode=append and an " //$NON-NLS-1$
        + "empty source - it rewrites the module's current text, line ends normalized (add " //$NON-NLS-1$
        + "skipSyntaxCheck=true if the module was written with it)"; //$NON-NLS-1$

    /** The step's own rerun, as an instruction. */
    private static final String RERUN = "run this step again: call " + RERUN_CALL; //$NON-NLS-1$

    /** The step's own rerun, as the operation BmModelResolver's transient-model error tells to retry. */
    private static final String RETRY_OPERATION = "this step: call " + RERUN_CALL; //$NON-NLS-1$

    /** Said by every failure: the text IS written, so repeating the edit would apply it twice. */
    private static final String STANDS = "The write stands: do not re-send the original edit."; //$NON-NLS-1$

    /**
     * Why a step that cannot finish on the ordinary-application module does not fail the write: a fact of
     * EDT's own property types - no delegate, 8.3.8 to 8.5.1, lists that module (see the class comment).
     * It compares that module with no other: which modules a delegate lists depends on the platform
     * version.
     */
    private static final String ORDINARY_NOTE = "EDT does not list the ordinary-application module among the " //$NON-NLS-1$
        + "properties an extension can extend, so this does not fail the write."; //$NON-NLS-1$

    /** How the step ended. */
    public enum Outcome
    {
        /** Not the module of an adopted object in an extension: nothing was done. */
        NOT_APPLICABLE,
        /**
         * The state was None and is now Extended, and the owner's {@code .mdo}, read back, carries the line - or,
         * on the ordinary-application module, a note says it does not.
         */
        SWITCHED,
        /**
         * The state already was Extended, and the owner's {@code .mdo}, its export requested again, carries the line
         * when read back - or, on the ordinary-application module, a note says it does not.
         */
        ALREADY_EXTENDED,
        /** The adopted owner has no extension block, so there is no state to record: a success, with a note. */
        NO_EXTENSION_BLOCK,
        /**
         * The state is Checked or Notify: like EDT's own listener, this step switches only None, so the
         * state is left as it is - a success with a note.
         */
        OTHER_STATE,
        /** The step could not be carried out. */
        FAILED,
        /**
         * The state is Extended in EDT's model - switched by this call, or found so - but the export of the
         * owner's {@code .mdo} is not confirmed: the file was not seen carrying the module's state line - read
         * back within the budget after an accepted export request, read ONCE when the export could not be
         * scheduled, each read only while the budget lasts and bounded by what is left, and not read at all when
         * there is no file or line to look for, no time left, or an earlier read of the project's files still holding
         * its claim.
         * One outcome for both: which of the two the step found is a race with EDT's own listener.
         */
        EXPORT_NOT_CONFIRMED
    }

    /**
     * How the export of the owner's {@code .mdo} ended - decided by the FILE, never by the queue alone (the queue
     * drains also when EDT fails to write the file): read back after the queue's wait, or read once when the
     * export could not be scheduled.
     */
    enum Export
    {
        /**
         * The {@code .mdo}, read back, carries the module's state line - also when the export could not be
         * scheduled and the file, read once, already carried it. The only confirmed export.
         */
        WRITTEN,
        /**
         * The export was requested and the queue drained or could not be observed, but the {@code .mdo}, read
         * back until the budget ran out, did not carry the line.
         */
        MISSING,
        /**
         * The export was requested and the queue drained or could not be observed, but the {@code .mdo} could
         * not be read back - or parsed - until the budget ran out: also a read the budget cut off, a read not started
         * while an earlier read of the project's files was still out, and no read at all when the queue's wait left
         * no time for one. Never MISSING then: only a file read and found without the line says that.
         */
        UNREADABLE,
        /**
         * The export was requested, the queue was not seen draining within the budget - its wait ran out, or the
         * request itself used the budget up - and the line was not seen.
         */
        PENDING,
        /**
         * The export could not be scheduled - the platform accepted no request, or exporting threw - and the
         * {@code .mdo}, read once while the budget lasted, was not seen carrying the line: absent, unreadable or not
         * read in time, or not read at all - no file or line to look for, no time left, or an earlier read of the
         * project's files still holding its claim.
         */
        NOT_SCHEDULED
    }

    /** What one read of the owner's {@code .mdo} found for the module's state line. */
    enum LineOnDisk
    {
        /** The owner's own extension block carries the module's element with {@code Extended}. */
        PRESENT,
        /** The file was read and parsed, and the owner's block does not carry that line. */
        ABSENT,
        /**
         * The file could not be read or parsed - EDT may be writing it - or not within the time the read was given,
         * or it was not read at all because the job of an earlier read of the project's files had not ended and its
         * claim had not lapsed; so it says nothing either way.
         */
        UNREADABLE
    }

    /**
     * The platform calls the step makes, behind one seam so a test can run the whole decision against
     * an in-memory model and observe what is exported, and in which order. Package-visible: only the
     * tests, in this package, supply another one.
     */
    interface Environment
    {
        /**
         * @param project the project the module was written in
         * @return whether EDT's project registry names it a configuration extension - {@code false} also for
         *     a project it has not registered (yet, or again)
         */
        boolean isExtensionProject(IProject project);

        /**
         * @param project the project the module was written in
         * @return whether its persistent description carries the extension's nature, or {@code null} when its
         *     natures cannot be read - which is never "no"
         */
        Boolean hasExtensionNature(IProject project);

        /**
         * @param project the project the module was written in
         * @return its BM model, or the unavailable resolution - with its actionable error - while EDT
         *     has not registered one; never {@code null}
         */
        BmModelResolver.Resolution model(IProject project);

        /** @return EDT's adopted-property service, or {@code null} when it is not available */
        IMdAdoptedPropertyAccess access();

        /**
         * @param owner the module's owner, read inside a model read
         * @return the FQN of the top object whose {@code .mdo} holds the owner's state
         */
        String topObjectFqn(EObject owner);

        /**
         * Requests the export through the shared export call, {@link BmTransactions#forceExportToDisk}: on the
         * caller's thread, and not bounded here - EDT can keep the thread in it (see
         * {@link AdoptedModuleState#EXPORT_DEADLINE_MS}).
         *
         * @param project the project
         * @param topObjectFqn the top object to export
         * @return whether the platform accepted an export request
         */
        boolean forceExport(IProject project, String topObjectFqn);

        /**
         * @param project the project
         * @param timeoutMs what the export request left of the budget, always positive
         * @return how the export wait ended
         */
        BuildUtils.DiskExportState awaitExport(IProject project, long timeoutMs);

        /**
         * Reads a {@code .mdo} as it is on disk NOW and looks for the module's state line in it - within
         * {@code timeoutMs}: the caller stops waiting then, and a read that has not answered by then is
         * UNREADABLE. While the job of an earlier read of the project's files has not ended - it is reading, or still
         * waiting to run - and its claim has not lapsed, the platform's read starts none and is UNREADABLE at once
         * ({@link AdoptedModuleState#readBounded}).
         *
         * @param project the project
         * @param mdoPath the project-relative {@code .mdo}
         * @param line the line to look for
         * @param timeoutMs how long the read may take: what is left of the budget, always positive
         * @return what the read found; never {@code null}
         */
        LineOnDisk readStateLine(IProject project, String mdoPath, StateLine line, long timeoutMs);

        /** @return a monotonic clock, in milliseconds */
        long currentTimeMillis();

        /**
         * @param ms how long to wait
         * @return {@code false} when the wait was interrupted - the interrupt flag is kept for the caller
         */
        boolean pause(long ms);
    }

    /** The production platform. */
    static final Environment PLATFORM = new Environment()
    {
        @Override
        public boolean isExtensionProject(IProject project)
        {
            return ExtensionOriginUtils.isExtensionProject(project);
        }

        @Override
        public Boolean hasExtensionNature(IProject project)
        {
            return ProjectContext.hasAnyNature(project, EXTENSION_NATURE);
        }

        @Override
        public BmModelResolver.Resolution model(IProject project)
        {
            return BmModelResolver.resolve(project);
        }

        @Override
        public IMdAdoptedPropertyAccess access()
        {
            return ExtensionServices.adoptedPropertyAccess();
        }

        @Override
        public String topObjectFqn(EObject owner)
        {
            // The model's own identity of the top object, as AdoptMetadataObjectTool takes it - never
            // the path's spelling, which the platform would not find (an export of a name it does not
            // know is silently not scheduled).
            if (!(owner instanceof IBmObject))
            {
                return null;
            }
            IBmObject top = ((IBmObject)owner).bmGetTopObject();
            return top == null ? null : top.bmGetFqn();
        }

        @Override
        public boolean forceExport(IProject project, String topObjectFqn)
        {
            return BmTransactions.forceExportToDisk(project, topObjectFqn);
        }

        @Override
        public BuildUtils.DiskExportState awaitExport(IProject project, long timeoutMs)
        {
            return BuildUtils.waitForDiskExport(project, timeoutMs);
        }

        @Override
        public LineOnDisk readStateLine(IProject project, String mdoPath, StateLine line, long timeoutMs)
        {
            return readBounded(BoundedJob::run, projectName(project), "Reading back " + mdoPath, timeoutMs, //$NON-NLS-1$
                () -> line.in(mdoBytes(project, mdoPath)));
        }

        @Override
        public long currentTimeMillis()
        {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }

        @Override
        public boolean pause(long ms)
        {
            try
            {
                Thread.sleep(ms);
                return true;
            }
            catch (InterruptedException e)
            {
                // The call is being cancelled: stop reading, and keep the interrupt for the caller.
                Thread.currentThread().interrupt();
                return false;
            }
        }
    };

    /**
     * The bytes of a project's file as they are on the FILE SYSTEM - what EDT's export wrote, not the
     * workspace's view of it - read through NIO, which on Windows shares the file for writing and deleting,
     * so a read never stands in the way of the export it waits for.
     *
     * @param project the project
     * @param projectRelativePath the file
     * @return the bytes, or {@code null} when the file has no location or cannot be read
     */
    private static byte[] mdoBytes(IProject project, String projectRelativePath)
    {
        try
        {
            IPath location = project.getFile(new Path(projectRelativePath)).getLocation();
            return location == null ? null : Files.readAllBytes(location.toFile().toPath());
        }
        catch (IOException | RuntimeException e) // NOSONAR an unreadable file is an answer (not confirmed), not a crash
        {
            return null;
        }
    }

    /**
     * Runs work under a deadline and reports when its job has ended: BoundedJob's run with a completion callback in
     * production, a fake in a test. Package-visible for tests.
     */
    @FunctionalInterface
    interface BoundedRunner
    {
        /**
         * @param jobName the job's name in EDT's progress view
         * @param timeoutMs how long the caller waits for the work
         * @param work the work
         * @param jobEnded run once when the work's job has ended - the work returned, or the job was cancelled or
         *     removed without running it (EDT's {@code done} notification, BoundedJob's completion callback) - also
         *     when that is after the run answered; a job that may still run has not ended. It runs on whatever thread
         *     EDT delivers the notification on, so it must not block.
         * @return how the run ended
         */
        BoundedJob.Result run(String jobName, long timeoutMs, BoundedJob.IBoundedWork work, Runnable jobEnded);
    }

    /**
     * One read of a {@code .mdo}, bounded: the read runs in a {@link BoundedJob} - off the caller's thread - and the
     * caller waits for it no longer than {@code timeoutMs}, so a file system that does not answer (a network disk) or
     * a file too large for what is left of the budget cannot hold the call open with the write already done. Only a
     * read that COMPLETED in time is an answer. One the deadline caught (running, or before it started), one that
     * never ran (a suspended job manager), one that threw, one that could not be scheduled, and a wait in which the
     * caller's thread was interrupted - BoundedJob keeps that thread's interrupt flag - are UNREADABLE: a file not read
     * in time proves nothing either way. Nothing left of the budget starts no read at all.
     * <p>
     * A read the deadline caught keeps its job - one of EDT's worker threads - until the file system answers, and
     * the call does not wait for it. So that such reads do not pile up one per write, a read first claims its
     * project - the marker read's rule, on a key of its own ({@link BuildUtils#beginStateLineRead}): the claim is
     * taken before a job is scheduled, and while an earlier read's claim holds - its job has not ended, and the claim
     * has not lapsed - no job is started and the read is UNREADABLE at once, as one the deadline cut. The first
     * refusal by a claim is logged at INFO and the ones after it are not: while the claim holds, the poll is refused
     * about once a second.
     * <p>
     * The claim is released when the read's job ends. The job releases it itself when the read returns, so a caller
     * that has the answer finds it released; the job's terminal notification - EDT's {@code done}, through
     * BoundedJob's completion callback - releases it as well, and that notification also comes for a job that ends
     * WITHOUT running the read: one cancelled before it started, by our deadline or by anything else (the job manager
     * of 2026.1 and 2026.2, org.eclipse.core.jobs 3.15, notifies done inside the cancel of a waiting job, and when
     * the worker ends a job cancelled at the start line). The claim of a read that could not be scheduled, or whose
     * job our own cancel kept from starting, is released at once too. A job that may still run keeps the claim until
     * it ends - one the deadline cut while it ran, or one a suspended job manager still holds, for which BoundedJob
     * answers NOT_RUN and which EDT runs when the manager resumes unless something cancels it first - or until the
     * claim lapses: three times the step's whole budget after the claim was taken, elapsed on a monotonic clock, not
     * three times this read's share of it - a read cut with a sliver of the budget left would otherwise hold its claim
     * for a sliver - so a file system that never answers costs a project at most one hung read per lapse, not one per
     * write. A release only sets the claim's own flag: it never blocks the thread that delivers the notification, and a
     * late one cannot release a newer claim. A read whose job had not started at the deadline may take up to
     * BoundedJob's short grace (0.5s) more to establish that. Never throws an exception. Package-visible for tests.
     *
     * @param runner what runs the read under its deadline: BoundedJob in production
     * @param projectName the project whose files are read: the key of the read's claim
     * @param jobName the read's name in EDT's progress view
     * @param timeoutMs what is left of the budget; nothing left - zero or less - means no read, and no claim
     * @param read the read itself, unbounded
     * @return what the read found, or UNREADABLE
     */
    static LineOnDisk readBounded(BoundedRunner runner, String projectName, String jobName, long timeoutMs,
        Supplier<LineOnDisk> read)
    {
        return readBounded(runner, projectName, jobName, timeoutMs, read, LineOnDisk.UNREADABLE);
    }

    /**
     * The one bounded read of a project's files, whatever the read finds: {@link #readBounded(BoundedRunner, String,
     * String, long, Supplier)} with the answer for a read that is no answer as a parameter. Every rule of that read
     * holds here - the job, the deadline, only a read that COMPLETED in time is an answer, and the project's one claim
     * ({@link BuildUtils#beginStateLineRead}), taken before a job is scheduled, released when the job ends, and
     * lapsing three times the state-line step's budget after it was taken, whoever took it. Its second caller is
     * ExtensionRootBuilder's look at a new extension's root file and the files beside it, on the same claim: both read
     * files under the same project location, and a file system that does not answer one of them answers neither, so
     * one claim per project is what keeps such reads from piling up one hung job each. Never throws an exception.
     * Package-visible for tests.
     *
     * @param <T> what a read finds
     * @param runner what runs the read under its deadline: BoundedJob in production
     * @param projectName the project whose files are read: the key of the read's claim
     * @param jobName the read's name in EDT's progress view
     * @param timeoutMs what is left of the budget; nothing left - zero or less - means no read, and no claim
     * @param read the read itself, unbounded; a {@code null} it returns is no answer either
     * @param unreadable what to answer for a read that did not complete in time, was refused, threw or could not be
     *     scheduled - and when nothing was left of the budget; may be {@code null}
     * @return what the read found, or {@code unreadable}
     */
    static <T> T readBounded(BoundedRunner runner, String projectName, String jobName, long timeoutMs, // NOSONAR the read's bounds, its claim and its two answers, one each
        Supplier<T> read, T unreadable)
    {
        if (timeoutMs <= 0)
        {
            return unreadable;
        }
        AtomicBoolean returned = BuildUtils.beginStateLineRead(projectName, EXPORT_DEADLINE_MS);
        String logKey = String.valueOf(projectName); // the set takes no null
        if (returned == null)
        {
            // The job of an earlier read of this project's files has not ended: another would only add a hung job.
            // Said once per claim: while the claim holds, the poll is refused about once a second.
            if (REFUSAL_LOGGED.add(logKey))
            {
                Activator.logInfo("A previous .mdo read for " + projectName //$NON-NLS-1$
                    + " has not returned yet; not starting another one"); //$NON-NLS-1$
            }
            return unreadable;
        }
        // This read holds the claim now: the first read it refuses is said again.
        REFUSAL_LOGGED.remove(logKey);
        AtomicReference<T> answer = new AtomicReference<>();
        // The job's end - EDT's done notification, which comes also for a job that ends without running the read. It
        // only sets this claim's flag: it never blocks the thread EDT delivers the notification on.
        Runnable jobEnded = () -> returned.set(true);
        BoundedJob.Result result;
        try
        {
            result = runner.run(jobName, timeoutMs, monitor -> {
                try
                {
                    answer.set(read.get());
                }
                finally
                {
                    // Set from the JOB thread when the read returns: before the caller has the answer, or after the
                    // caller has gone if the deadline cut it.
                    returned.set(true);
                }
            }, jobEnded);
        }
        catch (RuntimeException e) // NOSONAR a read that could not be scheduled read nothing: an answer, not a crash
        {
            // No job was scheduled, so nothing is outstanding.
            returned.set(true);
            return unreadable;
        }
        if (result.getOutcome() == BoundedJob.Outcome.TIMED_OUT_BEFORE_START)
        {
            // Our cancel kept the job from starting: the read never ran and never will (BuildUtils' rule). Released
            // here, not only by the job's terminal notification, which may come a moment after the run answered.
            returned.set(true);
        }
        // A read is an answer only when it COMPLETED in time; one that published past the deadline is not.
        T seen = answer.get();
        return result.getOutcome() == BoundedJob.Outcome.COMPLETED && result.getFailure() == null && seen != null
            ? seen : unreadable;
    }

    private AdoptedModuleState()
    {
        // Utility class
    }

    /**
     * Where a module file sits in the metadata: the object that owns its state and the file's stem.
     * Package-visible for tests.
     */
    static final class ModuleAddress
    {
        /** {@code Type.Name}, {@code Type.Name.Command.Name}, or {@code Configuration}. */
        final String ownerFqn;
        /** The file name without {@code .bsl}, e.g. {@code ObjectModule}. */
        final String stem;

        ModuleAddress(String ownerFqn, String stem)
        {
            this.ownerFqn = ownerFqn;
            this.stem = stem;
        }

        /** @return whether the owner is the configuration root itself */
        boolean isConfiguration()
        {
            return CONFIGURATION.equals(ownerFqn);
        }

        /**
         * @return whether this is the root's ordinary-application module, which EDT does not list among the
         *     properties an extension can extend - known from the path alone, so also when the model could
         *     not be read
         */
        boolean isOrdinaryApplicationModule()
        {
            return isConfiguration() && moduleFeature(MdClassPackage.Literals.CONFIGURATION,
                stem) == MdClassPackage.Literals.CONFIGURATION__ORDINARY_APPLICATION_MODULE;
        }
    }

    /**
     * Reads the owner of a module file off its project-relative path through the one reading of a module
     * path, {@link VendorSupportGuard#moduleOwnerFqn(String, boolean)}, STRICTLY: only a module its owner
     * holds itself - {@code Configuration/<Stem>.bsl}, {@code <Dir>/<Name>/<Stem>.bsl} and
     * {@code <Dir>/<Name>/Commands/<Command>/CommandModule.bsl}. Anything deeper names a NESTED object (a
     * form, a recalculation, an external data source's table), and mapping it to the object above it -
     * as the lenient reading does for the vendor guard - would switch the wrong object's module: a
     * recalculation's {@code RecordSetModule.bsl} would switch its register's. Package-visible for tests.
     *
     * @param projectRelativePath the module file's project-relative path, may be {@code null}
     * @return the address, or {@code null} when the path is not one of the three shapes
     */
    static ModuleAddress moduleAddress(String projectRelativePath)
    {
        String ownerFqn = VendorSupportGuard.moduleOwnerFqn(projectRelativePath, true);
        if (ownerFqn == null)
        {
            return null;
        }
        // The strict reading admitted the path only with a <Stem>.bsl file name as its last segment.
        String path = projectRelativePath.replace('\\', '/');
        String file = path.substring(path.lastIndexOf('/') + 1);
        return new ModuleAddress(ownerFqn, file.substring(0, file.length() - BSL.length()));
    }

    /**
     * The owner's feature a module file stands for: EDT's own map - the first structural feature whose
     * name equals the stem, ignoring case - restricted to references to a BSL {@code Module}. EDT's
     * listener does not restrict it, so a file named after an attribute ({@code Comment.bsl}) would be
     * taken for a module there; here it is no module. A form has no such feature ({@code BasicForm}
     * holds a {@code form}, not a {@code module}), so form modules fall out on their own.
     * Package-visible for tests.
     *
     * @param ownerClass the owner's class, may be {@code null}
     * @param stem the file name without {@code .bsl}
     * @return the module feature, or {@code null}
     */
    static EStructuralFeature moduleFeature(EClass ownerClass, String stem)
    {
        if (ownerClass == null || stem == null)
        {
            return null;
        }
        for (EStructuralFeature feature : ownerClass.getEAllStructuralFeatures())
        {
            if (feature.getName().equalsIgnoreCase(stem))
            {
                return isModuleReference(feature) ? feature : null;
            }
        }
        return null;
    }

    private static boolean isModuleReference(EStructuralFeature feature)
    {
        if (!(feature instanceof EReference))
        {
            return false;
        }
        EClass type = ((EReference)feature).getEReferenceType();
        return type != null && BslPackage.Literals.MODULE.isSuperTypeOf(type);
    }

    /**
     * The project-relative {@code .mdo} of a top object, named by the model's own FQN of it - the object
     * the export writes: the root's special case, then {@link MetadataPathResolver#resolveTopObjectMdoPath},
     * as DeleteMetadataTool names a registering container's file.
     *
     * @param topObjectFqn the model's FQN of the top object, may be {@code null}
     * @return the path, or {@code null} when there is no FQN or it maps to no {@code .mdo}
     */
    static String mdoPathOf(String topObjectFqn)
    {
        if (topObjectFqn == null || topObjectFqn.isEmpty())
        {
            return null;
        }
        return CONFIGURATION.equals(topObjectFqn) ? CONFIGURATION_MDO
            : MetadataPathResolver.resolveTopObjectMdoPath(topObjectFqn);
    }

    /**
     * Where a module's state line sits in its owner's top-object {@code .mdo}: down the containment steps from
     * the top object to the owner - each an element named as the containment feature, told by its
     * {@code <name>} - then the owner's OWN {@code <extension>} block, in which the state is the element named
     * as the module feature ({@code <module>Extended</module>}, {@code <commandModule>Extended</commandModule>}).
     * Scoped on purpose: a top object's {@code .mdo} also holds the blocks of its forms, commands and
     * attributes, and a sibling command's {@code Extended} line is not this command's. Package-visible for tests.
     */
    static final class StateLine
    {
        /** The containment features from the top object down to the owner. */
        private final List<String> features;
        /** The Names of the objects along those steps. */
        private final List<String> names;
        /** The module feature's name: the state's element. */
        private final String element;

        private StateLine(List<String> features, List<String> names, String element)
        {
            this.features = features;
            this.names = names;
            this.element = element;
        }

        /**
         * @param owner the module's owner, as the model holds it (read inside a model read)
         * @param moduleFeature its module feature
         * @return the owner's state line, or {@code null} when the owner cannot be addressed by containment and
         *     Name from its top object
         */
        static StateLine of(EObject owner, EStructuralFeature moduleFeature)
        {
            if (owner == null || moduleFeature == null)
            {
                return null;
            }
            List<String> features = new ArrayList<>();
            List<String> names = new ArrayList<>();
            // Up to the top object: a configuration's collections only REFER to its top objects, so the
            // containment root is the top object whose .mdo the export writes; a command is CONTAINED by it.
            for (EObject current = owner; current.eContainer() != null; current = current.eContainer())
            {
                EReference containment = current.eContainmentFeature();
                String name = current instanceof MdObject ? ((MdObject)current).getName() : null;
                if (containment == null || name == null || name.isEmpty())
                {
                    return null;
                }
                features.add(0, containment.getName());
                names.add(0, name);
            }
            return new StateLine(features, names, moduleFeature.getName());
        }

        /**
         * Looks for the line in a {@code .mdo}'s bytes with the hardened parser ({@link SecureXml}).
         *
         * @param mdo the file's bytes, may be {@code null}
         * @return PRESENT, ABSENT, or UNREADABLE for no bytes or bytes that do not parse - a file being
         *     written proves nothing either way
         */
        LineOnDisk in(byte[] mdo)
        {
            if (mdo == null || mdo.length == 0)
            {
                return LineOnDisk.UNREADABLE;
            }
            Element current;
            try
            {
                DocumentBuilder builder = SecureXml.documentBuilderFactory().newDocumentBuilder();
                // Silent: a malformed file is an answer here, not a message for the console.
                builder.setErrorHandler(new DefaultHandler());
                current = builder.parse(new ByteArrayInputStream(mdo)).getDocumentElement();
            }
            catch (ParserConfigurationException | SAXException | IOException | RuntimeException e) // NOSONAR an unparsable file is an answer
            {
                return LineOnDisk.UNREADABLE;
            }
            for (int i = 0; current != null && i < features.size(); i++)
            {
                current = namedChild(current, features.get(i), names.get(i));
            }
            Element block = current == null ? null : firstChild(current, EXTENSION_ELEMENT);
            if (block == null)
            {
                return LineOnDisk.ABSENT;
            }
            for (Element state : children(block, element))
            {
                if (MdPropertyState.EXTENDED.getLiteral().equals(state.getTextContent().trim()))
                {
                    return LineOnDisk.PRESENT;
                }
            }
            return LineOnDisk.ABSENT;
        }

        /** The direct child element {@code feature} whose own {@code <name>} is {@code name}, or {@code null}. */
        private static Element namedChild(Element parent, String feature, String name)
        {
            for (Element child : children(parent, feature))
            {
                Element childName = firstChild(child, NAME_ELEMENT);
                if (childName != null && name.equals(childName.getTextContent().trim()))
                {
                    return child;
                }
            }
            return null;
        }

        private static Element firstChild(Element parent, String name)
        {
            List<Element> found = children(parent, name);
            return found.isEmpty() ? null : found.get(0);
        }

        /** The DIRECT child elements named {@code name} - never a deeper one: that would be another object's. */
        private static List<Element> children(Element parent, String name)
        {
            List<Element> found = new ArrayList<>();
            NodeList nodes = parent.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++)
            {
                Node node = nodes.item(i);
                if (node instanceof Element)
                {
                    Element child = (Element)node;
                    String local = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
                    if (name.equals(local))
                    {
                        found.add(child);
                    }
                }
            }
            return found;
        }

        /** E.g. {@code commands[Fill]/extension/commandModule} - how the fake platform and the logs name it. */
        @Override
        public String toString()
        {
            StringBuilder path = new StringBuilder();
            for (int i = 0; i < features.size(); i++)
            {
                path.append(features.get(i)).append('[').append(names.get(i)).append("]/"); //$NON-NLS-1$
            }
            return path.append(EXTENSION_ELEMENT).append('/').append(element).toString();
        }
    }

    /** What the state decision found. Package-visible for tests. */
    static final class Verdict
    {
        /** The kinds of finding. */
        enum Kind
        {
            /** Not an adopted object's module. */
            NOT_APPLICABLE,
            /** The state is None: switch it (or, after a write, it was switched). */
            SWITCH,
            /** The state is Extended. */
            ALREADY_EXTENDED,
            /** The adopted object has no extension block. */
            NO_EXTENSION_BLOCK,
            /** The state is Checked or Notify ({@link Verdict#detail}): left as it is. */
            OTHER_STATE,
            /** The decision itself failed ({@link Verdict#failure}, or {@link Verdict#detail} without one). */
            FAILED
        }

        final Kind kind;
        /** The state's name for OTHER_STATE ({@code Checked} or {@code Notify}); the reason for a FAILED with no exception. */
        final String detail;
        /** The platform's refusal for FAILED, when there was one. */
        final RuntimeException failure;

        private Verdict(Kind kind, String detail, RuntimeException failure)
        {
            this.kind = kind;
            this.detail = detail;
            this.failure = failure;
        }

        static Verdict of(Kind kind)
        {
            return new Verdict(kind, null, null);
        }

        /** @return whether the finding names the owner's {@code .mdo}: a state to export or to report */
        boolean namesTheMdo()
        {
            return kind == Kind.SWITCH || kind == Kind.ALREADY_EXTENDED || kind == Kind.NO_EXTENSION_BLOCK
                || kind == Kind.OTHER_STATE;
        }
    }

    /**
     * Decides, without writing anything, what the state of {@code feature} on {@code owner} calls for.
     * Not an adopted object's module (not an {@link MdObject}, not {@code ADOPTED}, or no module
     * feature) answers NOT_APPLICABLE without asking the service at all. Otherwise the service is asked
     * the listener's own question first ({@code NONE}?), then whether a block exists at all, because the
     * service answers "not None" for a missing block.
     * <p>
     * Every module is decided alike, the ordinary-application module included: {@code None} is to be
     * switched, as EDT's listener switches it; {@code Checked} or {@code Notify} is named, to be left as
     * it is. The state has only those four values, so a service that answers none of them contradicts
     * itself: a failure, never a state to name. The mapper's "no extension property / class" refusals
     * cannot occur for an owner {@link #moduleAddress} admits - each has an extension class carrying the
     * module state - so one is platform drift or a corrupted block, and answered FAILED, never silently
     * skipped. Package-visible for tests.
     *
     * @param owner the module's owner
     * @param feature its module feature, may be {@code null}
     * @param access EDT's adopted-property service, may be {@code null}
     * @return the verdict, never {@code null}
     */
    static Verdict decide(EObject owner, EStructuralFeature feature, IMdAdoptedPropertyAccess access)
    {
        if (!(owner instanceof MdObject) || feature == null
            || ((MdObject)owner).getObjectBelonging() != ObjectBelonging.ADOPTED)
        {
            return Verdict.of(Verdict.Kind.NOT_APPLICABLE);
        }
        if (access == null)
        {
            return new Verdict(Verdict.Kind.FAILED, "EDT's adopted-property service is not available " //$NON-NLS-1$
                + "(the md.extension bundle may not be active)", null); //$NON-NLS-1$
        }
        try
        {
            boolean none = access.hasPropertyState(owner, feature, MdPropertyState.NONE);
            if (((MdObject)owner).getExtension() == null)
            {
                return Verdict.of(Verdict.Kind.NO_EXTENSION_BLOCK);
            }
            if (none)
            {
                return Verdict.of(Verdict.Kind.SWITCH);
            }
            if (access.hasPropertyState(owner, feature, MdPropertyState.EXTENDED))
            {
                return Verdict.of(Verdict.Kind.ALREADY_EXTENDED);
            }
            MdPropertyState kept = keptState(owner, feature, access);
            if (kept == null)
            {
                return new Verdict(Verdict.Kind.FAILED, "EDT's adopted-property service names none of the four " //$NON-NLS-1$
                    + "module states (None, Extended, Checked, Notify) for it", null); //$NON-NLS-1$
            }
            return new Verdict(Verdict.Kind.OTHER_STATE, kept.getName(), null);
        }
        catch (IllegalStateException | IllegalArgumentException e)
        {
            return new Verdict(Verdict.Kind.FAILED, null, e);
        }
    }

    /** The state the step leaves as it is - Checked or Notify - or {@code null} when the service names neither. */
    private static MdPropertyState keptState(EObject owner, EStructuralFeature feature, IMdAdoptedPropertyAccess access)
    {
        for (MdPropertyState state : new MdPropertyState[] { MdPropertyState.CHECKED, MdPropertyState.NOTIFY })
        {
            if (access.hasPropertyState(owner, feature, state))
            {
                return state;
            }
        }
        return null;
    }

    /**
     * After a module file was written, gives the module of an object adopted into the extension the
     * state {@code Extended}, the way EDT does when code is added to an adopted module, and brings the
     * owner's {@code .mdo} in line. See the class comment for the sequence and its boundaries.
     * <p>
     * Never throws: anything the platform throws becomes a FAILED result. Only unexpected platform
     * exceptions are logged at ERROR; a transient model state is logged at INFO, a finding not at all.
     *
     * @param project the project the module was written in
     * @param scope the project's metadata root, resolved before the write
     * @param projectRelativePath the written file's project-relative path
     * @return the step's result, never {@code null}
     */
    public static Result ensureExtended(IProject project, MetadataScope scope, String projectRelativePath)
    {
        return ensureExtended(project, scope, projectRelativePath, PLATFORM);
    }

    /**
     * {@link #ensureExtended(IProject, MetadataScope, String)} against an explicit platform - the seam the
     * tests drive.
     *
     * @param project the project the module was written in
     * @param scope the project's metadata root
     * @param projectRelativePath the written file's project-relative path
     * @param env the platform
     * @return the step's result, never {@code null}
     */
    static Result ensureExtended(IProject project, MetadataScope scope, String projectRelativePath,
        Environment env)
    {
        // Pure, and first: a path that is no module of the strict reading is answered before the
        // platform is asked anything, and every later failure has an owner to name.
        ModuleAddress address = moduleAddress(projectRelativePath);
        if (address == null)
        {
            return Result.notApplicable();
        }
        Probe probe = null;
        try
        {
            if (!isExtension(project, env) || scope == null || scope.configuration() == null)
            {
                return Result.notApplicable();
            }
            Result.Context context = new Result.Context(address, projectRelativePath, projectName(project));
            BmModelResolver.Resolution resolution = env.model(project);
            if (!resolution.isAvailable())
            {
                Activator.logInfo("Module state of '" + address.ownerFqn + "' not checked: no BM model for " //$NON-NLS-1$ //$NON-NLS-2$
                    + context.projectName + " yet"); //$NON-NLS-1$
                return Result.modelUnavailable(context, resolution.actionableError(RETRY_OPERATION, STANDS));
            }
            IBmModel model = resolution.getModel();
            IMdAdoptedPropertyAccess access = env.access();
            probe = BmTransactions.read(model, "AdoptedModuleState.check", //$NON-NLS-1$
                (tx, monitor) -> probe(scope, address, access, env));
            Verdict verdict = probe.verdict;
            if (verdict.kind == Verdict.Kind.SWITCH)
            {
                Probe found = probe;
                verdict = BmTransactions.write(model, "AdoptedModuleState.extend", //$NON-NLS-1$
                    (tx, monitor) -> extend(tx, found, address.stem, access));
            }
            context = context.withProbe(probe.feature, mdoPathOf(probe.topFqn));
            switch (verdict.kind)
            {
            case SWITCH:
                return Result.exported(Outcome.SWITCHED, context, export(project, probe, env));
            case ALREADY_EXTENDED:
                return Result.exported(Outcome.ALREADY_EXTENDED, context, export(project, probe, env));
            case NO_EXTENSION_BLOCK:
                return Result.finding(Outcome.NO_EXTENSION_BLOCK, context, null);
            case OTHER_STATE:
                return Result.finding(Outcome.OTHER_STATE, context, verdict.detail);
            case FAILED:
                return failure(context, verdict);
            case NOT_APPLICABLE:
            default:
                return Result.notApplicable();
            }
        }
        catch (BmBatchSessionException e)
        {
            Activator.logInfo("Module state of '" + address.ownerFqn + "' not set: EDT is running a batch " //$NON-NLS-1$ //$NON-NLS-2$
                + "operation on " + projectName(project) + " (" + PlatformFailures.describe(e) + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return Result.batchSession(contextOf(address, projectRelativePath, project, probe),
                PlatformFailures.describe(e));
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not set the module state of '" + address.ownerFqn //$NON-NLS-1$
                + "' to Extended after writing " + projectRelativePath, e); //$NON-NLS-1$
            return Result.unexpected(contextOf(address, projectRelativePath, project, probe),
                PlatformFailures.describe(e));
        }
    }

    /**
     * Whether the module was written in a configuration extension. EDT's project registry is asked first; its
     * "no" is also what it says for a project it has not registered - as while it disposes and restarts a
     * project's context, which can fall between the write and this step - so that "no" stands only when the
     * project's persistent nature verifiably is not the extension's. Natures that cannot be read are no proof
     * either: the step goes on, and the model and the owner's own belonging decide - without the model, it is
     * the transient failure that says the text is written; with it, only an ADOPTED owner is touched.
     */
    private static boolean isExtension(IProject project, Environment env)
    {
        return env.isExtensionProject(project) || !Boolean.FALSE.equals(env.hasExtensionNature(project));
    }

    /** The context of a step that stopped on an exception: what the read had found by then, if anything. */
    private static Result.Context contextOf(ModuleAddress address, String projectRelativePath, IProject project,
        Probe probe)
    {
        Result.Context context = new Result.Context(address, projectRelativePath, projectName(project));
        return probe == null ? context : context.withProbe(probe.feature, mdoPathOf(probe.topFqn));
    }

    /** What the read found: the owner (as the read resolved it), its module feature and its verdict. */
    private static final class Probe
    {
        final EObject owner;
        final EStructuralFeature feature;
        final Verdict verdict;
        /** The model's FQN of the owner's top object; taken only when the finding names its {@code .mdo}. */
        final String topFqn;
        /** Where the module's state line sits in that {@code .mdo}; taken with {@link #topFqn}. */
        final StateLine line;

        Probe(EObject owner, EStructuralFeature feature, Verdict verdict, String topFqn, StateLine line)
        {
            this.owner = owner;
            this.feature = feature;
            this.verdict = verdict;
            this.topFqn = topFqn;
            this.line = line;
        }
    }

    /** The read: resolve the owner exactly, map the feature, decide - all inside one model read. */
    private static Probe probe(MetadataScope scope, ModuleAddress address, IMdAdoptedPropertyAccess access,
        Environment env)
    {
        // Resolved EXACTLY: the directory IS the programmatic Name, so there is no yo retry.
        EObject owner = address.isConfiguration() ? scope.configuration()
            : MetadataNodeResolver.resolveAddress(scope, address.ownerFqn, false);
        if (owner == null)
        {
            return new Probe(null, null, Verdict.of(Verdict.Kind.NOT_APPLICABLE), null, null);
        }
        EStructuralFeature feature = moduleFeature(owner.eClass(), address.stem);
        Verdict verdict = decide(owner, feature, access);
        if (!verdict.namesTheMdo())
        {
            return new Probe(owner, feature, verdict, null, null);
        }
        return new Probe(owner, feature, verdict, env.topObjectFqn(owner), StateLine.of(owner, feature));
    }

    /**
     * The write: EDT's listener's own sequence on the transaction's copy of the owner - decide again
     * there, and set {@code EXTENDED} only on that verdict, so a listener that won the race in between
     * leaves an ALREADY_EXTENDED here, not a second set.
     */
    private static Verdict extend(IBmTransaction tx, Probe probe, String stem, IMdAdoptedPropertyAccess access)
    {
        EObject owner = tx.toTransactionObject(probe.owner);
        if (owner == null)
        {
            return Verdict.of(Verdict.Kind.NOT_APPLICABLE);
        }
        EStructuralFeature feature = moduleFeature(owner.eClass(), stem);
        Verdict verdict = decide(owner, feature, access);
        if (verdict.kind == Verdict.Kind.SWITCH)
        {
            access.setPropertyState(owner, feature, MdPropertyState.EXTENDED);
        }
        return verdict;
    }

    /**
     * Requests the export of the owner's top object, waits for the export queue, then reads the {@code .mdo}
     * back for the module's state line - ONE budget for all three, counted from BEFORE the request, so a slow
     * request leaves the wait and the reads only what it did not use. The request itself is not bounded here: it is
     * the shared export call ({@link BmTransactions#forceExportToDisk}), and one that EDT keeps waiting holds the
     * call until it returns (see {@link #EXPORT_DEADLINE_MS}). A refused request is never waited for: the queue
     * would be empty, the wait would "drain", and the answer would claim an export that was never queued - but the
     * file still decides it ({@link #unscheduled}). Never throws: the state is committed by now, so a failure here
     * is an unconfirmed export, not a failed step.
     */
    private static Export export(IProject project, Probe probe, Environment env)
    {
        String topFqn = probe.topFqn;
        // Taken BEFORE the request: the request, the queue's wait and every read of the file share the one budget.
        long deadline = env.currentTimeMillis() + EXPORT_DEADLINE_MS;
        try
        {
            if (topFqn == null || topFqn.isEmpty() || !env.forceExport(project, topFqn))
            {
                return unscheduled(project, topFqn, probe.line, deadline, env);
            }
            // The queue's wait gets what the request left. Less than MIN_USEFUL_WAIT_MS left, nothing waited for: so
            // short a wait cannot see the queue drain, and BuildUtils' would still start a platform wait it cannot
            // cancel - so the queue is PENDING, not seen draining within the budget, and the reads get the rest.
            long left = deadline - env.currentTimeMillis();
            boolean awaited = left >= MIN_USEFUL_WAIT_MS;
            BuildUtils.DiskExportState queue =
                awaited ? env.awaitExport(project, left) : BuildUtils.DiskExportState.PENDING;
            Export export = confirmOnDisk(project, mdoPathOf(topFqn), probe.line, queue, deadline, env);
            if (export != Export.WRITTEN)
            {
                String queueNote = awaited ? "queue: " + queue //$NON-NLS-1$
                    : "queue not awaited: the export request left " + Math.max(left, 0L) + "ms of the budget"; //$NON-NLS-1$ //$NON-NLS-2$
                Activator.logInfo("Module state line " + probe.line + " not seen in the .mdo of " + topFqn //$NON-NLS-1$ //$NON-NLS-2$
                    + " within " + EXPORT_DEADLINE_MS + "ms of its export request (" + queueNote + "): " + export); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            return export;
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not export the .mdo of " + topFqn + " after setting a module state", e); //$NON-NLS-1$ //$NON-NLS-2$
            return unscheduled(project, topFqn, probe.line, deadline, env);
        }
    }

    /**
     * An export that could not be scheduled - the platform accepted no request, or exporting threw - is decided
     * by the FILE all the same: nothing was queued that could still change the {@code .mdo}, so it is read ONCE,
     * at once - never polled, never waited for - within what the request left of the budget: a bounded read, and
     * none at all when nothing is left. A file that already carries the module's state line (recorded in EDT's UI,
     * or by an earlier write) is the evidence a confirmed export leaves: WRITTEN. A line not seen - absent,
     * unreadable or not read in time, a read that throws, a read not started while an earlier read of the project's
     * files is still out, no file or line to look for, or no time left to look - leaves it NOT_SCHEDULED. Never
     * throws.
     *
     * @param project the project
     * @param topFqn the model's FQN of the owner's top object, may be {@code null} (no file to read)
     * @param line the module's state line, may be {@code null} (nothing to look for)
     * @param deadline when the budget ends, on {@link Environment#currentTimeMillis()}'s clock
     * @param env the platform
     * @return WRITTEN or NOT_SCHEDULED
     */
    private static Export unscheduled(IProject project, String topFqn, StateLine line, long deadline,
        Environment env)
    {
        LineOnDisk seen = LineOnDisk.UNREADABLE;
        try
        {
            String mdoPath = mdoPathOf(topFqn);
            long left = deadline - env.currentTimeMillis();
            if (mdoPath != null && line != null && left > 0)
            {
                seen = env.readStateLine(project, mdoPath, line, left);
            }
        }
        catch (RuntimeException e) // NOSONAR a read that throws sees no line: an answer, not a crash
        {
            seen = LineOnDisk.UNREADABLE;
        }
        return seen == LineOnDisk.PRESENT ? Export.WRITTEN : Export.NOT_SCHEDULED;
    }

    /**
     * Confirms an export by the FILE. The queue's answer cannot: EDT's export task processor catches a failed
     * file write (a locked or read-only {@code .mdo}, a full disk), so DRAINED means only that nothing is queued
     * any more, and UNOBSERVABLE means nothing at all. So the {@code .mdo} is read back - after the wait, never
     * before - until it carries the module's own state line or the budget is spent; the first read that finds
     * the line ends it, whatever the queue said. An interrupted pause ends it too: the call is being cancelled.
     * No read starts once the budget is spent - not even the first, so a queue's wait that used it all leaves the
     * file unread - and each read is given only what is left: one not answered by then confirms nothing. A read not
     * started because an earlier read of the project's files is still out confirms nothing either, and the poll goes
     * on: once that read's job ends - also without having run the read - or its claim lapses, a later one can still
     * find the line.
     *
     * @param project the project
     * @param mdoPath the owner's top-object {@code .mdo}, may be {@code null} (nothing to read: not confirmed)
     * @param line the state line, may be {@code null} (nothing to look for: not confirmed)
     * @param queue how the export queue's wait ended
     * @param deadline when the budget ends, on {@link Environment#currentTimeMillis()}'s clock
     * @param env the platform
     * @return WRITTEN, or why not
     */
    private static Export confirmOnDisk(IProject project, String mdoPath, StateLine line,
        BuildUtils.DiskExportState queue, long deadline, Environment env)
    {
        LineOnDisk seen = LineOnDisk.UNREADABLE;
        if (mdoPath != null && line != null)
        {
            long pause = MDO_POLL_FIRST_MS;
            while (true)
            {
                long left = deadline - env.currentTimeMillis();
                if (left <= 0)
                {
                    // The budget is spent: no read starts with nothing left - the first one included.
                    break;
                }
                seen = env.readStateLine(project, mdoPath, line, left);
                if (seen == LineOnDisk.PRESENT)
                {
                    return Export.WRITTEN;
                }
                left = deadline - env.currentTimeMillis();
                if (left <= 0 || !env.pause(Math.min(pause, left)))
                {
                    break;
                }
                pause = Math.min(pause * 2, MDO_POLL_MAX_MS);
            }
        }
        if (queue == BuildUtils.DiskExportState.PENDING)
        {
            return Export.PENDING;
        }
        return seen == LineOnDisk.ABSENT ? Export.MISSING : Export.UNREADABLE;
    }

    /** A FAILED verdict: the platform's refusal (logged at ERROR - it is drift), or a missing service. */
    private static Result failure(Result.Context context, Verdict verdict)
    {
        if (verdict.failure != null)
        {
            Activator.logError("EDT refused the module state question for '" + context.address.ownerFqn + "'", //$NON-NLS-1$ //$NON-NLS-2$
                verdict.failure);
            return Result.unexpected(context, PlatformFailures.describe(verdict.failure));
        }
        Activator.logWarning("Module state of '" + context.address.ownerFqn + "' not checked: " + verdict.detail); //$NON-NLS-1$ //$NON-NLS-2$
        return Result.serviceUnavailable(context, verdict.detail);
    }

    private static String projectName(IProject project)
    {
        return project == null ? "?" : project.getName(); //$NON-NLS-1$
    }

    /** The step's result: its outcome, and what the write's response says about it. */
    public static final class Result
    {
        private static final Result NOT_APPLICABLE = new Result(Outcome.NOT_APPLICABLE, null, null, null, Cause.NONE);

        /** Why a FAILED failed; decides the advice. */
        private enum Cause
        {
            NONE, MODEL_UNAVAILABLE, BATCH_SESSION, SERVICE, UNEXPECTED
        }

        /** Where the step ran: the module, its owner and the project, and what the read found. */
        static final class Context
        {
            final ModuleAddress address;
            final String modulePath;
            final String projectName;
            /** The owner's module feature; its name is the {@code .mdo} element. May be {@code null}. */
            final EStructuralFeature feature;
            /** The {@code .mdo} of the owner's top object, as the model names it. May be {@code null}. */
            final String mdoPath;

            Context(ModuleAddress address, String modulePath, String projectName)
            {
                this(address, modulePath, projectName, null, null);
            }

            private Context(ModuleAddress address, String modulePath, String projectName, EStructuralFeature feature,
                String mdoPath)
            {
                this.address = address;
                this.modulePath = modulePath;
                this.projectName = projectName;
                this.feature = feature;
                this.mdoPath = mdoPath;
            }

            Context withProbe(EStructuralFeature moduleFeature, String topObjectMdoPath)
            {
                return new Context(address, modulePath, projectName, moduleFeature, topObjectMdoPath);
            }
        }

        private final Outcome outcome;
        private final Context context;
        private final Export export;
        private final String detail;
        private final Cause cause;
        /** Whether the step found the state already Extended rather than switched it; worded, never decided on. */
        private final boolean foundExtended;

        private Result(Outcome outcome, Context context, Export export, String detail, Cause cause)
        {
            this(outcome, context, export, detail, cause, false);
        }

        private Result(Outcome outcome, Context context, Export export, String detail, Cause cause,
            boolean foundExtended)
        {
            this.outcome = outcome;
            this.context = context;
            this.export = export;
            this.detail = detail;
            this.cause = cause;
            this.foundExtended = foundExtended;
        }

        /** @return the result of a write that is not into an adopted object's module */
        public static Result notApplicable()
        {
            return NOT_APPLICABLE;
        }

        /**
         * The result of a step that requested the export of the owner's {@code .mdo}. Only the file confirms an
         * export - read back after the queue's wait (see confirmOnDisk), or read once when the export could not be
         * scheduled (see unscheduled) - and ONE piece of evidence gets ONE verdict: a {@code .mdo} that, read back,
         * does not carry the line, or cannot be read back, fails the write whether this call switched the state or
         * found it already Extended. EDT's own listener flips the model in reaction to the same {@code .bsl} write,
         * so which of the two the step found is a race; a verdict that turned on it would make a failed export look
         * done, by chance, to a caller who checks only the error flag. The ordinary-application module, which EDT
         * does not list among the properties an extension can extend, is the exception: a note on a success.
         *
         * @param state SWITCHED or ALREADY_EXTENDED: what the step found
         * @param context where the step ran
         * @param export how the export ended
         * @return the result
         */
        static Result exported(Outcome state, Context context, Export export)
        {
            boolean success = export == Export.WRITTEN || context.address.isOrdinaryApplicationModule();
            return new Result(success ? state : Outcome.EXPORT_NOT_CONFIRMED, context, export, null, Cause.NONE,
                state == Outcome.ALREADY_EXTENDED);
        }

        static Result finding(Outcome outcome, Context context, String state)
        {
            return new Result(outcome, context, null, state, Cause.NONE);
        }

        /**
         * @param context where the step ran
         * @param actionable BmModelResolver's actionable error for the unavailable model
         * @return the failure of a step that could not read the model
         */
        static Result modelUnavailable(Context context, String actionable)
        {
            return new Result(Outcome.FAILED, context, null, actionable, Cause.MODEL_UNAVAILABLE);
        }

        static Result batchSession(Context context, String why)
        {
            return new Result(Outcome.FAILED, context, null, why, Cause.BATCH_SESSION);
        }

        static Result serviceUnavailable(Context context, String why)
        {
            return new Result(Outcome.FAILED, context, null, why, Cause.SERVICE);
        }

        static Result unexpected(Context context, String why)
        {
            return new Result(Outcome.FAILED, context, null, why, Cause.UNEXPECTED);
        }

        /** @return the outcome */
        public Outcome outcome()
        {
            return outcome;
        }

        /**
         * @return whether the step failed, so the write must answer with an error that says the text
         *     is written: a failure, or a module Extended in EDT's model - switched or found so - whose
         *     {@code .mdo} export was not confirmed - never for the ordinary-application module, which EDT
         *     does not list among the properties an extension can extend. A Checked/Notify state or a
         *     missing extension block is no failure: the write succeeds, and its key and paragraph say so.
         */
        public boolean failed()
        {
            switch (outcome)
            {
            case EXPORT_NOT_CONFIRMED:
                return true;
            case FAILED:
                return !ordinaryApplicationModule();
            default:
                return false;
            }
        }

        /**
         * @return the value of the {@link AdoptedModuleState#FRONT_MATTER_KEY} key - {@code switchedToExtended},
         *     {@code alreadyExtended}, {@code keptChecked}, {@code keptNotify} or {@code noExtensionBlock} -
         *     or {@code null} when the write reports no key
         */
        public String frontMatterValue()
        {
            switch (outcome)
            {
            case SWITCHED:
                return "switchedToExtended"; //$NON-NLS-1$
            case ALREADY_EXTENDED:
                return "alreadyExtended"; //$NON-NLS-1$
            case OTHER_STATE:
                return "kept" + detail; //$NON-NLS-1$
            case NO_EXTENSION_BLOCK:
                return "noExtensionBlock"; //$NON-NLS-1$
            default:
                return null;
            }
        }

        /**
         * The paragraph a successful write adds under its first line: a switch, a state left as it is, a
         * missing extension block - or, on the ordinary-application module only, an export that is not
         * confirmed or a step that could not run. A paragraph on every write into an already-Extended
         * module would be noise: the key says that much.
         *
         * @return the paragraph, or {@code null} for none
         */
        public String bodySentence()
        {
            switch (outcome)
            {
            case SWITCHED:
                return switchedParagraph();
            case OTHER_STATE:
                return keptParagraph();
            case NO_EXTENSION_BLOCK:
                return noBlockParagraph();
            case ALREADY_EXTENDED:
                return confirmed() ? null : alreadyExtendedParagraph();
            case FAILED:
                return ordinaryApplicationModule() ? "The state of the ordinary-application module of " //$NON-NLS-1$
                    + ownerName() + " was left as it is: " + failureReason() + ". " + ORDINARY_NOTE : null; //$NON-NLS-1$ //$NON-NLS-2$
            default:
                return null;
            }
        }

        private String switchedParagraph()
        {
            String switched = "The module belongs to " + ownerWithRole() + ": its module state was switched from " //$NON-NLS-1$ //$NON-NLS-2$
                + "None to Extended (" + stateLine("Extended") + ")" + exportClause() + " " + PARITY; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return ordinaryApplicationModule() && !confirmed() ? switched + " " + ORDINARY_NOTE : switched; //$NON-NLS-1$
        }

        /**
         * Checked or Notify: named, and left exactly as the author set it. The note compares the step with
         * EDT's own listener, which asks for None first as the step does - not with EDT as a whole: EDT's
         * module adopter, which its UI runs to add a method to an extension, sets Extended without asking.
         */
        private String keptParagraph()
        {
            return "The module state of " + ownerName() + " is " + detail + " (" + stateLine(detail) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "), not Extended, and was left as it is: like EDT's own listener, this tool only switches a " //$NON-NLS-1$
                + "module's state from None to Extended. If the module should be Extended, set its state in EDT, " //$NON-NLS-1$
                + "in the properties of the adopted object."; //$NON-NLS-1$
        }

        /**
         * No extension block: there is no state to record, and nothing was created to hold one. For the extension's
         * root it says which roots carry the block - EDT's own new-extension wizard's, and create_project's, which adopts
         * the base's root the way that wizard does - and that a root an earlier create_project made from a new
         * configuration has none (issue #706); then the one way out it can stand behind: recreating the extension.
         * <p>
         * No property state set in EDT is named, because on the roots this note is about every such route is a trap
         * (2026.1.2 and 2026.2.1 bytecode, and EDT's own property-state models and validator run on such a root, #747
         * review round 3). A Checked or Notify state of an attribute - the default run mode, for one - is set with the
         * base's value copied in first, through {@code setValue}, which writes the state into the block there is: with
         * none it throws, and no state is set. 'Extended configuration object' is declared for every adopted object, so
         * a state set on it creates a bare MdObjectExtension block, which holds no module states. A state of the
         * default language does create the root's ConfigurationExtension block ({@link
         * IMdAdoptedPropertyAccess#setPropertyState} creates a missing block as an instance of the class that DECLARES
         * the state), but such a root has no default language, and a Checked one that does not match the base's is a
         * CRITICAL_DATA_INTEGRITY issue of EDT's extension validator, which stops EDT from updating an infobase until
         * the state is set back.
         */
        private String noBlockParagraph()
        {
            String missing = "This module belongs to " + ownerWithRole() + ": there is no extension block in " //$NON-NLS-1$ //$NON-NLS-2$
                + mdoName() + ", so the module state cannot be recorded."; //$NON-NLS-1$
            if (context.address.isConfiguration())
            {
                return missing + " An extension root that EDT's own new-extension wizard creates carries that " //$NON-NLS-1$
                    + "block, and so does one that create_project creates, as it adopts the base's root the way that " //$NON-NLS-1$
                    + "wizard does; a root made by an earlier version of create_project - a new configuration, not the " //$NON-NLS-1$
                    + "base's root adopted - has none. To record the state, recreate the extension with create_project " //$NON-NLS-1$
                    + "and add its objects and code again, then " + RERUN + "."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return missing + " EDT writes that block whenever it adopts an object, so the .mdo was changed outside " //$NON-NLS-1$
                + "EDT: to record the state, restore the block (for example from version control), then " + RERUN //$NON-NLS-1$
                + "."; //$NON-NLS-1$
        }

        /**
         * A module that already was Extended, whose {@code .mdo} was not seen carrying the line - a note on a
         * success only on the ordinary-application module: on any other module that fails the write (see
         * {@link #exported}).
         */
        private String alreadyExtendedParagraph()
        {
            String already = "The module state of " + ownerName() + " is already Extended (" //$NON-NLS-1$ //$NON-NLS-2$
                + stateLine("Extended") + "), but "; //$NON-NLS-1$ //$NON-NLS-2$
            String why;
            switch (export)
            {
            case MISSING:
                why = "the export of the .mdo was requested again to match the model and the .mdo, read back, did " //$NON-NLS-1$
                    + "not carry that line within " + seconds() + "s"; //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case UNREADABLE:
                why = "the export of the .mdo was requested again to match the model and the .mdo could not be " //$NON-NLS-1$
                    + "read back to confirm that line within " + seconds() + "s"; //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case PENDING:
                why = "the export of the .mdo, requested again to match the model, was not confirmed within " //$NON-NLS-1$
                    + seconds() + "s, so the .mdo on disk may still lack that line"; //$NON-NLS-1$
                break;
            case NOT_SCHEDULED:
            default:
                why = "the export of the .mdo could not be scheduled, so the .mdo on disk may still lack that line"; //$NON-NLS-1$
                break;
            }
            String note = already + why + ". Nothing was rolled back; check the line before committing, and if it " //$NON-NLS-1$
                + "is still missing once list_projects reports the project ready, " + RERUN + "."; //$NON-NLS-1$ //$NON-NLS-2$
            return ordinaryApplicationModule() ? note + " " + ORDINARY_NOTE : note; //$NON-NLS-1$
        }

        /**
         * The error a failed step turns the write into. It starts with what IS done - the text is
         * written - and says not to re-send the edit, because a caller retrying on an error would apply
         * it twice; where a retry helps, it names the one that re-runs only this step.
         *
         * @return the message, or {@code null} when the step did not fail
         */
        public String failureMessage()
        {
            if (!failed())
            {
                return null;
            }
            String written = "The module text was written to " + context.modulePath; //$NON-NLS-1$
            switch (outcome)
            {
            case EXPORT_NOT_CONFIRMED:
                return written + ", and the module state of " + ownerName() //$NON-NLS-1$
                    + (foundExtended ? " is already Extended" : " was switched from None to Extended") //$NON-NLS-1$ //$NON-NLS-2$
                    + " in EDT's model, but " + unconfirmedExport() + ". Nothing was rolled back. " + STANDS //$NON-NLS-1$ //$NON-NLS-2$
                    + " And do not commit the working tree in this state: once list_projects reports the " //$NON-NLS-1$
                    + "project ready, check " + mdoName() + " for that line, and if it is still missing, " //$NON-NLS-1$ //$NON-NLS-2$
                    + RERUN + " - that exports the .mdo again."; //$NON-NLS-1$
            case FAILED:
            default:
                return written + failedMessage();
            }
        }

        /**
         * Why an export is not confirmed: what was asked of EDT - for a module found already Extended, an export
         * to bring the {@code .mdo} in line with the model - and what the {@code .mdo} showed. A line the file did
         * not carry names EDT's log - its export task processor logs a file it fails to write and drains all the
         * same (2026.1 and 2026.2 bytecode).
         */
        private String unconfirmedExport()
        {
            String line = element("Extended"); //$NON-NLS-1$
            String requested = foundExtended ? "requested again to match the model" : "requested"; //$NON-NLS-1$ //$NON-NLS-2$
            switch (export)
            {
            case MISSING:
                return "the export of its .mdo was " + requested + " and " + mdoName() + ", read back, did not carry " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + line + " within " + seconds() + "s; if EDT failed to write the file (a locked or read-only .mdo, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "a full disk), its log says so"; //$NON-NLS-1$
            case UNREADABLE:
                return "the export of its .mdo was " + requested + " and " + mdoName() //$NON-NLS-1$ //$NON-NLS-2$
                    + " could not be read back to confirm " + line + " within " + seconds() + "s"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case PENDING:
                return "the export of its .mdo" + (foundExtended ? ", " + requested + "," : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    + " was not confirmed within " + seconds() + "s, so " + mdoName() + " may still lack " + line; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case NOT_SCHEDULED:
            default:
                return "the export of its .mdo could not be scheduled, so " + mdoName() + " may still lack " + line; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private String failedMessage()
        {
            switch (cause)
            {
            case MODEL_UNAVAILABLE:
                // BmModelResolver's own actionable error, with this step's rerun as the operation to retry.
                return ", but whether its owner '" + context.address.ownerFqn + "' is adopted from the base " //$NON-NLS-1$ //$NON-NLS-2$
                    + "configuration - and so whether its module state is to be recorded as Extended - could not " //$NON-NLS-1$
                    + "be checked. " + detail; //$NON-NLS-1$
            case BATCH_SESSION:
                return ", but the module state of " + ownerName() + " could not be set to Extended: EDT is " //$NON-NLS-1$ //$NON-NLS-2$
                    + "running a batch operation on the model of project '" + context.projectName + "' (" //$NON-NLS-1$ //$NON-NLS-2$
                    + detail + "). This is transient. " + STANDS + " Wait a few seconds, then " + RERUN + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case SERVICE:
                return ", but the module state of " + ownerWithRole() + ", could not be checked: " + detail //$NON-NLS-1$ //$NON-NLS-2$
                    + ". " + STANDS + " Restart EDT, then " + RERUN + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case UNEXPECTED:
            default:
                return ", but setting the module state of " + ownerName() + " to Extended failed: " + detail //$NON-NLS-1$ //$NON-NLS-2$
                    + ". " + STANDS + " Check the EDT log; once the project is ready, " + RERUN //$NON-NLS-1$ //$NON-NLS-2$
                    + " - it sets the state if it is still None."; //$NON-NLS-1$
            }
        }

        /** Why a FAILED step could not finish, in a clause - for the note on the ordinary-application module. */
        private String failureReason()
        {
            switch (cause)
            {
            case MODEL_UNAVAILABLE:
                return "EDT had not registered the BM model of project '" + context.projectName + "' yet"; //$NON-NLS-1$ //$NON-NLS-2$
            case BATCH_SESSION:
                return "EDT was running a batch operation on the model of project '" + context.projectName //$NON-NLS-1$
                    + "' (" + detail + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            case SERVICE:
                return detail;
            case UNEXPECTED:
            default:
                return "setting it to Extended failed: " + detail; //$NON-NLS-1$
            }
        }

        /**
         * What happened to the export of the {@code .mdo} after a switch, ending the sentence: "exported" only
         * when the file, read back, carries the line.
         */
        private String exportClause()
        {
            switch (export)
            {
            case WRITTEN:
                return ", and the .mdo was exported."; //$NON-NLS-1$
            case MISSING:
                return ", but the export of the .mdo was requested and the .mdo, read back, did not carry that line " //$NON-NLS-1$
                    + "within " + seconds() + "s."; //$NON-NLS-1$ //$NON-NLS-2$
            case UNREADABLE:
                return ", but the export of the .mdo was requested and the .mdo could not be read back to confirm " //$NON-NLS-1$
                    + "that line within " + seconds() + "s."; //$NON-NLS-1$ //$NON-NLS-2$
            case PENDING:
                return ", but the export of the .mdo was not confirmed within " + seconds() + "s."; //$NON-NLS-1$ //$NON-NLS-2$
            case NOT_SCHEDULED:
            default:
                return ", but the export of the .mdo could not be scheduled."; //$NON-NLS-1$
            }
        }

        /** @return whether the export is confirmed: the {@code .mdo}, read back, carries the module's state line */
        private boolean confirmed()
        {
            return export == Export.WRITTEN;
        }

        /**
         * @return whether the module is the root's ordinary-application module, which EDT does not list
         *     among the properties an extension can extend
         */
        private boolean ordinaryApplicationModule()
        {
            return context != null && context.address.isOrdinaryApplicationModule();
        }

        /** The owner by name: {@code 'Catalog.X'}, or the extension's root. */
        private String ownerName()
        {
            return context.address.isConfiguration() ? "the extension's root object (Configuration)" //$NON-NLS-1$
                : "'" + context.address.ownerFqn + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** The owner by name, with what makes its module state matter. */
        private String ownerWithRole()
        {
            return ownerName() + (context.address.isConfiguration() ? ", adopted from the base configuration" //$NON-NLS-1$
                : ", an object adopted into this extension"); //$NON-NLS-1$
        }

        /** The {@code .mdo} the state lives in, by path when the model named its top object. */
        private String mdoName()
        {
            return context.mdoPath != null ? context.mdoPath : "the .mdo of its top object"; //$NON-NLS-1$
        }

        /** The state line as the {@code .mdo} holds it, with the file it is in. */
        private String stateLine(String state)
        {
            return element(state) + " in " + mdoName(); //$NON-NLS-1$
        }

        /** The {@code .mdo} element of the module's state: the extension feature is named as the module's. */
        private String element(String state)
        {
            String name = context.feature == null ? context.address.stem : context.feature.getName();
            return "<" + name + ">" + state + "</" + name + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        private static long seconds()
        {
            return EXPORT_DEADLINE_MS / 1000;
        }
    }
}
