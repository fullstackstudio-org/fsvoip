// SPDX-License-Identifier: AGPL-3.0-or-later
//
// This file deliberately does NOT import linphonesw: it gives our own types an unambiguous name inside the
// LinphoneEngine module, where linphonesw declares a `RegistrationState` of its own.

import SipEngine

public typealias OurRegistrationState = RegistrationState
