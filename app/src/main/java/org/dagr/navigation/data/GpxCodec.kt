package org.dagr.navigation.data

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.IOException

object GpxCodec {
    private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    private const val XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"

    fun read(contentResolver: ContentResolver, uri: Uri): List<Pair<String, Pair<Double, Double>>> {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected GPX file.")
        input.use { stream ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(stream, null)

            val waypoints = mutableListOf<Pair<String, Pair<Double, Double>>>()
            var latitude: Double? = null
            var longitude: Double? = null
            var name: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "wpt" -> {
                            latitude = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            longitude = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            name = null
                        }
                        "name" -> if (latitude != null && longitude != null) {
                            name = parser.nextText().trim()
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "wpt") {
                        val lat = latitude
                        val lon = longitude
                        if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                            waypoints += (name?.takeIf { it.isNotBlank() } ?: "Waypoint") to (lat to lon)
                        }
                        latitude = null
                        longitude = null
                        name = null
                    }
                }
                parser.next()
            }

            if (waypoints.isEmpty()) {
                throw IOException("No valid GPX waypoints were found in the selected file.")
            }
            return waypoints
        }
    }

    fun write(serializer: XmlSerializer, waypoints: List<Waypoint>) {
        serializer.startDocument("UTF-8", true)
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        serializer.setPrefix("", GPX_NAMESPACE)
        serializer.setPrefix("xsi", XSI_NAMESPACE)
        serializer.startTag(GPX_NAMESPACE, "gpx")
        serializer.attribute(null, "version", "1.1")
        serializer.attribute(null, "creator", "DAGR")
        serializer.attribute(
            XSI_NAMESPACE,
            "schemaLocation",
            "$GPX_NAMESPACE $GPX_NAMESPACE/gpx.xsd"
        )
        waypoints.forEach { waypoint ->
            serializer.startTag(null, "wpt")
            serializer.attribute(null, "lat", waypoint.latitude.toString())
            serializer.attribute(null, "lon", waypoint.longitude.toString())
            serializer.startTag(null, "name")
            serializer.text(waypoint.name)
            serializer.endTag(null, "name")
            serializer.endTag(null, "wpt")
        }
        serializer.endTag(GPX_NAMESPACE, "gpx")
        serializer.endDocument()
        serializer.flush()
    }
}
