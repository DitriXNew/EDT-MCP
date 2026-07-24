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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.common.AccountType;
import com._1c.g5.v8.dt.metadata.mdclass.AccountingFlag;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefined;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccounts;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccountsPredefined;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccountsPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypes;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypesCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypesPredefined;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypesPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypesPredefined;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypesPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ExtDimensionAccountingFlag;
import com._1c.g5.v8.dt.metadata.mdclass.ExtDimensionType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Predefined;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonArray;
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
 * <b>Scope:</b> {@code Catalog} and {@code ChartOfCharacteristicTypes} (the value-type owner), plus the
 * two richer owners {@code ChartOfCalculationTypes} (a contained {@code mcore.Value} code, an
 * {@code actionPeriodIsBase} flag and the {@code base}/{@code displaced}/{@code leading} NON-containment
 * reference lists to SIBLING predefined calc types - a FLAT model, no folders) and
 * {@code ChartOfAccounts} (a {@code String} code and {@code order}, an {@code accountType} enum, an
 * {@code offBalance} flag, the {@code accountingFlags} reference list to the chart's own accounting
 * flags, the CONTAINED {@code extDimensionTypes} rows - each a {@code characteristicType} reference into
 * the LINKED {@code ChartOfCharacteristicTypes}, a {@code turnover} flag and an
 * {@code extDimensionAccountingFlags} reference list - and the CONTAINED {@code childItems} account
 * hierarchy). All references resolve to a live in-resource {@link EObject} inside the caller's write
 * transaction (never a proxy / FQN string); only the CONTAINMENT trees ({@code content}/{@code childItems})
 * ever enter a recursive walk, so a cycle in a reference list can never loop the request thread.
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

    /** Supported property (ChartOfAccounts only): the account type token (Active / Passive / ActivePassive). */
    private static final String PROP_ACCOUNT_TYPE = "accounttype"; //$NON-NLS-1$

    /** Supported property (ChartOfAccounts only): the off-balance flag (JSON boolean). */
    private static final String PROP_OFF_BALANCE = "offbalance"; //$NON-NLS-1$

    /** Supported property (ChartOfAccounts only): the String order value (validated against orderLength). */
    private static final String PROP_ORDER = "order"; //$NON-NLS-1$

    /** Supported property (ChartOfCalculationTypes only): the action-period-is-base flag (JSON boolean). */
    private static final String PROP_ACTION_PERIOD_IS_BASE = "actionperiodisbase"; //$NON-NLS-1$

    /** Supported property (ChartOfCalculationTypes only): base sibling calc-type names (JSON string array). */
    private static final String PROP_BASE = "base"; //$NON-NLS-1$

    /** Supported property (ChartOfCalculationTypes only): displaced sibling calc-type names (JSON string array). */
    private static final String PROP_DISPLACED = "displaced"; //$NON-NLS-1$

    /** Supported property (ChartOfCalculationTypes only): leading sibling calc-type names (JSON string array). */
    private static final String PROP_LEADING = "leading"; //$NON-NLS-1$

    /** Supported property (ChartOfAccounts only): accounting-flag names on the same chart (JSON string array). */
    private static final String PROP_ACCOUNTING_FLAGS = "accountingflags"; //$NON-NLS-1$

    /** Supported property (ChartOfAccounts only): the ext-dimension-type rows (JSON object array). */
    private static final String PROP_EXT_DIMENSION_TYPES = "extdimensiontypes"; //$NON-NLS-1$

    /** Nested {@code extDimensionTypes} entry key: the linked-CCT predefined item name (the characteristicType). */
    private static final String KEY_CHARACTERISTIC_TYPE = "characteristictype"; //$NON-NLS-1$

    /** Nested {@code extDimensionTypes} entry key: the turnover flag (JSON boolean, default false). */
    private static final String KEY_TURNOVER = "turnover"; //$NON-NLS-1$

    /** Nested {@code extDimensionTypes} entry key: the ext-dimension accounting-flag names (JSON string array). */
    private static final String KEY_EXT_DIMENSION_ACCOUNTING_FLAGS = "extdimensionaccountingflags"; //$NON-NLS-1$

    // The Russian AccountType aliases, built from lowercase Unicode code points so this source stays
    // ASCII (the same non-UTF-8 Tycho-build guard the Russian FQN kind tokens above use). Matched by
    // EXACT (trimmed, lower-cased) token, NEVER contains() - see RU_ACTIVE / RU_PASSIVE below.
    private static final String RU_ACTIVE =
        MetadataLanguageUtils.cp(0x0430, 0x043a, 0x0442, 0x0438, 0x0432, 0x043d, 0x044b, 0x0439);

    private static final String RU_PASSIVE =
        MetadataLanguageUtils.cp(0x043f, 0x0430, 0x0441, 0x0441, 0x0438, 0x0432, 0x043d, 0x044b, 0x0439);

    /**
     * The accepted {@code accountType} tokens, mapped by their EXACT (trimmed, lower-cased) spelling to
     * the platform {@link AccountType} enum literal. Bilingual: the English enum tokens plus the Russian
     * account-type names (built via {@code \\uXXXX} code points). Matched by exact key, never by
     * {@code contains()} (a {@code contains} match would let "activepassivextra" resolve to a real type).
     */
    private static final Map<String, AccountType> ACCOUNT_TYPE_TOKENS = buildAccountTypeTokens();

    private static Map<String, AccountType> buildAccountTypeTokens()
    {
        Map<String, AccountType> m = new LinkedHashMap<>();
        m.put("active", AccountType.ACTIVE); //$NON-NLS-1$
        m.put(RU_ACTIVE, AccountType.ACTIVE);
        m.put("passive", AccountType.PASSIVE); //$NON-NLS-1$
        m.put(RU_PASSIVE, AccountType.PASSIVE);
        m.put("active_passive", AccountType.ACTIVE_PASSIVE); //$NON-NLS-1$
        m.put("activepassive", AccountType.ACTIVE_PASSIVE); //$NON-NLS-1$
        m.put(RU_ACTIVE + "/" + RU_PASSIVE, AccountType.ACTIVE_PASSIVE); //$NON-NLS-1$
        m.put(RU_ACTIVE + RU_PASSIVE, AccountType.ACTIVE_PASSIVE);
        return m;
    }

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
     * @return {@code null} when supported (Catalog / ChartOfCharacteristicTypes / ChartOfAccounts /
     *     ChartOfCalculationTypes); otherwise a ready, actionable error message naming the limitation
     */
    public static String unsupportedOwnerTypeError(String ownerTypeToken)
    {
        String canonical = MetadataTypeUtils.toEnglishSingular(ownerTypeToken);
        if ("Catalog".equals(canonical) || "ChartOfCharacteristicTypes".equals(canonical) //$NON-NLS-1$ //$NON-NLS-2$
            || "ChartOfAccounts".equals(canonical) || "ChartOfCalculationTypes".equals(canonical)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return null;
        }
        if (canonical != null)
        {
            return "'" + canonical + "' does not have predefined items. The '...Predefined.<Item>' " //$NON-NLS-1$ //$NON-NLS-2$
                + "address is supported on Catalog, ChartOfCharacteristicTypes, ChartOfAccounts and " //$NON-NLS-1$
                + "ChartOfCalculationTypes."; //$NON-NLS-1$
        }
        return "Unknown metadata type '" + ownerTypeToken + "'. Predefined items are supported on " //$NON-NLS-1$ //$NON-NLS-2$
            + "Catalog, ChartOfCharacteristicTypes, ChartOfAccounts and ChartOfCalculationTypes."; //$NON-NLS-1$
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

        // ---- ChartOfAccounts-only props (owner-gated in create/modify, like valueType) -----------

        /** Raw {@code accountType} token (bilingual: Active/Passive/ActivePassive + RU); resolved in apply. */
        public String accountType;
        public boolean accountTypeSet;
        /** The {@code offBalance} flag; only the {@code *Set} form is applied. */
        public Boolean offBalance;
        public boolean offBalanceSet;
        /** Raw JSON {@code order} (a String, length-validated against {@code orderLength}); null clears. */
        public JsonElement order;
        public boolean orderSet;
        /** Accounting-flag names (FULL-REPLACE: the supplied list becomes the exact list; empty = clear). */
        public List<String> accountingFlags;
        public boolean accountingFlagsSet;
        /** Ext-dimension-type rows (FULL-REPLACE; each is a {@link ExtDimensionTypeSpec}). */
        public List<ExtDimensionTypeSpec> extDimensionTypes;
        public boolean extDimensionTypesSet;

        // ---- ChartOfCalculationTypes-only props --------------------------------------------------

        /** The {@code actionPeriodIsBase} flag; only the {@code *Set} form is applied. */
        public Boolean actionPeriodIsBase;
        public boolean actionPeriodIsBaseSet;
        /** Base sibling calc-type names (FULL-REPLACE; empty = clear). */
        public List<String> base;
        public boolean baseSet;
        /** Displaced sibling calc-type names (FULL-REPLACE; empty = clear). */
        public List<String> displaced;
        public boolean displacedSet;
        /** Leading sibling calc-type names (FULL-REPLACE; empty = clear). */
        public List<String> leading;
        public boolean leadingSet;
    }

    /**
     * One parsed {@code extDimensionTypes} row for a {@code ChartOfAccounts} predefined item. An
     * {@link ExtDimensionType} is CONTAINMENT (created fresh in the write tx) with no identity of its own,
     * so the whole list is authored by FULL-REPLACE (no partial row edit). Public fields, populated by
     * {@link #parseProperties} from the wire JSON or set directly by unit tests.
     */
    public static final class ExtDimensionTypeSpec
    {
        /** The characteristicType target: a predefined item Name of the chart's LINKED CCT (resolved in apply). */
        public String characteristicType;
        /** The turnover flag (default {@code false} when omitted). */
        public boolean turnover;
        /** Ext-dimension accounting-flag names on the same chart (resolved in apply; may be empty). */
        public List<String> extDimensionAccountingFlags = new ArrayList<>();
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
            case PROP_ACCOUNT_TYPE:
                return applyAccountTypeProperty(prop, out);
            case PROP_OFF_BALANCE:
                return applyOffBalanceProperty(prop, out);
            case PROP_ORDER:
            {
                boolean orderCleared = !prop.has(KEY_VALUE) || prop.get(KEY_VALUE).isJsonNull();
                if (orderCleared && !isModify)
                {
                    return ToolResult.error("The 'order' entry needs a 'value' (a JSON string, " //$NON-NLS-1$
                        + "validated against the ChartOfAccounts orderLength; on modify_metadata, omit " //$NON-NLS-1$
                        + "the value or pass null to clear an existing order).").toJson(); //$NON-NLS-1$
                }
                out.order = orderCleared ? JsonNull.INSTANCE : prop.get(KEY_VALUE);
                out.orderSet = true;
                return null;
            }
            case PROP_ACTION_PERIOD_IS_BASE:
                return applyActionPeriodIsBaseProperty(prop, out);
            case PROP_BASE:
                return applyNameListProperty(prop, PROP_BASE, l -> out.base = l, () -> out.baseSet = true);
            case PROP_DISPLACED:
                return applyNameListProperty(prop, PROP_DISPLACED, l -> out.displaced = l,
                    () -> out.displacedSet = true);
            case PROP_LEADING:
                return applyNameListProperty(prop, PROP_LEADING, l -> out.leading = l,
                    () -> out.leadingSet = true);
            case PROP_ACCOUNTING_FLAGS:
                return applyNameListProperty(prop, PROP_ACCOUNTING_FLAGS, l -> out.accountingFlags = l,
                    () -> out.accountingFlagsSet = true);
            case PROP_EXT_DIMENSION_TYPES:
                return applyExtDimensionTypesProperty(prop, out);
            default:
                return ToolResult.error("Property '" + name + "' is not supported for a predefined item. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Supported: description, code, isFolder, valueType (alias 'type'; " //$NON-NLS-1$
                    + "ChartOfCharacteristicTypes only), accountType / offBalance / order / " //$NON-NLS-1$
                    + "accountingFlags / extDimensionTypes (ChartOfAccounts only), base / displaced / " //$NON-NLS-1$
                    + "leading / actionPeriodIsBase (ChartOfCalculationTypes only)" //$NON-NLS-1$
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

    /**
     * {@code accountType} (ChartOfAccounts only) - a non-empty account-type token. Shape-validated here
     * (a non-empty string); the token→{@link AccountType} resolution and the owner gate run later, once
     * the item's OWNER is known (mirrors {@link #applyValueTypeProperty}).
     */
    private static String applyAccountTypeProperty(JsonObject prop, ItemProps out)
    {
        JsonElement v = prop.get(KEY_VALUE);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()
            || v.getAsString().trim().isEmpty())
        {
            return ToolResult.error("'accountType' must be a non-empty JSON string (Active / Passive / " //$NON-NLS-1$
                + "ActivePassive, or their Russian equivalents); got " + jsonLabel(v) //$NON-NLS-1$
                + ". Applies only to a ChartOfAccounts predefined item.").toJson(); //$NON-NLS-1$
        }
        out.accountType = v.getAsString();
        out.accountTypeSet = true;
        return null;
    }

    private static String applyOffBalanceProperty(JsonObject prop, ItemProps out)
    {
        JsonElement v = prop.get(KEY_VALUE);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean())
        {
            return ToolResult.error("'offBalance' must be a JSON boolean (true/false). Applies only to a " //$NON-NLS-1$
                + "ChartOfAccounts predefined item.").toJson(); //$NON-NLS-1$
        }
        out.offBalance = v.getAsBoolean();
        out.offBalanceSet = true;
        return null;
    }

    private static String applyActionPeriodIsBaseProperty(JsonObject prop, ItemProps out)
    {
        JsonElement v = prop.get(KEY_VALUE);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean())
        {
            return ToolResult.error("'actionPeriodIsBase' must be a JSON boolean (true/false). Applies " //$NON-NLS-1$
                + "only to a ChartOfCalculationTypes predefined item.").toJson(); //$NON-NLS-1$
        }
        out.actionPeriodIsBase = v.getAsBoolean();
        out.actionPeriodIsBaseSet = true;
        return null;
    }

    /**
     * A FULL-REPLACE list-of-names property ({@code base} / {@code displaced} / {@code leading} /
     * {@code accountingFlags}): the {@code value} is a JSON array of non-empty strings (a MISSING or
     * null value means the empty list = clear, symmetric with a {@code valueType} clear). The names are
     * resolved to live in-resource objects later, once the OWNER is known.
     *
     * @param prop the raw property entry
     * @param propName the property's display name (for error messages)
     * @param sink receives the parsed name list
     * @param markSet marks the corresponding {@code *Set} flag
     * @return {@code null} on success, or a ready JSON error
     */
    private static String applyNameListProperty(JsonObject prop, String propName,
        Consumer<List<String>> sink, Runnable markSet)
    {
        JsonElement v = prop.get(KEY_VALUE);
        List<String> names = new ArrayList<>();
        if (v != null && !v.isJsonNull())
        {
            if (!v.isJsonArray())
            {
                return ToolResult.error("'" + propName + "' must be a JSON array of names (e.g. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "[\"Main\", \"Extra\"]); got " + jsonLabel(v) //$NON-NLS-1$
                    + ". Pass [] to clear the list.").toJson(); //$NON-NLS-1$
            }
            String err = collectNames(v.getAsJsonArray(), propName, names);
            if (err != null)
            {
                return err;
            }
        }
        sink.accept(names);
        markSet.run();
        return null;
    }

    /** Collects non-empty string elements of {@code arr} into {@code out}; rejects a non-string element. */
    private static String collectNames(JsonArray arr, String propName, List<String> out)
    {
        for (JsonElement el : arr)
        {
            if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()
                || el.getAsString().trim().isEmpty())
            {
                return ToolResult.error("Each '" + propName + "' entry must be a non-empty JSON string " //$NON-NLS-1$ //$NON-NLS-2$
                    + "(a target Name); got " + jsonLabel(el) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            out.add(el.getAsString());
        }
        return null;
    }

    /**
     * {@code extDimensionTypes} (ChartOfAccounts only) - a FULL-REPLACE array of ext-dimension-type rows,
     * each {@code {characteristicType:"<CctItem>", turnover?:bool, extDimensionAccountingFlags?:[...]}}.
     * Shape-validated here; the cross-owner {@code characteristicType} and flag references resolve later,
     * once the OWNER (and its linked CCT) is known. A MISSING / null value means the empty list = clear.
     */
    private static String applyExtDimensionTypesProperty(JsonObject prop, ItemProps out)
    {
        JsonElement v = prop.get(KEY_VALUE);
        List<ExtDimensionTypeSpec> rows = new ArrayList<>();
        if (v != null && !v.isJsonNull())
        {
            if (!v.isJsonArray())
            {
                return ToolResult.error("'extDimensionTypes' must be a JSON array of rows like " //$NON-NLS-1$
                    + "[{\"characteristicType\":\"Contractors\",\"turnover\":false}]; got " + jsonLabel(v) //$NON-NLS-1$
                    + ". Pass [] to clear the list.").toJson(); //$NON-NLS-1$
            }
            for (JsonElement el : v.getAsJsonArray())
            {
                String err = parseExtDimensionRow(el, rows);
                if (err != null)
                {
                    return err;
                }
            }
        }
        out.extDimensionTypes = rows;
        out.extDimensionTypesSet = true;
        return null;
    }

    /** Parses one {@code extDimensionTypes} row into {@code out}; returns a ready JSON error on a bad shape. */
    private static String parseExtDimensionRow(JsonElement el, List<ExtDimensionTypeSpec> out)
    {
        if (el == null || !el.isJsonObject())
        {
            return ToolResult.error("Each 'extDimensionTypes' entry must be a JSON object " //$NON-NLS-1$
                + "{characteristicType, turnover?, extDimensionAccountingFlags?}; got " + jsonLabel(el) //$NON-NLS-1$
                + ".").toJson(); //$NON-NLS-1$
        }
        JsonObject row = el.getAsJsonObject();
        // The nested row keys are matched case-INSENSITIVELY, exactly like the top-level property
        // names (the name.toLowerCase(Locale.ROOT) switch in applyProperty): the documented keys are
        // camelCase (characteristicType / extDimensionAccountingFlags) but JsonObject.get() is
        // case-sensitive, so read them through a lower-cased view of the row's members (issue #296 P3).
        Map<String, JsonElement> members = lowerCaseKeyed(row);
        ExtDimensionTypeSpec spec = new ExtDimensionTypeSpec();
        JsonElement ct = members.get(KEY_CHARACTERISTIC_TYPE);
        if (ct == null || !ct.isJsonPrimitive() || !ct.getAsJsonPrimitive().isString()
            || ct.getAsString().trim().isEmpty())
        {
            return ToolResult.error("Each 'extDimensionTypes' entry needs a non-empty " //$NON-NLS-1$
                + "'characteristicType' (a predefined item Name of the chart's linked " //$NON-NLS-1$
                + "ChartOfCharacteristicTypes); got " + jsonLabel(ct) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        spec.characteristicType = ct.getAsString();
        JsonElement turnover = members.get(KEY_TURNOVER);
        if (turnover != null && !turnover.isJsonNull())
        {
            if (!turnover.isJsonPrimitive() || !turnover.getAsJsonPrimitive().isBoolean())
            {
                return ToolResult.error("'turnover' in an 'extDimensionTypes' entry must be a JSON " //$NON-NLS-1$
                    + "boolean; got " + jsonLabel(turnover) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            spec.turnover = turnover.getAsBoolean();
        }
        JsonElement flags = members.get(KEY_EXT_DIMENSION_ACCOUNTING_FLAGS);
        if (flags != null && !flags.isJsonNull())
        {
            if (!flags.isJsonArray())
            {
                return ToolResult.error("'extDimensionAccountingFlags' in an 'extDimensionTypes' entry " //$NON-NLS-1$
                    + "must be a JSON array of names; got " + jsonLabel(flags) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String err = collectNames(flags.getAsJsonArray(), KEY_EXT_DIMENSION_ACCOUNTING_FLAGS,
                spec.extDimensionAccountingFlags);
            if (err != null)
            {
                return err;
            }
        }
        out.add(spec);
        return null;
    }

    private static String asString(JsonElement el)
    {
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    /**
     * A lower-cased-key view of a JSON object's members (last spelling wins on a case collision), so
     * the documented camelCase nested keys resolve the same way the top-level property names do (which
     * go through a {@code name.toLowerCase(Locale.ROOT)} switch). {@link JsonObject#get} is
     * case-sensitive, so without this a documented {@code {"characteristicType": ...}} row would be
     * reported as missing its {@code characteristicType} (issue #296 P3).
     */
    private static Map<String, JsonElement> lowerCaseKeyed(JsonObject obj)
    {
        Map<String, JsonElement> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : obj.entrySet())
        {
            m.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        return m;
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

    /**
     * A fresh identity-based visited set for the tree walks below. A disk-loaded {@code .mdo} cannot
     * express a containment cycle (nested XML serializes each item under exactly one parent), and this
     * writer only ever attaches freshly-created items, so a cycle is not reachable in practice - but an
     * in-memory graph COULD contain one, and an unguarded iterative walk would then loop forever on the
     * wire/UI thread. Marking every item on first visit bounds the walk (skip-on-revisit), the same
     * guard the reference-scan walk in {@code MetadataReferenceService} uses (issue #293 P2).
     */
    private static Set<PredefinedItem> newVisitedSet()
    {
        return Collections.newSetFromMap(new IdentityHashMap<>());
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
        Set<PredefinedItem> seen = newVisitedSet();
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
            if (!seen.add(it))
            {
                continue;
            }
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
        Set<PredefinedItem> seen = newVisitedSet();
        seen.add(item);
        ArrayDeque<PredefinedItem> stack = new ArrayDeque<>(childrenOf(item));
        while (!stack.isEmpty())
        {
            PredefinedItem next = stack.pop();
            if (!seen.add(next))
            {
                continue;
            }
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
        Set<PredefinedItem> seen = newVisitedSet();
        seen.add(item);
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
            if (!seen.add(next))
            {
                continue;
            }
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
        Set<PredefinedItem> seen = newVisitedSet();
        seen.add(item);
        ArrayDeque<PredefinedItem> stack = new ArrayDeque<>(childrenOf(item));
        while (!stack.isEmpty())
        {
            PredefinedItem next = stack.pop();
            if (!seen.add(next))
            {
                continue;
            }
            out.add(next);
            stack.addAll(childrenOf(next));
        }
        return out;
    }

    // ============================================================================================
    // read-side: flat listing for get_metadata_details' owner-level "Predefined items" section
    // ============================================================================================

    /**
     * One row of the owner-level predefined-items listing (a flattened, depth-tagged tree walk). Carries
     * the shared columns (name/code/description/folder/valueType) plus the OWNER-SPECIFIC columns, each
     * rendered to display text and {@code null} when not applicable (the dash-when-N/A precedent of
     * {@code valueType}): {@code accountType}/{@code offBalance}/{@code order} and the reference-name
     * lists ({@code base}/{@code displaced}/{@code leading} for a calc type; {@code accountingFlags} and
     * {@code extDimensionTypes} for an account) - all computed from the item via the public
     * {@code display*} helpers so the read caller consumes only strings.
     */
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
        /** ChartOfAccounts account type (e.g. {@code "ActivePassive"}), or {@code null} for other kinds. */
        public final String accountType;
        /** ChartOfAccounts off-balance flag, or {@code null} for other kinds. */
        public final Boolean offBalance;
        /** ChartOfAccounts order String, or {@code null} when unset / other kinds. */
        public final String order;
        /** ChartOfCalculationTypes action-period-is-base flag, or {@code null} for other kinds. */
        public final Boolean actionPeriodIsBase;
        /** ChartOfCalculationTypes base sibling names, comma-joined, or {@code null} when empty / other kinds. */
        public final String base;
        /** ChartOfCalculationTypes displaced sibling names, comma-joined, or {@code null}. */
        public final String displaced;
        /** ChartOfCalculationTypes leading sibling names, comma-joined, or {@code null}. */
        public final String leading;
        /** ChartOfAccounts accounting-flag names, comma-joined, or {@code null} when empty / other kinds. */
        public final String accountingFlags;
        /** ChartOfAccounts ext-dimension-type rows, rendered, or {@code null} when empty / other kinds. */
        public final String extDimensionTypes;

        ItemRow(PredefinedItem item, String parentName, int depth)
        {
            this.name = item.getName();
            this.code = displayCode(item);
            this.description = item.getDescription();
            this.isFolder = isFolder(item);
            this.parentName = parentName;
            this.depth = depth;
            this.valueType = displayValueType(item);
            this.accountType = displayAccountType(item);
            this.offBalance = displayOffBalance(item);
            this.order = displayOrder(item);
            this.actionPeriodIsBase = displayActionPeriodIsBase(item);
            this.base = displayBase(item);
            this.displaced = displayDisplaced(item);
            this.leading = displayLeading(item);
            this.accountingFlags = displayAccountingFlags(item);
            this.extDimensionTypes = displayExtDimensionTypes(item);
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
        Set<PredefinedItem> seen = newVisitedSet();
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
            if (!seen.add(item))
            {
                continue;
            }
            rows.add(new ItemRow(item, frame.parentName, frame.depth));
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
        if (!isSupportedOwner(owner))
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

        // Owner gate for every owner-specific property, BEFORE any mutation (fail fast) - the same
        // discipline as applyValueType: parse unconditionally, reject the wrong owner with an
        // actionable error instead of silently dropping the property.
        String gateErr = rejectCrossOwnerProps(owner, props);
        if (gateErr != null)
        {
            return WriteResult.fail(gateErr);
        }

        WriteResultOrParent parentResolution = resolveCreateParent(owner, props);
        if (parentResolution.error != null)
        {
            return WriteResult.fail(parentResolution.error);
        }
        PredefinedItem parent = parentResolution.parent;

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
        String ownerPropsErr = applyOwnerSpecificProps(owner, item, props);
        if (ownerPropsErr != null)
        {
            return WriteResult.fail(ownerPropsErr);
        }
        attach(predefined, parent, item);
        return WriteResult.ok(item);
    }

    /** A resolved create-time parent (or {@code null} for top-level), or a ready error. */
    private static final class WriteResultOrParent
    {
        final PredefinedItem parent;
        final String error;

        private WriteResultOrParent(PredefinedItem parent, String error)
        {
            this.parent = parent;
            this.error = error;
        }
    }

    /**
     * Resolves the create-time {@code parent} nesting target. {@code ChartOfCalculationTypes} is FLAT and
     * has no {@code parent} at all (rejected up front in {@link #rejectCrossOwnerProps}). For
     * {@code Catalog} / {@code ChartOfCharacteristicTypes} the parent must be an existing FOLDER; for
     * {@code ChartOfAccounts} it is an existing parent ACCOUNT (no folder concept - it nests via
     * {@code childItems}).
     */
    private static WriteResultOrParent resolveCreateParent(EObject owner, ItemProps props)
    {
        if (props.parentName == null || props.parentName.trim().isEmpty())
        {
            return new WriteResultOrParent(null, null);
        }
        Located parentLoc = locateYo(owner, props.parentName);
        if (parentLoc == null)
        {
            boolean account = owner instanceof ChartOfAccounts;
            return new WriteResultOrParent(null, "Parent predefined item (" //$NON-NLS-1$
                + (account ? "account" : "folder") + ") not found: '" + props.parentName //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "' on " + ownerLabel(owner) + ". Create it first, or omit 'parent' for a top-level item."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // A ChartOfAccounts nests accounts under a parent account (no folder flag); Catalog / CCT nest
        // only under a folder item.
        if (!(owner instanceof ChartOfAccounts) && !isFolder(parentLoc.item))
        {
            return new WriteResultOrParent(null, "Parent '" + props.parentName + "' on " //$NON-NLS-1$ //$NON-NLS-2$
                + ownerLabel(owner) + " is not a folder (isFolder=false); only a folder item can hold " //$NON-NLS-1$
                + "children."); //$NON-NLS-1$
        }
        return new WriteResultOrParent(parentLoc.item, null);
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
        String gateErr = rejectCrossOwnerProps(owner, props);
        if (gateErr != null)
        {
            return WriteResult.fail(gateErr);
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
        String ownerPropsErr = applyOwnerSpecificProps(owner, item, props);
        if (ownerPropsErr != null)
        {
            return WriteResult.fail(ownerPropsErr);
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
     * FOLDER, and which descendants the delete would cascade. Read-only. The cascade set is derived from
     * CONTAINMENT children ({@link #descendantRows}, i.e. {@link #childrenOf}), so it covers both a
     * Catalog / CCT FOLDER's content AND a {@code ChartOfAccounts} parent account's {@code childItems}
     * (an account is not a folder, yet its child accounts DO cascade). Byte-equivalent to the previous
     * folder-gated logic for Catalog / CCT (a non-folder there has no containment children, so the set
     * is empty either way).
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
        List<String[]> descendants = descendantRows(found.item);
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
    // internal EMF plumbing (Catalog / ChartOfCharacteristicTypes / ChartOfAccounts /
    // ChartOfCalculationTypes dispatch by instanceof)
    // ============================================================================================

    /** Whether {@code owner} is one of the four owner kinds that carry authored predefined items. */
    private static boolean isSupportedOwner(EObject owner)
    {
        return owner instanceof Catalog || owner instanceof ChartOfCharacteristicTypes
            || owner instanceof ChartOfAccounts || owner instanceof ChartOfCalculationTypes;
    }

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
        if (owner instanceof ChartOfAccounts)
        {
            return ((ChartOfAccounts)owner).getPredefined();
        }
        if (owner instanceof ChartOfCalculationTypes)
        {
            return ((ChartOfCalculationTypes)owner).getPredefined();
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
        if (owner instanceof ChartOfAccounts accounts)
        {
            ChartOfAccountsPredefined p = accounts.getPredefined();
            if (p == null)
            {
                p = MdClassFactory.eINSTANCE.createChartOfAccountsPredefined();
                accounts.setPredefined(p);
            }
            return p;
        }
        if (owner instanceof ChartOfCalculationTypes calc)
        {
            ChartOfCalculationTypesPredefined p = calc.getPredefined();
            if (p == null)
            {
                p = MdClassFactory.eINSTANCE.createChartOfCalculationTypesPredefined();
                calc.setPredefined(p);
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
        if (owner instanceof ChartOfAccounts)
        {
            return MdClassFactory.eINSTANCE.createChartOfAccountsPredefinedItem();
        }
        if (owner instanceof ChartOfCalculationTypes)
        {
            return MdClassFactory.eINSTANCE.createChartOfCalculationTypesPredefinedItem();
        }
        throw new IllegalArgumentException(
            "Owner does not support predefined items: " + owner.eClass().getName()); //$NON-NLS-1$
    }

    /**
     * The item's CONTAINED children - the only list that ever enters a tree walk. A
     * {@code ChartOfAccountsPredefinedItem} nests sub-accounts via {@code getChildItems()};
     * {@code ChartOfCalculationTypesPredefinedItem} is FLAT (empty). The {@code base}/{@code displaced}/
     * {@code leading} and {@code accountingFlags}/{@code extDimensionTypes} REFERENCE lists are NEVER
     * returned here - feeding a reference list to the recursive walk could loop on a cycle.
     */
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
        if (item instanceof ChartOfAccountsPredefinedItem)
        {
            return ((ChartOfAccountsPredefinedItem)item).getChildItems();
        }
        // ChartOfCalculationTypesPredefinedItem is FLAT - no containment children.
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
        if (predefined instanceof ChartOfAccountsPredefined)
        {
            return ((ChartOfAccountsPredefined)predefined).getItems();
        }
        if (predefined instanceof ChartOfCalculationTypesPredefined)
        {
            return ((ChartOfCalculationTypesPredefined)predefined).getItems();
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
        if (parent instanceof ChartOfAccountsPredefinedItem parentAccountItem
            && item instanceof ChartOfAccountsPredefinedItem)
        {
            parentAccountItem.getChildItems().add((ChartOfAccountsPredefinedItem)item);
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
            return;
        }
        if (predefined instanceof ChartOfAccountsPredefined accountsPredefined
            && item instanceof ChartOfAccountsPredefinedItem)
        {
            accountsPredefined.getItems().add((ChartOfAccountsPredefinedItem)item);
            return;
        }
        if (predefined instanceof ChartOfCalculationTypesPredefined calcPredefined
            && item instanceof ChartOfCalculationTypesPredefinedItem)
        {
            calcPredefined.getItems().add((ChartOfCalculationTypesPredefinedItem)item);
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
     * The item's display code: for a {@link CatalogPredefinedItem} /
     * {@link ChartOfCalculationTypesPredefinedItem} the wrapped {@link Value} (String/Number) is
     * unwrapped to plain text; for a {@link ChartOfCharacteristicTypesPredefinedItem} /
     * {@link ChartOfAccountsPredefinedItem} the plain {@code String} code is returned as-is.
     *
     * @param item the predefined item
     * @return the display code, or {@code null} when unset / unrecognized
     */
    public static String displayCode(PredefinedItem item)
    {
        if (item instanceof CatalogPredefinedItem)
        {
            return displayValueCode(((CatalogPredefinedItem)item).getCode());
        }
        if (item instanceof ChartOfCalculationTypesPredefinedItem)
        {
            return displayValueCode(((ChartOfCalculationTypesPredefinedItem)item).getCode());
        }
        if (item instanceof ChartOfCharacteristicTypesPredefinedItem)
        {
            return ((ChartOfCharacteristicTypesPredefinedItem)item).getCode();
        }
        if (item instanceof ChartOfAccountsPredefinedItem)
        {
            return ((ChartOfAccountsPredefinedItem)item).getCode();
        }
        return null;
    }

    /** Unwraps an {@code mcore.Value} code (String/Number) to plain display text, or {@code null}. */
    private static String displayValueCode(Value v)
    {
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
            // Pre-existing configs may hold values with an absurd exponent (either sign) our own write
            // path would reject - render those in scientific notation instead of expanding them into a
            // potentially enormous plain string. Two explicit compares, not Math.abs
            // (abs(Integer.MIN_VALUE) overflows negative and would skip the guard).
            int scale = bd.scale();
            return scale < -MAX_NUMERIC_CODE_DIGITS || scale > MAX_NUMERIC_CODE_DIGITS
                ? bd.toString() : bd.toPlainString();
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
     * a {@link Catalog} / {@link ChartOfCalculationTypes} needs an {@code mcore.Value} (a
     * {@link StringValue} or {@link NumberValue}, chosen by the owner's {@code getCodeType()}); a
     * {@link ChartOfCharacteristicTypes} / {@link ChartOfAccounts} takes a plain {@code String}. A JSON
     * {@code null} value CLEARS the code (used by modify to unset a wrongly-set code;
     * {@code parseProperties} maps a MISSING value on MODIFY to this same clear, since the MCP wire
     * strips a null-valued key - on CREATE a missing value is rejected there instead, nothing to clear).
     * Validates the JSON value's STRICT type (a string code must be a JSON string, a number code a JSON
     * number - #291 lesson) and the owner's {@code codeLength} (0 = unlimited, matching the rest of this
     * plugin's convention), rejecting an over-long code.
     *
     * @return {@code null} on success, or a ready, actionable error message
     */
    private static String applyCode(EObject owner, PredefinedItem item, JsonElement code)
    {
        if (owner instanceof Catalog catalog)
        {
            return applyValueCode(catalog.getCodeType() == CatalogCodeType.NUMBER, catalog.getCodeLength(),
                "Catalog", catalog.getName(), code, ((CatalogPredefinedItem)item)::setCode); //$NON-NLS-1$
        }
        if (owner instanceof ChartOfCharacteristicTypes)
        {
            return applyCharacteristicTypesCode((ChartOfCharacteristicTypes)owner,
                (ChartOfCharacteristicTypesPredefinedItem)item, code);
        }
        if (owner instanceof ChartOfCalculationTypes calc)
        {
            return applyValueCode(calc.getCodeType() == ChartOfCalculationTypesCodeType.NUMBER,
                calc.getCodeLength(), "ChartOfCalculationTypes", calc.getName(), code, //$NON-NLS-1$
                ((ChartOfCalculationTypesPredefinedItem)item)::setCode);
        }
        if (owner instanceof ChartOfAccounts accounts)
        {
            return applyAccountsCode(accounts, (ChartOfAccountsPredefinedItem)item, code);
        }
        return "Owner does not support a predefined-item code."; //$NON-NLS-1$
    }

    /**
     * The generalized {@code mcore.Value} code path shared by {@link Catalog} and
     * {@link ChartOfCalculationTypes} (both store a {@link Value} chosen by their {@code codeType}). The
     * BigDecimal precision/scale hardening is reused verbatim; {@code ownerKind} keeps the messages
     * owner-accurate (byte-identical to the original Catalog wording when {@code ownerKind} is
     * {@code "Catalog"}). A JSON {@code null} clears the code via {@code setter.accept(null)}.
     *
     * @param numeric whether the owner's code type is Number (else String)
     * @param codeLength the owner's code length (0 = unlimited)
     * @param ownerKind the owner's English type name (e.g. {@code Catalog}), for messages
     * @param ownerName the owner's Name, for messages
     * @param code the raw JSON code value (may be {@code null} / JSON null to clear)
     * @param setter sets the built {@link Value} (or {@code null}) on the concrete item
     * @return {@code null} on success, or a ready, actionable error message
     */
    private static String applyValueCode(boolean numeric, int codeLength, String ownerKind, String ownerName,
        JsonElement code, Consumer<Value> setter)
    {
        if (code == null || code.isJsonNull())
        {
            setter.accept(null);
            return null;
        }
        if (numeric)
        {
            if (!(code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber()))
            {
                return "'code' must be a JSON number for " + ownerKind + " '" + ownerName //$NON-NLS-1$ //$NON-NLS-2$
                    + "' (codeType=Number); got '" + code + "'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            BigDecimal bd = code.getAsBigDecimal();
            if (bd.signum() < 0)
            {
                return "'code' " + bd + " is negative; a numeric " + ownerKind + " code must be a " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "non-negative integer."; //$NON-NLS-1$
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
                    return "'code' " + bd + " has a fractional part; a numeric " + ownerKind + " code " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + "must be a non-negative integer."; //$NON-NLS-1$
                }
                digitCount = (long)stripped.precision() - stripped.scale();
            }
            long limit = codeLength > 0 ? codeLength : MAX_NUMERIC_CODE_DIGITS;
            if (digitCount > limit)
            {
                return codeLength > 0
                    ? "'code' " + bd + " has " + digitCount + " digit(s), exceeding " + ownerKind + " '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        + ownerName + "'s codeLength (" + codeLength + ")." //$NON-NLS-1$ //$NON-NLS-2$
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
            setter.accept(nv);
            return null;
        }
        if (!(code.isJsonPrimitive() && code.getAsJsonPrimitive().isString()))
        {
            return "'code' must be a JSON string for " + ownerKind + " '" + ownerName //$NON-NLS-1$ //$NON-NLS-2$
                + "' (codeType=String); got '" + code + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String s = code.getAsString();
        if (codeLength > 0 && s.length() > codeLength)
        {
            return "'code' \"" + s + "\" (" + s.length() + " char(s)) exceeds " + ownerKind + " '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + ownerName + "'s codeLength (" + codeLength + ")."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        StringValue sv = McoreFactory.eINSTANCE.createStringValue();
        sv.setValue(s);
        setter.accept(sv);
        return null;
    }

    /**
     * A {@link ChartOfAccounts} predefined item takes a plain {@code String} code, validated against the
     * chart's {@code codeLength} (0 = unlimited). A JSON {@code null} clears it. Never truncated.
     */
    private static String applyAccountsCode(ChartOfAccounts chart, ChartOfAccountsPredefinedItem item,
        JsonElement code)
    {
        if (code == null || code.isJsonNull())
        {
            item.setCode(null);
            return null;
        }
        if (!(code.isJsonPrimitive() && code.getAsJsonPrimitive().isString()))
        {
            return "'code' must be a JSON string for ChartOfAccounts '" + chart.getName() //$NON-NLS-1$
                + "'; got '" + code + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String s = code.getAsString();
        int codeLength = chart.getCodeLength();
        if (codeLength > 0 && s.length() > codeLength)
        {
            return "'code' \"" + s + "\" (" + s.length() + " char(s)) exceeds ChartOfAccounts '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + chart.getName() + "'s codeLength (" + codeLength + ")."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        item.setCode(s);
        return null;
    }

    /**
     * A {@link ChartOfAccounts} predefined item's {@code order} is a plain {@code String}, validated
     * against the chart's {@code orderLength} (0 = unlimited). A JSON {@code null} clears it. Never
     * truncated.
     */
    private static String applyOrder(ChartOfAccounts chart, ChartOfAccountsPredefinedItem item,
        JsonElement order)
    {
        if (order == null || order.isJsonNull())
        {
            item.setOrder(null);
            return null;
        }
        if (!(order.isJsonPrimitive() && order.getAsJsonPrimitive().isString()))
        {
            return "'order' must be a JSON string for ChartOfAccounts '" + chart.getName() //$NON-NLS-1$
                + "'; got '" + order + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String s = order.getAsString();
        int orderLength = chart.getOrderLength();
        if (orderLength > 0 && s.length() > orderLength)
        {
            return "'order' \"" + s + "\" (" + s.length() + " char(s)) exceeds ChartOfAccounts '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + chart.getName() + "'s orderLength (" + orderLength + ")."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        item.setOrder(s);
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

    // ============================================================================================
    // owner-specific properties (ChartOfAccounts / ChartOfCalculationTypes) - gate + apply
    // ============================================================================================

    /**
     * Owner GATE for every owner-specific property, run BEFORE any mutation (fail fast) - the same
     * discipline as {@link #applyValueType}: a property is parsed unconditionally by
     * {@link #parseProperties}, then rejected here with an actionable, owner-scoped error when it does
     * not apply to this owner (never silently dropped). {@code accountType}/{@code offBalance}/
     * {@code order}/{@code accountingFlags}/{@code extDimensionTypes} require a {@link ChartOfAccounts};
     * {@code base}/{@code displaced}/{@code leading}/{@code actionPeriodIsBase} require a
     * {@link ChartOfCalculationTypes}; {@code isFolder} is rejected for both new owners (no folder
     * concept) and {@code parent} for a {@link ChartOfCalculationTypes} (a FLAT model).
     *
     * @return {@code null} when every supplied property is legal for {@code owner}, else a ready error
     */
    private static String rejectCrossOwnerProps(EObject owner, ItemProps props)
    {
        boolean coa = owner instanceof ChartOfAccounts;
        boolean coct = owner instanceof ChartOfCalculationTypes;
        if (!coa)
        {
            String err = firstCrossOwnerReject(owner, "ChartOfAccounts", //$NON-NLS-1$
                new String[] {"accountType", "offBalance", "order", "accountingFlags", "extDimensionTypes"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                new boolean[] {props.accountTypeSet, props.offBalanceSet, props.orderSet,
                    props.accountingFlagsSet, props.extDimensionTypesSet});
            if (err != null)
            {
                return err;
            }
        }
        if (!coct)
        {
            String err = firstCrossOwnerReject(owner, "ChartOfCalculationTypes", //$NON-NLS-1$
                new String[] {"base", "displaced", "leading", "actionPeriodIsBase"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                new boolean[] {props.baseSet, props.displacedSet, props.leadingSet,
                    props.actionPeriodIsBaseSet});
            if (err != null)
            {
                return err;
            }
        }
        if (props.isFolderSet && (coa || coct))
        {
            return "Property 'isFolder' is not supported for a " + ownerLabel(owner) //$NON-NLS-1$
                + " predefined item: this owner has no folder concept." //$NON-NLS-1$
                + (coa ? " Nest an account under an existing parent account with 'parent' instead." : ""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (coct && props.parentName != null && !props.parentName.trim().isEmpty())
        {
            return "Property 'parent' is not supported for a " + ownerLabel(owner) //$NON-NLS-1$
                + " predefined item: a Chart of Calculation Types is FLAT (no folders, no nesting)."; //$NON-NLS-1$
        }
        return null;
    }

    /** Returns a cross-owner reject for the first set property in {@code names}, or {@code null}. */
    private static String firstCrossOwnerReject(EObject owner, String requiredOwner, String[] names,
        boolean[] set)
    {
        for (int i = 0; i < names.length; i++)
        {
            if (set[i])
            {
                return "Property '" + names[i] + "' applies only to a " + requiredOwner //$NON-NLS-1$ //$NON-NLS-2$
                    + " predefined item; " + ownerLabel(owner) + " does not support it."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    /**
     * Applies the owner-specific properties once the owner gate ({@link #rejectCrossOwnerProps}) has
     * passed: the {@link ChartOfAccounts} scalar + reference props, or the {@link ChartOfCalculationTypes}
     * flag + sibling-reference lists. Every reference resolves to a LIVE in-resource {@link EObject}
     * inside the caller's write transaction (never a proxy / FQN string); a name that resolves to nothing
     * is a hard, actionable error (the caller then rolls the whole write back).
     *
     * @return {@code null} on success, or a ready, actionable error message
     */
    private static String applyOwnerSpecificProps(EObject owner, PredefinedItem item, ItemProps props)
    {
        if (owner instanceof ChartOfAccounts chart && item instanceof ChartOfAccountsPredefinedItem account)
        {
            return applyChartOfAccountsProps(chart, account, props);
        }
        if (owner instanceof ChartOfCalculationTypes chart
            && item instanceof ChartOfCalculationTypesPredefinedItem calc)
        {
            return applyChartOfCalculationTypesProps(chart, calc, props);
        }
        return null;
    }

    private static String applyChartOfAccountsProps(ChartOfAccounts chart, ChartOfAccountsPredefinedItem item,
        ItemProps props)
    {
        if (props.accountTypeSet)
        {
            AccountType type = resolveAccountType(props.accountType);
            if (type == null)
            {
                return "'accountType' '" + props.accountType + "' is not recognized. Use one of: ACTIVE, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "PASSIVE, ACTIVE_PASSIVE (or their Russian equivalents)."; //$NON-NLS-1$
            }
            item.setAccountType(type);
        }
        if (props.offBalanceSet && props.offBalance != null)
        {
            item.setOffBalance(props.offBalance);
        }
        if (props.orderSet)
        {
            String orderErr = applyOrder(chart, item, props.order);
            if (orderErr != null)
            {
                return orderErr;
            }
        }
        if (props.accountingFlagsSet)
        {
            String flagsErr = replaceAccountingFlags(chart, item, props.accountingFlags);
            if (flagsErr != null)
            {
                return flagsErr;
            }
        }
        if (props.extDimensionTypesSet)
        {
            String extErr = replaceExtDimensionTypes(chart, item, props.extDimensionTypes);
            if (extErr != null)
            {
                return extErr;
            }
        }
        return null;
    }

    private static String applyChartOfCalculationTypesProps(ChartOfCalculationTypes chart,
        ChartOfCalculationTypesPredefinedItem item, ItemProps props)
    {
        if (props.actionPeriodIsBaseSet && props.actionPeriodIsBase != null)
        {
            item.setActionPeriodIsBase(props.actionPeriodIsBase);
        }
        if (props.baseSet)
        {
            String err = replaceCalcTypeRefs(chart, item.getBase(), props.base, "base"); //$NON-NLS-1$
            if (err != null)
            {
                return err;
            }
        }
        if (props.displacedSet)
        {
            String err = replaceCalcTypeRefs(chart, item.getDisplaced(), props.displaced, "displaced"); //$NON-NLS-1$
            if (err != null)
            {
                return err;
            }
        }
        if (props.leadingSet)
        {
            String err = replaceCalcTypeRefs(chart, item.getLeading(), props.leading, "leading"); //$NON-NLS-1$
            if (err != null)
            {
                return err;
            }
        }
        return null;
    }

    /**
     * FULL-REPLACE the {@code base}/{@code displaced}/{@code leading} reference list on a calc-type item:
     * every supplied name is resolved to a SIBLING predefined calc type in the SAME chart (a live
     * in-resource {@link EObject}) via the shared {@link #findByName} primitive; a name that resolves to
     * nothing (or to a non-calc-type item) is a hard error and NOTHING is left half-written for the
     * caller to roll back. The list is CLEARED first, so an empty input clears it.
     */
    private static String replaceCalcTypeRefs(ChartOfCalculationTypes chart,
        EList<ChartOfCalculationTypesPredefinedItem> target, List<String> names, String propName)
    {
        List<ChartOfCalculationTypesPredefinedItem> resolved = new ArrayList<>();
        if (names != null)
        {
            for (String name : names)
            {
                PredefinedItem sibling = findByName(chart, name);
                if (!(sibling instanceof ChartOfCalculationTypesPredefinedItem calcSibling))
                {
                    return "'" + propName + "' references a predefined calculation type '" + name //$NON-NLS-1$ //$NON-NLS-2$
                        + "' that does not exist on " + ownerLabel(chart) //$NON-NLS-1$
                        + ". Create it first, or fix the name."; //$NON-NLS-1$
                }
                resolved.add(calcSibling);
            }
        }
        target.clear();
        target.addAll(resolved);
        return null;
    }

    /**
     * FULL-REPLACE the {@code accountingFlags} reference list: every supplied name is resolved to one of
     * the chart's OWN {@link AccountingFlag} objects by Name (a live in-resource {@link EObject}); a name
     * that matches no accounting flag is a hard error (never create a flag). Cleared first.
     */
    private static String replaceAccountingFlags(ChartOfAccounts chart, ChartOfAccountsPredefinedItem item,
        List<String> names)
    {
        List<AccountingFlag> resolved = new ArrayList<>();
        if (names != null)
        {
            for (String name : names)
            {
                AccountingFlag flag = findByName(chart.getAccountingFlags(), name);
                if (flag == null)
                {
                    return "'accountingFlags' references an accounting flag '" + name //$NON-NLS-1$
                        + "' that does not exist on " + ownerLabel(chart) //$NON-NLS-1$
                        + ". Add it to the chart's AccountingFlags first, or fix the name."; //$NON-NLS-1$
                }
                resolved.add(flag);
            }
        }
        item.getAccountingFlags().clear();
        item.getAccountingFlags().addAll(resolved);
        return null;
    }

    /**
     * FULL-REPLACE the CONTAINED {@code extDimensionTypes} rows: each row is a fresh {@link ExtDimensionType}
     * whose {@code characteristicType} resolves to a predefined item of the chart's LINKED
     * {@link ChartOfCharacteristicTypes} ({@link ChartOfAccounts#getExtDimensionTypes()} on the CHART
     * returns that single linked CCT - a name collision with the item-level list this method fills) and
     * whose {@code extDimensionAccountingFlags} resolve to the chart's own
     * {@link ExtDimensionAccountingFlag}s. A missing target is a hard error. Cleared first.
     */
    private static String replaceExtDimensionTypes(ChartOfAccounts chart, ChartOfAccountsPredefinedItem item,
        List<ExtDimensionTypeSpec> specs)
    {
        List<ExtDimensionType> resolved = new ArrayList<>();
        if (specs != null)
        {
            ChartOfCharacteristicTypes linkedCct = chart.getExtDimensionTypes();
            for (ExtDimensionTypeSpec spec : specs)
            {
                String err = buildExtDimensionType(chart, linkedCct, spec, resolved);
                if (err != null)
                {
                    return err;
                }
            }
        }
        item.getExtDimensionTypes().clear();
        item.getExtDimensionTypes().addAll(resolved);
        return null;
    }

    /** Builds one {@link ExtDimensionType} from {@code spec} into {@code out}, or returns a ready error. */
    private static String buildExtDimensionType(ChartOfAccounts chart, ChartOfCharacteristicTypes linkedCct,
        ExtDimensionTypeSpec spec, List<ExtDimensionType> out)
    {
        if (linkedCct == null)
        {
            return "Cannot resolve 'characteristicType' '" + spec.characteristicType + "': " //$NON-NLS-1$ //$NON-NLS-2$
                + ownerLabel(chart) + " has no linked ChartOfCharacteristicTypes (set the chart's " //$NON-NLS-1$
                + "ExtDimensionType property first)."; //$NON-NLS-1$
        }
        PredefinedItem ct = findByName(linkedCct, spec.characteristicType);
        if (!(ct instanceof ChartOfCharacteristicTypesPredefinedItem characteristic))
        {
            return "'extDimensionTypes' references a characteristicType '" + spec.characteristicType //$NON-NLS-1$
                + "' that is not a predefined item of the linked " + ownerLabel(linkedCct) //$NON-NLS-1$
                + ". Create it first, or fix the name."; //$NON-NLS-1$
        }
        ExtDimensionType ext = MdClassFactory.eINSTANCE.createExtDimensionType();
        ext.setCharacteristicType(characteristic);
        ext.setTurnover(spec.turnover);
        for (String flagName : spec.extDimensionAccountingFlags)
        {
            ExtDimensionAccountingFlag flag = findByName(chart.getExtDimensionAccountingFlags(), flagName);
            if (flag == null)
            {
                return "'extDimensionAccountingFlags' references '" + flagName //$NON-NLS-1$
                    + "' that does not exist on " + ownerLabel(chart) //$NON-NLS-1$
                    + ". Add it to the chart's ExtDimensionAccountingFlags first, or fix the name."; //$NON-NLS-1$
            }
            ext.getExtDimensionAccountingFlags().add(flag);
        }
        out.add(ext);
        return null;
    }

    /**
     * Resolves a named {@link MdObject} in a live list by case-insensitive Name (1C identifiers),
     * EXACT-first then with a yo-normalized ('ё'->'е') retry - the SAME tolerance the predefined
     * SIBLING references already get via {@link #locateYo}. create_metadata normalizes a new
     * AccountingFlag / ExtDimensionAccountingFlag Name by default, so a caller who later cites the
     * flag with its natural 'ё' spelling must still resolve the stored (normalized) flag.
     */
    private static <T extends MdObject> T findByName(List<T> list, String name)
    {
        if (name == null || list == null)
        {
            return null;
        }
        T exact = findByNameExact(list, name);
        if (exact != null)
        {
            return exact;
        }
        String normalized = MdNameNormalizer.normalizeYo(name);
        return normalized.equals(name) ? null : findByNameExact(list, normalized);
    }

    private static <T extends MdObject> T findByNameExact(List<T> list, String name)
    {
        for (T candidate : list)
        {
            if (name.equalsIgnoreCase(candidate.getName()))
            {
                return candidate;
            }
        }
        return null;
    }

    /** Resolves an {@code accountType} token to the platform enum, EXACT (never contains()), or {@code null}. */
    private static AccountType resolveAccountType(String token)
    {
        if (token == null)
        {
            return null;
        }
        return ACCOUNT_TYPE_TOKENS.get(token.trim().toLowerCase(Locale.ROOT));
    }

    // ============================================================================================
    // read-side display helpers for the owner-specific columns (dash-when-N/A, like formatValueType)
    // ============================================================================================

    /** The ChartOfAccounts account type as its enum literal name, or {@code null} for other kinds. */
    public static String displayAccountType(PredefinedItem item)
    {
        if (item instanceof ChartOfAccountsPredefinedItem account)
        {
            AccountType type = account.getAccountType();
            return type != null ? type.getName() : null;
        }
        return null;
    }

    /** The ChartOfAccounts off-balance flag, or {@code null} for other kinds. */
    public static Boolean displayOffBalance(PredefinedItem item)
    {
        return (item instanceof ChartOfAccountsPredefinedItem account)
            ? Boolean.valueOf(account.isOffBalance()) : null;
    }

    /** The ChartOfAccounts order String, or {@code null} when unset / other kinds. */
    public static String displayOrder(PredefinedItem item)
    {
        return (item instanceof ChartOfAccountsPredefinedItem account) ? account.getOrder() : null;
    }

    /** The ChartOfCalculationTypes action-period-is-base flag, or {@code null} for other kinds. */
    public static Boolean displayActionPeriodIsBase(PredefinedItem item)
    {
        return (item instanceof ChartOfCalculationTypesPredefinedItem calc)
            ? Boolean.valueOf(calc.isActionPeriodIsBase()) : null;
    }

    /** The calc-type {@code base} sibling Names, comma-joined, or {@code null} when empty / other kinds. */
    public static String displayBase(PredefinedItem item)
    {
        return (item instanceof ChartOfCalculationTypesPredefinedItem calc)
            ? joinItemNames(calc.getBase()) : null;
    }

    /** The calc-type {@code displaced} sibling Names, comma-joined, or {@code null}. */
    public static String displayDisplaced(PredefinedItem item)
    {
        return (item instanceof ChartOfCalculationTypesPredefinedItem calc)
            ? joinItemNames(calc.getDisplaced()) : null;
    }

    /** The calc-type {@code leading} sibling Names, comma-joined, or {@code null}. */
    public static String displayLeading(PredefinedItem item)
    {
        return (item instanceof ChartOfCalculationTypesPredefinedItem calc)
            ? joinItemNames(calc.getLeading()) : null;
    }

    /** The ChartOfAccounts {@code accountingFlags} Names, comma-joined, or {@code null} when empty. */
    public static String displayAccountingFlags(PredefinedItem item)
    {
        if (item instanceof ChartOfAccountsPredefinedItem account)
        {
            List<String> names = new ArrayList<>();
            for (AccountingFlag flag : account.getAccountingFlags())
            {
                names.add(safeName(flag));
            }
            return names.isEmpty() ? null : String.join(", ", names); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The ChartOfAccounts {@code extDimensionTypes} rows rendered as text (e.g.
     * {@code "Contractors [turnover] {Sum}; Products"}), or {@code null} when empty / other kinds.
     */
    public static String displayExtDimensionTypes(PredefinedItem item)
    {
        if (!(item instanceof ChartOfAccountsPredefinedItem account))
        {
            return null;
        }
        List<String> rows = new ArrayList<>();
        for (ExtDimensionType ext : account.getExtDimensionTypes())
        {
            rows.add(renderExtDimensionType(ext));
        }
        return rows.isEmpty() ? null : String.join("; ", rows); //$NON-NLS-1$
    }

    /**
     * A predefined-item reference's Name for DISPLAY, hardened against a DANGLING reference: a
     * {@code null}, an unresolved EMF proxy, or a {@code null} Name (e.g. left behind when the
     * referenced item was removed with {@code force=true} and the project was reloaded) renders
     * {@code "?"} instead of throwing. Without this, {@code new StringBuilder(proxy.getName())} would
     * {@code NullPointerException} and fail the whole {@code get_metadata_details} render (issue #296 P3).
     */
    private static String safeName(PredefinedItem it)
    {
        if (it == null || it.eIsProxy())
        {
            return "?"; //$NON-NLS-1$
        }
        String name = it.getName();
        return name != null ? name : "?"; //$NON-NLS-1$
    }

    /**
     * The {@link MdObject} overload of {@link #safeName(PredefinedItem)}, for a chart's
     * {@code AccountingFlag} / {@code ExtDimensionAccountingFlag} reference: a force-deleted flag
     * still cited by a predefined account leaves a dangling proxy / null Name, which would make
     * {@code String.join} render "null" (or abort on a hard proxy). Renders {@code "?"} instead.
     */
    private static String safeName(MdObject obj)
    {
        if (obj == null || obj.eIsProxy())
        {
            return "?"; //$NON-NLS-1$
        }
        String name = obj.getName();
        return name != null ? name : "?"; //$NON-NLS-1$
    }

    /** Renders one {@link ExtDimensionType} row as {@code "<charType> [turnover] {flag, ...}"}. */
    private static String renderExtDimensionType(ExtDimensionType ext)
    {
        StringBuilder sb = new StringBuilder(safeName(ext.getCharacteristicType()));
        if (ext.isTurnover())
        {
            sb.append(" [turnover]"); //$NON-NLS-1$
        }
        List<String> flags = new ArrayList<>();
        for (ExtDimensionAccountingFlag flag : ext.getExtDimensionAccountingFlags())
        {
            flags.add(safeName(flag));
        }
        if (!flags.isEmpty())
        {
            sb.append(" {").append(String.join(", ", flags)).append("}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return sb.toString();
    }

    /** Comma-joins the Names of a predefined-item reference list, or {@code null} when empty. */
    private static String joinItemNames(List<? extends PredefinedItem> items)
    {
        if (items == null || items.isEmpty())
        {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (PredefinedItem it : items)
        {
            names.add(safeName(it));
        }
        return String.join(", ", names); //$NON-NLS-1$
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
        if (owner instanceof ChartOfAccounts)
        {
            return ((ChartOfAccounts)owner).getDescriptionLength();
        }
        if (owner instanceof ChartOfCalculationTypes)
        {
            return ((ChartOfCalculationTypes)owner).getDescriptionLength();
        }
        return 0;
    }

    /**
     * Validates {@code description}'s length against {@code owner}'s {@code descriptionLength} - the
     * description-side counterpart of {@link #applyValueCode}/{@link #applyCharacteristicTypesCode}'s
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
