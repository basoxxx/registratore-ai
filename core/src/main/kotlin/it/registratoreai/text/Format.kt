package it.registratoreai.text

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatTimestamp(ms: Long): String {
    val total = ms / 1000
    return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDate(ms: Long): String =
    SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALY).format(Date(ms))
        .replaceFirstChar { it.uppercase() }

fun formatShortDate(ms: Long): String =
    SimpleDateFormat("d MMM yyyy · HH:mm", Locale.ITALY).format(Date(ms))
