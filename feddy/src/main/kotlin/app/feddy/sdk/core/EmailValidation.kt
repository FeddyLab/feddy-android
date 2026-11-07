package app.feddy.sdk.core

/** The same loose check the iOS SDK and the API make: something@something.something. */
internal object EmailValidation {
    private val pattern = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

    fun isValid(email: String): Boolean = pattern.matches(email)
}
