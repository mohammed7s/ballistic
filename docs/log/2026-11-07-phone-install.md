# First physical-phone installation — 7 November 2026

The owner connected the Pixel 10 Pro to the ThinkPad over USB and authorized installation
and a startup check. ADB reported an authorized physical Pixel 10 Pro, Android 17, ARM64.
All phone commands used its explicit serial; the separate emulator was not targeted.

The model-setup debug APK was copied from the Latitude to the ThinkPad at
`~/ballistic_runs/phone_setup_20261107/Ballistic-model-setup-debug.apk`.
The transferred file passed SHA-256 verification:
`8120c39a4218887bb1a4f2e0147c628b71ed45a1f81f3286245d0553c17754a1`.

`adb install -r` returned `Success`. Starting `app.ballistic.assistant/.MainActivity`
returned `Status: ok`; the app process was present. Android displayed the first-run
"Allow Ballistic to send you notifications?" prompt. A UI-tree dump confirmed that
prompt; the short app-process-filtered AndroidRuntime/Ballistic log query returned no errors.

This establishes installation and startup as far as the permission prompt on the physical
phone. It does not yet establish successful biometric unlock, model download/load, local
generation, or an agent task on the phone. The owner was given the next steps: dismiss the
notification prompt, unlock Ballistic with a biometric, then Local AI → Download & set up.
Harness reliability work remains paused.
