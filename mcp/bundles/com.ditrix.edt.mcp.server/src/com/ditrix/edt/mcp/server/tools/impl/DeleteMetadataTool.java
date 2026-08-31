/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.refactoring.core.CleanReferenceProblem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com._1c.g5.v8.dt.xdto.model.ObjectType;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.BoundedJob;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataPathResolver;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.PersistedContents;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.SecureXml;
import com.ditrix.edt.mcp.server.utils.XdtoWriteException;
import com.ditrix.edt.mcp.server.utils.XdtoWriter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Deletes a metadata node (a top-level object or a subordinate member) addressed by a 1C full-name
 * FQN. A TOP-LEVEL object, and an mdclass MEMBER of one, goes through EDT's md-refactoring
 * service, which cascades the cleanup of every reference it CAN clean (BSL code, forms, other
 * metadata); one it cannot blocks the delete instead. A member living inside another object's own
 * content (an owned form object, a form member, an XDTO package member) is removed from that
 * container directly, with no CROSS-object cascade - only the owner's own pointers (a default-form
 * setting naming the deleted form) are cleaned.
 * Two-phase: a bare call previews the affected references; {@code confirm=true}
 * performs the delete. Replaces the former {@code delete_metadata_object}.
 */
public class DeleteMetadataTool extends AbstractMetadataWriteTool
{
    /**
     * A non-forced delete answers "is anything still referencing this?" from the Xtext index, and a
     * predefined-item delete does so even when the strict cascade settle is skipped. That index is
     * built in the NORMAL bucket, which EDT's "important" segment set excludes - so the model gate
     * could admit a delete whose reference check then runs against an index that is still being
     * built and reports a clean bill of health. This tool keeps the strict gate.
     */
    @Override
    protected boolean requiresFullDerivedData()
    {
        return true;
    }

    /** Bounded cascade settle seam; production delegates to {@link ProjectStateChecker}. */
    @FunctionalInterface
    interface CascadeSettler
    {
        String settle(String projectName, long timeoutMs);
    }

    /**
     * Queues one top object's {@code .mdo} export. A package-private SEAM: production delegates to
     * {@link BmTransactions#forceExportToDisk(IProject, String)}, while a unit test substitutes a
     * recorder to observe that the generic delete SUBMITS its export, and submits it AFTER the
     * refactoring performed - the ordering the export barrier depends on and cannot create by
     * itself.
     */
    @FunctionalInterface
    interface ExportSubmitter
    {
        /**
         * @param project the project owning the object
         * @param topObjectFqn the FQN of the top object whose {@code .mdo} to queue
         * @return whether the platform accepted a save task
         */
        boolean submit(IProject project, String topObjectFqn);
    }

    /**
     * Asks the destructive-consent gate. A package-private SEAM: the production default delegates to
     * {@link DestructiveConsentGate#getInstance()}, which stays a private static final singleton, while
     * a unit test substitutes a requester answering REJECT / TIMEOUT to prove the write never runs
     * (issue #331 / #295 review).
     */
    @FunctionalInterface
    interface ConsentRequester
    {
        /**
         * @param toolName the gated tool's name
         * @param preview what the user is being asked to authorize
         * @return the verdict
         */
        DestructiveConsentGate.ConsentDecision request(String toolName, ConsentPreview preview);
    }

    /**
     * Reads which projects take part in a cascade rooted at the target. A package-private SEAM: the
     * production default is {@link ProjectStateChecker#cascadeParticipants(IProject)}, which needs a
     * live workspace, while a unit test substitutes a fixed set so what a confirmed delete DECLARES
     * can be observed headlessly.
     */
    @FunctionalInterface
    interface CascadeParticipants
    {
        /**
         * @param base the project the cascade mutates
         * @return the other projects taking part; never {@code null}
         */
        List<IProject> of(IProject base);
    }

    /** Result of checking the registering {@code .mdo} after the export barrier. */
    enum RegistrationState
    {
        /** The registering file no longer contains the deleted node. */
        ABSENT,
        /** The registering file still contains the deleted node. */
        PRESENT,
        /** The registering file could not be read or its registration shape could not be resolved. */
        UNVERIFIABLE
    }

    /** Reads the registering {@code .mdo}; a package-private seam keeps the post-barrier result testable. */
    @FunctionalInterface
    interface RegistrationVerifier
    {
        RegistrationState verify(String projectName, String registeringFile,
            String registeringContainer, String targetFqn);
    }

    private final ConsentRequester consentRequester;
    private final CascadeSettler cascadeSettler;
    private final ExportSubmitter exportSubmitter;
    private final CascadeParticipants cascadeParticipants;
    private final RegistrationVerifier registrationVerifier;

    /** Production instance: consent goes to the real gate. */
    public DeleteMetadataTool()
    {
        this((tool, preview) -> DestructiveConsentGate.getInstance().requireConsent(tool, preview),
            (projectName, timeoutMs) -> ProjectStateChecker.settleBeforeCascadeOrError(projectName,
                timeoutMs, NAME, "Nothing was deleted."), //$NON-NLS-1$
            BmTransactions::forceExportToDisk, ProjectStateChecker::cascadeParticipants,
            DeleteMetadataTool::verifyRegistrationOnDisk);
    }

    /**
     * Test seam constructor.
     *
     * @param consentRequester the consent source to use instead of the singleton gate
     */
    DeleteMetadataTool(ConsentRequester consentRequester)
    {
        this(consentRequester,
            (projectName, timeoutMs) -> ProjectStateChecker.settleBeforeCascadeOrError(projectName,
                timeoutMs, NAME, "Nothing was deleted."), //$NON-NLS-1$
            BmTransactions::forceExportToDisk, ProjectStateChecker::cascadeParticipants,
            DeleteMetadataTool::verifyRegistrationOnDisk);
    }

    /** Test seam for the caller-thread cascade settle. */
    DeleteMetadataTool(ConsentRequester consentRequester, CascadeSettler cascadeSettler)
    {
        this(consentRequester, cascadeSettler, BmTransactions::forceExportToDisk,
            ProjectStateChecker::cascadeParticipants, DeleteMetadataTool::verifyRegistrationOnDisk);
    }

    /** Test seam for the cascade settle AND the post-refactoring export submission. */
    DeleteMetadataTool(ConsentRequester consentRequester, CascadeSettler cascadeSettler,
        ExportSubmitter exportSubmitter)
    {
        this(consentRequester, cascadeSettler, exportSubmitter, ProjectStateChecker::cascadeParticipants,
            DeleteMetadataTool::verifyRegistrationOnDisk);
    }

    /** Test seam for everything above PLUS the cascade participant set the write scope declares. */
    DeleteMetadataTool(ConsentRequester consentRequester, CascadeSettler cascadeSettler,
        ExportSubmitter exportSubmitter, CascadeParticipants cascadeParticipants)
    {
        this(consentRequester, cascadeSettler, exportSubmitter, cascadeParticipants,
            DeleteMetadataTool::verifyRegistrationOnDisk);
    }

    /** Test seam for the post-export on-disk registration check. */
    DeleteMetadataTool(ConsentRequester consentRequester, CascadeSettler cascadeSettler,
        ExportSubmitter exportSubmitter, CascadeParticipants cascadeParticipants,
        RegistrationVerifier registrationVerifier)
    {
        this.consentRequester = consentRequester;
        this.cascadeSettler = cascadeSettler;
        this.exportSubmitter = exportSubmitter;
        this.cascadeParticipants = cascadeParticipants;
        this.registrationVerifier = registrationVerifier;
    }

    /**
     * The mutation one delete branch performs once the gate has answered ALLOW. A dedicated type
     * rather than a bare {@code Supplier<String>} because it is load-bearing for the enforcement:
     * it is the ONE thing {@link #deleteWithConsent} invokes, and {@code
     * DeleteMetadataConsentSinglePointRatchetTest} reads the compiled classes - this one and every
     * class compiled inside it - to prove that nothing else reaches it, by call OR by method handle,
     * and that a callback nobody hands to the gate is not exempt from its walk. That turns "nothing is
     * written without ALLOW" into something checkable instead of something a reviewer has to re-read
     * every time a branch is added.
     */
    @FunctionalInterface
    interface DeleteWrite
    {
        /**
         * Performs the branch's mutation.
         *
         * @return the branch's JSON result
         */
        String perform();
    }

    /**
     * The tool's SINGLE authorization point: EVERY branch that can mutate - the generic mdclass
     * object / member, an owned form object, a form member, a predefined item and an XDTO package
     * member - asks here, and its write runs ONLY on ALLOW. "Did we ask before mutating?" is one
     * question for the whole tool instead of one per branch, which is what issue #331 asked for after
     * two branches were found writing with no gate at all; the branches that had their own
     * {@link DestructiveConsentGate#getInstance()} call were routed through here for the same reason
     * - a per-branch copy is a per-branch chance to forget.
     *
     * <p>Every branch resolves and validates its target BEFORE calling this, so a typo answers "not
     * found" without ever raising a destructive prompt, and the prompt names what is really removed.
     * The gate is the LAST check before the write and runs outside any transaction, because it may
     * block on a UI dialog.</p>
     *
     * @param preview what the user is being asked to authorize
     * @param write the mutation, invoked only when consent is granted
     * @return the mutation's result, or the refusal error
     */
    String deleteWithConsent(ConsentPreview preview, DeleteWrite write)
    {
        DestructiveConsentGate.ConsentDecision decision = consentRequester.request(NAME, preview);
        if (decision != DestructiveConsentGate.ConsentDecision.ALLOW)
        {
            return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(decision, NAME)).toJson();
        }
        return write.perform();
    }

    public static final String NAME = "delete_metadata"; //$NON-NLS-1$

    /** Input key: caller-side bound on the UI-thread delete work, in seconds. */
    static final String KEY_TIMEOUT = "timeout"; //$NON-NLS-1$

    /**
     * Default bound on the delete work (7 minutes).
     * <p>
     * Delete and rename use the same md-refactoring/UI-thread machinery, so delete deliberately
     * uses rename's 420s default and 60..3600 range. The shared worst legitimate observation is a
     * 301-second refactoring that completed after EDT waited out its own five-minute derived-data
     * timeout. A lower delete default has the more dangerous failure mode: the call reports a
     * timeout while the non-preemptible cascade goes on to remove the target and rewrite references,
     * manufacturing exactly the uncertain, possibly half-deleted configuration this bound exists to
     * report. 420s clears that observation by almost two minutes while still bounding a genuinely
     * wedged request; 60s is the lowest value that does not invite cutting an ordinary healthy
     * cascade off mid-flight, and 3600s still gives unusually large configurations an explicit
     * escape hatch without restoring an indefinite wait.
     */
    static final int DEFAULT_DELETE_TIMEOUT_SECONDS = 420;

    /** Smallest accepted UI-thread delete bound, in seconds. */
    private static final int MIN_DELETE_TIMEOUT_SECONDS = 60;

    /** Largest accepted UI-thread delete bound, in seconds. */
    private static final int MAX_DELETE_TIMEOUT_SECONDS = 3600;

    /** Shared bound for derived-data drain and BM-model registration before an mdclass cascade. */
    private static final long SETTLE_TIMEOUT_MS = 60_000L;

    /** Output key: title of the delete refactoring (preview). */
    private static final String KEY_REFACTORING_TITLE = "refactoringTitle"; //$NON-NLS-1$

    /** Output key: metadata items the deletion would remove (preview). */
    private static final String KEY_ITEMS = "items"; //$NON-NLS-1$

    /** Output key: whether the listed blocking references block the delete. */
    private static final String KEY_BLOCKING = "blocking"; //$NON-NLS-1$

    /** Output value of 'action' for a preview-only response. */
    private static final String VAL_PREVIEW = "preview"; //$NON-NLS-1$

    /** Output value of 'action' for an executed (performed) deletion. */
    private static final String VAL_EXECUTED = "executed"; //$NON-NLS-1$

    /** FQN kind token / label for a form event handler. */
    private static final String KEY_HANDLER = "handler"; //$NON-NLS-1$

    /** Label for a form member (non-handler). */
    private static final String KEY_MEMBER = "member"; //$NON-NLS-1$

    /** Output key: refactoring problems that prohibit the delete but are not incoming references. */
    private static final String KEY_PLATFORM_PROHIBITIONS = "platformProhibitions"; //$NON-NLS-1$

    /** Output key: count of platform prohibition problems. */
    private static final String KEY_PLATFORM_PROHIBITIONS_COUNT = "platformProhibitionsCount"; //$NON-NLS-1$

    /** Optional partial-result key: whether the registering file reflects the forced delete. */
    private static final String KEY_PERSISTED = "persisted"; //$NON-NLS-1$

    /** Optional partial-result key: project-relative path of the registering {@code .mdo}. */
    private static final String KEY_REGISTERING_FILE = "registeringFile"; //$NON-NLS-1$

    /** Internal-to-post-barrier carrier, retained in output only for a partial result. */
    private static final String KEY_REGISTERING_CONTAINER = "registeringContainer"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Delete a metadata object or member (FQN-addressed). DESTRUCTIVE and CASCADING: on the " //$NON-NLS-1$
            + "md-refactoring path EDT cleans the REFERENCES to the deleted object across BSL, forms " //$NON-NLS-1$
            + "and metadata - the referring objects themselves are NOT deleted. Two-phase: call once " //$NON-NLS-1$
            + "WITHOUT confirm to preview what will be removed, then again with confirm=true to apply. A " //$NON-NLS-1$
            + "reference EDT cannot auto-clean leaves the delete BLOCKED and lists the referring " //$NON-NLS-1$
            + "objects; an EDT platform prohibition is listed separately and also blocks. force=true " //$NON-NLS-1$
            + "overrides either block and leaves only genuine incoming references dangling. " //$NON-NLS-1$
            + "EXCEPTION - an owned FORM object, a FORM " //$NON-NLS-1$
            + "member or an XDTO package member is removed straight from its container: NOTHING blocks " //$NON-NLS-1$
            + "it (force is ignored) and no cross-object cascade runs, so references from elsewhere (a " //$NON-NLS-1$
            + "field's dataPath, a command, an XDTO type) are left broken - re-check with " //$NON-NLS-1$
            + "get_metadata_details (find_references takes TOP-level FQNs only, not these members). " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('delete_metadata')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty("fqn", //$NON-NLS-1$
                "Full-name FQN of the node to delete (required), e.g. 'Catalog.Products' or " //$NON-NLS-1$
                + "'Document.SalesOrder.Attribute.Amount' (type / kind tokens may be English or " //$NON-NLS-1$
                + "Russian; the Name parts are the programmatic Name, not the synonym).", true) //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = execute the deletion; default false = preview only.") //$NON-NLS-1$
            .booleanProperty("force", //$NON-NLS-1$
                "true = delete despite incoming references the refactoring cannot auto-clean or " //$NON-NLS-1$
                + "platform prohibitions (only the incoming references are left dangling). Default " //$NON-NLS-1$
                + "false = on confirm=true either condition BLOCKS deletion and is listed under its " //$NON-NLS-1$
                + "own output fields (independent of 'confirm', which is the preview gate).") //$NON-NLS-1$
            .integerProperty(KEY_TIMEOUT,
                "How long to wait for the UI-thread delete work, in seconds (default " //$NON-NLS-1$
                + DEFAULT_DELETE_TIMEOUT_SECONDS + ", clamped to " + MIN_DELETE_TIMEOUT_SECONDS //$NON-NLS-1$
                + ".." + MAX_DELETE_TIMEOUT_SECONDS + "). On expiry the call fails, but EDT may " //$NON-NLS-1$ //$NON-NLS-2$
                + "still finish a confirm=true delete, so verify the model before retrying. Does " //$NON-NLS-1$
                + "not cover the pre-flight cascade settle (a separate 60s bound).") //$NON-NLS-1$
            .build();
    }

    @Override
    protected long uiThreadBoundMs(Map<String, String> params)
    {
        return resolveDeleteTimeoutMs(params);
    }

    /** A preview cannot mutate, even when its non-preemptible UI work remains in flight. */
    @Override
    protected boolean uiThreadBoundOutcomeMayHaveMutated(Map<String, String> params,
        BoundedJob.Outcome outcome)
    {
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$
        return confirm && super.uiThreadBoundOutcomeMayHaveMutated(params, outcome);
    }

    @Override
    protected String uiThreadBoundError(Map<String, String> params, long timeoutMs,
        BoundedJob.Outcome outcome)
    {
        String fqn = JsonUtils.extractStringArgument(params, "fqn"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$
        return boundedOutcomeError(fqn, confirm, timeoutMs, outcome);
    }

    /**
     * Resolves the UI-thread delete bound for this call: the explicit {@code timeout} argument when
     * given, otherwise the configured per-tool default, clamped to the accepted range.
     *
     * @param params the raw tool arguments
     * @return the bound in milliseconds
     */
    static long resolveDeleteTimeoutMs(Map<String, String> params)
    {
        int configuredDefault = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, KEY_TIMEOUT, DEFAULT_DELETE_TIMEOUT_SECONDS);
        int seconds = JsonUtils.extractIntArgument(params, KEY_TIMEOUT, configuredDefault);
        return clampTimeoutSeconds(seconds) * 1000L;
    }

    /**
     * Clamps a delete bound to the range chosen for a non-preemptible cascade.
     *
     * @param seconds the requested bound in seconds
     * @return the accepted bound in seconds
     */
    static int clampTimeoutSeconds(int seconds)
    {
        if (seconds < MIN_DELETE_TIMEOUT_SECONDS)
        {
            return MIN_DELETE_TIMEOUT_SECONDS;
        }
        return Math.min(seconds, MAX_DELETE_TIMEOUT_SECONDS);
    }

    /**
     * Translates every non-completed bounded outcome without requiring a live workbench.
     * <p>
     * Package-visible so the unit test can pin the safety-critical distinction between a queued
     * delete our cancellation kept from starting and UI work that may still finish after the caller
     * stopped waiting.
     *
     * @param fqn the requested delete target
     * @param confirm whether this call could mutate the model
     * @param timeoutMs the configured caller-side bound
     * @param outcome the bounded-job outcome
     * @return the actionable error JSON
     */
    static String boundedOutcomeError(String fqn, boolean confirm, long timeoutMs,
        BoundedJob.Outcome outcome)
    {
        String target = fqn == null || fqn.isEmpty() ? "<missing fqn>" : fqn; //$NON-NLS-1$
        long seconds = Math.max(1L, Math.round(timeoutMs / 1000.0));
        switch (outcome)
        {
        case TIMED_OUT:
            return inFlightBoundError("Deleting '" + target + "' did not finish within " + seconds //$NON-NLS-1$ //$NON-NLS-2$
                + secondsSuffix(seconds) + ".", target, confirm, seconds); //$NON-NLS-1$
        case TIMED_OUT_BEFORE_START:
            return ToolResult.error("Deleting '" + target + "' did not START within " + seconds //$NON-NLS-1$ //$NON-NLS-2$
                + secondsSuffix(seconds) + ": the deadline elapsed while its UI-thread work was " //$NON-NLS-1$
                + "still queued, and cancelling it kept it from starting. NOTHING was deleted and " //$NON-NLS-1$
                + "the model is untouched - no check or cleanup is needed. Retry when EDT's job " //$NON-NLS-1$
                + "scheduler is less busy, or " + largerTimeoutAdvice(seconds)).toJson(); //$NON-NLS-1$
        case INTERRUPTED:
            return inFlightBoundError("Waiting for the deletion of '" + target //$NON-NLS-1$
                + "' was interrupted after " + seconds + secondsSuffix(seconds) + ".", //$NON-NLS-1$ //$NON-NLS-2$
                target, confirm, seconds);
        case NOT_RUN:
            return ToolResult.error("The delete request for '" + target + "' was cancelled before " //$NON-NLS-1$ //$NON-NLS-2$
                + "its UI-thread work started, so NOTHING was deleted and the model is untouched - " //$NON-NLS-1$
                + "no check or cleanup is needed. Retry; if it keeps happening, EDT is shutting " //$NON-NLS-1$
                + "down or another operation is cancelling background jobs.").toJson(); //$NON-NLS-1$
        case COMPLETED:
        default:
            return ToolResult.error("The deletion of '" + target + "' ended in an unrecognised " //$NON-NLS-1$ //$NON-NLS-2$
                + "bounded state (" + outcome + "). Whether it applied is unknown; call " //$NON-NLS-1$ //$NON-NLS-2$
                + "get_metadata_details on '" + target + "' before retrying.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** A running preview is harmless; a running confirmed delete must be treated as possibly applied. */
    private static String inFlightBoundError(String prefix, String fqn, boolean confirm, long seconds)
    {
        if (!confirm)
        {
            return ToolResult.error(prefix + " This was a PREVIEW (confirm=false), which never " //$NON-NLS-1$
                + "writes: nothing was deleted and the model is unchanged. EDT may still finish " //$NON-NLS-1$
                + "computing the preview, but it cannot apply the deletion. Retry later, or " //$NON-NLS-1$
                + largerTimeoutAdvice(seconds)).toJson();
        }
        return ToolResult.error(prefix + " The MCP call stopped waiting, but it did NOT stop EDT's " //$NON-NLS-1$
            + "UI-thread work: EDT may still finish deleting '" + fqn + "', and the model may " //$NON-NLS-1$ //$NON-NLS-2$
            + "already have changed. Before retrying, call get_metadata_details on '" + fqn //$NON-NLS-1$
            + "'; for a top-level target, also call get_metadata_objects for its metadata type. " //$NON-NLS-1$
            + largerTimeoutAdvice(seconds)).toJson();
    }

    /** Grammar helper for error messages that name the configured bound. */
    private static String secondsSuffix(long seconds)
    {
        return seconds == 1L ? " second" : " seconds"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The actionable lever, without recommending a value above the accepted maximum. */
    private static String largerTimeoutAdvice(long seconds)
    {
        if (seconds >= MAX_DELETE_TIMEOUT_SECONDS)
        {
            return "this is already the largest accepted '" + KEY_TIMEOUT + "', so check for a " //$NON-NLS-1$ //$NON-NLS-2$
                + "stuck build or another EDT operation holding the workspace."; //$NON-NLS-1$
        }
        return "pass a larger '" + KEY_TIMEOUT + "' (seconds, up to " //$NON-NLS-1$ //$NON-NLS-2$
            + MAX_DELETE_TIMEOUT_SECONDS + ") or raise the default in Preferences > MCP Server > " //$NON-NLS-1$ //$NON-NLS-2$
            + "Tools > " + NAME + "."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the request succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION, "Either 'preview', 'executed' or 'blocked'") //$NON-NLS-1$
            .stringProperty("fqn", "FQN of the node targeted for deletion") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_REFACTORING_TITLE, "Title of the delete refactoring (preview)") //$NON-NLS-1$
            .objectArrayProperty(KEY_ITEMS, "Metadata items the deletion would remove (preview)") //$NON-NLS-1$
            .booleanProperty(KEY_BLOCKING, "Whether blockingReferences or platformProhibitions BLOCK " //$NON-NLS-1$
                + "the delete; a confirm=true delete is refused unless force=true") //$NON-NLS-1$
            .objectArrayProperty("blockingReferences", "Genuine incoming references, represented only " //$NON-NLS-1$ //$NON-NLS-2$
                + "by EDT CleanReferenceProblem entries, that the refactoring cannot auto-clean: listed " //$NON-NLS-1$
                + "in the preview, the reason a delete is refused (action='blocked'), or left dangling " //$NON-NLS-1$
                + "when force=true (action='executed')") //$NON-NLS-1$
            .integerProperty("blockingReferencesCount", "Count of blocking references") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("affectedReferences", "Deprecated alias of blockingReferences (the " //$NON-NLS-1$ //$NON-NLS-2$
                + "same list), kept for one release for wire compatibility") //$NON-NLS-1$
            .integerProperty("affectedReferencesCount", "Deprecated alias of blockingReferencesCount " //$NON-NLS-1$ //$NON-NLS-2$
                + "(the same count), kept for one release for wire compatibility") //$NON-NLS-1$
            .objectArrayProperty(KEY_PLATFORM_PROHIBITIONS, "EDT refactoring problems other than " //$NON-NLS-1$
                + "CleanReferenceProblem: platform prohibitions, not incoming references") //$NON-NLS-1$
            .integerProperty(KEY_PLATFORM_PROHIBITIONS_COUNT, "Count of platform prohibitions") //$NON-NLS-1$
            .booleanProperty("forced", "Whether the delete was forced past a reference or platform block") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty(KEY_PERSISTED, "Present and false only for a partial forced-delete result: " //$NON-NLS-1$
                + "the model deletion completed, but its registering .mdo is still stale or could not " //$NON-NLS-1$
                + "be verified after the export wait") //$NON-NLS-1$
            .stringProperty(KEY_REGISTERING_FILE, "Project-relative .mdo path that still registers the " //$NON-NLS-1$
                + "deleted node or could not be verified (partial forced-delete result only)") //$NON-NLS-1$
            .stringProperty(KEY_REGISTERING_CONTAINER, "FQN of the object serialized in registeringFile " //$NON-NLS-1$
                + "(partial forced-delete result only)") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable description of the result") //$NON-NLS-1$
            .stringArrayProperty(WriteScope.RESULT_MEMBER, WriteScope.OUTPUT_SCHEMA_DESCRIPTION)
            .build();
    }

    @Override
    protected String beforeUiThreadOrError(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String fqn = JsonUtils.extractStringArgument(params, "fqn"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty() || fqn == null || fqn.isEmpty())
        {
            return null;
        }

        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);
        try
        {
            if (FormElementWriter.parse(normFqn) != null
                || FormElementWriter.parseFormObjectCreate(normFqn) != null
                || XdtoWriter.parseMemberRef(normFqn) != null
                || PredefinedWriter.parseRef(normFqn) != null)
            {
                return null;
            }
        }
        catch (RuntimeException e)
        {
            // Preserve the existing UI-thread validation/error path for a malformed specialized FQN.
            return null;
        }
        return cascadeSettler.settle(projectName, SETTLE_TIMEOUT_MS);
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, "fqn"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String fqn = JsonUtils.extractStringArgument(params, "fqn"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$
        if (!confirm)
        {
            // A preview writes nothing, on every branch - the flag IS the gate. Said once, here,
            // and safe against being wrong: an actual write record always beats it.
            WriteScope.recordNothingQueued();
        }
        boolean force = JsonUtils.extractBooleanArgument(params, "force", false); //$NON-NLS-1$

        // Normalized BEFORE the context is resolved, because the FQN goes IN: the specialized
        // deletes below (predefined item, XDTO member) return before the generic path that
        // appends the addressing hint, so a type this project kind cannot hold was reported as a
        // missing local owner - sending the caller to look for something that can never be here
        // (issue #309).
        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);
        ProjectContext ctx = resolveProjectAndScope(projectName, normFqn);
        if (ctx.hasError())
        {
            return ctx.error;
        }

        // A FQN addressing a FORM member (item / attribute / command / handler) is handled by a
        // dedicated branch: form members live on the editable Form content model (a cross-model hop),
        // not the mdclass tree, and are removed directly (the md-refactoring service is mdclass-only).
        FormElementWriter.FormMemberRef formRef = FormElementWriter.parse(normFqn);
        if (formRef != null)
        {
            String columnErr = FormElementWriter.columnAddressingError(formRef);
            if (columnErr != null)
            {
                return ToolResult.error(columnErr).toJson();
            }
            return deleteFormMember(ctx, normFqn, formRef, confirm);
        }

        // A 4-part form FQN (Type.Object.Form.FormName) addresses the FORM OBJECT itself. create_metadata
        // accepts this FQN to CREATE an owned form; to stay symmetric, delete it the same way: an
        // owned BasicForm is removed by cascade through its owner's 'forms' collection, not by the
        // md-refactoring service (it is not a top object, so resolveExisting / the delete refactoring see
        // nothing here). A CommonForm (2 parts) is NOT matched - it is a real top object handled below.
        FormElementWriter.FormObjectRef formObjectRef = FormElementWriter.parseFormObjectCreate(normFqn);
        if (formObjectRef != null)
        {
            return deleteFormObject(ctx, normFqn, formObjectRef, confirm);
        }

        // A FQN addressing an XDTO PACKAGE MEMBER (an ObjectType or a Property - issue #183 stream 1)
        // is handled by a dedicated branch too: it lives on the package's lazily materialized
        // xdto.model content (a cross-model hop), not the mdclass tree, so the md-refactoring service
        // (mdclass-only) cannot see it - removed directly instead, mirroring the form-member delete's
        // two-phase preview/confirm shape.
        XdtoWriter.MemberRef xdtoRef = XdtoWriter.parseMemberRef(normFqn);
        if (xdtoRef != null)
        {
            return deleteXdtoMember(ctx, normFqn, xdtoRef, confirm);
        }

        // A FQN addressing a PREDEFINED item (Catalog/ChartOfCharacteristicTypes.Name.Predefined.Item)
        // is handled by a dedicated branch too: the predefined content is a plain EMF containment on
        // the owner, not a top object the md-refactoring service can see (issue #293).
        PredefinedWriter.PredefinedRef predefinedRef = PredefinedWriter.parseRef(normFqn);
        if (predefinedRef != null)
        {
            return deletePredefinedItem(ctx, normFqn, predefinedRef, confirm, force);
        }

        // The md-refactoring service is needed ONLY by the generic mdclass path below - the
        // form-member / form-object / XDTO-member / predefined-item branches above delete directly
        // through their own content models, so its unavailability (e.g. during startup) must not block
        // them.
        IMdRefactoringService refactoringService = Activator.getDefault().getMdRefactoringService();
        if (refactoringService == null)
        {
            return ToolResult.error("IMdRefactoringService not available").toJson(); //$NON-NLS-1$
        }

        // Exact-first resolve with the yo-addressing fallback: create_metadata normalizes
        // 'yo'->'ye' in names by default, so a caller re-typing the original yo spelling
        // would miss the stored name — the resolver retries the normalized FQN.
        MetadataNodeResolver.ResolvedNode resolved =
            MetadataNodeResolver.resolveExistingWithYoFallback(ctx.scope, normFqn);
        MetadataNodeResolver.MetadataNode node = resolved.node;
        if (node == null)
        {
            return ToolResult.error("Node not found: " + fqn + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Check the FQN: 'Type.Name' for a top object (e.g. 'Catalog.Products'), " //$NON-NLS-1$
                + "'Type.Name.Kind.Name' for a member (e.g. 'Document.Order.Attribute.Amount'). " //$NON-NLS-1$
                + "Any node create_metadata can address can be deleted; see " //$NON-NLS-1$
                + "get_tool_guide('create_metadata') for the kinds. " //$NON-NLS-1$
                + "Use get_metadata_objects to find an object's FQN." //$NON-NLS-1$
                + MetadataNodeResolver.yoNotFoundHint(normFqn)
                + ctx.scope.addressingHint(normFqn)).toJson();
        }
        if (resolved.yoFallback)
        {
            Activator.logInfo("delete_metadata: '" + normFqn //$NON-NLS-1$
                + "' did not resolve exactly; proceeding with its yo-normalized form '" //$NON-NLS-1$
                + resolved.fqn + "'"); //$NON-NLS-1$
            normFqn = resolved.fqn;
        }

        BmModelResolver.Resolution modelResolution = BmModelResolver.resolveForRefactoring(ctx.project);
        // Read WHILE THE NODE IS STILL THERE: after the refactoring the node is detached, and the
        // container is what has to be re-exported (see performDeleteRefactoring).
        String containerFqn = containerExportFqn(node.owner);
        return prepareMdClassDelete(ctx.project, normFqn, node.object, containerFqn, confirm, force,
            refactoringService, modelResolution);
    }

    /**
     * The FQN of the top object whose {@code .mdo} REGISTERS the node being deleted - the file that
     * has to be rewritten for the deletion to be complete on disk.
     * <p>
     * Taken from the model, never derived from the caller's FQN string:
     * {@link MetadataNodeResolver.MetadataNode#owner} is the {@code Configuration} for a top object
     * and the owning {@code MdObject} for a member, and {@link #findTopContainer} climbs a member's
     * owner to the top object it is serialized into (a WebService operation parameter's owner is the
     * operation, whose file is the web service's). So a top-object delete names
     * {@code Configuration.mdo} and a member delete names the owning object's own {@code .mdo}.
     * <p>
     * Returns {@code null} when the container is not a BM object or its container chain has no top
     * (both mean "nothing here we can name"), and the caller then submits nothing rather than
     * guessing.
     *
     * Package-visible: which file a delete has to rewrite is the whole decision here, and it is not
     * observable from the tool's JSON result.
     *
     * @param owner the resolved node's container
     * @return the container's top-object FQN, or {@code null}
     */
    static String containerExportFqn(EObject owner)
    {
        if (!(owner instanceof IBmObject))
        {
            return null;
        }
        IBmObject top = findTopContainer((IBmObject)owner);
        return top == null ? null : top.bmGetFqn();
    }

    /**
     * Creates the EDT mdclass delete refactoring only after the shared BM resolver has verified the
     * target and dependent project models. Package-visible so the null-model refusal is covered
     * without requiring a live workbench.
     */
    String prepareMdClassDelete(IProject project, String normFqn, MdObject object,
        String containerFqn, boolean confirm, boolean force,
        IMdRefactoringService refactoringService, BmModelResolver.Resolution modelResolution)
    {
        if (!modelResolution.isAvailable())
        {
            return unavailableModelError(modelResolution, "Nothing was deleted."); //$NON-NLS-1$
        }

        IRefactoring refactoring;
        try
        {
            refactoring = refactoringService.createMdObjectDeleteRefactoring(
                Collections.singletonList(object));
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not prepare delete refactoring for " + normFqn, e); //$NON-NLS-1$
            return ToolResult.error("Could not prepare deletion of '" + normFqn + "' in project '" //$NON-NLS-1$ //$NON-NLS-2$
                + project.getName() + "'. Nothing was deleted. Use list_projects to check the project " //$NON-NLS-1$
                + "state and get_metadata_details to verify the target, then retry delete_metadata.") //$NON-NLS-1$
                .toJson();
        }
        if (refactoring == null)
        {
            return ToolResult.error("Failed to create delete refactoring for: " + normFqn).toJson(); //$NON-NLS-1$
        }

        return confirm ? performDelete(project, normFqn, containerFqn, refactoring, force)
            : buildPreview(normFqn, refactoring);
    }

    private String buildPreview(String fqn, IRefactoring refactoring)
    {
        List<Map<String, Object>> allItems = new ArrayList<>();

        String title = refactoring.getTitle();

        Collection<IRefactoringItem> items = refactoring.getItems();
        if (items != null)
        {
            for (IRefactoringItem item : items)
            {
                Map<String, Object> itemMap = new java.util.LinkedHashMap<>();
                itemMap.put("name", item.getName()); //$NON-NLS-1$
                itemMap.put("optional", item.isOptional()); //$NON-NLS-1$
                itemMap.put("checked", item.isChecked()); //$NON-NLS-1$
                allItems.add(itemMap);
            }
        }

        RefactoringProblems problems = collectRefactoringProblems(refactoring);
        boolean hasBlocking = problems.blocksDelete();

        String message = previewMessage(problems);

        // The legacy reference fields retain their documented meaning: only CleanReferenceProblem
        // entries are references. Every other problem is exposed separately as a platform prohibition.
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_PREVIEW)
            .put("fqn", fqn) //$NON-NLS-1$
            .put(KEY_REFACTORING_TITLE, title)
            .put(KEY_ITEMS, allItems)
            .put(KEY_BLOCKING, hasBlocking);
        return putRefactoringProblems(result, problems)
            .put(McpKeys.MESSAGE, message)
            .toJson();
    }

    private String performDelete(IProject project, String fqn, String containerFqn,
        IRefactoring refactoring, boolean force)
    {
        String projectName = project.getName();
        // EDT's own problem check: genuine incoming references and platform prohibitions are both
        // blocking conditions, but they remain distinct in the response. 'confirm' is the preview
        // gate; 'force' overrides either block.
        RefactoringProblems problems = collectRefactoringProblems(refactoring);
        if (problems.blocksDelete() && !force)
        {
            ToolResult blocked = ToolResult.error(blockedMessage(fqn, problems))
                .put(McpKeys.ACTION, "blocked") //$NON-NLS-1$
                .put("fqn", fqn) //$NON-NLS-1$
                .put(KEY_BLOCKING, true);
            return putRefactoringProblems(blocked, problems).toJson();
        }

        // Destructive-operation consent gate: the LAST check before the model mutation. Built from the
        // ref list the tool already computed; on ALLOW the behaviour is byte-identical, on REJECT the
        // caller returns an error and NOTHING is mutated. Headless / env-bypass / non-ASK never block.
        // The count/name line names the ACTUAL deletion target (count 1, its FQN) — like the other five
        // gated tools — so the common case (no blocking refs) reads "1 object: <fqn>" rather than a
        // misleading "0 objects:". Any incoming references the delete leaves dangling (force=true) are
        // described in the subtitle, where the count reflects the references, not the deletion.
        String subtitle = !problems.blocksDelete()
            ? "This deletes '" + fqn + "' and cascades reference cleanup (BSL, forms, metadata)." //$NON-NLS-1$ //$NON-NLS-2$
            : forcedConsentSubtitle(fqn, problems);
        ConsentPreview preview = new ConsentPreview(
            "Delete metadata node", //$NON-NLS-1$
            subtitle, 1, Collections.singletonList(fqn));
        return deleteWithConsent(preview,
            () -> performDeleteRefactoring(project, fqn, containerFqn, refactoring, force, problems));
    }

    /**
     * Runs the delete refactoring and builds the result. Split out of {@link #performDelete} so the
     * WHOLE mutation is the callback {@link #deleteWithConsent} invokes: this branch used to consult
     * the gate itself and then fall through to the work below it, which left "nothing is written
     * without ALLOW" true only by the order of statements (issue #331).
     *
     * <p>Call only after consent was granted.</p>
     *
     * @param project the project whose model is being changed
     * @param fqn the normalized FQN being deleted
     * @param containerFqn the FQN of the top object registering the deleted node, captured before
     *     the delete; {@code null} when it could not be named
     * @param refactoring the prepared delete refactoring
     * @param force whether blocking references were overridden
     * @param problems the references and platform prohibitions the caller already collected
     * @return the tool's JSON result
     */
    private String performDeleteRefactoring(IProject project, String fqn, String containerFqn,
        IRefactoring refactoring, boolean force, RefactoringProblems problems)
    {
        String projectName = project.getName();
        try
        {
            // EDT's refactoring API does not expose rollback/partial-apply state if perform()
            // throws. Record that opacity before entering it; a normal return is upgraded to the
            // known write below, while a throw makes the base finalizer emit outcome-unknown.
            WriteScope.recordUndeterminable("delete refactoring may mutate before throwing", //$NON-NLS-1$
                Collections.singletonList(projectName));
            refactoring.perform();
            // This project was written in, whatever the container export below manages to queue:
            // stating it here rather than leaving it to that submission keeps the wait honest when
            // the container could not be named at all.
            WriteScope.recordWrite(project);
            // The cascade also cleans the references held by dependent extensions - EDT's
            // refactoring writes them, we do not, and it does not report which of them it touched.
            // So they are declared as projects this call MAY have written in: awaited, so a caller
            // that reads the disk next no longer sees the extension half-written, but never able to
            // refuse, because a stalled queue in a project we never submitted to is not evidence
            // about this call. That grading is what makes awaiting them safe at all - the set is
            // "every open extension of the target", i.e. what EDT SCANS, and awaiting a
            // scanned-but-untouched extension under the strict grade would fail a healthy delete.
            for (IProject participant : cascadeParticipants.of(project))
            {
                WriteScope.recordCascade(participant);
            }
            // Said out loud in the answer, not only in the log: when this call could not queue the
            // container's export, the barrier behind it is back to reporting only what the
            // refactoring queued on its own - which is exactly the state that let a delete answer
            // over a stale Configuration.mdo. A caller that is about to read the disk has to be
            // able to tell the two apart, and the specialized branches already say so for their
            // own write (see the "in-memory only" clause on the form-object delete).
            // Names the CONTAINER's file, never "its": the file at risk is the one that registers
            // the deleted node (Configuration.mdo for a top object, the owner .mdo for a member),
            // and the deleted object's own file was never this call's to queue.
            String exportLag = submitContainerExport(project, containerFqn)
                ? "" //$NON-NLS-1$
                : " The .mdo export of " //$NON-NLS-1$
                    + (containerFqn == null || containerFqn.isEmpty()
                        ? "the object that registers it" : "'" + containerFqn + "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " could not be queued by this call, so that file may still list the deleted " //$NON-NLS-1$
                    + "node; re-check it before relying on it."; //$NON-NLS-1$
            ToolResult result = ToolResult.success()
                .put(McpKeys.ACTION, VAL_EXECUTED)
                .put("fqn", fqn) //$NON-NLS-1$
                .put("forced", force); //$NON-NLS-1$
            if (force)
            {
                String registeringFile = registeringFilePath(containerFqn);
                if (containerFqn != null && !containerFqn.isEmpty())
                {
                    // Carried through the base export wait, then removed on a verified happy path.
                    // A partial result retains both fields so it names the exact file and container.
                    result.put(KEY_REGISTERING_CONTAINER, containerFqn);
                    if (registeringFile != null)
                    {
                        result.put(KEY_REGISTERING_FILE, registeringFile);
                    }
                }
            }
            if (problems.blocksDelete())
            {
                putRefactoringProblems(result, problems)
                    .put(McpKeys.MESSAGE, forcedResultMessage(problems) + exportLag);
            }
            else
            {
                result.put(McpKeys.MESSAGE, "Delete refactoring completed successfully." + exportLag); //$NON-NLS-1$
            }
            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error performing delete refactoring", e); //$NON-NLS-1$
            return ToolResult.error("Delete failed for '" + fqn + "' in project '" + projectName //$NON-NLS-1$ //$NON-NLS-2$
                + "'. The final state is uncertain. Use get_metadata_details to check whether the " //$NON-NLS-1$
                + "node still exists before retrying delete_metadata.").toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Queues the export of the container the delete just emptied - the SUBMIT half of "submit, then
     * wait".
     * <p>
     * The export barrier this tool inherits only WAITS. A wait is ordered with an export only when
     * the same call put that export in the queue: {@code create_metadata} and the four specialized
     * delete branches call {@code forceExportToDisk} and only then let the barrier run, so by the
     * time they answer, nothing of THEIRS is still queued. (Which is all a drained queue ever
     * proves - the platform logs a per-file write failure and completes the computation anyway, so
     * "drained" never means "the bytes are right".) This branch left the scheduling to EDT's
     * md-refactoring, and the barrier's probe of the export segment could therefore answer "quiet"
     * truthfully and uselessly - observed on run 31728870176: the object's own {@code .mdo} already
     * gone, {@code Configuration.mdo} not yet rewritten, and no barrier-failure marker in the log.
     * Submitting here restores the ordering by construction instead of by timing luck.
     * <p>
     * Two things it deliberately does NOT do, because the platform cannot be asked for them:
     * <ul>
     * <li>the deleted object's OWN {@code .mdo} (or its removal) is not resubmitted - EDT builds a
     * save task by looking the FQN up in the transaction, so an FQN that no longer resolves yields
     * no task at all. That file stays scheduled by the refactoring alone;</li>
     * <li>the cascade's other files - referring objects cleaned in this project, and any dependent
     * extension - are not submitted either. Naming them would mean re-exporting an unbounded set
     * inside one deadline, and a false REFUSAL on a healthy delete costs more than a miss. That
     * boundary is issue #408's, and the tool reporting what it actually wrote is the maintainer's
     * call, not something to be inferred here.
     * </ul>
     * A refusal or a {@link RuntimeException} never becomes a failed delete - the model change
     * already happened, and answering "delete failed" would be a worse lie than answering late. It
     * is REPORTED instead: the caller is told which file may lag, and the log carries the reason.
     * That is also why the submission is wrapped here rather than left to the caller's catch block:
     * an exception thrown on the way to queueing an export says nothing about whether the delete
     * succeeded. An {@link Error} is deliberately NOT caught and reaches the caller's handler - a
     * JVM-level failure is not a fact about this export.
     *
     * @param project the project owning the container
     * @param containerFqn the container's top-object FQN, or {@code null} when it could not be named
     * @return whether the platform accepted an export task for the container
     */
    private boolean submitContainerExport(IProject project, String containerFqn)
    {
        if (containerFqn == null || containerFqn.isEmpty())
        {
            Activator.logInfo("delete_metadata: the deleted node's container could not be named, so " //$NON-NLS-1$
                + "no .mdo export was queued for it; the export barrier can only report what the " //$NON-NLS-1$
                + "refactoring queued on its own"); //$NON-NLS-1$
            return false;
        }
        try
        {
            if (exportSubmitter.submit(project, containerFqn))
            {
                return true;
            }
            Activator.logInfo("delete_metadata: the platform accepted no .mdo export task for '" //$NON-NLS-1$
                + containerFqn + "' after the delete; the export barrier can only report what the " //$NON-NLS-1$
                + "refactoring queued on its own"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            Activator.logError("delete_metadata: queueing the .mdo export of '" + containerFqn //$NON-NLS-1$
                + "' after the delete threw", e); //$NON-NLS-1$
        }
        return false;
    }

    /**
     * Verifies the forced delete's on-disk half only after the shared export barrier has run. A clean
     * result is returned byte-for-byte unchanged: the temporary registering-file fields are removed.
     * A stale or unreadable registration remains a successful executed MODEL change, but gains
     * {@code persisted=false} and retains the exact file/container so clients can treat it as partial.
     */
    @Override
    protected String refreshAfterExportAwait(Map<String, String> params, String result,
        boolean drainEstablished)
    {
        JsonObject object;
        try
        {
            object = JsonParser.parseString(result).getAsJsonObject();
        }
        catch (RuntimeException e)
        {
            Activator.logError("delete_metadata: could not read the forced-delete result for " //$NON-NLS-1$
                + "on-disk verification", e); //$NON-NLS-1$
            return result;
        }
        if (!VAL_EXECUTED.equals(resultString(object, McpKeys.ACTION))
            || !object.has("forced") || !object.get("forced").getAsBoolean() //$NON-NLS-1$ //$NON-NLS-2$
            || !object.has(KEY_REGISTERING_CONTAINER))
        {
            return result;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String targetFqn = resultString(object, "fqn"); //$NON-NLS-1$
        String registeringFile = resultString(object, KEY_REGISTERING_FILE);
        String registeringContainer = resultString(object, KEY_REGISTERING_CONTAINER);
        RegistrationState state = registeringFile == null ? RegistrationState.UNVERIFIABLE
            : registrationVerifier.verify(projectName, registeringFile, registeringContainer, targetFqn);
        if (state == RegistrationState.ABSENT)
        {
            object.remove(KEY_REGISTERING_FILE);
            object.remove(KEY_REGISTERING_CONTAINER);
            return GsonProvider.toJson(object);
        }

        // The model change completed. Do not turn that fact into a failure; state only that its
        // registering file is not confirmed current.
        object.addProperty(KEY_PERSISTED, false);
        String message = resultString(object, McpKeys.MESSAGE);
        String subject = registeringContainer == null || registeringContainer.isEmpty()
            ? "the object that registers it" : "'" + registeringContainer + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String verificationLag;
        if (state == RegistrationState.PRESENT)
        {
            verificationLag = " The .mdo export of " + subject + " did not remove the deleted node from '" //$NON-NLS-1$ //$NON-NLS-2$
                + registeringFile + "', so that file still lists the deleted node; re-check it before " //$NON-NLS-1$
                + "relying on it."; //$NON-NLS-1$
        }
        else if (registeringFile != null)
        {
            verificationLag = " The .mdo export of " + subject + " could not be verified in '" //$NON-NLS-1$ //$NON-NLS-2$
                + registeringFile + "', so that file may still list the deleted node; re-check it " //$NON-NLS-1$
                + "before relying on it."; //$NON-NLS-1$
        }
        else
        {
            // submitContainerExport already emitted the established export-lag wording for this
            // unnameable file; persisted=false supplies the structured partial-result signal.
            verificationLag = ""; //$NON-NLS-1$
        }
        object.addProperty(McpKeys.MESSAGE, (message == null ? "" : message) + verificationLag); //$NON-NLS-1$
        return GsonProvider.toJson(object);
    }

    /** Resolves the registering container's project-relative {@code .mdo} path. */
    static String registeringFilePath(String containerFqn)
    {
        if ("Configuration".equals(containerFqn)) //$NON-NLS-1$
        {
            return "src/Configuration/Configuration.mdo"; //$NON-NLS-1$
        }
        String direct = MetadataPathResolver.resolveTopObjectMdoPath(containerFqn);
        if (direct != null)
        {
            return direct;
        }

        // Nested subsystems are top BM objects too, stored below their parent's Subsystems folder.
        String[] parts = containerFqn == null ? new String[0] : containerFqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 4 || (parts.length & 1) != 0
            || !"Subsystems".equals(MetadataPathResolver.resolveMetadataDir(parts[0]))) //$NON-NLS-1$
        {
            return null;
        }
        StringBuilder path = new StringBuilder("src/Subsystems/").append(parts[1]); //$NON-NLS-1$
        for (int i = 2; i < parts.length; i += 2)
        {
            if (!"subsystems".equals(MetadataNodeResolver.featureNameForKind(parts[i]))) //$NON-NLS-1$
            {
                return null;
            }
            path.append("/Subsystems/").append(parts[i + 1]); //$NON-NLS-1$
        }
        return path.append('/').append(parts[parts.length - 1]).append(".mdo").toString(); //$NON-NLS-1$
    }

    /** Reads and structurally checks one registering {@code .mdo}. */
    private static RegistrationState verifyRegistrationOnDisk(String projectName,
        String registeringFile, String registeringContainer, String targetFqn)
    {
        if (projectName == null || registeringFile == null || registeringContainer == null
            || targetFqn == null)
        {
            return RegistrationState.UNVERIFIABLE;
        }
        try
        {
            // Fully qualified on purpose: the inherited AbstractMetadataWriteTool.ProjectContext
            // shadows the utils one, so an import here would not compile.
            com.ditrix.edt.mcp.server.utils.ProjectContext projectContext =
                com.ditrix.edt.mcp.server.utils.ProjectContext.of(projectName);
            IProject project = projectContext.project();
            if (!projectContext.exists())
            {
                return RegistrationState.UNVERIFIABLE;
            }
            IFile file = project.getFile(new Path(registeringFile));
            if (!file.exists())
            {
                return RegistrationState.UNVERIFIABLE;
            }
            DocumentBuilderFactory factory = SecureXml.documentBuilderFactory();
            try (InputStream input = file.getContents())
            {
                Element root = factory.newDocumentBuilder().parse(input).getDocumentElement();
                return containsRegistration(root, registeringContainer, targetFqn)
                    ? RegistrationState.PRESENT : RegistrationState.ABSENT;
            }
        }
        catch (Exception e)
        {
            Activator.logError("delete_metadata: could not verify '" + targetFqn + "' in '" //$NON-NLS-1$ //$NON-NLS-2$
                + registeringFile + "'", e); //$NON-NLS-1$
            return RegistrationState.UNVERIFIABLE;
        }
    }

    /** Package-visible pure XML check for the registering-file tests. */
    static boolean containsRegistration(Element root, String registeringContainer, String targetFqn)
    {
        if (root == null || registeringContainer == null || targetFqn == null)
        {
            return false;
        }
        if ("Configuration".equals(registeringContainer)) //$NON-NLS-1$
        {
            String[] target = targetFqn.split("\\."); //$NON-NLS-1$
            if (target.length != 2)
            {
                return false;
            }
            String feature = MetadataTypeUtils.getConfigReferenceName(target[0]);
            for (Element child : directChildren(root, feature))
            {
                if (targetFqn.equalsIgnoreCase(child.getTextContent().trim()))
                {
                    return true;
                }
            }
            return false;
        }

        String prefix = registeringContainer + "."; //$NON-NLS-1$
        if (targetFqn.length() <= prefix.length()
            || !targetFqn.regionMatches(true, 0, prefix, 0, prefix.length()))
        {
            return false;
        }
        String[] remainder = targetFqn.substring(prefix.length()).split("\\."); //$NON-NLS-1$
        if (remainder.length == 0 || (remainder.length & 1) != 0)
        {
            return false;
        }
        Element current = root;
        String addressedPrefix = registeringContainer;
        for (int i = 0; i < remainder.length; i += 2)
        {
            String feature = MetadataNodeResolver.featureNameForKind(remainder[i]);
            if (feature == null)
            {
                return false;
            }
            String name = remainder[i + 1];
            addressedPrefix += "." + remainder[i] + "." + name; //$NON-NLS-1$ //$NON-NLS-2$
            Element match = null;
            for (Element candidate : directChildren(current, feature))
            {
                if (addressedPrefix.equalsIgnoreCase(simpleText(candidate))
                    || name.equalsIgnoreCase(directChildText(candidate, "name"))) //$NON-NLS-1$
                {
                    match = candidate;
                    break;
                }
            }
            if (match == null)
            {
                return false;
            }
            current = match;
        }
        return true;
    }

    /**
     * The direct children that belong to THIS document's registration vocabulary: the local name
     * matches AND the element is either unqualified (how EDT writes every child of an {@code .mdo})
     * or in the document element's own namespace. A same-named element from a FOREIGN namespace is
     * not a registration, and counting one would report a completed delete as partial.
     */
    private static List<Element> directChildren(Element parent, String name)
    {
        List<Element> result = new ArrayList<>();
        if (parent == null || name == null)
        {
            return result;
        }
        Document owner = parent.getOwnerDocument();
        Element root = owner == null ? null : owner.getDocumentElement();
        String documentNamespace = root == null ? null : root.getNamespaceURI();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node child = children.item(i);
            if (child instanceof Element element && name.equals(elementName(element))
                && belongsToDocument(element, documentNamespace))
            {
                result.add(element);
            }
        }
        return result;
    }

    /** Whether an element is part of the document's own vocabulary rather than a foreign one. */
    private static boolean belongsToDocument(Element element, String documentNamespace)
    {
        String namespace = element.getNamespaceURI();
        return namespace == null || namespace.equals(documentNamespace);
    }

    private static String directChildText(Element parent, String name)
    {
        List<Element> children = directChildren(parent, name);
        return children.isEmpty() ? "" : children.get(0).getTextContent().trim(); //$NON-NLS-1$
    }

    private static String simpleText(Element element)
    {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            if (children.item(i) instanceof Element)
            {
                return ""; //$NON-NLS-1$
            }
        }
        return element.getTextContent().trim();
    }

    private static String elementName(Element element)
    {
        String local = element.getLocalName();
        return local == null ? element.getNodeName() : local;
    }

    private static String unavailableModelError(BmModelResolver.Resolution resolution,
        String stateStatement)
    {
        return ToolResult.error(resolution.actionableError(NAME, stateStatement)).toJson();
    }

    /**
     * Puts the blocking-reference list and count onto {@code result} — the SINGLE place every response
     * branch (preview / blocked / forced execute / form previews) emits them, so the legacy aliases
     * below can never drift from the canonical keys. Package-visible for tests.
     */
    static ToolResult putBlockingReferences(ToolResult result, List<Map<String, Object>> blocking)
    {
        return result
            .put("blockingReferences", blocking) //$NON-NLS-1$
            .put("blockingReferencesCount", blocking.size()) //$NON-NLS-1$
            // legacy aliases of blockingReferences*, kept for one release for wire compatibility (upstream review)
            .put("affectedReferences", blocking) //$NON-NLS-1$
            .put("affectedReferencesCount", blocking.size()); //$NON-NLS-1$
    }

    /** Emits the platform-prohibition side of the split. */
    private static ToolResult putPlatformProhibitions(ToolResult result,
        List<Map<String, Object>> prohibitions)
    {
        return result
            .put(KEY_PLATFORM_PROHIBITIONS, prohibitions)
            .put(KEY_PLATFORM_PROHIBITIONS_COUNT, prohibitions.size());
    }

    /** Emits both problem categories without changing the legacy reference aliases. */
    private static ToolResult putRefactoringProblems(ToolResult result, RefactoringProblems problems)
    {
        return putPlatformProhibitions(putBlockingReferences(result, problems.references),
            problems.prohibitions);
    }

    /** The two semantically different categories returned by EDT's refactoring status. */
    private static final class RefactoringProblems
    {
        final List<Map<String, Object>> references = new ArrayList<>();
        final List<Map<String, Object>> prohibitions = new ArrayList<>();

        boolean blocksDelete()
        {
            return !references.isEmpty() || !prohibitions.isEmpty();
        }
    }

    /**
     * Splits the refactoring's blocking problems by the one verified semantic discriminator the EDT
     * API provides here: {@link CleanReferenceProblem} is a genuine incoming reference; every other
     * {@link IRefactoringProblem} is a platform prohibition. No unverified platform subtype list is
     * encoded. Never throws on a single odd problem.
     */
    private static RefactoringProblems collectRefactoringProblems(IRefactoring refactoring)
    {
        RefactoringProblems result = new RefactoringProblems();

        RefactoringStatus status = refactoring.getStatus();
        if (status == null)
        {
            return result;
        }
        Collection<IRefactoringProblem> problems = status.getProblems();
        if (problems == null)
        {
            return result;
        }

        for (IRefactoringProblem problem : problems)
        {
            Map<String, Object> description = describeProblem(problem);
            if (problem instanceof CleanReferenceProblem)
            {
                result.references.add(description);
            }
            else
            {
                result.prohibitions.add(description);
            }
        }
        return result;
    }

    private static String previewMessage(RefactoringProblems problems)
    {
        if (!problems.blocksDelete())
        {
            return "Preview of delete refactoring. References listed above will be cleaned up. " //$NON-NLS-1$
                + "Call with confirm=true to apply."; //$NON-NLS-1$
        }
        if (problems.references.isEmpty())
        {
            return "Preview of delete refactoring. EDT reports " + problems.prohibitions.size() //$NON-NLS-1$
                + " platform prohibition(s): a confirm=true delete will be BLOCKED unless force=true " //$NON-NLS-1$
                + "is also passed."; //$NON-NLS-1$
        }
        String message = "Preview of delete refactoring. This node has " + problems.references.size() //$NON-NLS-1$
            + " incoming reference(s) the refactoring CANNOT auto-clean"; //$NON-NLS-1$
        if (!problems.prohibitions.isEmpty())
        {
            message += ", and EDT reports " + problems.prohibitions.size() //$NON-NLS-1$
                + " separate platform prohibition(s)"; //$NON-NLS-1$
        }
        return message + ": a confirm=true delete will be BLOCKED unless force=true is also passed " //$NON-NLS-1$
            + "(force leaves the incoming references dangling)."; //$NON-NLS-1$
    }

    private static String blockedMessage(String fqn, RefactoringProblems problems)
    {
        if (problems.references.isEmpty())
        {
            return "Cannot delete '" + fqn + "': EDT reports " + problems.prohibitions.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " platform prohibition(s). Resolve the platform restriction, or call again with " //$NON-NLS-1$
                + "force=true to delete anyway."; //$NON-NLS-1$
        }
        String message = "Cannot delete '" + fqn + "': it has " + problems.references.size() //$NON-NLS-1$ //$NON-NLS-2$
            + " incoming reference(s) that the refactoring cannot auto-clean"; //$NON-NLS-1$
        if (!problems.prohibitions.isEmpty())
        {
            message += " and " + problems.prohibitions.size() + " separate platform prohibition(s)"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        // The ADVICE has to track what is actually present, not just the description above it:
        // telling a caller to "resolve the platform restrictions" when EDT reported none sends them
        // after something they do not have.
        String remedy = problems.prohibitions.isEmpty()
            ? "Remove the incoming references" //$NON-NLS-1$
            : "Remove the incoming references and resolve the platform restrictions"; //$NON-NLS-1$
        return message + ". " + remedy + ", or call again with force=true to delete anyway " //$NON-NLS-1$ //$NON-NLS-2$
            + "(the objects that reference it are left with dangling references)."; //$NON-NLS-1$
    }

    private static String forcedConsentSubtitle(String fqn, RefactoringProblems problems)
    {
        if (problems.references.isEmpty())
        {
            return "This deletes '" + fqn + "' despite " + problems.prohibitions.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " platform prohibition(s) reported by EDT."; //$NON-NLS-1$
        }
        String subtitle = "This deletes '" + fqn //$NON-NLS-1$
            + "' and cascades cleanup; " + problems.references.size() //$NON-NLS-1$
            + " incoming reference(s) the refactoring cannot auto-clean will be left dangling"; //$NON-NLS-1$
        if (!problems.prohibitions.isEmpty())
        {
            subtitle += ", and " + problems.prohibitions.size() //$NON-NLS-1$
                + " platform prohibition(s) will be overridden"; //$NON-NLS-1$
        }
        return subtitle + "."; //$NON-NLS-1$
    }

    private static String forcedResultMessage(RefactoringProblems problems)
    {
        if (problems.references.isEmpty())
        {
            return "Delete refactoring completed (forced). " + problems.prohibitions.size() //$NON-NLS-1$
                + " platform prohibition(s) were overridden."; //$NON-NLS-1$
        }
        String message = "Delete refactoring completed (forced). " + problems.references.size() //$NON-NLS-1$
            + " incoming reference(s) were left dangling."; //$NON-NLS-1$
        if (!problems.prohibitions.isEmpty())
        {
            message += " " + problems.prohibitions.size() //$NON-NLS-1$
                + " platform prohibition(s) were overridden."; //$NON-NLS-1$
        }
        return message;
    }

    /**
     * Describes a single refactoring {@link IRefactoringProblem} as a JSON-ready map: the problem type
     * plus, best-effort, the referencing object / feature (for a {@link CleanReferenceProblem}) and the
     * target object. Mirrors what the EDT/Configurator UI shows per blocking reference. Never throws on
     * a single odd problem — a description failure is logged and the partial map is still returned.
     */
    private static Map<String, Object> describeProblem(IRefactoringProblem problem)
    {
        Map<String, Object> problemMap = new java.util.LinkedHashMap<>();
        problemMap.put("problemType", problem.getClass().getSimpleName()); //$NON-NLS-1$
        // Best-effort description; never let a single odd problem abort the whole check.
        try
        {
            if (problem instanceof CleanReferenceProblem crp)
            {
                EObject refObj = crp.getReferencingObject();
                if (refObj instanceof IBmObject bmObj)
                {
                    String refFqn = bmFqnSafe(bmObj);
                    if (refFqn != null)
                    {
                        problemMap.put("referencingObject", refFqn); //$NON-NLS-1$
                    }
                }
                EStructuralFeature feat = crp.getReference();
                if (feat != null)
                {
                    problemMap.put("reference", feat.getName()); //$NON-NLS-1$
                }
            }
            EObject obj = problem.getObject();
            if (obj instanceof IBmObject bmObj)
            {
                String tgtFqn = bmFqnSafe(bmObj);
                if (tgtFqn != null)
                {
                    problemMap.put("targetObject", tgtFqn); //$NON-NLS-1$
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error describing refactoring problem", e); //$NON-NLS-1$
        }
        return problemMap;
    }

    /**
     * Returns a human-readable FQN for a BM object. {@code bmGetFqn()} is only legal on top objects,
     * so for a nested object (e.g. a register dimension or a type item that holds the reference) we
     * climb to the owning top object and append the nested element's name when one is available.
     * Never throws.
     */
    private static String bmFqnSafe(IBmObject obj)
    {
        if (obj == null)
        {
            return null;
        }
        try
        {
            if (obj.bmIsTop())
            {
                return obj.bmGetFqn();
            }
        }
        catch (Exception e)
        {
            // fall through to top-object resolution
        }

        String localName = null;
        if (obj instanceof MdObject mdo)
        {
            localName = mdo.getName();
        }
        else if (obj instanceof org.eclipse.emf.ecore.ENamedElement ene)
        {
            localName = ene.getName();
        }

        try
        {
            IBmObject top = obj.bmGetTopObject();
            if (top != null && top != obj)
            {
                String topFqn = top.bmGetFqn();
                if (topFqn != null)
                {
                    return (localName != null && !localName.isEmpty())
                        ? topFqn + " (" + localName + ")" //$NON-NLS-1$ //$NON-NLS-2$
                        : topFqn;
                }
            }
        }
        catch (Exception e)
        {
            // ignore — fall back to the local name (or null)
        }
        return localName;
    }

    // ==================== FORM members (cross-model hop) ====================

    /** The project's platform version, or {@code null} when it cannot be resolved. */
    private static Version platformVersionOf(ProjectContext ctx)
    {
        IV8ProjectManager manager = Activator.getDefault().getV8ProjectManager();
        IV8Project project = manager != null ? manager.getProject(ctx.project) : null;
        return project != null ? project.getVersion() : null;
    }

    /**
     * Deletes a FORM member (item / attribute / command / handler) addressed by a form FQN. The member
     * lives on the editable Form content model, so it is removed directly with {@link EcoreUtil#remove}
     * (a Group / Table cascades its contained subtree because {@code items} is containment) - the
     * md-refactoring service that cascades mdclass references does NOT apply here, so a cross-reference
     * to the removed member (a field's dataPath, a button's command) is NOT rewritten; the caller
     * should re-read the form afterwards. Two-phase like the mdclass path: {@code confirm=false}
     * previews what would be removed (no write transaction), {@code confirm=true} removes it and
     * force-exports the content form to {@code Form.form}.
     */
    private String deleteFormMember(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, boolean confirm)
    {
        final boolean handler = FormElementWriter.isHandlerToken(ref.kindToken);
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.scope, ref.formPath,
                "Form not found for '" + normFqn + "'. Address a form member as " //$NON-NLS-1$ //$NON-NLS-2$
                    + "'Type.Object.Form.FormName.<Kind>.Name' or 'CommonForm.FormName.<Kind>.Name' " //$NON-NLS-1$
                    + "(Kind = Attribute / Command / Parameter / Field / Button / Group / " //$NON-NLS-1$
                    + "Decoration / Table / Column on a collection attribute / " //$NON-NLS-1$
                    + "Handler)."); //$NON-NLS-1$
            // The #343 advice may quote a corrected handler address, and whether the corrected
            // owner really carries that event is a question only the platform type can answer.
            final Version version = platformVersionOf(ctx);
            if (!confirm)
            {
                return buildFormDeletePreview(fctx, normFqn, ref, handler, version);
            }
            // Resolve and read the real preview BEFORE asking: a typo must answer "not found"
            // without ever raising a destructive dialog, and the prompt must list what will actually
            // be removed. The gate is the LAST check before the write, and runs outside any
            // transaction because it may block on a UI dialog (issue #331 / #295 review).
            FormDeletePreview data = readFormDeletePreview(fctx, ref, handler, normFqn, version);
            if (!data.found)
            {
                return formMemberNotFound(ref, handler, data.kindAdvice);
            }
            return gateFormMemberDelete(normFqn, ref, handler, data,
                () -> performFormDelete(fctx, normFqn, ref, handler, version));
        }
        catch (Exception e)
        {
            String ready = FormValidationException.jsonOf(e);
            if (ready != null)
            {
                return ready;
            }
            Activator.logError("Error deleting form member", e); //$NON-NLS-1$
            return ToolResult.error("Failed to delete form member: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    /** Resolves the delete target: a handler (form/item container) or a member (attribute/command/item). */
    private static EObject resolveFormTarget(EObject formModel, FormElementWriter.FormMemberRef ref,
        boolean handler)
    {
        if (handler)
        {
            // The container is the form root, a form ITEM, or a form COMMAND (whose single Action
            // handler is its contained action - removing it clears the binding).
            EObject container = FormElementWriter.resolveHandlerContainer(formModel, ref);
            return container == null ? null : FormElementWriter.findFormHandler(container, ref.name);
        }
        return FormElementWriter.resolveFormMember(formModel, ref);
    }

    /**
     * The "not found" error for a form delete target. {@code advice} is the kind-mismatch tail computed
     * INSIDE the transaction (see {@link FormElementWriter#kindMismatchAdvice}): the resolution is
     * kind-aware (issue #343), so an address whose kind segment names another element's kind must say
     * which kind the same-named element really has instead of a bare "not found". Empty when there is
     * nothing to add, in which case the generic pointer is kept verbatim.
     */
    private static String formMemberNotFound(FormElementWriter.FormMemberRef ref, boolean handler,
        String advice)
    {
        if (handler)
        {
            // A non-empty advice here is only produced when the OWNER itself did not resolve (see
            // formTargetAdvice), so the miss is the owner's, not the handler's - saying "no event
            // handler" would blame the wrong thing about an element that does have one. The subject
            // follows the OWNER's token: a Command address misses a form COMMAND, not an item.
            if (!advice.isEmpty())
            {
                boolean commandOwner = FormElementWriter.kindForToken(ref.itemKindToken)
                    == FormElementWriter.Kind.COMMAND;
                return ToolResult.error((commandOwner ? "Form command not found: " //$NON-NLS-1$
                    : "Form item not found: ") + ref.itemName + " (kind '" //$NON-NLS-1$ //$NON-NLS-2$
                    + ref.itemKindToken + "') on " + ref.formPath + advice).toJson(); //$NON-NLS-1$
            }
            return ToolResult.error("No event handler for '" + ref.name + "' on " //$NON-NLS-1$ //$NON-NLS-2$
                + (ref.isItemLevel() ? ref.formPath + "." + ref.itemName : ref.formPath) //$NON-NLS-1$
                + ". Use get_metadata_details to list the handlers.").toJson(); //$NON-NLS-1$
        }
        return ToolResult.error("Form member not found: " + ref.name + " (kind '" + ref.kindToken //$NON-NLS-1$ //$NON-NLS-2$
            + "') on " + ref.formPath //$NON-NLS-1$
            + (advice.isEmpty() ? ". Use get_metadata_details to list the members." : advice)) //$NON-NLS-1$
            .toJson();
    }

    /**
     * The kind-mismatch advice for a delete target that did not resolve, computed on the tx-bound form
     * model: for an ITEM-LEVEL handler address the OWNER's kind segment is the one that can be wrong,
     * for a member address the leaf's. A FORM-LEVEL handler address ({@code ...Form.F.Handler.OnOpen})
     * carries no element kind segment at all - its leaf is an EVENT name - so it has no advice.
     *
     * <p>For a handler the advice is asked for ONLY when the owner itself did not resolve. Otherwise a
     * genuinely missing handler on a resolved owner would pick up advice about a same-named element of
     * another kind ({@code ...Command.Sync.Handler.Action} on an existing command {@code Sync} while a
     * BUTTON {@code Sync} also exists) and report an owner miss that did not happen.</p>
     */
    private static String formTargetAdvice(EObject formModel, FormElementWriter.FormMemberRef ref,
        boolean handler, String normFqn, Version version)
    {
        if (handler)
        {
            return FormElementWriter.resolveHandlerContainer(formModel, ref) != null
                ? "" //$NON-NLS-1$
                : FormElementWriter.handlerOwnerKindMismatchAdvice(formModel, ref, normFqn, version);
        }
        return FormElementWriter.kindMismatchAdvice(formModel, ref.kindToken, ref.name, normFqn);
    }

    /**
     * The FORM-MEMBER branch's authorization step: builds the prompt from what the preview actually
     * found and hands the branch's write to {@link #deleteWithConsent}. Package-private and taking the
     * write as a parameter so a unit test can drive THIS branch's prompt and refusal without an EDT
     * context; that the branch REACHES this step - and that no branch reaches a write without it - is
     * pinned separately by {@code DeleteMetadataConsentSinglePointRatchetTest} (issue #331).
     *
     * @param normFqn the normalized FQN being deleted
     * @param ref the parsed form-member ref
     * @param handler whether the FQN addresses an event handler
     * @param data what the read preview found
     * @param write this branch's mutation
     * @return the mutation's result, or the refusal error
     */
    String gateFormMemberDelete(String normFqn, FormElementWriter.FormMemberRef ref, boolean handler,
        FormDeletePreview data, DeleteWrite write)
    {
        // The breakdown is DERIVED from what the walk actually found, not a fixed phrase naming the
        // kinds the walk used to follow: the prompt named "nested items, attribute columns" while an
        // event handler and a command's action went along unmentioned (issue #295 review).
        ConsentPreview preview = new ConsentPreview(
            handler ? "Delete form event handler" : "Delete form member", //$NON-NLS-1$ //$NON-NLS-2$
            data.descendants.isEmpty()
                ? "Removes it from " + ref.formPath + '.' //$NON-NLS-1$
                : "Removes it and its " + data.descendants.size() //$NON-NLS-1$
                    + " contained member(s) (" + data.describeDescendants() + ")" //$NON-NLS-1$ //$NON-NLS-2$
                    + data.truncationNote() + " from " + ref.formPath + '.', //$NON-NLS-1$
            1 + data.descendants.size(), Collections.singletonList(normFqn));
        return deleteWithConsent(preview, write);
    }

    /**
     * The owned-FORM branch's authorization step, the twin of {@link #gateFormMemberDelete}: the
     * prompt is built from what the form's content ACTUALLY holds, read before this is called. A
     * constant "1" understated every form delete - the user authorized one element while the whole
     * {@code Form.form} (its items, attributes, columns and commands) went with it, which is exactly
     * what issue #331's acceptance criteria ask the prompt to say.
     *
     * @param normFqn the normalized form FQN being deleted
     * @param content what the form's content model holds
     * @param write this branch's mutation
     * @return the mutation's result, or the refusal error
     */
    String gateFormObjectDelete(String normFqn, FormContentSummary content, DeleteWrite write)
    {
        return deleteWithConsent(new ConsentPreview("Delete form", //$NON-NLS-1$
            "Removes the form and its content" //$NON-NLS-1$
                + (content.isEmpty() ? "" : " (" + content.describe() + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + content.truncationNote()
                + " from the owner, clearing any default-form setting " //$NON-NLS-1$
                + "that pointed at it. Call confirm=false first to see the details.", //$NON-NLS-1$
            1 + content.total(), Collections.singletonList(normFqn)), write);
    }

    /**
     * Reads what the form's content model holds, inside a READ transaction, so the consent prompt can
     * name the real blast radius instead of a constant. Best-effort: a form whose editable content
     * cannot be read (no content model at all) answers an EMPTY summary, so the delete still proceeds
     * with a prompt that names the form alone - degrading the wording, never the operation.
     *
     * @param project the owning EDT project
     * @param mdForm the resolved MD form
     * @return what the content holds; empty when it could not be read
     */
    private static FormContentSummary readFormObjectContent(IProject project, MdObject mdForm)
    {
        // Resolving the BM services is NOT best-effort: when they are unavailable the delete cannot
        // happen either, so that is a DETERMINISTIC refusal and it must reach the caller before the
        // consent gate. Swallowing it here asked the user to authorize a delete that would then fail
        // below the authorization point with the very error the prompt had hidden (issue #295 review).
        FormElementWriter.FormEditContext fctx = FormElementWriter.editContextFor(project, mdForm);
        try
        {
            return FormElementWriter.readEditableForm(fctx, "DeleteFormContentPreview", //$NON-NLS-1$
                (formModel, tx) -> summarizeFormContent(formModel));
        }
        catch (Exception e) // NOSONAR only the CONTENT read degrades - see above
        {
            // A form with no editable content model still deletes; only the prompt's wording degrades.
            Activator.logWarning("Could not read the form content for the delete prompt: " //$NON-NLS-1$
                + unwrapCauseMessage(e));
            return new FormContentSummary();
        }
    }

    /**
     * Test seam for {@link #summarizeFormContent}: the same summary feeds BOTH the consent prompt's
     * counts and the {@code confirm=false} preview's item list, so what it collects is asserted
     * directly.
     *
     * @param formModel the form content model
     * @return the summary
     */
    static FormContentSummary summarizeFormContentForTest(EObject formModel)
    {
        return summarizeFormContent(formModel);
    }

    /**
     * Everything a whole-form delete removes, read with {@link #collectRemovedMembers} - the SAME
     * containment walk the member delete uses, for the same reason: the radius of
     * {@code EcoreUtil.remove} is the containment closure, and any list of features to visit is a
     * list that will fall behind it.
     *
     * @param formModel the tx-bound form model
     * @return the summary
     */
    private static FormContentSummary summarizeFormContent(EObject formModel)
    {
        // THE SAME containment walk the member delete uses. Counting by feature name here - the items
        // tree, `attributes`, their `columns`, `formCommands` - understated a whole-form delete in
        // exactly the way it understated a member delete: EcoreUtil.remove also takes the named
        // non-FormItem containments (the form's own `handlers`, every element's `handlers`, a
        // command's `action`), and none of them was counted. Adding those three features would have
        // left the next one to be found the same way (issue #295 review).
        FormContentSummary summary = new FormContentSummary();
        summary.truncated = collectRemovedMembers(formModel, summary.elements);
        return summary;
    }

    /**
     * Reads what a form delete would remove, inside a READ transaction: the target's type and, for a
     * non-handler, every contained descendant (items subtree AND attribute columns). Shared by the
     * {@code confirm=false} preview and by the consent prompt, so the dialog lists exactly what the
     * preview promised - and so a typo answers "not found" without ever raising a destructive dialog
     * (issue #295 review).
     *
     * @param fctx the resolved form edit context
     * @param ref the parsed form-member ref
     * @param handler whether the FQN addresses an event handler
     * @return the preview data; {@code found} is false when the target does not exist
     */
    private FormDeletePreview readFormDeletePreview(FormElementWriter.FormEditContext fctx,
        FormElementWriter.FormMemberRef ref, boolean handler, String normFqn, Version version)
    {
        return FormElementWriter.readEditableForm(fctx, "DeleteFormMemberPreview", //$NON-NLS-1$
            (formModel, tx) ->
            {
                EObject target = resolveFormTarget(formModel, ref, handler);
                if (target == null)
                {
                    FormDeletePreview miss = new FormDeletePreview(); // found stays false
                    // The advice must be read HERE: the model is tx-bound and must not escape.
                    miss.kindAdvice = formTargetAdvice(formModel, ref, handler, normFqn, version);
                    return miss;
                }
                FormDeletePreview d = new FormDeletePreview();
                d.found = true;
                d.type = target.eClass().getName();
                if (!handler)
                {
                    d.truncated = collectRemovedMembers(target, d.descendants);
                }
                return d;
            });
    }

    private String buildFormDeletePreview(FormElementWriter.FormEditContext fctx, String normFqn,
        FormElementWriter.FormMemberRef ref, boolean handler, Version version)
    {
        FormDeletePreview data = readFormDeletePreview(fctx, ref, handler, normFqn, version);

        if (!data.found)
        {
            return formMemberNotFound(ref, handler, data.kindAdvice);
        }

        List<Map<String, Object>> removed = new ArrayList<>();
        Map<String, Object> head = new java.util.LinkedHashMap<>();
        head.put("name", ref.name); //$NON-NLS-1$
        head.put("type", data.type); //$NON-NLS-1$
        removed.add(head);
        removed.addAll(data.descendants);

        String memberWord = handler ? KEY_HANDLER : KEY_MEMBER;
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_PREVIEW)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_REFACTORING_TITLE, "Delete form " + memberWord + " " + ref.name) //$NON-NLS-1$ //$NON-NLS-2$
            .put(KEY_ITEMS, removed)
            .put(KEY_BLOCKING, false);
        return putBlockingReferences(result, Collections.emptyList())
            .put(McpKeys.MESSAGE, "Preview: deleting '" + ref.name + "' (" + data.type + ") from " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ref.formPath + " would remove " //$NON-NLS-1$
                + (data.descendants.isEmpty()
                    ? "the " + memberWord + " itself." //$NON-NLS-1$ //$NON-NLS-2$
                    : "it and its " + data.descendants.size() + " contained member(s) (" //$NON-NLS-1$ //$NON-NLS-2$
                        + data.describeDescendants() + ")" + data.truncationNote() + ".") //$NON-NLS-1$ //$NON-NLS-2$
                + " Cross-references to it (a field's dataPath, a button's command) are NOT rewritten - " //$NON-NLS-1$
                + "re-check with get_metadata_details afterwards. Call confirm=true " //$NON-NLS-1$
                + "to apply.") //$NON-NLS-1$
            .toJson();
    }

    /** Delete inside a WRITE transaction: EcoreUtil.remove the target, then export the content form. */
    private String performFormDelete(FormElementWriter.FormEditContext fctx, String normFqn,
        FormElementWriter.FormMemberRef ref, boolean handler, Version version)
    {
        final String[] capturedType = new String[1];
        boolean persisted = FormElementWriter.writeEditableForm(fctx, "DeleteFormMember", //$NON-NLS-1$
            (formModel, tx) ->
            {
                EObject target = resolveFormTarget(formModel, ref, handler);
                if (target == null)
                {
                    // Thrown (not flagged): rolls the unchanged tx back and skips the export.
                    throw new FormValidationException(formMemberNotFound(ref, handler,
                        formTargetAdvice(formModel, ref, handler, normFqn, version)));
                }
                capturedType[0] = target.eClass().getName();
                // items is containment, so removing a Group/Table cascades its contained subtree.
                EcoreUtil.remove(target);
            });

        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_EXECUTED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, "Deleted form " + (handler ? KEY_HANDLER : KEY_MEMBER) + " '" + ref.name //$NON-NLS-1$ //$NON-NLS-2$
                + "' (" + capturedType[0] + ") from " + ref.formPath //$NON-NLS-1$ //$NON-NLS-2$
                + (persisted ? " and persisted to disk." //$NON-NLS-1$
                    : " (in-memory only; on-disk write did not complete - re-check before relying on " //$NON-NLS-1$
                        + "it).")) //$NON-NLS-1$
            .toJson();
    }

    // ==================== FORM object (owned BasicForm, symmetric with create) ====================

    /**
     * Deletes an OWNED form OBJECT addressed by a 4-part form FQN ({@code Type.Object.Form.FormName}) -
     * the symmetric counterpart of {@code create_metadata}'s {@link FormElementWriter#createForm}. An
     * owned form is not a top object (it lives on its owner's {@code forms} collection), so the
     * md-refactoring service cannot see it; it is removed directly by re-fetching the owner inside a
     * write transaction, detaching the content {@code Form} top object (the store created at attach), and
     * removing the {@code BasicForm} from the {@code forms} collection while clearing any default-form
     * reference the owner held to it (so no dangling {@code defaultObjectForm} / {@code defaultListForm}
     * ref is left behind). Two-phase like the rest of the tool: {@code confirm=false} previews (no
     * mutation), {@code confirm=true} removes it and force-exports the owner {@code .mdo}.
     */
    private String deleteFormObject(ProjectContext ctx, String normFqn,
        FormElementWriter.FormObjectRef ref, boolean confirm)
    {
        IProject project = ctx.project;

        // Reuse create_metadata's owner + owned-form resolution so create/delete address the SAME object. The
        // resolver expects the 'forms' shape: Type.Object.forms.FormName (FormElementWriter owns it).
        String formPath = FormElementWriter.formPathOf(ref.ownerType, ref.ownerName, ref.formName);
        MdObject mdForm = FormStructureReader.resolveMdForm(ctx.scope, formPath);
        if (mdForm == null)
        {
            return formObjectNotFoundError(ctx.scope, ref);
        }
        if (!(mdForm instanceof IBmObject))
        {
            return ToolResult.error("Form is not a BM object").toJson(); //$NON-NLS-1$
        }

        if (!confirm)
        {
            // The SAME content read the consent prompt uses, so the two phases cannot disagree: the
            // prompt counted the content and told the caller to run confirm=false for the details,
            // while this branch still answered with the BasicForm alone (issue #295 review).
            FormContentSummary content = readFormObjectContent(project, mdForm);
            List<Map<String, Object>> removed = new ArrayList<>();
            removed.add(formItem(ref.formName, mdForm.eClass().getName()));
            removed.addAll(content.elements);
            // blocking is hardcoded false: an owned form is removed by cascade (not through the
            // md-refactoring service), so unlike top-object previews NO incoming-reference scan
            // runs here — the message says so to keep the preview honest (deep scan is follow-up).
            ToolResult preview = ToolResult.success()
                .put(McpKeys.ACTION, VAL_PREVIEW)
                .put("fqn", normFqn) //$NON-NLS-1$
                .put(KEY_REFACTORING_TITLE, "Delete form " + ref.formName) //$NON-NLS-1$
                .put(KEY_ITEMS, removed)
                .put(KEY_BLOCKING, false);
            return putBlockingReferences(preview, Collections.emptyList())
                .put(McpKeys.MESSAGE, "Preview: deleting form '" + ref.formName + "' from " + ref.ownerFqn() //$NON-NLS-1$ //$NON-NLS-2$
                    + " would remove the form and its content Form.form" //$NON-NLS-1$
                    + (content.isEmpty() ? "" : " (" + content.describe() + ", listed above)") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + content.truncationNote()
                    + ". Cross-references to it " //$NON-NLS-1$
                    + "(a default-form setting) are cleared on the owner. Note: incoming references " //$NON-NLS-1$
                    + "from OTHER top objects (e.g. BSL code opening this form by name) are NOT " //$NON-NLS-1$
                    + "checked for owned forms — verify with find_references if unsure. " //$NON-NLS-1$
                    + "Call confirm=true to apply.") //$NON-NLS-1$
                .toJson();
        }

        // The authorization point: the whole mutation below is the callback, so nothing this branch
        // writes can run without ALLOW - the guarantee is structural, not a matter of statement order
        // (issue #331 review). The form is resolved and its content is READ first, so the prompt names
        // what is really removed; this is the LAST check before the write, outside any transaction.
        return gateFormObjectDelete(normFqn, readFormObjectContent(project, mdForm),
            () -> performFormObjectDelete(project, normFqn, ref, mdForm));
    }


    /**
     * Applies the owned-form delete: the BM write transaction, the owner force-export and the physical
     * removal of the form's resource folder, ending in the success payload. Extracted so the WHOLE
     * mutation is the callback {@link #deleteWithConsent} invokes - previously the gate was consulted
     * with an empty callback and the real work sat below it, which left the "nothing is written
     * without ALLOW" guarantee true only by the order of statements (issue #331 review).
     *
     * <p>Call only after consent was granted.</p>
     *
     * @param project the owning EDT project
     * @param normFqn the normalized form FQN being deleted
     * @param ref the parsed form-object ref
     * @param mdForm the resolved MD form
     * @return the tool's JSON result
     */
    private String performFormObjectDelete(IProject project, String normFqn,
        FormElementWriter.FormObjectRef ref, MdObject mdForm)
    {
        // The owner is a top object whose .mdo registers the form; force-export it after the removal so
        // the <forms> entry (and any cleared default-form ref) lands on disk. eContainer() is the owner.
        EObject ownerObj = mdForm.eContainer();
        final String ownerFqn = (ownerObj instanceof IBmObject) ? ((IBmObject)ownerObj).bmGetFqn()
            : ref.ownerFqn();
        // Capture the RESOLVED names BEFORE the delete: the model lookup is case-INsensitive while the
        // workspace folder path is case-sensitive, so the folder cleanup must address the names the
        // model actually carries, not the user-typed FQN segments (which may differ in case).
        String resolvedFormName = mdForm.getName();
        final String formNameOnDisk =
            (resolvedFormName == null || resolvedFormName.isEmpty()) ? ref.formName : resolvedFormName;
        String resolvedOwnerName = (ownerObj instanceof MdObject) ? ((MdObject)ownerObj).getName() : null;
        final String ownerNameOnDisk =
            (resolvedOwnerName == null || resolvedOwnerName.isEmpty()) ? ref.ownerName : resolvedOwnerName;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.editContextFor(project, mdForm);
            FormElementWriter.writeMdForm(fctx, "DeleteFormObject", //$NON-NLS-1$
                DeleteMetadataTool::removeFormObjectInTx);
        }
        catch (Exception e)
        {
            String ready = FormValidationException.jsonOf(e);
            if (ready != null)
            {
                return ready;
            }
            Activator.logError("Error deleting form object", e); //$NON-NLS-1$
            return ToolResult.error("Failed to delete form: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // The form is out of the model at this point; an owner FQN we could not name only costs
        // the submission, not the fact that this project was written in (#408).
        WriteScope.recordWrite(project);
        boolean persisted = ownerFqn != null && !ownerFqn.isEmpty()
            && BmTransactions.forceExportToDisk(project, ownerFqn);

        // The BM-model delete + owner force-export drop the <forms> entry from the owner .mdo, but the
        // form's own resource folder on disk (src/<TypeDir>/<Owner>/Forms/<FormName>/, holding Form.form
        // and any sub-files) is NOT touched by the export - it would survive as an orphan that still
        // resolves the form FQN ("no editable content model") and clutters a fresh checkout / XML import.
        // Remove it physically through the workspace API (best-effort: never fail the delete the model
        // already committed). Only this EXACT form folder is removed, never the parent Forms/ (siblings)
        // or the owner folder. The path is built from the RESOLVED names captured above.
        FolderCleanup folderCleanup =
            deleteFormResourceFolder(project, ref.ownerType, ownerNameOnDisk, formNameOnDisk);

        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_EXECUTED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, "Deleted form '" + ref.formName + "' from " + ref.ownerFqn() //$NON-NLS-1$ //$NON-NLS-2$
                + (persisted ? " and persisted to disk." //$NON-NLS-1$
                    : " (in-memory only; on-disk write did not complete - re-check before relying on " //$NON-NLS-1$
                        + "it).") //$NON-NLS-1$
                + folderCleanupMessage(folderCleanup))
            .toJson();
    }

    /**
     * Builds the "form object not found" error for {@link #deleteFormObject}: distinguishes a missing
     * owner from a missing form (the form lookup failed) for a sharper message. Pure message selection,
     * no mutation.
     */
    private static String formObjectNotFoundError(MetadataScope scope, FormElementWriter.FormObjectRef ref)
    {
        // Distinguish a missing owner from a missing form for a sharper message.
        MdObject owner = scope.findObject(ref.ownerType, ref.ownerName);
        if (owner == null)
        {
            return ToolResult.error("Owner object not found: " + ref.ownerFqn() + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Use get_metadata_objects to list available objects." //$NON-NLS-1$
                + scope.addressingHint(ref.ownerFqn())).toJson();
        }
        return ToolResult.error("Form '" + ref.formName + "' not found on " + ref.ownerFqn() //$NON-NLS-1$ //$NON-NLS-2$
            + ". Use get_metadata_details to list the object's forms.").toJson(); //$NON-NLS-1$
    }

    /**
     * The {@code confirm=true} write-transaction body for {@link #deleteFormObject}: detaches the
     * content {@code Form} top object, clears any default-form reference the owner held to this form,
     * and removes the MD-form from the owner's {@code forms} containment list. Runs inside the BM write
     * transaction supplied by {@link FormElementWriter#writeMdForm}.
     */
    private static void removeFormObjectInTx(EObject txMdForm, IBmTransaction tx)
    {
        EObject owner = txMdForm.eContainer();
        // Detach the content Form top object (the BM store the attach created) before removing the
        // MD-form, so no store-less top object is left orphaned in the namespace.
        EObject content = FormElementWriter.getEditableForm(txMdForm);
        if (content instanceof IBmObject)
        {
            tx.detachTopObject((IBmObject)content);
        }
        // Clear any single-valued default-form reference on the owner that points at this form
        // (defaultObjectForm / defaultListForm / ...), so removing the form leaves no dangling ref.
        if (owner != null)
        {
            clearReferencesTo(owner, txMdForm);
        }
        // Remove the MD-form from the owner's 'forms' containment list.
        EcoreUtil.remove(txMdForm);
    }

    // ==================== PREDEFINED item (a plain EMF containment on the owner) ====================

    /**
     * Deletes a PREDEFINED item addressed by {@code Type.Owner.Predefined.ItemName}. Two-phase like
     * the rest of this tool: {@code confirm=false} previews (a FOLDER's preview reports how many
     * nested items the delete would cascade), {@code confirm=true} removes it from its ACTUAL
     * containing list and force-exports the OWNER's canonical FQN (the predefined content is a plain
     * EMF containment - there is no separate top object to detach, unlike an owned form).
     */
    private String deletePredefinedItem(ProjectContext ctx, String normFqn,
        PredefinedWriter.PredefinedRef ref, boolean confirm, boolean force)
    {
        String ownerTypeErr = PredefinedWriter.unsupportedOwnerTypeError(ref.ownerType);
        if (ownerTypeErr != null)
        {
            return ToolResult.error(ownerTypeErr).toJson();
        }

        MetadataNodeResolver.ResolvedNode ownerResolved =
            MetadataNodeResolver.resolveExistingWithYoFallback(ctx.scope, ref.ownerFqn());
        if (ownerResolved.node == null)
        {
            return ToolResult.error("Owner object not found: " + ref.ownerFqn() + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Use get_metadata_objects to list available objects." //$NON-NLS-1$
                + MetadataNodeResolver.yoNotFoundHint(ref.ownerFqn())).toJson();
        }
        MdObject owner = ownerResolved.node.object;
        if (!(owner instanceof IBmObject))
        {
            return ToolResult.error("Owner object is not a BM object").toJson(); //$NON-NLS-1$
        }

        PredefinedWriter.DeletePreview preview = PredefinedWriter.preview(owner, ref.itemName);
        if (!preview.found)
        {
            return ToolResult.error("Predefined item not found: '" + ref.itemName + "' on " //$NON-NLS-1$ //$NON-NLS-2$
                + ref.ownerFqn() + ". Use get_metadata_details to list the owner's predefined items.") //$NON-NLS-1$
                .toJson();
        }

        // Incoming-reference check (issue #296 P1): a predefined item CAN be referenced elsewhere in
        // the model (e.g. a DynamicList filter, another object's default value referencing this
        // item), so deleting it unconditionally could silently leave a dangling reference. Mirrors
        // the generic-node delete path above (collectRefactoringProblems / force), reusing the SAME
        // back-reference mechanism find_references' MetadataReferenceService uses.
        //
        // FAIL-CLOSED (P1 fix): the scan can fail to run to completion (no BM model/manager, a missing
        // owner/item inside the transaction, or a per-item getBackReferences exception) - that is NOT
        // the same as "genuinely zero references" and must never be silently treated as safe. See
        // PredefinedRefScan#completed.
        PredefinedRefScan refScan = collectPredefinedItemBlockingReferences(ctx.project, (IBmObject)owner, ref);

        if (!confirm)
        {
            return buildPredefinedItemDeletePreview(normFqn, ref, preview, refScan);
        }

        if (predefinedDeleteWouldBlock(refScan, force))
        {
            // Distinct message for the two block reasons: an UNVERIFIED scan vs. actual references.
            String reason = !refScan.completed
                ? "Could not verify incoming references to predefined item '" + ref.itemName + "' on " //$NON-NLS-1$ //$NON-NLS-2$
                    + ref.ownerFqn() + " (the project may still be building or the reference index is " //$NON-NLS-1$
                    + "unavailable). Retry when the project is ready, or pass force=true to delete " //$NON-NLS-1$
                    + "without the reference check." //$NON-NLS-1$
                : "Cannot delete predefined item '" + ref.itemName + "' on " + ref.ownerFqn() //$NON-NLS-1$ //$NON-NLS-2$
                    + ": it is still referenced by " + refScan.refs.size() + " place(s). Remove the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "references first, or call again with force=true to delete anyway (those " //$NON-NLS-1$
                    + "references will be left dangling)."; //$NON-NLS-1$
            ToolResult blocked = ToolResult.error(reason)
                .put(McpKeys.ACTION, "blocked") //$NON-NLS-1$
                .put("fqn", normFqn) //$NON-NLS-1$
                .put(KEY_BLOCKING, true);
            return putBlockingReferences(blocked, refScan.refs).toJson();
        }

        // Destructive-operation consent gate, through the tool's single authorization point: the LAST
        // check before the mutation, mirroring every other branch. delete_metadata is a gated tool, and
        // a FOLDER delete cascades its whole content tree - so an interactive session that requires
        // confirmation must get the dialog here too. On ALLOW the behaviour is unchanged; headless /
        // env-bypass never block. Reached only when the reference check completed with nothing
        // blocking, OR force=true bypasses either an incomplete check or a non-empty blocking set.
        int cascadeTotal = 1 + preview.descendantCount;
        // Cascade wording follows the real containment-descendant count, not isFolder: a
        // ChartOfAccounts parent account (isFolder=false) still cascades its childItems, so the
        // subtitle must report the count. The "(a folder)" label stays gated on isFolder.
        StringBuilder consentSubtitle = new StringBuilder(preview.descendantCount > 0
            ? "This deletes predefined item '" + ref.itemName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                + (preview.isFolder ? " (a folder)" : "") + " and its " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + preview.descendantCount + " nested item(s) from " + ref.ownerFqn() + "." //$NON-NLS-1$ //$NON-NLS-2$
            : "This deletes predefined item '" + ref.itemName + "' from " + ref.ownerFqn() + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!refScan.completed)
        {
            consentSubtitle.append(' ').append("The incoming-reference check did not complete " //$NON-NLS-1$
                + "(force=true bypasses it); any references to this item are UNVERIFIED and may be " //$NON-NLS-1$
                + "left dangling."); //$NON-NLS-1$
        }
        else if (!refScan.refs.isEmpty())
        {
            consentSubtitle.append(' ').append(refScan.refs.size())
                .append(" incoming reference(s) will be left dangling."); //$NON-NLS-1$
        }
        ConsentPreview consentPreview = new ConsentPreview(
            "Delete predefined item", //$NON-NLS-1$
            consentSubtitle.toString(), cascadeTotal, Collections.singletonList(normFqn));
        return deleteWithConsent(consentPreview,
            () -> performPredefinedItemDelete(ctx.project, owner, normFqn, ref, refScan, force));
    }

    /**
     * Preview (no mutation): a {name, type} row for the item itself AND one per cascaded descendant
     * (a folder delete removes its whole content tree - the structured {@code items} must list
     * everything the confirm would remove, like the owned-form preview does), plus a message noting
     * a folder's cascade count AND (issue #296 P1) the incoming-reference count - or, when the scan
     * did NOT run to completion, that the check could not be completed (so a confirm=true may still
     * be blocked) - so a caller sees the block coming before ever calling confirm=true.
     */
    private String buildPredefinedItemDeletePreview(String normFqn, PredefinedWriter.PredefinedRef ref,
        PredefinedWriter.DeletePreview preview, PredefinedRefScan refScan)
    {
        Map<String, Object> head = new java.util.LinkedHashMap<>();
        head.put("name", ref.itemName); //$NON-NLS-1$
        head.put("type", preview.kind); //$NON-NLS-1$
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(head);
        for (String[] descendant : preview.descendants)
        {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", descendant[0]); //$NON-NLS-1$
            row.put("type", descendant[1]); //$NON-NLS-1$
            items.add(row);
        }

        // A cascade is driven by CONTAINMENT descendants, NOT by isFolder: a ChartOfAccounts parent
        // account is NOT a folder yet its childItems DO cascade (isFolder=false, descendantCount>0).
        // The "(a FOLDER)" label stays gated on isFolder so a non-folder cascading owner is not
        // mislabelled, but the cascade wording itself follows the real descendant count.
        boolean cascades = preview.descendantCount > 0;
        // A confirm=true delete WITHOUT force would block when the scan found references OR did not
        // complete - the SAME decision the confirm path makes - so the preview's blocking flag never
        // contradicts what a subsequent confirm actually does.
        boolean wouldBlock = predefinedDeleteWouldBlock(refScan, false);
        boolean hasBlocking = refScan.completed && !refScan.refs.isEmpty();
        StringBuilder message = new StringBuilder(cascades
            ? "Preview: deleting predefined item '" + ref.itemName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                + (preview.isFolder ? " (a FOLDER)" : "") + " from " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ref.ownerFqn() + " would remove it AND its " + preview.descendantCount //$NON-NLS-1$
                + " nested item(s)." //$NON-NLS-1$
            : "Preview: deleting predefined item '" + ref.itemName + "' from " + ref.ownerFqn() //$NON-NLS-1$ //$NON-NLS-2$
                + " would remove the item itself."); //$NON-NLS-1$
        if (!refScan.completed)
        {
            message.append(" The incoming-reference check could NOT be completed (the project may " //$NON-NLS-1$
                + "still be building or the reference index is unavailable): a confirm=true delete may " //$NON-NLS-1$
                + "be BLOCKED unless force=true is also passed."); //$NON-NLS-1$
        }
        else if (hasBlocking)
        {
            message.append(" It is referenced by ").append(refScan.refs.size()) //$NON-NLS-1$
                .append(" place(s) that cannot be auto-cleaned: a confirm=true delete will be BLOCKED " //$NON-NLS-1$
                    + "unless force=true is also passed (force leaves these references dangling)."); //$NON-NLS-1$
        }
        message.append(" Call confirm=true to apply."); //$NON-NLS-1$

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_PREVIEW)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_REFACTORING_TITLE, "Delete predefined item " + ref.itemName) //$NON-NLS-1$
            .put(KEY_ITEMS, items)
            .put(KEY_BLOCKING, wouldBlock);
        return putBlockingReferences(result, refScan.refs)
            .put(McpKeys.MESSAGE, message.toString())
            .toJson();
    }

    /**
     * Delete inside a WRITE transaction: re-fetch the owner, remove the item, export the owner FQN.
     * {@code refScan} / {@code force} are used only to compose the result message (the actual
     * block/force decision already ran in the caller before this is reached).
     */
    private String performPredefinedItemDelete(IProject project, MdObject owner,
        String normFqn, PredefinedWriter.PredefinedRef ref, PredefinedRefScan refScan,
        boolean force)
    {
        BmModelResolver.Resolution modelResolution = BmModelResolver.resolve(project);
        if (!modelResolution.isAvailable())
        {
            return unavailableModelError(modelResolution, "Nothing was deleted."); //$NON-NLS-1$
        }
        IBmModel bmModel = modelResolution.getModel();

        final long ownerBmId = ((IBmObject)owner).bmGetId();
        final String itemName = ref.itemName;
        // Force-export must target the owner's CANONICAL FQN (its own bmGetFqn()), never the
        // caller's spelling; and the cascade count in the result message is re-taken INSIDE the
        // write transaction (the pre-confirm preview may be stale by the time confirm runs).
        final String[] canonicalOwnerFqnHolder = new String[1];
        final PredefinedWriter.DeletePreview[] txPreviewHolder = new PredefinedWriter.DeletePreview[1];

        try
        {
            BmTransactions.<Void>write(bmModel, "DeletePredefinedItem", (tx, pm) -> //$NON-NLS-1$
            {
                EObject txOwner = (EObject)tx.getObjectById(ownerBmId);
                if (txOwner == null)
                {
                    throw new RuntimeException("Owner object not found in transaction"); //$NON-NLS-1$
                }
                canonicalOwnerFqnHolder[0] = ((IBmObject)txOwner).bmGetFqn();
                txPreviewHolder[0] = PredefinedWriter.preview(txOwner, itemName);
                PredefinedWriter.WriteResult result = PredefinedWriter.delete(txOwner, itemName);
                if (result.isError())
                {
                    throw new IllegalStateException(result.error);
                }
                return null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error deleting predefined item", e); //$NON-NLS-1$
            return ToolResult.error("Delete failed: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        boolean persisted = BmTransactions.forceExportToDisk(project, canonicalOwnerFqnHolder[0]);
        PredefinedWriter.DeletePreview txPreview = txPreviewHolder[0];
        // Cascade is driven by the CONTAINMENT descendant count, not isFolder: a ChartOfAccounts
        // parent account (isFolder=false) still cascades its childItems, so the executed-result
        // message must report the nested count too (the "(with its N nested item(s))" clause below is
        // already folder-agnostic, so Catalog/CCT output stays byte-identical - their non-folders have
        // zero containment descendants).
        boolean cascaded = txPreview != null && txPreview.descendantCount > 0;

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_EXECUTED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("forced", force); //$NON-NLS-1$
        StringBuilder message =
            new StringBuilder("Deleted predefined item '" + itemName + "' from " + ref.ownerFqn()); //$NON-NLS-1$ //$NON-NLS-2$
        if (cascaded)
        {
            message.append(" (with its ").append(txPreview.descendantCount).append(" nested item(s))"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        message.append(persisted ? " and persisted to disk." //$NON-NLS-1$
            : " (in-memory only; on-disk write did not complete - re-check before relying on it)."); //$NON-NLS-1$
        if (!refScan.completed)
        {
            message.append(' ').append("The incoming-reference check did not complete before this " //$NON-NLS-1$
                + "delete; any references to this item are UNVERIFIED and may be left dangling."); //$NON-NLS-1$
            if (!refScan.refs.isEmpty())
            {
                putBlockingReferences(result, refScan.refs);
            }
        }
        else if (!refScan.refs.isEmpty())
        {
            message.append(' ').append(refScan.refs.size())
                .append(" incoming reference(s) were left dangling."); //$NON-NLS-1$
            putBlockingReferences(result, refScan.refs);
        }
        return result.put(McpKeys.MESSAGE, message.toString()).toJson();
    }

    /**
     * Result of the predefined-item incoming-reference scan: the collected blocking-reference rows,
     * and whether the scan RAN TO COMPLETION. {@code completed=false} (a partial/failed scan) is NOT
     * the same as "genuinely zero references" - it means the reference state is UNVERIFIED, which
     * fail-closes a non-forced delete (see {@link #predefinedDeleteWouldBlock}). Package-visible so the
     * pure block-decision it feeds is unit-testable.
     */
    static final class PredefinedRefScan
    {
        final List<Map<String, Object>> refs;
        final boolean completed;

        PredefinedRefScan(List<Map<String, Object>> refs, boolean completed)
        {
            this.refs = refs;
            this.completed = completed;
        }
    }

    /**
     * The SINGLE fail-closed decision for a predefined-item delete: whether a {@code confirm=true}
     * delete would be BLOCKED for this reference scan. A non-forced delete blocks when the scan did NOT
     * complete (the reference state is UNVERIFIED - safer to refuse than to delete blind) OR when it
     * completed and found at least one blocking reference. {@code force=true} bypasses both. Both the
     * confirm path and the preview's {@code blocking} flag route through this ONE method, so the
     * behaviour cannot drift between them and a regression that inverts it fails the unit test.
     * Package-visible for testing.
     *
     * @param scan the reference scan result
     * @param force the caller's {@code force} flag
     * @return {@code true} when a non-forced delete must be blocked
     */
    static boolean predefinedDeleteWouldBlock(PredefinedRefScan scan, boolean force)
    {
        if (force)
        {
            return false;
        }
        return !scan.completed || !scan.refs.isEmpty();
    }

    /**
     * Result-size hint passed to {@link MetadataReferenceService#collectReferencesForObjectStrict} for a
     * predefined item's incoming-reference scan - generous enough that a real config never truncates it
     * (the collector's own internal cap is {@code limit * 10} per category), matching find_references'
     * own default ({@code FindReferencesTool}'s {@code limit} parameter default).
     */
    private static final int PREDEFINED_REF_SCAN_LIMIT = 100;

    /**
     * Collects incoming references to the predefined item {@code ref.itemName} on {@code owner} AND -
     * when it has children (a FOLDER, or a ChartOfAccounts parent account) - every descendant it would
     * cascade (issue #296 P1), REUSING the exact same
     * reference-collection engine {@code find_references} uses ({@link
     * MetadataReferenceService#collectReferencesForObjectStrict}, issue #293) rather than a hand-rolled
     * subset of it. This closes two gaps the former hand-rolled scan had: (1) it now ALSO covers BSL
     * code references - the SEPARATE Xtext-indexed mechanism the shared service wires in as its 5th
     * collection step ({@code collectBslReferences}), which a plain {@code IBmEngine.getBackReferences}
     * scan can never see; and (2) it no longer false-positives on the item's OWN owner showing up as a
     * "reference" through the derived predefined-data-source linkage (the EXACT {@code "source"}
     * feature, narrowed - a same-owner reference through any OTHER feature is a real dependency and
     * still blocks) - see {@link #isOwnerSelfReference}. Runs inside its own READ transaction,
     * re-fetching the owner by BM id (like every other transaction in this tool: an EMF object resolved
     * OUTSIDE a transaction is not valid to query INSIDE a different one). De-duplicated by
     * (referencingObject, reference, line).
     * <p>
     * FAILS CLOSED: returns {@link PredefinedRefScan#completed}=false (never throws) when the BM model
     * manager / model is unavailable, the owner/item cannot be re-fetched inside the transaction, a
     * per-item scan throws, the transaction itself throws, OR the BSL code-reference step of the scan
     * for the item OR any descendant did not itself complete ({@link
     * MetadataReferenceService.ReferenceScanResult#complete}={@code false} - an unavailable/throwing
     * Xtext reference index) - the caller must then treat the reference state as UNVERIFIED, not as
     * "zero references" (never silently treated as safe).
     * <p>
     * <b>BSL coverage (confirmed live):</b> a predefined item is exposed to BSL as a member of its
     * owner's manager (e.g. {@code Catalogs.Products.SomeItem}). The Xtext scope provider resolves that
     * usage to the {@code PredefinedItem}'s OWN EMF URI, so {@link
     * MetadataReferenceService#collectReferencesForObjectStrict}'s BSL step (which matches on {@code
     * EcoreUtil.getURI(target)}) DOES find it - verified end-to-end on the 2026.1 stand (a common
     * module reading a predefined item blocks the item's non-forced delete). The e2e suite fails hard
     * if this ever stops holding (a BSL URI-resolution / indexing regression), so this path can be
     * relied on to catch a BSL incoming reference to a predefined item.
     *
     * @param project the owning workspace project
     * @param owner the (already resolved) predefined-item owner
     * @param ref the parsed predefined-item FQN
     * @return the scan outcome (never {@code null}); {@code refs} is the de-duplicated blocking-reference
     *     rows, the SAME shape {@link #describeProblem} builds for the generic-node delete path
     */
    private static PredefinedRefScan collectPredefinedItemBlockingReferences(IProject project,
        IBmObject owner, PredefinedWriter.PredefinedRef ref)
    {
        try
        {
            BmModelResolver.Resolution modelResolution = BmModelResolver.resolve(project);
            if (!modelResolution.isAvailable())
            {
                return new PredefinedRefScan(Collections.emptyList(), false);
            }
            final IBmModel bmModel = modelResolution.getModel();
            final long ownerBmId = owner.bmGetId();
            return BmTransactions.<PredefinedRefScan>read(bmModel, "PredefinedItemBackReferences", //$NON-NLS-1$
                (tx, pm) ->
                {
                    EObject txOwner = (EObject)tx.getObjectById(ownerBmId);
                    if (txOwner == null)
                    {
                        return new PredefinedRefScan(Collections.emptyList(), false);
                    }
                    PredefinedItem item = PredefinedWriter.findByName(txOwner, ref.itemName);
                    if (item == null)
                    {
                        return new PredefinedRefScan(Collections.emptyList(), false);
                    }
                    MetadataReferenceService referenceService = new MetadataReferenceService();
                    List<Map<String, Object>> refs = new ArrayList<>();
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    boolean completed = collectOnePredefinedItemReferences(referenceService, project,
                        bmModel, item, ownerBmId, seen, refs);
                    for (PredefinedItem descendant : PredefinedWriter.descendants(item))
                    {
                        completed = collectOnePredefinedItemReferences(referenceService, project, bmModel,
                            descendant, ownerBmId, seen, refs) && completed;
                    }
                    return new PredefinedRefScan(refs, completed);
                });
        }
        catch (Exception e)
        {
            Activator.logError("Error collecting predefined item back references; the reference check " //$NON-NLS-1$
                + "could not be completed", e); //$NON-NLS-1$
            return new PredefinedRefScan(Collections.emptyList(), false);
        }
    }

    /**
     * Collects (into {@code out}, de-duplicated via {@code seen}) the non-owner-self references to a
     * single predefined item - reusing the SAME metadata+BSL reference-collection engine
     * find_references uses ({@link MetadataReferenceService#collectReferencesForObjectStrict}), which
     * already applies find_references' OWN technical-noise filter (a transient feature; a {@code
     * dbview} / {@code cmi}+{@code deriveddata} package reference - mirrors {@code
     * MetadataReferenceService.ReferenceCollector#isInternalReference}/{@code #isInternalPath}), and ALSO
     * reports whether its BSL code-reference step completed. This method adds ONE further exclusion on
     * top, specific to THIS delete safety check and NOT applied by the shared service (which
     * intentionally shows self-references in its own diagnostic view): a reference whose source object's
     * own top container IS the item's owner AND whose feature is the structural predefined-data-source
     * linkage is dropped - see {@link #isOwnerSelfReference}.
     *
     * @return {@code true} when the scan for THIS item completed without error AND its BSL
     *     code-reference step ({@link MetadataReferenceService.ReferenceScanResult#complete}) itself ran
     *     to completion (issue #293 P1 fix-round: an unavailable/throwing BSL index now fails the scan
     *     CLOSED instead of silently reading as "no BSL references"), {@code false} when either {@link
     *     MetadataReferenceService#collectReferencesForObjectStrict} threw or its BSL step did not
     *     complete (in which case {@code out} may still hold whatever rows a previous item in the
     *     caller's loop already gathered - the caller ANDs this flag across every item/descendant it
     *     scans, per {@link PredefinedRefScan#completed})
     */
    private static boolean collectOnePredefinedItemReferences(MetadataReferenceService referenceService,
        IProject project, IBmModel bmModel, PredefinedItem item, long ownerTopId, java.util.Set<String> seen,
        List<Map<String, Object>> out)
    {
        MetadataReferenceService.ReferenceScanResult scanResult;
        try
        {
            scanResult = referenceService.collectReferencesForObjectStrict(project, bmModel,
                (IBmObject)item, PREDEFINED_REF_SCAN_LIMIT);
        }
        catch (Exception e)
        {
            Activator.logError("Error collecting references for predefined item '" + item.getName() //$NON-NLS-1$
                + "'", e); //$NON-NLS-1$
            return false;
        }
        for (MetadataReferenceService.ReferenceInfo info : scanResult.refs)
        {
            if (isOwnerSelfReference(info, ownerTopId))
            {
                continue;
            }
            Map<String, Object> row = describePredefinedReferenceInfo(item, info);
            String key = row.get("referencingObject") + ":" + row.get("reference") + ":" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + row.get("line"); //$NON-NLS-1$
            if (seen.add(key))
            {
                out.add(row);
            }
        }
        return scanResult.complete;
    }

    /**
     * The structural feature name of the predefined-data-source linkage: a derived-model back-reference
     * to a pristine {@link PredefinedItem} through this EXACT feature always resolves to the item's own
     * owner Catalog/ChartOfCharacteristicTypes - {@link PredefinedItem} itself declares no such feature
     * in {@code MdClass.xcore} (only {@code id}/{@code name}/{@code description}/{@code extension}), so
     * this back-reference is produced by a derived model the platform maintains alongside the raw
     * containment, not by any real dependency. Confirmed live (issue #293 P1): a fresh predefined item
     * with zero real references still surfaces exactly one back-reference whose feature name is this
     * constant and whose resolved source renders as the owner itself.
     */
    private static final String PREDEFINED_DATA_SOURCE_FEATURE = "source"; //$NON-NLS-1$

    /**
     * Whether {@code info} is the STRUCTURAL predefined-data-source self-reference to exclude (issue
     * #293 P1, narrowed): {@code true} ONLY when BOTH (a) {@code info}'s SOURCE object (a metadata
     * reference only - a BSL reference never carries one, see {@link
     * MetadataReferenceService.ReferenceInfo#sourceObject}) belongs to the SAME owner top-object as the
     * predefined item being deleted (walks the source object's container chain up to its OWN top {@code
     * IBmObject} and compares {@code bmGetId()} against {@code ownerTopId}), AND (b) the reference's
     * structural feature is exactly {@link #PREDEFINED_DATA_SOURCE_FEATURE}.
     * <p>
     * The feature check matters: matching on same-owner ALONE was too broad and discarded a REAL
     * same-owner reference - e.g. a Catalog attribute's fill value / {@code ReferenceValue} pointing at
     * a predefined item of the SAME catalog (feature "value"), or a {@code
     * ChartOfCalculationTypesPredefinedItem}'s {@code base}/{@code displaced}/{@code leading} referring
     * to a SIBLING predefined item - both of which must still BLOCK a delete. Only a same-owner
     * reference through the derived predefined-data-source linkage is purely structural (it exists
     * merely because the item lives inside that owner) and never an external dependency.
     * <p>
     * Deliberately NOT applied inside {@code MetadataReferenceService} itself - find_references
     * intentionally shows self-references in its own diagnostic view; this exclusion is specific to the
     * delete safety check. Package-visible for tests.
     *
     * @param info the collected reference (its {@code sourceObject} may be {@code null})
     * @param ownerTopId the {@code bmGetId()} of the predefined item's owner (a top object)
     * @return {@code true} when this reference is the structural predefined-data-source self-reference
     */
    static boolean isOwnerSelfReference(MetadataReferenceService.ReferenceInfo info, long ownerTopId)
    {
        IBmObject source = info.sourceObject;
        if (source == null)
        {
            // A BSL reference (no live source object) - never an owner-self reference: a BSL module is
            // always a DIFFERENT top object from the predefined item's owner.
            return false;
        }
        if (!PREDEFINED_DATA_SOURCE_FEATURE.equals(info.feature))
        {
            // A REAL reference (value / fillValue / type / base / displaced / leading /
            // characteristicType / ...) must never be excluded, even from the same owner top-object.
            return false;
        }
        IBmObject top = findTopContainer(source);
        return top != null && top.bmGetId() == ownerTopId;
    }

    /**
     * Walks {@code object}'s container chain up to (and including) its own top {@link IBmObject} -
     * mirrors {@code MetadataReferenceService.ReferenceCollector#findTopContainer} (private to a
     * different package, so this is a small, deliberate duplicate: the owner-self exclusion belongs in
     * THIS delete path, not the shared find_references service - see {@link #isOwnerSelfReference}).
     * Guarded by an IDENTITY visited-set (not a depth cap): a genuine {@code eContainer()} CYCLE
     * terminates and returns {@code null}, while every finite chain reaches its real top. Returns
     * {@code null} on a top-less / cyclic chain - NOT the last reached non-top object:
     * {@link #isOwnerSelfReference} then treats it as "not owner-self" and KEEPS the reference (a
     * delete over-blocks rather than deleting a possibly referenced item - the fail-safe direction).
     * The ONLY non-null return is a real top object.
     */
    private static IBmObject findTopContainer(IBmObject object)
    {
        if (object == null)
        {
            return null;
        }
        if (object.bmIsTop())
        {
            return object;
        }
        java.util.Set<EObject> visited =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        EObject current = (EObject)object;
        while (current != null && visited.add(current))
        {
            if (current instanceof IBmObject && ((IBmObject)current).bmIsTop())
            {
                return (IBmObject)current;
            }
            current = current.eContainer();
        }
        return null;
    }

    /**
     * Converts one {@link MetadataReferenceService.ReferenceInfo} (from {@link
     * MetadataReferenceService#collectReferencesForObjectStrict}) into this tool's block-row map shape - the
     * SAME {@code problemType} / {@code referencingObject} / {@code reference} / {@code targetObject}
     * fields {@link #describeProblem} builds for the generic-node delete path - so the wire contract
     * ({@code blockingReferences}) is unchanged regardless of which collector produced the row.
     * {@code problemType} carries the reference's CATEGORY (e.g. "BSL modules", "Documents", "Common
     * modules") - more specific than the single "PredefinedItemReference" constant the former
     * hand-rolled collector used, since the shared service already classifies every reference it finds.
     * A BSL reference additionally carries its {@code line} number (a metadata reference has none - it
     * carries a {@code reference} feature name instead). Package-visible for tests.
     */
    static Map<String, Object> describePredefinedReferenceInfo(PredefinedItem item,
        MetadataReferenceService.ReferenceInfo info)
    {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("problemType", info.category != null ? info.category : "Reference"); //$NON-NLS-1$ //$NON-NLS-2$
        if (info.sourcePath != null && !info.sourcePath.isEmpty())
        {
            map.put("referencingObject", info.sourcePath); //$NON-NLS-1$
        }
        if (info.isBslReference)
        {
            map.put("reference", "BSL code"); //$NON-NLS-1$ //$NON-NLS-2$
            map.put("line", info.line); //$NON-NLS-1$
        }
        else if (info.feature != null && !info.feature.isEmpty())
        {
            map.put("reference", info.feature); //$NON-NLS-1$
        }
        map.put("targetObject", item.getName()); //$NON-NLS-1$
        return map;
    }

    /** Outcome of the orphan form-folder cleanup - never conflate "not found" with "removed". */
    private enum FolderCleanup
    {
        /** The folder existed and was deleted. */
        REMOVED,
        /** No folder at the resolved path (nothing was removed). */
        NOT_FOUND,
        /** The path could not be resolved or the delete attempt failed. */
        FAILED
    }

    /** The message fragment describing the folder-cleanup outcome (leading space included). */
    private static String folderCleanupMessage(FolderCleanup cleanup)
    {
        switch (cleanup)
        {
        case REMOVED:
            return " The form resource folder was removed from disk."; //$NON-NLS-1$
        case NOT_FOUND:
            return " The form resource folder was not found on disk (nothing was removed)."; //$NON-NLS-1$
        case FAILED:
        default:
            return " (the form resource folder could not be removed - check it manually)."; //$NON-NLS-1$
        }
    }

    /**
     * Physically removes an owned form's resource folder
     * ({@code src/<TypeDir>/<Owner>/Forms/<FormName>/}, containing {@code Form.form} and any sub-files)
     * through the Eclipse workspace API so the workspace stays in sync. The path is built from the
     * RESOLVED owner / form names (the names the model actually carries), NOT the user-typed FQN
     * segments: the model lookup is case-insensitive while the workspace path is case-sensitive, so a
     * case-variant FQN would otherwise miss the real folder and leave the orphan behind. Best-effort: a
     * delete failure is logged but never propagated - the BM-model delete already committed, so the
     * orphan-folder cleanup must not turn a successful delete into an error. A folder that does not
     * exist is reported as {@link FolderCleanup#NOT_FOUND}, never claimed as removed. Only the EXACT
     * {@code Forms/<FormName>} folder is targeted, never the parent {@code Forms/} directory (which may
     * hold sibling forms) or the owner folder.
     *
     * @param project the owning workspace project
     * @param ownerType the owner metadata TYPE token (English or Russian, as supplied)
     * @param resolvedOwnerName the owner object Name AS RESOLVED on the model
     * @param resolvedFormName the form Name AS RESOLVED on the model
     * @return the cleanup outcome (removed / not found on disk / failed)
     */
    private static FolderCleanup deleteFormResourceFolder(IProject project, String ownerType,
        String resolvedOwnerName, String resolvedFormName)
    {
        String folderRel = formResourceFolderPath(ownerType, resolvedOwnerName, resolvedFormName);
        if (folderRel == null)
        {
            Activator.logError("Could not resolve the form resource folder for " + ownerType + "." //$NON-NLS-1$ //$NON-NLS-2$
                + resolvedOwnerName + ".Form." + resolvedFormName + "; leaving any on-disk Forms/" //$NON-NLS-1$ //$NON-NLS-2$
                + resolvedFormName + " folder in place.", null); //$NON-NLS-1$
            return FolderCleanup.FAILED;
        }
        try
        {
            IFolder folder = project.getFolder(new Path(folderRel));
            if (!folder.exists())
            {
                // Nothing on disk at the resolved path (e.g. the form had no rendered content yet).
                // Reported as NOT_FOUND - never claimed as a removal.
                return FolderCleanup.NOT_FOUND;
            }
            // delete(true, monitor): force-delete the folder and its contents, keeping the workspace
            // resource tree in sync with disk. DEPTH is implicitly infinite for a container.
            folder.delete(true, new NullProgressMonitor());
            return FolderCleanup.REMOVED;
        }
        catch (Exception e)
        {
            Activator.logError("Failed to remove the form resource folder " + folderRel //$NON-NLS-1$
                + " (the model delete already succeeded; remove it manually if it persists).", e); //$NON-NLS-1$
            return FolderCleanup.FAILED;
        }
    }

    /**
     * The project-relative resource folder of an owned form
     * ({@code src/<TypeDir>/<Owner>/Forms/<FormName>}), built from the RESOLVED owner / form names via
     * the shared {@link MetadataPathResolver} mapping (same disk layout create_metadata writes), or
     * {@code null} when the type token is unknown. Pure; package-visible for tests.
     */
    static String formResourceFolderPath(String ownerType, String resolvedOwnerName,
        String resolvedFormName)
    {
        return MetadataPathResolver.resolveFormFolderPath(
            FormElementWriter.formPathOf(ownerType, resolvedOwnerName, resolvedFormName));
    }

    /** A {name, type} preview entry for the form object being removed. */
    private static Map<String, Object> formItem(String name, String type)
    {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("name", name); //$NON-NLS-1$
        entry.put("type", type); //$NON-NLS-1$
        return entry;
    }

    /**
     * Nulls out every single-valued (non-containment) reference on {@code holder} whose value is
     * {@code target}. For a form owner these are the {@code defaultObjectForm} / {@code defaultListForm}
     * / {@code defaultChoiceForm} / ... settings - all declared on the direct owner pointing at one of
     * its own {@code BasicForm}s - so checking the owner's own features is sufficient to avoid a dangling
     * reference once the form is removed. Containment / many-valued references (the {@code forms} list
     * itself) are left to {@link EcoreUtil#remove}.
     */
    private static void clearReferencesTo(EObject holder, EObject target)
    {
        for (EReference reference : holder.eClass().getEAllReferences())
        {
            if (reference.isContainment() || reference.isMany() || !reference.isChangeable())
            {
                continue;
            }
            if (holder.eGet(reference) == target)
            {
                holder.eUnset(reference);
            }
        }
    }

    // ==================== XDTO package members (cross-model hop, issue #183 stream 1) ====================

    /**
     * Deletes an XDTO PACKAGE MEMBER (an ObjectType or a Property, package-global or nested in an
     * ObjectType) addressed by {@code ref}. The md-refactoring service is mdclass-only, so the member is
     * removed directly (EMF list removal - {@code ObjectType.getProperties()} is containment, so
     * removing an ObjectType cascades its own properties). Two-phase like the rest of the tool:
     * {@code confirm=false} previews (a rolled-back read, since the package's content is a lazy
     * {@code @ExternalProperty}), {@code confirm=true} removes it (behind the SAME
     * {@link DestructiveConsentGate} the generic mdclass delete path uses) and force-exports the owning
     * package.
     */
    private String deleteXdtoMember(ProjectContext ctx, String normFqn, XdtoWriter.MemberRef ref,
        boolean confirm)
    {
        MetadataNodeResolver.MetadataNode pkgNode =
            MetadataNodeResolver.resolveExistingWithYoFallback(ctx.scope, ref.packageFqn).node;
        if (pkgNode == null || !(pkgNode.object instanceof XDTOPackage)
            || !(pkgNode.object instanceof IBmObject))
        {
            return ToolResult.error("XDTOPackage not found: " + ref.packageFqn //$NON-NLS-1$
                + ". Use get_metadata_objects to find an FQN.").toJson(); //$NON-NLS-1$
        }
        // The generator is needed only on the confirm=true (write) path - to derive the content's own
        // export FQN from the OWNER (never via bmGetFqn() on the content itself; see
        // XdtoWriter.resolvePackageContent's javadoc for why that throws BmAssertionException even on
        // content that looks "attached").
        ITopObjectFqnGenerator fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();
        if (confirm && fqnGenerator == null)
        {
            return ToolResult.error("ITopObjectFqnGenerator not available").toJson(); //$NON-NLS-1$
        }
        BmModelResolver.Resolution modelResolution = BmModelResolver.resolve(ctx.project);
        if (!modelResolution.isAvailable())
        {
            return unavailableModelError(modelResolution, "Nothing was deleted."); //$NON-NLS-1$
        }
        IBmModel bmModel = modelResolution.getModel();
        final long pkgBmId = ((IBmObject)pkgNode.object).bmGetId();
        // The RESOLVED package's canonical FQN: with the yo fallback the caller-typed spelling may
        // differ from the stored name, and force-export must target the stored top object.
        final String pkgExportFqn = "XDTOPackage." + ((XDTOPackage)pkgNode.object).getName(); //$NON-NLS-1$
        return confirm
            ? performXdtoMemberDelete(ctx, normFqn, ref, bmModel, pkgBmId, fqnGenerator, pkgExportFqn)
            : buildXdtoMemberDeletePreview(normFqn, ref, bmModel, pkgBmId);
    }

    /**
     * What a pre-write lookup of an XDTO member found. Two OUTCOMES, not one, because the two callers
     * owe the caller different errors: the confirm path must be able to report the package-level
     * failure with the SAME message the write transaction would have produced, instead of collapsing
     * it into "member not found" (issue #331 review).
     *
     * <p>Package-visible so a unit test can hand each of the three states to
     * {@link #gateXdtoMemberDelete} and check what the caller is told - and, above all, that the two
     * miss states are told it without a consent prompt ever being raised.</p>
     */
    static final class XdtoLookup
    {
        /** Whether the owning package resolved inside the transaction at all. */
        final boolean packageResolved;

        /** {eClassName} of the member, or {@code null} when it is not there. */
        final String[] member;

        XdtoLookup(boolean packageResolved, String[] member)
        {
            this.packageResolved = packageResolved;
            this.member = member;
        }
    }

    /**
     * Locates the target member inside a rolled-back (read-with-materialize) transaction - the
     * package's content is a lazy {@code @ExternalProperty}, so even a pure read has to materialize it.
     * Shared by the preview and the confirm path so both ask the model the same question, and so the
     * confirm path can answer "not found" BEFORE the consent gate (issue #331). It is a separate
     * transaction from the write, so it is a pre-check and not a guarantee: the write re-locates the
     * member and reports its own error if it went in between.
     *
     * @param bmModel the project's BM model
     * @param pkgBmId the owning XDTO package's BM id
     * @param ref the parsed member ref
     * @return what the lookup found; never {@code null}
     */
    private static XdtoLookup locateXdtoMemberInModel(IBmModel bmModel, long pkgBmId,
        XdtoWriter.MemberRef ref)
    {
        return BmTransactions.executeAndRollback(bmModel, "DeleteXdtoMemberLookup", (tx, pm) -> //$NON-NLS-1$
        {
            Object inTx = tx.getObjectById(pkgBmId);
            if (!(inTx instanceof XDTOPackage))
            {
                return new XdtoLookup(false, null);
            }
            return new XdtoLookup(true, locateXdtoMember(((XDTOPackage)inTx).getPackage(), ref));
        });
    }

    /** Preview inside a rolled-back (read-with-materialize) transaction: locates the target, no mutation. */
    private String buildXdtoMemberDeletePreview(String normFqn, XdtoWriter.MemberRef ref, IBmModel bmModel,
        long pkgBmId)
    {
        // The preview reports both misses as "not found" exactly as it did before the lookup gained a
        // second outcome: a preview cannot mutate, so the package-level distinction buys it nothing.
        String[] found = locateXdtoMemberInModel(bmModel, pkgBmId, ref).member;
        if (found == null)
        {
            return xdtoMemberNotFoundError(ref);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(formItem(ref.memberName(), found[0]));

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_PREVIEW)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_REFACTORING_TITLE, "Delete XDTO member " + ref.memberName()) //$NON-NLS-1$
            .put(KEY_ITEMS, items)
            .put(KEY_BLOCKING, false);
        return putBlockingReferences(result, Collections.emptyList())
            .put(McpKeys.MESSAGE, "Preview: deleting '" + ref.memberName() + "' (" + found[0] + ") from " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ref.packageFqn
                + (ref.kind == XdtoWriter.Kind.OBJECT_TYPE ? " would remove it and all its own properties." //$NON-NLS-1$
                    : ".") //$NON-NLS-1$
                + " Cross-references to it (a Property whose type/ref points at this ObjectType) are " //$NON-NLS-1$
                + "NOT rewritten - re-check with get_metadata_details afterwards. Call confirm=true to " //$NON-NLS-1$
                + "apply.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Resolves the target, then asks the gate, then writes: the ORDER is the fix, not an accident of
     * layout. This branch used to ask FIRST and look the member up only inside the write transaction,
     * so a typo in the member name raised a destructive prompt at a human and answered "not found"
     * only after it had been dealt with - the ordering defect the form branches had already fixed
     * (issue #331). The lookup is the SAME rolled-back read the preview runs.
     *
     * @param ctx the resolved project/configuration
     * @param normFqn the normalized FQN being deleted
     * @param ref the parsed XDTO member ref
     * @param bmModel the project's BM model
     * @param pkgBmId the owning package's BM id
     * @param fqnGenerator generator for the content's own export FQN
     * @param pkgExportFqn the resolved package's canonical export FQN
     * @return the tool's JSON result
     */
    private String performXdtoMemberDelete(ProjectContext ctx, String normFqn, XdtoWriter.MemberRef ref,
        IBmModel bmModel, long pkgBmId, ITopObjectFqnGenerator fqnGenerator,
        String pkgExportFqn)
    {
        XdtoLookup found;
        try
        {
            found = locateXdtoMemberInModel(bmModel, pkgBmId, ref);
        }
        catch (Exception e)
        {
            // The lookup materializes the package's lazy content, so it can fail exactly the way the
            // write can; mapping it through the SAME helper keeps the error contract identical to
            // the one this branch had when the lookup lived inside the write transaction.
            return xdtoDeleteFailure(normFqn, e);
        }
        return gateXdtoMemberDelete(normFqn, ref, found,
            () -> writeXdtoMemberDelete(ctx, normFqn, ref, bmModel, pkgBmId, fqnGenerator, pkgExportFqn));
    }

    /**
     * The XDTO branch's authorization step, the twin of {@link #gateFormMemberDelete}: turns what the
     * pre-write lookup found into either an error or a consent prompt, and hands the branch's write to
     * {@link #deleteWithConsent}.
     *
     * <p>Package-private and taking BOTH the lookup outcome and the write as parameters so a unit test
     * can drive all three states without an EDT context. That matters more here than anywhere else in
     * this tool: the ORDER (resolve, then ask) is what issue #331 asked for, and an order pinned only
     * by bytecode offsets would stay green if the lookup's RESULT stopped being used - the prompt would
     * come back for a target that is not there. The behavioural pin is
     * {@code DeleteMetadataToolTest#testXdtoBranch...}; the offsets are pinned separately by
     * {@code DeleteMetadataConsentSinglePointRatchetTest}.</p>
     *
     * @param normFqn the normalized FQN being deleted
     * @param ref the parsed XDTO member ref
     * @param found what the pre-write lookup found
     * @param write this branch's mutation
     * @return the mutation's result, the refusal error, or the miss the lookup found
     */
    String gateXdtoMemberDelete(String normFqn, XdtoWriter.MemberRef ref, XdtoLookup found, DeleteWrite write)
    {
        // Both misses answer with the error the WRITE transaction would have produced for them, so
        // moving the lookup in front of the gate changes when the caller is told, never what - and
        // neither of them reaches the gate, so a typo never raises a destructive prompt.
        if (!found.packageResolved)
        {
            return xdtoPackageUnresolvedError();
        }
        if (found.member == null)
        {
            return xdtoMemberNotFoundError(ref);
        }
        ConsentPreview preview = new ConsentPreview("Delete metadata node", //$NON-NLS-1$
            "This deletes '" + normFqn + "'" //$NON-NLS-1$ //$NON-NLS-2$
                + (ref.kind == XdtoWriter.Kind.OBJECT_TYPE ? " and all its own properties." : "."), //$NON-NLS-1$ //$NON-NLS-2$
            1, Collections.singletonList(normFqn));
        return deleteWithConsent(preview, write);
    }

    /**
     * The XDTO member delete itself: the write transaction and the dual force-export. Split out of
     * {@link #performXdtoMemberDelete} so the WHOLE mutation is the callback
     * {@link #deleteWithConsent} invokes. The member is re-located inside the transaction (it may have
     * gone while the prompt was open), so the pre-gate lookup above never becomes the only check.
     *
     * <p>Call only after consent was granted.</p>
     *
     * @param ctx the resolved project/configuration
     * @param normFqn the normalized FQN being deleted
     * @param ref the parsed XDTO member ref
     * @param bmModel the project's BM model
     * @param pkgBmId the owning package's BM id
     * @param fqnGenerator generator for the content's own export FQN
     * @param pkgExportFqn the resolved package's canonical export FQN
     * @return the tool's JSON result
     */
    private String writeXdtoMemberDelete(ProjectContext ctx, String normFqn, XdtoWriter.MemberRef ref,
        IBmModel bmModel, long pkgBmId, ITopObjectFqnGenerator fqnGenerator,
        String pkgExportFqn)
    {
        XdtoDeleteResult result;
        try
        {
            result = BmTransactions.<XdtoDeleteResult> write(bmModel, "DeleteXdtoMember", (tx, pm) -> //$NON-NLS-1$
                deleteXdtoMemberInTx(tx, pkgBmId, ref, fqnGenerator));
        }
        catch (Exception e)
        {
            return xdtoDeleteFailure(normFqn, e);
        }

        // DUAL force-export, mirroring create_metadata / modify_metadata exactly: the owning
        // XDTOPackage's FQN (drains the .mdo) AND the content's OWN resource FQN (drains the sibling
        // .xdto) - exporting the package FQN alone would leave the deleted member on disk (a
        // #239-class silent false "persisted").
        List<String> exportFqns = new ArrayList<>();
        exportFqns.add(pkgExportFqn);
        if (!result.contentFqn.equals(pkgExportFqn))
        {
            exportFqns.add(result.contentFqn);
        }
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, exportFqns);
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_EXECUTED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("forced", false) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, "Deleted XDTO member '" + ref.memberName() + "' (" + result.kind //$NON-NLS-1$ //$NON-NLS-2$
                + ") from " + ref.packageFqn //$NON-NLS-1$
                + (persisted ? " and persisted to disk." //$NON-NLS-1$
                    : " (in-memory only; on-disk write did not complete - re-check before relying on " //$NON-NLS-1$
                        + "it).")) //$NON-NLS-1$
            .toJson();
    }

    /**
     * The "package is not there" error of the XDTO delete, in ONE place: the pre-gate lookup and the
     * write transaction both hit this condition and must report it identically, so that moving the
     * lookup in front of the gate (issue #331) changed when the caller hears it, not what.
     *
     * @return the tool's JSON error
     */
    private static String xdtoPackageUnresolvedError()
    {
        return ToolResult.error("The XDTO package could not be resolved inside the transaction.").toJson(); //$NON-NLS-1$
    }

    /**
     * Maps an EXCEPTION from the XDTO member delete to the tool's JSON error: a ready one carried by an
     * {@link XdtoWriteException}, otherwise a logged generic. One helper for both the pre-gate lookup
     * and the write, so a failure that used to surface from inside the write transaction still reaches
     * the caller in the same shape now that the lookup runs before it (issue #331).
     *
     * <p>The generic branch names the TARGET and what to do next, because the platform message alone
     * ("Resource ... could not be loaded") does not tell the caller which delete it belongs to nor how
     * to proceed - CLAUDE.md rule #8. The message itself is kept: it is the only thing that
     * distinguishes a corrupt {@code .xdto} from a stale BM id, the same text the write path has always
     * returned, and the preview (no consent at all) reaches the identical failure - so withholding it
     * here would buy no confidentiality and cost the caller its only diagnosis. The stack trace goes to
     * the workspace error log only.</p>
     *
     * <p>Package-visible so a unit test can pin that shape.</p>
     *
     * @param fqn the normalized FQN the delete was addressed to
     * @param e the failure
     * @return the tool's JSON error
     */
    static String xdtoDeleteFailure(String fqn, Exception e)
    {
        String ready = XdtoWriteException.jsonOf(e);
        if (ready != null)
        {
            return ready;
        }
        Activator.logError("Error deleting XDTO member " + fqn, e); //$NON-NLS-1$
        return ToolResult.error("Delete failed for '" + fqn + "': " + unwrapCauseMessage(e) //$NON-NLS-1$ //$NON-NLS-2$
            + ". Nothing was deleted. Re-read the owning XDTO package with get_metadata_details and " //$NON-NLS-1$
            + "retry; if the package itself does not load, fix it on disk first.").toJson(); //$NON-NLS-1$
    }

    /** The write-transaction result for {@link #writeXdtoMemberDelete}: the removed kind + the content's own export FQN. */
    private static final class XdtoDeleteResult
    {
        final String kind;
        final String contentFqn;

        XdtoDeleteResult(String kind, String contentFqn)
        {
            this.kind = kind;
            this.contentFqn = contentFqn;
        }
    }

    /**
     * The write-transaction body for {@link #writeXdtoMemberDelete}: re-fetches the XDTOPackage,
     * reads its (possibly {@code null} / never-materialized) content directly - no ATTACH needed for a
     * delete of an EXISTING member, since a package that already has a member was necessarily
     * materialized + attached by an earlier create/modify - derives the content's OWN export FQN (for
     * the dual force-export) from the OWNER via the generator, locates the target and removes it.
     * Deriving the content FQN from the owner (never via {@code bmGetFqn()} on the content itself) is
     * REQUIRED, not a style choice: a live-stand regression proved {@code bmGetFqn()} throws
     * {@code BmAssertionException} on this package's content even when it looks fully attached
     * ({@code bmIsTop() == true}) - see {@link XdtoWriter#resolvePackageContent}'s javadoc for the full
     * trail. Throws {@link XdtoWriteException} (a ready JSON error) when the package or the target
     * member cannot be resolved, rolling the whole write back with no partial mutation.
     */
    private static XdtoDeleteResult deleteXdtoMemberInTx(IBmTransaction tx, long pkgBmId,
        XdtoWriter.MemberRef ref, ITopObjectFqnGenerator fqnGenerator)
    {
        Object inTx = tx.getObjectById(pkgBmId);
        if (!(inTx instanceof XDTOPackage))
        {
            throw new XdtoWriteException(xdtoPackageUnresolvedError());
        }
        XDTOPackage txPkg = (XDTOPackage)inTx;
        Package content = txPkg.getPackage();
        if (content == null)
        {
            throw new XdtoWriteException(xdtoMemberNotFoundError(ref));
        }
        // Derived from the OWNER, never from the content (see the method javadoc above) - the ONLY call
        // proven safe regardless of which transaction attached the content.
        String contentFqn =
            fqnGenerator.generateExternalPropertyFqn(txPkg, MdClassPackage.Literals.XDTO_PACKAGE__PACKAGE);
        if (contentFqn == null || contentFqn.isEmpty())
        {
            throw new XdtoWriteException(ToolResult.error("Cannot resolve the on-disk resource for the " //$NON-NLS-1$
                + "XDTO package content; report it with the package FQN '" + ref.packageFqn + "'.") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson());
        }

        if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE)
        {
            ObjectType type = XdtoWriter.findObjectType(content, ref.objectTypeName);
            if (type == null)
            {
                throw new XdtoWriteException(xdtoMemberNotFoundError(ref));
            }
            String kind = type.eClass().getName();
            XdtoWriter.removeObjectType(content, type);
            return new XdtoDeleteResult(kind, contentFqn);
        }
        EList<Property> owner = content.getProperties();
        if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE_PROPERTY)
        {
            ObjectType type = XdtoWriter.findObjectType(content, ref.objectTypeName);
            if (type == null)
            {
                throw new XdtoWriteException(xdtoMemberNotFoundError(ref));
            }
            owner = type.getProperties();
        }
        Property property = XdtoWriter.findProperty(owner, ref.propertyName);
        if (property == null)
        {
            throw new XdtoWriteException(xdtoMemberNotFoundError(ref));
        }
        String kind = property.eClass().getName();
        XdtoWriter.removeProperty(owner, property);
        return new XdtoDeleteResult(kind, contentFqn);
    }

    /**
     * Locates the target member (a pure read, used by the preview): {eClassName}, or {@code null}.
     * Package-visible for tests.
     */
    static String[] locateXdtoMember(Package content, XdtoWriter.MemberRef ref)
    {
        if (content == null)
        {
            return null;
        }
        if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE)
        {
            ObjectType type = XdtoWriter.findObjectType(content, ref.objectTypeName);
            return type == null ? null : new String[] { type.eClass().getName() };
        }
        EList<Property> owner = content.getProperties();
        if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE_PROPERTY)
        {
            ObjectType type = XdtoWriter.findObjectType(content, ref.objectTypeName);
            if (type == null)
            {
                return null;
            }
            owner = type.getProperties();
        }
        Property property = XdtoWriter.findProperty(owner, ref.propertyName);
        return property == null ? null : new String[] { property.eClass().getName() };
    }

    /**
     * The actionable "member not found" error, naming the ObjectType/Property and its owner.
     * Package-visible for tests.
     */
    static String xdtoMemberNotFoundError(XdtoWriter.MemberRef ref)
    {
        if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE)
        {
            return ToolResult.error("ObjectType not found: '" + ref.objectTypeName + "' in package " //$NON-NLS-1$ //$NON-NLS-2$
                + ref.packageFqn + ". Use get_metadata_details on the package FQN to list its object " //$NON-NLS-1$
                + "types.").toJson(); //$NON-NLS-1$
        }
        String owner = ref.kind == XdtoWriter.Kind.OBJECT_TYPE_PROPERTY
            ? ref.packageFqn + ".ObjectType." + ref.objectTypeName //$NON-NLS-1$
            : ref.packageFqn;
        return ToolResult.error("Property not found: '" + ref.propertyName + "' on " + owner //$NON-NLS-1$ //$NON-NLS-2$
            + ". Use get_metadata_details on the package FQN to list its properties.").toJson(); //$NON-NLS-1$
    }

    /**
     * Test seam for {@link #collectRemovedMembers}: the walk decides what a destructive preview
     * promises, so it is verified directly instead of through a live form.
     *
     * @param item the element to descend from
     * @param out receives one {name, type} entry per contained descendant
     */
    static void collectDescendantsForTest(EObject item, List<Map<String, Object>> out)
    {
        collectRemovedMembers(item, out);
    }

    /**
     * Walks what a delete of {@code item} takes with it, appending each removed member as a
     * {name, type} map, so the preview and the consent prompt describe the real blast radius. The item
     * ITSELF is not added.
     *
     * <p>The radius is DERIVED, not listed: {@code EcoreUtil.remove} takes the whole containment
     * subtree, so every containment reference of the object's EClass is followed - many-valued and
     * single-valued alike. Naming the features to follow ({@code items}, {@code columns}, plus the
     * singular containments that hold a {@code FormItem}) left out the two containments that hold
     * something else: an element's {@code handlers} list and a command's {@code action}. Both go with
     * their owner, so deleting a field, a button or a command was authorized and previewed as "one
     * member" while it silently carried off the procedure binding (issue #295 review).</p>
     *
     * <p>What gets REPORTED is likewise a property, not a list: a contained object that carries its
     * own non-empty {@code name} is a member the caller can address and therefore loses (a nested
     * item, an attribute column, an event handler, a command's action handler); one that carries none
     * is a property holder of its owner (a data path, a title, an extInfo, a type description) and is
     * descended THROUGH, not listed - which is how a command's action, an unnamed container, still
     * yields the named {@code CommandHandler} inside it.</p>
     *
     * <p>Only the PERSISTED containments are followed - a derived / transient one is skipped, again
     * by asking EMF rather than by naming classes. It matters on the form ROOT: a content form also
     * contains its DERIVED data (the form-data structure of every attribute, the BSL context with its
     * types, properties, methods, parameters and events, the standard commands, the ChildItems
     * views). None of that is authored, none of it is written to {@code Form.form}, and it is
     * recomputed after any edit - counting it turned a 15-member form into a 450-entry prompt when
     * this walk first replaced the old one. What a delete really costs the caller is what was
     * persisted (found by the live probe of this round).</p>
     *
     * <p>The traversal is an explicit stack, not recursion: a {@code StackOverflowError} is an
     * {@link Error} that no {@code catch (Exception)} above would stop. The
     * {@link FormStructureReader#MAX_NODES} bound counts VISITS, not matches, so a subtree full of
     * unnamed property holders cannot walk unboundedly while this claims a cap.</p>
     *
     * @param root the element (or the form root) to descend from; it is NOT itself added
     * @param out receives one {name, type} entry per removed member, depth-first in metamodel order
     * @return {@code true} when the walk hit its bound and stopped, so {@code out} is a PREFIX
     */
    private static boolean collectRemovedMembers(EObject root, List<Map<String, Object>> out)
    {
        int visits = FormStructureReader.MAX_NODES;
        Deque<EObject> pending = new ArrayDeque<>();
        pushPersistedChildren(root, pending);
        while (!pending.isEmpty() && visits > 0)
        {
            visits--;
            EObject child = pending.pop();
            String name = ownNameOf(child);
            if (name != null)
            {
                out.add(formItem(name, child.eClass().getName()));
            }
            pushPersistedChildren(child, pending);
        }
        // A cut walk is FLAGGED, not padded with a pseudo-element: adding a marker to the list would
        // make it disagree with the count that summarizes the very same entries.
        return !pending.isEmpty();
    }

    /**
     * Pushes {@code parent}'s PERSISTED contained objects so they pop in metamodel order (so the walk
     * above stays depth-first, left to right). Which children count as persisted - and why the
     * derived / transient question is asked before the value is read - is
     * {@link PersistedContents}.
     *
     * @param parent the object whose containments to follow
     * @param pending the traversal stack
     */
    private static void pushPersistedChildren(EObject parent, Deque<EObject> pending)
    {
        List<EObject> children = PersistedContents.of(parent);
        for (int i = children.size() - 1; i >= 0; i--)
        {
            pending.push(children.get(i));
        }
    }

    /**
     * The object's OWN name, asked of its EClass, or {@code null} when it carries none. Deliberately
     * NOT {@link FormStructureReader#nameOf}: that one answers {@code "(unnamed)"} so a renderer never
     * prints a blank cell, which here would turn every property holder into a reported member.
     *
     * @param object the contained object to inspect
     * @return its non-empty {@code name}, or {@code null}
     */
    private static String ownNameOf(EObject object)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
        if (!(feature instanceof EAttribute))
        {
            return null;
        }
        Object value = object.eGet(feature);
        return (value instanceof String && !((String)value).isEmpty()) ? (String)value : null;
    }

    /** Mutable carrier for the form-delete preview read task so tx-bound EObjects never escape. */
    static final class FormDeletePreview
    {
        boolean found;
        String type;
        /** The kind-mismatch advice for a MISS, read inside the transaction (issue #343); never null. */
        String kindAdvice = ""; //$NON-NLS-1$
        final List<Map<String, Object>> descendants = new ArrayList<>();

        /**
         * Whether the walk hit its node bound: {@code descendants} is then a PREFIX of what the
         * delete removes, and the message says so rather than presenting a cut list as complete.
         */
        boolean truncated;

        /**
         * The descendants grouped by their model type, e.g. {@code "2 FormField, 1 EventHandler"} -
         * read off the entries the walk produced, so the prompt cannot name a category the walk does
         * not actually follow (issue #295 review).
         *
         * @return the breakdown, or {@code ""} when nothing is contained
         */
        String describeDescendants()
        {
            return describeByType(descendants);
        }

        /** The "and there is more" note for the message, or {@code ""} when the walk finished. */
        String truncationNote()
        {
            return truncationNoteFor(truncated);
        }
    }

    /**
     * Groups {@code entries} by their {@code type}, e.g. {@code "2 FormField, 1 EventHandler"}. ONE
     * renderer for both delete previews, so the member prompt and the whole-form prompt cannot start
     * describing the same walk differently.
     *
     * @param entries the {name, type} entries a removal walk produced
     * @return the breakdown in first-seen order, or {@code ""} when there are none
     */
    private static String describeByType(List<Map<String, Object>> entries)
    {
        Map<String, Integer> byType = new java.util.LinkedHashMap<>();
        for (Map<String, Object> entry : entries)
        {
            String type = String.valueOf(entry.get("type")); //$NON-NLS-1$
            byType.merge(type, Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + 1));
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : byType.entrySet())
        {
            parts.add(entry.getValue() + " " + entry.getKey()); //$NON-NLS-1$
        }
        return String.join(", ", parts); //$NON-NLS-1$
    }

    /** The shared "the walk was cut" note, so both previews word the same fact the same way. */
    private static String truncationNoteFor(boolean truncated)
    {
        return truncated
            ? " (first " + FormStructureReader.MAX_NODES + " nodes only - the form is larger)" //$NON-NLS-1$ //$NON-NLS-2$
            : ""; //$NON-NLS-1$
    }

    /**
     * What a form's content model holds, counted for the delete prompt so it cannot understate the
     * blast radius (issue #331). A plain counter carrier - no tx-bound EObject escapes the read.
     */
    static final class FormContentSummary
    {
        /**
         * Every named member the containment walk found under the form - the ONE source of both the
         * consent prompt's count and the {@code confirm=false} preview's list, so the dialog cannot
         * promise a number the preview does not itemize.
         *
         * <p>Deliberately no per-category counters any more: they were filled by walking a named list
         * of features ({@code items} / {@code attributes} / {@code columns} / {@code formCommands}),
         * which is exactly what left the form's own {@code handlers}, every element's
         * {@code handlers} and a command's {@code action} uncounted - all removed with the form
         * (issue #295 review). The breakdown is derived from the entries instead.</p>
         */
        final List<Map<String, Object>> elements = new ArrayList<>();

        /**
         * Whether the walk hit its node bound and stopped: the count and the list then describe a
         * PREFIX of what the delete removes, and both phases must say so rather than present a cut
         * list as complete.
         */
        boolean truncated;

        /** The "and there is more" note for the message, or {@code ""} when the walk finished. */
        String truncationNote()
        {
            return truncationNoteFor(truncated);
        }

        /** @return every member the content form carries */
        int total()
        {
            return elements.size();
        }

        /** @return whether the form's content holds nothing (or could not be read) */
        boolean isEmpty()
        {
            return elements.isEmpty();
        }

        /** @return the breakdown by model type, e.g. {@code "4 FormField, 2 FormAttribute"} */
        String describe()
        {
            return describeByType(elements);
        }
    }
}
