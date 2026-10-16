package ced.cedclient.accessor;

/**
 * Implemented on AvatarRenderState via PlayerRenderStateMixin. Lets
 * PlayerRendererMixin's scale() injection tell apart two distinct cases
 * tagged during extractRenderState():
 *   - isSelf: this render is the local client's own player entity, so the
 *     user-adjustable Scale X/Y/Z sliders apply.
 *   - isHardcodedTarget: this render's IGN has a scale pushed via
 *     CosmeticsSync while HardcodedCosmetics is enabled, so
 *     syncedScaleX/Y/Z applies instead, regardless of PlayerScale's own
 *     slider values or toggle. Takes priority over isSelf.
 */
public interface CedClientPlayerRenderStateAccessor {
    boolean cedclient$isSelf();
    void cedclient$setSelf(boolean value);

    boolean cedclient$isHardcodedTarget();
    void cedclient$setHardcodedTarget(boolean value);

    float cedclient$getSyncedScaleX();
    float cedclient$getSyncedScaleY();
    float cedclient$getSyncedScaleZ();
    void cedclient$setSyncedScale(float x, float y, float z);

    /** Synced halo color (0xRRGGBB), or -1 for no halo. Drawn by HaloLayer. */
    int cedclient$getHaloColor();
    void cedclient$setHaloColor(int color);

    /** Halo animation bit flags (CosmeticsSync HALO_ANIMS order: spin, bob, pulse, rainbow). */
    int cedclient$getHaloAnim();
    void cedclient$setHaloAnim(int flags);
}