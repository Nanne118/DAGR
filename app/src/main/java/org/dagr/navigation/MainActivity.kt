package org.dagr.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dagr.navigation.data.GpxCodec
import org.dagr.navigation.data.NavigationDatabase
import org.dagr.navigation.data.Waypoint
import org.dagr.navigation.data.WaypointCollection
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val Forest = Color(0xFFB9D2A2)
private val CanvasColor = Color(0xFF111713)
private val PanelColor = Color(0xFF1B241E)
private val MutedText = Color(0xFFADB8AE)
private val TargetColor = Color(0xFFFFB74D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DagrApp() }
    }
}

private enum class MainTab(val label: String) {
    NAVIGATE("Navigate"),
    MAP("Map"),
    WAYPOINTS("Waypoints")
}

@Composable
private fun DagrApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val database = remember(context) { NavigationDatabase.get(context) }
    val dao = remember(database) { database.navigationDao() }
    val preferences = remember(context) {
        context.getSharedPreferences("dagr_preferences", Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var selectedCollectionId by rememberSaveable {
        mutableStateOf(preferences.getString("selected_collection", null))
    }
    var hasLocationPermission by remember { mutableStateOf(hasAnyLocationPermission(context)) }
    var hasFineLocationPermission by remember {
        mutableStateOf(hasFineLocationPermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasLocationPermission = hasAnyLocationPermission(context)
        hasFineLocationPermission = hasFineLocationPermission(context)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val collectionId = selectedCollectionId
        if (uri != null && collectionId != null) {
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        GpxCodec.read(context.contentResolver, uri).map { (name, coordinate) ->
                            Waypoint(
                                id = UUID.randomUUID().toString(),
                                collectionId = collectionId,
                                name = name,
                                latitude = coordinate.first,
                                longitude = coordinate.second
                            )
                        }
                    }
                    dao.insertWaypoints(imported)
                    snackbarHost.showSnackbar("Imported ${imported.size} waypoint(s)")
                } catch (error: java.io.IOException) {
                    snackbarHost.showSnackbar(error.message ?: "Could not import that GPX file")
                } catch (error: org.xmlpull.v1.XmlPullParserException) {
                    snackbarHost.showSnackbar(error.message ?: "The selected file is not valid GPX")
                } catch (error: SecurityException) {
                    snackbarHost.showSnackbar(error.message ?: "Could not import that GPX file")
                } catch (error: android.database.sqlite.SQLiteException) {
                    snackbarHost.showSnackbar(error.message ?: "Could not save imported waypoints")
                }
            }
        }
    }
    var pendingExport by remember { mutableStateOf<List<Waypoint>>(emptyList()) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: throw IllegalStateException("Could not write to the selected file.")
                        output.use { stream ->
                            val serializer = android.util.Xml.newSerializer()
                            serializer.setOutput(stream, "UTF-8")
                            GpxCodec.write(serializer, pendingExport)
                        }
                    }
                    snackbarHost.showSnackbar("GPX export complete")
                } catch (error: java.io.IOException) {
                    snackbarHost.showSnackbar(error.message ?: "Could not export GPX")
                } catch (error: IllegalStateException) {
                    snackbarHost.showSnackbar(error.message ?: "Could not export GPX")
                } catch (error: SecurityException) {
                    snackbarHost.showSnackbar(error.message ?: "Could not export GPX")
                } catch (error: IllegalArgumentException) {
                    snackbarHost.showSnackbar(error.message ?: "Could not export GPX")
                }
            }
        }
    }

    var collections by remember { mutableStateOf(emptyList<WaypointCollection>()) }
    var waypoints by remember { mutableStateOf(emptyList<Waypoint>()) }
    var selectedWaypointId by rememberSaveable {
        mutableStateOf(preferences.getString("selected_waypoint", null))
    }
    var activeTab by rememberSaveable { mutableStateOf(MainTab.NAVIGATE.name) }
    var showSettings by remember { mutableStateOf(false) }
    var coordinateFormat by remember {
        mutableStateOf(
            if (preferences.getString("coordinate_format", "MGRS") == "LAT_LONG") {
                CoordinateFormat.LATITUDE_LONGITUDE
            } else {
                CoordinateFormat.MGRS
            }
        )
    }
    var editingWaypoint by remember { mutableStateOf<Waypoint?>(null) }
    var showWaypointEditor by remember { mutableStateOf(false) }
    var draftCoordinates by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var location by remember { mutableStateOf<Location?>(null) }
    var locationError by remember { mutableStateOf<String?>(null) }
    val heading = remember { mutableFloatStateOf(Float.NaN) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                hasLocationPermission = hasAnyLocationPermission(context)
                hasFineLocationPermission = hasFineLocationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(dao) {
        dao.observeCollections().collect { current ->
            collections = current
            if (current.isEmpty()) {
                dao.insertCollection(
                    WaypointCollection(UUID.randomUUID().toString(), "My waypoints")
                )
            }
        }
    }

    LaunchedEffect(collections) {
        if (collections.isNotEmpty() && collections.none { it.id == selectedCollectionId }) {
            selectedCollectionId = collections.first().id
        }
    }
    LaunchedEffect(selectedCollectionId) {
        val id = selectedCollectionId ?: return@LaunchedEffect
        preferences.edit().putString("selected_collection", id).apply()
        dao.observeWaypoints(id).collect { waypoints = it }
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LocationUpdates(
        hasPermission = hasLocationPermission,
        finePermission = hasFineLocationPermission,
        onLocation = { location = it },
        onError = { locationError = it }
    )
    DeviceHeading(onHeading = { heading.floatValue = it })

    val selectedWaypoint = waypoints.firstOrNull { it.id == selectedWaypointId }
    val collection = collections.firstOrNull { it.id == selectedCollectionId }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Forest,
            onPrimary = Color(0xFF182015),
            secondary = TargetColor,
            background = CanvasColor,
            surface = PanelColor,
            onBackground = Color(0xFFE9EFE9),
            onSurface = Color(0xFFE9EFE9),
            surfaceVariant = Color(0xFF273229),
            onSurfaceVariant = MutedText,
            error = Color(0xFFFF8A80)
        )
    ) {
        Scaffold(
            containerColor = CanvasColor,
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CanvasColor)
                        .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "DAGR",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            when {
                                !hasLocationPermission -> "LOCATION PERMISSION NEEDED"
                                locationError != null -> locationError!!.uppercase(Locale.US)
                                location != null -> "POSITION ACTIVE"
                                else -> "WAITING FOR GPS FIX"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (location != null && locationError == null) Forest else MutedText
                        )
                    }
                    TextButton(onClick = { showSettings = true }) {
                        Text("Settings")
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = PanelColor) {
                    MainTab.values().forEach { tab ->
                        NavigationBarItem(
                            selected = activeTab == tab.name,
                            onClick = { activeTab = tab.name },
                            icon = {
                                Text(
                                    when (tab) {
                                        MainTab.NAVIGATE -> "N"
                                        MainTab.MAP -> "M"
                                        MainTab.WAYPOINTS -> "W"
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        ) { contentPadding ->
            when (MainTab.valueOf(activeTab)) {
                MainTab.NAVIGATE -> NavigateScreen(
                    modifier = Modifier.padding(contentPadding),
                    location = location,
                    heading = heading.floatValue,
                    waypoint = selectedWaypoint,
                    coordinateFormat = coordinateFormat,
                    locationError = locationError,
                    onRequestPermission = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                )
                MainTab.MAP -> MapScreen(
                    modifier = Modifier.padding(contentPadding),
                    location = location,
                    waypoints = waypoints,
                    onAddAtCenter = { latitude, longitude ->
                        editingWaypoint = null
                        draftCoordinates = latitude to longitude
                        showWaypointEditor = true
                    }
                )
                MainTab.WAYPOINTS -> WaypointsScreen(
                    modifier = Modifier.padding(contentPadding),
                    collections = collections,
                    selectedCollection = collection,
                    waypoints = waypoints,
                    selectedWaypointId = selectedWaypointId,
                    coordinateFormat = coordinateFormat,
                    location = location,
                    onSelectCollection = { selectedCollectionId = it },
                    onAddCollection = { name ->
                        scope.launch {
                            val newCollection = WaypointCollection(UUID.randomUUID().toString(), name)
                            try {
                                withContext(Dispatchers.IO) {
                                    dao.insertCollection(newCollection)
                                }
                                selectedCollectionId = newCollection.id
                            } catch (error: android.database.sqlite.SQLiteException) {
                                snackbarHost.showSnackbar(error.message ?: "Could not create waypoint set")
                            }
                        }
                    },
                    onImport = {
                        importLauncher.launch(
                            arrayOf(
                                "application/gpx+xml",
                                "application/gpx",
                                "application/xml",
                                "text/xml",
                                "*/*"
                            )
                        )
                    },
                    onExport = {
                        pendingExport = waypoints
                        exportLauncher.launch("waypoints.gpx")
                    },
                    onAddAtCurrentLocation = {
                        if (location != null) {
                            editingWaypoint = null
                            draftCoordinates = location!!.latitude to location!!.longitude
                            showWaypointEditor = true
                        } else {
                            scope.launch { snackbarHost.showSnackbar("Waiting for a GPS fix") }
                        }
                    },
                    onSelectWaypoint = { waypoint ->
                        selectedWaypointId = waypoint?.id
                        preferences.edit().putString("selected_waypoint", waypoint?.id).apply()
                    },
                    onEditWaypoint = { waypoint ->
                        editingWaypoint = waypoint
                        draftCoordinates = null
                        showWaypointEditor = true
                    },
                    onDeleteWaypoint = { waypoint ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    dao.deleteWaypoint(waypoint.id)
                                }
                            } catch (error: android.database.sqlite.SQLiteException) {
                                snackbarHost.showSnackbar(error.message ?: "Could not delete waypoint")
                            }
                        }
                        if (selectedWaypointId == waypoint.id) {
                            selectedWaypointId = null
                            preferences.edit().remove("selected_waypoint").apply()
                        }
                    }
                )
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            selectedFormat = coordinateFormat,
            onSelectFormat = { format ->
                coordinateFormat = format
                preferences.edit().putString(
                    "coordinate_format",
                    if (format == CoordinateFormat.MGRS) "MGRS" else "LAT_LONG"
                ).apply()
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showWaypointEditor) {
        WaypointEditorDialog(
            waypoint = editingWaypoint,
            coordinates = if (editingWaypoint != null) {
                null
            } else {
                draftCoordinates ?: location?.let { it.latitude to it.longitude }
            },
            onDismiss = { showWaypointEditor = false },
            onSave = { name, latitude, longitude ->
                val targetCollection = selectedCollectionId
                if (targetCollection != null) {
                    val oldWaypoint = editingWaypoint
                    scope.launch(Dispatchers.IO) {
                        try {
                            dao.insertWaypoint(
                                Waypoint(
                                    id = oldWaypoint?.id ?: UUID.randomUUID().toString(),
                                    collectionId = oldWaypoint?.collectionId ?: targetCollection,
                                    name = name,
                                    latitude = latitude,
                                    longitude = longitude
                                )
                            )
                        } catch (error: android.database.sqlite.SQLiteException) {
                            withContext(Dispatchers.Main) {
                                snackbarHost.showSnackbar(error.message ?: "Could not save waypoint")
                            }
                        }
                    }
                }
                showWaypointEditor = false
            }
        )
    }
}

@Composable
private fun NavigateScreen(
    modifier: Modifier,
    location: Location?,
    heading: Float,
    waypoint: Waypoint?,
    coordinateFormat: CoordinateFormat,
    locationError: String?,
    onRequestPermission: () -> Unit
) {
    val magneticBearing = if (location != null && waypoint != null) {
        val target = Location("waypoint").apply {
            latitude = waypoint.latitude
            longitude = waypoint.longitude
        }
        val declination = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis()
        ).declination
        normalizeDegrees(location.bearingTo(target) - declination)
    } else {
        null
    }
    val trueBearing = if (location != null && waypoint != null) {
        val target = Location("waypoint").apply {
            latitude = waypoint.latitude
            longitude = waypoint.longitude
        }
        normalizeDegrees(location.bearingTo(target))
    } else {
        null
    }
    val distance = if (location != null && waypoint != null) {
        val target = Location("waypoint").apply {
            latitude = waypoint.latitude
            longitude = waypoint.longitude
        }
        location.distanceTo(target)
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("CURRENT POSITION", style = MaterialTheme.typography.labelSmall, color = MutedText)
                Text(
                    location?.let { formatCoordinates(it.latitude, it.longitude, coordinateFormat) }
                        ?: "Waiting for GPS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (heading.isFinite()) "HDG ${heading.toInt()}° M" else "HDG --",
                    color = Forest,
                    style = MaterialTheme.typography.labelMedium
                )
                if (location != null && location.hasAccuracy()) {
                    Text("±${location.accuracy.toInt()} m", color = MutedText, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        if (location == null && !locationError.isNullOrBlank()) {
            Text(locationError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            CompassDial(
                heading = heading,
                targetBearing = magneticBearing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(310.dp)
            )
        }

        if (waypoint == null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = PanelColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("No destination selected", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Choose a waypoint to see its direction and distance.",
                        color = MutedText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = PanelColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("DESTINATION", style = MaterialTheme.typography.labelSmall, color = MutedText)
                    Text(waypoint.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Metric("DIST", distance?.let(::formatDistance) ?: "--", Modifier.weight(1f))
                        Metric("BRG TRUE", trueBearing?.let { "${it.toInt()}°" } ?: "--", Modifier.weight(1f))
                        Metric("HDG MAG", if (heading.isFinite()) "${heading.toInt()}°" else "--", Modifier.weight(1f))
                    }
                    Text(
                        formatCoordinates(waypoint.latitude, waypoint.longitude, coordinateFormat),
                        color = MutedText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        if (location == null) {
            TextButton(onClick = onRequestPermission) {
                Text(if (locationError == null) "Enable location" else "Retry location")
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MutedText)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CompassDial(heading: Float, targetBearing: Float?, modifier: Modifier = Modifier) {
    val textColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.39f
        drawCircle(color = Color(0xFF344238), radius = radius + 16.dp.toPx(), center = center)
        drawCircle(
            color = Color(0xFF202B23),
            radius = radius + 12.dp.toPx(),
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        )

        rotate(degrees = if (heading.isFinite()) -heading else 0f, pivot = center) {
            drawCircle(
                color = Color(0xFF718174),
                radius = radius,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
            for (tick in 0 until 36) {
                val angle = Math.toRadians((tick * 10.0) - 90.0)
                val major = tick % 3 == 0
                val innerRadius = radius - if (major) 14.dp.toPx() else 7.dp.toPx()
                drawLine(
                    color = if (major) Forest else Color(0xFF627064),
                    start = Offset(
                        center.x + cos(angle).toFloat() * innerRadius,
                        center.y + sin(angle).toFloat() * innerRadius
                    ),
                    end = Offset(
                        center.x + cos(angle).toFloat() * radius,
                        center.y + sin(angle).toFloat() * radius
                    ),
                    strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx()
                )
            }

            val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor.toArgb()
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 18.sp.toPx()
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            }
            listOf("N", "E", "S", "W").forEachIndexed { index, label ->
                val angle = Math.toRadians((index * 90.0) - 90.0)
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    center.x + cos(angle).toFloat() * (radius - 34.dp.toPx()),
                    center.y + sin(angle).toFloat() * (radius - 34.dp.toPx()) + 6.dp.toPx(),
                    labelPaint
                )
            }
        }

        val pointer = Path().apply {
            moveTo(center.x, center.y - radius - 7.dp.toPx())
            lineTo(center.x - 9.dp.toPx(), center.y - radius + 11.dp.toPx())
            lineTo(center.x + 9.dp.toPx(), center.y - radius + 11.dp.toPx())
            close()
        }
        drawPath(pointer, color = Forest)

        if (targetBearing != null && heading.isFinite()) {
            val angle = Math.toRadians((targetBearing - heading).toDouble() - 90.0)
            val markerRadius = radius - 2.dp.toPx()
            val target = Offset(
                center.x + cos(angle).toFloat() * markerRadius,
                center.y + sin(angle).toFloat() * markerRadius
            )
            drawCircle(color = TargetColor, radius = 8.dp.toPx(), center = target)
            drawCircle(color = CanvasColor, radius = 3.dp.toPx(), center = target)
        }
        drawCircle(color = Forest, radius = 4.dp.toPx(), center = center)
    }
}

@Composable
private fun MapScreen(
    modifier: Modifier,
    location: Location?,
    waypoints: List<Waypoint>,
    onAddAtCenter: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context) {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            val initialPoint = location?.let { GeoPoint(it.latitude, it.longitude) } ?: GeoPoint(0.0, 0.0)
            controller.setCenter(initialPoint)
        }
    }

    var centeredOnFirstFix by remember(mapView) { mutableStateOf(location != null) }
    LaunchedEffect(mapView, location?.latitude, location?.longitude, waypoints) {
        if (location != null && !centeredOnFirstFix) {
            mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude))
            centeredOnFirstFix = true
        }
        mapView.overlays.clear()
        location?.let { current ->
            Marker(mapView).apply {
                position = GeoPoint(current.latitude, current.longitude)
                title = "Your position"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mapView.overlays.add(this)
            }
        }
        waypoints.forEach { waypoint ->
            Marker(mapView).apply {
                position = GeoPoint(waypoint.latitude, waypoint.longitude)
                title = waypoint.name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mapView.overlays.add(this)
            }
        }
        mapView.invalidate()
    }

    DisposableEffect(mapView, lifecycleOwner) {
        var resumed = false
        fun resumeMap() {
            if (!resumed) {
                mapView.onResume()
                resumed = true
            }
        }
        fun pauseMap() {
            if (resumed) {
                mapView.onPause()
                resumed = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> resumeMap()
                Lifecycle.Event.ON_STOP -> pauseMap()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            resumeMap()
        }
        onDispose {
            pauseMap()
            mapView.onDetach()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    location?.let {
                        mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                    }
                },
                enabled = location != null
            ) {
                Text("Recenter")
            }
            Button(onClick = {
                val center = mapView.mapCenter
                onAddAtCenter(center.latitude, center.longitude)
            }) {
                Text("Add at center")
            }
        }
        Text(
            "© OpenStreetMap contributors",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xAA111713))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun WaypointsScreen(
    modifier: Modifier,
    collections: List<WaypointCollection>,
    selectedCollection: WaypointCollection?,
    waypoints: List<Waypoint>,
    selectedWaypointId: String?,
    coordinateFormat: CoordinateFormat,
    location: Location?,
    onSelectCollection: (String) -> Unit,
    onAddCollection: (String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onAddAtCurrentLocation: () -> Unit,
    onSelectWaypoint: (Waypoint?) -> Unit,
    onEditWaypoint: (Waypoint) -> Unit,
    onDeleteWaypoint: (Waypoint) -> Unit
) {
    var showCollectionDialog by remember { mutableStateOf(false) }
    var collectionName by remember { mutableStateOf("") }
    var collectionMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { collectionMenuExpanded = true }) {
                    Text(selectedCollection?.name ?: "Choose collection")
                }
                DropdownMenu(
                    expanded = collectionMenuExpanded,
                    onDismissRequest = { collectionMenuExpanded = false }
                ) {
                    collections.forEach { collection ->
                        DropdownMenuItem(
                            text = { Text(collection.name) },
                            onClick = {
                                onSelectCollection(collection.id)
                                collectionMenuExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showCollectionDialog = true }) { Text("+ Set") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport) { Text("Import GPX") }
            OutlinedButton(onClick = onExport, enabled = waypoints.isNotEmpty()) { Text("Export GPX") }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${waypoints.size} waypoint(s)",
                color = MutedText,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onAddAtCurrentLocation, enabled = location != null) {
                Text("+ Current position")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (waypoints.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = PanelColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "This collection is empty. Add your position or import a GPX file.",
                    color = MutedText,
                    modifier = Modifier.padding(18.dp)
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(waypoints, key = { it.id }) { waypoint ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (waypoint.id == selectedWaypointId) {
                                Color(0xFF2B3A2D)
                            } else {
                                PanelColor
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectWaypoint(
                                    if (waypoint.id == selectedWaypointId) null else waypoint
                                )
                            }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(waypoint.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        formatCoordinates(
                                            waypoint.latitude,
                                            waypoint.longitude,
                                            coordinateFormat
                                        ),
                                        color = MutedText,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (waypoint.id == selectedWaypointId) {
                                    Text("ACTIVE", color = Forest, style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.width(4.dp))
                                }
                                TextButton(onClick = { onEditWaypoint(waypoint) }) { Text("Edit") }
                                TextButton(onClick = { onDeleteWaypoint(waypoint) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCollectionDialog) {
        AlertDialog(
            onDismissRequest = { showCollectionDialog = false },
            title = { Text("New waypoint set") },
            text = {
                OutlinedTextField(
                    value = collectionName,
                    onValueChange = { collectionName = it },
                    label = { Text("Set name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = collectionName.trim()
                        if (name.isNotEmpty()) {
                            onAddCollection(name)
                            collectionName = ""
                            showCollectionDialog = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCollectionDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsDialog(
    selectedFormat: CoordinateFormat,
    onSelectFormat: (CoordinateFormat) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Coordinate display", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                listOf(
                    CoordinateFormat.MGRS to "MGRS grid",
                    CoordinateFormat.LATITUDE_LONGITUDE to "Latitude / longitude"
                ).forEach { (format, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectFormat(format) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (selectedFormat == format) "●" else "○", color = Forest)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Compass heading is magnetic; waypoint bearing is shown relative to true north.",
                    color = MutedText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun WaypointEditorDialog(
    waypoint: Waypoint?,
    coordinates: Pair<Double, Double>?,
    onDismiss: () -> Unit,
    onSave: (String, Double, Double) -> Unit
) {
    var name by remember(waypoint?.id) { mutableStateOf(waypoint?.name.orEmpty()) }
    var latitude by remember(waypoint?.id, coordinates) {
        mutableStateOf(waypoint?.latitude?.toString() ?: coordinates?.first?.toString().orEmpty())
    }
    var longitude by remember(waypoint?.id, coordinates) {
        mutableStateOf(waypoint?.longitude?.toString() ?: coordinates?.second?.toString().orEmpty())
    }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (waypoint == null) "Add waypoint" else "Edit waypoint") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it },
                    label = { Text("Latitude") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it },
                    label = { Text("Longitude") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val lat = latitude.toDoubleOrNull()
                val lon = longitude.toDoubleOrNull()
                when {
                    name.isBlank() -> validationMessage = "Enter a waypoint name."
                    lat == null || lon == null || !lat.isFinite() || !lon.isFinite() ||
                        lat !in -90.0..90.0 || lon !in -180.0..180.0 ->
                        validationMessage = "Enter valid latitude and longitude values."
                    else -> onSave(name.trim(), lat, lon)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LocationUpdates(
    hasPermission: Boolean,
    finePermission: Boolean,
    onLocation: (Location) -> Unit,
    onError: (String?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val manager = remember(context) {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    DisposableEffect(manager, hasPermission, finePermission, lifecycleOwner) {
        if (!hasPermission) {
            onDispose { }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(newLocation: Location) {
                    onLocation(newLocation)
                }

                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(providerName: String?, status: Int, extras: Bundle?) = Unit
            }
            var registered = false
            fun startUpdates() {
                if (registered) return
                try {
                    val provider = when {
                        finePermission && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                            LocationManager.GPS_PROVIDER
                        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                            LocationManager.NETWORK_PROVIDER
                        else -> null
                    }
                    if (provider == null) {
                        onError("No location provider is available")
                    } else {
                        manager.getLastKnownLocation(provider)?.let(onLocation)
                        manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                        registered = true
                        onError(null)
                    }
                } catch (_: SecurityException) {
                    onError("Location permission changed; enable it to continue")
                }
            }
            fun stopUpdates() {
                if (registered) {
                    manager.removeUpdates(listener)
                    registered = false
                }
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> startUpdates()
                    Lifecycle.Event.ON_STOP -> stopUpdates()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                startUpdates()
            }
            onDispose {
                stopUpdates()
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }
}

@Composable
private fun DeviceHeading(onHeading: (Float) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val sensor = remember(sensorManager) {
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    DisposableEffect(sensorManager, sensor, lifecycleOwner) {
        if (sensor == null) {
            onHeading(Float.NaN)
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                private var lastHeading = Float.NaN

                override fun onSensorChanged(event: SensorEvent) {
                    val rotation = FloatArray(9)
                    val remapped = FloatArray(9)
                    val orientation = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val displayRotation = windowManager.defaultDisplay.rotation
                    val axes = when (displayRotation) {
                        android.view.Surface.ROTATION_90 ->
                            SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                        android.view.Surface.ROTATION_180 ->
                            SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                        android.view.Surface.ROTATION_270 ->
                            SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                    }
                    SensorManager.remapCoordinateSystem(rotation, axes.first, axes.second, remapped)
                    SensorManager.getOrientation(remapped, orientation)
                    val nextHeading = normalizeDegrees(Math.toDegrees(orientation[0].toDouble()).toFloat())
                    val difference = abs(((nextHeading - lastHeading + 540f) % 360f) - 180f)
                    if (!lastHeading.isFinite() || difference >= 0.5f) {
                        lastHeading = nextHeading
                        onHeading(nextHeading)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            var registered = false
            fun startSensor() {
                if (!registered) {
                    registered = sensorManager.registerListener(
                        listener,
                        sensor,
                        SensorManager.SENSOR_DELAY_UI
                    )
                }
            }
            fun stopSensor() {
                if (registered) {
                    sensorManager.unregisterListener(listener)
                    registered = false
                }
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> startSensor()
                    Lifecycle.Event.ON_STOP -> stopSensor()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                startSensor()
            }
            onDispose {
                stopSensor()
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }
}

private fun hasAnyLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun hasFineLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun normalizeDegrees(value: Float): Float = ((value % 360f) + 360f) % 360f

private fun formatDistance(meters: Float): String =
    if (meters < 1000f) {
        "${meters.toInt()} m"
    } else {
        String.format(Locale.US, "%.1f km", meters / 1000f)
    }
