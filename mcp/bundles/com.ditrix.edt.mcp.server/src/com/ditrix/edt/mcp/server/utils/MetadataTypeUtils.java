/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Centralized utility for 1C metadata type name resolution.
 * Single source of truth for all metadata type mappings:
 * English singular/plural, Russian singular/plural, directory names,
 * and EMF Configuration reference names.
 * <p>
 * Supports case-insensitive lookup for all name variants.
 */
public final class MetadataTypeUtils
{
    /**
     * Metadata type information: all known forms of a metadata type name.
     */
    public enum MetadataTypeInfo
    {
        CATALOG("Catalog", "Catalogs", "catalogs", "Catalogs", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A", "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A\u0438"), // Справочник, Справочники //$NON-NLS-1$ //$NON-NLS-2$

        DOCUMENT("Document", "Documents", "documents", "Documents", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442", "\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u044B"), // Документ, Документы //$NON-NLS-1$ //$NON-NLS-2$

        COMMON_MODULE("CommonModule", "CommonModules", "commonModules", "CommonModules", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u041E\u0431\u0449\u0438\u0439\u041C\u043E\u0434\u0443\u043B\u044C"), // ОбщийМодуль //$NON-NLS-1$

        INFORMATION_REGISTER("InformationRegister", "InformationRegisters", "informationRegisters", "InformationRegisters", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0421\u0432\u0435\u0434\u0435\u043D\u0438\u0439", //$NON-NLS-1$
            "\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u044B\u0421\u0432\u0435\u0434\u0435\u043D\u0438\u0439"), // РегистрСведений, РегистрыСведений //$NON-NLS-1$

        ACCUMULATION_REGISTER("AccumulationRegister", "AccumulationRegisters", "accumulationRegisters", "AccumulationRegisters", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u041D\u0430\u043A\u043E\u043F\u043B\u0435\u043D\u0438\u044F", //$NON-NLS-1$
            "\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u044B\u041D\u0430\u043A\u043E\u043F\u043B\u0435\u043D\u0438\u044F"), // РегистрНакопления, РегистрыНакопления //$NON-NLS-1$

        ENUM("Enum", "Enums", "enums", "Enums", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u041F\u0435\u0440\u0435\u0447\u0438\u0441\u043B\u0435\u043D\u0438\u0435", "\u041F\u0435\u0440\u0435\u0447\u0438\u0441\u043B\u0435\u043D\u0438\u044F"), // Перечисление, Перечисления //$NON-NLS-1$ //$NON-NLS-2$

        REPORT("Report", "Reports", "reports", "Reports", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u041E\u0442\u0447\u0435\u0442", "\u041E\u0442\u0447\u0435\u0442\u044B"), // Отчет, Отчеты //$NON-NLS-1$ //$NON-NLS-2$

        DATA_PROCESSOR("DataProcessor", "DataProcessors", "dataProcessors", "DataProcessors", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u041E\u0431\u0440\u0430\u0431\u043E\u0442\u043A\u0430", "\u041E\u0431\u0440\u0430\u0431\u043E\u0442\u043A\u0438"), // Обработка, Обработки //$NON-NLS-1$ //$NON-NLS-2$

        EXCHANGE_PLAN("ExchangePlan", "ExchangePlans", "exchangePlans", "ExchangePlans", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u041F\u043B\u0430\u043D\u041E\u0431\u043C\u0435\u043D\u0430", "\u041F\u043B\u0430\u043D\u044B\u041E\u0431\u043C\u0435\u043D\u0430"), // ПланОбмена, ПланыОбмена //$NON-NLS-1$ //$NON-NLS-2$

        BUSINESS_PROCESS("BusinessProcess", "BusinessProcesses", "businessProcesses", "BusinessProcesses", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u0411\u0438\u0437\u043D\u0435\u0441\u041F\u0440\u043E\u0446\u0435\u0441\u0441", //$NON-NLS-1$
            "\u0411\u0438\u0437\u043D\u0435\u0441\u041F\u0440\u043E\u0446\u0435\u0441\u0441\u044B"), // БизнесПроцесс, БизнесПроцессы //$NON-NLS-1$

        TASK("Task", "Tasks", "tasks", "Tasks", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u0417\u0430\u0434\u0430\u0447\u0430", "\u0417\u0430\u0434\u0430\u0447\u0438"), // Задача, Задачи //$NON-NLS-1$ //$NON-NLS-2$

        ROLE("Role", "Roles", "roles", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u0420\u043E\u043B\u044C", "\u0420\u043E\u043B\u0438"), // Роль, Роли //$NON-NLS-1$ //$NON-NLS-2$

        SUBSYSTEM("Subsystem", "Subsystems", "subsystems", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u041F\u043E\u0434\u0441\u0438\u0441\u0442\u0435\u043C\u0430", "\u041F\u043E\u0434\u0441\u0438\u0441\u0442\u0435\u043C\u044B"), // Подсистема, Подсистемы //$NON-NLS-1$ //$NON-NLS-2$

        COMMON_COMMAND("CommonCommand", "CommonCommands", "commonCommands", "CommonCommands", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u041E\u0431\u0449\u0430\u044F\u041A\u043E\u043C\u0430\u043D\u0434\u0430"), // ОбщаяКоманда //$NON-NLS-1$

        COMMON_FORM("CommonForm", "CommonForms", "commonForms", "CommonForms", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u041E\u0431\u0449\u0430\u044F\u0424\u043E\u0440\u043C\u0430"), // ОбщаяФорма //$NON-NLS-1$

        WEB_SERVICE("WebService", "WebServices", "webServices", "WebServices", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u0412\u0435\u0431\u0421\u0435\u0440\u0432\u0438\u0441"), // ВебСервис //$NON-NLS-1$

        HTTP_SERVICE("HTTPService", "HTTPServices", "httpServices", "HTTPServices", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "HTTP\u0421\u0435\u0440\u0432\u0438\u0441"), // HTTPСервис //$NON-NLS-1$

        CONSTANT("Constant", "Constants", "constants", "Constants", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u041A\u043E\u043D\u0441\u0442\u0430\u043D\u0442\u0430", "\u041A\u043E\u043D\u0441\u0442\u0430\u043D\u0442\u044B"), // Константа, Константы //$NON-NLS-1$ //$NON-NLS-2$

        CHART_OF_CHARACTERISTIC_TYPES("ChartOfCharacteristicTypes", "ChartsOfCharacteristicTypes", //$NON-NLS-1$ //$NON-NLS-2$
            "chartsOfCharacteristicTypes", "ChartsOfCharacteristicTypes", //$NON-NLS-1$ //$NON-NLS-2$
            "\u041F\u043B\u0430\u043D\u0412\u0438\u0434\u043E\u0432\u0425\u0430\u0440\u0430\u043A\u0442\u0435\u0440\u0438\u0441\u0442\u0438\u043A"), // ПланВидовХарактеристик //$NON-NLS-1$

        CHART_OF_ACCOUNTS("ChartOfAccounts", "ChartsOfAccounts", "chartsOfAccounts", "ChartsOfAccounts", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u041F\u043B\u0430\u043D\u0421\u0447\u0435\u0442\u043E\u0432"), // ПланСчетов //$NON-NLS-1$

        CHART_OF_CALCULATION_TYPES("ChartOfCalculationTypes", "ChartsOfCalculationTypes", //$NON-NLS-1$ //$NON-NLS-2$
            "chartsOfCalculationTypes", "ChartsOfCalculationTypes", //$NON-NLS-1$ //$NON-NLS-2$
            "\u041F\u043B\u0430\u043D\u0412\u0438\u0434\u043E\u0432\u0420\u0430\u0441\u0447\u0435\u0442\u0430"), // ПланВидовРасчета //$NON-NLS-1$

        ACCOUNTING_REGISTER("AccountingRegister", "AccountingRegisters", "accountingRegisters", "AccountingRegisters", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0411\u0443\u0445\u0433\u0430\u043B\u0442\u0435\u0440\u0438\u0438"), // РегистрБухгалтерии //$NON-NLS-1$

        CALCULATION_REGISTER("CalculationRegister", "CalculationRegisters", "calculationRegisters", "CalculationRegisters", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0420\u0430\u0441\u0447\u0435\u0442\u0430"), // РегистрРасчета //$NON-NLS-1$

        DOCUMENT_JOURNAL("DocumentJournal", "DocumentJournals", "documentJournals", "DocumentJournals", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u0416\u0443\u0440\u043D\u0430\u043B\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u043E\u0432"), // ЖурналДокументов //$NON-NLS-1$

        SEQUENCE("Sequence", "Sequences", "sequences", "Sequences", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u041F\u043E\u0441\u043B\u0435\u0434\u043E\u0432\u0430\u0442\u0435\u043B\u044C\u043D\u043E\u0441\u0442\u044C"), // Последовательность //$NON-NLS-1$

        FILTER_CRITERION("FilterCriterion", "FilterCriteria", "filterCriteria", "FilterCriteria", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u041A\u0440\u0438\u0442\u0435\u0440\u0438\u0439\u041E\u0442\u0431\u043E\u0440\u0430"), // КритерийОтбора //$NON-NLS-1$

        SETTINGS_STORAGE("SettingsStorage", "SettingsStorages", "settingsStorages", "SettingsStorages", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u0425\u0440\u0430\u043D\u0438\u043B\u0438\u0449\u0435\u041D\u0430\u0441\u0442\u0440\u043E\u0435\u043A"), // ХранилищеНастроек //$NON-NLS-1$

        EXTERNAL_DATA_SOURCE("ExternalDataSource", "ExternalDataSources", "externalDataSources", "ExternalDataSources", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "\u0412\u043D\u0435\u0448\u043D\u0438\u0439\u0418\u0441\u0442\u043E\u0447\u043D\u0438\u043A\u0414\u0430\u043D\u043D\u044B\u0445"), // ВнешнийИсточникДанных //$NON-NLS-1$

        COMMON_ATTRIBUTE("CommonAttribute", "CommonAttributes", "commonAttributes", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u041E\u0431\u0449\u0438\u0439\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442"), // ОбщийРеквизит //$NON-NLS-1$

        EVENT_SUBSCRIPTION("EventSubscription", "EventSubscriptions", "eventSubscriptions", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u041F\u043E\u0434\u043F\u0438\u0441\u043A\u0430\u041D\u0430\u0421\u043E\u0431\u044B\u0442\u0438\u0435"), // ПодпискаНаСобытие //$NON-NLS-1$

        SCHEDULED_JOB("ScheduledJob", "ScheduledJobs", "scheduledJobs", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u0420\u0435\u0433\u043B\u0430\u043C\u0435\u043D\u0442\u043D\u043E\u0435\u0417\u0430\u0434\u0430\u043D\u0438\u0435"), // РегламентноеЗадание //$NON-NLS-1$

        SESSION_PARAMETER("SessionParameter", "SessionParameters", "sessionParameters", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u041F\u0430\u0440\u0430\u043C\u0435\u0442\u0440\u0421\u0435\u0430\u043D\u0441\u0430"), // ПараметрСеанса //$NON-NLS-1$

        FUNCTIONAL_OPTION("FunctionalOption", "FunctionalOptions", "functionalOptions", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u0424\u0443\u043D\u043A\u0446\u0438\u043E\u043D\u0430\u043B\u044C\u043D\u0430\u044F\u041E\u043F\u0446\u0438\u044F"), // ФункциональнаяОпция //$NON-NLS-1$

        FUNCTIONAL_OPTIONS_PARAMETER("FunctionalOptionsParameter", "FunctionalOptionsParameters", //$NON-NLS-1$ //$NON-NLS-2$
            "functionalOptionsParameters", null, //$NON-NLS-1$
            "\u041F\u0430\u0440\u0430\u043C\u0435\u0442\u0440\u0424\u0443\u043D\u043A\u0446\u0438\u043E\u043D\u0430\u043B\u044C\u043D\u044B\u0445\u041E\u043F\u0446\u0438\u0439"), // ПараметрФункциональныхОпций //$NON-NLS-1$

        COMMON_PICTURE("CommonPicture", "CommonPictures", "commonPictures", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u041E\u0431\u0449\u0430\u044F\u041A\u0430\u0440\u0442\u0438\u043D\u043A\u0430"), // ОбщаяКартинка //$NON-NLS-1$

        STYLE_ITEM("StyleItem", "StyleItems", "styleItems", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u042D\u043B\u0435\u043C\u0435\u043D\u0442\u0421\u0442\u0438\u043B\u044F"), // ЭлементСтиля //$NON-NLS-1$

        DEFINED_TYPE("DefinedType", "DefinedTypes", "definedTypes", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u041E\u043F\u0440\u0435\u0434\u0435\u043B\u044F\u0435\u043C\u044B\u0439\u0422\u0438\u043F"), // ОпределяемыйТип //$NON-NLS-1$

        COMMON_TEMPLATE("CommonTemplate", "CommonTemplates", "commonTemplates", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u041E\u0431\u0449\u0438\u0439\u041C\u0430\u043A\u0435\u0442"), // ОбщийМакет //$NON-NLS-1$

        COMMAND_GROUP("CommandGroup", "CommandGroups", "commandGroups", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u0413\u0440\u0443\u043F\u043F\u0430\u041A\u043E\u043C\u0430\u043D\u0434"), // ГруппаКоманд //$NON-NLS-1$

        DOCUMENT_NUMERATOR("DocumentNumerator", "DocumentNumerators", "documentNumerators", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u041D\u0443\u043C\u0435\u0440\u0430\u0442\u043E\u0440\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u043E\u0432"), // НумераторДокументов //$NON-NLS-1$

        WS_REFERENCE("WSReference", "WSReferences", "wsReferences", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "WS\u0421\u0441\u044B\u043B\u043A\u0430"), // WSСсылка //$NON-NLS-1$

        // The Configuration collection feature is "xDTOPackages" (capital DTO), not "xdtoPackages" -
        // a casing mismatch made create_metadata fail to resolve the collection (verified live).
        XDTO_PACKAGE("XDTOPackage", "XDTOPackages", "xDTOPackages", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u041F\u0430\u043A\u0435\u0442XDTO"), // ПакетXDTO //$NON-NLS-1$

        LANGUAGE("Language", "Languages", "languages", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u042F\u0437\u044B\u043A", "\u042F\u0437\u044B\u043A\u0438"), // Язык, Языки //$NON-NLS-1$ //$NON-NLS-2$

        STYLE("Style", "Styles", "styles", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u0421\u0442\u0438\u043B\u044C", "\u0421\u0442\u0438\u043B\u0438"), // Стиль, Стили //$NON-NLS-1$ //$NON-NLS-2$

        INTERFACE("Interface", "Interfaces", "interfaces", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u0418\u043D\u0442\u0435\u0440\u0444\u0435\u0439\u0441", "\u0418\u043D\u0442\u0435\u0440\u0444\u0435\u0439\u0441\u044B"), // Интерфейс, Интерфейсы //$NON-NLS-1$ //$NON-NLS-2$

        INTEGRATION_SERVICE("IntegrationService", "IntegrationServices", "integrationServices", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u0421\u0435\u0440\u0432\u0438\u0441\u0418\u043D\u0442\u0435\u0433\u0440\u0430\u0446\u0438\u0438"), // СервисИнтеграции //$NON-NLS-1$

        BOT("Bot", "Bots", "bots", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u0411\u043E\u0442", "\u0411\u043E\u0442\u044B"), // Бот, Боты //$NON-NLS-1$ //$NON-NLS-2$

        WEB_SOCKET_CLIENT("WebSocketClient", "WebSocketClients", "webSocketClients", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "WebSocket\u041A\u043B\u0438\u0435\u043D\u0442"); // WebSocketКлиент //$NON-NLS-1$

        private final String englishSingular;
        private final String englishPlural;
        private final String configReferenceName;
        private final String directoryName; // null if type has no src/ directory
        private final String[] russianNames;

        MetadataTypeInfo(String englishSingular, String englishPlural,
                         String configReferenceName, String directoryName,
                         String... russianNames)
        {
            this.englishSingular = englishSingular;
            this.englishPlural = englishPlural;
            this.configReferenceName = configReferenceName;
            this.directoryName = directoryName;
            this.russianNames = russianNames;
        }

        public String getEnglishSingular()
        {
            return englishSingular;
        }

        public String getEnglishPlural()
        {
            return englishPlural;
        }

        public String getConfigReferenceName()
        {
            return configReferenceName;
        }

        /** @return directory name in src/, or {@code null} if not applicable */
        public String getDirectoryName()
        {
            return directoryName;
        }

        public String[] getRussianNames()
        {
            return russianNames;
        }
    }

    /**
     * Canonical English and Russian spellings of a NESTED structural FQN segment kind - the
     * {@code .Kind.} token that separates two programmatic names inside a nested full name
     * (e.g. {@code Form} in {@code Document.Order.Form.ItemForm}).
     * <p>
     * Deliberately NOT a {@link MetadataTypeInfo} constant: that enum is the catalogue of
     * TOP-LEVEL configuration types (each carries a {@code Configuration} collection reference
     * and an {@code src/} directory name), and a nested kind has neither. Keeping the two
     * catalogues apart lets {@link MetadataTypeUtils#getAllFqnVariants(String)} translate every
     * structural segment of a nested FQN without polluting the top-level type catalogue with
     * synthetic entries.
     */
    public static final class NestedKindInfo
    {
        private final String english;
        private final String russian;

        private NestedKindInfo(String english, String russian)
        {
            this.english = english;
            this.russian = russian;
        }

        /**
         * @return the canonical English singular token (e.g. {@code "Form"})
         */
        public String getEnglish()
        {
            return english;
        }

        /**
         * @return the canonical Russian singular token (the Cyrillic spelling of the same kind)
         */
        public String getRussian()
        {
            return russian;
        }
    }

    /** Key: lowercase name variant -> MetadataTypeInfo */
    private static final Map<String, MetadataTypeInfo> LOOKUP = new HashMap<>();

    /** Key: directory name (case-sensitive) -> MetadataTypeInfo */
    private static final Map<String, MetadataTypeInfo> DIR_LOOKUP = new HashMap<>();

    /** Ordered set of all English singular names */
    private static final Set<String> ALL_ENGLISH_SINGULAR;

    static
    {
        Set<String> singulars = new LinkedHashSet<>();
        for (MetadataTypeInfo info : MetadataTypeInfo.values())
        {
            LOOKUP.put(info.englishSingular.toLowerCase(), info);
            LOOKUP.put(info.englishPlural.toLowerCase(), info);
            for (String ru : info.russianNames)
            {
                LOOKUP.put(ru.toLowerCase(), info);
            }
            if (info.directoryName != null)
            {
                DIR_LOOKUP.put(info.directoryName, info);
            }
            singulars.add(info.englishSingular);
        }
        ALL_ENGLISH_SINGULAR = Collections.unmodifiableSet(singulars);
    }

    /**
     * Key: lowercase NESTED structural segment token (English/Russian, singular/plural) -&gt; the
     * canonical English/Russian pair. This is a segment-alias catalogue ONLY: unlike the
     * token-to-EMF-feature map in {@link MetadataNodeResolver} it says nothing about containment
     * features, so a kind that has no navigable feature there (e.g. {@code Form}) still belongs here.
     */
    private static final Map<String, NestedKindInfo> NESTED_KIND_LOOKUP;
    static
    {
        // The Russian tokens are built from Unicode code points via cp(...) so this source stays
        // pure ASCII: no raw Cyrillic and no reliance on the compiler source encoding (the same
        // non-UTF-8 Tycho-build risk the project guards against elsewhere). The trailing comment
        // spells each token out in ASCII only.
        Map<String, NestedKindInfo> m = new HashMap<>();
        // Form (ru: forma / formy)
        putNestedKind(m, "Form", "Forms", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x0424, 0x043e, 0x0440, 0x043c, 0x0430),
            cp(0x0424, 0x043e, 0x0440, 0x043c, 0x044b));
        // Attribute (ru: rekvizit / rekvizity)
        putNestedKind(m, "Attribute", "Attributes", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x0420, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442),
            cp(0x0420, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442, 0x044b));
        // Subsystem NESTED under another subsystem (ru: podsistema / podsistemy). SubsystemUtils
        // parses 'Podsistema.Sales.Subsystem.Orders' and modify_metadata documents it, so the second
        // token must translate too - it is a top-level type AND a nested one.
        putNestedKind(m, "Subsystem", "Subsystems", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041f, 0x043e, 0x0434, 0x0441, 0x0438, 0x0441, 0x0442, 0x0435, 0x043c, 0x0430),
            cp(0x041f, 0x043e, 0x0434, 0x0441, 0x0438, 0x0441, 0x0442, 0x0435, 0x043c, 0x044b));
        // Predefined item (ru: predopredelennye / predopredelyonnye - PredefinedWriter accepts BOTH
        // the 'e' and the 'yo' spelling, so both are aliases here as well).
        putNestedKind(m, "Predefined", "Predefined", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041f, 0x0440, 0x0435, 0x0434, 0x043e, 0x043f, 0x0440, 0x0435, 0x0434, 0x0435, 0x043b,
                0x0435, 0x043d, 0x043d, 0x044b, 0x0435),
            cp(0x041f, 0x0440, 0x0435, 0x0434, 0x043e, 0x043f, 0x0440, 0x0435, 0x0434, 0x0435, 0x043b,
                0x0451, 0x043d, 0x043d, 0x044b, 0x0435));
        // Module (ru: modul / moduli). Not an mdclass child kind, but EDT ends a BSL marker's
        // presentation with it (e.g. "CommonModule.Calc.Module"), so a filter that names the module
        // segment must translate too - otherwise it silently matches nothing.
        putNestedKind(m, "Module", "Modules", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041c, 0x043e, 0x0434, 0x0443, 0x043b, 0x044c),
            cp(0x041c, 0x043e, 0x0434, 0x0443, 0x043b, 0x0438));
        // The FORM-CONTENT kinds. A form validation marker's presentation descends into the form
        // item tree, and this tool advertises bilingual tokens at EVERY level, so the kinds
        // FormElementWriter already accepts in a form-member FQN must translate here too.
        // MetadataTypeUtilsTest pins these against FormElementWriter.kindForToken so the two token
        // tables cannot drift apart.
        // Field (ru: pole / polya)
        putNestedKind(m, "Field", "Fields", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041f, 0x043e, 0x043b, 0x0435), cp(0x041f, 0x043e, 0x043b, 0x044f));
        // Button (ru: knopka / knopki)
        putNestedKind(m, "Button", "Buttons", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041a, 0x043d, 0x043e, 0x043f, 0x043a, 0x0430),
            cp(0x041a, 0x043d, 0x043e, 0x043f, 0x043a, 0x0438));
        // Group (ru: gruppa / gruppy)
        putNestedKind(m, "Group", "Groups", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x0413, 0x0440, 0x0443, 0x043f, 0x043f, 0x0430),
            cp(0x0413, 0x0440, 0x0443, 0x043f, 0x043f, 0x044b));
        // Decoration (ru: dekoraciya / dekoracii)
        putNestedKind(m, "Decoration", "Decorations", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x0414, 0x0435, 0x043a, 0x043e, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f),
            cp(0x0414, 0x0435, 0x043a, 0x043e, 0x0440, 0x0430, 0x0446, 0x0438, 0x0438));
        // Table (ru: tablica / tablicy)
        putNestedKind(m, "Table", "Tables", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x0422, 0x0430, 0x0431, 0x043b, 0x0438, 0x0446, 0x0430),
            cp(0x0422, 0x0430, 0x0431, 0x043b, 0x0438, 0x0446, 0x044b));
        // Handler (ru: obrabotchik / obrabotchiki)
        putNestedKind(m, "Handler", "Handlers", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041e, 0x0431, 0x0440, 0x0430, 0x0431, 0x043e, 0x0442, 0x0447, 0x0438, 0x043a),
            cp(0x041e, 0x0431, 0x0440, 0x0430, 0x0431, 0x043e, 0x0442, 0x0447, 0x0438, 0x043a, 0x0438));
        // TabularSection (ru: tablichnaya chast / tablichnye chasti)
        putNestedKind(m, "TabularSection", "TabularSections", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x0422, 0x0430, 0x0431, 0x043b, 0x0438, 0x0447, 0x043d, 0x0430, 0x044f,
                0x0427, 0x0430, 0x0441, 0x0442, 0x044c),
            cp(0x0422, 0x0430, 0x0431, 0x043b, 0x0438, 0x0447, 0x043d, 0x044b, 0x0435,
                0x0427, 0x0430, 0x0441, 0x0442, 0x0438));
        // Dimension (ru: izmerenie / izmereniya)
        putNestedKind(m, "Dimension", "Dimensions", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x0418, 0x0437, 0x043c, 0x0435, 0x0440, 0x0435, 0x043d, 0x0438, 0x0435),
            cp(0x0418, 0x0437, 0x043c, 0x0435, 0x0440, 0x0435, 0x043d, 0x0438, 0x044f));
        // Resource (ru: resurs / resursy)
        putNestedKind(m, "Resource", "Resources", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x0420, 0x0435, 0x0441, 0x0443, 0x0440, 0x0441),
            cp(0x0420, 0x0435, 0x0441, 0x0443, 0x0440, 0x0441, 0x044b));
        // EnumValue (ru: znachenie perechisleniya / znacheniya perechisleniya)
        putNestedKind(m, "EnumValue", "EnumValues", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x0417, 0x043d, 0x0430, 0x0447, 0x0435, 0x043d, 0x0438, 0x0435,
                0x041f, 0x0435, 0x0440, 0x0435, 0x0447, 0x0438, 0x0441, 0x043b, 0x0435, 0x043d, 0x0438, 0x044f),
            cp(0x0417, 0x043d, 0x0430, 0x0447, 0x0435, 0x043d, 0x0438, 0x044f,
                0x041f, 0x0435, 0x0440, 0x0435, 0x0447, 0x0438, 0x0441, 0x043b, 0x0435, 0x043d, 0x0438, 0x044f));
        // Command (ru: komanda / komandy)
        putNestedKind(m, "Command", "Commands", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x0430),
            cp(0x041a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x044b));
        // Template (ru: maket / makety)
        putNestedKind(m, "Template", "Templates", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041c, 0x0430, 0x043a, 0x0435, 0x0442),
            cp(0x041c, 0x0430, 0x043a, 0x0435, 0x0442, 0x044b));
        // Column (ru: kolonka / kolonki)
        putNestedKind(m, "Column", "Columns", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041a, 0x043e, 0x043b, 0x043e, 0x043d, 0x043a, 0x0430),
            cp(0x041a, 0x043e, 0x043b, 0x043e, 0x043d, 0x043a, 0x0438));
        // Recalculation (ru: pereraschet / pereraschety)
        putNestedKind(m, "Recalculation", "Recalculations", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041f, 0x0435, 0x0440, 0x0435, 0x0440, 0x0430, 0x0441, 0x0447, 0x0435, 0x0442),
            cp(0x041f, 0x0435, 0x0440, 0x0435, 0x0440, 0x0430, 0x0441, 0x0447, 0x0435, 0x0442, 0x044b));
        // AccountingFlag (ru: priznak ucheta / priznaki ucheta)
        putNestedKind(m, "AccountingFlag", "AccountingFlags", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041f, 0x0440, 0x0438, 0x0437, 0x043d, 0x0430, 0x043a, 0x0423, 0x0447, 0x0435, 0x0442, 0x0430),
            cp(0x041f, 0x0440, 0x0438, 0x0437, 0x043d, 0x0430, 0x043a, 0x0438, 0x0423, 0x0447, 0x0435, 0x0442, 0x0430));
        // ExtDimensionAccountingFlag (ru: priznak ucheta subkonto / priznaki ucheta subkonto)
        putNestedKind(m, "ExtDimensionAccountingFlag", "ExtDimensionAccountingFlags", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041f, 0x0440, 0x0438, 0x0437, 0x043d, 0x0430, 0x043a, 0x0423, 0x0447, 0x0435, 0x0442, 0x0430,
                0x0421, 0x0443, 0x0431, 0x043a, 0x043e, 0x043d, 0x0442, 0x043e),
            cp(0x041f, 0x0440, 0x0438, 0x0437, 0x043d, 0x0430, 0x043a, 0x0438, 0x0423, 0x0447, 0x0435, 0x0442, 0x0430,
                0x0421, 0x0443, 0x0431, 0x043a, 0x043e, 0x043d, 0x0442, 0x043e));
        // AddressingAttribute (ru: rekvizit adresacii / rekvizity adresacii)
        putNestedKind(m, "AddressingAttribute", "AddressingAttributes", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x0420, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442,
                0x0410, 0x0434, 0x0440, 0x0435, 0x0441, 0x0430, 0x0446, 0x0438, 0x0438),
            cp(0x0420, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442, 0x044b,
                0x0410, 0x0434, 0x0440, 0x0435, 0x0441, 0x0430, 0x0446, 0x0438, 0x0438));
        // URLTemplate (ru token is Cyrillic "Shablon" + ASCII "URL")
        putNestedKind(m, "URLTemplate", "URLTemplates", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x0428, 0x0430, 0x0431, 0x043b, 0x043e, 0x043d) + "URL", //$NON-NLS-1$
            cp(0x0428, 0x0430, 0x0431, 0x043b, 0x043e, 0x043d, 0x044b) + "URL"); //$NON-NLS-1$
        // Method (ru: metod / metody)
        putNestedKind(m, "Method", "Methods", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041c, 0x0435, 0x0442, 0x043e, 0x0434),
            cp(0x041c, 0x0435, 0x0442, 0x043e, 0x0434, 0x044b));
        // Operation (ru: operaciya / operacii)
        putNestedKind(m, "Operation", "Operations", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041e, 0x043f, 0x0435, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f),
            cp(0x041e, 0x043f, 0x0435, 0x0440, 0x0430, 0x0446, 0x0438, 0x0438));
        // Parameter (ru: parametr / parametry)
        putNestedKind(m, "Parameter", "Parameters", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x041f, 0x0430, 0x0440, 0x0430, 0x043c, 0x0435, 0x0442, 0x0440),
            cp(0x041f, 0x0430, 0x0440, 0x0430, 0x043c, 0x0435, 0x0442, 0x0440, 0x044b));
        NESTED_KIND_LOOKUP = Collections.unmodifiableMap(m);
    }

    /**
     * Registers one nested structural kind under all four of its accepted spellings.
     * The canonical pair stored for every spelling is the SINGULAR English/Russian form,
     * because that is how EDT renders a segment in a marker location / object presentation.
     *
     * @param map the map under construction
     * @param englishSingular the canonical English singular token (e.g. {@code "Form"})
     * @param englishPlural the English plural spelling accepted on input
     * @param russianSingular the canonical Russian singular token
     * @param russianPlural the Russian plural spelling accepted on input
     */
    private static void putNestedKind(Map<String, NestedKindInfo> map, String englishSingular,
        String englishPlural, String russianSingular, String russianPlural)
    {
        NestedKindInfo info = new NestedKindInfo(englishSingular, russianSingular);
        map.put(englishSingular.toLowerCase(), info);
        map.put(englishPlural.toLowerCase(), info);
        map.put(russianSingular.toLowerCase(), info);
        map.put(russianPlural.toLowerCase(), info);
    }

    /**
     * Builds a string from Unicode code points, keeping the Russian nested-kind tokens above out
     * of the source as raw Cyrillic (encoding-independent under a non-UTF-8 Tycho build).
     * Delegates to the shared {@link MetadataLanguageUtils#cp}.
     *
     * @param codePoints the BMP code points of the token characters
     * @return the assembled token string
     */
    private static String cp(int... codePoints)
    {
        return MetadataLanguageUtils.cp(codePoints);
    }

    private MetadataTypeUtils()
    {
        // Utility class
    }

    /**
     * Resolves any recognized form of a metadata type name to its canonical English singular form.
     * Supports English singular/plural and Russian singular/plural forms.
     * Case-insensitive.
     *
     * @param typeName type name in any recognized form (e.g. "Catalogs", "Справочник", "document")
     * @return canonical English singular form (e.g. "Catalog"), or {@code null} if not recognized
     */
    public static String toEnglishSingular(String typeName)
    {
        if (typeName == null || typeName.isEmpty())
        {
            return null;
        }
        MetadataTypeInfo info = LOOKUP.get(typeName.toLowerCase());
        return info != null ? info.englishSingular : null;
    }

    /**
     * Checks whether the given string is a recognized metadata type name
     * (English or Russian, singular or plural). Case-insensitive.
     *
     * @param name name to check
     * @return {@code true} if recognized
     */
    public static boolean isMetadataTypeName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        return LOOKUP.containsKey(name.toLowerCase());
    }

    /**
     * Returns the directory name in src/ for the given metadata type name.
     * Accepts any recognized form (English/Russian, singular/plural).
     * Case-insensitive.
     *
     * @param typeName type name in any recognized form
     * @return directory name (e.g. "Catalogs"), or {@code null} if not recognized or type has no directory
     */
    public static String getDirectoryName(String typeName)
    {
        if (typeName == null || typeName.isEmpty())
        {
            return null;
        }
        MetadataTypeInfo info = LOOKUP.get(typeName.toLowerCase());
        return info != null ? info.directoryName : null;
    }

    /**
     * Returns the EMF Configuration reference name for the given metadata type name.
     * Accepts any recognized form.
     *
     * @param typeName type name in any recognized form
     * @return EMF reference name (e.g. "catalogs", "chartsOfAccounts"), or {@code null} if not recognized
     */
    public static String getConfigReferenceName(String typeName)
    {
        if (typeName == null || typeName.isEmpty())
        {
            return null;
        }
        MetadataTypeInfo info = LOOKUP.get(typeName.toLowerCase());
        return info != null ? info.configReferenceName : null;
    }

    /**
     * Resolves the English singular type name from a src/ directory name.
     * Case-sensitive because directory names are specific (e.g. "Catalogs" -&gt; "Catalog").
     *
     * @param directoryName directory name (e.g. "Catalogs", "InformationRegisters")
     * @return English singular type name, or {@code null} if not recognized
     */
    public static String getTypeByDirectoryName(String directoryName)
    {
        if (directoryName == null || directoryName.isEmpty())
        {
            return null;
        }
        MetadataTypeInfo info = DIR_LOOKUP.get(directoryName);
        return info != null ? info.englishSingular : null;
    }

    /**
     * Returns an unmodifiable set of all known English singular metadata type names,
     * in definition order. Useful for displaying "Supported Metadata Types".
     *
     * @return all English singular names
     */
    public static Set<String> getAllEnglishSingularNames()
    {
        return ALL_ENGLISH_SINGULAR;
    }

    /**
     * Normalizes a full FQN string by translating the type part (before the first dot)
     * from any recognized form to the canonical English singular form.
     * The object name part (after the dot) is preserved as-is.
     * <p>
     * Examples:
     * <ul>
     *   <li>"Документ.Встреча" -&gt; "Document.Встреча"</li>
     *   <li>"Catalogs.Products" -&gt; "Catalog.Products"</li>
     *   <li>"Document.SalesOrder" -&gt; "Document.SalesOrder" (no change)</li>
     *   <li>"UnknownType.Name" -&gt; "UnknownType.Name" (no change)</li>
     * </ul>
     *
     * @param fqn fully qualified name with dot separator
     * @return normalized FQN, or original string if type part is not recognized
     */
    public static String normalizeFqn(String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return fqn;
        }

        int dotIdx = fqn.indexOf('.');
        if (dotIdx <= 0)
        {
            return fqn;
        }

        String typePart = fqn.substring(0, dotIdx);
        String rest = fqn.substring(dotIdx); // includes the dot

        String normalized = toEnglishSingular(typePart);
        if (normalized != null && !normalized.equals(typePart))
        {
            return normalized + rest;
        }
        return fqn;
    }

    /**
     * Returns the collection of metadata objects from Configuration for the given type name.
     * Uses EMF reflection to find the collection by its reference name.
     *
     * @param config the Configuration to search in
     * @param typeName type name in any recognized form (English/Russian, singular/plural)
     * @return list of MdObjects, or {@code null} if type is not recognized or collection not found
     */
    @SuppressWarnings("unchecked")
    public static List<? extends MdObject> getObjects(Configuration config, String typeName)
    {
        if (config == null || typeName == null || typeName.isEmpty())
        {
            return null;
        }

        String refName = getConfigReferenceName(typeName);
        if (refName == null)
        {
            return null;
        }

        for (EReference ref : config.eClass().getEAllReferences())
        {
            if (ref.getName().equals(refName))
            {
                Object value = config.eGet(ref);
                if (value instanceof EList)
                {
                    return (List<? extends MdObject>) value;
                }
                break;
            }
        }

        return null;
    }

    /**
     * Finds a specific metadata object by type name and object name.
     * Accepts any recognized form for the type name.
     * Object name comparison is case-insensitive.
     *
     * @param config the Configuration to search in
     * @param typeName type name in any recognized form
     * @param objectName name of the object to find
     * @return the found MdObject, or {@code null} if not found
     */
    public static MdObject findObject(Configuration config, String typeName, String objectName)
    {
        List<? extends MdObject> objects = getObjects(config, typeName);
        if (objects == null || objectName == null)
        {
            return null;
        }

        for (MdObject obj : objects)
        {
            if (objectName.equalsIgnoreCase(obj.getName()))
            {
                return obj;
            }
        }

        return null;
    }

    /**
     * Finds metadata objects with names similar to the given name (case-insensitive substring match).
     *
     * @param config the Configuration to search in
     * @param typeName type name in any recognized form
     * @param name name to search for (substring match)
     * @param maxResults maximum number of results to return
     * @return list of similar object names, may be empty
     */
    public static List<String> findSimilarObjects(Configuration config, String typeName,
                                                   String name, int maxResults)
    {
        List<String> similar = new ArrayList<>();
        List<? extends MdObject> objects = getObjects(config, typeName);
        if (objects == null || name == null)
        {
            return similar;
        }

        String nameLower = name.toLowerCase();
        for (MdObject obj : objects)
        {
            String objName = obj.getName();
            String objNameLower = objName.toLowerCase();
            if (objNameLower.contains(nameLower) || nameLower.contains(objNameLower))
            {
                similar.add(objName);
                if (similar.size() >= maxResults)
                {
                    break;
                }
            }
        }

        return similar;
    }

    /**
     * Resolves full MetadataTypeInfo for a given type name.
     * Accepts any recognized form.
     *
     * @param typeName type name in any recognized form
     * @return MetadataTypeInfo or {@code null} if not recognized
     */
    public static MetadataTypeInfo resolve(String typeName)
    {
        if (typeName == null || typeName.isEmpty())
        {
            return null;
        }
        return LOOKUP.get(typeName.toLowerCase());
    }

    /**
     * Resolves a NESTED structural FQN segment token (English or Russian, singular or plural,
     * any case) to its canonical English/Russian spellings - e.g. {@code "Forms"} and the Russian
     * plural both resolve to the {@code Form} pair.
     * <p>
     * Nested kinds are catalogued separately from {@link MetadataTypeInfo} on purpose: that enum
     * is the catalogue of TOP-LEVEL configuration types and must not gain synthetic entries.
     *
     * @param segment the segment token (may be {@code null})
     * @return the canonical English/Russian pair, or {@code null} if the token is not a known
     *     nested structural kind
     */
    public static NestedKindInfo resolveNestedKind(String segment)
    {
        if (segment == null || segment.isEmpty())
        {
            return null;
        }
        return NESTED_KIND_LOOKUP.get(segment.toLowerCase());
    }

    /**
     * Returns all FQN variants (original, all-English, all-Russian) for a given FQN, lowercased.
     * Useful for case-insensitive matching of markers against user-provided FQNs regardless of
     * the configuration language.
     * <p>
     * <b>Every</b> structural segment is translated, not just the leading type token. In a 1C full
     * name the dot-separated segments alternate: the EVEN indexes (0, 2, 4, ...) are structural
     * tokens - index 0 is the top-level TYPE, the rest are nested KIND tokens - and the ODD indexes
     * are programmatic Names, which are copied verbatim (a name that happens to spell a kind token
     * is NOT translated). A segment that matches neither catalogue is copied verbatim too, so an
     * unknown token never breaks the expansion.
     * <p>
     * Exactly three candidates are produced (deduplicated): the original, the all-English form and
     * the all-Russian form. This is deliberately NOT the full cross product of per-segment
     * languages, which grows exponentially with the FQN depth; a marker location is rendered in ONE
     * language, so the two single-language forms are all that can ever match.
     * <p>
     * Example: {@code "Документ.Встреча"} produces:
     * <ul>
     *   <li>{@code "документ.встреча"} (original, lowercased)</li>
     *   <li>{@code "document.встреча"} (all-English structural segments)</li>
     * </ul>
     * Example: {@code "Document.SalesOrder.Form.ItemForm"} produces:
     * <ul>
     *   <li>{@code "document.salesorder.form.itemform"} (original, lowercased)</li>
     *   <li>{@code "документ.salesorder.форма.itemform"} (all-Russian structural segments)</li>
     * </ul>
     *
     * @param fqn fully qualified name with dot separator
     * @return set of lowercase FQN variants (never empty if input is non-null and non-empty)
     */
    public static Set<String> getAllFqnVariants(String fqn)
    {
        Set<String> variants = new LinkedHashSet<>();
        if (fqn == null || fqn.isEmpty())
        {
            return variants;
        }

        // Always add the original (lowercased)
        variants.add(fqn.toLowerCase());

        int dotIdx = fqn.indexOf('.');
        if (dotIdx <= 0)
        {
            // No separator (or a leading dot): there is no Type.Name shape to translate, so the
            // input is returned untouched - a bare word must never be read as a type token.
            return variants;
        }

        String[] segments = fqn.split("\\.", -1); //$NON-NLS-1$
        variants.add(translateStructuralSegments(segments, true).toLowerCase());
        variants.add(translateStructuralSegments(segments, false).toLowerCase());

        return variants;
    }

    /**
     * Rebuilds a split FQN with every STRUCTURAL segment rendered in one language, copying the
     * programmatic Names (the odd indexes) untouched.
     *
     * @param segments the dot-split FQN segments
     * @param toEnglish {@code true} to render the structural segments in English, {@code false}
     *     to render them in Russian
     * @return the rebuilt FQN (never {@code null})
     */
    private static String translateStructuralSegments(String[] segments, boolean toEnglish)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++)
        {
            if (i > 0)
            {
                sb.append('.');
            }
            // Even index = structural token (0 = top-level type, 2/4/... = nested kind);
            // odd index = programmatic Name, which is never translated.
            sb.append(i % 2 == 0
                ? translateStructuralSegment(segments[i], i == 0, toEnglish)
                : segments[i]);
        }
        return sb.toString();
    }

    /**
     * Translates a single structural segment to one language, returning it unchanged when the
     * token is not in the matching catalogue.
     *
     * @param segment the structural segment token
     * @param topLevel {@code true} for the leading TYPE segment (resolved through
     *     {@link MetadataTypeInfo}), {@code false} for a nested KIND segment (resolved through
     *     {@link #resolveNestedKind(String)})
     * @param toEnglish {@code true} for the English spelling, {@code false} for the Russian one
     * @return the translated segment, or the input when it is not recognized
     */
    private static String translateStructuralSegment(String segment, boolean topLevel, boolean toEnglish)
    {
        if (topLevel)
        {
            MetadataTypeInfo typeInfo = resolve(segment);
            if (typeInfo == null)
            {
                return segment;
            }
            if (toEnglish)
            {
                return typeInfo.getEnglishSingular();
            }
            String[] russianNames = typeInfo.getRussianNames();
            return russianNames.length > 0 ? russianNames[0] : segment;
        }

        NestedKindInfo kindInfo = resolveNestedKind(segment);
        if (kindInfo == null)
        {
            return segment;
        }
        return toEnglish ? kindInfo.getEnglish() : kindInfo.getRussian();
    }
}
