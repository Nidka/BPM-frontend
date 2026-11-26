package pe.bpm.features.reports.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.reports.domain.*

/** Encabezado que debe tener todo CSV de historial; sirve para reconocer descargas incorrectas. */
/** Encabezado actual (sin identificadores: sede por nombre). Se sigue aceptando el anterior para no romper entre despliegues. */
internal const val CSV_HEADER_NAMES = "Fecha local,Enviado el,Sede,Control,Área,Versión de la plantilla,Cumplimiento (%)"
internal const val CSV_HEADER = "ID de ejecución,Fecha local,Enviado el,ID de sede,Sede,Control,Área,Versión de la plantilla,Cumplimiento (%)"

/**
 * Adaptador Ktor de reportes. No registra cuerpos ni tokens; los mensajes del servidor se descartan y solo
 * se conservan códigos públicos. La verificación pública no envía credenciales.
 */
class RemoteReportsRepository(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : ReportsReadRepository, ReportsAdminRepository, CertificateVerificationRepository {

    override suspend fun kpis(org: String): ReportResult<Kpis> = guarded {
        get<Envelope<KpisDto>>(org, "reports/kpis").data.domain()
    }

    override suspend fun compliance(org: String, from: String, to: String, locationId: String?): ReportResult<ComplianceReport> = guarded {
        val report = get<Envelope<ComplianceDto>>(org, "reports/compliance", periodQuery(from, to, locationId)).data.domain()
        require(report.from == from && report.to == to)
        report
    }

    override suspend fun summary(org: String, from: String, to: String, locationId: String?): ReportResult<SummaryReport> = guarded {
        val report = get<Envelope<SummaryDto>>(org, "reports/summary", periodQuery(from, to, locationId)).data.domain()
        require(report.from == from && report.to == to)
        report
    }

    override suspend fun exportCsv(org: String, from: String, to: String, locationId: String?): ReportResult<CsvExport> = guarded {
        val response = request(org, "reports/compliance/export.csv", periodQuery(from, to, locationId)) { timeout { requestTimeoutMillis = 120_000L; socketTimeoutMillis = 120_000L } }
        require(response.contentType()?.match(ContentType.Text.CSV) == true)
        val bytes: ByteArray = response.body()
        currentCoroutineContext().ensureActive()
        // Un flujo cortado después del primer byte deja el archivo truncado: se exige tamaño y cierre de fila.
        val expected = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (expected != null && expected != bytes.size.toLong()) throw TransportFailure(ReportFailure.INCOMPLETE_DOWNLOAD)
        val text = bytes.decodeToString().removePrefix("﻿")
        if (!(text.startsWith(CSV_HEADER) || text.startsWith(CSV_HEADER_NAMES)) || !text.endsWith("\n")) throw TransportFailure(ReportFailure.INCOMPLETE_DOWNLOAD)
        // Excel necesita la marca BOM UTF-8 para leer bien los acentos del encabezado en español.
        CsvExport("checklists_${from}_$to.csv", "\uFEFF" + text)
    }

    override suspend fun exportPdf(org: String, from: String, to: String, locationId: String?): ReportResult<PdfExport> = guarded {
        val response = request(org, "reports/compliance/export.pdf", periodQuery(from, to, locationId)) { timeout { requestTimeoutMillis = 120_000L; socketTimeoutMillis = 120_000L } }
        require(response.contentType()?.match(ContentType.Application.Pdf) == true)
        val bytes: ByteArray = response.body()
        currentCoroutineContext().ensureActive()
        // Igual que el CSV: tamaño completo y firma de PDF; un flujo cortado no se guarda como si estuviera bien.
        val expected = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (expected != null && expected != bytes.size.toLong()) throw TransportFailure(ReportFailure.INCOMPLETE_DOWNLOAD)
        if (bytes.size < 8 || bytes.decodeToString(0, 5) != "%PDF-") throw TransportFailure(ReportFailure.INCOMPLETE_DOWNLOAD)
        PdfExport("checklists_${from}_$to.pdf", bytes)
    }

    override suspend fun trainingCourses(org: String): ReportResult<List<CourseProgressRow>> = guarded {
        val items = get<PagedEnvelope<CourseProgressDto>>(org, "reports/training/courses").data.map { it.domain() }
        require(items.map { it.courseId }.distinct().size == items.size)
        items
    }

    override suspend fun trainingPeople(org: String, courseId: String?, pageToken: String?): ReportResult<PersonProgressPage> = guarded {
        val query = buildList {
            add("pageSize" to "25")
            pageToken?.takeIf { it.isNotEmpty() }?.let { add("pageToken" to it) }
            courseId?.let { add("courseId" to id(it)) }
        }
        val envelope = get<PagedEnvelope<PersonProgressDto>>(org, "reports/training/people", query)
        val items = envelope.data.map { it.domain() }
        require(items.size <= 100 && items.map { it.userId }.distinct().size == items.size)
        PersonProgressPage(items, envelope.pagination.nextPageToken)
    }

    override suspend fun settings(org: String): ReportResult<ReportSettings> = guarded {
        get<Envelope<SettingsDto>>(org, "reports/settings").data.domain()
    }

    override suspend fun saveSettings(org: String, settings: ReportSettings): ReportResult<ReportSettings> = guarded {
        val body = buildJsonObject { put("weeklyEmailEnabled", settings.weeklyEmailEnabled); put("complianceThreshold", settings.complianceThreshold) }
        val saved = send<Envelope<SettingsDto>>(org, "reports/settings", HttpMethod.Put, body).data.domain()
        require(saved.weeklyEmailEnabled == settings.weeklyEmailEnabled)
        saved
    }

    override suspend fun issueCertificate(org: String, from: String, to: String): ReportResult<IssuedCertificate> = guarded {
        val response = request(org, "reports/certificates", emptyList(), HttpMethod.Post, buildJsonObject { put("from", from); put("to", to) })
        val certificate = response.body<Envelope<CertificateDto>>().data.domain()
        require(certificate.periodStart == from && certificate.periodEnd == to)
        IssuedCertificate(certificate, response.status == HttpStatusCode.Created)
    }

    override suspend fun certificates(org: String, pageToken: String?): ReportResult<CertificatePage> = guarded {
        val query = buildList {
            add("pageSize" to "25")
            pageToken?.takeIf { it.isNotEmpty() }?.let { add("pageToken" to it) }
        }
        val envelope = get<PagedEnvelope<CertificateDto>>(org, "reports/certificates", query)
        val items = envelope.data.map { it.domain() }
        require(items.map { it.id }.distinct().size == items.size)
        CertificatePage(items, envelope.pagination.nextPageToken)
    }

    override suspend fun verify(code: String): ReportResult<PublicCertificate> = guarded {
        require(Regex("[A-Z2-7]{24}").matches(code))
        currentCoroutineContext().ensureActive()
        val response = client.get("${config.apiBaseUrl}/api/v1/compliance-certificates/$code")
        currentCoroutineContext().ensureActive()
        if (!response.status.isSuccess()) throw TransportFailure(failureFor(response.status.value, errorCode(response)))
        val certificate = response.body<Envelope<PublicCertificateDto>>().data.domain()
        require(certificate.verificationCode == code)
        certificate
    }

    // ---- Transporte --------------------------------------------------------------------------

    private fun id(value: String): String { require(Regex("[A-Za-z0-9_-]{1,64}").matches(value)); return value }

    private fun periodQuery(from: String, to: String, locationId: String?): List<Pair<String, String>> {
        require(ReportDates.parse(from) != null && ReportDates.parse(to) != null)
        return buildList { add("from" to from); add("to" to to); locationId?.let { add("locationId" to id(it)) } }
    }

    private suspend inline fun <reified T> get(org: String, resource: String, query: List<Pair<String, String>> = emptyList()): T =
        request(org, resource, query).body()

    private suspend inline fun <reified T> send(org: String, resource: String, verb: HttpMethod, body: JsonObject): T =
        request(org, resource, emptyList(), verb, body).body()

    private suspend fun request(
        org: String, resource: String, query: List<Pair<String, String>>, verb: HttpMethod = HttpMethod.Get,
        body: JsonObject? = null, configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(org))
        val access = token()?.takeIf { it.isNotBlank() } ?: throw TransportFailure(ReportFailure.SESSION)
        currentCoroutineContext().ensureActive()
        val response = client.request("${config.apiBaseUrl}/api/v1/organizations/$org/$resource") {
            method = verb
            bearerAuth(access)
            query.forEach { (name, value) -> parameter(name, value) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            configure()
        }
        onSessionResponse(response)
        currentCoroutineContext().ensureActive()
        if (!response.status.isSuccess()) {
            if (response.status.value == 401) onSessionRejected(access)
            throw TransportFailure(failureFor(response.status.value, errorCode(response)), publicFields(response))
        }
        return response
    }

    private suspend fun errorCode(response: HttpResponse): String =
        if (response.status.value in setOf(400, 404, 409, 422)) {
            try { response.body<ErrorsEnvelope>().errors.firstOrNull()?.code.orEmpty() } catch (c: CancellationException) { throw c } catch (_: Exception) { "" }
        } else ""

    /** Solo se conservan promedio y umbral de una constancia rechazada; el resto del detalle se descarta. */
    private suspend fun publicFields(response: HttpResponse): Map<String, String> =
        if (response.status.value == 422) {
            try {
                response.body<ErrorsEnvelope>().errors.firstOrNull()?.fields.orEmpty()
                    .filterKeys { it == "averageCompliance" || it == "threshold" }
                    .filterValues { Regex("\\d{1,3}\\.\\d{2}").matches(it) }
            } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyMap() }
        } else emptyMap()
}

/** Traduce estado HTTP y código de dominio a un motivo público. */
internal fun failureFor(status: Int, code: String): ReportFailure = when (status) {
    400 -> ReportFailure.INVALID
    401 -> ReportFailure.SESSION
    403 -> ReportFailure.DENIED
    404 -> if (code == "CERTIFICATE_NOT_FOUND") ReportFailure.CERTIFICATE_NOT_FOUND else ReportFailure.NOT_FOUND
    422 -> when (code) {
        "REPORT_NO_DATA" -> ReportFailure.NO_DATA
        "REPORT_THRESHOLD_NOT_MET" -> ReportFailure.THRESHOLD_NOT_MET
        "EXPORT_TOO_LARGE" -> ReportFailure.EXPORT_TOO_LARGE
        else -> ReportFailure.INVALID_PERIOD
    }
    429 -> ReportFailure.RATE_LIMITED
    else -> ReportFailure.UNAVAILABLE
}

private class TransportFailure(val reason: ReportFailure, val fields: Map<String, String> = emptyMap()) : Exception()

private suspend inline fun <T> guarded(block: () -> T): ReportResult<T> =
    try {
        val result = block()
        currentCoroutineContext().ensureActive()
        ReportResult.Success(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TransportFailure) {
        ReportResult.Failure(error.reason, error.fields)
    } catch (error: SessionAccessException) {
        ReportResult.Failure(if (error.expired) ReportFailure.SESSION else ReportFailure.CONNECTION)
    } catch (_: SerializationException) {
        ReportResult.Failure(ReportFailure.INVALID_RESPONSE)
    } catch (_: JsonConvertException) {
        ReportResult.Failure(ReportFailure.INVALID_RESPONSE)
    } catch (_: IllegalArgumentException) {
        ReportResult.Failure(ReportFailure.INVALID_RESPONSE)
    } catch (_: IllegalStateException) {
        ReportResult.Failure(ReportFailure.INVALID_RESPONSE)
    } catch (_: Exception) {
        ReportResult.Failure(ReportFailure.CONNECTION)
    }
