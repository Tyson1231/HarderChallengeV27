package name;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record HunterStatePayload(boolean active) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(HarderChallenge.MOD_ID, "hunter_state");

    public static final Type<HunterStatePayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, HunterStatePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    HunterStatePayload::active,
                    HunterStatePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
