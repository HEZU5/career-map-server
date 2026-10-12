import 'dart:async';

/// Bản stub cho nền không phải web: không có Fullscreen API / không có DOM.
bool isFullscreenSupported() => false;

bool isFullscreenActive() => false;

Future<bool> enterFullscreen() async => false;

Future<bool> exitFullscreen() async => false;

Stream<bool> fullscreenChanges() => const Stream<bool>.empty();
