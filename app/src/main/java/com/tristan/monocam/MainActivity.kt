package com.tristan.monocam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {
    private lateinit var cameraEngine: CameraEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        hideSystemStatusBar()

        cameraEngine = CameraEngine(this)

        setContent {
            MonoCamScreen(cameraEngine)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemStatusBar()
        }
    }

    private fun hideSystemStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // Use Volume Up / Volume Down as a tactile physical shutter button
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) || (keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cameraEngine.takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraEngine.release()
    }
}

private data class RotatedInsets(
    val start: Dp,
    val top: Dp,
    val end: Dp,
    val bottom: Dp,
)

private enum class ViewfinderDragMode { NONE, HORIZONTAL_LOOK, OPEN_SETTINGS, OPEN_MANUAL }

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun MonoCamScreen(engine: CameraEngine) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val state by engine.uiState.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val settingsProgress = remember { Animatable(0f) }
    val manualProgress = remember { Animatable(0f) }
    val isSettingsOpen by remember { derivedStateOf { settingsProgress.value > 0.005f } }
    val isManualOpen by remember { derivedStateOf { manualProgress.value > 0.005f } }
    val showOverlayCrosshair by remember {
        derivedStateOf { settingsProgress.value < 0.9f && manualProgress.value < 0.9f }
    }
    val showBottomControls by remember {
        derivedStateOf { settingsProgress.value < 0.96f && manualProgress.value < 0.96f }
    }
    var badgeText by remember { mutableStateOf<String?>(null) }
    var focusReticle by remember { mutableStateOf<Offset?>(null) }
    var focusReticleTrigger by remember { mutableIntStateOf(0) }
    val focusReticleAlpha = remember { Animatable(0f) }
    val showFocusReticle by remember { derivedStateOf { focusReticle != null && focusReticleAlpha.value > 0.005f } }
    val shutterFlashAlpha = remember { Animatable(0f) }
    val showShutterFlash by remember { derivedStateOf { shutterFlashAlpha.value > 0.002f } }

    val liquidOpenSpec = remember {
        spring(
            dampingRatio = 0.94f,
            stiffness = 300f,
            visibilityThreshold = 0.0005f
        )
    }
    val liquidCloseSpec = remember {
        spring(
            dampingRatio = 0.96f,
            stiffness = 360f,
            visibilityThreshold = 0.0005f
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    val lutPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            engine.importCustomLut(uri)
        }
    }

    BackHandler(enabled = isSettingsOpen || isManualOpen) {
        scope.launch {
            if (isSettingsOpen) settingsProgress.animateTo(0f, liquidCloseSpec)
            if (isManualOpen) manualProgress.animateTo(0f, liquidCloseSpec)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(focusReticleTrigger) {
        if (focusReticleTrigger > 0) {
            focusReticleAlpha.snapTo(1f)
            delay(850.milliseconds)
            focusReticleAlpha.animateTo(0f, animationSpec = tween(durationMillis = 260))
            focusReticle = null
        }
    }

    LaunchedEffect(state.captureTrigger) {
        if (state.captureTrigger > 0) {
            shutterFlashAlpha.snapTo(0.32f)
            shutterFlashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 160))
        }
    }

    LaunchedEffect(state.lookChangeTrigger) {
        if (state.lookChangeTrigger > 0) {
            badgeText = state.activeLook.label
            delay(1300.milliseconds)
            badgeText = null
        }
    }

    LaunchedEffect(state.statusMessage, state.captureTrigger) {
        val isFeedback = state.statusMessage.startsWith("Saved") ||
            state.statusMessage.startsWith("Added") ||
            state.statusMessage.startsWith("Invalid") ||
            state.statusMessage.startsWith("Failed")
        if (isFeedback) {
            badgeText = state.statusMessage.uppercase()
            delay(1500.milliseconds)
            badgeText = null
        }
    }

    val swipeThresholdPx = with(density) { 48.dp.toPx() }
    val touchSlopPx = with(density) { 10.dp.toPx() }
    val settingsDragRangePx = with(density) { 300.dp.toPx() }
    val manualDragRangePx = with(density) { 220.dp.toPx() }
    val currentOrientationDeg by rememberUpdatedState(state.deviceOrientationDeg)

    // Smooth shortest-path rotation for orientation-adaptive overlays and badges
    val isLandscape = (state.deviceOrientationDeg == 90) || (state.deviceOrientationDeg == 270)
    var rotationTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.deviceOrientationDeg) {
        val desired = when (state.deviceOrientationDeg) {
            90 -> -90f
            270 -> 90f
            180 -> 180f
            else -> 0f
        }
        var diff = (desired - rotationTarget) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        rotationTarget += diff
    }
    val animatedRotation = animateFloatAsState(
        targetValue = rotationTarget,
        animationSpec = spring(
            dampingRatio = 0.86f,
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = 0.05f
        ),
        label = "settingsOrientationRotation"
    )

    // Rotate safe-drawing + display-cutout insets accurately for each orientation
    val safeAndCutoutInsets = WindowInsets.safeDrawing.union(WindowInsets.displayCutout)
    val rawLeft = with(density) { safeAndCutoutInsets.getLeft(density, layoutDirection).toDp() }
    val rawTop = with(density) { safeAndCutoutInsets.getTop(density).toDp() }
    val rawRight = with(density) { safeAndCutoutInsets.getRight(density, layoutDirection).toDp() }
    val rawBottom = with(density) { safeAndCutoutInsets.getBottom(density).toDp() }

    val settingsInsets = when (state.deviceOrientationDeg) {
        90 -> RotatedInsets(start = rawBottom, top = rawLeft, end = rawTop, bottom = rawRight)
        180 -> RotatedInsets(start = rawRight, top = rawBottom, end = rawLeft, bottom = rawTop)
        270 -> RotatedInsets(start = rawTop, top = rawRight, end = rawBottom, bottom = rawLeft)
        else -> RotatedInsets(start = rawLeft, top = rawTop, end = rawRight, bottom = rawBottom)
    }

    val activeLens = state.lenses.getOrNull(state.activeLensIndex)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight

        val targetFrameWidth: Dp
        val targetFrameHeight: Dp
        if (state.aspectRatio == AspectRatioOption.FULL) {
            targetFrameWidth = screenWidth
            targetFrameHeight = screenHeight
        } else {
            val r = state.aspectRatio.fixedRatio ?: (screenHeight / screenWidth)
            targetFrameWidth = minOf(screenWidth, screenHeight / r)
            targetFrameHeight = targetFrameWidth * r
        }

        val frameWidthState = animateDpAsState(
            targetValue = targetFrameWidth,
            animationSpec = spring(dampingRatio = 0.92f, stiffness = 340f),
            label = "frameWidth"
        )
        val frameHeightState = animateDpAsState(
            targetValue = targetFrameHeight,
            animationSpec = spring(dampingRatio = 0.92f, stiffness = 340f),
            label = "frameHeight"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(hasPermission, isSettingsOpen, isManualOpen, state.isManualMode) {
                    detectTapGestures { offset ->
                        if (!hasPermission || isSettingsOpen) return@detectTapGestures
                        if (isManualOpen) {
                            scope.launch { manualProgress.animateTo(0f, liquidCloseSpec) }
                            return@detectTapGestures
                        }
                        if (!state.isManualMode) {
                            val fw = with(density) { frameWidthState.value.toPx() }
                            val fh = with(density) { frameHeightState.value.toPx() }
                            val localX = offset.x - (size.width - fw) / 2f
                            val localY = offset.y - (size.height - fh) / 2f
                            if (localX in 0f..fw && localY in 0f..fh) {
                                focusReticle = offset
                                focusReticleTrigger += 1
                                engine.focusAndMeterAt(localX, localY, fw.roundToInt(), fh.roundToInt())
                            }
                        }
                    }
                }
                .pointerInput(swipeThresholdPx, touchSlopPx, settingsDragRangePx, manualDragRangePx) {
                    var rawSumX = 0f
                    var rawSumY = 0f
                    var lastUserDy = 0f
                    var lookSwitched = false
                    var mode = ViewfinderDragMode.NONE

                    detectDragGestures(
                        onDragStart = {
                            rawSumX = 0f
                            rawSumY = 0f
                            lastUserDy = 0f
                            lookSwitched = false
                            mode = ViewfinderDragMode.NONE
                        },
                        onDrag = { _, dragAmount ->
                            val sheetBusy = settingsProgress.value > 0.02f || manualProgress.value > 0.02f
                            if (sheetBusy && mode != ViewfinderDragMode.OPEN_SETTINGS && mode != ViewfinderDragMode.OPEN_MANUAL) {
                                return@detectDragGestures
                            }

                            rawSumX += dragAmount.x
                            rawSumY += dragAmount.y

                            val (userSumX, userSumY) = rotateDelta(rawSumX, rawSumY, currentOrientationDeg)
                            val (_, userStepY) = rotateDelta(dragAmount.x, dragAmount.y, currentOrientationDeg)
                            lastUserDy = userStepY

                            if (mode == ViewfinderDragMode.NONE) {
                                if (abs(userSumX) > touchSlopPx || abs(userSumY) > touchSlopPx) {
                                    mode = when {
                                        abs(userSumX) >= abs(userSumY) -> ViewfinderDragMode.HORIZONTAL_LOOK
                                        userSumY > 0f -> ViewfinderDragMode.OPEN_SETTINGS
                                        else -> ViewfinderDragMode.OPEN_MANUAL
                                    }
                                }
                            }

                            when (mode) {
                                ViewfinderDragMode.HORIZONTAL_LOOK -> {
                                    if (!lookSwitched && abs(userSumX) >= swipeThresholdPx) {
                                        lookSwitched = true
                                        view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
                                        engine.cycleLook(if (userSumX < 0f) 1 else -1)
                                    }
                                }
                                ViewfinderDragMode.OPEN_SETTINGS -> {
                                    // Liquid drag-to-open from top with NO haptic feedback
                                    val p = (userSumY / settingsDragRangePx).coerceIn(0f, 1f)
                                    scope.launch(start = CoroutineStart.UNDISPATCHED) { settingsProgress.snapTo(p) }
                                }
                                ViewfinderDragMode.OPEN_MANUAL -> {
                                    // Liquid drag-to-open manual sheet from bottom with NO haptic feedback
                                    val p = (-userSumY / manualDragRangePx).coerceIn(0f, 1f)
                                    scope.launch(start = CoroutineStart.UNDISPATCHED) { manualProgress.snapTo(p) }
                                }
                                ViewfinderDragMode.NONE -> Unit
                            }
                        },
                        onDragEnd = {
                            when (mode) {
                                ViewfinderDragMode.OPEN_SETTINGS -> {
                                    val shouldOpen = settingsProgress.value >= 0.16f ||
                                        (settingsProgress.value > 0.04f && lastUserDy > 6f)
                                    scope.launch {
                                        if (shouldOpen) {
                                            settingsProgress.animateTo(1f, liquidOpenSpec)
                                        } else {
                                            settingsProgress.animateTo(0f, liquidCloseSpec)
                                        }
                                    }
                                }
                                ViewfinderDragMode.OPEN_MANUAL -> {
                                    val shouldOpen = manualProgress.value >= 0.16f ||
                                        (manualProgress.value > 0.04f && lastUserDy < -6f)
                                    scope.launch {
                                        if (shouldOpen) {
                                            manualProgress.animateTo(1f, liquidOpenSpec)
                                        } else {
                                            manualProgress.animateTo(0f, liquidCloseSpec)
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        },
                        onDragCancel = {
                            if (mode == ViewfinderDragMode.OPEN_SETTINGS) {
                                scope.launch { settingsProgress.animateTo(0f, liquidCloseSpec) }
                            } else if (mode == ViewfinderDragMode.OPEN_MANUAL) {
                                scope.launch { manualProgress.animateTo(0f, liquidCloseSpec) }
                            }
                        }
                    )
                }
        ) {

        // 1. Live camera viewfinder cropped cleanly to the selected aspect ratio
        if (hasPermission) {
            // Pre-build look shader + 10 smooth blur chain steps once per look/grain (0 drag-time allocations)
            val blurEffects = remember(state.activeLook, state.filmGrain) {
                val baseEffect = FujiFilter.createRenderEffect(state.activeLook, state.filmGrain)
                listOf(0f, 3f, 7f, 12f, 17f, 23f, 29f, 35f, 40f, 44f).map { radius ->
                    if (radius <= 0f) {
                        baseEffect
                    } else {
                        val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                        RenderEffect.createChainEffect(blur, baseEffect)
                    }
                }
            }
            var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
            var lastAppliedConfig by remember { mutableStateOf(Triple(0, 0, -1)) }
            val currentPreviewWidth by rememberUpdatedState(state.previewWidth)
            val currentPreviewHeight by rememberUpdatedState(state.previewHeight)
            val currentSensorOrientation by rememberUpdatedState(state.sensorOrientation)
            val transformMatrix = remember { Matrix() }

            LaunchedEffect(textureViewRef, blurEffects) {
                val tv = textureViewRef ?: return@LaunchedEffect
                snapshotFlow {
                    (settingsProgress.value * (blurEffects.size - 1))
                        .roundToInt()
                        .coerceIn(0, blurEffects.lastIndex)
                }
                    .distinctUntilChanged()
                    .collect { step ->
                        tv.setRenderEffect(blurEffects[step])
                    }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .layout { measurable, constraints ->
                        val wPx = frameWidthState.value.roundToPx().coerceIn(1, constraints.maxWidth)
                        val hPx = frameHeightState.value.roundToPx().coerceIn(1, constraints.maxHeight)
                        val placeable = measurable.measure(Constraints.fixed(wPx, hPx))
                        layout(wPx, hPx) { placeable.place(0, 0) }
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            textureViewRef = this
                            setRenderEffect(blurEffects[0])
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                private var attachedSurface: Surface? = null

                                override fun onSurfaceTextureAvailable(
                                    surfaceTexture: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    val pw = currentPreviewWidth
                                    val ph = currentPreviewHeight
                                    val so = currentSensorOrientation
                                    surfaceTexture.setDefaultBufferSize(pw, ph)
                                    applyCenterCropTransform(
                                        view = this@apply,
                                        viewWidth = width,
                                        viewHeight = height,
                                        bufferWidth = pw,
                                        bufferHeight = ph,
                                        sensorOrientation = so,
                                        reusableMatrix = transformMatrix
                                    )
                                    lastAppliedConfig = Triple(pw, ph, so)
                                    val surface = Surface(surfaceTexture)
                                    attachedSurface = surface
                                    engine.attachPreviewSurface(surface)
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surfaceTexture: SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    applyCenterCropTransform(
                                        view = this@apply,
                                        viewWidth = width,
                                        viewHeight = height,
                                        bufferWidth = currentPreviewWidth,
                                        bufferHeight = currentPreviewHeight,
                                        sensorOrientation = currentSensorOrientation,
                                        reusableMatrix = transformMatrix
                                    )
                                }

                                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                    attachedSurface?.release()
                                    attachedSurface = null
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                            }
                        }
                    },
                    update = { textureView ->
                        textureViewRef = textureView
                        val cfg = Triple(state.previewWidth, state.previewHeight, state.sensorOrientation)
                        if (cfg != lastAppliedConfig) {
                            lastAppliedConfig = cfg
                            textureView.surfaceTexture?.setDefaultBufferSize(state.previewWidth, state.previewHeight)
                            if (textureView.width > 0 && textureView.height > 0) {
                                applyCenterCropTransform(
                                    view = textureView,
                                    viewWidth = textureView.width,
                                    viewHeight = textureView.height,
                                    bufferWidth = state.previewWidth,
                                    bufferHeight = state.previewHeight,
                                    sensorOrientation = state.sensorOrientation,
                                    reusableMatrix = transformMatrix
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Subtle viewfinder center crosshair that doubles as a horizon & flat-lay leveler
            if (showOverlayCrosshair) {
                CenterLevelCrosshair(
                    deviceOrientationDeg = state.deviceOrientationDeg,
                    rotationProvider = { animatedRotation.value },
                    alphaProvider = {
                        val fade = maxOf(settingsProgress.value, manualProgress.value)
                        (1f - fade * 1.4f).coerceIn(0f, 1f)
                    },
                    hapticEnabled = !isSettingsOpen && !isManualOpen,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Minimal tap-to-focus reticle with smooth fade-out
            if (showFocusReticle && !isSettingsOpen && !isManualOpen) {
                focusReticle?.let { target ->
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = focusReticleAlpha.value }
                    ) {
                        val boxSize = 54.dp.toPx()
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.85f),
                            topLeft = Offset(target.x - boxSize / 2f, target.y - boxSize / 2f),
                            size = Size(boxSize, boxSize),
                            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                            style = Stroke(width = 1.4.dp.toPx())
                        )
                    }
                }
            }

            // Brief shutter flash feedback (zero recompositions during fade)
            if (showShutterFlash) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = shutterFlashAlpha.value }
                        .background(Color.White)
                )
            }
        } else {
            // Minimal permission prompt
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MonoText(
                    text = "CAMERA ACCESS REQUIRED",
                    color = Color.White,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(50))
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    MonoText(
                        text = "GRANT ACCESS",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        val panelWidth = if (isLandscape) screenHeight else screenWidth
        val panelHeight = if (isLandscape) screenWidth else screenHeight
        val panelHeightPx = with(density) { panelHeight.toPx() }

        // Transient pill for look swipe / capture confirmation & live vintage date stamp preview
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .requiredSize(panelWidth, panelHeight)
                .graphicsLayer { rotationZ = animatedRotation.value }
        ) {
            AnimatedVisibility(
                visible = badgeText != null && !isSettingsOpen,
                enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
                    scaleIn(initialScale = 0.92f, animationSpec = spring(stiffness = Spring.StiffnessMedium)),
                exit = fadeOut(tween(260)) +
                    scaleOut(targetScale = 0.94f, animationSpec = tween(260)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        top = maxOf(settingsInsets.top, 20.dp) + 12.dp,
                        start = settingsInsets.start,
                        end = settingsInsets.end
                    )
            ) {
                MonoText(
                    text = badgeText.orEmpty(),
                    color = Color.White,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            if (hasPermission && state.dateStamp && showBottomControls) {
                val stampText = remember(state.captureTrigger) { VintageDateStamp.formatCurrentDate() }
                val digitHeightPx = with(density) { 17.dp.toPx() }
                val stampWidthDp = with(density) {
                    (VintageDateStamp.measureWidth(stampText, digitHeightPx) + digitHeightPx * 0.14f).toDp()
                }
                val visualFrameW = if (isLandscape) targetFrameHeight else targetFrameWidth
                val visualFrameH = if (isLandscape) targetFrameWidth else targetFrameHeight
                val frameEndMargin = ((panelWidth - visualFrameW) / 2f).coerceAtLeast(0.dp)
                val frameBottomMargin = ((panelHeight - visualFrameH) / 2f).coerceAtLeast(0.dp)

                val stampBottomClear = maxOf(
                    frameBottomMargin + 14.dp,
                    if (state.deviceOrientationDeg == 0) maxOf(settingsInsets.bottom, 16.dp) + 162.dp
                    else maxOf(settingsInsets.bottom, 20.dp)
                )
                val stampEndClear = maxOf(
                    frameEndMargin + 14.dp,
                    if (state.deviceOrientationDeg == 90) maxOf(settingsInsets.end, 16.dp) + 152.dp
                    else maxOf(settingsInsets.end, 20.dp)
                )

                Canvas(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = stampEndClear, bottom = stampBottomClear)
                        .size(width = stampWidthDp, height = 17.dp)
                        .graphicsLayer {
                            val fade = maxOf(settingsProgress.value, manualProgress.value)
                            alpha = (1f - fade * 1.8f).coerceIn(0f, 1f)
                        }
                ) {
                    drawIntoCanvas { canvas ->
                        VintageDateStamp.drawAt(
                            canvas = canvas.nativeCanvas,
                            text = stampText,
                            left = 0f,
                            top = 0f,
                            digitHeight = digitHeightPx
                        )
                    }
                }
            }
        }

        // 2. Fixed bottom controls: Minimal ratio pill + Lens switcher + capture button + RAW toggle
        if (hasPermission && showBottomControls) {
            val ratioGap by animateDpAsState(
                targetValue = if (isLandscape) 30.dp else 12.dp,
                animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                label = "ratioButtonGap"
            )
            val controlsRowGap by animateDpAsState(
                targetValue = if (isLandscape) 24.dp else 18.dp,
                animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                label = "controlsRowGap"
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp)
                    .graphicsLayer {
                        val fade = maxOf(settingsProgress.value, manualProgress.value)
                        alpha = (1f - fade * 1.8f).coerceIn(0f, 1f)
                        val s = 1f - fade * 0.12f
                        scaleX = s
                        scaleY = s
                    }
            ) {
                MinimalRatioButton(
                    label = state.aspectRatio.label,
                    active = state.aspectRatio != AspectRatioOption.FULL,
                    rotationProvider = { animatedRotation.value },
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        engine.cycleAspectRatio()
                    }
                )

                Spacer(modifier = Modifier.height(ratioGap))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(controlsRowGap)
                ) {
                    MinimalLensButton(
                        label = activeLens?.label ?: "1×",
                        enabled = state.lenses.size > 1,
                        rotationProvider = { animatedRotation.value },
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            engine.cycleLens()
                        }
                    )

                    MinimalCaptureButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            engine.takePhoto()
                        }
                    )

                    MinimalRawButton(
                        active = state.captureRaw,
                        enabled = activeLens?.supportsRaw == true,
                        rotationProvider = { animatedRotation.value },
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            engine.updateManualSettings(raw = !state.captureRaw)
                        }
                    )
                }
            }
        }

        // 3. Orientation-adaptive compact frosted manual controls sheet (slides up from bottom)
        if (isManualOpen) {
            var manualSheetHeightPx by remember {
                mutableFloatStateOf(with(density) { (if (isLandscape) 190.dp else 360.dp).toPx() })
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .requiredSize(panelWidth, panelHeight)
                    .graphicsLayer { rotationZ = animatedRotation.value }
            ) {
                // Tap above the compact sheet to dismiss
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            scope.launch { manualProgress.animateTo(0f, liquidCloseSpec) }
                        }
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { size ->
                            if (size.height > 0) manualSheetHeightPx = size.height.toFloat()
                        }
                        .graphicsLayer {
                            val p = manualProgress.value.coerceIn(0f, 1f)
                            translationY = (1f - p) * manualSheetHeightPx
                            alpha = (p * 1.4f).coerceIn(0f, 1f)
                        }
                ) {
                    FrostedManualOverlay(
                        state = state,
                        isLandscape = isLandscape,
                        insets = settingsInsets,
                        dragRangePx = manualDragRangePx,
                        onDragProgressDelta = { deltaFraction ->
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                manualProgress.snapTo((manualProgress.value + deltaFraction).coerceIn(0f, 1f))
                            }
                        },
                        onReleaseProgress = { velocityY ->
                            val shouldClose = manualProgress.value <= 0.84f || velocityY > 700f
                            scope.launch {
                                if (shouldClose) {
                                    manualProgress.animateTo(0f, liquidCloseSpec)
                                } else {
                                    manualProgress.animateTo(1f, liquidOpenSpec)
                                }
                            }
                        },
                        onCloseClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            scope.launch { manualProgress.animateTo(0f, liquidCloseSpec) }
                        },
                        onToggleManual = { manual ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            engine.updateManualSettings(isManual = manual)
                        },
                        onIsoChange = { iso -> engine.updateManualSettings(isManual = true, iso = iso) },
                        onShutterChange = { ns -> engine.updateManualSettings(isManual = true, shutterNs = ns) },
                        onFocusChange = { focus -> engine.updateManualSettings(isManual = true, focus = focus) }
                    )
                }
            }
        }

        // 4. Orientation-adaptive full-screen frosted glass settings (liquid slide from top)
        if (isSettingsOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .requiredSize(panelWidth, panelHeight)
                    .graphicsLayer { rotationZ = animatedRotation.value }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = settingsProgress.value.coerceIn(0f, 1f)
                            translationY = -(1f - p) * panelHeightPx
                            alpha = (p * 1.25f).coerceIn(0f, 1f)
                        }
                ) {
                    FrostedSettingsOverlay(
                        state = state,
                        isLandscape = isLandscape,
                        insets = settingsInsets,
                        dragRangePx = settingsDragRangePx,
                        onDragProgressDelta = { deltaFraction ->
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                settingsProgress.snapTo((settingsProgress.value + deltaFraction).coerceIn(0f, 1f))
                            }
                        },
                        onReleaseProgress = { velocityY ->
                            val shouldClose = settingsProgress.value <= 0.84f || velocityY < -700f
                            scope.launch {
                                if (shouldClose) {
                                    settingsProgress.animateTo(0f, liquidCloseSpec)
                                } else {
                                    settingsProgress.animateTo(1f, liquidOpenSpec)
                                }
                            }
                        },
                        onCloseClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            scope.launch { settingsProgress.animateTo(0f, liquidCloseSpec) }
                        },
                        onSelectLook = { lookId ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            engine.selectLook(lookId)
                        },
                        onAddCustomLut = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            lutPickerLauncher.launch(arrayOf("*/*"))
                        },
                        onRemoveCustomLut = { lookId ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            engine.removeCustomLut(lookId)
                        },
                        onToggleGrain = { grain ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            engine.updateFilmGrain(grain)
                        },
                        onToggleDateStamp = { enabled ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            engine.updateDateStamp(enabled)
                        },
                        onSelectAspectRatio = { ratio ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            engine.selectAspectRatio(ratio)
                        },
                        onSelectLens = { idx ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            engine.selectLens(idx)
                        },
                        onToggleRaw = { raw ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            engine.updateManualSettings(raw = raw)
                        },
                        onToggleManual = { manual ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            engine.updateManualSettings(isManual = manual)
                        },
                        onIsoChange = { iso -> engine.updateManualSettings(iso = iso) },
                        onShutterChange = { ns -> engine.updateManualSettings(shutterNs = ns) },
                        onFocusChange = { focus -> engine.updateManualSettings(focus = focus) }
                    )
                }
            }
        }
        }
    }
}

private fun rotateDelta(dx: Float, dy: Float, orientationDeg: Int): Pair<Float, Float> {
    return when (orientationDeg) {
        90 -> -dy to dx
        180 -> -dx to -dy
        270 -> dy to -dx
        else -> dx to dy
    }
}

@Composable
private fun MinimalCaptureButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMedium,
            visibilityThreshold = 0.001f
        ),
        label = "shutterScale"
    )

    Box(
        modifier = modifier
            .size(68.dp)
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .background(Color.White)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = "Capture photo" }
    )
}

@Composable
private fun MinimalLensButton(
    label: String,
    enabled: Boolean,
    rotationProvider: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.001f),
        label = "lensPressScale"
    )
    val backgroundColor = if (enabled) Color.Black.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.25f)
    val borderColor = if (enabled) Color.White.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.14f)
    val textColor = if (enabled) Color.White.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.35f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                rotationZ = rotationProvider()
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .width(62.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = "Switch lens ($label)" }
    ) {
        MonoText(
            text = label,
            color = textColor,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
    }
}

@Composable
private fun MinimalRatioButton(
    label: String,
    active: Boolean,
    rotationProvider: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.001f),
        label = "ratioPressScale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) Color.White.copy(alpha = 0.52f) else Color.White.copy(alpha = 0.28f),
        animationSpec = tween(durationMillis = 160),
        label = "ratioBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (active) Color.White else Color.White.copy(alpha = 0.84f),
        animationSpec = tween(durationMillis = 160),
        label = "ratioText"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                rotationZ = rotationProvider()
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .width(52.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.42f))
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = "Aspect ratio ($label)" }
    ) {
        MonoText(
            text = label,
            color = textColor,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
    }
}

@Composable
private fun MinimalRawButton(
    active: Boolean,
    enabled: Boolean,
    rotationProvider: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.001f),
        label = "rawPressScale"
    )
    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Black.copy(alpha = 0.25f)
            active -> Color.White
            else -> Color.Black.copy(alpha = 0.45f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "rawBg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.White.copy(alpha = 0.14f)
            active -> Color.White
            else -> Color.White.copy(alpha = 0.34f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "rawBorder"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.White.copy(alpha = 0.30f)
            active -> Color.Black
            else -> Color.White.copy(alpha = 0.86f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "rawText"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                rotationZ = rotationProvider()
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .width(62.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = if (active) "Disable RAW" else "Enable RAW" }
    ) {
        MonoText(
            text = "RAW",
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun LumaBrandSymbol(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val tone = Color(0xFFF5F3EE)

        // Outer simple ring + center solid circle + warm film tally dot
        drawCircle(
            color = tone,
            radius = w * 0.37f,
            center = Offset(w / 2f, h / 2f),
            style = Stroke(width = 1.6.dp.toPx())
        )
        drawCircle(
            color = tone,
            radius = w * 0.16f,
            center = Offset(w / 2f, h / 2f)
        )
        drawCircle(
            color = Color(0xFFE85D3F),
            radius = w * 0.065f,
            center = Offset(w * 0.86f, h * 0.14f)
        )
    }
}

@Composable
private fun SettingsClosePill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.001f),
        label = "closePressScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        MonoText(
            text = "CLOSE",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun FrostedSettingsOverlay(
    state: CameraUiState,
    isLandscape: Boolean,
    insets: RotatedInsets,
    dragRangePx: Float,
    onDragProgressDelta: (Float) -> Unit,
    onReleaseProgress: (Float) -> Unit,
    onCloseClick: () -> Unit,
    onSelectLook: (String) -> Unit,
    onAddCustomLut: () -> Unit,
    onRemoveCustomLut: (String) -> Unit,
    onToggleGrain: (Boolean) -> Unit,
    onToggleDateStamp: (Boolean) -> Unit,
    onSelectAspectRatio: (AspectRatioOption) -> Unit,
    onSelectLens: (Int) -> Unit,
    onToggleRaw: (Boolean) -> Unit,
    onToggleManual: (Boolean) -> Unit,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Long) -> Unit,
    onFocusChange: (Float) -> Unit
) {
    val dividerColor = Color.White.copy(alpha = 0.16f)
    var isDraggingOverlay by remember { mutableStateOf(false) }

    // Continuous liquid swipe-up-from-anywhere to close (zero haptic feedback)
    val swipeUpNestedScroll = remember(dragRangePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isDraggingOverlay && available.y > 0f) {
                    onDragProgressDelta(available.y / dragRangePx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0f) {
                    isDraggingOverlay = true
                    onDragProgressDelta(available.y / dragRangePx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (isDraggingOverlay) {
                    isDraggingOverlay = false
                    onReleaseProgress(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .nestedScroll(swipeUpNestedScroll)
            .pointerInput(dragRangePx) {
                var lastDragY = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        lastDragY = 0f
                    },
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount < 0f || isDraggingOverlay) {
                            isDraggingOverlay = true
                            lastDragY = dragAmount
                            onDragProgressDelta(dragAmount / dragRangePx)
                        }
                    },
                    onDragEnd = {
                        if (isDraggingOverlay) {
                            isDraggingOverlay = false
                            val syntheticVelocity = if (lastDragY < -6f) -1000f else 0f
                            onReleaseProgress(syntheticVelocity)
                        }
                    },
                    onDragCancel = {
                        if (isDraggingOverlay) {
                            isDraggingOverlay = false
                            onReleaseProgress(0f)
                        }
                    }
                )
            }
            .padding(
                start = insets.start,
                top = insets.top,
                end = insets.end,
                bottom = insets.bottom
            )
    ) {
        // Header (in portrait, CLOSE moves to the bottom thumb zone)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = if (isLandscape) 10.dp else 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LumaBrandSymbol()
                MonoText(
                    text = "LUMACAM  •  SETTINGS",
                    color = Color.White.copy(alpha = 0.80f),
                    fontSize = 11.sp,
                    letterSpacing = 1.6.sp
                )
            }

            if (isLandscape) {
                SettingsClosePill(onClick = onCloseClick)
            }
        }

        HorizontalDivider(color = dividerColor, thickness = 1.dp)

        if (isLandscape) {
            // Balanced 3-column horizontal layout so all controls fit cleanly without scrolling
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.08f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    LookSection(state, onSelectLook, onAddCustomLut, onRemoveCustomLut, compact = true)
                    if (state.lenses.isNotEmpty()) {
                        HorizontalDivider(color = dividerColor, thickness = 1.dp)
                        LensSection(state, onSelectLens, compact = true)
                    }
                }

                VerticalDivider(color = dividerColor, thickness = 1.dp)

                Column(
                    modifier = Modifier
                        .weight(0.98f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    RatioSection(state, onSelectAspectRatio, compact = true)
                    HorizontalDivider(color = dividerColor, thickness = 1.dp)
                    FormatSection(state, onToggleRaw, compact = true)
                    HorizontalDivider(color = dividerColor, thickness = 1.dp)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f)) {
                            GrainSection(state, onToggleGrain, compact = true)
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            DateStampSection(state, onToggleDateStamp, compact = true)
                        }
                    }
                }

                VerticalDivider(color = dividerColor, thickness = 1.dp)

                Column(
                    modifier = Modifier
                        .weight(0.94f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    ModeSection(state, onToggleManual, compact = true)
                    if (state.isManualMode) {
                        HorizontalDivider(color = dividerColor, thickness = 1.dp)
                        ExposureFocusSection(state, onIsoChange, onShutterChange, onFocusChange, compact = true)
                    }
                }
            }

            // Bottom handle indicator
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.40f))
                )
            }
        } else {
            // Portrait: comfortable lower-mid thumb spacing so controls aren't crammed at the very bottom
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 20.dp, bottom = 32.dp)
                ) {
                    ModeSection(state, onToggleManual)
                    if (state.isManualMode) {
                        HorizontalDivider(color = dividerColor, thickness = 1.dp)
                        ExposureFocusSection(state, onIsoChange, onShutterChange, onFocusChange)
                    }
                    HorizontalDivider(color = dividerColor, thickness = 1.dp)
                    RatioSection(state, onSelectAspectRatio)
                    HorizontalDivider(color = dividerColor, thickness = 1.dp)
                    FormatSection(state, onToggleRaw)
                    if (state.lenses.isNotEmpty()) {
                        HorizontalDivider(color = dividerColor, thickness = 1.dp)
                        LensSection(state, onSelectLens)
                    }
                    HorizontalDivider(color = dividerColor, thickness = 1.dp)
                    DateStampSection(state, onToggleDateStamp)
                    HorizontalDivider(color = dividerColor, thickness = 1.dp)
                    GrainSection(state, onToggleGrain)
                    HorizontalDivider(color = dividerColor, thickness = 1.dp)
                    LookSection(state, onSelectLook, onAddCustomLut, onRemoveCustomLut)
                }
            }

            HorizontalDivider(color = dividerColor, thickness = 1.dp)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 18.dp)
            ) {
                SettingsClosePill(onClick = onCloseClick)
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.40f))
                )
            }
        }
    }
}

@Composable
private fun LookSection(
    state: CameraUiState,
    onSelectLook: (String) -> Unit,
    onAddCustomLut: () -> Unit,
    onRemoveCustomLut: (String) -> Unit,
    compact: Boolean = false
) {
    SettingsSection(title = "LOOK (SWIPE LEFT / RIGHT)", compact = compact) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
        ) {
            state.looks.forEach { look ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SettingChoice(
                        label = look.label,
                        selected = state.activeLookId == look.id,
                        compact = compact,
                        onClick = { onSelectLook(look.id) }
                    )
                    if (look is CameraLook.CustomLut) {
                        MonoText(
                            text = "×",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onRemoveCustomLut(look.id) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.34f), RoundedCornerShape(6.dp))
                    .clickable(onClick = onAddCustomLut)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                MonoText(
                    text = "+ ADD .CUBE",
                    color = Color.White.copy(alpha = 0.90f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun RatioSection(
    state: CameraUiState,
    onSelectAspectRatio: (AspectRatioOption) -> Unit,
    compact: Boolean = false
) {
    SettingsSection(title = "RATIO", compact = compact) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AspectRatioOption.entries.forEach { ratio ->
                SettingChoice(
                    label = ratio.label,
                    selected = state.aspectRatio == ratio,
                    compact = compact,
                    onClick = { onSelectAspectRatio(ratio) }
                )
            }
        }
    }
}

@Composable
private fun GrainSection(
    state: CameraUiState,
    onToggleGrain: (Boolean) -> Unit,
    compact: Boolean = false
) {
    SettingsSection(title = "GRAIN", compact = compact) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 28.dp)
        ) {
            SettingChoice(
                label = "OFF",
                selected = !state.filmGrain,
                compact = compact,
                onClick = { onToggleGrain(false) }
            )
            SettingChoice(
                label = "ON",
                selected = state.filmGrain,
                compact = compact,
                onClick = { onToggleGrain(true) }
            )
        }
    }
}

@Composable
private fun DateStampSection(
    state: CameraUiState,
    onToggleDateStamp: (Boolean) -> Unit,
    compact: Boolean = false
) {
    SettingsSection(title = "DATE STAMP", compact = compact) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 28.dp)
        ) {
            SettingChoice(
                label = "OFF",
                selected = !state.dateStamp,
                compact = compact,
                onClick = { onToggleDateStamp(false) }
            )
            SettingChoice(
                label = "ON",
                selected = state.dateStamp,
                compact = compact,
                onClick = { onToggleDateStamp(true) }
            )
        }
    }
}

@Composable
private fun FormatSection(
    state: CameraUiState,
    onToggleRaw: (Boolean) -> Unit,
    compact: Boolean = false
) {
    val activeLens = state.lenses.getOrNull(state.activeLensIndex)
    SettingsSection(title = "FORMAT", compact = compact) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 28.dp)
        ) {
            SettingChoice(
                label = "JPG",
                selected = !state.captureRaw,
                compact = compact,
                onClick = { onToggleRaw(false) }
            )
            SettingChoice(
                label = "RAW + JPG",
                selected = state.captureRaw,
                enabled = activeLens?.supportsRaw == true,
                compact = compact,
                onClick = { onToggleRaw(true) }
            )
        }
    }
}

@Composable
private fun LensSection(
    state: CameraUiState,
    onSelectLens: (Int) -> Unit,
    compact: Boolean = false
) {
    if (state.lenses.isEmpty()) return
    SettingsSection(title = "LENS", compact = compact) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
        ) {
            state.lenses.forEachIndexed { index, lens ->
                SettingChoice(
                    label = lens.label,
                    selected = index == state.activeLensIndex,
                    compact = compact,
                    onClick = { onSelectLens(index) }
                )
            }
        }
    }
}

@Composable
private fun ModeSection(
    state: CameraUiState,
    onToggleManual: (Boolean) -> Unit,
    compact: Boolean = false
) {
    SettingsSection(title = "MODE", compact = compact) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 28.dp)
        ) {
            SettingChoice(
                label = "AUTO",
                selected = !state.isManualMode,
                compact = compact,
                onClick = { onToggleManual(false) }
            )
            SettingChoice(
                label = "MANUAL",
                selected = state.isManualMode,
                compact = compact,
                onClick = { onToggleManual(true) }
            )
        }
    }
}

@Composable
private fun ExposureFocusSection(
    state: CameraUiState,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Long) -> Unit,
    onFocusChange: (Float) -> Unit,
    compact: Boolean = false
) {
    val activeLens = state.lenses.getOrNull(state.activeLensIndex) ?: return
    val view = LocalView.current

    SettingsSection(title = "EXPOSURE & FOCUS", compact = compact) {
        val minIso = activeLens.isoRange.lower.toFloat()
        val maxIso = activeLens.isoRange.upper.coerceAtMost(6400).toFloat()
        var lastIsoBucket by remember { mutableIntStateOf(state.iso / 50) }

        ManualSliderRow(
            label = "ISO",
            valueText = state.iso.toString(),
            value = state.iso.toFloat().coerceIn(minIso, maxIso),
            valueRange = minIso..maxIso,
            onValueChange = { rawVal ->
                val newIso = rawVal.roundToInt()
                val bucket = newIso / 50
                if (bucket != lastIsoBucket) {
                    lastIsoBucket = bucket
                    view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                }
                onIsoChange(newIso)
            }
        )

        if (!compact) Spacer(modifier = Modifier.height(6.dp))

        val shutterFraction = (1_000_000_000.0 / state.shutterSpeedNs).roundToInt().coerceIn(4, 2000)
        var lastShutterBucket by remember { mutableIntStateOf(shutterFraction / 40) }
        ManualSliderRow(
            label = "SHUTTER",
            valueText = "1/${shutterFraction}s",
            value = shutterFraction.toFloat(),
            valueRange = 4f..2000f,
            onValueChange = { frac ->
                val bucket = frac.roundToInt() / 40
                if (bucket != lastShutterBucket) {
                    lastShutterBucket = bucket
                    view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                }
                onShutterChange((1_000_000_000.0 / frac).toLong())
            }
        )

        if (activeLens.minFocusDistance > 0f) {
            if (!compact) Spacer(modifier = Modifier.height(6.dp))

            var lastFocusStep by remember { mutableIntStateOf((state.focusDistance * 5f).roundToInt()) }
            ManualSliderRow(
                label = "FOCUS",
                valueText = if (state.focusDistance < 0.2f) "INFINITY" else "MACRO",
                value = state.focusDistance.coerceIn(0f, activeLens.minFocusDistance),
                valueRange = 0f..activeLens.minFocusDistance,
                onValueChange = { newFocus ->
                    val step = (newFocus * 5f).roundToInt()
                    if (step != lastFocusStep) {
                        lastFocusStep = step
                        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                    }
                    onFocusChange(newFocus)
                }
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    compact: Boolean = false,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (compact) 16.dp else 20.dp,
                vertical = if (compact) 10.dp else 17.dp
            ),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)
    ) {
        MonoText(
            text = title,
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 10.sp,
            letterSpacing = 1.2.sp
        )
        content()
    }
}

@Composable
private fun SettingChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val textColor = when {
        !enabled -> Color.White.copy(alpha = 0.28f)
        selected -> Color.White
        else -> Color.White.copy(alpha = 0.66f)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = if (compact) 4.dp else 6.dp)
    ) {
        MonoText(
            text = label,
            color = textColor,
            fontSize = if (compact) 13.sp else 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun ManualSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonoText(
                text = label,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 11.sp
            )
            MonoText(
                text = valueText,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        MinimalSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
private fun MinimalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    val currentRange by rememberUpdatedState(valueRange)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    fun mapOffsetToValue(x: Float, widthPx: Int, thumbRadiusPx: Float): Float {
        val usableWidth = (widthPx - thumbRadiusPx * 2f).coerceAtLeast(1f)
        val t = ((x - thumbRadiusPx) / usableWidth).coerceIn(0f, 1f)
        val range = currentRange
        return range.start + t * (range.endInclusive - range.start)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                val thumbRadiusPx = 7.dp.toPx()
                detectTapGestures { offset ->
                    currentOnValueChange(mapOffsetToValue(offset.x, size.width, thumbRadiusPx))
                }
            }
            .pointerInput(Unit) {
                val thumbRadiusPx = 7.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        currentOnValueChange(mapOffsetToValue(offset.x, size.width, thumbRadiusPx))
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        currentOnValueChange(mapOffsetToValue(change.position.x, size.width, thumbRadiusPx))
                    }
                )
            }
    ) {
        val thumbRadius = 7.dp.toPx()
        val trackStroke = 2.5.dp.toPx()
        val centerY = size.height / 2f
        val startX = thumbRadius
        val endX = (size.width - thumbRadius).coerceAtLeast(startX)
        val thumbX = startX + (endX - startX) * fraction

        drawLine(
            color = Color.White.copy(alpha = 0.25f),
            start = Offset(startX, centerY),
            end = Offset(endX, centerY),
            strokeWidth = trackStroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(startX, centerY),
            end = Offset(thumbX, centerY),
            strokeWidth = trackStroke,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Color.White,
            radius = thumbRadius,
            center = Offset(thumbX, centerY)
        )
    }
}

@Composable
private fun MonoText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = TextUnit.Unspecified
) {
    BasicText(
        text = text,
        style = TextStyle(
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize,
            fontWeight = fontWeight,
            letterSpacing = letterSpacing
        ),
        modifier = modifier
    )
}

@Composable
private fun HorizontalDivider(color: Color, thickness: Dp = 1.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color)
    )
}

@Composable
private fun VerticalDivider(color: Color, thickness: Dp = 1.dp) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(thickness)
            .background(color)
    )
}

@Composable
private fun FrostedManualOverlay(
    state: CameraUiState,
    isLandscape: Boolean,
    insets: RotatedInsets,
    dragRangePx: Float,
    onDragProgressDelta: (Float) -> Unit,
    onReleaseProgress: (Float) -> Unit,
    onCloseClick: () -> Unit,
    onToggleManual: (Boolean) -> Unit,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Long) -> Unit,
    onFocusChange: (Float) -> Unit
) {
    val activeLens = state.lenses.getOrNull(state.activeLensIndex)
    val view = LocalView.current
    val dividerColor = Color.White.copy(alpha = 0.16f)
    val sheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    var isDraggingSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(sheetShape)
            .background(Color.Black.copy(alpha = 0.62f))
            .border(1.dp, Color.White.copy(alpha = 0.20f), sheetShape)
            .pointerInput(dragRangePx) {
                var lastDragY = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        lastDragY = 0f
                        isDraggingSheet = false
                    },
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount > 0f || isDraggingSheet) {
                            isDraggingSheet = true
                            lastDragY = dragAmount
                            onDragProgressDelta(-dragAmount / dragRangePx)
                        }
                    },
                    onDragEnd = {
                        if (isDraggingSheet) {
                            isDraggingSheet = false
                            val syntheticVelocity = if (lastDragY > 6f) 1000f else 0f
                            onReleaseProgress(syntheticVelocity)
                        }
                    },
                    onDragCancel = {
                        if (isDraggingSheet) {
                            isDraggingSheet = false
                            onReleaseProgress(0f)
                        }
                    }
                )
            }
            .padding(
                start = insets.start,
                end = insets.end,
                bottom = insets.bottom
            )
    ) {
        // Top handle indicator
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.40f))
            )
        }

        // Header with AUTO / MANUAL toggle (CLOSE stays in bottom thumb zone in portrait)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                MonoText(
                    text = "MANUAL",
                    color = Color.White.copy(alpha = 0.80f),
                    fontSize = 11.sp,
                    letterSpacing = 1.6.sp
                )
                SettingChoice(
                    label = "AUTO",
                    selected = !state.isManualMode,
                    onClick = { onToggleManual(false) }
                )
                SettingChoice(
                    label = "ON",
                    selected = state.isManualMode,
                    onClick = { onToggleManual(true) }
                )
            }

            if (isLandscape) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable(role = Role.Button, onClick = onCloseClick)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    MonoText(
                        text = "CLOSE",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        HorizontalDivider(color = dividerColor, thickness = 1.dp)

        if (activeLens != null) {
            val minIso = activeLens.isoRange.lower.toFloat()
            val maxIso = activeLens.isoRange.upper.coerceAtMost(6400).toFloat()
            var lastIsoBucket by remember { mutableIntStateOf(state.iso / 50) }

            val shutterFraction = (1_000_000_000.0 / state.shutterSpeedNs).roundToInt().coerceIn(4, 2000)
            var lastShutterBucket by remember { mutableIntStateOf(shutterFraction / 40) }

            var lastFocusStep by remember { mutableIntStateOf((state.focusDistance * 5f).roundToInt()) }

            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        ManualSliderRow(
                            label = "ISO",
                            valueText = state.iso.toString(),
                            value = state.iso.toFloat().coerceIn(minIso, maxIso),
                            valueRange = minIso..maxIso,
                            onValueChange = { rawVal ->
                                val newIso = rawVal.roundToInt()
                                val bucket = newIso / 50
                                if (bucket != lastIsoBucket) {
                                    lastIsoBucket = bucket
                                    view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                                }
                                onIsoChange(newIso)
                            }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        ManualSliderRow(
                            label = "SHUTTER",
                            valueText = "1/${shutterFraction}s",
                            value = shutterFraction.toFloat(),
                            valueRange = 4f..2000f,
                            onValueChange = { frac ->
                                val bucket = frac.roundToInt() / 40
                                if (bucket != lastShutterBucket) {
                                    lastShutterBucket = bucket
                                    view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                                }
                                onShutterChange((1_000_000_000.0 / frac).toLong())
                            }
                        )
                    }
                    if (activeLens.minFocusDistance > 0f) {
                        Box(modifier = Modifier.weight(1f)) {
                            ManualSliderRow(
                                label = "FOCUS",
                                valueText = if (state.focusDistance < 0.2f) "INF" else "MACRO",
                                value = state.focusDistance.coerceIn(0f, activeLens.minFocusDistance),
                                valueRange = 0f..activeLens.minFocusDistance,
                                onValueChange = { newFocus ->
                                    val step = (newFocus * 5f).roundToInt()
                                    if (step != lastFocusStep) {
                                        lastFocusStep = step
                                        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                                    }
                                    onFocusChange(newFocus)
                                }
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ManualSliderRow(
                        label = "ISO",
                        valueText = state.iso.toString(),
                        value = state.iso.toFloat().coerceIn(minIso, maxIso),
                        valueRange = minIso..maxIso,
                        onValueChange = { rawVal ->
                            val newIso = rawVal.roundToInt()
                            val bucket = newIso / 50
                            if (bucket != lastIsoBucket) {
                                lastIsoBucket = bucket
                                view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                            }
                            onIsoChange(newIso)
                        }
                    )
                    ManualSliderRow(
                        label = "SHUTTER",
                        valueText = "1/${shutterFraction}s",
                        value = shutterFraction.toFloat(),
                        valueRange = 4f..2000f,
                        onValueChange = { frac ->
                            val bucket = frac.roundToInt() / 40
                            if (bucket != lastShutterBucket) {
                                lastShutterBucket = bucket
                                view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                            }
                            onShutterChange((1_000_000_000.0 / frac).toLong())
                        }
                    )
                    if (activeLens.minFocusDistance > 0f) {
                        ManualSliderRow(
                            label = "FOCUS",
                            valueText = if (state.focusDistance < 0.2f) "INFINITY" else "MACRO",
                            value = state.focusDistance.coerceIn(0f, activeLens.minFocusDistance),
                            valueRange = 0f..activeLens.minFocusDistance,
                            onValueChange = { newFocus ->
                                val step = (newFocus * 5f).roundToInt()
                                if (step != lastFocusStep) {
                                    lastFocusStep = step
                                    view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                                }
                                onFocusChange(newFocus)
                            }
                        )
                    }
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp)

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, bottom = 18.dp)
                ) {
                    SettingsClosePill(onClick = onCloseClick)
                }
            }
        }
    }
}

@Composable
private fun CenterLevelCrosshair(
    deviceOrientationDeg: Int,
    rotationProvider: () -> Float,
    alphaProvider: () -> Float,
    hapticEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentHapticEnabled by rememberUpdatedState(hapticEnabled)
    val currentOrientationDeg by rememberUpdatedState(deviceOrientationDeg)

    val gxState = remember { mutableFloatStateOf(0f) }
    val gyState = remember { mutableFloatStateOf(9.81f) }
    val gzState = remember { mutableFloatStateOf(0f) }

    DisposableEffect(context, lifecycleOwner) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        var initialized = false
        var armedForLevelTick = false
        var lastTickMs = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rx = event.values[0]
                val ry = event.values[1]
                val rz = event.values[2]
                if (!initialized) {
                    gxState.floatValue = rx
                    gyState.floatValue = ry
                    gzState.floatValue = rz
                    initialized = true
                } else {
                    val alpha = 0.18f
                    gxState.floatValue += (rx - gxState.floatValue) * alpha
                    gyState.floatValue += (ry - gyState.floatValue) * alpha
                    gzState.floatValue += (rz - gzState.floatValue) * alpha
                }

                val gx = gxState.floatValue
                val gy = gyState.floatValue
                val gz = gzState.floatValue
                val norm = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.01f)
                val nz = abs(gz / norm)
                if (nz <= 0.82f) {
                    val rawDeg = Math.toDegrees(atan2((gx / norm).toDouble(), (gy / norm).toDouble())).toFloat()
                    val cardinal = (rawDeg / 90f).roundToInt() * 90f
                    val err = abs(rawDeg - cardinal)
                    if (err > 2.5f) {
                        armedForLevelTick = true
                    } else if (armedForLevelTick && err <= 0.85f && currentHapticEnabled) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTickMs > 650L) {
                            lastTickMs = now
                            armedForLevelTick = false
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                } else {
                    val flatErr = hypot((gx / norm).toDouble(), (gy / norm).toDouble()).toFloat()
                    if (flatErr > 0.055f) {
                        armedForLevelTick = true
                    } else if (armedForLevelTick && flatErr <= 0.020f && currentHapticEnabled) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTickMs > 650L) {
                            lastTickMs = now
                            armedForLevelTick = false
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        fun register() {
            if (gravitySensor != null) {
                sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> sensorManager.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(listener)
        }
    }

    Canvas(
        modifier = modifier
            .size(56.dp)
            .graphicsLayer {
                rotationZ = rotationProvider()
                alpha = alphaProvider()
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val levelAccent = Color(0xFFFFD54F)

        val gx = gxState.floatValue
        val gy = gyState.floatValue
        val gz = gzState.floatValue
        val norm = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.01f)
        val nx = gx / norm
        val ny = gy / norm
        val nzAbs = abs(gz / norm)

        if (nzAbs >= 0.85f) {
            // Flat / top-down 2-axis level mode: solid minimal crosshair + center dot
            val flatErr = hypot(nx.toDouble(), ny.toDouble()).toFloat()
            val isFlatLevel = flatErr <= 0.025f
            val stroke = if (isFlatLevel) 2.2.dp.toPx() else 2.0.dp.toPx()
            val baseColor = if (isFlatLevel) levelAccent else Color.White.copy(alpha = 0.72f)
            val crossHalf = 12.dp.toPx()

            drawLine(
                color = baseColor,
                start = Offset(center.x - crossHalf, center.y),
                end = Offset(center.x + crossHalf, center.y),
                strokeWidth = stroke,
                cap = StrokeCap.Butt
            )
            drawLine(
                color = baseColor,
                start = Offset(center.x, center.y - crossHalf),
                end = Offset(center.x, center.y + crossHalf),
                strokeWidth = stroke,
                cap = StrokeCap.Butt
            )

            val (ux, uy) = when (currentOrientationDeg) {
                90 -> ny to nx
                180 -> -nx to ny
                270 -> -ny to -nx
                else -> nx to -ny
            }
            val maxShift = 18.dp.toPx()
            val scale = 30.dp.toPx()
            val dotPos = if (isFlatLevel) {
                center
            } else {
                val rawDx = ux * scale
                val rawDy = uy * scale
                val dist = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat().coerceAtLeast(1f)
                val clampFactor = if (dist > maxShift) maxShift / dist else 1f
                Offset(center.x + rawDx * clampFactor, center.y + rawDy * clampFactor)
            }
            drawCircle(
                color = if (isFlatLevel) levelAccent else Color.White,
                radius = if (isFlatLevel) 3.0.dp.toPx() else 2.6.dp.toPx(),
                center = dotPos
            )
        } else {
            // Upright horizon level mode: solid straight wings + rotating center bar + center point
            val rawAngleDeg = Math.toDegrees(atan2(nx.toDouble(), ny.toDouble())).toFloat()
            val nearestCardinal = (rawAngleDeg / 90f).roundToInt() * 90f
            val horizonErrorDeg = rawAngleDeg - nearestCardinal
            val isLevel = abs(horizonErrorDeg) <= 1.15f
            val displayAngle = if (abs(horizonErrorDeg) <= 0.7f) 0f else horizonErrorDeg

            val stroke = if (isLevel) 2.2.dp.toPx() else 2.0.dp.toPx()
            val refColor = if (isLevel) levelAccent else Color.White.copy(alpha = 0.60f)
            val innerColor = if (isLevel) levelAccent else Color.White.copy(alpha = 0.90f)

            val wingInner = 13.dp.toPx()
            val wingOuter = 23.dp.toPx()
            val vertHalf = 8.dp.toPx()
            val barHalf = 10.dp.toPx()

            drawLine(
                color = refColor,
                start = Offset(center.x - wingOuter, center.y),
                end = Offset(center.x - wingInner, center.y),
                strokeWidth = stroke,
                cap = StrokeCap.Butt
            )
            drawLine(
                color = refColor,
                start = Offset(center.x + wingInner, center.y),
                end = Offset(center.x + wingOuter, center.y),
                strokeWidth = stroke,
                cap = StrokeCap.Butt
            )
            drawLine(
                color = refColor,
                start = Offset(center.x, center.y - vertHalf),
                end = Offset(center.x, center.y + vertHalf),
                strokeWidth = stroke,
                cap = StrokeCap.Butt
            )

            rotate(degrees = displayAngle, pivot = center) {
                drawLine(
                    color = innerColor,
                    start = Offset(center.x - barHalf, center.y),
                    end = Offset(center.x + barHalf, center.y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Butt
                )
            }

            drawCircle(
                color = if (isLevel) levelAccent else Color.White,
                radius = if (isLevel) 2.8.dp.toPx() else 2.1.dp.toPx(),
                center = center
            )
        }
    }
}

private fun applyCenterCropTransform(
    view: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    sensorOrientation: Int,
    reusableMatrix: Matrix
) {
    if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return

    val isRotated = (sensorOrientation == 90) || (sensorOrientation == 270)
    val contentWidth = if (isRotated) bufferHeight.toFloat() else bufferWidth.toFloat()
    val contentHeight = if (isRotated) bufferWidth.toFloat() else bufferHeight.toFloat()

    val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
    val contentAspect = contentWidth / contentHeight

    val scaleX: Float
    val scaleY: Float
    if (contentAspect > viewAspect) {
        scaleX = contentAspect / viewAspect
        scaleY = 1f
    } else {
        scaleX = 1f
        scaleY = viewAspect / contentAspect
    }

    reusableMatrix.reset()
    reusableMatrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
    view.setTransform(reusableMatrix)
}
