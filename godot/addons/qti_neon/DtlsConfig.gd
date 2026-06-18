## TLS/DTLS configuration for encrypted relay connections.
##
## Use the static constructors rather than instantiating directly.
## Pass the result to [member NeonConfig.dtls_config].
## If no [DtlsConfig] is provided, connections use plain UDP without encryption.
class_name DtlsConfig
extends RefCounted

## Controls how TLS options are constructed when [method build_tls_options] is called.
enum Mode {
	## The relay is acting as a TLS server using a certificate and private key.
	SERVER,
	## The client verifies the server against a trusted CA certificate.
	CLIENT_TRUSTED,
	## The client accepts any server certificate without verification.
	CLIENT_INSECURE,
}

## The active mode for this configuration.
var mode: Mode
var _cert_path: String
var _key_path: String
var _ca_path: String

## Creates a server-side config from a PEM certificate and private key.
##
## Use this when configuring a [NeonRelay].
## [param cert_path] Path to the PEM certificate file.
## [param key_path] Path to the PEM private key file.
static func from_key_store(cert_path: String, key_path: String) -> DtlsConfig:
	var cfg := DtlsConfig.new()
	cfg.mode = Mode.SERVER
	cfg._cert_path = cert_path
	cfg._key_path = key_path
	return cfg

## Creates a client-side config that verifies the server against a CA certificate.
##
## [param ca_path] Path to the PEM CA certificate file.
static func with_trust_store(ca_path: String) -> DtlsConfig:
	var cfg := DtlsConfig.new()
	cfg.mode = Mode.CLIENT_TRUSTED
	cfg._ca_path = ca_path
	return cfg

## Creates a client-side config that accepts any server certificate without verification.
##
## @experimental Use only for development and testing. Not safe for production.
static func insecure_trust_all() -> DtlsConfig:
	var cfg := DtlsConfig.new()
	cfg.mode = Mode.CLIENT_INSECURE
	return cfg

## Builds a Godot [TLSOptions] object from this configuration.
##
## [param relay_hostname] Required for client modes to perform hostname verification.
func build_tls_options(relay_hostname: String = "") -> TLSOptions:
	match mode:
		Mode.SERVER:
			var key := load(_key_path) as CryptoKey
			var cert := load(_cert_path) as X509Certificate
			return TLSOptions.server(key, cert)
		Mode.CLIENT_TRUSTED:
			var ca := load(_ca_path) as X509Certificate
			return TLSOptions.client(ca, relay_hostname)
		Mode.CLIENT_INSECURE:
			return TLSOptions.client_unsafe()
	return TLSOptions.client_unsafe()
