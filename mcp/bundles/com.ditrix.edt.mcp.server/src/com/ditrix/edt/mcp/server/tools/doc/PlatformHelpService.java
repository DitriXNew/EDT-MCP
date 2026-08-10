/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.doc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.platform.doc.PlatformDocPage;
import com._1c.g5.v8.dt.platform.doc.PlatformDocProvider;
import com._1c.g5.v8.dt.platform.doc.PlatformDocTree;
import com._1c.g5.v8.dt.platform.doc.PlatformDocTreeNode;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.google.inject.Injector;

/**
 * Reads the 1C:Enterprise SYNTAX HELPER (the platform's own documentation) for a type and its
 * members. Issue #299.
 *
 * <p>Why this exists: the {@code mcore} model a platform type is built from carries names,
 * parameter lists and flags, but NOT the prose - and not even the return value of every method
 * ({@code Chart.GetValue} is documented as returning "the chart value at the given point and
 * series", which the model renders as a bare type). Everything this class adds - what a method
 * returns, and the DESCRIPTION of a type, method or property - exists only in the syntax helper.
 *
 * <p>It adds nothing where the helper itself has nothing: {@code AccessToken.Sign}, the case the
 * report cited, is documented as a PROCEDURE with no return section at all, so no return is
 * rendered for it. Neither source knows one, and none is invented.
 *
 * <p><b>How it reaches the helper, and why that is best-effort.</b> {@link PlatformDocProvider} is a
 * published class, but nothing publishes an INSTANCE: it is not an OSGi service, its constructor
 * takes internal loaders, and the Guice injector that binds it lives in a package EDT does not
 * export ({@code com._1c.g5.v8.dt.bsl.ui.internal}). So the injector is reached REFLECTIVELY and
 * everything after that is normal typed code. Every failure - a renamed activator, a missing
 * binding, a headless workbench with no UI plugin started - is swallowed and simply yields "no
 * documentation": the caller then renders exactly what it rendered before. This must never turn a
 * working doc lookup into an error.
 */
public final class PlatformHelpService
{
    /** The page section holding a type's / member's prose. Mirrors {@code PlatformDocPage.DESCRIPTION}. */
    private static final String DESCRIPTION_SECTION = "description"; //$NON-NLS-1$

    /** The page section holding what a method returns. Mirrors {@code PlatformDocPage.RETURN}. */
    private static final String RETURN_SECTION = "return"; //$NON-NLS-1$

    /** The BSL language id whose Guice injector binds the platform doc provider. */
    private static final String BSL_LANGUAGE_ID = "com._1c.g5.v8.dt.bsl.Bsl"; //$NON-NLS-1$

    /** The bundle that owns the injector. Its class is loaded THROUGH the bundle, not through our
     * own class loader: the package is not exported, so {@code Class.forName} cannot see it. */
    private static final String BSL_UI_BUNDLE = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$

    /** The activator that owns the injector; not exported, hence reached reflectively. */
    private static final String BSL_ACTIVATOR = "com._1c.g5.v8.dt.bsl.ui.internal.BslActivator"; //$NON-NLS-1$

    /** How deep to look for a TYPE page from the tree root. */
    private static final int MAX_TYPE_SCAN_DEPTH = 8;

    /** How deep to look for a MEMBER page under its type's node. */
    private static final int MAX_MEMBER_SCAN_DEPTH = 4;

    /** Resolved once: the provider, or {@code null} when the syntax helper is unreachable. */
    private static PlatformDocProvider provider;

    private static boolean providerResolved;

    /** Cache of type name (lower-cased) to its doc node, per platform version. */
    private final Map<String, PlatformDocTreeNode> typeNodes = new HashMap<>();

    /** Cache of "type#member" to its doc node: one member is asked for its description AND its
     * return, and a subtree walk per question adds up to hundreds on a wide type. */
    private final Map<String, PlatformDocTreeNode> memberNodes = new HashMap<>();

    /** Cache of node path to the parsed page, for the same reason: one page, one parse. */
    private final Map<String, PlatformDocPage> pages = new HashMap<>();

    private final Version version;

    private final String language;

    /** {@code true} for the {@link #disabled()} instance: every lookup answers "no documentation". */
    private final boolean off;

    /**
     * An instance that reads nothing. Used by the CONCISE rendering, which discards every
     * description anyway - looking them up would walk the doc tree and parse a page per member on
     * the UI thread only to throw the result away.
     *
     * @return a service whose every lookup returns {@code null}
     */
    public static PlatformHelpService disabled()
    {
        return new PlatformHelpService(null, null, true);
    }

    /**
     * @param version the platform version whose documentation to read
     * @param language the documentation language ({@code "en"} / {@code "ru"})
     */
    public PlatformHelpService(Version version, String language)
    {
        this(version, language, false);
    }

    private PlatformHelpService(Version version, String language, boolean off)
    {
        this.version = version;
        this.language = language;
        this.off = off;
    }

    /**
     * Whether the syntax helper is reachable at all. When {@code false} every lookup returns
     * {@code null} and the caller renders what it always did.
     *
     * @return {@code true} when documentation can be read
     */
    public boolean isAvailable()
    {
        return !off && version != null && docProvider() != null;
    }

    /**
     * The documentation of a TYPE itself.
     *
     * @param typeName the English or Russian type name
     * @return the type's description, or {@code null} when the helper has none
     */
    public String typeDescription(String typeName)
    {
        return read(typeName, null, DESCRIPTION_SECTION);
    }

    /**
     * The documentation of one MEMBER of a type - a method, property or event.
     *
     * @param typeName the owning type's name
     * @param memberName the member's English or Russian name
     * @return the member's description, or {@code null} when the helper has none
     */
    public String memberDescription(String typeName, String memberName)
    {
        return read(typeName, memberName, DESCRIPTION_SECTION);
    }

    /**
     * What a METHOD returns, as the syntax helper states it - the wording the {@code mcore} model
     * does not carry, and for some methods the only place a return is recorded at all. {@code null}
     * for a method the documentation describes as a procedure.
     *
     * @param typeName the owning type's name
     * @param methodName the method's English or Russian name
     * @return the documented return value, or {@code null} when the helper has none
     */
    public String methodReturnValue(String typeName, String methodName)
    {
        return read(typeName, methodName, RETURN_SECTION);
    }

    /**
     * The single entry point every lookup goes through, and the single place a failure is absorbed.
     * <p>
     * It catches {@link LinkageError} as well as {@link Exception}: the {@code platform.doc} package
     * is an OPTIONAL import, so on an EDT that does not ship it the first touch of a helper type
     * raises {@code NoClassDefFoundError} - an Error, which would otherwise sail past every
     * {@code catch (Exception)} and fail the whole documentation request instead of quietly
     * degrading to the model-only view.
     *
     * @param typeName the owning type
     * @param memberName the member, or {@code null} to read the TYPE's own page
     * @param section the page section to read
     * @return the section text, or {@code null} when there is none
     */
    private String read(String typeName, String memberName, String section)
    {
        if (off)
        {
            return null;
        }
        try
        {
            PlatformDocTreeNode node =
                memberName == null ? findTypeNode(typeName) : findMemberNode(typeName, memberName);
            return valueOf(node, section);
        }
        catch (Exception | LinkageError e) // NOSONAR optional enrichment: never fail the caller's lookup
        {
            Activator.logInfo("Platform help lookup failed (" + e.getClass().getSimpleName() //$NON-NLS-1$
                + "): documentation falls back to the model-only view"); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Loads the page a doc node points at and reads one of its sections.
     *
     * @return the section text, or {@code null} when the node/page/section is absent
     */
    private String valueOf(PlatformDocTreeNode node, String section)
    {
        if (off)
        {
            return null;
        }
        PlatformDocProvider docProvider = docProvider();
        if (node == null || docProvider == null)
        {
            return null;
        }
        try
        {
            String path = node.getPath();
            PlatformDocPage page;
            if (pages.containsKey(path))
            {
                page = pages.get(path);
            }
            else
            {
                page = docProvider.loadPage(path, version, language);
                pages.put(path, page);
            }
            if (page == null)
            {
                return null;
            }
            return toPlainText(page.getValue(section));
        }
        catch (Exception e) // NOSONAR the syntax helper is an optional enrichment: never fail the lookup
        {
            Activator.logInfo("Platform help page could not be read: " + e); //$NON-NLS-1$
            return null;
        }
    }


    /**
     * Whether a node is the page of a TYPE rather than of one of some type's members.
     * <p>
     * Names repeat across the help tree: {@code AccessToken} is both a platform TYPE and a PROPERTY
     * of the internet-mail profile, and the property page is the one a plain name search hits first.
     * A member page always sits under a {@code methods} / {@code properties} / {@code events}
     * segment, so a node whose path carries one is never the type we are looking for.
     */
    private static boolean isTypePage(PlatformDocTreeNode node)
    {
        // A GROUPING CATALOG can carry the very name we are looking for - the help has a catalog
        // named "Query" beside the Query TYPE page - and, having children, it would otherwise win.
        // The member search would then walk the wrong subtree and find nothing, so the type would
        // silently lose every description. A type page is never a catalog.
        if (Boolean.TRUE.equals(node.getIsCatalog()))
        {
            return false;
        }
        String path = node.getPath();
        if (path == null)
        {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return !lower.contains("/methods/") && !lower.contains("/properties/") //$NON-NLS-1$ //$NON-NLS-2$
            && !lower.contains("/events/"); //$NON-NLS-1$
    }

    /**
     * Turns a syntax-helper section into plain text a caller can read.
     * <p>
     * The stored sections are HTML fragments: tags, entities, and the help's own
     * <code>{ссылка:...}</code> cross-reference markers. Rendering them raw put markup and
     * seven-levels-up relative hrefs straight into the answer, so they are stripped down to the
     * words - a link keeps its TEXT, everything else goes.
     *
     * @param html the raw section (may be {@code null})
     * @return readable one-paragraph text, or {@code null} when nothing is left
     */
    static String toPlainText(String html)
    {
        if (html == null || html.isBlank())
        {
            return null;
        }
        String text = html;
        // Keep a link's visible text, drop the anchor itself.
        text = text.replaceAll("(?is)<a[^>]*>(.*?)</a>", "$1"); //$NON-NLS-1$ //$NON-NLS-2$
        // Every BLOCK boundary is a word boundary: a list of chart kinds is stored as
        // <li>Area;</li><li>Stacked areas;</li>, and stripping those tags without a separator
        // would run the items together into "Area;Stacked areas;".
        text = text.replaceAll("(?i)<(br|/p|/li|/div|/tr|/td|/h[1-6])\\s*/?>", " "); //$NON-NLS-1$ //$NON-NLS-2$
        text = text.replaceAll("<[^>]+>", " "); //$NON-NLS-1$ //$NON-NLS-2$
        // The help's own cross-reference markers, e.g. {ссылка:Объекты; 772; ИнтернетПочта} - keep
        // the readable tail (the referenced name), drop the bookkeeping. ONLY that shape: braces
        // also carry real content the documentation means literally, such as the XDTO namespace
        // {http://v8.1c.ru/8.1/xdto} or a quantifier like {3,5}, which must survive verbatim.
        text = text.replaceAll("\\{[\\p{L}]+:[^{}]*;\\s*([^{};]+)\\}", "$1"); //$NON-NLS-1$ //$NON-NLS-2$
        text = text.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            .replace("&quot;", "\"").replace("&amp;", "&"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        text = text.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        return text.isEmpty() ? null : text;
    }

    /**
     * Finds the doc node of a type, caching the result (the tree holds thousands of nodes and a
     * single documentation render asks for the same type many times over).
     */
    private PlatformDocTreeNode findTypeNode(String typeName)
    {
        if (typeName == null || typeName.isEmpty() || !isAvailable())
        {
            return null;
        }
        String key = typeName.toLowerCase(Locale.ROOT);
        if (typeNodes.containsKey(key))
        {
            return typeNodes.get(key);
        }
        PlatformDocTreeNode found = null;
        try
        {
            PlatformDocTree tree = docProvider().getTree(version);
            if (tree != null && tree.getRootNode() != null)
            {
                found = searchByName(tree.getRootNode(), typeName, 0, false);
                if (found == null)
                {
                    // A metadata TYPE SET is filed under a QUALIFIED page name - the help documents
                    // CatalogObject as "CatalogObject.<Catalog name>" - so an exact search finds
                    // nothing for the very name a caller asks with (issue #355). Only ever a SECOND
                    // pass: an exact page must always win, or a type could lose its own description
                    // to a same-prefixed neighbour.
                    found = searchByName(tree.getRootNode(), typeName, 0, true);
                }
            }
        }
        catch (Exception e) // NOSONAR optional enrichment, see valueOf
        {
            Activator.logInfo("Platform help tree could not be walked: " + e); //$NON-NLS-1$
        }
        typeNodes.put(key, found);
        return found;
    }

    /** Finds a member node under the type's node, matching by the localized node name. */
    private PlatformDocTreeNode findMemberNode(String typeName, String memberName)
    {
        PlatformDocTreeNode typeNode = findTypeNode(typeName);
        if (typeNode == null || memberName == null || memberName.isEmpty())
        {
            return null;
        }
        String key = typeName.toLowerCase(Locale.ROOT) + '#' + memberName.toLowerCase(Locale.ROOT);
        if (memberNodes.containsKey(key))
        {
            return memberNodes.get(key);
        }
        PlatformDocTreeNode found = searchMember(typeNode, memberName, 0);
        memberNodes.put(key, found);
        return found;
    }

    /**
     * Bounded recursive search for a member page under a type's node. How deep a member sits varies:
     * AccessToken keeps its methods one level down, Chart groups them further - so the whole subtree
     * is searched rather than the nesting (or the localized name of the grouping catalogs) guessed.
     */
    private PlatformDocTreeNode searchMember(PlatformDocTreeNode node, String memberName, int depth)
    {
        if (depth > MAX_MEMBER_SCAN_DEPTH)
        {
            return null;
        }
        for (PlatformDocTreeNode child : children(node))
        {
            if (nameMatches(child, memberName) && !isTypePage(child))
            {
                return child;
            }
            PlatformDocTreeNode deeper = searchMember(child, memberName, depth + 1);
            if (deeper != null)
            {
                return deeper;
            }
        }
        return null;
    }

    /**
     * Depth-limited search for a node whose name matches, preferring a node that HAS children (a
     * type's page has members under it; a same-named leaf elsewhere in the tree does not).
     *
     * @param qualified when {@code true}, also accepts a page whose name is {@code name} followed by
     *            a dot - how the help files a metadata type set ({@code CatalogObject.<Catalog
     *            name>}). Reserved for a second pass, so an exact page always wins.
     */
    private PlatformDocTreeNode searchByName(PlatformDocTreeNode node, String name, int depth,
        boolean qualified)
    {
        if (depth > MAX_TYPE_SCAN_DEPTH)
        {
            return null;
        }
        PlatformDocTreeNode leafMatch = null;
        for (PlatformDocTreeNode child : children(node))
        {
            if ((nameMatches(child, name) || (qualified && qualifiedNameMatches(child, name)))
                && isTypePage(child))
            {
                if (child.hasChildren())
                {
                    return child;
                }
                leafMatch = child;
            }
            PlatformDocTreeNode deeper = searchByName(child, name, depth + 1, qualified);
            if (deeper != null)
            {
                return deeper;
            }
        }
        return leafMatch;
    }

    /**
     * Whether a node is the page of {@code name} filed under a QUALIFIED title - the help names the
     * page of a metadata type set after the set plus the metadata object it is parameterized by
     * ({@code CatalogObject.<Catalog name>} / {@code СправочникОбъект.<Имя справочника>}). The dot is
     * required, so {@code Catalog} never claims {@code CatalogObject}'s page.
     */
    private boolean qualifiedNameMatches(PlatformDocTreeNode node, String name)
    {
        for (String candidate : nodeNames(node))
        {
            if (candidate == null)
            {
                continue;
            }
            String trimmed = candidate.trim();
            if (trimmed.length() > name.length() + 1 && trimmed.charAt(name.length()) == '.'
                && trimmed.regionMatches(true, 0, name, 0, name.length()))
            {
                return true;
            }
        }
        return false;
    }

    /** The node's children, never {@code null}. */
    private static Collection<PlatformDocTreeNode> children(PlatformDocTreeNode node)
    {
        Collection<PlatformDocTreeNode> children = node != null ? node.getChildren() : null;
        return children != null ? children : List.of();
    }

    /**
     * The names a node answers to: its localized title plus both element names. Empty when the node
     * cannot be read at all - a failure here degrades to "no documentation", never to an error.
     */
    private List<String> nodeNames(PlatformDocTreeNode node)
    {
        List<String> candidates = new ArrayList<>();
        try
        {
            candidates.add(node.getName(language));
            candidates.add(node.getElementName(true));
            candidates.add(node.getElementName(false));
        }
        catch (Exception e) // NOSONAR optional enrichment, see valueOf
        {
            return List.of();
        }
        return candidates;
    }

    /**
     * Whether a node carries the given member/type name. The tree's node names are localized and a
     * method node is often named with its signature ("Sign(...)"), so the comparison accepts the
     * name followed by a bracket as well as an exact match.
     */
    private boolean nameMatches(PlatformDocTreeNode node, String name)
    {
        for (String candidate : nodeNames(node))
        {
            if (candidate == null)
            {
                continue;
            }
            String trimmed = candidate.trim();
            if (trimmed.equalsIgnoreCase(name))
            {
                return true;
            }
            int bracket = trimmed.indexOf('(');
            if (bracket > 0 && trimmed.substring(0, bracket).trim().equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The platform doc provider, resolved once per session.
     * <p>
     * Nothing publishes an instance of it, so the Guice injector that binds it is reached through
     * the BSL UI activator - a class EDT does not export, hence reflection. A failure here is not an
     * error: it only means the tool renders the model-only documentation it always did.
     */
    private static synchronized PlatformDocProvider docProvider()
    {
        if (providerResolved)
        {
            return provider;
        }
        try
        {
            Bundle bundle = Platform.getBundle(BSL_UI_BUNDLE);
            if (bundle == null)
            {
                // NOT latched: a bundle that is not started yet may well be by the next call, and
                // latching here would keep answering "no documentation" for the whole session.
                Activator.logInfo("Platform help unavailable: the BSL UI bundle is not installed"); //$NON-NLS-1$
                return null;
            }
            Class<?> activatorClass = bundle.loadClass(BSL_ACTIVATOR);
            Object activator = activatorClass.getMethod("getInstance").invoke(null); //$NON-NLS-1$
            if (activator == null)
            {
                // Also not latched, same reason: the plugin starts lazily.
                Activator.logInfo("Platform help unavailable: the BSL UI plugin is not started"); //$NON-NLS-1$
                return null;
            }
            Method getInjector = activatorClass.getMethod("getInjector", String.class); //$NON-NLS-1$
            Object injector = getInjector.invoke(activator, BSL_LANGUAGE_ID);
            if (injector instanceof Injector)
            {
                provider = ((Injector)injector).getInstance(PlatformDocProvider.class);
            }
            else
            {
                // Two different Guice bundles would make the cast fail while everything else looks
                // fine - say so explicitly rather than reporting a bare "no documentation".
                Activator.logInfo("Platform help unavailable: the BSL injector is not a " //$NON-NLS-1$
                    + "com.google.inject.Injector visible to this bundle"); //$NON-NLS-1$
            }
        }
        catch (Exception | LinkageError e) // NOSONAR optional enrichment: an EDT that moved this API must not break the tool
        {
            // LinkageError too, not just Exception: the platform.doc import is OPTIONAL, so on an
            // EDT without that package the typed calls below raise NoClassDefFoundError - an Error,
            // which would otherwise escape every handler and fail the whole documentation request.
            Activator.logInfo("Platform help unavailable (" + e.getClass().getSimpleName() //$NON-NLS-1$
                + "): documentation falls back to the model-only view"); //$NON-NLS-1$
        }
        // Latched only once something was decided: a successful resolution, or a failure that will
        // not fix itself (a moved API). The "not started yet" cases above return without latching.
        providerResolved = true;
        return provider;
    }
}
