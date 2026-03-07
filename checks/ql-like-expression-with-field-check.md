# The right operand of the LIKE comparison operation is a table field

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `ql-like-expression-with-field` |
| **Category** | Query Language |
| **Severity** | Major |
| **Type** | Code smell |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies that the right operand of the LIKE comparison operation is a literal (parameter) or an expression over literals. Using a table field as the right operand of LIKE and ESCAPE is prohibited. Wildcard characters: `_` (any single character) and `%` (sequence of any characters).

## ❌ Error Examples

```bsl
// Incorrect - table field as right operand of LIKE
Query.Text = "SELECT
    | StocksBalanceAndTurnovers.Warehouse
    |FROM
    | AccumulationRegister.Stocks.BalanceAndTurnovers AS StocksBalanceAndTurnovers
    |WHERE
    | StocksBalanceAndTurnovers.Warehouse LIKE Table.Field";

// Incorrect - using another field in LIKE pattern
Query.Text = "SELECT
    | Products.Name
    |FROM
    | Catalog.Products AS Products
    |WHERE
    | Products.Code LIKE Products.SearchPattern";

// Incorrect - field reference with wildcard
Query.Text = "SELECT
    | Documents.Number
    |FROM
    | Document.Invoice AS Documents
    |WHERE
    | Documents.Number LIKE Patterns.NumberMask";
```

## ✅ Compliant Solution

```bsl
// Correct - string literal with escape character
Query.Text = "SELECT
    | StocksBalanceAndTurnovers.Warehouse
    |FROM
    | AccumulationRegister.Stocks.BalanceAndTurnovers AS StocksBalanceAndTurnovers
    |WHERE
    | StocksBalanceAndTurnovers.Warehouse LIKE ""123%!%"" ESCAPE ""!""";

// Correct - parameter as pattern
Query.Text = "SELECT
    | Products.Name
    |FROM
    | Catalog.Products AS Products
    |WHERE
    | Products.Code LIKE &SearchPattern";

// Correct - literal pattern
Query.Text = "SELECT
    | Documents.Number
    |FROM
    | Document.Invoice AS Documents
    |WHERE
    | Documents.Number LIKE ""INV-%""";
```

## 🔧 How to Fix

1. Replace the table field with a string literal or parameter
2. Build the search pattern in BSL code and pass it as a parameter
3. Use constant string literals
4. If escaping is needed, use ESCAPE with a literal

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.ql.check.LikeExpressionWithFieldCheck`
- **Plugin**: `com.e1c.v8codestyle.ql`

