package com.mallocgfw.app.model

import java.util.Locale

/**
 * Picks a country flag for a node from its name, for the node avatar:
 * a flag emoji anywhere in the name, else a country / region / city name in
 * Chinese or English, else a country code ("HK01", "US-SJC", "jp-2").
 * Returns null when nothing is recognised, and the avatar keeps the initial.
 */
object NodeFlags {
    fun flagFor(name: String): String? {
        flagEmojiIn(name)?.let { return it }
        val matches = mutableListOf<Pair<Int, String>>()
        for ((keyword, code) in cjkKeywords) {
            val index = name.indexOf(keyword)
            if (index >= 0) matches += index to code
        }
        for ((pattern, code) in latinPatterns) {
            pattern.find(name)?.let { matches += it.range.first to code }
        }
        if (matches.isEmpty()) return null
        // "上海→香港 IPLC": the mainland entry city is not the exit, so prefer
        // any other country; leftmost wins among the rest.
        val ordered = matches.sortedBy { it.first }
        val code = (ordered.firstOrNull { it.second != "CN" } ?: ordered.first()).second
        return emojiFor(code)
    }

    /** The first pair of regional-indicator symbols in [text], i.e. a flag emoji. */
    private fun flagEmojiIn(text: String): String? {
        var index = 0
        while (index < text.length) {
            val first = text.codePointAt(index)
            val next = index + Character.charCount(first)
            if (first in REGIONAL_INDICATORS && next < text.length) {
                val second = text.codePointAt(next)
                if (second in REGIONAL_INDICATORS) {
                    return text.substring(index, next + Character.charCount(second))
                }
            }
            index = next
        }
        return null
    }

    private fun emojiFor(code: String): String {
        val iso = if (code == "UK") "GB" else code
        return iso.uppercase(Locale.ROOT).map { char ->
            String(Character.toChars(REGIONAL_INDICATORS.first + (char - 'A')))
        }.joinToString("")
    }

    private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

    /** Chinese names (simplified and traditional), longest first so 印度尼西亚 beats 印度. */
    private val cjkKeywords: List<Pair<String, String>> = listOf(
        "HK" to "香港 港島 九龙 九龍 新界",
        "MO" to "澳门 澳門",
        "TW" to "台湾 台灣 臺灣 台北 臺北 新北 台中 高雄",
        "JP" to "日本 东京 東京 大阪 埼玉 名古屋 福冈 福岡 札幌",
        "KR" to "韩国 韓國 首尔 首爾 春川 釜山",
        "SG" to "新加坡 狮城 獅城",
        "US" to "美国 美國 美西 美东 美東 美中 洛杉矶 洛杉磯 圣何塞 聖何塞 硅谷 矽谷 西雅图 西雅圖 纽约 紐約 芝加哥 达拉斯 達拉斯 凤凰城 鳳凰城 迈阿密 邁阿密 波特兰 弗吉尼亚 亚特兰大",
        "GB" to "英国 英國 伦敦 倫敦 曼彻斯特",
        "DE" to "德国 德國 法兰克福 法蘭克福 柏林 杜塞尔多夫",
        "FR" to "法国 法國 巴黎 马赛",
        "NL" to "荷兰 荷蘭 阿姆斯特丹",
        "RU" to "俄罗斯 俄羅斯 莫斯科 伯力 圣彼得堡 新西伯利亚",
        "CA" to "加拿大 多伦多 多倫多 温哥华 溫哥華 蒙特利尔",
        "AU" to "澳大利亚 澳大利亞 澳洲 悉尼 墨尔本 墨爾本",
        "NZ" to "新西兰 紐西蘭 奥克兰",
        "ID" to "印度尼西亚 印度尼西亞 印尼 雅加达 雅加達",
        "IN" to "印度 孟买 孟買 新德里",
        "TR" to "土耳其 伊斯坦布尔",
        "VN" to "越南 胡志明 河内",
        "TH" to "泰国 泰國 曼谷",
        "MY" to "马来西亚 馬來西亞 吉隆坡",
        "PH" to "菲律宾 菲律賓 马尼拉",
        "KH" to "柬埔寨 金边",
        "MN" to "蒙古",
        "KZ" to "哈萨克斯坦 哈薩克",
        "PK" to "巴基斯坦",
        "BD" to "孟加拉",
        "AE" to "阿联酋 阿聯酋 迪拜 杜拜",
        "SA" to "沙特 沙烏地",
        "IL" to "以色列",
        "BR" to "巴西 圣保罗",
        "AR" to "阿根廷",
        "MX" to "墨西哥",
        "CL" to "智利",
        "CO" to "哥伦比亚",
        "PE" to "秘鲁 秘魯",
        "IT" to "意大利 義大利 米兰 米蘭",
        "ES" to "西班牙 马德里",
        "PT" to "葡萄牙 里斯本",
        "CH" to "瑞士 苏黎世 蘇黎世",
        "SE" to "瑞典 斯德哥尔摩",
        "FI" to "芬兰 芬蘭 赫尔辛基",
        "NO" to "挪威 奥斯陆",
        "DK" to "丹麦 丹麥",
        "IS" to "冰岛 冰島",
        "IE" to "爱尔兰 愛爾蘭 都柏林",
        "BE" to "比利时 比利時",
        "AT" to "奥地利 奧地利 维也纳",
        "CZ" to "捷克 布拉格",
        "PL" to "波兰 波蘭 华沙",
        "UA" to "乌克兰 烏克蘭 基辅",
        "RO" to "罗马尼亚 羅馬尼亞",
        "HU" to "匈牙利",
        "GR" to "希腊 希臘",
        "LU" to "卢森堡 盧森堡",
        "ZA" to "南非 约翰内斯堡",
        "EG" to "埃及",
        "NG" to "尼日利亚",
        "CN" to "中国 中國 回国 回國 上海 北京 广州 廣州 深圳 杭州 成都",
    ).flatMap { (code, words) -> words.split(' ').map { it to code } }
        .sortedByDescending { it.first.length }

    /** English place names, then ISO codes; both must stand alone (no letters around). */
    private val latinPatterns: List<Pair<Regex, String>> = run {
        val places = listOf(
            "HK" to "hong kong|hongkong",
            "MO" to "macau|macao",
            "TW" to "taiwan|taipei",
            "JP" to "japan|tokyo|osaka|nagoya",
            "KR" to "south korea|korea|seoul",
            "SG" to "singapore",
            "US" to "united states|america|los angeles|san jose|silicon valley|seattle|new york|chicago|dallas|miami|phoenix|ashburn",
            "GB" to "united kingdom|britain|england|london|manchester",
            "DE" to "germany|frankfurt|berlin|dusseldorf",
            "FR" to "france|paris|marseille",
            "NL" to "netherlands|holland|amsterdam",
            "RU" to "russia|moscow|khabarovsk",
            "CA" to "canada|toronto|vancouver|montreal",
            "AU" to "australia|sydney|melbourne",
            "NZ" to "new zealand|auckland",
            "ID" to "indonesia|jakarta",
            "IN" to "india|mumbai|delhi",
            "TR" to "turkey|turkiye|istanbul",
            "VN" to "vietnam|hanoi",
            "TH" to "thailand|bangkok",
            "MY" to "malaysia|kuala lumpur",
            "PH" to "philippines|manila",
            "AE" to "dubai|emirates",
            "BR" to "brazil|sao paulo",
            "AR" to "argentina",
            "MX" to "mexico",
            "IT" to "italy|milan",
            "ES" to "spain|madrid",
            "CH" to "switzerland|zurich",
            "SE" to "sweden|stockholm",
            "FI" to "finland|helsinki",
            "NO" to "norway|oslo",
            "PL" to "poland|warsaw",
            "UA" to "ukraine|kyiv|kiev",
            "IE" to "ireland|dublin",
            "ZA" to "south africa|johannesburg",
        ).map { (code, words) -> Regex("(?<![A-Za-z])(?:$words)(?![A-Za-z])", RegexOption.IGNORE_CASE) to code }
        // Codes that are rarely anything else; NO, ID, IS, AT and CO are left out
        // ("NO.1", "ID", "IS", "AT", "CO" appear in names for other reasons).
        val codes = listOf(
            "HK", "MO", "TW", "JP", "KR", "SG", "US", "UK", "GB", "DE", "FR", "NL", "RU", "CA", "AU", "NZ",
            "IN", "TR", "VN", "TH", "MY", "PH", "AE", "BR", "AR", "MX", "IT", "ES", "CH", "SE", "FI", "DK",
            "PL", "UA", "IE", "BE", "CZ", "PT", "RO", "HU", "GR", "ZA", "IL", "KH", "KZ", "PK", "CL", "PE",
        )
        val threeLetter = mapOf(
            "USA" to "US", "HKG" to "HK", "TWN" to "TW", "JPN" to "JP", "KOR" to "KR", "SGP" to "SG",
            "GBR" to "GB", "DEU" to "DE", "FRA" to "FR", "NLD" to "NL", "RUS" to "RU", "CAN" to "CA",
            "AUS" to "AU", "IND" to "IN", "TUR" to "TR", "VNM" to "VN", "THA" to "TH", "MYS" to "MY",
            "PHL" to "PH", "BRA" to "BR", "ARG" to "AR", "MEX" to "MX", "MAC" to "MO",
        )
        places +
            // Upper-case code standing alone: "HK 01", "HK01", "US-SJC".
            codes.map { code -> Regex("(?<![A-Za-z])$code(?![A-Za-z])") to code } +
            // Lower-case code only right before a digit or separator: "hk01", "jp-2".
            codes.map { code -> Regex("(?<![A-Za-z])${code.lowercase(Locale.ROOT)}(?=[0-9_-])") to code } +
            threeLetter.map { (code3, code) -> Regex("(?<![A-Za-z])$code3(?![A-Za-z])") to code }
    }
}
