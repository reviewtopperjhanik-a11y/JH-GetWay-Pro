package com.example.core.security

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

object MaskingUtils {
    fun maskSecret(secret: String): String {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.length <= 6 -> "••••••"
            trimmed.length <= 12 -> "${trimmed.take(2)}••••${trimmed.takeLast(2)}"
            else -> "${trimmed.take(5)}••••••${trimmed.takeLast(4)}"
        }
    }

    fun sanitizeErrorMessage(message: String?, secretsToStrip: List<String> = emptyList()): String {
        var sanitized = message?.trim().orEmpty()
        if (sanitized.isEmpty()) return "Unknown error"
        secretsToStrip.filter { it.length >= 4 }.forEach { secret ->
            sanitized = sanitized.replace(secret, "[REDACTED]")
        }
        // Strip common key patterns
        sanitized = sanitized
            .replace(Regex("(sk-[A-Za-z0-9_\\-]{8,})"), "[REDACTED_KEY]")
            .replace(Regex("(AIza[A-Za-z0-9_\\-]{12,})"), "[REDACTED_KEY]")
            .replace(Regex("(jhgw_[A-Za-z0-9_\\-]{8,})"), "[REDACTED_TOKEN]")
            .replace(Regex("(Bearer\\s+[A-Za-z0-9._\\-]{8,})", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
            .replace(Regex("([?&]key=)[^&\\s]+", RegexOption.IGNORE_CASE), "$1[REDACTED]")
        return sanitized.take(320)
    }
}

enum class EndpointLocality {
    LOOPBACK,
    PRIVATE_LAN,
    PUBLIC_REMOTE
}

interface DnsResolver {
    @Throws(UnknownHostException::class)
    fun lookup(hostname: String): List<InetAddress>
}

object SystemDnsResolver : DnsResolver {
    override fun lookup(hostname: String): List<InetAddress> = InetAddress.getAllByName(hostname).toList()
}

data class EndpointSecurityResult(
    val allowed: Boolean,
    val locality: EndpointLocality = EndpointLocality.PUBLIC_REMOTE,
    val resolvedAddresses: List<InetAddress> = emptyList(),
    val errorMessage: String? = null
)

data class BindValidationResult(
    val allowed: Boolean,
    val normalizedHost: String,
    val normalizedPort: Int,
    val isLoopback: Boolean,
    val unencryptedLanWarning: Boolean = false,
    val errorMessage: String? = null
)

data class RemoteUrlValidationResult(
    val valid: Boolean,
    val normalizedUrl: String = "",
    val errorMessage: String? = null
)

object NetworkAddressClassifier {
    private val blockedMetadataHosts = setOf(
        "metadata.google.internal",
        "metadata.goog",
        "169.254.169.254",
        "instance-data"
    )

    fun isLoopbackAddress(hostInput: String): Boolean {
        val host = hostInput.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1") {
            return true
        }
        val parts = host.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            return parts[0].toInt() == 127
        }
        return false
    }

    fun isWildcardBindAddress(hostInput: String): Boolean {
        val host = hostInput.trim().removePrefix("[").removeSuffix("]").lowercase()
        return host == "0.0.0.0" || host == "::" || host == "0:0:0:0:0:0:0:0" || host == "*"
    }

    fun isPrivateRfc1918Ip(hostInput: String): Boolean {
        val host = hostInput.trim().lowercase()
        val parts = host.split(".")
        if (parts.size != 4 || !parts.all { it.toIntOrNull() in 0..255 }) return false
        val a = parts[0].toInt()
        val b = parts[1].toInt()
        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            else -> false
        }
    }

    fun isPrivateOrLoopbackHost(urlString: String): Boolean {
        val host = (parseHost(urlString) ?: urlString.trim()).removePrefix("[").removeSuffix("]").lowercase()
        if (isLoopbackAddress(host) || host == "10.0.2.2") return true
        if (host.endsWith(".local")) return true
        if (isPrivateRfc1918Ip(host)) return true
        val bareLiteral = parseIpLiteralOrNull(host) ?: return false
        return bareLiteral.isLoopbackAddress || bareLiteral.isSiteLocalAddress || isIpv6UniqueLocal(bareLiteral)
    }

    fun isRestrictedMetadataOrUnspecified(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) {
            return true
        }
        val bytes = addr.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // 0.0.0.0/8 ("this network"), 169.254.0.0/16 (link-local/cloud metadata), 100.64.0.0/10 (CGNAT)
            if (b0 == 0) return true
            if (b0 == 169 && b1 == 254) return true
            if (b0 == 100 && (b1 and 0xC0) == 64) return true
        } else if (bytes.size == 16) {
            val hostAddr = addr.hostAddress?.lowercase()?.substringBefore('%').orEmpty()
            // Cloud IPv6 metadata endpoints (AWS EC2 fd00:ec2::254)
            if (hostAddr.startsWith("fd00:ec2:")) return true
            // IPv4-mapped IPv6 ::ffff:a.b.c.d
            val isMappedV4 = (0..9).all { bytes[it].toInt() == 0 } &&
                (bytes[10].toInt() and 0xFF) == 0xFF &&
                (bytes[11].toInt() and 0xFF) == 0xFF
            if (isMappedV4) {
                val b0 = bytes[12].toInt() and 0xFF
                val b1 = bytes[13].toInt() and 0xFF
                if (b0 == 0 || (b0 == 169 && b1 == 254) || (b0 == 100 && (b1 and 0xC0) == 64)) return true
            }
        }
        return false
    }

    private fun isIpv6UniqueLocal(addr: InetAddress): Boolean {
        val bytes = addr.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC && !isRestrictedMetadataOrUnspecified(addr)
    }

    fun classifyAddressLocality(addr: InetAddress): EndpointLocality {
        return when {
            addr.isLoopbackAddress -> EndpointLocality.LOOPBACK
            addr.isSiteLocalAddress || isIpv6UniqueLocal(addr) -> EndpointLocality.PRIVATE_LAN
            else -> EndpointLocality.PUBLIC_REMOTE
        }
    }

    fun classifyEndpointLocality(
        urlString: String,
        resolver: DnsResolver = SystemDnsResolver
    ): EndpointLocality {
        val host = (parseHost(urlString) ?: urlString.trim()).removePrefix("[").removeSuffix("]").lowercase()
        if (host.isBlank()) return EndpointLocality.PUBLIC_REMOTE
        if (isLoopbackAddress(host)) return EndpointLocality.LOOPBACK
        if (isPrivateRfc1918Ip(host) || host == "10.0.2.2" || host.endsWith(".local")) return EndpointLocality.PRIVATE_LAN
        parseIpLiteralOrNull(host)?.let { return classifyAddressLocality(it) }
        val resolved = runCatching { resolver.lookup(host) }.getOrNull().orEmpty()
        if (resolved.isNotEmpty()) {
            return when {
                resolved.all { it.isLoopbackAddress } -> EndpointLocality.LOOPBACK
                resolved.all { it.isLoopbackAddress || it.isSiteLocalAddress || isIpv6UniqueLocal(it) } -> EndpointLocality.PRIVATE_LAN
                else -> EndpointLocality.PUBLIC_REMOTE
            }
        }
        return EndpointLocality.PUBLIC_REMOTE
    }

    fun validateEndpointSecurity(
        urlString: String,
        allowLocalHttp: Boolean,
        requireDnsResolution: Boolean = false,
        resolver: DnsResolver = SystemDnsResolver
    ): EndpointSecurityResult {
        val trimmed = urlString.trim()
        if (trimmed.isBlank()) {
            return EndpointSecurityResult(false, errorMessage = "Endpoint URL is blank.")
        }
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return EndpointSecurityResult(false, errorMessage = "Malformed endpoint URL.")
        }
        val scheme = uri.scheme?.lowercase() ?: return EndpointSecurityResult(false, errorMessage = "URL scheme is required.")
        if (scheme != "http" && scheme != "https") {
            return EndpointSecurityResult(false, errorMessage = "Only http:// and https:// schemes are allowed.")
        }
        val rawHost = uri.host?.removePrefix("[")?.removeSuffix("]")?.trim().orEmpty()
        if (rawHost.isBlank()) {
            return EndpointSecurityResult(false, errorMessage = "URL host is missing.")
        }
        if (!uri.userInfo.isNullOrBlank()) {
            return EndpointSecurityResult(false, errorMessage = "Embedded credentials (user:pass@) in URLs are blocked.")
        }
        if (uri.port != -1 && uri.port !in 1..65535) {
            return EndpointSecurityResult(false, errorMessage = "URL port is out of valid range.")
        }
        val normalizedHost = rawHost.lowercase()
        if (normalizedHost in blockedMetadataHosts || normalizedHost.endsWith(".internal")) {
            return EndpointSecurityResult(false, errorMessage = "Access to cloud metadata or internal host '$rawHost' is blocked.")
        }

        // First validate raw IP literals locally without requiring network DNS.
        val literalAddr = parseIpLiteralOrNull(normalizedHost)
        if (literalAddr != null) {
            if (isRestrictedMetadataOrUnspecified(literalAddr)) {
                return EndpointSecurityResult(false, errorMessage = "Restricted link-local, metadata, or unspecified address blocked ($rawHost).")
            }
            val locality = classifyAddressLocality(literalAddr)
            if (scheme == "http" && (!allowLocalHttp || locality == EndpointLocality.PUBLIC_REMOTE)) {
                return EndpointSecurityResult(
                    allowed = false,
                    locality = locality,
                    resolvedAddresses = listOf(literalAddr),
                    errorMessage = if (locality == EndpointLocality.PUBLIC_REMOTE) {
                        "Unencrypted HTTP to public endpoints is blocked; use HTTPS."
                    } else {
                        "Local Network Access is disabled for private HTTP endpoints."
                    }
                )
            }
            if (!allowLocalHttp && locality != EndpointLocality.PUBLIC_REMOTE) {
                return EndpointSecurityResult(
                    allowed = false,
                    locality = locality,
                    resolvedAddresses = listOf(literalAddr),
                    errorMessage = "Private/loopback endpoint blocked while Local Network Access is disabled."
                )
            }
            return EndpointSecurityResult(true, locality, listOf(literalAddr))
        }

        val resolved = try {
            resolver.lookup(normalizedHost)
        } catch (e: UnknownHostException) {
            if (requireDnsResolution) {
                return EndpointSecurityResult(false, errorMessage = "DNS resolution failed for '$rawHost'.")
            }
            emptyList()
        } catch (_: Exception) {
            if (requireDnsResolution) {
                return EndpointSecurityResult(false, errorMessage = "DNS resolution failed for '$rawHost'.")
            }
            emptyList()
        }

        if (resolved.isNotEmpty()) {
            for (addr in resolved) {
                if (isRestrictedMetadataOrUnspecified(addr)) {
                    return EndpointSecurityResult(
                        allowed = false,
                        resolvedAddresses = resolved,
                        errorMessage = "SSRF blocked: '$rawHost' resolves to a restricted metadata, link-local, or unspecified address."
                    )
                }
            }
            val anyPublic = resolved.any { classifyAddressLocality(it) == EndpointLocality.PUBLIC_REMOTE }
            val anyPrivateOrLoopback = resolved.any { classifyAddressLocality(it) != EndpointLocality.PUBLIC_REMOTE }
            val locality = when {
                resolved.all { it.isLoopbackAddress } -> EndpointLocality.LOOPBACK
                !anyPublic -> EndpointLocality.PRIVATE_LAN
                else -> EndpointLocality.PUBLIC_REMOTE
            }

            if (scheme == "http" && (!allowLocalHttp || anyPublic)) {
                return EndpointSecurityResult(
                    allowed = false,
                    locality = locality,
                    resolvedAddresses = resolved,
                    errorMessage = if (anyPublic) {
                        "HTTP cleartext request to public IP is blocked; use HTTPS."
                    } else {
                        "Local Network Access is disabled for private/local HTTP endpoints."
                    }
                )
            }
            if (!allowLocalHttp && anyPrivateOrLoopback) {
                return EndpointSecurityResult(
                    allowed = false,
                    locality = locality,
                    resolvedAddresses = resolved,
                    errorMessage = "DNS rebinding protection blocked private/loopback resolution while Local Network Access is disabled."
                )
            }
            return EndpointSecurityResult(true, locality, resolved)
        }

        // Fallback static classification when offline DNS cannot resolve remote domain and requireDnsResolution == false
        val isLocalSyntax = isPrivateOrLoopbackHost(trimmed)
        val fallbackLocality = if (isLoopbackAddress(normalizedHost)) EndpointLocality.LOOPBACK
        else if (isLocalSyntax) EndpointLocality.PRIVATE_LAN
        else EndpointLocality.PUBLIC_REMOTE

        if (scheme == "http" && (!allowLocalHttp || !isLocalSyntax)) {
            return EndpointSecurityResult(
                allowed = false,
                locality = fallbackLocality,
                errorMessage = "HTTPS is required for remote endpoints (or enable Local Network Access for private hosts)."
            )
        }
        return EndpointSecurityResult(true, fallbackLocality)
    }

    fun pinnedSafeDns(
        allowLocalHttp: Boolean,
        resolver: DnsResolver = SystemDnsResolver
    ): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val norm = hostname.trim().removePrefix("[").removeSuffix("]").lowercase()
            if (norm in blockedMetadataHosts || norm.endsWith(".internal")) {
                throw UnknownHostException("Blocked metadata/internal host: $hostname")
            }
            val addrs = resolver.lookup(hostname)
            if (addrs.isEmpty()) throw UnknownHostException("No addresses resolved for $hostname")
            for (addr in addrs) {
                if (isRestrictedMetadataOrUnspecified(addr)) {
                    throw UnknownHostException("Blocked restricted SSRF IP for $hostname")
                }
                if (!allowLocalHttp && classifyAddressLocality(addr) != EndpointLocality.PUBLIC_REMOTE) {
                    throw UnknownHostException("Blocked private/loopback DNS resolution while Local Network Access is off: $hostname")
                }
            }
            return addrs
        }
    }

    private fun parseIpLiteralOrNull(host: String): InetAddress? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        val isV4Literal = h.split(".").let { parts ->
            parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
        }
        val isV6Literal = h.contains(":")
        if (!isV4Literal && !isV6Literal) return null
        return runCatching {
            val addr = InetAddress.getByName(h)
            if (addr is Inet4Address || addr is Inet6Address) addr else null
        }.getOrNull()
    }

    fun validateLocalGatewayBindConfig(
        hostInput: String,
        port: Int,
        allowLanExposure: Boolean
    ): BindValidationResult {
        val host = hostInput.trim().ifBlank { "127.0.0.1" }
        if (port !in 0..65535 || (port != 0 && port < 1024)) {
            return BindValidationResult(
                allowed = false,
                normalizedHost = host,
                normalizedPort = port,
                isLoopback = false,
                errorMessage = "Gateway port must be between 1024 and 65535."
            )
        }
        if (isWildcardBindAddress(host)) {
            return BindValidationResult(
                allowed = false,
                normalizedHost = host,
                normalizedPort = port,
                isLoopback = false,
                errorMessage = "Wildcard binding ($host) is blocked because unencrypted HTTP would expose credentials on all interfaces. Use 127.0.0.1 or a specific private LAN IP with a TLS reverse proxy."
            )
        }
        if (isLoopbackAddress(host)) {
            return BindValidationResult(
                allowed = true,
                normalizedHost = if (host.equals("localhost", true)) "127.0.0.1" else host,
                normalizedPort = port,
                isLoopback = true,
                unencryptedLanWarning = false
            )
        }
        if (isPrivateRfc1918Ip(host)) {
            if (!allowLanExposure) {
                return BindValidationResult(
                    allowed = false,
                    normalizedHost = host,
                    normalizedPort = port,
                    isLoopback = false,
                    errorMessage = "Local Gateway defaults to loopback-only (127.0.0.1). Enable opt-in LAN Exposure in Gateway settings only when fronted by TLS/tunnel."
                )
            }
            return BindValidationResult(
                allowed = true,
                normalizedHost = host,
                normalizedPort = port,
                isLoopback = false,
                unencryptedLanWarning = true
            )
        }
        // Reject public IPs or arbitrary hostnames for unencrypted local HTTP server binding
        val resolvedPrivate = runCatching {
            val addr = InetAddress.getByName(host)
            !isRestrictedMetadataOrUnspecified(addr) &&
                (addr.isLoopbackAddress || (addr.isSiteLocalAddress && allowLanExposure))
        }.getOrDefault(false)
        if (!resolvedPrivate) {
            return BindValidationResult(
                allowed = false,
                normalizedHost = host,
                normalizedPort = port,
                isLoopback = false,
                errorMessage = "Binding unencrypted local HTTP to non-loopback/public host '$host' is not allowed."
            )
        }
        return BindValidationResult(
            allowed = true,
            normalizedHost = host,
            normalizedPort = port,
            isLoopback = false,
            unencryptedLanWarning = true
        )
    }

    fun validateRemoteGatewayUrl(urlString: String, allowExistingPrivateHttp: Boolean = false): RemoteUrlValidationResult {
        val trimmed = urlString.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return RemoteUrlValidationResult(false, errorMessage = "Remote gateway URL is required.")
        }
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: ""
            val host = uri.host?.trim().orEmpty()
            if (host.isBlank()) {
                return RemoteUrlValidationResult(false, errorMessage = "Remote gateway URL must contain a valid hostname.")
            }
            if (!uri.userInfo.isNullOrBlank()) {
                return RemoteUrlValidationResult(false, errorMessage = "Remote URL must not embed credentials (user:pass@).")
            }
            if (uri.port != -1 && uri.port !in 1..65535) {
                return RemoteUrlValidationResult(false, errorMessage = "Remote URL port is invalid.")
            }
            when (scheme) {
                "https" -> {
                    val sec = validateEndpointSecurity(trimmed, allowLocalHttp = allowExistingPrivateHttp)
                    if (!sec.allowed) RemoteUrlValidationResult(false, errorMessage = sec.errorMessage ?: "Unsafe remote gateway host.")
                    else RemoteUrlValidationResult(true, trimmed)
                }
                "http" -> {
                    if (allowExistingPrivateHttp && isLoopbackAddress(host)) {
                        RemoteUrlValidationResult(true, trimmed)
                    } else {
                        RemoteUrlValidationResult(
                            false,
                            errorMessage = "Remote gateway requires HTTPS to protect API keys and prompts."
                        )
                    }
                }
                else -> RemoteUrlValidationResult(false, errorMessage = "Remote gateway URL must use https://.")
            }
        } catch (_: Exception) {
            RemoteUrlValidationResult(false, errorMessage = "Malformed remote gateway URL.")
        }
    }

    fun isSecureOrAllowedLocalUrl(urlString: String, allowLocalHttp: Boolean): Boolean {
        return validateEndpointSecurity(urlString, allowLocalHttp = allowLocalHttp).allowed
    }

    fun parseHost(urlString: String): String? {
        return try {
            URI(urlString.trim()).host
        } catch (_: Exception) {
            null
        }
    }
}
