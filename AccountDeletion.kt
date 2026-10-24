package com.mcgownjohn.battlebuddy.analysis

import android.util.Log
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.mcgownjohn.battlebuddy.models.PrePlanVisibility
import kotlinx.coroutines.tasks.await

/** What happened when someone tried to delete their account. */
sealed class DeleteAccountResult {
    object Deleted : DeleteAccountResult()
    object WrongPassword : DeleteAccountResult()
    object ActiveSubscription : DeleteAccountResult()
    object NeedsRecentSignIn : DeleteAccountResult()
    data class Failed(val message: String) : DeleteAccountResult()
}

/**
 * Deletes the signed-in user's account and personal data.
 *
 * Deleted: profile, PRIVATE pre-plans (and their photos), incidents, calls,
 * CAD notes, department memberships, and the sign-in account itself.
 *
 * Kept: pre-plans shared with a department or marked public. Those are part of
 * the department's records, and the delete screen tells the user so.
 */
object AccountDeletion {

    private const val TAG = "AccountDeletion"

    suspend fun deleteAccount(password: String): DeleteAccountResult {
        val auth = Firebase.auth
        val user = auth.currentUser ?: return DeleteAccountResult.Failed("You're not signed in")
        val uid = user.uid
        val email = user.email ?: ""
        val db = Firebase.firestore

        // 1) Confirm it's really them. Firebase also requires a recent sign-in
        //    before an account can be deleted.
        try {
            val credential = EmailAuthProvider.getCredential(email, password)
            user.reauthenticate(credential).await()
        } catch (e: Exception) {
            Log.w(TAG, "Re-authentication failed: ${e.message}")
            return DeleteAccountResult.WrongPassword
        }

        // 2) Don't delete someone who's still being charged.
        try {
            val profile = db.collection("users").document(uid).get().await()
            if (profile.getBoolean("subscriptionActive") == true &&
                profile.getBoolean("subscriptionCancelAtPeriodEnd") != true
            ) {
                return DeleteAccountResult.ActiveSubscription
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't check subscription: ${e.message}")
        }

        try {
            // 3) Private pre-plans and their photos
            val myPlans = db.collection("preplans")
                .whereEqualTo("ownerId", uid)
                .get().await()
            for (doc in myPlans.documents) {
                if (doc.getString("visibility") == PrePlanVisibility.PRIVATE.name) {
                    @Suppress("UNCHECKED_CAST")
                    val photos = doc.get("photoUrls") as? List<String> ?: emptyList()
                    photos.forEach { url ->
                        try {
                            FirebaseStorage.getInstance().getReferenceFromUrl(url).delete().await()
                        } catch (e: Exception) {
                            Log.w(TAG, "Photo delete skipped: ${e.message}")
                        }
                    }
                    doc.reference.delete().await()
                }
            }

            // 4) Incidents, calls and CAD notes they created
            deleteWhere("incidents", "userId", uid)
            deleteWhere("calls", "createdBy", uid)
            deleteWhere("cad_notes", "authorUid", uid)

            // 5) Department memberships (their member doc ID is their uid)
            if (email.isNotBlank()) {
                val memberships = db.collectionGroup("members")
                    .whereEqualTo("email", email)
                    .get().await()
                memberships.documents
                    .filter { it.id == uid }
                    .forEach { it.reference.delete().await() }
            }

            // 6) Profile
            db.collection("users").document(uid).delete().await()

            // 7) The sign-in account itself (last, so the steps above are allowed)
            user.delete().await()
            Log.d(TAG, "Account deleted: $uid")
            return DeleteAccountResult.Deleted
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            return DeleteAccountResult.NeedsRecentSignIn
        } catch (e: Exception) {
            Log.e(TAG, "Account deletion failed: ${e.message}", e)
            return DeleteAccountResult.Failed(e.message ?: "Something went wrong")
        }
    }

    private suspend fun deleteWhere(collection: String, field: String, uid: String) {
        val docs = Firebase.firestore.collection(collection)
            .whereEqualTo(field, uid)
            .get().await()
        docs.documents.forEach { it.reference.delete().await() }
    }
}
