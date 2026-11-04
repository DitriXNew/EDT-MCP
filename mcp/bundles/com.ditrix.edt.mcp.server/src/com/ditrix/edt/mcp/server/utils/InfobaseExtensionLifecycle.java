/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import com.ditrix.edt.mcp.server.ipc.EndpointRegistry;
import com.ditrix.edt.mcp.server.ipc.PrivatePaths;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/** Runtime lifecycle policy, independent of the Designer transport. */
public final class InfobaseExtensionLifecycle
{
    public record Request(String action, String extensionName, Path cfePath, Boolean safeMode,
        Boolean protectionFromDangerousActions, String expectedVersion, boolean confirm, String expectedHash)
    {
    }

    public record Target(String projectName, String applicationId)
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

    @FunctionalInterface
    interface StagingParent
    {
        Path directory() throws java.io.IOException;
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
            || request.expectedVersion() != null || request.expectedHash() != null))
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
        if (request.confirm() && (request.expectedHash() == null || request.expectedHash().isBlank()))
        {
            return "expectedHash is required with confirm=true. Request a fresh preview without confirm and pass its contentHash.";
        }
        if (!request.confirm() && request.expectedHash() != null)
        {
            return "expectedHash is accepted only with confirm=true; omit it to request a fresh preview.";
        }
        return null;
    }

    public static ToolResult execute(Request request, Target target, Backend backend, Predicate<ConsentPreview> consent,
        WriteGuard guard, Runnable mutationEntered) throws Exception
    {
        return execute(request, target, backend, consent, guard, mutationEntered, EndpointRegistry::userDirectory);
    }

    static ToolResult execute(Request request, Target target, Backend backend, Predicate<ConsentPreview> consent,
        WriteGuard guard, Runnable mutationEntered, StagingParent stagingParent) throws Exception
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
        Path stagedDirectory = null;
        Path staged = null;
        try
        {
            if (!uninstall)
            {
                stagedDirectory = PrivatePaths.temporaryDirectory(stagingParent.directory(), "edt-mcp-extension-");
                staged = Files.createTempFile(stagedDirectory, "extension-", ".cfe");
                stageCfe(request.cfePath(), staged);
            }
            String artifactHash = staged == null ? null : sha256(staged);
            String current = previewContent(request, target, before, safe, protection, artifactHash);
            if (!request.confirm())
            {
                return ToolResult.success().put("requiresConfirmation", true).put("action", request.action())
                    .put("extensionName", request.extensionName()).put("stateBefore", previous == null ? null : previous.details())
                    .put("requestedSafeMode", uninstall ? null : safe)
                    .put("requestedProtectionFromDangerousActions", uninstall ? null : protection)
                    .put("contentHash", ContentHash.of(current))
                    .put("message", "Preview only. Back up the infobase, then repeat with confirm=true and expectedHash=contentHash. "
                        + (uninstall ? "Uninstall can permanently delete extension data." : "Loading and restructuring can change infobase data."));
            }
            // Staleness check for an honest agent, not an authentication token.
            if (!ContentHash.matches(current, request.expectedHash()))
            {
                return ToolResult.error("expectedHash does not match the current extension preview. "
                    + "The target, parameters, installed extensions or .cfe bytes changed; nothing was changed. "
                    + "Request a fresh preview without confirm and retry with its up-to-date contentHash.");
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
            guard.check();
            // Consent and admission checks can wait; recheck immediately before mutation.
            if (!before.equals(checked(backend.read())))
            {
                return ToolResult.error("Installed extensions changed while awaiting consent or admission checks; request a fresh preview before retrying.");
            }
            if (staged != null && !artifactHash.equals(sha256(staged)))
            {
                return ToolResult.error("Staged .cfe bytes changed while awaiting consent or admission checks; nothing was changed. "
                    + "Request a fresh preview before retrying.");
            }
            // A local writer can still change bytes between this check and Designer opening the file.
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
                boolean updateEvidence = install || uninstall || request.expectedVersion() != null
                    && !request.expectedVersion().equals(previous.version());
                boolean verified = matches && othersUnchanged && updateEvidence;
                ToolResult result = verified ? ToolResult.success()
                    : matches && othersUnchanged ? ToolResult.error("The Designer operation returned, but the update cannot be verified "
                        + "without an expectedVersion different from the installed version. Inspect action=list before retrying; "
                        + "the infobase may already have changed.")
                    : ToolResult.error("The Designer operation returned, but extension read-back mismatched the request. "
                        + "Inspect action=list and the EDT Error Log before retrying; the infobase may already have changed.");
                return result.put("verification", verified ? "verified" : matches && othersUnchanged ? "notVerified" : "mismatched")
                    .put("extensionInstalled", actual != null).put("actualState", actual == null ? null : actual.details())
                    .put("stateBefore", previous == null ? null : previous.details())
                    .put("verificationScope", "Presence, runtime flags, preserved active state, other extensions and expectedVersion when supplied; "
                        + "updates require an observed transition to expectedVersion; not binary file identity.");
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
            if (stagedDirectory != null)
            {
                try { Files.deleteIfExists(stagedDirectory); }
                catch (java.io.IOException e)
                {
                    com.ditrix.edt.mcp.server.Activator.logWarning("Could not remove staged .cfe directory: " + stagedDirectory);
                }
            }
        }
    }

    static void stageCfe(Path source, Path staged) throws java.io.IOException
    {
        // Preserve the temporary file's identity and permissions instead of replacing it.
        try (var output = Files.newOutputStream(staged, java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING))
        {
            Files.copy(source, output);
        }
    }

    private static String sha256(Path file) throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file))
        {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1)
            {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String previewContent(Request request, Target target, List<Extension> before,
        boolean safe, boolean protection, String artifactHash)
    {
        Map<String, Object> inputs = new TreeMap<>();
        inputs.put("projectName", target.projectName());
        inputs.put("applicationId", target.applicationId());
        inputs.put("action", request.action());
        inputs.put("extensionName", request.extensionName());
        inputs.put("cfePath", request.cfePath() == null ? null : request.cfePath().toAbsolutePath().normalize().toString());
        inputs.put("safeMode", safe);
        inputs.put("protectionFromDangerousActions", protection);
        inputs.put("expectedVersion", request.expectedVersion());
        inputs.put("artifactHash", artifactHash);
        inputs.put("inventory", before.stream().map(e -> new TreeMap<>(e.details())).toList());
        return GsonProvider.toJson(inputs);
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
