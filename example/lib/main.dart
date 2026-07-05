import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ringkit/ringkit.dart';

// M2 manual test harness (PLAN §15 subset): show-path buttons with delays
// (background/lock-screen tests), in-app answer UI driving accept/decline,
// cancel-push variants incl. out-of-order, and a live event console with
// deliveryPath + sequence badges and the degradation set of every
// ShowCallResult. Diagnostics screen + latency probe land in M3.
void main() {
  runApp(const RingKitExampleApp());
}

class RingKitExampleApp extends StatefulWidget {
  const RingKitExampleApp({super.key});

  @override
  State<RingKitExampleApp> createState() => _RingKitExampleAppState();
}

class _RingKitExampleAppState extends State<RingKitExampleApp> {
  final List<String> _log = <String>[];
  final Set<String> _liveCalls = <String>{};
  bool _notificationsGranted = false;
  int _shownCounter = 0;

  @override
  void initState() {
    super.initState();
    _start();
  }

  void _append(String line) {
    if (!mounted) return;
    setState(() {
      _log.insert(0, line);
      if (_log.length > 200) _log.removeLast();
    });
  }

  Future<void> _start() async {
    debugPrint('[harness] _start: subscribing');
    // Subscribe FIRST, then consume the launch action (§6.1 startup recipe).
    RingKit.events.listen(
      (CallEvent event) {
        debugPrint('[harness] event #${event.sequence} ${event.runtimeType}');
        _append(
          '#${event.sequence} ${event.runtimeType} (${event.deliveryPath.name}) '
          '· ${event.call.name}',
        );
        unawaited(_refreshActive());
      },
      onError: (Object e, StackTrace s) =>
          debugPrint('[harness] events stream error: $e\n$s'),
    );
    await RingKit.initialize(
      RingKitConfig(
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
      ),
    );
    debugPrint('[harness] initialize done; consuming launch action');
    final launch = await RingKit.consumeLaunchAction();
    debugPrint('[harness] launch action: ${launch?.type.name} seq=${launch?.sequence}');
    if (launch != null) {
      _append(
        '▶ launch action: ${launch.type.name} seq=${launch.sequence}'
        '${launch.alreadyEnded ? " (alreadyEnded)" : ""}',
      );
    }
    final granted = await RingKit.permissions.requestNotifications();
    if (mounted) setState(() => _notificationsGranted = granted);
    _append('notifications granted: $granted');
    unawaited(_refreshActive());
  }

  Future<void> _refreshActive() async {
    final active = await RingKit.activeCalls();
    if (!mounted) return;
    setState(() {
      _liveCalls
        ..clear()
        ..addAll(active.map((c) => c.id));
    });
  }

  String _describeShow(ShowCallResult result) {
    final degradations = result.degradations;
    final buffer = StringBuffer(result.code.name);
    if (degradations != null && degradations.isNotEmpty) {
      buffer.write(' {${degradations.map((d) => d.name).join(", ")}}');
    }
    if (result.failReason != null) {
      buffer.write(' [${result.failReason!.name}]');
    }
    return buffer.toString();
  }

  Future<void> _show({
    Duration delay = Duration.zero,
    CallType type = CallType.audio,
    bool withAvatar = false,
  }) async {
    if (delay > Duration.zero) {
      _append('… showing in ${delay.inSeconds}s (background/lock the app now)');
      await Future<void>.delayed(delay);
    }
    final id = 'demo-${++_shownCounter}-${DateTime.now().millisecondsSinceEpoch}';
    final show = await RingKit.showIncomingCall(
      IncomingCall(
        id: id,
        callerName: type == CallType.video ? 'Vera Video' : 'Alice Example',
        callerHandle: '+1 555 0100',
        avatarUrl: withAvatar
            ? 'https://api.dicebear.com/9.x/thumbs/png?seed=ringkit&size=256'
            : null,
        type: type,
        sentAtEpochMs: DateTime.now().millisecondsSinceEpoch,
        extra: <String, String>{'session': id},
        android: AndroidCallOptions(
          vibrate: true,
          lockScreenVisibility: NotificationVisibility.public,
          lockScreenBehavior: LockScreenBehavior.fullScreen,
        ),
      ),
    );
    _append('show($id) → ${_describeShow(show)}');
    unawaited(_refreshActive());
  }

  Future<void> _forEachLive(
    String verb,
    Future<CallCommandResult> Function(String id) action,
  ) async {
    if (_liveCalls.isEmpty) {
      _append('$verb: no live calls');
      return;
    }
    for (final id in _liveCalls.toList()) {
      final result = await action(id);
      _append('$verb($id) → ${result.name}');
    }
    unawaited(_refreshActive());
  }

  Future<void> _outOfOrderCancel() async {
    final id = 'ooo-${DateTime.now().millisecondsSinceEpoch}';
    final cancel = await RingKit.cancelCall(id, reason: CancelReason.callerCancelled);
    _append('cancel-before-show($id) → ${cancel.name} (pre-tombstone written)');
    final show = await RingKit.showIncomingCall(
      IncomingCall(
        id: id,
        callerName: 'Ghost Caller',
        type: CallType.audio,
        sentAtEpochMs: DateTime.now().millisecondsSinceEpoch,
        extra: const <String, String>{},
        android: AndroidCallOptions(
          vibrate: true,
          lockScreenVisibility: NotificationVisibility.public,
          lockScreenBehavior: LockScreenBehavior.fullScreen,
        ),
      ),
    );
    _append('show-after-cancel($id) → ${_describeShow(show)} (must NOT ring)');
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('ringkit M2 harness'),
          actions: [
            Padding(
              padding: const EdgeInsets.only(right: 12),
              child: Icon(
                _notificationsGranted
                    ? Icons.notifications_active
                    : Icons.notifications_off,
              ),
            ),
          ],
        ),
        body: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(8),
              child: Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  _btn('Show now', () => _show()),
                  _btn('Show in 5s', () => _show(delay: const Duration(seconds: 5))),
                  _btn('Show in 15s (lock now)',
                      () => _show(delay: const Duration(seconds: 15))),
                  _btn('Video call', () => _show(type: CallType.video)),
                  _btn('With avatar', () => _show(withAvatar: true)),
                  _btn('Accept (in-app)',
                      () => _forEachLive('accept', RingKit.acceptCall)),
                  _btn('Decline (in-app)',
                      () => _forEachLive('decline', RingKit.declineCall)),
                  _btn('Mark connected',
                      () => _forEachLive('connected', RingKit.setCallConnected)),
                  _btn('End (local)', () => _forEachLive('end', RingKit.endCall)),
                  _btn(
                    'Cancel-push',
                    () => _forEachLive(
                      'cancel',
                      (id) => RingKit.cancelCall(id, reason: CancelReason.callerCancelled),
                    ),
                  ),
                  _btn(
                    'Cancel: answeredElsewhere',
                    () => _forEachLive(
                      'cancel',
                      (id) =>
                          RingKit.cancelCall(id, reason: CancelReason.answeredElsewhere),
                    ),
                  ),
                  _btn('Out-of-order cancel', _outOfOrderCancel),
                  _btn('End all', () async {
                    await RingKit.endAllCalls();
                    _append('endAllCalls()');
                    unawaited(_refreshActive());
                  }),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text('live: ${_liveCalls.isEmpty ? "—" : _liveCalls.join(", ")}'),
              ),
            ),
            const Divider(),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(12),
                children: _log
                    .map((line) => Text(line, style: const TextStyle(fontSize: 13)))
                    .toList(),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _btn(String label, VoidCallback onTap) =>
      ElevatedButton(onPressed: onTap, child: Text(label));
}
