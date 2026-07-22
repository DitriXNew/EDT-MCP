/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.QName;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com._1c.g5.v8.dt.xdto.model.Enumeration;
import com._1c.g5.v8.dt.xdto.model.Import;
import com._1c.g5.v8.dt.xdto.model.ObjectType;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com._1c.g5.v8.dt.xdto.model.Type;
import com._1c.g5.v8.dt.xdto.model.ValueType;
import com._1c.g5.v8.dt.xdto.model.XdtoFactory;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Authors the MEMBERS (ObjectTypes and Properties) of an XDTO Package (issue #183 stream 1) - the
 * {@link Package} model behind an {@code XDTOPackage}'s lazy {@code @ExternalProperty} content, reached
 * by the caller inside a BM boundary from {@code XDTOPackage.getPackage()} (the same transient
 * {@code @ExternalProperty} shape {@link DcsWriter} authors for a report's Data Composition Schema and
 * {@code SpreadsheetTemplateWriter} authors for a template's spreadsheet content).
 *
 * <p>Unlike {@link DcsWriter} (a bulk JSON spec applied wholesale to a schema), an XDTO Package's
 * CONTENTS are addressed one member at a time, by a 1C full-name FQN - the SAME per-member addressing
 * {@code create_metadata} / {@code modify_metadata} / {@code delete_metadata} already use for a
 * mdclass member (an Attribute, a TabularSection, ...). Because an XDTO {@link ObjectType} / {@link
 * Property} is NOT an {@code MdObject} (they live in the wholly separate, PUBLIC (not
 * access-restricted) {@code com._1c.g5.v8.dt.xdto.model} package), {@link MetadataNodeResolver} cannot
 * see them, so this class provides its own small FQN grammar ({@link #parseMemberRef}) mirroring the
 * {@code Type.Name.Kind.Name(.Kind.Name)*} shape:</p>
 *
 * <ul>
 * <li>{@code XDTOPackage.<Package>.ObjectType.<Name>} - an ObjectType in the package
 * ({@link Kind#OBJECT_TYPE});</li>
 * <li>{@code XDTOPackage.<Package>.Property.<Name>} - a PACKAGE-GLOBAL property ({@code Package.getProperties()},
 * {@link Kind#PACKAGE_PROPERTY});</li>
 * <li>{@code XDTOPackage.<Package>.ObjectType.<Type>.Property.<Name>} - a property nested in an
 * ObjectType ({@code ObjectType.getProperties()}, {@link Kind#OBJECT_TYPE_PROPERTY}).</li>
 * </ul>
 *
 * <p>Every mutator here uses the TYPED xdto.model API directly ({@code XdtoFactory.eINSTANCE}, typed
 * getters/setters) - never a reflective {@code eSet} - because, unlike the DCS "default settings" subtree
 * or the form content model, the xdto.model package is NOT Tycho access-restricted (see the Property /
 * ObjectType / Package javadoc - all public API), so there is no reason to fall back to reflection.</p>
 *
 * <p>A Property's {@code type} is an mcore {@link QName} (a {@code {nsUri, name}} pair). {@link #resolveQName}
 * accepts either the explicit object form {@code {nsUri, name}} (its {@code nsUri} must be the XSD
 * namespace, the package's own namespace, or a namespace reachable via a {@code Package.getDependencies()}
 * {@link Import}) or a shorthand STRING: an EXACT (case-sensitive) name match against an ObjectType
 * already in the SAME package resolves to a same-package reference ({@code nsUri} = the package's own
 * {@code nsUri}); any other string must be a whitelisted XSD built-in datatype name
 * ({@link #XSD_BUILTIN_TYPES}, under {@code http://www.w3.org/2001/XMLSchema}) - a bare string matching
 * neither is REJECTED (never silently turned into an invalid {@code xs:<typo>} reference). {@code ref}
 * (a reference to a GLOBAL PROPERTY - a different XDTO concept) is NOT supported for v1 - see
 * {@link #PROPERTY_PROPS}'s javadoc.</p>
 *
 * <p>Does NOT open a transaction and does NOT force-export - the caller ({@code CreateMetadataTool} /
 * {@code ModifyMetadataTool} / {@code DeleteMetadataTool}) reaches the {@link Package} inside its own BM
 * write boundary (materializing + {@code attachTopObject}-ing it exactly like a DCS schema / a
 * spreadsheet template) and drains it to the sibling {@code .xdto} after this returns.</p>
 */
public final class XdtoWriter
{
    /** Canonical English singular type token for the XDTOPackage top object (mirrors CreateMetadataTool). */
    private static final String TYPE_XDTO_PACKAGE = "XDTOPackage"; //$NON-NLS-1$

    /** FQN kind token addressing an ObjectType member. */
    private static final String KIND_OBJECT_TYPE = "ObjectType"; //$NON-NLS-1$

    /** FQN kind token addressing a Property member (package-global or nested in an ObjectType). */
    private static final String KIND_PROPERTY = "Property"; //$NON-NLS-1$

    /** The standard XML Schema namespace, used for the primitive-type-name QName shorthand. */
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema"; //$NON-NLS-1$

    /**
     * The full set of XSD built-in datatype local names (19 primitives + 25 derived) accepted for the
     * bare-STRING {@code type}/{@code ref} shorthand. Case-SENSITIVE (matches XSD's own canonical
     * casing, e.g. {@code "dateTime"} not {@code "datetime"}) - a name outside this set is rejected
     * rather than silently becoming an invalid {@code xs:<typo>} reference.
     */
    private static final Set<String> XSD_BUILTIN_TYPES = setOf(
        // primitives
        "string", "boolean", "decimal", "float", "double", "duration", "dateTime", "time", "date", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
        "gYearMonth", "gYear", "gMonthDay", "gDay", "gMonth", "hexBinary", "base64Binary", "anyURI", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        "QName", "NOTATION", //$NON-NLS-1$ //$NON-NLS-2$
        // derived
        "normalizedString", "token", "language", "NMTOKEN", "NMTOKENS", "Name", "NCName", "ID", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        "IDREF", "IDREFS", "ENTITY", "ENTITIES", "integer", "nonPositiveInteger", "negativeInteger", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "long", "int", "short", "byte", "nonNegativeInteger", "unsignedLong", "unsignedInt", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "unsignedShort", "unsignedByte", "positiveInteger", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // the two XSD/EDT root ur-types (codex review residual #2/finding #4: a valid shorthand, not a typo)
        "anySimpleType", "anyType"); //$NON-NLS-1$ //$NON-NLS-2$

    /** ObjectType assignable property names (the boolean content-model flags). */
    private static final Set<String> OBJECT_TYPE_PROPS =
        setOf("open", "abstract", "mixed", "ordered", "sequenced"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /**
     * Property assignable property names. {@code ref} (a reference to a GLOBAL PROPERTY - a different
     * XDTO concept from a {@code type} reference to a type) is DELIBERATELY excluded for v1 (codex review
     * residual #3 / finding #5): rejected explicitly by {@link #applyPropertyProperties} with a
     * "not yet supported" error rather than silently accepted half-broken (unusable on create since
     * {@code type} alone satisfies the required-attribute check but a caller meaning to use {@code ref}
     * would get no type; and unchecked-target on modify - no verification the referenced global property
     * even exists). A future increment can reintroduce it with real target-existence validation.
     */
    private static final Set<String> PROPERTY_PROPS =
        setOf("type", "lowerBound", "upperBound", "nillable", "fixed", "default"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    private static Set<String> setOf(String... values)
    {
        return new LinkedHashSet<>(List.of(values));
    }

    private XdtoWriter()
    {
        // Utility class
    }

    // ==================== FQN member addressing ====================

    /** The kind of XDTO member an {@link MemberRef} addresses. */
    public enum Kind
    {
        /** {@code XDTOPackage.<Package>.ObjectType.<Name>}. */
        OBJECT_TYPE,
        /** {@code XDTOPackage.<Package>.Property.<Name>} (package-global). */
        PACKAGE_PROPERTY,
        /** {@code XDTOPackage.<Package>.ObjectType.<Type>.Property.<Name>}. */
        OBJECT_TYPE_PROPERTY
    }

    /**
     * A parsed XDTO member FQN: the owning package's own (2-part) FQN, the addressed ObjectType /
     * Property names (whichever apply to {@link #kind}), and the {@link Kind}.
     */
    public static final class MemberRef
    {
        /** Normalized 2-part FQN of the owning XDTOPackage, e.g. {@code "XDTOPackage.MyPackage"}. */
        public final String packageFqn;
        /** The ObjectType name; non-{@code null} for {@link Kind#OBJECT_TYPE} / {@link Kind#OBJECT_TYPE_PROPERTY}. */
        public final String objectTypeName;
        /** The Property name; non-{@code null} for {@link Kind#PACKAGE_PROPERTY} / {@link Kind#OBJECT_TYPE_PROPERTY}. */
        public final String propertyName;
        public final Kind kind;

        MemberRef(String packageFqn, String objectTypeName, String propertyName, Kind kind)
        {
            this.packageFqn = packageFqn;
            this.objectTypeName = objectTypeName;
            this.propertyName = propertyName;
            this.kind = kind;
        }

        /** The addressed member's own name (the trailing FQN segment), for messages / result payloads. */
        public String memberName()
        {
            return kind == Kind.OBJECT_TYPE ? objectTypeName : propertyName;
        }
    }

    /**
     * Parses an XDTO member FQN (already normalized by {@link MetadataTypeUtils#normalizeFqn}) into a
     * {@link MemberRef}, or {@code null} when {@code normFqn} does not address an XDTO member (a
     * top-level {@code XDTOPackage.<Name>} FQN, or any non-XDTOPackage FQN) - the caller then falls
     * through to the generic mdclass path, mirroring {@code FormElementWriter.parse}'s null-means-not-mine
     * contract.
     *
     * @param normFqn the normalized FQN
     * @return the parsed member reference, or {@code null} when not an XDTO member FQN
     */
    public static MemberRef parseMemberRef(String normFqn)
    {
        if (normFqn == null || normFqn.isEmpty())
        {
            return null;
        }
        String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        if (parts.length != 4 && parts.length != 6)
        {
            return null;
        }
        String type = MetadataTypeUtils.toEnglishSingular(parts[0]);
        if (!TYPE_XDTO_PACKAGE.equals(type))
        {
            return null;
        }
        String packageFqn = TYPE_XDTO_PACKAGE + "." + parts[1]; //$NON-NLS-1$
        if (parts.length == 4)
        {
            if (KIND_OBJECT_TYPE.equalsIgnoreCase(parts[2]))
            {
                return new MemberRef(packageFqn, parts[3], null, Kind.OBJECT_TYPE);
            }
            if (KIND_PROPERTY.equalsIgnoreCase(parts[2]))
            {
                return new MemberRef(packageFqn, null, parts[3], Kind.PACKAGE_PROPERTY);
            }
            return null;
        }
        // parts.length == 6: XDTOPackage.<Package>.ObjectType.<Type>.Property.<Name>
        if (!KIND_OBJECT_TYPE.equalsIgnoreCase(parts[2]) || !KIND_PROPERTY.equalsIgnoreCase(parts[4]))
        {
            return null;
        }
        return new MemberRef(packageFqn, parts[3], parts[5], Kind.OBJECT_TYPE_PROPERTY);
    }

    // ==================== persistence (materialize the @ExternalProperty content) ====================
    //
    // XDTOPackage.getPackage() is a transient @ExternalProperty - the SAME lazy-external-property shape
    // BasicTemplate.getTemplate() is for a moxel template / a report's DCS (#245 / #241). Unlike those,
    // ALL THREE write tools (create/modify/delete_metadata) need to reach an XDTO Package's content - the
    // DCS content, by contrast, is reached from exactly one call site (ModifyMetadataTool's `dcs`
    // payload branch). So the materialize + attachTopObject step is centralized HERE (not duplicated
    // three times per tool) rather than copied into ModifyMetadataTool's private
    // resolveSpreadsheetContent/resolveDcsContent pair, which stay untouched (those still each have only
    // ONE caller).

    /**
     * The outcome of {@link #resolvePackageContent}: exactly one of ({@link #content} + {@link #contentFqn})
     * / {@link #error} is non-null/non-empty. {@link #contentFqn} is ALWAYS the content's own canonical
     * top-object FQN when {@link #content} is non-null - ALWAYS derived from the OWNER via
     * {@link ITopObjectFqnGenerator#generateExternalPropertyFqn}, NEVER read via {@code bmGetFqn()} on the
     * content object itself (see {@link #resolvePackageContent}'s javadoc for why) - so callers building
     * the dual force-export list must use THIS field, never call {@code bmGetFqn()} on {@link #content}
     * themselves.
     */
    public static final class ContentResolution
    {
        public final Package content;
        /** The content's own canonical top-object FQN; non-empty whenever {@link #content} is non-null. */
        public final String contentFqn;
        public final String error;

        private ContentResolution(Package content, String contentFqn, String error)
        {
            this.content = content;
            this.contentFqn = contentFqn;
            this.error = error;
        }

        static ContentResolution of(Package content, String contentFqn)
        {
            return new ContentResolution(content, contentFqn, null);
        }

        static ContentResolution failed(String error)
        {
            return new ContentResolution(null, null, error);
        }
    }

    /**
     * Resolves the {@link Package} content of an in-transaction {@link XDTOPackage}, materializing an
     * empty one when the package has none usable yet. Mirrors {@code ModifyMetadataTool}'s
     * {@code resolveDcsContent} / {@code resolveSpreadsheetContent}: a fresh/never-authored XDTOPackage
     * has NO usable content (either {@code getPackage() == null} or a non-attached placeholder), so a
     * fresh {@link Package} is built via {@link XdtoFactory#createPackage()} and ATTACHED as a BM top
     * object under its generated external-property FQN - else committing the write fails "Failed to
     * persist reference value" (the #245 lesson). An EXISTING, already-attached content is reused as-is.
     * MUST run inside the write boundary ({@code tx.attachTopObject}, {@code bmIsTop()} are only legal
     * there).
     *
     * <p>The owning {@code XDTOPackage}'s own {@code namespace} (mdclass) property is the SINGLE SOURCE
     * OF TRUTH for the target namespace: the content {@link Package}'s {@code nsUri} is RE-SYNCED to it on
     * every resolution whenever the two differ (not merely when the content's own {@code nsUri} is
     * missing) - never left {@code null} - so a same-package QName shorthand ({@link #resolveQName},
     * {@code qname.setNsUri(pkg.getNsUri())}) never writes a broken {@code null}-namespace reference and
     * the serialized {@code .xdto} always declares the CURRENT target namespace. This matters because
     * {@code create_metadata XDTOPackage.<Name>} eagerly materializes the content at package-create time
     * (seeded with the namespace as it stood THEN); a later {@code modify_metadata} of the mdclass
     * {@code namespace} updates only the owner, so without this re-sync the content's {@code targetNamespace}
     * would go stale and members would be authored under the OLD namespace. Running on BOTH paths (fresh
     * and already-attached) also covers an XML-imported / adopted package whose owner namespace was cleared
     * or changed after the content was first authored. A metadata package with NO namespace set at all
     * (should not happen for a {@code create_metadata}-made package, which always defaults one, but is
     * possible for an XML-imported / adopted one) is rejected up front rather than silently materializing
     * or reusing a nsUri-less package.</p>
     *
     * <p><b>The content FQN is ALWAYS derived from the OWNER via the generator, NEVER read via
     * {@code bmGetFqn()} on the content object itself</b> (three live-stand findings, post-review, fixed
     * the wrong way twice before landing here - see the trail below):</p>
     * <ul>
     * <li>Calling {@code bmGetFqn()} on a {@link Package} THIS SAME transaction just
     * {@code attachTopObject}-ed throws {@code BmAssertionException} - {@code bmIsTop()} is already
     * {@code true} right after attach, but the object is not yet "settled" enough for a
     * {@code bmGetFqn()} read within the SAME transaction.</li>
     * <li>{@code create_metadata XDTOPackage.<Name>} already auto-materializes an EMPTY content on the
     * TOP-object create - so the very FIRST member write already sees an EXISTING, {@code bmIsTop()==true}
     * content, and calling {@code bmGetFqn()} on THAT ("existing path is safe, it was settled by a prior
     * committed tx" - WRONG) throws the SAME assertion: {@code bmIsTop()} and
     * "attached-enough-for-{@code bmGetFqn()}" are DIFFERENT states, and a {@code getPackage()}-returned
     * content can satisfy the former while still failing the latter, regardless of which transaction
     * attached it.</li>
     * <li>Fixing the above by hoisting ONE {@code generateExternalPropertyFqn} call before the
     * existing-vs-fresh branch ("it is a pure function of the OWNER, order doesn't matter") was ALSO
     * wrong in practice: it made the FRESH branch call the generator BEFORE {@code txPkg.setPackage(pkg)} -
     * unlike every proven-working sibling ({@code ModifyMetadataTool.resolveDcsContent} /
     * {@code resolveSpreadsheetContent} / {@code FormElementWriter}'s content-form attach), which ALL
     * generate the external-property FQN AFTER the owner's reference already points at the fresh content,
     * THEN {@code attachTopObject}. With the generator called before the reference was set, an EXISTING
     * (real, platform-loaded) package persisted correctly on live re-test, but a BRAND-NEW package's first
     * member never made it to disk - and every subsequent create silently re-materialized ANOTHER fresh,
     * empty {@link Package}, discarding the previous one: {@code attachTopObject} "succeeded" (no
     * exception, the reference reads back fine within the model) but did not durably register the object
     * as a force-exportable top object under that FQN. The fix restores the EXACT proven order per branch:
     * point the reference at the target content FIRST, THEN generate its FQN, THEN (fresh only)
     * attach.</li>
     * </ul>
     * <p>{@code bmGetFqn()} is never called on the content in this class - do not reintroduce it.</p>
     *
     * @param txPkg the XDTOPackage re-fetched inside the write transaction
     * @param tx the active write transaction
     * @param fqnGenerator the external-property FQN generator
     * @return the resolution - check {@link ContentResolution#error} first
     */
    public static ContentResolution resolvePackageContent(XDTOPackage txPkg, IBmTransaction tx,
        ITopObjectFqnGenerator fqnGenerator)
    {
        Package existing = txPkg.getPackage();
        if (existing instanceof IBmObject && ((IBmObject)existing).bmIsTop())
        {
            String namespaceError = ensureNamespace(txPkg, existing);
            if (namespaceError != null)
            {
                return ContentResolution.failed(namespaceError);
            }
            // The reference ALREADY points at `existing` - generate AFTER, mirroring the proven order.
            String contentFqn =
                fqnGenerator.generateExternalPropertyFqn(txPkg, MdClassPackage.Literals.XDTO_PACKAGE__PACKAGE);
            if (contentFqn == null || contentFqn.isEmpty())
            {
                return ContentResolution.failed(ToolResult.error("Could not generate the content resource " //$NON-NLS-1$
                    + "FQN for the XDTO package; report it with the package FQN.").toJson()); //$NON-NLS-1$
            }
            return ContentResolution.of(existing, contentFqn);
        }
        String namespaceError = ensureNamespace(txPkg, null);
        if (namespaceError != null)
        {
            return ContentResolution.failed(namespaceError);
        }
        Package pkg = XdtoFactory.eINSTANCE.createPackage();
        pkg.setNsUri(txPkg.getNamespace());
        // SET the reference to the fresh content FIRST, THEN generate its FQN, THEN attach - the exact
        // order ModifyMetadataTool.resolveDcsContent / resolveSpreadsheetContent / FormElementWriter's
        // content-form attach all use (see the javadoc trail above for why the order matters here).
        txPkg.setPackage(pkg);
        String contentFqn =
            fqnGenerator.generateExternalPropertyFqn(txPkg, MdClassPackage.Literals.XDTO_PACKAGE__PACKAGE);
        if (contentFqn == null || contentFqn.isEmpty())
        {
            return ContentResolution.failed(ToolResult.error("Could not generate the content resource FQN " //$NON-NLS-1$
                + "for the XDTO package; report it with the package FQN.").toJson()); //$NON-NLS-1$
        }
        tx.attachTopObject((IBmObject)pkg, contentFqn);
        return ContentResolution.of(pkg, contentFqn);
    }

    /**
     * Requires {@code txPkg.getNamespace()} to be non-empty (a ready JSON error otherwise) and, when
     * {@code content} is supplied and its {@code nsUri} differs from the owner namespace, RE-SYNCS it to
     * the owner (the owner is the single source of truth - this propagates a namespace changed AFTER the
     * content was materialized, not only a missing one). Pass {@code content == null} for the
     * fresh-materialize path (nothing to sync yet - the caller seeds the freshly-created
     * {@link Package}'s {@code nsUri} itself once this returns {@code null}).
     *
     * @return a ready {@link ToolResult#error} JSON when the owner has no namespace set, else {@code null}
     */
    private static String ensureNamespace(XDTOPackage txPkg, Package content)
    {
        String namespace = txPkg.getNamespace();
        if (namespace == null || namespace.isEmpty())
        {
            return ToolResult.error("XDTOPackage '" + safeName(txPkg) //$NON-NLS-1$
                + "' has no target namespace set; set one first (modify_metadata with a 'namespace' " //$NON-NLS-1$
                + "property) before authoring its content.").toJson(); //$NON-NLS-1$
        }
        if (content != null && !namespace.equals(content.getNsUri()))
        {
            content.setNsUri(namespace);
        }
        return null;
    }

    private static String safeName(XDTOPackage txPkg)
    {
        String name = txPkg.getName();
        return name != null ? name : "?"; //$NON-NLS-1$
    }

    // ==================== find / create / remove ====================

    /**
     * Finds an ObjectType by EXACT (case-SENSITIVE) name, or {@code null} when {@code pkg} is
     * {@code null} or has none by that name. Unlike a 1C mdclass member name (matched
     * case-insensitively elsewhere in this codebase, e.g. {@link MetadataNodeResolver}), an XDTO/XML
     * QName local name is case-SENSITIVE: {@code "Order"} and {@code "order"} are different names, so a
     * case-insensitive match would wrongly collide two distinct members or make a case-distinct member
     * uncreatable.
     *
     * <p>On an EXACT miss, retries once with {@code name} run through {@link MdNameNormalizer#normalizeYo}
     * (issue #183 P2): {@code create_metadata} normalizes 'yo'->'ye' in a member's own NAME segment by
     * default (the FQN LEAF only - {@code CreateMetadataTool#normalizeLeafName}), so an ObjectType created
     * from an FQN spelled with the original 'yo' is stored under its 'ye'-normalized name. A LATER
     * member-FQN request (modify/delete, or a create addressing this ObjectType as the OWNER segment of a
     * nested Property FQN - never the leaf, so never normalized on the way in) that still spells the name
     * with 'yo' would otherwise miss it entirely and report "not found" for a member that in fact exists.
     * The retry is a no-op (and therefore free) whenever {@code name} carries no 'yo' at all.</p>
     */
    public static ObjectType findObjectType(Package pkg, String name)
    {
        if (pkg == null || name == null)
        {
            return null;
        }
        for (ObjectType type : pkg.getObjects())
        {
            if (name.equals(type.getName()))
            {
                return type;
            }
        }
        String yoNormalized = MdNameNormalizer.normalizeYo(name);
        if (!name.equals(yoNormalized))
        {
            for (ObjectType type : pkg.getObjects())
            {
                if (yoNormalized.equals(type.getName()))
                {
                    return type;
                }
            }
        }
        return null;
    }

    /**
     * Finds a LOCAL type (an {@link ObjectType} from {@code Package.getObjects()} OR a
     * {@link ValueType} from {@code Package.getTypes()}) by name, with the same yo fallback
     * {@link #findObjectType} applies. XDTO property QNames may legitimately target a local
     * value type (e.g. an imported package's enumerations), not only object types.
     *
     * @return the matching type, or {@code null}
     */
    /**
     * EXACT-name ObjectType lookup, NO yo fallback: used by create-time duplicate checks, where the
     * yo-tolerant {@link #findObjectType} would wrongly flag a distinct name differing only by yo as
     * a duplicate (XDTO local names are character-sensitive).
     */
    public static ObjectType findObjectTypeExact(Package pkg, String name)
    {
        if (pkg == null || name == null)
        {
            return null;
        }
        for (ObjectType type : pkg.getObjects())
        {
            if (name.equals(type.getName()))
            {
                return type;
            }
        }
        return null;
    }

    /**
     * Rewrites STALE SELF-REFERENCES only, walking the SAME recursive surface as
     * {@link #rewriteNamespaceReferences} (properties incl. anonymous nested {@code typeDefs} trees,
     * baseType / itemType / union member / enumeration QNames at every depth). A QName whose
     * {@code nsUri} equals {@code staleNs} is moved to {@code ownNs} ONLY when its local name matches
     * (exactly) a symbol of THIS content in the QName's OWN symbol space: a {@code type}-position
     * QName against the local ObjectTypes/ValueTypes, a {@code ref} QName against the PACKAGE-GLOBAL
     * properties. That disambiguation keeps a GENUINE reference into another package (whose namespace
     * happens to equal the stale value, e.g. a rename-collision) untouched for the broader cascade
     * rewrite to handle. Imports are deliberately NOT touched (importing one's own namespace is
     * meaningless - an import of the stale value is a remote reference by construction).
     *
     * @return {@code true} when at least one QName was rewritten
     */
    public static boolean rewriteStaleSelfReferences(Package content, String staleNs, String ownNs)
    {
        if (content == null || staleNs == null || ownNs == null || staleNs.equals(ownNs))
        {
            return false;
        }
        boolean changed = selfRewriteProperties(content.getProperties(), content, staleNs, ownNs);
        for (ObjectType type : content.getObjects())
        {
            changed |= selfRewriteTypeTree(type, content, staleNs, ownNs);
        }
        for (ValueType type : content.getTypes())
        {
            changed |= selfRewriteTypeTree(type, content, staleNs, ownNs);
        }
        return changed;
    }

    /** Recursive self-pass mirror of {@link #rewriteTypeTree}: every TYPE-space QName (baseType,
     * itemType, union members, enumeration types) at any nesting depth is disambiguated against the
     * content's local types; nested anonymous {@code typeDefs} are walked too. */
    private static boolean selfRewriteTypeTree(Type type, Package content, String staleNs, String ownNs)
    {
        boolean changed = retargetSelfTypeQName(type.getBaseType(), content, staleNs, ownNs);
        if (type instanceof ObjectType)
        {
            changed |= selfRewriteProperties(((ObjectType)type).getProperties(), content, staleNs, ownNs);
        }
        if (type instanceof ValueType)
        {
            ValueType valueType = (ValueType)type;
            changed |= retargetSelfTypeQName(valueType.getItemType(), content, staleNs, ownNs);
            for (QName member : valueType.getMemeberTypes())
            {
                changed |= retargetSelfTypeQName(member, content, staleNs, ownNs);
            }
            for (Enumeration enumeration : valueType.getEnumerations())
            {
                changed |= retargetSelfTypeQName(enumeration.getType(), content, staleNs, ownNs);
            }
            for (Type nested : valueType.getTypeDefs())
            {
                changed |= selfRewriteTypeTree(nested, content, staleNs, ownNs);
            }
        }
        return changed;
    }

    /** Self-pass property walk: type QNames against the TYPE space, ref QNames against PACKAGE-GLOBAL
     * properties, plus each property's anonymous nested {@code typeDefs} tree. */
    private static boolean selfRewriteProperties(EList<Property> properties, Package content,
        String staleNs, String ownNs)
    {
        boolean changed = false;
        for (Property property : properties)
        {
            changed |= retargetSelfTypeQName(property.getType(), content, staleNs, ownNs);
            changed |= retargetSelfRefQName(property.getRef(), content, staleNs, ownNs);
            if (property.getTypeDefs() != null)
            {
                changed |= selfRewriteTypeTree(property.getTypeDefs(), content, staleNs, ownNs);
            }
        }
        return changed;
    }

    /** Self-retarget for a TYPE-space QName: the local name must match a local ObjectType/ValueType. */
    private static boolean retargetSelfTypeQName(QName qname, Package content, String staleNs, String ownNs)
    {
        if (qname == null || !staleNs.equals(qname.getNsUri()))
        {
            return false;
        }
        String local = qname.getName();
        if (findObjectTypeExact(content, local) == null && findValueTypeExact(content, local) == null)
        {
            return false;
        }
        qname.setNsUri(ownNs);
        return true;
    }

    /** Self-retarget for a REF QName: refs target PACKAGE-GLOBAL properties, not types. */
    private static boolean retargetSelfRefQName(QName qname, Package content, String staleNs, String ownNs)
    {
        if (qname == null || !staleNs.equals(qname.getNsUri()))
        {
            return false;
        }
        if (findPropertyExact(content.getProperties(), qname.getName()) == null)
        {
            return false;
        }
        qname.setNsUri(ownNs);
        return true;
    }

    /** EXACT-name local ValueType lookup, NO yo fallback (see {@link #findObjectTypeExact}). */
    public static ValueType findValueTypeExact(Package pkg, String name)
    {
        if (pkg == null || name == null)
        {
            return null;
        }
        for (ValueType valueType : pkg.getTypes())
        {
            if (name.equals(valueType.getName()))
            {
                return valueType;
            }
        }
        return null;
    }

    /** EXACT-name Property lookup, NO yo fallback (see {@link #findObjectTypeExact}). */
    public static Property findPropertyExact(EList<Property> owner, String name)
    {
        if (owner == null || name == null)
        {
            return null;
        }
        for (Property property : owner)
        {
            if (name.equals(property.getName()))
            {
                return property;
            }
        }
        return null;
    }

    public static Type findLocalType(Package pkg, String name)
    {
        if (pkg == null || name == null)
        {
            return null;
        }
        // ALL exact passes precede ANY yo fallback: an ObjectType yo-match must not shadow a
        // ValueType stored under the exact requested spelling (and vice versa).
        for (ObjectType type : pkg.getObjects())
        {
            if (name.equals(type.getName()))
            {
                return type;
            }
        }
        for (ValueType valueType : pkg.getTypes())
        {
            if (name.equals(valueType.getName()))
            {
                return valueType;
            }
        }
        String yoNormalized = MdNameNormalizer.normalizeYo(name);
        if (!name.equals(yoNormalized))
        {
            for (ObjectType type : pkg.getObjects())
            {
                if (yoNormalized.equals(type.getName()))
                {
                    return type;
                }
            }
            for (ValueType valueType : pkg.getTypes())
            {
                if (yoNormalized.equals(valueType.getName()))
                {
                    return valueType;
                }
            }
        }
        return null;
    }

    /**
     * Finds a Property by EXACT (case-SENSITIVE) name in an owner list (either
     * {@code Package.getProperties()} - package-global - or {@code ObjectType.getProperties()} - object
     * members), or {@code null} when not found. Case-sensitive for the same reason as
     * {@link #findObjectType} (an XDTO/XML QName local name is case-sensitive).
     *
     * <p>On an EXACT miss, retries once with the yo-normalized {@code name} - see
     * {@link #findObjectType}'s javadoc for the full rationale (the SAME create-time normalization /
     * later-lookup mismatch applies to a Property's own name).</p>
     */
    public static Property findProperty(EList<Property> owner, String name)
    {
        if (owner == null || name == null)
        {
            return null;
        }
        for (Property property : owner)
        {
            if (name.equals(property.getName()))
            {
                return property;
            }
        }
        String yoNormalized = MdNameNormalizer.normalizeYo(name);
        if (!name.equals(yoNormalized))
        {
            for (Property property : owner)
            {
                if (yoNormalized.equals(property.getName()))
                {
                    return property;
                }
            }
        }
        return null;
    }

    /** Creates and appends a new (unchecked - the caller must have already rejected a duplicate) ObjectType. */
    public static ObjectType createObjectType(Package pkg, String name)
    {
        ObjectType type = XdtoFactory.eINSTANCE.createObjectType();
        type.setName(name);
        pkg.getObjects().add(type);
        return type;
    }

    /** Creates and appends a new (unchecked - the caller must have already rejected a duplicate) Property. */
    public static Property createProperty(EList<Property> owner, String name)
    {
        Property property = XdtoFactory.eINSTANCE.createProperty();
        property.setName(name);
        owner.add(property);
        return property;
    }

    /** Removes an ObjectType from its package (its own properties are containment - removed with it). */
    public static void removeObjectType(Package pkg, ObjectType type)
    {
        pkg.getObjects().remove(type);
    }

    /** Removes a Property from its owner list (package-global or nested in an ObjectType). */
    public static void removeProperty(EList<Property> owner, Property property)
    {
        owner.remove(property);
    }

    // ==================== namespace cascade (maintainer request) ====================
    //
    // modify_metadata's own xdtoNamespaceChange handling (ModifyMetadataTool#applyGenericPropertyChanges)
    // re-syncs the CHANGED package's own content nsUri to its new namespace - but every OTHER package of
    // the same configuration that either imports the OLD namespace, or references one of the changed
    // package's types via a QName carrying the OLD nsUri, is left dangling (the maintainer's exact
    // complaint: renaming one package's namespace silently breaks a DIFFERENT, unrelated package, not
    // this one). rewriteNamespaceReferences is the PURE per-package rewrite; the caller
    // (ModifyMetadataTool) enumerates every OTHER XDTOPackage of the configuration,
    // materializes each one's content via resolvePackageContent, and applies this to it.

    /**
     * Rewrites every reference to {@code oldNs} found in {@code content} to {@code newNs}: an
     * {@link Import} in {@code content.getDependencies()} whose {@code namespace} equals {@code oldNs};
     * a {@link Property}'s {@code type} / {@code ref} QName - both PACKAGE-GLOBAL
     * ({@code content.getProperties()}) and NESTED in every {@link ObjectType}
     * ({@code content.getObjects()}) - whose {@code nsUri} equals {@code oldNs}; an {@link ObjectType}'s
     * or a {@link ValueType}'s own {@code baseType} QName (both extend {@link Type}, which is where
     * {@code baseType} actually lives); a {@link ValueType}'s {@code itemType} (list) and
     * {@code getMemeberTypes()} (union - the EDT API's own spelling) QNames and each of its
     * {@link Enumeration}'s {@code type} QNames; and the WHOLE anonymous nested {@code typeDefs}
     * tree (a Property's single nested Type and a ValueType's list of them) - nested definitions
     * recursively carry the same QName surface, so the walk covers every depth.
     *
     * <p>Pure - mutates {@code content} in place and returns whether anything changed; does not touch BM,
     * does not force-export (the caller decides what to do with a {@code true} result).</p>
     *
     * @param content the OTHER package's content to rewrite (never the changed package's own content -
     *            that one is re-synced by {@link #resolvePackageContent} already)
     * @param oldNs the namespace being replaced
     * @param newNs the namespace to replace it with
     * @return {@code true} when at least one reference was rewritten (the caller must force-export this
     *         package), {@code false} when nothing matched {@code oldNs} (nothing to export)
     */
    public static boolean rewriteNamespaceReferences(Package content, String oldNs, String newNs)
    {
        if (content == null || oldNs == null || newNs == null)
        {
            return false;
        }
        boolean changed = false;
        for (Import dependency : content.getDependencies())
        {
            if (oldNs.equals(dependency.getNamespace()))
            {
                dependency.setNamespace(newNs);
                changed = true;
            }
        }
        changed |= rewritePropertyQNames(content.getProperties(), oldNs, newNs);
        for (ObjectType type : content.getObjects())
        {
            changed |= rewriteTypeTree(type, oldNs, newNs);
        }
        for (ValueType type : content.getTypes())
        {
            changed |= rewriteTypeTree(type, oldNs, newNs);
        }
        return changed;
    }

    /**
     * Recursive per-type rewrite for the broad pass: baseType, an ObjectType's properties (each of
     * which may carry an ANONYMOUS nested {@code typeDefs} type), a ValueType's itemType / union
     * member types / enumeration types and its own nested {@code typeDefs} - the model nests type
     * definitions arbitrarily deep, and a QName at any depth can carry the old namespace.
     */
    private static boolean rewriteTypeTree(Type type, String oldNs, String newNs)
    {
        boolean changed = retargetQName(type.getBaseType(), oldNs, newNs);
        if (type instanceof ObjectType)
        {
            changed |= rewritePropertyQNames(((ObjectType)type).getProperties(), oldNs, newNs);
        }
        if (type instanceof ValueType)
        {
            ValueType valueType = (ValueType)type;
            changed |= retargetQName(valueType.getItemType(), oldNs, newNs);
            for (QName member : valueType.getMemeberTypes())
            {
                changed |= retargetQName(member, oldNs, newNs);
            }
            for (Enumeration enumeration : valueType.getEnumerations())
            {
                changed |= retargetQName(enumeration.getType(), oldNs, newNs);
            }
            for (Type nested : valueType.getTypeDefs())
            {
                changed |= rewriteTypeTree(nested, oldNs, newNs);
            }
        }
        return changed;
    }

    /** Moves {@code qname} from {@code oldNs} to {@code newNs}; {@code false} when null/other ns. */
    private static boolean retargetQName(QName qname, String oldNs, String newNs)
    {
        if (qname == null || !oldNs.equals(qname.getNsUri()))
        {
            return false;
        }
        qname.setNsUri(newNs);
        return true;
    }

    /** Rewrites {@code type}/{@code ref} (and any anonymous nested {@code typeDefs} tree) on every
     * Property in {@code properties} whose QNames carry {@code oldNs}. */
    private static boolean rewritePropertyQNames(EList<Property> properties, String oldNs, String newNs)
    {
        boolean changed = false;
        for (Property property : properties)
        {
            if (property.getTypeDefs() != null)
            {
                changed |= rewriteTypeTree(property.getTypeDefs(), oldNs, newNs);
            }
            QName type = property.getType();
            if (type != null && oldNs.equals(type.getNsUri()))
            {
                type.setNsUri(newNs);
                changed = true;
            }
            QName ref = property.getRef();
            if (ref != null && oldNs.equals(ref.getNsUri()))
            {
                ref.setNsUri(newNs);
                changed = true;
            }
        }
        return changed;
    }

    // ==================== apply (typed attribute writes) ====================

    /**
     * The outcome of applying property/attribute changes to one XDTO member: either an actionable
     * {@link ToolResult#error} JSON string in {@link #error} (an unassignable name / a malformed
     * QName / a required 'type' missing on create) or the list of attribute names actually applied.
     */
    public static final class Result
    {
        /** Non-null when the change was rejected (nothing was mutated): a ready ToolResult.error. */
        public final String error;
        /** Names of the attributes actually set, in input order. */
        public final List<String> applied;

        private Result(String error, List<String> applied)
        {
            this.error = error;
            this.applied = applied;
        }

        static Result failed(String error)
        {
            return new Result(error, List.of());
        }

        static Result ok(List<String> applied)
        {
            return new Result(null, applied);
        }

        public boolean hasError()
        {
            return error != null;
        }
    }

    /**
     * Applies the ObjectType boolean flags ({@code open} / {@code abstract} / {@code mixed} /
     * {@code ordered} / {@code sequenced}) found in {@code properties} ({@code [{name, value}]}, the same
     * shape {@code modify_metadata} / {@code create_metadata} already use). Every entry is VALIDATED
     * (name must be assignable, value must be a boolean) before ANY mutation, so a bad entry leaves
     * {@code type} untouched.
     *
     * @param type the ObjectType to mutate (may be a freshly created, still-unattached instance)
     * @param properties the raw {@code [{name, value}]} entries
     * @return the result - check {@link Result#hasError()} first
     */
    public static Result applyObjectTypeProperties(ObjectType type, List<JsonObject> properties)
    {
        MapResult mapResult = toMap(properties);
        if (mapResult.error != null)
        {
            return Result.failed(mapResult.error);
        }
        Map<String, JsonElement> values = mapResult.values;
        String unknown = firstUnknownKey(values.keySet(), OBJECT_TYPE_PROPS);
        if (unknown != null)
        {
            return Result.failed(unassignableError(unknown, "an XDTO ObjectType", OBJECT_TYPE_PROPS)); //$NON-NLS-1$
        }
        for (String key : values.keySet())
        {
            if (boolProp(values, key) == null)
            {
                return Result.failed(ToolResult.error("Property '" + key //$NON-NLS-1$
                    + "' on an XDTO ObjectType must be a boolean (true/false).").toJson()); //$NON-NLS-1$
            }
        }
        List<String> applied = new ArrayList<>();
        Boolean open = boolProp(values, "open"); //$NON-NLS-1$
        if (open != null)
        {
            type.setOpen(open.booleanValue());
            applied.add("open"); //$NON-NLS-1$
        }
        Boolean isAbstract = boolProp(values, "abstract"); //$NON-NLS-1$
        if (isAbstract != null)
        {
            type.setAbstract(isAbstract.booleanValue());
            applied.add("abstract"); //$NON-NLS-1$
        }
        Boolean mixed = boolProp(values, "mixed"); //$NON-NLS-1$
        if (mixed != null)
        {
            type.setMixed(mixed.booleanValue());
            applied.add("mixed"); //$NON-NLS-1$
        }
        Boolean ordered = boolProp(values, "ordered"); //$NON-NLS-1$
        if (ordered != null)
        {
            type.setOrdered(ordered.booleanValue());
            applied.add("ordered"); //$NON-NLS-1$
        }
        Boolean sequenced = boolProp(values, "sequenced"); //$NON-NLS-1$
        if (sequenced != null)
        {
            type.setSequenced(sequenced.booleanValue());
            applied.add("sequenced"); //$NON-NLS-1$
        }
        return Result.ok(applied);
    }

    /**
     * Applies the Property attributes ({@code type} - a {@link QName} spec - plus the optional
     * {@code lowerBound} / {@code upperBound} / {@code nillable} / {@code fixed} / {@code default}) found
     * in {@code properties}. Every entry is VALIDATED (name assignable, a {@code type} QName spec
     * well-formed, a numeric/boolean shape correct) before ANY mutation. {@code type} is a created-once
     * REQUIRED attribute when {@code requireType} is {@code true} (a fresh Property needs a type; a
     * modify of an existing Property does not have to touch it).
     *
     * <p>{@code ref} (a reference to a GLOBAL PROPERTY) is NOT supported for v1 - a {@code ref} entry is
     * rejected with an actionable "not yet supported" error, not silently dropped, pointing the caller at
     * {@code type} (see {@link #PROPERTY_PROPS}'s javadoc for why).</p>
     *
     * @param property the Property to mutate (may be freshly created, still-unattached)
     * @param pkg the owning Package (for the same-package ObjectType-name QName shorthand)
     * @param properties the raw {@code [{name, value}]} entries
     * @param requireType {@code true} to require a {@code type} entry (a Property CREATE)
     * @return the result - check {@link Result#hasError()} first
     */
    public static Result applyPropertyProperties(Property property, Package pkg, List<JsonObject> properties,
        boolean requireType)
    {
        MapResult mapResult = toMap(properties);
        if (mapResult.error != null)
        {
            return Result.failed(mapResult.error);
        }
        Map<String, JsonElement> values = mapResult.values;
        if (values.containsKey("ref")) //$NON-NLS-1$
        {
            return Result.failed(ToolResult.error("Property 'ref' (a global-property reference) is not " //$NON-NLS-1$
                + "yet supported; set a 'type' instead (a built-in XSD type name, the name of an " //$NON-NLS-1$
                + "ObjectType already in the same package, or the explicit object form " //$NON-NLS-1$
                + "{nsUri, name}).").toJson()); //$NON-NLS-1$
        }
        String unknown = firstUnknownKey(values.keySet(), PROPERTY_PROPS);
        if (unknown != null)
        {
            return Result.failed(unassignableError(unknown, "an XDTO Property", PROPERTY_PROPS)); //$NON-NLS-1$
        }
        if (requireType && !values.containsKey("type")) //$NON-NLS-1$
        {
            return Result.failed(ToolResult.error("Property 'type' is required: {name:'type', value:'string'} " //$NON-NLS-1$
                + "(a built-in XSD type) or {name:'type', value:{nsUri:'...', name:'...'}}, or the name " //$NON-NLS-1$
                + "of an ObjectType already in the same package (a same-package reference).").toJson()); //$NON-NLS-1$
        }

        // Resolve 'type' up front (before any mutation), so a bad spec leaves the Property untouched.
        QName typeQName = null;
        if (values.containsKey("type")) //$NON-NLS-1$
        {
            QNameResult r = resolveQName(values.get("type"), pkg, "'type'"); //$NON-NLS-1$ //$NON-NLS-2$
            if (r.error != null)
            {
                return Result.failed(r.error);
            }
            typeQName = r.qname;
        }
        Integer lowerBound = null;
        if (values.containsKey("lowerBound")) //$NON-NLS-1$
        {
            lowerBound = intProp(values, "lowerBound"); //$NON-NLS-1$
            if (lowerBound == null)
            {
                return Result.failed(
                    ToolResult.error("Property 'lowerBound' must be a non-negative integer.").toJson()); //$NON-NLS-1$
            }
        }
        Integer upperBound = null;
        if (values.containsKey("upperBound")) //$NON-NLS-1$
        {
            upperBound = intProp(values, "upperBound"); //$NON-NLS-1$
            if (upperBound == null)
            {
                return Result.failed(
                    ToolResult.error("Property 'upperBound' must be an integer (-1 for unbounded).").toJson()); //$NON-NLS-1$
            }
        }
        Boolean nillable = null;
        if (values.containsKey("nillable")) //$NON-NLS-1$
        {
            nillable = boolProp(values, "nillable"); //$NON-NLS-1$
            if (nillable == null)
            {
                return Result.failed(ToolResult.error("Property 'nillable' must be a boolean (true/false).") //$NON-NLS-1$
                    .toJson());
            }
        }
        Boolean fixed = null;
        if (values.containsKey("fixed")) //$NON-NLS-1$
        {
            fixed = boolProp(values, "fixed"); //$NON-NLS-1$
            if (fixed == null)
            {
                return Result.failed(
                    ToolResult.error("Property 'fixed' must be a boolean (true/false).").toJson()); //$NON-NLS-1$
            }
        }
        String default_ = null;
        if (values.containsKey("default")) //$NON-NLS-1$
        {
            default_ = stringProp(values, "default"); //$NON-NLS-1$
            if (default_ == null)
            {
                // Present but not a string primitive (object/array/JSON null): reject like the sibling
                // properties above - silently applying would call setDefault(null) and CLEAR an existing
                // default while reporting the property as applied.
                return Result.failed(
                    ToolResult.error("Property 'default' must be a string.").toJson()); //$NON-NLS-1$
            }
        }

        // Validate the EFFECTIVE post-change state (this call's new values layered over whatever the
        // property already carries) BEFORE any mutation - a bad combination must leave the property
        // untouched, not partially applied.
        String boundsError = validateEffectiveBounds(property, pkg, lowerBound, upperBound);
        if (boundsError != null)
        {
            return Result.failed(boundsError);
        }
        String fixedError = validateEffectiveFixed(property, values, fixed, default_);
        if (fixedError != null)
        {
            return Result.failed(fixedError);
        }

        List<String> applied = new ArrayList<>();
        if (typeQName != null)
        {
            property.setType(typeQName);
            // 'type' and 'ref' are mutually exclusive XDTO alternative forms - clear any pre-existing
            // 'ref' (e.g. from an XML import; this code never SETS 'ref' itself, v1 rejects it above) so
            // a freshly-typed property never ends up carrying both.
            property.setRef(null);
            applied.add("type"); //$NON-NLS-1$
        }
        if (lowerBound != null)
        {
            property.setLowerBound(lowerBound.intValue());
            applied.add("lowerBound"); //$NON-NLS-1$
        }
        if (upperBound != null)
        {
            property.setUpperBound(upperBound.intValue());
            applied.add("upperBound"); //$NON-NLS-1$
        }
        if (nillable != null)
        {
            property.setNillable(nillable.booleanValue());
            applied.add("nillable"); //$NON-NLS-1$
        }
        if (fixed != null)
        {
            property.setFixed(fixed.booleanValue());
            applied.add("fixed"); //$NON-NLS-1$
        }
        if (values.containsKey("default")) //$NON-NLS-1$
        {
            property.setDefault(default_);
            applied.add("default"); //$NON-NLS-1$
        }
        return Result.ok(applied);
    }

    /**
     * Validates the EFFECTIVE {@code lowerBound}/{@code upperBound} once this call's new values (if any)
     * are layered over the property's current ones: {@code lowerBound >= 0} (whenever it is known - just
     * set now, or already set before); {@code upperBound}, whenever known, must be {@code -1} (unbounded)
     * or {@code >= 0} (a bare negative OTHER than {@code -1}, e.g. {@code -2}, is never valid XDTO - codex
     * review residual #4a); {@code upperBound >= lowerBound} is additionally checked when BOTH bounds are
     * actually known (just set or already set - so a call that touches only ONE bound and leaves the
     * other genuinely never-set is not rejected against an invented default); and a PACKAGE-GLOBAL
     * property (its direct container is the Package itself, not an ObjectType) cannot carry occurrence
     * bounds at all - XDTO only gives cardinality meaning to a property nested in an ObjectType. Returns a
     * ready JSON error, or {@code null} when the effective state is valid.
     */
    private static String validateEffectiveBounds(Property property, Package pkg, Integer newLower,
        Integer newUpper)
    {
        if (newLower == null && newUpper == null)
        {
            return null;
        }
        boolean isPackageGlobal = property.eContainer() == pkg;
        if (isPackageGlobal)
        {
            return ToolResult.error("A package-global property cannot carry occurrence bounds " //$NON-NLS-1$
                + "('lowerBound'/'upperBound'); those apply only to a property nested in an ObjectType.") //$NON-NLS-1$
                    .toJson();
        }
        // A side neither set in this call nor already set still has an EFFECTIVE value: the model
        // DEFAULT the platform serializes/reads. Comparing against it (read from the EMF feature,
        // not hardcoded) rejects a one-sided range the platform would refuse, e.g. lowerBound=2
        // with the other side at its default.
        Integer effectiveLower = newLower != null ? newLower
            : (property.isSetLowerBound() ? Integer.valueOf(property.getLowerBound())
                : boundDefault(property, "lowerBound")); //$NON-NLS-1$
        Integer effectiveUpper = newUpper != null ? newUpper
            : (property.isSetUpperBound() ? Integer.valueOf(property.getUpperBound())
                : boundDefault(property, "upperBound")); //$NON-NLS-1$
        if (effectiveLower != null && effectiveLower.intValue() < 0)
        {
            return ToolResult.error("Property 'lowerBound' must be >= 0.").toJson(); //$NON-NLS-1$
        }
        if (effectiveUpper != null && effectiveUpper.intValue() < -1)
        {
            return ToolResult.error("Property 'upperBound' must be -1 (unbounded) or >= 0.").toJson(); //$NON-NLS-1$
        }
        if (effectiveLower != null && effectiveUpper != null && effectiveUpper.intValue() != -1
            && effectiveUpper.intValue() < effectiveLower.intValue())
        {
            return ToolResult.error("Property 'upperBound' must be -1 (unbounded) or >= 'lowerBound' (" //$NON-NLS-1$
                + effectiveLower + ").").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /** The EMF DEFAULT of an occurrence-bound feature ({@code null} when the model declares none). */
    private static Integer boundDefault(Property property, String featureName)
    {
        Object def = property.eClass().getEStructuralFeature(featureName).getDefaultValue();
        return def instanceof Integer ? (Integer)def : null;
    }

    /**
     * Validates the EFFECTIVE {@code fixed}/{@code default} once this call's new values (if any) are
     * layered over the property's current ones: a default is REQUIRED only when {@code fixed=true} is
     * being NEWLY set BY THIS CALL and no default is set now nor supplied in this call (codex review
     * residual #4b) - a call that leaves an ALREADY-{@code fixed} property's {@code fixed} flag untouched
     * (e.g. editing an unrelated bound) never re-fires this check. "Set" is {@code getDefault() != null} -
     * an EMPTY STRING default (a legitimate value; {@link Property} has no {@code isSetDefault()}/unset
     * pair, so {@code null} is the only "absent" signal) counts as present, not absent. Returns a ready
     * JSON error, or {@code null} when the effective state is valid.
     */
    private static String validateEffectiveFixed(Property property, Map<String, JsonElement> values,
        Boolean newFixed, String newDefault)
    {
        boolean fixedNewlySetTrue = newFixed != null && newFixed.booleanValue();
        if (!fixedNewlySetTrue)
        {
            return null;
        }
        String effectiveDefault = values.containsKey("default") ? newDefault : property.getDefault(); //$NON-NLS-1$
        if (effectiveDefault == null)
        {
            return ToolResult.error("Property 'fixed'=true requires a 'default' value (either in this " //$NON-NLS-1$
                + "call or already set).").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    // ==================== QName resolution ====================

    /** The outcome of {@link #resolveQName}: exactly one of {@link #qname} / {@link #error} is non-null. */
    public static final class QNameResult
    {
        public final QName qname;
        public final String error;

        private QNameResult(QName qname, String error)
        {
            this.qname = qname;
            this.error = error;
        }

        static QNameResult of(QName qname)
        {
            return new QNameResult(qname, null);
        }

        static QNameResult failed(String error)
        {
            return new QNameResult(null, error);
        }
    }

    /**
     * Resolves a {@code type} / {@code ref} JSON spec into an mcore {@link QName}: a JSON STRING is
     * resolved as a same-package {@link ObjectType} name (EXACT, case-sensitive match against one
     * already in {@code pkg} - the resulting QName's {@code nsUri} is the package's own {@code nsUri}) or,
     * failing that, as an XSD built-in datatype name ({@link #XSD_BUILTIN_TYPES}, under
     * {@code http://www.w3.org/2001/XMLSchema}) - ANY OTHER bare string is REJECTED (not silently turned
     * into an invalid {@code xs:<typo>} reference), pointing the caller at the explicit object form. A
     * JSON OBJECT {@code {nsUri, name}} is that explicit form (a non-empty {@code name} is required;
     * {@code nsUri} defaults to the XSD namespace when omitted) - a NON-XSD, non-same-package
     * {@code nsUri} must be reachable via a matching {@code Package.getDependencies()} Import, else it is
     * rejected as an unimported (invalid) namespace reference.
     *
     * @param spec the raw JSON value of the {@code type}/{@code ref} entry
     * @param pkg the owning Package (for the same-package ObjectType-name shorthand and the Import
     *            reachability check); may be {@code null} (the same-package shorthand and the Import
     *            check then both no-op)
     * @param fieldLabel the quoted field name, for an actionable error (e.g. {@code "'type'"})
     * @return the resolution - check {@link QNameResult#error} first
     */
    public static QNameResult resolveQName(JsonElement spec, Package pkg, String fieldLabel)
    {
        if (spec == null || spec.isJsonNull())
        {
            return QNameResult.failed(ToolResult.error(fieldLabel + " must not be null.").toJson()); //$NON-NLS-1$
        }
        if (spec.isJsonPrimitive() && spec.getAsJsonPrimitive().isString())
        {
            return resolveQNameShorthand(spec.getAsString().trim(), pkg, fieldLabel);
        }
        if (spec.isJsonObject())
        {
            return resolveQNameObjectForm(spec.getAsJsonObject(), pkg, fieldLabel);
        }
        return QNameResult.failed(ToolResult.error(fieldLabel + " must be a string (the EXACT name of an " //$NON-NLS-1$
            + "ObjectType already in the same package, or a built-in XSD type name) or an object " //$NON-NLS-1$
            + "{nsUri, name}.").toJson()); //$NON-NLS-1$
    }

    /**
     * The bare-STRING shorthand branch of {@link #resolveQName}: an exact same-package ObjectType name,
     * else a whitelisted XSD built-in, else a rejection (never a silently-invented namespace).
     */
    private static QNameResult resolveQNameShorthand(String token, Package pkg, String fieldLabel)
    {
        if (token.isEmpty())
        {
            return QNameResult.failed(ToolResult.error(fieldLabel + " must be a non-empty string or an " //$NON-NLS-1$
                + "object {nsUri, name}.").toJson()); //$NON-NLS-1$
        }
        Type sameName = findLocalType(pkg, token);
        if (sameName != null)
        {
            QName qname = McoreFactory.eINSTANCE.createQName();
            qname.setNsUri(pkg.getNsUri());
            qname.setName(sameName.getName());
            return QNameResult.of(qname);
        }
        if (XSD_BUILTIN_TYPES.contains(token))
        {
            QName qname = McoreFactory.eINSTANCE.createQName();
            qname.setNsUri(XSD_NS);
            qname.setName(token);
            return QNameResult.of(qname);
        }
        return QNameResult.failed(ToolResult.error(fieldLabel + " '" + token + "' is neither the EXACT " //$NON-NLS-1$ //$NON-NLS-2$
            + "(case-sensitive) name of an ObjectType already in the same package nor a built-in XSD " //$NON-NLS-1$
            + "type (e.g. 'string', 'boolean', 'decimal', 'dateTime', 'date', 'int'). Use the exact " //$NON-NLS-1$
            + "ObjectType name, a built-in XSD type name, or the explicit object form {nsUri, name} for " //$NON-NLS-1$
            + "a cross-namespace reference.").toJson()); //$NON-NLS-1$
    }

    /** The {@code {nsUri, name}} object-form branch of {@link #resolveQName}, with the Import reachability check. */
    private static QNameResult resolveQNameObjectForm(JsonObject obj, Package pkg, String fieldLabel)
    {
        String name = stringMember(obj, "name"); //$NON-NLS-1$
        if (name == null || name.isEmpty())
        {
            return QNameResult.failed(ToolResult.error(fieldLabel + " object form needs a non-empty " //$NON-NLS-1$
                + "'name', e.g. {nsUri:'http://www.w3.org/2001/XMLSchema', name:'string'}.").toJson()); //$NON-NLS-1$
        }
        String nsUri = stringMember(obj, "nsUri"); //$NON-NLS-1$
        if (nsUri == null && obj.has("nsUri") && !obj.get("nsUri").isJsonNull()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            // A PRESENT but non-string nsUri is malformed input - treating it like an omitted one
            // would silently default to the XSD namespace and persist a different reference than
            // the caller supplied.
            return QNameResult.failed(ToolResult.error(fieldLabel
                + " 'nsUri' must be a string when present.").toJson()); //$NON-NLS-1$
        }
        String resolvedNsUri = nsUri != null && !nsUri.isEmpty() ? nsUri : XSD_NS;
        String importError = validateNamespaceReachable(resolvedNsUri, pkg, fieldLabel);
        if (importError != null)
        {
            return QNameResult.failed(importError);
        }
        // The object form's 'name' is free-text (unlike the bare-STRING shorthand, whose same-package
        // ObjectType branch never reaches here): when the RESOLVED namespace is XSD, the name must still
        // be one of the whitelisted built-ins, or a typo like {name:'strng'} (explicit or via the
        // omitted-nsUri default) would silently persist an invalid 'xs:strng' reference (issue #183 P2 -
        // the bare-string shorthand already rejects this; the object form did not).
        if (XSD_NS.equals(resolvedNsUri) && !XSD_BUILTIN_TYPES.contains(name))
        {
            return QNameResult.failed(ToolResult.error(fieldLabel + " name '" + name + "' is not a " //$NON-NLS-1$ //$NON-NLS-2$
                + "built-in XSD type (e.g. 'string', 'boolean', 'decimal', 'dateTime', 'date', 'int'); " //$NON-NLS-1$
                + "the XSD namespace only accepts a built-in type name.").toJson()); //$NON-NLS-1$
        }
        // Same parity for the package's OWN namespace: the bare-string shorthand only resolves to an
        // ObjectType that actually exists, so the object form must not silently persist a dangling
        // same-package reference (e.g. {nsUri: <own ns>, name: 'Adress'} with no such ObjectType).
        // The persisted name is the RESOLVED ObjectType's stored name: findObjectType tolerates a yo
        // spelling variant, and serializing the raw input would dangle against the stored name.
        if (pkg != null && !XSD_NS.equals(resolvedNsUri) && resolvedNsUri.equals(pkg.getNsUri()))
        {
            Type localTarget = findLocalType(pkg, name);
            if (localTarget == null)
            {
                return QNameResult.failed(ToolResult.error(fieldLabel + " name '" + name + "' does not match " //$NON-NLS-1$ //$NON-NLS-2$
                    + "any ObjectType or value type in this package (its own namespace '" + resolvedNsUri //$NON-NLS-1$
                    + "'). Create the ObjectType first, or reference an imported / XSD type.").toJson()); //$NON-NLS-1$
            }
            name = localTarget.getName();
        }
        // An IMPORTED-namespace name is free text so far - the XSD branch has its whitelist and
        // the own-namespace branch resolves a real local type, but an imported name like
        // "123 bad" would persist an invalid XML local name.
        if (!isNcNameLite(name))
        {
            return QNameResult.failed(ToolResult.error(fieldLabel + " name " + QUOTE + name + QUOTE + " is not a " //$NON-NLS-1$ //$NON-NLS-2$
                + "valid XML type name (must start with a letter or underscore and contain only " //$NON-NLS-1$
                + "letters, digits, underscores, dots or hyphens).").toJson()); //$NON-NLS-1$
        }
        QName qname = McoreFactory.eINSTANCE.createQName();
        qname.setNsUri(resolvedNsUri);
        qname.setName(name);
        return QNameResult.of(qname);
    }

    /** XML NCName-lite: letter/underscore start; letters, digits, underscore, dot, hyphen after. */
    private static boolean isNcNameLite(String name)
    {
        if (name == null || name.isEmpty()
            || (!Character.isLetter(name.charAt(0)) && name.charAt(0) != UNDERSCORE))
        {
            return false;
        }
        for (int i = 1; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != UNDERSCORE && c != DOT && c != HYPHEN)
            {
                return false;
            }
        }
        return true;
    }

    private static final String QUOTE = String.valueOf((char)39); // apostrophe, kept out of the literal
    private static final char UNDERSCORE = 95;   // underscore
    private static final char DOT = 46;          // .
    private static final char HYPHEN = 45;       // -

    /**
     * Validates that {@code nsUri} is REACHABLE from {@code pkg}: the XSD namespace and the package's
     * OWN namespace are implicitly reachable (no Import needed); any other namespace must have a
     * matching {@link Import} in {@code pkg.getDependencies()} - an unimported namespace is an invalid
     * XDTO reference (EDT's own validator would reject it). Returns a ready JSON error, or {@code null}
     * when {@code nsUri} is reachable (or {@code pkg} is {@code null}, in which case the check no-ops -
     * the caller has no package context to check against).
     */
    private static String validateNamespaceReachable(String nsUri, Package pkg, String fieldLabel)
    {
        if (pkg == null || XSD_NS.equals(nsUri) || nsUri.equals(pkg.getNsUri()))
        {
            return null;
        }
        for (Import dependency : pkg.getDependencies())
        {
            if (nsUri.equals(dependency.getNamespace()))
            {
                return null;
            }
        }
        return ToolResult.error(fieldLabel + " namespace '" + nsUri + "' is not reachable from this XDTO " //$NON-NLS-1$ //$NON-NLS-2$
            + "package (no matching entry in its Import dependencies, and it is neither the XSD " //$NON-NLS-1$
            + "namespace nor the package's own namespace). Add an Import for it first, or use the " //$NON-NLS-1$
            + "package's own namespace / a built-in XSD type.").toJson(); //$NON-NLS-1$
    }

    // ==================== JSON helpers ====================

    /**
     * The outcome of {@link #toMap}: exactly one of {@link #values} / {@link #error} is non-null.
     */
    private static final class MapResult
    {
        final Map<String, JsonElement> values;
        final String error;

        private MapResult(Map<String, JsonElement> values, String error)
        {
            this.values = values;
            this.error = error;
        }

        static MapResult of(Map<String, JsonElement> values)
        {
            return new MapResult(values, null);
        }

        static MapResult failed(String error)
        {
            return new MapResult(null, error);
        }
    }

    /**
     * Flattens the raw {@code [{name, value, language?}]} property entries into a {@code name -> value}
     * map (last entry wins on a repeated name). An entry missing a non-empty {@code name} is skipped (it
     * addresses nothing actionable). An entry that HAS a non-empty {@code name} but NO {@code value} at
     * all is a HARD ERROR (issue #183 P2), not a silent drop: {@code toMap} used to skip it the same way
     * as an unnamed entry, so a caller that mistyped {@code {name:'open'}} (forgetting {@code value})
     * saw the tool report SUCCESS with that property silently missing from {@code applied} - actionable
     * only if the caller happened to notice the shorter list.
     */
    private static MapResult toMap(List<JsonObject> properties)
    {
        Map<String, JsonElement> map = new LinkedHashMap<>();
        if (properties == null)
        {
            return MapResult.of(map);
        }
        for (JsonObject entry : properties)
        {
            String name = stringMember(entry, "name"); //$NON-NLS-1$
            if (name == null || name.isEmpty())
            {
                // A named-value list entry without a (string) name is malformed input, not something
                // to skip: silently dropping it would report success while the flag never applied.
                return MapResult.failed(ToolResult.error(
                    "Each property entry must carry a non-empty string 'name'.").toJson()); //$NON-NLS-1$
            }
            if (!entry.has("value")) //$NON-NLS-1$
            {
                return MapResult.failed(ToolResult.error("Property '" + name + "' has no 'value'.").toJson()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            map.put(name, entry.get("value")); //$NON-NLS-1$
        }
        return MapResult.of(map);
    }

    /** The first key in {@code keys} that is NOT in {@code allowed}, or {@code null} when all are known. */
    private static String firstUnknownKey(Set<String> keys, Set<String> allowed)
    {
        for (String key : keys)
        {
            if (!allowed.contains(key))
            {
                return key;
            }
        }
        return null;
    }

    /** A ready {@link ToolResult#error} JSON (issue #183 codex review #9: never a bare string). */
    private static String unassignableError(String name, String targetDescription, Set<String> allowed)
    {
        return ToolResult.error("Property '" + name + "' is not assignable on " + targetDescription //$NON-NLS-1$ //$NON-NLS-2$
            + ". Assignable: " + String.join(", ", allowed) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String stringMember(JsonObject obj, String name)
    {
        if (obj == null || !obj.has(name))
        {
            return null;
        }
        JsonElement element = obj.get(name);
        // Strict STRING primitive (see stringProp): {name: 123} must be rejected, not coerced to "123".
        return (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
            ? element.getAsString() : null;
    }

    private static Boolean boolProp(Map<String, JsonElement> values, String key)
    {
        JsonElement element = values.get(key);
        if (element == null || !element.isJsonPrimitive())
        {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isBoolean() ? Boolean.valueOf(primitive.getAsBoolean()) : null;
    }

    private static Integer intProp(Map<String, JsonElement> values, String key)
    {
        JsonElement element = values.get(key);
        // Strict NUMERIC primitive: a stringified bound ("2") is malformed input, not something to
        // coerce - the contract documents these as integers (mirrors the strict string fields).
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
        {
            return null;
        }
        try
        {
            double d = element.getAsDouble();
            if (d != Math.floor(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE)
            {
                return null;
            }
            return Integer.valueOf((int)d);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static String stringProp(Map<String, JsonElement> values, String key)
    {
        JsonElement element = values.get(key);
        if (element == null || element.isJsonNull())
        {
            return null;
        }
        // Strict STRING primitive: a number/boolean (default: 42) must not silently coerce to "42" -
        // the contract documents these fields as strings and the sibling props reject wrong JSON types.
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
            ? element.getAsString() : null;
    }
}
