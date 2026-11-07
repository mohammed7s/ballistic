# App access cleanup — 8 November 2026

The owner asked whether app access rules belong in phone Settings or Ballistic and requested
stronger, clearer controls. SPEC §4.3–4.4 assigns app policy to Ballistic and platform grants to
the OS. No OS repository or build server was changed; harness reliability work remains paused.

## Findings and changes

The previous settings table exposed tree/full access despite this host only implementing
notification observations and open_app/wait. Unconfigured apps inherited broad observation and
action defaults. NotificationScreen did not consult per-app policy before constructing nodes,
and dispatch relied on advertised app grants rather than its own permission check.

- Missing, malformed or unreadable policy denies notification observation and app actions;
  model routing defaults to device-only. Explicit serialized grants remain; omitted fields
  receive the new restrictive defaults. No blanket grant was installed on the test phone.
- Android Settings remains the location for Ballistic's system permissions, with a direct
  app-details link. Notification listener access and task notification display are explained
  separately. Local inference and opening apps need no notification listener grant when no
  notification sources are allowed.
- Ballistic's searchable, expandable app rules offer Blocked/Notifications for reading and
  Blocked/Open app for actions, plus routing and approval settings. Legacy screen/full settings
  are labeled as such and do not expose extra capabilities.
- Saving a rule displays the immutable proposed settings in an Android BIOMETRIC_STRONG prompt,
  with no device-PIN fallback. Success checks fresh unlocked/idle state and unchanged prior
  policy before saving. Denial/cancellation/error makes no write. This uses a system biometric
  prompt; it is separate from the existing signed sensitive-operation approval mechanism.
- Notification sources are filtered before reading their extras and constructing nodes.
  Connected inference omits device-only sources rather than silently routing their content to
  another model. If no sources are allowed, the adapter does not fetch notifications at all.
- Installed-app discovery advertises only action-granted apps. PolicyHands independently checks
  the saved launch grant, applies every-action biometric approval, and rechecks cancellation
  and policy after that approval before delegating. Unsupported operations fail closed.

The notification listener's Android grant is still broad process access. These filters constrain
assistant inputs, not what the OS can deliver to the listener process. OS sandboxing, network
revocation and app integrity remain separate boundaries. Future routes must enforce the same
policy; this work does not establish general screen control or full security validation.

GrapheneOS documents its ordinary app sandbox/permission model and added
[Network permission](https://grapheneos.org/features#network-permission-toggle). The Network
toggle gives an OS-enforced offline boundary; it also prevents model downloads/connected
inference. Ballistic does not change that permission automatically.

## Validation

Offline Android debug build passed. Nineteen selected JVM tests passed, with zero failures,
errors or skips: PolicyTest (7), PolicyStoreTest (1), AppAccessTest (5), PromptSessionTest (6).
Coverage includes missing/corrupt policy, independent read/action consent, device-only exclusion
from connected inference, denial at dispatch, every-action approval denial, Stop/revocation
during approval, unsupported-operation rejection and existing confirmation floors.

Installed on the USB-connected Pixel through the ThinkPad; model files were preserved. Artifact:
`ballistic_runs/phone_access_20261108/Ballistic-access-debug.apk`, 54,132,290 bytes; SHA-256
`63467c0123b8d1b94321d1b4c3465bdb146343ff610f25a5c56f69ee8de3bf4e`.
The initial UI check found the phone asleep/locked and requested owner unlock. Physical biometric
success, cancellation and route-filter behavior are not established by these unit tests/build.
