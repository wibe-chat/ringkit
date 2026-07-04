import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'ringkit_method_channel.dart';

abstract class RingkitPlatform extends PlatformInterface {
  /// Constructs a RingkitPlatform.
  RingkitPlatform() : super(token: _token);

  static final Object _token = Object();

  static RingkitPlatform _instance = MethodChannelRingkit();

  /// The default instance of [RingkitPlatform] to use.
  ///
  /// Defaults to [MethodChannelRingkit].
  static RingkitPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [RingkitPlatform] when
  /// they register themselves.
  static set instance(RingkitPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
