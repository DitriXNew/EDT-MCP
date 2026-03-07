# Right set: Interactive Clear Deletion Mark Predefined Data

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-interactive-clear-deletion-mark-predefined-data` |
| **Category** | Role Rights |
| **Severity** | Major |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `InteractiveClearDeletionMarkPredefinedData` right in roles. This right allows clearing the deletion mark from predefined items. Should be limited to administrative roles.

## ❌ Error Examples

```xml
<!-- Incorrect - Right enabled for regular user role -->
<Rights>
  <Right>
    <Name>InteractiveClearDeletionMarkPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>

<!-- Incorrect - Operator can clear deletion mark -->
<Rights>
  <Right>
    <Name>InteractiveClearDeletionMarkPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.PaymentTypes</Object>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - Right disabled for regular roles -->
<Rights>
  <Right>
    <Name>InteractiveClearDeletionMarkPredefinedData</Name>
    <Value>false</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>

<!-- Correct - Only Administrator has this right -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>InteractiveClearDeletionMarkPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Remove the right for all user roles
2. Allow only for the Administrator role
3. Operations with predefined data should be restricted
4. Document changes to predefined data

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightInteractiveClearDeletionMarkPredefinedData`
- **Plugin**: `com.e1c.v8codestyle.right`

## 📚 References

- [Role-based access restriction](https://its.1c.ru/db/v8std/content/689/hdoc)
