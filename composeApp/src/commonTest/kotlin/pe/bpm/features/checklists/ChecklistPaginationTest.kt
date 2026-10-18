package pe.bpm.features.checklists

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import pe.bpm.core.domain.PageRequest
import pe.bpm.features.checklists.domain.*
import pe.bpm.features.checklists.presentation.ChecklistCatalogViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChecklistPaginationTest {
    @Test fun pagesAndSearchUseServerQueriesAndDetailLoadsOutsideFirstPage()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore();val seen=mutableListOf<PageRequest>()
        val first=ChecklistTemplate("first","Primera","","Cocina","v1",1,emptyList())
        val last=first.copy(id="last",name="Última")
        val repository=object:ChecklistCatalogRepository {
            override suspend fun catalog(organizationId:String,request:PageRequest):ChecklistCatalogResult {
                seen+=request
                return if(request.token.isEmpty()&&request.query.isEmpty())ChecklistCatalogResult.Success(listOf(first),"next") else ChecklistCatalogResult.Success(listOf(last))
            }
            override suspend fun template(organizationId:String,templateId:String)=ChecklistTemplateResult.Success(last)
        }
        try {
            val vm=ChecklistCatalogViewModel(LoadChecklistCatalog(repository),LoadChecklistTemplate(repository));store.put("vm",vm)
            vm.open("user","org");advanceUntilIdle();vm.showTemplate("last");advanceUntilIdle()
            assertEquals("last",vm.state.value.detail?.id);assertEquals(listOf(first),vm.state.value.templates)
            vm.showTemplate(null);vm.next();advanceUntilIdle();assertEquals("next",seen.last().token);assertEquals(listOf(last),vm.state.value.templates)
            vm.previous();advanceUntilIdle();assertEquals("",seen.last().token)
            vm.search("descartar");vm.search("última");advanceUntilIdle();assertEquals(1,seen.count { it.query.isNotEmpty() });assertEquals("última",seen.last().query);assertEquals("",seen.last().token)
            vm.open("user","other");advanceUntilIdle();assertNull(vm.state.value.detail);assertEquals("",vm.state.value.query)
        }finally{store.clear();Dispatchers.resetMain()}
    }
    @Test fun deniedDetailAlsoClearsThePreviouslyAuthorizedCatalog()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore()
        val repository=object:ChecklistCatalogRepository {
            override suspend fun catalog(organizationId:String,request:PageRequest)=ChecklistCatalogResult.Success(listOf(ChecklistTemplate("first","Primera","","Cocina","v1",1,emptyList())))
            override suspend fun template(organizationId:String,templateId:String)=ChecklistTemplateResult.Failure(ChecklistFailure.ACCESS_DENIED)
        }
        try {
            val vm=ChecklistCatalogViewModel(LoadChecklistCatalog(repository),LoadChecklistTemplate(repository));store.put("vm",vm)
            vm.open("user","org");advanceUntilIdle();assertTrue(vm.state.value.templates.isNotEmpty())
            vm.showTemplate("first");advanceUntilIdle()
            assertTrue(vm.state.value.templates.isEmpty());assertNull(vm.state.value.detail);assertEquals(ChecklistFailure.ACCESS_DENIED,vm.state.value.failure)
        }finally{store.clear();Dispatchers.resetMain()}
    }

}
