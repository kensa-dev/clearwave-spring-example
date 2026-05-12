package com.clearwave.feasibility

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class FeasibilityController(private val service: FeasibilityService) {

    @PostMapping("/api/feasibility")
    fun check(
        @RequestHeader("X-Tracking-Id") trackingId: String,
        @RequestBody request: FeasibilityRequest,
    ): FeasibilityResponse = service.check(trackingId, request)
}
