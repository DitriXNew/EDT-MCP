/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.emf.common.util.Enumerator;

import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.md.compare.ParentSupportModeComparisonNode;
import com._1c.g5.v8.dt.md.compare.SupportSettingsComparisonNode;
import com._1c.g5.v8.dt.md.compare.UserSupportModeComparisonNode;

/**
 * Reads the three-way SUPPORT state of a compared metadata object out of the comparison tree.
 *
 * <p>The state is taken from the CHILD NODES the platform actually builds, and from nowhere else:
 * a {@code SupportSettingsComparisonNode} hangs under the metadata-object node and carries a
 * {@code UserSupportModeComparisonNode} and/or a {@code ParentSupportModeComparisonNode}, each of
 * which exposes {@code getMainValue()} / {@code getOtherValue()} / {@code getAncestorValue()}. The
 * accessors promised by the 2025.2 javadoc - a support flag read straight off the top node - are
 * NOT present on either shipped platform (2026.1.2 / 2026.2), so reading them would not compile
 * there and reading "no support settings" from their absence would be a lie. Measured, not assumed:
 * that javadoc is residue.</p>
 *
 * <p>Rendering is deliberately locale-free: the mode is rendered from the EMF
 * {@link Enumerator#getName() literal name}, never through a label provider that branches on
 * {@code Locale.getDefault()}.</p>
 *
 * <p>Every accessor here touches comparison-tree nodes, so the caller MUST already be inside the
 * comparison's own read boundary ({@code ComparisonEngine.read(...)}); this class opens none of its
 * own.</p>
 */
public final class SupportStateReader
{
    private SupportStateReader()
    {
        // Utility class
    }

    /**
     * The three-way support state of one compared object. A side whose object does not exist (or
     * whose value the platform left unset) renders as an empty string - never as a guess.
     */
    public static final class SupportState
    {
        /** Name of the parent (vendor) configuration, or {@code ""} when the platform left it unset. */
        public final String parentConfigurationName;
        /** {@code UserSupportMode} on the main side, or {@code ""}. */
        public final String mainUserMode;
        /** {@code UserSupportMode} on the other side, or {@code ""}. */
        public final String otherUserMode;
        /** {@code UserSupportMode} on the common-ancestor side, or {@code ""}. */
        public final String ancestorUserMode;
        /** {@code ParentSupportMode} on the main side, or {@code ""}. */
        public final String mainParentMode;
        /** {@code ParentSupportMode} on the other side, or {@code ""}. */
        public final String otherParentMode;
        /** {@code ParentSupportMode} on the common-ancestor side, or {@code ""}. */
        public final String ancestorParentMode;

        SupportState(String parentConfigurationName, String mainUserMode, String otherUserMode,
            String ancestorUserMode, String mainParentMode, String otherParentMode,
            String ancestorParentMode)
        {
            this.parentConfigurationName = parentConfigurationName;
            this.mainUserMode = mainUserMode;
            this.otherUserMode = otherUserMode;
            this.ancestorUserMode = ancestorUserMode;
            this.mainParentMode = mainParentMode;
            this.otherParentMode = otherParentMode;
            this.ancestorParentMode = ancestorParentMode;
        }

        /** @return {@code true} when at least one user-support-mode value was read. */
        public boolean hasUserMode()
        {
            return !mainUserMode.isEmpty() || !otherUserMode.isEmpty() || !ancestorUserMode.isEmpty();
        }

        /** @return {@code true} when at least one parent-support-mode value was read. */
        public boolean hasParentMode()
        {
            return !mainParentMode.isEmpty() || !otherParentMode.isEmpty()
                || !ancestorParentMode.isEmpty();
        }

        /** @return {@code true} when the node carried a support-settings child but no readable value. */
        public boolean isEmpty()
        {
            return !hasUserMode() && !hasParentMode() && parentConfigurationName.isEmpty();
        }
    }

    /**
     * Reads the support state carried by {@code node} itself (when it IS the support-settings node)
     * or by its direct support-settings child.
     *
     * @param node the comparison node to inspect; may be {@code null}
     * @return the support state, or {@code null} when this node carries no support-settings node at
     *         all (the normal case for an object outside vendor support)
     */
    public static SupportState read(ComparisonNode node)
    {
        SupportSettingsComparisonNode settings = findSettings(node);
        if (settings == null)
        {
            return null;
        }

        String parentConfigurationName = ""; //$NON-NLS-1$
        String mainUser = ""; //$NON-NLS-1$
        String otherUser = ""; //$NON-NLS-1$
        String ancestorUser = ""; //$NON-NLS-1$
        String mainParent = ""; //$NON-NLS-1$
        String otherParent = ""; //$NON-NLS-1$
        String ancestorParent = ""; //$NON-NLS-1$

        for (ComparisonNode child : children(settings))
        {
            if (child instanceof UserSupportModeComparisonNode)
            {
                UserSupportModeComparisonNode user = (UserSupportModeComparisonNode)child;
                parentConfigurationName = text(user.getParentConfigurationName());
                mainUser = literal(user.getMainValue());
                otherUser = literal(user.getOtherValue());
                ancestorUser = literal(user.getAncestorValue());
            }
            else if (child instanceof ParentSupportModeComparisonNode)
            {
                ParentSupportModeComparisonNode parent = (ParentSupportModeComparisonNode)child;
                mainParent = literal(parent.getMainValue());
                otherParent = literal(parent.getOtherValue());
                ancestorParent = literal(parent.getAncestorValue());
            }
        }

        return new SupportState(parentConfigurationName, mainUser, otherUser, ancestorUser,
            mainParent, otherParent, ancestorParent);
    }

    /**
     * Finds the support-settings node for {@code node}: the node itself when the caller expanded the
     * support-settings node directly, otherwise its first direct child of that type.
     */
    private static SupportSettingsComparisonNode findSettings(ComparisonNode node)
    {
        if (node instanceof SupportSettingsComparisonNode)
        {
            return (SupportSettingsComparisonNode)node;
        }
        for (ComparisonNode child : children(node))
        {
            if (child instanceof SupportSettingsComparisonNode)
            {
                return (SupportSettingsComparisonNode)child;
            }
        }
        return null;
    }

    /**
     * Direct children of {@code node}, tolerating a null node and a node whose child list the
     * platform has not materialised. The lazy tree is the caller's problem (it must prioritize and
     * wait on the node status first); this reader never reports an empty list as "no support".
     */
    private static List<ComparisonNode> children(ComparisonNode node)
    {
        if (node == null)
        {
            return Collections.emptyList();
        }
        List<ComparisonNode> result = new ArrayList<>();
        List<ComparisonNode> children = node.<ComparisonNode> getChildren();
        if (children == null)
        {
            return result;
        }
        for (ComparisonNode child : children)
        {
            if (child != null)
            {
                result.add(child);
            }
        }
        return result;
    }

    /**
     * Renders an EMF enumeration value by its literal NAME - deterministic across locales, unlike a
     * label provider.
     */
    private static String literal(Enumerator value)
    {
        return value == null ? "" : text(value.getName()); //$NON-NLS-1$
    }

    private static String text(String value)
    {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
