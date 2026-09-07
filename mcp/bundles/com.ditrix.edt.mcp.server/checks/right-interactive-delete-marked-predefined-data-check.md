# Right set: Interactive Delete Marked Predefined Data

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-interactive-delete-marked-predefined-data` |
| **Category** | Role Rights |
| **Severity** | Critical |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `InteractiveDeleteMarkedPredefinedData` right in roles. This right allows deleting marked predefined items. Should be limited to system roles only.

## ❌ Error Examples

```xml
<!-- Incorrect - Right enabled for regular role -->
<Rights>
  <Right>
    <Name>InteractiveDeleteMarkedPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>

<!-- Incorrect - Manager can delete marked predefined data -->
<Rights>
  <Right>
    <Name>InteractiveDeleteMarkedPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Currencies</Object>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - Right disabled for all roles -->
<Rights>
  <Right>
    <Name>InteractiveDeleteMarkedPredefinedData</Name>
    <Value>false</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>

<!-- Correct - Only for FullRights/Administrator role -->
<!-- Role: FullRights -->
<Rights>
  <Right>
    <Name>InteractiveDeleteMarkedPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Remove the InteractiveDeleteMarkedPredefinedData right for user roles
2. Keep the right only for service roles (FullRights)
3. Control deletion of predefined data through administrator
4. Use scheduled jobs for cleaning marked objects

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightInteractiveDeleteMarkedPredefinedData`
- **Plugin**: `com.e1c.v8codestyle.right`

## 📚 References

- [Role-based access restriction](https://its.1c.ru/db/v8std/content/689/hdoc)
