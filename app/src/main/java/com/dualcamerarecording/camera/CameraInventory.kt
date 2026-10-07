package com.dualcamerarecording.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import java.text.DecimalFormat
import kotlin.math.roundToInt

/**
 * Enumerates available cameras via CameraManager: probes for extra camera IDs (OEM aux,
 * hidden lenses), filters for color output, drops OEM alias ids of the same sensor and
 * labels each camera with focal length and resolution.
 *
 * Alias ids (e.g. "100" / "101" mirroring "0" / "1" on some Xiaomi devices) are skipped:
 * they describe the same sensor and, on the devices tested, cannot even configure a
 * normal preview/video stream (the attempt makes the camera HAL restart).
 */
class CameraInventory(private val context: Context) {

    data class CameraInfo(
        val cameraId: String,
        val facing: Int,
        val hasFlash: Boolean,
        val physicalIds: List<String>,
        val isLogical: Boolean,
        val focalLengthMm: Float? = null,
        val indexAmongFacing: Int = 1,
        val label: String = cameraId
    )

    private var cached: List<CameraInfo>? = null

    /**
     * Get list of available cameras (one entry per sensor, aliases grouped).
     */
    fun getAvailableCameras(): List<CameraInfo> {
        cached?.let { return it }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val publicIds = try {
            cameraManager.cameraIdList.toList()
        } catch (e: SecurityException) {
            emptyList()
        }

        // Map physical camera IDs to their logical camera ID
        val physicalOf = mutableMapOf<String, String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (logicalId in publicIds) {
                try {
                    val physical = cameraManager.getCameraCharacteristics(logicalId).physicalCameraIds
                    for (pid in physical) {
                        physicalOf.putIfAbsent(pid, logicalId)
                    }
                    if (physical.isNotEmpty()) {
                        Log.i(TAG, "Logical camera $logicalId physicalIds=$physical")
                    }
                } catch (e: Exception) { }
            }
        }

        // Build set of all candidate IDs: public + physical + probes for extra
        val candidateIds = linkedSetOf<String>()
        candidateIds.addAll(publicIds)
        candidateIds.addAll(physicalOf.keys)
        for (extra in probeExtraIds(cameraManager, candidateIds)) {
            candidateIds += extra
        }

        data class RawCamera(
            val id: String,
            val facing: Int,
            val focal: Float?,
            val fromPublicList: Boolean,
            val hasFlash: Boolean,
            val sensorKey: String,
            val megapixels: Float
        )

        val raw = mutableListOf<RawCamera>()
        for (id in candidateIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)

                // Skip cameras that don't support color output
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                if (caps != null && !caps.contains(
                        android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
                    Log.i(TAG, "Skipping camera id=$id (no color output)")
                    continue
                }

                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val pixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val sensorKey = listOf(
                    facing,
                    focal?.let { String.format(java.util.Locale.US, "%.2f", it) },
                    pixels?.let { "${it.width}x${it.height}" },
                    physicalSize?.let { String.format(java.util.Locale.US, "%.2fx%.2f", it.width, it.height) }
                ).joinToString("|")
                val megapixels = pixels?.let { it.width.toFloat() * it.height / 1_000_000f } ?: 0f

                raw += RawCamera(id, facing, focal, id in publicIds, hasFlash, sensorKey, megapixels)
                Log.d(TAG, "Found camera id=$id facing=$facing focalMm=$focal public=${id in publicIds} " +
                    "hasFlash=$hasFlash sensor=$sensorKey")
            } catch (e: Exception) {
                Log.w(TAG, "Error reading camera $id: ${e.message}")
            }
        }

        // Keep one id per sensor: the public id (or the first found); other ids with the
        // same facing / focal length / pixel array / sensor size are OEM aliases.
        val groups = LinkedHashMap<String, MutableList<RawCamera>>()
        for (entry in raw) groups.getOrPut(entry.sensorKey) { mutableListOf() } += entry

        val result = mutableListOf<CameraInfo>()
        val counters = mutableMapOf<Int, Int>()
        for (group in groups.values) {
            val primary = group.firstOrNull { it.fromPublicList } ?: group.first()
            group.filter { it !== primary }.forEach {
                Log.i(TAG, "Skipping alias camera id=${it.id} (same sensor as ${primary.id})")
            }
            val index = (counters[primary.facing] ?: 0) + 1
            counters[primary.facing] = index

            val physicalIds = if (primary.fromPublicList && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    cameraManager.getCameraCharacteristics(primary.id).physicalCameraIds.toList()
                } catch (e: Exception) { emptyList() }
            } else emptyList()

            result.add(
                CameraInfo(
                    cameraId = primary.id,
                    facing = primary.facing,
                    hasFlash = primary.hasFlash,
                    physicalIds = physicalIds,
                    isLogical = physicalIds.isNotEmpty(),
                    focalLengthMm = primary.focal,
                    indexAmongFacing = index,
                    label = buildLabel(primary.facing, index, primary.focal, primary.megapixels)
                )
            )
        }

        cached = result
        return result
    }

    private fun buildLabel(facing: Int, index: Int, focal: Float?, megapixels: Float): String {
        val facingStr = when (facing) {
            android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT -> "Front"
            android.hardware.camera2.CameraMetadata.LENS_FACING_BACK -> "Back"
            android.hardware.camera2.CameraMetadata.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }
        val details = listOfNotNull(
            focal?.let { DecimalFormat("0.0").format(it.toDouble()) + "mm" },
            megapixels.takeIf { it > 0f }?.let { "${it.roundToInt().coerceAtLeast(1)}MP" }
        )
        return if (details.isEmpty()) "$facingStr $index" else "$facingStr $index (${details.joinToString(", ")})"
    }

    /**
     * Get front-facing cameras.
     */
    fun getFrontCameras(): List<CameraInfo> {
        return getAvailableCameras().filter {
            it.facing == android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT
        }
    }

    /**
     * Get back-facing cameras.
     */
    fun getBackCameras(): List<CameraInfo> {
        return getAvailableCameras().filter {
            it.facing == android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
        }
    }

    /**
     * Find the logical camera that faces the given direction.
     */
    fun findCameraFacing(facing: Int): String? {
        return getAvailableCameras().firstOrNull { it.facing == facing }?.cameraId
    }

    /**
     * Probe for additional camera IDs that might be hidden (OEM aux cameras).
     */
    private fun probeExtraIds(
        manager: CameraManager,
        already: Set<String>
    ): List<String> {
        val found = mutableListOf<String>()
        val suspects = (2..32).map { it.toString() } +
            listOf("100", "101", "102", "103")
        for (id in suspects) {
            if (id in already) continue
            val ok = runCatching { manager.getCameraCharacteristics(id) }.isSuccess
            if (ok) {
                found += id
                Log.i(TAG, "Discovered extra camera id=$id via probe")
            }
        }
        return found
    }

    companion object {
        const val TAG = "CameraInventory"
    }
}
