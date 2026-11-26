package dev.hermeskotlin.core.custom

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import java.io.File

/** Fork: the live transcripts' database in [dir]. No destructive fallback. */
fun liveTranscriptDatabase(dir: File): LiveTranscriptDatabase =
    Room.databaseBuilder<LiveTranscriptDatabase>(name = File(dir, "live-transcripts.db").absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
