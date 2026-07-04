import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'ringkit_platform_interface.dart';

/// An implementation of [RingkitPlatform] that uses method channels.
class MethodChannelRingkit extends RingkitPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('ringkit');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>(
      'getPlatformVersion',
    );
    return version;
  }
}
