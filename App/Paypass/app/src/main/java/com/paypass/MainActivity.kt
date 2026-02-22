package com.paypass

import android.Manifest
import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton

// Extension property for DataStore
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "scanner") {
                        composable("scanner") {
                            PaypassScreen(
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}

data class UpiAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PaypassScreen(onNavigateToSettings: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()
    
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    var selectedAppPackage by remember { mutableStateOf<String?>(null) }
    var isScanningLocked by remember { mutableStateOf(false) }
    var lastScannedUpi by remember { mutableStateOf<String?>(null) }
    var displayErrorToast by remember { mutableStateOf<String?>(null) }
    
    val SELECTED_APP_KEY = stringPreferencesKey("selected_upi_app")

    val upiApps = remember {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("upi://pay?pa=test@upi&pn=Test&am=1.00&cu=INR")
        }
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val resolveInfos: List<ResolveInfo> = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, flags)
        }
        
        val apps = resolveInfos.map {
            UpiAppInfo(
                packageName = it.activityInfo.packageName,
                appName = it.loadLabel(pm).toString(),
                icon = it.loadIcon(pm)
            )
        }.distinctBy { it.packageName }
        
        apps
    }
    
    // Load selected app and check Selection Mode from DataStore
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        val savedPackage = prefs[SELECTED_APP_KEY]
        val selectionMode = prefs[SELECTION_MODE_KEY] ?: MODE_REMEMBER_LAST // Default is remember
        
        if (selectionMode == MODE_FIXED_DEFAULT && savedPackage != null && upiApps.any { it.packageName == savedPackage }) {
            // Unconditionally force the fixed app
            selectedAppPackage = savedPackage
        } else {
            // Otherwise, fall back to whatever was saved (or first app if empty)
            if (savedPackage != null && upiApps.any { it.packageName == savedPackage }) {
                selectedAppPackage = savedPackage
            } else if (upiApps.isNotEmpty()) {
                selectedAppPackage = upiApps.first().packageName
            }
        }
    }

    // Reset scanner lock after 3 seconds of moving away or scanning
    LaunchedEffect(isScanningLocked) {
        if (isScanningLocked) {
            delay(3000)
            isScanningLocked = false
        }
    }
    
    // Show Toast for errors
    LaunchedEffect(displayErrorToast) {
        displayErrorToast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            displayErrorToast = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top 80%: Camera Viewfinder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.8f)
                .background(Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            if (cameraPermissionState.status.isGranted) {
                CameraPreviewScreen(
                    onQrCodeScanned = { qrContent ->
                        if (!isScanningLocked) {
                            if (qrContent.startsWith("upi://")) {
                                isScanningLocked = true
                                lastScannedUpi = qrContent
                                
                                selectedAppPackage?.let { targetPackage ->
                                    try {
                                        val launchIntent = Intent(Intent.ACTION_VIEW).apply {
                                            data = Uri.parse(qrContent)
                                            setPackage(targetPackage)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(launchIntent)
                                    } catch (e: Exception) {
                                        Log.e("Paypass", "Failed to launch app: $targetPackage", e)
                                        displayErrorToast = "Failed to launch ${targetPackage}. Try another app."
                                        isScanningLocked = false
                                    }
                                } ?: run {
                                    displayErrorToast = "No UPI app selected"
                                }
                            } else {
                                // Error handling: Scanned a non-upi QR code
                                isScanningLocked = true // lock briefly to avoid spamming toasts
                                displayErrorToast = "Not a UPI QR code!"
                            }
                        }
                    },
                    onQrCodeLost = {
                        // Move-Away Reset: Unlock the UI when QR code leaves the frame
                        isScanningLocked = false
                        lastScannedUpi = null
                    }
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Camera permission is required to scan QR codes",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                        Text("Grant Permission")
                    }
                }
            }
            
            if (isScanningLocked && lastScannedUpi != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Processing Payment...",
                        color = Color.White,
                        fontSize = 20.sp
                    )
                }
            }
            
            // Settings Icon Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.4f), shape = CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = Color.White
                    )
                }
            }
        }
        
        // Bottom 20%: App Selection Strip
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.2f)
                .background(Color(0xFF1E1E1E)),
            contentAlignment = Alignment.Center
        ) {
            if (upiApps.isEmpty()) {
                Text(
                    text = "No UPI apps found",
                    color = Color.Gray
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize().alpha(if (isScanningLocked) 0.5f else 1f)
                ) {
                    items(upiApps) { appInfo ->
                        val isSelected = appInfo.packageName == selectedAppPackage
                        UpiAppItem(
                            appInfo = appInfo,
                            isSelected = isSelected,
                            onClick = {
                                if (!isScanningLocked) {
                                    selectedAppPackage = appInfo.packageName
                                    // Save the new selection only if in Remember Last mode
                                    scope.launch {
                                        val prefs = context.dataStore.data.first()
                                        val mode = prefs[SELECTION_MODE_KEY] ?: MODE_REMEMBER_LAST
                                        if (mode == MODE_REMEMBER_LAST) {
                                            context.dataStore.edit { preferences ->
                                                preferences[SELECTED_APP_KEY] = appInfo.packageName
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreviewScreen(onQrCodeScanned: (String) -> Unit, onQrCodeLost: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = ContextCompat.getMainExecutor(ctx)
            val analysisExecutor = Executors.newSingleThreadExecutor()
            
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                
                imageAnalysis.setAnalyzer(analysisExecutor, QrCodeAnalyzer(onQrCodeScanned, onQrCodeLost))
                
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", e)
                }
            }, executor)
            
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

class QrCodeAnalyzer(
    private val onQrCodeScanned: (String) -> Unit,
    private val onQrCodeLost: () -> Unit
) : ImageAnalysis.Analyzer {
    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    private val scanner = BarcodeScanning.getClient(options)
    
    private var lastScannedTime = 0L
    private val debounceTimeMs = 2000L // Prevent rapid re-launches
    private var isQrInFrame = false
    private var framesWithoutQr = 0
    private val maxFramesWithoutQr = 10 // Reset after 10 frames of no QR
    
    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val now = System.currentTimeMillis()
                    
                    if (barcodes.isNotEmpty()) {
                        framesWithoutQr = 0
                        isQrInFrame = true
                        
                        // Handle Debounce
                        if (now - lastScannedTime > debounceTimeMs) {
                            for (barcode in barcodes) {
                                barcode.rawValue?.let { value ->
                                    if (value.startsWith("upi://")) {
                                        lastScannedTime = now
                                        onQrCodeScanned(value)
                                    }
                                }
                            }
                        }
                    } else {
                        // Handle "Move-Away" reset logic
                        if (isQrInFrame) {
                            framesWithoutQr++
                            if (framesWithoutQr > maxFramesWithoutQr) {
                                isQrInFrame = false
                                framesWithoutQr = 0
                                onQrCodeLost()
                            }
                        }
                    }
                }
                .addOnFailureListener {
                    Log.e("QrCodeAnalyzer", "Barcode scanning failed", it)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}

@Composable
fun UpiAppItem(appInfo: UpiAppInfo, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (isSelected) Color(0xFF4CAF50) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = appInfo.icon.toBitmap().asImageBitmap(),
                contentDescription = appInfo.appName,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(4.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = appInfo.appName,
            color = if (isSelected) Color(0xFF4CAF50) else Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

val SELECTION_MODE_KEY = stringPreferencesKey("selection_mode")
const val MODE_REMEMBER_LAST = "REMEMBER_LAST"
const val MODE_FIXED_DEFAULT = "FIXED_DEFAULT"

@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()
    
    // We fetch the apps again here strictly offline, no CameraX impact
    val upiApps = remember {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("upi://pay?pa=test@upi&pn=Test&am=1.00&cu=INR")
        }
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val resolveInfos: List<ResolveInfo> = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, flags)
        }
        
        resolveInfos.map {
            UpiAppInfo(
                packageName = it.activityInfo.packageName,
                appName = it.loadLabel(pm).toString(),
                icon = it.loadIcon(pm)
            )
        }.distinctBy { it.packageName }
    }

    var selectionMode by remember { mutableStateOf(MODE_REMEMBER_LAST) }
    var fixedDefaultPackage by remember { mutableStateOf<String?>(null) }
    
    val SELECTED_APP_KEY = stringPreferencesKey("selected_upi_app")

    // Load current settings
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        selectionMode = prefs[SELECTION_MODE_KEY] ?: MODE_REMEMBER_LAST
        fixedDefaultPackage = prefs[SELECTED_APP_KEY]
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp)
    ) {
        // App Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = "Settings",
                color = Color.White,
                fontSize = 24.sp,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text("App Selection Behavior", color = Color.Gray, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(16.dp))
        
        // Option 1: Remember Last
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    selectionMode = MODE_REMEMBER_LAST
                    scope.launch {
                        context.dataStore.edit { it[SELECTION_MODE_KEY] = MODE_REMEMBER_LAST }
                    }
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selectionMode == MODE_REMEMBER_LAST,
                onClick = null
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text("Remember Last Used", color = Color.White, fontSize = 16.sp)
                Text("App opens with whatever you used last time", color = Color.Gray, fontSize = 12.sp)
            }
        }
        
        // Option 2: Fixed Default
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    selectionMode = MODE_FIXED_DEFAULT
                    scope.launch {
                        context.dataStore.edit { it[SELECTION_MODE_KEY] = MODE_FIXED_DEFAULT }
                    }
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selectionMode == MODE_FIXED_DEFAULT,
                onClick = null
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text("Force Fixed Default", color = Color.White, fontSize = 16.sp)
                Text("Always launch specific app, regardless of last use", color = Color.Gray, fontSize = 12.sp)
            }
        }
        
        // Dropdown for fixed default
        if (selectionMode == MODE_FIXED_DEFAULT) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Select Fixed App:", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(start = 48.dp))
            Spacer(modifier = Modifier.height(8.dp))
            
            // Reusing UpiAppItem in a Row for selection
            LazyRow(
                contentPadding = PaddingValues(start = 48.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(upiApps) { appInfo ->
                    val isSelected = appInfo.packageName == fixedDefaultPackage
                    UpiAppItem(
                        appInfo = appInfo,
                        isSelected = isSelected,
                        onClick = {
                            fixedDefaultPackage = appInfo.packageName
                            scope.launch {
                                context.dataStore.edit { it[SELECTED_APP_KEY] = appInfo.packageName }
                            }
                        }
                    )
                }
            }
        }
    }
}
