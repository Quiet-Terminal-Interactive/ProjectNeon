package com.quietterminal.projectneon.core;

import com.quietterminal.projectneon.PublicAPI;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Handles protocol version mismatches between peers.
 *
 * <p>Provides explicit handling strategies for when a peer sends packets
 * with a different protocol version than expected. Supports:
 * <ul>
 *   <li>Strict mode: reject all mismatched versions</li>
 *   <li>Lenient mode: accept compatible versions within a range</li>
 *   <li>Custom handlers for version-specific logic</li>
 *   <li>Automatic version negotiation suggestions</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * VersionMismatchHandler handler = VersionMismatchHandler.create()
 *     .currentVersion(PacketHeader.VERSION)
 *     .minCompatibleVersion((byte) 1)
 *     .maxCompatibleVersion((byte) 2)
 *     .onMismatch((received, expected) -> {
 *         logger.warn("Version mismatch: received " + received + ", expected " + expected);
 *     });
 *
 * // Check incoming packet
 * VersionMismatchHandler.Result result = handler.check(incomingVersion);
 * if (!result.isAccepted()) {
 *     sendVersionMismatchResponse(result.suggestedAction());
 *     return;
 * }
 * }</pre>
 *
 * @since 1.1
 */
@PublicAPI
public final class VersionMismatchHandler {

    private byte currentVersion;
    private byte minCompatibleVersion;
    private byte maxCompatibleVersion;
    private boolean strictMode;
    private final List<BiConsumer<Byte, Byte>> mismatchListeners = new CopyOnWriteArrayList<>();

    private VersionMismatchHandler() {
        this.currentVersion = PacketHeader.VERSION;
        this.minCompatibleVersion = PacketHeader.VERSION;
        this.maxCompatibleVersion = PacketHeader.VERSION;
        this.strictMode = false;
    }

    /**
     * Creates a new VersionMismatchHandler with default settings.
     */
    public static VersionMismatchHandler create() {
        return new VersionMismatchHandler();
    }

    /**
     * Creates a strict handler that only accepts the current version.
     */
    public static VersionMismatchHandler strict() {
        return new VersionMismatchHandler().strictMode(true);
    }

    /**
     * Creates a lenient handler that accepts a range of versions.
     *
     * @param minVersion minimum acceptable version (inclusive)
     * @param maxVersion maximum acceptable version (inclusive)
     */
    public static VersionMismatchHandler lenient(byte minVersion, byte maxVersion) {
        return new VersionMismatchHandler()
            .minCompatibleVersion(minVersion)
            .maxCompatibleVersion(maxVersion)
            .strictMode(false);
    }

    /**
     * Sets the current protocol version.
     *
     * @param version the current version
     * @return this handler for chaining
     */
    public VersionMismatchHandler currentVersion(byte version) {
        this.currentVersion = version;
        return this;
    }

    /**
     * Sets the minimum compatible version (inclusive).
     *
     * @param version the minimum version to accept
     * @return this handler for chaining
     */
    public VersionMismatchHandler minCompatibleVersion(byte version) {
        this.minCompatibleVersion = version;
        return this;
    }

    /**
     * Sets the maximum compatible version (inclusive).
     *
     * @param version the maximum version to accept
     * @return this handler for chaining
     */
    public VersionMismatchHandler maxCompatibleVersion(byte version) {
        this.maxCompatibleVersion = version;
        return this;
    }

    /**
     * Enables or disables strict mode.
     * In strict mode, only exact version matches are accepted.
     *
     * @param strict true to enable strict mode
     * @return this handler for chaining
     */
    public VersionMismatchHandler strictMode(boolean strict) {
        this.strictMode = strict;
        return this;
    }

    /**
     * Adds a listener called when a version mismatch is detected.
     *
     * @param listener consumer receiving (receivedVersion, expectedVersion)
     * @return this handler for chaining
     */
    public VersionMismatchHandler onMismatch(BiConsumer<Byte, Byte> listener) {
        mismatchListeners.add(listener);
        return this;
    }

    /**
     * Checks if a version is acceptable.
     *
     * @param version the version to check
     * @return result indicating acceptance and suggested action
     */
    public Result check(byte version) {
        if (strictMode) {
            if (version == currentVersion) {
                return Result.accepted(version, currentVersion);
            }
            notifyListeners(version);
            return Result.rejected(version, currentVersion, determineSuggestedAction(version));
        }

        if (version >= minCompatibleVersion && version <= maxCompatibleVersion) {
            if (version != currentVersion) {
                return Result.acceptedWithWarning(version, currentVersion,
                    "Version differs but is compatible");
            }
            return Result.accepted(version, currentVersion);
        }

        notifyListeners(version);
        return Result.rejected(version, currentVersion, determineSuggestedAction(version));
    }

    /**
     * Checks a packet header's version.
     *
     * @param header the packet header to check
     * @return result indicating acceptance and suggested action
     */
    public Result check(PacketHeader header) {
        return check(header.version());
    }

    /**
     * Validates a version and throws if rejected.
     *
     * @param version the version to validate
     * @throws VersionMismatchException if the version is not acceptable
     */
    public void validate(byte version) {
        Result result = check(version);
        if (!result.isAccepted()) {
            throw new VersionMismatchException(result);
        }
    }

    /**
     * Validates a packet header's version and throws if rejected.
     *
     * @param header the packet header to validate
     * @throws VersionMismatchException if the version is not acceptable
     */
    public void validate(PacketHeader header) {
        validate(header.version());
    }

    private SuggestedAction determineSuggestedAction(byte receivedVersion) {
        int received = receivedVersion & 0xFF;
        int current = currentVersion & 0xFF;
        int min = minCompatibleVersion & 0xFF;
        int max = maxCompatibleVersion & 0xFF;

        if (received < min) {
            return new SuggestedAction(
                ActionType.UPGRADE_REQUIRED,
                String.format("Client version %d is too old. Minimum supported: %d, current: %d",
                    received, min, current),
                minCompatibleVersion,
                currentVersion
            );
        }

        if (received > max) {
            return new SuggestedAction(
                ActionType.DOWNGRADE_SUGGESTED,
                String.format("Client version %d is newer than supported. Maximum: %d, current: %d",
                    received, max, current),
                currentVersion,
                maxCompatibleVersion
            );
        }

        return new SuggestedAction(
            ActionType.INCOMPATIBLE,
            String.format("Version %d is not compatible with version %d", received, current),
            currentVersion,
            currentVersion
        );
    }

    private void notifyListeners(byte receivedVersion) {
        for (BiConsumer<Byte, Byte> listener : mismatchListeners) {
            listener.accept(receivedVersion, currentVersion);
        }
    }

    /**
     * Gets the current protocol version.
     */
    public byte getCurrentVersion() {
        return currentVersion;
    }

    /**
     * Gets the minimum compatible version.
     */
    public byte getMinCompatibleVersion() {
        return minCompatibleVersion;
    }

    /**
     * Gets the maximum compatible version.
     */
    public byte getMaxCompatibleVersion() {
        return maxCompatibleVersion;
    }

    /**
     * Checks if strict mode is enabled.
     */
    public boolean isStrictMode() {
        return strictMode;
    }

    /**
     * Creates a CONNECT_DENY payload for version mismatch.
     *
     * @param result the version check result
     * @return a ConnectDeny payload with appropriate message
     */
    public static PacketPayload.ConnectDeny createDenyPayload(Result result) {
        return new PacketPayload.ConnectDeny(result.suggestedAction().message());
    }

    /**
     * Result of a version check.
     */
    @PublicAPI
    public record Result(
        byte receivedVersion,
        byte expectedVersion,
        boolean isAccepted,
        boolean hasWarning,
        String warningMessage,
        SuggestedAction suggestedAction
    ) {
        static Result accepted(byte received, byte expected) {
            return new Result(received, expected, true, false, null, null);
        }

        static Result acceptedWithWarning(byte received, byte expected, String warning) {
            return new Result(received, expected, true, true, warning, null);
        }

        static Result rejected(byte received, byte expected, SuggestedAction action) {
            return new Result(received, expected, false, false, null, action);
        }

        /**
         * Checks if the version was exactly as expected.
         */
        public boolean isExactMatch() {
            return receivedVersion == expectedVersion;
        }

        @Override
        public String toString() {
            if (isAccepted && !hasWarning) {
                return String.format("VersionResult[accepted, version=%d]", receivedVersion & 0xFF);
            }
            if (isAccepted) {
                return String.format("VersionResult[accepted with warning, version=%d, expected=%d: %s]",
                    receivedVersion & 0xFF, expectedVersion & 0xFF, warningMessage);
            }
            return String.format("VersionResult[rejected, version=%d, expected=%d: %s]",
                receivedVersion & 0xFF, expectedVersion & 0xFF, suggestedAction.message());
        }
    }

    /**
     * Suggested action when a version mismatch is detected.
     */
    @PublicAPI
    public record SuggestedAction(
        ActionType type,
        String message,
        byte suggestedMinVersion,
        byte suggestedMaxVersion
    ) {
        @Override
        public String toString() {
            return String.format("SuggestedAction[%s: %s]", type, message);
        }
    }

    /**
     * Types of suggested actions for version mismatches.
     */
    @PublicAPI
    public enum ActionType {
        UPGRADE_REQUIRED,
        DOWNGRADE_SUGGESTED,
        INCOMPATIBLE
    }

    /**
     * Exception thrown when version validation fails.
     */
    @PublicAPI
    public static class VersionMismatchException extends RuntimeException {
        private final Result result;

        public VersionMismatchException(Result result) {
            super(result.suggestedAction() != null ?
                result.suggestedAction().message() :
                "Version mismatch: received " + (result.receivedVersion() & 0xFF) +
                    ", expected " + (result.expectedVersion() & 0xFF));
            this.result = result;
        }

        public Result getResult() {
            return result;
        }

        public byte getReceivedVersion() {
            return result.receivedVersion();
        }

        public byte getExpectedVersion() {
            return result.expectedVersion();
        }

        public Optional<SuggestedAction> getSuggestedAction() {
            return Optional.ofNullable(result.suggestedAction());
        }
    }
}
