package com.tapnex.autoclicker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.compose.*
import com.tapnex.autoclicker.ads.BannerAd
import com.tapnex.autoclicker.model.ClickPoint
import com.tapnex.autoclicker.model.PointStore
import com.tapnex.autoclicker.overlay.FloatingControlService
import com.tapnex.autoclicker.overlay.SecondaryControlService
import com.tapnex.autoclicker.settings.FloatingSettingsScreen
import kotlinx.coroutines.delay

/** Fixed TAPNEX look: dark base with red and yellow accents, white text. No theme chooser. */
private object Tp {
    val Bg = Color(0xFF0B0F12)
    val Card = Color(0xFF161D22)
    val CardHi = Color(0xFF1F2930)
    val Red = Color(0xFFE53935)
    val Yellow = Color(0xFFF5B700)
    val White = Color(0xFFFFFFFF)
    val Muted = Color(0xFF8E9AA3)
}

private const val APPS_PREFS = "tapnex_apps"
private const val TARGET_PREFS = "tapnex_target"
private const val TARGET_KEY = "package"

private val tapnexScheme = darkColorScheme(
    primary = Tp.Red,
    onPrimary = Tp.White,
    secondary = Tp.Yellow,
    onSecondary = Color.Black,
    background = Tp.Bg,
    onBackground = Tp.White,
    surface = Tp.Card,
    onSurface = Tp.White,
    surfaceVariant = Tp.CardHi,
    onSurfaceVariant = Tp.Muted,
    error = Tp.Red
)

class MainActivity : ComponentActivity() {

    // Bumped on every resume so permission states and balances are re-read after returning from Settings.
    private val refreshTick = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = tapnexScheme) {
                Surface(Modifier.fillMaxSize(), color = Tp.Bg) {
                    TapnexApp(refreshTick.value)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.value++
    }
}

@Composable
private fun TapnexApp(refresh: Int) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "splash") {
        composable("splash") {
            SplashScreen(onDone = {
                nav.navigate("main") { popUpTo("splash") { inclusive = true } }
            })
        }
        composable("main") {
            MainScreen(
                refresh = refresh,
                onOpenApps = { nav.navigate("apps") },
                onOpenPoints = { nav.navigate("points") }
            )
        }
        composable("apps") { AppPickerScreen(onBack = { nav.popBackStack() }, onAppAdded = { nav.popBackStack() }) }
        composable("points") { PointEditorScreen(onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun SplashScreen(onDone: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
        delay(5000)
        onDone()
    }
    val scale by animateFloatAsState(if (visible) 1f else 0.72f, animationSpec = tween(1100), label = "logoScale")
    Box(Modifier.fillMaxSize().background(Tp.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(
                painter = painterResource(R.drawable.tapnex_splash_logo),
                contentDescription = "TAPNEX logo",
                modifier = Modifier.size(200.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            )
            Text("TAPNEX", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Tp.White)
            Text("Tap smarter. Stay in control.", color = Tp.Muted)
        }
    }
}

@Composable
private fun MainScreen(
    refresh: Int,
    onOpenApps: () -> Unit,
    onOpenPoints: () -> Unit
) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) } // 0 = home, 1 = local profile, 2 = settings

    Scaffold(
        containerColor = Tp.Bg,
        bottomBar = {
            BottomBar(
                selected = tab,
                onStart = {
                    tab = 0
                    toast(ctx, "Open your selected target app; the floating controls appear there.")
                },
                onHome = { tab = 0 },
                onProfile = { tab = 1 },
                onSettings = { tab = 2 }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeTab(refresh, onOpenApps, onOpenPoints)
                1 -> LocalProfileTab()
                else -> SettingsTab(refresh = refresh)
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Int, onStart: () -> Unit, onHome: () -> Unit, onProfile: () -> Unit, onSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(40.dp),
        color = Tp.Card
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onStart,
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tp.Red, contentColor = Tp.White),
                modifier = Modifier.weight(1.8f).height(56.dp)
            ) {
                Icon(Icons.Filled.SportsEsports, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start", fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onProfile, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Person, contentDescription = "Profile", tint = if (selected == 1) Tp.Yellow else Tp.White)
            }
            IconButton(onClick = onSettings, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = if (selected == 2) Tp.Yellow else Tp.White)
            }
        }
    }
}

@Composable
private fun HomeTab(refresh: Int, onOpenApps: () -> Unit, onOpenPoints: () -> Unit) {
    val ctx = LocalContext.current
    var packages by remember(refresh) { mutableStateOf(loadAddedApps(ctx)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painter = painterResource(R.drawable.tapnex_app_icon), contentDescription = "TAPNEX", modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("TAPNEX", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Tp.Red)
                Text("Device-only mode • No account or wallet", color = Tp.Muted, fontSize = 12.sp)
            }
        }
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Tp.CardHi)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("READY TO USE", color = Tp.Yellow, fontWeight = FontWeight.Bold)
                Text("Choose a target app below. TAPNEX floating controls appear when that app is opened.", color = Tp.White)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("APP LIBRARY", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onOpenApps) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add app")
            }
        }
        if (packages.isEmpty()) Text("No apps yet. Tap Add app to choose a target app.", color = Tp.Muted)
        OutlinedButton(onClick = onOpenPoints, modifier = Modifier.fillMaxWidth()) { Text("Manage click points") }
        packages.forEach { pkg ->
            AppRow(
                pkg = pkg,
                onOpen = { openSelectedApp(ctx, pkg) },
                onRemove = {
                    packages = packages - pkg
                    saveAddedApps(ctx, packages)
                    if (getTargetPackage(ctx) == pkg) setTargetPackage(ctx, packages.firstOrNull())
                }
            )
        }
        BannerAd(Modifier.fillMaxWidth())
    }
}

@Composable
private fun LocalProfileTab() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Device profile", fontSize = 26.sp, fontWeight = FontWeight.Black)
        Text("TAPNEX works locally on this device. No email, password, Firebase account, credits, or wallet are used.", color = Tp.Muted)
    }
}

@Composable
private fun AppRow(pkg: String, onOpen: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val info = remember(pkg) { runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() } ?: return
    val icon = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Tp.Card), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onOpen() }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(48.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(ctx.packageManager.getApplicationLabel(info).toString(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("Selected app • Tap to open", color = Tp.Muted, fontSize = 12.sp)
            }
            TextButton(onClick = onRemove) { Text("Remove", color = Tp.Muted) }
        }
    }
}

@Composable
private fun SettingsTab(
    refresh: Int
) {
    val ctx = LocalContext.current
    val overlayOk = remember(refresh) { Permissions.canDrawOverlay(ctx) }
    val accessibilityOk = remember(refresh) { Permissions.isAccessibilityEnabled(ctx) }
    val notificationOk = remember(refresh) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Black)
        SectionCard("Permissions") {
            PermissionRow(
                "Display over other apps", overlayOk,
                if (!overlayOk) { { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) } } else null
            )
            PermissionRow(
                "Accessibility service", accessibilityOk,
                if (!accessibilityOk) { { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } } else null
            )
            if (Build.VERSION.SDK_INT >= 33) {
                PermissionRow(
                    "Notifications", notificationOk,
                    if (!notificationOk) { {
                        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                    } } else null
                )
            }
            Text(
                "TAPNEX needs these permissions to display floating controls, send your configured taps, and keep the service visible.",
                color = Tp.Muted, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun PermissionRow(label: String, enabled: Boolean, action: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(if (enabled) "Allowed" else "Not allowed", color = if (enabled) Tp.Yellow else Tp.Red, fontWeight = FontWeight.Bold)
    }
    if (action != null) {
        OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Text("Open permission settings") }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Tp.Card), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            content()
        })
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(if (ok) "On" else "Off", color = if (ok) Tp.Yellow else Tp.Red, fontWeight = FontWeight.Bold)
    }
}

private fun loadAddedApps(ctx: Context): List<String> =
    ctx.getSharedPreferences(APPS_PREFS, Context.MODE_PRIVATE).getStringSet("packages", emptySet())
        ?.sorted() ?: emptyList()

private fun saveAddedApps(ctx: Context, packages: List<String>) {
    ctx.getSharedPreferences(APPS_PREFS, Context.MODE_PRIVATE).edit().putStringSet("packages", packages.toSet()).apply()
}


private fun getTargetPackage(ctx: Context): String? =
    ctx.getSharedPreferences(TARGET_PREFS, Context.MODE_PRIVATE).getString(TARGET_KEY, null)

private fun setTargetPackage(ctx: Context, pkg: String?) {
    ctx.getSharedPreferences(TARGET_PREFS, Context.MODE_PRIVATE).edit().apply {
        if (pkg == null) remove(TARGET_KEY) else putString(TARGET_KEY, pkg)
    }.apply()
}
/** Launches the selected app. The accessibility service detects it and shows controls there. */
private fun openSelectedApp(ctx: Context, pkg: String) {
    setTargetPackage(ctx, pkg)
    val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
    if (launch == null) {
        toast(ctx, "This app cannot be opened from TAPNEX.")
        return
    }
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(launch) }
        .onFailure { toast(ctx, "Could not open this app.") }
}

private fun toast(ctx: Context, message: String) {
    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
}

@Composable
private fun AppPickerScreen(onBack: () -> Unit, onAppAdded: () -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    var search by remember { mutableStateOf("") }
    var selectedPackages by remember { mutableStateOf(loadAddedApps(ctx).toSet()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val apps = remember {
        runCatching {
            pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != ctx.packageName }
                .map { LaunchableApp(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }
    val visibleApps = remember(apps, search) {
        if (search.isBlank()) apps else apps.filter { it.label.contains(search, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Choose apps", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search apps") }
        )
        val targetPackage = getTargetPackage(ctx)
        val targetLabel = targetPackage?.let { pkg -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
        Text(
            if (targetLabel == null) "Select one app as the automatic target." else "Automatic target: $targetLabel",
            color = Tp.Muted, fontSize = 13.sp
        )
        notice?.let { Text(it, color = Tp.Yellow, fontWeight = FontWeight.SemiBold) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(visibleApps, key = { it.packageName }) { app ->
                Card(colors = CardDefaults.cardColors(containerColor = Tp.Card), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val bitmap = remember(app.packageName) {
                            runCatching { app.icon.toBitmap(96, 96).asImageBitmap() }.getOrNull()
                        }
                        if (bitmap != null) Image(bitmap, contentDescription = "${app.label} icon", modifier = Modifier.size(44.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold)
                            Text(app.packageName, fontSize = 11.sp, color = Tp.Muted, maxLines = 1)
                        }
                        if (app.packageName in selectedPackages) {
                            TextButton(onClick = { setTargetPackage(ctx, app.packageName); notice = "${app.label} is now the automatic target." }) {
                                Text(if (getTargetPackage(ctx) == app.packageName) "Target" else "Set target")
                            }
                        } else {
                            Button(onClick = {
                                selectedPackages = selectedPackages + app.packageName
                                saveAddedApps(ctx, selectedPackages.toList())
                                if (getTargetPackage(ctx) == null) setTargetPackage(ctx, app.packageName)
                                notice = "${app.label} added and selected as the automatic target."
                            }) { Text("Add") }
                        }
                    }
                }
            }
        }
        Button(onClick = onAppAdded, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun PointEditorScreen(onBack: () -> Unit) {
    var points by remember { mutableStateOf(PointStore.load()) }

    fun updatePoint(id: String, transform: (ClickPoint) -> ClickPoint) {
        points = points.map { if (it.id == id) transform(it) else it }
        PointStore.save(points)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Tap points", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("Positions are fractions of the screen (0 = left/top, 1 = right/bottom). Recheck them after rotation.", color = Tp.Muted, fontSize = 13.sp)
        Button(onClick = {
            points = points + ClickPoint(label = "Point ${points.size + 1}", nx = .5f, ny = .5f)
            PointStore.save(points)
        }, modifier = Modifier.fillMaxWidth()) { Text("Add point") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(points, key = { it.id }) { point ->
                Card(colors = CardDefaults.cardColors(containerColor = Tp.Card), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(point.label, fontWeight = FontWeight.SemiBold)
                        Text("Horizontal: ${"%.2f".format(point.nx)}")
                        Slider(value = point.nx, onValueChange = { v -> updatePoint(point.id) { it.copy(nx = v) } }, valueRange = 0f..1f)
                        Text("Vertical: ${"%.2f".format(point.ny)}")
                        Slider(value = point.ny, onValueChange = { v -> updatePoint(point.id) { it.copy(ny = v) } }, valueRange = 0f..1f)
                        Text("Opacity: ${point.opacityPercent}%")
                        Slider(value = point.opacityPercent.toFloat(), onValueChange = { v -> updatePoint(point.id) { it.copy(opacityPercent = v.toInt()) } }, valueRange = 10f..50f)
                        Text("Icon size: ${point.sizeDp} dp")
                        Slider(value = point.sizeDp.toFloat(), onValueChange = { v -> updatePoint(point.id) { it.copy(sizeDp = v.toInt()) } }, valueRange = 28f..64f)
                        Text("Interval: ${point.intervalMs} ms")
                        Slider(
                            value = point.intervalMs.toFloat(),
                            onValueChange = { v -> updatePoint(point.id) { it.copy(intervalMs = v.toLong()) } },
                            valueRange = 100f..5000f
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = point.enabled, onCheckedChange = { enabled -> updatePoint(point.id) { it.copy(enabled = enabled) } })
                            Text("Enabled")
                            Spacer(Modifier.width(12.dp))
                            TextButton(onClick = { updatePoint(point.id) { it.copy(locked = !it.locked) } }) { Text(if (point.locked) "Unlock" else "Lock") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                points = points.filterNot { it.id == point.id }
                                PointStore.save(points)
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

private data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable
)
