package net.kyori.adventure.webui.js

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.dom.hasClass
import net.kyori.adventure.webui.Serializers
import net.kyori.adventure.webui.URL_API
import net.kyori.adventure.webui.URL_CORP_MINI_TO_HTML
import net.kyori.adventure.webui.tryDecodeFromString
import net.kyori.adventure.webui.websocket.Call
import net.kyori.adventure.webui.websocket.Packet
import net.kyori.adventure.webui.websocket.Response
import org.w3c.dom.Element
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.Node
import org.w3c.dom.WebSocket
import org.w3c.dom.Window
import org.w3c.dom.asList
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.fetch.Headers
import org.w3c.fetch.NO_CACHE
import org.w3c.fetch.RequestCache
import org.w3c.fetch.RequestInit
import kotlin.js.Promise
import kotlin.js.json

private lateinit var webSocket: WebSocket

public fun mainLoaded() {
    // WEBSOCKET
    webSocket = WebSocket("ws://ssh.corpium.net:4000/$URL_API$URL_CORP_MINI_TO_HTML")
    webSocket.onopen = { onWebsocketReady() }
    webSocket.onclose = { onWebsocketClose() }
    // A closed websocket will be handled by the above, but log the error to console for debugging sake
    webSocket.onerror = { err -> console.log("Websocket error: $err") }

    // OBFUSCATION
    window.setInterval({ obfuscateAll() }, 10)

    installHoverManager()
}

public fun main() {
    document.addEventListener(
        "adventure-webui.initialize",
        {
            mainLoaded()
        }
    )
}

private fun onWebsocketReady() {
    parse()

    // INPUT
    val input = document.element<HTMLTextAreaElement>("input")
    input.addEventListener("keyup", { parse() })
    input.addEventListener("change", { parse() })
    input.addEventListener(
        "paste",
        { event ->
            event.preventDefault()
            val paste = event.unsafeCast<ClipboardEvent>().clipboardData!!.getData("text")
            document.execCommand("insertText", false, paste.replace("\\n", "\n"))
        }
    )
    val output = document.getElementById("output-pre")!!
    webSocket.onmessage =
        { messageEvent ->
            val data = messageEvent.data
            if (data is String) {
                val response = Serializers.json.tryDecodeFromString<Response>(data)

                response?.parseResult?.let { result ->
                    if (result.success && result.dom != null) {
                        output.textContent = ""

                        document.createElement("div").also { div ->
                            div.innerHTML = result.dom.replace("\n", "<br>")
                            output.append(div)
                        }

                        // reset scroll to bottom (like how chat works)
                        output.scrollTop = output.scrollHeight.toDouble()
                    } else if (!result.success && result.errorMessage != null) {
                        console.error("A parse error occurred: ${result.errorMessage}")
                    } else {
                        console.error("An unknown error occurred!")
                    }
                }
            }
        }
}

private fun onWebsocketClose() {
    // We no longer have a working websocket connection, so any input changes would not go through. Display a little
    // warning to the user and disable the input box to bring more attention to it, since changing the input would
    // have no effect at this point anyway.
    val warning = document.element<HTMLTextAreaElement>("connection-lost-warning")
    val inputBox = document.element<HTMLTextAreaElement>("input")
    warning.hidden = false
    inputBox.disabled = true
}

private fun obfuscateAll() {
    document.getElementsByClassName("obfuscated").asList().forEach { obfuscate(it) }
}

private fun obfuscate(input: Node) {
    if (input is Element && input.hasClass("hover")) return
    val childNodes = input.childNodes
    if (childNodes.length > 0) {
        childNodes.asList().forEach { obfuscate(it) }
    }
    if (input.nodeType == Node.TEXT_NODE) {
        input.nodeValue = obfuscate(input.nodeValue.orEmpty())
    }
}

private fun CharArray.map(transform: (Char) -> Char): CharArray {
    for (i in this.indices) {
        this[i] = transform(this[i])
    }
    return this
}

private fun obfuscate(input: String): String {
    val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')

    return input.toCharArray().map { if (it != ' ') allowedChars.random() else it }.concatToString()
}

@OptIn(ExperimentalStdlibApi::class)
private fun parse() {
    // don't do anything if we're not initialised yet
    if (::webSocket.isInitialized) {
        val input = document.element<HTMLTextAreaElement>("input").value
        // Store current input for persistence

        if (input.isEmpty()) {
            // we don't want to parse if input is empty (server list mode is an exception!)
            document.getElementById("output-pre")!!.textContent = ""
        } else {
            val lines = input.split("\n", "\\n")

            val combinedLines =
                lines.joinToString(separator = "\n") { line ->
                    // we don't want to lose empty lines, so replace them with zero-width space
                    if (line == "") "\u200B" else line
                }

            webSocket.send(Call(combinedLines))
        }
    }
}

public fun WebSocket.send(packet: Packet) {
    this.send(Serializers.json.encodeToString(packet))
}

public inline fun <reified T> Window.postPacket(url: String, packet: T): Promise<org.w3c.fetch.Response> {
    return this.fetch(
        url,
        RequestInit(
            method = "POST",
            cache = RequestCache.NO_CACHE,
            headers = Headers(json("Content-Type" to "text/plain; charset=UTF-8")),
            body = Serializers.json.encodeToString(packet)
        )
    )
}
