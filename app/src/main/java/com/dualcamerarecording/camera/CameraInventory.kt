package com.dualcamerarecording.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import java.text.DecimalFormat

/**
 * Enumerates available cameras via CameraManager, following the AndroidCamera
 * CameraInventory pattern: probes for extra camera IDs, filters for color output,
 * deduplicates OEM aliases, and includes focal length information for labeling.
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

    /**
     * Get list of available cameras.
     * Probes for extra camera IDs (OEM aux, hidden lenses), deduplicates
     * alias cameras, filters for color output, and includes focal length info.
     * Matches AndroidCamera's CameraInventory.listBindings pattern.
     */
    fun getAvailableCameras(): List<CameraInfo> {
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

        // Collect raw camera info
        data class RawCamera(
            val id: String,
            val facing: Int,
            val focal: Float?,
            val fromPublicList: Boolean,
            val hasFlash: Boolean,
            val isPhysical: Boolean
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
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull()
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val isPhysical = id in physicalOf

                raw += RawCamera(id, facing, focal, id in publicIds, hasFlash, isPhysical)
                Log.d(TAG, "Found camera id=$id facing=$facing focalMm=$focal public=${id in publicIds} hasFlash=$hasFlash")
            } catch (e: Exception) {
                Log.w(TAG, "Error reading camera $id: ${e.message}")
            }
        }

        // Deduplicate: drop OEM alias IDs that mirror a public logical camera
        // (same facing + focal length)
        val publicKeys = raw.filter { it.fromPublicList }
            .map { facingFocalKey(it.facing, it.focal) }
            .toSet()
        val deduped = raw.filter { entry ->
            if (entry.fromPublicList) return@filter true
            if (entry.isPhysical) return@filter true
            val key = facingFocalKey(entry.facing, entry.focal)
            val keep = key !in publicKeys
            if (!keep) Log.i(TAG, "Skipping alias camera id=${entry.id} key=$key")
            keep
        }

        val result = mutableListOf<CameraInfo>()
        val counters = mutableMapOf<Int, Int>()
        for (entry in deduped) {
            val index = (counters[entry.facing] ?: 0) + 1
            counters[entry.facing] = index

            val isLogical = entry.isPhysical && entry.fromPublicList
            val label = buildLabel(entry.facing, index, entry.focal)

            result.add(
                CameraInfo(
                    cameraId = entry.id,
                    facing = entry.facing,
                    hasFlash = entry.hasFlash,
                    physicalIds = if (entry.fromPublicList) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                cameraManager.getCameraCharacteristics(entry.id).physicalCameraIds.toList()
                            } else emptyList()
                        } catch (e: Exception) { emptyList() }
                    } else emptyList(),
                    isLogical = isLogical,
                    focalLengthMm = entry.focal,
                    indexAmongFacing = index,
                    label = label
                )
            )
        }

        return result
    }

    private fun buildLabel(facing: Int, index: Int, focal: Float?): String {
        val facingStr = when (facing) {
            android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT -> "Front"
            android.hardware.camera2.CameraMetadata.LENS_FACING_BACK -> "Back"
            android.hardware.camera2.CameraMetadata.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }
        val df = DecimalFormat("0.0")
        val focalStr = focal?.let { df.format(it.toDouble()) + "mm" }
        return if (focalStr != null) {
            "$facingStr $index ($focalStr)"
        } else {
            "$facingStr $index"
        }
    }

    private fun facingFocalKey(facing: Int, focal: Float?): String {
        val f = focal?.let { String.format("%.2f", it) } ?: "?"
        return "$facing|$f"
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