/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Shared writer for the editable FORM CONTENT model ({@code com._1c.g5.v8.dt.form.model.Form}, a
 * separate top object reached from a {@code BasicForm} mdo via {@code getForm()}).
 *
 * <p>The whole form package is touched REFLECTIVELY (by feature / classifier name) so this bundle
 * needs no compile-time dependency on the form model. Form-MEMBER editing (adding a form attribute,
 * command or visual item, binding event handlers, moving items) resolves everything on the editable
 * form instance's own EPackage; form-OBJECT creation ({@link #createForm}) resolves the form
 * EPackage from the global EMF package registry by its nsURI ({@code http://g5.1c.ru/v8/dt/form} -
 * the mdclass {@code BasicForm.form} reference is typed by the mdclass-own {@code AbstractForm}
 * base, so the mdclass metamodel deliberately does NOT lead into the form package) and builds the
 * renderable content form with EDT's default structure through that package's factory.</p>
 *
 * <p>This is the canonical home for the form-write logic that {@code create_metadata} (and, until
 * they are removed, the {@code add_form_*} tools) use. Mutation MUST run inside a BM write
 * transaction on the re-fetched content form; the shared scaffold ({@link #resolveForEdit} +
 * {@link #writeEditableForm} / {@link #readEditableForm}) owns the resolve -&gt; transact -&gt;
 * force-export pipeline, so tools only supply the per-call work.</p>
 */
public final class FormElementWriter
{
    // Form-model feature names (reflective).
    private static final String FEATURE_ITEMS = "items"; //$NON-NLS-1$
    private static final String FEATURE_ATTRIBUTES = "attributes"; //$NON-NLS-1$
    private static final String FEATURE_FORM_COMMANDS = "formCommands"; //$NON-NLS-1$
    private static final String FEATURE_TITLE = "title"; //$NON-NLS-1$
    private static final String FEATURE_VALUE_TYPE = "valueType"; //$NON-NLS-1$
    private static final String FEATURE_TYPE = "type"; //$NON-NLS-1$
    private static final String FEATURE_EXT_INFO = "extInfo"; //$NON-NLS-1$
    private static final String FEATURE_ID = "id"; //$NON-NLS-1$
    private static final String FEATURE_NAME = "name"; //$NON-NLS-1$
    /** The command-bar / menu "auto-fill from the form commands" flag. */
    private static final String FEATURE_AUTO_FILL = "autoFill"; //$NON-NLS-1$
    /** The default field ext-info EClass (a plain input field, before the value type is known). */
    private static final String ECLASS_INPUT_FIELD_EXT_INFO = "InputFieldExtInfo"; //$NON-NLS-1$
    /** The form attribute's "is the form's main data source" flag. */
    private static final String FEATURE_MAIN = "main"; //$NON-NLS-1$
    /** The dynamic-list ext-info EClass and its query-carrying features. */
    private static final String ECLASS_DYNAMIC_LIST_EXT_INFO = "DynamicListExtInfo"; //$NON-NLS-1$
    private static final String FEATURE_QUERY_TEXT = "queryText"; //$NON-NLS-1$
    private static final String FEATURE_CUSTOM_QUERY = "customQuery"; //$NON-NLS-1$
    private static final String FEATURE_AUTO_FILL_AVAILABLE_FIELDS = "autoFillAvailableFields"; //$NON-NLS-1$
    /** "Dynamic data reading" - the designer enables it for a new dynamic list. */
    private static final String FEATURE_DYNAMIC_DATA_READ = "dynamicDataRead"; //$NON-NLS-1$
    /** The dynamic list's main table - a DbViewDef reference (resolved from an object FQN). */
    private static final String FEATURE_MAIN_TABLE = "mainTable"; //$NON-NLS-1$
    private static final String FEATURE_VISIBLE = "visible"; //$NON-NLS-1$
    private static final String FEATURE_ENABLED = "enabled"; //$NON-NLS-1$
    private static final String FEATURE_USER_VISIBLE = "userVisible"; //$NON-NLS-1$
    private static final String FEATURE_AUTO_COMMAND_BAR = "autoCommandBar"; //$NON-NLS-1$
    private static final String FEATURE_SEARCH_STRING_ADDITION = "searchStringAddition"; //$NON-NLS-1$
    private static final String FEATURE_VIEW_STATUS_ADDITION = "viewStatusAddition"; //$NON-NLS-1$
    private static final String FEATURE_SEARCH_CONTROL_ADDITION = "searchControlAddition"; //$NON-NLS-1$
    private static final String FEATURE_SOURCE = "source"; //$NON-NLS-1$
    private static final String FEATURE_ACTION = "action"; //$NON-NLS-1$
    private static final String FEATURE_HANDLER = "handler"; //$NON-NLS-1$
    private static final String FEATURE_USE = "use"; //$NON-NLS-1$
    private static final String FEATURE_COMMON = "common"; //$NON-NLS-1$
    private static final String FEATURE_EXTENDED_TOOLTIP = "extendedTooltip"; //$NON-NLS-1$
    private static final String FEATURE_CONTEXT_MENU = "contextMenu"; //$NON-NLS-1$
    private static final String FEATURE_MD_FORM = "mdForm"; //$NON-NLS-1$
    private static final String FEATURE_GROUP = "group"; //$NON-NLS-1$
    private static final String FEATURE_COMMAND_INTERFACE = "commandInterface"; //$NON-NLS-1$
    private static final String FEATURE_NAVIGATION_PANEL = "navigationPanel"; //$NON-NLS-1$
    private static final String FEATURE_COMMAND_BAR = "commandBar"; //$NON-NLS-1$
    private static final String FEATURE_BASE_FORM = "baseForm"; //$NON-NLS-1$
    private static final String FEATURE_EXTENSION_FORM = "extensionForm"; //$NON-NLS-1$
    private static final int DEFAULT_EXT_FORM_OBJECT_ID = 1_000_000;
    /** {@code FormChildrenGroup.VERTICAL} - the designer default children grouping before 8.5.1. */
    private static final String LITERAL_VERTICAL = "Vertical"; //$NON-NLS-1$
    /** The {@code Auto} enum literal/name ({@code FormChildrenGroup.AUTO}, {@code ShowTitle851.AUTO}). */
    private static final String LITERAL_AUTO = "Auto"; //$NON-NLS-1$

    // Concrete form-model classifier names (resolved on the form EPackage).
    private static final String ECLASS_FORM_GROUP = "FormGroup"; //$NON-NLS-1$
    private static final String ECLASS_DECORATION = "Decoration"; //$NON-NLS-1$
    private static final String ECLASS_ABSTRACT_FORM_ATTRIBUTE = "AbstractFormAttribute"; //$NON-NLS-1$
    private static final String ECLASS_FORM_ITEM = "FormItem"; //$NON-NLS-1$
    private static final String ECLASS_FORM_FIELD = "FormField"; //$NON-NLS-1$
    private static final String ECLASS_USUAL_GROUP_EXT_INFO = "UsualGroupExtInfo"; //$NON-NLS-1$
    private static final String ECLASS_LABEL_DECORATION_EXT_INFO = "LabelDecorationExtInfo"; //$NON-NLS-1$
    private static final String ECLASS_FORM_COMMAND = "FormCommand"; //$NON-NLS-1$
    private static final String ECLASS_AUTO_COMMAND_BAR = "AutoCommandBar"; //$NON-NLS-1$
    private static final String ECLASS_CONTEXT_MENU = "ContextMenu"; //$NON-NLS-1$
    private static final String ECLASS_TABLE = "Table"; //$NON-NLS-1$
    private static final String ECLASS_EXTENDED_TOOLTIP = "ExtendedTooltip"; //$NON-NLS-1$
    private static final String ECLASS_FORM_COMMAND_HANDLER_CONTAINER = "FormCommandHandlerContainer"; //$NON-NLS-1$
    private static final String ECLASS_COMMAND_HANDLER = "CommandHandler"; //$NON-NLS-1$
    /**
     * The {@code form:EventHandlerExtension} EClass (subtype of {@code EventHandler}, same EPackage) a
     * configuration EXTENSION uses to intercept a base element's event with a {@code callType}. Resolved
     * reflectively from the form EPackage - no {@code com._1c.g5.v8.dt.form.model} compile import.
     */
    private static final String ECLASS_EVENT_HANDLER_EXTENSION = "EventHandlerExtension"; //$NON-NLS-1$
    /** The {@code callType} EAttribute (EEnum {@code ExtendedMethodCallType}) on EventHandlerExtension. */
    private static final String FEATURE_CALL_TYPE = "callType"; //$NON-NLS-1$
    /** The 1C UI call-type label "Instead" (Вместо) maps to the EMF enum literal "Override". */
    private static final String CALL_TYPE_UI_INSTEAD = "Instead"; //$NON-NLS-1$
    private static final String CALL_TYPE_LITERAL_OVERRIDE = "Override"; //$NON-NLS-1$
    /** Enum literal/name of the method-only call type, never valid for a form EVENT. */
    private static final String CALL_TYPE_NAME_CHANGE_AND_VALIDATE = "CHANGE_AND_VALIDATE"; //$NON-NLS-1$
    private static final String CALL_TYPE_LITERAL_CHANGE_AND_VALIDATE = "ChangeAndValidate"; //$NON-NLS-1$
    private static final String ECLASS_FORM_COMMAND_INTERFACE = "FormCommandInterface"; //$NON-NLS-1$
    private static final String ECLASS_FORM_COMMAND_INTERFACE_ITEMS = "FormCommandInterfaceItems"; //$NON-NLS-1$
    private static final String TYPE_LITERAL_USUAL_GROUP = "UsualGroup"; //$NON-NLS-1$
    private static final String TYPE_LITERAL_LABEL = "Label"; //$NON-NLS-1$
    /** Group {@code type} literals whose items are command-bar buttons (CommandBarButton). */
    private static final String TYPE_LITERAL_COMMAND_BAR = "CommandBar"; //$NON-NLS-1$
    private static final String TYPE_LITERAL_BUTTON_GROUP = "ButtonGroup"; //$NON-NLS-1$
    private static final String TYPE_LITERAL_POPUP = "Popup"; //$NON-NLS-1$
    private static final String TYPE_LITERAL_PAGES = "Pages"; //$NON-NLS-1$
    private static final String TYPE_LITERAL_PAGE = "Page"; //$NON-NLS-1$
    private static final String TYPE_LITERAL_COLUMN_GROUP = "ColumnGroup"; //$NON-NLS-1$
    /** The single handler "event" of a form command (its FQN leaf: {@code Command.X.Handler.Action}). */
    private static final String COMMAND_ACTION_EVENT = "Action"; //$NON-NLS-1$
    /** The parent token addressing the form's auto command bar (its {@code ChildItems} in Designer XML). */
    private static final String AUTO_COMMAND_BAR_TOKEN = "AutoCommandBar"; //$NON-NLS-1$
    /** The Designer-XML child-collection token, tolerated (and ignored) at the end of a parent path. */
    private static final String CHILD_ITEMS_TOKEN = "ChildItems"; //$NON-NLS-1$
    /** The owner's owned-form collection feature name (and FQN segment). */
    private static final String KEY_FORMS = "forms"; //$NON-NLS-1$
    /** The horizontal-alignment feature name on a command bar / label-decoration ext info. */
    private static final String KEY_HORIZONTAL_ALIGN = "horizontalAlign"; //$NON-NLS-1$
    /** The event-handlers collection feature name on a form element. */
    private static final String KEY_HANDLERS = "handlers"; //$NON-NLS-1$
    /** The auto-max-width boolean feature name on a visual item / ext info. */
    private static final String KEY_AUTO_MAX_WIDTH = "autoMaxWidth"; //$NON-NLS-1$
    /** The auto-max-height boolean feature name on a visual item / ext info. */
    private static final String KEY_AUTO_MAX_HEIGHT = "autoMaxHeight"; //$NON-NLS-1$
    /** The concrete {@code Button} form-item EClass / platform-type-map key. */
    private static final String ELEM_BUTTON = "Button"; //$NON-NLS-1$
    /** The leading fragment of the "invalid event" error message. */
    private static final String ERR_EVENT_PREFIX = "Event '"; //$NON-NLS-1$
    /** The leading fragment of the "item already exists" error message. */
    private static final String ERR_ITEM_EXISTS = "Form item already exists: "; //$NON-NLS-1$
    /** The legacy managed-form platform type name (swapped with {@code ClientApplicationForm}). */
    private static final String TYPE_MANAGED_FORM = "ManagedForm"; //$NON-NLS-1$

    /** A supported form-element kind, resolved from a (bilingual) FQN kind token. */
    public enum Kind { ATTRIBUTE, COMMAND, GROUP, DECORATION, FIELD, BUTTON, TABLE }

    /** A parsed form-member FQN: the form path (for {@code resolveMdForm}) + the leaf kind/name. */
    public static final class FormMemberRef
    {
        /** The owning form path, normalized to the {@code Type.Object.forms.FormName} /
         * {@code CommonForm.Name} shape that {@code FormStructureReader.resolveMdForm} expects. */
        public final String formPath;
        /** The raw element kind token (English or Russian); resolve via {@link #kindForToken}. */
        public final String kindToken;
        /** The element's programmatic name (for a handler FQN, the EVENT name). */
        public final String name;
        /** For an ITEM-LEVEL handler FQN, the owning item's kind token; {@code null} for a form-level
         * member or handler. */
        public final String itemKindToken;
        /** For an ITEM-LEVEL handler FQN, the owning item's name; {@code null} otherwise. */
        public final String itemName;

        FormMemberRef(String formPath, String kindToken, String name, String itemKindToken,
            String itemName)
        {
            this.formPath = formPath;
            this.kindToken = kindToken;
            this.name = name;
            this.itemKindToken = itemKindToken;
            this.itemName = itemName;
        }

        /** Whether the FQN addresses an event handler on a form ITEM (vs the form root). */
        public boolean isItemLevel()
        {
            return itemName != null;
        }
    }

    private FormElementWriter()
    {
        // utility class
    }

    /**
     * Parses a form-member FQN into its form path + leaf kind/name, or returns {@code null} when the
     * FQN does not address a form member. The recognized shapes are:
     * <ul>
     *   <li>{@code Type.Object.Form.FormName.Kind.Name} (form-level member/handler; the {@code Form}
     *       token may be {@code Form}/{@code Forms}/{@code Форма}/{@code Формы})</li>
     *   <li>{@code CommonForm.FormName.Kind.Name} (a CommonForm IS a form)</li>
     *   <li>{@code Type.Object.Form.FormName.ItemKind.ItemName.Handler.Event} (an event handler on a
     *       form ITEM) and its {@code CommonForm.FormName.ItemKind.ItemName.Handler.Event} variant</li>
     * </ul>
     * The form-element kind tokens are NOT confused with the mdclass member tokens because a mdclass
     * member FQN never carries a form token at position 2 nor starts with {@code CommonForm} followed
     * by a kind pair.
     */
    public static FormMemberRef parse(String normFqn)
    {
        if (normFqn == null)
        {
            return null;
        }
        String[] p = normFqn.split("\\."); //$NON-NLS-1$
        String formPath;
        int rem; // index where the kind/name remainder begins
        if (p.length >= 6 && isFormToken(p[2]))
        {
            formPath = formPathOf(p[0], p[1], p[3]);
            rem = 4;
        }
        else if (p.length >= 4 && "CommonForm".equalsIgnoreCase(MetadataTypeUtils.toEnglishSingular(p[0]))) //$NON-NLS-1$
        {
            formPath = p[0] + "." + p[1]; //$NON-NLS-1$
            rem = 2;
        }
        else
        {
            return null;
        }
        int tail = p.length - rem;
        if (tail == 2)
        {
            // Form-level member or handler: Kind.Name.
            return new FormMemberRef(formPath, p[rem], p[rem + 1], null, null);
        }
        if (tail == 4 && isHandlerToken(p[rem + 2]))
        {
            // Item-level handler: ItemKind.ItemName.Handler.Event.
            return new FormMemberRef(formPath, p[rem + 2], p[rem + 3], p[rem], p[rem + 1]);
        }
        return null;
    }

    /**
     * Builds the canonical owned-form path {@code Type.Object.forms.FormName} — THE shape
     * {@code FormStructureReader.resolveMdForm} / {@code MetadataPathResolver} expect. Single
     * owner of the literal so the parse helpers here and external callers (e.g. the delete
     * tool's form-object branch) cannot drift apart on the {@code .forms.} segment.
     *
     * @param ownerType the owner's TYPE token (e.g. {@code Catalog})
     * @param ownerName the owner object's name
     * @param formName the owned form's name
     * @return the {@code Type.Object.forms.FormName} path
     */
    public static String formPathOf(String ownerType, String ownerName, String formName)
    {
        return ownerType + "." + ownerName + ".forms." + formName; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Whether {@code token} is a recognized FORM segment of an FQN / form path:
     * {@code Form} / {@code Forms} and their Russian equivalents (singular / plural), case-insensitive.
     * This is THE form-token predicate - every consumer that parses a form path (this writer,
     * {@link MetadataPathResolver}) must share it so a form addressed one way (e.g. created via
     * {@code Catalog.X.Form.Y}) stays addressable everywhere (screenshot / layout snapshot).
     */
    public static boolean isFormToken(String token)
    {
        if (token == null)
        {
            return false;
        }
        String s = token.toLowerCase();
        return "form".equals(s) || KEY_FORMS.equals(s) //$NON-NLS-1$
            || RU_FORM.equals(s) || RU_FORMS.equals(s);
    }

    /**
     * If {@code normFqn} addresses a FORM ITSELF (not a member) - {@code Type.Object.Form(s).FormName}
     * (4 parts, form token at position 2) or {@code CommonForm.FormName} (2 parts) - returns the form
     * path normalized to the {@code Type.Object.forms.FormName} / {@code CommonForm.Name} shape that
     * {@code FormStructureReader.resolveMdForm} expects; otherwise {@code null}. Used to render a
     * form's structure from {@code get_metadata_details}.
     */
    public static String parseFormPath(String normFqn)
    {
        if (normFqn == null)
        {
            return null;
        }
        String[] p = normFqn.split("\\."); //$NON-NLS-1$
        if (p.length == 4 && isFormToken(p[2]))
        {
            return formPathOf(p[0], p[1], p[3]);
        }
        if (p.length == 2 && "CommonForm".equalsIgnoreCase(MetadataTypeUtils.toEnglishSingular(p[0]))) //$NON-NLS-1$
        {
            return p[0] + "." + p[1]; //$NON-NLS-1$
        }
        return null;
    }

    /** A parsed form-OBJECT create FQN: the owner type/name + the new form's Name. */
    public static final class FormObjectRef
    {
        /** Owner metadata TYPE token, as supplied (English or Russian), e.g. {@code Catalog}. */
        public final String ownerType;
        /** Owner metadata object Name, e.g. {@code Products}. */
        public final String ownerName;
        /** Programmatic Name of the form to create, e.g. {@code ItemForm}. */
        public final String formName;

        FormObjectRef(String ownerType, String ownerName, String formName)
        {
            this.ownerType = ownerType;
            this.ownerName = ownerName;
            this.formName = formName;
        }

        /** The {@code Type.Object} owner FQN of the new form. */
        public String ownerFqn()
        {
            return ownerType + "." + ownerName; //$NON-NLS-1$
        }
    }

    /**
     * If {@code normFqn} addresses a FORM OBJECT to CREATE on a metadata object -
     * {@code Type.Object.Form(s).FormName} (exactly 4 parts, a form token at position 2) - returns the
     * parsed owner + form name; otherwise {@code null}. This is the create counterpart of
     * {@link #parse} (which addresses a form MEMBER, 6+ parts) and of {@link #parseFormPath} (which
     * resolves an EXISTING form for reading): a 4-part form FQN is neither a member nor a top object, so
     * it is handled by {@code create_metadata}'s dedicated form-object branch.
     * <p>
     * A {@code CommonForm.Name} (2 parts) is NOT returned here: a CommonForm IS a top object and is
     * created through the normal top-level create path.
     */
    public static FormObjectRef parseFormObjectCreate(String normFqn)
    {
        if (normFqn == null)
        {
            return null;
        }
        String[] p = normFqn.split("\\."); //$NON-NLS-1$
        if (p.length == 4 && isFormToken(p[2]))
        {
            return new FormObjectRef(p[0], p[1], p[3]);
        }
        return null;
    }

    /**
     * Resolves a form-member FQN kind token (English or Russian, case-insensitive) to a {@link Kind},
     * or {@code null} if it is not a supported form-element kind.
     */
    // Russian kind / form tokens, built from code points so this source stays pure ASCII (the same
    // non-UTF-8 Tycho-build guard the rest of the project uses; no raw Cyrillic literals).
    private static final String RU_ATTRIBUTE = cp(0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442); // rekvizit
    private static final String RU_COMMAND = cp(0x043a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x0430); // komanda
    private static final String RU_GROUP = cp(0x0433, 0x0440, 0x0443, 0x043f, 0x043f, 0x0430); // gruppa
    private static final String RU_DECORATION = cp(0x0434, 0x0435, 0x043a, 0x043e, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f); // dekoraciya
    private static final String RU_FIELD = cp(0x043f, 0x043e, 0x043b, 0x0435); // pole
    private static final String RU_BUTTON = cp(0x043a, 0x043d, 0x043e, 0x043f, 0x043a, 0x0430); // knopka
    private static final String RU_TABLE = cp(0x0442, 0x0430, 0x0431, 0x043b, 0x0438, 0x0446, 0x0430); // tablica
    private static final String RU_FORM = cp(0x0444, 0x043e, 0x0440, 0x043c, 0x0430); // forma
    private static final String RU_FORMS = cp(0x0444, 0x043e, 0x0440, 0x043c, 0x044b); // formy
    private static final String RU_HANDLER = cp(0x043e, 0x0431, 0x0440, 0x0430, 0x0431, 0x043e, 0x0442, 0x0447, 0x0438, 0x043a); // obrabotchik
    private static final String RU_ACTION = cp(0x0434, 0x0435, 0x0439, 0x0441, 0x0442, 0x0432, 0x0438, 0x0435); // dejstvie
    // Auto-child name suffixes, localized by the configuration SCRIPT VARIANT the way the designer's
    // FormObjectDefaultNameProvider localizes them (RasshirennayaPodskazka / KontekstnoeMenyu).
    private static final String RU_SUFFIX_EXTENDED_TOOLTIP = cp(0x0420, 0x0430, 0x0441, 0x0448,
        0x0438, 0x0440, 0x0435, 0x043d, 0x043d, 0x0430, 0x044f, 0x041f, 0x043e, 0x0434, 0x0441,
        0x043a, 0x0430, 0x0437, 0x043a, 0x0430);
    private static final String RU_SUFFIX_CONTEXT_MENU = cp(0x041a, 0x043e, 0x043d, 0x0442, 0x0435,
        0x043a, 0x0441, 0x0442, 0x043d, 0x043e, 0x0435, 0x041c, 0x0435, 0x043d, 0x044e);
    private static final String SUFFIX_EXTENDED_TOOLTIP = "ExtendedTooltip"; //$NON-NLS-1$
    private static final String SUFFIX_CONTEXT_MENU = "ContextMenu"; //$NON-NLS-1$
    /** ru "КоманднаяПанель" - the script-variant suffix for a table's own command bar name. */
    private static final String RU_SUFFIX_COMMAND_BAR = cp(0x041a, 0x043e, 0x043c, 0x0430, 0x043d,
        0x0434, 0x043d, 0x0430, 0x044f, 0x041f, 0x0430, 0x043d, 0x0435, 0x043b, 0x044c);
    private static final String SUFFIX_COMMAND_BAR = "CommandBar"; //$NON-NLS-1$
    // The three table additions: like the command bar and LineNumber, their auto-name suffix is
    // localized per script variant by the designer (verified against EDT's ru report_variant.form
    // template: СтрокаПоиска / СостояниеПросмотра / УправлениеПоиском).
    private static final String SUFFIX_SEARCH_STRING = "SearchString"; //$NON-NLS-1$
    private static final String SUFFIX_VIEW_STATUS = "ViewStatus"; //$NON-NLS-1$
    private static final String SUFFIX_SEARCH_CONTROL = "SearchControl"; //$NON-NLS-1$
    /** ru "СтрокаПоиска" - the script-variant suffix for a table's search-string addition name. */
    private static final String RU_SUFFIX_SEARCH_STRING = cp(0x0421, 0x0442, 0x0440, 0x043e, 0x043a,
        0x0430, 0x041f, 0x043e, 0x0438, 0x0441, 0x043a, 0x0430);
    /** ru "СостояниеПросмотра" - the script-variant suffix for a table's view-status addition name. */
    private static final String RU_SUFFIX_VIEW_STATUS = cp(0x0421, 0x043e, 0x0441, 0x0442, 0x043e,
        0x044f, 0x043d, 0x0438, 0x0435, 0x041f, 0x0440, 0x043e, 0x0441, 0x043c, 0x043e, 0x0442, 0x0440,
        0x0430);
    /** ru "УправлениеПоиском" - the script-variant suffix for a table's search-control addition name. */
    private static final String RU_SUFFIX_SEARCH_CONTROL = cp(0x0423, 0x043f, 0x0440, 0x0430, 0x0432,
        0x043b, 0x0435, 0x043d, 0x0438, 0x0435, 0x041f, 0x043e, 0x0438, 0x0441, 0x043a, 0x043e, 0x043c);
    /** The standard tabular-section row-number attribute name, English / Russian script variant. */
    private static final String EN_LINE_NUMBER = "LineNumber"; //$NON-NLS-1$
    private static final String RU_LINE_NUMBER = cp(0x041d, 0x043e, 0x043c, 0x0435, 0x0440, 0x0421,
        0x0442, 0x0440, 0x043e, 0x043a, 0x0438); // NomerStroki

    /** Whether a kind token addresses an event Handler (English or Russian, case-insensitive). */
    public static boolean isHandlerToken(String token)
    {
        if (token == null)
        {
            return false;
        }
        String t = token.trim().toLowerCase();
        return FEATURE_HANDLER.equals(t) || RU_HANDLER.equals(t);
    }

    public static Kind kindForToken(String token)
    {
        if (token == null)
        {
            return null;
        }
        String t = token.trim().toLowerCase();
        if ("attribute".equals(t) || FEATURE_ATTRIBUTES.equals(t) || RU_ATTRIBUTE.equals(t)) //$NON-NLS-1$
        {
            return Kind.ATTRIBUTE;
        }
        if ("command".equals(t) || "commands".equals(t) || RU_COMMAND.equals(t)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return Kind.COMMAND;
        }
        if (FEATURE_GROUP.equals(t) || RU_GROUP.equals(t))
        {
            return Kind.GROUP;
        }
        if ("decoration".equals(t) || RU_DECORATION.equals(t)) //$NON-NLS-1$
        {
            return Kind.DECORATION;
        }
        if ("field".equals(t) || RU_FIELD.equals(t)) //$NON-NLS-1$
        {
            return Kind.FIELD;
        }
        if ("button".equals(t) || RU_BUTTON.equals(t)) //$NON-NLS-1$
        {
            return Kind.BUTTON;
        }
        if ("table".equals(t) || RU_TABLE.equals(t)) //$NON-NLS-1$
        {
            return Kind.TABLE;
        }
        return null;
    }

    /** Builds a string from BMP code points (keeps this source pure ASCII). Delegates to the shared
     * {@link MetadataLanguageUtils#cp}. */
    private static String cp(int... codePoints)
    {
        return MetadataLanguageUtils.cp(codePoints);
    }

    /**
     * Reads the editable form content model from a {@code BasicForm} mdo via {@code getForm()}
     * (reflective). Returns {@code null} if the form has no managed-form content (empty / legacy /
     * not yet built), recognized by the presence of the {@code items} feature.
     *
     * @param txMdForm the transaction-bound {@code BasicForm} EObject
     * @return the editable form content EObject, or {@code null}
     */
    public static EObject getEditableForm(EObject txMdForm)
    {
        try
        {
            Method getForm = txMdForm.getClass().getMethod("getForm"); //$NON-NLS-1$
            Object form = getForm.invoke(txMdForm);
            if (form instanceof EObject
                && ((EObject)form).eClass().getEStructuralFeature(FEATURE_ITEMS) != null)
            {
                return (EObject)form;
            }
        }
        catch (ReflectiveOperationException e)
        {
            // No getForm() / inaccessible - treated as "no editable model".
        }
        return null;
    }

    // ---- shared form write-transaction scaffold ---------------------------------------------------
    //
    // Every form-editing tool repeats the same ~40-line pipeline: resolve the MD-form from a form
    // path, null-check the BM services, capture the bmId, re-fetch the MD-form inside a BM
    // transaction, hop to the editable content form, run the work, then force-export the content
    // form's own FQN (it serializes to Form.form). The scaffold below owns that pipeline ONCE; // NOSONAR explanatory comment, not commented-out code
    // tools supply only the per-call work and their user-visible "form not found" message. Every
    // scaffold-level failure that carries an actionable message is thrown as a
    // FormValidationException with the READY error JSON, so callers surface it verbatim
    // (FormValidationException.jsonOf) from one catch block.

    /** Work executed on the re-fetched editable content form inside a BM WRITE transaction. */
    @FunctionalInterface
    public interface FormWork
    {
        /**
         * @param formModel the transaction-bound editable content form
         * @param tx the active BM write transaction
         */
        void run(EObject formModel, IBmTransaction tx);
    }

    /** Read work executed on the re-fetched editable content form inside a BM READ transaction. */
    @FunctionalInterface
    public interface FormRead<T>
    {
        /**
         * @param formModel the transaction-bound editable content form
         * @param tx the active BM read transaction
         * @return the read result (must not leak transaction-bound EObjects)
         */
        T run(EObject formModel, IBmTransaction tx);
    }

    /** Work executed on the re-fetched MD-form ({@code BasicForm}) inside a BM WRITE transaction. */
    @FunctionalInterface
    public interface MdFormWork
    {
        /**
         * @param txMdForm the transaction-bound {@code BasicForm} mdo
         * @param tx the active BM write transaction
         */
        void run(EObject txMdForm, IBmTransaction tx);
    }

    /**
     * A resolved form-edit context: the project, its BM model and the MD-form (pre-transaction
     * snapshot - re-fetched by {@link #mdFormBmId} inside the transaction for any mutation).
     */
    public static final class FormEditContext
    {
        /** The workspace project owning the form. */
        public final IProject project;
        /** The project's BM model. */
        public final IBmModel bmModel;
        /** Pre-transaction snapshot of the MD-form (safe for reads like {@code getName()}). */
        public final MdObject mdForm;
        /** The MD-form's bmId, used to re-fetch it inside the transaction. */
        final long mdFormBmId;
        /** The resolved form path (for error messages), or {@code null} for a pre-resolved form. */
        final String formPath;

        FormEditContext(IProject project, IBmModel bmModel, MdObject mdForm, long mdFormBmId,
            String formPath)
        {
            this.project = project;
            this.bmModel = bmModel;
            this.mdForm = mdForm;
            this.mdFormBmId = mdFormBmId;
            this.formPath = formPath;
        }
    }

    /**
     * Resolves the form addressed by {@code formPath} (the {@code Type.Object.forms.FormName} /
     * {@code CommonForm.Name} shape) and the BM services needed to edit it. Every failure is thrown
     * as a {@link FormValidationException} carrying the ready error JSON ({@code formNotFoundMessage}
     * for a missing form), so the caller's single catch block surfaces it verbatim.
     *
     * @param project the workspace project
     * @param config the project configuration
     * @param formPath the form path to resolve
     * @param formNotFoundMessage the user-visible message when the form does not resolve
     * @return the resolved context
     */
    public static FormEditContext resolveForEdit(IProject project, Configuration config,
        String formPath, String formNotFoundMessage)
    {
        MdObject mdForm = FormStructureReader.resolveMdForm(config, formPath);
        if (mdForm == null)
        {
            throw new FormValidationException(ToolResult.error(formNotFoundMessage).toJson());
        }
        return editContext(project, mdForm, formPath);
    }

    /**
     * Builds a {@link FormEditContext} for an ALREADY-RESOLVED MD-form (a caller with its own
     * resolution / error wording, e.g. the owned-form delete). Throws {@link FormValidationException}
     * with the ready error JSON when the BM services are unavailable.
     *
     * @param project the workspace project
     * @param mdForm the resolved MD-form
     * @return the context
     */
    public static FormEditContext editContextFor(IProject project, MdObject mdForm)
    {
        return editContext(project, mdForm, null);
    }

    private static FormEditContext editContext(IProject project, MdObject mdForm, String formPath)
    {
        if (!(mdForm instanceof IBmObject))
        {
            throw new FormValidationException(ToolResult.error("Form is not a BM object").toJson()); //$NON-NLS-1$
        }
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            throw new FormValidationException(
                ToolResult.error("IBmModelManager not available").toJson()); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            throw new FormValidationException(ToolResult.error("BM model not available for project: " //$NON-NLS-1$
                + project.getName()).toJson());
        }
        return new FormEditContext(project, bmModel, mdForm, ((IBmObject)mdForm).bmGetId(), formPath);
    }

    /**
     * Runs {@code work} against the editable content form inside ONE BM WRITE transaction, then
     * force-exports the content form's OWN top-object FQN (forms serialize to {@code Form.form}).
     * The MD-form is re-fetched by bmId inside the transaction; a missing editable content model is
     * thrown as a {@link FormValidationException} (rolling the transaction back), so an exception
     * from {@code work} (including a {@code FormValidationException} carrying a ready JSON error)
     * leaves no partial mutation.
     *
     * @param ctx the resolved context (see {@link #resolveForEdit})
     * @param taskName a short BM task name for diagnostics
     * @param work the mutation to run on the content form
     * @return whether the export persisted the change to disk
     */
    public static boolean writeEditableForm(FormEditContext ctx, String taskName, FormWork work)
    {
        String contentFormFqn = BmTransactions.<String>write(ctx.bmModel, taskName, (tx, pm) ->
        {
            EObject formModel = editableFormInTx(ctx, tx);
            work.run(formModel, tx);
            normalizeFormAttributeIds(formModel);
            normalizeFormItemIds(formModel);
            normalizeFormCommandIds(formModel);
            // The content Form is a separate top object serialized to Form.form - export ITS fqn.
            return (formModel instanceof IBmObject) ? ((IBmObject)formModel).bmGetFqn() : null;
        });
        boolean exported = contentFormFqn != null && !contentFormFqn.isEmpty()
            && BmTransactions.forceExportToDisk(ctx.project, contentFormFqn);
        if (exported)
        {
            // The BM write + export does NOT refresh an already-open form editor (it keeps rendering its
            // own stale, grey/read-only representation until a clean_project). Rebuild it, best-effort.
            FormEditorRefreshUtils.refreshOpenFormEditorAsync(ctx.project, ctx.formPath);
        }
        return exported;
    }

    /**
     * Runs {@code work} against the editable content form inside ONE BM READ transaction (no
     * mutation, nothing exported). Scaffold failures are thrown like {@link #writeEditableForm}.
     *
     * @param ctx the resolved context (see {@link #resolveForEdit})
     * @param taskName a short BM task name for diagnostics
     * @param work the read to run on the content form
     * @param <T> the read result type
     * @return the read result
     */
    public static <T> T readEditableForm(FormEditContext ctx, String taskName, FormRead<T> work)
    {
        return BmTransactions.read(ctx.bmModel, taskName,
            (tx, pm) -> work.run(editableFormInTx(ctx, tx), tx));
    }

    /**
     * Runs {@code work} against the re-fetched MD-form ({@code BasicForm}) itself inside ONE BM
     * WRITE transaction - the variant for work that mutates the MD-form / its owner rather than the
     * content form (e.g. deleting an owned form). No editable-content check is applied and nothing
     * is exported; the caller exports whichever top object(s) it dirtied.
     *
     * @param ctx the resolved context (see {@link #editContextFor})
     * @param taskName a short BM task name for diagnostics
     * @param work the mutation to run on the MD-form
     */
    public static void writeMdForm(FormEditContext ctx, String taskName, MdFormWork work)
    {
        BmTransactions.<Void>write(ctx.bmModel, taskName, (tx, pm) ->
        {
            work.run(mdFormInTx(ctx, tx), tx);
            return null;
        });
    }

    /** Re-fetches the MD-form inside the transaction, failing clearly when it has gone. */
    private static EObject mdFormInTx(FormEditContext ctx, IBmTransaction tx)
    {
        EObject txMdForm = (EObject)tx.getObjectById(ctx.mdFormBmId);
        if (txMdForm == null)
        {
            throw new IllegalStateException("Form object not found in transaction"); //$NON-NLS-1$
        }
        return txMdForm;
    }

    /** Re-fetches the MD-form and hops to its editable content form, failing on either gap. */
    private static EObject editableFormInTx(FormEditContext ctx, IBmTransaction tx)
    {
        EObject formModel = getEditableForm(mdFormInTx(ctx, tx));
        if (formModel == null)
        {
            throw new FormValidationException(noEditableContentError(ctx.formPath));
        }
        return formModel;
    }

    /** The canonical "no editable content model" error JSON (with the form path when known). */
    private static String noEditableContentError(String formPath)
    {
        String suffix = (formPath != null && !formPath.isEmpty()) ? ": " + formPath : ""; //$NON-NLS-1$ //$NON-NLS-2$
        return ToolResult.error("the form has no editable content model (it may be empty, an " //$NON-NLS-1$
            + "ordinary/legacy form, or not yet built)" + suffix).toJson(); //$NON-NLS-1$
    }

    /**
     * Creates a form member of {@code kind} named {@code name} on the editable {@code formModel}.
     * For a visual item (group / decoration) the optional {@code parentName} nests it under an
     * existing item (form root when {@code null}); {@code title} (with its language CODE) is applied
     * when given. Visual items receive the designer's defaults including the auto-children
     * (extended tooltip / context menu) whose name suffixes follow the configuration script variant
     * ({@code russianAutoNames}). Runs INSIDE a BM write transaction on the re-fetched content form.
     *
     * @return {@code null} on success, or a human-readable error message (the caller wraps it in
     *     {@code ToolResult.error}); the created element's concrete EClass name is returned via
     *     {@code createdKind} when non-null.
     */
    public static String createMember(EObject formModel, Kind kind, String name, String parentName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        String bindTarget, String titleLanguage, String title, boolean russianAutoNames,
        String[] createdKind)
    {
        switch (kind)
        {
            case ATTRIBUTE:
                return createAttribute(formModel, name, titleLanguage, title, createdKind);
            case COMMAND:
                return createCommand(formModel, name, titleLanguage, title, createdKind);
            case FIELD:
                return createField(formModel, name, parentName, bindTarget, titleLanguage, title,
                    russianAutoNames, createdKind);
            case BUTTON:
                return createButton(formModel, name, parentName, bindTarget, titleLanguage, title,
                    russianAutoNames, createdKind);
            case TABLE:
                // Bare table (the bind slot carries its dataPath). The metadata-aware caller adds the
                // tabular-section columns via createTable(..., columnAttributeNames, ...).
                return createTable(formModel, name, parentName, bindTarget, java.util.Collections.emptyList(),
                    titleLanguage, title, russianAutoNames, createdKind);
            case GROUP:
            case DECORATION:
            default:
                // For a GROUP the bind slot carries the optional explicit group type literal.
                return createItem(formModel, kind, name, parentName, bindTarget, titleLanguage,
                    title, russianAutoNames, createdKind);
        }
    }

    // ---- form-OBJECT creation (the BasicForm mdo + its renderable content Form) ------------------

    /**
     * Creates a managed form OBJECT on {@code owner} inside an active BM write transaction: the
     * MD-form ({@link BasicForm}, added to the owner's {@code forms} collection) AND an empty,
     * renderable content {@code Form}, linked both ways, with the content form registered as a BM top
     * object under the canonical external-property FQN. Mirrors the EDT "New form" wizard.
     * <p>
     * The content form is built by the FORM model factory ({@code formFactory}, the same
     * {@code FormObjectFactory} the wizard uses) so it gets the predefined {@code autoCommandBar} the
     * WYSIWYG layout generator requires - without it {@code HippoGenerator.readElement} ->
     * {@code findHGClass(null)} throws and the form never renders. As a guard against the factory not
     * resolving in this environment (or a future change), the render-critical {@code autoCommandBar}
     * and the standard form-level flags are also applied explicitly here.
     * <p>
     * The content form is attached under {@code ITopObjectFqnGenerator.generateExternalPropertyFqn(
     * mdForm, BASIC_FORM__FORM)} - the SAME FQN EDT's own form infrastructure uses - so the BM
     * namespace assigns it a store and later look-ups resolve; any other FQN leaves it store-less and
     * access fails with "No store … assigned to namespace".
     *
     * @param tx the active BM write transaction
     * @param owner the owner metadata object, re-fetched inside {@code tx}
     * @param formName the programmatic Name of the new form (already validated)
     * @param synonymLanguage the resolved synonym language CODE, or {@code null} when no synonym
     * @param synonym the synonym text, or {@code null}
     * @param comment the comment text to set on the MD-form, or {@code null}
     * @param setAsDefault when {@code true}, registers the form as the owner's default object form
     * @param mdFactory the MD model-object factory (creates the BasicForm)
     * @param formFactory the FORM model-object factory (creates the content Form), may be {@code null}
     * @param fqnGenerator the top-object FQN generator (computes the content form's canonical FQN)
     * @param version the platform version (drives the designer's version-dependent form defaults)
     * @param russianAutoNames whether the configuration script variant is Russian (localizes the
     *     fallback predefined command-bar name, like the designer's default-name provider)
     * @return the content form's own top-object FQN (serialized to {@code Form.form}), for force-export
     */
    public static String createForm(IBmTransaction tx, MdObject owner, String formName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        String synonymLanguage, String synonym, String comment, boolean setAsDefault,
        IModelObjectFactory mdFactory, IModelObjectFactory formFactory,
        ITopObjectFqnGenerator fqnGenerator, Version version, boolean russianAutoNames)
    {
        EStructuralFeature formsFeature = owner.eClass().getEStructuralFeature(KEY_FORMS);
        if (formsFeature == null || !(formsFeature.getEType() instanceof EClass))
        {
            throw new IllegalArgumentException("Object type '" + owner.eClass().getName() //$NON-NLS-1$
                + "' does not support forms."); //$NON-NLS-1$
        }
        if (findOwnedFormByName(owner, formsFeature, formName) != null)
        {
            throw new IllegalStateException("Form already exists: " + formName); //$NON-NLS-1$
        }
        EClass mdFormEClass = (EClass)formsFeature.getEType();

        // (1) The MD-form via the standard MD factory (wizard-equivalent).
        BasicForm mdForm = (BasicForm)mdFactory.create(mdFormEClass, version);
        if (mdForm == null)
        {
            throw new IllegalStateException("Factory returned null for form type: " + mdFormEClass.getName()); //$NON-NLS-1$
        }
        mdForm.setName(formName);
        mdForm.setUuid(UUID.randomUUID());
        if (synonym != null && !synonym.isEmpty() && synonymLanguage != null)
        {
            mdForm.getSynonym().put(synonymLanguage, synonym);
        }
        if (comment != null && !comment.isEmpty())
        {
            mdForm.setComment(comment);
        }

        // (2) The content form, built by the FORM factory so it gets EDT's default structure
        // (autoCommandBar, command interface, form flags). Falls back to a manual minimal-but-
        // renderable build if the factory is unavailable.
        EObject content = createContentForm(formFactory, owner, version, russianAutoNames);

        // (3) Link MD-form <-> content form (both directions, by feature - no typed form API).
        mdForm.eSet(MdClassPackage.Literals.BASIC_FORM__FORM, content);
        setSingleReference(content, FEATURE_MD_FORM, mdForm);

        // (4) Add the MD-form to the owner's forms collection BEFORE generating the content FQN, so the
        // MD-form has a resolvable parent chain (owner -> configuration) and therefore a resolvable FQN.
        addToList(owner, KEY_FORMS, mdForm);

        // (5) Register the content form as a BM top object under the canonical external-property FQN.
        String contentFqn = fqnGenerator.generateExternalPropertyFqn(mdForm,
            MdClassPackage.Literals.BASIC_FORM__FORM);
        if (contentFqn == null || contentFqn.isEmpty())
        {
            throw new IllegalStateException("Could not generate the content-form FQN for: " + formName); //$NON-NLS-1$
        }
        tx.attachTopObject((IBmObject)content, contentFqn);

        // (6) Fill default references / usePurposes as the wizard does.
        mdFactory.fillDefaultReferences(mdForm);

        // (6a) Re-assert the autoCommandBar's id=-1 sentinel as the LAST writer. createContentForm
        // already set it, but the BM integration above (attachTopObject + fillDefaultReferences)
        // resets the bar's id back to the model default (0). A 0-id predefined command bar serializes
        // WITHOUT an <id> element and EDT flags the form with form-invalid-item-id; re-applying it here
        // makes the bar match a designer-built form (<id>-1</id>). See issue #189.
        enforceAutoCommandBarIdSentinel(content);

        // (7) Optionally set as the owner's default object form.
        if (setAsDefault)
        {
            setDefaultObjectForm(owner, mdForm);
        }
        return contentFqn;
    }

    /**
     * Builds the content {@code Form} with EDT's default structure. Prefers the FORM model factory
     * ({@code FormObjectFactory}) - {@code create(Form, owner, version)} produces exactly what the
     * "New form" wizard builds (predefined {@code autoCommandBar}, command interface, form flags).
     * Falls back to a bare EFactory create when the factory is absent. In both cases the
     * render-critical {@code autoCommandBar} and the standard form-level defaults are applied
     * explicitly afterwards (filling only what the factory left unset), so the form renders whether
     * or not the factory ran.
     * <p>
     * Fully reflective: the {@code Form} EClass is reached through {@link #contentFormEClass()} (the
     * EMF package registry, by nsURI), so no compile-time dependency on
     * {@code com._1c.g5.v8.dt.form.model} is needed. Package-visible for the headless unit test.
     */
    static EObject createContentForm(IModelObjectFactory formFactory, MdObject owner, Version version,
        boolean russianAutoNames)
    {
        EClass formEClass = contentFormEClass();
        EObject content = null;
        if (formFactory != null)
        {
            content = formFactory.create(formEClass, owner, version);
        }
        if (content == null)
        {
            content = formEClass.getEPackage().getEFactoryInstance().create(formEClass);
        }
        // Guard: the factory may not run in this environment (its injector may be absent), or a future
        // change may stop seeding the command bar. Ensure the render-critical element is present.
        EObject autoCommandBar = singleReference(content, FEATURE_AUTO_COMMAND_BAR);
        if (autoCommandBar == null)
        {
            autoCommandBar = createDefaultAutoCommandBar(content, russianAutoNames);
            setSingleReference(content, FEATURE_AUTO_COMMAND_BAR, autoCommandBar);
        }
        // The FormObjectFactory-built bar does NOT carry the id=-1 sentinel a form's own predefined
        // command bar requires, so EDT validation flags it (form-invalid-item-id). Enforce id=-1 on
        // the bar regardless of who created it (the fallback bar already set it; this is idempotent).
        // NOTE: when this runs as part of createForm, the later BM integration (attachTopObject +
        // fillDefaultReferences) RESETS this id back to 0, so createForm re-applies it as its last
        // step (6a). This set here still matters for the direct (headless / no-BM) callers. Issue #189.
        enforceAutoCommandBarIdSentinel(content);
        applyFormDefaults(content, version);
        normalizeFormAttributeIds(content);
        normalizeFormItemIds(content);
        normalizeFormCommandIds(content);
        return content;
    }

    /**
     * Forces the form's predefined {@code autoCommandBar} to carry the {@code id == -1} sentinel - the
     * value a designer-built form persists for its own command bar, keeping it out of the regular
     * element id space. A {@code 0}-id bar serializes WITHOUT an {@code <id>} element and EDT then
     * flags the form with {@code form-invalid-item-id}. Called by {@link #createContentForm} and again
     * by {@link #createForm} after the BM integration (which resets the id to the model default). A
     * no-op when the form has no command bar. Package-visible for the headless test. Issue #189.
     */
    static void enforceAutoCommandBarIdSentinel(EObject content)
    {
        EObject bar = singleReference(content, FEATURE_AUTO_COMMAND_BAR);
        if (bar != null)
        {
            setIntFeature(bar, FEATURE_ID, -1);
        }
    }

    /** The form model EPackage nsURI ({@code com._1c.g5.v8.dt.form.model.FormPackage.eNS_URI}). */
    private static final String FORM_PACKAGE_NS_URI = "http://g5.1c.ru/v8/dt/form"; //$NON-NLS-1$
    /** The mcore EPackage nsURI - holds {@code UndefinedValue} (the table's default rowFilter). */
    private static final String MCORE_PACKAGE_NS_URI = "http://g5.1c.ru/v8/dt/mcore"; //$NON-NLS-1$

    /**
     * The CONCRETE content {@code Form} EClass, reached WITHOUT a form-model import: the form
     * EPackage is resolved from the global EMF package registry by its nsURI
     * ({@code http://g5.1c.ru/v8/dt/form}) and the {@code Form} classifier by name on it. The
     * mdclass metamodel cannot lead here - the {@code BasicForm.form} reference is deliberately
     * typed by the mdclass-own {@code AbstractForm} base, NOT by the form package - so the registry
     * is the one compile-time-free route. Package-visible for the headless unit test.
     *
     * @throws RuntimeException (wrapped into the tool error by the caller) when the form model
     *     package is not available in this platform
     */
    static EClass contentFormEClass()
    {
        EPackage formPkg = EPackage.Registry.INSTANCE.getEPackage(FORM_PACKAGE_NS_URI);
        EClassifier concrete = formPkg != null ? formPkg.getEClassifier("Form") : null; //$NON-NLS-1$
        if (!(concrete instanceof EClass))
        {
            throw new IllegalStateException("The form model EPackage (" + FORM_PACKAGE_NS_URI //$NON-NLS-1$
                + ") is not available in this platform."); //$NON-NLS-1$
        }
        return (EClass)concrete;
    }

    /**
     * Sets the standard default form-level properties a managed form authored in EDT has, mirroring the
     * designer's {@code FormObjectFactory.newForm(owner, version)} INCLUDING its version branches:
     * <ul>
     * <li>always: {@code autoTitle}, {@code autoUrl}, {@code autoFillCheck}, {@code allowFormCustomize},
     * {@code enabled}, {@code showCloseButton} true;</li>
     * <li>version &lt; 8.5.1: {@code group = FormChildrenGroup.VERTICAL} and {@code showTitle = true};
     * version &gt;= 8.5.1: {@code group = FormChildrenGroup.AUTO} and
     * {@code showTitle851 = ShowTitle851.AUTO} (the wizard does NOT set the legacy boolean there);</li>
     * <li>{@code saveWindowSettings = true} only for version &gt; 8.3.22 (the wizard leaves it unset on
     * older compatibility versions);</li>
     * <li>an (empty) {@code FormCommandInterface} holding an empty navigation panel and command bar.</li>
     * </ul>
     * A {@code null} version is treated as the legacy (pre-8.5.1, post-8.3.22) shape, preserving the
     * previous behavior of this writer. Every feature is only filled when the factory did not already
     * set it ({@code eIsSet}), so a form built by the real {@code FormObjectFactory} keeps the factory's
     * version-correct values and this method is the authoritative writer only on the manual fallback.
     * The {@code autoCommandBar} is created separately (it is render-critical); this method does not
     * touch it. Reflective (by feature / classifier name), like every other write in this class.
     */
    private static void applyFormDefaults(EObject form, Version version)
    {
        setBooleanFeatureIfUnset(form, "autoTitle", true); //$NON-NLS-1$
        setBooleanFeatureIfUnset(form, "autoUrl", true); //$NON-NLS-1$
        setBooleanFeatureIfUnset(form, "autoFillCheck", true); //$NON-NLS-1$
        setBooleanFeatureIfUnset(form, "allowFormCustomize", true); //$NON-NLS-1$
        setBooleanFeatureIfUnset(form, FEATURE_ENABLED, true);
        setBooleanFeatureIfUnset(form, "showCloseButton", true); //$NON-NLS-1$
        boolean before851 = version == null || version.isLessThan(Version.V8_5_1);
        if (before851)
        {
            setEnumFeatureIfUnset(form, FEATURE_GROUP, LITERAL_VERTICAL);
            setBooleanFeatureIfUnset(form, "showTitle", true); //$NON-NLS-1$
        }
        else
        {
            setEnumFeatureIfUnset(form, FEATURE_GROUP, LITERAL_AUTO);
            // NOTE: ShowTitle851's EMF literal string is "auto" while its name is "Auto" - the
            // if-unset setter resolves both, case-insensitively.
            setEnumFeatureIfUnset(form, "showTitle851", LITERAL_AUTO); //$NON-NLS-1$
        }
        if (version == null || version.isGreaterThan(Version.V8_3_22))
        {
            setBooleanFeatureIfUnset(form, "saveWindowSettings", true); //$NON-NLS-1$
        }

        if (singleReference(form, FEATURE_COMMAND_INTERFACE) == null)
        {
            EObject commandInterface = createFromClassifier(form, ECLASS_FORM_COMMAND_INTERFACE);
            if (commandInterface != null)
            {
                setSingleReference(commandInterface, FEATURE_NAVIGATION_PANEL,
                    createFromClassifier(form, ECLASS_FORM_COMMAND_INTERFACE_ITEMS));
                setSingleReference(commandInterface, FEATURE_COMMAND_BAR,
                    createFromClassifier(form, ECLASS_FORM_COMMAND_INTERFACE_ITEMS));
                setSingleReference(form, FEATURE_COMMAND_INTERFACE, commandInterface);
            }
        }
    }

    /**
     * Builds the form's predefined automatic command bar, mirroring
     * {@code FormObjectFactory.newAutoCommandBar}: {@code autoFill = true}, {@code horizontalAlign =
     * LEFT}, id {@code -1} (the sentinel EDT persists for a form's own predefined command bar, keeping
     * it out of the regular element id space). The name follows the configuration script variant the
     * way the designer's default-name provider builds it for a predefined item on the form root
     * ({@code FormObjectDefaultNameProvider.getFormDefaultName + getDefaultName(COMMAND_BAR)}):
     * {@code FormCommandBar} for English, {@code ФормаКоманднаяПанель} for Russian.
     *
     * @param formModel any object of the form package (resolves the {@code AutoCommandBar} classifier)
     * @param russianAutoNames whether the configuration script variant is Russian
     * @return the bar, or {@code null} when the classifier does not resolve
     */
    private static EObject createDefaultAutoCommandBar(EObject formModel, boolean russianAutoNames)
    {
        EObject bar = createFromClassifier(formModel, ECLASS_AUTO_COMMAND_BAR);
        if (bar == null)
        {
            return null;
        }
        setBooleanFeature(bar, FEATURE_AUTO_FILL, true);
        setEnumFeature(bar, KEY_HORIZONTAL_ALIGN, "Left"); //$NON-NLS-1$
        setIntFeature(bar, FEATURE_ID, -1);
        setStringFeature(bar, FEATURE_NAME,
            russianAutoNames ? RU_FORM_COMMAND_BAR : EN_FORM_COMMAND_BAR);
        return bar;
    }

    /** en "FormCommandBar" - the canonical English predefined-command-bar name. */
    private static final String EN_FORM_COMMAND_BAR = "FormCommandBar"; //$NON-NLS-1$

    /** ru "ФормаКоманднаяПанель" - the canonical Russian predefined-command-bar name (pure-ASCII source). */
    private static final String RU_FORM_COMMAND_BAR = cp(0x0424, 0x043e, 0x0440, 0x043c, 0x0430,
        0x041a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x043d, 0x0430, 0x044f,
        0x041f, 0x0430, 0x043d, 0x0435, 0x043b, 0x044c);

    /**
     * Sets the owner's default object form via {@code setDefaultObjectForm(...)} when present. Uses
     * reflection because that setter is declared per owner type without a common interface; a missing
     * setter is reported clearly rather than failing silently.
     */
    private static void setDefaultObjectForm(MdObject owner, BasicForm mdForm)
    {
        for (Method method : owner.getClass().getMethods())
        {
            if (!"setDefaultObjectForm".equals(method.getName())) //$NON-NLS-1$
            {
                continue;
            }
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length == 1 && paramTypes[0].isInstance(mdForm))
            {
                try
                {
                    method.invoke(owner, mdForm);
                    return;
                }
                catch (ReflectiveOperationException e)
                {
                    throw new IllegalStateException("Failed to set default object form", e); //$NON-NLS-1$
                }
            }
        }
        throw new IllegalArgumentException("Owner type '" + owner.eClass().getName() //$NON-NLS-1$
            + "' has no compatible setDefaultObjectForm(...) method; create the form without " //$NON-NLS-1$
            + "setAsDefault and assign it manually."); //$NON-NLS-1$
    }

    /**
     * Finds a form by Name in {@code owner}'s {@code forms} collection (case-insensitive), or
     * {@code null} when the owner holds no such form (or supports no forms at all). The public
     * duplicate probe for the form-object create path, so the tool can honor
     * {@code expectedNotExists} with the same precondition semantics as every other create.
     *
     * @param owner the owner metadata object
     * @param formName the programmatic form Name to look for
     * @return the owned MD-form, or {@code null}
     */
    public static EObject findOwnedForm(MdObject owner, String formName)
    {
        EStructuralFeature formsFeature = owner.eClass().getEStructuralFeature(KEY_FORMS);
        if (formsFeature == null)
        {
            return null;
        }
        return findOwnedFormByName(owner, formsFeature, formName);
    }

    /** Finds a form by Name in the owner's {@code forms} collection (case-insensitive), or null. */
    private static EObject findOwnedFormByName(EObject owner, EStructuralFeature formsFeature, String name)
    {
        Object value = owner.eGet(formsFeature);
        if (value instanceof EList<?>)
        {
            for (Object form : (EList<?>)value)
            {
                if (form instanceof MdObject && name.equalsIgnoreCase(((MdObject)form).getName()))
                {
                    return (EObject)form;
                }
            }
        }
        return null;
    }

    private static String createAttribute(EObject formModel, String name, String titleLanguage,
        String title, String[] createdKind)
    {
        if (findByName(referenceList(formModel, FEATURE_ATTRIBUTES), name) != null)
        {
            return "Form attribute already exists: " + name; //$NON-NLS-1$
        }
        EObject attr = createFromFeatureType(formModel, FEATURE_ATTRIBUTES);
        if (attr == null)
        {
            return "Cannot create a form attribute for this form model."; //$NON-NLS-1$
        }
        setStringFeature(attr, FEATURE_NAME, name);
        setIntFeature(attr, FEATURE_ID, nextAttributeId(formModel));
        setDefaultValueType(attr);
        applyTitle(attr, titleLanguage, title);
        addToList(formModel, FEATURE_ATTRIBUTES, attr);
        recordKind(attr, createdKind);
        return null;
    }

    /**
     * Configures the form {@code attribute} as a <b>dynamic list with a custom query</b>, fully
     * reflectively (no compile dependency on the form model). If the attribute is not already a dynamic
     * list it is turned into one: a {@code DynamicListExtInfo} is created, the {@code DynamicList} value
     * type is set, {@code autoFillAvailableFields} is turned on (so the platform derives the available
     * fields from the query - no DCS {@code <fields>} block is authored), and the attribute is marked as
     * the form's main attribute when the form has none yet. Then {@code queryText} and/or
     * {@code customQuery} are applied to the ext-info. A non-null {@code queryText} implies
     * {@code customQuery=true} unless {@code customQuery} is given explicitly. Runs inside the BM write
     * transaction opened by the caller (the {@code attribute} is the tx-bound member).
     *
     * @param formModel the tx-bound content form
     * @param attribute the form attribute to configure (must be a {@code FormAttribute})
     * @param queryText the custom query text, or {@code null} to leave it unchanged
     * @param customQuery the explicit custom-query flag, or {@code null} to default it from
     *            {@code queryText}
     * @param mainTableFqn the FQN of the object whose main table the list reads from (its
     *            {@code DbViewDef} is set as the list's {@code mainTable}), or {@code null} to leave it
     *            unchanged
     * @param config the configuration, to resolve {@code mainTableFqn}
     * @param version the platform version (to build the {@code DynamicList} value type)
     * @return the feature names actually set (for the success payload), in apply order
     */
    public static List<String> configureDynamicListQuery(EObject formModel, EObject attribute,
        String queryText, Boolean customQuery, String mainTableFqn, Configuration config, Version version)
    {
        List<String> applied = new ArrayList<>();

        EObject extInfo = singleReference(attribute, FEATURE_EXT_INFO);
        boolean alreadyDynamicList = isDynamicListAttribute(attribute);
        if (!alreadyDynamicList && queryText == null && mainTableFqn == null)
        {
            // Converting a plain attribute to a dynamic list needs a query or a main table, so a bare
            // 'customQuery' toggle on a not-yet-dynamic-list attribute would create an incomplete list.
            // Require the query text (or main table) up front. (Toggling 'customQuery' on an EXISTING
            // dynamic list keeps its stored query, so it is allowed.)
            throw new FormValidationException(ToolResult.error(
                "To create a dynamic list, provide a 'queryText' (the custom query), e.g. " //$NON-NLS-1$
                + "{name:'queryText', value:'SELECT Ref, Description AS Description FROM " //$NON-NLS-1$
                + "Catalog.Products'}. 'customQuery' alone only toggles an attribute that is already a " //$NON-NLS-1$
                + "dynamic list.").toJson()); //$NON-NLS-1$
        }
        if (!alreadyDynamicList)
        {
            extInfo = convertPlainAttributeToDynamicList(formModel, attribute, version, applied);
        }

        boolean effectiveCustomQuery = customQuery != null ? customQuery.booleanValue() : queryText != null;
        if (queryText != null)
        {
            setStringFeature(extInfo, FEATURE_QUERY_TEXT, queryText);
            applied.add(FEATURE_QUERY_TEXT);
        }
        if (customQuery != null || queryText != null)
        {
            setBooleanFeature(extInfo, FEATURE_CUSTOM_QUERY, effectiveCustomQuery);
            applied.add(FEATURE_CUSTOM_QUERY);
            if (effectiveCustomQuery)
            {
                // A custom query without auto-filled fields trips the platform field-binding checks; keep
                // auto-fill on whenever the query is custom so EDT derives the available fields.
                setBooleanFeature(extInfo, FEATURE_AUTO_FILL_AVAILABLE_FIELDS, true);
            }
        }
        if (mainTableFqn != null)
        {
            applyMainTable(extInfo, config, mainTableFqn, applied);
        }
        return applied;
    }

    /**
     * Turns a plain form attribute into a dynamic list: creates the {@code DynamicListExtInfo}, sets the
     * {@code DynamicList} value type, turns on {@code autoFillAvailableFields} and {@code dynamicDataRead}
     * (mirroring the designer's new-list defaults), and marks the attribute as the form's main one when the
     * form has none yet. Appends {@code "dynamicList"} to {@code applied} and returns the new ext-info.
     */
    private static EObject convertPlainAttributeToDynamicList(EObject formModel, EObject attribute,
        Version version, List<String> applied)
    {
        setExtInfoClassifier(formModel, attribute, ECLASS_DYNAMIC_LIST_EXT_INFO);
        EObject extInfo = singleReference(attribute, FEATURE_EXT_INFO);
        if (extInfo == null)
        {
            throw new IllegalStateException(
                "The form model does not expose a DynamicListExtInfo classifier."); //$NON-NLS-1$
        }
        EObject dynamicListType = MetadataTypeBuilder.dynamicListType(version);
        EStructuralFeature valueTypeFeature = attribute.eClass().getEStructuralFeature(FEATURE_VALUE_TYPE);
        if (dynamicListType != null && valueTypeFeature instanceof EReference)
        {
            attribute.eSet(valueTypeFeature, dynamicListType);
        }
        setBooleanFeature(extInfo, FEATURE_AUTO_FILL_AVAILABLE_FIELDS, true);
        // The designer turns on "dynamic data reading" for a new dynamic list (the model default is
        // false); mirror it so an MCP-created list matches a designer-created one.
        setBooleanFeature(extInfo, FEATURE_DYNAMIC_DATA_READ, true);
        if (!hasMainAttribute(formModel))
        {
            setBooleanFeature(attribute, FEATURE_MAIN, true);
        }
        applied.add("dynamicList"); //$NON-NLS-1$
        return extInfo;
    }

    /**
     * Resolves {@code mainTableFqn} to its {@code DbViewDef} and sets it as the dynamic list's
     * {@code mainTable} reference, appending {@code "mainTable"} to {@code applied}. Throws a
     * {@link FormValidationException} when the FQN cannot be resolved.
     */
    private static void applyMainTable(EObject extInfo, Configuration config, String mainTableFqn,
        List<String> applied)
    {
        EObject mainTable = resolveMainTableDbView(config, mainTableFqn);
        if (mainTable == null)
        {
            throw new FormValidationException(ToolResult.error(
                "Cannot resolve the main table '" + mainTableFqn + "'. Pass the FQN of the object " //$NON-NLS-1$ //$NON-NLS-2$
                + "the list reads from, e.g. 'Catalog.Products' or 'Document.Order'.").toJson()); //$NON-NLS-1$
        }
        EStructuralFeature mainTableFeature = extInfo.eClass().getEStructuralFeature(FEATURE_MAIN_TABLE);
        if (mainTableFeature instanceof EReference)
        {
            extInfo.eSet(mainTableFeature, mainTable);
            applied.add(FEATURE_MAIN_TABLE);
        }
    }

    /** Whether a form attribute carries a {@code DynamicListExtInfo} (i.e. it is a dynamic list). */
    private static boolean isDynamicListAttribute(EObject attribute)
    {
        EObject extInfo = singleReference(attribute, FEATURE_EXT_INFO);
        return extInfo != null && ECLASS_DYNAMIC_LIST_EXT_INFO.equals(extInfo.eClass().getName());
    }

    /**
     * Resolves an object FQN (e.g. {@code "Document.Order"}) to the {@code DbViewDef} of its MAIN table -
     * the value a dynamic list's {@code mainTable} reference holds. Reflective (no compile dependency on
     * the dbview model): {@code MdObject.dbViewDefs} -> the typed {@code *DbViewDefs} container ->
     * {@code mainView}. Returns {@code null} when the object, its db-view container, or the main view
     * cannot be resolved (e.g. derived data not yet computed). Must run inside the BM transaction so the
     * resolved DbViewDef is the project's canonical one.
     */
    private static EObject resolveMainTableDbView(Configuration config, String fqn)
    {
        if (config == null)
        {
            return null;
        }
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, fqn);
        if (node == null || node.object == null)
        {
            return null;
        }
        EObject md = node.object;
        EStructuralFeature dbViewDefsFeature = md.eClass().getEStructuralFeature("dbViewDefs"); //$NON-NLS-1$
        if (dbViewDefsFeature == null || !(md.eGet(dbViewDefsFeature) instanceof EObject))
        {
            return null;
        }
        EObject dbViewDefs = (EObject)md.eGet(dbViewDefsFeature);
        EStructuralFeature mainViewFeature = dbViewDefs.eClass().getEStructuralFeature("mainView"); //$NON-NLS-1$
        if (mainViewFeature == null)
        {
            return null;
        }
        Object mainView = dbViewDefs.eGet(mainViewFeature);
        return mainView instanceof EObject ? (EObject)mainView : null;
    }

    /** Whether the form already has an attribute flagged as its main data source. */
    private static boolean hasMainAttribute(EObject formModel)
    {
        for (EObject attr : referenceList(formModel, FEATURE_ATTRIBUTES))
        {
            EStructuralFeature mainFeature = attr.eClass().getEStructuralFeature(FEATURE_MAIN);
            if (mainFeature != null && Boolean.TRUE.equals(attr.eGet(mainFeature)))
            {
                return true;
            }
        }
        return false;
    }

    private static String createCommand(EObject formModel, String name, String titleLanguage,
        String title, String[] createdKind)
    {
        if (findByName(referenceList(formModel, FEATURE_FORM_COMMANDS), name) != null)
        {
            return "Form command already exists: " + name; //$NON-NLS-1$
        }
        EObject cmd = createFromFeatureType(formModel, FEATURE_FORM_COMMANDS);
        if (cmd == null)
        {
            return "Cannot create a form command for this form model."; //$NON-NLS-1$
        }
        setStringFeature(cmd, FEATURE_NAME, name);
        setIntFeature(cmd, FEATURE_ID, nextCommandId(formModel));
        // The platform factory's defaults: use=AdjustableBoolean(common) and currentRowUse=Auto -
        // without them the exported command is unusable.
        setAdjustableBooleanFeature(cmd, FEATURE_USE);
        setEnumFeature(cmd, "currentRowUse", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        applyTitle(cmd, titleLanguage, title);
        addToList(formModel, FEATURE_FORM_COMMANDS, cmd);
        recordKind(cmd, createdKind);
        return null;
    }

    private static String createItem(EObject formModel, Kind kind, String name, String parentName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        String groupTypeLiteral, String titleLanguage, String title, boolean russianAutoNames,
        String[] createdKind)
    {
        if (findItem(formModel, name) != null)
        {
            return ERR_ITEM_EXISTS + name;
        }
        EObject container = containerFor(formModel, parentName);
        if (container == null)
        {
            return parentNotFound(parentName);
        }
        String invalid = validatePlacement(kind, container, parentName);
        if (invalid != null)
        {
            return invalid;
        }
        String classifier = kind == Kind.GROUP ? ECLASS_FORM_GROUP : ECLASS_DECORATION;
        EObject item = createFromClassifier(formModel, classifier);
        if (item == null)
        {
            return "Cannot create a form " + classifier + " for this form model."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        // An explicit group type ({name:'type', value:'Popup'}) is validated against the model's
        // ManagedFormGroupType literals (case-insensitive); the container default applies otherwise.
        String requestedType = null;
        if (kind == Kind.GROUP && groupTypeLiteral != null && !groupTypeLiteral.isEmpty())
        {
            requestedType = resolveEnumLiteral(item, FEATURE_TYPE, groupTypeLiteral);
            if (requestedType == null)
            {
                return "Unknown group type '" + groupTypeLiteral + "'. Allowed group types: " //$NON-NLS-1$ //$NON-NLS-2$
                    + enumLiteralsOf(item, FEATURE_TYPE) + "."; //$NON-NLS-1$
            }
        }
        setStringFeature(item, FEATURE_NAME, name);
        applyVisibleDefaults(item);
        setIntFeature(item, FEATURE_ID, nextItemId(formModel));
        if (kind == Kind.DECORATION)
        {
            setBooleanFeature(item, KEY_AUTO_MAX_WIDTH, true);
            setBooleanFeature(item, KEY_AUTO_MAX_HEIGHT, true);
        }
        initManagedItem(formModel, item, kind, container, requestedType);
        applyTitle(item, titleLanguage, title);
        addToList(container, FEATURE_ITEMS, item);
        // The designer's auto-children: a decoration carries a context menu + an extended tooltip,
        // a group only the tooltip (FormObjectFactory.newDecoration / newFormGroup).
        addAutoChildren(formModel, item, kind == Kind.DECORATION, russianAutoNames);
        recordKind(item, createdKind);
        return null;
    }

    // ---- move / reorder -------------------------------------------------------------------------

    /** Position spec prefixes (the integer / {@code first} / {@code last} forms have no prefix). */
    private static final String POS_FIRST = "first"; //$NON-NLS-1$
    private static final String POS_LAST = "last"; //$NON-NLS-1$
    private static final String POS_BEFORE = "before:"; //$NON-NLS-1$
    private static final String POS_AFTER = "after:"; //$NON-NLS-1$

    /**
     * Moves an EXISTING visual form item under a new parent container (the form root for a blank
     * {@code parentName}, the auto command bar for the {@code AutoCommandBar} token, a named item
     * otherwise), appending it at the end - the position-less variant of
     * {@link #moveItem(EObject, EObject, String, String, String)}.
     *
     * @return {@code null} on success, or a human-readable error message
     */
    public static String moveItem(EObject formModel, EObject item, String parentName)
    {
        return moveItem(formModel, item, parentName, null, null);
    }

    /**
     * Moves an EXISTING visual form item under a new parent container and/or to a new position among
     * the destination's children. The parent resolves like a create ({@code containerFor}): the form
     * root for a blank {@code parentName} OR the form's own name ({@code formName}), the auto command
     * bar for the {@code AutoCommandBar} token, a named container otherwise - with the same placement
     * validation a create applies. A button's type is re-derived when it crosses a command-bar
     * boundary (CommandBarButton &harr; UsualButton). The designer's auto-children (tooltips /
     * context menus / command bars) are not movable. The {@code position} spec ({@code first} /
     * {@code last} / {@code before:&lt;name&gt;} / {@code after:&lt;name&gt;} / a 0-based FINAL
     * integer index, see {@link #resolveMovePosition}) picks the insertion index; {@code null}
     * appends at the end. Must run inside a BM write transaction on the tx-bound form model.
     *
     * @return {@code null} on success, or a human-readable error message (a malformed position spec
     *     THROWS a {@code RuntimeException} carrying the user-facing message instead)
     */
    public static String moveItem(EObject formModel, EObject item, String parentName, String position,
        String formName)
    {
        boolean toRoot = parentName == null || parentName.isEmpty()
            || (formName != null && parentName.equalsIgnoreCase(formName));
        EObject container = toRoot ? formModel : containerFor(formModel, parentName);
        if (container == null)
        {
            return parentNotFound(parentName);
        }
        return moveItemInto(formModel, item, container,
            toRoot ? "the form root" : parentName, position); //$NON-NLS-1$
    }

    /**
     * Resolves the moved item BY NAME - rejecting an AMBIGUOUS name (more than one match anywhere in
     * the form-item tree) instead of silently moving the first match - then delegates to the
     * container-resolving move. This is the {@code modify_metadata} entry point and implements its
     * 'parent' contract: {@code null} keeps the CURRENT container (a pure reorder); blank or the
     * form's own name means the form ROOT; anything else resolves like a create parent (a group /
     * table / {@code AutoCommandBar} / ...).
     *
     * @param formModel the editable form content model (tx-bound)
     * @param itemName the programmatic name of the item to move
     * @param targetParent the destination container name; blank or equal to {@code formName} means
     *     the form root; {@code null} keeps the item in its current container (reorder in place)
     * @param position the destination position spec, or {@code null} to append at the end
     * @param formName the MD-form Name (matching it as {@code targetParent} means the form root)
     * @return a human-readable description of where the item ended up (e.g. {@code "group 'Main' at
     *     index 1"})
     * @throws RuntimeException with a user-facing message on any rejection (the calling write lambda
     *     rolls back with no partial mutation)
     */
    public static String moveItem(EObject formModel, String itemName, String targetParent,
        String position, String formName)
    {
        EObject item = findUniqueItem(formModel, itemName);
        if (item == null)
        {
            throw new IllegalArgumentException("Form item not found: '" + itemName //$NON-NLS-1$
                + "'. Use get_metadata_details on the form to inspect its items."); //$NON-NLS-1$
        }
        String err;
        if (targetParent == null)
        {
            // Reorder in place: the destination is the item's CURRENT container.
            EObject container = item.eContainer();
            if (container == null)
            {
                throw new IllegalStateException("Form item '" + itemName //$NON-NLS-1$
                    + "' has no parent container and cannot be moved."); //$NON-NLS-1$
            }
            err = moveItemInto(formModel, item, container, containerLabel(formModel, container),
                position);
        }
        else
        {
            err = moveItem(formModel, item, targetParent, position, formName);
        }
        if (err != null)
        {
            throw new IllegalArgumentException(err);
        }
        return destinationOf(formModel, item);
    }

    /**
     * The shared move core: validates the item and the destination (the designer-parity guards a
     * create applies), resolves the insertion index, performs the containment move and re-derives a
     * button's type. ALL validation precedes the first mutation, so an error leaves the model
     * untouched (and the surrounding BM transaction rolls back clean).
     */
    @SuppressWarnings("unchecked")
    private static String moveItemInto(EObject formModel, EObject item, EObject container, // NOSONAR reflective/form or transport god-method; further extraction deferred (reflective code)
        String parentLabel, String position)
    {
        EClassifier formItem = formModel.eClass().getEPackage().getEClassifier(ECLASS_FORM_ITEM);
        if (!(formItem instanceof EClass) || !((EClass)formItem).isInstance(item))
        {
            return "Only a visual form item (field / button / group / decoration / table) can be " //$NON-NLS-1$
                + "moved; '" + item.eClass().getName() //$NON-NLS-1$
                + "' is not one. Attributes and commands have no visual parent."; //$NON-NLS-1$
        }
        if (item.eContainmentFeature() == null
            || !FEATURE_ITEMS.equals(item.eContainmentFeature().getName()))
        {
            return "'" + stringFeature(item, FEATURE_NAME) + "' is a designer auto-child (" //$NON-NLS-1$ //$NON-NLS-2$
                + item.eClass().getName() + ") and cannot be moved."; //$NON-NLS-1$
        }
        if (container == item)
        {
            return "An item cannot become its own parent."; //$NON-NLS-1$
        }
        for (EObject ancestor = container; ancestor != null; ancestor = ancestor.eContainer())
        {
            if (ancestor == item)
            {
                return "Cannot move '" + stringFeature(item, FEATURE_NAME) //$NON-NLS-1$
                    + "' into its own contained item '" + parentLabel //$NON-NLS-1$
                    + "': an item cannot be moved into itself or its own descendant."; //$NON-NLS-1$
            }
        }
        Kind kind = kindForEClass(item.eClass().getName());
        if (kind != null)
        {
            String invalid = validatePlacement(kind, container, parentLabel);
            if (invalid != null)
            {
                return invalid;
            }
        }
        EStructuralFeature itemsFeature = container.eClass().getEStructuralFeature(FEATURE_ITEMS);
        if (!(itemsFeature instanceof EReference) || !itemsFeature.isMany())
        {
            return "The parent '" + parentLabel + "' (" + container.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ") cannot hold nested items."; //$NON-NLS-1$
        }
        EList<EObject> destItems = (EList<EObject>)container.eGet(itemsFeature);
        // Resolve the index BEFORE any mutation (a bad position spec throws and leaves the model
        // untouched). The sibling names EXCLUDE the moved item, so the integer index is the desired
        // FINAL 0-based position in both the reorder-in-place and the cross-container case.
        List<String> destNames = new ArrayList<>(destItems.size());
        for (EObject sibling : destItems)
        {
            if (sibling != item)
            {
                destNames.add(stringFeature(sibling, FEATURE_NAME));
            }
        }
        int index = resolveMovePosition(position, destNames, stringFeature(item, FEATURE_NAME));
        EList<EObject> sourceItems =
            (EList<EObject>)item.eContainer().eGet(item.eContainmentFeature());
        sourceItems.remove(item);
        if (index < 0 || index > destItems.size())
        {
            index = destItems.size();
        }
        destItems.add(index, item);
        if (ELEM_BUTTON.equals(item.eClass().getName()))
        {
            setEnumFeature(item, FEATURE_TYPE,
                isCommandBarContext(container) ? "CommandBarButton" : "UsualButton"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Resolves a requested {@code position} into a 0-based insertion index in a destination list whose
     * sibling names are {@code destNames} (already EXCLUDING the moved item). The {@code first} /
     * {@code last} / {@code before:<name>} / {@code after:<name>} forms are name-relative; a plain
     * integer is the desired FINAL index as-is. Pure (no model dependency) so it is unit-testable.
     *
     * @param position the position spec, or {@code null} / blank / {@code last} for the end
     * @param destNames the destination sibling names in order (without the moved item)
     * @param movedName the moved item's name (a {@code before:}/{@code after:} reference to it is rejected)
     * @return the 0-based insertion index
     * @throws RuntimeException with a user-facing message on a malformed spec or unknown sibling
     */
    public static int resolveMovePosition(String position, List<String> destNames, String movedName)
    {
        if (position == null || position.isEmpty() || POS_LAST.equalsIgnoreCase(position))
        {
            return destNames.size();
        }
        if (POS_FIRST.equalsIgnoreCase(position))
        {
            return 0;
        }
        String lower = position.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith(POS_BEFORE))
        {
            return indexOfSibling(destNames, position.substring(POS_BEFORE.length()).trim(), movedName);
        }
        if (lower.startsWith(POS_AFTER))
        {
            return indexOfSibling(destNames, position.substring(POS_AFTER.length()).trim(), movedName) + 1;
        }
        try
        {
            int idx = Integer.parseInt(position.trim());
            if (idx < 0)
            {
                throw new IllegalArgumentException("Invalid position index '" + position //$NON-NLS-1$
                    + "': must be zero or positive."); //$NON-NLS-1$
            }
            return idx;
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Invalid position '" + position //$NON-NLS-1$
                + "'. Expected an integer index, 'first', 'last', 'before:<name>' or 'after:<name>'."); //$NON-NLS-1$
        }
    }

    /** The 0-based index of {@code sibling} in {@code destNames} (case-insensitive), or throws. */
    private static int indexOfSibling(List<String> destNames, String sibling, String movedName)
    {
        if (sibling.isEmpty())
        {
            throw new IllegalArgumentException("Position reference is missing a sibling name " //$NON-NLS-1$
                + "(use 'before:<name>' or 'after:<name>')."); //$NON-NLS-1$
        }
        if (sibling.equalsIgnoreCase(movedName))
        {
            throw new IllegalArgumentException("Position cannot reference the moved item itself: '" //$NON-NLS-1$
                + sibling + "'."); //$NON-NLS-1$
        }
        for (int i = 0; i < destNames.size(); i++)
        {
            if (sibling.equalsIgnoreCase(destNames.get(i)))
            {
                return i;
            }
        }
        throw new IllegalArgumentException("Sibling '" + sibling //$NON-NLS-1$
            + "' not found in the destination container."); //$NON-NLS-1$
    }

    /** Where the item now lives, for the move result: the container label + the final 0-based index. */
    @SuppressWarnings("unchecked")
    private static String destinationOf(EObject formModel, EObject item)
    {
        EObject container = item.eContainer();
        int index = ((EList<EObject>)container.eGet(item.eContainmentFeature())).indexOf(item);
        return containerLabel(formModel, container) + " at index " + index; //$NON-NLS-1$
    }

    /** "the form root" / "group 'X'" / "'X' (AutoCommandBar)" - the user-facing container label. */
    private static String containerLabel(EObject formModel, EObject container)
    {
        if (container == formModel)
        {
            return "the form root"; //$NON-NLS-1$
        }
        if (ECLASS_FORM_GROUP.equals(container.eClass().getName()))
        {
            return "group '" + stringFeature(container, FEATURE_NAME) + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "'" + stringFeature(container, FEATURE_NAME) + "' (" + container.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
            + ")"; //$NON-NLS-1$
    }

    /**
     * Finds a form item by name anywhere in the form-item tree (the same all-containment walk
     * {@code findItem} uses: items, command bars, context menus, tooltips), REJECTING an ambiguous
     * name (more than one match) with a clear error rather than silently picking the first match.
     * Returns the unique match, or {@code null} when none exists.
     */
    private static EObject findUniqueItem(EObject formModel, String name)
    {
        EClassifier formItem = formModel.eClass().getEPackage().getEClassifier(ECLASS_FORM_ITEM);
        if (!(formItem instanceof EClass))
        {
            return null;
        }
        List<EObject> matches = new ArrayList<>();
        collectItemsByName(formModel, name, (EClass)formItem, matches);
        if (matches.size() > 1)
        {
            throw new IllegalArgumentException("Form item name '" + name //$NON-NLS-1$
                + "' is ambiguous (it matches more than one item)."); //$NON-NLS-1$
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    /** Collects every {@code FormItem} in the tree whose name matches (case-insensitive). */
    private static void collectItemsByName(EObject container, String name, EClass formItem,
        List<EObject> out)
    {
        for (EObject child : container.eContents())
        {
            if (!formItem.isInstance(child))
            {
                continue;
            }
            if (name.equalsIgnoreCase(stringFeature(child, FEATURE_NAME)))
            {
                out.add(child);
            }
            collectItemsByName(child, name, formItem, out);
        }
    }

    /** The placement-rule {@link Kind} for a concrete item EClass name, or {@code null} when none. */
    private static Kind kindForEClass(String eClassName)
    {
        if (ELEM_BUTTON.equals(eClassName))
        {
            return Kind.BUTTON;
        }
        if (ECLASS_DECORATION.equals(eClassName))
        {
            return Kind.DECORATION;
        }
        return null;
    }

    /** Resolves a requested EEnum literal case-insensitively to its canonical form, or {@code null}. */
    private static String resolveEnumLiteral(EObject owner, String featureName, String requested)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EAttribute)
            || !(((EAttribute)feature).getEAttributeType() instanceof EEnum))
        {
            return null;
        }
        for (EEnumLiteral literal : ((EEnum)((EAttribute)feature).getEAttributeType()).getELiterals())
        {
            if (literal.getLiteral().equalsIgnoreCase(requested))
            {
                return literal.getLiteral();
            }
        }
        return null;
    }

    /** A comma-separated list of an EEnum attribute's literals (for the unknown-literal advisory). */
    private static String enumLiteralsOf(EObject owner, String featureName)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EAttribute)
            || !(((EAttribute)feature).getEAttributeType() instanceof EEnum))
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        for (EEnumLiteral literal : ((EEnum)((EAttribute)feature).getEAttributeType()).getELiterals())
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(literal.getLiteral());
        }
        return sb.toString();
    }

    /** A FormField bound to a form attribute via its dataPath (a generic InputField the user can refine). */
    private static String createField(EObject formModel, String name, String parentName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        String attrName, String titleLanguage, String title, boolean russianAutoNames,
        String[] createdKind)
    {
        if (attrName == null || attrName.isEmpty())
        {
            return "A form field needs a 'dataPath' property naming the form attribute it shows " //$NON-NLS-1$
                + "(e.g. {name:'dataPath', value:'Price'})."; //$NON-NLS-1$
        }
        // The field binds to a form attribute by name. A DOTTED path (e.g. "List.Number") binds to a
        // dynamic-list COLUMN: the head segment is the dynamic-list attribute, the tail is one of its
        // query fields (auto-filled by EDT - not a model attribute). Validate the head attribute, and
        // require it to be a dynamic list when a dotted path is used.
        int dot = attrName.indexOf('.');
        String headAttr = dot > 0 ? attrName.substring(0, dot) : attrName;
        EObject boundAttribute = findByName(referenceList(formModel, FEATURE_ATTRIBUTES), headAttr);
        if (boundAttribute == null)
        {
            return "Form attribute '" + headAttr + "' not found - create it first, then bind the field " //$NON-NLS-1$ //$NON-NLS-2$
                + "to it (so the data path resolves)."; //$NON-NLS-1$
        }
        if (dot > 0 && !isDynamicListAttribute(boundAttribute))
        {
            return "'" + attrName + "' is a nested data path, but '" + headAttr + "' is not a dynamic " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "list. A field binds to a form attribute by name (e.g. 'Price'); a dotted path is only " //$NON-NLS-1$
                + "for a dynamic-list column (e.g. 'List.Number', where the list has a custom query)."; //$NON-NLS-1$
        }
        if (findItem(formModel, name) != null)
        {
            return ERR_ITEM_EXISTS + name;
        }
        EObject container = containerFor(formModel, parentName);
        if (container == null)
        {
            return parentNotFound(parentName);
        }
        EObject item = createFromClassifier(formModel, ECLASS_FORM_FIELD);
        if (item == null)
        {
            return "Cannot create a form field for this form model."; //$NON-NLS-1$
        }
        setStringFeature(item, FEATURE_NAME, name);
        applyVisibleDefaults(item);
        setIntFeature(item, FEATURE_ID, nextItemId(formModel));
        // dataPath: a contained DataPath whose segments are the DOT-SPLIT parts of attrName, so the
        // validator resolves it segment by segment - a plain attribute ("Price" -> ["Price"]) and a
        // dynamic-list column ("List.Number" -> ["List", "Number"]) alike. A single dotted segment
        // would be read as one object name and flagged form-data-path. (objects is transient - the
        // form's derived data recomputes it.)
        buildDataPath(formModel, item, attrName);
        // Pure-model default field type (InputField + a fresh InputFieldExtInfo), as the platform's
        // own factory does before the value type is known.
        setEnumFeature(item, FEATURE_TYPE, "InputField"); //$NON-NLS-1$
        setExtInfoClassifier(formModel, item, ECLASS_INPUT_FIELD_EXT_INFO);
        // The designer's new-field defaults (FormObjectFactory.newFormField / newInputFieldExtInfo); // NOSONAR explanatory comment, not commented-out code
        // the booleans default to false in the model, so without them a created field renders with
        // no table header/footer, no wrap and a read-only text box. 'Auto'-valued enums are the
        // model defaults (literal 0) and stay unset, like the XMI omits them.
        setBooleanFeature(item, "showInHeader", true); //$NON-NLS-1$
        setBooleanFeature(item, "showInFooter", true); //$NON-NLS-1$
        setEnumFeature(item, "headerHorizontalAlign", "Left"); //$NON-NLS-1$ //$NON-NLS-2$
        setEnumFeature(item, "editMode", "Enter"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject extInfo = singleReference(item, FEATURE_EXT_INFO);
        if (extInfo != null)
        {
            setBooleanFeature(extInfo, KEY_AUTO_MAX_WIDTH, true);
            setBooleanFeature(extInfo, KEY_AUTO_MAX_HEIGHT, true);
            setBooleanFeature(extInfo, "wrap", true); //$NON-NLS-1$
            setBooleanFeature(extInfo, "chooseType", true); //$NON-NLS-1$
            setBooleanFeature(extInfo, "typeDomainEnabled", true); //$NON-NLS-1$
            setBooleanFeature(extInfo, "textEdit", true); //$NON-NLS-1$
        }
        applyTitle(item, titleLanguage, title);
        addToList(container, FEATURE_ITEMS, item);
        // A field carries both designer auto-children (context menu + extended tooltip).
        addAutoChildren(formModel, item, true, russianAutoNames);
        recordKind(item, createdKind);
        return null;
    }

    /**
     * Creates a {@code form:Table} bound to a tabular section, mirroring what the designer builds when
     * a tabular section is dropped on a form: the Table (dataPath {@code Object.<TabularSection>}), its
     * own command bar / context menu / extended tooltip, the table scalar defaults, and one column per
     * given tabular-section attribute (plus the standard {@code LineNumber} column). The column names
     * come from the metadata-aware caller (the form model alone cannot enumerate them); an empty list
     * yields a column-less table the caller can fill later.
     *
     * @param dataPath the tabular-section data path, e.g. {@code Object.Goods}
     * @param columnAttributeNames the tabular-section attribute names to materialize as input columns
     * @return {@code null} on success, or a human-readable error message
     */
    public static String createTable(EObject formModel, String name, String parentName, String dataPath, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        List<String> columnAttributeNames, String titleLanguage, String title, boolean russianAutoNames,
        String[] createdKind)
    {
        if (dataPath == null || dataPath.isEmpty())
        {
            return "A table needs a 'dataPath' property naming the tabular section it shows " //$NON-NLS-1$
                + "(e.g. {name:'dataPath', value:'Object.Goods'})."; //$NON-NLS-1$
        }
        if (findItem(formModel, name) != null)
        {
            return ERR_ITEM_EXISTS + name;
        }
        EObject container = containerFor(formModel, parentName);
        if (container == null)
        {
            return parentNotFound(parentName);
        }
        EObject table = createFromClassifier(formModel, ECLASS_TABLE);
        if (table == null)
        {
            return "Cannot create a form table for this form model."; //$NON-NLS-1$
        }
        setStringFeature(table, FEATURE_NAME, name);
        applyVisibleDefaults(table);
        setIntFeature(table, FEATURE_ID, nextItemId(formModel));
        buildDataPath(formModel, table, dataPath);
        setEnumFeature(table, "titleLocation", "None"); //$NON-NLS-1$ //$NON-NLS-2$
        applyTableDefaults(table);
        setUndefinedRowFilter(table);
        // The designer's table carries a title = its own name (hidden by titleLocation=None); use the
        // caller's explicit title when given, otherwise default to the table name in the script variant.
        if (title != null && !title.isEmpty())
        {
            applyTitle(table, titleLanguage, title);
        }
        else
        {
            applyTitle(table, russianAutoNames ? "ru" : "en", name); //$NON-NLS-1$ //$NON-NLS-2$
        }
        addToList(container, FEATURE_ITEMS, table);
        // The table's own command bar carries a NORMAL id (only the form-root bar uses the -1 sentinel).
        addTableAutoCommandBar(formModel, table, russianAutoNames);
        // The designer auto-children every visual item carries: a context menu + an extended tooltip.
        addAutoChildren(formModel, table, true, russianAutoNames);
        // The three table "additions" the designer adds: a search string, a view status and a search
        // control (each with its own auto-children + extInfo, sourced from the table).
        addTableAddition(formModel, table, FEATURE_SEARCH_STRING_ADDITION,
            russianAutoNames ? RU_SUFFIX_SEARCH_STRING : SUFFIX_SEARCH_STRING, null,
            "SearchStringAdditionExtInfo", russianAutoNames); //$NON-NLS-1$
        addTableAddition(formModel, table, FEATURE_VIEW_STATUS_ADDITION,
            russianAutoNames ? RU_SUFFIX_VIEW_STATUS : SUFFIX_VIEW_STATUS,
            "ViewStatusAddition", "ViewStatusAdditionExtInfo", russianAutoNames); //$NON-NLS-1$ //$NON-NLS-2$
        addTableAddition(formModel, table, FEATURE_SEARCH_CONTROL_ADDITION,
            russianAutoNames ? RU_SUFFIX_SEARCH_CONTROL : SUFFIX_SEARCH_CONTROL,
            "SearchControlAddition", "SearchControlAdditionExtInfo", russianAutoNames); //$NON-NLS-1$ //$NON-NLS-2$
        // Auto-columns: the standard LineNumber column, then one column per TS attribute (all input
        // fields, like the designer's table output).
        String lineNumber = russianAutoNames ? RU_LINE_NUMBER : EN_LINE_NUMBER;
        buildColumnField(formModel, table, name + lineNumber, dataPath + "." + lineNumber, russianAutoNames); //$NON-NLS-1$
        if (columnAttributeNames != null)
        {
            for (String attr : columnAttributeNames)
            {
                buildColumnField(formModel, table, name + attr, dataPath + "." + attr, russianAutoNames); //$NON-NLS-1$
            }
        }
        recordKind(table, createdKind);
        return null;
    }

    /**
     * Sets a contained {@code DataPath} whose {@code segments} are the dot-split parts of
     * {@code pathString} (e.g. {@code Object.Goods.Product} -> three segments). The validator resolves
     * the path segment by segment (the {@code Object} attribute -> its type -> the {@code Goods}
     * tabular section -> {@code Product}); a single dotted segment would be read as one object name and
     * flagged {@code form-data-path}. The on-disk serializer re-joins the segments with dots, so this
     * stays byte-identical to a designer-built path.
     */
    @SuppressWarnings("unchecked")
    private static void buildDataPath(EObject formModel, EObject item, String pathString)
    {
        EStructuralFeature dpFeat = item.eClass().getEStructuralFeature("dataPath"); //$NON-NLS-1$
        EObject dataPath = createFromClassifier(formModel, "DataPath"); //$NON-NLS-1$
        if (dpFeat instanceof EReference && dataPath != null)
        {
            EStructuralFeature segFeat = dataPath.eClass().getEStructuralFeature("segments"); //$NON-NLS-1$
            if (segFeat != null && dataPath.eGet(segFeat) instanceof EList<?>)
            {
                EList<String> segments = (EList<String>)dataPath.eGet(segFeat);
                for (String part : pathString.split("\\.")) //$NON-NLS-1$
                {
                    if (!part.isEmpty())
                    {
                        segments.add(part);
                    }
                }
            }
            item.eSet(dpFeat, dataPath);
        }
    }

    /**
     * Builds one table column: an {@code InputField} {@code FormField} bound to {@code columnDataPath},
     * with the designer column defaults ({@code InputFieldExtInfo} + flags) and the auto-children, added
     * to the table. The designer makes every tabular-section column - including the row-number column -
     * an input field.
     */
    private static void buildColumnField(EObject formModel, EObject table, String name,
        String columnDataPath, boolean russianAutoNames)
    {
        EObject column = createFromClassifier(formModel, ECLASS_FORM_FIELD);
        if (column == null)
        {
            return;
        }
        setStringFeature(column, FEATURE_NAME, uniqueChildName(formModel, name, "")); //$NON-NLS-1$
        applyVisibleDefaults(column);
        setIntFeature(column, FEATURE_ID, nextItemId(formModel));
        buildDataPath(formModel, column, columnDataPath);
        setEnumFeature(column, FEATURE_TYPE, "InputField"); //$NON-NLS-1$
        setBooleanFeature(column, "showInHeader", true); //$NON-NLS-1$
        setBooleanFeature(column, "showInFooter", true); //$NON-NLS-1$
        setEnumFeature(column, "headerHorizontalAlign", "Left"); //$NON-NLS-1$ //$NON-NLS-2$
        setEnumFeature(column, "editMode", "EnterOnInput"); //$NON-NLS-1$ //$NON-NLS-2$
        setExtInfoClassifier(formModel, column, ECLASS_INPUT_FIELD_EXT_INFO);
        EObject extInfo = singleReference(column, FEATURE_EXT_INFO);
        if (extInfo != null)
        {
            setBooleanFeature(extInfo, KEY_AUTO_MAX_WIDTH, true);
            setBooleanFeature(extInfo, KEY_AUTO_MAX_HEIGHT, true);
            setBooleanFeature(extInfo, "wrap", true); //$NON-NLS-1$
            setBooleanFeature(extInfo, "chooseType", true); //$NON-NLS-1$
            setBooleanFeature(extInfo, "typeDomainEnabled", true); //$NON-NLS-1$
            setBooleanFeature(extInfo, "textEdit", true); //$NON-NLS-1$
        }
        addToList(table, FEATURE_ITEMS, column);
        addAutoChildren(formModel, column, true, russianAutoNames);
    }

    /** The table's own predefined command bar: a NORMAL id, autoFill, left align, script-variant named. */
    private static void addTableAutoCommandBar(EObject formModel, EObject table, boolean russianAutoNames)
    {
        EStructuralFeature barFeat = table.eClass().getEStructuralFeature(FEATURE_AUTO_COMMAND_BAR);
        EObject bar = createFromClassifier(formModel, ECLASS_AUTO_COMMAND_BAR);
        if (bar == null || !(barFeat instanceof EReference) || barFeat.isMany())
        {
            return;
        }
        setStringFeature(bar, FEATURE_NAME, uniqueChildName(formModel, stringFeature(table, FEATURE_NAME),
            russianAutoNames ? RU_SUFFIX_COMMAND_BAR : SUFFIX_COMMAND_BAR));
        setBooleanFeature(bar, FEATURE_AUTO_FILL, true);
        setEnumFeature(bar, KEY_HORIZONTAL_ALIGN, "Left"); //$NON-NLS-1$
        table.eSet(barFeat, bar);
        setIntFeature(bar, FEATURE_ID, nextItemId(formModel));
    }

    /**
     * Adds one table "addition" (a {@code searchStringAddition} / {@code viewStatusAddition} /
     * {@code searchControlAddition} - the search box, view-status line and search control the designer
     * puts on a table). Named {@code <table><suffix>}, sourced from the table, carries its own extInfo
     * and the auto-children (extended tooltip + context menu). A no-op when the feature is absent.
     */
    private static void addTableAddition(EObject formModel, EObject table, String featureName,
        String suffix, String typeLiteral, String extInfoClassifier, boolean russianAutoNames)
    {
        EStructuralFeature feat = table.eClass().getEStructuralFeature(featureName);
        if (!(feat instanceof EReference) || feat.isMany() || !(feat.getEType() instanceof EClass))
        {
            return;
        }
        EObject addition = createFromClassifier(formModel, feat.getEType().getName());
        if (addition == null)
        {
            return;
        }
        // The table additions (search string / view status / search control) must be ENABLED to render
        // active rather than grey/read-only. The designer keeps these additions at visible=false /
        // userVisible=null (they are table chrome, shown regardless of 'visible') but enabled=true; the MCP
        // build path otherwise leaves enabled at its un-set default (false), which is exactly the grey
        // symptom. Set ONLY enabled here, so the rest of the addition matches the designer byte-for-byte.
        setBooleanFeature(addition, FEATURE_ENABLED, true);
        String tableName = stringFeature(table, FEATURE_NAME);
        setStringFeature(addition, FEATURE_NAME, uniqueChildName(formModel, tableName, suffix));
        if (typeLiteral != null)
        {
            setEnumFeature(addition, FEATURE_TYPE, typeLiteral);
        }
        // 'source' is an AdditionSource reference - the table the addition searches (serialized as the
        // table name); the Table itself is the source.
        EStructuralFeature srcFeat = addition.eClass().getEStructuralFeature(FEATURE_SOURCE);
        if (srcFeat instanceof EReference)
        {
            addition.eSet(srcFeat, table);
        }
        setExtInfoClassifier(formModel, addition, extInfoClassifier);
        EObject extInfo = singleReference(addition, FEATURE_EXT_INFO);
        if (extInfo != null)
        {
            setBooleanFeature(extInfo, KEY_AUTO_MAX_WIDTH, true);
        }
        table.eSet(feat, addition);
        setIntFeature(addition, FEATURE_ID, nextItemId(formModel));
        addAutoChildren(formModel, addition, true, russianAutoNames);
    }

    /** The designer's table scalar defaults (FormObjectFactory.newTable): selection, lines, scrollbars. */
    private static void applyTableDefaults(EObject table)
    {
        setBooleanFeature(table, "changeRowSet", true); //$NON-NLS-1$
        setBooleanFeature(table, "changeRowOrder", true); //$NON-NLS-1$
        setBooleanFeature(table, KEY_AUTO_MAX_WIDTH, true);
        setBooleanFeature(table, KEY_AUTO_MAX_HEIGHT, true);
        setBooleanFeature(table, "autoMaxRowsCount", true); //$NON-NLS-1$
        setEnumFeature(table, "selectionMode", "MultiRow"); //$NON-NLS-1$ //$NON-NLS-2$
        setBooleanFeature(table, "header", true); //$NON-NLS-1$
        setIntFeature(table, "headerHeight", 1); //$NON-NLS-1$
        setIntFeature(table, "footerHeight", 1); //$NON-NLS-1$
        setEnumFeature(table, "horizontalScrollBar", "AutoUse"); //$NON-NLS-1$ //$NON-NLS-2$
        setEnumFeature(table, "verticalScrollBar", "AutoUse"); //$NON-NLS-1$ //$NON-NLS-2$
        setBooleanFeature(table, "horizontalLines", true); //$NON-NLS-1$
        setBooleanFeature(table, "verticalLines", true); //$NON-NLS-1$
        setEnumFeature(table, "representation", "HierarchicalList"); //$NON-NLS-1$ //$NON-NLS-2$
        setEnumFeature(table, "searchOnInput", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        setEnumFeature(table, "initialListView", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        setBooleanFeature(table, "horizontalStretch", true); //$NON-NLS-1$
        setBooleanFeature(table, "verticalStretch", true); //$NON-NLS-1$
        setEnumFeature(table, "fileDragMode", "AsFileRef"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Sets the table's {@code rowFilter} to an mcore {@code UndefinedValue} - the explicit "no filter"
     * the designer serializes ({@code <rowFilter xsi:type="core:UndefinedValue"/>}). The classifier is
     * resolved from the mcore package by nsURI (no compile-time mcore dependency). No-op when the
     * feature or package is unavailable.
     */
    private static void setUndefinedRowFilter(EObject table)
    {
        EStructuralFeature feat = table.eClass().getEStructuralFeature("rowFilter"); //$NON-NLS-1$
        if (!(feat instanceof EReference))
        {
            return;
        }
        EPackage mcore = EPackage.Registry.INSTANCE.getEPackage(MCORE_PACKAGE_NS_URI);
        EClassifier undefined = mcore != null ? mcore.getEClassifier("UndefinedValue") : null; //$NON-NLS-1$
        if (undefined instanceof EClass)
        {
            table.eSet(feat, mcore.getEFactoryInstance().create((EClass)undefined));
        }
    }

    /** A Button bound to a form command (FormCommand is-a mcore Command, so the reference is direct). */
    private static String createButton(EObject formModel, String name, String parentName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        String cmdName, String titleLanguage, String title, boolean russianAutoNames,
        String[] createdKind)
    {
        if (cmdName == null || cmdName.isEmpty())
        {
            return "A form button needs a 'command' property naming the form command it runs " //$NON-NLS-1$
                + "(e.g. {name:'command', value:'Refresh'})."; //$NON-NLS-1$
        }
        EObject command = findByName(referenceList(formModel, FEATURE_FORM_COMMANDS), cmdName);
        if (command == null)
        {
            return "Form command '" + cmdName + "' not found - create it first, then bind the button " //$NON-NLS-1$ //$NON-NLS-2$
                + "to it."; //$NON-NLS-1$
        }
        if (findItem(formModel, name) != null)
        {
            return ERR_ITEM_EXISTS + name;
        }
        EObject container = containerFor(formModel, parentName);
        if (container == null)
        {
            return parentNotFound(parentName);
        }
        String invalid = validatePlacement(Kind.BUTTON, container, parentName);
        if (invalid != null)
        {
            return invalid;
        }
        EObject item = createFromClassifier(formModel, ELEM_BUTTON);
        if (item == null)
        {
            return "Cannot create a form button for this form model."; //$NON-NLS-1$
        }
        setStringFeature(item, FEATURE_NAME, name);
        applyVisibleDefaults(item);
        setIntFeature(item, FEATURE_ID, nextItemId(formModel));
        // The button type depends on the container (the platform allows ONLY CommandBarButton /
        // CommandBarHyperlink inside a command bar, context menu, button group or popup) - mirror
        // FormItemTypeInformationService.getDefaultButtonType. Buttons have no extInfo.
        setEnumFeature(item, FEATURE_TYPE,
            isCommandBarContext(container) ? "CommandBarButton" : "UsualButton"); //$NON-NLS-1$ //$NON-NLS-2$
        EStructuralFeature cmdFeat = item.eClass().getEStructuralFeature("commandName"); //$NON-NLS-1$
        if (cmdFeat instanceof EReference)
        {
            item.eSet(cmdFeat, command);
        }
        // The platform factory's remaining new-button defaults (FormObjectFactory.newButton); without
        // them the exported button diverges from a designer-created one (e.g. AutoMaxWidth=false).
        setBooleanFeature(item, KEY_AUTO_MAX_WIDTH, true);
        setBooleanFeature(item, KEY_AUTO_MAX_HEIGHT, true);
        setBooleanFeature(item, "commandUniqueness", true); //$NON-NLS-1$
        setEnumFeature(item, "representation", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        setEnumFeature(item, "shape", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        setEnumFeature(item, "shapeRepresentation", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        setEnumFeature(item, "pictureLocation", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        setEnumFeature(item, "representationInContextMenu", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        setEnumFeature(item, "locationInCommandBar", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        setEnumFeature(item, "placementArea", "UserCmds"); //$NON-NLS-1$ //$NON-NLS-2$
        applyTitle(item, titleLanguage, title);
        addToList(container, FEATURE_ITEMS, item);
        // A button carries only the extended-tooltip auto-child (FormObjectFactory.newButton).
        addAutoChildren(formModel, item, false, russianAutoNames);
        recordKind(item, createdKind);
        return null;
    }

    /**
     * Rejects an item-kind / parent-container combination the designer forbids, mirroring the
     * platform's {@code FormItemTypeInformationService} predicates ({@code isNotSupportedButtonContext}
     * / {@code isContextNotSupportDecoration}). Returns {@code null} when the placement is allowed.
     */
    private static String validatePlacement(Kind kind, EObject container, String parentName)
    {
        String containerClass = container.eClass().getName();
        if (kind == Kind.BUTTON
            && (ECLASS_TABLE.equals(containerClass)
                || isGroupOfTypeLiteral(container, TYPE_LITERAL_PAGES, TYPE_LITERAL_COLUMN_GROUP)))
        {
            return "A button cannot be placed in '" + parentName + "' (" + containerClass //$NON-NLS-1$ //$NON-NLS-2$
                + "): the platform does not allow buttons in tables, pages groups or column groups. " //$NON-NLS-1$
                + "Use the form root, a usual/popup group, or 'AutoCommandBar'."; //$NON-NLS-1$
        }
        if (kind == Kind.DECORATION
            && (ECLASS_TABLE.equals(containerClass) || ECLASS_AUTO_COMMAND_BAR.equals(containerClass)
                || ECLASS_CONTEXT_MENU.equals(containerClass)
                || isGroupOfTypeLiteral(container, TYPE_LITERAL_COMMAND_BAR, TYPE_LITERAL_POPUP,
                    TYPE_LITERAL_PAGES, TYPE_LITERAL_BUTTON_GROUP, TYPE_LITERAL_COLUMN_GROUP)))
        {
            return "A decoration cannot be placed in '" + parentName + "' (" + containerClass //$NON-NLS-1$ //$NON-NLS-2$
                + "): tables, command bars, context menus and popup/pages/button/column groups " //$NON-NLS-1$
                + "cannot hold decorations. Use the form root or a usual group."; //$NON-NLS-1$
        }
        return null;
    }

    /** Whether {@code container} is a FormGroup whose {@code type} matches one of the literals. */
    private static boolean isGroupOfTypeLiteral(EObject container, String... literals)
    {
        if (!ECLASS_FORM_GROUP.equals(container.eClass().getName()))
        {
            return false;
        }
        String groupType = enumLiteralOf(container, FEATURE_TYPE);
        for (String literal : literals)
        {
            if (literal.equals(groupType))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Attaches the designer's auto-children to a freshly created (and already container-attached)
     * visual item: an {@code ExtendedTooltip} (a Label-typed decoration) and - for fields and
     * decorations - a {@code ContextMenu}. Their names are the item name + the script-variant
     * localized suffix (unique-ified against the form-wide namespace) and their ids come from the
     * same form-wide allocator, the way {@code FormObjectFactory}/{@code FormItemManagementService}
     * build them. Best-effort: an absent feature/classifier is skipped.
     */
    private static void addAutoChildren(EObject formModel, EObject item, boolean withContextMenu,
        boolean russianAutoNames)
    {
        String base = stringFeature(item, FEATURE_NAME);
        if (base == null)
        {
            return;
        }
        if (withContextMenu)
        {
            EStructuralFeature menuFeat = item.eClass().getEStructuralFeature(FEATURE_CONTEXT_MENU);
            EObject menu = createFromClassifier(formModel, ECLASS_CONTEXT_MENU);
            if (menu != null && menuFeat instanceof EReference && !menuFeat.isMany())
            {
                setStringFeature(menu, FEATURE_NAME, uniqueChildName(formModel, base,
                    russianAutoNames ? RU_SUFFIX_CONTEXT_MENU : SUFFIX_CONTEXT_MENU));
                setBooleanFeature(menu, FEATURE_AUTO_FILL, true);
                item.eSet(menuFeat, menu);
                setIntFeature(menu, FEATURE_ID, nextItemId(formModel));
            }
        }
        EStructuralFeature tooltipFeat = item.eClass().getEStructuralFeature(FEATURE_EXTENDED_TOOLTIP);
        EObject tooltip = createFromClassifier(formModel, ECLASS_EXTENDED_TOOLTIP);
        if (tooltip != null && tooltipFeat instanceof EReference && !tooltipFeat.isMany())
        {
            setStringFeature(tooltip, FEATURE_NAME, uniqueChildName(formModel, base,
                russianAutoNames ? RU_SUFFIX_EXTENDED_TOOLTIP : SUFFIX_EXTENDED_TOOLTIP));
            setEnumFeature(tooltip, FEATURE_TYPE, TYPE_LITERAL_LABEL);
            setBooleanFeature(tooltip, KEY_AUTO_MAX_WIDTH, true);
            setBooleanFeature(tooltip, KEY_AUTO_MAX_HEIGHT, true);
            setExtInfoClassifier(formModel, tooltip, ECLASS_LABEL_DECORATION_EXT_INFO);
            EObject tooltipExtInfo = singleReference(tooltip, FEATURE_EXT_INFO);
            if (tooltipExtInfo != null)
            {
                setEnumFeature(tooltipExtInfo, KEY_HORIZONTAL_ALIGN, "Left"); //$NON-NLS-1$
            }
            item.eSet(tooltipFeat, tooltip);
            setIntFeature(tooltip, FEATURE_ID, nextItemId(formModel));
        }
    }

    /** {@code base + suffix}, unique-ified against the form-wide item namespace with a counter. */
    private static String uniqueChildName(EObject formModel, String base, String suffix)
    {
        String candidate = base + suffix;
        int counter = 1;
        while (findItem(formModel, candidate) != null)
        {
            candidate = base + suffix + counter++;
        }
        return candidate;
    }

    /**
     * Whether {@code container}'s child buttons are command-bar buttons: an auto command bar, a
     * context menu, or a group typed CommandBar / ButtonGroup / Popup (the platform's
     * {@code isCommandBarButtonSupport}).
     */
    private static boolean isCommandBarContext(EObject container)
    {
        String eClassName = container.eClass().getName();
        if (ECLASS_AUTO_COMMAND_BAR.equals(eClassName) || ECLASS_CONTEXT_MENU.equals(eClassName))
        {
            return true;
        }
        if (ECLASS_FORM_GROUP.equals(eClassName))
        {
            String groupType = enumLiteralOf(container, FEATURE_TYPE);
            return TYPE_LITERAL_COMMAND_BAR.equals(groupType)
                || TYPE_LITERAL_BUTTON_GROUP.equals(groupType)
                || TYPE_LITERAL_POPUP.equals(groupType);
        }
        return false;
    }

    /**
     * Sets the new-item defaults every visual form item shares with the platform factory: visible AND
     * enabled (the {@code enabled} EAttribute defaults to {@code false} in the model, so a created item
     * would otherwise export as {@code <Enabled>false</Enabled>} and render disabled in the client),
     * plus the {@code userVisible} AdjustableBoolean the model requires.
     */
    private static void applyVisibleDefaults(EObject item)
    {
        setBooleanFeature(item, FEATURE_VISIBLE, true);
        setBooleanFeature(item, FEATURE_ENABLED, true);
        setAdjustableBooleanFeature(item, FEATURE_USER_VISIBLE);
    }

    /**
     * Resolves the parent container for a new visual item: the form root for a blank parent, the
     * form's (or a named item's, e.g. a table's) auto command bar for the {@code AutoCommandBar}
     * token, the named item otherwise, or {@code null} if not found. A dotted parent path
     * ({@code Form.X.AutoCommandBar.ChildItems}) is tolerated: item names cannot contain dots, so only
     * the trailing segments matter (the Designer-XML {@code ChildItems} collection token is ignored).
     */
    private static EObject containerFor(EObject formModel, String parentName)
    {
        if (parentName == null || parentName.isEmpty())
        {
            return formModel;
        }
        String[] segments = parentName.split("\\."); //$NON-NLS-1$
        int last = segments.length - 1;
        if (last > 0 && CHILD_ITEMS_TOKEN.equalsIgnoreCase(segments[last]))
        {
            last--;
        }
        String token = segments[last];
        if (AUTO_COMMAND_BAR_TOKEN.equalsIgnoreCase(token))
        {
            // A path carrying a form token before the owner segment ('Form.X.AutoCommandBar',
            // 'Catalog.O.Form.F.AutoCommandBar') ALWAYS addresses the FORM's bar - there the
            // preceding segment is the form name, which may coincide with an item name. Only
            // 'MyTable.AutoCommandBar' (no form token) probes the named item's own bar, falling
            // back to the form's bar when the item has none.
            boolean formPathPrefix = last > 1 && isFormToken(segments[last - 2]);
            EObject owner = (last > 0 && !formPathPrefix)
                ? findItem(formModel, segments[last - 1]) : null;
            EObject bar = owner != null ? singleReference(owner, FEATURE_AUTO_COMMAND_BAR) : null;
            if (bar == null)
            {
                bar = singleReference(formModel, FEATURE_AUTO_COMMAND_BAR);
            }
            if (bar != null)
            {
                return bar;
            }
        }
        return findItem(formModel, token);
    }

    private static String parentNotFound(String parentName)
    {
        return "Parent form item not found: " + parentName //$NON-NLS-1$
            + ". Use an existing item's name (see the form structure via get_metadata_details), " //$NON-NLS-1$
            + "'AutoCommandBar' for the form's command bar, or omit 'parent' to add at the form root."; //$NON-NLS-1$
    }

    /** Attaches a fresh extInfo of the named concrete classifier to an item (best-effort). */
    private static void setExtInfoClassifier(EObject formModel, EObject item, String classifier)
    {
        EStructuralFeature feature = item.eClass().getEStructuralFeature(FEATURE_EXT_INFO);
        if (!(feature instanceof EReference))
        {
            return;
        }
        EClass extInfoClass = formEClass(formModel, classifier);
        if (extInfoClass != null && extInfoClass.getEPackage() != null)
        {
            item.eSet(feature, extInfoClass.getEPackage().getEFactoryInstance().create(extInfoClass));
        }
    }

    // ---- event handlers -------------------------------------------------------------------------

    /**
     * Convenience overload binding a BASE event {@code Handler} (no call type). Equivalent to
     * {@link #createHandler(EObject, String, String, Version, String, String, String[])} with a
     * {@code null} call type. Preserved for the existing callers/tests that bind plain handlers.
     */
    public static String createHandler(EObject container, String eventName, String procName,
        Version version, String langCode, String[] createdKind)
    {
        return createHandler(container, eventName, procName, version, langCode, null, createdKind);
    }

    /**
     * Binds an event {@code Handler} to {@code container} (the form itself or a form item): resolves
     * the requested {@code eventName} against the element's AVAILABLE events; on no match returns an
     * error LISTING the available events localized to {@code langCode} (the user-required advisory).
     * The {@code procName} is the BSL handler procedure name (defaults to the event name when blank).
     *
     * <p>When {@code callType} is non-blank this binds an EXTENSION handler ({@code
     * form:EventHandlerExtension} with a {@code <callType>}) instead of a base {@code EventHandler} -
     * how a configuration EXTENSION intercepts a base element's event Before / After / Instead (the 1C
     * UI label "Instead" / "Вместо" is the EMF enum literal {@code Override}). The extension handler
     * COEXISTS with the base element's own handler for the same event (the point of interception); only
     * another extension handler with the SAME call type is a duplicate. {@code ChangeAndValidate} is a
     * method-only call type and is rejected for a form event. A blank {@code callType} reproduces the
     * base-handler behavior exactly (one handler per event).
     *
     * <p>This writes only the {@code .form} model; the BSL handler procedure itself (like the base
     * path) is left to {@code write_module_source}.
     *
     * @param version the platform version (to resolve the element's platform Type and its events)
     * @param callType {@code null}/blank for a base handler; otherwise Before | After | Instead
     * @return {@code null} on success, or a human-readable error message
     */
    public static String createHandler(EObject container, String eventName, String procName, // NOSONAR reflective/form or transport god-method; further extraction deferred (reflective code)
        Version version, String langCode, String callType, String[] createdKind)
    {
        final boolean extension = callType != null && !callType.trim().isEmpty();
        if (ECLASS_FORM_COMMAND.equals(container.eClass().getName()))
        {
            if (extension)
            {
                return "Call-type interception is not supported for a form command action; " //$NON-NLS-1$
                    + "callType applies to a form ITEM event."; //$NON-NLS-1$
            }
            return createCommandAction(container, eventName, procName, createdKind);
        }
        EStructuralFeature handlersFeat = container.eClass().getEStructuralFeature(KEY_HANDLERS);
        if (!(handlersFeat instanceof EReference) || !handlersFeat.isMany())
        {
            return "The form element '" + container.eClass().getName() //$NON-NLS-1$
                + "' cannot hold event handlers."; //$NON-NLS-1$
        }
        List<EObject> events = availableEvents(container, version);
        if (events.isEmpty())
        {
            return "Could not resolve the available events for this form element."; //$NON-NLS-1$
        }
        EObject matched = null;
        for (EObject ev : events)
        {
            if (eventName.equalsIgnoreCase(eventNameOf(ev, false))
                || eventName.equalsIgnoreCase(eventNameOf(ev, true)))
            {
                matched = ev;
                break;
            }
        }
        if (matched == null)
        {
            boolean ru = "ru".equals(langCode); //$NON-NLS-1$
            StringBuilder sb = new StringBuilder();
            for (EObject ev : events)
            {
                String n = eventNameOf(ev, ru);
                if (n == null || n.isEmpty())
                {
                    n = eventNameOf(ev, !ru);
                }
                if (n != null && !n.isEmpty())
                {
                    if (sb.length() > 0)
                    {
                        sb.append(", "); //$NON-NLS-1$
                    }
                    sb.append(n);
                }
            }
            return ERR_EVENT_PREFIX + eventName + "' is not valid for " + container.eClass().getName() //$NON-NLS-1$
                + ". Available events: " + sb; //$NON-NLS-1$
        }
        return bindEventHandler(container, handlersFeat, matched, eventName, procName, callType,
            createdKind);
    }

    /**
     * Binds the already-resolved {@code matched} event to {@code container}: creates a base
     * {@code EventHandler} or, when {@code callType} is non-blank, a {@code form:EventHandlerExtension}
     * carrying that call type; sets {@code name}/{@code event}[/{@code callType}]; and appends it to the
     * {@code handlers} list. Duplicate rule: the base path keeps one handler per event; an extension
     * handler COEXISTS with the base handler and with other-call-type extension handlers, so only a
     * same-(event, callType) extension handler is a real duplicate. A wrong call type fails loudly (the
     * extension handler is never produced with an unset {@code callType}). Reflective throughout - no
     * {@code com._1c.g5.v8.dt.form.model} import. Package-visible for the headless unit test (the
     * model-dependent event resolution above is exercised by the e2e suite / live verification).
     *
     * @return {@code null} on success, or a human-readable error message
     */
    static String bindEventHandler(EObject container, EStructuralFeature handlersFeat, EObject matched, // NOSONAR reflective/form or transport god-method; further extraction deferred (reflective code)
        String eventName, String procName, String callType, String[] createdKind)
    {
        final boolean extension = callType != null && !callType.trim().isEmpty();
        EClass baseEhType = ((EReference)handlersFeat).getEReferenceType();
        if (baseEhType == null || baseEhType.getEPackage() == null)
        {
            return "Cannot create an event handler for this form model."; //$NON-NLS-1$
        }
        // Resolve the extension type and call-type literal UP FRONT - a wrong literal must fail loudly,
        // never silently produce an extension handler whose callType is left unset.
        EClass ehType = baseEhType;
        EEnumLiteral callTypeLiteral = null;
        if (extension)
        {
            EClassifier extClassifier =
                baseEhType.getEPackage().getEClassifier(ECLASS_EVENT_HANDLER_EXTENSION);
            if (!(extClassifier instanceof EClass))
            {
                return "This form model has no '" + ECLASS_EVENT_HANDLER_EXTENSION //$NON-NLS-1$
                    + "' type; extension event interception is not available here."; //$NON-NLS-1$
            }
            ehType = (EClass)extClassifier;
            callTypeLiteral = resolveEventCallType(ehType, callType);
            if (callTypeLiteral == null)
            {
                return "Invalid callType '" + callType + "' for a form event. Use Before, After or " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Instead (ChangeAndValidate is for method interception, not events)."; //$NON-NLS-1$
            }
        }
        // Duplicate guard. Base path keeps the original "one handler per event" rule. Extension path lets
        // the extension handler COEXIST with the base handler and with other-call-type extension handlers; // NOSONAR explanatory comment, not commented-out code
        // only a same-(event, callType) EventHandlerExtension is a real duplicate.
        EStructuralFeature evFeat = handlerEventFeature(handlersFeat);
        for (EObject existing : referenceList(container, KEY_HANDLERS))
        {
            if (evFeat == null || existing.eGet(evFeat) != matched)
            {
                continue;
            }
            if (!extension)
            {
                return "An event handler for '" + eventName + "' already exists on this element."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (ECLASS_EVENT_HANDLER_EXTENSION.equals(existing.eClass().getName())
                && callTypeLiteral.getName().equals(callTypeNameOf(existing)))
            {
                return "An extension event handler for '" + eventName + "' with call type '" //$NON-NLS-1$ //$NON-NLS-2$
                    + callType + "' already exists on this element."; //$NON-NLS-1$
            }
        }
        EObject handler = ehType.getEPackage().getEFactoryInstance().create(ehType);
        setStringFeature(handler, FEATURE_NAME, (procName == null || procName.isEmpty()) ? eventName : procName);
        if (evFeat != null)
        {
            handler.eSet(evFeat, matched);
        }
        if (extension)
        {
            EStructuralFeature ctFeat = handler.eClass().getEStructuralFeature(FEATURE_CALL_TYPE);
            if (ctFeat == null)
            {
                return "The form model's EventHandlerExtension has no 'callType' attribute."; //$NON-NLS-1$
            }
            handler.eSet(ctFeat, callTypeLiteral.getInstance());
        }
        addToList(container, KEY_HANDLERS, handler);
        recordKind(handler, createdKind);
        return null;
    }

    /**
     * Resolves a user-facing form-event call type (Before | After | Instead) to the {@code
     * EventHandlerExtension.callType} EEnum literal. The 1C UI label "Instead" (Вместо) maps to the EMF
     * literal {@code Override}. Matching is case-insensitive against the literal OR the name. The
     * method-only {@code ChangeAndValidate} and any unknown token resolve to {@code null} (the caller
     * then errors loudly). No {@code com._1c.g5.v8.dt.form.model} import - all reflective.
     * Package-visible for the headless unit test.
     */
    static EEnumLiteral resolveEventCallType(EClass eventHandlerExtType, String token)
    {
        EStructuralFeature feature = eventHandlerExtType.getEStructuralFeature(FEATURE_CALL_TYPE);
        if (!(feature instanceof EAttribute))
        {
            return null;
        }
        EClassifier type = ((EAttribute)feature).getEAttributeType();
        if (!(type instanceof EEnum))
        {
            return null;
        }
        String want = token.trim();
        if (CALL_TYPE_UI_INSTEAD.equalsIgnoreCase(want))
        {
            want = CALL_TYPE_LITERAL_OVERRIDE; // 1C UI label -> EMF enum literal
        }
        // A form EVENT never accepts the method-only call type, even if addressed by literal or name.
        if (CALL_TYPE_LITERAL_CHANGE_AND_VALIDATE.equalsIgnoreCase(want)
            || CALL_TYPE_NAME_CHANGE_AND_VALIDATE.equalsIgnoreCase(want))
        {
            return null;
        }
        for (EEnumLiteral lit : ((EEnum)type).getELiterals())
        {
            // Defense in depth: never hand back the method-only literal even if it were addressed
            // directly. The EEnum literal/name is "ChangeAndValidate" (not the Java constant name), so
            // compare against the literal form, case-insensitively.
            if (CALL_TYPE_LITERAL_CHANGE_AND_VALIDATE.equalsIgnoreCase(lit.getName())
                || CALL_TYPE_LITERAL_CHANGE_AND_VALIDATE.equalsIgnoreCase(lit.getLiteral()))
            {
                continue;
            }
            if (want.equalsIgnoreCase(lit.getLiteral()) || want.equalsIgnoreCase(lit.getName()))
            {
                return lit;
            }
        }
        return null;
    }

    /** The {@code callType} EEnum literal NAME currently set on an EventHandlerExtension, or null. */
    private static String callTypeNameOf(EObject handler)
    {
        EStructuralFeature ctFeat = handler.eClass().getEStructuralFeature(FEATURE_CALL_TYPE);
        Object value = ctFeat != null ? handler.eGet(ctFeat) : null;
        return value instanceof Enumerator ? ((Enumerator)value).getName() : null;
    }

    /**
     * Binds the ACTION handler of a form command ({@code ...Command.X.Handler.Action}): a command has
     * no platform events, only the single {@code action} containment, so the "event" leaf must be
     * {@code Action} (or its Russian equivalent). Builds the same
     * {@code FormCommandHandlerContainer}/{@code CommandHandler} pair the platform's
     * {@code ModelUtils.setCommandHandler} builds; the BSL procedure name defaults to the COMMAND name
     * (the EDT UI's suggestion), not the event name.
     */
    private static String createCommandAction(EObject command, String eventName, String procName,
        String[] createdKind)
    {
        if (!isActionToken(eventName))
        {
            return ERR_EVENT_PREFIX + eventName + "' is not valid for a form command" //$NON-NLS-1$
                + ". Available events: " + COMMAND_ACTION_EVENT; //$NON-NLS-1$
        }
        EStructuralFeature actionFeat = command.eClass().getEStructuralFeature(FEATURE_ACTION);
        if (!(actionFeat instanceof EReference))
        {
            return "This form model does not support a command action handler."; //$NON-NLS-1$
        }
        if (command.eGet(actionFeat) != null)
        {
            return "An event handler for '" + COMMAND_ACTION_EVENT //$NON-NLS-1$
                + "' already exists on this command."; //$NON-NLS-1$
        }
        EObject container = createFromClassifier(command, ECLASS_FORM_COMMAND_HANDLER_CONTAINER);
        EObject handler = createFromClassifier(command, ECLASS_COMMAND_HANDLER);
        EStructuralFeature handlerFeat =
            container != null ? container.eClass().getEStructuralFeature(FEATURE_HANDLER) : null;
        if (handler == null || !(handlerFeat instanceof EReference))
        {
            return "Cannot create a command action handler for this form model."; //$NON-NLS-1$
        }
        String proc = (procName == null || procName.isEmpty())
            ? stringFeature(command, FEATURE_NAME) : procName;
        setStringFeature(handler, FEATURE_NAME, proc);
        container.eSet(handlerFeat, handler);
        command.eSet(actionFeat, container);
        recordKind(handler, createdKind);
        return null;
    }

    /** Whether the handler FQN leaf addresses a command's Action (English or Russian). */
    private static boolean isActionToken(String eventName)
    {
        return COMMAND_ACTION_EVENT.equalsIgnoreCase(eventName)
            || (eventName != null && RU_ACTION.equals(eventName.trim().toLowerCase()));
    }

    /**
     * Resolves the element an ITEM-LEVEL handler FQN attaches to on the tx-bound form model: the named
     * form COMMAND for a {@code Command} kind token ({@code ...Command.X.Handler.Action}), the named
     * form ITEM otherwise; the form root for a form-level ref. Returns {@code null} when the named
     * owner does not exist.
     */
    public static EObject resolveHandlerContainer(EObject formModel, FormMemberRef ref)
    {
        if (!ref.isItemLevel())
        {
            return formModel;
        }
        if (kindForToken(ref.itemKindToken) == Kind.COMMAND)
        {
            return findFormCommand(formModel, ref.itemName);
        }
        return findFormItem(formModel, ref.itemName);
    }

    /** The {@code event} EReference on the EventHandler EClass held by the {@code handlers} feature. */
    private static EStructuralFeature handlerEventFeature(EStructuralFeature handlersFeat)
    {
        EClass ehType = ((EReference)handlersFeat).getEReferenceType();
        return ehType != null ? ehType.getEStructuralFeature("event") : null; //$NON-NLS-1$
    }

    private static String eventNameOf(EObject event, boolean russian)
    {
        return stringFeature(event, russian ? "nameRu" : "name"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The available platform events for a form element (the form root OR a form item), replicating
     * {@code FormItemInformationService.getAllowedEvents}'s pure-model logic (no form-service
     * dependency): the union of the events of the element's platform BASE type and, when present, its
     * {@code extInfo} SUB-type. The base/ext type name comes from {@link #PLATFORM_TYPE_BY_ECLASS}
     * (the same mapping the platform's {@code BASE_TYPES_OF_FORM_ITEMS_AND_EXT} holds); each name is
     * resolved to its {@code Type} via {@link IEObjectProvider} and its {@code events} collected.
     * <p>Unioning the ext-info type matters for items: e.g. an input field's {@code OnChange} lives on
     * {@code FormFieldExtensionForATextBox} (its {@code InputFieldExtInfo}), not on the bare
     * {@code FormField} base type.</p>
     */
    private static List<EObject> availableEvents(EObject element, Version version)
    {
        if (version == null)
        {
            return Collections.emptyList();
        }
        IEObjectProvider provider =
            IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
        if (provider == null)
        {
            return Collections.emptyList();
        }
        List<EObject> events = new ArrayList<>();
        addTypeEvents(provider, element, PLATFORM_TYPE_BY_ECLASS.get(element.eClass().getName()), events);
        EStructuralFeature extInfoFeat = element.eClass().getEStructuralFeature(FEATURE_EXT_INFO);
        if (extInfoFeat instanceof EReference)
        {
            Object ext = element.eGet(extInfoFeat);
            if (ext instanceof EObject)
            {
                addTypeEvents(provider, element,
                    PLATFORM_TYPE_BY_ECLASS.get(((EObject)ext).eClass().getName()), events);
            }
        }
        return events;
    }

    /** Resolves {@code typeName} to a platform {@code Type} and appends its {@code events} to the list. */
    @SuppressWarnings("unchecked")
    private static void addTypeEvents(IEObjectProvider provider, EObject context, String typeName,
        List<EObject> accumulator)
    {
        EObject type = resolveTypeName(provider, context, typeName);
        if (type == null)
        {
            return;
        }
        EStructuralFeature eventsFeat = type.eClass().getEStructuralFeature("events"); //$NON-NLS-1$
        Object value = eventsFeat != null ? type.eGet(eventsFeat) : null;
        if (value instanceof List<?>)
        {
            accumulator.addAll((List<EObject>)value);
        }
    }

    /**
     * Resolves a platform type by name, swapping {@code ManagedForm} &harr; {@code ClientApplication
     * Form} the way the platform does (the managed form's type is {@code ClientApplicationForm} on
     * modern platforms and {@code ManagedForm} on legacy ones).
     */
    private static EObject resolveTypeName(IEObjectProvider provider, EObject context, String typeName)
    {
        if (typeName == null)
        {
            return null;
        }
        EObject type = resolveType(provider, context, typeName);
        if (type == null && TYPE_MANAGED_FORM.equals(typeName))
        {
            type = resolveType(provider, context, "ClientApplicationForm"); //$NON-NLS-1$
        }
        else if (type == null && "ClientApplicationForm".equals(typeName)) //$NON-NLS-1$
        {
            type = resolveType(provider, context, TYPE_MANAGED_FORM);
        }
        return type;
    }

    private static EObject resolveType(IEObjectProvider provider, EObject context, String typeName)
    {
        try
        {
            // createProxy THROWS for a name the provider does not know (it does not return null), so
            // an unknown legacy/modern type name must not abort the lookup - we try the alternative.
            EObject proxy = provider.createProxy(typeName);
            if (proxy == null)
            {
                return null;
            }
            EObject resolved = EcoreUtil.resolve(proxy, context);
            return (resolved == null || resolved.eIsProxy()) ? null : resolved;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Form-element / ext-info EClass name &rarr; platform base-type name, a faithful copy of
     * {@code FormItemInformationService.BASE_TYPES_OF_FORM_ITEMS_AND_EXT} (keyed by EClass NAME so this
     * bundle needs no compile-time form-model dependency). The events of an element are the union over
     * its base EClass and its current {@code extInfo} EClass.
     */
    private static final Map<String, String> PLATFORM_TYPE_BY_ECLASS = buildPlatformTypeMap();

    private static Map<String, String> buildPlatformTypeMap()
    {
        Map<String, String> m = new HashMap<>();
        // Element base types.
        m.put("Form", TYPE_MANAGED_FORM); // modern: ClientApplicationForm (resolveTypeName swaps) //$NON-NLS-1$
        m.put(ECLASS_TABLE, "FormTable"); //$NON-NLS-1$
        m.put(ECLASS_DECORATION, "FormDecoration"); //$NON-NLS-1$
        m.put(ECLASS_FORM_FIELD, ECLASS_FORM_FIELD);
        m.put(ELEM_BUTTON, "FormButton"); //$NON-NLS-1$
        m.put(ECLASS_FORM_GROUP, ECLASS_FORM_GROUP);
        m.put("Addition", "FormItemAddition"); //$NON-NLS-1$ //$NON-NLS-2$
        // Form ext-infos.
        m.put("CatalogFormExtInfo", "ManagedFormExtensionForCatalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DocumentFormExtInfo", "ManagedFormExtensionForDocuments"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ChartOfCharacteristicTypesFormExtInfo", //$NON-NLS-1$
            "ManagedFormExtensionForChartOfCharacteristicsTypes"); //$NON-NLS-1$
        m.put("ReportFormExtInfo", "ManagedFormExtensionForReports"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ConstantsFormExtInfo", "ManagedFormExtensionForConstants"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("InformationRegisterManagerFormExtInfo", //$NON-NLS-1$
            "ManagedFormExtensionForInformationRegisterRecords"); //$NON-NLS-1$
        m.put("BusinessProcesFormExtInfo", "ManagedFormExtensionForBusinessProcesses"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TaskFormExtInfo", "ManagedFormExtensionForTasks"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SettingsComposerFormExtInfo", "ManagedFormExtensionForSettingsComposer"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("RecordSetFormExtInfo", "ManagedFormExtensionForRecordSet"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ObjectFormExtInfo", "ManagedFormExtensionForObjects"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TableObjectFormExtInfo", "ManagedFormExtensionForExternalDataSourceTableObject"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TableRecordFormExtInfo", "ManagedFormExtensionForExternalDataSourceTableRecord"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CubeRecordFormExtInfo", "ManagedFormExtensionForExternalDataSourceCubeRecord"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CubeRecordSetFormExtInfo", "ManagedFormExtensionForExternalDataSourceCubeRecordSet"); //$NON-NLS-1$ //$NON-NLS-2$
        // Table / decoration ext-infos.
        m.put("DynamicListTableExtInfo", "FormTableExtensionForDynamicList"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put(ECLASS_LABEL_DECORATION_EXT_INFO, "FormDecorationExtensionForALabel"); //$NON-NLS-1$
        m.put("PictureDecorationExtInfo", "FormDecorationExtensionForAPicture"); //$NON-NLS-1$ //$NON-NLS-2$
        // Field ext-infos.
        m.put("LabelFieldExtInfo", "FormFieldExtensionForALabelField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put(ECLASS_INPUT_FIELD_EXT_INFO, "FormFieldExtensionForATextBox"); //$NON-NLS-1$
        m.put("CheckBoxFieldExtInfo", "FormFieldExtensionForACheckBoxField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ImageFieldExtInfo", "FormFieldExtensionForAPictureField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("RadioButtonsFieldExtInfo", "FormFieldExtensionForARadioButtonField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SpreadSheetDocFieldExtInfo", "FormFieldExtensionForASpreadsheetDocumentField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TextDocFieldExtInfo", "FormFieldExtensionForATextDocument"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CalendarFieldExtInfo", "FormFieldExtensionForACalendarField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ProgressBarFieldExtInfo", "FormFieldExtensionForAProgressBarField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("TrackBarFieldExtInfo", "FormFieldExtensionForATrackBarField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ChartFieldExtInfo", "FormFieldExtensionForAChartField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("GanttChartFieldExtInfo", "FormFieldExtensionForAGanttChartField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DendrogramFieldExtInfo", "FormFieldExtensionForADendrogramField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FlowchartFieldExtInfo", "FormFieldExtensionForAGraphicalSchemaField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("HtmlFieldExtInfo", "FormExtensionForAHTMLDocumentField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("GeographicalMapFieldExtInfo", "FormFieldExtensionForAGeographicalSchemaField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FormattedDocFieldExtInfo", "FormFieldExtensionForAFormattedDocument"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PDFDocumentFieldExtInfo", "FormExtensionForAPDFDocumentField"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PlannerFieldExtInfo", "FormFieldExtensionForAPlanner"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PeriodFieldExtInfo", "FormFieldExtensionForAPeriodField"); //$NON-NLS-1$ //$NON-NLS-2$
        // Group ext-infos.
        m.put("ColumnGroupExtInfo", "FormGroupExtensionForAGroupOfColumns"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PagesGroupExtInfo", "FormGroupExtensionForPages"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PageGroupExtInfo", "FormGroupExtensionForAPage"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("PopupGroupExtInfo", "FormGroupExtensionForAPopup"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommandBarExtInfo", "FormGroupExtensionForACommandBar"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put(ECLASS_USUAL_GROUP_EXT_INFO, "FormGroupExtensionForAUsualGroup"); //$NON-NLS-1$
        // Addition ext-infos.
        m.put("SearchStringAdditionExtInfo", "FormItemAdditionExtensionForSearchString"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ViewStatusAdditionExtInfo", "FormItemAdditionExtensionForViewStatus"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SearchControlAdditionExtInfo", "FormItemAdditionExtensionForSearchControl"); //$NON-NLS-1$ //$NON-NLS-2$
        return Collections.unmodifiableMap(m);
    }

    // ---- element factories (reflective, via the form EPackage) ----------------------------------

    /** Creates an instance of a mono-typed collection's element EType (attributes / formCommands). */
    private static EObject createFromFeatureType(EObject formModel, String featureName)
    {
        EStructuralFeature feature = formModel.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EReference))
        {
            return null;
        }
        EClass type = ((EReference)feature).getEReferenceType();
        if (type == null || type.getEPackage() == null)
        {
            return null;
        }
        return type.getEPackage().getEFactoryInstance().create(type);
    }

    /** Creates an instance of a concrete form classifier (FormGroup / Decoration) by name. */
    private static EObject createFromClassifier(EObject formModel, String classifierName)
    {
        EClass itemClass = formEClass(formModel, classifierName);
        if (itemClass == null || itemClass.getEPackage() == null)
        {
            return null;
        }
        return itemClass.getEPackage().getEFactoryInstance().create(itemClass);
    }

    /** Sets the attribute's valueType to a fresh empty TypeDescription (the form default type). */
    private static void setDefaultValueType(EObject attribute)
    {
        EStructuralFeature feature = attribute.eClass().getEStructuralFeature(FEATURE_VALUE_TYPE);
        if (!(feature instanceof EReference))
        {
            return;
        }
        EClass typeClass = ((EReference)feature).getEReferenceType();
        if (typeClass == null || typeClass.getEPackage() == null)
        {
            return;
        }
        attribute.eSet(feature, typeClass.getEPackage().getEFactoryInstance().create(typeClass));
    }

    /**
     * Sets the managed item's type enum + a default extInfo, the way FormObjectFactory does. A
     * GROUP's type is the validated explicit {@code requestedGroupType} when given, otherwise it is
     * derived from the container (the platform's {@code getDefaultGroupType}): a Popup submenu
     * inside command bars / popups / button groups, a Page inside a Pages group, a ColumnGroup
     * inside tables and column groups, a UsualGroup elsewhere.
     */
    private static void initManagedItem(EObject formModel, EObject item, Kind kind, EObject container,
        String requestedGroupType)
    {
        String typeLiteral;
        if (kind == Kind.GROUP)
        {
            typeLiteral = requestedGroupType != null ? requestedGroupType
                : defaultGroupTypeFor(container);
        }
        else
        {
            typeLiteral = TYPE_LITERAL_LABEL;
        }
        String extInfoClassifier = kind == Kind.GROUP
            ? groupExtInfoClassifierFor(typeLiteral) : ECLASS_LABEL_DECORATION_EXT_INFO;
        setEnumFeature(item, FEATURE_TYPE, typeLiteral);
        setExtInfoClassifier(formModel, item, extInfoClassifier);
        if (kind == Kind.DECORATION)
        {
            // The factory's label decoration default (newLabelDecorationExtInfo).
            EObject extInfo = singleReference(item, FEATURE_EXT_INFO);
            if (extInfo != null)
            {
                setEnumFeature(extInfo, KEY_HORIZONTAL_ALIGN, "Left"); //$NON-NLS-1$
            }
        }
    }

    /** The platform's default group type literal for a container ({@code getDefaultGroupType}). */
    private static String defaultGroupTypeFor(EObject container)
    {
        if (isCommandBarContext(container))
        {
            return TYPE_LITERAL_POPUP;
        }
        if (isGroupOfTypeLiteral(container, TYPE_LITERAL_PAGES))
        {
            return TYPE_LITERAL_PAGE;
        }
        if (ECLASS_TABLE.equals(container.eClass().getName())
            || isGroupOfTypeLiteral(container, TYPE_LITERAL_COLUMN_GROUP))
        {
            return TYPE_LITERAL_COLUMN_GROUP;
        }
        return TYPE_LITERAL_USUAL_GROUP;
    }

    /** The concrete extInfo EClass name matching a group type literal (FormObjectFactory's pairs). */
    private static String groupExtInfoClassifierFor(String groupTypeLiteral)
    {
        switch (groupTypeLiteral)
        {
            case TYPE_LITERAL_POPUP:
                return "PopupGroupExtInfo"; //$NON-NLS-1$
            case TYPE_LITERAL_PAGE:
                return "PageGroupExtInfo"; //$NON-NLS-1$
            case TYPE_LITERAL_PAGES:
                return "PagesGroupExtInfo"; //$NON-NLS-1$
            case TYPE_LITERAL_COLUMN_GROUP:
                return "ColumnGroupExtInfo"; //$NON-NLS-1$
            case TYPE_LITERAL_COMMAND_BAR:
                return "CommandBarExtInfo"; //$NON-NLS-1$
            case TYPE_LITERAL_BUTTON_GROUP:
                return "ButtonGroupExtInfo"; //$NON-NLS-1$
            default:
                return ECLASS_USUAL_GROUP_EXT_INFO;
        }
    }

    private static EClass formEClass(EObject formModel, String classifierName)
    {
        EPackage pkg = formModel.eClass().getEPackage();
        if (pkg == null)
        {
            return null;
        }
        EClassifier classifier = pkg.getEClassifier(classifierName);
        return (classifier instanceof EClass) ? (EClass)classifier : null;
    }

    // ---- the form-wide id allocation ------------------------------------------------------------

    /**
     * The next free form-attribute id = max existing {@code AbstractFormAttribute} id across the whole
     * form + 1. This is a separate EDT id space from {@code FormItem.id}.
     */
    private static int nextAttributeId(EObject formModel)
    {
        EClass attributeClass = formEClass(formModel, ECLASS_ABSTRACT_FORM_ATTRIBUTE);
        if (attributeClass == null)
        {
            return 1;
        }
        int max = maxAttributeIdForAllocation(formModel, attributeClass);
        EObject extensionForm = liveReference(formModel, FEATURE_EXTENSION_FORM);
        if (extensionForm != null)
        {
            max = Math.max(max, maxAttributeIdForAllocation(extensionForm, attributeClass));
        }
        return max + 1;
    }

    private static int maxAttributeIdForAllocation(EObject formModel, EClass attributeClass)
    {
        int max = maxAttributeId(formModel, attributeClass);
        if (hasExtensionPeer(formModel) && max < DEFAULT_EXT_FORM_OBJECT_ID)
        {
            return DEFAULT_EXT_FORM_OBJECT_ID;
        }
        return max;
    }

    /**
     * The next free form-command id = max existing {@code FormCommand} id across the whole form + 1.
     * This is a separate EDT id space from both {@code FormItem.id} and
     * {@code AbstractFormAttribute.id}.
     */
    private static int nextCommandId(EObject formModel)
    {
        EClass commandClass = formEClass(formModel, ECLASS_FORM_COMMAND);
        if (commandClass == null)
        {
            return 1;
        }
        int max = maxCommandIdForAllocation(formModel, commandClass);
        EObject extensionForm = liveReference(formModel, FEATURE_EXTENSION_FORM);
        if (extensionForm != null)
        {
            max = Math.max(max, maxCommandIdForAllocation(extensionForm, commandClass));
        }
        return max + 1;
    }

    private static int maxCommandIdForAllocation(EObject formModel, EClass commandClass)
    {
        int max = maxCommandId(formModel, commandClass);
        if (hasExtensionPeer(formModel) && max < DEFAULT_EXT_FORM_OBJECT_ID)
        {
            return DEFAULT_EXT_FORM_OBJECT_ID;
        }
        return max;
    }

    private static int maxCommandId(EObject formModel, EClass commandClass)
    {
        int max = 0;
        for (TreeIterator<EObject> it = formModel.eAllContents(); it.hasNext();)
        {
            EObject obj = it.next();
            if (commandClass.isInstance(obj))
            {
                max = Math.max(max, intFeature(obj, FEATURE_ID));
            }
        }
        return max;
    }

    private static int maxAttributeId(EObject formModel, EClass attributeClass)
    {
        int max = 0;
        for (TreeIterator<EObject> it = formModel.eAllContents(); it.hasNext();)
        {
            EObject obj = it.next();
            if (attributeClass.isInstance(obj))
            {
                max = Math.max(max, intFeature(obj, FEATURE_ID));
            }
        }
        return max;
    }

    private static boolean hasExtensionPeer(EObject formModel)
    {
        return liveReference(formModel, FEATURE_BASE_FORM) != null
            || liveReference(formModel, FEATURE_EXTENSION_FORM) != null;
    }

    private static EObject liveReference(EObject owner, String featureName)
    {
        EObject reference = singleReference(owner, featureName);
        return reference != null && !reference.eIsProxy() ? reference : null;
    }

    /** The next free form-item id = max existing {@code FormItem} id across the whole form + 1. */
    private static int nextItemId(EObject formModel)
    {
        EClassifier formItem = formModel.eClass().getEPackage().getEClassifier(ECLASS_FORM_ITEM);
        boolean filter = formItem instanceof EClass;
        int max = 0;
        for (TreeIterator<EObject> it = formModel.eAllContents(); it.hasNext();)
        {
            EObject obj = it.next();
            if (filter && !((EClass)formItem).isInstance(obj))
            {
                continue;
            }
            EStructuralFeature idFeature = obj.eClass().getEStructuralFeature(FEATURE_ID);
            if (idFeature != null && obj.eGet(idFeature) instanceof Integer)
            {
                max = Math.max(max, ((Integer)obj.eGet(idFeature)).intValue());
            }
        }
        return max + 1;
    }

    /**
     * Repairs the form-wide {@code AbstractFormAttribute.id} invariant before validation/export sees
     * the model. The designer allocates these ids through {@code getNextAttributeId}; attributes and
     * attribute columns share this attribute id space, but it is intentionally independent from
     * {@code FormItem.id}.
     * Package-visible for the headless unit test.
     */
    static void normalizeFormAttributeIds(EObject formModel)
    {
        EClass attributeClass = formEClass(formModel, ECLASS_ABSTRACT_FORM_ATTRIBUTE);
        if (attributeClass == null)
        {
            return;
        }

        List<EObject> attributes = new ArrayList<>();
        int max = maxAttributeIdForAllocation(formModel, attributeClass);
        EObject extensionForm = liveReference(formModel, FEATURE_EXTENSION_FORM);
        if (extensionForm != null)
        {
            max = Math.max(max, maxAttributeIdForAllocation(extensionForm, attributeClass));
        }
        for (TreeIterator<EObject> it = formModel.eAllContents(); it.hasNext();)
        {
            EObject obj = it.next();
            if (!attributeClass.isInstance(obj))
            {
                continue;
            }
            attributes.add(obj);
        }

        Set<Integer> seen = new HashSet<>();
        for (EObject attribute : attributes)
        {
            int id = intFeature(attribute, FEATURE_ID);
            if (id > 0 && seen.add(Integer.valueOf(id)))
            {
                continue;
            }
            do
            {
                max++;
            }
            while (max <= 0 || seen.contains(Integer.valueOf(max)));
            setIntFeature(attribute, FEATURE_ID, max);
            seen.add(Integer.valueOf(max));
        }
    }

    /**
     * Repairs the form-wide {@code FormCommand.id} invariant before validation/export sees the model.
     * The designer allocates these ids through {@code getNextCommandId}; commands have their own id
     * space, independent from form items and form attributes.
     * Package-visible for the headless unit test.
     */
    static void normalizeFormCommandIds(EObject formModel)
    {
        EClass commandClass = formEClass(formModel, ECLASS_FORM_COMMAND);
        if (commandClass == null)
        {
            return;
        }

        List<EObject> commands = new ArrayList<>();
        int max = maxCommandIdForAllocation(formModel, commandClass);
        EObject extensionForm = liveReference(formModel, FEATURE_EXTENSION_FORM);
        if (extensionForm != null)
        {
            max = Math.max(max, maxCommandIdForAllocation(extensionForm, commandClass));
        }
        for (TreeIterator<EObject> it = formModel.eAllContents(); it.hasNext();)
        {
            EObject obj = it.next();
            if (!commandClass.isInstance(obj))
            {
                continue;
            }
            commands.add(obj);
        }

        Set<Integer> seen = new HashSet<>();
        for (EObject command : commands)
        {
            int id = intFeature(command, FEATURE_ID);
            if (id > 0 && seen.add(Integer.valueOf(id)))
            {
                continue;
            }
            do
            {
                max++;
            }
            while (max <= 0 || seen.contains(Integer.valueOf(max)));
            setIntFeature(command, FEATURE_ID, max);
            seen.add(Integer.valueOf(max));
        }
    }

    /**
     * Repairs the form-wide {@code FormItem.id} invariant before validation/export sees the model.
     * The form root's predefined {@code autoCommandBar} has the platform sentinel {@code -1}; every
     * other form item, including designer auto-children such as {@code contextMenu} and
     * {@code extendedTooltip}, gets a positive id unique in the same form-wide space.
     * Package-visible for the headless unit test.
     */
    static void normalizeFormItemIds(EObject formModel)
    {
        EPackage pkg = formModel.eClass().getEPackage();
        EClassifier formItem = pkg != null ? pkg.getEClassifier(ECLASS_FORM_ITEM) : null;
        if (!(formItem instanceof EClass))
        {
            return;
        }

        EClass formItemClass = (EClass)formItem;
        EObject rootAutoCommandBar = singleReference(formModel, FEATURE_AUTO_COMMAND_BAR);
        List<EObject> items = new ArrayList<>();
        int max = 0;
        for (TreeIterator<EObject> it = formModel.eAllContents(); it.hasNext();)
        {
            EObject obj = it.next();
            if (!formItemClass.isInstance(obj))
            {
                continue;
            }
            items.add(obj);
            if (obj != rootAutoCommandBar)
            {
                max = Math.max(max, intFeature(obj, FEATURE_ID));
            }
        }

        Set<Integer> seen = new HashSet<>();
        if (rootAutoCommandBar != null && formItemClass.isInstance(rootAutoCommandBar))
        {
            setIntFeature(rootAutoCommandBar, FEATURE_ID, -1);
            seen.add(Integer.valueOf(-1));
        }

        for (EObject item : items)
        {
            max = assignItemId(item, rootAutoCommandBar, seen, max);
        }
    }

    /**
     * Assigns {@code item} a positive id unique within {@code seen} - skipping the form root's
     * {@code autoCommandBar} (which keeps the platform sentinel {@code -1}) and keeping an already-unique
     * positive id - then advances and returns the running {@code max}. Early-returns keep the per-item
     * logic free of loop {@code continue}s.
     */
    private static int assignItemId(EObject item, EObject rootAutoCommandBar, Set<Integer> seen, int max)
    {
        if (item == rootAutoCommandBar)
        {
            return max;
        }
        int id = intFeature(item, FEATURE_ID);
        if (id > 0 && seen.add(Integer.valueOf(id)))
        {
            return max;
        }
        do
        {
            max++;
        }
        while (max <= 0 || seen.contains(Integer.valueOf(max)));
        setIntFeature(item, FEATURE_ID, max);
        seen.add(Integer.valueOf(max));
        return max;
    }

    // ---- reflective helpers ---------------------------------------------------------------------

    /** Writes the title for a language CODE into the object's {@code title} EMap (never the name). */
    private static void applyTitle(EObject object, String languageCode, String title)
    {
        if (languageCode == null || title == null || title.isEmpty())
        {
            return;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(FEATURE_TITLE);
        if (feature == null)
        {
            return;
        }
        Object value = object.eGet(feature);
        if (value instanceof EMap<?, ?>)
        {
            @SuppressWarnings("unchecked")
            EMap<String, String> map = (EMap<String, String>)value;
            map.put(languageCode, title);
        }
    }

    /**
     * Finds a form item by its (form-wide unique) programmatic name anywhere in the {@code items}
     * tree, or {@code null}. Used to resolve the owner of an item-level event handler. Must be called
     * on the transaction-bound form model.
     */
    public static EObject findFormItem(EObject formModel, String name)
    {
        return findItem(formModel, name);
    }

    /**
     * Finds a form item by name like {@link #findFormItem}, but REJECTS an ambiguous name (more than
     * one match anywhere in the form-item tree) by throwing a {@code RuntimeException} with a
     * user-facing message instead of silently returning the first match. The strict resolver for
     * write paths that mutate the named item (e.g. re-pointing a button's command). Returns the
     * unique match, or {@code null} when none exists. Call on the tx-bound form model.
     */
    public static EObject findUniqueFormItem(EObject formModel, String name)
    {
        return findUniqueItem(formModel, name);
    }

    /** Finds a form ATTRIBUTE by programmatic name, or {@code null}. Call on the tx-bound form model. */
    public static EObject findFormAttribute(EObject formModel, String name)
    {
        return findByName(referenceList(formModel, FEATURE_ATTRIBUTES), name);
    }

    /** Finds a form COMMAND by programmatic name, or {@code null}. Call on the tx-bound form model. */
    public static EObject findFormCommand(EObject formModel, String name)
    {
        return findByName(referenceList(formModel, FEATURE_FORM_COMMANDS), name);
    }

    /**
     * Resolves a form member EObject from a parsed member ref on the tx-bound form model: ATTRIBUTE
     * &rarr; the attributes list, COMMAND &rarr; the formCommands list, anything else (Field / Button /
     * Group / Decoration / Table / ...) &rarr; the items tree by name. Returns {@code null} if no such
     * member exists. A handler ref is NOT a member - resolve it via {@link #findFormHandler} on the
     * appropriate container.
     */
    public static EObject resolveFormMember(EObject formModel, FormMemberRef ref)
    {
        Kind kind = kindForToken(ref.kindToken);
        if (kind == Kind.ATTRIBUTE)
        {
            return findFormAttribute(formModel, ref.name);
        }
        if (kind == Kind.COMMAND)
        {
            return findFormCommand(formModel, ref.name);
        }
        return findFormItem(formModel, ref.name);
    }

    /**
     * Finds the event handler bound to {@code eventName} (English or Russian, case-insensitive) on
     * {@code container} (the form root or a form item), or {@code null}. Used to delete a handler by
     * the event its FQN names. Call on the tx-bound form model.
     */
    public static EObject findFormHandler(EObject container, String eventName)
    {
        if (ECLASS_FORM_COMMAND.equals(container.eClass().getName()))
        {
            // A command's single handler slot: its contained action (removing it clears the binding).
            if (!isActionToken(eventName))
            {
                return null;
            }
            return singleReference(container, FEATURE_ACTION);
        }
        EStructuralFeature handlersFeat = container.eClass().getEStructuralFeature(KEY_HANDLERS);
        if (!(handlersFeat instanceof EReference) || !handlersFeat.isMany())
        {
            return null;
        }
        EClass ehType = ((EReference)handlersFeat).getEReferenceType();
        EStructuralFeature evFeat = ehType != null ? ehType.getEStructuralFeature("event") : null; //$NON-NLS-1$
        for (EObject handler : referenceList(container, KEY_HANDLERS))
        {
            Object ev = evFeat != null ? handler.eGet(evFeat) : null;
            if (ev instanceof EObject
                && (eventName.equalsIgnoreCase(stringFeature((EObject)ev, "name")) //$NON-NLS-1$
                    || eventName.equalsIgnoreCase(stringFeature((EObject)ev, "nameRu")))) //$NON-NLS-1$
            {
                return handler;
            }
        }
        return null;
    }

    // ---- rebind: change an EXISTING handler's procedure / a button's command --------------------

    /**
     * Re-points an EXISTING event handler on {@code container} (the form root, a form item or a form
     * COMMAND) to a different BSL procedure. For an item / the form root it finds the handler bound
     * to {@code eventName} (English or Russian, case-insensitive) and overwrites its procedure
     * {@code name}; for a form command ({@code ...Command.X.Handler.Action}) the single Action's
     * contained {@code CommandHandler} is renamed. Does NOT bind a new event (that is
     * {@code create_metadata} via {@link #createHandler}); a missing handler is reported so the caller
     * can steer the user to create it. Reflective, so no compile-time form-model dependency. Call on
     * the tx-bound form model.
     *
     * @param container the form root, the owning form item or the form command (already resolved on
     *     the tx-bound model, see {@link #resolveHandlerContainer})
     * @param eventName the event whose handler to rebind (e.g. {@code OnChange}, or {@code Action}
     *     for a command)
     * @param procName the new BSL handler procedure name (must be non-blank)
     * @return {@code null} on success, or a human-readable error message
     */
    public static String rebindHandler(EObject container, String eventName, String procName)
    {
        if (ECLASS_FORM_COMMAND.equals(container.eClass().getName()))
        {
            // A command's single handler "event" is its Action: rename the CommandHandler inside the
            // action containment (the pair createCommandAction builds).
            if (procName == null || procName.isEmpty())
            {
                return "Provide the new handler procedure name in the 'procedure' property " //$NON-NLS-1$
                    + "(e.g. {name:'procedure', value:'PriceOnChange'})."; //$NON-NLS-1$
            }
            if (!isActionToken(eventName))
            {
                return ERR_EVENT_PREFIX + eventName + "' is not valid for a form command" //$NON-NLS-1$
                    + ". Available events: " + COMMAND_ACTION_EVENT; //$NON-NLS-1$
            }
            EObject action = singleReference(container, FEATURE_ACTION);
            EObject handler = action != null ? singleReference(action, FEATURE_HANDLER) : null;
            if (handler == null)
            {
                return "No event handler for '" + eventName + "' exists on this element to rebind. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Use create_metadata on the handler FQN to bind it first."; //$NON-NLS-1$
            }
            setStringFeature(handler, FEATURE_NAME, procName);
            return null;
        }
        EStructuralFeature handlersFeat = container.eClass().getEStructuralFeature(KEY_HANDLERS);
        if (!(handlersFeat instanceof EReference) || !handlersFeat.isMany())
        {
            return "The form element '" + container.eClass().getName() //$NON-NLS-1$
                + "' cannot hold event handlers."; //$NON-NLS-1$
        }
        if (procName == null || procName.isEmpty())
        {
            return "Provide the new handler procedure name in the 'procedure' property " //$NON-NLS-1$
                + "(e.g. {name:'procedure', value:'PriceOnChange'})."; //$NON-NLS-1$
        }
        EObject handler = findFormHandler(container, eventName);
        if (handler == null)
        {
            return "No event handler for '" + eventName + "' exists on this element to rebind. Use " //$NON-NLS-1$ //$NON-NLS-2$
                + "create_metadata on the handler FQN to bind it first."; //$NON-NLS-1$
        }
        setStringFeature(handler, FEATURE_NAME, procName);
        return null;
    }

    /**
     * Re-points an EXISTING button at a different (existing) form command: validates that
     * {@code button} carries a {@code commandName} reference and that a {@code FormCommand} named
     * {@code commandName} exists on {@code formModel}, then sets the reference. A button's
     * {@code commandName} targets a FormCommand (a form-model object, not an mdclass object), so it
     * is not introspector-assignable and is rebound here. Reflective, so no compile-time form-model
     * dependency. Call on the tx-bound form model.
     *
     * @param formModel the editable form content model (tx-bound)
     * @param button the button form item (already resolved on the tx-bound model)
     * @param commandName the name of the existing form command to point the button at
     * @return {@code null} on success, or a human-readable error message
     */
    public static String rebindButtonCommand(EObject formModel, EObject button, String commandName)
    {
        EStructuralFeature cmdFeat = button.eClass().getEStructuralFeature("commandName"); //$NON-NLS-1$
        if (!(cmdFeat instanceof EReference))
        {
            return "The form item '" + button.eClass().getName() //$NON-NLS-1$
                + "' has no 'commandName' reference; only a Button runs a form command."; //$NON-NLS-1$
        }
        if (commandName == null || commandName.isEmpty())
        {
            return "Provide the form command to point the button at in the 'command' property " //$NON-NLS-1$
                + "(e.g. {name:'command', value:'Refresh'})."; //$NON-NLS-1$
        }
        EObject command = findByName(referenceList(formModel, FEATURE_FORM_COMMANDS), commandName);
        if (command == null)
        {
            return "Form command '" + commandName + "' not found - create it first " //$NON-NLS-1$ //$NON-NLS-2$
                + "(create_metadata on the form's Command FQN), then re-point the button at it."; //$NON-NLS-1$
        }
        button.eSet(cmdFeat, command);
        return null;
    }

    /**
     * Depth-first search of ALL contained {@code FormItem}s for an item by its (form-wide unique)
     * programmatic name. Walks every containment that holds form items - the {@code items} tree, the
     * auto command bars (form- and table-level), context menus, extended tooltips - not just
     * {@code items}, by filtering {@code eContents()} to {@code FormItem} instances (the same filter
     * {@code nextItemId} uses).
     */
    private static EObject findItem(EObject root, String name)
    {
        EClassifier formItem = root.eClass().getEPackage().getEClassifier(ECLASS_FORM_ITEM);
        if (!(formItem instanceof EClass))
        {
            return null;
        }
        return findItemIn(root, name, (EClass)formItem);
    }

    private static EObject findItemIn(EObject container, String name, EClass formItem)
    {
        for (EObject child : container.eContents())
        {
            if (!formItem.isInstance(child))
            {
                continue;
            }
            if (name.equalsIgnoreCase(stringFeature(child, FEATURE_NAME)))
            {
                return child;
            }
            EObject nested = findItemIn(child, name, formItem);
            if (nested != null)
            {
                return nested;
            }
        }
        return null;
    }

    private static EObject findByName(EList<EObject> list, String name)
    {
        for (EObject e : list)
        {
            if (name.equalsIgnoreCase(stringFeature(e, FEATURE_NAME)))
            {
                return e;
            }
        }
        return null;
    }

    /** The value of a single-valued EReference, or {@code null} when absent/unset/not a reference. */
    private static EObject singleReference(EObject owner, String featureName)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EReference) || feature.isMany())
        {
            return null;
        }
        Object value = owner.eGet(feature);
        return value instanceof EObject ? (EObject)value : null;
    }

    /** Sets a single-valued EReference by feature name; a no-op when the feature is absent / not a
     * single-valued reference or {@code value} is {@code null} (best-effort, like the other setters). */
    private static void setSingleReference(EObject owner, String featureName, EObject value)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (feature instanceof EReference && !feature.isMany() && value != null)
        {
            owner.eSet(feature, value);
        }
    }

    /** The literal of a set EEnum attribute (e.g. a group's {@code type}), or {@code null}. */
    private static String enumLiteralOf(EObject owner, String featureName)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EAttribute)
            || !(((EAttribute)feature).getEAttributeType() instanceof EEnum))
        {
            return null;
        }
        Object value = owner.eGet(feature);
        if (value instanceof Enumerator)
        {
            return ((Enumerator)value).getLiteral();
        }
        return value != null ? value.toString() : null;
    }

    /**
     * Fills a contained {@code AdjustableBoolean} feature ({@code userVisible} on a visual item,
     * {@code use} on a command) with a fresh instance whose {@code common} flag is set - what the
     * platform factory's {@code newAdjustableBoolean} produces. A no-op when the feature is absent.
     */
    private static void setAdjustableBooleanFeature(EObject owner, String featureName)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EReference) || feature.isMany())
        {
            return;
        }
        EClass type = ((EReference)feature).getEReferenceType();
        if (type == null || type.getEPackage() == null || type.isAbstract())
        {
            return;
        }
        EObject adjustable = type.getEPackage().getEFactoryInstance().create(type);
        setBooleanFeature(adjustable, FEATURE_COMMON, true);
        owner.eSet(feature, adjustable);
    }

    @SuppressWarnings("unchecked")
    private static EList<EObject> referenceList(EObject owner, String featureName)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            Object value = owner.eGet(feature);
            if (value instanceof EList<?>)
            {
                return (EList<EObject>)value;
            }
        }
        return org.eclipse.emf.common.util.ECollections.emptyEList();
    }

    @SuppressWarnings("unchecked")
    private static void addToList(EObject container, String featureName, EObject element)
    {
        EStructuralFeature feature = container.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EReference) || !feature.isMany())
        {
            throw new IllegalArgumentException("Form feature '" + featureName + "' is not a list"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ((EList<EObject>)container.eGet(feature)).add(element);
    }

    private static void recordKind(EObject element, String[] createdKind)
    {
        if (createdKind != null && createdKind.length > 0)
        {
            createdKind[0] = element.eClass().getName();
        }
    }

    private static String stringFeature(EObject object, String featureName)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        Object value = feature != null ? object.eGet(feature) : null;
        return value instanceof String ? (String)value : null;
    }

    private static int intFeature(EObject object, String featureName)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        Object value = feature != null ? object.eGet(feature) : null;
        return value instanceof Integer ? ((Integer)value).intValue() : 0;
    }

    private static void setStringFeature(EObject object, String featureName, String value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, value);
        }
    }

    private static void setBooleanFeature(EObject object, String featureName, boolean value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, Boolean.valueOf(value));
        }
    }

    private static void setIntFeature(EObject object, String featureName, int value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, Integer.valueOf(value));
        }
    }

    private static void setEnumFeature(EObject object, String featureName, String literal)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EAttribute))
        {
            return;
        }
        EClassifier type = ((EAttribute)feature).getEAttributeType();
        if (!(type instanceof EEnum))
        {
            return;
        }
        EEnumLiteral enumLiteral = ((EEnum)type).getEEnumLiteralByLiteral(literal);
        if (enumLiteral != null)
        {
            object.eSet(feature, enumLiteral.getInstance());
        }
    }

    /**
     * Sets a boolean feature only when the factory (or anyone else) has not already set it
     * ({@code eIsSet}); used by {@link #applyFormDefaults} so the real {@code FormObjectFactory}'s
     * version-correct values are never clobbered. A no-op when the feature is absent.
     */
    private static void setBooleanFeatureIfUnset(EObject object, String featureName, boolean value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null && !object.eIsSet(feature))
        {
            object.eSet(feature, Boolean.valueOf(value));
        }
    }

    /**
     * Sets an EEnum feature only when it is not already set ({@code eIsSet}), resolving the requested
     * value against the literal string OR the literal name, case-insensitively. The resilient
     * resolution matters for form-model enums whose literal differs from the name (e.g.
     * {@code ShowTitle851.AUTO} has name {@code "Auto"} but literal {@code "auto"}). A no-op when the
     * feature is absent, not an EEnum, or the value resolves to no literal.
     */
    private static void setEnumFeatureIfUnset(EObject object, String featureName, String literalOrName)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EAttribute) || object.eIsSet(feature))
        {
            return;
        }
        EClassifier type = ((EAttribute)feature).getEAttributeType();
        if (!(type instanceof EEnum))
        {
            return;
        }
        for (EEnumLiteral enumLiteral : ((EEnum)type).getELiterals())
        {
            if (literalOrName.equalsIgnoreCase(enumLiteral.getLiteral())
                || literalOrName.equalsIgnoreCase(enumLiteral.getName()))
            {
                object.eSet(feature, enumLiteral.getInstance());
                return;
            }
        }
    }
}
