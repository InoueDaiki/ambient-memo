# iOS の Expo Module。React Native と Swift のエンジン(engine/ios の AmbientMemoEngine)をつなぐ
require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'EngineBridge'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = { type: 'UNLICENSED' }
  s.author         = 'ambient-memo'
  s.homepage       = 'https://github.com/InoueDaiki/ambient-memo'
  s.platforms      = { :ios => '16.4' }
  s.swift_version  = '5.9'
  s.source         = { git: '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'
  # app.plugin.js が Podfile に場所を足す
  s.dependency 'AmbientMemoEngine'

  s.source_files = '**/*.swift'

  # AmbientMemoEngine のモジュールが読む C のモジュール(sherpa-onnx、libbz2)と、
  # Swift Package と同じ import のための空のモジュールを、このモジュールからも見えるようにする
  engine = File.expand_path('../../../engine', __dir__)
  cache = File.join(engine, '.cache', 'ios')
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    # Debug だけのコード(debug-input.wav など)を #if DEBUG で分ける
    'SWIFT_ACTIVE_COMPILATION_CONDITIONS[config=Debug]' => '$(inherited) DEBUG',
    'SWIFT_INCLUDE_PATHS' => "$(inherited) \"#{engine}/ios/Sources/EngineIOS/Modules\"",
    'FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]' => "$(inherited) \"#{cache}/sherpa-onnx.xcframework/ios-arm64\" " \
      "\"#{cache}/litert-lm/CLiteRTLM.xcframework/ios-arm64\"",
    'FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*]' => "$(inherited) " \
      "\"#{cache}/sherpa-onnx.xcframework/ios-arm64_x86_64-simulator\" " \
      "\"#{cache}/litert-lm/CLiteRTLM.xcframework/ios-arm64-simulator\"",
    # LiteRT-LM のシミュレーター向けは arm64 しかない
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'x86_64'
  }
end
