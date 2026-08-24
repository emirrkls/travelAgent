package com.emirrkls.phokarta.core.share

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.Visit
import java.text.NumberFormat
import java.util.Locale

object PhokartaShare {
    fun placeText(resources: Resources, place: Place): String =
        placeText(
            resources = resources,
            name = place.name,
            location = place.city,
            score = place.communityScore ?: place.friendsScore ?: place.similarUsersScore,
        )

    fun placeText(resources: Resources, name: String, location: String, score: Double?): String {
        return if (score != null) {
            resources.getString(
                R.string.share_place_with_score,
                name,
                location,
                formatShareScore(resources, score),
            )
        } else {
            resources.getString(R.string.share_place_no_score, name, location)
        }
    }

    fun collectionText(resources: Resources, collection: Collection): String {
        val count = collection.placeIds.size
        return if (count == 1) {
            resources.getString(R.string.share_collection_one, collection.title)
        } else {
            resources.getString(R.string.share_collection_many, collection.title, count)
        }
    }

    fun visitText(resources: Resources, placeName: String, visit: Visit): String =
        resources.getString(
            R.string.share_visit,
            placeName,
            formatShareScore(resources, visit.overallRating),
        )

    fun shareText(context: Context, text: String, chooserTitle: String? = null) {
        val title = chooserTitle ?: context.getString(R.string.share_chooser_title)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, title))
    }

    private fun formatShareScore(resources: Resources, score: Double): String {
        @Suppress("DEPRECATION")
        val locale = resources.configuration.locales[0] ?: Locale.US
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(score)
    }
}
