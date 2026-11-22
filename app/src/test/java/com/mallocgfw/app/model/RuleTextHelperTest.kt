package com.mallocgfw.app.model

import com.mallocgfw.app.model.RuleTextHelper.Action
import com.mallocgfw.app.model.RuleTextHelper.Kind
import com.mallocgfw.app.model.RuleTextHelper.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleTextHelperTest {
    @Test
    fun pastedLinkBecomesWholeSiteRule() {
        assertEquals(
            "DOMAIN-SUFFIX,chatgpt.com,PROXY",
            RuleTextHelper.buildLine("https://www.chatgpt.com/c/abc?x=1", Match.Suffix, Action.Proxy),
        )
        assertEquals(
            "DOMAIN,api.example.com,DIRECT",
            RuleTextHelper.buildLine("api.example.com:8443", Match.Exact, Action.Direct),
        )
        assertEquals(
            "DOMAIN-KEYWORD,google,REJECT",
            RuleTextHelper.buildLine("Google", Match.Keyword, Action.Reject),
        )
    }

    @Test
    fun ipInputBecomesRangeRule() {
        assertEquals(Match.IpRange, RuleTextHelper.detectMatch("1.2.3.4", Match.Suffix))
        assertEquals(Match.Suffix, RuleTextHelper.detectMatch("example.com", Match.IpRange))
        assertEquals("IP-CIDR,1.2.3.4/32,PROXY,no-resolve", RuleTextHelper.buildLine("1.2.3.4", Match.IpRange, Action.Proxy))
        assertEquals("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve", RuleTextHelper.buildLine("10.0.0.0/8", Match.IpRange, Action.Direct))
        assertEquals("IP-CIDR6,2001:db8::/32,PROXY,no-resolve", RuleTextHelper.buildLine("2001:db8::/32", Match.IpRange, Action.Proxy))
    }

    @Test
    fun badInputIsRejected() {
        assertNull(RuleTextHelper.buildLine("", Match.Suffix, Action.Proxy))
        assertNull(RuleTextHelper.buildLine("localhost", Match.Suffix, Action.Proxy))
        assertNull(RuleTextHelper.buildLine("example.com", Match.IpRange, Action.Proxy))
        assertNull(RuleTextHelper.buildLine("a,b.com", Match.Suffix, Action.Proxy))
    }

    @Test
    fun describesLinesInPlainTerms() {
        val views = RuleTextHelper.describe(
            "# comment\nDOMAIN-SUFFIX,openai.com,PROXY\n\nGEOIP,CN,DIRECT,no-resolve\nDOMAIN,a.com,NODE:id:key:Tokyo_01\nDOMAIN-KEYWORD,ads\n",
        )
        assertEquals(listOf(2, 4, 5, 6), views.map { it.lineNumber })
        assertEquals(Kind.Suffix, views[0].kind)
        assertEquals(Action.Direct, views[1].action)
        assertEquals("Tokyo 01", views[2].nodeLabel)
        assertEquals(Action.Proxy, views[3].action)
    }

    @Test
    fun templatesSkipDuplicatesAndValidate() {
        val ai = RuleTextHelper.templates.first { it.id == "ai" }.lines
        val (once, added) = RuleTextHelper.appendLines("DOMAIN-SUFFIX,openai.com,PROXY", ai)
        assertEquals(ai.size - 1, added)
        val (twice, addedAgain) = RuleTextHelper.appendLines(once, ai)
        assertEquals(0, addedAgain)
        assertEquals(once, twice)
        RuleTextHelper.templates.forEach { template ->
            val result = RuleSourceManager.validateTextRules(template.lines.joinToString("\n"), RuleSourceType.Auto)
            assertTrue("${template.id}: ${result.message}", result.valid)
        }
    }

    @Test
    fun removeLineDropsOnlyThatLine() {
        assertEquals("a\nc", RuleTextHelper.removeLine("a\nb\nc", 2))
    }

    @Test
    fun githubPageLinksBecomeRawLinks() {
        assertEquals(
            "https://raw.githubusercontent.com/u/r/master/rule/x.list",
            RuleUrlPresets.normalizeUrl(" https://github.com/u/r/blob/master/rule/x.list "),
        )
        assertEquals("https://example.com/a.list", RuleUrlPresets.normalizeUrl("https://example.com/a.list"))
    }
}
