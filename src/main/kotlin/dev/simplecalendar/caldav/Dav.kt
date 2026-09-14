package dev.simplecalendar.caldav

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** XML namespaces used by WebDAV, CalDAV and the two widespread vendor extensions. */
object Ns {
    const val DAV = "DAV:"
    const val CALDAV = "urn:ietf:params:xml:ns:caldav"
    const val CALENDARSERVER = "http://calendarserver.org/ns/"
    const val APPLE = "http://apple.com/ns/ical/"
}

/**
 * Minimal DOM helpers for reading `multistatus` documents.
 *
 * WebDAV responses are small and their shape is fixed, so the JDK parser is plenty — it avoids
 * pulling in an XML library and keeps the wire format visible in the code that reads it.
 */
object DavXml {

    private val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Server responses are not trusted input; disable external entity resolution outright.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }

    fun parse(xml: String): Document = synchronized(factory) {
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

/** Direct element children matching a namespace and local name. */
fun Node.children(ns: String, name: String): List<Element> {
    val result = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node is Element && node.namespaceURI == ns && node.localName == name) result += node
    }
    return result
}

fun Node.child(ns: String, name: String): Element? = children(ns, name).firstOrNull()

/** Descendant elements matching a namespace and local name, at any depth. */
fun Node.descendants(ns: String, name: String): List<Element> {
    val result = mutableListOf<Element>()
    fun walk(node: Node) {
        val nodes = node.childNodes
        for (i in 0 until nodes.length) {
            val child = nodes.item(i)
            if (child is Element) {
                if (child.namespaceURI == ns && child.localName == name) result += child
                walk(child)
            }
        }
    }
    walk(this)
    return result
}

fun Node.descendant(ns: String, name: String): Element? = descendants(ns, name).firstOrNull()

val Node.text: String get() = textContent?.trim().orEmpty()

/**
 * One `<response>` entry of a multistatus document, flattened.
 *
 * WebDAV splits properties across several `<propstat>` blocks by status code; everything that
 * came back 2xx is merged here and the rest dropped, which is what callers actually want.
 */
class DavResponse(private val element: Element) {

    val href: String = element.child(Ns.DAV, "href")?.text.orEmpty()

    /** Status of the response element itself — 404 here means the resource was deleted. */
    val status: Int? = element.child(Ns.DAV, "status")?.text?.let(::parseStatusCode)

    private val properties: List<Element> = element.children(Ns.DAV, "propstat")
        .filter { propstat ->
            val code = propstat.child(Ns.DAV, "status")?.text?.let(::parseStatusCode)
            code == null || code in 200..299
        }
        .mapNotNull { it.child(Ns.DAV, "prop") }
        .flatMap { prop ->
            val children = mutableListOf<Element>()
            val nodes = prop.childNodes
            for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { children += it }
            children
        }

    fun prop(ns: String, name: String): Element? =
        properties.firstOrNull { it.namespaceURI == ns && it.localName == name }

    fun propText(ns: String, name: String): String? = prop(ns, name)?.text?.takeIf { it.isNotEmpty() }
}

fun Document.responses(): List<DavResponse> =
    documentElement.children(Ns.DAV, "response").map(::DavResponse)

/** Extracts the numeric code from a `HTTP/1.1 404 Not Found` status line. */
private fun parseStatusCode(statusLine: String): Int? =
    statusLine.split(' ').getOrNull(1)?.toIntOrNull()

/** ETags arrive quoted and sometimes weak-tagged; normalise before comparing or storing. */
fun normaliseEtag(raw: String?): String? = raw
    ?.trim()
    ?.removePrefix("W/")
    ?.trim('"')
    ?.takeIf { it.isNotEmpty() }
