# Right set: Start Automation

## 📋 General Information

| Property | Value |
|----------|-------|
| **Check ID** | `right-start-automation` |
| **Category** | Role Rights |
| **Severity** | Critical |
| **Type** | Security |
| **Standard** | 1C:Enterprise Development Standards |

## 🎯 What This Check Does

Verifies the assignment of the `StartAutomation` (OLE Automation) right in roles. This right allows connecting to the infobase through COM connection (OLE Automation). Poses a serious security threat and should be strictly limited.

## ❌ Error Examples

```xml
<!-- Incorrect - StartAutomation for regular user -->
<Rights>
  <Right>
    <Name>StartAutomation</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Incorrect - Multiple roles with automation -->
<!-- Role: Developer -->
<Rights>
  <Right>
    <Name>StartAutomation</Name>
    <Value>true</Value>
  </Right>
</Rights>
```

## ✅ Compliant Solution

```xml
<!-- Correct - StartAutomation for service account only -->
<!-- Role: IntegrationService -->
<Rights>
  <Right>
    <Name>StartAutomation</Name>
    <Value>true</Value>
  </Right>
</Rights>

<!-- Correct - Users cannot use automation -->
<!-- Role: User -->
<Rights>
  <Right>
    <Name>StartAutomation</Name>
    <Value>false</Value>
  </Right>
</Rights>
```

## 🔧 How to Fix

1. Remove the StartAutomation right for all user roles
2. Allow only for integration service accounts
3. Use web services or HTTP services for integration
4. Document all automation usage cases

## 🔍 Technical Details

- **Check class**: `com.e1c.v8codestyle.right.check.RightStartAutomation`
- **Plugin**: `com.e1c.v8codestyle.right`

