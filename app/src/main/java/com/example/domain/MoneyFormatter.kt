package com.example.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object MoneyFormatter {

    fun formatInr(paise: Long, includeSymbol: Boolean = true): String {
        val negative = paise < 0
        val absPaise = abs(paise)
        val rupees = absPaise / 100L
        val remPaise = (absPaise % 100L).toInt()
        val grouped = formatIndianGrouping(rupees)
        val fraction = if (remPaise > 0) ".%02d".format(Locale.US, remPaise) else ""
        val prefix = buildString {
            if (negative) append("-")
            if (includeSymbol) append("₹")
        }
        return "$prefix$grouped$fraction"
    }

    fun formatIndianGrouping(wholeRupees: Long): String {
        val digits = wholeRupees.toString()
        if (digits.length <= 3) return digits
        val lastThree = digits.takeLast(3)
        val head = digits.dropLast(3)
        val groups = mutableListOf<String>()
        var idx = head.length
        while (idx > 0) {
            val start = (idx - 2).coerceAtLeast(0)
            groups.add(0, head.substring(start, idx))
            idx = start
        }
        return groups.joinToString(",") + "," + lastThree
    }

    fun rupeesToPaise(input: String): Long? {
        val cleaned = input.replace("₹", "")
            .replace(",", "")
            .trim()
        if (cleaned.isEmpty()) return null
        val parsed = cleaned.toDoubleOrNull() ?: return null
        if (parsed < 0.0) return null
        return (parsed * 100.0).roundToLong()
    }

    fun paiseToEditableRupees(paise: Long): String {
        if (paise <= 0L) return ""
        val rupees = paise / 100L
        val rem = paise % 100L
        return if (rem == 0L) rupees.toString() else "$rupees.%02d".format(Locale.US, rem)
    }

    fun formatDate(millis: Long): String {
        if (millis <= 0L) return "Not set"
        return SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(millis))
    }

    fun formatDateTime(millis: Long): String {
        if (millis <= 0L) return "Just now"
        return SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date(millis))
    }
}
