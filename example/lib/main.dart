import 'package:flutter/material.dart';
import 'package:ringkit/ringkit.dart';

// The full §15 manual test harness (button matrix, event console with
// deliveryPath badges, diagnostics screen, latency probe) lands in M2 —
// this M1 example proves initialize + the unified stream wiring end-to-end.
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

  @override
  void initState() {
    super.initState();
    _start();
  }

  Future<void> _start() async {
    // Subscribe FIRST, then consume the launch action (§6.1 startup recipe).
    RingKit.events.listen((CallEvent event) {
      setState(() {
        _log.add(
          '#${event.sequence} ${event.runtimeType} (${event.deliveryPath.name}) '
          '· ${event.call.name}',
        );
      });
    });
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
    final launch = await RingKit.consumeLaunchAction();
    if (launch != null && mounted) {
      setState(() {
        _log.add('launch action: ${launch.type.name} seq=${launch.sequence}');
      });
    }
  }

  Future<void> _simulateLifecycle() async {
    final id = 'demo-${DateTime.now().millisecondsSinceEpoch}';
    final show = await RingKit.showIncomingCall(
      IncomingCall(
        id: id,
        callerName: 'Alice Example',
        callerHandle: '+1 555 0100',
        type: CallType.audio,
        sentAtEpochMs: DateTime.now().millisecondsSinceEpoch,
        extra: <String, String>{'session': id},
        android: AndroidCallOptions(
          vibrate: true,
          lockScreenVisibility: NotificationVisibility.public,
          lockScreenBehavior: LockScreenBehavior.fullScreen,
        ),
      ),
    );
    if (!mounted) return;
    setState(() => _log.add('show → ${show.code.name}'));
    final accept = await RingKit.acceptCall(id);
    if (!mounted) return;
    setState(() => _log.add('accept → ${accept.name}'));
    final end = await RingKit.endCall(id);
    if (!mounted) return;
    setState(() => _log.add('end → ${end.name}'));
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('ringkit M1 harness (full harness: M2)'),
        ),
        floatingActionButton: FloatingActionButton.extended(
          onPressed: _simulateLifecycle,
          label: const Text('show → accept → end'),
          icon: const Icon(Icons.ring_volume),
        ),
        body: ListView(
          padding: const EdgeInsets.all(16),
          children: _log.map((line) => Text(line)).toList(),
        ),
      ),
    );
  }
}
