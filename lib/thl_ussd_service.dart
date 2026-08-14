import 'dart:async';

import 'package:flutter/services.dart';

class UssdService {
  static const MethodChannel _channel =
      MethodChannel("com.thltechnologies.ussd_service/plugin_channel");

  /// Performs a silent USSD request and returns the response (without Accessibility Service).
  /// Only supports Android 8.0+ and compliant USSD codes starting with `*` and ending with `#`.
  static Future<String> makeRequest(
    int subscriptionId,
    String code, [
    Duration timeout = const Duration(seconds: 10),
  ]) async {
    final String response = await _channel
        .invokeMethod(
          "makeRequest",
          {"subscriptionId": subscriptionId, "code": code},
        )
        .timeout(timeout)
        .catchError((e) {
          if (e is TimeoutException) {
            throw PlatformException(
                code: "ussd_plugin_ussd_execution_timeout", message: e.message);
          }
          throw e;
        });
    return response;
  }

  /// Launches a single-session USSD request via the system dialer (requires Accessibility Service).
  /// Works for any USSD code formatting (including double `#` codes like `#101#451#`).
  static Future<String?> sendUssdRequest({
    required String ussdCode,
    required int subscriptionId,
    bool hideDialog = true,
  }) async {
    try {
      final String? response = await _channel.invokeMethod('sendUssdRequest', {
        'ussdCode': ussdCode,
        'subscriptionId': subscriptionId,
        'hideDialog': hideDialog,
      });
      return response;
    } on PlatformException catch (e) {
      rethrow;
    }
  }

  /// Launches a multi-step USSD session with automatic menu navigation (requires Accessibility Service).
  ///
  /// [code] - The initial USSD code to dial
  /// [slotIndex] - The SIM slot index (0 for first SIM, 1 for second)
  /// [options] - List of menu options to select automatically
  /// [initialDelayMs] - Delay before sending first option (default: 3000ms)
  /// [optionDelayMs] - Delay between options (default: 2500ms)
  /// [overlayMessage] - Custom message to display on the overlay during the session
  static Future<void> multisessionUssd({
    required String code,
    required int slotIndex,
    List<String> options = const [],
    int? initialDelayMs,
    int? optionDelayMs,
    String? overlayMessage,
    bool hideDialog = true,
  }) async {
    try {
      await _channel.invokeMethod('multisessionUssd', {
        'ussdCode': code,
        'slotIndex': slotIndex,
        'options': options,
        if (initialDelayMs != null) 'initialDelayMs': initialDelayMs,
        if (optionDelayMs != null) 'optionDelayMs': optionDelayMs,
        if (overlayMessage != null) 'overlayMessage': overlayMessage,
        'hideDialog': hideDialog,
      });
    } on PlatformException catch (e) {
      rethrow;
    }
  }

  /// Sets a listener for USSD messages received during a session.
  ///
  /// [listener] - Callback function that receives USSD message strings.
  /// This is essential for receiving intermediate responses in multi-session USSD.
  static void setUssdMessageListener(void Function(String message) listener) {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onUssdMessageReceived') {
        final String ussdMessage = call.arguments as String;
        listener(ussdMessage);
      }
    });
  }

  /// Removes the USSD message listener.
  static void removeUssdMessageListener() {
    _channel.setMethodCallHandler(null);
  }

  /// Gets information about available SIM cards on the device.
  static Future<List<Map<String, dynamic>>> getSimCards() async {
    try {
      final List<dynamic> result = await _channel.invokeMethod('getSimCards');
      return result.map((item) => Map<String, dynamic>.from(item)).toList();
    } on PlatformException catch (e) {
      print("UssdService: Error getting SIM cards: ${e.message}");
      return [];
    }
  }

  /// Checks if the accessibility service is enabled for this app.
  static Future<bool> isAccessibilityEnabled() async {
    try {
      final bool isEnabled =
          await _channel.invokeMethod('isAccessibilityEnabled');
      return isEnabled;
    } on PlatformException catch (e) {
      print("UssdService: Error checking accessibility: ${e.message}");
      return false;
    }
  }

  /// Opens the system accessibility settings page.
  static Future<void> openAccessibilitySettings() async {
    try {
      await _channel.invokeMethod('openAccessibilitySettings');
    } on PlatformException catch (e) {
      print("UssdService: Error opening accessibility settings: ${e.message}");
    }
  }

  /// Cancels the current interactive USSD session.
  static Future<void> cancelSession() async {
    try {
      await _channel.invokeMethod('cancelSession');
    } on PlatformException catch (e) {
      print("UssdService: Error cancelling session: ${e.message}");
    }
  }

  /// Checks if the overlay drawing permission (SYSTEM_ALERT_WINDOW) is granted.
  static Future<bool> isOverlayPermissionGranted() async {
    try {
      final bool isGranted =
          await _channel.invokeMethod('isOverlayPermissionGranted');
      return isGranted;
    } on PlatformException catch (e) {
      print("UssdService: Error checking overlay permission: ${e.message}");
      return false;
    }
  }

  /// Opens the system overlay permission settings page.
  static Future<void> openOverlaySettings() async {
    try {
      await _channel.invokeMethod('openOverlaySettings');
    } on PlatformException catch (e) {
      print("UssdService: Error opening overlay settings: ${e.message}");
    }
  }
}
