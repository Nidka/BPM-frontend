package pe.bpm.features.checklists

import androidx.lifecycle.ViewModelStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.checklists.data.RemoteChecklistCatalogRepository
import pe.bpm.features.checklists.data.RemoteChecklistTemplatePublisher
import pe.bpm.features.checklists.domain.*
import pe.bpm.features.checklists.presentation.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChecklistPublicationTest {
    private fun id(n: Int) = "10000000-0000-0000-0000-${n.toString().padStart(12,'0')}"
    private fun draft() = ChecklistTemplateDraft(id(1),id(2),"Apertura","Antes del turno","Cocina",listOf(
        ChecklistItem(id(3),"Personal","¿Lavado de manos?",ChecklistResponseType.COMPLIANCE,1,true,"",null,null,false)))
    private fun published(d: ChecklistTemplateDraft) = ChecklistTemplate(d.id,d.name,d.description,d.area,d.versionId,1,d.items)
    private val headers = headersOf("Content-Type","application/json")

    @Test fun validationRejectsMissingQuestionsWrongRangesAndEvidenceBeforeAnyWrite()=runTest {
        var writes=0
        val useCase=PublishChecklistTemplate(object:ChecklistTemplatePublisher {
            override suspend fun publish(organizationId:String,draft:ChecklistTemplateDraft):TemplatePublicationResult {writes++;return TemplatePublicationResult.Published(published(draft))}
        })
        val d=draft()
        for(invalid in listOf(d.copy(name=" "),d.copy(items=emptyList()),d.copy(items=d.items+d.items),
            d.copy(items=listOf(d.items[0].copy(responseType=ChecklistResponseType.TEMPERATURE,minimumValue=10.123,maximumValue=2.0))),
            d.copy(items=listOf(d.items[0].copy(evidenceRequiredOnFailure=true))))) {
            assertEquals(PublicationFailure.VALIDATION,assertIs<TemplatePublicationResult.Rejected>(useCase("org",invalid)).reason)
        }
        assertEquals(0,writes)
        val result=assertIs<TemplatePublicationResult.Published>(useCase("org",d.copy(name="  Apertura  ")))
        assertEquals("Apertura",result.template.name);assertEquals(1,writes)
    }

    @Test fun aLostPublicationResponseIsConfirmedOnRetryWithoutAnotherPost()=runTest {
        var writes=0;var saved:JsonObject?=null
        val client=createHttpClient(MockEngine { request ->
            assertEquals("Bearer token",request.headers["Authorization"])
            if(request.method==HttpMethod.Post) {
                writes++
                val body=Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertFalse("organizationId" in body);assertFalse("versionNumber" in body);assertFalse("role" in body)
                saved=JsonObject(body+mapOf("versionNumber" to JsonPrimitive(1)))
                respond("respuesta interrumpida",HttpStatusCode.ServiceUnavailable,headers)
            } else if(saved==null)respond("{}",HttpStatusCode.NotFound,headers)
            else respond(JsonObject(mapOf("data" to saved)).toString(),HttpStatusCode.OK,headers)
        })
        try {
            val reader=RemoteChecklistCatalogRepository(client,AppConfig("https://api.example.com")){"token"}
            val writer=RemoteChecklistTemplatePublisher(client,AppConfig("https://api.example.com"),reader){"token"}
            val useCase=PublishChecklistTemplate(writer)
            assertIs<TemplatePublicationResult.Rejected>(useCase("org",draft()))
            assertIs<TemplatePublicationResult.Published>(useCase("org",draft()))
            assertEquals(1,writes)
        }finally{client.close()}
    }
    @Test fun anExistingDifferentTemplateIsNotReportedAsPublishedOrOverwritten()=runTest {
        var writes=0
        val client=createHttpClient(MockEngine { writes++;respond("{}",HttpStatusCode.OK,headers) })
        val reader=object:ChecklistCatalogRepository {
            override suspend fun catalog(organizationId:String,request:pe.bpm.core.domain.PageRequest)=ChecklistCatalogResult.Success(emptyList())
            override suspend fun template(organizationId:String,templateId:String)=ChecklistTemplateResult.Success(published(draft()).copy(name="Otra plantilla"))
        }
        try{
            val result=RemoteChecklistTemplatePublisher(client,AppConfig("https://api.example.com"),reader){"token"}.publish("org",draft())
            assertEquals(PublicationFailure.CONFLICT,assertIs<TemplatePublicationResult.Rejected>(result).reason);assertEquals(0,writes)
        }finally{client.close()}
    }
    @Test fun writesRequireSessionAndFieldErrorsNeverExposeProviderMessages()=runTest {
        var access:String?=null;var calls=0
        val client=createHttpClient(MockEngine { request ->
            calls++
            if(request.method==HttpMethod.Get) respond("{}",HttpStatusCode.NotFound,headers)
            else respond("""{"errors":[{"message":"SQL private","fields":{"name":"private","password":"secret"}}]}""",HttpStatusCode.UnprocessableEntity,headers)
        })
        try{
            val reader=RemoteChecklistCatalogRepository(client,AppConfig("https://api.example.com")){access}
            val writer=RemoteChecklistTemplatePublisher(client,AppConfig("https://api.example.com"),reader){access}
            assertEquals(PublicationFailure.SESSION_REQUIRED,assertIs<TemplatePublicationResult.Rejected>(writer.publish("org",draft())).reason);assertEquals(0,calls)
            access="token"
            val failure=assertIs<TemplatePublicationResult.Rejected>(writer.publish("org",draft()))
            assertEquals(setOf("name"),failure.fields.keys);assertFalse(failure.toString().contains("private"));assertFalse(failure.toString().contains("secret"))
        }finally{client.close()}
    }
    @Test fun aNameConflictWithoutTheDraftIdAllowsCorrectingTheName()=runTest {
        var writes=0
        val client=createHttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Get) respond("{}",HttpStatusCode.NotFound,headers)
            else { writes++;respond("{}",HttpStatusCode.Conflict,headers) }
        })
        try {
            val reader=RemoteChecklistCatalogRepository(client,AppConfig("https://api.example.com")){"token"}
            val writer=RemoteChecklistTemplatePublisher(client,AppConfig("https://api.example.com"),reader){"token"}
            val failure=assertIs<TemplatePublicationResult.Rejected>(writer.publish("org",draft()))
            assertEquals(PublicationFailure.VALIDATION,failure.reason)
            assertEquals(setOf("name"),failure.fields.keys);assertEquals(1,writes)
        } finally { client.close() }
    }
    @Test fun oversizedPayloadsAreRejectedBeforeAnyNetworkRequest()=runTest {
        var calls=0
        val client=createHttpClient(MockEngine { calls++;respond("{}",HttpStatusCode.NotFound,headers) })
        try {
            val reader=RemoteChecklistCatalogRepository(client,AppConfig("https://api.example.com")){"token"}
            val writer=RemoteChecklistTemplatePublisher(client,AppConfig("https://api.example.com"),reader){"token"}
            val large=draft().copy(items=(1..100).map { draft().items.single().copy(id=id(it+10),sectionName="á".repeat(120),label="ó".repeat(300),displayOrder=it) })
            val failure=assertIs<TemplatePublicationResult.Rejected>(PublishChecklistTemplate(writer)("org",large))
            assertEquals(PublicationFailure.VALIDATION,failure.reason)
            assertEquals(setOf("items"),failure.fields.keys);assertEquals(0,calls)
        } finally { client.close() }
    }
    @Test fun rejectedWritesInvalidateOnlyTheCapturedSession()=runTest {
        var rejected:String?=null
        val client=createHttpClient(MockEngine { request ->
            if(request.method==HttpMethod.Get) respond("{}",HttpStatusCode.NotFound,headers)
            else respond("{}",HttpStatusCode.Unauthorized,headers)
        })
        try {
            val reader=RemoteChecklistCatalogRepository(client,AppConfig("https://api.example.com")){"captured-token"}
            val writer=RemoteChecklistTemplatePublisher(client,AppConfig("https://api.example.com"),reader,onSessionRejected={rejected=it}){"captured-token"}
            assertEquals(PublicationFailure.SESSION_REQUIRED,assertIs<TemplatePublicationResult.Rejected>(writer.publish("org",draft())).reason)
            assertEquals("captured-token",rejected)
        } finally { client.close() }
    }
    @Test fun editorPreservesQuestionIdsOrderUndoAndUncertainPayload()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore();var sequence=10
        val submitted=mutableListOf<ChecklistTemplateDraft>()
        val useCase=PublishChecklistTemplate(object:ChecklistTemplatePublisher {
            override suspend fun publish(organizationId:String,draft:ChecklistTemplateDraft):TemplatePublicationResult {
                submitted+=draft
                return if(submitted.size==1)TemplatePublicationResult.Rejected(PublicationFailure.UNAVAILABLE) else TemplatePublicationResult.Published(published(draft))
            }
        })
        try{
            val vm=ChecklistTemplateEditorViewModel(useCase){id(sequence++)};store.put("vm",vm);vm.open("user","org")
            vm.name("Apertura");vm.area("Cocina");val q=vm.state.value.questions.single().id
            vm.question(q){it.copy(section="Personal",label="Lavado de manos")};vm.addQuestion()
            val second=vm.state.value.questions.last().id;vm.question(second){it.copy(section="Instalaciones",label="Área limpia")}
            vm.move(second,-1);assertEquals(listOf(second,q),vm.state.value.questions.map{it.id})
            vm.removeQuestion(q);vm.undoRemove();assertEquals(listOf(second,q),vm.state.value.questions.map{it.id})
            vm.review();vm.confirmPublication();vm.confirmPublication();advanceUntilIdle()
            assertTrue(vm.state.value.uncertain);vm.name("No se aplica");vm.backToEdit();assertEquals("Apertura",vm.state.value.name)
            vm.confirmPublication();advanceUntilIdle();assertEquals(submitted.first(),submitted.last());assertEquals(2,submitted.size)
            assertEquals(TemplateEditorStep.Published,vm.state.value.step);vm.open("user","org");assertNotNull(vm.state.value.published)
        }finally{store.clear();Dispatchers.resetMain()}
    }
    @Test fun resetCancelsLatePublicationAndClearsTheDraftAcrossUsers()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore();val release=CompletableDeferred<Unit>();var sequence=20
        val useCase=PublishChecklistTemplate(object:ChecklistTemplatePublisher {
            override suspend fun publish(organizationId:String,draft:ChecklistTemplateDraft):TemplatePublicationResult {withContext(NonCancellable){release.await()};return TemplatePublicationResult.Published(published(draft))}
        })
        try{
            val vm=ChecklistTemplateEditorViewModel(useCase){id(sequence++)};store.put("vm",vm);vm.open("user","org");vm.name("Nombre privado");vm.area("Cocina")
            vm.question(vm.state.value.questions.single().id){it.copy(section="Personal",label="Lavado de manos")}
            vm.review();vm.confirmPublication();runCurrent();vm.reset();vm.open("other","other-org");release.complete(Unit);advanceUntilIdle()
            assertEquals("other",vm.state.value.userId);assertEquals("",vm.state.value.name);assertNull(vm.state.value.published)
        }finally{release.complete(Unit);store.clear();Dispatchers.resetMain()}
    }
}
