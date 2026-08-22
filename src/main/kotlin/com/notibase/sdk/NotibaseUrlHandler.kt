/**
 * Intercept notification destinations.
 *
 * Set [Notibase.urlHandler] when a tapped notification should route inside
 * your app rather than hand the URL to the system. Returning false for the
 * ones you do not care about keeps the default behaviour for those, so an
 * app can claim its own scheme and still let plain https links open a
 * browser.
 *
 * A `fun interface` rather than a Kotlin lambda type so Java hosts can
 * implement it without a FunctionN import.
 */
package com.notibase.sdk

import android.content.Context

public fun interface NotibaseUrlHandler {
    /** Return true when you have handled [url] and Notibase should not. */
    public fun handle(context: Context, url: String): Boolean
}
