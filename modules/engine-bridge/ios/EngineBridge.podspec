# iOS の Expo Module。React Native と Swift のエンジンをつなぐ。
# 今はエンジンがないので、BridgeEngine.swift の仮の実装を使う
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

  s.source_files = '**/*.swift'
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES'
  }
end
