/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.ditrix.edt.mcp.server.utils.BoundedJob;
import com.ditrix.edt.mcp.server.utils.BranchInfobaseBinding;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DesignerExtensionBackend;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionErrorProjection;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Request;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Target;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.e1c.g5.dt.applications.IApplication;

/** Lists and manages compiled extensions directly in an application's infobase. */
public class InfobaseExtensionsTool implements IMcpTool
{
    public static final String NAME = "infobase_extensions";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List installed infobase extensions and install, update or uninstall .cfe files. "
            + "DESTRUCTIVE: call once WITHOUT confirm to preview a write, then with confirm=true and expectedHash from the preview's contentHash; "
            + "writes require user consent; update verification requires a changed expectedVersion. "
            + "Full parameters and examples: call get_tool_guide('infobase_extensions').";
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "Configuration project owning the application.", true)
            .stringProperty(McpKeys.APPLICATION_ID, "Exact target ID from get_applications; never inferred.", true)
            .enumProperty(McpKeys.ACTION, "Default list.", "list", "install", "update", "uninstall")
            .stringProperty("extensionName", "Required for writes: exact programmatic extension name.")
            .stringProperty("cfePath", "Required for install/update: absolute path on the EDT host.")
            .booleanProperty("safeMode", "Install defaults true; update preserves the installed value.")
            .booleanProperty("protectionFromDangerousActions", "Install defaults true; update preserves the installed value.")
            .stringProperty("expectedVersion", "Optional exact read-back version; update verification requires a value different from the installed version.")
            .booleanProperty("confirm", "Default false previews; true requests consent and executes the write.")
            .stringProperty("expectedHash", "Required with confirm=true: contentHash from the preview; changed inputs require a fresh preview.")
            .integerProperty("timeoutSeconds", "Operation deadline, 10..600; default 120. A timeout does not imply rollback.")
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the requested operation succeeded.", true)
            .stringProperty(McpKeys.PROJECT_NAME, "Resolved project.")
            .stringProperty(McpKeys.APPLICATION_ID, "Resolved target application ID.")
            .stringProperty(McpKeys.ACTION, "Requested operation.")
            .objectArrayProperty("extensions", "Installed extension records with name, version, active, safeMode and protectionFromDangerousActions.")
            .objectProperty("actualState", "Observed extension state after a write; absent after uninstall.")
            .objectProperty("stateBefore", "Observed extension state before a write.")
            .stringProperty("extensionName", "Exact name selected by the caller.")
            .booleanProperty("requestedSafeMode", "Effective safeMode in the preview.")
            .booleanProperty("requestedProtectionFromDangerousActions", "Effective protection in the preview.")
            .stringProperty("message", "Preview instructions.")
            .integerProperty("count", "Number of installed extensions.")
            .booleanProperty("requiresConfirmation", "True for a write preview.")
            .stringProperty("contentHash", "Preview state hash; pass as expectedHash with confirm=true.")
            .booleanProperty("extensionInstalled", "Actual presence after a completed write.")
            .stringProperty("verification", "verified, mismatched or notVerified.")
            .stringProperty("verificationReason", "Why post-failure read-back was unavailable.")
            .stringProperty("verificationScope", "The observations this verification proves.")
            .booleanProperty("mutationOutcomeUnknown", "A failed or cancelled write may have changed the infobase.")
            .stringProperty("error", "Actionable error when success=false.")
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public boolean connectsToInfobase()
    {
        return true;
    }

    @Override
    public boolean returnsInfobaseData()
    {
        return true;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String required = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, McpKeys.APPLICATION_ID);
        if (required != null)
        {
            return required;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        try
        {
            for (String key : List.of("confirm", "safeMode", "protectionFromDangerousActions"))
            {
                if (params.containsKey(key) && !List.of("true", "false").contains(params.get(key)))
                {
                    return ToolResult.error(key + " must be a JSON boolean true or false; correct the value and retry.").toJson();
                }
            }
            String action = JsonUtils.extractStringArgument(params, McpKeys.ACTION);
            action = action == null ? "list" : action;
            String file = JsonUtils.extractStringArgument(params, "cfePath");
            Request request = new Request(action, JsonUtils.extractStringArgument(params, "extensionName"),
                file == null ? null : Path.of(file),
                params.containsKey("safeMode") ? JsonUtils.extractBooleanArgument(params, "safeMode", true) : null,
                params.containsKey("protectionFromDangerousActions")
                    ? JsonUtils.extractBooleanArgument(params, "protectionFromDangerousActions", true) : null,
                JsonUtils.extractStringArgument(params, "expectedVersion"),
                JsonUtils.extractBooleanArgument(params, "confirm", false),
                JsonUtils.extractStringArgument(params, "expectedHash"));
            String validation = InfobaseExtensionLifecycle.validate(request);
            if (validation != null)
            {
                return ToolResult.error(validation).toJson();
            }
            int timeout = JsonUtils.extractIntArgument(params, "timeoutSeconds", 120);
            if (timeout < 10 || timeout > 600 || (params.containsKey("timeoutSeconds")
                && !Integer.toString(timeout).equals(params.get("timeoutSeconds"))))
            {
                return ToolResult.error("timeoutSeconds must be an integer from 10 to 600; correct it and retry.").toJson();
            }
            ApplicationSupport.ManagerResult resolved = ApplicationSupport.resolveManager(projectName);
            if (resolved.errorJson() != null)
            {
                return resolved.errorJson();
            }
            var read = ApplicationSupport.getApplicationBounded(resolved.manager(), resolved.project(),
                applicationId, ApplicationSupport.LOOKUP_TIMEOUT_MS);
            if (!read.concluded())
            {
                return ToolResult.error(read.deadlineFailure()).toJson();
            }
            var found = read.valueOrRethrow();
            if (found.isEmpty())
            {
                return ToolResult.error("Application '" + applicationId + "' was not found in project '"
                    + projectName + "'; use get_applications to select the target infobase.").toJson();
            }
            IApplication application = found.get();
            AtomicReference<ToolResult> result = new AtomicReference<>();
            AtomicBoolean mutationEntered = new AtomicBoolean();
            AtomicBoolean sessionsChecked = new AtomicBoolean();
            BoundedJob.Result bounded = BoundedJob.run("Infobase extensions: " + applicationId,
                timeout * 1000L, monitor -> {
                    InfobaseAuthDialogSuppressor.markActivityStart();
                    try
                    {
                        var lock = LaunchLifecycleUtils.lockFor(projectName, applicationId);
                        var acquisition = lock.tryAcquire(LaunchLifecycleUtils.SESSIONS_LOCK_TIMEOUT_MS, monitor);
                        if (!acquisition.acquired())
                        {
                            result.set(ToolResult.error(LaunchLifecycleUtils.lockNotAcquiredMessage(acquisition,
                                projectName, applicationId, LaunchLifecycleUtils.SESSIONS_LOCK_TIMEOUT_MS)));
                            return;
                        }
                        try
                        {
                            result.set(DesignerExtensionBackend.withConnection(resolved.project(), application,
                                monitor, backend -> InfobaseExtensionLifecycle.execute(request,
                                    new Target(projectName, applicationId), backend,
                                    preview -> {
                                        ConsentDecision decision = DestructiveConsentGate.getInstance().requireConsent(NAME,
                                            new ConsentPreview(preview.getTitle(), "Application " + applicationId + ": "
                                                + preview.getSubtitle(), preview.getTotalCount(), preview.getTopNames()));
                                        if (decision != ConsentDecision.ALLOW)
                                        {
                                            throw new IllegalStateException(DestructiveConsentGate.consentDeniedMessage(decision, NAME));
                                        }
                                        return true;
                                    },
                                    () -> {
                                        DesignerExtensionBackend.checkCancelled(monitor);
                                        String refusal = BranchInfobaseBinding.refusalOrNull(resolved.project(), application, applicationId);
                                        if (refusal != null)
                                        {
                                            throw new IllegalStateException(refusal);
                                        }
                                        if (InfobaseSessionSupport.appliesTo(application) && sessionsChecked.compareAndSet(false, true))
                                        {
                                            String sessionRefusal = sessionRefusal(InfobaseSessionSupport.listSessions(application));
                                            if (sessionRefusal != null)
                                            {
                                                throw new IllegalStateException(sessionRefusal);
                                            }
                                        }
                                        String invalidFile = InfobaseExtensionLifecycle.validate(request);
                                        if (invalidFile != null)
                                        {
                                            throw new IllegalArgumentException(invalidFile);
                                        }
                                    }, () -> mutationEntered.set(true))));
                        }
                        finally
                        {
                            lock.unlock();
                        }
                    }
                    finally
                    {
                        InfobaseAuthDialogSuppressor.markActivityEnd();
                    }
                });
            if (!bounded.isSuccess() || result.get() == null)
            {
                boolean unknown = mutationEntered.get() || (request.confirm()
                    && BoundedJob.isInconclusive(bounded.getOutcome()));
                String reason = bounded.getFailure() != null ? PlatformFailures.describeWithRootCause(bounded.getFailure())
                    : "operation ended with " + bounded.getOutcome() + " after " + bounded.getElapsedMs() + " ms";
                return ToolResult.error("Infobase extension operation failed: " + reason
                    + ". Check set_infobase_credentials for authentication, infobase_sessions for locks, "
                    + "and the EDT Error Log. Read action=list before retrying. A cancelled operation may still be running.")
                    .put("verification", "notVerified").put("mutationOutcomeUnknown", unknown)
                    .put(McpKeys.APPLICATION_ID, applicationId).toJson();
            }
            String response = result.get().put(McpKeys.PROJECT_NAME, projectName)
                .put(McpKeys.APPLICATION_ID, applicationId).put(McpKeys.ACTION, action).toJson();
            return mutationEntered.get() ? ToolResult.markErrorWithUnknownMutationOutcome(response) : response;
        }
        catch (Exception e)
        {
            return ToolResult.error("Could not manage extensions for application '" + applicationId + "': "
                + PlatformFailures.describeWithRootCause(e)
                + ". Check get_applications and the supplied parameters, then retry.").toJson();
        }
    }

    /** Unreadable is never an empty inventory; Designer kind alone does not identify EDT ownership. */
    static String sessionRefusal(InfobaseSessionSupport.ReadResult sessions)
    {
        if (!sessions.isReadable())
        {
            return "Cannot verify infobase sessions: " + sessions.unreachableReason()
                + ". Inspect infobase_sessions(action='list') and retry after the server is reachable.";
        }
        var blockers = sessions.sessions().stream().filter(s -> !InfobaseSessionsTool.isDesignerSession(s)).toList();
        return blockers.isEmpty() ? null : "Infobase has active non-Designer sessions: "
            + InfobaseSessionErrorProjection.fields(blockers)
            + ". Inspect infobase_sessions and obtain separate consent before terminating sessions, then retry.";
    }
}
