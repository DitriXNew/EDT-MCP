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
        assertEquals("xdtoPackages", MetadataTypeUtils.getConfigReferenceName("XDTOPackage"));
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
}
