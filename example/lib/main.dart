import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';
import 'package:ringkit/ringkit.dart';

/// §10.4 background handler — a TOP-LEVEL `@pragma('vm:entry-point')` function
/// that runs in a headless isolate when an actionable event (decline/timeout/
/// cancel) arrives while the app is dead. It POSTs to a local sink so the
/// killed-state harness can prove decline-while-dead reached a "server" with no
/// app launch (README "how to test killed-state"; adb reverse tcp:8099).
///
/// It must not throw uncaught (an uncaught throw would skip the completion ack
/// and force a replay); every failure is swallowed best-effort.
@pragma('vm:entry-point')
Future<void> ringkitExampleBackgroundHandler(CallEvent event) async {
  try {
    final client = HttpClient();
    final request = await client.postUrl(
      Uri.parse('http://localhost:8099/event'),
    );
    final body = utf8.encode(
      jsonEncode(<String, Object?>{
        'type': event.runtimeType.toString(),
        'callId': event.call.id,
        'sequence': event.sequence,
        'deliveryPath': event.deliveryPath.name,
      }),
    );
    request.headers.contentType = ContentType.json;
    request.contentLength = body.length; // force Content-Length (no chunking)
    request.add(body);
    await request.close();
    client.close();
  } catch (_) {
    // Best-effort: the sink may be absent outside the harness.
  }
}

/// Mode 2 (§7.3) toggle for this example. `true` = the native
/// `RingKitMessagingReceiver` is enabled in AndroidManifest.xml and rings `rk.*`
/// pushes natively (< 300 ms, Dart off the critical path); the Dart handlers below
/// then GUARD `rk.*` out (native already rang it — it co-delivers to Dart via
/// FlutterFire's receiver). Flip to `false` for the Mode-1 baseline (Dart rings
/// `rk.*` via `dispatchRingPush`) AND drop the receiver's enable override from the
/// manifest. Keep the two in sync.
const bool kMode2NativeFcm = true;

/// Shared RingKit config used by both the foreground app (`_start`) and the
/// Mode-1 FCM background isolate — they MUST configure identically.
RingKitConfig ringkitExampleConfig() => RingKitConfig(
      incomingChannelName: 'Incoming calls',
      missedChannelName: 'Missed calls',
      ongoingChannelName: 'Ongoing call',
      audibleChannelName: 'Incoming calls (fallback)',
      defaultTimeoutSeconds: 45,
      connectionTimeoutSeconds: 120,
      maxConcurrentRinging: 3,
      telecomIntegration: false,
      manageOngoingCall: true,
      foregroundBehavior: ForegroundBehavior.headsUp,
      notificationIdBase: 424200,
      logLevel: RingKitLogLevel.info,
      channelSuffix: '',
    );

/// §7.2 Mode 1 — the app's OWN `firebase_messaging` background handler. Runs in a
/// headless isolate when a push arrives while the app is backgrounded/killed. A
/// ringkit call push (rk.* keys, §7.5) is initialized + rung here; any other
/// message is the app's own concern. Must be top-level + `@pragma('vm:entry-point')`.
/// NEVER await network before showIncomingCall — every awaited ms spends the
/// FGS-from-background exemption window (§4.2).
@pragma('vm:entry-point')
Future<void> firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  final data = message.data.map((k, v) => MapEntry(k, '$v'));
  if (RingKit.isRingMessage(data)) {
    // Mode 2 (§7.3): the native c2dm RingKitMessagingReceiver already rang this
    // rk.* push before Dart even started; it co-delivers here via FlutterFire's
    // receiver, so guard it out — do NOT ring again. Mode 1 rings it from Dart.
    if (kMode2NativeFcm) return;
    await RingKit.initialize(ringkitExampleConfig());
    await dispatchRingPush(data, message.sentTime);
    return;
  }
  // Not a ringkit push — the app's own message handling belongs here (both modes).
  // Proves coexistence (§7.3): non-rk messages still reach the app's Dart handler.
  debugPrint('[app msg] non-rk background push → app handles it: ${data.keys.toList()}');
}

/// Maps a §7.5 rk.* payload to a RingKit action — the Mode-1 Dart mirror of the
/// native RkWireParser (parity across modes). Shared by the background isolate
/// and the foreground `onMessage` listener.
Future<void> dispatchRingPush(Map<String, String> data, DateTime? sentTime) async {
  final callId = data['rk.callId'];
  if (callId == null || callId.isEmpty) return;
  if (data['rk.type'] == 'cancel') {
    await RingKit.cancelCall(
      callId,
      reason: switch (data['rk.reason']) {
        'answeredElsewhere' => CancelReason.answeredElsewhere,
        'declinedElsewhere' => CancelReason.declinedElsewhere,
        _ => CancelReason.callerCancelled,
      },
    );
    return;
  }
  final name = data['rk.callerName'];
  await RingKit.showIncomingCall(
    IncomingCall(
      id: callId,
      callerName: (name != null && name.isNotEmpty) ? name : 'Unknown caller',
      callerHandle: data['rk.callerHandle'],
      avatarUrl: data['rk.avatarUrl'],
      type: data['rk.callType'] == 'video' ? CallType.video : CallType.audio,
      timeoutSeconds: int.tryParse(data['rk.timeoutSeconds'] ?? ''),
      sentAtEpochMs: int.tryParse(data['rk.sentAt'] ?? '') ??
          sentTime?.millisecondsSinceEpoch,
      extra: const <String, String>{},
      android: AndroidCallOptions(
        vibrate: data['rk.vibrate'] != 'false',
        lockScreenVisibility: NotificationVisibility.public,
        lockScreenBehavior: data['rk.lockScreenBehavior'] == 'notificationOnly'
            ? LockScreenBehavior.notificationOnly
            : LockScreenBehavior.fullScreen,
      ),
    ),
  );
}

// Full §15 manual test harness + integration reference (M3.7):
// - Harness tab: every §15 button — show now/5s/15s, audio/video, avatar
//   (+auth headers), cancel-push per reason, out-of-order cancel, second
//   concurrent call, second call while in-call, end all, in-app answer UI,
//   outgoing leg (start/connect/hang up), updateCallInfo, lockScreenBehavior
//   toggle — plus the live event console (deliveryPath + seq badges,
//   degradation set on every ShowCallResult) and a full session history.
// - Diagnostics tab: diagnose() as a fix-it checklist with deep-link buttons,
//   event-log health, last show/delivery breadcrumbs, ring-trace viewer.
// - Permissions tab: snapshot + the recommended onboarding flow (§11.2).
// - Latency probe: SIMULATE_CALL stamps probeReceiptMs into extra; the
//   CallRinging event's occurredAt minus the stamp prints as the
//   receipt→transition latency line (§14.3 gate measurement).
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // FCM path (§7.2 Mode 1 / §7.3 Mode 2). Init Firebase + register the app's own
  // background message handler. `kMode2NativeFcm` above governs whether rk.* rings
  // via the native receiver (Mode 2) or from Dart here (Mode 1); either way the
  // handler must be registered so the app's OWN (non-rk) messages reach Dart.
  // Guarded so the SIMULATE_CALL harness still boots without Firebase configured.
  try {
    await Firebase.initializeApp();
    FirebaseMessaging.onBackgroundMessage(firebaseMessagingBackgroundHandler);
  } catch (e) {
    debugPrint('[fcm] Firebase init skipped: $e');
  }
  // §10.4: register the headless background handler (persists the 64-bit handle
  // natively) BEFORE any call can be declined-while-dead. Re-registered every
  // launch (handles are not stable across app updates).
  RingKit.setBackgroundEventHandler(ringkitExampleBackgroundHandler);
  runApp(const RingKitExampleApp());
}

class RingKitExampleApp extends StatelessWidget {
  const RingKitExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      theme: ThemeData(colorSchemeSeed: Colors.teal, useMaterial3: true),
      home: const HarnessHome(),
    );
  }
}

class HarnessHome extends StatefulWidget {
  const HarnessHome({super.key});

  @override
  State<HarnessHome> createState() => _HarnessHomeState();
}

class _HarnessHomeState extends State<HarnessHome> {
  final List<String> _console = <String>[];
  final List<String> _history = <String>[]; // full session event history
  final List<ActiveCall> _active = <ActiveCall>[];
  String? _selectedCallId;
  bool _notificationsGranted = false;
  bool _notificationOnly = false;
  int _counter = 0;
  int _tab = 0;

  DiagnosticsReport? _report;
  PermissionSnapshot? _snapshot;

  @override
  void initState() {
    super.initState();
    _start();
  }

  void _log(String line) {
    debugPrint('[harness] $line');
    if (!mounted) return;
    setState(() {
      _console.insert(0, line);
      if (_console.length > 200) _console.removeLast();
      _history.insert(0, line);
    });
  }

  Future<void> _start() async {
    // Boot beacon — tools/killed_state_harness.sh greps this exact marker.
    debugPrint('[harness] _start: subscribing');
    // §6.1 startup recipe: subscribe FIRST, then consume the launch action.
    RingKit.events.listen((CallEvent event) {
      final String detail = switch (event) {
        CallRinging(:final suppressed, :final suppressReason) =>
          suppressed ? ' SUPPRESSED(${suppressReason?.name})' : '',
        CallEnded(:final reason) => ' reason=${reason.name}',
        CallCancelled(:final reason) => ' reason=${reason.name}',
        _ => '',
      };
      _log('#${event.sequence} ${event.runtimeType} '
          '[${event.deliveryPath.name}] ${event.call.name}$detail');
      // Latency probe (§14.3): SIMULATE_CALL stamps receipt time into extra.
      final String? stamp = event.call.extra['probeReceiptMs'];
      if (event is CallRinging && stamp != null) {
        final int? receipt = int.tryParse(stamp);
        if (receipt != null) {
          _log('⏱ latency receipt→transition: '
              '${event.occurredAt.millisecondsSinceEpoch - receipt} ms');
        }
      }
      unawaited(_refreshActive());
    }, onError: (Object e) => _log('stream error: $e'));

    await RingKit.initialize(ringkitExampleConfig());
    final launch = await RingKit.consumeLaunchAction();
    if (launch != null) {
      _log('▶ launch action: ${launch.type.name} seq=${launch.sequence}'
          '${launch.alreadyEnded ? " (alreadyEnded)" : ""}');
    }
    final granted = await RingKit.permissions.requestNotifications();
    if (mounted) setState(() => _notificationsGranted = granted);
    _log('notifications granted: $granted');
    unawaited(_refreshActive());

    // Mode 1 (§7.2): surface the FCM registration token (tools/send_call.sh
    // <token>) and bridge foreground pushes. Guarded — the harness runs without
    // Firebase configured too.
    try {
      final token = await FirebaseMessaging.instance.getToken();
      _log('📱 FCM token: $token');
      FirebaseMessaging.onMessage.listen((message) {
        final data = message.data.map((k, v) => MapEntry(k, '$v'));
        if (RingKit.isRingMessage(data)) {
          // Mode 2: the native receiver rang it (foreground too) — guard out.
          // Mode 1: ring it here from Dart.
          if (!kMode2NativeFcm) {
            unawaited(dispatchRingPush(data, message.sentTime));
          }
        } else {
          // Coexistence proof (§7.3): a non-rk message still reaches the app's own
          // Dart handler untouched — RingKit never intercepts it (Mode 2 or Mode 1).
          _log('[app msg] non-rk push → app handles it: ${data.keys.toList()}');
        }
      });
    } catch (e) {
      _log('FCM unavailable: $e');
    }
  }

  Future<void> _refreshActive() async {
    final active = await RingKit.activeCalls();
    if (!mounted) return;
    setState(() {
      _active
        ..clear()
        ..addAll(active);
      if (_active.every((c) => c.id != _selectedCallId)) {
        _selectedCallId = _active.isEmpty ? null : _active.first.id;
      }
    });
  }

  String _describeShow(ShowCallResult result) {
    final buffer = StringBuffer(result.code.name);
    final degradations = result.degradations;
    if (degradations != null && degradations.isNotEmpty) {
      buffer.write(' {${degradations.map((d) => d.name).join(", ")}}');
    }
    if (result.failReason != null) buffer.write(' [${result.failReason!.name}]');
    return buffer.toString();
  }

  AndroidCallOptions _options() => AndroidCallOptions(
        vibrate: true,
        lockScreenVisibility: NotificationVisibility.public,
        lockScreenBehavior: _notificationOnly
            ? LockScreenBehavior.notificationOnly
            : LockScreenBehavior.fullScreen,
      );

  Future<String> _show({
    Duration delay = Duration.zero,
    CallType type = CallType.audio,
    bool withAvatar = false,
    bool withAuthHeaders = false,
    String? namePrefix,
  }) async {
    if (delay > Duration.zero) {
      _log('… showing in ${delay.inSeconds}s (background/lock now)');
      await Future<void>.delayed(delay);
    }
    final id = 'demo-${++_counter}-${DateTime.now().millisecondsSinceEpoch}';
    final show = await RingKit.showIncomingCall(
      IncomingCall(
        id: id,
        callerName: '${namePrefix ?? (type == CallType.video ? "Vera" : "Alice")} #$_counter',
        callerHandle: '+1 555 0100',
        avatarUrl: withAvatar || withAuthHeaders
            ? 'https://api.dicebear.com/9.x/thumbs/png?seed=$id&size=256'
            : null,
        avatarHeaders:
            withAuthHeaders ? const {'Authorization': 'Bearer demo-token'} : null,
        type: type,
        sentAtEpochMs: DateTime.now().millisecondsSinceEpoch,
        extra: <String, String>{'session': id},
        android: _options(),
      ),
    );
    _log('show($id) → ${_describeShow(show)}');
    unawaited(_refreshActive());
    return id;
  }

  Future<void> _cmd(
    String verb,
    String? callId,
    Future<CallCommandResult> Function(String id) action,
  ) async {
    if (callId == null) {
      _log('$verb: no call selected');
      return;
    }
    final result = await action(callId);
    _log('$verb($callId) → ${result.name}');
    unawaited(_refreshActive());
  }

  Future<void> _outOfOrderCancel() async {
    final id = 'ooo-${DateTime.now().millisecondsSinceEpoch}';
    final cancel =
        await RingKit.cancelCall(id, reason: CancelReason.answeredElsewhere);
    _log('cancel-before-show($id) → ${cancel.name} (pre-tombstone)');
    final show = await RingKit.showIncomingCall(
      IncomingCall(
        id: id,
        callerName: 'Ghost Caller',
        type: CallType.audio,
        sentAtEpochMs: DateTime.now().millisecondsSinceEpoch,
        extra: const <String, String>{},
        android: _options(),
      ),
    );
    _log('show-after-cancel($id) → ${_describeShow(show)} (must NOT ring)');
  }

  Future<void> _startOutgoing() async {
    final id = 'out-${++_counter}-${DateTime.now().millisecondsSinceEpoch}';
    final result = await RingKit.startOutgoingCall(
      OutgoingCall(
        id: id,
        calleeName: 'Bob Callee #$_counter',
        calleeHandle: '+1 555 0199',
        type: CallType.audio,
        extra: const <String, String>{},
        android: _options(),
      ),
    );
    _log('startOutgoing($id) → ${_describeShow(result)}');
    unawaited(_refreshActive());
  }

  Future<void> _updateInfo() async {
    final id = _selectedCallId;
    if (id == null) {
      _log('updateCallInfo: no call selected');
      return;
    }
    final result = await RingKit.updateCallInfo(
      id,
      CallInfoUpdate(callerName: 'Resolved Contact ✓'),
    );
    _log('updateCallInfo($id) → ${result.name} (late contact resolution)');
  }

  Future<void> _runDiagnostics() async {
    final report = await RingKit.diagnose();
    if (!mounted) return;
    setState(() => _report = report);
    _log('diagnose(): ${report.findings.length} findings');
  }

  Future<void> _loadSnapshot() async {
    final snapshot = await RingKit.permissions.snapshot();
    if (!mounted) return;
    setState(() => _snapshot = snapshot);
  }

  // ── UI ────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('ringkit harness'),
        actions: [
          Icon(_notificationsGranted
              ? Icons.notifications_active
              : Icons.notifications_off),
          const SizedBox(width: 12),
        ],
      ),
      body: switch (_tab) {
        1 => _diagnosticsTab(),
        2 => _permissionsTab(),
        _ => _harnessTab(),
      },
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) {
          setState(() => _tab = i);
          if (i == 1) unawaited(_runDiagnostics());
          if (i == 2) unawaited(_loadSnapshot());
        },
        destinations: const [
          NavigationDestination(icon: Icon(Icons.call), label: 'Harness'),
          NavigationDestination(icon: Icon(Icons.healing), label: 'Diagnostics'),
          NavigationDestination(icon: Icon(Icons.security), label: 'Permissions'),
        ],
      ),
    );
  }

  Widget _harnessTab() {
    final selected = _selectedCallId;
    return Column(
      children: [
        Expanded(
          flex: 3,
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _section('Show (§15: now / delayed / types / avatar)'),
                Wrap(spacing: 6, runSpacing: 6, children: [
                  _btn('Show now', () => _show()),
                  _btn('In 5s', () => _show(delay: const Duration(seconds: 5))),
                  _btn('In 15s (lock!)',
                      () => _show(delay: const Duration(seconds: 15))),
                  _btn('Video', () => _show(type: CallType.video)),
                  _btn('Avatar', () => _show(withAvatar: true)),
                  _btn('Auth-header avatar', () => _show(withAuthHeaders: true)),
                ]),
                _section('Multi-call (§5.2)'),
                Wrap(spacing: 6, runSpacing: 6, children: [
                  _btn('2nd concurrent call', () => _show(namePrefix: 'Second')),
                  _btn('Call while in-call', () async {
                    final id = await _show(namePrefix: 'First');
                    await RingKit.acceptCall(id);
                    _log('accepted($id) — now triggering the in-call arrival');
                    await _show(namePrefix: 'Busy');
                  }),
                ]),
                _section('Cancel-push (§7.4 reasons) + races'),
                Wrap(spacing: 6, runSpacing: 6, children: [
                  for (final reason in CancelReason.values.where(
                      (r) => r != CancelReason.localDismiss))
                    _btn('Cancel: ${reason.name}',
                        () => _cmd('cancel', selected,
                            (id) => RingKit.cancelCall(id, reason: reason))),
                  _btn('Out-of-order cancel', _outOfOrderCancel),
                ]),
                _section('In-app answer UI (§15/#375) — selected call'),
                Wrap(spacing: 6, runSpacing: 6, children: [
                  _btn('Accept', () => _cmd('accept', selected, RingKit.acceptCall)),
                  _btn('Decline',
                      () => _cmd('decline', selected, RingKit.declineCall)),
                  _btn('Mark connected',
                      () => _cmd('connect', selected, RingKit.setCallConnected)),
                  _btn('Hang up', () => _cmd('end', selected, RingKit.endCall)),
                  _btn('End all', () async {
                    await RingKit.endAllCalls();
                    _log('endAllCalls()');
                    unawaited(_refreshActive());
                  }),
                ]),
                _section('Outgoing leg (§15) + updateCallInfo'),
                Wrap(spacing: 6, runSpacing: 6, children: [
                  _btn('Start outgoing', _startOutgoing),
                  _btn('updateCallInfo', _updateInfo),
                ]),
                _section('Config toggles'),
                SwitchListTile(
                  dense: true,
                  title: const Text('lockScreenBehavior: notificationOnly'),
                  value: _notificationOnly,
                  onChanged: (v) => setState(() => _notificationOnly = v),
                ),
              ],
            ),
          ),
        ),
        const Divider(height: 1),
        // Live calls with selection (per-call command targeting).
        SizedBox(
          height: 56,
          child: _active.isEmpty
              ? const Center(child: Text('no live calls'))
              : ListView(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.all(8),
                  children: [
                    for (final call in _active)
                      Padding(
                        padding: const EdgeInsets.only(right: 6),
                        child: ChoiceChip(
                          label: Text('${call.info.name} · ${call.state.name}'),
                          selected: call.id == _selectedCallId,
                          onSelected: (_) =>
                              setState(() => _selectedCallId = call.id),
                        ),
                      ),
                  ],
                ),
        ),
        const Divider(height: 1),
        Expanded(
          flex: 2,
          child: DefaultTabController(
            length: 2,
            child: Column(children: [
              const TabBar(tabs: [
                Tab(text: 'Live console'),
                Tab(text: 'Session history'),
              ]),
              Expanded(
                child: TabBarView(children: [
                  _consoleList(_console),
                  _consoleList(_history),
                ]),
              ),
            ]),
          ),
        ),
      ],
    );
  }

  Widget _consoleList(List<String> lines) => ListView.builder(
        padding: const EdgeInsets.all(8),
        itemCount: lines.length,
        itemBuilder: (_, i) => Text(lines[i], style: const TextStyle(fontSize: 12)),
      );

  Widget _diagnosticsTab() {
    final report = _report;
    return RefreshIndicator(
      onRefresh: _runDiagnostics,
      child: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          Row(children: [
            Expanded(
                child: Text('DiagnosticsReport',
                    style: Theme.of(context).textTheme.titleMedium)),
            IconButton(
                onPressed: _runDiagnostics, icon: const Icon(Icons.refresh)),
          ]),
          if (report == null) const Text('running…'),
          if (report != null) ...[
            Text('generated ${DateTime.fromMillisecondsSinceEpoch(report.generatedAtEpochMs)}'),
            const SizedBox(height: 8),
            if (report.findings.isEmpty)
              const Card(
                  child: ListTile(
                      leading: Icon(Icons.check_circle, color: Colors.green),
                      title: Text('No findings — ready to ring'))),
            for (final f in report.findings) _findingCard(f),
            const SizedBox(height: 8),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Event-log health',
                        style: Theme.of(context).textTheme.titleSmall),
                    Text('size: ${report.eventLogHealth.sizeBytes} B · '
                        'unconsumed: ${report.eventLogHealth.unconsumedCount} · '
                        'dropped: ${report.eventLogHealth.droppedEvents} · '
                        'quarantined: ${report.eventLogHealth.quarantinedEntries} · '
                        'leases: ${report.eventLogHealth.activeLeases}'),
                    const SizedBox(height: 4),
                    Text('last show: ${report.lastShowResult == null ? "—" : _describeShow(report.lastShowResult!)} · '
                        'last delivery: ${report.lastDeliveryPath?.name ?? "—"}'),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 8),
            ExpansionTile(
              title: Text('Ring traces (${report.ringTraces.length})'),
              children: [
                for (final trace in report.ringTraces)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: Text(trace, style: const TextStyle(fontSize: 11)),
                    ),
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _findingCard(DiagnosticsFinding f) {
    final (icon, color) = switch (f.severity) {
      DiagnosticsSeverity.error => (Icons.error, Colors.red),
      DiagnosticsSeverity.warning => (Icons.warning, Colors.orange),
      DiagnosticsSeverity.info => (Icons.info, Colors.blue),
    };
    return Card(
      child: ListTile(
        leading: Icon(icon, color: color),
        title: Text(f.finding, style: const TextStyle(fontSize: 13)),
        subtitle: Text('${f.id}\n→ ${f.remedy}',
            style: const TextStyle(fontSize: 12)),
        isThreeLine: true,
        trailing: f.deepLink == null
            ? null
            : IconButton(
                icon: const Icon(Icons.open_in_new),
                onPressed: () => _openDeepLink(f.deepLink!),
              ),
      ),
    );
  }

  /// §11.3 deepLink vocabulary → §11.2 helpers.
  Future<void> _openDeepLink(String link) async {
    final permissions = RingKit.permissions;
    if (link == 'notificationSettings' || link.startsWith('channel:')) {
      final channel = switch (link) {
        'channel:incomingAudible' => RingKitChannel.incomingAudible,
        'channel:ongoing' => RingKitChannel.ongoing,
        'channel:missed' => RingKitChannel.missed,
        _ => RingKitChannel.incoming,
      };
      await permissions.openChannelSettings(channel);
    } else if (link == 'fullScreenIntentSettings') {
      await permissions.openFullScreenIntentSettings();
    } else if (link == 'batteryOptimizationSettings') {
      await permissions.openBatteryOptimizationSettings();
    } else if (link.startsWith('oem:')) {
      final screen = switch (link) {
        'oem:battery' => OemScreen.battery,
        'oem:lockScreenDisplay' => OemScreen.lockScreenDisplay,
        _ => OemScreen.autostart,
      };
      final opened = await permissions.openOemSettings(screen);
      _log('openOemSettings($link) → $opened');
    }
  }

  Widget _permissionsTab() {
    final snapshot = _snapshot;
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Row(children: [
          Expanded(
              child: Text('Onboarding flow (§11.2)',
                  style: Theme.of(context).textTheme.titleMedium)),
          IconButton(onPressed: _loadSnapshot, icon: const Icon(Icons.refresh)),
        ]),
        if (snapshot == null) const Text('loading…'),
        if (snapshot != null) ...[
          _permRow('Notifications', snapshot.notifications, () async {
            final granted = await RingKit.permissions.requestNotifications();
            _log('requestNotifications → $granted');
            await _loadSnapshot();
          }),
          _permRow('Full-screen intent (14+)', snapshot.fullScreenIntent,
              () => RingKit.permissions.openFullScreenIntentSettings()),
          _permRow(
              'Battery optimization exempt',
              snapshot.ignoringBatteryOptimizations,
              () => RingKit.permissions.openBatteryOptimizationSettings()),
          Card(
            child: ListTile(
              title: Text(
                  'Incoming channel: ${snapshot.incomingChannelImportance.name}'),
              subtitle: const Text('importance must be HIGH for heads-up'),
              trailing: IconButton(
                icon: const Icon(Icons.open_in_new),
                onPressed: () => RingKit.permissions
                    .openChannelSettings(RingKitChannel.incoming),
              ),
            ),
          ),
          Card(
            child: ListTile(
              title: Text('OEM: ${snapshot.oem.manufacturer}'),
              subtitle: Text(snapshot.oem.knownRestrictions.isEmpty
                  ? 'no known restrictions'
                  : 'restrictions: ${snapshot.oem.knownRestrictions.join(", ")}'),
              trailing: IconButton(
                icon: const Icon(Icons.open_in_new),
                onPressed: () async {
                  final opened = await RingKit.permissions
                      .openOemSettings(OemScreen.autostart);
                  _log('openOemSettings(autostart) → $opened');
                },
              ),
            ),
          ),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Text(
                'Merged manifest: FSI=${snapshot.fsiPermissionMerged} · '
                'FGS=${snapshot.fgsMerged} · '
                'FGS_PHONE_CALL=${snapshot.fgsPhoneCallMerged} · '
                'MANAGE_OWN_CALLS=${snapshot.manageOwnCallsMerged}',
                style: const TextStyle(fontSize: 12),
              ),
            ),
          ),
        ],
      ],
    );
  }

  Widget _permRow(String label, bool ok, Future<void> Function() onFix) => Card(
        child: ListTile(
          leading: Icon(ok ? Icons.check_circle : Icons.cancel,
              color: ok ? Colors.green : Colors.red),
          title: Text(label),
          trailing: ok
              ? null
              : TextButton(onPressed: () => unawaited(onFix()), child: const Text('Fix')),
        ),
      );

  Widget _section(String title) => Padding(
        padding: const EdgeInsets.only(top: 10, bottom: 4),
        child: Text(title, style: Theme.of(context).textTheme.titleSmall),
      );

  Widget _btn(String label, VoidCallback onTap) =>
      ElevatedButton(onPressed: onTap, child: Text(label));
}
