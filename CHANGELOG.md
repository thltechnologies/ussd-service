## 0.2.2

- Reverted legacy slot configurations in `sendUssdRequestLegacy` to address dialing stability issues.
- Simplified and improved the layout gravity and view placement in `UssdOverlayService`.

## 0.2.1

- Automatic fallback for non-standard USSD syntax (e.g. `#101#451#`) in single-session requests to bypass Android `USSD_RETURN_FAILURE` limitations.
- Automatic native dialog dismissal (`dismissActiveDialog`) after capturing USSD responses via Accessibility Service.
- Integrated overlay service for single-session requests to mask system dialogs during execution.
- Added `singleSessionMode` flag to reliably handle overlay teardown and popup cleanup.

## 0.2.0

- Integrated `ussd_launcher` features into `thl_ussd_service` under a unified API surface.
- Added Dialer-based Interactive USSD requests (`sendUssdRequest`) using Android's Accessibility Service to handle codes with special syntax (like `#101#451#`).
- Added Multi-step USSD menu automation (`multisessionUssd`) to navigate interactive menus automatically.
- Added Overlay Service (`UssdOverlayService`) to display a fullscreen loading calque and hide system dialogs.
- Added helper APIs to query active SIM cards (`getSimCards`), check/open accessibility settings, and check/open overlay settings.
- Re-implemented the native library unifications in Kotlin.

## 0.1.0+1

## 0.1.0

- Renamed package to `thl_ussd_service` under `com.thltechnologies.ussd_service` namespace.
- Upgraded package compatibility to Dart 3 and recent Flutter versions.
- Updated Android build configurations to use modern Kotlin DSL and Android Gradle Plugin 9.0+.
- Removed outdated `sim_data` dependency in example app.

## 0.1.1

Adds support for sound null safety

## 0.1.0+4

Fixes bug where Android Exception wasn't propagated to Dart

## 0.1.0+3

Recommends using plugin sim_data instead of sim_service

## 0.1.0+2

Adds support for subscription ID with a value of 0

## 0.1.0+1

Fixes Exception thrown when accessing deprecated package

## 0.1.0

Adds optional timeout parameter and permission checks on Android side

## 0.0.4+1

Adds guidance in README about interactive / multi steps USSD sessions

## 0.0.4

Upgrades to Android embedding V2 after release of Flutter 1.12 stable

## 0.0.3+2

Adds CHANGELOG entry

## 0.0.3+1

Removes pubspec.lock

## 0.0.3

Improves documentation and example project

## 0.0.2

Fixes AndroidManifest.xml unnecessary declaration.

## 0.0.1 - 2019-10-02

First working version of the plugin.
