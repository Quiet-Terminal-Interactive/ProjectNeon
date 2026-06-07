import { readFileSync } from "fs";

export class DtlsConfig {
  readonly certfile: string | null;
  readonly keyfile: string | null;
  readonly cafile: string | null;
  readonly serverSide: boolean;
  readonly insecure: boolean;

  private constructor(opts: {
    certfile: string | null;
    keyfile: string | null;
    cafile: string | null;
    serverSide: boolean;
    insecure: boolean;
  }) {
    this.certfile = opts.certfile;
    this.keyfile = opts.keyfile;
    this.cafile = opts.cafile;
    this.serverSide = opts.serverSide;
    this.insecure = opts.insecure;
  }

  static fromKeyStore(certfile: string, keyfile: string): DtlsConfig {
    return new DtlsConfig({ certfile, keyfile, cafile: null, serverSide: true, insecure: false });
  }

  static withTrustStore(cafile: string): DtlsConfig {
    return new DtlsConfig({ certfile: null, keyfile: null, cafile, serverSide: false, insecure: false });
  }

  static insecureTrustAll(): DtlsConfig {
    return new DtlsConfig({ certfile: null, keyfile: null, cafile: null, serverSide: false, insecure: true });
  }

  buildContext(): DtlsContext {
    return new DtlsContext(this);
  }
}

const LIBSSL_CANDIDATES = [
  "libssl.so.3",
  "/usr/lib/libssl.so.3",
  "/usr/lib/x86_64-linux-gnu/libssl.so.3",
  "/usr/local/lib/libssl.so.3",
];

const LIBCRYPTO_CANDIDATES = [
  "libcrypto.so.3",
  "/usr/lib/libcrypto.so.3",
  "/usr/lib/x86_64-linux-gnu/libcrypto.so.3",
  "/usr/local/lib/libcrypto.so.3",
];

const SSL_FILETYPE_PEM = 1;
const SSL_VERIFY_NONE = 0;
const SSL_VERIFY_PEER = 1;

interface OpenSSLBindings {
  DTLS_server_method(): unknown;
  DTLS_client_method(): unknown;
  SSL_CTX_new(method: unknown): unknown;
  SSL_CTX_free(ctx: unknown): void;
  SSL_CTX_set_read_ahead(ctx: unknown, yes: number): void;
  SSL_CTX_use_certificate_file(ctx: unknown, file: string, type: number): number;
  SSL_CTX_use_PrivateKey_file(ctx: unknown, file: string, type: number): number;
  SSL_CTX_set_verify(ctx: unknown, mode: number, cb: null): void;
  SSL_CTX_load_verify_locations(ctx: unknown, cafile: string, capath: null): number;
  SSL_new(ctx: unknown): unknown;
  SSL_free(ssl: unknown): void;
  SSL_set_bio(ssl: unknown, rbio: unknown, wbio: unknown): void;
  SSL_set_accept_state(ssl: unknown): void;
  SSL_set_connect_state(ssl: unknown): void;
  SSL_do_handshake(ssl: unknown): number;
  SSL_is_init_finished(ssl: unknown): number;
  SSL_read(ssl: unknown, buf: Buffer, num: number): number;
  SSL_write(ssl: unknown, buf: Buffer, num: number): number;
  SSL_get_rbio(ssl: unknown): unknown;
  SSL_get_wbio(ssl: unknown): unknown;
  BIO_s_mem(): unknown;
  BIO_new(type: unknown): unknown;
  BIO_read(bio: unknown, buf: Buffer, dlen: number): number;
  BIO_write(bio: unknown, data: Buffer, len: number): number;
}

let _bindings: OpenSSLBindings | null = null;

function getBindings(): OpenSSLBindings {
  if (_bindings) return _bindings;

  // eslint-disable-next-line @typescript-eslint/no-require-imports
  let koffi: typeof import("koffi", { with: { "resolution-mode": "require" } });
  try {
    koffi = require("koffi");
  } catch {
    throw new Error("DTLS requires koffi: npm install koffi");
  }

  function tryLoad(candidates: string[]): ReturnType<typeof koffi.load> {
    for (const path of candidates) {
      try {
        return koffi.load(path);
      } catch {}
    }
    throw new Error(`Could not load OpenSSL library. Tried: ${candidates.join(", ")}`);
  }

  const ssl = tryLoad(LIBSSL_CANDIDATES);
  const crypto = tryLoad(LIBCRYPTO_CANDIDATES);

  const BIO_s_mem = crypto.func("void* BIO_s_mem()");
  const BIO_new = crypto.func("void* BIO_new(void* type)");
  const BIO_read = crypto.func("int BIO_read(void* bio, void* data, int dlen)");
  const BIO_write = crypto.func("int BIO_write(void* bio, void* data, int len)");

  const DTLS_server_method = ssl.func("void* DTLS_server_method()");
  const DTLS_client_method = ssl.func("void* DTLS_client_method()");
  const SSL_CTX_new = ssl.func("void* SSL_CTX_new(void* method)");
  const SSL_CTX_free = ssl.func("void SSL_CTX_free(void* ctx)");
  const SSL_CTX_set_read_ahead = ssl.func("void SSL_CTX_set_read_ahead(void* ctx, int yes)");
  const SSL_CTX_use_certificate_file = ssl.func("int SSL_CTX_use_certificate_file(void* ctx, str file, int type)");
  const SSL_CTX_use_PrivateKey_file = ssl.func("int SSL_CTX_use_PrivateKey_file(void* ctx, str file, int type)");
  const SSL_CTX_set_verify = ssl.func("void SSL_CTX_set_verify(void* ctx, int mode, void* callback)");
  const SSL_CTX_load_verify_locations = ssl.func("int SSL_CTX_load_verify_locations(void* ctx, str cafile, void* capath)");
  const SSL_new = ssl.func("void* SSL_new(void* ctx)");
  const SSL_free = ssl.func("void SSL_free(void* ssl)");
  const SSL_set_bio = ssl.func("void SSL_set_bio(void* ssl, void* rbio, void* wbio)");
  const SSL_set_accept_state = ssl.func("void SSL_set_accept_state(void* ssl)");
  const SSL_set_connect_state = ssl.func("void SSL_set_connect_state(void* ssl)");
  const SSL_do_handshake = ssl.func("int SSL_do_handshake(void* ssl)");
  const SSL_is_init_finished = ssl.func("int SSL_is_init_finished(void* ssl)");
  const SSL_read = ssl.func("int SSL_read(void* ssl, void* buf, int num)");
  const SSL_write = ssl.func("int SSL_write(void* ssl, void* buf, int num)");
  const SSL_get_rbio = ssl.func("void* SSL_get_rbio(void* ssl)");
  const SSL_get_wbio = ssl.func("void* SSL_get_wbio(void* ssl)");

  _bindings = {
    DTLS_server_method: () => DTLS_server_method(),
    DTLS_client_method: () => DTLS_client_method(),
    SSL_CTX_new: (m) => SSL_CTX_new(m),
    SSL_CTX_free: (c) => SSL_CTX_free(c),
    SSL_CTX_set_read_ahead: (c, y) => SSL_CTX_set_read_ahead(c, y),
    SSL_CTX_use_certificate_file: (c, f, t) => SSL_CTX_use_certificate_file(c, f, t) as number,
    SSL_CTX_use_PrivateKey_file: (c, f, t) => SSL_CTX_use_PrivateKey_file(c, f, t) as number,
    SSL_CTX_set_verify: (c, m, cb) => SSL_CTX_set_verify(c, m, cb),
    SSL_CTX_load_verify_locations: (c, f, p) => SSL_CTX_load_verify_locations(c, f, p) as number,
    SSL_new: (c) => SSL_new(c),
    SSL_free: (s) => SSL_free(s),
    SSL_set_bio: (s, r, w) => SSL_set_bio(s, r, w),
    SSL_set_accept_state: (s) => SSL_set_accept_state(s),
    SSL_set_connect_state: (s) => SSL_set_connect_state(s),
    SSL_do_handshake: (s) => SSL_do_handshake(s) as number,
    SSL_is_init_finished: (s) => SSL_is_init_finished(s) as number,
    SSL_read: (s, b, n) => SSL_read(s, b, n) as number,
    SSL_write: (s, b, n) => SSL_write(s, b, n) as number,
    SSL_get_rbio: (s) => SSL_get_rbio(s),
    SSL_get_wbio: (s) => SSL_get_wbio(s),
    BIO_s_mem: () => BIO_s_mem(),
    BIO_new: (t) => BIO_new(t),
    BIO_read: (b, buf, n) => BIO_read(b, buf, n) as number,
    BIO_write: (b, d, n) => BIO_write(b, d, n) as number,
  };
  return _bindings;
}

export class DtlsContext {
  private ctx: unknown;
  private bindings: OpenSSLBindings;

  constructor(config: DtlsConfig) {
    const b = getBindings();
    this.bindings = b;

    const method = config.serverSide ? b.DTLS_server_method() : b.DTLS_client_method();
    const ctx = b.SSL_CTX_new(method);
    if (!ctx) throw new Error("SSL_CTX_new failed");

    b.SSL_CTX_set_read_ahead(ctx, 1);

    if (config.serverSide) {
      if (config.certfile) {
        if (b.SSL_CTX_use_certificate_file(ctx, config.certfile, SSL_FILETYPE_PEM) !== 1) {
          b.SSL_CTX_free(ctx);
          throw new Error(`Failed to load certificate: ${config.certfile}`);
        }
      }
      if (config.keyfile) {
        if (b.SSL_CTX_use_PrivateKey_file(ctx, config.keyfile, SSL_FILETYPE_PEM) !== 1) {
          b.SSL_CTX_free(ctx);
          throw new Error(`Failed to load private key: ${config.keyfile}`);
        }
      }
    } else {
      if (config.insecure) {
        b.SSL_CTX_set_verify(ctx, SSL_VERIFY_NONE, null);
      } else if (config.cafile) {
        if (b.SSL_CTX_load_verify_locations(ctx, config.cafile, null) !== 1) {
          b.SSL_CTX_free(ctx);
          throw new Error(`Failed to load CA file: ${config.cafile}`);
        }
        b.SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, null);
      }
    }

    this.ctx = ctx;
  }

  newSession(serverSide: boolean): DtlsSession {
    return new DtlsSession(this.ctx, serverSide, this.bindings);
  }

  destroy(): void {
    if (this.ctx) {
      this.bindings.SSL_CTX_free(this.ctx);
      this.ctx = null;
    }
  }
}

const _TMP_BUF = Buffer.allocUnsafe(65536);

export class DtlsSession {
  private ssl: unknown;
  private _ready = false;
  private b: OpenSSLBindings;

  constructor(ctx: unknown, serverSide: boolean, bindings: OpenSSLBindings) {
    this.b = bindings;
    const ssl = bindings.SSL_new(ctx);
    if (!ssl) throw new Error("SSL_new failed");

    const bioMem = bindings.BIO_s_mem();
    const rbio = bindings.BIO_new(bioMem);
    const wbio = bindings.BIO_new(bioMem);
    if (!rbio || !wbio) {
      bindings.SSL_free(ssl);
      throw new Error("BIO_new failed");
    }

    bindings.SSL_set_bio(ssl, rbio, wbio);

    if (serverSide) {
      bindings.SSL_set_accept_state(ssl);
    } else {
      bindings.SSL_set_connect_state(ssl);
    }

    this.ssl = ssl;
  }

  get isReady(): boolean {
    return this._ready;
  }

  initiate(): Buffer {
    this.b.SSL_do_handshake(this.ssl);
    return this._drainOutbound();
  }

  receive(data: Buffer): { plaintext: Buffer | null; outbound: Buffer } {
    const rbio = this.b.SSL_get_rbio(this.ssl);
    this.b.BIO_write(rbio, data, data.length);

    let plaintext: Buffer | null = null;

    if (!this._ready) {
      this.b.SSL_do_handshake(this.ssl);
      if (this.b.SSL_is_init_finished(this.ssl)) {
        this._ready = true;
      }
    }

    if (this._ready) {
      const n = this.b.SSL_read(this.ssl, _TMP_BUF, _TMP_BUF.length);
      if (n > 0) {
        plaintext = Buffer.allocUnsafe(n);
        _TMP_BUF.copy(plaintext, 0, 0, n);
      }
    }

    return { plaintext, outbound: this._drainOutbound() };
  }

  wrap(plaintext: Buffer): Buffer {
    this.b.SSL_write(this.ssl, plaintext, plaintext.length);
    return this._drainOutbound();
  }

  destroy(): void {
    if (this.ssl) {
      this.b.SSL_free(this.ssl);
      this.ssl = null;
    }
  }

  private _drainOutbound(): Buffer {
    const wbio = this.b.SSL_get_wbio(this.ssl);
    const n = this.b.BIO_read(wbio, _TMP_BUF, _TMP_BUF.length);
    if (n > 0) {
      const out = Buffer.allocUnsafe(n);
      _TMP_BUF.copy(out, 0, 0, n);
      return out;
    }
    return Buffer.alloc(0);
  }
}
