package com.clearwave.support

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT
import dev.kensa.render.InteractionRenderer
import dev.kensa.render.Language
import dev.kensa.render.RenderedAttributes
import dev.kensa.render.RenderedInteraction
import dev.kensa.spring.web.HttpCapturedRequest
import dev.kensa.spring.web.HttpCapturedResponse
import dev.kensa.util.Attributes
import dev.kensa.util.NamedValue

private val prettyJsonMapper = ObjectMapper().enable(INDENT_OUTPUT)

private fun String.prettyPrintJson(): String =
    prettyJsonMapper.writeValueAsString(prettyJsonMapper.readTree(this))

private fun bodyLanguage(body: String): Language =
    if (body.trimStart().startsWith("<")) Language.Xml else Language.Json

private fun Map<String, List<String>>.flattenForRender(): Set<NamedValue> =
    flatMap { (name, values) -> values.map { NamedValue(name, it) } }.toSet()

object RequestRenderer : InteractionRenderer<HttpCapturedRequest> {
    override fun render(value: HttpCapturedRequest, attributes: Attributes): List<RenderedInteraction> {
        val body = value.body
        if (body.isNullOrBlank()) return emptyList()
        val language = bodyLanguage(body)
        val prettyBody = if (language == Language.Json) body.prettyPrintJson() else body
        return listOf(RenderedInteraction("Request Body", prettyBody, language))
    }

    override fun renderAttributes(value: HttpCapturedRequest): List<RenderedAttributes> = listOf(
        RenderedAttributes("Request", setOf(NamedValue("Method", value.method), NamedValue("URI", value.uri))),
        RenderedAttributes("Headers", value.headers.flattenForRender()),
    )
}

object ResponseRenderer : InteractionRenderer<HttpCapturedResponse> {
    override fun render(value: HttpCapturedResponse, attributes: Attributes): List<RenderedInteraction> {
        val body = value.body
        if (body.isNullOrBlank()) return emptyList()
        val language = bodyLanguage(body)
        val prettyBody = if (language == Language.Json) body.prettyPrintJson() else body
        return listOf(RenderedInteraction("Response Body", prettyBody, language))
    }

    override fun renderAttributes(value: HttpCapturedResponse): List<RenderedAttributes> = listOf(
        RenderedAttributes("Status", setOf(NamedValue("Status", value.status.toString()))),
        RenderedAttributes("Headers", value.headers.flattenForRender()),
    )
}
