# Query join with sub query

## 📋 General Information

| Property | Value |
|----------|----------|
| **Check ID** | `ql-join-to-sub-query` |
| **Category** | Query Language |
| **Severity** | Major |
| **Type** | Performance |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Checks use of joins with subqueries. Joins with subqueries can lead to suboptimal query execution plan and performance degradation. It's recommended to use temporary tables instead of subqueries in joins.

## ❌ Error Examples

```bsl
// Incorrect - join with subquery
Query.Text = "SELECT
    | Products.Name,
    | Prices.Price
    |FROM
    | Catalog.Products AS Products
    |LEFT JOIN (
    |    SELECT
    |        ProductPrices.Product AS Product,
    |        MAX(ProductPrices.Price) AS Price
    |    FROM
    |        InformationRegister.ProductPrices AS ProductPrices
    |    GROUP BY
    |        ProductPrices.Product
    |) AS Prices
    |ON Products.Ref = Prices.Product";

// Incorrect - multiple subquery joins
Query.Text = "SELECT
    | Orders.Ref,
    | Balances.Balance
    |FROM
    | Document.SalesOrder AS Orders
    |LEFT JOIN (
    |    SELECT
    |        Goods.GoodsRef,
    |        SUM(Goods.Quantity) AS Balance
    |    FROM
    |        AccumulationRegister.GoodsInWarehouses AS Goods
    |    GROUP BY
    |        Goods.GoodsRef
    |) AS Balances
    |ON Orders.Product = Balances.GoodsRef";
```

## ✅ Compliant Solution

```bsl
// Correct - use temporary tables
Query.Text = "SELECT
    | ProductPrices.Product AS Product,
    | MAX(ProductPrices.Price) AS Price
    |INTO TempPrices
    |FROM
    | InformationRegister.ProductPrices AS ProductPrices
    |GROUP BY
    | ProductPrices.Product
    |;
    |SELECT
    | Products.Name,
    | Prices.Price
    |FROM
    | Catalog.Products AS Products
    |LEFT JOIN TempPrices AS Prices
    |ON Products.Ref = Prices.Product";

// Correct - separate batch queries
Query.Text = "SELECT
    | Goods.GoodsRef AS GoodsRef,
    | SUM(Goods.Quantity) AS Balance
    |INTO TempBalances
    |FROM
    | AccumulationRegister.GoodsInWarehouses AS Goods
    |GROUP BY
    | Goods.GoodsRef
    |;
    |SELECT
    | Orders.Ref,
    | Balances.Balance
    |FROM
    | Document.SalesOrder AS Orders
    |LEFT JOIN TempBalances AS Balances
    |ON Orders.Product = Balances.GoodsRef";
```

## 🔧 How to Fix

1. Выделите подзапрос во временную таблицу
2. Используйте конструкцию `INTO TempTableName`
3. Замените соединение с подзапросом на соединение с временной таблицей
4. При необходимости добавьте индексы к временной таблице

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.ql.check.JoinToSubQuery`
- **Plugin**: `com.e1c.v8codestyle.ql`

