package dev.veilshare.core.platform

import dev.veilshare.core.model.PlatformFailure

data class ImportHandle(val opaqueId: String) { init { require(opaqueId.isNotBlank()) } }
data class ImportRequest(val allowMedia: Boolean = true, val allowDocuments: Boolean = true)
sealed interface DeleteOriginalResult { data object Deleted : DeleteOriginalResult; data object Unsupported : DeleteOriginalResult; data class Failed(val failure: PlatformFailure) : DeleteOriginalResult }

interface ImportPicker { suspend fun pick(request: ImportRequest): List<ImportHandle> }
interface OriginalDeletion { suspend fun delete(handle: ImportHandle): DeleteOriginalResult }
interface SecretStore
interface BiometricGate { suspend fun authenticate(): Result<Unit> }
interface DisguiseController { suspend fun supportedProfiles(): List<DisguiseProfile>; suspend fun apply(profile: DisguiseProfile): DisguiseResult }
data class DisguiseProfile(val id: String, val label: String, val surface: DisguiseSurface, val iconKey: String? = null)
enum class DisguiseSurface { CALCULATOR, CONVERTER, NOTES }
sealed interface DisguiseResult { data object Applied : DisguiseResult; data object PartiallyApplied : DisguiseResult; data object Unsupported : DisguiseResult }
data class PlatformSecurityCapabilities(val screenshotProtection: Boolean, val biometricUnlock: Boolean, val originalDeletion: Boolean)
interface PlatformCapabilities { val importPicker: ImportPicker; val originalDeletion: OriginalDeletion; val disguise: DisguiseController; val security: PlatformSecurityCapabilities }
