/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.ditrix.edt.mcp.server.protocol.ToolResult;

/** Runtime lifecycle policy, independent of the Designer transport. */
public final class InfobaseExtensionLifecycle
{
    public record Request(String action, String extensionName, Path cfePath, Boolean safeMode,
        Boolean protectionFromDangerousActions, String expectedVersion, boolean confirm)
    {
    }

    public record Extension(String name, String version, boolean active, boolean safeMode,
        boolean protectionFromDangerousActions, String hash)
    {
        public Map<String, Object> details()
        {
            return Map.of("name", name, "version", version, "active", active,
                "safeMode", safeMode, "protectionFromDangerousActions", protectionFromDangerousActions,
                "hash", hash);
        }
    }

    public interface Backend
    {
        List<Extension> read() throws Exception;
        void load(Path file, String name) throws Exception;
        void update(String name) throws Exception;
        void flags(String name, boolean safe, boolean protectedActions, Boolean active) throws Exception;
        void delete(String name) throws Exception;
    }

    @FunctionalInterface
    public interface WriteGuard
    {
        void check() throws Exception;
    }

    private InfobaseExtensionLifecycle()
    {
    }

    /** Refuses unsupported combinations before resolving any application or starting a session. */
    public static String validate(Request request)
    {
        if (!List.of("list", "install", "update", "uninstall").contains(request.action()))
        {
            return "Invalid action '" + request.action() + "'. Allowed values: list, install, update, uninstall.";
        }
        boolean list = "list".equals(request.action());
        boolean file = "install".equals(request.action()) || "update".equals(request.action());
        if (list && (request.extensionName() != null || request.cfePath() != null || request.confirm()
            || request.safeMode() != null || request.protectionFromDangerousActions() != null
            || request.expectedVersion() != null))
        {
            return "action=list accepts only projectName, applicationId and timeoutSeconds; remove write parameters.";
        }
        if (!list && (request.extensionName() == null || request.extensionName().isBlank()))
        {
            return "extensionName is required for " + request.action()
                + "; pass the exact programmatic extension name, never a file-name guess.";
        }
        if (request.extensionName() != null && !request.extensionName().matches("[\\p{L}_][\\p{L}\\p{N}_]*"))
        {
            return "Invalid extensionName '" + request.extensionName() + "'; pass a single programmatic identifier.";
        }
        if (file && request.cfePath() == null)
        {
            return "cfePath is required for " + request.action() + "; pass an absolute path to a readable .cfe file.";
        }
        if (!file && !list && (request.cfePath() != null || request.safeMode() != null
            || request.protectionFromDangerousActions() != null || request.expectedVersion() != null))
        {
            return "action=uninstall accepts no cfePath, expectedVersion or runtime flags; remove those parameters.";
        }
        if (file)
        {
            Path path = request.cfePath();
            if (!path.isAbsolute() || path.getFileName() == null
                || !path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".cfe")
                || !Files.isRegularFile(path) || !Files.isReadable(path))
            {
                return "Invalid cfePath '" + path + "'; pass an absolute path to an existing readable .cfe file.";
            }
            try
            {
                if (Files.size(path) == 0)
                {
                    return "cfePath '" + path + "' is empty; export a valid .cfe file first.";
                }
            }
            catch (java.io.IOException e)
            {
                return "Cannot read cfePath '" + path + "'; check the file permissions and retry.";
            }
        }
        return null;
    }

    public static ToolResult execute(Request request, Backend backend, Predicate<ConsentPreview> consent,
        WriteGuard guard, Runnable mutationEntered) throws Exception
    {
        List<Extension> before = checked(backend.read());
        if ("list".equals(request.action()))
        {
            return ToolResult.success().put("extensions", before.stream().map(Extension::details).toList())
                .put("count", before.size());
        }
        Extension previous = find(before, request.extensionName());
        boolean install = "install".equals(request.action());
        boolean uninstall = "uninstall".equals(request.action());
        if (install && previous != null)
        {
            return ToolResult.error("Extension '" + request.extensionName()
                + "' already exists; use action=update to replace it.");
        }
        if (!install && previous == null)
        {
            return ToolResult.error("Extension '" + request.extensionName()
                + "' is not installed; call action=list and use its exact name, or action=install.");
        }
        boolean safe = request.safeMode() != null ? request.safeMode() : install || previous.safeMode();
        boolean protection = request.protectionFromDangerousActions() != null
            ? request.protectionFromDangerousActions() : install || previous.protectionFromDangerousActions();
        if (!request.confirm())
        {
            return ToolResult.success().put("requiresConfirmation", true).put("action", request.action())
                .put("extensionName", request.extensionName()).put("stateBefore", previous == null ? null : previous.details())
                .put("requestedSafeMode", uninstall ? null : safe)
                .put("requestedProtectionFromDangerousActions", uninstall ? null : protection)
                .put("message", "Preview only. Back up the infobase, then repeat with confirm=true. "
                    + (uninstall ? "Uninstall can permanently delete extension data." : "Loading and restructuring can change infobase data."));
        }
        Path staged = null;
        try
        {
            if (!uninstall)
            {
                staged = Files.createTempFile("edt-mcp-extension-", ".cfe");
                Files.copy(request.cfePath(), staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            ConsentPreview preview = new ConsentPreview("Modify infobase extension",
                request.action() + "; extension data can be changed or permanently deleted."
                    + (uninstall ? "" : " safeMode=" + safe + ", protectionFromDangerousActions=" + protection
                        + ", expectedVersion=" + request.expectedVersion() + ", source=" + request.cfePath())
                    + (previous == null ? "" : ", installedVersion=" + previous.version() + ", active=" + previous.active()),
                1, List.of(request.extensionName()));
            if (!consent.test(preview))
            {
                return ToolResult.error("Extension operation was not approved; nothing was changed. Request consent and retry.");
            }
            // Consent can wait: a GUI change must not turn an install into an overwrite.
            if (!before.equals(checked(backend.read())))
            {
                return ToolResult.error("Installed extensions changed while awaiting consent; request a fresh preview before retrying.");
            }
            guard.check();
            mutationEntered.run();
            try
            {
                if (uninstall)
                {
                    backend.delete(request.extensionName());
                }
                else
                {
                    backend.load(staged, request.extensionName());
                    guard.check();
                    backend.flags(request.extensionName(), safe, protection, previous == null ? null : previous.active());
                    guard.check();
                    backend.update(request.extensionName());
                }
                List<Extension> after = checked(backend.read());
                Extension actual = find(after, request.extensionName());
                boolean matches = uninstall ? actual == null : actual != null && actual.safeMode() == safe
                    && actual.protectionFromDangerousActions() == protection
                    && (previous == null || previous.active() == actual.active())
                    && (request.expectedVersion() == null || request.expectedVersion().equals(actual.version()));
                boolean othersUnchanged = before.stream().filter(e -> !e.name().equals(request.extensionName())).toList()
                    .equals(after.stream().filter(e -> !e.name().equals(request.extensionName())).toList());
                ToolResult result = matches && othersUnchanged ? ToolResult.success()
                    : ToolResult.error("The Designer operation returned, but extension read-back mismatched the request. "
                        + "Inspect action=list and the EDT Error Log before retrying; the infobase may already have changed.");
                return result.put("verification", matches && othersUnchanged ? "verified" : "mismatched")
                    .put("extensionInstalled", actual != null).put("actualState", actual == null ? null : actual.details())
                    .put("stateBefore", previous == null ? null : previous.details())
                    .put("verificationScope", "Presence, runtime flags, preserved active state, other extensions and expectedVersion when supplied; not binary file identity.");
            }
            catch (Exception failure)
            {
                ToolResult failed = ToolResult.errorWithUnknownMutationOutcome("Designer extension operation failed: "
                    + PlatformFailures.describeWithRootCause(failure)
                    + ". Inspect action=list and the EDT Error Log before retrying; the infobase may already have changed.")
                    .put("verification", "notVerified");
                try
                {
                    Extension observed = find(checked(backend.read()), request.extensionName());
                    failed.put("extensionInstalled", observed != null)
                        .put("actualState", observed == null ? null : observed.details());
                }
                catch (Exception readFailure)
                {
                    failed.put("verificationReason", "Post-failure inventory could not be read: "
                        + PlatformFailures.describeWithRootCause(readFailure));
                }
                return failed;
            }
        }
        finally
        {
            if (staged != null)
            {
                try
                {
                    Files.deleteIfExists(staged);
                }
                catch (java.io.IOException e)
                {
                    com.ditrix.edt.mcp.server.Activator.logWarning("Could not remove staged .cfe file: " + staged);
                }
            }
        }
    }

    private static Extension find(List<Extension> states, String name)
    {
        return states.stream().filter(state -> state.name().equals(name)).findFirst().orElse(null);
    }

    private static List<Extension> checked(List<Extension> states)
    {
        if (states == null || states.stream().anyMatch(e -> e == null || e.name() == null || e.name().isBlank()
            || e.version() == null || e.hash() == null)
            || states.stream().map(Extension::name).distinct().count() != states.size())
        {
            throw new IllegalStateException("Designer returned an unreadable extension inventory; inspect the EDT Error Log and retry action=list.");
        }
        return states.stream().sorted(java.util.Comparator.comparing(Extension::name)).toList();
    }
}
