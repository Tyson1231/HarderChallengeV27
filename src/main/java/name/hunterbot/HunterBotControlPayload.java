package name.hunterbot;

import name.HarderChallenge;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-authoritative command sent only to the dedicated Hunter bot client.
 * Live target coordinates are included only while the server confirms line of sight.
 */
public record HunterBotControlPayload(
        int sequence,
        Mode mode,
        String targetName,
        String dimension,
        double x,
        double y,
        double z,
        float targetWidth,
        float targetHeight,
        boolean mayBreak,
        boolean mayPlace
) implements CustomPacketPayload {

    public enum Mode {
        IDLE,
        VISIBLE_PURSUIT,
        LAST_SEEN_SEARCH,
        CANCEL
    }

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(HarderChallenge.MOD_ID, "hunter_bot_control");
    public static final Type<HunterBotControlPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, HunterBotControlPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.sequence());
                        buf.writeVarInt(payload.mode().ordinal());
                        buf.writeUtf(payload.targetName());
                        buf.writeUtf(payload.dimension());
                        buf.writeDouble(payload.x());
                        buf.writeDouble(payload.y());
                        buf.writeDouble(payload.z());
                        buf.writeFloat(payload.targetWidth());
                        buf.writeFloat(payload.targetHeight());
                        buf.writeBoolean(payload.mayBreak());
                        buf.writeBoolean(payload.mayPlace());
                    },
                    buf -> {
                        int sequence = buf.readVarInt();
                        int ordinal = buf.readVarInt();
                        Mode[] modes = Mode.values();
                        Mode mode = ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : Mode.CANCEL;
                        return new HunterBotControlPayload(
                                sequence,
                                mode,
                                buf.readUtf(),
                                buf.readUtf(),
                                buf.readDouble(),
                                buf.readDouble(),
                                buf.readDouble(),
                                buf.readFloat(),
                                buf.readFloat(),
                                buf.readBoolean(),
                                buf.readBoolean()
                        );
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
