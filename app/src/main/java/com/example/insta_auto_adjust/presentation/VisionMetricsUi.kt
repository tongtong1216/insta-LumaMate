package com.example.insta_auto_adjust.presentation

data class VisionMetricsUi(
    val frameId: String,
    val subjectBrightness: Double,
    val highlightRatio: Double,
    val darkRatio: Double,
    val roiVersion: String,
    val timestamp: Long
)