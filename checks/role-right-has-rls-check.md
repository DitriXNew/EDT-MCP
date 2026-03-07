# Role Right has RLS

## 📋 General Information

| Property | Value |
|----------|----------|
| **Check ID** | `role-right-has-rls` |
| **Category** | Role Rights |
| **Severity** | Minor |
| **Type** | Code smell |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Checks presence of RLS (Row Level Security - record level restrictions) for rights in roles. If rights to an object are set for a role, checks necessity of restricting access to individual records. RLS allows limiting user access to data at individual record level.

## ❌ Error Examples

```xml
<!-- Incorrect - Read right without RLS when separation is needed -->
<Rights>
  <Right>
    <Name>Read</Name>
    <Value>true</Value>
    <Object>Document.Invoice</Object>
    <!-- Missing RLS restriction -->
  </Right>
</Rights>

<!-- Incorrect - Update right without organization restriction -->
<Rights>
  <Right>
    <Name>Update</Name>
    <Value>true</Value>
    <Object>Catalog.Partners</Object>
    <!-- Should have RLS by organization -->
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - Read right with RLS restriction -->
<Rights>
  <Right>
    <Name>Read</Name>
    <Value>true</Value>
    <Object>Document.Invoice</Object>
    <Restriction>
      Organization IN (&AvailableOrganizations)
    </Restriction>
  </Right>
</Rights>

<!-- Correct - Update right with RLS -->
<Rights>
  <Right>
    <Name>Update</Name>
    <Value>true</Value>
    <Object>Catalog.Partners</Object>
    <Restriction>
      Organization IN (&AvailableOrganizations) AND
      Department IN (&AvailableDepartments)
    </Restriction>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Определите, требуется ли разграничение данных по записям
2. Определите измерения разграничения (организация, подразделение и т.д.)
3. Добавьте ограничения RLS к соответствующим правам
4. Используйте параметры сессии для хранения доступных значений

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RoleRightHasRls`
- **Plugin**: `com.e1c.v8codestyle.right`

## 📚 References

- [Access restriction at record level](https://its.1c.ru/db/v8std/content/689/hdoc)
