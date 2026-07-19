package name;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record BloodMoonStatePayload(boolean active) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(HarderChallenge.MOD_ID, "blood_moon_state");

    public static final Type<BloodMoonStatePayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, BloodMoonStatePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    BloodMoonStatePayload::active,
                    BloodMoonStatePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
