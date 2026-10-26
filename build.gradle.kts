plugins {
    alias(libs.plugins.android.application) apply false
    // Never applied: AGP 9 compiles Kotlin itself. Declaring it here only pins the Kotlin version AGP uses.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
