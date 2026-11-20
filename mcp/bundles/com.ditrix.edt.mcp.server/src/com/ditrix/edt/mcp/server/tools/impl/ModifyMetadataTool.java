/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static com.ditrix.edt.mcp.server.utils.MetadataPropertyApplier.*;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyApplier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.QName;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;
import com._1c.g5.v8.dt.metadata.mdclass.StyleElementType;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.xdto.model.ObjectType;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.textdocument.model.FileAwareTextDocument;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.AdjustableBooleanSupport;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouter;
import com.ditrix.edt.mcp.server.utils.AppearanceValueBuilder;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.QueryWriteValidation;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceAddress;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSupport;
import com.ditrix.edt.mcp.server.utils.CommonAttributeContentWriter;
import com.ditrix.edt.mcp.server.utils.ConfigurationRoot;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.ExchangePlanContentWriter;
import com.ditrix.edt.mcp.server.utils.ExtensionOriginUtils;
import com.ditrix.edt.mcp.server.utils.ExcludedCommandsSupport;
import com.ditrix.edt.mcp.server.utils.FieldListSupport;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.FormValueTypePolicy;
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.McoreValueListBuilder;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector;
import com.ditrix.edt.mcp.server.utils.MetadataMemberValues;
import com.ditrix.edt.mcp.server.utils.FormChoiceParameters;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.PropertyInfo;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeBuilder;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ReferencedTopObjects;
import com.ditrix.edt.mcp.server.utils.StandardCommandGroupResolver;
import com.ditrix.edt.mcp.server.utils.TableFieldReferences;
import com.ditrix.edt.mcp.server.utils.MethodReferenceValidator;
import com.ditrix.edt.mcp.server.utils.PictureValueBuilder;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;
import com.ditrix.edt.mcp.server.utils.ReferenceMembershipWriter;
import com.ditrix.edt.mcp.server.utils.RoleRightsWriter;
import com.ditrix.edt.mcp.server.utils.SpreadsheetTemplateWriter;
import com.ditrix.edt.mcp.server.utils.StyleValueBuilder;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;
import com.ditrix.edt.mcp.server.utils.TemplateContentSupport;
import com.ditrix.edt.mcp.server.utils.TypeChangeDefaults;
import com.ditrix.edt.mcp.server.utils.UseAlwaysSupport;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.ditrix.edt.mcp.server.utils.XdtoWriteException;
import com.ditrix.edt.mcp.server.utils.XdtoWriter;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Sets one or more properties of a metadata node (a top-level object or a member) addressed by a 1C
 * full-name FQN. Every property is VALIDATED before any write: an unknown / non-assignable property
 * is rejected with the list of assignable properties, and an out-of-range value (e.g. an enum value
 * that is not a valid literal) is rejected with the allowed values - so the error is actionable.
 * Replaces the former {@code set_metadata_property} (which set only Comment / Synonym).
 *
 * <p>Renaming is out of scope: setting the {@code name} property is refused with a pointer to
 * {@code rename_metadata_object}, because a Name change must cascade across all references.</p>
 */
public class ModifyMetadataTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "modify_metadata"; //$NON-NLS-1$

    private static final String COMMON_FORM_MDCLASS_DISCOVERY_HINT =
        "The common form's own metadata properties are listed by get_metadata_details with " //$NON-NLS-1$
            + "assignable:true on the same FQN."; //$NON-NLS-1$

    /** The same guidance for an owned form, whose metadata object sits in its owner (issue #995). */
    static final String OWNED_FORM_MDCLASS_DISCOVERY_HINT =
        "The form's own metadata properties (synonym, comment, usePurposes, ...) are listed by " //$NON-NLS-1$
            + "get_metadata_details with assignable:true on the same FQN."; //$NON-NLS-1$

    /**
     * Asks the destructive-consent gate. A package-private SEAM: the production default delegates to
     * {@link DestructiveConsentGate#getInstance()}, which stays a private static final singleton, while
     * a unit test substitutes a requester answering REJECT / TIMEOUT (or recording that it was never
     * asked at all) to prove the branch's dispatch. Mirrors the seam {@code DeleteMetadataTool} uses
     * (issue #295 review).
     */
    @FunctionalInterface
    interface ConsentRequester
    {
        /**
         * @param toolName the gated tool's name
         * @param preview what the user is being asked to authorize
         * @return the verdict
         */
        ConsentDecision request(String toolName, ConsentPreview preview);
    }

    private final ConsentRequester consentRequester;

    /** Production instance: consent goes to the real gate. */
    public ModifyMetadataTool()
    {
        this((tool, preview) -> DestructiveConsentGate.getInstance().requireConsent(tool, preview));
    }

    /**
     * Test seam constructor.
     *
     * @param consentRequester the consent source to use instead of the singleton gate
     */
    ModifyMetadataTool(ConsentRequester consentRequester)
    {
        this.consentRequester = consentRequester;
    }

    /**
     * THE single point where a destructive FORM RETYPE is authorized, and the only place that decides
     * the ORDER of the three steps: the deterministic pre-check runs FIRST, the gate is asked only when
     * the write could actually be applied, and the write runs only on ALLOW. A retype the tool is going
     * to refuse anyway (columns that would be stranded, a main table that does not resolve) must never
     * raise a destructive dialog, because a denial or a timeout would come back INSTEAD of the
     * actionable validation error (issue #295 review).
     *
     * @param preview what the user is being asked to authorize, built AFTER the pre-check ran: the
     *            request alone cannot say which of the two losses is actually at stake, and a
     *            dialog that names a loss that will not happen teaches the reader to ignore it
     * @param preflight the deterministic pre-check, run BEFORE any prompt: a ready JSON error refuses
     *            the write with no prompt at all, {@code ""} means nothing destructive happens (write
     *            without asking), {@code null} means a real retype - ask
     * @param write the mutation, invoked only when the pre-check passed and consent was granted
     * @return the mutation's result, the pre-check's refusal, or the consent refusal
     */
    String gateFormRetype(java.util.function.Supplier<ConsentPreview> preview,
        java.util.function.Supplier<String> preflight, java.util.function.Supplier<String> write)
    {
        String verdict = preflight.get();
        if (verdict != null && !verdict.isEmpty())
        {
            // A deterministic refusal: the write can never be applied, so nothing is asked.
            return verdict;
        }
        if (verdict == null)
        {
            ConsentDecision decision = consentRequester.request(NAME, preview.get());
            if (decision != ConsentDecision.ALLOW)
            {
                return ToolResult.error(
                    DestructiveConsentGate.consentDeniedMessage(decision, NAME)).toJson();
            }
        }
        return write.get();
    }

    /** Output result key: names of the properties that were set. */
    private static final String KEY_APPLIED = "applied"; //$NON-NLS-1$

    /** Output result key: whether the change was exported to disk. */
    private static final String KEY_PERSISTED = "persisted"; //$NON-NLS-1$

    /**
     * Output result key: the event handlers a form item's KIND change took with it, each named
     * {@code "Event (Procedure)"} (issue #601) - the ones the new kind publishes no event for, plus
     * any that went with an ext-info node the new type pairs with nothing to replace. Reported only
     * when non-empty, like {@code demotedMainAttributes}: a change to something the caller did not
     * address. The BSL procedure is never touched, only the subscription.
     */
    static final String KEY_REMOVED_EVENT_HANDLERS = "removedEventHandlers"; //$NON-NLS-1$

    /** Echoes the locale a localized property was actually written under (#298). */
    private static final String KEY_LANGUAGE = "language"; //$NON-NLS-1$

    /** Locales IN USE that still have no value for a localized property just written (#298). */
    private static final String KEY_LOCALES_MISSING = "localesMissing"; //$NON-NLS-1$

    /** Set when a write targets a declared language the configuration's own synonym does not use. */
    private static final String KEY_LOCALE_UNUSED = "localeUnusedInConfiguration"; //$NON-NLS-1$

    /** Locales whose EXISTING text this call did not touch - they now describe the old value. */
    private static final String KEY_LOCALES_STALE = "localesStale"; //$NON-NLS-1$

    /** Output value for {@link McpKeys#ACTION}: the node was modified. */
    private static final String VAL_MODIFIED = "modified"; //$NON-NLS-1$

 //$NON-NLS-1$
 //$NON-NLS-1$
 //$NON-NLS-1$

    /** Error message prefix for an unresolved form FQN. */
    private static final String ERR_FORM_NOT_FOUND_PREFIX = "Form not found for '"; //$NON-NLS-1$

    /** Payload / output key: the ROLE rights array. */
    private static final String KEY_RIGHTS = "rights"; //$NON-NLS-1$

    /** Payload / output key: the ROLE RLS templates array. */
    private static final String KEY_TEMPLATES = "templates"; //$NON-NLS-1$

    /** Payload / output key: the ROLE properties object. */
    private static final String KEY_ROLE_PROPERTIES = "roleProperties"; //$NON-NLS-1$

    /** Payload / output key: the membership content array / counts object. */
    private static final String KEY_CONTENT = "content"; //$NON-NLS-1$

    /** Payload / output key: the SpreadsheetDocument template content spec / applied-counts object. */
    private static final String KEY_TEMPLATE = "template"; //$NON-NLS-1$

    /** Payload / output key: the command-interface changes array / applied-counts object. */
    static final String KEY_COMMANDS = "commands"; //$NON-NLS-1$

    /** Input schema of {@link #KEY_RIGHTS}: the members {@code RoleRightsWriter} reads. */
    private static final String RIGHTS_SCHEMA = "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"object\":{\"type\":\"string\"},\"right\":{\"type\":\"string\"}," //$NON-NLS-1$
        + "\"value\":{\"type\":[\"boolean\",\"string\"],\"enum\":[true,false,\"set\",\"unset\",\"provided\"]}," //$NON-NLS-1$
        + "\"rls\":{\"type\":\"string\"},\"rlsFields\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}," //$NON-NLS-1$
        + "\"required\":[\"object\",\"right\"],\"additionalProperties\":false}}"; //$NON-NLS-1$

    /** Input schema of {@link #KEY_TEMPLATES}; a blank {@code op} means add, so the enum has no "". */
    private static final String TEMPLATES_SCHEMA = "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"op\":{\"type\":\"string\",\"enum\":[\"add\",\"edit\",\"delete\"]},\"name\":{\"type\":\"string\"}," //$NON-NLS-1$
        + "\"condition\":{\"type\":\"string\"}},\"required\":[\"name\"],\"additionalProperties\":false}}"; //$NON-NLS-1$

    /** Input schema of {@link #KEY_ROLE_PROPERTIES}. */
    private static final String ROLE_PROPERTIES_SCHEMA = "{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"setForNewObjects\":{\"type\":\"boolean\"},\"setForAttributesByDefault\":{\"type\":\"boolean\"}," //$NON-NLS-1$
        + "\"independentRightsOfChildObjects\":{\"type\":\"boolean\"}},\"additionalProperties\":false}"; //$NON-NLS-1$

    /** Input schema of {@link #KEY_CONTENT}: the members the three membership writers read. */
    private static final String CONTENT_SCHEMA = "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"op\":{\"type\":\"string\",\"enum\":[\"add\",\"remove\"]},\"metadata\":{\"type\":\"string\"}," //$NON-NLS-1$
        + "\"use\":{\"type\":\"string\",\"enum\":[\"Use\",\"DontUse\",\"Auto\"]}," //$NON-NLS-1$
        + "\"autoRecord\":{\"type\":\"string\",\"enum\":[\"Allow\",\"Deny\"]}}," //$NON-NLS-1$
        + "\"required\":[\"metadata\"],\"additionalProperties\":false}}"; //$NON-NLS-1$

    /** Input schema of {@link #KEY_COMMANDS}: the entry keys {@code CommandInterfaceSection} accepts. */
    private static final String COMMANDS_SCHEMA = "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"command\":{\"type\":\"string\"},\"visible\":{\"type\":\"boolean\"}," //$NON-NLS-1$
        + "\"roles\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"role\":{\"type\":\"string\"}," //$NON-NLS-1$
        + "\"visible\":{\"type\":[\"boolean\",\"string\",\"null\"],\"enum\":[true,false,\"default\"]}}," //$NON-NLS-1$
        + "\"required\":[\"role\",\"visible\"],\"additionalProperties\":false}}," //$NON-NLS-1$
        + "\"group\":{\"type\":\"string\"},\"after\":{\"type\":\"string\"},\"before\":{\"type\":\"string\"}}," //$NON-NLS-1$
        + "\"required\":[\"command\"],\"additionalProperties\":false}}"; //$NON-NLS-1$

    /** A spreadsheet row / column index: a non-negative integer. */
    private static final String INDEX_SCHEMA = "{\"type\":\"integer\",\"minimum\":0}"; //$NON-NLS-1$

    /**
     * Input schema of {@link #KEY_TEMPLATE}: the TextDocument and SpreadsheetDocument members. Cell, column
     * and row items stay open so {@code SpreadsheetTemplateWriter} names a misplaced key itself; bold,
     * autoMarkIncomplete and autoWidthCalculation are untyped because the writer also reads "yes" / "1".
     */
    private static final String TEMPLATE_SCHEMA = "{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"text\":{\"type\":\"string\"},\"mode\":{\"type\":\"string\"},\"oldText\":{\"type\":\"string\"}," //$NON-NLS-1$
        + "\"expectedHash\":{\"type\":\"string\"},\"expectedSource\":{\"type\":\"string\"}," //$NON-NLS-1$
        + "\"overwrite\":{\"type\":\"boolean\"}," //$NON-NLS-1$
        + "\"cells\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"row\":" + INDEX_SCHEMA + ",\"col\":" + INDEX_SCHEMA + ",\"replace\":{\"type\":\"boolean\"}," //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        + "\"text\":{\"type\":\"string\"},\"parameter\":{\"type\":\"string\"},\"bold\":{}," //$NON-NLS-1$
        + "\"fontSize\":{\"type\":\"integer\",\"minimum\":1}," //$NON-NLS-1$
        + "\"hAlign\":{\"type\":\"string\",\"enum\":[\"Auto\",\"Left\",\"Right\",\"Center\",\"Width\"]}," //$NON-NLS-1$
        + "\"vAlign\":{\"type\":\"string\",\"enum\":[\"Center\",\"Top\",\"Bottom\"]}," //$NON-NLS-1$
        + "\"wrap\":{\"type\":[\"boolean\",\"string\"],\"enum\":[true,false,\"Auto\",\"Cut\",\"Block\",\"Wrap\"]}," //$NON-NLS-1$
        + "\"textOrientation\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3600}," //$NON-NLS-1$
        + "\"autoIndent\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100},\"autoMarkIncomplete\":{}}," //$NON-NLS-1$
        + "\"required\":[\"row\",\"col\"]}}," //$NON-NLS-1$
        + "\"merges\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"fromRow\":" + INDEX_SCHEMA + ",\"fromCol\":" + INDEX_SCHEMA //$NON-NLS-1$ //$NON-NLS-2$
        + ",\"toRow\":" + INDEX_SCHEMA + ",\"toCol\":" + INDEX_SCHEMA //$NON-NLS-1$ //$NON-NLS-2$
        + "},\"required\":[\"fromRow\",\"fromCol\",\"toRow\",\"toCol\"],\"additionalProperties\":false}}," //$NON-NLS-1$
        + "\"areas\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"name\":{\"type\":\"string\"},\"fromRow\":" + INDEX_SCHEMA + ",\"fromCol\":" + INDEX_SCHEMA //$NON-NLS-1$ //$NON-NLS-2$
        + ",\"toRow\":" + INDEX_SCHEMA + ",\"toCol\":" + INDEX_SCHEMA //$NON-NLS-1$ //$NON-NLS-2$
        + "},\"required\":[\"name\",\"fromRow\",\"fromCol\",\"toRow\",\"toCol\"],\"additionalProperties\":false}}," //$NON-NLS-1$
        + "\"columnWidths\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"col\":" + INDEX_SCHEMA + ",\"width\":{\"type\":\"integer\",\"minimum\":1},\"autoWidthCalculation\":{}," //$NON-NLS-1$ //$NON-NLS-2$
        + "\"widthWeightFactor\":" + INDEX_SCHEMA + "},\"required\":[\"col\"]}}," //$NON-NLS-1$ //$NON-NLS-2$
        + "\"rowHeights\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"row\":" + INDEX_SCHEMA + ",\"height\":{\"type\":\"integer\",\"minimum\":1}}," //$NON-NLS-1$ //$NON-NLS-2$
        + "\"required\":[\"row\",\"height\"]}}},\"additionalProperties\":false}"; //$NON-NLS-1$

    /** Output key: what a command interface stores after a {@link #KEY_COMMANDS} change. */
    private static final String KEY_STORED = "stored"; //$NON-NLS-1$

    /** Output key: the commands of {@link #KEY_COMMANDS} entries that changed nothing. */
    private static final String KEY_UNCHANGED = "unchanged"; //$NON-NLS-1$

    /** How long a command-interface edit waits for EDT to finish recomputing the section. */
    private static final long COMMAND_INTERFACE_WAIT_MS = 15_000L;

    /** Actual-kind stem in the "payload only for X FQN" refusals (java:S1192). */
    private static final String ERR_IS_A = "is a "; //$NON-NLS-1$

    /** Shared BM bootstrap failure messages (java:S1192). */
    private static final String ERR_NO_BM_MANAGER = "IBmModelManager not available"; //$NON-NLS-1$
    private static final String ERR_NO_BM_MODEL = "BM model not available for project: "; //$NON-NLS-1$

    /** Output count key: members attached. */
    private static final String KEY_ADDED = "added"; //$NON-NLS-1$

    /** Output count key: members detached. */
    private static final String KEY_REMOVED = "removed"; //$NON-NLS-1$

    /** The form attribute's value-type feature / property alias. */
    private static final String PROP_VALUE_TYPE = "valueType"; //$NON-NLS-1$
    /** The form-attribute flag that names the form's main data source. */
    private static final String PROP_MAIN = "main"; //$NON-NLS-1$

 //$NON-NLS-1$

    /** Confirmation-message prefix for a completed modify. */
    private static final String MSG_MODIFIED_PREFIX = "Modified "; //$NON-NLS-1$

 //$NON-NLS-1$

 //$NON-NLS-1$

 //$NON-NLS-1$

    /** Confirmation-message fragment before a removed count. */
    private static final String MSG_REMOVED_COUNT = ", removed: "; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Set properties of any metadata node, including managed-form roots, items, " //$NON-NLS-1$
            + "attributes, commands, and handlers, and edit a section's command interface. " //$NON-NLS-1$
            + "Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('modify_metadata')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty("fqn", //$NON-NLS-1$
                "Full-name FQN of the node to modify (required), e.g. 'Catalog.Products' or " //$NON-NLS-1$
                + "'Catalog.Products.Attribute.Weight'; a managed-form root is " //$NON-NLS-1$
                + "'Catalog.Products.Form.ItemForm' or 'CommonForm.Main'; the configuration root is " //$NON-NLS-1$
                + "'Configuration'. On a form FQN ('Catalog.Products.Form.ItemForm', " //$NON-NLS-1$
                + "'CommonForm.Main'), any property of the form's metadata object keeps the whole call " //$NON-NLS-1$
                + "on its .mdo; only an all-content batch goes to the content root. Type / kind tokens may " //$NON-NLS-1$
                + "be English or Russian; the Name parts are the programmatic Name.", true) //$NON-NLS-1$
            .property("properties", //$NON-NLS-1$
                "Properties to set, as [{name, value, language?}], e.g. {name: 'synonym', value: " //$NON-NLS-1$
                + "'Products', language: 'en'}; 'language' defaults to the configuration's default " //$NON-NLS-1$
                + "language. Required unless a Role payload (rights / templates / roleProperties) is given.", //$NON-NLS-1$
                PROPERTY_ENTRIES_SCHEMA)
            .property(KEY_RIGHTS,
                "ROLE only: [{object, right, value?, rls?, rlsFields?}]. object = metadata FQN; right = " //$NON-NLS-1$
                + "right name, English or Russian ('Read' / 'Чтение'); value = 'set' (default) / 'unset' / " //$NON-NLS-1$
                + "'provided' (inherited) or a boolean; rls = restriction condition (1C query text); " //$NON-NLS-1$
                + "rlsFields = an array of field-name strings it applies to (omit or [] for the whole " //$NON-NLS-1$
                + "object).", RIGHTS_SCHEMA) //$NON-NLS-1$
            .property(KEY_TEMPLATES,
                "ROLE only: RLS templates as [{op?, name, condition?}]; op = 'add' (default) / 'edit' / " //$NON-NLS-1$
                + "'delete'; condition is required for add and edit.", TEMPLATES_SCHEMA) //$NON-NLS-1$
            .property(KEY_ROLE_PROPERTIES,
                "ROLE only: {setForNewObjects?, setForAttributesByDefault?, " //$NON-NLS-1$
                + "independentRightsOfChildObjects?} booleans; omitted flags are unchanged.", //$NON-NLS-1$
                ROLE_PROPERTIES_SCHEMA)
            .property(KEY_CONTENT,
                "Membership list to change, as [{op?, metadata, use?, autoRecord?}]: the owners of a " //$NON-NLS-1$
                + "CommonAttribute or Catalog, the content of an ExchangePlan or a (nested) Subsystem, the " //$NON-NLS-1$
                + "register records of a Document. op = 'add' (default) / 'remove'; metadata = member FQN, " //$NON-NLS-1$
                + "e.g. 'Catalog.Products'; use = 'Use' (default) / 'DontUse' / 'Auto', CommonAttribute " //$NON-NLS-1$
                + "only; autoRecord = 'Allow' / 'Deny', ExchangePlan only. Re-adding a listed " //$NON-NLS-1$
                + "CommonAttribute owner SETS its use (omitted -> 'Use'), a listed ExchangePlan member its " //$NON-NLS-1$
                + "given autoRecord; re-adding a plain reference is a no-op. Not with 'properties'.", //$NON-NLS-1$
                CONTENT_SCHEMA)
            .property(KEY_COMMANDS,
                "Only for 'Subsystem.<Name>.CommandInterface' or " //$NON-NLS-1$
                + "'Configuration.MainSectionCommandInterface': [{command, visible?, roles?, group?, after? " //$NON-NLS-1$
                + "| before?}], applied in order as one batch. command = command FQN, e.g. " //$NON-NLS-1$
                + "'CommonCommand.Print'; roles = [{role: 'Role.<Name>', visible: true | false | " //$NON-NLS-1$
                + "'default'}]; group = panel group, e.g. 'NavigationPanelImportant' or " //$NON-NLS-1$
                + "'CommandGroup.<Name>'; after / before = a command of that group. get_metadata_details on " //$NON-NLS-1$
                + "the same FQN lists them. Not with other payloads.", COMMANDS_SCHEMA) //$NON-NLS-1$
            .property(KEY_TEMPLATE,
                "TEMPLATE FQN only, instead of 'properties'. TextDocument: {text, mode?, oldText?, " //$NON-NLS-1$
                + "expectedHash?, expectedSource?, overwrite?}; mode = 'searchReplace' (default, needs " //$NON-NLS-1$
                + "exactly one literal oldText match) / 'append' / 'replace' (the only mode that creates a " //$NON-NLS-1$
                + "missing file); replacing an existing file needs expectedHash or expectedSource from " //$NON-NLS-1$
                + "get_metadata_details, or overwrite=true. SpreadsheetDocument: {cells?, merges?, areas?, " //$NON-NLS-1$
                + "columnWidths?, rowHeights?}. cells [{row, col (both 0-based, required), replace?, text? " //$NON-NLS-1$
                + "| parameter?, bold?, fontSize?, hAlign? (Left/Center/Right/Auto/Width), vAlign? " //$NON-NLS-1$
                + "(Top/Center/Bottom), wrap?, textOrientation? (TENTHS of a degree: 90 degrees = 900), " //$NON-NLS-1$
                + "autoIndent? (0..100), autoMarkIncomplete?}]; merges [{fromRow, fromCol, toRow, toCol}]; " //$NON-NLS-1$
                + "areas [{name, fromRow, fromCol, toRow, toCol}]; columnWidths [{col, width?, " //$NON-NLS-1$
                + "autoWidthCalculation?, widthWeightFactor? (only with autoWidthCalculation:true)}]; " //$NON-NLS-1$
                + "rowHeights [{row, height}]. Omitted or null cell keys keep their values; replace:true " //$NON-NLS-1$
                + "resets them. Non-empty text clears parameter and vice versa; '' clears the named one. " //$NON-NLS-1$
                + "false / 0 on a format key overrides inheritance, but wrap:false restores inherited " //$NON-NLS-1$
                + "placement. Other cells, merges and areas are kept.", TEMPLATE_SCHEMA) //$NON-NLS-1$
            .booleanProperty("normalizeYo", //$NON-NLS-1$
                "Default true: stores 'ё' / 'Ё' as 'е' / 'Е' in synonyms, titles and 'comment' (other " //$NON-NLS-1$
                + "strings stay as supplied); false keeps 'ё'.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return WrittenObjectMarkers.declareOutput(JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the properties were set", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION, "'modified' on success; 'unchanged' when a 'commands' change " //$NON-NLS-1$
                + "requested only what the section already had") //$NON-NLS-1$
            .stringProperty("fqn", "Normalized FQN of the modified node") //$NON-NLS-1$ //$NON-NLS-2$
            .property(KEY_APPLIED, "Names of the properties that were set (for a Role " //$NON-NLS-1$
                + "rights change this is instead an object {rights, templates, roleProperties} with " //$NON-NLS-1$
                + "the applied counts)", //$NON-NLS-1$
                Map.of("oneOf", List.of( //$NON-NLS-1$
                    Map.of("type", "array", "items", Map.of("type", "string")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                    JsonSchemaBuilder.object()
                        .integerProperty(KEY_RIGHTS, "Applied right values", true) //$NON-NLS-1$
                        .integerProperty(KEY_TEMPLATES, "Applied template operations", true) //$NON-NLS-1$
                        .integerProperty(KEY_ROLE_PROPERTIES, "Applied role properties", true) //$NON-NLS-1$
                        .buildMap())))
            .stringProperty(KEY_LANGUAGE, "Language code a localized property was written under; " //$NON-NLS-1$
                + "present only when this call wrote localized properties under exactly ONE code") //$NON-NLS-1$
            .stringArrayProperty(KEY_LOCALES_MISSING,
                "Language codes the configuration USES - the ones its OWN synonym is filled in for - " //$NON-NLS-1$
                + "that still have NO value for at least one of the localized properties just " //$NON-NLS-1$
                + "written (empty when every such language is translated); present only when a " //$NON-NLS-1$
                + "localized property was written. A declared language the configuration itself does " //$NON-NLS-1$
                + "not use is NOT reported: a multilingual configuration worked on in a " //$NON-NLS-1$
                + "single-language branch must not nag about the others") //$NON-NLS-1$
            .stringArrayProperty(KEY_LOCALES_STALE,
                "Language codes whose value this call did NOT write while it REPLACED the text of " //$NON-NLS-1$
                + "the same property in another language - they still carry the PREVIOUS text, so " //$NON-NLS-1$
                + "they now describe the old state (rename the synonym in 'en' and the 'fr' one " //$NON-NLS-1$
                + "keeps the old name). Reported for every DECLARED language that carries text - " //$NON-NLS-1$
                + "unlike 'localesMissing', which asks whether the configuration USES the language, " //$NON-NLS-1$
                + "because text that already exists is not work being demanded of anyone: it is " //$NON-NLS-1$
                + "there, and this call just made it wrong. Decided per PROPERTY, so a language " //$NON-NLS-1$
                + "written into that same property by this call is not listed. Absent when there " //$NON-NLS-1$
                + "are none") //$NON-NLS-1$
            .booleanProperty(KEY_LOCALE_UNUSED,
                "Set when a value was written under a language the configuration itself does not " //$NON-NLS-1$
                + "use (its own synonym has no text for that language). NOT an error - the language " //$NON-NLS-1$
                + "IS declared, so the value will display - but a prompt to ASK the user whether " //$NON-NLS-1$
                + "translating into it is really wanted: it may be a single-language build, or a " //$NON-NLS-1$
                + "language this configuration does not support yet") //$NON-NLS-1$
            .objectProperty(KEY_CONTENT, "For a membership-list content change: the counts object. A " //$NON-NLS-1$
                + "CommonAttribute / ExchangePlan change reports {added, updated, removed} (members " //$NON-NLS-1$
                + "attached / had their per-entry flag - 'use' / 'autoRecord' - updated / detached); a " //$NON-NLS-1$
                + "Catalog owners / Document register records / Subsystem content change (a plain " //$NON-NLS-1$
                + "reference list, no per-entry flag) reports {added, removed}") //$NON-NLS-1$
            .objectProperty(KEY_TEMPLATE, "For a template content change: the applied counts object " //$NON-NLS-1$
                + "{cells, merges, areas, columnWidths, rowHeights}, or {contentHash} for text") //$NON-NLS-1$
            .objectProperty(KEY_COMMANDS, "For a command-interface change: how many commands changed " //$NON-NLS-1$
                + "visibility / group, and how many groups got an explicit order {visibility, placement, " //$NON-NLS-1$
                + "order}") //$NON-NLS-1$
            .objectProperty(KEY_STORED, "For a command-interface change: what the section now stores - " //$NON-NLS-1$
                + "{commands: {<command>: {visible: 'default' | {common, roles}, group: 'default' | " //$NON-NLS-1$
                + "<group>}}, order: {<group>: [commands]}}; 'default' means the platform's own value") //$NON-NLS-1$
            .stringArrayProperty(KEY_UNCHANGED, "For a command-interface change: the commands whose " //$NON-NLS-1$
                + "entries requested what the section already had") //$NON-NLS-1$
            .booleanProperty(KEY_PERSISTED, //$NON-NLS-1$
                "Whether the platform accepted a save task for the change. The tool then waits for the " //$NON-NLS-1$
                    + "export queue to drain before answering, so a success normally means the write has "
                    + "already run - but that establishes the queue is empty, not that the bytes are "
                    + "correct (a platform-side write failure is logged inside EDT), and the wait is "
                    + "skipped where the export state cannot be observed") //$NON-NLS-1$
            .stringArrayProperty("normalized", //$NON-NLS-1$
                "Properties whose value was rewritten by the 'ё'->'е' normalization (when any)") //$NON-NLS-1$
            .stringArrayProperty(ExcludedCommandsSupport.PROPERTY,
                "For an excludedCommands write: the stored exclusion set as canonical English names") //$NON-NLS-1$
            .stringProperty("destination", //$NON-NLS-1$
                "Where a moved form item or predefined item ended up (when 'parent'/'position' moved it), " //$NON-NLS-1$
                + "e.g. \"group 'Main' at index 1\", \"under 'Folder'\"") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable confirmation message") //$NON-NLS-1$
            .stringArrayProperty(WriteScope.RESULT_MEMBER, WriteScope.OUTPUT_SCHEMA_DESCRIPTION))
            .build();
    }

    /** Vendor support is checked when the context is resolved (issue #642). */
    @Override
    protected VendorSupportGuard.Intent writeIntent()
    {
        return VendorSupportGuard.Intent.MODIFY;
    }

    @Override
    protected boolean reportsWrittenObjectMarkers()
    {
        return true;
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        ModifyArgs args = parseModifyArgs(params);
        if (args.error != null)
        {
            return args.error;
        }

        // Normalized BEFORE the context is resolved: the resolution refuses a type this project
        // kind cannot hold, and it has to do so ahead of the specialized dispatches below, which
        // resolve subsystems and XDTO packages through the Configuration - the BASE one for a
        // linked external-objects project (issue #309).
        String normFqn = MetadataTypeUtils.normalizeFqn(args.fqn);
        // A malformed subsystem chain is refused before the context (and its vendor-support lookup) is
        // resolved, for every payload - content as much as properties (issue #897).
        String malformedChain = malformedChainRefusal(normFqn);
        if (malformedChain != null)
        {
            return malformedChain;
        }
        ProjectContext ctx = resolveProjectAndScope(args.projectName, normFqn);
        if (ctx.hasError())
        {
            return ctx.error;
        }

        // A command interface is its own top object with its own payload; the 'commands' payload is
        // refused everywhere else.
        String commandInterfaceResult = dispatchCommandInterface(ctx, args);
        if (commandInterfaceResult != null)
        {
            return commandInterfaceResult;
        }

        // The configuration ROOT ('Configuration', issue #741) is its own address with a fixed set of
        // settable properties; the generic resolver below has no root to offer.
        String rootResult = dispatchConfigurationRoot(ctx, args);
        if (rootResult != null)
        {
            return rootResult;
        }

        // A FQN that addresses a FORM member (item / attribute / command) is dispatched to its own
        // branch: form members live on the editable Form content model (a cross-model hop), not the
        // mdclass tree. A Role / content payload addressed to a form member is refused there.
        FormElementWriter.FormMemberRef formRef = FormElementWriter.parse(normFqn);
        if (formRef != null)
        {
            String columnErr = FormElementWriter.columnAddressingError(formRef);
            if (columnErr != null)
            {
                return ToolResult.error(columnErr).toJson();
            }
            return dispatchFormMemberFqn(ctx, normFqn, formRef, args);
        }

        // A four-part OWNED-form FQN names two objects (issue #995): the form's metadata object (a
        // BasicForm in the owner's forms, written to the owner's .mdo) and its content root (the
        // form:Form object serialized in Form.form). The call is routed by its property names, the
        // rule CommonForm.Name follows below; the owned form is resolved here because the generic
        // resolver's grammar has no form step. A two-part CommonForm.Name is a top object and is
        // deliberately deferred until after mdclass resolution below.
        String formRootPath = FormElementWriter.parseFormPath(normFqn);
        if (shouldDispatchFormRoot(formRootPath, null, args.properties))
        {
            boolean headless = HostKind.current() == HostKind.HEADLESS;
            String formFqn = normFqn;
            String formPath = formRootPath;
            FormRoute route = readPreparation(ctx.project, () -> routeOwnedForm(preparationScope(ctx),
                formPath, formFqn, args.properties,
                args.hasRolePayload || args.hasContentPayload || args.hasTemplatePayload));
            if (route.node == null)
            {
                return formRootNotFoundError(formRootPath);
            }
            if (route.contentRoot)
            {
                return dispatchFormRootFqn(ctx, normFqn, formRootPath, args, OWNED_FORM_MDCLASS_DISCOVERY_HINT);
            }
            if (route.refusal != null)
            {
                return route.refusal;
            }
            // Headless, the node is resolved again inside the write preparation (it must not leave a read).
            return applyGenericPropertyChanges(ctx, args.projectName, normFqn, headless ? null : route.node,
                args.properties, args.normReport);
        }

        // A FQN addressing a PREDEFINED item (Catalog/ChartOfCharacteristicTypes.Name.Predefined.Item)
        // is dispatched EARLY too: the predefined content is a plain EMF containment on the owner, not
        // an mdclass member collection the generic resolver below knows about (issue #293).
        PredefinedWriter.PredefinedRef predefinedRef = PredefinedWriter.parseRef(normFqn);
        if (predefinedRef != null)
        {
            return dispatchPredefinedItemFqn(ctx, normFqn, predefinedRef, args);
        }

        // A SUBSYSTEM-content payload (content[] on a Subsystem FQN) is dispatched EARLY, before the
        // generic single-segment resolver (see dispatchSubsystemContentPayload); null means the
        // request is not a subsystem content change.
        String subsystemResult = dispatchSubsystemContentPayload(ctx, normFqn, args);
        if (subsystemResult != null)
        {
            return subsystemResult;
        }

        // A FQN that addresses an XDTO PACKAGE MEMBER (an ObjectType or a Property - issue #183
        // stream 1) is dispatched EARLY too: an ObjectType/Property lives on the package's lazily
        // materialized xdto.model content (a cross-model hop through a transient @ExternalProperty),
        // not the mdclass tree, so the generic single-segment resolver
        // below cannot see it (it does not know "ObjectType"/"Property" as mdclass child kinds).
        String xdtoResult = dispatchXdtoMemberPayload(ctx, normFqn, args);
        if (xdtoResult != null)
        {
            return xdtoResult;
        }

        // Exact-first resolve with the yo-addressing fallback: create_metadata normalizes
        // 'yo'->'ye' in names by default, so a caller re-typing the original yo spelling
        // would miss the stored name — the resolver retries the normalized FQN.
        final String requestedFqn = normFqn;
        if (HostKind.current() == HostKind.HEADLESS)
            return prepareWrite(ctx.project, () -> planHeadlessMetadataDispatch(ctx, args, requestedFqn));
        ResolvedTarget resolvedTarget = readPreparation(ctx.project,
            () -> resolveModifyTarget(preparationScope(ctx), args.fqn, requestedFqn));
        if (resolvedTarget.error != null)
        {
            return resolvedTarget.error;
        }
        normFqn = resolvedTarget.normFqn;
        MdObject target = resolvedTarget.node.object;

        // CommonForm.Name keeps the mdclass path whenever ANY requested property belongs to that
        // mdclass object. Only an all-non-mdclass property batch falls back to the content-form root;
        // the already-resolved CommonForm proves the FQN names a real common form.
        formRootPath = FormElementWriter.parseFormPath(normFqn);
        if (shouldDispatchFormRoot(formRootPath, target, args.properties))
        {
            return dispatchFormRootFqn(ctx, normFqn, formRootPath, args, COMMON_FORM_MDCLASS_DISCOVERY_HINT);
        }

        // The payload surfaces (template / role / membership content) are dispatched by the
        // resolved target's kind; null means none applies and the generic path runs.
        String payloadResult = dispatchPayloads(ctx, normFqn, target, args);
        if (payloadResult != null)
        {
            return payloadResult;
        }
        // A common form's batch that also names properties its metadata object does not have.
        String mixedForm = mixedFormLayersRefusal(normFqn, target, args.properties);
        if (mixedForm != null)
        {
            return mixedForm;
        }

        // The remaining case: a generic 'properties' change applied through the BM write boundary.
        return applyGenericPropertyChanges(ctx, args.projectName, normFqn, resolvedTarget.node,
            args.properties, args.normReport);
    }

    /** Select a continuation using the live target only while this single preparation read is active. */
    private java.util.function.Supplier<String> planHeadlessMetadataDispatch(ProjectContext handle,
        ModifyArgs args, String requestedFqn)
    {
        ProjectContext ctx = resolveProjectAndScope(args.projectName, requestedFqn);
        if (ctx.hasError()) return () -> ctx.error;
        ResolvedTarget resolved = resolveModifyTarget(ctx.scope, args.fqn, requestedFqn);
        if (resolved.error != null) return () -> resolved.error;
        String fqn = resolved.normFqn;
        MdObject target = resolved.node.object;
        String formPath = FormElementWriter.parseFormPath(fqn);
        if (shouldDispatchFormRoot(formPath, target, args.properties))
            return () -> dispatchFormRootFqn(handle, fqn, formPath, args, COMMON_FORM_MDCLASS_DISCOVERY_HINT);
        if (args.hasTemplatePayload)
        {
            if (!(target instanceof BasicTemplate))
            {
                String refusal = dispatchTemplatePayload(ctx, fqn, target, args.properties,
                    args.content, args.hasRolePayload, args.templateSpec);
                return () -> refusal;
            }
            return () -> modifyTemplateContent(handle, fqn, null, args.properties,
                args.content, args.hasRolePayload, args.templateSpec);
        }
        if (args.hasRolePayload)
        {
            if (!(target instanceof Role))
            {
                String refusal = dispatchRolePayload(ctx, fqn, target, args.properties,
                    args.rolePayloadRights, args.rolePayloadTemplates, args.roleProperties, true);
                return () -> refusal;
            }
            return () -> modifyRoleRights(handle, fqn, null, args.properties,
                args.rolePayloadRights, args.rolePayloadTemplates, args.roleProperties);
        }
        if (args.hasContentPayload)
        {
            String dropped = AdoptedPropertyRouter.refusalIfDropped(target, membershipList(target), fqn,
                ctx.project, resolvePlatformVersion(ctx));
            if (dropped != null) return () -> dropped;
            if (target instanceof CommonAttribute)
                return () -> modifyCommonAttributeContent(handle, fqn, null, args.properties, args.content);
            if (target instanceof ExchangePlan)
                return () -> modifyExchangePlanContent(handle, fqn, null, args.properties, args.content);
            if (target instanceof Catalog)
                return () -> modifyCatalogOwners(handle, fqn, null, args.properties, args.content);
            if (target instanceof Document)
                return () -> modifyDocumentRegisterRecords(handle, fqn, null, args.properties, args.content);
            String refusal = dispatchContentPayload(ctx, fqn, target, args.properties, args.content);
            return () -> refusal;
        }
        String mixedForm = mixedFormLayersRefusal(fqn, target, args.properties);
        if (mixedForm != null) return () -> mixedForm;
        return () -> applyGenericPropertyChanges(handle, args.projectName, fqn, null,
            args.properties, args.normReport);
    }

    /**
     * The parsed + validated arguments of one modify_metadata call (built by
     * {@link #parseModifyArgs}): the addressed project / FQN, the generic 'properties' list, the
     * payload surfaces (role / membership content / template) with their presence flags, and
     * the yo-normalization report. When {@link #error} is non-null (a ready JSON error), the other
     * fields must not be used.
     */
    private static final class ModifyArgs
    {
        /** A ready {@link ToolResult#error} JSON when parsing / validation failed, else {@code null}. */
        String error;
        String projectName;
        String fqn;
        List<JsonObject> properties;
        List<JsonObject> rolePayloadRights;
        List<JsonObject> rolePayloadTemplates;
        JsonObject roleProperties;
        boolean hasRolePayload;
        List<JsonObject> content;
        boolean hasContentPayload;
        JsonObject templateSpec;
        boolean hasTemplatePayload;
        List<JsonObject> commands;
        boolean hasCommandsPayload;
        MdNameNormalizer.Report normReport;
    }

    /**
     * Parses + validates the raw request arguments into a {@link ModifyArgs} bundle: the addressed
     * project / FQN, the generic 'properties' list, the Role payload ('rights' / 'templates' /
     * 'roleProperties'), the membership 'content' payload, the parsed 'template' payload and its
     * presence flag, and the yo-normalization report. {@link ModifyArgs#error} is
     * non-null (a ready JSON error) when a required argument is missing, a payload argument is
     * malformed, or no payload at all was supplied. Extracted verbatim from
     * {@link #executeOnUiThread}.
     */
    private static ModifyArgs parseModifyArgs(Map<String, String> params)
    {
        ModifyArgs args = new ModifyArgs();
        args.error = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, "fqn"); //$NON-NLS-1$
        if (args.error != null)
        {
            return args;
        }
        args.projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        args.fqn = JsonUtils.extractStringArgument(params, "fqn"); //$NON-NLS-1$
        boolean normalizeYo = JsonUtils.extractBooleanArgument(params, "normalizeYo", true); //$NON-NLS-1$
        // A 'properties' of another shape is refused, never read as "no properties": that read answered
        // "properties is required" for an object, and dropped it silently beside another payload (#930).
        String propertiesShape = JsonUtils.objectArrayShapeError(params, "properties"); //$NON-NLS-1$
        if (propertiesShape != null)
        {
            args.error = ToolResult.error(propertiesShape + NOTHING_CHANGED).toJson();
            return args;
        }
        args.properties = JsonUtils.extractObjectArray(params, "properties"); //$NON-NLS-1$

        // Role payload (rights[] / templates[] / roleProperties): dispatched when the resolved FQN is a
        // Role. When present, 'properties' is optional (a role is modified through the rights surface,
        // not the generic property bag).
        args.rolePayloadRights = JsonUtils.extractObjectArray(params, KEY_RIGHTS);
        args.rolePayloadTemplates = JsonUtils.extractObjectArray(params, KEY_TEMPLATES);
        args.roleProperties = parseRolePropertiesArg(params);
        args.hasRolePayload = !args.rolePayloadRights.isEmpty()
            || !args.rolePayloadTemplates.isEmpty() || args.roleProperties != null;

        // Membership content payload (content[]): one generic list dispatched by the resolved FQN's
        // kind (a CommonAttribute's / a Catalog's owners, an ExchangePlan's content objects, a
        // Document's register records). When present, 'properties' is optional (the membership list is
        // edited through its own surface, not the generic property bag) - mirrors the Role rights[]
        // precedent.
        args.content = JsonUtils.extractObjectArray(params, KEY_CONTENT);
        args.hasContentPayload = !args.content.isEmpty();

        // Template spreadsheet-content payload (template={cells/merges/areas/columnWidths/rowHeights}):
        // authored on a SpreadsheetDocument template FQN. When present, 'properties' is optional (the
        // template content is authored through its own surface, not the generic property bag) - mirrors
        // the Role rights[] / membership content[] precedents. A present-but-malformed 'template' (not a
        // JSON object) is rejected here rather than silently dropped: 'template' is the SOLE surface for
        // this feature, so dropping it would apply a stray 'properties' - or misreport 'properties is
        // required' - while the intended template authoring vanished.
        TemplateArg templateArg = parseTemplateArg(params);
        if (templateArg.error != null)
        {
            args.error = templateArg.error;
            return args;
        }
        args.templateSpec = templateArg.spec;
        args.hasTemplatePayload = args.templateSpec != null;

        // Command-interface payload (commands[]): only for a section's command interface FQN.
        args.commands = new ArrayList<>();
        String commandsError = parseCommandsArg(params, args.commands);
        if (commandsError != null)
        {
            args.error = commandsError;
            return args;
        }
        args.hasCommandsPayload = !args.commands.isEmpty();

        if (args.properties.isEmpty() && !args.hasRolePayload && !args.hasContentPayload
            && !args.hasTemplatePayload && !args.hasCommandsPayload)
        {
            args.error = ToolResult.error("properties is required: provide at least one {name, value} to " //$NON-NLS-1$
                + "set, e.g. [{name: 'comment', value: 'Goods'}]. For a Role FQN, provide 'rights', " //$NON-NLS-1$
                + "'templates' or 'roleProperties' instead; for a CommonAttribute / ExchangePlan / " //$NON-NLS-1$
                + "Catalog / Document / Subsystem FQN, provide 'content' instead; for a template FQN, " //$NON-NLS-1$
                + "provide 'template' instead; for a command interface FQN " //$NON-NLS-1$
                + "('Subsystem.<Name>.CommandInterface'), provide 'commands' instead.").toJson(); //$NON-NLS-1$
            return args;
        }

        // 'ё'->'е' normalization is applied at the parse step to every localized-string / free-text
        // value being set (synonym / comment / title / ...), matching mdo-ru-name-unallowed-letter.
        // Rename is out of scope here, so there is no Name to normalize.
        args.normReport = new MdNameNormalizer.Report(normalizeYo);
        return args;
    }

    /**
     * Dispatches a FQN that addresses a FORM member: refuses a 'template' payload up front
     * (a form member is not a spreadsheet template, so the sibling payload is never silently dropped
     * while the form branch reports success), then hands over to
     * {@link #dispatchFormMember} (which symmetrically refuses the Role / membership 'content'
     * payloads). Extracted verbatim from {@link #executeOnUiThread}.
     */
    private String dispatchFormMemberFqn(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef formRef, ModifyArgs args)
    {
        // A 'template' payload addressed to a FORM-member FQN is refused here (a form member is not a
        // spreadsheet template), symmetric with the Role / content refusal dispatchFormMember already
        // enforces, so the sibling payload is never silently dropped while the form branch reports
        // success. The guard is at the call site to keep dispatchFormMember byte-unchanged.
        if (args.hasTemplatePayload)
        {
            return templateOnlyForTemplateFqnError(normFqn, "addresses a FORM member"); //$NON-NLS-1$
        }
        return dispatchFormMember(ctx, normFqn, formRef, args.properties, args.normReport,
            args.hasRolePayload, args.hasContentPayload);
    }

    /**
     * Dispatches an EXISTING managed-form MODEL ROOT. Unlike a form member, the root has no
     * structural side channels (handler/button rebind, move/reorder, dynamic-list query), so the
     * only accepted payload is the ordinary {@code properties} list and it routes directly to the
     * shared form-property apply loop. {@code nonAssignableHint} names the form's metadata object in
     * the refusal of a property the content root does not have either.
     */
    private String dispatchFormRootFqn(ProjectContext ctx, String normFqn, String formRootPath,
        ModifyArgs args, String nonAssignableHint)
    {
        if (args.hasRolePayload || args.hasContentPayload || args.hasTemplatePayload)
        {
            return ToolResult.error("'" + normFqn //$NON-NLS-1$
                + "' addresses a managed FORM root, which only " //$NON-NLS-1$
                + "accepts the 'properties' payload. Role payloads, membership 'content', and " //$NON-NLS-1$
                + "spreadsheet 'template' content apply to their respective metadata objects, not " //$NON-NLS-1$
                + "to the form model root.").toJson(); //$NON-NLS-1$
        }
        return modifyFormRoot(ctx, normFqn, formRootPath, args.properties, args.normReport,
            nonAssignableHint);
    }

    /**
     * Builds the actionable not-found error for a syntactically valid form-root address. An owned
     * form names both the missing form and its owner, so it cannot fall back to the generic mdclass
     * "Node not found" message that describes a different address space. Package-visible for the
     * headless dispatch test.
     */
    static String formRootNotFoundError(String formPath)
    {
        return ToolResult.error(formRootNotFoundMessage(formPath)).toJson();
    }

    /**
     * Resolves only the existing managed-form root address accepted by the dedicated dispatch. The
     * shared parser owns bilingual token recognition and the shared reader owns project-root-aware
     * MD-form resolution. Package-visible so headless tests can exercise the exact dispatch decision
     * without a workbench/BM model.
     */
    static MdObject resolveFormRootForDispatch(MetadataScope scope, String normFqn)
    {
        String formPath = FormElementWriter.parseFormPath(normFqn);
        return formPath == null ? null : readPreparation(scope.project(),
            () -> FormStructureReader.resolveMdForm(scope, formPath));
    }

    /**
     * Decides whether a parsed form address belongs to the content-root write path. Before the form is
     * resolved ({@code resolvedTarget == null}) a four-part owned-form address is taken by the form
     * branch (which resolves it, and reports a missing form with its owner) and a common form waits for
     * the mdclass resolution. A resolved form - owned or common, any {@link BasicForm} - goes to the
     * content root only when none of the requested property names is assignable on its metadata object
     * (issue #995); the check is the same lightweight introspector lookup generic write preparation
     * uses. Package-visible for the headless dispatch test.
     */
    static boolean shouldDispatchFormRoot(String formRootPath, MdObject resolvedTarget,
        List<JsonObject> properties)
    {
        if (formRootPath == null)
        {
            return false;
        }
        if (resolvedTarget == null)
        {
            return FormElementWriter.parseFormObjectCreate(formRootPath) != null;
        }
        if (!(resolvedTarget instanceof BasicForm))
        {
            return false;
        }
        for (JsonObject property : properties)
        {
            String name = asString(property.get("name")); //$NON-NLS-1$
            if (MetadataPropertyIntrospector.findFeature(resolvedTarget, name) != null)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Where a call on an owned-form address goes (built by {@link #routeOwnedForm}): {@link #node} is
     * the form's metadata object ({@code null} when the form does not exist); {@link #contentRoot} sends
     * the call to {@code Form.form}; {@link #refusal} is the mixed-batch refusal; otherwise the call
     * writes the metadata object.
     */
    static final class FormRoute
    {
        final MetadataNodeResolver.MetadataNode node;
        final boolean contentRoot;
        final String refusal;

        private FormRoute(MetadataNodeResolver.MetadataNode node, boolean contentRoot, String refusal)
        {
            this.node = node;
            this.contentRoot = contentRoot;
            this.refusal = refusal;
        }
    }

    /**
     * Routes a call on an owned-form address {@code Type.Object.Form.F} by its property names (issue
     * #995). The address names two objects: the form's metadata object (a {@code BasicForm} in the
     * owner's {@code forms}) and its content root (in {@code Form.form}); their properties share no
     * name. A call with a payload, or with no property of the metadata object, keeps the content-root
     * path it has always had - byte for byte, payload refusals included. Package-visible for tests.
     *
     * @param scope the resolution root
     * @param formRootPath the parsed form path ({@code FormElementWriter.parseFormPath})
     * @param fqn the address as normalized, for the refusal text
     * @param properties the requested properties
     * @param payload whether a role / content / template payload accompanies the call
     * @return the route, never {@code null}
     */
    static FormRoute routeOwnedForm(MetadataScope scope, String formRootPath, String fqn,
        List<JsonObject> properties, boolean payload)
    {
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.formNode(scope, formRootPath);
        if (node == null)
        {
            return new FormRoute(null, false, null);
        }
        if (payload || shouldDispatchFormRoot(formRootPath, node.object, properties))
        {
            return new FormRoute(node, true, null);
        }
        String refusal = mixedFormLayersRefusal(fqn, node.object, properties);
        return new FormRoute(node, false, refusal != null ? refusal : formTypeRefusal(fqn, properties));
    }

    /**
     * Refuses a {@code formType} write on an owned form (issue #995): the enum alone would say Managed or
     * Ordinary while the form's content in {@code Form.form} stays of the kind it was created with, and
     * nothing here converts it. {@code null} when the batch does not name {@code formType}.
     */
    static String formTypeRefusal(String fqn, List<JsonObject> properties)
    {
        String feature = MdClassPackage.Literals.BASIC_FORM__FORM_TYPE.getName();
        for (JsonObject property : properties)
        {
            if (feature.equalsIgnoreCase(asString(property.get("name")))) //$NON-NLS-1$
            {
                return ToolResult.error("'" + MetadataTypeUtils.toCanonicalEnglishFqn(fqn) //$NON-NLS-1$
                    + "': formType of an existing form is not changed: the form's content in Form.form " //$NON-NLS-1$
                    + "stays of the kind it was created with, and setting the property would not convert " //$NON-NLS-1$
                    + "it. The other properties of the form's metadata object (synonym, comment, " //$NON-NLS-1$
                    + "usePurposes, ...) are set as usual." + NOTHING_CHANGED).toJson(); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * The refusal of a form batch that names properties of the form's metadata object AND properties
     * that object does not have (content-root properties, or misspelt ones): one call writes one of the
     * two, so the whole batch is refused before anything is written, naming the split (issue #995).
     * {@code null} for a target that is no form, or a batch that is not mixed; entries without a name are
     * left to the ordinary validation. Package-visible for tests.
     */
    static String mixedFormLayersRefusal(String fqn, MdObject target, List<JsonObject> properties)
    {
        if (!(target instanceof BasicForm))
        {
            return null;
        }
        List<String> metadata = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (JsonObject property : properties)
        {
            String name = asString(property.get("name")); //$NON-NLS-1$
            if (name == null || name.isEmpty())
            {
                continue;
            }
            (MetadataPropertyIntrospector.findFeature(target, name) != null ? metadata : other).add(name);
        }
        if (metadata.isEmpty() || other.isEmpty())
        {
            return null;
        }
        return ToolResult.error("'" + MetadataTypeUtils.toCanonicalEnglishFqn(fqn) //$NON-NLS-1$
            + "' is both the form's metadata object and its content root " //$NON-NLS-1$
            + "(Form.form), and one call writes only one of them. Properties of the metadata object: " //$NON-NLS-1$
            + String.join(", ", metadata) + "; not properties of it: " + String.join(", ", other) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ". Send content-root properties (title, autoTitle, ...) in a separate call on the same FQN, " //$NON-NLS-1$
            + "and check the spelling of a name that is neither; get_metadata_details with assignable:true " //$NON-NLS-1$
            + "lists both." + NOTHING_CHANGED).toJson(); //$NON-NLS-1$
    }

    private static String formRootNotFoundMessage(String formPath)
    {
        String[] parts = formPath == null ? new String[0] : formPath.split("\\."); //$NON-NLS-1$
        if (parts.length == 4)
        {
            String ownerFqn = parts[0] + "." + parts[1]; //$NON-NLS-1$
            return "Form '" + parts[3] + "' not found on owner '" + ownerFqn //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Use get_metadata_details on '" + ownerFqn + "' to list its forms, or " //$NON-NLS-1$ //$NON-NLS-2$
                + "get_metadata_objects to verify the owner FQN."; //$NON-NLS-1$
        }
        if (parts.length == 2)
        {
            return "Common form '" + parts[1] + "' not found. Use " //$NON-NLS-1$ //$NON-NLS-2$
                + "get_metadata_objects to list available CommonForm FQNs."; //$NON-NLS-1$
        }
        return "Form not found for '" + formPath //$NON-NLS-1$
            + "'. Use get_metadata_objects to verify the owner and form FQN."; //$NON-NLS-1$
    }

    /**
     * Dispatches a FQN addressing a PREDEFINED item on a {@code Catalog},
     * {@code ChartOfCharacteristicTypes}, {@code ChartOfCalculationTypes} or {@code ChartOfAccounts}
     * owner ({@code Type.Owner.Predefined.ItemName}). Refuses the sibling payloads (Role / membership
     * {@code content} / {@code template}) up front so they are never silently dropped,
     * then validates the owner kind (in lockstep with {@link PredefinedWriter#unsupportedOwnerTypeError}),
     * parses the properties via the SHARED {@link PredefinedWriter#parseProperties} (which also refuses
     * {@code name} and {@code parent} - a move - on modify), resolves the owner (yo-fallback) and
     * mutates it via {@link PredefinedWriter#modify} inside a BM write transaction. The owner kind is
     * NOT switched on here: every owner flows through the SAME generic path, with the per-owner
     * property vocabulary and its gating living inside {@link PredefinedWriter}. Like create, there is
     * no separate top object to attach - only the owner's canonical FQN is force-exported.
     */
    private String dispatchPredefinedItemFqn(ProjectContext ctx, String normFqn,
        PredefinedWriter.PredefinedRef ref, ModifyArgs args)
    {
        return prepareWrite(ctx.project, () -> {
            ProjectContext current = HostKind.current() == HostKind.HEADLESS
                ? resolveProjectAndScope(args.projectName, normFqn) : ctx;
            if (current.hasError()) return () -> current.error;
            return preparePredefinedModification(current, normFqn, ref, args);
        });
    }

    private java.util.function.Supplier<String> preparePredefinedModification(ProjectContext ctx, String normFqn,
        PredefinedWriter.PredefinedRef ref, ModifyArgs args)
    {
        if (args.hasTemplatePayload)
        {
            return () -> templateOnlyForTemplateFqnError(normFqn, "addresses a predefined item"); //$NON-NLS-1$
        }
        if (args.hasRolePayload || args.hasContentPayload)
        {
            return () -> ToolResult.error("'rights'/'templates'/'roleProperties'/'content' do not apply to " //$NON-NLS-1$
                + "a predefined item; '" + normFqn + "' addresses a predefined item. Use 'properties' " //$NON-NLS-1$ //$NON-NLS-2$
                + "(description / code on every owner, isFolder on a Catalog / " //$NON-NLS-1$ //$NON-NLS-2$
                + "ChartOfCharacteristicTypes, plus the owner-specific properties named in this " //$NON-NLS-1$
                + "tool's description).").toJson(); //$NON-NLS-1$
        }

        String ownerTypeErr = PredefinedWriter.unsupportedOwnerTypeError(ref.ownerType);
        if (ownerTypeErr != null)
        {
            return () -> ToolResult.error(ownerTypeErr).toJson();
        }

        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        String propErr = PredefinedWriter.parseProperties(args.properties, true, props);
        if (propErr != null)
        {
            return () -> propErr;
        }

        Configuration config = ctx.config;
        // A ChartOfCharacteristicTypes item's valueType is the one per-item property that needs a
        // resolution context (Configuration + platform Version) PredefinedWriter itself cannot reach:
        // it is built via MetadataTypeBuilder, the SAME platform machinery an attribute's type uses.
        // (extDimensionTypes[].characteristicType is NOT a consumer - the writer resolves it by
        // navigating the live model, not against config.) Stash the SAME context the generic
        // attribute-type path (resolvePlatformVersion / ExtensionOriginUtils.isExtensionProject)
        // resolves on props for PredefinedWriter#modify to use. Configuration is fetched in the write;
        // only the version and project-kind flag survive preparation as scalars.
        props.version = resolvePlatformVersion(ctx);
        props.isExtensionProject = ExtensionOriginUtils.isExtensionProject(ctx.project);
        // Owner resolution uses the yo-fallback; force-export targets the RESOLVED owner's canonical FQN.
        MetadataNodeResolver.ResolvedNode ownerResolved =
            MetadataNodeResolver.resolveExistingWithYoFallback(ctx.scope, ref.ownerFqn());
        if (ownerResolved.node == null)
        {
            return () -> ToolResult.error("Owner object not found: " + ref.ownerFqn() + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Use get_metadata_objects to list available objects." //$NON-NLS-1$
                + MetadataNodeResolver.yoNotFoundHint(ref.ownerFqn())
                + ctx.scope.addressingHint(ref.ownerFqn())).toJson();
        }
        MdObject owner = ownerResolved.node.object;
        if (!(owner instanceof IBmObject))
        {
            return () -> ToolResult.error("Owner object is not a BM object").toJson(); //$NON-NLS-1$
        }
        // The predefined items of an owner an extension adopted reach its .mdo only when the extension
        // marks them; otherwise EDT's .mdo writer drops the whole list (issues #709 / #710).
        String droppedPredefined = AdoptedPropertyRouter.refusalIfDropped(owner,
            owner.eClass().getEStructuralFeature("predefined"), ref.ownerFqn(), ctx.project, //$NON-NLS-1$
            resolvePlatformVersion(ctx));
        if (droppedPredefined != null)
        {
            return () -> droppedPredefined;
        }

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return () -> ToolResult.error(ERR_NO_BM_MANAGER).toJson();
        }
        IBmModel bmModel = BmModelResolver.resolve(ctx.project, bmModelManager).getModel();
        if (bmModel == null)
        {
            return () -> ToolResult.error(ERR_NO_BM_MODEL + args.projectName).toJson();
        }

        // Retyping a predefined item is as destructive as retyping an attribute (it can drop stored
        // values on the next database update), so it goes through the SAME consent gate the ordinary
        // valueType path uses - BEFORE the write transaction opens.
        final long ownerBmId = ((IBmObject)owner).bmGetId();
        final String itemName = ref.itemName;
        final String ownerFqn = ref.ownerFqn();
        // Force-export must target the owner's CANONICAL FQN (its own bmGetFqn()), never the
        // caller's spelling (ownerResolved.fqn echoes the input) - a case/spelling variant that
        // still resolves would otherwise export a non-existent FQN and leave the change in memory.
        final String[] canonicalOwnerFqnHolder = new String[1];
        final String[] destination = new String[1];

        return () -> {
            if (props.valueTypeSet)
            {
                String consentErr = consentForPredefinedRetype(normFqn);
                if (consentErr != null)
                {
                    return consentErr;
                }
            }

            try
            {
                BmTransactions.<Void>write(bmModel, "ModifyPredefinedItem", (tx, pm) -> //$NON-NLS-1$
                {
                    EObject txOwner = (EObject)tx.getObjectById(ownerBmId);
                    if (txOwner == null)
                    {
                        throw new RuntimeException("Owner object not found in transaction"); //$NON-NLS-1$
                    }
                    canonicalOwnerFqnHolder[0] = ((IBmObject)txOwner).bmGetFqn();
                    props.config = HostKind.current() == HostKind.HEADLESS
                        ? (Configuration)tx.getTopObjectByFqn("Configuration") : config; //$NON-NLS-1$
                    PredefinedWriter.WriteResult result = modifyPredefinedInWrite(txOwner, itemName, props, ownerFqn);
                    if (result.isError())
                    {
                        // A refusal: the message is OUR validation's, so mark it and keep the log quiet.
                        throw Refusals.state(result.error);
                    }
                    destination[0] = props.parentSet ? PredefinedWriter.locationOf(result.item) : null;
                    return null;
                });
            }
            catch (Exception e)
            {
                Refusals.log("Error modifying predefined item", e); //$NON-NLS-1$
                return ToolResult.error("Failed to modify: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
            }

            boolean persisted = BmTransactions.forceExportToDisk(ctx.project, canonicalOwnerFqnHolder[0]);
            List<String> applied = new ArrayList<>();
            if (props.descriptionSet)
            {
                applied.add("description"); //$NON-NLS-1$
            }
            if (props.codeSet)
            {
                applied.add("code"); //$NON-NLS-1$
            }
            if (props.valueTypeSet)
            {
                applied.add("valueType"); //$NON-NLS-1$
            }
            if (props.isFolderSet)
            {
                applied.add("isFolder"); //$NON-NLS-1$
            }
            // ChartOfAccounts owner-specific properties (parsed + applied by PredefinedWriter.modify).
            if (props.accountTypeSet)
            {
                applied.add("accountType"); //$NON-NLS-1$
            }
            if (props.offBalanceSet)
            {
                applied.add("offBalance"); //$NON-NLS-1$
            }
            if (props.orderSet)
            {
                applied.add("order"); //$NON-NLS-1$
            }
            if (props.accountingFlagsSet)
            {
                applied.add("accountingFlags"); //$NON-NLS-1$
            }
            if (props.extDimensionTypesSet)
            {
                applied.add("extDimensionTypes"); //$NON-NLS-1$
            }
            // ChartOfCalculationTypes owner-specific properties.
            if (props.actionPeriodIsBaseSet)
            {
                applied.add("actionPeriodIsBase"); //$NON-NLS-1$
            }
            if (props.baseSet)
            {
                applied.add("base"); //$NON-NLS-1$
            }
            if (props.displacedSet)
            {
                applied.add("displaced"); //$NON-NLS-1$
            }
            if (props.leadingSet)
            {
                applied.add("leading"); //$NON-NLS-1$
            }
            if (props.parentSet)
            {
                applied.add("parent"); //$NON-NLS-1$
            }
            String modified = buildModifiedResult(normFqn, applied, persisted, args.normReport);
            return destination[0] == null ? modified
                : ToolResult.parse(modified).put("destination", destination[0]).toJson(); //$NON-NLS-1$
        };
    }

    /**
     * Dispatches a SUBSYSTEM-content payload (content[] on a Subsystem FQN, possibly NESTED - e.g.
     * 'Subsystem.Sales.Subsystem.Orders') BEFORE the generic single-segment resolver: a subsystem's
     * content list is edited through its own membership surface, and the shared
     * SubsystemUtils.resolveByFqn is the only resolver that walks a nested (and bilingual) subsystem
     * path. Scoped to a content payload so a subsystem FQN carrying only 'properties' still takes
     * the normal generic-property path (its 'content' generic property still REPLACES the whole
     * list; the content[] payload here ADDS / REMOVES one member). Returns {@code null} when the
     * request is not a subsystem content change, so the caller continues down the normal path.
     * Extracted verbatim from {@link #executeOnUiThread}.
     */
    private String dispatchSubsystemContentPayload(ProjectContext ctx, String normFqn, ModifyArgs args)
    {
        if (!args.hasContentPayload || !SubsystemUtils.isSubsystemTypeToken(firstToken(normFqn)))
        {
            return null;
        }
        if (HostKind.current() == HostKind.HEADLESS)
        {
            return prepareWrite(ctx.project, () -> {
                ProjectContext current = resolveProjectAndScope(args.projectName, normFqn);
                if (current.hasError()) return () -> current.error;
                Subsystem subsystem = SubsystemUtils.resolveByFqn(current.config, normFqn);
                if (subsystem == null) return () -> ToolResult.error(SubsystemUtils.notFoundMessage(args.fqn)).toJson();
                if (args.hasTemplatePayload)
                {
                    String refusal = templateOnlyForTemplateFqnError(normFqn, ERR_IS_A + subsystem.eClass().getName());
                    return () -> refusal;
                }
                String dropped = AdoptedPropertyRouter.refusalIfDropped(subsystem, membershipList(subsystem), normFqn,
                    current.project, resolvePlatformVersion(current));
                if (dropped != null) return () -> dropped;
                return prepareSubsystemContent(current, normFqn, subsystem, args.properties, args.content, args.hasRolePayload);
            });
        }
        Subsystem subsystem = readPreparation(ctx.project, () -> SubsystemUtils.resolveByFqn(ctx.config, normFqn));
        if (subsystem == null)
        {
            return ToolResult.error(SubsystemUtils.notFoundMessage(args.fqn)).toJson();
        }
        // A 'template' payload addressed to a Subsystem FQN is refused here (a subsystem is not a
        // spreadsheet template), so a template payload combined with a subsystem content[] payload is
        // never silently dropped. The guard is at the call site to keep modifySubsystemContent
        // byte-unchanged.
        if (args.hasTemplatePayload)
        {
            return templateOnlyForTemplateFqnError(normFqn, ERR_IS_A + subsystem.eClass().getName());
        }
        // EDT's adopter marks an adopted subsystem's content Extended and EDT's .mdo writer then keeps the list, so
        // this refuses only a content state of None (issues #709 / #710).
        String dropped = readPreparation(ctx.project, () -> AdoptedPropertyRouter.refusalIfDropped(subsystem, membershipList(subsystem), normFqn,
            ctx.project, resolvePlatformVersion(ctx)));
        if (dropped != null)
        {
            return dropped;
        }
        return modifySubsystemContent(ctx, normFqn, subsystem, args.properties, args.content,
            args.hasRolePayload);
    }

    // ===== Section command interface (issue #666) ===================================================

    /**
     * Waits, off the UI thread, until EDT has recomputed the command interface a 'commands' change is
     * validated against - a content change made a moment earlier is not in it before that.
     */
    @Override
    protected String beforeUiThreadOrError(Map<String, String> params)
    {
        CommandInterfaceAddress address =
            CommandInterfaceAddress.parse(JsonUtils.extractStringArgument(params, "fqn")); //$NON-NLS-1$
        if (address == null || JsonUtils.extractObjectArray(params, KEY_COMMANDS).isEmpty())
        {
            return null;
        }
        com.ditrix.edt.mcp.server.utils.ProjectContext project = com.ditrix.edt.mcp.server.utils.ProjectContext
            .of(JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME));
        // A missing project is named by the regular resolution on the UI thread.
        if (!project.exists() || CommandInterfaceSupport.awaitComputed(project.project(), COMMAND_INTERFACE_WAIT_MS))
        {
            return null;
        }
        return "EDT is still computing the command interface of project '" + project.name() //$NON-NLS-1$
            + "', which a 'commands' change is validated against. Nothing was changed; retry in a few " //$NON-NLS-1$
            + "seconds."; //$NON-NLS-1$
    }

    /**
     * Dispatches a command-interface FQN and the 'commands' payload, which only go together.
     * Returns {@code null} when the call is neither, so the caller continues down the normal path.
     */
    private String dispatchCommandInterface(ProjectContext ctx, ModifyArgs args)
    {
        CommandInterfaceAddress address = CommandInterfaceAddress.parse(args.fqn);
        if (address == null)
        {
            return args.hasCommandsPayload ? ToolResult.error(commandsOnlyForCommandInterfaceError(args.fqn))
                .toJson() : null;
        }
        if (address.kind() == CommandInterfaceAddress.Kind.SECTIONS_PANEL)
        {
            return ToolResult.error(CommandInterfaceSupport.sectionsPanelRefusal()).toJson();
        }
        String fqn = address.canonicalFqn();
        if (!args.hasCommandsPayload)
        {
            return ToolResult.error("'" + fqn + "' is a command interface, which has no 'properties' of " //$NON-NLS-1$ //$NON-NLS-2$
                + "its own: change its commands with 'commands' = [{command, visible?, roles?, group?, " //$NON-NLS-1$
                + "after? | before?}]. get_metadata_details on the same FQN lists them.").toJson(); //$NON-NLS-1$
        }
        if (!args.properties.isEmpty() || args.hasRolePayload || args.hasContentPayload
            || args.hasTemplatePayload)
        {
            return ToolResult.error("'commands' cannot be combined with 'properties', a Role payload, " //$NON-NLS-1$
                + "'content' or 'template' in one call. Change the command interface alone, and its owner (" //$NON-NLS-1$
                + address.ownerFqn() + ") in a separate call.").toJson(); //$NON-NLS-1$
        }
        if (ctx.scope.isExternalObjects())
        {
            return ToolResult.error("Project '" + ctx.project.getName() + "' is an external-objects " //$NON-NLS-1$ //$NON-NLS-2$
                + "project, which has no command interface. Address " + fqn //$NON-NLS-1$
                + " in the configuration project it belongs to.").toJson(); //$NON-NLS-1$
        }
        if (ExtensionOriginUtils.isExtensionProject(ctx.project))
        {
            return ToolResult.error(CommandInterfaceSupport.extensionRefusal(ctx.project.getName())).toJson();
        }
        return modifyCommandInterface(ctx, address, args.commands);
    }

    /**
     * Dispatches the configuration ROOT (issue #741): {@code Configuration}, its Russian token, or
     * {@code Configuration.<its Name>}. The root takes only 'properties', and of them only
     * {@link ConfigurationRoot#settableProperties()}; every refusal comes before anything is written. An
     * accepted call goes down the generic property path with the root as a top-level node, so its
     * validation, its one BM write transaction, the export of the root's own top object
     * ({@link ConfigurationRoot#FQN}) and its result are those of any other object. Returns {@code null}
     * when the FQN is not a root address, so the caller continues down the normal path.
     */
    private String dispatchConfigurationRoot(ProjectContext ctx, ModifyArgs args)
    {
        if (HostKind.current() == HostKind.HEADLESS)
        {
            RootDispatch decision = readPreparation(ctx.project, () -> {
                var root = ConfigurationRoot.resolve(preparationScope(ctx), args.fqn);
                if (root == null) return new RootDispatch(false, null);
                String refusal = configurationRootRefusal(root.error, args.hasRolePayload,
                    args.hasContentPayload, args.hasTemplatePayload, args.properties);
                return new RootDispatch(true, refusal == null ? null : ToolResult.error(refusal).toJson());
            });
            if (!decision.matches) return null;
            if (decision.error != null) return decision.error;
            return applyGenericPropertyChanges(ctx, args.projectName, ConfigurationRoot.FQN, null,
                args.properties, args.normReport);
        }
        return dispatchConfigurationRoot(ctx.scope, args.fqn, args.hasRolePayload, args.hasContentPayload,
            args.hasTemplatePayload, args.properties,
            root -> applyGenericPropertyChanges(ctx, args.projectName, ConfigurationRoot.FQN, root,
                args.properties, args.normReport));
    }

    private record RootDispatch(boolean matches, String error) { }

    /**
     * The root dispatch itself: resolves a root address, judges the call with
     * {@link #configurationRootRefusal}, and hands the root to {@code write} only when nothing was
     * refused. The allow-list is the only barrier between a root call and the generic property path,
     * which can set ANY assignable feature of the root - its object collections included - so the order
     * is the point: no refused call reaches {@code write}. Package-visible for tests.
     *
     * @param scope the project's resolution root
     * @param fqn the requested address
     * @param rolePayload whether a role payload was sent
     * @param contentPayload whether a 'content' payload was sent
     * @param templatePayload whether a 'template' payload was sent
     * @param properties the 'properties' entries
     * @param write writes the properties on the resolved root (a top-level node) and answers the result
     * @return {@code null} when {@code fqn} is not a root address; else the refusal as ready JSON, or
     *     what {@code write} answered
     */
    static String dispatchConfigurationRoot(MetadataScope scope, String fqn, boolean rolePayload,
        boolean contentPayload, boolean templatePayload, List<JsonObject> properties,
        Function<MetadataNodeResolver.MetadataNode, String> write)
    {
        ConfigurationRoot.Resolution root = readPreparation(scope.project(), () -> ConfigurationRoot.resolve(scope, fqn));
        if (root == null)
        {
            return null;
        }
        String refusal = configurationRootRefusal(root.error, rolePayload, contentPayload, templatePayload,
            properties);
        if (refusal != null)
        {
            return ToolResult.error(refusal).toJson();
        }
        return write.apply(root.node);
    }

    /**
     * The refusal of a call on the configuration root, judged in the order the dispatch must: the address
     * (another configuration's Name, a project with no root of its own), then the payload, then the
     * properties. Package-visible for tests.
     *
     * @param resolutionError why the address does not name this project's root, or {@code null}
     * @param rolePayload whether a role payload was sent
     * @param contentPayload whether a 'content' payload was sent
     * @param templatePayload whether a 'template' payload was sent
     * @param properties the 'properties' entries
     * @return the first refusal that applies, or {@code null} when the call may proceed
     */
    static String configurationRootRefusal(String resolutionError, boolean rolePayload, boolean contentPayload,
        boolean templatePayload, List<JsonObject> properties)
    {
        if (resolutionError != null)
        {
            return resolutionError;
        }
        String payloadError = configurationRootPayloadError(rolePayload, contentPayload, templatePayload);
        return payloadError != null ? payloadError : configurationRootPropertiesError(properties);
    }

    /**
     * The refusal of a payload other than 'properties' on the configuration root (a 'commands' payload is
     * refused by the command-interface dispatch before this). Package-visible for tests.
     *
     * @param rolePayload whether a role payload was sent
     * @param contentPayload whether a 'content' payload was sent
     * @param templatePayload whether a 'template' payload was sent
     * @return the refusal naming the payload, or {@code null} when none was sent
     */
    static String configurationRootPayloadError(boolean rolePayload, boolean contentPayload,
        boolean templatePayload)
    {
        String payload;
        if (rolePayload)
        {
            payload = "a role payload ('rights' / 'templates' / 'roleProperties')"; //$NON-NLS-1$
        }
        else if (contentPayload)
        {
            payload = "'content'"; //$NON-NLS-1$
        }
        else if (templatePayload)
        {
            payload = "'template'"; //$NON-NLS-1$
        }
        else
        {
            return null;
        }
        return "'" + ConfigurationRoot.FQN + "' (the configuration root) accepts only 'properties'; " //$NON-NLS-1$ //$NON-NLS-2$
            + payload + " is not taken there. Set its properties as properties=[{name, value, language?}]."; //$NON-NLS-1$
    }

    /**
     * The refusal of the configuration root's 'properties' when any entry is not settable there - checked
     * for the whole batch before anything is written, like the generic validation. Package-visible for
     * tests.
     *
     * @param properties the 'properties' entries
     * @return the refusal of the first entry without a name or with a property the root does not take, or
     *     {@code null} when every entry is settable
     */
    static String configurationRootPropertiesError(List<JsonObject> properties)
    {
        if (properties == null)
        {
            return null;
        }
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (name == null || name.isEmpty())
            {
                return "Each entry in 'properties' needs a non-empty 'name'."; //$NON-NLS-1$
            }
            if (!ConfigurationRoot.isSettable(name))
            {
                return ConfigurationRoot.notSettableMessage(name);
            }
        }
        return null;
    }

    /**
     * Reads the 'commands' payload strictly: a skipped entry would apply part of a batch that is
     * validated as a whole.
     *
     * @param params the tool parameters
     * @param into receives the entries
     * @return a ready JSON error, or {@code null}
     */
    static String parseCommandsArg(Map<String, String> params, List<JsonObject> into)
    {
        String raw = params.get(KEY_COMMANDS);
        if (raw == null || raw.trim().isEmpty())
        {
            return null;
        }
        JsonElement parsed;
        try
        {
            parsed = JsonParser.parseString(raw.trim());
        }
        catch (RuntimeException e)
        {
            parsed = null;
        }
        if (parsed != null && parsed.isJsonNull())
        {
            return null;
        }
        if (parsed == null || !parsed.isJsonArray())
        {
            return ToolResult.error("'commands' must be an array of {command, visible?, roles?, group?, " //$NON-NLS-1$
                + "after? | before?} objects, e.g. [{command:'Report.Sales.StandardCommand.Open', " //$NON-NLS-1$
                + "visible:false}].").toJson(); //$NON-NLS-1$
        }
        JsonArray array = parsed.getAsJsonArray();
        for (int i = 0; i < array.size(); i++)
        {
            JsonElement entry = array.get(i);
            if (!entry.isJsonObject())
            {
                return ToolResult.error("commands[" + i + "] must be an object {command, visible?, roles?, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "group?, after? | before?}, got " + entry + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            into.add(entry.getAsJsonObject());
        }
        return null;
    }

    /** The refusal for a 'commands' payload addressed to anything but a section's command interface. */
    static String commandsOnlyForCommandInterfaceError(String fqn)
    {
        String why = CommandInterfaceAddress.hasCommandInterfaceTail(fqn)
            ? " only a subsystem and the configuration have a section command interface." //$NON-NLS-1$
            : " it is not a command interface address."; //$NON-NLS-1$
        return "'commands' applies only to a section's command interface - 'Subsystem.<Name>.CommandInterface' " //$NON-NLS-1$
            + "(nested: 'Subsystem.<Parent>.Subsystem.<Child>.CommandInterface') or " //$NON-NLS-1$
            + "'Configuration.MainSectionCommandInterface'. '" + fqn + "' does not qualify:" + why; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Validates the whole 'commands' batch against the section, applies it in one BM write through
     * EDT's own command interface tasks and exports the command interface's own file.
     */
    private String modifyCommandInterface(ProjectContext ctx, CommandInterfaceAddress address,
        List<JsonObject> entries)
    {
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error(ERR_NO_BM_MANAGER).toJson();
        }
        IBmModel bmModel = BmModelResolver.resolve(ctx.project, bmModelManager).getModel();
        if (bmModel == null)
        {
            return ToolResult.error(ERR_NO_BM_MODEL + ctx.project.getName()).toJson();
        }
        CommandInterfaceSupport.EditResult edit = CommandInterfaceSupport.edit(bmModel, address, entries);
        if (edit.error != null)
        {
            return ToolResult.error(edit.error).toJson();
        }
        boolean persisted = edit.writtenFqn != null
            && BmTransactions.forceExportToDisk(ctx.project, edit.writtenFqn);
        return buildCommandInterfaceResult(address.canonicalFqn(), edit, persisted);
    }

    /** The success JSON of a 'commands' change. Package-visible for tests. */
    static String buildCommandInterfaceResult(String fqn, CommandInterfaceSupport.EditResult edit,
        boolean persisted)
    {
        CommandInterfaceSection.Plan plan = edit.plan;
        JsonObject counts = new JsonObject();
        counts.addProperty("visibility", plan.visibility().size()); //$NON-NLS-1$
        counts.addProperty("placement", plan.placement().size()); //$NON-NLS-1$
        counts.addProperty("order", plan.order().size()); //$NON-NLS-1$
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, plan.isEmpty() ? "unchanged" : VAL_MODIFIED) //$NON-NLS-1$
            .put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(fqn)) //$NON-NLS-1$
            .put(KEY_COMMANDS, counts)
            .put(KEY_PERSISTED, persisted);
        if (edit.stored != null)
        {
            result.put(KEY_STORED, edit.stored);
        }
        if (!plan.unchanged().isEmpty())
        {
            JsonArray unchanged = new JsonArray();
            plan.unchanged().forEach(unchanged::add);
            result.put(KEY_UNCHANGED, unchanged);
        }
        String message = plan.isEmpty()
            ? "Nothing to change in " + MetadataTypeUtils.toCanonicalEnglishFqn(fqn)
                + ": every entry requested what the section already has" //$NON-NLS-1$ //$NON-NLS-2$
            : MSG_MODIFIED_PREFIX + MetadataTypeUtils.toCanonicalEnglishFqn(fqn) + " (visibility: " + plan.visibility().size() + ", placement: " //$NON-NLS-1$ //$NON-NLS-2$
                + plan.placement().size() + ", order: " + plan.order().size() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        return result.put(McpKeys.MESSAGE, message).toJson();
    }

    // ===== XDTO package member editing (issue #183 stream 1) ==========================================
    //
    // An XDTOPackage member (an ObjectType or a Property, package-global or nested in an ObjectType) is
    // modified through the SAME 'properties' surface as an ordinary mdclass member, but with an XDTO-
    // specific vocabulary applied by XdtoWriter (open/abstract/mixed/ordered/sequenced for an ObjectType;
    // type/ref/lowerBound/upperBound/nillable/fixed/default for a Property) - not the generic
    // MetadataPropertyIntrospector reflection path (an XDTO Property/ObjectType is not an MdObject).
    // The Package is a transient @ExternalProperty, materialized and attached via
    // XdtoWriter.resolvePackageContent (shared with create_metadata / delete_metadata).

    /**
     * Dispatches an XDTO PACKAGE MEMBER FQN: refuses a role / content / template payload (an XDTO
     * member is none of those - the same no-mixing policy every other cross-model-hop branch enforces,
     * so a sibling payload is never silently dropped while this branch reports success), then requires a
     * non-empty {@code properties} (the XDTO member's own change surface). Returns {@code null} when
     * {@code normFqn} is not an XDTO member FQN, so the caller continues to the generic mdclass resolver.
     */
    private String dispatchXdtoMemberPayload(ProjectContext ctx, String normFqn, ModifyArgs args)
    {
        XdtoWriter.MemberRef ref = XdtoWriter.parseMemberRef(normFqn);
        if (ref == null)
        {
            return null;
        }
        if (args.hasTemplatePayload)
        {
            return templateOnlyForTemplateFqnError(normFqn, "addresses an XDTO package member"); //$NON-NLS-1$
        }
        String payloadError =
            xdtoMemberPayloadError(normFqn, args.hasRolePayload, args.hasContentPayload, args.properties);
        if (payloadError != null)
        {
            return payloadError;
        }
        return modifyXdtoMember(ctx, normFqn, ref, args.properties);
    }

    /**
     * The pure guard for an XDTO member FQN's payload: refuses a Role payload ({@code rights} /
     * {@code templates} / {@code roleProperties}) or a membership {@code content} payload (an XDTO
     * member is neither), then requires a non-empty {@code properties} (the XDTO member's own change
     * surface - there is no dedicated {@code xdto} payload key, unlike {@code template}).
     * Returns the ready JSON error, or {@code null} when the payload is valid. Package-visible for tests
     * (mirrors {@link #templateMixError}).
     */
    static String xdtoMemberPayloadError(String normFqn, boolean hasRolePayload, boolean hasContentPayload,
        List<JsonObject> properties)
    {
        if (hasRolePayload || hasContentPayload)
        {
            return ToolResult.error("'" + normFqn + "' addresses an XDTO package member, which cannot " //$NON-NLS-1$ //$NON-NLS-2$
                + "take a Role payload ('rights' / 'templates' / 'roleProperties') or a membership " //$NON-NLS-1$
                + "'content' payload. Use 'properties' to change an XDTO ObjectType/Property.").toJson(); //$NON-NLS-1$
        }
        if (properties.isEmpty())
        {
            return ToolResult.error("'properties' is required to modify an XDTO package member ('" //$NON-NLS-1$
                + normFqn + "'): an ObjectType takes the optional flags 'open' / 'abstract' / 'mixed' " //$NON-NLS-1$
                + "/ 'ordered' / 'sequenced'; a Property takes 'type' / 'lowerBound' / 'upperBound' / " //$NON-NLS-1$
                + "'nillable' / 'fixed' / 'default'.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Modifies an EXISTING XDTO ObjectType / Property's attributes via {@link XdtoWriter}. The owning
     * XDTOPackage's content is materialized + attached exactly like create_metadata's own XDTO member
     * write ({@link XdtoWriter#resolvePackageContent}, shared); the target member must ALREADY exist (a
     * modify does not create one - use create_metadata first). Force-exports the owning package's FQN
     * (dual-export with the content's own resource FQN when it is a distinct top object).
     */
    private String modifyXdtoMember(ProjectContext ctx, String normFqn, XdtoWriter.MemberRef ref,
        List<JsonObject> properties)
    {
        MetadataNodeResolver.ObjectIdentity pkg = readPreparation(ctx.project,
            () -> MetadataNodeResolver.resolveIdentity(preparationScope(ctx), ref.packageFqn));
        if (pkg == null || !"XDTOPackage".equals(pkg.kind())) //$NON-NLS-1$
        {
            return ToolResult.error("XDTOPackage not found: " + ref.packageFqn + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        ITopObjectFqnGenerator fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();
        if (bmModelManager == null || fqnGenerator == null)
        {
            return ToolResult.error(ERR_NO_BM_MANAGER).toJson();
        }
        IBmModel bmModel = BmModelResolver.resolve(ctx.project, bmModelManager).getModel();
        if (bmModel == null)
        {
            return ToolResult.error(ERR_NO_BM_MODEL + ctx.project.getName()).toJson();
        }

        final long pkgBmId = pkg.bmId();
        final String[] contentFqnHolder = { null };
        XdtoWriter.Result result;
        try
        {
            result = BmTransactions.<XdtoWriter.Result> write(bmModel, "ModifyXdtoMember", (tx, pm) -> //$NON-NLS-1$
                modifyXdtoMemberInTx(tx, pkgBmId, fqnGenerator, ref, properties, contentFqnHolder));
        }
        catch (Exception e)
        {
            String ready = XdtoWriteException.jsonOf(e);
            if (ready != null)
            {
                return ready;
            }
            Activator.logError("Error modifying XDTO member", e); //$NON-NLS-1$
            return ToolResult.error("Failed to modify XDTO member: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        List<String> exportFqns = new ArrayList<>();
        // Export by the RESOLVED package's canonical FQN: with the yo (ё->е) fallback the
        // caller-typed ref.packageFqn may differ from the stored name, and force-export must
        // target the stored top object (never the caller-supplied spelling).
        String pkgExportFqn = pkg.fqn();
        exportFqns.add(pkgExportFqn);
        String contentFqn = contentFqnHolder[0];
        if (contentFqn != null && !contentFqn.equals(pkgExportFqn))
        {
            exportFqns.add(contentFqn);
        }
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, exportFqns);
        return buildModifiedResult(normFqn, result.applied, persisted,
            new MdNameNormalizer.Report(false));
    }

    /**
     * The write-transaction body for {@link #modifyXdtoMember}: re-fetches the XDTOPackage, materializes
     * its content, records the content's own export FQN (captured by {@link XdtoWriter#resolvePackageContent}
     * itself - NEVER re-derived here via a post-attach {@code bmGetFqn()}, which throws on a just-attached,
     * not-yet-settled object; a live-stand-caught regression) into {@code contentFqnHolder}, resolves the
     * target ObjectType / Property (which must ALREADY exist), and applies {@code properties} via
     * {@link XdtoWriter}. Throws {@link XdtoWriteException} (a ready JSON error) on a resolution /
     * validation failure, rolling the whole write back.
     */
    private static XdtoWriter.Result modifyXdtoMemberInTx(IBmTransaction tx, long pkgBmId,
        ITopObjectFqnGenerator fqnGenerator, XdtoWriter.MemberRef ref, List<JsonObject> properties,
        String[] contentFqnHolder)
    {
        Object inTx = tx.getObjectById(pkgBmId);
        if (!(inTx instanceof XDTOPackage))
        {
            throw new XdtoWriteException(ToolResult.error("The XDTO package could not be resolved " //$NON-NLS-1$
                + "inside the transaction.").toJson()); //$NON-NLS-1$
        }
        XDTOPackage txPkg = (XDTOPackage)inTx;
        XdtoWriter.ContentResolution resolved = XdtoWriter.resolvePackageContent(txPkg, tx, fqnGenerator);
        if (resolved.error != null)
        {
            throw new XdtoWriteException(resolved.error);
        }
        Package content = resolved.content;
        contentFqnHolder[0] = resolved.contentFqn;

        XdtoWriter.Result applied;
        if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE)
        {
            ObjectType type = XdtoWriter.findObjectType(content, ref.objectTypeName);
            if (type == null)
            {
                throw new XdtoWriteException(xdtoObjectTypeNotFoundError(ref));
            }
            applied = XdtoWriter.applyObjectTypeProperties(type, properties);
        }
        else
        {
            EList<Property> owner = content.getProperties();
            if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE_PROPERTY)
            {
                ObjectType type = XdtoWriter.findObjectType(content, ref.objectTypeName);
                if (type == null)
                {
                    throw new XdtoWriteException(xdtoObjectTypeNotFoundError(ref));
                }
                owner = type.getProperties();
            }
            Property property = XdtoWriter.findProperty(owner, ref.propertyName);
            if (property == null)
            {
                throw new XdtoWriteException(xdtoPropertyNotFoundError(ref));
            }
            applied = XdtoWriter.applyPropertyProperties(property, content, properties, false);
        }
        if (applied.hasError())
        {
            throw new XdtoWriteException(applied.error);
        }
        return applied;
    }

    /** The actionable "ObjectType not found" error, shared by the modify/delete XDTO branches. */
    static String xdtoObjectTypeNotFoundError(XdtoWriter.MemberRef ref)
    {
        return ToolResult.error("ObjectType not found: '" + ref.objectTypeName + "' in package " //$NON-NLS-1$ //$NON-NLS-2$
            + ref.packageFqn + ". Use get_metadata_details on the package FQN to list its object types.") //$NON-NLS-1$
                .toJson();
    }

    /** The actionable "Property not found" error, shared by the modify/delete XDTO branches. */
    static String xdtoPropertyNotFoundError(XdtoWriter.MemberRef ref)
    {
        String owner = ref.kind == XdtoWriter.Kind.OBJECT_TYPE_PROPERTY
            ? ref.packageFqn + ".ObjectType." + ref.objectTypeName //$NON-NLS-1$
            : ref.packageFqn;
        return ToolResult.error("Property not found: '" + ref.propertyName + "' on " + owner //$NON-NLS-1$ //$NON-NLS-2$
            + ". Use get_metadata_details on the package FQN to list its properties.").toJson(); //$NON-NLS-1$
    }

    /**
     * The resolved modify target (built by {@link #resolveModifyTarget}): the resolved metadata node
     * plus the FQN to use downstream (the yo-normalized form when the fallback resolved), or a ready
     * JSON {@link #error} when the node was not found. Exactly one of {@code node} / {@code error}
     * is non-null.
     */
    static final class ResolvedTarget
    {
        /** A ready {@link ToolResult#error} JSON when the node was not found, else {@code null}. */
        final String error;
        /** The resolved node (with a non-null {@code object}), or {@code null} on error. */
        final MetadataNodeResolver.MetadataNode node;
        /** The FQN to use downstream, or {@code null} on error. */
        final String normFqn;

        private ResolvedTarget(String error, MetadataNodeResolver.MetadataNode node, String normFqn)
        {
            this.error = error;
            this.node = node;
            this.normFqn = normFqn;
        }

        static ResolvedTarget notFound(String error)
        {
            return new ResolvedTarget(error, null, null);
        }

        static ResolvedTarget of(MetadataNodeResolver.MetadataNode node, String normFqn)
        {
            return new ResolvedTarget(null, node, normFqn);
        }
    }

    /**
     * Resolves the modify target with the exact-first / yo-fallback strategy (create_metadata
     * normalizes 'yo'->'ye' in names by default, so the resolver retries the normalized FQN when the
     * exact one misses). Returns a {@link ResolvedTarget} carrying the resolved node + the FQN to
     * use downstream, or a ready JSON {@link ResolvedTarget#error} when the node does not exist.
     * Extracted verbatim from {@link #executeOnUiThread}.
     */
    /** Ends a refusal that is decided before anything is written. */
    private static final String NOTHING_CHANGED = " Nothing was changed."; //$NON-NLS-1$

    /**
     * The shared refusal of a malformed subsystem chain request ({@link SubsystemUtils#malformedChainError}),
     * as this tool words it, or {@code null}. Asked before the project context is resolved, so the
     * tolerant vendor-support lookup never runs on the trimmed reading of such an address, and again
     * by {@link #resolveModifyTarget}. Package-visible for tests.
     */
    static String malformedChainRefusal(String normFqn)
    {
        String malformed = SubsystemUtils.malformedChainError(normFqn);
        return malformed == null ? null : ToolResult.error(malformed + NOTHING_CHANGED).toJson();
    }

    static ResolvedTarget resolveModifyTarget(MetadataScope scope, String fqn, String normFqn)
    {
        // A NESTED subsystem is a separate top object its parent only refers to, so the containment
        // grammar below has no step to it (issue #897): it is resolved by its chain, through the same
        // shared descent create / delete / get_metadata_details use, and refused by the subsystem texts.
        // A subsystem chain request that is no well-formed chain is refused by the shared rule, never
        // resolved by its trimmed reading or answered by the generic "not found".
        String malformed = malformedChainRefusal(normFqn);
        if (malformed != null)
        {
            return ResolvedTarget.notFound(malformed);
        }
        boolean nested = SubsystemUtils.nestedChain(normFqn) != null;
        MetadataNodeResolver.ResolvedNode resolved = nested
            ? MetadataNodeResolver.resolveNestedSubsystem(scope, normFqn, true)
            : MetadataNodeResolver.resolveExistingWithYoFallback(scope, normFqn);
        MetadataNodeResolver.MetadataNode node = resolved.node;
        if (node == null || node.object == null)
        {
            return ResolvedTarget.notFound(ToolResult.error(nested
                ? SubsystemUtils.notFoundMessage(fqn) + MetadataNodeResolver.yoNotFoundHint(normFqn)
                    + scope.addressingHint(normFqn)
                : nodeNotFoundMessage(scope, fqn, normFqn)).toJson());
        }
        if (resolved.yoFallback)
        {
            Activator.logInfo("modify_metadata: '" + normFqn //$NON-NLS-1$
                + "' did not resolve exactly; proceeding with its yo-normalized form '" //$NON-NLS-1$
                + resolved.fqn + "'"); //$NON-NLS-1$
            normFqn = resolved.fqn;
        }
        // A nested subsystem goes on under its canonical chain, whatever language the tokens were
        // typed in: the reply and the routing downstream name it the way EDT and list_subsystems do.
        return ResolvedTarget.of(node, nested ? resolved.fqn : normFqn);
    }

    /**
     * The refusal of a modify target that resolves to no node: the address forms this tool takes - the
     * configuration root's own among them (issue #741) - and where to find an FQN. Package-visible for
     * tests.
     *
     * @param scope the project's resolution root
     * @param fqn the address as the caller wrote it
     * @param normFqn the normalized address
     * @return the message
     */
    static String nodeNotFoundMessage(MetadataScope scope, String fqn, String normFqn)
    {
        return "Node not found: " + fqn + ". Use 'Type.Name' for a top object, " //$NON-NLS-1$ //$NON-NLS-2$
            + "'Type.Name.Kind.Name' for a member, or '" + ConfigurationRoot.FQN + "' for the " //$NON-NLS-1$ //$NON-NLS-2$
            + "configuration root. Use get_metadata_objects to find an FQN." //$NON-NLS-1$
            + (normFqn != null && normFqn.startsWith("ExternalDataSource.") //$NON-NLS-1$
                ? " External data source members use 'ExternalDataSource.<X>.Table.<T>(.Field.<F>)' " //$NON-NLS-1$
                    + "or 'ExternalDataSource.<X>.Function.<F>'." : "") //$NON-NLS-1$ //$NON-NLS-2$
            // A short 'Subsystem.<Child>' of a subsystem that exists only nested (issue #929).
            + SubsystemUtils.nestedNamesakesHint(scope, normFqn)
            + MetadataNodeResolver.yoNotFoundHint(normFqn)
            + scope.addressingHint(normFqn);
    }

    /**
     * Dispatches the payload surfaces against the resolved target, in the fixed template -> role ->
     * membership-content order (each guard refuses its payload on a wrong-kind FQN, so a
     * sibling payload is never silently dropped). Returns the branch result, or {@code null} when no
     * payload surface applies and the generic 'properties' path should run. Extracted verbatim from
     * {@link #executeOnUiThread}.
     */
    private String dispatchPayloads(ProjectContext ctx, String normFqn, MdObject target, ModifyArgs args)
    {
        // A `template` spreadsheet-content payload on a BasicTemplate FQN is authored through the moxel
        // content surface; the same payload on a NON-template FQN is refused. Dispatched BEFORE the role /
        // content path so a template payload combined with a role / content payload is refused here (on a
        // non-template FQN) or inside modifyTemplateContent (on a template FQN, the mixing guard) - never
        // silently dropped. Returns null when there is no template payload, so the role / content /
        // generic path below still runs.
        String templatePayloadResult = dispatchTemplatePayload(ctx, normFqn, target, args.properties,
            args.content, args.hasRolePayload, args.templateSpec);
        if (templatePayloadResult != null)
        {
            return templatePayloadResult;
        }

        // A ROLE FQN carrying a role payload (rights / templates / roleProperties) is dispatched to the
        // rights writer; the same payload on a NON-Role FQN is refused. Returns null only when there is
        // no role payload, so the content / generic property path below still runs.
        String rolePayloadResult = dispatchRolePayload(ctx, normFqn, target, args.properties,
            args.rolePayloadRights, args.rolePayloadTemplates, args.roleProperties,
            args.hasRolePayload);
        if (rolePayloadResult != null)
        {
            return rolePayloadResult;
        }

        // A FQN carrying a content payload (content[]) is dispatched by the resolved object's KIND to
        // its dedicated membership writer (or refused for an unsupported kind); the branch always
        // returns.
        if (args.hasContentPayload)
        {
            return dispatchContentPayload(ctx, normFqn, target, args.properties, args.content);
        }
        return null;
    }

    /**
     * Dispatches a FORM-member FQN (item / attribute / command): the member lives on the editable Form
     * content model (a cross-model hop), not the mdclass tree, and the validation + change pipeline is
     * reused as-is. A Role payload ('rights' / 'templates' / 'roleProperties') or a membership 'content'
     * payload addressed to a FORM-member FQN is refused here, BEFORE the form dispatch: a form member is
     * neither a Role nor a membership-list owner (CommonAttribute / ExchangePlan / Catalog / Document),
     * so those siblings do not apply to it. Without this guard the form branch would apply only
     * 'properties' (or nothing) and report success while the sibling payload vanished silently. Both
     * siblings are rejected together to keep them symmetric. Extracted verbatim from
     * {@link #executeOnUiThread}.
     */
    private String dispatchFormMember(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef formRef, List<JsonObject> properties,
        MdNameNormalizer.Report normReport, boolean hasRolePayload, boolean hasContentPayload)
    {
        if (hasRolePayload || hasContentPayload)
        {
            return ToolResult.error("'" + normFqn + "' addresses a FORM member, which cannot " //$NON-NLS-1$ //$NON-NLS-2$
                + "take a Role payload ('rights' / 'templates' / 'roleProperties') or a " //$NON-NLS-1$
                + "membership 'content' payload. 'rights' / 'templates' / 'roleProperties' " //$NON-NLS-1$
                + "are valid only for a Role.<Name> FQN, and 'content' only for a " //$NON-NLS-1$
                + "CommonAttribute / ExchangePlan / Catalog / Document / Subsystem FQN. Use " //$NON-NLS-1$
                + "'properties' to change a form member.").toJson(); //$NON-NLS-1$
        }
        return modifyFormMember(ctx, normFqn, formRef, properties, normReport);
    }

    /**
     * Dispatches a ROLE payload ('rights' / 'templates' / 'roleProperties'): a Role FQN carrying the
     * payload goes to {@link #modifyRoleRights} (the access-rights surface, not the generic property
     * bag; the mutation runs through the EDT-native rights tasks + a forceExport draining the sibling
     * Rights.rights sub-resource); the same payload on a NON-Role FQN is refused (it must not fall
     * through to the generic property path, which - with an empty 'properties' - would apply nothing yet
     * report a false success and silently drop the payload). Returns {@code null} when there is NO role
     * payload, so the caller continues to the content / generic path. Extracted verbatim from
     * {@link #executeOnUiThread}.
     */
    private String dispatchRolePayload(ProjectContext ctx, String normFqn, MdObject target, // NOSONAR cohesive role-payload dispatch helper extracted verbatim; the rights/templates/properties params forward as-is to modifyRoleRights
        List<JsonObject> properties, List<JsonObject> rolePayloadRights,
        List<JsonObject> rolePayloadTemplates, JsonObject roleProperties, boolean hasRolePayload)
    {
        if (target instanceof Role && hasRolePayload)
        {
            return modifyRoleRights(ctx, normFqn, (Role)target, properties, rolePayloadRights,
                rolePayloadTemplates, roleProperties);
        }
        if (hasRolePayload)
        {
            return ToolResult.error("'rights' / 'templates' / 'roleProperties' are only valid for a " //$NON-NLS-1$
                + "Role FQN; '" + normFqn + MSG_IS_A + target.eClass().getName() + ". Use " //$NON-NLS-1$ //$NON-NLS-2$
                + "'properties' for its generic properties, or address a Role.<Name>.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Dispatches a content payload (content[]) by the resolved object's KIND to its dedicated membership
     * writer: a member of that kind's structured list (a common attribute's owner, an exchange plan's
     * content object, a catalog's owner, a document's register record) is attached / detached through
     * the list surface, not the generic property bag. Each per-kind writer runs a BM write tx + a single
     * forceExport of the resolved TOP FQN and refuses mixing the content payload with a generic
     * 'properties' change (the same policy the Role rights branch enforces). A content payload addressed
     * to an unsupported kind is refused here (it must not fall through to the generic property path,
     * which - with an empty 'properties' - would apply nothing yet report a false success and silently
     * drop the content payload). Extracted verbatim from {@link #executeOnUiThread} (entered only when a
     * content payload is present).
     */
    private String dispatchContentPayload(ProjectContext ctx, String normFqn, MdObject target,
        List<JsonObject> properties, List<JsonObject> content)
    {
        // The list of an object an extension adopted reaches its .mdo only when the extension marks it;
        // otherwise EDT's .mdo writer drops it (issues #709 / #710).
        String dropped = readPreparation(ctx.project, () -> {
            MdObject current = HostKind.current() == HostKind.HEADLESS
                ? MetadataNodeResolver.resolveAddress(preparationScope(ctx), normFqn, true) : target;
            return AdoptedPropertyRouter.refusalIfDropped(current, membershipList(current), normFqn,
                ctx.project, resolvePlatformVersion(ctx));
        });
        if (dropped != null)
        {
            return dropped;
        }
        if (target instanceof CommonAttribute)
        {
            return modifyCommonAttributeContent(ctx, normFqn, (CommonAttribute)target, properties,
                content);
        }
        if (target instanceof ExchangePlan)
        {
            return modifyExchangePlanContent(ctx, normFqn, (ExchangePlan)target, properties, content);
        }
        if (target instanceof Catalog)
        {
            return modifyCatalogOwners(ctx, normFqn, (Catalog)target, properties, content);
        }
        if (target instanceof Document)
        {
            return modifyDocumentRegisterRecords(ctx, normFqn, (Document)target, properties, content);
        }
        return ToolResult.error("'content' is only valid for a CommonAttribute, ExchangePlan, " //$NON-NLS-1$
            + "Catalog, Document or Subsystem FQN; '" + normFqn + MSG_IS_A + target.eClass().getName() //$NON-NLS-1$
            + ". Use 'properties' for its generic properties, or address a CommonAttribute.<Name> " //$NON-NLS-1$
            + "(owners), ExchangePlan.<Name> (content objects), Catalog.<Name> (owners), " //$NON-NLS-1$
            + "Document.<Name> (register records) or Subsystem.<Name> (content objects).").toJson(); //$NON-NLS-1$
    }

    /**
     * The feature the membership {@code content} payload edits on {@code target}: a common attribute's or an
     * exchange plan's content, a catalog's owners, a document's register records, a subsystem's content.
     *
     * @param target the resolved object
     * @return the feature, or {@code null} for another kind
     */
    static EStructuralFeature membershipList(MdObject target)
    {
        if (target instanceof CommonAttribute)
        {
            return MdClassPackage.Literals.COMMON_ATTRIBUTE__CONTENT;
        }
        if (target instanceof ExchangePlan)
        {
            return MdClassPackage.Literals.EXCHANGE_PLAN__CONTENT;
        }
        if (target instanceof Catalog)
        {
            return MdClassPackage.Literals.CATALOG__OWNERS;
        }
        if (target instanceof Document)
        {
            return MdClassPackage.Literals.DOCUMENT__REGISTER_RECORDS;
        }
        return target instanceof Subsystem ? MdClassPackage.Literals.SUBSYSTEM__CONTENT : null;
    }

    /**
     * Dispatches a {@code template} spreadsheet-content payload: a {@link BasicTemplate} FQN (a common
     * template {@code CommonTemplate.<Name>} or an object-owned template
     * {@code <Type>.<Owner>.Template.<Name>}) carrying the payload goes to {@link #modifyTemplateContent}
     * (the SpreadsheetDocument content surface, not the generic property bag); the same payload on a
     * NON-template FQN is refused (it must not fall through to the role / content / generic property path,
     * which - with an empty {@code properties} - would apply nothing yet report a false success and
     * silently drop the payload). Returns {@code null} when there is NO template payload, so the caller
     * continues to the role / content / generic path. Entered only after the resolver has produced the
     * target object.
     */
    private String dispatchTemplatePayload(ProjectContext ctx, String normFqn, MdObject target,
        List<JsonObject> properties, List<JsonObject> content, boolean hasRolePayload,
        JsonObject templateSpec)
    {
        // The payload presence is derivable from the spec itself (java:S107: 8 -> 7 params).
        boolean hasTemplatePayload = templateSpec != null;
        if (target instanceof BasicTemplate && hasTemplatePayload)
        {
            return modifyTemplateContent(ctx, normFqn, (BasicTemplate)target, properties, content,
                hasRolePayload, templateSpec);
        }
        if (hasTemplatePayload)
        {
            return templateOnlyForTemplateFqnError(normFqn, ERR_IS_A + target.eClass().getName());
        }
        return null;
    }

    /**
     * Authors TextDocument file text or SpreadsheetDocument cells through the template payload.
     * Rejects mixed payloads and type mismatches before writing, then rechecks the model in the
     * BM write transaction. Spreadsheet content has its own export FQN; file-backed text uses the
     * shared initializer and a file journal so a failed BM transaction restores its original bytes.
     */
    private String modifyTemplateContent(ProjectContext ctx, String normFqn, BasicTemplate template,
        List<JsonObject> properties, List<JsonObject> content, boolean hasRolePayload,
        JsonObject templateSpec)
    {
        String mixError = templateMixError(properties, content, hasRolePayload);
        if (mixError != null)
        {
            return mixError;
        }

        TemplateWriteContext writeCtx = readPreparation(ctx.project, () -> {
            ProjectContext current = HostKind.current() == HostKind.HEADLESS
                ? resolveProjectAndScope(ctx.project.getName(), normFqn) : ctx;
            BasicTemplate currentTemplate = HostKind.current() == HostKind.HEADLESS
                ? (BasicTemplate)MetadataNodeResolver.resolveAddress(current.scope, normFqn, true) : template;
            String error = currentTemplate == null ? ToolResult.error("Template no longer exists: " + normFqn
                + ". Re-read get_metadata_details before retrying.").toJson()
                : TemplateContentSupport.payloadError(currentTemplate, templateSpec, normFqn);
            boolean text = currentTemplate != null && currentTemplate.getTemplateType() == TemplateType.TEXT_DOCUMENT;
            if (error == null && !text) error = nonSpreadsheetTemplateError(currentTemplate, normFqn);
            if (error != null)
            {
                TemplateWriteContext refused = new TemplateWriteContext();
                refused.error = error;
                return refused;
            }
            TemplateWriteContext plan = resolveTemplateWriteContext(current, currentTemplate, normFqn, !text);
            plan.text = text;
            return plan;
        });
        if (writeCtx.error != null)
        {
            return writeCtx.error;
        }

        if (writeCtx.text) return modifyTextTemplateContent(ctx, normFqn, writeCtx, templateSpec);

        // Captured inside the write: the moxel content's OWN canonical FQN (a template's content IS a
        // distinct top BM object once attached - pre-existing on disk, or freshly attached below), so it is
        // force-exported alongside the template so the sibling .mxlx drains to disk.
        final String[] contentFqnHolder = {null};
        SpreadsheetTemplateWriter.Result result;
        try
        {
            result = BmTransactions.write(writeCtx.bmModel, "ModifyTemplateContent", //$NON-NLS-1$
                (tx, pm) -> applyTemplateSpec(tx, writeCtx, normFqn, templateSpec, contentFqnHolder));
        }
        catch (TemplateWriteException e)
        {
            return e.getErrorJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error modifying template content", e); //$NON-NLS-1$
            return ToolResult.error("Failed to modify template content: " //$NON-NLS-1$
                + unwrapCauseMessage(e)).toJson();
        }

        // The spreadsheet content serializes to the template's sibling .mxlx resource under the
        // template's OWN top-object .mdo, so force-exporting the template's canonical FQN drains it. If
        // EDT instead models that content as a distinct top BM object (the same shape as a Role's separate
        // Rights.rights sub-resource, which modifyRoleRights exports by its own FQN), the template FQN
        // alone would NOT drain it - so export the content resource's OWN FQN too, guarding against the
        // #239-class silent-false-success (persisted=true while the authored cells never reach disk).
        List<String> exportFqns = new ArrayList<>();
        exportFqns.add(writeCtx.exportFqn);
        String contentFqn = contentFqnHolder[0];
        if (contentFqn != null && !contentFqn.equals(writeCtx.exportFqn))
        {
            exportFqns.add(contentFqn);
        }
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, exportFqns);
        return buildTemplateResult(normFqn, result, persisted);
    }

    /** Write the file-backed text in the same BM boundary used for spreadsheet content. */
    private String modifyTextTemplateContent(ProjectContext ctx, String normFqn, TemplateWriteContext writeCtx,
        JsonObject templateSpec)
    {
        if (writeCtx.error != null)
        {
            return writeCtx.error;
        }
        final String hash;
        try (TemplateContentSupport.Context files = new TemplateContentSupport.Context(ctx.project))
        {
            hash = BmTransactions.write(writeCtx.bmModel, "ModifyTextTemplateContent", (tx, monitor) ->
            {
                Object object = tx.getObjectById(writeCtx.templateBmId);
                if (!(object instanceof BasicTemplate))
                {
                    throw new TemplateWriteException(ToolResult.error("Template '" + normFqn
                        + "' no longer exists; re-read get_metadata_details.").toJson());
                }
                BasicTemplate current = (BasicTemplate)object;
                String locked = vendorSupportRefusal(current, transactionScope(ctx, tx), normFqn, normFqn, "changed"); //$NON-NLS-1$
                if (locked != null)
                {
                    throw new TemplateWriteException(locked);
                }
                EObject owner = current.eContainer();
                if (owner instanceof MdObject && !(owner instanceof Configuration))
                {
                    locked = vendorSupportRefusal(owner, transactionScope(ctx, tx), writeCtx.exportFqn, normFqn, "changed"); //$NON-NLS-1$
                    if (locked != null)
                    {
                        throw new TemplateWriteException(locked);
                    }
                }
                String error = TemplateContentSupport.payloadError(current, templateSpec, normFqn);
                if (error != null)
                {
                    throw new TemplateWriteException(error);
                }
                EObject currentContent = current.getTemplate();
                if (!(currentContent instanceof FileAwareTextDocument)
                    && !TemplateContentSupport.isPlaceholder(currentContent))
                {
                    throw new TemplateWriteException(ToolResult.error("Template '" + normFqn //$NON-NLS-1$
                        + "' has incompatible content. Create a separate TextDocument template with create_metadata.") //$NON-NLS-1$
                        .toJson());
                }
                IFile file = TemplateContentSupport.contentFile(ctx.project, current);
                TemplateContentSupport.TextWriteResult written =
                    TemplateContentSupport.writeTextResult(current, file, templateSpec, files);
                if (written.error() != null)
                {
                    throw new TemplateWriteException(written.error());
                }
                TemplateContentSupport.initialize(current, TemplateType.TEXT_DOCUMENT, tx,
                    writeCtx.dtProject, writeCtx.fqnGenerator, file);
                return written.contentHash();
            });
            files.commit();
        }
        catch (TemplateWriteException error)
        {
            return error.getErrorJson();
        }
        catch (Exception error)
        {
            Refusals.log("Error modifying text template", error);
            return ToolResult.error("Failed to modify template text: " + unwrapCauseMessage(error)).toJson();
        }
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, writeCtx.exportFqn);
        JsonObject applied = new JsonObject();
        applied.addProperty("contentHash", hash);
        return ToolResult.success().put(McpKeys.ACTION, VAL_MODIFIED).put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(normFqn))
            .put(KEY_TEMPLATE, applied).put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Modified text template " + MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)).toJson();
    }

    /** The final templateType in a property batch, or null when it does not change this property. */
    private static TemplateType requestedTemplateType(List<PreparedChange> changes)
    {
        TemplateType result = null;
        for (PreparedChange change : changes)
        {
            if (change.feature() == MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE_TYPE)
            {
                result = (TemplateType)change.scalarValue;
            }
        }
        return result;
    }

    /**
     * Everything the template-content write boundary needs (built by
     * {@link #resolveTemplateWriteContext}): the template's BM id for the in-tx re-fetch, the TOP
     * object's canonical FQN to force-export, the BM model, the (nullable) {@link IDtProject}
     * driving the canonical languageSettings block, and the external-property FQN generator (only
     * required for spreadsheet content). When
     * {@link #error} is non-null (a ready JSON error), the other fields must not be used.
     */
    private static final class TemplateWriteContext
    {
        boolean text;
        /** A ready {@link ToolResult#error} JSON when a service / export target is missing, else {@code null}. */
        String error;
        /** The template's stable BM id, re-fetched inside the write tx. */
        long templateBmId;
        /** The TOP object's canonical (all-English) FQN to force-export after the commit. */
        String exportFqn;
        IBmModel bmModel;
        /** Nullable: a null project still yields a templateMode=true document. */
        IDtProject dtProject;
        ITopObjectFqnGenerator fqnGenerator;
        /**
         * The project's 1C:Enterprise runtime version (the DT project manifest's {@code Runtime-Version},
         * which is what the moxel serializer gates its version-dependent members on). Nullable: a version
         * that cannot be resolved skips the version check rather than refusing a legal write.
         */
        Version projectVersion;
    }

    /**
     * Resolves everything the template-content write needs up front: the template's BM id, the TOP
     * object's canonical FQN to force-export, the project's BM model, the (nullable)
     * {@link IDtProject} and, when {@code attachSpreadsheet} is true, the external-property FQN
     * generator needed to attach spreadsheet content. Returns a
     * {@link TemplateWriteContext} whose non-null {@code error} (a ready JSON error) reports a
     * missing service / unresolvable export target. Extracted verbatim from
     * {@link #modifyTemplateContent}.
     */
    private static TemplateWriteContext resolveTemplateWriteContext(ProjectContext ctx,
        BasicTemplate template, String normFqn, boolean attachSpreadsheet)
    {
        TemplateWriteContext writeCtx = new TemplateWriteContext();

        // A common template (Template.X / CommonTemplate.X) is its OWN top BM object; an object-owned
        // template (Catalog.Y.Template.Z) is INLINE in its owner's .mdo, so it is NOT a top object. Its
        // stable BM id still re-fetches inside the tx (getObjectById resolves any managed object, not only
        // top ones), but the force-export target must be the TOP object's canonical (all-English) FQN - the
        // template itself when it is top, else the OWNER top object (bmGetTopObject) whose .mdo + sibling
        // .mxlx carry the content. Mirrors RoleRightsWriter's top climb - self when already top, else
        // bmGetTopObject. A bmGetFqn read is legal only on a top object, so it happens on `topObject`, never on a non-top
        // template. A null top (should not happen for a resolved template) fails LOUD, nothing written.
        IBmObject templateBm = (IBmObject)template;
        writeCtx.templateBmId = templateBm.bmGetId();
        IBmObject topObject = templateBm.bmIsTop() ? templateBm : templateBm.bmGetTopObject();
        if (topObject == null)
        {
            writeCtx.error = ToolResult.error("Cannot resolve the on-disk file to export for template '" + normFqn //$NON-NLS-1$
                + "': its owning top-level object could not be found; report it with the template FQN.") //$NON-NLS-1$
                    .toJson();
            return writeCtx;
        }
        writeCtx.exportFqn = topObject.bmGetFqn();

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            writeCtx.error = ToolResult.error(ERR_NO_BM_MANAGER).toJson();
            return writeCtx;
        }
        writeCtx.bmModel = BmModelResolver.resolve(ctx.project, bmModelManager).getModel();
        if (writeCtx.bmModel == null)
        {
            writeCtx.error = ToolResult.error(ERR_NO_BM_MODEL
                + ctx.project.getName()).toJson();
            return writeCtx;
        }

        // Drives the canonical (project-aware) <languageSettings> block when an EMPTY template's content
        // is materialized inside the tx (a freshly created template has getTemplate()==null). Resolved
        // best-effort with the SAME manager the force-export below uses; a null project still yields a
        // templateMode=true document (only the languageSettings block is skipped, which the platform's
        // moxel reader null-guards). Carried on the write context captured by the write lambda.
        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        writeCtx.dtProject =
            dtProjectManager == null ? null : dtProjectManager.getDtProject(ctx.project);

        // The moxel serializer gates autoWidthCalculation / widthWeightFactor on the project's RUNTIME
        // version (the DT manifest's Runtime-Version, which is exactly what IV8Project.getVersion()
        // returns) - not on the configuration's compatibilityMode. Below 8.3.10 it writes neither, so the
        // writer refuses them there instead of reporting success for a dropped value.
        writeCtx.projectVersion = com.ditrix.edt.mcp.server.utils.ProjectContext.platformVersion(ctx.project);

        // The moxel content is a transient @ExternalProperty of the template - its own .mxlx resource, NOT
        // an inline BM reference. A freshly-materialized content doc must be ATTACHED as a BM top object
        // under its generated external-property FQN (the same machinery FormElementWriter uses for a form's
        // content), else committing the write fails with "Failed to persist reference value". Carried on
        // the write context captured by the write lambda.
        if (attachSpreadsheet)
        {
            writeCtx.fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();
            if (writeCtx.fqnGenerator == null)
            {
                writeCtx.error = ToolResult.error("ITopObjectFqnGenerator not available").toJson(); //$NON-NLS-1$
                return writeCtx;
            }
        }
        return writeCtx;
    }

    /**
     * The template-content write-transaction body: re-fetches the template by its BM id, resolves
     * (or materializes + attaches) its content {@link SpreadsheetDocument}, records the content
     * resource's OWN export FQN into {@code contentFqnHolder}, and applies the payload via
     * {@link SpreadsheetTemplateWriter}. Throws a {@link TemplateWriteException} carrying a ready
     * JSON error on a resolution / validation failure, so the surrounding tx rolls back with no
     * partial mutation. Extracted verbatim from the write lambda of {@link #modifyTemplateContent}.
     */
    private static SpreadsheetTemplateWriter.Result applyTemplateSpec(IBmTransaction tx,
        TemplateWriteContext writeCtx, String normFqn, JsonObject templateSpec,
        String[] contentFqnHolder)
    {
        Object inTx = tx.getObjectById(writeCtx.templateBmId);
        if (!(inTx instanceof BasicTemplate))
        {
            throw new TemplateWriteException(ToolResult.error("The template could not be " //$NON-NLS-1$
                + "resolved inside the transaction.").toJson());
        }
        BasicTemplate txTemplate = (BasicTemplate)inTx;
        String payloadError = TemplateContentSupport.payloadError(txTemplate, templateSpec, normFqn);
        if (payloadError == null)
        {
            payloadError = nonSpreadsheetTemplateError(txTemplate, normFqn);
        }
        if (payloadError != null)
        {
            throw new TemplateWriteException(payloadError);
        }
        SpreadsheetDocument doc =
            resolveSpreadsheetContent(txTemplate, tx, writeCtx.dtProject, writeCtx.fqnGenerator, normFqn);
        // The content doc is now an attached BM top object (pre-existing on disk, or freshly
        // materialized + attachTopObject'd inside resolveSpreadsheetContent), so its own resource
        // FQN resolves and is force-exported alongside the template so the sibling .mxlx drains.
        contentFqnHolder[0] = contentResourceExportFqn(doc);
        SpreadsheetTemplateWriter.Result applied =
            SpreadsheetTemplateWriter.apply(doc, templateSpec, writeCtx.projectVersion);
        if (applied.hasError())
        {
            // Roll the whole write back so a validation failure leaves nothing on disk.
            throw new TemplateWriteException(applied.error);
        }
        return applied;
    }

    /**
     * A template's external-property moxel {@link SpreadsheetDocument}'s OWN canonical top-object FQN
     * when EDT models it as a DISTINCT top BM object (so force-exporting the template's FQN alone would
     * NOT drain the sibling {@code .mxlx}, the same shape as a Role's separate
     * {@code Rights.rights} sub-resource), else
     * {@code null} when the content is a contained child that the template's own export already serializes
     * (the export list is then unchanged). MUST run inside the write boundary:
     * {@code bmGetFqn()} is legal only on a top object, so the call is guarded by {@code bmIsTop()}.
     */
    private static String contentResourceExportFqn(EObject content)
    {
        if (content instanceof IBmObject)
        {
            IBmObject contentBm = (IBmObject)content;
            if (contentBm.bmIsTop())
            {
                return contentBm.bmGetFqn();
            }
        }
        return null;
    }

    /**
     * The up-front refusal for a {@code template} payload aimed at a real {@link BasicTemplate} whose type
     * is NOT {@link TemplateType#SPREADSHEET_DOCUMENT} (a text / binary-data / DCS / graphical template
     * cannot host cells): a ready {@link ToolResult#error} JSON naming the FQN and the template's ACTUAL
     * type (or {@code "unset"} for a null type - no NPE), pointing at the only template kind {@code template}
     * can author. Returns {@code null} when the template IS a SpreadsheetDocument (the write may proceed).
     * Pure (no model mutation, no transaction) so the guard is unit-testable headlessly; called fail-fast
     * before any BM write, mirrored inside the tx by {@link #resolveSpreadsheetContent}'s content re-check.
     */
    static String nonSpreadsheetTemplateError(BasicTemplate template, String normFqn)
    {
        if (template.getTemplateType() == TemplateType.SPREADSHEET_DOCUMENT)
        {
            return null;
        }
        String actual = template.getTemplateType() == null
            ? "unset" : template.getTemplateType().getName(); //$NON-NLS-1$
        return ToolResult.error("Template '" + normFqn + "' is not a SpreadsheetDocument template " //$NON-NLS-1$ //$NON-NLS-2$
            + "(its type is '" + actual + "'). Only a SpreadsheetDocument (print form / макет) " //$NON-NLS-1$ //$NON-NLS-2$
            + "template can be authored with 'template'.").toJson(); //$NON-NLS-1$
    }

    /**
     * Reuses spreadsheet content or initializes an empty placeholder through EDT's creation helper.
     * Real foreign content is refused. Must run inside the BM write boundary.
     */
    private static SpreadsheetDocument resolveSpreadsheetContent(BasicTemplate txTemplate, IBmTransaction tx,
        IDtProject dtProject, ITopObjectFqnGenerator fqnGenerator, String normFqn)
    {
        EObject contentObj = txTemplate.getTemplate();
        if (contentObj instanceof SpreadsheetDocument)
        {
            return (SpreadsheetDocument)contentObj;
        }
        if (!TemplateContentSupport.isPlaceholder(contentObj))
        {
            throw new TemplateWriteException(ToolResult.error("Template '" + normFqn
                + "' has incompatible content. Create a separate SpreadsheetDocument template with create_metadata.")
                .toJson());
        }
        TemplateContentSupport.initialize(txTemplate, TemplateType.SPREADSHEET_DOCUMENT,
            tx, dtProject, fqnGenerator, null);
        return (SpreadsheetDocument)txTemplate.getTemplate();
    }

    /**
     * Builds the success JSON for a completed template content change: the {@code template} counts object
     * ({@code cells} / {@code merges} / {@code areas} / {@code columnWidths} / {@code rowHeights}) plus
     * {@code persisted} and a confirmation message. Pure helper.
     */
    private static String buildTemplateResult(String normFqn, SpreadsheetTemplateWriter.Result result,
        boolean persisted)
    {
        JsonObject applied = new JsonObject();
        applied.addProperty("cells", result.cells); //$NON-NLS-1$
        applied.addProperty("merges", result.merges); //$NON-NLS-1$
        applied.addProperty("areas", result.areas); //$NON-NLS-1$
        applied.addProperty("columnWidths", result.columnWidths); //$NON-NLS-1$
        applied.addProperty("rowHeights", result.rowHeights); //$NON-NLS-1$
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)) //$NON-NLS-1$
            .put(KEY_TEMPLATE, applied)
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Modified template " + MetadataTypeUtils.toCanonicalEnglishFqn(normFqn) + " content (cells: " + result.cells //$NON-NLS-1$ //$NON-NLS-2$
                + ", merges: " + result.merges + ", areas: " + result.areas + ", columnWidths: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + result.columnWidths + ", rowHeights: " + result.rowHeights + ")") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * The actionable error for a {@code template} payload addressed to a FQN that is not a
     * SpreadsheetDocument template (a form member, a subsystem, or any other non-template object): names
     * the offending FQN + what it is, and points at the valid template FQN shapes. {@code isClause}
     * describes the resolved target (e.g. {@code "is a Catalog"} or {@code "addresses a FORM member"}).
     * Package-visible for tests.
     */
    static String templateOnlyForTemplateFqnError(String normFqn, String isClause)
    {
        return ToolResult.error("'template' is only valid for a SpreadsheetDocument template FQN (a " //$NON-NLS-1$
            + "common template 'CommonTemplate.<Name>' or an object-owned template " //$NON-NLS-1$
            + "'<Type>.<Owner>.Template.<Name>'); '" + normFqn + "' " + isClause + ". 'template' " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "authors a spreadsheet template's cells / merges / areas; use 'properties' for a generic " //$NON-NLS-1$
            + "property change, or address a template FQN.").toJson(); //$NON-NLS-1$
    }

    /**
     * The refusal for a {@code template} payload combined with another payload in the same call: a
     * generic {@code properties} change, a membership {@code content} payload or a Role payload
     * ({@code rights} / {@code templates} / {@code roleProperties}). A template's content is authored
     * through its own dedicated surface, so mixing is rejected up front - the same no-mixing policy the
     * Role rights / membership content branches enforce, so a sibling payload is never silently dropped
     * while the tool reports success. Returns the ready JSON error, or {@code null} when the
     * {@code template} payload stands alone. Package-visible for tests.
     */
    static String templateMixError(List<JsonObject> properties, List<JsonObject> content,
        boolean hasRolePayload)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("A template content change ('template') cannot be combined with a " //$NON-NLS-1$
                + "generic 'properties' change in one call. Set the template's own properties " //$NON-NLS-1$
                + "(comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }
        if (!content.isEmpty() || hasRolePayload)
        {
            return ToolResult.error("A template content change ('template') cannot be combined with a " //$NON-NLS-1$
                + "membership 'content' payload or a Role payload ('rights' / 'templates' / " //$NON-NLS-1$
                + "'roleProperties') in one call. 'template' authors a SpreadsheetDocument template's " //$NON-NLS-1$
                + "cells / merges / areas only.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Carries a ready JSON error out of the template write transaction (a validation / resolution failure)
     * so {@link #modifyTemplateContent} returns it verbatim AND the throw rolls the write back (no partial
     * mutation persists). Unchecked so it crosses the BM task boundary; the message is a validated
     * {@link ToolResult#error} JSON string. Mirrors {@code ReferenceMembershipWriter.ContentWriteException}.
     */
    private static final class TemplateWriteException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        private final transient String errorJson;

        TemplateWriteException(String errorJson)
        {
            super(errorJson);
            this.errorJson = errorJson;
        }

        String getErrorJson()
        {
            return errorJson;
        }
    }

    /**
     * Parses the optional {@code template} argument (a single JSON object - the spreadsheet content spec)
     * from the raw params into a {@link TemplateArg}: {@link TemplateArg#absent()} when the argument is
     * absent / blank / JSON null; a ready {@link TemplateArg#invalid} error when it is present but is NOT a
     * JSON object (unparseable, or a string / number / array). Unlike {@link #parseRolePropertiesArg}
     * (whose sibling {@code rights} / {@code templates} arrays still drive a role change if it is
     * malformed), {@code template} is the SOLE surface for the template-authoring feature, so a
     * present-but-malformed value must be an actionable error, NOT a silent drop that would apply a stray
     * {@code properties} - or misreport {@code properties is required} - while the authoring vanished. An
     * invalid INNER shape of a well-formed object is surfaced later by {@link SpreadsheetTemplateWriter}'s
     * validation. Package-visible for tests.
     */
    static TemplateArg parseTemplateArg(Map<String, String> params)
    {
        String raw = params.get(KEY_TEMPLATE);
        if (raw == null || raw.trim().isEmpty())
        {
            return TemplateArg.absent();
        }
        JsonElement element;
        try
        {
            element = JsonParser.parseString(raw.trim());
        }
        catch (RuntimeException e)
        {
            return TemplateArg.invalid(malformedTemplateError());
        }
        if (element.isJsonNull())
        {
            return TemplateArg.absent();
        }
        if (!element.isJsonObject())
        {
            return TemplateArg.invalid(malformedTemplateError());
        }
        return TemplateArg.of(element.getAsJsonObject());
    }

    /**
     * The actionable error for a present-but-malformed {@code template} argument (unparseable JSON, or a
     * string / number / array rather than an object): the {@code template} payload authors a
     * TextDocument or SpreadsheetDocument template's content, so it must be a JSON object.
     */
    private static String malformedTemplateError()
    {
        return ToolResult.error("'template' must be a JSON object, e.g. " //$NON-NLS-1$
            + "{cells:[{row:0,col:0,text:'Total'}]}. It authors a SpreadsheetDocument template's cells / " //$NON-NLS-1$
            + "merges / areas / column & row sizes on a template FQN. For a TextDocument use " //$NON-NLS-1$
            + "{text:'...'} with optional expectedHash from get_metadata_details.").toJson(); //$NON-NLS-1$
    }

    /**
     * The parsed {@code template} argument: {@link #absent()} (no payload - both fields {@code null}), a
     * valid parsed {@link #spec}, or a ready {@link #error} JSON for a present-but-malformed value. At most
     * one of {@code spec} / {@code error} is non-null. Package-visible for tests.
     */
    static final class TemplateArg
    {
        /** The parsed content spec, or {@code null} when the argument is absent or malformed. */
        final JsonObject spec;
        /** A ready {@link ToolResult#error} JSON when the argument is present-but-malformed, else {@code null}. */
        final String error;

        private TemplateArg(JsonObject spec, String error)
        {
            this.spec = spec;
            this.error = error;
        }

        static TemplateArg absent()
        {
            return new TemplateArg(null, null);
        }

        static TemplateArg of(JsonObject spec)
        {
            return new TemplateArg(spec, null);
        }

        static TemplateArg invalid(String error)
        {
            return new TemplateArg(null, error);
        }
    }

    /**
     * Applies a generic 'properties' change to the resolved node through the BM write boundary (the
     * remaining case once the form / role / content branches are ruled out): resolves the BM re-fetch
     * strategy and the platform version, validates + prepares every change (fail fast, no partial
     * mutation), runs the destructive-consent gate for a retype, then applies the changes inside ONE BM
     * write transaction and force-exports the TOP object. Extracted verbatim from
     * {@link #executeOnUiThread}; the reject conditions, the consent-gate call and the force-export are
     * unchanged.
     */
    private String applyGenericPropertyChanges(ProjectContext ctx, String projectName, String normFqn,
        MetadataNodeResolver.MetadataNode node, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
    {
        return prepareWrite(ctx.project, () -> {
            ProjectContext current = HostKind.current() == HostKind.HEADLESS
                ? resolveProjectAndScope(projectName, normFqn) : ctx;
            if (current.hasError()) return () -> current.error;
            MetadataNodeResolver.MetadataNode currentNode = HostKind.current() == HostKind.HEADLESS
                ? resolveGenericWriteNode(current.scope, normFqn) : node;
            if (currentNode == null) return () -> ToolResult.error("Node no longer exists: " + normFqn).toJson();
            return prepareGenericPropertyChanges(current, projectName, normFqn, currentNode,
                currentNode.object, properties, normReport);
        });
    }

    static MetadataNodeResolver.MetadataNode resolveGenericWriteNode(MetadataScope scope, String fqn)
    {
        var root = ConfigurationRoot.resolve(scope, fqn);
        if (root != null)
        {
            return root.node;
        }
        // An owned form's metadata object, which the child grammar below has no step to (issue #995).
        if (FormElementWriter.parseFormObjectCreate(fqn) != null)
        {
            return MetadataNodeResolver.formNode(scope, FormElementWriter.parseFormPath(fqn));
        }
        // The address resolveModifyTarget handed on: a nested subsystem's canonical chain (issue #897).
        return SubsystemUtils.nestedChain(fqn) != null
            ? MetadataNodeResolver.resolveNestedSubsystem(scope, fqn, false).node
            : MetadataNodeResolver.resolveExisting(scope, fqn);
    }

    private java.util.function.Supplier<String> prepareGenericPropertyChanges(ProjectContext ctx, String projectName, String normFqn,
        MetadataNodeResolver.MetadataNode node, MdObject target, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
    {
        Configuration config = ctx.config;

        // Resolve the BM re-fetch strategy (mutation must re-fetch inside the write tx). Only TOP
        // objects are re-fetchable by bmId, so for a member we re-fetch the TOP object and
        // re-navigate to the leaf's owner BY NAME inside the tx - this is what lets a member of a
        // NESTED object (e.g. a tabular-section attribute) be modified, not just a direct member.
        final String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        BmFetchPlan plan = resolveBmFetchPlan(ctx.scope, node, target, parts);
        if (plan.error != null)
        {
            return () -> plan.error;
        }
        final long topBmId = plan.topBmId;
        final EStructuralFeature memberFeature = plan.memberFeature;
        final String memberName = plan.memberName;

        // The platform version is needed only to build a 'type' value; resolve it best-effort (a
        // missing version is reported only if a 'type' property is actually set).
        final Version version = resolvePlatformVersion(ctx);

        // Validate every property against the introspected schema BEFORE any write (fail fast, no
        // partial mutation). On success, collect the prepared changes to apply inside the tx. Computed
        // once so an unresolved 'type' reference can append the extension-adopt hint (issue #262).
        boolean isExtensionProject = ExtensionOriginUtils.isExtensionProject(ctx.project);
        List<PreparedChange> changes = new ArrayList<>();
        String prepErr = validateAndPrepare(ctx.project, ctx.scope, config, version, target, properties, changes,
            normReport, isExtensionProject, this::buildTypeDescription);
        if (prepErr != null)
        {
            return () -> prepErr;
        }

        // A change of an object an extension ADOPTED goes where EDT keeps it - its extension block - or
        // refuses the call, before the consent gate and the write (issues #709 / #710).
        AdoptedPropertyRouter.Routing routing = routeAdopted(normFqn, ctx.project, ctx.scope, config, version,
            target, changes, AdoptedPropertyRouter::route);
        if (routing.isRefused())
        {
            return routing::refusal;
        }
        if (changes.isEmpty() && !routing.notes().isEmpty())
        {
            // Every change of the call leaves the object as it is: nothing to write, nothing to export -
            // a finding, published as an empty writtenProjects (#408).
            WriteScope.recordNothingQueued();
            return () -> buildModifiedResult(normFqn, List.of(), false, normReport, List.of(), null,
                TypeChangeDefaults.Reset.NONE, routing.notes());
        }

        // Ask the human before RETYPING an attribute (the destructive case): the preview is built from
        // the already-prepared changes, so a benign edit never prompts. On REJECT the tool returns the
        // error and mutates nothing; the gate itself is a no-op headless / when env or preference allows.
        // Then the base value a routed write of an adopted object was planned from is read again (#709/#710).
        // The top object that owns the node's .mdo file.
        final String[] fileOwnerFqn = { null };
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return () -> ToolResult.error(ERR_NO_BM_MANAGER).toJson();
        }
        IBmModel bmModel = BmModelResolver.resolve(ctx.project, bmModelManager).getModel();
        if (bmModel == null)
        {
            return () -> ToolResult.error(ERR_NO_BM_MODEL + projectName).toJson();
        }

        final List<String> applied = appliedFeatureNames(changes);
        // An XDTOPackage's target namespace lives in TWO synced places: the mdclass 'namespace' (this
        // .mdo edit) and the content Package's targetNamespace (the sibling .xdto). The generic path
        // exports only the .mdo, so when the namespace changes, re-sync the content (XdtoWriter treats
        // the owner as the single source of truth) and force-export the .xdto too - else members would be
        // authored under a stale namespace once the content is eagerly materialized at package-create (a
        // live-stand-caught drift).
        final boolean xdtoNamespaceChange =
            applied.contains(MdClassPackage.Literals.XDTO_PACKAGE__NAMESPACE.getName());
        final ITopObjectFqnGenerator fqnGenerator =
            xdtoNamespaceChange ? Activator.getDefault().getTopObjectFqnGenerator() : null;
        if (xdtoNamespaceChange && fqnGenerator == null)
        {
            // Fail BEFORE mutating: without the generator we cannot keep the content .xdto
            // targetNamespace in sync, and committing only the .mdo would silently reintroduce the
            // namespace drift while reporting success. Nothing is written here.
            return () -> ToolResult.error("Cannot change the XDTO package namespace right now: the " //$NON-NLS-1$
                + "ITopObjectFqnGenerator service is unavailable, so the content resource (.xdto) " //$NON-NLS-1$
                + "target namespace cannot be kept in sync. Retry once EDT has finished " //$NON-NLS-1$
                + "initializing.").toJson(); //$NON-NLS-1$
        }
        // Needed only for the namespace CASCADE below (maintainer request: a namespace change on package
        // P must not leave OTHER packages that import P's OLD namespace, or reference one of P's types via
        // a QName carrying the OLD nsUri, dangling - renaming one package must not silently break a
        // DIFFERENT, unrelated package). A Configuration BM id, captured OUTSIDE the write tx and
        // re-fetched INSIDE it, mirrors CreateMetadataTool.createTopLevel's configBmId precedent.
        final long configBmId =
            xdtoNamespaceChange && config instanceof IBmObject ? ((IBmObject)config).bmGetId() : -1L;
        final String[] contentFqnHolder = { null };
        // Read OUTSIDE the write transaction (a plain configuration read, like the language
        // resolution the prepare step already did); only the per-object present locales are
        // collected inside. Issue #298.
        final List<String> declaredCodes = ctx.scope.declaredOrOverride(
            declaredCodesAfterBatch(config, target, properties));
        final List<String> externalLocales = externalLocalesForWrite(ctx, declaredCodes);
        final LocalizedWriteReport localizedReport = new LocalizedWriteReport();
        final List<String> cascadedPackageNames = new ArrayList<>();
        final List<String> cascadedExportFqns = new ArrayList<>();
        // What a type write reset besides the type (issue #700), for the confirmation message.
        final TypeChangeDefaults.Reset[] typeReset = { TypeChangeDefaults.Reset.NONE };
        final TemplateType requestedTemplateType = requestedTemplateType(changes);

        return () -> {
            String consentErr = consentThenBaseCheck(normFqn, changes, routing);
            if (consentErr != null)
            {
                return consentErr;
            }

            try (TemplateContentSupport.Context templates = new TemplateContentSupport.Context(ctx.project))
            {
                BmTransactions.<Void>write(bmModel, "ModifyMetadata", (tx, pm) -> //$NON-NLS-1$
                {
                    EObject applyTo = resolveApplyTarget(tx, topBmId, memberFeature, memberName, parts);
                    fileOwnerFqn[0] = ReferencedTopObjects.fileOwnerFqn(applyTo);
                    // Captured BEFORE the prepared changes are applied (only meaningful for an XDTOPackage
                    // namespace change - null otherwise, so the cascade below never fires).
                    final String oldNamespace = (xdtoNamespaceChange && applyTo instanceof XDTOPackage)
                        ? ((XDTOPackage)applyTo).getNamespace() : null;
                    localizedReport.rememberPreState(applyTo, changes);
                    if (applyTo instanceof BasicTemplate && requestedTemplateType != null)
                    {
                        AdoptedPropertyRouter.requireStillWritten(applyTo,
                            MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE_TYPE, null, normFqn);
                        contentFqnHolder[0] = templates.retype((BasicTemplate)applyTo, requestedTemplateType, tx);
                    }
                    typeReset[0] = applyChanges(applyTo, changes, tx, normFqn);
                    localizedReport.collectWithLocales(applyTo, changes, declaredCodes,
                        localesInUseDuringWrite(ctx, tx, declaredCodes, externalLocales));
                    if (fqnGenerator != null && applyTo instanceof XDTOPackage)
                    {
                        XDTOPackage changedPkg = (XDTOPackage)applyTo;
                        // Captured BEFORE resolvePackageContent: ensureNamespace re-syncs a STALE content
                        // targetNamespace to the owner, and QNames still carrying that stale value must be
                        // rewritten too (not only the old mdclass namespace).
                        com._1c.g5.v8.dt.xdto.model.Package preContent = changedPkg.getPackage();
                        String preContentNs = (preContent instanceof IBmObject
                            && ((IBmObject)preContent).bmIsTop()) ? preContent.getNsUri() : null;
                        XdtoWriter.ContentResolution resolved =
                            XdtoWriter.resolvePackageContent(changedPkg, tx, fqnGenerator);
                        if (resolved.error != null)
                        {
                            // Abort + roll back the whole modify rather than committing only the .mdo and
                            // leaving the content .xdto target namespace stale (a silent partial success).
                            // Mirrors modifyXdtoMemberInTx.
                            throw new XdtoWriteException(resolved.error);
                        }
                        contentFqnHolder[0] = resolved.contentFqn;

                        String newNamespace = changedPkg.getNamespace();
                        if (oldNamespace != null && !oldNamespace.equals(newNamespace))
                        {
                            // The renamed package's OWN content can hold SAME-PACKAGE references (a property
                            // typed at a sibling ObjectType carries a QName with the package's own nsUri) -
                            // rewrite those too, or the package would dangle against ITSELF right after the
                            // rename. Its content FQN is already force-exported via contentFqnHolder.
                            XdtoWriter.rewriteNamespaceReferences(resolved.content, oldNamespace, newNamespace);
                            if (preContentNs != null && !preContentNs.equals(oldNamespace)
                                && !preContentNs.equals(newNamespace))
                            {
                                // The content targetNamespace was stale before this edit: QNames written
                                // under THAT value and naming THIS package's own local types would stay
                                // dangling after the rename. Targeted rewrite only (a genuine reference
                                // to a third package whose namespace equals the stale value is kept).
                                XdtoWriter.rewriteStaleSelfReferences(resolved.content, preContentNs, newNamespace);
                            }
                            cascadeNamespaceChange(tx, configBmId, changedPkg, oldNamespace, newNamespace,
                                fqnGenerator, cascadedPackageNames, cascadedExportFqns);
                        }
                    }
                    return null;
                });
                templates.commit();
            }
            catch (Exception e)
            {
                String ready = XdtoWriteException.jsonOf(e);
                if (ready != null)
                {
                    return ready;
                }
                Refusals.log("Error modifying metadata", e); //$NON-NLS-1$
                return ToolResult.error("Failed to modify: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
            }

            List<String> exportFqns = new ArrayList<>();
            exportFqns.add(fileOwnerFqn[0]);
            String contentFqn = contentFqnHolder[0];
            if (contentFqn != null && !contentFqn.equals(fileOwnerFqn[0]))
            {
                exportFqns.add(contentFqn);
            }
            for (String cascadedFqn : cascadedExportFqns)
            {
                if (!exportFqns.contains(cascadedFqn))
                {
                    exportFqns.add(cascadedFqn);
                }
            }
            boolean persisted = BmTransactions.forceExportToDisk(ctx.project, exportFqns);

            List<String> adoptedNotes = new ArrayList<>(routing.notes());
            for (PreparedChange change : changes)
            {
                String note = change.routedNote();
                if (note != null)
                {
                    adoptedNotes.add(note);
                }
            }
            return buildModifiedResult(normFqn, applied, persisted, normReport, cascadedPackageNames,
                localizedReport, typeReset[0], adoptedNotes);
        };
    }

    /**
     * Routes the prepared batch of an object an extension ADOPTED (issues #709 / #710) and rewrites it in
     * place: a defined type's {@code type} or an event subscription's {@code source} that EDT keeps in the
     * extension block becomes the write of that block ({@link PreparedChange#typeExtension} /
     * {@link PreparedChange#extendedSource}); a change that leaves the object as it is, or a type write a
     * later one of the same call supersedes, is removed; a change EDT's {@code .mdo} writer would drop refuses
     * the whole call - see {@link AdoptedPropertyRouter}. The batch of any other object comes back untouched.
     * Package-visible: the headless tests pass a router over their own platform.
     *
     * @param normFqn the FQN the call addresses
     * @param project the project
     * @param scope its resolution root
     * @param config its configuration
     * @param version its runtime version, or {@code null}
     * @param target the object the batch is written to
     * @param changes the prepared batch, rewritten in place unless the call is refused
     * @param router the router
     * @return the routing; nothing may be written when it is refused
     */
    static AdoptedPropertyRouter.Routing routeAdopted(String normFqn, IProject project, MetadataScope scope, // NOSONAR the call's context is passed through to the router as is
        Configuration config, Version version, MdObject target, List<PreparedChange> changes,
        Function<AdoptedPropertyRouter.Request, AdoptedPropertyRouter.Routing> router)
    {
        List<AdoptedPropertyRouter.Change> requested = new ArrayList<>();
        for (PreparedChange change : changes)
        {
            requested.add(new AdoptedPropertyRouter.Change(change.feature(), change.value(),
                change.bindingFeature()));
        }
        AdoptedPropertyRouter.Routing routing = router.apply(
            new AdoptedPropertyRouter.Request(target, normFqn, project, scope, config, version, requested));
        if (routing.isRefused())
        {
            return routing;
        }
        List<PreparedChange> routed = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++)
        {
            AdoptedPropertyRouter.Decision decision = routing.decision(i);
            switch (decision.kind())
            {
                case DROP:
                    break;
                case TYPE_EXTENSION:
                    routed.add(PreparedChange.typeExtension(changes.get(i).feature(), decision.typePlan()));
                    break;
                case EXTENDED_SOURCE:
                    routed.add(PreparedChange.extendedSource(changes.get(i).feature(), decision.sourcePlan()));
                    break;
                case PLAIN:
                default:
                    routed.add(changes.get(i));
                    break;
            }
        }
        changes.clear();
        changes.addAll(routed);
        return routing;
    }

    /**
     * Cascades an XDTOPackage namespace change to every OTHER XDTOPackage of the same configuration
     * (maintainer request: changing package P's namespace must not silently break a SIBLING package that
     * imports P's OLD namespace or references one of P's types via a QName carrying the OLD nsUri - the
     * maintainer's exact complaint was that renaming one package breaks a DIFFERENT, unrelated package,
     * not the one being renamed). Runs INSIDE the same write transaction that applied the target
     * package's own namespace change, so the whole modify commits or rolls back together. A sibling
     * package whose content cannot be resolved (e.g. it has no namespace of its own set yet - see
     * {@link XdtoWriter#resolvePackageContent}) is SKIPPED, not treated as a failure of this modify: a
     * sibling package's own unrelated problem is not this call's business. Appends the FQN of every
     * REWRITTEN package (its own top-object FQN and its content's own resource FQN) to
     * {@code exportFqnsOut}, and the package's Name to {@code cascadedNamesOut}, for the caller's
     * force-export list and confirmation message.
     *
     * @param configBmId the Configuration's BM id, captured OUTSIDE this tx (-1 when unavailable, in
     *            which case this is a no-op - the top-level guard above already required it whenever
     *            {@code xdtoNamespaceChange} is true, so -1 here would mean the Configuration was not a
     *            BM object at all)
     */
    private static void cascadeNamespaceChange(IBmTransaction tx, long configBmId, XDTOPackage changedPkg,
        String oldNamespace, String newNamespace, ITopObjectFqnGenerator fqnGenerator,
        List<String> cascadedNamesOut, List<String> exportFqnsOut)
    {
        if (configBmId < 0)
        {
            return;
        }
        Object cfgObj = tx.getObjectById(configBmId);
        if (!(cfgObj instanceof Configuration))
        {
            return;
        }
        Configuration cfg = (Configuration)cfgObj;
        long changedPkgBmId = ((IBmObject)changedPkg).bmGetId();
        List<XDTOPackage> siblings = new ArrayList<>();
        for (XDTOPackage other : cfg.getXDTOPackages())
        {
            if (other instanceof IBmObject && ((IBmObject)other).bmGetId() != changedPkgBmId)
            {
                siblings.add(other);
            }
        }
        // Vendor support (#642), before any sibling is touched: a locked sibling that references the
        // old namespace refuses the whole modify (the throw rolls back the target's own change too).
        List<String> locked = lockedReferrers(siblings, oldNamespace, ModifyMetadataTool::attachedContent,
            VendorSupportGuard::allowsEdit);
        if (!locked.isEmpty())
        {
            throw new XdtoWriteException(ToolResult.error(VendorSupportGuard.cascadeRefusal(
                "XDTOPackage." + changedPkg.getName(), "changed", //$NON-NLS-1$ //$NON-NLS-2$
                "its namespace change would rewrite the XDTO packages that reference '" + oldNamespace //$NON-NLS-1$
                    + "':", locked)).toJson()); //$NON-NLS-1$
        }
        for (XDTOPackage other : siblings)
        {
            if (attachedContent(other) != null && !VendorSupportGuard.allowsEdit(other))
            {
                // Locked and not referencing the old namespace: not even the self-repair below.
                continue;
            }
            // A sibling with NO attached content cannot reference any namespace - skip it WITHOUT
            // resolvePackageContent, which would otherwise MATERIALIZE + attach a fresh empty content
            // for an unrelated package as a committed-but-unexported side effect of this modify.
            com._1c.g5.v8.dt.xdto.model.Package existingContent = other.getPackage();
            if (!(existingContent instanceof IBmObject) || !((IBmObject)existingContent).bmIsTop())
            {
                continue;
            }
            // resolvePackageContent may REPAIR this sibling as a side effect (ensureNamespace
            // re-syncs a stale content targetNamespace to its own owner) - capture the pre-state so
            // a repaired-but-not-rewritten sibling is still force-exported (an in-memory change that
            // never reaches disk would leave BM and Package.xdto inconsistent).
            String siblingPreNs = existingContent.getNsUri();
            XdtoWriter.ContentResolution resolved = XdtoWriter.resolvePackageContent(other, tx, fqnGenerator);
            if (resolved.error != null)
            {
                // Best-effort: a sibling package with no usable content of its own (e.g. no namespace set
                // yet) is skipped, not a failure of THIS modify.
                continue;
            }
            boolean repaired = siblingPreNs == null || !siblingPreNs.equals(other.getNamespace());
            if (repaired && siblingPreNs != null)
            {
                // FIRST restore the repaired sibling's SELF-consistency - but ONLY for QNames whose
                // local name matches one of the sibling's OWN local types (the disambiguation): a
                // genuine reference into another package whose namespace equals the stale value
                // (e.g. the renamed package's old namespace) must stay for the cascade rewrite
                // below, and imports are never self-imports, so they are not touched here.
                XdtoWriter.rewriteStaleSelfReferences(resolved.content, siblingPreNs, other.getNamespace());
            }
            boolean rewritten = XdtoWriter.rewriteNamespaceReferences(resolved.content, oldNamespace, newNamespace);
            if (!rewritten && !repaired)
            {
                continue;
            }
            exportFqnsOut.add("XDTOPackage." + other.getName()); //$NON-NLS-1$
            if (resolved.contentFqn != null)
            {
                exportFqnsOut.add(resolved.contentFqn);
            }
            if (rewritten)
            {
                cascadedNamesOut.add(other.getName());
            }
        }
    }

    /**
     * The locked siblings a namespace cascade would rewrite: those whose content references
     * {@code oldNamespace} and that {@code editable} does not allow changing. Read-only.
     *
     * @param siblings the other XDTO packages of the configuration
     * @param oldNamespace the namespace being replaced
     * @param contentOf a sibling's attached content, or {@code null} when it has none
     * @param editable whether a package may be changed
     * @return the FQNs of the locked referrers, in configuration order
     */
    static List<String> lockedReferrers(List<XDTOPackage> siblings, String oldNamespace,
        Function<XDTOPackage, com._1c.g5.v8.dt.xdto.model.Package> contentOf,
        Predicate<XDTOPackage> editable)
    {
        List<String> locked = new ArrayList<>();
        for (XDTOPackage other : siblings)
        {
            com._1c.g5.v8.dt.xdto.model.Package content = contentOf.apply(other);
            if (content != null && XdtoWriter.referencesNamespace(content, oldNamespace) && !editable.test(other))
            {
                locked.add("XDTOPackage." + other.getName()); //$NON-NLS-1$
            }
        }
        return locked;
    }

    /** A package's content when it is attached as a top object (what the cascade may rewrite). */
    private static com._1c.g5.v8.dt.xdto.model.Package attachedContent(XDTOPackage pkg)
    {
        com._1c.g5.v8.dt.xdto.model.Package content = pkg.getPackage();
        return content instanceof IBmObject && ((IBmObject)content).bmIsTop() ? content : null;
    }

    /**
     * Resolves the platform {@link Version} for the project best-effort (used only to build a 'type'
     * value): {@code null} when the V8 project manager or project is unavailable. Side-effect-free
     * helper extracted from {@link #executeOnUiThread}.
     */
    private static Version resolvePlatformVersion(ProjectContext ctx)
    {
        return com.ditrix.edt.mcp.server.utils.ProjectContext.platformVersion(ctx.project);
    }

    /**
     * Re-navigates to the EObject that the prepared changes must be applied to, INSIDE the write
     * transaction: re-fetches the TOP object by {@code topBmId} and, for a member, re-navigates to
     * the leaf's owner and then to the leaf BY NAME. Read-only resolution (no eSet) - the actual
     * mutation stays in the caller's apply loop. Throws the SAME {@link RuntimeException}s as before
     * (target / owner / member not found), which propagate to the same catch and roll the tx back.
     * Extracted verbatim from {@link #executeOnUiThread}'s transaction body.
     */
    private static EObject resolveApplyTarget(IBmTransaction tx, long topBmId,
        EStructuralFeature memberFeature, String memberName, String[] parts)
    {
        EObject top = (EObject)tx.getObjectById(topBmId);
        if (top == null)
        {
            throw new RuntimeException("Target not found in transaction"); //$NON-NLS-1$ // NOSONAR propagates checked exceptions across the reflective boundary by design
        }
        if (memberFeature == null)
        {
            return top;
        }
        EObject owner = MetadataNodeResolver.resolveOwnerInTx(top, parts);
        if (owner == null)
        {
            throw new RuntimeException("Could not re-navigate to the owner inside the transaction"); //$NON-NLS-1$ // NOSONAR propagates checked exceptions across the reflective boundary by design
        }
        EObject applyTo = childByName(owner, memberFeature, memberName);
        if (applyTo == null)
        {
            throw new RuntimeException("Member not found in transaction: " + memberName); //$NON-NLS-1$ // NOSONAR propagates checked exceptions across the reflective boundary by design
        }
        return applyTo;
    }

    /**
     * Collects the feature names of the applied changes, in order. Pure helper extracted from
     * {@link #executeOnUiThread}.
     */
    private static List<String> appliedFeatureNames(List<PreparedChange> changes)
    {
        List<String> applied = new ArrayList<>();
        for (PreparedChange change : changes)
        {
            applied.add(change.featureName());
        }
        return applied;
    }

    /**
     * The predefined-item write inside its transaction: first checks again, on the owner read inside the
     * transaction, that EDT's {@code .mdo} writer still keeps the owner's predefined items
     * ({@link AdoptedPropertyRouter#requireStillWritten}). The guard before the consent gate of a retype judged
     * it, and the extension state of 'predefined' can be set to None while the call waits there; written
     * anyway, the item would not reach the file while the call reports it written (#709 / #710). A
     * package-visible seam, so the check is pinned headless.
     *
     * @param txOwner the owner, read inside the write transaction
     * @param itemName the predefined item's name
     * @param props the parsed item properties
     * @param ownerFqn the owner's FQN as the call addresses it, for a refusal
     * @return the writer's result
     * @throws IllegalStateException when the owner's predefined items would now be dropped (rolls back)
     */
    static PredefinedWriter.WriteResult modifyPredefinedInWrite(EObject txOwner, String itemName,
        PredefinedWriter.ItemProps props, String ownerFqn)
    {
        AdoptedPropertyRouter.requireStillWritten(txOwner, txOwner.eClass().getEStructuralFeature("predefined"), //$NON-NLS-1$
            null, ownerFqn);
        return PredefinedWriter.modify(txOwner, itemName, props);
    }

    /**
     * The predefined-item counterpart of {@link #consentForTypeChanges}: a
     * {@code ChartOfCharacteristicTypes} predefined item's {@code valueType} is a real RETYPE (or a
     * clear), so it must pass the same destructive-consent gate an ordinary attribute retype does,
     * before the write transaction opens.
     *
     * @param normFqn the predefined item's normalized FQN
     * @return a ready {@link ToolResult#error} JSON payload when consent was not granted, else
     *         {@code null}
     */
    private static String consentForPredefinedRetype(String normFqn)
    {
        ConsentPreview preview = new ConsentPreview(
            "Change the data type of " + normFqn, //$NON-NLS-1$
            "Retyping stored data can drop existing values on the next database update.", //$NON-NLS-1$
            1, List.of(PROP_VALUE_TYPE));
        ConsentDecision consentDecision = DestructiveConsentGate.getInstance().requireConsent(NAME, preview);
        if (consentDecision != ConsentDecision.ALLOW)
        {
            return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(consentDecision, NAME)).toJson();
        }
        return null;
    }

    /**
     * The tail of a form "not found" message: the kind-mismatch advice when there is one (issue
     * #343), otherwise the branch's own generic pointer. Keeps every form miss in this tool phrased
     * the same way - name the kind that found nothing, then either explain what DOES bear the name or
     * point at {@code get_metadata_details}.
     *
     * @param advice the advice from {@code FormElementWriter} (never {@code null}, possibly empty)
     * @param fallback the generic tail to use when there is no advice
     * @return the tail to append
     */
    private static String advisedOr(String advice, String fallback)
    {
        return advice.isEmpty() ? fallback : advice;
    }

    /**
     * Runs the destructive-consent gate for a modify BEFORE the mutation, but ONLY when at least one
     * prepared change RETYPES data (a {@code TYPE_DESCRIPTION} / form {@code valueType} set): retyping an
     * attribute can drop stored values on the next database update, so it is the destructive case a plain
     * property edit is not. A benign change list skips the gate entirely (no prompt, byte-identical path).
     *
     * <p>The gate itself decides whether to block on a UI dialog (env / headless / preference-driven — see
     * {@link DestructiveConsentGate}); this method just supplies the object FQN + the retyped features as
     * the preview. Returns a ready JSON error when the human REJECTS, or when nobody answers within the
     * gate's bounded wait (TIMEOUT — see {@link DestructiveConsentGate#consentDeniedMessage}) - the
     * caller returns it and mutates NOTHING - or {@code null} to proceed.</p>
     */
    private String consentForTypeChanges(String normFqn, List<PreparedChange> changes)
    {
        ConsentPreview preview = typeChangePreview(normFqn, changes);
        if (preview == null)
        {
            return null;
        }
        ConsentDecision consentDecision = consentRequester.request(NAME, preview);
        if (consentDecision != ConsentDecision.ALLOW)
        {
            return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(consentDecision, NAME)).toJson();
        }
        return null;
    }

    /**
     * The last step before the write transaction of the generic path: the consent gate
     * ({@link #consentForTypeChanges}), then - once the call may go on - the base value each routed write of an
     * adopted object was planned from is read again ({@link AdoptedPropertyRouter.Routing#baseChangedRefusal}):
     * the gate can hold the call, and the base configuration is another project, edited independently. In this
     * order, so a denied call reads nothing and the base is read as late as it can be. Package-visible: a test
     * pins the order and the refusal (issues #709 / #710).
     *
     * @param normFqn the FQN the call addresses
     * @param changes the routed batch
     * @param routing its routing
     * @return a ready JSON error - the consent refusal, or a changed base - or {@code null} to write
     */
    String consentThenBaseCheck(String normFqn, List<PreparedChange> changes, AdoptedPropertyRouter.Routing routing)
    {
        String consentErr = consentForTypeChanges(normFqn, changes);
        if (consentErr != null)
        {
            return consentErr;
        }
        return routing.baseChangedRefusal();
    }

    /**
     * The consent preview of the type changes of a batch, or {@code null} when it has none. Package-visible:
     * a test pins the wording of a routed write of an adopted object (issues #709 / #710).
     */
    static ConsentPreview typeChangePreview(String normFqn, List<PreparedChange> changes)
    {
        List<String> retyped = new ArrayList<>();
        List<String> routedWarnings = new ArrayList<>();
        for (PreparedChange change : changes)
        {
            if (change.isTypeChange())
            {
                retyped.add(change.featureName());
                String warning = change.routedConsentWarning();
                if (warning != null)
                {
                    routedWarnings.add(warning);
                }
            }
        }
        if (retyped.isEmpty())
        {
            return null;
        }
        // A routed write of an adopted object (issues #709 / #710) adds types or sources in the extension
        // block: the preview says what it adds and removes - the retype warning would be untrue for a pure
        // addition.
        return routedWarnings.size() == retyped.size()
            ? new ConsentPreview("Change adopted " + normFqn + " in the extension", //$NON-NLS-1$ //$NON-NLS-2$
                String.join(" ", routedWarnings), retyped.size(), retyped) //$NON-NLS-1$
            : new ConsentPreview(
                "Change the data type of " + normFqn, //$NON-NLS-1$
                "Retyping stored data can drop existing values on the next database update.", //$NON-NLS-1$
                retyped.size(), retyped);
    }

    /**
     * Whether this form-member modify RETYPES it: a {@code type} / {@code valueType} property on an
     * Attribute (or on one of its Columns, which is retyped exactly like an attribute - skipping it
     * would let a column silently change type unprompted, issue #295). Scoped to those two kinds so a
     * decoration's benign enum {@code type} never prompts, mirroring the ATTRIBUTE guard the
     * dynamic-list-query branch uses. Reads only the request (no model access).
     *
     * <p>This is the cheap PRE-FILTER deciding whether the model is worth opening at all, not the
     * verdict: a request may name {@code valueType} and still leave the member's type exactly as it
     * was. Whether anything is really retyped is answered by {@link #retypesMember} against the
     * prepared batch, inside the pre-check's read transaction (issue #599).</p>
     *
     * @param ref the parsed form-member ref
     * @param properties the requested property changes
     * @return {@code true} when the request WRITES the member's data type
     */
    private static boolean isFormRetypeRequest(FormElementWriter.FormMemberRef ref,
        List<JsonObject> properties)
    {
        FormElementWriter.Kind typedKind = FormElementWriter.kindForToken(ref.kindToken);
        if (typedKind != FormElementWriter.Kind.ATTRIBUTE && typedKind != FormElementWriter.Kind.COLUMN)
        {
            return false;
        }
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if ("type".equalsIgnoreCase(name) || PROP_VALUE_TYPE.equalsIgnoreCase(name)) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    /** The project's platform version (the type payload is built for it), or {@code null}. */
    private static Version platformVersionOf(ProjectContext ctx)
    {
        return com.ditrix.edt.mcp.server.utils.ProjectContext.platformVersion(ctx.project);
    }

    /**
     * What the user authorizes when a form attribute (or column) changes its data type. BOTH facts
     * come from the pre-check, never from the request: a batch may name {@code valueType} and leave
     * the type untouched (issue #599), and a {@code main} write may take nothing with it - a dialog
     * that names a loss which will not happen teaches the reader to ignore it.
     *
     * @param retype whether the batch really changes the member's data type
     * @param extInfoLoss whether the batch really empties the form root's ext-info
     */
    static ConsentPreview formRetypePreview(String normFqn,
        FormElementWriter.FormMemberRef ref, boolean retype, boolean extInfoLoss)
    {
        if (retype && extInfoLoss)
        {
            // One batch, two different losses - the dialog has to name both, or the answer
            // authorizes something the question never mentioned.
            return new ConsentPreview(
                "Change the data type of " + normFqn + " and rewrite its main flag", //$NON-NLS-1$ //$NON-NLS-2$
                "Retyping a form attribute can drop stored values on the next database update, " //$NON-NLS-1$
                    + "and the main flag leaves the form without its root ext-info, deleting the " //$NON-NLS-1$
                    + "event handlers bound inside it.", //$NON-NLS-1$
                2, List.of(PROP_VALUE_TYPE, PROP_MAIN));
        }
        if (extInfoLoss)
        {
            // The only other thing this gate authorizes: a main-flag write that leaves the form
            // root without its ext-info, and the event handlers bound inside it with it.
            return new ConsentPreview(
                "Remove the root ext-info of " + ref.formPath, //$NON-NLS-1$
                "Writing the main flag leaves this form without a root ext-info, deleting the " //$NON-NLS-1$
                    + "event handlers bound inside it.", //$NON-NLS-1$
                1, java.util.Collections.singletonList(PROP_MAIN));
        }
        // Only the retype is at stake: the main flag either was not written, or writing it takes
        // nothing with it (an empty node, or a kind change that carries its handlers over).
        return new ConsentPreview(
            "Change the data type of " + normFqn, //$NON-NLS-1$
            "Retyping a form attribute can drop stored values on the next database update.", //$NON-NLS-1$
            1, java.util.Collections.singletonList(PROP_VALUE_TYPE));
    }

    /**
     * The {@code main} value this request writes on an ATTRIBUTE, or {@code null} when it writes
     * none. Reads only the request; whether that write actually destroys anything is a question
     * for the model, answered in {@link #formRetypePreflight}.
     */
    private static Boolean requestedMainFlag(FormElementWriter.FormMemberRef ref,
        List<JsonObject> properties)
    {
        if (FormElementWriter.kindForToken(ref.kindToken) != FormElementWriter.Kind.ATTRIBUTE)
        {
            return null; // NOSONAR tri-state: null means "this request writes no main flag"
        }
        return mainFlagIn(properties);
    }

    /**
     * The {@code main} value a property list LEAVES on the member: the batch is applied in order,
     * so a repeated property is decided by its last write, and the gate has to judge the state the
     * model actually ends up in.
     *
     * <p>Read with the SAME parser the write uses - {@link #parseBoolean} also takes
     * {@code 1}/{@code 0}/{@code yes}/{@code no}, and a gate that recognized a narrower set would
     * miss exactly the writes it exists to catch. Package-private so a test can pin both.</p>
     *
     * @param properties the requested property changes
     * @return the value the list leaves in {@code main}, or {@code null} when it writes none (or
     *         writes something that is not a boolean at all, which the write itself refuses)
     */
    /**
     * Whether the batch flags this member main AT ANY POINT. The demotion of the OTHER attributes
     * follows from that, not from the flag the batch is left with: applied in order,
     * {@code [main=true, main=false]} promotes and takes it back, and the previous main is demoted
     * on the way through - which is what the platform's two calls would do.
     *
     * @param properties the requested property changes
     * @return {@code true} when any {@code main} write in the list is true
     */
    static boolean mainPromotedIn(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            if (PROP_MAIN.equalsIgnoreCase(asString(prop.get("name"))) //$NON-NLS-1$
                && Boolean.TRUE.equals(parseBoolean(asString(prop.get("value"))))) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    static Boolean mainFlagIn(List<JsonObject> properties)
    {
        Boolean last = null;
        for (JsonObject prop : properties)
        {
            if (PROP_MAIN.equalsIgnoreCase(asString(prop.get("name")))) //$NON-NLS-1$
            {
                last = parseBoolean(asString(prop.get("value"))); //$NON-NLS-1$
            }
        }
        return last; // NOSONAR tri-state: see above
    }

    /**
     * The form-member property branch's PRE-CHECK, run before the consent gate (see
     * {@link #gateFormRetype}): ONE read transaction decides whether a prompt is warranted at all.
     * These cases must NOT prompt, because the write is refused or unchanged in each and a denial
     * would be returned INSTEAD of the actionable error: the request retypes nothing, the member does
     * not exist (the write path answers "not found"), or ANY of the requested property changes fails
     * to validate - a retype that would strand the attribute's columns, an unbuildable {@code type}
     * payload, an unknown property, an out-of-range value. Issue #295 review.
     *
     * <p>TWO requests can reach the gate: a retype, and a {@code main} write that would leave the
     * form root without its ext-info AND take bound event handlers with it. The second is asked
     * about only in that exact shape - an empty node, or one that merely changes kind (its data is
     * carried over), loses nothing and is written without a prompt.</p>
     *
     * @param ctx the resolved project context (the configuration references resolve against)
     * @param version the platform version the type payload is built for
     * @param fctx the resolved form edit context
     * @param ref the parsed form-member ref
     * @param properties the requested property changes
     * @return a ready JSON error to return as-is, {@code ""} to write without prompting, or
     *         {@code null} to ask
     */
    private String formRetypePreflight(ProjectContext ctx, Version version, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        FormElementWriter.FormEditContext fctx, FormElementWriter.FormMemberRef ref,
        List<JsonObject> properties, MdNameNormalizer.Report normReport, boolean[] gateFlags)
    {
        final boolean writesType = isFormRetypeRequest(ref, properties);
        Boolean mainFlag = requestedMainFlag(ref, properties);
        if (!writesType && mainFlag == null)
        {
            return ""; //$NON-NLS-1$
        }
        return FormElementWriter.readEditableForm(fctx, "FormRetypePreflight", //$NON-NLS-1$
            (formModel, tx) ->
            {
                EObject member = FormElementWriter.resolveFormMember(formModel, ref);
                // The WHOLE batch is prepared first, whichever of the two brought us here: every
                // refusal that pass can produce belongs above the gate, or a denial comes back
                // instead of the actionable error (issue #295).
                List<HolderChange> prepared = new ArrayList<>();
                String refusal = formRetypeVerdict(transactionScope(ctx, tx), version, member, properties,
                    normReport, prepared);
                if (refusal != null)
                {
                    // A refusal, or "" for a member the write path answers "not found" for.
                    return refusal;
                }
                // Prepared cleanly: ask when the batch really retypes stored data - the type it
                // LEAVES, against the one the member carries (issue #599) - or when the main flag
                // it leaves would take the root ext-info and the handlers bound in it. WHICH of
                // the two it is decides what the dialog says, so both are reported back.
                gateFlags[GATE_RETYPE] = writesType && retypesMember(prepared, member);
                gateFlags[GATE_EXT_INFO_LOSS] = mainFlag != null
                    && FormElementWriter.clearsBoundFormExtInfo(formModel, member,
                        mainFlag.booleanValue(), categoryAfter(prepared, member),
                        mainPromotedIn(properties));
                return gateFlags[GATE_RETYPE] || gateFlags[GATE_EXT_INFO_LOSS] ? null : ""; //$NON-NLS-1$
            });
    }

    /** Index into the gate's flag array: the batch really changes the member's data type. */
    static final int GATE_RETYPE = 0;

    /** Index into the gate's flag array: the write really empties the form root's ext-info. */
    static final int GATE_EXT_INFO_LOSS = 1;

    /**
     * Whether the prepared batch really RETYPES {@code member}: the {@code TypeDescription} it
     * LEAVES behind differs from the one the member carries now (issue #599).
     *
     * <p>The question is about the batch's END STATE, not about any single entry, so the list is
     * folded first - {@code [valueType=String, valueType=CatalogObject]} on an attribute already
     * typed {@code CatalogObject} ends where it started and destroys nothing. That is the same
     * last-write-wins rule {@link #mainFlagIn} and {@link #categoryAfter} follow.</p>
     *
     * <p>Answering "no retype" wrongly SILENCES a destructive-consent gate, so the comparison never
     * assumes equality: {@link MetadataTypeBuilder#describesSameType} answers {@code true} only for
     * two descriptions it can read end to end, and this method asks anyway whenever it cannot find
     * the type write it was told about.</p>
     *
     * @param prepared the whole batch, already prepared against the current model
     * @param member the form member, on the tx-bound model
     * @return {@code true} when the batch leaves a different type - or when that cannot be decided
     */
    static boolean retypesMember(List<HolderChange> prepared, EObject member)
    {
        HolderChange last = lastValueTypeWrite(prepared);
        if (last == null || member == null)
        {
            // The request named a type property, yet there is no prepared type write to compare it
            // against (or no member to compare it with). Not knowing is not knowing that nothing
            // changes, and the only safe answer to not knowing here is to ask.
            return true;
        }
        EStructuralFeature feature = member.eClass().getEStructuralFeature(PROP_VALUE_TYPE);
        Object stored = feature == null ? null : member.eGet(feature);
        return !MetadataTypeBuilder.describesSameType(stored, last.change.value());
    }

    /**
     * The {@code valueType} write the batch is LEFT with, or {@code null} when it writes none. The
     * batch is applied in ORDER, so a repeated property is decided by its LAST write - the same rule
     * {@link #mainFlagIn} follows for the main flag. Single owner of that fold: both the ext-info
     * decision ({@link #categoryAfter}) and the retype verdict ({@link #retypesMember}) key on it.
     */
    private static HolderChange lastValueTypeWrite(List<HolderChange> prepared)
    {
        HolderChange last = null;
        for (HolderChange hc : prepared)
        {
            if (!hc.onExtInfo && hc.change.isTypeChange()
                && PROP_VALUE_TYPE.equalsIgnoreCase(hc.change.featureName()))
            {
                last = hc;
            }
        }
        return last;
    }

    /**
     * The property branch's pre-check verdict for an ALREADY-RESOLVED member - the body of
     * {@link #formRetypePreflight}'s read, package-private so a unit test can drive the decision
     * itself without an EDT context.
     *
     * <p>It runs the SAME preparation the write runs ({@link #prepareFormMemberChanges}) and throws
     * the result away: every refusal that pass can produce is decided by the request and the current
     * model, never by the user's answer, so ALL of it belongs above the consent gate. Doing only part
     * of it here is exactly the defect the review found twice - the stranded-columns guard was lifted
     * while the {@code type} payload stayed below the gate, so an unbuildable type still raised a
     * destructive prompt and answered a consent denial instead of "Unknown type kind".</p>
     *
     * <p>The normalization report is a THROWAWAY: the write repeats the preparation with the real
     * one, and sharing it would report every renamed name twice.</p>
     *
     * @param scope the root reference targets resolve against
     * @param version the platform version the type payload is built for
     * @param member the resolved form member, or {@code null} when it does not exist
     * @param properties the requested property changes
     * @param normReport the caller's report - only its SETTING is used, via
     *            {@link MdNameNormalizer.Report#emptyCopy()}, so this pass reports nothing twice
     * @return a ready JSON error, {@code ""} for "do not prompt", or {@code null} to ask
     */
    String formRetypeVerdict(MetadataScope scope, Version version, EObject member,
        List<JsonObject> properties, MdNameNormalizer.Report normReport)
    {
        return formRetypeVerdict(scope, version, member, properties, normReport, new ArrayList<>());
    }

    /**
     * {@link #formRetypeVerdict(MetadataScope, Version, EObject, List, MdNameNormalizer.Report)}
     * that also hands the PREPARED batch back. The pre-check has to know what the batch will leave
     * behind - a retype in the same call changes the very category the ext-info decision keys on -
     * and preparing it twice would report every normalized name twice.
     *
     * @param preparedOut filled with the prepared changes when the preparation succeeded
     */
    String formRetypeVerdict(MetadataScope scope, Version version, EObject member, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        List<JsonObject> properties, MdNameNormalizer.Report normReport,
        List<HolderChange> preparedOut)
    {
        if (member == null)
        {
            return ""; //$NON-NLS-1$
        }
        try
        {
            preparedOut.addAll(
                prepareFormMemberChanges(scope, version, member, properties, normReport.emptyCopy()));
        }
        catch (FormValidationException e)
        {
            return FormValidationException.jsonOf(e);
        }
        return null;
    }

    /**
     * The type category the member is left with: the one the batch WRITES when it retypes it,
     * otherwise the one it already carries. Read from the prepared change rather than from the
     * model, because the model still holds the old type when the pre-check runs.
     */
    static String categoryAfter(List<HolderChange> prepared, EObject member)
    {
        HolderChange last = lastValueTypeWrite(prepared);
        return last != null ? FormElementWriter.typeCategoryOf(last.change.value())
            : FormElementWriter.valueTypeCategoryOf(member);
    }

    /**
     * Builds the success JSON for a completed modify (action / fqn / applied / persisted, the
     * normalization report and the confirmation message). Pure helper extracted from
     * {@link #executeOnUiThread}; the same shape used by the form-member branch and
     * {@link #modifyXdtoMember} (neither of those ever cascades a namespace, so they use this overload).
     */
    private static String buildModifiedResult(String normFqn, List<String> applied, boolean persisted,
        MdNameNormalizer.Report normReport)
    {
        return buildModifiedResult(normFqn, applied, persisted, normReport, List.of());
    }

    /**
     * Same as the 4-arg overload, plus the namespace-CASCADE report (maintainer request): when the
     * namespace change propagated to one or more OTHER XDTOPackages (see
     * {@link #cascadeNamespaceChange}), their Names are appended to the confirmation MESSAGE text only -
     * the output schema / description are deliberately untouched (zero golden churn) - as
     * {@code "; namespace propagated to N referencing package(s): Q, R"}.
     */
    private static String buildModifiedResult(String normFqn, List<String> applied, boolean persisted,
        MdNameNormalizer.Report normReport, List<String> cascadedPackageNames)
    {
        return buildModifiedResult(normFqn, applied, persisted, normReport, cascadedPackageNames, null);
    }

    /**
     * The {@link #buildModifiedResult(String, List, boolean, MdNameNormalizer.Report, List)} variant
     * that also reports the localized write: the locale actually used and the declared locales that
     * still have no translation. Issue #298.
     */
    private static String buildModifiedResult(String normFqn, List<String> applied, boolean persisted, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        MdNameNormalizer.Report normReport, List<String> cascadedPackageNames,
        LocalizedWriteReport localizedReport)
    {
        return buildModifiedResult(normFqn, applied, persisted, normReport, cascadedPackageNames,
            localizedReport, TypeChangeDefaults.Reset.NONE);
    }

    /**
     * The full variant: also names, in the confirmation MESSAGE only, what a type change reset besides
     * the type (issue #700) - the output schema is untouched.
     */
    private static String buildModifiedResult(String normFqn, List<String> applied, boolean persisted, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        MdNameNormalizer.Report normReport, List<String> cascadedPackageNames,
        LocalizedWriteReport localizedReport, TypeChangeDefaults.Reset typeReset)
    {
        return buildModifiedResult(normFqn, applied, persisted, normReport, cascadedPackageNames,
            localizedReport, typeReset, List.of());
    }

    /**
     * The full variant: also says, in the confirmation MESSAGE only, what the writes of an adopted object
     * did in its extension block, or that they changed nothing (issues #709 / #710).
     */
    private static String buildModifiedResult(String normFqn, List<String> applied, boolean persisted, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        MdNameNormalizer.Report normReport, List<String> cascadedPackageNames,
        LocalizedWriteReport localizedReport, TypeChangeDefaults.Reset typeReset, List<String> adoptedNotes)
    {
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted);
        if (localizedReport != null)
        {
            localizedReport.addTo(result);
        }
        normReport.addTo(result);
        return result.put(McpKeys.MESSAGE,
            modifiedMessage(normFqn, applied, cascadedPackageNames, typeReset, adoptedNotes)).toJson();
    }

    /**
     * The confirmation MESSAGE of a completed modify: the applied properties, the namespace cascade when
     * there is one, and what a type change reset besides the type (issue #700). Package-visible for
     * tests.
     */
    static String modifiedMessage(String normFqn, List<String> applied, List<String> cascadedPackageNames,
        TypeChangeDefaults.Reset typeReset)
    {
        return modifiedMessage(normFqn, applied, cascadedPackageNames, typeReset, List.of());
    }

    /**
     * The confirmation MESSAGE with what the writes of an adopted object did in its extension block, or
     * that they changed nothing (issues #709 / #710): a call whose every change leaves the object as it is
     * says so instead of naming applied properties. Package-visible for tests.
     */
    static String modifiedMessage(String normFqn, List<String> applied, List<String> cascadedPackageNames,
        TypeChangeDefaults.Reset typeReset, List<String> adoptedNotes)
    {
        String message = applied.isEmpty() && !adoptedNotes.isEmpty()
            ? "Nothing was changed on " + MetadataTypeUtils.toCanonicalEnglishFqn(normFqn) //$NON-NLS-1$
            : MSG_MODIFIED_PREFIX + MetadataTypeUtils.toCanonicalEnglishFqn(normFqn) + " (" + String.join(", ", applied) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!cascadedPackageNames.isEmpty())
        {
            message += "; namespace propagated to " + cascadedPackageNames.size() //$NON-NLS-1$
                + " referencing package(s): " + String.join(", ", cascadedPackageNames); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (typeReset == TypeChangeDefaults.Reset.OWNER)
        {
            message += "; the type change also reset the properties that depend on the type, as EDT's " //$NON-NLS-1$
                + "designer does"; //$NON-NLS-1$
        }
        else if (typeReset == TypeChangeDefaults.Reset.REFERRERS)
        {
            message += "; the type change also reset the fill value of every editable object typed " //$NON-NLS-1$
                + "with it, as EDT's designer does"; //$NON-NLS-1$
        }
        if (!adoptedNotes.isEmpty())
        {
            message += "; " + String.join("; ", adoptedNotes); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return message;
    }

    /**
     * Parses the optional {@code roleProperties} argument (a single JSON object) from the raw params.
     * Returns {@code null} when the argument is absent or is not a JSON object (an invalid shape is
     * surfaced later by {@link RoleRightsWriter}'s validation). Kept separate from the array parsing
     * because {@link JsonUtils} has no single-object extractor.
     */
    private static JsonObject parseRolePropertiesArg(Map<String, String> params)
    {
        String raw = params.get(KEY_ROLE_PROPERTIES);
        if (raw == null || raw.trim().isEmpty())
        {
            return null;
        }
        try
        {
            JsonElement element = JsonParser.parseString(raw.trim());
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Modifies a ROLE's access rights (the {@code rights} / {@code templates} / {@code roleProperties}
     * payload) via {@link RoleRightsWriter}. A role is modified through its rights surface, not the
     * generic property bag, so mixing the role payload with a generic {@code properties} change in the
     * same call is refused (the same policy the move / handler / command form branches enforce). The
     * writer mutates only through the EDT-native rights tasks; this branch then force-exports BOTH the
     * Role FQN AND the {@code RoleDescription}'s own top-object FQN, OUTSIDE the writer, because the
     * rights matrix lives in its OWN BM resource ({@code Rights.rights}) that the role FQN alone does
     * not drain.
     * <p>
     * That second FQN is NOT resolved here: the writer reports it as {@link RoleRightsWriter.Result#rightsFqn},
     * produced inside the same write boundary that registered the description as a BM top object. It has
     * to come from there - a description this call has just attached has no readable {@code bmGetFqn()}
     * within its own transaction, so asking the object for its FQN afterwards returned {@code null} for
     * exactly the freshly created role that issue #452 is about.
     * <p>
     * A REFUSED apply is force-exported too - when the writer reports that it WROTE, which is a
     * different question from whether it can name the rights resource. The writer bootstraps the
     * rights model - and commits it - before it resolves the first entry, and then applies entries
     * one at a time, so a refusal raised afterwards (an unknown object, an unknown right, a failing
     * task) can leave committed work behind. The export is what drains that work, and it is also how
     * this call DECLARES the project it wrote in (issue #408: {@code WriteScope} is recorded by the
     * export submission), so returning the error without it would let a call that changed the model
     * claim it changed nothing.
     */
    private String modifyRoleRights(ProjectContext ctx, String normFqn, Role role,
        List<JsonObject> properties, List<JsonObject> rights, List<JsonObject> templates,
        JsonObject roleProperties)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("A role rights change ('rights' / 'templates' / 'roleProperties') " //$NON-NLS-1$
                + "cannot be combined with a generic 'properties' change in one call. Set the role's " //$NON-NLS-1$
                + "own properties (comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }

        RoleRightsWriter.Result result =
            HostKind.current() == HostKind.HEADLESS
                ? RoleRightsWriter.apply(ctx.project, normFqn, rights, templates, roleProperties)
                : RoleRightsWriter.apply(ctx.project, ctx.config, role, rights, templates, roleProperties);

        // The rights matrix (the Rights.rights file) is a SEPARATE BM resource from Role.mdo: the
        // RoleDescription is its own top BM object (its impl extends com._1c.g5.v8.bm.core.BmObject)
        // with its own EClass-keyed exporter (RightsExporter supports ROLE_DESCRIPTION). Exporting only
        // the role FQN drains Role.mdo but never Rights.rights, so force-export its OWN FQN too.
        // The writer carries that FQN out of the boundary that registered the description (issue #452);
        // 'persisted' stays honest: true only when the writer reported one AND the export succeeded.
        String rightsFqn = result.rightsFqn;
        List<String> exportFqns = new ArrayList<>();
        exportFqns.add(normFqn);
        if (rightsFqn != null && !rightsFqn.equals(normFqn))
        {
            exportFqns.add(rightsFqn);
        }
        if (result.hasError())
        {
            // A refusal is not the same as "nothing happened", and the two questions it raises are
            // SEPARATE. Whether to export at all is answered by rightsModelWritten - the writer says
            // whether one of its commits already landed (the bootstrap attaching the rights model, or
            // an entry applied before the failing one). What to export it UNDER is answered by
            // rightsFqn, and a missing FQN only costs the Rights.rights leg: the role FQN is still
            // submitted, because that submission is what records the project in this call's
            // WriteScope (issue #408) - without it a call that mutated the model would be declaring
            // that it changed nothing. Gating on the FQN conflated the two and skipped both the drain
            // and the declaration whenever the generator could not name an already-registered rights
            // model.
            if (result.rightsModelWritten)
            {
                BmTransactions.forceExportToDisk(ctx.project, exportFqns);
            }
            return result.error;
        }

        boolean exported = BmTransactions.forceExportToDisk(ctx.project, exportFqns);
        boolean persisted = exported && rightsFqn != null;

        return roleRightsSuccess(normFqn, result.rights, result.templates, result.roleProperties, persisted);
    }

    /** Builds the Role branch's success envelope after applying and exporting its rights. */
    static String roleRightsSuccess(String normFqn, int rights, int templates, int roleProperties,
        boolean persisted)
    {
        JsonObject applied = new JsonObject();
        applied.addProperty(KEY_RIGHTS, rights);
        applied.addProperty(KEY_TEMPLATES, templates);
        applied.addProperty(KEY_ROLE_PROPERTIES, roleProperties);
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Modified role " + MetadataTypeUtils.toCanonicalEnglishFqn(normFqn) + " (rights: " + rights //$NON-NLS-1$ //$NON-NLS-2$
                + ", templates: " + templates + ", roleProperties: " + roleProperties //$NON-NLS-1$ //$NON-NLS-2$
                + ")") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Modifies a COMMON ATTRIBUTE's content list (the {@code content[]} payload) via
     * {@link CommonAttributeContentWriter}: attaches / detaches an owner object in the common
     * attribute's {@code <content>} list. A common attribute's content is edited through this dedicated
     * surface, not the generic property bag, so mixing the content payload with a generic
     * {@code properties} change in the same call is refused (the same policy the Role rights / move /
     * handler / command branches enforce). The writer mutates only through the BM write boundary; this
     * branch then force-exports the single CommonAttribute TOP FQN OUTSIDE the writer, once, after the
     * write has committed.
     */
    private String modifyCommonAttributeContent(ProjectContext ctx, String normFqn,
        CommonAttribute commonAttribute, List<JsonObject> properties, List<JsonObject> content)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("A common attribute content change ('content') cannot be combined " //$NON-NLS-1$
                + "with a generic 'properties' change in one call. Set the common attribute's own " //$NON-NLS-1$
                + "properties (comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }

        CommonAttributeContentWriter.Result result =
            HostKind.current() == HostKind.HEADLESS
                ? CommonAttributeContentWriter.apply(ctx.project, normFqn, content)
                : CommonAttributeContentWriter.apply(ctx.project, ctx.config, commonAttribute, content);
        if (result.hasError())
        {
            return result.error;
        }

        // The content list lives inside the CommonAttribute's own .mdo, so exporting the CommonAttribute
        // TOP FQN once drains the change to disk.
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, normFqn);

        JsonObject applied = new JsonObject();
        applied.addProperty(KEY_ADDED, result.added);
        applied.addProperty("updated", result.updated); //$NON-NLS-1$
        applied.addProperty(KEY_REMOVED, result.removed);
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)) //$NON-NLS-1$
            .put(KEY_CONTENT, applied)
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Modified common attribute " + MetadataTypeUtils.toCanonicalEnglishFqn(normFqn) + " content (added: " //$NON-NLS-1$ //$NON-NLS-2$
                + result.added + ", updated: " + result.updated + MSG_REMOVED_COUNT + result.removed //$NON-NLS-1$
                + ")") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Modifies an EXCHANGE PLAN's content list (the {@code content[]} payload) via
     * {@link ExchangePlanContentWriter}: attaches / detaches an MdObject in the exchange plan's
     * {@code <content>} list, optionally with a per-object {@code autoRecord} (Allow / Deny) flag. An
     * exchange plan's content is edited through this dedicated surface, not the generic property bag, so
     * mixing the content payload with a generic {@code properties} change in the same call is refused
     * (the same policy the Role rights / CommonAttribute content branches enforce). The writer mutates
     * only through the BM write boundary; this branch then force-exports the single ExchangePlan TOP FQN
     * OUTSIDE the writer, once, after the write has committed.
     */
    private String modifyExchangePlanContent(ProjectContext ctx, String normFqn,
        ExchangePlan exchangePlan, List<JsonObject> properties, List<JsonObject> content)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("An exchange plan content change ('content') cannot be combined " //$NON-NLS-1$
                + "with a generic 'properties' change in one call. Set the exchange plan's own " //$NON-NLS-1$
                + "properties (comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }

        ExchangePlanContentWriter.Result result =
            HostKind.current() == HostKind.HEADLESS
                ? ExchangePlanContentWriter.apply(ctx.project, normFqn, content)
                : ExchangePlanContentWriter.apply(ctx.project, ctx.config, exchangePlan, content);
        if (result.hasError())
        {
            return result.error;
        }

        // The content list lives inside the ExchangePlan's own .mdo, so exporting the ExchangePlan TOP
        // FQN once drains the change to disk.
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, normFqn);

        JsonObject applied = new JsonObject();
        applied.addProperty(KEY_ADDED, result.added);
        applied.addProperty("updated", result.updated); //$NON-NLS-1$
        applied.addProperty(KEY_REMOVED, result.removed);
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)) //$NON-NLS-1$
            .put(KEY_CONTENT, applied)
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Modified exchange plan " + MetadataTypeUtils.toCanonicalEnglishFqn(normFqn) + " content (added: " //$NON-NLS-1$ //$NON-NLS-2$
                + result.added + ", updated: " + result.updated + MSG_REMOVED_COUNT + result.removed //$NON-NLS-1$
                + ")") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Modifies a CATALOG's owners list (the {@code content[]} payload) via
     * {@link ReferenceMembershipWriter} with {@link ReferenceMembershipWriter.Kind#CATALOG_OWNERS}:
     * attaches / detaches an owner object (a PLAIN reference, no per-entry flag) in the catalog's
     * {@code <owners>} list. A catalog's owners are edited through this dedicated surface, not the
     * generic property bag, so mixing the content payload with a generic {@code properties} change in
     * the same call is refused (the same policy the Role rights / CommonAttribute content branches
     * enforce). The writer mutates only through the BM write boundary; this branch then force-exports
     * the single Catalog TOP FQN OUTSIDE the writer, once, after the write has committed.
     */
    private String modifyCatalogOwners(ProjectContext ctx, String normFqn, Catalog catalog,
        List<JsonObject> properties, List<JsonObject> content)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("A catalog owners change ('content') cannot be combined with a " //$NON-NLS-1$
                + "generic 'properties' change in one call. Set the catalog's own properties " //$NON-NLS-1$
                + "(comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }

        ReferenceMembershipWriter.Result result = HostKind.current() == HostKind.HEADLESS
            ? ReferenceMembershipWriter.apply(ctx.project, normFqn, content, ReferenceMembershipWriter.Kind.CATALOG_OWNERS)
            : ReferenceMembershipWriter.apply(ctx.project, ctx.config, catalog, content, ReferenceMembershipWriter.Kind.CATALOG_OWNERS);
        if (result.hasError())
        {
            return result.error;
        }

        // The owners list lives inside the Catalog's own .mdo, so exporting the Catalog TOP FQN once
        // drains the change to disk.
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, normFqn);

        return buildMembershipResult(normFqn, "catalog", "owners", result, persisted); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Modifies a DOCUMENT's register records list / движения (the {@code content[]} payload) via
     * {@link ReferenceMembershipWriter} with
     * {@link ReferenceMembershipWriter.Kind#DOCUMENT_REGISTER_RECORDS}: attaches / detaches a register
     * (a PLAIN reference, no per-entry flag) in the document's {@code <registerRecords>} list. A
     * document's register records are edited through this dedicated surface, not the generic property
     * bag, so mixing the content payload with a generic {@code properties} change in the same call is
     * refused (the same policy the Role rights / CommonAttribute content branches enforce). The writer
     * mutates only through the BM write boundary; this branch then force-exports the single Document TOP
     * FQN OUTSIDE the writer, once, after the write has committed.
     */
    private String modifyDocumentRegisterRecords(ProjectContext ctx, String normFqn, Document document,
        List<JsonObject> properties, List<JsonObject> content)
    {
        if (!properties.isEmpty())
        {
            return ToolResult.error("A document register records change ('content') cannot be combined " //$NON-NLS-1$
                + "with a generic 'properties' change in one call. Set the document's own properties " //$NON-NLS-1$
                + "(comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }

        ReferenceMembershipWriter.Result result = HostKind.current() == HostKind.HEADLESS
            ? ReferenceMembershipWriter.apply(ctx.project, normFqn, content, ReferenceMembershipWriter.Kind.DOCUMENT_REGISTER_RECORDS)
            : ReferenceMembershipWriter.apply(ctx.project, ctx.config, document, content, ReferenceMembershipWriter.Kind.DOCUMENT_REGISTER_RECORDS);
        if (result.hasError())
        {
            return result.error;
        }

        // The register records list lives inside the Document's own .mdo, so exporting the Document TOP
        // FQN once drains the change to disk.
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, normFqn);

        return buildMembershipResult(normFqn, "document", "register records", result, persisted); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Modifies a SUBSYSTEM's content list (the {@code content[]} payload) via
     * {@link ReferenceMembershipWriter} with {@link ReferenceMembershipWriter.Kind#SUBSYSTEM_CONTENT}:
     * attaches / detaches a top-level configuration object (a PLAIN reference, no per-entry flag) in the
     * subsystem's {@code <content>} list. A subsystem's content is edited through this dedicated surface,
     * not the generic property bag (the generic {@code content} property REPLACES the whole list; this
     * ADDS / REMOVES one member idempotently), so mixing the content payload with a generic
     * {@code properties} change - or with a Role payload ({@code rights} / {@code templates} /
     * {@code roleProperties}, which is valid only for a Role FQN) - in the same call is refused (the same
     * policy the Role rights / CommonAttribute content / Catalog owners branches enforce, so a sibling
     * payload is never silently dropped while the tool reports success). The writer mutates only through
     * the BM write boundary; this branch then force-exports the Subsystem's OWN top-object FQN OUTSIDE the
     * writer, once, after the write has committed.
     *
     * <p>The export target is derived from the RESOLVED subsystem's OWN BM identity
     * ({@code bmGetFqn()}), NOT the caller-supplied {@code normFqn}:
     * {@link MetadataTypeUtils#normalizeFqn} canonicalizes only the LEADING type token, so a nested
     * subsystem addressed with a Russian type token in a non-leading segment (e.g.
     * {@code Subsystem.Sales.Подсистема.Orders}) resolves + writes correctly in memory yet yields a
     * non-canonical FQN that {@code forceExport} - keyed by the all-English canonical FQN
     * ({@code Subsystem.Sales.Subsystem.Orders}) - cannot match, silently discarding the committed change
     * on the next refresh. Reading the resolved subsystem's own {@code bmGetFqn()} yields that canonical
     * English-token FQN regardless of how the caller addressed it (English or Russian, top-level or
     * nested).</p>
     *
     * <p>A subsystem - top-level OR nested - is ALWAYS its own BM top object: {@code Subsystem.getSubsystems()}
     * is a plain REFERENCE list (not a containment list) and the parent link is the settable
     * {@code parentSubsystem} reference, so every subsystem is the root of its own resource with its own
     * {@code .mdo} (a nested child at {@code src/Subsystems/<Parent>/Subsystems/<Child>/<Child>.mdo}) and its
     * own canonical FQN. The content change lives in that same {@code .mdo}, so force-exporting the
     * subsystem's own FQN drains it - a nested child's OWN file, not an ancestor's. The
     * {@code bmIsTop()} guard makes this fail LOUD (an honest error, nothing written) in the
     * model-invariant-violating event a subsystem were ever NOT a top object, so {@code persisted} is never
     * a false {@code true} over an ancestor {@code .mdo} while the child's own file goes unwritten
     * ({@code bmGetFqn()} is legal only on a top object).</p>
     */
    private String modifySubsystemContent(ProjectContext ctx, String normFqn, Subsystem subsystem,
        List<JsonObject> properties, List<JsonObject> content, boolean hasRolePayload)
    {
        return prepareSubsystemContent(ctx, normFqn, subsystem, properties, content, hasRolePayload).get();
    }

    private java.util.function.Supplier<String> prepareSubsystemContent(ProjectContext ctx, String normFqn, Subsystem subsystem,
        List<JsonObject> properties, List<JsonObject> content, boolean hasRolePayload)
    {
        String subsystemKind = subsystem.eClass().getName();
        // A Role payload on a Subsystem FQN is refused before anything is applied (a Subsystem is not a
        // Role), mirroring dispatchRolePayload: the sibling payload must never be silently dropped while
        // the content change reports success.
        if (hasRolePayload)
        {
            return () -> ToolResult.error("'rights' / 'templates' / 'roleProperties' are only valid for a " //$NON-NLS-1$
                + "Role FQN; '" + normFqn + MSG_IS_A + subsystemKind + ". Use " //$NON-NLS-1$ //$NON-NLS-2$
                + "'content' to edit the subsystem's content objects, or address a Role.<Name>.").toJson(); //$NON-NLS-1$
        }
        if (!properties.isEmpty())
        {
            return () -> ToolResult.error("A subsystem content change ('content') cannot be combined with a " //$NON-NLS-1$
                + "generic 'properties' change in one call. Set the subsystem's own properties " //$NON-NLS-1$
                + "(comment / synonym) separately.").toJson(); //$NON-NLS-1$
        }

        // The resolved subsystem's OWN canonical (all-English) FQN is the force-export key. A subsystem -
        // top-level OR nested - is ALWAYS its own BM top object: Subsystem.getSubsystems() is a plain
        // REFERENCE list (NOT containment) and the parent link is the settable parentSubsystem reference,
        // so every subsystem is the root of its own resource with its own .mdo and its own canonical FQN,
        // where its content change lives. Reading bmGetFqn() on the subsystem itself - rather than reusing
        // normFqn, which normalizeFqn canonicalizes only in its leading token - keeps a nested subsystem
        // addressed with a Russian type token in a non-leading segment exportable (otherwise forceExport
        // cannot match the mixed-language FQN and the committed change is never drained to the .mdo).
        // Captured up front (a safe BM identity read), before the write transaction, mirroring how
        // ReferenceMembershipWriter.apply captures bmGetId(). The bmIsTop() guard makes this fail LOUD - an
        // honest error, nothing written - in the impossible event a subsystem were ever NOT a top object,
        // so persisted is never a false true over an ancestor .mdo while the child's own file goes
        // unwritten (bmGetFqn() is legal only on a top object).
        IBmObject subsystemBm = (IBmObject)subsystem;
        if (!subsystemBm.bmIsTop())
        {
            return () -> ToolResult.error("Cannot resolve the on-disk file to export for subsystem '" //$NON-NLS-1$
                + normFqn + "': it is not an independent top-level metadata object. A subsystem is always " //$NON-NLS-1$
                + "its own object, so this should not happen; report it with the subsystem FQN.").toJson(); //$NON-NLS-1$
        }
        String exportFqn = subsystemBm.bmGetFqn();

        return () -> {
            ReferenceMembershipWriter.Result result = HostKind.current() == HostKind.HEADLESS
                ? ReferenceMembershipWriter.apply(ctx.project, normFqn, content, ReferenceMembershipWriter.Kind.SUBSYSTEM_CONTENT)
                : ReferenceMembershipWriter.apply(ctx.project, ctx.config, subsystem, content, ReferenceMembershipWriter.Kind.SUBSYSTEM_CONTENT);
            if (result.hasError())
            {
                return result.error;
            }

            // The content list lives inside the Subsystem's own .mdo (a nested subsystem has its own .mdo),
            // so exporting the resolved subsystem's canonical top-object FQN once drains the change to disk.
            boolean persisted = BmTransactions.forceExportToDisk(ctx.project, exportFqn);

            return buildMembershipResult(normFqn, "subsystem", KEY_CONTENT, result, persisted); //$NON-NLS-1$
        };
    }

    /**
     * The first dot-delimited token of an FQN (its type token, e.g. {@code Subsystem} in
     * {@code Subsystem.Sales.Subsystem.Orders}), or the whole string when there is no dot. Pure helper
     * used to scope the early subsystem-content path by its type token, before the generic resolver runs.
     */
    private static String firstToken(String normFqn)
    {
        int dot = normFqn.indexOf('.');
        return dot < 0 ? normFqn : normFqn.substring(0, dot);
    }

    /**
     * Builds the success JSON for a plain-reference membership change (Catalog owners / Document
     * register records / Subsystem content) applied via {@link ReferenceMembershipWriter}: the
     * {@code content} counts object ({@code added} / {@code removed}; a plain reference list has no
     * per-entry flag, so there is no {@code updated}) plus {@code persisted} and a confirmation message.
     * Pure helper shared by {@link #modifyCatalogOwners}, {@link #modifyDocumentRegisterRecords} and
     * {@link #modifySubsystemContent}.
     */
    private static String buildMembershipResult(String normFqn, String kindNoun, String listNoun,
        ReferenceMembershipWriter.Result result, boolean persisted)
    {
        JsonObject applied = new JsonObject();
        applied.addProperty(KEY_ADDED, result.added);
        applied.addProperty(KEY_REMOVED, result.removed);
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)) //$NON-NLS-1$
            .put(KEY_CONTENT, applied)
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, MSG_MODIFIED_PREFIX + kindNoun + " " + MetadataTypeUtils.toCanonicalEnglishFqn(normFqn) + " " + listNoun + " (added: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + result.added + MSG_REMOVED_COUNT + result.removed + ")") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Holds the BM re-fetch strategy resolved for the modify transaction: the {@code topBmId} of the
     * re-fetchable top object plus, for a member node, the owning {@code memberFeature} and the leaf's
     * {@code memberName} to re-navigate by name inside the tx. {@link #error} is non-null (a ready JSON
     * error) when the target / top object is not a BM object.
     */
    private static final class BmFetchPlan
    {
        private long topBmId;
        private EStructuralFeature memberFeature;
        private String memberName;
        private String error;
    }

    /**
     * Resolves the BM re-fetch strategy for the modify transaction (see {@link BmFetchPlan}). Only TOP
     * objects are re-fetchable by bmId; for a member the TOP object's bmId is captured and the leaf is
     * re-navigated by name inside the tx. Side-effect-free: it only reads ids / features. Extracted
     * verbatim from {@link #executeOnUiThread}; the caller re-checks {@link BmFetchPlan#error} and
     * returns it unchanged, preserving the original error cases.
     */
    private static BmFetchPlan resolveBmFetchPlan(MetadataScope scope,
        MetadataNodeResolver.MetadataNode node, MdObject target, String[] parts)
    {
        BmFetchPlan plan = new BmFetchPlan();
        if (node.topLevel || ReferencedTopObjects.isReferencedTopCollection(node.feature))
        {
            if (!(target instanceof IBmObject))
            {
                plan.error = ToolResult.error("Target is not a BM object").toJson(); //$NON-NLS-1$
                return plan;
            }
            plan.topBmId = ((IBmObject)target).bmGetId();
            plan.memberFeature = null;
            plan.memberName = null;
        }
        else
        {
            MdObject topObject = scope.findObject(parts[0], parts[1]);
            if (!(topObject instanceof IBmObject))
            {
                plan.error = ToolResult.error("Top object is not a BM object").toJson(); //$NON-NLS-1$
                return plan;
            }
            plan.topBmId = ((IBmObject)topObject).bmGetId();
            plan.memberFeature = node.feature;
            plan.memberName = target.getName();
        }
        return plan;
    }

    /**
     * Modifies the editable {@code form:Form} ROOT addressed by a form FQN. The MD-form has already
     * been proven to exist by the dispatch; {@link FormElementWriter#resolveForEdit} establishes the
     * canonical form-write context, and the root then uses the same validation, localized reporting,
     * one-transaction apply, normalization and content-form export as an ordinary form member.
     */
    private String modifyFormRoot(ProjectContext ctx, String normFqn, String formRootPath,
        List<JsonObject> properties, MdNameNormalizer.Report normReport, String nonAssignableHint)
    {
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.scope, formRootPath, formRootNotFoundMessage(formRootPath));
            Version version = platformVersionOf(ctx);
            return applyFormProperties(ctx, normFqn, properties, normReport, fctx, version,
                "ModifyFormRoot", formModel -> formModel, nonAssignableHint); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Refusals.log("Error modifying form root", e); //$NON-NLS-1$
            return ToolResult.error("Failed to modify form root: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Modifies a FORM member (item / attribute / command) addressed by a form FQN. The member lives on
     * the editable Form content model (reached via the cross-model hop), so this branch resolves the
     * member there, reuses the shared {@link #prepare} validation + {@link PreparedChange} pipeline
     * (the introspector is EClass-driven, so an item's title / visible / readOnly and an attribute's
     * valueType / enums classify the same way mdclass properties do), then applies the changes inside a
     * BM write transaction and force-exports the CONTENT form to its {@code Form.form} on disk.
     *
     * <p>Validation and mutation run inside ONE BM write transaction: every property is validated
     * first and a failure throws {@link FormValidationException} (carrying a ready JSON error) BEFORE
     * any {@code eSet}, so the transaction rolls back with no partial mutation; building the change
     * values and setting them in the same transaction avoids any cross-transaction detached-object
     * concern. The member is re-navigated by name inside the transaction.</p>
     */
    private String modifyFormMember(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
    {
        // A handler FQN ('...Handler.Event' at form / item level) is not a property-bag member: the only
        // supported change is REBINDING its BSL procedure ('procedure' / 'handler' property). Binding a
        // NEW event stays in create_metadata, removing it in delete_metadata; any other property on a
        // handler FQN is refused with that pointer.
        if (FormElementWriter.isHandlerToken(ref.kindToken) || ref.isItemLevel())
        {
            String procName = handlerProcedureValue(properties);
            String rebindErr = validateHandlerRebind(properties, procName);
            if (rebindErr != null)
            {
                return rebindErr;
            }
            return rebindFormHandler(ctx, normFqn, ref, procName);
        }

        // A button's command targets a FormCommand (a form-model object, not an mdclass object), so it
        // is not introspector-assignable; a 'command' property on a Button FQN RE-POINTS it at an
        // existing form command.
        if (FormElementWriter.kindForToken(ref.kindToken) == FormElementWriter.Kind.BUTTON
            && hasCommandProperty(properties))
        {
            return rebindButtonCommand(ctx, normFqn, ref, properties);
        }

        // A MOVE / REORDER is expressed through the 'parent' and/or 'position' properties on a form
        // ITEM (a field / group / decoration / button / table - anything in the items tree). It is a
        // structural re-parent/reorder, not an eSet property change, so it is routed to its own branch
        // (and must not be mixed with ordinary property changes in the same call).
        if (hasMoveProperty(properties))
        {
            return moveFormItem(ctx, normFqn, ref, properties);
        }

        // A DynamicList custom query is set through 'queryText' / 'customQuery' on a form ATTRIBUTE.
        // These live on the attribute's DynamicListExtInfo - a sub-object the generic introspector does
        // not reach - so they route to their own branch that creates / configures the ext-info
        // reflectively, turning a plain form attribute into a custom-query dynamic list.
        if (isDynamicListQueryRequest(ref, properties))
        {
            return configureDynamicListQuery(ctx, normFqn, ref, properties, normReport);
        }

        // Ordinary property modify: the remaining (non-handler, non-command, non-move) case.
        return modifyFormMemberProperties(ctx, normFqn, ref, properties, normReport);
    }

    /**
     * Modifies the ordinary (non-handler, non-command, non-move) properties of a form member, the
     * remaining case of {@link #modifyFormMember} once those three structural branches are ruled out.
     * Resolves the platform version, then validates + applies every property inside ONE BM write
     * transaction (the member is re-navigated by name inside the tx; a validation failure throws
     * {@link FormValidationException} carrying its JSON error BEFORE any {@code eSet}, so the tx rolls
     * back with no partial mutation) and force-exports the CONTENT form to disk. Extracted verbatim
     * from {@link #modifyFormMember}: the BM write transaction (apply loop + forceExport via
     * {@code writeEditableForm}) stays INLINE here, and the early-returns return the SAME JSON in the
     * SAME case.
     */
    private String modifyFormMemberProperties(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
    {
        try
        {
            // Retyping a form ATTRIBUTE ('type' / 'valueType') is the destructive case for a form
            // member, so the order is resolve -> read/validate -> ask -> write: the form is resolved
            // first (a typo answers "form not found" without a dialog), the retype is validated against
            // the CURRENT model, and only a retype that can really be applied reaches the gate - which
            // runs outside any transaction, because it may block on a UI dialog (issue #295 review).
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.scope, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address a form member as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.<Kind>.Name' or 'CommonForm.FormName.<Kind>.Name' " //$NON-NLS-1$
                    + "(Kind = Attribute / Command / Parameter / Field / Button / Group / " //$NON-NLS-1$
                    + "Decoration / Table, " //$NON-NLS-1$
                    + "or Column: '...Attribute.AttrName.Column.ColName' / " //$NON-NLS-1$
                    + "'...Attribute.AttrName.Column.TabularSection.ColName')."); //$NON-NLS-1$
            // The version the type payload is built for: resolved BEFORE the gate, because the
            // pre-check validates that payload (it is the same one the write then uses).
            final Version version = platformVersionOf(ctx);
            // Written by the pre-check, read by the preview - in that order, which is the order
            // gateFormRetype runs them in.
            final boolean[] gateFlags = new boolean[2];
            return gateFormRetype(
                () -> formRetypePreview(normFqn, ref, gateFlags[GATE_RETYPE],
                    gateFlags[GATE_EXT_INFO_LOSS]),
                () -> formRetypePreflight(ctx, version, fctx, ref, properties, normReport,
                    gateFlags),
                () -> applyFormMemberProperties(ctx, normFqn, ref, properties, normReport, fctx,
                    version));
        }
        catch (Exception e)
        {
            // A property-validation failure carries a ready JSON error (possibly wrapped by the tx
            // runner) - surface it directly; anything else is a genuine failure.
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Refusals.log("Error modifying form member", e); //$NON-NLS-1$
            return ToolResult.error("Failed to modify form member: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Applies the validated form-member property changes: ONE BM write transaction (resolve the member,
     * validate every property, apply) plus the force-export and the success payload. Split out of
     * {@link #modifyFormMemberProperties} so the WHOLE mutation is the callback
     * {@link #gateFormRetype} invokes - a destructive retype cannot be written by statement order
     * alone (issue #295 review). Throws like the code it was extracted from; the caller maps the
     * exception.
     *
     * @param ctx the resolved project context
     * @param normFqn the normalized member FQN
     * @param ref the parsed form-member ref
     * @param properties the requested property changes
     * @param normReport the name-normalization report
     * @param fctx the already-resolved form edit context
     * @param version the platform version the pre-check already validated the type payload against
     * @return the tool's JSON result
     */
    private String applyFormMemberProperties(ProjectContext ctx, String normFqn, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties,
        MdNameNormalizer.Report normReport, FormElementWriter.FormEditContext fctx, Version version)
    {
        return applyFormProperties(ctx, normFqn, properties, normReport, fctx, version,
            "ModifyFormMember", formModel -> //$NON-NLS-1$
            {
                EObject member = FormElementWriter.resolveFormMember(formModel, ref);
                if (member == null)
                {
                    // The KIND is part of the resolution (issue #343), so a member that exists under
                    // another kind is reported as exactly that, with the corrected address -
                    // otherwise "not found" contradicts what get_metadata_details lists.
                    throw new FormValidationException(ToolResult.error("Form member not found: " //$NON-NLS-1$
                        + ref.name + " (kind '" + ref.kindToken + "') on " + ref.formPath //$NON-NLS-1$ //$NON-NLS-2$
                        + advisedOr(FormElementWriter.kindMismatchAdvice(formModel, ref, normFqn),
                            ". Use get_metadata_details to list the members.")).toJson()); //$NON-NLS-1$
                }
                // A form COMMAND's action is its handler member ('...Command.<Name>.Handler.Action'),
                // not a property-bag value (issue #628): its property spellings are refused with that
                // member's route instead of the bare assignable list, which read as "impossible
                // here". Decided on the RESOLVED command, so a missing form / command / wrong kind
                // keeps its own answer above; any other kind has no action, so there the same names
                // go on to the ordinary pipeline and its own assignable list.
                String actionProperty = commandActionPropertyOf(
                    FormElementWriter.kindForToken(ref.kindToken), properties);
                if (actionProperty != null)
                {
                    throw new FormValidationException(
                        commandActionPropertyRefusal(normFqn, actionProperty));
                }
                return member;
            }, null);
    }

    /** Resolves the form-model EObject whose ordinary properties the shared apply loop will set. */
    @FunctionalInterface
    private interface FormPropertyTargetResolver
    {
        EObject resolve(EObject formModel);
    }

    /**
     * Applies ordinary properties to either the form MODEL ROOT or a resolved form member. This is
     * the single form property loop: it prepares every {@link HolderChange} before applying any,
     * performs the extInfo holder hop, tracks localized pre/post state, records applied features and
     * returns the common modified/persisted/normalization result shape.
     */
    private String applyFormProperties(ProjectContext ctx, String normFqn, // NOSONAR shared root/member contract
        List<JsonObject> properties, MdNameNormalizer.Report normReport,
        FormElementWriter.FormEditContext fctx, Version version, String taskName,
        FormPropertyTargetResolver targetResolver, String nonAssignableHint)
    {
        final List<String> applied = new ArrayList<>();
        // A form root's/member's title is localized too, so it gets the same report the mdclass path
        // gives (issue #298). The declared codes are read OUTSIDE the write transaction.
        final List<String> declaredCodes = readPreparation(ctx.project, () -> preparationScope(ctx).declaredLanguageCodes());
        final List<String> externalLocales = externalLocalesForWrite(ctx, declaredCodes);
        final LocalizedWriteReport localizedReport = new LocalizedWriteReport();
        // Attributes this call took the main flag AWAY from - a change to a member the caller did
        // not address, so it is reported rather than left to be discovered.
        final List<String> demotedMains = new ArrayList<>();
        // Same rule for the bindings a KIND change took with it (issue #601): the caller addressed
        // the item's type, not its subscriptions, so a removal it did not ask for is reported.
        final List<String> removedHandlers = new ArrayList<>();

        // Validate + apply inside ONE BM write transaction: resolve the target, validate every
        // property (a failure throws FormValidationException carrying the JSON error BEFORE any eSet,
        // so the tx rolls back with no partial mutation), then apply. A member is re-navigated by name
        // inside the tx; the root is the re-fetched content form itself. Building the change values
        // and setting them in the SAME tx avoids any cross-transaction detached-object concern.
        final List<String> writtenExcludedCommands = new ArrayList<>();
        final boolean persisted = FormElementWriter.writeEditableForm(fctx, taskName,
            (formModel, tx) ->
            {
                EObject target = targetResolver.resolve(formModel);
                List<HolderChange> changes =
                    prepareFormMemberChanges(transactionScope(ctx, tx), version, target, properties, normReport,
                        nonAssignableHint);
                // (receiver, change) of every localized write, reported only AFTER the whole
                // batch is applied: reading a map mid-batch would report a locale as missing that
                // a LATER change in the same call fills in.
                List<EObject> localizedHolders = new ArrayList<>();
                List<PreparedChange> localizedChanges = new ArrayList<>();
                boolean mainFlagWritten = false;
                boolean itemKindWritten = false;
                final List<Object> pairingBefore = itemPairingOf(target);
                for (HolderChange hc : changes)
                {
                    // A direct feature lands on the target; a property on the nested <extInfo> lands
                    // on the extInfo holder, created (or reused) here now that every property has
                    // validated. Mixing both in one call routes each change to its correct receiver.
                    EObject holder = hc.onExtInfo
                        ? FormElementWriter.ensureExtInfo(formModel, target) : target;
                    // BEFORE the write: whether this locale already held text decides if the
                    // OTHER locales go stale (see LocalizedWriteReport.rememberPreState).
                    localizedReport.rememberPreState(holder, List.of(hc.change));
                    hc.change.applyTo(holder, tx);
                    if (hc.change.scalarValue instanceof ExcludedCommandsSupport.Plan plan)
                    {
                        writtenExcludedCommands.clear();
                        writtenExcludedCommands.addAll(plan.names());
                    }
                    applied.add(hc.change.featureName());
                    if (syncExtInfoAfter(hc, formModel, target, removedHandlers))
                    {
                        applied.add("extInfo"); //$NON-NLS-1$
                    }
                    mainFlagWritten = mainFlagWritten || decidesFormExtInfo(hc);
                    itemKindWritten = itemKindWritten || writesItemKind(hc);
                    if (hc.change.isLocalized())
                    {
                        // Remember the receiver the change actually landed on: a title on the
                        // member and one on its <extInfo> live in different objects.
                        localizedHolders.add(holder);
                        localizedChanges.add(hc.change);
                    }
                }
                // Which events the item still publishes is decided ONCE, on the kind the batch
                // LEAVES. Per change it would answer about an intermediate kind: applied in order,
                // [type=LabelField, type=InputField] would drop, at the LabelField step, the very
                // subscriptions InputField publishes. Same last-write-wins rule the retype verdict
                // and the main flag follow.
                if (pairingChanged(itemKindWritten, pairingBefore, target))
                {
                    removedHandlers.addAll(
                        FormElementWriter.dropUnpublishedItemHandlers(target, version));
                }
                // A main-attribute promotion is the platform's setMainAttribute: it demotes the
                // previous main first, then re-derives the root ext-info from the FINAL state of
                // the batch (per change it would depend on the order the properties arrived in).
                if (mainFlagWritten)
                {
                    // Demoted by the PROMOTION the batch performed, even when a later write in the
                    // same batch takes the flag back off this one.
                    if (mainPromotedIn(properties))
                    {
                        demotedMains.addAll(
                            FormElementWriter.demoteOtherMainAttributes(formModel, target));
                    }
                    if (FormElementWriter.syncFormExtInfo(formModel) != null
                        && !applied.contains("extInfo")) //$NON-NLS-1$
                    {
                        applied.add("extInfo"); //$NON-NLS-1$
                    }
                }
                for (int i = 0; i < localizedChanges.size(); i++)
                {
                    localizedReport.collectWithLocales(localizedHolders.get(i),
                        List.of(localizedChanges.get(i)), declaredCodes,
                        localesInUseDuringWrite(ctx, tx, declaredCodes, externalLocales));
                }
            });

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted);
        if (applied.contains(ExcludedCommandsSupport.PROPERTY))
        {
            result.put(ExcludedCommandsSupport.PROPERTY, writtenExcludedCommands);
        }
        localizedReport.addTo(result);
        normReport.addTo(result);
        if (!demotedMains.isEmpty())
        {
            result.put("demotedMainAttributes", demotedMains); //$NON-NLS-1$
        }
        if (!removedHandlers.isEmpty())
        {
            result.put(KEY_REMOVED_EVENT_HANDLERS, removedHandlers);
        }
        return result
            .put(McpKeys.MESSAGE, MSG_MODIFIED_PREFIX + MetadataTypeUtils.toCanonicalEnglishFqn(normFqn) + " (" + String.join(", ", applied) + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    // --- DynamicList custom query (set on a form ATTRIBUTE) -----------------------------------------

    /** The dynamic-list query property names. {@code queryText} sets the custom query, {@code customQuery}
     * toggles whether the dynamic list uses it (vs the automatic main-table query). */
    private static final String PROP_QUERY_TEXT = "queryText"; //$NON-NLS-1$
    private static final String PROP_CUSTOM_QUERY = "customQuery"; //$NON-NLS-1$
    // ru TekstZaprosa (= queryText) / ProizvolnyjZapros (= customQuery) - pure-ASCII source (cp codepoints).
    private static final String RU_PROP_QUERY_TEXT = MetadataLanguageUtils.cp(0x0422, 0x0435, 0x043a,
        0x0441, 0x0442, 0x0417, 0x0430, 0x043f, 0x0440, 0x043e, 0x0441, 0x0430);
    private static final String RU_PROP_CUSTOM_QUERY = MetadataLanguageUtils.cp(0x041f, 0x0440, 0x043e,
        0x0438, 0x0437, 0x0432, 0x043e, 0x043b, 0x044c, 0x043d, 0x044b, 0x0439, 0x0417, 0x0430, 0x043f,
        0x0440, 0x043e, 0x0441);
    /** The dynamic-list main-table property: an object FQN whose main table the list reads from. */
    private static final String PROP_MAIN_TABLE = "mainTable"; //$NON-NLS-1$
    // ru OsnovnayaTablica (= mainTable) - pure-ASCII source (cp codepoints).
    private static final String RU_PROP_MAIN_TABLE = MetadataLanguageUtils.cp(0x041e, 0x0441, 0x043d,
        0x043e, 0x0432, 0x043d, 0x0430, 0x044f, 0x0422, 0x0430, 0x0431, 0x043b, 0x0438, 0x0446, 0x0430);

    /** Whether a property NAME is the {@code queryText} dynamic-list property (English or Russian). */
    static boolean isQueryTextProp(String name)
    {
        return PROP_QUERY_TEXT.equalsIgnoreCase(name) || RU_PROP_QUERY_TEXT.equalsIgnoreCase(name);
    }

    /** Whether a property NAME is the {@code customQuery} dynamic-list property (English or Russian). */
    static boolean isCustomQueryProp(String name)
    {
        return PROP_CUSTOM_QUERY.equalsIgnoreCase(name) || RU_PROP_CUSTOM_QUERY.equalsIgnoreCase(name);
    }

    /** Whether a property NAME is the {@code mainTable} dynamic-list property (English or Russian). */
    static boolean isMainTableProp(String name)
    {
        return PROP_MAIN_TABLE.equalsIgnoreCase(name) || RU_PROP_MAIN_TABLE.equalsIgnoreCase(name);
    }

    /**
     * Whether this modify is a DynamicList custom-query request: a form ATTRIBUTE FQN carrying a
     * {@code queryText} and/or {@code customQuery} property. Those properties live on the attribute's
     * {@code DynamicListExtInfo} (not on the attribute itself), so they are routed to their own branch
     * instead of the generic introspector path. Reads only the property list (no model mutation).
     */
    private static boolean isDynamicListQueryRequest(FormElementWriter.FormMemberRef ref,
        List<JsonObject> properties)
    {
        if (FormElementWriter.kindForToken(ref.kindToken) != FormElementWriter.Kind.ATTRIBUTE)
        {
            return false;
        }
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (isQueryTextProp(name) || isCustomQueryProp(name) || isMainTableProp(name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Configures a form attribute as a custom-query dynamic list ({@code queryText} / {@code customQuery}).
     * Mirrors {@link #modifyFormMemberProperties}: resolves the platform version, then applies the change
     * inside ONE BM write transaction ({@link FormElementWriter#configureDynamicListQuery} creates /
     * configures the {@code DynamicListExtInfo} reflectively) and force-exports the content form. The
     * query properties are structural (they create the ext-info and the {@code DynamicList} value type),
     * so they must not be mixed with ordinary property changes in one call - the same policy the move /
     * handler / command branches enforce.
     */
    private String configureDynamicListQuery(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
    {
        DynListQueryRequest req = parseDynListQueryProps(properties);
        if (req.error != null)
        {
            return req.error;
        }
        final String qt = req.queryText;
        final Boolean cq = req.customQuery;
        final String mt = req.mainTable;

        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.scope, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address the dynamic-list attribute as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.Attribute.Name'."); //$NON-NLS-1$
            // Converting a plain (or collection-typed) attribute into a dynamic list REPLACES its
            // valueType, exactly like the `type` property does - and that path asks the consent gate.
            // The order is resolve -> read/validate -> ask -> write (see gateFormRetype); the gate may
            // block on a dialog, so it never runs inside a transaction (issue #295 review).
            // Resolved BEFORE the gate: the conversion needs the DynamicList value type for this
            // version, and failing to build it refuses the write whatever the user answers.
            final Version version = platformVersionOf(ctx);
            QueryWriteValidation.Batch queryValidation = QueryWriteValidation.validate(ctx.project,
                qt == null ? List.of() : List.of(new QueryWriteValidation.Query("queryText", qt, false)));
            if (queryValidation.errorJson() != null) return queryValidation.errorJson();
            return gateFormRetype(() -> dynamicListRetypePreview(normFqn),
                () -> dynamicListRetypePreflight(fctx, ctx.config, version, ref, qt, mt),
                () -> applyDynamicListQuery(ctx, normFqn, ref, qt, cq, mt, normReport, fctx, version, queryValidation));
        }
        catch (Exception e)
        {
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Activator.logError("Error configuring dynamic-list query", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set the dynamic-list query: " //$NON-NLS-1$
                + unwrapCauseMessage(e)).toJson();
        }
    }

    /**
     * Applies the dynamic-list query change: ONE BM write transaction plus the success payload. Split
     * out of {@link #configureDynamicListQuery} so the WHOLE mutation is the callback
     * {@link #gateFormRetype} invokes (issue #295 review). Throws like the code it was extracted from;
     * the caller maps the exception.
     *
     * @param ctx the resolved project context
     * @param normFqn the normalized attribute FQN
     * @param ref the parsed form-member ref
     * @param qt the custom query text, or {@code null}
     * @param cq the {@code customQuery} toggle, or {@code null}
     * @param mt the main-table FQN, or {@code null}
     * @param normReport the name-normalization report
     * @param fctx the already-resolved form edit context
     * @return the tool's JSON result
     */
    private String applyDynamicListQuery(ProjectContext ctx, String normFqn, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        FormElementWriter.FormMemberRef ref, String qt, Boolean cq, String mt,
        MdNameNormalizer.Report normReport, FormElementWriter.FormEditContext fctx, Version version,
        QueryWriteValidation.Batch queryValidation)
    {
        final List<String> applied = new ArrayList<>();
        boolean persisted = FormElementWriter.writeEditableForm(fctx, "ConfigureDynamicListQuery", //$NON-NLS-1$
            (formModel, tx) ->
            {
                EObject member = FormElementWriter.resolveFormMember(formModel, ref);
                if (member == null)
                {
                    // A dynamic list lives on a form ATTRIBUTE; when the name belongs to an element
                    // of another kind, say so instead of advising a create that would collide.
                    throw new FormValidationException(ToolResult.error("Form attribute not found: " //$NON-NLS-1$
                        + ref.name + " on " + ref.formPath //$NON-NLS-1$
                        + advisedOr(FormElementWriter.kindMismatchAdvice(formModel, ref, normFqn),
                            ". Create it with create_metadata, then set its query.")).toJson()); //$NON-NLS-1$
                }
                // This branch retypes the attribute to DynamicList without going through the
                // property path, so it needs the SAME stranded-columns guard (issue #295 review) -
                // but ONLY when a conversion is actually on the table. A customQuery-only request
                // converts nothing, and answering it with "delete the columns first" hid the real
                // problem ("provide a queryText"), which the writer raises just below.
                boolean converts = (qt != null && !qt.isEmpty()) || (mt != null && !mt.isEmpty());
                String orphanErr = converts ? orphanColumnsError(member) : null;
                if (orphanErr != null)
                {
                    throw new FormValidationException(orphanErr);
                }
                applied.addAll(FormElementWriter.configureDynamicListQuery(
                    formModel, member, qt, cq, mt, transactionScope(ctx, tx).configuration(), version));
            });

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted);
        queryValidation.addTo(result);
        normReport.addTo(result);
        return result
            .put(McpKeys.MESSAGE, "Configured dynamic-list query on " + MetadataTypeUtils.toCanonicalEnglishFqn(normFqn) //$NON-NLS-1$
                + " (" + String.join(", ", applied) + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    /**
     * The parsed dynamic-list query properties, or a ready JSON {@code error} when a value was malformed
     * or a query prop was mixed with another property change. Lets {@link #configureDynamicListQuery} stay
     * a thin orchestrator while the property loop and its early-return validations live in
     * {@link #parseDynListQueryProps}.
     */
    private static final class DynListQueryRequest
    {
        final String queryText;
        final Boolean customQuery;
        final String mainTable;
        final String error;

        private DynListQueryRequest(String queryText, Boolean customQuery, String mainTable, String error)
        {
            this.queryText = queryText;
            this.customQuery = customQuery;
            this.mainTable = mainTable;
            this.error = error;
        }

        static DynListQueryRequest of(String queryText, Boolean customQuery, String mainTable)
        {
            return new DynListQueryRequest(queryText, customQuery, mainTable, null);
        }

        static DynListQueryRequest failed(String error)
        {
            return new DynListQueryRequest(null, null, null, error);
        }
    }

    /**
     * Reads the dynamic-list query properties ({@code queryText} / {@code customQuery} / {@code mainTable})
     * from the property list into a {@link DynListQueryRequest}. Returns one carrying a ready JSON error
     * when a value is malformed or a query prop is mixed with another property change. Pure (reads only the
     * supplied list).
     */
    private DynListQueryRequest parseDynListQueryProps(List<JsonObject> properties)
    {
        DynListQueryProps acc = new DynListQueryProps();
        for (JsonObject prop : properties)
        {
            String error = readDynListQueryProp(prop, acc);
            if (error != null)
            {
                return DynListQueryRequest.failed(error);
            }
        }
        if (acc.queryTextGiven && (acc.queryText == null || acc.queryText.trim().isEmpty()))
        {
            return DynListQueryRequest.failed(ToolResult.error("'queryText' must be a non-empty 1C query, e.g. " //$NON-NLS-1$
                + "\"SELECT Ref, Description AS Description FROM Catalog.Products\". To switch the " //$NON-NLS-1$
                + "dynamic list back to its automatic query, pass 'customQuery' = false instead.").toJson()); //$NON-NLS-1$
        }
        return DynListQueryRequest.of(acc.queryText, acc.customQuery, acc.mainTable);
    }

    /** Mutable accumulator for the dynamic-list query properties read by {@link #readDynListQueryProp}. */
    private static final class DynListQueryProps
    {
        String queryText;
        Boolean customQuery;
        String mainTable;
        boolean queryTextGiven;
    }

    /**
     * Reads a single property into {@code acc}, returning a ready JSON error string when the value is
     * malformed or a query prop is mixed with another property change, or {@code null} to continue.
     * Pure apart from updating {@code acc}.
     */
    private String readDynListQueryProp(JsonObject prop, DynListQueryProps acc)
    {
        String name = asString(prop.get("name")); //$NON-NLS-1$
        if (isQueryTextProp(name))
        {
            acc.queryText = asString(prop.get(KEY_VALUE));
            acc.queryTextGiven = true;
            return null;
        }
        if (isCustomQueryProp(name))
        {
            Boolean parsed = parseBooleanFlag(prop.get(KEY_VALUE));
            if (parsed == null)
            {
                return ToolResult.error("'customQuery' must be a boolean (true / false).").toJson(); //$NON-NLS-1$
            }
            acc.customQuery = parsed;
            return null;
        }
        if (isMainTableProp(name))
        {
            acc.mainTable = asString(prop.get(KEY_VALUE));
            if (acc.mainTable == null || acc.mainTable.trim().isEmpty())
            {
                return ToolResult.error("'mainTable' must be an object FQN, e.g. " //$NON-NLS-1$
                    + "'Catalog.Products' or 'Document.Order'.").toJson(); //$NON-NLS-1$
            }
            return null;
        }
        return ToolResult.error("Setting a dynamic-list query ('queryText' / 'customQuery' / " //$NON-NLS-1$
            + "'mainTable') cannot be combined with other property changes ('" + name //$NON-NLS-1$
            + "') in one call. Configure the query first, then make the other changes " //$NON-NLS-1$
            + "separately.").toJson(); //$NON-NLS-1$
    }

    /**
     * Parses a flag property value (a JSON boolean, or the string {@code "true"} / {@code "false"}),
     * or {@code null} when the value is not a recognizable boolean.
     */
    static Boolean parseBooleanFlag(JsonElement value)
    {
        if (value == null || !value.isJsonPrimitive())
        {
            return null; // NOSONAR tri-state: null means "not a recognizable boolean"; callers check it explicitly
        }
        JsonPrimitive prim = value.getAsJsonPrimitive();
        if (prim.isBoolean())
        {
            return Boolean.valueOf(prim.getAsBoolean());
        }
        String s = prim.getAsString().trim();
        if ("true".equalsIgnoreCase(s)) //$NON-NLS-1$
        {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s)) //$NON-NLS-1$
        {
            return Boolean.FALSE;
        }
        return null; // NOSONAR tri-state: null means "not a recognizable boolean"; callers check it explicitly
    }

    /**
     * Validates a handler-rebind request on a handler / item-level FQN. A handler FQN supports only
     * REBINDING its BSL procedure, and that rebind is structural so it must not be mixed with other
     * property changes. Returns a JSON error to refuse the call - when no {@code procedure} value was
     * supplied ({@code procName == null}), or when the rebind is mixed with another property change -
     * or {@code null} when the rebind is valid and the caller may proceed to {@link #rebindFormHandler}.
     * Pure (no model mutation): reads only the supplied property list.
     */
    private static String validateHandlerRebind(List<JsonObject> properties, String procName)
    {
        if (procName != null)
        {
            // A handler rebind is structural and must not be mixed with other property changes in
            // one call - the same policy the move ('parent'/'position') and button-command
            // ('command') branches enforce. Reject BEFORE any mutation.
            String mixed = firstNonHandlerRebindProperty(properties);
            if (mixed != null)
            {
                return ToolResult.error("Rebinding a handler's procedure ('procedure') cannot be " //$NON-NLS-1$
                    + "combined with other property changes ('" + mixed + "') in one call. Rebind " //$NON-NLS-1$ //$NON-NLS-2$
                    + "the procedure first, then make the other changes in a separate call.").toJson(); //$NON-NLS-1$
            }
            return null;
        }
        return ToolResult.error("On a form event-handler FQN, modify_metadata can only REBIND the " //$NON-NLS-1$
            + "bound procedure - pass a 'procedure' property (e.g. {name:'procedure', " //$NON-NLS-1$
            + "value:'NewProc'}). To bind a new event use create_metadata, to remove it " //$NON-NLS-1$
            + "delete_metadata.").toJson(); //$NON-NLS-1$
    }

    /**
     * Validates every property of a form-model EObject (root or member) against the introspected
     * schema and builds the ordered list of {@link HolderChange}s to apply - each pairing a
     * {@link PreparedChange} with the
     * receiver it targets: the EObject itself for a direct feature, or a member's nested
     * {@code <extInfo>} holder for a layout / kind-specific property (a UsualGroup's grouping / united /
     * ... live under {@code <extInfo>}, not on the group element). Runs inside the BM write transaction
     * (called from the {@code writeEditableForm} callback) but performs NO model mutation itself - it
     * only reads the target's (and, when present, its extInfo's) schema and constructs the changes; a
     * structural-property guard or an invalid value throws {@link FormValidationException} BEFORE any
     * {@code eSet}, so the transaction rolls back with no partial mutation. The extInfo holder is
     * created (when absent) only at APPLY time by the caller, once every property has validated.
     */
    private List<HolderChange> prepareFormMemberChanges(MetadataScope scope, Version version, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        EObject member, List<JsonObject> properties, MdNameNormalizer.Report normReport)
    {
        return prepareFormMemberChanges(scope, version, member, properties, normReport, null);
    }

    /**
     * The form-root variant may add discovery guidance for the common form's mdclass surface. The
     * five-argument overload remains the ordinary member path and preserves its validation contract.
     */
    private List<HolderChange> prepareFormMemberChanges(MetadataScope scope, Version version, // NOSONAR shared validation contract
        EObject member, List<JsonObject> properties, MdNameNormalizer.Report normReport,
        String nonAssignableHint)
    {
        // Reject a classifier `type` change batched with a nested-extInfo layout prop BEFORE building any
        // change: the extInfo props are validated against the pre-change type's extInfo EClass, so
        // applying both in one tx is order-dependent and unsafe (see formTypeExtInfoComboError).
        String excludedBatchErr = ExcludedCommandsSupport.isFeature(
            member.eClass().getEStructuralFeature(ExcludedCommandsSupport.PROPERTY))
                ? ExcludedCommandsSupport.batchError(properties) : null;
        String comboErr = excludedBatchErr == null ? null : ToolResult.error(excludedBatchErr).toJson();
        if (comboErr == null)
        {
            comboErr = formTypeExtInfoComboError(member, properties);
        }
        if (comboErr == null)
        {
            comboErr = useAlwaysWithRetypeError(properties);
        }
        if (comboErr != null)
        {
            throw new FormValidationException(comboErr);
        }
        // A CommonForm.Name fallback was chosen only because no requested name belongs to the
        // common form's mdclass object. If a name is absent from the content root as well, report
        // BOTH surfaces before a structural guard can replace the ordinary assignability error.
        if (nonAssignableHint != null)
        {
            for (JsonObject prop : properties)
            {
                JsonObject normProp = normalizeFormProperty(member, prop);
                String name = asString(normProp.get("name")); //$NON-NLS-1$
                if (name == null || name.isEmpty())
                {
                    continue;
                }
                FormHolder holder = resolveFormHolder(member, name);
                if (MetadataPropertyIntrospector.findFeature(member, holder.classifyExtInfo, name) == null)
                {
                    EClass extInfoEClass = holder.classifyExtInfo != null
                        ? holder.classifyExtInfo.eClass() : FormElementWriter.resolveExtInfoEClass(member);
                    throw new FormValidationException(nonAssignablePropertyError(member, name,
                        extInfoEClass, nonAssignableHint));
                }
            }
        }
        List<HolderChange> changes = new ArrayList<>();
        boolean dataPathSeen = false;
        for (JsonObject prop : properties)
        {
            if ("dataPath".equalsIgnoreCase(asString(prop.get("name")))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                if (dataPathSeen)
                {
                    throw new FormValidationException(ToolResult.error(
                        "Set 'dataPath' only once per modify_metadata call. An intermediate table binding " //$NON-NLS-1$
                            + "can introduce binding-specific configuration; pass the final path in one property.") //$NON-NLS-1$
                        .toJson());
                }
                dataPathSeen = true;
            }
            String guard = guardFormProperty(prop);
            if (guard != null)
            {
                throw new FormValidationException(guard);
            }
            changes.add(prepareFormMemberChange(scope, version, member, prop, normReport));
        }
        return changes;
    }

    /**
     * Re-pairs a form member's nested {@code <extInfo>} with the classifier the change just set, in the
     * SAME transaction, so the member is never persisted half-built (issue #369). Two classifiers do
     * this, and only these two:
     * <ul>
     * <li>a form ATTRIBUTE's {@code valueType} - a {@code ValueList} needs a {@code ValueListExtInfo},
     * a {@code SpreadsheetDocument} a {@code SpreadsheetDocumentExtInfo}, ... ({@link
     * FormElementWriter#syncAttributeExtInfo});</li>
     * <li>a form ITEM's {@code type} - a {@code Picture} decoration needs a
     * {@code PictureDecorationExtInfo}, a {@code CheckBoxField} a {@code CheckBoxFieldExtInfo}, ...
     * ({@link FormElementWriter#syncItemExtInfo}).</li>
     * </ul>
     * A change that lands ON the extInfo is never a classifier change (it is a property INSIDE the
     * holder), and {@link #formTypeExtInfoComboError} has already refused mixing the two in one call.
     * Both syncs no-op for a member with no {@code extInfo} feature (an attribute COLUMN, a Button).
     *
     * @param hc the change that was just applied
     * @param formModel the editable content form
     * @param member the form member the change landed on
     * @param lostHandlers collects the bindings an ext-info node took with it when the new type
     *            pairs with none to carry them into (issue #601)
     * @return {@code true} when an extInfo is now attached (so the caller can report it as applied)
     */
    private static boolean syncExtInfoAfter(HolderChange hc, EObject formModel, EObject member,
        List<String> lostHandlers)
    {
        if (hc.onExtInfo)
        {
            return false;
        }
        if (hc.change.isTypeChange())
        {
            return FormElementWriter.syncAttributeExtInfo(formModel, member) != null;
        }
        if ("type".equalsIgnoreCase(hc.change.featureName())) //$NON-NLS-1$
        {
            return FormElementWriter.syncItemExtInfo(formModel, member, lostHandlers) != null;
        }
        return false;
    }

    /** The item's display kind (its {@code type} enum value), or {@code null} when it has none. */
    static Object itemKindOf(EObject item)
    {
        EStructuralFeature feature = item == null ? null : item.eClass().getEStructuralFeature("type"); //$NON-NLS-1$
        return feature instanceof EAttribute && !feature.isMany() ? item.eGet(feature) : null;
    }

    /** The item's kind and its ext-info class together: the two decide which events it publishes. */
    static List<Object> itemPairingOf(EObject item)
    {
        EStructuralFeature extFeature = item == null ? null : item.eClass().getEStructuralFeature("extInfo"); //$NON-NLS-1$
        Object ext = extFeature instanceof EReference && !extFeature.isMany() ? item.eGet(extFeature) : null;
        return Arrays.asList(itemKindOf(item), ext instanceof EObject ? ((EObject)ext).eClass().getName() : null);
    }

    /**
     * Whether a kind write really changed what the item publishes: a new kind, or the same kind whose
     * stale ext-info was repaired. Rewriting an already-consistent pairing prunes no binding.
     */
    static boolean pairingChanged(boolean kindWritten, List<Object> pairingBefore, EObject item)
    {
        return kindWritten && !Objects.equals(pairingBefore, itemPairingOf(item));
    }

    /** Whether this change writes a form ITEM's display KIND - the {@code type} enum on the item. */
    private static boolean writesItemKind(HolderChange hc)
    {
        return !hc.onExtInfo && !hc.change.isTypeChange()
            && "type".equalsIgnoreCase(hc.change.featureName()); //$NON-NLS-1$
    }

    /**
     * Whether this change decides the FORM ROOT's ext-info - a write of the {@code main} flag, and
     * nothing else. The platform re-derives that node from one place only
     * ({@code FormAttributeService.setMainAttribute}); a retype of the main attribute goes to
     * {@code setTypeDescription}, which never touches the root node.
     */
    private static boolean decidesFormExtInfo(HolderChange hc)
    {
        return !hc.onExtInfo && PROP_MAIN.equalsIgnoreCase(hc.change.featureName());
    }

    /**
     * Refuses {@code useAlways} batched with an attribute retype: the checkboxes are validated against
     * the type the attribute has BEFORE the batch, so the retype must come first, in its own call.
     * Package-visible for the headless test.
     */
    static String useAlwaysWithRetypeError(List<JsonObject> properties)
    {
        boolean useAlways = false;
        boolean retype = false;
        for (JsonObject prop : properties)
        {
            String name = prop == null ? null : asString(prop.get("name")); //$NON-NLS-1$
            useAlways = useAlways || UseAlwaysSupport.PROPERTY.equalsIgnoreCase(name);
            retype = retype || PROP_VALUE_TYPE.equalsIgnoreCase(name) || "type".equalsIgnoreCase(name); //$NON-NLS-1$
        }
        return useAlways && retype
            ? ToolResult.error("'" + UseAlwaysSupport.PROPERTY + "' cannot be combined with a 'type' / " //$NON-NLS-1$ //$NON-NLS-2$
                + "'valueType' change in one call: the checkboxes are checked against the attribute's " //$NON-NLS-1$
                + "current type. Change the type first, then set '" + UseAlwaysSupport.PROPERTY //$NON-NLS-1$
                + "' in a separate call.").toJson() //$NON-NLS-1$
            : null;
    }

    /**
     * Rejects a form-member modify that UNSAFELY combines a classifier change - a group's / field's /
     * decoration's {@code type}, or an ATTRIBUTE's {@code valueType}, each of which decides which
     * concrete {@code <extInfo>} EClass applies - with a
     * property that lives on that nested {@code <extInfo>}, in the SAME call. The extInfo props are
     * classified / validated against the PRE-change type's extInfo EClass (in {@link #resolveFormHolder}),
     * so applying both in one transaction is order-dependent and unsafe:
     * <ul>
     * <li>{@code type} first &rarr; {@link FormElementWriter#ensureExtInfo} creates the NEW type's extInfo
     * EClass and the extInfo {@code eSet} throws {@link IllegalArgumentException} on a feature the new
     * EClass lacks (surfaced as an opaque "Failed to modify form member");</li>
     * <li>the extInfo prop first &rarr; the re-pairing replaces the holder it was just written to, so the
     * property is DISCARDED while still being reported as applied (and, before the re-pairing existed, a
     * stale-typed extInfo was force-exported onto a now-differently typed element - a silent
     * inconsistency EDT serialization rejects).</li>
     * </ul>
     * The {@code type} change must be a SEPARATE call so the extInfo is re-resolved against the new type.
     * Detection is fully reflective (the direct-vs-extInfo routing from {@link #resolveFormHolder} plus the
     * normalized property name); an mdclass object has no extInfo so this is a no-op there.
     * Package-visible so it is unit-testable headlessly. Returns a ready JSON error to reject, or
     * {@code null} when the batch is safe.
     */
    static String formTypeExtInfoComboError(EObject member, List<JsonObject> properties)
    {
        int directTypeChanges = 0;
        String typeName = null;
        boolean hasExtInfoChange = false;
        for (JsonObject prop : properties)
        {
            String name = asString(normalizeFormProperty(member, prop).get("name")); //$NON-NLS-1$
            if (name == null || name.isEmpty())
            {
                continue;
            }
            if (resolveFormHolder(member, name).onExtInfo)
            {
                hasExtInfoChange = true;
            }
            else if ("type".equalsIgnoreCase(name) || PROP_VALUE_TYPE.equalsIgnoreCase(name)) //$NON-NLS-1$
            {
                // BOTH spellings, because a form ATTRIBUTE has no `type` feature: normalizeFormProperty
                // has already rewritten its `type` to `valueType` by the time this reads the name, and a
                // guard that only knew the enum spelling let the attribute case straight through
                // (issue #369 review). A value type decides the ext-info exactly as an item's enum does.
                directTypeChanges++;
                typeName = name;
            }
        }
        if (directTypeChanges > 1)
        {
            // Each write rebuilds the ext-info, so an intermediate value would drop what the final
            // one keeps, while the retype gate and the guards judge a state the batch never reaches.
            return ToolResult.error("A form member's '" + typeName + "' can be set only ONCE per " //$NON-NLS-1$ //$NON-NLS-2$
                + "call: each write rebuilds its <extInfo>, so an intermediate value would drop what " //$NON-NLS-1$
                + "the final one keeps (a ValueList's item type, a Pages group's event handlers). " //$NON-NLS-1$
                + "Pass only the final value.").toJson(); //$NON-NLS-1$
        }
        boolean hasDirectTypeChange = directTypeChanges > 0;
        if (hasDirectTypeChange && hasExtInfoChange)
        {
            return ToolResult.error("Changing a form member's 'type' cannot be combined with a " //$NON-NLS-1$
                + "property that lives on its <extInfo> in the same call, because the 'type' decides " //$NON-NLS-1$
                + "which extInfo applies: on an ITEM that is a layout property (e.g. 'group' / " //$NON-NLS-1$
                + "'united' / 'showLeftMargin' / 'throughAlign' / 'currentRowUse' / 'representation'), " //$NON-NLS-1$
                + "on an ATTRIBUTE a type-specific one (e.g. a ValueList's 'itemValueType'). Change " //$NON-NLS-1$
                + "the 'type' first, then set the extInfo properties in a separate call.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Validates ONE form-member property and pairs the resulting {@link PreparedChange} with the
     * receiver it must be applied to. The receiver is decided reflectively: a DIRECT feature of the
     * member stays on the member; a property that lives on the member's nested {@code <extInfo>} (a
     * layout / kind-specific sub-object) is flagged {@code onExtInfo} so the caller routes the
     * {@code eSet} to the extInfo holder. The change is BUILT against the same extInfo the receiver was
     * chosen from (so the enum / boolean / ... value is coerced to the correct feature); an invalid
     * value throws {@link FormValidationException} BEFORE any mutation.
     */
    private HolderChange prepareFormMemberChange(MetadataScope scope, Version version, EObject member,
        JsonObject prop, MdNameNormalizer.Report normReport)
    {
        String property = asString(prop.get("name"));
        if (FormChoiceParameters.isProperty(property))
        {
            try
            {
                FormChoiceParameters.requireInputField(member, property);
            }
            catch (IllegalArgumentException e)
            {
                throw new FormValidationException(ToolResult.error(e.getMessage()).toJson());
            }
        }
        if ("dataPath".equalsIgnoreCase(asString(prop.get("name")))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            JsonElement value = prop.get(KEY_VALUE);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            {
                throw new FormValidationException(ToolResult.error(
                    "'dataPath' takes a non-empty string naming a form attribute or its field/collection path.") //$NON-NLS-1$
                    .toJson());
            }
            FormElementWriter.DataPathPlan plan =
                FormElementWriter.planDataPathChange(formOf(member), member, value.getAsString());
            if (plan.error() != null)
            {
                throw new FormValidationException(ToolResult.error(plan.error()).toJson());
            }
            return new HolderChange(false, PreparedChange.formDataPath(
                member.eClass().getEStructuralFeature("dataPath"), plan)); //$NON-NLS-1$
        }
        JsonObject normProp = normalizeFormProperty(member, prop);
        // The three retype guards below are about DATA BINDING - columns hanging off the member,
        // tables and fields bound to its data path. They identify their subject by NAME, and a
        // parameter shares no namespace with an attribute, so a parameter named like one answered
        // for the ATTRIBUTE and refused a legal retype with a message about a different member.
        // Nothing binds to a parameter by data path, so none of them applies (issue #396 review).
        if (!FormElementWriter.isFormParameter(member))
        {
            String orphanErr = refuseRetypeThatOrphansColumns(member, normProp);
            if (orphanErr != null)
            {
                throw new FormValidationException(orphanErr);
            }
            String listErr = refuseCollectionRetypeOnADynamicList(member, normProp);
            if (listErr != null)
            {
                throw new FormValidationException(listErr);
            }
            String boundItemsErr = refuseRetypeThatOrphansItems(member, normProp);
            if (boundItemsErr != null)
            {
                throw new FormValidationException(boundItemsErr);
            }
        }
        FormHolder holder = resolveFormHolder(member, asString(normProp.get("name"))); //$NON-NLS-1$
        List<PreparedChange> built = new ArrayList<>();
        // The extension-adopt hint (issue #262) is scoped to the mdclass 'type' property path
        // (validateAndPrepare, which threads the project's extension status through); a form member's
        // 'type' is a platform-type classifier (group/field/decoration kind), never a metadata reference,
        // so there is no unresolved-reference case here to hint. `project` is null: a form member is
        // never a ScheduledJob / EventSubscription, so validateMethodReference never dereferences it.
        String pErr = prepare(PrepareContext.forFormMember(scope, version, this::buildTypeDescription), member, holder.classifyExtInfo, normProp,
            built, normReport, false);
        if (pErr != null)
        {
            throw new FormValidationException(pErr);
        }
        // prepare() appends exactly one change on success.
        return new HolderChange(holder.onExtInfo, built.get(0));
    }

    /** What the user authorizes when a plain attribute is converted into a dynamic list. */
    private static ConsentPreview dynamicListRetypePreview(String normFqn)
    {
        return new ConsentPreview(
            "Convert a form attribute into a dynamic list", //$NON-NLS-1$
            "This replaces the attribute's data type with DynamicList. Any value the form held " //$NON-NLS-1$
                + "through it is dropped on the next database update.", //$NON-NLS-1$
            1, List.of(normFqn));
    }

    /**
     * The dynamic-list branch's PRE-CHECK, run before the consent gate (see {@link #gateFormRetype}).
     * ONE read transaction decides whether a prompt is warranted at all, because every case below
     * either cannot convert or is refused outright - and a denial would come back INSTEAD of the
     * actionable validation error (issue #295 review):
     *
     * <ul>
     * <li>the request carries neither {@code queryText} nor {@code mainTable}, so no list can be
     * created (a {@code customQuery}-only request is rejected downstream);</li>
     * <li>the attribute is absent (the write answers "attribute not found");</li>
     * <li>the {@code mainTable} FQN does not resolve - the same refusal the write would raise, only
     * without a dialog in front of it;</li>
     * <li>the attribute is ALREADY a dynamic list, so nothing is retyped;</li>
     * <li>it still owns columns the conversion would strand.</li>
     * </ul>
     *
     * @param fctx the resolved form edit context
     * @param config the configuration the main table is resolved against
     * @param ref the parsed form-member ref
     * @param queryText the requested custom query text, or {@code null}
     * @param mainTable the requested main-table FQN, or {@code null}
     * @return a ready JSON error to return as-is, {@code ""} to write without prompting, or
     *         {@code null} to ask
     */
    private static String dynamicListRetypePreflight(FormElementWriter.FormEditContext fctx, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        Configuration config, Version version, FormElementWriter.FormMemberRef ref, String queryText,
        String mainTable)
    {
        boolean couldConvert = (queryText != null && !queryText.isEmpty())
            || (mainTable != null && !mainTable.isEmpty());
        if (!couldConvert)
        {
            return ""; //$NON-NLS-1$
        }
        return FormElementWriter.readEditableForm(fctx, "DynamicListRetypeProbe", //$NON-NLS-1$
            (formModel, tx) -> dynamicListRetypeVerdict(HostKind.current() == HostKind.HEADLESS
                ? com.ditrix.edt.mcp.server.utils.ProjectContext.of(fctx.project.getName())
                    .resolveMetadataRootInTransaction().configuration() : config, version, formModel,
                FormElementWriter.resolveFormMember(formModel, ref), mainTable));
    }

    /** The implemented structural rebind route; tables must first release their old columns. */
    private static final String FORM_DATA_PATH_REBIND_ADVICE =
        "modify_metadata on each Field/Table FQN with properties:[{name:'dataPath', value:'<valid path>'}] " //$NON-NLS-1$
            + "(for a table, remove its columns first)"; //$NON-NLS-1$

    /**
     * Refuses a form-attribute retype that would strand its COLUMNS. Retyping a collection attribute
     * to a non-collection leaves its {@code FormAttributeColumn} children hanging off something that
     * cannot own them - the very shape {@code create_metadata} refuses to build - and EDT does not
     * flag it. Collection-to-collection (ValueTable to ValueTree) stays allowed. Issue #295.
     *
     * <p>The three things a retype can strand are refused on DIFFERENT conditions, because they break
     * for different reasons: columns and a table's row source need the attribute to keep holding ROWS,
     * so any non-collection type strands them; an item bound BELOW the attribute only breaks when the
     * new type has no members to resolve against, which is asked of
     * {@link #requestsOnlyMemberlessTypes} (issue #295 review).</p>
     *
     * @param member the form member being modified
     * @param normProp the normalized property (its name already aliased to {@code valueType})
     * @return a ready JSON error, or {@code null} when the change strands nothing
     */
    private static String refuseRetypeThatOrphansColumns(EObject member, JsonObject normProp)
    {
        if (!PROP_VALUE_TYPE.equalsIgnoreCase(asString(normProp.get("name")))) //$NON-NLS-1$
        {
            return null;
        }
        // Collection-to-collection (ValueTable to ValueTree) keeps an owner the columns can live on,
        // and keeps every path through the attribute resolving - nothing is stranded either way.
        if (requestsCollectionType(normProp))
        {
            return null;
        }
        if (!FormElementWriter.attributeColumnNames(member).isEmpty())
        {
            return orphanColumnsError(member);
        }
        // A collection with NO columns could still be the row source of a table, and the early return
        // on "no columns" let that through: the table stayed bound to something that no longer has
        // rows - the state createTable refuses to build, so the tool was stricter about creating a
        // form than about editing one into the same shape (issue #295 review).
        List<String> rowConsumers = FormElementWriter.rowConsumersBoundToAttribute(member);
        if (!rowConsumers.isEmpty())
        {
            return ToolResult.error("Form attribute '" + FormStructureReader.nameOf(member) //$NON-NLS-1$
                + "' is the row source of " + rowConsumers.size() + " table(s) (" //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", rowConsumers) + "), which a non-collection type cannot be. Delete " //$NON-NLS-1$ //$NON-NLS-2$
                + "them with delete_metadata, or use " + FORM_DATA_PATH_REBIND_ADVICE //$NON-NLS-1$
                + " first, or keep a collection type (ValueTable / ValueTree).").toJson(); //$NON-NLS-1$
        }
        // ...and the same for items bound BELOW it - but ONLY when the requested type is terminal: a
        // field on 'Rows.Price' survives the retype and keeps pointing at a name a String does not
        // have, while a REFERENCE type carries its members in the metadata, so 'Rows.Product.
        // Description' keeps resolving and createField deliberately builds exactly that. Firing on the
        // mere presence of a tail made this tool refuse a retype it was happy to create - creation
        // allowed, editing forbade (issue #295 review). The verdict is the requested TYPE's, asked of
        // the same MetadataTypeBuilder that decides what the retype will build.
        if (!requestsOnlyMemberlessTypes(normProp))
        {
            return null;
        }
        List<String> below = FormElementWriter.itemsBoundBelowAttribute(member);
        if (!below.isEmpty())
        {
            return ToolResult.error("Retyping '" + FormStructureReader.nameOf(member) //$NON-NLS-1$
                + "' would leave " + below.size() + " form item(s) (" + String.join(", ", below) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ") bound below it to a name the new type does not have. Delete them with " //$NON-NLS-1$
                + "delete_metadata, or use " + FORM_DATA_PATH_REBIND_ADVICE + " first.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        return null;
    }

    /**
     * Whether a {@code valueType} spec names an IN-MEMORY collection kind (ValueTable / ValueTree).
     * One reader for the two guards that both turn on "is the caller asking for a collection".
     *
     * @param normProp the normalized property (its name already aliased to {@code valueType})
     * @return {@code true} when at least one requested type item is a collection kind
     */
    private static boolean requestsCollectionType(JsonObject normProp)
    {
        for (String kind : requestedTypeKinds(normProp))
        {
            if (MetadataTypeBuilder.isCollectionKind(kind))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a {@code valueType} spec asks for types that ALL own no addressable member - the only
     * shape that really strands a data path running through the attribute. The twin of
     * {@link #requestsCollectionType}: both answer a question about the REQUESTED type, and both put
     * it to {@link MetadataTypeBuilder}, the place that decides what the retype will actually build.
     *
     * <p>An unrecognizable spec (no {@code types} array, an empty one, a kind this tool cannot build)
     * answers {@code false}: the guard refuses only what is PROVABLY stranded, and the payload's own
     * validation answers the malformed case with a type error.</p>
     *
     * @param normProp the normalized property (its name already aliased to {@code valueType})
     * @return {@code true} when at least one type is requested and every one of them is memberless
     */
    private static boolean requestsOnlyMemberlessTypes(JsonObject normProp)
    {
        List<String> kinds = requestedTypeKinds(normProp);
        if (kinds.isEmpty())
        {
            return false;
        }
        for (String kind : kinds)
        {
            if (!MetadataTypeBuilder.isMemberlessType(kind))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code kind} tokens a {@code valueType} spec asks for, in spec order, or an EMPTY list when
     * the spec carries no {@code types} array. One reader for every guard that turns on WHAT the
     * caller asked for, so they cannot read the payload differently.
     *
     * @param normProp the normalized property (its name already aliased to {@code valueType})
     * @return the requested kinds; an entry is {@code null} when the item declares none
     */
    private static List<String> requestedTypeKinds(JsonObject normProp)
    {
        List<String> kinds = new ArrayList<>();
        JsonElement spec = normProp.get(KEY_VALUE);
        if (spec == null || !spec.isJsonObject())
        {
            return kinds;
        }
        JsonElement types = spec.getAsJsonObject().get("types"); //$NON-NLS-1$
        if (types == null || !types.isJsonArray())
        {
            return kinds;
        }
        for (JsonElement item : types.getAsJsonArray())
        {
            kinds.add(item.isJsonObject() ? asString(item.getAsJsonObject().get("kind")) : null); //$NON-NLS-1$
        }
        return kinds;
    }

    /**
     * Refuses a retype to a collection that would leave EXISTING form items bound below the attribute
     * to a name it will not own as a column. The mirror of
     * {@link #refuseRetypeThatOrphansColumns}: that one guards what hangs BELOW the attribute, this
     * one what already points INTO it. Once the attribute holds rows a dotted path under it names a
     * COLUMN, so a field carrying {@code Object.Number} across a retype of {@code Object} to
     * ValueTable ends up in exactly the shape {@code createField} refuses to build - silently, since
     * nothing revalidates existing items (issue #295 review).
     *
     * <p>A COMPOSITE spec is not that shape. {@code {ValueTable, CatalogRef.Products}} keeps
     * {@code Rows.Product.Description} resolving through its REFERENCE half - which is exactly why
     * {@code createField} accepts that path - so firing on "a collection is mentioned" refused a
     * retype the creator is happy to build, one level below the same defect this branch already
     * fixed for terminal types. The question is put to
     * {@link FormElementWriter#carriesMembersOutsideThisModel}, the per-type rule the nested-address
     * classifier itself applies, so creating and editing cannot answer it differently (issue #295
     * review).</p>
     *
     * @param member the form member being modified
     * @param normProp the normalized property (its name already aliased to {@code valueType})
     * @return a ready JSON error naming the items, or {@code null}
     */
    private static String refuseRetypeThatOrphansItems(EObject member, JsonObject normProp)
    {
        if (!PROP_VALUE_TYPE.equalsIgnoreCase(asString(normProp.get("name"))) //$NON-NLS-1$
            || !requestsCollectionType(normProp)
            || FormElementWriter.carriesMembersOutsideThisModel(requestedTypeKinds(normProp)))
        {
            return null;
        }
        List<String> bound = FormElementWriter.itemsBoundBelowAttribute(member);
        if (bound.isEmpty())
        {
            return null;
        }
        return ToolResult.error("Retyping '" + FormStructureReader.nameOf(member) + "' to a " //$NON-NLS-1$ //$NON-NLS-2$
            + "collection would leave " + bound.size() + " form item(s) (" //$NON-NLS-1$ //$NON-NLS-2$
            + String.join(", ", bound) + ") bound to a name the collection does not own: under a " //$NON-NLS-1$ //$NON-NLS-2$
            + "ValueTable / ValueTree a dotted data path addresses a COLUMN. Delete those items with " //$NON-NLS-1$
            + "delete_metadata, or use " + FORM_DATA_PATH_REBIND_ADVICE + " first.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Refuses giving a collection type to an attribute that is already a DYNAMIC LIST. Writing only
     * the {@code valueType} would leave the {@code DynamicListExtInfo} attached, so the exported
     * attribute would be a collection AND still answer {@code isDynamicListAttribute} - it could then
     * take columns while a stale query / main table kept describing it. That state cannot exist in the
     * designer, and this branch is what made it reachable at all (issue #295 review).
     *
     * <p>The tool REFUSES rather than dropping the list configuration silently, for the same reason
     * {@link #refuseRetypeThatOrphansColumns} refuses instead of deleting columns: the ext-info holds
     * content the caller authored (the query text, the main table), the request says "make it a
     * collection" and not "discard my query", and the consent prompt the caller answered speaks of a
     * type change only - stripping the list under it would destroy something nobody authorized.</p>
     *
     * @param member the form member being modified
     * @param normProp the normalized property (its name already aliased to {@code valueType})
     * @return a ready JSON error, or {@code null} when nothing conflicts
     */
    private static String refuseCollectionRetypeOnADynamicList(EObject member, JsonObject normProp)
    {
        if (!PROP_VALUE_TYPE.equalsIgnoreCase(asString(normProp.get("name"))) //$NON-NLS-1$
            || !requestsCollectionType(normProp)
            || !FormElementWriter.isDynamicListAttribute(member))
        {
            return null;
        }
        String name = FormStructureReader.nameOf(member);
        return ToolResult.error("Form attribute '" + name + "' is configured as a DYNAMIC LIST (it " //$NON-NLS-1$ //$NON-NLS-2$
            + "carries a DynamicListExtInfo with its query / main table), and a dynamic list cannot " //$NON-NLS-1$
            + "also hold an in-memory collection type: the attribute would be exported as a collection " //$NON-NLS-1$
            + "while still counting as a list, with the old query left describing it. The list " //$NON-NLS-1$
            + "configuration is not dropped for you - delete the attribute with delete_metadata " //$NON-NLS-1$
            + "('...Attribute." + name + "') and create it again with the collection type, or keep the " //$NON-NLS-1$ //$NON-NLS-2$
            + "dynamic list and set its data with 'queryText' / 'mainTable'.").toJson(); //$NON-NLS-1$
    }

    /**
     * Resolves the write receiver for a form-member property named {@code propName}: whether it lives
     * on the member directly or on the member's nested {@code <extInfo>}, plus the extInfo instance the
     * property is classified against. A DIRECT feature wins (mirroring {@link
     * MetadataPropertyIntrospector#findFeature(EObject, EObject, String)}). When the element carries no
     * extInfo instance yet but CAN (a form group's layout props live under an as-yet-uncreated
     * {@code <extInfo>}), the property is classified against a THROWAWAY (unattached) instance of the
     * element's concrete extInfo EClass, so an extInfo property is visible WITHOUT mutating the model
     * during validation - the real extInfo holder is created (and reused) at apply time via
     * {@link FormElementWriter#ensureExtInfo}. Fully reflective; a no-op for an element with no extInfo.
     */
    static FormHolder resolveFormHolder(EObject member, String propName)
    {
        EObject extInfo = extInfoOf(member);
        // A form group whose live extInfo no longer matches its `type` is STALE (the type was changed):
        // classify against the type-AUTHORITATIVE extInfo, not the stale holder, so a property is
        // validated against the class ensureExtInfo will actually (re)create at apply time (#235 review).
        EClass authoritative = FormElementWriter.resolveExtInfoEClass(member);
        boolean stale = extInfo != null && authoritative != null
            && !extInfo.eClass().getName().equals(authoritative.getName());
        EObject classifyAgainst = stale ? null : extInfo;
        PropertyInfo info = MetadataPropertyIntrospector.findFeature(member, classifyAgainst, propName);
        if (info == null && classifyAgainst == null && propName != null && !propName.isEmpty()
            && authoritative != null && !authoritative.isAbstract() && authoritative.getEPackage() != null)
        {
            EObject probe = authoritative.getEPackage().getEFactoryInstance().create(authoritative);
            PropertyInfo onProbe = MetadataPropertyIntrospector.findFeature(member, probe, propName);
            if (onProbe != null && onProbe.onExtInfo)
            {
                return new FormHolder(true, probe);
            }
        }
        return new FormHolder(info != null && info.onExtInfo, classifyAgainst);
    }

    /**
     * The element's nested {@code <extInfo>} EObject, read reflectively from the single-valued
     * {@code extInfo} containment reference, or {@code null} when the element has no such feature (an
     * mdclass object) or the slot is empty. Self-contained (no form-model import).
     */
    private static EObject extInfoOf(EObject element)
    {
        EStructuralFeature feature = element.eClass().getEStructuralFeature("extInfo"); //$NON-NLS-1$
        if (feature instanceof EReference && !feature.isMany())
        {
            Object value = element.eGet(feature);
            if (value instanceof EObject)
            {
                return (EObject)value;
            }
        }
        return null;
    }

    /**
     * The move property names (the structural re-parent / reorder of a form item): {@code parent}
     * (the destination container - a group, the {@code AutoCommandBar} token, a table - or the form
     * name / blank for the form root) and {@code position}
     * (the destination order: {@code first} / {@code last} / {@code before:<name>} / {@code after:<name>}
     * / a 0-based integer index). They are bilingual: ru {@code roditel} / ru {@code poziciya}.
     */
    private static final String PROP_PARENT = "parent"; //$NON-NLS-1$
    private static final String PROP_POSITION = "position"; //$NON-NLS-1$
    // ru "родитель" (roditel) / "позиция" (poziciya) - pure-ASCII source (matching the rest of the project).
    private static final String RU_PROP_PARENT =
        MetadataLanguageUtils.cp(0x0440, 0x043e, 0x0434, 0x0438, 0x0442, 0x0435, 0x043b, 0x044c);
    private static final String RU_PROP_POSITION =
        MetadataLanguageUtils.cp(0x043f, 0x043e, 0x0437, 0x0438, 0x0446, 0x0438, 0x044f);

    /** Whether a property NAME is the {@code parent} move property (English or Russian). */
    private static boolean isParentProp(String name)
    {
        return PROP_PARENT.equalsIgnoreCase(name) || RU_PROP_PARENT.equalsIgnoreCase(name);
    }

    /** Whether a property NAME is the {@code position} move property (English or Russian). */
    private static boolean isPositionProp(String name)
    {
        return PROP_POSITION.equalsIgnoreCase(name) || RU_PROP_POSITION.equalsIgnoreCase(name);
    }

    /** Whether any property in the list is a move property ({@code parent} / {@code position}). */
    private static boolean hasMoveProperty(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (isParentProp(name) || isPositionProp(name))
            {
                return true;
            }
        }
        return false;
    }

    /** The rebind property names. {@code procedure} (alias {@code handler}) rebinds a handler's BSL
     * procedure; {@code command} (alias {@code commandName}) re-points a button at a form command.
     * {@code PROP_HANDLER} doubles as an EventSubscription's method-reference property (guarded by
     * {@link MethodReferenceValidator} on the mdclass path). */
    private static final String PROP_PROCEDURE = "procedure"; //$NON-NLS-1$
 //$NON-NLS-1$
    private static final String PROP_COMMAND = "command"; //$NON-NLS-1$
    private static final String PROP_COMMAND_NAME = "commandName"; //$NON-NLS-1$

    /**
     * The new BSL procedure name from a {@code procedure} (or {@code handler} alias) property on a
     * handler-rebind call, or {@code null} when no such property is present. The same key
     * {@code create_metadata} accepts when binding a handler.
     */
    private static String handlerProcedureValue(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (PROP_PROCEDURE.equalsIgnoreCase(name) || PROP_HANDLER.equalsIgnoreCase(name))
            {
                return asString(prop.get(KEY_VALUE));
            }
        }
        return null;
    }

    /**
     * The name of the first property that is NOT the handler-rebind property ({@code procedure} /
     * {@code handler} alias), or {@code null} when the list carries only rebind properties. Used to
     * REJECT a handler-rebind call that mixes in other property changes (which the rebind path would
     * otherwise silently drop). Package-visible for tests.
     */
    static String firstNonHandlerRebindProperty(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (!PROP_PROCEDURE.equalsIgnoreCase(name) && !PROP_HANDLER.equalsIgnoreCase(name))
            {
                return name;
            }
        }
        return null;
    }

    /**
     * The first property name that asks to set a form COMMAND's action, or {@code null} when none
     * does (issue #628). A command's action is its handler member, not a property, so these names are
     * refused with that member's route on a command: the designer's {@code Action} - in any case, or
     * its Russian spelling: the leaf {@link FormElementWriter#isActionToken} accepts - and the
     * handler-rebind names {@code procedure} / {@code handler}. EXACT names only, so a real
     * FormCommand property that merely starts alike ({@code actionPurpose}) still reaches the
     * property pipeline. Package-visible for tests.
     *
     * @param properties the requested property changes
     * @return the first such name as the caller spelled it, or {@code null}
     */
    static String firstCommandActionProperty(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (PROP_PROCEDURE.equalsIgnoreCase(name) || PROP_HANDLER.equalsIgnoreCase(name)
                || FormElementWriter.isActionToken(name))
            {
                return name;
            }
        }
        return null;
    }

    /**
     * The action-like property (see {@link #firstCommandActionProperty}) a batch sends to a form
     * member of {@code kind}, or {@code null} (issue #628). Only a form COMMAND has an action: on any
     * other kind these names are ordinary unknown properties, refused by the property pipeline with
     * that member's own assignable list - never with a {@code ...Handler.Action} address the member
     * cannot have. Package-visible for tests.
     *
     * @param kind the member's kind ({@link FormElementWriter#kindForToken}), may be {@code null}
     * @param properties the requested property changes
     * @return the first action-like name on a command, or {@code null}
     */
    static String commandActionPropertyOf(FormElementWriter.Kind kind, List<JsonObject> properties)
    {
        return kind == FormElementWriter.Kind.COMMAND ? firstCommandActionProperty(properties) : null;
    }

    /**
     * The refusal for an action-like property on a form COMMAND (issue #628): instead of the bare
     * assignable list - whose {@code actionPurpose} the reporter read as "the action cannot be set
     * through MCP" - it names the handler member that IS the command's action and the routes to it:
     * create_metadata binds it, modify_metadata with a {@code procedure} property rebinds a
     * single-handler action, and an action without one - an extension's interception list, which
     * {@link FormElementWriter#rebindHandler} cannot rename - is removed with delete_metadata, which
     * takes an action of any shape. The text is the same whatever the command's action is, so each
     * clause says which action it applies to. Package-visible for tests.
     *
     * @param commandFqn the FQN of the command the property was sent to
     * @param propertyName the property name as the caller spelled it
     * @return the ready JSON error
     */
    static String commandActionPropertyRefusal(String commandFqn, String propertyName)
    {
        String handlerFqn = commandFqn + ".Handler.Action"; //$NON-NLS-1$
        return ToolResult.error("Property '" + propertyName + "' is not assignable on FormCommand: " //$NON-NLS-1$ //$NON-NLS-2$
            + "a form command's action (the designer's Action property, the BSL procedure the command " //$NON-NLS-1$
            + "runs) is its handler member '" + handlerFqn + "'. Bind it with create_metadata on that " //$NON-NLS-1$ //$NON-NLS-2$
            + "FQN (the optional 'procedure' property names the procedure and defaults to the command " //$NON-NLS-1$
            + "name); if the command already has an action, rebind it with modify_metadata on that FQN " //$NON-NLS-1$
            + "and a 'procedure' property. An action that holds no single handler (an extension's " //$NON-NLS-1$
            + "interception list) cannot be rebound - remove it with delete_metadata on that FQN.") //$NON-NLS-1$
            .toJson();
    }

    /** Whether any property in the list re-points a button at a form command ({@code command}). */
    private static boolean hasCommandProperty(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (PROP_COMMAND.equalsIgnoreCase(name) || PROP_COMMAND_NAME.equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves / reorders a form ITEM addressed by {@code ref} (a field / group / decoration / button /
     * table), expressed as the {@code parent} and/or {@code position} move properties. Resolves the
     * MD-form, opens ONE BM write transaction on the re-fetched content form, re-parents / reorders the
     * item via {@link FormElementWriter#moveItem} (which rejects an ambiguous / missing item, an
     * unknown parent - the error advertises the {@code AutoCommandBar} token - a placement the
     * designer forbids and a containment cycle, rolling the tx back), then
     * force-exports the CONTENT form to its {@code Form.form} on disk - the same persistence path the
     * property-modify branch uses. Position semantics match the dedicated move primitive exactly (the
     * integer index is the desired FINAL 0-based position).
     */
    private String moveFormItem(ProjectContext ctx, String normFqn, // NOSONAR form-move orchestration: structural validation + move dispatch; the mutating write stays inline, further extraction deferred
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties)
    {
        // A move addresses a form ITEM only - never an attribute / command (which are not in the items
        // tree and have no position / parent).
        FormElementWriter.Kind kind = FormElementWriter.kindForToken(ref.kindToken);
        if (kind == FormElementWriter.Kind.ATTRIBUTE || kind == FormElementWriter.Kind.COMMAND
            || kind == FormElementWriter.Kind.COLUMN
            || kind == FormElementWriter.Kind.PARAMETER)
        {
            return ToolResult.error("'parent' / 'position' move a form ITEM (field / group / " //$NON-NLS-1$
                + "decoration / button / table); a form " + ref.kindToken + " is not positioned. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Address the item by its FQN, e.g. 'Type.Object.Form.FormName.Field.Price'.").toJson(); //$NON-NLS-1$
        }

        // A move is structural - it must not be mixed with ordinary property changes in one call.
        String targetParent = null;
        boolean hasParent = false;
        String position = null;
        boolean hasPosition = false;
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (isParentProp(name))
            {
                targetParent = asString(prop.get(KEY_VALUE));
                hasParent = true;
            }
            else if (isPositionProp(name))
            {
                position = asString(prop.get(KEY_VALUE));
                hasPosition = true;
            }
            else
            {
                return ToolResult.error("A move ('parent' / 'position') cannot be combined with other " //$NON-NLS-1$
                    + "property changes ('" + name + "') in one call. Move the item first, then modify " //$NON-NLS-1$ //$NON-NLS-2$
                    + "its properties in a separate call.").toJson(); //$NON-NLS-1$
            }
        }
        if (!hasParent && !hasPosition)
        {
            return ToolResult.error("Nothing to move: provide 'parent' (to re-parent) and/or " //$NON-NLS-1$
                + "'position' (to reorder).").toJson(); //$NON-NLS-1$
        }
        // A re-parent with no explicit position appends to the destination (position stays null); a pure
        // reorder keeps the current parent (targetParent stays null).
        final String targetParentFinal;
        if (!hasParent)
        {
            targetParentFinal = null;
        }
        else
        {
            targetParentFinal = targetParent == null ? "" : targetParent; //$NON-NLS-1$
        }
        final String positionFinal = position;

        final String itemName = ref.name;
        final String[] destination = new String[1];
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.scope, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address a form item as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.<Kind>.Name' or 'CommonForm.FormName.<Kind>.Name'."); //$NON-NLS-1$
            final String mdFormName = HostKind.current() == HostKind.HEADLESS
                ? fctx.read(MdObject::getName) : fctx.mdForm.getName();
            persisted = FormElementWriter.writeEditableForm(fctx, "MoveFormItem", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    // The addressed move goes through the SAME kind-aware resolver the property /
                    // delete / read paths use (issue #343): 'Button.<a field>' with a 'parent'
                    // property must not move the field. The strict variant additionally rejects an
                    // ambiguous name instead of moving the first match.
                    EObject item = FormElementWriter.resolveUniqueFormMember(formModel, ref);
                    if (item == null)
                    {
                        throw new FormValidationException(ToolResult.error("Form item not found: " //$NON-NLS-1$
                            + itemName + " (kind '" + ref.kindToken + "') on " + ref.formPath //$NON-NLS-1$ //$NON-NLS-2$
                            + advisedOr(FormElementWriter.kindMismatchAdvice(formModel, ref.kindToken,
                                itemName, normFqn),
                                ". Use get_metadata_details on the form to inspect its items.")) //$NON-NLS-1$
                            .toJson());
                    }
                    destination[0] = FormElementWriter.moveResolvedItem(formModel, item, itemName,
                        targetParentFinal, positionFinal, mdFormName);
                });
        }
        catch (Exception e)
        {
            return moveFormItemError(e);
        }

        List<String> applied = new ArrayList<>();
        if (hasParent)
        {
            applied.add(PROP_PARENT);
        }
        if (hasPosition)
        {
            applied.add(PROP_POSITION);
        }
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted)
            .put("destination", destination[0]) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, "Moved form item '" + itemName + "' to " + destination[0]) //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Maps a failure from the {@link #moveFormItem} write transaction to its error JSON: a structured
     * {@link FormValidationException} payload when present (the move primitive rejected the item / parent /
     * placement and rolled the tx back), otherwise a generic "Failed to move form item" error built from
     * the unwrapped cause message. Mirrors the catch arm of {@code moveFormItem} verbatim.
     */
    private String moveFormItemError(Exception e)
    {
        String validationJson = FormValidationException.jsonOf(e);
        if (validationJson != null)
        {
            return validationJson;
        }
        Refusals.log("Error moving form item", e); //$NON-NLS-1$
        return ToolResult.error("Failed to move form item: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
    }

    /**
     * REBINDS an existing event handler (addressed by a handler FQN, {@code ...Handler.Event} at form
     * or item level) to a different BSL procedure {@code procName}. Resolves the MD-form, opens ONE BM
     * write transaction on the re-fetched content form, resolves the handler's CONTAINER via
     * {@link FormElementWriter#resolveHandlerContainer} (the form root, the named item, or the form
     * COMMAND for a {@code ...Command.C.Handler.Action} FQN - so a command's Action procedure is
     * rebindable too), re-points the existing handler via {@link
     * FormElementWriter#rebindHandler} (which fails clearly when no handler for the event exists, so the
     * tx rolls back), then force-exports the CONTENT form to its {@code Form.form} on disk - the same
     * persistence path the property-modify branch uses. Does NOT bind a NEW event (that is
     * create_metadata's job); a single {@code procedure} property is the whole change.
     */
    private String rebindFormHandler(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, String procName)
    {
        final String eventName = ref.name;
        final boolean commandOwner = ref.isItemLevel()
            && FormElementWriter.kindForToken(ref.itemKindToken) == FormElementWriter.Kind.COMMAND;
        // The advice may quote a corrected handler address, and whether the corrected owner really
        // carries that event is a question only the platform type can answer - hence the version.
        final Version version = platformVersionOf(ctx);
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.scope, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address a handler as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.Handler.Event' or " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.<ItemKind>.<ItemName>.Handler.Event'."); //$NON-NLS-1$
            persisted = FormElementWriter.writeEditableForm(fctx, "RebindFormHandler", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    // Form-level handlers live on the form root; item-level handlers on the named
                    // item; a COMMAND ref (...Command.C.Handler.Action) on the form command - the
                    // same resolution create_metadata / delete_metadata use.
                    EObject container = FormElementWriter.resolveHandlerContainer(formModel, ref);
                    if (container == null)
                    {
                        // The OWNER's kind is resolved too (issue #343): an item of that name under
                        // another kind is named, with the corrected address. The message then names
                        // the KIND that found nothing, so it cannot read as a lie about the element.
                        String advice =
                            FormElementWriter.handlerOwnerKindMismatchAdvice(formModel, ref,
                                normFqn, version);
                        throw new FormValidationException(ToolResult.error((commandOwner
                            ? "Form command not found: " : "Form item not found: ") + ref.itemName //$NON-NLS-1$ //$NON-NLS-2$
                            + (advice.isEmpty()
                                ? ". Use get_metadata_details to inspect the form items." //$NON-NLS-1$
                                : " (kind '" + ref.itemKindToken + "')" + advice)).toJson()); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    String err = FormElementWriter.rebindHandler(container, eventName, procName);
                    if (err != null)
                    {
                        throw new FormValidationException(ToolResult.error(err).toJson());
                    }
                });
        }
        catch (Exception e)
        {
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Activator.logError("Error rebinding form handler", e); //$NON-NLS-1$
            return ToolResult.error("Failed to rebind form handler: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)) //$NON-NLS-1$
            .put(KEY_APPLIED, java.util.Collections.singletonList(PROP_PROCEDURE))
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Rebound the handler for event '" + eventName + "' to procedure '" //$NON-NLS-1$ //$NON-NLS-2$
                + procName + "'") //$NON-NLS-1$
            .toJson();
    }

    /**
     * RE-POINTS an existing button (a Button form item) at a different (existing) form command. A
     * button's {@code commandName} references a FormCommand (a form-model object, not an mdclass
     * object), so it is not introspector-assignable and is rebound here. Resolves the MD-form, opens ONE
     * BM write transaction on the re-fetched content form, resolves the button and re-points it via
     * {@link FormElementWriter#rebindButtonCommand} (which validates the command exists, rolling the tx
     * back otherwise), then force-exports the CONTENT form to its {@code Form.form} on disk. A
     * {@code command} change is structural-by-reference and must not be mixed with other property
     * changes in one call.
     */
    private String rebindButtonCommand(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties)
    {
        String commandName = null;
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (PROP_COMMAND.equalsIgnoreCase(name) || PROP_COMMAND_NAME.equalsIgnoreCase(name))
            {
                commandName = asString(prop.get(KEY_VALUE));
            }
            else
            {
                return ToolResult.error("Re-pointing a button's command ('command') cannot be combined " //$NON-NLS-1$
                    + "with other property changes ('" + name + "') in one call. Rebind the command " //$NON-NLS-1$ //$NON-NLS-2$
                    + "first, then modify the button's properties in a separate call.").toJson(); //$NON-NLS-1$
            }
        }
        if (commandName == null || commandName.isEmpty())
        {
            return ToolResult.error("Provide the form command to point the button at in the 'command' " //$NON-NLS-1$
                + "property (e.g. {name:'command', value:'Refresh'}).").toJson(); //$NON-NLS-1$
        }

        final String buttonName = ref.name;
        final String commandNameFinal = commandName;
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.scope, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address a button as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.Button.Name' or 'CommonForm.FormName.Button.Name'."); //$NON-NLS-1$
            persisted = FormElementWriter.writeEditableForm(fctx, "RebindButtonCommand", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    // Strict resolution: the KIND is verified by the shared resolver (issue #343), so
                    // 'Button.<a field>' with a 'command' property cannot reach the field, and an
                    // AMBIGUOUS button name (several items by that name anywhere in the form-item
                    // tree) is rejected instead of silently re-pointing the first match (the strict
                    // lookup throws; the tx rolls back).
                    EObject button = FormElementWriter.resolveUniqueFormMember(formModel, ref);
                    if (button == null)
                    {
                        throw new FormValidationException(ToolResult.error("Form button not found: " //$NON-NLS-1$
                            + buttonName + " (kind '" + ref.kindToken + "') on " + ref.formPath //$NON-NLS-1$ //$NON-NLS-2$
                            + advisedOr(FormElementWriter.kindMismatchAdvice(formModel, ref.kindToken,
                                buttonName, normFqn),
                                ". Use get_metadata_details to inspect the form items.")).toJson()); //$NON-NLS-1$
                    }
                    String err =
                        FormElementWriter.rebindButtonCommand(formModel, button, commandNameFinal);
                    if (err != null)
                    {
                        throw new FormValidationException(ToolResult.error(err).toJson());
                    }
                });
        }
        catch (Exception e)
        {
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Activator.logError("Error rebinding button command", e); //$NON-NLS-1$
            return ToolResult.error("Failed to rebind button command: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)) //$NON-NLS-1$
            .put(KEY_APPLIED, java.util.Collections.singletonList(PROP_COMMAND))
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Re-pointed button '" + buttonName + "' at command '" //$NON-NLS-1$ //$NON-NLS-2$
                + commandNameFinal + "'") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Refuses the structural form property a client must not set as a value: {@code id} (the
     * form-wide-unique item id is allocated automatically). The {@code name} (rename) property is
     * already refused by {@link #prepare}. Returns a JSON error to reject, or {@code null} to allow.
     */
    private static String guardFormProperty(JsonObject prop)
    {
        String name = asString(prop.get("name")); //$NON-NLS-1$
        if ("id".equalsIgnoreCase(name)) //$NON-NLS-1$
        {
            return ToolResult.error("The form item 'id' is allocated automatically and must stay " //$NON-NLS-1$
                + "form-wide unique - it cannot be set.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Maps the friendly {@code type} alias to a form attribute's real {@code valueType} feature so a
     * form attribute's data type is set with the same {@code {name:'type', value:{types:[...]}}} shape
     * mdclass attributes use. Returns the original prop unchanged when no alias applies.
     */
    private static JsonObject normalizeFormProperty(EObject member, JsonObject prop)
    {
        String name = asString(prop.get("name")); //$NON-NLS-1$
        if ("type".equalsIgnoreCase(name) //$NON-NLS-1$
            && member.eClass().getEStructuralFeature("type") == null //$NON-NLS-1$
            && member.eClass().getEStructuralFeature(PROP_VALUE_TYPE) != null)
        {
            JsonObject copy = prop.deepCopy();
            copy.addProperty("name", PROP_VALUE_TYPE); //$NON-NLS-1$
            return copy;
        }
        return prop;
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * A {@link PreparedChange} paired with WHERE it must be applied: {@code onExtInfo == false} targets
     * the form member itself (a direct feature); {@code onExtInfo == true} targets the member's nested
     * {@code <extInfo>} holder (a layout / kind-specific property - a UsualGroup's grouping / united /
     * ... live under {@code <extInfo>}). Threading the receiver per property lets a mixed direct +
     * extInfo batch apply each change to the correct EObject inside the one form write transaction.
     */
    static final class HolderChange
    {
        private final boolean onExtInfo;
        private final PreparedChange change;

        HolderChange(boolean onExtInfo, PreparedChange change)
        {
            this.onExtInfo = onExtInfo;
            this.change = change;
        }

        /** @return the prepared change (package-visible for tests) */
        PreparedChange change()
        {
            return change;
        }
    }

    /**
     * The resolved receiver for a form-member property: {@code onExtInfo} tells the caller whether the
     * write goes to the member's nested {@code <extInfo>} holder (vs the member itself), and
     * {@code classifyExtInfo} is the extInfo instance the property was classified against (a live
     * instance, a throwaway probe for an as-yet-uncreated extInfo, or {@code null} for a pure-direct
     * member) - passed to {@link #prepare} so the value is coerced to the correct feature. Carries no
     * behaviour. Package-visible so {@link #resolveFormHolder} is unit-testable.
     */
    static final class FormHolder
    {
        final boolean onExtInfo;
        final EObject classifyExtInfo;

        FormHolder(boolean onExtInfo, EObject classifyExtInfo)
        {
            this.onExtInfo = onExtInfo;
            this.classifyExtInfo = classifyExtInfo;
        }
    }

    private static EObject childByName(EObject owner, EStructuralFeature feature, String name)
    {
        Object value = owner.eGet(feature);
        if (value instanceof EList<?>)
        {
            for (Object element : (EList<?>)value)
            {
                if (element instanceof MdObject child && name.equalsIgnoreCase(child.getName()))
                {
                    return child;
                }
            }
        }
        return null;
    }

    private static List<String> externalLocalesForWrite(ProjectContext ctx, List<String> declaredCodes)
    {
        return HostKind.current() == HostKind.HEADLESS && ctx.scope.isExternalObjects()
            ? readPreparation(ctx.project, () -> MetadataLanguageUtils.localesInUse(preparationScope(ctx).configuration(), declaredCodes)) : null;
    }

    private static List<String> localesInUseDuringWrite(ProjectContext ctx, IBmTransaction tx,
        List<String> declaredCodes, List<String> externalLocales)
    {
        if (HostKind.current() != HostKind.HEADLESS)
            return MetadataLanguageUtils.localesInUse(ctx.config, declaredCodes);
        if (ctx.scope.isExternalObjects()) return externalLocales;
        if (!(tx.getTopObjectByFqn("Configuration") instanceof Configuration config))
            throw Refusals.state("Configuration disappeared. Re-read get_metadata_details and retry.");
        return MetadataLanguageUtils.localesInUse(config, declaredCodes);
    }

    /**
     * Collects what a modify wrote to LOCALIZED properties, so the result can tell the caller which
     * locale was actually used and which declared locales still owe a translation. Issue #298.
     *
     * <p>A locale counts as MISSING when the configuration USES it (its own synonym is filled in
     * for it) and AT LEAST ONE of the localized properties written by this call has no value for it -
     * the caller is told there is work left, without having to re-read the object. The present locales are read from the model right after the changes are applied, so an
     * object that already carried other translations is reported correctly (unlike a create, where
     * the map is necessarily fresh).
     */
    private static final class LocalizedWriteReport
    {
        private final Set<String> languagesUsed = new LinkedHashSet<>();
        private final Set<String> missing = new LinkedHashSet<>();
        /** Per PROPERTY (one receiver's one feature): the locales this call left holding old text. */
        private final Map<String, Set<String>> staleByProperty = new LinkedHashMap<>();
        /** Per PROPERTY: the locales this call actually wrote - the ones that are NOT left behind. */
        private final Map<String, Set<String>> writtenByProperty = new LinkedHashMap<>();
        /** (receiver, feature, language) -> the text that was there BEFORE this call touched it. */
        private final Map<String, String> textBefore = new LinkedHashMap<>();
        /** (receiver, feature, language) -> the text the LAST change in this call writes there. */
        private final Map<String, String> textAfter = new LinkedHashMap<>();
        private boolean wrote;
        private boolean unusedLocale;

        /**
         * Records which of the localized changes will OVERWRITE existing text. Call INSIDE the write
         * transaction, BEFORE the changes are applied.
         * <p>
         * The distinction decides whether the OTHER languages went stale. Overwriting the 'en' text
         * of a property leaves the 'fr' one describing the previous value - that is the case worth
         * reporting. FILLING IN a previously missing 'fr' does not touch 'en' at all: the English
         * text is as current as it was, and calling it stale would be an invented warning.
         *
         * @param target the object the changes are about to be applied to
         * @param changes the prepared changes
         */
        @SuppressWarnings("unchecked")
        void rememberPreState(EObject target, List<PreparedChange> changes)
        {
            for (PreparedChange change : changes)
            {
                if (!change.isLocalized())
                {
                    continue;
                }
                Object map = target.eGet(change.feature());
                if (!(map instanceof EMap))
                {
                    continue;
                }
                String had = ((EMap<String, String>)map).map().get(change.language());
                String key = preStateKey(target, change);
                // The FIRST pre-state seen for a key is the real "before": a batch may write the
                // same property and language more than once, and the later writes see what the
                // earlier ones left, not what the call started from.
                textBefore.putIfAbsent(key, had == null ? "" : had); //$NON-NLS-1$
                // The LAST write is what the model ends up with, and only the end state can make
                // another language out of date. Writing 'New' and then putting 'Old' back leaves
                // the property exactly as it was, so nothing behind it went stale.
                textAfter.put(key, change.localizedValue == null ? "" : change.localizedValue); //$NON-NLS-1$
            }
        }

        /**
         * Identity of one (receiver, feature, language) triple.
         * <p>
         * The receiver is part of it because a form call writes the same feature name on DIFFERENT
         * objects (a title on the member and one on its extInfo, or on two different items), and the
         * language because that is what a single change writes.
         */
        private static String preStateKey(EObject target, PreparedChange change)
        {
            return propertyKey(target, change) + "/" + change.language(); //$NON-NLS-1$
        }

        /**
         * Whether this call left that (receiver, feature, language) holding DIFFERENT text than it
         * found there. Empty before means there was nothing to make out of date; equal before and
         * after means the value never moved, however many writes passed through it.
         */
        private boolean replaced(String key)
        {
            String before = textBefore.get(key);
            return before != null && !before.isEmpty() && !before.equals(textAfter.get(key));
        }

        /** Identity of one PROPERTY - one receiver's one feature; staleness is decided per property. */
        private static String propertyKey(EObject target, PreparedChange change)
        {
            return System.identityHashCode(target) + "#" + change.feature().getName(); //$NON-NLS-1$
        }

        /**
         * Reads the localized maps of the just-applied changes. Call INSIDE the write transaction,
         * AFTER the changes are applied.
         */
        @SuppressWarnings("unchecked")
        void collect(EObject target, List<PreparedChange> changes, List<String> declaredCodes,
            Configuration config)
        {
            // Only the languages the configuration ACTUALLY uses are owed a translation; a declared
            // one it never fills in is a language nobody is translating into (see localesInUse).
            // The question is asked about declaredCodes - the AFTER-batch set this call reports on -
            // so a code this very batch declares is judged by the same rule as any other.
            List<String> inUse = MetadataLanguageUtils.localesInUse(config, declaredCodes);
            collectWithLocales(target, changes, declaredCodes, inUse);
        }

        void collectWithLocales(EObject target, List<PreparedChange> changes, List<String> declaredCodes,
            List<String> inUse)
        {
            for (PreparedChange change : changes)
            {
                if (!change.isLocalized())
                {
                    continue;
                }
                wrote = true;
                languagesUsed.add(change.language());
                writtenByProperty.computeIfAbsent(propertyKey(target, change), k -> new LinkedHashSet<>())
                    .add(change.language());
                unusedLocale |= declaredCodes.contains(change.language())
                    && !inUse.contains(change.language());
                Object map = target.eGet(change.feature());
                if (!(map instanceof EMap))
                {
                    continue;
                }
                Map<String, String> present = ((EMap<String, String>)map).map();
                // MISSING and STALE ask different questions, so they read different sets. Owing a
                // NEW translation is what the in-use rule is about: a language the configuration
                // itself is not named in is one nobody is translating into, and nagging about it
                // is what the rule forbids. Text that is ALREADY THERE is not owed - it exists,
                // and this call just made it describe the old value - so staleness is asked about
                // every DECLARED language: whoever wrote that text is translating into it,
                // whatever the configuration's own synonym says.
                for (String declared : declaredCodes)
                {
                    String value = present.get(declared);
                    if (value == null || value.isEmpty())
                    {
                        if (inUse.contains(declared))
                        {
                            missing.add(declared);
                        }
                    }
                    else if (!declared.equals(change.language()) && replaced(preStateKey(target, change)))
                    {
                        // It HAS text, this call did not write it, and the language it DID write
                        // already had text of its own: the value CHANGED, so the others now say
                        // what the object used to be called. Invisible to a "missing" list - the
                        // value is there, it is just the OLD one. (Had this call merely filled in a
                        // language that was empty, nothing would have gone stale - see
                        // rememberPreState.)
                        staleByProperty.computeIfAbsent(propertyKey(target, change),
                            k -> new LinkedHashSet<>()).add(declared);
                    }
                }
            }
        }

        /**
         * Appends the report to a result: the locale used (only when this call used exactly ONE -
         * see {@link #singleLanguage()}) and the declared locales still without a translation. A
         * no-op when no localized property was written, so the ABSENCE of both fields is what tells
         * a caller nothing localized was touched.
         */
        void addTo(ToolResult result)
        {
            if (!wrote)
            {
                return;
            }
            String only = singleLanguage();
            if (only != null)
            {
                result.put(KEY_LANGUAGE, only);
            }
            result.put(KEY_LOCALES_MISSING, missing());
            List<String> stillOld = staleLocales();
            if (!stillOld.isEmpty())
            {
                result.put(KEY_LOCALES_STALE, stillOld);
            }
            if (unusedLocale)
            {
                // Legal, but worth a question: the configuration's own synonym has no text for that
                // language, so this may be a single-language build or one that does not support it
                // yet. The caller's agent should ask rather than quietly populate it.
                result.put(KEY_LOCALE_UNUSED, true);
            }
        }

        /**
         * The locales that CARRY TEXT this call did not rewrite - the old wording of a property
         * whose other language just changed.
         * <p>
         * Decided PER PROPERTY. A call that changes {@code title} in en and {@code toolTip} in fr
         * leaves title.fr and toolTip.en behind: excluding every language the call touched anywhere
         * would hide both. Only the property's OWN written locales are excluded, so translating en
         * and fr of the SAME property in one call still leaves neither of them behind.
         *
         * @return the codes, deduplicated across properties, never {@code null}
         */
        List<String> staleLocales()
        {
            Set<String> out = new LinkedHashSet<>();
            for (Map.Entry<String, Set<String>> entry : staleByProperty.entrySet())
            {
                Set<String> left = new LinkedHashSet<>(entry.getValue());
                left.removeAll(writtenByProperty.getOrDefault(entry.getKey(), Set.of()));
                out.addAll(left);
            }
            return new ArrayList<>(out);
        }

        /**
         * The single locale every localized property was written under, or {@code null} when this
         * call used more than one (echoing one of them would misdescribe the others).
         */
        String singleLanguage()
        {
            return languagesUsed.size() == 1 ? languagesUsed.iterator().next() : null;
        }

        List<String> missing()
        {
            return new ArrayList<>(missing);
        }
    }
    /** Platform dependency seam shared with the property applier. */
    protected MetadataTypeBuilder.Result buildTypeDescription(JsonElement spec, Configuration config,
        MetadataScope scope, Version version, boolean extension, MetadataTypeBuilder.TypeTarget target)
    {
        return MetadataPropertyApplier.buildTypeDescription(spec, config, scope, version, extension, target);
    }
}
