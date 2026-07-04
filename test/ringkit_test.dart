import 'package:flutter_test/flutter_test.dart';
import 'package:ringkit/ringkit.dart';
import 'package:ringkit/ringkit_platform_interface.dart';
import 'package:ringkit/ringkit_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockRingkitPlatform
    with MockPlatformInterfaceMixin
    implements RingkitPlatform {
  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final RingkitPlatform initialPlatform = RingkitPlatform.instance;

  test('$MethodChannelRingkit is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelRingkit>());
  });

  test('getPlatformVersion', () async {
    Ringkit ringkitPlugin = Ringkit();
    MockRingkitPlatform fakePlatform = MockRingkitPlatform();
    RingkitPlatform.instance = fakePlatform;

    expect(await ringkitPlugin.getPlatformVersion(), '42');
  });
}
