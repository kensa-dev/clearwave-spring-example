package com.clearwave.support

import com.clearwave.domain.AppointmentSlot
import com.clearwave.domain.LineProfile
import com.clearwave.domain.ServiceAddress
import dev.kensa.fixture.FixtureContainer
import dev.kensa.fixture.Parents
import dev.kensa.fixture.SecondaryFixture
import dev.kensa.fixture.fixture
import java.time.LocalDate

object TelecomsFixtures : FixtureContainer {

    /** Unique correlation token for each test invocation. */
    val trackingId = fixture("Tracking Id", highlighted = true) { TrackingId() }

    // --- Customer ---

    val customerId = fixture("Customer Id") { "CUST-10042" }

    // --- Address ---

    val postcode     = fixture("Postcode")       { "SW1A 2AA" }
    val addressLine1 = fixture("Address Line 1") { "1 Parliament Square" }
    val town         = fixture("Town")           { "Westminster" }
    val county       = fixture("County")         { "London" }

    val serviceAddress: SecondaryFixture<ServiceAddress> = SecondaryFixture(
        "Service Address",
        { fixtures ->
            ServiceAddress(
                postcode     = fixtures[postcode],
                addressLine1 = fixtures[addressLine1],
                town         = fixtures[town],
                county       = fixtures[county],
            )
        },
        Parents.Three(postcode, addressLine1, town)
    )

    // --- Voice profile (OpenNetwork FTTP) ---

    val voiceSupplier      = fixture("Voice Supplier")       { "OpenNetwork" }
    val voiceDownloadSpeed = fixture("Voice Download Speed") { 900 }
    val voiceUploadSpeed   = fixture("Voice Upload Speed")   { 110 }

    val voiceProfile = fixture("Voice Profile", voiceDownloadSpeed, voiceUploadSpeed, voiceSupplier) { dl, ul, sup ->
        LineProfile(type = "FTTP", downloadSpeed = dl, uploadSpeed = ul, description = "Full Fibre 900 with Voice", supplier = sup)
    }

    // --- Broadband profile (FibreVision FTTC) ---

    val broadbandSupplier      = fixture("Broadband Supplier")       { "FibreVision" }
    val broadbandDownloadSpeed = fixture("Broadband Download Speed") { 80 }
    val broadbandUploadSpeed   = fixture("Broadband Upload Speed")   { 20 }

    val broadbandProfile = fixture("Broadband Profile", broadbandDownloadSpeed, broadbandUploadSpeed, broadbandSupplier) { dl, ul, sup ->
        LineProfile(type = "FTTC", downloadSpeed = dl, uploadSpeed = ul, description = "Superfast 80", supplier = sup)
    }

    // --- Appointment ---

    val appointmentDate     = fixture("Appointment Date")      { LocalDate.now().plusDays(7) }
    val appointmentTimeSlot = fixture("Appointment Time Slot") { "AM" }

    val appointmentSlot = fixture("Appointment Slot", appointmentDate, appointmentTimeSlot) { date, slot ->
        AppointmentSlot(date = date, timeSlot = slot)
    }
}
