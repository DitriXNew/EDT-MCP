/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefined;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypesPredefined;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypesPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Predefined;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Standalone helper authoring PREDEFINED items on a {@code Catalog} or
 * {@code ChartOfCharacteristicTypes}, addressed by a dedicated FQN grammar:
 * {@code <OwnerType>.<OwnerName>.Predefined.<ItemName>} (English {@code Predefined} or its Russian
 * equivalent; the owner TYPE token itself is bilingual like every other FQN in this plugin).
 * <p>
 * <b>Architecture (issue #293):</b> {@code MdClass.xcore} declares {@code predefined} as a plain EMF
 * CONTAINMENT on the owner ({@code contains CatalogPredefined predefined}) - NOT an
 * {@code @ExternalProperty}. There is therefore no separate top object, no
 * {@code attachTopObject}/FQN-generator machinery and no {@code bmGetFqn()} on the predefined content:
 * a caller mutates the (already re-fetched, transaction-bound) OWNER directly via this class, then
 * force-exports the owner's own top-object FQN. The on-disk layout (whether the platform serializer
 * inlines the items in the owner's {@code .mdo} or splits them into a sibling {@code Predefined.xml})
 * is the serializer's business, not this writer's.
 * <p>
 * Every operation here is a pure EMF read/mutation - no BM transaction is opened or assumed - so the
 * whole class is unit-testable against a bare {@link MdClassFactory}-created {@link Catalog} /
 * {@link ChartOfCharacteristicTypes}. The WRITE callers ({@code create_metadata} /
 * {@code modify_metadata} / {@code delete_metadata}) open a BM write transaction and force-export the
 * owner, exactly like the plain mdclass-member path in {@code CreateMetadataTool#createMember}. The
 * READ caller ({@code get_metadata_details}) needs no transaction: the predefined items are plain
 * containment on the already-resolved {@code Configuration} (the same in-resource data as the owner's
 * other reflected sections), not a lazily-loaded sub-resource.
 * <p>
 * <b>Scope (v1):</b> {@code Catalog} and {@code ChartOfCharacteristicTypes} only. A
 * {@code ChartOfAccounts} / {@code ChartOfCalculationTypes} owner carries a richer per-item model
 * (account type / accounting flags / ext-dimension types, or base/displaced/leading arrays) this
 * version does not author; such a request is rejected with an actionable "not yet supported" error
 * rather than faking support (see {@link #unsupportedOwnerTypeError}).
 */
public final class PredefinedWriter
{
    /** Property/JSON key: the name of a {@code properties} entry. */
    private static final String KEY_NAME = "name"; //$NON-NLS-1$

    /** Property/JSON key: the value of a {@code properties} entry. */
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$

    /** Supported property: the item's presentation text (default: the item's Name). */
    private static final String PROP_DESCRIPTION = "description"; //$NON-NLS-1$

    /** Supported property: the item's code (String or Number, matched to the owner's code type). */
    private static final String PROP_CODE = "code"; //$NON-NLS-1$

    /** Supported property: whether the item is a FOLDER (case-normalized to lower case for matching). */
    private static final String PROP_IS_FOLDER = "isfolder"; //$NON-NLS-1$

    /** Supported property (create only): the name of an existing predefined FOLDER to nest under. */
    private static final String PROP_PARENT = "parent"; //$NON-NLS-1$

    /**
     * Supported property: a {@code ChartOfCharacteristicTypes} item's VALUE TYPE ({@code getType()}/
     * {@code setType(TypeDescription)}), built via {@link MetadataTypeBuilder} from the SAME payload
     * shape an attribute's {@code type} property uses. Rejected for any other owner (issue #296 P2).
     */
    private static final String PROP_VALUE_TYPE = "valuetype"; //$NON-NLS-1$

    /** Alias of {@link #PROP_VALUE_TYPE} - the same name an mdclass attribute's type property uses. */
    private static final String PROP_TYPE_ALIAS = "type"; //$NON-NLS-1$

    /** Refused property: identity is the FQN leaf, not a renamable property. */
    private static final String PROP_NAME = "name"; //$NON-NLS-1$

    /**
     * Digit cap for a numeric code when the Catalog's {@code codeLength} is 0 (= unlimited): the
     * 1C platform's own numeric precision maximum. Keeps an "unlimited" code from smuggling in an
     * astronomically large exponent the serializer/renderer would then have to expand.
     */
    private static final int MAX_NUMERIC_CODE_DIGITS = 38;

    // The Russian equivalent of the 'Predefined' FQN kind token - built from lowercase Unicode
    // code points so the string LITERAL stays ASCII (the same non-UTF-8 Tycho-build guard
    // FormElementWriter/MetadataNodeResolver use for their Russian kind tokens). Both accepted
    // spellings are enumerated EXPLICITLY (not via a blanket yo-normalization of the incoming
    // token, which would over-accept a misplaced 'ё' - e.g. "прёдопределенные"):
    //   RU_PREDEFINED    = "предопределенные" (the yo-normalized 'е' spelling)
    //   RU_PREDEFINED_YO = "предопределённые" (the natural 'ё' spelling; only index 11 differs)
    private static final String RU_PREDEFINED = MetadataLanguageUtils.cp(0x043f, 0x0440, 0x0435, 0x0434,
        0x043e, 0x043f, 0x0440, 0x0435, 0x0434, 0x0435, 0x043b, 0x0435, 0x043d, 0x043d, 0x044b, 0x0435);

    private static final String RU_PREDEFINED_YO = MetadataLanguageUtils.cp(0x043f, 0x0440, 0x0435, 0x0434,
        0x043e, 0x043f, 0x0440, 0x0435, 0x0434, 0x0435, 0x043b, 0x0451, 0x043d, 0x043d, 0x044b, 0x0435);

    private PredefinedWriter()
    {
        // utility class
    }

    // ============================================================================================
    // FQN parsing
    // ============================================================================================

    /** A parsed predefined-item FQN: {@code <OwnerType>.<OwnerName>.Predefined.<ItemName>}. */
    public static final class PredefinedRef
    {
        /** Owner metadata TYPE token, as supplied in the FQN (English or Russian), e.g. {@code Catalog}. */
        public final String ownerType;
        /** Owner metadata object Name, e.g. {@code Products}. */
        public final String ownerName;
        /** The predefined item's programmatic Name (the FQN leaf; identity of the item). */
        public final String itemName;

        PredefinedRef(String ownerType, String ownerName, String itemName)
        {
            this.ownerType = ownerType;
            this.ownerName = ownerName;
            this.itemName = itemName;
        }

        /** The {@code Type.Object} owner FQN. */
        public String ownerFqn()
        {
            return ownerType + "." + ownerName; //$NON-NLS-1$
        }
    }

    /**
     * Parses a predefined-item FQN: exactly 4 dot-separated parts with a {@code Predefined} (or its
     * Russian equivalent) token at position 2 - {@code Type.Object.Predefined.ItemName}. Mirrors
     * {@link FormElementWriter#parse}: recognized purely by SHAPE, regardless of whether the owner
     * TYPE is actually supported (that is a separate, later check - see
     * {@link #unsupportedOwnerTypeError}) or whether the owner/item actually exist. Returns
     * {@code null} when {@code normFqn} does not have this shape, so the caller falls through to the
     * generic mdclass-member resolution.
     *
     * @param normFqn the (type-normalized) full-name FQN
     * @return the parsed reference, or {@code null} when this is not a predefined-item FQN
     */
    public static PredefinedRef parseRef(String normFqn)
    {
        if (normFqn == null || normFqn.isEmpty())
        {
            return null;
        }
        String[] p = normFqn.split("\\."); //$NON-NLS-1$
        if (p.length != 4 || !isPredefinedToken(p[2]))
        {
            return null;
        }
        return new PredefinedRef(p[0], p[1], p[3]);
    }

    /** Whether {@code token} is the (bilingual, case-insensitive) {@code Predefined} kind token. */
    private static boolean isPredefinedToken(String token)
    {
        if (token == null)
        {
            return false;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        // Accept English "predefined" and EXACTLY the two valid Russian spellings (the 'е' and the
        // natural 'ё' form). A blanket yo-normalization of the token would be too loose - it would
        // also accept a 'ё' misplaced onto any of RU_PREDEFINED's five 'е' positions.
        return "predefined".equals(t) || RU_PREDEFINED.equals(t) || RU_PREDEFINED_YO.equals(t); //$NON-NLS-1$
    }

    // ============================================================================================
    // Owner-type support gate
    // ============================================================================================

    /**
     * Checks whether {@code ownerTypeToken} (the FQN's leading TYPE token, English or Russian)
     * supports authored predefined items in this version. Side-effect-free; needs no configuration
     * (a pure token-level check), so it can fail fast before any project/model resolution.
     *
     * @param ownerTypeToken the owner TYPE token, as supplied in the FQN
     * @return {@code null} when supported (Catalog / ChartOfCharacteristicTypes); otherwise a ready,
     *     actionable error message naming the limitation
     */
    public static String unsupportedOwnerTypeError(String ownerTypeToken)
    {
        String canonical = MetadataTypeUtils.toEnglishSingular(ownerTypeToken);
        if ("Catalog".equals(canonical) || "ChartOfCharacteristicTypes".equals(canonical)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return null;
        }
        if ("ChartOfAccounts".equals(canonical) || "ChartOfCalculationTypes".equals(canonical)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "Predefined items on '" + canonical + "' are not yet supported by this tool: it " //$NON-NLS-1$ //$NON-NLS-2$
                + "carries a richer per-item model (AccountType / AccountingFlags / ExtDimensionTypes " //$NON-NLS-1$
                + "for a Chart of Accounts, or base / displaced / leading arrays for a Chart of " //$NON-NLS-1$
                + "Calculation Types) this version does not author. Author it directly in EDT."; //$NON-NLS-1$
        }
        if (canonical != null)
        {
            return "'" + canonical + "' does not have predefined items. The '...Predefined.<Item>' " //$NON-NLS-1$ //$NON-NLS-2$
                + "address is supported only on Catalog and ChartOfCharacteristicTypes."; //$NON-NLS-1$
        }
        return "Unknown metadata type '" + ownerTypeToken + "'. Predefined items are supported on " //$NON-NLS-1$ //$NON-NLS-2$
            + "Catalog and ChartOfCharacteristicTypes."; //$NON-NLS-1$
    }

    // ============================================================================================
    // properties parsing (shared by create_metadata / modify_metadata)
    // ============================================================================================

    /**
     * Parsed {@code properties} entries for a predefined-item create/modify. Fields are public (not
     * accessor-wrapped): populated by {@link #parseProperties} from the wire JSON in normal use, and
     * set directly by unit tests exercising {@link #create}/{@link #modify} without a JSON round-trip.
     */
    public static final class ItemProps
    {
        public String description;
        public boolean descriptionSet;
        /** The raw JSON value of a supplied {@code code} property; strict-typed downstream. */
        public JsonElement code;
        public boolean codeSet;
        public Boolean isFolder;
        public boolean isFolderSet;
        /** Create-only: the name of an existing predefined FOLDER to nest the new item under. */
        public String parentName;
        /**
         * The raw JSON value of a supplied {@code valueType} (alias {@code type}) property - a
         * {@code ChartOfCharacteristicTypes} item's VALUE TYPE. Meaningless for any other owner (see
         * {@link #applyValueType}). A JSON null clears an existing value type (modify only; create
         * rejects it - mirrors {@link #code}).
         */
        public JsonElement valueType;
        public boolean valueTypeSet;
        /**
         * Type-resolution CONTEXT the caller (create_metadata / modify_metadata) stashes here - NOT
         * parsed from JSON - so {@link #applyValueType} can build the {@code TypeDescription} via
         * {@link MetadataTypeBuilder}, the SAME platform machinery an attribute's {@code type}
         * property uses. This is the one place this otherwise pure-EMF writer needs a resolution
         * context; left {@code null}/{@code false} whenever {@code valueType} is never set (every
         * existing create/modify test), so those callers never touch it.
         */
        public Configuration config;
        public Version version;
        public boolean isExtensionProject;
    }

    /**
     * Parses the {@code properties} array into {@code out}. Supported names: {@code description},
     * {@code code}, {@code isFolder}, and (create only) {@code parent}. {@code name} is always
     * refused - a predefined item's identity is the FQN leaf, not a renamable property (delete and
     * re-create instead). On modify, {@code parent} (a move) is refused with an actionable
     * "not yet supported" message rather than silently ignored.
     *
     * @param properties the raw {@code properties} array (may be {@code null}, treated as empty)
     * @param isModify {@code true} when parsing for {@code modify_metadata} (rejects {@code parent})
     * @param out the props holder to fill
     * @return a ready JSON error string on the first malformed/unsupported entry, or {@code null} on success
     */
    public static String parseProperties(List<JsonObject> properties, boolean isModify, ItemProps out)
    {
        if (properties == null)
        {
            return null;
        }
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get(KEY_NAME));
            if (name == null || name.isEmpty())
            {
                return ToolResult.error("Each entry in 'properties' needs a non-empty 'name'.").toJson(); //$NON-NLS-1$
            }
            String err = applyOneProperty(name, prop, isModify, out);
            if (err != null)
            {
                return err;
            }
        }
        return null;
    }

    /**
     * Applies one {@code properties} entry (already name-validated) to {@code out}. Extracted from
     * {@link #parseProperties} to stay within the per-method complexity budget; same contract (a
     * ready JSON error, or {@code null} on success).
     */
    private static String applyOneProperty(String name, JsonObject prop, boolean isModify, ItemProps out)
    {
        // Locale.ROOT: under a Turkish default locale 'I'.toLowerCase() is a dotless 'ı', which
        // would silently stop matching ISFOLDER/DESCRIPTION/the Predefined token.
        switch (name.toLowerCase(Locale.ROOT))
        {
            case PROP_NAME:
                return ToolResult.error("Renaming a predefined item is not supported: its identity is " //$NON-NLS-1$
                    + "the FQN leaf ('...Predefined.<Item>'). Delete it and create a new item under " //$NON-NLS-1$
                    + "the new name.").toJson(); //$NON-NLS-1$
            case PROP_DESCRIPTION:
                return applyDescriptionProperty(prop, out);
            case PROP_CODE:
            {
                // CLEAR on modify: a JSON null value clears an existing code. The MCP wire drops a
                // null-valued key on the way in, so a MISSING value on MODIFY is treated as that same
                // clear. On CREATE there is nothing to clear, so a missing/null value is malformed.
                boolean codeCleared = !prop.has(KEY_VALUE) || prop.get(KEY_VALUE).isJsonNull();
                if (codeCleared && !isModify)
                {
                    return ToolResult.error("The 'code' entry needs a 'value' (a JSON string or " //$NON-NLS-1$
                        + "number matched to the owner's code type; on modify_metadata, omit the value " //$NON-NLS-1$
                        + "or pass null to clear an existing code).").toJson(); //$NON-NLS-1$
                }
                out.code = codeCleared ? JsonNull.INSTANCE : prop.get(KEY_VALUE);
                out.codeSet = true;
                return null;
            }
            case PROP_IS_FOLDER:
                return applyIsFolderProperty(prop, out);
            case PROP_PARENT:
                if (isModify)
                {
                    return ToolResult.error("Moving a predefined item to a different parent folder is " //$NON-NLS-1$
                        + "not yet supported by modify_metadata; delete the item and re-create it with " //$NON-NLS-1$
                        + "the new 'parent' (a create-time-only property).").toJson(); //$NON-NLS-1$
                }
                return applyParentProperty(prop, out);
            case PROP_VALUE_TYPE:
            case PROP_TYPE_ALIAS:
                return applyValueTypeProperty(prop, out, isModify);
            default:
                return ToolResult.error("Property '" + name + "' is not supported for a predefined item. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Supported: description, code, isFolder, valueType (alias 'type'; " //$NON-NLS-1$
                    + "ChartOfCharacteristicTypes only)" //$NON-NLS-1$
                    + (isModify ? "" : ", parent (create only)") + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * {@code description} is FREE TEXT (like a synonym), so it takes any JSON string as-is - no
     * name-style yo-normalization, and an explicitly supplied empty string is honored (only an
     * OMITTED description defaults to the item's Name, in {@link #create}). It cannot be "cleared"
     * to null - a null/missing value is a type error, not an unset.
     */
    private static String applyDescriptionProperty(JsonObject prop, ItemProps out)
    {
        JsonElement v = prop.get(KEY_VALUE);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString())
        {
            return ToolResult.error("'description' must be a JSON string; got " + jsonLabel(v) + "."). //$NON-NLS-1$ //$NON-NLS-2$
                toJson();
        }
        out.description = v.getAsString();
        out.descriptionSet = true;
        return null;
    }

    /** {@code parent} (create only) must be the non-empty Name of an existing predefined folder. */
    private static String applyParentProperty(JsonObject prop, ItemProps out)
    {
        JsonElement v = prop.get(KEY_VALUE);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()
            || v.getAsString().trim().isEmpty())
        {
            return ToolResult.error("'parent' must be a non-empty JSON string (the Name of an " //$NON-NLS-1$
                + "existing predefined FOLDER on the same owner); got " + jsonLabel(v) + ". Omit it " //$NON-NLS-1$ //$NON-NLS-2$
                + "entirely for a top-level item.").toJson(); //$NON-NLS-1$
        }
        out.parentName = v.getAsString();
        return null;
    }

    /**
     * {@code valueType} (alias {@code type}) - a {@code ChartOfCharacteristicTypes} item's VALUE TYPE,
     * built from the SAME payload shape an attribute's {@code type} property uses (e.g.
     * {@code {types:[{kind:'String', length:50}]}}). A MISSING or explicit-null {@code value} CLEARS
     * the value type on MODIFY (the MCP wire drops a null-valued key, so a client's explicit null
     * arrives as a missing value - both mean clear); on CREATE it is malformed (nothing to clear).
     * The clear is carried as {@code JsonNull} to {@link #modify} / {@link #applyValueType}. The
     * owner-type gate (CCT only) and the actual TypeDescription build
     * run later, in {@link #applyValueType}, once the item's OWNER is known.
     */
    private static String applyValueTypeProperty(JsonObject prop, ItemProps out, boolean isModify)
    {
        // CLEAR on modify: a JSON null clears an existing value type. The MCP wire drops a null-valued
        // key, so a MISSING value on MODIFY is treated as that same clear. On CREATE there is nothing
        // to clear, so a missing/null value is malformed.
        boolean cleared = !prop.has(KEY_VALUE) || prop.get(KEY_VALUE).isJsonNull();
        if (cleared && !isModify)
        {
            return ToolResult.error("The 'valueType' entry needs a 'value' (a type spec object like " //$NON-NLS-1$
                + "{types:[{kind:'String', length:50}]}, the SAME shape an attribute's 'type' " //$NON-NLS-1$
                + "property uses; on modify_metadata, omit the value or pass null to clear an existing " //$NON-NLS-1$
                + "value type). Applies only to a ChartOfCharacteristicTypes predefined item.") //$NON-NLS-1$
                .toJson();
        }
        out.valueType = cleared ? JsonNull.INSTANCE : prop.get(KEY_VALUE);
        out.valueTypeSet = true;
        return null;
    }

    /** A short, safe wire label for a bad JSON value ({@code missing} / {@code null} / the value). */
    private static String jsonLabel(JsonElement v)
    {
        if (v == null)
        {
            return "no value"; //$NON-NLS-1$
        }
        if (v.isJsonNull())
        {
            return "null"; //$NON-NLS-1$
        }
        String s = v.toString();
        return s.length() > 60 ? "'" + s.substring(0, 60) + "..." + "'" : "'" + s + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    private static String applyIsFolderProperty(JsonObject prop, ItemProps out)
    {
        JsonElement v = prop.get(KEY_VALUE);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean())
        {
            return ToolResult.error("'isFolder' must be a JSON boolean (true/false).").toJson(); //$NON-NLS-1$
        }
        out.isFolder = v.getAsBoolean();
        out.isFolderSet = true;
        return null;
    }

    private static String asString(JsonElement el)
    {
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    // ============================================================================================
    // recursive find (items + content trees) - the single navigation primitive for find/create/
    // modify/delete/render. Deliberately NOT predefinedItems() (a flattened, derived op) for writes.
    // ============================================================================================

    /** An item found by {@link #locate}, with the list it is contained in and its parent's Name. */
    private static final class Located
    {
        final PredefinedItem item;
        final List<? extends PredefinedItem> containerList;
        /** The containing item's Name, or {@code null} when the item is at the owner's top level. */
        final String parentName;

        Located(PredefinedItem item, List<? extends PredefinedItem> containerList, String parentName)
        {
            this.item = item;
            this.containerList = containerList;
            this.parentName = parentName;
        }
    }

    /**
     * One frame of the ITERATIVE pre-order walks below: a containing list, its owning folder's Name,
     * its depth and a cursor. All tree walks here are iterative (explicit stack, not recursion) so a
     * pathologically deep authored hierarchy degrades into an actionable "not found"/slow walk, never
     * an uncatchable {@link StackOverflowError} on the wire thread.
     */
    private static final class WalkFrame
    {
        final List<? extends PredefinedItem> list;
        final String parentName;
        final int depth;
        int index;

        WalkFrame(List<? extends PredefinedItem> list, String parentName, int depth)
        {
            this.list = list;
            this.parentName = parentName;
            this.depth = depth;
        }
    }

    private static Located locate(EObject owner, String name)
    {
        if (name == null || name.isEmpty())
        {
            return null;
        }
        Predefined predefined = getPredefinedContainer(owner);
        if (predefined == null)
        {
            return null;
        }
        ArrayDeque<WalkFrame> stack = new ArrayDeque<>();
        stack.push(new WalkFrame(topItems(predefined), null, 0));
        while (!stack.isEmpty())
        {
            WalkFrame frame = stack.peek();
            if (frame.index >= frame.list.size())
            {
                stack.pop();
                continue;
            }
            PredefinedItem it = frame.list.get(frame.index++);
            if (name.equalsIgnoreCase(it.getName()))
            {
                return new Located(it, frame.list, frame.parentName);
            }
            List<? extends PredefinedItem> children = childrenOf(it);
            if (!children.isEmpty())
            {
                stack.push(new WalkFrame(children, it.getName(), frame.depth + 1));
            }
        }
        return null;
    }

    /**
     * Locates a predefined item EXACT-first, then retries with the yo-normalized name ('ё'-&gt;'е') on
     * a miss - the SAME addressing tolerance {@code MetadataNodeResolver#resolveExistingWithYoFallback}
     * gives the generic mdclass and XDTO paths. Used by every READ/MODIFY/DELETE lookup: create_metadata
     * normalizes a new item's Name (and its {@code parent} reference) by default, so a caller who later
     * re-types the original 'ё' spelling ({@code ...Predefined.Мёд}) must still resolve the stored
     * (normalized) item. The create-time DUPLICATE check deliberately does NOT use this (it stays exact,
     * the #291 lesson: with normalizeYo=false a caller may author distinct yo-variant names).
     */
    private static Located locateYo(EObject owner, String name)
    {
        Located exact = locate(owner, name);
        if (exact != null || name == null)
        {
            return exact;
        }
        String normalized = MdNameNormalizer.normalizeYo(name);
        return normalized.equals(name) ? null : locate(owner, normalized);
    }

    /**
     * Finds a predefined item by recursive name match over the owner's items+content tree, EXACT-first
     * with a yo-normalized fallback (see {@link #locateYo}) - a read/modify/delete lookup, not the
     * create-time duplicate check.
     *
     * @param owner the owner object ({@link Catalog} or {@link ChartOfCharacteristicTypes})
     * @param name the item's programmatic Name
     * @return the found item, or {@code null}
     */
    public static PredefinedItem findByName(EObject owner, String name)
    {
        Located l = locateYo(owner, name);
        return l != null ? l.item : null;
    }

    /** A found item plus its parent's Name (for rendering), or {@code null} when not found. */
    public static final class ItemLookup
    {
        public final PredefinedItem item;
        /** The containing folder's Name, or {@code null} when the item is top-level. */
        public final String parentName;

        ItemLookup(PredefinedItem item, String parentName)
        {
            this.item = item;
            this.parentName = parentName;
        }
    }

    /**
     * Like {@link #findByName}, but also returns the item's parent Name (for a single-item render).
     *
     * @param owner the owner object
     * @param name the item's programmatic Name
     * @return the lookup result, or {@code null} when not found
     */
    public static ItemLookup lookup(EObject owner, String name)
    {
        Located l = locateYo(owner, name);
        return l != null ? new ItemLookup(l.item, l.parentName) : null;
    }

    /** Counts every descendant of {@code item} (its whole content tree), NOT including itself. */
    public static int countDescendants(PredefinedItem item)
    {
        int count = 0;
        ArrayDeque<PredefinedItem> stack = new ArrayDeque<>(childrenOf(item));
        while (!stack.isEmpty())
        {
            PredefinedItem next = stack.pop();
            count++;
            stack.addAll(childrenOf(next));
        }
        return count;
    }

    /**
     * Flattened {@code {name, eClassName}} rows for every DESCENDANT of {@code item} (depth-first,
     * document order), NOT including {@code item} itself - the delete preview's cascade listing.
     *
     * @param item the folder whose content tree to flatten
     * @return the descendant rows (empty for a plain item)
     */
    public static List<String[]> descendantRows(PredefinedItem item)
    {
        List<String[]> out = new ArrayList<>();
        ArrayDeque<WalkFrame> stack = new ArrayDeque<>();
        stack.push(new WalkFrame(childrenOf(item), item.getName(), 0));
        while (!stack.isEmpty())
        {
            WalkFrame frame = stack.peek();
            if (frame.index >= frame.list.size())
            {
                stack.pop();
                continue;
            }
            PredefinedItem next = frame.list.get(frame.index++);
            out.add(new String[] { next.getName(), next.eClass().getName() });
            List<? extends PredefinedItem> children = childrenOf(next);
            if (!children.isEmpty())
            {
                stack.push(new WalkFrame(children, next.getName(), frame.depth + 1));
            }
        }
        return out;
    }

    /**
     * Every DESCENDANT {@link PredefinedItem} of {@code item} (its whole content tree), NOT including
     * {@code item} itself - the OBJECT counterpart of {@link #descendantRows} (which flattens to
     * display rows). Used by {@code delete_metadata}'s incoming-reference check (issue #296 P1): a
     * FOLDER delete cascades its whole subtree, so the back-reference scan must cover every item the
     * delete would actually remove, not just the folder itself. Order is irrelevant to that caller
     * (a plain traversal, not the depth-tagged {@link #descendantRows} walk).
     *
     * @param item the folder whose content tree to flatten
     * @return the descendant items (empty for a plain item)
     */
    public static List<PredefinedItem> descendants(PredefinedItem item)
    {
        List<PredefinedItem> out = new ArrayList<>();
        ArrayDeque<PredefinedItem> stack = new ArrayDeque<>(childrenOf(item));
        while (!stack.isEmpty())
        {
            PredefinedItem next = stack.pop();
            out.add(next);
            stack.addAll(childrenOf(next));
        }
        return out;
    }

    // ============================================================================================
    // read-side: flat listing for get_metadata_details' owner-level "Predefined items" section
    // ============================================================================================

    /** One row of the owner-level predefined-items listing (a flattened, depth-tagged tree walk). */
    public static final class ItemRow
    {
        public final String name;
        /** The item's display code (String or Number, already unwrapped), or {@code null} if unset. */
        public final String code;
        public final String description;
        public final boolean isFolder;
        /** The containing folder's Name, or {@code null} for a top-level item. */
        public final String parentName;
        /** Nesting depth, 0 = top-level. */
        public final int depth;
        /**
         * The item's VALUE TYPE, already rendered as text (e.g. {@code "String, CatalogRef.Products"}),
         * or {@code null} when unset / not applicable (only a {@code ChartOfCharacteristicTypes} item
         * carries one - issue #296 P2).
         */
        public final String valueType;

        ItemRow(String name, String code, String description, boolean isFolder, String parentName, int depth,
            String valueType)
        {
            this.name = name;
            this.code = code;
            this.description = description;
            this.isFolder = isFolder;
            this.parentName = parentName;
            this.depth = depth;
            this.valueType = valueType;
        }
    }

    /**
     * Lists every predefined item on {@code owner} (recursively, items + content), depth-first, in
     * document order. Returns an empty list when the owner has no {@code predefined} content yet
     * (never {@code null}).
     *
     * @param owner the owner object ({@link Catalog} or {@link ChartOfCharacteristicTypes})
     * @return the flattened rows
     */
    public static List<ItemRow> listAll(EObject owner)
    {
        List<ItemRow> rows = new ArrayList<>();
        Predefined predefined = getPredefinedContainer(owner);
        if (predefined == null)
        {
            return rows;
        }
        ArrayDeque<WalkFrame> stack = new ArrayDeque<>();
        stack.push(new WalkFrame(topItems(predefined), null, 0));
        while (!stack.isEmpty())
        {
            WalkFrame frame = stack.peek();
            if (frame.index >= frame.list.size())
            {
                stack.pop();
                continue;
            }
            PredefinedItem item = frame.list.get(frame.index++);
            rows.add(new ItemRow(item.getName(), displayCode(item), item.getDescription(), isFolder(item),
                frame.parentName, frame.depth, displayValueType(item)));
            List<? extends PredefinedItem> children = childrenOf(item);
            if (!children.isEmpty())
            {
                stack.push(new WalkFrame(children, item.getName(), frame.depth + 1));
            }
        }
        return rows;
    }

    // ============================================================================================
    // write-side: create / modify / delete (operate on the ALREADY-RESOLVED, tx-bound owner)
    // ============================================================================================

    /** The outcome of a create/modify: either the affected item, or a ready, actionable error. */
    public static final class WriteResult
    {
        public final String error;
        public final PredefinedItem item;

        private WriteResult(String error, PredefinedItem item)
        {
            this.error = error;
            this.item = item;
        }

        static WriteResult ok(PredefinedItem item)
        {
            return new WriteResult(null, item);
        }

        static WriteResult fail(String error)
        {
            return new WriteResult(error, null);
        }

        public boolean isError()
        {
            return error != null;
        }
    }

    /**
     * Creates a new predefined item named {@code itemName} on {@code owner}. Validates the exact
     * (recursive) duplicate check, resolves an optional {@code parent} FOLDER, lazily creates the
     * owner's {@code predefined} container when absent, sets a mandatory random {@code id}, the
     * {@code description} (defaulting to {@code itemName} when omitted), the optional
     * {@code isFolder} flag and the optional {@code code} (matched to the owner's code type; omitted
     * -&gt; left UNSET, never invented/autonumbered).
     *
     * @param owner the (already re-fetched, tx-bound) owner - must be a {@link Catalog} or
     *     {@link ChartOfCharacteristicTypes}
     * @param itemName the new item's programmatic Name (already identifier-validated by the caller)
     * @param props the parsed create-time properties
     * @param expectedNotExists when {@code true}, a duplicate reports a sharper "stale snapshot" error
     * @return the result: the created item, or a ready error
     */
    public static WriteResult create(EObject owner, String itemName, ItemProps props,
        boolean expectedNotExists)
    {
        if (!(owner instanceof Catalog) && !(owner instanceof ChartOfCharacteristicTypes))
        {
            return WriteResult.fail("Owner does not support predefined items: " + owner.eClass().getName()); //$NON-NLS-1$
        }
        if (locate(owner, itemName) != null)
        {
            return WriteResult.fail(expectedNotExists
                ? "Precondition failed: you set expectedNotExists, but the predefined item '" + itemName //$NON-NLS-1$
                    + "' already exists on " + ownerLabel(owner) + " - your snapshot is stale. Re-read with " //$NON-NLS-1$ //$NON-NLS-2$
                    + "get_metadata_details, then modify the existing item instead of creating a duplicate." //$NON-NLS-1$
                : "Predefined item already exists: '" + itemName + "' on " + ownerLabel(owner) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        PredefinedItem parent = null;
        if (props.parentName != null && !props.parentName.trim().isEmpty())
        {
            Located parentLoc = locateYo(owner, props.parentName);
            if (parentLoc == null)
            {
                return WriteResult.fail("Parent predefined item (folder) not found: '" + props.parentName //$NON-NLS-1$
                    + "' on " + ownerLabel(owner) + ". Create the folder first, or omit 'parent' for a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "top-level item."); //$NON-NLS-1$
            }
            if (!isFolder(parentLoc.item))
            {
                return WriteResult.fail("Parent '" + props.parentName + "' on " + ownerLabel(owner) //$NON-NLS-1$ //$NON-NLS-2$
                    + " is not a folder (isFolder=false); only a folder item can hold children."); //$NON-NLS-1$
            }
            parent = parentLoc.item;
        }

        // An OMITTED description defaults to the Name (the designer's UX); a supplied one - even an
        // explicit empty string - is honored as-is (parseProperties already guaranteed it's a string).
        // Validated against the owner's descriptionLength BEFORE any mutation (fail fast, mirrors the
        // codeLength check below) - an over-long description is REJECTED, never silently truncated.
        String description = props.descriptionSet ? props.description : itemName;
        String descriptionLengthErr = validateDescriptionLength(owner, description);
        if (descriptionLengthErr != null)
        {
            return WriteResult.fail(descriptionLengthErr);
        }
        if (props.valueTypeSet && (props.valueType == null || props.valueType.isJsonNull()))
        {
            // A JSON-null valueType is a MODIFY concept (clearing an existing value); at create there
            // is nothing to clear - omit the property to leave the value type unset. Checked up front
            // (before any mutation), mirroring the code-null-at-create guard below.
            return WriteResult.fail("'valueType' cannot be JSON null at create - omit the property to " //$NON-NLS-1$
                + "leave the value type unset (an explicit null clears an existing value type via " //$NON-NLS-1$
                + "modify_metadata)."); //$NON-NLS-1$
        }

        Predefined predefined = getOrCreatePredefined(owner);
        PredefinedItem item = newItem(owner);
        item.setId(UUID.randomUUID());
        item.setName(itemName);
        item.setDescription(description);
        if (props.isFolderSet && props.isFolder != null)
        {
            setFolder(item, props.isFolder);
        }
        if (props.codeSet)
        {
            // A JSON-null code is a MODIFY concept (clearing an existing value); at create there is
            // nothing to clear - omit the property to leave the code unset.
            if (props.code == null || props.code.isJsonNull())
            {
                return WriteResult.fail("'code' cannot be JSON null at create - omit the property to " //$NON-NLS-1$
                    + "leave the code unset (an explicit null clears an existing code via " //$NON-NLS-1$
                    + "modify_metadata)."); //$NON-NLS-1$
            }
            String codeErr = applyCode(owner, item, props.code);
            if (codeErr != null)
            {
                return WriteResult.fail(codeErr);
            }
        }
        if (props.valueTypeSet)
        {
            String valueTypeErr = applyValueType(owner, item, props);
            if (valueTypeErr != null)
            {
                return WriteResult.fail(valueTypeErr);
            }
        }
        attach(predefined, parent, item);
        return WriteResult.ok(item);
    }

    /**
     * Modifies an existing predefined item's {@code description} / {@code code} / {@code isFolder}.
     * A folder-&gt;item transition ({@code isFolder=false} on a folder that still has children) is
     * rejected (remove/move the children first). {@code parent} (a move) is refused upstream in
     * {@link #parseProperties} before this is ever called.
     *
     * @param owner the (already re-fetched, tx-bound) owner
     * @param itemName the item's programmatic Name (identity; never changed here)
     * @param props the parsed modify-time properties (only the {@code *Set} fields are applied)
     * @return the result: the modified item, or a ready error
     */
    public static WriteResult modify(EObject owner, String itemName, ItemProps props)
    {
        Located found = locateYo(owner, itemName);
        if (found == null)
        {
            return WriteResult.fail("Predefined item not found: '" + itemName + "' on " + ownerLabel(owner) //$NON-NLS-1$ //$NON-NLS-2$
                + ". Use get_metadata_details to list the owner's predefined items."); //$NON-NLS-1$
        }
        PredefinedItem item = found.item;
        if (props.descriptionSet)
        {
            String descriptionLengthErr = validateDescriptionLength(owner, props.description);
            if (descriptionLengthErr != null)
            {
                return WriteResult.fail(descriptionLengthErr);
            }
            item.setDescription(props.description);
        }
        if (props.codeSet)
        {
            String codeErr = applyCode(owner, item, props.code);
            if (codeErr != null)
            {
                return WriteResult.fail(codeErr);
            }
        }
        if (props.valueTypeSet)
        {
            String valueTypeErr = applyValueType(owner, item, props);
            if (valueTypeErr != null)
            {
                return WriteResult.fail(valueTypeErr);
            }
        }
        if (props.isFolderSet)
        {
            boolean newValue = Boolean.TRUE.equals(props.isFolder);
            if (isFolder(item) && !newValue && !childrenOf(item).isEmpty())
            {
                return WriteResult.fail("Cannot change '" + itemName + "' from a folder to a plain item: " //$NON-NLS-1$ //$NON-NLS-2$
                    + "it has " + childrenOf(item).size() + " child item(s). Move or delete them first."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            setFolder(item, newValue);
        }
        return WriteResult.ok(item);
    }

    // ---- delete (two-phase: preview + confirm) ----------------------------------------------------

    /** The delete preview: whether found, whether a folder, and (if a folder) every descendant. */
    public static final class DeletePreview
    {
        public final boolean found;
        public final boolean isFolder;
        /** Count of every descendant a folder delete cascades (0 for a plain item). */
        public final int descendantCount;
        /** The item's concrete EClass name (e.g. {@code CatalogPredefinedItem}), or {@code null}. */
        public final String kind;
        /** Flattened {@code {name, eClassName}} rows of every cascaded descendant (never null). */
        public final List<String[]> descendants;

        DeletePreview(boolean found, boolean isFolder, String kind, List<String[]> descendants)
        {
            this.found = found;
            this.isFolder = isFolder;
            this.descendantCount = descendants.size();
            this.kind = kind;
            this.descendants = descendants;
        }
    }

    /**
     * Previews deleting the predefined item named {@code itemName} on {@code owner}: whether it is a
     * FOLDER, and (if so) which descendants the delete would cascade. Read-only.
     *
     * @param owner the owner object
     * @param itemName the item's programmatic Name
     * @return the preview ({@link DeletePreview#found} is {@code false} when there is no such item)
     */
    public static DeletePreview preview(EObject owner, String itemName)
    {
        Located found = locateYo(owner, itemName);
        if (found == null)
        {
            return new DeletePreview(false, false, null, Collections.emptyList());
        }
        boolean folder = isFolder(found.item);
        List<String[]> descendants = folder ? descendantRows(found.item) : Collections.emptyList();
        return new DeletePreview(true, folder, found.item.eClass().getName(), descendants);
    }

    /**
     * Deletes the predefined item named {@code itemName} on {@code owner}, removing it from its
     * ACTUAL containing list (the owner's top-level items, or its parent folder's content) - a
     * FOLDER delete cascades its children via EMF containment removal.
     *
     * @param owner the (already re-fetched, tx-bound) owner
     * @param itemName the item's programmatic Name
     * @return the result: the removed item, or a ready "not found" error
     */
    public static WriteResult delete(EObject owner, String itemName)
    {
        Located found = locateYo(owner, itemName);
        if (found == null)
        {
            return WriteResult.fail("Predefined item not found: '" + itemName + "' on " + ownerLabel(owner) //$NON-NLS-1$ //$NON-NLS-2$
                + "."); //$NON-NLS-1$
        }
        found.containerList.remove(found.item);
        return WriteResult.ok(found.item);
    }

    // ============================================================================================
    // internal EMF plumbing (Catalog / ChartOfCharacteristicTypes dispatch by instanceof)
    // ============================================================================================

    private static Predefined getPredefinedContainer(EObject owner)
    {
        if (owner instanceof Catalog)
        {
            return ((Catalog)owner).getPredefined();
        }
        if (owner instanceof ChartOfCharacteristicTypes)
        {
            return ((ChartOfCharacteristicTypes)owner).getPredefined();
        }
        return null;
    }

    private static Predefined getOrCreatePredefined(EObject owner)
    {
        if (owner instanceof Catalog catalog)
        {
            CatalogPredefined p = catalog.getPredefined();
            if (p == null)
            {
                p = MdClassFactory.eINSTANCE.createCatalogPredefined();
                catalog.setPredefined(p);
            }
            return p;
        }
        if (owner instanceof ChartOfCharacteristicTypes types)
        {
            ChartOfCharacteristicTypesPredefined p = types.getPredefined();
            if (p == null)
            {
                p = MdClassFactory.eINSTANCE.createChartOfCharacteristicTypesPredefined();
                types.setPredefined(p);
            }
            return p;
        }
        throw new IllegalArgumentException(
            "Owner does not support predefined items: " + owner.eClass().getName()); //$NON-NLS-1$
    }

    private static PredefinedItem newItem(EObject owner)
    {
        if (owner instanceof Catalog)
        {
            return MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        }
        if (owner instanceof ChartOfCharacteristicTypes)
        {
            return MdClassFactory.eINSTANCE.createChartOfCharacteristicTypesPredefinedItem();
        }
        throw new IllegalArgumentException(
            "Owner does not support predefined items: " + owner.eClass().getName()); //$NON-NLS-1$
    }

    private static List<? extends PredefinedItem> childrenOf(PredefinedItem item)
    {
        if (item instanceof CatalogPredefinedItem)
        {
            return ((CatalogPredefinedItem)item).getContent();
        }
        if (item instanceof ChartOfCharacteristicTypesPredefinedItem)
        {
            return ((ChartOfCharacteristicTypesPredefinedItem)item).getContent();
        }
        return Collections.emptyList();
    }

    private static List<? extends PredefinedItem> topItems(Predefined predefined)
    {
        if (predefined instanceof CatalogPredefined)
        {
            return ((CatalogPredefined)predefined).getItems();
        }
        if (predefined instanceof ChartOfCharacteristicTypesPredefined)
        {
            return ((ChartOfCharacteristicTypesPredefined)predefined).getItems();
        }
        return Collections.emptyList();
    }

    /** Attaches {@code item} to {@code parent}'s content list, or {@code predefined}'s top items. */
    private static void attach(Predefined predefined, PredefinedItem parent, PredefinedItem item)
    {
        if (parent instanceof CatalogPredefinedItem parentCatalogItem && item instanceof CatalogPredefinedItem)
        {
            parentCatalogItem.getContent().add((CatalogPredefinedItem)item);
            return;
        }
        if (parent instanceof ChartOfCharacteristicTypesPredefinedItem parentTypesItem
            && item instanceof ChartOfCharacteristicTypesPredefinedItem)
        {
            parentTypesItem.getContent().add((ChartOfCharacteristicTypesPredefinedItem)item);
            return;
        }
        if (predefined instanceof CatalogPredefined catalogPredefined && item instanceof CatalogPredefinedItem)
        {
            catalogPredefined.getItems().add((CatalogPredefinedItem)item);
            return;
        }
        if (predefined instanceof ChartOfCharacteristicTypesPredefined typesPredefined
            && item instanceof ChartOfCharacteristicTypesPredefinedItem)
        {
            typesPredefined.getItems().add((ChartOfCharacteristicTypesPredefinedItem)item);
        }
    }

    /**
     * Whether {@code item} is a FOLDER ({@code isIsFolder()} on either concrete item subtype).
     *
     * @param item the predefined item
     * @return the folder flag, or {@code false} for an unrecognized item type
     */
    public static boolean isFolder(PredefinedItem item)
    {
        if (item instanceof CatalogPredefinedItem)
        {
            return ((CatalogPredefinedItem)item).isIsFolder();
        }
        if (item instanceof ChartOfCharacteristicTypesPredefinedItem)
        {
            return ((ChartOfCharacteristicTypesPredefinedItem)item).isIsFolder();
        }
        return false;
    }

    private static void setFolder(PredefinedItem item, boolean value)
    {
        if (item instanceof CatalogPredefinedItem)
        {
            ((CatalogPredefinedItem)item).setIsFolder(value);
        }
        else if (item instanceof ChartOfCharacteristicTypesPredefinedItem)
        {
            ((ChartOfCharacteristicTypesPredefinedItem)item).setIsFolder(value);
        }
    }

    /**
     * The item's display code: for a {@link CatalogPredefinedItem} the wrapped {@link Value}
     * (String/Number) is unwrapped to plain text; for a {@link ChartOfCharacteristicTypesPredefinedItem}
     * the plain {@code String} code is returned as-is.
     *
     * @param item the predefined item
     * @return the display code, or {@code null} when unset / unrecognized
     */
    public static String displayCode(PredefinedItem item)
    {
        if (item instanceof CatalogPredefinedItem)
        {
            Value v = ((CatalogPredefinedItem)item).getCode();
            if (v instanceof StringValue)
            {
                return ((StringValue)v).getValue();
            }
            if (v instanceof NumberValue)
            {
                BigDecimal bd = ((NumberValue)v).getValue();
                if (bd == null)
                {
                    return null;
                }
                // Pre-existing configs may hold values with an absurd exponent (either sign) our
                // own write path would reject - render those in scientific notation instead of
                // expanding them into a potentially enormous plain string. Two explicit compares,
                // not Math.abs (abs(Integer.MIN_VALUE) overflows negative and would skip the guard).
                int scale = bd.scale();
                return scale < -MAX_NUMERIC_CODE_DIGITS || scale > MAX_NUMERIC_CODE_DIGITS
                    ? bd.toString() : bd.toPlainString();
            }
            return null;
        }
        if (item instanceof ChartOfCharacteristicTypesPredefinedItem)
        {
            return ((ChartOfCharacteristicTypesPredefinedItem)item).getCode();
        }
        return null;
    }

    /**
     * The item's display VALUE TYPE (issue #296 P2): only a
     * {@link ChartOfCharacteristicTypesPredefinedItem} carries one ({@code getType()}); rendered via
     * {@link #formatValueType}. {@code null} for a {@link CatalogPredefinedItem} (no such concept) or
     * when the CCT item's type is unset.
     *
     * @param item the predefined item
     * @return the rendered value type, or {@code null}
     */
    public static String displayValueType(PredefinedItem item)
    {
        if (item instanceof ChartOfCharacteristicTypesPredefinedItem cctItem)
        {
            return formatValueType(cctItem.getType());
        }
        return null;
    }

    /**
     * Renders a {@link TypeDescription}'s type list as a human-readable, comma-joined string (e.g.
     * {@code "String, CatalogRef.Products"}) - the SAME {@link McoreUtil#getTypeName}/
     * {@link McoreUtil#getTypeNameRu} fallback chain the plugin's other type renderers
     * ({@code AbstractMetadataFormatter#formatType}, {@code MetadataPropertyIntrospector#renderType})
     * already use, never a bespoke type-name derivation.
     *
     * @param typeDesc the value type (e.g. a CCT predefined item's {@code getType()}), or {@code null}
     * @return the rendered type list, or {@code null} when unset/empty
     */
    public static String formatValueType(TypeDescription typeDesc)
    {
        if (typeDesc == null)
        {
            return null;
        }
        EList<TypeItem> types = typeDesc.getTypes();
        if (types == null || types.isEmpty())
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (TypeItem typeItem : types)
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            String typeName = McoreUtil.getTypeName(typeItem);
            if (typeName == null || typeName.isEmpty())
            {
                typeName = McoreUtil.getTypeNameRu(typeItem);
            }
            if (typeName == null || typeName.isEmpty())
            {
                typeName = typeItem.getClass().getSimpleName();
            }
            sb.append(typeName);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Builds/validates the {@code code} value on {@code item}, matched to {@code owner}'s code type:
     * a {@link Catalog} needs an {@code mcore.Value} (a {@link StringValue} or {@link NumberValue},
     * chosen by {@code Catalog#getCodeType()}); a {@link ChartOfCharacteristicTypes} takes a plain
     * {@code String}. A JSON {@code null} value CLEARS the code (used by modify to unset a wrongly-set
     * code; {@code parseProperties} maps a MISSING value on MODIFY to this same clear, since the MCP
     * wire strips a null-valued key - on CREATE a missing value is rejected there instead, nothing to
     * clear). Validates the JSON value's STRICT type (a string code must be a JSON
     * string, a number code a JSON number - #291 lesson) and the owner's {@code codeLength} (0 =
     * unlimited, matching the rest of this plugin's convention), rejecting an over-long code.
     *
     * @return {@code null} on success, or a ready, actionable error message
     */
    private static String applyCode(EObject owner, PredefinedItem item, JsonElement code)
    {
        if (owner instanceof Catalog)
        {
            return applyCatalogCode((Catalog)owner, (CatalogPredefinedItem)item, code);
        }
        if (owner instanceof ChartOfCharacteristicTypes)
        {
            return applyCharacteristicTypesCode((ChartOfCharacteristicTypes)owner,
                (ChartOfCharacteristicTypesPredefinedItem)item, code);
        }
        return "Owner does not support a predefined-item code."; //$NON-NLS-1$
    }

    private static String applyCatalogCode(Catalog catalog, CatalogPredefinedItem item, JsonElement code)
    {
        if (code == null || code.isJsonNull())
        {
            item.setCode(null);
            return null;
        }
        int codeLength = catalog.getCodeLength();
        if (catalog.getCodeType() == CatalogCodeType.NUMBER)
        {
            if (!(code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber()))
            {
                return "'code' must be a JSON number for Catalog '" + catalog.getName() //$NON-NLS-1$
                    + "' (codeType=Number); got '" + code + "'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            BigDecimal bd = code.getAsBigDecimal();
            if (bd.signum() < 0)
            {
                return "'code' " + bd + " is negative; a numeric Catalog code must be a non-negative " //$NON-NLS-1$ //$NON-NLS-2$
                    + "integer."; //$NON-NLS-1$
            }
            // Digit-count math on precision/scale - NEVER materialize the integer (toBigInteger/
            // toPlainString on e.g. 1e100000000 would expand a tiny request into a gigabyte), and
            // never stripTrailingZeros() a scale<=0 value either (on 100E+2147483647 the strip
            // itself underflows the int scale and throws before any validation could run).
            long digitCount;
            if (bd.signum() == 0)
            {
                digitCount = 1;
            }
            else if (bd.scale() <= 0)
            {
                // Already an integer by construction; trailing zeros only shift between the
                // unscaled value and the exponent, so precision - scale IS the exact digit count.
                digitCount = (long)bd.precision() - bd.scale();
            }
            else
            {
                // scale > 0: stripping is safe (|scale| can only shrink toward the value's own
                // precision) and decides integer-vs-fractional.
                BigDecimal stripped = bd.stripTrailingZeros();
                if (stripped.scale() > 0)
                {
                    return "'code' " + bd + " has a fractional part; a numeric Catalog code must be " //$NON-NLS-1$ //$NON-NLS-2$
                        + "a non-negative integer."; //$NON-NLS-1$
                }
                digitCount = (long)stripped.precision() - stripped.scale();
            }
            long limit = codeLength > 0 ? codeLength : MAX_NUMERIC_CODE_DIGITS;
            if (digitCount > limit)
            {
                return codeLength > 0
                    ? "'code' " + bd + " has " + digitCount + " digit(s), exceeding Catalog '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + catalog.getName() + "'s codeLength (" + codeLength + ")." //$NON-NLS-1$ //$NON-NLS-2$
                    : "'code' " + bd + " has " + digitCount + " digit(s), exceeding the platform's " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + "numeric precision cap (" + MAX_NUMERIC_CODE_DIGITS + ")."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            NumberValue nv = McoreFactory.eINSTANCE.createNumberValue();
            // Store the value scale-NORMALIZED to a plain integer. Without this, an input like
            // 0e-1000000000 (zero with a huge positive scale - it passes the integer and digit
            // checks) would be stored as-is and later explode toPlainString() at render/serialize
            // time. Safe here: the digit cap above bounds the expansion, and the fractional check
            // guarantees setScale(0) is exact.
            nv.setValue(bd.setScale(0));
            item.setCode(nv);
            return null;
        }
        if (!(code.isJsonPrimitive() && code.getAsJsonPrimitive().isString()))
        {
            return "'code' must be a JSON string for Catalog '" + catalog.getName() //$NON-NLS-1$
                + "' (codeType=String); got '" + code + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String s = code.getAsString();
        if (codeLength > 0 && s.length() > codeLength)
        {
            return "'code' \"" + s + "\" (" + s.length() + " char(s)) exceeds Catalog '" + catalog.getName() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "'s codeLength (" + codeLength + ")."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        StringValue sv = McoreFactory.eINSTANCE.createStringValue();
        sv.setValue(s);
        item.setCode(sv);
        return null;
    }

    private static String applyCharacteristicTypesCode(ChartOfCharacteristicTypes types,
        ChartOfCharacteristicTypesPredefinedItem item, JsonElement code)
    {
        if (code == null || code.isJsonNull())
        {
            item.setCode(null);
            return null;
        }
        if (!(code.isJsonPrimitive() && code.getAsJsonPrimitive().isString()))
        {
            return "'code' must be a JSON string for ChartOfCharacteristicTypes '" + types.getName() //$NON-NLS-1$
                + "'; got '" + code + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String s = code.getAsString();
        int codeLength = types.getCodeLength();
        if (codeLength > 0 && s.length() > codeLength)
        {
            return "'code' \"" + s + "\" (" + s.length() + " char(s)) exceeds '" + types.getName() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "'s codeLength (" + codeLength + ")."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        item.setCode(s);
        return null;
    }

    /**
     * Builds/validates the {@code valueType} on {@code item} (issue #296 P2): meaningful ONLY for a
     * {@link ChartOfCharacteristicTypesPredefinedItem} - a {@link CatalogPredefinedItem} has no such
     * concept and is rejected outright. An EXPLICIT JSON {@code null} CLEARS an existing value type
     * ({@link #create} rejects a null upstream, mirroring how {@link #applyCode} treats a code clear
     * as a MODIFY concept in practice). Otherwise builds the {@link TypeDescription} via
     * {@link MetadataTypeBuilder#build(JsonElement, Configuration, Version, boolean)} - the SAME
     * payload shape / platform-type resolution an attribute's {@code type} property uses - using the
     * Configuration/Version/isExtensionProject context the caller stashed on {@code props}
     * ({@link ItemProps#config}/{@link ItemProps#version}/{@link ItemProps#isExtensionProject}): this
     * pure-EMF writer has no other way to reach the platform type-resolution machinery, so the caller
     * (create_metadata / modify_metadata) supplies it - mirroring how the generic attribute-type path
     * (e.g. {@code ModifyMetadataTool#prepareTypeDescription}) resolves the same context.
     *
     * @return {@code null} on success, or a ready, actionable error message
     */
    private static String applyValueType(EObject owner, PredefinedItem item, ItemProps props)
    {
        if (!(owner instanceof ChartOfCharacteristicTypes)
            || !(item instanceof ChartOfCharacteristicTypesPredefinedItem cctItem))
        {
            return "'valueType' applies only to a ChartOfCharacteristicTypes predefined item; " //$NON-NLS-1$
                + ownerLabel(owner) + " does not support it (use 'code'/'description'/'isFolder' " //$NON-NLS-1$
                + "instead)."; //$NON-NLS-1$
        }
        JsonElement valueType = props.valueType;
        if (valueType == null || valueType.isJsonNull())
        {
            cctItem.setType(null);
            return null;
        }
        if (props.version == null)
        {
            return "Cannot resolve the platform version needed to build 'valueType'."; //$NON-NLS-1$
        }
        if (props.config == null)
        {
            return "Cannot build 'valueType': the configuration context is unavailable."; //$NON-NLS-1$
        }
        MetadataTypeBuilder.Result result =
            MetadataTypeBuilder.build(valueType, props.config, props.version, props.isExtensionProject);
        if (result.error != null)
        {
            return "Invalid 'valueType': " + result.error; //$NON-NLS-1$
        }
        cctItem.setType(result.typeDescription);
        return null;
    }

    /**
     * The owner's {@code descriptionLength} (issue #296 addendum): {@code Catalog} and
     * {@code ChartOfCharacteristicTypes} both expose {@code getDescriptionLength()} (0 = unlimited,
     * the SAME convention {@code getCodeLength()} uses). {@code 0} (unlimited) for any owner this
     * writer does not yet author predefined items on.
     */
    private static int descriptionLengthOf(EObject owner)
    {
        if (owner instanceof Catalog)
        {
            return ((Catalog)owner).getDescriptionLength();
        }
        if (owner instanceof ChartOfCharacteristicTypes)
        {
            return ((ChartOfCharacteristicTypes)owner).getDescriptionLength();
        }
        return 0;
    }

    /**
     * Validates {@code description}'s length against {@code owner}'s {@code descriptionLength} - the
     * description-side counterpart of {@link #applyCatalogCode}/{@link #applyCharacteristicTypesCode}'s
     * {@code codeLength} check: an over-long description is REJECTED with an actionable error, never
     * silently truncated (0 = unlimited, never rejects).
     *
     * @param owner the predefined item's owner
     * @param description the description text about to be stored (normally never {@code null} -
     *     {@code null} is tolerated defensively and always accepted)
     * @return {@code null} on success, or a ready, actionable error message
     */
    private static String validateDescriptionLength(EObject owner, String description)
    {
        int limit = descriptionLengthOf(owner);
        if (description != null && limit > 0 && description.length() > limit)
        {
            return "'description' \"" + description + "\" (" + description.length() //$NON-NLS-1$ //$NON-NLS-2$
                + " char(s)) exceeds " + ownerLabel(owner) + "'s descriptionLength (" + limit + ")."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return null;
    }

    /** A human-readable {@code Type.Name} label for {@code owner}, for error messages. */
    private static String ownerLabel(EObject owner)
    {
        String type = owner.eClass().getName();
        String name = (owner instanceof MdObject) ? ((MdObject)owner).getName() : null;
        return (name != null && !name.isEmpty()) ? type + "." + name : type; //$NON-NLS-1$
    }
}
