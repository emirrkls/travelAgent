package com.emirrkls.phokarta.core.share

import android.content.Context
import android.content.Intent
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.Visit
import java.util.Locale

object PhokartaShare {
    fun placeText(place: Place): String =
        placeText(
            name = place.name,
            location = place.city,
            score = place.communityScore ?: place.friendsScore ?: place.similarUsersScore,
        )

    fun placeText(name: String, location: String, score: Double?): String {
        val base = "$name · $location"
        return if (score != null) {
            "$base — ${String.format(Locale.US, "%.1f", score)} on Phokarta"
        } else {
            "$base on Phokarta"
        }
    }

    fun collectionText(collection: Collection): String {
        val count = collection.placeIds.size
        val placesLabel = if (count == 1) "1 place" else "$count places"
        return "${collection.title} · $placesLabel on Phokarta"
    }

    fun visitText(placeName: String, visit: Visit): String =
        "$placeName — ${String.format(Locale.US, "%.1f", visit.overallRating)} on Phokarta"

    fun shareText(context: Context, text: String, chooserTitle: String = "Share") {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }
}
