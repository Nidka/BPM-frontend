package pe.bpm.screenshots

import androidx.compose.runtime.Composable
import java.lang.reflect.Proxy
import pe.bpm.core.designsystem.BpmAccessScreen
import pe.bpm.features.accounts.domain.AcceptInvitation
import pe.bpm.features.accounts.domain.AccountsRepository
import pe.bpm.features.accounts.presentation.InvitationAcceptScreen
import pe.bpm.features.accounts.presentation.InvitationAcceptViewModel
import pe.bpm.features.connection.presentation.ConnectionScreen
import pe.bpm.features.connection.presentation.ConnectionUiState
import pe.bpm.features.identity.domain.IdentityError
import pe.bpm.features.identity.domain.Profile
import pe.bpm.features.identity.presentation.AuthMode
import pe.bpm.features.identity.presentation.IdentityScreen
import pe.bpm.features.identity.presentation.IdentityUiState
import pe.bpm.features.reports.domain.CertificateVerificationRepository
import pe.bpm.features.reports.domain.PublicCertificate
import pe.bpm.features.reports.domain.ReportFailure
import pe.bpm.features.reports.domain.ReportResult
import pe.bpm.features.reports.domain.VerifyComplianceCertificate
import pe.bpm.features.reports.presentation.CertificateVerificationScreen
import pe.bpm.features.reports.presentation.CertificateVerificationViewModel
import pe.bpm.features.reports.presentation.VerificationState
import pe.bpm.features.workspace.domain.Organization
import pe.bpm.features.workspace.presentation.OrganizationPickerScreen
import pe.bpm.features.workspace.presentation.WorkspacePageState

/**
 * Pantallas de acceso (sin sesión) y selección de organización para el loop de diseño
 * (`docs/superpowers/plans/2026-11-22-loop-pantallas.md`). Se pueden pedir solas con la variable de entorno
 * `BPM_SCREENSHOTS_ONLY=ingreso,conexion` (prefijos separados por comas).
 */
private val certificate = PublicCertificate(
    organizationName = "Cocina Andina", periodStart = "2026-10-01", periodEnd = "2026-10-31",
    averageCompliance = 94.0, threshold = 90.0, issuedAt = "2026-11-02T15:00:00Z", verificationCode = "ABCDEFGHIJKLMNOPQRSTUVWX",
)

private val verificationRepository = object : CertificateVerificationRepository {
    override suspend fun verify(code: String): ReportResult<PublicCertificate> = ReportResult.Success(certificate)
}

/** El repositorio solo se toca al aceptar; con la pantalla en reposo ninguna llamada llega aquí. */
private val unusedAccountsRepository: AccountsRepository = Proxy.newProxyInstance(
    AccountsRepository::class.java.classLoader, arrayOf(AccountsRepository::class.java),
) { _, method, _ -> error("No se usa en la captura: ${method.name}") } as AccountsRepository

private val organizations = listOf(
    Organization("o1", "Inversiones Gastronómicas Andina S.A.C.", "Cocina Andina"),
    Organization("o2", "Catering del Sur E.I.R.L.", "Catering del Sur"),
    Organization("o3", "Cafetería Miraflores S.R.L.", "Cafetería Miraflores"),
)

@Composable private fun Identity(state: IdentityUiState) = IdentityScreen(
    state = state, onLogin = { _, _ -> }, onLogout = {}, onVerify = {}, googleSupported = true, onGoogle = {},
    onMode = {}, onRegister = { _, _, _, _, _ -> }, onRecover = {}, onResend = {}, onConfirmEmail = {},
)

internal val accessScreens: List<ScreenSpec> = listOf(
    ScreenSpec("ingreso") { { Identity(IdentityUiState()) } },
    ScreenSpec("ingreso-error") {
        {
            Identity(IdentityUiState(errors = listOf(IdentityError("INVALID_CREDENTIALS", "El correo o la contraseña no son correctos."))))
        }
    },
    ScreenSpec("ingreso-crear") { { Identity(IdentityUiState(mode = AuthMode.Register)) } },
    ScreenSpec("ingreso-recuperar") { { Identity(IdentityUiState(mode = AuthMode.Recovery)) } },
    ScreenSpec("ingreso-verificar") {
        { Identity(IdentityUiState(mode = AuthMode.Verification, verificationEmail = "ana.perez@restaurante.pe", canResendVerification = true)) }
    },
    ScreenSpec("conexion") { { ConnectionScreen(ConnectionUiState.Connected("06e3992a1b2c"), onRetry = {}) } },
    ScreenSpec("conexion-error") { { ConnectionScreen(ConnectionUiState.Unavailable("No pudimos comunicarnos con el servicio. Inténtalo de nuevo."), onRetry = {}) } },
    ScreenSpec("verificar-constancia") {
        val viewModel = CertificateVerificationViewModel(VerifyComplianceCertificate(verificationRepository))
        val content: @Composable () -> Unit = {
            CertificateVerificationScreen("ABCDEFGHIJKLMNOPQRSTUVWX", viewModel, VerificationState(code = "ABCDEFGHIJKLMNOPQRSTUVWX", certificate = certificate))
        }
        content
    },
    ScreenSpec("verificar-constancia-error") {
        val viewModel = CertificateVerificationViewModel(VerifyComplianceCertificate(verificationRepository))
        val content: @Composable () -> Unit = {
            CertificateVerificationScreen("ABCDEFGHIJKLMNOPQRSTUVWX", viewModel, VerificationState(failure = ReportFailure.CERTIFICATE_NOT_FOUND))
        }
        content
    },
    ScreenSpec("organizaciones") {
        {
            BpmAccessScreen {
                OrganizationPickerScreen(
                    organizations = organizations, selectedId = "o1", loading = false, errors = emptyList(), canCreate = true,
                    onSelect = {}, onCreate = {}, onUserActivity = {}, notice = {}, noticeVisible = false,
                    page = WorkspacePageState(nextToken = "siguiente"), onSearch = {}, onNext = {}, onPrevious = {}, onRetry = {},
                )
            }
        }
    },
    ScreenSpec("invitacion") {
        val viewModel = InvitationAcceptViewModel(AcceptInvitation(unusedAccountsRepository))
        val content: @Composable () -> Unit = { BpmAccessScreen(scrollable = false) { InvitationAcceptScreen(viewModel, onContinue = {}) } }
        content
    },
)
