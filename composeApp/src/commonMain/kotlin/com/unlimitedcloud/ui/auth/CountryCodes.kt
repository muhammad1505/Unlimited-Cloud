package com.unlimitedcloud.ui.auth

/** A dial code option for the phone field. Flags are plain emoji so no asset is needed. */
data class CountryCode(
    val iso: String,
    val name: String,
    val dialCode: String,
    val flag: String,
)

val DefaultCountry = CountryCode("ID", "Indonesia", "+62", "🇮🇩")

/**
 * Curated list covering the common Telegram markets, Indonesia pinned first.
 * The picker also searches ISO codes, so `US`, `+1` or `America` all work.
 */
val CountryCodes: List<CountryCode> = listOf(
    DefaultCountry,
    CountryCode("AE", "Uni Emirat Arab", "+971", "🇦🇪"),
    CountryCode("AR", "Argentina", "+54", "🇦🇷"),
    CountryCode("AT", "Austria", "+43", "🇦🇹"),
    CountryCode("AU", "Australia", "+61", "🇦🇺"),
    CountryCode("BD", "Bangladesh", "+880", "🇧🇩"),
    CountryCode("BE", "Belgia", "+32", "🇧🇪"),
    CountryCode("BH", "Bahrain", "+973", "🇧🇭"),
    CountryCode("BN", "Brunei", "+673", "🇧🇳"),
    CountryCode("BR", "Brasil", "+55", "🇧🇷"),
    CountryCode("CA", "Kanada", "+1", "🇨🇦"),
    CountryCode("CH", "Swiss", "+41", "🇨🇭"),
    CountryCode("CL", "Chili", "+56", "🇨🇱"),
    CountryCode("CN", "Tiongkok", "+86", "🇨🇳"),
    CountryCode("CO", "Kolombia", "+57", "🇨🇴"),
    CountryCode("CZ", "Ceko", "+420", "🇨🇿"),
    CountryCode("DE", "Jerman", "+49", "🇩🇪"),
    CountryCode("DK", "Denmark", "+45", "🇩🇰"),
    CountryCode("DZ", "Aljazair", "+213", "🇩🇿"),
    CountryCode("EG", "Mesir", "+20", "🇪🇬"),
    CountryCode("ES", "Spanyol", "+34", "🇪🇸"),
    CountryCode("FI", "Finlandia", "+358", "🇫🇮"),
    CountryCode("FR", "Prancis", "+33", "🇫🇷"),
    CountryCode("GB", "Inggris", "+44", "🇬🇧"),
    CountryCode("GH", "Ghana", "+233", "🇬🇭"),
    CountryCode("GR", "Yunani", "+30", "🇬🇷"),
    CountryCode("HK", "Hong Kong", "+852", "🇭🇰"),
    CountryCode("IE", "Irlandia", "+353", "🇮🇪"),
    CountryCode("IL", "Israel", "+972", "🇮🇱"),
    CountryCode("IN", "India", "+91", "🇮🇳"),
    CountryCode("IT", "Italia", "+39", "🇮🇹"),
    CountryCode("JO", "Yordania", "+962", "🇯🇴"),
    CountryCode("JP", "Jepang", "+81", "🇯🇵"),
    CountryCode("KE", "Kenya", "+254", "🇰🇪"),
    CountryCode("KH", "Kamboja", "+855", "🇰🇭"),
    CountryCode("KR", "Korea Selatan", "+82", "🇰🇷"),
    CountryCode("KW", "Kuwait", "+965", "🇰🇼"),
    CountryCode("KZ", "Kazakhstan", "+7", "🇰🇿"),
    CountryCode("LA", "Laos", "+856", "🇱🇦"),
    CountryCode("LK", "Sri Lanka", "+94", "🇱🇰"),
    CountryCode("MA", "Maroko", "+212", "🇲🇦"),
    CountryCode("MM", "Myanmar", "+95", "🇲🇲"),
    CountryCode("MX", "Meksiko", "+52", "🇲🇽"),
    CountryCode("MY", "Malaysia", "+60", "🇲🇾"),
    CountryCode("NG", "Nigeria", "+234", "🇳🇬"),
    CountryCode("NL", "Belanda", "+31", "🇳🇱"),
    CountryCode("NO", "Norwegia", "+47", "🇳🇴"),
    CountryCode("NP", "Nepal", "+977", "🇳🇵"),
    CountryCode("NZ", "Selandia Baru", "+64", "🇳🇿"),
    CountryCode("OM", "Oman", "+968", "🇴🇲"),
    CountryCode("PE", "Peru", "+51", "🇵🇪"),
    CountryCode("PH", "Filipina", "+63", "🇵🇭"),
    CountryCode("PK", "Pakistan", "+92", "🇵🇰"),
    CountryCode("PL", "Polandia", "+48", "🇵🇱"),
    CountryCode("PT", "Portugal", "+351", "🇵🇹"),
    CountryCode("QA", "Qatar", "+974", "🇶🇦"),
    CountryCode("RO", "Rumania", "+40", "🇷🇴"),
    CountryCode("RU", "Rusia", "+7", "🇷🇺"),
    CountryCode("SA", "Arab Saudi", "+966", "🇸🇦"),
    CountryCode("SE", "Swedia", "+46", "🇸🇪"),
    CountryCode("SG", "Singapura", "+65", "🇸🇬"),
    CountryCode("TH", "Thailand", "+66", "🇹🇭"),
    CountryCode("TN", "Tunisia", "+216", "🇹🇳"),
    CountryCode("TR", "Turki", "+90", "🇹🇷"),
    CountryCode("TW", "Taiwan", "+886", "🇹🇼"),
    CountryCode("UA", "Ukraina", "+380", "🇺🇦"),
    CountryCode("US", "Amerika Serikat", "+1", "🇺🇸"),
    CountryCode("UZ", "Uzbekistan", "+998", "🇺🇿"),
    CountryCode("VN", "Vietnam", "+84", "🇻🇳"),
    CountryCode("ZA", "Afrika Selatan", "+27", "🇿🇦"),
)

/** Strips a duplicated dial code or a national trunk `0` before combining. */
fun normalizePhone(country: CountryCode, input: String): String {
    val dial = country.dialCode.removePrefix("+")
    val digits = input.filter { it.isDigit() }
    val national = when {
        digits.startsWith(dial) -> digits.removePrefix(dial)
        else -> digits.trimStart('0')
    }
    return "+$dial$national"
}

fun isValidPhone(country: CountryCode, input: String): Boolean {
    val dial = country.dialCode.removePrefix("+")
    val digits = input.filter { it.isDigit() }
    val national = if (digits.startsWith(dial)) digits.removePrefix(dial) else digits.trimStart('0')
    return national.length in 6..14
}
