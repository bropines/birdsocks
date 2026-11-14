package io.github.bropines.birdsocks.admin.safety

import io.github.bropines.birdsocks.admin.api.ApiRoles

/**
 * The classes that depend on values, not only on the kind. A fixed kind has
 * its [ChangeKind.defaultClass]; a builder asks here when the values decide,
 * and passes the answer as [AdminChange.changeClass]. Add a function here
 * when a rule is worth testing on its own.
 */
object ChangeClassifier {

    /** Handing someone a role that may change the network is HIGH; anything else MEDIUM. */
    fun userRole(before: String?, after: String): ChangeClass =
        if (rank(after) > rank(before) && rank(after) >= rank(ApiRoles.NETWORK_ADMIN)) ChangeClass.HIGH else ChangeClass.MEDIUM

    /** A reusable key without a usage limit admits any number of machines until it expires: HIGH. */
    fun setupKeyCreate(reusable: Boolean, usageLimit: Int): ChangeClass =
        if (reusable && usageLimit == 0) ChangeClass.HIGH else ChangeClass.MEDIUM

    /** Switching a protection off (approval, expiration, a block) is HIGH; switching it on MEDIUM. */
    fun protection(before: Boolean, after: Boolean): ChangeClass =
        if (before && !after) ChangeClass.HIGH else ChangeClass.MEDIUM

    /** A longer expiration (or none, 0) keeps machines signed in longer: HIGH; shorter MEDIUM. */
    fun expiration(beforeSec: Long, afterSec: Long): ChangeClass =
        if (afterSec == 0L || (beforeSec != 0L && afterSec > beforeSec)) ChangeClass.HIGH else ChangeClass.MEDIUM

    /** Taking an exit route away cuts off every peer that sends its traffic through it: HIGH. */
    fun route(beforeExit: Boolean, afterExit: Boolean, enabledBefore: Boolean, enabledAfter: Boolean): ChangeClass =
        if (beforeExit && (!afterExit || (enabledBefore && !enabledAfter))) ChangeClass.HIGH else ChangeClass.MEDIUM

    /** Removing the last nameserver of a primary group leaves its peers without a resolver: HIGH. */
    fun nameservers(primary: Boolean, before: Int, after: Int): ChangeClass =
        if (primary && before > 0 && after == 0) ChangeClass.HIGH else ChangeClass.MEDIUM

    private fun rank(role: String?): Int = when (role) {
        ApiRoles.OWNER -> 5
        ApiRoles.ADMIN -> 4
        ApiRoles.NETWORK_ADMIN -> 3
        ApiRoles.BILLING_ADMIN -> 2
        ApiRoles.AUDITOR -> 1
        else -> 0
    }
}
