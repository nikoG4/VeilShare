package dev.veilshare.core.vault

import dev.veilshare.core.model.BlobId

/** Test-only failure injection around the production store. */
class FaultingBlobStore(private val delegate:BlobStore, private val failAfterBytes:Long?=null, private val failOnCommit:Boolean=false, private val failOnDelete:Boolean=false):BlobStore {
 override suspend fun create():BlobWriteHandle { val w=delegate.create();var n=0L;return object:BlobWriteHandle{override suspend fun write(bytes:ByteArray,offset:Int,length:Int){n+=length;if(failAfterBytes!=null&&n>failAfterBytes)throw IllegalStateException("injected write failure");w.write(bytes,offset,length)};override suspend fun commit():BlobId{if(failOnCommit)throw IllegalStateException("injected commit failure");return w.commit()};override suspend fun abort()=w.abort()} }
 override suspend fun open(id:BlobId)=delegate.open(id);override suspend fun exists(id:BlobId)=delegate.exists(id);override suspend fun delete(id:BlobId){if(failOnDelete)throw IllegalStateException("injected delete failure");delegate.delete(id)};override suspend fun size(id:BlobId)=delegate.size(id);override suspend fun listIds()=delegate.listIds()
}
