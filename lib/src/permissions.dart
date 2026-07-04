import 'messages.g.dart';

/// §11.2 — permission state + request/deep-link helpers for the app's
/// onboarding flow. The plugin itself never prompts mid-ring (§11.1).
final class RingKitPermissions {
  RingKitPermissions(this._api);

  final RingKitPermissionsApi _api;

  /// Everything below in one call.
  Future<PermissionSnapshot> snapshot() => _api.snapshot();

  /// POST_NOTIFICATIONS (13+). M1 reports current state; the runtime prompt
  /// plumbing arrives with the M2 example harness.
  Future<bool> requestNotifications() => _api.requestNotifications();

  /// 14+ (true below); reporting-only — the FSI is always attached (§8.2).
  Future<bool> canUseFullScreenIntent() => _api.canUseFullScreenIntent();

  Future<void> openFullScreenIntentSettings() =>
      _api.openFullScreenIntentSettings();

  Future<void> openChannelSettings(RingKitChannel channel) =>
      _api.openChannelSettings(channel);

  Future<bool> isIgnoringBatteryOptimizations() =>
      _api.isIgnoringBatteryOptimizations();

  /// The no-permission settings list (§11.2 default helper).
  Future<void> openBatteryOptimizationSettings() =>
      _api.openBatteryOptimizationSettings();

  /// Direct dialog — REQUIRES the app to declare
  /// REQUEST_IGNORE_BATTERY_OPTIMIZATIONS itself (Play-policy caveat, §11.2).
  Future<bool> requestIgnoreBatteryOptimizations() =>
      _api.requestIgnoreBatteryOptimizations();

  Future<OemInfo> oemInfo() => _api.oemInfo();

  /// Best-effort OEM intent table (§12); false if no intent resolved.
  Future<bool> openOemSettings(OemScreen screen) =>
      _api.openOemSettings(screen);
}
