package pl.botmod;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;

import java.io.IOException;

/** Odczytuje przedmioty z pakietow kodekiem samej gry (obsluguje wszystkie komponenty przedmiotow). */
public final class McItemReader implements InventoryModel.ItemReader {
    @Override
    public Object read(InventoryModel.Cursor c) throws IOException {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) throw new IOException("brak swiata gry");
        ByteBuf raw = Unpooled.wrappedBuffer(c.data, c.pos, c.data.length - c.pos);
        RegistryByteBuf buf = new RegistryByteBuf(raw, mc.world.getRegistryManager());
        ItemStack stack = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
        c.pos += buf.readerIndex();
        return stack.isEmpty() ? null : stack;
    }
}
