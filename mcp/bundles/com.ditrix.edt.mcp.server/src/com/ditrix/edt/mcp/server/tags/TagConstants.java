/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.tags;

/**
 * Constants used across the tags module.
 * Centralizes magic strings and configuration values.
 */
public final class TagConstants {
    
    private TagConstants() {
        // Utility class
    }
    
    // === Storage Constants ===
    
    /** Folder for project settings */
    public static final String SETTINGS_FOLDER = ".settings";
    
    /** YAML file for tag storage */
    public static final String TAGS_FILE = "metadata-tags.yaml";
    
    // === EClass Type Names ===
    
    /** Configuration root type */
    public static final String TYPE_CONFIGURATION = "Configuration";
    
    /** Subsystem type (special handling for nested subsystems) */
    public static final String TYPE_SUBSYSTEM = "Subsystem";
    
    // === Type Prefixes for FQN ===
    
    public static final String TYPE_DOCUMENT = "Document";
    public static final String TYPE_CATALOG = "Catalog";
    public static final String TYPE_ENUM = "Enum";
    public static final String TYPE_REPORT = "Report";
    public static final String TYPE_DATA_PROCESSOR = "DataProcessor";
    public static final String TYPE_INFORMATION_REGISTER = "InformationRegister";
    public static final String TYPE_ACCUMULATION_REGISTER = "AccumulationRegister";
    public static final String TYPE_ACCOUNTING_REGISTER = "AccountingRegister";
    public static final String TYPE_CALCULATION_REGISTER = "CalculationRegister";
    public static final String TYPE_COMMON_MODULE = "CommonModule";
    public static final String TYPE_COMMON_FORM = "CommonForm";
    public static final String TYPE_COMMON_COMMAND = "CommonCommand";
    public static final String TYPE_COMMON_TEMPLATE = "CommonTemplate";
    public static final String TYPE_COMMON_PICTURE = "CommonPicture";
    public static final String TYPE_COMMON_ATTRIBUTE = "CommonAttribute";
    public static final String TYPE_COMMAND_GROUP = "CommandGroup";
    public static final String TYPE_STYLE_ITEM = "StyleItem";
    public static final String TYPE_STYLE = "Style";
    public static final String TYPE_PALETTE_COLOR = "PaletteColor";
    public static final String TYPE_SESSION_PARAMETER = "SessionParameter";
    public static final String TYPE_ROLE = "Role";
    public static final String TYPE_EXCHANGE_PLAN = "ExchangePlan";
    public static final String TYPE_FILTER_CRITERION = "FilterCriterion";
    public static final String TYPE_EVENT_SUBSCRIPTION = "EventSubscription";
    public static final String TYPE_SCHEDULED_JOB = "ScheduledJob";
    public static final String TYPE_BOT = "Bot";
    public static final String TYPE_FUNCTIONAL_OPTION = "FunctionalOption";
    public static final String TYPE_FUNCTIONAL_OPTIONS_PARAMETER = "FunctionalOptionsParameter";
    public static final String TYPE_DEFINED_TYPE = "DefinedType";
    public static final String TYPE_SETTINGS_STORAGE = "SettingsStorage";
    public static final String TYPE_XDTO_PACKAGE = "XDTOPackage";
    public static final String TYPE_WEB_SERVICE = "WebService";
    public static final String TYPE_HTTP_SERVICE = "HTTPService";
    public static final String TYPE_WS_REFERENCE = "WSReference";
    public static final String TYPE_WEB_SOCKET_CLIENT = "WebSocketClient";
    public static final String TYPE_INTEGRATION_SERVICE = "IntegrationService";
    public static final String TYPE_SEQUENCE = "Sequence";
    public static final String TYPE_RECALCULATION = "Recalculation";
    public static final String TYPE_LANGUAGE = "Language";
    public static final String TYPE_CONSTANT = "Constant";
    public static final String TYPE_BUSINESS_PROCESS = "BusinessProcess";
    public static final String TYPE_TASK = "Task";
    public static final String TYPE_CHART_OF_CHARACTERISTIC_TYPES = "ChartOfCharacteristicTypes";
    public static final String TYPE_CHART_OF_ACCOUNTS = "ChartOfAccounts";
    public static final String TYPE_CHART_OF_CALCULATION_TYPES = "ChartOfCalculationTypes";
    public static final String TYPE_EXTERNAL_DATA_SOURCE = "ExternalDataSource";
    public static final String TYPE_DOCUMENT_JOURNAL = "DocumentJournal";
    public static final String TYPE_DOCUMENT_NUMERATOR = "DocumentNumerator";
    
    // === Nested Object Types ===
    
    public static final String NESTED_ATTRIBUTE = "Attribute";
    public static final String NESTED_ENUM_VALUE = "EnumValue";
    public static final String NESTED_TABULAR_SECTION = "TabularSection";
    public static final String NESTED_DIMENSION = "Dimension";
    public static final String NESTED_RESOURCE = "Resource";
    public static final String NESTED_FORM = "Form";
    public static final String NESTED_COMMAND = "Command";
    public static final String NESTED_TEMPLATE = "Template";
    
    // === URI Schemes ===
    
    /** BM (Big Model) URI scheme */
    public static final String BM_URI_SCHEME = "bm://";
    
    // === View IDs ===
    
    /** EDT Navigator view ID */
    public static final String NAVIGATOR_VIEW_ID = "com._1c.g5.v8.dt.ui2.navigator";
    
    // === Default Values ===
    
    /** Default tag color (gray) */
    public static final String DEFAULT_TAG_COLOR = "#808080";
    
    /** Default color icon size in pixels */
    public static final int COLOR_ICON_SIZE_SMALL = 12;
    public static final int COLOR_ICON_SIZE_NORMAL = 16;
    public static final int COLOR_ICON_SIZE_LARGE = 24;
    
    // === Method Names (for reflection) ===
    
    /** getName() method for retrieving object names */
    public static final String METHOD_GET_NAME = "getName";
    
    /** getParentSubsystem() method for nested subsystems */
    public static final String METHOD_GET_PARENT_SUBSYSTEM = "getParentSubsystem";
    
    /** getSubsystems() method for subsystem children */
    public static final String METHOD_GET_SUBSYSTEMS = "getSubsystems";
    
    /** getModel() method for navigator adapters */
    public static final String METHOD_GET_MODEL = "getModel";
    
    /** getModelObjectName() method for nested folders */
    public static final String METHOD_GET_MODEL_OBJECT_NAME = "getModelObjectName";
    
    /** getProject() method for getting project from objects */
    public static final String METHOD_GET_PROJECT = "getProject";
}
