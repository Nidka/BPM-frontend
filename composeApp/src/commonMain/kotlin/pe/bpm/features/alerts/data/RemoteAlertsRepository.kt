package pe.bpm.features.alerts.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.*
import pe.bpm.core.network.*
import pe.bpm.features.alerts.domain.*

class RemoteAlertsRepository(
  private val client: HttpClient,
  private val config: AppConfig,
  private val onSessionRejected: (String) -> Unit = {},
  private val onSessionResponse: (HttpResponse) -> Unit = {},
  private val token: suspend () -> String?,
) : AlertsRepository, AlertRuleTransport {
  private fun path(org: String, resource: String): String {
    require(org.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    return "${config.apiBaseUrl}/api/v1/organizations/$org/$resource"
  }

  private suspend fun response(
    org: String,
    resource: String,
    method: HttpMethod = HttpMethod.Get,
    page: PageRequest? = null,
    locationId: String? = null,
    filter: AlertFilter? = null,
    pending: PendingAlertRule? = null,
  ): HttpResponse {
    val access =
      token()?.takeIf { it.isNotBlank() } ?: throw TransportFailure(AlertsFailure.SESSION)
    val r =
      client.request(path(org, resource)) {
        this.method = method
        bearerAuth(access)
        page?.let {
          require(it.size in 1..100 && it.query.length <= 200)
          validatePageToken(it.token)
          pageParameters(it)
        }
        locationId?.let {
          alertId(it)
          parameter("locationId", it)
        }
        filter?.let { parameter("state", it.name) }
        if (method != HttpMethod.Get) {
          contentType(ContentType.Application.Json)
          setBody(pending?.body ?: "{}")
        }
        pending?.let { header("Idempotency-Key", it.key) }
      }
    onSessionResponse(r)
    currentCoroutineContext().ensureActive()
    if (r.status.isSuccess()) return r
    if (r.status.value == 401) onSessionRejected(access)
    throw TransportFailure(
      when (r.status.value) {
        400 -> AlertsFailure.INVALID_PAGE
        401 -> AlertsFailure.SESSION
        403 -> AlertsFailure.DENIED
        404 -> AlertsFailure.NOT_FOUND
        409 -> AlertsFailure.CONFLICT
        422 -> AlertsFailure.INVALID
        429 -> AlertsFailure.RATE_LIMITED
        else -> AlertsFailure.UNAVAILABLE
      }
    )
  }

  override suspend fun inbox(
    org: String,
    filter: AlertFilter,
    page: PageRequest,
  ): AlertsResult<Page<Alert>> = guarded {
    val dto = response(org, "alerts", page = page, filter = filter).body<ListEnvelope<AlertDto>>()
    val rows = dto.data.map { it.domain() }
    require(rows.distinctBy { it.id }.size == rows.size && rows.size <= page.size)
    require(
      rows.all {
        when (filter) {
          AlertFilter.ALL -> true
          AlertFilter.UNREAD -> it.unread
          AlertFilter.VIEWED -> it.viewedAt != null
          AlertFilter.ATTENDED -> it.attendedAt != null
        }
      }
    )
    checkedPage(rows, dto.pagination, page)
  }

  override suspend fun unreadCount(org: String): AlertsResult<Int> = guarded {
    response(org, "alerts/unread-count").body<CountDto>().count.also { require(it >= 0) }
  }

  private suspend fun alert(org: String, id: String, action: String? = null): Alert {
    alertId(id)
    val a =
      response(
          org,
          "alerts/$id${action?.let {"/$it"}.orEmpty()}",
          if (action == null) HttpMethod.Get else HttpMethod.Post,
        )
        .body<Envelope<AlertDto>>()
        .data
        .domain()
    require(a.id == id)
    if (action == "view") require(a.viewedAt != null)
    if (action == "attend") require(a.viewedAt != null && a.attendedAt != null)
    return a
  }

  override suspend fun detail(org: String, id: String): AlertsResult<Alert> = guarded {
    alert(org, id)
  }

  override suspend fun view(org: String, id: String): AlertsResult<Alert> = guarded {
    alert(org, id, "view")
  }

  override suspend fun attend(org: String, id: String): AlertsResult<Alert> = guarded {
    alert(org, id, "attend")
  }

  override suspend fun authorizeRules(org: String, role: String): AlertsResult<Unit> = guarded {
    if (!AlertsAccess.canConfigure(role)) throw TransportFailure(AlertsFailure.DENIED)
    val w = response(org, "workspace").body<JsonObject>()["data"]?.jsonObject ?: error("workspace")
    require(w["organization"]?.jsonObject?.get("id")?.jsonPrimitive?.content == org)
    if (w["role"]?.jsonPrimitive?.content != role) throw TransportFailure(AlertsFailure.DENIED)
  }

  override suspend fun rules(
    org: String,
    locationId: String?,
    page: PageRequest,
  ): AlertsResult<Page<AlertRule>> = guarded {
    val dto =
      response(org, "alert-rules", page = page, locationId = locationId)
        .body<ListEnvelope<RuleDto>>()
    val rows = dto.data.map { it.domain() }
    require(
      rows.size <= page.size &&
        rows.distinctBy { it.id }.size == rows.size &&
        rows.all { locationId == null || it.locationId == locationId }
    )
    checkedPage(rows, dto.pagination, page)
  }

  // El contrato no expone GET por ID: recuperar verifica el ID entre reglas autorizadas.
  override suspend fun rule(org: String, locationId: String?, id: String): AlertsResult<AlertRule> =
    guarded {
      alertId(id)
      var next = ""
      val seen = mutableSetOf<String>()
      repeat(100) {
        val p =
          when (val r = rules(org, locationId, PageRequest(next, size = 100))) {
            is AlertsResult.Success -> r.value
            is AlertsResult.Failure -> throw TransportFailure(r.reason)
          }
        p.items
          .firstOrNull { it.id == id }
          ?.let {
            return@guarded it
          }
        if (p.nextToken.isEmpty()) throw TransportFailure(AlertsFailure.NOT_FOUND)
        require(seen.add(p.nextToken))
        next = p.nextToken
      }
      throw TransportFailure(AlertsFailure.UNAVAILABLE)
    }

  override fun prepare(form: AlertRuleForm, key: String) = prepareAlertRule(form, key)

  override suspend fun submit(
    org: String,
    form: AlertRuleForm,
    pending: PendingAlertRule,
  ): AlertsResult<AlertRule> = guarded {
    require(pending == prepare(form, pending.key))
    val r =
      response(
          org,
          if (form.original == null) "alert-rules" else "alert-rules/${form.id}",
          if (form.original == null) HttpMethod.Post else HttpMethod.Put,
          pending = pending,
        )
        .body<Envelope<RuleDto>>()
        .data
        .domain()
    val expectedVersion: Long = form.original?.rowVersion?.plus(1L) ?: 1L
    require(
      r.id == form.id &&
        r.locationId == form.locationId &&
        r.type == form.type &&
        r.name == form.values.getValue("name").trim() &&
        r.configuration == AlertRuleValidation.configuration(form) &&
        r.active == form.active &&
        r.emailEnabled == form.emailEnabled &&
        r.pushEnabled == form.pushEnabled &&
        r.rowVersion == expectedVersion
    )
    r
  }
}

private class TransportFailure(val reason: AlertsFailure) : Exception()

private suspend inline fun <T> guarded(block: () -> T): AlertsResult<T> =
  try {
    val r = block()
    currentCoroutineContext().ensureActive()
    AlertsResult.Success(r)
  } catch (e: CancellationException) {
    throw e
  } catch (e: TransportFailure) {
    AlertsResult.Failure(e.reason)
  } catch (e: SessionAccessException) {
    AlertsResult.Failure(if (e.expired) AlertsFailure.SESSION else AlertsFailure.CONNECTION)
  } catch (_: SerializationException) {
    AlertsResult.Failure(AlertsFailure.INVALID_RESPONSE)
  } catch (_: JsonConvertException) {
    AlertsResult.Failure(AlertsFailure.INVALID_RESPONSE)
  } catch (_: IllegalArgumentException) {
    AlertsResult.Failure(AlertsFailure.INVALID_RESPONSE)
  } catch (_: IllegalStateException) {
    AlertsResult.Failure(AlertsFailure.INVALID_RESPONSE)
  } catch (_: Exception) {
    AlertsResult.Failure(AlertsFailure.CONNECTION)
  }

@Serializable private data class Envelope<T>(val data: T)

@Serializable private data class ListEnvelope<T>(val data: List<T>, val pagination: PageMetadata)

@Serializable private data class CountDto(val count: Int)
