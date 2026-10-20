package pe.bpm.core.presentation

import androidx.compose.runtime.Composable

/** Avisa al volver a una app o pestaña visible; no representa una interacción del usuario. */
@Composable expect fun OnAppForeground(onForeground: () -> Unit)
