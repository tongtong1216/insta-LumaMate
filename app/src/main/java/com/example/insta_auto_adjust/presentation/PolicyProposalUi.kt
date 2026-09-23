package com.example.insta_auto_adjust.presentation

data class PolicyProposalUi(
    val proposalId: String,
    val action: String,
    val reason: String,
    val cost: String,
    val validUntil: Long
)