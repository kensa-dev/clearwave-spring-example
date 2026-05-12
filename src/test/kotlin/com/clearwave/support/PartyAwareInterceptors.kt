package com.clearwave.support

import dev.kensa.context.TestContextHolder
import dev.kensa.spring.web.HttpCapturedRequest
import dev.kensa.spring.web.HttpCapturedResponse
import dev.kensa.state.CapturedInteractionBuilder
import dev.kensa.state.CapturedInteractions
import dev.kensa.state.Party
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Party-aware HandlerInterceptor for inbound requests on the Clearwave SUT.
 *
 * - `/api/feasibility`, `/api/orders` come from [TelecomsParty.Customer]
 * - `/api/notifications/{id}` with JSON body comes from [TelecomsParty.OpenNetwork]
 * - `/api/notifications/{id}` with XML body comes from [TelecomsParty.FibreVision]
 */
class TelecomsHandlerInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        captureInto { interactions ->
            val from = inboundFrom(request)
            val to = inboundTo(request)
            CapturedInteractionBuilder.from(from)
                .to(to)
                .with(request.toCaptured(), "HTTP ${request.method} ${request.requestURI}")
                .applyTo(interactions)
        }
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        captureInto { interactions ->
            val from = inboundTo(request)
            val to = inboundFrom(request)
            CapturedInteractionBuilder.from(from)
                .to(to)
                .with(response.toCaptured(), "HTTP ${response.status}")
                .applyTo(interactions)
        }
    }

    private fun inboundFrom(req: HttpServletRequest): Party {
        val path = req.requestURI ?: return TelecomsParty.Customer
        return when {
            path.startsWith("/api/notifications/") -> {
                val contentType = req.contentType ?: ""
                if (contentType.contains("xml")) TelecomsParty.FibreVision else TelecomsParty.OpenNetwork
            }
            else -> TelecomsParty.Customer
        }
    }

    private fun inboundTo(req: HttpServletRequest): Party {
        val path = req.requestURI ?: return TelecomsParty.FeasibilityService
        return if (path.startsWith("/api/feasibility")) TelecomsParty.FeasibilityService else TelecomsParty.OrderService
    }

    private fun HttpServletRequest.toCaptured(): HttpCapturedRequest {
        val headers = headerNames.toList().associateWith { getHeaders(it).toList() }
        return HttpCapturedRequest(method, requestURI, headers, body = null)
    }

    private fun HttpServletResponse.toCaptured(): HttpCapturedResponse {
        val headers = headerNames.associateWith { getHeaders(it).toList() }
        return HttpCapturedResponse(status, headers, body = null)
    }

    private inline fun captureInto(block: (CapturedInteractions) -> Unit) {
        val ctx = runCatching { TestContextHolder.testContext() }.getOrNull() ?: return
        block(ctx.interactions)
    }
}

/**
 * Party-aware ClientHttpRequestInterceptor covering both directions:
 *
 *  - Test → SUT (paths under `/api/feasibility` or `/api/orders`):
 *    from [TelecomsParty.Customer] to the corresponding Clearwave service.
 *  - SUT → supplier (any other path; suppliers are reached via their own host/port):
 *    from the originating Clearwave service to the supplier, discriminated by
 *    request content type (JSON → [TelecomsParty.OpenNetwork], XML →
 *    [TelecomsParty.FibreVision]).
 */
class TelecomsClientHttpRequestInterceptor : ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val (from, to) = parties(request)

        captureInto { interactions ->
            CapturedInteractionBuilder.from(from)
                .to(to)
                .with(
                    HttpCapturedRequest(
                        method = request.method.name(),
                        uri = request.uri.toString(),
                        headers = request.headers.mapValues { it.value.toList() },
                        body = body.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8),
                    ),
                    "HTTP ${request.method.name()} ${request.uri}",
                )
                .applyTo(interactions)
        }

        val response = execution.execute(request, body)

        captureInto { interactions ->
            CapturedInteractionBuilder.from(to)
                .to(from)
                .with(
                    HttpCapturedResponse(
                        status = response.statusCode.value(),
                        headers = response.headers.mapValues { it.value.toList() },
                        body = null,
                    ),
                    "HTTP ${response.statusCode.value()}",
                )
                .applyTo(interactions)
        }

        return response
    }

    private fun parties(request: HttpRequest): Pair<Party, Party> {
        val path = request.uri.path ?: ""
        if (path.startsWith("/api/feasibility")) return TelecomsParty.Customer to TelecomsParty.FeasibilityService
        if (path.startsWith("/api/orders")) return TelecomsParty.Customer to TelecomsParty.OrderService
        val service = if (path.contains("feasibility")) TelecomsParty.FeasibilityService else TelecomsParty.OrderService
        val contentType = request.headers.contentType?.toString() ?: ""
        val supplier = if (contentType.contains("xml")) TelecomsParty.FibreVision else TelecomsParty.OpenNetwork
        return service to supplier
    }

    private inline fun captureInto(block: (CapturedInteractions) -> Unit) {
        val ctx = runCatching { TestContextHolder.testContext() }.getOrNull() ?: return
        block(ctx.interactions)
    }
}
