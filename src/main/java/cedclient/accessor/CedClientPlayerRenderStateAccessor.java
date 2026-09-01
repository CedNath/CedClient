package cedclient.accessor;

/**
 * Implemented on AvatarRenderState via PlayerRenderStateMixin. Lets
 * PlayerRendererMixin's scale() injection tell apart two distinct cases
 * tagged during extractRenderState():
 *   - isSelf: this render is the local client's own player entity, so the
 *     user-adjustable Scale X/Y/Z sliders apply.
 *   - isHardcodedTarget: this render is specifically CedNath, so the fixed
 *     PlayerScale.HARDCODED_SCALE_X/Y/Z applies instead, regardless of
 *     anyone's slider values or the module toggle. Takes priority over
 *     isSelf.
 */
public interface CedClientPlayerRenderStateAccessor {
    boolean cedclient$isSelf();
    void cedclient$setSelf(boolean value);

    boolean cedclient$isHardcodedTarget();
    void cedclient$setHardcodedTarget(boolean value);
}