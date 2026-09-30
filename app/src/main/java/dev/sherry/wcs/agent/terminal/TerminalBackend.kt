package dev.sherry.wcs.agent.terminal

import dev.sherry.wcs.agent.environment.EnvironmentSnapshot

interface TerminalBackend {
    suspend fun start(
        environment: EnvironmentSnapshot,
        argv: List<String>,
        workingDirectory: String?,
        environmentVariables: Map<String, String>,
        cols: Int,
        rows: Int,
    ): TerminalBackendStart
}
