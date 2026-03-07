# Temporary table should have indexes

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `ql-temp-table-index` |
| **Category** | Query Language |
| **Severity** | Major |
| **Type** | Performance |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies that temporary tables have indexes. Indexing is advisable when:

1. A large temporary table participates in a join (regardless of which side). Add fields participating in the BY condition to the index.
2. A temporary table is used in a subquery of the IN (...) logical operator. Add temporary table fields from the selection list corresponding to fields on the left side of the IN(...) operator to the index.

**Note**: There is no need to index small temporary tables consisting of less than 1000 records.

## ❌ Error Examples

```bsl
// Incorrect - large temp table without index used in join
Query.Text = "SELECT
    | Orders.Product AS Product,
    | Orders.Quantity AS Quantity
    |INTO TempOrders
    |FROM
    | Document.SalesOrder.Products AS Orders
    |;
    |SELECT
    | Products.Name,
    | TempOrders.Quantity
    |FROM
    | Catalog.Products AS Products
    |LEFT JOIN TempOrders AS TempOrders
    |ON Products.Ref = TempOrders.Product";

// Incorrect - temp table in IN clause without index
Query.Text = "SELECT
    | Products.Ref AS Product
    |INTO SelectedProducts
    |FROM
    | Catalog.Products AS Products
    |WHERE
    | Products.Category = &Category
    |;
    |SELECT
    | Prices.Price
    |FROM
    | InformationRegister.Prices AS Prices
    |WHERE
    | Prices.Product IN (SELECT Product FROM SelectedProducts)";
```

## ✅ Compliant Solution

```bsl
// Correct - temp table with index for join
Query.Text = "SELECT
    | Orders.Product AS Product,
    | Orders.Quantity AS Quantity
    |INTO TempOrders
    |FROM
    | Document.SalesOrder.Products AS Orders
    |
    |INDEX BY
    | Product
    |;
    |SELECT
    | Products.Name,
    | TempOrders.Quantity
    |FROM
    | Catalog.Products AS Products
    |LEFT JOIN TempOrders AS TempOrders
    |ON Products.Ref = TempOrders.Product";

// Correct - temp table with index for IN clause
Query.Text = "SELECT
    | Products.Ref AS Product
    |INTO SelectedProducts
    |FROM
    | Catalog.Products AS Products
    |WHERE
    | Products.Category = &Category
    |
    |INDEX BY
    | Product
    |;
    |SELECT
    | Prices.Price
    |FROM
    | InformationRegister.Prices AS Prices
    |WHERE
    | Prices.Product IN (SELECT Product FROM SelectedProducts)";
```

## 🔧 How to Fix

1. Determine if the temporary table participates in joins or IN operators
2. Add the `INDEX BY` clause after the temporary table definition
3. Include fields used in join conditions in the index
4. For IN operators, index fields from the subquery's selection list

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.ql.check.TempTableHasIndex`
- **Plugin**: `com.e1c.v8codestyle.ql`

