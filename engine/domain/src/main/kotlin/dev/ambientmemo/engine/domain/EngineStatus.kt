package dev.ambientmemo.engine.domain

data class EngineStatus(
    val engineName: String,
    val apiVersion: Int,
)

fun currentEngineStatus(): EngineStatus = EngineStatus(engineName = "ambient-memo-engine", apiVersion = 1)
