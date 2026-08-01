/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.Set;

import org.junit.Test;

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
            "AddressingAttribute"};
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

    @Test
    public void testFormKindAliasesAgreeWithTheFormParser()
    {
        // Two token tables now describe the same form kinds: this one (for FILTER variants) and
        // FormElementWriter's (for FQN parsing). They are separate on purpose - the parser maps a
        // token to an EMF feature, this map to a bilingual canon - so pin them against each other,
        // or a kind added to one will silently drift from the other.
        String[][] pairs = {
            {"Attribute", "\u0420\u0435\u043A\u0432\u0438\u0437\u0438\u0442"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Command", "\u041A\u043E\u043C\u0430\u043D\u0434\u0430"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Field", "\u041F\u043E\u043B\u0435"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Button", "\u041A\u043D\u043E\u043F\u043A\u0430"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Group", "\u0413\u0440\u0443\u043F\u043F\u0430"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Decoration", "\u0414\u0435\u043A\u043E\u0440\u0430\u0446\u0438\u044F"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"Table", "\u0422\u0430\u0431\u043B\u0438\u0446\u0430"}, //$NON-NLS-1$ //$NON-NLS-2$
        };
        // Column is deliberately absent from this pin: it is a nested kind of the MDCLASS model (a
        // DocumentJournal column), which this map must translate, but it is not a form-content kind
        // the form parser knows. Add it here if the parser ever gains it.
        assertNotNull(MetadataTypeUtils.resolveNestedKind("Column")); //$NON-NLS-1$
        assertNotNull(MetadataTypeUtils.resolveNestedKind(
            "\u041A\u043E\u043B\u043E\u043D\u043A\u0430")); //$NON-NLS-1$
        for (String[] pair : pairs)
        {
            assertNotNull("this map must know the form kind " + pair[0], //$NON-NLS-1$
                MetadataTypeUtils.resolveNestedKind(pair[0]));
            assertNotNull("this map must know the Russian form kind for " + pair[0], //$NON-NLS-1$
                MetadataTypeUtils.resolveNestedKind(pair[1]));
            assertEquals("the form parser must read both spellings of " + pair[0] //$NON-NLS-1$
                + " as the SAME kind", //$NON-NLS-1$
                FormElementWriter.kindForToken(pair[0]), FormElementWriter.kindForToken(pair[1]));
            assertNotNull("the form parser must know " + pair[0], //$NON-NLS-1$
                FormElementWriter.kindForToken(pair[0]));
        }
        // Handler is not a Kind (it routes to its own branch), but it IS a structural segment.
        assertNotNull(MetadataTypeUtils.resolveNestedKind("Handler")); //$NON-NLS-1$
        assertTrue(FormElementWriter.isHandlerToken(
            "\u043E\u0431\u0440\u0430\u0431\u043E\u0442\u0447\u0438\u043A")); //$NON-NLS-1$
    }
}
