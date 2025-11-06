package com.namit.presentation_displays

import android.content.ContentValues.TAG
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import com.google.gson.Gson
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/** PresentationDisplaysPlugin */
class PresentationDisplaysPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var flutterEngineChannel: MethodChannel? = null
    private var context: Context? = null
    private val presentations = mutableMapOf<String, PresentationDisplay>()
    private var binaryMessenger: BinaryMessenger? = null
    private val activeEngines = mutableMapOf<String, FlutterEngine>()

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        val messenger = flutterPluginBinding.binaryMessenger
        channel = MethodChannel(messenger, viewTypeId)
        channel.setMethodCallHandler(this)

        eventChannel = EventChannel(messenger, viewTypeEventsId)
        displayManager = flutterPluginBinding.applicationContext
            .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displayConnectedStreamHandler = DisplayConnectedStreamHandler(displayManager)
        eventChannel.setStreamHandler(displayConnectedStreamHandler)
        binaryMessenger = messenger

        if (activeEngines.isNotEmpty()) {
            Log.w(TAG, "Cleaning up ${activeEngines.size} stale FlutterEngines after hot restart")
            activeEngines.values.forEach { it.destroy() }
            activeEngines.clear()
        }
    }

    private fun setup(binaryMessenger: BinaryMessenger, context: Context?) {
        val channel = MethodChannel(binaryMessenger, viewTypeId)
        channel.setMethodCallHandler(this)

        val eventChannel = EventChannel(binaryMessenger, viewTypeEventsId)
        displayManager = context?.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displayConnectedStreamHandler = DisplayConnectedStreamHandler(displayManager)
        eventChannel.setStreamHandler(displayConnectedStreamHandler)
    }

    companion object {
        private const val viewTypeId = "presentation_displays_plugin"
        private const val viewTypeEventsId = "presentation_displays_plugin_events"
        private var displayManager: DisplayManager? = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)

        activeEngines.values.forEach { it.destroy() }
        activeEngines.clear()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        Log.i(TAG, "Channel: method: ${call.method} | arguments: ${call.arguments}")
        when (call.method) {
            "showPresentation" -> {
                try {
                    val obj = JSONObject(call.arguments as String)
                    Log.i(
                        TAG,
                        "Channel: method: ${call.method} | displayId: ${obj.getInt("displayId")} | routerName: ${
                            obj.getString("routerName")
                        }"
                    )
                    val displayId: Int = obj.getInt("displayId")
                    val tag: String = obj.getString("routerName")
                    val existing = presentations[tag]
                    if (existing != null && existing.isShowing) {
                        Log.i(
                            TAG,
                            "Presentation for tag '$tag' already active — skipping creation."
                        )
                        result.success(true)
                        return
                    }

                    val display = displayManager?.getDisplay(displayId)
                    if (display == null) {
                        result.error("404", "Can't find display with displayId = $displayId", null)
                        return
                    }

                    val flutterEngine = activeEngines[tag] ?: createFlutterEngine(tag)
                    if (flutterEngine == null) {
                        result.error("404", "Can't create FlutterEngine for tag = $tag", null)
                        return
                    }
                    flutterEngineChannel =
                        MethodChannel(
                            flutterEngine.dartExecutor.binaryMessenger,
                            "${viewTypeId}_engine"
                        )
                    context?.let { it1 ->
                        val presentation = PresentationDisplay(it1, tag, display)
                        Log.i(TAG, "presentation: $presentation")
                        presentation.show()
                        presentations[tag] = presentation
                        result.success(true)
                    }

                } catch (e: Exception) {
                    result.error(call.method, e.message, null)
                }
            }

            "hidePresentation" -> {
                try {
                    val obj = JSONObject(call.arguments as String)
                    val tag = obj.getString("routerName")

                    presentations[tag]?.let {
                        Log.i(TAG, "Hiding presentation for tag '$tag'")
                        it.dismiss()
                        presentations.remove(tag)
                    }

                    activeEngines[tag]?.let {
                        Log.i(TAG, "Destroying engine for tag '$tag'")
                        it.destroy()
                        activeEngines.remove(tag)
                    }
                    result.success(true)
                } catch (e: Exception) {
                    result.error(call.method, e.message, null)
                }
            }

            "listDisplay" -> {
                val listJson = ArrayList<DisplayJson>()
                val category = call.arguments
                val displays = displayManager?.getDisplays(category as String?)
                if (displays != null) {
                    for (display: Display in displays) {
                        Log.i(TAG, "display: $display")
                        val d = DisplayJson(
                            display.displayId,
                            display.flags,
                            display.rotation,
                            display.name
                        )
                        listJson.add(d)
                    }
                }
                result.success(Gson().toJson(listJson))
            }

            "transferDataToPresentation" -> {
                try {
                    flutterEngineChannel?.invokeMethod("DataTransfer", call.arguments)
                    result.success(true)
                } catch (e: Exception) {
                    result.success(false)
                }
            }
        }
    }

    private fun createFlutterEngine(tag: String): FlutterEngine? {
        if (context == null) return null

        activeEngines[tag]?.let {
            Log.i(TAG, "Reusing existing FlutterEngine for tag: $tag")
            return it
        }

        Log.i(TAG, "Creating new FlutterEngine for tag: $tag")
        FlutterInjector.instance().flutterLoader().startInitialization(context!!)
        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(context!!, null)

        val flutterEngine = FlutterEngine(context!!)
        val path = FlutterInjector.instance().flutterLoader().findAppBundlePath()
        val entrypoint = DartExecutor.DartEntrypoint(path, "secondaryDisplayMain")
        flutterEngine.dartExecutor.executeDartEntrypoint(entrypoint)
        flutterEngine.lifecycleChannel.appIsResumed()

        activeEngines[tag] = flutterEngine
        FlutterEngineCache.getInstance().put(tag, flutterEngine)

        return flutterEngine
    }

    override fun onDetachedFromActivity() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.context = binding.activity
        displayManager = context?.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        binaryMessenger?.let { setup(it, context) }
    }

    override fun onDetachedFromActivityForConfigChanges() {}
}

class DisplayConnectedStreamHandler(private var displayManager: DisplayManager?) :
    EventChannel.StreamHandler {
    private var sink: EventChannel.EventSink? = null
    private var handler: Handler? = null

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                sink?.success(1)
            }

            override fun onDisplayRemoved(displayId: Int) {
                sink?.success(0)
            }

            override fun onDisplayChanged(p0: Int) {}
        }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
        handler = Handler(Looper.getMainLooper())
        displayManager?.registerDisplayListener(displayListener, handler)
    }

    override fun onCancel(arguments: Any?) {
        sink = null
        handler = null
        displayManager?.unregisterDisplayListener(displayListener)
    }
}
