package com.clearwave.feasibility

import com.clearwave.SupplierProperties
import com.clearwave.domain.LineProfile
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate

/**
 * Clearwave feasibility service. Fans out feasibility enquiries to two suppliers:
 *
 * - **OpenNetwork** — voice and broadband (JSON API)
 * - **FibreVision** — broadband only (XML API)
 *
 * The `X-Tracking-Id` header is forwarded on all outbound supplier calls so that
 * test stubs can correlate interactions with the correct test invocation.
 */
@Service
class FeasibilityService(
    private val restTemplate: RestTemplate,
    private val suppliers: SupplierProperties,
) {

    fun check(trackingId: String, request: FeasibilityRequest): FeasibilityResponse {
        val openNetworkProfiles = queryOpenNetwork(trackingId, request)
        val fibreVisionProfiles = queryFibreVision(trackingId, request)
        val allProfiles = (openNetworkProfiles + fibreVisionProfiles)
            .sortedByDescending { it.downloadSpeed }

        return FeasibilityResponse(
            address = request.address,
            serviceable = allProfiles.isNotEmpty(),
            profiles = allProfiles,
        )
    }

    private fun queryOpenNetwork(trackingId: String, req: FeasibilityRequest): List<LineProfile> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Tracking-Id", trackingId)
        }
        val entity = HttpEntity(
            OpenNetworkFeasibilityRequest(req.address.postcode, req.services),
            headers,
        )
        return try {
            val response = restTemplate.exchange(
                "${suppliers.openNetworkUrl}/api/feasibility/check",
                HttpMethod.POST,
                entity,
                OpenNetworkFeasibilityResponse::class.java,
            )
            response.body?.profiles?.map { it.toLineProfile() } ?: emptyList()
        } catch (_: RestClientException) {
            emptyList()
        }
    }

    private fun queryFibreVision(trackingId: String, req: FeasibilityRequest): List<LineProfile> {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<FeasibilityEnquiry>
    <Postcode>${req.address.postcode}</Postcode>
    <ServiceType>BROADBAND</ServiceType>
</FeasibilityEnquiry>"""
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_XML
            set("X-Tracking-Id", trackingId)
        }
        return try {
            val response = restTemplate.exchange(
                "${suppliers.fibreVisionUrl}/api/feasibility/enquiry",
                HttpMethod.POST,
                HttpEntity(xml, headers),
                String::class.java,
            )
            parseFibreVisionResponse(response.body ?: "")
        } catch (_: RestClientException) {
            emptyList()
        }
    }

    private fun parseFibreVisionResponse(xml: String): List<LineProfile> {
        val status = xml.extractXml("Status") ?: return emptyList()
        if (status != "SERVICEABLE") return emptyList()
        return Regex("<Profile>(.*?)</Profile>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { match ->
                val p = match.groupValues[1]
                LineProfile(
                    type          = p.extractXml("Type") ?: "UNKNOWN",
                    downloadSpeed = p.extractXml("DownloadSpeed")?.toIntOrNull() ?: 0,
                    uploadSpeed   = p.extractXml("UploadSpeed")?.toIntOrNull() ?: 0,
                    description   = p.extractXml("Description") ?: "",
                    supplier      = "FibreVision",
                )
            }.toList()
    }

    private fun String.extractXml(tag: String): String? =
        Regex("<$tag>(.*?)</$tag>").find(this)?.groupValues?.getOrNull(1)
}
