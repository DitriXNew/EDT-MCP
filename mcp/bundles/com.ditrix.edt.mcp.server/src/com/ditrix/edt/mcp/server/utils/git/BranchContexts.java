/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import java.util.Optional;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;

/**
 * The one place that maps a git branch to the infobase-association context EDT keys it by (#684).
 * <p>
 * EDT keys a branch context by the FULL ref: {@code GitRepositoryAssociationContextManager} returns
 * {@code InfobaseAssociationContext.of(repository.getFullBranch())} ({@code refs/heads/<branch>}),
 * {@code GitBranchToContextConverter} builds {@code of("refs/heads/" + name)}, and
 * {@link InfobaseAssociationContext#equals(Object)} compares the strings literally. A binding written
 * under the short name ({@code feature/x}) is therefore never the current context: EDT does not pick
 * it up on checkout and {@code update_database} / {@code get_applications} never see it. Older
 * versions of {@code set_branch_infobase} and {@code create_git_branch} wrote exactly such short
 * keys; {@link #displayName(InfobaseAssociationContext)} marks them and {@link Target#legacyContext()}
 * lets a detach remove them. Nothing here migrates them.
 * <p>
 * The mapping is built here instead of through EDT's internal converter so no internal class is
 * referenced.
 */
public final class BranchContexts
{
    /** Prefix of a local branch ref - the prefix EDT's branch contexts carry. */
    public static final String REFS_HEADS = "refs/heads/"; //$NON-NLS-1$

    /** What a context without a name (the project-level binding) is shown as. */
    public static final String DEFAULT_CONTEXT_LABEL = "(default)"; //$NON-NLS-1$

    /** Marker on a context stored under a bare short name by an older version of our tools. */
    public static final String LEGACY_SHORT_KEY_MARKER =
        " (legacy short key - matches no branch, EDT never reads it; detach it with set_branch_infobase)"; //$NON-NLS-1$

    /** Marker on a context that is a ref, but not a local branch ref. */
    public static final String NON_BRANCH_REF_MARKER =
        " (not a local branch ref - EDT never reads it as a branch context)"; //$NON-NLS-1$

    /** Marker on a context that is a commit id: EDT's context while HEAD is detached at that commit. */
    public static final String COMMIT_CONTEXT_MARKER =
        " (detached-HEAD commit - EDT reads it only while HEAD is detached at this commit)"; //$NON-NLS-1$

    private static final String REFS = "refs/"; //$NON-NLS-1$

    private static final String REFS_REMOTES = "refs/remotes/"; //$NON-NLS-1$

    private BranchContexts()
    {
        // Utility class
    }

    /**
     * A branch input resolved to the context EDT reads, plus the legacy short key older versions of
     * our tools wrote for the same branch.
     */
    public static final class Target
    {
        private final String shortName;

        private final String fullRef;

        private final InfobaseAssociationContext context;

        private final InfobaseAssociationContext legacyContext;

        private Target(String shortName, String fullRef, InfobaseAssociationContext context,
            InfobaseAssociationContext legacyContext)
        {
            this.shortName = shortName;
            this.fullRef = fullRef;
            this.context = context;
            this.legacyContext = legacyContext;
        }

        /** @return the branch name without {@code refs/heads/}, e.g. {@code feature/x} */
        public String shortName()
        {
            return shortName;
        }

        /** @return the full ref, e.g. {@code refs/heads/feature/x} */
        public String fullRef()
        {
            return fullRef;
        }

        /** @return the context EDT reads for this branch: {@code of(fullRef())} */
        public InfobaseAssociationContext context()
        {
            return context;
        }

        /**
         * @return the context older versions of our tools wrote for this branch ({@code of(shortName())}),
         *         or {@code null} when EDT refuses the short name as a context (then no such entry can exist)
         *         or when the short name itself starts with {@code refs/} (then the bare name is another
         *         branch's real context, never a legacy key of this one)
         */
        public InfobaseAssociationContext legacyContext()
        {
            return legacyContext;
        }
    }

    /** The outcome of {@link #resolve(String)}: a {@link Target}, or an actionable refusal text. */
    public static final class Resolution
    {
        private final Target target;

        private final String error;

        private Resolution(Target target, String error)
        {
            this.target = target;
            this.error = error;
        }

        /** @return {@code true} when the input names a local branch */
        public boolean ok()
        {
            return target != null;
        }

        /** @return the resolved branch, or {@code null} when refused */
        public Target target()
        {
            return target;
        }

        /** @return the actionable refusal text, or {@code null} when resolved */
        public String error()
        {
            return error;
        }
    }

    /**
     * Resolves a branch input - a short local branch name ({@code feature/x}) or its full ref
     * ({@code refs/heads/feature/x}) - to the context EDT keys that branch by. Refuses a blank input,
     * a remote-tracking ref, any other {@code refs/...} ref, and a name git cannot use as a branch
     * (such a name can never become the checked-out branch, so a binding under it could never apply).
     * The name is kept verbatim otherwise (any script, including Cyrillic).
     *
     * @param branchOrRef the caller's input (may be {@code null})
     * @return the resolution, never {@code null}
     */
    public static Resolution resolve(String branchOrRef)
    {
        if (branchOrRef == null || branchOrRef.trim().isEmpty())
        {
            return refused("The branch name is blank. Pass the short branch name (e.g. 'feature/x') or its " //$NON-NLS-1$
                + "full ref 'refs/heads/feature/x'; list_git_branches lists the branches."); //$NON-NLS-1$
        }
        String shortName;
        if (branchOrRef.startsWith(REFS_HEADS))
        {
            shortName = branchOrRef.substring(REFS_HEADS.length());
        }
        else if (branchOrRef.startsWith(REFS_REMOTES))
        {
            return refused("'" + branchOrRef + "' is a remote-tracking ref; an infobase binding belongs to a " //$NON-NLS-1$ //$NON-NLS-2$
                + "LOCAL branch. Pass the local branch name (e.g. 'feature/x'), and create the local branch " //$NON-NLS-1$
                + "with create_git_branch if it does not exist yet."); //$NON-NLS-1$
        }
        else if (branchOrRef.startsWith(REFS))
        {
            return refused("'" + branchOrRef + "' is not a local branch ref. Pass the short branch name " //$NON-NLS-1$ //$NON-NLS-2$
                + "(e.g. 'feature/x') or 'refs/heads/<branch>'; list_git_branches lists the branches."); //$NON-NLS-1$
        }
        else
        {
            shortName = branchOrRef;
        }
        String fullRef = REFS_HEADS + shortName;
        if (shortName.isEmpty() || !Repository.isValidRefName(fullRef))
        {
            return refused("'" + branchOrRef + "' is not a valid git branch name, so no checkout can ever " //$NON-NLS-1$ //$NON-NLS-2$
                + "make it the current branch. Pass the short branch name (e.g. 'feature/x') or " //$NON-NLS-1$
                + "'refs/heads/<branch>'; list_git_branches lists the branches."); //$NON-NLS-1$
        }
        InfobaseAssociationContext context;
        try
        {
            context = InfobaseAssociationContext.of(fullRef);
        }
        catch (IllegalArgumentException e)
        {
            return refused("'" + branchOrRef + "' cannot be used as an EDT branch context: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new Resolution(new Target(shortName, fullRef, context, legacyContextOrNull(shortName)), null);
    }

    /**
     * The context EDT reads for a branch, for a caller that already holds a valid branch name or ref.
     *
     * @param branchOrRef a short local branch name or its {@code refs/heads/...} ref
     * @return {@code InfobaseAssociationContext.of("refs/heads/<branch>")}
     * @throws IllegalArgumentException with the actionable refusal text of {@link #resolve(String)}
     */
    public static InfobaseAssociationContext toContext(String branchOrRef)
    {
        Resolution resolution = resolve(branchOrRef);
        if (!resolution.ok())
        {
            throw new IllegalArgumentException(resolution.error());
        }
        return resolution.target().context();
    }

    /**
     * @param context an association context (may be {@code null})
     * @return {@code true} when the context is a local branch context EDT can make current
     *         ({@code refs/heads/<branch>})
     */
    public static boolean isBranchContext(InfobaseAssociationContext context)
    {
        Optional<String> name = context == null ? Optional.empty() : context.getContext();
        return name.isPresent() && name.get().startsWith(REFS_HEADS)
            && name.get().length() > REFS_HEADS.length();
    }

    /**
     * How a context is shown to the caller: {@code refs/heads/X} as {@code X}; the unnamed context as
     * {@link #DEFAULT_CONTEXT_LABEL}; anything else as is WITH a marker - a bare short name is a legacy
     * key that matches no branch ({@link #LEGACY_SHORT_KEY_MARKER}), a commit id is a detached-HEAD
     * context ({@link #COMMIT_CONTEXT_MARKER}), and any other ref is no branch context at all
     * ({@link #NON_BRANCH_REF_MARKER}).
     *
     * @param context an association context (may be {@code null})
     * @return the display text, never {@code null}
     */
    public static String displayName(InfobaseAssociationContext context)
    {
        Optional<String> name = context == null ? Optional.empty() : context.getContext();
        if (name.isEmpty())
        {
            return DEFAULT_CONTEXT_LABEL;
        }
        String raw = name.get();
        if (isBranchContext(context))
        {
            return raw.substring(REFS_HEADS.length());
        }
        if (raw.startsWith(REFS))
        {
            return raw + NON_BRANCH_REF_MARKER;
        }
        if (ObjectId.isId(raw))
        {
            return raw + COMMIT_CONTEXT_MARKER;
        }
        return raw + LEGACY_SHORT_KEY_MARKER;
    }

    private static InfobaseAssociationContext legacyContextOrNull(String shortName)
    {
        if (shortName.startsWith(REFS))
        {
            // A branch literally named 'refs/...': its bare name is ANOTHER branch's real context (the
            // short name 'refs/heads/x' is branch x's full ref), and older versions wrote the full ref
            // for such an input, which is the right key. There is no legacy entry to fall back to.
            return null;
        }
        try
        {
            return InfobaseAssociationContext.of(shortName);
        }
        catch (IllegalArgumentException e) // NOSONAR EDT refuses the name as a context, so no entry can exist
        {
            return null;
        }
    }

    /**
     * The note for an attach that succeeded while the requested default did not. The two failures
     * {@code IInfobaseAssociationManager.setDefaultInfobase(project, infobase, context)} can raise mean
     * different things, and only one of them has a remedy the caller can apply:
     * <ul>
     * <li>an {@link IllegalArgumentException} is EDT's refusal: it checks the infobase against the
     * binding of the CHECKED-OUT branch ({@code getAssociation(IProject)}), not of the context it is
     * given, and throws when that binding lacks the infobase - so a default is recorded for a branch
     * only while it is checked out, unless the checked-out branch already has this base bound. The
     * note names that remedy;</li>
     * <li>anything else (an {@code InfobaseAssociationException}: storing the property or reading the
     * current context failed) is a platform failure. Checking the branch out would not fix it, so the
     * note only reports what EDT said; the caller logs it.</li>
     * </ul>
     *
     * @param applicationId the attached application id
     * @param target the branch the application was attached to
     * @param failure what EDT threw
     * @return the note, never {@code null}
     */
    public static String defaultNotSetNote(String applicationId, Target target, RuntimeException failure)
    {
        String attached = "Application '" + applicationId + "' was attached to branch '" + target.shortName() //$NON-NLS-1$ //$NON-NLS-2$
            + "' (context '" + target.fullRef() + "'), but "; //$NON-NLS-1$ //$NON-NLS-2$
        if (!(failure instanceof IllegalArgumentException))
        {
            return attached + "EDT failed to store it as the branch's default infobase: " //$NON-NLS-1$
                + PlatformFailures.describe(failure) + ". The attach stands; the EDT log has the details."; //$NON-NLS-1$
        }
        String reason = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
        return attached + "EDT did not record it as the branch's default infobase (EDT: " + reason //$NON-NLS-1$
            + "). EDT accepts a default only for an infobase that is also bound to the CHECKED-OUT branch: " //$NON-NLS-1$
            + "check this branch out (switch_git_branch) and call set_branch_infobase again with " //$NON-NLS-1$
            + "setDefault=true."; //$NON-NLS-1$
    }

    private static Resolution refused(String error)
    {
        return new Resolution(null, error);
    }
}
