/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.GitTool.CommandRejectedException;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@code git init} in the {@link GitTool} (#934): the narrow grammar it accepts, the refusal of a
 * project that already belongs to a repository, the consent it asks for, and the order of those
 * checks through {@link GitTool#execute}. The real process is covered by
 * {@link GitToolProcessIntegrationTest}.
 */
public class GitToolInitTest
{
    private final List<IProject> projects = new ArrayList<>();

    private final List<Path> folders = new ArrayList<>();

    @After
    public void tearDown() throws Exception
    {
        for (IProject project : projects)
        {
            if (project.exists())
            {
                // The folders are temporary directories of this test, deleted below - not the project's
                // to delete.
                project.delete(false, true, new NullProgressMonitor());
            }
        }
        for (Path folder : folders)
        {
            deleteTree(folder);
        }
    }

    // ---- the whitelist ----

    @Test
    public void testInitIsWhitelistedAndCloneStaysRefused() throws Exception
    {
        assertTrue(GitTool.ALLOWED_SUBCOMMANDS.contains("init")); //$NON-NLS-1$
        assertEquals(List.of("git", "init"), GitTool.parseCommand("init")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(List.of("git", "init"), GitTool.parseCommand("git init")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String clone = rejection("clone https://example.com/r.git"); //$NON-NLS-1$
        assertTrue(clone, clone.contains("'clone' is not supported")); //$NON-NLS-1$
    }

    // ---- the grammar: what is accepted ----

    @Test
    public void testInitAcceptsQuietAndTheInitialBranchInEverySpellingGitReads() throws Exception
    {
        for (String command : new String[] { "init -q", "init --quiet", "init -b main", "init -bmain", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "init --initial-branch=main", "init --initial-branch main", "init -qb main", "init -qbmain", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "init -qqb main", "init -b feature/x -q", "init --quiet --initial-branch=release/2026" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            List<String> argv = GitTool.parseCommand(command);
            List<String> expected = new ArrayList<>(List.of("git")); //$NON-NLS-1$
            expected.addAll(Arrays.asList(command.split(" "))); //$NON-NLS-1$
            assertEquals(command + " is passed to git unchanged", expected, argv); //$NON-NLS-1$
        }
    }

    @Test
    public void testTheLetterAfterBIsTheBranchNameNotAnotherFlag() throws Exception
    {
        // git reads '-bq' as the branch "q", not as -b plus --quiet; so does the grammar - a refusal here
        // would block a valid command, an acceptance of '-bx' as a flag would mis-judge the cluster.
        assertEquals(List.of("git", "init", "-bq"), GitTool.parseCommand("init -bq")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(List.of("git", "init", "-bx"), GitTool.parseCommand("init -bx")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    // ---- the grammar: what is refused ----

    @Test
    public void testInitRefusesTheOptionsThatMoveOrChangeTheRepository()
    {
        for (String option : new String[] { "--bare", "--separate-git-dir=../elsewhere", "--shared", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "--shared=group", "--object-format=sha256", "--ref-format=reftable", "--initial=main", "--qui", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "--no-quiet" }) //$NON-NLS-1$
        {
            String message = rejection("init " + option); //$NON-NLS-1$
            assertTrue(option + ": " + message, message.contains("is not accepted for 'git init'")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(option + " must be named: " + message, //$NON-NLS-1$
                message.contains("'" + option.split("=")[0])); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("the refusal names what IS accepted: " + message, //$NON-NLS-1$
                message.contains("Only -q/--quiet and -b <name>/--initial-branch=<name>")); //$NON-NLS-1$
        }
        // The separated value form of --separate-git-dir is refused on the option, before its value.
        assertTrue(rejection("init --separate-git-dir ../elsewhere").contains("'--separate-git-dir'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnOptionValueIsNotEchoedInTheRefusal()
    {
        String message = rejection("init --shared=s3cr3t-value"); //$NON-NLS-1$
        assertFalse(message, message.contains("s3cr3t-value")); //$NON-NLS-1$
    }

    @Test
    public void testInitRefusesAnUnknownShortFlagAloneOrInACluster()
    {
        for (String command : new String[] { "init -x", "init -qx", "init -n" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String message = rejection(command);
            assertTrue(command + ": " + message, message.contains("is not accepted for 'git init'")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertTrue(rejection("init -qx").contains("'-x'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheTemplateOptionStaysBlockedForInitToo()
    {
        // --template copies hooks - programs - into the new repository: blocked for every subcommand.
        String message = rejection("init --template=/tmp/hooks"); //$NON-NLS-1$
        assertTrue(message, message.contains("'--template=***' is not allowed")); //$NON-NLS-1$
    }

    @Test
    public void testInitRefusesADirectoryOperand()
    {
        for (String command : new String[] { "init sub", "init .", "init -q ../other", "init -" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            String message = rejection(command);
            assertTrue(command + ": " + message, message.contains("takes no directory or other operand")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // Past '--' every token would be the directory: the separator itself is refused.
        assertTrue(rejection("init -- sub").contains("'--'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInitRefusesAMissingOrOptionLikeBranchName()
    {
        for (String command : new String[] { "init -b", "init -qb", "init --initial-branch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "init --initial-branch=", "init -b -q", "init -b --bare", "init --initial-branch=-x" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            String message = rejection(command);
            assertTrue(command + ": " + message, message.contains("needs a branch name")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testInitRefusesAnInvalidBranchName()
    {
        for (String command : new String[] { "init -b \"a b\"", "init -b a..b", "init -b x.lock", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "init -b x~1", "init --initial-branch=feature/", "init -bx:y", "init -b HEAD" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            String message = rejection(command);
            assertTrue(command + ": " + message, message.contains("is not a valid git branch name")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ---- consent ----

    @Test
    public void testInitIsWriteCapableSoItAsksForConsent()
    {
        assertEquals("init", GitTool.destructiveForm(List.of("git", "init", "-q"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(DestructiveConsentGate.GATED_TOOLS.contains(GitTool.NAME));
    }

    @Test
    public void testTheInitPreviewNamesTheFolderAndIsKeptInTheAudit()
    {
        File folder = new File("C:/work/Project"); //$NON-NLS-1$
        ConsentPreview bare = GitTool.initConsentPreview(List.of("git", "init"), folder); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(bare.getSubtitle(), bare.getSubtitle().contains(folder.toString()));
        assertEquals(List.of(folder.getPath()), bare.getTopNames());
        assertTrue("a folder and a branch name are the record of what was created", //$NON-NLS-1$
            bare.areNamesLoggable());

        ConsentPreview withBranch = GitTool.initConsentPreview(List.of("git", "init", "-b", "main"), folder); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(List.of(folder.getPath(), "init -b main"), withBranch.getTopNames()); //$NON-NLS-1$
        assertTrue(GitTool.consentPreview("init", List.of("git", "init")).areNamesLoggable()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testTheOutputSchemaDeclaresNextStep()
    {
        assertTrue(new GitTool().getOutputSchema().contains("\"nextStep\"")); //$NON-NLS-1$
    }

    // ---- the folder: is there a repository already? ----

    @Test
    public void testAFolderOutsideEveryRepositoryMayBeInitialised() throws Exception
    {
        Path folder = freeFolder();
        Assume.assumeTrue("the temporary directory itself lies inside a git repository on this machine", //$NON-NLS-1$
            GitRepositoryResolver.existingRepositoryRefusal(folder.getParent().toFile(), "P") == null); //$NON-NLS-1$
        assertNull(GitRepositoryResolver.existingRepositoryRefusal(folder.toFile(), "P")); //$NON-NLS-1$
    }

    @Test
    public void testAFolderInsideARepositoryIsRefusedNamingItsRoot() throws Exception
    {
        Path outer = freeFolder();
        createRepository(outer);
        Path nested = Files.createDirectories(outer.resolve("projects").resolve("Sales")); //$NON-NLS-1$ //$NON-NLS-2$

        String refusal = GitRepositoryResolver.existingRepositoryRefusal(nested.toFile(), "Sales"); //$NON-NLS-1$

        assertNotNull("a project inside a work tree must not get a nested repository", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("already belongs to the git repository at '" //$NON-NLS-1$
            + outer.toFile().getCanonicalFile())
            || refusal.contains("already belongs to the git repository at '" + outer.toFile())); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("lies inside that repository")); //$NON-NLS-1$
    }

    @Test
    public void testAFolderThatIsAlreadyARepositoryIsRefused() throws Exception
    {
        Path folder = freeFolder();
        createRepository(folder);
        String refusal = GitRepositoryResolver.existingRepositoryRefusal(folder.toFile(), "P"); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("already holds a '.git' entry")); //$NON-NLS-1$
    }

    @Test
    public void testABrokenDotGitFileIsStillARepositoryMarker() throws Exception
    {
        // JGit's upward search skips a .git FILE it cannot follow and reports nothing there; git treats
        // the folder as a repository all the same, so the refusal must not depend on that search.
        Path folder = freeFolder();
        Files.write(folder.resolve(".git"), "gitdir: does/not/exist\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        String refusal = GitRepositoryResolver.existingRepositoryRefusal(folder.toFile(), "P"); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("already holds a '.git' entry")); //$NON-NLS-1$
    }

    // ---- execute(): the order of the checks ----

    @Test
    public void testInitForAnUnknownProjectSaysSo()
    {
        JsonObject result = execute("NoSuchProject_init_" + UUID.randomUUID().toString().replace("-", ""), "init"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.toString(), result.get("error").getAsString().toLowerCase().contains("not found")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAProjectInsideARepositoryIsRefusedBeforeConsentAndGetsNoRepository() throws Exception
    {
        Path outer = freeFolder();
        createRepository(outer);
        Path nested = Files.createDirectories(outer.resolve("Sales")); //$NON-NLS-1$
        IProject project = project(nested);

        JsonObject result = execute(project.getName(), "init -b main"); //$NON-NLS-1$

        assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = result.get("error").getAsString(); //$NON-NLS-1$
        assertTrue("the nested refusal, not a consent answer - the read-only check runs first: " + error, //$NON-NLS-1$
            error.contains("'git init' here would create a repository nested inside it")); //$NON-NLS-1$
        assertFalse("no repository may appear in the project folder", //$NON-NLS-1$
            Files.exists(nested.resolve(".git"), LinkOption.NOFOLLOW_LINKS)); //$NON-NLS-1$
    }

    @Test
    public void testAFreeProjectReachesTheConsentGateAndNothingRunsWithoutConsent() throws Exception
    {
        // No workbench in this test runtime: the gate answers UNATTENDED - unless the launch bypass is set.
        Assume.assumeFalse("EDT_MCP_DESTRUCTIVE_CONSENT=allow is set for this run", //$NON-NLS-1$
            "allow".equalsIgnoreCase(String.valueOf(System.getenv("EDT_MCP_DESTRUCTIVE_CONSENT")))); //$NON-NLS-1$ //$NON-NLS-2$
        Path folder = freeFolder();
        Assume.assumeTrue("the temporary directory lies inside a git repository on this machine", //$NON-NLS-1$
            GitRepositoryResolver.existingRepositoryRefusal(folder.toFile(), "P") == null); //$NON-NLS-1$
        IProject project = project(folder);

        JsonObject result = execute(project.getName(), "init"); //$NON-NLS-1$

        assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("init is gated like every write-capable subcommand", //$NON-NLS-1$
            DestructiveConsentGate.consentDeniedMessage(DestructiveConsentGate.ConsentDecision.UNATTENDED,
                GitTool.NAME),
            result.get("error").getAsString()); //$NON-NLS-1$
        assertFalse("nothing may run without consent", //$NON-NLS-1$
            Files.exists(folder.resolve(".git"), LinkOption.NOFOLLOW_LINKS)); //$NON-NLS-1$
    }

    @Test
    public void testAGrammarRefusalComesBeforeTheProjectIsResolved()
    {
        JsonObject result = execute("NoSuchProject_init_grammar", "init --bare"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.toString(), result.get("error").getAsString().contains("'--bare'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- helpers ----

    private static String rejection(String command)
    {
        try
        {
            List<String> argv = GitTool.parseCommand(command);
            fail("expected '" + command + "' to be rejected, got " + argv); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        catch (CommandRejectedException e)
        {
            return e.getMessage();
        }
    }

    private static JsonObject execute(String projectName, String command)
    {
        String json = new GitTool().execute(Map.of("projectName", projectName, "command", command)); //$NON-NLS-1$ //$NON-NLS-2$
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private Path freeFolder() throws IOException
    {
        Path folder = Files.createTempDirectory("git-init-934"); //$NON-NLS-1$
        folders.add(folder);
        return folder;
    }

    private static void createRepository(Path workTree) throws IOException
    {
        try (Repository repository = FileRepositoryBuilder.create(workTree.resolve(".git").toFile())) //$NON-NLS-1$
        {
            repository.create();
        }
    }

    private IProject project(Path location) throws Exception
    {
        String name = "ZzGitInit" + UUID.randomUUID().toString().replace("-", ""); //$NON-NLS-1$ //$NON-NLS-2$
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        projects.add(project);
        IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription(name);
        description.setLocation(org.eclipse.core.runtime.Path.fromOSString(location.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        return project;
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS))
        {
            return;
        }
        try (Stream<Path> paths = Files.walk(root))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
