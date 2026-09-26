package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class ArushiNativeBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun isNativeBridgeAvailable(): Boolean = true

    @JavascriptInterface
    fun getApiKey(): String {
        return try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun requestPermissions() {
        activity.runOnUiThread {
            activity.requestAppPermissions()
        }
    }

    @JavascriptInterface
    fun checkPermissions(): String {
        val result = JSONObject()
        result.put("audio", ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        result.put("contacts", ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
        result.put("call", ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
        return result.toString()
    }

    @JavascriptInterface
    fun openWhatsApp(): String {
        return try {
            val pm = activity.packageManager
            val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
            var launchIntent: Intent? = null
            for (pkg in packages) {
                launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) break
            }
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(launchIntent)
                JSONObject().apply {
                    put("success", true)
                    put("action", "openWhatsApp")
                    put("message", "WhatsApp opened successfully.")
                }.toString()
            } else {
                // Try deep link intent
                val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send"))
                deepLinkIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (deepLinkIntent.resolveActivity(pm) != null) {
                    activity.startActivity(deepLinkIntent)
                    JSONObject().apply {
                        put("success", true)
                        put("action", "openWhatsApp")
                        put("message", "WhatsApp opened.")
                    }.toString()
                } else {
                    JSONObject().apply {
                        put("success", false)
                        put("action", "openWhatsApp")
                        put("message", "WhatsApp is not installed on this device.")
                    }.toString()
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("action", "openWhatsApp")
                put("message", "Could not open WhatsApp: ${e.message}")
            }.toString()
        }
    }

    @JavascriptInterface
    fun openApp(appName: String): String {
        val cleanName = appName.trim().lowercase()
        return try {
            val pm = activity.packageManager

            when {
                cleanName.contains("whatsapp") -> return openWhatsApp()

                cleanName.contains("youtube") -> {
                    val intent = pm.getLaunchIntentForPackage("com.google.android.youtube")
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(intent)
                        return JSONObject().apply {
                            put("success", true)
                            put("appName", "YouTube")
                            put("message", "YouTube opened successfully.")
                        }.toString()
                    } else {
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
                        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(webIntent)
                        return JSONObject().apply {
                            put("success", true)
                            put("appName", "YouTube")
                            put("message", "Opened YouTube in browser.")
                        }.toString()
                    }
                }

                cleanName.contains("instagram") -> {
                    val intent = pm.getLaunchIntentForPackage("com.instagram.android")
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(intent)
                        return JSONObject().apply {
                            put("success", true)
                            put("appName", "Instagram")
                            put("message", "Instagram opened successfully.")
                        }.toString()
                    } else {
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com"))
                        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(webIntent)
                        return JSONObject().apply {
                            put("success", true)
                            put("appName", "Instagram")
                            put("message", "Opened Instagram.")
                        }.toString()
                    }
                }

                cleanName.contains("chrome") -> {
                    val intent = pm.getLaunchIntentForPackage("com.android.chrome")
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(intent)
                        return JSONObject().apply {
                            put("success", true)
                            put("appName", "Chrome")
                            put("message", "Chrome opened successfully.")
                        }.toString()
                    } else {
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                        webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(webIntent)
                        return JSONObject().apply {
                            put("success", true)
                            put("appName", "Chrome")
                            put("message", "Browser opened.")
                        }.toString()
                    }
                }

                cleanName.contains("setting") -> {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                    return JSONObject().apply {
                        put("success", true)
                        put("appName", "Settings")
                        put("message", "Device Settings opened.")
                    }.toString()
                }

                cleanName.contains("camera") -> {
                    val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                    return JSONObject().apply {
                        put("success", true)
                        put("appName", "Camera")
                        put("message", "Camera opened.")
                    }.toString()
                }

                cleanName.contains("map") -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                    return JSONObject().apply {
                        put("success", true)
                        put("appName", "Maps")
                        put("message", "Maps opened.")
                    }.toString()
                }
            }

            // General query of installed packages
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (label.contains(cleanName) || cleanName.contains(label)) {
                    val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(launchIntent)
                        return JSONObject().apply {
                            put("success", true)
                            put("appName", pm.getApplicationLabel(app).toString())
                            put("message", "${pm.getApplicationLabel(app)} opened successfully.")
                        }.toString()
                    }
                }
            }

            JSONObject().apply {
                put("success", false)
                put("appName", appName)
                put("message", "Application '$appName' is not installed on this device.")
            }.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("appName", appName)
                put("message", "Failed to open $appName: ${e.message}")
            }.toString()
        }
    }

    @JavascriptInterface
    fun makeCall(phoneNumber: String): String {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.isEmpty()) {
            return JSONObject().apply {
                put("success", false)
                put("message", "Invalid phone number provided.")
            }.toString()
        }

        return try {
            val hasCallPermission = ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

            if (hasCallPermission) {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber"))
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(callIntent)
                JSONObject().apply {
                    put("success", true)
                    put("action", "makeCall")
                    put("phoneNumber", cleanNumber)
                    put("mode", "direct_call")
                    put("message", "Calling $cleanNumber")
                }.toString()
            } else {
                // If direct call not permitted, open phone dialer pre-filled
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber"))
                dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(dialIntent)
                JSONObject().apply {
                    put("success", true)
                    put("action", "makeCall")
                    put("phoneNumber", cleanNumber)
                    put("mode", "dialer")
                    put("message", "Opened dialer with $cleanNumber")
                }.toString()
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("message", "Could not initiate call: ${e.message}")
            }.toString()
        }
    }

    @JavascriptInterface
    fun callContact(contactName: String): String {
        val queryName = contactName.trim()
        if (queryName.isEmpty()) {
            return JSONObject().apply {
                put("success", false)
                put("status", "NOT_FOUND")
                put("message", "No contact name provided.")
            }.toString()
        }

        val hasContactsPermission = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!hasContactsPermission) {
            activity.runOnUiThread {
                activity.requestContactsPermission()
            }
            return JSONObject().apply {
                put("success", false)
                put("status", "PERMISSION_REQUIRED")
                put("message", "Contacts permission is required to search contacts. Please grant Contacts permission.")
            }.toString()
        }

        return try {
            val matches = searchContacts(queryName)
            when (matches.size) {
                0 -> {
                    JSONObject().apply {
                        put("success", false)
                        put("status", "NOT_FOUND")
                        put("message", "No contact found matching '$queryName'.")
                    }.toString()
                }
                1 -> {
                    val contact = matches[0]
                    val callRes = makeCall(contact.number)
                    val callObj = JSONObject(callRes)
                    JSONObject().apply {
                        put("success", callObj.optBoolean("success", false))
                        put("status", "CALLING")
                        put("name", contact.name)
                        put("phoneNumber", contact.number)
                        put("mode", callObj.optString("mode", "direct_call"))
                        put("message", "Calling ${contact.name} at ${contact.number}")
                    }.toString()
                }
                else -> {
                    val array = JSONArray()
                    val names = mutableListOf<String>()
                    for (c in matches) {
                        val obj = JSONObject()
                        obj.put("name", c.name)
                        obj.put("phoneNumber", c.number)
                        array.put(obj)
                        names.add(c.name)
                    }
                    JSONObject().apply {
                        put("success", false)
                        put("status", "MULTIPLE_MATCHES")
                        put("count", matches.size)
                        put("contacts", array)
                        put("message", "I found ${matches.size} contacts matching '$queryName': ${names.joinToString(", ")}. Which one should I call?")
                    }.toString()
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("status", "ERROR")
                put("message", "Error searching contacts: ${e.message}")
            }.toString()
        }
    }

    @JavascriptInterface
    fun openUrl(url: String): String {
        return try {
            val targetUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            JSONObject().apply {
                put("success", true)
                put("action", "openUrl")
                put("url", targetUrl)
                put("message", "Opened $targetUrl")
            }.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("action", "openUrl")
                put("message", "Could not open URL: ${e.message}")
            }.toString()
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

        val cursor = activity.contentResolver.query(
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
