# Ballistic

**An AI assistant app for Android, built to keep you in control.**

Tell Ballistic what you want done on your phone. The goal is to handle everyday tasks across
apps, run models on the device, and ask for your fingerprint before sensitive actions.
Ballistic is an app; its planning, models and safety rules stay in the app.

**Early prototype:** the agent runs in an emulator harness. A native Android APK has demonstrated
opening Clock and stopping a run. General phone automation, on-device inference and the full
safety design are not ready for everyday use.

## What we are building

- An assistant that can work across apps using the capabilities your phone exposes.
- Local inference, with cloud use an explicit choice.
- Approval for the exact action being taken, and checks that it actually happened.
- Reusable workflows for repetitive tasks.

These are product goals. The current prototype can use a configured remote model; requests and
screen context then leave the device. See [implementation status](core/README.md) and
[Android app status](docs/ANDROID_APP.md) for what is implemented and tested.

## Where it can run

The prototype APK targets Android 13 and later, with limited actions as an ordinary app.
Installing it does not grant access to other apps' screens.

Full screen automation needs platform support. Our first integration target is GrapheneOS with
[ballistic-os](https://github.com/mohammed7s/ballistic-os), a separate OS patch and build project.
If stock Android exposes suitable APIs to third-party assistants, Ballistic can gain an adapter
for them without moving its brain into the OS. That compatibility is a goal, not a release promise.
See [platform support](docs/PLATFORM.md).

## Try the prototype

Start with the [Android build guide](apps/assistant/README.md#build) or the
[emulator harness](harness/README.md). With the Android build prerequisites installed:

```bash
cd apps/assistant
scripts/build_core.sh
./gradlew :app:assembleDebug
```

The app distinguishes the real Rust engine from its scripted demo. Check that distinction before
judging a run. The harness supports terminal prompts and repeatable benchmark tasks.

## Find your way around

- [apps/assistant](apps/assistant/README.md): native Android app.
- [core](core/README.md): shared Rust agent and safety logic.
- [harness](harness/README.md): emulator, benchmarks and replay.
- [docs](docs/README.md): app specification, decisions, roadmap and research.

Contributions are welcome, especially reproducible failures, device measurements and safety tests.

Apache-2.0. Maintainer: [@mohammed7s](https://github.com/mohammed7s).
Not affiliated with or endorsed by GrapheneOS.
