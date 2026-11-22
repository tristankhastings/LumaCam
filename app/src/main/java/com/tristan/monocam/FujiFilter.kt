package com.tristan.monocam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

sealed class CameraLook {
    abstract val id: String
    abstract val label: String

    data object Natural : CameraLook() {
        override val id: String = "natural"
        override val label: String = "NATURAL"
    }

    data object Fuji : CameraLook() {
        override val id: String = "fuji"
        override val label: String = "FUJI"
    }

    data object Kodak : CameraLook() {
        override val id: String = "kodak"
        override val label: String = "KODAK"
    }

    data class CustomLut(
        override val id: String,
        override val label: String,
        val filePath: String,
        val lut: FloatArray,
    ) : CameraLook() {
        val lutBitmap: Bitmap by lazy { FujiFilter.buildLutBitmap(lut) }

        override fun equals(other: Any?): Boolean = (other is CustomLut) && (id == other.id)
        override fun hashCode(): Int = id.hashCode()
    }
}

object FujiFilter {

    private const val LUT_SIZE = 33

    private const val AGSL_SHADER = """
        uniform shader inputShader;
        uniform float uMode; // 0 = Natural, 1 = Fuji, 2 = Kodak
        uniform float uGrainEnabled;

        float filmCurve(float x, float contrast, float blackLift, float whiteClip) {
            float c = clamp(x, 0.0, 1.0);
            float s = c * c * (3.0 - 2.0 * c);
            float curved = mix(c, s, contrast);
            float over = max(curved - 0.64, 0.0) / 0.36;
            float shoulder = curved - 0.08 * over * over;
            return clamp(blackLift + (shoulder / 0.92) * (whiteClip - blackLift), 0.0, 1.0);
        }

        float naturalCurve(float x) {
            float c = clamp(x, 0.0, 1.0);
            float s = c * c * (3.0 - 2.0 * c);
            float contrasted = mix(c, s, 0.24);
            float over = max(contrasted - 0.70, 0.0) / 0.30;
            float shoulder = (contrasted - 0.055 * over * over) / 0.945;
            float shadowOpen = 0.048 * pow(1.0 - c, 2.6) * smoothstep(0.03, 0.20, c);
            return clamp(shoulder + shadowOpen, 0.0, 1.0);
        }

        float3 gradeNatural(float3 rgb) {
            float3 c = clamp(rgb, 0.0, 1.0);
            c.r = naturalCurve(c.r);
            c.g = naturalCurve(c.g);
            c.b = naturalCurve(c.b);

            float luma = dot(c, float3(0.2126, 0.7152, 0.0722));
            // Warm natural iPhone-like balance (removes cool shadow cast, gently warms midtones & highlights)
            float shadowW = pow(1.0 - luma, 2.2);
            float midW = 4.0 * luma * (1.0 - luma);
            float highW = pow(luma, 1.5);
            c += float3(0.004, 0.002, -0.005) * shadowW;
            c += float3(0.024, 0.009, -0.022) * midW;
            c += float3(0.012, 0.005, -0.012) * highW;

            // Smart vibrance: enriches muted tones without clipping vivid colors
            float maxC = max(c.r, max(c.g, c.b));
            float minC = min(c.r, min(c.g, c.b));
            float sat = maxC - minC;
            float vib = 1.0 + 0.16 * (1.0 - sat);
            float gLuma = dot(c, float3(0.2126, 0.7152, 0.0722));
            c = mix(float3(gLuma), c, vib);
            return clamp(c, 0.0, 1.0);
        }

        float3 gradeFuji(float3 rgb) {
            float3 c = clamp(rgb, 0.0, 1.0);
            float luma = dot(c, float3(0.2126, 0.7152, 0.0722));
            float redPop = clamp((c.r - max(c.g, c.b)) * 2.2, 0.0, 1.0);
            float warmW = clamp((c.r - c.b) * 1.4, 0.0, 1.0);
            float blueW = clamp((c.b - c.r) * 1.6, 0.0, 1.0);

            c = float3(
                c.r * 1.05 + c.g * 0.03 - c.b * 0.04,
                c.r * 0.01 + c.g * 1.00 + c.b * 0.01,
                -c.r * 0.02 + c.g * 0.05 + c.b * 0.90
            );
            c.r = filmCurve(c.r, 0.44, 0.014, 0.96);
            c.g = filmCurve(c.g, 0.44, 0.014, 0.96);
            c.b = filmCurve(c.b, 0.44, 0.014, 0.96);

            float shadowW = pow(1.0 - luma, 2.0);
            float highW = pow(luma, 1.4);
            c += float3(0.002, 0.003, -0.003) * shadowW;
            c += float3(0.032, 0.018, -0.028) * highW;

            c += float3(0.105, -0.015, -0.040) * redPop;
            c += float3(0.024, 0.014, -0.018) * warmW;
            c += float3(-0.038, 0.072, 0.026) * blueW;

            float gLuma = dot(c, float3(0.2126, 0.7152, 0.0722));
            c = mix(float3(gLuma), c, 1.15);
            return clamp(c, 0.0, 1.0);
        }

        float3 gradeKodak(float3 rgb) {
            float3 c = clamp(rgb, 0.0, 1.0);
            float luma = dot(c, float3(0.2126, 0.7152, 0.0722));
            float redW = clamp((c.r - max(c.g, c.b)) * 2.0, 0.0, 1.0);
            float yellowW = clamp((min(c.r, c.g) - c.b) * 1.8, 0.0, 1.0);
            float blueW = clamp((c.b - c.r) * 1.6, 0.0, 1.0);

            // Vintage Kodak crosstalk: warm amber-cream whites, rich terracotta & deep cobalt
            c = float3(
                c.r * 1.07 + c.g * 0.04 - c.b * 0.05,
                c.r * 0.02 + c.g * 0.99 - c.b * 0.01,
                -c.r * 0.03 + c.g * 0.03 + c.b * 0.87
            );
            c.r = filmCurve(c.r, 0.40, 0.022, 0.945);
            c.g = filmCurve(c.g, 0.40, 0.020, 0.935);
            c.b = filmCurve(c.b, 0.40, 0.016, 0.895);

            float shadowW = pow(1.0 - luma, 2.0);
            float highW = pow(luma, 1.3);
            c += float3(0.012, 0.005, -0.008) * shadowW;
            c += float3(0.036, 0.020, -0.032) * highW;

            // Burnt terracotta reds, golden-ochre yellows, deep cobalt blues
            c += float3(0.065, 0.012, -0.030) * redW;
            c += float3(0.028, 0.018, -0.032) * yellowW;
            c += float3(-0.022, -0.008, 0.042) * blueW;

            float gLuma = dot(c, float3(0.2126, 0.7152, 0.0722));
            c = mix(float3(gLuma), c, 1.12);
            return clamp(c, 0.0, 1.0);
        }

        half4 main(float2 fragCoord) {
            half4 src = inputShader.eval(fragCoord);
            float3 rgb = float3(src.rgb);
            if (src.a > 0.001) {
                rgb = rgb / float(src.a);
            }

            if (uMode > 1.5) {
                rgb = gradeKodak(rgb);
                // Subtle warm vintage halation around bright specular highlights
                float3 hSum = float3(0.0);
                hSum += max(float3(inputShader.eval(fragCoord + float2(6.0, 0.0)).rgb) - 0.80, 0.0);
                hSum += max(float3(inputShader.eval(fragCoord + float2(-6.0, 0.0)).rgb) - 0.80, 0.0);
                hSum += max(float3(inputShader.eval(fragCoord + float2(0.0, 6.0)).rgb) - 0.80, 0.0);
                hSum += max(float3(inputShader.eval(fragCoord + float2(0.0, -6.0)).rgb) - 0.80, 0.0);
                float glow = dot(hSum * 0.25, float3(0.30, 0.50, 0.20));
                rgb = clamp(rgb + float3(1.0, 0.36, 0.14) * glow * 0.45, 0.0, 1.0);
            } else if (uMode > 0.5) {
                rgb = gradeFuji(rgb);
            } else {
                rgb = gradeNatural(rgb);
            }

            if (uGrainEnabled > 0.5) {
                float l = dot(rgb, float3(0.2126, 0.7152, 0.0722));
                float grainMask = 4.0 * l * (1.0 - l);
                float n = fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
                rgb = clamp(rgb + n * 0.052 * (0.35 + 0.65 * grainMask), 0.0, 1.0);
            }

            return half4(half3(rgb * float(src.a)), src.a);
        }
    """

    private const val CUSTOM_LUT_AGSL_SHADER = """
        uniform shader inputShader;
        uniform shader uLut;
        uniform float uGrainEnabled;

        float3 preContrast(float3 rgb) {
            float3 c = clamp(rgb, 0.0, 1.0);
            float3 s = c * c * (3.0 - 2.0 * c);
            return mix(c, s, 0.14);
        }

        float3 sampleLut(float r, float g, float b) {
            float x = b * 33.0 + r + 0.5;
            float y = g + 0.5;
            return float3(uLut.eval(float2(x, y)).rgb);
        }

        float3 evalLut3D(float3 rgb) {
            float3 c = clamp(rgb, 0.0, 1.0) * 32.0;
            float3 i0 = min(floor(c), float3(31.0));
            float3 f = c - i0;

            float r0 = i0.r;
            float g0 = i0.g;
            float b0 = i0.b;
            float r1 = r0 + 1.0;
            float g1 = g0 + 1.0;
            float b1 = b0 + 1.0;

            float3 c000 = sampleLut(r0, g0, b0);
            float3 c100 = sampleLut(r1, g0, b0);
            float3 c010 = sampleLut(r0, g1, b0);
            float3 c110 = sampleLut(r1, g1, b0);
            float3 c001 = sampleLut(r0, g0, b1);
            float3 c101 = sampleLut(r1, g0, b1);
            float3 c011 = sampleLut(r0, g1, b1);
            float3 c111 = sampleLut(r1, g1, b1);

            float3 c00 = mix(c000, c100, f.r);
            float3 c10 = mix(c010, c110, f.r);
            float3 c01 = mix(c001, c101, f.r);
            float3 c11 = mix(c011, c111, f.r);

            float3 c0 = mix(c00, c10, f.g);
            float3 c1 = mix(c01, c11, f.g);
            return clamp(mix(c0, c1, f.b), 0.0, 1.0);
        }

        half4 main(float2 fragCoord) {
            half4 src = inputShader.eval(fragCoord);
            float3 rgb = float3(src.rgb);
            if (src.a > 0.001) {
                rgb = rgb / float(src.a);
            }

            rgb = evalLut3D(preContrast(rgb));

            if (uGrainEnabled > 0.5) {
                float l = dot(rgb, float3(0.2126, 0.7152, 0.0722));
                float grainMask = 4.0 * l * (1.0 - l);
                float n = fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
                rgb = clamp(rgb + n * 0.052 * (0.35 + 0.65 * grainMask), 0.0, 1.0);
            }

            return half4(half3(rgb * float(src.a)), src.a);
        }
    """

    private val naturalLut: FloatArray by lazy { buildLut(CameraLook.Natural) }
    private val fujiLut: FloatArray by lazy { buildLut(CameraLook.Fuji) }
    private val kodakLut: FloatArray by lazy { buildLut(CameraLook.Kodak) }
    private val renderEffectCache = java.util.concurrent.ConcurrentHashMap<String, RenderEffect>()
    private val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val gradeExecutor by lazy {
        Executors.newFixedThreadPool(workerCount) { r ->
            Thread(r, "LumaGradeWorker").apply { isDaemon = true }
        }
    }

    fun warmUp() {
        naturalLut
        fujiLut
        kodakLut
        createRenderEffect(CameraLook.Natural, false)
    }

    fun evictRenderEffect(lookId: String) {
        renderEffectCache.remove("$lookId|false")
        renderEffectCache.remove("$lookId|true")
    }

    fun createRenderEffect(look: CameraLook, grain: Boolean): RenderEffect {
        val cacheKey = "${look.id}|$grain"
        return renderEffectCache.getOrPut(cacheKey) {
            if (look is CameraLook.CustomLut) {
                val lutShader = BitmapShader(look.lutBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val shader = RuntimeShader(CUSTOM_LUT_AGSL_SHADER).apply {
                    setInputShader("uLut", lutShader)
                    setFloatUniform("uGrainEnabled", if (grain) 1f else 0f)
                }
                RenderEffect.createRuntimeShaderEffect(shader, "inputShader")
            } else {
                val mode = when (look) {
                    is CameraLook.Fuji -> 1f
                    is CameraLook.Kodak -> 2f
                    else -> 0f
                }
                val shader = RuntimeShader(AGSL_SHADER).apply {
                    setFloatUniform("uMode", mode)
                    setFloatUniform("uGrainEnabled", if (grain) 1f else 0f)
                }
                RenderEffect.createRuntimeShaderEffect(shader, "inputShader")
            }
        }
    }

    fun applyToJpeg(
        jpegBytes: ByteArray,
        look: CameraLook,
        grain: Boolean,
        dateStampText: String? = null,
        targetAspectRatio: Float? = null,
    ): ByteArray {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return jpegBytes
        val bitmap = if (targetAspectRatio != null) cropToAspectRatio(decoded, targetAspectRatio) else decoded

        return try {
            val width = bitmap.width
            val height = bitmap.height
            val lut = when (look) {
                is CameraLook.Natural -> naturalLut
                is CameraLook.Fuji -> fujiLut
                is CameraLook.Kodak -> kodakLut
                is CameraLook.CustomLut -> look.lut
            }
            val applyPreContrast = look is CameraLook.CustomLut

            applyPixelsByRow(bitmap, width, height, lut, grain, applyPreContrast)

            if (dateStampText != null) {
                stampDateOnBitmap(bitmap, jpegBytes, dateStampText)
            }

            val out = ByteArrayOutputStream(jpegBytes.size)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun cropToAspectRatio(source: Bitmap, targetRatio: Float): Bitmap {
        val w = source.width
        val h = source.height
        val isLandscapeBitmap = w >= h
        val currentRatio = if (isLandscapeBitmap) w.toFloat() / h else h.toFloat() / w
        if (abs(currentRatio - targetRatio) < 0.015f) return source

        val cropW: Int
        val cropH: Int
        if (currentRatio > targetRatio) {
            // Current is wider (e.g. 4:3 -> 1:1): trim the long side
            if (isLandscapeBitmap) {
                cropH = h
                cropW = (h * targetRatio).roundToInt().coerceIn(1, w)
            } else {
                cropW = w
                cropH = (w * targetRatio).roundToInt().coerceIn(1, h)
            }
        } else {
            // Current is narrower (e.g. 4:3 -> 3:2 or 16:9): trim the short side
            if (isLandscapeBitmap) {
                cropW = w
                cropH = (w / targetRatio).roundToInt().coerceIn(1, h)
            } else {
                cropH = h
                cropW = (h / targetRatio).roundToInt().coerceIn(1, w)
            }
        }

        if (cropW == w && cropH == h) return source
        val offsetX = ((w - cropW) / 2).coerceAtLeast(0)
        val offsetY = ((h - cropH) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(source, offsetX, offsetY, cropW, cropH)
        if (cropped !== source) source.recycle()
        return if (cropped.isMutable) {
            cropped
        } else {
            val mutable = cropped.copy(Bitmap.Config.ARGB_8888, true)
            cropped.recycle()
            mutable
        }
    }

    private fun stampDateOnBitmap(bitmap: Bitmap, originalJpegBytes: ByteArray, dateText: String) {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(originalJpegBytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val canvas = Canvas(bitmap)
        canvas.save()
        val (visualW, visualH) = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> {
                canvas.translate(0f, h)
                canvas.rotate(-90f)
                h to w
            }
            ExifInterface.ORIENTATION_ROTATE_180 -> {
                canvas.translate(w, h)
                canvas.rotate(180f)
                w to h
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> {
                canvas.translate(w, 0f)
                canvas.rotate(90f)
                h to w
            }
            else -> w to h
        }
        VintageDateStamp.drawInBottomRight(canvas, dateText, visualW, visualH)
        canvas.restore()
    }

    fun buildLutBitmap(lut: FloatArray): Bitmap {
        val width = LUT_SIZE * LUT_SIZE
        val height = LUT_SIZE
        val pixels = IntArray(width * height)
        val strideY = LUT_SIZE
        val strideZ = LUT_SIZE * LUT_SIZE

        for (b in 0 until LUT_SIZE) {
            for (g in 0 until LUT_SIZE) {
                for (r in 0 until LUT_SIZE) {
                    val srcIdx = (r + g * strideY + b * strideZ) * 3
                    val rr = (lut[srcIdx] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val gg = (lut[srcIdx + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val bb = (lut[srcIdx + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val x = b * LUT_SIZE + r
                    pixels[g * width + x] = -0x1000000 or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }

        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888,
            false,
            ColorSpace.get(ColorSpace.Named.SRGB),
        )
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    class ParsedCubeLut(val title: String, val lut: FloatArray)

    fun parseCubeLut(cubeText: String, fallbackName: String): ParsedCubeLut? {
        var title: String? = null
        var size3d = 0
        var size1d = 0
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<Float>()

        cubeText.lineSequence().forEach { rawLine ->
            val commentIdx = rawLine.indexOf('#')
            val line = (if (commentIdx >= 0) rawLine.substring(0, commentIdx) else rawLine).trim()
            if (line.isEmpty()) return@forEach

            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE" -> {
                    val rawTitle = line.substringAfter("TITLE", "").trim().trim('"')
                    if (rawTitle.isNotEmpty()) title = rawTitle
                }
                "LUT_3D_SIZE" -> size3d = parts.getOrNull(1)?.toIntOrNull() ?: 0
                "LUT_1D_SIZE" -> size1d = parts.getOrNull(1)?.toIntOrNull() ?: 0
                "DOMAIN_MIN" -> {
                    if (parts.size >= 4) {
                        domainMin = floatArrayOf(
                            parts[1].toFloatOrNull() ?: 0f,
                            parts[2].toFloatOrNull() ?: 0f,
                            parts[3].toFloatOrNull() ?: 0f,
                        )
                    }
                }
                "DOMAIN_MAX" -> {
                    if (parts.size >= 4) {
                        domainMax = floatArrayOf(
                            parts[1].toFloatOrNull() ?: 1f,
                            parts[2].toFloatOrNull() ?: 1f,
                            parts[3].toFloatOrNull() ?: 1f,
                        )
                    }
                }
                "LUT_3D_INPUT_RANGE", "LUT_1D_INPUT_RANGE" -> Unit
                else -> {
                    if (parts.size >= 3) {
                        val r = parts[0].toFloatOrNull()
                        val g = parts[1].toFloatOrNull()
                        val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            values.add(r.coerceIn(0f, 1f))
                            values.add(g.coerceIn(0f, 1f))
                            values.add(b.coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }

        val cleanTitle = (title ?: fallbackName)
            .replace(Regex("\\.cube$", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { "CUSTOM LUT" }
            .uppercase()

        if (size3d in 2..128 && values.size >= size3d * size3d * size3d * 3) {
            val rawLut = values.subList(0, size3d * size3d * size3d * 3).toFloatArray()
            val normalized = resample3dLut(rawLut, size3d, domainMin, domainMax)
            return ParsedCubeLut(cleanTitle, normalized)
        }

        if (size1d in 2..4096 && values.size >= size1d * 3) {
            val raw1d = values.subList(0, size1d * 3).toFloatArray()
            val converted = convert1dTo3dLut(raw1d, size1d, domainMin, domainMax)
            return ParsedCubeLut(cleanTitle, converted)
        }

        return null
    }

    private fun resample3dLut(
        src: FloatArray,
        srcSize: Int,
        domainMin: FloatArray,
        domainMax: FloatArray
    ): FloatArray {
        val dst = FloatArray(LUT_SIZE * LUT_SIZE * LUT_SIZE * 3)
        val srcMax = srcSize - 1
        val strideZ = srcSize * srcSize
        val dstDenom = (LUT_SIZE - 1).toFloat()

        val spanR = (domainMax[0] - domainMin[0]).takeIf { it > 1e-5f } ?: 1f
        val spanG = (domainMax[1] - domainMin[1]).takeIf { it > 1e-5f } ?: 1f
        val spanB = (domainMax[2] - domainMin[2]).takeIf { it > 1e-5f } ?: 1f

        var ptr = 0
        for (bIdx in 0 until LUT_SIZE) {
            val bNorm = (((bIdx / dstDenom) - domainMin[2]) / spanB).coerceIn(0f, 1f)
            val bPos = bNorm * srcMax
            val b0 = bPos.toInt().coerceIn(0, srcMax - 1)
            val db = bPos - b0

            for (gIdx in 0 until LUT_SIZE) {
                val gNorm = (((gIdx / dstDenom) - domainMin[1]) / spanG).coerceIn(0f, 1f)
                val gPos = gNorm * srcMax
                val g0 = gPos.toInt().coerceIn(0, srcMax - 1)
                val dg = gPos - g0

                for (rIdx in 0 until LUT_SIZE) {
                    val rNorm = (((rIdx / dstDenom) - domainMin[0]) / spanR).coerceIn(0f, 1f)
                    val rPos = rNorm * srcMax
                    val r0 = rPos.toInt().coerceIn(0, srcMax - 1)
                    val dr = rPos - r0

                    val i000 = (r0 + g0 * srcSize + b0 * strideZ) * 3
                    val i100 = i000 + 3
                    val i010 = i000 + srcSize * 3
                    val i110 = i010 + 3
                    val i001 = i000 + strideZ * 3
                    val i101 = i001 + 3
                    val i011 = i001 + srcSize * 3
                    val i111 = i011 + 3

                    dst[ptr++] = trilinear(src, i000, i100, i010, i110, i001, i101, i011, i111, dr, dg, db, 0)
                    dst[ptr++] = trilinear(src, i000, i100, i010, i110, i001, i101, i011, i111, dr, dg, db, 1)
                    dst[ptr++] = trilinear(src, i000, i100, i010, i110, i001, i101, i011, i111, dr, dg, db, 2)
                }
            }
        }
        return dst
    }

    private fun convert1dTo3dLut(
        src1d: FloatArray,
        srcSize: Int,
        domainMin: FloatArray,
        domainMax: FloatArray
    ): FloatArray {
        val dst = FloatArray(LUT_SIZE * LUT_SIZE * LUT_SIZE * 3)
        val dstDenom = (LUT_SIZE - 1).toFloat()
        val srcMax = srcSize - 1

        fun sample1d(v: Float, ch: Int): Float {
            val span = (domainMax[ch] - domainMin[ch]).takeIf { it > 1e-5f } ?: 1f
            val norm = ((v - domainMin[ch]) / span).coerceIn(0f, 1f)
            val pos = norm * srcMax
            val i0 = pos.toInt().coerceIn(0, srcMax - 1)
            val f = pos - i0
            val a = src1d[i0 * 3 + ch]
            val b = src1d[(i0 + 1) * 3 + ch]
            return (a + (b - a) * f).coerceIn(0f, 1f)
        }

        var ptr = 0
        for (bIdx in 0 until LUT_SIZE) {
            val bVal = sample1d(bIdx / dstDenom, 2)
            for (gIdx in 0 until LUT_SIZE) {
                val gVal = sample1d(gIdx / dstDenom, 1)
                for (rIdx in 0 until LUT_SIZE) {
                    val rVal = sample1d(rIdx / dstDenom, 0)
                    dst[ptr++] = rVal
                    dst[ptr++] = gVal
                    dst[ptr++] = bVal
                }
            }
        }
        return dst
    }

    private fun applyPixelsByRow(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        lut: FloatArray,
        grain: Boolean,
        preContrast: Boolean
    ) {
        val bandHeight = ((height + 1) / 2).coerceAtLeast(1)
        val bandPixels = IntArray(width * bandHeight)

        var startY = 0
        while (startY < height) {
            val rows = min(bandHeight, height - startY)
            bitmap.getPixels(bandPixels, 0, width, 0, startY, width, rows)

            val sliceCount = if (rows >= workerCount * 4) workerCount else 1
            if (sliceCount == 1) {
                gradeSlice(bandPixels, width, startY, 0, rows, lut, grain, preContrast)
            } else {
                val latch = CountDownLatch(sliceCount)
                val rowsPerSlice = (rows + sliceCount - 1) / sliceCount
                for (s in 0 until sliceCount) {
                    val sliceY0 = s * rowsPerSlice
                    val sliceY1 = min(sliceY0 + rowsPerSlice, rows)
                    if (sliceY0 >= sliceY1) {
                        latch.countDown()
                        continue
                    }
                    gradeExecutor.execute {
                        try {
                            gradeSlice(bandPixels, width, startY, sliceY0, sliceY1, lut, grain, preContrast)
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await()
            }

            bitmap.setPixels(bandPixels, 0, width, 0, startY, width, rows)
            startY += rows
        }
    }

    private fun gradeSlice(
        pixels: IntArray,
        width: Int,
        bandOffsetY: Int,
        sliceStartY: Int,
        sliceEndY: Int,
        lut: FloatArray,
        grain: Boolean,
        preContrast: Boolean
    ) {
        val maxIdx = LUT_SIZE - 1
        val maxIdxMinusOne = maxIdx - 1
        val strideY = LUT_SIZE * 3
        val strideZ = LUT_SIZE * LUT_SIZE * 3
        val posScale = maxIdx / 255f
        val inv255 = 1f / 255f

        for (localY in sliceStartY until sliceEndY) {
            val rowOffset = localY * width
            val globalY = bandOffsetY + localY
            for (x in 0 until width) {
                val idx = rowOffset + x
                val argb = pixels[idx]
                val a = argb and -0x1000000

                val rPos: Float
                val gPos: Float
                val bPos: Float
                if (preContrast) {
                    val rNorm = applySoftSCurve(((argb ushr 16) and 0xFF) * inv255)
                    val gNorm = applySoftSCurve(((argb ushr 8) and 0xFF) * inv255)
                    val bNorm = applySoftSCurve((argb and 0xFF) * inv255)
                    rPos = rNorm * maxIdx
                    gPos = gNorm * maxIdx
                    bPos = bNorm * maxIdx
                } else {
                    rPos = ((argb ushr 16) and 0xFF) * posScale
                    gPos = ((argb ushr 8) and 0xFF) * posScale
                    bPos = (argb and 0xFF) * posScale
                }

                val r0 = rPos.toInt().coerceIn(0, maxIdxMinusOne)
                val g0 = gPos.toInt().coerceIn(0, maxIdxMinusOne)
                val b0 = bPos.toInt().coerceIn(0, maxIdxMinusOne)

                val dr = rPos - r0
                val dg = gPos - g0
                val db = bPos - b0

                val i000 = r0 * 3 + g0 * strideY + b0 * strideZ
                val i100 = i000 + 3
                val i010 = i000 + strideY
                val i110 = i010 + 3
                val i001 = i000 + strideZ
                val i101 = i001 + 3
                val i011 = i001 + strideY
                val i111 = i011 + 3

                // Inlined trilinear lookup for R, G, B
                val r00 = lut[i000] + (lut[i100] - lut[i000]) * dr
                val r10 = lut[i010] + (lut[i110] - lut[i010]) * dr
                val r01 = lut[i001] + (lut[i101] - lut[i001]) * dr
                val r11 = lut[i011] + (lut[i111] - lut[i011]) * dr
                var rf = (r00 + (r10 - r00) * dg).let { c0 ->
                    c0 + ((r01 + (r11 - r01) * dg) - c0) * db
                }

                val g00 = lut[i000 + 1] + (lut[i100 + 1] - lut[i000 + 1]) * dr
                val g10 = lut[i010 + 1] + (lut[i110 + 1] - lut[i010 + 1]) * dr
                val g01 = lut[i001 + 1] + (lut[i101 + 1] - lut[i001 + 1]) * dr
                val g11 = lut[i011 + 1] + (lut[i111 + 1] - lut[i011 + 1]) * dr
                var gf = (g00 + (g10 - g00) * dg).let { c0 ->
                    c0 + ((g01 + (g11 - g01) * dg) - c0) * db
                }

                val b00 = lut[i000 + 2] + (lut[i100 + 2] - lut[i000 + 2]) * dr
                val b10 = lut[i010 + 2] + (lut[i110 + 2] - lut[i010 + 2]) * dr
                val b01 = lut[i001 + 2] + (lut[i101 + 2] - lut[i001 + 2]) * dr
                val b11 = lut[i011 + 2] + (lut[i111 + 2] - lut[i011 + 2]) * dr
                var bf = (b00 + (b10 - b00) * dg).let { c0 ->
                    c0 + ((b01 + (b11 - b01) * dg) - c0) * db
                }

                if (grain) {
                    val l = 0.2126f * rf + 0.7152f * gf + 0.0722f * bf
                    val grainMask = 4f * l * (1f - l)
                    var h = x * 374761393 + globalY * 668265263
                    h = (h xor (h ushr 13)) * 1274126177
                    h = h xor (h ushr 16)
                    val n = ((h and 0xFFFF) / 65535f) - 0.5f
                    val delta = n * 0.052f * (0.35f + 0.65f * grainMask)
                    rf = (rf + delta).coerceIn(0f, 1f)
                    gf = (gf + delta).coerceIn(0f, 1f)
                    bf = (bf + delta).coerceIn(0f, 1f)
                }

                val rOut = (rf * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gOut = (gf * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bOut = (bf * 255f + 0.5f).toInt().coerceIn(0, 255)
                pixels[idx] = a or (rOut shl 16) or (gOut shl 8) or bOut
            }
        }
    }

    private fun applySoftSCurve(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val s = c * c * (3f - 2f * c)
        return c + (s - c) * 0.14f
    }

    private fun trilinear(
        lut: FloatArray,
        i000: Int,
        i100: Int,
        i010: Int,
        i110: Int,
        i001: Int,
        i101: Int,
        i011: Int,
        i111: Int,
        dr: Float,
        dg: Float,
        db: Float,
        ch: Int
    ): Float {
        val c00 = lut[i000 + ch] + (lut[i100 + ch] - lut[i000 + ch]) * dr
        val c10 = lut[i010 + ch] + (lut[i110 + ch] - lut[i010 + ch]) * dr
        val c01 = lut[i001 + ch] + (lut[i101 + ch] - lut[i001 + ch]) * dr
        val c11 = lut[i011 + ch] + (lut[i111 + ch] - lut[i011 + ch]) * dr
        val c0 = c00 + (c10 - c00) * dg
        val c1 = c01 + (c11 - c01) * dg
        return c0 + (c1 - c0) * db
    }

    private fun buildLut(look: CameraLook): FloatArray {
        val lut = FloatArray(LUT_SIZE * LUT_SIZE * LUT_SIZE * 3)
        val denom = (LUT_SIZE - 1).toFloat()
        var ptr = 0
        for (bIdx in 0 until LUT_SIZE) {
            val b = bIdx / denom
            for (gIdx in 0 until LUT_SIZE) {
                val g = gIdx / denom
                for (rIdx in 0 until LUT_SIZE) {
                    val r = rIdx / denom
                    val graded = when (look) {
                        is CameraLook.Fuji -> gradeFujiRgb(r, g, b)
                        is CameraLook.Kodak -> gradeKodakRgb(r, g, b)
                        else -> gradeNaturalRgb(r, g, b)
                    }
                    lut[ptr++] = graded[0]
                    lut[ptr++] = graded[1]
                    lut[ptr++] = graded[2]
                }
            }
        }
        return lut
    }

    private fun gradeNaturalRgb(rIn: Float, gIn: Float, bIn: Float): FloatArray {
        var r = naturalCurve(rIn)
        var g = naturalCurve(gIn)
        var b = naturalCurve(bIn)

        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val shadowW = (1f - luma).pow(2.2f)
        val midW = 4f * luma * (1f - luma)
        val highW = luma.pow(1.5f)
        r += 0.004f * shadowW + 0.024f * midW + 0.012f * highW
        g += 0.002f * shadowW + 0.009f * midW + 0.005f * highW
        b += -0.005f * shadowW - 0.022f * midW - 0.012f * highW

        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val sat = maxC - minC
        val vib = 1f + 0.16f * (1f - sat)
        val gLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        r = gLuma + (r - gLuma) * vib
        g = gLuma + (g - gLuma) * vib
        b = gLuma + (b - gLuma) * vib

        return floatArrayOf(
            r.coerceIn(0f, 1f),
            g.coerceIn(0f, 1f),
            b.coerceIn(0f, 1f)
        )
    }

    private fun gradeFujiRgb(rIn: Float, gIn: Float, bIn: Float): FloatArray {
        val luma = 0.2126f * rIn + 0.7152f * gIn + 0.0722f * bIn
        val redPop = ((rIn - max(gIn, bIn)) * 2.2f).coerceIn(0f, 1f)
        val warmW = ((rIn - bIn) * 1.4f).coerceIn(0f, 1f)
        val blueW = ((bIn - rIn) * 1.6f).coerceIn(0f, 1f)

        var r = filmCurve(rIn * 1.05f + gIn * 0.03f - bIn * 0.04f, 0.44f, 0.014f, 0.96f)
        var g = filmCurve(rIn * 0.01f + gIn * 1.00f + bIn * 0.01f, 0.44f, 0.014f, 0.96f)
        var b = filmCurve(-rIn * 0.02f + gIn * 0.05f + bIn * 0.90f, 0.44f, 0.014f, 0.96f)

        val shadowW = (1f - luma).pow(2f)
        val highW = luma.pow(1.4f)
        r += 0.002f * shadowW + 0.032f * highW + 0.105f * redPop + 0.024f * warmW - 0.038f * blueW
        g += 0.003f * shadowW + 0.018f * highW - 0.015f * redPop + 0.014f * warmW + 0.072f * blueW
        b += -0.003f * shadowW - 0.028f * highW - 0.040f * redPop - 0.018f * warmW + 0.026f * blueW

        val gLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        r = gLuma + (r - gLuma) * 1.15f
        g = gLuma + (g - gLuma) * 1.15f
        b = gLuma + (b - gLuma) * 1.15f

        return floatArrayOf(
            r.coerceIn(0f, 1f),
            g.coerceIn(0f, 1f),
            b.coerceIn(0f, 1f)
        )
    }

    private fun gradeKodakRgb(rIn: Float, gIn: Float, bIn: Float): FloatArray {
        val luma = 0.2126f * rIn + 0.7152f * gIn + 0.0722f * bIn
        val redW = ((rIn - max(gIn, bIn)) * 2.0f).coerceIn(0f, 1f)
        val yellowW = ((min(rIn, gIn) - bIn) * 1.8f).coerceIn(0f, 1f)
        val blueW = ((bIn - rIn) * 1.6f).coerceIn(0f, 1f)

        var r = filmCurve(rIn * 1.07f + gIn * 0.04f - bIn * 0.05f, 0.40f, 0.022f, 0.945f)
        var g = filmCurve(rIn * 0.02f + gIn * 0.99f - bIn * 0.01f, 0.40f, 0.020f, 0.935f)
        var b = filmCurve(-rIn * 0.03f + gIn * 0.03f + bIn * 0.87f, 0.40f, 0.016f, 0.895f)

        val shadowW = (1f - luma).pow(2f)
        val highW = luma.pow(1.3f)
        r += 0.012f * shadowW + 0.036f * highW + 0.065f * redW + 0.028f * yellowW - 0.022f * blueW
        g += 0.005f * shadowW + 0.020f * highW + 0.012f * redW + 0.018f * yellowW - 0.008f * blueW
        b += -0.008f * shadowW - 0.032f * highW - 0.030f * redW - 0.032f * yellowW + 0.042f * blueW

        val gLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        r = gLuma + (r - gLuma) * 1.12f
        g = gLuma + (g - gLuma) * 1.12f
        b = gLuma + (b - gLuma) * 1.12f

        return floatArrayOf(
            r.coerceIn(0f, 1f),
            g.coerceIn(0f, 1f),
            b.coerceIn(0f, 1f)
        )
    }

    private fun naturalCurve(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val s = c * c * (3f - 2f * c)
        val contrasted = c + (s - c) * 0.24f
        val over = max(contrasted - 0.70f, 0f) / 0.30f
        val shoulder = (contrasted - 0.055f * over * over) / 0.945f
        val smoothEdge = ((c - 0.03f) / 0.17f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val shadowOpen = 0.048f * (1f - c).pow(2.6f) * smoothEdge
        return (shoulder + shadowOpen).coerceIn(0f, 1f)
    }

    private fun filmCurve(x: Float, contrast: Float, blackLift: Float, whiteClip: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val s = c * c * (3f - 2f * c)
        val curved = c + (s - c) * contrast
        val over = max(curved - 0.64f, 0f) / 0.36f
        val shoulder = curved - 0.08f * over * over
        return (blackLift + (shoulder / 0.92f) * (whiteClip - blackLift)).coerceIn(0f, 1f)
    }
}
