package com.clearwave.support

import dev.kensa.state.Party

enum class TelecomsParty : Party {
    Customer,
    FeasibilityService,
    OrderService,
    OpenNetwork,
    FibreVision;

    override fun asString(): String = name
}
