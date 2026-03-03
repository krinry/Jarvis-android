package dev.krinry.jarvis.agent

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.telephony.SmsManager
import android.util.Log

/**
 * DirectIntentExecutor — Fire Android Intents directly, skipping UI.
 *
 * 10x faster than UI clicking. No screen reading needed.
 * Actions: call, send_sms, set_alarm, set_timer, create_event,
 *          navigate, search_web, send_email, flashlight, set_volume
 */
object DirectIntentExecutor {

    private const val TAG = "DirectIntentExecutor"

    fun execute(action: ActionExecutor.AgentAction, context: Context): String {
        return when (action.action) {
            "call" -> makeCall(action, context)
            "send_sms" -> sendSms(action, context)
            "set_alarm" -> setAlarm(action, context)
            "set_timer" -> setTimer(action, context)
            "create_event" -> createCalendarEvent(action, context)
            "navigate" -> navigate(action, context)
            "search_web" -> searchWeb(action, context)
            "send_email" -> sendEmail(action, context)
            "flashlight" -> toggleFlashlight(action, context)
            "set_volume" -> setVolume(action, context)
            "open_settings" -> openSettings(action, context)
            else -> "❓ Unknown intent action: ${action.action}"
        }
    }

    /**
     * Direct phone call.
     * AI: {"action":"call","phone":"9876543210","speech":"Call kar raha hoon"}
     */
    private fun makeCall(action: ActionExecutor.AgentAction, context: Context): String {
        val phone = action.phone ?: action.text
        if (phone.isNullOrBlank()) return "❌ Phone number nahi diya"

        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "✅ Call kar raha hoon: $phone"
        } catch (e: SecurityException) {
            "❌ Call permission nahi hai. Settings mein allow karo."
        } catch (e: Exception) {
            "❌ Call error: ${e.message?.take(50)}"
        }
    }

    /**
     * Send SMS directly.
     * AI: {"action":"send_sms","phone":"9876543210","text":"Hello bhai"}
     */
    private fun sendSms(action: ActionExecutor.AgentAction, context: Context): String {
        val phone = action.phone
        val message = action.text
        if (phone.isNullOrBlank()) return "❌ Phone number nahi diya"
        if (message.isNullOrBlank()) return "❌ Message nahi diya"

        return try {
            @Suppress("DEPRECATION")
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phone, null, message, null, null)
            "✅ SMS bhej diya: $phone"
        } catch (e: SecurityException) {
            "❌ SMS permission nahi hai. Settings mein allow karo."
        } catch (e: Exception) {
            "❌ SMS error: ${e.message?.take(50)}"
        }
    }

    /**
     * Set alarm.
     * AI: {"action":"set_alarm","text":"7:30 AM"}
     */
    private fun setAlarm(action: ActionExecutor.AgentAction, context: Context): String {
        val timeText = action.text ?: return "❌ Time nahi diya"

        return try {
            // Parse simple time formats (e.g., "7:30", "7:30 AM", "19:30")
            val parts = timeText.replace(Regex("[^0-9:]"), " ").trim().split(":")
            val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return "❌ Time samajh nahi aaya: $timeText"
            val minute = parts.getOrNull(1)?.trim()?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 0

            // Adjust for PM
            val adjustedHour = if (timeText.contains("PM", ignoreCase = true) && hour < 12) hour + 12
                              else if (timeText.contains("AM", ignoreCase = true) && hour == 12) 0
                              else hour

            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, adjustedHour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "✅ Alarm set: $timeText"
        } catch (e: Exception) {
            "❌ Alarm error: ${e.message?.take(50)}"
        }
    }

    /**
     * Set timer.
     * AI: {"action":"set_timer","text":"5 minutes"}
     */
    private fun setTimer(action: ActionExecutor.AgentAction, context: Context): String {
        val timeText = action.text ?: return "❌ Timer duration nahi diya"

        return try {
            // Parse: "5 minutes", "30 seconds", "1 hour", "90"
            val num = Regex("\\d+").find(timeText)?.value?.toIntOrNull() ?: return "❌ Timer samajh nahi aaya"
            val seconds = when {
                timeText.contains("hour", ignoreCase = true) -> num * 3600
                timeText.contains("min", ignoreCase = true) -> num * 60
                timeText.contains("sec", ignoreCase = true) -> num
                else -> num * 60 // default to minutes
            }

            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "✅ Timer set: $timeText"
        } catch (e: Exception) {
            "❌ Timer error: ${e.message?.take(50)}"
        }
    }

    /**
     * Create calendar event.
     * AI: {"action":"create_event","text":"Meeting at 3 PM"}
     */
    private fun createCalendarEvent(action: ActionExecutor.AgentAction, context: Context): String {
        val title = action.text ?: return "❌ Event title nahi diya"

        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "✅ Calendar event: $title"
        } catch (e: Exception) {
            "❌ Calendar error: ${e.message?.take(50)}"
        }
    }

    /**
     * Navigate via Google Maps.
     * AI: {"action":"navigate","text":"India Gate, New Delhi"}
     */
    private fun navigate(action: ActionExecutor.AgentAction, context: Context): String {
        val destination = action.text ?: return "❌ Destination nahi diya"

        return try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "✅ Navigation shuru: $destination"
        } catch (e: Exception) {
            // Fallback: open in browser
            try {
                val webUri = Uri.parse("https://www.google.com/maps/search/${Uri.encode(destination)}")
                context.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                "✅ Maps khol raha hoon: $destination"
            } catch (_: Exception) {
                "❌ Maps error: ${e.message?.take(50)}"
            }
        }
    }

    /**
     * Web search.
     * AI: {"action":"search_web","text":"IPL score today"}
     */
    private fun searchWeb(action: ActionExecutor.AgentAction, context: Context): String {
        val query = action.text ?: return "❌ Search query nahi diya"

        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "✅ Search: $query"
        } catch (e: Exception) {
            "❌ Search error: ${e.message?.take(50)}"
        }
    }

    /**
     * Compose email.
     * AI: {"action":"send_email","text":"boss@company.com","body":"Sir, leave chahiye kal"}
     */
    private fun sendEmail(action: ActionExecutor.AgentAction, context: Context): String {
        val to = action.text ?: return "❌ Email address nahi diya"
        val body = action.body ?: ""

        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$to")
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "✅ Email compose: $to"
        } catch (e: Exception) {
            "❌ Email error: ${e.message?.take(50)}"
        }
    }

    /**
     * Toggle flashlight.
     * AI: {"action":"flashlight","text":"on"} or {"action":"flashlight","text":"off"}
     */
    private fun toggleFlashlight(action: ActionExecutor.AgentAction, context: Context): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            val turnOn = action.text?.lowercase() != "off"
            cameraManager.setTorchMode(cameraId, turnOn)
            if (turnOn) "✅ Flashlight ON 🔦" else "✅ Flashlight OFF"
        } catch (e: Exception) {
            "❌ Flashlight error: ${e.message?.take(50)}"
        }
    }

    /**
     * Set volume.
     * AI: {"action":"set_volume","text":"50"} (0-100) or {"action":"set_volume","text":"mute"}
     */
    private fun setVolume(action: ActionExecutor.AgentAction, context: Context): String {
        val text = action.text ?: return "❌ Volume level nahi diya"

        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            when (text.lowercase()) {
                "mute" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                    "✅ Volume mute kiya"
                }
                "unmute" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                    "✅ Volume unmute kiya"
                }
                "max" -> {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, AudioManager.FLAG_SHOW_UI)
                    "✅ Volume max"
                }
                else -> {
                    val percent = text.replace("%", "").trim().toIntOrNull()
                    if (percent != null) {
                        val vol = (percent * maxVolume / 100).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, AudioManager.FLAG_SHOW_UI)
                        "✅ Volume set: $percent%"
                    } else {
                        "❌ Volume samajh nahi aaya: $text"
                    }
                }
            }
        } catch (e: Exception) {
            "❌ Volume error: ${e.message?.take(50)}"
        }
    }

    /**
     * Open specific Android settings.
     * AI: {"action":"open_settings","text":"wifi"} or "bluetooth" or "display"
     */
    private fun openSettings(action: ActionExecutor.AgentAction, context: Context): String {
        val setting = action.text?.lowercase() ?: "main"

        return try {
            val settingsAction = when {
                setting.contains("wifi") -> android.provider.Settings.ACTION_WIFI_SETTINGS
                setting.contains("bluetooth") -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                setting.contains("display") || setting.contains("brightness") -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
                setting.contains("sound") || setting.contains("volume") -> android.provider.Settings.ACTION_SOUND_SETTINGS
                setting.contains("battery") -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
                setting.contains("location") -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                setting.contains("app") -> android.provider.Settings.ACTION_APPLICATION_SETTINGS
                setting.contains("notification") -> android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                else -> android.provider.Settings.ACTION_SETTINGS
            }

            val intent = Intent(settingsAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "✅ Settings khol diya: $setting"
        } catch (e: Exception) {
            "❌ Settings error: ${e.message?.take(50)}"
        }
    }
}
