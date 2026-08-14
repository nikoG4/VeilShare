package dev.veilshare.core.platform

object FakePlatformCapabilities : PlatformCapabilities {
    override val importPicker = object : ImportPicker { override suspend fun pick(request: ImportRequest) = emptyList<ImportHandle>() }
    override val originalDeletion = object : OriginalDeletion { override suspend fun delete(handle: ImportHandle) = DeleteOriginalResult.Unsupported }
    override val disguise = object : DisguiseController {
        override suspend fun supportedProfiles() = listOf(DisguiseProfile("calculator", "Calculator", DisguiseSurface.CALCULATOR))
        override suspend fun apply(profile: DisguiseProfile) = DisguiseResult.Applied
    }
    override val security = PlatformSecurityCapabilities(false, false, false)
}
