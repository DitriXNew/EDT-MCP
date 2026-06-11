/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.debug.core.model.IDebugTarget;

/**
 * Single, unified entry point that resolves <em>any</em> form of debug
 * {@code applicationId} the MCP debug tools accept to the one underlying Eclipse
 * {@link IDebugTarget}.
 *
 * <h2>Why this exists</h2>
 * The same running debug session can be addressable under several different ids,
 * and different tools historically accepted different subsets:
 * <ul>
 *   <li>launch-based ids: the real {@code ATTR_APPLICATION_ID}, {@code attach:<name>},
 *       {@code launch:<name>} — resolved via
 *       {@link DebugSessionRegistry#findActiveTarget(String)};</li>
 *   <li>server-target ids: {@code ServerApplication.<app>}, the bare application
 *       name, the debug server URL — resolved via {@link DebugServerTargetSupport}.</li>
 * </ul>
 * This class removes the burden of remembering which id each tool wants: every
 * tool resolves through {@link #resolve(String)} and accepts every id form for the
 * same session.
 *
 * <h2>The key fact that makes one target serve everything</h2>
 * A standalone-server / UI-started debug session is backed by an EDT
 * {@code IRuntimeDebugClientTarget}, which is enumerated by
 * {@code IRuntimeDebugClientTargetManager.listDebugTargets()} (addressable as
 * {@code ServerApplication.<app>}) and also exposes {@code getLaunch()}, so the
 * Eclipse {@link org.eclipse.debug.core.ILaunch} that owns it surfaces the same
 * object via {@code launch.getDebugTargets()} — addressable as {@code launch:<name>}
 * or the real {@code ATTR_APPLICATION_ID}. In other words, the two id families are
 * two <em>views of one IDebugTarget object</em>; resolving any id to that object
 * makes wait/resume/step/variables/evaluate all work off the same session.
 *
 * <h2>Resolution order</h2>
 * {@link #resolve(String)} tries, in order:
 * <ol>
 *   <li>{@link DebugSessionRegistry#findActiveTarget(String)} — launch-based ids.</li>
 *   <li>{@link DebugServerTargetSupport#resolve(String)} — server-target ids.</li>
 *   <li>loose lone-session fallbacks when the id is blank: the single active
 *       Eclipse launch, else the single server target.</li>
 * </ol>
 * Every step is null-safe; an unresolvable id yields {@code null} (never throws).
 */
public final class DebugTargetResolver
{
    private DebugTargetResolver()
    {
        // utility class
    }

    /**
     * The outcome of resolving an {@code applicationId}: the underlying Eclipse
     * {@link IDebugTarget}, a canonical id the rest of the chain can reuse, and the
     * {@link DebugServerTargetSupport.ServerTarget} when the session was found
     * through the server-target view.
     */
    public static final class Resolution
    {
        /** The resolved debug target (never {@code null} in a returned Resolution). */
        public final IDebugTarget target;
        /**
         * The canonical, stable id for the resolved session — the SAME id
         * regardless of which id form the caller used and which view (launch
         * manager or 1C debug-server) located the target. It is the key the
         * {@link DebugSessionRegistry} uses for the session's suspend snapshots:
         * the id of the owning Eclipse launch when one exists (real
         * {@code ATTR_APPLICATION_ID} / {@code attach:<name>} / {@code launch:<name>}),
         * else the minted {@code ServerApplication.<app>} id. Never the caller's
         * raw input — echoing it produced inconsistent snapshot keys for the same
         * session. See {@link #canonicalIdFor}.
         */
        public final String canonicalId;
        /** The server-target view, or {@code null} if resolved purely via the launch manager. */
        public final DebugServerTargetSupport.ServerTarget serverTarget;
        /** {@code true} if the id was blank and a lone session was auto-selected. */
        public final boolean autoResolved;

        Resolution(IDebugTarget target, String canonicalId,
            DebugServerTargetSupport.ServerTarget serverTarget, boolean autoResolved)
        {
            this.target = target;
            this.canonicalId = canonicalId;
            this.serverTarget = serverTarget;
            this.autoResolved = autoResolved;
        }

        /** @return {@code true} if this resolution came through the 1C debug-server view. */
        public boolean isServerTarget()
        {
            return serverTarget != null;
        }
    }

    /**
     * Resolves any id form to a {@link Resolution}. Returns {@code null} when no
     * active session matches (or the id is blank and there is not exactly one
     * obvious session to auto-select). Never throws.
     *
     * @param applicationId the id to resolve (any form; may be {@code null}/empty)
     * @return the resolution, or {@code null} if nothing matches
     */
    public static Resolution resolve(String applicationId)
    {
        // 1) Launch-based view first for a concrete id: real ATTR_APPLICATION_ID,
        //    attach:<name>, launch:<name> — sessions owned by a registered Eclipse
        //    launch resolve here and wait event-driven via the suspend registry.
        if (applicationId != null && !applicationId.isEmpty())
        {
            IDebugTarget launchTarget = DebugSessionRegistry.findActiveTarget(applicationId);
            if (launchTarget != null && !launchTarget.isTerminated())
            {
                // A launch-owned target is exposed through the server-target view
                // only when it genuinely IS a server target (a debug-mode standalone
                // server — live SERVER-typed thread — whose suspends only the poll
                // bridge observes). A plain launch-owned client session yields null
                // here (listServerTargets excludes it), so it never takes the poll
                // path and never reports serverTarget:true.
                DebugServerTargetSupport.ServerTarget sameObject = serverTargetForTarget(launchTarget);
                return new Resolution(launchTarget,
                    canonicalIdFor(launchTarget, sameObject, applicationId), sameObject, false);
            }

            // 2) Server-target view: ServerApplication.<app>, bare app name, URL.
            //    Do NOT echo the caller's raw id and do NOT blindly use the minted
            //    ServerApplication.<app> id: when the same session is also owned by an
            //    Eclipse launch, the registry keys its snapshots by the LAUNCH id, and
            //    a second key form would split one session across two snapshot keys
            //    (otherwise resume cleared one key while wait_for_break read the
            //    other, returning a stale pre-resume snapshot as a fresh hit).
            DebugServerTargetSupport.ServerTarget st = DebugServerTargetSupport.resolve(applicationId);
            if (st != null && st.target != null && !st.target.isTerminated())
            {
                return new Resolution(st.target, canonicalIdFor(st.target, st, st.applicationId), st, false);
            }
            // Concrete id given but nothing matched — do NOT silently fall back to a
            // lone session; the caller asked for a specific session.
            return null;
        }

        // 3) Blank id — auto-resolve a single obvious session.
        String loneLaunchId = DebugSessionRegistry.findLoneActiveApplicationId();
        if (loneLaunchId != null)
        {
            IDebugTarget launchTarget = DebugSessionRegistry.findActiveTarget(loneLaunchId);
            if (launchTarget != null && !launchTarget.isTerminated())
            {
                DebugServerTargetSupport.ServerTarget sameObject = serverTargetForTarget(launchTarget);
                return new Resolution(launchTarget,
                    canonicalIdFor(launchTarget, sameObject, loneLaunchId), sameObject, true);
            }
        }
        DebugServerTargetSupport.ServerTarget lone = DebugServerTargetSupport.findLoneServerTarget();
        if (lone != null && lone.target != null && !lone.target.isTerminated())
        {
            return new Resolution(lone.target, canonicalIdFor(lone.target, lone, lone.applicationId), lone, true);
        }
        return null;
    }

    /**
     * Computes the ONE canonical snapshot key for a resolved debug target — the id
     * the {@link DebugSessionRegistry} uses for that target's suspend snapshots,
     * regardless of which view located it:
     * <ol>
     *   <li>if an owning Eclipse {@code ILaunch} with an EDT/1C configuration exists,
     *       the id that launch reports (real {@code ATTR_APPLICATION_ID},
     *       {@code attach:<name>} or {@code launch:<name>}) — exactly what the
     *       registry's event listener keys real SUSPEND events by;</li>
     *   <li>else the minted {@code ServerApplication.<app>} id of the server-target
     *       view (such targets never key into the launch-based listener, so their
     *       snapshots only ever live under the minted id).</li>
     * </ol>
     * Centralizing the policy here guarantees that wait_for_break, resume and step
     * inject/read/clear snapshots under the SAME key for the same session, whichever
     * id form each call used. Null-safe; never throws.
     *
     * @param target the resolved debug target (may be {@code null})
     * @param serverTarget the server-target view of the same object, or {@code null}
     * @return the canonical id, or {@code null} when neither view yields one
     */
    public static String canonicalIdFor(IDebugTarget target, DebugServerTargetSupport.ServerTarget serverTarget)
    {
        if (target != null)
        {
            try
            {
                String launchId = LaunchConfigUtils.getApplicationIdFor(target.getLaunch());
                if (launchId != null && !launchId.isEmpty())
                {
                    return launchId;
                }
            }
            catch (Exception ex)
            {
                // best-effort — fall through to the server-target view
            }
        }
        if (serverTarget != null && serverTarget.applicationId != null && !serverTarget.applicationId.isEmpty())
        {
            return serverTarget.applicationId;
        }
        return null;
    }

    /**
     * Same as {@link #canonicalIdFor(IDebugTarget, DebugServerTargetSupport.ServerTarget)}
     * with a defensive fallback for the (theoretical) case where neither view yields
     * an id — keeps {@link Resolution#canonicalId} non-null for resolved sessions.
     */
    private static String canonicalIdFor(IDebugTarget target,
        DebugServerTargetSupport.ServerTarget serverTarget, String fallback)
    {
        String canonical = canonicalIdFor(target, serverTarget);
        return canonical != null ? canonical : fallback;
    }

    /**
     * Returns the {@link DebugServerTargetSupport.ServerTarget} whose underlying 1C
     * target is the SAME object as {@code target} (identity match), or {@code null}
     * when {@code target} is not a server target — an ordinary Eclipse launch
     * target, including a launch-owned thin-client session that the target manager
     * also tracks but {@link DebugServerTargetSupport#listServerTargets()} excludes
     * (such sessions wait event-driven, not via the poll bridge).
     *
     * @param target the resolved debug target
     * @return the matching server-target view, or {@code null}
     */
    public static DebugServerTargetSupport.ServerTarget serverTargetForTarget(IDebugTarget target)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            for (DebugServerTargetSupport.ServerTarget st : DebugServerTargetSupport.listServerTargets())
            {
                if (st.target == target)
                {
                    return st;
                }
            }
        }
        catch (Exception ex)
        {
            // best-effort — treat as a non-server target
        }
        return null;
    }
}
