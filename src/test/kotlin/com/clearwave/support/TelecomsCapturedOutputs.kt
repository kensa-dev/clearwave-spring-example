package com.clearwave.support

import com.clearwave.feasibility.FeasibilityResponse
import com.clearwave.order.OrderResponse
import dev.kensa.outputs.CapturedOutputContainer
import dev.kensa.outputs.capturedOutput

object TelecomsCapturedOutputs : CapturedOutputContainer {

    /** Aggregated feasibility response containing available line profiles. */
    val FeasibilityResult = capturedOutput<FeasibilityResponse>("Feasibility Result")

    /** Order confirmation from the Clearwave order service. */
    val OrderConfirmation = capturedOutput<OrderResponse>("Order Confirmation", highlighted = true)
}
