package com.myraa.android

import android.content.Context
import android.widget.Toast
import com.myraa.shared.contracts.AndroidKeystoreProvider
import com.myraa.shared.contracts.DeviceIdentity
import com.myraa.shared.contracts.DeviceSigningKey
import com.myraa.shared.contracts.OwnerApprovalChallenge
import com.myraa.shared.contracts.PlatformOwnerApprovalProvider
import com.myraa.shared.contracts.SignedOwnerApproval
import com.myraa.shared.contracts.StableDeviceIdentityProvider
import java.io.IOException
import java.util.UUID

class AndroidLocalDeviceIdentityStore(context: Context) : StableDeviceIdentityProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        "device_identity_v1",
        Context.MODE_PRIVATE,
    )

    override fun loadOrCreateDeviceIdentity(): DeviceIdentity = synchronized(IDENTITY_LOCK) {
        val existing = preferences.getString(DEVICE_ID_KEY, null)
        if (existing != null) {
            if (!DEVICE_ID_PATTERN.matches(existing)) {
                throw IOException("Stored Android device identity is malformed.")
            }
            return@synchronized DeviceIdentity(existing)
        }

        val deviceId = UUID.randomUUID().toString()
        if (!preferences.edit().putString(DEVICE_ID_KEY, deviceId).commit()) {
            throw IOException("Could not persist Android device identity.")
        }
        DeviceIdentity(deviceId)
    }

    private companion object {
        val IDENTITY_LOCK = Any()
        const val DEVICE_ID_KEY = "device_id"
        val DEVICE_ID_PATTERN =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
    }
}

class UnavailableAndroidKeystoreSigningProvider : AndroidKeystoreProvider {
    override fun loadOrCreateSigningKey(deviceId: String): DeviceSigningKey {
        throw SecurityException("Android Keystore signing is not integrated; protected operations are disabled.")
    }

    override fun sign(payload: ByteArray): ByteArray {
        throw SecurityException("Android Keystore signing is not integrated; protected operations are disabled.")
    }
}

class UnavailableAndroidOwnerApprovalProvider : PlatformOwnerApprovalProvider {
    override fun requestApproval(challenge: OwnerApprovalChallenge): SignedOwnerApproval {
        throw SecurityException("Android biometric owner approval is not integrated.")
    }
}

enum class AssistantInvocationSource {
    TOUCH,
    VOICE,
}

interface AssistantInvocationEntryPoint {
    fun request(source: AssistantInvocationSource)
}

class UnavailableAssistantInvocationEntryPoint(
    private val context: Context,
) : AssistantInvocationEntryPoint {
    override fun request(source: AssistantInvocationSource) {
        Toast.makeText(context, "MYRAA Assistant is not available yet.", Toast.LENGTH_SHORT).show()
    }
}
