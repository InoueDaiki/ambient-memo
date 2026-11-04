# iOS のエンジン。EngineDomain、EngineStore、EngineIOS のソースを 1 つのモジュール(AmbientMemoEngine)にまとめる。
# modules/engine-bridge の EngineBridge.podspec が依存し、app.plugin.js が Podfile に `pod 'AmbientMemoEngine', :path => ...` を足す
# (Android の includeBuild と同じ考え方)。
#
# 推論のライブラリは `mise run ios-frameworks:fetch` で engine/.cache/ios に取得しておく。
# - sherpa-onnx と ONNX Runtime: 静的な xcframework。このモジュールにリンクする
# - LiteRT-LM: 取得のときに作る LiteRTLM の Pod(engine/.cache/ios/litert-lm)。Swift のラッパーは別のモジュールにする
#
# Swift Package(Package.swift)と同じ `import EngineDomain` と `import EngineStore` を書けるように、
# Sources/EngineIOS/Modules に空のモジュールを置く。型は同じモジュールの中にある。

Pod::Spec.new do |s|
  s.name           = 'AmbientMemoEngine'
  s.version        = '0.1.0'
  s.summary        = '会話を端末の中で文字にして整理するエンジン'
  s.description    = s.summary
  s.license        = { type: 'UNLICENSED' }
  s.author         = 'ambient-memo'
  s.homepage       = 'https://github.com/InoueDaiki/ambient-memo'
  s.platforms      = { :ios => '16.4' }
  s.swift_version  = '6.0'
  s.source         = { git: '' }
  s.static_framework = true

  s.source_files = [
    'Sources/EngineDomain/**/*.swift',
    'Sources/EngineStore/**/*.swift',
    'Sources/EngineIOS/**/*.swift',
  ]
  s.preserve_paths = 'Sources/EngineIOS/Modules/*'

  # CocoaPods は podspec の外のファイルを集めないので、engine/.cache/ios の xcframework は検索の場所とリンクの指定で使う
  cache = File.expand_path('../.cache/ios', __dir__)
  %w[sherpa-onnx.xcframework onnxruntime.xcframework].each do |name|
    unless File.directory?(File.join(cache, name))
      raise "#{name} がありません。先に mise run ios-frameworks:fetch を実行する"
    end
  end
  device = "\"#{cache}/sherpa-onnx.xcframework/ios-arm64\" \"#{cache}/onnxruntime.xcframework/ios-arm64\""
  simulator = "\"#{cache}/sherpa-onnx.xcframework/ios-arm64_x86_64-simulator\" " \
              "\"#{cache}/onnxruntime.xcframework/ios-arm64_x86_64-simulator\""
  link = '-framework "SherpaOnnxC" -framework "onnxruntime"'

  s.libraries = 'sqlite3', 'bz2', 'c++'
  s.frameworks = 'AVFoundation', 'BackgroundTasks', 'UIKit', 'CoreML'

  s.dependency 'LiteRTLM'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    # Debug だけのコード(debug-input.wav など)を #if DEBUG で分ける
    'SWIFT_ACTIVE_COMPILATION_CONDITIONS[config=Debug]' => '$(inherited) DEBUG',
    # LiteRT-LM のシミュレーター向けは arm64 しかない
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'x86_64',
    'SWIFT_INCLUDE_PATHS' => '$(inherited) "$(PODS_TARGET_SRCROOT)/Sources/EngineIOS/Modules"',
    'FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]' => "$(inherited) #{device}",
    'FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*]' => "$(inherited) #{simulator}",
  }
  # 静的なライブラリなので、アプリのリンクにも要る。アプリの Swift もこのモジュールの import を読むので、空のモジュールの場所も渡す
  s.user_target_xcconfig = {
    'SWIFT_INCLUDE_PATHS' => "$(inherited) \"#{File.expand_path('Sources/EngineIOS/Modules', __dir__)}\"",
    'FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]' => "$(inherited) #{device}",
    'FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*]' => "$(inherited) #{simulator}",
    'OTHER_LDFLAGS' => "$(inherited) #{link}",
  }
end
