# Right set: Interactive Set Deletion Mark Predefined Data

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-interactive-set-deletion-mark-predefined-data` |
| **Category** | Role Rights |
| **Severity** | Major |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `InteractiveSetDeletionMarkPredefinedData` right in roles. This right allows marking predefined catalog items for deletion. Should be limited to administrative roles.

## ❌ Error Examples

```xml
<!-- Incorrect - Right enabled for regular role -->
<Rights>
  <Right>
    <Name>InteractiveSetDeletionMarkPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>

<!-- Incorrect - User can mark predefined data for deletion -->
<Rights>
  <Right>
    <Name>InteractiveSetDeletionMarkPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Units</Object>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - Right disabled for user roles -->
<Rights>
  <Right>
    <Name>InteractiveSetDeletionMarkPredefinedData</Name>
    <Value>false</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>

<!-- Correct - Only for Administrator role -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>InteractiveSetDeletionMarkPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Disable the right for all regular roles
2. Allow only for the Administrator role
3. Predefined data protects configuration integrity
4. Changing their state requires administrative control

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightInteractiveSetDeletionMarkPredefinedData`
- **Plugin**: `com.e1c.v8codestyle.right`

## 📚 References

- [Role-based access restriction](https://its.1c.ru/db/v8std/content/689/hdoc)
