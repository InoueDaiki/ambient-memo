package dev.ambientmemo.bridge

import dev.ambientmemo.engine.domain.currentEngineStatus
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class EngineBridgeModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("EngineBridge")

    AsyncFunction("getStatus") {
      val status = currentEngineStatus()
      mapOf("engineName" to status.engineName, "apiVersion" to status.apiVersion)
    }
  }
}
