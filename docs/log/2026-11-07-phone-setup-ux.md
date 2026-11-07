# Physical-phone setup diagnosis and UX — 7 November 2026

The owner reported that Download & set up appeared to do nothing and that the lock/unlock
purpose was unclear. The physical Pixel 10 Pro showed an authenticated Ballistic session,
no installed model and "Cannot reach the model host" above the starter card. Android reported
`Active default network: none`; resolving `huggingface.co` failed. Ballistic's INTERNET
permission was granted. This failure was connectivity, not missing Computer Control permission.

After the owner connected Wi-Fi, Android reported an active default network and Ballistic's
managed `import.partial` grew. The owner independently reported the moving progress bar. The
old one-decimal GiB counter stayed at 0.0 through approximately the first 54 decimal MB; that
rounding made a working transfer appear stalled.

Changes:

- Direct biometric authentication from Local AI and Settings, with session-purpose wording.
- A locked setup button starts authentication, then presents download consent; it cannot download
  without authentication. Pending Ask requests and busy operations still block model changes.
- Scrollable download confirmation content, with errors adjacent to the setup control.
- Download progress in decimal MB and integer percent; network error messages distinguish DNS,
  timeout and TLS failures without exposing exception payloads or redirected URLs.
- Screen stays awake during local setup while the app is visible. No background-download or
  resume support is implied. Command-gate relocking and sensitive-action approval are unchanged.

The initial authentication/wording update was installed on the Pixel. The later error/progress
update was built and transferred to the ThinkPad but is held until the active download ends,
because replacing the APK would interrupt a non-resumable transfer.

Validation: Android debug build passed; download tests: 8 passed, 1 optional live-download test
skipped, no failures/errors. Final progress-label edit was compiled in a subsequent successful
APK build. Artifact: `ballistic_runs/phone_setup_fix_20261107/Ballistic-setup-fix-debug.apk`,
SHA-256 `241e85bea84f78037ca41da69a2e9e6213b2768fbee54a49a51ba0634633824a`.
This record establishes a working phone transfer after Wi-Fi connection, not completed model
verification/loading/inference or end-to-end phone automation.
