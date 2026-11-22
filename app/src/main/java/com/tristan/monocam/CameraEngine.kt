package com.tristan.monocam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.ImageReader
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

data class LensOption(
    val label: String,
    val cameraId: String,
    val physicalId: String?,
    val isFront: Boolean,
    val focalLength: Float,
    val equivalentFocalMm: Float,
    val sensorPixels: Long,
    val supportsRaw: Boolean,
    val isoRange: Range<Int>,
    val exposureRange: Range<Long>,
    val minFocusDistance: Float,
)

enum class AspectRatioOption(val id: String, val label: String, val fixedRatio: Float?) {
    FULL("full", "FULL", null),
    RATIO_4_3("4_3", "4:3", 4f / 3f),
    RATIO_3_2("3_2", "3:2", 3f / 2f),
    RATIO_16_9("16_9", "16:9", 16f / 9f),
    RATIO_1_1("1_1", "1:1", 1f)
}

data class CameraUiState(
    val lenses: List<LensOption> = emptyList(),
    val activeLensIndex: Int = 0,
    val isManualMode: Boolean = false,
    val captureRaw: Boolean = false,
    val aspectRatio: AspectRatioOption = AspectRatioOption.FULL,
    val looks: List<CameraLook> = listOf(CameraLook.Natural, CameraLook.Fuji, CameraLook.Kodak),
    val activeLookId: String = CameraLook.Natural.id,
    val filmGrain: Boolean = false,
    val dateStamp: Boolean = false,
    val iso: Int = 200,
    val shutterSpeedNs: Long = 16_666_666L, // 1/60s
    val focusDistance: Float = 0f,
    val statusMessage: String = "Ready",
    val previewWidth: Int = 1920,
    val previewHeight: Int = 1440,
    val sensorOrientation: Int = 90,
    val deviceOrientationDeg: Int = 0,
    val captureTrigger: Int = 0,
    val lookChangeTrigger: Int = 0,
) {
    val activeLook: CameraLook
        get() = looks.firstOrNull { it.id == activeLookId } ?: CameraLook.Natural
}

private data class PendingCaptureGrade(
    val look: CameraLook,
    val grain: Boolean,
    val dateStampText: String?,
    val targetAspectRatio: Float?
)

class CameraEngine(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val backgroundThread = HandlerThread("CameraBackground").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)
    private val executor = Executors.newSingleThreadExecutor()
    private val lutsDir = File(context.filesDir, "luts").apply { mkdirs() }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var activeSensorOrientation: Int = 90
    @Volatile
    private var deviceOrientationDeg: Int = 0

    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var lastCaptureResult: TotalCaptureResult? = null
    private val pendingGrades = ConcurrentLinkedQueue<PendingCaptureGrade>()
    private val pendingDngOrientations = ConcurrentLinkedQueue<Int>()

    @Volatile
    private var latestAutoIso: Int = 200
    @Volatile
    private var latestAutoShutterNs: Long = 16_666_666L
    @Volatile
    private var latestAutoFocus: Float = 0f
    @Volatile
    private var latestAeState: Int = CaptureResult.CONTROL_AE_STATE_INACTIVE
    @Volatile
    private var latestAfState: Int = CaptureResult.CONTROL_AF_STATE_INACTIVE
    @Volatile
    private var currentAutoAeStep: Int = 0
    @Volatile
    private var smoothedEv100: Double = 9.0
    @Volatile
    private var isOutdoorLighting: Boolean = false
    @Volatile
    private var activeMeteringRegions: Array<MeteringRectangle>? = null
    private val focusResetRunnable = Runnable {
        activeMeteringRegions = null
        applyRepeatingPreviewRequest()
    }
    private val repeatingRequestRunnable = Runnable {
        applyRepeatingPreviewRequest()
    }

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val current = deviceOrientationDeg
            val diff = ((orientation - current + 540) % 360) - 180
            if (abs(diff) > 60) {
                val snapped = ((orientation + 45) / 90 * 90) % 360
                deviceOrientationDeg = snapped
                _uiState.update { it.copy(deviceOrientationDeg = snapped) }
            }
        }
    }

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState = _uiState.asStateFlow()

    init {
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        backgroundHandler.post { discoverPhone4aProLenses() }
        loadSavedCustomLuts()
        executor.execute { FujiFilter.warmUp() }
    }

    private fun discoverPhone4aProLenses() {
        val discovered = mutableListOf<LensOption>()

        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK &&
                facing != CameraCharacteristics.LENS_FACING_FRONT
            ) {
                continue
            }
            val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT

            val physicalIds = chars.physicalCameraIds
            if (physicalIds.isNotEmpty()) {
                for (physId in physicalIds) {
                    val pChars = cameraManager.getCameraCharacteristics(physId)
                    buildLensOption(id, physId, isFront, pChars)?.let { discovered.add(it) }
                }
            } else {
                buildLensOption(id, null, isFront, chars)?.let { discovered.add(it) }
            }
        }

        // Deduplicate sensors with matching facing & 35mm-equivalent focal length, preferring RAW + highest resolution
        val dedupedRaw = discovered
            .groupBy { it.isFront to (it.equivalentFocalMm / 3f).roundToInt() }
            .values
            .mapNotNull { group ->
                group.maxWithOrNull(
                    compareBy<LensOption> { it.supportsRaw }
                        .thenBy { it.physicalId != null }
                        .thenBy { it.sensorPixels }
                )
            }
            .sortedWith(compareBy<LensOption> { it.isFront }.thenBy { it.equivalentFocalMm })

        val frontCount = dedupedRaw.count { it.isFront }
        val deduped = dedupedRaw.mapIndexed { idx, lens ->
            val tag = if (lens.isFront) {
                if (frontCount <= 1) "SELFIE" else if (idx == dedupedRaw.indexOfFirst { it.isFront }) "SELFIE W" else "SELFIE"
            } else {
                when {
                    lens.equivalentFocalMm < 19f -> "0.6×"
                    lens.equivalentFocalMm < 36f -> "1×"
                    lens.equivalentFocalMm < 65f -> "2×"
                    else -> "3.5×"
                }
            }
            lens.copy(label = tag)
        }

        val defaultIdx = deduped.indexOfFirst { !it.isFront && it.label == "1×" }.coerceAtLeast(0)
        _uiState.update { state ->
            val defaultLens = deduped.getOrNull(defaultIdx)
            val clampedIso = if (defaultLens != null) {
                state.iso.coerceIn(defaultLens.isoRange.lower, defaultLens.isoRange.upper)
            } else {
                state.iso
            }
            state.copy(lenses = deduped, activeLensIndex = defaultIdx, iso = clampedIso)
        }
    }

    private fun buildLensOption(
        logicalId: String,
        physicalId: String?,
        isFront: Boolean,
        chars: CameraCharacteristics
    ): LensOption? {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
            return null
        }

        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 5.5f
        val physSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val eq35 = if (physSize != null && physSize.width > 0f && physSize.height > 0f) {
            val diag = hypot(physSize.width.toDouble(), physSize.height.toDouble()).toFloat()
            focal * (43.2666f / diag)
        } else {
            focal * 4.4f
        }
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorPixels = if (activeArray != null) {
            activeArray.width().toLong() * activeArray.height().toLong()
        } else {
            12_000_000L
        }

        val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
        val exp = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        return LensOption(
            label = "${focal}mm",
            cameraId = logicalId,
            physicalId = physicalId,
            isFront = isFront,
            focalLength = focal,
            equivalentFocalMm = eq35,
            sensorPixels = sensorPixels,
            supportsRaw = hasRaw,
            isoRange = iso,
            exposureRange = exp,
            minFocusDistance = minFocus
        )
    }

    private fun loadSavedCustomLuts() {
        executor.execute {
            val files = lutsDir.listFiles { file -> file.extension.equals("cube", ignoreCase = true) }
                ?.sortedBy { it.lastModified() }
                ?: return@execute

            val customLooks = files.mapNotNull { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                val parsed = FujiFilter.parseCubeLut(text, file.nameWithoutExtension) ?: return@mapNotNull null
                CameraLook.CustomLut(
                    id = "custom_${file.nameWithoutExtension}",
                    label = parsed.title,
                    filePath = file.absolutePath,
                    lut = parsed.lut
                )
            }

            if (customLooks.isNotEmpty()) {
                _uiState.update { state ->
                    val builtIn = state.looks.filterNot { it is CameraLook.CustomLut }
                    state.copy(looks = builtIn + customLooks)
                }
            }
        }
    }

    fun importCustomLut(uri: Uri) {
        executor.execute {
            val fileName = queryDisplayName(uri) ?: "custom_lut.cube"
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()

            if (text == null) {
                _uiState.update { it.copy(statusMessage = "Failed to read LUT file") }
                return@execute
            }

            val parsed = FujiFilter.parseCubeLut(text, fileName)
            if (parsed == null) {
                _uiState.update { it.copy(statusMessage = "Invalid .cube LUT") }
                return@execute
            }

            val slug = parsed.title.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifEmpty { "lut" }
            val uniqueSuffix = System.currentTimeMillis().toString(36)
            val savedFile = File(lutsDir, "${slug}_$uniqueSuffix.cube")

            val storedText = if (text.lineSequence().any { it.trimStart().startsWith("TITLE", ignoreCase = true) }) {
                text
            } else {
                "TITLE \"${parsed.title}\"\n$text"
            }
            savedFile.writeText(storedText)

            val customLook = CameraLook.CustomLut(
                id = "custom_${savedFile.nameWithoutExtension}",
                label = parsed.title,
                filePath = savedFile.absolutePath,
                lut = parsed.lut
            )

            _uiState.update { state ->
                state.copy(
                    looks = state.looks + customLook,
                    activeLookId = customLook.id,
                    filmGrain = false,
                    statusMessage = "Added ${customLook.label}",
                )
            }
        }
    }

    fun removeCustomLut(lookId: String) {
        executor.execute {
            val target = _uiState.value.looks
                .filterIsInstance<CameraLook.CustomLut>()
                .firstOrNull { it.id == lookId } ?: return@execute

            runCatching { File(target.filePath).delete() }
            FujiFilter.evictRenderEffect(lookId)

            _uiState.update { state ->
                val remaining = state.looks.filterNot { it.id == lookId }
                val nextActiveId = if (state.activeLookId == lookId) CameraLook.Natural.id else state.activeLookId
                val nextLook = remaining.firstOrNull { it.id == nextActiveId } ?: CameraLook.Natural
                state.copy(
                    looks = remaining,
                    activeLookId = nextActiveId,
                    filmGrain = nextLook.isFilmPreset(),
                    lookChangeTrigger = state.lookChangeTrigger + 1
                )
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    fun attachPreviewSurface(surface: Surface) {
        backgroundHandler.post {
            previewSurface = surface
            openActiveLens()
        }
    }

    fun selectLens(index: Int) {
        val lens = _uiState.value.lenses.getOrNull(index) ?: return
        _uiState.update { state ->
            state.copy(
                activeLensIndex = index,
                iso = state.iso.coerceIn(lens.isoRange.lower, lens.isoRange.upper),
                focusDistance = state.focusDistance.coerceIn(0f, lens.minFocusDistance),
                captureRaw = if (lens.supportsRaw) state.captureRaw else false
            )
        }
        backgroundHandler.post { openActiveLens() }
    }

    fun cycleLens() {
        val size = _uiState.value.lenses.size
        if (size <= 1) return
        val nextIdx = (_uiState.value.activeLensIndex + 1) % size
        selectLens(nextIdx)
    }

    fun updateManualSettings(
        isManual: Boolean = _uiState.value.isManualMode,
        iso: Int = _uiState.value.iso,
        shutterNs: Long = _uiState.value.shutterSpeedNs,
        focus: Float = _uiState.value.focusDistance,
        raw: Boolean = _uiState.value.captureRaw
    ) {
        val current = _uiState.value
        val lens = current.lenses.getOrNull(current.activeLensIndex)
        val enteringManual = isManual && !current.isManualMode

        val targetIso = if (enteringManual && iso == current.iso) {
            latestAutoIso
        } else {
            iso
        }.let { v ->
            if (lens != null) v.coerceIn(lens.isoRange.lower, lens.isoRange.upper) else v
        }

        val targetShutter = if (enteringManual && shutterNs == current.shutterSpeedNs) {
            latestAutoShutterNs
        } else {
            shutterNs
        }.let { v ->
            if (lens != null) v.coerceIn(lens.exposureRange.lower, lens.exposureRange.upper) else v
        }

        val targetFocus = if (enteringManual && focus == current.focusDistance) {
            latestAutoFocus
        } else {
            focus
        }.let { v ->
            if (lens != null) v.coerceIn(0f, lens.minFocusDistance) else v.coerceAtLeast(0f)
        }

        _uiState.update {
            it.copy(
                isManualMode = isManual,
                iso = targetIso,
                shutterSpeedNs = targetShutter,
                focusDistance = targetFocus,
                captureRaw = raw
            )
        }
        backgroundHandler.removeCallbacks(repeatingRequestRunnable)
        backgroundHandler.post(repeatingRequestRunnable)
    }

    fun focusAndMeterAt(
        tapX: Float,
        tapY: Float,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (_uiState.value.isManualMode || viewWidth <= 0 || viewHeight <= 0) return
        backgroundHandler.post {
            if (_uiState.value.isManualMode) return@post
            val device = cameraDevice ?: return@post
            val session = captureSession ?: return@post
            val preview = previewSurface ?: return@post
            val chars = activeCharacteristics ?: return@post
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post

            val state = _uiState.value
            val isRotated = (state.sensorOrientation == 90) || (state.sensorOrientation == 270)
            val contentWidth = if (isRotated) state.previewHeight.toFloat() else state.previewWidth.toFloat()
            val contentHeight = if (isRotated) state.previewWidth.toFloat() else state.previewHeight.toFloat()
            val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
            val contentAspect = contentWidth / contentHeight

            val scaleX = if (contentAspect > viewAspect) contentAspect / viewAspect else 1f
            val scaleY = if (contentAspect > viewAspect) 1f else viewAspect / contentAspect

            val rawNx = (((tapX - viewWidth / 2f) / scaleX + viewWidth / 2f) / viewWidth).coerceIn(0f, 1f)
            val isFront = state.lenses.getOrNull(state.activeLensIndex)?.isFront == true
            val nx = if (isFront) 1f - rawNx else rawNx
            val ny = (((tapY - viewHeight / 2f) / scaleY + viewHeight / 2f) / viewHeight).coerceIn(0f, 1f)

            val (sx, sy) = when (state.sensorOrientation) {
                90 -> ny to (1f - nx)
                180 -> (1f - nx) to (1f - ny)
                270 -> (1f - ny) to nx
                else -> nx to ny
            }

            val cx = (activeArray.left + sx * activeArray.width()).roundToInt()
            val cy = (activeArray.top + sy * activeArray.height()).roundToInt()
            val halfW = (activeArray.width() * 0.08f).roundToInt().coerceAtLeast(32)
            val halfH = (activeArray.height() * 0.08f).roundToInt().coerceAtLeast(32)

            val rect = Rect(
                (cx - halfW).coerceIn(activeArray.left, activeArray.right - 1),
                (cy - halfH).coerceIn(activeArray.top, activeArray.bottom - 1),
                (cx + halfW).coerceIn(activeArray.left + 1, activeArray.right),
                (cy + halfH).coerceIn(activeArray.top + 1, activeArray.bottom)
            )
            val regions = arrayOf(MeteringRectangle(rect, 850))
            activeMeteringRegions = regions

            backgroundHandler.removeCallbacks(focusResetRunnable)
            backgroundHandler.postDelayed(focusResetRunnable, 3500L)

            val triggerBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                configureRequestControls(this, isStillCapture = false)
                val maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
                val maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
                if (maxAf > 0) {
                    set(CaptureRequest.CONTROL_AF_REGIONS, regions)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                }
                if (maxAe > 0) {
                    set(CaptureRequest.CONTROL_AE_REGIONS, regions)
                    set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }
            }
            runCatching {
                session.capture(triggerBuilder.build(), null, backgroundHandler)
            }
            applyRepeatingPreviewRequest()
        }
    }

    fun selectLook(lookId: String) {
        _uiState.update { state ->
            val target = state.looks.firstOrNull { it.id == lookId } ?: return@update state
            if (state.activeLookId == lookId) return@update state
            state.copy(
                activeLookId = target.id,
                filmGrain = target.isFilmPreset(),
                lookChangeTrigger = state.lookChangeTrigger + 1
            )
        }
    }

    fun cycleLook(delta: Int) {
        _uiState.update { state ->
            val list = state.looks
            if (list.size <= 1) return@update state
            val currentIdx = list.indexOfFirst { it.id == state.activeLookId }.coerceAtLeast(0)
            val nextIdx = ((currentIdx + delta) % list.size + list.size) % list.size
            val nextLook = list[nextIdx]
            state.copy(
                activeLookId = nextLook.id,
                filmGrain = nextLook.isFilmPreset(),
                lookChangeTrigger = state.lookChangeTrigger + 1
            )
        }
    }

    private fun CameraLook.isFilmPreset(): Boolean = (this is CameraLook.Fuji) || (this is CameraLook.Kodak)

    fun updateFilmGrain(grain: Boolean) {
        _uiState.update { it.copy(filmGrain = grain) }
    }

    fun updateDateStamp(enabled: Boolean) {
        _uiState.update { it.copy(dateStamp = enabled) }
    }

    fun selectAspectRatio(ratio: AspectRatioOption) {
        _uiState.update { it.copy(aspectRatio = ratio) }
    }

    fun cycleAspectRatio() {
        val entries = AspectRatioOption.entries
        _uiState.update { state ->
            val nextIdx = (entries.indexOf(state.aspectRatio) + 1) % entries.size
            state.copy(aspectRatio = entries[nextIdx])
        }
    }

    @SuppressLint("MissingPermission")
    private fun openActiveLens() {
        val surface = previewSurface ?: return
        val lens = _uiState.value.lenses.getOrNull(_uiState.value.activeLensIndex) ?: return

        backgroundHandler.removeCallbacks(focusResetRunnable)
        activeMeteringRegions = null
        currentAutoAeStep = 0
        smoothedEv100 = 9.0
        isOutdoorLighting = false

        val activeChars = cameraManager.getCameraCharacteristics(lens.physicalId ?: lens.cameraId)
        activeCharacteristics = activeChars
        val map = activeChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        val sensorOrientation = activeChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        activeSensorOrientation = sensorOrientation
        val chosenPreview = chooseBestPreviewSize(map.getOutputSizes(SurfaceTexture::class.java))
        _uiState.update {
            it.copy(
                previewWidth = chosenPreview.width,
                previewHeight = chosenPreview.height,
                sensorOrientation = sensorOrientation
            )
        }

        val jpegSize = chooseBestJpegSize(map.getOutputSizes(ImageFormat.JPEG))
        jpegReader?.close()
        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.use { img ->
                    val buffer = img.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val state = _uiState.value
                    val fallbackStamp = if (state.dateStamp) VintageDateStamp.formatCurrentDate() else null
                    val grade = pendingGrades.poll()
                        ?: PendingCaptureGrade(state.activeLook, state.filmGrain, fallbackStamp, state.aspectRatio.fixedRatio)
                    executor.execute {
                        saveJpegToGallery(bytes, grade.look, grade.grain, grade.dateStampText, grade.targetAspectRatio)
                    }
                }
            }, backgroundHandler)
        }

        rawReader?.close()
        rawReader = null
        if (lens.supportsRaw) {
            val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
            if (rawSize != null) {
                rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        reader.acquireLatestImage()?.use { img ->
                            val result = lastCaptureResult ?: return@use
                            val orientationDeg = pendingDngOrientations.poll() ?: computeJpegOrientation()
                            saveDngToGallery(img, activeChars, result, orientationDeg)
                        }
                    }, backgroundHandler)
                }
            }
        }

        val existingDevice = cameraDevice
        captureSession?.close()
        captureSession = null

        // Fast physical lens switching when sharing the same logical CameraDevice
        if (existingDevice != null && existingDevice.id == lens.cameraId) {
            createSession(existingDevice, surface, lens.physicalId)
            return
        }

        existingDevice?.close()
        cameraDevice = null

        cameraManager.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                createSession(device, surface, lens.physicalId)
            }
            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) { device.close() }
        }, backgroundHandler)
    }

    private fun createSession(device: CameraDevice, preview: Surface, physicalId: String?) {
        val outputs = mutableListOf<OutputConfiguration>()

        fun makeOutput(surface: Surface): OutputConfiguration {
            return OutputConfiguration(surface).apply {
                if (physicalId != null) setPhysicalCameraId(physicalId)
            }
        }

        outputs.add(makeOutput(preview))
        jpegReader?.surface?.let { outputs.add(makeOutput(it)) }
        rawReader?.surface?.let { outputs.add(makeOutput(it)) }

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    applyRepeatingPreviewRequest()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }
        )
        device.createCaptureSession(sessionConfig)
    }

    private fun configureRequestControls(builder: CaptureRequest.Builder, isStillCapture: Boolean) {
        val state = _uiState.value
        val chars = activeCharacteristics

        builder.set(
            CaptureRequest.CONTROL_CAPTURE_INTENT,
            if (isStillCapture) CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
            else CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
        )

        if (state.isManualMode) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, state.iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, state.shutterSpeedNs)
            val minFocus = chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (minFocus > 0f) {
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, state.focusDistance)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            val afModes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            when {
                afModes == null || afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                }
                else -> {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                }
            }

            val awbModes = chars?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            if (awbModes == null || awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }

            val antibandingModes = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
            when {
                antibandingModes == null || antibandingModes.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO) -> {
                    builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
                }
                antibandingModes.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ) -> {
                    builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ)
                }
            }

            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentAutoAeStep)

            activeMeteringRegions?.let { regions ->
                val maxAf = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
                val maxAe = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
                if (maxAf > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
                if (maxAe > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
            }

            if (!isStillCapture) {
                choosePreviewFpsRange(chars)?.let { fps ->
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                }
            }
        }

        // Enable hardware OIS on Nothing Phone 4a Pro main & telephoto sensors; disable digital EIS crop
        val oisModes = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (oisModes != null && oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        val eisModes = chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        if (eisModes != null && eisModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }

        // Optical lens shading & hot-pixel cleanup improve both indoor low-light and outdoor uniformity
        val shadingModes = chars?.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
        when {
            shadingModes?.contains(CaptureRequest.SHADING_MODE_HIGH_QUALITY) == true -> {
                builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
            }
            shadingModes?.contains(CaptureRequest.SHADING_MODE_FAST) == true -> {
                builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_FAST)
            }
        }

        val hotPixelModes = chars?.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
        when {
            hotPixelModes?.contains(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY) == true -> {
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
            }
            hotPixelModes?.contains(CaptureRequest.HOT_PIXEL_MODE_FAST) == true -> {
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
            }
        }

        val abModes = chars?.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
        when {
            abModes?.contains(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY) == true -> {
                builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
            }
            abModes?.contains(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST) == true -> {
                builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
            }
        }

        // Disable camera sharpening and denoising for clean unprocessed JPEGs
        val edgeModes = chars?.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        if (edgeModes == null || edgeModes.contains(CaptureRequest.EDGE_MODE_OFF)) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        }

        val nrModes = chars?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        when {
            nrModes == null || nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) -> {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            }
            nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL) -> {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
            }
        }
    }

    private fun choosePreviewFpsRange(chars: CameraCharacteristics?): Range<Int>? {
        val ranges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        // Allow variable lower bound (e.g. 10..30 or 15..30) so indoor preview exposes brightly without forced high ISO
        return ranges
            .filter { it.upper <= 30 }
            .sortedWith(compareByDescending<Range<Int>> { it.upper }.thenBy { it.lower })
            .firstOrNull()
    }

    private val repeatingCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (aeState != null) latestAeState = aeState
            if (afState != null) latestAfState = afState

            if (_uiState.value.isManualMode) return

            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (iso != null) latestAutoIso = iso
            if (expNs != null) latestAutoShutterNs = expNs
            if (focusDist != null) latestAutoFocus = focusDist

            if (iso != null && expNs != null && expNs > 0L && iso > 0) {
                val chars = activeCharacteristics
                val aperture = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 1.8f
                val expSec = expNs / 1_000_000_000.0
                val ev100 = (ln((aperture * aperture) / expSec) / ln(2.0)) - (ln(iso / 100.0) / ln(2.0))
                smoothedEv100 = smoothedEv100 * 0.85 + ev100 * 0.15

                val aeSettled = aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                if (aeSettled && chars != null) {
                    val wasOutdoor = isOutdoorLighting
                    val nowOutdoor = when {
                        wasOutdoor && smoothedEv100 <= 10.6 -> false
                        !wasOutdoor && smoothedEv100 >= 11.6 -> true
                        else -> wasOutdoor
                    }
                    isOutdoorLighting = nowOutdoor

                    // Subtle highlight-protection bias outdoors (-0.33 EV), balanced 0 EV indoors
                    val targetEv = if (nowOutdoor) -0.33f else 0.0f
                    val compRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    val compStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
                    val desiredStep = if (compRange != null && compStep > 0f) {
                        (targetEv / compStep).roundToInt().coerceIn(compRange.lower, compRange.upper)
                    } else {
                        0
                    }

                    if (desiredStep != currentAutoAeStep) {
                        currentAutoAeStep = desiredStep
                        applyRepeatingPreviewRequest()
                    }
                }
            }
        }
    }

    private fun applyRepeatingPreviewRequest() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val preview = previewSurface ?: return

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            configureRequestControls(this, isStillCapture = false)
        }
        runCatching {
            session.setRepeatingRequest(builder.build(), repeatingCaptureCallback, backgroundHandler)
        }
    }

    private fun computeJpegOrientation(): Int {
        val isFront = _uiState.value.lenses.getOrNull(_uiState.value.activeLensIndex)?.isFront == true
        val signedDevice = if (isFront) -deviceOrientationDeg else deviceOrientationDeg
        return (activeSensorOrientation + signedDevice + 360) % 360
    }

    fun takePhoto() {
        backgroundHandler.post {
            val device = cameraDevice ?: return@post
            val session = captureSession ?: return@post
            val state = _uiState.value
            val activeLook = state.activeLook
            val jpegOrientation = computeJpegOrientation()

            val needsBriefSettle = !state.isManualMode && (
                latestAfState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
                    latestAfState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                    latestAeState == CaptureResult.CONTROL_AE_STATE_SEARCHING
                )

            if (!needsBriefSettle) {
                executeStillCapture(device, session, state, activeLook, jpegOrientation)
                return@post
            }

            // Wait briefly for in-progress AF/AE scan to settle before firing capture (max 200ms)
            val preview = previewSurface
            if (preview != null) {
                val precap = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    configureRequestControls(this, isStillCapture = false)
                    set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }
                runCatching { session.capture(precap.build(), null, backgroundHandler) }
            }
            backgroundHandler.postDelayed({
                executeStillCapture(device, session, _uiState.value, _uiState.value.activeLook, computeJpegOrientation())
            }, 200L)
        }
    }

    private fun executeStillCapture(
        device: CameraDevice,
        session: CameraCaptureSession,
        state: CameraUiState,
        activeLook: CameraLook,
        jpegOrientation: Int
    ) {
        val stampText = if (state.dateStamp) VintageDateStamp.formatCurrentDate() else null
        pendingGrades.add(PendingCaptureGrade(activeLook, state.filmGrain, stampText, state.aspectRatio.fixedRatio))
        if (state.captureRaw && rawReader != null) {
            pendingDngOrientations.add(jpegOrientation)
        }

        _uiState.update {
            it.copy(
                statusMessage = "Capturing...",
                captureTrigger = it.captureTrigger + 1
            )
        }

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            jpegReader?.surface?.let { addTarget(it) }
            if (state.captureRaw && rawReader != null) {
                addTarget(rawReader!!.surface)
            }
            configureRequestControls(this, isStillCapture = true)
            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            set(CaptureRequest.JPEG_QUALITY, 98.toByte())
            activeCharacteristics
                ?.get(CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES)
                ?.firstOrNull { it.width > 0 && it.height > 0 }
                ?.let { set(CaptureRequest.JPEG_THUMBNAIL_SIZE, it) }
        }

        runCatching {
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    lastCaptureResult = result
                    val lookSuffix = if (activeLook !is CameraLook.Natural) " • ${activeLook.label}" else ""
                    val msg = if (state.captureRaw) "Saved RAW + JPG$lookSuffix" else "Saved JPG$lookSuffix"
                    _uiState.update { it.copy(statusMessage = msg) }
                }
            }, backgroundHandler)
        }
    }

    private fun saveJpegToGallery(
        originalBytes: ByteArray,
        look: CameraLook,
        grain: Boolean,
        dateStampText: String?,
        targetAspectRatio: Float?
    ) {
        val name = "MonoCam_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}${File.separator}MonoCam")
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        val gradedBytes = FujiFilter.applyToJpeg(originalBytes, look, grain, dateStampText, targetAspectRatio)
        val tempFile = File.createTempFile("monocam_grade_", ".jpg", context.cacheDir)
        try {
            tempFile.writeBytes(gradedBytes)
            copyExifMetadata(originalBytes, tempFile)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out) }
            }
        } finally {
            tempFile.delete()
        }
    }

    @SuppressLint("ExifInterface")
    private fun copyExifMetadata(sourceJpeg: ByteArray, targetFile: File) {
        val srcExif = ExifInterface(ByteArrayInputStream(sourceJpeg))
        val dstExif = ExifInterface(targetFile.absolutePath)
        val tags = arrayOf(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            "ISOSpeedRatings",
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH
        )
        for (tag in tags) {
            srcExif.getAttribute(tag)?.let { dstExif.setAttribute(tag, it) }
        }
        dstExif.saveAttributes()
    }

    @SuppressLint("ExifInterface")
    private fun saveDngToGallery(
        image: android.media.Image,
        chars: CameraCharacteristics,
        result: TotalCaptureResult,
        orientationDeg: Int
    ) {
        val name = "MonoCam_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.dng"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}${File.separator}MonoCam")
        }
        val exifOrientation = when (orientationDeg) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            context.contentResolver.openOutputStream(uri)?.use { out ->
                DngCreator(chars, result).use { creator ->
                    creator.setOrientation(exifOrientation)
                    creator.writeImage(out, image)
                }
            }
        }
    }

    private fun chooseBestJpegSize(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(4000, 3000)
        // Prefer 4:3 hardware-binned ~12.5MP output (<=16MP) for best dynamic range, speed, and low-light quality
        val binned = sizes.filter { it.width.toLong() * it.height.toLong() <= 16_000_000L }
        val pool = binned.ifEmpty { sizes.toList() }
        val fourByThree = pool.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.05f }
        return fourByThree.maxByOrNull { it.width * it.height }
            ?: pool.maxByOrNull { it.width * it.height }
            ?: Size(4000, 3000)
    }

    private fun chooseBestPreviewSize(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(1920, 1440)
        // Prefer 4:3 preview matching the 4:3 still capture so 4:3, 3:2, 16:9, and 1:1 framing match saved photos
        val capped = sizes.filter { it.width <= 2048 && it.height <= 1536 }.ifEmpty { sizes.toList() }
        val fourByThree = capped.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.05f }
        return fourByThree.maxByOrNull { it.width * it.height }
            ?: capped.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1440)
    }

    fun release() {
        orientationListener.disable()
        backgroundHandler.removeCallbacksAndMessages(null)
        captureSession?.close()
        cameraDevice?.close()
        jpegReader?.close()
        rawReader?.close()
        backgroundThread.quitSafely()
        executor.shutdown()
    }
}
