package org.dashevo.dashpay

import org.bitcoinj.core.Base58
import org.bouncycastle.crypto.params.KeyParameter
import org.dashevo.dapiclient.model.DocumentQuery
import org.dashevo.dpp.document.Document
import org.dashevo.dpp.identity.Identity
import org.dashevo.dpp.identifier.Identifier
import org.dashevo.platform.Documents
import org.dashevo.platform.Platform
import java.util.*
import kotlin.collections.ArrayList

class ContactRequests(val platform: Platform) {

    companion object {
        const val CONTACTREQUEST_DOCUMENT = "dashpay.contactRequest"
    }

    fun create(fromUser: BlockchainIdentity, toUser: Identity, aesKey: KeyParameter) {
        val contactKeyChain = fromUser.getReceiveFromContactChain(toUser, aesKey)
        val contactKey = contactKeyChain.watchingKey
        val contactPub = contactKey.serializeContactPub()

        val (encryptedContactPubKey, encryptedAccountLabel) = fromUser.encryptExtendedPublicKey(
            contactPub,
            toUser,
            0,
            aesKey
        )
        val accountReference = fromUser.getAccountReference(aesKey, toUser)
        val timeStamp = Date().time

        val contactRequestDocument = platform.documents.create(
            CONTACTREQUEST_DOCUMENT, fromUser.uniqueIdentifier, mutableMapOf<String, Any?>(
                "encryptedPublicKey" to encryptedContactPubKey,
                "recipientKeyIndex" to toUser.publicKeys[0].id,
                "senderKeyIndex" to fromUser.identity!!.publicKeys[0].id,
                "toUserId" to toUser.id,
                "accountReference" to accountReference,
                // TODO: contract requires 64 bytes, but minimum should be 32 to allow for short labels
                // 16 bytes IV + 16 bytes for any label less than 15 characters
                // This is a bug in the dashpay contract
                //"encryptedAccountLabel" to encryptedAccountLabel
                "\$createdAt" to timeStamp
            )
        )

        val transitionMap = hashMapOf(
            "create" to listOf(contactRequestDocument)
        )

        val transition = platform.dpp.document.createStateTransition(transitionMap)
        fromUser.signStateTransition(transition, aesKey)

        platform.client.broadcastStateTransition(transition)
    }

    /**
     * Gets the contactRequest documents for the given userId
     * @param userId String
     * @param toUserId Boolean (true if getting toUserId, false if $userId)
     * @param afterTime Long Time in milliseconds
     * @param retrieveAll Boolean get all results (true) or 100 at a time (false)
     * @param startAt Int where to start getting results
     * @return List<Documents>
     */
    fun get(
        userId: String,
        toUserId: Boolean,
        afterTime: Long = 0,
        retrieveAll: Boolean = true,
        startAt: Int = 0
    ): List<Document> {
        return get(Identifier.from(userId), toUserId, afterTime, retrieveAll, startAt)
    }

    fun get(
        userId: Identifier,
        toUserId: Boolean,
        afterTime: Long = 0,
        retrieveAll: Boolean = true,
        startAt: Int = 0
    ): List<Document> {
        val documentQuery = DocumentQuery.Builder()

        if (toUserId)
            documentQuery.where(listOf("toUserId", "==", userId))
        else
            documentQuery.where(listOf("\$ownerId", "==", userId))

        if (afterTime > 0)
            documentQuery.where(listOf("\$createdAt", ">", afterTime))

        // TODO: Refactor the rest of this code since it is also used in Names.search
        // TODO: This block of code can get all the results of a query, or 100 at a time
        var startAt = startAt
        val documents = ArrayList<Document>()
        var documentList: List<Document>
        var requests = 0

        do {
            try {
                documentList =
                    platform.documents.get(
                        CONTACTREQUEST_DOCUMENT,
                        documentQuery.startAt(startAt).build()
                    )
                requests += 1
                startAt += Documents.DOCUMENT_LIMIT
                if (documentList.isNotEmpty())
                    documents.addAll(documentList)
            } catch (e: Exception) {
                throw e
            }
        } while ((requests == 0 || documentList.size >= Documents.DOCUMENT_LIMIT) && retrieveAll)

        return documents
    }

    suspend fun watchContactRequest(
        fromUserId: Identifier,
        toUserId: Identifier,
        retryCount: Int,
        delayMillis: Long,
        retryDelayType: RetryDelayType
    ): Document? {
        val documentQuery = DocumentQuery.Builder()
        documentQuery.where("\$ownerId", "==", fromUserId)
            .where("toUserId", "==", toUserId)
        val result = platform.documents.get(CONTACTREQUEST_DOCUMENT, documentQuery.build())
        if (result.isNotEmpty()) {
            return result[0]
        } else {
            if (retryCount > 0) {
                val nextDelay = delayMillis * when (retryDelayType) {
                    RetryDelayType.SLOW20 -> 5 / 4
                    RetryDelayType.SLOW50 -> 3 / 2
                    else -> 1
                }
                kotlinx.coroutines.delay(nextDelay)
                return watchContactRequest(fromUserId, toUserId, retryCount - 1, nextDelay, retryDelayType)
            }
        }
        return null
    }
}