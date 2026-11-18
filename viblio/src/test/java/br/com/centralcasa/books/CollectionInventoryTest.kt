package br.com.centralcasa.books
import org.junit.Assert.*
import org.junit.Test
class CollectionInventoryTest {
    private val title=CollectionTitle(id="series",title="Mangá",expectedVolumes=3)
    private fun volume(number:Int?,id:String="v$number")=LibraryBook(id=id,title="Volume",kind="manga",collectionId="series",sequenceNumber=number,totalPages=1,pagesKnown=false)
    @Test fun addingNewNumbersReducesMissingWithoutChangingTotal(){
        assertEquals(2,CollectionInventory.of(title,listOf(volume(1))).missing)
        assertEquals(1,CollectionInventory.of(title,listOf(volume(1),volume(2))).missing)
        assertEquals(CollectionCompletion.COMPLETE,CollectionInventory.of(title,listOf(volume(1),volume(2),volume(3))).status)
    }
    @Test fun duplicatesGapsUnknownAndOutsideNumbersDoNotCompleteACollection(){
        val books=listOf(volume(1),volume(1,"copy"),volume(3),volume(4),volume(null))
        assertEquals(1,CollectionInventory.of(title,books).missing)
        assertEquals(CollectionCompletion.INCOMPLETE,CollectionInventory.of(title,books).status)
    }
    @Test fun unknownTotalIsSeparateFromIncomplete(){
        val result=CollectionInventory.of(title.copy(expectedVolumes=null),listOf(volume(1)))
        assertNull(result.missing);assertEquals(CollectionCompletion.UNKNOWN,result.status)
    }
    @Test fun readStatusDoesNotChangeOwnership(){
        val books=(1..3).map {volume(it)}
        assertFalse(title.shelfBook(books).complete)
        assertEquals(CollectionCompletion.COMPLETE,CollectionInventory.of(title,books).status)
        assertEquals(CollectionCompletion.INCOMPLETE,CollectionInventory.of(title,listOf(volume(1).copy(readFinished=true))).status)
    }
    @Test fun archivedCopiesDoNotFillMissingNumbers(){
        assertEquals(1,CollectionInventory.of(title,(1..3).map {volume(it).copy(archived=it==2)}).missing)
    }
    @Test fun legacyMissingCountProvidesAStableInitialTotal(){
        val legacy=title.copy(expectedVolumes=null,missingVolumes=2)
        assertEquals(3,CollectionInventory.expected(legacy,listOf(volume(1))))
        val migrated=legacy.copy(expectedVolumes=3)
        assertEquals(1,CollectionInventory.of(migrated,listOf(volume(1),volume(2))).missing)
    }
    @Test fun legacyTotalRetainsRemovedVolumeSlots(){
        val legacy=title.copy(expectedVolumes=null,missingVolumes=2)
        assertEquals(3,CollectionInventory.expected(legacy,listOf(volume(1).copy(archived=true))))
        assertEquals(3,CollectionInventory.of(legacy,listOf(volume(1).copy(archived=true))).missing)
    }
    @Test fun emptyZeroTotalIsNotACompleteCollection(){
        assertEquals(CollectionCompletion.UNKNOWN,CollectionInventory.of(title.copy(expectedVolumes=null,missingVolumes=0),emptyList()).status)
    }
}
