/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.List;
import java.util.Locale;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/**
 * The address of a project's own configuration ROOT - the {@link Configuration} object of a
 * configuration or an extension project - and the properties of it that {@code modify_metadata} sets
 * (issue #741: an extension's version had to be edited in {@code Configuration.mdo} by hand at every
 * release, because neither {@code get_metadata_details} nor {@code modify_metadata} could address it).
 *
 * <p>The canonical address is {@link #FQN}, EDT's own top-object FQN of the root:
 * {@code MdUtil.getTopMdObjectFullyQualifiedName} answers {@code Configuration} for the Configuration
 * EClass, so the export of the root and the markers of its top object are addressed by it as well.
 * Also accepted: the Russian token, case-insensitively, and {@code Configuration.<Name>} with the root's
 * own programmatic Name - the shape an agent types after the "Expected format: Type.Name" hint - matched
 * like a Name in modify_metadata and delete_metadata (ignoring case, with their one-way yo retry). Another Name
 * is refused with the real one, never answered with this project's root. A command-interface tail wins:
 * {@code Configuration.CommandInterface} and {@code Configuration.MainSectionCommandInterface} stay the
 * addresses of the configuration's command interfaces ({@link CommandInterfaceAddress}).</p>
 *
 * <p>Deliberately NOT a type of {@code MetadataTypeUtils} and NOT reachable from
 * {@link MetadataNodeResolver}'s {@code resolveExisting} / {@code resolveAddress} /
 * {@code resolveForCreate}: those back delete, rename, adopt, create and reference targets, and the root
 * would become deletable, adoptable and a reference target in every tool at once. The callers that need
 * the root ask for it here: {@code get_metadata_details}, {@code modify_metadata} and the vendor-support
 * guard of a change ({@link VendorSupportGuard}).</p>
 */
public final class ConfigurationRoot
{
    /** The canonical address of the root: EDT's own top-object FQN of a Configuration. */
    public static final String FQN = "Configuration"; //$NON-NLS-1$

    /** The English token, lower-cased for the case-insensitive match. */
    private static final String EN_TOKEN = "configuration"; //$NON-NLS-1$

    /** The Russian token ("konfiguraciya"), lower-cased, built from code points. */
    private static final String RU_TOKEN = MetadataLanguageUtils.cp(0x043a, 0x043e, 0x043d, 0x0444, 0x0438,
        0x0433, 0x0443, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f);

    private ConfigurationRoot()
    {
        // Utility class
    }

    /**
     * The properties of the root that {@code modify_metadata} sets: its identity text and the information
     * block {@code get_configuration_properties} reports, without its name. Named through
     * {@link MdClassPackage.Literals}, so a model rename fails the build instead of silently shrinking the
     * surface. A holder, so asking whether a token names the root never initializes the metadata package.
     *
     * <p>Why exactly these nine (the Configuration class is identical in EDT 2026.1 and 2026.2):</p>
     * <ul>
     * <li>they are what EDT's own editor lets a user edit: on an EXTENSION root
     * {@code AdoptedConfigurationRule} enables synonym, version, vendor, copyright, both information
     * addresses and the brief / detailed information, and {@code AdoptedMdObjectRule} the comment; on a
     * native root {@code ConfigurationRule} disables or hides none of them;</li>
     * <li>they persist on an extension root. EDT's {@code MdExtXmlFormatter} drops a native property of an
     * adopted object while the object's extension holds a same-named property state that is unset or
     * {@code None}. A {@code ConfigurationExtension} holds no state for eight of the nine; it holds one
     * for the synonym ({@code MdObjectExtension.synonym}), but the formatter's
     * {@code isStateDependentFeature} exempts the synonym of a {@code ConfigurationExtension}, so the root
     * synonym is always written. None of the nine can therefore report a write that the file never
     * receives.</li>
     * </ul>
     *
     * <p>What the editor also enables on an extension root stays closed here: {@code caption} and
     * {@code shortCaption} carry a property state, so the formatter would drop them while it is unset;
     * {@code scriptVariant} is copied from the base configuration when the root is adopted
     * ({@code ConfigurationAdopterParticipant}); {@code namePrefix}, {@code configurationExtensionPurpose},
     * {@code configurationExtensionCompatibilityMode} and
     * {@code keepMappingToExtendedConfigurationObjectsByIDs} are the extension's own settings, which stay
     * with EDT. The name is the root's identity (an extension's name is how the infobase knows it), and the
     * behaviour settings, the object collections, the pictures and the update-catalog address are not part
     * of this surface either.</p>
     */
    private static final class Settable
    {
        static final List<String> NAMES = List.of(
            MdClassPackage.Literals.MD_OBJECT__SYNONYM.getName(),
            MdClassPackage.Literals.MD_OBJECT__COMMENT.getName(),
            MdClassPackage.Literals.CONFIGURATION__VERSION.getName(),
            MdClassPackage.Literals.CONFIGURATION__VENDOR.getName(),
            MdClassPackage.Literals.CONFIGURATION__COPYRIGHT.getName(),
            MdClassPackage.Literals.CONFIGURATION__BRIEF_INFORMATION.getName(),
            MdClassPackage.Literals.CONFIGURATION__DETAILED_INFORMATION.getName(),
            MdClassPackage.Literals.CONFIGURATION__VENDOR_INFORMATION_ADDRESS.getName(),
            MdClassPackage.Literals.CONFIGURATION__CONFIGURATION_INFORMATION_ADDRESS.getName());

        private Settable()
        {
            // Holder
        }
    }

    /** What a root address resolved to: the root's node, or why it cannot be addressed here. */
    public static final class Resolution
    {
        /** The root as a top-level node (owner and feature {@code null}); {@code null} when refused. */
        public final MetadataNodeResolver.MetadataNode node;
        /** Why the address cannot be answered in this project; {@code null} when it resolved. */
        public final String error;

        private Resolution(MetadataNodeResolver.MetadataNode node, String error)
        {
            this.node = node;
            this.error = error;
        }
    }

    /**
     * Whether {@code token} is the root's type token, English or Russian, in any case.
     *
     * @param token the token, may be {@code null}
     * @return {@code true} for {@code Configuration} or the Russian token
     */
    public static boolean isToken(String token)
    {
        if (token == null)
        {
            return false;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        return t.equals(EN_TOKEN) || t.equals(RU_TOKEN);
    }

    /**
     * Whether {@code fqn} names a configuration root: the bare token, or the token and one non-blank Name
     * segment that is not a command-interface tail. Says nothing about whether the Name is this project's.
     *
     * @param fqn the requested address, may be {@code null}
     * @return {@code true} when the address has the shape of a root address
     */
    public static boolean isAddress(String fqn)
    {
        if (fqn == null)
        {
            return false;
        }
        String trimmed = fqn.trim();
        String[] parts = trimmed.split("\\.", -1); //$NON-NLS-1$
        if (parts.length == 1)
        {
            return isToken(parts[0]);
        }
        return parts.length == 2 && isToken(parts[0]) && !parts[1].trim().isEmpty()
            && !CommandInterfaceAddress.hasCommandInterfaceTail(trimmed);
    }

    /**
     * Resolves a root address against a project's resolution root.
     *
     * @param scope the project's resolution root
     * @param fqn the requested address, may be {@code null}
     * @return {@code null} when {@code fqn} is not a root address; otherwise the root's node, or the
     *     reason it cannot be addressed in this project - an external-objects project (whose configuration
     *     is the LINKED base, never its own root), a project without a configuration, or a Name that is
     *     not this configuration's
     */
    public static Resolution resolve(MetadataScope scope, String fqn)
    {
        if (!isAddress(fqn))
        {
            return null;
        }
        if (scope != null && scope.isExternalObjects())
        {
            return new Resolution(null, externalObjectsRefusal(scope));
        }
        Configuration configuration = scope == null ? null : scope.configuration();
        if (configuration == null)
        {
            return new Resolution(null, "The project has no configuration loaded, so '" + FQN //$NON-NLS-1$
                + "' cannot be resolved. Retry once list_projects reports the project ready."); //$NON-NLS-1$
        }
        String[] parts = fqn.trim().split("\\.", -1); //$NON-NLS-1$
        if (parts.length == 2)
        {
            String name = parts[1].trim();
            if (!namesConfiguration(name, configuration))
            {
                return new Resolution(null, "'" + fqn.trim() + "' names a configuration called '" + name //$NON-NLS-1$ //$NON-NLS-2$
                    + "', but the configuration of this project is '" + configuration.getName() //$NON-NLS-1$
                    + "'. Address its root as '" + FQN + "'."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return new Resolution(new MetadataNodeResolver.MetadataNode(configuration, null, null, true), null);
    }

    /**
     * Whether {@code name} names {@code configuration}: by the one rule every Name is found by
     * ({@link MetadataTypeUtils#findByName}, ignoring case), with the yo retry modify_metadata and
     * delete_metadata resolve an address with ({@link MetadataNodeResolver#yoRetryFqn}) - so a Name spelt with
     * yo names a root whose Name is stored with ye. One-way like that retry: it normalizes the address, never
     * the stored Name.
     */
    private static boolean namesConfiguration(String name, Configuration configuration)
    {
        List<Configuration> root = List.of(configuration);
        if (MetadataTypeUtils.findByName(root, name) != null)
        {
            return true;
        }
        String retry = MetadataNodeResolver.yoRetryFqn(name);
        return retry != null && MetadataTypeUtils.findByName(root, retry) != null;
    }

    /**
     * The properties of the root {@code modify_metadata} sets, in the order a refusal names them.
     *
     * @return the nine property names (unmodifiable)
     */
    public static List<String> settableProperties()
    {
        return Settable.NAMES;
    }

    /**
     * Whether {@code name} is one of the root's settable properties, in any case.
     *
     * @param name the property name, may be {@code null}
     * @return {@code true} for one of {@link #settableProperties()}
     */
    public static boolean isSettable(String name)
    {
        if (name == null)
        {
            return false;
        }
        for (String settable : Settable.NAMES)
        {
            if (settable.equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The refusal of a property the root does not take through {@code modify_metadata}.
     *
     * @param name the refused property name
     * @return the actionable message: what is settable, and what stays with EDT
     */
    public static String notSettableMessage(String name)
    {
        return "'" + name + "' is not settable on the configuration root ('" + FQN + "') through " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "modify_metadata. Settable there: " + String.join(", ", Settable.NAMES) + ". The root's " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "name, the properties that change how the configuration behaves (compatibility mode, " //$NON-NLS-1$
            + "script variant, default language, default roles, run, lock and interface modes, ...), its " //$NON-NLS-1$
            + "object collections and an extension's own settings " //$NON-NLS-1$
            + "(keepMappingToExtendedConfigurationObjectsByIDs, namePrefix, configurationExtensionPurpose, " //$NON-NLS-1$
            + "configurationExtensionCompatibilityMode) are changed in EDT."; //$NON-NLS-1$
    }

    /** Why an external-objects project has no root to address; says only what the scope knows. */
    private static String externalObjectsRefusal(MetadataScope scope)
    {
        IProject project = scope.project();
        String subject = project == null ? "This is an external-objects project" //$NON-NLS-1$
            : "Project '" + project.getName() + "' is an external-objects project"; //$NON-NLS-1$ //$NON-NLS-2$
        String base = scope.baseProjectName();
        String linked;
        if (base != null)
        {
            linked = " (it is linked to the configuration project '" + base + "')"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            linked = scope.configuration() != null ? " (it is linked to a base configuration)" : ""; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String where = base != null ? "in that configuration project" //$NON-NLS-1$
            : "in a configuration or extension project"; //$NON-NLS-1$
        return subject + ": it has no configuration root of its own" + linked + ". Address '" + FQN + "' " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + where + "."; //$NON-NLS-1$
    }
}
