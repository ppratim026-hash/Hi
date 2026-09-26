package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.net.URLEncoder

data class ActionExecutionResult(
    val success: Boolean,
    val actionName: String,
    val message: String,
    val status: String = "SUCCESS",
    val data: String? = null
)

class ArushiActionHandler(private val context: Context) {

    private var isTorchOn = false

    fun toggleTorch(enable: Boolean? = null): ActionExecutionResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager == null) {
                return ActionExecutionResult(false, "toggleTorch", "Camera service unavailable.")
            }
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId == null) {
                return ActionExecutionResult(false, "toggleTorch", "No camera flash found on device.")
            }

            val targetState = enable ?: !isTorchOn
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager.setTorchMode(cameraId, targetState)
                isTorchOn = targetState
                ActionExecutionResult(
                    success = true,
                    actionName = "toggleTorch",
                    message = if (targetState) "Flashlight turned ON." else "Flashlight turned OFF.",
                    status = if (targetState) "TORCH_ON" else "TORCH_OFF"
                )
            } else {
                ActionExecutionResult(false, "toggleTorch", "Flashlight toggle requires Android M+.")
            }
        } catch (e: Exception) {
            ActionExecutionResult(false, "toggleTorch", "Torch error: ${e.message}")
        }
    }

    fun getBatteryStatus(): ActionExecutionResult {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 85

            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val message = "Battery level is $batteryPct%" + if (isCharging) " (Charging)" else " (Discharging)"
            ActionExecutionResult(true, "getBatteryStatus", message, data = "$batteryPct")
        } catch (e: Exception) {
            ActionExecutionResult(false, "getBatteryStatus", "Could not read battery: ${e.message}")
        }
    }

    fun adjustVolume(direction: Int): ActionExecutionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                return ActionExecutionResult(false, "adjustVolume", "Audio service unavailable.")
            }

            val flag = AudioManager.FLAG_SHOW_UI
            val streamType = AudioManager.STREAM_MUSIC

            when (direction) {
                1 -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, flag)
                    ActionExecutionResult(true, "adjustVolume", "Volume increased.")
                }
                -1 -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, flag)
                    ActionExecutionResult(true, "adjustVolume", "Volume decreased.")
                }
                0 -> {
                    audioManager.setStreamVolume(streamType, 0, flag)
                    ActionExecutionResult(true, "adjustVolume", "Media volume muted.")
                }
                else -> {
                    val maxVol = audioManager.getStreamMaxVolume(streamType)
                    audioManager.setStreamVolume(streamType, maxVol, flag)
                    ActionExecutionResult(true, "adjustVolume", "Volume set to maximum.")
                }
            }
        } catch (e: Exception) {
            ActionExecutionResult(false, "adjustVolume", "Volume control error: ${e.message}")
        }
    }

    fun setAlarm(hour: Int, minute: Int, label: String = "Maya Alarm"): ActionExecutionResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionExecutionResult(true, "setAlarm", "Alarm set for ${String.format("%02d:%02d", hour, minute)}.")
        } catch (e: Exception) {
            ActionExecutionResult(false, "setAlarm", "Could not set alarm: ${e.message}")
        }
    }

    fun setTimer(seconds: Int, label: String = "Maya Timer"): ActionExecutionResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionExecutionResult(true, "setTimer", "Timer set for $seconds seconds.")
        } catch (e: Exception) {
            ActionExecutionResult(false, "setTimer", "Could not set timer: ${e.message}")
        }
    }

    fun openHotspot(): ActionExecutionResult {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setClassName("com.android.settings", "com.android.settings.TetherSettings")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                ActionExecutionResult(true, "openHotspot", "Opened Hotspot settings.")
            } else {
                val wireIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(wireIntent)
                ActionExecutionResult(true, "openHotspot", "Opened Network settings.")
            }
        } catch (e: Exception) {
            ActionExecutionResult(false, "openHotspot", "Opening settings failed: ${e.message}")
        }
    }

    fun openWhatsApp(): ActionExecutionResult {
        return try {
            val pm = context.packageManager
            val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
            var launchIntent: Intent? = null
            for (pkg in packages) {
                launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) break
            }

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                ActionExecutionResult(
                    success = true,
                    actionName = "openWhatsApp",
                    message = "WhatsApp opened successfully."
                )
            } else {
                val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (deepLinkIntent.resolveActivity(pm) != null) {
                    context.startActivity(deepLinkIntent)
                    ActionExecutionResult(
                        success = true,
                        actionName = "openWhatsApp",
                        message = "WhatsApp opened via link."
                    )
                } else {
                    ActionExecutionResult(
                        success = false,
                        actionName = "openWhatsApp",
                        message = "WhatsApp is not installed on this device."
                    )
                }
            }
        } catch (e: Exception) {
            ActionExecutionResult(
                success = false,
                actionName = "openWhatsApp",
                message = "Could not open WhatsApp: ${e.message}"
            )
        }
    }

    fun sendWhatsAppMessage(numberOrName: String, text: String): ActionExecutionResult {
        return try {
            val cleanNumber = numberOrName.replace(Regex("[^0-9+]"), "")
            val encoded = URLEncoder.encode(text, "UTF-8")
            val uri = if (cleanNumber.isNotBlank()) {
                Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=$encoded")
            } else {
                Uri.parse("https://api.whatsapp.com/send?text=$encoded")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionExecutionResult(true, "sendWhatsAppMessage", "WhatsApp message prepared: $text")
        } catch (e: Exception) {
            ActionExecutionResult(false, "sendWhatsAppMessage", "WhatsApp send failed: ${e.message}")
        }
    }

    fun playYouTube(query: String = ""): ActionExecutionResult {
        return try {
            val clean = query.trim()
            val intent = if (clean.isNotBlank()) {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(clean, "UTF-8")))
            } else {
                context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionExecutionResult(true, "playYouTube", if (clean.isNotBlank()) "Playing '$clean' on YouTube." else "YouTube opened.")
        } catch (e: Exception) {
            ActionExecutionResult(false, "playYouTube", "YouTube error: ${e.message}")
        }
    }

    fun playSpotify(query: String = ""): ActionExecutionResult {
        return try {
            val clean = query.trim()
            val intent = if (clean.isNotBlank()) {
                Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + URLEncoder.encode(clean, "UTF-8"))).apply {
                    setPackage("com.spotify.music")
                }
            } else {
                context.packageManager.getLaunchIntentForPackage("com.spotify.music")
            }
            if (intent != null && intent.resolveActivity(context.packageManager) != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionExecutionResult(true, "playSpotify", "Playing on Spotify.")
            } else {
                val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + URLEncoder.encode(clean, "UTF-8"))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(web)
                ActionExecutionResult(true, "playSpotify", "Opened Spotify Web.")
            }
        } catch (e: Exception) {
            ActionExecutionResult(false, "playSpotify", "Spotify error: ${e.message}")
        }
    }

    fun openApp(appName: String): ActionExecutionResult {
        val cleanName = appName.trim().lowercase()
        return try {
            val pm = context.packageManager

            when {
                cleanName.contains("whatsapp") -> return openWhatsApp()
                cleanName.contains("youtube") -> return playYouTube()
                cleanName.contains("spotify") -> return playSpotify()
                cleanName.contains("torch") || cleanName.contains("flashlight") -> return toggleTorch()
                cleanName.contains("battery") -> return getBatteryStatus()
                cleanName.contains("hotspot") -> return openHotspot()

                cleanName.contains("instagram") -> {
                    val intent = pm.getLaunchIntentForPackage("com.instagram.android")
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return ActionExecutionResult(true, "openApp", "Instagram opened successfully.")
                    } else {
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(webIntent)
                        return ActionExecutionResult(true, "openApp", "Opened Instagram in browser.")
                    }
                }

                cleanName.contains("chrome") -> {
                    val intent = pm.getLaunchIntentForPackage("com.android.chrome")
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return ActionExecutionResult(true, "openApp", "Chrome opened successfully.")
                    } else {
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(webIntent)
                        return ActionExecutionResult(true, "openApp", "Browser opened.")
                    }
                }

                cleanName.contains("setting") -> {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return ActionExecutionResult(true, "openApp", "Device Settings opened.")
                }

                cleanName.contains("camera") -> {
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return ActionExecutionResult(true, "openApp", "Camera opened.")
                }

                cleanName.contains("map") -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return ActionExecutionResult(true, "openApp", "Maps opened.")
                }
            }

            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (label.contains(cleanName) || cleanName.contains(label)) {
                    val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        return ActionExecutionResult(true, "openApp", "${pm.getApplicationLabel(app)} opened.")
                    }
                }
            }

            ActionExecutionResult(false, "openApp", "Application '$appName' is not installed.")
        } catch (e: Exception) {
            ActionExecutionResult(false, "openApp", "Failed to open $appName: ${e.message}")
        }
    }

    fun makeCall(phoneNumber: String): ActionExecutionResult {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.isEmpty()) {
            return ActionExecutionResult(false, "makeCall", "Invalid phone number provided.")
        }

        return try {
            val hasCallPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

            if (hasCallPermission) {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                ActionExecutionResult(
                    success = true,
                    actionName = "makeCall",
                    message = "Calling $cleanNumber",
                    status = "CALLING",
                    data = cleanNumber
                )
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                ActionExecutionResult(
                    success = true,
                    actionName = "makeCall",
                    message = "Opened dialer with $cleanNumber",
                    status = "DIALER",
                    data = cleanNumber
                )
            }
        } catch (e: Exception) {
            ActionExecutionResult(false, "makeCall", "Could not initiate call: ${e.message}")
        }
    }

    fun callContact(contactName: String): ActionExecutionResult {
        val queryName = contactName.trim()
        if (queryName.isEmpty()) {
            return ActionExecutionResult(false, "callContact", "No contact name provided.", status = "NOT_FOUND")
        }

        val hasContactsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!hasContactsPermission) {
            return ActionExecutionResult(
                success = false,
                actionName = "callContact",
                message = "Contacts permission is required to search contacts. Please grant Contacts permission.",
                status = "PERMISSION_REQUIRED"
            )
        }

        return try {
            val matches = searchContacts(queryName)
            when (matches.size) {
                0 -> {
                    ActionExecutionResult(
                        success = false,
                        actionName = "callContact",
                        message = "No contact found matching '$queryName'.",
                        status = "NOT_FOUND"
                    )
                }
                1 -> {
                    val contact = matches[0]
                    val callRes = makeCall(contact.number)
                    ActionExecutionResult(
                        success = callRes.success,
                        actionName = "callContact",
                        message = "Calling ${contact.name} (${contact.number})",
                        status = "CALLING",
                        data = contact.number
                    )
                }
                else -> {
                    val names = matches.joinToString(", ") { it.name }
                    ActionExecutionResult(
                        success = false,
                        actionName = "callContact",
                        message = "I found ${matches.size} contacts matching '$queryName': $names. Which one should I call?",
                        status = "MULTIPLE_MATCHES",
                        data = names
                    )
                }
            }
        } catch (e: Exception) {
            ActionExecutionResult(
                success = false,
                actionName = "callContact",
                message = "Error searching contacts: ${e.message}",
                status = "ERROR"
            )
        }
    }

    fun openUrl(url: String): ActionExecutionResult {
        return try {
            val targetUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionExecutionResult(true, "openUrl", "Opened $targetUrl", data = targetUrl)
        } catch (e: Exception) {
            ActionExecutionResult(false, "openUrl", "Could not open URL: ${e.message}")
        }
    }

    private data class ContactItem(val name: String, val number: String)

    private fun searchContacts(query: String): List<ContactItem> {
        val results = mutableListOf<ContactItem>()
        val seenNumbers = mutableSetOf<String>()
        val lowerQuery = query.lowercase().trim()

        val queryAliases = when (lowerQuery) {
            "mom", "mummy", "mother", "maa", "aai", "mataji" -> listOf("mom", "mummy", "mother", "maa", "aai")
            "dad", "daddy", "father", "papa", "baba", "pitaji" -> listOf("dad", "daddy", "father", "papa", "baba")
            "bro", "brother", "bhai", "bhaiya" -> listOf("bro", "brother", "bhai", "bhaiya")
            "sis", "sister", "didi", "behen" -> listOf("sis", "sister", "didi", "behen")
            else -> listOf(lowerQuery)
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val name = if (nameIndex >= 0) it.getString(nameIndex) ?: "" else ""
                val number = if (numberIndex >= 0) it.getString(numberIndex) ?: "" else ""
                val lowerName = name.lowercase()

                val matches = queryAliases.any { alias ->
                    lowerName.contains(alias) || alias.contains(lowerName)
                }

                if (matches && number.isNotBlank() && !seenNumbers.contains(number)) {
                    seenNumbers.add(number)
                    results.add(ContactItem(name, number))
                }
            }
        }

        return results
    }
}
