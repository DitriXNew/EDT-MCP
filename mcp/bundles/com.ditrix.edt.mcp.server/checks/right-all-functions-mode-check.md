# Right set: All Functions Mode

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-all-functions-mode` |
| **Category** | Role Rights |
| **Severity** | Critical |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `AllFunctionsMode` right in roles. This right provides access to a special mode that allows opening all configuration objects directly. Should only be available for administrative roles as it can bypass interface restrictions.

## ❌ Error Examples

```xml
<!-- Incorrect - AllFunctionsMode for regular user -->
<Rights>
  <Right>
    <Name>AllFunctionsMode</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Incorrect - Multiple roles with AllFunctionsMode -->
<!-- Role: PowerUser -->
<Rights>
  <Right>
    <Name>AllFunctionsMode</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - AllFunctionsMode only for Administrator -->
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>AllFunctionsMode</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Regular roles without AllFunctionsMode -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>AllFunctionsMode</Name>
    <Value>false</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Remove the AllFunctionsMode right for all roles except Administrator
2. Provide access to required functions through the interface
3. Create subsystems and commands for needed actions
4. Do not use AllFunctionsMode as a substitute for proper interface configuration

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightAllFunctionsMode`
- **Plugin**: `com.e1c.v8codestyle.right`

