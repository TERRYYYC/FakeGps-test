package name.caiyao.fakegps.ui.screen.map

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onAddProfile: (lat: Double, lon: Double) -> Unit,
    onOpenCollection: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: MapViewModel = viewModel(),
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val tapped by vm.tappedPoint.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val count by vm.profileCount.collectAsState()

    var showSearchDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var searchCoord by remember { mutableStateOf("") }

    // MapView reference for imperative operations
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "FakeGPS",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 16.dp),
                )
                NavigationDrawerItem(
                    icon = {
                        BadgedBox(badge = {
                            if (count > 0) Badge { Text("$count") }
                        }) {
                            Icon(Icons.Default.Bookmarks, contentDescription = null)
                        }
                    },
                    label = { Text("收藏档案") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenCollection()
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                )
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("FakeGPS") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearchDialog = true }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "清空")
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        val p = tapped
                        if (p != null) {
                            onAddProfile(p.lat, p.lon)
                            vm.clearTap()
                        } else {
                            scope.launch { snackbarHostState.showSnackbar("请先在地图上点击一个位置") }
                        }
                    },
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加档案")
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                OsmMapView(
                    profiles = profiles,
                    onTap = { lat, lon -> vm.onMapTap(lat, lon) },
                    onMapReady = { mapViewRef = it },
                )
            }
        }
    }

    // Search dialog
    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("搜索坐标") },
            text = {
                OutlinedTextField(
                    value = searchCoord,
                    onValueChange = { searchCoord = it },
                    label = { Text("纬度,经度") },
                    placeholder = { Text("39.9042,116.4074") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSearchDialog = false
                    val parts = searchCoord.split(",")
                    if (parts.size == 2) {
                        val lat = parts[0].trim().toDoubleOrNull()
                        val lon = parts[1].trim().toDoubleOrNull()
                        if (lat != null && lon != null) {
                            mapViewRef?.controller?.run {
                                setCenter(GeoPoint(lat, lon))
                                setZoom(15.0)
                            }
                            vm.onMapTap(lat, lon)
                        }
                    }
                    searchCoord = ""
                }) { Text("搜索") }
            },
            dismissButton = {
                TextButton(onClick = { showSearchDialog = false; searchCoord = "" }) { Text("取消") }
            },
        )
    }

    // Clear all dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("确认清空") },
            text = { Text("删除所有已保存的档案？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAll()
                    showClearDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun OsmMapView(
    profiles: List<name.caiyao.fakegps.data.db.ProfileSummary>,
    onTap: (Double, Double) -> Unit,
    onMapReady: (MapView) -> Unit,
) {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(39.9042, 116.4074)) // Default: Beijing
        }
    }

    // Tap listener
    LaunchedEffect(Unit) {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                // Remove previous temp marker (tagged "temp")
                mapView.overlays.removeAll { it is Marker && (it as Marker).id == "temp" }

                val marker = Marker(mapView).apply {
                    id = "temp"
                    position = p
                    isDraggable = true
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "%.6f, %.6f".format(p.latitude, p.longitude)
                }
                mapView.overlays.add(marker)
                mapView.invalidate()
                onTap(p.latitude, p.longitude)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        mapView.overlays.add(0, MapEventsOverlay(receiver))
        onMapReady(mapView)
    }

    // Update profile markers when data changes
    LaunchedEffect(profiles) {
        // Remove old profile markers and polylines (keep temp marker and events overlay)
        mapView.overlays.removeAll {
            (it is Marker && (it as Marker).id != "temp") || it is Polyline
        }

        val points = mutableListOf<GeoPoint>()
        for (p in profiles) {
            val lat = p.latitude ?: continue
            val lon = p.longitude ?: continue
            val point = GeoPoint(lat, lon)
            points.add(point)

            val marker = Marker(mapView).apply {
                id = "profile_${p.id}"
                position = point
                isDraggable = false
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = p.addname ?: "%.4f, %.4f".format(lat, lon)
            }
            mapView.overlays.add(marker)
        }

        if (points.size > 1) {
            val polyline = Polyline().apply {
                setPoints(points)
                outlinePaint.color = AndroidColor.BLUE
                outlinePaint.strokeWidth = 6f
            }
            mapView.overlays.add(polyline)
        }

        if (points.isNotEmpty()) {
            mapView.controller.setCenter(points.last())
        }

        mapView.invalidate()
    }

    // Lifecycle
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}
