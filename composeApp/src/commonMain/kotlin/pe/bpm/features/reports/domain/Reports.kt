package pe.bpm.features.reports.domain

/** Cumplimiento de un día; [average] es nulo cuando no hubo ejecuciones. */
data class DayCompliance(val date: String, val runs: Int, val average: Double?)

data class LocationCompliance(val locationId: String, val locationName: String, val runs: Int, val average: Double?, val days: List<DayCompliance>)

data class ComplianceReport(
    val from: String,
    val to: String,
    val runs: Int,
    val average: Double?,
    val days: List<DayCompliance>,
    val locations: List<LocationCompliance>,
)

data class LocationSummary(
    val locationId: String, val locationName: String, val runs: Int, val average: Double?,
    val incidentsReported: Int, val incidentsClosed: Int, val incidentsOpenNow: Int,
    val temperatureMeasurements: Int, val temperatureOutOfRange: Int,
)

data class AreaSummary(val area: String, val runs: Int, val average: Double?)
data class TemplateSummary(val templateId: String, val name: String, val area: String, val runs: Int, val average: Double?)
data class IncidentTotals(val reported: Int, val closed: Int, val openNow: Int)
data class TemperatureTotals(val measurements: Int, val outOfRange: Int)

data class SummaryReport(
    val from: String, val to: String, val runs: Int, val average: Double?,
    val locations: List<LocationSummary>, val areas: List<AreaSummary>, val templates: List<TemplateSummary>,
    val incidents: IncidentTotals, val temperatures: TemperatureTotals,
)

data class ComplianceWindow(val average: Double?, val runs: Int)
data class PendingChecks(val assigned: Int, val pending: Int, val overdue: Int)

data class TrainingKpis(
    val publishedCourses: Int, val learners: Int, val completedEnrollments: Int, val inProgressEnrollments: Int,
    val completionPercent: Double?, val averageBestScore: Double?,
)

/** Panel de indicadores (RF-30). [training] es nulo para CHEF: la interfaz debe ocultar el bloque. */
data class Kpis(
    val generatedAt: String,
    val today: ComplianceWindow, val last7Days: ComplianceWindow, val last30Days: ComplianceWindow,
    val pendingChecks: PendingChecks, val openAlerts: Int, val openIncidents: Int,
    val training: TrainingKpis?,
)

data class CourseProgressRow(
    val courseId: String, val code: String, val title: String, val assignable: Int, val enrolled: Int,
    val inProgress: Int, val completed: Int, val completionPercent: Double?, val averageBestScore: Double?,
)

data class PersonProgressRow(
    val userId: String, val displayName: String, val role: String, val publishedCourses: Int,
    val completed: Int, val inProgress: Int, val averageBestScore: Double?, val lastActivityAt: String?,
)

data class PersonProgressPage(val items: List<PersonProgressRow>, val nextPageToken: String)

data class ReportSettings(val weeklyEmailEnabled: Boolean, val complianceThreshold: Double)

data class ComplianceCertificate(
    val id: String, val periodStart: String, val periodEnd: String, val averageCompliance: Double,
    val threshold: Double, val runs: Int, val verificationCode: String, val issuedAt: String,
)

data class CertificatePage(val items: List<ComplianceCertificate>, val nextPageToken: String)

/** [created] es falso cuando el servidor devolvió la constancia ya emitida para ese período exacto. */
data class IssuedCertificate(val certificate: ComplianceCertificate, val created: Boolean)

/** Datos publicables de la verificación sin sesión; nunca incluye identificadores internos. */
data class PublicCertificate(
    val organizationName: String, val periodStart: String, val periodEnd: String,
    val averageCompliance: Double, val threshold: Double, val issuedAt: String, val verificationCode: String,
)

/** Contenido CSV ya validado como completo; el texto es UTF-8 y no contiene personas. */
data class CsvExport(val fileName: String, val text: String)

/** PDF del mismo informe (A4, en español); los bytes se entregan a la plataforma una sola vez. */
class PdfExport(val fileName: String, val bytes: ByteArray)

enum class ReportFailure {
    CONNECTION, SESSION, DENIED, NOT_FOUND, INVALID_PERIOD, INVALID, NO_DATA, THRESHOLD_NOT_MET, EXPORT_TOO_LARGE,
    CERTIFICATE_NOT_FOUND, RATE_LIMITED, UNAVAILABLE, INVALID_RESPONSE, INCOMPLETE_DOWNLOAD;

    val retryable: Boolean
        get() = this in setOf(CONNECTION, UNAVAILABLE, RATE_LIMITED, INVALID_RESPONSE, INCOMPLETE_DOWNLOAD)
}

sealed interface ReportResult<out T> {
    data class Success<T>(val value: T) : ReportResult<T>
    /** [fields] solo trae valores públicos del servidor (por ejemplo promedio y umbral de una constancia). */
    data class Failure(val reason: ReportFailure, val fields: Map<String, String> = emptyMap()) : ReportResult<Nothing>
}

/** Lectura de cumplimiento, resumen, KPIs y exportación (ADMIN, CHEF con su alcance y SUPER_ADMIN). */
interface ReportsReadRepository {
    suspend fun kpis(org: String): ReportResult<Kpis>
    suspend fun compliance(org: String, from: String, to: String, locationId: String?): ReportResult<ComplianceReport>
    suspend fun summary(org: String, from: String, to: String, locationId: String?): ReportResult<SummaryReport>
    suspend fun exportCsv(org: String, from: String, to: String, locationId: String?): ReportResult<CsvExport>
    suspend fun exportPdf(org: String, from: String, to: String, locationId: String?): ReportResult<PdfExport>
}

/** Operaciones solo para ADMIN y SUPER_ADMIN: personas, configuración y constancias. */
interface ReportsAdminRepository {
    suspend fun trainingCourses(org: String): ReportResult<List<CourseProgressRow>>
    suspend fun trainingPeople(org: String, courseId: String?, pageToken: String?): ReportResult<PersonProgressPage>
    suspend fun settings(org: String): ReportResult<ReportSettings>
    suspend fun saveSettings(org: String, settings: ReportSettings): ReportResult<ReportSettings>
    suspend fun issueCertificate(org: String, from: String, to: String): ReportResult<IssuedCertificate>
    suspend fun certificates(org: String, pageToken: String?): ReportResult<CertificatePage>
}

/** Verificación pública sin sesión. */
interface CertificateVerificationRepository {
    suspend fun verify(code: String): ReportResult<PublicCertificate>
}
