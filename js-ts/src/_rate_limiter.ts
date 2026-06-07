const VIOLATION_THRESHOLD = 10;

export class RateLimiter {
  private tokens: number;
  private readonly maxTokens: number;
  private lastRefill: number;
  private violations = 0;

  constructor(maxTokensPerSecond: number) {
    this.maxTokens = maxTokensPerSecond;
    this.tokens = maxTokensPerSecond;
    this.lastRefill = Date.now();
  }

  allowPacket(): boolean {
    this._refill();
    if (this.tokens > 0) {
      this.tokens--;
      return true;
    }
    this.violations++;
    return false;
  }

  get isThrottled(): boolean {
    return this.violations > VIOLATION_THRESHOLD;
  }

  private _refill(): void {
    const now = Date.now();
    if (now - this.lastRefill >= 1000) {
      this.tokens = this.maxTokens;
      this.lastRefill = now;
      if (this.violations > 0) this.violations--;
    }
  }
}
