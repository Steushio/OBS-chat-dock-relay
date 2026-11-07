package com.obs.chat.relay.util

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

object QrCodeScannerHelper {

    fun scanQrCode(
        context: Context,
        onSuccess: (targetUrl: String, rawPayload: String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()

            val scanner = GmsBarcodeScanning.getClient(context, options)

            // Ensure Google Play Services scanner module is present
            val moduleInstallClient = ModuleInstall.getClient(context)
            val moduleInstallRequest = ModuleInstallRequest.newBuilder()
                .addApi(scanner)
                .build()

            moduleInstallClient.installModules(moduleInstallRequest)
                .addOnSuccessListener {
                    scanner.startScan()
                        .addOnSuccessListener { barcode ->
                            val raw = barcode.rawValue ?: barcode.displayValue ?: ""
                            if (raw.isBlank()) {
                                onError("Scanned QR code was empty")
                                return@addOnSuccessListener
                            }
                            val targetUrl = parseScannedTarget(raw)
                            onSuccess(targetUrl, raw)
                        }
                        .addOnCanceledListener {
                            // User backed out
                        }
                        .addOnFailureListener { e ->
                            onError(e.message ?: "QR Scan failed")
                        }
                }
                .addOnFailureListener { e ->
                    // Fallback to direct scan start
                    scanner.startScan()
                        .addOnSuccessListener { barcode ->
                            val raw = barcode.rawValue ?: barcode.displayValue ?: ""
                            if (raw.isBlank()) {
                                onError("Scanned QR code was empty")
                                return@addOnSuccessListener
                            }
                            val targetUrl = parseScannedTarget(raw)
                            onSuccess(targetUrl, raw)
                        }
                        .addOnFailureListener { scanErr ->
                            onError(scanErr.message ?: "Failed to scan QR code")
                        }
                }
        } catch (e: Exception) {
            onError(e.message ?: "Scanner initialization error")
        }
    }

    fun parseScannedTarget(raw: String): String {
        val trimmed = raw.trim()
        // 1. Check if payload is structured JSON
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JsonParser.parseString(trimmed).asJsonObject
                if (json.has("wsUrl") && !json.get("wsUrl").isJsonNull) {
                    return json.get("wsUrl").asString
                }
                if (json.has("url") && !json.get("url").isJsonNull) {
                    return json.get("url").asString
                }
                if (json.has("httpUrl") && !json.get("httpUrl").isJsonNull) {
                    val http = json.get("httpUrl").asString
                    return http.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
                }
                if (json.has("host") && !json.get("host").isJsonNull) {
                    val host = json.get("host").asString
                    val port = if (json.has("port") && !json.get("port").isJsonNull) json.get("port").asInt else 443
                    return "$host:$port"
                }
            } catch (e: Exception) {}
        }
        // 2. Direct string or URL
        return trimmed
    }
}
