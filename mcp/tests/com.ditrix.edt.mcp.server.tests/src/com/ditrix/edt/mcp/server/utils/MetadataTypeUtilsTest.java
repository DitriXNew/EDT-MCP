/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import java.util.function.Predicate;
import org.junit.Test;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;

import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils.MetadataTypeInfo;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;

/**
 * Tests for {@link MetadataTypeUtils}.
 * Verifies metadata type name resolution for English and Russian forms.
 */
public class MetadataTypeUtilsTest
{
    // ========== toEnglishSingular ==========

    @Test
    public void testEnglishSingular()
    {
        assertEquals("Catalog", MetadataTypeUtils.toEnglishSingular("Catalog"));
        assertEquals("Document", MetadataTypeUtils.toEnglishSingular("Document"));
        assertEquals("CommonModule", MetadataTypeUtils.toEnglishSingular("CommonModule"));
        assertEquals("InformationRegister", MetadataTypeUtils.toEnglishSingular("InformationRegister"));
        assertEquals("AccumulationRegister", MetadataTypeUtils.toEnglishSingular("AccumulationRegister"));
        assertEquals("Enum", MetadataTypeUtils.toEnglishSingular("Enum"));
        assertEquals("Report", MetadataTypeUtils.toEnglishSingular("Report"));
        assertEquals("DataProcessor", MetadataTypeUtils.toEnglishSingular("DataProcessor"));
        assertEquals("ExchangePlan", MetadataTypeUtils.toEnglishSingular("ExchangePlan"));
        assertEquals("BusinessProcess", MetadataTypeUtils.toEnglishSingular("BusinessProcess"));
        assertEquals("Task", MetadataTypeUtils.toEnglishSingular("Task"));
        assertEquals("Constant", MetadataTypeUtils.toEnglishSingular("Constant"));
        assertEquals("HTTPService", MetadataTypeUtils.toEnglishSingular("HTTPService"));
        assertEquals("WebService", MetadataTypeUtils.toEnglishSingular("WebService"));
    }

    @Test
    public void testEnglishPlural()
    {
        assertEquals("Catalog", MetadataTypeUtils.toEnglishSingular("Catalogs"));
        assertEquals("Document", MetadataTypeUtils.toEnglishSingular("Documents"));
        assertEquals("CommonModule", MetadataTypeUtils.toEnglishSingular("CommonModules"));
        assertEquals("InformationRegister", MetadataTypeUtils.toEnglishSingular("InformationRegisters"));
        assertEquals("BusinessProcess", MetadataTypeUtils.toEnglishSingular("BusinessProcesses"));
        assertEquals("ChartOfCharacteristicTypes", MetadataTypeUtils.toEnglishSingular("ChartsOfCharacteristicTypes"));
        assertEquals("ChartOfAccounts", MetadataTypeUtils.toEnglishSingular("ChartsOfAccounts"));
        assertEquals("FilterCriterion", MetadataTypeUtils.toEnglishSingular("FilterCriteria"));
    }

    @Test
    public void testRussianSingular()
    {
        assertEquals("Catalog", MetadataTypeUtils.toEnglishSingular("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A")); // Справочник
        assertEquals("Document", MetadataTypeUtils.toEnglishSingular("\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442")); // Документ
        assertEquals("CommonModule", MetadataTypeUtils.toEnglishSingular("\u041E\u0431\u0449\u0438\u0439\u041C\u043E\u0434\u0443\u043B\u044C")); // ОбщийМодуль
        assertEquals("InformationRegister", MetadataTypeUtils.toEnglishSingular("\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0421\u0432\u0435\u0434\u0435\u043D\u0438\u0439")); // РегистрСведений
        assertEquals("AccumulationRegister", MetadataTypeUtils.toEnglishSingular("\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u041D\u0430\u043A\u043E\u043F\u043B\u0435\u043D\u0438\u044F")); // РегистрНакопления
        assertEquals("Enum", MetadataTypeUtils.toEnglishSingular("\u041F\u0435\u0440\u0435\u0447\u0438\u0441\u043B\u0435\u043D\u0438\u0435")); // Перечисление
        assertEquals("Report", MetadataTypeUtils.toEnglishSingular("\u041E\u0442\u0447\u0435\u0442")); // Отчет
        assertEquals("DataProcessor", MetadataTypeUtils.toEnglishSingular("\u041E\u0431\u0440\u0430\u0431\u043E\u0442\u043A\u0430")); // Обработка
        assertEquals("ExchangePlan", MetadataTypeUtils.toEnglishSingular("\u041F\u043B\u0430\u043D\u041E\u0431\u043C\u0435\u043D\u0430")); // ПланОбмена
        assertEquals("BusinessProcess", MetadataTypeUtils.toEnglishSingular("\u0411\u0438\u0437\u043D\u0435\u0441\u041F\u0440\u043E\u0446\u0435\u0441\u0441")); // БизнесПроцесс
        assertEquals("Task", MetadataTypeUtils.toEnglishSingular("\u0417\u0430\u0434\u0430\u0447\u0430")); // Задача
        assertEquals("Role", MetadataTypeUtils.toEnglishSingular("\u0420\u043E\u043B\u044C")); // Роль
        assertEquals("Subsystem", MetadataTypeUtils.toEnglishSingular("\u041F\u043E\u0434\u0441\u0438\u0441\u0442\u0435\u043C\u0430")); // Подсистема
        assertEquals("CommonCommand", MetadataTypeUtils.toEnglishSingular("\u041E\u0431\u0449\u0430\u044F\u041A\u043E\u043C\u0430\u043D\u0434\u0430")); // ОбщаяКоманда
        assertEquals("CommonForm", MetadataTypeUtils.toEnglishSingular("\u041E\u0431\u0449\u0430\u044F\u0424\u043E\u0440\u043C\u0430")); // ОбщаяФорма
        assertEquals("WebService", MetadataTypeUtils.toEnglishSingular("\u0412\u0435\u0431\u0421\u0435\u0440\u0432\u0438\u0441")); // ВебСервис
        assertEquals("HTTPService", MetadataTypeUtils.toEnglishSingular("HTTP\u0421\u0435\u0440\u0432\u0438\u0441")); // HTTPСервис
        assertEquals("Constant", MetadataTypeUtils.toEnglishSingular("\u041A\u043E\u043D\u0441\u0442\u0430\u043D\u0442\u0430")); // Константа
        assertEquals("ChartOfCharacteristicTypes", MetadataTypeUtils.toEnglishSingular("\u041F\u043B\u0430\u043D\u0412\u0438\u0434\u043E\u0432\u0425\u0430\u0440\u0430\u043A\u0442\u0435\u0440\u0438\u0441\u0442\u0438\u043A")); // ПланВидовХарактеристик
        assertEquals("ChartOfAccounts", MetadataTypeUtils.toEnglishSingular("\u041F\u043B\u0430\u043D\u0421\u0447\u0435\u0442\u043E\u0432")); // ПланСчетов
        assertEquals("AccountingRegister", MetadataTypeUtils.toEnglishSingular("\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0411\u0443\u0445\u0433\u0430\u043B\u0442\u0435\u0440\u0438\u0438")); // РегистрБухгалтерии
        assertEquals("CalculationRegister", MetadataTypeUtils.toEnglishSingular("\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0420\u0430\u0441\u0447\u0435\u0442\u0430")); // РегистрРасчета
        assertEquals("EventSubscription", MetadataTypeUtils.toEnglishSingular("\u041F\u043E\u0434\u043F\u0438\u0441\u043A\u0430\u041D\u0430\u0421\u043E\u0431\u044B\u0442\u0438\u0435")); // ПодпискаНаСобытие
        assertEquals("ScheduledJob", MetadataTypeUtils.toEnglishSingular("\u0420\u0435\u0433\u043B\u0430\u043C\u0435\u043D\u0442\u043D\u043E\u0435\u0417\u0430\u0434\u0430\u043D\u0438\u0435")); // РегламентноеЗадание
    }

    @Test
    public void testRussianPlural()
    {
        assertEquals("Catalog", MetadataTypeUtils.toEnglishSingular("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A\u0438")); // Справочники
        assertEquals("Document", MetadataTypeUtils.toEnglishSingular("\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u044B")); // Документы
        assertEquals("InformationRegister", MetadataTypeUtils.toEnglishSingular("\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u044B\u0421\u0432\u0435\u0434\u0435\u043D\u0438\u0439")); // РегистрыСведений
        assertEquals("AccumulationRegister", MetadataTypeUtils.toEnglishSingular("\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u044B\u041D\u0430\u043A\u043E\u043F\u043B\u0435\u043D\u0438\u044F")); // РегистрыНакопления
        assertEquals("Report", MetadataTypeUtils.toEnglishSingular("\u041E\u0442\u0447\u0435\u0442\u044B")); // Отчеты
        assertEquals("DataProcessor", MetadataTypeUtils.toEnglishSingular("\u041E\u0431\u0440\u0430\u0431\u043E\u0442\u043A\u0438")); // Обработки
        assertEquals("ExchangePlan", MetadataTypeUtils.toEnglishSingular("\u041F\u043B\u0430\u043D\u044B\u041E\u0431\u043C\u0435\u043D\u0430")); // ПланыОбмена
        assertEquals("BusinessProcess", MetadataTypeUtils.toEnglishSingular("\u0411\u0438\u0437\u043D\u0435\u0441\u041F\u0440\u043E\u0446\u0435\u0441\u0441\u044B")); // БизнесПроцессы
        assertEquals("Task", MetadataTypeUtils.toEnglishSingular("\u0417\u0430\u0434\u0430\u0447\u0438")); // Задачи
        assertEquals("Constant", MetadataTypeUtils.toEnglishSingular("\u041A\u043E\u043D\u0441\u0442\u0430\u043D\u0442\u044B")); // Константы
        assertEquals("Enum", MetadataTypeUtils.toEnglishSingular("\u041F\u0435\u0440\u0435\u0447\u0438\u0441\u043B\u0435\u043D\u0438\u044F")); // Перечисления
    }

    @Test
    public void testCaseInsensitivity()
    {
        assertEquals("Catalog", MetadataTypeUtils.toEnglishSingular("catalog"));
        assertEquals("Catalog", MetadataTypeUtils.toEnglishSingular("CATALOG"));
        assertEquals("Catalog", MetadataTypeUtils.toEnglishSingular("CaTaLoG"));
        assertEquals("Document", MetadataTypeUtils.toEnglishSingular("document"));
        assertEquals("Document", MetadataTypeUtils.toEnglishSingular("DOCUMENTS"));
        // Russian case insensitivity
        assertEquals("Catalog", MetadataTypeUtils.toEnglishSingular("\u0441\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A")); // справочник (lowercase)
        assertEquals("Catalog", MetadataTypeUtils.toEnglishSingular("\u0421\u041F\u0420\u0410\u0412\u041E\u0427\u041D\u0418\u041A")); // СПРАВОЧНИК (uppercase)
    }

    @Test
    public void testUnrecognizedReturnsNull()
    {
        assertNull(MetadataTypeUtils.toEnglishSingular("UnknownType"));
        assertNull(MetadataTypeUtils.toEnglishSingular(""));
        assertNull(MetadataTypeUtils.toEnglishSingular(null));
        assertNull(MetadataTypeUtils.toEnglishSingular("Products"));
    }

    // ========== isMetadataTypeName ==========

    @Test
    public void testIsMetadataTypeName()
    {
        assertTrue(MetadataTypeUtils.isMetadataTypeName("Catalog"));
        assertTrue(MetadataTypeUtils.isMetadataTypeName("Catalogs"));
        assertTrue(MetadataTypeUtils.isMetadataTypeName("Document"));
        assertTrue(MetadataTypeUtils.isMetadataTypeName("catalog")); // case-insensitive
        assertTrue(MetadataTypeUtils.isMetadataTypeName("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A")); // Справочник
        assertTrue(MetadataTypeUtils.isMetadataTypeName("\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442")); // Документ
        assertTrue(MetadataTypeUtils.isMetadataTypeName("\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0421\u0432\u0435\u0434\u0435\u043D\u0438\u0439")); // РегистрСведений
    }

    @Test
    public void testIsNotMetadataTypeName()
    {
        assertFalse(MetadataTypeUtils.isMetadataTypeName("Products"));
        assertFalse(MetadataTypeUtils.isMetadataTypeName("SomeRandomName"));
        assertFalse(MetadataTypeUtils.isMetadataTypeName(""));
        assertFalse(MetadataTypeUtils.isMetadataTypeName(null));
    }

    // ========== getDirectoryName ==========

    @Test
    public void testGetDirectoryName()
    {
        assertEquals("Catalogs", MetadataTypeUtils.getDirectoryName("Catalog"));
        assertEquals("Documents", MetadataTypeUtils.getDirectoryName("Document"));
        assertEquals("CommonModules", MetadataTypeUtils.getDirectoryName("CommonModule"));
        assertEquals("InformationRegisters", MetadataTypeUtils.getDirectoryName("InformationRegister"));
        assertEquals("AccumulationRegisters", MetadataTypeUtils.getDirectoryName("AccumulationRegister"));
        assertEquals("Enums", MetadataTypeUtils.getDirectoryName("Enum"));
        assertEquals("Reports", MetadataTypeUtils.getDirectoryName("Report"));
        assertEquals("DataProcessors", MetadataTypeUtils.getDirectoryName("DataProcessor"));
        assertEquals("ExchangePlans", MetadataTypeUtils.getDirectoryName("ExchangePlan"));
        assertEquals("BusinessProcesses", MetadataTypeUtils.getDirectoryName("BusinessProcess"));
        assertEquals("Tasks", MetadataTypeUtils.getDirectoryName("Task"));
        assertEquals("Constants", MetadataTypeUtils.getDirectoryName("Constant"));
        assertEquals("HTTPServices", MetadataTypeUtils.getDirectoryName("HTTPService"));
        assertEquals("ChartsOfCharacteristicTypes", MetadataTypeUtils.getDirectoryName("ChartOfCharacteristicTypes"));
        assertEquals("ChartsOfAccounts", MetadataTypeUtils.getDirectoryName("ChartOfAccounts"));
        assertEquals("FilterCriteria", MetadataTypeUtils.getDirectoryName("FilterCriterion"));
    }

    @Test
    public void testGetDirectoryNameFromRussian()
    {
        assertEquals("Catalogs", MetadataTypeUtils.getDirectoryName("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A")); // Справочник
        assertEquals("Documents", MetadataTypeUtils.getDirectoryName("\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442")); // Документ
        assertEquals("InformationRegisters", MetadataTypeUtils.getDirectoryName("\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0421\u0432\u0435\u0434\u0435\u043D\u0438\u0439")); // РегистрСведений
    }

    @Test
    public void testGetDirectoryNameNull()
    {
        assertNull(MetadataTypeUtils.getDirectoryName("UnknownType"));
        assertNull(MetadataTypeUtils.getDirectoryName(null));
        // Types without directories return null
        assertNull(MetadataTypeUtils.getDirectoryName("Role"));
        assertNull(MetadataTypeUtils.getDirectoryName("Subsystem"));
    }

    // ========== getConfigReferenceName ==========

    @Test
    public void testGetConfigReferenceName()
    {
        assertEquals("catalogs", MetadataTypeUtils.getConfigReferenceName("Catalog"));
        assertEquals("documents", MetadataTypeUtils.getConfigReferenceName("Document"));
        assertEquals("commonModules", MetadataTypeUtils.getConfigReferenceName("CommonModule"));
        assertEquals("businessProcesses", MetadataTypeUtils.getConfigReferenceName("BusinessProcess"));
        assertEquals("chartsOfCharacteristicTypes", MetadataTypeUtils.getConfigReferenceName("ChartOfCharacteristicTypes"));
        assertEquals("chartsOfAccounts", MetadataTypeUtils.getConfigReferenceName("ChartOfAccounts"));
        assertEquals("filterCriteria", MetadataTypeUtils.getConfigReferenceName("FilterCriterion"));
        assertEquals("httpServices", MetadataTypeUtils.getConfigReferenceName("HTTPService"));
        // The Configuration feature is "xDTOPackages" (capital DTO) - a casing fix; the old
        // "xdtoPackages" made create_metadata fail to resolve the collection.
        assertEquals("xDTOPackages", MetadataTypeUtils.getConfigReferenceName("XDTOPackage"));
    }

    @Test
    public void testGetConfigReferenceNameFromRussian()
    {
        assertEquals("catalogs", MetadataTypeUtils.getConfigReferenceName("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A")); // Справочник
        assertEquals("documents", MetadataTypeUtils.getConfigReferenceName("\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442")); // Документ
    }

    // ========== getTypeByDirectoryName ==========

    @Test
    public void testGetTypeByDirectoryName()
    {
        assertEquals("Catalog", MetadataTypeUtils.getTypeByDirectoryName("Catalogs"));
        assertEquals("Document", MetadataTypeUtils.getTypeByDirectoryName("Documents"));
        assertEquals("CommonModule", MetadataTypeUtils.getTypeByDirectoryName("CommonModules"));
        assertEquals("InformationRegister", MetadataTypeUtils.getTypeByDirectoryName("InformationRegisters"));
        assertEquals("BusinessProcess", MetadataTypeUtils.getTypeByDirectoryName("BusinessProcesses"));
        assertEquals("ChartOfAccounts", MetadataTypeUtils.getTypeByDirectoryName("ChartsOfAccounts"));
        assertEquals("ChartOfCharacteristicTypes", MetadataTypeUtils.getTypeByDirectoryName("ChartsOfCharacteristicTypes"));
        assertEquals("FilterCriterion", MetadataTypeUtils.getTypeByDirectoryName("FilterCriteria"));
        assertEquals("HTTPService", MetadataTypeUtils.getTypeByDirectoryName("HTTPServices"));
    }

    @Test
    public void testGetTypeByDirectoryNameUnknown()
    {
        assertNull(MetadataTypeUtils.getTypeByDirectoryName("UnknownDir"));
        assertNull(MetadataTypeUtils.getTypeByDirectoryName(null));
        assertNull(MetadataTypeUtils.getTypeByDirectoryName(""));
    }

    // ========== normalizeFqn ==========

    @Test
    public void testNormalizeFqnRussianType()
    {
        assertEquals("Document.\u0412\u0441\u0442\u0440\u0435\u0447\u0430",
            MetadataTypeUtils.normalizeFqn("\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u0412\u0441\u0442\u0440\u0435\u0447\u0430")); // Документ.Встреча
        assertEquals("Catalog.\u0423\u0441\u043B\u0443\u0433\u0438SLA",
            MetadataTypeUtils.normalizeFqn("\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u0423\u0441\u043B\u0443\u0433\u0438SLA")); // Справочник.УслугиSLA
        assertEquals("InformationRegister.\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442\u044BSLA",
            MetadataTypeUtils.normalizeFqn("\u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0421\u0432\u0435\u0434\u0435\u043D\u0438\u0439.\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442\u044BSLA")); // РегистрСведений.РеквизитыSLA
        assertEquals("Enum.TelegramВидКлавиатуры",
            MetadataTypeUtils.normalizeFqn("\u041F\u0435\u0440\u0435\u0447\u0438\u0441\u043B\u0435\u043D\u0438\u0435.Telegram\u0412\u0438\u0434\u041A\u043B\u0430\u0432\u0438\u0430\u0442\u0443\u0440\u044B")); // Перечисление.TelegramВидКлавиатуры
    }

    @Test
    public void testNormalizeFqnEnglishType()
    {
        // Already English — should pass through unchanged
        assertEquals("Document.SalesOrder", MetadataTypeUtils.normalizeFqn("Document.SalesOrder"));
        assertEquals("Catalog.Products", MetadataTypeUtils.normalizeFqn("Catalog.Products"));
    }

    @Test
    public void testNormalizeFqnPluralType()
    {
        // Plural English → normalized to singular
        assertEquals("Catalog.Products", MetadataTypeUtils.normalizeFqn("Catalogs.Products"));
        assertEquals("Document.SalesOrder", MetadataTypeUtils.normalizeFqn("Documents.SalesOrder"));
    }

    @Test
    public void testNormalizeFqnUnrecognized()
    {
        // Unrecognized type — passes through unchanged
        assertEquals("UnknownType.Name", MetadataTypeUtils.normalizeFqn("UnknownType.Name"));
        assertEquals("MyModule.Method", MetadataTypeUtils.normalizeFqn("MyModule.Method"));
    }

    @Test
    public void testNormalizeFqnNoDot()
    {
        // No dot — passes through unchanged
        assertEquals("MethodName", MetadataTypeUtils.normalizeFqn("MethodName"));
    }

    @Test
    public void testNormalizeFqnNullEmpty()
    {
        assertNull(MetadataTypeUtils.normalizeFqn(null));
        assertEquals("", MetadataTypeUtils.normalizeFqn(""));
    }

    // ========== getAllEnglishSingularNames ==========

    @Test
    public void testGetAllEnglishSingularNames()
    {
        Set<String> names = MetadataTypeUtils.getAllEnglishSingularNames();
        assertNotNull(names);
        assertTrue(names.contains("Catalog"));
        assertTrue(names.contains("Document"));
        assertTrue(names.contains("CommonModule"));
        assertTrue(names.contains("ChartOfCharacteristicTypes"));
        assertTrue(names.contains("FilterCriterion"));
        assertTrue(names.size() >= 40);
    }

    // ========== resolve ==========

    @Test
    public void testResolve()
    {
        MetadataTypeInfo info = MetadataTypeUtils.resolve("Catalog");
        assertNotNull(info);
        assertEquals("Catalog", info.getEnglishSingular());
        assertEquals("Catalogs", info.getEnglishPlural());
        assertEquals("catalogs", info.getConfigReferenceName());
        assertEquals("Catalogs", info.getDirectoryName());
    }

    @Test
    public void testResolveFromRussian()
    {
        MetadataTypeInfo info = MetadataTypeUtils.resolve("\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442"); // Документ
        assertNotNull(info);
        assertEquals("Document", info.getEnglishSingular());
    }

    @Test
    public void testResolveUnknown()
    {
        assertNull(MetadataTypeUtils.resolve("UnknownType"));
        assertNull(MetadataTypeUtils.resolve(null));
    }

    // ========== Round-trip consistency ==========

    @Test
    public void testDirectoryRoundTrip()
    {
        // For every type that has a directory, verify: type -> dir -> type
        for (MetadataTypeInfo info : MetadataTypeInfo.values())
        {
            if (info.getDirectoryName() != null)
            {
                String dir = MetadataTypeUtils.getDirectoryName(info.getEnglishSingular());
                assertNotNull("getDirectoryName returned null for " + info.getEnglishSingular(), dir);
                assertEquals(info.getDirectoryName(), dir);

                String type = MetadataTypeUtils.getTypeByDirectoryName(dir);
                assertNotNull("getTypeByDirectoryName returned null for " + dir, type);
                assertEquals(info.getEnglishSingular(), type);
            }
        }
    }

    @Test
    public void testAllTypesHaveConfigReferenceNames()
    {
        for (MetadataTypeInfo info : MetadataTypeInfo.values())
        {
            assertNotNull("configReferenceName is null for " + info.getEnglishSingular(),
                info.getConfigReferenceName());
            assertFalse("configReferenceName is empty for " + info.getEnglishSingular(),
                info.getConfigReferenceName().isEmpty());
        }
    }

    @Test
    public void testAllEnglishNamesResolvable()
    {
        for (MetadataTypeInfo info : MetadataTypeInfo.values())
        {
            // Singular
            assertEquals(info.getEnglishSingular(),
                MetadataTypeUtils.toEnglishSingular(info.getEnglishSingular()));
            // Plural
            assertEquals(info.getEnglishSingular(),
                MetadataTypeUtils.toEnglishSingular(info.getEnglishPlural()));
        }
    }

    @Test
    public void testAllRussianNamesResolvable()
    {
        for (MetadataTypeInfo info : MetadataTypeInfo.values())
        {
            for (String ru : info.getRussianNames())
            {
                assertEquals("Russian name '" + ru + "' should resolve to " + info.getEnglishSingular(),
                    info.getEnglishSingular(), MetadataTypeUtils.toEnglishSingular(ru));
            }
        }
    }

    // ========== getAllFqnVariants ==========

    @Test
    public void testGetAllFqnVariantsRussianInput()
    {
        // Russian FQN should produce original (lowercased) + English variant
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants(
            "\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u0420\u0430\u0441\u0445\u043E\u0434\u044B"); // Документ.Расходы
        assertTrue("Should contain original lowercased",
            variants.contains("\u0434\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u0440\u0430\u0441\u0445\u043E\u0434\u044B")); // документ.расходы
        assertTrue("Should contain English variant",
            variants.contains("document.\u0440\u0430\u0441\u0445\u043E\u0434\u044B")); // document.расходы
    }

    @Test
    public void testGetAllFqnVariantsEnglishInput()
    {
        // English FQN should produce original (lowercased) + Russian variant
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants("Document.SalesOrder");
        assertTrue("Should contain original lowercased",
            variants.contains("document.salesorder"));
        assertTrue("Should contain Russian variant",
            variants.contains("\u0434\u043E\u043A\u0443\u043C\u0435\u043D\u0442.salesorder")); // документ.salesorder
    }

    @Test
    public void testGetAllFqnVariantsPluralInput()
    {
        // Plural English should also work
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants("Catalogs.Products");
        assertTrue("Should contain original lowercased",
            variants.contains("catalogs.products"));
        assertTrue("Should contain English singular variant",
            variants.contains("catalog.products"));
        assertTrue("Should contain Russian variant",
            variants.contains("\u0441\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.products")); // справочник.products
    }

    @Test
    public void testGetAllFqnVariantsMixedCase()
    {
        // Mixed case input should be lowercased
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants("DOCUMENT.SalesOrder");
        assertTrue(variants.contains("document.salesorder"));
        assertTrue(variants.contains("\u0434\u043E\u043A\u0443\u043C\u0435\u043D\u0442.salesorder")); // документ.salesorder
    }

    @Test
    public void testGetAllFqnVariantsUnknownType()
    {
        // Unknown type — should return only original lowercased
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants("UnknownType.Name");
        assertEquals(1, variants.size());
        assertTrue(variants.contains("unknowntype.name"));
    }

    @Test
    public void testGetAllFqnVariantsNoDot()
    {
        // No dot — single variant
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants("MethodName");
        assertEquals(1, variants.size());
        assertTrue(variants.contains("methodname"));
    }

    @Test
    public void testGetAllFqnVariantsNullEmpty()
    {
        assertTrue(MetadataTypeUtils.getAllFqnVariants(null).isEmpty());
        assertTrue(MetadataTypeUtils.getAllFqnVariants("").isEmpty());
    }

    @Test
    public void testGetAllFqnVariantsNoDuplicates()
    {
        // English singular input: original == English variant, so set should deduplicate
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants("Document.Test");
        // Should have exactly 2: "document.test" and "документ.test"
        assertEquals(2, variants.size());
    }

    @Test
    public void testGetAllFqnVariantsAllLowercase()
    {
        // All returned variants must be lowercase
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants("Catalog.MyObject");
        for (String v : variants)
        {
            assertEquals("Variant should be lowercase: " + v, v.toLowerCase(), v);
        }
    }

    // ========== getAllFqnVariants: NESTED FQNs (issue #312) ==========

    /** Russian tokens are written as code points so this source stays pure ASCII. */
    private static final String RU_DOCUMENT_LOWER = "\u0434\u043E\u043A\u0443\u043C\u0435\u043D\u0442"; // документ
    private static final String RU_DOCUMENT = "\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442"; // Документ
    private static final String RU_CATALOG_LOWER = "\u0441\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A"; // справочник
    private static final String RU_FORM = "\u0424\u043E\u0440\u043C\u0430"; // Форма
    private static final String RU_FORMS = "\u0424\u043E\u0440\u043C\u044B"; // Формы
    private static final String RU_FORM_LOWER = "\u0444\u043E\u0440\u043C\u0430"; // форма
    private static final String RU_TABULAR_SECTION_LOWER =
        "\u0442\u0430\u0431\u043B\u0438\u0447\u043D\u0430\u044F\u0447\u0430\u0441\u0442\u044C"; // табличнаячасть
    private static final String RU_ATTRIBUTE_LOWER = "\u0440\u0435\u043A\u0432\u0438\u0437\u0438\u0442"; // реквизит
    private static final String RU_ATTRIBUTE = "\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442"; // Attribute (ru)
    private static final String RU_MODULE_LOWER = "\u043C\u043E\u0434\u0443\u043B\u044C"; // Module (ru, lowercase)

    @Test
    public void testGetAllFqnVariantsNestedEnglishInputProducesFullRussianVariant()
    {
        // THE regression (issue #312): an English NESTED FQN must produce a variant whose EVERY
        // structural segment is Russian. Translating only the leading type token yields
        // "документ.meeting.form.itemform", which never matches the Russian marker location
        // "Документ.Meeting.Форма.ItemForm" -> the filter silently drops every finding and the
        // tool reports a clean project.
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants("Document.Meeting.Form.ItemForm");
        assertTrue("Should contain original lowercased",
            variants.contains("document.meeting.form.itemform"));
        assertTrue("Should translate BOTH structural segments to Russian",
            variants.contains(RU_DOCUMENT_LOWER + ".meeting." + RU_FORM_LOWER + ".itemform"));
        assertFalse("The half-translated form must not be produced",
            variants.contains(RU_DOCUMENT_LOWER + ".meeting.form.itemform"));
    }

    @Test
    public void testGetAllFqnVariantsNestedRussianInputProducesFullEnglishVariant()
    {
        // Документ.Встреча.Форма.ФормаЭлемента
        String meeting = "\u0412\u0441\u0442\u0440\u0435\u0447\u0430"; // Встреча
        String itemForm = "\u0424\u043E\u0440\u043C\u0430\u042D\u043B\u0435\u043C\u0435\u043D\u0442\u0430"; // ФормаЭлемента
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants(
            RU_DOCUMENT + "." + meeting + "." + RU_FORM + "." + itemForm);
        assertTrue("Should translate BOTH structural segments to English",
            variants.contains("document." + meeting.toLowerCase() + ".form." + itemForm.toLowerCase()));
    }

    @Test
    public void testGetAllFqnVariantsProgrammaticNamesAreNeverTranslated()
    {
        // An object AND its form both literally named Forma (the Russian word for "Form"): the
        // NAME segments (odd indexes) must survive untouched while only the structural segments
        // (even indexes) translate.
        Set<String> variants =
            MetadataTypeUtils.getAllFqnVariants("Catalog." + RU_FORM + ".Form." + RU_FORM);
        assertTrue("Names must stay as typed in the all-English variant",
            variants.contains("catalog." + RU_FORM_LOWER + ".form." + RU_FORM_LOWER));
        assertFalse("A NAME that spells a kind token must NOT be translated",
            variants.contains("catalog.form.form.form"));
        assertTrue("Structural segments must still translate to Russian",
            variants.contains(RU_CATALOG_LOWER + "." + RU_FORM_LOWER + "." + RU_FORM_LOWER
                + "." + RU_FORM_LOWER));
    }

    @Test
    public void testGetAllFqnVariantsThreeLevelNestedFqn()
    {
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants(
            "Catalog.Products.TabularSection.Goods.Attribute.Price");
        assertTrue("Should contain original lowercased",
            variants.contains("catalog.products.tabularsection.goods.attribute.price"));
        assertTrue("All three structural segments must translate to Russian",
            variants.contains(RU_CATALOG_LOWER + ".products." + RU_TABULAR_SECTION_LOWER
                + ".goods." + RU_ATTRIBUTE_LOWER + ".price"));
    }

    @Test
    public void testGetAllFqnVariantsNestedPluralKindToken()
    {
        // A plural nested kind token is accepted and canonicalized to the singular.
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants("Catalog.Products.Forms.ItemForm");
        assertTrue("Plural kind token must canonicalize to the English singular",
            variants.contains("catalog.products.form.itemform"));
        assertTrue("Plural kind token must canonicalize to the Russian singular",
            variants.contains(RU_CATALOG_LOWER + ".products." + RU_FORM_LOWER + ".itemform"));
    }

    @Test
    public void testGetAllFqnVariantsUnknownNestedSegmentIsKept()
    {
        // An unrecognized structural segment is copied verbatim - it must never break the method
        // nor swallow the other segments' translation.
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants("Catalog.Products.Widget.Foo");
        assertTrue(variants.contains("catalog.products.widget.foo"));
        assertTrue("The known type token must still translate",
            variants.contains(RU_CATALOG_LOWER + ".products.widget.foo"));
    }

    @Test
    public void testALooseFragmentThatStartsOnANestedKindStillTranslates()
    {
        // `objects` entries are documented FRAGMENTS and may begin in the MIDDLE of a location.
        // A fragment starting on a nested KIND was only ever looked up in the TYPE catalogue, so it
        // stayed Russian, matched no English location, and - because a loose entry reports no miss -
        // handed the caller an empty report: the #312 symptom in the loose mode.
        Set<String> form = MetadataTypeUtils.getAllFqnVariants(RU_FORM + ".ItemForm");
        assertTrue("the fragment as typed must stay", form.contains(RU_FORM_LOWER + ".itemform"));
        assertTrue("a fragment starting on a nested kind must reach the English location",
            form.contains("form.itemform"));

        Set<String> attribute = MetadataTypeUtils.getAllFqnVariants(RU_ATTRIBUTE + ".Weight");
        assertTrue(attribute.contains("attribute.weight"));

        // Symmetrical: an English fragment must reach a Russian-rendered location.
        Set<String> english = MetadataTypeUtils.getAllFqnVariants("Form.ItemForm");
        assertTrue(english.contains(RU_FORM_LOWER + ".itemform"));
    }

    @Test
    public void testTheNestedKindFallbackDoesNotTranslateNameSegments()
    {
        // The boundary of the fallback above: it changes only WHICH catalogue the LEADING segment is
        // looked up in. The parity is untouched, so an odd-index segment is still a programmatic
        // Name copied verbatim - a fragment that begins on a NAME is deliberately NOT translated,
        // because without segment alignment a name cannot be told from a kind.
        Set<String> startsOnName = MetadataTypeUtils.getAllFqnVariants("Calc.Module");
        assertTrue("the fragment as typed must stay", startsOnName.contains("calc.module"));
        for (String variant : startsOnName)
        {
            assertFalse("a kind token at a NAME position must never be translated: " + variant,
                variant.contains(RU_MODULE_LOWER));
        }

        // And a real NAME after a leading kind is still verbatim (an object literally called Forma).
        Set<String> nameAfterKind = MetadataTypeUtils.getAllFqnVariants(RU_FORM + "." + RU_FORM);
        assertTrue("only the leading segment translates",
            nameAfterKind.contains("form." + RU_FORM_LOWER));
        assertFalse("the NAME must not be translated too",
            nameAfterKind.contains("form.form"));
    }

    @Test
    public void testGetAllFqnVariantsNeverExplodesCombinatorially()
    {
        // At most THREE candidates (original + all-English + all-Russian), never the per-segment
        // cross product, which would grow exponentially with the FQN depth.
        Set<String> deep = MetadataTypeUtils.getAllFqnVariants(
            "Catalog.Products.TabularSection.Goods.Attribute.Price");
        assertTrue("deep FQN produced " + deep.size() + " variants", deep.size() <= 3);

        // A MIXED-language input is the case that really yields all three distinct forms.
        Set<String> mixed = MetadataTypeUtils.getAllFqnVariants("Document.X." + RU_FORM + ".Y");
        assertEquals(3, mixed.size());
        assertTrue(mixed.contains("document.x." + RU_FORM_LOWER + ".y")); // original
        assertTrue(mixed.contains("document.x.form.y")); // all-English
        assertTrue(mixed.contains(RU_DOCUMENT_LOWER + ".x." + RU_FORM_LOWER + ".y")); // all-Russian
    }


    /**
     * Every nested kind this catalogue publishes must have an OWNER predicate on the exact path, and
     * that owner must accept EVERY spelling the catalogue advertises for it.
     *
     * <p>This is the invariant behind three separate review findings, each one a fresh copy of the
     * same mistake: the catalogue advertised a spelling, the exact resolver's own list of literals
     * did not have it, so an address we document resolved by NAME and was then refused on its KIND -
     * a node that plainly exists reported as objectsNotFound. Enumerating the fixes is what let the
     * next copy through, so the property is checked over the WHOLE catalogue instead.</p>
     *
     * <p>A kind added to the catalogue later has no owner here and FAILS this test until one is
     * declared. That is deliberate: declaring the owner is exactly the step that was being missed.</p>
     */
    @Test
    public void testEveryPublishedNestedKindTokenIsAcceptedByItsExactResolver()
    {
        // EVERY applicable consumer, not one. A single-owner map let the FIRST consumer fill the
        // slot: for Attribute and Command that was the form parser, so the catalogue ->
        // MetadataNodeResolver direction went unchecked for them and an alias published here and
        // accepted by the form writer, but unknown to the resolver, passed green.
        Map<String, List<Predicate<String>>> owners = new LinkedHashMap<>();
        // Form-content kinds: the form parser resolves the element and then checks the KIND token.
        for (FormElementWriter.Kind kind : FormElementWriter.Kind.values())
        {
            List<String> tokens = FormElementWriter.tokensForKind(kind);
            if (tokens.isEmpty())
            {
                continue;
            }
            MetadataTypeUtils.NestedKindInfo info =
                MetadataTypeUtils.resolveNestedKind(tokens.get(0));
            assertNotNull("the form parser accepts '" + tokens.get(0) + "' but this map does not",
                info);
            owners.computeIfAbsent(info.getEnglish(), k -> new ArrayList<>())
                .add(t -> FormElementWriter.kindForToken(t) == kind);
        }
        // The structural tokens that route an address to a branch rather than to an element kind.
        addOwner(owners, "Form", FormElementWriter::isFormToken);
        addOwner(owners, "Handler", FormElementWriter::isHandlerToken);
        addOwner(owners, "Subsystem", SubsystemUtils::isSubsystemTypeToken);
        addOwner(owners, "Predefined", MetadataTypeUtilsTest::predefinedTokenAccepted);
        // Resolver groups are built by a FULL pass over the resolver's own map, and its
        // applicability is decided by THAT, never by asking the resolver about the canonical token.
        // Deriving applicability from the thing under test let it opt out: deleting just the
        // canonical "attribute" token made featureNameForKind("Attribute") null, the resolver
        // dropped out of the owners, the reverse-equality block was skipped, and the form owner
        // kept the "no owner declared" assertion quiet. Losing a token switched off its own check.
        Map<String, Set<String>> resolverTokens = new LinkedHashMap<>();
        Map<String, Set<String>> resolverFeatures = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : MetadataNodeResolver.childFeatureByToken().entrySet())
        {
            MetadataTypeUtils.NestedKindInfo info = MetadataTypeUtils.resolveNestedKind(e.getKey());
            assertNotNull("resolver token is not published by the catalogue: " + e.getKey(), info);
            resolverTokens.computeIfAbsent(info.getEnglish(), k -> new TreeSet<>())
                .add(e.getKey().toLowerCase(Locale.ROOT));
            resolverFeatures.computeIfAbsent(info.getEnglish(), k -> new LinkedHashSet<>())
                .add(e.getValue());
        }
        for (String canonical : resolverTokens.keySet())
        {
            addOwner(owners, canonical, t -> MetadataNodeResolver.featureNameForKind(t) != null);
        }
        // Applicability of the mdclass resolver is DECLARED, not inferred from the map under test.
        // Deriving it from resolverTokens.keySet() still let the subject opt out: deleting the whole
        // Attribute block made the group vanish, so the exact-equality branch never ran, and the
        // form owner kept the "no owner declared" assertion quiet. A vanished group is exactly the
        // regression this test exists to catch, so the set below is written by hand ON PURPOSE -
        // the alternative is not inference but self-confirmation. Adding an mdclass kind requires
        // adding it here, and that is the step that must not be silent.
        for (String canonical : MDCLASS_RESOLVED_KINDS)
        {
            assertTrue("the mdclass resolver must still map the kind '" + canonical //$NON-NLS-1$
                + "' - its whole token group disappeared", resolverTokens.containsKey(canonical)); //$NON-NLS-1$
            addOwner(owners, canonical, t -> MetadataNodeResolver.featureNameForKind(t) != null);
        }

        // Every OTHER kind is an mdclass member, and its exact resolver is MetadataNodeResolver:
        // it maps the kind token to the EMF child feature. That is a real owner, not an excuse -
        // listing these by hand is exactly the "enumerate the fixes" habit that let the same drift
        // through twice, so they are checked through their resolver like everything else.
        Map<String, String> notAddressed = new LinkedHashMap<>();
        // The only genuine exceptions: CONTENT segments of a marker location. They are translated so
        // the filter can match a location, but no address is ever parsed with them as a segment.
        notAddressed.put("Module", "a CONTENT segment of a marker location (CommonModule.X.Module), "
            + "never an address segment - it is translated for matching only");
        notAddressed.put("Package", "the CONTENT of an XDTO package (XDTOPackage.P.Package) - a "
            + "marker location segment; XDTO members are answered as objectsUnsupported");

        for (String canonical : MetadataTypeUtils.nestedKindCanonicalTokens())
        {
            List<Predicate<String>> applicable = owners.get(canonical);
            if (applicable == null)
            {
                assertTrue("a published kind must declare an owner or be documented as content-only: "
                    + canonical, notAddressed.containsKey(canonical));
                continue;
            }
            Set<String> aliases = MetadataTypeUtils.nestedKindAliases(canonical);
            assertFalse("a published kind must have spellings: " + canonical, aliases.isEmpty());
            for (Predicate<String> owner : applicable)
            {
                for (String alias : aliases)
                {
                    assertTrue("the catalogue advertises '" + alias + "' for " + canonical
                        + ", so the exact resolver must accept it", owner.test(alias));
                    // ...and case must not matter: a marker location renders these capitalized.
                    assertTrue("case must not matter for '" + alias + "'",
                        owner.test(alias.substring(0, 1).toUpperCase() + alias.substring(1)));
                }
            }
            // ...and the reverse direction for the mdclass resolver: its token group for this kind
            // must equal the catalogue's aliases EXACTLY, and all of them must mean one feature.
            Set<String> published = new TreeSet<>();
            for (String alias : aliases)
            {
                published.add(alias.toLowerCase(Locale.ROOT));
            }
            if (resolverTokens.containsKey(canonical))
            {
                assertEquals("resolver and catalogue must accept EXACTLY the same tokens for "
                    + canonical, published, resolverTokens.get(canonical));
                assertEquals("every alias of one kind must resolve to ONE feature: " + canonical,
                    1, resolverFeatures.get(canonical).size());
            }
            // 2. The FORM consumer, in the SAME place and in the reverse direction too: its token
            // list must equal the catalogue's aliases exactly, so a token the form parser accepts
            // but the catalogue does not publish is caught here rather than in a second test.
            FormElementWriter.Kind formKind = FormElementWriter.kindForToken(canonical);
            if (formKind != null)
            {
                Set<String> formTokens = new TreeSet<>();
                for (String token : FormElementWriter.tokensForKind(formKind))
                {
                    formTokens.add(token.toLowerCase(Locale.ROOT));
                }
                assertEquals("form parser and catalogue must accept EXACTLY the same tokens for "
                    + canonical, published, formTokens);
            }
        }
    }

    /**
     * Canonical kinds the mdclass resolver MUST map, declared explicitly.
     *
     * <p>The one place in this file where a hand-written list is the right answer. Everything else
     * is derived from a catalogue so a new entry cannot slip past; here derivation would mean asking
     * the map under test whether it still contains the group, which is self-confirmation - losing a
     * group would switch off the check that a group must not be lost.</p>
     */
    private static final Set<String> MDCLASS_RESOLVED_KINDS = new LinkedHashSet<>(Arrays.asList(
        "Attribute", "TabularSection", "Dimension", "Resource", "EnumValue", "Command",
        "AccountingFlag", "ExtDimensionAccountingFlag", "AddressingAttribute", "Column",
        "Template", "Recalculation", "URLTemplate", "Method", "Operation", "Parameter"));

    /** Registers one more applicable consumer for a canonical kind. */
    private static void addOwner(Map<String, List<Predicate<String>>> owners, String canonical,
        Predicate<String> owner)
    {
        owners.computeIfAbsent(canonical, k -> new ArrayList<>()).add(owner);
    }

    /** The predefined-item token predicate, which is private to its writer - probed through parseRef. */
    private static boolean predefinedTokenAccepted(String token)
    {
        return PredefinedWriter.parseRef("Catalog.Products." + token + ".Sample") != null;
    }


    @Test
    public void testALooseFragmentIsTranslatedAtBOTHSegmentParities()
    {
        // A fragment's OFFSET into the location is unknown. It may start on the type
        // (Catalog.Products), on a nested kind (Form.ItemForm) - or on a NAME (ItemForm.Form), and
        // then the structural segments sit on the ODD indexes. Assuming one parity left the other
        // untranslated, so the fragment matched nothing; and because a loose entry reports no miss,
        // the caller just got an empty report. Three findings in a row, one per offset - so BOTH
        // parities are emitted rather than one being guessed.
        //
        // 'ItemForm.<Forma>' is a real substring of the English location
        // 'Catalog.C.Form.ItemForm.Form' once the Russian kind is translated, and it starts on a
        // NAME.
        Set<String> nameLeading = MetadataTypeUtils.getAllFragmentVariants("ItemForm." + RU_FORM);
        assertTrue("the fragment as typed must stay",
            nameLeading.contains("itemform." + RU_FORM_LOWER));
        assertTrue("a NAME-leading fragment must translate its structural segment too",
            nameLeading.contains("itemform.form"));

        // The other parity still works - this is an addition, not a replacement.
        Set<String> kindLeading = MetadataTypeUtils.getAllFragmentVariants(RU_FORM + ".ItemForm");
        assertTrue(kindLeading.contains("form.itemform"));
        Set<String> typeLeading = MetadataTypeUtils.getAllFragmentVariants("Document.Meeting");
        assertTrue(typeLeading.contains(RU_DOCUMENT_LOWER + ".meeting"));

        // Deeper, mixed: both readings of 'Products.Attribute.Weight' are produced.
        Set<String> deep = MetadataTypeUtils.getAllFragmentVariants("Products.Attribute.Weight");
        assertTrue("the odd parity must translate the nested kind",
            deep.contains("products." + RU_ATTRIBUTE_LOWER + ".weight"));

        // LINEAR, not combinatorial: original + 2 parities x 2 languages, deduplicated.
        assertTrue("a fragment must never explode: got " + deep.size(), deep.size() <= 5);
        Set<String> deeper = MetadataTypeUtils.getAllFragmentVariants(
            "Products.Attribute.Weight.Form.ItemForm.Field.Code");
        assertTrue("depth must not change the bound: got " + deeper.size(), deeper.size() <= 5);
    }

    @Test
    public void testTheExactFilterKeepsTheSingleKnownParity()
    {
        // The dual parity belongs to the LOOSE filter alone. A full address always begins on a
        // structural segment, so its parity is known - expanding the other one there would let a
        // NAME that literally spells a kind token widen an EXACT scope onto unrelated objects.
        Set<String> exact = MetadataTypeUtils.getAllFqnVariants("Catalog." + RU_FORM);
        assertTrue(exact.contains("catalog." + RU_FORM_LOWER));
        assertFalse("a NAME must never be translated on the exact path",
            exact.contains("catalog.form"));
        // ...while the same string read as a FRAGMENT does get the second reading.
        assertTrue(MetadataTypeUtils.getAllFragmentVariants("Catalog." + RU_FORM)
            .contains("catalog.form"));
    }


    @Test
    public void testASingleTokenFragmentIsTranslatedToo()
    {
        // The early return for a dotless input skipped the catalogues entirely, so a one-token
        // fragment came back as nothing but its own lowercase. In an English workspace the Russian
        // MODULE and FORM tokens are valid substrings of 'CommonModule.Calc.Module' and
        // '...Form.ItemForm.Form', so the filter selected nothing - and a loose entry reports no
        // miss. Same false all-clear, reached by an early return rather than by the parity logic.
        Set<String> module = MetadataTypeUtils.getAllFragmentVariants(RU_MODULE_LOWER);
        assertTrue("the token as typed must stay", module.contains(RU_MODULE_LOWER));
        assertTrue("a lone nested-kind token must reach its English spelling",
            module.contains("module"));

        Set<String> form = MetadataTypeUtils.getAllFragmentVariants(RU_FORM);
        assertTrue(form.contains("form"));

        // A lone TYPE token too, and symmetrically from English into Russian.
        assertTrue(MetadataTypeUtils.getAllFragmentVariants(RU_DOCUMENT).contains("document"));
        assertTrue(MetadataTypeUtils.getAllFragmentVariants("Module").contains(RU_MODULE_LOWER));

        // A bare word that is NOT a structural token stays exactly as typed - the early return's
        // original point, which must survive: it must never be read as a type.
        Set<String> plain = MetadataTypeUtils.getAllFragmentVariants("MethodName");
        assertEquals(1, plain.size());
        assertTrue(plain.contains("methodname"));

        // And the EXACT path keeps the old behaviour: a lone token is not an address at all.
        assertEquals(1, MetadataTypeUtils.getAllFqnVariants(RU_MODULE_LOWER).size());

        // A LEADING dot is NOT a single token - it is a multi-token fragment, and a real substring
        // of 'Catalog.Products.Form.ItemForm.Form'. Treating indexOf('.') <= 0 as "one token" left
        // it untranslated: the very same early-return hole, one character further along.
        Set<String> leadingDot = MetadataTypeUtils.getAllFragmentVariants("." + RU_FORM + ".ItemForm");
        assertTrue("a leading-dot fragment must still translate its structural token",
            leadingDot.contains(".form.itemform"));
        assertTrue(MetadataTypeUtils.getAllFragmentVariants(".Form.ItemForm")
            .contains("." + RU_FORM_LOWER + ".itemform"));

        // ...and BOTH parities, not just the odd one. With a leading dot the structural tokens can
        // sit on EITHER side of the empty first segment, and delegating the even parity to
        // getAllFqnVariants lost it - that method bails on a leading dot by design.
        Set<String> bothWays = MetadataTypeUtils.getAllFragmentVariants(".Products." + RU_FORM);
        assertTrue("the EVEN parity must be produced for a leading-dot fragment too",
            bothWays.contains(".products.form"));

        // A trailing dot, a doubled dot and an all-dots fragment behave the same way: an empty
        // segment is simply a segment that translates to itself.
        assertTrue(MetadataTypeUtils.getAllFragmentVariants(RU_FORM + ".ItemForm.")
            .contains("form.itemform."));
        assertTrue(MetadataTypeUtils.getAllFragmentVariants("Products.." + RU_FORM)
            .contains("products..form"));
        assertFalse(MetadataTypeUtils.getAllFragmentVariants("..").isEmpty());
    }

    // ========== resolveNestedKind ==========

    @Test
    public void testResolveNestedKindEnglishAndRussianSingularAndPlural()
    {
        for (String token : new String[]{"Form", "forms", "FORM", RU_FORM, RU_FORMS})
        {
            MetadataTypeUtils.NestedKindInfo info = MetadataTypeUtils.resolveNestedKind(token);
            assertNotNull("token should resolve: " + token, info);
            assertEquals("Form", info.getEnglish());
            assertEquals(RU_FORM, info.getRussian());
        }
    }

    @Test
    public void testResolveNestedKindCoversTheStructuralKinds()
    {
        // The nested kinds an FQN can address; each must resolve from its English spelling and
        // round-trip through its Russian canon.
        String[] kinds = {"Form", "Attribute", "TabularSection", "Dimension", "Resource",
            "EnumValue", "Command", "Template", "Column", "Recalculation", "AccountingFlag",
            "AddressingAttribute", "Package"};
        for (String kind : kinds)
        {
            MetadataTypeUtils.NestedKindInfo info = MetadataTypeUtils.resolveNestedKind(kind);
            assertNotNull("nested kind should be catalogued: " + kind, info);
            assertEquals(kind, info.getEnglish());
            MetadataTypeUtils.NestedKindInfo byRussian =
                MetadataTypeUtils.resolveNestedKind(info.getRussian());
            assertNotNull("Russian canon should resolve for: " + kind, byRussian);
            assertEquals(kind, byRussian.getEnglish());
        }
    }

    @Test
    public void testResolveNestedKindUnknownAndNull()
    {
        assertNull(MetadataTypeUtils.resolveNestedKind(null));
        assertNull(MetadataTypeUtils.resolveNestedKind(""));
        assertNull(MetadataTypeUtils.resolveNestedKind("Widget"));
        // A TOP-LEVEL type is NOT a nested kind: the two catalogues stay separate.
        assertNull(MetadataTypeUtils.resolveNestedKind("Catalog"));
    }

    // ---- the alias inventory must match the repo's PUBLIC FQN parsers (issue #312 review) -------

    @Test
    public void testNestedSubsystemAndPredefinedTranslate()
    {
        // SubsystemUtils parses a nested subsystem chain and PredefinedWriter a Predefined item;
        // both shapes are documented FQNs, so both structural tokens must normalize in either
        // direction - otherwise they reproduce the very locale miss issue #312 fixes.
        Set<String> subsystem =
            MetadataTypeUtils.getAllFqnVariants("Subsystem.Sales.Subsystem.Orders"); //$NON-NLS-1$
        assertTrue("a nested Subsystem token must translate", //$NON-NLS-1$
            subsystem.contains("\u043F\u043E\u0434\u0441\u0438\u0441\u0442\u0435\u043C\u0430.sales.\u043F\u043E\u0434\u0441\u0438\u0441\u0442\u0435\u043C\u0430.orders")); //$NON-NLS-1$

        Set<String> predefined =
            MetadataTypeUtils.getAllFqnVariants("Catalog.Goods.Predefined.Service"); //$NON-NLS-1$
        assertTrue("the Predefined token must translate", //$NON-NLS-1$
            predefined.contains("\u0441\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.goods.\u043F\u0440\u0435\u0434\u043E\u043F\u0440\u0435\u0434\u0435\u043B\u0435\u043D\u043D\u044B\u0435.service")); //$NON-NLS-1$

        // The natural yo spelling is accepted too, exactly as PredefinedWriter accepts it.
        assertNotNull(MetadataTypeUtils.resolveNestedKind("\u041F\u0440\u0435\u0434\u043E\u043F\u0440\u0435\u0434\u0435\u043B\u0451\u043D\u043D\u044B\u0435")); //$NON-NLS-1$
    }

    @Test
    public void testTheXdtoPackageContentSegmentTranslatesBothWays()
    {
        // EDT reports every problem of an XDTO package on the package CONTENT, so a marker location
        // ends in the structural segment Package (ru Paket) - which is exactly what this tool
        // documents. Without that alias the trailing segment survived untranslated: a fully Russian
        // address expanded to "xdtopackage.p.<cyrillic>", which never matches the location an
        // English-language workspace renders, so the filter silently reported a clean package.
        String ruPackage = "\u041F\u0430\u043A\u0435\u0442"; // Paket
        String ruXdtoPackage = "\u041F\u0430\u043A\u0435\u0442XDTO"; // PaketXDTO

        Set<String> fromRussian =
            MetadataTypeUtils.getAllFqnVariants(ruXdtoPackage + ".P." + ruPackage); //$NON-NLS-1$
        assertTrue("a Russian XDTO address must produce a fully ENGLISH variant", //$NON-NLS-1$
            fromRussian.contains("xdtopackage.p.package")); //$NON-NLS-1$

        Set<String> fromEnglish = MetadataTypeUtils.getAllFqnVariants("XDTOPackage.P.Package"); //$NON-NLS-1$
        assertTrue("an English XDTO address must produce a fully RUSSIAN variant", //$NON-NLS-1$
            fromEnglish.contains(ruXdtoPackage.toLowerCase() + ".p." + ruPackage.toLowerCase())); //$NON-NLS-1$
    }

    // ---- form-content kinds inside a nested FQN (issue #312 review) ------------------------------

    @Test
    public void testFormItemKindsTranslateInsideANestedFqn()
    {
        // A form validation marker's presentation descends into the ITEM tree, so a Russian
        // form-member path must reach an English presentation (and back). Before the fix the
        // `Pole` segment survived untranslated and matched nothing.
        Set<String> fromRussian = MetadataTypeUtils.getAllFqnVariants(
            "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Goods." //$NON-NLS-1$
                + "\u0424\u043E\u0440\u043C\u0430.ItemForm.\u041F\u043E\u043B\u0435.Price"); //$NON-NLS-1$
        assertTrue("the Russian form-member path must yield a fully English variant", //$NON-NLS-1$
            fromRussian.contains("catalog.goods.form.itemform.field.price")); //$NON-NLS-1$

        Set<String> fromEnglish =
            MetadataTypeUtils.getAllFqnVariants("Catalog.Goods.Form.ItemForm.Button.Post"); //$NON-NLS-1$
        assertTrue("the English form-member path must yield a fully Russian variant", //$NON-NLS-1$
            fromEnglish.contains("\u0441\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.goods." //$NON-NLS-1$
                + "\u0444\u043E\u0440\u043C\u0430.itemform.\u043A\u043D\u043E\u043F\u043A\u0430.post")); //$NON-NLS-1$
    }

    /**
     * The form kinds deliberately NOT required to be addressable as a nested FQN segment, each with
     * the reason it is exempt. EMPTY today: every kind the form parser accepts is also a nested-kind
     * alias here. An entry must carry its reason, so an omission is a recorded decision and never an
     * oversight - which is exactly what a hand-written list of the INCLUDED kinds could not give.
     */
    private static final Map<FormElementWriter.Kind, String> KINDS_NOT_ADDRESSED_AS_FQN_SEGMENT =
        Collections.emptyMap();

    @Test
    public void testEveryFormKindIsSpelledInBothLanguagesAndPairedConsistently()
    {
        // SCOPE - read this before adding to it. Token-SET parity between the catalogue and the form
        // parser (both directions), and that every alias reads back as its kind, are owned by
        // testEveryPublishedNestedKindTokenIsAcceptedByItsExactResolver. Duplicating them here is
        // what left one invariant split across two methods.
        //
        // What is NOT covered there, and is the only reason this test still exists:
        //   - BILINGUAL COVERAGE: a kind must have at least one Latin AND one Cyrillic spelling.
        //     Set equality cannot see this - two catalogues can agree on a purely English set.
        //   - The RUSSIAN half of the canonical pair: set equality compares tokens, never that all
        //     spellings of a kind carry the same canonical Russian.
        //   - Standalone aliases that belong to no form Kind (Column, Handler).
        for (FormElementWriter.Kind kind : FormElementWriter.Kind.values())
        {
            if (KINDS_NOT_ADDRESSED_AS_FQN_SEGMENT.containsKey(kind))
            {
                continue;
            }
            List<String> tokens = FormElementWriter.tokensForKind(kind);
            assertFalse("the form parser must publish the tokens it accepts for " + kind, //$NON-NLS-1$
                tokens.isEmpty());
            MetadataTypeUtils.NestedKindInfo canon = null;
            boolean sawLatin = false;
            boolean sawCyrillic = false;
            for (String token : tokens)
            {
                // Kept, NOT delegated: absorption of this one is UNPROVEN. The mutation meant to
                // test it was invalid (it referenced a map before its declaration, so it never
                // compiled), and a mutation that cannot run proves nothing. Deleting an assertion
                // on an unproven absorption is exactly how a guarantee is lost.
                assertEquals("the form parser must read '" + token + "' as " + kind, //$NON-NLS-1$ //$NON-NLS-2$
                    kind, FormElementWriter.kindForToken(token));
                MetadataTypeUtils.NestedKindInfo info = MetadataTypeUtils.resolveNestedKind(token);
                if (info == null)
                {
                    // NOT this test's business: whether the catalogue publishes every token the
                    // parser accepts is set parity, owned by the consolidated test. Asserting it
                    // here too made one mutation raise two identical signals.
                    continue;
                }
                if (canon == null)
                {
                    canon = info;
                }
                // The RUSSIAN half of the pair - the part token-set equality cannot check.
                assertEquals("every accepted spelling of " + kind //$NON-NLS-1$
                    + " must resolve to the SAME canonical Russian", //$NON-NLS-1$
                    canon.getRussian(), info.getRussian());
                if (isCyrillic(token))
                {
                    sawCyrillic = true;
                }
                else
                {
                    sawLatin = true;
                }
            }
            assertTrue("the form parser must accept an English spelling of " + kind, sawLatin); //$NON-NLS-1$
            assertTrue("the form parser must accept a Russian spelling of " + kind, sawCyrillic); //$NON-NLS-1$
        }
        // Aliases belonging to no form Kind, so no loop above can pin them: Column is a mdclass
        // nested kind (a DocumentJournal column) and Handler routes to its own branch.
        assertNotNull(MetadataTypeUtils.resolveNestedKind("Column")); //$NON-NLS-1$
        assertNotNull(MetadataTypeUtils.resolveNestedKind(
            "\u041A\u043E\u043B\u043E\u043D\u043A\u0430")); //$NON-NLS-1$
        assertNotNull(MetadataTypeUtils.resolveNestedKind("Handler")); //$NON-NLS-1$
        assertTrue(FormElementWriter.isHandlerToken(
            "\u043E\u0431\u0440\u0430\u0431\u043E\u0442\u0447\u0438\u043A")); //$NON-NLS-1$
    }

    /** Whether {@code token} is written in Cyrillic (its first letter decides). */
    private static boolean isCyrillic(String token)
    {
        for (int i = 0; i < token.length(); i++)
        {
            if (Character.isLetter(token.charAt(i)))
            {
                return Character.UnicodeBlock.of(token.charAt(i)) == Character.UnicodeBlock.CYRILLIC;
            }
        }
        return false;
    }
}
