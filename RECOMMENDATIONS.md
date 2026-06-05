# Defending Against Accessibility and Overlay Abuse

Guidance for Android apps that handle sensitive actions (authentication, funds transfer) and need to
defend against malware that abuses the `AccessibilityService` API and overlay windows to read the
screen, inject input, conceal activity, and complete transactions on the device.

## Principle

Do not enumerate accessibility services and block on their presence. Move from "is accessibility
enabled?" to "is this specific protected action being driven by automation?". Presence of an
accessibility service is a low-weight input; active automation of the protected action is the trigger.
Respond with step-up, not a hard exit.

## Why presence-based blocking fails

- It produces false positives. Screen readers, switch access, voice access, and assistive-touch
  utilities are legitimate and indistinguishable from malware at the permission layer; blocking on
  presence locks out users who depend on assistive technology.
- It does not stop the abuse. Malware can still scrape and inject; a forced exit only degrades the
  experience for legitimate users.
- The robust distinctions are behavioral (is the action human-driven or automated?) and structural
  (prevent the abuse where the platform allows).

## Layered model

| Layer | Mechanism | Why it avoids false positives |
| --- | --- | --- |
| Structural prevention | `accessibilityDataSensitive` on sensitive views (API 34+), `setHideOverlayWindows` (API 31+), `setFilterTouchesWhenObscured` (API 9+) | Prevents abuse rather than guessing; nothing to misclassify |
| Behavioral risk engine | Score active automation at the decision point: absence of genuine human input, automated node actions, synthetic input device id, overlay concealment, scrape rate | The deciding signals reflect automation, not presence; spoofable signals are discounted unless an untrusted capable service is present |
| Continuous evaluation | Periodic background risk sampling for automation that never reaches an instrumented decision point | Same scoring and discounting; only escalates on real automation |
| Platform integrity | Play Integrity (server-verified), Restricted Settings (API 33+), Advanced Protection Mode (API 36+) | Advisory inputs, not the block trigger |
| Server and out-of-band | Backend fraud signals and out-of-band transaction confirmation | Survives a fully compromised device |

## Decision policy

Return one of three results at each sensitive action; do not perform a silent forced exit.

```
ALLOW    proceed normally
STEP_UP  require re-authentication or out-of-band confirmation
BLOCK    deny and report, only on corroborated active automation
```

- Step-up at medium confidence keeps false positives invisible to legitimate users.
- Hard block only when multiple automation signals corroborate; no single signal should block.
- Fail open: if the detector is not initialized, allow the action rather than break it.

## Structural controls (apply first)

Mark only the sensitive views, not whole screens, to preserve screen-reader use elsewhere.

```kotlin
// API 34+: prevents services that are not declared accessibility tools from reading or acting on the view.
view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
// API 9+: tapjacking protection; on API 34+ also marks the view accessibility-sensitive.
view.setFilterTouchesWhenObscured(true)
// API 31+: hide non-system overlays over this window.
window.setHideOverlayWindows(true)
```

`FLAG_SECURE` is not sufficient on its own: it blocks screenshots and `MediaProjection` capture, but
not accessibility tree reads. Use `accessibilityDataSensitive` for read and action protection. On
versions below API 34 the behavioral engine is the compensating control.

## Behavioral signals

- Detect automation, not presence. Node actions (`ACTION_SET_TEXT`, `ACTION_PASTE`, `ACTION_CLICK`)
  are observed directly through a chained delegate and never fire for IME input or the app's own
  `setText`. Injected gestures carry a framework-assigned device id that cannot be forged.
- Be pace-independent. Automation that mimics human timing only defeats the low-weight pacing signal;
  device id, absence of genuine human input, and node-action detection remain effective.
- Discount spoofable signals unless a capable, non-tool, untrusted-installer, non-allow-listed
  service is actually present. Allow-list trusted assistive vendors; treat screen readers and switch
  access as benign.

## Version matrix

| API | Control |
| --- | --- |
| 21+ | Behavioral engine; `setFilterTouchesWhenObscured` |
| 30 | `verifyInputEvent`; Play Integrity |
| 31 | `setHideOverlayWindows` |
| 33 | Restricted Settings (OS blocks sideloaded services by default) |
| 34 | `accessibilityDataSensitive` on sensitive views |
| 36+ | Advanced Protection Mode (restricts non-tool apps from the accessibility API) |

## Do and do not

Do:

- Treat accessibility as a signal feeding a risk decision, scoped to the sensitive action.
- Prefer structural prevention over detection.
- Use step-up, allow-list legitimate vendors, and log every verdict (without PII) for tuning.
- Keep the primary control server-side with out-of-band confirmation.

Do not:

- Enumerate enabled services and force-exit on presence.
- Block screen readers, switch access, or voice access.
- Rely on a single hidden flag, on `FLAG_SECURE` for accessibility reads, or on timing alone.
- Hard-fail on a single signal; require corroboration and default to step-up.

## Scope

In-process detection observes automation against the host app's own windows. Reconnaissance across
other apps is outside the scope of an in-app library and belongs to platform controls (Play
Integrity, Restricted Settings, Advanced Protection Mode) or an on-device security agent. Defense is
layered: app, platform, and server.

## Minimum viable

`accessibilityDataSensitive` and `setHideOverlayWindows` on sensitive screens; a behavioral
evaluation at login and transfer that returns step-up rather than block; Play Integrity feeding the
verdict; and server-side out-of-band confirmation. This removes the false-positive lock-outs and
addresses on-device automation without asking whether accessibility is enabled.
