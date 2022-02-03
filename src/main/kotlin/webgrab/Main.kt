package webgrab

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.io.File
import java.net.URL
import java.net.URLConnection
import java.security.MessageDigest


private val hexArray = "0123456789ABCDEF".toCharArray()

data class library(var checksum: String, var length: String, var detail: String, var status: String)

/*
https://www.techiedelight.com/add-new-element-array-kotlin/
 */
fun append(arr: Array<library>, element: library): Array<library> {
    val list: MutableList<library> = arr.toMutableList()
    list.add(element)
    return list.toTypedArray()
}

fun readLibraries(fileName: String): Array<library> {

    var libraries = arrayOf(library("", "", "", ""))

    val lineList = mutableListOf<String>()

    File(fileName).useLines { lines ->
        lines.forEach {
            lineList.add(it)
            var libraryLine: List<String> = it.split(",")
            var lib = library(libraryLine.get(1), libraryLine.get(0), libraryLine.get(2), libraryLine.get(3))
            libraries = append(libraries, lib)
        }
    }

    return libraries

}

fun bytesToHex(bytes: ByteArray): String {
    val hexChars = CharArray(bytes.size * 2)
    for (j in bytes.indices) {
        val v = bytes[j].toInt() and 0xFF

        hexChars[j * 2] = hexArray[v ushr 4]
        hexChars[j * 2 + 1] = hexArray[v and 0x0F]
    }
    return String(hexChars)
}

fun sha256(input: String) = hashString("SHA-256", input)
fun sha256(input: ByteArray) = hashString("SHA-256", input)

fun hashString(type: String, input: ByteArray): ByteArray {
    val bytes = MessageDigest
        .getInstance(type)
        .digest(input)
    return bytes
}

fun hashString(type: String, input: String): ByteArray {
    val bytes = MessageDigest
        .getInstance(type)
        .digest(input.toByteArray())
    return bytes
}


fun scanJSIncludes(baseURL: String, verbose: Boolean, dump: Boolean, dumpDir: String) {

    try {
//This will get you the response.

        val myUrl = URL(baseURL)
        val urlConn: URLConnection = myUrl.openConnection()
        urlConn.connect()

        var  headerName="";
        var runner = 1

        while (urlConn.getHeaderFieldKey(runner) != null && urlConn != null) {
            headerName = urlConn.getHeaderFieldKey(runner)
            var headerValue = urlConn.getHeaderField(runner)
            println("$headerName : $headerValue")
            runner++;
        }



        runner = 0

        var doc = Jsoup
            .connect(baseURL)
            .ignoreContentType(true)
            .timeout(2000).get()

        doc.select("script").forEach {


            var scriptSource = it.attr("src")
            if (scriptSource.length > 2) {

                if (verbose) println("Run: " + runner + " Scriptsource: " + scriptSource)
                
                if (!scriptSource.startsWith("http")) {
                    if (verbose) println("scriptsource did not start with http... script source = " + scriptSource)
                    scriptSource = baseURL + scriptSource
                }

                if (verbose) println("Connecting to...source = " + scriptSource)

                val bytes = Jsoup.connect(scriptSource)
                    .header("Accept-Encoding", "gzip, deflate")
                    .userAgent("Mozilla/5.0 (Windows NT 6.1; WOW64; rv:23.0) Gecko/20100101 Firefox/23.0")
                    .referrer(baseURL)
                    .ignoreContentType(true)
                    .maxBodySize(0)
                    .timeout(2000)
                    .execute()
                    .bodyAsBytes()

                val hash = bytesToHex(sha256(bytes))

                println("Size of " + scriptSource + " " + bytes.size + " with hash " + hash)

                if (dump) {
                    val fileName = dumpDir + "/" + hash
                    val myfile = File(fileName)
                    myfile.writeBytes(bytes)
                }

            }
        }   // if script source is > 2


    } // try
    catch (e: java.net.SocketTimeoutException) {
        println("Info: Unable to connect to URL $baseURL (SocketTimeout)")
    }
    catch (e: java.net.NoRouteToHostException) {
        println("Info: Unable to connect/no route to  URL $baseURL")
    }

    catch (e: java.net.UnknownHostException) {
        println("Info: Unknown hosts at $baseURL")
    }
}


fun main(args: Array<String>) = runBlocking {

    val parser = ArgParser("webgrab")

    var input by parser.option(ArgType.String, shortName = "i", description = "input URL").default("https://httpbin.org/cookies/set?http-only-cookie=test;%20httponly")
    var inputList by parser.option(ArgType.String, shortName = "iL", description = "input URL list").default("")
    var output by parser.option(ArgType.String, shortName = "o", description = "Output file name").default("")
    var dumpDir by parser.option(ArgType.String, shortName = "dD", description = "Name of dump directory").default("./dump/")
    val dump by parser.option(ArgType.Boolean, shortName = "d", description = "Turn on dumping downloaded JS libraries")
        .default(false)
    val verbose by parser.option(ArgType.Boolean, shortName = "v", description = "Turn on debugging info")
        .default(false)

    parser.parse(args)

    if (inputList.length < 2) {
        if (verbose) println("Scanning single URL: " + input)
        scanJSIncludes(input, verbose, dump, dumpDir)
    } else {

        println("Scanning URL list at " + inputList)

        File(inputList).useLines { lines ->
            lines.forEach {

                var url:String = it
                if (url.startsWith("http")) { scanJSIncludes(url, verbose, dump, dumpDir) }
                else {

                    // URL list entry without http lead

                    if (verbose) println("Scanning URL " + url)

                        launch {
                            scanJSIncludes("http://" + url, verbose, dump, dumpDir)

                        }

                    launch {

                        scanJSIncludes("https://" + url, verbose, dump, dumpDir)
                    }

                }

            }
        }
    } // else

}
