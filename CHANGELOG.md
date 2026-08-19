## 0.3.0

- Added smart detection for intermediate prompt dialogs vs. terminal receipt dialogs (`isPromptDialogContent` and `isTerminalDialogContent`).
- Improved `hideDialog` behavior to only auto-dismiss native dialogs upon detecting a confirmed terminal/final receipt message, keeping intermediate prompts open for user interaction.
- Added `PROMPT_DIALOG:` response prefix for interactive screens with input fields.
- Added dynamic keyboard (IME) and system package filtering via `InputMethodManager` to prevent false session completion while the user is typing.
- Enhanced native dialog dismissal with multi-strategy fallback (standard button IDs, button labels, and system back action) and automatic host app foregrounding (`bringAppToFront`).
- Optimized `UssdMultiSession` to keep sessions open for interactive user input after automated options are processed instead of prematurely terminating.

## 0.2.3

- Added `hideDialog` parameter to `sendUssdRequest` and `multisessionUssd` to control the visibility of native USSD system dialogs.
- Updated Kotlin native service (`UssdAccessibilityService`, `UssdServicePlugin`, `ussd_multi_session_controller`) to dynamically toggle native dialog hiding based on `hideDialog`.
- Updated example application UI to allow toggling native USSD dialog visibility during execution.

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
