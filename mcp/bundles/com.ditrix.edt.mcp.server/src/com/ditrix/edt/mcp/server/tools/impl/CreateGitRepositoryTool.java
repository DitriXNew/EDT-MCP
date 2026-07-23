/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.egit.core.op.CloneOperation;
import org.eclipse.egit.core.op.ConnectProviderOperation;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.InitCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.git.GitRemoteSupport;

/**
 * Bootstraps a git repository for an EDT project - the non-UI sibling of EDT's own
 * "Share Project" / "Clone" commands, and the entry point of the git dev-loop family
 * that also adds {@code commit_git_changes} / {@code push_git_branch} /
 * {@code pull_git_branch} / {@code get_git_status}. Two modes, discriminated by
 * whether a remote {@code url} is given:
 * <ul>
 * <li><b>init</b> (no {@code url}): {@code git init} at an EXISTING open project's
 * filesystem location, then connect it to the EGit team provider
 * ({@link ConnectProviderOperation}) so the rest of the git tools resolve it via
 * {@code GitRepositoryResolver}. Rejected up front when the project already lives
 * inside a git working tree (a walk-up {@code findGitDir} match) - initializing a
 * nested repository there would be a mistake.</li>
 * <li><b>clone</b> ({@code url} given): clone the remote into {@code targetPath} on a
 * bounded background {@link GitRemoteSupport} Job (never the UI thread) with the
 * non-interactive credentials provider, refresh the workspace, then import the
 * cloned project and connect it to EGit.</li>
 * </ul>
 * <p>
 * <b>Honest-partial contract.</b> The init/clone step is the irreversible one; once
 * the repository exists on disk the call is a success. A subsequent EGit-share
 * failure (or, for clone, an import failure or a workspace-refresh failure) is
 * reported as a note in the result {@code message} with {@code shared:false} - never
 * a total failure that would dishonestly claim the already-created repository does
 * not exist. Even without the EGit share, {@code GitRepositoryResolver} still finds
 * the repository via its filesystem {@code findGitDir} discovery fallback.
 * <p>
 * <b>Unattended-safety.</b> The clone network op is time-bounded (120 s) and runs off
 * the UI thread. Authentication is non-interactive: SSH is transparent (EGit's
 * process-global session factory + ssh-agent / {@code ~/.ssh}); HTTPS uses the
 * OPTIONAL {@code username}/{@code token} params, and with neither supplied the
 * credentials provider fails fast rather than opening a modal login dialog. No
 * infobase connection is opened ({@link #connectsToInfobase()} stays the default
 * {@code false}); {@link #getAnnotations()} advertises {@code openWorldHint=true}
 * because the clone mode reaches the network.
 */
public class CreateGitRepositoryTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "create_git_repository"; //$NON-NLS-1$

    /** Input param: remote URL to clone; its presence selects clone mode. */
    private static final String KEY_URL = "url"; //$NON-NLS-1$

    /** Input param: local directory to clone into (required in clone mode). */
    private static final String KEY_TARGET_PATH = "targetPath"; //$NON-NLS-1$

    /** Input param: remote name for the clone (default {@code origin}). */
    private static final String KEY_REMOTE_NAME = "remoteName"; //$NON-NLS-1$

    /** Input param: initial branch name for {@code git init} (optional). */
    private static final String KEY_INITIAL_BRANCH = "initialBranch"; //$NON-NLS-1$

    /** Input param: optional HTTPS username. */
    private static final String KEY_USERNAME = "username"; //$NON-NLS-1$

    /** Input param: optional HTTPS token/password. */
    private static final String KEY_TOKEN = "token"; //$NON-NLS-1$

    /** Output key: the mode the call ran in ({@code init} / {@code clone}). */
    private static final String KEY_MODE = "mode"; //$NON-NLS-1$

    /** Output key: absolute path of the created {@code .git} directory. */
    private static final String KEY_REPOSITORY_PATH = "repositoryPath"; //$NON-NLS-1$

    /** Output key: whether the project was connected to the EGit team provider. */
    private static final String KEY_SHARED = "shared"; //$NON-NLS-1$

    private static final String KEY_IMPORTED = "imported"; //$NON-NLS-1$

    /** Output key: echo of the cloned remote URL (clone mode only). */
    private static final String KEY_REMOTE_URL = "remoteUrl"; //$NON-NLS-1$

    private static final String MODE_INIT = "init"; //$NON-NLS-1$

    private static final String MODE_CLONE = "clone"; //$NON-NLS-1$

    /** Default git remote name used by the clone when {@code remoteName} is omitted. */
    private static final String DEFAULT_REMOTE = "origin"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Bootstrap a git repository for an EDT project. Without 'url': git init at an EXISTING " //$NON-NLS-1$
            + "open project's location and connect it to EGit (rejected if the project is already inside " //$NON-NLS-1$
            + "a git working tree). With 'url' + 'targetPath': clone the remote on a bounded background " //$NON-NLS-1$
            + "Job, then import and connect the project. SSH auth is transparent (ssh-agent / ~/.ssh); " //$NON-NLS-1$
            + "HTTPS uses optional username/token and never opens a login dialog. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('create_git_repository')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "For init: the EXISTING open EDT project to initialize a repository at (required). For " //$NON-NLS-1$
                + "clone: the name to import the cloned project under when the cloned tree has no own " //$NON-NLS-1$
                + ".project (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_URL,
                "Remote repository URL to clone (SSH or HTTPS). When given, the tool runs in CLONE mode; " //$NON-NLS-1$
                + "when omitted, in INIT mode.") //$NON-NLS-1$
            .stringProperty(KEY_TARGET_PATH,
                "Clone mode only (required there): an absolute local directory to clone into. It must not " //$NON-NLS-1$
                + "already exist as a non-empty directory.") //$NON-NLS-1$
            .stringProperty(KEY_REMOTE_NAME,
                "Clone mode only: the git remote name to register. Optional; defaults to 'origin'.") //$NON-NLS-1$
            .stringProperty(KEY_INITIAL_BRANCH,
                "Init mode only: the initial branch name for the new repository (e.g. 'main'). Optional; " //$NON-NLS-1$
                + "defaults to git's configured default.") //$NON-NLS-1$
            .stringProperty(KEY_USERNAME,
                "Optional HTTPS username for the clone. Omit for SSH (handled transparently by the " //$NON-NLS-1$
                + "ssh-agent) or for anonymous/public HTTPS.") //$NON-NLS-1$
            .stringProperty(KEY_TOKEN,
                "Optional HTTPS token/password for the clone, paired with username. With neither given, " //$NON-NLS-1$
                + "an HTTPS remote that requires credentials fails fast (no interactive prompt).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the repository was created (init/clone succeeded)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_MODE, "The mode the call ran in: 'init' or 'clone'.") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT, "The name of the project the repository is for. In clone mode " //$NON-NLS-1$
                + "it is present only when the clone was actually imported (see 'imported').") //$NON-NLS-1$
            .stringProperty(KEY_REPOSITORY_PATH, "Absolute path of the created .git directory.") //$NON-NLS-1$
            .booleanProperty(KEY_IMPORTED,
                "Clone mode: whether the cloned tree was imported as a workspace project. False (with a " //$NON-NLS-1$
                + "note in message, and no 'project' field) when a same-named project already exists at " //$NON-NLS-1$
                + "another location or the import failed - the repository is on disk but must be imported " //$NON-NLS-1$
                + "manually.") //$NON-NLS-1$
            .booleanProperty(KEY_SHARED,
                "Whether the project was connected to the EGit team provider. Check 'imported' FIRST: when " //$NON-NLS-1$
                + "imported=true, shared=false means the share failed but the repository is on disk and " //$NON-NLS-1$
                + "usable (found via filesystem discovery); when imported=false (clone collision / import " //$NON-NLS-1$
                + "failure) shared is also false because sharing was never attempted and there is no usable " //$NON-NLS-1$
                + "project - see 'message'.") //$NON-NLS-1$
            .stringProperty(KEY_REMOTE_URL, "Echo of the cloned remote URL (clone mode only).") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE,
                "Present only when a non-fatal step (EGit share, clone import, or workspace refresh) did " //$NON-NLS-1$
                + "not fully succeed: a short note. The repository itself was still created.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        // Non-destructive write that CAN reach the network (clone mode), so advertise openWorldHint=true.
        // The name-prefix classifier would otherwise leave openWorldHint=false (local-workspace default).
        return new ToolAnnotations(null, Boolean.FALSE, Boolean.FALSE, null, Boolean.TRUE);
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String url = JsonUtils.extractStringArgument(params, KEY_URL);
        String targetPath = JsonUtils.extractStringArgument(params, KEY_TARGET_PATH);
        String remoteName = JsonUtils.extractStringArgument(params, KEY_REMOTE_NAME);
        String initialBranch = JsonUtils.extractStringArgument(params, KEY_INITIAL_BRANCH);
        String username = JsonUtils.extractStringArgument(params, KEY_USERNAME);
        String token = JsonUtils.extractStringArgument(params, KEY_TOKEN);

        String nameError = validateProjectName(projectName);
        if (nameError != null)
        {
            return nameError;
        }

        boolean cloneMode = url != null && !url.isEmpty();
        try
        {
            if (cloneMode)
            {
                String remote = remoteName != null && !remoteName.isEmpty() ? remoteName : DEFAULT_REMOTE;
                return cloneRepository(projectName, url, targetPath, remote, username, token);
            }
            return initRepository(projectName, initialBranch);
        }
        catch (Exception e) // NOSONAR unattended-safety: no exception may escape the tool (CLAUDE.md #8)
        {
            Activator.logError("create_git_repository: failed for project '" + projectName + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to create git repository: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    // ==================== init mode ====================

    private String initRepository(String projectName, String initialBranch)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        if (!ctx.isOpen())
        {
            return ToolResult.error("Project is closed: " + projectName //$NON-NLS-1$
                + ". Open the project in EDT first.").toJson(); //$NON-NLS-1$
        }
        IProject project = ctx.project();
        IPath locPath = project.getLocation();
        if (locPath == null)
        {
            return ToolResult.error("Project '" + projectName //$NON-NLS-1$
                + "' has no local filesystem location; cannot initialize a git repository for it.").toJson(); //$NON-NLS-1$
        }
        File location = locPath.toFile();

        File existingGitDir = findGitDir(location);
        if (existingGitDir != null)
        {
            return ToolResult.error("Project '" + projectName + "' is already inside a git repository (git " //$NON-NLS-1$ //$NON-NLS-2$
                + "dir: " + existingGitDir.getAbsolutePath() + "). Use get_git_status or list_git_branches " //$NON-NLS-1$ //$NON-NLS-2$
                + "to inspect it, or commit_git_changes to record changes.").toJson(); //$NON-NLS-1$
        }

        File gitDir;
        try
        {
            InitCommand cmd = Git.init().setDirectory(location);
            if (initialBranch != null && !initialBranch.isEmpty())
            {
                cmd.setInitialBranch(initialBranch);
            }
            try (Git git = cmd.call())
            {
                gitDir = git.getRepository().getDirectory();
            }
        }
        catch (InvalidRefNameException e)
        {
            return ToolResult.error("Invalid initialBranch name '" + initialBranch + "': " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage()).toJson();
        }
        catch (GitAPIException e)
        {
            Activator.logError("create_git_repository: init failed at '" + location.getAbsolutePath() + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to initialize git repository at '" + location.getAbsolutePath() //$NON-NLS-1$
                + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // The repository now exists on disk: everything below is reported honestly, never as a total failure.
        List<String> warnings = new ArrayList<>();
        boolean shared = shareProject(project, warnings);

        ToolResult ok = ToolResult.success()
            .put(KEY_MODE, MODE_INIT)
            .put(McpKeys.PROJECT, project.getName())
            .put(KEY_REPOSITORY_PATH, gitDir != null ? gitDir.getAbsolutePath() : location.getAbsolutePath())
            .put(KEY_SHARED, shared);
        if (!warnings.isEmpty())
        {
            ok.put(McpKeys.MESSAGE, String.join(" ", warnings)); //$NON-NLS-1$
        }
        return ok.toJson();
    }

    // ==================== clone mode ====================

    private String cloneRepository(String projectName, String url, String targetPath, String remoteName,
        String username, String token) throws Exception
    {
        if (targetPath == null || targetPath.isBlank())
        {
            return ToolResult.error("targetPath is required when 'url' is given: the local directory to " //$NON-NLS-1$
                + "clone '" + url + "' into.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // Trim ONCE and use the trimmed value everywhere below (validation AND the actual clone target),
        // so a leading-space path cannot pass the absolute check yet clone to a relative location.
        targetPath = targetPath.trim();
        if (!new File(targetPath).isAbsolute())
        {
            return ToolResult.error("targetPath must be an ABSOLUTE local directory path, but got '" //$NON-NLS-1$
                + targetPath + "'. A relative path would clone into EDT's own working directory; give a " //$NON-NLS-1$
                + "full path (e.g. 'C:/work/repo' or '/home/user/repo').").toJson(); //$NON-NLS-1$
        }
        URIish uri;
        try
        {
            uri = new URIish(url);
        }
        catch (URISyntaxException e)
        {
            return ToolResult.error("Invalid remote url '" + url + "': " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        File targetDir = new File(targetPath);
        if (targetDir.exists() && !isEmptyDirectory(targetDir))
        {
            return ToolResult.error("targetPath already exists and is not an empty directory: '" //$NON-NLS-1$
                + targetDir.getAbsolutePath() + "'. Choose a new or empty target directory to clone into.") //$NON-NLS-1$
                .toJson();
        }

        CredentialsProvider credentials = GitRemoteSupport.credentialsProvider(username, token);
        // Pass Constants.HEAD (not null) as refName so EGit checks out the remote HEAD branch into the
        // working tree: a null refName makes CloneOperation call setNoCheckout(true), leaving targetDir empty.
        CloneOperation clone = new CloneOperation(uri, true, null, targetDir, Constants.HEAD, remoteName,
            (int)GitRemoteSupport.REMOTE_TIMEOUT_SECONDS);
        clone.setCredentialsProvider(credentials);

        // No owned Repository to release here: CloneOperation creates the on-disk repo itself and does
        // not hold a borrowed handle to close (unlike push/pull), so no afterJobDone closer is needed.
        GitRemoteSupport.RemoteOutcome outcome = GitRemoteSupport.run(
            "Clone git repository: " + uri, null, monitor -> clone.run(monitor), null); //$NON-NLS-1$

        if (outcome.timedOut())
        {
            return ToolResult.error("Cloning '" + url + "' timed out after " //$NON-NLS-1$ //$NON-NLS-2$
                + GitRemoteSupport.REMOTE_TIMEOUT_SECONDS
                + " seconds. Check network access and the remote url, then retry.").toJson(); //$NON-NLS-1$
        }
        if (outcome.interrupted())
        {
            return ToolResult.error("Cloning '" + url + "' was interrupted.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (outcome.jobError() != null)
        {
            Activator.logError("create_git_repository: clone failed for '" + url + "'", outcome.jobError()); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error(cloneFailureMessage(url, outcome.jobError())).toJson();
        }

        // The clone succeeded on disk: import + share are honest-partial from here.
        List<String> warnings = new ArrayList<>();
        return importAndShareClone(projectName, url, targetDir, clone.getGitDir(), warnings);
    }

    /**
     * Imports the cloned working tree as a workspace project and connects it to
     * EGit. Prefers the cloned tree's own {@code .project} (its name wins); falls
     * back to creating a project named {@code projectName} at the clone location.
     * Every non-fatal problem here (name collision, import failure, refresh failure,
     * share failure) is a note in {@code warnings}, never a total failure - the
     * repository is already on disk.
     */
    private String importAndShareClone(String projectName, String url, File targetDir, File gitDir,
        List<String> warnings)
    {
        IWorkspace workspace = ProjectContext.workspace();
        IProgressMonitor monitor = new NullProgressMonitor();
        IProject project = null;

        IPath targetDirPath = IPath.fromOSString(targetDir.getAbsolutePath());
        IPath descPath = targetDirPath.append(IProjectDescription.DESCRIPTION_FILE_NAME);
        try
        {
            if (descPath.toFile().isFile())
            {
                IProjectDescription description = workspace.loadProjectDescription(descPath);
                String importedName = description.getName();
                IStatus importedNameStatus = workspace.validateName(importedName, IResource.PROJECT);
                if (!importedNameStatus.isOK())
                {
                    // The name embedded in the cloned .project is not a valid workspace segment (e.g.
                    // 'bad/name'): getProject(importedName) would throw IllegalArgumentException AFTER the
                    // clone is already on disk. Leave it un-imported with a note instead of a total failure.
                    warnings.add("The cloned repository's .project name '" + importedName //$NON-NLS-1$
                        + "' is not a valid EDT project name (" + importedNameStatus.getMessage() //$NON-NLS-1$
                        + "); the clone on disk is complete but was not imported."); //$NON-NLS-1$
                    project = null;
                }
                else if ((project = workspace.getRoot().getProject(importedName)).exists()
                    && !isSameLocation(project, targetDir))
                {
                    // A DIFFERENT workspace project already owns this name (at another location).
                    // Adopting it would open/share/refresh an unrelated project and falsely report
                    // the clone as imported. Mirror the no-.project branch: warn, leave it un-imported
                    // (the clone is already on disk).
                    warnings.add("A workspace project named '" + importedName + "' already exists at a " //$NON-NLS-1$ //$NON-NLS-2$
                        + "different location; the clone on disk is complete but was not imported."); //$NON-NLS-1$
                    project = null;
                }
                else
                {
                    if (!project.exists())
                    {
                        description.setLocation(targetDirPath);
                        project.create(description, monitor);
                    }
                    if (!project.isOpen())
                    {
                        project.open(monitor);
                    }
                    if (!importedName.equals(projectName))
                    {
                        warnings.add("The cloned repository defines project '" + importedName //$NON-NLS-1$
                            + "' (imported under that name), not the requested '" + projectName + "'."); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }
            else
            {
                project = workspace.getRoot().getProject(projectName);
                if (project.exists())
                {
                    warnings.add("A workspace project named '" + projectName + "' already exists; the clone " //$NON-NLS-1$ //$NON-NLS-2$
                        + "on disk is complete but was not imported."); //$NON-NLS-1$
                    project = null;
                }
                else
                {
                    IProjectDescription description = workspace.newProjectDescription(projectName);
                    description.setLocation(targetDirPath);
                    project.create(description, monitor);
                    project.open(monitor);
                }
            }
        }
        catch (CoreException e)
        {
            Activator.logError("create_git_repository: importing cloned project failed at '" //$NON-NLS-1$
                + targetDir.getAbsolutePath() + "'", e); //$NON-NLS-1$
            if (project != null && project.exists() && isSameLocation(project, targetDir))
            {
                // The project WAS created at OUR clone location (a follow-up step - open, link
                // reconciliation, refresh - failed AFTER creation). Keep it as imported: reporting
                // imported=false here would tell the caller to import it manually, which would then collide
                // with this real, existing project. The isSameLocation guard rejects a concurrently-created
                // DIFFERENT same-name project (elsewhere) that would have failed our create().
                warnings.add("The repository was cloned to '" + targetDir.getAbsolutePath() //$NON-NLS-1$
                    + "' and the project was created, but a follow-up import step failed (" + e.getMessage() //$NON-NLS-1$
                    + "); the project may need a manual refresh (F5)."); //$NON-NLS-1$
            }
            else
            {
                warnings.add("The repository was cloned to '" + targetDir.getAbsolutePath() //$NON-NLS-1$
                    + "', but importing it as an EDT project failed (" + e.getMessage() //$NON-NLS-1$
                    + "); import it manually via File -> Import -> Existing Projects."); //$NON-NLS-1$
                project = null;
            }
        }

        boolean shared = false;
        if (project != null)
        {
            shared = shareProject(project, warnings);
            refreshQuietly(project, warnings);
        }

        boolean imported = project != null;
        ToolResult ok = ToolResult.success()
            .put(KEY_MODE, MODE_CLONE)
            .put(KEY_REMOTE_URL, url)
            .put(KEY_REPOSITORY_PATH, gitDir != null ? gitDir.getAbsolutePath() : targetDir.getAbsolutePath())
            .put(KEY_IMPORTED, imported)
            .put(KEY_SHARED, shared);
        // Only advertise a project name when a project was actually imported. On a name collision (or an
        // import failure) project is null: returning the requested name would point a chained git tool at
        // an UNRELATED (or nonexistent) project. imported=false + the warning tell the caller to import
        // the on-disk clone manually; imported=true + shared=false means imported but the EGit share failed.
        if (imported)
        {
            ok.put(McpKeys.PROJECT, project.getName());
        }
        if (!warnings.isEmpty())
        {
            ok.put(McpKeys.MESSAGE, String.join(" ", warnings)); //$NON-NLS-1$
        }
        return ok.toJson();
    }

    // ==================== shared helpers ====================

    /**
     * Rejects a project name that is not a valid workspace path segment BEFORE any network or
     * filesystem mutation. Without this an invalid name (e.g. one containing {@code '/'}) throws an
     * {@link IllegalArgumentException} out of a later {@code getProject(...)} - which, in clone mode,
     * happens only AFTER the repository is already on disk, so the tool would report a total failure
     * for a clone that actually succeeded (an honest-partial violation).
     *
     * @param projectName the requested project name
     * @return an error JSON to return from {@code execute}, or {@code null} when the name is valid
     */
    private static String validateProjectName(String projectName)
    {
        IStatus status = ProjectContext.workspace().validateName(projectName, IResource.PROJECT);
        if (!status.isOK())
        {
            return ToolResult.error("Invalid project name '" + projectName + "': " + status.getMessage() //$NON-NLS-1$ //$NON-NLS-2$
                + ". Use a valid EDT project name - a single path segment, without '/'.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * @return {@code true} when the existing {@code project}'s on-disk location is
     *         {@code targetDir} - i.e. the pre-existing handle genuinely points at the
     *         freshly cloned tree and may be adopted; {@code false} when it resolves
     *         elsewhere (a real name collision with an unrelated project) or has no
     *         location. On Windows {@link java.io.File#equals} is case-insensitive, which
     *         matches the platform's own path handling.
     */
    private static boolean isSameLocation(IProject project, File targetDir)
    {
        IPath location = project.getLocation();
        return location != null && location.toFile().getAbsoluteFile().equals(targetDir.getAbsoluteFile());
    }

    /**
     * Connects {@code project} to the EGit team provider so the other git tools
     * resolve it via {@code RepositoryMapping}. A failure is appended to
     * {@code warnings} and {@code false} returned - it never fails the
     * already-created repository (which the resolver still finds via its
     * {@code findGitDir} discovery fallback).
     */
    private static boolean shareProject(IProject project, List<String> warnings)
    {
        try
        {
            new ConnectProviderOperation(project).execute(new NullProgressMonitor());
            return true;
        }
        catch (CoreException | RuntimeException e)
        {
            Activator.logError("create_git_repository: EGit share failed for project '" //$NON-NLS-1$
                + project.getName() + "'", e); //$NON-NLS-1$
            warnings.add(shareFailureWarning(e.getMessage()));
            return false;
        }
    }

    /**
     * Refreshes the imported project so the EDT model picks up the cloned files.
     * A refresh failure is a note, never a failure of the already-cloned repository.
     */
    private static void refreshQuietly(IProject project, List<String> warnings)
    {
        try
        {
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        }
        catch (CoreException e)
        {
            Activator.logError("create_git_repository: post-clone refresh failed for '" //$NON-NLS-1$
                + project.getName() + "'", e); //$NON-NLS-1$
            warnings.add("Workspace refresh after the clone failed (" + e.getMessage() //$NON-NLS-1$
                + "); the EDT model may be stale until a manual refresh."); //$NON-NLS-1$
        }
    }

    /**
     * The honest-partial warning appended when the EGit share fails but the
     * repository was already created. Package-visible so its shape is unit-testable
     * without a live workspace.
     */
    static String shareFailureWarning(String reason)
    {
        return "The repository was created, but connecting the project to the EGit team provider failed (" //$NON-NLS-1$
            + reason + "); the repository is still usable (get_git_status / commit_git_changes work), and " //$NON-NLS-1$
            + "you can share it manually via Team -> Share Project."; //$NON-NLS-1$
    }

    /**
     * The actionable message for a failed clone: the root cause plus the auth hint
     * (SSH via ssh-agent, HTTPS via username/token, never an interactive prompt).
     * Package-visible so it is unit-testable without a network.
     */
    static String cloneFailureMessage(String url, Throwable error)
    {
        return "Failed to clone '" + url + "': " + rootMessage(error) //$NON-NLS-1$ //$NON-NLS-2$
            + ". For an SSH url ensure your key is loaded (ssh-agent); for an HTTPS url pass 'username' and " //$NON-NLS-1$
            + "'token' (no interactive credential prompt is shown in unattended mode)."; //$NON-NLS-1$
    }

    /** Unwraps to the deepest cause's message (clone wraps the transport error in InvocationTargetException). */
    static String rootMessage(Throwable error)
    {
        Throwable cur = error;
        while (cur.getCause() != null && cur.getCause() != cur)
        {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg != null && !msg.isEmpty() ? msg : cur.getClass().getSimpleName();
    }

    /**
     * Whether {@code location} is already inside a git working tree (a walk-up
     * {@code findGitDir} match), returning the discovered {@code .git} directory or
     * {@code null}. Mirrors {@code GitRepositoryResolver}'s discovery mechanics; a
     * non-null result blocks {@code git init} to avoid a nested repository.
     */
    private static File findGitDir(File location)
    {
        try
        {
            return new FileRepositoryBuilder().findGitDir(location).getGitDir();
        }
        catch (RuntimeException e)
        {
            Activator.logError("create_git_repository: git-dir discovery failed at '" //$NON-NLS-1$
                + location.getAbsolutePath() + "'", e); //$NON-NLS-1$
            return null;
        }
    }

    private static boolean isEmptyDirectory(File dir)
    {
        if (!dir.isDirectory())
        {
            return false;
        }
        String[] entries = dir.list();
        return entries == null || entries.length == 0;
    }
}
