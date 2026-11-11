import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:ringkit/ringkit.dart';

RingKitConfig _config([RingKitTelemetryConfig? telemetry]) => RingKitConfig(
  incomingChannelName: 'Incoming calls',
  missedChannelName: 'Missed calls',
  ongoingChannelName: 'Ongoing call',
  audibleChannelName: 'Incoming calls (fallback)',
  defaultTimeoutSeconds: 30,
  connectionTimeoutSeconds: 3,
  maxConcurrentRinging: 3,
  telecomIntegration: false,
  manageOngoingCall: true,
  foregroundBehavior: ForegroundBehavior.headsUp,
  notificationIdBase: 424200,
  logLevel: RingKitLogLevel.info,
  channelSuffix: '',
  telemetry: telemetry,
);

/// Exercises the real native lifecycle, WorkManager, journal, gzip transport,
/// and call-event capture against an in-process loopback HTTP fixture. No
/// production credentials or backend are used. Run with normal ringer/DND off.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  test(
    'r14 native session uploads a redacted call and final manifest',
    () async {
      final fixture = await _SessionFixture.start();
      final subscription = RingKit.events.listen((_) {});
      try {
        await RingKit.initialize(_config());
        await RingKit.endAllCalls();
        await RingKit.initialize(
          _config(
            RingKitTelemetryConfig(
              endpoint: fixture.endpoint,
              apiKey: _SessionFixture.bootstrap,
              deviceLabel: 'local-integration-test',
              uploadLevel: RingKitLogLevel.info,
              dailyUploadBudgetKb: 200,
            ),
          ),
        );
        await fixture.acknowledged.future.timeout(const Duration(seconds: 60));
        // The response must be applied to the native journal before the call.
        await Future<void>.delayed(const Duration(milliseconds: 500));
        final id = 'diagnostic-${DateTime.now().millisecondsSinceEpoch}';
        final result = await RingKit.showIncomingCall(
          IncomingCall(
            id: id,
            callerName: 'Private diagnostic caller',
            type: CallType.audio,
            sentAtEpochMs: DateTime.now().millisecondsSinceEpoch,
            extra: const <String, String>{},
            android: AndroidCallOptions(
              ringtone: 'file:///ringkit-private-test-missing.ogg',
              vibrate: true,
              lockScreenVisibility: NotificationVisibility.public,
              lockScreenBehavior: LockScreenBehavior.fullScreen,
            ),
          ),
        );
        expect(
          result.code,
          anyOf(ShowCallResultCode.shown, ShowCallResultCode.degraded),
        );
        await Future<void>.delayed(const Duration(seconds: 2));
        debugPrint('SESSION_RINGING_DIAGNOSE ${await RingKit.diagnose()}');
        expect(
          await RingKit.cancelCall(id, reason: CancelReason.callerCancelled),
          CallCommandResult.ok,
        );
        await fixture.finished.future.timeout(const Duration(seconds: 60));

        expect(fixture.errors, isEmpty);
        expect(fixture.registrations, isNotEmpty);
        final device =
            fixture.registrations.last['device'] as Map<String, dynamic>;
        expect(device['telemetry_protocol'], 2);
        expect(device['capabilities'], contains('diagnostic_session'));
        expect(device['host_debuggable'], isTrue);
        expect(device['host_version_code'], isA<int>());
        expect(
          device['sdk_build_id'],
          matches(RegExp(r'^sha256:[a-f0-9]{64}$')),
        );

        final reports = fixture.reports;
        expect(reports, hasLength(2));
        expect(reports.map((r) => r['request_id']).toSet(), {
          fixture.requestId,
        });
        for (final report in reports) {
          expect(report['part'], 1);
          expect(report['of'], 1);
          final identity = report['identity'] as Map<String, dynamic>;
          expect(identity['sdk_build_id'], device['sdk_build_id']);
          expect(identity['host_debuggable'], isTrue);
        }
        final metadata = reports.last['metadata'] as Map<String, dynamic>;
        expect(metadata['final'], isTrue);
        expect(metadata['end_reason'], 'call_limit');
        expect(metadata['observed_call_count'], 1);
        expect(
          metadata['manifest'],
          unorderedEquals(reports.map((r) => r['report_id'])),
        );
        final firstMetadata = reports.first['metadata'] as Map<String, dynamic>;
        expect(firstMetadata['diagnose'], isA<List<dynamic>>());

        final raw = reports
            .map(
              (r) => utf8.decode(
                gzip.decode(base64Decode(r['records_gzip_b64'] as String)),
              ),
            )
            .join();
        final rows = const LineSplitter()
            .convert(raw)
            .map((line) => jsonDecode(line) as Map<String, dynamic>)
            .toList();
        expect(rows, isNotEmpty);
        expect(rows.every((r) => r['callId'] == id), isTrue);
        expect(raw, contains('Playing'));
        expect(raw, contains('BundledDefault'));
        expect(raw, isNot(contains('Private diagnostic caller')));
        expect(
          raw,
          isNot(contains('file:///ringkit-private-test-missing.ogg')),
        );
        expect(raw, isNot(contains('RING_AUDIO_FAILED')));
        expect(await RingKit.activeCalls(), isEmpty);
        debugPrint(
          'SESSION_HTTP_PASS reports=${reports.length} records=${rows.length} '
          'nativeBuild=${device['sdk_build_id']} hostDebuggable=${device['host_debuggable']}',
        );
      } finally {
        await RingKit.endAllCalls();
        await RingKit.initialize(_config());
        await subscription.cancel();
        await fixture.server.close(force: true);
      }
    },
    timeout: const Timeout(Duration(minutes: 3)),
  );
}

class _SessionFixture {
  _SessionFixture(this.server);

  // Deliberately synthetic, accepted only by this loopback fixture.
  static const bootstrap = 'local-bootstrap-fixture';
  static const token = 'local-device-fixture';
  final HttpServer server;
  final requestId =
      '20000000-0000-4000-8000-'
      '${DateTime.now().millisecondsSinceEpoch.toRadixString(16).padLeft(12, '0')}';
  final acknowledged = Completer<void>();
  final finished = Completer<void>();
  final registrations = <Map<String, dynamic>>[];
  final reports = <Map<String, dynamic>>[];
  final errors = <String>[];
  bool complete = false;
  String get endpoint => 'http://127.0.0.1:${server.port}/checkin';

  static Future<_SessionFixture> start() async {
    final fixture = _SessionFixture(
      await HttpServer.bind(InternetAddress.loopbackIPv4, 0),
    );
    fixture.server.listen(fixture.handle);
    return fixture;
  }

  Future<void> handle(HttpRequest request) async {
    try {
      final bytes = await request.fold<List<int>>(
        <int>[],
        (all, chunk) => all..addAll(chunk),
      );
      final payload =
          jsonDecode(utf8.decode(gzip.decode(bytes))) as Map<String, dynamic>;
      final kind = payload['kind'];
      if (request.headers.value(HttpHeaders.authorizationHeader) !=
          'Bearer ${kind == 'register' ? bootstrap : token}') {
        request.response.statusCode = HttpStatus.unauthorized;
        await request.response.close();
        return;
      }
      final response = <String, dynamic>{
        'status': 'ok',
        'telemetry_protocol': 2,
        'diagnostics_enabled': true,
        'device_token': token,
        'log_level': 'info',
        'kill': false,
        'sampled': false,
        'pull_buffer': false,
        'server_wall_ms': DateTime.now().millisecondsSinceEpoch,
      };
      if (kind == 'register') registrations.add(payload);
      if (kind == 'control') {
        if (payload['action'] == 'acknowledged') {
          response['request_state'] = 'acknowledged';
        }
        response['requests'] = complete
            ? <Object>[]
            : <Object>[
                <String, dynamic>{
                  'id': requestId,
                  'kind': 'diagnostic_session',
                  'state': acknowledged.isCompleted
                      ? 'acknowledged'
                      : 'offered',
                  'duration_ms': 120000,
                  'max_calls': 1,
                  'max_bytes': 524288,
                },
              ];
        if (complete) {
          response['request_states'] = <Object>[
            <String, dynamic>{'id': requestId, 'state': 'received'},
          ];
        }
      }
      if (kind == 'diagnostic_report') {
        final report = payload['report'] as Map<String, dynamic>;
        reports.add(report);
        complete =
            (report['metadata'] as Map<String, dynamic>)['final'] == true;
        response['state'] = complete ? 'received' : 'collecting';
      }
      request.response.headers.contentType = ContentType.json;
      request.response.write(jsonEncode(response));
      await request.response.close();
      if (payload['action'] == 'acknowledged' && !acknowledged.isCompleted) {
        acknowledged.complete();
      }
      if (complete && !finished.isCompleted) finished.complete();
    } catch (error) {
      errors.add(error.runtimeType.toString());
      request.response.statusCode = HttpStatus.internalServerError;
      await request.response.close();
    }
  }
}
