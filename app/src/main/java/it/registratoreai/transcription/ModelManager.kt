package it.registratoreai.transcription

import android.content.Context
import java.io.File

typealias ModelManager = ModelStore

fun ModelManager(context: Context): ModelManager = ModelStore(File(context.filesDir, "models"))
