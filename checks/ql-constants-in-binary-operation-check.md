# Using binary operations with constants or parameters in queries

## 📋 General Information

| Property | Value |
|----------|----------|
| **Check ID** | `ql-constants-in-binary-operation` |
| **Category** | Query Language |
| **Severity** | Major |
| **Type** | Code smell |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Checks use of binary operations with constants or parameters in queries. It's not recommended to generate template strings using calculations or string concatenation in query language. This requirement is based on specifics of application migration to various DBMS.

## ❌ Error Examples

```bsl
// Incorrect - string concatenation in query
Query.Text = "SELECT
    | Products.Name AS Name,
    | ""My"" + ""Goods"" AS Code
    |FROM
    | Catalog.Products AS Products";

// Incorrect - concatenation with parameter
Query.Text = "SELECT
    | Products.Name AS Name,
    | ""My"" + &Parameter AS Code
    |FROM
    | Catalog.Products AS Products";

// Incorrect - concatenation in LIKE clause
Query.Text = "SELECT
    | Products.Name AS Name,
    | Products.Code AS Code
    |FROM
    | Catalog.Products AS Products
    |WHERE
    | Products.Code LIKE ""123"" + ""%""";
```

## ✅ Compliant Solution

```bsl
// Correct - single string literal
Query.Text = "SELECT
    | Products.Name AS Name,
    | ""MyGoods"" AS Code
    |FROM
    | Catalog.Products AS Products";

// Correct - parameter without concatenation
Query.Text = "SELECT
    | Products.Name AS Name,
    | &Parameter AS Code
    |FROM
    | Catalog.Products AS Products";

// Correct - complete pattern in LIKE
Query.Text = "SELECT
    | Products.Name AS Name,
    | Products.Code AS Code
    |FROM
    | Catalog.Products AS Products
    |WHERE
    | Products.Code LIKE ""123%""";
```

## 🔧 How to Fix

1. Объедините строковые литералы в одну строку
2. Формируйте параметры запроса заранее в коде BSL
3. Используйте полные шаблоны в выражениях LIKE
4. Избегайте вычислений и конкатенации внутри текста запроса

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.ql.check.ConstantsInBinaryOperationCheck`
- **Plugin**: `com.e1c.v8codestyle.ql`

