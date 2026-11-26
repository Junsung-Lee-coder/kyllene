package dev.hermeskotlin.core.custom

import android.content.Context
import androidx.room.Room

/**
 * Fork: the live transcripts' database in the app's private storage, apart from the offline cache. No destructive
 * fallback: a version it can't open fails rather than starting the transcripts over.
 */
fun liveTranscriptDatabase(context: Context): LiveTranscriptDatabase =
    Room.databaseBuilder<LiveTranscriptDatabase>(context.applicationContext, context.getDatabasePath("live-transcripts.db").absolutePath)
        .build()
