package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Maya AI", appName)
  }

  @Test
  fun `test action handler methods`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val handler = ArushiActionHandler(context)

    // Test openUrl
    val urlRes = handler.openUrl("https://example.com")
    assertTrue(urlRes.success)

    // Test makeCall
    val callRes = handler.makeCall("9876543210")
    assertTrue(callRes.success)

    // Test battery check
    val batteryRes = handler.getBatteryStatus()
    assertTrue(batteryRes.success)

    // Test volume adjustment
    val volRes = handler.adjustVolume(1)
    assertTrue(volRes.success)

    // Test callContact when contact is not found
    val contactRes = handler.callContact("NonExistentPerson")
    assertNotNull(contactRes)
  }

  @Test
  fun `test language detection in ViewModel`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val handler = ArushiActionHandler(context)
    val vm = ArushiViewModel(handler)

    assertEquals("Hindi (हिंदी)", vm.detectLanguage("नमस्ते, आप कैसी हैं?"))
    assertEquals("English", vm.detectLanguage("Hello Maya, how are you?"))
    assertEquals("Hinglish / Hindi", vm.detectLanguage("WhatsApp kholo aur mummy ko phone lagao"))
    assertEquals("Bengali (বাংলা)", vm.detectLanguage("কেমন আছেন?"))
    assertEquals("Tamil (தமிழ்)", vm.detectLanguage("வணக்கம்"))
  }

  @Test
  fun `test memory manager and personalities`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val mem = MayaMemoryManager(context)

    mem.personality = MayaPersonality.VENOM
    assertEquals(MayaPersonality.VENOM, mem.personality)

    mem.wakeState = WakeState.SLEEPING
    assertEquals(WakeState.SLEEPING, mem.wakeState)

    mem.saveFact("favorite fruit", "Mango")
    val fact = mem.findFact("what is my favorite fruit?")
    assertEquals("Mango", fact)

    val markets = mem.getLiveMarkets()
    assertTrue(markets.isNotEmpty())
  }

  @Test
  fun `test native bridge methods`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).get()
    val bridge = ArushiNativeBridge(activity)

    assertTrue(bridge.isNativeBridgeAvailable())

    val urlRes = bridge.openUrl("https://example.com")
    val urlJson = JSONObject(urlRes)
    assertTrue(urlJson.getBoolean("success"))

    val callRes = bridge.makeCall("9876543210")
    val callJson = JSONObject(callRes)
    assertTrue(callJson.getBoolean("success"))

    val contactRes = bridge.callContact("NonExistentPerson")
    val contactJson = JSONObject(contactRes)
    assertNotNull(contactJson)
  }
}
