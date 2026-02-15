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

        XDTO_PACKAGE("XDTOPackage", "XDTOPackages", "xdtoPackages", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
}
