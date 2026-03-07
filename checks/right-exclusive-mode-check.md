# Right set: Exclusive Mode

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-exclusive-mode` |
| **Category** | Role Rights |
| **Severity** | Critical |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `ExclusiveMode` right in roles. This right allows setting exclusive mode, disconnecting all other users from the infobase. This is a critical operation and should only be available to administrators.

## ❌ Error Examples

```xml
<!-- Incorrect - ExclusiveMode for regular user -->
<Rights>
  <Right>
    <Name>ExclusiveMode</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Incorrect - Non-admin role with exclusive mode -->
<!-- Role: Accountant -->
<Rights>
  <Right>
    <Name>ExclusiveMode</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - ExclusiveMode only for Administrator -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>ExclusiveMode</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Users cannot set exclusive mode -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>ExclusiveMode</Name>
    <Value>false</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Remove the ExclusiveMode right for all roles except Administrator
2. Exclusive mode blocks all users from working
3. Use alternative data locking mechanisms
4. Document cases where exclusive mode is necessary

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightExclusiveMode`
- **Plugin**: `com.e1c.v8codestyle.right`

