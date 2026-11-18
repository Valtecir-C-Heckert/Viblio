package br.com.viblio.account

import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test

class LibrarySyncPolicyTest {
    private fun sample()=JsonParser.parseString("""{"library":{"books":[{"id":"b","currentPage":61}],"sessions":[{"id":"s","durationMs":60000}]},"appearance":{"SHELF_SPINE_ZOOM":"0.75","SHELF_MANUAL_ORDER:book":"b","SHELF_READING_GOAL:x":"private"}}""").asJsonObject
    private fun conflict(vararg paths:String,revision:Long=2)=AccountFailure(409,JsonObject().apply {add("detail",JsonObject().apply {addProperty("code","conflicts");addProperty("revision",revision);add("conflicts",JsonArray().apply {paths.forEach {p->add(JsonObject().apply {addProperty("path",p)})}})})},"Conflito")
    private fun changed()=AccountFailure(409,JsonObject().apply {add("detail",JsonObject().apply {addProperty("code","changed_again")})},"Mudou")
    @Test fun excludesOnlyZoomWithoutMutatingOriginal(){
        val original=sample();val local=LibrarySyncPolicy.shared(original)
        assertFalse(local.getAsJsonObject("appearance").has("SHELF_SPINE_ZOOM"));assertTrue(original.getAsJsonObject("appearance").has("SHELF_SPINE_ZOOM"))
        assertEquals(original.get("library"),local.get("library"));assertTrue(local.getAsJsonObject("appearance").has("SHELF_READING_GOAL:x"));assertTrue(local.getAsJsonObject("appearance").has("SHELF_MANUAL_ORDER:book"))
    }
    @Test fun normalSendExcludesZoom(){LibrarySyncPolicy.submit(sample(),1){r->assertFalse(r.getAsJsonObject("snapshot").getAsJsonObject("appearance").has("SHELF_SPINE_ZOOM"));assertFalse(r.has("resolutions"));JsonObject()}}
    @Test fun legacyZoomConflictRetriesAtExactRevision(){
        val seen=mutableListOf<JsonObject>();val answer=JsonObject().apply {addProperty("ok",true)}
        assertEquals(answer,LibrarySyncPolicy.submit(sample(),1){r->seen.add(r);if(seen.size==1)throw conflict(LibrarySyncPolicy.ZOOM_PATH,revision=7);answer})
        assertEquals(2,seen.size);assertEquals(7L,seen[1].get("against_revision").asLong);assertEquals("local",seen[1].getAsJsonObject("resolutions").get(LibrarySyncPolicy.ZOOM_PATH).asString)
        assertEquals(1L,seen[1].get("base_revision").asLong);assertNotEquals(seen[0].get("request_id"),seen[1].get("request_id"));assertEquals(seen[0].get("snapshot"),seen[1].get("snapshot"))
    }
    @Test fun mixedBookConflictIsNeverChosenAutomatically(){
        var calls=0;val failure=conflict(LibrarySyncPolicy.ZOOM_PATH,"/library/books/b/currentPage")
        assertSame(failure,runCatching {LibrarySyncPolicy.submit(sample(),1){calls++;throw failure}}.exceptionOrNull());assertEquals(1,calls)
    }
    @Test fun goalsAndManualOrderRemainRealConflicts(){
        listOf("/appearance/SHELF_READING_GOAL:x","/appearance/SHELF_MANUAL_ORDER:book","/appearance/SHELF_SPINE_ZOOM~1extra","/library/books/SHELF_SPINE_ZOOM/title").forEach {path->
            var calls=0;val failure=conflict(path);assertSame(failure,runCatching {LibrarySyncPolicy.submit(sample(),1){calls++;throw failure}}.exceptionOrNull());assertEquals(1,calls)
        }
    }
    @Test fun serverChangesCannotRebaseUserChoices(){
        val choices=JsonObject().apply {addProperty("/library/books/b/currentPage","local")};var calls=0;val failure=changed()
        assertSame(failure,runCatching {LibrarySyncPolicy.submit(sample(),1,resolutions=choices,against=2){calls++;throw failure}}.exceptionOrNull());assertEquals(1,calls)
    }
    @Test fun zoomRevisionRaceRestartsWithoutUnboundChoices(){
        var calls=0
        LibrarySyncPolicy.submit(sample(),1){r->calls++;when(calls){1->throw conflict(LibrarySyncPolicy.ZOOM_PATH);2->{assertEquals(2L,r.get("against_revision").asLong);throw changed()};else->{assertFalse(r.has("resolutions"));assertFalse(r.has("against_revision"));JsonObject()}}}
        assertEquals(3,calls)
    }
    @Test fun zoomRetryNeverIgnoresNewBookConflict(){
        var calls=0;val data=conflict("/library/books/b/currentPage",revision=3)
        assertSame(data,runCatching {LibrarySyncPolicy.submit(sample(),1){calls++;when(calls){1->throw conflict(LibrarySyncPolicy.ZOOM_PATH);2->throw changed();else->throw data}}}.exceptionOrNull());assertEquals(3,calls)
    }
    @Test fun repeatedZoomChangesHaveBoundedRetries(){
        var calls=0;val failure=runCatching {LibrarySyncPolicy.submit(sample(),1){calls++;throw conflict(LibrarySyncPolicy.ZOOM_PATH,revision=calls.toLong()+1)}}.exceptionOrNull() as AccountFailure
        assertEquals(3,calls);assertEquals("changed_again",failure.response.getAsJsonObject("detail").get("code").asString)
    }
    @Test fun invalidRevisionIsNotAutomaticallyAccepted(){
        val failure=conflict(LibrarySyncPolicy.ZOOM_PATH,revision=0);var calls=0
        assertSame(failure,runCatching {LibrarySyncPolicy.submit(sample(),1){calls++;throw failure}}.exceptionOrNull());assertEquals(1,calls)
    }
}
