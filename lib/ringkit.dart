
import 'ringkit_platform_interface.dart';

class Ringkit {
  Future<String?> getPlatformVersion() {
    return RingkitPlatform.instance.getPlatformVersion();
  }
}
