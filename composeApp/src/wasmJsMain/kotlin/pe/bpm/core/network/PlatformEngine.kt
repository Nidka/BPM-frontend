package pe.bpm.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

actual fun platformHttpEngine(): HttpClientEngine = Js.create()
