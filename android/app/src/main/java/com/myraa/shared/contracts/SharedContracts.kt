package com.myraa.shared.contracts

/** Kotlin wire-model projection of the versioned contracts in shared/contracts.ts. */
object SharedContractVersions {
    const val DATA_SCHEMA = 1
    const val PROTOCOL = 1
    const val OWNER_APPROVAL = 1
}

enum class MemoryCategory(val wireValue: String) {
    IDENTITY("identity"),
    PREFERENCE("preference"),
    GOAL("goal"),
    PROJECT("project"),
    RELATIONSHIP("relationship"),
    EMOTIONAL("emotional"),
    BEHAVIOR("behavior"),
}

data class MemoryRecord(
    val id: String,
    val category: MemoryCategory,
    val text: String,
    val createdAt: String,
    val updatedAt: String,
)

data class MemoryTransaction(
    val action: String,
    val id: String,
    val category: MemoryCategory,
    val text: String,
)

data class DeviceIdentity(val deviceId: String)

interface StableDeviceIdentityProvider {
    fun loadOrCreateDeviceIdentity(): DeviceIdentity
}

data class DeviceSigningKey(val publicKey: String)

interface DeviceKeyLifecycleProvider {
    fun loadOrCreateSigningKey(deviceId: String): DeviceSigningKey
    fun sign(payload: ByteArray): ByteArray
}

interface AndroidKeystoreProvider : DeviceKeyLifecycleProvider

data class OwnerApprovalChallenge(
    val action: String,
    val transactionDigest: String,
    val requestId: String,
    val expiresAt: Long,
    val nonce: String,
)

interface PlatformOwnerApprovalProvider {
    fun requestApproval(challenge: OwnerApprovalChallenge): SignedOwnerApproval
}

data class SignedOwnerApproval(
    val version: Int = SharedContractVersions.OWNER_APPROVAL,
    val action: String,
    val transactionDigest: String,
    val requestId: String,
    val expiresAt: Long,
    val nonce: String,
    val ownerId: String,
    val deviceId: String,
    val signature: String,
)
