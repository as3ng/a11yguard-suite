# a11yguard

Runtime detection of accessibility-driven automation and overlay abuse for Android apps that handle
sensitive actions such as authentication and funds transfer.

## Motivation

Malware targeting financial apps abuses the `AccessibilityService` API and overlay windows to read
screen content, inject input, conceal the screen, and complete transactions without the user.
Detecting this by enumerating enabled accessibility services and blocking on their presence has two
problems: it produces false positives for legitimate assistive technology (screen readers, switch
access, assistive-touch utilities), and it does not stop the abuse. Something like that?

`a11yguard` scores whether a specific protected action is being driven by automation and returns an
allow / step-up / block decision. The presence of an accessibility service is a low-weight input,
evidence of active automation of the protected action is the deciding factor. Spoofable per-event
signals are discounted **UNLESSS** a capable, non-tool, untrusted service is actually present, which keeps
false positives for legitimate assistive technology close to zero.

## Modules

| Module | Description |
| --- | --- |
| `a11yguard` | Detection library (AAR). Depends only on `androidx.annotation`. |
| `harness` | Instrumentation app for validating an integration. Test devices only. |

## Requirements

- `minSdk` 21, `compileSdk` 35
- JDK 17, Gradle 8.9, Android Gradle Plugin 8.5

## Integration

```kotlin
// Application.onCreate
val config = A11yGuardConfig.Builder()
    .allowlistServicePackages("com.vendor.assistivetouch")
    .applyAccessibilityDataSensitive(true)
    .debugLogging(BuildConfig.DEBUG)
    .build()
A11yGuard.init(this, config)
A11yGuard.enableAutoProtection(this)
A11yGuard.setIntegrityProvider { playIntegrityVerdict() } // optional, server-verified

// On a sensitive screen, after the view hierarchy is created
// Yes dev needs to mark this later as A11yGuard will be a PnP plugin for any dev to use/improve (ik the code may succs)
A11yGuard.markSensitive(usernameField, passwordField, confirmButton)

// At the decision point
when (A11yGuard.evaluate(SensitiveAction.TRANSFER).decision) {
    RiskDecision.ALLOW -> submit()
    RiskDecision.STEP_UP -> requireStepUpAuth()
    RiskDecision.BLOCK -> denyAndReport()
}
```

`RiskVerdict.contributions` exposes the per-signal breakdown for logging and audit. Weights and
thresholds are configurable through `A11yGuardConfig.Builder`.

## Design

- Automation is observed through a chained `View.AccessibilityDelegate`, so IME input and the app's
  own `setText` are never misclassified as automation.
- Injected gestures carry a framework-assigned device id that an attacker cannot forge. This, and the
  absence of genuine human input, are independent of timing and remain effective against automation
  that mimics human speed.
- Structural defenses are applied where the platform supports them: `accessibilityDataSensitive`
  (API 34+), `setHideOverlayWindows` (API 31+), and `setFilterTouchesWhenObscured`.
- `evaluate` fails open: if the library is not initialized it returns `ALLOW`, so a transaction is
  never blocked by a library error.

## Continuous evaluation (just like thread-ish)

`A11yGuard.startContinuousWatch` periodically re-evaluates risk on a background scheduler and delivers
verdicts to a listener on the main thread, covering automation that does not pass through an
instrumented decision point.

## Scope ?

`a11yguard` observes automation targeting the host app's own windows. Reconnaissance across other apps
is outside the scope of an in-process library; that is the responsibility of platform controls (Play
Integrity, Restricted Settings, AAPM or Android Advanced Protection Mode) or an on-device security agent.

## P, How to build?

```bash
./gradlew :a11yguard:assembleRelease
./gradlew :harness:assembleDebug
```

## Harness

The harness drives an integrated build and confirms detection. It includes a guarded sample screen
and a targeted console that can launch a named package, enumerate and act on resources, and record
replayable scenarios. It performs no network activity, exfiltration, or persistence and is intended
for test devices only.

## More notes

PRs r welcome!
