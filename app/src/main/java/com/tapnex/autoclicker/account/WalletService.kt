package com.tapnex.autoclicker.account

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

data class WalletTransaction(
    val id: String,
    val description: String,
    val amount: Long,
    val status: String
)

data class WalletSnapshot(
    val balance: Long,
    val totalEarned: Long,
    val totalSpent: Long,
    val transactions: List<WalletTransaction>
)

/**
 * Read-only wallet access. Firestore rules deny all client writes to wallet and transaction documents.
 * The only client-side call is `initializeWallet`, a Cloud Function that creates a zero-balance wallet
 * if none exists. Credit changes happen only inside trusted Cloud Functions.
 */
object WalletService {
    private val db: FirebaseFirestore
        get() = FirebaseFirestore.getInstance()

    suspend fun load(uid: String): WalletSnapshot {
        val walletRef = db.document("users/$uid/wallet/current")
        var walletSnap = walletRef.get().await()
        if (!walletSnap.exists()) {
            FirebaseFunctions.getInstance().getHttpsCallable("initializeWallet").call().await()
            walletSnap = walletRef.get().await()
        }
        val balance = (walletSnap.get("balance") as? Number)?.toLong() ?: 0L
        val totalEarned = (walletSnap.get("totalEarned") as? Number)?.toLong() ?: 0L
        val totalSpent = (walletSnap.get("totalSpent") as? Number)?.toLong() ?: 0L

        val transactions = db.collection("users/$uid/transactions")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(25)
            .get()
            .await()
            .documents
            .map { doc ->
                WalletTransaction(
                    id = doc.id,
                    description = doc.getString("description") ?: "Transaction",
                    amount = (doc.get("amount") as? Number)?.toLong() ?: 0L,
                    status = doc.getString("status") ?: "unknown"
                )
            }
        return WalletSnapshot(balance, totalEarned, totalSpent, transactions)
    }
}
