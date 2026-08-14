import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:thl_ussd_service/thl_ussd_service.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'THL USSD Service Demo',
      theme: ThemeData(
        brightness: Brightness.dark,
        primaryColor: const Color(0xFF1976D2),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF1976D2),
          secondary: Color(0xFF03DAC6),
        ),
      ),
      home: const UssdDemoPage(),
    );
  }
}

class UssdDemoPage extends StatefulWidget {
  const UssdDemoPage({super.key});

  @override
  _UssdDemoPageState createState() => _UssdDemoPageState();
}

enum UssdMode {
  silent,
  interactiveSingle,
  interactiveMulti,
}

class _UssdDemoPageState extends State<UssdDemoPage> {
  UssdMode _ussdMode = UssdMode.silent;
  bool _isAccessibilityEnabled = false;
  bool _isOverlayPermissionGranted = false;
  List<Map<String, dynamic>> _simCards = [];

  int _selectedSimSubscriptionId = 0;
  int _selectedSimSlotIndex = 0;

  final TextEditingController _codeController =
      TextEditingController(text: '*123#');
  final TextEditingController _optionsController =
      TextEditingController(text: '1, 2, 3');
  final TextEditingController _overlayMessageController =
      TextEditingController(text: 'USSD session running...');

  bool _loading = false;
  String _statusMessage = 'Ready';
  final List<String> _messageLogs = [];

  @override
  void initState() {
    super.initState();
    _checkStatusAndSims();
    UssdService.setUssdMessageListener((message) {
      setState(() {
        _messageLogs.add('Received: $message');
      });
    });
  }

  @override
  void dispose() {
    UssdService.removeUssdMessageListener();
    _codeController.dispose();
    _optionsController.dispose();
    _overlayMessageController.dispose();
    super.dispose();
  }

  Future<void> _checkStatusAndSims() async {
    final access = await UssdService.isAccessibilityEnabled();
    final overlay = await UssdService.isOverlayPermissionGranted();

    await Permission.phone.request();
    final sims = await UssdService.getSimCards();

    setState(() {
      _isAccessibilityEnabled = access;
      _isOverlayPermissionGranted = overlay;
      _simCards = sims;
      if (_simCards.isNotEmpty) {
        _selectedSimSubscriptionId = _simCards.first['subscriptionId'] ?? 0;
        _selectedSimSlotIndex = _simCards.first['slotIndex'] ?? 0;
      }
    });
  }

  Future<void> _executeUssd() async {
    setState(() {
      _loading = true;
      _messageLogs.clear();
      _statusMessage = 'Initiating request...';
    });

    final code = _codeController.text.trim();

    try {
      if (_ussdMode == UssdMode.silent) {
        _statusMessage = 'Running silent request...';
        final response =
            await UssdService.makeRequest(_selectedSimSubscriptionId, code);
        setState(() {
          _statusMessage = 'Success';
          _messageLogs.add('Response: $response');
        });
      } else if (_ussdMode == UssdMode.interactiveSingle) {
        if (!_isAccessibilityEnabled) {
          throw Exception("Accessibility Service must be enabled in settings.");
        }
        _statusMessage = 'Running interactive single request...';
        final response = await UssdService.sendUssdRequest(
          ussdCode: code,
          subscriptionId: _selectedSimSubscriptionId,
        );
        setState(() {
          _statusMessage = 'Success';
          _messageLogs.add('Result: $response');
        });
      } else {
        if (!_isAccessibilityEnabled) {
          throw Exception("Accessibility Service must be enabled in settings.");
        }
        _statusMessage = 'Running multi-session automation...';

        final optionsString = _optionsController.text.trim();
        final options = optionsString.isNotEmpty
            ? optionsString.split(',').map((e) => e.trim()).toList()
            : <String>[];

        await UssdService.multisessionUssd(
          code: code,
          slotIndex: _selectedSimSlotIndex,
          options: options,
          overlayMessage: _overlayMessageController.text.trim(),
        );

        setState(() {
          _statusMessage = 'Automation finished / Completed';
        });
      }
    } on PlatformException catch (e) {
      setState(() {
        _statusMessage = 'Failed with platform error';
        _messageLogs.add('Error [${e.code}]: ${e.message}');
      });
    } catch (e) {
      setState(() {
        _statusMessage = 'Failed with error';
        _messageLogs.add('Error: ${e.toString()}');
      });
    } finally {
      setState(() {
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('THL USSD Service'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _checkStatusAndSims,
          )
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status Card
            Card(
              elevation: 4,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('Permissions & Services',
                        style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            color: Colors.blue)),
                    const SizedBox(height: 12),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text('Accessibility Service:'),
                        Row(
                          children: [
                            Icon(
                              _isAccessibilityEnabled
                                  ? Icons.check_circle
                                  : Icons.error,
                              color: _isAccessibilityEnabled
                                  ? Colors.green
                                  : Colors.red,
                            ),
                            const SizedBox(width: 8),
                            ElevatedButton(
                              onPressed: () async {
                                await UssdService.openAccessibilitySettings();
                              },
                              child: const Text('Settings'),
                            ),
                          ],
                        )
                      ],
                    ),
                    const SizedBox(height: 12),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text('Overlay Permission:'),
                        Row(
                          children: [
                            Icon(
                              _isOverlayPermissionGranted
                                  ? Icons.check_circle
                                  : Icons.warning,
                              color: _isOverlayPermissionGranted
                                  ? Colors.green
                                  : Colors.orange,
                            ),
                            const SizedBox(width: 8),
                            ElevatedButton(
                              onPressed: () async {
                                await UssdService.openOverlaySettings();
                              },
                              child: const Text('Settings'),
                            ),
                          ],
                        )
                      ],
                    )
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // SIM selector
            Card(
              elevation: 4,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('SIM Card Selection',
                        style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            color: Colors.blue)),
                    const SizedBox(height: 12),
                    if (_simCards.isEmpty)
                      const Text(
                          'No SIM cards detected or permissions missing. Click refresh at the top.')
                    else
                      DropdownButtonFormField<int>(
                        initialValue: _selectedSimSubscriptionId,
                        decoration:
                            const InputDecoration(labelText: 'Select SIM'),
                        items: _simCards.map((sim) {
                          return DropdownMenuItem<int>(
                            value: sim['subscriptionId'],
                            child: Text(
                                '[Slot ${sim['slotIndex']}] ${sim['displayName']} (${sim['carrierName']})'),
                          );
                        }).toList(),
                        onChanged: (val) {
                          if (val != null) {
                            final selected = _simCards.firstWhere(
                                (element) => element['subscriptionId'] == val);
                            setState(() {
                              _selectedSimSubscriptionId = val;
                              _selectedSimSlotIndex =
                                  selected['slotIndex'] ?? 0;
                            });
                          }
                        },
                      )
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Mode Selector
            Card(
              elevation: 4,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('Execution Mode',
                        style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            color: Colors.blue)),
                    const SizedBox(height: 12),
                    RadioListTile<UssdMode>(
                      title: const Text('Silent Request (TelephonyManager)'),
                      subtitle: const Text(
                          'Completely silent. Android 8+. Only standard *...# codes.'),
                      value: UssdMode.silent,
                      groupValue: _ussdMode,
                      onChanged: (val) => setState(() => _ussdMode = val!),
                    ),
                    RadioListTile<UssdMode>(
                      title: const Text(
                          'Interactive Single Request (Accessibility)'),
                      subtitle:
                          const Text('Launches Dialer. Works with #101#451#.'),
                      value: UssdMode.interactiveSingle,
                      groupValue: _ussdMode,
                      onChanged: (val) => setState(() => _ussdMode = val!),
                    ),
                    RadioListTile<UssdMode>(
                      title: const Text(
                          'Interactive Multi-step Request (Accessibility)'),
                      subtitle: const Text(
                          'Sends multiple menu options sequentially.'),
                      value: UssdMode.interactiveMulti,
                      groupValue: _ussdMode,
                      onChanged: (val) => setState(() => _ussdMode = val!),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // USSD parameters input
            Card(
              elevation: 4,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('Parameters',
                        style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            color: Colors.blue)),
                    const SizedBox(height: 12),
                    TextField(
                      controller: _codeController,
                      decoration: const InputDecoration(
                        labelText: 'USSD Code',
                        hintText: 'e.g. *123# or #101#451#',
                        border: OutlineInputBorder(),
                      ),
                    ),
                    if (_ussdMode == UssdMode.interactiveMulti) ...[
                      const SizedBox(height: 12),
                      TextField(
                        controller: _optionsController,
                        decoration: const InputDecoration(
                          labelText: 'Menu Options (comma separated)',
                          hintText: 'e.g. 1, 2, 3',
                          border: OutlineInputBorder(),
                        ),
                      ),
                      const SizedBox(height: 12),
                      TextField(
                        controller: _overlayMessageController,
                        decoration: const InputDecoration(
                          labelText: 'Overlay Message',
                          border: OutlineInputBorder(),
                        ),
                      ),
                    ]
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),

            // Execution Button
            ElevatedButton(
              onPressed: _loading ? null : _executeUssd,
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
                backgroundColor: const Color(0xFF1976D2),
                foregroundColor: Colors.white,
              ),
              child: _loading
                  ? const SizedBox(
                      width: 24,
                      height: 24,
                      child: CircularProgressIndicator(
                          color: Colors.white, strokeWidth: 2),
                    )
                  : const Text('Execute USSD',
                      style:
                          TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            ),
            const SizedBox(height: 24),

            // Outputs and Logs
            Card(
              color: Colors.black45,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text('Status Logs',
                            style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color: Colors.grey)),
                        Text(_statusMessage,
                            style: const TextStyle(
                                fontWeight: FontWeight.bold,
                                color: Colors.blue)),
                      ],
                    ),
                    const Divider(height: 24, color: Colors.grey),
                    if (_messageLogs.isEmpty)
                      const Text(
                          'No activity yet. Run a code to see responses here.',
                          style: TextStyle(
                              fontStyle: FontStyle.italic, color: Colors.grey))
                    else
                      ..._messageLogs.map((log) => Padding(
                            padding: const EdgeInsets.only(bottom: 8.0),
                            child: Text(log,
                                style:
                                    const TextStyle(fontFamily: 'monospace')),
                          )),
                  ],
                ),
              ),
            )
          ],
        ),
      ),
    );
  }
}
