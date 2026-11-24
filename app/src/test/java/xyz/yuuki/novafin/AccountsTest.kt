package xyz.yuuki.novafin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountsTest {
    private fun acc(server: String, user: String, token: String = "t-$server-$user") =
        Account(server, "Server $server", "10.11.0", user, "User $user", token, listOf(Address("http://$server.lan:8096", "LAN")))

    private val home = acc("s1", "ana")
    private val kid = acc("s1", "leo")
    private val friend = acc("s2", "ana")

    @Test fun switchingStashesTheCurrentOne() {
        assertEquals(friend to listOf(home, kid), switchAccount(home, listOf(kid, friend), friend))
    }

    @Test fun addingKeepsEveryoneElse() {
        assertEquals(friend to listOf(home, kid), switchAccount(home, listOf(kid), friend))
    }

    @Test fun firstAccountHasNothingToStash() {
        assertEquals(home to emptyList<Account>(), switchAccount(null, emptyList(), home))
    }

    @Test fun sameUserOnSameServerIsReplacedNotDuplicated() {
        val renewed = acc("s1", "ana", "new-token")
        // As the account in use, and as a saved one.
        assertEquals(renewed to listOf(kid), switchAccount(home, listOf(kid), renewed))
        assertEquals(renewed to listOf(kid, friend), switchAccount(kid, listOf(home, friend), renewed))
        assertEquals("new-token", switchAccount(kid, listOf(home), renewed).first.token)
    }

    @Test fun sameUserIdOnAnotherServerIsADifferentAccount() {
        assertEquals(friend to listOf(home), switchAccount(home, emptyList(), friend))
    }

    @Test fun jsonRoundTrip() {
        val full = Account(
            "s1", "Home", "10.11.2", "u1", "Ana", "tok",
            listOf(Address("http://192.168.1.10:8096", "LAN"), Address("https://jf.example.com", "Public", tv = true)),
            active = "http://192.168.1.10:8096", pinned = "https://jf.example.com", isAdmin = true, audioLang = "jpn", subLang = "spa", subMode = "Smart",
        )
        assertEquals(full, Account.from(JSONObject(full.toJson().toString())))
        assertEquals(home, Account.from(JSONObject(home.toJson().toString())))
    }

    @Test fun readsStoredJson() {
        val stored = """{"serverId":"s1","serverName":"Server s1","serverVersion":"10.11.0","userId":"ana","userName":"User ana","token":"t-s1-ana",""" +
            """"addresses":[{"url":"http://s1.lan:8096","label":"LAN","tv":false}],"isAdmin":false}"""
        assertEquals(home, Account.from(JSONObject(stored)))
    }
}
